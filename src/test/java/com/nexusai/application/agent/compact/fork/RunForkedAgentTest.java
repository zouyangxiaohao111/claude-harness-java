package com.nexusai.application.agent.compact.fork;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.TaskBudgetParam;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-18 · RunForkedAgent/CacheSafeParams 单测 · 对齐 CC
 * Open-ClaudeCode/src/utils/forkedAgent.ts:57-68 CacheSafeParams + :489-626 runForkedAgent。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-18 的目标是移植 fork 缓存共享支撑
 * （querySource:'compact'、maxTurns:1、skipCacheWrite:true、abortController 透传、继承权限），
 * 并接线 COMPACT_MAX_OUTPUT_TOKENS 使用点（INV-7 / REQ-27）。本测试逐条验证 IMP-18 §5 验收标准：
 * <ol>
 *   <li>fork 参数断言：不设 maxOutputTokens、skipCacheWrite=true、abortController 透传（INV-7）</li>
 *   <li>CacheSafeParams 前 5 参 + thinking 派生（前 5 参 = cache key 全部来源；thinking 不单独携带，
 *       从 toolUseContext 派生 —— 见 forkedAgent.ts:46-56 注释，设 maxOutputTokens 会改 budget_tokens
 *       使 thinking config 偏移 → 缓存 miss）</li>
 *   <li>COMPACT_MAX_OUTPUT_TOKENS 使用点接线（min(20000, getMaxOutputTokensForModel)）</li>
 *   <li>createCompactCanUseTool deny 语义（压缩 summary 模型工具受限，compact.ts:1125-1133）</li>
 * </ol>
 */
class RunForkedAgentTest {

    private static final String MODEL = "claude-sonnet-4-20250514";

