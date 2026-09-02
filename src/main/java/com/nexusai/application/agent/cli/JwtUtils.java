package com.nexusai.application.agent.cli;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * JWT 工具类 · 对齐 CC bridge/jwtUtils.ts.
 *
 * <p>FIX-BRIDGE-2 (s17 P3-3): 极简 JWT 解码 (header + payload, 不验签).
 * 用于从 Authorization Bearer token 提取 session 信息.
 *
 * <p>L1 语义:
 * <ul>
 *   <li>{@link #decode(String)}: 解码 JWT (header + payload), 返回 record</li>
 *   <li>{@link #extractSubject(String)}: 提取 sub claim</li>
 *   <li>{@link #isExpired(String, long)}: 检查 exp claim</li>
 * </ul>
 *
 * <p>LIMIT: 不验签 — 仅用于内部 session token 解析 (假设 caller 已验签或信任源).
 */
public final class JwtUtils {

    private JwtUtils() {}

    public record JwtClaims(String sub, String iss, Long exp, Long iat, String raw) {}

    /** 解码 JWT (header + payload), 不验签. */
    public static JwtClaims decode(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is blank");
        }
        String stripped = token.startsWith("Bearer ") ? token.substring(7) : token;
        String[] parts = stripped.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid JWT format (expected at least 2 segments)");
        }
        String headerJson = b64UrlDecode(parts[0]);
        String payloadJson = b64UrlDecode(parts[1]);

        String sub = extractJsonString(payloadJson, "sub");
        String iss = extractJsonString(payloadJson, "iss");
        Long exp = extractJsonNumber(payloadJson, "exp");
        Long iat = extractJsonNumber(payloadJson, "iat");
        return new JwtClaims(sub, iss, exp, iat, payloadJson);
    }

    /** 提取 subject claim. */
    public static String extractSubject(String token) {
        try {
            return decode(token).sub();
        } catch (Exception e) {
            return null;
        }
    }

    /** 检查是否过期. nowMs 来自调用方 (testable). */
    public static boolean isExpired(String token, long nowMs) {
        JwtClaims c = decode(token);
        if (c.exp() == null) return false;
        return nowMs > c.exp() * 1000L;
    }

    private static String b64UrlDecode(String s) {
        try {
            // 补齐 padding
            int padding = (4 - s.length() % 4) % 4;
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < padding; i++) sb.append('=');
            return new String(Base64.getUrlDecoder().decode(sb.toString()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid base64url: " + e.getMessage());
        }
    }

    /** 简单 JSON string field 提取 (无依赖). */
    private static String extractJsonString(String json, String key) {
        try {
            String quoted = "\"" + key + "\":\"";
            int i = json.indexOf(quoted);
            if (i < 0) return null;
            int start = i + quoted.length();
            int end = json.indexOf('"', start);
            if (end < 0) return null;
            return json.substring(start, end);
        } catch (Exception e) { return null; }
    }

    /** 简单 JSON number field 提取. */
    private static Long extractJsonNumber(String json, String key) {
        try {
            String quoted = "\"" + key + "\":";
            int i = json.indexOf(quoted);
            if (i < 0) return null;
            int start = i + quoted.length();
            StringBuilder sb = new StringBuilder();
            while (start < json.length()) {
                char c = json.charAt(start);
                if (Character.isDigit(c) || c == '-') {
                    sb.append(c);
                    start++;
                } else break;
            }
            return sb.length() > 0 ? Long.parseLong(sb.toString()) : null;
        } catch (Exception e) { return null; }
    }
}