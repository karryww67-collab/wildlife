package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wildlife.recognition.entity.User;
import com.wildlife.recognition.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String TOKEN_PREFIX = "auth:token:";
    private static final long TOKEN_EXPIRE_HOURS = 8;
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final PasswordService passwordService;

    public AuthService(UserRepository userRepository,
                       StringRedisTemplate redisTemplate,
                       PasswordService passwordService) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.passwordService = passwordService;
    }

    public LoginResult login(String username, String password) {
        User user = userRepository.selectOne(
                new QueryWrapper<User>().eq("username", username));
        if (user == null) return null;

        if (!passwordService.matches(password, user.getPassword())) {
            return null;
        }

        // 透明升级：历史遗留的明文 / MD5 存量在首次成功登录时改写为 BCrypt。
        // 用户无感知，也不需要一次性数据迁移脚本（老账号照样能登录）。
        if (passwordService.needsUpgrade(user.getPassword())) {
            user.setPassword(passwordService.encode(password));
            userRepository.updateById(user);
            log.info("存量密码格式已升级为 BCrypt: username={}", user.getUsername());
        }

        String role = normalizeRole(user.getRole());
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                TOKEN_PREFIX + token,
                username + "|" + role,
                TOKEN_EXPIRE_HOURS,
                TimeUnit.HOURS
        );
        return new LoginResult(token, username, role);
    }

    public String verify(String token) {
        Session session = verifySession(token);
        return session == null ? null : session.username();
    }

    public Session verifySession(String token) {
        if (token == null || token.isBlank()) return null;
        String payload = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);
        if (payload == null || payload.isBlank()) return null;
        int sep = payload.indexOf('|');
        if (sep < 0) {
            return new Session(payload, ROLE_USER);
        }
        String username = payload.substring(0, sep);
        String role = normalizeRole(payload.substring(sep + 1));
        return new Session(username, role);
    }

    public void logout(String token) {
        if (token != null) redisTemplate.delete(TOKEN_PREFIX + token);
    }

    public static String normalizeRole(String role) {
        if (role == null || role.isBlank()) return ROLE_USER;
        String value = role.trim();
        if ("ADMIN".equalsIgnoreCase(value) || "管理员".equals(value)) {
            return ROLE_ADMIN;
        }
        return ROLE_USER;
    }

    public record LoginResult(String token, String username, String role) {}

    public record Session(String username, String role) {
        public boolean isAdmin() {
            return ROLE_ADMIN.equalsIgnoreCase(role);
        }
    }
}
