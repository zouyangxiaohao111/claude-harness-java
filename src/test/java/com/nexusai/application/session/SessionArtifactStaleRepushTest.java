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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧产物「不重推 + 不顶时间戳」（2026-11-04 用户实测「如果之前轮次有个产物，现在新的轮次就会默认打开之前的产物」）。
 *
 * <h2>WHY（规则九：测意图）</h2>
 * <p>「新轮次打开上一轮的产物」是<b>三个事实叠加</b>的结果，缺一不可：
 * <ol>
 *   <li><b>后端把旧产物当「刚生成」重新入库</b>：轮末目录扫描 / 轮内监看每轮都会把目录里
 *       <b>所有</b>文件重走一遍 {@code upsert}，而 {@code upsert} <b>无条件</b>刷
 *       {@code updated_at} ⇒ 上一轮的旧产物被顶到「最新」；</li>
 *   <li><b>轮内监看第一拍把已存在的旧文件当成「变过了」</b>：签名表起始为空 ⇒
 *       {@code signatures.put} 首次返回 null ≠ prev ⇒ 全部判为变更 ⇒ 逐个重推；</li>
 *   <li><b>前端按 updated_at 倒序取 list[0]</b> ⇒ 弹出来的是被顶到第一位的旧产物。</li>
 * </ol>
 *
 * <p>本测试钉住后端侧的两个根因（前端侧见 {@code App.tsx} 的 handleArtifactPush）：
 * <ul>
 *   <li><b>内容没变 ⇒ 不刷 updated_at、不推送</b>；</li>
 *   <li><b>轮内监看开始时先播种签名表</b> ⇒ 目录里已存在的旧文件不会被重推。</li>
 * </ul>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>去掉 {@code upsert} 里的「未变则跳过」判断 ⇒ 用例 ① 红（updated_at 被顶）；</li>
 *   <li>去掉 {@code seedTurnWatchSignatures} 调用 ⇒ 用例 ② 红（旧文件被重推）。</li>
 * </ul>
 */
@DisplayName("[2026-11-04] 旧产物不重推 + 不顶时间戳")
class SessionArtifactStaleRepushTest {

    private static final String SESSION = "sess-stale-repush";
    private static final String TOPIC = "/topic/sessions/" + SESSION + "/artifact";

    @TempDir
    Path tempDir;

    private SessionArtifactMapper mapper;
    private SimpMessagingTemplate wsTemplate;
    private SessionArtifactService service;

