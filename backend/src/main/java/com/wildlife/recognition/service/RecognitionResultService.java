package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.ModelVersion;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.repository.DetectionResultRepository;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.ModelVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 识别结果服务。
 *
 * 每条结果 = 某张图像上检出的一个目标（物种 + 置信度 + 边界框）。
 * 结果由 AI 引擎批量回写，之后进入人工复核环节。
 */
@Service
public class RecognitionResultService {

    private static final Logger log = LoggerFactory.getLogger(RecognitionResultService.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 单条批量 INSERT 最多携带的结果行数。
     *
     * 一张图通常只有几个检测框，正常不会触发分批；
     * 这个上限只用于防御"单图框数异常大"时拼出超长 SQL（受 max_allowed_packet 限制）。
     */
    private static final int RESULT_INSERT_BATCH_SIZE = 500;

    private final DetectionResultRepository resultRepository;
    private final ImageRepository imageRepository;
    private final ModelVersionRepository modelVersionRepository;

    public RecognitionResultService(DetectionResultRepository resultRepository,
                                    ImageRepository imageRepository,
                                    ModelVersionRepository modelVersionRepository) {
        this.resultRepository = resultRepository;
        this.imageRepository = imageRepository;
        this.modelVersionRepository = modelVersionRepository;
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    /**
     * 分页查询结果。
     * taskId 为任务维度筛选：先定位该任务下的图像，再取这些图像的结果。
     * startTime / endTime 按检出时间（detection_result.create_time）闭区间筛选，可只传一端。
     */
    public Map<String, Object> list(Long taskId, Long imageId, String className,
                                    Double minConfidence, String reviewStatus,
                                    LocalDateTime startTime, LocalDateTime endTime,
                                    int page, int size) {
        QueryWrapper<DetectionResult> wrapper =
                buildWrapper(taskId, imageId, className, minConfidence, reviewStatus, startTime, endTime);
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return pageOf(wrapper, page, size);
    }

    /**
     * 构造识别结果查询条件。
     *
     * taskId 筛选使用数据库子查询：
     *
     *   detection_result.image_id IN (
     *       SELECT id
     *       FROM recognition_image
     *       WHERE task_id = ?
     *   )
     *
     * 避免先把一个任务下的数万/十万 imageId
     * 全部加载到 Java 内存。
     */
    private QueryWrapper<DetectionResult> buildWrapper(
            Long taskId,
            Long imageId,
            String className,
            Double minConfidence,
            String reviewStatus,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        QueryWrapper<DetectionResult> wrapper =
                new QueryWrapper<>();

        if (imageId != null) {
            wrapper.eq(
                    "image_id",
                    imageId
            );
        } else if (taskId != null) {
            wrapper.inSql(
                    "image_id",
                    "SELECT id " +
                            "FROM recognition_image " +
                            "WHERE task_id = " + taskId
            );
        }

        if (StringUtils.hasText(className)) {
            wrapper.eq(
                    "class_name",
                    className
            );
        }
        if (minConfidence != null) {
            wrapper.ge(
                    "confidence",
                    minConfidence
            );
        }
        if (StringUtils.hasText(reviewStatus)) {
            wrapper.eq(
                    "review_status",
                    reviewStatus
            );
        }
        // 检出时间闭区间。两端都可单独缺省，方便"只要某天之后"或"只要某天之前"。
        if (startTime != null) {
            wrapper.ge("create_time", startTime);
        }
        if (endTime != null) {
            wrapper.le("create_time", endTime);
        }
        return wrapper;
    }

    public DetectionResult getById(Long id) {
        return id == null ? null : resultRepository.selectById(id);
    }

    /** 某个任务的全部结果。 */
    public List<DetectionResult> listByTask(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        List<Long> imageIds = imageIdsOfTask(taskId);
        if (imageIds.isEmpty()) {
            return List.of();
        }
        return resultRepository.selectList(
                new QueryWrapper<DetectionResult>().in("image_id", imageIds).orderByAsc("image_id").orderByDesc("confidence"));
    }

    /** 某张图像的全部结果（按置信度倒序，用于图像详情叠框）。 */
    public List<DetectionResult> listByImage(Long imageId) {
        if (imageId == null) {
            return List.of();
        }
        return resultRepository.selectList(
                new QueryWrapper<DetectionResult>().eq("image_id", imageId).orderByDesc("confidence"));
    }

    public List<DetectionResult> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return resultRepository.selectList(new QueryWrapper<DetectionResult>().in("id", ids));
    }

    public List<DetectionResult> listAll() {
        return resultRepository.selectList(null);
    }

    /**
     * 去重后的类别（物种）名列表。
     *
     * <p>usedOnly=true  —— 只返回库内**实际检出过**的类别；
     * <p>usedOnly=false —— 在此基础上并入**当前启用模型 class_config 里声明、
     * 但尚未检出过**的类别。人工复核需要完整清单：把误检改成"模型支持但本轮
     * 没检出"的物种时，下拉框里必须能选到它。
     *
     * <p>原实现是 {@code listAll()} 把整张结果表读进内存再 distinct ——
     * 既完全忽略了 usedOnly 参数，又会在数据量上去后直接吃内存
     * （10 万张图对应几十万条结果）。现改为数据库端 DISTINCT。
     */
    public List<String> listClasses(boolean usedOnly) {
        Set<String> classes = new LinkedHashSet<>(detectedClassNames());
        if (!usedOnly) {
            classes.addAll(configuredClassNames());
        }
        List<String> list = new ArrayList<>(classes);
        Collections.sort(list);
        return list;
    }

    /** 库内实际检出过的类别：交给数据库 DISTINCT + ORDER BY，不把整表拉进 JVM。 */
    private List<String> detectedClassNames() {
        return resultRepository.selectObjs(new QueryWrapper<DetectionResult>()
                        .select("DISTINCT class_name")
                        .isNotNull("class_name")
                        .ne("class_name", "")
                        .orderByAsc("class_name"))
                .stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 当前启用模型声明的类别清单（model_version.class_config）。
     *
     * <p>class_config 有三种历史写法（与前端 parseClassConfig 保持一致）：
     * JSON 数组 {@code ["deer","tiger"]}、逗号分隔、换行分隔。这里统一用
     * 宽松拆分处理，不引 Jackson —— 值为纯类别名，不需要真正的 JSON 解析。
     */
    private List<String> configuredClassNames() {
        ModelVersion active = modelVersionRepository.selectOne(new QueryWrapper<ModelVersion>()
                .eq("status", "ENABLED")
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (active == null || !StringUtils.hasText(active.getClassConfig())) {
            return List.of();
        }
        return Arrays.stream(active.getClassConfig().replaceAll("[\\[\\]{}\"]", " ")
                        .split("[,，;；\\n\\r]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<Long> imageIdsOfTask(Long taskId) {
        return imageRepository
                .selectList(new QueryWrapper<RecognitionImage>().eq("task_id", taskId))
                .stream()
                .map(RecognitionImage::getId)
                .toList();
    }

    /**
     * 识别结果真正的数据库分页。
     */
    private Map<String, Object> pageOf(
            QueryWrapper<DetectionResult> wrapper,
            int page,
            int size) {
        long current = Math.max(page, 1);
        long pageSize = Math.max(size, 1);
        // 防止前端一次请求过多数据。
        pageSize = Math.min(pageSize, 200);

        Page<DetectionResult> pageRequest =
                new Page<>(current, pageSize);
        Page<DetectionResult> pageResult =
                resultRepository.selectPage(
                        pageRequest,
                        wrapper
                );

        Map<String, Object> result =
                new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        result.put("pages", pageResult.getPages());
        result.put("list", pageResult.getRecords());
        return result;
    }

    // ── 写入（AI 引擎回写）──────────────────────────────────────────────────

    public DetectionResult save(DetectionResult result) {
        resultRepository.insert(result);
        return result;
    }

    /**
     * 批量写入某张图像的识别结果（先清掉该图旧结果，保证重试不重复）。
     *
     * 改前：for 循环里逐条 {@code insert} → N 条 INSERT（名字叫 batch，实际是单条循环）。
     * 改后：一条 INSERT 多行 VALUES → 1 条 INSERT（见 {@code DetectionResultRepository#insertBatchSomeColumn}）。
     * 因此单张图的写语句从 {@code 1 DELETE + N INSERT} 变成 {@code 1 DELETE + 1 INSERT}。
     */
    public int saveBatch(Long imageId, Long modelId, List<DetectionResult> results) {
        if (imageId == null) {
            return 0;
        }
        resultRepository.delete(new QueryWrapper<DetectionResult>().eq("image_id", imageId));

        if (results == null || results.isEmpty()) {
            return 0;
        }
        // 先把每行补齐（这段必须留在批插之前：批插 SQL 是同步拼装的，循环里再改对象也无效）
        for (DetectionResult result : results) {
            result.setImageId(imageId);
            if (result.getModelId() == null) {
                result.setModelId(modelId);
            }
            if (result.getReviewStatus() == null) {
                result.setReviewStatus("PENDING");
            }
        }

        int saved = 0;
        for (int start = 0; start < results.size(); start += RESULT_INSERT_BATCH_SIZE) {
            int end = Math.min(start + RESULT_INSERT_BATCH_SIZE, results.size());
            saved += resultRepository.insertBatchSomeColumn(results.subList(start, end));
        }
        log.debug("回写识别结果: imageId={}, count={}", imageId, saved);
        return saved;
    }

    // ── 复核状态联动 ────────────────────────────────────────────────────────

    /** 更新单条结果的复核状态（复核服务调用）。 */
    public DetectionResult updateReviewStatus(Long id, String reviewStatus) {
        DetectionResult result = resultRepository.selectById(id);
        if (result == null) {
            return null;
        }
        result.setReviewStatus(reviewStatus);
        resultRepository.updateById(result);
        return result;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    public boolean delete(Long id) {
        if (id == null || resultRepository.selectById(id) == null) {
            return false;
        }
        resultRepository.deleteById(id);
        return true;
    }

    public int deleteByImage(Long imageId) {
        if (imageId == null) {
            return 0;
        }
        return resultRepository.delete(new QueryWrapper<DetectionResult>().eq("image_id", imageId));
    }

    public int deleteByImages(List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            return 0;
        }
        return resultRepository.delete(new QueryWrapper<DetectionResult>().in("image_id", imageIds));
    }

    public int deleteByTask(Long taskId) {
        List<Long> imageIds = imageIdsOfTask(taskId);
        return deleteByImages(imageIds);
    }

    // ── 导出 ────────────────────────────────────────────────────────────────

    /** 按筛选条件导出 CSV 文本（不含 BOM，由控制器补）。 */
    public String exportCsv(Long taskId, String className, Double minConfidence, String reviewStatus,
                            LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<DetectionResult> wrapper =
                buildWrapper(taskId, null, className, minConfidence, reviewStatus, startTime, endTime);
        wrapper.orderByAsc("image_id").orderByDesc("confidence");
        List<DetectionResult> results = resultRepository.selectList(wrapper);

        Map<Long, RecognitionImage> imageMap = new LinkedHashMap<>();
        if (!results.isEmpty()) {
            List<Long> imageIds = results.stream().map(DetectionResult::getImageId).distinct().toList();
            for (RecognitionImage image : imageRepository.selectBatchIds(imageIds)) {
                imageMap.put(image.getId(), image);
            }
        }

        StringBuilder csv = new StringBuilder();
        csv.append("结果ID,图像ID,文件名,物种,置信度,检测框(x1,y1,x2,y2),复核状态,识别时间\n");
        for (DetectionResult result : results) {
            RecognitionImage image = imageMap.get(result.getImageId());
            csv.append(result.getId()).append(',')
                    .append(result.getImageId()).append(',')
                    .append(escape(image == null ? "" : image.getFileName())).append(',')
                    .append(escape(result.getClassName())).append(',')
                    .append(result.getConfidence() == null ? "" : String.format("%.4f", result.getConfidence())).append(',')
                    .append('"').append(result.getX1()).append(',').append(result.getY1()).append(',')
                    .append(result.getX2()).append(',').append(result.getY2()).append('"').append(',')
                    .append(escape(result.getReviewStatus())).append(',')
                    .append(result.getCreateTime() == null ? "" : result.getCreateTime().format(TIME_FORMAT))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
