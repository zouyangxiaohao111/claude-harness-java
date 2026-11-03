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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写盘即入库 + 实时推送（2026-11-02 用户实测「编辑过程中侧栏没有自动展开」）。
 *
 * <h2>WHY（规则九：意图）</h2>
 * <p>用户要的是「产物在**编辑过程中**就实时推送并自动展开侧栏」。而此前两条事实**在结构上互斥**：
 * <ol>
 *   <li>产物推送只发生在 {@code ChatService} 的**轮末**（{@code status=idle} 之后调
 *       {@code scheduleExtract}）—— 日志实测每次 {@code [artifact-scan]} 都紧跟 {@code status=idle}；</li>
 *   <li>前端自动展开的门控是「本轮仍在跑」（{@code turnRunningRef.current}）—— 轮末推送到达时
 *       该门控刚变成 false ⇒ 面板被按回去。</li>
 * </ol>
 * <p>⇒ 门控分支<b>永不成立</b>，用户看到的「之前不是修了吗」其实依赖的是一条从不触发的事件。
 *
 * <h2>本测试钉住</h2>
 * <ul>
 *   <li>写进 {@code artifacts/<sid>/deliverables/} 的文件，**调用当时**就入库并推 STOMP
 *       （不等轮末）；</li>
 *   <li>不在产物目录里的普通文件**不**入库（避免每次 Edit 源码都触发一次产物推送）；</li>
 *   <li>源码文件即便写进目录也**不**入库（与目录扫描同一黑名单）。</li>
 * </ul>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>删掉 {@code syncDeliverableForPath} 里的 {@code file.startsWith(dir)} 判断 ⇒ 用例 ② 红；</li>
 *   <li>不调用 {@code saveDeliverable}（直接 return true）⇒ 用例 ①③ 红；</li>
 *   <li>不调 {@code pushArtifactUpsert} ⇒ 用例 ① 的 STOMP 断言红。</li>
 * </ul>
 */
@DisplayName("[2026-11-02] 产物写盘即入库 + 实时推送")
class SessionArtifactWriteTimeSyncTest {

    private static final String SESSION = "sess-write-time";
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

    private Path writeFile(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("产物目录里的文件：写盘即入库 + 立即推 artifact 事件（不等轮末）")
    void deliverableInDirIsSavedAndPushedImmediately() throws IOException {
        Path file = writeFile(SessionArtifactService.deliverablesDir(SESSION), "方案.docx", "x");

        assertThat(service.syncDeliverableForPath(SESSION, file.toString()))
            .as("产物目录里的文件应当场入库").isTrue();

        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getTitle()).isEqualTo("方案.docx");

        // 关键：推送发生在**调用当时**（前端据此自动展开面板）
        verify(wsTemplate, atLeastOnce()).convertAndSend(eq(TOPIC), any(Object.class));
    }

    @Test
    @DisplayName("普通文件（不在产物目录）⇒ 不入库、不推送（否则每次 Edit 源码都会弹产物面板）")
    void ordinaryFileIsIgnored() throws IOException {
        Path file = writeFile(tempDir.resolve("workspace"), "readme.txt", "x");

        assertThat(service.syncDeliverableForPath(SESSION, file.toString())).isFalse();
        verify(mapper, never()).insert(any(SessionArtifactRecord.class));
        verify(wsTemplate, never()).convertAndSend(eq(TOPIC), any(Object.class));
    }

    @Test
    @DisplayName("源码写进产物目录 ⇒ 仍不入库（与目录扫描同一黑名单）")
    void sourceCodeInsideDirIsRejected() throws IOException {
        Path file = writeFile(SessionArtifactService.deliverablesDir(SESSION), "make_docx.py", "print(1)");

        assertThat(service.syncDeliverableForPath(SESSION, file.toString())).isFalse();
        verify(mapper, never()).insert(any(SessionArtifactRecord.class));
    }

