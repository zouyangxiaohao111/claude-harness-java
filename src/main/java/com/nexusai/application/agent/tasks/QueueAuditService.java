package com.nexusai.application.agent.tasks;

import com.nexusai.repository.session.entity.QueueOperationRecord;
import com.nexusai.repository.session.mapper.QueueOperationMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 队列审计落库端（对齐 CC queue-operation · OD-D11）· 消费 {@link NotificationQueue} 审计 sink。
 *
 * <p><b>WHY</b>：CC 每次入队/出队/移除/拉回落编辑经 recordQueueOperation 写一条
 * {@code {type:'queue-operation', operation, timestamp, sessionId, content?}}（messageQueueManager.ts
 * logOperation :28-38 → sessionStorage.ts:1464 recordQueueOperation，fire-and-forget 恒本地）。
 * Java 端以 {@code queue_operation} 表（V68）承载同一诊断目的（排查「命令怎么丢」），
 * 不进模型/UI/resume（loadSessionFile 丢弃非 TranscriptMessage 同款隔离）。
 *
 * <p><b>装配（MINOR 5）</b>：依赖注入在字段（{@code notificationQueue}/{@code queueOperationMapper}），
 * {@link #registerIntoNotificationQueue() @PostConstruct} 仅做一件事 —— 把本类注册为
 * NotificationQueue 审计 sink。用 @PostConstruct 而非 ApplicationRunner：NotificationQueue 是
 * 直接注入字段，Spring 保证其 bean 先于本类 @PostConstruct 就绪（无循环依赖 ——
 * TaskConfiguration/NotificationQueue 均不依赖本类；CronIdleExecutor 字段注入先例无环）。
 *
 * <p><b>注册前事件不审计</b>（startup 窗口，诊断可接受）：从 NotificationQueue bean 被创建到本类
 * @PostConstruct 完成之间的队列操作不落审计。与 CC「进程启动即具备 recordQueueOperation」相比有
 * 极短盲窗；队列审计仅诊断（排查命令丢失），窗口内事件丢失可接受。
 *
 * <p><b>异步双保险（红线 3 非阻塞）</b>：{@link NotificationQueue} 自持分发线程只把 record
 * submit 给本类 {@link #accept}，本类又只 submit 到自有 {@link #auditExecutor} 后立即返回 ——
 * 落库绝不阻塞 NotificationQueue mutator 热路径；DB 失败 {@code log.warn} 静默（不抛）。
 */
@Component
public class QueueAuditService implements Consumer<QueueOperationRecord> {

    private static final Logger log = LoggerFactory.getLogger(QueueAuditService.class);

    /** 审计写入专用 executor · 单线程 daemon · insert 串行（同队列变更序落库）。
     *
     *  <p>诊断性质（MINOR 4）：与 NotificationQueue 分发 executor 构成两级无界单线程队列 ——
     *  DB 挂起时本队列内存累积（仅诊断数据，不阻塞队列功能热路径）；后续如需可改有界 + 丢弃最旧。 */
    private final ExecutorService auditExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queue-audit-writer");
        t.setDaemon(true);
        return t;
    });

    /** 命令队列（TaskConfiguration @Bean）· sink 注册目标；null（非 Spring 单测）→ 跳过注册。 */
    @Autowired(required = false)
    private NotificationQueue notificationQueue;

    /** queue_operation 表 mapper · 审计落库；null（非 Spring 单测）→ accept 丢弃。 */
    @Autowired(required = false)
    private QueueOperationMapper queueOperationMapper;

    /**
     * 启动装配 · 注入 TaskConfiguration 的 NotificationQueue bean 并注册本类为审计 sink。
     *
     * <p>见类 JavaDoc：@PostConstruct 仅承担 registerAuditSink 一件事（MINOR 5），不在此做任何
     * 业务逻辑。注册前事件不审计（startup 窗口）。
     */
    @PostConstruct
    void registerIntoNotificationQueue() {
        if (notificationQueue != null) {
            notificationQueue.registerAuditSink(this);
            if (log.isInfoEnabled()) {
                log.info("[QueueAuditService] 已注册为 NotificationQueue 审计 sink（队列操作 → queue_operation 表）");
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[QueueAuditService] notificationQueue 未注入（非 Spring 上下文），跳过审计 sink 注册");
        }
    }

    /**
     * 接收单条审计 · 只 submit 到 {@link #auditExecutor} 后立即返回，绝不阻塞调用线程
     * （调用方为 NotificationQueue 自持分发线程）。
     *
     * @param record 审计记录（null → 丢弃）
     */
    @Override
    public void accept(QueueOperationRecord record) {
        if (record == null) {
            return;
        }
        auditExecutor.submit(() -> persist(record));
    }

    /**
     * 落库 · insertSelective（跳过 null 列 → created_at 走 V68 DEFAULT datetime('now')，Java 不设值）。
     *
     * <p>fail-soft（红线 3）：mapper 未注入 / 任何 DB 异常 → log.warn/debug 静默，不抛、不重试
     * （审计仅诊断，丢几条不阻塞队列主流程）。
     */
    private void persist(QueueOperationRecord record) {
        try {
            if (queueOperationMapper == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[QueueAuditService] queueOperationMapper 未注入，丢弃审计: op={} session={}",
                        record.getOperation(), record.getSessionId());
                }
                return;
            }
            queueOperationMapper.insertSelective(record);
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[QueueAuditService] 队列审计落库失败（静默不阻塞队列）: op={} session={} err={}",
                    record.getOperation(), record.getSessionId(), e.getMessage());
            }
        }
    }
}
