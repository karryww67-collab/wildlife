#!/usr/bin/env python
"""把 LILA BC 的「COCO Camera Traps + 物种级边界框」标注转成 YOLO 训练布局。

支持任意使用 COCO Camera Traps 格式、且 annotations 带 `bbox` 的 LILA 数据集。
内置两套实测过的预设：

  --preset swg   越南-老挝 SWG Camera Traps（东南亚区系，与"中国野生动物"主题贴合）
                 88,135 张带框图 / 101,659 框 / 99 个带框类别 / 915 个相机位点
                 边界框文件：swg_camera_traps.bounding_boxes.with_species.zip (6.4 MB → 72.5 MB)
                 https://storage.googleapis.com/public-datasets-lila/swg-camera-traps/swg_camera_traps.bounding_boxes.with_species.zip

  --preset wcs   WCS Camera Traps（全球 12 国，物种多样性最高）
                 298,725 张图 / 429,482 标注 / 678 类（611 类带框）
                 边界框文件：wcs_20220205_bboxes_with_classes.zip (22.9 MB → 220.9 MB)
                 https://storage.googleapis.com/public-datasets-lila/wcs/wcs_20220205_bboxes_with_classes.zip

  两者均为 CDLA-Permissive-1.0 许可证，图片可逐张 HTTP 下载（无需下全量）。

输出：
    <out>/classes.txt            类别清单（一行一个，行序 = class_id）
    <out>/labels/{train,val}/*.txt
    <out>/images/{train,val}/    图片目录（空；由 --download 填充）
    <out>/data.yaml
    <out>/download_{train,val}.txt

━━ 三个关键设计（都不是可选项）━━

1) **按相机位点划分 train/val，而不是按图片随机划分。**
   红外相机图片是「序列」——一头鹿走过连拍十几张。随机划分会让同一头鹿、
   同一位点、同一段光照的相邻帧同时进入训练集与验证集，验证指标被严重高估
   （Beery et al., *Recognition in Terra Incognita*, ECCV 2018）。本脚本按
   `image.location` 划分，保证同一位点只出现在一侧，并自动修复「某类别在 val
   中缺席」的情况。

2) **框清洗**：裁到图片边界、丢弃退化框、丢弃过小框、丢弃「整帧框」。

3) **类别可收窄**：原始数据集类别很多（SWG 99 类 / WCS 611 类），长尾严重。
   预设只挑样本量足够、且可与其他近缘种合并成属级类别的物种。

用法：

    # 只生成标注 + 下载清单（不联网，秒级）
    python lila_to_yolo.py --preset swg \\
        --bbox-json swg_camera_traps.bounding_boxes.with_species.json \\
        --out /mnt/workspace/data/wildlife

    # 连图片一起下（并发 + 断点续传）
    python lila_to_yolo.py --preset swg \\
        --bbox-json ... --out /mnt/workspace/data/wildlife \\
        --download --workers 16

    # 每类最多 800 张，控制总量与类别均衡
    ... --max-per-class 800

    # 用自己的类别映射（JSON：[{"name":"野猪","scientific":["eurasian_wild_pig"]}, ...]）
    ... --classes-file my_classes.json

    # 下完图后过门禁
    python prepare_dataset.py --dataset <out> --classes <out>/classes.txt --out <out>/data.yaml
"""
import argparse
import collections
import json
import os
import random
import sys
import threading
import urllib.error
import urllib.request

