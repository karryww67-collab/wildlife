#!/usr/bin/env python
"""Pascal VOC → YOLO 训练布局，**按「视频/序列」近似划分 train/val**。

为 NTLNP（东北虎豹国家公园红外相机数据集）而写，也适用于任何 VOC 格式检测标注。

━━ 为什么必须按序列划分 ━━

NTLNP 的 25,657 张图是从红外相机**视频片段抽帧**得到的。同一段视频的相邻帧里动物
没走、光照没变、背景像素几乎相同。按图片随机划分 train/val，相邻帧会同时进入两侧，
模型"见过"近乎相同的图 —— 验证 mAP 被严重高估。这类泄漏在视频抽帧数据集上
是**默认会发生**的。

━━ NTLNP 的两个实测事实（决定了本脚本的设计）━━

1. **文件名里的类别名不可信**（实测自真实解压日志）：
       WildBoar 514  vs  Wildboar 190     ← 大小写不一致
       RaccoonDog 201 vs RacoonDog 117    ← 拼写错误
       Raccoondogbunight 695 / SablebunightSablebunight 317  ← 尾部粘连垃圾
       Y.T.Marten 101 / Y.T.Marten_ 24    ← 缩写 + 尾下划线
   → **类别一律从 XML 的 `<object><name>` 读取**，绝不解析文件名。

2. **文件名里没有视频号**，只有形如 `<类别><编号>.jpg` 或 `<类别>(<编号>).jpg` 的
   每类索引，且编号**有跳号**（真实日志：…1022,1023,1024,1025,1026,1027, 跳 1041,
   1043~1048）—— 说明编号是**视频内帧号**，跳过的正是被丢弃的非动物帧。
   → 用「编号连续段」近似「同一段视频」，见 `--strategy index-run`。

━━ 划分策略 ━━

  index-run（默认，最保守）  同一类内把编号**连续**（相邻差 ≤ --max-index-gap）的帧
                            归为一组，整组只能进 train 或整组进 val。
                            跳号即断开 → 相邻帧不会跨划分，泄漏最小。
  regex                      用 --seq-regex 从文件名提取序列键（若你确知命名规律）
  random                     ⚠️ 按图随机划分。**仅用于复现"错误做法"做对比实验**，
                            正常训练不要用（会虚高指标）。

━━ 用法 ━━

    # 1) 先侦察：看 XML 里的真实类别、文件名格式、各策略的分组统计（不写文件）
    python voc_to_yolo.py --roots D:\\NTLNP\\day\\voc_day D:\\NTLNP\\night\\voc_night --inspect

    # 2) 转换（剔除家畜 cow/dog；硬链接图片，不占额外空间）
    python voc_to_yolo.py --roots .../voc_day .../voc_night \\
        --out /mnt/workspace/data/wildlife --drop-classes cow,dog --link-images

    # 3) 过门禁
    python prepare_dataset.py --dataset <out> --classes <out>/classes.txt --out <out>/data.yaml
"""
import argparse
import collections
import glob
import os
import random
import re
import shutil
import sys
import xml.etree.ElementTree as ET

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

SUFFIX_OK = (".jpg", ".jpeg", ".png", ".bmp", ".webp")


