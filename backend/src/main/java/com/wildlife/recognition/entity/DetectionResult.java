package com.wildlife.recognition.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 单条检测结果（一张图像可以有多个目标）
 */
@Data
@TableName("detection_result")
public class DetectionResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属图像 */
    @TableField("image_id")
    private Long imageId;

    /** 产出该结果的模型版本 */
    @TableField("model_id")
    private Long modelId;

    /** 类别编号 */
    @TableField("class_id")
    private Integer classId;

    /** 类别名称（物种名） */
    @TableField("class_name")
    private String className;

    /** 置信度 0-1 */
    private Double confidence;

    /** 检测框左上角 x */
    private Integer x1;

    /** 检测框左上角 y */
    private Integer y1;

    /** 检测框右下角 x */
    private Integer x2;

    /** 检测框右下角 y */
    private Integer y2;

    /** 复核状态 PENDING / CONFIRMED / CORRECTED / REJECTED */
    @TableField("review_status")
    private String reviewStatus = "PENDING";

    @TableField("create_time")
    private LocalDateTime createTime = LocalDateTime.now();
}
