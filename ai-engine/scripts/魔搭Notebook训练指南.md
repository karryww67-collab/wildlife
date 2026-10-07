# 在魔搭社区 Notebook（8 核 / 32GB / 24G 显存）上训练 YOLO 权重

> 本机与 ai-engine 容器**都没有 GPU**（CPU 推理与批量控制见 README 第八节 8.5），
> 训练放到 GPU 实例上做。这份指南只覆盖「训练与评测」，产出的 `best.pt` 再拿回本项目落地。

---

## ⚡ 零、先看这一节：魔搭实例的实测网络拓扑（**2026-10 实测，必读**）

魔搭 Notebook 的**外网出口是受限的**。实测（`curl -sI -m 5` 逐个探测）：

| 域名 | 结果 | 能用来做什么 |
|---|---|---|
| `storage.googleapis.com` | ✅ **通**（HTTP 400/200） | **SWG/WCS 数据集与图片都在这里** —— 直接下载，**不需要从本机上传数据集** |
| `codeload.github.com` | ✅ **通**（HTTP 301） | **拉本项目代码走这里**（zip 下载，不用 git） |
| `gitee.com` | ✅ 通 | 备用代码源 |
| `hf-mirror.com` | ✅ 通 | HuggingFace 镜像 |
| `www.modelscope.cn` | ✅ 通 | 魔搭自家 |
| `github.com` | ❌ **不通**（连接超时 ~130s） | git clone / 权重下载 / ultralytics 字体下载都会卡死 |
| `raw.githubusercontent.com` | ❌ **不通** | 不能 curl raw 文件 |
| `ghproxy.com` | ❌ 不通 | 代理也救不了 |

**三条由此得出的硬结论**：

1. **代码用 codeload 拉 zip，不要 git clone**：
   ```bash
   curl -L -o wildlife.zip https://codeload.github.com/karryww67-collab/wildlife/zip/refs/heads/main
   unzip -q wildlife.zip && mv wildlife-main wildlife-src
   ```
2. **数据集不用从本机上传**。SWG/WCS 的标注与图片都在 `storage.googleapis.com`，实例内直接下。
   （本机若已下载过，也不必再传 —— 17 GB 级上传远慢于实例内直下。）
3. **ultralytics 首次运行会尝试从 github.com 下 `Arial.Unicode.ttf`，会卡 3 轮重试**（每轮 30s 超时）。
   预先放好字体可跳过（见 §2）。**不处理会拖慢启动，但不会让训练失败。**

### 持久化：`/mnt/data` 是 NAS 持久盘（实测）

实例上的 `/mnt/data` 挂载的是阿里云 NAS（`*.nas.aliyuncs.com`，1 PB 容量），
**跨实例保留** —— 换一台实例后之前的文件还在（已用探针文件实测验证：写入后重开实例仍可读）。
所以：

| 放什么 | 放哪 |
|---|---|
| 代码、临时日志 | `/mnt/workspace/`（不持久，每次重拉） |
| **数据集、训练产物**、跨实例要保留的东西 | **`/mnt/data/`**（持久） |

