package com.nexusai.application.agent.tasks;

import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.repository.session.entity.QueueOperationRecord;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 统一命令队列 · 对齐 CC utils/messageQueueManager.ts（单队列 + mode 语义，MQ-1）。
 *
 * <p>单一进程级队列承载所有命令：用户输入 (prompt) / 后台任务通知 (task-notification) /
 * cron 通知 / scheduled 通知（CC messageQueueManager.ts:40-53 注释：
 * "All commands — user input, task notifications, orphaned permissions — go
 * through this single queue"）。cron 通知与普通通知共用本队列 + 优先级（CC 无独立 cron 队列）。
 *
 * <p>实现对齐 CC {@code commandQueue: QueuedCommand[]}（Array + splice）：同优先级内先进先出
 * （FIFO），非 {@link java.util.concurrent.PriorityBlockingQueue} 堆序。Java 多生产者
 * （TestJob / BackgroundTaskRunner / CommandHookExecutor）+ 多消费者（LlmAgentLoop drain /
 * CronIdleExecutor poll）→ 全部操作 synchronized 保证 FIFO 与分区语义（CC 模块级单线程）。
 *
 * <p>优先级 (CC messageQueueManager.ts:151-155 PRIORITY_ORDER)：now(0) &gt; next(1) &gt; later(2)。
 * 同级 FIFO（CC dequeue :175-185 数组线性扫描严格更小优先级 → 同优先级首个插入项先出，
 * 与 PriorityBlockingQueue 堆序不同 —— 堆序不保证同级 FIFO）。
 *
 * <p>入队默认优先级由方法决定（对齐 CC enqueue :128-135 默认 'next' /
 * enqueuePendingNotification :142-149 默认 'later'），record 不硬编码默认值。
 *
 * <p>线程安全：CC 为 JS 单线程模块队列；Java 生产方（BackgroundTaskRunner 异步线程 /
 * CommandHookExecutor / cron）与消费方（LlmAgentLoop drain）跨线程，所有读写方法 synchronized。
 */
public class NotificationQueue {

    private static final Logger log = LoggerFactory.getLogger(NotificationQueue.class);

    /** CC messageQueueManager.ts:151-155 — PRIORITY_ORDER = { now:0, next:1, later:2 } */
    public enum Priority {
        NOW(0), NEXT(1), LATER(2);

        private final int order;

        Priority(int order) { this.order = order; }
        public int order() { return order; }
    }

    /** CC workloadContext.ts:26 — export const WORKLOAD_CRON: Workload = 'cron' */
    public static final String WORKLOAD_CRON = "cron";

    /** CC mode 常量：子 agent 仅消费 mode='task-notification' 的通知 (query.ts:1577) */
    public static final String MODE_TASK_NOTIFICATION = "task-notification";

    /** CC mode 常量：用户输入 (query.ts:1633 consumedCommands 含 mode==='prompt') */
    public static final String MODE_PROMPT = "prompt";

    /**
     * 消息来源判别 · CC original: {@code MessageOrigin}（textInputTypes.ts:341
     * {@code QueuedCommand.origin?: MessageOrigin}；{@code {kind: 'channel', server} | {kind: 'human'} |
     * {@code {kind: 'task-notification'} | ...}）。
     *
     * <p>入队携带（useManageMCPConnections.ts:528 {@code origin: {kind:'channel', server:client.name}}）
     * → 消费侧 {@code wrapCommandText}（messages.ts:3742-3746 透传 + 5496-5512 分支判别）——
     * channel 消息 = 「非用户 + untrusted」，绝不落入 human 分支（prompt-injection 面不扩大）。
     *
     * @param kind   CC original: kind — 'channel' | 'human' | 'task-notification' | ...
     * @param server CC original: server — 仅 channel 分支携带（来源 server 名，注入文本生成「A message arrived from {server}」）
     */
    public record MessageOrigin(String kind, @Nullable String server) {}

