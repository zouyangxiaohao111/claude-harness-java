package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.classifier.DenialTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T16 · {@code DenialTracker} 单例路径的会话隔离 · 裁定-10 (A) 的验收断言。
 *
 * <p><b>守护什么</b>：CC 的 {@code appState.denialTracking} 挂在<b>进程级 AppState</b>
 * （{@code state/AppStateStore.ts:426}）——在 CC 里安全（单进程单会话）。本仓是<b>一 JVM 多会话</b>，
 * 且<b>主 agent 的每次工具调用都走这个 {@code @Component} 单例</b>
 * （{@code LlmAgentLoop#buildBaseToolUseContext} 对 localDenialTracking 传 null ⇒
 * {@code PermissionPipeline.resolveDenialTracker} 回落）⇒ 若计数是单份实例状态，
 * <b>A 会话的连续拒绝会把 B 会话直接推入 fallback（回退 prompting = 无谓打断用户）</b>。
 *
 * <p><b>正反两向都断言</b>：既要证「B 不被 A 推」，也要证「A 自己确实会被推」
 * （否则「B 不被推」可以靠「谁都推不动」这种恒真实现骗过 —— 与「纯否定断言恒绿」同族）。
 */
class DenialTrackerCrossSessionIsolationTest {

    private static final String SESSION_A = "sess-denial-a";
    private static final String SESSION_B = "sess-denial-b";

    @Test
    @DisplayName("T16: A 会话累计 2 次（未达阈值 3）后，B 会话第 1 次拒绝【不得】触发 fallback；A 第 3 次【必须】触发")
    void sessionB_isNotPushedBySessionA() {
        DenialTracker tracker = new DenialTracker(3, 20);

        assertThat(tracker.recordDenial(SESSION_A).fallback()).isFalse();
        assertThat(tracker.recordDenial(SESSION_A).fallback()).isFalse();

        // 反向方向①：B 的第 1 次拒绝不得被 A 的累计推入 fallback
        DenialTracker.FallbackSnapshot bFirst = tracker.recordDenial(SESSION_B);
        assertThat(bFirst.fallback())
            .as("B 会话首拒不得触发回退（原单份实例字段实现下，A 的 2 次 + B 的 1 次 = 3 ⇒ B 被误推）")
            .isFalse();
        assertThat(bFirst.consecutiveDenials())
            .as("B 的计数必须从 1 起算（不是 A 的 2+1）")
            .isEqualTo(1);
        assertThat(tracker.getConsecutiveDenials(SESSION_B)).isEqualTo(1);
        assertThat(tracker.getConsecutiveDenials(SESSION_A))
            .as("A 的计数不得被 B 的拒绝影响").isEqualTo(2);

        // 反向方向②：A 自己第 3 次【必须】触发（防「谁都推不动」的恒真实现）
        DenialTracker.FallbackSnapshot aThird = tracker.recordDenial(SESSION_A);
        assertThat(aThird.fallback())
            .as("A 会话连续 3 次 ⇒ 必须触发回退（门 1 有鉴别力：本断言保证上面 B 的 false 不是恒真）")
            .isTrue();
        assertThat(tracker.shouldFallbackToPrompting(SESSION_A)).isTrue();
        assertThat(tracker.shouldFallbackToPrompting(SESSION_B))
            .as("A 熔断不得把 B 一起熔断").isFalse();
    }

    @Test
    @DisplayName("T16: A 会话已熔断（3 次）后，B 的首拒仍不触发 fallback，且 B 计数独立从 1 起")
    void alreadyBrokenSessionA_doesNotBreakSessionB() {
        DenialTracker tracker = new DenialTracker(3, 20);
        for (int i = 0; i < 3; i++) {
            tracker.recordDenial(SESSION_A);
        }
        assertThat(tracker.shouldFallbackToPrompting(SESSION_A)).isTrue();

        DenialTracker.FallbackSnapshot b = tracker.recordDenial(SESSION_B);
        assertThat(b.fallback()).isFalse();
        assertThat(b.consecutiveDenials()).isEqualTo(1);
        assertThat(tracker.shouldFallbackToPrompting(SESSION_B)).isFalse();
    }

    @Test
    @DisplayName("T16: allow 断连拒链（recordSuccess）只清本会话的 consecutive，不动他会话")
    void recordSuccess_isPerSession() {
        DenialTracker tracker = new DenialTracker(3, 20);
        tracker.recordDenial(SESSION_A);
        tracker.recordDenial(SESSION_A);
        tracker.recordDenial(SESSION_B);
        tracker.recordDenial(SESSION_B);

        tracker.recordSuccess(SESSION_A);

        assertThat(tracker.getConsecutiveDenials(SESSION_A)).isZero();
        assertThat(tracker.getTotalDenials(SESSION_A))
            .as("recordSuccess 只清 consecutive（total 保留，CC denialTracking.ts:32-38）").isEqualTo(2);
        assertThat(tracker.getConsecutiveDenials(SESSION_B))
            .as("B 的连拒链不得被 A 的 allow 事件断掉（原实现在此归零 ⇒ B 的熔断被 A 的错误解除）")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("T16: removeSession(A) 回收 A 的计数槽且不影响 B（容量/清理路径）")
    void removeSession_reclaimsOnlyThatSession() {
        DenialTracker tracker = new DenialTracker(3, 20);
        tracker.recordDenial(SESSION_A);
        tracker.recordDenial(SESSION_A);
        tracker.recordDenial(SESSION_B);

        tracker.removeSession(SESSION_A);

        assertThat(tracker.getConsecutiveDenials(SESSION_A))
            .as("A 的计数槽已回收 ⇒ 归零").isZero();
        assertThat(tracker.getTotalDenials(SESSION_A)).isZero();
        assertThat(tracker.getConsecutiveDenials(SESSION_B))
            .as("B 的计数不受 A 回收影响").isEqualTo(1);
        // 回收后从 1 重新起算（不是接着 3 继续）
        assertThat(tracker.recordDenial(SESSION_A).consecutiveDenials()).isEqualTo(1);
    }

    @Test
    @DisplayName("T16: 无会话桶（sessionId=null）只与同为无会话的调用共享，不与真实会话互窃")
    void nullSessionBucket_isIsolatedFromRealSessions() {
        DenialTracker tracker = new DenialTracker(3, 20);
        tracker.recordDenial(SESSION_A);
        tracker.recordDenial(SESSION_A);
        tracker.recordDenial(SESSION_A);
        assertThat(tracker.shouldFallbackToPrompting(SESSION_A)).isTrue();

        assertThat(tracker.recordDenial(null).fallback())
            .as("无会话调用不得被真实会话 A 的熔断推到 fallback").isFalse();
        assertThat(tracker.getConsecutiveDenials(SESSION_A))
            .as("无会话调用也不得改变 A 的计数").isEqualTo(3);
    }
}
