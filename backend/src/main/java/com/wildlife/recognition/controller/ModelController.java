package com.wildlife.recognition.controller;

import com.wildlife.recognition.entity.ModelVersion;
import com.wildlife.recognition.service.ModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 识别模型管理。
 *
 * 支持登记多个模型版本（YOLO 权重），记录 mAP、Precision、Recall 等指标，
 * 并在其中指定一个"当前启用"的模型 —— 创建识别任务时若不指定 modelId，就走启用的那个。
 *
 * <p>路径口径（与前端 {@code api/index.ts} 的契约一致）：
 * <pre>
 * GET    /api/models       模型列表
 * POST   /api/models       登记模型版本
 * PUT    /api/models/{id}  修改模型
 * DELETE /api/models/{id}  删除模型
 * </pre>
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    /** 模型列表，可按状态筛选：ENABLED 启用中 / DISABLED 已停用；不传则返回全部。 */
    @GetMapping
    public ResponseEntity<List<ModelVersion>> list(@RequestParam(required = false) String status) {
        if (STATUS_ENABLED.equalsIgnoreCase(status)) {
            return ResponseEntity.ok(modelService.list(true));
        }
        List<ModelVersion> models = modelService.list(false);
        if (STATUS_DISABLED.equalsIgnoreCase(status)) {
            models = models.stream()
                    .filter(model -> STATUS_DISABLED.equalsIgnoreCase(model.getStatus()))
                    .toList();
        }
        return ResponseEntity.ok(models);
    }

    /** 当前启用的模型。 */
    @GetMapping("/active")
    public ResponseEntity<?> active() {
        ModelVersion model = modelService.getActive();
        if (model == null) {
            return ResponseEntity.ok(Map.of("model", Map.of(), "message", "尚未启用任何模型"));
        }
        return ResponseEntity.ok(model);
    }

    /** 模型详情。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> getModel(@PathVariable Long id) {
        ModelVersion model = modelService.getById(id);
        if (model == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(model);
    }

    /** 模型评估指标（mAP / Precision / Recall / F1，以及各类别维度的表现）。 */
    @GetMapping("/{id}/metrics")
    public ResponseEntity<?> metrics(@PathVariable Long id) {
        if (modelService.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(modelService.getMetrics(id));
    }

    /**
     * 登记模型版本。
     * 请求体：{"modelName":"Wildlife-YOLO11","version":"v1.0",
     *          "modelPath":"/models/wildlife-v1.0/best.pt","classConfig":"...",
     *          "precisionValue":0.85,"recallValue":0.79,"map50":0.82,"map5095":0.65}
     * 第一个登记的模型自动启用。
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody ModelVersion model) {
        try {
            return ResponseEntity.ok(modelService.create(model));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 修改模型信息，只需传要改的字段。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ModelVersion model) {
        ModelVersion updated = modelService.update(id, model);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    /** 启用该模型（同时取消其他模型的启用状态）。 */
    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable Long id) {
        ModelVersion model = modelService.activate(id);
        if (model == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", model.getId(), "status", model.getStatus()));
    }

    /** 停用该模型。 */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable Long id) {
        ModelVersion model = modelService.deactivate(id);
        if (model == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", model.getId(), "status", model.getStatus()));
    }

    /** 删除模型；已被识别任务使用的模型不允许删除，只能停用。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            boolean removed = modelService.delete(id);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("message", "模型已删除"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
