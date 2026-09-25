#!/usr/bin/env python
"""在自备数据集上微调 YOLO 权重，产出 `best.pt`。

本脚本是 ultralytics 的一层薄封装，额外做三件事：

1. **开训前把实情讲清楚** —— 数据在哪、几个类别、跑在什么设备上、大概要多久；
2. **对明显不合理的配置给出警告** —— 数据集太小、epoch 太少、CPU 上硬跑长训练，
   这些都不会报错，只会静默产出一个"指标看着还行但毫无意义"的权重；
3. **训完打印 best.pt 的路径与 md5**，并给出后续步骤（评测 → 登记 → 放置权重）。

## 关于算力

训练建议放在**有 GPU 的环境**（例如魔搭社区 Notebook 的 8 核 / 32GB / 24G 显存实例），
本机与 ai-engine 容器内都没有可用 GPU（`torch.cuda.is_available()` 为 False）。

- **GPU 上**：`--device auto` 会选 0，`--batch auto` 交给 ultralytics 的 AutoBatch
  按显存自动定批（占用约 60% 显存），AMP 默认开启。24G 显存跑 yolo11n@640 通常
  一轮只需几分钟。
- **CPU 上**：每图每 epoch 约 0.1~0.5 秒，几千张图跑 100 轮是数十小时 ——
  只适合 `--epochs 3` 验证链路。

`--device` 与 `--batch` 默认都是 `auto`，**故意不默认成 cpu/8**：在 24G 显卡的机器上
默认 cpu 不会报错，只会把 20 分钟变成十几小时，是最该避免的沉默错误。
实际选用了什么会明确打印出来。

具体操作见同目录的《魔搭Notebook训练指南.md》。

用法：

    python scripts/train.py --data /data/wildlife/data.yaml --model yolo11n.pt \\
        --epochs 100 --imgsz 640 --name wildlife-v1.0          # GPU 上全部走 auto
    python scripts/train.py --data /data/wildlife/data.yaml --epochs 3 --device cpu
"""
import argparse
import hashlib
import os
import sys


def md5_of(path: str) -> str:
    digest = hashlib.md5()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dataset_image_count(data_yaml: str) -> int:
    """
    粗略统计数据集图片数，只用于给出时长预期。

    刻意不引入 PyYAML：解析失败就算了，这个数字只影响提示信息，不影响训练。
    """
    dataset_root = ""
    try:
        with open(data_yaml, "r", encoding="utf-8") as handle:
            for line in handle:
                stripped = line.strip()
                if stripped.startswith("path:"):
                    dataset_root = stripped.split(":", 1)[1].strip().strip("\"'")
                    break
    except OSError:
        return 0

    total = 0
    for split in ("train", "val", "valid", "test"):
        image_dir = os.path.join(dataset_root, "images", split)
        if not os.path.isdir(image_dir):
            continue
        total += sum(
            1
            for name in os.listdir(image_dir)
            if name.lower().endswith((".jpg", ".jpeg", ".png", ".bmp", ".webp"))
        )
    return total


def resolve_device(requested: str, cuda_ok: bool) -> str:
    """
    解析 `--device`。默认 `auto`：有 CUDA 就用 `0`，否则用 `cpu`。

    **故意不把默认值写成 `cpu`**：在 24G 显卡的机器上，默认 cpu 不会报错，
    只会把 20 分钟的训练变成十几小时 —— 这正是最该避免的沉默错误。
    实际选用了什么会在开训前打印出来。
    """
    value = (requested or "auto").strip().lower()
    if value in ("", "auto"):
        return "0" if cuda_ok else "cpu"
    return requested.strip()


def resolve_batch(requested: str, cuda_ok: bool) -> int:
    """
    解析 `--batch`。默认 `auto`：

    - GPU：交给 ultralytics 的 AutoBatch（`-1`，按显存自动定批，约占 60% 显存）；
    - CPU：用 8。CPU 上批越大峰值内存越高，而收益远不如 GPU。

    批大小不只是速度问题：BatchNorm 的统计量与小批噪声有关，批太小也影响收敛。
    """
    value = str(requested or "auto").strip().lower()
    if value in ("", "auto"):
        return -1 if cuda_ok else 8
    try:
        return int(value)
    except ValueError:
        print(f"警告：--batch {requested!r} 不是整数也不是 auto，改用 auto", file=sys.stderr)
        return -1 if cuda_ok else 8


