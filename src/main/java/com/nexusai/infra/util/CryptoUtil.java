package com.nexusai.infra.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES/GCM 256-bit 加解密工具。
 *
 * <p>用于加密 LLM Provider 的 API key（存数据库时密文存储）。
 * 解密后的明文仅在运行时使用，发完请求即丢弃（不写日志 / 不返回 API）。
 *
 * <p>算法：
 * <ul>
 *   <li>Cipher：{@code AES/GCM/NoPadding}</li>
 *   <li>Key 长度：256 bit（32 bytes），从 {@code nexusai.encryption.key}（base64）读取</li>
 *   <li>IV 长度：96 bit（12 bytes），每次 {@link #encrypt} 用 {@link SecureRandom#getInstanceStrong()} 重新生成</li>
 *   <li>Tag 长度：128 bit</li>
 *   <li>密文格式：{@code base64(iv || ciphertext_with_tag)}，整体存为单字符串</li>
 * </ul>
 *
 * <p>启动时若 {@code nexusai.encryption.key} 缺失或解码后不是 32 bytes，立即抛
 * {@link IllegalStateException}，避免运行时静默回退到错误密钥。
 *
 * <p>v2 应从 macOS Keychain / 环境变量加载（开发期 base64 占位即可）。
 */
@Component
public class CryptoUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;  // 256 bit

    private final SecretKeySpec keySpec;

    public CryptoUtil(@Value("${nexusai.encryption.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                "nexusai.encryption.key is not configured. Generate one with: openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "nexusai.encryption.key is not valid base64: " + e.getMessage(), e);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                "nexusai.encryption.key must decode to " + KEY_LENGTH_BYTES
                    + " bytes (AES-256). Got: " + decoded.length + " bytes");
        }
        this.keySpec = new SecretKeySpec(decoded, "AES");
    }

    /**
     * 加密明文 → 返回 base64(iv || ciphertext_with_tag)。
     *
     * @param plaintext 待加密明文（允许 null，返回 null）
     * @return base64 编码的密文；{@code plaintext == null} → null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            // getInstanceStrong() 熵更足，但首次调用可能阻塞（/dev/random 阻塞）。
            // 用默认 SecureRandom（无 strong 限制）以免影响启动/请求延迟。
            SecureRandom rng = new SecureRandom();
            rng.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[IV_LENGTH_BYTES + cipherBytes.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH_BYTES);
            System.arraycopy(cipherBytes, 0, out, IV_LENGTH_BYTES, cipherBytes.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES/GCM encryption failed", e);
        }
    }

    /**
     * 解密 base64(iv || ciphertext_with_tag) → 明文。
     *
     * @param ciphertext base64 编码的密文（{@code null} → 返回 {@code null}）
     * @return 解密后的明文
     * @throws IllegalStateException 解码失败 / IV 长度错 / 认证失败（被篡改）
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            if (all.length < IV_LENGTH_BYTES + (TAG_LENGTH_BITS / 8)) {
                throw new IllegalStateException("ciphertext too short");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] cipherBytes = new byte[all.length - IV_LENGTH_BYTES];
            System.arraycopy(all, IV_LENGTH_BYTES, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM 认证失败 → AEADBadTagException（也包在 IllegalStateException 中）
            throw new IllegalStateException("AES/GCM decryption failed: " + e.getMessage(), e);
        }
    }

    /** 测试用入口 */
    public static void main(String[] args) {
        String key = "ZGV2LWtleS1ub3QtZm9yLXByb2R1Y3Rpb24tdXNlLWluLWRldi1vbmx5LWFhYWVlZ";
        CryptoUtil c = new CryptoUtil(key);
        String[] tests = {"sk-test-1234", "短", "a very long key with special chars !@#$%^&*()"};
        for (String t : tests) {
            String enc = c.encrypt(t);
            String dec = c.decrypt(enc);
            System.out.println("plain=" + t + " enc.len=" + enc.length() + " match=" + t.equals(dec));
        }
    }
}
