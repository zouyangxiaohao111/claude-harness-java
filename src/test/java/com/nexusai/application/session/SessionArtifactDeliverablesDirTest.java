package com.nexusai.application.session;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交付物目录扫描单测 · 纯 JUnit，无 Spring 上下文（⛔ @SpringBootTest 会迁移用户真库）。
 *
 * <p><b>WHY（2026-11-02 用户裁定「做法 A」+ 规则九）</b>：原产物收集只有「轮末旁路 LLM 读会话猜路径」
 * 一条，而 <b>Bash 脚本生成的文件根本猜不到</b> —— {@code BashTool} 的入参是 {@code command}，
 * 不含任何路径键 ⇒ {@code extractPathFromArguments} 恒 null ⇒ 脚本写出的 docx/pdf 永远进不了产物列表。
 *
 * <p>现改为「Agent 按系统提示词把交付物写进固定目录（{@code deliverables/}）→ 后端扫目录收集」。
 * 本测试钉住这条确定性通道：**只要文件在目录里，就能被入库**，不依赖模型、不依赖工具入参。
 */
class SessionArtifactDeliverablesDirTest {

    private static final String SESSION = "sess-deliverables";

    @TempDir
    Path tempDir;

    private SessionArtifactMapper mapper;
    private SessionArtifactService service;

    @BeforeEach
    void setUp() {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
        mapper = mock(SessionArtifactMapper.class);
        service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);
        // 目录扫描走「新增」分支（不命中既有行）
        when(mapper.selectOneByQuery(any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    /** 在交付物目录里放一个文件（模拟 Agent 按提示词写入）。 */
    private Path writeDeliverable(String name, String content) throws IOException {
        Path dir = SessionArtifactService.deliverablesDir(SESSION);
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("目录里的 docx / pdf / xlsx / html / md / 图片 ⇒ 全部自动入库（脚本产物也能收到）")
    void allDeliverablesInDirAreCollected() throws IOException {
        List<String> names = List.of(
                "农污查询方案.docx", "方案说明.pdf", "查询结果.xlsx",
                "汇报.pptx", "农污查询原型.html", "维度梳理.md", "流程图.png");
        for (String n : names) {
            writeDeliverable(n, "x");
        }

        int saved = service.syncDeliverablesFromDir(SESSION);

        assertThat(saved).as("7 个交付物都应入库").isEqualTo(names.size());
        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper, times(names.size())).insert(cap.capture());
        assertThat(cap.getAllValues().stream().map(SessionArtifactRecord::getTitle))
                .containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("目录里的源码 / 临时脚本 ⇒ 不入库（即便模型真写进去了也拦得住）")
    void sourceCodeInsideDirIsIgnored() throws IOException {
        writeDeliverable("make_docx.py", "print(1)");
        writeDeliverable("build.sh", "echo hi");
        writeDeliverable("App.java", "class App {}");
        writeDeliverable("真正的报告.docx", "x");

        int saved = service.syncDeliverablesFromDir(SESSION);

        assertThat(saved).as("只有那份 docx 该入库").isEqualTo(1);
        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper, times(1)).insert(cap.capture());
        assertThat(cap.getValue().getTitle()).isEqualTo("真正的报告.docx");
    }

    @Test
    @DisplayName("目录不存在 ⇒ 返回 0 且不抛（本轮没交付物是正常情况，不是错误）")
    void missingDirectoryIsNotAnError() {
        assertThat(service.syncDeliverablesFromDir(SESSION)).isZero();
        verify(mapper, never()).insert(any(SessionArtifactRecord.class));
    }

    @Test
    @DisplayName("已在自有产物目录内的文件 ⇒ 跳过归档复制（避免自我复制出 {hash}-xxx.docx）")
    void fileAlreadyInArtifactStoreIsNotArchived() throws IOException {
        Path original = writeDeliverable("报告.docx", "x");

        service.syncDeliverablesFromDir(SESSION);

        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper, atLeastOnce()).insert(cap.capture());
        // localPath 必须仍是**原文件**（不是 sessionArtifactsDir 下的 hash 副本）
        assertThat(cap.getValue().getLocalPath())
                .isEqualTo(original.toAbsolutePath().normalize().toString());
        assertThat(cap.getValue().getLocalPath()).endsWith("deliverables/报告.docx");
    }

    @Test
    @DisplayName("无会话 id ⇒ 返回 0，不碰文件系统")
    void blankSessionIdIsNoop() {
        assertThat(service.syncDeliverablesFromDir("  ")).isZero();
        assertThat(service.syncDeliverablesFromDir(null)).isZero();
    }

    @Test
    @DisplayName("交付物目录就在会话产物目录之下（归档跳过判据依赖这一点）")
    void deliverablesDirLivesUnderArtifactStore() {
        Path dir = SessionArtifactService.deliverablesDir(SESSION);
        assertThat(dir.startsWith(SessionArtifactService.artifactsRoot())).isTrue();
        assertThat(dir.getFileName().toString()).isEqualTo(SessionArtifactService.DELIVERABLES_DIR_NAME);
    }
}
