package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.ReviewRecord;
import com.wildlife.recognition.repository.DetectionResultRepository;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.ReviewRecordRepository;
import com.wildlife.recognition.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ReviewService 分页测试（低置信度待复核 + 复核记录）。
 *
 * 两个方法都改成了真分页，但返回形状故意不同，测试要分别盯住：
 * <ul>
 *   <li>{@code listPending(taskId, maxConfidence, page, size, hideMissing)} —— 待人工复核的低置信度结果，
 *       返回手写 5 键 Map；筛选条件是 {@code review_status='PENDING'} + {@code confidence <= maxConfidence}，
 *       按置信度升序（最不可信的排最前）；</li>
 *   <li>{@code listRecords(taskId, page, size)} —— 复核记录，接口契约未变，
 *       仍然返回裸 {@code List}，分页只在 SQL 层生效。</li>
 * </ul>
 *
 * 两处 taskId 都要求走数据库子查询，本类用 {@code verifyNoInteractions(imageRepository)}
 * 证明没有把 imageId 拉进 Java 内存。
 *
 * <p>⑩ 起 {@code listPending} 多了一个 {@code hideMissing} 开关与 {@code imageAvailable}
 * 标记，可用性判断被抽到 {@link ImageAvailabilityService}。这里把它 mock 掉：
 * 一是为了让上面那条「不碰 imageRepository」的断言依然成立（真实现要遍历图像表判存），
 * 二是让"哪些图算丢失"由用例自己指定，测试不依赖本机磁盘。
 */
class ReviewServiceTest {

    private DetectionResultRepository resultRepository;
    private ReviewRecordRepository reviewRecordRepository;
    private ImageRepository imageRepository;
    private ImageAvailabilityService imageAvailability;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        resultRepository = mock(DetectionResultRepository.class);
        reviewRecordRepository = mock(ReviewRecordRepository.class);
        imageRepository = mock(ImageRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        // 默认：没有任何图丢失，且所有结果都可复核（review() 的守卫放行）
        imageAvailability = mock(ImageAvailabilityService.class);
        when(imageAvailability.missingImageIds()).thenReturn(Set.of());
        when(imageAvailability.isAvailable(any())).thenReturn(true);

        reviewService = new ReviewService(resultRepository, reviewRecordRepository,
                imageRepository, userRepository, imageAvailability);
    }

