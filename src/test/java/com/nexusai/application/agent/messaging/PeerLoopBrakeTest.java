package com.nexusai.application.agent.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动循环刹车（aVn / lVn / uea）· 纯对象。
 *
 * <p>触发条件与行为逐条对齐 CC 2.1.328（{@code claude.exe} 偏移 336254506 / 336254843）：
 * 有 starter 且已证同机 ⇒ 计数；本地发送「计数 &gt; maxLocalRelays <b>且</b> starter 链带循环信号」
 * 才暂停；复位 = 真实用户输入。Desktop 转发按「用户输入以来」计数，超 maxDesktopForwards 暂停。
 */
@DisplayName("[rlim] 自动循环刹车：localSends/bridgeSends/desktopForwards + 用户输入复位")
class PeerLoopBrakeTest {

    /** 造一条带循环信号的链（29 段 &gt; maxChainLength=28）。 */
    private static List<String> runawayChain() {
        List<String> chain = new ArrayList<>();
        for (int i = 0; i <= PeerEnvelope.MAX_CHAIN_LENGTH; i++) {
            chain.add(PeerEnvelope.hopToken("hop-" + i));
        }
        return chain;
    }

    /** 正常短链（无循环信号）。 */
    private static List<String> shortChain(String... sessions) {
        List<String> chain = new ArrayList<>();
        for (String s : sessions) {
            chain.add(PeerEnvelope.hopToken(s));
        }
        return chain;
    }

