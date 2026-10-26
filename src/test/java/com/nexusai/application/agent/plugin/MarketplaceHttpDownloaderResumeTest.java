package com.nexusai.application.agent.plugin;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件包下载断点续传（规则九：测意图）。
 *
 * <p><b>WHY</b>（2026-10-26 用户要求「安装要有进度条，支持断点续传」）：旧实现每次
 * {@code Files.createTempFile} 随机名、从 0 字节下载、finally 必删，网络一断就整包重来。
 * 本测试钉住三件事：
 * <ol>
 *   <li><b>续传生效</b>：本地已有 N 字节残片时发 {@code Range: bytes=N-}，成功后包内容完整；
 *       进度回调从累计字节（而非本次读取量）起算 —— 否则前端进度会从 0 重跳。</li>
 *   <li><b>成功清理</b>：解压成功后删除 {@code .part}，不会残留。</li>
 *   <li><b>区间失效自愈</b>：残片长度 >= 服务端资源（资源更新/上次解压失败）时，416 →
 *       清残片从 0 重下一次，不把用户卡死在错误区间。</li>
 * </ol>
 */
class MarketplaceHttpDownloaderResumeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("残片续传：Range 从 N 字节起下，进度按累计字节推进，成功后删除 .part")
    void resumesFromPartialFile() throws Exception {
        byte[] zip = zipWithSkill("demo");
        AtomicLong firstProgress = new AtomicLong(-1L);
        AtomicLong lastRead = new AtomicLong(-1L);
        AtomicLong lastTotal = new AtomicLong(-1L);
        HttpServer server = rangeServer(zip, false);
        try {
            server.start();
            Path target = tempDir.resolve("cache/market/skills/demo/1.0.0");
            Files.createDirectories(target);
            int existing = zip.length / 2;
            Path part = MarketplaceHttpDownloader.partFileFor(target);
            Files.write(part, Arrays.copyOf(zip, existing));

            new MarketplaceHttpDownloader().downloadArchive(baseUrl(server) + "/plugin.zip",
                null, target.toString(), (read, total) -> {
                    firstProgress.compareAndSet(-1L, read);
                    lastRead.set(read);
                    lastTotal.set(total);
                });

            assertThat(Files.readString(target.resolve("SKILL.md")))
                .as("续传后必须得到完整、可解压的包")
                .contains("name: demo");
            assertThat(firstProgress.get())
                .as("续传首帧进度必须从残片长度起算（否则进度从 0 重跳）")
                .isGreaterThanOrEqualTo(existing);
            assertThat(lastRead.get()).as("最终进度=完整包长度").isEqualTo(zip.length);
            assertThat(lastTotal.get()).as("总长度来自 Content-Range").isEqualTo(zip.length);
            assertThat(part).as("解压成功后必须删除断点文件").doesNotExist();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("服务端忽略 Range（回 200）：覆盖残片从头下载，内容仍完整")
    void restartsWhenServerIgnoresRange() throws Exception {
        byte[] zip = zipWithSkill("fallback");
        HttpServer server = rangeServer(zip, true);
        try {
            server.start();
            Path target = tempDir.resolve("cache/market/skills/fallback/1.0.0");
            Files.createDirectories(target);
            Path part = MarketplaceHttpDownloader.partFileFor(target);
            Files.write(part, "garbage".getBytes(StandardCharsets.UTF_8));

            new MarketplaceHttpDownloader().downloadArchive(baseUrl(server) + "/plugin.zip",
                null, target.toString(), null);

            assertThat(Files.readString(target.resolve("SKILL.md"))).contains("name: fallback");
            assertThat(part).doesNotExist();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("残片超出资源长度（HTTP 416）：清理后从 0 重下，不卡死")
    void restartsFromZeroOnRangeNotSatisfiable() throws Exception {
        byte[] zip = zipWithSkill("oversize");
        HttpServer server = rangeServer(zip, false);
        try {
            server.start();
            Path target = tempDir.resolve("cache/market/skills/oversize/1.0.0");
            Files.createDirectories(target);
            Path part = MarketplaceHttpDownloader.partFileFor(target);
            // 残片比服务端资源还长（资源被重新发布 / 上次已下完但解压失败）
            Files.write(part, Arrays.copyOf(zip, zip.length + 32));

            new MarketplaceHttpDownloader().downloadArchive(baseUrl(server) + "/plugin.zip",
                null, target.toString(), null);

            assertThat(Files.readString(target.resolve("SKILL.md"))).contains("name: oversize");
            assertThat(part).doesNotExist();
        } finally {
            server.stop(0);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 测试桩
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 极简 Range 服务器：{@code ignoreRange=true} 时无视 Range 恒回 200（验证覆盖写路径）；
     * 否则 Range 起点 >= 资源长度回 416，合法区间回 206 + Content-Range。
     */
    private static HttpServer rangeServer(byte[] data, boolean ignoreRange) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plugin.zip", exchange -> {
            try {
                String range = exchange.getRequestHeaders().getFirst("Range");
                int start = 0;
                boolean partial = false;
                if (!ignoreRange && range != null && range.startsWith("bytes=")) {
                    String spec = range.substring("bytes=".length());
                    int dash = spec.indexOf('-');
                    start = Integer.parseInt(spec.substring(0, dash));
                    if (start >= data.length) {
                        exchange.getResponseHeaders().add("Content-Range", "bytes */" + data.length);
                        exchange.sendResponseHeaders(416, -1);
                        return;
                    }
                    partial = true;
                }
                int length = data.length - start;
                if (partial) {
                    exchange.getResponseHeaders().add("Content-Range",
                        "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
                    exchange.sendResponseHeaders(206, length);
                } else {
                    exchange.sendResponseHeaders(200, length);
                }
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(data, start, length);
                }
            } finally {
                exchange.close();
            }
        });
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 生成含 SKILL.md 的最小可解压 zip；附加几百字节让包足够大（前半段才有意义）。 */
    private static byte[] zipWithSkill(String name) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(("---\nname: " + name + "\ndescription: demo\n---\n\n# demo\n")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("payload-".repeat(120).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