    @BeforeEach
    void setUp() {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
        mapper = mock(SessionArtifactMapper.class);
        wsTemplate = mock(SimpMessagingTemplate.class);
        service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);
        ReflectionTestUtils.setField(service, "wsTemplate", wsTemplate);
        when(mapper.selectOneByQuery(any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    /** 在交付物目录里写一个文件（必须真在目录下 —— syncDeliverableForPath 会先判目录归属）。 */
    private Path writeDeliverable(String name, String content) throws IOException {
        Path dir = SessionArtifactService.deliverablesDir(SESSION);
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    /** 已入库的旧行（模拟 DB 里已有一行同名产物）。 */
    private SessionArtifactRecord existingRow(String title, String localPath, Long sizeBytes) {
        SessionArtifactRecord existing = new SessionArtifactRecord();
        existing.setId("art-old");
        existing.setSessionId(SESSION);
        existing.setKind("deliverable");
        existing.setTitle(title);
        existing.setLocalPath(localPath);
        existing.setUrl(null);
        existing.setSizeBytes(sizeBytes);
        existing.setSummary(null);
        existing.setUpdatedAt("2020-01-01T00:00:00+08:00");
        return existing;
    }

    @Test
    @DisplayName("内容没变 ⇒ 不刷 updated_at、不推送（否则旧产物被顶到列表第一位）")
    void unchangedArtifactDoesNotBumpTimestampNorPush() throws IOException {
        // 文件内容固定 12 字节（"old-content"）
        Path file = writeDeliverable("旧产物.txt", "old-content");
        long size = Files.size(file);
        // 已有行：路径 / 大小 / 摘要与本次完全一致。
        // ⚠️ updated_at 必须**晚于文件的 mtime** —— 它表示「我们上次确认这个文件时的时间」。
        //    真实流程里 upsert 写的就是「刚才」⇒ 必然晚于 mtime ⇒ 判为「没变」。
        //    （若把 updated_at 设成 2020，语义就变成「2020 年确认过，之后没人动过」——
        //      而文件其实是刚写的 ⇒ 那就真的是「变了」，判「变了」才是对的。）
        SessionArtifactRecord existing = existingRow("旧产物.txt", file.toString(), size);
        existing.setUpdatedAt(OffsetDateTime.now().plusSeconds(5).toString());
        when(mapper.selectOneByQuery(any())).thenReturn(existing);

        service.syncDeliverableForPath(SESSION, file.toString(), null);

        // 关键：未变 ⇒ 不 update（不刷 updated_at）
        verify(mapper, never()).update(any(SessionArtifactRecord.class), any(Boolean.class));
        // 关键：未变 ⇒ 不推送（前端不弹面板）
        verify(wsTemplate, never()).convertAndSend(eq(TOPIC), any(Object.class));
    }

    @Test
    @DisplayName("内容变了（大小不同）⇒ 刷 updated_at 并推送（真更新必须可见）")
    void changedArtifactBumpsTimestampAndPushes() throws IOException {
        Path file = writeDeliverable("旧产物.txt", "old-content");
        // 已有行记录的大小与实际不同 ⇒ 本次 upsert 是「变了」
        when(mapper.selectOneByQuery(any())).thenReturn(existingRow("旧产物.txt", file.toString(), 999L));

        service.syncDeliverableForPath(SESSION, file.toString(), null);

        verify(mapper, atLeastOnce()).update(any(SessionArtifactRecord.class), any(Boolean.class));
        verify(wsTemplate, atLeastOnce()).convertAndSend(eq(TOPIC), any(Object.class));
    }

    @Test
    @DisplayName("⭐ 同尺寸重写（cat → dog）⇒ 仍判为「变了」并推送（只比大小会把编辑中的实时推送压掉）")
    void sameSizeRewriteIsStillDetectedAsChanged() throws Exception {
        // WHY：轮内监看按 size:mtime 去重 —— 若 upsert 只比大小，同尺寸重写会被误判成
        //   「没变」⇒ 不刷新时间戳、不推送 ⇒ 编辑过程中侧栏不再实时更新（真回归）。
        Path file = writeDeliverable("产物.txt", "cat");     // 3 字节
        // 先入库一次（记录 updated_at）
        when(mapper.selectOneByQuery(any())).thenReturn(null);
        service.syncDeliverableForPath(SESSION, file.toString(), null);
        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper).insert(cap.capture());
        String firstUpdatedAt = cap.getValue().getUpdatedAt();
        assertThat(firstUpdatedAt).isNotBlank();

        // 同尺寸改写内容；并把 mtime 推到「上次确认时间之后」
        Files.writeString(file, "dog", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                OffsetDateTime.parse(firstUpdatedAt).toInstant().toEpochMilli() + 5000L));

        // DB 里已有行：路径/大小相同，但 updated_at = 第一次记录的时间（文件在其后被改过）
        SessionArtifactRecord existing = existingRow("产物.txt", file.toString(), 3L);
        existing.setUpdatedAt(firstUpdatedAt);
        when(mapper.selectOneByQuery(any())).thenReturn(existing);

        int pushesBefore = pushCount();
        service.syncDeliverableForPath(SESSION, file.toString(), null);

        verify(mapper, atLeastOnce()).update(any(SessionArtifactRecord.class), any(Boolean.class));
        assertThat(pushCount()).as("同尺寸重写也必须推送（否则编辑中侧栏不更新）").isGreaterThan(pushesBefore);
    }

    @Test
    @DisplayName("轮内监看开始时播种 ⇒ 目录里已存在的旧文件不会被重推（但本轮新写的会被）")
    void turnWatchSeedsExistingFilesSoTheyAreNotRepushed() throws Exception {
        Path dir = SessionArtifactService.deliverablesDir(SESSION);
        Files.createDirectories(dir);
        // 上一轮就存在的产物
        Path old = dir.resolve("上一轮的产物.docx");
        Files.writeString(old, "old-content", StandardCharsets.UTF_8);

        service.startTurnWatch(SESSION, null);
        try {
            // 等 3 拍（每拍 2s）⇒ 若没有播种，旧文件会被当成「变过了」重推
            Thread.sleep(3200);
            assertThat(pushCount())
                .as("已存在的旧文件不得被重新推送（否则新轮次弹出上一轮的产物）")
                .isZero();

            // 本轮新写的文件 ⇒ 必须被感知并推送
            Path fresh = dir.resolve("本轮的新产物.docx");
            Files.writeString(fresh, "fresh", StandardCharsets.UTF_8);
            int after = awaitPushCountAbove(0);
            assertThat(after).as("本轮新写的文件必须被推送").isGreaterThan(0);
        } finally {
            service.stopTurnWatch(SESSION);
        }
    }

    @Test
    @DisplayName("目录不存在时播种不报错（fail-soft）")
    void seedingWithMissingDirIsSafe() {
        service.startTurnWatch(SESSION, null);
        try {
            // 不抛异常即通过
            assertThat(pushCount()).isZero();
        } finally {
            service.stopTurnWatch(SESSION);
        }
    }

    /** 等推送次数**超过** baseline（最多 ~8s）。 */
    private int awaitPushCountAbove(int baseline) throws InterruptedException {
        for (int i = 0; i < 40 && pushCount() <= baseline; i++) {
            Thread.sleep(200);
        }
        return pushCount();
    }

    /** 推送次数 · ⛔ 不吞异常（吞了会把「查不到」伪装成「没推送」）。 */
    private int pushCount() {
        return (int) org.mockito.Mockito.mockingDetails(wsTemplate).getInvocations().stream()
            .filter(i -> "convertAndSend".equals(i.getMethod().getName()))
            .filter(i -> TOPIC.equals(String.valueOf(i.getArguments()[0])))
            .count();
    }
}
