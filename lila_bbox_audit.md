# LILA BC 红外相机数据集边界框审计报告

> ## 📌 后续更新（2026-10）：本审计的结论已被采用
>
> 本报告筛选出「**真正带物种级边界框**」的 LILA 数据集共 8 个。
> 最终**采用了其中两个**（本报告 §0 与 §2 有详细实测数据）：
>
> - **SWG Camera Traps** —— 14 类，11,107 图 / 20,600 框
> - **WCS Camera Traps** —— 11 类，8,672 图 / 10,545 框
>
> 两者合并为 **19 类 / 19,723 图 / 23,443 框**，用于本项目的 YOLO 微调。
>
> 本报告同时**排除了** NACTI、Snapshot Serengeti、Idaho、iWildCam 2022 等
> 常被误认为可用的数据集（它们的框只有 `animal/person/vehicle` 三类，
> 不是物种级），这一排除过程可直接写进论文的「数据集选型」章节。
>
> 最终采用详情见 `数据集来源与引用.md`。
>
> ---
>
> ⚠️ 注：报告末尾提到的原始证据目录 `_lila_audit/` 已于 2026-10 清理
> （783 MB 的下载文件与扫描脚本），但**本报告中的所有实测数字均保留**。

> 审计方式：**逐个抓取 LILA 数据集页面** + **实际下载并解析每个数据集的 COCO Camera Traps JSON 元数据文件**，统计 `annotations[].bbox` / `bbox_relative` 的实际存在情况与框所用类别。
> 审计日期：本次会话。所有数字均为**实测值**（用 Python 解析真实 JSON 得到），非页面文字转述；页面文字与实测不一致处已标注。
> 原始证据文件保存在 `_lila_audit/`（`dl/` 为下载的元数据，`sweep.py` / `inspect_cct.py` / `analyze_multi.py` 为审计脚本）。

---

## 0. 结论速览（先看这个）

**真正带物种级边界框的相机陷阱数据集共 8 个（另有 1 个只到"类"级）：**

WCS Camera Traps、SWG Camera Traps、Island Conservation、Oregon Critters、Nkhotakota、Caltech Camera Traps (CCT)、ENA24-detection、Missouri Camera Traps，加上**粗类级**的 Channel Islands。

**重点纠错（与普遍印象相反）：**

- **NACTI 的框不是物种级**——8892 张图、10564 个框，类别只有 `animal / person / vehicle / group`。页面写"mostly vehicles and birds"极具误导性。
- **Snapshot Serengeti 的框不是物种级**——82938 张图、146359 个框，类别只有 `animal / person / group`。
- **Idaho Camera Traps 一个框都没有**（1,535,725 张图，0 个 bbox）。
- LILA 那个"全 LILA 框大合集" `mdv5_lila_boxes.zip`（110 万框）**也是纯 3 类**，物种信息被刻意剥离了。
- LILA 的统一大表 `lila_image_urls_and_labels.csv` **完全没有 bbox 列**。

**所以：物种级框只存在于各数据集的独立文件里，没有任何"全库统一带物种框"的资源。**

---

## 1. COCO Camera Traps 格式里 `bbox` 什么时候出现？

来源（实际抓取）：<https://raw.githubusercontent.com/agentmorris/MegaDetector/main/megadetector/data_management/README.md>
（LILA FAQ 与多个数据集页面均指向上文；永久短链 <https://lila.science/coco-camera-traps>）

`annotation` 对象的定义：

```
annotation
{
  ## Required ##
  "id" : str,
  "image_id" : str,
  "category_id" : int,

  ## Optional ##
  "count" : int,

  # These are in absolute, floating-point coordinates, with the origin at the upper-left
  # Mutually exclusive with "bbox_relative"
  "bbox" : [x,y,width,height],

  # These are in normalized, floating-point coordinates, with the origin at the upper-left
  # Mutually exclusive with "bbox"
  "bbox_relative" : [x,y,width,height],

  # This indicates that this annotation is really applied at the *sequence* level,
  # and may not be reliable at the individual-image level.
  "sequence_level_annotation" : bool
}
```

**关键点：**

1. `bbox` 和 `bbox_relative` 都是**可选字段**（Optional），且**二者互斥**。格式规范里**没有任何一处强制要求 bbox**。
2. `category_id` 是**必需**的——但它指向的类别体系**完全由各数据集自己定**。所以"有没有框"和"框是不是物种级"是**两个独立问题**：一个框必然有 category_id，但这个 category 可能是 `animal`，也可能是 `tayassu pecari`。
3. 没有框时，annotation 退化为**纯图片级分类标签**（`image_id` + `category_id`）。这正是 LILA 上绝大多数数据集的状态。
4. `sequence_level_annotation` 字段是重要的质量警告：标签可能只对整个连拍序列可靠，对单帧不可靠。
5. 格式里**没有**任何"数据集级"的声明字段说"本数据集有/没有框"——必须去读实际文件。这是本次审计必须逐个下载 JSON 的原因。

---

## 2. 总表（全部为实测值）

"图片数"列：`数据集总数（元数据文件中的图片数）`。"框数"为实测 `with_bbox` 的 annotation 数。

