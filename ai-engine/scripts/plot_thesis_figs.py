#!/usr/bin/env python3
"""生成论文第 8 章的两张图（图 8-1 / 图 8-2）。

这两张图没有训练时自动生成的素材，数据来自实测记录：
  - 图 8-1：1200 张端到端实测（ai-engine/scripts/scale_result_1200.json）
            + 纯推理基准（ai-engine/scripts/bench_inference.py）
  - 图 8-2：batch=1 vs batch=3 的置信度对照（README 第 8.5 节）

数值口径与 `论文图表清单.md` 第三节逐项一致，改数据请同时改那份文档。

用法：
    python ai-engine/scripts/plot_thesis_figs.py [输出目录]
默认输出到仓库根目录的 `论文插图/`。
"""
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch

# ── 全局样式：论文插图用白底、无上右边框、300 dpi ──────────────────────
plt.rcParams["font.sans-serif"] = ["SimHei", "Microsoft YaHei", "SimSun"]
plt.rcParams["axes.unicode_minus"] = False
plt.rcParams["figure.dpi"] = 300
plt.rcParams["savefig.dpi"] = 300
plt.rcParams["savefig.bbox"] = "tight"
plt.rcParams["axes.edgecolor"] = "#333333"
plt.rcParams["axes.linewidth"] = 0.9
plt.rcParams["font.size"] = 11

REPO = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "论文插图"
OUT.mkdir(parents=True, exist_ok=True)

GREEN = "#3F7D5C"
ORANGE = "#B5563A"
GREY = "#7A7A7A"
LIGHT = "#8FB49E"
DARK = "#2F5D45"


def _clean(ax, grid_axis="y"):
    """去掉上/右边框，只留浅色网格。"""
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.grid(axis=grid_axis, color="#DDDDDD", linewidth=0.7, zorder=0)
    ax.set_axisbelow(True)


# ══════════════════════════════════════════════════════════════════════
# 图 8-1　端到端吞吐与瓶颈定位
# ══════════════════════════════════════════════════════════════════════
def fig_8_1():
    labels = [
        "图像上传\n（每批 200 张）",
        "端到端识别\n（推理 + 回调 + 入库）",
        "纯推理基准\n（batch=8）",
    ]
    values = [42.1, 7.49, 7.46]
    colors = [GREEN, ORANGE, GREY]

    fig, ax = plt.subplots(figsize=(9.2, 4.6))
    ypos = [2, 1, 0]
    bars = ax.barh(ypos, values, height=0.52, color=colors, zorder=3)

    for y, v in zip(ypos, values):
        ax.text(
            v + 1.0, y, f"{v:.2f} 张/秒",
            va="center", ha="left", fontsize=12, fontweight="bold", color="#222222",
        )

    ax.set_yticks(ypos)
    ax.set_yticklabels(labels, fontsize=11)
    ax.set_xlim(0, 52)
    ax.set_xlabel("吞吐量（张/秒）", fontsize=11)
    ax.set_title("各环节吞吐量对比", fontsize=13, pad=14)
    _clean(ax, grid_axis="x")

    # 上传 ÷ 端到端 = 5.6 倍：在上传条内画白色双向箭头，直观显示多出来的长度
    ax.annotate(
        "", xy=(41.2, 2), xytext=(8.2, 2),
        arrowprops=dict(arrowstyle="<->", color="white", linewidth=1.8),
    )
    ax.text(
        24.7, 2, "5.6 倍（上传 ÷ 端到端）", ha="center", va="center",
        fontsize=11.5, color="white", fontweight="bold",
        bbox=dict(facecolor=GREEN, edgecolor="none", pad=4),
    )

    # 端到端 ≈ 纯推理：文字放在两条短条的右侧中点，不加箭头，避免指向不明
    ax.text(
        9.4, 0.5,
        "端到端与纯推理仅差 0.4%\n"
        "队列、回调与批量入库的开销\n"
        "在测量精度内可忽略",
        ha="left", va="center", fontsize=10, color="#333333", linespacing=1.7,
    )

    ax.text(
        0.985, 0.055,
        "推理瓶颈型系统：上传 42.1 张/秒，远高于识别 7.49 张/秒",
        transform=ax.transAxes, ha="right", va="bottom",
        fontsize=10, color=ORANGE, fontweight="bold",
    )

    path = OUT / "图8-1_端到端吞吐与瓶颈定位.png"
    fig.savefig(path)
    plt.close(fig)
    return path