def parse_voc(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return None, "XML 解析失败: %s" % exc
    size = root.find("size")
    if size is None:
        return None, "缺少 <size>"
    try:
        w, h = float(size.findtext("width")), float(size.findtext("height"))
    except (TypeError, ValueError):
        return None, "<size> 的 width/height 不是数字"
    objs = []
    for o in root.findall("object"):
        name = (o.findtext("name") or "").strip()
        bb = o.find("bndbox")
        if not name or bb is None or o.findtext("difficult") == "1":
            continue
        try:
            x1, y1 = float(bb.findtext("xmin")), float(bb.findtext("ymin"))
            x2, y2 = float(bb.findtext("xmax")), float(bb.findtext("ymax"))
        except (TypeError, ValueError):
            continue
        objs.append((name, x1, y1, x2, y2))
    return {"file_name": (root.findtext("filename") or "").strip(),
            "w": w, "h": h, "objs": objs, "folder": (root.findtext("folder") or "").strip()}, None


def index_of(stem):
    """取文件名里的编号；<类别>(123) 与 <类别>123 都支持。无编号返回 None。"""
    m = re.search(r"\((\d+)\)\s*$", stem) or re.search(r"(\d+)\s*$", stem)
    return int(m.group(1)) if m else None


def group_index_runs(recs, max_gap):
    """同类内编号连续段成组；返回 {group_key: [rec,...]}。"""
    by_class = collections.defaultdict(list)
    for r in recs:
        by_class[r["cls_key"]].append(r)
    groups = collections.defaultdict(list)
    for cls, items in by_class.items():
        items.sort(key=lambda r: (r["idx"] is None, r["idx"] or 0))
        run_id, prev = 0, None
        for r in items:
            if r["idx"] is None:
                run_id += 1                      # 无编号的自成一组，保守处理
            elif prev is not None and r["idx"] - prev > max_gap:
                run_id += 1
            groups["%s#%d" % (cls, run_id)].append(r)
            prev = r["idx"] if r["idx"] is not None else prev
    return groups


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--roots", nargs="+", required=True,
                    help="一个或多个目录，每个目录下应有 Annotations/ 与 JPEGImages/")
    ap.add_argument("--out")
    ap.add_argument("--classes-file", help="类别清单 txt；缺省时从 XML 的 <name> 自动生成")
    ap.add_argument("--drop-classes", default="", help="剔除的类别名（逗号分隔，忽略大小写）。NTLNP 建议 cow,dog")
    ap.add_argument("--strategy", default="index-run", choices=["index-run", "regex", "random"])
    ap.add_argument("--seq-regex", default="", help="strategy=regex 时的序列键正则（第 1 组为键）")
    ap.add_argument("--max-index-gap", type=int, default=1,
                    help="index-run 策略下，编号差 ≤ 此值视为同一段视频（默认 1=严格连续）")
    ap.add_argument("--val-ratio", type=float, default=0.2)
    ap.add_argument("--min-box-px", type=float, default=8.0)
    ap.add_argument("--max-box-area-ratio", type=float, default=0.9)
    ap.add_argument("--link-images", action="store_true",
                    help="把图片硬链接（不可用时复制）到 images/{train,val}/")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--inspect", action="store_true", help="只侦察并打印，不写任何文件")
    args = ap.parse_args()

    drop = {x.strip().lower() for x in args.drop_classes.split(",") if x.strip()}

    # ── 读全部 XML ────────────────────────────────────────────────────
    recs, unparsed, raw_names = [], [], collections.Counter()
    for root_dir in args.roots:
        adir = os.path.join(root_dir, "Annotations")
        if not os.path.isdir(adir):
            print("错误：%s 下没有 Annotations/" % root_dir)
            return 2
        for x in sorted(glob.glob(os.path.join(adir, "*.xml"))):
            d, err = parse_voc(x)
            if err:
                unparsed.append((os.path.basename(x), err))
                continue
            stem = os.path.splitext(d["file_name"] or os.path.basename(x))[0]
            for o in d["objs"]:
                raw_names[o[0]] += 1
            d.update(xml=x, stem=stem, idx=index_of(stem), root=root_dir,
                     objs=[o for o in d["objs"] if o[0].strip().lower() not in drop])
            d["cls_key"] = re.sub(r"[\(\)\d_\s]+$", "", stem) or stem
            recs.append(d)

    # 剔除 cow/dog 后若整图已无任何目标类别框，则整图丢弃。
    # 这些图里确实有动物（只是不是目标类），若当纯背景负样本，等于教模型「牛/狗 = 背景」，
    # 反而有害。宁可不喂。
    before = len(recs)
    recs = [r for r in recs if r["objs"]]
    dropped_empty = before - len(recs)

    print("XML 文件    : %d（解析失败 %d）" % (len(recs) + len(unparsed) + dropped_empty, len(unparsed)))
    if dropped_empty:
        print("剔除后无目标类别的图: %d 张（如 cow/dog 图，不作为背景负样本）" % dropped_empty)
    for n, e in unparsed[:5]:
        print("   ⚠️ %s: %s" % (n, e))

    # 文件名格式分布（用于印证「文件名不可信」）
    fmt = collections.Counter()
    for r in recs:
        s = r["stem"]
        fmt["带括号 <类>(123)" if re.search(r"\(\d+\)$", s) else
            ("<类>_<数字>" if re.search(r"_\d+$", s) else
             ("<类><数字>" if re.search(r"\d+$", s) else "无编号"))] += 1

    if args.inspect:
        print()
        print("=== XML 里的真实类别（权威来源）===")
        for n, c in raw_names.most_common():
            print("   %-22s %7d 框%s" % (n, c, "   [--drop-classes 将剔除]" if n.strip().lower() in drop else ""))
        print()
        print("=== 文件名格式分布 ===")
        for k, v in fmt.most_common():
            print("   %-22s %6d 个" % (k, v))
        print()
        print("   ⚠️ NTLNP 文件名里的类别名是**脏的**（实测：WildBoar/Wildboar 大小写不一、")
        print("      RaccoonDog/RacoonDog 拼写错误、Raccoondogbunight 尾部粘连、")
        print("      Y.T.Marten 缩写）。**不要**用文件名推类别，本脚本只用 XML 的 <name>。")
        print()
        print("=== 各划分策略的分组统计 ===")
        for strat, kw in (("index-run(gap=1)", dict(max_gap=1)),
                          ("index-run(gap=3)", dict(max_gap=3)),
                          ("index-run(gap=10)", dict(max_gap=10))):
            g = group_index_runs(recs, kw["max_gap"])
            sizes = sorted(len(v) for v in g.values())
            big = sum(1 for s in sizes if s >= 2)
            print("   %-20s 组数 %6d   平均 %.1f 张/组   含多帧的组 %d 组（最大 %d 帧）"
                  % (strat, len(g), len(recs) / max(1, len(g)), big, sizes[-1] if sizes else 0))
        print()
        print("   组越大 → 同组内帧越可能来自同一段视频 → 整组进同一侧才安全。")
        print("   默认 gap=1（最保守）：跳号即断开。若你的 NTLNP 编号是严格帧号，可放宽。")
        print()
        print("   注意：NTLNP 公开文件名**不含视频号**，index-run 只是近似。若论文要求严格")
        print("   无泄漏，需向数据方索取视频/序列 ID，或在论文中如实说明本近似及其局限。")
        return 0

    if not args.out:
        print("错误：非 --inspect 模式必须给 --out")
        return 2

    # ── 类别表 ────────────────────────────────────────────────────────
    if args.classes_file:
        with open(args.classes_file, encoding="utf-8") as fh:
            names = [x.strip() for x in fh if x.strip()]
    else:
        names = sorted({o[0] for r in recs for o in r["objs"]})
    cid = {n: i for i, n in enumerate(names)}
    print("类别数      : %d" % len(names))
    unknown = {o[0] for r in recs for o in r["objs"]} - set(names)
    if unknown:
        print("   ⚠️ XML 中有但不在类别表里的类（丢弃）：%s" % "、".join(sorted(unknown)))

    # ── 划分 ──────────────────────────────────────────────────────────
    if args.strategy == "index-run":
        groups = group_index_runs(recs, args.max_index_gap)
    elif args.strategy == "regex":
        if not args.seq_regex:
            print("错误：strategy=regex 需要 --seq-regex")
            return 2
        rx = re.compile(args.seq_regex)
        groups = collections.defaultdict(list)
        for r in recs:
            m = rx.match(r["stem"])
            groups[m.group(1) if m else r["stem"]].append(r)
    else:
        print()
        print("   ⚠️⚠️ strategy=random：按图随机划分，同视频相邻帧会跨划分，指标会虚高。")
        print("        仅用于做「错误划分」的对比实验，正常训练请用 index-run。")
        groups = {r["stem"]: [r] for r in recs}

    keys = sorted(groups)
    rng = random.Random(args.seed)
    rng.shuffle(keys)
    target, val_keys, n = int(len(recs) * args.val_ratio), set(), 0
    for k in keys:
        if n >= target:
            break
        val_keys.add(k)
        n += len(groups[k])

    def recount():
        v = [r for k in val_keys for r in groups[k]]
        t = [r for k in keys if k not in val_keys for r in groups[k]]
        return t, v

    def counts(rs):
        c = collections.Counter()
        for r in rs:
            for o in r["objs"]:
                if o[0] in cid:
                    c[cid[o[0]]] += 1
        return c

    train_recs, val_recs = recount()
    moved = []
    for i, nm in enumerate(names):                     # 修复「某类在 val 缺席」
        if counts(val_recs).get(i):
            continue
        cand = [k for k in keys if k not in val_keys
                and any(any(o[0] == nm for o in r["objs"]) for r in groups[k])]
        if not cand:
            continue
        best = min(cand, key=lambda k: len(groups[k]))
        val_keys.add(best)
        train_recs, val_recs = recount()
        moved.append((nm, best, len(groups[best])))

    print("策略        : %s%s" % (args.strategy,
                                  "（编号差 ≤ %d 视为同组）" % args.max_index_gap
                                  if args.strategy == "index-run" else ""))
    print("划分        : train %d 图 / %d 组，val %d 图 / %d 组"
          % (len(train_recs), len(keys) - len(val_keys), len(val_recs), len(val_keys)))
    for nm, k, c in moved:
        print("   [修复] 类别「%s」原在 val 缺席，移入最小分组 %s（%d 图）" % (nm, k, c))
    ratio = len(val_recs) / max(1, len(recs))
    if abs(ratio - args.val_ratio) > 0.4 * args.val_ratio:
        print("   ⚠️ 修复类别缺失后 val 实际占比 %.1f%%（目标 %.0f%%）。"
              % (ratio * 100, args.val_ratio * 100))
        print("      分组数少时修复会明显挤占比例；NTLNP 这种上万图的数据集上可忽略。")

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "classes.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(names) + "\n")

    stats = {}
    for split, rs in (("train", train_recs), ("val", val_recs)):
        ldir = os.path.join(args.out, "labels", split)
        idir = os.path.join(args.out, "images", split)
        os.makedirs(ldir, exist_ok=True)
        os.makedirs(idir, exist_ok=True)
        box_c, img_c = collections.Counter(), collections.defaultdict(set)
        for r in rs:
            lines = []
            for nm, x1, y1, x2, y2 in r["objs"]:
                if nm not in cid:
                    continue
                x1, y1 = max(0.0, min(x1, r["w"])), max(0.0, min(y1, r["h"]))
                x2, y2 = max(0.0, min(x2, r["w"])), max(0.0, min(y2, r["h"]))
                bw, bh = x2 - x1, y2 - y1
                if bw <= 0 or bh <= 0 or bw < args.min_box_px or bh < args.min_box_px:
                    continue
                if args.max_box_area_ratio > 0 and (bw * bh) / (r["w"] * r["h"]) > args.max_box_area_ratio:
                    continue
                lines.append("%d %.6f %.6f %.6f %.6f"
                             % (cid[nm], (x1 + bw / 2) / r["w"], (y1 + bh / 2) / r["h"],
                                bw / r["w"], bh / r["h"]))
                box_c[cid[nm]] += 1
                img_c[cid[nm]].add(r["stem"])
            if not lines:
                continue
            with open(os.path.join(ldir, r["stem"] + ".txt"), "w", encoding="utf-8") as fh:
                fh.write("\n".join(lines) + "\n")
            if args.link_images:
                src = os.path.join(r["root"], "JPEGImages", r["file_name"] or (r["stem"] + ".jpg"))
                dst = os.path.join(idir, os.path.basename(src))
                if os.path.exists(src) and not os.path.exists(dst):
                    try:
                        os.link(src, dst)
                    except OSError:
                        shutil.copy2(src, dst)
        stats[split] = (box_c, img_c)

    tb, ti = stats["train"]
    vb, vi = stats["val"]
    print()
    print("=== 每类统计（清洗后）===")
    print("   %-22s %9s %9s %9s %9s" % ("类别", "train框", "train图", "val框", "val图"))
    warn = []
    for i, nm in enumerate(names):
        print("   %-22s %9d %9d %9d %9d" % (nm, tb[i], len(ti[i]), vb[i], len(vi[i])))
        if tb[i] + vb[i] < 100:
            warn.append("%s（总框 %d，过少）" % (nm, tb[i] + vb[i]))
        elif vb[i] == 0:
            warn.append("%s（val 无实例）" % nm)
    if warn:
        print()
        print("   ⚠️ %s" % "；".join(warn))

    with open(os.path.join(args.out, "data.yaml"), "w", encoding="utf-8") as fh:
        fh.write("# 由 voc_to_yolo.py 生成（策略=%s，按序列近似划分）\n" % args.strategy)
        fh.write("path: %s\n" % os.path.abspath(args.out))
        fh.write("train: images/train\nval: images/val\n")
        fh.write("nc: %d\nnames:\n" % len(names))
        for nm in names:
            fh.write("  - %s\n" % nm)

    with open(os.path.join(args.out, "sequences.txt"), "w", encoding="utf-8") as fh:
        for k in sorted(val_keys):
            fh.write("val\t%s\n" % k)
        for k in keys:
            if k not in val_keys:
                fh.write("train\t%s\n" % k)

    print()
    n_tr_lbl = len([f for f in os.listdir(os.path.join(args.out, "labels", "train")) if f.endswith(".txt")])
    n_va_lbl = len([f for f in os.listdir(os.path.join(args.out, "labels", "val")) if f.endswith(".txt")])
    print("已写出 %s/{classes.txt, labels/, data.yaml, sequences.txt}" % args.out)
    print("  标注文件: train %d 份 / val %d 份" % (n_tr_lbl, n_va_lbl))
    if args.link_images:
        print("图片已硬链接到 images/{train,val}/")
    else:
        print("未复制图片（未加 --link-images）。请按 sequences.txt 归属把图片放入 images/。")
    return 0


if __name__ == "__main__":
    sys.exit(main())