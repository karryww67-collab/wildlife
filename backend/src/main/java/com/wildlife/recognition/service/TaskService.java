package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.ModelVersion;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.entity.RecognitionTask;
import com.wildlife.recognition.entity.User;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.TaskRepository;
import com.wildlife.recognition.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量识别任务服务（识别链路的起点）。
 *
 * 一个任务 = 一批图像的一次批量识别过程。本服务负责：
 * 落任务 → 把图像挂到任务下 → 往 Redis 队列投递任务消息。
 * 之后由 AI 引擎消费队列逐张识别，经 {@link RedisSubscriberService} 回写结果并推进进度，
 * 前端既可通过 WebSocket 实时接收，也可轮询 {@code GET /api/tasks/{id}/progress}。
 *
 * 投递给 AI 引擎的消息格式（队列 {@value #TASK_QUEUE_KEY}，由 ai-engine 的
 * {@code task_consumer.py} 消费）：
 * <pre>
 * {
 *   "taskId": 10001,
 *   "modelId": 2,                                  // model_version.id，落库与排查用
 *   "modelVersion": "wildlife-v1.1",               // 选中模型的版本号
 *   "modelPath": "/models/wildlife-v1.1/best.pt",  // 该版本的权重路径（AI 引擎容器内路径）
 *   "images": [{"imageId": 2048, "filePath": "/shared-data/originals/20260920/a.jpg"}]
 * }
 * </pre>
 *
 * modelVersion / modelPath 是「用户在创建任务时选的那个模型」的落地方式：
 * AI 引擎据此加载对应权重，从而与 detection_result.model_id 记录的模型保持一致。
 * 只带 modelId 时 AI 引擎无从得知该用哪份权重，只能回退到默认权重，
 * 模型管理里选的版本就等于没生效。
 *
 * filePath 是 backend 与 ai-engine **两个容器内一致的绝对路径**：docker-compose 把宿主机的
 * {@code ./ai-engine/data} 同时挂载为两端的 {@code /shared-data}，图像不进网络传输，
 * 因此这里不做路径转换，直接投递库中存的值。
 *
 * 任务状态流转：
 * <pre>
 * PENDING ──AI 回写 STARTED──→ PROCESSING ──处理数达到总数──→ COMPLETED
 *    │                              │
 *    └──────── 用户取消 ────────────┴──→ CANCELED ──retry──→ 派生新任务（原任务保持 CANCELED）
 *                                   └──→ FAILED   ──retry──→ 派生新任务（原任务保持 FAILED）
 * </pre>
 *
 * <b>⑨-C：retry 不再复活原任务</b>，而是派生一个拥有新 {@code taskId} / 新 {@code imageId} 的新任务
 * （见 {@link #retryTask(Long)}）。原任务恒为终态，因此迟到的旧消息会被 ⑨-B 的任务守卫拦下 ——
 * 隔离是「身份级」的，不需要任何轮次字段。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** 任务队列：后端投递，AI 引擎消费。 */
    private static final String TASK_QUEUE_KEY = "wildlife:tasks";

    /** 单个投递消息最多携带的图像数，超出的拆成多条消息，避免单条消息过大。 */
    private static final int DISPATCH_BATCH_SIZE = 500;

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELED = "CANCELED";

    private static final String IMAGE_STATUS_FAILED = "FAILED";

    /** 取消任务时写入未完成图像 error_message 的原因（图像枚举里没有 CANCELED，只能复用 FAILED + 原因）。 */
    private static final String IMAGE_FAILED_REASON_CANCELED = "任务已取消";

    private static final DateTimeFormatter DEFAULT_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TaskRepository taskRepository;
    private final ImageRepository imageRepository;
    private final UserRepository userRepository;
    private final ImageService imageService;
    private final RecognitionResultService recognitionResultService;
    private final ModelService modelService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** recognition_task.task_name 是 VARCHAR(200)（init.sql），派生命名必须截断后才能写库。 */
    private static final int TASK_NAME_MAX_LENGTH = 200;

    /** 派生重试任务的命名标记 —— 同时充当 [幂等] 判据（库里没有 retry_from 列）。 */
    private static final String RETRY_NAME_MARK = "·重试自#";

    /**
     * 重试串行化锁：{@code retryTask} 要「先查派生任务、再插入」，两步之间存在检查-写入窗口。
     * 单实例部署下用一把 JVM 锁把它闭合成串行，避免并发点击派生两个新任务。
     *
     * <p>⚠️ 多实例部署时这把锁不跨进程 —— 严格并发唯一需要数据库唯一约束，属 DDL 议题，不在本轮。
     * 另注意：本方法所在的服务类<b>没有 {@code @Transactional}</b>，{@code insert} 即时提交，
     * 所以锁释放时派生行已对第二个线程可见，串行判据成立；日后若给该方法加事务，这把锁会失效。
     */
    private final Object retryLock = new Object();

    public TaskService(TaskRepository taskRepository,
                       ImageRepository imageRepository,
                       UserRepository userRepository,
                       ImageService imageService,
                       RecognitionResultService recognitionResultService,
                       ModelService modelService,
                       StringRedisTemplate redisTemplate) {
        this.taskRepository = taskRepository;
        this.imageRepository = imageRepository;
        this.userRepository = userRepository;
        this.imageService = imageService;
        this.recognitionResultService = recognitionResultService;
        this.modelService = modelService;
        this.redisTemplate = redisTemplate;
    }

    // ── 创建 ────────────────────────────────────────────────────────────────

    /**
     * 创建批量识别任务并立即投递队列。
     *
     * @param taskName  任务名称，留空则按时间自动生成
     * @param imageIds  参与识别的图像 ID（来自图像批量上传接口的返回值）
     * @param modelId   使用的模型版本，省略时用当前启用中的模型
     * @param username  创建人账号（由认证拦截器注入）
     */
    public RecognitionTask createTask(String taskName, List<Long> imageIds, Long modelId, String username) {
        if (imageIds == null || imageIds.isEmpty()) {
            throw new IllegalArgumentException("imageIds 不能为空");
        }

        List<RecognitionImage> images = imageService.listByIds(imageIds);
        if (images.isEmpty()) {
            throw new IllegalArgumentException("imageIds 对应的图像不存在");
        }

        // 先解析模型：既校验模型存在，也拿到投递队列时要交给 AI 引擎的权重信息
        ModelVersion model = resolveModel(modelId);

        RecognitionTask task = new RecognitionTask();
        task.setTaskName(StringUtils.hasText(taskName) ? taskName.trim() : defaultTaskName());
        task.setUserId(requireUserId(username));
        task.setModelId(model.getId());
        task.setTotalCount(images.size());
        task.setProcessedCount(0);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        task.setProgress(0.0);
        task.setStatus(STATUS_PENDING);
        task.setCreateTime(LocalDateTime.now());
        taskRepository.insert(task);

        // 先挂到任务下（会把图像重置为 WAITING），再决定哪些真能投递
        imageService.attachToTask(images.stream().map(RecognitionImage::getId).toList(), task.getId());
        startTask(task, images, model);

        log.info("识别任务已创建: id={}, name={}, 图像={} 张, modelId={}",
                task.getId(), task.getTaskName(), task.getTotalCount(), task.getModelId());
        return task;
    }

    private static String defaultTaskName() {
        return "识别任务-" + LocalDateTime.now().format(DEFAULT_NAME_FORMAT);
    }

    /**
     * 筛选可投递的图像、把 AI 必然读不到的图像直接判失败，然后投递队列。
     *
     * 关键是保证 {@code processedCount + 待识别数 == totalCount}：
     * 否则 AI 回写的进度永远够不到总数，任务会卡在 PROCESSING 不动。
     */
    private void startTask(RecognitionTask task, List<RecognitionImage> images, ModelVersion model) {
        List<RecognitionImage> dispatchable = new ArrayList<>();
        int invalid = 0;
        for (RecognitionImage image : images) {
            if (StringUtils.hasText(image.getFilePath())) {
                dispatchable.add(image);
            } else {
                // 库里没存路径的图像不可能被 AI 引擎读到，直接判失败
                imageService.markStatus(image.getId(), IMAGE_STATUS_FAILED, "图像文件路径缺失");
                invalid++;
            }
        }

        if (invalid > 0) {
            task.setFailedCount(invalid);
            task.setProcessedCount(invalid);
        }

        if (dispatchable.isEmpty()) {
            // 没有任何图像可投递，任务直接结束，不留在 PENDING 空等
            task.setStatus(STATUS_COMPLETED);
            task.setProgress(100.0);
            task.setFinishTime(LocalDateTime.now());
            taskRepository.updateById(task);
            log.warn("任务 {} 没有可投递的图像，直接结束", task.getId());
            return;
        }

        taskRepository.updateById(task);
        dispatch(task, dispatchable, model);
    }

    /**
     * 把任务消息分块投递到 Redis 队列，交给 AI 引擎消费。
     *
     * 每 {@value #DISPATCH_BATCH_SIZE} 张图像拆成一条消息，消息里带上该批次的
     * {@code batchStart}/{@code batchEnd}/{@code batchSize}，AI 引擎按批识别并回写结果。
     * 分块的意义是避免上万张图时单条消息过大（Redis 单值 / 网络传输都是一次性读入），
     * 同时让 AI 引擎能更早开始处理，不必等整条消息序列化完。
     *
     * 消息里同时带上选中模型的版本号与权重路径，AI 引擎据此加载对应权重。
     */
    private void dispatch(RecognitionTask task, List<RecognitionImage> images, ModelVersion model) {
        for (int start = 0; start < images.size(); start += DISPATCH_BATCH_SIZE) {
            int end = Math.min(start + DISPATCH_BATCH_SIZE, images.size());
            List<RecognitionImage> batch = images.subList(start, end);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("taskId", task.getId());
            if (task.getModelId() != null) {
                payload.put("modelId", task.getModelId());
            }
            if (model != null) {
                if (StringUtils.hasText(model.getVersion())) {
                    payload.put("modelVersion", model.getVersion());
                }
                // model_path 存的就是 AI 引擎容器内的绝对路径（init.sql 注释有约定），
                // 两个容器都把宿主机 ./ai-engine/models 挂载到 /models，因此这里直接透传。
                if (StringUtils.hasText(model.getModelPath())) {
                    payload.put("modelPath", toContainerPath(model.getModelPath()));
                }
            }
            payload.put("batchStart", start);
            payload.put("batchEnd", end);
            payload.put("batchSize", batch.size());

            ArrayNode array = payload.putArray("images");
            for (RecognitionImage image : batch) {
                ObjectNode node = array.addObject();
                node.put("imageId", image.getId());
                node.put("filePath", toContainerPath(image.getFilePath()));
            }

            try {
                String message = objectMapper.writeValueAsString(payload);
                redisTemplate.opsForList().rightPush(TASK_QUEUE_KEY, message);
                log.info("任务 {} 投递批次: start={}, end={}, size={}",
                        task.getId(), start, end, batch.size());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("任务消息序列化失败: " + e.getMessage(), e);
            }
        }
    }

    /** 路径统一成正斜杠（Windows 上开发、容器内运行时都一致）。 */
    private static String toContainerPath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    /**
     * 分页查询识别任务，可按状态筛选；不传状态则不过滤。
     *
     * 使用 MyBatis-Plus selectPage()，由 MySQL 直接执行 LIMIT/OFFSET。
     *
     * 排序固定为 create_time DESC, id DESC：真分页必须有确定的全序，
     * 只按 create_time 排序时同一秒创建的任务顺序不定，翻页会出现重复或漏项。
     *
     * 返回手写的五键 Map（total/page/size/pages/list），与 /api/images、/api/results 口径一致；
     * 不能直接返回 Page 对象 —— Page 会被序列化成 records/current，前端读不到 list。
     */
    public Map<String, Object> listAll(String status, int page, int size) {
        long current = Math.max(page, 1);
        long pageSize = Math.min(Math.max(size, 1), 200);

        QueryWrapper<RecognitionTask> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");

        Page<RecognitionTask> pageResult = taskRepository.selectPage(new Page<>(current, pageSize), wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        result.put("pages", pageResult.getPages());
        result.put("list", pageResult.getRecords());
        return result;
    }

    /** 不带状态筛选的分页查询（status 传 null）。 */
    public Map<String, Object> listAll(int page, int size) {
        return listAll(null, page, size);
    }

    /** 今日创建的任务。 */
    public List<RecognitionTask> listToday() {
        LocalDate today = LocalDate.now();
        return taskRepository.selectList(new QueryWrapper<RecognitionTask>()
                .ge("create_time", today.atStartOfDay())
                .lt("create_time", today.plusDays(1).atStartOfDay())
                .orderByDesc("create_time")
                .orderByDesc("id"));
    }

    public RecognitionTask getById(Long id) {
        return id == null ? null : taskRepository.selectById(id);
    }

    /** 任务下的全部识别结果（跨图像汇总）。 */
    public List<DetectionResult> getTaskResults(Long taskId) {
        return recognitionResultService.listByTask(taskId);
    }

    /**
     * 任务进度，供前端轮询；字段与 WebSocket 推送、前端 {@code TaskProgress} 类型一致。
     * 任务不存在时返回空 Map。
     */
    public Map<String, Object> getProgress(Long id) {
        RecognitionTask task = getById(id);
        if (task == null) {
            return Map.of();
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("taskId", task.getId());
        progress.put("status", task.getStatus());
        progress.put("taskName", task.getTaskName());
        progress.put("totalCount", nvl(task.getTotalCount()));
        progress.put("processedCount", nvl(task.getProcessedCount()));
        progress.put("successCount", nvl(task.getSuccessCount()));
        progress.put("failedCount", nvl(task.getFailedCount()));
        progress.put("progress", task.getProgress() == null ? 0.0 : task.getProgress());
        progress.put("startTime", task.getStartTime());
        progress.put("finishTime", task.getFinishTime());
        return progress;
    }

    // ── 取消 / 重试 / 删除 ──────────────────────────────────────────────────

    /**
     * 取消任务（语义 <b>β：硬取消</b> —— 取消后不再接受该任务的任何结果）。
     *
     * <p>AI 引擎没有中断通道，{@code wildlife:tasks} 又是 Redis List、无法按 taskId 撤回已投递的消息，
     * 所以"取消"不是"让队列里的消息消失"，而是在<b>后端回写边界</b>建一道任务生命周期闸门。
     * 三件事合起来才构成完整的取消语义：
     * <ol>
     *   <li><b>取消自身是条件 UPDATE</b>（{@code TaskRepository#cancelIfActive}）：
     *       {@code SET status='CANCELED', finish_time=? WHERE id=? AND status IN ('PENDING','PROCESSING')}。
     *       取消操作只拥有 status / finish_time 两列的写权限 —— 不会再把读到的整行（含计数）写回去，
     *       于是它与调度线程的 {@code advanceCounters} 并发时不可能造成计数倒退，重复取消也天然幂等；</li>
     *   <li><b>未出结果的图像就地收尾为 FAILED</b>：取消之后迟到的结果会被回写通道的任务守卫挡下，
     *       不会再有人把这些图推进终态，不收尾它们会永远停在"待识别 / 识别中"。
     *       已是 SUCCESS 的图与其识别结果、人工复核结论都原样保留；</li>
     *   <li>此后 {@code RedisSubscriberService} 的两道守卫生效：迟到的逐张结果不落
     *       {@code detection_result}、不推进计数；迟到的任务级 FAILED 也翻不动 CANCELED。</li>
     * </ol>
     * 于是取消后的数据口径是闭合的：SUCCESS 的图 + 原结果保留、其余图 FAILED、
     * {@code processed_count} 冻结在取消时刻且等于图像终态数，任务保持 CANCELED。
     */
    public RecognitionTask cancelTask(Long id) {
        RecognitionTask task = getById(id);
        if (task == null) {
            return null;
        }
        if (!STATUS_PENDING.equals(task.getStatus()) && !STATUS_PROCESSING.equals(task.getStatus())) {
            throw new IllegalStateException("只有排队中或进行中的任务可以取消，当前状态: " + task.getStatus());
        }

        // ① 条件 UPDATE 改状态：上面 getById 只是为了让报错信息更友好，绝不再把这份快照写回去
        LocalDateTime finishTime = LocalDateTime.now();
        if (taskRepository.cancelIfActive(id, finishTime) == 0) {
            // getById 之后任务被调度线程推到了终态（例如最后一张图恰好落地、任务翻成 COMPLETED）。
            // 守卫把它挡住了：不写计数、不覆盖状态，此时取消失败才是正确结果。
            throw new IllegalStateException("任务已结束，无法取消，当前状态: " + task.getStatus());
        }

        // ② 收尾未终态图像（必须在状态已确认为 CANCELED 之后做 —— 否则守卫还没生效，
        //    并发到达的结果可能在两步之间抢先把图像认领成 SUCCESS）
        int closed = imageRepository.failPendingImages(id, IMAGE_FAILED_REASON_CANCELED);

        task.setStatus(STATUS_CANCELED);
        task.setFinishTime(finishTime);
        log.info("任务 {} 已取消（未出结果的 {} 张图像已收尾为 FAILED）", id, closed);
        return task;
    }

    /**
     * 重试失败或已取消的任务 —— <b>⑨-C 起语义为「派生一个新任务」，不再复活原任务</b>。
     *
     * <p>原任务连同它的图像行、识别结果、人工复核结论<b>全部原样保留</b>（一行都不再写）；
     * 新任务拥有新的 {@code task_id} 与新的 {@code image_id}，只复用 {@code file_path}（磁盘文件不复制）。
     *
     * <p>这样隔离是<b>身份级</b>的，不需要任何轮次字段：旧消息里的 taskId 指向那个恒为终态的原任务，
     * 于是 ⑨-B 既有的跨表任务守卫（{@code status IN ('PENDING','PROCESSING')}）会把它们全部拦下；
     * 而新任务立刻是 PENDING，但旧消息的 taskId ≠ 新任务主键，因此不存在"旧轮污染新轮"的路径。
     *
     * <p><b>幂等</b>：同一原任务只允许派生一个 retry 任务，判据是派生任务的命名
     * （{@code {原名}·重试自#{oldId}}）。库中没有 retry_from 列，所以这是应用层判据 + 单实例串行锁；
     * 数据库级严格唯一需要新增唯一索引，属 DDL 议题，不在本轮。
     */
    public RecognitionTask retryTask(Long id) {
        synchronized (retryLock) {
            RecognitionTask origin = getById(id);
            if (origin == null) {
                return null;
            }
            if (!STATUS_FAILED.equals(origin.getStatus()) && !STATUS_CANCELED.equals(origin.getStatus())) {
                throw new IllegalStateException("只有失败或已取消的任务可以重试，当前状态: " + origin.getStatus());
            }

            List<RecognitionImage> originImages = imageService.listByTask(id);
            if (originImages.isEmpty()) {
                throw new IllegalStateException("任务下没有可重试的图像");
            }

            // 沿用原任务的模型。已被任务使用的模型不允许删除（ModelService.delete 会拦），
            // 所以正常情况下必然存在；万一不存在则直接报错，避免换了模型却仍按旧的 model_id 记录结果。
            ModelVersion model = resolveModel(origin.getModelId());

            // ① 幂等：同一原任务只派生一次；第二次点击直接复用已存在的派生任务，不重复投递
            String retryName = retryTaskName(origin);
            RecognitionTask existing = findDerivedTask(retryName);
            if (existing != null) {
                log.info("任务 {} 已存在派生任务 {}，直接复用，不再重新投递", id, existing.getId());
                return existing;
            }

            // ② 建新任务：自增主键由数据库回填，insert 后即可取到新 taskId
            RecognitionTask fresh = new RecognitionTask();
            fresh.setTaskName(retryName);
            // user_id 非空且有外键；retryTask 没有 username 入参，只能从原任务继承
            fresh.setUserId(origin.getUserId());
            fresh.setModelId(origin.getModelId());
            fresh.setTotalCount(originImages.size());
            fresh.setProcessedCount(0);
            fresh.setSuccessCount(0);
            fresh.setFailedCount(0);
            fresh.setProgress(0.0);
            fresh.setStatus(STATUS_PENDING);
            fresh.setCreateTime(LocalDateTime.now());
            fresh.setStartTime(null);
            fresh.setFinishTime(null);
            taskRepository.insert(fresh);

            // ③ 复制图像行：新 task_id + 新自增主键，复用 file_path / file_name / file_size，状态从头开始
            List<RecognitionImage> copies = new ArrayList<>(originImages.size());
            for (RecognitionImage src : originImages) {
                RecognitionImage copy = new RecognitionImage();
                copy.setId(null);                    // 交给自增，绝不复用原 imageId
                copy.setTaskId(fresh.getId());
                copy.setFileName(src.getFileName());
                copy.setFilePath(src.getFilePath());
                copy.setFileSize(src.getFileSize());
                copy.setStatus("WAITING");
                copy.setErrorMessage(null);
                copy.setCreateTime(LocalDateTime.now());
                copies.add(copy);
            }
            imageRepository.insertBatchInChunks(copies);

            // ④ 批插不回填主键，按新 task_id 回查，拿到真正的 imageId 才能投递
            List<RecognitionImage> freshImages = imageService.listByTask(fresh.getId());

            // ⑤ 复用既有 startTask：筛掉无路径的图 + 拆批投递。原任务全程零写入。
            startTask(fresh, freshImages, model);

            log.info("任务 {} 已派生重试任务: id={}, name={}, 图像={} 张",
                    id, fresh.getId(), fresh.getTaskName(), fresh.getTotalCount());
            return fresh;
        }
    }

    /**
     * 派生任务命名：{@code {原名}·重试自#{oldId}}。
     * 按 {@code task_name VARCHAR(200)} 截断<b>原名</b>，不截后缀 —— 否则幂等判据会失配。
     */
    private static String retryTaskName(RecognitionTask origin) {
        String suffix = RETRY_NAME_MARK + origin.getId();
        String base = origin.getTaskName() == null ? "" : origin.getTaskName();
        int room = TASK_NAME_MAX_LENGTH - suffix.length();
        if (room < 0) {
            room = 0;
        }
        if (base.length() > room) {
            base = base.substring(0, room);
        }
        return base + suffix;
    }

    /** 按命名找已派生的重试任务（库里没有 retry_from 列，命名即幂等判据）。 */
    private RecognitionTask findDerivedTask(String name) {
        List<RecognitionTask> found = taskRepository.selectList(
                new QueryWrapper<RecognitionTask>().eq("task_name", name).last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 删除任务，并清理整条数据链：识别结果 → 图像（含磁盘文件）→ 任务本身。
     * 复核记录随检测结果级联删除（review_record → detection_result 外键 ON DELETE CASCADE）。
     */
    public boolean deleteTask(Long id) {
        RecognitionTask task = getById(id);
        if (task == null) {
            return false;
        }

        int results = recognitionResultService.deleteByTask(id);
        int images = imageService.deleteByTask(id);
        taskRepository.deleteById(id);

        log.info("任务 {} 已删除: 结果 {} 条, 图像 {} 张", id, results, images);
        return true;
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    /**
     * 解析本次任务使用的模型：指定了 modelId 就用它（顺带校验存在），
     * 否则用当前启用中的模型。返回值同时用于落库与投递队列。
     */
    private ModelVersion resolveModel(Long modelId) {
        if (modelId != null) {
            ModelVersion model = modelService.getById(modelId);
            if (model == null) {
                throw new IllegalArgumentException("模型不存在: " + modelId);
            }
            return model;
        }
        ModelVersion active = modelService.getActive();
        if (active == null) {
            throw new IllegalArgumentException("当前没有启用中的识别模型，请先在模型管理中启用一个");
        }
        return active;
    }

    /** recognition_task.user_id 非空且有外键约束，创建人必须能解析出用户。 */
    private Long requireUserId(String username) {
        Long userId = resolveUserId(username);
        if (userId == null) {
            throw new IllegalArgumentException("无法确定任务创建人，请重新登录后再试");
        }
        return userId;
    }

    private Long resolveUserId(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        User user = userRepository.selectOne(
                new QueryWrapper<User>().eq("username", username).last("LIMIT 1"));
        return user == null ? null : user.getId();
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
