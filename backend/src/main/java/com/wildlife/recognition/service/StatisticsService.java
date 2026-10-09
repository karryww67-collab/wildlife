package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.ModelVersion;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.entity.RecognitionTask;
import com.wildlife.recognition.repository.DetectionResultRepository;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.ModelVersionRepository;
import com.wildlife.recognition.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 统计分析服务。
 *
 * 把识别结果聚合成可以直接绘图的形态，供大屏与论文图表使用：
 * 总量概览、物种分布、保护等级分布、时间趋势、置信度分布、任务状态。
 */
@Service
public class StatisticsService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    /**
     * 物种 → {保护等级, IUCN 等级}。
     * 依据《国家重点保护野生动物名录》（2021）与 IUCN 红色名录整理，
     * 覆盖红外相机常见的兽类与鸟类；未收录的物种归入"其他"。
     */
    private static final Map<String, String[]> PROTECTION_DICT = new LinkedHashMap<>();

    static {
        // ── 国家一级 ──
        PROTECTION_DICT.put("大熊猫", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("东北虎", new String[]{"国家一级", "EN"});
        PROTECTION_DICT.put("华南虎", new String[]{"国家一级", "CR"});
        PROTECTION_DICT.put("豹", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("雪豹", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("云豹", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("川金丝猴", new String[]{"国家一级", "EN"});
        PROTECTION_DICT.put("滇金丝猴", new String[]{"国家一级", "EN"});
        PROTECTION_DICT.put("黔金丝猴", new String[]{"国家一级", "CR"});
        PROTECTION_DICT.put("羚牛", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("梅花鹿", new String[]{"国家一级", "LC"});
        PROTECTION_DICT.put("麋鹿", new String[]{"国家一级", "EW"});
        PROTECTION_DICT.put("藏羚", new String[]{"国家一级", "NT"});
        PROTECTION_DICT.put("朱鹮", new String[]{"国家一级", "EN"});
        PROTECTION_DICT.put("丹顶鹤", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("黑颈鹤", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("绿孔雀", new String[]{"国家一级", "EN"});
        PROTECTION_DICT.put("褐马鸡", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("中华秋沙鸭", new String[]{"国家一级", "EN"});
        PROTECTION_DICT.put("东方白鹳", new String[]{"国家一级", "EN"});
        PROTECTION_DICT.put("白颈长尾雉", new String[]{"国家一级", "VU"});
        PROTECTION_DICT.put("蟒蛇", new String[]{"国家一级", "VU"});

        // ── 国家二级 ──
        PROTECTION_DICT.put("猕猴", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("藏酋猴", new String[]{"国家二级", "NT"});
        PROTECTION_DICT.put("小熊猫", new String[]{"国家二级", "EN"});
        PROTECTION_DICT.put("黑熊", new String[]{"国家二级", "VU"});
        PROTECTION_DICT.put("棕熊", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("水獭", new String[]{"国家二级", "NT"});
        PROTECTION_DICT.put("黄喉貂", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("豹猫", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("猞猁", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("林麝", new String[]{"国家二级", "EN"});
        PROTECTION_DICT.put("马鹿", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("水鹿", new String[]{"国家二级", "VU"});
        PROTECTION_DICT.put("毛冠鹿", new String[]{"国家二级", "VU"});
        PROTECTION_DICT.put("中华鬣羚", new String[]{"国家二级", "VU"});
        PROTECTION_DICT.put("中华斑羚", new String[]{"国家二级", "NT"});
        PROTECTION_DICT.put("岩羊", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("白鹇", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("红腹角雉", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("白冠长尾雉", new String[]{"国家二级", "VU"});
        PROTECTION_DICT.put("鸳鸯", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("小天鹅", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("红隼", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("游隼", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("雕鸮", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("斑头鸺鹠", new String[]{"国家二级", "LC"});

        // ── 三有（有重要生态、科学、社会价值的陆生野生动物）──
        PROTECTION_DICT.put("野猪", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("猪獾", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("狗獾", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("果子狸", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("小麂", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("赤麂", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("狍", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("华南兔", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("草兔", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("豪猪", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("竹鼠", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("赤腹松鼠", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("雉鸡", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("珠颈斑鸠", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("山斑鸠", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("白鹭", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("苍鹭", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("绿头鸭", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("斑嘴鸭", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("赤麻鸭", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("王锦蛇", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("乌梢蛇", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("黑眉锦蛇", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("中华蟾蜍", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("黑斑蛙", new String[]{"三有", "LC"});

        // ── 当前部署模型 wildlife-v1.0（19 类）中此前未收录的 8 类 ──
        // 此前它们全部落到"其他"，保护等级分布图失真。
        PROTECTION_DICT.put("虎", new String[]{"国家一级", "EN"});
        PROTECTION_DICT.put("灰孔雀雉", new String[]{"国家一级", "LC"});
        PROTECTION_DICT.put("红原鸡", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("蟹獴", new String[]{"国家二级", "LC"});
        PROTECTION_DICT.put("麂", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("鼬獾", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("红颊松鼠", new String[]{"三有", "LC"});
        PROTECTION_DICT.put("帚尾豪猪", new String[]{"三有", "LC"});
    }

    private final ImageRepository imageRepository;
    private final DetectionResultRepository resultRepository;
    private final TaskRepository taskRepository;
    private final ModelVersionRepository modelVersionRepository;

    public StatisticsService(ImageRepository imageRepository,
                             DetectionResultRepository resultRepository,
                             TaskRepository taskRepository,
                             ModelVersionRepository modelVersionRepository) {
        this.imageRepository = imageRepository;
        this.resultRepository = resultRepository;
        this.taskRepository = taskRepository;
        this.modelVersionRepository = modelVersionRepository;
    }

    // ── 总览 ────────────────────────────────────────────────────────────────

    /**
     * 大屏顶部指标：图像数、任务数、检出目标数、物种数、重点保护物种数、平均置信度，
     * 以及<b>检出率</b>（有检出的图像 / 识别成功的图像）。
     */
    public Map<String, Object> overview(String range) {
        List<RecognitionImage> images = filterImagesByRange(imageRepository.selectList(null), range);
        List<DetectionResult> results = filterResultsByRange(resultRepository.selectList(null), range);

        long speciesCount = results.stream()
                .map(DetectionResult::getClassName)
                .filter(StringUtils::hasText)
                .distinct()
                .count();
        long protectedSpeciesCount = results.stream()
                .map(DetectionResult::getClassName)
                .filter(StringUtils::hasText)
                .filter(StatisticsService::isKeyProtected)
                .distinct()
                .count();
        long pendingReview = results.stream().filter(r -> "PENDING".equals(r.getReviewStatus())).count();

        double confidenceSum = 0.0;
        int confidenceCount = 0;
        for (DetectionResult result : results) {
            if (result.getConfidence() != null) {
                confidenceSum += result.getConfidence();
                confidenceCount++;
            }
        }

        // 分子分母都在已加载的数据上算 —— 与物种分布、保护等级分布共用同一批 results，
        // 因此"检出率"与"物种分布"的口径必然一致，不会出现两者对不上的情况。
        Set<Long> detectedImageIds = results.stream()
                .map(DetectionResult::getImageId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        long detectedImages = detectedImageIds.size();
        long successImages = scopedSuccessImages(images, detectedImageIds);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("imageCount", images.size());
        overview.put("taskCount", taskRepository.selectList(null).size());
        overview.put("resultCount", results.size());
        overview.put("speciesCount", speciesCount);
        overview.put("protectedSpeciesCount", protectedSpeciesCount);
        overview.put("pendingReviewCount", pendingReview);
        overview.put("avgConfidence", confidenceCount == 0 ? 0.0 : round(confidenceSum / confidenceCount, 4));
        overview.put("successImageCount", successImages);
        overview.put("detectedImageCount", detectedImages);
        overview.put("undetectedImageCount", Math.max(successImages - detectedImages, 0L));
        overview.put("detectionRate", detectionRateOf(detectedImages, successImages));
        return overview;
    }

    // ── 物种分布 ────────────────────────────────────────────────────────────

    /** 各物种检出次数与占比，按次数倒序取前 top 个。 */
    public Map<String, Object> speciesDistribution(Long taskId, String range, int top) {
        List<DetectionResult> results = filterResultsByRange(resultsOfTask(taskId), range);

        Map<String, Integer> counter = new LinkedHashMap<>();
        for (DetectionResult result : results) {
            String className = StringUtils.hasText(result.getClassName()) ? result.getClassName() : "未知";
            counter.merge(className, 1, Integer::sum);
        }

        int total = results.size();
        List<Map<String, Object>> list = new ArrayList<>();
        counter.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed())
                .limit(Math.max(top, 1))
                .forEach(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("value", e.getValue());
                    item.put("ratio", total == 0 ? 0.0 : round(e.getValue() * 100.0 / total, 2));
                    item.put("protectionLevel", protectionLevelOf(e.getKey()));
                    list.add(item);
                });

        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("total", total);
        distribution.put("speciesCount", counter.size());
        distribution.put("list", list);
        return distribution;
    }

    // ── 保护等级分布 ────────────────────────────────────────────────────────

    /** 国家一级 / 二级 / 三有 / 其他 的检出占比，以及 IUCN 等级分布。 */
    public Map<String, Object> protectionDistribution(String range) {
        List<DetectionResult> results = filterResultsByRange(resultRepository.selectList(null), range);

        Map<String, Integer> byProtection = new LinkedHashMap<>();
        Map<String, Integer> byIucn = new LinkedHashMap<>();
        for (DetectionResult result : results) {
            String className = result.getClassName();
            String protection = protectionLevelOf(className);
            byProtection.merge(protection, 1, Integer::sum);

            String[] profile = className == null ? null : PROTECTION_DICT.get(className);
            String iucn = profile == null ? "未评级" : profile[1];
            byIucn.merge(iucn, 1, Integer::sum);
        }

        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("byProtectionLevel", toNameValueList(byProtection));
        distribution.put("byIucn", toNameValueList(byIucn));
        return distribution;
    }

    // ── 时间趋势 ────────────────────────────────────────────────────────────

    /** 按小时 / 天聚合的识别量，可看出动物活动节律。 */
    public Map<String, Object> trend(String granularity, String range) {
        List<DetectionResult> results = filterResultsByRange(resultRepository.selectList(null), range);
        boolean hourly = "hour".equalsIgnoreCase(granularity);

        Map<String, Integer> counter = new LinkedHashMap<>();
        for (DetectionResult result : results) {
            if (result.getCreateTime() == null) {
                continue;
            }
            String key = result.getCreateTime().format(hourly ? HOUR_FORMAT : DAY_FORMAT);
            counter.merge(key, 1, Integer::sum);
        }

        List<Map<String, Object>> list = new ArrayList<>();
        counter.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> list.add(Map.of("time", e.getKey(), "value", e.getValue())));

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("granularity", hourly ? "hour" : "day");
        trend.put("range", range == null ? "all" : range);
        trend.put("list", list);
        return trend;
    }

    // ── 检出率趋势 ──────────────────────────────────────────────────────────

    /**
     * 检出率趋势：<b>有检出的图像数 / 识别成功的图像数</b>。
     *
     * <p>与 {@link #trend} 的区别正是本模块要补的那个缺口：
     * <ul>
     *   <li>{@code trend()} 数的是 <b>detection_result 的行数</b> —— 一张图检出 3 个目标就算 3。
     *       它衡量的是"系统吐出了多少个框"，线越高只说明框越多，<b>回答不了
     *       "这批图里有多少张确实拍到了动物"</b>；</li>
     *   <li>本方法数的是 <b>图像</b>（去重后的 image_id），这才是需求里写的"检出率"。</li>
     * </ul>
     *
     * <p>返回的 {@code list} 已合并两条序列的时间 key 并按时间升序 —— 某天若
     * "识别成功但一个目标都没检出"，该天仍会出现在序列里（{@code detectionRate=0}），
     * 而不是整个点消失；否则趋势图会把这种"零检出"的日子悄悄跳过，
     * 看起来像是从没上传过图。
     *
     * @param granularity {@code hour} / {@code day}
     * @param range       {@code all} / {@code today} / {@code 7d} / {@code 30d} / {@code 90d} / {@code 1y}
     * @return {@code {granularity, range, successImages, detectedImages, undetectedImages, detectionRate, list[]}}
     */
    public Map<String, Object> detectionRate(String granularity, String range) {
        boolean hourly = "hour".equalsIgnoreCase(granularity);

        List<RecognitionImage> images = filterImagesByRange(imageRepository.selectList(null), range);
        List<DetectionResult> results = filterResultsByRange(resultRepository.selectList(null), range);

        Set<Long> detectedImageIds = results.stream()
                .map(DetectionResult::getImageId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        long detectedImages = detectedImageIds.size();
        long successImages = scopedSuccessImages(images, detectedImageIds);

        Map<String, Long> successBySlot = successBySlot(images, detectedImageIds, hourly);
        Map<String, Long> detectedBySlot = detectedBySlot(results, hourly);

        Set<String> slots = new TreeSet<>();
        slots.addAll(successBySlot.keySet());
        slots.addAll(detectedBySlot.keySet());

        List<Map<String, Object>> list = new ArrayList<>();
        for (String slot : slots) {
            long success = successBySlot.getOrDefault(slot, 0L);
            long detected = detectedBySlot.getOrDefault(slot, 0L);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", slot);
            point.put("successImages", success);
            point.put("detectedImages", detected);
            point.put("undetectedImages", Math.max(success - detected, 0L));
            point.put("detectionRate", detectionRateOf(detected, success));
            list.add(point);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("granularity", hourly ? "hour" : "day");
        result.put("range", range == null ? "all" : range);
        result.put("successImages", successImages);
        result.put("detectedImages", detectedImages);
        result.put("undetectedImages", Math.max(successImages - detectedImages, 0L));
        result.put("detectionRate", detectionRateOf(detectedImages, successImages));
        result.put("list", list);
        return result;
    }

    // ── 置信度分布 ──────────────────────────────────────────────────────────

    /** 置信度分区间统计，用于评估模型可靠性。 */
    public Map<String, Object> confidenceDistribution(Long taskId) {
        List<DetectionResult> results = resultsOfTask(taskId);

        // 区间：<0.5 / 0.5-0.6 / 0.6-0.7 / 0.7-0.8 / 0.8-0.9 / ≥0.9
        String[] buckets = {"[0,0.5)", "[0.5,0.6)", "[0.6,0.7)", "[0.7,0.8)", "[0.8,0.9)", "[0.9,1.0]"};
        int[] counts = new int[buckets.length];

        for (DetectionResult result : results) {
            double c = result.getConfidence() == null ? 0.0 : result.getConfidence();
            int index;
            if (c < 0.5) {
                index = 0;
            } else if (c < 0.6) {
                index = 1;
            } else if (c < 0.7) {
                index = 2;
            } else if (c < 0.8) {
                index = 3;
            } else if (c < 0.9) {
                index = 4;
            } else {
                index = 5;
            }
            counts[index]++;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < buckets.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("range", buckets[i]);
            item.put("value", counts[i]);
            item.put("ratio", results.isEmpty() ? 0.0 : round(counts[i] * 100.0 / results.size(), 2));
            list.add(item);
        }

        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("total", results.size());
        distribution.put("list", list);
        return distribution;
    }

    // ── 任务状态 ────────────────────────────────────────────────────────────

    /** 任务状态统计：排队 / 进行中 / 已完成 / 失败 / 已取消。 */
    public Map<String, Object> taskStatus() {
        List<RecognitionTask> tasks = taskRepository.selectList(null);

        Map<String, Integer> counter = new LinkedHashMap<>();
        counter.put("PENDING", 0);
        counter.put("PROCESSING", 0);
        counter.put("COMPLETED", 0);
        counter.put("FAILED", 0);
        counter.put("CANCELED", 0);

        int totalImages = 0;
        int processedImages = 0;
        for (RecognitionTask task : tasks) {
            String status = task.getStatus() == null ? "PENDING" : task.getStatus();
            counter.merge(status, 1, Integer::sum);
            totalImages += task.getTotalCount() == null ? 0 : task.getTotalCount();
            processedImages += task.getProcessedCount() == null ? 0 : task.getProcessedCount();
        }

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("total", tasks.size());
        statistics.put("list", toNameValueList(counter));
        statistics.put("totalImages", totalImages);
        statistics.put("processedImages", processedImages);
        statistics.put("progress", totalImages == 0 ? 0.0 : round(processedImages * 100.0 / totalImages, 2));
        return statistics;
    }

    // ── 保护等级字典 ────────────────────────────────────────────────────────

    public static String protectionLevelOf(String className) {
        if (!StringUtils.hasText(className)) {
            return "其他";
        }
        String[] profile = PROTECTION_DICT.get(className);
        return profile == null ? "其他" : profile[0];
    }

    /** 是否为国家一级或二级重点保护物种。 */
    public static boolean isKeyProtected(String className) {
        String level = protectionLevelOf(className);
        return "国家一级".equals(level) || "国家二级".equals(level);
    }

    // ── 统计口径收窄：只统计当前启用模型能产出的类别 ──────────────────────────

    /** 类别清单的进程内缓存时长，避免每次统计都查库。 */
    private static final long CLASS_SCOPE_TTL_MS = 60_000L;

    private volatile Set<String> classScopeCache;
    private volatile long classScopeCachedAt;

    /**
     * 当前启用模型声明的类别名集合（读 {@code model_version.class_config}，当前为 19 类）。
     *
     * <p><b>为什么需要它</b>：库里留着建库早期用 COCO 预训练模型跑出来的历史结果
     * （{@code person} / {@code dog} / {@code elephant} / {@code zebra}，合计 756 行，
     * 占 detection_result 全表 93.6%），以及一批 class_id 整体偏移 3 位的记录。
     * 它们都不是当前部署的野生动物模型产出的，不剔除的话统计页头条会显示
     * "elephant 44.93%"，检出率会变成 99.03% 这种与真实模型无关的数字。
     *
     * <p>清单直接读库，所以换模型后统计口径自动跟随，不需要改代码。
     * 读不到（没有启用模型 / 解析失败 / 查库异常）就返回<b>空集</b>，
     * 调用方见此不做过滤 —— 宁可多显示，也不能把整页统计静默清空成 0，
     * 那看起来像数据丢了。
     */
    private Set<String> activeClassNames() {
        long now = System.currentTimeMillis();
        Set<String> cached = classScopeCache;
        if (cached != null && now - classScopeCachedAt < CLASS_SCOPE_TTL_MS) {
            return cached;
        }
        Set<String> names = new HashSet<>();
        try {
            ModelVersion active = modelVersionRepository.selectOne(new QueryWrapper<ModelVersion>()
                    .eq("status", "ENABLED").orderByDesc("id").last("LIMIT 1"));
            if (active != null && StringUtils.hasText(active.getClassConfig())) {
                // class_config 形如 ["野猪", "猕猴", ...]，简单按逗号切再剥掉括号与引号，
                // 不引 JSON 库 —— 这里只需要一份名字清单，容错比严格更重要。
                for (String token : active.getClassConfig().split(",")) {
                    String name = token.replace('[', ' ').replace(']', ' ').replace('"', ' ').trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        } catch (RuntimeException ex) {
            names.clear();
        }
        classScopeCache = names;
        classScopeCachedAt = now;
        return names;
    }

    /** 按当前模型的类别清单收窄结果集；清单为空时原样返回（见 activeClassNames 的说明）。 */
    private List<DetectionResult> scopedByActiveModel(List<DetectionResult> results) {
        if (results == null || results.isEmpty()) {
            return results == null ? List.of() : results;
        }
        Set<String> scope = activeClassNames();
        if (scope.isEmpty()) {
            return results;
        }
        return results.stream()
                .filter(r -> r.getClassName() != null && scope.contains(r.getClassName()))
                .toList();
    }

    /**
     * 检出率的<b>分母</b>：范围内状态为 SUCCESS 的图像，且其所属任务产出过
     * 当前模型类别的检出。
     *
     * <p>为什么分母也要收窄：413 张 SUCCESS 图里有 407 张是 COCO 时代跑的，
     * 当前模型从没见过它们。把它们算进分母，等于拿别的模型的成绩当自己的分母，
     * 检出率会被稀释成一个没有意义的混合数字。
     *
     * <p>只按"任务"收窄而不按"图像"收窄是有意的：同一个任务里的图是同一批数据、
     * 同一个模型跑的，只要这个任务产出过当前模型的类别，它下面那些
     * "识别成功但没检出"的图才是真正有价值的漏检样本，必须留在分母里。
     */
    private long scopedSuccessImages(List<RecognitionImage> images, Set<Long> detectedImageIds) {
        boolean narrowed = !activeClassNames().isEmpty() && !detectedImageIds.isEmpty();
        Set<Long> taskIds = new HashSet<>();
        if (narrowed) {
            for (RecognitionImage image : images) {
                if (detectedImageIds.contains(image.getId()) && image.getTaskId() != null) {
                    taskIds.add(image.getTaskId());
                }
            }
        }
        return images.stream()
                .filter(i -> ImageRepository.STATUS_SUCCESS.equals(i.getStatus()))
                .filter(i -> !narrowed || (i.getTaskId() != null && taskIds.contains(i.getTaskId())))
                .count();
    }

    /** 按天 / 小时分桶的"识别成功图像数"，口径与 {@link #scopedSuccessImages} 完全一致。 */
    private Map<String, Long> successBySlot(List<RecognitionImage> images, Set<Long> detectedImageIds, boolean hourly) {
        boolean narrowed = !activeClassNames().isEmpty() && !detectedImageIds.isEmpty();
        Set<Long> taskIds = new HashSet<>();
        if (narrowed) {
            for (RecognitionImage image : images) {
                if (detectedImageIds.contains(image.getId()) && image.getTaskId() != null) {
                    taskIds.add(image.getTaskId());
                }
            }
        }
        Map<String, Long> map = new LinkedHashMap<>();
        for (RecognitionImage image : images) {
            if (!ImageRepository.STATUS_SUCCESS.equals(image.getStatus()) || image.getCreateTime() == null) {
                continue;
            }
            if (narrowed && (image.getTaskId() == null || !taskIds.contains(image.getTaskId()))) {
                continue;
            }
            map.merge(image.getCreateTime().format(hourly ? HOUR_FORMAT : DAY_FORMAT), 1L, Long::sum);
        }
        return map;
    }

    /** 按天 / 小时分桶的"有检出图像数"（同一张图的多个框只算一次）。 */
    private Map<String, Long> detectedBySlot(List<DetectionResult> results, boolean hourly) {
        Map<String, Set<Long>> seen = new LinkedHashMap<>();
        for (DetectionResult result : results) {
            if (result.getImageId() == null || result.getCreateTime() == null) {
                continue;
            }
            seen.computeIfAbsent(result.getCreateTime().format(hourly ? HOUR_FORMAT : DAY_FORMAT),
                    key -> new HashSet<>()).add(result.getImageId());
        }
        Map<String, Long> map = new LinkedHashMap<>();
        seen.forEach((slot, ids) -> map.put(slot, (long) ids.size()));
        return map;
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private List<DetectionResult> resultsOfTask(Long taskId) {
        if (taskId == null) {
            return scopedByActiveModel(resultRepository.selectList(null));
        }
        List<Long> imageIds = imageRepository
                .selectList(new QueryWrapper<RecognitionImage>().eq("task_id", taskId))
                .stream().map(RecognitionImage::getId).toList();
        if (imageIds.isEmpty()) {
            return List.of();
        }
        return scopedByActiveModel(
                resultRepository.selectList(new QueryWrapper<DetectionResult>().in("image_id", imageIds)));
    }

    private List<DetectionResult> filterResultsByRange(List<DetectionResult> results, String range) {
        List<DetectionResult> scoped = scopedByActiveModel(results);
        LocalDateTime[] window = resolveRange(range);
        if (window == null) {
            return scoped;
        }
        return scoped.stream()
                .filter(r -> r.getCreateTime() != null
                        && !r.getCreateTime().isBefore(window[0])
                        && !r.getCreateTime().isAfter(window[1]))
                .toList();
    }

    private List<RecognitionImage> filterImagesByRange(List<RecognitionImage> images, String range) {
        LocalDateTime[] window = resolveRange(range);
        if (window == null) {
            return images;
        }
        return images.stream()
                .filter(i -> i.getCreateTime() != null
                        && !i.getCreateTime().isBefore(window[0])
                        && !i.getCreateTime().isAfter(window[1]))
                .toList();
    }

    /** 解析时间范围：today / 7d / 30d / 90d / 1y，空值表示不限。 */
    private LocalDateTime[] resolveRange(String range) {
        if (!StringUtils.hasText(range) || "all".equalsIgnoreCase(range.trim())) {
            return null;
        }
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = switch (range.trim().toLowerCase()) {
            case "today" -> LocalDate.now().atStartOfDay();
            case "7d" -> end.minusDays(7);
            case "30d" -> end.minusDays(30);
            case "90d" -> end.minusDays(90);
            case "1y" -> end.minusYears(1);
            default -> null;
        };
        return start == null ? null : new LocalDateTime[]{start, end};
    }

    private static List<Map<String, Object>> toNameValueList(Map<String, Integer> counter) {
        List<Map<String, Object>> list = new ArrayList<>();
        counter.forEach((name, value) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("value", value);
            list.add(item);
        });
        return list;
    }

    // ── 检出率内部工具 ──────────────────────────────────────────────────────

    /**
     * 检出率（百分比）= 有检出的图像 / 识别成功的图像。
     *
     * <p>分母为 0 时返回 0.0 而不是 NaN —— 空库或空时间窗不该在图上画成断线。
     */
    private static double detectionRateOf(long detectedImages, long successImages) {
        return successImages <= 0 ? 0.0 : round(detectedImages * 100.0 / successImages, 2);
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}