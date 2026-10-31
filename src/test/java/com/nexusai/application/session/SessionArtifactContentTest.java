package com.nexusai.application.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 产物<b>内容预览</b>单测 · 纯 JUnit，无 Spring 上下文（⛔ @SpringBootTest 会迁移用户真库）。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：产物 {@code local_path} 由模型抽取链路写入，
 * 且归档失败时会回落成「用户原文件的绝对路径」。若内容接口不校验目录，这个端点就变成
 * <b>任意本机文件读取</b>（配合本仓「无鉴权」形态 = 高危）。所以本测试钉死三件事：
 * <ul>
 *   <li><b>越界必拒</b>：{@code ..} 穿越、别的会话目录、归档外路径一律 400，绝不返回内容；</li>
 *   <li><b>归属必查</b>：artifactId 属于别的会话 → 404（不暴露「存在但属于别人」）；</li>
 *   <li><b>读得出、读得对</b>：UTF-8 / GB18030 回落 / 512KB 截断（含截断在半个汉字上）/ 二进制不下发内容。</li>
 * </ul>
 */
class SessionArtifactContentTest {

    private static final String SESSION = "sess-content";
    private static final String OTHER_SESSION = "sess-other";
    private static final Charset GB18030 = Charset.forName("GB18030");

    @TempDir
    Path tempDir;

    @BeforeEach
    void redirectConfigHome() {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
    }

    @AfterEach
    void restoreConfigHome() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    // ───────────────────────── 路径安全 ─────────────────────────

    @Test
    @DisplayName("合法路径：读本会话归档目录内的文件（内容真的读出来了，不是只回路径）")
    void readsFileInsideSessionDir() throws IOException {
        Path file = writeArtifact(SESSION, "0123456789ab-报告.md", "# 季度报告\n\n正文".getBytes(StandardCharsets.UTF_8));

        SessionArtifactService.ArtifactContent c =
                SessionArtifactService.readArtifactFile(SESSION, "art-1", file.toString());

        assertThat(c.binary()).isFalse();
        assertThat(c.truncated()).isFalse();
        assertThat(c.content()).isEqualTo("# 季度报告\n\n正文");
        assertThat(c.mime()).isEqualTo("text/markdown");
        assertThat(c.size()).isEqualTo(Files.size(file));
    }

    @Test
    @DisplayName("路径穿越（..）归一化后落到归档目录外 → 400，绝不返回内容")
    void rejectsTraversalOutsideSessionDir() throws IOException {
        Path secret = Files.writeString(tempDir.resolve("secret.txt"), "TOP-SECRET");
        Path sessionDir = Files.createDirectories(SessionArtifactService.sessionArtifactsDir(SESSION));
        // 归档目录内的 .. 穿越形态
        String sneaky = sessionDir.resolve("..").resolve("..").resolve("secret.txt").toString();

        assertThatThrownBy(() -> SessionArtifactService.readArtifactFile(SESSION, "art-x", sneaky))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("路径非法");
        assertThat(secret).exists();
    }

