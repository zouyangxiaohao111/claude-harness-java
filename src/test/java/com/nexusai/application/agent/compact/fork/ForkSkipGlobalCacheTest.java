package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.RunForkedAgent.ForkQueryParams;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP2-07 G1] fork 发送边界 needsToolBasedCacheMarker 等价物
 * （CC claude.ts:1212-1214 → claude.ts:1377 skipGlobalCacheForSystemPrompt 传参点）。
 *
 * <p><b>WHY 本测试存在（意图）</b>: fork 发送边界恒传 false 时，MCP 工具存在 +
 * useGlobalCacheScope=true 仍走 boundary 模式 2 → 静态段带 GLOBAL cache_control —— fork 与主线程
 * 发送边界语义偏离 CC（MCP 工具 per-user 动态段不可 global cache，claude.ts:1210-1214 注释）。
 * Java 无 tool-search（willDefer 恒 false）→ Java 等价物 = gate && 发送工具集存在 MCP 工具。
 *
 * <p>harness：ForkQueryParams 直构（systemPrompt 含 boundary + TUC 含 MCP 工具 +
 * useGlobalCacheScope=true + querySource=COMPACT）+ fake LlmProvider 捕获 17-arg blocks stream
 * 的 arg(2)=blocks → run 后断言无 GLOBAL 块。
 */
class ForkSkipGlobalCacheTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BOUNDARY = SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY;

    @Test
    @DisplayName("G1: fork gate=true + MCP 工具 → 模式 1（blocks 无 GLOBAL 块）")
    void fork_withMcpTool_blocksHaveNoGlobalScope() {
        List<SystemPromptBlock>[] capturedBlocks = new List[]{null};
        LlmProvider provider = fakeProvider(capturedBlocks);
        Tool mcpTool = new Tool() {
            @Override public String name() { return "mcp__demo__x"; }
            @Override public String description() { return "demo mcp tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
            }
        };
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(mcpTool), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of());

        ForkQueryParams params = new ForkQueryParams(
            List.of(userMessage("u1", "question")),
            List.of("static-part", BOUNDARY, "dynamic-part"),
            Map.of(), Map.of(),
            allowAll(), tuc,
            QuerySource.COMPACT, null, 3, true, true, null);

        QueryLoopForkedQuery query = forkQuery(provider);

        query.run(params);

        assertThat(capturedBlocks[0]).as("fake provider 必须收到 blocks 数组").isNotNull();
        assertThat(capturedBlocks[0])
            .as("MCP 工具 + gate=true → needsToolBasedCacheMarker=true → 模式 1 无 GLOBAL 块"
                + "（现恒 false → 模式 2 静态段 GLOBAL → 本断言 RED）")
            .noneMatch(b -> b.cacheScope() == CacheScope.GLOBAL);
    }

    @Test
    @DisplayName("G1 对照: fork gate=true + 无 MCP 工具 → 模式 2 保持（静态段 GLOBAL）")
    void fork_noMcpTool_globalScopePreserved() {
        List<SystemPromptBlock>[] capturedBlocks = new List[]{null};
        LlmProvider provider = fakeProvider(capturedBlocks);

        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of());

        ForkQueryParams params = new ForkQueryParams(
            List.of(userMessage("u1", "question")),
            List.of("static-part", BOUNDARY, "dynamic-part"),
            Map.of(), Map.of(),
            allowAll(), tuc,
            QuerySource.COMPACT, null, 3, true, true, null);

        QueryLoopForkedQuery query = forkQuery(provider);

        query.run(params);

        assertThat(capturedBlocks[0]).isNotNull();
        assertThat(capturedBlocks[0])
            .as("无 MCP 工具 → needsToolBasedCacheMarker=false → boundary 模式 2 保持（api.ts:368-397）")
            .anyMatch(b -> b.cacheScope() == CacheScope.GLOBAL);
    }

    /**
     * 放行任意工具的 canUseTool。
     *
     * <p>主循环对后台 fork 来源（COMPACT 等）强制要求注入受限 canUseTool
     * （{@code LlmAgentLoop.java:4393} INV-6 fail-loud）；本测试的关注点是
     * cache marker 判定，权限维度取「全放行」以隔离变量。
     */
    private static com.nexusai.application.agent.permission.hook.HookPermissionResolver.CanUseTool allowAll() {
        return (tool, input, cctx, toolUseId, forceDecision) ->
            com.nexusai.application.agent.permission.ToolPermissionGate.DecisionResult.allow();
    }

    /** 主循环 queryLoop 的 fork seam（E-2 后唯一实现）。factory 只需 llmProviderFactory。 */
    private static QueryLoopForkedQuery forkQuery(LlmProvider provider) {
        com.nexusai.infra.llm.LlmProviderFactory pf =
            org.mockito.Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class);
        org.mockito.Mockito.when(pf.getProvider(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(provider);
        org.mockito.Mockito.when(pf.getProvider(
            org.mockito.ArgumentMatchers.any())).thenReturn(provider);
        com.nexusai.application.agent.loop.AgentLoopContextFactory factory =
            new com.nexusai.application.agent.loop.AgentLoopContextFactory();
        try {
            java.lang.reflect.Field f =
                com.nexusai.application.agent.loop.AgentLoopContextFactory.class.getDeclaredField("llmProviderFactory");
            f.setAccessible(true);
            f.set(factory, pf);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new QueryLoopForkedQuery(
            full -> null, () -> "test-model",
            () -> new ProviderConfig("https://api.anthropic.com", "sk-test"), factory);
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** fake provider：捕获 17-arg blocks stream 的 arg(2) 并正常完成（无工具调用）。 */
    private static LlmProvider fakeProvider(List<SystemPromptBlock>[] capturedBlocks) {
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
                    com.nexusai.infra.llm.LlmCallScope scope,
                    com.nexusai.infra.llm.StreamIdleControl streamIdleControl) {
                capturedBlocks[0] = blocks;
                onChunk.accept("fork reply");
                onAssistant.accept(new AssistantMessage("fork reply", "stop", List.of()));
                onComplete.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "fork reply";
            }
        };
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}
