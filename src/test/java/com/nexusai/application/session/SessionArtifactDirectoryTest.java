package com.nexusai.application.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.SessionArtifactDto;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 产物<b>目录形态</b>单测 · 纯 JUnit，无 Spring 上下文（⛔ @SpringBootTest 会迁移用户真库）。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：产物 {@code local_path} 由模型抽取链路写入，而目录
 * <b>不会被归档</b>（归档只复制普通文件）⇒ 目录的路径必然落在会话归档目录<b>之外</b>。
 * 若三个产物端点不先判「是不是目录」，用户点开一个目录产物只会看到
 * <b>误导性的「产物路径非法」</b>（路径其实没坏，它是目录）⇒ 必须给出诚实错误。
 * 同时前端要靠 {@code directory} 标记画文件夹图标、禁掉预览/编辑。
 */
class SessionArtifactDirectoryTest {

    private static final String SESSION = "sess-dir";

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

    // ───────────────────────── 端点：目录 ⇒ 明确错误 ─────────────────────────

    @Test
    @DisplayName("目录产物读内容 ⇒ 400 且消息明说「目录」（不是误导性的「路径非法」）")
    void directoryContentIsRejectedWithHonestMessage() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("农污软件设计包"));

        assertThatThrownBy(() -> SessionArtifactService.readArtifactFile(SESSION, "art-dir", dir.toString()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("目录")
                .hasMessageNotContaining("路径非法");
    }

    @Test
    @DisplayName("目录产物读原始字节 ⇒ 400 且消息明说「目录」（不是「路径非法」）")
    void directoryRawIsRejectedWithHonestMessage() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("农污软件设计包"));
        givenRow(row("art-dir", dir.toString()));

        assertThatThrownBy(() -> service.readRaw(SESSION, "art-dir"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("目录")
                .hasMessageNotContaining("路径非法");
    }

    @Test
    @DisplayName("目录产物写回文本 ⇒ 400 且消息明说「目录，无法编辑」")
    void directoryEditIsRejectedWithHonestMessage() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("农污软件设计包"));
        givenRow(row("art-dir", dir.toString()));

        assertThatThrownBy(() -> service.writeTextContent(SESSION, "art-dir", "随便写点"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("目录")
                .hasMessageContaining("无法编辑");
    }

    // ───────────────────────── 目录判定 helper ─────────────────────────

    @Test
    @DisplayName("目录判定：目录=true、普通文件=false、空路径=false（⛔ 解析异常不当成 true）")
    void directoryDetection() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("设计包"));
        Path file = Files.writeString(tempDir.resolve("报告.md"), "x", StandardCharsets.UTF_8);

        assertThat(SessionArtifactService.isDirectoryArtifact(dir.toString())).isTrue();
        assertThat(SessionArtifactService.isDirectoryArtifact(file.toString())).isFalse();
        assertThat(SessionArtifactService.isDirectoryArtifact(null)).isFalse();
        assertThat(SessionArtifactService.isDirectoryArtifact("")).isFalse();
        assertThat(SessionArtifactService.isDirectoryArtifact("   ")).isFalse();
        // 路径非法（带 NUL 的路径无法解析）⇒ 吞异常返回 false，不当成目录
        assertThat(SessionArtifactService.isDirectoryArtifact("bad\0path")).isFalse();
    }

    // ───────────────────────── 出站 DTO ─────────────────────────

    @Test
    @DisplayName("DTO 出站带 directory 标记（前端据此画文件夹图标、禁预览/编辑）")
    void dtoCarriesDirectoryFlag() {
        SessionArtifactRecord r = row("art-dir", "D:/some/dir");

        assertThat(SessionArtifactDto.from(r, true).directory()).isTrue();
        assertThat(SessionArtifactDto.from(r, false).directory()).isFalse();
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 让 mapper 对任意查询返回给定产物行。 */
    private void givenRow(SessionArtifactRecord row) {
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(row);
    }

    private static SessionArtifactRecord row(String id, String localPath) {
        SessionArtifactRecord r = new SessionArtifactRecord();
        r.setId(id);
        r.setSessionId(SESSION);
        r.setKind("deliverable");
        r.setTitle("农污软件设计包");
        r.setLocalPath(localPath);
        return r;
    }
}
