package com.nexusai.application.market;

import com.nexusai.application.agent.plugin.MarketplaceHttpDownloader;
import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.NotFoundException;
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

    /**
     * 市场清单 · zjky-doc-writer 的真实目录名是 {@code doc-writer}（0.1.0 为历史版本，entry.source
     * 已指向 0.2.0）—— 预览必须按清单 source 取包，不能用 name 拼路径。
     */
    private static final String MANIFEST = """
        {"plugins":[
          {"name":"foo","version":"1.0.0","source":"skills/foo/1.0.0"},
          {"name":"big","version":"1.0.0","source":"skills/big/1.0.0"},
          {"name":"noskill","version":"1.0.0","source":"skills/noskill/1.0.0"},
          {"name":"ghost","version":"1.0.0","source":"skills/ghost/1.0.0",
           "versions":[{"version":"1.0.0","source":"skills/ghost/1.0.0"}]},
          {"name":"zjky-doc-writer","version":"0.2.0","source":"skills/doc-writer/0.2.0",
           "versions":[{"version":"0.1.0","source":"skills/doc-writer/0.1.0"},
                       {"version":"0.2.0","source":"skills/doc-writer/0.2.0"}]}
        ]}
        """;

    private HttpServer server;
    private MarketSkillPreviewService service;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/market/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            if (path.endsWith("/marketplace.json")) {
                byte[] m = MANIFEST.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, m.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(m);
                }
                ex.close();
                return;
            }
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
            } else if (path.endsWith("/skills/doc-writer/0.1.0/plugin.zip")) {
                // 清单 source 用裸目录名 doc-writer；entry.name 是 zjky-doc-writer
                body = zip(Map.of("skills/doc-writer/SKILL.md",
                        "---\nname: Doc Writer\n---\n\n# Doc\n"));
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
    @DisplayName("name 与目录名不同：按清单 source 取包，不用 name 拼路径（zjky-doc-writer → doc-writer）")
    void resolvesSourceFromManifestInsteadOfName() {
        MarketSkillPreviewDto dto = service.preview("zjky-doc-writer", "0.1.0", null, null);

        assertThat(dto.slug()).isEqualTo("doc-writer");
        assertThat(dto.markdown()).contains("# Doc");
        assertThat(hits).containsKey("/market/skills/doc-writer/0.1.0/plugin.zip");
        assertThat(hits).doesNotContainKey("/market/skills/zjky-doc-writer/0.1.0/plugin.zip");
    }

    @Test
    @DisplayName("清单里没有该版本 → 404（不静默回落当前版本）")
    void unknownVersionRejected() {
        assertThatThrownBy(() -> service.preview("ghost", "9.9.9", null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("9.9.9");
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
