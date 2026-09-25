package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wildlife.recognition.entity.DetectionResult;
import com.wildlife.recognition.entity.ModelVersion;
import com.wildlife.recognition.entity.RecognitionTask;
import com.wildlife.recognition.repository.DetectionResultRepository;
import com.wildlife.recognition.repository.ModelVersionRepository;
import com.wildlife.recognition.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 识别模型版本管理。
 *
 * 系统里可能同时存在多个模型（不同 YOLO 版本、不同训练轮次），
 * 这里负责登记模型元信息与评估指标，并维护"当前启用"的那一个 ——
 * 创建识别任务时不指定 modelId，就走启用的模型。
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";

    private final ModelVersionRepository modelVersionRepository;
    private final TaskRepository taskRepository;
    private final DetectionResultRepository resultRepository;

    public ModelService(ModelVersionRepository modelVersionRepository,
                        TaskRepository taskRepository,
                        DetectionResultRepository resultRepository) {
        this.modelVersionRepository = modelVersionRepository;
        this.taskRepository = taskRepository;
        this.resultRepository = resultRepository;
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    /** 模型列表；activeOnly=true 时只返回启用中的。 */
    public List<ModelVersion> list(boolean activeOnly) {
        QueryWrapper<ModelVersion> wrapper = new QueryWrapper<>();
        if (activeOnly) {
            wrapper.eq("status", STATUS_ENABLED);
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return modelVersionRepository.selectList(wrapper);
    }

    /** 当前启用的模型（取最近登记的一个）。 */
    public ModelVersion getActive() {
        return modelVersionRepository.selectOne(new QueryWrapper<ModelVersion>()
                .eq("status", STATUS_ENABLED)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    public ModelVersion getById(Long id) {
        return id == null ? null : modelVersionRepository.selectById(id);
    }

    // ── 登记与修改 ──────────────────────────────────────────────────────────

    /** 登记模型。第一个登记的模型自动启用。 */
    public ModelVersion create(ModelVersion model) {
        if (model == null || !StringUtils.hasText(model.getModelName())) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (!StringUtils.hasText(model.getModelPath())) {
            throw new IllegalArgumentException("模型文件路径不能为空");
        }

        boolean firstOne = list(false).isEmpty();
        model.setStatus(firstOne ? STATUS_ENABLED : STATUS_DISABLED);
        modelVersionRepository.insert(model);

        log.info("模型已登记: id={}, name={}, version={}", model.getId(), model.getModelName(), model.getVersion());
        return model;
    }

    /** 修改模型信息，只覆盖传入的非空字段。 */
    public ModelVersion update(Long id, ModelVersion payload) {
        ModelVersion model = modelVersionRepository.selectById(id);
        if (model == null) {
            return null;
        }
        if (payload != null) {
            if (StringUtils.hasText(payload.getModelName())) {
                model.setModelName(payload.getModelName());
            }
            if (StringUtils.hasText(payload.getVersion())) {
                model.setVersion(payload.getVersion());
            }
            if (StringUtils.hasText(payload.getModelPath())) {
                model.setModelPath(payload.getModelPath());
            }
            if (payload.getClassConfig() != null) {
                model.setClassConfig(payload.getClassConfig());
            }
            if (payload.getPrecisionValue() != null) {
                model.setPrecisionValue(payload.getPrecisionValue());
            }
            if (payload.getRecallValue() != null) {
                model.setRecallValue(payload.getRecallValue());
            }
            if (payload.getMap50() != null) {
                model.setMap50(payload.getMap50());
            }
            if (payload.getMap5095() != null) {
                model.setMap5095(payload.getMap5095());
            }
            if (StringUtils.hasText(payload.getStatus())) {
                model.setStatus(payload.getStatus());
            }
        }
        modelVersionRepository.updateById(model);
        return model;
    }

    // ── 启用 / 停用 ─────────────────────────────────────────────────────────

    /** 启用该模型，同时把其他模型置为停用（保证同一时间只有一个在用）。 */
    public ModelVersion activate(Long id) {
        ModelVersion model = modelVersionRepository.selectById(id);
        if (model == null) {
            return null;
        }

        List<ModelVersion> others = list(false);
        for (ModelVersion other : others) {
            if (!other.getId().equals(id) && STATUS_ENABLED.equals(other.getStatus())) {
                other.setStatus(STATUS_DISABLED);
                modelVersionRepository.updateById(other);
            }
        }

        model.setStatus(STATUS_ENABLED);
        modelVersionRepository.updateById(model);
        log.info("模型已启用: id={}, name={}", model.getId(), model.getModelName());
        return model;
    }

    /** 停用该模型。 */
    public ModelVersion deactivate(Long id) {
        ModelVersion model = modelVersionRepository.selectById(id);
        if (model == null) {
            return null;
        }
        model.setStatus(STATUS_DISABLED);
        modelVersionRepository.updateById(model);
        return model;
    }

    // ── 评估指标 ────────────────────────────────────────────────────────────

    /**
     * 模型指标：训练/验证阶段记录的 mAP、Precision、Recall、F1，
     * 加上该模型在实际识别任务中的产出情况（检出目标数、涉及的物种数、平均置信度）。
     */
    public Map<String, Object> getMetrics(Long id) {
        ModelVersion model = modelVersionRepository.selectById(id);
        if (model == null) {
            return Map.of();
        }

        List<DetectionResult> results = resultRepository.selectList(
                new QueryWrapper<DetectionResult>().eq("model_id", id));

        Map<String, Integer> classCount = new LinkedHashMap<>();
        double confidenceSum = 0.0;
        int confidenceCount = 0;
        for (DetectionResult result : results) {
            String className = StringUtils.hasText(result.getClassName()) ? result.getClassName() : "未知";
            classCount.merge(className, 1, Integer::sum);
            if (result.getConfidence() != null) {
                confidenceSum += result.getConfidence();
                confidenceCount++;
            }
        }

        List<Map<String, Object>> classDistribution = new ArrayList<>();
        classCount.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed())
                .forEach(e -> classDistribution.add(Map.of("className", e.getKey(), "count", e.getValue())));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("modelId", model.getId());
        metrics.put("modelName", model.getModelName());
        metrics.put("version", model.getVersion());
        metrics.put("precision", model.getPrecisionValue());
        metrics.put("recall", model.getRecallValue());
        metrics.put("map50", model.getMap50());
        metrics.put("map5095", model.getMap5095());
        metrics.put("f1", f1(model));
        metrics.put("detectedCount", results.size());
        metrics.put("classCount", classCount.size());
        metrics.put("avgConfidence", confidenceCount == 0 ? 0.0 : round(confidenceSum / confidenceCount));
        metrics.put("classDistribution", classDistribution);
        return metrics;
    }

    private static Double f1(ModelVersion model) {
        Double precision = model.getPrecisionValue();
        Double recall = model.getRecallValue();
        if (precision == null || recall == null || precision + recall == 0) {
            return null;
        }
        return round(2 * precision * recall / (precision + recall));
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    /** 删除模型；已被识别任务使用的模型不允许删除。 */
    public boolean delete(Long id) {
        ModelVersion model = modelVersionRepository.selectById(id);
        if (model == null) {
            return false;
        }

        Long used = taskRepository.selectCount(
                new QueryWrapper<RecognitionTask>().eq("model_id", id));
        if (used != null && used > 0) {
            throw new IllegalStateException("该模型已被 " + used + " 个识别任务使用，不能删除，可改为停用");
        }

        modelVersionRepository.deleteById(id);
        log.info("模型已删除: id={}, name={}", id, model.getModelName());
        return true;
    }
}
