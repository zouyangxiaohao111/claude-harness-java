package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-11 ?-EX-01 + EX-C R9] 50 turn 边界差分测试 · 探针 hook 空转 50 turn.
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: 探查期 ?-EX-01 声称「CC {@code >= MAX_AGENT_TURNS}
 * vs Java {@code turnCount() > maxTurns()}」疑似差 1（风险 low）。读 CC 真源后两类边界
 * 分别核验：
 * <ol>
 *   <li><b>主循环边界</b>：CC query.ts:1705 {@code maxTurns && nextTurnCount > maxTurns}
 *       （迭代末、递归前检查，nextTurnCount = turnCount + 1）与 Java LlmAgentLoop:2061
 *       {@code state.maxTurns() > 0 && state.turnCount() > state.maxTurns()}
 *       （迭代顶递增后检查）——两侧均为 <b>{@code >}</b>，模型调用恰好 maxTurns 次后停止，
 *       <b>无差 1</b>。</li>
 *   <li><b>agent hook 侧边界</b>：CC execAgentHook.ts:197-207 按 <b>assistant 消息数</b>计
 *       {@code turnCount++} 且 {@code turnCount >= MAX_AGENT_TURNS(50)} → abort + break
 *       （第 50 条 assistant 消息到达即熔断，<b>其 tool call 不再执行</b>）。Java 旧实现以
 *       {@code state.maxTurns(50)} 委托 LlmAgentLoop，第 50 轮完整执行（含 tool call）、
 *       第 51 轮迭代顶退出——模型调用数两侧同为 50（差 1 不暴露在调用数），差异仅在
 *       「第 50 轮 tool call 是否执行」。<b>[EX-C R9 实施]</b> ExecAgentHook 层包装计数
 *       （deps.callModel 包 onAssistantMessage）：第 50 条消息到达 → abort → loop
 *       aborted_streaming 退出（exitReason=ABORTED），第 50 轮 tool call 不执行。</li>
 * </ol>
 *
 * <p>本测试用探针 provider（恒返回 {@code tool_calls}，空转）+ <b>计数探针工具</b>
 * （注册进 ToolRegistry，每次真实执行 +1）驱动 ExecAgentHook：
 * <ul>
 *   <li>断言 <b>恰好 50 次模型调用</b>（callCount == 50，非 49 / 非 51）——锁定 Java
 *       侧 50 turn 边界不差 1；</li>
 *   <li>断言 <b>第 50 轮 tool call 不执行</b>（toolExecCount == 49：turns 1-49 各执行 1 次，
 *       第 50 轮被熔断跳过）——R9 差分锁定（旧实现 toolExecCount == 50）；</li>
 *   <li>断言 outcome == CANCELLED + {@code tengu_agent_stop_hook_max_turns} analytics
 *       （CC :242-247 等价；熔断走 ABORTED 退出仍按 hitMaxTurns 发 max_turns analytics）；</li>
 *   <li>对照组：maxTurns 熔断发生在 50 轮空转后（CC 语义注释 execAgentHook.ts:201）。</li>
 * </ul>
 *
 * <p>镜像 {@link ExecAgentHookSemanticsTest} 夹具（同包复用模式：ScriptableProvider /
 * CapturingTelemetry），不依赖 Spring 容器。
 *
 * @since IMPL-11 (P2 测试补强) · EX-C R9 (熔断语义差分)
 */
@DisplayName("[IMPL-11 ?-EX-01 + EX-C R9] 50 turn 边界差分（探针 hook 空转: 恰好 50 次模型调用 + 第 50 轮 tool call 不执行）")
class AgentTurnBoundaryDifferentialTest {

    private static final int MAX_AGENT_TURNS = 50; // CC execAgentHook.ts:119
    private static final String DEFAULT_FAST_MODEL = "haiku-test";
    private static final String HOOK_NAME = "boundary-agent-hook";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HookEvent hookEvent = HookEvent.userPromptSubmit("sess-boundary", "agent-1", "probe");

    /** 空转 tool_call（Read 探针）· 每次迭代模型都要求执行工具 → 永不自然终止. */
    private ToolUseBlock probeCall() {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", "transcript.txt");
        return new ToolUseBlock("toolu-probe-" + System.nanoTime(), "Read", input);
    }

    /**
     * 计数探针工具（注册进 ToolRegistry）· 每次真实执行 +1。
     *
     * <p>WHY (R9 差分): 熔断语义的可观察差异 = 第 50 轮 tool call 是否执行。旧实现
     * （maxTurns 委托主循环）第 50 轮完整执行 → execCount==50；R9 熔断（第 50 条消息
     * 到达即 abort）→ turns 1-49 各执行 1 次 → execCount==49。
     */
    private static final class CountingReadTool implements Tool {
        final AtomicInteger execCount = new AtomicInteger(0);

        @Override public String name() { return "Read"; }
        @Override public String description() { return "counting probe read"; }
        @Override public JsonNode inputSchema() { return new ObjectMapper().createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            execCount.incrementAndGet();
            return ToolResult.success(call.id(), "ok");
        }
        @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
        @Override public String interruptBehavior() { return "block"; }
    }

    /** 恒返回 tool_calls 的探针 provider · 超出脚本长度时重复最后一条. */
    static class ProbeProvider implements LlmProvider {
        final List<AssistantMessage> responses;
        final AtomicInteger callCount = new AtomicInteger(0);

        ProbeProvider(List<AssistantMessage> responses) { this.responses = responses; }

