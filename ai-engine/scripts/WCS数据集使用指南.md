# 数据集方案：WCS Camera Traps（已实测验证）

> **结论先行**：用 **WCS Camera Traps** 这个公开数据集，可以在**今天**就开始训练，
> 而且产出的是**真正的物种级目标检测**模型 —— 不需要自己标注一张图。
>
> 本文所有数字都是**实测**得到的，不是估计。验证过程见文末「已验证清单」。

> ## 📌 现状更新（2026-10）：WCS 与 SWG **合并**使用，共 19 类
>
> 本文最初把 WCS 当作**唯一**数据集（11 类）。实际训练采用的是
> **WCS + SWG 合并**方案（`merge_datasets.py`），共 **19 类 / 19,723 图 / 23,443 框**：
>
> - SWG 贡献 14 类（本文档未覆盖，见 `数据集来源与引用.md` §1.2）
> - WCS 贡献 11 类，其中 **7 类与 SWG 重合**（野猪/猕猴/麂/果子狸/中华鬣羚/白鹇/黄喉貂）
> - WCS **独有 4 类**：虎、豹、豹猫、猪獾 —— 这是 WCS 的独特价值（SWG 没有大猫）
>
> 本文档仍然有效：它记录的 **WCS 单独的实测数据、转换命令、方法论**都适用，
> 只是最终产物会与 SWG 合并。**下载与转换步骤照本文走，合并步骤另见
> `merge_datasets.py` 与《魔搭训练_完整命令.md》块 2.3。**
>
> 合并后的类别分布、训练规模见 `数据集来源与引用.md` §0。

---

## 一、为什么是它

找数据集时最大的坑是：**红外相机数据集绝大多数只有图片级分类标签，没有边界框。**
只有框的（如 MegaDetector）又只有 `animal/person/vehicle` 三类，不含物种。
两者都不满足「目标检测」的要求。

WCS Camera Traps 是少数**同时具备物种标签与物种级边界框**的公开数据集：

| 项目 | 实测值 |
|---|---|
| 来源 | Wildlife Conservation Society，托管于 LILA BC |
| 主页 | https://lila.science/datasets/wcscameratraps |
| 许可证 | **CDLA-Permissive 1.0**（明确允许使用，论文可写） |
| 全库规模 | 约 140 万张图，约 675 个物种，12 个国家 |
| **带类别边界框文件** | `wcs_20220205_bboxes_with_classes.zip`（22.87 MB） |
| 解压后 | `wcs_20220205_bboxes_with_classes.json`（**220.89 MB**） |
| 该文件实测内容 | **298,725 张图 / 429,482 个标注 / 678 个类别，其中 611 类带框** |
| 关键优势 | 图片支持**逐张 HTTP 直接下载**，不用下 140 万张全集 |

> 引用：使用该数据集时请引用 LILA BC 与 WCS 的数据集条目。
> 若论文需要，可同时引用 LILA 的 COCO Camera Traps 格式说明与 MegaDetector 相关工作。

---

## 二、能拿到什么（实测）

从你的 20 个目标物种里，**9 个能拿到**；另外还多出「豹」「虎」两类，共 **11 类**：

| 类别 | 来源学名 | train 框 | train 图 | val 框 | val 图 |
|---|---|---|---|---|---|
| 猕猴 | macaca nemestrina / arctoides / fascicularis | 3,194 | 2,162 | 872 | 582 |
| 赤麂 | muntiacus muntjak | 2,329 | 2,248 | 579 | 568 |
| 野猪 | sus scrofa | 1,015 | 687 | 215 | 142 |
| 黄喉貂 | martes flavigula | 368 | 349 | 125 | 118 |
| 鬣羚 | capricornis sumatraensis / milneedwardsii | 383 | 378 | 31 | 30 |
| 白鹇 | lophura nycthemera | 269 | 224 | 89 | 80 |
| 豹 | panthera pardus | 202 | 200 | 68 | 67 |
| 猪獾 | arctonyx collaris | 208 | 207 | 32 | 32 |
| 果子狸 | paguma larvata | 164 | 163 | 50 | 49 |
| 虎 | panthera tigris | 147 | 145 | 41 | 40 |
| 豹猫 | prionailurus bengalensis | 140 | 140 | 24 | 24 |
| **合计** | | **8,419** | **6,903** | **2,126** | **1,732** |

- **总计 8,672 张带框图片、10,545 个框**，远超毕设需求（2,000~5,000）
- **11 个类别每一个都同时出现在 train 与 val 中** —— 每类都有独立验证样本，mAP 才有意义
- 图片主要为印尼（idn）与老挝（lao）站点，少量豹来自肯尼亚

### 覆盖不到的（必须如实处理）

原始 20 类里这 **11 类拿不到**，因为 WCS 没有高海拔中国特有种：

> 大熊猫、雪豹、川金丝猴、羚牛、小熊猫、小麂、毛冠鹿、斑羚、红腹角雉、血雉、绿尾虹雉

**处理建议**：把类别表改成上面实测的 11 类，论文里写清「类别体系可配置，
本实验基于 WCS 公开数据构建 11 类检测集」。这比硬凑 20 类（其中 11 类无数据、
mAP 恒为 0）要诚实得多，也好看得多。

---

## 三、三步命令（在魔搭 Notebook 上跑）