# Windows 控制台默认是 GBK，本脚本会打印 ⚠️ / → 等字符，不改这里会在输出阶段抛
# UnicodeEncodeError（且发生在标注已写完或跑完之后，看起来像"白跑一趟"）。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ── 预设 ────────────────────────────────────────────────────────────────
# image_base 已实测可用（HTTP 200）。类别清单按「带框图片数 ≥ 100」挑选，
# 近缘种合并为属级类别，避免单类样本过少（mAP 恒为 0，只会拉低整体指标）。
PRESETS = {
    "swg": {
        "image_base": "https://storage.googleapis.com/public-datasets-lila/swg-camera-traps/",
        "classes": [
            ("野猪",     ["eurasian_wild_pig"]),
            ("猕猴",     ["stump_tailed_macaque", "assam_or_rhesus_macaque",
                          "pig_tailed_macaque", "unidentified_macaque",
                          "macaque_not_stump_tailed"]),
            ("麂",       ["large_antlered_muntjac", "red_muntjac", "unidentified_muntjac"]),
            ("水鹿",     ["sambar"]),
            ("鼬獾",     ["ferret_badger"]),
            ("红颊松鼠", ["red_cheeked_squirrel", "pallass_squirrel"]),
            ("果子狸",   ["masked_palm_civet", "common_palm_civet", "unidentified_palm_civet"]),
            ("蟹獴",     ["crab_eating_mongoose"]),
            ("中华鬣羚", ["chinese_serow", "chinese serow"]),   # 数据集内两种写法并存
            ("白鹇",     ["silver_pheasant"]),
            ("黄喉貂",   ["yellow_throated_marten"]),
            ("帚尾豪猪", ["asiatic_brush_tailed_porcupine"]),
            ("灰孔雀雉", ["grey_peacock_pheasant"]),
            ("红原鸡",   ["red_junglefowl"]),
        ],
    },
    "wcs": {
        "image_base": "https://storage.googleapis.com/public-datasets-lila/wcs-unzipped/",
        "classes": [
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
        ],
    },
}


def load_classes(path):
    with open(path, encoding="utf-8") as fh:
        raw = json.load(fh)
    return [(item["name"], [s.lower() for s in item["scientific"]]) for item in raw]


def build_index(data, class_spec):
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
        cid = sci_to_cid.get(cat_name.get(a.get("category_id")))
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


def cap_per_class(per_image, img_meta, class_spec, cap, seed):
    """每类最多保留 cap 张图。从**稀有类优先**挑，挑中的图整体保留（含它带的其他类框）。"""
    if cap <= 0:
        return per_image
    rng = random.Random(seed)
    by_class = collections.defaultdict(list)
    for image_id, boxes in per_image.items():
        for cid in boxes:
            by_class[cid].append(image_id)

    kept = set()
    for cid, _ in sorted(((c, len(v)) for c, v in by_class.items()), key=lambda x: x[1]):
        have = sum(1 for i in by_class[cid] if i in kept)
        need = cap - have
        if need <= 0:
            continue
        pool = [i for i in by_class[cid] if i not in kept]
        rng.shuffle(pool)
        kept.update(pool[:need])
    return {i: per_image[i] for i in kept}


def clean_boxes(boxes, width, height, min_px, max_area_ratio):
    out = []
    for x, y, w, h in boxes:
        x1, y1, x2, y2 = x, y, x + w, y + h
        x1, y1 = max(0.0, min(x1, width)), max(0.0, min(y1, height))
        x2, y2 = max(0.0, min(x2, width)), max(0.0, min(y2, height))
        w2, h2 = x2 - x1, y2 - y1
        if w2 <= 0 or h2 <= 0 or w2 < min_px or h2 < min_px:
            continue
        if max_area_ratio > 0 and (w2 * h2) / float(width * height) > max_area_ratio:
            continue
        out.append(((x1 + w2 / 2.0) / width, (y1 + h2 / 2.0) / height, w2 / width, h2 / height))
    return out