        @Override public String type() { return "test"; }
        @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                           List<com.nexusai.model.session.dto.ChatMessageDto> history, ArrayNode tools,
                           Integer maxOutputTokensOverride,
                           com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                           java.util.function.Consumer<ToolUseBlock> onToolCallComplete,
                           java.util.function.Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           com.nexusai.application.agent.tool.AbortController abortController,
                           java.util.function.Consumer<Throwable> onError,
                           Runnable onComplete) {
            int idx = callCount.getAndIncrement();
            AssistantMessage am = responses.get(Math.min(idx, responses.size() - 1));
            onAssistantMessage.accept(am);
            onComplete.run();
        }
    }

    /** 捕获 recordEvent 事件名 · 验证 tengu_agent_stop_hook_max_turns analytics. */
    static class CapturingTelemetry extends Telemetry {
        final List<String> eventNames = new ArrayList<>();

        @Override public void recordEvent(String name, java.util.Map<String, Object> attributes) {
            eventNames.add(name);
            super.recordEvent(name, attributes);
        }
    }

    private ExecAgentHook hookWith(LlmProvider provider, Telemetry telemetry, Tool probeTool) {
        // [MAINCHAIN-01] LlmAgentLoop 主链现调 2 参 getProvider(config, providerType)，须覆写 2 参版本
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) { return provider; }
        };
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        ToolRegistry parentRegistry = new ToolRegistry();
        parentRegistry.register(probeTool);   // 计数探针工具 → 第 50 轮是否执行可观察
        // registry=null: 不注册 enforcement hook, 避免熔断后 STOP 重入放大轮数
        return new ExecAgentHook(objectMapper, contextFactory, parentRegistry, null,
            ProviderConfig.empty(), DEFAULT_FAST_MODEL, telemetry, null, null);
    }

    private GenericHook.HookResult exec(ExecAgentHook h, String prompt) {
        AgentHook hook = new AgentHook(prompt, null, null, null, null, null);
        return h.exec(hook, HOOK_NAME, hookEvent, "{}", null, null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, null);
    }

    @Test
    @DisplayName("50 turn 边界: 空转探针恰好 50 次模型调用 + 第 50 轮 tool call 不执行 → CANCELLED + max_turns analytics")
    void probeHook_idlesExactly50Turns_thenCancelled() {
        // WHY (?-EX-01 + R9): CC execAgentHook.ts:197-207 第 50 条 assistant 消息到达即 abort，
        //   其 tool call 不再执行，模型调用数保持 50。旧 Java（maxTurns 委托主循环）第 50 轮
        //   完整执行（含 tool call）→ toolExecCount==50；R9 熔断后 toolExecCount==49（差分锁定）。
        CountingReadTool probeTool = new CountingReadTool();
        ProbeProvider p = new ProbeProvider(List.of(
            new AssistantMessage("checking", "tool_calls", List.of(probeCall()))
        ));
        CapturingTelemetry telemetry = new CapturingTelemetry();
        ExecAgentHook h = hookWith(p, telemetry, probeTool);

        GenericHook.HookResult r = exec(h, "verify");

        // 1. 恰好 MAX_AGENT_TURNS 次模型调用（CC: 第 50 条 assistant 消息熔断, 同为 50 次调用）
        assertThat(p.callCount.get())
            .as("空转探针必须恰好 50 次模型调用（CC 第 50 条消息熔断, 同为 50 次调用）")
            .isEqualTo(MAX_AGENT_TURNS);
        // 2. [R9 差分] 第 50 轮 tool call 不执行: turns 1-49 各执行 1 次 → 49（旧实现为 50）
        assertThat(probeTool.execCount.get())
            .as("第 50 轮 tool call 不得执行（turns 1-49 各执行 1 次 → 49；旧实现 50）")
            .isEqualTo(MAX_AGENT_TURNS - 1);
        // 3. outcome=cancelled（CC :238-252 hitMaxTurns → cancelled；熔断走 ABORTED 退出同映射）
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.CANCELLED);
        // 4. max_turns analytics（CC :242 logEvent('tengu_agent_stop_hook_max_turns')）
        assertThat(telemetry.eventNames).contains("tengu_agent_stop_hook_max_turns");
    }

    @Test
    @DisplayName("对照: 49 轮内正常结束不触发熔断（调用数 < 50, 无 max_turns analytics）")
    void probeHook_completesBeforeBoundary_notCancelled() {
        // WHY: 熔断只在 50 轮空转时触发 —— 正常 hook 应在边界内结束（第 2 轮 stop）。
        //       CC execAgentHook.ts:254-267: 未调 structured_output 正常结束 → outcome
        //       'cancelled' + tengu_agent_stop_hook_error(errorType=1)，不烧 max_turns。
        CountingReadTool probeTool = new CountingReadTool();
        ProbeProvider p = new ProbeProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(probeCall())),
            new AssistantMessage("done", "stop", List.of())
        ));
        CapturingTelemetry telemetry = new CapturingTelemetry();
        ExecAgentHook h = hookWith(p, telemetry, probeTool);

        GenericHook.HookResult r = exec(h, "verify");

        assertThat(p.callCount.get())
            .as("正常 hook 在第 2 轮 stop 完成, 调用数远小于 50")
            .isLessThan(MAX_AGENT_TURNS);
        // 对照: 未触发熔断 → 第 1 轮 tool call 正常执行（1 次）
        assertThat(probeTool.execCount.get())
            .as("未触发熔断时第 1 轮 tool call 正常执行")
            .isEqualTo(1);
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.CANCELLED);
        assertThat(telemetry.eventNames)
            .as("未到 50 turn 不得发 max_turns analytics（CC :242 仅在 hitMaxTurns 时发）")
            .doesNotContain("tengu_agent_stop_hook_max_turns");
        assertThat(telemetry.eventNames)
            .as("正常结束未返回 structured_output → errorType=1 analytics（CC :257-263）")
            .contains("tengu_agent_stop_hook_error");
    }
}
