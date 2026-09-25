package com.wildlife.recognition.controller;

import com.wildlife.recognition.service.StatisticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 统计分析。
 *
 * 面向大屏与论文图表，把识别结果聚合成可直接绘图的形态：
 * 总量概览、类别分布、保护等级分布、时间趋势、置信度分布、任务状态。
 *
 * <p>路径口径（与前端 {@code api/index.ts} 的契约一致）：
 * <pre>
 * GET /api/statistics/classes  类别（物种）分布，返回 {total, speciesCount, list[{name,value,ratio,protectionLevel}]}
 * GET /api/statistics/trends   识别量趋势，返回 {granularity, range, list[{time,value}]}
 * </pre>
 */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    /** 总览：图像总数、任务总数、识别目标数、物种数、重点保护物种数、平均置信度。 */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(@RequestParam(required = false) String range) {
        return ResponseEntity.ok(statisticsService.overview(range));
    }

    /**
     * 类别（物种）分布：各类别的检出次数与占比，按次数倒序取前 top 个。
     * range 口径：all / today / 7d / 30d / 90d / 1y。
     */
    @GetMapping("/classes")
    public ResponseEntity<?> classes(@RequestParam(required = false) Long taskId,
                                     @RequestParam(required = false) String range,
                                     @RequestParam(defaultValue = "10") int top) {
        return ResponseEntity.ok(statisticsService.speciesDistribution(taskId, range, top));
    }

    /**
     * 识别量趋势：按小时 / 天聚合，按小时可看出动物活动节律。
     * granularity 口径：hour / day。
     */
    @GetMapping("/trends")
    public ResponseEntity<?> trends(@RequestParam(defaultValue = "day") String granularity,
                                    @RequestParam(required = false) String range) {
        return ResponseEntity.ok(statisticsService.trend(granularity, range));
    }

    /** 保护等级分布：国家一级 / 二级 / 三有 / 其他，以及 IUCN 等级分布。 */
    @GetMapping("/protection-distribution")
    public ResponseEntity<?> protectionDistribution(@RequestParam(required = false) String range) {
        return ResponseEntity.ok(statisticsService.protectionDistribution(range));
    }

    /** 置信度分布：按区间统计，用于评估模型可靠性。 */
    @GetMapping("/confidence-distribution")
    public ResponseEntity<?> confidenceDistribution(@RequestParam(required = false) Long taskId) {
        return ResponseEntity.ok(statisticsService.confidenceDistribution(taskId));
    }

    /** 任务状态统计：排队 / 进行中 / 已完成 / 失败 / 已取消。 */
    @GetMapping("/task-status")
    public ResponseEntity<?> taskStatus() {
        return ResponseEntity.ok(statisticsService.taskStatus());
    }
}
