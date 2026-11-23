package com.nexusai.application.market;

import com.nexusai.application.agent.plugin.MarketplaceHttpDownloader;
import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.market.dto.MarketSkillPreviewDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 市场技能预览：plugin.zip → SKILL.md、缓存、大小上限。 */
class MarketSkillPreviewServiceTest {

    private HttpServer server;
    private MarketSkillPreviewService service;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/market/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            byte[] body;
            if (path.endsWith("/skills/foo/1.0.0/plugin.zip")) {
                body = zip(Map.of(
                        "skills/foo/SKILL.md",
                        "---\nname: Foo\ndescription: demo skill\n---\n\n# Body\n",
                        "icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
            } else if (path.endsWith("/skills/big/1.0.0/plugin.zip")) {
                body = zip(Map.of("skills/big/SKILL.md", "x".repeat(512 * 1024 + 10)));
            } else if (path.endsWith("/skills/noskill/1.0.0/plugin.zip")) {
                body = zip(Map.of("README.md", "no skill"));
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/market";
        service = new MarketSkillPreviewService(new MarketplaceHttpDownloader(), base);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("从 plugin.zip 取 skills/foo/SKILL.md + frontmatter + icon；第二次命中缓存")
    void previewAndCache() {
        MarketSkillPreviewDto dto = service.preview("foo", "1.0.0", null, null);

        assertThat(dto.slug()).isEqualTo("foo");
        assertThat(dto.markdown()).contains("# Body");
        assertThat(dto.frontmatter()).containsEntry("name", "Foo");
        assertThat(dto.iconDataUrl()).startsWith("data:image/svg+xml;base64,");
        assertThat(dto.contentLength()).isGreaterThan(0);
        assertThat(dto.cached()).isFalse();
        String path = "/market/skills/foo/1.0.0/plugin.zip";
        assertThat(hits.get(path).get()).isEqualTo(1);

        MarketSkillPreviewDto again = service.preview("foo", "1.0.0", null, null);
        assertThat(again.cached()).isTrue();
        assertThat(hits.get(path).get()).isEqualTo(1);
    }

    @Test
    @DisplayName("SKILL.md 超过 512KB → 400")
    void oversizeRejected() {
        assertThatThrownBy(() -> service.preview("big", "1.0.0", null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("512KB");
    }

    @Test
    @DisplayName("包内无 SKILL.md → 502")
    void missingSkillMd() {
        assertThatThrownBy(() -> service.preview("noskill", "1.0.0", null, null))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    @DisplayName("非法技能名 / 跨源 URL → 400（不发起下载）")
    void rejectsBadInput() {
        assertThatThrownBy(() -> service.preview("../x", "1.0.0", null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.preview("foo", null, null, "http://evil.example/x.zip"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("同源");
    }

    private static byte[] zip(Map<String, String> entries) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> e : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
