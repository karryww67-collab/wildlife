#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图像存储一致性巡检 —— 只读，不写库、不删文件。

背景
----
`recognition_image.file_path` 存的是**绝对路径**（容器内口径，如
`/shared-data/originals/20260922/ab12....jpg`），物理文件落在 bind mount
`./ai-engine/data` 上。这个目录一旦被清理（手工删、重建 data、换机器部署），
数据库里就会留下一批"有行无文件"的**悬空引用**：

  * `GET /api/images/{id}/raw` → `ImageController.serveFile()` 里
    `Files.exists()` 为 false → 返回 **404**；
  * 前端 `ReviewDialog` 走 axios 拿 Blob，404 抛错 → 弹窗显示"图像加载失败"；
  * 人工复核队列按置信度升序排，低分的老记录排最前，所以一打开就撞上。

本脚本把这类悬空行查出来，并统计它们牵连了多少条识别结果 / 待复核项。
**只读**：只做 SELECT 与列目录，不产生任何写操作。

用法
----
    # 默认读本机 docker 容器（wildlife-mysql / wildlife-backend）
    python ai-engine/scripts/check_image_consistency.py

    # 只看汇总，不列明细
    python ai-engine/scripts/check_image_consistency.py --no-detail

    # 输出 JSON（便于喂给别的脚本）
    python ai-engine/scripts/check_image_consistency.py --json

    # 容器没起时，直接扫宿主机目录
    python ai-engine/scripts/check_image_consistency.py --data-dir ai-engine/data

退出码
------
    0 = 全部一致
    1 = 存在悬空引用（便于挂进 CI / 巡检定时任务）
    2 = 巡检本身失败（连不上容器等）
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from collections import Counter, defaultdict

# ── 默认值（与本机 docker-compose.yml 对齐）─────────────────────────────────
DEFAULT_MYSQL_CONTAINER = "wildlife-mysql"
DEFAULT_BACKEND_CONTAINER = "wildlife-backend"
DEFAULT_DB = "wildlife_db"
DEFAULT_MYSQL_USER = "root"
DEFAULT_MYSQL_PASSWORD = os.environ.get("WILDLIFE_MYSQL_PASSWORD", "root123")
DEFAULT_STORAGE_ROOT = "/shared-data"
DEFAULT_HOST_DATA_DIR = os.path.join("ai-engine", "data")

# image_path 里 originals 之后的第一段目录名，用作分组（例如 20260922）
ORIGINALS_MARKER = "originals"


def _configure_stdout() -> None:
    """Windows 控制台默认 GBK，中文报告会炸；尽量切成 UTF-8。"""
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def _run(cmd: list[str], timeout: int = 60) -> tuple[int, str, str]:
    """跑一条命令，返回 (exit_code, stdout, stderr)。"""
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        return 127, "", f"命令不存在: {cmd[0]}"
    except subprocess.TimeoutExpired:
        return 124, "", f"命令超时: {' '.join(cmd)}"

    out = proc.stdout.decode("utf-8", errors="replace")
    err = proc.stderr.decode("utf-8", errors="replace")
    return proc.returncode, out, err


# ── 取数 ────────────────────────────────────────────────────────────────────

def query_tsv(args: argparse.Namespace, sql: str) -> list[list[str]]:
    """通过 docker exec mysql 跑一条 SELECT，返回 TSV 拆好的二维列表。"""
    cmd = [
        "docker", "exec", args.mysql_container,
        "mysql",
        f"-u{args.mysql_user}",
        f"-p{args.mysql_password}",
        args.database,
        "-N", "-B", "--default-character-set=utf8mb4",
        "-e", sql,
    ]
    code, out, err = _run(cmd)
    if code != 0:
        raise RuntimeError(f"查询失败（exit {code}）: {err.strip() or out.strip()}")

    rows: list[list[str]] = []
    for line in out.splitlines():
        line = line.rstrip("\r")
        if not line:
            continue
        # mysql -B 用 \t 分隔；NULL 输出为字面量 "NULL"
        rows.append(line.split("\t"))
    return rows


