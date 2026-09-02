package com.nexusai.application.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BriefTool 附件上传器 · 对齐 CC tools/BriefTool/upload.ts.
 *
 * <p>L1 语义: 上传 BriefTool 附件到 /api/oauth/file_upload 让 web viewer 预览.
 *            当 repl bridge 启用时: 路径对 web viewer 无意义,上传并返回 file_uuid.
 *            best-effort: 任何失败 (no token / bridge off / 网络 / 4xx) → debug log + return undefined.
 *            本地渲染仍可用 {path, size, isImage}.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: uploadBriefAttachment(path, size, ctx) → Optional&lt;String&gt;;
 *       MAX_UPLOAD_BYTES=30MB; UPLOAD_TIMEOUT_MS=30000; MIME_BY_EXT 5 个;
 *       guessMimeType 后备 application/octet-stream;
 *       getBridgeBaseUrl 优先级: override > ANTHROPIC_BASE_URL > oauthConfig.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — BRIDGE_MODE on + replBridgeEnabled → size&lt;=MAX →
 *       token 存在 → readFile → guessMimeType → multipart/form-data →
 *       POST /api/oauth/file_upload → 201 + file_uuid → return uuid.
 *       任何一步失败 → return undefined (graceful).</li>
 *   <li><b>A3</b>: 状态机: SKIPPED (bridge off/no token/size limit) → READING → UPLOADING → (OK|FAIL).</li>
 *   <li><b>A4</b>: !replBridgeEnabled → undefined;size&gt;MAX → undefined;无 token → undefined;
 *       read 失败 → undefined;非 201 → undefined;响应缺 file_uuid → undefined;throw → undefined.</li>
 *   <li><b>A5</b>: 真实场景 — .png 2MB attachment + bridge enabled + token →
 *       upload → 201 → file_uuid;失败时不阻断本地渲染.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `axios.post` → 注入式 HttpPoster (testable);
 *                    TS `getOauthConfig()` → 注入式 Supplier;
 *                    TS `process.env.ANTHROPIC_BASE_URL` → 注入式 Supplier;
 *                    TS `getBridgeAccessToken()` → 注入式 Supplier;
 *                    TS `readFile` → 注入式 FileReader;
 *                    TS `randomUUID()` → Java UUID.randomUUID().
 */
public final class BriefAttachmentUploader {

    private static final Logger log = LoggerFactory.getLogger(BriefAttachmentUploader.class);

    public static final long MAX_UPLOAD_BYTES = 30L * 1024 * 1024;
    public static final long UPLOAD_TIMEOUT_MS = 30_000L;

    private static final Map<String, String> MIME_BY_EXT = new LinkedHashMap<>();
    static {
        MIME_BY_EXT.put(".png", "image/png");
        MIME_BY_EXT.put(".jpg", "image/jpeg");
        MIME_BY_EXT.put(".jpeg", "image/jpeg");
        MIME_BY_EXT.put(".gif", "image/gif");
        MIME_BY_EXT.put(".webp", "image/webp");
    }

    private final BooleanSupplier bridgeModeFeature;
    private final Supplier<String> oauthConfigBaseUrlSupplier;
    private final Supplier<String> envBaseUrlSupplier;       // [W4-1] DB provider baseUrl（原 ANTHROPIC_BASE_URL env）
    private final Supplier<String> bridgeBaseUrlOverrideSupplier;
    private final Supplier<String> accessTokenSupplier;
    private final FileReader fileReader;
    private final HttpPoster httpPoster;
    private final ResponseParser responseParser;

    public BriefAttachmentUploader(BooleanSupplier bridgeModeFeature,
                                     Supplier<String> oauthConfigBaseUrlSupplier,
                                     Supplier<String> envBaseUrlSupplier,
                                     Supplier<String> bridgeBaseUrlOverrideSupplier,
                                     Supplier<String> accessTokenSupplier,
                                     FileReader fileReader,
                                     HttpPoster httpPoster,
                                     ResponseParser responseParser) {
        this.bridgeModeFeature = Objects.requireNonNull(bridgeModeFeature);
        this.oauthConfigBaseUrlSupplier = Objects.requireNonNull(oauthConfigBaseUrlSupplier);
        this.envBaseUrlSupplier = Objects.requireNonNull(envBaseUrlSupplier);
        this.bridgeBaseUrlOverrideSupplier = Objects.requireNonNull(bridgeBaseUrlOverrideSupplier);
        this.accessTokenSupplier = Objects.requireNonNull(accessTokenSupplier);
        this.fileReader = Objects.requireNonNull(fileReader);
        this.httpPoster = Objects.requireNonNull(httpPoster);
        this.responseParser = Objects.requireNonNull(responseParser);
    }

