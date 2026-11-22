package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SendMessageTool;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 代理退役 / 恢复配额 / 失败分档 / 停止标记 · 对齐 claude.exe 2.1.328 的 SendMessage 八步判定。
 *
 * <h2>WHY（规则九 · 意图验证）</h2>
 * <p>旧实现里 agent 终态会连名带 agentId 注销 ⇒ {@code resolveEntry} 恒 null ⇒
 * 「已完成/已停止 → 从 transcript 热启动」这条 CC 主路径在生产<b>不可达</b>；单测 mock 掉
 * resolveEntry 反而掩盖了它。本组用例全部走<b>真实</b> {@link AgentNameRegistry} +
 * 真实 {@link ResumeService}（只 mock 落不到盘上的 {@link SubagentTool}），锁死
 * 「注销 → 退役表 → 可寻址 → resume / 拒绝 / 配额 / 分档」整条链。
 */
@DisplayName("[agent-retire-resume] 退役表 + 配额 + 分档 + 停止标记")
class AgentResumeAlignmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEX = "a0123456789abcdef";
    private static final String SESS = "sess-a";

    // ───────────────────────── 夹具 ─────────────────────────

    private static String packed() {
        return HEX;
    }

    /** doResume 末尾的 taskOutputPath 走 CwdResolution.getOriginalCwdLayer ⇒ L1 槽必须可解析。 */
    private static void seedCwd(String sessionId, Path dir) {
        com.nexusai.application.agent.agent.SessionCwdHolder
            .setOriginalCwd(sessionId, dir.toAbsolutePath().toString());
    }

    @AfterEach
    void clearCwd() {
        com.nexusai.application.agent.agent.SessionCwdHolder.reset();
    }

    private static void writeTranscript(Path dir, String sessionId, String hex) {
        Map<String, Object> row = new HashMap<>();
        row.put("role", "assistant");
        row.put("content", "finished earlier");
        AgentTranscript.recordSidechainTranscript(dir, sessionId, hex,
            new ArrayList<>(List.of(row)), null);
    }

    private static void writeMeta(Path dir, String sessionId, String hex) throws Exception {
        AgentTranscript.writeMetadata(dir, sessionId, hex,
            new AgentTranscript.AgentMetadata("general-purpose", null, "work", null));
    }

    private static BackgroundTaskRunner runnerWith(BackgroundTask task) {
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.getTask(task.id())).thenReturn(Optional.of(task));
        return runner;
    }

    private static BackgroundTask completed(String agentId) {
        return new BackgroundTask(agentId, TaskType.LOCAL_AGENT, BackgroundTaskStatus.COMPLETED,
            "done", null, 1L, 2L, null, "", 0L, true, null, true, SESS,
            null, null, null, null, null, null);
    }

    private static SendMessageTool toolWith(AgentNameRegistry registry, ResumeService resume,
                                            BackgroundTaskRunner runner, Path dir) {
        SendMessageTool tool = new SendMessageTool(null);
        tool.setAgentNameRegistry(registry);
        if (resume != null) {
            tool.setResumeServiceProvider(provider(resume));
        }
        if (runner != null) {
            tool.setBackgroundTaskRunner(runner);
        }
        tool.setSessionDirResolver(s -> dir);
        return tool;
    }

    private static ToolUseContext ctx(String sessionId) {
        ToolUseContext ctx = mock(ToolUseContext.class);
        when(ctx.sessionId()).thenReturn(sessionId);
        return ctx;
    }

    private static ToolResult<?> send(SendMessageTool tool, String to, String message,
                                      ToolUseContext ctx) throws Exception {
        return (ToolResult<?>) tool.execute(new ToolUseBlock("c1", "SendMessage",
            JSON.readTree("{\"to\":\"" + to + "\",\"message\":\"" + message + "\"}")), ctx);
    }

    private static String data(ToolResult<?> r) {
        return String.valueOf(r.data());
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    // ───────────────────────── a. 完成后按名发送 → resume 成功 ─────────────────────────

    @Test
    @DisplayName("a) 完成后（已注销进退役表）按名发送 → 真链 resume，返回 Resuming agent")
    void completedAgent_resumesFromTranscript(@TempDir Path dir) throws Exception {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.setSessionDirResolver(s -> dir);
        registry.register(SESS, "researcher", HEX);
        writeTranscript(dir, SESS, HEX);
        seedCwd(SESS, dir);

        SubagentTool subagentTool = mock(SubagentTool.class);
        ResumeService resume = new ResumeService();
        resume.setSubagentTool(subagentTool);

        // 终态注销（生产点 = SubagentTool finally）→ 进退役表，不再不可达。
        registry.unregister(SESS, "researcher", HEX);
        assertThat(registry.retiredSize()).isEqualTo(1);

        SendMessageTool tool = toolWith(registry, resume, runnerWith(completed(HEX)), dir);
        ToolResult<?> r = send(tool, "researcher", "keep going", ctx(SESS));

        verify(subagentTool).executeResumeAsync(eq(packed()), eq("keep going"), any(), any(),
            any(), any(), any(), any(), eq(SESS));
        assertThat(data(r)).contains("Resuming agent researcher");
    }

    // ───────────────────────── b. 用户 Stop → k9 拒绝 ─────────────────────────

    @Test
    @DisplayName("b) 用户 Stop（内存标记）→ 自动 resume 拒绝，k9 原文")
    void stoppedByUser_autoResumeIsRefusedWithK9(@TempDir Path dir) {
        writeTranscript(dir, SESS, HEX);
        StopMarkers markers = new StopMarkers();
        markers.markStopped(packed().toString());

        ResumeService resume = new ResumeService();
        resume.setStopMarkers(markers);
        resume.setSubagentTool(mock(SubagentTool.class));

        assertThatThrownBy(() -> resume.resumeAgentBackground(packed(), "again", dir, SESS, null, false))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("Agent " + packed() + " was stopped and won't be resumed.")
            .hasMessageContaining("only launch a new agent if the user explicitly asks.")
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.AGENT_STOPPED_BY_USER));
    }

    @Test
    @DisplayName("b2) 有 lineage 时跳过停止探测（CC he={found:false}）→ 用户 Stop 也放行")
    void lineageSkipsStopProbe(@TempDir Path dir) {
        writeTranscript(dir, SESS, HEX);
        seedCwd(SESS, dir);
        StopMarkers markers = new StopMarkers();
        markers.markStopped(packed().toString());
        SubagentTool subagentTool = mock(SubagentTool.class);

        ResumeService resume = new ResumeService();
        resume.setStopMarkers(markers);
        resume.setSubagentTool(subagentTool);

        resume.resumeAgentBackground(packed(), "continue", dir, SESS, null, false, true);
        verify(subagentTool).executeResumeAsync(eq(packed()), eq("continue"), any(), any(),
            any(), any(), any(), any(), eq(SESS));
    }

    @Test
    @DisplayName("b3) 磁盘伴生文件 / meta.stoppedByUser 任一存在即拒（双写判据）")
    void stopMarkerOnDiskRejects(@TempDir Path dir) {
        writeTranscript(dir, SESS, HEX);
        AgentTranscript.markStoppedByUserInMetadata(dir, SESS, HEX);
        ResumeService resume = new ResumeService();
        resume.setSubagentTool(mock(SubagentTool.class));

        assertThatThrownBy(() -> resume.resumeAgentBackground(packed(), "again", dir, SESS, null, false))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.AGENT_STOPPED_BY_USER));
    }

    @Test
    @DisplayName("b4) 伴生标记 ifAbsent 幂等 + StopMarkers 不重复自增")
    void stopMarkerIsIdempotent(@TempDir Path dir) {
        AgentTranscript.markStoppedByUser(dir, SESS, HEX);
        AgentTranscript.markStoppedByUser(dir, SESS, HEX);
        assertThat(AgentTranscript.isStoppedByUser(dir, SESS, HEX)).isTrue();

        StopMarkers markers = new StopMarkers();
        assertThat(markers.markStopped("x")).isEqualTo(1);
        assertThat(markers.markStopped("x")).as("已停不重复加").isEqualTo(1);
    }

    // ───────────────────────── c. 无 transcript → transcriptMissing ─────────────────────────

    @Test
    @DisplayName("c) 无 transcript → transcriptMissing（no_transcript 档，REST 仍 404）")
    void missingTranscriptIsClassified(@TempDir Path dir) {
        ResumeService resume = new ResumeService();
        resume.setSubagentTool(mock(SubagentTool.class));

        assertThatThrownBy(() -> resume.resumeAgentBackground(packed(), "hi", dir, SESS, null, false))
            .isInstanceOf(ResumeErrorCode.ResumeTranscriptMissingException.class)
            .hasMessageContaining("could not be resumed: No transcript found for agent ID: " + packed())
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeTranscriptMissingException) e).errorCode())
                .isEqualTo(ResumeErrorCode.AGENT_TRANSCRIPT_MISSING));
        assertThat(ResumeErrorCode.telemetryCode(ResumeErrorCode.AGENT_TRANSCRIPT_MISSING))
            .isEqualTo("no_transcript");
    }

    @Test
    @DisplayName("c2) SendMessage 回执保留 no_transcript 分档（不被压成 Could not resume）")
    void sendReceiptKeepsTranscriptMissingTier(@TempDir Path dir) throws Exception {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.setSessionDirResolver(s -> dir);
        registry.register(SESS, "researcher", HEX);
        ResumeService resume = mock(ResumeService.class);
        doThrow(new ResumeErrorCode.ResumeTranscriptMissingException("missing"))
            .when(resume).resumeAgentBackground(any(), anyString(), any(), anyString(), any(),
                eq(false));

        ToolResult<?> r = send(toolWith(registry, resume, runnerWith(completed(HEX)), dir),
            "researcher", "hi", ctx(SESS));
        assertThat(data(r)).contains("\"degradedClass\":\"no_transcript\"");
        assertThat(data(r)).contains("No transcript found for agent ID: " + HEX);
    }

    // ───────────────────────── d. 配额满 → session_full，不排队 ─────────────────────────

    @Test
    @DisplayName("d) 配额满 → session_full（CC uSa 原文），不排队")
    void quotaFullRejectsWithoutQueueing(@TempDir Path dir) {
        ResumeQuota quota = new ResumeQuota(1);
        ResumeQuota.Release held = quota.tryAcquire(SESS);
        try {
            writeTranscript(dir, SESS, HEX);
            SubagentTool subagentTool = mock(SubagentTool.class);
            ResumeService resume = new ResumeService();
            resume.setResumeQuota(quota);
            resume.setSubagentTool(subagentTool);

            assertThatThrownBy(() -> resume.resumeAgentBackground(packed(), "hi", dir, SESS, null, false))
                .isInstanceOf(ResumeErrorCode.ResumeException.class)
                .hasMessageContaining("this session has no room for another agent now")
                .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                    .isEqualTo(ResumeErrorCode.SESSION_FULL));
            verify(subagentTool, never()).executeResumeAsync(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        } finally {
            held.release();
        }
        assertThat(quota.inUse(SESS)).isZero();
    }

    @Test
    @DisplayName("d2) SendMessage 回执保留 session_full 分档")
    void sendReceiptKeepsSessionFullTier(@TempDir Path dir) throws Exception {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.setSessionDirResolver(s -> dir);
        registry.register(SESS, "researcher", HEX);
        ResumeService resume = mock(ResumeService.class);
        doThrow(new ResumeErrorCode.ResumeException(ResumeErrorCode.SESSION_FULL, "full"))
            .when(resume).resumeAgentBackground(any(), anyString(), any(), anyString(), any(),
                eq(false));

        ToolResult<?> r = send(toolWith(registry, resume, runnerWith(completed(HEX)), dir),
            "researcher", "hi", ctx(SESS));
        assertThat(data(r)).contains("\"degradedClass\":\"session_full\"");
        assertThat(data(r)).contains("no room for another agent");
    }

    @Test
    @DisplayName("d3) 配额上限：env 优先；远端配置 >20 && <=1000 才生效并钳到 64")
    void quotaLimitResolution() {
        assertThat(ResumeQuota.resolveMax(null, null)).isEqualTo(20);
        assertThat(ResumeQuota.resolveMax("7", null)).isEqualTo(7);
        assertThat(ResumeQuota.resolveMax(null, 21)).isEqualTo(21);
        assertThat(ResumeQuota.resolveMax(null, 500)).as("钳到 64").isEqualTo(64);
        assertThat(ResumeQuota.resolveMax(null, 1001)).as("超上界忽略").isEqualTo(20);
        assertThat(ResumeQuota.resolveMax(null, 20)).as("须 >20").isEqualTo(20);
    }

    // ───────────────────────── e. 逐出分流 ─────────────────────────

    @Test
    @DisplayName("e) 逐出 + meta.taskKind → teammate 重拉 in-process")
    void evictedTeammateIsRelaunched(@TempDir Path dir) throws Exception {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.setSessionDirResolver(s -> dir);
        registry.register(SESS, "researcher", HEX);
        writeMeta(dir, SESS, HEX);
        AgentTranscript.writeMetaExtras(dir, SESS, HEX,
            new AgentTranscript.MetaExtras("general-purpose", "in_process_teammate", "researcher", "team"));
        registry.unregister(SESS, "researcher", HEX);

        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.getTask(HEX)).thenReturn(Optional.empty());

        SendMessageTool tool = toolWith(registry, null, runner, dir);
        TeammateRelauncher relauncher = mock(TeammateRelauncher.class);
        when(relauncher.relaunch(eq("researcher"), eq("team"), eq(SESS), any(), eq("go"), any()))
            .thenReturn(TeammateRelauncher.TeammateRelaunchResult.resumed("a111111111111111", 0));
        tool.setTeammateRelauncher(relauncher);

        ToolResult<?> r = send(tool, "researcher", "go", ctx(SESS));
        assertThat(data(r)).contains("in-process teammate");
        verify(relauncher).relaunch(eq("researcher"), eq("team"), eq(SESS), any(), eq("go"), any());
    }

    @Test
    @DisplayName("e2) 逐出 + meta 无 taskKind → subagent 等同 agent-stopped（resume）")
    void evictedSubagentResumesLikeStopped(@TempDir Path dir) throws Exception {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.setSessionDirResolver(s -> dir);
        registry.register(SESS, "researcher", HEX);
        writeMeta(dir, SESS, HEX); // 只有 agentType，无 taskKind
        registry.unregister(SESS, "researcher", HEX);

        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.getTask(HEX)).thenReturn(Optional.empty());

        ResumeService resume = mock(ResumeService.class);
        ToolResult<?> r = send(toolWith(registry, resume, runner, dir), "researcher", "go", ctx(SESS));

        verify(resume).resumeAgentBackgroundForLineage(eq(packed()), eq("go"), any(), eq(SESS), any(),
            eq(false), org.mockito.ArgumentMatchers.nullable(String.class));
        assertThat(data(r)).contains("Resuming agent researcher");
    }

    @Test
    @DisplayName("e3) 逐出 + meta 不存在 / 读失败 → 两档不同文案（CC dSa / cSa）")
    void evictedMetaMissingVsUnreadable(@TempDir Path dir) throws Exception {
        // 不存在
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.setSessionDirResolver(s -> dir);
        registry.register(SESS, "researcher", HEX);
        registry.unregister(SESS, "researcher", HEX);
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.getTask(HEX)).thenReturn(Optional.empty());
        SendMessageTool tool = toolWith(registry, null, runner, dir);
        ToolResult<?> missing = send(tool, "researcher", "go", ctx(SESS));
        assertThat(data(missing)).contains("has no saved settings for agent " + HEX);

        // 读失败（坏 JSON）
        AgentNameRegistry registry2 = new AgentNameRegistry();
        registry2.setSessionDirResolver(s -> dir);
        registry2.register(SESS, "researcher", HEX);
        Path meta = AgentTranscript.getMetadataPath(dir, SESS, HEX);
        Files.createDirectories(meta.getParent());
        Files.writeString(meta, "{not-json");
        registry2.unregister(SESS, "researcher", HEX);
        ToolResult<?> unreadable = send(toolWith(registry2, null, runner, dir), "researcher", "go", ctx(SESS));
        assertThat(data(unreadable)).contains("could not be read just now");
    }

    // ───────────────────────── f. restartResumes 达 5 不再自动续跑 ─────────────────────────

    @Test
    @DisplayName("f) restartResumes 达到 5 → 自动 resume 拒绝（resumes_spent）")
    void restartResumesBudgetExhausted(@TempDir Path dir) throws Exception {
        writeTranscript(dir, SESS, HEX);
        writeMeta(dir, SESS, HEX);
        seedCwd(SESS, dir);
        for (int i = 0; i < ResumeQuota.MAX_RESTART_RESUMES; i++) {
            AgentTranscript.spendRestartResume(dir, SESS, HEX);
        }
        assertThat(AgentTranscript.readRestartResumes(dir, SESS, HEX)).isEqualTo(5);

        SubagentTool subagentTool = mock(SubagentTool.class);
        ResumeService resume = new ResumeService();
        resume.setSubagentTool(subagentTool);

        assertThatThrownBy(() -> resume.resumeAgentBackground(packed(), "hi", dir, SESS, null, false))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("has already been resumed 5 times automatically")
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.SUBAGENT_RESUME_RESUMES_SPENT));
        verify(subagentTool, never()).executeResumeAsync(any(), any(), any(), any(), any(),
            any(), any(), any(), any());

        // 用户显式 resume 不受该预算限制（restartResumes 只拦自动续跑）。
        resume.resumeAgentBackground(packed(), "hi", dir, SESS, null, true);
        verify(subagentTool).executeResumeAsync(eq(packed()), any(), any(), any(), any(),
            any(), any(), any(), eq(SESS));
    }

    @Test
    @DisplayName("f2) restartResumes 台账：spend +1 / refund -1 / 批准清零并记 SetBackBy（同卡幂等）")
    void restartResumeLedgerSemantics(@TempDir Path dir) throws Exception {
        assertThat(AgentTranscript.readRestartResumes(dir, SESS, HEX)).isZero();
        assertThat(AgentTranscript.spendRestartResume(dir, SESS, HEX)).isEqualTo(1);
        assertThat(AgentTranscript.spendRestartResume(dir, SESS, HEX)).isEqualTo(2);
        assertThat(AgentTranscript.refundRestartResume(dir, SESS, HEX)).as("wait 退回").isEqualTo(1);
        assertThat(AgentTranscript.clearRestartResumes(dir, SESS, HEX, "toolu_card_1")).isTrue();
        assertThat(AgentTranscript.readRestartResumes(dir, SESS, HEX)).isZero();
        JsonNode meta = JSON.readTree(java.nio.file.Files.readString(
            AgentTranscript.getMetadataPath(dir, SESS, HEX)));
        assertThat(meta.path("restartResumesSetBackBy").asText()).isEqualTo("toolu_card_1");
        assertThat(AgentTranscript.clearRestartResumes(dir, SESS, HEX, "toolu_card_1"))
            .as("同一张卡不重复清零").isFalse();
    }

    // ───────────────────────── g. not-found 不写 mailbox ─────────────────────────

    @Test
    @DisplayName("g) 解析顺序最后一步：not-found → No agent named + 不写任何 mailbox")
    void notFoundDoesNotWriteMailbox() throws Exception {
        AgentNameRegistry registry = new AgentNameRegistry();
        SendMessageTool tool = new SendMessageTool(null);
        tool.setAgentNameRegistry(registry);

        ToolResult<?> r = send(tool, "nobody", "hello", ctx(SESS));
        assertThat(data(r)).contains("No agent named");
        assertThat(registry.retiredSize()).isZero();
    }

    // ───────────────────────── 恢复起点 ─────────────────────────

    @Test
    @DisplayName("恢复起点：末尾未收尾 assistant 截断 + 丢失 tool_use 补 is_error")
    void resumeStartPointTruncatesAndFillsInterrupted() {
        AgentMessage user = AgentMessage.of("user", "do work");
        AgentMessage assistantToolUse = new AgentMessage("assistant", "", false, null, false,
            "u2", "u1", List.of(new AgentMessage.ToolCallInfo("call-1", "Bash", "{}")), null);
        AgentMessage cutTurn = new AgentMessage("assistant", "", false, null, false,
            "u3", "u2", List.of(new AgentMessage.ToolCallInfo("call-2", "Read", "{}")), null);

        List<AgentMessage> truncated = ResumeService.truncateIncompleteTail(
            List.of(user, assistantToolUse, cutTurn));
        assertThat(truncated).hasSize(1);

        List<AgentMessage> filled = ResumeService.fillInterruptedToolResults(
            List.of(user, assistantToolUse));
        assertThat(filled).hasSize(3);
        AgentMessage interrupted = filled.get(2);
        assertThat(interrupted.role()).isEqualTo("tool");
        assertThat(interrupted.toolCallId()).isEqualTo("call-1");
        assertThat(interrupted.isApiError()).as("is_error=true").isTrue();
        assertThat(interrupted.content())
            .isEqualTo("A call of Bash that a restart lost was refused. Do not make it again.");
        // 补完后三层过滤不再丢掉该 assistant（它有结果了）
        assertThat(MessageFilters.filterUnresolvedToolUses(filled)).hasSize(3);
    }
}
