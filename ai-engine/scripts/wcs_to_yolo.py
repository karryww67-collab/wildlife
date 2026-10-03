#!/usr/bin/env python
"""把 WCS Camera Traps 的「带物种类别边界框」标注转成 YOLO 训练布局。

输入（LILA 官方文件，无需自己标注）：
    wcs_20220205_bboxes_with_classes.zip  →  wcs_20220205_bboxes_with_classes.json
    下载地址：
      https://storage.googleapis.com/public-datasets-lila/wcs/wcs_20220205_bboxes_with_classes.zip
    数据集主页（许可证 CDLA-Permissive 1.0）：
      https://lila.science/datasets/wcscameratraps

输出：
    <out>/classes.txt            类别清单（一行一个，行序 = class_id）
    <out>/labels/train/*.txt     YOLO 标注
    <out>/labels/val/*.txt
    <out>/images/train/          图片目录（空；由 --download 填充）
    <out>/images/val/
    <out>/data.yaml              直接喂给 train.py
    <out>/download_train.txt     需要下载的图片清单（file_name，每行一个）
    <out>/download_val.txt

━━ 两个关键设计（都不是可选项）━━

1) **按相机位点划分 train/val，而不是按图片随机划分。**
   红外相机的图片是「序列」——一头鹿走过，连拍十几张。若随机划分，同一头鹿、
   同一个位点、同一段光照的相邻帧会同时进入训练集与验证集，验证指标会被
   严重高估（这就是所谓 "recognition in terra incognita" 问题）。
   本脚本按 image.location 划分，保证同一 location 只出现在一侧。

2) **框清洗。**
   裁到图片边界内；丢弃退化框（w<=0 或 h<=0）；丢弃过小框（--min-box-px）。
   WCS 里有相当比例的框是「整帧框」（如 [0,0,577,1200]），这类框对定位
   帮助有限，`--max-box-area-ratio` 可把它们滤掉。

用法：

    # 1) 只生成标注 + 下载清单（不联网，秒级）
    python wcs_to_yolo.py \\
        --bbox-json wcs_20220205_bboxes_with_classes.json \\
        --out /mnt/workspace/data/wildlife

    # 2) 直接把图片也下下来（并发，可中断续传）
    python wcs_to_yolo.py \\
        --bbox-json wcs_20220205_bboxes_with_classes.json \\
        --out /mnt/workspace/data/wildlife \\
        --download --workers 16

    # 3) 只想要亚洲站点（印尼/老挝）
    ... --countries idn,lao

    # 4) 下完图后校验（用项目自带的校验器）
    python prepare_dataset.py --dataset /mnt/workspace/data/wildlife \\
        --classes /mnt/workspace/data/wildlife/classes.txt \\
        --out /mnt/workspace/data/wildlife/data.yaml
"""
import argparse
import collections
import json
import os
import random
import sys
import urllib.error
import urllib.request

# Windows 控制台默认是 GBK，本脚本会打印 ⚠️ / → 等字符，不改这里会在
# 输出阶段抛 UnicodeEncodeError（且发生在标注已写完之后，看起来像"白跑一趟"）。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ── 默认类别映射：中文类别名 → WCS 学名列表 ──────────────────────────────
# 清单依据实测的「带框图片数」挑选：每类 ≥150 张才纳入。
# 近缘种合并为属级类别（猕猴属 / 鬣羚属），避免单类样本过少导致 mAP 恒为 0。
DEFAULT_CLASSES = [
    ("赤麂",   ["muntiacus muntjak"]),
    ("猕猴",   ["macaca nemestrina", "macaca arctoides", "macaca fascicularis"]),
    ("野猪",   ["sus scrofa"]),
    ("黄喉貂", ["martes flavigula"]),
    ("鬣羚",   ["capricornis sumatraensis", "capricornis milneedwardsii"]),
    ("白鹇",   ["lophura nycthemera"]),
    ("豹",     ["panthera pardus"]),
    ("猪獾",   ["arctonyx collaris"]),
    ("果子狸", ["paguma larvata"]),
    ("虎",     ["panthera tigris"]),
    ("豹猫",   ["prionailurus bengalensis"]),
]

