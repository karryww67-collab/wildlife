package com.wildlife.recognition.controller;

import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.service.RecognitionResultService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 识别结果管理。
 *
 * 每条结果 = 某张图像上检出的一个目标（类别 + 置信度 + 边界框）。
 * 结果由 AI 引擎回写，复核状态流转：PENDING（待复核）→ CONFIRMED / CORRECTED / REJECTED。
 *
 * <p>路径口径（与前端 {@code api/index.ts} 的契约一致）：
 * <pre>
 * GET    /api/results            结果分页查询
 * GET    /api/results/{imageId}  某张图像上的全部检出（叠框展示）
 * </pre>
 */
@RestController
@RequestMapping("/api/results")
public class ResultController {

    private final RecognitionResultService resultService;

    public ResultController(RecognitionResultService resultService) {
        this.resultService = resultService;
    }

    /**
     * 结果分页查询。
     * 支持按任务、图像、类别名、最低置信度、复核状态、检出时间区间筛选。
     *
     * <p>startTime / endTime 取 ISO 日期时间（如 {@code 2026-09-24T00:00}，
     * 即前端 {@code <input type="datetime-local">} 的取值格式），闭区间，两端均可缺省。
     * 格式非法时 Spring 会直接返回 400，而不是静默忽略筛选条件。
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Long taskId,
                                  @RequestParam(required = false) Long imageId,
                                  @RequestParam(required = false) String className,
                                  @RequestParam(required = false) Double minConfidence,
                                  @RequestParam(required = false) String reviewStatus,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                resultService.list(taskId, imageId, className, minConfidence, reviewStatus,
                        startTime, endTime, page, size));
    }

    /**
     * 某张图像的全部检出（按置信度倒序），用于图像详情页叠框展示。
     *
     * 注意：这里 {@code {imageId}} 是**图像 ID**，不是结果 ID —— 与前端契约
     * {@code GET /api/results/{imageId}} 一致。
     */
    @GetMapping("/{imageId}")
    public ResponseEntity<List<DetectionResult>> byImage(@PathVariable Long imageId) {
        return ResponseEntity.ok(resultService.listByImage(imageId));
    }

    /** 类别（物种）名列表，供结果页筛选下拉；usedOnly=true 时只返回实际检出过的。 */
    @GetMapping("/classes")
    public ResponseEntity<List<String>> classes(@RequestParam(defaultValue = "true") boolean usedOnly) {
        return ResponseEntity.ok(resultService.listClasses(usedOnly));
    }

    /** 按筛选条件导出 CSV，供论文／报表使用。筛选口径与分页查询完全一致。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long taskId,
                                         @RequestParam(required = false) String className,
                                         @RequestParam(required = false) Double minConfidence,
                                         @RequestParam(required = false) String reviewStatus,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        String csv = resultService.exportCsv(taskId, className, minConfidence, reviewStatus,
                startTime, endTime);
        // 带 UTF-8 BOM，Excel 直接打开不乱码
        byte[] payload = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recognition_results.csv\"")
                .body(payload);
    }

    /** 删除单条结果。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        boolean removed = resultService.delete(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "识别结果已删除"));
    }
}
