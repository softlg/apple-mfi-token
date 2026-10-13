package com.galaplat.mfi.identity.infrastructure;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 密码哈希。只存 Argon2id 摘要，任何接口与日志都不得出现明文密码或哈希原文。
 *
 * <p>参数取自 Spring Security 默认档（salt 16B、hash 32B、并行度 1、内存 1MiB、迭代 3），
 * 每次哈希自带随机 salt，因此同一密码的摘要不同。
 */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /**
     * 一条结构合法、永远不可能匹配成功的摘要。账号不存在时用它做一次 {@link #burn} 比较，
     * 让"账号不存在"与"密码错误"的响应耗时一致，避免用响应时间枚举账号。
     */
    private final String unreachableHash = encoder.encode(UUID.randomUUID().toString());

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }

    public void burn(String rawPassword) {
        encoder.matches(rawPassword, unreachableHash);
    }

}
