package com.nexusai.application.agent.cli;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trusted Device 设备信任 · 对齐 CC bridge/trustedDevice.ts.
 *
 * <p>FIX-BRIDGE-3 (s17 P3-3): 简化版设备 token 管理.
 *
 * <p>L1 语义:
 * <ul>
 *   <li>{@link #issueToken(String, String)}: 给 (deviceId, userId) 颁发 device token</li>
 *   <li>{@link #verifyToken(String, String, String)}: 验证 token 是否匹配 deviceId + userId</li>
 *   <li>{@link #revokeToken(String)}: 撤销 token</li>
 * </ul>
 *
 * <p>LIMIT: 内存存储 (重启丢失). 真实场景应接数据库/Redis — 留 P1 接入.
 */
public final class TrustedDevice {

    private TrustedDevice() {}

    private static final long DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 days

    /** deviceId+userId → token record */
    private static final Map<String, TokenRecord> TOKENS = new ConcurrentHashMap<>();
    private static final SecureRandom RNG = new SecureRandom();

    public record TokenRecord(String token, String deviceId, String userId, long issuedAt, long expiresAt) {}

    public static TokenRecord issueToken(String deviceId, String userId) {
        return issueToken(deviceId, userId, DEFAULT_TTL_MS);
    }

    public static TokenRecord issueToken(String deviceId, String userId, long ttlMs) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId required");
        }
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        long now = Instant.now().toEpochMilli();
        TokenRecord rec = new TokenRecord(token, deviceId, userId, now, now + ttlMs);
        TOKENS.put(deviceId + ":" + userId, rec);
        return rec;
    }

    public static boolean verifyToken(String token, String deviceId, String userId) {
        if (token == null || token.isBlank()) return false;
        TokenRecord rec = TOKENS.get(deviceId + ":" + userId);
        if (rec == null) return false;
        if (!rec.token().equals(token)) return false;
        if (Instant.now().toEpochMilli() > rec.expiresAt()) return false;
        return true;
    }

    public static boolean revokeToken(String deviceId, String userId) {
        return TOKENS.remove(deviceId + ":" + userId) != null;
    }

    /** 清理过期 token. 单元测试 / 后台任务可调用. */
    public static int purgeExpired() {
        long now = Instant.now().toEpochMilli();
        int[] removed = {0};
        TOKENS.entrySet().removeIf(e -> {
            if (now > e.getValue().expiresAt()) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }
}