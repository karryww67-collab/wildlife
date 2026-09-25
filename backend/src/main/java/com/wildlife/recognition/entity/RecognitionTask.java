package com.wildlife.recognition.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 批量识别任务
 */
@Data
@TableName("recognition_task")
public class RecognitionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务名称 */
    @TableField("task_name")
    private String taskName;

    /** 创建人 */
    @TableField("user_id")
    private Long userId;

    /** 使用的模型版本 */
    @TableField("model_id")
    private Long modelId;

    /** 图像总数 */
    @TableField("total_count")
    private Integer totalCount = 0;

    /** 已处理数 */
    @TableField("processed_count")
    private Integer processedCount = 0;

    /** 成功数 */
    @TableField("success_count")
    private Integer successCount = 0;

    /** 失败数 */
    @TableField("failed_count")
    private Integer failedCount = 0;

    /** 进度 0-100 */
    private Double progress = 0.0;

    /** 任务状态 PENDING / PROCESSING / COMPLETED / FAILED / CANCELED */
    private String status = "PENDING";

    @TableField("create_time")
    private LocalDateTime createTime = LocalDateTime.now();

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("finish_time")
    private LocalDateTime finishTime;
}
