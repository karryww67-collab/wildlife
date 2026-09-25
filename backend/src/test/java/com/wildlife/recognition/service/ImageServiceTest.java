package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wildlife.recognition.config.MybatisPlusConfig;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.repository.ImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImageService 分页测试。
 *
 * 注意方法名：项目里没有 listImages()，实际入口是
 * {@code list(Long taskId, String status, int page, int size)}，
 * 内部转调私有 {@code pageOf(...)} → {@code imageRepository.selectPage(...)}。
 *
 * 除了 service 自身的分页行为，这里还验证「让 selectPage 生效的那半边」——
 * {@link MybatisPlusConfig} 是否真的注册了 PaginationInnerInterceptor。
 * 少了插件，selectPage 不会改写 SQL，接口照样返回 200 但分页静默失效，
 * 所以这一条必须和 service 层一起测。
 */
class ImageServiceTest {

    private ImageRepository imageRepository;
    private ImageService imageService;

    /** ⑨-C 物理文件引用保护要用真实文件系统验证"文件还在不在"。 */
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        imageRepository = mock(ImageRepository.class);
        imageService = new ImageService(imageRepository);
    }

    private void stubSelectPage(long total, List<RecognitionImage> records) {
        when(imageRepository.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<RecognitionImage> page = invocation.getArgument(0);
            page.setTotal(total);
            page.setRecords(records);
            return page;
        });
    }

    @SuppressWarnings("unchecked")
    private Page<RecognitionImage> capturePageRequest() {
        ArgumentCaptor<Page<RecognitionImage>> captor = ArgumentCaptor.forClass(Page.class);
        verify(imageRepository).selectPage(captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<RecognitionImage> captureWrapper() {
        ArgumentCaptor<QueryWrapper<RecognitionImage>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(imageRepository).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private static RecognitionImage image(long id, long taskId) {
        RecognitionImage img = new RecognitionImage();
        img.setId(id);
        img.setTaskId(taskId);
        img.setFileName("img-" + id + ".jpg");
        return img;
    }

    @Test
    @DisplayName("list：page/size 进 selectPage，返回 5 键 Map，total/pages 与分页结果一致")
    void list_usesSelectPage_andReturnsFiveKeyMap() {
        List<RecognitionImage> records = List.of(image(2048L, 10001L), image(2049L, 10001L));
        stubSelectPage(57L, records);

        Map<String, Object> result = imageService.list(10001L, null, 1, 10);

        Page<RecognitionImage> pageRequest = capturePageRequest();
        assertThat(pageRequest.getCurrent()).isEqualTo(1L);
        assertThat(pageRequest.getSize()).isEqualTo(10L);

        assertThat(result).containsOnlyKeys("total", "page", "size", "pages", "list");
        assertThat(result.get("total")).isEqualTo(57L);
        assertThat(result.get("page")).isEqualTo(1L);
        assertThat(result.get("size")).isEqualTo(10L);
        assertThat(result.get("pages")).as("57/10 → 6 页").isEqualTo(6L);
        assertThat(result.get("list")).isEqualTo(records);
    }

    @Test
    @DisplayName("list：taskId 与 status 都进 SQL，排序为 create_time DESC + id DESC")
    void list_buildsTaskAndStatusConditions() {
        stubSelectPage(0L, List.of());

        imageService.list(10001L, "SUCCESS", 1, 20);

        QueryWrapper<RecognitionImage> wrapper = captureWrapper();
        String sql = wrapper.getCustomSqlSegment();

        assertThat(sql).contains("task_id").contains("status").contains("create_time");
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("taskId=10001 与 status=SUCCESS 都应作为参数绑定")
                .contains(10001L, "SUCCESS");
    }

    @Test
    @DisplayName("list：size 越界夹到 200，page<=0 夹到 1")
    void list_clampsPageAndSize() {
        stubSelectPage(0L, List.of());

        imageService.list(null, null, 0, 5000);

        Page<RecognitionImage> pageRequest = capturePageRequest();
        assertThat(pageRequest.getCurrent()).isEqualTo(1L);
        assertThat(pageRequest.getSize()).isEqualTo(200L);
    }

    @Test
    @DisplayName("list：不查全表、不用 selectList —— 伪分页回潮哨兵")
    void list_neverFallsBackToSelectList() {
        stubSelectPage(0L, List.of());

        imageService.list(10001L, "WAITING", 3, 24);

        verify(imageRepository, never()).selectList(any());
    }

    @Test
    @DisplayName("MybatisPlusConfig：分页插件真的注册了（MYSQL / maxLimit=200 / overflow=false）")
    void mybatisPlusConfig_registersPaginationInnerInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

        List<InnerInterceptor> inners = interceptor.getInterceptors();
        assertThat(inners).as("应注册且只注册一个内部拦截器").hasSize(1);

        InnerInterceptor first = inners.get(0);
        assertThat(first).isInstanceOf(PaginationInnerInterceptor.class);

        PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) first;
        assertThat(pagination.getDbType()).as("方言必须是 MySQL").isEqualTo(DbType.MYSQL);
        assertThat(pagination.getMaxLimit()).as("单页上限 200，与 service 的夹取口径一致").isEqualTo(200L);
        assertThat(pagination.isOverflow()).as("overflow=false：页码越界不自动回第一页").isFalse();
    }

    // ── ⑨-C 物理文件共享保护：删行前先数引用 ────────────────────────────────

    private static RecognitionImage imageWithFile(long id, long taskId, Path file) {
        RecognitionImage img = new RecognitionImage();
        img.setId(id);
        img.setTaskId(taskId);
        img.setFileName("f.jpg");
        img.setFilePath(file.toString().replace('\\', '/'));
        img.setFileSize(3L);
        img.setStatus("SUCCESS");
        return img;
    }

    @Test
    @DisplayName("⑨-C delete：同一 file_path 仍被其它行引用 → 只删行，物理文件保留")
    void delete_keepsPhysicalFileWhenStillReferenced() throws IOException {
        Path file = tempDir.resolve("shared.jpg");
        Files.writeString(file, "x");
        RecognitionImage img = imageWithFile(355L, 12L, file);
        when(imageRepository.selectById(355L)).thenReturn(img);
        when(imageRepository.countOtherRefs(img.getFilePath(), 355L)).thenReturn(1L);

        assertThat(imageService.delete(355L)).isTrue();

        assertThat(Files.exists(file)).as("还有别的任务在用这个文件 → 必须保留").isTrue();
        verify(imageRepository).deleteById(355L);
    }

    @Test
    @DisplayName("⑨-C delete：最后一个引用 → 行与物理文件一起消失")
    void delete_removesPhysicalFileWhenLastReference() throws IOException {
        Path file = tempDir.resolve("last.jpg");
        Files.writeString(file, "x");
        RecognitionImage img = imageWithFile(546L, 31L, file);
        when(imageRepository.selectById(546L)).thenReturn(img);
        when(imageRepository.countOtherRefs(img.getFilePath(), 546L)).thenReturn(0L);

        assertThat(imageService.delete(546L)).isTrue();

        assertThat(Files.exists(file)).as("没有别的引用 → 物理删除").isFalse();
        verify(imageRepository).deleteById(546L);
    }

    @Test
    @DisplayName("⑨-C deleteByTask：共享文件保留、独占文件删除")
    void deleteByTask_keepsSharedFilesAndRemovesExclusiveOnes() throws IOException {
        Path shared = tempDir.resolve("shared.jpg");
        Path own = tempDir.resolve("own.jpg");
        Files.writeString(shared, "x");
        Files.writeString(own, "y");
        RecognitionImage a = imageWithFile(1L, 12L, shared);
        RecognitionImage b = imageWithFile(2L, 12L, own);
        when(imageRepository.selectList(any())).thenReturn(List.of(a, b));
        when(imageRepository.countOtherRefs(a.getFilePath(), 1L)).thenReturn(1L);
        when(imageRepository.countOtherRefs(b.getFilePath(), 2L)).thenReturn(0L);

        assertThat(imageService.deleteByTask(12L)).isEqualTo(2);

        assertThat(Files.exists(shared)).as("被派生任务共享 → 保留").isTrue();
        assertThat(Files.exists(own)).as("没有别的引用 → 删除").isFalse();
        verify(imageRepository).delete(any(QueryWrapper.class));
    }
}