def gpu_label(torch_module) -> str:
    """显卡名称与显存，用于开训前如实说明环境（论文需要写明训练环境）。"""
    try:
        props = torch_module.cuda.get_device_properties(0)
        return (
            f"{torch_module.cuda.get_device_name(0)}，"
            f"{props.total_memory / 1024 ** 3:.1f} GB 显存"
        )
    except Exception:  # noqa: BLE001 - 读不到就如实说明，不影响训练
        return "（读取显卡信息失败）"


def configure_cjk_font() -> str:
    """
    让训练曲线与混淆矩阵里的中文类别名能正常显示。

    matplotlib 的默认字体（DejaVu Sans）**没有 CJK 字形**，中文类别名会被画成方框。
    日志里的表现是一串容易被忽略的警告：

        UserWarning: Glyph 37326 (\\N{CJK UNIFIED IDEOGRAPH-91CE}) missing from font(s) DejaVu Sans.

    后果是论文里那张混淆矩阵满屏方块 —— 而训练本身完全正常，
    很难联想到是字体问题。所以这里主动查一次字体并给出结论。

    返回一句可直接打印的结论：装了中文字体就启用，没装就给出安装命令。
    """
    candidates = [
        "Noto Sans CJK JP",
        "Noto Sans CJK SC",
        "Source Han Sans SC",
        "WenQuanYi Zen Hei",
        "WenQuanYi Micro Hei",
        "SimHei",
        "Microsoft YaHei",
        "Arial Unicode MS",
    ]
    try:
        import matplotlib
        from matplotlib import font_manager
    except ImportError:
        return ""

    try:
        available = {font.name for font in font_manager.fontManager.ttflist}
    except Exception:  # noqa: BLE001 - 查不到就当作没有，不影响训练
        return ""

    picked = [name for name in candidates if name in available]
    if not picked:
        return (
            "未找到中文字体：训练曲线与混淆矩阵里的中文类别名会显示成方框。\n"
            "    安装后重跑即可：apt-get install -y fonts-noto-cjk && rm -rf ~/.cache/matplotlib"
        )

    matplotlib.rcParams["font.sans-serif"] = picked + ["DejaVu Sans"]
    matplotlib.rcParams["axes.unicode_minus"] = False
    return f"已启用中文字体：{picked[0]}"