| 数据集 | 图片数 | 类别数 | 有物种级框? | bbox 来源 | 许可 | 下载方式 |
|---|---|---|---|---|---|---|
| **WCS Camera Traps** | ~1.4M (298,725) | 678 | ✅ **是**，374,270 框 / 611 类 | `wcs_20220205_bboxes_with_classes.zip` (独立文件) | CDLA-Permissive-1.0 | `gs://public-datasets-lila/wcs-unzipped` |
| **SWG Camera Traps** | 2,039,657 (120,321) | 121 | ✅ **是**，101,659 框 / 99 类 | `swg_camera_traps.bounding_boxes.with_species.zip` | CDLA-Permissive-1.0 | `gs://public-datasets-lila/swg-camera-traps` |
| **Island Conservation** | ~123,000 (127,410) | 49 | ✅ **是**，64,671 框 / 48 类 | **主元数据内** | CDLA-Permissive-1.0 | `gs://public-datasets-lila/islandconservationcameratraps/public` |
| **Oregon Critters** | 99,909 | 46 | ✅ **是**，93,150 框 / 45 类 | **主元数据内** | CDLA-Permissive-1.0 | `gs://public-datasets-lila/oregon-critters` |
| **Nkhotakota** | 321,562 (324,635) | 46 | ✅ **是**，49,057 框 / 39 类 | **主元数据内** | CDLA-Permissive-1.0 | `gs://public-datasets-lila/nkhotakota-camera-traps` |
| **Caltech Camera Traps** | 243,100 (63,025) | 22 | ✅ **是**，65,112 框 / ~18 动物类 | `caltech_bboxes_20200316.json` | CDLA-Permissive-1.0 | `gs://public-datasets-lila/caltech-unzipped/cct_images` |
| **ENA24-detection** | ~10,000 (9,676) | 23 | ✅ **是**，11,596 框 / 23 类 | **主元数据内** | CDLA-Permissive-1.0 | `gs://public-datasets-lila/ena24/images` |
| **Missouri Camera Traps** | ~25,000 (24,673) | 21 | ✅ **是**，956 框 / 20 类 | **主元数据内** | CDLA-Permissive-1.0 | `gs://public-datasets-lila/missouricameratraps/images` |
| **Channel Islands** | 246,529 (245,529) | 7 | ⚠️ **仅"类"级**，149,372 个有效框 | **主元数据内** | CDLA-Permissive-1.0 | `gs://public-datasets-lila/channel-islands-camera-traps/images` |
| NACTI | 3.7M (3,382,215) | 59 | ❌ 否（仅 animal/person/vehicle/group） | `nacti_20230920_bboxes.zip`（8,892 图） | CDLA-Permissive-1.0 | `gs://public-datasets-lila/nacti-unzipped` |
| Snapshot Serengeti | 7.1M (S01: 411,414) | 61 | ❌ 否（仅 animal/person/group） | `SnapshotSerengetiBboxes_20190903.json.zip`（82,938 图） | CDLA-Permissive-1.0 | `gs://public-datasets-lila/snapshotserengeti-unzipped` |
| Idaho Camera Traps | ~1.5M (1,535,725) | 62 | ❌ 无任何框（实测 0） | — | 自定义（IDFG，禁止转售） | `gs://public-datasets-lila/idaho-camera-traps/public` |
| Wellington Camera Traps | 270,450 | 17 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/wellington-unzipped/images` |
| Ohio Small Animals | 118,554 | 46 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/osu-small-animals` |
| California Small Animals | 2,278,071 | 256 | ❌ 无任何框 | — | **CC-BY 4.0** | `gs://public-datasets-lila/california-small-animals` |
| Snapshot Safari 2024 Expansion | 4,029,374 | 131 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/snapshot-safari-2024-expansion` |
| NZ Trail Cameras | ~2.5M (2,453,840) | 110 (实测 98) | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/nz-trailcams` |
| WSU Lynx | 1,205,453 | 26 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/wsu-lynx` |
| AMMonitor Camera Traps | ~1.2M (1,858,251) | 77 | ❌ 无任何框 | — | 公有领域（ScienceBase） | `gs://public-datasets-lila/ammonitor-camera-traps` |
| Felidae Conservation Fund | 357,934 | 66 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/felidae-conservation-fund` |
| Orinoquía Camera Traps | 112,221 | 58 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/orinoquia-camera-traps` |
| Desert Lion Conservation | 65,959 (63,468) | 46 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/desert-lion-camera-traps/annotated-imgs` |
| Duck Pictures in Wetlands | 674,426 (675,948) | 11 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/duck-pictures-in-wetlands` |
| Snapshot Karoo | 38,074 (38,293) | 38 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/snapshot-safari/KAR/KAR_public` |
| Snapshot Kgalagadi | 10,222 (10,357) | 31 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/snapshot-safari/KGA` |
| Snapshot Kruger | 10,072 (10,604) | 46 | ❌ 无任何框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/snapshot-safari/KRU` |
| UNSW Predators | 131,802 | 5 | ❌ 页面未提框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/unsw-predators/images` |
| Seattle(ish) Camera Traps | ~20,000 | — | ❌ 页面未提框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/seattleish-camera-traps` |
| Biome Health Project Maasai Mara | 37,075 | 100 | ❌ 页面未提框 | — | CDLA-Permissive-1.0 | `gs://public-datasets-lila/biome-health-project-maasai-mara-2018` |

**说明**：括号内为元数据 JSON 中的实际图片数（与页面文字偶有出入，以实测为准）。

### 可复制的下载命令模板

LILA FAQ（<https://lila.science/faq#downloadtips>）给出的三种命令行方式（原文照抄）：

```bash
# GCP —— 把 https://storage.googleapis.com/ 换成 gs://
gsutil cp "gs://public-datasets-lila/mydataset/myfile.zip" "./myfile.zip"

# AWS —— 把 http://us-west-2.opendata.source.coop.s3.amazonaws.com/ 换成 s3://...coop/，必须加 --no-sign-request
aws s3 cp "s3://us-west-2.opendata.source.coop/agentmorris/lila-wildlife/mydataset/myfile.zip" "./myfile.zip" --no-sign-request

# Azure
azcopy cp "https://lilablobssc.blob.core.windows.net/mydataset/myfile.zip" "./myfile.zip"
```

整目录（推荐，免解压大 zip）：

```bash
gsutil -m cp -r "gs://public-datasets-lila/oregon-critters" .
aws s3 sync "s3://us-west-2.opendata.source.coop/agentmorris/lila-wildlife/oregon-critters" ./oregon-critters --no-sign-request
azcopy sync "https://lilawildlife.blob.core.windows.net/lila-wildlife/oregon-critters" "./oregon-critters" --recursive
```

---

