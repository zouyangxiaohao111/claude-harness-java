package com.nexusai.application.agent.tasks;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [cwd3 · 用户裁定 2026-09-15 步骤 1b] 无会话 cron 回合的会话键必须是
 * {@link SessionKeys#NO_SESSION} 哨兵，⛔ 不是 {@code "global"} 字面量。
 *
 * <p><b>WHY（规则九 · 意图，不是行为）</b>：{@code CronIdleExecutor.GLOBAL_SESSION_KEY} 是
 * 「结构上确无会话」的合法路径在<b>唯一会话槽位</b>上的占位。它经 {@code RunRequest.sessionId}
 * 流到 {@code CwdResolution.getCwd}。步骤 2 把「DB 明确答无此会话」从「回落进程 user.dir」改成
 * <b>fail-loud 抛</b>之后，若该键仍是 {@code "global"}（DB 里当然没有这一行），它会直接<b>抛崩</b>
 * 一条合法路径 —— {@code CronIdleExecutor.surfaceMissedOneShots} 就是这条（它用 6 参
 * {@code QueueItem} 硬编码 {@code sessionId=null}，与 {@code schedules} 行无关，启动期 missed
 * 通知即触发，任何 REST 侧的强制会话锚都改不动它）。
 *
 * <p><b>⚠️ 为什么不能只靠现有 {@code CronIdleExecutorTest:543} 那类断言</b>：那些用例是
 * {@code isEqualTo(CronIdleExecutor.GLOBAL_SESSION_KEY)} —— <b>比常量</b>，把值改回 {@code "global"}
 * 它们照样绿。⇒ 必须直接钉「值本身是哨兵」+「它在 cwd 域走的是哪条路」。
 *
 * <p><b>RED（RE-1b-1）</b>：把 {@code GLOBAL_SESSION_KEY} 改回 {@code "global"} ⇒
 * ①{@code isNoSession} 断言红；②「回源计数 == 0」断言红（{@code "global"} 会进 lookup 回源一次）。
 * <br>⚠️ <b>只断言返回值是不够的</b>：两种取值下 {@code getCwd} 都返回
 * {@code getCwdForNonSession()}（{@code "global"} 走 unknown 分支也回落 user.dir）⇒ 返回值断言
 * <b>无鉴别力</b>，鉴别力全在「是否查 DB」与「打的是哪条告警」。
 */
class CronGlobalSessionKeySentinelTest {

    @AfterEach
    void tearDown() {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    private static ListAppender<ILoggingEvent> attachToCwdResolutionLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger(CwdResolution.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    @DisplayName("GLOBAL_SESSION_KEY 的值必须是 SessionKeys.NO_SESSION（不是 \"global\" 字面量）")
    void globalSessionKeyIsTheNoSessionSentinel() {
        // RED（RE-1b-1）：改回 "global" ⇒ 本断言红
        assertThat(CronIdleExecutor.GLOBAL_SESSION_KEY)
            .as("无会话 cron 回合的占位键必须是「确无会话」哨兵")
            .isEqualTo(SessionKeys.NO_SESSION);
        assertThat(SessionKeys.isNoSession(CronIdleExecutor.GLOBAL_SESSION_KEY))
            .as("必须被 isNoSession 识别（CwdResolution 顶部据此短路）")
            .isTrue();
    }

    @Test
    @DisplayName("getCwd(GLOBAL_SESSION_KEY) 走命名无会话出口：不查 DB、不打「DB 中不存在」告警")
    void cwdOfGlobalSessionKeyGoesToNamedNonSessionExitWithoutDbLookup() {
        // RED（RE-1b-1）：值改回 "global" ⇒ 落 unknown 分支 ⇒ 先 safeLookup → 回源一次（计数 1）⇒ 红
        ListAppender<ILoggingEvent> appender = attachToCwdResolutionLogger();
        final AtomicInteger lookups = new AtomicInteger();
        SessionProjectRoot.setDbResolver(sid -> {
            lookups.incrementAndGet();
            return SessionProjectRoot.Lookup.unknown();
        });
        try {
            String cwd = CwdResolution.getCwd(CronIdleExecutor.GLOBAL_SESSION_KEY);

            assertThat(cwd)
                .as("返回值 = 命名无会话出口（进程 user.dir）")
                .isEqualTo(CwdResolution.getCwdForNonSession());
            assertThat(lookups.get())
                .as("⭐ 真正的鉴别点：哨兵必须短路，不得触发任何 DB 回源（0 次）")
                .isZero();

            List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages)
                .as("哨兵路径不得打「DB 中不存在 / 来源不明 id」告警（那是 unknown 分支的告警，"
                    + "会把一条合法路径报成缺陷）")
                .noneMatch(m -> m.contains("在 DB 中不存在"));
        } finally {
            Logger logger = (Logger) LoggerFactory.getLogger(CwdResolution.class);
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