def load_images(args: argparse.Namespace) -> list[dict]:
    sql = (
        "SELECT id, file_name, file_path, task_id, status "
        "FROM recognition_image ORDER BY id;"
    )
    rows = query_tsv(args, sql)
    images = []
    for r in rows:
        if len(r) < 5:
            continue
        images.append(
            {
                "id": int(r[0]),
                "file_name": r[1],
                "file_path": "" if r[2] == "NULL" else r[2],
                "task_id": None if r[3] == "NULL" else int(r[3]),
                "status": r[4],
            }
        )
    return images


def load_result_counts(args: argparse.Namespace) -> dict[int, Counter]:
    """image_id -> Counter(review_status -> 条数)，按 (image_id, review_status) 聚合取回。"""
    sql = (
        "SELECT image_id, review_status, COUNT(*) "
        "FROM detection_result GROUP BY image_id, review_status;"
    )
    rows = query_tsv(args, sql)
    per_image: dict[int, Counter] = defaultdict(Counter)
    for r in rows:
        if len(r) < 3:
            continue
        image_id = int(r[0])
        status = r[1]
        count = int(r[2])
        per_image[image_id][status] += count
    return per_image


def load_tasks(args: argparse.Namespace) -> dict[int, dict]:
    sql = "SELECT id, task_name, status FROM recognition_task;"
    rows = query_tsv(args, sql)
    tasks: dict[int, dict] = {}
    for r in rows:
        if len(r) < 3:
            continue
        tasks[int(r[0])] = {"id": int(r[0]), "task_name": r[1], "status": r[2]}
    return tasks


# ── 盘上文件枚举 ─────────────────────────────────────────────────────────────

def dir_of(path: str) -> str:
    return path.rsplit("/", 1)[0] if "/" in path else ""


def base_of(path: str) -> str:
    return path.rsplit("/", 1)[-1]


def group_key(path: str) -> str:
    """originals/<这一段>/ 作为分组名；没有则回落为所在目录。"""
    parts = [p for p in path.split("/") if p]
    if ORIGINALS_MARKER in parts:
        i = parts.index(ORIGINALS_MARKER)
        if i + 1 < len(parts) - 1:
            return parts[i + 1]
    return dir_of(path) or "(未知目录)"


class DirLister:
    """列举某个目录下的文件名集合 —— 走容器或宿主机，取决于配置。"""

    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.via = "container" if not args.data_dir else "host"
        self._cache: dict[str, set[str] | None] = {}
        self._container_broken = False

    def _list_container(self, container_dir: str) -> set[str] | None:
        cmd = ["docker", "exec", self.args.backend_container, "ls", "-1", container_dir]
        code, out, err = _run(cmd)
        if code != 0:
            # 目录不存在 → 空集合；容器/命令不可用 → None（交给上层处理）
            if "No such file" in err or "Not a directory" in err:
                return set()
            return None
        return {line.strip() for line in out.splitlines() if line.strip()}

    def _list_host(self, container_dir: str) -> set[str]:
        root = self.args.storage_root.rstrip("/")
        if not container_dir.startswith(root):
            return set()
        rel = container_dir[len(root):].lstrip("/")
        host_dir = os.path.join(self.args.data_dir, *rel.split("/")) if rel else self.args.data_dir
        try:
            return {n for n in os.listdir(host_dir) if os.path.isfile(os.path.join(host_dir, n))}
        except (FileNotFoundError, NotADirectoryError):
            return set()

    def list_dir(self, container_dir: str) -> set[str] | None:
        if container_dir in self._cache:
            return self._cache[container_dir]

        names: set[str] | None = None
        if self.via == "host":
            names = self._list_host(container_dir)
        else:
            names = self._list_container(container_dir)
            if names is None:
                self._container_broken = True
                raise RuntimeError(
                    f"容器 {self.args.backend_container} 不可用，无法列目录 {container_dir}；"
                    f"可改用 --data-dir 直接扫宿主机目录"
                )

        self._cache[container_dir] = names
        return names


# ── 主流程 ──────────────────────────────────────────────────────────────────

