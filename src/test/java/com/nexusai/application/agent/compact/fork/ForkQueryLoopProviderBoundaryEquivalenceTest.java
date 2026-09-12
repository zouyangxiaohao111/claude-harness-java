package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [E-1b-2] fork 收敛到主循环 {@code queryLoop} 的 <b>provider 边界等价验证</b>。
 *
 * <p><b>WHY 本测试存在（规则 9 · 意图）</b>：E-1b-2 把 6 个 fork 入口的 seam 实现从自建循环
 * {@link ProductionForkedQuery} 换成 {@link QueryLoopForkedQuery}（走 {@code LlmAgentLoop.queryLoop}）。
 * seam 与 {@code ForkQueryParams} 仍在 ⇒ {@code ForkConvergenceCcContractTest} 只证明「seam 收到的
 * 透传参数不变」，<b>证明不了「到达 provider 边界的字节不变」</b>（主循环中途还要做
 * prependUserContext / appendSystemContext / splitSysPromptPrefix / 工具数组适配 / 模型剥前缀）。
 * 本测试把**同一个** {@code ForkedAgentParams} 分别喂给两条 seam，逐字段比对
 * {@code LlmProvider.stream} 实收入参：
 * <ul>
 *   <li>messages（含 userContext 恰前置一次）</li>
 *   <li>systemPromptBlocks（pre-append → appendSystemContext → splitSysPromptPrefix 产物）</li>
 *   <li>tools / maxOutputTokensOverride（INV-7 恒 null）/ skipCacheWrite / querySource</li>
 *   <li>abortController（Esc 可中断 fork）</li>
 *   <li>model + config（= 路由目标：防「静默落全局 settings 模型 → MockLlmProvider 假回复」）</li>
 * </ul>
 *
 * <p><b>等价成立的前提（重要，别过度外推）</b>：断言面 = 本 harness 的 ctx（只接线
 * {@code llmProviderFactory} + {@code modelConfigResolver}）。主循环的<b>主会话专属注入</b>在
 * 完整装配的 ctx 下会对后台 fork 也生效（E-1a 未加门的 3 处；本测试 §5 实测了「相关记忆预取」
 * 确实注入）—— 该维度新旧路径<b>不等价</b>，已登记（E-1b-2 条目 R11），不擅自加门。
 *
 * <p><b>RED teeth（逐条独立变异实测过）</b>：去掉会话模型直传 ⇒ model/config 不等价；
 * 去掉 {@code producedMessages} 切片 ⇒ 产出面把 initialMessages 一并带回（下游摘要取错消息）；
 * 去掉 {@code withOnMessage} ⇒ watcher 收不到 assistant(tool_calls)；
 * 去掉 {@code state.maxTurns(...)} ⇒ maxTurns 不落地，provider 多跑一轮。
 */
@DisplayName("[E-1b-2] fork 走主循环：provider 边界与旧自建循环逐字段等价")
class ForkQueryLoopProviderBoundaryEquivalenceTest {

    /** 会话模型（DB models.name 裸名）；全名形态见 §会话模型直传 用例。 */
    private static final String MODEL_NAME = "deepseek-v4-flash";
    private static final String PROVIDER_ID = "7";
    private static final String BASE_URL = "http://localhost:8080/v1";   // 非 api.anthropic.com → gate 3P
    private static final String API_KEY = "sk-test-key";

    // ════════════════════════════════════════════════════════════════════
    // 1. 6 个 fork 入口 · 逐入口 provider 边界等价
    // ════════════════════════════════════════════════════════════════════

    /** 一个 fork 入口的边界参数形态（取值 = 各生产调用点实际值）。 */
    private record ForkEntry(String name, QuerySource querySource, Integer maxTurns,
                             boolean skipCacheWrite, boolean withAbort, boolean denyCanUseTool) {}