> ⚠️ **NAS IO 明显慢于本地盘 —— 这是本项目最大的一个速度陷阱。**
> 实测同一份 19,723 张图、同一台 A10、同一个 batch=32：
>
> | 数据位置 | 读取速度 | 每步 | 每 epoch（493 步） | 100 epochs |
> |---|---|---|---|---|
> | `/mnt/data`（NAS） | — | 4.8 s | ~20 min | **~33 小时**（超额度，跑不完） |
> | `/tmp`（本地盘） | **782 MB/s** | **0.12 s**（8.4 it/s） | **~1 分钟** | **~2 小时** ✅ |
>
> 提速约 **40 倍**。**正式长训练前必须先把数据集拷到 `/tmp`**：
>
> ```bash
> mkdir -p /tmp/yolo && cp -r /mnt/data/wildlife-combined/yolo/* /tmp/yolo/
> ```
>
> ⚠️ **改 data.yaml 的 path 不要用 sed** —— 里面的 path 是**带引号**的：
> `path: "/mnt/data/wildlife-combined/yolo"`，
> 而 `sed 's|path: /mnt/...|...|'` 少了引号、**匹配不上且不报错**，
> 看起来改了其实没改，训练继续读 NAS —— 白等几小时。用 python：
>
> ```bash
> python - <<'PY'
> import pathlib
> p = pathlib.Path("/tmp/yolo/data.yaml")
> text = p.read_text(encoding="utf-8").replace(
>     '"/mnt/data/wildlife-combined/yolo"', '"/tmp/yolo"'
> ).replace("/mnt/data/wildlife-combined/yolo", "/tmp/yolo")
> p.write_text(text, encoding="utf-8")
> print(text[:80])
> PY
> ```
>
> **改完务必 `head -3 /tmp/yolo/data.yaml` 亲眼确认** `path: "/tmp/yolo"`。
> 提速是否生效，进训练后看 `s/it`：**< 0.2 秒才算走对了盘**。
>
> 顺带：数据在本地盘时，dataloader 扫描从 NAS 上的 ~11 分钟变成 **6 秒**，
> 并在 `labels/` 下生成 `train.cache` / `val.cache`。

### `--workers` 怎么选：看数据在哪

| 数据位置 | `--workers` | 原因 |
|---|---|---|
| `/tmp`（本地盘） | **4** | 正常并发预取 |
| `/mnt/data`（NAS） | **0** | 多 worker 会在 fork 上卡死，训练永远进不了第一个 epoch（实测） |

### 下载并发：`--workers 64`

数据集图片走 Google bucket 单张 HTTP。实测并发从 16 提到 **64** 后速度明显改善
（仍受实例出口带宽限制，SWG 的 1.1 万张约需 30-60 分钟）。脚本支持**断点续传** ——
中断后重跑同一命令会自动跳过已下载的图。

---

## ⚠️ 关于持久化的两点补充（实测与官方答复有出入）

**魔搭官方答复**（发布于 2023-12 与 2024-07，平台行为可能已变）：

