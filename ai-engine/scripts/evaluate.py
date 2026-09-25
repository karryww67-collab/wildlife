#!/usr/bin/env python
"""在数据集上评测权重，并把**可复现的**指标写回 `meta.json`。

这是让页面上的 mAP 从"一个数字"变成"一个可核验的数字"的关键一步。

写出的 `meta.json` 除了 `model_manager.py` 会读取的指标字段
（`modelName` / `description` / `precisionValue` / `recallValue` / `map50` / `map5095`），
还带上完整溯源：

    metricsVerified   是否由本脚本真实评测产生（人工填的数字没有这一项）
    weightsMd5        被评测权重的 md5 —— 与 /ai/model/status 报告的是同一个值
    datasetSha256     data.yaml 的摘要，锁定"用的哪份数据定义"
    evaluatedAt       评测时间
    command           复现这条指标所需的命令
    environment       ultralytics / torch 版本与设备

有了这些，任何人拿着同一个 `best.pt` 与同一份数据都能复现出同样的指标 ——
而不是只能相信一个写在文件里的数字。

用法：

    python scripts/evaluate.py --weights runs/detect/wildlife-v1.0/weights/best.pt \\
        --data /data/wildlife/data.yaml \\
        --out /models/wildlife-v1.0/meta.json --write-classes
"""
import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone


def file_md5(path: str, chunk_size: int = 1 << 20) -> str:
    """
    与 `ModelManager.file_md5` 保持一致的算法（整文件 md5，1 MiB 分块）。

    刻意再实现一遍而不是 import：本脚本要能在没装 torch 的环境里被阅读/复用，
    而重复四行 md5 代码的风险远低于引入一条不必要的依赖链。
    """
    digest = hashlib.md5()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_sha256(path: str, chunk_size: int = 1 << 20) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _metric(source, *names, default=None):
    """从 ultralytics 的指标对象里取数，取不到就返回 default，不抛异常。"""
    for name in names:
        value = getattr(source, name, None)
        if value is not None:
            try:
                return float(value)
            except (TypeError, ValueError):
                continue
    return default


