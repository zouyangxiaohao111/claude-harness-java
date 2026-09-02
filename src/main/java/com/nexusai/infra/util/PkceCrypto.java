package com.nexusai.infra.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PkceCrypto · 对齐 CC services/oauth/crypto.ts.
 *
 * <p>L1 语义: PKCE (RFC 7636) 加密原语 + 高熵随机生成器。
 * <ul>
 *   <li>{@link #generateCodeVerifier()} — 32 bytes 随机 → base64URL (43 chars)</li>
 *   <li>{@link #generateCodeChallenge(String)} — verifier 的 SHA-256 → base64URL</li>
 *   <li>{@link #generateState()} — 32 bytes 随机 → base64URL (43 chars)</li>
 * </ul>
 *
 * <p>base64URL: 标准 base64 但 {@code + → -}, {@code / → _}, 丢弃 {@code =} padding。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 个 static method 输入输出严格对齐;verifier 长度 = 43 chars (32 bytes base64URL-no-padding)</li>
 *   <li><b>A2 Golden Trace</b>: randomBytes(32) → base64URL → 43 chars;
 *       sha256(verifier) → base64URL → 43 chars</li>
 *   <li><b>A3 纯函数</b>: 内部 {@link SecureRandom} 无状态 (每次自取);并发安全</li>
 *   <li><b>A4 边界</b>: 多次调用结果不同 (熵);verifier 长度固定 43;无 padding</li>
 *   <li><b>A5 业务场景</b>: OAuth PKCE flow — 生成 verifier/challenge 对;state 用于 CSRF 防护</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS {@code createHash('sha256')} + {@code hash.digest()} →
 * Java {@link MessageDigest};TS {@code randomBytes(32)} → Java {@link SecureRandom}.
 * 字符编码统一 UTF-8 (verifier 内部为 byte 序列,实际是基于 ASCII 字符集)。
 */
public final class PkceCrypto {

    private static final SecureRandom RNG = new SecureRandom();

    private PkceCrypto() {
        // 工具类
    }

    /**
     * Generate a 32-byte cryptographically random code verifier (PKCE).
     * Returns base64URL-encoded string without padding (43 chars).
     */
    public static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return base64URLEncode(bytes);
    }

    /**
     * Generate SHA-256 of the verifier, base64URL-encoded without padding (43 chars).
     * Per RFC 7636 §4.2.
     */
    public static String generateCodeChallenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return base64URLEncode(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 always available on Java; defensive throw.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Generate a 32-byte cryptographically random state token (CSRF protection).
     * Returns base64URL-encoded string without padding (43 chars).
     */
    public static String generateState() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return base64URLEncode(bytes);
    }

    /**
     * RFC 4648 §5 base64url (no padding). Mirrors CC base64URLEncode(Buffer).
     */
    private static String base64URLEncode(byte[] buffer) {
        return Base64.getEncoder().withoutPadding().encodeToString(buffer)
            .replace('+', '-')
            .replace('/', '_');
    }
}
