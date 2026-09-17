package com.nexusai.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboundDeliveryStats} 纯逻辑单测（OBS2）。
 *
 * <p><b>WHY（规则九：验证意图）</b>：本类的存在意义是回答事故复盘里答不出的那一问 ——
 * 「那一刻帧有没有真写出去」。所以这里断言的<b>不是</b>「计数器会加一」这种 WHAT，而是三件判据：
 * <ol>
 *   <li><b>非会话帧不能污染判据</b>：无 simpSessionId 的广播帧若被算进来，「帧数在涨」就失去意义
 *       （会掩盖「该会话一个字节都没收到」）—— 故必须静默跳过而不是计入某个空 key。</li>
 *   <li><b>窗口增量与累计必须分开</b>：周期汇总看的是「本窗口还在不在推」；若汇总把累计当增量，
 *       一条早已死掉的通道会永远显示「在推」（累计数还在），判据失效。故 drain 后本窗口必须归零、
 *       累计不得被清零。</li>
 *   <li><b>写出失败不得冒充「最后写出时刻」</b>：失败推进 lastSendAt 会把「写失败了」读成
 *       「还在成功写」，正是本类要消灭的那类误判。</li>
 * </ol>
 *
 * <p>时刻全部由调用方传入（{@code nowMs}）→ 断言确定，不依赖真实时钟。
 */
@DisplayName("OBS2 · 出站帧投递统计（纯逻辑）")
class OutboundDeliveryStatsTest {

    @Test
    @DisplayName("无 simpSessionId 的帧静默跳过：不建 key、不计数、不抛异常")
    void nonSessionFrameSkippedSilently() {
        OutboundDeliveryStats stats = new OutboundDeliveryStats();

        stats.recordSend(null, 1_000L);
        stats.recordSend("", 1_000L);

        assertThat(stats.sessionCount())
            .as("广播/队列类非会话帧没有 simpSessionId —— 必须彻底不计，否则「帧数在涨」这个判据会被无主帧注水")
            .isZero();
        assertThat(stats.drainSummary(2_000L))
            .as("无登记会话 → 汇总为空串（调用方据此跳过打印，空闲应用不留噪声日志）")
            .isEmpty();
    }

    @Test
    @DisplayName("按 simpSessionId 分别累计：帧数 + 最后写出时刻各自独立")
    void countsArePerSession() {
        OutboundDeliveryStats stats = new OutboundDeliveryStats();

        stats.recordSend("s1", 1_000L);
        stats.recordSend("s2", 1_100L);
        stats.recordSend("s1", 1_200L);

        assertThat(stats.sessionCount()).isEqualTo(2);
        assertThat(stats.get("s1").framesSent())
            .as("s1 收到 2 帧，s2 收到 1 帧 —— 计数必须按会话隔离（否则答不出「是哪一个会话没收到」）")
            .isEqualTo(2L);
        assertThat(stats.get("s2").framesSent()).isEqualTo(1L);
        assertThat(stats.get("s1").lastSendAt())
            .as("最后写出时刻 = 该会话最后一次放行帧的时刻（1_200，不是 1_000）")
            .isEqualTo(1_200L);
        assertThat(stats.get("s2").lastSendAt()).isEqualTo(1_100L);
    }

    @Test
    @DisplayName("drainSummary 取窗口增量并清零；累计不受影响（『还在推吗』的判据靠这个）")
    void drainTakesWindowDeltaAndResetsWindowOnly() {
        OutboundDeliveryStats stats = new OutboundDeliveryStats();

        stats.recordSend("s1", 1_000L);
        stats.recordSend("s1", 1_500L);

        String first = stats.drainSummary(2_000L);
        assertThat(first)
            .as("本窗口 2 帧 / 累计 2 帧 / 最后写出在 500ms 前（2000-1500）")
            .isEqualTo("s1=+2/总2/最后写出 500ms 前");

        String second = stats.drainSummary(3_000L);
        assertThat(second)
            .as("窗口已被取走 → 本窗口 +0（通道停了就该读出 +0，⛔ 不能因为累计非零而显示在推）")
            .isEqualTo("s1=+0/总2/最后写出 1500ms 前");

        stats.recordSend("s1", 3_100L);
        assertThat(stats.drainSummary(3_200L))
            .as("累计单调递增、窗口重新计 —— drain 只能清窗口，⛔ 不许把累计一起清掉")
            .isEqualTo("s1=+1/总3/最后写出 100ms 前");
    }

    @Test
    @DisplayName("从未放过帧的会话显示「从未」而不是「0ms 前」")
    void neverSentShowsNever() {
        OutboundDeliveryStats stats = new OutboundDeliveryStats();

        stats.recordFailure("s1", 5_000L);

        assertThat(stats.drainSummary(9_000L))
            .as("只有失败、没有成功 → 最后写出必须是「从未」；写成 0ms 前会把「一帧都没出去」读成「刚出去」")
            .isEqualTo("s1=+0/总0/失败1(最后 4000ms 前)/最后写出 从未");
    }

    @Test
    @DisplayName("写出失败不推进『最后写出时刻』（失败 ≠ 写成功）")
    void failureDoesNotAdvanceLastSendAt() {
        OutboundDeliveryStats stats = new OutboundDeliveryStats();

        stats.recordSend("s1", 1_000L);
        stats.recordFailure("s1", 8_000L);

        assertThat(stats.get("s1").lastSendAt())
            .as("最后一次成功放行仍是 1_000 —— 失败若覆盖它，就会把「写失败了」读成「还在成功写」")
            .isEqualTo(1_000L);
        assertThat(stats.get("s1").failures()).isEqualTo(1L);
        assertThat(stats.get("s1").lastFailureAt()).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("会话断开 → removeSession 清掉该会话（防 simpSessionId 随重连无界累积）")
    void removeSessionDropsEntry() {
        OutboundDeliveryStats stats = new OutboundDeliveryStats();

        stats.recordSend("s1", 1_000L);
        stats.recordSend("s2", 1_000L);
        stats.removeSession("s1");

        assertThat(stats.sessionCount()).isEqualTo(1);
        assertThat(stats.get("s1")).isNull();
        assertThat(stats.drainSummary(2_000L))
            .as("已断开的会话不再出现在汇总里（否则汇总会越打越长，长跑应用无界）")
            .isEqualTo("s2=+1/总1/最后写出 1000ms 前");
    }

    @Test
    @DisplayName("多会话汇总按 simpSessionId 稳定排序（两次汇总可直接比对）")
    void summaryIsStablyOrdered() {
        OutboundDeliveryStats stats = new OutboundDeliveryStats();

        stats.recordSend("s-b", 1_000L);
        stats.recordSend("s-a", 1_000L);

        assertThat(stats.drainSummary(1_000L))
            .isEqualTo("s-a=+1/总1/最后写出 0ms 前, s-b=+1/总1/最后写出 0ms 前");
    }
}