    /**
     * 6 个 fork 入口 · 参数形态取自各生产调用点：
     * <ol>
     *   <li>StreamCompactSummary（compact.ts:1188-1200）：maxTurns=1 · skipCacheWrite=true · abort=有 · deny</li>
     *   <li>ExtractMemoriesAgent（extractMemories.ts:415-427）：maxTurns=5 · abort=null · 受限</li>
     *   <li>AutoDreamConsolidator 自动（autoDream.ts:224-233）：maxTurns=null · abort=有 · 受限</li>
     *   <li>AutoDreamConsolidator 手动（doDream）：同上（无 DreamTask）</li>
     *   <li>SessionMemoryService 提取（sessionMemory.ts:318-325）：maxTurns=null · abort=null · 受限</li>
     *   <li>SessionMemoryService 手动（manuallyExtractSessionMemory:420-433）：同 5</li>
     * </ol>
     */
    private static List<ForkEntry> sixForkEntries() {
        return List.of(
            new ForkEntry("StreamCompactSummary", QuerySource.COMPACT, 1, true, true, true),
            new ForkEntry("ExtractMemoriesAgent", QuerySource.EXTRACT_MEMORIES, 5, false, false, true),
            new ForkEntry("AutoDreamConsolidator(auto)", QuerySource.AUTO_DREAM, null, false, true, true),
            new ForkEntry("AutoDreamConsolidator(manual)", QuerySource.AUTO_DREAM, null, false, true, true),
            new ForkEntry("SessionMemoryService(extract)", QuerySource.SESSION_MEMORY, null, false, false, true),
            new ForkEntry("SessionMemoryService(manual)", QuerySource.SESSION_MEMORY, null, false, false, true));
    }

