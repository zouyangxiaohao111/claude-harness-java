package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.recovery.context.ContextConstants;
import com.nexusai.application.agent.recovery.query.QueryConstants;
import com.nexusai.application.agent.recovery.MaxTokensHandler;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [IMP-15 P2] max_tokens 64k 升级 + 多轮恢复 ≤3 集成测试（真实 queryLoop）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>64k 升级生效（DRIFT-10/11）</b> — CC query.ts:1188-1221：首次截断且 gate
 *       {@code tengu_otk_slot_v1} 开启且无 env override → ESCALATED 静默重试（不追加恢复消息）；
 *       重试不截断 → 无恢复消息。旧实现 provider body 恒 4096，升级值从未到达 API（D-29）。</li>
 *   <li><b>多轮恢复 ≤3 回归（MAX_OUTPUT_TOKENS_RECOVERY_LIMIT=3）</b> — CC query.ts:1223-1252：
 *       gate 关闭时直走多轮续写（≤3），耗尽 surface 后 exit=NORMAL（[P-6] CC query.ts:1264
 *       completed），不烧 MAX_TURNS。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: 移除 MaxTokensHandler gate / 恢复无条件 escalate → ESCALATED 测试
 * 退化为多轮恢复（出现恢复消息）→ fail；回退硬编码 4096 使升级无效 → 长输出仍截断 → 恢复消息出现 → fail。
 */
class MaxOutputTokensEscalationIntegrationTest {

    // ─────────────────────── 基础设施 helper ───────────────────────

    /**
     * 最小 AgentLoopContext · maxTokensHandler 注入（record 位置 17）。
     *
     * <p>featureFlags ALL_DISABLED；其余基础设施 null（对齐 PtlRecoveryIntegrationTest.recoveryCtx
     * 结构 · GR-3 后无压缩组件位）。
     */
    /**
     * 创建 MaxTokensHandler 并注入 settings provider（返回 null 表示无 settings.maxOutputTokens
     * override → 64k 升级 gate 可达）。F1 迁移后 gate 判定读 settings.maxOutputTokens（CC
     * env.CLAUDE_CODE_MAX_OUTPUT_TOKENS 等价，E4 发现 C）；无 Spring 上下文时 staticSettingsMapper
     * 未桥接默认即 null，此处显式注入固守"无 override"语义（隔离 CI 环境可能注入的配置）。
     */
    private MaxTokensHandler maxTokensHandler(boolean gate) {
        MaxTokensHandler handler = new MaxTokensHandler(gate);
        handler.setSettingsOverrideProvider(() -> null); // 无 settings.maxOutputTokens override
        return handler;
    }

