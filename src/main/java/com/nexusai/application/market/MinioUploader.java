package com.nexusai.application.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * MinIO 上传器（AWS Signature V4 · path-style）· 插件市场上架用。
 *
 * <p>为什么自己实现：后端当前没有任何 S3/MinIO 客户端依赖（只有下载器
 * {@code MarketplaceHttpDownloader}），而上架只需要「PUT 一个对象」这一个动作。
 * 引入整套 AWS SDK 只为一个 PUT 不划算 —— 本类照搬
 * {@code marketplace/publish_marketplace.py} 里已验证可用的 SigV4 流程（纯 JDK）。
 *
 * <p>兼容性：MinIO 默认 path-style（{@code /bucket/key}），region 默认 {@code us-east-1}；
 * 若换成真实 S3 需补 virtual-host 风格与 region 解析。
 */
public class MinioUploader {

    private static final Logger log = LoggerFactory.getLogger(MinioUploader.class);

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final HttpClient httpClient;

    /**
     * @param endpoint  MinIO 根地址（如 {@code http://192.168.20.125:9000}，不带 bucket）
     * @param bucket    桶名
     * @param accessKey 访问键
     * @param secretKey 密钥
     * @param region    region（MinIO 默认 us-east-1）
     */
    public MinioUploader(String endpoint, String bucket, String accessKey, String secretKey, String region) {
        this.endpoint = stripTrailingSlash(endpoint);
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region == null || region.isBlank() ? "us-east-1" : region;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * 上传对象（覆盖同名键）。
     *
     * @param objectKey 桶内键（如 {@code marketplace/skills/doc-writer/0.1.1/plugin.zip}）
     * @param payload   对象内容
     * @param contentType MIME（如 application/zip / application/json）
     * @throws IOException 网络失败或非 2xx 响应
     */
    public void upload(String objectKey, byte[] payload, String contentType) throws IOException {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String payloadHash = hex(sha256(payload));
        URI uri = URI.create(endpoint + "/" + bucket + "/" + encodePath(objectKey));
        String hostHeader = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

        String canonicalRequest = "PUT\n"
            + uri.getRawPath() + "\n"
            + "\n"
            + "host:" + hostHeader + "\n"
            + "x-amz-content-sha256:" + payloadHash + "\n"
            + "x-amz-date:" + amzDate + "\n"
            + "\n"
            + "host;x-amz-content-sha256;x-amz-date\n"
            + payloadHash;

        String scope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
            + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = signingKey(secretKey, dateStamp, region, SERVICE);
        String signature = hex(hmacSha256(signingKey, stringToSign));
        String authorization = ALGORITHM + " Credential=" + accessKey + "/" + scope
            + ", SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Authorization", authorization)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("MinIO 上传失败 HTTP " + resp.statusCode() + " key=" + objectKey
                    + " body=" + truncate(resp.body()));
            }
            if (log.isDebugEnabled()) {
                log.debug("[MinioUploader] 上传成功 key={} bytes={}", objectKey, payload.length);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MinIO 上传被中断 key=" + objectKey, e);
        }
    }

    /** 下载对象（清单读回校验用）。 */
    public byte[] download(String objectKey) throws IOException {
        URI uri = URI.create(endpoint + "/" + bucket + "/" + encodePath(objectKey));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        try {
            HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) {
                throw new java.io.FileNotFoundException("MinIO 对象不存在 key=" + objectKey);
            }
            if (resp.statusCode() != 200) {
                throw new IOException("MinIO 下载失败 HTTP " + resp.statusCode() + " key=" + objectKey);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MinIO 下载被中断 key=" + objectKey, e);
        }
    }

    // ── SigV4 基元 ─────────────────────────────────────────────────────────

    private static byte[] signingKey(String secret, String dateStamp, String region, String service) {
        byte[] kDate = hmacSha256(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 计算失败", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    /** 键逐段 URL 编码，保留 {@code /}（与 python 脚本的 {@code quote(key, safe='/')} 等价）。 */
    private static String encodePath(String key) {
        String[] parts = key.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(java.net.URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
