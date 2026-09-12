package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.ProductionForkedQuery;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.infra.llm.TaskBudgetParam;
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
 * fork 消息 userContext 前置 · 对齐 CC {@code query.ts:900} {@code prependUserContext}
 * （forkedAgent.ts:543 initialMessages <b>不贴</b> userContext，userContext 作参数透传到
 * 发送边界才贴一次）。
 *
 * <p><b>WHY 本测试存在（意图）</b>: 用户上下文（claudeMd?/currentDate）是 Anthropic prompt
 * cache 前缀的一部分 —— fork 不前置则消息前缀与主线程不一致、fork 缓存共享永不命中；
 * 但前置<b>两次</b>（StreamCompactSummary 外层 + ProductionForkedQuery 内层）会得到
 * {@code [meta, meta, ...]}，与主线程 {@code [meta, ...]} 从 message[1] 起错位，同样
 * 使 prompt cache 永不命中，且 `AgentLoopContext.prependUserContext` 无去重、每次
 * {@code UUID.randomUUID()}（不幂等）。本测试钉扎「<b>恰好前置一次</b>」。
 *
 * <p><b>[userctx-single-prepend] 断言面 = 真实发给 provider 的 messages</b>: 旧版本经
 * {@link ForkConvergenceCcContractTest.RecordingQuery} 假替身断言（该替身只记录
 * RunForkedAgent 交给 seam 的中间产物，<b>从不执行</b> ProductionForkedQuery 的发送边界
 * 前置）→ 双前置 bug 恒绿。现改为注入真实 {@code ProductionForkedQuery} + 捕获 provider 入参的
 * {@link ForkConvergenceCcContractTest.CapturingProvider}。
 *
 * <p><b>断言 WHY</b>:
 * <ul>
 *   <li>userContext 非空 → provider 实收 = [meta, ...forkCtx, summary] 且 meta <b>恰好 1 条</b>
 *       （外层前置加回去 → 5 条 / 2 meta → RED）</li>
 *   <li>userContext 空 map → 2 条无 meta（对齐 CC api.ts:457-459，空 context 不污染前缀）</li>
 *   <li>INV-7 不变量不回归：maxOutputTokens=null / skipCacheWrite=true / abortController 透传
 *       （经委托参数断言）</li>
 * </ul>
 */
class StreamCompactSummaryForkUserContextTest {

    private static final String SUMMARY_REQUEST = "请对会话做摘要";

    @Test
    @DisplayName("F1（真实生产路径）: provider 实收队首 1 条 meta user 消息（forkCtx 后 summary 最后）")
    void forkMessages_prependsUserContextMetaMessage() {
        CacheSafeParams cs = new CacheSafeParams(
            List.of("systemPrompt"),
            Map.of("claudeMd", "项目指令", "currentDate", "Today's date is 2026-08-06."),
            Map.of(),
            baseContext(),
            List.of(userMessage("c1", "ctx1"), userMessage("c2", "ctx2")));
        AbortController abort = new AbortController();
        ForkConvergenceCcContractTest.CapturingProvider provider =
            new ForkConvergenceCcContractTest.CapturingProvider();

        scsWithRealForkLoop(cs, abort, provider).streamCompactSummary(
            List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
            "model", fakeProvider(), ProviderConfig.empty());

        // ⚠️ 断言面 = 真实 ProductionForkedQuery 实际发给 provider 的 messages（旧断言读
        //   RunForkedAgent 交给 seam 的中间产物 → 假替身从不执行 ProductionForkedQuery:233-235
        //   的发送边界前置 → 「外层 + 内层双前置」bug 在该替身下恒绿 → 已被本改造取代）。
        List<ChatMessageDto> fork = provider.lastHistory();
        assertThat(fork).as("fake provider 必须真实收到 fork 请求（否则本测试空转）").isNotNull();
        // 1(userContext meta) + 2(forkCtx) + 1(summary) = 4（双层都贴 → 5 条 → RED）
        assertThat(fork)
            .as("userContext 只前置一次（CC query.ts:900）：双层都贴 → [meta, meta, ...] → 主线程"
                + " [meta, ...] 前缀错位 → message[1] 起 prompt cache 永不命中")
            .hasSize(4);
        assertThat(fork.stream().filter(ChatMessageDto::isMeta).count())
            .as("meta 消息必须恰好 1 条（AgentLoopContext.prependUserContext 无去重、不幂等）")
            .isEqualTo(1);
        ChatMessageDto meta = fork.get(0);
        assertThat(meta.isMeta()).as("userContext 元消息必须 isMeta=true（CC createUserMessage isMeta:true）").isTrue();
        assertThat(meta.role()).isEqualTo(Role.user);
        assertThat(meta.content()).startsWith("<system-reminder>");
        assertThat(meta.content()).contains("# claudeMd");
        assertThat(meta.content()).contains("# currentDate");
        // forkCtx 顺序保持，summaryRequest 恒在末尾
        assertThat(fork.get(1).content()).isEqualTo("ctx1");
        assertThat(fork.get(2).content()).isEqualTo("ctx2");
        assertThat(fork.get(3).content()).isEqualTo(SUMMARY_REQUEST);
    }

