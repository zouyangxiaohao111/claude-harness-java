package com.nexusai.infra.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * Fingerprint · 对齐 CC utils/fingerprint.ts.
 *
 * <p>L1 语义: Claude Code attribution 3-char fingerprint (SHA256)。
 * 算法: SHA256(SALT + msg[4] + msg[7] + msg[20] + version)[:3];缺字符填 '0'。
 * 后端 API (1P/3P Bedrock/Vertex/Azure) 期望该指纹一致。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: FINGERPRINT_SALT 常量 + extractFirstMessageText + computeFingerprint + computeFingerprintFromMessages 4 静态方法</li>
 *   <li><b>A2 Golden Trace</b>: 'hello world'[4,7,20]='ow0';FINGERPRINT_SALT+chars+version → SHA256[:3] = 3 hex chars</li>
 *   <li><b>A3 副作用</b>: SHA-256 stateless;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: 空 messages→'' 文本 → chars='000';empty string version OK;缺 index → '0'</li>
 *   <li><b>A5 业务场景</b>: Claude Code attribution 追踪 1P/3P 来源 (Bedrock/Vertex/Azure)</li>
 * </ul>
 *
 * <p>L3 升级: TS crypto.createHash('sha256') → Java MessageDigest.getInstance;
 * TS template literal → Java String concatenation;
 * TS slice(0, 3) → Java substring(0, 3).
 */
public final class Fingerprint {

    /**
     * Hardcoded salt from backend validation. Must match exactly.
     * Mirrors CC FINGERPRINT_SALT.
     */
    public static final String FINGERPRINT_SALT = "59cf53e54c78";

    private static final int[] FINGERPRINT_INDICES = { 4, 7, 20 };

    private Fingerprint() {}

    /**
     * Extract the text content from the first user message.
     */
    @SuppressWarnings("unchecked")
    public static String extractFirstMessageText(List<Map<String, Object>> messages) {
        if (messages == null) return "";
        for (Map<String, Object> msg : messages) {
            if ("user".equals(msg.get("type"))) {
                Object content = msg.get("message");
                if (content instanceof Map) {
                    Object text = ((Map<String, Object>) content).get("content");
                    if (text instanceof String) return (String) text;
                }
                continue;
            }
        }
        return "";
    }

    /**
     * Compute the 3-character hex fingerprint from message text + version.
     */
    public static String computeFingerprint(String messageText, String version) {
        if (messageText == null) messageText = "";
        if (version == null) version = "";
        StringBuilder chars = new StringBuilder();
        for (int i : FINGERPRINT_INDICES) {
            chars.append(i < messageText.length() ? messageText.charAt(i) : '0');
        }
        String input = FINGERPRINT_SALT + chars + version;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // Take first 3 hex chars (first 1.5 bytes; pad to 2 chars = 4 hex)
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 2 && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i] & 0xFF));
            }
            // hex.length() may be 2-4; ensure 3
            String result = hex.toString();
            return (result + "000").substring(0, 3);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Compute fingerprint from the first user message in a list.
     */
    public static String computeFingerprintFromMessages(
        List<Map<String, Object>> messages,
        String version) {
        return computeFingerprint(extractFirstMessageText(messages), version);
    }
}
