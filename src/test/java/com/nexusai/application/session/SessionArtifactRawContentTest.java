package com.nexusai.application.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.ArtifactContentUpdateResponse;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物<b>原始字节下发（/raw）</b>与<b>文本写回（PUT /content）</b>单测 · 纯 JUnit，无 Spring 上下文
 * （⛔ @SpringBootTest 会迁移用户真库）。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：这两个端点是「产物能不能在浏览器里打开 / 能不能改回来」的
 * 全部通路，且都在<b>无鉴权</b>的本机形态下直接落到磁盘文件 ⇒ 判据不是「返回了字节」，而是：
 * <ul>
 *   <li><b>归属</b>：artifactId 属于别的会话 → 404（不暴露「存在但属于别人」）；</li>
 *   <li><b>路径边界</b>：越出本会话归档目录 → 400；符号链接指向目录外 → 400
 *       （文本路径的 {@code startsWith} 拦不住符号链接，必须按 {@code toRealPath()} 再判一次）；</li>
 *   <li><b>类型一致</b>：二进制产物 PUT → 400（写进去就把交付物写坏了）；</li>
 *   <li><b>写回可靠</b>：原子写（不留半截文件）+ 落库 size 与磁盘真实字节数一致；</li>
 *   <li><b>MIME 单一真源</b>：/content、/raw、PUT 三处对扩展名的判定必须一致。</li>
 * </ul>
 */
class SessionArtifactRawContentTest {

    private static final String SESSION = "sess-raw";
    private static final String OTHER_SESSION = "sess-raw-other";

    @TempDir
    Path tempDir;

    private SessionArtifactMapper mapper;
    private SessionArtifactService service;