    /** boundary 标记 · CC original: {@code SYSTEM_PROMPT_DYNAMIC_BOUNDARY}
     *  (Open-ClaudeCode/src/constants/prompts.ts:114-115,573)。 */
    private static final String BOUNDARY = SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY;

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · fork 参数断言（INV-7）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fork 路径: 不设 maxOutputTokens + skipCacheWrite=true + abortController 透传 (INV-7)")
    void forkPath_doesNotSetMaxOutputTokens_skipCacheWrite_abortPassthrough() {
        AbortController abort = new AbortController();
        ChatMessageDto forkMsg1 = userMessage("f1", "fork context 1");
        ChatMessageDto forkMsg2 = userMessage("f2", "fork context 2");
        ChatMessageDto summaryRequest = userMessage("sr", "compact prompt");

        CacheSafeParams cs = new CacheSafeParams(
            List.of("main-system-prompt"),
            Map.of("cwd", "/repo"),
            Map.of("os", "linux"),
            baseContext(),
            List.of(forkMsg1, forkMsg2));

        ForkedAgentParams params = new ForkedAgentParams(
            List.of(summaryRequest), cs, RunForkedAgent.createCompactCanUseTool(),
            QuerySource.COMPACT, "compact",
            /*maxOutputTokens*/ null,   // fork 路径不设（防缓存 key 破坏）
            /*maxTurns*/ 1,
            /*skipTranscript*/ false,   // compact fork 需要记 transcript
            /*skipCacheWrite*/ true,
            abort,                        // abortController 透传
            null);

        RecordingQuery query = new RecordingQuery();
        RunForkedAgent.run(params, query);

        RunForkedAgent.ForkQueryParams q = query.lastParams();
        // INV-7: fork 不设 maxOutputTokens
        assertThat(q.maxOutputTokensOverride()).isNull();
        // skipCacheWrite=true（fork 不写缓存）
        assertThat(q.skipCacheWrite()).isTrue();
        // querySource='compact'
        assertThat(q.querySource()).isEqualTo(QuerySource.COMPACT);
        // maxTurns=1
        assertThat(q.maxTurns()).isEqualTo(1);
        // abortController 透传（隔离上下文与传入同一实例 —— 用户 Esc 可中止 fork）
        assertThat(q.toolUseContext().abortController()).isSameAs(abort);
        // messages = [...forkContextMessages, ...promptMessages]（forkedAgent.ts:524）
        assertThat(q.messages()).containsExactly(forkMsg1, forkMsg2, summaryRequest);
        // cache-safe 参数透传（systemPrompt 数组 / userContext / systemContext · [RES-R4-2] 数组语义）
        assertThat(q.systemPrompt()).containsExactly("main-system-prompt");
        assertThat(q.userContext()).containsEntry("cwd", "/repo");
        assertThat(q.systemContext()).containsEntry("os", "linux");
        // canUseTool 透传（createCompactCanUseTool deny）
        assertThat(q.canUseTool()).isSameAs(params.canUseTool());
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · CacheSafeParams 前 5 参 + thinking 派生
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CacheSafeParams: 前 5 参精确保留，thinking 从 toolUseContext 派生（不单独携带）")
    void cacheSafeParams_carriesFiveParams_thinkingDerivedFromToolUseContext() {
        ToolUseContext ctx = baseContext();
        List<ChatMessageDto> forkCtx = List.of(userMessage("f1", "ctx1"));
        CacheSafeParams cs = new CacheSafeParams(
            List.of("sys"), Map.of("cwd", "/x"), Map.of("os", "mac"),
            ctx, forkCtx);

        // 前 5 参（cache key 全部来源）精确保留（[RES-R4] systemPrompt 数组语义）
        assertThat(cs.systemPrompt()).containsExactly("sys");
        assertThat(cs.userContext()).containsEntry("cwd", "/x");
        assertThat(cs.systemContext()).containsEntry("os", "mac");
        assertThat(cs.toolUseContext()).isSameAs(ctx);
        assertThat(cs.forkContextMessages()).isSameAs(forkCtx);

        // thinking 派生：thinking config 不是本 record 字段，由继承的
        // toolUseContext.options.thinkingConfig 派生（forkedAgent.ts:46-56）。
        // 若 fork 设 maxOutputTokens，claude.ts Math.min(budget, maxOutputTokens-1)
        // 会改 budget_tokens → thinking config 偏移 → 破坏主线程 cache key。
        // [RES-R4] 第 6 字段是 useGlobalCacheScope（gate 通信通道，CC betas.ts:227-233），
        // 非 thinking —— thinking 仍由 toolUseContext 派生。
        assertThat(CacheSafeParams.class.getRecordComponents()).hasSize(6);
    }

    @Test
    @DisplayName("CacheSafeParams: null 字段兜底（systemPrompt/userContext/systemContext/forkContextMessages）")
    void cacheSafeParams_normalizesNulls() {
        CacheSafeParams cs = new CacheSafeParams(null, null, null, baseContext(), null);
        assertThat(cs.systemPrompt()).isEmpty();
        assertThat(cs.userContext()).isEmpty();
        assertThat(cs.systemContext()).isEmpty();
        assertThat(cs.forkContextMessages()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // RES-C5 · createMinimalCacheSafeParams 兜底填真实 systemPrompt + gate
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RES-C5 兜底构造: createMinimalCacheSafeParams 填真实会话 systemPrompt + 透传 gate（不再恒空）")
    void createMinimalCacheSafeParams_fillsSystemPromptAndGate() {
        // WHY (规则九): CC createCacheSafeParams 从 REPLHookContext 完整构建
        // systemPrompt/userContext/systemContext/toolUseContext/forkContextMessages
        // （forkedAgent.ts:131-141）。Java 兜底 createMinimalCacheSafeParams 旧实现
        // systemPrompt 恒空 → fork 发送边界 splitSysPromptPrefix 无真实输入、缓存 key 与
        // 主循环不一致（RES-C5 缺陷）。本测试锁定: 注入真实原料后 systemPrompt 非空 + gate 透传。
        List<ChatMessageDto> forkMsgs = List.of(userMessage("f1", "ctx"));
        List<String> sysPrompt = List.of("REAL-SESSION-PROMPT",
            "__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__", "dynamic-suffix");

        CacheSafeParams cs = RunForkedAgent.createMinimalCacheSafeParams(
            forkMsgs, sysPrompt, Map.of("uk", "uv"), Map.of("sk", "sv"), true);

        assertThat(cs.systemPrompt()).containsExactlyElementsOf(sysPrompt);
        assertThat(cs.userContext()).containsEntry("uk", "uv");
        assertThat(cs.systemContext()).containsEntry("sk", "sv");
        assertThat(cs.forkContextMessages()).isEqualTo(forkMsgs);
        assertThat(cs.useGlobalCacheScope()).isTrue();
    }

    @Test
    @DisplayName("RES-C5 兜底降级: 无主会话原料（null/空）→ 原 List.of() 行为保留，不抛错")
    void createMinimalCacheSafeParams_nullRawMaterialDegrades() {
        // WHY (规则九): 消费方无主会话 cache-safe 原料时仍兜底 —— null/空 → 原 List.of()
        // 行为保留为异常降级（RES-C5 验收 2），不抛错（CacheSafeParams 紧凑构造 null 兜底）。
        CacheSafeParams cs = RunForkedAgent.createMinimalCacheSafeParams(
            null, null, null, null, false);
        assertThat(cs.systemPrompt()).isEmpty();
        assertThat(cs.userContext()).isEmpty();
        assertThat(cs.systemContext()).isEmpty();
        assertThat(cs.forkContextMessages()).isEmpty();
        assertThat(cs.useGlobalCacheScope()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · COMPACT_MAX_OUTPUT_TOKENS 使用点接线（INV-7）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("COMPACT_MAX_OUTPUT_TOKENS: 常量 = 20000 (context.ts:12) 且使用点 = min(20000, model)")
    void compactMaxOutputTokens_usagePoint_isMinOf20000AndModelMax() {
        // CC 源值：utils/context.ts:12 COMPACT_MAX_OUTPUT_TOKENS = 20_000
        assertThat(CompactConstants.COMPACT_MAX_OUTPUT_TOKENS).isEqualTo(20_000);

        // 使用点：maxOutputTokensOverride = min(COMPACT_MAX_OUTPUT_TOKENS, getMaxOutputTokensForModel)
        // 大模型 → 20_000（封顶）
        assertThat(RunForkedAgent.maxOutputTokensOverride(MODEL)).isEqualTo(20_000);
        // 小模型 → 模型上限（4_096）
        assertThat(RunForkedAgent.maxOutputTokensOverride("claude-3-opus-20240229")).isEqualTo(4_096);
        // 与 IMP-01 流式 fallback 口径一致（同为 INV-7）
        assertThat(RunForkedAgent.maxOutputTokensOverride(MODEL))
            .isEqualTo(StreamCompactSummary.maxOutputTokensOverride(MODEL));
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · createCompactCanUseTool deny 语义
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("createCompactCanUseTool: deny 语义（压缩 summary 模型不能调用任意工具）")
    void createCompactCanUseTool_deniesEveryTool() {
        HookPermissionResolver.CanUseTool canUseTool = RunForkedAgent.createCompactCanUseTool();
        ToolPermissionGate.DecisionResult decision =
            canUseTool.canUse(null, null, baseContext(), "toolUse-1", null);
        assertThat(decision.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5 · onMessage 回调
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-79：onMessage 流式透传——ForkQueryParams 承载 onMessage，query 产出时逐条回调且无 post-hoc 二次回放")
    void runForkedAgent_streamsOnMessage_noPostHocReplay() {
        // WHY（forkedAgent.ts:578）：CC 在 query loop 内 `outputMessages.push(message);
        // onMessage?.(message)` —— 消息产出即回调，非完成后回放。旧 Java RunForkedAgent.run
        // 在 query.run 返回后逐条 replay（△-3，IMP-18 先行者风险）。本测试用流式 fake
        // （产出时回调 onMessage）断言：①onMessage 经 ForkQueryParams 透传（fake 能回调）；
        // ②回调总数 = 产出消息数（无 replay 造成的二次调用）。
        CacheSafeParams cs = new CacheSafeParams(List.of("sys"), Map.of(), Map.of(), baseContext(),
            List.of(userMessage("f1", "ctx1")));
        List<ChatMessageDto> produced = List.of(
            userMessage("a1", "msg1"), userMessage("a2", "msg2"));
        RecordingQuery query = new RecordingQuery(produced);
        List<ChatMessageDto> out = new ArrayList<>();
        ForkedAgentParams params = new ForkedAgentParams(
            List.of(userMessage("sr", "prompt")), cs, RunForkedAgent.createCompactCanUseTool(),
            QuerySource.COMPACT, "compact", null, 1, false, true, new AbortController(), out::add);

        RunForkedAgent.run(params, query);

        // 每条产出消息恰好回调一次（流式 fake 回调 + 无 replay → 数量 = 产出数）
        assertThat(out).containsExactlyElementsOf(produced);
    }

    @Test
    @DisplayName("G-79：ProductionForkedQuery 流式回调顺序 = assistant → tool_result → assistant（产出即回调 · forkedAgent.ts:578）")
    void productionForkedQuery_streamsOnMessageInProduceOrder() {
        // WHY：生产 fork loop 是唯一消息生产点 —— 每产出一条消息（assistant + tool_result）
        // 即回调 onMessage，顺序与 outputMessages 一致（CC query() 产出 user 消息亦回调）。
        EchoTool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        ScriptedProvider provider = new ScriptedProvider(
            new com.nexusai.infra.llm.AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock("c1", "Echo", emptyObject()))),
            new com.nexusai.infra.llm.AssistantMessage("done", "stop", List.of()));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> com.nexusai.infra.llm.ProviderConfig.empty(), registry);

        List<ChatMessageDto> streamed = new ArrayList<>();
        RunForkedAgent.ForkQueryParams params = new RunForkedAgent.ForkQueryParams(
            List.of(userMessage("sr", "fork prompt")), List.of("sys"), Map.of(), Map.of(),
            allowAll(), forkCtxWith(echo), QuerySource.EXTRACT_MEMORIES, null, null, false,
            /*useGlobalCacheScope*/ false, streamed::add);

        ForkedAgentResult result = loop.run(params);

        // 流式回调 = outputMessages 顺序（assistant → tool → assistant），数量一致（无回放）
        assertThat(streamed).containsExactlyElementsOf(result.messages());
        assertThat(streamed).hasSize(3);
        assertThat(streamed.get(1).role()).isEqualTo(Role.tool);
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-M-P0-3 · skipTranscript 参数承载（extract/auto-dream 后台 fork）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ForkedAgentParams 承载 skipTranscript: 后台 fork（extract/auto-dream）置 true 不污染主 transcript")
    void forkParams_carriesSkipTranscriptForBackgroundForks() {
        // WHY: forkedAgent.ts:109 skipTranscript —— extract-memories/auto-dream 后台 fork
        // 记 transcript 会与主线程产生 race（extractMemories.ts:421-423 注释）。参数契约必须
        // 能承载该标志（IMP-M-P0-3 后台 fork 收敛）。
        CacheSafeParams cs = new CacheSafeParams(List.of("sys"), Map.of(), Map.of(), baseContext(),
            List.of(userMessage("f1", "ctx1")));
        ForkedAgentParams background = new ForkedAgentParams(
            List.of(userMessage("sr", "prompt")), cs, RunForkedAgent.createCompactCanUseTool(),
            QuerySource.EXTRACT_MEMORIES, "extract_memories", null, 5,
            /*skipTranscript*/ true, /*skipCacheWrite*/ false, new AbortController(), null);
        assertThat(background.skipTranscript()).isTrue();
        assertThat(background.maxTurns()).isEqualTo(5);
        assertThat(background.querySource()).isEqualTo(QuerySource.EXTRACT_MEMORIES);
    }

    // ════════════════════════════════════════════════════════════════════
    // RES-R4-2 · 通用 fork boundary 剥离数组语义（对齐 CC systemPrompt 数组贯穿）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[RES-R4-2] ProductionForkedQuery 发送边界剥离 boundary（boundary 永不达 LLM）")
    void productionForkedQuery_stripsBoundaryBeforeSend() {
        // WHY（CLAUDE.md 规则 9 · 测试验证意图）：[RES-R4-2] 通用 fork（RunForkedAgent）必须与
        // 主线程一致在发送边界剥离 boundary（splitSysPromptPrefix · LlmAgentLoop:2903-2911），
        // 否则 boundary 标记泄漏到 LLM 且 fork 缓存 key 与主循环字节不一致（forkedAgent.ts:59
        // systemPrompt 为数组、发送边界才剥离）。本测试走完整链（RunForkedAgent → ProductionForkedQuery
        // → provider），断言 provider 收到的 systemPrompt 剥离 boundary 且静态/动态以 \n\n 连接。
        EchoTool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        ScriptedProvider provider = new ScriptedProvider(
            new com.nexusai.infra.llm.AssistantMessage("done", "stop", List.of()));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> com.nexusai.infra.llm.ProviderConfig.empty(), registry);

        // GIVEN: systemPrompt 数组含 boundary + gate=true（firstParty → boundary 模式）
        CacheSafeParams cs = new CacheSafeParams(
            List.of("static-prefix", BOUNDARY, "dynamic-suffix"),
            Map.of(), Map.of(), baseContext(),
            List.of(userMessage("f1", "ctx")), /*useGlobalCacheScope*/ true);
        ForkedAgentParams params = new ForkedAgentParams(
            List.of(userMessage("sr", "prompt")), cs, RunForkedAgent.createCompactCanUseTool(),
            QuerySource.EXTRACT_MEMORIES, "extract", null, 1, true, false, new AbortController(), null);

        RunForkedAgent.run(params, loop);

        // THEN: 发送的 systemPrompt 不含 boundary，静态/动态块以 \n\n 连接（对齐主线程 :2903-2911）
        assertThat(provider.lastSystemPrompt())
            .doesNotContain(BOUNDARY)
            .isEqualTo("static-prefix\n\ndynamic-suffix");
    }

    @Test
    @DisplayName("[RES-C6] ProductionForkedQuery 发送走 blocks 数组（非 join 单 String）· 对齐主线程 blocks 通道")
    void productionForkedQuery_sendsBlocksArray_notJoinedString() {
        // WHY（CLAUDE.md 规则 9）：[RES-C6] 通用 fork 发送此前走 split + join("\\n\\n") 单 String +
        // String 重载（ProductionForkedQuery:174-179/206-207）→ provider String 路径 wrap 单 block
        // ORG，与主线程 blocks 数组通道（LlmAgentLoop:2897-2904 → ModelCaller blocks 重载）序列化
        // 层级不一致 → 缓存 key / 模型侧 system 表示漂移。CC systemPrompt 以数组贯穿发送边界
        // （utils/api.ts:321-435 splitSysPromptPrefix 产物 → buildSystemPromptBlocks 发送）。
        // 本测试断言 provider 收到的是 blocks 数组（splitSysPromptPrefix 剥离产物），而非 join 单 String。
        EchoTool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        ScriptedProvider provider = new ScriptedProvider(
            new com.nexusai.infra.llm.AssistantMessage("done", "stop", List.of()));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> com.nexusai.infra.llm.ProviderConfig.empty(), registry);

        // GIVEN: systemPrompt 数组含 boundary + gate=true（firstParty → boundary 模式）
        CacheSafeParams cs = new CacheSafeParams(
            List.of("static-prefix", BOUNDARY, "dynamic-suffix"),
            Map.of(), Map.of(), baseContext(),
            List.of(userMessage("f1", "ctx")), /*useGlobalCacheScope*/ true);
        ForkedAgentParams params = new ForkedAgentParams(
            List.of(userMessage("sr", "prompt")), cs, RunForkedAgent.createCompactCanUseTool(),
            QuerySource.EXTRACT_MEMORIES, "extract", null, 1, true, false, new AbortController(), null);

        RunForkedAgent.run(params, loop);

        // THEN: 发送走 blocks 数组（splitSysPromptPrefix 产物），boundary 已剥离，cacheScope 正确。
        //   静态（boundary 前）→ GLOBAL、动态（boundary 后）→ NULL（api.ts:368-397）。
        assertThat(provider.lastSystemPromptBlocks()).isNotNull();
        assertThat(provider.lastSystemPromptBlocks())
            .extracting(SystemPromptBlock::text)
            .doesNotContain(BOUNDARY)
            .containsExactly("static-prefix", "dynamic-suffix");
        assertThat(provider.lastSystemPromptBlocks())
            .extracting(SystemPromptBlock::cacheScope)
            .containsExactly(CacheScope.GLOBAL, CacheScope.NULL);
    }

    @Test
    @DisplayName("[RES-R4-2] fork query 收到含 boundary 的 systemPrompt 数组 + gate 透传（AC1/AC4）")
    void runForkedAgent_preservesBoundaryElementsInForkQueryParams() {
        // WHY（CLAUDE.md 规则 9）：[RES-R4-2] ForkQueryParams.systemPrompt 必须是数组语义
        // （forkedAgent.ts:59 SystemPrompt = readonly string[]），boundary 是独立数组元素而非
        // join 内联文本 —— 发送边界（ProductionForkedQuery splitSysPromptPrefix）需要数组才能
        // 识别 boundary 元素并剥离；join 扁平化会让 boundary 丢失元素身份 → 无法剥离 → 泄漏。
        CacheSafeParams cs = new CacheSafeParams(
            List.of("static-prefix", BOUNDARY, "dynamic-suffix"),
            Map.of(), Map.of(), baseContext(),
            List.of(userMessage("f1", "ctx")), /*useGlobalCacheScope*/ true);
        ForkedAgentParams params = new ForkedAgentParams(
            List.of(userMessage("sr", "prompt")), cs, RunForkedAgent.createCompactCanUseTool(),
            QuerySource.COMPACT, "compact", null, 1, false, true, new AbortController(), null);

        RecordingQuery query = new RecordingQuery();
        RunForkedAgent.run(params, query);

        // THEN: fork query 收到 systemPrompt 数组（含 boundary，未经 join 扁平化）+ gate 透传
        RunForkedAgent.ForkQueryParams q = query.lastParams();
        assertThat(q.systemPrompt()).containsExactly(
            "static-prefix", BOUNDARY, "dynamic-suffix");
        assertThat(q.useGlobalCacheScope()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具
    // ════════════════════════════════════════════════════════════════════

    /** 最小 ToolUseContext（8 参兼容构造器）。 */
    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }

    /** 构造 user 消息（对齐 StreamCompactSummaryTest.userMessage 语义）。 */
    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    /** 记录最后一次 query() 调用参数的 fake ForkedQuery · G-79 流式语义：产出消息时回调 onMessage。 */
    static class RecordingQuery implements RunForkedAgent.ForkedQuery {
        private RunForkedAgent.ForkQueryParams last;
        private final List<ChatMessageDto> produced;

        RecordingQuery() {
            this(null);
        }

        RecordingQuery(List<ChatMessageDto> produced) {
            this.produced = produced;
        }

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            this.last = params;
            // G-79 流式语义：query 每产出一条消息即回调 onMessage（forkedAgent.ts:578）——
            // 对齐 ProductionForkedQuery fork loop；RunForkedAgent 不再 post-hoc replay
            List<ChatMessageDto> msgs = produced != null ? produced : params.messages();
            for (ChatMessageDto m : msgs) {
                params.onMessage().accept(m);
            }
            return new ForkedAgentResult(msgs, ForkedAgentResult.ForkUsage.empty());
        }

        RunForkedAgent.ForkQueryParams lastParams() {
            return last;
        }
    }
    // ════════════════════════════════════════════════════════════════════
    // IMP-M-P0-3 · ProductionForkedQuery 生产 fork loop 契约
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ProductionForkedQuery: maxTurns 逐轮上限（恒工具调用时 5 轮硬性终止）")
    void productionForkedQuery_limitsTurnsToMaxTurns() {
        // WHY: extractMemories.ts:426 maxTurns=5 —— fork 必须硬性限制 API round-trips，
        // 防 verification rabbit-hole 烧 turn（CC 注释 "A hard cap prevents verification
        // rabbit-holes from burning turns"）。loop 恒产出工具调用时最多 5 轮 provider 调用。
        EchoTool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        ScriptedProvider provider = new ScriptedProvider(
            new com.nexusai.infra.llm.AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock("c1", "Echo", emptyObject()))),
            new com.nexusai.infra.llm.AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock("c2", "Echo", emptyObject()))));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> com.nexusai.infra.llm.ProviderConfig.empty(), registry);

