#!/usr/bin/env python
"""校验并整理野生动物数据集为 YOLO 训练布局，生成 `data.yaml`。

**本脚本不产生数据、也不下载数据。** 数据必须由你提供 —— 训练链路里唯一
无法用代码替代的就是数据本身。本脚本负责在开训之前把数据问题挡下来：
标注缺失、类别 id 越界、坐标越界、图片与标注不成对，这些如果等到训到第
30 个 epoch 才发现，代价是几十小时的 CPU 时间。

期望的输入布局（YOLO 标准写法，train/val 各自成对）：

    <dataset>/
    ├── images/train/*.jpg
    ├── images/val/*.jpg
    ├── labels/train/*.txt
    └── labels/val/*.txt

标注为 YOLO txt 格式，每行 `class_id cx cy w h`，坐标归一化到 0~1。

类别清单来源二选一：
  `--classes` 指向 classes.txt（一行一个类别名，即 `models/wildlife-v*.0/classes.txt`）
  `--names`   直接给逗号分隔的类别名

用法：

    python scripts/prepare_dataset.py \\
        --dataset /data/wildlife \\
        --classes /models/wildlife-v2.0/classes.txt \\
        --out /data/wildlife/data.yaml

退出码：校验发现问题且未加 `--force` 时为 1（可直接用作训练流水线的前置门禁）。
"""
import argparse
import json
import os
import sys
from collections import Counter
from typing import Dict, List, Tuple

IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tif", ".tiff")


def read_classes(classes_file: str, names_arg: str) -> List[str]:
    """类别清单：优先 --names，其次 --classes 文件。"""
    if names_arg:
        return [item.strip() for item in names_arg.split(",") if item.strip()]
    if not classes_file:
        return []
    if not os.path.exists(classes_file):
        print(f"错误：类别文件不存在 {classes_file}", file=sys.stderr)
        raise SystemExit(2)
    with open(classes_file, "r", encoding="utf-8") as handle:
        return [line.strip() for line in handle if line.strip()]


def scan_split(
    dataset: str, split: str, class_count: int
) -> Tuple[Dict[str, int], Counter, List[str]]:
    """
    扫描一个 split，返回 (统计计数, 各类别实例数, 问题列表)。

    「图片没有对应标注文件」在 YOLO 里是合法的（表示该图无目标，即负样本），
    因此只统计不报错；反向的「标注没有对应图片」才是真问题。
    """
    image_dir = os.path.join(dataset, "images", split)
    label_dir = os.path.join(dataset, "labels", split)
    stats = {
        "images": 0,
        "labels": 0,
        "negativeImages": 0,   # 无标注的负样本图
        "orphanLabels": 0,     # 标注找不到对应图片
        "boxes": 0,
        "badLines": 0,
    }
    counts: Counter = Counter()
    problems: List[str] = []

    if not os.path.isdir(image_dir):
        problems.append(f"{split}: 找不到图片目录 {image_dir}")
        return stats, counts, problems
    if not os.path.isdir(label_dir):
        problems.append(f"{split}: 找不到标注目录 {label_dir}")
        return stats, counts, problems

    stems = set()
    for name in sorted(os.listdir(image_dir)):
        if not name.lower().endswith(IMAGE_SUFFIXES):
            continue
        stems.add(os.path.splitext(name)[0])
        stats["images"] += 1

    for stem in sorted(stems):
        label_path = os.path.join(label_dir, f"{stem}.txt")
        if not os.path.exists(label_path):
            stats["negativeImages"] += 1
            continue
        stats["labels"] += 1
        with open(label_path, "r", encoding="utf-8", errors="replace") as handle:
            for line_no, raw in enumerate(handle, start=1):
                line = raw.strip()
                if not line:
                    continue
                parts = line.split()
                if len(parts) != 5:
                    stats["badLines"] += 1
                    problems.append(
                        f"{split}/{stem}.txt:{line_no} 字段数应为 5，实际 {len(parts)}"
                    )
                    continue
                try:
                    class_id = int(float(parts[0]))
                    coords = [float(value) for value in parts[1:]]
                except ValueError:
                    stats["badLines"] += 1
                    problems.append(f"{split}/{stem}.txt:{line_no} 存在非数值字段")
                    continue

                if class_count and not (0 <= class_id < class_count):
                    stats["badLines"] += 1
                    problems.append(
                        f"{split}/{stem}.txt:{line_no} 类别 id {class_id} 越界"
                        f"（应在 0~{class_count - 1}）"
                    )
                    continue
                if any(value < 0 or value > 1 for value in coords):
                    stats["badLines"] += 1
                    problems.append(
                        f"{split}/{stem}.txt:{line_no} 坐标未归一化到 0~1：{coords}"
                    )
                    continue
                if coords[2] <= 0 or coords[3] <= 0:
                    stats["badLines"] += 1
                    problems.append(f"{split}/{stem}.txt:{line_no} 宽或高不为正：{coords}")
                    continue

                stats["boxes"] += 1
                counts[class_id] += 1

    # 反向检查：标注文件没有对应图片
    for name in sorted(os.listdir(label_dir)):
        if not name.endswith(".txt"):
            continue
        stem = os.path.splitext(name)[0]
        if stem not in stems:
            stats["orphanLabels"] += 1
            problems.append(f"{split}: 标注 {name} 找不到对应图片（会被 ultralytics 忽略）")

    return stats, counts, problems