    @Test
    @DisplayName("e. 本地：超预算且链带循环信号 ⇒ 暂停；用户输入后恢复")
    void localBrakePausesAndResetsOnUserPrompt() {
        PeerLoopBrake brake = new PeerLoopBrake();
        brake.setMaxLocalRelaysRaw("3");
        // 首个合格来信 origin → starter（CC mea）。
        brake.onPeerOrigin("self-session", "peer-a", false, true, false, false, runawayChain());

        assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginLocalSend("self-session").kind());
        assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginLocalSend("self-session").kind());
        assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginLocalSend("self-session").kind());

        PeerLoopBrake.Outcome paused = brake.beginLocalSend("self-session");
        assertEquals(PeerLoopBrake.Kind.PAUSED, paused.kind(), "第 4 条（> 3）必须暂停");
        assertEquals("Not delivered: this session keeps answering other sessions, which looks like "
                + "an automatic loop. Sending to sessions on this machine is paused until your "
                + "user's next prompt starts a turn in this session.",
            paused.message(), "文案必须逐字等于 CC 原文");

        // 复位 = 真实用户输入（CC dMs）。
        brake.onUserPrompt("self-session");
        assertEquals(0, brake.localSends("self-session"));
        assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginLocalSend("self-session").kind(),
            "用户输入后计数清零 ⇒ 恢复发送");
    }

    @Test
    @DisplayName("e2. 只有计数超限、链无循环信号 ⇒ 不暂停（CC 的 AND 条件）")
    void noLoopSignalMeansNoBrake() {
        PeerLoopBrake brake = new PeerLoopBrake();
        brake.setMaxLocalRelaysRaw("3");
        brake.onPeerOrigin("self-session", "peer-a", false, true, false, false,
            shortChain("peer-a", "self-session"));

        for (int i = 0; i < 20; i++) {
            assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginLocalSend("self-session").kind(),
                "正常长协作（链干净）不得被误杀，第 " + (i + 1) + " 条");
        }
        assertEquals(20, brake.localSends("self-session"));
    }

    @Test
    @DisplayName("e3. 无 starter ⇒ 完全不计入（CC starter 为空即放行）")
    void noStarterNoBrake() {
        PeerLoopBrake brake = new PeerLoopBrake();
        brake.setMaxLocalRelaysRaw("1");
        for (int i = 0; i < 5; i++) {
            assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginLocalSend("fresh").kind());
        }
        assertNull(brake.starter("fresh"));
        assertEquals(0, brake.localSends("fresh"));
    }

    @Test
    @DisplayName("e4. countLocalHops=true / 未证同机 ⇒ 不刹车（CC vXr）")
    void brakeRequiresVerifiedLocal() {
        PeerLoopBrake brake = new PeerLoopBrake();
        brake.setMaxLocalRelaysRaw("1");
        brake.onPeerOrigin("s1", "peer-a", false, false, false, false, runawayChain());
        for (int i = 0; i < 5; i++) {
            assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginLocalSend("s1").kind(),
                "未证同机（verifiedLocal=false）⇒ 不启用");
        }

        PeerLoopBrake counted = new PeerLoopBrake();
        counted.setMaxLocalRelaysRaw("1");
        counted.setCountLocalHops(true);
        counted.onPeerOrigin("s2", "peer-a", false, true, false, false, runawayChain());
        for (int i = 0; i < 5; i++) {
            assertEquals(PeerLoopBrake.Kind.ADMITTED, counted.beginLocalSend("s2").kind(),
                "countLocalHops=true ⇒ 不启用刹车");
        }
    }

    @Test
    @DisplayName("e5. bridge：带循环信号时 bridgeSends > 10 才暂停，第 1..10 条放行")
    void bridgeBrakeUsesMaxSelfHops() {
        PeerLoopBrake brake = new PeerLoopBrake();
        brake.onPeerOrigin("self-session", "peer-a", false, true, false, false, runawayChain());

        for (int i = 0; i < PeerEnvelope.MAX_SELF_HOPS; i++) {
            PeerLoopBrake.Outcome outcome = brake.beginBridgeSend("self-session",
                shortChain("peer-a"));
            assertEquals(PeerLoopBrake.Kind.ADMITTED, outcome.kind(), "第 " + (i + 1) + " 条放行");
            assertEquals(shortChain("peer-a"), outcome.hopChain(), "放行时必须带旧 hop 链");
        }
        PeerLoopBrake.Outcome paused = brake.beginBridgeSend("self-session", shortChain("peer-a"));
        assertEquals(PeerLoopBrake.Kind.PAUSED, paused.kind());
        assertTrue(paused.message().contains("Remote Control and cloud sessions"),
            "bridge 文案目标是 Remote Control and cloud sessions，实际=" + paused.message());
    }

    @Test
    @DisplayName("d. Desktop 转发预算：默认 100000；非法配置回退 20；按用户输入序号归零")
    void desktopForwardBudget() {
        PeerLoopBrake brake = new PeerLoopBrake();
        assertEquals(PeerRateLimits.DEFAULT_FORWARD_BUDGET, brake.maxDesktopForwards(),
            "缺失配置 ⇒ 100000（CC .default(1e5)）");
        brake.setMaxDesktopForwardsRaw("abc");
        assertEquals(PeerRateLimits.FALLBACK_FORWARD_BUDGET, brake.maxDesktopForwards(),
            "配置非法 ⇒ 20（CC .catch(20)）");
        brake.setMaxDesktopForwardsRaw("200001");
        assertEquals(PeerRateLimits.FALLBACK_FORWARD_BUDGET, brake.maxDesktopForwards(),
            "越界（> 1e5）⇒ 20");

        brake.setMaxDesktopForwardsRaw("2");
        assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginDesktopForward("s", 1).kind());
        assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginDesktopForward("s", 1).kind());
        PeerLoopBrake.DesktopOutcome paused = brake.beginDesktopForward("s", 1);
        assertEquals(PeerLoopBrake.Kind.PAUSED, paused.kind());
        assertEquals(2, paused.forwards(), "CC 文案里的 N = forwardsSincePrompt - 1");
        assertTrue(paused.message().startsWith(
            "Not delivered: this session has already messaged Claude Desktop sessions 2 times"),
            "实际=" + paused.message());

        // 用户新一轮输入（promptIndex 变化）⇒ 计数归零。
        assertEquals(PeerLoopBrake.Kind.ADMITTED, brake.beginDesktopForward("s", 2).kind());
    }

    @Test
    @DisplayName("d2. maxLocalRelays 同样遵循 缺失=100000 / 非法=20")
    void localRelayBudgetParsing() {
        PeerLoopBrake brake = new PeerLoopBrake();
        assertEquals(PeerRateLimits.DEFAULT_FORWARD_BUDGET, brake.maxLocalRelays());
        brake.setMaxLocalRelaysRaw("0");
        assertEquals(PeerRateLimits.FALLBACK_FORWARD_BUDGET, brake.maxLocalRelays());
        brake.setMaxLocalRelaysRaw("50000");
        assertEquals(50_000, brake.maxLocalRelays());
    }
}
