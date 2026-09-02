package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.infra.llm.TaskBudgetParam;
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
 * IMP2-19 · fork usage 非恒空 CC 契约测试（S-11 · forkedAgent.ts:558-566）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC {@code runForkedAgent} 从 message_delta
 * stream 事件累加真实 usage（forkedAgent.ts:558-566：每轮 {@code output_tokens} + input/cache
 * 增量），返回 {@code totalUsage} 供 extract-memories/auto-dream 遥测消费。目标端
 * {@code ProductionForkedQuery} 此前无 usage 回调通道 → totalUsage 恒空（自登记 :64-67，
 * S-11/✗-7）。本测试锁定修复后契约：
 * <ol>
 *   <li>provider 每轮 {@code AssistantMessage.usage}（AgentUsage）4 token 字段逐轮全量累加进
 *       totalUsage → <b>非恒空</b>（input/output/cache 均真实，[IMP-MV2-10]）；</li>
 *   <li>多轮 fork（工具调用 → 继续 → 最终回答）Σ 各轮 4 字段 = totalUsage 对应字段；</li>
 *   <li>全程无 usage（totalTokens=0）→ 仍为全零（如实，不伪造）。</li>
 * </ol>
 *
 * <p><b>[IMP-MV2-10] 全字段累计</b>: 数据源 = AssistantMessage.usage（AgentUsage，
 * AnthropicSdkProvider 从 message_start/message_delta usage 解析完整 4 token 字段，DEC-04
 * 闭环）——旧实现 `new ForkUsage(0, outputTokens, 0, 0)` 截断累计（input/cache 恒 0）已删，
 * 逐轮 4 字段全量累计对齐 CC forkedAgent.ts:557-566。
 *
 * <p><b>RED teeth</b>: 修复前 totalUsage 的 input/cache 恒 0（截断累计），input/cache 非零
 * 断言 FAIL；逐轮 Σ 断言把「只在末轮记一次」的口径错误也打红。
 */
@DisplayName("[IMP2-19+IMP-MV2-10] fork usage 全字段累计（ProductionForkedQuery 逐轮累加 4 token · S-11）")
class ProductionForkedQueryUsageCcContractTest {

    private static final String MODEL = "test-model";

    // ── 场景 1：多轮 fork（工具调用 → 最终回答），每轮 provider 上报完整 AgentUsage（[IMP-MV2-10] 全字段）──

    @Test
    @DisplayName("fork usage 非恒空：逐轮 outputTokens 累加进 totalUsage（forkedAgent.ts:558-566）")
    void forkUsage_accumulatesPerTurnTokens() {
        Tool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        // 第 1 轮：工具调用（input=1000/output=300/cacheRead=200/cacheCreate=50）；
        // 第 2 轮：最终回答（input=1000/output=42/cacheRead=200/cacheCreate=0）——
        //   [IMP-MV2-10] AssistantMessage.usage（AgentUsage）承载 4 token 字段，
        //   ProductionForkedQuery 逐轮全量累计（对齐 CC forkedAgent.ts:557-566 全量累计）
        ScriptedProvider provider = new ScriptedProvider(
            new AssistantMessage("", "tool_calls", List.of(new ToolUseBlock("c1", "Echo", emptyObject())), "", null,
                new AgentUsage(1000L, 300L, 50L, 200L, null, null, null)),
            new AssistantMessage("done", "stop", List.of(), "", null,
                new AgentUsage(1000L, 42L, 0L, 200L, null, null, null)));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> ProviderConfig.empty(), registry);

        ForkedAgentResult result = loop.run(forkParams(null, allowAll(), forkCtxWith(echo)));

        assertThat(provider.callCount()).isEqualTo(2);
        // [IMP-MV2-10] 全字段累计：input=Σ(1000+1000)、output=Σ(300+42)、cacheRead=Σ(200+200)、cacheCreate=50
        assertThat(result.totalUsage()).isNotNull();
        assertThat(result.totalUsage().inputTokens()).isEqualTo(2000L);
        assertThat(result.totalUsage().outputTokens()).isEqualTo(342L);
        assertThat(result.totalUsage().cacheReadInputTokens()).isEqualTo(400L);
        assertThat(result.totalUsage().cacheCreationInputTokens()).isEqualTo(50L);
        // 恒空守卫：4 字段和非 0 → 非恒空（input/cache 不再被截断为 0）
        assertThat(result.totalUsage().inputTokens() + result.totalUsage().outputTokens()
            + result.totalUsage().cacheReadInputTokens() + result.totalUsage().cacheCreationInputTokens())
            .as("fork usage 必须非恒空（S-11 验收）")
            .isNotZero();
    }

    // ── 场景 2：单轮纯文本回答（input=500/output=1000/cacheRead=100）──

    @Test
    @DisplayName("fork usage 单轮纯文本：outputTokens 累计（非恒空）")
    void forkUsage_singleTurnPlainAnswer() {
        Tool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        // [IMP-MV2-10] 单轮全字段透传（input=500/output=1000/cacheRead=100/cacheCreate=0）
        ScriptedProvider provider = new ScriptedProvider(
            new AssistantMessage("final answer", "stop", List.of(), "", null,
                new AgentUsage(500L, 1000L, 0L, 100L, null, null, null)));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> ProviderConfig.empty(), registry);

        ForkedAgentResult result = loop.run(forkParams(null, allowAll(), forkCtxWith(echo)));

        assertThat(provider.callCount()).isEqualTo(1);
        assertThat(result.totalUsage().inputTokens()).isEqualTo(500L);
        assertThat(result.totalUsage().outputTokens()).isEqualTo(1000L);
        assertThat(result.totalUsage().cacheReadInputTokens()).isEqualTo(100L);
        assertThat(result.totalUsage().cacheCreationInputTokens()).isZero();
    }

    // ── 场景 3：provider 全程无 usage（totalTokens=0）→ 仍为全零（如实不伪造）──

    @Test
    @DisplayName("fork usage 无 usage 上报 → 全零（如实，不伪造估算）")
    void forkUsage_noUsageReported_staysEmpty() {
        Tool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        ScriptedProvider provider = new ScriptedProvider(
            new AssistantMessage("done", "stop", List.of()));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> ProviderConfig.empty(), registry);

        ForkedAgentResult result = loop.run(forkParams(null, allowAll(), forkCtxWith(echo)));

        // 无 usage 上报 → 4 字段全零（如实，不伪造估算）
        assertThat(result.totalUsage().inputTokens()).isZero();
        assertThat(result.totalUsage().outputTokens()).isZero();
        assertThat(result.totalUsage().cacheReadInputTokens()).isZero();
        assertThat(result.totalUsage().cacheCreationInputTokens()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具（RunForkedAgentTest.ScriptedProvider/EchoTool 同构，独立类避免跨写集）
    // ════════════════════════════════════════════════════════════════════

    private static RunForkedAgent.ForkQueryParams forkParams(Integer maxTurns,
            HookPermissionResolver.CanUseTool canUseTool, ToolUseContext ctx) {
        return new RunForkedAgent.ForkQueryParams(
            List.of(userMessage("sr", "fork prompt")), List.of("sys"), Map.of(), Map.of(),
            canUseTool, ctx, QuerySource.EXTRACT_MEMORIES, null, maxTurns, false,
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
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
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

    /** 脚本化 provider：每轮 stream 按脚本返回 assistant message（含 outputTokens）。 */
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