IMAGE_BASE = {
    "gcp": "https://storage.googleapis.com/public-datasets-lila/wcs-unzipped/",
    "aws": "http://us-west-2.opendata.source.coop.s3.amazonaws.com/agentmorris/lila-wildlife/wcs-unzipped/",
    "azure": "https://lilawildlife.blob.core.windows.net/lila-wildlife/wcs-unzipped/",
}


def load_classes(path):
    """从文件读类别映射。格式（JSON）：
    [{"name": "赤麂", "scientific": ["muntiacus muntjak"]}, ...]
    """
    with open(path, encoding="utf-8") as fh:
        raw = json.load(fh)
    out = []
    for item in raw:
        out.append((item["name"], [s.lower() for s in item["scientific"]]))
    return out


def build_index(data, class_spec):
    """返回 (学名→class_id, image_id→{class_id: [boxes]}, 命中的 image 集合)。"""
    cat_name = {c["id"]: str(c.get("name", "")).strip().lower() for c in data["categories"]}
    sci_to_cid = {}
    for cid, (_, scientific) in enumerate(class_spec):
        for s in scientific:
            sci_to_cid[s] = cid

    img_meta = {im["id"]: im for im in data["images"]}

    per_image = collections.defaultdict(lambda: collections.defaultdict(list))
    stats = collections.Counter()

    for a in data["annotations"]:
        bb = a.get("bbox")
        if not bb or len(bb) != 4:
            stats["skip_no_bbox"] += 1
            continue
        sci = cat_name.get(a.get("category_id"))
        cid = sci_to_cid.get(sci)
        if cid is None:
            stats["skip_other_class"] += 1
            continue
        im = img_meta.get(a["image_id"])
        if im is None:
            stats["skip_no_image"] += 1
            continue
        if im.get("corrupt"):
            stats["skip_corrupt"] += 1
            continue
        if a.get("sequence_level_annotation"):
            stats["seq_level_kept"] += 1
        per_image[a["image_id"]][cid].append([float(v) for v in bb])
        stats["kept"] += 1

    return per_image, img_meta, stats


def clean_boxes(boxes, width, height, min_px, max_area_ratio):
    """把 COCO 绝对像素 [x,y,w,h] 裁到边界内，返回清洗后的列表。"""
    out = []
    for x, y, w, h in boxes:
        x1, y1, x2, y2 = x, y, x + w, y + h
        x1 = max(0.0, min(x1, width))
        y1 = max(0.0, min(y1, height))
        x2 = max(0.0, min(x2, width))
        y2 = max(0.0, min(y2, height))
        w2, h2 = x2 - x1, y2 - y1
        if w2 <= 0 or h2 <= 0:
            continue
        if w2 < min_px or h2 < min_px:
            continue
        if max_area_ratio > 0 and (w2 * h2) / float(width * height) > max_area_ratio:
            continue
        out.append(((x1 + w2 / 2.0) / width, (y1 + h2 / 2.0) / height, w2 / width, h2 / height))
    return out


def split_locations(per_image, img_meta, class_spec, val_ratio, seed):
    """按 location 划分；随后修复「某类别在 val 中缺席」的情况。"""
    locs = collections.defaultdict(list)
    for image_id in per_image:
        locs[img_meta[image_id].get("location") or "unknown"].append(image_id)

    rng = random.Random(seed)
    keys = sorted(locs.keys())
    rng.shuffle(keys)

    target = int(len(per_image) * val_ratio)
    val_locs, val_n = [], 0
    for k in keys:
        if val_n >= target:
            break
        val_locs.append(k)
        val_n += len(locs[k])

    def class_counts(image_ids):
        c = collections.Counter()
        for i in image_ids:
            c.update(per_image[i].keys())
        return c

    val_ids = [i for k in val_locs for i in locs[k]]
    train_locs = [k for k in keys if k not in set(val_locs)]

    # 修复：val 中缺席的类别 —— 从 train 里挑一个含该类别的最小位点搬过来
    moved = []
    for cid, (name, _) in enumerate(class_spec):
        if class_counts(val_ids).get(cid):
            continue
        candidates = [k for k in train_locs if any(cid in per_image[i] for i in locs[k])]
        if not candidates:
            continue
        best = min(candidates, key=lambda k: len(locs[k]))
        train_locs.remove(best)
        val_locs.append(best)
        val_ids = [i for k in val_locs for i in locs[k]]
        moved.append((name, best, len(locs[best])))

    train_ids = [i for k in train_locs for i in locs[k]]
    return train_ids, val_ids, val_locs, train_locs, moved


