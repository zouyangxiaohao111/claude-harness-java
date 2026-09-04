package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.repository.session.entity.QueueOperationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [queue-audit OD-D11] NotificationQueue 审计 sink 意图测试。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: 对齐 CC messageQueueManager.ts logOperation —— 每次
 * enqueue/dequeue/remove/popAll 写一条 queue-operation（enqueue/popAll 带 content，dequeue/remove
 * 不带），clear/reset/peek/getCommandsByMaxPriority 不写，空返回不触发。Java 以旁路
 * auditSink 承载（mutator 锁内只 submit → AUDIT_EXECUTOR 分发线程异步调 sink，绝不阻塞热路径）。
 * 变异点：
 * <ul>
 *   <li>mutator 不触发 auditSink → 排查「命令怎么丢」无据可查 → 红</li>
 *   <li>dequeue/remove 误带 content / clear 误写 → 语义漂移（CC 对照） → 红</li>
 *   <li>sink 抛异常冒泡到 mutator → 审计故障拖垮队列热路径 → 红</li>
 * </ul>
 *
 * <p><b>异步注意（MINOR 3）</b>：auditSink 自持分发线程异步调 sink —— 单测必须 await/latch，
 * 同步断言会 flaky。故用 {@link #awaitSize} 轮询等待记录数。
 */
class NotificationQueueAuditSinkTest {

    private static final int AWAIT_SECONDS = 3;

    /** 注册 sink：追加到线程安全 list。 */
    private static List<QueueOperationRecord> registerSink(NotificationQueue q) {
        List<QueueOperationRecord> records = Collections.synchronizedList(new ArrayList<>());
        q.registerAuditSink(records::add);
        return records;
    }

    /** 轮询等待 records 达到 expected 条（异步分发需等待；3s 未达 → 断言失败）。 */
    private static void awaitSize(List<?> records, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (records.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(records)
            .as("审计记录数必须达到 %d（NotificationQueue AUDIT_EXECUTOR 异步分发，需等待）", expected)
            .hasSize(expected);
    }

    /** 短等待断言「不再新增」（clear/reset/空返回不触发）。 */
    private static void awaitNoMore(List<?> records, int after) throws InterruptedException {
        Thread.sleep(200);
        assertThat(records).as("不得新增审计记录（clear/reset/空返回不触发）").hasSize(after);
    }

    private static QueueItem promptItem(String value, String sessionId) {
        // 10-arg：value/mode/priority/agentId/uuid/isMeta/workload/skipSlashCommands/origin/sessionId
        return new QueueItem(value, "prompt", Priority.NEXT, null, null, false, null, false, null, sessionId);
    }

    // ============================================================================
    // enqueue / enqueuePendingNotification → 'enqueue'（带 content + 身份字段）
    // ============================================================================

    @Test
    @DisplayName("enqueue 触发 auditSink：op='enqueue' 带 content + priority 小写 + 身份字段")
    void enqueue_auditsEnqueueWithContent() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);

        q.enqueue(promptItem("你好 prompt", "sess-1"));

        awaitSize(records, 1);
        QueueOperationRecord rec = records.get(0);
        // WHY: CC logOperation('enqueue', command.value) —— 入队必须留痕且带原文（排查命令怎么丢）
        assertThat(rec.getOperation()).isEqualTo("enqueue");
        assertThat(rec.getContent()).isEqualTo("你好 prompt");
        // Java 增强身份字段（plan §4.2）：sessionId/mode/uuid/priority/workload
        assertThat(rec.getSessionId()).isEqualTo("sess-1");
        assertThat(rec.getMode()).isEqualTo("prompt");
        // priority 存小写 'next'（默认补 NEXT，enum name 转小写）
        assertThat(rec.getPriority()).isEqualTo("next");
    }

    @Test
    @DisplayName("enqueuePendingNotification 触发 auditSink：op='enqueue' priority 小写 'later'")
    void enqueuePendingNotification_auditsEnqueueLater() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);

        q.enqueuePendingNotification(new QueueItem("通知", "task-notification"));

        awaitSize(records, 1);
        QueueOperationRecord rec = records.get(0);
        assertThat(rec.getOperation()).isEqualTo("enqueue");
        assertThat(rec.getContent()).isEqualTo("通知");
        assertThat(rec.getPriority()).isEqualTo("later");
        assertThat(rec.getMode()).isEqualTo("task-notification");
    }

    @Test
    @DisplayName("enqueue(null) 不触发（空守卫同款 CC 无命令不写）")
    void enqueueNull_doesNotAudit() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);

        q.enqueue(null);
        q.enqueuePendingNotification(null);

        awaitNoMore(records, 0);
    }

    // ============================================================================
    // dequeue / dequeueAll / dequeueAllMatching → 'dequeue'（无 content）
    // ============================================================================

    @Test
    @DisplayName("dequeue 触发 auditSink：op='dequeue' 无 content")
    void dequeue_auditsDequeueWithoutContent() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        q.enqueue(promptItem("待出队", "sess-1"));
        awaitSize(records, 1);

        Optional<QueueItem> d = q.dequeue(cmd -> cmd.mode().equals("prompt"));

        awaitSize(records, 2);
        assertThat(d).isPresent();
        QueueOperationRecord rec = records.get(1);
        assertThat(rec.getOperation()).isEqualTo("dequeue");
        assertThat(rec.getContent()).as("dequeue 不带 content（CC :191 logOperation('dequeue')）").isNull();
        assertThat(rec.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("dequeueAll 批量出队：每条打一条 'dequeue'（无 content）")
    void dequeueAll_auditsPerItem() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        q.enqueue(promptItem("a", "sess-1"));
        q.enqueue(promptItem("b", "sess-1"));
        awaitSize(records, 2);

        q.dequeueAll();

        awaitSize(records, 4);
        assertThat(records.subList(2, 4))
            .as("批量出队逐条打 'dequeue'（CC dequeueAll :208-210）")
            .extracting(QueueOperationRecord::getOperation)
            .containsExactly("dequeue", "dequeue");
        assertThat(records.subList(2, 4))
            .extracting(QueueOperationRecord::getContent)
            .containsOnlyNulls();
    }

    @Test
    @DisplayName("dequeueAllMatching 匹配批量出队：每条 'dequeue'；全不匹配空返回不触发")
    void dequeueAllMatching_auditsMatchedOnly() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        q.enqueue(promptItem("match-1", "sess-1"));
        q.enqueue(promptItem("keep", "sess-2"));
        awaitSize(records, 2);

        // 阶段二：换新 sink，仅捕获 dequeueAllMatching 产生的审计（旧 sink 已替换不再收）
        List<QueueOperationRecord> phase2 = Collections.synchronizedList(new ArrayList<>());
        q.registerAuditSink(phase2::add);
        List<QueueItem> matched = q.dequeueAllMatching(cmd -> cmd.value().startsWith("match"));

        awaitSize(phase2, 1);
        assertThat(matched).extracting(QueueItem::value).containsExactly("match-1");
        assertThat(phase2.get(0).getOperation()).isEqualTo("dequeue");
        assertThat(phase2.get(0).getContent()).isNull();

        // 空返回（谓词无匹配）→ 不触发
        List<QueueOperationRecord> phase3 = Collections.synchronizedList(new ArrayList<>());
        q.registerAuditSink(phase3::add);
        q.dequeueAllMatching(cmd -> cmd.value().startsWith("nope"));
        awaitNoMore(phase3, 0);
    }

    @Test
    @DisplayName("dequeue 空队列 → Optional.empty 且不触发审计")
    void dequeueOnEmpty_doesNotAudit() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);

        Optional<QueueItem> d = q.dequeue();

        assertThat(d).isEmpty();
        awaitNoMore(records, 0);
    }

    // ============================================================================
    // remove / removeByFilter → 'remove'（无 content）
    // ============================================================================

    @Test
    @DisplayName("remove 按引用移除：每条实际移除项打 'remove'（无 content）；空入参不触发")
    void remove_auditsRemovedItems() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        QueueItem drop = promptItem("drop", "sess-1");
        QueueItem keep = promptItem("keep", "sess-2");
        q.enqueue(drop);
        q.enqueue(keep);
        awaitSize(records, 2);

        q.remove(List.of(drop));

        awaitSize(records, 3);
        QueueOperationRecord rec = records.get(2);
        assertThat(rec.getOperation()).isEqualTo("remove");
        assertThat(rec.getContent()).as("remove 不带 content（CC :289-291）").isNull();
        assertThat(rec.getSessionId()).isEqualTo("sess-1");

        // 空入参不触发
        List<QueueOperationRecord> phase2 = Collections.synchronizedList(new ArrayList<>());
        q.registerAuditSink(phase2::add);
        q.remove(List.of());
        q.remove(null);
        awaitNoMore(phase2, 0);
    }

    @Test
    @DisplayName("removeByFilter 谓词移除：每条 'remove'；空移除不触发")
    void removeByFilter_auditsRemoved() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        q.enqueue(promptItem("x", "sess-1"));
        q.enqueue(promptItem("y", "sess-2"));
        awaitSize(records, 2);

        List<QueueItem> removed = q.removeByFilter(cmd -> "sess-1".equals(cmd.sessionId()));

        awaitSize(records, 3);
        assertThat(removed).extracting(QueueItem::value).containsExactly("x");
        assertThat(records.get(2).getOperation()).isEqualTo("remove");
        assertThat(records.get(2).getContent()).isNull();

        List<QueueOperationRecord> phase2 = Collections.synchronizedList(new ArrayList<>());
        q.registerAuditSink(phase2::add);
        q.removeByFilter(cmd -> "ghost".equals(cmd.sessionId()));
        awaitNoMore(phase2, 0);
    }

    // ============================================================================
    // popForEdit → 'popAll'（带 content）
    // ============================================================================

    @Test
    @DisplayName("popForEdit：op='popAll' 带 content + 保持原序；非匹配项留队列；空弹出不触发")
    void popForEdit_auditsPopAllWithContent() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        q.enqueue(promptItem("first", "sess-1"));
        q.enqueue(promptItem("second", "sess-1"));
        q.enqueue(promptItem("other-session", "sess-2"));
        // 10-arg：value/mode/priority/agentId/uuid/isMeta/workload/skipSlashCommands/origin/sessionId
        q.enqueuePendingNotification(new QueueItem("note", "task-notification", Priority.LATER, null, null, false, null, false, null, "sess-1"));
        awaitSize(records, 4);

        List<QueueItem> popped = q.popForEdit(cmd -> "sess-1".equals(cmd.sessionId())
            && "prompt".equals(cmd.mode()));

        awaitSize(records, 6);
        assertThat(popped).as("popForEdit 返回该会话全部 mode=prompt 项（保持原序）")
            .extracting(QueueItem::value).containsExactly("first", "second");
        // 非匹配项留队列（sess-2 prompt + sess-1 task-notification）
        assertThat(q.dequeueAll()).extracting(QueueItem::value)
            .containsExactlyInAnyOrder("other-session", "note");

        // WHY: CC popAllEditable :471-476 —— 拉回编辑走 'popAll' 且每条带 content
        assertThat(records.subList(4, 6))
            .extracting(QueueOperationRecord::getOperation)
            .containsExactly("popAll", "popAll");
        assertThat(records.subList(4, 6))
            .extracting(QueueOperationRecord::getContent)
            .containsExactly("first", "second");
        assertThat(records.subList(4, 6))
            .extracting(QueueOperationRecord::getSessionId)
            .containsOnly("sess-1");

        // 空弹出不触发
        List<QueueOperationRecord> phase2 = Collections.synchronizedList(new ArrayList<>());
        q.registerAuditSink(phase2::add);
        q.popForEdit(cmd -> "ghost".equals(cmd.sessionId()));
        awaitNoMore(phase2, 0);
    }

    // ============================================================================
    // clear / reset / peek / getCommandsByMaxPriority 不写 · drainForQuery 不双计
    // ============================================================================

    @Test
    @DisplayName("clear / reset 不触发审计（对齐 CC clearCommandQueue/resetCommandQueue :322-337）")
    void clearAndReset_doNotAudit() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        q.enqueue(promptItem("a", "sess-1"));
        awaitSize(records, 1);

        q.clear();
        awaitNoMore(records, 1);

        q.enqueue(promptItem("b", "sess-1"));
        awaitSize(records, 2);
        q.reset();
        awaitNoMore(records, 2);
    }

    @Test
    @DisplayName("peek / getCommandsByMaxPriority 只读不触发审计")
    void peekAndGetCommands_doNotAudit() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        q.enqueue(promptItem("a", "sess-1"));
        awaitSize(records, 1);

        q.peek(cmd -> cmd.value().startsWith("a"));
        q.getCommandsByMaxPriority(Priority.NEXT);
        q.hasCommandsInQueue();
        q.size();

        awaitNoMore(records, 1);
    }

    @Test
    @DisplayName("drainForQuery 消费经内部 remove 打一条 'remove'（不双计）")
    void drainForQuery_auditsOnceViaRemove() throws Exception {
        NotificationQueue q = new NotificationQueue();
        List<QueueOperationRecord> records = registerSink(q);
        // 全局 prompt（sessionId=null，agentId=null）→ drainForQuery(false, null, null) 可消费
        q.enqueue(new QueueItem("全局 prompt", "prompt"));
        awaitSize(records, 1);

        List<QueueItem> consumed = q.drainForQuery(false, null, null);

        awaitSize(records, 2);
        assertThat(consumed).extracting(QueueItem::value).containsExactly("全局 prompt");
        // WHY: drainForQuery 不另打（内部 remove 覆盖，plan §4.3）；双计会让审计计数失真
        assertThat(records).extracting(QueueOperationRecord::getOperation)
            .containsExactly("enqueue", "remove");
        assertThat(q.hasCommandsInQueue()).isFalse();
    }

    // ============================================================================
    // sink null / sink 异常 → 静默不阻塞（红线 3）
    // ============================================================================

    @Test
    @DisplayName("sink 未注册（null）→ mutator 照常跑不抛")
    void nullSink_doesNotThrow() {
        NotificationQueue q = new NotificationQueue();   // 未注册 sink

        q.enqueue(promptItem("a", "sess-1"));
        q.dequeue();
        q.enqueuePendingNotification(new QueueItem("note", "task-notification"));
        q.clear();
        q.reset();

        assertThat(q.size()).isZero();
    }

    @Test
    @DisplayName("sink 抛异常 → 分发线程捕获不冒泡到 mutator")
    void throwingSink_doesNotPropagate() throws Exception {
        NotificationQueue q = new NotificationQueue();
        q.registerAuditSink(rec -> {
            throw new IllegalStateException("sink 故障");
        });

        q.enqueue(promptItem("a", "sess-1"));   // 不应抛
        q.dequeue();                             // 不应抛

        // 静默成功（无异常即 PASS；等待窗口保证分发线程跑过异常分支）
        Thread.sleep(200);
        assertThat(q.hasCommandsInQueue()).isFalse();
    }
}
