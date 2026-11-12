package com.nexusai.application.session;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.model.session.dto.SessionArtifactDto;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物按「轮次」归属 · 批次 W12（2026-11-05 用户裁定 B 方案「一轮一行」）。
 *
 * <p><b>WHY 这些断言</b>：归属这件事错了都是<b>静默</b>的 ——
 * <ul>
 *   <li><b>归属丢失</b>（不写 source_message_id）⇒ 前端每一轮都看不见自己的产物，
 *       只能去右侧栏翻；用户会以为是「产物没生成」。</li>
 *   <li><b>跨轮覆盖</b>（唯一键不带轮次）⇒ 第 7 轮改了第 3 轮的文件，
 *       第 3 轮气泡下的产物<b>凭空消失</b>（历史被改写）—— 这是本批要根治的核心缺陷。</li>
 *   <li><b>伪造归属</b>（拿不到轮次就随便填一个）⇒ 比没有归属更难排查。</li>
 * </ul>
 * ⇒ 断言必须覆盖「同一文件跨轮 = 两行」与「拿不到轮次 = NULL 不猜」。
 */
@DisplayName("[W12] 产物按轮次归属")
class SessionArtifactTurnOwnershipTest {

    private static final String SESSION = "sess-turn";

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
        // 走「新增」分支
        when(mapper.selectOneByQuery(any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    private Path writeDeliverable(String name, String content) throws IOException {
        Path dir = SessionArtifactService.deliverablesDir(SESSION);
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // ── 归属写入 ───────────────────────────────────────────

    @Test
    @DisplayName("⭐ 扫目录入库时写入本轮 userMessageId（不是 null）")
    void scanWritesSourceMessageId() throws IOException {
        writeDeliverable("方案.docx", "v1");

        service.syncDeliverablesFromDir(SESSION, "msg-user-3");

        SessionArtifactRecord saved = captureInserted();
        assertThat(saved.getSourceMessageId())
                .as("⛔ 不写归属 ⇒ 前端每一轮都看不见自己的产物")
                .isEqualTo("msg-user-3");
    }

    @Test
    @DisplayName("⭐ 同一文件跨两轮 = 两行（历史轮次的产物不能被「搬走」）")
    void sameFileInTwoTurnsCreatesTwoRows() throws IOException {
        writeDeliverable("报告.docx", "v1");
        service.syncDeliverablesFromDir(SESSION, "msg-user-1");

        // 第二轮：文件被改动，归属另一条消息
        writeDeliverable("报告.docx", "v2-changed");
        service.syncDeliverablesFromDir(SESSION, "msg-user-2");

        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        List<String> owners = new ArrayList<>();
        for (SessionArtifactRecord r : cap.getAllValues()) {
            owners.add(r.getSourceMessageId());
        }
        assertThat(owners)
                .as("⭐ 唯一键必须带轮次；否则第二轮会 UPDATE 掉第一轮的行 ⇒ 历史凭空少一个产物")
                .containsExactlyInAnyOrder("msg-user-1", "msg-user-2");
    }

    @Test
    @DisplayName("⭐ 拿不到轮次 ⇒ 归属为 null（如实标注未知，不伪造）")
    void missingTurnYieldsNullOwner() throws IOException {
        writeDeliverable("无归属.docx", "x");

        service.syncDeliverablesFromDir(SESSION, null);

        assertThat(captureInserted().getSourceMessageId())
                .as("⛔ 编一个 id 出来比没有归属更难排查")
                .isNull();
    }

    @Test
    @DisplayName("工具抓取的来源也按轮次归属")
    void toolSourceAlsoOwned() {
        service.recordSource(SESSION, "某篇文章", "https://example.com/a", null, "msg-user-7");

        SessionArtifactRecord saved = captureInserted();
        assertThat(saved.getKind()).isEqualTo("source");
        assertThat(saved.getSourceMessageId())
                .as("来源与产物同表同口径 —— 只改产物会让右栏混着两种分组")
                .isEqualTo("msg-user-7");
    }

    // ── 读回 ───────────────────────────────────────────────

    @Test
    @DisplayName("按轮取：只返回该轮的产物")
    void listByMessageFiltersByOwner() {
        when(mapper.selectListByQuery(any())).thenReturn(List.of(
                row("art-1", "deliverable", "本轮.docx", "msg-user-5"),
                row("art-2", "deliverable", "本轮2.docx", "msg-user-5")
        ));

        SessionArtifactService.ArtifactBuckets buckets =
                service.listByMessage(SESSION, "msg-user-5");

        assertThat(buckets.deliverables()).extracting(SessionArtifactDto::title)
                .containsExactlyInAnyOrder("本轮.docx", "本轮2.docx");
    }

    @Test
    @DisplayName("按轮取：传 null ⇒ 返回归属未知的历史行（不丢）")
    void listByMessageNullReturnsUnknown() {
        when(mapper.selectListByQuery(any())).thenReturn(List.of(
                row("art-9", "deliverable", "历史.docx", null)
        ));

        SessionArtifactService.ArtifactBuckets buckets = service.listByMessage(SESSION, null);

        assertThat(buckets.deliverables()).extracting(SessionArtifactDto::title)
                .as("V276 之前的历史行没有归属 —— 要能看得到，⛔ 不能静默丢弃")
                .containsExactly("历史.docx");
    }

    @Test
    @DisplayName("DTO 出站带 sourceMessageId（前端按它分组）")
    void dtoExposesOwner() {
        SessionArtifactDto dto = SessionArtifactDto.from(
                row("art-3", "deliverable", "a.docx", "msg-user-2"), false, null, null);

        assertThat(dto.sourceMessageId()).isEqualTo("msg-user-2");
    }

    // ── 辅助 ───────────────────────────────────────────────

    private SessionArtifactRecord captureInserted() {
        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).insert(cap.capture());
        return cap.getValue();
    }

    private static SessionArtifactRecord row(String id, String kind, String title, String owner) {
        SessionArtifactRecord r = new SessionArtifactRecord();
        r.setId(id);
        r.setSessionId(SESSION);
        r.setKind(kind);
        r.setTitle(title);
        r.setSourceMessageId(owner);
        r.setUpdatedAt("2026-11-05T00:00:00");
        return r;
    }
}
