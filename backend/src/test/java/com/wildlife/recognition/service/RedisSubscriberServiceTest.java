package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.entity.RecognitionTask;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.TaskRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 回写通道的三把刀：⑧ 图像幂等闸门 + ⑨-A 任务计数原子化 + ⑨-B 任务生命周期闸门。
 *
 * <p>为什么单独写这个类：{@code RedisSubscriberService} 是"AI 结果回写"的唯一入口，
 * 一张图像在这里被处理两次就会重复计数、重复写结果，甚至把人工复核结论冲掉；
 * 任务计数在这里被整行 RMW 回写，就会与 {@code cancelTask} 互相覆盖。
 * 但这两刀改造之前它<b>没有任何测试覆盖</b>（仓库里只有 Image / Result / Review / Task 四个 Service 的测试），
 * 所以"改了幂等判断 / 改了计数写法"在离线状态下是无法证明的。这里把两件事的行为钉死：
 *
 * <p><b>⑧ 闸门（前四例）</b>
 * <ol>
 *   <li>抢占成功（affected=1）→ 落结果、推进计数；</li>
 *   <li>抢占失败（affected=0）→ 幂等丢弃，不写结果、不推进计数；</li>
 *   <li>条件 UPDATE 的 SQL 形状（SET 两列 + WHERE id AND status NOT IN 两个终态），
 *       并且 {@code error_message} 的 null 必须真的作为绑定值传下去；</li>
 *   <li>处理链路里不再有 per-image 的 {@code selectById}（少一次 SELECT）。</li>
 * </ol>
 *
 * <p><b>⑨-A 任务计数（后五例）</b>
 * <ol>
 *   <li>成功/失败分别走 {@code advanceCounters(taskId, 1, 0)} / {@code (taskId, 0, 1)} —— 增量由 Service 决定；</li>
 *   <li>抓 {@code TaskRepository#advanceCounters} 真正交给 MyBatis 的那个 {@link UpdateWrapper}，
 *       把 SQL 形状钉死：增量式、{@code progress} 由 SQL 派生、状态 CASE 同语句完成流转，
 *       并且 <b>{@code processed_count = processed_count + 1} 必须落在 SET 的最后</b>
 *       （MySQL 单表 UPDATE 从左到右求值且后赋值能看到新值，放前面会让进度整轮多算一格）；</li>
 *   <li>反证"整行 UPDATE 被消灭"：SET 里<b>不得</b>出现 {@code task_name / user_id / model_id / create_time}；</li>
 *   <li>状态守卫拦下（affected=0）时，本地快照不被推进，避免给前端推一个假的"已完成"；</li>
 *   <li>本地预测（推送用）的进度序列与真库探针实测序列一致，防止 Java 预测与 SQL 口径漂移。</li>
 * </ol>
 *
 * <p><b>⑨-B 任务生命周期闸门（后五例）</b>：图像状态保护 ≠ 任务状态保护 ——
 * 任务被取消后队列里必然还有在途消息（AI 引擎没有中断通道、{@code wildlife:tasks} 是 List、无法按 taskId 撤回），
 * 所以判据必须带上任务状态，而且要和图像守卫压进<b>同一条原子 UPDATE</b>，
 * 不留 Java 侧 {@code if (CANCELED) return} 那种"检查完再写"的 TOCTOU 窗口。
 * 顺带把剩下两处任务级 {@code updateById} 也换成条件 UPDATE：迟到的任务级 FAILED 不得把 CANCELED 翻掉；
 * 迟到的 STARTED 不得把已取消的任务复活成 PROCESSING（那会重新打开 ⑨-A 的计数守卫，让越权计数推进回来）。
 * 这一组把三件事钉死：① 图像闸门的 WHERE 里真的有跨表任务子查询；② 被守卫拦下时一条 SQL 都不发；
 * ③ 这条链路里再也没有 {@code updateById} 整行回写。
 *
 * <p>两个实现要点：
 * <ul>
 *   <li><b>必须手工装载 TableInfo</b>：⑧ 的 {@code LambdaUpdateWrapper} 靠实体的表元数据把
 *       {@code RecognitionImage::getStatus} 解析成列名，脱离 Spring 容器时这份缓存不存在，
 *       会直接抛 {@code MybatisPlusException: can not find lambda cache for this entity}。
 *       所以 {@link #initTableInfo()} 里手工调一次 {@code TableInfoHelper.initTableInfo}
 *       —— 它内部有缓存短路，重复调用是安全的。（⑨-A 的 {@code UpdateWrapper} 用字符串列名，不需要它。）</li>
 *   <li><b>驱动方式</b>：{@code handleMessage} 是私有的，这里通过公开的 {@code consumeResults()}
 *       喂消息（stub 一次 {@code leftPop} 返回消息、第二次返回 null 结束循环），
 *       顺带也覆盖了"单条消息失败不影响整批"的消费循环本身。</li>
 *   <li><b>⑨-A 的 {@code advanceCounters} 是 Mapper 上的 default 方法</b>（SQL 形状归 Repository 管），
 *       所以 {@link #setUp()} 里用 {@code doCallRealMethod()} 让它在测试中真的执行，
 *       再拦截它内部调用的 {@code update(entity, wrapper)} 抓 wrapper —— 与 ⑧ 抓闸门 wrapper 同一个套路。</li>
 * </ul>
 */
class RedisSubscriberServiceTest {

    private static final long TASK_ID = 7L;
    private static final long IMAGE_ID = 22L;

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private ListOperations<String, String> listOps;
    private RecognitionResultService resultService;
    private ImageRepository imageRepository;
    private TaskRepository taskRepository;
    private RedisSubscriberService subscriber;

    /**
     * 装载 {@code RecognitionImage} 的表元数据，让 LambdaUpdateWrapper 能解析列名。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "r8-test");
        TableInfoHelper.initTableInfo(assistant, RecognitionImage.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        resultService = mock(RecognitionResultService.class);
        imageRepository = mock(ImageRepository.class);
        taskRepository = mock(TaskRepository.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);

        // ⑨-A：advanceCounters 是 Mapper 接口的 default 方法，默认在 mock 上不会执行。
        // 让它走真实实现（从而真的构建 UpdateWrapper），再在下面拦 update(entity, wrapper) 抓 wrapper。
        doCallRealMethod().when(taskRepository).advanceCounters(anyLong(), anyInt(), anyInt());

        // ⑨-B：两个任务生命周期闸门同样是 Mapper 的 default 方法，同样要走真实实现才能验 SQL 形状
        doCallRealMethod().when(taskRepository).markFailedIfActive(anyLong(), any(LocalDateTime.class));
        doCallRealMethod().when(taskRepository).markProcessingIfPending(anyLong(), any(LocalDateTime.class));

        subscriber = new RedisSubscriberService(redisTemplate, resultService, imageRepository, taskRepository);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 喂一条消息并按队列空结束消费循环。 */
    private void deliver(String message) {
        when(listOps.leftPop(anyString())).thenReturn(message).thenReturn((String) null);
        subscriber.consumeResults();
    }

    /** 任务进行到 3/10 的中间态，便于断言计数推进。 */
    private RecognitionTask runningTask() {
        RecognitionTask task = new RecognitionTask();
        task.setId(TASK_ID);
        task.setModelId(1L);
        task.setStatus("PROCESSING");
        task.setTotalCount(10);
        task.setProcessedCount(3);
        task.setSuccessCount(3);
        task.setFailedCount(0);
        return task;
    }

    private static String successMessage(long imageId) {
        return "{\"taskId\":" + TASK_ID + ",\"imageId\":" + imageId + ",\"status\":\"SUCCESS\","
                + "\"detections\":[{\"classId\":1,\"className\":\"dog\",\"confidence\":0.91,"
                + "\"x1\":10,\"y1\":20,\"x2\":30,\"y2\":40}]}";
    }

    private static String failedMessage(long imageId, String error) {
        return "{\"taskId\":" + TASK_ID + ",\"imageId\":" + imageId + ",\"status\":\"FAILED\","
                + "\"errorMessage\":\"" + error + "\"}";
    }

    /**
     * 抓取 service 交给 repository 的那条条件 UPDATE，用来离线检查它生成的 SQL。
     *
     * <p>⚠️ 下面两行"白拿返回值"的调用是<b>必须的</b>，不是凑数：
     * MP 3.5.7 里 {@code set(...)} 的参数在构建时就登记进 {@code paramNameValuePairs}，
     * 但 {@code eq/notIn/in} 这类 WHERE 条件传的是惰性 {@code ISqlSegment}（{@code () -> formatParam(null, val)}），
     * <b>只有真正取一次 WHERE 片段才会登记</b>。实测：build 完 params.size()=2，
     * 取过 {@code getSqlSet()} 仍是 2，取过 {@code getCustomSqlSegment()} 才变 5。
     * 少这一步就会看到"id 与两个终态字面量都不在参数表里"的假失败 —— 而线上 MyBatis
     * 一定会先渲染完整 SQL，所以只是取值顺序问题，不是缺陷。
     */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<RecognitionImage> capturedGateWrapper() {
        ArgumentCaptor<Wrapper<RecognitionImage>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(imageRepository).update(isNull(), captor.capture());
        LambdaUpdateWrapper<RecognitionImage> wrapper =
                (LambdaUpdateWrapper<RecognitionImage>) captor.getValue();
        wrapper.getSqlSet();            // 渲染 SET 段
        wrapper.getCustomSqlSegment();  // 渲染 WHERE 段（这一步才会登记 WHERE 的参数）
        return wrapper;
    }

    /**
     * 抓取 ⑨-A 那条任务计数 UPDATE 的 wrapper。
     * {@code advanceCounters} 内部调 {@code update(null, wrapper)}，这里拦在同一层。
     */
    @SuppressWarnings("unchecked")
    private UpdateWrapper<RecognitionTask> capturedAdvanceWrapper() {
        ArgumentCaptor<Wrapper<RecognitionTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskRepository).update(isNull(), captor.capture());
        UpdateWrapper<RecognitionTask> wrapper = (UpdateWrapper<RecognitionTask>) captor.getValue();
        wrapper.getSqlSet();            // 渲染 SET 段（setSql 的参数是即刻登记的）
        wrapper.getCustomSqlSegment();  // 渲染 WHERE 段（这一步才会登记 WHERE 的参数）
        return wrapper;
    }

    /** 让图像抢占成功、任务计数推进成功。 */
    private void preemptOk(RecognitionTask task) {
        when(taskRepository.selectById(TASK_ID)).thenReturn(task);
        when(imageRepository.update(any(), any())).thenReturn(1);
        when(taskRepository.update(isNull(), any())).thenReturn(1);
    }

    // ── ⑧ 图像幂等闸门 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⑧ 抢占成功（affected=1）：继续落结果、推进计数、重算进度")
    void preempted_runsAllSideEffects() {
        RecognitionTask task = runningTask();
        preemptOk(task);

        deliver(successMessage(IMAGE_ID));

        // 结果落库：imageId + 任务当前模型版本 + 解析出的检测框
        verify(resultService).saveBatch(eq(IMAGE_ID), eq(1L), anyList());

        // 计数推进：增量由 Service 决定 —— 成功走 successDelta=1
        verify(taskRepository).advanceCounters(TASK_ID, 1, 0);

        // 本地快照同步为 3 → 4，进度 40%（共 10 张），供 WebSocket 推送使用
        assertThat(task.getProcessedCount()).isEqualTo(4);
        assertThat(task.getSuccessCount()).isEqualTo(4);
        assertThat(task.getFailedCount()).isZero();
        assertThat(task.getProgress()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("⑧ 抢占失败（affected=0）：重复消息到此为止 —— 不写结果、不推进计数")
    void notPreempted_isIdempotent() {
        when(taskRepository.selectById(TASK_ID)).thenReturn(runningTask());
        when(imageRepository.update(any(), any())).thenReturn(0);

        deliver(successMessage(IMAGE_ID));

        verify(resultService, never()).saveBatch(any(), any(), anyList());
        verify(taskRepository, never()).advanceCounters(anyLong(), anyInt(), anyInt());
        // 任务表一个字都不该动
        verify(taskRepository, never())
                .update(ArgumentMatchers.<RecognitionTask>any(), ArgumentMatchers.<Wrapper<RecognitionTask>>any());
    }

    @Test
    @DisplayName("⑧ 闸门 SQL：SET status/error_message，WHERE id AND status NOT IN(SUCCESS,FAILED)，且 null 真被绑定")
    void gateSqlShape() {
        preemptOk(runningTask());

        deliver(successMessage(IMAGE_ID));

        LambdaUpdateWrapper<RecognitionImage> wrapper = capturedGateWrapper();
        String where = wrapper.getCustomSqlSegment();

        assertThat(wrapper.getSqlSet())
                .as("要同时写回状态与失败原因")
                .contains("status=")
                .contains("error_message=");
        assertThat(where)
                .as("终态判断必须下沉到 WHERE")
                .contains("id =")
                .contains("status NOT IN");
        assertThat(where)
                .as("⑨-B 任务生命周期守卫：图像守卫与任务守卫压进同一条原子 UPDATE，"
                        + "取消后迟到的结果连图像都认领不了（图像状态保护 ≠ 任务状态保护）")
                .contains("task_id IN")
                .contains("SELECT id FROM recognition_task WHERE status IN ('PENDING', 'PROCESSING')");

        assertThat(wrapper.getParamNameValuePairs().values())
                .as("id + 两个终态字面量 + 状态值都在参数表里")
                .contains(IMAGE_ID, "SUCCESS", "FAILED")
                .as("error_message=null 必须真的作为参数绑定：updateById(实体) 的 NOT_NULL 策略会跳过 null，"
                        + "那样重试成功时旧的失败原因就清不掉")
                .containsNull();
    }

    @Test
    @DisplayName("⑧ 失败结果：不写 detection_result，但状态与失败原因照样回写，计数走 failedDelta")
    void failedResult_skipsResultsButStillMarksImage() {
        RecognitionTask task = runningTask();
        preemptOk(task);

        deliver(failedMessage(IMAGE_ID, "CUDA out of memory"));

        verify(resultService, never()).saveBatch(any(), any(), anyList());
        assertThat(capturedGateWrapper().getParamNameValuePairs().values())
                .contains("FAILED", "CUDA out of memory", IMAGE_ID);

        // 增量由 Service 决定 —— 失败走 failedDelta=1，成功数不动
        verify(taskRepository).advanceCounters(TASK_ID, 0, 1);
        assertThat(task.getFailedCount()).isEqualTo(1);
        assertThat(task.getSuccessCount()).as("失败不该动成功数").isEqualTo(3);
    }

    @Test
    @DisplayName("⑧ 每张图少一次 SELECT：处理链路里不再 selectById(imageId)")
    void noPerImageSelect() {
        preemptOk(runningTask());

        deliver(successMessage(IMAGE_ID));

        verify(imageRepository, never()).selectById(any());
        // 任务快照仍然只读一次（saveBatch 要用 modelId），⑨-A 没有给它加查询
        verify(taskRepository).selectById(TASK_ID);
    }

    // ── ⑨-A 任务计数原子化 ──────────────────────────────────────────────────

    @Test
    @DisplayName("⑨-A 计数 UPDATE 形状：增量式 + SQL 派生进度 + 状态同语句流转，且 processed_count 落在 SET 最后")
    void advanceSqlShape() {
        preemptOk(runningTask());

        deliver(successMessage(IMAGE_ID));

        UpdateWrapper<RecognitionTask> wrapper = capturedAdvanceWrapper();
        String set = wrapper.getSqlSet();
        String where = wrapper.getCustomSqlSegment();

        assertThat(set)
                .as("计数必须是数据库侧增量，而不是 Java 算好再写固定值")
                .contains("processed_count = processed_count + 1")
                .contains("success_count = success_count + ")
                .contains("failed_count = failed_count + ");
        assertThat(set)
                .as("进度由 SQL 派生，并带 100 上限与除零保护")
                .contains("LEAST(ROUND(")
                .contains("100.00");
        assertThat(set)
                .as("PENDING→PROCESSING 与达标→COMPLETED 必须在同一条 UPDATE 里完成")
                .contains("'COMPLETED'")
                .contains("'PROCESSING'")
                .contains("COALESCE(start_time, NOW())")
                .contains("COALESCE(finish_time, NOW())");

        assertThat(set.indexOf("processed_count = processed_count + 1"))
                .as("⚠️ processed_count 的赋值必须排在所有引用它的表达式之后："
                        + "MySQL 单表 UPDATE 从左到右求值且后赋值可见新值，放前面会让 (processed_count + 1) 变成 (旧值 + 2)，"
                        + "进度整轮多算一格（真库探针实测 66.67 vs 33.33）")
                .isGreaterThan(set.indexOf("LEAST(ROUND("))
                .isGreaterThan(set.indexOf("'COMPLETED'"));

        assertThat(set)
                .as("反证整行 RMW 被消灭：这些与计数无关的列一个都不许再写")
                .doesNotContain("task_name")
                .doesNotContain("user_id")
                .doesNotContain("model_id")
                .doesNotContain("create_time");

        assertThat(where)
                .as("状态守卫必须下沉到 WHERE：终态任务冻结")
                .contains("id =")
                .contains("status IN");

        assertThat(wrapper.getParamNameValuePairs().values())
                .as("taskId + 两个增量 + 两个允许状态都在参数表里")
                .contains(TASK_ID, 1, 0, "PENDING", "PROCESSING");

        // 本用例直接对 wrapper 渲染出的 SQL 形状与绑定参数做断言：
        // 不必启动 Spring、连 MySQL，就能验证 MyBatis 即将执行的语句（@Update 注解做不到这点）。
    }

    @Test
    @DisplayName("⑨-A 状态守卫拦下（affected=0）：不回写、不改本地快照，绝不推假的\"已完成\"")
    void guardBlocked_keepsLocalSnapshot() {
        RecognitionTask task = runningTask();
        when(taskRepository.selectById(TASK_ID)).thenReturn(task);
        when(imageRepository.update(any(), any())).thenReturn(1);
        // 任务已被取消/已完成 → 守卫拦下，UPDATE 影响 0 行
        when(taskRepository.update(isNull(), any())).thenReturn(0);

        deliver(successMessage(IMAGE_ID));

        verify(taskRepository).advanceCounters(TASK_ID, 1, 0);
        assertThat(task.getProcessedCount())
                .as("库里的计数没变，本地快照就不该被推进；否则广播会推出一个凭空的进度")
                .isEqualTo(3);
        assertThat(task.getStatus())
                .as("尤其不能把本地状态预测成 COMPLETED —— 那会给前端推一条假的\"识别完成\"")
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("⑨-A 本地预测序列与真库探针一致：total=6 时 16.67/33.33/50.00/66.67/83.33/100.00，第 6 步翻 COMPLETED")
    void localPredictionMatchesDbProbeSeries() {
        double[] expected = {16.67, 33.33, 50.00, 66.67, 83.33, 100.00};

        for (int step = 1; step <= 6; step++) {
            RecognitionTask task = new RecognitionTask();
            task.setId(TASK_ID);
            task.setModelId(1L);
            task.setStatus("PROCESSING");
            task.setTotalCount(6);
            task.setProcessedCount(step - 1);
            task.setSuccessCount(step - 1);
            task.setFailedCount(0);
            preemptOk(task);

            deliver(successMessage(IMAGE_ID + step));

            assertThat(task.getProcessedCount()).as("第 %d 步 processed", step).isEqualTo(step);
            assertThat(task.getProgress()).as("第 %d 步 progress", step).isEqualTo(expected[step - 1]);
            assertThat(task.getStatus()).as("第 %d 步 status", step)
                    .isEqualTo(step == 6 ? "COMPLETED" : "PROCESSING");
        }
    }

    // ── ⑨-B 任务生命周期闸门 ────────────────────────────────────────────────

    /** 已被取消的任务快照（计数冻结在取消时刻）。 */
    private static RecognitionTask canceledTask() {
        RecognitionTask task = new RecognitionTask();
        task.setId(TASK_ID);
        task.setModelId(1L);
        task.setStatus("CANCELED");
        task.setTotalCount(10);
        task.setProcessedCount(3);
        task.setSuccessCount(3);
        task.setFailedCount(0);
        task.setProgress(30.0);
        return task;
    }

    /** 任务刚建好、还没有任何结果。 */
    private static RecognitionTask pendingTask() {
        RecognitionTask task = new RecognitionTask();
        task.setId(TASK_ID);
        task.setModelId(1L);
        task.setStatus("PENDING");
        task.setTotalCount(10);
        task.setProcessedCount(0);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        return task;
    }

    private static String taskLevelFailedMessage(String error) {
        return "{\"taskId\":" + TASK_ID + ",\"status\":\"FAILED\",\"errorMessage\":\"" + error + "\"}";
    }

    private static String taskStartedMessage() {
        return "{\"taskId\":" + TASK_ID + ",\"status\":\"STARTED\"}";
    }

    @Test
    @DisplayName("⑨-B 任务级 FAILED：只写 status+finish_time 的条件 UPDATE（不碰计数列），成功后收尾未完成图像")
    void taskLevelFailed_usesConditionalUpdate() {
        RecognitionTask task = runningTask();
        when(taskRepository.selectById(TASK_ID)).thenReturn(task);
        when(taskRepository.update(isNull(), any())).thenReturn(1);

        deliver(taskLevelFailedMessage("AI 引擎崩了"));

        UpdateWrapper<RecognitionTask> wrapper = capturedAdvanceWrapper();
        assertThat(wrapper.getSqlSet())
                .as("只拥有 status / finish_time 两列的写权限")
                .contains("status=")
                .contains("finish_time=");
        assertThat(wrapper.getSqlSet())
                .as("整行 RMW 被消灭：与状态无关的列一个都不许再写")
                .doesNotContain("processed_count")
                .doesNotContain("success_count")
                .doesNotContain("failed_count")
                .doesNotContain("progress")
                .doesNotContain("task_name")
                .doesNotContain("user_id")
                .doesNotContain("model_id")
                .doesNotContain("create_time");
        assertThat(wrapper.getCustomSqlSegment())
                .as("守卫下沉到 WHERE：只有非终态任务能被翻成 FAILED")
                .contains("id =")
                .contains("status IN");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("FAILED", "PENDING", "PROCESSING");

        verify(imageRepository).failPendingImages(TASK_ID, "AI 引擎崩了");
        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getFinishTime()).isNotNull();
    }

    @Test
    @DisplayName("⑨-B 迟到任务级 FAILED：守卫拦下（affected=0）→ CANCELED 不被翻成 FAILED，也不收尾图像")
    void lateTaskLevelFailed_cannotOverwriteCanceled() {
        RecognitionTask task = canceledTask();
        when(taskRepository.selectById(TASK_ID)).thenReturn(task);
        when(taskRepository.update(isNull(), any())).thenReturn(0);

        deliver(taskLevelFailedMessage("迟到的整批失败"));

        // 形状仍要正确 —— 挡住它的正是这条 WHERE 守卫
        UpdateWrapper<RecognitionTask> wrapper = capturedAdvanceWrapper();
        assertThat(wrapper.getCustomSqlSegment()).contains("status IN");

        verify(imageRepository, never()).update(any(), any());
        verify(taskRepository, never()).updateById(ArgumentMatchers.<RecognitionTask>any());
        assertThat(task.getStatus())
                .as("任务状态与本地快照都必须保持 CANCELED")
                .isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("⑨-B 迟到任务级 STARTED：任务已取消 → 一条 SQL 都不发，图像不会被\"复活\"成识别中")
    void lateStarted_cannotResurrectCanceledTask() {
        RecognitionTask task = canceledTask();
        when(taskRepository.selectById(TASK_ID)).thenReturn(task);

        deliver(taskStartedMessage());

        verify(taskRepository, never()).update(isNull(), any());
        verify(taskRepository, never()).updateById(ArgumentMatchers.<RecognitionTask>any());
        verify(imageRepository, never()).update(any(), any());
        assertThat(task.getStatus()).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("⑨-B 正常 STARTED：PENDING→PROCESSING 走条件 UPDATE（只写 status+start_time），图像批量带任务守卫")
    void started_pendingToProcessing_usesConditionalUpdate() {
        RecognitionTask task = pendingTask();
        when(taskRepository.selectById(TASK_ID)).thenReturn(task);
        when(taskRepository.update(isNull(), any())).thenReturn(1);

        deliver(taskStartedMessage());

        UpdateWrapper<RecognitionTask> wrapper = capturedAdvanceWrapper();
        assertThat(wrapper.getSqlSet())
                .as("开工也只写两列，不再整行回写")
                .contains("status=")
                .contains("start_time=");
        assertThat(wrapper.getSqlSet())
                .doesNotContain("processed_count")
                .doesNotContain("success_count")
                .doesNotContain("failed_count")
                .doesNotContain("progress")
                .doesNotContain("task_name")
                .doesNotContain("model_id")
                .doesNotContain("create_time");
        assertThat(wrapper.getCustomSqlSegment())
                .as("开工守卫比取消更严：只认 PENDING")
                .contains("id =")
                .contains("status =");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("PROCESSING", "PENDING");

        ArgumentCaptor<Wrapper<RecognitionImage>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(imageRepository).update(isNull(), captor.capture());
        @SuppressWarnings("unchecked")
        UpdateWrapper<RecognitionImage> imageWrapper = (UpdateWrapper<RecognitionImage>) captor.getValue();
        imageWrapper.getCustomSqlSegment();
        assertThat(imageWrapper.getCustomSqlSegment())
                .as("批量置为识别中也要确认任务还活着，否则会与取消并发把一批图复活")
                .contains("task_id IN")
                .contains("SELECT id FROM recognition_task WHERE status IN ('PENDING', 'PROCESSING')");

        verify(taskRepository, never()).updateById(ArgumentMatchers.<RecognitionTask>any());
        assertThat(task.getStatus()).isEqualTo("PROCESSING");
        assertThat(task.getStartTime()).isNotNull();
    }
}
