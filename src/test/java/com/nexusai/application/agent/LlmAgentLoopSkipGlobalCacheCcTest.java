package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.TokenCounter;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * [IMP-SP2-07 G1] needsToolBasedCacheMarker 等价物（CC claude.ts:1212-1214）：
 * {@code useGlobalCacheFeature && filteredTools.some(t => t.isMcp === true && !willDefer(t))}。
 *
 * <p><b>WHY 本测试存在（意图）</b>:
 * <ol>
 *   <li><b>G1 等价物接线</b> — Java 无 tool-search（willDefer 恒 false）→ Java 等价物 =
 *       gate && 发送工具集存在 MCP 工具（McpServerScope.isMcpTool 等价 t.isMcp===true）。
 *       恒传 false 时 MCP 工具存在 + firstParty 走 boundary 模式 2 → 静态段带 GLOBAL
 *       cache_control，而 MCP 工具是 per-user 动态段 → 与主线程前缀不一致、缓存不生效且
 *       与 CC 语义偏离（claude.ts:1210-1214 注释：MCP tools are per-user → can't globally
 *       cache）。</li>
 *   <li><b>模式 2 保持</b> — 无 MCP 工具 + gate=true → skipGlobalCache=false → boundary
 *       模式 2（静态段 GLOBAL）必须保持（CC splitSysPromptPrefix api.ts:368-397）。</li>
 * </ol>
 *
 * <p>harness 仿 {@code LlmAgentLoopTaskBudgetCcTest}：mock LlmProvider 17-arg blocks stream
 * 捕获 arg(2)=blocks（发送边界产物），firstParty ProviderConfig 走真实 gate 链
 * （useGlobalCacheScope(params.config()) → SystemPromptAssembler 插入 boundary）。
 */
class LlmAgentLoopSkipGlobalCacheCcTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("G1: firstParty + MCP 工具 → split 模式 1（blocks 无 GLOBAL 块，claude.ts:1212-1214）")
    void queryLoop_withMcpTool_firstParty_blocksHaveNoGlobalScope() {
        // ── 1. provider：捕获 17-arg blocks stream 的 arg(2)=systemPromptBlocks ──
        List<SystemPromptBlock>[] capturedBlocks = new List[]{null};
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            capturedBlocks[0] = inv.getArgument(2);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. MCP 工具（name=mcp__ 前缀 · 对齐 CC t.isMcp===true 判定）──
        Tool mcpTool = new Tool() {
            @Override public String name() { return "mcp__demo__x"; }
            @Override public String description() { return "demo mcp tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
            }
        };
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of(mcpTool));
        AgentState state = new AgentState(null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.replaceMessages(List.of(message("m1", Role.user, "question")));
        AgentLoopContext ctx = agentLoopContext(factory);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        ProviderConfig firstParty = new ProviderConfig("https://api.anthropic.com", "sk-test");

        // ── 4. queryLoop（gate=true 经 params.config() 注入）──
        LoopResult result = LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null, tuc,
                QuerySource.USER, "test-model", null,
                null, null, null, null, deps, firstParty),
            state, new ArrayList<>());

        // ── 5. 断言 ──
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(capturedBlocks[0]).as("provider 必须收到 blocks 数组（blocks 重载发送）").isNotNull();
        assertThat(capturedBlocks[0])
            .as("MCP 工具存在 + firstParty → needsToolBasedCacheMarker=true → 模式 1 无 GLOBAL 块"
                + "（现恒 false → 模式 2 静态段 GLOBAL → 本断言 RED）")
            .noneMatch(b -> b.cacheScope() == CacheScope.GLOBAL);
    }

    @Test
    @DisplayName("G1 对照: firstParty + 无 MCP 工具 → 模式 2 保持（静态段 GLOBAL）")
    void queryLoop_noMcpTool_firstParty_globalScopePreserved() {
        List<SystemPromptBlock>[] capturedBlocks = new List[]{null};
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            capturedBlocks[0] = inv.getArgument(2);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // 无 MCP 工具（空工具集）
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of());

        AgentState state = new AgentState(null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.replaceMessages(List.of(message("m1", Role.user, "question")));
        AgentLoopContext ctx = agentLoopContext(factory);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        ProviderConfig firstParty = new ProviderConfig("https://api.anthropic.com", "sk-test");

        LoopResult result = LlmAgentLoop.queryLoop(
            QueryParams.forLoop(state.messages(), null, tuc,
                QuerySource.USER, "test-model", null,
                null, null, null, null, deps, firstParty),
            state, new ArrayList<>());

        assertThat(result.aborted()).isFalse();
        assertThat(capturedBlocks[0]).isNotNull();
        assertThat(capturedBlocks[0])
            .as("无 MCP 工具 → needsToolBasedCacheMarker=false → boundary 模式 2 保持（api.ts:368-397）")
            .anyMatch(b -> b.cacheScope() == CacheScope.GLOBAL);
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers（与 LlmAgentLoopTaskBudgetCcTest 同形）
    // ════════════════════════════════════════════════════════════════════

    private static AgentLoopContext agentLoopContext(LlmProviderFactory factory) {
        // 34 组件（DEL-03 删 findRelevant 后 35→34；H7-arch/H6-FIX/RV14B-WIRE-04/OPD-TS-22 各模块追加组件）
        return new AgentLoopContext(
            null, null,                                     // 1-2 toolRegistry/hookRegistry
            null, null, null, null, null, null, null, null, // 3-10 mcpServerService..queryConfig
            factory,                                        // 11 llmProviderFactory
            null, null, null, null, null, null, null, null, // 12-19 transientErrorHandler..streamUserMessageId
            FeatureFlags.ALL_DISABLED,                      // 20 featureFlags
            null, null, null, null, null, null, null, null, // 21-28 reactiveCompactor..permissionContextBuilder
            null, null, null, null, null, null, null, null);  // 29-34 promptSuggestion..sdkEventQueue · 35 queueEventPublisher · 36 modelCostCalculator（新增）
    }

    private static ChatMessageDto message(String id, Role role, String content) {
        return new ChatMessageDto(
            id, null, role, role.name(), content, null, List.of(),
            FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null,
            false, false, null);
    }
}
