package com.wildlife.recognition.controller;

import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.ReviewRecord;
import com.wildlife.recognition.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 人工复核。
 *
 * AI 识别不可能 100% 准确，尤其红外相机图像的物种细粒度分类。
 * 本模块负责把低置信度结果捞出来给人看，人工确认或修正后再入库，
 * 复核结果同时作为后续模型迭代的训练依据。
 *
 * <p>路径口径（与前端 {@code api/index.ts} 的契约一致）：
 * <pre>
 * GET  /api/reviews/pending  待复核队列（按置信度升序，最不确定的排前）
 * POST /api/reviews/{id}     提交单条复核
 * </pre>
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 待复核列表。
     * 默认按置信度升序（最不确定的排前面），可指定置信度上限，只拉出低置信结果。
     *
     * <p>返回的每条结果都带 {@code imageAvailable}：原图物理文件已丢失（悬空引用）时该值为 false，
     * 页面上取图会 404、也就没有判据，前端据此禁用「复核」。
     *
     * @param hideMissing true 时把"原图已丢失"的项从队列里排除（在 SQL 里过滤，分页仍然正确）
     */
    @GetMapping("/pending")
    public ResponseEntity<?> pending(@RequestParam(required = false) Long taskId,
                                     @RequestParam(required = false) Double maxConfidence,
                                     @RequestParam(defaultValue = "false") boolean hideMissing,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(reviewService.listPending(taskId, maxConfidence, page, size, hideMissing));
    }

    /**
     * 提交单条复核。
     * 请求体：{"action":"CONFIRM","remark":"确认无误"}
     * 修正类别时：{"action":"CORRECT","correctedClass":"小麂","remark":"实为小麂"}
     * action 取值：CONFIRM 确认 / CORRECT 修正 / REJECT 误检剔除
     */
    @PostMapping("/{id}")
    public ResponseEntity<?> review(@PathVariable Long id,
                                    @RequestBody Map<String, Object> body,
                                    @RequestAttribute(value = "username", required = false) String username) {
        String action = body.get("action") == null ? null : String.valueOf(body.get("action"));
        if (action == null || action.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "action 不能为空"));
        }

        // correctedClass：修正后的类别（物种）名，action=CORRECT 时必填
        String correctedClass = body.get("correctedClass") == null
                ? null : String.valueOf(body.get("correctedClass"));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));

        try {
            DetectionResult result = reviewService.review(id, action, correctedClass, remark, username);
            if (result == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 批量复核（同一动作、同一修正类别）。
     * 请求体：{"resultIds":[1,2,3],"action":"CONFIRM","remark":"批量确认"}
     *
     * <p>返回 {@code {"reviewed":N,"skippedMissing":M}}：M 是因原图已丢失被跳过的条数
     * （批量选择是页面行为，混进一条脏数据不该让整批失败）。
     */
    @PostMapping("/batch")
    public ResponseEntity<?> reviewBatch(@RequestBody Map<String, Object> body,
                                         @RequestAttribute(value = "username", required = false) String username) {
        Object raw = body.get("resultIds");
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "resultIds 不能为空"));
        }

        List<Long> resultIds;
        try {
            resultIds = ((List<?>) raw).stream()
                    .map(item -> Long.valueOf(String.valueOf(item)))
                    .toList();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "resultIds 必须为结果 ID 数组"));
        }

        String action = body.get("action") == null ? null : String.valueOf(body.get("action"));
        String correctedClass = body.get("correctedClass") == null
                ? null : String.valueOf(body.get("correctedClass"));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));

        try {
            ReviewService.BatchReviewResult result =
                    reviewService.reviewBatch(resultIds, action, correctedClass, remark, username);
            return ResponseEntity.ok(Map.of(
                    "reviewed", result.reviewed(),
                    "skippedMissing", result.skippedMissing()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 复核记录列表（可按任务筛选）。 */
    @GetMapping("/records")
    public ResponseEntity<List<ReviewRecord>> records(@RequestParam(required = false) Long taskId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(reviewService.listRecords(taskId, page, size));
    }

    /** 某条识别结果的复核历史。 */
    @GetMapping("/records/{resultId}")
    public ResponseEntity<List<ReviewRecord>> recordsOfResult(@PathVariable Long resultId) {
        return ResponseEntity.ok(reviewService.listRecordsByResult(resultId));
    }

    /** 复核统计：复核率、修正率、误检率，以及各类别被修正的 top 列表。 */
    @GetMapping("/stats")
    public ResponseEntity<?> stats(@RequestParam(required = false) Long taskId) {
        return ResponseEntity.ok(reviewService.stats(taskId));
    }
}
