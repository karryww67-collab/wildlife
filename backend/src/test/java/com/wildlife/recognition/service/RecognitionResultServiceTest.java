package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.repository.DetectionResultRepository;
import com.wildlife.recognition.repository.ImageRepository;
import com.wildlife.recognition.repository.ModelVersionRepository;
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
 * RecognitionResultService 分页测试。
 *
 * 结果分页有两个筛选维度最容易出事，这里逐个盯死：
 * <ul>
 *   <li><b>taskId</b>：改造后必须走数据库子查询
 *       （{@code image_id IN (SELECT id FROM recognition_image WHERE task_id = ?)}），
 *       而不是先把任务下全部 imageId 拉进 Java 内存再 IN。
 *       本类用 {@code verifyNoInteractions(imageRepository)} 直接证明"一个 ID 都没查"；</li>
 *   <li><b>confidence / className / reviewStatus</b>：条件不能被分页改造弄丢。</li>
 * </ul>
 *
 * 注意参数顺序：{@code list(taskId, imageId, className, minConfidence, reviewStatus,
 * startTime, endTime, page, size)} —— 时间区间排在 page/size 之前，
 * 这两个 {@code LocalDateTime} 很容易漏掉。
 */
class RecognitionResultServiceTest {

    private DetectionResultRepository resultRepository;
    private ImageRepository imageRepository;
    private RecognitionResultService resultService;

    @BeforeEach
    void setUp() {
        resultRepository = mock(DetectionResultRepository.class);
        imageRepository = mock(ImageRepository.class);
        resultService = new RecognitionResultService(resultRepository, imageRepository,
                mock(ModelVersionRepository.class));
    }

    private void stubSelectPage(long total, List<DetectionResult> records) {
        when(resultRepository.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<DetectionResult> page = invocation.getArgument(0);
            page.setTotal(total);
            page.setRecords(records);
            return page;
        });
    }

    @SuppressWarnings("unchecked")
    private Page<DetectionResult> capturePageRequest() {
        ArgumentCaptor<Page<DetectionResult>> captor = ArgumentCaptor.forClass(Page.class);
        verify(resultRepository).selectPage(captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<DetectionResult> captureWrapper() {
        ArgumentCaptor<QueryWrapper<DetectionResult>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(resultRepository).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private static DetectionResult result(long id, long imageId) {
        DetectionResult r = new DetectionResult();
        r.setId(id);
        r.setImageId(imageId);
        r.setClassName("麂");
        r.setConfidence(0.87);
        return r;
    }

    @Test
    @DisplayName("list(taskId)：走数据库子查询，且完全不碰 imageRepository（没把 ID 拉进内存）")
    void list_byTaskId_usesDatabaseSubquery_withoutLoadingImageIds() {
        stubSelectPage(12L, List.of(result(1L, 2048L)));

        Map<String, Object> map = resultService.list(7L, null, null, null, null, null, null, 1, 10);

        QueryWrapper<DetectionResult> wrapper = captureWrapper();
        String sql = wrapper.getCustomSqlSegment();
        assertThat(sql).contains("image_id");
        assertThat(sql)
                .as("taskId 必须渲染成子查询，而非把 imageId 列表铺进 IN(...)")
                .contains("SELECT id FROM recognition_image WHERE task_id = 7");

        // 关键：整个调用过程对 imageRepository 零交互 = 没有"先查全部 imageId"这一步
        verifyNoInteractions(imageRepository);

        assertThat(map).containsOnlyKeys("total", "page", "size", "pages", "list");
        assertThat(map.get("total")).isEqualTo(12L);
        assertThat(map.get("pages")).as("12/10 → 2 页").isEqualTo(2L);
    }

    @Test
    @DisplayName("list(imageId)：imageId 优先，退化成等值条件（不再叠加任务子查询）")
    void list_byImageId_prefersEqualityOverTaskSubquery() {
        stubSelectPage(3L, List.of(result(1L, 2048L)));

        resultService.list(7L, 2048L, null, null, null, null, null, 1, 10);

        QueryWrapper<DetectionResult> wrapper = captureWrapper();
        String sql = wrapper.getCustomSqlSegment();
        assertThat(sql).contains("image_id");
        assertThat(sql)
                .as("传了 imageId 就该走 image_id = ?，不该再带任务子查询")
                .doesNotContain("SELECT id FROM recognition_image");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(2048L);
    }

    @Test
    @DisplayName("list：className / minConfidence / reviewStatus 三个条件都保留")
    void list_keepsClassConfidenceAndReviewStatusFilters() {
        stubSelectPage(0L, List.of());

        resultService.list(null, null, "麂", 0.5, "PENDING", null, null, 1, 20);

        QueryWrapper<DetectionResult> wrapper = captureWrapper();
        String sql = wrapper.getCustomSqlSegment();

        assertThat(sql).contains("class_name").contains("confidence").contains("review_status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("三个筛选值都应作为参数绑定")
                .contains("麂", 0.5, "PENDING");
    }

    @Test
    @DisplayName("list：page/size 夹取到 [1, 200]，返回的 page/size 与请求页一致")
    void list_clampsPageAndSize() {
        stubSelectPage(0L, List.of());

        Map<String, Object> map = resultService.list(null, null, null, null, null, null, null, -3, 100000);

        Page<DetectionResult> pageRequest = capturePageRequest();
        assertThat(pageRequest.getCurrent()).isEqualTo(1L);
        assertThat(pageRequest.getSize()).isEqualTo(200L);
        assertThat(map.get("page")).isEqualTo(1L);
        assertThat(map.get("size")).isEqualTo(200L);
    }

    @Test
    @DisplayName("list：分页走 selectPage，不再用 selectList 查全表")
    void list_usesSelectPageNotSelectList() {
        stubSelectPage(0L, List.of());

        resultService.list(7L, null, null, null, null, null, null, 2, 20);

        verify(resultRepository).selectPage(any(), any());
        verify(resultRepository, never()).selectList(any());
    }
}
