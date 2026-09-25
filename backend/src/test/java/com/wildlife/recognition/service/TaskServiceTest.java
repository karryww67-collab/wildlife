package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wildlife.recognition.entity.ModelVersion;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.entity.RecognitionTask;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.TaskRepository;
import com.wildlife.recognition.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskService.listAll 真分页测试。
 *
 * 验证目标是「分页真的下沉到数据库」这件事本身，而不是「代码能跑」：
 * <ol>
 *   <li>page/size 被原样交给 {@code selectPage}（而不是查全表再在 Java 里切）；</li>
 *   <li>返回的是手写 5 键 Map，键名与前端契约一致；</li>
 *   <li>分页参数越界时按 current=max(page,1)、pageSize=min(max(size,1),200) 夹紧；</li>
 *   <li>status 筛选没有被分页改造弄丢；</li>
 *   <li>{@code selectList} 一次都不许调用 —— 这条是防"伪分页回潮"的哨兵。</li>
 * </ol>
 *
 * <p><b>⑨-B 取消竞态（后三例）</b>：{@code cancelTask} 原来走「selectById 读整行 → 改两个字段
 * → {@code updateById} 整行写回」，而它跑在 Tomcat HTTP 线程、{@code advanceCounters} 跑在调度线程，
 * 两者并发时 Java 侧那份陈旧计数会把新计数踏掉（真库临时表探针复现：先把计数从 2 推到 5，
 * 整行取消写回后变回 2）。现在改成只写 status + finish_time 的条件 UPDATE，这三例把
 * 「写权限只有两列」「守卫拦下时不收尾图像」「全程没有 updateById」三件事钉死，
 * 并顺带证明语义 β 的收尾动作（未出结果的图像 → FAILED）确实被触发。
 *
 * 用 Mockito 直接替换仓储，不启动 Spring、不连 MySQL，因此毫秒级、无外部依赖。
 * 模拟分页插件的办法：在 {@code selectPage} 的桩里给传入的 Page 填 total/records，
 * 这正是 {@code PaginationInnerInterceptor} 在真实查询后所做的事。
 */
class TaskServiceTest {

    private TaskRepository taskRepository;
    private ImageRepository imageRepository;
    private ImageService imageService;
    private ModelService modelService;
    private StringRedisTemplate redisTemplate;
    /** 投递队列用的 ListOperations —— 放在字段上，⑨-C 的用例要抓投递出去的消息体。 */
    private ListOperations<String, String> redisOps;
    private TaskService taskService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        imageRepository = mock(ImageRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        imageService = mock(ImageService.class);
        RecognitionResultService recognitionResultService = mock(RecognitionResultService.class);
        modelService = mock(ModelService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        redisOps = (ListOperations<String, String>) mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(redisOps);

        // ⑨-B：cancelIfActive 是 Mapper 接口的 default 方法，默认在 mock 上不会执行。
        // 让它走真实实现（从而真的构建 UpdateWrapper），再拦 update(entity, wrapper) 抓 wrapper 验 SQL 形状。
        doCallRealMethod().when(taskRepository).cancelIfActive(anyLong(), any(LocalDateTime.class));

        taskService = new TaskService(taskRepository, imageRepository, userRepository, imageService,
                recognitionResultService, modelService, redisTemplate);
    }

    /** 模拟分页插件：把 total / records 填进 service 传进来的那个 Page 对象。 */
    private void stubSelectPage(long total, List<RecognitionTask> records) {
        when(taskRepository.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<RecognitionTask> page = invocation.getArgument(0);
            page.setTotal(total);
            page.setRecords(records);
            return page;
        });
    }

