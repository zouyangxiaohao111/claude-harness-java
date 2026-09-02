package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.ContextCollapse;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [IMP-02 P0] PTL 恢复路径集成测试 · 对齐 CC query.ts:1085-1183 + compact.ts:227/293。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>典型 400/413 PTL 不再被流式错误抑制方法清错+continue 拦截</b> —
 *       OD-08 裁决：删除 PTL 抑制，非 suppressed PTL 路由到 CC 恢复路径（collapse drain →
 *       reactive compact）。若拦截仍存在，persistent PTL 将烧毁 MAX_TURNS=8（P0 风险 06 §10-1），
 *       本测试断言 exitReason == PROMPT_TOO_LONG（而非 MAX_TURNS / STREAM_ERROR）→ RED。</li>
 *   <li><b>恢复失败即 surface，不落入 stop hooks</b> — CC query.ts:1168-1175 错误消息不触发
 *       hook blocking（防死亡螺旋）。</li>
 *   <li><b>media 恢复仅消息级（[P-11] 对齐修正）</b> — CC errors.ts:147-153 isMediaSizeErrorMessage +
 *       query.ts:1082-1084 isWithheldMedia：恢复链只消费 withheld 消息；异常级（Java provider 现以
 *       LlmApiException Kind.IMAGE 表达）直 surface image_error（不进 strip-retry/应急压缩）。</li>
 *   <li><b>MAX_PTL_RETRIES=3 独立重试上限替代 MAX_TURNS 烧毁</b> — compact.ts:227；PTL 恢复
 *       由 single-shot 守卫（hasAttemptedReactiveCompact / collapse_drain_retry）限定，不再消耗
 *       主 turn 预算。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert LlmAgentLoop 流式错误抑制（把 PTL 重新纳入 suppress+continue）
 * → 本测试 exitReason 变为 MAX_TURNS → fail。
 */
class PtlRecoveryIntegrationTest {

    // ─────────────────────── 基础设施 helper ───────────────────────