        RunForkedAgent.ForkQueryParams params = forkParams(5, allowAll(), forkCtxWith(echo));
        ForkedAgentResult result = loop.run(params);

        // maxTurns=5 硬顶：provider 调用数 = 5（每轮 1 次 API round-trip）
        assertThat(provider.callCount()).isEqualTo(5);
        long assistantCount = result.messages().stream()
            .filter(m -> m.role() == Role.assistant).count();
        assertThat(assistantCount).isEqualTo(5);
        // skipCacheWrite 契约透传（ForkQueryParams 承载；extract/auto-dream fork = false）
        assertThat(params.skipCacheWrite()).isFalse();
        assertThat(params.maxTurns()).isEqualTo(5);
    }

    @Test
    @DisplayName("ProductionForkedQuery: 无工具调用时正常终止（读→写多轮 fork 语义）")
    void productionForkedQuery_terminatesOnFinalAnswer() {
        // WHY: extract/auto-dream fork 是受限多轮 agent（读文件 → 写记忆），末轮必须是无工具
        // 调用的最终回答。loop 在模型产出纯文本时终止（CC query() 无 tool_use → 停止）。
        EchoTool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        ScriptedProvider provider = new ScriptedProvider(
            new com.nexusai.infra.llm.AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock("c1", "Echo", emptyObject()))),
            new com.nexusai.infra.llm.AssistantMessage("done", "stop", List.of()));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> com.nexusai.infra.llm.ProviderConfig.empty(), registry);

        ForkedAgentResult result = loop.run(forkParams(null, allowAll(), forkCtxWith(echo)));

        assertThat(provider.callCount()).isEqualTo(2);
        // assistant(tool) + tool_result + assistant(final)
        assertThat(result.messages()).hasSize(3);
        assertThat(result.messages().get(2).role()).isEqualTo(Role.assistant);
        assertThat(result.messages().get(2).content()).isEqualTo("done");
    }

    @Test
    @DisplayName("ProductionForkedQuery: canUseTool Deny → tool_result isError（INV-6 受限门控真实生效）")
    void productionForkedQuery_canUseToolDeny_producesErrorToolResult() {
        // WHY: INV-6 —— extract/auto-dream 的受限 canUseTool（Read/Grep/Glob + 只读 Bash +
        // auto-memory 内 Edit/Write）必须在 fork loop 内真实生效。拒绝 → ToolResult.error
        // 注入 messages 让 LLM 自纠（对齐 CC canUseTool deny 语义）。此前 fork 若复用主循环
        // 会继承主线程权限 → INV-6 破坏（H9-GAP-4）。
        EchoTool echo = new EchoTool();
        ToolRegistry registry = ToolRegistry.from(List.of(echo));
        ScriptedProvider provider = new ScriptedProvider(
            new com.nexusai.infra.llm.AssistantMessage("", "tool_calls",
                List.of(new ToolUseBlock("c1", "Echo", emptyObject()))));
        ProductionForkedQuery loop = new ProductionForkedQuery(
            () -> provider, () -> MODEL, () -> com.nexusai.infra.llm.ProviderConfig.empty(), registry);

        // 受限 canUseTool（对齐 createAutoMemCanUseTool deny 语义：拒绝任意工具）
        // maxTurns=1 界定单轮：脚本化 provider 恒产工具调用（无 maxTurns 会无限循环 ——
        // 真实模型会看到 denied tool_result 后终止，脚本 provider 不会）。
        ForkedAgentResult result = loop.run(forkParams(1, denyAll(), forkCtxWith(echo)));

        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().get(1).role()).isEqualTo(Role.tool);
        assertThat(result.messages().get(1).isError()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-M-P0-3 · ProductionForkedQuery 测试工具
    // ════════════════════════════════════════════════════════════════════

    /** 构造 ForkQueryParams（extract 风格：maxTurns/skipCacheWrite=false；[RES-R4-2] 数组 + gate=3P 默认）。 */
    private static RunForkedAgent.ForkQueryParams forkParams(Integer maxTurns,
            HookPermissionResolver.CanUseTool canUseTool, ToolUseContext ctx) {
        return new RunForkedAgent.ForkQueryParams(
            List.of(userMessage("sr", "fork prompt")), List.of("sys"), Map.of(), Map.of(),
            canUseTool, ctx, QuerySource.EXTRACT_MEMORIES, null, maxTurns, false,
            /*useGlobalCacheScope*/ false, /*onMessage*/ null);
    }

    /** 放行任意工具的 canUseTool。 */
    private static HookPermissionResolver.CanUseTool allowAll() {
        return (tool, input, ctx, toolUseId, forceDecision) ->
            ToolPermissionGate.DecisionResult.allow();
    }

    /** 拒绝任意工具的 canUseTool（对齐 createAutoMemCanUseTool deny 语义）。 */
    private static HookPermissionResolver.CanUseTool denyAll() {
        return (tool, input, ctx, toolUseId, forceDecision) ->
            ToolPermissionGate.DecisionResult.deny(null);
    }

    /** fork 隔离上下文（availableTools 携带工具 → buildToolsArray 产真实工具数组）。 */
    private static ToolUseContext forkCtxWith(Tool... tools) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(tools), "", new AbortController(), List.of());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode emptyObject() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    /** 简单可执行工具（ToolRegistry.dispatch 目标）。 */
    static final class EchoTool implements Tool {
        @Override public String name() { return "Echo"; }
        @Override public String description() { return "echo test tool"; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "echo:" + call.name());
        }
    }

    /** 脚本化 provider：每轮 stream 按脚本返回 assistant message（[RES-R4-2] 捕获发送的 systemPrompt）。 */
    static final class ScriptedProvider implements com.nexusai.infra.llm.LlmProvider {
        private final List<com.nexusai.infra.llm.AssistantMessage> script;
        private final java.util.concurrent.atomic.AtomicInteger callCount =
            new java.util.concurrent.atomic.AtomicInteger();
        private volatile String lastSystemPrompt;
        private volatile List<SystemPromptBlock> lastSystemPromptBlocks;

        ScriptedProvider(com.nexusai.infra.llm.AssistantMessage... script) {
            this.script = List.of(script);
        }

        int callCount() { return callCount.get(); }
        String lastSystemPrompt() { return lastSystemPrompt; }
        /** [RES-C6] blocks 重载收到的发送边界 blocks 数组（null = 未走 blocks 重载 → 旧 String join 路径）。 */
        List<SystemPromptBlock> lastSystemPromptBlocks() { return lastSystemPromptBlocks; }

        @Override public String type() { return "test"; }
        @Override public String chat(com.nexusai.infra.llm.ProviderConfig c, String m, String s, String u) { return ""; }

        /** [RES-C6] blocks 重载覆写：捕获发送边界 blocks 数组（对齐主线程 ModelCaller blocks 分支）。 */
        @Override
        public void stream(com.nexusai.infra.llm.ProviderConfig config, String modelName,
                           List<SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, com.fasterxml.jackson.databind.node.ArrayNode tools,
                           Integer maxOutputTokensOverride, TaskBudgetParam taskBudget, String effortValue,
                           String querySource,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.Consumer<com.nexusai.infra.llm.AssistantMessage> onAssistantMessage,
                           java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> onToolCallComplete,
                           java.util.function.Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           com.nexusai.application.agent.tool.AbortController abortController,
                           java.util.function.Consumer<Throwable> onError,
                           Runnable onComplete) {
            this.lastSystemPromptBlocks = systemPromptBlocks;
            // 兼容既有 String 断言：blocks → join("\\n\\n") 与 splitSysPromptPrefix 语义一致
            this.lastSystemPrompt = systemPromptBlocks == null ? null : systemPromptBlocks.stream()
                .map(SystemPromptBlock::text)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
            int idx = Math.min(callCount.getAndIncrement(), script.size() - 1);
            onAssistantMessage.accept(script.get(idx));
            onComplete.run();
        }
    }
}