## 3. 逐个数据集细节

### 3.1 ✅ WCS Camera Traps —— 全库最大的物种级框来源

- 页面：<https://lila.science/datasets/wcscameratraps>
- 图片：约 1.4M，来自 12 个国家，约 675 个物种；约 50% 为空图
- 类别数：**678**
- **框（实测）**：`wcs_20220205_bboxes_with_classes.json` → 298,725 张图、429,482 条 annotation，其中 **374,270 条带 bbox**，覆盖 **611 个类别**（含 `human`、`vehicle` 等非动物类）
  - 每图框数：均值 1.54，最大 26；243,513 张有框图中 **56,129 张有 >1 个框**
  - 最多的类：tayassu pecari (96,829)、bos taurus (19,324)、equus quagga (15,752)、meleagris ocellata (15,157)
- **另一版本**：`wcs_20220205_bboxes_no_classes.zip` —— 官方明确说明"with just animal/person/vehicle labels"，即**同一批框的 3 类退化版**
- 许可：CDLA-Permissive-1.0
- 重要提醒：页面标注 "Images were labeled at a combination of image and sequence level" —— 框对应的物种标签可能来自同序列其他帧

```bash
gsutil cp "gs://public-datasets-lila/wcs/wcs_20220205_bboxes_with_classes.zip" ./wcs_bboxes.zip
gsutil cp "gs://public-datasets-lila/wcs/wcs_camera_traps.json.zip" ./wcs_labels.zip
gsutil -m cp -r "gs://public-datasets-lila/wcs-unzipped" .
```

### 3.2 ✅ SWG Camera Traps 2018-2020

- 页面：<https://lila.science/datasets/swg-camera-traps>
- 图片：436,617 个序列 / **2,039,657 张**，982 个点位（越南、老挝）；120 个类别
- 页面原文："101,659 bounding boxes are provided on 88,135 images." —— **与实测完全一致**
- **框（实测）**：`swg_camera_traps.bounding_boxes.with_species.json` → 120,321 张图、133,837 条 annotation，其中 **101,659 条带 bbox**，覆盖 **99 个类别**
  - 每图框数：均值 1.15，最大 14；88,135 张有框图中 9,205 张有 >1 框
  - 最多的类：eurasian_wild_pig (17,801)、unidentified_murid (9,818)、large_antlered_muntjac (8,238)
  - 注意有 `chinese_serow` 与 `chinese serow` 两种写法并存（下划线与空格），是数据里的**类别命名不一致**
- **另有 3 类退化版**：`...bounding_boxes.no_species.zip`
- 许可：CDLA-Permissive-1.0

```bash
gsutil cp "gs://public-datasets-lila/swg-camera-traps/swg_camera_traps.bounding_boxes.with_species.zip" ./swg_bboxes.zip
gsutil -m cp -r "gs://public-datasets-lila/swg-camera-traps" .
```

### 3.3 ✅ Island Conservation Camera Traps

- 页面：<https://lila.science/datasets/island-conservation-camera-traps>
- 图片：约 123,000（实测元数据 127,410），123 个点位 / 7 个岛 / 6 个国家
- 类别数：49
- 页面原文："We have also included approximately 65,000 bounding box annotations for about 50,000 images."
- **框（实测，在主元数据文件里，不需要单独下载）**：**64,671 条带 bbox**，覆盖 **48 个类别**
  - 每图框数：均值 1.30，最大 19；49,740 张有框图中 5,927 张有 >1 框
  - 最多的类：rat (16,338)、rabbit (8,556)、petrel (6,999)、iguana (6,284)、**human (6,237)**
  - 注意 `petrel` 与 `petrel_chick` 是两个类；`eye_shine`（308 框）不是物种
- 许可：CDLA-Permissive-1.0

```bash
gsutil cp "gs://public-datasets-lila/islandconservationcameratraps/island_conservation_camera_traps_1.02.zip" ./ic_meta.zip
gsutil -m cp -r "gs://public-datasets-lila/islandconservationcameratraps/public" .
```

### 3.4 ✅ Oregon Critters

- 页面：<https://lila.science/datasets/oregon-critters>
- 图片：99,909，美国俄勒冈西部；46 个类别
- 页面原文："**Manually drawn bounding boxes with class labels are provided for 91,045 images. The remaining 8,864 images are from camera false triggers and contain no animals.**"
- **框（实测）**：**93,150 条带 bbox**；**恰好 8,864 条不带 bbox** —— 与页面文字**精确吻合**，可信度极高
  - 91,045 张有框图中 1,739 张有 >1 框（均值 1.02，最大 8）
  - 最多的类：catharus species (11,652)、black-tailed deer (11,239)、townsend's chipmunk (8,988)、douglas squirrel (8,253)、roosevelt elk (7,127)
  - 类别混合了物种级与科/属级：`catharus species`、`leporidae family`、`small mammal`、`parulidae family`、`picidae family`、`troglodytidae family`、`neotoma species`、`weasel family`、`reptile or amphibian`、`invertebrate`、`other bird` —— **不是纯物种级**
- 许可：CDLA-Permissive-1.0；元数据内含 train/val/test 划分

```bash
gsutil cp "gs://public-datasets-lila/oregon-critters/oregon_critters.zip" ./oregon_critters_meta.zip
gsutil -m cp -r "gs://public-datasets-lila/oregon-critters" .
```
（或用 Azure：`https://lilawildlife.blob.core.windows.net/lila-wildlife/oregon-critters/oregon_critters.zip`）

### 3.5 ✅ Nkhotakota Camera Traps