# ══════════════════════════════════════════════════════════════════════
# 图 8-2　批量大小对检测置信度的影响
# ══════════════════════════════════════════════════════════════════════
def fig_8_2():
    # (图像, 类别, batch=1, batch=3) —— 顺序与 论文图表清单.md 一致
    rows = [
        ("deer.jpg", "赤麂", 0.7762, 0.7727),
        ("deer.jpg", "水鹿", 0.4741, 0.2669),
        ("elephant.jpg", "虎", 0.4612, 0.5539),
        ("elephant.jpg", "豹", 0.3537, 0.3411),
        ("tiger.jpg", "虎", 0.7791, 0.8603),
    ]
    labels = [f"{c}\n{img}" for img, c, _, _ in rows]
    b1 = [r[2] for r in rows]
    b3 = [r[3] for r in rows]

    fig, ax = plt.subplots(figsize=(10.2, 5.2))
    x = list(range(len(rows)))
    w = 0.36

    ax.bar([i - w / 2 for i in x], b1, width=w, label="batch = 1",
           color=LIGHT, edgecolor=DARK, linewidth=0.6, zorder=3)
    ax.bar([i + w / 2 for i in x], b3, width=w, label="batch = 3",
           color=DARK, zorder=3)

    for i, (v1, v3) in enumerate(zip(b1, b3)):
        ax.text(i - w / 2, v1 + 0.012, f"{v1:.4f}", ha="center", va="bottom",
                fontsize=8.5, color="#333333")
        ax.text(i + w / 2, v3 + 0.012, f"{v3:.4f}", ha="center", va="bottom",
                fontsize=8.5, color="#333333")

    # 复核阈值参考线（标签放左侧空白，避开所有柱子）
    ax.axhline(0.6, color=ORANGE, linestyle="--", linewidth=1.4, zorder=4)
    ax.text(-0.72, 0.622, "人工复核阈值 0.6",
            ha="left", va="bottom", fontsize=9.5, color=ORANGE, fontweight="bold")

    # 最大差值：水鹿 0.4741 → 0.2669。箭头指向两根柱子之间的空隙，
    # 不压在数值标签上（数值标签在柱顶，箭头终点落在两柱中间）。
    ax.annotate(
        "最大差值 Δ = 0.2072\n（水鹿，batch=1 → batch=3）",
        xy=(1.0, 0.37), xytext=(1.60, 0.74),
        fontsize=10, color="#C0392B", linespacing=1.5, ha="left", va="center",
        arrowprops=dict(arrowstyle="->", color="#C0392B", linewidth=1.2,
                        connectionstyle="arc3,rad=-0.18"),
    )

    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=10)
    ax.set_xlim(-0.78, 4.5)
    ax.set_ylim(0, 1.0)
    ax.set_ylabel("检测置信度", fontsize=11)
    ax.set_title("同一图像在不同批量下的置信度差异", fontsize=13, pad=14)
    ax.legend(loc="upper left", frameon=False, fontsize=10.5, ncol=2)
    _clean(ax, grid_axis="y")

    ax.text(
        0.5, -0.175,
        "检出类别集合与框数量不变，但置信度最大相差 0.2072、框坐标最大偏移 17.0 px；\n"
        "该幅度足以使结果在 0.6 阈值两侧跳变，故识别结果必须与 BATCH_SIZE 绑定报告",
        transform=ax.transAxes, ha="center", va="top",
        fontsize=9, color="#444444", linespacing=1.6,
    )

    path = OUT / "图8-2_批量大小对置信度的影响.png"
    fig.savefig(path)
    plt.close(fig)
    return path


if __name__ == "__main__":
    p1 = fig_8_1()
    p2 = fig_8_2()
    for p in (p1, p2):
        print(f"{p.name}\t{p.stat().st_size} B")
