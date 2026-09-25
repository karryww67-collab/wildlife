package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.entity.RecognitionTask;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.TaskRepository;
import com.wildlife.recognition.websocket.RecognitionWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 识别结果回写通道（前后端解耦的核心）。
 *
 * AI 引擎逐张处理图像，每处理完一张就把结果推到 Redis 队列的尾部；
 * 本服务轮询队列，把结果落到 MySQL，推进任务计数，并通过 WebSocket 把进度推给前端。
 *
 * 消息格式（AI 引擎 → Redis，队列 {@value #RESULT_QUEUE_KEY}）：
 * <pre>
 * {
 *   "taskId": 10001,
 *   "imageId": 2048,
 *   "status": "SUCCESS",                       // SUCCESS / FAILED
 *   "errorMessage": "...",                     // status=FAILED 时才有
 *   "detections": [                            // status=SUCCESS 时才有
 *     {"classId": 3, "className": "野猪", "confidence": 0.93, "x1": 120, "y1": 80, "x2": 460, "y2": 390}
 *   ]
 * }
 * </pre>
 *
 * 推送给前端的进度消息：
 * <pre>
 * {"taskId":10001,"processedCount":38291,"totalCount":100000,"progress":38.29,"status":"PROCESSING"}
 * </pre>
 *
 * <p>四条关键约定：
 * <ul>
 *   <li><b>幂等</b>：一张图像只计一次数。收到 taskId=STARTED 时把该任务下待识别的
 *       图像批量置为 PROCESSING；之后每张图像的<b>第一条</b>结果才有处理权 ——
 *       这个判断不是"先查出来再比"，而是前置的一条条件 UPDATE
 *       （{@code WHERE id=? AND status NOT IN ('SUCCESS','FAILED')}），
 *       只有把图像从非终态推进到终态的那条消息才算抢到，受影响行数为 0 就直接丢弃。
 *       这样重试期间迟到的旧结果既不会重复计数，也不会在 saveBatch 里把人工复核结论冲掉，
 *       并发消费者同时处理同一张图时也只有一方能抢到（靠 UPDATE 行锁）。</li>
 *   <li><b>任务生命周期闸门</b>：图像状态保护 ≠ 任务状态保护。任务被取消（或已结束）后，
 *       队列里必然还有在途消息（AI 引擎没有中断通道、{@code wildlife:tasks} 是 List 无法按 taskId 撤回），
 *       所以判据必须带上任务状态 —— 上述条件 UPDATE 的 WHERE 里多一条跨表子查询
 *       {@code task_id IN (SELECT id FROM recognition_task WHERE status IN ('PENDING','PROCESSING'))}，
 *       把"图像守卫 + 任务守卫"压进<b>同一条原子 UPDATE</b>，不留 Java 侧 {@code if (CANCELED) return} 的 TOCTOU 窗口。
 *       效果：CANCELED 之后，迟到的逐张结果不认领图像、不写 detection_result、不推进计数；
 *       迟到的任务级 FAILED 也翻不动 CANCELED。取消语义是"硬取消"（见 {@code TaskService#cancelTask}）。</li>
 *   <li><b>计数原子化</b>：抢占成功的消息只推进一次任务计数，推进动作是数据库侧的
 *       一条条件 UPDATE（{@code TaskRepository#advanceCounters}）——
 *       {@code processed_count = processed_count + 1}、{@code progress} 由 SQL 派生、
 *       计数达标时在<b>同一条语句</b>里把任务翻成 COMPLETED 并补 finish_time。
 *       {@code WHERE status IN ('PENDING','PROCESSING')} 是状态守卫：终态任务
 *       （尤其刚被 {@code cancelTask} 取消的）不会再被迟到结果改写计数。
 *       这同时消掉了原先"整行 RMW 回写"的两个问题 —— 与 cancelTask 互相覆盖、每图重写 9–10 列。</li>
 *   <li><b>收尾</b>：任务级 FAILED 时把还没出结果的图像一并置为 FAILED，
 *       不让任何图像永远停在 WAITING/PROCESSING。任务级状态本身也只写
 *       status + finish_time（{@code TaskRepository#markFailedIfActive}），带状态守卫。</li>
 * </ul>
 */
@Service
@EnableScheduling
public class RedisSubscriberService {

    private static final Logger log = LoggerFactory.getLogger(RedisSubscriberService.class);

    /** 识别结果队列。AI 引擎写入，后端消费。 */
    public static final String RESULT_QUEUE_KEY = "wildlife:results";

    /** 单次调度最多消费的消息条数，避免队列积压时长时间占用调度线程。 */
    private static final int MAX_MESSAGES_PER_TICK = 200;

    // AI 引擎回写的消息状态
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_STARTED = "STARTED";
    private static final String STATUS_PROCESSING = "PROCESSING";

    // 任务状态（与 recognition_task.status 一致）
    private static final String TASK_PENDING = "PENDING";
    private static final String TASK_PROCESSING = "PROCESSING";
    private static final String TASK_COMPLETED = "COMPLETED";
    private static final String TASK_FAILED = "FAILED";
    private static final String TASK_CANCELED = "CANCELED";

    // 图像状态（与 recognition_image.status 一致）
    private static final String IMAGE_WAITING = "WAITING";
    private static final String IMAGE_PROCESSING = "PROCESSING";
    private static final String IMAGE_SUCCESS = "SUCCESS";
    private static final String IMAGE_FAILED = "FAILED";

    /**
     * 任务生命周期守卫（⑨-B）：只有非终态任务（PENDING / PROCESSING）的图像才允许被本通道改写。
     *
     * <p>写成跨表子查询是为了把它压进<b>同一条原子 UPDATE</b>：
     * <pre>
     * UPDATE recognition_image SET ... WHERE id = ? AND status NOT IN (...) AND task_id IN (SELECT ...)
     * </pre>
     * MySQL 禁止 UPDATE 的子查询引用<b>被更新的同一张表</b>，但允许引用另一张表 ——
     * recognition_image 的子查询查 recognition_task 合法（临时表探针实测：
     * 任务 CANCELED 时 affected=0；任务非终态时 affected=1，正常路径无回归）。
     *
     * <p>状态取值不硬编码，直接取 {@link TaskRepository#ADVANCE_ALLOWED_STATUS} ——
     * "任务还活着"只有一处口径，将来改状态枚举不会漏掉这里。
     */
    private static final String ACTIVE_TASK_CONDITION =
            "SELECT id FROM recognition_task WHERE status IN ('"
                    + String.join("', '", TaskRepository.ADVANCE_ALLOWED_STATUS) + "')";

    private final StringRedisTemplate redisTemplate;
    private final RecognitionResultService recognitionResultService;
    private final ImageRepository imageRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisSubscriberService(StringRedisTemplate redisTemplate,
                                  RecognitionResultService recognitionResultService,
                                  ImageRepository imageRepository,
                                  TaskRepository taskRepository) {
        this.redisTemplate = redisTemplate;
        this.recognitionResultService = recognitionResultService;
        this.imageRepository = imageRepository;
        this.taskRepository = taskRepository;
    }

    /**
     * 轮询消费识别结果。
     * 一次调度内尽量把队列排空（受单次上限保护），队列为空则直接返回，不做空转等待。
     */
    @Scheduled(fixedDelay = 1000)
    public void consumeResults() {
        for (int i = 0; i < MAX_MESSAGES_PER_TICK; i++) {
            String message = redisTemplate.opsForList().leftPop(RESULT_QUEUE_KEY);
            if (message == null) {
                return;
            }
            try {
                handleMessage(message);
            } catch (Exception e) {
                // 单条消息失败不能拖垮整批消费
                log.error("AI 回写消息处理失败: {}", message, e);
            }
        }
    }

    // ── 单条消息处理 ────────────────────────────────────────────────────────

    private void handleMessage(String message) throws Exception {
        JsonNode root = objectMapper.readTree(message);

        long taskId = root.path("taskId").asLong(0L);
        if (taskId <= 0) {
            log.warn("AI 回写消息缺少 taskId，已忽略: {}", message);
            return;
        }

        RecognitionTask task = taskRepository.selectById(taskId);
        if (task == null) {
            log.warn("收到未知任务的识别结果: taskId={}", taskId);
            return;
        }

        String status = root.path("status").asText(STATUS_SUCCESS).toUpperCase();
        Long imageId = root.hasNonNull("imageId") ? root.get("imageId").asLong() : null;

        // 没有 imageId 视为任务级消息（例如任务开始、整批失败）
        if (imageId == null) {
            handleTaskLevelMessage(task, status, root);
            return;
        }

        boolean success = !STATUS_FAILED.equals(status);
        String errorMessage = success ? null : root.path("errorMessage").asText("识别失败");

        // 幂等闸门（前置抢占）：一张图像只认第一条结果，判断下沉到数据库的条件 UPDATE。
        // 只有把图像从非终态推进到终态的那条消息才算"抢到"处理权，后面所有副作用
        // （落结果 → 推进计数 → 推前端）都建立在抢占成功之上：
        //   · 重试期间迟到的旧结果撞车时 affected=0 直接丢弃，不会再走到 saveBatch；
        //   · saveBatch 会先删该图旧结果，闸门前置后重复消息不可能再把人工复核结论冲掉；
        //   · 并发消费者同时处理同一张图时，UPDATE 行锁保证只有一方 affected=1，不会双计数；
        //   · ⑨-B：闸门同时带任务状态守卫，任务已取消/已结束时 affected=0，
        //     迟到的结果不落 detection_result、不推进计数（硬取消语义）。
        // 代价记在账上：本步只做"抢占"，不含事务。抢占成功后 saveBatch/advanceTask 若抛异常，
        // 会留下"图像已终态、但结果与计数没落地"的窗口（改动前是"保持非终态、可被重投"）。
        // 彻底解法是把 抢占 + 写结果 + 推进计数 放进同一个事务边界，属后续范围。
        int affected = markImage(imageId, success ? IMAGE_SUCCESS : IMAGE_FAILED, errorMessage);
        if (affected == 0) {
            // 命中不了更新有三种可能：图像已是终态（重复消息）、该图不存在，
            // 或所属任务已不再接受结果（已取消/已结束）。都不该继续往下走 ——
            // 不覆盖状态、不重写结果、不重复计数。
            log.warn("图像 {} 抢占失败（已是终态、任务已终态或不存在），本次回写已忽略：taskId={}, status={}",
                    imageId, taskId, status);
            return;
        }

        // 1. 结果落 MySQL
        if (success) {
            List<DetectionResult> detections = parseDetections(root.get("detections"));
            recognitionResultService.saveBatch(imageId, task.getModelId(), detections);
        }

        // 2. 推进任务计数
        advanceTask(task, success);

        // 3. WebSocket 推送进度
        broadcastProgress(task);

        if (log.isDebugEnabled()) {
            log.debug("任务 {} 进度: {}/{} ({}%)", task.getId(),
                    task.getProcessedCount(), task.getTotalCount(), task.getProgress());
        }
    }

    /** 任务级消息：任务开始、整批失败等，只动任务与其下图像的状态，不碰计数。 */
    private void handleTaskLevelMessage(RecognitionTask task, String status, JsonNode root) {
        if (STATUS_FAILED.equals(status)) {
            String error = root.path("errorMessage").asText("AI 引擎处理失败");

            // 任务级 FAILED 同样只写 status + finish_time 两列，并带状态守卫：
            // ① 迟到的 FAILED 不能把刚被取消的任务从 CANCELED 翻成 FAILED（⑨-B 前的真实缺陷）；
            // ② 整行回写会把 Java 侧那份可能已经过时的计数一起盖回去。
            LocalDateTime finishTime = LocalDateTime.now();
            if (taskRepository.markFailedIfActive(task.getId(), finishTime) == 0) {
                log.warn("任务 {} 已是终态（{}），忽略迟到的任务级 FAILED", task.getId(), task.getStatus());
                return;
            }
            task.setStatus(TASK_FAILED);
            task.setFinishTime(finishTime);

            // 整批中断后不会再有任何结果回来了，把没出结果的图像一并收尾，
            // 否则它们在任务详情页会永远显示"待识别 / 识别中"
            int closed = failPendingImages(task.getId(), error);
            log.error("任务 {} 处理失败: {}（已收尾 {} 张未出结果的图像）",
                    task.getId(), error, closed);
            broadcastProgress(task);
        } else if (STATUS_STARTED.equals(status) || STATUS_PROCESSING.equals(status)) {
            String current = task.getStatus();
            if (!TASK_PENDING.equals(current) && !TASK_PROCESSING.equals(current)) {
                // CANCELED / COMPLETED / FAILED 都不该被一条迟到的 STARTED 复活
                log.warn("任务 {} 已是终态（{}），忽略迟到的任务级 {}", task.getId(), current, status);
                return;
            }

            if (TASK_PENDING.equals(current)) {
                // PENDING → PROCESSING 也改成条件 UPDATE，不再整行回写：
                // ① selectById 之后若用户取消成功，整行写会把 CANCELED 复活成 PROCESSING，
                //    而 advanceCounters 的状态守卫正是按 PROCESSING 放行的 —— 越权的计数推进会被重新打开；
                // ② 大任务按 500 张拆多条消息、每条都带一次 STARTED，重试轮次里迟到的 STARTED
                //    会把 Java 侧那份"读出来时还是 0"的计数与进度整行盖回去。
                LocalDateTime startTime = LocalDateTime.now();
                if (taskRepository.markProcessingIfPending(task.getId(), startTime) == 0) {
                    log.warn("任务 {} 已不是排队状态，忽略本次任务级 {}", task.getId(), status);
                    return;
                }
                task.setStatus(TASK_PROCESSING);
                task.setStartTime(startTime);
            }

            int marked = markImagesProcessing(task.getId());
            log.info("任务 {} 已开始识别，{} 张图像置为识别中", task.getId(), marked);
            broadcastProgress(task);
        } else {
            // SUCCESS 之类的逐张结果状态走到这里，说明消息缺 imageId，后端无法落库
            log.warn("忽略任务 {} 的未知任务级状态消息: status={}", task.getId(), status);
        }
    }

    // ── 解析检测结果 ────────────────────────────────────────────────────────

    /**
     * 解析 detections 数组。
     * 框坐标同时兼容 {x1,y1,x2,y2} 与 {bbox:[x1,y1,x2,y2]} 两种写法。
     */
    private List<DetectionResult> parseDetections(JsonNode detections) {
        List<DetectionResult> results = new ArrayList<>();
        if (detections == null || !detections.isArray()) {
            return results;
        }

        for (JsonNode node : detections) {
            DetectionResult result = new DetectionResult();
            if (node.hasNonNull("classId")) {
                result.setClassId(node.get("classId").asInt());
            }
            result.setClassName(node.path("className").asText("未知"));
            if (node.hasNonNull("confidence")) {
                result.setConfidence(node.get("confidence").asDouble());
            }

            if (node.hasNonNull("bbox") && node.get("bbox").isArray() && node.get("bbox").size() >= 4) {
                JsonNode bbox = node.get("bbox");
                result.setX1(bbox.get(0).asInt());
                result.setY1(bbox.get(1).asInt());
                result.setX2(bbox.get(2).asInt());
                result.setY2(bbox.get(3).asInt());
            } else {
                result.setX1(intOrNull(node, "x1"));
                result.setY1(intOrNull(node, "y1"));
                result.setX2(intOrNull(node, "x2"));
                result.setY2(intOrNull(node, "y2"));
            }

            result.setReviewStatus("PENDING");
            result.setCreateTime(LocalDateTime.now());
            results.add(result);
        }
        return results;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    // ── 状态推进 ────────────────────────────────────────────────────────────

    /**
     * 抢占单张图像的终态 —— 幂等闸门 + 任务生命周期闸门，两层都下沉到数据库。
     *
     * <p>把原来的「先 SELECT 出来判断、再 updateById」换成一条条件 UPDATE：
     * <pre>
     * UPDATE recognition_image
     *    SET status = ?, error_message = ?
     *  WHERE id = ?
     *    AND status NOT IN ('SUCCESS', 'FAILED')
     *    AND task_id IN (SELECT id FROM recognition_task WHERE status IN ('PENDING', 'PROCESSING'))
     * </pre>
     * 返回的受影响行数就是"是否抢占成功"：1 = 本消息是第一个处理这张图的，0 = 已被处理、图像不存在，
     * 或<b>所属任务已不再接受结果</b>（已取消 / 已结束）。
     *
     * <p>⑨-B 加的第三行 WHERE 解决的是一个曾经真实存在的悬空数据：
     * 任务取消后队列里仍有在途消息，图像级幂等闸门对此一无所知 ——
     * 于是会写出"任务 CANCELED、图却是 SUCCESS、还新增了 detection_result"，
     * 而任务计数被 ⑨-A 的守卫冻住不动，三方数据永久对不上。
     *
     * <p>两个实现细节（都在 MP 3.5.7 + MySQL 上实测过）：
     * <ul>
     *   <li><b>error_message 置空必须走 wrapper 的显式 set</b>：
     *       {@code updateById(实体)} 走 NOT_NULL 策略，值为 null 的字段会被整段跳过，
     *       重试成功时旧的失败原因就清不掉。用它生成 SET 子句时会得到
     *       {@code error_message=#{ew.paramNameValuePairs.MPGENVALn}} 并把 null 真正放进参数表
     *       （已用探针打印参数表确认，不是被吞掉）。</li>
     *   <li><b>受影响行数的口径</b>：JDBC URL 未设 {@code useAffectedRows}，
     *       MySQL 返回的是"匹配行数"而不是"变更行数"。这里不构成问题 ——
     *       WHERE 已排除终态，能匹配到就必然发生 WAITING/PROCESSING → SUCCESS/FAILED 的值变化，
     *       所以 matched == affected，不用改连接串。</li>
     * </ul>
     *
     * @return 受影响行数：1 表示抢占成功；0 表示已是终态、任务已终态，或图像/任务不存在
     */
    private int markImage(Long imageId, String status, String errorMessage) {
        LambdaUpdateWrapper<RecognitionImage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(RecognitionImage::getStatus, status)
                .set(RecognitionImage::getErrorMessage, errorMessage)
                .eq(RecognitionImage::getId, imageId)
                .notIn(RecognitionImage::getStatus, IMAGE_SUCCESS, IMAGE_FAILED)
                // ⑨-B 任务生命周期守卫：所在任务已取消/已结束时，迟到的结果连图像都不许认领
                .inSql(RecognitionImage::getTaskId, ACTIVE_TASK_CONDITION);
        return imageRepository.update(null, wrapper);
    }

    /**
     * 任务开始识别：把该任务下还在排队的图像批量置为 PROCESSING，
     * 一条 SQL 解决，逐张更新在万张量级下不可接受。
     *
     * <p>⑨-B 补的任务守卫：整批开工也要确认"任务还活着"，
     * 否则取消与 STARTED 并发时，一批 WAITING 图像会被无条件"复活"成识别中。
     */
    private int markImagesProcessing(Long taskId) {
        UpdateWrapper<RecognitionImage> wrapper = new UpdateWrapper<>();
        wrapper.set("status", IMAGE_PROCESSING)
                .eq("task_id", taskId)
                .eq("status", IMAGE_WAITING)
                .inSql("task_id", ACTIVE_TASK_CONDITION);
        return imageRepository.update(null, wrapper);
    }

    /**
     * 任务整批失败：把没有终态的图像一并置为 FAILED，并记下失败原因。
     *
     * <p>SQL 归 {@link ImageRepository#failPendingImages} —— 与"任务被取消后收尾未完成图像"
     * 共用同一份实现，两处语义完全一致（这批图不会再有人回来认领了）。
     */
    private int failPendingImages(Long taskId, String reason) {
        return imageRepository.failPendingImages(taskId, reason);
    }

    /**
     * 推进任务计数：计数/进度/状态流转全部由数据库的<b>一条条件 UPDATE</b> 原子完成
     * （{@code TaskRepository#advanceCounters}），这里只负责决定增量、并把结果同步到本地快照。
     *
     * <p>改动前是典型的 read-modify-write：{@code selectById} 出整个实体 → Java 侧算好
     * {@code processed/success/failed/progress/status} → {@code updateById} 整行写回，
     * {@code WHERE} 只有 id、没有任何状态守卫。两个后果都真实存在：
     * <ul>
     *   <li>{@code cancelTask}（HTTP 线程）与本方法（调度线程）互相覆盖 ——
     *       用户取消后，一条迟到的批量结果会把 CANCELED 连同陈旧计数一起写回去；</li>
     *   <li>每张图重写 9–10 列（含 {@code task_name/user_id/model_id/create_time} 这些与计数无关的列），
     *       10 万张图 ≈ 100 万列写入。</li>
     * </ul>
     * 现在两条都被消掉：写回的列收敛到 7 列，且 {@code WHERE status IN ('PENDING','PROCESSING')}
     * 让终态任务冻结、迟到结果不会再改写计数（受影响行数 0）。
     *
     * <p><b>本地预测（A-3）</b>：推进成功后把增量同步到内存里的 {@code task}，
     * 让 {@link #broadcastProgress(RecognitionTask)} 与完成日志拿到正确的 {@code processedCount/progress/status}，
     * 从而**不额外多查一次库**。代价记在账上：多实例并发消费时，推送值可能与库值短暂相差 ±N
     * （不是错，前端有 {@code GET /api/tasks/{id}/progress} 轮询兜底）；推送精度与节流留到 ⑩ 一并处理。
     * 推进被守卫拦下（{@code affected == 0}）时<b>不做预测</b>：库里既然没变，前置快照就仍然准确，
     * 原样推给前端反而是对的，也绝不会误报"已完成"。
     *
     * @return 受影响行数：1 = 推进成功；0 = 任务已是终态（CANCELED/COMPLETED/FAILED）或已不存在
     */
    private int advanceTask(RecognitionTask task, boolean success) {
        int successDelta = success ? 1 : 0;
        int failedDelta = success ? 0 : 1;

        int affected = taskRepository.advanceCounters(task.getId(), successDelta, failedDelta);
        if (affected == 0) {
            // 任务已是终态（COMPLETED/FAILED/CANCELED）或已被删除：状态守卫挡下了本次推进。
            // 计数不回写、进度不预测、也不推"已完成" —— 例如用户刚取消的任务不会被迟到结果复活。
            log.warn("任务 {} 计数推进被状态守卫拦下（已是终态或不存在），本次不回写计数：success={}",
                    task.getId(), success);
            return 0;
        }

        // ── 以下只更新内存快照，供推送与日志使用，不再写库 ──
        int processed = nvl(task.getProcessedCount()) + 1;
        task.setProcessedCount(processed);
        if (success) {
            task.setSuccessCount(nvl(task.getSuccessCount()) + 1);
        } else {
            task.setFailedCount(nvl(task.getFailedCount()) + 1);
        }

        int total = nvl(task.getTotalCount());
        // 幂等闸门已保证不会重复计数，这里再夹一次上限，进度绝不越过 100%
        task.setProgress(total <= 0 ? 0.0 : round2(Math.min(processed * 100.0 / total, 100.0)));

        if (TASK_PENDING.equals(task.getStatus())) {
            task.setStatus(TASK_PROCESSING);
            task.setStartTime(LocalDateTime.now());
        }

        // 全部图像都处理过了（无论成功失败）即视为任务结束
        if (total > 0 && processed >= total && !TASK_COMPLETED.equals(task.getStatus())
                && !TASK_FAILED.equals(task.getStatus()) && !TASK_CANCELED.equals(task.getStatus())) {
            task.setStatus(TASK_COMPLETED);
            task.setProgress(100.0);
            task.setFinishTime(LocalDateTime.now());
            log.info("任务 {} 已完成: 成功 {} 张 / 失败 {} 张", task.getId(),
                    task.getSuccessCount(), task.getFailedCount());
        }
        return affected;
    }

    // ── WebSocket 推送 ──────────────────────────────────────────────────────

    /**
     * 推送任务进度：
     * {"taskId":10001,"processedCount":38291,"totalCount":100000,"progress":38.29,"status":"PROCESSING"}
     * 任务处理完毕时改走"识别完成"推送（status=COMPLETED、progress=100）。
     */
    private void broadcastProgress(RecognitionTask task) {
        long taskId = task.getId() == null ? 0L : task.getId();
        int processed = nvl(task.getProcessedCount());
        int total = nvl(task.getTotalCount());

        if (TASK_COMPLETED.equals(task.getStatus())) {
            RecognitionWebSocket.sendTaskCompleted(taskId, processed, total);
        } else {
            double progress = task.getProgress() == null ? 0.0 : task.getProgress();
            RecognitionWebSocket.sendTaskProgress(taskId, processed, total, progress, task.getStatus());
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