- 页面：<https://lila.science/datasets/nkhotakota-camera-traps>
- 图片：321,562（实测 324,635），马拉维 Nkhotakota 野生动物保护区
- 类别数：46（混合物种级与高阶类群）
- 页面原文："a subset of images (**33,813**) also have manually drawn bounding box annotations"
- **框（实测）**：**49,057 条带 bbox**，覆盖 **39 个类别**，分布在 **40,743 张图**上
  - 每图框数：均值 1.20，最大 17；5,693 张有 >1 框
  - 最多的类：yellow_baboon (12,712)、cape_bushbuck (7,282)、small_antelope (5,550)、bushpig (4,959)、african_savanna_elephant (3,629)
  - 注意：实测"有框图片数 40,743" > 页面所称 33,813 张；页面的 33,813 可能指人工绘制子集，实测值为准
  - 类别含 `small_antelope`、`genet`、`mongoose`、`galago` 等**非物种级**标签
- 许可：CDLA-Permissive-1.0；用于训练 YOLOv4 多类检测器，含 train/val/test 划分

```bash
gsutil cp "gs://public-datasets-lila/nkhotakota-camera-traps/nkhotakota_camera_traps.json.zip" ./nk_meta.zip
gsutil -m cp -r "gs://public-datasets-lila/nkhotakota-camera-traps" .
```

### 3.6 ✅ Caltech Camera Traps (CCT)

- 页面：<https://lila.science/datasets/caltech-camera-traps>
- 图片：243,100，140 个点位，美国西南部；21 个动物类 + empty
- 页面原文："approximately **66,000** bounding box annotations"
- **框（实测）**：`caltech_bboxes_20200316.json` → 63,025 张图、**65,112 条全部带 bbox**，覆盖 ~19 个动物类 + car + empty
  - 每图框数：均值 1.05，最大 9；61,945 张有框图中 2,446 张有 >1 框
  - 最多的类：opossum (12,134)、raccoon (7,908)、coyote (6,505)、deer (6,428)、rabbit (6,025)
- **⚠️ 重要限制（文件 `info.description` 原文）**：
  > "Bounding box annotations for 63,025 images from Caltech Camera Traps, **where the images only have one species label or are empty**. Contains all annotations for CCT-20, the 20-location dataset used in the ECCV18 paper 'Recognition in Terra Incognita,' as well as additional annotations collected by MS AI for Earth."
  - 即：**多物种图片被排除在框数据集之外**。这是"为规避多物种歧义而牺牲覆盖率"的典型做法——框只在 63,025 / 243,100 ≈ 26% 的图上。
  - 实测印证：该文件中**非空图片 100.00% 只有 1 个类别**。
- 另有纯标签文件 `caltech_camera_traps.json.zip`（243,100 图，**0 框**）
- 许可：CDLA-Permissive-1.0

```bash
gsutil cp "gs://public-datasets-lila/caltechcameratraps/labels/caltech_bboxes_20200316.json" ./cct_bboxes.json
gsutil cp "gs://public-datasets-lila/caltechcameratraps/labels/caltech_camera_traps.json.zip" ./cct_labels.zip
gsutil -m cp -r "gs://public-datasets-lila/caltech-unzipped/cct_images" .
```

### 3.7 ✅ ENA24-detection

- 页面：<https://lila.science/datasets/ena24detection>
- 图片：约 10,000（实测 9,676），23 个类别，北美东部
- 页面原文："**with bounding boxes on each image**"
- **框（实测）**：
  - `ena24.json`（完整版）：9,676 张图、**11,596 条全带 bbox**，23 类
  - `ena24_public.json`（去人类图片版，官方称 99.9999999% 准确）：8,789 张图、**9,772 条全带 bbox**，22 类
  - 最多的类：American Crow (1,278)、American Black Bear (959)、Chicken (732)、Dog (726)、Virginia Opossum (725)
  - 每图框数：均值 1.11，最大 6；756 张有 >1 框
  - **106 张图（1.21%）有 2 个不同物种框**（如 American Crow + Eastern Cottontail 68 张）
- 许可：CDLA-Permissive-1.0

```bash
gsutil cp "gs://public-datasets-lila/ena24/ena24_public.json" ./ena24_public.json
gsutil cp "gs://public-datasets-lila/ena24/ena24.json" ./ena24.json
gsutil -m cp -r "gs://public-datasets-lila/ena24/images" .
```

### 3.8 ✅ Missouri Camera Traps

- 页面：<https://lila.science/datasets/missouricameratraps>
- 图片：约 25,000（实测 24,673），20 个物种
- 页面原文："Around **900 bounding boxes** are included."
- **框（实测）**：**956 条带 bbox**，覆盖 **20 个物种类**，分布在 947 张图上
  - 最多的类：agouti (87)、collared_peccary (82)、red_deer (68)、red_brocket_deer (63)、ocelot (63)
  - 每图框数：均值 1.01，**最大 2**；仅 9 张图有 >1 框
- **⚠️ 页面明确的数据缺陷**：
  > "bounding boxes are accurate, but for images that have multiple individuals in them, **a bounding box is present for only one**. For these images, we have added a non-standard field to the .json file ('**n_boxes**') which indicates the number of animals that actually exist in the image"
  - 涉及 79 张图，名单在 <http://lila.science/wp-content/uploads/2019/05/mct_images_with_redundant_boxes.txt>
- 许可：CDLA-Permissive-1.0

```bash
gsutil cp "gs://public-datasets-lila/missouricameratraps/missouri_camera_traps_set1_1.21.json.zip" ./mct_meta.zip
gsutil -m cp -r "gs://public-datasets-lila/missouricameratraps/images" .
```

### 3.9 ⚠️ Channel Islands Camera Traps —— 有框，但只是"类"级，且有坑

- 页面：<https://lila.science/datasets/channel-islands-camera-traps>
- 图片：246,529（实测 245,529），73 个点位，加州海峡群岛
- 类别数：**仅 7** —— `empty, rodent, fox, bird, human, skunk, other`
- 页面原文："**All animals are annotated with bounding boxes.**"
- **框（实测）**：245,529 张图、264,321 条 annotation，其中 **264,266 条带 bbox**
  - 但 **114,894 条是 `empty` 类的"整帧框"**！实测这些框的中位尺寸就是整幅图像（w=2047, h=1535；样例 `[0,0,1919,1079]`）——**空图被塞了一个覆盖全图的占位框**。
  - 因此**真正的语义框 ≈ 149,372 个**（rodent 82,912 + fox 48,150 + bird 11,099 + human 5,981 + skunk 1,071 + other 159）
  - 另有 55 条 annotation 连 bbox 都没有
  - 每图框数：均值 1.08，最大 14；14,808 张有 >1 框
