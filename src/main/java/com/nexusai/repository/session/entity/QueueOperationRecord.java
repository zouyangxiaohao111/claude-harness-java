package com.nexusai.repository.session.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

/**
 * queue_operation 表（V68）记录 · 队列审计单行（对齐 CC queue-operation 事件）。
 *
 * <p><b>WHY（OD-D11 · messageQueueManager.ts logOperation :28-38）</b>：CC 每次
 * enqueue/dequeue/remove/popAll 写一条 {@code {type:'queue-operation', operation,
 * sessionId, content?}} 到 transcript（sessionStorage.ts:1464 recordQueueOperation）。
 * Java 多会话服务把队列审计落到本表（仅诊断，不进模型/UI/resume），排查「命令怎么丢」。
 *
 * <p>列映射（MyBatis-Flex camelCase → snake_case 自动映射）：
 * <table>
 *   <tr><th>字段</th><th>DB 列</th><th>说明</th></tr>
 *   <tr><td>{@link #id}</td><td>id</td><td>INTEGER PK AUTOINCREMENT（SQLite 自增）</td></tr>
 *   <tr><td>{@link #operation}</td><td>operation</td><td>'enqueue'|'dequeue'|'remove'|'popAll'（NOT NULL）</td></tr>
 *   <tr><td>{@link #sessionId}</td><td>session_id</td><td>队列项 sessionId（可空 = 全局/无会话）</td></tr>
 *   <tr><td>{@link #uuid}</td><td>uuid</td><td>队列项 uuid（可空）</td></tr>
 *   <tr><td>{@link #mode}</td><td>mode</td><td>队列项 mode（'prompt'|'task-notification'|...）</td></tr>
 *   <tr><td>{@link #priority}</td><td>priority</td><td>小写 'now'|'next'|'later'（非 enum name 大写）</td></tr>
 *   <tr><td>{@link #workload}</td><td>workload</td><td>billing 头标记（如 'cron'，可空）</td></tr>
 *   <tr><td>{@link #content}</td><td>content</td><td>仅 enqueue/popAll 带原文（CC logOperation 语义）</td></tr>
 * </table>
 *
 * <p>{@code created_at} <b>不在本实体映射</b> —— V68 DEFAULT datetime('now') 由 DB 生成，
 * Java 端不设值（统一 DB 文本格式保 created_at 排序，plan §4.2）。因此落库必须用
 * {@code insertSelective}（跳过 null 列），否则 insert() 会显式写 NULL 绕过 DEFAULT
 * → NOT NULL 约束失败（先例：AttachmentRecord V64 实测注释）。
 *
 * <p>POJO（非 record）：MyBatis-Flex insert 需要 setter；record 不能直接当实体。
 */
@Table("queue_operation")
public class QueueOperationRecord {

    /** 自增主键（INTEGER PK AUTOINCREMENT）；insertSelective 不进列清单，SQLite 自增分配。 */
    @Id(keyType = KeyType.Auto)
    private Long id;
    /** 操作类型：'enqueue'|'dequeue'|'remove'|'popAll'（CC QueueOperation，NOT NULL）。 */
    private String operation;
    /** 队列项 sessionId（CC sessionId ambient 的 Java 显式等价；null = 全局/DURABLE 无会话）。 */
    private String sessionId;
    /** 队列项 uuid（命令唯一标识；可空）。 */
    private String uuid;
    /** 队列项 mode（'prompt'|'task-notification'|...；可空）。 */
    private String mode;
    /** 队列项 priority 小写：'now'|'next'|'later'（可空 = 异常路径未补默认）。 */
    private String priority;
    /** billing 头 workload（如 'cron'；可空）。 */
    private String workload;
    /** 原文（仅 enqueue/popAll 携带；dequeue/remove 为 null）。 */
    private String content;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getWorkload() { return workload; }
    public void setWorkload(String workload) { this.workload = workload; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
