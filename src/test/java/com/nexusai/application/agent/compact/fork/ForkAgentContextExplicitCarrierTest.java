package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.RunForkedAgent.ForkQueryParams;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.model.session.dto.FinishReason;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[S1-T18] fork / autodream 归因判定与落地的行为断言 —— 结论 = <u>本该有</u></b>。
 *
 * <h2>判定依据（读 CC 真源，非注释转述）</h2>
 * <p>主真源 {@code D:/code/ai_project/claude-code-best}：
 * {@code grep -n "agentContext|runWithAgentContext|invokingRequestId" src/utils/forkedAgent.ts}
 * ⇒ <b>零命中</b>。⇒ fork <b>不建立</b>自己的 AgentContext（既不 new 也不 run-with）；
 * 其 API 调用归因到<b>发起它的 agent</b>（Node {@code AsyncLocalStorage} 跨 await 自动传播到
 * fork 内部的 {@code query()}）。故 fork 的归因上下文<b>本该有</b>（= 发起者上下文），
 * <b>不是</b>「本就不需要」。
 *
 * <h2>落地（显式载体，非 ambient）</h2>
 * <ol>
 *   <li>{@link RunForkedAgent#run} 在 {@code createIsolatedContext} 之后把<b>发起者</b>的上下文
 *       （{@code cs.toolUseContext().agentContext()}）显式盖章到隔离 ctx ——
 *       {@code ToolUseContext.with(SubagentContextOverrides)} 对 agentContext 取 null
 *       （「新 agent 不继承父」语义，对 {@code createSubagentContext} 的真子代理正确），
 *       但 fork <b>不是新 agent</b>，故按 SubagentExecutor 同款「显式盖章」手法补上。</li>
 *   <li>{@code ProductionForkedQuery.streamOnce} 从 fork 上下文的<b>显式字段</b>取
 *       （原读 {@code AgentContext.getAgentContext()} ambient —— 已删）。</li>
 * </ol>
 *
 * <h2>断言</h2>
 * <ul>
 *   <li>★ {@link #withOverrides_doesNotInheritAgentContext()} —— 隔离点本身<b>清零</b>
 *       （守护「新 agent 不继承父归因上下文」这一失败方向取属性缺失的既有设计，
 *       防止本批顺手把 {@code with()} 改成继承）。</li>
 *   <li>★ {@link #forkLoop_isolatedContext_carriesInitiatorContext()} —— 经
 *       {@link RunForkedAgent#run} 后，交给 query 的 {@code ForkQueryParams.toolUseContext()}
 *       携带的 agentContext <b>就是发起者的同一实例</b>。</li>
 *   <li>★ {@link #forkLoop_mainThreadFork_keepsNull()} —— 发起者无上下文（主线程 fork）时
 *       隔离 ctx 仍为 null 且<b>不新建实例</b>（同值短路）。</li>
 *   <li>★ {@link #productionForkedQuery_sendsInitiatorContextToProvider()} —— 真的走到了
 *       provider 发送边界：stub provider 捕获到的末位实参 == 发起者同一实例（端到端，
 *       而不只是「ctx 上有值」）。</li>
 * </ul>
 */
class ForkAgentContextExplicitCarrierTest {

    private static final String AGENT_ID = "a0123456789abcdef";

    private static AgentContext ctx() {
        return new AgentContext.SubagentContext(AGENT_ID, "sess-parent", "Explore", true, "req-1", "spawn");
    }

    private static ToolUseContext tuc(AgentContext agentContext) {
        ToolUseContext base = new ToolUseContext(
            UUID.randomUUID(), "sess-parent", PermissionMode.DEFAULT,
            Map.of(), List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of());
        return base.withAgentContext(agentContext);
    }

    // ──────────────────────────── ★ 行为断言 ────────────────────────────

    @Test
    @DisplayName("★ 隔离点清零：with(SubagentContextOverrides) 的 agentContext 取 null（失败方向取属性缺失）")
    void withOverrides_doesNotInheritAgentContext() {
        ToolUseContext parent = tuc(ctx());
        assertThat(parent.agentContext()).as("前置：父上下文确实带着归因上下文").isNotNull();

        ToolUseContext isolated = RunForkedAgent.createIsolatedContext(parent, null, null);
        assertThat(isolated.agentContext())
            .as("fork 隔离点必须清空 agentContext（新 agent/新隔离上下文不继承父归因上下文）；"
                + "若 RED ⇒ 有人把 with() 改成继承 —— 那会让「新 invocation 不带旧边」语义消失")
            .isNull();
    }

    @Test
    @DisplayName("★ RunForkedAgent.run：隔离 ctx 显式携带发起者的同一 agentContext 实例")
    void forkLoop_isolatedContext_carriesInitiatorContext() {
        AgentContext initiator = ctx();
        ToolUseContext parent = tuc(initiator);
        CacheSafeParams cs = new CacheSafeParams(
            List.of(), Map.of(), Map.of(), parent, List.of(), false, null);
        ForkedAgentParams params = new ForkedAgentParams(
            List.of(userMessage("p-1")), cs, null, QuerySource.COMPACT, "compact",
            null, 1, false, true, null, null);

        ForkQueryParams[] captured = new ForkQueryParams[]{null};
        RunForkedAgent.run(params, p -> {
            captured[0] = p;
            return new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty(), null);
        });

        assertThat(captured[0]).as("query seam 必须被调用").isNotNull();
        assertThat(captured[0].toolUseContext()).as("fork 上下文必须存在").isNotNull();
        assertThat(captured[0].toolUseContext().agentContext())
            .as("fork 的 API 调用归因到**发起它的 agent**（CC AsyncLocalStorage 跨 await 传播等价）；"
                + "RED ⇒ fork 侧归因上下文丢失（provider 事件不带 invokingRequestId）")
            .isSameAs(initiator);
    }

    @Test
    @DisplayName("★ 主线程 fork（发起者无上下文）：隔离 ctx 恒 null 且不新建实例")
    void forkLoop_mainThreadFork_keepsNull() {
        ToolUseContext parent = tuc(null);
        CacheSafeParams cs = new CacheSafeParams(
            List.of(), Map.of(), Map.of(), parent, List.of(), false, null);
        ForkedAgentParams params = new ForkedAgentParams(
            List.of(userMessage("p-1")), cs, null, QuerySource.COMPACT, "compact",
            null, 1, false, true, null, null);

        ForkQueryParams[] captured = new ForkQueryParams[]{null};
        RunForkedAgent.run(params, p -> {
            captured[0] = p;
            return new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty(), null);
        });

        assertThat(captured[0].toolUseContext().agentContext())
            .as("主线程 fork（等价 CC 主线程 undefined）⇒ 显式 null，且 withAgentContext(null) "
                + "同值短路返回同一实例（零新实例、零行为变化）")
            .isNull();
        assertThat(captured[0].toolUseContext().sessionId()).isEqualTo("sess-parent");
    }

    @Test
    @DisplayName("★ 端到端：ProductionForkedQuery 把发起者上下文送到 provider 的显式实参")
    void productionForkedQuery_sendsInitiatorContextToProvider() {
        AgentContext initiator = ctx();
        Object[] capturedAgentContext = new Object[]{null};
        LlmProvider provider = capturingProvider(capturedAgentContext);

        ForkQueryParams params = new ForkQueryParams(
            List.of(userMessage("u-1")),
            List.of(), Map.of(), Map.of(),
            null, tuc(initiator),
            QuerySource.COMPACT, null, 1, true, false, null);

        new ProductionForkedQuery(() -> provider, () -> "test-model",
            () -> new ProviderConfig("https://api.test", "sk-test"),
            null, null).run(params);

        assertThat(capturedAgentContext[0])
            .as("provider.stream 的末位显式实参 = fork 上下文字段携带的**同一** agentContext 实例；"
                + "RED ⇒ 归因上下文没走到发送边界（旧实现读 ambient 在该线程不可靠）")
            .isSameAs(initiator);
    }

    // ──────────────────────────── stub ────────────────────────────

    private static LlmProvider capturingProvider(Object[] sink) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }

            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> blocks,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxTokens,
                                         com.nexusai.infra.llm.TaskBudgetParam taskBudget, String effort,
                                         String querySource, Consumer<String> onChunk,
                                         Consumer<AssistantMessage> onAssistant,
                                         Consumer<com.nexusai.application.agent.tool.ToolUseBlock> onToolCall,
                                         Consumer<String> onReasoning, Runnable onStreamingFallback,
                                         com.nexusai.application.agent.tool.AbortController abort,
                                         Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite,
                                         AgentContext agentContext) {
                sink[0] = agentContext;
                onAssistant.accept(new AssistantMessage("fork-ok", "stop", List.of()));
                onComplete.run();
            }

            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "ok";
            }
        };
    }

    private static ChatMessageDto userMessage(String id) {
        return new ChatMessageDto(
            id, null, Role.user, "user", "hi", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}
