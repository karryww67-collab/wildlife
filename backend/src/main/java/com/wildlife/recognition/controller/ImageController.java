package com.wildlife.recognition.controller;

import com.wildlife.recognition.entity.RecognitionImage;
import com.wildlife.recognition.service.ImageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 图像管理：批量上传、检索、预览、删除。
 *
 * 这是"批量识别"的数据入口 —— 用户一次性选择/拖拽多张图片上传，
 * 拿到返回的 imageId 列表后再由 {@link TaskController} 创建识别任务。
 *
 * <p>路径口径：{@code /api/images}（与 tasks / results / reviews / models 保持复数风格一致）。
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
    }

    // ── 上传 ────────────────────────────────────────────────────────────────

    /** 单张上传。 */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(imageService.upload(file));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "文件写入失败: " + e.getMessage()));
        }
    }

    /**
     * 批量上传（一次请求多张图）。
     * 单张失败不影响其余图片，返回逐张结果，便于前端提示哪几张没成功。
     * 数量与单张体积上限由 {@link ImageService} 统一把关。
     */
    @PostMapping("/upload-batch")
    public ResponseEntity<?> uploadBatch(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "files 不能为空"));
        }
        try {
            ImageService.BatchUploadResult result = imageService.uploadBatch(files);
            return ResponseEntity.ok(Map.of(
                    "total", result.total(),
                    "successCount", result.images().size(),
                    "failedCount", result.failures().size(),
                    "images", result.images(),
                    "failures", result.failures()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    /** 图像列表，支持按任务、状态筛选，按创建时间倒序分页。 */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Long taskId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "24") int size) {
        return ResponseEntity.ok(imageService.list(taskId, status, page, size));
    }

    /** 图像详情。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> getImage(@PathVariable Long id) {
        RecognitionImage image = imageService.getById(id);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(image);
    }

    /** 原图。 */
    @GetMapping("/{id}/raw")
    public ResponseEntity<Resource> raw(@PathVariable Long id) {
        return serveFile(id);
    }

    /**
     * 缩略图。
     *
     * 当前 {@code recognition_image} 表没有缩略图列，此处回退为原图，
     * 保证前端 {@code /thumbnail} 的调用不至于 404；后续若引入缩略图生成，
     * 只需在这里改成优先读缩略图路径。
     */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable Long id) {
        return serveFile(id);
    }

    private ResponseEntity<Resource> serveFile(Long id) {
        RecognitionImage image = imageService.getById(id);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }

        String path = image.getFilePath();
        if (path == null || path.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        Path filePath = Paths.get(path).toAbsolutePath();
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        String contentType;
        try {
            contentType = Files.probeContentType(filePath);
        } catch (IOException e) {
            contentType = null;
        }
        if (contentType == null) {
            contentType = "image/jpeg";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                .body(new FileSystemResource(filePath));
    }

    // ── 修改与删除 ──────────────────────────────────────────────────────────

    /** 修改图像信息（文件名、状态、失败原因）。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        RecognitionImage updated = imageService.update(id, body);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    /** 删除单张图像（磁盘文件一并清理）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        boolean removed = imageService.delete(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "图像已删除"));
    }

    /** 批量删除，请求体：{"imageIds":[1,2,3]}。 */
    @PostMapping("/batch-delete")
    public ResponseEntity<?> batchDelete(@RequestBody Map<String, Object> body) {
        Object raw = body.get("imageIds");
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "imageIds 不能为空"));
        }

        List<Long> imageIds;
        try {
            imageIds = ((List<?>) raw).stream()
                    .map(item -> Long.valueOf(String.valueOf(item)))
                    .toList();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "imageIds 必须为图像 ID 数组"));
        }

        return ResponseEntity.ok(Map.of("deleted", imageService.deleteBatch(imageIds)));
    }
}