def split_locations(per_image, img_meta, class_spec, val_ratio, seed):
    locs = collections.defaultdict(list)
    for image_id in per_image:
        locs[img_meta[image_id].get("location") or "unknown"].append(image_id)

    rng = random.Random(seed)
    keys = sorted(locs)
    rng.shuffle(keys)
    target = int(len(per_image) * val_ratio)
    val_locs, val_n = [], 0
    for k in keys:
        if val_n >= target:
            break
        val_locs.append(k)
        val_n += len(locs[k])

    def class_counts(ids):
        c = collections.Counter()
        for i in ids:
            c.update(per_image[i].keys())
        return c

    train_locs = [k for k in keys if k not in set(val_locs)]
    val_ids = [i for k in val_locs for i in locs[k]]

    moved = []
    for cid, (name, _) in enumerate(class_spec):
        if class_counts(val_ids).get(cid):
            continue
        cand = [k for k in train_locs if any(cid in per_image[i] for i in locs[k])]
        if not cand:
            continue
        best = min(cand, key=lambda k: len(locs[k]))
        train_locs.remove(best)
        val_locs.append(best)
        val_ids = [i for k in val_locs for i in locs[k]]
        moved.append((name, best, len(locs[best])))

    train_ids = [i for k in train_locs for i in locs[k]]
    return train_ids, val_ids, len(train_locs), len(val_locs), moved