- **判断**：`fox` 大概是岛屿灰狐（物种），但 `rodent`（啮齿目）、`bird`（鸟纲）、`skunk`、`other` 都是**目/纲级**，严格说**不是物种级框**，是"粗类级框"。而且它把 `human` 也当一类框。
- 许可：CDLA-Permissive-1.0

```bash
gsutil cp "gs://public-datasets-lila/channel-islands-camera-traps/channel-islands-camera-traps.json.zip" ./ci_meta.zip
gsutil -m cp -r "gs://public-datasets-lila/channel-islands-camera-traps/images" .
```

### 3.10 ❌ NACTI —— 有框，但是纯 MegaDetector 三类（重要纠错）

- 页面：<https://lila.science/datasets/nacti>
- 图片：3.7M（实测元数据 3,382,215），5 个地点，美国
- 类别数：**59**（实测）。注意页面写"28 animal categories"，但 LILA 元数据里实际有 59 类，且**是拉丁学名**：`alces alces, bos taurus, canis latrans, canis lupus, cervus canadensis, ... odocoileus virginianus, puma concolor, sus scrofa, ursus americanus, vehicle, empty` 等——**图片级标签确实是物种级**。
- 页面原文："We have also added bounding box annotations to **8892 images (mostly vehicles and birds)**." ← **这句话极具误导性**
- **框（实测）**：`nacti_20230920_bboxes.json`（已解压为 4.5MB JSON）
  - 8,892 张图、10,564 条 annotation，**全部带 bbox**
  - **类别只有 4 个（+empty）**：`vehicle` (5,652)、`animal` (3,660)、`person` (1,251)、`group` (1)
  - **`animal` 是单一泛类，没有任何物种信息**。所谓"birds"只是因为这些框恰好落在鸟图上，框本身的标签仍是 `animal`。
  - 每图框数：均值 1.28，最大 23；8,263 张有框图中 1,528 张有 >1 框
  - 主元数据文件 `nacti_metadata.1.14.json`：**0 框**
- **判断**：NACTI 是"**图片级物种标签 + MegaDetector 式 3 类框**"的教科书案例 → 合成候选（见第 5 节）
- 许可：CDLA-Permissive-1.0

```bash
gsutil cp "gs://public-datasets-lila/nacti/nacti_20230920_bboxes.zip" ./nacti_bboxes.zip
gsutil cp "gs://public-datasets-lila/nacti/nacti_metadata.1.14.json.zip" ./nacti_meta.zip
gsutil -m cp -r "gs://public-datasets-lila/nacti-unzipped" .   # 约 1.4TB
```

### 3.11 ❌ Snapshot Serengeti —— 有框，但也是纯 MegaDetector 三类（重要纠错）

- 页面：<https://lila.science/datasets/snapshot-serengeti>
- 图片：约 2.65M 序列 / 7.1M 张，S1–S11；61 个类别（物种级）
- 页面原文："We have also added approximately **150,000 bounding box annotations to approximately 78,000 of those images**."
- **框（实测）**：`SnapshotSerengetiBboxes_20190903.json`
  - 82,938 张图、**146,359 条全部带 bbox**
  - **类别只有 3 个（+empty）**：`animal` (145,380)、`person` (544)、`group` (435)
  - **完全没有物种信息**
  - 每图框数：均值 1.88，**最大 71**；78,029 张有框图中 **24,905 张有 >1 框**（这是所有数据集里"多目标"最严重的）
  - 文件 `info.description` 原文提到只覆盖 S1–S6，且"with remaining small insect and distant bird bboxes smaller than 400 sq pixels"被移除——即**小目标框被主动删掉了**，做小动物检测要注意
- 主元数据 `SnapshotSerengetiS01.json`（实测）：411,414 张图、61 类、**0 框**
- 覆盖极低：82,938 / 7.1M ≈ **1.2%** 的图片有框
- **判断**：典型的"图片级物种标签 + MD 式 3 类框" → 合成候选（见第 5 节）
- 许可：CDLA-Permissive-1.0。注意人去图，但元数据保留 human 标签

```bash
gsutil cp "gs://public-datasets-lila/snapshotserengeti-v-2-0/SnapshotSerengetiBboxes_20190903.json.zip" ./ss_bboxes.zip
gsutil cp "gs://public-datasets-lila/snapshotserengeti-v-2-0/SnapshotSerengetiS01.json.zip" ./ss_s01.zip
gsutil -m cp -r "gs://public-datasets-lila/snapshotserengeti-unzipped" .   # 约 5TB
```

### 3.12 ❌ Idaho Camera Traps —— **一个框都没有**

- 页面：<https://lila.science/datasets/idaho-camera-traps>
- 图片：约 1.5M（实测 1,535,725），62 个类别
- 页面**从头到尾没有提 bounding box**
- **实测**：1,551,552 条 annotation，**`with_bbox = 0`** —— 确认为**纯图片/序列级分类标签**
  - 实测类别（前几）：empty (181,205)、deer (6,991)、elk (5,176)、snow on lens (2,626)、foggy lens (1,703)、human (1,445)…… 注意含 `snow on lens`、`foggy lens`、`malfunction` 等**状态类标签**
  - 页面明确："Annotations were assigned to image **sequences**, rather than individual images, so annotations are meaningful only at the **sequence level**."
- 许可：**非标准** —— 页面原文："No representations or warranties are made regarding the data… Images **may not be sold** in any format, but may be used for scientific publications. Please acknowledge the Idaho Department of Fish and Game."