    @Test
    @DisplayName("F1（真实生产路径）: userContext 空 map → provider 实收 2 条无 meta（不污染前缀）")
    void forkMessages_emptyUserContext_passthrough() {
        CacheSafeParams cs = new CacheSafeParams(
            List.of("systemPrompt"), Map.of(), Map.of(), baseContext(),
            List.of(userMessage("c1", "ctx1")));
        ForkConvergenceCcContractTest.CapturingProvider provider =
            new ForkConvergenceCcContractTest.CapturingProvider();

        scsWithRealForkLoop(cs, new AbortController(), provider).streamCompactSummary(
            List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
            "model", fakeProvider(), ProviderConfig.empty());

        List<ChatMessageDto> fork = provider.lastHistory();
        assertThat(fork).as("fake provider 必须真实收到 fork 请求").isNotNull();
        assertThat(fork).hasSize(2);
        assertThat(fork.get(0).content()).isEqualTo("ctx1");
        assertThat(fork.get(1).content()).isEqualTo(SUMMARY_REQUEST);
        assertThat(fork.get(0).isMeta()).as("无 userContext 不得前置 meta 消息").isFalse();
    }

    @Test
    @DisplayName("INV-7: fork 委托参数不设 maxOutputTokens / skipCacheWrite=true / abortController 透传（不回归）")
    void forkRequest_inv7_invariantsPreserved() {
        CacheSafeParams cs = new CacheSafeParams(
            List.of("systemPrompt"),
            Map.of("claudeMd", "项目指令"),
            Map.of(),
            baseContext(),
            List.of(userMessage("c1", "ctx1")));
        AbortController abort = new AbortController();
        ForkConvergenceCcContractTest.RecordingQuery recording = new ForkConvergenceCcContractTest.RecordingQuery();
        recording.respond(new ForkedAgentResult(
            List.of(assistantMessage("summary text")), ForkedAgentResult.ForkUsage.empty()));

        compactSummaryWith(cs, abort, recording).streamCompactSummary(
            List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
            "model", fakeProvider(), ProviderConfig.empty());

        RunForkedAgent.ForkQueryParams q = recording.lastParams();
        assertThat(q.maxOutputTokensOverride()).as("fork 设 maxOutputTokens 会改 budget_tokens 破坏主线程 cache key").isNull();
        assertThat(q.skipCacheWrite()).isTrue();
        assertThat(q.toolUseContext().abortController())
            .as("abortController 透传到 fork 隔离上下文（用户 Esc 可中止 fork）")
            .isSameAs(abort);
    }

