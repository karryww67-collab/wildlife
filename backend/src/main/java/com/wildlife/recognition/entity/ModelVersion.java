package com.wildlife.recognition.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 识别模型版本
 */
@Data
@TableName("model_version")
public class ModelVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型名称 */
    @TableField("model_name")
    private String modelName;

    /** 版本号 */
    private String version;

    /** 权重文件路径 */
    @TableField("model_path")
    private String modelPath;

    /** 类别配置（JSON） */
    @TableField("class_config")
    private String classConfig;

    /** 精确率 */
    @TableField("precision_value")
    private Double precisionValue;

    /** 召回率 */
    @TableField("recall_value")
    private Double recallValue;

    /** mAP@0.5 */
    @TableField("map50")
    private Double map50;

    /** mAP@0.5:0.95 */
    @TableField("map5095")
    private Double map5095;

    /**
     * 状态 ENABLED / DISABLED
     *
     * ⚠️ 刻意不设字段初始值（原来写的 "ENABLED" 已移除）。
     * 本实体同时被 ModelController 当作 POST / PUT 的 @RequestBody 使用，而 Jackson
     * 反序列化会套用字段初始值：客户端漏传 status 时，payload.getStatus() 依然是
     * "ENABLED" 而非 null，于是 ModelService.update() 里那句
     * `if (StringUtils.hasText(payload.getStatus()))` 永远成立，把「只覆盖传入的
     * 非空字段」变成了「每次都强制改成 ENABLED」——库里会同时出现两个 ENABLED，
     * getActive() 的 selectOne 随即抛 TooManyResultsException（模型选择与建任务 500）。
     *
     * 不设初始值后：未传即 null → 不会被覆盖；新建插入由 ModelService.create()
     * 显式赋值（首个模型 ENABLED，其余 DISABLED），裸 SQL 插入由建表语句的
     * DEFAULT 'DISABLED' 兜底。三个默认值口径就此统一。
     */
    private String status;

    @TableField("create_time")
    private LocalDateTime createTime = LocalDateTime.now();
}
