package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩警告抑制态 STOMP 通道测试 · IMP-BACK-3（decisions-log §32「前端联动 · token_warning 事件契约」）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>: A4 探查 ✗-R3 登记 Java 抑制态读侧
 * {@code isCompactWarningSuppressed()} 无生产消费（悬空）——CC 读侧由前端 TokenWarning.tsx 订阅
 * {@code compactWarningStore.getState()}（compactWarningHook.ts:11-16），Java 端此前无桥暴露。
 * IMP-BACK-3 补 STOMP 通道，本测试锁定通道的 3 触发点 + store 订阅语义：
 * <ol>
 *   <li><b>触发点 1</b>：压缩成功 {@code suppressCompactWarning()} → 推
 *       {@code AgentEvent.TokenWarning(suppressed=true)}（对齐 CC compactWarningState.ts:11-13）;</li>
 *   <li><b>触发点 2</b>：新压缩开始 {@code clearCompactWarningSuppression()} → 推
 *       {@code TokenWarning(suppressed=false)}（对齐 compactWarningState.ts:16-18）;</li>
 *   <li><b>触发点 3</b>：上下文接近阈值 {@code publishTokenWarning(...)} → 推带完整
 *       tokenUsage/contextWindow/percentLeft 的 TokenWarning（对齐 CC TokenWarning.tsx:10 props）;</li>
 *   <li><b>store 订阅语义</b>：{@code subscribe} 仅状态实际变化时通知（对齐 CC createStore setState
 *       「值未变不触发 listener」，store.ts:14-17）——幂等 suppress 不重复推送。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: 基线 {@code isCompactWarningSuppressed()} 仅 3 方法（suppress/clear/is）无
 * subscribe/registerPushContext/publishTokenWarning → 本测试编译失败 = 通道未实现。
 */
class CompactWarningStateTest {

    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /** 记录 STOMP 推送的 TokenWarning 载荷（模拟前端订阅收到的 token_warning 事件）。 */
    private final List<AgentEvent.TokenWarning> pushes = new ArrayList<>();
    /** 记录订阅者收到的抑制态通知。 */
    private final List<Boolean> notifications = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void resetModuleState() {
        CompactWarningState.resetForTesting();
    }

    private CompactWarningState.SessionPushContext sessionPushContext() {
        return new CompactWarningState.SessionPushContext(SESSION, pushes::add);
    }

    // ── 触发点 1：压缩成功 → suppressed=true ──

    @Test
    @DisplayName("触发点1: 压缩成功 suppressCompactWarning → 推 token_warning(suppressed=true, sessionId 对齐)")
    void trigger1_compactSuccess_pushesSuppressedTrue() {
        CompactWarningState.registerPushContext(sessionPushContext());

        CompactWarningState.suppressCompactWarning();

        assertThat(pushes).hasSize(1);
        AgentEvent.TokenWarning w = pushes.get(0);
        assertThat(w.eventType()).isEqualTo(AgentEvent.TokenWarning.EVENT_TYPE); // "token_warning" 后端定
        assertThat(w.sessionId()).isEqualTo(SESSION);
        assertThat(w.suppressed()).isTrue(); // 对齐 CC compactWarningStore=true
        assertThat(CompactWarningState.isCompactWarningSuppressed()).isTrue();
    }

    @Test
    @DisplayName("幂等: 已抑制再 suppress 不重复推送（对齐 CC setState 值未变不触发）")
    void suppressWhenAlreadySuppressed_isIdempotentNoDuplicatePush() {
        CompactWarningState.registerPushContext(sessionPushContext());

        CompactWarningState.suppressCompactWarning();
        CompactWarningState.suppressCompactWarning(); // 幂等：值未变

        assertThat(pushes).hasSize(1); // 只推一次
        assertThat(CompactWarningState.isCompactWarningSuppressed()).isTrue();
    }

    // ── 触发点 2：新压缩开始 → suppressed=false ──

    @Test
    @DisplayName("触发点2: 新压缩开始 clearCompactWarningSuppression → 推 token_warning(suppressed=false)")
    void trigger2_compactStart_pushesSuppressedFalse() {
        CompactWarningState.registerPushContext(sessionPushContext());
        CompactWarningState.suppressCompactWarning(); // 先抑制
        assertThat(pushes).hasSize(1);

        CompactWarningState.clearCompactWarningSuppression();

        assertThat(pushes).hasSize(2);
        AgentEvent.TokenWarning w = pushes.get(1);
        assertThat(w.sessionId()).isEqualTo(SESSION);
        assertThat(w.suppressed()).isFalse(); // 对齐 CC clearCompactWarningSuppression → false
        assertThat(CompactWarningState.isCompactWarningSuppressed()).isFalse();
    }

