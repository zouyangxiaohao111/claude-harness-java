package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * [H7-arch Phase 5-2 B1-f] run(RunRequest) 适配器 + queryLoop 收敛契约测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: B1 是结构性大改造（行为零变化）——
 * 旧 {@code agent.QueryParams} 改名 {@link RunRequest}（run 契约），新建
 * {@code loop.QueryParams}（loop 输入），{@code run(RunRequest)} 变适配器经
 * {@code queryLoop(loop.QueryParams, state, uuids)} 驱动循环。本测试守住三条契约：
 * <ol>
 *   <li><b>run(RunRequest) 是 run 契约</b> —— {@link AgentLoop#run(RunRequest)} 签名存在，
 *       且传入 {@code RunRequest.forTest(...)} 能端到端驱动真实 loop 产出 assistant 消息
 *       （若适配器没把 RunRequest 解包到 QueryParams、loop 未接通，则无 assistant 消息 → RED）。</li>
 *   <li><b>queryLoop 唯一入口签名</b> —— {@code queryLoop(QueryParams, state, uuids)} 可编译可调用
 *       （若签名回归到旧 7 散参，本测试编译失败 → RED），且返回 {@link LoopResult}。</li>
 *   <li><b>querySource 必填校验</b> —— {@code loop.QueryParams} compact ctor 拒绝 null querySource。</li>
 * </ol>
 *
 * @see LlmAgentLoop#run(RunRequest)
 * @see LlmAgentLoop#queryLoop(QueryParams, AgentState, List)
 * @see RunRequest#forTest(String, String)
 */
class LlmAgentLoopRunRequestContractTest {

    /**
     * RED-tooth: run(RunRequest.forTest) 驱动真实 loop 产出 ≥1 assistant 消息。
     *
     * <p>真实 LlmAgentLoop 实例 + mocked LlmProviderFactory（无 toolRegistry / hook / token budget），
     * provider 首调返回纯文本 stop 消息 → loop 退出 NORMAL。若 run() 适配器断裂（RunRequest 未
     * 解包 / queryLoop 未接通），state 无 assistant 消息 → 断言失败。
     */
    @Test
    @DisplayName("run(RunRequest.forTest) 驱动真实 loop 返回 AgentState 且含 ≥1 assistant 消息")
    void run_withRunRequest_forTest_returnsStateWithAssistantMessage() {
        // ── 1. provider：首调返回 stop 纯文本 → loop 正常退出 ──
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("Hello from test provider");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("Hello from test provider", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. 真实 LlmAgentLoop（null toolRegistry → streaming executor 分支跳过）──
        LlmAgentLoop loop = new LlmAgentLoop(factory);

        // ── 3. 唯一 run 契约：AgentLoop 接口入参 RunRequest ──
        AgentLoop asInterface = loop;   // 编译断言：AgentLoop.run(RunRequest) 签名存在
        AgentState state = asInterface.run(RunRequest.forTest("hello", "test-model", null));

        // ── 4. 断言 ──
        assertThat(state).as("run(RunRequest) 必须返回非 null AgentState").isNotNull();
        assertThat(state.rawMessages().stream().anyMatch(m -> m.role() == Role.assistant))
            .as("适配器必须接通 loop：至少 1 条 assistant 消息（run() 追加 user → loop 追加 assistant）")
            .isTrue();
        assertThat(state.exitReason())
            .as("loop 正常退出（provider 返回 stop 无 tool call → needsFollowUp=false → NORMAL）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
    }

    /**
     * [对齐 CC 2026-09-09] reasoning-only（content 空、无工具）不得被空响应守卫判死 —— 回归 LlmAgentLoop:7252。
     *
     * <p><b>WHY（规则 9 · 测试验证意图）</b>：CC query.ts 无 {@code NO_ASSISTANT_TEXT} 终止（Java 独有
     * 防御，AgentState 自注）。deepseek reasoning 模型长思考后极常见「content 空 + reasoning 非空 +
     * 无工具」（本会话 sess-fcdcdc68/msg-d9aaa8dc turn36 reasoning=221872 字符实锤）——尤其在工具批
     * （如 TodoWrite）后 do-while 强制续轮回喂结果的那一轮。旧守卫只看 text/chunkCount，把这种合法收尾
     * 当空响应 break 掉整个 run，22 万字符思考在 appendMessage 前整体丢弃、最后正文永不产出。修复后：
     * reasoning 非空即走正常纯文本分支，thinking 落历史、finishReason=stop、NORMAL 收尾。
     * <p>若修复回退（守卫重新无视 reasoning）→ 本测试 run 以 NO_ASSISTANT_TEXT 死 → RED。
     */
    @Test
    @DisplayName("reasoning-only(content 空无工具) 不再 NO_ASSISTANT_TEXT：run NORMAL 且 reasoning 落历史")
    void run_reasoningOnlyEmptyContent_isNormalAndKeepsReasoning() {
        // ── 1. provider：不产任何 content chunk；仅交付带 reasoning 的 assistant（deepseek 思考后 content 空）──
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("", "stop", List.of(), "模型深度思考内容(不含正文)"));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

        // ── 2. 断言 ──
        assertThat(state.exitReason())
            .as("reasoning-only 是合法收尾：不得以 NO_ASSISTANT_TEXT 判死 run（对齐 CC 无该终止）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
        assertThat(state.rawMessages())
            .as("reasoning 必须随 assistant 消息落历史（修复后走正常 appendMessage，不再被 break 丢弃）")
            .anyMatch(m -> m.role() == Role.assistant && "模型深度思考内容(不含正文)".equals(m.reasoning()));
    }

    /**
     * [对齐 CC 2026-09-09] 空响应守卫的"另一半"必须保留：真·全空（无 content 无 reasoning 无工具）仍判死。
     *
     * <p><b>WHY（规则 9）</b>：NO_ASSISTANT_TEXT 只应保留给 provider 真·空响应（无正文、无思考、无工具），
     * 它是 Java 对"流完成但无任何可消费输出"的防御。本次修正是给该守卫加 reasoning 豁免，<b>不是删除它</b>。
     * 若后续把守卫整个拿掉 → 真·空响应会以空 assistant 消息 + NORMAL 收尾，掩盖 provider 异常 → RED 拦截。
     */
    @Test
    @DisplayName("真·全空(无 content 无 reasoning 无工具) 仍 NO_ASSISTANT_TEXT（守卫保留验证）")
    void run_trulyEmpty_noContentNoReasoning_stillNoAssistantText() {
        // ── 1. provider：无 content chunk、msg 无 reasoning 无工具（真·空响应）──
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("", "stop", List.of(), ""));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

        // ── 2. 断言 ──
        assertThat(state.exitReason())
            .as("真·全空响应仍应 NO_ASSISTANT_TEXT（豁免只给 reasoning，不放行 provider 空响应）")
            .isEqualTo(AgentState.ExitReason.NO_ASSISTANT_TEXT);
    }

    /**
     * [RES-SP31 · OPD-SP-31] appendSystemPrompt 传递链：RunRequest → AgentState。
     *
     * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC 中 {@code --append-system-prompt} 是用户追加指令的
     * 唯一无条件通道，append 恒末尾追加（systemPrompt.ts:121 / main.tsx:1364-1382）。
     * Java 侧此前 LlmAgentLoop:2793 恒传 null（死通道）——用户追加指令永远到不了 system prompt。
     * 本测试钉死接线：RunRequest 携带的 append 值必须落到 AgentState，供 LlmAgentLoop s10
     * 组装链（:2793）消费。若接线回归到恒 null，断言失败即 RED。
     */
    @Test
    @DisplayName("appendSystemPrompt 传递链: RunRequest.appendSystemPrompt → AgentState.appendSystemPrompt（OPD-SP-31 接线）")
    void run_withAppendSystemPrompt_stateCarriesIt() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null, "用户追加指令"));

        assertThat(state.appendSystemPrompt())
            .as("RunRequest 携带的 append 必须落到 AgentState（LlmAgentLoop s10:2793 组装消费）")
            .isEqualTo("用户追加指令");
    }

    /**
     * [RES-SP31] RunRequest.session 工厂透传 appendSystemPrompt（HTTP DTO → RunRequest 链）。
     *
     * <p><b>WHY</b>: CC main.tsx:1364-1382 中 append 经 options 传递至 QueryParams；
     * Java 侧 SendMessageRequest.appendSystemPrompt → ChatService → RunRequest.session 须透传，
     * 否则 HTTP 请求体里的追加指令丢失。本测试钉死工厂级透传（编译级 + 断言级）。
     */
    @Test
    @DisplayName("RunRequest.session 工厂透传 appendSystemPrompt（HTTP DTO → RunRequest 链）")
    void sessionFactory_carriesAppendSystemPrompt() {
        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RunRequest req = RunRequest.session("p", sid, UUID.randomUUID(),
            ProviderConfig.empty(), "test-model", "CUSTOM", "APPEND-指令", null);

        assertThat(req.appendSystemPrompt()).as("session 工厂必须透传 appendSystemPrompt").isEqualTo("APPEND-指令");
    }

    /**
     * [IMP-GP-02 · GC-03 · OPD-WF7-GC-03] RunRequest.session 12 参工厂透传 jsonSchema
     * （HTTP DTO → RunRequest 链）。
     *
     * <p><b>WHY</b>: CC 主循环注册点 QueryEngine.ts:327-333 依赖 {@code jsonSchema} 门控
     * （jsonSchema && hasStructuredOutputTool → registerStructuredOutputEnforcement）。
     * Java 侧 web 层接线 {@code SendMessageRequest.jsonSchema}（HTTP 请求体，CC original:
     * {@code --json-schema} main.tsx:1880-1883）→ ChatService → 本 12 参 session 工厂 → RunRequest
     * → LlmAgentLoop.doRun 门控（params.jsonSchema()!=null → 注册 STOP enforcement）。若工厂未
     * 透传 jsonSchema，HTTP 请求体的结构化输出 schema 丢失 → 主循环 STOP 门控失效（GC-002 缺口
     * 复发）。本测试钉死工厂级透传（编译级 + 断言级）。
     */
    @Test
    @DisplayName("RunRequest.session 12 参工厂透传 jsonSchema（HTTP DTO → RunRequest 链，GC-03）")
    void sessionFactory_carriesJsonSchema() {
        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode schema = om.createObjectNode()
            .put("type", "object")
            .set("properties", om.createObjectNode()
                .set("answer", om.createObjectNode().put("type", "string")));
        RunRequest req = RunRequest.session("p", sid, UUID.randomUUID(),
            ProviderConfig.empty(), "test-model", "CUSTOM", null, null,
            null, false, null, schema);

        assertThat(req.jsonSchema())
            .as("12 参 session 工厂必须透传 jsonSchema（主循环 structured output enforcement 门控输入）")
            .isEqualTo(schema);
    }

    /**
     * [IMP-GP-02 · GC-03 · OPD-WF7-GC-03] 默认会话工厂 jsonSchema 恒 null（结构化输出未启用零回归）。
     *
     * <p><b>WHY</b>: jsonSchema 缺省（HTTP 请求体未传）→ 主循环不注册 enforcement（CC
     * QueryEngine.ts:331 门控短路）→ 行为与现状一致（零回归）。若默认工厂意外注入非 null schema，
     * 主循环会在未指定结构化输出的 run 上错误注册 STOP 门控 → 每次 STOP 全 blocking 挂起。
     */
    @Test
    @DisplayName("默认 session 工厂 jsonSchema 恒 null（未启用结构化输出零回归，GC-03）")
    void sessionFactory_defaultJsonSchemaIsNull() {
        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RunRequest req = RunRequest.session("p", sid, UUID.randomUUID(),
            ProviderConfig.empty(), "test-model", "CUSTOM", null, null, null);
        assertThat(req.jsonSchema()).as("默认工厂不得注入 jsonSchema（零回归门控）").isNull();
    }

    /**
     * queryLoop 唯一入口签名：queryLoop(loop.QueryParams, state, uuids)。
     *
     * <p>编译级断言（签名回归 → 编译失败即 RED）+ 调用返回 LoopResult。
     * deps 从 params.deps() 读（D1）、stopHookActive 不进 params（D2）、
     * config/modelName/querySource 从 params 读（D3）。
     */
    @Test
    @DisplayName("queryLoop(loop.QueryParams, state, uuids) 为唯一循环入口")
    void queryLoop_convergedSignature_isSingleLoopEntry() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("ok");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("ok", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentState state = new AgentState("sys", null, null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            com.nexusai.model.session.dto.FinishReason.stop, null, null, "刚刚",
            java.time.OffsetDateTime.now(), null, null, null, List.of(), List.of()));

        // [H7-arch Phase 5-2 P3-③] MainLoopDeps 去 LlmAgentLoop 引用 → record(context, modelResolver)
        com.nexusai.application.agent.loop.AgentLoopContext ctx =
            TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = QueryParams.forLoop(
            // [prompt-assembly-A] 2nd arg 类型 String → List<String>（CC SystemPrompt 段数组语义）。
            //   本仓该字段为 vestigial（0 生产读点）→ 测试侧一律 List.of()（= 三条生产调用方同值），
            //   行为与旧传 "sys" 逐位相同（旧值从不被读；且空列表 ⇒ loop 走 per-run 材料收集 = 旧行为）。
            state.rawMessages(), java.util.List.of(),
            com.nexusai.application.agent.tool.ToolUseContext.of(java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
            QuerySource.USER, "test-model", null, null, null, null, null,
            new LlmAgentLoop.MainLoopDeps(ctx, loop::getModelForCall), ProviderConfig.empty());
        // 编译断言：唯一入口签名必须接受 (loop.QueryParams, AgentState, List<String>)
        LoopResult result = LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>());

        assertThat(result).as("queryLoop 必须返回 LoopResult").isNotNull();
        assertThat(result.finalState()).as("LoopResult 必须带 finalState").isNotNull();
    }

    /**
     * loop.QueryParams compact ctor 必须拒绝 null querySource（CC query.ts:189 必传）。
     */
    @Test
    @DisplayName("loop.QueryParams compact ctor 校验 querySource 非空")
    void queryParams_rejectsNullQuerySource() {
        List<ChatMessageDto> messages = List.of(new ChatMessageDto(
            "m1", null, Role.user, "user", "hi", null, List.of(),
            com.nexusai.model.session.dto.FinishReason.stop, null, null, "刚刚",
            java.time.OffsetDateTime.now(), null, null, null, List.of(), List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new QueryParams(
                messages, java.util.List.of(), java.util.Map.of(), java.util.Map.of(),
                // [5b] canUseTool 为第 5 字段（CC query.ts:243 必填；Java 侧 null = 回落内层 gate）；
                // 本测试锁定 compact ctor 的 querySource 非空校验，canUseTool 传 null 不影响校验。
                null,
                com.nexusai.application.agent.tool.ToolUseContext.of(java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                // [IMP2-05] querySourceValue 为第 8 字段（精确值通道，null = 回退 category.canonical()）；
                // 本测试锁定 compact ctor 的 querySource 非空校验，querySourceValue 传 null 不影响校验。
                null, null, null, null, null, null, null, null, ProviderConfig.empty(), "test-model",
                // [D] 第 17 字段 onToolProgress / 第 18 字段 onMessage（均 null → 不影响本校验）
                null, null, null),
            "querySource 为 null 必须抛 IllegalArgumentException");
    }
}
