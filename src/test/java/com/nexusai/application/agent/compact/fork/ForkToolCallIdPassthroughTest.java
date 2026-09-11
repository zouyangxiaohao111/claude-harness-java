package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fork-toolcallid] fork 自执行工具轮产出的 tool 消息必须携带 {@code toolCallId}。
 *
 * <h2>WHY（CLAUDE.md 规则九 · 测试验证意图：为什么这件事重要）</h2>
 * fork（extract-memories / auto-dream / SM 等）在第 2 轮起要把「上一轮 assistant 的
 * {@code tool_calls}」与「工具结果 {@code role=tool} 消息」配对发给 provider。OpenAI/DeepSeek
 * 协议要求每条 {@code role=tool} 带 {@code tool_call_id}；缺失即整条被丢弃
 * （{@code OpenAiSdkProvider} 的 {@code case tool: if (m.toolCallId() == null) { log.warn(
 * "跳过缺少 toolCallId 的 tool 消息"); yield null; }}）→ 下一轮请求里 assistant(tool_calls) 无
 * 对应 tool 响应 → <b>400 insufficient tool messages following tool_calls message</b>。
 *
 * <p><b>真实缺陷</b>：{@code ProductionForkedQuery} 工具轮走 1 参重载
 * {@code LlmAgentLoop.toolResultMessage(result)}，该重载内部把 {@code toolUseId} 硬编码为
 * {@code null}（{@code LlmAgentLoop:13058-13061}）→ fork 每条工具结果都丢 id。真机日志实证：
 * 「跳过缺少 toolCallId 的 tool 消息」36 次，全在 fork 线程；EXTRACT_MEMORIES 第 2 轮 8 次。
 *
 * <p><b>CC 对照</b>：{@code toolExecution.ts:1367}
 * {@code tool.mapToolResultToToolResultBlockParam(result.data, toolUseID)} —— {@code toolUseID}
 * 是<b>显式透传参数</b>（来自 {@code ToolUseBlock.id()}），绝不从 {@code ToolResult} 里取
 * （Java 侧 IMP-C2 已对齐删掉 {@code ToolResult.toolUseId}）。
 *
 * <h2>RED teeth（把修复改回去 → 哪条断言红）</h2>
 * <ul>
 *   <li>把 {@code ProductionForkedQuery} 的调用改回 1 参重载 {@code toolResultMessage(result)}
 *       （toolUseId=null）→ {@link #forkToolResult_carriesToolCallId} 的
 *       {@code toolCallId == "call-abc"} 断言红；</li>
 *   <li>同一个变异 → {@link #noToolMessageWithoutIdReachesProvider} 的「provider 之前那层」
 *       守卫断言红（复刻 provider 丢弃条件 {@code toolCallId == null}）。</li>
 * </ul>
 */
@DisplayName("[fork-toolcallid] fork 工具结果 toolCallId 透传（CC toolExecution.ts:1367）")
class ForkToolCallIdPassthroughTest {

    private static final String MODEL = "test-model";

    /** 工具调用 id（LLM 产出的 tool_call id，必须原样出现在 role=tool 消息上）。 */
    private static final String TOOL_CALL_ID = "call-abc";

    @Test
    @DisplayName("fork 工具轮：role=tool 消息的 toolCallId == 对应 tool_call.id（不再为 null）")
    void forkToolResult_carriesToolCallId() {
        Tool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        ScriptedProvider provider = new ScriptedProvider(
            new AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock(TOOL_CALL_ID, "Echo", emptyObject())), "", null),
            new AssistantMessage("done", "stop", List.of()));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> ProviderConfig.empty(), registry);

        ForkedAgentResult result = loop.run(forkParams(allowAll(), forkCtxWith(echo)));

        assertThat(provider.callCount()).as("第 1 轮工具调用 + 第 2 轮收尾").isEqualTo(2);
        List<ChatMessageDto> toolMsgs = result.messages().stream()
            .filter(m -> m.role() == Role.tool)
            .toList();
        assertThat(toolMsgs)
            .as("fork 工具轮必须产出 1 条 role=tool 消息（否则本测试空转）")
            .hasSize(1);
        assertThat(toolMsgs.get(0).toolCallId())
            .as("toolCallId 必须显式透传 = 对应 tool_call.id（旧实现 1 参重载恒 null → provider 丢弃 → 400）")
            .isEqualTo(TOOL_CALL_ID);
    }

    @Test
    @DisplayName("provider 之前那层守卫：fork 产物中不存在「缺 toolCallId 的 tool 消息」")
    void noToolMessageWithoutIdReachesProvider() {
        Tool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        // 两轮工具调用 + 收尾：覆盖「多轮 fork」下每条 tool 结果都必须带 id（真机 36 次丢弃都是多轮场景）
        ScriptedProvider provider = new ScriptedProvider(
            new AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock("call-1", "Echo", emptyObject())), "", null),
            new AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock("call-2", "Echo", emptyObject())), "", null),
            new AssistantMessage("done", "stop", List.of()));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> ProviderConfig.empty(), registry);

        ForkedAgentResult result = loop.run(forkParams(allowAll(), forkCtxWith(echo)));

        // 复刻 OpenAiSdkProvider role=tool 分支的丢弃条件（toolCallId == null → yield null）。
        // 断言口径放在 provider 之前（fork 产物层），因此不依赖任何 provider 实现 —— 只要修复回退
        // （toolUseId=null），这里就会红。
        List<ChatMessageDto> idless = result.messages().stream()
            .filter(m -> m.role() == Role.tool)
            .filter(m -> m.toolCallId() == null)
            .toList();
        assertThat(idless)
            .as("任何缺 toolCallId 的 tool 消息都会被 provider 整条丢弃（真机 36 次「跳过缺少 toolCallId 的 "
                + "tool 消息」根因）——fork 产物里不允许存在")
            .isEmpty();
        // 且 id 集合与 tool_call 一一对应（配对完整性，不是「恰好为空」的弱断言）
        assertThat(result.messages().stream()
            .filter(m -> m.role() == Role.tool)
            .map(ChatMessageDto::toolCallId)
            .toList())
            .as("两条工具结果的 id 必须与两次 tool_call.id 逐位一致")
            .containsExactly("call-1", "call-2");
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具（RunForkedAgentTest / ProductionForkedQueryUsageCcContractTest 同构，
    // 独立类避免跨写集）
    // ════════════════════════════════════════════════════════════════════

    private static RunForkedAgent.ForkQueryParams forkParams(
            HookPermissionResolver.CanUseTool canUseTool, ToolUseContext ctx) {
        return new RunForkedAgent.ForkQueryParams(
            List.of(userMessage("sr", "fork prompt")), List.of("sys"), Map.of(), Map.of(),
            canUseTool, ctx, QuerySource.EXTRACT_MEMORIES, null, null, false,
            /*useGlobalCacheScope*/ false, null);
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(id, "s1", Role.user, "user", content, null, List.of(),
            null, null, null, "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, List.of(), List.of());
    }

    private static HookPermissionResolver.CanUseTool allowAll() {
        return (tool, input, ctx, toolUseId, forceDecision) ->
            ToolPermissionGate.DecisionResult.allow();
    }

    private static ToolUseContext forkCtxWith(Tool... tools) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT,
            Map.of(), List.of(tools), "", new AbortController(), List.of());
    }

    private static ObjectNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** 简单可执行工具（ToolRegistry.dispatch 目标）。 */
    static final class EchoTool implements Tool {
        @Override public String name() { return "Echo"; }
        @Override public String description() { return "echo test tool"; }
        @Override public JsonNode inputSchema() {
            return JsonNodeFactory.instance.objectNode();
        }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "echo:" + call.name());
        }
    }

    /** 脚本化 provider：每轮 stream 按脚本返回 assistant message。 */
    static final class ScriptedProvider implements LlmProvider {
        private final List<AssistantMessage> script;
        private final AtomicInteger callCount = new AtomicInteger();

        ScriptedProvider(AssistantMessage... script) {
            this.script = List.of(script);
        }

        int callCount() { return callCount.get(); }

        @Override public String type() { return "test"; }
        @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, ArrayNode tools,
                           Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           Consumer<String> onChunk,
                           Consumer<AssistantMessage> onAssistantMessage,
                           Consumer<ToolUseBlock> onToolCallComplete,
                           Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           AbortController abortController,
                           Consumer<Throwable> onError,
                           Runnable onComplete) {
            int idx = Math.min(callCount.getAndIncrement(), script.size() - 1);
            onAssistantMessage.accept(script.get(idx));
            onComplete.run();
        }
    }
}
