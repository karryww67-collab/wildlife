package com.wildlife.recognition.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wildlife.recognition.entity.User;
import com.wildlife.recognition.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户账号管理。
 *
 * 系统不开放自助注册：账号由管理员在后台创建，
 * 角色分 ADMIN（全部权限）、REVIEWER（识别 + 复核）、USER（只读查看）。
 *
 * 两点约定：
 * 1. 密码一律经 {@link PasswordService} 以 BCrypt 落库；对历史明文 / MD5 存量的
 *    兼容判定也集中在那个类里，本类不再自己实现哈希。
 * 2. nickname / email 由 UserController 传入，但 users 表与 User 实体
 *    当前都没有对应列，本类只接收并记日志，不伪造字段、不改表结构。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_REVIEWER = "REVIEWER";
    private static final String ROLE_USER = "USER";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final UserRepository userRepository;
    private final PasswordService passwordService;

    public UserService(UserRepository userRepository, PasswordService passwordService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    /** 用户列表，可按角色过滤、按用户名模糊搜索。 */
    public List<User> list(String role, String keyword) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(role)) {
            wrapper.eq("role", role.trim().toUpperCase());
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like("username", keyword.trim());
        }
        wrapper.orderByAsc("id");
        return userRepository.selectList(wrapper);
    }

    public User getByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return userRepository.selectOne(new QueryWrapper<User>().eq("username", username.trim()));
    }

    public User getById(Long id) {
        return id == null ? null : userRepository.selectById(id);
    }

    /**
     * 脱敏：抹掉密码后返回，供接口直接输出。
     * 复制一份而不是直接改原对象 —— 原对象随后可能被 updateById 落库，
     * 就地清空密码会把库里的密码一起写成 null。
     */
    public User sanitize(User user) {
        if (user == null) {
            return null;
        }
        User safe = new User();
        safe.setId(user.getId());
        safe.setUsername(user.getUsername());
        safe.setRole(user.getRole());
        safe.setStatus(user.getStatus());
        safe.setCreatedAt(user.getCreatedAt());
        return safe;
    }

    // ── 新增与修改 ──────────────────────────────────────────────────────────

    /** 新增用户。用户名重复、用户名为空、密码为空都抛 IllegalArgumentException。 */
    public User create(String username, String password, String nickname, String role) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (getByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在：" + username.trim());
        }

        User user = new User();
        user.setUsername(username.trim());
        user.setPassword(passwordService.encode(password));
        user.setRole(normalizeRole(role));
        user.setStatus(STATUS_ACTIVE);
        userRepository.insert(user);

        log.info("用户已创建: id={}, username={}, role={}",
                user.getId(), user.getUsername(), user.getRole());
        if (StringUtils.hasText(nickname)) {
            log.warn("nickname 暂无对应列，未落库: {}", nickname);
        }
        return user;
    }

    /** 修改角色（昵称 / 邮箱当前没有对应列，只接收不落库）。 */
    public User update(Long id, String nickname, String role, String email) {
        User user = getById(id);
        if (user == null) {
            return null;
        }

        if (StringUtils.hasText(role)) {
            user.setRole(normalizeRole(role));
        }
        userRepository.updateById(user);

        if (StringUtils.hasText(nickname) || StringUtils.hasText(email)) {
            log.warn("nickname / email 暂无对应列，未落库: id={}, nickname={}, email={}",
                    id, nickname, email);
        }
        return user;
    }

    /** 启用 / 停用账号。status 只接受 ACTIVE 与 DISABLED。 */
    public User updateStatus(Long id, String status) {
        User user = getById(id);
        if (user == null) {
            return null;
        }

        String normalized = normalizeStatus(status);
        user.setStatus(normalized);
        userRepository.updateById(user);

        log.info("用户状态已更新: id={}, username={}, status={}",
                id, user.getUsername(), normalized);
        return user;
    }

    // ── 密码 ────────────────────────────────────────────────────────────────

    /** 管理员重置他人密码。 */
    public boolean resetPassword(Long id, String newPassword) {
        User user = getById(id);
        if (user == null) {
            return false;
        }
        if (!StringUtils.hasText(newPassword)) {
            throw new IllegalArgumentException("新密码不能为空");
        }

        user.setPassword(passwordService.encode(newPassword));
        userRepository.updateById(user);
        log.info("密码已重置: id={}, username={}", id, user.getUsername());
        return true;
    }

    /** 修改自己的密码，需校验原密码；原密码不正确返回 false。 */
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        User user = getByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (!StringUtils.hasText(newPassword)) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        if (!passwordService.matches(oldPassword, user.getPassword())) {
            return false;
        }

        user.setPassword(passwordService.encode(newPassword));
        userRepository.updateById(user);
        log.info("密码已修改: username={}", user.getUsername());
        return true;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    /** 删除用户；不允许删除自己，也不允许删掉最后一个管理员。 */
    public boolean delete(Long id, String currentUsername) {
        User user = getById(id);
        if (user == null) {
            return false;
        }

        if (StringUtils.hasText(currentUsername)
                && currentUsername.equals(user.getUsername())) {
            throw new IllegalStateException("不能删除当前登录的账号");
        }

        if (ROLE_ADMIN.equalsIgnoreCase(user.getRole())) {
            Long admins = userRepository.selectCount(
                    new QueryWrapper<User>().eq("role", ROLE_ADMIN));
            if (admins == null || admins <= 1) {
                throw new IllegalStateException("系统必须保留至少一个管理员账号");
            }
        }

        userRepository.deleteById(id);
        log.info("用户已删除: id={}, username={}", id, user.getUsername());
        return true;
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 角色归一化：只认 ADMIN / REVIEWER / USER，其余归为 USER。 */
    private static String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            return ROLE_USER;
        }
        String value = role.trim().toUpperCase();
        if (ROLE_ADMIN.equals(value)) {
            return ROLE_ADMIN;
        }
        if (ROLE_REVIEWER.equals(value)) {
            return ROLE_REVIEWER;
        }
        return ROLE_USER;
    }

    /** 状态归一化：只认 ACTIVE / DISABLED，脏值直接抛异常而不是静默接受。 */
    private static String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("状态不能为空，只接受 ACTIVE / DISABLED");
        }
        String value = status.trim().toUpperCase();
        if (STATUS_ACTIVE.equals(value)) {
            return STATUS_ACTIVE;
        }
        if (STATUS_DISABLED.equals(value)) {
            return STATUS_DISABLED;
        }
        throw new IllegalArgumentException(
                "非法状态：" + status + "，只接受 ACTIVE / DISABLED");
    }
}
