package com.nexusai.application.agent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Download · 对齐 CC utils/nativeInstaller/download.ts (524 行).
 *
 * <p>L1: HttpClient 下载 + SHA-256 校验 + stall 30s×2 检测.
 *
 * <p>L2 契约: 错误码 "CHECKSUM_MISMATCH" / "STALL_DETECTED" 字面同形.
 */
public final class Download {

    private static final Logger log = LoggerFactory.getLogger(Download.class);

    /** Stall 检测间隔 (ms). */
    public static final long STALL_INTERVAL_MS = 30_000L;
    /** Stall 检测超时次数 — 连续 N 次无进度判定 stall. */
    public static final int STALL_THRESHOLD = 2;

    /** 错误码常量 — 与 TS 字面同形. */
    public static final String CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH";
    public static final String STALL_DETECTED = "STALL_DETECTED";

    private final HttpClient httpClient;

    public Download() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public Download(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 下载 url 到 targetPath;SHA-256 校验;stall 检测.
     *
     * @param expectedSha256 期望的小写 64 字符 hex (null 时跳过校验)
     */
    public void download(String url, Path targetPath, String expectedSha256) throws IOException {
        Files.createDirectories(targetPath.getParent());
        long contentLengthHint = -1L;
        long lastProgressBytes = -1L;
        int stallTicks = 0;

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(30))
            .GET()
            .build();

        HttpResponse<java.io.InputStream> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while sending request", ie);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        long total = resp.headers().firstValue("content-length")
            .map(Long::parseLong).orElse(-1L);
        contentLengthHint = total;

        try (var in = resp.body();
             FileChannel ch = FileChannel.open(targetPath,
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                 StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buf = new byte[64 * 1024];
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException nsa) {
                throw new IOException("SHA-256 not available", nsa);
            }

            long lastStallCheck = System.currentTimeMillis();
            int read;
            while ((read = in.read(buf)) != -1) {
                // update digest
                md.update(buf, 0, read);
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, read);
                while (bb.hasRemaining()) {
                    ch.write(bb);
                }
                long now = System.currentTimeMillis();
                long size = Files.size(targetPath);
                if (size == lastProgressBytes) {
                    if (now - lastStallCheck > STALL_INTERVAL_MS) {
                        stallTicks++;
                        if (stallTicks >= STALL_THRESHOLD) {
                            throw new IOException(STALL_DETECTED + ": no progress for "
                                + STALL_INTERVAL_MS + "ms x " + STALL_THRESHOLD);
                        }
                        lastStallCheck = now;
                    }
                } else {
                    stallTicks = 0;
                    lastProgressBytes = size;
                    lastStallCheck = now;
                }
            }
        }
        if (log.isInfoEnabled()) {
            log.info("Download complete url={} bytes={} (expected={})",
                url, Files.size(targetPath), contentLengthHint);
        }

        // SHA-256 校验
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String actual = sha256OfFile(targetPath);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException(CHECKSUM_MISMATCH
                    + ": expected=" + expectedSha256 + " actual=" + actual);
            }
            if (log.isDebugEnabled()) log.debug("SHA-256 OK {}", actual);
        }
    }

    /** 文件 SHA-256 hex (小写). */
    public String sha256OfFile(Path p) throws IOException {
        try (var in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) md.update(buf, 0, read);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException nsa) {
            throw new IOException("SHA-256 not available", nsa);
        }
    }
}