    @Test
    @DisplayName("6 个 fork 入口：新路径(queryLoop) 与旧路径(ProductionForkedQuery) provider 边界逐字段相同")
    void sixForkEntries_providerBoundary_newPathEqualsOldPath() {
        for (ForkEntry entry : sixForkEntries()) {
            Harness h = new Harness();
            ForkedAgentParams params = h.params(entry);

            Harness.StreamCapture oldCap = h.runWithProductionPath(params);
            Harness.StreamCapture newCap = h.runWithQueryLoopPath(params);
            String tag = "[" + entry.name() + "] ";

            assertThat(normalizeMessages(newCap.messages()))
                .as(tag + "messages 序列（role/content/toolCalls 逐条 · userContext 恰前置一次）")
                .isEqualTo(normalizeMessages(oldCap.messages()));
            assertThat(newCap.blocks()).as(tag + "systemPromptBlocks（appendSystemContext + boundary 剥离产物）")
                .usingRecursiveComparison().isEqualTo(oldCap.blocks());
            assertThat(newCap.tools()).as(tag + "tools 数组（cache-safe 工具集）")
                .isEqualTo(oldCap.tools());
            assertThat(newCap.maxOutputTokensOverride()).as(tag + "maxOutputTokensOverride（INV-7 恒 null）")
                .isEqualTo(oldCap.maxOutputTokensOverride()).isNull();
            assertThat(newCap.skipCacheWrite()).as(tag + "skipCacheWrite").isEqualTo(oldCap.skipCacheWrite());
            assertThat(newCap.querySource()).as(tag + "querySource 精确值").isEqualTo(oldCap.querySource());
            assertThat(newCap.model()).as(tag + "SDK 发送模型（裸名）").isEqualTo(oldCap.model());
            assertThat(newCap.config()).as(tag + "provider 配置（baseUrl + apiKey = 路由目标）")
                .isEqualTo(oldCap.config());
            if (entry.withAbort()) {
                assertThat(newCap.abortController()).as(tag + "abortController 同一性（Esc 可中断 fork）")
                    .isSameAs(params.abortController());
                assertThat(oldCap.abortController()).isSameAs(params.abortController());
            } else {
                assertThat(newCap.abortController()).as(tag + "abort 缺省 → 继承隔离上下文控制器（非 null）")
                    .isNotNull();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 会话模型直传（风险 1：静默落全局 settings 模型 = Mock 假回复/永不 Edit）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("会话模型直传：fork 用 effectiveModelName 的路由，而非全局 supplier 的 gpt-4/global baseUrl")
    void sessionModelDirectPass_winsOverGlobalSupplier() {
        Harness h = new Harness();
        h.fallbackModel = "gpt-4";                                                  // 全局回落（若直传丢失则会用它）
        h.fallbackConfig = new ProviderConfig("http://global-default.example/v1", "sk-global");

        Harness.StreamCapture cap = h.runWithQueryLoopPath(h.params(sixForkEntries().get(1)));

        assertThat(cap.model()).as("必须用会话模型发送，而非全局 settings 模型").isEqualTo(MODEL_NAME);
        assertThat(cap.config().baseUrl()).as("必须用会话模型对应 provider 的 baseUrl").isEqualTo(BASE_URL);
        assertThat(cap.config().apiKey()).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("会话模型为全名（provider/model）时：SDK 发送名剥为裸名，且新旧路径逐字节一致")
    void sessionModelFullName_strippedForSdk_identicalToOldPath() {
        Harness h = new Harness();
        h.sessionModelName = "deepseek/" + MODEL_NAME;    // 前端 ModelPickerModal fullName 形态

        ForkEntry entry = sixForkEntries().get(0);
        Harness.StreamCapture oldCap = h.runWithProductionPath(h.params(entry));
        Harness.StreamCapture newCap = h.runWithQueryLoopPath(h.params(entry));

        assertThat(newCap.model()).as("SDK 实收裸名（全名直接下发会 400）").isEqualTo(MODEL_NAME);
        assertThat(newCap.model()).as("新旧路径发送名相同").isEqualTo(oldCap.model());
        assertThat(newCap.config()).as("新旧路径 config 相同（同 provider 解密 key）").isEqualTo(oldCap.config());
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 产出面切片（风险 3）+ usage 多轮累加（风险 2）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("产出面切片：ForkedAgentResult.messages 不含 initialMessages（摘要取末条 assistant 依赖它）")
    void producedMessages_sliceExcludesInitialMessages() {
        Harness h = new Harness();
        ForkEntry entry = sixForkEntries().get(0);
        ForkedAgentParams params = h.params(entry);

        ForkedAgentResult r = h.runQueryLoop(params);

        assertThat(r.messages()).as("只含 fork 自己产出的消息（父前缀 + promptMessages 已切片剔除）")
            .hasSize(1);
        assertThat(r.messages().get(0).role()).isEqualTo(Role.assistant);
        assertThat(r.messages()).as("切片必须排除 initialMessages")
            .extracting(ChatMessageDto::id)
            .doesNotContain(params.cacheSafeParams().forkContextMessages().get(0).id(),
                params.promptMessages().get(0).id());
    }

    @Test
    @DisplayName("usage 多轮累加：两轮 usage 之和（末轮单轮值 → RED）")
    void usage_accumulatesAcrossTurns_notLastTurnOnly() {
        Harness h = new Harness();
        h.emitToolCallOnce = true;                                            // 第一轮带工具 → 第二轮纯文本
        h.toolCallTurnUsage = usage(100L, 10L, 5L, 7L);
        h.plainTurnUsage = usage(200L, 20L, 50L, 70L);

        ForkedAgentResult r = h.runQueryLoop(h.params(sixForkEntries().get(1)));

        assertThat(r.totalUsage().outputTokens()).as("两轮累加 = 30；只取末轮 = 20 → RED").isEqualTo(30L);
        assertThat(r.totalUsage().inputTokens()).isEqualTo(300L);
        assertThat(r.totalUsage().cacheReadInputTokens()).as("7 + 70").isEqualTo(77L);
        assertThat(r.totalUsage().cacheCreationInputTokens()).as("5 + 50").isEqualTo(55L);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. canUseTool deny（INV-6）· onMessage（风险 6）· 屏蔽档（风险 7）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("canUseTool deny：新旧路径都产 isError=true 的 tool_result（权限受限语义不丢）")
    void canUseToolDeny_producesErrorToolResult_bothPaths() {
        Harness h = new Harness();
        h.emitToolCallOnce = true;
        ForkEntry entry = sixForkEntries().get(0);      // compact：canUseTool 恒 deny

        ForkedAgentResult oldResult = h.runProduction(h.params(entry));
        ForkedAgentResult newResult = h.runQueryLoop(h.params(entry));

        ChatMessageDto newToolResult = toolResult(newResult);
        assertThat(newToolResult).as("新路径：deny 仍产出 tool_result（被拒也要回灌模型，否则 tool_calls 悬空）")
            .isNotNull();
        assertThat(newToolResult.isError())
            .as("新路径：deny → isError=true（主循环内层权限门显式标记；旧路径经 isToolErrorData "
                + "'Permission denied' 前缀同样为 true ⇒ 等价）").isTrue();
        assertThat(newToolResult.toolCallId()).isEqualTo("t1");
        assertThat(toolResult(oldResult)).as("旧路径同样回灌 tool_result").isNotNull();
        assertThat(toolResult(oldResult).isError()).as("旧路径 isError=true").isTrue();
    }

    @Test
    @DisplayName("onMessage：新路径逐条回调 assistant(tool_calls) + tool_result，arguments 带 file_path")
    void onMessage_newPathEmitsAssistantToolCalls_withArgumentsJson() {
        Harness h = new Harness();
        h.emitToolCallOnce = true;
        List<ChatMessageDto> seen = new ArrayList<>();

        ForkedAgentParams params =
            h.params(sixForkEntries().get(2), seen::add);      // auto-dream（生产注入 watcher）
        h.runQueryLoop(params);

        assertThat(seen).as("onMessage 至少收到 assistant(tool_calls) 与 tool_result 两类").isNotEmpty();
        ChatMessageDto withCalls = seen.stream()
            .filter(m -> m.role() == Role.assistant && m.toolCalls() != null && !m.toolCalls().isEmpty())
            .findFirst().orElse(null);
        assertThat(withCalls).as("dream watcher 靠 assistant(tool_calls) 收集 touchedPaths").isNotNull();
        assertThat(withCalls.toolCalls().get(0).arguments())
            .as("[AD-02] watcher 从 arguments JSON 取 file_path → 必须是 JSON 串")
            .isNotNull().contains("file_path");
    }

    @Test
    @DisplayName("maxTurns 落地在 state：compact（maxTurns=1）带工具调用 → provider 只被调 1 次")
    void maxTurns_landsOnState_loopStopsAtLimit() {
        Harness h = new Harness();
        h.emitToolCallOnce = true;                     // 第 1 轮有工具 → 本可继续第 2 轮
        ForkEntry entry = sixForkEntries().get(0);     // StreamCompactSummary：maxTurns=1

        ForkedAgentResult r = h.runQueryLoop(h.params(entry));

        assertThat(h.provider.captures).as("maxTurns=1 ⇒ provider 边界只被触达 1 次（未落地则 2 次 → RED）")
            .hasSize(1);
        assertThat(r.messages()).as("maxTurns 截断点产物（主循环轮末判 + max_turns_reached 注入，"
                + "与旧循环「超限前 break」的消息集合不同 —— 见登记处 E-1b-2 条目 R8）")
            .isNotEmpty();
    }

    @Test
    @DisplayName("后台 fork 来源走真 seam：正常跑完（屏蔽档不改写 exit/finish 语义，E-1a 门覆盖见 E1aForkShieldGateTest）")
    void backgroundForkSource_runsThroughRealSeam_withoutShieldInterference() {
        Harness h = new Harness();
        ForkEntry entry = sixForkEntries().get(4);          // session_memory

        ForkedAgentResult r = h.runQueryLoop(h.params(entry));

        assertThat(r.messages()).as("后台 fork 走主循环正常产出（屏蔽档只压主会话副作用，不压 fork 本身）")
            .hasSize(1);
        assertThat(h.provider.captures).as("provider 边界被真实触达（非空跑）").hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. 风险 11 实测：E-1a 未加门的注入点（相关记忆预取）对 fork 是否污染
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[风险 11 实测] 接线 memoryPrefetcher 后：主循环为 fork 启动相关记忆预取并注入（旧自建循环不会）")
    void memoryPrefetch_injectsForFork_onNewPathOnly_measured() {
        Harness h = new Harness();
        h.wireMemoryPrefetch();
        ForkEntry entry = sixForkEntries().get(1);      // extract-memories
        ForkedAgentParams params = h.params(entry);

        Harness.StreamCapture oldCap = h.runWithProductionPath(params);
        Harness.StreamCapture newCap = h.runWithQueryLoopPath(params);

        assertThat(relevantMemoryCount(newCap.messages()))
            .as("新路径（主循环）为后台 fork 也启动了 relevant-memory 预取并注入 isMeta user 消息")
            .isEqualTo(1);
        assertThat(relevantMemoryCount(oldCap.messages()))
            .as("旧自建循环从不注入 —— 该维度上新旧并不等价（已登记 E-1b-2 条目 R11，不擅自加门）")
            .isZero();
    }

    private static int relevantMemoryCount(List<ChatMessageDto> messages) {
        if (messages == null) {
            return 0;
        }
        int n = 0;
        for (ChatMessageDto m : messages) {
            if (m.content() != null && m.content().contains("Relevant memory:")) {
                n++;
            }
        }
        return n;
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试脚手架
    // ════════════════════════════════════════════════════════════════════

    private static AgentUsage usage(long input, long output, long cacheCreate, long cacheRead) {
        return new AgentUsage(input, output, cacheCreate, cacheRead, null, null, null);
    }

    /** 一次 fork 运行的全部装置（两条 seam 共享同一 provider / config / model / DB mock）。 */
    private static final class Harness {

        /** provider 边界捕获（一次 stream 调用）。 */
        record StreamCapture(List<ChatMessageDto> messages, List<SystemPromptBlock> blocks, ArrayNode tools,
                             Integer maxOutputTokensOverride, Boolean skipCacheWrite,
                             AbortController abortController, String querySource,
                             String model, ProviderConfig config) { }

        final CapturingProvider provider = new CapturingProvider();
        private int streamCalls = 0;

        String sessionModelName = MODEL_NAME;
        String fallbackModel = MODEL_NAME;
        ProviderConfig fallbackConfig = new ProviderConfig(BASE_URL, API_KEY);

        boolean emitToolCallOnce = false;
        AgentUsage toolCallTurnUsage = null;
        AgentUsage plainTurnUsage = null;

        private final List<ChatMessageDto> forkContextMessages =
            List.of(userMessage("m1", "hi"), userMessage("m2", "go"));
        private final List<ChatMessageDto> promptMessages = List.of(userMessage("m3", "do the work"));

        private final LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        private final AgentLoopContextFactory contextFactory;

        private final ProductionForkedQuery production;
        private final QueryLoopForkedQuery queryLoop;

        Harness() {
            when(providerFactory.getProvider(any(), any())).thenReturn(provider);
            when(providerFactory.getProvider(any())).thenReturn(provider);
            contextFactory = buildContextFactory(buildResolver());
            production = new ProductionForkedQuery(
                () -> provider, () -> fallbackModel, () -> fallbackConfig,
                com.nexusai.application.agent.tool.ToolRegistry.from(List.of(writeTool())),
                null, sessionModelRoute());
            queryLoop = new QueryLoopForkedQuery(
                sessionModelRoute(), () -> fallbackModel, () -> fallbackConfig, contextFactory);
        }

        final class CapturingProvider implements LlmProvider {
            final List<StreamCapture> captures = new ArrayList<>();

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
                               Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite) {
                int call = ++streamCalls;
                captures.add(new StreamCapture(List.copyOf(history),
                    blocks == null ? null : List.copyOf(blocks), tools, maxOutputTokensOverride,
                    skipCacheWrite, abortController, querySource, model, config));
                if (emitToolCallOnce && call == 1) {
                    ToolUseBlock call1 = toolCall("t1", "Write", "{\"file_path\":\"/mem/a.md\"}");
                    onToolCallComplete.accept(call1);
                    onAssistant.accept(new AssistantMessage("", "tool_use", List.of(call1),
                        null, null, toolCallTurnUsage, null));
                } else {
                    onChunk.accept("final answer");
                    onAssistant.accept(new AssistantMessage("final answer", "stop", List.of(),
                        null, null, plainTurnUsage, null));
                }
                onComplete.run();
            }
        }

        // ── 会话模型直传 resolver（与 ToolRegistrationConfig.sessionForkModelRoute 同语义）──

        private java.util.function.Function<String, ProductionForkedQuery.ForkModelRoute> sessionModelRoute() {
            return modelFull -> new ProductionForkedQuery.ForkModelRoute(
                MODEL_NAME, new ProviderConfig(BASE_URL, API_KEY), provider, "openai_compatible");
        }

        private static ModelRecord enabledModel() {
            ModelRecord rec = new ModelRecord();
            rec.setId("1");
            rec.setName(MODEL_NAME);
            rec.setProviderId(PROVIDER_ID);
            rec.setEnabled(true);
            return rec;
        }

        private static ProviderRecord enabledProvider() {
            ProviderRecord prov = new ProviderRecord();
            prov.setId(PROVIDER_ID);
            prov.setType("openai_compatible");
            prov.setBaseUrl(BASE_URL);
            prov.setEnabled(true);
            return prov;
        }

        /** 真实 ModelConfigResolver（反射注入 DB mock）· resolveSdkModelName / resolveProviderType 来源。 */
        private static ModelConfigResolver buildResolver() {
            ModelRecord rec = enabledModel();
            ProviderRecord prov = enabledProvider();
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

        private AgentLoopContextFactory buildContextFactory(ModelConfigResolver resolver) {
            AgentLoopContextFactory f = new AgentLoopContextFactory();
            setField(f, "llmProviderFactory", providerFactory);
            setField(f, "modelConfigResolver", resolver);
            return f;
        }

        /** [风险 11 实测] 接线 memoryPrefetcher：一条已 settled 的相关记忆 attachment。 */
        void wireMemoryPrefetch() {
            com.nexusai.application.agent.memory.MemoryPrefetcher prefetcher =
                mock(com.nexusai.application.agent.memory.MemoryPrefetcher.class);
            com.nexusai.application.agent.memory.MemoryPrefetcher.RelevantMemoryAttachment attachment =
                new com.nexusai.application.agent.memory.MemoryPrefetcher.RelevantMemoryAttachment(
                    "/mem/a.md", "remember X", 1L, "Relevant memory: /mem/a.md", null);
            com.nexusai.application.agent.memory.MemoryPrefetcher.MemoryPrefetch prefetch =
                new com.nexusai.application.agent.memory.MemoryPrefetcher.MemoryPrefetch(
                    java.util.concurrent.CompletableFuture.completedFuture(List.of(attachment)), null);
            prefetch.settledAt = 1L;
            when(prefetcher.startPrefetch(any(), any(), any())).thenReturn(prefetch);
            when(prefetcher.filterDuplicateMemoryAttachments(any(), any())).thenReturn(List.of(attachment));
            setField(contextFactory, "memoryPrefetcher", prefetcher);
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

        // ── params ──

        ForkedAgentParams params(ForkEntry entry) {
            return params(entry, null);
        }

        ForkedAgentParams params(ForkEntry entry, Consumer<ChatMessageDto> onMessage) {
            AbortController abort = entry.withAbort() ? new AbortController() : null;
            return new ForkedAgentParams(
                promptMessages,
                new CacheSafeParams(systemPromptPreAppend(), Map.of("currentDate", "2026-09-13"),
                    Map.of("gitStatus", "clean"), baseTuc(), forkContextMessages, false),
                entry.denyCanUseTool() ? denyCanUseTool() : null,
                entry.querySource(), entry.querySource().canonical(),
                null,                       // maxOutputTokens（INV-7 恒 null）
                entry.maxTurns(),
                false,                      // skipTranscript（本仓不记 sidechain · D-E1a-01）
                entry.skipCacheWrite(),
                abort,
                onMessage);
        }

        private ToolUseContext baseTuc() {
            ToolUseContext tuc = new ToolUseContext(
                UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
                PermissionMode.DEFAULT, Map.of(), List.of(), "", AbortController.NOOP, List.of());
            return tuc.withAvailableTools(List.of(writeTool())).withEffectiveModelName(sessionModelName);
        }

        private static List<String> systemPromptPreAppend() {
            return List.of("You are a fork.", "SYSTEM_PROMPT_BOUNDARY_TOKEN");
        }

        // ── 两条路径 ──

        RunForkedAgent.ForkedQuery productionSeam() { return production; }

        RunForkedAgent.ForkedQuery queryLoopSeam() { return queryLoop; }

        StreamCapture runWithProductionPath(ForkedAgentParams params) {
            runProduction(params);
            return provider.captures.get(provider.captures.size() - 1);
        }

        StreamCapture runWithQueryLoopPath(ForkedAgentParams params) {
            runQueryLoop(params);
            return provider.captures.get(provider.captures.size() - 1);
        }

        /** 跑旧路径（每跑一次重置流计数，保证两路径看到相同的「第 N 轮」形态）。 */
        ForkedAgentResult runProduction(ForkedAgentParams params) {
            reset();
            return RunForkedAgent.run(params, production);
        }

        /** 跑新路径（每跑一次重置流计数）。 */
        ForkedAgentResult runQueryLoop(ForkedAgentParams params) {
            reset();
            return RunForkedAgent.run(params, queryLoop);
        }

        private void reset() {
            streamCalls = 0;
            provider.captures.clear();
        }
    }

    // ── 测试工具 / 断言辅助 ──

    private static Tool writeTool() {
        return new Tool() {
            @Override public String name() { return "Write"; }
            @Override public String description() { return "write a file"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return JsonNodeFactory.instance.objectNode();
            }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "written");
            }
        };
    }

    private static HookPermissionResolver.CanUseTool denyCanUseTool() {
        return (tool, input, ctx, toolUseId, forceDecision) -> ToolPermissionGate.DecisionResult.deny(null);
    }

    private static ToolUseBlock toolCall(String id, String name, String json) {
        try {
            return new ToolUseBlock(id, name, new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** tool_result 消息（role=tool）。 */
    private static ChatMessageDto toolResult(ForkedAgentResult r) {
        return r.messages().stream()
            .filter(m -> m.role() == Role.tool)
            .findFirst().orElse(null);
    }

    /**
     * 消息序列规范化（去掉每次运行都新生成的 id / createdAt / time）—— 等价验证比的是
     * <b>送到 provider 的语义内容</b>（role / content / reasoning / toolCalls 名与参数 / toolCallId / isError）。
     */
    private static List<String> normalizeMessages(List<ChatMessageDto> messages) {
        List<String> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (ChatMessageDto m : messages) {
            StringBuilder sb = new StringBuilder();
            sb.append(m.role()).append('|').append(m.content()).append('|').append(m.reasoning());
            sb.append('|').append(m.toolCallId()).append('|').append(m.isError());
            if (m.toolCalls() != null) {
                for (com.nexusai.model.session.dto.ToolCallDto tc : m.toolCalls()) {
                    sb.append("|tc(").append(tc.id()).append(',').append(tc.name())
                        .append(',').append(tc.arguments()).append(')');
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }
}
