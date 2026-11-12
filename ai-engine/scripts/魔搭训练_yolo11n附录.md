
---

# 附：第二个版本 yolo11n（补齐 n / s 消融）

> **为什么补**：`wildlife-v1.0` 是 **yolo11s 训 60 轮**的产物（mAP@0.5 **0.8106**）。
> 当时只做过 **1 轮** 的 n / s 对比（mAP@0.5 0.28314 vs 0.37342），**没有 60 轮的 yolo11n 权重**，
> 所以「模型版本管理」里只有一个真版本。
> 本附录补上另一半：**同数据、同配置、只换基础权重**，训出可部署的第二个版本。

## 与 11s 的唯一差异

| | 11s（已完成） | 11n（本附录） |
|---|---|---|
| `--model` | `yolo11s.pt` | **`yolo11n.pt`** |
| `--name` | `wildlife-v1.0` | **`wildlife-11n`** |
| epochs / imgsz / batch / patience / seed / workers | 60 / 640 / 32 / 20 / 0 / 6 | **完全一致** |

> ⚠️ **必须都是 60 轮。** 块 6 的示例里写的是 `--epochs 100`，那是文档示例值；
> 实际 11s 跑的是 **60 轮**（见 `论文训练产物/args.yaml` 的 `epochs: 60`）。
> **轮数不同就没有可比性**，消融结论会站不住。

---

## 附-1 ── 取预训练权重（走代理）

`yolo11n.pt` 在 GitHub releases 上，魔搭直连不通：

```bash
cd /mnt/workspace/wildlife
curl -fL -o yolo11n.pt \
  https://gh-proxy.com/https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.pt
ls -l yolo11n.pt        # 约 5.4 MB（5,613,764 字节左右）
```

---

## 附-2 ── 先跑 3 轮验证链路（几分钟）

```bash
cd /mnt/workspace/wildlife
nohup python train.py \
    --data /tmp/yolo/data.yaml \
    --model yolo11n.pt \
    --epochs 3 --imgsz 640 --workers 6 --batch 32 \
    --name wildlife-11n-smoke --project /mnt/workspace/runs \
    > /tmp/train_11n_smoke.log 2>&1 &

echo "PID: $!"
```

```bash
tail -f /tmp/train_11n_smoke.log
```

**通过标准**：3 轮都正常结束、`runs/wildlife-11n-smoke/weights/best.pt` 存在、mAP 在往上走。
若这里就崩，先修链路，别开长训练。

---

## 附-3 ── 正式训练 60 轮

```bash
cd /mnt/workspace/wildlife
nohup python train.py \
    --data /tmp/yolo/data.yaml \
    --model yolo11n.pt \
    --epochs 60 --imgsz 640 --workers 6 --batch 32 \
    --patience 20 --save-period 10 \
    --name wildlife-11n --project /mnt/workspace/runs \
    > /tmp/train_11n.log 2>&1 &

echo "PID: $!"
```

```bash
tail -f /tmp/train_11n.log      # Ctrl+C 退出查看，不会杀训练
```

**启动后 90 秒自查**：日志里步速应在 `2~2.5 it/s`（数据在 `/tmp` 上时）。
若显示 `0.2 it/s`，说明还在读 NAS，回块 4.6 检查 `data.yaml` 的 `path` 有没有改成 `/tmp/yolo`。

**耗时估算**：11s 是 **216 秒/轮、60 轮 3.61 小时**；
11n 参数量约为 11s 的 1/3.6，**预计 1～1.8 小时**（估算值，以实测日志为准）。

**长训练防丢**（实例可能被回收）：

```bash
cp /mnt/workspace/runs/wildlife-11n/weights/last.pt /mnt/data/
```

中断后：

```bash
nohup python train.py \
    --data /tmp/yolo/data.yaml \
    --name wildlife-11n --project /mnt/workspace/runs --resume \
    > /tmp/train_11n_resume.log 2>&1 &
```

---

## 附-4 ── 评测并生成 meta.json

