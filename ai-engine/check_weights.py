#!/usr/bin/env python
"""权重落地体检工具。

`model_manager.py` 的模块文档里点名了这个脚本 ——
「落地前用 `check_weights.py` 或本模块的 `inspect_weight()` / `validate()` 体检一次」。
但它此前并不存在：文档引用了一个缺失的工具。这里补上。

**为什么必须体检**：`best.pt` 本质是个 zip 包。用文本模式拷贝、编辑器另存、
或任何做 CRLF 规范化的工具碰过它之后，文件只会少掉几十字节 ——
肉眼看不出来，md5 也比不出"异常"（只是变成了另一个 md5），
但 zip 内所有偏移量会整体错位，torch 从此再也加载不了这个权重。
真正可靠的判据是「zip 结构完整 + 能列出成员」，这正是本脚本做的事。

只依赖标准库：**不需要 torch**，不进容器也能跑。

用法（在 `ai-engine/` 目录下）：

    python check_weights.py                  # 体检当前启用版本
    python check_weights.py wildlife-v1.0    # 体检指定版本
    python check_weights.py --all            # 体检所有已知版本
    python check_weights.py --json           # 输出 JSON，便于脚本消费
    python check_weights.py --quiet          # 只打印结论行

退出码：全部通过为 0，有任一版本未通过为 1 —— 可直接用作部署前置检查。
"""
import argparse
import json
import os
import sys

# 允许从任意工作目录执行：把 ai-engine/ 放进 sys.path，才能 import app.*
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.services.model_manager import get_model_manager  # noqa: E402


def _print_report(report: dict, verbose: bool = True) -> None:
    status = "OK  " if report["ok"] else "FAIL"
    print(f"[{status}] {report['version']}")

    if verbose:
        print(f"       模型根目录    : {report['modelRoot']}")
        print(f"       期望权重      : {report['expectedWeightPath']}")
        if report["weightExists"]:
            print(f"       实际权重      : {report['weightPath']}")
            print(f"       大小          : {report['weightSizeBytes']} bytes")
            print(f"       md5           : {report['weightMd5']}")
        print(f"       回退到备用权重: {'是' if report['usingFallback'] else '否'}")

        classes = report["declaredClasses"]
        if classes:
            preview = "、".join(classes[:8]) + ("..." if len(classes) > 8 else "")
            print(f"       声明类别      : {len(classes)} 个（{preview}）")
        else:
            print("       声明类别      : 未声明")

        if report["duplicateWith"]:
            print(f"       同一权重还有  : {', '.join(report['duplicateWith'])}")

    for issue in report["issues"]:
        print(f"       - {issue}")


def main() -> int:
    parser = argparse.ArgumentParser(description="模型权重体检")
    parser.add_argument(
        "version", nargs="?", default=None, help="要体检的版本；缺省为当前启用版本"
    )
    parser.add_argument("--all", action="store_true", help="体检所有已知版本")
    parser.add_argument("--json", action="store_true", help="以 JSON 输出")
    parser.add_argument("--quiet", action="store_true", help="只打印结论行")
    args = parser.parse_args()

    manager = get_model_manager()

    if args.all:
        reports = [manager.validate(version) for version in manager.list_versions()]
    else:
        reports = [manager.validate(args.version)]

    if args.json:
        payload = reports if args.all else reports[0]
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        for report in reports:
            _print_report(report, verbose=not args.quiet)

    failed = [report for report in reports if not report["ok"]]
    if failed:
        print(f"\n{len(failed)}/{len(reports)} 个版本未通过体检。")
        if any(report["usingFallback"] for report in failed):
            print(
                "注意：存在「回退到备用权重」的版本 —— "
                "该版本的识别结果与版本号不对应，页面上显示的模型版本是失真的。"
            )
        return 1

    print(f"\n{len(reports)}/{len(reports)} 个版本体检通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())