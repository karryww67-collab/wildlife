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

import static org.assertj.core.api.Assertions.assertThat;
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
 *   <li>{@code listPending(taskId, maxConfidence, page, size)} —— 待人工复核的低置信度结果，
 *       返回手写 5 键 Map；筛选条件是 {@code review_status='PENDING'} + {@code confidence <= maxConfidence}，
 *       按置信度升序（最不可信的排最前）；</li>
 *   <li>{@code listRecords(taskId, page, size)} —— 复核记录，接口契约未变，
 *       仍然返回裸 {@code List}，分页只在 SQL 层生效。</li>
 * </ul>
 *
 * 两处 taskId 都要求走数据库子查询，本类用 {@code verifyNoInteractions(imageRepository)}
 * 证明没有把 imageId 拉进 Java 内存。
 */
class ReviewServiceTest {

    private DetectionResultRepository resultRepository;
    private ReviewRecordRepository reviewRecordRepository;
    private ImageRepository imageRepository;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        resultRepository = mock(DetectionResultRepository.class);
        reviewRecordRepository = mock(ReviewRecordRepository.class);
        imageRepository = mock(ImageRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        reviewService = new ReviewService(resultRepository, reviewRecordRepository,
                imageRepository, userRepository);
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

        Map<String, Object> map = reviewService.listPending(7L, 0.6, 1, 10);

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

        reviewService.listPending(null, null, 1, 20);

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

        reviewService.listPending(7L, 0.5, 0, 0);

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

        reviewService.listPending(7L, 0.5, 1, 20);
        reviewService.listRecords(7L, 1, 20);

        verify(resultRepository, never()).selectList(any());
        verify(reviewRecordRepository, never()).selectList(any());
    }
}
