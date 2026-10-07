#!/usr/bin/env python
"""把 SWG + WCS 两个数据集的 YOLO 目录合并成统一 19 类。

输入（由 lila_to_yolo.py / wcs_to_yolo.py 产出）：

    <swg>/images/{train,val}/*.jpg     <swg>/labels/{train,val}/*.txt    (14 类)
    <wcs>/images/{train,val}/*.jpg     <wcs>/labels/{train,val}/*.txt    (11 类)

输出：

    <out>/images/{train,val}/swg_*.jpg     ← 硬链接或复制，加前缀避免同名冲突
    <out>/images/{train,val}/wcs_*.jpg
    <out>/labels/{train,val}/swg_*.txt     ← SWG 的 class_id 原样保留（见下）
    <out>/labels/{train,val}/wcs_*.txt     ← WCS 的 class_id 逐行重映射
    <out>/classes.txt                       ← 19 类最终清单
    <out>/data.yaml                         ← 指向本目录

━━ 合并规则（为什么是 19 类）━━

SWG 14 类 + WCS 11 类，其中 **7 类跨数据集重叠**：

    野猪(=eurasian_wild_pig=sus scrofa)、猕猴、果子狸、中华鬣羚(=wcs 的「鬣羚」)、
    白鹇、黄喉貂、麂/赤麂（注意：**不合并**，见下）

WCS 独有 4 类作为新增类别：**虎、豹、豹猫、猪獾**（含大猫，是 WCS 的独特价值）。

14 + 11 - 7 = 18，但结果是 **19** —— 因为「麂」与「赤麂」**刻意分列**：
SWG 的「麂」是麂属广义（*Muntiacus* spp.，含大角麂/红麂/未定种），
WCS 的「赤麂」是 *Muntiacus muntjak* 单种。两者类别内涵不同，合并会引入
标签噪声（把不同种混成一个类），因此各占一个 class_id（2 与 18）。

SWG 的 class_id 0-13 在最终表中**恰好前移不变**，所以 SWG 标签**无需改写**；
只有 WCS 的 11 个 id 需要重映射（WCS_REMAP）。

━━ 链接还是复制 ━━

默认**先试硬链接**（同文件系统内零额外空间，19,723 张图省约 20 GB），
失败（跨挂载点等）自动回落到 `shutil.copy2`。NAS 上硬链接可能不可用，
此时会多占一份空间 —— 脚本会在结尾报告实际用了哪种方式。

用法：

    python merge_datasets.py \\
        --swg /mnt/data/wildlife-swg/yolo \\
        --wcs /mnt/data/wildlife-wcs/yolo \\
        --out /mnt/data/wildlife-combined/yolo

    不加 --download 类参数；本脚本只做本地合并，不联网。
"""
import argparse
import os
import shutil
import sys
from pathlib import Path

# Windows 控制台默认 GBK，本脚本会打印中文与符号，不重配置会在输出阶段抛
# UnicodeEncodeError（且发生在合并已跑完之后，看起来像"白跑一趟"）。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# WCS 原始 class_id → 合并后的 class_id
#   来源：wcs_to_yolo.py 的 DEFAULT_CLASSES（11 类）顺序
#    0 赤麂 1 猕猴 2 野猪 3 黄喉貂 4 鬣羚 5 白鹇 6 豹 7 猪獾 8 果子狸 9 虎 10 豹猫
#   目标：本文件的 FINAL_CLASSES（19 类）顺序
WCS_REMAP = {
    0: 18,   # 赤麂   → 18（独立于 SWG 的「麂」）
    1: 1,    # 猕猴   → 1  猕猴（与 SWG 合并）
    2: 0,    # 野猪   → 0  野猪（与 SWG 合并）
    3: 10,   # 黄喉貂 → 10 黄喉貂（与 SWG 合并）
    4: 8,    # 鬣羚   → 8  中华鬣羚（与 SWG 合并）
    5: 9,    # 白鹇   → 9  白鹇（与 SWG 合并）
    6: 15,   # 豹     → 15 豹（WCS 独有）
    7: 17,   # 猪獾   → 17 猪獾（WCS 独有）
    8: 6,    # 果子狸 → 6  果子狸（与 SWG 合并）
    9: 14,   # 虎     → 14 虎（WCS 独有）
    10: 16,  # 豹猫   → 16 豹猫（WCS 独有）
}