```bash
gsutil cp "gs://public-datasets-lila/idaho-camera-traps/idaho-camera-traps.json.zip" ./idaho_meta.zip
gsutil -m cp -r "gs://public-datasets-lila/idaho-camera-traps/public" .
```

### 3.13 ❌ 其余"无框"数据集（实测 0 框）

| 数据集 | 实测图片数 | 类别数 | 实测 bbox 数 |
|---|---|---|---|
| Wellington Camera Traps | 270,450 | 17 | **0** |
| Ohio Small Animals | 118,554 | 46 | **0** |
| California Small Animals | 2,278,071 | 256 | **0** |
| Snapshot Safari 2024 Expansion | 4,029,374 | 131 | **0** |
| NZ Trail Cameras | 2,453,840 | 98 | **0** |
| WSU Lynx | 1,205,453 | 26 | **0** |
| AMMonitor Camera Traps | 1,858,251 | 77 | **0** |
| Felidae Conservation Fund | 357,934 | 66 | **0** |
| Orinoquía Camera Traps | 112,221 | 58 | **0** |
| Desert Lion Conservation | 63,468 | 46 | **0** |
| Duck Pictures in Wetlands | 675,948 | 11 | **0** |
| Snapshot Karoo / Kgalagadi / Kruger | 38,293 / 10,357 / 10,604 | 38 / 31 / 46 | **0** |

全部许可为 CDLA-Permissive-1.0，除 California Small Animals 为 **CC-BY 4.0**。下载命令统一为 `gsutil -m cp -r "gs://public-datasets-lila/<slug>" .`。

**易被误认有框的**：California Small Animals 由 Wildlife Insights 标注、256 个类、2.28M 图，看起来很像检测数据集，**但实测 0 框**。Ohio / California 这类"小型动物相机（small animal cameras）"数据集全部只有图片级标签。

### 3.14 非相机陷阱但有物种级框的 LILA 数据集（旁证）

这些**不是**红外相机数据，但如果你的任务需要物种级框，它们质量更高（来自 LILA 数据集索引页 <https://lila.science/datasets> 的原文）：

- **Izembek Lagoon Waterfowl**：9,267 张航拍图，**521,270 个框**，每个框标注为 Brant (424,790) / Canada goose (47,561) / … → **物种级**
- **Leopard ID 2022 / Hyena ID 2022**：单物种（非洲豹 / 斑鬣狗），"with bounding boxes and individual animal identifications"
- **Great Zebra and Giraffe Count and ID**：斑马与长颈鹿，带框 + 个体 ID
- **Whale Shark ID**：鲸鲨，带框 + 个体 ID
- **Big Bird**：4,284 张无人机图，49,990 条标注（**41,337 个框 + 8,653 个多边形**）
- **NOAA Arctic Seals 2019**：约 8 万张航空/热成像，约 **28,000 个框**
- **NOAA Puget Sound Nearshore Fish 2017-2018**：67,990 个目标标注 / 30,384 张图
- **Community Fish Detection Dataset**：>190 万图 / **>935,000 个框**
- **Aerial Seabirds West Africa**、**Boxes on Bees and Pollen**、**WNI Giraffes** 等

---

## 4. 官方"哪些数据集有框"的列表 —— 找到了什么？

你要求的"现成官方列表"，我找到 4 个相关资源，**但结论是：没有一个完整、准确的官方列表**。

1. **`lila_camera_trap_datasets.csv`**（最接近官方列表）
   <https://lila.science/wp-content/uploads/2023/06/lila_camera_trap_datasets.csv>
   - 由 MegaDetector 结果页 <https://lila.science/megadetector-results-for-camera-trap-datasets/> 指向，含全部 32 个相机陷阱数据集
   - 列：`name, short_name, continent, country, region, image_base_url_*, **bbox_url_gcp/aws/azure**, metadata_url_*, mdv5a_results_raw, mdv5b_results_raw, md1000-redwood_results_raw, md_results_with_rde`
   - **`bbox_url_*` 列只对 5 个数据集有值**：CCT、NACTI、WCS、Snapshot Serengeti、SWG
   - **⚠️ 两个严重缺陷**：
     a. **它只登记"独立 bbox 文件"**。Channel Islands、Island Conservation、Nkhotakota、Oregon Critters、Missouri、ENA24 这 6 个**框在主元数据里的数据集，该列为空**——按这个 CSV 筛选会漏掉一半有框的数据集。
     b. **已过时**：WCS 那行给的是旧文件 `wcs_20200403_bboxes.json.zip`，而 WCS 页面现在提供的是 `wcs_20220205_bboxes_with_classes.zip`。
2. **MegaDetector 结果页**：<https://lila.science/megadetector-results-for-camera-trap-datasets/>
   - 列出所有数据集的 MD 检测结果下载链接（**这些是 3 类框，不是物种框**）
   - 另提供 `mdv5_lila_boxes.zip`："there are >1.1M animal boxes … representing most of what's on LILA and was also used for MDv5 training"
3. **⚠️ 但这个"全 LILA 框大合集"也是纯 3 类**（**我实测确认**）：
   - `https://lila.science/public/md-splits/mdv5_lila_boxes.zip`（35MB 压缩 / 607MB 解压）
   - 1,094,605 张图、1,388,413 条 annotation、**1,161,636 个框**
   - **`categories` 只有 5 个：`empty, animal, person, vehicle, group`**
   - 用 `bbox_relative` 字段（归一化坐标），不是 `bbox`
   - **物种信息被刻意剥离了**，无法直接当物种级框用
4. **⚠️ 统一大表没有 bbox 列**（**我实测确认**，用 HTTP Range 只读取了 765MB zip 的头部）：
   - `https://lila.science/public/lila_image_urls_and_labels.csv.zip`（765MB）
   - 表头为：`dataset_name, url_gcp, url_aws, url_azure, image_id, sequence_id, location_id, frame_num, original_label, scientific_name, common_name, datetime, annotation_level, kingdom, … variety`
   - **没有任何 bbox / x / y / width / height 列**，纯图片级标签 + iNaturalist 分类学映射
   - 由 <https://lila.science/taxonomy-mapping-for-camera-trap-data-sets/> 页面指向，该页面明确说"Multiple rows may be present for the same image if **multiple species are present**"