> 「关闭实例后，**.ipynb 文件会保存下来，其他文件及文件夹不会被保存**，可以在关闭前下载到本地。」
> —— [noote过一段时间会关机清理的吗](https://developer.aliyun.com/ask/661773)
>
> 「**notebook 实例关闭后文件不会被保存**」
> —— [磁盘空间不足问题怎么解决呢？](https://developer.aliyun.com/ask/582399)

**但本项目实测**：`/mnt/data`（阿里云 NAS）**跨实例保留** —— 用探针文件验证过
（写入后换实例重开仍可读），本项目的 35 GB 数据集也确实在多台实例间延续了下来。

**保守做法**：最终产物（`best.pt` / `meta.json` / `classes.txt`）**既存 `/mnt/data`、
也下载回本机**，不要只依赖任一侧。`/tmp` 则**明确不持久**（虽然训练数据放那里最快）。

---

## 0. 为什么这四个脚本能直接拿到 Notebook 上跑

`ai-engine/scripts/` 下的脚本刻意只依赖标准库 + ultralytics，没有任何本项目特有的依赖
（不连数据库、不读 Redis、不 import 后端或 ai-engine 的包）：

| 脚本 | 依赖 |
|---|---|
| `prepare_dataset.py` | 仅标准库 |
| `train.py` | ultralytics + torch |
| `evaluate.py` | ultralytics + torch |
| `register_model.py` | 仅标准库（**这一步回本地做**，因为要连后端） |

所以只需把这几个 `.py` 复制过去即可，不用把整个项目搬上 Notebook。

---

## 1. 开工前的自检（先跑这个，别猜）

```bash
nvidia-smi
python -c "import torch;print('torch',torch.__version__,'cuda_ok',torch.cuda.is_available())"
python -c "import torch;print(torch.cuda.get_device_name(0), round(torch.cuda.get_device_properties(0).total_memory/1024**3,1),'GB')"
nproc; free -g | head -2
df -h | grep -Ev 'tmpfs|overlay'          # 找持久化目录与剩余空间
curl -sI -m 10 https://github.com | head -1   # 外网可达性：决定能否自动下预训练权重
```

对照解读：

| 自检结果 | 影响 |
|---|---|
| `cuda_ok` 为 False | GPU 没挂上，先别开始训 |
| 显存 24 GB | yolo11n@640 可跑 batch 32~64；yolo11s 16~32；yolo11m 8~16 |
| 8 核 / 32 GB | 建议 `--workers 4`；worker 过多会吃满内存 |
| github 不可达 | 预训练权重下不来 → 先把 `.pt` 传上去，用 `--model /path/to/yolo11n.pt` |
| 磁盘剩余空间 | 数据集 + `runs/` 检查点要占几个 GB，空间不足会在训练中途失败 |

**关于"持久化目录"**：按上面两条社区答复，关闭实例后只有 `.ipynb` 保留，
所以**别把任何路径当成持久盘**。要验证当前实例的实际行为，可以放一个探针文件后重开实例看看：

```bash
echo probe > /mnt/workspace/persist_probe.txt   # 或你看到的其它数据盘路径
# 关闭实例 → 重新打开 → 看这个文件还在不在
ls -l /mnt/workspace/persist_probe.txt
```

探针还在，说明该目录在当前平台版本下是持久的（那就把数据与产物都放这里，能省下每次重传）；
探针没了，就按"每次重开都要重新准备环境与数据"来做。

**每次重开实例的最短流程**（不可持久时）：① `pip install -U ultralytics` →
② 重新放入 `scripts/*.py` → ③ 重新放入数据集 → ④ 从最近一次上传的检查点 `--resume` 续训
→ ⑤ 训完立即下载产物。所以**把脚本与数据也放在某个能一键取回的地方**
（网盘、git 仓库、或魔搭的数据集仓库）会比每次手传省事得多。

---

## 1.5 选镜像（创建实例时）

预装镜像列表里选：

**首选 `ubuntu22.04-cuda12.1.0-py310-torch2.3.0-1.18.0`**
（想用更新的 Python 则选 `ubuntu22.04-cuda12.1.0-py312-torch2.3.1-tf2.16.1-…`）

| 因素 | 说明 |
|---|---|
| **CUDA 12.1** | 对驱动要求低（驱动 ≥ 525 即可，几乎都满足）。**CUDA 13.0 需要很新的驱动** —— 宿主驱动偏旧时 `torch.cuda.is_available()` 会是 `False`，于是你在 CPU 上训练而不自知 |
| **torch 2.3** | 完全在 ultralytics 支持范围内，是 CUDA 12.1 的成熟搭配 |
| **py310** | 三方包兼容性最稳（py312 也可用） |

**不要选** py37/py38 那两个（Python 与 torch 太旧，新版 ultralytics 会要求更高版本，
易陷依赖冲突），也不要选 paddle 那个（与本任务无关）。

选完花 10 秒验证，不行就换个镜像重开实例（成本很低）：

```bash
nvidia-smi
python -c "import torch;print(torch.__version__, torch.version.cuda, torch.cuda.is_available())"
# 期望 2.3.x / 12.1 / True；若是 False，别开始训练
```

**实测环境**（2026-09-25，本项目实例，选第 2 个镜像）：

```
NVIDIA-SMI 550.54.15   Driver Version: 550.54.15   CUDA Version: 12.4
NVIDIA A10   23028MiB (24GB)
torch 2.3.1+cu121   12.1   True
```

这也印证了上面的判断：**驱动 550 最高支持 CUDA 12.4，而 CUDA 13 需要驱动 ≥ 580**，
所以第 1 个镜像（`cuda13.0.3-py312-torch2.13.0`）在这台机器上会
`is_available() = False` —— 于是你在 CPU 上训练而不自知。
**挑镜像前先看 `nvidia-smi` 显示的驱动版本上限。**

> ⚠️ **别让 pip 换掉预装的 torch。** `pip install -U ultralytics` 看到 torch 已满足要求
> 就不会动它；但**不要执行 `pip install -U torch`** —— 那会装上 PyPI 的通用 wheel，
> 可能破坏与 CUDA 的匹配。装完复查一次上面那条命令。

**关于额度**：创建页会显示剩余 GPU 额度（例如"36 小时"）。24G 显存跑 yolo11n@640，
几千张图训 100 轮约 1~2 小时，额度够用；但别把额度耗在无意义试跑上 ——
第 5 节的链路验证固定用 `--epochs 3`。

---

## 2. 装依赖

```bash
pip install -U ultralytics
python -c "import ultralytics;print(ultralytics.__version__)"
```

**同时装一个中文字体。** matplotlib 的默认字体（DejaVu Sans）没有 CJK 字形，
中文类别名会被画成方框 —— 训练曲线与混淆矩阵都会受影响，
而**训练本身完全正常**，很难联想到是字体问题。日志里的线索只是一串容易忽略的警告：

```
UserWarning: Glyph 37326 (\N{CJK UNIFIED IDEOGRAPH-91CE}) missing from font(s) DejaVu Sans.
```

```bash
apt-get update && apt-get install -y fonts-noto-cjk && rm -rf ~/.cache/matplotlib
# 没有 apt / 没有外网时：把任意中文字体放进 /usr/share/fonts/truetype/ 再清缓存即可
```

**同时把字体放到 ultralytics 期望的位置**——它启动时会尝试从 `github.com` 下载
`Arial.Unicode.ttf`（画框标注用），而魔搭上 github 不通，会卡 3 轮重试（约 100 秒）：

```bash
mkdir -p /root/.config/Ultralytics
cp /usr/share/fonts/truetype/wqy/wqy-zenhei.ttc /root/.config/Ultralytics/Arial.Unicode.ttf
# 或（若上面路径不存在）
cp /usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc /root/.config/Ultralytics/Arial.Unicode.ttf
```

`train.py` 开训前会查一次字体并打印结论（`已启用中文字体：...` 或提示安装命令），
所以这一步有没有生效，一眼可见。

本项目容器里是 `torch 2.7.1+cpu`，Notebook 上通常是 CUDA 版 torch。
**版本差异不影响权重文件本身** —— YOLO 的 `.pt` 存的是与设备无关的权重，
GPU 上训出来的 `best.pt` 拿回本地 CPU 容器可以直接加载（就是会走 CPU 推理）。

---

## 3. 放代码与数据

### 3.1 代码：用 codeload 拉 zip（**不要 git clone**）

项目已推到 GitHub（公开仓库）：`https://github.com/karryww67-collab/wildlife`

**魔搭上 `github.com` 不通（见 §0），`git clone` 会超时 130 秒后失败。改用 codeload：**

```bash
cd /mnt/workspace
curl -L -o wildlife.zip https://codeload.github.com/karryww67-collab/wildlife/zip/refs/heads/main
unzip -q wildlife.zip
mv wildlife-main wildlife-src

mkdir -p /mnt/workspace/wildlife/models
cp /mnt/workspace/wildlife-src/ai-engine/scripts/*.py /mnt/workspace/wildlife/
cp /mnt/workspace/wildlife-src/ai-engine/check_weights.py /mnt/workspace/wildlife/
# 注意：classes.txt 用数据集自己的（见 §4），不要拷 models/wildlife-v1.0/ 里的那份
```

应看到 **12 个 .py**：`prepare_dataset / train / evaluate / register_model / smoke_test /
lila_to_yolo / wcs_to_yolo / voc_to_yolo / merge_datasets / check_weights …`

> 备选：`gitee.com` 通（若项目有 Gitee 镜像可走）；`raw.githubusercontent.com`
> 与 `ghproxy.com` 均**不通**，不要用。

> ⚠️ **下面这行 clone 没有 token —— 因为仓库是公开的。**
> 若日后改成私有，clone 时要认证，**不要把 token 拼进 URL**（会明文存进 `.git/config`），
> 让它交互提示即可：提示 `Username` 输 `karryww67-collab`，提示 `Password` 输 token
> （token 不是登录密码；粘贴时终端不显示任何字符，属正常）。
>
> 另外：命令里的尖括号 `<...>` 是占位符，**照抄会报
> `bash: ...: 没有那个文件或目录`** —— bash 会把 `<` 当成重定向。

**为什么值得这么做**：实例关闭后除 `.ipynb` 什么都留不下（见开头），
所以每次重开实例都要重新准备代码 —— 手工传 6 个文件是**每次**都要做，
`git clone` 只要**一条命令**。

> ⚠️ **推之前先补两条忽略规则。** 实测本项目的 `.gitignore`：
> `ai-engine/data/`（439 张图 144.6 MB）**已被正确忽略** ✅，
> 但 `ai-engine/models/**/best.pt`（5.35 MB）与 `.workbuddy/`（9.1 MB）**会被推上去** ——
> 因为 `models/*.pt` 带中间斜杠，只匹配根目录的 `models/`。追加：
>
> ```gitignore
> .workbuddy/
> ai-engine/models/**/*.pt
> ```

### 3.2 不用 git 时

JupyterLab 左侧文件浏览器支持**多选/拖拽上传**，6 个文件合计约 47 KB。
注意上传目标是 `/mnt/workspace/wildlife/`（先在文件浏览器里切到该目录），
不是默认的 `/mnt/workspace`。

**数据要不要每次重传，取决于第 1 节的探针结果。** 若不可持久化，
把数据集也放进某个能一键取回的地方（魔搭的数据集仓库支持 git + git-lfs），
比每次从本机上传快得多。

数据按 YOLO 标准布局（下面用本文档统一的 `/mnt/workspace/data/` 作数据根）：

```
/mnt/workspace/data/wildlife/
  images/train/*.jpg
  images/val/*.jpg
  labels/train/*.txt      # 与图片同名
  labels/val/*.txt        # 每行: class_id cx cy w h（归一化）
```

## 4. 校验数据 + 生成 data.yaml

```bash
python prepare_dataset.py --dataset /mnt/workspace/data/wildlife \
    --classes /mnt/workspace/wildlife/classes.txt \
    --out /mnt/workspace/data/wildlife/data.yaml
```

- 退出码 **1 = 数据有问题**（图片/标注不配对、类别 id 越界、坐标越界、孤立标注），
  先修数据，别带着问题硬训；
- 它会指出**零实例类别** —— 那些类别的 mAP 恒为 0，会拉低整体指标。

## 4.5 还没有数据集？先验证链路（约 10 分钟）

正式数据集要到项目后期才到位。**别等数据来了才第一次运行训练脚本** ——
那时才发现 GPU 路径有问题，代价是几小时额度加一次失败训练。

`smoke_test.py` 用仓库自带的 3 张测试图（`ai-engine/tests/*.jpg`）造一个最小数据集，
把 `prepare_dataset → train → evaluate` 走一遍：

```bash
cd /mnt/workspace/wildlife
python smoke_test.py --keep
```

它检查 5 项：`prepare_dataset` 通过、`train.py` 的 `--device auto` **解析成 GPU**
（而不是静默退回 cpu）、`best.pt` 非空、`evaluate.py` 生成 `meta.json`、
`meta.json` 里 `metricsVerified=true` 且记到了显卡名。结尾打印 PASS/FAIL 汇总。

若 `--device` 那项显示 `cpu` 而机器有显卡，说明 torch 没识别到 GPU ——
先查 `torch.cuda.is_available()`，别继续。

> ⚠️ 这次跑出来的精度**毫无意义**（3 张图、3 轮、imgsz 320），脚本自己也会提示。
> 它只验"链路通不通"，不能拿这个 mAP 写论文。

## 5. 训练

```bash
python train.py --data /mnt/workspace/data/wildlife/data.yaml \
    --model yolo11n.pt --epochs 100 --imgsz 640 \
    --workers 4 --name wildlife-v1.0 --project /mnt/workspace/runs
```

`--device` 与 `--batch` 默认都是 `auto`：有 CUDA 就用 `0`，批大小交给 ultralytics 的
AutoBatch 按显存自动定（约占 60% 显存）。**开训前会把实际选用的设备、显卡型号、批大小
打印出来**，并提醒"预训练权重需联网下载"和"实例释放后产物不保留"。

> **强烈建议先 `--epochs 3` 跑通一遍**（几分钟），确认数据、标注、类别数都对，
> 再上长训练。这一步能省掉几小时的白跑。

中断了可以续训：

```bash
python train.py --data ... --name wildlife-v1.0 --project /mnt/workspace/runs --resume
```

## 6. 评测并生成可溯源的 meta.json

```bash
python evaluate.py --weights /mnt/workspace/runs/wildlife-v1.0/weights/best.pt \
    --data /mnt/workspace/data/wildlife/data.yaml \
    --out /mnt/workspace/out/wildlife-v1.0/meta.json --write-classes
```

写出的 `meta.json` 带 `metricsVerified: true`，并记录：权重 md5、`data.yaml` 的 sha256、
评测命令、ultralytics/torch 版本、**显卡型号与显存**、CUDA/cuDNN 版本、批大小与 imgsz、
CPU 核数、评测时间。任何人拿同一个 `best.pt` 与同一份数据都能复现这组数字。

## 7. 关闭实例前，必须取回产物

**这一步不能省。** 按魔搭开发者钉群的答复，实例关闭后只有 `.ipynb` 保留，
`runs/`、`out/` 以及你上传的数据**都不会被保存**。

要拿回本项目的只有三个文件：

```
out/wildlife-v1.0/meta.json
out/wildlife-v1.0/classes.txt      # --write-classes 生成
runs/wildlife-v1.0/weights/best.pt
```

三条持久化途径，任选一（建议 1+2 都做）：

1. **下载到本地**（最直接）；
2. **推到魔搭模型仓库**（git + git-lfs）—— 下次可直接拉回来，也便于论文里给出来源；
3. 传到自己的网盘 / 对象存储。

> **长训练要中途留一手。** 若训练要跑几小时，别等全部结束才取产物：
> 每训一段就把 `weights/last.pt`（或 `--save-period` 产生的按轮次检查点）
> 取回一次。这样即使实例被回收，也能在下次 `--resume` 续训，
> 而不是从第 1 轮重来。
>
> 尤其别丢 `meta.json`：它记着权重 md5 与数据集 sha256，
> **丢了就再也补不回来** —— 只能拿权重重新评测一遍（还得有同一份数据）。

---

## 8. 拿回本项目落地

```bash
# 1) 放进版本目录（models/ 是只读挂载，放宿主机上）
cp best.pt     ai-engine/models/wildlife-v1.0/best.pt
cp classes.txt ai-engine/models/wildlife-v1.0/classes.txt
cp meta.json   ai-engine/models/wildlife-v1.0/meta.json

# 2) 体检：确认不再回退
docker exec wildlife-ai-engine python check_weights.py wildlife-v1.0
#    期望看到「回退到备用权重: 否」

# 3) 把引擎指回正式版本：docker-compose.yml 改两行
#      ACTIVE_MODEL_VERSION: wildlife-v1.0
#      YOLO_MODEL_PATH: /models/wildlife-v1.0/best.pt
docker compose up -d

# 4) 确认自检通过（启动日志 + 接口）
curl "http://localhost:9095/ai/model/status?version=wildlife-v1.0"
#    期望 ok=true、usingFallback=false

# 5) 登记/启用版本（用「模型管理」页，或命令行）
python ai-engine/scripts/register_model.py \
    --meta ai-engine/models/wildlife-v1.0/meta.json \
    --version wildlife-v1.0 --model-path /models/wildlife-v1.0/best.pt --send
```

**第 4 步是关键**：只有 `usingFallback=false`，才说明"页面显示的版本"和"实际加载的权重"
第一次真正一致 —— 这正是前面几批工作要达成的状态。

---

## 9. 参数速查（24 GB 显存 / 8 核 / 32 GB）

| 模型 | 建议批大小 | 说明 |
|---|---|---|
| `yolo11n` | 32~64（或 auto） | 最快，适合先跑通与快速迭代 |
| `yolo11s` | 16~32 | 精度/速度折中 |
| `yolo11m` | 8~16 | 精度更高、训练更慢 |
| `yolo11l` / `x` | 可能 OOM | 24 GB 上要降 `imgsz` 或换小模型 |

| 参数 | 建议 | 理由 |
|---|---|---|
| `--imgsz` | 640 | 推理侧固定 640（README 8.5），训练也用 640 才与部署一致 |
| `--workers` | **4**（数据在本地盘）/ **0**（数据在 NAS） | NAS 上多 worker 会 fork 卡死，实测 `workers=0` 才跑得动；**但速度只有本地盘的 1/10** —— 长训练务必先把数据拷到 `/tmp` 再用 4 |
| `--batch` | 32（A10 实测，显存占 8.3 GB） | AutoBatch 在数据首轮扫描时常给偏小值，显式指定更稳 |
| `--epochs` | 100~300 | 配合 `--patience 50` 早停 |
| `--seed` | 0 | 固定随机种子，指标可复现 |
| `--save-period` | 长训练设 10 | 多留按轮次编号的检查点 |

### 实测速度基准（A10 24GB，19,723 张图）

| 数据位置 | 每步耗时 | 每 epoch | 100 epochs 预计 |
|---|---|---|---|
| NAS（`/mnt/data`） | 4.8 s/it | ~20 min | **~33 小时**（超额度，不可行） |
| 本地盘（`/tmp`） | ~0.5-1 s/it | ~2-3 min | **~4-5 小时**（可行） |

> 这是本指南最重要的一条经验：**NAS 只用来存数据，不用来训数据。**
> 训练前 `cp -r` 到 `/tmp`，训完把 `best.pt` 拷回 `/mnt/data` 或直接下载到本机。

---

## 10. 三个容易踩的坑

1. **批大小会影响识别结果。** ultralytics 按批内最大尺寸补齐张量，同一张图在不同批次里
   结果有微小差异（实测置信度差 ≤ 0.08、框差 ≤ 4px，最高分类别不变，详见 README 8.5）。
   所以训练、评测、以及论文里的前后对比，**批大小要固定并在文中写明**。

2. **`classes.txt` 可能被"算出来"的类别覆盖。** 若误用 COCO 预训练权重评测，会带出 80 个
   英文类别名。`evaluate.py --write-classes` 发现已有且内容不同时会**拒绝覆盖**（退出码 3）——
   这是有意设计的保护，确认无误再用 `--force`。

3. **实例释放即丢失。** 数据、`runs/`、`out/` 都要放在持久化目录，并尽快下载
   `best.pt` 与 `meta.json`。丢的如果是 `meta.json`，这组指标的溯源信息就再也补不回来了。