    private void stubPendingPage(long total, List<DetectionResult> records) {
        when(resultRepository.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<DetectionResult> page = invocation.getArgument(0);
            page.setTotal(total);
            page.setRecords(records);
            return page;
        });
    }

    private void stubRecordPage(long total, List<ReviewRecord> records) {
        when(reviewRecordRepository.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<ReviewRecord> page = invocation.getArgument(0);
            page.setTotal(total);
            page.setRecords(records);
            return page;
        });
    }

    @SuppressWarnings("unchecked")
    private Page<DetectionResult> capturePendingPageRequest() {
        ArgumentCaptor<Page<DetectionResult>> captor = ArgumentCaptor.forClass(Page.class);
        verify(resultRepository).selectPage(captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<DetectionResult> capturePendingWrapper() {
        ArgumentCaptor<QueryWrapper<DetectionResult>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(resultRepository).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<ReviewRecord> captureRecordWrapper() {
        ArgumentCaptor<QueryWrapper<ReviewRecord>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(reviewRecordRepository).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private static DetectionResult lowConfidenceResult(long id, long imageId, double conf) {
        DetectionResult r = new DetectionResult();
        r.setId(id);
        r.setImageId(imageId);
        r.setClassName("未知");
        r.setConfidence(conf);
        r.setReviewStatus("PENDING");
        return r;
    }

    private static ReviewRecord record(long id, long resultId) {
        ReviewRecord rec = new ReviewRecord();
        rec.setId(id);
        rec.setResultId(resultId);
        rec.setReviewStatus("CONFIRMED");
        return rec;
    }

    @Test
    @DisplayName("listPending：PENDING + confidence<=maxConfidence，按置信度升序，返回 5 键 Map")
    void listPending_filtersLowConfidencePending_andOrdersByConfidenceAsc() {
        List<DetectionResult> records = List.of(
                lowConfidenceResult(1L, 2048L, 0.21),
                lowConfidenceResult(2L, 2049L, 0.33));
        stubPendingPage(9L, records);

        Map<String, Object> map = reviewService.listPending(7L, 0.6, 1, 10, false);

        Page<DetectionResult> pageRequest = capturePendingPageRequest();
        assertThat(pageRequest.getCurrent()).isEqualTo(1L);
        assertThat(pageRequest.getSize()).isEqualTo(10L);

        QueryWrapper<DetectionResult> wrapper = capturePendingWrapper();
        String sql = wrapper.getCustomSqlSegment();
        assertThat(sql).contains("review_status").contains("confidence").contains("image_id");
        assertThat(sql)
                .as("taskId 必须走子查询")
                .contains("SELECT id FROM recognition_image WHERE task_id = 7");
        assertThat(sql)
                .as("待复核列表要按置信度升序，最不可信的排最前")
                .contains("confidence");
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("PENDING 与 maxConfidence 都要绑定成参数")
                .contains("PENDING", 0.6);

        assertThat(map).containsOnlyKeys("total", "page", "size", "pages", "list");
        assertThat(map.get("total")).isEqualTo(9L);
        assertThat(map.get("pages")).as("9/10 → 1 页").isEqualTo(1L);
        assertThat(map.get("list")).isEqualTo(records);
    }

    @Test
    @DisplayName("listPending(taskId=null)：不带任务子查询，仍按 PENDING 过滤")
    void listPending_withoutTaskId_skipsTaskSubquery() {
        stubPendingPage(0L, List.of());

        reviewService.listPending(null, null, 1, 20, false);

        QueryWrapper<DetectionResult> wrapper = capturePendingWrapper();
        String sql = wrapper.getCustomSqlSegment();
        assertThat(sql).contains("review_status");
        assertThat(sql)
                .as("没传 taskId 就不该出现任务子查询")
                .doesNotContain("SELECT id FROM recognition_image");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("PENDING");

        verifyNoInteractions(imageRepository);
    }

    @Test
    @DisplayName("listPending：page/size 夹取到 [1, 200]")
    void listPending_clampsPageAndSize() {
        stubPendingPage(0L, List.of());

        reviewService.listPending(7L, 0.5, 0, 0, false);

        Page<DetectionResult> pageRequest = capturePendingPageRequest();
        assertThat(pageRequest.getCurrent()).isEqualTo(1L);
        assertThat(pageRequest.getSize()).isEqualTo(1L);
    }

    @Test
    @DisplayName("listRecords(taskId)：三层子查询落到 SQL，返回裸 List（契约未变），不碰 imageRepository")
    void listRecords_usesNestedSubquery_andStillReturnsBareList() {
        List<ReviewRecord> records = List.of(record(11L, 1L), record(12L, 2L));
        stubRecordPage(2L, records);

        List<ReviewRecord> returned = reviewService.listRecords(7L, 1, 20);

        // 契约：仍然返回裸 List，不是 Map —— 前端 records 接口按数组读
        assertThat(returned).isEqualTo(records);

        QueryWrapper<ReviewRecord> wrapper = captureRecordWrapper();
        String sql = wrapper.getCustomSqlSegment();
        assertThat(sql).contains("result_id");
        assertThat(sql)
                .as("复核记录 → 结果 → 图像 → 任务，三层关系必须都在数据库里完成")
                .contains("SELECT id FROM detection_result WHERE image_id IN ("
                        + "SELECT id FROM recognition_image WHERE task_id = 7)");
        assertThat(sql).contains("review_time");

        verifyNoInteractions(imageRepository);
    }

    @Test
    @DisplayName("两个分页方法都只用 selectPage，不用 selectList 查全表")
    void bothPagedMethods_neverUseSelectList() {
        stubPendingPage(0L, List.of());
        stubRecordPage(0L, List.of());

        reviewService.listPending(7L, 0.5, 1, 20, false);
        reviewService.listRecords(7L, 1, 20);

        verify(resultRepository, never()).selectList(any());
        verify(reviewRecordRepository, never()).selectList(any());
    }

    // ── ⑩ 原图已丢失（悬空引用）不得进入复核链路 ──────────────────────────────

    @Test
    @DisplayName("listPending(hideMissing=true)：缺失项在 SQL 里 NOT IN 掉，当前页逐条打 imageAvailable")
    void listPending_hideMissing_filtersInSql_andMarksAvailability() {
        DetectionResult ok = lowConfidenceResult(1L, 2048L, 0.21);
        DetectionResult lost = lowConfidenceResult(2L, 9999L, 0.33);
        stubPendingPage(2L, List.of(ok, lost));
        when(imageAvailability.missingImageIds()).thenReturn(Set.of(9999L));

        reviewService.listPending(null, null, 1, 20, true);

        QueryWrapper<DetectionResult> wrapper = capturePendingWrapper();
        assertThat(wrapper.getCustomSqlSegment())
                .as("过滤必须落在 SQL 里 —— 在 Java 里「取一页再筛掉几条」会让页码错位")
                .contains("NOT IN");

        assertThat(ok.getImageAvailable()).as("在盘上的图标记为可用").isTrue();
        assertThat(lost.getImageAvailable()).as("文件已丢失的图标记为不可用").isFalse();
    }

    @Test
    @DisplayName("listPending(hideMissing=false)：仍返回缺失项，但同样标成不可用")
    void listPending_withoutHideMissing_stillMarksAvailability() {
        DetectionResult lost = lowConfidenceResult(2L, 9999L, 0.33);
        stubPendingPage(1L, List.of(lost));
        when(imageAvailability.missingImageIds()).thenReturn(Set.of(9999L));

        reviewService.listPending(null, null, 1, 20, false);

        QueryWrapper<DetectionResult> wrapper = capturePendingWrapper();
        assertThat(wrapper.getCustomSqlSegment()).doesNotContain("NOT IN");
        assertThat(lost.getImageAvailable()).isFalse();
    }

    @Test
    @DisplayName("review：原图已丢失的结果必须拒绝 —— 看不到图就没有判据")
    void review_rejectsWhenOriginalImageMissing() {
        DetectionResult lost = lowConfidenceResult(2L, 9999L, 0.33);
        when(resultRepository.selectById(2L)).thenReturn(lost);
        when(imageAvailability.isAvailable(9999L)).thenReturn(false);

        assertThatThrownBy(() -> reviewService.review(2L, "CONFIRM", null, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原图已从磁盘丢失");

        // 关键：既没改结果状态，也没留下复核记录。
        // any() 必须带类型：BaseMapper 里 updateById 有 T 与 Collection<T> 两个重载，
        // 裸 any() 会让编译器无法在两者间选择。
        verify(resultRepository, never()).updateById(any(DetectionResult.class));
        verifyNoInteractions(reviewRecordRepository);
    }

    @Test
    @DisplayName("reviewBatch：混进原图丢失的项时跳过并计数，不让整批一起失败")
    void reviewBatch_skipsMissingImageItems() {
        DetectionResult ok = lowConfidenceResult(1L, 2048L, 0.21);
        DetectionResult lost = lowConfidenceResult(2L, 9999L, 0.33);
        when(resultRepository.selectById(1L)).thenReturn(ok);
        when(resultRepository.selectById(2L)).thenReturn(lost);
        when(imageAvailability.isAvailable(9999L)).thenReturn(false);

        ReviewService.BatchReviewResult result =
                reviewService.reviewBatch(List.of(1L, 2L), "CONFIRM", null, null, "admin");

        assertThat(result.reviewed()).as("可复核的那条照常生效").isEqualTo(1);
        assertThat(result.skippedMissing()).as("丢失的那条被跳过而不是让整批 400").isEqualTo(1);
    }
}
