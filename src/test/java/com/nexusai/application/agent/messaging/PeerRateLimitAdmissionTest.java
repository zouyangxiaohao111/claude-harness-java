package com.nexusai.application.agent.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接收侧准入（令牌桶 / 同文去重 / 队容）· 纯对象 + 注入时钟（不 sleep）。
 *
 * <p>参数与判定顺序逐条对齐 CC 2.1.328（{@code claude.exe} 偏移 324034003 / 325455000）：
 * 容量 30、回填 0.5/s、去重窗口 30s、队容 50（拒新不淘汰旧）、最多跟踪 256 发送方。
 *
 * <p><b>RED tooth</b>：把令牌桶扣减删掉 ⇒ {@link #thirtyFirstMessageIsRateLimited()} 红；
 * 把去重窗口改成 0 ⇒ {@link #identicalBodyWithinWindowIsDropped()} 红。
 */
@DisplayName("[rlim] 接收侧准入：令牌桶 30/0.5s、同文去重 30s、队容 50")
class PeerRateLimitAdmissionTest {

    /** 可变时钟（毫秒）。 */
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

    private static PeerInboundAdmission.Input input(String senderKey, String body, int queued) {
        return new PeerInboundAdmission.Input(senderKey, body, queued);
    }

    @Test
    @DisplayName("a. 连续 31 条（正文各不相同）→ 第 31 条 rate-limited；回填 2s 后又能发 1 条")
    void thirtyFirstMessageIsRateLimited() {
        MutableClock clock = new MutableClock();
        PeerInboundAdmission gate = new PeerInboundAdmission(clock);

        for (int i = 0; i < 30; i++) {
            PeerInboundAdmission.Decision d = gate.admit(input("from:src", "body-" + i, 0));
            assertTrue(d.admitted(), "前 30 条必须放行（容量 30），第 " + (i + 1) + " 条被拒");
        }
        PeerInboundAdmission.Decision rejected = gate.admit(input("from:src", "body-30", 0));
        assertFalse(rejected.admitted(), "第 31 条必须被拒");
        assertEquals(PeerRateLimits.Reason.RATE_LIMITED, rejected.reason());

        // 0.5/s ⇒ 2s 回填 1 个令牌。
        clock.advance(2_000);
        assertTrue(gate.admit(input("from:src", "body-31", 0)).admitted(),
            "回填一个令牌后必须放行");
        assertEquals(PeerRateLimits.Reason.RATE_LIMITED,
            gate.admit(input("from:src", "body-32", 0)).reason());
    }

    @Test
    @DisplayName("b. 30s 内同文逐字符相等 → duplicate；窗口边界（>=30000ms）放行")
    void identicalBodyWithinWindowIsDropped() {
        MutableClock clock = new MutableClock();
        PeerInboundAdmission gate = new PeerInboundAdmission(clock);

        assertTrue(gate.admit(input("from:src", "same body", 0)).admitted());
        PeerInboundAdmission.Decision dup = gate.admit(input("from:src", "same body", 0));
        assertFalse(dup.admitted());
        assertEquals(PeerRateLimits.Reason.DUPLICATE, dup.reason());

        // 不同正文不受影响（去重只比「上一条」）。
        assertTrue(gate.admit(input("from:src", "same body!", 0)).admitted());

        // 另一个发送方各算各的。
        assertTrue(gate.admit(input("from:other", "same body!", 0)).admitted());

        // 恰好 30000ms：CC 判据是 `u - lastBodyAt < dedupWindowMs` ⇒ 边界放行。
        clock.advance(30_000);
        assertTrue(gate.admit(input("from:src", "same body!", 0)).admitted());
    }

    @Test
    @DisplayName("c. 队容 50：已排 50 条时拒新；调用方重试模式提前短接；旧项不受影响")
    void queueFullRejectsNewOnly() {
        MutableClock clock = new MutableClock();
        PeerInboundAdmission gate = new PeerInboundAdmission(clock);

        // 49 条排队：仍放行（上限是 50）。
        assertTrue(gate.admit(input("from:src", "q-48", 48)).admitted());
        // 已排 50 条 ⇒ 拒新（reason=queue-full）。
        PeerInboundAdmission.Decision full = gate.admit(input("from:src", "q-50", 50));
        assertFalse(full.admitted());
        assertEquals(PeerRateLimits.Reason.QUEUE_FULL, full.reason());
        // 队列降到 49 条 ⇒ 又能收（上限口径是「已排 ≥ 50 才拒新」）。
        assertTrue(gate.admit(input("from:src", "q-49b", 49)).admitted());
    }

    @Test
    @DisplayName("c2. 判定顺序：caller-retries 时 queue-full 先于令牌桶；coalesced 时桶先于队容")
    void queueFullOrderMatchesCc() {
        MutableClock clock = new MutableClock();
        PeerInboundAdmission gate = new PeerInboundAdmission(clock);
        // 先把桶打空（30 条）。
        for (int i = 0; i < 30; i++) {
            assertTrue(gate.admit(input("from:src", "fill-" + i, 0)).admitted());
        }
        // 队满 + 桶空 + caller-retries ⇒ CC 先判 queue-full。
        PeerInboundAdmission.Decision retries = gate.admit(new PeerInboundAdmission.Input(
            "from:src", "x", null, Set.of(), 50, true));
        assertEquals(PeerRateLimits.Reason.QUEUE_FULL, retries.reason());
        // 队满 + 桶空 + coalesced（默认）⇒ CC 先判令牌桶。
        PeerInboundAdmission.Decision coalesced = gate.admit(input("from:src", "y", 50));
        assertEquals(PeerRateLimits.Reason.RATE_LIMITED, coalesced.reason());
    }

    @Test
    @DisplayName("d. hop 链：> 28 段 ⇒ hop-runaway；自身 token ≥ 10 次 ⇒ hop-loop")
    void hopChainChecks() {
        List<String> tooLong = new ArrayList<>();
        for (int i = 0; i <= PeerEnvelope.MAX_CHAIN_LENGTH; i++) {
            tooLong.add(PeerEnvelope.hopToken("h" + i));
        }
        assertEquals(PeerRateLimits.Reason.HOP_RUNAWAY,
            PeerInboundAdmission.checkHopChain(tooLong, Set.of()));

        String own = PeerEnvelope.hopToken("self");
        List<String> loop = new ArrayList<>();
        for (int i = 0; i < PeerEnvelope.MAX_SELF_HOPS; i++) {
            loop.add(own);
        }
        assertEquals(PeerRateLimits.Reason.HOP_LOOP,
            PeerInboundAdmission.checkHopChain(loop, Set.of(own)));

        // 9 次（< 10）不拒：正常往返协作必须放行。
        assertNull(PeerInboundAdmission.checkHopChain(loop.subList(0, 9), Set.of(own)));
        // 28 段恰好不拒（硬限是「> 28」）。
        assertNull(PeerInboundAdmission.checkHopChain(
            tooLong.subList(0, PeerEnvelope.MAX_CHAIN_LENGTH), Set.of()));
    }

    @Test
    @DisplayName("e. 最多跟踪 256 个发送方（LRU 逐出最旧）")
    void tracksAtMost256Senders() {
        MutableClock clock = new MutableClock();
        PeerInboundAdmission gate = new PeerInboundAdmission(clock);
        for (int i = 0; i < PeerRateLimits.MAX_TRACKED_SENDERS; i++) {
            assertTrue(gate.admit(input("from:s" + i, "b", 0)).admitted());
        }
        assertEquals(PeerRateLimits.MAX_TRACKED_SENDERS, gate.trackedSenderCount());
        gate.admit(input("from:newcomer", "b", 0));
        assertEquals(PeerRateLimits.MAX_TRACKED_SENDERS, gate.trackedSenderCount(),
            "超过 256 必须逐出最旧，不得无限增长");
        assertFalse(gate.trackedSenders().contains("from:s0"), "最旧发送方应被逐出");
        assertTrue(gate.trackedSenders().contains("from:newcomer"));
    }

    @Test
    @DisplayName("f. 时钟注入：自始至终没有 sleep；成本 = 纯算术")
    void clockIsInjectedNoSleep() {
        MutableClock clock = new MutableClock();
        PeerInboundAdmission gate = new PeerInboundAdmission(clock);
        // 满血约 60s（30 / 0.5），全部靠 advance 模拟。
        for (int i = 0; i < 30; i++) {
            assertTrue(gate.admit(input("from:src", "z" + i, 0)).admitted());
        }
        assertEquals(PeerRateLimits.Reason.RATE_LIMITED,
            gate.admit(input("from:src", "z30", 0)).reason());
        clock.advance(60_000);
        assertTrue(gate.admit(input("from:src", "z31", 0)).admitted(),
            "60s 后必须回满（0.5/s × 60 = 30）");
        assertEquals(29.0, gate.tokensOf("from:src"), 0.0001,
            "回满 30 扣 1 ⇒ 29");
    }
}
