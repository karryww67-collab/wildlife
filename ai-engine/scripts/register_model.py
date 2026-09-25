#!/usr/bin/env python
"""把训练好的权重登记到后端 `model_version` 表。

对应接口 `POST /api/models`（见 `backend/.../controller/ModelController.java`），
请求体就是 `ModelVersion` 实体的 JSON 形态：

    {"modelName": "...", "version": "...", "modelPath": "/models/<版本>/best.pt",
     "classConfig": "[\"大熊猫\", ...]", "precisionValue": 0.85, "recallValue": 0.79,
     "map50": 0.82, "map5095": 0.65, "status": "DISABLED"}

指标与类别清单直接取自 `scripts/evaluate.py` 写出的 `meta.json`，
**不手工填数** —— 手工填的 mAP 无法复现，也就无法核验。

该接口需要管理员 token（`Authorization`/`X-Auth-Token` 皆可，见后端鉴权约定）。
没有 token 时脚本只打印将要发送的请求体与等价 curl 命令，不发送 ——
这样在流水线里可以先生成再人工确认。

用法：

    # 先看要发什么（不发请求）
    python scripts/register_model.py --meta /models/wildlife-v2.0/meta.json \\
        --version wildlife-v2.0 --model-path /models/wildlife-v2.0/best.pt

    # 拿到 token 后真正登记
    set WILDLIFE_TOKEN=<管理员 token>
    python scripts/register_model.py --meta /models/wildlife-v2.0/meta.json \\
        --version wildlife-v2.0 --model-path /models/wildlife-v2.0/best.pt --send
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_API_BASE = os.getenv("WILDLIFE_API_BASE", "http://localhost:8085")


def load_meta(path: str) -> dict:
    if not os.path.exists(path):
        print(f"错误：meta.json 不存在 {path}", file=sys.stderr)
        print("      先用 scripts/evaluate.py 生成它。", file=sys.stderr)
        raise SystemExit(2)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"错误：meta.json 无法解析（{exc}）", file=sys.stderr)
        raise SystemExit(2)


def resolve_class_config(classes_arg: str, meta: dict, meta_path: str) -> str:
    """
    解析出**真正的类别清单**（JSON 数组字符串），而不是文件名。

    这是个容易踩的坑：`meta.json` 里的 `classConfig` 常常写的是**文件名**
    （例如 `"classConfig": "classes.txt"`）。直接把它登记进库，
    `model_version.class_config` 就会变成"一个名叫 classes.txt 的类别" ——
    页面的类别下拉会莫名多出这一项，人工复核选错类别时非常难查。

    按优先级解析：
      1) `--classes` 显式指定的文件
      2) `classConfig` 本身已是数组/逗号/换行列表 —— 归一化即可
      3) `classConfig` 是文件名 —— 相对 meta.json 所在目录读取
      4) meta.json 同目录下的 `classes.txt`

    解析不出就返回空串：宁可让库里 `class_config` 为空（前端会退化为
    "无类别可选"），也不要写入一个错误的类别名。
    """
    names: list = []

    def read_names_file(path: str) -> list:
        try:
            with open(path, "r", encoding="utf-8") as handle:
                return [line.strip() for line in handle if line.strip()]
        except OSError:
            return []

    if classes_arg:
        names = read_names_file(classes_arg)

    if not names:
        raw = (meta.get("classConfig") or "").strip()
        if raw:
            # 情况 2：本身就是列表
            if raw.startswith("[") or any(sep in raw for sep in (",", "\n", "，", "；", ";")):
                cleaned = raw.replace("[", " ").replace("]", " ").replace('"', " ")
                for sep in ("，", "；", ";", "\n", "\r"):
                    cleaned = cleaned.replace(sep, ",")
                names = [item.strip() for item in cleaned.split(",") if item.strip()]
            else:
                # 情况 3：看起来是文件名 —— 相对 meta.json 目录解析
                candidate = os.path.join(os.path.dirname(os.path.abspath(meta_path)), raw)
                names = read_names_file(candidate)

    if not names:
        # 情况 4：同目录下的 classes.txt
        names = read_names_file(
            os.path.join(os.path.dirname(os.path.abspath(meta_path)), "classes.txt")
        )

    return json.dumps(names, ensure_ascii=False) if names else ""


def main() -> int:
    parser = argparse.ArgumentParser(description="登记模型版本到 model_version 表")
    parser.add_argument("--meta", required=True, help="evaluate.py 写出的 meta.json")
    parser.add_argument("--version", required=True, help="版本号，如 wildlife-v2.0")
    parser.add_argument("--model-path", required=True, help="容器内权重路径，如 /models/wildlife-v2.0/best.pt")
    parser.add_argument("--model-name", default="", help="缺省取 meta.json 的 modelName")
    parser.add_argument("--description", default="", help="缺省取 meta.json 的 description")
    parser.add_argument(
        "--classes",
        default="",
        help="类别清单文件（classes.txt）；缺省按 meta.json 的 classConfig 自动解析",
    )
    parser.add_argument(
        "--status",
        default="DISABLED",
        choices=["DISABLED", "ENABLED"],
        help="登记后的状态；缺省 DISABLED —— 登记不应顺带切换线上启用的模型",
    )
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--token", default=os.getenv("WILDLIFE_TOKEN", ""))
    parser.add_argument(
        "--send", action="store_true", help="真正发送请求；不给则只打印请求体"
    )
    args = parser.parse_args()

    meta = load_meta(args.meta)

    # 未经验证的指标不进库：宁可留空，也不要让页面上出现一个无法溯源的 mAP
    verified = bool(meta.get("metricsVerified"))
    payload = {
        "modelName": args.model_name or meta.get("modelName") or "wildlife-detector",
        "version": args.version,
        "modelPath": args.model_path,
        "classConfig": resolve_class_config(args.classes, meta, args.meta),
        "description": args.description or meta.get("description") or "",
        "precisionValue": meta.get("precisionValue") if verified else None,
        "recallValue": meta.get("recallValue") if verified else None,
        "map50": meta.get("map50") if verified else None,
        "map5095": meta.get("map5095") if verified else None,
        "status": args.status,
    }

    print(f"目标接口 : POST {args.api_base.rstrip('/')}/api/models")
    print(f"metricsVerified : {verified}")
    if not verified:
        print("⚠️  meta.json 未标记 metricsVerified，指标字段将留空登记。")
        print("    人工填写的 mAP 无法复现，登记进库只会让页面显示一个查不到出处的数字。")
    if meta.get("weightsMd5"):
        print(f"权重 md5 : {meta['weightsMd5']}（{meta.get('weightsSizeBytes')} bytes）")
    print()
    body = json.dumps(payload, ensure_ascii=False, indent=2)
    print(body)
    print()

    token = args.token
    if not args.send or not token:
        if not token:
            print("未提供 token（--token 或环境变量 WILDLIFE_TOKEN），仅打印不发送。")
        print("等价命令：")
        print(f"  curl -X POST {args.api_base.rstrip('/')}/api/models \\")
        print("       -H 'Content-Type: application/json' \\")
        print("       -H 'X-Auth-Token: <管理员 token>' \\")
        print(f"       -d @- <<'JSON'\n{body}\nJSON")
        return 0

    request = urllib.request.Request(
        f"{args.api_base.rstrip('/')}/api/models",
        data=body.encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "X-Auth-Token": token,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode("utf-8", errors="replace")
            print(f"HTTP {response.status}")
            print(text)
            print("\n登记成功。到「模型管理」页确认指标与状态；")
            print("启用前请先跑 python check_weights.py <版本> 确认不会回退。")
            return 0
    except urllib.error.HTTPError as exc:
        print(f"HTTP {exc.code}", file=sys.stderr)
        print(exc.read().decode("utf-8", errors="replace"), file=sys.stderr)
        if exc.code == 403:
            print("403 表示该 token 不是管理员。", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"请求失败：{exc.reason}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())