def write_data_yaml(
    out_path: str, dataset: str, splits: List[str], names: List[str]
) -> None:
    """
    手写 data.yaml，不依赖 PyYAML（容器内未必装了它）。

    类别名可能含中文，用 JSON 双引号转义 —— 双引号标量是合法 YAML，
    且 `ensure_ascii=False` 保证写入的是 UTF-8 原文而不是 \\uXXXX。
    """
    lines = [
        "# 由 ai-engine/scripts/prepare_dataset.py 生成",
        f"path: {json.dumps(os.path.abspath(dataset), ensure_ascii=False)}",
    ]
    for split in splits:
        lines.append(f"{split}: {json.dumps(os.path.join('images', split))}")
    lines.append(f"nc: {len(names)}")
    lines.append("names:")
    for index, name in enumerate(names):
        lines.append(f"  {index}: {json.dumps(name, ensure_ascii=False)}")

    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="校验数据集并生成 data.yaml")
    parser.add_argument("--dataset", required=True, help="数据集根目录")
    parser.add_argument("--classes", default="", help="classes.txt 路径（一行一个类别名）")
    parser.add_argument("--names", default="", help="逗号分隔的类别名，优先于 --classes")
    parser.add_argument("--out", default="", help="data.yaml 输出路径，缺省写到数据集根目录")
    parser.add_argument("--splits", default="train,val", help="要处理的 split，逗号分隔")
    parser.add_argument(
        "--force", action="store_true", help="即使校验发现问题也照常生成 data.yaml"
    )
    args = parser.parse_args()

    dataset = os.path.abspath(args.dataset)
    if not os.path.isdir(dataset):
        print(f"错误：数据集目录不存在 {dataset}", file=sys.stderr)
        return 2

    names = read_classes(args.classes, args.names)
    if not names:
        print(
            "错误：必须提供类别清单（--classes 或 --names）。\n"
            "      类别顺序必须与标注里的 class_id 严格一致 —— "
            "顺序错了，模型学到的就是错的映射，而且训练全程不会报错。",
            file=sys.stderr,
        )
        return 2

    splits = [item.strip() for item in args.splits.split(",") if item.strip()]
    print(f"数据集    : {dataset}")
    print(f"类别数    : {len(names)}")
    print(f"类别清单  : {'、'.join(names[:10])}{'...' if len(names) > 10 else ''}")
    print()

    all_problems: List[str] = []
    total_boxes = 0
    merged: Counter = Counter()

    for split in splits:
        stats, counts, problems = scan_split(dataset, split, len(names))
        total_boxes += stats["boxes"]
        merged.update(counts)
        print(f"[{split}] 图片 {stats['images']} 张，标注 {stats['labels']} 份，"
              f"框 {stats['boxes']} 个")
        print(f"       无标注的负样本图 {stats['negativeImages']} 张，"
              f"孤立标注 {stats['orphanLabels']} 份，非法行 {stats['badLines']} 行")
        all_problems.extend(problems)

    print()
    print(f"合计框数  : {total_boxes}")
    if merged:
        print("类别分布  :")
        for class_id, count in merged.most_common():
            label = names[class_id] if 0 <= class_id < len(names) else f"<越界 id={class_id}>"
            share = count / total_boxes * 100 if total_boxes else 0
            print(f"  {class_id:>3}  {label:<12} {count:>6}  {share:5.1f}%")
        missing = [names[i] for i in range(len(names)) if merged.get(i, 0) == 0]
        if missing:
            print(f"  未出现任何实例的类别（{len(missing)} 个）: {'、'.join(missing)}")
            print("  提示：这些类别的 mAP 恒为 0，会拉低整体指标；"
                  "要么补数据，要么从类别表里去掉。")

    if all_problems:
        print()
        print(f"发现 {len(all_problems)} 个问题（最多显示 20 条）：")
        for problem in all_problems[:20]:
            print(f"  - {problem}")
        if len(all_problems) > 20:
            print(f"  ... 另有 {len(all_problems) - 20} 条")

    out_path = args.out or os.path.join(dataset, "data.yaml")
    if all_problems and not args.force:
        print()
        print("存在问题，未生成 data.yaml。确认无误可加 --force 强制生成。", file=sys.stderr)
        return 1

    write_data_yaml(out_path, dataset, splits, names)
    print()
    print(f"已生成 {out_path}")
    print("下一步：")
    print(f"  python scripts/train.py --data {out_path} --model yolo11n.pt --epochs 100")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())