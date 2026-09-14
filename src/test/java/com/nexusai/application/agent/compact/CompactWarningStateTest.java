package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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
 * subscribe/publishTokenWarning → 本测试编译失败 = 通道未实现。
 *
 * <p><b>[批 5a-2]</b> push 上下文载体由 ThreadLocal 改为**显式实参**（每次调用传入）⇒ 相关
 * 用例已同步改为「显式传 ctx / 显式传 null」两态断言（原 clearPushContext 用例已重表达）。
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

        CompactWarningState.suppressCompactWarning(SESSION, sessionPushContext());

        assertThat(pushes).hasSize(1);
        AgentEvent.TokenWarning w = pushes.get(0);
        assertThat(w.eventType()).isEqualTo(AgentEvent.TokenWarning.EVENT_TYPE); // "token_warning" 后端定
        assertThat(w.sessionId()).isEqualTo(SESSION);
        assertThat(w.suppressed()).isTrue(); // 对齐 CC compactWarningStore=true
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION)).isTrue();
    }

    @Test
    @DisplayName("幂等: 已抑制再 suppress 不重复推送（对齐 CC setState 值未变不触发）")
    void suppressWhenAlreadySuppressed_isIdempotentNoDuplicatePush() {

        CompactWarningState.suppressCompactWarning(SESSION, sessionPushContext());
        CompactWarningState.suppressCompactWarning(SESSION, sessionPushContext()); // 幂等：值未变

        assertThat(pushes).hasSize(1); // 只推一次
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION)).isTrue();
    }

    // ── 触发点 2：新压缩开始 → suppressed=false ──

    @Test
    @DisplayName("触发点2: 新压缩开始 clearCompactWarningSuppression → 推 token_warning(suppressed=false)")
    void trigger2_compactStart_pushesSuppressedFalse() {
        CompactWarningState.suppressCompactWarning(SESSION, sessionPushContext()); // 先抑制
        assertThat(pushes).hasSize(1);

        CompactWarningState.clearCompactWarningSuppression(SESSION, sessionPushContext());

        assertThat(pushes).hasSize(2);
        AgentEvent.TokenWarning w = pushes.get(1);
        assertThat(w.sessionId()).isEqualTo(SESSION);
        assertThat(w.suppressed()).isFalse(); // 对齐 CC clearCompactWarningSuppression → false
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION)).isFalse();
    }

    // ── 触发点 3：上下文接近阈值 → 推 token 用量 ──

    @Test
    @DisplayName("触发点3: 上下文接近阈值 publishTokenWarning 推完整 token 数据（对齐 TokenWarning.tsx props）")
    void trigger3_nearThreshold_pushesTokenUsageWithFullData() {

        CompactWarningState.publishTokenWarning(sessionPushContext(), false, 45_000L, 200_000L, 78);

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

        CompactWarningState.publishTokenWarning(sessionPushContext(), true, 0L, 200_000L, null);

        AgentEvent.TokenWarning w = pushes.get(0);
        assertThat(w.percentLeft()).isNull();
        assertThat(w.suppressed()).isTrue();
    }

    // ── store 订阅语义（对齐 CC createStore.subscribe）──

    @Test
    @DisplayName("store 订阅: suppress/clear 状态变化时通知订阅者（对齐 CC createStore.subscribe）")
    void subscribe_notifiedOnStateChange() {
        Runnable unsubscribe = CompactWarningState.subscribe(notifications::add);

        CompactWarningState.suppressCompactWarning(SESSION, sessionPushContext());
        CompactWarningState.clearCompactWarningSuppression(SESSION, sessionPushContext());

        assertThat(notifications).containsExactly(true, false); // 每次状态变化通知最新值
        unsubscribe.run();
    }

    @Test
    @DisplayName("store 订阅: 取消订阅后不再通知（对齐 CC subscribe 返回 unsubscribe）")
    void subscribe_unsubscribe_stopsNotification() {
        Runnable unsubscribe = CompactWarningState.subscribe(notifications::add);
        unsubscribe.run();

        CompactWarningState.suppressCompactWarning(SESSION, sessionPushContext());

        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("store 订阅: 值未变不通知（对齐 CC setState Object.is(next,prev) 短路）")
    void subscribe_noNotifyWhenValueUnchanged() {
        CompactWarningState.subscribe(notifications::add);

        CompactWarningState.suppressCompactWarning(SESSION, sessionPushContext());
        CompactWarningState.suppressCompactWarning(SESSION, sessionPushContext()); // 幂等 → 不通知
        CompactWarningState.clearCompactWarningSuppression(SESSION, sessionPushContext());
        CompactWarningState.clearCompactWarningSuppression(SESSION, sessionPushContext()); // 幂等 → 不通知

        assertThat(notifications).containsExactly(true, false);
    }

    // ── 会话推送上下文隔离 / 无上下文安全跳过 ──

    @Test
    @DisplayName("通道: 无会话推送上下文时推送安全跳过（非 STOMP 路径不抛异常，store 仍推进）")
    void noPushContext_safeNoOp() {
        // [批 5a-2] 「无推送上下文」= 显式传 null（原「未 registerPushContext」）
        CompactWarningState.suppressCompactWarning(SESSION, null);
        CompactWarningState.publishTokenWarning(null, true, 45_000L, 200_000L, null);

        assertThat(pushes).isEmpty(); // 无 STOMP 发送器 → 不推
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION)).isTrue(); // store 行为不回归
    }

    @Test
    @DisplayName("[批 5a-2] 显式实参语义：传 null 不推 / 传真实 ctx 推（原「clearPushContext 后不推」的重表达）")
    void explicitNullPushContext_stopsPush() {
        // 原用例断言已删除的 ThreadLocal 槽位（clearPushContext 后 current() 为 null ⇒ 不推）。
        // 新语义 = push 上下文是**每次调用的显式实参**：传 null ⇒ 不推（「进程内残留」这一态随载体消失）。
        CompactWarningState.suppressCompactWarning(SESSION, null);
        assertThat(pushes).as("显式传 null ⇒ 一条都不推；store 仍推进").isEmpty();
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION)).isTrue();

        // 正向对照：传真实 ctx ⇒ 会推（证明上面的空不是「幂等未触发」造成的）
        CompactWarningState.clearCompactWarningSuppression(SESSION, sessionPushContext());
        assertThat(pushes).as("对照：传真实 ctx ⇒ 推（区分「无通道」与「幂等未触发」）").hasSize(1);
    }
    /**
     * [批 5a-2] 挂 WARN 级 logback appender（本用例只关心 ≥WARN 是否留痕）。
     */
    private static ListAppender<ILoggingEvent> attachWarnCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CompactWarningState.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static long warnCount(ListAppender<ILoggingEvent> app) {
        return app.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).count();
    }

    @Test
    @DisplayName("[批 5a-2] (b) 类可跳过但必须 ≥WARN：无推送上下文时两条推送路径都留 WARN 痕（原为仅 DEBUG / 零日志）")
    void nullPushContext_leavesWarnTrace() {
        ListAppender<ILoggingEvent> app = attachWarnCapture();

        // 触发点 1（publishSuppressedChange 内部）——原实现此处**零日志**直接 return
        CompactWarningState.suppressCompactWarning(SESSION, null);
        assertThat(warnCount(app))
            .as("触发点1 无上下文 ⇒ 必须 ≥WARN（原实现零日志 = 静默吞掉）").isPositive();

        // 触发点 3（publishTokenWarning）——原实现此处**只写 DEBUG**
        app.list.clear();
        CompactWarningState.publishTokenWarning(null, false, 1L, 1L, 0);
        assertThat(warnCount(app))
            .as("触发点3 无上下文 ⇒ 必须 ≥WARN（原实现只写 DEBUG）").isPositive();

        // 正向对照：有上下文时不应产生 WARN（证明上面的 WARN 不是「总会打」的背景噪声）
        app.list.clear();
        CompactWarningState.clearCompactWarningSuppression(SESSION, sessionPushContext());
        CompactWarningState.publishTokenWarning(sessionPushContext(), true, 1L, 1L, 0);
        assertThat(warnCount(app))
            .as("对照：有推送上下文 ⇒ 不产生 WARN（区分本通道告警与背景日志）").isZero();
    }

}
