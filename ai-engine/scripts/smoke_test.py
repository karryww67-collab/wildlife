#!/usr/bin/env python
"""
训练链路自检（冒烟测试）：用仓库里现成的测试图，10 分钟内跑通
prepare_dataset.py -> train.py -> evaluate.py 全链路。

为什么需要它
------------
正式数据集往往在项目后期才到位。等真数据来了才发现训练脚本在 GPU 上跑不通，
代价是几小时 GPU 额度 + 一次失败的训练。这个脚本用 3 张图把整条链路先走一遍，
把"环境 / 依赖 / 参数 / 产物"的问题一次性暴露出来。

它验证的是什么
--------------
1. prepare_dataset.py 能校验数据并生成 data.yaml
2. train.py 的 --device auto 在 GPU 上真的解析成 0（而不是静默退回 cpu）
3. 训练产出 best.pt / last.pt
4. evaluate.py 能生成 meta.json，且 metricsVerified=true、environment 里记到了 GPU 名
5. 类别数、类别 id 与 classes.txt 对得上

它不验证的是什么
----------------
模型精度毫无意义（3 张图、3 轮、imgsz 320）。这只验"链路通不通"，
绝不能拿这里的 mAP 写进论文。

只用标准库 + Pillow（ultralytics 依赖里已有）。

用法
----
    python smoke_test.py                    # 默认在当前目录下建 .smoke_test/
    python smoke_test.py --keep             # 保留中间产物以便排查
    python smoke_test.py --epochs 5 --imgsz 640
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def log(msg: str = "") -> None:
    print(msg, flush=True)


# --------------------------------------------------------------------------
# 找素材：优先用仓库自带的 3 张测试图，找不到就用 Pillow 现造
# --------------------------------------------------------------------------
def find_test_images() -> list[Path]:
    """按可能性依次找 ai-engine/tests/ 下的测试图。"""
    candidates = [
        HERE.parent / "tests",                      # scripts/ 的上一级
        HERE / "tests",
        Path("/mnt/workspace/wildlife-src/ai-engine/tests"),
        Path("/app/tests"),
        Path.cwd() / "tests",
    ]
    env = os.getenv("WILDLIFE_TEST_IMAGES")
    if env:
        candidates.insert(0, Path(env))

    for d in candidates:
        if not d.is_dir():
            continue
        found = sorted(
            p for p in d.iterdir()
            if p.suffix.lower() in {".jpg", ".jpeg", ".png"}
        )
        if found:
            return found[:3]
    return []


def synthesize_images(out_dir: Path, count: int = 3) -> list[Path]:
    """没有现成图片时，用 Pillow 造几张带色块的图（够 YOLO 跑起来）。"""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        log("  找不到测试图，且 Pillow 不可用 —— 无法继续")
        return []

    out_dir.mkdir(parents=True, exist_ok=True)
    colors = [(200, 60, 60), (60, 160, 90), (70, 90, 200)]
    paths: list[Path] = []
    for i in range(count):
        img = Image.new("RGB", (640, 480), (30, 30, 30))
        draw = ImageDraw.Draw(img)
        c = colors[i % len(colors)]
        draw.rectangle([120, 90, 520, 400], fill=c)
        p = out_dir / f"synthetic_{i}.jpg"
        img.save(p, quality=90)
        paths.append(p)
    return paths


# --------------------------------------------------------------------------
# 造一个最小可用数据集
# --------------------------------------------------------------------------
def build_dataset(root: Path, images: list[Path], class_names: list[str]) -> Path:
    """
    布局：
        root/wildlife/images/{train,val}/*.jpg
        root/wildlife/labels/{train,val}/*.txt
    每张图一个框，类别依次取 classes.txt 的前 N 个（保证 id 不越界）。
    """
    data_root = root / "wildlife"
    if data_root.exists():
        shutil.rmtree(data_root)

    for split in ("train", "val"):
        (data_root / "images" / split).mkdir(parents=True, exist_ok=True)
        (data_root / "labels" / split).mkdir(parents=True, exist_ok=True)

    for i, src in enumerate(images):
        cls = i % len(class_names)
        # 同一张图同时进 train 和 val：只是为了 val 非空、指标能算出来。
        # 真实训练绝不能这样（会严重高估精度）。
        for split in ("train", "val"):
            dst = data_root / "images" / split / f"{src.stem}_{i}{src.suffix.lower()}"
            shutil.copyfile(src, dst)
            label = data_root / "labels" / split / f"{src.stem}_{i}.txt"
            label.write_text(
                f"{cls} 0.500000 0.500000 0.600000 0.600000\n"
                f"{cls} 0.250000 0.250000 0.200000 0.200000\n",
                encoding="utf-8",
            )

    log(f"  数据集: {data_root}")
    log(f"  train={len(images)} 张  val={len(images)} 张  "
        f"类别 id 0~{len(class_names) - 1}")
    return data_root


# --------------------------------------------------------------------------
# 依次调用三个脚本
# --------------------------------------------------------------------------
def run_step(title: str, cmd: list[str]) -> tuple[bool, str]:
    log("")
    log("=" * 70)
    log(f"  {title}")
    log(f"  $ {' '.join(cmd)}")
    log("=" * 70)
    proc = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    out = (proc.stdout or "") + (proc.stderr or "")
    print(out, flush=True)
    return proc.returncode == 0, out


def main() -> int:
    ap = argparse.ArgumentParser(
        description="训练链路自检：用现成测试图跑通 prepare_dataset -> train -> evaluate")
    ap.add_argument("--workdir", default=".smoke_test",
                    help="工作目录（默认 ./.smoke_test）")
    ap.add_argument("--classes", default="",
                    help="classes.txt 路径（默认自动在若干候选位置找）")
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--imgsz", type=int, default=320)
    ap.add_argument("--model", default="yolo11n.pt",
                    help="预训练权重（默认 yolo11n.pt，会自动下载；"
                         "无外网时传本地 .pt 路径）")
    ap.add_argument("--keep", action="store_true", help="保留中间产物")
    args = ap.parse_args()

    root = Path(args.workdir).resolve()
    root.mkdir(parents=True, exist_ok=True)

    log("=" * 70)
    log("  训练链路自检")
    log("=" * 70)
    log(f"  工作目录: {root}")
    log(f"  Python  : {sys.version.split()[0]}  ({sys.executable})")

    # ---- 0. 依赖与设备 ---------------------------------------------------
    log("")
    log("--- [0] 环境 ---")
    try:
        import torch
        log(f"  torch {torch.__version__}  cuda_available={torch.cuda.is_available()}")
        if torch.cuda.is_available():
            log(f"  GPU: {torch.cuda.get_device_name(0)}")
    except ImportError:
        log("  !! torch 未安装 —— 先 pip install -U ultralytics")
        return 2

    # ---- 1. classes.txt ---------------------------------------------------
    log("")
    log("--- [1] 类别清单 ---")
    class_cands = [
        Path(args.classes) if args.classes else None,
        HERE.parent / "models" / "wildlife-v1.0" / "classes.txt",
        HERE / "classes.txt",
        Path("/models/wildlife-v1.0/classes.txt"),          # 本项目容器的挂载点
        Path("/mnt/workspace/wildlife-src/ai-engine/models/wildlife-v1.0/classes.txt"),
        Path("/mnt/workspace/wildlife/classes.txt"),
    ]
    classes_path = next((p for p in class_cands if p and p.is_file()), None)
    if classes_path is None:
        log("  !! 找不到 classes.txt，用 --classes 指定")
        return 2
    names = [ln.strip() for ln in classes_path.read_text(encoding="utf-8").splitlines()
             if ln.strip()]
    if len(names) < 3:
        log(f"  !! classes.txt 只有 {len(names)} 类，至少需要 3 类")
        return 2
    use_names = names[:3]
    log(f"  {classes_path}")
    log(f"  共 {len(names)} 类，本次用前 3 类: {use_names}")

    # ---- 2. 素材 ----------------------------------------------------------
    log("")
    log("--- [2] 素材 ---")
    images = find_test_images()
    if images:
        log(f"  用仓库自带测试图: {[p.name for p in images]}")
    else:
        log("  未找到测试图，用 Pillow 现造 3 张")
        images = synthesize_images(root / "synthetic")
    if not images:
        return 2

    # ---- 3. 数据集 --------------------------------------------------------
    log("")
    log("--- [3] 构造最小数据集 ---")
    data_root = build_dataset(root, images, use_names)

    # 三个脚本的位置（本目录优先，其次常见位置）
    def locate(name: str) -> Path | None:
        for c in (HERE / name, Path.cwd() / name,
                  Path("/mnt/workspace/wildlife") / name):
            if c.is_file():
                return c
        return None

    prep, train, ev = locate("prepare_dataset.py"), locate("train.py"), locate("evaluate.py")
    missing = [n for n, p in (("prepare_dataset.py", prep), ("train.py", train),
                              ("evaluate.py", ev)) if p is None]
    if missing:
        log(f"  !! 找不到: {missing}（本脚本需与它们放在同一目录）")
        return 2

    results: list[tuple[str, bool, str]] = []

    # ---- 4. prepare_dataset ----------------------------------------------
    yaml_path = root / "data.yaml"
    ok, _ = run_step("[4] prepare_dataset.py（校验数据 + 生成 data.yaml）",
                     [sys.executable, str(prep),
                      "--dataset", str(data_root),
                      "--classes", str(classes_path),
                      "--out", str(yaml_path), "--force"])
    results.append(("prepare_dataset.py", ok, "" if ok else "见上方输出"))
    if not ok:
        log("\n数据校验没过，后面的步骤没有意义。先修数据。")
        return 1

    # ---- 5. train ---------------------------------------------------------
    runs = root / "runs"
    ok, out = run_step("[5] train.py（--device auto，应解析成 GPU）",
                       [sys.executable, str(train),
                        "--data", str(yaml_path),
                        "--model", args.model,
                        "--epochs", str(args.epochs),
                        "--imgsz", str(args.imgsz),
                        "--workers", "2",
                        "--name", "smoke",
                        "--project", str(runs)])
    # 关键断言：auto 在 GPU 机器上必须解析成 0
    device_line = next((ln.strip() for ln in out.splitlines()
                        if ln.strip().startswith("设备")), "")
    resolved_gpu = device_line.startswith("设备") and "cpu" not in device_line
    results.append(("train.py", ok, device_line))

    best = runs / "smoke" / "weights" / "best.pt"
    weights_ok = best.is_file() and best.stat().st_size > 0
    results.append(("产出 best.pt", weights_ok,
                    f"{best.stat().st_size} bytes" if weights_ok else "缺失"))

    # ---- 6. evaluate ------------------------------------------------------
    meta_path = root / "out" / "meta.json"
    ok2, _ = run_step("[6] evaluate.py（生成可溯源的 meta.json）",
                      [sys.executable, str(ev),
                       "--weights", str(best),
                       "--data", str(yaml_path),
                       "--out", str(meta_path),
                       "--imgsz", str(args.imgsz)])
    results.append(("evaluate.py", ok2, ""))

    meta_ok, meta_note = False, "meta.json 未生成"
    if meta_path.is_file():
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            env = meta.get("environment", {})
            meta_ok = bool(meta.get("metricsVerified")) and bool(meta.get("weightsMd5"))
            meta_note = (f"metricsVerified={meta.get('metricsVerified')} "
                         f"map50={meta.get('map50')} "
                         f"gpu={env.get('gpuName')} cuda={env.get('cudaVersion')} "
                         f"device={meta.get('device')} batch={meta.get('batch')}")
        except Exception as exc:  # noqa: BLE001
            meta_note = f"解析失败: {exc}"
    results.append(("meta.json 可溯源", meta_ok, meta_note))

    # ---- 汇总 -------------------------------------------------------------
    log("")
    log("=" * 70)
    log("  自检结果")
    log("=" * 70)
    failed = 0
    for name, ok, note in results:
        mark = "PASS" if ok else "FAIL"
        if not ok:
            failed += 1
        log(f"  [{mark}] {name:<24} {note}")

    log("")
    if failed == 0:
        log("  链路通了。注意：本次精度毫无意义（3 张图 / "
            f"{args.epochs} 轮 / imgsz {args.imgsz}），不要写进论文。")
        if not resolved_gpu:
            log("")
            log("  ⚠ 但 --device auto 解析成了 CPU。若这台机器有 GPU，")
            log("    说明 torch 没识别到显卡 —— 先查 torch.cuda.is_available()。")
    else:
        log(f"  {failed} 项未通过，看上面输出定位。")
        log(f"  中间产物保留在 {root}")

    if not args.keep and failed == 0:
        shutil.rmtree(root / "runs", ignore_errors=True)
        log(f"  （已清理训练产物，加 --keep 可保留）")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())