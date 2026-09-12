package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.ProductionForkedQuery;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
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
 * [SM-fork 模型直传回归] SessionMemory 提取 fork 的 toolUseContext 必须携带会话模型。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：生产事故 —— SM（SessionMemory）提取 fork 恒落
 * {@code MockLlmProvider}（日志 {@code model=null provider=MockLlmProvider}、假回复
 * "this is the mock final response (no real LLM in fallback mode)"、summary.md 冻结不前进）。
 * 根因：{@code doExtractSessionMemory}/{@code manuallyExtractSessionMemory} 把
 * <b>supplier 的</b> {@code toolUseContext} 原样塞进 {@code CacheSafeParams} —— 该上下文由
 * {@code buildProductionCacheSafeParams} 用 8 参构造（{@code effectiveModelName} 缺省 null），
 * 于是 {@code ProductionForkedQuery} 的「会话模型直传」分支（:239-256）读不到会话模型 →
 * 回落全局 supplier（settings.json 无 model → null → ProviderConfig.empty() → Mock）。
 *
 * <p>CC 真源：{@code createCacheSafeParams(context)} 直接取 {@code context.toolUseContext}
 * （forkedAgent.ts:131-141），该上下文携带 {@code options.mainLoopModel}
 * （sessionMemory.ts:411-418 {@code toolUseContext.options} 取 mainLoopModel 组装 systemPrompt）——
 * 即 CC 的 fork 上下文<b>同时</b>携带工具集与会话模型，Java 侧两个来源（supplier 工具集 +
 * psContext 会话模型）必须在传入 fork 前合并。
 *
 * <p>本测试从 {@link SessionMemoryService#extractSessionMemory} 入口起（<b>不</b>直接注入
 * forkCtx —— 那正是既有 {@code ProductionForkedQuerySessionModelRouteTest} 测不出本 bug 的原因），
 * 断言真正传给 fork 的 toolUseContext 带非空 effectiveModelName，且 supplier 工具集不丢。
 */
@DisplayName("[SM-fork 模型直传] 提取 fork 的 toolUseContext 携带会话模型（修 MockLlmProvider 恒落）")
class SessionMemoryForkSessionModelTest {

    /** 会话模型全名（provider 全名形态，生产 sessions.model_name 实测值）。 */
    private static final String SESSION_MODEL = "deepseek/deepseek-v4.1-flash-expires-on-0910";

    private static final String SESSION_ID = "sess-smfork-model";

    @TempDir
    Path baseDir;

    @AfterEach
    void tearDown() {
        SessionMemoryUtils.resetSessionMemoryState();
        SessionMemoryService.setLastSummarizedMessageId(SESSION_ID, null);
        SessionMemoryService.resetLastMemoryMessageUuid();
    }

    /** [G-41] 读取通道注入（ReadFileTool 权限层读取；guard 与 baseDir 同源保证 key 派生一致）。 */
    private ReadFileTool readFileTool() {
        return new ReadFileTool(PathGuard.of(baseDir.toString()));
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · supplier 分支（生产形态）: 会话模型合并进 fork toolUseContext
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("生产 supplier 分支: psContext 会话模型必须写入 fork toolUseContext（supplier 工具集保留）")
    void extractSessionMemory_forksWithSessionModelOnToolUseContext() {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(readFileTool());

        // 生产 supplier 形态（ToolRegistrationConfig.buildProductionCacheSafeParams 等价）:
        //   8 参 ToolUseContext 构造 → effectiveModelName 缺省 null，availableTools = 主线程工具集
        Tool editTool = Mockito.mock(Tool.class);
        Mockito.when(editTool.name()).thenReturn("Edit");
        ToolUseContext supplierCtx = ctx(List.of(editTool), null);
        svc.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of(), Map.of(), Map.of(), supplierCtx, List.of(), false));

        // 会话上下文（LlmAgentLoop hookToolUseContext 等价）: 带本轮真实模型
        ToolUseContext sessionCtx = ctx(List.of(), SESSION_MODEL);

        svc.extractSessionMemory(new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of("SYS"), Map.of(), Map.of(),
            sessionCtx, QuerySource.REPL_MAIN_THREAD));

        assertThat(query.captured).as("阈值满足 → 必须发起 fork").isNotNull();
        assertThat(query.captured.toolUseContext().effectiveModelName())
            .as("fork toolUseContext 必须携带会话模型（= CC toolUseContext.options.mainLoopModel，"
                + "sessionMemory.ts:411-418）—— 否则 ProductionForkedQuery 会话模型直传取不到模型 → "
                + "model=null → provider 回落 MockLlmProvider（假回复 / 永不 Edit）")
            .isEqualTo(SESSION_MODEL);
        assertThat(query.captured.toolUseContext().availableTools())
            .as("supplier 真实工具集必须保留（buildProductionCacheSafeParams 唯一有效载荷，"
                + "fork 无工具 → 提取 fork 不能 Edit/Write）")
            .containsExactly(editTool);
    }

    @Test
    @DisplayName("会话模型为空: 不造值（supplier toolUseContext 原样，回落既有全局 supplier 语义）")
    void extractSessionMemory_noSessionModel_keepsSupplierContext() {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(readFileTool());

        ToolUseContext supplierCtx = ctx(List.of(), null);
        svc.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of(), Map.of(), Map.of(), supplierCtx, List.of(), false));

        svc.extractSessionMemory(new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of("SYS"), Map.of(), Map.of(),
            ctx(List.of(), null), QuerySource.REPL_MAIN_THREAD));

        assertThat(query.captured).isNotNull();
        assertThat(query.captured.toolUseContext().effectiveModelName())
            .as("会话模型缺省 → 不伪造模型名（保持 null，走既有全局 supplier 回落语义）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · 端到端: SM 服务 → 真 ProductionForkedQuery → 必须路由到会话模型的 provider
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("端到端: SM 提取 fork 走会话模型路由的 provider（不得落全局 supplier / mock）")
    void extractSessionMemory_routesToSessionModelProvider() {
        AtomicBoolean supplierProviderCalled = new AtomicBoolean(false);
        AtomicBoolean sessionProviderCalled = new AtomicBoolean(false);
        AtomicReference<String> usedModel = new AtomicReference<>(null);

        LlmProvider supplierProvider = capturingProvider(supplierProviderCalled, usedModel);
        LlmProvider sessionProvider = capturingProvider(sessionProviderCalled, usedModel);
        ProviderConfig sessionCfg = new ProviderConfig("https://api.deepseek.com", "sk-session");

        Function<String, ProductionForkedQuery.ForkModelRoute> resolver = (full) ->
            SESSION_MODEL.equals(full)
                ? new ProductionForkedQuery.ForkModelRoute(
                    "deepseek-v4.1-flash-expires-on-0910", sessionCfg, sessionProvider, "openai_compatible")
                : null;

        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(readFileTool());
        // 生产 seam 形态: SM 持有真 ProductionForkedQuery（非 RecordingQuery —— 后者绕过 TUC 取值）
        svc.setForkedQuery(new ProductionForkedQuery(
            () -> supplierProvider, () -> "global-model",
            () -> new ProviderConfig("https://api.ark.volces.com", "sk-global"),
            null, null, resolver));
        svc.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of(), Map.of(), Map.of(), ctx(List.of(), null), List.of(), false));

        svc.extractSessionMemory(new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of("SYS"), Map.of(), Map.of(),
            ctx(List.of(), SESSION_MODEL), QuerySource.REPL_MAIN_THREAD));

        assertThat(sessionProviderCalled)
            .as("fork 必须用会话模型路由的 provider（会话模型的 baseUrl/key）")
            .isTrue();
        assertThat(supplierProviderCalled)
            .as("有会话模型时不得回落全局 supplier provider（settings 无 model → mock 假回复）")
            .isFalse();
        assertThat(usedModel.get()).isEqualTo("deepseek-v4.1-flash-expires-on-0910");
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 会话/供应器 ToolUseContext（sessionId 固定便于清理；modelName=null → effectiveModelName 缺省）。 */
    private static ToolUseContext ctx(List<Tool> tools, String modelName) {
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), SESSION_ID, PermissionMode.DEFAULT, Map.of(),
            tools, "", AbortController.NOOP, List.of());
        return modelName != null ? tuc.withEffectiveModelName(modelName) : tuc;
    }

    private static ChatMessageDto asst(String id, int inputTokens, List<com.nexusai.model.session.dto.ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, null, Role.assistant, "assistant", "ok", null,
            toolCalls, FinishReason.stop, inputTokens, 0, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

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
                                         AbortController abort, Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite) {
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

    /** 捕获 fork 参数的 RecordingQuery（对齐 SessionMemoryExtractionPipelineTest 模式）。 */
    static class RecordingQuery implements RunForkedAgent.ForkedQuery {
        RunForkedAgent.ForkQueryParams captured;

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            this.captured = params;
            return new ForkedAgentResult(new ArrayList<>(), ForkedAgentResult.ForkUsage.empty());
        }
    }
}
