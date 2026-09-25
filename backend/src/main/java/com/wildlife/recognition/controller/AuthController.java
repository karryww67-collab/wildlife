package com.wildlife.recognition.controller;

import com.wildlife.recognition.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录，返回 token 与角色（ADMIN 管理员 / USER 普通用户）。不提供注册接口，账号由管理员预置。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
        }
        AuthService.LoginResult result = authService.login(username, password);
        if (result == null) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        return ResponseEntity.ok(Map.of(
                "token", result.token(),
                "username", result.username(),
                "role", result.role()
        ));
    }

    /** 验证 token 是否有效。 */
    @GetMapping("/verify")
    public ResponseEntity<?> verify(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        AuthService.Session session = authService.verifySession(token);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或 token 已过期"));
        }
        return ResponseEntity.ok(Map.of(
                "username", session.username(),
                "role", session.role(),
                "valid", true
        ));
    }

    /** 登出。 */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        authService.logout(token);
        return ResponseEntity.ok(Map.of("message", "已登出"));
    }
}
