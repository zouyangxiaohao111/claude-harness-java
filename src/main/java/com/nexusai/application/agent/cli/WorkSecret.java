package com.nexusai.application.agent.cli;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Work Secret 编解码 · 对齐 CC bridge/workSecret.ts.
 *
 * <p>FIX-BRIDGE-1 (s17 P3-3): 新增 WorkSecret 工具类, 用于 CCR v2 SDK URL 与 session secret 解析.
 *
 * <p>L1 语义:
 * <ul>
 *   <li>{@link #decodeWorkSecret(String)}: 从 base64url 编码 secret 提取 sessionToken + sandboxId + gitRemote</li>
 *   <li>{@link #buildSdkUrl(String, String)}: 拼接 SDK URL ({@code <base>/sessions/<id>/sdk})</li>
 *   <li>{@link #buildCCRv2SdkUrl(String, String)}: CCR v2 SDK URL ({@code <base>/v2/sessions/<id>/sdk})</li>
 * </ul>
 */
public final class WorkSecret {

    private WorkSecret() {}

    private static final Pattern SECRET_RE = Pattern.compile(
        "^[A-Za-z0-9_-]+:([A-Za-z0-9_-]+):([A-Za-z0-9_-]+)$");

    public record Decoded(String sessionToken, String sandboxId, String gitRemote) {}

    /** 解码 work secret. 格式: {@code <prefix>:<token>:<sandbox>}. 返回 token + sandbox + gitRemote (可选). */
    public static Decoded decodeWorkSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret is blank");
        }
        // 1. 尝试标准格式 base64url:token:sandbox
        String[] parts = secret.split(":", 3);
        if (parts.length >= 2) {
            String prefix = parts[0];
            String token = parts[1];
            String sandbox = parts.length >= 3 ? parts[2] : "";
            String gitRemote = prefix.startsWith("git@")
                ? prefix : "";
            return new Decoded(token, sandbox, gitRemote);
        }
        throw new IllegalArgumentException("Invalid work secret format");
    }

    /** 构造标准 SDK URL (v1). */
    public static String buildSdkUrl(String base, String sessionId) {
        if (base == null || base.isBlank()) throw new IllegalArgumentException("base required");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId required");
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/sessions/" + sessionId + "/sdk";
    }

    /** 构造 CCR v2 SDK URL. */
    public static String buildCCRv2SdkUrl(String base, String sessionId) {
        if (base == null || base.isBlank()) throw new IllegalArgumentException("base required");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId required");
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/v2/sessions/" + sessionId + "/sdk";
    }

    /** 简单 SHA-256 哈希, 用于 gitRemote 校验. */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}