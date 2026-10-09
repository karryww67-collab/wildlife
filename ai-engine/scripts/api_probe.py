#!/usr/bin/env python3
"""压测期间定期打关键查询接口，记录延迟随表规模的变化（10 万张规模论证用）。

用法（在 ai-engine 容器内跑，与压测同时启动）：

    docker cp ai-engine/scripts/api_probe.py wildlife-ai-engine:/tmp/api_probe.py
    docker exec -d wildlife-ai-engine python /tmp/api_probe.py /tmp/api_C.json <taskId> 18000

这是论文「识别结果结构化入库与检索」那一节的性能证据来源。
覆盖五个接口，正好对应五个模块：

    progress      —— ①批量上传与队列调度的进度查询
    statOverview  —— ⑤统计报表（概览）
    statClasses   —— ⑤统计报表（各类别数量）
    reviewList    —— ④低置信度样本人工复核列表
    imageList     —— ③识别结果结构化入库与检索

重点关注 reviewList：ImageAvailabilityService 会在快照过期时同步做一次全表
Files.exists() 探测，代码注释预测 10^5 行时单次约 47 s。本探针就是来验证这一点的
—— 若出现 TIMEOUT，说明请求线程确实被巡检锁挡住了。
"""
import json
import sys
import time

import requests

BASE = "http://backend:8085"
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/api.json"
TASK_ID = int(sys.argv[2]) if len(sys.argv) > 2 else 0
DUR = int(sys.argv[3]) if len(sys.argv) > 3 else 21600
INTERVAL = 60
TIMEOUT = 300

TOKEN = requests.post(f"{BASE}/api/auth/login",
                      json={"username": "admin", "password": "admin123"},
                      timeout=60).json()["token"]
H = {"X-Auth-Token": TOKEN}

ENDPOINTS = {
    "progress": f"/api/tasks/{TASK_ID}/progress",
    "statOverview": "/api/statistics/overview?range=all",
    "statClasses": "/api/statistics/classes?top=20",
    "reviewList": "/api/reviews?page=1&size=20",
    "imageList": "/api/images?page=1&size=20",
}

rows = []
t0 = time.time()

while time.time() - t0 < DUR:
    for name, path in ENDPOINTS.items():
        t1 = time.time()
        try:
            resp = requests.get(f"{BASE}{path}", headers=H, timeout=TIMEOUT)
            code, ms = resp.status_code, round((time.time() - t1) * 1000, 1)
        except requests.Timeout:
            code, ms = "TIMEOUT", TIMEOUT * 1000
        except requests.RequestException as exc:
            code, ms = type(exc).__name__, round((time.time() - t1) * 1000, 1)
        rows.append({"t": round(time.time() - t0, 1), "api": name, "ms": ms, "code": code})
        print(f"  [{rows[-1]['t']:7.1f}s] {name:13s} {ms:9.1f} ms  {code}", flush=True)
    time.sleep(INTERVAL)

with open(OUT, "w", encoding="utf-8") as fh:
    json.dump(rows, fh, ensure_ascii=False)

print(f"\nsampled {len(rows)} calls -> {OUT}")
for name in ENDPOINTS:
    got = sorted(x["ms"] for x in rows if x["api"] == name)
    if got:
        print(f"  {name:13s} p50={got[len(got)//2]:8.1f}  "
              f"p95={got[int(len(got)*0.95)]:9.1f}  max={got[-1]:9.1f} ms")