**结论**：要找物种级框，**只能逐数据集下载其独立文件并检查 `categories`**。本报告第 2 节的表格就是我实测出来的这个列表。可复用的检测脚本在 `_lila_audit/sweep.py`。

---

## 5. 关键判断：能否用"MegaDetector 框 + 图片物种标签"合成物种级框？

**能，但只在特定条件下成立，而且要接受明确的精度损失。** 下面是量化评估。

### 5.1 候选数据集与实测风险指标

| 数据集 | 有 MD 式框的图片数 | 有 MD 式框图片中 >1 框占比 | **非空图片中"恰好 1 个类别"占比** | >1 物种图片占比 |
|---|---|---|---|---|
| **NACTI** | 8,263 | 1,528 / 8,263 = **18.5%** | **100.00%**（2,912,859 张） | **0%** |
| **Snapshot Serengeti** (S01) | 78,029（全库 82,938） | **24,905 / 78,029 = 31.9%** | 98.04%（71,151 / 72,577） | **1.96%** |
| （对照）WCS 物种框 | 243,513 | 23.1% | 99.21% | 0.79% |
| （对照）SWG 物种框 | 88,135 | 10.4% | 100.00% | 0% |
| （对照）Channel Islands | 245,474 | 6.0% | 99.91% | 0.09% |
| （对照）Island Conservation | 49,740 | 11.9% | 98.41% | 1.59% |
| （对照）ENA24 | 8,789 | 8.6% | 98.79% | 1.21% |
| （对照）Nkhotakota | 40,743 | 14.0% | 99.73% | 0.27% |
| （对照）Oregon Critters | 91,045 | 1.9% | 100.00% | 0% |

**合成公式**：`对每张图 i，取其图片级物种标签 L_i；对 i 上的每个 MegaDetector animal 框 b，输出 (b, L_i)`。

### 5.2 什么时候能成立（前提条件）

1. **该图只有一个物种标签**。NACTI 结构上 100% 满足（每图恰好一个 annotation），Serengeti 98.04% 满足。
2. **MegaDetector 在该图检出恰好 1 个 animal 框**。NACTI 有 18.5% 的有框图含 >1 框，Serengeti 高达 **31.9%**（最多一张图 71 个框）。含多框时，若确实是同种个体（如 5 只斑马），全部赋同一个种是对的；若是混群，就错。
3. **检测器召回完整**：MD 漏检的动物没有框 → 合成后这些个体彻底从训练集消失（不会产生错框，但会产生**系统性漏标**，训练检测器时会惩罚正确预测）。
4. **物种标签本身可靠**：LILA 明确警告多数数据集是**序列级标签**，单帧未必有动物。Serengeti 页面原文："annotations are tied to _images_, but are only reliable at the _sequence_ level… there are rare sequences in which two of three images contain a lion, but the third is empty… but all three images would be annotated as 'lion'."

### 5.3 会出错的具体情形（按严重度排序）

| # | 失效模式 | 后果 | 实测证据 |
|---|---|---|---|
| 1 | **一图多物种** | 把 A 物种的框标成 B 物种，**制造错误监督信号** | Serengeti S01: 1,426 张图 (1.96%) 有 >1 物种标签。高频混淆对：`gazellethomsons + otherbird` (210)、**`gazellegrants + gazellethomsons` (210)**、`gazellethomsons + zebra` (121)、`gazellethomsons + hartebeest` (78)。**两种瞪羚长得极像**，这类错标对细粒度分类器伤害最大 |
| 2 | **多个个体 / 混群** | 多个框被赋同一个种；混群时至少一部分错 | Serengeti 31.9% 的有框图含 >1 框，最多 **71 个框/图**；WCS 最多 26 个框/图 |
| 3 | **框是"整群"框** | 一个框套住一群动物，赋单一物种 | Serengeti 框文件里有独立的 **`group`** 类（435 个框）；NACTI 有 1 个；`mdv5_lila_boxes.zip` 里有 **1,904 个 `group` 框** |
| 4 | **序列级标签错配到帧** | 空帧/异种帧被赋予标签 | Idaho 页面："annotations are meaningful only at the _sequence_ level"；Serengeti、Snapshot Karoo、Wellington 页面均有同款警告 |
| 5 | **非动物框** | `person`/`vehicle` 框必须剔除 | NACTI bbox 文件里 **vehicle 5,652 个、person 1,251 个**，animal 只有 3,660 个 —— **65% 的框根本不是动物**！Serengeti：person 544 |
| 6 | **标签非物种级** | 合成出来的是"属/科/泛类框" | SWG：`unidentified_murid` (9,818)、`unidentified_bird`；Oregon Critters：`small mammal`、`leporidae family`；Nkhotakota：`small_antelope`；Island Conservation：`unknown`、`eye_shine`（反光不是动物！308 个框） |
| 7 | **类别命名不一致** | 同物异名造成伪类别 | SWG 里 `chinese_serow` 与 `chinese serow` 并存；NACTI 元数据用拉丁学名而 bbox 文件用英文泛类 |
| 8 | **覆盖率极低** | 训练集急剧缩小 | Serengeti 有框图 82,938 / 全库 7.1M ≈ **1.2%**；NACTI 8,892 / 3.38M ≈ **0.26%** |
| 9 | **小目标被删** | 合成集缺失小动物 | Serengeti bbox 文件 `info` 明确：小于 400 平方像素的昆虫/远鸟框**已被移除** |
| 10 | **占位框污染** | 把空图当有物体 | Channel Islands：**114,894 个 `empty` 类的整帧框**（如 `[0,0,1919,1079]`）。虽然该集框是类级不是 MD 级，但同类陷阱在其他数据集同样可能出现 |

