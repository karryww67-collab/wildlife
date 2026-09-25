package com.wildlife.recognition.controller;

import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.RecognitionTask;
import com.wildlife.recognition.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 识别任务管理。
 *
 * 一个任务 = 一批图像的一次批量识别过程。
 * 前端先调用 {@link ImageController} 批量上传图像拿到 imageId 列表，再调用本控制器创建任务。
 *
 * <p>路径口径（与前端 {@code api/index.ts} 的契约一致）：
 * <pre>
 * POST   /api/tasks                创建批量识别任务
 * GET    /api/tasks                任务列表，可按状态筛选
 * GET    /api/tasks/{id}           任务详情
 * GET    /api/tasks/{id}/progress  任务进度（轮询，与 WebSocket 推送同构）
 * DELETE /api/tasks/{id}           删除任务
 * </pre>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 创建批量识别任务。
     * 请求体：{"taskName":"春季红外相机批次","imageIds":[1,2,3],"modelId":1}
     * modelId 可省略，省略时使用当前启用的模型。
     */
    @PostMapping
    public ResponseEntity<?> createTask(@RequestBody Map<String, Object> body,
                                        @RequestAttribute(value = "username", required = false) String username) {
        Object rawImageIds = body.get("imageIds");
        if (rawImageIds == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "imageIds 不能为空"));
        }

        List<Long> imageIds;
        try {
            imageIds = ((List<?>) rawImageIds).stream()
                    .map(item -> Long.valueOf(String.valueOf(item)))
                    .toList();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "imageIds 必须为图像 ID 数组"));
        }

        if (imageIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "imageIds 不能为空数组"));
        }

        // taskName 为准；name 是早期前端字段名，保留兼容
        String taskName = firstNonBlank(body.get("taskName"), body.get("name"));

        Long modelId;
        try {
            modelId = body.get("modelId") == null ? null : Long.valueOf(String.valueOf(body.get("modelId")));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "modelId 必须为数字"));
        }

        try {
            RecognitionTask task = taskService.createTask(taskName, imageIds, modelId, username);
            return ResponseEntity.ok(task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 任务列表分页查询，可按状态筛选：PENDING / PROCESSING / COMPLETED / FAILED / CANCELED。
     *
     * 返回手写的五键分页体（total/page/size/pages/list），与 /api/images、/api/results 口径一致。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listTasks(@RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(taskService.listAll(status, page, size));
    }

    /** 今日任务。 */
    @GetMapping("/today")
    public ResponseEntity<List<RecognitionTask>> listToday() {
        return ResponseEntity.ok(taskService.listToday());
    }

    /** 任务详情。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> getTask(@PathVariable Long id) {
        RecognitionTask task = taskService.getById(id);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }

    /** 任务进度，供前端轮询或配合 WebSocket 使用。 */
    @GetMapping("/{id}/progress")
    public ResponseEntity<?> getProgress(@PathVariable Long id) {
        Map<String, Object> progress = taskService.getProgress(id);
        if (progress.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(progress);
    }

    /** 任务下的识别结果列表（跨图像汇总，供任务详情页一次性拉全）。 */
    @GetMapping("/{id}/results")
    public ResponseEntity<List<DetectionResult>> getTaskResults(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.getTaskResults(id));
    }

    /** 取消排队中或进行中的任务。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable Long id) {
        try {
            RecognitionTask task = taskService.cancelTask(id);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(task);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 重试失败或已取消的任务。 */
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retryTask(@PathVariable Long id) {
        try {
            RecognitionTask task = taskService.retryTask(id);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(task);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除任务（同时清理其识别结果、图像与磁盘文件）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTask(@PathVariable Long id) {
        boolean removed = taskService.deleteTask(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "任务已删除"));
    }

    private static String firstNonBlank(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null && !String.valueOf(candidate).isBlank()) {
                return String.valueOf(candidate);
            }
        }
        return null;
    }
}