    @Test
    @DisplayName("轮内监看：复制进来的新文件被感知并推送；未变更的文件不重复推送")
    void turnWatchDetectsCopiedFileWithoutRepush() throws Exception {
        // WHY（2026-11-03 用户：「copy 了是不是要写监听器，不然怎么动态感知产物」）：
        //   cp / 脚本复制不进 SessionFilesRecorder ⇒ 靠轮内每 2s 扫目录发现。
        //   ⚠️ upsert 无条件推送 ⇒ 必须有 size:mtime 签名去重，否则每 2s 重推、面板被反复切回最新。
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
        Path dir = SessionArtifactService.deliverablesDir(SESSION);
        Files.createDirectories(dir);

        service.startTurnWatch(SESSION);
        try {
            // ① 复制进来一个新文件 → 下一拍必须入库 + 推送
            Path copied = dir.resolve("复制来的报告.docx");
            Files.writeString(copied, "x", StandardCharsets.UTF_8);
            int pushesAfterFirst = awaitPushCountAbove(0);
            assertThat(pushesAfterFirst).as("复制进来的文件必须被推送").isGreaterThan(0);
            verify(mapper, atLeastOnce()).insert(any(SessionArtifactRecord.class));

            // ② 文件没变 → 后续几拍不得再推（否则面板每 2s 被切回最新）
            Thread.sleep(2600);
            assertThat(pushCount())
                .as("未变更的文件不得重复推送")
                .isEqualTo(pushesAfterFirst);

            // ③ 内容变了 → 必须再推
            Files.writeString(copied, "changed-content-longer", StandardCharsets.UTF_8);
            int afterChange = awaitPushCountAbove(pushesAfterFirst);
            assertThat(afterChange).as("内容变更必须再次推送").isGreaterThan(pushesAfterFirst);
        } finally {
            service.stopTurnWatch(SESSION);
        }
    }

    @Test
    @DisplayName("stopTurnWatch 后不再扫描（WHY：否则已结束的轮还会继续自动展开面板）")
    void stoppedWatchDoesNotScan() throws Exception {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
        Path dir = SessionArtifactService.deliverablesDir(SESSION);
        Files.createDirectories(dir);

        service.startTurnWatch(SESSION);
        service.stopTurnWatch(SESSION);
        // 停掉之后复制文件 → 不应被感知
        Files.writeString(dir.resolve("停止后复制.docx"), "x", StandardCharsets.UTF_8);
        Thread.sleep(2600);
        assertThat(pushCount()).as("停掉监看后不得再推送").isZero();
    }

    /**
     * 等推送次数**超过** `baseline`（最多 ~8s）。
     *
     * <p>⛔ 早期版本写成「先等 =0，再等 > lastSeen」，第二次调用时 pushCount 已非 0 ⇒ 第一个循环
     * 立刻退出、第二个循环比较的是同一个基线 ⇒ 直接返回（实测「内容变更必须再推」假红）。
     * 显式传基线后不存在这个歧义。
     *
     * @return 等待结束后的推送次数（未达预期时调用方自己断言）
     */
    private int awaitPushCountAbove(int baseline) throws InterruptedException {
        for (int i = 0; i < 40 && pushCount() <= baseline; i++) {
            Thread.sleep(200);
        }
        return pushCount();
    }

    /** 推送次数 · ⛔ 不吞异常（吞了会把「查不到」伪装成「没推送」，本批实测踩过）。 */
    private int pushCount() {
        return (int) org.mockito.Mockito.mockingDetails(wsTemplate).getInvocations().stream()
            .filter(i -> "convertAndSend".equals(i.getMethod().getName()))
            .filter(i -> TOPIC.equals(String.valueOf(i.getArguments()[0])))
            .count();
    }

    @Test
    @DisplayName("目录本身 / 不存在的路径 ⇒ false 且不抛（写盘钩子必须 fail-soft）")
    void dirItselfAndMissingPathAreIgnored() {
        Path dir = SessionArtifactService.deliverablesDir(SESSION);
        assertThat(service.syncDeliverableForPath(SESSION, dir.toString())).isFalse();
        assertThat(service.syncDeliverableForPath(SESSION, dir.resolve("不存在.docx").toString())).isFalse();
        // null / 空会话 → 直接 false
        assertThat(service.syncDeliverableForPath(null, dir.toString())).isFalse();
        assertThat(service.syncDeliverableForPath(SESSION, null)).isFalse();
        verify(mapper, never()).insert(any(SessionArtifactRecord.class));
    }
}
