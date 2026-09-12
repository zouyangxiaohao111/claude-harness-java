package com.nexusai.application.agent.compact;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [P2-9 · 2026-09-12] {@code SqliteBusyRetry} —— compact 落库 <b>5 通道共用的 BUSY_SNAPSHOT 重试单点</b>。
 *
 * <h2>WHY（规则九 · 测试验证意图）</h2>
 * 真机根因（3461 实例实证）：{@code POST /partial-compact} 稳定 500，错误
 * {@code [SQLITE_BUSY_SNAPSHOT] Another database connection has already written to the database}。
 * compact 落库唯一出口 {@code MessageService.appendPostCompactMessages} 是 {@code @Transactional}
 * 且<b>先读后写</b>（先 SELECT knownIds 拿读快照 → 再 INSERT/UPDATE）—— WAL 下并发提交顶掉读快照后，
 * 该事务内任何写都必然抛 BUSY_SNAPSHOT（SQLite 不允许把过期读快照升级为写事务；JDBC 的
 * {@code busy_timeout} 对本错误<b>不生效</b>）。
 *
 * <p>这个失败模式对 5 条落库通道（① auto / ② reactive / ③ manual / ④ SM / ⑤ partial）<b>完全同源</b>
 * （同一出口方法、同一并发形状），修复时却只给 ⑤ 加了重试 → 策略分裂成「5 次 vs 0 次」。本测试钉死的
 * 意图是：<b>「同源故障 = 同策略」必须结构成立</b> —— 判别 / 次数 / 换新事务 / fail-loud 全部来自
 * 同一个类，任何把某个通道改回「不重试」或「自己另写一份」的改动都会让本测试或
 * {@code PartialCompactBusySnapshotTest} 变红。
 *
 * <p><b>RED 条件（改哪一行会红）</b>：
 * <ol>
 *   <li>删掉重试循环（只试一次）→ {@link #retriesUntilSuccess_onBusySnapshot} 红；</li>
 *   <li>把重试判定放宽成「任何异常都重试」→ {@link #nonBusyErrorIsNotRetried} 红；</li>
 *   <li>耗尽后静默返回 / 返回 null（不 fail loud）→ {@link #exhaustedRetriesFailLoud} 红；</li>
 *   <li>把 {@code extraSameTxAction} 挪到事务回调<b>之外</b> → {@link #txOverload_extraActionRunsInsideSameTransaction}
 *       与 {@link #txOverload_extraActionFailureRollsBackWholeTransaction} 红（原子性破）；</li>
 *   <li>把 {@code executor.execute} 拿掉（改成同事务内重试）→ {@link #txOverload_retriesInFreshTransaction} 红。</li>
 * </ol>
 */
@DisplayName("[P2-9] SqliteBusyRetry · compact 落库重试单点（判别 / 换新事务 / fail loud）")
class SqliteBusyRetryTest {

    /** 真机异常文案（org.sqlite.SQLiteException 的 message，原样取自 3461 实例 500 响应体）。 */
    private static final String BUSY_SNAPSHOT_MSG =
        "[SQLITE_BUSY_SNAPSHOT] Another database connection has already written to the database"
            + " (database is locked)";

    // ── 假事务管理器：只记录「事务起止」事件，不接触真实 DB ──────────────────────

    private static final class RecordingTxManager implements PlatformTransactionManager {

        private final List<String> events;
        final AtomicInteger began = new AtomicInteger();
        final AtomicInteger committed = new AtomicInteger();
        final AtomicInteger rolledBack = new AtomicInteger();

        RecordingTxManager(List<String> events) {
            this.events = events;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            began.incrementAndGet();
            events.add("tx-begin");
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            committed.incrementAndGet();
            events.add("tx-commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack.incrementAndGet();
            events.add("tx-rollback");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 判别（单点 · 不许第二套）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isSqliteBusy：沿 cause 链匹配 SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT（5 与 517 两种文案）；非 BUSY 不匹配")
    void isSqliteBusy_matchesBothWording_viaCauseChain() {
        assertThat(SqliteBusyRetry.isSqliteBusy(new RuntimeException(BUSY_SNAPSHOT_MSG))).isTrue();
        // 生产形状：最外层 MyBatis 包装（message 不带 SQLITE_BUSY），文案在 cause 链上
        assertThat(SqliteBusyRetry.isSqliteBusy(
            new RuntimeException("### Error updating database (see cause)",
                new IllegalStateException("[SQLITE_BUSY] database is locked")))).isTrue();
        assertThat(SqliteBusyRetry.isSqliteBusy(
            new RuntimeException("NOT NULL constraint failed"))).isFalse();
        assertThat(SqliteBusyRetry.isSqliteBusy(
            new RuntimeException("outer", new IllegalArgumentException("inner")))).isFalse();
        assertThat(SqliteBusyRetry.isSqliteBusy(null)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 普通通道（①②③④ 用）· 重试 / 不重试 / fail loud
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BUSY_SNAPSHOT → 重试直至成功（第 3 次返回；不吞返回值）")
    void retriesUntilSuccess_onBusySnapshot() {
        AtomicInteger calls = new AtomicInteger();
        String out = SqliteBusyRetry.executeWithBusyRetry("test-op", () -> {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                throw new RuntimeException(
                    "### Error updating database. Cause: org.sqlite.SQLiteException (see cause)",
                    new IllegalStateException(BUSY_SNAPSHOT_MSG));
            }
            return "ok-" + n;
        });

        assertThat(out).as("重试后拿到成功那次的返回值（不是 null / 不是入参原样）").isEqualTo("ok-3");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("非 BUSY 错误不重试：立即抛出，只尝试 1 次（fail loud，不把真 bug 掩盖成偶发失败）")
    void nonBusyErrorIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> SqliteBusyRetry.executeWithBusyRetry("test-op", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("NOT NULL constraint failed: messages.id");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOT NULL constraint failed");

        assertThat(calls.get()).as("不可重试错误只尝试一次").isEqualTo(1);
    }

    @Test
    @DisplayName("耗尽重试 → fail loud：抛最后一次 BUSY（不静默返回 / 不吞异常），次数恒 = MAX_WRITE_ATTEMPTS")
    void exhaustedRetriesFailLoud() {
        ListAppender<ILoggingEvent> app = attachErrorCapture();
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> SqliteBusyRetry.executeWithBusyRetry("test-op", () -> {
                calls.incrementAndGet();
                throw new RuntimeException("wrapped", new IllegalStateException(BUSY_SNAPSHOT_MSG));
            }))
                .as("耗尽后必须显式报错（业务层据此走 PERSIST_FAILED / log.error 兜底），不得静默")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("wrapped");

            assertThat(calls.get()).isEqualTo(SqliteBusyRetry.MAX_WRITE_ATTEMPTS);
            assertThat(app.list.stream().map(ILoggingEvent::getFormattedMessage))
                .as("耗尽必须留下 error 级日志（规则十二 fail loud）")
                .anyMatch(m -> m.contains("均因 SQLITE_BUSY_SNAPSHOT 失败")
                    && m.contains(String.valueOf(SqliteBusyRetry.MAX_WRITE_ATTEMPTS)));
        } finally {
            detachErrorCapture(app);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 显式事务通道（⑤ partial 用）· 新事务重试 + 额外同事务动作
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("事务重载：额外动作在主写之后、同一事务内执行（原子性 = partial 的落库 + updateConversationId）")
    void txOverload_extraActionRunsInsideSameTransaction() {
        List<String> events = new ArrayList<>();
        RecordingTxManager tx = new RecordingTxManager(events);
        TransactionTemplate template = new TransactionTemplate(tx);

        String out = SqliteBusyRetry.executeInTxWithBusyRetry("test-op", template,
            () -> {
                events.add("primary");
                return "written";
            },
            () -> events.add("extra"));

        assertThat(out).isEqualTo("written");
        // 两处写必须都在同一个事务的开-提之间（拆到事务外 = partial 会出现「boundary 落了而
        // conversationId 没更新」的半写）
        assertThat(events.indexOf("primary")).isGreaterThan(events.indexOf("tx-begin"));
        assertThat(events.indexOf("primary")).isLessThan(events.indexOf("tx-commit"));
        assertThat(events.indexOf("extra")).isGreaterThan(events.indexOf("primary"));
        assertThat(events.indexOf("extra")).isLessThan(events.indexOf("tx-commit"));
        assertThat(tx.began.get()).as("成功路径只开一个事务").isEqualTo(1);
        assertThat(tx.committed.get()).isEqualTo(1);
        assertThat(tx.rolledBack.get()).isZero();
    }

    @Test
    @DisplayName("事务重载：额外动作抛错 → 整体回滚（两处写同生共死，无半写）")
    void txOverload_extraActionFailureRollsBackWholeTransaction() {
        List<String> events = new ArrayList<>();
        RecordingTxManager tx = new RecordingTxManager(events);
        TransactionTemplate template = new TransactionTemplate(tx);

        assertThatThrownBy(() -> SqliteBusyRetry.executeInTxWithBusyRetry("test-op", template,
            () -> {
                events.add("primary");
                return "written";
            },
            () -> {
                throw new IllegalStateException("updateConversationId failed");
            }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("updateConversationId failed");

        assertThat(tx.committed.get()).as("额外动作失败 → 主写一并回滚（原子性保住）").isZero();
        assertThat(tx.rolledBack.get()).isEqualTo(1);
        assertThat(events).doesNotContain("tx-commit");
    }

    @Test
    @DisplayName("事务重载：BUSY_SNAPSHOT → 每次尝试开新事务（新读快照），失败事务各自回滚、成功那次提交")
    void txOverload_retriesInFreshTransaction() {
        List<String> events = new ArrayList<>();
        RecordingTxManager tx = new RecordingTxManager(events);
        TransactionTemplate template = new TransactionTemplate(tx);

        AtomicInteger calls = new AtomicInteger();
        String out = SqliteBusyRetry.executeInTxWithBusyRetry("test-op", template,
            () -> {
                int n = calls.incrementAndGet();
                events.add("primary#" + n);
                if (n <= 2) {
                    throw new RuntimeException("wrapped", new IllegalStateException(BUSY_SNAPSHOT_MSG));
                }
                return "ok";
            },
            () -> events.add("extra"));

        assertThat(out).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
        assertThat(tx.began.get()).as("BUSY 重试必须换新事务（同事务内重试拿不到新快照，纯属浪费）").isEqualTo(3);
        assertThat(tx.rolledBack.get()).isEqualTo(2);
        assertThat(tx.committed.get()).isEqualTo(1);
        // 前两次失败事务在 primary 就抛了 → extra 只被成功那次触及（重试不产生多余写）
        assertThat(events.stream().filter("extra"::equals)).hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 调用方义务守卫：外层事务 = 重试无效 → fail loud（warn）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("调用时已存在活动事务 → warn（重试拿不到新快照）；不阻断业务但绝不静默")
    void ambientTransactionIsWarnedLoud() {
        ListAppender<ILoggingEvent> app = attachWarnCapture();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            String out = SqliteBusyRetry.executeWithBusyRetry("test-op", () -> "ok");
            assertThat(out).as("守卫只告警不阻断（业务语义不变）").isEqualTo("ok");
            assertThat(app.list.stream().map(ILoggingEvent::getFormattedMessage))
                .as("调用链被外层 @Transactional 包住 = 重试结构性失效，必须 warn 暴露")
                .anyMatch(m -> m.contains("已存在活动事务"));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            detachWarnCapture(app);
        }
    }

    // ── 日志捕获（logback ListAppender，镜像 CachedMcBoundaryWiringCcTest 既有模式）────

    private static ListAppender<ILoggingEvent> attachErrorCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SqliteBusyRetry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachErrorCapture(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SqliteBusyRetry.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static ListAppender<ILoggingEvent> attachWarnCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SqliteBusyRetry.class);
        // 测试 logback-test.xml 已把 com.nexusai 升到 DEBUG（WARN/ERROR 必然可见）；此处显式降级只为
        // 让「WARN 事件确实产生」的断言不依赖外部配置，收尾恢复原级别（避免污染同 JVM 其它用例）。
        logger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachWarnCapture(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SqliteBusyRetry.class);
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(Level.DEBUG); // logback-test.xml 的 com.nexusai 级别
    }
}