def write_split(out, split, image_ids, per_image, img_meta, min_px, max_area_ratio):
    label_dir = os.path.join(out, "labels", split)
    os.makedirs(label_dir, exist_ok=True)
    os.makedirs(os.path.join(out, "images", split), exist_ok=True)

    written = 0
    download = []
    class_boxes = collections.Counter()
    class_images = collections.defaultdict(set)

    for image_id in sorted(image_ids):
        im = img_meta[image_id]
        w, h = im.get("width"), im.get("height")
        if not w or not h:
            continue
        lines = []
        for cid, boxes in sorted(per_image[image_id].items()):
            for cx, cy, nw, nh in clean_boxes(boxes, w, h, min_px, max_area_ratio):
                lines.append("%d %.6f %.6f %.6f %.6f" % (cid, cx, cy, nw, nh))
                class_boxes[cid] += 1
                class_images[cid].add(image_id)
        if not lines:
            continue
        stem = os.path.splitext(os.path.basename(im["file_name"]))[0]
        # 文件名去重：WCS 的 file_name 形如 animals/0325/0375.jpg，用路径扁平化
        flat = im["file_name"].replace("/", "_")
        with open(os.path.join(label_dir, os.path.splitext(flat)[0] + ".txt"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
        download.append(flat + "\t" + im["file_name"])
        written += 1

    with open(os.path.join(out, "download_%s.txt" % split), "w", encoding="utf-8") as fh:
        fh.write("\n".join(download) + "\n")

    return written, class_boxes, class_images


def download_images(out, workers, base_key):
    """按 download_*.txt 并发下载图片，落到 images/<split>/<扁平文件名>。"""
    import concurrent.futures as cf

    base = IMAGE_BASE[base_key]
    jobs = []
    for split in ("train", "val"):
        manifest = os.path.join(out, "download_%s.txt" % split)
        if not os.path.exists(manifest):
            continue
        with open(manifest, encoding="utf-8") as fh:
            for line in fh:
                if not line.strip():
                    continue
                flat, remote = line.rstrip("\n").split("\t")
                jobs.append((split, flat, remote))

    print("待下载 %d 张" % len(jobs))
    ok = fail = skip = 0
    lock = __import__("threading").Lock()

    def one(job):
        nonlocal ok, fail, skip
        split, flat, remote = job
        dest = os.path.join(out, "images", split, flat)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            with lock:
                skip += 1
            return
        for attempt in range(3):
            try:
                with urllib.request.urlopen(base + remote, timeout=60) as resp:
                    blob = resp.read()
                tmp = dest + ".part"
                with open(tmp, "wb") as fh:
                    fh.write(blob)
                os.replace(tmp, dest)
                with lock:
                    ok += 1
                return
            except (urllib.error.URLError, TimeoutError, OSError):
                if attempt == 2:
                    with lock:
                        fail += 1

    with cf.ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(one, jobs))
    print("下载完成：成功 %d，已存在跳过 %d，失败 %d" % (ok, skip, fail))
    return fail


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox-json", required=True, help="wcs_20220205_bboxes_with_classes.json")
    ap.add_argument("--out", required=True, help="输出目录")
    ap.add_argument("--classes-file", help="类别映射 JSON（不给则用内置 11 类）")
    ap.add_argument("--countries", help="只保留这些国家代码，逗号分隔（如 idn,lao）")
    ap.add_argument("--val-ratio", type=float, default=0.2, help="验证集图片占比，默认 0.2")
    ap.add_argument("--min-box-px", type=float, default=8.0, help="丢弃短边小于该像素数的框")
    ap.add_argument("--max-box-area-ratio", type=float, default=0.9,
                    help="丢弃面积超过整图该比例的框（滤「整帧框」），0=不过滤")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--download", action="store_true", help="顺带下载图片")
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--source", default="gcp", choices=sorted(IMAGE_BASE), help="图片源")
    args = ap.parse_args()

    class_spec = load_classes(args.classes_file) if args.classes_file else DEFAULT_CLASSES

    print("读取 %s ..." % args.bbox_json, flush=True)
    with open(args.bbox_json, encoding="utf-8") as fh:
        data = json.load(fh)
    print("  images=%d annotations=%d categories=%d"
          % (len(data["images"]), len(data["annotations"]), len(data["categories"])))

    per_image, img_meta, stats = build_index(data, class_spec)
    print("  命中目标类别的框: %d" % stats["kept"])

    if args.countries:
        keep = {c.strip().lower() for c in args.countries.split(",") if c.strip()}
        before = len(per_image)
        per_image = {i: v for i, v in per_image.items()
                     if str(img_meta[i].get("country_code", "")).lower() in keep}
        print("  按国家过滤 %s：%d → %d 张图" % (sorted(keep), before, len(per_image)))

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "classes.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(name for name, _ in class_spec) + "\n")

    train_ids, val_ids, val_locs, train_locs, moved = split_locations(
        per_image, img_meta, class_spec, args.val_ratio, args.seed)
    print("  按位点划分：train %d 图 / %d 位点，val %d 图 / %d 位点"
          % (len(train_ids), len(train_locs), len(val_ids), len(val_locs)))
    for name, loc, n in moved:
        print("    [修复] 类别「%s」原在 val 中缺席，把一个含它的位点 %s（%d 图）移入 val" % (name, loc, n))

    n_tr, box_tr, img_tr = write_split(args.out, "train", train_ids, per_image, img_meta,
                                       args.min_box_px, args.max_box_area_ratio)
    n_va, box_va, img_va = write_split(args.out, "val", val_ids, per_image, img_meta,
                                       args.min_box_px, args.max_box_area_ratio)

    print()
    print("=== 每类统计（清洗后）===")
    print("  %-10s %10s %10s %10s %10s" % ("类别", "train框", "train图", "val框", "val图"))
    warn = []
    for cid, (name, _) in enumerate(class_spec):
        tb, ti, vb, vi = box_tr[cid], len(img_tr[cid]), box_va[cid], len(img_va[cid])
        print("  %-10s %10d %10d %10d %10d" % (name, tb, ti, vb, vi))
        if tb + vb < 100:
            warn.append(name)
        elif vi == 0:
            warn.append(name + "（val 中无实例）")
    if warn:
        print()
        print("  ⚠️ 需要处理：%s" % "、".join(warn))
        print("     少于 100 个框的类别建议从类别表里去掉（它的 mAP 恒为 0，拉低整体指标）。")

    # data.yaml
    yaml_path = os.path.join(args.out, "data.yaml")
    with open(yaml_path, "w", encoding="utf-8") as fh:
        fh.write("# 由 wcs_to_yolo.py 生成\n")
        fh.write("path: %s\n" % os.path.abspath(args.out))
        fh.write("train: images/train\n")
        fh.write("val: images/val\n")
        fh.write("nc: %d\n" % len(class_spec))
        fh.write("names:\n")
        for name, _ in class_spec:
            fh.write("  - %s\n" % name)

    print()
    print("已写出：")
    print("  %s/classes.txt" % args.out)
    print("  %s/labels/{train,val}/   (%d + %d 份标注)" % (args.out, n_tr, n_va))
    print("  %s/download_{train,val}.txt" % args.out)
    print("  %s" % yaml_path)

    if args.download:
        print()
        fail = download_images(args.out, args.workers, args.source)
        if fail:
            print("  有 %d 张失败，重跑本命令会自动续传。" % fail)

    print()
    print("下一步：")
    print("  python prepare_dataset.py --dataset %s --classes %s/classes.txt --out %s"
          % (args.out, args.out, yaml_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())