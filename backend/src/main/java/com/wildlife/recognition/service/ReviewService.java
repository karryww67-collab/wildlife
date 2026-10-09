package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.entity.ReviewRecord;
import com.wildlife.recognition.entity.User;
import com.wildlife.recognition.repository.DetectionResultRepository;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.ReviewRecordRepository;
import com.wildlife.recognition.repository.UserRepository;
import com.wildlife.recognition.websocket.RecognitionWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工复核服务。
 *
 * 红外相机图像里物种细粒度分类很难做到 100% 准确，低置信度结果需要人来把关。
 * 复核产出两条信息：结果的最终状态（落回 detection_result）与可追溯的复核记录（review_record），
 * 后者同时是后续模型迭代的标注来源。
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    /** 复核动作。 */
    private static final String ACTION_CONFIRM = "CONFIRM";
    private static final String ACTION_CORRECT = "CORRECT";
    private static final String ACTION_REJECT = "REJECT";

    private final DetectionResultRepository resultRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final ImageRepository imageRepository;
    private final UserRepository userRepository;
    private final ImageAvailabilityService imageAvailability;

    public ReviewService(DetectionResultRepository resultRepository,
                         ReviewRecordRepository reviewRecordRepository,
                         ImageRepository imageRepository,
                         UserRepository userRepository,
                         ImageAvailabilityService imageAvailability) {
        this.resultRepository = resultRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.imageRepository = imageRepository;
        this.userRepository = userRepository;
        this.imageAvailability = imageAvailability;
    }

    // ── 待复核 ──────────────────────────────────────────────────────────────

    /**
     * 待复核列表。
     *
     * 使用 MyBatis-Plus selectPage()，
     * 由 MySQL 直接完成 LIMIT/OFFSET。
     *
     * <p>每条结果都会带上 {@code imageAvailable}：原图物理文件没了的结果
     * 在页面上看不到图，也就没有判据，前端据此禁用「复核」。
     *
     * @param hideMissing true = 直接从队列里排除"原图已丢失"的项。
     *                    这一步在 SQL 里完成（{@code image_id NOT IN (...)}），
     *                    所以 LIMIT/OFFSET 分页仍然正确 —— 不会出现"取 20 条筛掉 15 条"的页码错位。
     */
    public Map<String, Object> listPending(
            Long taskId,
            Double maxConfidence,
            int page,
            int size,
            boolean hideMissing) {
        QueryWrapper<DetectionResult> wrapper =
                new QueryWrapper<DetectionResult>()
                        .eq(
                                "review_status",
                                "PENDING"
                        );

        if (taskId != null) {
            /*
             * 不再：
             *
             *   recognition_image -> 查出所有 imageId
             *   -> Java List
             *   -> IN (...)
             *
             * 而是让 MySQL 自己完成关联筛选。
             */
            wrapper.inSql(
                    "image_id",
                    "SELECT id " +
                            "FROM recognition_image " +
                            "WHERE task_id = " + taskId
            );
        }

        if (maxConfidence != null) {
            wrapper.le(
                    "confidence",
                    maxConfidence
            );
        }

        Set<Long> missingImages = imageAvailability.missingImageIds();
        if (hideMissing && !missingImages.isEmpty()) {
            wrapper.notIn("image_id", missingImages);
        }

        wrapper.orderByAsc("confidence")
                .orderByAsc("id");

        long current = Math.max(page, 1);
        long pageSize = Math.max(size, 1);
        pageSize = Math.min(pageSize, 200);

        Page<DetectionResult> pageRequest =
                new Page<>(
                        current,
                        pageSize
                );
        Page<DetectionResult> pageResult =
                resultRepository.selectPage(
                        pageRequest,
                        wrapper
                );

        // 只对当前页（≤200 条）打可用性标记，不额外查文件
        for (DetectionResult record : pageResult.getRecords()) {
            record.setImageAvailable(!missingImages.contains(record.getImageId()));
        }

        Map<String, Object> result =
                new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        result.put("pages", pageResult.getPages());
        result.put("list", pageResult.getRecords());
        return result;
    }

    // ── 提交复核 ────────────────────────────────────────────────────────────

    /**
     * 复核单条结果。
     *
     * @param resultId        识别结果 ID
     * @param action          CONFIRM 确认 / CORRECT 修正 / REJECT 误检剔除
     * @param correctedClass  修正后的物种名（action=CORRECT 时必填）
     * @param remark          备注
     * @param username        复核人账号
     */
    public DetectionResult review(Long resultId, String action, String correctedClass,
                                  String remark, String username) {
        DetectionResult result = resultRepository.selectById(resultId);
        if (result == null) {
            return null;
        }

        /*
         * 原图丢了就不能复核。
         *
         * 前端已经按 imageAvailable 禁用了按钮，但接口是公开契约，不能只靠 UI 兜底：
         * 看不到图 = 没有判据，把这种结果标成"已确认 / 已修正"会直接污染
         * 复核率、修正率这些指标，也会让后续模型迭代拿到假的标注。
         */
        if (!imageAvailability.isAvailable(result.getImageId())) {
            throw new IllegalArgumentException(
                    "该结果所属原图已从磁盘丢失，无法核对，不能提交复核（resultId=" + resultId + "）");
        }

        String normalized = normalizeAction(action);
        String originalClass = result.getClassName();

        switch (normalized) {
            case ACTION_CONFIRM -> result.setReviewStatus("CONFIRMED");
            case ACTION_REJECT -> result.setReviewStatus("REJECTED");
            case ACTION_CORRECT -> {
                if (!StringUtils.hasText(correctedClass)) {
                    throw new IllegalArgumentException("修正物种时 correctedClass 不能为空");
                }
                result.setClassName(correctedClass);
                result.setReviewStatus("CORRECTED");
            }
            default -> throw new IllegalArgumentException("不支持的复核动作: " + action);
        }

        resultRepository.updateById(result);

        ReviewRecord record = new ReviewRecord();
        record.setResultId(resultId);
        record.setReviewerId(resolveUserId(username));
        record.setOriginalClass(originalClass);
        record.setCorrectedClass(ACTION_CORRECT.equals(normalized) ? correctedClass : originalClass);
        record.setOriginalConfidence(result.getConfidence());
        record.setReviewStatus(result.getReviewStatus());
        record.setRemark(remark);
        record.setReviewTime(LocalDateTime.now());
        reviewRecordRepository.insert(record);

        // 推送审核状态，前端复核工作台/大屏可实时刷新
        RecognitionWebSocket.sendReviewStatus(
                resultId,
                result.getImageId() == null ? 0L : result.getImageId(),
                originalClass,
                record.getCorrectedClass(),
                result.getReviewStatus(),
                username);

        log.info("复核完成: resultId={}, action={}, by={}", resultId, normalized, username);
        return result;
    }

    /**
     * 批量复核：同一动作、同一修正物种。
     *
     * <p>原图已丢失的项**跳过而不是整批失败**：批量选择是页面行为，
     * 队列里混进一条脏数据不该让另外 19 条一起提交不了。跳过条数会回给前端提示，
     * 用户知道"我选了 20 条，实际生效 18 条，2 条原图丢了"。
     */
    public BatchReviewResult reviewBatch(List<Long> resultIds, String action, String correctedClass,
                                         String remark, String username) {
        if (resultIds == null || resultIds.isEmpty()) {
            throw new IllegalArgumentException("resultIds 不能为空");
        }
        String normalized = normalizeAction(action);
        if (ACTION_CORRECT.equals(normalized) && !StringUtils.hasText(correctedClass)) {
            throw new IllegalArgumentException("批量修正时 correctedClass 不能为空");
        }

        int reviewed = 0;
        int skippedMissing = 0;
        for (Long resultId : resultIds) {
            DetectionResult result = resultRepository.selectById(resultId);
            if (result == null) {
                continue;
            }
            // 这里比 review() 多查一次是刻意的：要能把"原图丢失"和"其它失败"分开计数
            if (!imageAvailability.isAvailable(result.getImageId())) {
                skippedMissing++;
                continue;
            }
            if (review(resultId, normalized, correctedClass, remark, username) != null) {
                reviewed++;
            }
        }

        if (skippedMissing > 0) {
            log.warn("批量复核跳过 {} 条原图已丢失的结果: {}", skippedMissing, resultIds);
        }
        return new BatchReviewResult(reviewed, skippedMissing);
    }

    /** 批量复核结果：{@code reviewed} 实际生效条数，{@code skippedMissing} 因原图丢失被跳过的条数。 */
    public record BatchReviewResult(int reviewed, int skippedMissing) {
    }

    private String normalizeAction(String action) {
        if (!StringUtils.hasText(action)) {
            throw new IllegalArgumentException("action 不能为空");
        }
        String upper = action.trim().toUpperCase();
        if (!ACTION_CONFIRM.equals(upper) && !ACTION_CORRECT.equals(upper) && !ACTION_REJECT.equals(upper)) {
            throw new IllegalArgumentException("action 只能为 CONFIRM / CORRECT / REJECT");
        }
        return upper;
    }

    private Long resolveUserId(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        User user = userRepository.selectOne(new QueryWrapper<User>().eq("username", username).last("LIMIT 1"));
        return user == null ? null : user.getId();
    }

    // ── 复核记录 ────────────────────────────────────────────────────────────

    /**
     * 复核记录列表。
     *
     * 保持原有接口返回 List，避免影响现有前端。
     * 但底层已经改成真正的数据库分页。
     */
    public List<ReviewRecord> listRecords(
            Long taskId,
            int page,
            int size) {
        QueryWrapper<ReviewRecord> wrapper =
                new QueryWrapper<>();

        if (taskId == null) {
            wrapper.orderByDesc(
                    "review_time"
            );
        } else {
            /*
             * 复核记录
             *   -> detection_result
             *   -> recognition_image
             *   -> task_id
             *
             * 使用数据库子查询，不把任务下的全部 ID
             * 加载到 Java 内存。
             */
            wrapper.inSql(
                    "result_id",
                    "SELECT id " +
                            "FROM detection_result " +
                            "WHERE image_id IN (" +
                            "SELECT id " +
                            "FROM recognition_image " +
                            "WHERE task_id = " + taskId +
                            ")"
            );
            wrapper.orderByDesc(
                    "review_time"
            );
        }

        long current = Math.max(page, 1);
        long pageSize = Math.max(size, 1);
        pageSize = Math.min(pageSize, 200);

        Page<ReviewRecord> pageRequest =
                new Page<>(
                        current,
                        pageSize
                );
        Page<ReviewRecord> pageResult =
                reviewRecordRepository.selectPage(
                        pageRequest,
                        wrapper
                );

        return pageResult.getRecords();
    }

    /** 某条结果的复核历史。 */
    public List<ReviewRecord> listRecordsByResult(Long resultId) {
        if (resultId == null) {
            return List.of();
        }
        return reviewRecordRepository.selectList(
                new QueryWrapper<ReviewRecord>().eq("result_id", resultId).orderByDesc("review_time"));
    }

    // ── 复核统计 ────────────────────────────────────────────────────────────

    /** 复核率、修正率、误检率，以及被修正最多的物种。 */
    public Map<String, Object> stats(Long taskId) {
        List<DetectionResult> results = resultsOfTask(taskId);
        long total = results.size();
        long reviewed = results.stream()
                .filter(r -> r.getReviewStatus() != null && !"PENDING".equals(r.getReviewStatus()))
                .count();
        long corrected = results.stream().filter(r -> "CORRECTED".equals(r.getReviewStatus())).count();
        long rejected = results.stream().filter(r -> "REJECTED".equals(r.getReviewStatus())).count();
        long confirmed = results.stream().filter(r -> "CONFIRMED".equals(r.getReviewStatus())).count();

        /*
         * 待复核里有多少项是"原图已丢失、根本没法复核"的。
         *
         * pending 保持全量口径（库里确实是这么多条待复核），另给一个 pendingMissing 供前端
         * 说明差额 —— 否则队列被过滤掉一部分后，用户会看到"统计说 2798、列表说 2000"而对不上账。
         */
        Set<Long> missingImages = imageAvailability.missingImageIds();
        long pendingMissing = results.stream()
                .filter(r -> r.getReviewStatus() == null || "PENDING".equals(r.getReviewStatus()))
                .filter(r -> missingImages.contains(r.getImageId()))
                .count();

        // 各原始物种被修正的次数 —— 用来判断模型在哪些物种上最容易认错
        Map<String, Integer> correctedByClass = new LinkedHashMap<>();
        List<Long> resultIds = results.stream().map(DetectionResult::getId).toList();
        if (!resultIds.isEmpty()) {
            List<ReviewRecord> records = reviewRecordRepository.selectList(
                    new QueryWrapper<ReviewRecord>().in("result_id", resultIds).eq("review_status", "CORRECTED"));
            for (ReviewRecord record : records) {
                String key = StringUtils.hasText(record.getOriginalClass()) ? record.getOriginalClass() : "未知";
                correctedByClass.merge(key, 1, Integer::sum);
            }
        }

        List<Map<String, Object>> topCorrected = new ArrayList<>();
        correctedByClass.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed())
                .limit(10)
                .forEach(e -> topCorrected.add(Map.of("className", e.getKey(), "correctedCount", e.getValue())));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("reviewed", reviewed);
        stats.put("pending", total - reviewed);
        stats.put("pendingMissing", pendingMissing);
        stats.put("confirmed", confirmed);
        stats.put("corrected", corrected);
        stats.put("rejected", rejected);
        /*
         * 复核率的分母要扣掉"原图已丢失"的待复核项。
         *
         * 这些项看不到原图、review() 也会拒绝，永远不可能离开 PENDING。若仍算进分母，
         * 复核率会被永久压低并且**封顶** —— 本机 808 条里 798 条属于此类，
         * 封顶只有 10/808 = 1.24%，这个数字对外没有解释力。
         *
         * 扣掉之后它才回答一个有意义的问题："能复核的里面复核了多少"。
         * pendingMissing 只统计 PENDING 且原图丢失的，是 total 的子集，
         * 所以 reviewable >= reviewed 恒成立，不会出现超过 100% 的比值。
         */
        long reviewable = total - pendingMissing;
        stats.put("reviewable", reviewable);
        stats.put("reviewRate", reviewable <= 0 ? 0.0 : round(reviewed * 100.0 / reviewable));
        stats.put("correctRate", reviewed == 0 ? 0.0 : round(corrected * 100.0 / reviewed));
        stats.put("rejectRate", reviewed == 0 ? 0.0 : round(rejected * 100.0 / reviewed));
        stats.put("topCorrectedClasses", topCorrected);
        return stats;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private List<Long> imageIdsOfTask(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        return imageRepository.selectList(new QueryWrapper<RecognitionImage>().eq("task_id", taskId))
                .stream().map(RecognitionImage::getId).toList();
    }

    private List<Long> resultIdsOfTask(Long taskId) {
        List<Long> imageIds = imageIdsOfTask(taskId);
        if (imageIds.isEmpty()) {
            return List.of();
        }
        return resultRepository.selectList(new QueryWrapper<DetectionResult>().in("image_id", imageIds))
                .stream().map(DetectionResult::getId).toList();
    }

    private List<DetectionResult> resultsOfTask(Long taskId) {
        if (taskId == null) {
            return resultRepository.selectList(null);
        }
        List<Long> imageIds = imageIdsOfTask(taskId);
        if (imageIds.isEmpty()) {
            return List.of();
        }
        return resultRepository.selectList(new QueryWrapper<DetectionResult>().in("image_id", imageIds));
    }
}
