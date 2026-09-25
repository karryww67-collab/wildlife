package com.wildlife.recognition.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 密码哈希与校验。
 *
 * <p>存储格式：BCrypt（60 字符，形如 {@code $2a$10$...}）。
 *
 * <p><b>历史存量兼容</b>：早期版本把密码<b>明文</b>存库（{@code init.sql} 的种子
 * {@code 'admin123'}），中间还写过一段 MD5。这两种都能被 {@link #matches} 识别并按
 * 旧规则比对，因此老账号不会因为本次改动而登录失败。校验通过后调用方应立即用
 * {@link #encode} 覆盖写回（见 {@link AuthService#login}），完成透明升级 ——
 * 用户无感知，且不需要一次性数据迁移脚本。
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    /** BCrypt 强度沿用默认的 10 轮。 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 生成 BCrypt 哈希，供落库使用。 */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** 存量是否已是 BCrypt 格式。 */
    public boolean isHashed(String stored) {
        return stored != null && stored.startsWith("$2");
    }

    /** 命中的存量是否为需要升级的旧格式（明文 / MD5）。 */
    public boolean needsUpgrade(String stored) {
        return stored != null && !isHashed(stored);
    }

    /**
     * 校验明文密码。
     *
     * @param rawPassword 用户提交的明文
     * @param stored      库中存量（BCrypt / 明文 / MD5）
     * @return 是否匹配；返回 true 且 {@link #needsUpgrade} 为 true 时，调用方应回写 BCrypt
     */
    public boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        if (isHashed(stored)) {
            return encoder.matches(rawPassword, stored);
        }
        // 旧规则：明文相等，或 MD5(明文) 相等（忽略大小写）
        if (stored.equals(rawPassword)) {
            log.warn("检测到明文存量密码，将在本次校验通过后自动升级为 BCrypt");
            return true;
        }
        boolean md5Hit = stored.equalsIgnoreCase(md5(rawPassword));
        if (md5Hit) {
            log.warn("检测到 MD5 存量密码，将在本次校验通过后自动升级为 BCrypt");
        }
        return md5Hit;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}