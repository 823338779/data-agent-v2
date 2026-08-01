package com.datagent.agentv2.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class RedisLoginSessionStore {

    static final String KEY_PREFIX = "data-agent-v2:auth:session:";

    private final StringRedisTemplate redis;

    void save(String token, String tenantId, String userId, Duration lifetime) {
        redis.opsForValue().set(key(token), encode(tenantId) + "." + encode(userId), lifetime);
    }

    Optional<LoginSession> find(String token) {
        String value = redis.opsForValue().get(key(token));
        if (value == null) {
            return Optional.empty();
        }
        int separator = value.indexOf('.');
        if (separator < 1 || separator == value.length() - 1) {
            redis.delete(key(token));
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new LoginSession(
                            decode(value.substring(0, separator)),
                            decode(value.substring(separator + 1))));
        } catch (IllegalArgumentException invalidValue) {
            redis.delete(key(token));
            return Optional.empty();
        }
    }

    void delete(String token) {
        redis.delete(key(token));
    }

    private static String key(String token) {
        return KEY_PREFIX + sha256(token);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record LoginSession(String tenantId, String userId) {}
}
