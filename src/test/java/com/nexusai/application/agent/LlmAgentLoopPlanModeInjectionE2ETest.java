package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.PlanModeAttachments;
import com.nexusai.application.agent.compact.PlanProviderImpl;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [RV-E-01 GAP-02] plan 附件注入链 re-wire 端到端守卫。
 *
 * <p><b>意图 (WHY · CLAUDE.md 规则 9)</b>: CC {@code getAttachmentMessages} 每 tool 轮都注册
 * {@code maybe('plan_mode', getPlanModeAttachments)}（attachments.ts:881-882）—— 模型在 plan 模式
 * 每个 tool 轮都必须收到 planFilePath，才知道 plan 文件写到哪；否则模型永不写 plan 文件 →
 * getPlan 恒 null → 读侧死链。Java 端 {@code EnterPlanModeTool} 只置 mode=PLAN 无 planFilePath，
 * 若 {@code maybeInjectPlanModeAttachments} 不被挂回每 tool 轮注入链，planFilePath 永不送达模型。
 * 本测试驱动真实 {@code new LlmAgentLoop(factory)} 一个 tool 轮，捕获 provider.stream 第 4 参
 * （messages），断言其含 plan_mode meta user 消息且内容携带 planFilePath —— 删 LlmAgentLoop 内
 * 的 re-wire 行即 RED。
 *
 * <p><b>appState 前置</b>: 经 public {@code loop.setAppState} 写 {@code toolPermissionContext}
 * （mode=PLAN）+ {@code planModeFlags}（模拟 EnterPlanModeTool 生产不变量：mode=PLAN ⟹ flags 已
 * 存在，防 getOrCreateFlags 在不可变快照上 put）。
 */
class LlmAgentLoopPlanModeInjectionE2ETest {

    @Test
    @DisplayName("plan 模式每 tool 轮 LLM 消息携带 plan_mode 附件（含 planFilePath）")
    void planMode_perTurnLlmMessagesCarryPlanFilePath() {
        // 捕获 provider.stream 的 messages（17-arg blocks 重载第 4 参 · ModelCaller.java:84）
        List<List<ChatMessageDto>> captured = new ArrayList<>();
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            captured.add(inv.getArgument(3));
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("response");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("response", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        ctxFactory.setNotificationQueue(new NotificationQueue());
        loop.setContextFactory(ctxFactory);

        // 前置写 appStateRef：mode=PLAN + planModeFlags（模拟 EnterPlanModeTool 已置位）。
        loop.setAppState(prev -> {
            Map<String, Object> next = new java.util.LinkedHashMap<>(prev);
            next.put("toolPermissionContext", ToolPermissionContext.strict(PermissionMode.PLAN));
            next.put(PlanModeAttachments.APP_STATE_FLAGS_KEY, new PlanModeAttachments.PlanModeFlags());
            return next;
        });

        // 主线程 session（agentId=null → 完整 base TUC 可达）：forTest 的 sessionId=null 会使
        // buildBaseToolUseContext 返回 null → perTurnTuc null → maybeInjectPlanModeAttachments 早退。
        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = loop.run(RunRequest.session("hello", sid, null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(captured).as("必须至少发起一次 LLM stream 调用").isNotEmpty();
        // 期望 planFilePath 与生产同源：maybeInjectPlanModeAttachments 内 new PlanProviderImpl(state.sessionId())
        String expectedPlanFilePath = new PlanProviderImpl(state.sessionId()).getPlanFilePath(null);
        assertThat(captured.stream()
            .flatMap(List::stream)
            .filter(m -> m.isMeta() && m.content() != null)
            .map(ChatMessageDto::content)
            .toList())
            .as("plan 模式每个 tool 轮 LLM 消息必须携带 plan_mode 附件（system-reminder + planFilePath）")
            .anyMatch(c -> c.contains("<system-reminder>")
                && c.contains("You should create your plan at " + expectedPlanFilePath));
    }
}