def main() -> int:
    parser = argparse.ArgumentParser(description="评测权重并写出带溯源的 meta.json")
    parser.add_argument("--weights", required=True, help="要评测的 best.pt")
    parser.add_argument("--data", required=True, help="data.yaml 路径")
    parser.add_argument("--out", required=True, help="meta.json 输出路径")
    parser.add_argument("--split", default="val", help="评测用哪个 split")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", default="auto", help="批大小，或 auto（GPU 按显存自动定批）")
    parser.add_argument("--device", default="auto", help="auto / cpu / GPU 序号（如 0）")
    parser.add_argument("--model-name", default="", help="meta.json 里的 modelName")
    parser.add_argument("--description", default="", help="meta.json 里的 description")
    parser.add_argument(
        "--write-classes",
        action="store_true",
        help="把权重自带的类别名写成同目录的 classes.txt（已存在且不同则拒绝，除非 --force）",
    )
    parser.add_argument("--force", action="store_true", help="允许覆盖已存在的 classes.txt")
    args = parser.parse_args()

    for path, label in ((args.weights, "权重"), (args.data, "data.yaml")):
        if not os.path.exists(path):
            print(f"错误：{label}不存在 {path}", file=sys.stderr)
            return 2

    try:
        import torch
        from ultralytics import YOLO
        import ultralytics
    except ImportError as exc:  # pragma: no cover - 环境问题
        print(f"错误：缺少依赖 {exc}。请在 ai-engine 容器内执行。", file=sys.stderr)
        return 2

    weights_md5 = file_md5(args.weights)
    cuda_ok = bool(torch.cuda.is_available())

    # 与 train.py 保持同一套语义：默认 auto，有 CUDA 就用 0 / 自动定批。
    # 评测与训练用不同设备会引入不一致（批大小还会轻微改变结果，见 README 8.5），
    # 所以这里也把实际选用的值明确打印出来，并原样记进 meta.json。
    device = (args.device or "auto").strip()
    if device.lower() in ("", "auto"):
        device = "0" if cuda_ok else "cpu"
    batch_raw = str(args.batch or "auto").strip().lower()
    if batch_raw in ("", "auto"):
        batch_size = -1 if cuda_ok else 8
    else:
        try:
            batch_size = int(batch_raw)
        except ValueError:
            print(f"警告：--batch {args.batch!r} 无效，改用 auto", file=sys.stderr)
            batch_size = -1 if cuda_ok else 8

    gpu_name = None
    gpu_memory_mb = None
    if cuda_ok:
        try:
            gpu_name = torch.cuda.get_device_name(0)
            gpu_memory_mb = int(
                torch.cuda.get_device_properties(0).total_memory // (1024 * 1024)
            )
        except Exception:  # noqa: BLE001 - 读不到就如实留空，不影响评测
            pass

    print("=" * 70)
    print(f"权重      : {args.weights}")
    print(f"权重 md5  : {weights_md5}")
    print(f"数据集    : {args.data}")
    print(f"设备      : {device}（--device 传的是 {args.device!r}，cuda_available={cuda_ok}）")
    if gpu_name:
        print(f"显卡      : {gpu_name}，{gpu_memory_mb / 1024:.1f} GB 显存")
    print("=" * 70)

    model = YOLO(args.weights)
    metrics = model.val(
        data=args.data,
        split=args.split,
        imgsz=args.imgsz,
        batch=batch_size,
        device=device,
        plots=False,
        verbose=False,
    )

    box = getattr(metrics, "box", None)
    map50 = _metric(box, "map50", "map_50")
    map5095 = _metric(box, "map", "map50_95")
    precision = _metric(box, "mp", "precision")
    recall = _metric(box, "mr", "recall")

    # 权重自带的类别名（YOLO 把 names 存在权重里）
    names = getattr(model, "names", None) or {}
    if isinstance(names, dict):
        class_names = [names[key] for key in sorted(names)]
    else:
        class_names = list(names)

    print()
    print(f"mAP@0.5      : {map50}")
    print(f"mAP@0.5:0.95 : {map5095}")
    print(f"Precision    : {precision}")
    print(f"Recall       : {recall}")
    print(f"类别数       : {len(class_names)}")
    if class_names:
        preview = "、".join(str(item) for item in class_names[:10])
        print(f"类别         : {preview}{'...' if len(class_names) > 10 else ''}")

    payload = {
        "modelName": args.model_name or os.path.basename(os.path.dirname(os.path.dirname(args.weights))) or "wildlife-detector",
        "description": args.description or f"由 scripts/evaluate.py 评测于 {args.data}",
        "precisionValue": precision,
        "recallValue": recall,
        "map50": map50,
        "map5095": map5095,
        # 类别清单：同时写 JSON 数组形式，供 model_manager.class_names() 读取
        "classConfig": json.dumps(class_names, ensure_ascii=False),
        # ── 溯源 ──────────────────────────────────────────────────────────
        "metricsVerified": True,
        "metricsSource": "ai-engine/scripts/evaluate.py",
        "weightsPath": os.path.abspath(args.weights),
        "weightsMd5": weights_md5,
        "weightsSizeBytes": os.path.getsize(args.weights),
        "datasetYaml": os.path.abspath(args.data),
        "datasetSha256": file_sha256(args.data),
        "split": args.split,
        "imgsz": args.imgsz,
        "device": device,
        "batch": batch_size,
        "evaluatedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "environment": {
            "ultralytics": getattr(ultralytics, "__version__", "unknown"),
            "torch": getattr(torch, "__version__", "unknown"),
            "cudaAvailable": cuda_ok,
            # GPU 信息要记全：论文必须写明训练/评测环境，而"torch 2.x + CUDA"
            # 这种粒度不足以复现 —— 不同显卡的显存上限不同，能用的批大小差很多，
            # 而批大小会轻微影响评测结果（见 README 8.5）。
            "cudaVersion": getattr(torch.version, "cuda", None),
            "cudnnVersion": (
                torch.backends.cudnn.version()
                if torch.backends.cudnn.is_available()
                else None
            ),
            "gpuName": gpu_name,
            "gpuMemoryMB": gpu_memory_mb,
            "cpuCount": os.cpu_count(),
        },
        "command": (
            f"python scripts/evaluate.py --weights {args.weights} --data {args.data} "
            f"--out {args.out} --split {args.split} --imgsz {args.imgsz} "
            f"--device {device} --batch {batch_size}"
        ),
    }

    out_path = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
    print(f"\n已写出 {out_path}")

    # ── classes.txt：让"声明的类别"与权重真正一致 ──────────────────────────
    if args.write_classes and class_names:
        classes_path = os.path.join(os.path.dirname(out_path), "classes.txt")
        if os.path.exists(classes_path) and not args.force:
            with open(classes_path, "r", encoding="utf-8") as handle:
                existing = [line.strip() for line in handle if line.strip()]
            if existing != [str(item) for item in class_names]:
                print(
                    f"\n⚠️  {classes_path} 已存在且与权重自带的类别不同，未覆盖。\n"
                    f"    已有 {len(existing)} 个类别，权重自带 {len(class_names)} 个。\n"
                    "    直接覆盖会让该版本\"声明的类别\"与旧记录脱节 —— "
                    "确认无误请加 --force。",
                    file=sys.stderr,
                )
                return 3
        else:
            with open(classes_path, "w", encoding="utf-8") as handle:
                for name in class_names:
                    handle.write(f"{name}\n")
            print(f"已写出 {classes_path}（{len(class_names)} 个类别）")

    print()
    print("下一步：")
    print(f"  1) 把权重放到版本目录：<MODEL_ROOT>/<版本>/best.pt")
    print(f"  2) 体检：python check_weights.py <版本>")
    print(f"  3) 登记到 model_version 表（二选一）：")
    print(f"       - 打开「模型管理」页 → 新增模型版本")
    print(f"       - python scripts/register_model.py --meta {out_path} "
          f"--model-path /models/<版本>/best.pt --version <版本>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())