def write_split(out, split, ids, per_image, img_meta, min_px, max_area_ratio):
    label_dir = os.path.join(out, "labels", split)
    os.makedirs(label_dir, exist_ok=True)
    os.makedirs(os.path.join(out, "images", split), exist_ok=True)

    written = 0
    manifest = []
    class_boxes = collections.Counter()
    class_images = collections.defaultdict(set)

    for image_id in sorted(ids):
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
        flat = im["file_name"].replace("/", "_")
        with open(os.path.join(label_dir, os.path.splitext(flat)[0] + ".txt"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
        manifest.append(flat + "\t" + im["file_name"])
        written += 1

    with open(os.path.join(out, "download_%s.txt" % split), "w", encoding="utf-8") as fh:
        fh.write("\n".join(manifest) + "\n")
    return written, class_boxes, class_images


def download_images(out, base, workers):
    import concurrent.futures as cf

    jobs = []
    for split in ("train", "val"):
        p = os.path.join(out, "download_%s.txt" % split)
        if not os.path.exists(p):
            continue
        with open(p, encoding="utf-8") as fh:
            for line in fh:
                if line.strip():
                    flat, remote = line.rstrip("\n").split("\t")
                    jobs.append((split, flat, remote))

    print("待下载 %d 张（源：%s）" % (len(jobs), base))
    state = {"ok": 0, "fail": 0, "skip": 0}
    lock = threading.Lock()

    def one(job):
        split, flat, remote = job
        dest = os.path.join(out, "images", split, flat)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            with lock:
                state["skip"] += 1
            return
        for attempt in range(3):
            try:
                with urllib.request.urlopen(base + remote, timeout=90) as resp:
                    blob = resp.read()
                tmp = dest + ".part"
                with open(tmp, "wb") as fh:
                    fh.write(blob)
                os.replace(tmp, dest)
                with lock:
                    state["ok"] += 1
                return
            except Exception:  # noqa: BLE001
                if attempt == 2:
                    with lock:
                        state["fail"] += 1

    with cf.ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(one, jobs))
    print("下载完成：成功 %d，已存在跳过 %d，失败 %d" % (state["ok"], state["skip"], state["fail"]))
    return state["fail"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox-json", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--preset", default="swg", choices=sorted(PRESETS))
    ap.add_argument("--classes-file", help="覆盖预设的类别映射（JSON）")
    ap.add_argument("--image-base", help="覆盖预设的图片 URL 前缀")
    ap.add_argument("--max-per-class", type=int, default=0,
                    help="每类最多保留多少张图（0=不限制）。用于控制总量与类别均衡")
    ap.add_argument("--val-ratio", type=float, default=0.2)
    ap.add_argument("--min-box-px", type=float, default=8.0)
    ap.add_argument("--max-box-area-ratio", type=float, default=0.9, help="滤「整帧框」，0=不过滤")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--workers", type=int, default=12)
    args = ap.parse_args()

    preset = PRESETS[args.preset]
    class_spec = load_classes(args.classes_file) if args.classes_file else preset["classes"]
    image_base = args.image_base or preset["image_base"]

    print("预设 %s | 图片源 %s" % (args.preset, image_base))
    print("读取 %s ..." % args.bbox_json, flush=True)
    with open(args.bbox_json, encoding="utf-8") as fh:
        data = json.load(fh)
    print("  images=%d annotations=%d categories=%d"
          % (len(data["images"]), len(data["annotations"]), len(data["categories"])))

    per_image, img_meta, stats = build_index(data, class_spec)
    print("  命中目标类别的框: %d" % stats["kept"])
    if stats["seq_level_kept"]:
        print("  其中序列级标注框: %d（已保留，但精度可能低于逐帧标注）" % stats["seq_level_kept"])

    if args.max_per_class > 0:
        before = len(per_image)
        per_image = cap_per_class(per_image, img_meta, class_spec, args.max_per_class, args.seed)
        print("  每类限 %d 张：%d → %d 张图" % (args.max_per_class, before, len(per_image)))

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "classes.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(n for n, _ in class_spec) + "\n")

    train_ids, val_ids, n_tl, n_vl, moved = split_locations(
        per_image, img_meta, class_spec, args.val_ratio, args.seed)
    print("  按位点划分：train %d 图 / %d 位点，val %d 图 / %d 位点"
          % (len(train_ids), n_tl, len(val_ids), n_vl))
    for name, loc, n in moved:
        print("    [修复] 类别「%s」原在 val 中缺席，把含它的最小位点 %s（%d 图）移入 val" % (name, loc, n))

    n_tr, box_tr, img_tr = write_split(args.out, "train", train_ids, per_image, img_meta,
                                       args.min_box_px, args.max_box_area_ratio)
    n_va, box_va, img_va = write_split(args.out, "val", val_ids, per_image, img_meta,
                                       args.min_box_px, args.max_box_area_ratio)

    print()
    print("=== 每类统计（清洗后）===")
    print("  %-10s %9s %9s %9s %9s" % ("类别", "train框", "train图", "val框", "val图"))
    warn = []
    for cid, (name, _) in enumerate(class_spec):
        tb, ti, vb, vi = box_tr[cid], len(img_tr[cid]), box_va[cid], len(img_va[cid])
        print("  %-10s %9d %9d %9d %9d" % (name, tb, ti, vb, vi))
        if tb + vb < 100:
            warn.append("%s（总框数 %d < 100）" % (name, tb + vb))
        elif vi == 0:
            warn.append("%s（val 无实例）" % name)
    if warn:
        print()
        print("  ⚠️ 需处理：%s" % "、".join(warn))
        print("     框数过少的类别建议从类别表去掉 —— 它的 mAP 恒为 0，只会拉低整体指标。")

    yaml_path = os.path.join(args.out, "data.yaml")
    with open(yaml_path, "w", encoding="utf-8") as fh:
        fh.write("# 由 lila_to_yolo.py 生成（preset=%s）\n" % args.preset)
        fh.write("path: %s\n" % os.path.abspath(args.out))
        fh.write("train: images/train\nval: images/val\n")
        fh.write("nc: %d\nnames:\n" % len(class_spec))
        for name, _ in class_spec:
            fh.write("  - %s\n" % name)

    print()
    print("已写出：%s/{classes.txt, labels/, download_*.txt, data.yaml}" % args.out)
    print("  标注 %d + %d 份" % (n_tr, n_va))

    if args.download:
        print()
        if download_images(args.out, image_base, args.workers):
            print("  有失败项，重跑本命令会自动续传。")

    print()
    print("下一步：")
    print("  python prepare_dataset.py --dataset %s --classes %s/classes.txt --out %s"
          % (args.out, args.out, yaml_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())