    @SuppressWarnings("unchecked")
    private Page<RecognitionTask> capturePageRequest() {
        ArgumentCaptor<Page<RecognitionTask>> captor = ArgumentCaptor.forClass(Page.class);
        verify(taskRepository).selectPage(captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<RecognitionTask> captureWrapper() {
        ArgumentCaptor<QueryWrapper<RecognitionTask>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(taskRepository).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private static RecognitionTask task(long id) {
        RecognitionTask t = new RecognitionTask();
        t.setId(id);
        t.setTaskName("任务-" + id);
        return t;
    }

    @Test
    @DisplayName("listAll(page=1, size=10)：分页参数进 selectPage，返回 5 键 Map，total/pages 正确")
    void listAll_page1size10_returnsFiveKeyMapWithCorrectTotalAndPages() {
        List<RecognitionTask> records = List.of(task(10001L), task(10002L));
        stubSelectPage(35L, records);

        Map<String, Object> result = taskService.listAll(null, 1, 10);

        // ① page/size 真的传给了 selectPage
        Page<RecognitionTask> pageRequest = capturePageRequest();
        assertThat(pageRequest.getCurrent()).as("current 应为 1").isEqualTo(1L);
        assertThat(pageRequest.getSize()).as("size 应为 10").isEqualTo(10L);

        // ② 返回键与前端契约一致（Page 直传会被序列化成 records/current，前端静默拿空数组）
        assertThat(result).containsOnlyKeys("total", "page", "size", "pages", "list");

        // ③ 值来自分页结果：35 条 / 每页 10 → 4 页
        assertThat(result.get("total")).isEqualTo(35L);
        assertThat(result.get("page")).isEqualTo(1L);
        assertThat(result.get("size")).isEqualTo(10L);
        assertThat(result.get("pages")).isEqualTo(4L);
        assertThat(result.get("list")).isEqualTo(records);
    }

    @Test
    @DisplayName("listAll(size>200)：超过 maxLimit 被夹到 200，单页不会被一次拉爆")
    void listAll_oversizedSize_isClampedTo200() {
        stubSelectPage(1000L, List.of());

        Map<String, Object> result = taskService.listAll(null, 1, 999);

        Page<RecognitionTask> pageRequest = capturePageRequest();
        assertThat(pageRequest.getSize()).as("size 应被夹到 200").isEqualTo(200L);
        assertThat(result.get("size")).isEqualTo(200L);
        assertThat(result.get("pages")).as("1000/200 = 5 页").isEqualTo(5L);
    }

    @Test
    @DisplayName("listAll(page<=0 或 size<=0)：夹到 1，不产生负数 OFFSET")
    void listAll_nonPositivePageOrSize_isClampedTo1() {
        stubSelectPage(0L, List.of());

        Map<String, Object> result = taskService.listAll(null, 0, 0);

        Page<RecognitionTask> pageRequest = capturePageRequest();
        assertThat(pageRequest.getCurrent()).as("page=0 → 1").isEqualTo(1L);
        assertThat(pageRequest.getSize()).as("size=0 → 1").isEqualTo(1L);
        assertThat(result.get("total")).isEqualTo(0L);
        assertThat(result.get("pages")).as("total=0 → pages=0").isEqualTo(0L);
        assertThat((List<?>) result.get("list")).as("空页返回空 List 而非 null").isEmpty();
    }

    @Test
    @DisplayName("listAll(status)：状态筛选仍生效，且大写归一化、create_time+id 排序保留")
    void listAll_statusFilter_survivesPaginationRewrite() {
        stubSelectPage(0L, List.of());

        taskService.listAll("pending", 1, 20);

        QueryWrapper<RecognitionTask> wrapper = captureWrapper();
        String sql = wrapper.getCustomSqlSegment();

        assertThat(sql).as("status 条件应出现在 SQL 里").contains("status");
        assertThat(sql).as("排序必须保留，否则同秒任务翻页会重复/漏项").contains("create_time");
        assertThat(sql).as("id 作为 tiebreaker 也要在").contains("id");
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("status 传入小写 pending 应被归一化成 PENDING")
                .contains("PENDING");
    }

    @Test
    @DisplayName("listAll：全程不调用 selectList —— 伪分页回潮哨兵")
    void listAll_neverFallsBackToSelectList() {
        stubSelectPage(0L, List.of());

        taskService.listAll("PENDING", 2, 50);

        verify(taskRepository, never()).selectList(any());
    }

    // ── ⑨-B 取消竞态：条件 UPDATE + 未终态图像收尾 ──────────────────────────

    /** 一个进行中、计数已经推进到 5/10 的任务。 */
    private static RecognitionTask runningTask() {
        RecognitionTask task = new RecognitionTask();
        task.setId(9L);
        task.setTaskName("r9b-cancel");
        task.setStatus("PROCESSING");
        task.setTotalCount(10);
        task.setProcessedCount(5);
        task.setSuccessCount(4);
        task.setFailedCount(1);
        task.setProgress(50.0);
        return task;
    }

    /** 抓 cancelIfActive 真正交给 MyBatis 的那个 wrapper，用来离线检查 SQL。 */
    @SuppressWarnings("unchecked")
    private UpdateWrapper<RecognitionTask> capturedCancelWrapper() {
        ArgumentCaptor<Wrapper<RecognitionTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskRepository).update(isNull(), captor.capture());
        UpdateWrapper<RecognitionTask> wrapper = (UpdateWrapper<RecognitionTask>) captor.getValue();
        wrapper.getSqlSet();            // 渲染 SET 段
        wrapper.getCustomSqlSegment();  // 渲染 WHERE 段（这一步才会登记 WHERE 的参数）
        return wrapper;
    }

    @Test
    @DisplayName("⑨-B cancelTask：只写 status+finish_time 的条件 UPDATE —— 陈旧计数再没有机会被写回")
    void cancelTask_writesOnlyTwoColumns() {
        when(taskRepository.selectById(9L)).thenReturn(runningTask());
        when(taskRepository.update(isNull(), any())).thenReturn(1);
        when(imageRepository.failPendingImages(anyLong(), any())).thenReturn(3);

        RecognitionTask result = taskService.cancelTask(9L);

        UpdateWrapper<RecognitionTask> wrapper = capturedCancelWrapper();
        String set = wrapper.getSqlSet();
        String where = wrapper.getCustomSqlSegment();

        assertThat(set).as("取消操作只拥有 status / finish_time 两列的写权限")
                .contains("status=")
                .contains("finish_time=");
        assertThat(set).as("整行 RMW 被消灭：这些与取消无关的列一个都不许再写")
                .doesNotContain("processed_count")
                .doesNotContain("success_count")
                .doesNotContain("failed_count")
                .doesNotContain("progress")
                .doesNotContain("task_name")
                .doesNotContain("user_id")
                .doesNotContain("model_id")
                .doesNotContain("create_time");
        assertThat(where).as("取消自身也带状态守卫：已是终态的任务不会被二次覆盖")
                .contains("id =")
                .contains("status IN");
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("状态字面量与两个允许状态都在参数表里")
                .contains("CANCELED", "PENDING", "PROCESSING");
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("finish_time 作为参数绑定（不是 NOW()），返回给前端的快照才与库值一字不差")
                .contains(result.getFinishTime());

        // 语义 β：未出结果的图像就地收尾（理由固定），SUCCESS 的图由 SQL 的 NOT IN 排除在外
        verify(imageRepository).failPendingImages(9L, "任务已取消");
        assertThat(result.getStatus()).isEqualTo("CANCELED");
        assertThat(result.getFinishTime()).isNotNull();
    }

    @Test
    @DisplayName("⑨-B cancelTask：守卫拦下（affected=0）→ 报错、不收尾图像、没有任何整行写回")
    void cancelTask_guardBlocked_throwsAndSkipsImageClosing() {
        when(taskRepository.selectById(9L)).thenReturn(runningTask());
        when(taskRepository.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> taskService.cancelTask(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法取消");

        verify(imageRepository, never()).failPendingImages(anyLong(), any());
        verify(taskRepository, never()).updateById(any(RecognitionTask.class));
    }

    @Test
    @DisplayName("⑨-B cancelTask：已是终态 → 连条件 UPDATE 都不发，且全程没有 updateById（RMW 哨兵）")
    void cancelTask_alreadyTerminal_rejectedWithoutAnyWrite() {
        RecognitionTask task = runningTask();
        task.setStatus("COMPLETED");
        when(taskRepository.selectById(9L)).thenReturn(task);

        assertThatThrownBy(() -> taskService.cancelTask(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有排队中或进行中的任务可以取消");

        verify(taskRepository, never()).update(isNull(), any());
        verify(taskRepository, never()).updateById(any(RecognitionTask.class));
    }

    // ── ⑨-C retry 派生新任务：身份级隔离 + 幂等 + 原任务零写入 ────────────────

    /** 一个已取消、计数已收尾的原任务。 */
    private static RecognitionTask cancelledTask() {
        RecognitionTask task = new RecognitionTask();
        task.setId(9L);
        task.setTaskName("r9c-origin");
        task.setUserId(1L);
        task.setModelId(2L);
        task.setStatus("CANCELED");
        task.setTotalCount(2);
        task.setProcessedCount(2);
        task.setSuccessCount(1);
        task.setFailedCount(1);
        task.setProgress(100.0);
        task.setFinishTime(LocalDateTime.now());
        return task;
    }

    private static RecognitionImage image(long id, long taskId, String path) {
        RecognitionImage img = new RecognitionImage();
        img.setId(id);
        img.setTaskId(taskId);
        img.setFileName("a.jpg");
        img.setFilePath(path);
        img.setFileSize(123L);
        img.setStatus("FAILED");
        img.setErrorMessage("任务已取消");
        return img;
    }

    private static ModelVersion model(long id) {
        ModelVersion m = new ModelVersion();
        m.setId(id);
        m.setVersion("wildlife-v1.1");
        m.setModelPath("/models/wildlife-v1.1/best.pt");
        return m;
    }

    /** 让 insert 像真实 MyBatis-Plus 那样回填自增主键（mock 下不会自动回填，不补桩会得到假绿）。 */
    private void stubInsertBackfillsId(long generatedId) {
        when(taskRepository.insert(any(RecognitionTask.class))).thenAnswer(invocation -> {
            invocation.<RecognitionTask>getArgument(0).setId(generatedId);
            return 1;
        });
    }

    @SuppressWarnings("unchecked")
    private List<RecognitionImage> capturedCopies() {
        ArgumentCaptor<List<RecognitionImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(imageRepository).insertBatchInChunks(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("⑨-C retryTask：派生新任务（新 taskId + 新图像行 + 复用 file_path），原任务一行都不写")
    void retryTask_derivesNewTask_andWritesNothingOnOrigin() {
        RecognitionTask origin = cancelledTask();
        when(taskRepository.selectById(9L)).thenReturn(origin);
        when(modelService.getById(2L)).thenReturn(model(2L));
        stubInsertBackfillsId(99L);
        when(taskRepository.updateById(any(RecognitionTask.class))).thenReturn(1);
        when(taskRepository.selectList(any())).thenReturn(List.of());   // 尚未派生

        List<RecognitionImage> originImages = List.of(
                image(355L, 9L, "/shared-data/originals/a.jpg"),
                image(356L, 9L, "/shared-data/originals/b.jpg"));
        List<RecognitionImage> freshImages = List.of(
                image(546L, 99L, "/shared-data/originals/a.jpg"),
                image(547L, 99L, "/shared-data/originals/b.jpg"));
        when(imageService.listByTask(9L)).thenReturn(originImages);
        when(imageService.listByTask(99L)).thenReturn(freshImages);

        RecognitionTask fresh = taskService.retryTask(9L);

        // 新任务：新主键、派生命名、继承 user/model、状态 PENDING、计数从 0 开始
        assertThat(fresh.getId()).isEqualTo(99L);
        assertThat(fresh.getTaskName()).isEqualTo("r9c-origin·重试自#9");
        assertThat(fresh.getUserId()).isEqualTo(1L);
        assertThat(fresh.getModelId()).isEqualTo(2L);
        assertThat(fresh.getStatus()).isEqualTo("PENDING");
        assertThat(fresh.getTotalCount()).isEqualTo(2);
        assertThat(fresh.getProcessedCount()).isZero();
        assertThat(fresh.getSuccessCount()).isZero();
        assertThat(fresh.getFailedCount()).isZero();
        assertThat(fresh.getProgress()).isEqualTo(0.0);
        assertThat(fresh.getStartTime()).isNull();

        // 复制出来的是"新图像行"：主键位空、指向新任务、状态 WAITING、复用原 file_path
        List<RecognitionImage> copies = capturedCopies();
        assertThat(copies).hasSize(2);
        assertThat(copies).allSatisfy(copy -> {
            assertThat(copy.getId()).as("绝不复用原 imageId").isNull();
            assertThat(copy.getTaskId()).isEqualTo(99L);
            assertThat(copy.getStatus()).isEqualTo("WAITING");
            assertThat(copy.getErrorMessage()).isNull();
        });
        assertThat(copies).extracting(RecognitionImage::getFilePath)
                .containsExactly("/shared-data/originals/a.jpg", "/shared-data/originals/b.jpg");

        // 投递出去的消息带的是新 taskId + 新 imageId（旧消息因此不可能命中新任务）
        verify(imageService).listByTask(99L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisOps).rightPush(eq("wildlife:tasks"), payload.capture());
        assertThat(payload.getValue())
                .contains("\"taskId\":99")
                .contains("\"imageId\":546")
                .contains("\"imageId\":547")
                .doesNotContain("\"taskId\":9,");

        // N1：原任务全程零写入 —— updateById 只可能被 startTask 用来写新任务
        ArgumentCaptor<RecognitionTask> written = ArgumentCaptor.forClass(RecognitionTask.class);
        verify(taskRepository, atLeastOnce()).updateById(written.capture());
        assertThat(written.getAllValues()).extracting(RecognitionTask::getId).containsOnly(99L);

        // 原任务快照没有被就地改写（RMW 复活路径消失的最直接体现）
        assertThat(origin.getStatus()).isEqualTo("CANCELED");
        assertThat(origin.getProcessedCount()).isEqualTo(2);
        assertThat(origin.getSuccessCount()).isEqualTo(1);
        assertThat(origin.getFailedCount()).isEqualTo(1);
        assertThat(origin.getStartTime()).isNull();
    }

    @Test
    @DisplayName("⑨-C retryTask 幂等：已存在派生任务 → 直接复用，不建任务、不复制图像、不投递")
    void retryTask_existingDerivedTask_isReusedWithoutSecondDispatch() {
        when(taskRepository.selectById(9L)).thenReturn(cancelledTask());
        when(modelService.getById(2L)).thenReturn(model(2L));
        when(imageService.listByTask(9L)).thenReturn(List.of(image(355L, 9L, "/shared-data/a.jpg")));

        RecognitionTask existing = new RecognitionTask();
        existing.setId(77L);
        existing.setTaskName("r9c-origin·重试自#9");
        existing.setStatus("PENDING");
        when(taskRepository.selectList(any())).thenReturn(List.of(existing));

        RecognitionTask result = taskService.retryTask(9L);

        assertThat(result.getId()).as("复用已有派生任务").isEqualTo(77L);
        verify(taskRepository, never()).insert(any(RecognitionTask.class));
        verify(imageRepository, never()).insertBatchInChunks(any());
        verify(redisOps, never()).rightPush(any(), any());
        verify(taskRepository, never()).updateById(any(RecognitionTask.class));
    }

    @Test
    @DisplayName("⑨-C retryTask 命名：原名超长时截断原名而不截后缀，总长 ≤ 200 才能落库")
    void retryTask_truncatesOverlongOriginNameButKeepsSuffix() {
        RecognitionTask origin = cancelledTask();
        origin.setTaskName("长".repeat(300));
        when(taskRepository.selectById(9L)).thenReturn(origin);
        when(modelService.getById(2L)).thenReturn(model(2L));
        stubInsertBackfillsId(99L);
        when(taskRepository.updateById(any(RecognitionTask.class))).thenReturn(1);
        when(taskRepository.selectList(any())).thenReturn(List.of());
        when(imageService.listByTask(9L)).thenReturn(List.of(image(355L, 9L, "/shared-data/a.jpg")));
        when(imageService.listByTask(99L)).thenReturn(List.of(image(546L, 99L, "/shared-data/a.jpg")));

        RecognitionTask fresh = taskService.retryTask(9L);

        assertThat(fresh.getTaskName()).hasSizeLessThanOrEqualTo(200);
        assertThat(fresh.getTaskName()).as("后缀必须完整保留，否则幂等判据会失配")
                .endsWith("·重试自#9");
    }
}