    private List<ChatMessageDto> snipableMessages() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            msgs.add(new ChatMessageDto(
                "m" + i, "s", Role.user, "user", "content " + i, null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of()));
        }
        return msgs;
    }

    /** 三 feature 全开：REACTIVE_COMPACT + CONTEXT_COLLAPSE · 对齐 CC feature() flag 开启。 */
    private AgentLoopContext recoveryCtx(LlmProviderFactory factory) {
        return recoveryCtx(factory, null);
    }

    /** 同构 ctx + hookRegistry（§14 STOP 流水线 / STOP_FAILURE 事件测试用 · IMP-02 REWORK）。 */
    private AgentLoopContext recoveryCtx(LlmProviderFactory factory, HookRegistry hookRegistry) {
        FeatureFlags flags = new FeatureFlags(true, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        ContextCollapse collapse = new ContextCollapse(flags);
        // 用户算法（OD-01 #3）：tryReactiveCompact 复用 CompactCallback.summarize 生成摘要；null callback
        // 会 fail-loud 返回 null（无法压缩消息）→ 集成用例断言「消息数 < 原始」会红。故注入 stub 摘要回调
        // 保 reactive compact 成功路径覆盖（60 条 → tail_start=55，摘要 1 + 尾部 5 = 6 条 < 60）。
        ReactiveCompactor rc = new ReactiveCompactor(
            new TokenEstimator()::estimateMessageTokens,
            (prompt, msgs) -> new CompactConversation.SummaryResult("reactive summary stub", null));
        rc.setEnabled(true);
        // AgentLoopContext · 位置与 TestContexts.agentLoopContext 同构（feature 三件套，GR-3 后无压缩组件位）
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class),  // 1
            hookRegistry,                                                          // 2
            null, null, null, null, null, null, null, null,     // 3-10
            factory, null, null, null, null, null, null, null, null,           // 11-19
            flags, rc, collapse,                                                   // 20-22
            null, null, null, null, null, null, null, null, null, null);                // 23-32
    }

    private LlmProviderFactory providerFactory(Consumer<Consumer<Throwable>> errorBehavior) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        // [IMP-SP-08] blocks 重载：onErr@15/onComplete@16
        Mockito.doAnswer(inv -> {
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            errorBehavior.accept(onErr);
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    private LlmProviderFactory persistentPtlProvider() {
        // [IMP-A4-2 · A-24 isPtlError 收窄] CC 真源 PTL 错误（errors.ts:562-564 仅认
        //   error.message.toLowerCase().includes('prompt is too long')）——旧触发串
        //   "prompt_too_long: conversation exceeds context window"（下划线变体）是 A-24 拍板
        //   消除的异常级误触发类（CC 不会把它转成 'Prompt is too long' 消息，isWithheld413 不命中）。
        return providerFactory(onErr -> onErr.accept(
            new LlmApiException(413, Collections.emptyMap(),
                "prompt is too long: 137500 tokens > 135000 maximum")));
    }

    private LlmProviderFactory persistentImageErrorProvider() {
        return providerFactory(onErr -> onErr.accept(
            LlmApiException.imageError(413, Collections.emptyMap(), "image_too_large: image dimensions exceed limit")));
    }

    /** [P-11 生产生产者] CC 媒体尺寸错误（status 400 + 'image exceeds' + 'maximum' · errors.ts:612-623）。 */
    private LlmProviderFactory persistentCcMediaSizeErrorProvider() {
        return providerFactory(onErr -> onErr.accept(
            LlmApiException.imageError(400, Collections.emptyMap(),
                "image exceeds 5 MB maximum: 5316852 bytes > 5242880 bytes")));
    }

    private void runLoop(AgentLoopContext ctx, AgentState state, Integer maxTurns) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        QueryParams params = QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", maxTurns, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());
    }

    // ─────────────────────── 用例 ───────────────────────

    @Test
    @DisplayName("persistent PTL → 走恢复路径（collapse drain → reactive compact）→ 恢复耗尽 surface PROMPT_TOO_LONG，非 MAX_TURNS/STREAM_ERROR")
    void persistentPtl_routesToRecoveryPath_andSurfacesPromptTooLong() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        List<ChatMessageDto> original = snipableMessages();
        original.forEach(state::appendMessage);

        AgentLoopContext ctx = recoveryCtx(persistentPtlProvider());

        runLoop(ctx, state, 8);

        // 恢复路径成功触发（消息被 collapse drain / reactive compact 压缩）
        assertThat(state.messages().size())
            .as("PTL 必须走恢复路径（collapse drain / reactive compact 压缩消息）· CC query.ts:1086-1166")
            .isLessThan(original.size());
        // 恢复失败即 surface（非 STREAM_ERROR 退化 · D-25 删除前置不变量）
        assertThat(state.exitReason())
            .as("PTL 恢复耗尽必须 surface PROMPT_TOO_LONG（CC query.ts:1175 return prompt_too_long），非 STREAM_ERROR")
            .isEqualTo(AgentState.ExitReason.PROMPT_TOO_LONG);
        // 非 MAX_TURNS=8 烧毁（OD-08：MAX_PTL_RETRIES=3 独立上限替代 MAX_TURNS）
        assertThat(state.exitReason())
            .as("不得烧毁 MAX_TURNS（OD-08 裁决：PTL 恢复独立于主 turn 预算）")
            .isNotEqualTo(AgentState.ExitReason.MAX_TURNS);
        assertThat(state.turnCount())
            .as("PTL 恢复在 3 次内 surface（MAX_PTL_RETRIES=3 · compact.ts:227），不得烧满 maxTurns")
            .isLessThan(8);
    }

    @Test
    @DisplayName("[P-11] 异常级 media(image) error → 直 surface IMAGE_ERROR（不进 strip-retry/应急压缩）· CC errors.ts:147-153 仅消息级恢复")
    void exceptionLevelMediaError_directSurface_imageError() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        List<ChatMessageDto> original = snipableMessages();
        original.forEach(state::appendMessage);

        AgentLoopContext ctx = recoveryCtx(persistentImageErrorProvider());

        runLoop(ctx, state, 8);
        // [P-11] CC 恢复链只消费 withheld 消息（query.ts:1082-1084 isWithheldMedia =
        //   mediaRecoveryEnabled && isWithheldMediaSizeError(lastMessage)）；异常级（Java provider
        //   现以 LlmApiException Kind.IMAGE 表达）不进入恢复路径 → 无 reactive compact。
        //   60→51/52 为循环顶 applyCollapsesIfNeeded（CC query.ts:440-447）对 60 条可 snip 消息的
        //   L2 Snip 投影（snip 条数与迭代序相关，区间断言防抖动）；reactive compact stub 命中
        //   会进一步缩到 ~6 条，故 size > 20 即证明恢复链未触发。
        assertThat(state.messages().size())
            .as("[P-11] 异常级 media 错误直 surface：仅循环顶压缩投影（60→~51），不得触发 reactive compact（~6 条）· CC errors.ts:147-153")
            .isGreaterThan(20);
        // surface 语义对齐 CC query.ts:1175 isWithheldMedia ? 'image_error'
        assertThat(state.exitReason())
            .as("[P-11] 异常级 media 错误直 surface IMAGE_ERROR（CC query.ts:1175 return image_error）")
            .isEqualTo(AgentState.ExitReason.IMAGE_ERROR);
        // 不得烧毁 MAX_TURNS（直 surface 单次退出 · OD-08 独立上限语义保持）
        assertThat(state.exitReason())
            .as("不得烧毁 MAX_TURNS（直 surface 单次退出）")
            .isNotEqualTo(AgentState.ExitReason.MAX_TURNS);
        assertThat(state.turnCount())
            .as("直 surface 不计 turn 消耗（turnCount 保持初始 1 · CC query.ts:276）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("[P-11 生产生产者] CC 媒体尺寸错误（400 image exceeds+maximum）→ 消息级转换 → reactive compact 恢复链 → 耗尽 surface IMAGE_ERROR · CC claude.ts:2743/2801 + errors.ts:612-623")
    void ccMediaSizeError_messageLevelConversion_routesToReactiveCompact() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        List<ChatMessageDto> original = snipableMessages();
        original.forEach(state::appendMessage);

        AgentLoopContext ctx = recoveryCtx(persistentCcMediaSizeErrorProvider());

        runLoop(ctx, state, 8);

        // 生产生产者（createMediaSizeErrorApiMessage）把异常级 media 错误转消息级（errorDetails 命中
        // CC 子串 errors.ts:133-139）→ isMediaError 命中 → reactive compact 恢复链触发（60 条压缩
        // 到 ~6）。若断链（errorDetails 无生产者），恢复链不触发 → 消息数保持 >20（同
        // exceptionLevelMediaError_directSurface 断言）→ 本断言 RED。
        assertThat(state.messages().size())
            .as("[P-11 生产生产者] CC 媒体尺寸错误必须走消息级恢复链（reactive compact 压缩消息数），"
                + "非异常级直 surface（size 保持 >20）· CC claude.ts:2743/2801 + errors.ts:612-623")
            .isLessThan(original.size());
        // 恢复耗尽（hasAttemptedReactiveCompact 单次限制 · query.ts:1154）→ surface IMAGE_ERROR · query.ts:1175
        assertThat(state.exitReason())
            .as("[P-11] 媒体恢复耗尽 surface IMAGE_ERROR（CC query.ts:1175 return image_error）")
            .isEqualTo(AgentState.ExitReason.IMAGE_ERROR);
        assertThat(state.exitReason())
            .as("不得烧毁 MAX_TURNS（恢复单次限制 · OD-08）")
            .isNotEqualTo(AgentState.ExitReason.MAX_TURNS);
        assertThat(state.turnCount())
            .as("恢复链在单次限制内收敛（compact 一次 + surface），不得烧满 maxTurns")
            .isLessThan(8);
    }

    /**
     * [IMP-02 REWORK #3] 验收标准 #7：PTL 恢复失败不落入 stop hooks（防死亡螺旋）。
     *
     * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
     * CC query.ts:1168-1182 对 PTL/media 恢复失败「提前 return」，明确不落入
     * handleStopHooks（query.ts:1267）—— 注释明示死亡螺旋：error → hook blocking →
     * retry → error → …（hook 每轮注入更多 token）。若不门控，blocking stop hook 会让
     * Java {@code LlmAgentLoop} §14 :3357-3365 append user message 并递归重入 loop；
     * 因 hasAttemptedReactiveCompact=true 恢复已耗尽，重入后再次 PTL → surface → 再
     * blocking → 再重入…… 被 MAX_TURNS=8 兜底烧毁，复现 IMP-02 本应消除的 P0。
     *
     * <p><b>RED teeth</b>: 删除 §14 门控（恢复失败出口仍走 executeEvent(stopEvent)）
     * → blocking stop hook 被调用、blocking user message 被 append、exitReason 变为
     * MAX_TURNS → 本测试 fail。
     */
    @Test
    @DisplayName("persistent PTL + blocking stop hook → 跳过 STOP 流水线：不触发 blocking hook、不 append blocking user message、不烧 MAX_TURNS")
    void persistentPtl_withBlockingStopHook_skipsStopPipeline() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        snipableMessages().forEach(state::appendMessage);

        // 注册返回 blockingError 的 STOP hook · 对齐 CC stopHooks.ts 的 blockingErrors 通道
        HookRegistry hookRegistry = new HookRegistry();
        AtomicInteger stopBlockCalls = new AtomicInteger();
        hookRegistry.register("stop-blocker", event -> {
            stopBlockCalls.incrementAndGet();
            return GenericHook.HookResult.stop("blocked-by-hook", "hook_blocking: death spiral guard");
        }, HookEventType.STOP);

        AgentLoopContext ctx = recoveryCtx(persistentPtlProvider(), hookRegistry);

        runLoop(ctx, state, 8);

        // ① blocking stop hook 不得被触发（§14 executeEvent(stopEvent) 被门控跳过 · CC query.ts:1174-1182 提前 return）
        assertThat(stopBlockCalls.get())
            .as("PTL 恢复失败必须跳过 STOP 流水线：blocking stop hook 不得被调用（防死亡螺旋 · CC query.ts:1168-1182）")
            .isZero();
        // ② 不 append blocking user message（§14 :3362 appendMessage 重入通道被门控）
        assertThat(state.messages().stream().map(ChatMessageDto::content))
            .as("不得 append blocking user message（hook blockingError 注入 LLM 的重入通道被门控）")
            .doesNotContain("hook_blocking: death spiral guard");
        // ③ 以 PROMPT_TOO_LONG 退出，非 MAX_TURNS 烧毁
        assertThat(state.exitReason())
            .as("PTL 恢复耗尽必须 surface PROMPT_TOO_LONG（CC query.ts:1175 return prompt_too_long），非 MAX_TURNS 烧毁")
            .isEqualTo(AgentState.ExitReason.PROMPT_TOO_LONG);
        assertThat(state.exitReason())
            .as("不得烧毁 MAX_TURNS（死亡螺旋被 §14 门控阻断 · CC query.ts:1171-1172）")
            .isNotEqualTo(AgentState.ExitReason.MAX_TURNS);
        // ④ 不重入循环（turnCount 不烧满 maxTurns）
        assertThat(state.turnCount())
            .as("不得重入循环烧毁 MAX_TURNS（§14 门控阻断 blockingError 重入）")
            .isLessThan(8);
    }

    /**
     * [IMP-02 REWORK #2] PTL 恢复失败出口触发 STOP_FAILURE 事件 · 对齐 CC executeStopFailureHooks
     * (utils/hooks.ts:3594-3627)。
     *
     * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
     * CC query.ts:1174/1181 在提前 return 前 {@code void executeStopFailureHooks(lastMessage, toolUseContext)}
     * —— 用轻量 StopFailure 事件通知外部（区别于会重入的 Stop 流水线），且受
     * {@code hasHookForEvent('StopFailure')}（hooks.ts:3604）门控。Java 若缺位，PTL/media
     * 退出时外部 hook 完全收不到失败信号（hook 观测缺失）；若门控失效，无 StopFailure 监听时
     * 也会空跑 executeEvent（对齐度漂移）。
     *
     * <p><b>RED teeth</b>: 移除 hasHookForEvent("StopFailure") 门控 → 本测试 stopFailureCalls 仍为
     * 1（不 RED）；删除整个 STOP_FAILURE 触发块 → stopFailureCalls == 0 → fail。
     */
    @Test
    @DisplayName("persistent PTL 恢复失败 → STOP_FAILURE 事件触发（CC executeStopFailureHooks · hasHookForEvent('StopFailure') 门控）")
    void persistentPtl_firesStopFailureEvent() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        snipableMessages().forEach(state::appendMessage);

        // 注册 STOP_FAILURE 观察 hook · 验证 hasHookForEvent("StopFailure") 门控通过后事件真正分发
        HookRegistry hookRegistry = new HookRegistry();
        AtomicInteger stopFailureCalls = new AtomicInteger();
        AtomicReference<HookEvent> captured = new AtomicReference<>();
        hookRegistry.register("stop-failure-observer", event -> {
            stopFailureCalls.incrementAndGet();
            captured.set(event);
            return null; // 观察者：不干预结果
        }, HookEventType.STOP_FAILURE);

        AgentLoopContext ctx = recoveryCtx(persistentPtlProvider(), hookRegistry);

        runLoop(ctx, state, 8);

        assertThat(stopFailureCalls.get())
            .as("PTL 恢复失败必须触发 STOP_FAILURE 事件（CC executeStopFailureHooks hooks.ts:3594-3627 · query.ts:1174）")
            .isEqualTo(1);
        assertThat(captured.get())
            .as("STOP_FAILURE 事件必须携带 error/last_assistant_message 载荷")
            .isNotNull();
        assertThat(captured.get().type())
            .as("事件类型必须是 STOP_FAILURE（HookEvent.stopFailure · HookEventType.STOP_FAILURE）")
            .isEqualTo(HookEventType.STOP_FAILURE);
        // 仍以 PROMPT_TOO_LONG 退出（STOP_FAILURE 是轻量通知，不改变 exitReason）
        assertThat(state.exitReason())
            .as("STOP_FAILURE 事件不改变 exitReason（CC 提前 return prompt_too_long 语义）")
            .isEqualTo(AgentState.ExitReason.PROMPT_TOO_LONG);
    }
}