    /**
     * CC QueuedCommand (textInputTypes.ts:299-345)：{value, mode, priority?, uuid?, skipSlashCommands?, isMeta?, workload?, agentId?}。
     * 融合 cron_scheduler 侧 (isMeta/workload/skipSlashCommands) 与 WF-3 侧 (uuid) → 8 字段完整字段集
     * + origin（S07，CC textInputTypes.ts:341 等价物）+ sessionId（CRON-D5）。
     *
     * @param value             CC original: value (useScheduledTasks.ts:73) — 原始 prompt / 通知文本
     * @param mode              CC original: mode (useScheduledTasks.ts:74) — 'prompt' | 'task-notification' | ...
     * @param priority          CC original: priority (useScheduledTasks.ts:75) — 'now' | 'next' | 'later'; null 由入队方法补默认
     * @param agentId           CC original: agentId (textInputTypes.ts:357) — 目标 agent; 主线程 null
     * @param uuid              CC original: uuid (textInputTypes.ts:309) — 命令唯一标识
     * @param isMeta            CC original: isMeta (useScheduledTasks.ts:76) — 系统生成消息, UI 隐藏但模型可见
     * @param workload          CC original: workload (useScheduledTasks.ts:81) — billing 头 cc_workload= 标记（如 'cron'）
     * @param skipSlashCommands CC original: skipSlashCommands (textInputTypes.ts:320) — true 时 '/'-开头也按纯文本送模型
     * @param origin            CC original: origin (textInputTypes.ts:341) — 命令来源 provenance;
     *                          null = human 键盘输入 (CC "undefined = human (keyboard)")。channel 来源由
     *                          入队方显式携带（useManageMCPConnections.ts:528），normalizePriority 重建透传
     *                          不丢弃（CC messages.ts:3742-3746 origin ?? 回退）
     * @param sessionId         CRON-D5 新增（CC QueuedCommand 无此字段 — CC 单进程单会话 ambient 上下文
     *                          天然存在；Java 多会话 web 服务须显式携带）— cron fire 时透传创建会话
     *                          sessionId（ScheduleDto.sessionId：SESSION scope 生命周期绑定 / DURABLE
     *                          scope 归属对话=创建会话，CronCreateTool DURABLE 分支存创建会话），
     *                          队列跨线程边界 MDC 不可传，消费线程（CronIdleExecutor.runOneAgentLoop）据此
     *                          恢复 MDC + cwd 归组创建会话（对齐 CC 单进程 ambient）。null = 无会话上下文
     *                          （DURABLE 无会话直建/普通 prompt），回落全局会话/user.dir。
     * @param boundProject      批次X Q2 新增（CC QueuedCommand 无此字段 — CC durable 项目锚=文件位置
     *                          cronTasks.ts:74-83，队列跨线程无需传；Java 全局单表须显式落列 V23，
     *                          fire 时经 QueueItem 透传）— DURABLE 任务创建会话绑定项目
     *                          （ScheduleDto.boundProject，SESSION/无会话 null），队列跨线程边界 MDC
     *                          不可传，消费线程（CronIdleExecutor.runOneAgentLoop）据此把项目上下文
     *                          注入执行线程（CwdResolution.runWithCwdOverride），使 CwdResolution.getCwd
     *                          解析到创建项目而非 user.dir（对齐 CC durable 文件位置锚项目语义）。
     *                          [cron-durable-session-fire] 同时作 drain 判别符：boundProject!=null =
     *                          DURABLE（创建会话已关也照常 fire），null = SESSION（必须会话存活）。
     *                          null = 无项目锚（SESSION 走 sessionId 恢复路径 / DURABLE 无会话直建），
     *                          回落 user.dir。
     * @param scheduleId        保留透传调度 id（TestJob.execute JobDataMap:99 取到 → enqueueLead 参数透传，
     *                          SESSION scope 亦可携带但不使用）。原 cron-transcript 方案 a 用其派生
     *                          per-task 虚拟会话键，该机制已随 [cron-durable-session-fire] 删除（DURABLE
     *                          fire 归创建会话），本字段仅供日志归组/诊断。null = 无调度上下文
     *                          （普通 prompt / missed 启动通知）。
     */
    public record QueueItem(
        String value,
        String mode,
        @Nullable Priority priority,
        @Nullable String agentId,
        @Nullable String uuid,
        boolean isMeta,
        @Nullable String workload,
        boolean skipSlashCommands,
        @Nullable MessageOrigin origin,
        @Nullable String sessionId,
        @Nullable String boundProject,
        @Nullable String scheduleId,
        /**
         * [OD-D5/OD-D13 busy 图片] 附件列表 · CC original: {@code pastedContents}
         * （handlePromptSubmit.ts:340 忙时 enqueue 携带 raw pastedContents；
         * attachments.ts:1060-1083 每条 queued_command 各自 pastedContents → buildImageContentBlocks）。
         *
         * <p>仅 busy-queued 携图（enqueueBusyPrompt 提取 ≤5MB base64 image 直传项）；
         * task-notification/coordinator/channel/cron 不携。drain 消费点逐项注册
         * （registerRunPromptImages per-item），消费即清（CC per-command 独立附件，
         * attachments.ts:1060-1083 —— 绝不共享桶）。null = 无附件（纯文本）。
         */
        @Nullable List<AttachmentRequest> attachments
    ) {
        /**
         * 12-arg 兼容构造（attachments=null）— [OD-D5] 既有 canonical 调用方零改动
         * （normalizePriority 等 12 参直传路径仍可用，attachments 留空 = 无附件）。
         */
        public QueueItem(String value, String mode, @Nullable Priority priority, @Nullable String agentId,
                         @Nullable String uuid, boolean isMeta, @Nullable String workload,
                         boolean skipSlashCommands, @Nullable MessageOrigin origin,
                         @Nullable String sessionId, @Nullable String boundProject,
                         @Nullable String scheduleId) {
            this(value, mode, priority, agentId, uuid, isMeta, workload, skipSlashCommands, origin,
                sessionId, boundProject, scheduleId, null);
        }
        /**
         * 11-arg 兼容构造（origin + sessionId + boundProject，scheduleId=null）— cron-transcript
         * 保留旧 canonical 签名（scheduleId 后置 null），使既有 11 参调用（TestJob fire 路径 / 测试）
         * 零改动。
         */
        public QueueItem(String value, String mode, @Nullable Priority priority, @Nullable String agentId,
                         @Nullable String uuid, boolean isMeta, @Nullable String workload,
                         boolean skipSlashCommands, @Nullable MessageOrigin origin,
                         @Nullable String sessionId, @Nullable String boundProject) {
            this(value, mode, priority, agentId, uuid, isMeta, workload, skipSlashCommands, origin,
                sessionId, boundProject, null);
        }

        /**
         * 10-arg 兼容构造（origin 携带方 + sessionId 后置）— CRON-D5 保留旧 canonical 签名
         * （boundProject=null 后置），使既有 10 参调用零改动（不属批次X 写集；仅 TestJob fire
         * 路径显式传 boundProject）。
         */
        public QueueItem(String value, String mode, @Nullable Priority priority, @Nullable String agentId,
                         @Nullable String uuid, boolean isMeta, @Nullable String workload,
                         boolean skipSlashCommands, @Nullable MessageOrigin origin,
                         @Nullable String sessionId) {
            this(value, mode, priority, agentId, uuid, isMeta, workload, skipSlashCommands, origin,
                sessionId, null, null, null);
        }

        /**
         * 9-arg 兼容构造（origin 携带方，如 ChannelNotification）— CRON-D5 保留旧 canonical 签名
         * （origin 后置 sessionId=null），使 MCP 域既有 9 参调用零改动（不属 cron 写集）。
         */
        public QueueItem(String value, String mode, @Nullable Priority priority, @Nullable String agentId,
                         @Nullable String uuid, boolean isMeta, @Nullable String workload,
                         boolean skipSlashCommands, @Nullable MessageOrigin origin) {
            this(value, mode, priority, agentId, uuid, isMeta, workload, skipSlashCommands, origin,
                null, null, null);
        }

        /**
         * 8-arg 兼容构造（origin=null）· [S07] 保留既有全部 8 参调用方零改动
         * （CC messages.ts:3742-3746 origin ?? 兜底：无 origin 项消费侧按 mode 判别）。
         */
        public QueueItem(String value, String mode, @Nullable Priority priority, @Nullable String agentId,
                         @Nullable String uuid, boolean isMeta, @Nullable String workload,
                         boolean skipSlashCommands) {
            this(value, mode, priority, agentId, uuid, isMeta, workload, skipSlashCommands, null,
                null, null, null);
        }

        /** 2-arg 构造（CommandHookExecutor 等消费方）— priority/uuid/agentId 留 null, 入队方法按 CC 默认补 priority */
        public QueueItem(String value, String mode) {
            this(value, mode, null, null, null, false, null, false, null, null, null, null);
        }

        /** 4-arg 兼容构造（LlmAgentLoop/BackgroundTaskRunner/测试）— uuid 默认 null, 非 meta + 无 workload */
        public QueueItem(String value, String mode, @Nullable Priority priority, @Nullable String agentId) {
            this(value, mode, priority, agentId, null, false, null, false, null, null, null, null);
        }

        /** 6-arg 兼容构造（TestJob fire 入队 / CronIdleExecutorTest）— uuid 默认 null, skipSlashCommands=false */
        public QueueItem(String value, String mode, @Nullable Priority priority, @Nullable String agentId,
                         boolean isMeta, @Nullable String workload) {
            this(value, mode, priority, agentId, null, isMeta, workload, false, null, null, null, null);
        }

        /** 7-arg 兼容构造（NotificationQueueFifoTest / cron 侧 skipSlashCommands 用例）— uuid 默认 null */
        public QueueItem(String value, String mode, @Nullable Priority priority, @Nullable String agentId,
                         boolean isMeta, @Nullable String workload, boolean skipSlashCommands) {
            this(value, mode, priority, agentId, null, isMeta, workload, skipSlashCommands, null, null, null, null);
        }
    }