# 最终 19 类的顺序（行序 = class_id）
# 前 14 个 = SWG 的 14 类原序（因此 SWG 标签无需改写），后 5 个为 WCS 贡献
FINAL_CLASSES = [
    "野猪",     # 0  SWG + WCS
    "猕猴",     # 1  SWG + WCS
    "麂",       # 2  SWG（麂属广义）
    "水鹿",     # 3  SWG
    "鼬獾",     # 4  SWG
    "红颊松鼠", # 5  SWG
    "果子狸",   # 6  SWG + WCS
    "蟹獴",     # 7  SWG
    "中华鬣羚", # 8  SWG + WCS（WCS 的「鬣羚」）
    "白鹇",     # 9  SWG + WCS
    "黄喉貂",   # 10 SWG + WCS
    "帚尾豪猪", # 11 SWG
    "灰孔雀雉", # 12 SWG
    "红原鸡",   # 13 SWG
    "虎",       # 14 WCS 独有
    "豹",       # 15 WCS 独有
    "豹猫",     # 16 WCS 独有
    "猪獾",     # 17 WCS 独有
    "赤麂",     # 18 WCS 独有（*Muntiacus muntjak* 单种）
]


def remap_label(src: Path, dst: Path) -> int:
    """把一份 WCS 标签的 class_id 重写为新表，返回写出的框数。"""
    boxes = 0
    with open(src, encoding="utf-8") as fin, open(dst, "w", encoding="utf-8") as fout:
        for line in fin:
            parts = line.strip().split()
            if len(parts) < 5:
                continue
            old_id = int(float(parts[0]))
            if old_id not in WCS_REMAP:
                raise ValueError(f"{src}: class_id {old_id} 不在 WCS_REMAP 中")
            fout.write("%d %s\n" % (WCS_REMAP[old_id], " ".join(parts[1:])))
            boxes += 1
    return boxes


def place(src: Path, dst: Path, stats: dict) -> None:
    """优先硬链接，失败则复制。dst 已存在时先删。"""
    if dst.exists():
        dst.unlink()
    try:
        os.link(src, dst)
        stats["linked"] += 1
    except OSError:
        shutil.copy2(src, dst)
        stats["copied"] += 1


