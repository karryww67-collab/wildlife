#!/usr/bin/env python
"""
推理性能基准：实测不同 batch 大小下的单张耗时，为论文提供可复现的性能数据。

为什么需要它
------------
README 第 8.5 节记录了 CPU 批量的实测值（batch 1 为 508~768 ms/张，batch 3 为
74.6~75.7 ms/张），但当时没有留下脚本，换一台机器无法复现，也没有记录重复次数
与预热方式。本脚本把测量过程固化：同一组图、同一组 batch、同样的预热与重复次数，
任何人执行都能得到口径一致的数字。

它测的是什么
------------
只测**推理环节本身**——ultralytics ``model.predict`` 的墙钟耗时，包含批内解码与
letterbox，不含上传、落盘、Redis 入队、结果入库。

因此：论文若要写"系统端到端吞吐"，不能用这里的数字直接代替，只能用单张耗时
乘以图像数再叠加其它环节做外推，并注明是外推。

它不测什么
----------
不验证精度。这里输出的检测框只用来确认模型确实在工作，数值本身没有论文价值。

用法
----
    python scripts/bench_inference.py \\
        --weights /models/wildlife-v1.0/best.pt \\
        --images /app/tests --batches 1 3 8 --repeats 3 --imgsz 640

结果既打印成表格，也以 JSON 形式输出，便于存档或粘进论文。

只用标准库 + ultralytics（其依赖里已含 Pillow）。
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import time
from pathlib import Path

IMG_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def log(msg: str = "") -> None:
    print(msg, flush=True)


def collect_images(src: str) -> list[Path]:
    """--images 可以传目录，也可以传多个文件路径。"""
    p = Path(src)
    if p.is_dir():
        return sorted(q for q in p.iterdir() if q.suffix.lower() in IMG_SUFFIXES)
    if p.is_file():
        return [p]
    return []


def tile_pool(distinct: list[Path], batches: list[int]) -> list[Path]:
    """把去重后的原图平铺到池大小，使每个 batch 都能整除池大小。

    池大小取所有 batch 的最小公倍数，再向上取到不小于原图数量的整数倍。
    这样每块都是满的，不会出现"最后一块只有一张"的偏差。
    """
    target = 1
    for b in batches:
        target = target * b // math.gcd(target, b)
    size = max(target, math.ceil(len(distinct) / target) * target)
    return [distinct[i % len(distinct)] for i in range(size)]


def chunked(seq: list[Path], n: int) -> list[list[Path]]:
    return [seq[i:i + n] for i in range(0, len(seq), n)]


def main() -> int:
    ap = argparse.ArgumentParser(description="推理性能基准（batch 大小 vs 单张耗时）")
    ap.add_argument("--weights", required=True, help="权重路径")
    ap.add_argument("--images", action="append", required=True,
                    help="图片目录或文件，可重复传多次")
    ap.add_argument("--batches", type=int, nargs="+", default=[1, 3, 8],
                    help="要测的 batch 大小（默认 1 3 8）")
    ap.add_argument("--repeats", type=int, default=3, help="每个 batch 的重复次数（默认 3）")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--device", default="", help="留空则用 ultralytics 自动判定")
    ap.add_argument("--warmup", type=int, default=1, help="每个 batch 的预热次数（不计时）")
    args = ap.parse_args()

    distinct: list[Path] = []
    for s in args.images:
        distinct.extend(collect_images(s))
    distinct = sorted({p.resolve() for p in distinct})

    pool = tile_pool(distinct, sorted(args.batches))

    log("=" * 72)
    log("  推理性能基准")
    log("=" * 72)
    if not distinct:
        log("  !! 没有找到任何图片，检查 --images")
        return 2
    log(f"  原图池      : {len(distinct)} 张")
    for p in distinct:
        log(f"      {p.name}  ({p.stat().st_size} B)")
    log(f"  权重        : {args.weights}")
    log(f"  batch 列表  : {args.batches}")
    log(f"  重复次数    : {args.repeats}（另有 {args.warmup} 次预热不计时）")
    log(f"  imgsz       : {args.imgsz}")

    try:
        import torch
        from ultralytics import YOLO
    except ImportError as exc:  # noqa: BLE001
        log(f"  !! 依赖缺失: {exc}")
        return 2

    threads = torch.get_num_threads()
    log(f"  torch       : {torch.__version__}  threads={threads}  "
        f"cuda={torch.cuda.is_available()}")
    log("")

    model = YOLO(args.weights)
    names = model.names
    log(f"  模型类别数  : {len(names)}")

    predict_kw = {"imgsz": args.imgsz, "conf": 0.25, "iou": 0.5, "max_det": 300}
    if args.device:
        predict_kw["device"] = args.device

    rows: list[dict] = []
    for b in sorted(args.batches):
        blocks = chunked(pool, b)
        for _ in range(args.warmup):
            for blk in blocks:
                model.predict([str(p) for p in blk], verbose=False, **predict_kw)

        runs_ms: list[float] = []
        n_boxes = 0
        for _ in range(args.repeats):
            t0 = time.perf_counter()
            for blk in blocks:
                res = model.predict([str(p) for p in blk], verbose=False, **predict_kw)
                n_boxes = sum(len(getattr(r, "boxes", []) or []) for r in res)
            runs_ms.append((time.perf_counter() - t0) * 1000.0)

        med = statistics.median(runs_ms)
        rows.append({
            "batch": b,
            "blocks": len(blocks),
            "poolSize": len(pool),
            "runs_ms": [round(v, 1) for v in runs_ms],
            "median_pass_ms": round(med, 1),
            "median_per_image_ms": round(med / len(pool), 2),
            "min_per_image_ms": round(min(runs_ms) / len(pool), 2),
            "boxes_last_pass": n_boxes,
        })
        log(f"  batch={b:<3} 全池={rows[-1]['median_pass_ms']:>9.1f} ms   "
            f"单张={rows[-1]['median_per_image_ms']:>7.2f} ms   "
            f"最快={rows[-1]['min_per_image_ms']:>7.2f} ms   "
            f"块数={len(blocks)}   末轮检出框={n_boxes}")

    base = next((r["median_per_image_ms"] for r in rows if r["batch"] == 1), None)
    if base:
        log("")
        log("  相对 batch=1 的单张加速比：")
        for r in rows:
            log(f"      batch={r['batch']:<3} {base / r['median_per_image_ms']:.2f}x")

    payload = {
        "weights": args.weights,
        "weightsBytes": Path(args.weights).stat().st_size,
        "imgsz": args.imgsz,
        "repeats": args.repeats,
        "warmup": args.warmup,
        "torch": torch.__version__,
        "torchThreads": threads,
        "cudaAvailable": torch.cuda.is_available(),
        "classCount": len(names),
        "images": [p.name for p in pool],
        "rows": rows,
    }
    log("")
    log("=== JSON ===")
    log(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())