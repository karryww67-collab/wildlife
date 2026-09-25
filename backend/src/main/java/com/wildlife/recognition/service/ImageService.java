package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.repository.ImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 图像管理服务。
 *
 * 负责批量识别流水线的数据入口：把用户一次上传的多张图像落盘并登记入库，
 * 每张图像初始为 WAITING，纳入某个识别任务后由 AI 引擎逐张处理并回写状态。
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    /** 允许的图像扩展名。 */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "bmp", "webp", "tif", "tiff");

    /** 单张图像体积上限：50MB。 */
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    /** 单次批量上传数量上限。 */
    public static final int MAX_BATCH_SIZE = 200;

    /**
     * attachToTask 单条批量 UPDATE 最多携带的 ID 数。
     *
     * 不把全部 ID 塞进一个 IN (...) 里：10 万个 ID 会生成超长 SQL（占位符数量、
     * 网络包体、MySQL 解析开销都会跟着涨），按 1000 个一批切成 100 条语句更稳。
     */
    private static final int ATTACH_BATCH_SIZE = 1000;

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ImageRepository imageRepository;

    /** 图像落盘根目录，与 AI 引擎共享（AI 端按此路径直接读取）。 */
    @Value("${app.image.storage-path:../ai-engine/data/originals}")
    private String storagePath;

    public ImageService(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    // ── 上传 ────────────────────────────────────────────────────────────────

    /** 单张上传：校验 → 落盘 → 入库。 */
    public RecognitionImage upload(MultipartFile file) throws IOException {
        validate(file);

        String originalName = originalName(file);
        String extension = extensionOf(originalName);
        Path target = store(file, extension);

        RecognitionImage image = new RecognitionImage();
        image.setFileName(originalName);
        image.setFilePath(target.toString().replace("\\", "/"));
        image.setFileSize(file.getSize());
        image.setStatus("WAITING");
        image.setCreateTime(LocalDateTime.now());
        imageRepository.insert(image);

        log.info("图像入库: id={}, name={}, size={}B", image.getId(), image.getFileName(), image.getFileSize());
        return image;
    }

    /**
     * 批量上传。
     * 单张失败不影响其余图片，逐张返回结果，前端可据此提示哪几张没成功。
     */
    public BatchUploadResult uploadBatch(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("files 不能为空");
        }
        if (files.length > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("单次批量上传不能超过 " + MAX_BATCH_SIZE + " 张，请分批上传");
        }

        List<RecognitionImage> images = new ArrayList<>();
        List<Map<String, String>> failures = new ArrayList<>();

        for (MultipartFile file : files) {
            try {
                images.add(upload(file));
            } catch (Exception e) {
                String reason = e.getMessage() == null ? "上传失败" : e.getMessage();
                failures.add(Map.of("fileName", originalName(file), "reason", reason));
                log.warn("图像上传失败: {} - {}", originalName(file), reason);
            }
        }

        return new BatchUploadResult(files.length, images, failures);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String name = originalName(file);
        String extension = extensionOf(name);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的图像格式: " + (extension.isEmpty() ? name : extension));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("单张图像不能超过 50MB");
        }
    }

    private Path store(MultipartFile file, String extension) throws IOException {
        Path dir = Paths.get(storagePath).toAbsolutePath().normalize()
                .resolve(LocalDate.now().format(DATE_DIR));
        Files.createDirectories(dir);

        Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + extension);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static String originalName(MultipartFile file) {
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        // 去掉浏览器可能带上的路径前缀
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    /** 分页列表，支持按任务、状态筛选。 */
    public Map<String, Object> list(Long taskId, String status, int page, int size) {
        QueryWrapper<RecognitionImage> wrapper = new QueryWrapper<>();
        if (taskId != null) {
            wrapper.eq("task_id", taskId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return pageOf(wrapper, page, size);
    }

    public RecognitionImage getById(Long id) {
        return id == null ? null : imageRepository.selectById(id);
    }

    public List<RecognitionImage> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return imageRepository.selectList(new QueryWrapper<RecognitionImage>().in("id", ids));
    }

    public List<RecognitionImage> listByTask(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        return imageRepository.selectList(
                new QueryWrapper<RecognitionImage>().eq("task_id", taskId).orderByAsc("id"));
    }

    public long countByTask(Long taskId) {
        return listByTask(taskId).size();
    }

    /**
     * 真正的数据库分页。
     *
     * 不再：
     *   selectList() -> 查询全部数据 -> Java subList()
     *
     * 而是：
     *   selectPage() -> MySQL LIMIT/OFFSET -> 只查询当前页。
     */
    private Map<String, Object> pageOf(
            QueryWrapper<RecognitionImage> wrapper,
            int page,
            int size) {
        long current = Math.max(page, 1);
        long pageSize = Math.max(size, 1);
        // 与 MybatisPlusConfig.maxLimit = 200 保持一致。
        pageSize = Math.min(pageSize, 200);

        Page<RecognitionImage> pageRequest =
                new Page<>(current, pageSize);
        Page<RecognitionImage> pageResult =
                imageRepository.selectPage(
                        pageRequest,
                        wrapper
                );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        result.put("pages", pageResult.getPages());
        result.put("list", pageResult.getRecords());
        return result;
    }

    // ── 修改 ────────────────────────────────────────────────────────────────

    /** 修改图像信息（文件名、状态、失败原因）。 */
    public RecognitionImage update(Long id, Map<String, Object> body) {
        RecognitionImage image = imageRepository.selectById(id);
        if (image == null) {
            return null;
        }
        if (body.get("fileName") != null) {
            image.setFileName(String.valueOf(body.get("fileName")));
        }
        if (body.get("status") != null) {
            image.setStatus(String.valueOf(body.get("status")));
        }
        if (body.containsKey("errorMessage")) {
            Object message = body.get("errorMessage");
            image.setErrorMessage(message == null ? null : String.valueOf(message));
        }
        imageRepository.updateById(image);
        return image;
    }

    /** 更新单张图像的处理状态（AI 引擎回写 / 任务推进时使用）。 */
    public void markStatus(Long id, String status, String errorMessage) {
        RecognitionImage image = imageRepository.selectById(id);
        if (image == null) {
            return;
        }
        image.setStatus(status);
        image.setErrorMessage(errorMessage);
        imageRepository.updateById(image);
    }

    /**
     * 把一批图像挂到某个任务下，并重置为待识别。
     *
     * 用批量 UPDATE 取代「逐张 selectById + updateById」：
     * 10 万张图由 10 万次 UPDATE 降为 ceiling(N / {@value #ATTACH_BATCH_SIZE}) 次。
     * 这一步发生在投递 Redis 之前，是任务创建链路上最直接的写放大来源。
     *
     * 只按 ID 更新，**不回查数据库** —— 唯一调用方 {@code TaskService.createTask} 调用前
     * 已经拿到图像实体，这里再查一次纯属重复。因此任务创建链路只剩一次 SELECT（校验 + 统计张数）。
     *
     * <p>⑨-C 起 {@code retryTask} <b>不再调用本方法</b>：重试改为「派生新任务 + 复制新图像行」，
     * 原任务的图像行一个字段都不动 —— 这正是"旧任务的人工复核结论不会被重试冲掉"的实现方式。
     *
     * error_message 用 {@code set(..., null)} 显式置空：MyBatis-Plus 默认
     * updateStrategy = NOT_NULL，旧实现走 {@code updateById(实体)} 时 null 字段会被跳过，
     * 其实并没有清掉上一次的失败原因。
     *
     * @return 实际匹配到的图像行数（累加各批受影响行数）
     */
    public int attachToTask(List<Long> imageIds, Long taskId) {
        if (imageIds == null || imageIds.isEmpty()) {
            return 0;
        }

        int updated = 0;
        for (int start = 0; start < imageIds.size(); start += ATTACH_BATCH_SIZE) {
            int end = Math.min(start + ATTACH_BATCH_SIZE, imageIds.size());
            List<Long> batch = imageIds.subList(start, end);

            LambdaUpdateWrapper<RecognitionImage> wrapper = new LambdaUpdateWrapper<>();
            wrapper.in(RecognitionImage::getId, batch)
                    .set(RecognitionImage::getTaskId, taskId)
                    .set(RecognitionImage::getStatus, "WAITING")
                    .set(RecognitionImage::getErrorMessage, null);

            updated += imageRepository.update(null, wrapper);
        }

        log.info("图像挂载任务 {}: 请求 {} 张, 更新 {} 行, 分 {} 批",
                taskId, imageIds.size(), updated,
                (imageIds.size() + ATTACH_BATCH_SIZE - 1) / ATTACH_BATCH_SIZE);
        return updated;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    /** 删除单张图像；磁盘文件仅在「没有别的行引用同一路径」时才清理。 */
    public boolean delete(Long id) {
        RecognitionImage image = imageRepository.selectById(id);
        if (image == null) {
            return false;
        }
        deleteFileIfUnreferenced(image);
        imageRepository.deleteById(id);
        return true;
    }

    /** 批量删除，返回实际删除张数。 */
    public int deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Long id : ids) {
            if (delete(id)) {
                deleted++;
            }
        }
        return deleted;
    }

    /** 删除某任务下的全部图像，返回删除张数；被其它任务共享的物理文件会被保留。 */
    public int deleteByTask(Long taskId) {
        List<RecognitionImage> images = listByTask(taskId);
        for (RecognitionImage image : images) {
            deleteFileIfUnreferenced(image);
        }
        if (!images.isEmpty()) {
            imageRepository.delete(new QueryWrapper<RecognitionImage>().eq("task_id", taskId));
        }
        return images.size();
    }

    /**
     * 删除图像对应的磁盘文件 —— 但仅当没有别的图像行引用同一个 {@code file_path}。
     *
     * <p>⑨-C 起 retry 是「派生新任务 + 复制 image 行」，新旧任务共用同一 {@code file_path}
     * （磁盘文件不复制）。若无条件删除，删掉旧任务就会把新任务正在用的文件一起删掉，
     * 留下"库里有行、盘上无文件"的悬空引用。这里做一次引用计数：
     * 仍有引用就跳过物理删除，让文件跟着最后一个引用一起消失。
     *
     * <p>⚠️ 同一路径被同一任务内的多行共享时（正常不会发生，{@code upload} 每张一个 UUID 名），
     * 保守方向是「都不删」→ 文件残留而非悬空，是安全的一侧。
     */
    private void deleteFileIfUnreferenced(RecognitionImage image) {
        long others = imageRepository.countOtherRefs(image.getFilePath(), image.getId());
        if (others > 0) {
            log.info("文件仍被 {} 行引用，跳过物理删除: {}", others, image.getFilePath());
            return;
        }
        deleteFile(image.getFilePath());
    }

    private void deleteFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            log.warn("图像文件删除失败: {}", filePath, e);
        }
    }

    /** 批量上传结果。 */
    public record BatchUploadResult(int total,
                                    List<RecognitionImage> images,
                                    List<Map<String, String>> failures) {
    }
}
