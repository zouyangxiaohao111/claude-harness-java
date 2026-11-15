package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * resume 门禁 · 对齐 claude.exe v2.1.325 resume 链路：
 * {@code gt()}（resumesInFlight）→ {@code Gq()}（stopPendingAgentIds）→ taskRegistry 状态。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>三道门的共同目的是「不许两个 worker 同时写同一 transcript」：
 * <ul>
 *   <li>并发 resume（in-flight）；</li>
 *   <li>kill 已发出但旧 worker 尚未退出（CC "still stopping"）；</li>
 *   <li>任务本身仍在 RUNNING。</li>
 * </ul>
 * 少任何一道，用户连点两次就会得到交错损坏的 transcript。
 */
@DisplayName("[agent-resume-align] resume 门禁")
class ResumeServiceGateTest {

    private static final UUID AGENT =
        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private static ResumeService newService(BackgroundTaskRunner runner) {
        ResumeService svc = new ResumeService();
        if (runner != null) {
            svc.setBackgroundTaskRunner(runner);
        }
        return svc;
    }

    @Test
    @DisplayName("stop 已请求但 worker 未退出 → still stopping（CC 文案）")
    void stopPendingRejectsWithStillStopping() {
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.isStopPending(AGENT.toString())).thenReturn(true);
        ResumeService svc = newService(runner);

        assertThatThrownBy(() -> svc.resumeAgentBackground(
            AGENT, "again", Paths.get("."), "sess-1", null))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("is still stopping")
            .hasMessageContaining("wait for it to exit before resuming")
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.SUBAGENT_RESUME_STILL_STOPPING));
    }

    @Test
    @DisplayName("任务仍 RUNNING → already running（CC 文案）")
    void runningTaskRejectsWithAlreadyRunning() {
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        BackgroundTask running = new BackgroundTask(
            AGENT.toString(), TaskType.LOCAL_AGENT, BackgroundTaskStatus.RUNNING,
            "still working", null, System.currentTimeMillis(), null, null,
            "/tmp/out", 0L, false, AGENT, true);
        when(runner.getTaskById(AGENT.toString())).thenReturn(running);
        ResumeService svc = newService(runner);

        assertThatThrownBy(() -> svc.resumeAgentBackground(
            AGENT, "again", Paths.get("."), "sess-1", null))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("already running or being resumed")
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.SUBAGENT_RESUME_ALREADY_RUNNING));
    }

    @Test
    @DisplayName("meta 记录的 cwd 不存在 → cwd_refused（CC 逐字文案，instead 出口句）")
    void missingRecordedCwdRejects() {
        AgentTranscript.AgentMetadata meta = new AgentTranscript.AgentMetadata(
            "general-purpose", null, "old work", "D:/definitely-not-a-real-dir-nexusai-test");

        assertThatThrownBy(() -> ResumeService.verifyMetadataCwd(meta))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("Cannot resume this agent in the directory its record names:"
                + " D:/definitely-not-a-real-dir-nexusai-test")
            .hasMessageContaining("Start a new agent instead.")
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.SUBAGENT_RESUME_CWD_REFUSED));
    }

    @Test
    @DisplayName("meta 未记录 cwd（旧文件）→ 不阻断")
    void absentRecordedCwdDoesNotBlock() {
        ResumeService.verifyMetadataCwd(
            new AgentTranscript.AgentMetadata("general-purpose", null, "old work", null));
        ResumeService.verifyMetadataCwd(null);
    }

    @Test
    @DisplayName("meta 记录的 cwd 是真实目录 → 通过")
    void existingRecordedCwdPasses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        ResumeService.verifyMetadataCwd(
            new AgentTranscript.AgentMetadata("general-purpose", null, "work", tmp.toString()));
    }

    @Test
    @DisplayName("用户停止过 + 自动 resume → AgentStoppedByUserError（CC 逐字文案）")
    void stoppedByUserRejectsAutomaticResume(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        String hex = "a0123456789abcdef";
        AgentTranscript.markStoppedByUser(tmp, "sess-1", hex);

        assertThatThrownBy(() -> ResumeService.verifyNotStoppedByUser(
            tmp, "sess-1", hex, "uuid-legacy", false))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("Agent uuid-legacy was stopped and won't be resumed.")
            .hasMessageContaining("only launch a new agent if the user explicitly asks.")
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.AGENT_STOPPED_BY_USER));
    }

    @Test
    @DisplayName("用户停止过但用户显式 resume → 放行（标记随后清理）")
    void stoppedByUserAllowsExplicitUserResume(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        String hex = "a0123456789abcdef";
        AgentTranscript.markStoppedByUser(tmp, "sess-1", hex);
        assertThat(AgentTranscript.isStoppedByUser(tmp, "sess-1", hex)).isTrue();

        ResumeService.verifyNotStoppedByUser(tmp, "sess-1", hex, "uuid-legacy", true);

        AgentTranscript.clearStoppedByUser(tmp, "sess-1", hex);
        assertThat(AgentTranscript.isStoppedByUser(tmp, "sess-1", hex)).isFalse();
        // 清理后自动 resume 也不再被拦
        ResumeService.verifyNotStoppedByUser(tmp, "sess-1", hex, "uuid-legacy", false);
    }

    @Test
    @DisplayName("门抛错后 in-flight 名额释放（否则该 agent 永久不可 resume）")
    void inFlightSlotReleasedAfterRejection() {
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.isStopPending(AGENT.toString())).thenReturn(true);
        ResumeService svc = newService(runner);

        // 第一次：被门 2 拒（STILL_STOPPING）
        assertThatThrownBy(() -> svc.resumeAgentBackground(
            AGENT, "again", Paths.get("."), "sess-1", null))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("is still stopping");
        // 第二次：仍是门 2 拒 —— 若名额没释放，这里会变成 ALREADY_RUNNING
        assertThatThrownBy(() -> svc.resumeAgentBackground(
            AGENT, "again", Paths.get("."), "sess-1", null))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.SUBAGENT_RESUME_STILL_STOPPING));
    }
}
