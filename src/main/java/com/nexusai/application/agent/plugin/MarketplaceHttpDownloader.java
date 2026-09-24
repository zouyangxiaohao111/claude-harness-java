package com.nexusai.application.agent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Marketplace HTTP 下载器 · 支撑 {@code {source:'url'}} 源物化（MinIO / 静态 HTTP 托管）。
 *
 * <p>与 {@link MarketplaceSyncService}（纯 git）平级：本类负责从 URL 下载 marketplace 内容，
 * 不掺入 git 语义。下载内容兼容两种形态：
 * <ul>
 *   <li><b>zip 包</b>（magic {@code PK\x03\x04}）→ 解压到目标目录（含路径穿越防护）；</li>
 *   <li><b>单文件 marketplace.json</b> → 落到目标目录根。</li>
 * </ul>
 *
 * <p><b>统一落盘约定</b>：下载/解压后，若 marketplace.json 位于目录根（非标准
 * {@code .claude-plugin/marketplace.json} 布局），自动同步一份到
 * {@code .claude-plugin/marketplace.json} —— 保证下游 {@code readCachedMarketplace} /
 * {@code requireCachedMarketplaceReadable} 的嵌套路径命中。返回路径优先嵌套路径。
 *
 * <p><b>fail-loud</b>：非 200 / 下载失败 / 解压含路径穿越 / 无 marketplace.json → 抛
 * {@link IOException}（中文），绝不静默降级。
 */
public class MarketplaceHttpDownloader {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceHttpDownloader.class);

    /** 下载超时（秒）· 市场包一般 <10MB，60s 充裕。 */
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 60;

    /** zip 魔数 PK\x03\x04。 */
    private static final byte[] ZIP_MAGIC = {(byte) 0x50, (byte) 0x4B, 0x03, 0x04};

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /**
     * 下载并物化 marketplace 内容到 {@code targetDir}。
     *
     * @param url      marketplace URL（zip 或 marketplace.json）
     * @param headers  额外请求头（可 null；MinIO 公开读通常不需要）
     * @param targetDir 目标目录（不存在则创建）
     * @return marketplace.json 的绝对路径（优先 {@code .claude-plugin/marketplace.json}，否则目录根）
     * @throws IOException 下载失败 / 非 200 / 解压不安全 / 未找到 marketplace.json
     */
    public String downloadMarketplace(String url, Map<String, String> headers, String targetDir) throws IOException {
        Path dir = Paths.get(targetDir);
        Files.createDirectories(dir);
        Path tempFile = Files.createTempFile("nexusai-marketplace-", ".tmp");
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .GET();
            if (headers != null) {
                headers.forEach(b::header);
            }
            HttpResponse<Path> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofFile(tempFile));
            int sc = resp.statusCode();
            if (sc != 200) {
                throw new IOException("marketplace 下载失败（HTTP " + sc + "）: " + redact(url));
            }
            if (isZip(tempFile)) {
                extractZip(tempFile, dir);
            } else {
                Files.move(tempFile, dir.resolve("marketplace.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            if (log.isInfoEnabled()) {
                log.info("[MarketplaceHttpDownloader] 已下载 {} → {}（{} 字节）",
                    redact(url), dir, contentBytes(dir));
            }
            return locateMarketplaceJson(dir, url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("marketplace 下载被中断: " + redact(url), e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /**
     * 下载并解压插件包（plugin.zip）到目标目录 · 与 {@link #downloadMarketplace} 的区别：
     * 本方法<b>不要求内容含 marketplace.json</b>——插件包内是 {@code .claude-plugin/plugin.json}
     * + agents/skills/commands 等，无市场索引。
     *
     * @param url       插件包 URL（zip）
     * @param headers   额外请求头（可 null）
     * @param targetDir 目标目录（不存在则创建）
     * @throws IOException 下载失败 / 非 200 / 非 zip
     */
    public void downloadArchive(String url, Map<String, String> headers, String targetDir) throws IOException {
        Path dir = Paths.get(targetDir);
        Files.createDirectories(dir);
        Path tempFile = Files.createTempFile("nexusai-plugin-", ".tmp");
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .GET();
            if (headers != null) {
                headers.forEach(b::header);
            }
            HttpResponse<Path> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofFile(tempFile));
            int sc = resp.statusCode();
            if (sc != 200) {
                throw new IOException("插件包下载失败（HTTP " + sc + "）: " + redact(url));
            }
            if (!isZip(tempFile)) {
                throw new IOException("插件包不是 zip（无法解压）: " + redact(url));
            }
            extractZip(tempFile, dir);
            if (log.isInfoEnabled()) {
                log.info("[MarketplaceHttpDownloader] 插件包已解压 {} → {}", redact(url), dir);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("插件包下载被中断: " + redact(url), e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 内部工具
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 定位 marketplace.json 并保证嵌套布局：优先 {@code .claude-plugin/marketplace.json}；
     * 若仅根目录有 marketplace.json（用户自建布局），同步一份到嵌套路径（下游按嵌套查找）。
     */
    private static String locateMarketplaceJson(Path dir, String url) throws IOException {
        Path nested = dir.resolve(".claude-plugin").resolve("marketplace.json");
        Path root = dir.resolve("marketplace.json");
        if (Files.isRegularFile(nested)) {
            return nested.toString();
        }
        if (Files.isRegularFile(root)) {
            Files.createDirectories(nested.getParent());
            Files.copy(root, nested, StandardCopyOption.REPLACE_EXISTING);
            if (log.isDebugEnabled()) {
                log.debug("[MarketplaceHttpDownloader] 根 marketplace.json 已同步到 .claude-plugin/marketplace.json");
            }
            return nested.toString();
        }
        throw new IOException("下载内容中未找到 marketplace.json: " + redact(url));
    }

    /** 目标目录内容体积（日志用）。 */
    private static long contentBytes(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Files.size(dir);
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    /** 判断文件是否为 zip（magic 头）。 */
    private static boolean isZip(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            byte[] head = new byte[4];
            int n = in.read(head);
            if (n < 4) {
                return false;
            }
            for (int i = 0; i < 4; i++) {
                if (head[i] != ZIP_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 解压 zip 到目标目录（路径穿越防护：entry 归一化后必须落在 target 内）。 */
    private static void extractZip(Path zip, Path target) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("marketplace zip 含路径穿越条目，已拒绝解压: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /** URL 脱敏（日志；避免在日志中残留凭据/预签名查询参数）。 */
    private static String redact(String url) {
        if (url == null) {
            return "null";
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) + "?…" : url;
    }
}
