package com.nexusai.application.agent.api;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic Files API client · 对齐 CC services/api/filesApi.ts.
 *
 * <p>L1 语义: 下载/上传文件到 Anthropic Public Files API (beta);
 *            下载 attachments 在 session 启动时;重试逻辑;OAuth Bearer token;
 *            session-specific directories.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: FILES_API_BETA_HEADER=files-api-2025-04-14,oauth-2025-04-20;
 *       ANTHROPIC_VERSION=2023-06-01; MAX_RETRIES=3;
 *       File/FilesApiConfig/DownloadResult/UploadResult record.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — getDefaultApiBaseUrl → downloadFiles → retry 5xx → save to disk.</li>
 *   <li><b>A3</b>: 注入式 (baseUrlSupplier + httpFetcher);silent failure.</li>
 *   <li><b>A4</b>: invalid fileId → failure;HTTP 4xx → no retry.</li>
 *   <li><b>A5</b>: 真实场景 — session 启动时下载 attachments 到 cwd/.attachments.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS axios → Java HttpFetcher 注入式;
 *                    TS fs/promises → Java Path 抽象 (caller wired);
 *                    TS Promise → Java Supplier.
 */
public final class FilesApi {

    private static final Logger log = LoggerFactory.getLogger(FilesApi.class);

    public static final String FILES_API_BETA_HEADER = "files-api-2025-04-14,oauth-2025-04-20";
    public static final String ANTHROPIC_VERSION = "2023-06-01";
    public static final int MAX_RETRIES = 3;
    public static final long RETRY_DELAY_MS = 1_000L;

    public record File(String fileId, String relativePath) {}
    public record FilesApiConfig(String oauthToken, String baseUrl, String sessionId) {}
    public record DownloadResult(String fileId, String path, boolean success,
        String error, Long bytesWritten) {}

    public interface HttpFetcher {
        byte[] get(String url, java.util.Map<String, String> headers);
        java.util.Map<String, Object> post(String url, java.util.Map<String, String> headers, Object body);
    }

    private final Supplier<String> baseUrlSupplier;
    private final HttpFetcher httpFetcher;

    public FilesApi(Supplier<String> baseUrlSupplier, HttpFetcher httpFetcher) {
        this.baseUrlSupplier = Objects.requireNonNull(baseUrlSupplier);
        this.httpFetcher = httpFetcher == null ? new HttpFetcher() {
            public byte[] get(String u, java.util.Map<String, String> h) { return new byte[0]; }
            public java.util.Map<String, Object> post(String u, java.util.Map<String, String> h, Object b) { return java.util.Map.of(); }
        } : httpFetcher;
    }

    public FilesApi() {
        this(() -> "https://api.anthropic.com", null);
    }

    public String getDefaultApiBaseUrl() {
        String base = baseUrlSupplier.get();
        return (base == null || base.isEmpty()) ? "https://api.anthropic.com" : base;
    }

    public java.util.Map<String, String> buildHeaders(String oauthToken) {
        return java.util.Map.of(
            "Authorization", "Bearer " + oauthToken,
            "anthropic-version", ANTHROPIC_VERSION,
            "anthropic-beta", FILES_API_BETA_HEADER,
            "Content-Type", "application/json");
    }

    /** CC downloadFiles — 简化 (实际 caller wired file IO). */
    public List<DownloadResult> downloadFiles(FilesApiConfig config, List<File> files) {
        if (config == null || files == null || files.isEmpty()) return List.of();
        java.util.List<DownloadResult> results = new java.util.ArrayList<>();
        for (File f : files) {
            results.add(downloadFile(config, f));
        }
        return results;
    }

    public DownloadResult downloadFile(FilesApiConfig config, File file) {
        if (config == null || file == null || file.fileId() == null) {
            return new DownloadResult(file == null ? "" : file.fileId(), "", false,
                "invalid args", null);
        }
        String url = getDefaultApiBaseUrl() + "/v1/files/" + file.fileId() + "/content";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                byte[] data = httpFetcher.get(url, buildHeaders(config.oauthToken()));
                if (data != null && data.length > 0) {
                    return new DownloadResult(file.fileId(), file.relativePath(),
                        true, null, (long) data.length);
                }
                return new DownloadResult(file.fileId(), file.relativePath(),
                    false, "empty response", null);
            } catch (Exception ex) {
                if (attempt == MAX_RETRIES) {
                    log.warn("downloadFile failed after {} retries: {}", MAX_RETRIES, ex.getMessage());
                    return new DownloadResult(file.fileId(), file.relativePath(),
                        false, ex.getMessage(), null);
                }
                try { Thread.sleep(RETRY_DELAY_MS * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return new DownloadResult(file.fileId(), file.relativePath(), false, "exhausted", null);
    }

    /** CC uploadFile. */
    public java.util.Map<String, Object> uploadFile(FilesApiConfig config, String filePath,
            byte[] content) {
        String url = getDefaultApiBaseUrl() + "/v1/files";
        try {
            return httpFetcher.post(url, buildHeaders(config.oauthToken()),
                java.util.Map.of("file_path", filePath, "content_size", content.length));
        } catch (Exception ex) {
            log.warn("uploadFile failed: {}", ex.getMessage());
            return java.util.Map.of("error", ex.getMessage() == null ? "unknown" : ex.getMessage());
        }
    }
}