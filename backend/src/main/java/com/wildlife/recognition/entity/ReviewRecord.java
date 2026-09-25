package com.wildlife.recognition.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 人工复核记录
 */
@Data
@TableName("review_record")
public class ReviewRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被复核的检测结果 */
    @TableField("result_id")
    private Long resultId;

    /** 复核人 */
    @TableField("reviewer_id")
    private Long reviewerId;

    /** 原识别类别 */
    @TableField("original_class")
    private String originalClass;

    /** 修正后类别 */
    @TableField("corrected_class")
    private String correctedClass;

    /** 原置信度 */
    @TableField("original_confidence")
    private Double originalConfidence;

    /** 复核状态 CONFIRMED / CORRECTED / REJECTED */
    @TableField("review_status")
    private String reviewStatus;

    /** 复核备注 */
    private String remark;

    @TableField("review_time")
    private LocalDateTime reviewTime = LocalDateTime.now();
}
