package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.memory.ExtractMemoriesAgent;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.permission.hook.StopHookPipeline;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [fork-agentid 守护] fork 的 {@code AgentState.agentId} 必须非空 · 且该身份喂 stop-hook 门会跳过。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：{@link QueryLoopForkedQuery} 造 fork 的 AgentState 时曾把
 * agentId 写成 {@code null}（依据 = forkedAgent.ts 里一个<b>同名不同物的局部变量</b>）。nexusai 的
 * stop-hook 提取/梦境门读的正是 {@code AgentState.agentId()}（{@code LlmAgentLoop:8570
 * stopMainAgentId → :8704 → StopHookPipeline:286 {@code agentId != null → 跳过}}）⇒ null 使门形同
 * 虚设：后台提取 fork 被当主线程，回合末再触发一次提取（提取 fork 生提取 fork），而失控轮里恒
 * 0 写入（「No memory updates needed」）⇒ 唯一收敛出口 INV-5 永不满足 ⇒ 永不收敛（用户真机实测：
 * 约每 2 秒一次真 API、持续 4~8 分钟）。
 *
 * <p><b>本测试钉两件事（都不跑真 LLM / 不走网络 / 不碰 DB）</b>：
 * <ol>
 *   <li><b>fork 构造出的 AgentState.agentId 非空</b>，且形态 = CC {@code uuid.ts:24-27} 的
 *       {@code a}+16hex（证明复用本仓既有生成器 {@link AgentContext#createAgentId}）。观测通道 =
 *       主循环每轮 messagesForLlm 定稿段的 SendMessage 待办 drain
 *       （{@code LlmAgentLoop:6828 registry.drain(state.agentId().toString())}）——
 *       {@code AgentState.agentId()} 是 fork 内部状态、无其它公开出口；drain 一旦被调用即证明
 *       agentId 非空（守卫就在方法首行 {@code state.agentId() == null → return}）。
 *       反证：把构造点改回 null ⇒ drain <b>根本不会被调用</b> ⇒ 本用例 RED（实测过）。</li>
 *   <li><b>该身份喂 stop hook 那道门会跳过</b>（{@code executeExtractMemoriesAndAutoDream} 返回
 *       false 且 extract agent 一次都没被调）—— 用上一步捕获的<b>真实 fork 身份</b>，把「身份非空」
 *       与「门真的挡住」钉成一条链。</li>
 * </ol>
 *
 * <p>provider 用 {@link CapturingProvider}（本地假 provider，把 responses 直接回调，
 * stream 不触网）。脚手架与 {@code ForkQueryLoopProviderBoundaryEquivalenceTest.Harness} 同形
 * （同一 fork 路径：{@code RunForkedAgent.run(params, queryLoop)}）。
 */
@DisplayName("[fork-agentid] fork 的 AgentState.agentId 非空（stop-hook 提取门唯一判据）")
class QueryLoopForkedAgentIdTest {

    private static final String MODEL_NAME = "deepseek-v4-flash";
    private static final String PROVIDER_ID = "7";
    private static final String BASE_URL = "http://localhost:8080/v1";   // 非 api.anthropic.com → gate 3P
    private static final String API_KEY = "sk-test-key";
    private static final String SESSION = "sess-fork-agentid";

    /** AgentNameRegistry 静态桥（{@code setAgentNameRegistry} 写 LlmAgentLoop 的 static volatile）。 */
    private LlmAgentLoop loopForRegistryBridge;

    @AfterEach
    void clearRegistryBridge() {
        // 全局静态桥必须复位（否则污染同 JVM 的其它测试类）
        if (loopForRegistryBridge != null) {
            loopForRegistryBridge.setAgentNameRegistry(null);
        }
    }

    @Test
    @DisplayName("提取 fork：AgentState.agentId 非空（a+16hex）· 该身份喂 stop-hook 门 → 跳过提取")
    void extractFork_agentStateAgentId_isNonNull_andGateSkips() {
        // ══ 1. 捕获 fork 的 AgentState.agentId（唯一非公开出口 = 每轮 drain SendMessage 待办）══
        AgentNameRegistry registry = mock(AgentNameRegistry.class);
        // 空队列 → drain 命中后立即返回（不进 coordinator 包裹链 ⇒ 断言面只有 agentId 本身）
        when(registry.drain(anyString())).thenReturn(List.of());
        loopForRegistryBridge = new LlmAgentLoop(mock(LlmProviderFactory.class));
        loopForRegistryBridge.setAgentNameRegistry(registry);

        RunForkedAgent.run(extractForkParams(), newQueryLoop());

        ArgumentCaptor<String> capturedId = ArgumentCaptor.forClass(String.class);
        verify(registry, atLeastOnce()).drain(capturedId.capture());
        String forkAgentId = capturedId.getValue();

        assertThat(forkAgentId)
            .as("fork 的 AgentState.agentId 必须非空：它是 stop-hook 提取/梦境门的唯一判据"
                + "（StopHookPipeline:286）。填 null ⇒ 提取 fork 走主线程分支 ⇒ 提取 fork 生提取 fork"
                + "且 0 写入下永不收敛（改回 null → 本断言 RED：drain 根本不会被调用）")
            .isNotBlank();

        assertThat(AgentContext.unpackAgentId(UUID.fromString(forkAgentId)))
            .as("身份必须来自本仓既有生成器 AgentContext.createAgentId（CC uuid.ts:24-27 的 a+16hex）"
                + "—— 不是新造一套、也不是随便一个 UUID")
            .matches("a[0-9a-f]{16}");

        // ══ 2. 该身份喂 stop hook 那道门 → 必须跳过（否则 fork 回合末再触发一次提取 = 自激）══
        ExtractMemoriesAgent extractAgent = mock(ExtractMemoriesAgent.class);
        boolean triggered = StopHookPipeline.executeExtractMemoriesAndAutoDream(
            forkAgentId,
            extractAgent,
            null,                       // dreamer 未注入
            List.of(),                  // messages 快照
            false,                      // isNonInteractiveSession
            null,                       // appendSystemMessage
            false,                      // bareMode
            null,                       // workspaceDir
            SESSION,                    // sessionId
            null,                       // forkRawMaterial
            null,                       // memoryDir
            null);                      // sessionCwd
        assertThat(triggered)
            .as("fork 上 extract/dream 阶段必须被跳过（StopHookPipeline:286 agentId != null → return false）")
            .isFalse();
        verify(extractAgent, never())
            .executeExtractMemories(any(), any(), any(), any(), any(), any(), any());
    }

    // ════════════════════════════════════════════════════════════════════
    // 脚手架（与 ForkQueryLoopProviderBoundaryEquivalenceTest.Harness 同形 · 只保留本用例所需）
    // ════════════════════════════════════════════════════════════════════

    /** 提取 fork 的生产参数形态（extractMemories.ts:415-427 → 本仓 ExtractMemoriesAgent）。 */
    private ForkedAgentParams extractForkParams() {
        return new ForkedAgentParams(
            List.of(userMessage("m1", "do the work")),
            new CacheSafeParams(
                List.of("You are a fork."),
                Map.of("currentDate", "2026-09-20"),
                Map.of("gitStatus", "clean"),
                baseTuc(),
                List.of(userMessage("f1", "hi")),
                false),
            canUseTool(),
            QuerySource.EXTRACT_MEMORIES, "extract_memories",
            null,               // maxOutputTokens（INV-7 恒 null）
            5,                  // maxTurns（CC maxTurns=5）
            true,               // skipTranscript（本仓不记 sidechain · D-E1a-01）
            false,              // skipCacheWrite
            null,               // abortController
            null);              // onMessage
    }

    /** 生产 seam（{@link QueryLoopForkedQuery}）· 主循环 queryLoop 路径。 */
    private QueryLoopForkedQuery newQueryLoop() {
        CapturingProvider provider = new CapturingProvider();
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        when(providerFactory.getProvider(any(), any())).thenReturn(provider);
        when(providerFactory.getProvider(any())).thenReturn(provider);
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        setField(contextFactory, "llmProviderFactory", providerFactory);
        setField(contextFactory, "modelConfigResolver", buildResolver());
        return new QueryLoopForkedQuery(
            modelFull -> new ProductionForkedQuery.ForkModelRoute(
                MODEL_NAME, new ProviderConfig(BASE_URL, API_KEY), provider, "openai_compatible"),
            () -> MODEL_NAME, () -> new ProviderConfig(BASE_URL, API_KEY), contextFactory);
    }

    /** 本地假 provider：直接回调一段纯文本收尾响应（stream 不触网）。 */
    private static final class CapturingProvider implements LlmProvider {
        @Override public String type() { return "openai_sdk"; }

        @Override public String chat(ProviderConfig config, String model, String sp, String userMessage) {
            return "captured";
        }

        @Override
        public void stream(ProviderConfig config, String model, List<SystemPromptBlock> blocks,
                           List<ChatMessageDto> history, ArrayNode tools, Integer maxOutputTokensOverride,
                           TaskBudgetParam taskBudget, String effortValue, String querySource,
                           Consumer<String> onChunk, Consumer<AssistantMessage> onAssistant,
                           Consumer<ToolUseBlock> onToolCallComplete, Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback, AbortController abortController,
                           Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite,
                           com.nexusai.application.agent.subagent.AgentContext agentContext) {
            onChunk.accept("no memory updates needed");
            onAssistant.accept(new AssistantMessage("no memory updates needed", "stop", List.of()));
            onComplete.run();
        }
    }

    /** 会话 ToolUseContext（effectiveModelName 已写 ⇒ fork 走会话模型直传分支 · 对齐生产）。 */
    private static ToolUseContext baseTuc() {
        return new ToolUseContext(
            UUID.randomUUID(), SESSION, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of())
            .withEffectiveModelName(MODEL_NAME);
    }

    /** 受限 canUseTool（提取 fork 形态；本用例无工具调用 ⇒ 不被触达）。 */
    private static HookPermissionResolver.CanUseTool canUseTool() {
        return (tool, input, ctx, toolUseId, forceDecision) ->
            com.nexusai.application.agent.permission.ToolPermissionGate.DecisionResult.deny(null);
    }

    /** 真实 ModelConfigResolver（反射注入 DB mock）· resolveProviderType / resolveSdkModelName 来源。 */
    private static ModelConfigResolver buildResolver() {
        ModelRecord rec = new ModelRecord();
        rec.setId("1");
        rec.setName(MODEL_NAME);
        rec.setProviderId(PROVIDER_ID);
        rec.setEnabled(true);
        ProviderRecord prov = new ProviderRecord();
        prov.setId(PROVIDER_ID);
        prov.setType("openai_compatible");
        prov.setBaseUrl(BASE_URL);
        prov.setEnabled(true);

        ModelMapper modelMapper = mock(ModelMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(rec));
        when(modelMapper.selectOneByQuery(any())).thenReturn(rec);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        when(providerMapper.selectOneById(any())).thenReturn(prov);
        when(providerMapper.selectOneByQuery(any())).thenReturn(prov);
        com.nexusai.domain.provider.ProviderService providerService =
            mock(com.nexusai.domain.provider.ProviderService.class);
        when(providerService.getDecryptedApiKey(any())).thenReturn(API_KEY);

        ModelConfigResolver r = new ModelConfigResolver();
        setField(r, "modelMapper", modelMapper);
        setField(r, "providerMapper", providerMapper);
        setField(r, "providerService", providerService);
        return r;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("测试字段注入失败: " + name, e);
        }
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }
}
