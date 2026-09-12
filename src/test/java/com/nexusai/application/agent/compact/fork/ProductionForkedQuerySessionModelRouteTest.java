package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.RunForkedAgent.ForkQueryParams;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [方案 A] fork 会话模型直传：forkCtx.effectiveModelName（父会话 mainLoopModel）经
 * sessionModelRouteResolver 覆盖全局 supplier —— 修跨 provider 重名误路由（fz/ark）。
 *
 * <p>两个意图：
 * <ol>
 *   <li>forkCtx 携带 effectiveModelName + resolver 可解析 → 用 route 的 provider/config/裸模型
 *       （不是 supplier 的全局模型）；</li>
 *   <li>无 effectiveModelName / resolver=null → 回落 supplier（旧行为，行为不变）。</li>
 * </ol>
 */
class ProductionForkedQuerySessionModelRouteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("方案 A: forkCtx 携带会话模型 → 走 resolver 路由（provider=route 的 provider、model=裸名）")
    void sessionModelRoute_overridesSupplier() {
        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        AtomicReference<String> usedModel = new AtomicReference<>(null);
        AtomicBoolean sessionProviderCalled = new AtomicBoolean(false);

        // supplier（旧路径）provider —— 不应被调用
        LlmProvider supplierProvider = capturingProvider(supplierCalled, usedModel);
        // resolver 路由 provider —— 应被调用
        LlmProvider routeProvider = capturingProvider(sessionProviderCalled, usedModel);
        ProviderConfig sessionCfg = new ProviderConfig("https://api.deepseek.com", "sk-session");

        Function<String, ProductionForkedQuery.ForkModelRoute> resolver = (full) ->
            "deepseek/deepseek-v4.1-flash-expires-on-0910".equals(full)
                ? new ProductionForkedQuery.ForkModelRoute("deepseek-v4.1-flash-expires-on-0910", sessionCfg,
                    routeProvider, "openai_compatible")
                : null;

        ToolUseContext tuc = baseTuc().withEffectiveModelName("deepseek/deepseek-v4.1-flash-expires-on-0910");
        ForkQueryParams params = new ForkQueryParams(
            List.of(userMessage("u1", "hi")), List.of("sys"), Map.of(), Map.of(),
            null, tuc, QuerySource.SESSION_MEMORY, null, null, false, false, null);

        ProductionForkedQuery query = new ProductionForkedQuery(
            () -> supplierProvider, () -> "global-model",
            () -> new ProviderConfig("https://api.ark.volces.com", "sk-global"),
            null, null, resolver);

        query.run(params);

        assertThat(supplierCalled).as("有会话模型路由时不得调用全局 supplier provider").isFalse();
        assertThat(sessionProviderCalled).as("必须调用 resolver 路由的 provider").isTrue();
        assertThat(usedModel.get()).as("发送名应为路由裸模型（去 provider 前缀）")
            .isEqualTo("deepseek-v4.1-flash-expires-on-0910");
    }

    @Test
    @DisplayName("方案 A 回落: 无 effectiveModelName → 走全局 supplier（旧行为不变）")
    void noSessionModel_fallsBackToSupplier() {
        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        AtomicReference<String> usedModel = new AtomicReference<>(null);
        LlmProvider supplierProvider = capturingProvider(supplierCalled, usedModel);
        AtomicBoolean sessionProviderCalled = new AtomicBoolean(false);
        LlmProvider routeProvider = capturingProvider(sessionProviderCalled, usedModel);

        Function<String, ProductionForkedQuery.ForkModelRoute> resolver = (full) -> null;

        ToolUseContext tuc = baseTuc();   // effectiveModelName = null
        ForkQueryParams params = new ForkQueryParams(
            List.of(userMessage("u1", "hi")), List.of("sys"), Map.of(), Map.of(),
            null, tuc, QuerySource.SESSION_MEMORY, null, null, false, false, null);

        ProductionForkedQuery query = new ProductionForkedQuery(
            () -> supplierProvider, () -> "global-model",
            () -> new ProviderConfig("https://api.deepseek.com", "sk-global"),
            null, null, resolver);

        query.run(params);

        assertThat(supplierCalled).as("无会话模型时回落全局 supplier").isTrue();
        assertThat(sessionProviderCalled).as("不得误用 resolver 路由 provider").isFalse();
        assertThat(usedModel.get()).isEqualTo("global-model");
    }

    // ── helpers ──

    /** fake provider：标记调用 + 记录 stream 收到的 model，回无工具调用文本即停。 */
    private static LlmProvider capturingProvider(AtomicBoolean called, AtomicReference<String> modelRef) {
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
                                         Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite) {
                called.set(true);
                modelRef.set(m);
                onAssistant.accept(new AssistantMessage("done", "stop", List.of()));
                onComplete.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "done";
            }
        };
    }

    private static ToolUseContext baseTuc() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, Map.of(), List.of(), "", AbortController.NOOP, List.of());
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}
