#!/usr/bin/env python3
"""10 万张规模论证 —— 中等规模端到端实测（论文素材第 8.4 节的 C 部分）。

全程走真实 HTTP 接口：登录 → 生成负载 → 分批上传 → 建任务 → 轮询至终态。
每个阶段单独计时，并核对图像数与检出框数是否守恒。

必须在 ai-engine 容器内运行（要靠容器网络访问 backend:8085）：

    docker cp ai-engine/scripts/scale_test.py wildlife-ai-engine:/tmp/scale_test.py
    docker exec wildlife-ai-engine python /tmp/scale_test.py 1200

结果写入容器内 /tmp/scale_result.json，用 docker cp 取回即可。

注意：负载由 ai-engine/tests 里的真图循环复制而成，只用于造量，与精度无关；
尺寸分布比真实数据集窄，结论只对"该尺寸分布 + batch=8 + 单 worker"成立。
"""
import json
import sys
import time
from pathlib import Path

import requests

BASE = "http://backend:8085"
SRC_DIRS = [Path("/tmp/tests"), Path("/shared-data/originals")]
OUT = Path("/tmp/scale")
N = int(sys.argv[1]) if len(sys.argv) > 1 else 1200
UP_BATCH = 200          # 与 ImageService.MAX_BATCH_SIZE 一致
POLL = 5
DEADLINE = 7200
RESULT = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("/tmp/scale_result.json")


def log(msg):
    print(msg, flush=True)


def pick_sources():
    """优先用 /tmp/tests（容器内的 3 张真图），退回到共享目录里已上传的原图。"""
    for d in SRC_DIRS:
        if d.is_dir():
            got = sorted(p for p in d.rglob("*")
                         if p.is_file() and p.suffix.lower() in (".jpg", ".jpeg", ".png"))
            if got:
                return d, got
    return None, []


T_ALL = time.time()
rep = {}

# ── 1. 登录 ────────────────────────────────────────────────────────────────
t0 = time.time()
r = requests.post(f"{BASE}/api/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
r.raise_for_status()
TOKEN = r.json()["token"]
H = {"X-Auth-Token": TOKEN}
log(f"[1] login {time.time()-t0:.2f}s")

# ── 2. 生成负载 ────────────────────────────────────────────────────────────
t0 = time.time()
OUT.mkdir(parents=True, exist_ok=True)
src_dir, srcs = pick_sources()
if not srcs:
    log("FATAL: no source images found")
    sys.exit(1)
log(f"    source dir = {src_dir}  ({len(srcs)} distinct images)")
files = []
for i in range(N):
    dst = OUT / f"scale_{i:05d}.jpg"
    if not dst.exists():
        dst.write_bytes(srcs[i % len(srcs)].read_bytes())
    files.append(dst)
rep["genSeconds"] = round(time.time() - t0, 2)
TOTAL_BYTES = sum(p.stat().st_size for p in files)
rep["imageCount"] = len(files)
rep["distinctSourceImages"] = len(srcs)
rep["sourceDir"] = str(src_dir)
rep["totalBytes"] = TOTAL_BYTES
log(f"[2] gen {N} imgs {rep['genSeconds']}s {TOTAL_BYTES/1048576:.1f} MB")

# ── 3. 分批上传 ────────────────────────────────────────────────────────────
t0 = time.time()
ids = []
batch_times = []
for s in range(0, N, UP_BATCH):
    chunk = files[s:s + UP_BATCH]
    handles = [(p.name, open(p, "rb"), "image/jpeg") for p in chunk]
    bsw = time.time()
    try:
        r = requests.post(f"{BASE}/api/images/upload-batch",
                          files=[("files", h) for h in handles],
                          headers=H, timeout=1800)
        r.raise_for_status()
        j = r.json()
    finally:
        for _, fh, _ in handles:
            fh.close()
    batch_times.append(round(time.time() - bsw, 2))
    ids += [im["id"] for im in j["images"]]
    if j["failedCount"]:
        log(f"    WARN failures={j['failedCount']} {j['failures'][:2]}")
    log(f"    {len(ids)}/{N}  batch {batch_times[-1]}s  total {time.time()-t0:.1f}s")
UP = time.time() - t0
rep["uploadSeconds"] = round(UP, 2)
rep["uploadedCount"] = len(ids)
rep["batchSeconds"] = batch_times
rep["uploadPerSec"] = round(len(ids) / UP, 1)
rep["uploadMBps"] = round((TOTAL_BYTES / 1048576) / UP, 2)
log(f"[3] uploaded {len(ids)} in {UP:.2f}s = {rep['uploadPerSec']} img/s {rep['uploadMBps']} MB/s")

# ── 4. 建任务（一次性提交全部 ID）──────────────────────────────────────────
t0 = time.time()
task_name = f"scale-test-{N}-{int(time.time())}"
r = requests.post(f"{BASE}/api/tasks",
                  json={"taskName": task_name, "imageIds": ids, "modelId": 1},
                  headers=H, timeout=1800)
r.raise_for_status()
task = r.json()
TID = task["id"]
rep["createSeconds"] = round(time.time() - t0, 2)
rep["taskId"] = TID
rep["taskName"] = task_name
rep["taskTotalCount"] = task.get("totalCount")
rep["taskStatusAfterCreate"] = task.get("status")
log(f"[4] task id={TID} {rep['createSeconds']}s totalCount={task.get('totalCount')} status={task.get('status')}")

# ── 5. 轮询至终态 ──────────────────────────────────────────────────────────
t0 = time.time()
t_proc = None
seen = set()
final = None
while True:
    pr = requests.get(f"{BASE}/api/tasks/{TID}/progress", headers=H, timeout=60).json()
    st = pr["status"]
    el = time.time() - t0
    key = (st, pr["processedCount"])
    if key not in seen:
        seen.add(key)
        log(f"    [{el:7.1f}s] {st} {pr['processedCount']}/{pr['totalCount']} "
            f"ok={pr['successCount']} fail={pr['failedCount']}")
    if st == "PROCESSING" and t_proc is None:
        t_proc = round(el, 1)
    if st in ("COMPLETED", "FAILED", "CANCELED"):
        final = pr
        break
    if el > DEADLINE:
        log("    TIMEOUT")
        final = pr
        break
    time.sleep(POLL)
WALL = time.time() - t0
rep["wallSeconds"] = round(WALL, 2)
rep["firstProcessingSeconds"] = t_proc
rep["finalStatus"] = final["status"]
rep["finalProcessed"] = final["processedCount"]
rep["finalSuccess"] = final["successCount"]
rep["finalFailed"] = final["failedCount"]
log(f"[5] terminal={final['status']} wall={WALL:.1f}s firstProcessing={t_proc}s")

rep["totalSeconds"] = round(time.time() - T_ALL, 2)
rep["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S")
if rep["wallSeconds"] > 0:
    rep["wallMsPerImage"] = round(rep["wallSeconds"] * 1000 / rep["uploadedCount"], 2)
    rep["wallPerSec"] = round(rep["uploadedCount"] / rep["wallSeconds"], 2)

RESULT.write_text(json.dumps(rep, ensure_ascii=False, indent=2), encoding="utf-8")
log(f"\nwritten {RESULT}")
log("===== SUMMARY =====")
for k, v in rep.items():
    log(f"  {k} = {v}")