def main() -> int:
    parser = argparse.ArgumentParser(description="微调 YOLO 野生动物识别权重")
    parser.add_argument("--data", required=True, help="data.yaml 路径")
    parser.add_argument("--model", default="yolo11n.pt", help="基础权重（预训练或已有 best.pt）")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", default="auto", help="批大小，或 auto（GPU 按显存自动定批）")
    parser.add_argument("--device", default="auto", help="auto / cpu / GPU 序号（如 0）")
    parser.add_argument("--project", default="runs/detect", help="输出根目录")
    parser.add_argument("--name", default="wildlife", help="本次训练的输出子目录名")
    parser.add_argument("--seed", type=int, default=0, help="随机种子，保证可复现")
    parser.add_argument("--workers", type=int, default=2, help="数据加载进程数（8 核机器建议 4）")
    parser.add_argument("--patience", type=int, default=50, help="早停耐心值")
    parser.add_argument("--save-period", type=int, default=0, help="每 N 轮另存一个检查点（0=只存 last.pt）")
    parser.add_argument("--resume", action="store_true", help="从上次中断处继续")
    args = parser.parse_args()

    if not os.path.exists(args.data):
        print(f"错误：data.yaml 不存在 {args.data}", file=sys.stderr)
        print("      先用 scripts/prepare_dataset.py 生成它。", file=sys.stderr)
        return 2

    try:
        import torch
        from ultralytics import YOLO
    except ImportError as exc:  # pragma: no cover - 环境问题
        print(f"错误：缺少依赖 {exc}。请在 ai-engine 容器内执行，", file=sys.stderr)
        print("      或安装 requirements.txt 后再跑。", file=sys.stderr)
        return 2

    cuda_ok = torch.cuda.is_available()
    image_count = dataset_image_count(args.data)
    device = resolve_device(args.device, cuda_ok)
    batch_size = resolve_batch(args.batch, cuda_ok)

    print("=" * 70)
    print(f"数据集      : {args.data}")
    print(f"训练图片数  : {image_count if image_count else '未知（无法解析 data.yaml）'}")
    print(f"基础权重    : {args.model}")
    print(f"设备        : {device}（--device 传的是 {args.device!r}，cuda_available={cuda_ok}）")
    if cuda_ok:
        print(f"显卡        : {gpu_label(torch)}")
    batch_shown = "auto（按显存自动定批）" if batch_size == -1 else str(batch_size)
    print(f"轮数/批大小 : {args.epochs} epochs / batch {batch_shown} / imgsz {args.imgsz}")
    print(f"输出目录    : {os.path.join(args.project, args.name)}")
    if cuda_ok and device == "cpu":
        print("提示：检测到可用 GPU，但 --device 仍是 cpu；要提速请用 --device 0 或 auto")
    font_note = configure_cjk_font()
    if font_note:
        print(f"图表字体    : {font_note}")
    print("=" * 70)

    # ── 诚实警告：这些配置不会报错，只会产出一个没有意义的权重 ──────────────
    warnings = []
    if image_count and image_count < 200:
        warnings.append(
            f"训练集只有 {image_count} 张图 —— 这个量级训出来的权重，指标不具备统计意义，"
            "论文里不宜作为「模型性能」引用"
        )
    if args.epochs < 10:
        warnings.append(
            f"只训 {args.epochs} 个 epoch —— 适合用来验证链路是否跑通，不是一次真正的训练"
        )
    if not cuda_ok and args.epochs >= 50:
        warnings.append(
            f"CPU 上跑 {args.epochs} 个 epoch，按每图每 epoch 0.1~0.5 秒估算，"
            f"可能耗时数十小时；建议先 --epochs 3 验证链路"
        )
    if warnings:
        print()
        for warning in warnings:
            print(f"⚠️  {warning}")
        print()

    # ── GPU 环境下的两点提醒：都不会报错，但会让成果丢失或训练卡住 ──────────
    if cuda_ok:
        run_dir = os.path.join(args.project, args.name)
        print("GPU 环境提醒：")
        print(f"  · 首次运行会联网下载基础权重 {args.model}；若环境无外网（部分 Notebook 受限），")
        print("    请先把 .pt 传上去，然后用 --model /path/to/yolo11n.pt")
        print(f"  · 训练产物在 {run_dir}/ 下。魔搭 Notebook 等临时实例**关闭后只有 .ipynb 保留**，")
        print("    其它文件与文件夹都不会被保存（魔搭官方开发者钉群的答复）——")
        print("    训完请立即取回 weights/best.pt，并跑 scripts/evaluate.py 生成 meta.json，")
        print("    否则这批指标将来无法溯源（meta.json 里没有权重 md5 与数据集哈希）")
        if args.save_period <= 0:
            print(f"  · 未设 --save-period：ultralytics 每轮更新 last.pt，可 --resume 续训；")
            print("    长训练可加 --save-period 10 多留几个按轮次编号的检查点")
        print()

    model = YOLO(args.model)
    results = model.train(
        data=args.data,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=batch_size,
        device=device,
        project=args.project,
        name=args.name,
        seed=args.seed,
        workers=args.workers,
        patience=args.patience,
        save_period=args.save_period,
        resume=args.resume,
        exist_ok=True,
    )

    save_dir = str(getattr(results, "save_dir", os.path.join(args.project, args.name)))
    best = os.path.join(save_dir, "weights", "best.pt")
    last = os.path.join(save_dir, "weights", "last.pt")

    print()
    print("=" * 70)
    if os.path.exists(best):
        print(f"最佳权重 : {best}")
        print(f"大小     : {os.path.getsize(best)} bytes")
        print(f"md5      : {md5_of(best)}")
    else:
        print(f"未找到 best.pt（预期在 {best}）", file=sys.stderr)
    if os.path.exists(last):
        print(f"最后权重 : {last}")
    print("=" * 70)
    print()
    print("下一步：")
    print("  1) 评测并在权重旁写出带溯源的 meta.json：")
    print(f"       python scripts/evaluate.py --weights {best} --data {args.data} \\")
    print(f"           --out <MODEL_ROOT>/{args.name}/meta.json --write-classes")
    print("  2) 把 best.pt 与 classes.txt 一起放进 <MODEL_ROOT>/<版本>/：")
    print(f"       cp {best} <MODEL_ROOT>/{args.name}/best.pt")
    print("  3) 体检确认不再回退：")
    print(f"       python check_weights.py {args.name}")
    print("  4) 在「模型管理」页登记/启用该版本（或 POST /api/models）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())