def inspect(args: argparse.Namespace) -> dict:
    images = load_images(args)
    results = load_result_counts(args)
    tasks = load_tasks(args)
    lister = DirLister(args)

    dir_names: dict[str, set[str]] = {}
    for img in images:
        d = dir_of(img["file_path"])
        if d and d not in dir_names:
            dir_names[d] = lister.list_dir(d) or set()

    on_disk: list[dict] = []
    missing: list[dict] = []
    no_path: list[dict] = []

    for img in images:
        path = img["file_path"]
        if not path:
            no_path.append(img)
            continue
        names = dir_names.get(dir_of(path), set())
        (on_disk if base_of(path) in names else missing).append(img)

    # 悬空行按目录 / 任务聚合，并带上牵连的结果条数
    by_group: dict[str, dict] = defaultdict(
        lambda: {"images": 0, "results": 0, "pending": 0, "task_ids": set()}
    )
    for img in missing:
        g = by_group[group_key(img["file_path"])]
        g["images"] += 1
        cnt = results.get(img["id"])
        if cnt:
            g["results"] += sum(cnt.values())
            g["pending"] += cnt.get("PENDING", 0)
        if img["task_id"] is not None:
            g["task_ids"].add(img["task_id"])

    by_task: dict[int, dict] = defaultdict(
        lambda: {"images": 0, "results": 0, "pending": 0}
    )
    for img in missing:
        if img["task_id"] is None:
            continue
        t = by_task[img["task_id"]]
        t["images"] += 1
        cnt = results.get(img["id"])
        if cnt:
            t["results"] += sum(cnt.values())
            t["pending"] += cnt.get("PENDING", 0)

    total_results = sum(sum(c.values()) for c in results.values())
    total_pending = sum(c.get("PENDING", 0) for c in results.values())
    missing_pending = sum(
        results.get(img["id"], Counter()).get("PENDING", 0) for img in missing
    )
    missing_results = sum(sum(results.get(img["id"], Counter()).values()) for img in missing)

    detail = []
    for img in missing:
        cnt = results.get(img["id"], Counter())
        detail.append(
            {
                "image_id": img["id"],
                "file_name": img["file_name"],
                "file_path": img["file_path"],
                "task_id": img["task_id"],
                "status": img["status"],
                "results": sum(cnt.values()),
                "pending": cnt.get("PENDING", 0),
            }
        )

    return {
        "via": lister.via,
        "storage_root": args.storage_root if lister.via == "container" else args.data_dir,
        "images_total": len(images),
        "images_on_disk": len(on_disk),
        "images_missing": len(missing),
        "images_without_path": len(no_path),
        "results_total": total_results,
        "results_pending_total": total_pending,
        "results_of_missing_images": missing_results,
        "results_pending_of_missing_images": missing_pending,
        "dangling_dirs": sorted({group_key(i["file_path"]) for i in missing}),
        "by_group": {
            k: {
                "images": v["images"],
                "results": v["results"],
                "pending": v["pending"],
                "task_ids": sorted(v["task_ids"]),
            }
            for k, v in sorted(by_group.items())
        },
        "by_task": {
            str(k): {
                "task_name": tasks.get(k, {}).get("task_name", "?"),
                "task_status": tasks.get(k, {}).get("status", "?"),
                **v,
            }
            for k, v in sorted(by_task.items())
        },
        "missing_detail": detail,
    }


