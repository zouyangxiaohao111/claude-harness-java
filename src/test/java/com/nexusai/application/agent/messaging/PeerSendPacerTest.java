package com.nexusai.application.agent.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发送侧 pacer · 纯对象 + 注入时钟（CC {@code ge()}，{@code claude.exe} 偏移 324042423）。
 *
 * <p>参数：容量 30 / 回填 0.5/s；维度 = 目标会话 × 代次；失败文案逐字来自
 * {@link PeerRateLimits#pacedMessage(int)}（偏移 324044952，errorClass {@code sender_paced}）。
 */
@DisplayName("[rlim] 发送侧 pacer：按目标 30/0.5s 预留、refund 归还")
class PeerSendPacerTest {

    private static final class MutableClock implements LongSupplier {
        private long now = 1_700_000_000_000L;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    @Test
    @DisplayName("同一目标发 30 条后第 31 条被拒（sentInBurst=30），回填 2s 恢复")
    void pacesToOneTarget() {
        MutableClock clock = new MutableClock();
        PeerSendPacer pacer = new PeerSendPacer(clock);

        for (int i = 0; i < 30; i++) {
            assertTrue(pacer.reserve("sess-a#1").ok(), "第 " + (i + 1) + " 条必须放行");
        }
        PeerSendPacer.Reservation rejected = pacer.reserve("sess-a#1");
        assertFalse(rejected.ok());
        assertEquals(30, rejected.sentInBurst(), "失败文案里的条数 = 本轮已发数");

        clock.advance(2_000);
        assertTrue(pacer.reserve("sess-a#1").ok());
    }

    @Test
    @DisplayName("refund 归还令牌；额度满时不变负数，不同目标互不影响")
    void refundAndTargetIsolation() {
        MutableClock clock = new MutableClock();
        PeerSendPacer pacer = new PeerSendPacer(clock);

        PeerSendPacer.Reservation first = pacer.reserve("sess-a#1");
        assertTrue(first.ok());
        assertEquals(1, pacer.sentInBurst("sess-a#1"));
        first.refund();
        first.refund(); // 幂等
        assertEquals(0, pacer.sentInBurst("sess-a#1"), "refund 只归还一次");

        // 打满 A。
        for (int i = 0; i < 30; i++) {
            pacer.reserve("sess-a#1");
        }
        assertFalse(pacer.reserve("sess-a#1").ok());
        // B 有独立桶。
        assertTrue(pacer.reserve("sess-b#1").ok());
        // 换代次（重注册）= 新键 = 新桶。
        assertTrue(pacer.reserve("sess-a#2").ok());
    }

    @Test
    @DisplayName("burst 计数在满桶或超过回满时间（60s）后归零")
    void burstWindowResets() {
        MutableClock clock = new MutableClock();
        PeerSendPacer pacer = new PeerSendPacer(clock);
        pacer.reserve("sess-a#1");
        pacer.reserve("sess-a#1");
        assertEquals(2, pacer.sentInBurst("sess-a#1"));
        clock.advance(61_000);
        pacer.reserve("sess-a#1");
        assertEquals(1, pacer.sentInBurst("sess-a#1"), "超过回满时间后本轮计数归零");
    }

    @Test
    @DisplayName("失败文案 = CC 原文（sender_paced）")
    void pacedMessageMatchesCc() {
        assertEquals("Too many messages to this session just now: 30 were sent recently and more "
                + "would be dropped by its rate limit, so this one was not sent. Batch what remains "
                + "into one message, or wait a little before sending more.",
            PeerRateLimits.pacedMessage(30));
        assertEquals("sender_paced", PeerRateLimits.ERROR_CLASS_SENDER_PACED);
    }
}
