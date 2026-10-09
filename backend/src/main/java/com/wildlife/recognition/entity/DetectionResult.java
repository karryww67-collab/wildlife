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

    /**
     * 所属图像的**原图物理文件**是否还在。
     *
     * <p>不落库（`exist = false`），只在接口返回待复核队列时由
     * {@code ImageAvailabilityService} 现算填充：
     * <ul>
     *   <li>{@code false} —— 库里还有这条识别结果，但磁盘上的原图已经没了（悬空引用）。
     *       此时复核弹窗取图会 404，看不到图就没有判据，前端应禁用「提交复核」，
     *       后端 {@code ReviewService.review()} 也会拒绝。</li>
     *   <li>{@code true} / {@code null}（字段未参与本次查询时为 null）—— 正常。</li>
     * </ul>
     * 之所以不在 detection_result 上落一列，是因为"文件是否存在"是文件系统事实、
     * 会随磁盘清理而变化，落库反而需要一套同步机制；见 {@code ImageAvailabilityService} 的说明。
     */
    @TableField(exist = false)
    private Boolean imageAvailable;

    @TableField("create_time")
    private LocalDateTime createTime = LocalDateTime.now();
}
