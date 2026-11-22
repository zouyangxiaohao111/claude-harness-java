package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.subagent.ResumeService;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SendMessage 对已停止 agent 的热启动 · 对齐 CC
 * 「names keep working after an agent completes (a send resumes it from its transcript)」。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>如果只把消息塞进队列：agent 已结束时没有人消费它，消息<b>静默丢失</b>，
 * 调用方却拿到 success。本用例锁死「已停止 ⇒ 走 resume，不再 queue」。
 */
@DisplayName("[agent-resume-align] SendMessage 热启动已停止子代理")
class SendMessageToolResumeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("agent 已停止 ⇒ resume 同一 agent，不再只入队")
    void stoppedAgent_resumes() throws Exception {
        AgentNameRegistry registry = mock(AgentNameRegistry.class);
        when(registry.resolveEntry("sess-1", "researcher"))
            .thenReturn(new AgentNameRegistry.AgentNameEntry("sess-1", "researcher", "a1234567890abcdef"));
        ResumeService resume = mock(ResumeService.class);
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        BackgroundTask completed = new BackgroundTask(
            "a1234567890abcdef", TaskType.LOCAL_AGENT, BackgroundTaskStatus.COMPLETED,
            "done", null, 1L, 2L, null, "", 0L, true, null, true, "sess-1",
            null, null, null, null, null, null);
        when(runner.getTask("a1234567890abcdef")).thenReturn(Optional.of(completed));

        SendMessageTool tool = new SendMessageTool(null);
        tool.setAgentNameRegistry(registry);
        tool.setResumeServiceProvider(provider(resume));
        tool.setBackgroundTaskRunner(runner);
        tool.setSessionDirResolver(s -> java.nio.file.Paths.get("."));
        ToolUseContext ctx = mock(ToolUseContext.class);
        when(ctx.sessionId()).thenReturn("sess-1");

        ToolResult<?> r = (ToolResult<?>) tool.execute(new ToolUseBlock("c1", "SendMessage",
            JSON.readTree("{\"to\":\"researcher\",\"message\":\"keep going\"}")), ctx);

        // 模型发起的自动 resume → userInitiated=false（stoppedByUser 门据此拦截用户停止过的 agent）；
        // [lineage] 发起方身份取 ToolUseContext（此处 mock 无 agentId → null，lineage 由 ResumeService 现算）
        verify(resume).resumeAgentBackgroundForLineage(any(), eq("keep going"), any(), eq("sess-1"),
            any(), eq(false), org.mockito.ArgumentMatchers.nullable(String.class));
        verify(registry, never()).queue(anyString(), anyString());
        // CC 成功文案逐字：`Resuming agent <name>`（偏移 356128040 ws()）
        assertThat(String.valueOf(r.data())).contains("Resuming agent researcher");
    }

    @Test
    @DisplayName("agent 仍在运行 ⇒ 只入队（不触发 resume）")
    void runningAgent_queues() throws Exception {
        AgentNameRegistry registry = mock(AgentNameRegistry.class);
        when(registry.resolveEntry("sess-1", "researcher"))
            .thenReturn(new AgentNameRegistry.AgentNameEntry("sess-1", "researcher", "a1234567890abcdef"));
        ResumeService resume = mock(ResumeService.class);
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        BackgroundTask running = new BackgroundTask(
            "a1234567890abcdef", TaskType.LOCAL_AGENT, BackgroundTaskStatus.RUNNING,
            "working", null, 1L, null, null, "", 0L, false, null, true, "sess-1",
            null, null, null, null, null, null);
        when(runner.getTask("a1234567890abcdef")).thenReturn(Optional.of(running));

        SendMessageTool tool = new SendMessageTool(null);
        tool.setAgentNameRegistry(registry);
        tool.setResumeServiceProvider(provider(resume));
        tool.setBackgroundTaskRunner(runner);
        tool.setSessionDirResolver(s -> java.nio.file.Paths.get("."));
        ToolUseContext ctx = mock(ToolUseContext.class);
        when(ctx.sessionId()).thenReturn("sess-1");

        tool.execute(new ToolUseBlock("c2", "SendMessage",
            JSON.readTree("{\"to\":\"researcher\",\"message\":\"status?\"}")), ctx);

        // [agent-notice] 入队携带来源/会话/origin（稳定 ID 由 registry 生成，此处只校验路由参数）
        verify(registry).queue("a1234567890abcdef", "sess-1", "team-lead", "status?", null, "coordinator");
        verify(resume, never()).resumeAgentBackgroundForLineage(any(), anyString(), any(), anyString(),
            any(), org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.nullable(String.class));
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }
}
