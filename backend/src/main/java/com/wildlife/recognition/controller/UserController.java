package com.wildlife.recognition.controller;

import com.wildlife.recognition.entity.User;
import com.wildlife.recognition.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户管理。
 *
 * 系统不开放自助注册，账号由管理员预置；
 * 角色分 ADMIN（全部权限）、REVIEWER（识别 + 复核）、USER（只读查看）。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 用户列表（管理员）。 */
    @GetMapping("/list")
    public ResponseEntity<List<User>> list(@RequestParam(required = false) String role,
                                           @RequestParam(required = false) String keyword) {
        // 必须逐条脱敏：本端点原先直接返回实体，会把 password 一并序列化给前端
        // （其它端点早已调用 sanitize，只有这里漏了）。
        List<User> users = userService.list(role, keyword).stream()
                .map(userService::sanitize)
                .toList();
        return ResponseEntity.ok(users);
    }

    /** 当前登录用户信息。 */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestAttribute(value = "username", required = false) String username) {
        User user = userService.getByUsername(username);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(userService.sanitize(user));
    }

    /** 用户详情（管理员）。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> getUser(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(userService.sanitize(user));
    }

    /**
     * 新增用户（管理员）。
     * 请求体：{"username":"reviewer01","password":"...","nickname":"张巡护","role":"REVIEWER"}
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        try {
            User user = userService.create(
                    body.get("username"),
                    body.get("password"),
                    body.get("nickname"),
                    body.get("role"));
            return ResponseEntity.ok(userService.sanitize(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 修改用户资料 / 角色（管理员）。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        User user = userService.update(id, body.get("nickname"), body.get("role"), body.get("email"));
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(userService.sanitize(user));
    }

    /** 启用 / 停用账号（管理员）。请求体：{"status":"ACTIVE"} 或 {"status":"DISABLED"}。 */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        User user = userService.updateStatus(id, body.get("status"));
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", user.getId(), "status", user.getStatus()));
    }

    /** 管理员重置他人密码。 */
    @PostMapping("/{id}/password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码不能为空"));
        }
        boolean ok = userService.resetPassword(id, newPassword);
        if (!ok) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "密码已重置"));
    }

    /** 修改自己的密码，需校验原密码。 */
    @PostMapping("/password")
    public ResponseEntity<?> changeOwnPassword(@RequestBody Map<String, String> body,
                                               @RequestAttribute(value = "username", required = false) String username) {
        try {
            boolean ok = userService.changePassword(username, body.get("oldPassword"), body.get("newPassword"));
            if (!ok) {
                return ResponseEntity.badRequest().body(Map.of("error", "原密码不正确"));
            }
            return ResponseEntity.ok(Map.of("message", "密码修改成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除用户（管理员），不能删除自己或最后一个管理员。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id,
                                    @RequestAttribute(value = "username", required = false) String username) {
        try {
            boolean removed = userService.delete(id, username);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("message", "用户已删除"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