### 5.4 实操建议

如果必须走合成路线：

1. **只保留图片级标签唯一的图**（Serengeti 丢掉 ~2%，NACTI 丢 0%）。
2. **只保留 MD 检出恰好 1 个 animal 框的图**（Serengeti 会丢掉约 1/3；这些图可另作"弱标签多目标"集，或只用于分类不用于检测）。
3. **剔除 `person` / `vehicle` / `group` 框**（NACTI 尤其关键，65% 的框是车和人）。
4. **把类别映射到统一分类学**再做，"unknown_bird" 之类先合并或丢弃——用 LILA 现成的
   `lila-taxonomy-mapping_release.csv`（2MB，<https://lila.science/public/lila-taxonomy-mapping_release.csv>，列：`dataset_name, query, taxonomy_level, scientific_name, common_name, taxonomy_string, kingdom…variety`）。
5. **把 `sequence_level_annotation` 为 true 的标注降权或排除**。
6. **在报告里明确说明框是合成的**，不要与 CCT/WCS/SWG 那种人工框混为一谈——后者是人工绘制并核对过单个个体的。

**更好的替代方案**：直接训练/微调一个**多类检测器**，用第 2 节那 8 个真有物种框的数据集（合计约 **75.9 万个**物种级框，以 WCS 374,270 + SWG 101,659 + Oregon 93,150 + CCT 65,112 + Island Conservation 64,671 + Nkhotakota 49,057 + ENA24 9,772 + Missouri 956 计；若把 Channel Islands 的 149,372 个类级框也算上则约 **90.8 万个**），再用 MegaDetector 对无框数据集做**伪标签**——这比"图片标签灌进 MD 框"噪声更可控，因为你可以用置信度阈值和人工复核筛伪标签。

---

## 6. 最终结论

### LILA 上真正有物种级框的相机陷阱数据集（实测确认，共 8 个）

1. **WCS Camera Traps** —— 374,270 框 / 611 类 ← 最大
2. **SWG Camera Traps** —— 101,659 框 / 99 类
3. **Oregon Critters** —— 93,150 框 / 45 类
4. **Caltech Camera Traps** —— 65,112 框 / ~18 类（**仅单物种图**）
5. **Island Conservation** —— 64,671 框 / 48 类
6. **Nkhotakota** —— 49,057 框 / 39 类
7. **ENA24-detection** —— 9,772 框 / 22 类
8. **Missouri Camera Traps** —— 956 框 / 20 类（**每图仅 1 个框**）

另有 **1 个类级（非物种级）**：**Channel Islands** —— 149,372 个有效框，类别为 rodent / fox / bird / skunk / other。

**合计约 75.9 万个物种级框**（8 个数据集之和）；若把 Channel Islands 的类级框也计入，约 **90.8 万个**。

### 明确没有物种级框的

- **只有 MegaDetector 三类框**：**NACTI**（animal/person/vehicle/group，且 65% 是车和人）、**Snapshot Serengeti**（animal/person/group）。这两个是"有框但无物种"的代表。
- **完全没有框**：Idaho、Wellington、Ohio Small Animals、California Small Animals、Snapshot Safari 2024 Expansion、NZ Trail Cameras、WSU Lynx、AMMonitor、Felidae、Orinoquía、Desert Lion、Duck Pictures in Wetlands、Snapshot Karoo/Kgalagadi/Kruger、UNSW Predators、Seattle(ish)、Biome Health Maasai Mara（后三者为页面无提及，未下载元数据实测）。
- **全库聚合资源也没有物种框**：`mdv5_lila_boxes.zip`（1,161,636 框）是纯 3 类；统一大表 `lila_image_urls_and_labels.csv` 根本没有 bbox 列。

### "合成框"这条路可行吗？

**有条件可行，但只在 NACTI 和 Snapshot Serengeti 上值得做，且必须以牺牲覆盖率和接受细粒度错标为代价。**

- **NACTI**：图片标签 100% 唯一 + 物种级（59 个拉丁学名类），结构上最适合合成；但必须剔除 65% 的非动物框，且最终只剩 3,660 个 animal 框 / 8,892 张图（占全库 0.26%）——**合成出来的框数量极少**，性价比一般。
- **Snapshot Serengeti**：31.9% 的图有多个框、1.96% 的图有多个物种、最多 71 框/图，还有 `group` 整群框与小目标被删的问题；严格筛选后能用的比例不高。且两种瞪羚（Grant's / Thomson's）高频共现，正是最容易标错的组合。
- **不建议**用这种方式去"补" Idaho / California Small Animals 这类 0 框大数据集——它们连 MD 框都要自己跑 MegaDetector 生成，此时不如直接把图片级标签当**弱监督分类标签**用，或者用 MegaDetector 出框后走**伪标签 + 人工抽检**流程。

**一句话**：想要干净的物种级框，就用第 6 节那 9 个数据集（尤其 WCS、SWG、Oregon Critters）；想要覆盖 LILA 全库，就只能接受"MegaDetector 框 + 图片级标签"的弱监督，并明确标注其合成属性与误差来源。

---

## 附录：本次审计的原始证据

| 文件 | 说明 |
|---|---|
| `_lila_audit/inspect_cct.py` | 解析单个 CCT JSON/zip，输出类别分布与框的类别归属 |
| `_lila_audit/sweep.py` | 批量扫描，对每个文件给出 `NO BBOXES AT ALL` / `MD 3-class only` / `CLASS-SPECIES BBOXES` 判定 |
| `_lila_audit/analyze_multi.py` | 统计每图类别数、每图框数（评估合成风险） |
| `_lila_audit/peek_zip_header.py` | 用 HTTP Range + deflate 部分解压，读取 765MB 远程 zip 的表头 |
| `_lila_audit/dl/` | 本次下载并解析的全部元数据文件（约 30 个） |
| `_lila_audit/lila_camera_trap_datasets.csv` | LILA 官方数据集索引 CSV（含 `bbox_url_*` 列） |