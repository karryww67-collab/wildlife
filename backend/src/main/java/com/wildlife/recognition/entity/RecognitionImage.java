package com.wildlife.recognition.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 识别图像（批量识别的基本单位）
 */
@Data
@TableName("recognition_image")
public class RecognitionImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属识别任务 */
    @TableField("task_id")
    private Long taskId;

    /** 原始文件名 */
    @TableField("file_name")
    private String fileName;

    /** 存储路径 */
    @TableField("file_path")
    private String filePath;

    /** 文件大小（字节） */
    @TableField("file_size")
    private Long fileSize;

    /** 状态 WAITING / PROCESSING / SUCCESS / FAILED */
    private String status = "WAITING";

    /** 失败原因 */
    @TableField("error_message")
    private String errorMessage;

    @TableField("create_time")
    private LocalDateTime createTime = LocalDateTime.now();
}
