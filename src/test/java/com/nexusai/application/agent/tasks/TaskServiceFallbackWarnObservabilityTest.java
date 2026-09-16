package com.nexusai.application.agent.tasks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S1-T14 · 缺值策略 (a)] 「无显式会话 → 回退全进程共享列表」必须<b>每次</b> ≥WARN 可观测，
 * 不得被一次性闸降级成「每 JVM 一行」。
 *
 * <p><b>WHY（规则九 · 意图）</b>：该回落落到的是<b>全进程共享</b>的任务列表桶——在单 JVM 多会话
 * 形态下等于「任务写进了所有会话共享的那一个桶」。原实现用进程级 {@code AtomicBoolean} 做
 * 「首次 WARN、后续 debug」的一次性闸：第 2 次及以后的回落<b>在结构上不可观测</b>
 * （本仓裁定-8 的实证：一次性闸会把「≥WARN 可观测」变成「每 JVM 一行」）。
 *
 * <p>本测试锁「每次回落 = 一条 WARN」这一判据（第 1 次、第 2 次都必须在日志里）。
 *
 * <p>⚠️ <b>与派单措辞的差异（已实测）</b>：派单原文写「两个<b>不同会话</b>各触发一次 ⇒ 捕获到
 * 两条独立 WARN」。实测该措辞<b>不可实现</b>——{@link TaskService#getTaskListId(String, com.nexusai.application.agent.team.TeammateIdentity)}
 * 走到最终回落的<b>充要条件</b>是优先级 6（显式会话形参）也 miss，即 {@code sessionId} 必为
 * null/空白；<b>带会话的调用在优先级 6 就返回了，永远到不了本级</b>。因此「按 sessionId 分桶」
 * 的键恒为同一个（死分支）。本测试改锁同一意图的<b>可实现形式</b>：每次回落各一条 WARN。
 *
 * <p><b>RED teeth</b>：把 WARN 改回进程级一次性闸（{@code AtomicBoolean.compareAndSet(false,true)}）
 * ⇒ 第 2 次调用零 WARN ⇒ 断言红。
 */
class TaskServiceFallbackWarnObservabilityTest {

    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty("nexusai.taskListId");
        System.clearProperty("nexusai.team.name");
        System.clearProperty("nexusai.sessionId");
        TaskService.clearLeaderTeamName();
        TaskService.resetFallbackWarnCounterForTesting();
    }

    private static ListAppender<ILoggingEvent> captureFallbackWarn() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(TaskService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        return appender;
    }

    private static void stopCapture(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(TaskService.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<ILoggingEvent> fallbackWarns(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
            .filter(e -> e.getFormattedMessage().contains("缺值策略(a)"))
            .filter(e -> e.getFormattedMessage().contains("无显式会话标识"))
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言1: 每次回落各一条 WARN —— 第 2 次回落不得被一次性闸吞掉")
    void everyFallback_isWarned_notJustTheFirst() {
        Assumptions.assumeTrue(
            System.getenv("CLAUDE_CODE_TASK_LIST_ID") == null
                || System.getenv("CLAUDE_CODE_TASK_LIST_ID").isBlank(),
            "环境设置了 CLAUDE_CODE_TASK_LIST_ID（优先级 1），不会走到回落分支");

        ListAppender<ILoggingEvent> logs = captureFallbackWarn();
        try {
            String first = TaskService.getTaskListId(null, null);
            String second = TaskService.getTaskListId(null, null);
            assertThat(first).as("回落值进程内稳定").isEqualTo(second);
        } finally {
            stopCapture(logs);
        }

        assertThat(fallbackWarns(logs))
            .as("⭐ 两次回落必须各产生一条 WARN（一次性闸在此处必红：只会有 1 条）")
            .hasSize(2);
        assertThat(fallbackWarns(logs).get(0).getFormattedMessage()).contains("第 1 次回落");
        assertThat(fallbackWarns(logs).get(1).getFormattedMessage()).contains("第 2 次回落");
    }

    @Test
    @DisplayName("对照: 显式会话/身份命中时**不产生**回落 WARN（热路径不刷屏）")
    void noFallbackWarn_whenSessionResolves() {
        ListAppender<ILoggingEvent> logs = captureFallbackWarn();
        try {
            assertThat(TaskService.getTaskListId("sess-s1-t14", null)).isEqualTo("sess-s1-t14");
            assertThat(TaskService.getTaskListId("sess-s1-t14",
                new com.nexusai.application.agent.team.TeammateIdentity(
                    "p@t", "p", "t-x", null, false, null))).isEqualTo("t-x");
        } finally {
            stopCapture(logs);
        }

        assertThat(fallbackWarns(logs))
            .as("会话/身份能解析时不得回落，也就不该有回落 WARN")
            .isEmpty();
    }
}