    // ── 触发点 3：上下文接近阈值 → 推 token 用量 ──

    @Test
    @DisplayName("触发点3: 上下文接近阈值 publishTokenWarning 推完整 token 数据（对齐 TokenWarning.tsx props）")
    void trigger3_nearThreshold_pushesTokenUsageWithFullData() {
        CompactWarningState.registerPushContext(sessionPushContext());

        CompactWarningState.publishTokenWarning(SESSION, false, 45_000L, 200_000L, 78);

        assertThat(pushes).hasSize(1);
        AgentEvent.TokenWarning w = pushes.get(0);
        assertThat(w.eventType()).isEqualTo(AgentEvent.TokenWarning.EVENT_TYPE);
        assertThat(w.suppressed()).isFalse();   // 对齐 compactWarningStore 当前值
        assertThat(w.tokenUsage()).isEqualTo(45_000L);  // 对齐 TokenWarning.tsx:10 props tokenUsage
        assertThat(w.contextWindow()).isEqualTo(200_000L); // 对齐 getEffectiveContextWindowSize → effectiveWindow
        assertThat(w.percentLeft()).isEqualTo(78);  // 对齐 displayPercentLeft
    }

    @Test
    @DisplayName("触发点3: percentLeft 可省略（null 时前端自行计算，对齐 TokenWarning.tsx:127/:154）")
    void trigger3_percentLeftOptional_nullAllowed() {
        CompactWarningState.registerPushContext(sessionPushContext());

        CompactWarningState.publishTokenWarning(SESSION, true, 0L, 200_000L, null);

        AgentEvent.TokenWarning w = pushes.get(0);
        assertThat(w.percentLeft()).isNull();
        assertThat(w.suppressed()).isTrue();
    }

    // ── store 订阅语义（对齐 CC createStore.subscribe）──

    @Test
    @DisplayName("store 订阅: suppress/clear 状态变化时通知订阅者（对齐 CC createStore.subscribe）")
    void subscribe_notifiedOnStateChange() {
        Runnable unsubscribe = CompactWarningState.subscribe(notifications::add);

        CompactWarningState.suppressCompactWarning();
        CompactWarningState.clearCompactWarningSuppression();

        assertThat(notifications).containsExactly(true, false); // 每次状态变化通知最新值
        unsubscribe.run();
    }

    @Test
    @DisplayName("store 订阅: 取消订阅后不再通知（对齐 CC subscribe 返回 unsubscribe）")
    void subscribe_unsubscribe_stopsNotification() {
        Runnable unsubscribe = CompactWarningState.subscribe(notifications::add);
        unsubscribe.run();

        CompactWarningState.suppressCompactWarning();

        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("store 订阅: 值未变不通知（对齐 CC setState Object.is(next,prev) 短路）")
    void subscribe_noNotifyWhenValueUnchanged() {
        CompactWarningState.subscribe(notifications::add);

        CompactWarningState.suppressCompactWarning();
        CompactWarningState.suppressCompactWarning(); // 幂等 → 不通知
        CompactWarningState.clearCompactWarningSuppression();
        CompactWarningState.clearCompactWarningSuppression(); // 幂等 → 不通知

        assertThat(notifications).containsExactly(true, false);
    }

    // ── 会话推送上下文隔离 / 无上下文安全跳过 ──

    @Test
    @DisplayName("通道: 无会话推送上下文时推送安全跳过（非 STOMP 路径不抛异常，store 仍推进）")
    void noPushContext_safeNoOp() {
        // 未注册 registerPushContext
        CompactWarningState.suppressCompactWarning();
        CompactWarningState.publishTokenWarning(SESSION, true, 45_000L, 200_000L, null);

        assertThat(pushes).isEmpty(); // 无 STOMP 发送器 → 不推
        assertThat(CompactWarningState.isCompactWarningSuppressed()).isTrue(); // store 行为不回归
    }

    @Test
    @DisplayName("通道: 会话推送上下文线程隔离（clearPushContext 后不推，对齐 CacheSafeParamsHolder）")
    void clearPushContext_stopsPush() {
        CompactWarningState.registerPushContext(sessionPushContext());
        CompactWarningState.clearPushContext();

        CompactWarningState.suppressCompactWarning();

        assertThat(pushes).isEmpty();
    }
}
