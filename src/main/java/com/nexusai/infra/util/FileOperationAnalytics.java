package com.nexusai.infra.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * FileOperationAnalytics · 对齐 CC utils/fileOperationAnalytics.ts.
 *
 * <p>L1 语义: 文件操作 analytics — privacy-preserving SHA256 hash (16 chars)
 * for file paths + full SHA256 for content + Statsig logEvent.
 * 用于去重 / 变更检测 / 隐私上报。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: hashFilePath(path)→String(16) + hashFileContent(content)→String(64) + logFileOperation(params)→void;MAX_CONTENT_HASH_SIZE=100KB</li>
 *   <li><b>A2 Golden Trace</b>: sha256('foo') hex prefix 16 chars;content {'op':'read'} 完整 hex 64 chars</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 hash;stateless</li>
 *   <li><b>A4 边界</b>: null path throws (caller guard);content > 100KB skip hash;NoSuchAlgorithmException throws IllegalStateException</li>
 *   <li><b>A5 业务场景</b>: Read/Write/Edit tool 完成 → logFileOperation 注入 Statsig (用 path hash 而非 raw path)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS crypto.createHash('sha256').digest('hex') →
 * Java MessageDigest.getInstance('SHA-256') + HexFormat;
 * TS logEvent(sdk) → Java Consumer&lt;Map&gt; 注入式;
 * TS Record → Java LinkedHashMap。
 */
public final class FileOperationAnalytics {

    public static final int MAX_CONTENT_HASH_SIZE = 100 * 1024;

    private FileOperationAnalytics() {}

    /**
     * 16-char hex prefix of SHA256(path). Privacy-preserving — raw path never logged.
     */
    public static String hashFilePath(String filePath) {
        if (filePath == null) throw new IllegalArgumentException("filePath must not be null");
        return sha256Hex(filePath).substring(0, 16);
    }

    /** Full 64-char SHA256 hex digest. */
    public static String hashFileContent(String content) {
        if (content == null) throw new IllegalArgumentException("content must not be null");
        return sha256Hex(content);
    }

    /**
     * Log a file operation analytics event. Caller injects an {@code eventLogger}
     * (typically Statsig SDK, but any Consumer&lt;Map&lt;String,Object&gt;&gt; suffices).
     *
     * @param params      operation metadata (see inner fields)
     * @param eventLogger injected Statsig adapter
     */
    public static void logFileOperation(
        Params params,
        Consumer<Map<String, Object>> eventLogger) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("operation", params.operation);
        metadata.put("tool", params.tool);
        metadata.put("filePathHash", hashFilePath(params.filePath));
        if (params.content != null && params.content.length() <= MAX_CONTENT_HASH_SIZE) {
            metadata.put("contentHash", hashFileContent(params.content));
        }
        if (params.type != null) metadata.put("type", params.type);
        eventLogger.accept(metadata);
    }

    /** Operation record. */
    public record Params(
        String operation,
        String tool,
        String filePath,
        String content,
        String type) {
        public Params {
            if (!"read".equals(operation) && !"write".equals(operation) && !"edit".equals(operation)) {
                throw new IllegalArgumentException("operation must be read|write|edit");
            }
            if (!"FileReadTool".equals(tool) && !"FileWriteTool".equals(tool) && !"FileEditTool".equals(tool)) {
                throw new IllegalArgumentException("tool must be FileReadTool|FileWriteTool|FileEditTool");
            }
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
