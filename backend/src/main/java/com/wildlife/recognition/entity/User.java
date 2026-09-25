package com.wildlife.recognition.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /**
     * 密码存量（BCrypt）。写入一律经 {@link com.wildlife.recognition.service.PasswordService}。
     *
     * <p>标 {@code @JsonIgnore} 是最后一道防线：本实体同时用作响应体，
     * 即便某个接口忘了调 {@code UserService.sanitize()}，密码也不会被序列化出去
     * （此前正是 {@code /api/user/list} 漏了脱敏，把明文密码直接返回给了前端）。
     *
     * <p>不影响入参解析 —— 所有写密码的接口用的都是
     * {@code @RequestBody Map<String, String>}，没有以 User 作为请求体的用法。
     */
    @JsonIgnore
    private String password;

    private String role = "USER";

    /**
     * 账号状态：ACTIVE 启用 / DISABLED 停用。
     * 不写字段初始化值 —— 实体兼作 DTO，Null 与 "未传" 必须能区分。
     * 默认值只由 DDL 提供（users.status DEFAULT 'ACTIVE'）。
     */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