    /** Upload context. */
    public record BriefUploadContext(boolean replBridgeEnabled) {}

    /** File reader (注入). */
    @FunctionalInterface
    public interface FileReader {
        byte[] read(String path) throws Exception;
    }

    /** HTTP poster (注入). */
    @FunctionalInterface
    public interface HttpPoster {
        HttpResult post(String url, Map<String, String> headers, byte[] body, long timeoutMs);
    }

    public record HttpResult(int status, String body) {
        public boolean isCreated() { return status == 201; }
    }

    /** Response parser (注入). */
    @FunctionalInterface
    public interface ResponseParser {
        Optional<String> parseFileUuid(String body);
    }

    /** CC getBridgeBaseUrl — 优先级 chain. */
    public String getBridgeBaseUrl() {
        String override = bridgeBaseUrlOverrideSupplier.get();
        if (override != null && !override.isEmpty()) return override;
        String envUrl = envBaseUrlSupplier.get();
        if (envUrl != null && !envUrl.isEmpty()) return envUrl;
        return oauthConfigBaseUrlSupplier.get();
    }

    /** CC guessMimeType. */
    public static String guessMimeType(String filename) {
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx < 0) return "application/octet-stream";
        String ext = filename.substring(dotIdx).toLowerCase();
        return MIME_BY_EXT.getOrDefault(ext, "application/octet-stream");
    }

    /** CC uploadBriefAttachment — 主链. */
    public Optional<String> uploadBriefAttachment(String fullPath, long size,
                                                    BriefUploadContext ctx) {
        if (!bridgeModeFeature.getAsBoolean()) {
            return Optional.empty();
        }
        if (!ctx.replBridgeEnabled()) {
            return Optional.empty();
        }

        if (size > MAX_UPLOAD_BYTES) {
            log.debug("[BriefUpload] skip {}: {} bytes exceeds {} limit",
                fullPath, size, MAX_UPLOAD_BYTES);
            return Optional.empty();
        }

        String token = accessTokenSupplier.get();
        if (token == null || token.isEmpty()) {
            log.debug("[BriefUpload] skip: no oauth token");
            return Optional.empty();
        }

        byte[] content;
        try {
            content = fileReader.read(fullPath);
        } catch (Exception e) {
            log.debug("[BriefUpload] read failed for {}: {}", fullPath, e.getMessage());
            return Optional.empty();
        }

        String baseUrl = getBridgeBaseUrl();
        String url = baseUrl + "/api/oauth/file_upload";
        String filename = basename(fullPath);
        String mimeType = guessMimeType(filename);
        String boundary = "----FormBoundary" + UUID.randomUUID();

        byte[] body = buildMultipartBody(boundary, filename, mimeType, content);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        headers.put("Content-Length", String.valueOf(body.length));

        try {
            HttpResult result = httpPoster.post(url, headers, body, UPLOAD_TIMEOUT_MS);
            if (!result.isCreated()) {
                log.debug("[BriefUpload] upload failed for {}: status={} body={}",
                    fullPath, result.status(), snippet(result.body(), 200));
                return Optional.empty();
            }
            Optional<String> fileUuid = responseParser.parseFileUuid(result.body());
            if (fileUuid.isEmpty()) {
                log.debug("[BriefUpload] unexpected response shape for {}", fullPath);
                return Optional.empty();
            }
            log.debug("[BriefUpload] uploaded {} → {} ({} bytes)",
                fullPath, fileUuid.get(), size);
            return fileUuid;
        } catch (Exception e) {
            log.debug("[BriefUpload] upload threw for {}: {}", fullPath, e.getMessage());
            return Optional.empty();
        }
    }

    /** Build multipart/form-data body. */
    static byte[] buildMultipartBody(String boundary, String filename, String mimeType,
                                       byte[] content) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new RuntimeException("build multipart body failed", e);
        }
        return out.toByteArray();
    }

    static String basename(String path) {
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    static String snippet(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /** Java Optional. */
    public static final class Optional<T> {
        private final T value;
        private Optional(T value) { this.value = value; }
        public static <T> Optional<T> empty() { return new Optional<>(null); }
        public static <T> Optional<T> of(T v) { return new Optional<>(v); }
        public boolean isPresent() { return value != null; }
        public boolean isEmpty() { return value == null; }
        public T get() { return value; }
        @SuppressWarnings("unchecked")
        public static <T> Optional<T> ofNullable(T v) {
            return v == null ? (Optional<T>) empty() : new Optional<>(v);
        }
    }
}