def render(report: dict, args: argparse.Namespace) -> str:
    L: list[str] = []
    add = L.append
    add("=" * 68)
    add("图像存储一致性巡检（只读）")
    add("=" * 68)
    add(f"数据来源：{report['via']}  存储根：{report['storage_root']}")
    add("")
    add("【总览】")
    add(f"  recognition_image 总行数        : {report['images_total']}")
    add(f"    ├─ 物理文件存在               : {report['images_on_disk']}")
    add(f"    ├─ 物理文件已丢失（悬空引用） : {report['images_missing']}")
    add(f"    └─ file_path 为空             : {report['images_without_path']}")
    add(f"  detection_result 总条数         : {report['results_total']}")
    add(f"    其中待复核 PENDING            : {report['results_pending_total']}")
    add("")
    add(f"  悬空图像牵连的识别结果          : {report['results_of_missing_images']}")
    add(f"  悬空图像牵连的待复核项          : {report['results_pending_of_missing_images']}"
        "   ← 这些项在复核页看不到图")
    add("")

    if not report["images_missing"]:
        add("结论：未发现悬空引用，库与磁盘一致。")
        add("=" * 68)
        return "\n".join(L)

    add("【按存储目录分组】")
    add(f"  {'目录':<16}{'悬空图像':>10}{'结果数':>10}{'待复核':>10}   涉及任务")
    for name, g in report["by_group"].items():
        task_ids = ",".join(str(t) for t in g["task_ids"]) or "-"
        add(f"  {name:<16}{g['images']:>10}{g['results']:>10}{g['pending']:>10}   {task_ids}")
    add("")

    add("【按任务分组】")
    add(f"  {'任务ID':<8}{'任务名':<28}{'状态':<12}{'悬空图':>8}{'待复核':>8}")
    for tid, t in report["by_task"].items():
        name = (t["task_name"] or "?")[:26]
        add(f"  {tid:<8}{name:<28}{t['task_status']:<12}{t['images']:>8}{t['pending']:>8}")
    add("")

    if args.no_detail:
        add("（--no-detail：已省略逐条明细）")
    else:
        detail = report["missing_detail"]
        shown = detail[: args.limit]
        add(f"【悬空明细】共 {len(detail)} 条，显示前 {len(shown)} 条")
        add(f"  {'image_id':>9}  {'task':>5}  {'待复核':>6}  文件名 / 丢失路径")
        for d in shown:
            add(f"  {d['image_id']:>9}  {str(d['task_id']):>5}  {d['pending']:>6}  "
                f"{d['file_name']}")
            add(f"  {'':>9}  {'':>5}  {'':>6}  -> {d['file_path']}")
        if len(detail) > len(shown):
            add(f"  ... 另有 {len(detail) - len(shown)} 条，用 --limit 调整显示条数")

    add("")
    add("【处置建议】")
    add("  1) 原图有备份：按 file_path 里的文件名（UUID）放回 ai-engine/data/originals/<日期>/")
    add("     —— 名字必须与 file_path 完全一致，否则依旧 404。")
    add("  2) 原图无法找回：这些是脏数据。可删除对应旧任务（连带 result/image 行），")
    add("     ⚠️ 但删除会改变检出率分母（只有 status='SUCCESS' 的图像才计入），")
    add("        动手前先确认论文里的数字用不到它们。")
    add("  3) 系统已自带兜底：待复核队列默认过滤掉「原图已丢失」的项（可取消勾选查看），")
    add("     后端对这类结果直接拒绝复核；巡检本身也建议定期跑一次。")
    add("=" * 68)
    return "\n".join(L)


def main() -> int:
    _configure_stdout()

    p = argparse.ArgumentParser(
        description="巡检 recognition_image.file_path 与磁盘实际文件的一致性（只读）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--mysql-container", default=DEFAULT_MYSQL_CONTAINER)
    p.add_argument("--backend-container", default=DEFAULT_BACKEND_CONTAINER)
    p.add_argument("--database", default=DEFAULT_DB)
    p.add_argument("--mysql-user", default=DEFAULT_MYSQL_USER)
    p.add_argument("--mysql-password", default=DEFAULT_MYSQL_PASSWORD)
    p.add_argument("--storage-root", default=DEFAULT_STORAGE_ROOT,
                   help="file_path 的容器内前缀，默认 /shared-data")
    p.add_argument("--data-dir", default="",
                   help="容器不可用时，改用宿主机目录直接扫描（如 ai-engine/data）")
    p.add_argument("--limit", type=int, default=20, help="明细显示条数，默认 20")
    p.add_argument("--no-detail", action="store_true", help="只输出汇总，不列明细")
    p.add_argument("--json", action="store_true", help="输出 JSON 而非文本报告")
    args = p.parse_args()

    try:
        report = inspect(args)
    except Exception as exc:  # noqa: BLE001 —— 巡检失败要给人话，不要 traceback 糊脸
        print(f"[巡检失败] {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(render(report, args))

    return 1 if report["images_missing"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
