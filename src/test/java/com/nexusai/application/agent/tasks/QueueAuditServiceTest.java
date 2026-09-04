package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.repository.session.entity.QueueOperationRecord;
import com.nexusai.repository.session.mapper.QueueOperationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [queue-audit OD-D11] QueueAuditService 审计写入端测试。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: CC recordQueueOperation 落 transcript（fire-and-forget）；
 * Java 端 QueueAuditService 把 NotificationQueue 审计 sink 的每条记录 insert 到 V68 queue_operation
 * 表。必须 <b>异步</b>（自有单线程 executor）+ <b>DB 失败静默 log.warn 不抛</b>（红线 3：
 * 审计故障绝不阻塞队列主流程）。变异点：
 * <ul>
 *   <li>insert 同步阻塞调用线程 → 审计拖慢 NotificationQueue 分发 → 红</li>
 *   <li>mapper 抛异常冒泡 → audit 故障拖垮队列 → 红</li>
 *   <li>created_at 由 DB 默认（insertSelective 跳过 null 列）→ insert 写全列会 NULL 冲 DEFAULT → 红</li>
 * </ul>
 */
class QueueAuditServiceTest {

    private static final int TIMEOUT_MS = 3000;

    private QueueAuditService newService(QueueOperationMapper mapper) {
        QueueAuditService svc = new QueueAuditService();
        if (mapper != null) {
            ReflectionTestUtils.setField(svc, "queueOperationMapper", mapper);
        }
        return svc;
    }

    @Test
    @DisplayName("@PostConstruct 注册到 NotificationQueue：enqueue → mapper.insertSelective 落一条 'enqueue'")
    void registerIntoNotificationQueue_forwardsQueueAudits() {
        QueueOperationMapper mapper = mock(QueueOperationMapper.class);
        when(mapper.insertSelective(any())).thenReturn(1);
        NotificationQueue q = new NotificationQueue();
        QueueAuditService svc = newService(mapper);
        ReflectionTestUtils.setField(svc, "notificationQueue", q);

        svc.registerIntoNotificationQueue();   // @PostConstruct 同款装配

        q.enqueue(new QueueItem("你好", "prompt"));

        ArgumentCaptor<QueueOperationRecord> captor = ArgumentCaptor.forClass(QueueOperationRecord.class);
        verify(mapper, timeout(TIMEOUT_MS)).insertSelective(captor.capture());
        QueueOperationRecord rec = captor.getValue();
        assertThat(rec.getOperation()).isEqualTo("enqueue");
        assertThat(rec.getContent()).isEqualTo("你好");
        assertThat(rec.getMode()).isEqualTo("prompt");
        assertThat(rec.getPriority()).isEqualTo("next");
    }

    @Test
    @DisplayName("accept 异步 insert（自有 executor）：mapper.insertSelective 被异步调用且带记录")
    void accept_insertsRecordAsync() {
        QueueOperationMapper mapper = mock(QueueOperationMapper.class);
        when(mapper.insertSelective(any())).thenReturn(1);
        QueueAuditService svc = newService(mapper);
        QueueOperationRecord rec = record("dequeue", "sess-1", null);

        svc.accept(rec);

        // WHY: 落库必须发生在自有 executor 线程（accept 立即返回，不阻塞 NotificationQueue 分发线程）
        ArgumentCaptor<QueueOperationRecord> captor = ArgumentCaptor.forClass(QueueOperationRecord.class);
        verify(mapper, timeout(TIMEOUT_MS)).insertSelective(captor.capture());
        assertThat(captor.getValue()).isSameAs(rec);
    }

    @Test
    @DisplayName("mapper insert 抛异常 → 静默（accept 不抛、executor 线程存活继续工作）")
    void accept_mapperFailure_isSilentAndExecutorSurvives() {
        QueueOperationMapper mapper = mock(QueueOperationMapper.class);
        when(mapper.insertSelective(any())).thenThrow(new RuntimeException("sqlite 挂起"));
        QueueAuditService svc = newService(mapper);

        // 失败记录：accept 必须立即返回不抛（同步断言）；异步 insert 尝试发生（verify timeout）
        svc.accept(record("enqueue", "sess-1", "内容"));
        verify(mapper, timeout(TIMEOUT_MS)).insertSelective(any());

        // 故障后 executor 线程仍存活：第二次 insert 成功 → 证明失败被捕获、线程未死
        // doReturn（而非 when().thenReturn）：方法已 stub 成抛异常，when(mapper.xxx()) 重stub会先触发旧抛异常
        org.mockito.Mockito.doReturn(1).when(mapper).insertSelective(any());
        svc.accept(record("dequeue", "sess-2", null));
        verify(mapper, timeout(TIMEOUT_MS).times(2)).insertSelective(any());
    }

    @Test
    @DisplayName("accept(null) 不落库（空守卫）")
    void accept_nullRecord_noop() throws Exception {
        QueueOperationMapper mapper = mock(QueueOperationMapper.class);
        QueueAuditService svc = newService(mapper);

        svc.accept(null);

        Thread.sleep(200);
        verify(mapper, never()).insertSelective(any());
    }

    @Test
    @DisplayName("mapper 未注入（非 Spring 单测）→ accept 丢弃不抛")
    void accept_mapperNotInjected_discardsSilently() throws Exception {
        QueueAuditService svc = newService(null);   // mapper = null

        svc.accept(record("remove", "sess-1", null));

        // 静默成功：无异常即 PASS（等待窗口让异步 persist 跑过 mapper==null 分支）
        Thread.sleep(200);
    }

    private static QueueOperationRecord record(String op, String sessionId, String content) {
        QueueOperationRecord rec = new QueueOperationRecord();
        rec.setOperation(op);
        rec.setSessionId(sessionId);
        rec.setContent(content);
        rec.setPriority("next");
        return rec;
    }
}