    @Test
    @DisplayName("F2: 流式 fallback 不前置 userContext（对齐 CC compact.ts:1292 streamingFallback 无 userContext 参数）")
    void streamingFallback_doesNotPrependUserContext() {
        // 捕获传给 provider 的消息列表（fallback 路径经 streamOnce → provider.stream history）
        List<ChatMessageDto>[] captured = new List[]{null};
        LlmProvider fake = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                                         List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                                         List<ChatMessageDto> h,
                                         ArrayNode t,
                                         Integer maxOutputTokensOverride,
                                         com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                                         String effortValue, String querySource,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                                         Consumer<String> orc, Runnable osf,
                                         AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp, Boolean skipCacheWrite) {
                captured[0] = h;
                oa.accept(new AssistantMessage("summary text", "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "summary text";
            }
        };

        // 3 参构造：cacheSafeParamsSupplier=null → fork 路径跳过 → 直落流式 fallback
        StreamCompactSummary scs = new StreamCompactSummary(() -> fake, () -> "model", ProviderConfig::empty);

        // [IMP-CM-14 F02] streamCompactSummary 返回 SummaryResult（text + usage）
        CompactConversation.SummaryResult result = scs.streamCompactSummary(
            List.of(userMessage("c1", "ctx1"), userMessage("c2", "ctx2")),
            SUMMARY_REQUEST, 0, "model", fake, ProviderConfig.empty());

        // WHY: CC streamingFallback 直调 queryModelWithStreaming（compact.ts:1292-1304）无 userContext
        //   参数（claude.ts:752 签名无 userContext 字段）→ fallback 消息不得含 userContext meta 消息。
        //   若将来有人把 prependUserContext 误加到 fallback（偏离 CC 主查询循环 query.ts:660），
        //   队首将出现 <system-reminder> meta 消息 → 本断言 RED。
        assertThat(result).as("fallback 摘要结果正常返回").isNotNull();
        assertThat(result.text()).as("fallback 摘要文本正常返回").isEqualTo("summary text");
        assertThat(captured[0]).as("fake provider 应收到 fallback 消息列表").isNotNull();
        assertThat(captured[0].get(0).isMeta())
            .as("流式 fallback 队首不得是 userContext meta 消息（CC compact.ts:1292 不前置）")
            .isFalse();
        assertThat(captured[0].get(0).content())
            .as("流式 fallback 队首不得以 <system-reminder> 开头")
            .doesNotStartWith("<system-reminder>");
        assertThat(captured[0].get(captured[0].size() - 1).content())
            .as("fallback 末尾恒为 summaryRequest")
            .isEqualTo(SUMMARY_REQUEST);
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-SP2-07 G1] fork 发送边界 needsToolBasedCacheMarker 等价物
    // （CC claude.ts:1212-1214 → claude.ts:1377 skipGlobalCacheForSystemPrompt）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G1: fork gate=true + MCP 工具 → blocks 无 GLOBAL 块（模式 1）")
    void forkCacheSharing_withMcpTool_blocksHaveNoGlobalScope() {
        // ── 1. fake provider：捕获 17-arg blocks stream 的 arg(2)=blocks ──
        List<SystemPromptBlock>[] capturedBlocks = new List[]{null};
        LlmProvider fake = new LlmProvider() {
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
                capturedBlocks[0] = blocks;
                onChunk.accept("summary text");
                onAssistant.accept(new AssistantMessage("summary text", "stop", List.of()));
                onComplete.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "summary text";
            }
        };