    /** CC messageQueueManager.ts:53 — const commandQueue: QueuedCommand[] = []（Array + splice） */
    private final List<QueueItem> queue = new ArrayList<>();

    // ============================================================================
    // [queue-audit] 队列审计 sink（对齐 CC queue-operation · OD-D11）· 仅诊断旁路
    // ============================================================================

    /** 审计 sink · 每次 enqueue/dequeue/remove/popAll 提交一条 QueueOperationRecord；
     *  null = 未注册（启动窗口事件不审计，诊断可接受）。 */
    private volatile Consumer<QueueOperationRecord> auditSink;

    /** 审计分发线程 · 单线程 daemon（仿上方 NOTIFY_EXECUTOR :239 同款）——
     *  mutator 锁内<b>只 submit</b> QueueOperationRecord，本分发线程异步调 sink，
     *  绝不执行 sink 代码到热路径 / 绝不阻塞入队线程（红线 3：audit 不阻塞、不抛）。
     *
     *  <p>诊断性质（MINOR 4）：本 executor 任务队列与 {@code QueueAuditService} 自有
     *  executor 构成两级无界单线程队列——DB 挂起时内存累积（仅诊断数据，不阻塞队列
     *  功能热路径）；后续如需可改有界 + 丢弃最旧。 */
    private static final ExecutorService AUDIT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queue-audit-dispatch");
        t.setDaemon(true);
        return t;
    });

    // ============================================================================
    // P3 事件驱动（对齐 CC useQueueProcessor.ts:35-67 useSyncExternalStore 订阅队列快照）
    // ============================================================================

    /** onChange 监听器（线程安全 CopyOnWriteArrayList）。 */
    private final CopyOnWriteArrayList<Runnable> onChangeListeners = new CopyOnWriteArrayList<>();

    /** 异步分发执行器 · 单线程 daemon · listener 绝不在入队线程同步跑（防嵌套 synchronized 死锁）。 */
    private static final ExecutorService NOTIFY_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queue-on-change");
        t.setDaemon(true);
        return t;
    });

    /** 通知合并标志 · fire 只标记 + 入队，dispatch 完成 finally 复位。 */
    private final AtomicBoolean notifyPending = new AtomicBoolean(false);

    // ============================================================================
    // 入队（write operations）· 对齐 CC messageQueueManager.ts:128-149
    // ============================================================================

    /**
     * 入队 — 对齐 CC enqueue (messageQueueManager.ts:128-135)。
     * 默认优先级 'next'（用户输入 prompt / bash / orphaned-permission 先于 task 通知处理）。
     */
    public synchronized void enqueue(QueueItem item) {
        if (item == null) return;
        QueueItem normalized = normalizePriority(item, Priority.NEXT);
        queue.add(normalized);
        if (log.isDebugEnabled()) {
            log.debug("NotificationQueue.enqueue: mode={}, priority={}, agentId={}, origin={}, scheduleId={}",
                item.mode(), normalized.priority(), item.agentId(), originDesc(normalized.origin()),
                normalized.scheduleId());
        }
        // [DEL-13] 入队不发 hook · 对齐 CC messageQueueManager.ts 入队路径零 hook（E-TS07-08）：
        // Notification hook 仅由系统通知路径触发（LlmAgentLoop A11 / ElicitationHandler / AgentLoopContext）。
        // [queue-audit] 入队审计（带 content · CC logOperation 'enqueue' :131-134）
        submitAudit(auditRecord("enqueue", normalized));
        // [P3] 队列变更通知（事件驱动消费，对齐 CC useSyncExternalStore 订阅队列快照）
        fireOnChange();
    }

    /**
     * 入队（后台通知，默认 LATER）— 对齐 CC enqueuePendingNotification (messageQueueManager.ts:142-149)。
     * 用户输入永不因系统消息饿死。
     */
    public synchronized void enqueuePendingNotification(QueueItem item) {
        if (item == null) return;
        QueueItem normalized = normalizePriority(item, Priority.LATER);
        queue.add(normalized);
        if (log.isDebugEnabled()) {
            log.debug("NotificationQueue.enqueuePendingNotification: mode={}, priority={}, agentId={}, origin={}, scheduleId={}",
                item.mode(), normalized.priority(), item.agentId(), originDesc(normalized.origin()),
                normalized.scheduleId());
        }
        // [queue-audit] 入队审计（带 content · CC logOperation 'enqueue' :145-148）
        submitAudit(auditRecord("enqueue", normalized));
        // [P3] 队列变更通知（事件驱动消费）
        fireOnChange();
    }

    /** CC :128-135/142-149 — priority 缺省在入队方法内补 (command.priority ?? default)。origin 透传（S07）+ sessionId 透传（CRON-D5）+ boundProject 透传（批次X Q2）。 */
    private static QueueItem normalizePriority(QueueItem item, Priority fallback) {
        if (item.priority() != null) return item;
        // [S07] origin 透传（ChannelNotification 入队携带 {kind:'channel',server}，
        // 重建不得丢弃——CC messages.ts:3742-3746 origin 全链透传）
        // [CRON-D5 改1] sessionId 透传（cron fire 携带创建会话 id，重建丢弃则消费线程归组失效）
        // [批次X Q2] boundProject 透传（DURABLE cron fire 携带项目锚，重建丢弃则消费线程
        // 恢复项目上下文失效 → 回落 user.dir）
        // [PROBE-DUR 修订 2026-08-22] scheduleId 透传（DURABLE cron fire 携带调度 id）——仅供日志
        // 归组/诊断（NotificationQueue 唯一消费点 = 日志），per-task 虚拟会话键机制已删（master a6dce0b0）
        // [OD-D5] attachments 透传（busy-queued 携图）：priority==null 路径逐字段重建必须保留附件，
        //   否则忙时排队带图消息经 normalizePriority（priority 缺省补 NEXT）丢图（reflector MAJOR-2）。
        return new QueueItem(item.value(), item.mode(), fallback, item.agentId(),
            item.uuid(), item.isMeta(), item.workload(), item.skipSlashCommands(), item.origin(),
            item.sessionId(), item.boundProject(), item.scheduleId(), item.attachments());
    }

    /** debug 日志用 origin 描述（channel → "channel:server"，null → "null"）。 */
    private static String originDesc(@Nullable MessageOrigin origin) {
        if (origin == null) return "null";
        return origin.kind() + (origin.server() != null ? ":" + origin.server() : "");
    }

    // ============================================================================
    // [queue-audit] 注册 + 审计构造/提交
    // ============================================================================

    /**
     * 注册审计 sink · 由 {@code QueueAuditService} @PostConstruct 装配（本类不依赖 Spring）。
     *
     * <p>sink 为 {@code volatile} 单槽：后注册替换先注册（生产端唯一 = QueueAuditService；
     * 测试可注入捕获器断言）。注册前（startup 窗口）mutator 照常跑、不审计。
     *
     * @param sink 审计消费端（null → no-op，不替换既有 sink）
     */
    public void registerAuditSink(Consumer<QueueOperationRecord> sink) {
        if (sink != null) {
            this.auditSink = sink;
        }
    }

    /**
     * 构造审计记录 · CC logOperation (messageQueueManager.ts:28-38) 语义：
     * <ul>
     *   <li>enqueue / popAll → 带 content（CC :131-134/:471-476 传 command.value 字符串）</li>
     *   <li>dequeue / remove → 不带 content（CC :191/:289-291/:310-312 无 content）</li>
     * </ul>
     * Java 增强身份字段（sessionId/uuid/mode/priority/workload）供入出队配对诊断；
     * priority 存小写（QueueItem.Priority name() 转小写 → 'now'|'next'|'later'）。
     */
    private static QueueOperationRecord auditRecord(String operation, QueueItem item) {
        QueueOperationRecord rec = new QueueOperationRecord();
        rec.setOperation(operation);
        rec.setSessionId(item.sessionId());
        rec.setUuid(item.uuid());
        rec.setMode(item.mode());
        rec.setPriority(item.priority() != null ? item.priority().name().toLowerCase() : null);
        rec.setWorkload(item.workload());
        if (("enqueue".equals(operation) || "popAll".equals(operation)) && item.value() != null) {
            rec.setContent(item.value());
        }
        return rec;
    }

    /**
     * 异步提交审计 · mutator 锁内调用：只把 record submit 到 {@link #AUDIT_EXECUTOR}，
     * 分发线程再调 sink（不执行任何 sink 代码到入队线程）。
     *
     * <p>fail-soft（红线 3）：sink null / 提交被拒 → debug 不抛；分发线程 sink 异常 → warn
     * 不抛。空返回场景由调用方守卫（不构造 record 即不进本方法）。
     */
    private void submitAudit(QueueOperationRecord rec) {
        Consumer<QueueOperationRecord> sink = auditSink;
        if (sink == null || rec == null) {
            return;
        }
        try {
            AUDIT_EXECUTOR.submit(() -> {
                try {
                    sink.accept(rec);
                } catch (Exception e) {
                    if (log.isWarnEnabled()) {
                        log.warn("NotificationQueue 审计 sink 分发异常（已捕获，不阻塞队列）: op={} session={} err={}",
                            rec.getOperation(), rec.getSessionId(), e.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (log.isDebugEnabled()) {
                log.debug("NotificationQueue 审计提交被拒（executor 关闭，跳过审计）: {}", e.getMessage());
            }
        }
    }

    // ============================================================================
    // 出队（read/write operations）· 对齐 CC messageQueueManager.ts:167-266
    // ============================================================================
    /**
     * 出队最高优先级项 — 对齐 CC dequeue (messageQueueManager.ts:167-193)。
     *
     * <p>同级 FIFO：线性扫描取「优先级严格更小」的项（CC :175-185），同优先级取首个（先插入者）。
     *
     * @param filter 可选谓词；仅匹配 filter 的项参与出队，非匹配项原地保留（CC :177-179）。
     * @return 最高优先级匹配项；队列空或全不匹配返回 {@link Optional#empty()}
     */
    public synchronized Optional<QueueItem> dequeue(@Nullable Predicate<QueueItem> filter) {
        int bestIdx = -1;
        int bestOrder = Integer.MAX_VALUE;
        for (int i = 0; i < queue.size(); i++) {
            QueueItem cmd = queue.get(i);
            if (filter != null && !filter.test(cmd)) continue;
            int order = priorityOrder(cmd);
            if (order < bestOrder) {   // CC :181 严格更小才替换 → 同优先级保留先插入者 (FIFO)
                bestIdx = i;
                bestOrder = order;
            }
        }
        if (bestIdx == -1) {
            return Optional.empty();
        }
        QueueItem dequeued = queue.remove(bestIdx);
        if (log.isDebugEnabled()) log.debug("NotificationQueue.dequeue: mode={}", dequeued.mode());
        // [queue-audit] 出队审计（无 content · CC logOperation 'dequeue' :191）——空/全不匹配早退不触发
        submitAudit(auditRecord("dequeue", dequeued));
        // [P3] 队列变更通知（事件驱动消费）
        fireOnChange();
        return Optional.of(dequeued);
    }

    /** 无 filter 出队（兼容旧调用）。 */
    public Optional<QueueItem> dequeue() {
        return dequeue(null);
    }

    /**
     * 查看最高优先级项但不移除 — 对齐 CC peek (messageQueueManager.ts:219-238)。
     */
    public synchronized Optional<QueueItem> peek(@Nullable Predicate<QueueItem> filter) {
        int bestIdx = -1;
        int bestOrder = Integer.MAX_VALUE;
        for (int i = 0; i < queue.size(); i++) {
            QueueItem cmd = queue.get(i);
            if (filter != null && !filter.test(cmd)) continue;
            int order = priorityOrder(cmd);
            if (order < bestOrder) {
                bestIdx = i;
                bestOrder = order;
            }
        }
        if (bestIdx == -1) return Optional.empty();
        return Optional.of(queue.get(bestIdx));
    }

    /**
     * 全部出队 — 对齐 CC dequeueAll (messageQueueManager.ts:199-213)。
     */
    public synchronized List<QueueItem> dequeueAll() {
        if (queue.isEmpty()) return new ArrayList<>();
        List<QueueItem> commands = new ArrayList<>(queue);
        queue.clear();
        if (log.isDebugEnabled()) log.debug("NotificationQueue.dequeueAll: {} items", commands.size());
        // [queue-audit] 批量出队审计（每条无 content · CC dequeueAll :208-210 for _cmd logOperation('dequeue')）
        for (QueueItem c : commands) {
            submitAudit(auditRecord("dequeue", c));
        }
        // [P3] 队列变更通知（事件驱动消费）
        fireOnChange();
        return commands;
    }

    /**
     * 按谓词全部出队（全队列扫描）— 对齐 CC dequeueAllMatching (messageQueueManager.ts:244-266)。
     *
     * <p>修正旧实现「首个不匹配即 break」缺陷：CC 扫描整队列，匹配项全取、非匹配项保留。
     */
    public synchronized List<QueueItem> dequeueAllMatching(Predicate<QueueItem> predicate) {
        List<QueueItem> matched = new ArrayList<>();
        List<QueueItem> remaining = new ArrayList<>();
        for (QueueItem cmd : queue) {
            if (predicate.test(cmd)) {
                matched.add(cmd);
            } else {
                remaining.add(cmd);
            }
        }
        if (matched.isEmpty()) {
            return new ArrayList<>();
        }
        queue.clear();
        queue.addAll(remaining);
        if (log.isDebugEnabled()) log.debug("NotificationQueue.dequeueAllMatching: {} matched", matched.size());
        // [queue-audit] 批量出队审计（每条无 content · CC dequeueAllMatching :262-264）
        for (QueueItem c : matched) {
            submitAudit(auditRecord("dequeue", c));
        }
        // [P3] 队列变更通知（事件驱动消费）
        fireOnChange();
        return matched;
    }

    /**
     * 取 <= 给定优先级阈值的全部命令（不删除）— 对齐 CC messageQueueManager.ts:525-532。
     *
     * <p>CC: threshold = PRIORITY_ORDER[maxPriority]，filter priority.order <= threshold。
     * 'later' 返回全部；'now' 仅返回 now。priority==null 按 CC cmd.priority ?? 'next' 计为 next。
     */
    public synchronized List<QueueItem> getCommandsByMaxPriority(@Nullable Priority maxPriority) {
        int threshold = maxPriority != null ? maxPriority.order() : Priority.NEXT.order();
        List<QueueItem> result = new ArrayList<>();
        for (QueueItem cmd : queue) {
            if (priorityOrder(cmd) <= threshold) {
                result.add(cmd);
            }
        }
        if (log.isDebugEnabled()) log.debug("NotificationQueue.getCommandsByMaxPriority: max={} → {} 条",
            maxPriority, result.size());
        return result;
    }

    /** CC PRIORITY_ORDER[cmd.priority ?? 'next'] → Java 顺序值。 */
    private static int priorityOrder(QueueItem cmd) {
        Priority p = cmd.priority() != null ? cmd.priority() : Priority.NEXT;
        return p.order();
    }

    /**
     * 按引用身份移除指定命令 — 对齐 CC messageQueueManager.ts:273-292。
     * 调用方须传队列中同一对象引用（如 getCommandsByMaxPriority 返回的项）。
     */
    public synchronized void remove(List<QueueItem> commandsToRemove) {
        if (commandsToRemove == null || commandsToRemove.isEmpty()) return;
        List<QueueItem> removedItems = new ArrayList<>();
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (containsIdentity(commandsToRemove, queue.get(i))) {
                removedItems.add(queue.remove(i));
            }
        }
        if (log.isDebugEnabled() && !removedItems.isEmpty()) {
            log.debug("NotificationQueue.remove: removed {} items", removedItems.size());
        }
        // [queue-audit] 移除审计（每条无 content · CC remove :289-291 logOperation('remove')）
        // 只对「实际按引用移除」的项打点（并发消费竞态下已不在队列的传入项不打 —— 空守卫不触发）
        for (QueueItem removed : removedItems) {
            submitAudit(auditRecord("remove", removed));
        }
        // [P3] 队列变更通知（事件驱动消费；drainForQuery 经本方法消费 → 覆盖 mid-turn drain 变化点）
        fireOnChange();
    }

    /** CC remove :280 — includes 按引用标识 (SameValueZero → 对象引用)。 */
    private static boolean containsIdentity(List<QueueItem> list, QueueItem target) {
        for (QueueItem item : list) {
            if (item == target) return true;
        }
        return false;
    }

    /**
     * 按谓词移除并返回被移除项 — 对齐 CC removeByFilter (messageQueueManager.ts:298-316)。
     *
     * <p><b>[queue-audit 孤儿声明 · MINOR 2]</b>：ChatController /queue/pop 已改调
     * {@link #popForEdit}（审计 'popAll'）后，本方法<b>生产侧无调用方</b>。保留不改删
     * —— CC messageQueueManager.ts:298-316 有对应物（removeByFilter 仍被 CC 保留），
     * 且本方法承载「通用谓词移除 + 审计 'remove'」语义，供未来生产路径复用；如需清理
     * 登记待清理项，勿删。
     */
    public synchronized List<QueueItem> removeByFilter(Predicate<QueueItem> predicate) {
        List<QueueItem> removed = new ArrayList<>();
        for (int i = queue.size() - 1; i >= 0; i--) {
            QueueItem cmd = queue.get(i);
            if (predicate.test(cmd)) {
                removed.add(0, cmd);   // 保持原顺序 (CC :304 removed.unshift)
                queue.remove(i);
            }
        }
        if (log.isDebugEnabled() && !removed.isEmpty()) {
            log.debug("NotificationQueue.removeByFilter: removed {} items", removed.size());
        }
        // [queue-audit] 移除审计（每条无 content · CC removeByFilter :310-312）；空移除不触发
        for (QueueItem cmd : removed) {
            submitAudit(auditRecord("remove", cmd));
        }
        // [P3] 队列变更通知（事件驱动消费）
        fireOnChange();
        return removed;
    }

    /**
     * 弹出可编辑排队命令（Esc/↑ 拉回编辑）— 对齐 CC popAllEditable (messageQueueManager.ts:428-484)。
     *
     * <p><b>ChatController /queue/pop 专用入口（reflector MAJOR 定死）</b>：与
     * {@link #removeByFilter} 相同的「谓词匹配 + 保持原序移除 + 返回被移除项」，但审计
     * op=<b>'popAll'（带 content）</b> —— 区分「拉回编辑」（Esc/↑，CC popAllEditable :471-476
     * 每条带 content logOperation('popAll')）与「模型消费通用移除」（removeByFilter → 'remove' 无 content）。
     *
     * <p>谓词契约（MINOR 1）：返回该谓词命中的<b>全部</b>项（保持旧序，与现 removeByFilter
     * 谓词语义完全一致）；调用方取首条回填（复刻 ChatController 旧行为：移除全部 prompt
     * 命令仅回填最旧一条）。无匹配 → 返回空 List、不触发审计（CC :432-443 空守卫同款）。
     *
     * @param predicate 匹配谓词（ChatController 传 sessionId + mode=prompt 过滤）
     * @return 匹配并移除的项（原顺序）；无匹配 → 空 List
     */
    public synchronized List<QueueItem> popForEdit(Predicate<QueueItem> predicate) {
        List<QueueItem> removed = new ArrayList<>();
        for (int i = queue.size() - 1; i >= 0; i--) {
            QueueItem cmd = queue.get(i);
            if (predicate.test(cmd)) {
                removed.add(0, cmd);   // 保持原顺序 (CC :304 removed.unshift 同款)
                queue.remove(i);
            }
        }
        if (log.isDebugEnabled() && !removed.isEmpty()) {
            log.debug("NotificationQueue.popForEdit: popped {} items", removed.size());
        }
        // [queue-audit] popAll 审计（带 content · CC popAllEditable :471-476）；空弹出不触发
        for (QueueItem cmd : removed) {
            submitAudit(auditRecord("popAll", cmd));
        }
        // [P3] 队列变更通知（事件驱动消费）
        fireOnChange();
        return removed;
    }

    // ============================================================================
    // agentId scoping · [OPD-TP-03] 对齐 CC query.ts:1569+1574-1577
    // ============================================================================

    /**
     * CC query.ts:1570-1578 + 1632-1643 完整 drain 语义。
     *
     * <ol>
     *   <li>阈值快照 {@code getCommandsByMaxPriority(sleepRan ? 'later' : 'next')} (:1570-1571)</li>
     *   <li>filter：排除 slash command (:1573)；主线程 → agentId===undefined (:1574)；
     *       subagent → mode==='task-notification' && agentId==currentAgentId (:1577)</li>
     *   <li>消费：mode prompt / task-notification 才 removeFromQueue (:1632-1643)</li>
     * </ol>
     *
     * <p>通过 filter 但非 prompt/task-notification 模式的项（如 scheduled）不消费、留队列。
     *
     * <p><b>[3a] sessionId 归属过滤</b> · CC query.ts:1574 主线程（agentId===undefined）的多会话展开：
     * CC 单进程单主会话 → 归属结构性无歧义（cron 无 agentId 恒归唯一主会话）；Java 每会话的 turn
     * 都被 {@code deriveAgentIdForCommandFilter} 归一成"主线程"（agentId==null），若只滤 agentId，
     * 会话 B 的回合会把会话 A 的 cron / prompt 捞走（跨会话捞走缺口，A-queue-ownership-probe §2.2）。
     * 归属不变式：<b>每条命令有且仅有一个合法消费者</b> ——
     * <ul>
     *   <li>{@code cmd.sessionId() != null}（归创建会话）：只允许该会话自己的 turn 消费；
     *       会话空闲时由 {@code CronIdleExecutor} 代跑（带该会话上下文）。</li>
     *   <li>{@code cmd.sessionId() == null}（全局/DURABLE 无会话）：只允许全局执行器
     *       （{@code CronIdleExecutor} + GLOBAL_SESSION_UUID）消费，任何具体会话 turn 一律不捞。</li>
     * </ul>
     * 因此：具体会话 turn（currentSessionId != null）只捞 {@code cmd.sessionId()
     * .equals(currentSessionId)} 的本会话命令，sessionId==null 全局命令不捞（交 CronIdleExecutor）；
     * 无会话主线程（currentSessionId == null，forTest/headless 等价全局执行器）只捞 sessionId==null
     * 全局命令，不捞任何归会话命令。
     *
     * <p><b>[3e] sessionId 统一 short 后裸 equals 铁律</b>：[session-id-short] {@code QueueItem.sessionId}
     * 与 {@code AgentState.sessionId} 同为 short（sess-xxx），裸 equals 必中 —— 原 canonicalUuid
     * 归一化比较失去存在前提（双形态根因已消除）；存量派生 UUID 串历史项由 CronIdleExecutor
     * 归组路径 originalKey(String) 兼容反解（阶段1），不在本热路径。
     *
     * @param sleepRan         本迭代之前的 assistant 消息是否含 Sleep tool_use（query.ts:1566）
     * @param currentAgentId   当前 agentId; null 表示主线程
     * @param currentSessionId 当前会话 short（sess-xxx）; null 表示全局/无会话主线程（forTest/headless）——
     *                         仅捞 sessionId==null 全局命令; 具体会话 turn 只捞本会话命令（[3a]）
     * @return 被消费的 prompt/task-notification 项（已移出队列）
     */
    public synchronized List<QueueItem> drainForQuery(boolean sleepRan, @Nullable String currentAgentId,
                                                      @Nullable String currentSessionId) {
        Priority threshold = sleepRan ? Priority.LATER : Priority.NEXT;
        List<QueueItem> snapshot = getCommandsByMaxPriority(threshold);
        boolean isMainThread = currentAgentId == null;
        List<QueueItem> consumed = new ArrayList<>();
        for (QueueItem cmd : snapshot) {
            if (isSlashCommand(cmd)) continue;                       // query.ts:1573
            if (isMainThread) {
                if (cmd.agentId() != null) continue;                 // query.ts:1574 子 agent 命令不捞
                if (currentSessionId == null) {
                    // 全局/无会话主线程（forTest/headless）：只捞 sessionId==null 全局命令
                    if (cmd.sessionId() != null) continue;           // 归会话命令 → 该会话 turn 消费
                } else {
                    // 具体会话 turn：[3a] 只捞本会话命令（3e: 双 short 裸 equals 必中）
                    if (cmd.sessionId() == null) continue;           // 全局命令 → CronIdleExecutor（3c）
                    if (!cmd.sessionId().equals(currentSessionId)) {
                        continue;                                    // 别人会话的命令 → 跳过
                    }
                }
            } else {
                if (!MODE_TASK_NOTIFICATION.equals(cmd.mode())
                        || !currentAgentId.equals(cmd.agentId())) {  // query.ts:1577
                    continue;
                }
            }
            // consumedCommands = prompt / task-notification (query.ts:1632-1634)
            if (MODE_PROMPT.equals(cmd.mode()) || MODE_TASK_NOTIFICATION.equals(cmd.mode())) {
                consumed.add(cmd);
            }
        }
        if (!consumed.isEmpty()) {
            remove(consumed);
        }
        if (log.isDebugEnabled()) {
            log.debug("NotificationQueue.drainForQuery(sleepRan={}, agent={}, session={}): threshold={}, consumed={}, size={}",
                sleepRan, currentAgentId, currentSessionId, threshold, consumed.size(), queue.size());
        }
        return consumed;
    }

    /**
     * 是否为应走 slash 命令路径的命令 — 对齐 CC messageQueueManager.ts:541-547。
     *
     * <p>CC: value.trim().startsWith('/') && !skipSlashCommands。skipSlashCommands=true 时
     * （bridge/CCR 消息）'/'开头仍按纯文本送模型。
     */
    public static boolean isSlashCommand(QueueItem cmd) {
        if (cmd == null || cmd.value() == null) return false;
        return cmd.value().trim().startsWith("/") && !cmd.skipSlashCommands();
    }

    /** 队列是否有命令 — 对齐 CC messageQueueManager.ts:104-106 hasCommandsInQueue() */
    public synchronized boolean hasCommandsInQueue() {
        return !queue.isEmpty();
    }

    /** 队列大小 */
    public synchronized int size() {
        return queue.size();
    }

    /** 清空队列 · 对齐 CC clearCommandQueue (messageQueueManager.ts:322-328) */
    public synchronized void clear() {
        if (queue.isEmpty()) return;
        queue.clear();
        if (log.isDebugEnabled()) log.debug("NotificationQueue.clear");
        // [P3] 队列变更通知（事件驱动消费）
        fireOnChange();
    }

    /** 清空并复位 · 对齐 CC resetCommandQueue (messageQueueManager.ts:334-337，测试清理用) */
    public synchronized void reset() {
        queue.clear();
        // [P3] 队列变更通知（事件驱动消费）
        fireOnChange();
    }

    // ============================================================================
    // P3 事件驱动监听（对齐 CC useQueueProcessor.ts:35-67 subscribeToCommandQueue）
    // ============================================================================

    /**
     * 注册队列变更监听器 · P3 事件驱动（对齐 CC useSyncExternalStore subscribeToCommandQueue）。
     *
     * <p>任意写方法（enqueue/dequeue/remove/clear/reset...）末尾 {@link #fireOnChange()} →
     * 异步 dispatch，listener 在 {@link #NOTIFY_EXECUTOR} 独立线程执行（不入队线程同步跑 →
     * 无嵌套 synchronized 死锁，入队线程不被 listener 阻塞）。listener 应幂等（poll 语义天然幂等）。
     *
     * @param listener 变更回调（可为 null → no-op）
     */
    public void registerOnChange(Runnable listener) {
        if (listener != null) {
            onChangeListeners.add(listener);
        }
    }

    /** 注销队列变更监听器（防跨 run/跨 bean 泄漏）。 */
    public void unregisterOnChange(Runnable listener) {
        if (listener != null) {
            onChangeListeners.remove(listener);
        }
    }

    /**
     * 公开 re-fire 入口 · turn 结束（LlmAgentLoop.run() finally markIdle 之后）显式触发一次，
     * 使 now 命令 / 残留 busy-queued 立即被 CronIdleExecutor 事件驱动消费（0 延迟替代 3s 轮询，
     * 对齐 CC useQueueProcessor.ts isQueryActive=false 立即消费）。
     */
    public void notifyChanged() {
        fireOnChange();
    }

    /** 队列变更通知 · 只标记 + 入队异步 dispatch（对齐 CC useSyncExternalStore 快照订阅，0 延迟）。 */
    private void fireOnChange() {
        if (notifyPending.compareAndSet(false, true)) {
            NOTIFY_EXECUTOR.submit(this::dispatchOnChange);
        }
    }

    /** 异步 dispatch · 独立线程遍历 listeners（finally 复位 notifyPending，允许下次 re-fire）。 */
    private void dispatchOnChange() {
        try {
            for (Runnable l : onChangeListeners) {
                try {
                    l.run();
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("NotificationQueue.onChange listener 异常: {}", e.getMessage());
                    }
                }
            }
        } finally {
            notifyPending.set(false);
        }
    }
}