    private AgentLoopContext recoveryCtx(LlmProviderFactory factory, MaxTokensHandler handler) {
        FeatureFlags flags = new FeatureFlags(false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class), // 1
            null, null, null, null,                                    // 2-5
            null, null, null, null,                                          // 6-9
            null, factory, null, handler, null, null,                                  // 10-15
            null, null, null, null,                                                 // 16-19
            flags, null, null, null, null, null,                                    // 20-25
            null, null, null, null, null, null, null);                                    // 26-32
    }

    /**
     * 每次 LLM 调用按 {@code finishReasons} 返回对应 finishReason 的 assistant message
     * （越界回落最后一项）。onChunk 同步投递使 acc 非空（正常 end_turn 路径可 append 文本）。
     *
     * <p>[IMP-15 REWORK] 同时 stub <b>15-arg stream</b>（override null 常规路径）与
     * <b>16-arg stream</b>（ESCALATED 升级重试 override=64000 → ModelCaller 走 16-arg，
     * 对齐 CC query.ts:1213 + claude.ts:1593-1594）。两签名共享同一应答逻辑
     * （回调位置不同：15-arg onChunk@5/onMsg@6/onComplete@12；16-arg onChunk@6/onMsg@7/onComplete@13）。
     */
    private LlmProviderFactory providerFactory(List<String> finishReasons, AtomicInteger callCount) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        // [IMP-SP-08] ModelCaller 切 blocks 重载（loop 恒经 splitSysPromptPrefix → blocks 发送）。
        // blocks 重载回调位置：onChunk@9/onMsg@10/onComplete@16（maxOutputTokensOverride@5 保持）。
        // 常规路径（override null）
        Mockito.doAnswer(inv -> answerProviderStream(
            inv.getArgument(9), inv.getArgument(10), inv.getArgument(16),
            finishReasons, callCount))
            .when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // [IMP-15 REWORK] 升级重试 override 非 null
        Mockito.doAnswer(inv -> answerProviderStream(
            inv.getArgument(9), inv.getArgument(10), inv.getArgument(16),
            finishReasons, callCount))
            .when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    /** 共享应答：投递 onChunk + onAssistantMessage(finishReason) + onComplete（callCount 递增）。 */
    private Object answerProviderStream(Consumer<String> onChunk, Consumer<AssistantMessage> onMsg,
            Runnable onComplete, List<String> finishReasons, AtomicInteger callCount) {
        int call = callCount.getAndIncrement();
        String text = "response " + call;
        onChunk.accept(text);
        String fr = finishReasons.get(Math.min(call, finishReasons.size() - 1));
        // [ER-IMP-07] 注入 Anthropic 真实信号：raw stop_reason 'max_tokens'/'model_context_window_exceeded'
        //   → 消息级 apiError='max_output_tokens'（对齐 CC claude.ts:2266-2292 归一化产物）。
        //   循环层恢复判定只认 apiError（query.ts:178），不再依赖 finishReason 字符串（DC-21 替换）。
        String apiError = ("max_tokens".equals(fr) || "model_context_window_exceeded".equals(fr))
            ? "max_output_tokens" : null;
        onMsg.accept(new AssistantMessage(text, fr, List.of(), null, apiError));
        onComplete.run();
        return null;
    }

    /**
     * 运行 queryLoop；{@code capturedOverrides} 非 null 时在 deps.callModel 捕获每轮请求的
     * maxOutputTokensOverride（[IMP-15 REWORK] 端到端验证升级值到达 ModelRequest）。
     */
    private void runLoop(AgentLoopContext ctx, AgentState state, Integer maxTurns,
            List<Integer> capturedOverrides) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override
            public com.nexusai.application.agent.loop.ModelResponse callModel(
                    com.nexusai.application.agent.loop.ModelRequest request) {
                if (capturedOverrides != null) {
                    capturedOverrides.add(request.maxOutputTokensOverride());
                }
                return LoopDeps.super.callModel(request);
            }
        };
        QueryParams params = QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", maxTurns, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());
    }

    private AgentState initialState() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m0", "s", com.nexusai.model.session.dto.Role.user, "user",
            "hello", null, List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, List.of(), List.of()));
        return state;
    }

    // ─────────────────────── 用例 ───────────────────────

    /**
     * [验收 #1] 64k 升级生效：gate 开启 → 首次截断静默 ESCALATED 重试 → 重试成功（不截断）→
     * 无恢复消息（CC query.ts:1199-1221 不再截断后无恢复消息）。
     */
    @Test
    @DisplayName("gate 开启 + 首次截断后重试成功 → ESCALATED 静默重试，无恢复消息（64k 升级生效）")
    void escalatedRetry_noRecoveryMessage() {
        AgentState state = initialState();
        AtomicInteger callCount = new AtomicInteger();
        List<Integer> capturedOverrides = new ArrayList<>();
        // 第 1 次调用截断（Anthropic raw stop_reason 'max_tokens' → apiError='max_output_tokens'）
        //   → 升级重试；第 2 次调用成功（end_turn）
        LlmProviderFactory factory = providerFactory(List.of("max_tokens", "end_turn"), callCount);
        AgentLoopContext ctx = recoveryCtx(factory, maxTokensHandler(true));

        runLoop(ctx, state, 8, capturedOverrides);

        // ① 升级触发已由下方 capturedOverrides==[null,64000] 端到端证实时（CC query.ts:1213
        //   maxOutputTokensOverride: ESCALATED_MAX_TOKENS），不再断言 RecoveryState.hasEscalated
        //   —— DC-22 已删 hasEscalated 粘性字段，升级信号活在 override 参数（CC query.ts:1201
        //   maxOutputTokensOverride === undefined re-arm）。
        // ② 升级后重试成功 → 无恢复消息（不再截断后无恢复消息）
        assertThat(state.messages().stream().map(ChatMessageDto::content))
            .as("升级重试成功（不截断）→ 不得追加续写恢复消息（CC query.ts:1223 只在重试仍截断时追加）")
            .doesNotContain("Output token limit hit. Resume directly — no apology, no recap of what you were doing. Pick up mid-thought if that is where the cut happened. Break remaining work into smaller pieces.");
        // ③ 正常退出，非 MAX_OUTPUT_TOKENS 耗尽
        assertThat(state.exitReason())
            .as("升级重试成功后正常退出（CC 不 surface 被 withhold 的截断错误）")
            .isNotEqualTo(AgentState.ExitReason.MAX_OUTPUT_TOKENS);
        // ④ 确实发生了一次重试（2 次 LLM 调用）
        assertThat(callCount.get())
            .as("升级 = 静默重试同一请求（CC query.ts:1207-1221），共 2 次 LLM 调用")
            .isEqualTo(2);
        // ⑤ [IMP-15 REWORK 端到端] 重试请求必须携带 maxOutputTokensOverride=64000（ESCALATED_MAX_TOKENS）
        //    —— 升级值真正到达 API（DRIFT-10 升级路径修复）。若无此断言，重试仍 8000（gate 开启
        //    getMaxOutputTokensForModel 被 cap）也会 GREEN —— 该缺口正是反思 §4 的核心缺陷。
        assertThat(capturedOverrides)
            .as("请求序列 override 必须为 [首次=null(按模型解析), 升级重试=64000]（CC query.ts:1213）")
            .containsExactly(null, ContextConstants.ESCALATED_MAX_TOKENS);
    }

    /**
     * [验收 #3] 多轮恢复 ≤3 回归：gate 关闭 → 直走多轮续写（≤3）→ 耗尽 surface MAX_OUTPUT_TOKENS。
     */
    @Test
    @DisplayName("gate 关闭 + 持续截断 → 多轮恢复 ≤3 后耗尽 surface MAX_OUTPUT_TOKENS")
    void multiTurnRecovery_limitedTo3_surfacesExhausted() {
        AgentState state = initialState();
        AtomicInteger callCount = new AtomicInteger();
        // 所有调用都截断（Anthropic raw stop_reason 'max_tokens' → apiError='max_output_tokens'）
        //   → 多轮恢复耗尽
        LlmProviderFactory factory = providerFactory(List.of("max_tokens"), callCount);
        AgentLoopContext ctx = recoveryCtx(factory, maxTokensHandler(false));

        runLoop(ctx, state, 8, null);

        // ① [P-6] 恢复耗尽 surface 后 exit reason = NORMAL（CC query.ts:1263-1264
        //    lastMessage.isApiErrorMessage → executeStopFailureHooks + return {reason:'completed'}），
        //    不再生产 MAX_OUTPUT_TOKENS（query.ts:1254-1256 yield 被 withhold 错误后 completed）。
        assertThat(state.exitReason())
            .as("[P-6] 多轮恢复耗尽 exit reason = NORMAL（CC query.ts:1264 return completed，非 MAX_OUTPUT_TOKENS）")
            .isEqualTo(AgentState.ExitReason.NORMAL);
        // ② 多轮恢复 ≤3：续写恢复消息不超过 MAX_OUTPUT_TOKENS_RECOVERY_LIMIT=3
        long recoveryMessages = state.messages().stream()
            .map(ChatMessageDto::content)
            .filter("Output token limit hit. Resume directly — no apology, no recap of what you were doing. Pick up mid-thought if that is where the cut happened. Break remaining work into smaller pieces."::equals)
            .count();
        assertThat(recoveryMessages)
            .as("续写恢复消息 ≤ MAX_OUTPUT_TOKENS_RECOVERY_LIMIT=3（CC query.ts:164）")
            .isLessThanOrEqualTo(3);
        // ③ [P-8] 恢复迭代不计 turn：turnCount==1（CC query.ts:276 初始 1；恢复类 transition
        //    全保持 turnCount query.ts:1244，仅 genuine next_turn 递增 :1679）
        assertThat(state.turnCount())
            .as("[P-8] 多轮恢复迭代不计 turn，turnCount 保持 1（CC query.ts:276 初始 1 + :1244 恢复保持）")
            .isEqualTo(1);
        // ④ [IMP-15 REWORK] 耗尽必须 surface CC 截断错误 assistant 消息（query.ts:1254-1256 +
        //    claude.ts:2272-2279 content 原文格式），而非只置 exitReason 静默丢弃（半对齐修复）。
        assertThat(state.messages().stream().map(ChatMessageDto::content))
            .as("恢复耗尽后 transcript 必须含 CC 格式 assistant 错误消息（output token maximum）")
            .anyMatch(c -> c != null && c.contains("output token maximum"));
    }

    /**
     * [接线] AgentLoopContext.resolveRecoveryMaxTokens：override 非 null 时下一请求 max_tokens=该值。
     *
     * <p>验证 max_output_tokens 接线（AgentLoopContext）——升级值（ESCALATED_MAX_TOKENS）
     * 能到达下一请求的 max_tokens（DRIFT-10 修复语义）。
     *
     * <p><b>[ER-IMP-07 / DC-22]</b>：升级信号从 RecoveryState.hasEscalated 粘性字段改为
     * {@code maxOutputTokensOverride} 参数（CC query.ts:1201 maxOutputTokensOverride === undefined
     * re-arm）。接线判定 = override != null 时返回该值，否则按模型解析。
     */
    @Test
    @DisplayName("接线：override=64000 时 resolveRecoveryMaxTokens 返回 64000；override=null 委托 getMaxOutputTokensForModel")
    void wiring_resolveRecoveryMaxTokens() {
        // override=null（未升级）→ 委托 getMaxOutputTokensForModel（模型族 default + settings
        //   maxOutputTokens 有界 override 的模型解析，F1 迁移后无 env——断言与 AnthropicSdkProvider
        //   实际解析一致，不硬编码配置相关值）
        int expectedDefault = com.nexusai.infra.llm.AnthropicSdkProvider.getMaxOutputTokensForModel("claude-sonnet-4-6");
        assertThat(AgentLoopContext.resolveRecoveryMaxTokens("claude-sonnet-4-6", null))
            .as("override=null（未升级）委托 getMaxOutputTokensForModel（模型族 default + settings.maxOutputTokens 有界 override）")
            .isEqualTo(expectedDefault);
        // override=64000（升级）→ 64000（ESCALATED_MAX_TOKENS，环境无关）
        assertThat(AgentLoopContext.resolveRecoveryMaxTokens("claude-sonnet-4-6",
                ContextConstants.ESCALATED_MAX_TOKENS))
            .as("override=ESCALATED_MAX_TOKENS 时下一请求 max_tokens=64000（DRIFT-10 修复）")
            .isEqualTo(ContextConstants.ESCALATED_MAX_TOKENS);
    }
}