```bash
cd /mnt/workspace/wildlife

# ── 第 1 步：下载标注文件（22.87 MB）──────────────────────────
mkdir -p /mnt/workspace/data/wcs && cd /mnt/workspace/data/wcs
wget -q --show-progress \
  https://storage.googleapis.com/public-datasets-lila/wcs/wcs_20220205_bboxes_with_classes.zip
unzip -o wcs_20220205_bboxes_with_classes.zip     # → 220.89 MB 的 json

# ── 第 2 步：转成 YOLO 布局 + 下载图片 ────────────────────────
cd /mnt/workspace/wildlife
python wcs_to_yolo.py \
    --bbox-json /mnt/workspace/data/wcs/wcs_20220205_bboxes_with_classes.json \
    --out /mnt/workspace/data/wildlife \
    --download --workers 16
#   不加 --download 则只生成标注与下载清单（秒级完成）
#   想只要亚洲站点加：--countries idn,lao

# ── 第 3 步：过门禁（项目自带校验器）──────────────────────────
python prepare_dataset.py \
    --dataset /mnt/workspace/data/wildlife \
    --classes /mnt/workspace/data/wildlife/classes.txt \
    --out /mnt/workspace/data/wildlife/data.yaml
echo "退出码=$?   # 0 = 数据合格，可以开训"
```

然后接你已有的训练链路（见 `魔搭训练_完整命令.md` 块 5~8）：

```bash
python train.py --data /mnt/workspace/data/wildlife/data.yaml \
                --model yolo11n.pt --epochs 100
python evaluate.py ...    # 生成 metricsVerified=true 的 meta.json
```

图片约 8,672 张、估计 2~4 GB，魔搭上下载很快。**A10 上训 100 轮预计 1~2 小时。**

---

## 四、方法论要点（写进论文是加分项）

### 1. 为什么必须按「相机位点」划分 train/val

红外相机的图片是**序列**：一头鹿走过，连拍十几张。如果按图片随机划分，
同一头鹿、同一个位点、同一段光照的相邻帧会**同时进入训练集与验证集**，
验证指标会被严重高估。这正是 Beery 等人 *Recognition in Terra Incognita* 指出的问题。

`wcs_to_yolo.py` 因此**按 `image.location` 划分**（本次实测：train 898 个位点 /
val 197 个位点），保证同一位点只出现在一侧。并且会**自动修复**「某类别在 val 中缺席」
的情况（把含该类别的最小位点移入 val），确保 11 类都有验证样本。

> 论文里把这一点写出来，比多训 10 个 epoch 有价值得多。

### 2. 类别不均衡是真实的，不要藏

猕猴占 38.6%、赤麂 27.6%，最小的豹猫只占 1.6%。这是红外相机数据的常态。
论文里应报告**每类 AP**，而不是只报一个总体 mAP —— 否则总体 mAP 会被两个大类主导。

### 3. 框清洗

脚本会：裁框到图片边界、丢弃退化框、丢弃短边 < 8px 的框、
丢弃面积 > 整图 90% 的「整帧框」（WCS 里存在这类粗框）。
本次实测**丢弃后仍有 10,545 个有效框**。

### 4. 数据跨国家，要在论文里说明

豹主要来自肯尼亚（226/279），虎来自印尼（苏门答腊虎）。同种不同亚种/生境混在一起，
是这批公开数据的客观情况，如实写出来即可。

---

## 五、已验证清单（本文所有结论的证据）

| 验证项 | 结果 |
|---|---|
| 标注文件可下载并解压 | ✅ 22.87 MB → 220.89 MB JSON |
| JSON 结构含所需字段 | ✅ `width`/`height` 100% 齐全（298,725/298,725），`bbox` 为 COCO 绝对像素，`location` 有 3,792 个取值 |
| 你的目标物种在清单中 | ✅ 物种清单 `wcs_specieslist.csv` 实测命中 9 个原定物种 + 豹 + 虎 |
| 转换脚本在真实数据上可运行 | ✅ 退出码 0，输出 8,672 图 / 11 类 |
| 全部标注格式正确 | ✅ 10,545 行框：字段数错误 0、类别 id 越界 0、坐标越界 0、宽高非正 0 |
| 图片与标注严格配对 | ✅ train 6,903/6,903，val 1,732/1,732 |
| 每个类别两侧都有 | ✅ train 11/11，val 11/11 |
| 项目门禁 `prepare_dataset.py` 通过 | ✅ **退出码 0**，孤立标注 0、非法行 0 |
| 图片真的能下载 | ✅ 抽样下载 85 张（5 张各类 + 80 张子集），全部为有效 JPEG（magic `FFD8FF`），0 失败 |

> 复现命令与中间产物见 `.workbuddy/tmp/cleanup/`（`analyze_wcs.py`、`verify_full.py`、
> `run_log.txt`、`gate_log.txt`、`verify_result.txt`）。

---

## 六、备选方案

若上述类别不合适，还有两条路（调研中，结论待补）：

1. **Caltech Camera Traps（CCT）** —— 243,100 张图 / 21 个北美物种级类别 /
   **约 66,000 个边界框**，CDLA-Permissive 1.0。有 6GB 的降采样基准子集（CCT20），
   同样提供基于位点的 train/val 划分。
   主页：https://lila.science/datasets/caltech-camera-traps
   缺点：物种全是北美种（负鼠、浣熊、郊狼…），与"中国野生动物"主题更远。

2. **自建数据集** —— 若你有红外相机原始数据（导师课题、保护区合作），
   按 `数据集要求.md` 的格式标注即可。这是最好的路线，但需要标注工时。

> ❌ **不要用 NACTI**：虽然它有 370 万张图、28 个物种级类别，
> 但其边界框只加在 8,892 张图上，且"mostly vehicles and birds"，
> **不是物种级框**，无法用于物种检测。