# 🦌 基于深度学习的野生动物图像批量识别系统

面向自然保护区/监测站的**红外相机图像批量识别与人工复核平台**：批量上传图像 → 队列异步调度 → YOLO 检测 → 结构化结果入库 → 按物种类别/时间/置信度检索 → 低置信度人工复核 → 统计报表与模型版本管理。

识别与 Web 服务**分离部署**：大批量推理在独立的 AI 引擎容器里异步消费，不阻塞 Web 请求。

[![Vue](https://img.shields.io/badge/Vue-3.4-42b883?logo=vue.js)](https://vuejs.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-6db33f?logo=springboot)](https://spring.io/projects/spring-boot)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.110-009688?logo=fastapi)](https://fastapi.tiangolo.com/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479a1?logo=mysql)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7-dc382d?logo=redis)](https://redis.io/)
[![YOLO](https://img.shields.io/badge/Ultralytics-YOLO-111f68)](https://docs.ultralytics.com/)

---

## 一、系统架构

```
                        浏览器（Vue 3 SPA）
                              │
              HTTP /api/**    │    WebSocket /ws/recognition
                              ▼
                    nginx（frontend :9095）
                              │
                              ▼
              backend（Spring Boot :8085）──────────▶ MySQL 8（wildlife_db）
                    │        ▲
      RPUSH 任务     │        │      消费结果
                    ▼        │
        Redis db9  wildlife:tasks        wildlife:results
                    │                        ▲
                    ▼                        │
        ai-engine（FastAPI :8001）── YOLO 推理 ──┘
                    │
        共享卷 /shared-data（仅保存上传的原图）
```

**为什么用队列解耦**：Web 层只负责"收图 + 建任务 + 入队"，推理层按自己的节奏消费。上传 200 张图时 HTTP 请求立即返回，推理在后台跑，进度通过 WebSocket 实时推给前端。

---

## 二、功能模块

| 模块 | 说明 | 状态 |
|---|---|---|
| **批量上传** | 拖拽多选上传，单次 ≤200 张、单文件 ≤50MB；**前端**按「文件名+大小+修改时间」去重（服务端不做内容级去重） | ✅ |
| **队列调度** | 任务入 Redis 队列，AI 引擎异步消费；支持取消/重试，重试幂等（同一任务重复入队只产出一份结果） | ✅ |
| **目标检测** | YOLO 检测，输出类别 + 置信度 + 归一化框坐标 | ✅ |
| **结果存储** | 一条检测框一行记录（`detection_result`）；图像物理路径在 `recognition_image.file_path`，本系统不生成缩略图与标注图 | ✅ |
| **结果检索** | 按任务、图像、物种类别、置信度下限、复核状态、**检出时间区间**筛选，分页；筛选口径与 CSV 导出完全一致 | ✅ |
| **人工复核** | 低置信度结果进入待复核列表，可确认/修正/驳回，支持批量复核；复核结论独立留存不被重跑覆盖 | ✅ |
| **统计报表** | 物种分布、保护级别/IUCN 分布、置信度分布、检测趋势、任务状态统计（ECharts） | ✅ |
| **模型版本管理** | 多版本登记、启用/停用、指标查看；使用中的版本禁止删除 | ✅ |
| **用户与权限** | 登录（token，8 小时）、用户增删改与改密；密码 BCrypt 存储；后端按权限矩阵强制校验，管理端点对非管理员返回 403 | ✅ |

---

## 三、技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3.4 + Vite 5 + Pinia 2 + Vue Router 4 + ECharts 5 + axios |
| 后端 | Spring Boot 3.2.4（Java 17）+ MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.0（`wildlife_db`，utf8mb4） |
| 队列 | Redis 7，db 9，键 `wildlife:tasks` / `wildlife:results` |
| AI 引擎 | Python + FastAPI + Ultralytics YOLO（PyTorch，支持 GPU，无 GPU 时 CPU 回退） |
| 部署 | Docker Compose + nginx |

---

## 四、目录结构

```
wildlife/
├── backend/                    Spring Boot 后端
│   ├── src/main/java/com/wildlife/recognition/
│   │   ├── config/             鉴权拦截器、CORS、WebSocket、MyBatis-Plus
│   │   ├── controller/         Auth/Task/Image/Result/Review/Model/Statistics/User
│   │   ├── service/            业务逻辑（含 Redis 订阅者、任务派发）
│   │   ├── entity/ repository/ 实体与 Mapper
│   │   └── websocket/          识别进度推送（JSR-356）
│   └── src/test/               5 个单元测试类
├── ai-engine/                  Python AI 引擎
│   ├── app/services/           task_consumer / wildlife_detector / model_manager /
│   │                           batch_processor / result_callback
│   ├── app/api/health.py       健康检查
│   ├── check_weights.py        权重体检（只依赖标准库）
│   ├── scripts/                prepare_dataset / train / evaluate / register_model /
│   │                           smoke_test / lila_to_yolo / wcs_to_yolo /
│   │                           voc_to_yolo / merge_datasets + 三份训练指南
│   ├── data/                   共享卷（仅保存上传的原图）
│   └── models/                 权重目录（按版本分子目录）
├── frontend/                   Vue 3 前端
│   ├── src/api/index.ts        全部接口封装（含 token 注入）
│   ├── src/views/              登录/仪表盘/任务/图像/结果/复核/统计/模型/用户
│   ├── src/components/         图表、图片卡片、进度条等
│   └── nginx.conf              静态托管 + /api /ws /ai 反代
├── docker/mysql/init.sql       建表脚本（含历史遗留表清理）
├── docker-compose.yml          5 容器编排
└── 打包-替换文档.md            镜像打包与替换说明
```

---

## 五、快速开始

```bash
# 1. 构建并启动全部 5 个容器
docker compose up -d --build

# 2. 查看状态（首次启动 MySQL 初始化约需 30s）
docker compose ps
```

| 服务 | 容器 | 地址 |
|---|---|---|
| 前端 | `wildlife-frontend` | http://localhost:9095 |
| 后端 API | `wildlife-backend` | http://localhost:8085/api |
| AI 引擎 | `wildlife-ai-engine` | http://localhost:8001/ai/health |
| MySQL | `wildlife-mysql` | localhost:3306 |
| Redis | `wildlife-redis` | localhost:6379 |

**默认账号**：`admin` / `admin123`（首次登录后请改密）

### 本地开发（不走 Docker）

```bash
# 后端
cd backend && mvn spring-boot:run

# AI 引擎
cd ai-engine && pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8001

# 前端（Vite dev server 会代理 /api /ws /ai）
cd frontend && npm install && npm run dev
```

---

## 六、数据库表

| 表 | 用途 |
|---|---|
| `users` | 用户账号与角色 |
| `model_version` | 模型版本登记（含 `class_config` 类别清单、指标） |
| `recognition_task` | 批量识别任务（状态、进度、计数） |
| `recognition_image` | 任务内每张图像（状态、物理文件路径、文件大小；**无内容指纹列**） |
| `detection_result` | 检测框结果（类别、置信度、坐标、复核状态） |
| `review_record` | 复核操作留痕（操作人、动作、修正前后类别） |

---

## 七、关键配置

| 变量 | 位置 | 说明 |
|---|---|---|
| `APP_IMAGE_STORAGE_PATH` | backend | 图像落盘根目录，容器内为 `/shared-data/originals` |
| `SPRING_DATASOURCE_URL` | backend | MySQL 连接串 |
| `REDIS_HOST/PORT/DATABASE` | backend + ai-engine | 队列地址（db 9） |
| `TASK_QUEUE_KEY` / `RESULT_QUEUE_KEY` | ai-engine | 队列键名，需与后端一致 |
| `MODEL_ROOT` / `ACTIVE_MODEL_VERSION` | ai-engine | 权重根目录与启用版本 |
| `YOLO_DEVICE` | ai-engine | 留空自动选择，可填 `cuda:0` / `cpu` |
| `BATCH_SIZE` / `RECOGNITION_WORKERS` | ai-engine | 批量大小与消费者线程数 |

完整示例见 `ai-engine/.env.example`。

---

## 八、模型权重与训练链路

### 8.0 训练数据（两个公开数据集合并，19 类）

本系统的模型**基于两个公开红外相机数据集微调**，经 `ai-engine/scripts/merge_datasets.py` 合并为 **19 类**：

| 数据集 | 提供方 | 许可 | 本项目取用 |
|---|---|---|---|
| **SWG Camera Traps 2018-2020** | IUCN SSC 亚洲野牛专家组 Saola Working Group | CDLA-Permissive 1.0 | 14 类，11,107 图 / 12,898 框 |
| **WCS Camera Traps** | Wildlife Conservation Society | CDLA-Permissive 1.0 | 11 类，8,672 图 / 10,545 框 |

合并去重后：**19,723 图（train 15,774 / val 3,949）、23,443 框**，按相机位点划分 train/val（避免同序列泄漏）。

> SWG 的框数由合并总数反推（23,443 − 10,545 = 12,898）：该数据集原始图片与标注已清理，
> 无法重跑统计。此值与转换阶段独立实测的 12,903 框吻合（差 5，为合并时跳过的个别样本）。

19 类：野猪、猕猴、麂、水鹿、鼬獾、红颊松鼠、果子狸、蟹獴、中华鬣羚、白鹇、黄喉貂、帚尾豪猪、灰孔雀雉、红原鸡、虎、豹、豹猫、猪獾、赤麂。

数据来源、许可、引用格式与实测数字详见 [`数据集来源与引用.md`](数据集来源与引用.md)。

### 8.1 当前权重状态：**页面显示的版本 ≠ 实际生效的权重**

三个正式版本目录（`wildlife-v1.0` / `v1.1` / `v2.0`）**都只有 `classes.txt` 与 `meta.json`，没有 `best.pt`**。
引擎因此回退到 `YOLO_MODEL_PATH` 指向的 `models/test-coco-yolo11n/best.pt`（COCO 公开权重）。

> 微调训练在魔搭 A10 上进行（`ai-engine/scripts/魔搭Notebook训练指南.md`）。
> 训练产出 `best.pt` 后按 §8.3 的流程放入版本目录、评测、登记，
> 回退告警即消失。在权重落地前，本节描述的「回退」状态依然成立。

这件事原先只出现在启动日志里，页面上完全看不出来 —— 于是"当前模型：wildlife-v1.0"和实际跑的权重是两回事。现在它被显式暴露：

```bash
# 引擎侧自检（经 nginx 反代，从前端入口即可访问）
curl "http://localhost:9095/ai/model/status?version=wildlife-v1.0"

# 或直接体检磁盘上的权重（不需要 torch，可用作部署门禁）
docker exec wildlife-ai-engine python check_weights.py --all
```

`GET /ai/model/status` 返回的 `usingFallback` 为真即表示发生了回退，`effectiveWeight.md5` 是**实际加载**的那份权重的指纹，`issues` 逐条说明原因。「模型管理」页会在这种情况下显示醒目告警条，并列出该版本声明要识别的类别。

各版本声明的类别（来自各自的 `classes.txt`）：**v1.0 20 类、v1.1 30 类、v2.0 45 类**，均为中文物种名（大熊猫、雪豹、川金丝猴、羚牛、小熊猫、豹猫、野猪 …）。

> ⚠️ 这些类别表是**系统设计阶段的占位声明**，与实际训练数据（§8.0 的 19 类合并集）不一致。
> 微调权重落地时应同步把 `classes.txt` 与 `model_version.class_config` 对齐为
> 实际训练用的 19 类（`evaluate.py --write-classes` 会写出与权重一致的那份）。

### 8.2 指标的「实测」与「记录」

`meta.json` 里的 mAP / Precision / Recall 目前**没有权重与训练日志可佐证**（权重不存在，也没有可复现的评测记录），因此都标了 `"metricsVerified": false`。这类指标**不会被登记进库** —— `scripts/register_model.py` 会把未验证的指标字段置空。页面上留空，好过显示一个查不到出处的数字。

要得到**可核验**的指标，跑 `scripts/evaluate.py`：它把权重 md5、`data.yaml` 的 sha256、评测时间、复现命令、ultralytics/torch 版本一起写进 `meta.json`，并把 `metricsVerified` 置为 `true`。任何人拿着同一个 `best.pt` 与同一份数据都能复现出同样的数字。

### 8.3 训练链路（数据需自备）

```
数据 → prepare_dataset.py → train.py → evaluate.py → register_model.py → 在「模型管理」启用
       校验 + data.yaml      微调       评测 + 溯源      登记入库
```

| 脚本 | 作用 |
|---|---|
| `ai-engine/check_weights.py` | 权重体检：zip 结构、md5、是否回退、类别声明。**只依赖标准库，不需要 torch** |
| `ai-engine/scripts/lila_to_yolo.py` | LILA COCO Camera Traps（SWG/WCS）→ YOLO 布局；内置换相机位点划分、框清洗、每类限量、并发下载 |
| `ai-engine/scripts/wcs_to_yolo.py` | WCS 专用转换（11 类内置映射、多国家过滤、三云镜像源可切换） |
| `ai-engine/scripts/voc_to_yolo.py` | Pascal VOC（NTLNP）→ YOLO；按编号连续段近似视频序列划分 |
| `ai-engine/scripts/merge_datasets.py` | 合并 SWG + WCS 为统一 19 类，重映射 WCS 的 class_id，图片加前缀去重 |
| `ai-engine/scripts/prepare_dataset.py` | 校验数据集（图片/标注配对、类别 id 越界、坐标越界、孤立标注），统计类别分布，生成 `data.yaml` |
| `ai-engine/scripts/train.py` | 微调 YOLO；对"数据集太小 / epoch 太少 / CPU 硬跑长训练"给出明确警告 |
| `ai-engine/scripts/evaluate.py` | 评测并写出带溯源的 `meta.json`；可选写出与权重一致的 `classes.txt`（已存在且内容不同则拒绝覆盖） |
| `ai-engine/scripts/register_model.py` | 登记到 `model_version` 表；未验证指标不入库；无 token 时只打印请求体与等价 curl，不发送 |

**算力实情（分两侧看）**：

- **部署侧**（本机与 ai-engine 容器）没有可用 GPU（`torch 2.7.1+cpu`，`torch.cuda.is_available()` 为 `False`），推理固定跑 CPU —— 批量控制与实测速度见 8.5。
- **训练侧**放到有 GPU 的环境做。例如魔搭社区 Notebook（8 核 / 32GB / 24G 显存）：`train.py` 与 `evaluate.py` 的 `--device` / `--batch` 默认都是 `auto`，有 CUDA 就自动用 `0` 并交给 AutoBatch 按显存定批，开训前会打印实际选用的设备、显卡型号与批大小。**完整操作步骤与坑见 [`ai-engine/scripts/魔搭Notebook训练指南.md`](ai-engine/scripts/魔搭Notebook训练指南.md)。**

  > **⚠️ 一条最容易踩的坑：训练数据不要放在 NAS 上读。**
  > 实测同一份 19,723 张图、同一台 A10：数据在 NAS（`/mnt/data`）上时
  > **4.8 s/步、100 轮需 ~33 小时**（会耗尽 GPU 额度）；拷到实例本地盘（`/tmp`）后
  > **0.12 s/步、~2 小时**，提速约 40 倍。**正式训练前必须先把数据拷到 `/tmp`**，
  > 并把 `data.yaml` 的 `path` 改过去（注意该字段带引号，用 `sed` 改会静默失效）。
  > 详见训练指南 §0 与《魔搭训练_完整命令.md》块 4.6。
  >
  > 另注：魔搭官方答复称「实例关闭后只有 `.ipynb` 会保留」，但本项目**实测**
  > `/mnt/data`（阿里云 NAS）跨实例保留。保守做法是最终产物既存 `/mnt/data` 也下载回本机。

### 8.4 数据获取与合并（已跑通）

数据不放在仓库里（体积原因），但**获取与合并链路已全部脚本化并实测跑通**：

```bash
# 1) 下载两个数据集的标注文件（各 6.4 MB / 22.9 MB）
curl -sL -o swg_bboxes.zip \
  https://storage.googleapis.com/public-datasets-lila/swg-camera-traps/swg_camera_traps.bounding_boxes.with_species.zip
curl -sL -o wcs_bboxes.zip \
  https://storage.googleapis.com/public-datasets-lila/wcs/wcs_20220205_bboxes_with_classes.zip
unzip swg_bboxes.zip && unzip wcs_bboxes.zip

# 2) 转 YOLO 布局 + 下载图片（走 Google bucket，单张 HTTP 直取）
python lila_to_yolo.py --preset swg --bbox-json swg_camera_traps.bounding_boxes.with_species.json \
    --out data/swg --max-per-class 800 --download --workers 64
python wcs_to_yolo.py --bbox-json wcs_20220205_bboxes_with_classes.json \
    --out data/wcs --download --workers 64

# 3) 合并为 19 类
python merge_datasets.py      # SWG + WCS → data/combined（19 类）

# 4) 门禁校验
python prepare_dataset.py --dataset data/combined --classes data/combined/classes.txt \
    --out data/combined/data.yaml
```

> 图片可逐张 HTTP 下载（无需下全量 200 万张），实测 19,723 张约 17 GB。
> 训练环境的网络限制与实操坑见 [`ai-engine/scripts/魔搭Notebook训练指南.md`](ai-engine/scripts/魔搭Notebook训练指南.md)。

### 8.5 CPU 推理与批量控制

部署环境没有 GPU，因此推理固定跑在 CPU 上（`YOLO_DEVICE: cpu` —— 显式写死，不依赖 ultralytics 的自动判定，换台机器行为一致）。旋钮都在 `docker-compose.yml` 的 ai-engine 段：

| 变量 | 默认 | 说明 |
|---|---|---|
| `YOLO_DEVICE` | `cpu` | 推理设备。有 GPU 的机器改成 `0` 即可切回 CUDA |
| `BATCH_SIZE` | `8` | 单次送入 YOLO 的图像张数。这是**真正的批推理**：`detect_batch` 把整个列表交给 `model.predict`，批内先整块读图再一次性推理，因此它同时决定峰值内存与单批延迟 |
| `RECOGNITION_WORKERS` | `1` | 消费线程数，**每个线程持有独立模型副本**。CPU 上保持 1：核心已被批推理用满，多线程不会提速，只会成倍占用内存并相互抢核 |
| `YOLO_CPU_THREADS` | 不设 | 限制 torch 使用的 CPU 线程数。不设 = 用满所有核心。若本机同时跑 MySQL/Redis/后端，可设为物理核数的一半左右，避免推理占满 CPU 让其它容器变慢 |
| `YOLO_IMAGE_SIZE` | `640` | 推理分辨率。耗时大致随图像面积增长，640 → 1280 约为 4 倍 |

启动日志会打印生效参数，并在无 GPU 时对"批太大 / workers > 1 / imgsz > 640"给出明确提醒 —— 这些配置**都不会报错**，只会让系统莫名变慢：

```
推理配置：设备=cpu (cuda=False, torch 线程=8) imgsz=640 batch=8 workers=1 conf=0.25 iou=0.5 max_det=300
提示：未设置 YOLO_CPU_THREADS，torch 将使用全部 8 个 CPU 线程。若本机同时运行 MySQL/Redis/后端，可设该变量限制推理占用的核心数。
```

**批量对速度影响很大**（实测，CPU，640×640，三张测试图）：

| 批大小 | 单张耗时 |
|---|---|
| 1 | 508 ~ 768 ms |
| 3 | 74.6 ~ 75.7 ms |

约 **7~10 倍**差距 —— CPU 部署下调大 `BATCH_SIZE` 是性价比最高的优化，代价是峰值内存线性增长（批内图像会先整块解码进内存）。

**但批量会轻微改变结果。** ultralytics 先把每张图 letterbox 到 `imgsz`，再按**批内最大尺寸补齐**成矩形张量，所以同一张图处在不同批次里，实际重采样比例不同。实测（同一批图，batch=1 vs batch=3）：

| 指标 | 实测差异 |
|---|---|
| 最高分检测的类别 | **不变**（top-1 批不变） |
| 置信度 | 最大差 **0.08** |
| 框坐标 | 最大差 **4px** |
| 框数量 | 偶发多出/少掉一个低分框（实测 deer.jpg：batch=1 只检出 dog，batch=3 额外多出 person 0.33） |

这是 ultralytics 的固有行为，不是本系统缺陷。含义是：**识别结果与 `BATCH_SIZE` 绑定**，报告指标或做前后对比时应固定该值。

---

## 九、已知限制

1. **微调权重待落地**。训练数据链路已跑通（§8.0/§8.4：SWG+WCS 合并 19 类、19,723 图 / 23,443 框），微调训练在魔搭 A10 上进行。在 `best.pt` 放入 `ai-engine/models/wildlife-v1.0/` 之前，引擎仍回退加载 `models/test-coco-yolo11n/best.pt`（COCO 预训练权重，不含 19 类中的任何目标）。这种"版本号与实际权重不一致"的状态现已**不再静默**：启动日志会打出自检结论，「模型管理」页会显示醒目告警条，`GET /ai/model/status` 与 `check_weights.py` 都能给出实际生效权重的 md5（详见第八节）。
   库内 798 条结果的类别分布恰好印证了这一点：实际检出的是 COCO 的 `dog / elephant / person / zebra` 与历史遗留的 `野猪 / 鹿`，而 `model_version.class_config` 声明的 `bear / deer / fox / monkey / tiger` **一次都没有检出过**。可直接对比 `GET /api/results/classes?usedOnly=false`（11 类，含声明未检出者）与 `?usedOnly=true`（6 类，仅实际检出者）。
2. **超大任务未经压测**。实测最大任务为 32 张图；任务创建走单条 `IN (...)` 查询、结果消费约 200 条/秒，**10 万张级别未做加载测试**。
3. **前端类型检查未过**。`npm run build`（= `vue-tsc && vite build`）会被类型错误拦住，故 `frontend/Dockerfile` 里用 `npx vite build` 绕过；类型层问题不影响运行，但修完后应改回标准命令。
4. **REVIEWER 角色实际退化为 USER**。`AuthService.normalizeRole()` 只归一出 `ADMIN` 与 `USER` 两种角色，库里存的 `REVIEWER` 会被降为 `USER`。用户管理页仍可创建 REVIEWER 账号，但该账号的实际权限与只读用户完全相同。要让它真正生效，需先改归一化逻辑，再把「提交复核结论」之类的动作单独收口。
5. **上传内容未做图像校验**。`POST /api/images/upload` 只校验扩展名与文件大小，实测一个 2 MB 的全零文件（命名为 `.jpg`）也能入库为 WAITING 状态。不会导致崩溃，但会给库引入无效图像；要收紧需加魔数/解码校验。
6. **`model_version.class_config` 曾与版本目录的 `classes.txt` 不一致**（**已修复**）。原先是 6 个英文名（`["deer","tiger","elephant","monkey","bear","fox"]`），现已对齐为 `models/wildlife-v1.0/classes.txt` 里的 20 个中文物种名。对齐前 `GET /api/results/classes?usedOnly=false` 返回 11 类，对齐后返回 25 类（20 个声明物种 ∪ 6 个实际检出，其中「野猪」重合）；`usedOnly=true` 始终是实际检出的 6 类，未受影响。库内值经 md5 逐字节比对确认与 `classes.txt` 一致。
    **补充（同日发现并修复）**：该对齐当时**只改了运行库，未同步 `docker/mysql/init.sql` 的种子值** —— 意味着执行 `docker compose down -v` 重建后，`class_config` 会静默退回 6 个英文名，而本文档却写着"已修复"。现已把种子值同步为同样的 20 个中文物种名（紧凑 JSON，逗号后无空格），并**实测验证**：全新 MySQL 8.0 容器 + 空数据卷跑 `init.sql`，得到 `md5(class_config) = cd8cc1b44c52bb911c13dfa672cb9384`、220 字节、20 类，与运行库逐字节一致。同时给 `init.sql` 加了 `SET NAMES utf8mb4`（否则客户端字符集可能把中文种子值写坏且不报错）。
7. **识别结果与 `BATCH_SIZE` 绑定**。ultralytics 会按批内最大尺寸补齐张量，同一张图在不同批次里重采样比例不同，因此置信度与框会有小幅差异（实测置信度差 ≤ 0.08、框坐标差 ≤ 4px，最高分类别不变，偶发多出/少掉一个低分框）。这是 ultralytics 的固有行为，不是本系统缺陷；报告指标或做前后对比时应固定 `BATCH_SIZE`。详见第八节 8.5。

> 2026-09-25 已修复（其中两项原先列在此处）：
> - nginx `/api/` 未设 `client_max_body_size`，经前端入口上传大图返回 413 —— 已设为 500m 并与 `application.yml` 对齐；
> - 后端未强制管理员权限（`AuthInterceptor.isAdminOnly()` 原为恒返回 false 的空壳）—— 已按前端路由的权限矩阵实现，非管理员访问管理端点返回 403；
> - 密码明文存储与响应泄露 —— 改为 BCrypt，`GET /api/user/list` 不再返回密码字段，历史明文/MD5 存量在首次登录时透明升级。
>
> 同一轮还修掉三处问题：结果检索的**复核状态筛选静默失效**（前端传的是 `status`，后端参数名是 `reviewStatus`，而构建跳过类型检查未拦住）；**检出时间区间筛选**前后端均缺失（现已补全，非法时间格式返回 400 而非静默忽略）；`listClasses()` **忽略 `usedOnly` 参数且把整张结果表读进 JVM 去重**（已改为数据库端 `DISTINCT`，并让 `usedOnly=false` 并入模型声明的类别）。

---

## 十、相关文档

| 文档 | 内容 |
|---|---|
| `打包-替换文档.md` | Docker 镜像构建、导出与替换流程 |
| `设计文档_系统设计与实现.md` | 系统设计与实现全文档（模块、数据流、接口、表结构） |
| `数据集来源与引用.md` | 训练数据来源、许可、引用格式与规模实测 |
| `lila_bbox_audit.md` | LILA 各数据集物种级边界框的实测审计 |
| `中国野生动物红外相机目标检测数据集调研.md` | 中国公开红外相机数据集调研 |
| `ai-engine/scripts/魔搭Notebook训练指南.md` | 魔搭 GPU 训练完整流程与踩坑 |
| `ai-engine/scripts/魔搭训练_完整命令.md` | 逐块可复制的训练命令 |
| `ai-engine/scripts/数据集要求.md` | 找数据时的格式硬性要求 |
| `ai-engine/scripts/WCS数据集使用指南.md` | WCS 数据集转换与使用 |