    @Test
    @DisplayName("别的会话的归档文件 → 400（前缀碰撞形 sess-content-evil 也不能放行）")
    void rejectsOtherSessionDir() throws IOException {
        Path otherFile = writeArtifact(OTHER_SESSION, "abcdef123456-别处.md", "other".getBytes(StandardCharsets.UTF_8));
        // 前缀碰撞：{artifactsRoot}/sess-content-evil 不应被当成 {artifactsRoot}/sess-content
        Path evilDir = Files.createDirectories(
                SessionArtifactService.artifactsRoot().resolve(SESSION + "-evil"));
        Path evilFile = Files.writeString(evilDir.resolve("0123456789ab-x.md"), "evil");

        assertThatThrownBy(() -> SessionArtifactService.readArtifactFile(SESSION, "art-2", otherFile.toString()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SessionArtifactService.readArtifactFile(SESSION, "art-3", evilFile.toString()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("文件不存在 → 404，消息明说「可能已被删除」")
    void missingFileIsNotFound() {
        Path dir = SessionArtifactService.sessionArtifactsDir(SESSION);
        String path = dir.resolve("deadbeefcafe-已删除.md").toString();

        assertThatThrownBy(() -> SessionArtifactService.readArtifactFile(SESSION, "art-4", path))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("产物文件不存在")
                .hasMessageContaining("可能已被删除");
    }

    // ───────────────────────── 归属校验 ─────────────────────────

    @Test
    @DisplayName("artifactId 属于别的会话 → 404（跨会话防护在查库层，不是靠路径）")
    void rejectsArtifactOwnedByAnotherSession() throws IOException {
        Path file = writeArtifact(OTHER_SESSION, "0123456789ab-别处.md", "x".getBytes(StandardCharsets.UTF_8));
        SessionArtifactMapper mapper = mock(SessionArtifactMapper.class);
        when(mapper.selectOneByQuery(any(QueryWrapper.class)))
                .thenReturn(row("art-x", OTHER_SESSION, file.toString()));
        SessionArtifactService service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);

        assertThatThrownBy(() -> service.readContent(SESSION, "art-x"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("产物不存在");
    }

    @Test
    @DisplayName("本会话产物行 → 走内容读取（归属通过后路径校验照常生效）")
    void readsOwnArtifact() throws IOException {
        Path file = writeArtifact(SESSION, "0123456789ab-导出.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        SessionArtifactMapper mapper = mock(SessionArtifactMapper.class);
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(row("art-ok", SESSION, file.toString()));
        SessionArtifactService service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);

        SessionArtifactService.ArtifactContent c = service.readContent(SESSION, "art-ok");

        assertThat(c.content()).isEqualTo("a,b\n1,2\n");
        assertThat(c.mime()).isEqualTo("text/csv");
    }

    @Test
    @DisplayName("来源类条目（local_path 为 null）→ 404 而不是 500")
    void sourceRowWithoutLocalPathIsNotFound() {
        SessionArtifactMapper mapper = mock(SessionArtifactMapper.class);
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(row("art-src", SESSION, null));
        SessionArtifactService service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);

        assertThatThrownBy(() -> service.readContent(SESSION, "art-src"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("产物文件不存在");
    }

    // ───────────────────────── 编码 / 截断 / 二进制 ─────────────────────────

    @Test
    @DisplayName("UTF-8 正常读取（中文不乱码）")
    void decodesUtf8() throws IOException {
        String text = "季度报告：\n第一行\n第二行\n";
        Path file = writeArtifact(SESSION, "0123456789ab-utf8.txt", text.getBytes(StandardCharsets.UTF_8));

        SessionArtifactService.ArtifactContent c =
                SessionArtifactService.readArtifactFile(SESSION, "art-u8", file.toString());

        assertThat(c.content()).isEqualTo(text);
        assertThat(c.mime()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("非 UTF-8 字节 → 回落 GB18030（Windows 老产物常见），不是 U+FFFD 乱码")
    void fallsBackToGb18030() throws IOException {
        String text = "季度报告：\n销售额 1200 万\n";
        Path file = writeArtifact(SESSION, "0123456789ab-gbk.txt", text.getBytes(GB18030));

        SessionArtifactService.ArtifactContent c =
                SessionArtifactService.readArtifactFile(SESSION, "art-gbk", file.toString());

        assertThat(c.content()).isEqualTo(text);
        assertThat(c.content()).doesNotContain("�");
    }

    @Test
    @DisplayName("超过 512KB → truncated=true 且只回 512KB（响应体不被大文件打爆）")
    void truncatesOversizeFile() throws IOException {
        int max = SessionArtifactService.CONTENT_MAX_BYTES;
        byte[] big = new byte[max + 100];
        Arrays.fill(big, (byte) 'a');
        Path file = writeArtifact(SESSION, "0123456789ab-big.log", big);

        SessionArtifactService.ArtifactContent c =
                SessionArtifactService.readArtifactFile(SESSION, "art-big", file.toString());

        assertThat(c.truncated()).isTrue();
        assertThat(c.size()).isEqualTo(max + 100L);
        assertThat(c.content()).hasSize(max);
        assertThat(c.binary()).isFalse();
    }

    @Test
    @DisplayName("截断点落在半个汉字上也不报错（尾部残字节丢弃，不留乱码）")
    void truncationAtMultibyteBoundaryDoesNotThrow() throws IOException {
        int max = SessionArtifactService.CONTENT_MAX_BYTES;
        byte[] prefix = new byte[max - 2];
        Arrays.fill(prefix, (byte) 'a');
        byte[] chinese = "报".getBytes(StandardCharsets.UTF_8);   // 3 字节
        byte[] all = new byte[prefix.length + chinese.length + 1];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(chinese, 0, all, prefix.length, chinese.length);
        all[all.length - 1] = (byte) '\n';
        Path file = writeArtifact(SESSION, "0123456789ab-cut.md", all);

        SessionArtifactService.ArtifactContent c =
                SessionArtifactService.readArtifactFile(SESSION, "art-cut", file.toString());

        assertThat(c.truncated()).isTrue();
        assertThat(c.content()).hasSize(max - 2);       // 半个「报」被丢弃，不会变成乱码
        assertThat(c.content()).doesNotContain("�");
    }

    @Test
    @DisplayName("二进制/未知扩展名 → binary=true、content=null、不返回 base64")
    void binaryIsNotInlined() throws IOException {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        Path file = writeArtifact(SESSION, "0123456789ab-chart.png", png);

        SessionArtifactService.ArtifactContent c =
                SessionArtifactService.readArtifactFile(SESSION, "art-png", file.toString());

        assertThat(c.binary()).isTrue();
        assertThat(c.content()).isNull();
        // mime 按扩展名给真实类型（png → image/png）：/raw 用同一个表出 Content-Type，
        // 浏览器才能把图片按图片渲染（给 octet-stream 会让浏览器当下载文件而不是显示图片）。
        assertThat(c.mime()).isEqualTo("image/png");
        assertThat(c.size()).isEqualTo(png.length);
        assertThat(c.truncated()).isFalse();
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 在 {@code {configHome}/artifacts/{sessionId}/} 下写一个产物副本。 */
    private Path writeArtifact(String sessionId, String fileName, byte[] bytes) throws IOException {
        Path dir = Files.createDirectories(SessionArtifactService.sessionArtifactsDir(sessionId));
        return Files.write(dir.resolve(fileName), bytes);
    }

    private static SessionArtifactRecord row(String id, String sessionId, String localPath) {
        SessionArtifactRecord r = new SessionArtifactRecord();
        r.setId(id);
        r.setSessionId(sessionId);
        r.setKind("deliverable");
        r.setTitle("测试产物");
        r.setLocalPath(localPath);
        return r;
    }
}