def merge_split(split: str, swg: Path, wcs: Path, out: Path, stats: dict) -> None:
    out_img = out / "images" / split
    out_lbl = out / "labels" / split
    out_img.mkdir(parents=True, exist_ok=True)
    out_lbl.mkdir(parents=True, exist_ok=True)

    counts = {}

    # ── SWG：class_id 原样保留，只加文件名前缀 ──────────────────
    src_img, src_lbl = swg / "images" / split, swg / "labels" / split
    n, skip = 0, 0
    if src_img.is_dir():
        for img in sorted(src_img.glob("*.jpg")):
            label = src_lbl / (img.stem + ".txt")
            if not label.exists():
                skip += 1
                continue
            try:
                place(img, out_img / ("swg_" + img.name), stats)
                place(label, out_lbl / ("swg_" + img.stem + ".txt"), stats)
                n += 1
            except OSError as exc:
                print(f"  ⚠️ SWG {img.name}: {exc}", file=sys.stderr)
                skip += 1
    counts["swg"], counts["swg_skip"] = n, skip

    # ── WCS：图片加前缀，标签逐行重映射 class_id ────────────────
    src_img, src_lbl = wcs / "images" / split, wcs / "labels" / split
    n, skip = 0, 0
    if src_img.is_dir():
        for img in sorted(src_img.glob("*.jpg")):
            label = src_lbl / (img.stem + ".txt")
            if not label.exists():
                skip += 1
                continue
            try:
                place(img, out_img / ("wcs_" + img.name), stats)
                remap_label(label, out_lbl / ("wcs_" + img.stem + ".txt"))
                n += 1
            except (OSError, ValueError) as exc:
                print(f"  ⚠️ WCS {img.name}: {exc}", file=sys.stderr)
                skip += 1
    counts["wcs"], counts["wcs_skip"] = n, skip

    print("  %-5s  SWG %5d（跳过 %d） + WCS %5d（跳过 %d） = %d 张"
          % (split, counts["swg"], counts["swg_skip"],
             counts["wcs"], counts["wcs_skip"],
             counts["swg"] + counts["wcs"]))
    # 供 main 汇总
    stats.setdefault("per_split", {})[split] = counts


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--swg", default="/mnt/data/wildlife-swg/yolo",
                    help="SWG 的 YOLO 目录（lila_to_yolo.py 的 --out）")
    ap.add_argument("--wcs", default="/mnt/data/wildlife-wcs/yolo",
                    help="WCS 的 YOLO 目录（wcs_to_yolo.py 的 --out）")
    ap.add_argument("--out", default="/mnt/data/wildlife-combined/yolo",
                    help="合并输出目录")
    ap.add_argument("--force", action="store_true",
                    help="输出目录非空时也继续（默认会拒绝，避免混入上次的残留）")
    args = ap.parse_args()

    swg, wcs, out = Path(args.swg), Path(args.wcs), Path(args.out)

    # ── 前置检查：两边都必须在，且都是 YOLO 布局 ────────────────
    missing = []
    for name, root in (("SWG", swg), ("WCS", wcs)):
        for split in ("train", "val"):
            if not (root / "images" / split).is_dir():
                missing.append(f"{name} 的 {root}/images/{split}")
    if missing:
        for item in missing:
            print(f"错误：找不到 {item}", file=sys.stderr)
        print("\n提示：先跑 lila_to_yolo.py / wcs_to_yolo.py 生成 YOLO 布局"
              "（加 --download 才有图片）。", file=sys.stderr)
        return 2

    for root in (swg, wcs):
        for split in ("train", "val"):
            img_dir = root / "images" / split
            if img_dir.is_dir() and not any(img_dir.glob("*.jpg")):
                print(f"错误：{img_dir} 是空的 —— 图片还没下载。\n"
                      f"      重跑对应的转换脚本并加 --download（支持断点续传）。",
                      file=sys.stderr)
                return 2

    if out.exists() and any(out.iterdir()) and not args.force:
        print(f"错误：输出目录非空 {out}\n"
              f"      合并会与上次的残留混在一起。请先清空，或加 --force。",
              file=sys.stderr)
        return 2

    out.mkdir(parents=True, exist_ok=True)
    with open(out / "classes.txt", "w", encoding="utf-8") as fh:
        fh.write("\n".join(FINAL_CLASSES) + "\n")
    print("写出 classes.txt：%d 类" % len(FINAL_CLASSES))
    for cid, name in enumerate(FINAL_CLASSES):
        print("  %2d %s" % (cid, name))

    print("\n合并（优先硬链接，失败自动复制）：")
    stats = {"linked": 0, "copied": 0}
    merge_split("train", swg, wcs, out, stats)
    merge_split("val", swg, wcs, out, stats)

    verb = "硬链接 %d 个" % stats["linked"]
    if stats["copied"]:
        verb += "、**复制** %d 个（跨文件系统，额外占用一份空间）" % stats["copied"]
    print("\n链接方式：%s" % verb)

    # ── 写 data.yaml ───────────────────────────────────────────
    yaml_path = out / "data.yaml"
    with open(yaml_path, "w", encoding="utf-8") as fh:
        fh.write("# 由 merge_datasets.py 生成（SWG + WCS，%d 类）\n" % len(FINAL_CLASSES))
        fh.write("path: %s\n" % out.resolve())
        fh.write("train: images/train\nval: images/val\n")
        fh.write("nc: %d\nnames:\n" % len(FINAL_CLASSES))
        for name in FINAL_CLASSES:
            fh.write("  - %s\n" % name)

    print("\n已写出：%s/{images,labels,classes.txt,data.yaml}" % out)
    print("\n下一步（门禁校验）：")
    print("  python prepare_dataset.py --dataset %s --classes %s/classes.txt --out %s"
          % (out, out, yaml_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