    @BeforeEach
    void setUp() {
        // 自有根指向 @TempDir ⇒ 绝不碰用户真 ~/.nexusai
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
        mapper = mock(SessionArtifactMapper.class);
        service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    // ───────────────────────── /raw：原始字节 ─────────────────────────

    @Test
    @DisplayName("/raw 下发文本文件的原始字节（字节逐个相同，不是重新编码的字符串）")
    void rawReturnsOriginalBytesForText() throws IOException {
        byte[] bytes = "# 季度报告\n\n正文内容".getBytes(StandardCharsets.UTF_8);
        Path file = writeArtifact(SESSION, "0123456789ab-报告.md", bytes);
        givenRow(row("art-1", SESSION, file.toString(), "季度报告.md"));

        SessionArtifactService.ArtifactRaw raw = service.readRaw(SESSION, "art-1");

        assertThat(raw.bytes()).isEqualTo(bytes);
        assertThat(raw.size()).isEqualTo(bytes.length);
        // text/* 必须带 charset=utf-8，否则中文 Windows 浏览器按 GBK 猜 → 乱码
        assertThat(raw.contentType()).isEqualTo("text/markdown; charset=utf-8");
        assertThat(raw.fileName()).isEqualTo("季度报告.md");
    }

    @Test
    @DisplayName("/raw 与 /content 对同一文本文件给出一致内容（两个出口不打架）")
    void rawAndContentAgreeForText() throws IOException {
        String text = "第一行\n第二行：中文\n";
        Path file = writeArtifact(SESSION, "0123456789ab-笔记.md", text.getBytes(StandardCharsets.UTF_8));
        givenRow(row("art-2", SESSION, file.toString(), "笔记.md"));

        SessionArtifactService.ArtifactRaw raw = service.readRaw(SESSION, "art-2");
        SessionArtifactService.ArtifactContent content = service.readContent(SESSION, "art-2");

        assertThat(new String(raw.bytes(), StandardCharsets.UTF_8)).isEqualTo(text);
        assertThat(content.content()).isEqualTo(text);
        assertThat(content.binary()).isFalse();
    }

    @Test
    @DisplayName("/raw 下发二进制原始字节，MIME 按真实扩展名给（png → image/png，不给 octet-stream）")
    void rawReturnsBinaryBytesWithImageMime() throws IOException {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        Path file = writeArtifact(SESSION, "0123456789ab-图表.png", png);
        givenRow(row("art-3", SESSION, file.toString(), "图表.png"));

        SessionArtifactService.ArtifactRaw raw = service.readRaw(SESSION, "art-3");

        assertThat(raw.bytes()).isEqualTo(png);
        assertThat(raw.contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("PDF / 未知扩展名的 MIME 分别是 application/pdf 与 octet-stream（未知一律当二进制）")
    void rawMimeForPdfAndUnknown() throws IOException {
        Path pdf = writeArtifact(SESSION, "0123456789ab-合同.pdf", new byte[] {1, 2, 3, 4});
        Path odd = writeArtifact(SESSION, "0123456789ab-数据.qqq", new byte[] {5, 6, 7});
        givenRow(row("art-pdf", SESSION, pdf.toString(), "合同.pdf"));
        assertThat(service.readRaw(SESSION, "art-pdf").contentType()).isEqualTo("application/pdf");

        givenRow(row("art-odd", SESSION, odd.toString(), "数据.qqq"));
        assertThat(service.readRaw(SESSION, "art-odd").contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("SVG：文本内容可预览，但 MIME 仍是 image/svg+xml（浏览器要按图片渲染）")
    void svgIsTextButServedAsImage() throws IOException {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>";
        Path file = writeArtifact(SESSION, "0123456789ab-图.svg", svg.getBytes(StandardCharsets.UTF_8));
        givenRow(row("art-svg", SESSION, file.toString(), "图.svg"));

        SessionArtifactService.ArtifactRaw raw = service.readRaw(SESSION, "art-svg");

        assertThat(raw.contentType()).isEqualTo("image/svg+xml");
        assertThat(service.readContent(SESSION, "art-svg").content()).isEqualTo(svg);
    }

    @Test
    @DisplayName("title 为空 → 文件名回落副本 basename（前端下载到的名字不能是空串）")
    void rawFileNameFallsBackToCopyBasename() throws IOException {
        Path file = writeArtifact(SESSION, "0123456789ab-归档件.txt", "x".getBytes(StandardCharsets.UTF_8));
        givenRow(row("art-4", SESSION, file.toString(), "   "));

        assertThat(service.readRaw(SESSION, "art-4").fileName()).isEqualTo("0123456789ab-归档件.txt");
    }

    @Test
    @DisplayName("/raw：产物属于别的会话 → 404（跨会话读防护在查库层）")
    void rawRejectsCrossSession() throws IOException {
        Path file = writeArtifact(OTHER_SESSION, "0123456789ab-别处.md", "x".getBytes(StandardCharsets.UTF_8));
        givenRow(row("art-x", OTHER_SESSION, file.toString(), "别处.md"));

        assertThatThrownBy(() -> service.readRaw(SESSION, "art-x"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("产物不存在");
    }

    // ───────────────────────── PUT /content：文本写回 ─────────────────────────

    @Test
    @DisplayName("PUT 写回文本：磁盘内容真的换了，sizeBytes 是 UTF-8 字节数（不是字符数），且落库一致")
    void putWritesContentAndUpdatesSize() throws IOException {
        Path file = writeArtifact(SESSION, "0123456789ab-报告.md", "旧内容".getBytes(StandardCharsets.UTF_8));
        SessionArtifactRecord row = row("art-5", SESSION, file.toString(), "报告.md");
        givenRow(row);

        String newContent = "新内容：中文三字节";
        ArtifactContentUpdateResponse resp = service.writeTextContent(SESSION, "art-5", newContent);

        byte[] expected = newContent.getBytes(StandardCharsets.UTF_8);
        assertThat(Files.readAllBytes(file)).isEqualTo(expected);
        // ⛔ 字节数 ≠ 字符数：中文一个字 3 字节，回填字符数会让前端「文件大小」标签与磁盘对不上
        assertThat(resp.sizeBytes()).isEqualTo(expected.length).isNotEqualTo(newContent.length());
        assertThat(resp.id()).isEqualTo("art-5");
        assertThat(resp.updatedAt()).isNotBlank();

        // 落库的那一行也必须带新 size（前端列表靠它刷新大小标签）
        ArgumentCaptor<SessionArtifactRecord> captor = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper).update(captor.capture(), eq(true));
        assertThat(captor.getValue().getSizeBytes()).isEqualTo(expected.length);
    }

    @Test
    @DisplayName("PUT 原子写：成功后不留临时文件（半截文件留在归档目录里会被当成产物）")
    void putLeavesNoTempFileBehind() throws IOException {
        Path file = writeArtifact(SESSION, "0123456789ab-报告.md", "旧".getBytes(StandardCharsets.UTF_8));
        givenRow(row("art-6", SESSION, file.toString(), "报告.md"));

        service.writeTextContent(SESSION, "art-6", "新内容");

        try (Stream<Path> files = Files.list(SessionArtifactService.sessionArtifactsDir(SESSION))) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names).containsExactly("0123456789ab-报告.md");
        }
    }

    @Test
    @DisplayName("PUT 空串 = 清空文件（用户主动清空 ≠ 漏传参数）")
    void putEmptyContentClearsFile() throws IOException {
        Path file = writeArtifact(SESSION, "0123456789ab-报告.md", "旧内容".getBytes(StandardCharsets.UTF_8));
        givenRow(row("art-7", SESSION, file.toString(), "报告.md"));

        ArtifactContentUpdateResponse resp = service.writeTextContent(SESSION, "art-7", "");

        assertThat(Files.size(file)).isZero();
        assertThat(resp.sizeBytes()).isZero();
    }

    @Test
    @DisplayName("PUT content 缺失（null）→ 400（不把 null 写进文件）")
    void putRejectsNullContent() throws IOException {
        Path file = writeArtifact(SESSION, "0123456789ab-报告.md", "旧".getBytes(StandardCharsets.UTF_8));
        givenRow(row("art-8", SESSION, file.toString(), "报告.md"));

        assertThatThrownBy(() -> service.writeTextContent(SESSION, "art-8", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("不能为空");
        assertThat(Files.readString(file)).isEqualTo("旧");
    }

    @Test
    @DisplayName("PUT 二进制产物 → 400（写进去会把 docx/pdf/图片当场写坏）")
    void putRejectsBinaryArtifact() throws IOException {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        Path file = writeArtifact(SESSION, "0123456789ab-图表.png", png);
        givenRow(row("art-9", SESSION, file.toString(), "图表.png"));

        assertThatThrownBy(() -> service.writeTextContent(SESSION, "art-9", "随便写点文字"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("不支持文本编辑");
        assertThat(Files.readAllBytes(file)).isEqualTo(png);
    }

    @Test
    @DisplayName("PUT 跨会话产物 → 404（连路径都不校验，先判归属）")
    void putRejectsCrossSession() throws IOException {
        Path file = writeArtifact(OTHER_SESSION, "0123456789ab-别处.md", "x".getBytes(StandardCharsets.UTF_8));
        givenRow(row("art-y", OTHER_SESSION, file.toString(), "别处.md"));

        assertThatThrownBy(() -> service.writeTextContent(SESSION, "art-y", "新内容"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("产物不存在");
        assertThat(Files.readString(file)).isEqualTo("x");
    }

    @Test
    @DisplayName("PUT 路径越出本会话归档目录 → 400，且不碰目录外的文件")
    void putRejectsPathOutsideSessionDir() throws IOException {
        Path outside = Files.writeString(tempDir.resolve("outside.md"), "OUTSIDE");
        givenRow(row("art-10", SESSION, outside.toString(), "外部.md"));

        assertThatThrownBy(() -> service.writeTextContent(SESSION, "art-10", "被篡改"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("路径非法");
        assertThat(Files.readString(outside)).isEqualTo("OUTSIDE");
    }

    @Test
    @DisplayName("符号链接指向归档目录外 → 400（文本路径 startsWith 拦不住，必须按真实路径再判一次）")
    void rejectsSymlinkEscape() throws IOException {
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside-dir"));
        Files.writeString(outsideDir.resolve("secret.txt"), "TOP-SECRET");
        Path sessionDir = Files.createDirectories(SessionArtifactService.sessionArtifactsDir(SESSION));

        Path link = sessionDir.resolve("link-out");
        if (!createLink(link, outsideDir)) {
            Assumptions.assumeTrue(false,
                    "当前环境既不能建符号链接也不能建目录联接（Windows 未开启开发者模式），本用例跳过");
            return;
        }
        Path throughLink = link.resolve("secret.txt");
        givenRow(row("art-11", SESSION, throughLink.toString(), "链接产物.txt"));

        assertThatThrownBy(() -> service.readRaw(SESSION, "art-11"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("路径非法");
        assertThatThrownBy(() -> service.writeTextContent(SESSION, "art-11", "被篡改"))
                .isInstanceOf(ValidationException.class);
        assertThat(Files.readString(outsideDir.resolve("secret.txt"))).isEqualTo("TOP-SECRET");
    }

    // ───────────────────────── kind → mime 单一真源 ─────────────────────────

    @Test
    @DisplayName("kind→mime 覆盖约定扩展名清单（三个出口共用这一张表，不各写一份）")
    void kindMimeTableCoversContract() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("html", "text/html");
        expected.put("htm", "text/html");
        expected.put("md", "text/markdown");
        expected.put("markdown", "text/markdown");
        expected.put("txt", "text/plain");
        expected.put("log", "text/plain");
        expected.put("json", "application/json");
        expected.put("csv", "text/csv");
        expected.put("yaml", "text/yaml");
        expected.put("yml", "text/yaml");
        expected.put("xml", "application/xml");
        expected.put("js", "text/javascript");
        expected.put("jsx", "text/javascript");
        expected.put("ts", "text/javascript");
        expected.put("tsx", "text/javascript");
        expected.put("css", "text/css");
        expected.put("py", "text/x-python");
        expected.put("java", "text/x-java-source");
        expected.put("sh", "text/x-shellscript");
        expected.put("bat", "text/x-shellscript");
        expected.put("ps1", "text/x-shellscript");
        expected.put("sql", "text/x-sql");
        expected.put("doc", "application/msword");
        expected.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        expected.put("xls", "application/vnd.ms-excel");
        expected.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        expected.put("ppt", "application/vnd.ms-powerpoint");
        expected.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        expected.put("pdf", "application/pdf");
        expected.put("png", "image/png");
        expected.put("jpg", "image/jpeg");
        expected.put("jpeg", "image/jpeg");
        expected.put("gif", "image/gif");
        expected.put("webp", "image/webp");
        expected.put("zip", "application/zip");

        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertThat(ArtifactFileKind.mimeOf(e.getKey()))
                    .as("扩展名 %s 的 MIME", e.getKey())
                    .isEqualTo(e.getValue());
        }

        // 二进制类：docx/xlsx/pptx/pdf/图片 一律不可编辑
        for (String binary : List.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf",
                "png", "jpg", "jpeg", "gif", "webp", "zip")) {
            assertThat(ArtifactFileKind.isText(binary)).as("%s 应为二进制", binary).isFalse();
            assertThat(ArtifactFileKind.of(binary)).isEqualTo(ArtifactFileKind.BINARY);
        }
        // 文本类：可预览可编辑
        for (String text : List.of("md", "txt", "json", "csv", "html", "js", "ts", "css", "py", "sql")) {
            assertThat(ArtifactFileKind.isText(text)).as("%s 应为文本", text).isTrue();
            assertThat(ArtifactFileKind.of(text)).isEqualTo(ArtifactFileKind.TEXT);
        }
        // 未知扩展名 → 一律二进制（保守：宁可不预览，也不把未知二进制当文本写坏）
        assertThat(ArtifactFileKind.of("qqq")).isEqualTo(ArtifactFileKind.BINARY);
        assertThat(ArtifactFileKind.mimeOf("qqq")).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("/raw 的 Content-Type：text/* 带 charset，二进制不带（加了也无意义）")
    void contentTypeAddsCharsetOnlyForText() {
        assertThat(ArtifactFileKind.contentTypeOf("md")).isEqualTo("text/markdown; charset=utf-8");
        assertThat(ArtifactFileKind.contentTypeOf("txt")).isEqualTo("text/plain; charset=utf-8");
        assertThat(ArtifactFileKind.contentTypeOf("png")).isEqualTo("image/png");
        assertThat(ArtifactFileKind.contentTypeOf("pdf")).isEqualTo("application/pdf");
        // SVG 是文本但按图片渲染 ⇒ 不加 charset
        assertThat(ArtifactFileKind.contentTypeOf("svg")).isEqualTo("image/svg+xml");
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 让 mapper 对任意查询返回给定产物行。 */
    private void givenRow(SessionArtifactRecord row) {
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(row);
    }

    /** 在 {@code {configHome}/artifacts/{sessionId}/} 下写一个产物副本。 */
    private Path writeArtifact(String sessionId, String fileName, byte[] bytes) throws IOException {
        Path dir = Files.createDirectories(SessionArtifactService.sessionArtifactsDir(sessionId));
        return Files.write(dir.resolve(fileName), bytes);
    }

    private static SessionArtifactRecord row(String id, String sessionId, String localPath, String title) {
        SessionArtifactRecord r = new SessionArtifactRecord();
        r.setId(id);
        r.setSessionId(sessionId);
        r.setKind("deliverable");
        r.setTitle(title);
        r.setLocalPath(localPath);
        return r;
    }

    /**
     * 建一个指向 {@code target} 的链接（优先真符号链接；Windows 无权限时退化为目录联接）。
     *
     * <p>WHY 退化：Windows 未开开发者模式时 {@code Files.createSymbolicLink} 抛
     * {@code WinError 1314}；目录联接（junction）同样是重解析点，{@code Path.toRealPath()}
     * 会把它解析成真实目标 ⇒ <b>走的是同一段防护代码</b>（{@code toRealPath} 越界判定）。
     *
     * @return true = 链接已建成；false = 本机两种方式都不行
     */
    private static boolean createLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // 落到目录联接
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            boolean ok = p.waitFor() == 0;
            if (!ok) {
                try (var in = p.getInputStream()) {
                    in.readAllBytes();
                }
            }
            return ok && Files.exists(link);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
