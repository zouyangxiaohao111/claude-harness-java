package com.nexusai.infra.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * API key 工具：SHA-256 hash + 脱敏显示
 *
 * <p>脱敏规则（v1）：
 * <ul>
 *   <li>key 长度 ≤ 7：只显示前 2 + **** + 后 1（如 "ab****x"）</li>
 *   <li>key 长度 > 7：显示前 3 + **** + 后 4（如 "sk-****4f2a"）</li>
 *   <li>key 长度 ≤ 4：全 ****（避免过短 key 完全暴露）</li>
 * </ul>
 *
 * <p>hash 用 SHA-256（无盐，因为 key 本身就是 secret，再加盐没用）。
 * 唯一目的是数据库泄露时不直接暴露明文 key。
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {}

    public static String hash(String plainKey) {
        if (plainKey == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(plainKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String mask(String plainKey) {
        if (plainKey == null || plainKey.isEmpty()) return "****";
        int len = plainKey.length();
        if (len <= 4) return "****";
        if (len <= 7) {
            return plainKey.substring(0, 2) + "****" + plainKey.substring(len - 1);
        }
        return plainKey.substring(0, 3) + "****" + plainKey.substring(len - 4);
    }

    /** 测试用入口 */
    public static void main(String[] args) {
        String[] keys = {
            "sk-abc123def456",
            "short",
            "ab",
            "verylongapikeywithmanycharacters"
        };
        for (String k : keys) {
            System.out.println(k + " -> mask: " + mask(k) + ", hash: " + hash(k).substring(0, 16) + "...");
        }
    }
}