        // ── 2. CacheSafeParams：systemPrompt 含 boundary + TUC 含 MCP 工具 + gate=true ──
        com.nexusai.application.agent.tool.Tool mcpTool = new com.nexusai.application.agent.tool.Tool() {
            @Override public String name() { return "mcp__demo__x"; }
            @Override public String description() { return "demo mcp tool"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
            }
        };
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(mcpTool), "", new AbortController(), List.of());
        CacheSafeParams cs = new CacheSafeParams(
            List.of("static-part", SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY, "dynamic-part"),
            Map.of(), Map.of(), tuc,
            List.of(userMessage("c1", "ctx1")),
            true /* useGlobalCacheScope=true · fork 与主线程同一 gate */);

        // ── 3. 全量构造（cacheSafeParamsSupplier 注入 + promptCacheSharingEnabled=true）──
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> fake, () -> "model", () -> new ProviderConfig("https://api.anthropic.com", "sk-test"),
            () -> cs, () -> new AbortController(), () -> null, null,
            false, true, false, null, null, null);

        // [IMP-CM-14 F02] streamCompactSummary 返回 SummaryResult（text + usage）
        CompactConversation.SummaryResult result = scs.streamCompactSummary(
            List.of(userMessage("c1", "ctx1")), SUMMARY_REQUEST, 0,
            "model", fake, new ProviderConfig("https://api.anthropic.com", "sk-test"));

        // ── 4. 断言 ──
        assertThat(result).as("fork 摘要结果正常返回").isNotNull();
        assertThat(result.text()).as("fork 摘要文本正常返回").isEqualTo("summary text");
        assertThat(result.usage()).as("fork 摘要必带 usage（非 null，可零值）").isNotNull();
        assertThat(capturedBlocks[0]).as("fake provider 必须收到 blocks 数组").isNotNull();
        assertThat(capturedBlocks[0])
            .as("MCP 工具 + gate=true → needsToolBasedCacheMarker=true → 模式 1 无 GLOBAL 块"
                + "（现恒 false → 模式 2 静态段 GLOBAL → 本断言 RED）")
            .noneMatch(b -> b.cacheScope() == CacheScope.GLOBAL);
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具
    // ════════════════════════════════════════════════════════════════════

    /**
     * 13 参构造 + <b>真实生产 fork loop</b>（{@link ProductionForkedQuery}）·
     * 消息拼接断言面（「最终发给 provider 的 messages」）。
     *
     * <p><b>为什么不用 {@link ForkConvergenceCcContractTest.RecordingQuery}</b>: 假替身只记录
     * RunForkedAgent 交给 seam 的中间产物，<b>从不执行</b> {@code ProductionForkedQuery:233-235}
     * 的发送边界 {@code prependUserContext} —— 「外层 + 内层双前置」bug 在替身下恒绿。
     */
    private static StreamCompactSummary scsWithRealForkLoop(
            CacheSafeParams cs, AbortController abort,
            ForkConvergenceCcContractTest.CapturingProvider provider) {
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> fakeProvider(), () -> "model", ProviderConfig::empty,
            () -> cs, () -> abort, null, null, false, true, false, null, null, null);
        scs.setForkedQuery(new ProductionForkedQuery(
            () -> provider, () -> "model", ProviderConfig::empty, new ToolRegistry()));
        return scs;
    }

    /** 13 参构造 + fork seam 注入（⊕-7 委托断言面）。 */
    private static StreamCompactSummary compactSummaryWith(CacheSafeParams cs, AbortController abort,
                                                           ForkConvergenceCcContractTest.RecordingQuery recording) {
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> fakeProvider(), () -> "model", ProviderConfig::empty,
            () -> cs, () -> abort, null, null, false, true, false, null, null, null);
        scs.setForkedQuery(recording);
        return scs;
    }

    /** fake provider（fork 成功路径不经 provider；仅兜底引用）。 */
    private static LlmProvider fakeProvider() {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> sp,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                         TaskBudgetParam tb, String ev, String qs,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         Consumer<ToolUseBlock> otc, Consumer<String> orc,
                                         Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp, Boolean skipCacheWrite) {
                oa.accept(new AssistantMessage("summary text", "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "summary text";
            }
        };
    }

    /** 最小 ToolUseContext（8 参兼容构造器）。 */
    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }

    /** 构造 assistant 消息（CC 结果提取面）。 */
    private static ChatMessageDto assistantMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, null,
            false, null, null, null, null, null, null, false, false);
    }

    /** 构造 user 消息（对齐 RunForkedAgentTest.userMessage 语义）。 */
    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}
