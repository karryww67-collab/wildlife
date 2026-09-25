package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.entity.RecognitionTask;
import com.wildlife.recognition.repository.DetectionResultRepository;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    }

    private final ImageRepository imageRepository;
    private final DetectionResultRepository resultRepository;
    private final TaskRepository taskRepository;

    public StatisticsService(ImageRepository imageRepository,
                             DetectionResultRepository resultRepository,
                             TaskRepository taskRepository) {
        this.imageRepository = imageRepository;
        this.resultRepository = resultRepository;
        this.taskRepository = taskRepository;
    }

    // ── 总览 ────────────────────────────────────────────────────────────────

    /** 大屏顶部指标：图像数、任务数、检出目标数、物种数、重点保护物种数、平均置信度。 */
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

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("imageCount", images.size());
        overview.put("taskCount", taskRepository.selectList(null).size());
        overview.put("resultCount", results.size());
        overview.put("speciesCount", speciesCount);
        overview.put("protectedSpeciesCount", protectedSpeciesCount);
        overview.put("pendingReviewCount", pendingReview);
        overview.put("avgConfidence", confidenceCount == 0 ? 0.0 : round(confidenceSum / confidenceCount, 4));
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

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private List<DetectionResult> resultsOfTask(Long taskId) {
        if (taskId == null) {
            return resultRepository.selectList(null);
        }
        List<Long> imageIds = imageRepository
                .selectList(new QueryWrapper<RecognitionImage>().eq("task_id", taskId))
                .stream().map(RecognitionImage::getId).toList();
        if (imageIds.isEmpty()) {
            return List.of();
        }
        return resultRepository.selectList(new QueryWrapper<DetectionResult>().in("image_id", imageIds));
    }

    private List<DetectionResult> filterResultsByRange(List<DetectionResult> results, String range) {
        LocalDateTime[] window = resolveRange(range);
        if (window == null) {
            return results;
        }
        return results.stream()
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

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
