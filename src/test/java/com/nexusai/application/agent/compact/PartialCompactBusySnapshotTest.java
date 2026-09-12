package com.nexusai.application.agent.compact;

import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.PartialCompactResponse;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [SQLITE_BUSY_SNAPSHOT 修复] partial-compact 事务边界 + 落库重试（真机 500 的根因回归测试）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：真机现象 = {@code POST /partial-compact} 稳定 500，
 * 错误 {@code org.sqlite.SQLiteException: [SQLITE_BUSY_SNAPSHOT] Another database connection has
 * already written to the database}，失败点 = 落库那条语句。根因不是「哪条 SQL 写错」，而是
 * <b>事务边界</b>：SQLite WAL 下事务的第一条 SELECT 固定读快照，快照被其它连接提交顶掉后，该事务内
 * <b>任何写</b>都必然 BUSY_SNAPSHOT（{@code busy_timeout} 对该错误不生效）。旧实现把
 * {@code partialCompact} 整体 {@code @Transactional}，事务内夹着几十秒的 LLM 摘要调用 → 并发写
 * （quartz 触发 / 实时落库 / 其它会话）必然让快照过期 → 写回必失败。
 *
 * <p>归因实测已排除「写回方法选型」这一嫌疑：把落库改回旧写法
 * {@code replaceSessionMessages}（b3b13e7~1）后<b>同样 500</b>（同一个 BUSY_SNAPSHOT，只是失败在
 * 它自己的首条写 {@code ToolCallMapper.deleteByQuery}）—— 证明这是<b>既有缺陷</b>，
 * 与「换成 appendPostCompactMessages」无关。因此本测试钉死的是<b>事务边界</b>与<b>重试</b>，
 * 而不是某一条 SQL。
 *
 * <p><b>RED 条件（改哪一行会红）</b>：
 * <ol>
 *   <li>{@link #partialCompactMustNotBeTransactional()} —— 给 {@code partialCompact} 重新加上
 *       {@code @Transactional}（= 把几十秒 LLM 调用重新关进事务）→ 红。</li>
 *   <li>{@link #summarizeRunsOutsideTransaction_writeInsideIt()} —— 把落库段（TransactionTemplate）
 *       挪到 LLM 调用之前 / 或让事务包住 summarize → 事件顺序断言翻转为红。</li>
 *   <li>{@link #busySnapshotWriteIsRetriedInFreshTransaction()} —— 删掉
 *       {@code persistCompactedMessages} 里的重试循环（只试一次）→ 异常直接抛出 → 红。</li>
 *   <li>{@link #nonBusyErrorIsNotRetried()} —— 把重试判定放宽成「任何异常都重试」→ 红。</li>
 * </ol>
 */
@DisplayName("[SQLITE_BUSY_SNAPSHOT] partial-compact 事务边界 + 落库重试")
class PartialCompactBusySnapshotTest {

    private static final String SESSION = "s1";

    /** 真机异常文案（org.sqlite.SQLiteException 的 message，原样取自 3461 实例的 500 响应体）。 */
    private static final String BUSY_SNAPSHOT_MSG =
        "[SQLITE_BUSY_SNAPSHOT] Another database connection has already written to the database"
            + " (database is locked)";

    // ── 消息工厂（与 PartialCompactServiceTest 同款，保证走同一条编排）────────────

    private static ChatMessageDto msg(String id, Role role) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            "content-" + id, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    private static List<ChatMessageDto> fourMessages() {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(msg("u0", Role.user));
        list.add(msg("a0", Role.assistant));
        list.add(msg("u1", Role.user));
        list.add(msg("a1", Role.assistant));
        return list;
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

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
    // 1. 事务边界：LLM 调用必须在事务外
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>RED</b>：给 {@code partialCompact} 重新加 {@code @Transactional} → 红。
     *
     * <p>这条断言是真机 500 的<b>直接</b>回归门：只要方法上挂了 {@code @Transactional}，
     * Spring 代理就会在方法入口开启事务 → 步骤 1 的 {@code listForResume} 固定读快照 →
     * 几十秒 LLM 摘要期间并发写顶掉快照 → 落库必然 BUSY_SNAPSHOT。单元测试直构不走代理，
     * 所以必须<b>直接</b>对注解本身断言，否则这条回归在单测里看不见。
     */
    @Test
    @DisplayName("RED 门：partialCompact 不得标注 @Transactional（长 LLM 调用不得关进事务）")
    void partialCompactMustNotBeTransactional() throws Exception {
        Method m = PartialCompactService.class.getMethod(
            "partialCompact", String.class, PartialCompactRequest.class);
        assertThat(m.isAnnotationPresent(Transactional.class))
            .as("partialCompact 必须无 @Transactional —— 否则事务跨几十秒 LLM 调用，"
                + "SQLite WAL 下读快照必被并发写顶掉 → 落库必抛 SQLITE_BUSY_SNAPSHOT（真机 500 根因）")
            .isFalse();
    }

    /**
     * <b>RED</b>：把落库短事务挪到 LLM 调用之前 / 让事务包住 summarize → 事件顺序断言红。
     *
     * <p>断言三个事实（都直接对应根因）：
     * <ol>
     *   <li>{@code summarize}（几十秒的 LLM 调用）发生在<b>第一个事务开启之前</b> —— 事务内不夹长调用；</li>
     *   <li>落库写发生在事务<b>内</b>（tx-begin … write … tx-commit）—— 两处写仍原子；</li>
     *   <li>整条编排只开<b>一个</b>事务（不因拆事务把 append + conversationId 拆成两段非原子写）。</li>
     * </ol>
     */
    @Test
    @DisplayName("LLM 摘要调用在事务外、落库写在事务内（顺序 + 单事务）")
    void summarizeRunsOutsideTransaction_writeInsideIt() {
        List<String> events = new ArrayList<>();
        RecordingTxManager tx = new RecordingTxManager(events);

        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.appendPostCompactMessages(anyString(), anyList())).thenAnswer(inv -> {
            events.add("write");
            return inv.getArgument(1);
        });
        when(summary.summarize(anyString(), anyList())).thenAnswer(inv -> {
            events.add("summarize"); // 几十秒的 LLM 调用（真机实测 31s）
            return new CompactConversation.SummaryResult("summary ok", null);
        });

        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);
        svc.setTransactionManager(tx);

        PartialCompactResponse resp = svc.partialCompact(SESSION,
            new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null));

        assertThat(resp.messages()).isNotEmpty();
        // ① LLM 调用在事务外：summarize 早于第一个 tx-begin（顺序翻转 = 根因回归）
        assertThat(events).contains("summarize", "tx-begin", "write", "tx-commit");
        assertThat(events.indexOf("summarize"))
            .as("LLM 摘要调用必须在事务开启之前（事件序 = %s）", events)
            .isLessThan(events.indexOf("tx-begin"));
        // ② 落库写在事务内
        assertThat(events.indexOf("write")).isGreaterThan(events.indexOf("tx-begin"));
        assertThat(events.indexOf("write")).isLessThan(events.indexOf("tx-commit"));
        // ③ 单个短事务包住两处写（append + conversationId 仍原子）
        assertThat(tx.began.get()).as("整条编排只开一个事务").isEqualTo(1);
        assertThat(tx.committed.get()).isEqualTo(1);
        assertThat(tx.rolledBack.get()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 落库阶段 BUSY_SNAPSHOT 重试（换新事务 = 换新快照）
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>RED</b>：删掉 {@code persistCompactedMessages} 的重试循环 → 第 1 次 BUSY_SNAPSHOT 直接抛出 → 红。
     *
     * <p>为什么必须换事务重试：BUSY_SNAPSHOT 是「读快照已死」，同事务内重试<b>无用</b>
     * （快照无法刷新）。{@code TransactionTemplate.execute} 每次调用都开新事务 → 新快照，
     * 故重试天然正确。本测试同时钉死「重试确实开了新事务」（rolledBack=2 + began=3 + committed=1），
     * 而不只是「调用次数变多」。
     */
    @Test
    @DisplayName("落库 BUSY_SNAPSHOT → 换新事务重试直至成功（幂等，无半写）")
    void busySnapshotWriteIsRetriedInFreshTransaction() {
        List<String> events = new ArrayList<>();
        RecordingTxManager tx = new RecordingTxManager(events);

        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        // 前 2 次 BUSY_SNAPSHOT（真机 MyBatis 包装后的形状：外层 message 不一定带 SQLITE_BUSY，
        //  SQLITE_BUSY_SNAPSHOT 文案在 cause 链上）→ 第 3 次成功。
        AtomicInteger calls = new AtomicInteger();
        when(messageService.appendPostCompactMessages(anyString(), anyList())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            events.add("write#" + n);
            if (n <= 2) {
                throw new RuntimeException(
                    "### Error updating database. Cause: org.sqlite.SQLiteException (see cause)",
                    new IllegalStateException(BUSY_SNAPSHOT_MSG));
            }
            return inv.getArgument(1);
        });
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));

        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);
        svc.setTransactionManager(tx);

        PartialCompactResponse resp = svc.partialCompact(SESSION,
            new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null));

        // 落库最终成功（不再 500）
        assertThat(resp.messages()).isNotEmpty();
        assertThat(resp.conversationId()).isNotBlank();
        assertThat(calls.get()).as("BUSY_SNAPSHOT 两次后第三次成功").isEqualTo(3);
        // 每次重试都是新事务（失败 2 次回滚 + 成功 1 次提交）
        assertThat(tx.began.get()).isEqualTo(3);
        assertThat(tx.rolledBack.get()).as("两次失败事务各自回滚（无半写）").isEqualTo(2);
        assertThat(tx.committed.get()).isEqualTo(1);
        // 幂等：前两次尝试在 appendPostCompactMessages 就抛了 → updateConversationId 未被触及，
        //       只有成功那次调用（重试不产生多余写、也不换 conversationId）
        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        verify(sessionService, times(1)).updateConversationId(anyString(), conv.capture());
        assertThat(conv.getValue()).as("重试前后 conversationId 不变（同一次编排同一个新 id）")
            .isEqualTo(resp.conversationId());
    }

    /**
     * <b>RED</b>：把重试判定放宽成「任何异常都重试」→ 红。
     *
     * <p>非 BUSY 错误（约束冲突 / SQL 语法 / 代码 bug）必须<b>立刻</b>抛出：无限重试会把真 bug
     * 掩盖成「偶发失败」，且对不可重试错误重试纯属浪费时间（规则十二 fail loud）。
     */
    @Test
    @DisplayName("非 BUSY 错误不重试：立即抛出，只开 1 个事务（fail loud）")
    void nonBusyErrorIsNotRetried() {
        List<String> events = new ArrayList<>();
        RecordingTxManager tx = new RecordingTxManager(events);

        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        AtomicInteger calls = new AtomicInteger();
        when(messageService.appendPostCompactMessages(anyString(), anyList())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("NOT NULL constraint failed: messages.id");
        });
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));

        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);
        svc.setTransactionManager(tx);

        assertThatThrownBy(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOT NULL constraint failed");

        assertThat(calls.get()).as("不可重试错误只尝试一次").isEqualTo(1);
        assertThat(tx.rolledBack.get()).isEqualTo(1);
        assertThat(tx.committed.get()).isZero();
    }

    @Test
    @DisplayName("isSqliteBusy：沿 cause 链匹配 SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT；非 BUSY 不匹配")
    void isSqliteBusyMatchesOnlyBusyErrors() {
        // [P2-9 · 2026-09-12] 判别已下沉 SqliteBusyRetry（5 通道共用的单点，不再 PartialCompactService 私有）
        assertThat(SqliteBusyRetry.isSqliteBusy(new RuntimeException(BUSY_SNAPSHOT_MSG))).isTrue();
        assertThat(SqliteBusyRetry.isSqliteBusy(
            new RuntimeException("### Error updating database (see cause)",
                new IllegalStateException("[SQLITE_BUSY] database is locked")))).isTrue();
        assertThat(SqliteBusyRetry.isSqliteBusy(
            new RuntimeException("NOT NULL constraint failed"))).isFalse();
        assertThat(SqliteBusyRetry.isSqliteBusy(
            new RuntimeException("outer", new IllegalArgumentException("inner")))).isFalse();
        assertThat(SqliteBusyRetry.isSqliteBusy(null)).isFalse();
    }

    /**
     * [P2-9 · 2026-09-12] <b>partial 原子性未破</b>：重试包装下沉到共用 {@link SqliteBusyRetry} 后，
     * 落库与 {@code updateConversationId} 仍在<b>同一事务</b>内（共用方法以 {@code extraSameTxAction}
     * 形参承载后者）。
     *
     * <p><b>RED</b>：把 {@code updateConversationId} 挪出 {@code template.execute} 回调（= 两处写各自
     * 独立事务）→ 本用例红（rolledBack 不再等于 1 / extra 事件落在 tx-commit 之后）。
     *
     * <p>为什么重要：两处写必须同生共死 —— 否则会出现「boundary 已落库、conversationId 仍是旧值」
     * 的半写，下一次 partial 会基于旧 conversationId 再压一次（重复压缩 + 归属错乱）。
     */
    @Test
    @DisplayName("[P2-9] 原子性：updateConversationId 在主写之后、同一事务内；其抛错 → 整体回滚（无半写）")
    void partialAtomicityKept_extraActionSharesTransaction() {
        List<String> events = new ArrayList<>();
        RecordingTxManager tx = new RecordingTxManager(events);

        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.appendPostCompactMessages(anyString(), anyList())).thenAnswer(inv -> {
            events.add("write");
            return inv.getArgument(1);
        });
        // 额外同事务动作抛错（非 BUSY）→ 必须整体回滚，且不再开新事务重试（不可重试错误）。
        // updateConversationId 返回 void → 只能用 doAnswer（when(...) 不适用于 void 方法）。
        doAnswer(inv -> {
            events.add("update-conv");
            throw new IllegalStateException("updateConversationId failed");
        }).when(sessionService).updateConversationId(anyString(), anyString());
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));

        PartialCompactService svc = new PartialCompactService(messageService, sessionService, summary);
        svc.setTransactionManager(tx);

        assertThatThrownBy(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("updateConversationId failed");

        // ① 额外动作与主写同事务：write → update-conv 都落在 tx-begin 与回滚之间
        assertThat(events.indexOf("write")).isGreaterThan(events.indexOf("tx-begin"));
        assertThat(events.indexOf("update-conv")).isGreaterThan(events.indexOf("write"));
        assertThat(events).doesNotContain("tx-commit");
        // ② 原子性：整体回滚一次（主写不会单独提交）
        assertThat(tx.began.get()).as("非 BUSY 错误不重试 → 只开 1 个事务").isEqualTo(1);
        assertThat(tx.rolledBack.get()).isEqualTo(1);
        assertThat(tx.committed.get()).isZero();
    }
}