```bash
cd /mnt/workspace/wildlife
mkdir -p /mnt/workspace/out/wildlife-11n
python evaluate.py \
    --weights /mnt/workspace/runs/wildlife-11n/weights/best.pt \
    --data /tmp/yolo/data.yaml \
    --out /mnt/workspace/out/wildlife-11n/meta.json \
    --batch 32 \
    --write-classes
```

> ⚠️ `--batch 32` **不能省**（`--batch auto` 在 GPU 上会算出 `-1` 直接崩，见块 7 的说明）。
> 这会同时写出 `classes.txt` 和带 md5 / 数据集 sha256 的 `meta.json`。

---

## 附-5 ── 取回产物

```bash
cd /mnt/workspace/out
cp /mnt/workspace/runs/wildlife-11n/weights/best.pt wildlife-11n/
cp /mnt/workspace/runs/wildlife-11n/args.yaml       wildlife-11n/
cp /mnt/workspace/runs/wildlife-11n/results.csv     wildlife-11n/
cp /mnt/workspace/runs/wildlife-11n/labels.jpg      wildlife-11n/
mkdir -p wildlife-11n/plots
cp /mnt/workspace/runs/wildlife-11n/plots/*.png     wildlife-11n/plots/ 2>/dev/null
cp /mnt/workspace/runs/wildlife-11n/plots/*.jpg     wildlife-11n/plots/ 2>/dev/null
zip -r wildlife-11n.zip wildlife-11n
ls -lh wildlife-11n.zip
```

下载 `wildlife-11n.zip` 到本机后，解压到 `论文训练产物/` 之外的位置先放着。

---

## 附-6 ── 本机落地并登记为第二个版本

**① 放版本目录**（与 `wildlife-v1.0` 同级）：

```
ai-engine/models/wildlife-11n/
    best.pt        ← 从 zip 里取
    classes.txt    ← evaluate.py --write-classes 生成
    meta.json      ← evaluate.py 生成
```

**② 登记入库**（不覆盖 v1.0，默认登记为 DISABLED）：

```bash
docker exec wildlife-ai-engine python /app/scripts/register_model.py \
  --meta /models/wildlife-11n/meta.json \
  --version wildlife-11n \
  --model-path /models/wildlife-11n/best.pt \
  --classes /models/wildlife-11n/classes.txt \
  --send --api-base http://backend:8085 --token <你的 admin token>
```

token 取法：

```bash
curl -s -X POST http://localhost:8085/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

**③ 确认**：

```bash
docker exec wildlife-mysql mysql -uroot -proot123 wildlife_db \
  -e "SELECT id, model_name, version, status, map50 FROM model_version;"
```

应看到 `wildlife-11n` 一条，`status = DISABLED`，`map50` 是实测值。

---

## 附-7 ── 预期结果与判读

| | yolo11s（已有） | yolo11n（本次） |
|---|---|---|
| 预期 mAP@0.5 | 0.8106（实测） | **低于 s，具体以实测为准** |
| 预期参数量 | 约 9.4 M | 约 2.6 M |
| 预期推理速度 | 较慢 | **更快**（CPU 上尤其明显）|
| 1 轮时的差距 | — | mAP@0.5 低 **31.9%** |
| 官方 COCO 参考差距 | — | mAP@0.5:0.95 低 **16%**（39.5 vs 47.0）|

**判读要点**：

1. **60 轮收敛后，n 与 s 的差距应明显小于 1 轮时的 31.9%**，更接近官方 COCO 的 16% 量级。
   如果实测仍然差 30% 以上，说明 19 类红外数据对模型容量要求较高 —— **这本身就是一个有价值的结论**。
2. **不要把 1 轮数据和 60 轮数据混在一张表里比较**，论文里要么都用 1 轮（架构起步速度），
   要么都用 60 轮（收敛后精度）。
3. 两个版本都登记后，「模型版本管理」页才有真正的 n / s 对比，
   **启用切换目前仍需重启 `ai-engine` 容器**（`ACTIVE_MODEL_VERSION` 是环境变量）。
