package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.chat.ChatService;
import com.nexusai.application.chat.SlashCommandInterceptor;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionKeys;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * idle 自动执行 — 对齐 CC useQueueProcessor.ts + queueProcessor.ts:52-87 processQueueIfReady。
 *
 * <p>P0 核心断裂 R-1（WF-D）：Spring 定时轮询统一 {@link NotificationQueue}，队列有主线程命令
 * （agentId==null）且目标 session 无运行中 agent_loop 时，按 CC 语义启动一轮 agent_loop 执行入队 prompt。
 *
 * <p>三闸（对齐 useQueueProcessor.ts:48-60）：无活动 turn（isQueryActive）→ 无 UI（Java 无概念）→
 * 队列非空（queueSnapshot.length）。消费语义对齐 queueProcessor.ts:70-85：slash/bash 单条 dequeue，
 * 其余按同 mode dequeueAllMatching 批量。
 */
@Component
public class CronIdleExecutor {

    private static final Logger log = LoggerFactory.getLogger(CronIdleExecutor.class);

    /**
     * 全局会话 UUID — CRON-D5 后仅作<b>兜底</b>（sessionId=null 无会话 / 非法 UUID / DURABLE
     * 无项目锚 boundProject=null），非 SESSION/DURABLE 存活创建会话主路径。SESSION scope cron 经
     * {@code QueueItem.sessionId} 透传创建会话 short（改3：RunRequest 真实 short + MDC 归组），
     * 对齐 CC 单进程 ambient"任务即属创建会话"语义。DURABLE fire（boundProject!=null）创建会话
     * 存活 → 创建会话 short；已关 → null（headless 无 transcript，见 {@link #runOneAgentLoop}）。
     * CC 单进程单主会话；Java 多会话 → cron 任务归组创建会话。
     *
     * <p>[session-id-short] GLOBAL 占位键由 UUID(0,0)c001 → {@code "global"}：保持非 null 以维持
     * markRunning 计数语义（markRunning(null) 早退漏计数）；真实会话恒 "sess-" 前缀不冲突。
     */
    public static final String GLOBAL_SESSION_KEY = "global";

    private static final int SETTINGS_SINGLETON_ID = 1;

    @Autowired(required = false) private NotificationQueue notificationQueue;
    @Autowired(required = false) private ObjectProvider<LlmAgentLoop> loopProvider;
    /** [queue-first B3] STOMP 出站模板 · busy-queued 真实会话 prompt 注入 streamContext（助手回复推流到该会话）用。 */
    @Autowired(required = false) private org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate;
    /** [queue-first B3] 队列出站事件 · 消费 busy-queued 后 emitDrained（前端排队框移除 + 注册新 streamTopic）。 */
    @Autowired(required = false) private QueueEventPublisher queueEventPublisher;
    /** [queue-order-fix 方案A] 用户消息落库 · 消费 busy-queued 时 createQueuedUserMessage（此时前一轮
     *   assistant 已落库 → DB 顺序正确）。 */
    @Autowired(required = false) private com.nexusai.domain.session.MessageService messageService;
    /**
     * [cron-fire-visible] 结果落库 · cron 触发 run 结束后复用 {@link ChatService#replayAndPersist}
     *   （主链路同款回放持久化：落库 assistant/tool/final，user 已落库跳过，注入历史经
     *   prePersistedMessageIds 跳过防重）→ 转录落 DB + tool_call/tool_result STOMP 推前端。
     *   对齐 CC onFireTask（useScheduledTasks.ts:110-113）：cron 结果落 transcript，用户可见回复。
     *   null（未注入，非 Spring 单测）→ 跳过落库（headless/测试不阻断 loop）。
     */
    @Autowired(required = false) private ChatService chatService;
    /**
     * [P2 · slash 消费兜底] SlashCommandInterceptor · '/' 开头排队命令的解析 + 技能内容加载（P1 交付物，
     *   镜像 CC processSlashCommand.tsx:309-921 全流程）。dequeue 后经 {@code intercept} 共用分派，
     *   替代 runOneAgentLoop 丢原文进 LLM turn。null（未注入，非 Spring 单测）→ 回落旧行为
     *   （runOneAgentLoop 丢原文起 turn），不阻断。
     */
    @Autowired(required = false) private SlashCommandInterceptor slashInterceptor;
    /**
     * 运行时门控 · CC original: isKilled (cronScheduler.ts:231 check() 每 tick 顶部 gate /
     * useScheduledTasks.ts:119 isKilled: () => !isKairosCronEnabled())。对齐 CC isKairosCronEnabled
     * (ScheduleCronTool/prompt.ts:36-45)。null（未注入）→ fail-open。
     */
    @Autowired(required = false) private CronEnabledGates cronGates;
    @Autowired(required = false) private TokenBudgetChecker tokenBudgetChecker;
    @Autowired(required = false) private QueryConfig queryConfig;
    @Autowired(required = false) private com.nexusai.application.agent.memory.MemoryStorage memoryStorage;
    @Autowired(required = false) private com.nexusai.application.agent.memory.MemoryPrefetcher memoryPrefetcher;
    @Autowired(required = false) private com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine;
    @Autowired(required = false) private com.nexusai.application.agent.recovery.MaxTokensHandler maxTokensHandler;
    @Autowired(required = false) private com.nexusai.application.agent.recovery.TransientErrorHandler transientErrorHandler;
    @Autowired(required = false) private ModelConfigResolver modelConfigResolver;
    @Autowired(required = false) private SettingsMapper settingsMapper;
    @Autowired(required = false) private ModelMapper modelMapper;
    @Autowired(required = false) private com.nexusai.repository.provider.mapper.ProviderMapper providerMapper;
    /**
     * 创建会话存活判定查询 · [cron-durable-session-fire]：DURABLE fire 判定创建会话是否存活
     * （存活 → RunRequest 用创建会话 UUID，transcript 归创建会话文件；已关 → headless 无 transcript）。
     * 会话生命周期权威 = DB 行（SessionService.delete 删行即关闭，对齐 ChatService.processUserMessage
     * :161 sessionMapper.selectOneById 的存在性判定）。null（未注入，非 Spring 单测）→ fail-open
     * 视为存活（不阻断 fire，测试可注入 mock 模拟已关）。
     */
    @Autowired(required = false) private SessionMapper sessionMapper;
    /** 专用执行器（AsyncConfig cronExecutor · core=1 串行，避免同会话并发 agent_loop）。 */
    @Autowired(required = false) private Executor cronExecutor;
    /**
     * 调度业务门面 — 启动 missed 表面编排（CRON-F5）。null（未注入）→ 跳过启动表面并 warn。
     */
    @Autowired(required = false) private ScheduleService scheduleService;
    /**
     * [P3 事件驱动] 队列变更 → 立即消费的合并闸 · 防止 onQueueChanged 自触发风暴（poll 内
     *   dequeueAllMatching 再 fire → processing=true 吞掉）与突发并发。cronExecutor core=1 串行
     *   天然单飞；本闸只保证不重复 submit。
     */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * CRON-B3（决策 #7 + #8）统一 ApplicationReady 编排入口 · 对齐 CC cronScheduler.ts:179-227 load(initial).
     *
     * <p>CC load(initial) 在启动时对权威存储（scheduled_tasks.json）做全量重建。Java 侧等价 =
     * DB 为权威、QRTZ 为持久调度器，启动三步补偿（顺序不可乱）：
     * <ol>
     *   <li>{@link #sweepSessionTasksAtStartup}：清扫 SESSION-scope 残留（决策 #7，CC SESSION=随进程死）</li>
     *   <li>{@link #surfaceMissedAtStartup}：missed one-shot 表面 + DB↔QRTZ 全量对账（CRON-F5 + 决策 #8）</li>
     * </ol>
     * 顺序约束：sweep 先于 missed/对账（否则 SESSION 孤儿可能被对账重注册后再清扫，净效果相同但浪费）；
     * 对账必须晚于 missed 表面（见 {@link #surfaceMissedAtStartup} 顺序说明）。
     *
     * <p>单钩子合并原因：Spring 对同事件多个 {@code @EventListener} 方法的调用顺序无契约保证，
     * 为满足 sweep→missed→对账的严格顺序，收拢为单入口顺序编排（concerns 登记）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        sweepSessionTasksAtStartup();
        surfaceMissedAtStartup();
    }

    /**
     * CRON-F5 + CRON-B3-2：启动时表面 missed one-shot 任务 + QRTZ 全量对账 · 对齐 CC
     * cronScheduler.ts:179-227 load(initial)（双职责，由 {@link #onApplicationReady} 编排）。
     *
     * <p>CC load(initial) 双职责：
     * <ul>
     *   <li><b>全量重建</b>（CRON-B3-2 决策 #8）：启动以权威存储重建调度器。Java 等价 =
     *       {@link ScheduleService#reconcileQuartzAtStartup()} DB↔QRTZ 全量对账（DB 有任务 QRTZ
     *       缺 trigger → 补注册防僵尸；QRTZ 孤儿 job → warn）</li>
     *   <li><b>missed 表面</b>（CRON-F5）：检测 createdAt 在过去且未 fired 的 one-shot
     *       （cronTasks.ts:453-458 findMissedTasks）→ buildMissedTaskNotification 生成 fence 包裹的
     *       指示通知（header 要求先 AskUserQuestion 问用户再执行，不自动执行，cronScheduler.ts:542-565）
     *       → surface-then-delete（cronScheduler.ts:218-223）→ enqueueForLead 等价入队
     *       （useScheduledTasks.ts:71-82）</li>
     * </ul>
     *
     * <p><b>顺序约束</b>：对账必须在 missed 表面（surface-then-delete）之后 —— 否则把已表面删除的
     * missed one-shot 重注册进 QRTZ，Quartz once SimpleTrigger startAt=过去 + misfire=
     * NextWithRemainingCount 可能立即 fire 自动执行，违反 OPD-Cron-09-2「先问后执行」。
     *
     * <p>gate 语义：missed 表面受 {@code isKairosCronEnabled} gate（useScheduledTasks.ts:61
     * "launch-grain" 守卫，门关不骚扰用户）；对账不 gate（数据完整性非执行路径，与 B3-1 SESSION
     * sweep 同判点）。null 未注入 → fail-open（对齐 poll() 既有语义）。
     */
    public void surfaceMissedAtStartup() {
        if (scheduleService == null) {
            log.warn("CronIdleExecutor: ScheduleService 未注入，跳过 missed 启动表面与 QRTZ 对账");
            return;
        }
        if (cronGates == null || cronGates.isKairosCronEnabled()) {
            surfaceMissedOneShots();
        } else {
            log.warn("CronIdleExecutor: 定时功能已关闭（isKairosCronEnabled=false），跳过 missed 启动表面"
                + "（对齐 CC useScheduledTasks.ts:61 gate）；QRTZ 全量对账仍执行（决策 #8 数据完整性）");
        }
        // 对账：顺序必须在 missed 表面（surface-then-delete）之后
        int reRegistered = scheduleService.reconcileQuartzAtStartup();
        if (reRegistered > 0) {
            log.info("CronIdleExecutor: 启动对账补注册 {} 条 QRTZ 缺失 trigger 的任务"
                + "（对齐 CC load() 全量重建 cronScheduler.ts:179-227，决策 #8）", reRegistered);
        }
    }

    /** missed 表面 + 入队（拆出便于顺序编排阅读）· 对齐 CC useScheduledTasks.ts:61-89 + cronScheduler.ts:194-227. */
    private void surfaceMissedOneShots() {
        Optional<String> notification =
            scheduleService.surfaceMissedForStartup(System.currentTimeMillis());
        if (notification.isEmpty()) {
            return;
        }
        if (notificationQueue == null) {
            log.warn("CronIdleExecutor: NotificationQueue 未注入，missed 通知丢弃（任务已 surface-then-delete）");
            return;
        }
        NotificationQueue.QueueItem item = new NotificationQueue.QueueItem(
            notification.get(), NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.LATER, null,
            true, NotificationQueue.WORKLOAD_CRON);
        notificationQueue.enqueuePendingNotification(item);
        log.info("CronIdleExecutor: missed 启动通知入队，{} 字符，mode={}, isMeta={}, workload={}"
            + "（对齐 CC useScheduledTasks.ts:71-82 enqueueForLead）",
            notification.get().length(), item.mode(), item.isMeta(), item.workload());
    }

    /**
     * 启动清扫所有 SESSION-scope 任务 · 决策 #7 / OPD-Cron-D5（启动清扫）。
     *
     * <p>CC 对齐：SESSION = 随进程死（cronTasks.ts:59-63 durable=false 仅内存，cronTasks.ts:211-213
     * addSessionCronTask 不写盘；cronScheduler.ts:376-378 每 tick 从内存读）。Java 因 SESSION
     * 仍落库（OPD-Cron-02），重启后残留行会经 Quartz 重新注册复活 fire —— 启动时清扫是补偿。
     *
     * <p><b>不 gate cronGates</b>（判断点 C1）：CC 进程死亡无 gate 概念，SESSION 孤儿必须清；
     * 若 gate 且运行中重开定时，孤儿任务会复活 fire。null 未注入 → warn 跳过。
     *
     * <p>时序：由 {@link #onApplicationReady}（ApplicationReadyEvent 单钩子）编排，顺序
     * sweep→missed 表面→对账（CRON-B3-2 决策 #8）：sweep 先于对账，否则 SESSION 孤儿可能被
     * 对账重注册后再清扫（净效果相同但浪费）。Quartz JDBC JobStore 就绪后再 unregisterSchedule
     * （风险 R2）。
     */
    public void sweepSessionTasksAtStartup() {
        if (scheduleService == null) {
            log.warn("CronIdleExecutor: ScheduleService 未注入，跳过 SESSION 启动清扫"
                + "（对齐 CC SESSION=随进程死，OPD-Cron-D5）");
            return;
        }
        int swept = scheduleService.sweepSessionTasksAtStartup(Set.of());
        if (swept > 0) {
            log.info("CronIdleExecutor: 启动清扫删除 {} 条 SESSION-scope 任务"
                + "（对齐 CC SESSION=随进程死，OPD-Cron-D5）", swept);
        }
    }

    /**
     * [P3 事件驱动] 注册队列变更监听 · 对齐 CC useQueueProcessor.ts:35-67
     *   useSyncExternalStore(subscribeToCommandQueue, getCommandQueueSnapshot) —— CC 队列变化
     *   → isQueryActive=false 立即消费（0 延迟）；Java 以本监听替代纯 3s 轮询的延迟窗口。
     *
     * <p>与 {@link #onApplicationReady}（@EventListener ApplicationReadyEvent）互不影响：
     *   @PostConstruct 在 bean 初始化后即注册监听（notificationQueue 字段注入已完成），
     *   ApplicationReady 只编排启动 missed 表面/对账。@Scheduled 3s 保留作兜底（防通知丢失）。
     *
     * <p><b>spurious 调用登记</b>：mid-turn drain（LlmAgentLoop）消费时 remove → fire → onQueueChanged
     *   → poll 是 spurious 调用（会话运行中 mainThreadConsumable :isSessionRunning 跳过 → poll 返回
     *   false 无害）。turn 结束 LlmAgentLoop.run() finally notifyChanged 显式 re-fire 才是真实消费点。
     */
    @PostConstruct
    public void registerQueueListener() {
        if (notificationQueue != null) {
            notificationQueue.registerOnChange(this::onQueueChanged);
            if (log.isInfoEnabled()) {
                log.info("CronIdleExecutor: 注册队列变更监听（P3 事件驱动消费，对齐 CC useQueueProcessor.ts 订阅队列快照）");
            }
        }
    }

    /** [P3] 队列变更回调 · AtomicBoolean 合并自触发与突发风暴；cronExecutor 串行执行 poll（幂等）。 */
    private void onQueueChanged() {
        if (processing.compareAndSet(false, true)) {
            if (cronExecutor != null) {
                cronExecutor.execute(() -> {
                    try {
                        poll(this::executeQueuedInput);
                    } finally {
                        processing.set(false);
                    }
                });
            } else {
                // cronExecutor 未注入（非 Spring 单测）→ 释放闸（无异步通道，靠 @Scheduled 兜底）
                processing.set(false);
            }
        }
    }

    /**
     * 定时轮询入口（对齐 CC 1s tick 量级 → 3s fixedDelay，实施登记）。
     * [P3] 保留作兜底（防通知丢失），事件驱动已覆盖 0 延迟主路径。
     */
    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void pollScheduled() {
        if (notificationQueue == null) {
            log.warn("CronIdleExecutor: notificationQueue 未注入，轮询跳过");
            return;
        }
        boolean processed = poll(this::executeQueuedInput);
        if (processed) {
            log.info("CronIdleExecutor: 轮询消费队列并启动 agent_loop（对齐 CC processQueueIfReady）");
        }
    }

    /**
     * 消费语义 — 对齐 CC queueProcessor.ts:52-87 processQueueIfReady。
     *
     * @param executeInput 命令消费回调（测试可注入 fake）
     * @return 是否消费并启动
     */
    public boolean poll(Consumer<List<NotificationQueue.QueueItem>> executeInput) {
        // CRON-F2 运行时门控 · CC original: isKilled (cronScheduler.ts:231 check() 每 tick 顶部
        // `if (isKilled?.()) return`; useScheduledTasks.ts:119 isKilled: () => !isKairosCronEnabled())。
        // OPD-Cron-07-h 拍板「关闭后已注册任务立即停止」：门关 → 不消费队列、不启动 agent_loop。
        // pollScheduled() 委托 poll()，一处 gate 覆盖两条入口。null（未注入）→ fail-open 视为开。
        if (cronGates != null && !cronGates.isKairosCronEnabled()) {
            log.warn("CronIdleExecutor: 定时功能已关闭（isKairosCronEnabled=false），跳过轮询消费"
                    + "（对齐 CC isKilled 每 tick gate, cronScheduler.ts:231）");
            return false;
        }
        // 三闸 2: 队列非空（对齐 useQueueProcessor.ts:51 queueSnapshot.length === 0）
        if (!notificationQueue.hasCommandsInQueue()) {
            return false;
        }
        // [3c] 判别收紧为「全局 + 空闲会话」消费者 · CC queueProcessor.ts:61
        // isMainThread = cmd.agentId === undefined → Java agentId == null。CC 单进程单主会话：
        // 回合间 drain 只会被唯一主会话消费，无归属歧义。Java 多会话 → 本执行器是 CC 回合间 drain
        // （useQueueProcessor.ts:48-60）在 web 服务里的等价物，消费集合收敛为：
        //   agentId==null（主线程命令）
        //     && (sessionId==null   → 全局/DURABLE 无会话 → GLOBAL_SESSION_UUID 兜底消费
        //         || 目标会话空闲   → 归创建会话的命令，会话空闲时本执行器代跑（带该会话上下文）)
        //     && 绝不捞真实会话用户 prompt（mode==prompt && workload==null && sessionId!=null
        //        → 留给该会话自身 turn；3b 后用户 prompt 已带 sessionId，防止 CronIdleExecutor
        //        再起一轮 loop 造成重复处理）。
        // [3c-修饿死] 原实现「peek 首个命令会话在跑 → 整个 poll return false」把同优先级靠后的
        //   空闲会话 cron 一起饿死（A-queue-ownership-probe §2.2 场景 C）。改为把「目标会话运行中」
        //   并入谓词逐条跳过，peek 返回首个可消费项 —— 运行中会话的命令被跳过，空闲会话命令正常处理。
        // [cron-durable-session-fire] DURABLE vs SESSION 判别：boundProject != null = DURABLE
        //   （创建于会话恒有项目锚；SESSION 恒 null）。DURABLE 命令创建会话已关也照常 fire
        //   （headless，CronIdleExecutor 代跑）；SESSION 命令必须会话存活（会话已关 → 不消费）。
        //   理由选 boundProject 判别而非新增 durable 标记：boundProject 已区分两 scope 语义
        //   （DURABLE 项目锚 / SESSION 走 sessionId 恢复路径），新增字段会扩大 QueueItem 构造面
        //   （8 个兼容构造 + normalizePriority + TestJob 入队），收益仅覆盖「会话无绑定项目」的
        //   罕见边角（DURABLE 在未绑定项目会话中创建 → boundProject=null 被误判 SESSION，
        //   SessionKeys.originalKey 仍可反解，运行路径不受影响，登记 concern）。
        Predicate<NotificationQueue.QueueItem> mainThreadConsumable = cmd -> {
            if (cmd.agentId() != null) return false;                  // 子 agent 命令留给对应 agent
            String target = resolveSessionUuid(cmd.sessionId());      // sessionId==null → GLOBAL_SESSION_KEY
            if (LlmAgentLoop.isSessionRunning(target)) return false;  // 目标会话运行中 → 逐条跳过（修饿死）
            if (cmd.sessionId() == null) return true;                 // 全局空闲 → 本执行器消费
            if (cmd.boundProject() != null && !cmd.boundProject().isBlank()) {
                return true;                                          // DURABLE（项目锚）：空闲/已关照常消费（3c）
            }
            // [R4] 已删会话的后台任务通知照常消费（路由到全局/headless，不滞留孤儿）：
            //   后台任务完成通知（task-notification）带创建会话 sessionId（CronNotifyProducerSessionRoutingTest
            //   锁死），创建会话已删（SessionService.delete 删行）→ 若按 SESSION 语义拒消费，通知永久滞留
            //   队列（孤儿）。放宽：通知类命令会话已删 → 仍消费，runOneAgentLoop 走 headless null 会话兜底
            //   （无 transcript，通知作为全局通知被模型消费）。放在 isSessionAlive 拒绝分支之前。
            if (NotificationQueue.MODE_TASK_NOTIFICATION.equals(cmd.mode())
                    && !isSessionAlive(cmd.sessionId())) {
                return true;
            }
            // SESSION：必须会话存活（会话已关 → 不消费，SESSION 随会话生命周期消亡）
            if (!isSessionAlive(cmd.sessionId())) return false;
            // [esc-cron-loop-fix] 真实会话用户 prompt（workload==null）一律留自身 turn —— 含 slash 命令。
            //   原谓词带 `!isSlashCommand` 使 workload==null 的 slash 命令（如 /import-cc 直接发送的
            //   prompt 型 skill）绕过检查被本执行器消费 → runOneAgentLoop 起新 run → LlmAgentLoop.run
            //   每次又入队用户 prompt（:2820，对齐 CC enqueue）→ turn-0 提前 drain 后残留 → 本执行器
            //   3s poll 再消费 → 无限循环（联调实测每 3-8 秒一轮，DB 刷 ~20 条无占位 assistant）。
            //   对齐 CC：空闲用户 prompt 不入队（handlePromptSubmit 直接处理），queueProcessor 消费的
            //   都是排队命令（busy-queued / cron / 通知，workload 非 null）——workload==null 必为
            //   用户直接发送，由本会话 turn 自身消费，本执行器绝不打捞。
            if (NotificationQueue.MODE_PROMPT.equals(cmd.mode())
                    && cmd.workload() == null) {
                return false;                                         // 真实会话用户 prompt（含 slash）→ 留自身 turn
            }
            // [mid-turn-align] busy-queued（workload="busy-queued"）不命中上方 workload==null 分支 →
            //   落 return true（会话空闲时由本执行器代跑；运行中已被 :272 isSessionRunning 跳过）。
            //   busy-queued 现由运行中 turn 的工具边界 mid-turn 消费（同轮回答，LlmAgentLoop drainForQuery
            //   不再过滤 workload）；本路径仅兜底「当前轮结束仍残留 busy-queued」——纯文本轮末无更多
            //   工具边界注入、最后一次 drain 之后入队、后台 loop 未捞的消息（CC useQueueProcessor.ts:48-67
            //   turn 结束兜底消费语义）。与 mid-turn 注入互斥：运行中 isSessionRunning 跳过，turn 结束
            //   后 mid-turn drain 不再发生 → 无双发。
            return true;                                              // 空闲会话 cron/通知/busy-queued → 代跑
        };
        Optional<NotificationQueue.QueueItem> nextOpt = notificationQueue.peek(mainThreadConsumable);
        if (nextOpt.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("CronIdleExecutor: 无可消费主线程命令（会话运行中/真实会话用户 prompt 均跳过），跳过（CC queueProcessor.ts:64-66）");
            }
            return false;
        }
        NotificationQueue.QueueItem next = nextOpt.get();
        // [3d] 批量归组键：[session-id-short] QueueItem.sessionId 已 short 裸 equals 直键
        // （原 canonicalUuid 归一化铁律失去前提 —— CRON-D5 F2 双形态根因消除）。
        // sessionId==null 全局命令归组到 GLOBAL_SESSION_KEY 占位键，同组不混会话。
        String targetSessionKey = next.sessionId() != null ? next.sessionId() : GLOBAL_SESSION_KEY;
        // CC queueProcessor.ts:70-74 — slash/bash 单条 dequeue
        if (isSlashCommand(next) || "bash".equals(next.mode())) {
            Optional<NotificationQueue.QueueItem> cmd = notificationQueue.dequeue(mainThreadConsumable);
            if (cmd.isEmpty()) return false;
            executeInput.accept(List.of(cmd.get()));
            return true;
        }
        // CC queueProcessor.ts:76-87 — 同 mode 批量 dequeueAllMatching（非 slash + 主线程 + 同 mode）
        // [3d] 追加 sessionId 归组谓词：不把不同会话的命令混进一个 batch —— executeQueuedInput 逐命令
        // 串行 runOneAgentLoop 恢复各自创建会话 MDC，混会话批次会串台。
        String targetMode = next.mode();
        List<NotificationQueue.QueueItem> commands = notificationQueue.dequeueAllMatching(
            c -> mainThreadConsumable.test(c)
                && !isSlashCommand(c)
                && targetMode.equals(c.mode())
                && (c.sessionId() != null ? c.sessionId() : GLOBAL_SESSION_KEY).equals(targetSessionKey));
        if (commands.isEmpty()) return false;
        executeInput.accept(commands);
        return true;
    }

    /**
     * 对齐 CC messageQueueManager.ts:538-547 isSlashCommand — value trim 后以 '/' 开头且
     * skipSlashCommands=false。skipSlashCommands=true（bridge/CCR 消息，textInputTypes.ts:320）
     * 时 '/'-开头按纯文本送模型，不走命令链 —— 本执行器判别必须与 NotificationQueue.isSlashCommand
     * 同语义，防双实现分叉（ChannelNotification 入队 isMeta+skipSlashCommands 被误判为 slash）。
     */
    static boolean isSlashCommand(NotificationQueue.QueueItem cmd) {
        if (cmd == null || cmd.value() == null) return false;
        return cmd.value().trim().startsWith("/") && !cmd.skipSlashCommands();
    }

    /**
     * 真实消费（对齐 CC executeQueuedInput）：batch 命令串行各启动一轮 agent_loop，
     * 每命令独立 run（对齐 CC handlePromptSubmit 逐命令独立 user message）。
     * 串行保证同会话不并发（run() 内 RUNNING_SESSIONS 计数）。
     */
    private void executeQueuedInput(List<NotificationQueue.QueueItem> commands) {
        if (cronExecutor == null || loopProvider == null) {
            log.error("CronIdleExecutor: cronExecutor/loopProvider 未注入，命令无法执行（丢弃 {} 条）", commands.size());
            return;
        }
        cronExecutor.execute(() -> {
            for (NotificationQueue.QueueItem cmd : commands) {
                try {
                    // [queue-first B3 改] 先推 queue.drained 再 runOneAgentLoop —— [streamTopic-session-level]
                    //   前端已订阅会话级 /topic/sessions/{sid}/stream（单一订阅），drained[].streamTopic
                    //   恒为会话 topic（emitDrained 恒派生，无新订阅地址）；runOneAgentLoop 阻塞到整轮
                    //   结束前先推 drained 供前端渲染 queued-user 气泡，兜底轮流式同走会话 topic。
                    //   空 content 也 emit（清理排队框幽灵行——enqueueBusyPrompt content 可为空串）。
                    if ("busy-queued".equals(cmd.workload())
                            && cmd.sessionId() != null && !cmd.sessionId().isBlank()
                            && queueEventPublisher != null) {
                        queueEventPublisher.emitDrained(cmd.sessionId(), List.of(cmd));
                    }
                    // [queue-order-fix 方案A + cron-fire-visible 目标1] 消费前落库 user 消息
                    //   （指定 id = 队列 uuid；cron uuid=null → createQueuedUserMessage 生成兜底 id）：
                    //   对齐 CC 消费时 createUserMessage——落库顺序 = 消费顺序（当前轮已结束、前一轮
                    //   assistant 已落库），修复 user 消息插入到未落库 assistant 前的 DB 顺序错位。
                    //   busy 时 controller 未落库（预生成 pendingId 入队），此处补落库供前端消息流读取。
                    //   条件从 workload=busy-queued 放宽到 mode=prompt：busy-queued（排队 prompt）与
                    //   cron（workload=cron，isMeta 语义，CC createUserMessage isMeta）都落库 user 消息
                    //   （2026-08-25 联调实测 cron 触发结果前端收不到，补 cron 落库）。通知类命令
                    //   （mode≠prompt）/ 无会话（headless，sessionId null/空白）不受影响。
                    // [P5-①] 落库后真实 user 消息 id（供拒绝消息 flow 归属；cron uuid=null 时兜底）。
                    String consumedUserId = cmd.uuid();
                    if (NotificationQueue.MODE_PROMPT.equals(cmd.mode())
                            && cmd.sessionId() != null && !cmd.sessionId().isBlank()
                            && messageService != null) {
                        boolean isCron = NotificationQueue.WORKLOAD_CRON.equals(cmd.workload());
                        String persistedUserId = cmd.uuid();
                        try {
                            // [C1] 5 参重载落库 isMeta · CC original: isMeta（useScheduledTasks.ts:76 cron
                            //   入队 isMeta 语义 —— UI 隐藏但模型可见）：cron（workload=WORKLOAD_CRON）落
                            //   isMeta=true；busy-queued（mode=prompt 但 workload!=cron）恒 false
                            MessageCreatedResponse created = messageService.createQueuedUserMessage(
                                cmd.sessionId(), cmd.uuid(), cmd.value(), OffsetDateTime.now(), isCron);
                            // 落库后真实 user 消息 id（cron uuid=null 时 createQueuedUserMessage 内部 generateId 兜底）
                            if (created != null && created.userMessageId() != null) {
                                persistedUserId = created.userMessageId();
                                consumedUserId = persistedUserId;
                            }
                            if (log.isInfoEnabled()) {
                                log.info("CronIdleExecutor: prompt 落库 user 消息 session={} id={} workload={}",
                                    cmd.sessionId(), persistedUserId, cmd.workload());
                            }
                        } catch (Exception e) {
                            log.warn("CronIdleExecutor: prompt user 消息落库失败 session={} id={}: {}"
                                    + "（仍推 message.user 占位保前端锚点）",
                                cmd.sessionId(), cmd.uuid(), e.getMessage());
                        }
                        // [cron-complete 修复] 推 message.user（isMeta=true 前端占位不显示，保持 flow 顺序）：
                        //   cron user prompt 只落库不推前端 → 前端 messages 缺锚点 → 该轮 assistant 流式块
                        //   （无 complete 收口）残留 streams → 被后续用户 turn complete 混收口后按 flowKey
                        //   找不到锚点插入末尾 → 顺序倒挂。streamTopic=会话级单 topic，与 LlmAgentLoop
                        //   setStreamContext 同源，id=落库后真实 id（uuid=null 兜底一致）。
                        //   [esc-cron-loop-fix] 落库失败（uuid 复用主键冲突等）不再阻断推送 —— 前端仍需
                        //   user 占位锚点归属该轮 assistant 块，否则穿插对话（对齐 CC cron user isMeta 占位）。
                        if (chatService != null) {
                            chatService.publishUserMessageEvent(cmd.sessionId(), persistedUserId, cmd.value(), isCron,
                                "/topic/sessions/" + cmd.sessionId() + "/stream", wsTemplate);
                        }
                    }
                    if (cmd.value() == null || cmd.value().isBlank()) continue;
                    // [P5-①] 排队消费复判 userInvocable=false · 对齐 CC processSlashCommand.tsx:526-548：
                    //   busy 排队命令 dequeue 后重走 handlePromptSubmit，userInvocable=false 同样拒绝
                    //   （推拒绝文案 + idle，不起 agent loop）。会话级 topic 推送由 ChatService 负责。
                    if (chatService != null
                            && chatService.rejectNonUserInvocable(
                                cmd.sessionId(), cmd.value(), consumedUserId, wsTemplate)) {
                        continue;
                    }
                    // [P2 · slash 消费兜底] '/' 开头排队命令（workload=cron / busy-queued，非真实会话
                    //   用户 prompt —— 后者已被 mainThreadConsumable workload==null 分支留自身 turn，
                    //   不达本执行器）→ 走命令执行链（对齐 CC queueProcessor.ts:70-74 单条 dequeue →
                    //   executeInput → processSlashCommand）：dequeue 后调用 P1 实现的共用分派
                    //   （SlashCommandInterceptor），替代 runOneAgentLoop 丢原文进 LLM turn。
                    //   分派语义（对齐 CC processSlashCommand.tsx:309-921）：
                    //   - prompt 型（shouldQuery=true）→ 技能内容先落 isMeta DB 消息（persistSlashMeta，
                    //     镜像 P1 ChatService slashMetaId 模式，对齐 CC :915-918 createUserMessage
                    //     isMeta:true），userPrompt 用 cmd.value() 原文 —— 注意：<b>这不是 CC 的
                    //     『用户原文』</b>，CC 可见 user 消息实为 formatCommandLoadingMetadata 生成的
                    //     XML metadata（<command-message>/cmd</command-message>\n<command-name>...
                    //     processSlashCommand.tsx:896-898 + :803-822），web 以原始 /command 气泡
                    //     等价，属 Java 自选简化（登记差异，Fix-P2 Issue 3）；技能内容仅作 isMeta
                    //     经 run() 历史重载进模型上下文（对齐 P1 双消息语义 [metadata, isMeta]，
                    //     避免双注入）；保留全部现有 turn 编排
                    //     （MDC 恢复 / DURABLE boundProject override / streamContext / replayAndPersist）。
                    //   - local / local-jsx / 未知命令 / fork 占位（shouldQuery=false）→ 非查询型终态：
                    //     local 在 intercept 内部经 UserInputDispatcher.dispatchResult 本地执行，有结果
                    //     文本则落库 + 推会话流（真实会话可见），不起 LLM turn（CC local/local-jsx
                    //     shouldQuery=false :657-722；未知命令 "Unknown skill" :333-361）。
                    //   - 解析抛异常 → 外层 catch 兜底 log.error，不阻断循环。
                    if (isSlashCommand(cmd) && slashInterceptor != null) {
                        String prevProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
                        try {
                            // [CRON-D5 改2] 恢复创建会话 MDC（local handler 可能依赖 RequestContext；
                            //   finally 还原防线程池复用串台；runOneAgentLoop 内部同款 capture/restore）
                            if (cmd.sessionId() != null && !cmd.sessionId().isBlank()) {
                                RequestContext.setSession(cmd.sessionId());
                            }
                            SlashCommandInterceptor.SlashResolution slash = slashInterceptor.intercept(
                                cmd.sessionId(), consumedUserId, cmd.value(),
                                cmd.sessionId() != null
                                    ? "/topic/sessions/" + cmd.sessionId() + "/stream" : null,
                                wsTemplate);
                            if (slash.handled()) {
                                if (slash.shouldQuery()) {
                                    // [Fix-P2 · Issue 2] prompt 型技能内容 isMeta 落库（镜像
                                    //   ChatService.java:598-617 slashMetaId 模式，对齐 CC
                                    //   processSlashCommand.tsx:915-918 createUserMessage isMeta:true）：
                                    //   resume/压缩按 id 排除当前 user、metaId 独立 id 会被载入历史 →
                                    //   技能内容随 transcript 持久可恢复（P1 直连路径已落，P2 排队路径
                                    //   此前缺失 —— 该轮被压缩/resume 技能内容永久丢失，转录里只有裸 /cmd）。
                                    //   best-effort：落库失败不阻断主链（对齐 P1 与 cron isMeta 先例）。
                                    persistSlashMeta(cmd, slash, consumedUserId);
                                    // [Fix-P2 · Issue 2] userPrompt 用 cmd.value() 原文（非技能内容）：
                                    //   LlmAgentLoop.run 会从 DB 历史重载刚落的 isMeta 技能内容
                                    //   （listForResumeExcluding 排除 streamUserMessageId=cmd.uuid() 但
                                    //   保留 metaId），若仍传技能内容作 prompt → 技能内容在模型上下文
                                    //   出现两次（双注入）。对齐 P1：技能内容仅作 isMeta 消息，
                                    //   userPrompt = 原文 /command（P1 ChatService :722 userPrompt=req.content()）。
                                    //   技能级 model 覆盖 CC processSlashCommand.tsx:917。
                                    runOneAgentLoop(cmd, slashModelOverride(slash));
                                } else {
                                    // 非查询型终态 → 不起 LLM turn（unknown / local / local-jsx /
                                    //   userInvocable=false / fork 占位）
                                    handleNonQueryingSlash(cmd, slash, consumedUserId);
                                }
                                continue;
                            }
                            // handled=false（文件路径疑似回落普通 prompt / 未知命令类型）→ 落下方
                            //   runOneAgentLoop 原文路径（对齐 CC processSlashCommand.tsx:362-380）
                        } finally {
                            RequestContext.clear();
                            AutoMemPaths.restoreCurrentProjectRoot(prevProjectRoot);
                        }
                    }
                    runOneAgentLoop(cmd);
                } catch (Exception e) {
                    log.error("CronIdleExecutor: 执行入队 prompt 失败, command前20字符={}",
                        cmd.value() != null ? cmd.value().substring(0, Math.min(20, cmd.value().length())) : "",
                        e);
                }
            }
        });
    }

    /**
     * 启动一轮 agent_loop — 镜像 ChatService.processUserMessage 依赖注入
     * （tokenBudget/config/memory/recovery），主线程 agentId=null（对齐 CC 主线程契约）。
     *
     * <p><b>[CRON-D5 改2 + 改3]</b>（cron 后台任务会话上下文对齐 CC）：cronExecutor 线程无 MDC
     * （ThreadLocal 不跨线程），cron 触发的 agent_loop 工作目录域此前全回落 user.dir（跨会话 cwd
     * 错位）。改2 = 消费前 {@link RequestContext#setSession} 恢复创建会话 MDC（RemoteAgentTaskService.tick
     * :409-563 同款 capture/restore：finally restore 而非 remove，防线程池复用串台）；改3 =
     * {@link RunRequest} 用真实创建会话 UUID（非 GLOBAL 常量）→ {@code markRunning/isSessionRunning}
     * 归组创建会话 + {@code CwdResolution.getCwd(sessionId)} 解析到创建会话 boundProject/sessionCwd
     * （对齐 CC 单进程 ambient：任务即属创建会话）。SESSION scope cron 恢复 sessionId 时 log.info
     * 中文记录（日志自动带 [sessionId=...] 前缀）；DURABLE/无 sessionId 回落 GLOBAL（现状）。
     *
     * <p><b>[批次X Q2]</b>（DURABLE 项目锚恢复 · 对齐 CC durable 文件位置锚项目）：SESSION 任务
     * 走 {@code cmd.sessionId()} 恢复（会话仍存活才可命中 boundProject）；DURABLE 任务锚从
     * {@code cmd.boundProject()}（V23 bound_project 列，TestJob fire 经 QueueItem 透传）取 ——
     * 非空时用 {@link CwdResolution#runWithCwdOverride} 注入执行线程 cwd override（对齐 CC
     * cwd.ts:12-14 runWithCwdOverride / AsyncLocalStorage 语义），使 {@code CwdResolution.getCwd}
     * 四层解析（override→sessionCwd→boundProject→user.dir）直接命中 override 层解析到创建项目
     * 而非 user.dir。无会话直建 DURABLE（boundProject=null）→ 兜底 user.dir（已知差异：CC 所有
     * durable 任务都在会话里创建）。两路径清晰分离：SESSION 走 sessionId 恢复（MDC/boundProject 层），
     * DURABLE 走 boundProject 列注入（override 层）。
     *
     * <p><b>[cron-durable-session-fire]</b>（DURABLE fire 归创建会话 · 去 per-task 虚拟键）：
     * DURABLE 命令现在携带创建会话 sessionId（CronCreateTool DURABLE 分支存创建会话），
     * {@code resolveSessionUuid} 解析为创建会话 UUID（不再 GLOBAL）。RunRequest 用哪个 UUID 由
     * 创建会话<b>存活判定</b>决定（{@link #isSessionAlive}，DB 行存在性）：
     * <ul>
     *   <li><b>创建会话存活</b> → RunRequest 用创建会话 UUID → AgentState.sessionId=创建会话 UUID，
     *       经 SessionStorage 三 seam 纯 sessionId 解析自然命中
     *       {@code {boundProject}/{创建会话UUID}.jsonl}（[PROBE-DUR 修订 2026-08-22] Java 近似
     *       CRON-D5 单用户：fire 归创建会话；CC 实际注入挂载 scheduler 的活跃会话）；</li>
     *   <li><b>创建会话已关 / 无会话</b> → headless 无 transcript：RunRequest.sessionId 传
     *       <b>null</b>（GLOBAL 兜底语义的 headless 变体）→ AgentState.sessionId=null →
     *       {@code SessionStorage.getTranscriptPath(workspaceDir, null)} 返回 null → 消费方跳过写
     *       transcript（不产生任何会话 transcript 文件，杜绝 GLOBAL 共享污染）。</li>
     * </ul>
     * 不再注入 per-task 虚拟会话键 override（deriveVirtualSessionKey 已删，LlmAgentLoop
     * cronTranscriptSessionKeyOverride 已删，SessionStorage ThreadLocal override 已删）。
     */
    private void runOneAgentLoop(NotificationQueue.QueueItem cmd) {
        runOneAgentLoop(cmd, null);
    }

    /**
     * [P2 · slash 消费兜底] 技能级 model 覆盖重载。
     *
     * @param modelOverride  技能级 model 覆盖（CC processSlashCommand.tsx:917 model: command.model）；
     *                       null → 主模型
     */
    private void runOneAgentLoop(NotificationQueue.QueueItem cmd, String modelOverride) {
        LlmAgentLoop loop = loopProvider.getObject();
        if (tokenBudgetChecker != null) loop.setTokenBudgetChecker(tokenBudgetChecker);
        if (queryConfig != null) loop.setQueryConfig(queryConfig);
        if (memoryStorage != null) loop.setMemoryStorage(memoryStorage);
        if (memoryPrefetcher != null) loop.setMemoryPrefetcher(memoryPrefetcher);
        if (claudemdEngine != null) loop.setClaudemdEngine(claudemdEngine);
        if (maxTokensHandler != null) loop.setMaxTokensHandler(maxTokensHandler);
        if (transientErrorHandler != null) loop.setTransientErrorHandler(transientErrorHandler);
        // 无 WS streamContext → headless run（streamTopic=null，run() 容忍）
        com.nexusai.infra.llm.ProviderConfig config = resolveMainConfig();
        String modelName = resolveMainModelName();
        // [P2 · slash 消费兜底] 技能级 model 覆盖（CC processSlashCommand.tsx:917 model: command.model）
        if (modelOverride != null && !modelOverride.isBlank()) {
            log.info("CronIdleExecutor: slash 技能级 model 覆盖: {} → {}（对齐 CC processSlashCommand.tsx:917）",
                modelName, modelOverride);
            // [Fix-P2 · Issue 1] 覆盖模型后必须重解析 ProviderConfig —— 原实现 config 恒为主模型
            //   config（先 resolveMainConfig 再覆盖 modelName），覆盖模型若落在不同 provider，会拿
            //   覆盖模型名打主模型的 baseUrl/apiKey（错配，中危缺陷）。镜像 ChatService 语义
            //   （ChatService.java:687 buildConfigForModel(modelName) 在 modelOverride 之后重解析）：
            //   经 ModelConfigResolver.resolve（warn+skip，不可用 → null 不构造 mock）。命中 → config
            //   切到覆盖模型；不可用 → 整体回落主模型（modelName+config 一起回退，保持名配一致）。
            ModelConfigResolver.ResolvedModel overrideResolved =
                modelConfigResolver != null ? modelConfigResolver.resolve(modelOverride) : null;
            if (overrideResolved != null) {
                modelName = modelOverride;
                config = overrideResolved.config();
            } else {
                log.warn("CronIdleExecutor: slash 技能级 model 覆盖无法解析 model={} → 回落主模型 "
                        + "modelName={}（null 守卫 warn+skip 不落 mock，对齐 ModelConfigResolver 语义）",
                    modelOverride, modelName);
            }
        }
        String sessionId = cmd.sessionId();
        String boundProject = cmd.boundProject();   // 批次X Q2: DURABLE 任务项目锚（V23 列）
        // [session-id-short] QueueItem.sessionId 已统一 short（"sess-xxx"），MDC 直写 short
        // （原 CRON-D5 F2 originalKey 反解派生 UUID 串的键形态双形态已消除）。
        String mdcSessionKey = sessionId;
        // CRON-D5 改2: 恢复创建会话 MDC + 防线程池串台（capture → set → finally restore/clear）。
        String prevProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
        try {
            if (mdcSessionKey != null && !mdcSessionKey.isBlank()) {
                RequestContext.setSession(mdcSessionKey);
                log.info("CronIdleExecutor: 恢复 cron 命令会话上下文 sessionId={} mdcKey={} "
                        + "（CRON-D5：对齐 CC 单进程 ambient，cwd/记忆归组创建会话）", sessionId, mdcSessionKey);
            } else if (log.isDebugEnabled()) {
                log.debug("CronIdleExecutor: cmd 无 sessionId，回落全局会话/user.dir（DURABLE 或兼容路径，CRON-D5）");
            }
            // [cron-durable-session-fire] RunRequest 会话 ID 判定：
            //   SESSION / 无项目锚（DURABLE 无会话直建 boundProject=null）→ 既有 resolveSessionUuid
            //   （真实会话 short / null→GLOBAL_SESSION_KEY 兜底）；
            //   DURABLE（boundProject!=null）→ 创建会话存活判定：存活 → 创建会话 short（transcript
            //   归创建会话文件）；已关 / 无会话 → null（headless 无 transcript —— null 使
            //   SessionStorage 三 seam 返回 null，消费方跳过写 transcript）。
            // [session-id-short] resolveSessionUuid 返回 short 直键（不再 parseSessionUuid 归一化）。
            String sessionUuid;
            if (boundProject != null && !boundProject.isBlank()) {
                boolean creatingSessionAlive = cmd.sessionId() != null && !cmd.sessionId().isBlank()
                    && isSessionAlive(cmd.sessionId());
                if (creatingSessionAlive) {
                    sessionUuid = resolveSessionUuid(cmd.sessionId());
                    log.info("CronIdleExecutor: DURABLE fire 归创建会话（存活判定通过）: sessionId={} "
                            + "sessionUuid={}（transcript 归创建会话文件，[PROBE-DUR] Java 近似 CRON-D5："
                            + "CC 实际注入挂载 scheduler 的活跃会话）",
                        cmd.sessionId(), sessionUuid);
                } else {
                    sessionUuid = null;
                    log.info("CronIdleExecutor: DURABLE fire headless 无 transcript（创建会话已关/无会话）: "
                            + "sessionId={}（RunRequest.sessionId=null → SessionStorage 路径 null → 不写 transcript）",
                        cmd.sessionId());
                }
            } else {
                // [R4] 已删会话的后台任务通知 → headless null 会话（无 transcript）：
                //   mainThreadConsumable 已放行该通知（会话已删仍消费），此处 RunRequest.sessionId=null →
                //   AgentState.sessionId=null → SessionStorage 三 seam 返回 null → 不写死会话 transcript
                //   （复用 DURABLE 已关分支 :437-442 的 headless 语义，通知作为全局通知被模型消费，不滞留队列）。
                if (NotificationQueue.MODE_TASK_NOTIFICATION.equals(cmd.mode())
                        && cmd.sessionId() != null && !cmd.sessionId().isBlank()
                        && !isSessionAlive(cmd.sessionId())) {
                    sessionUuid = null;
                    log.info("CronIdleExecutor: task-notification 创建会话已删 → headless 消费（无 transcript）: "
                            + "sessionId={}（通知作为全局通知被模型消费，不滞留队列）", cmd.sessionId());
                } else {
                    sessionUuid = resolveSessionUuid(sessionId);
                }
            }
            // [C4 · 修订 UP-05] task-notification 空闲路径发原文（无前缀）· CC 真源：空闲触发
            //   useQueueProcessor.ts:30-61 → processQueueIfReady → executeQueuedInput → processTextPrompt
            //   （processTextPrompt.ts:89-94 createUserMessage({content: input}) 发原文，无任何前缀）。
            //   wrapCommandText case 'task-notification' 前缀（messages.ts:5501-5502）仅用于 mid-turn
            //   queued_command 注入（LlmAgentLoop.drainAndInjectQueued C4 分支）。
            //   原实现强制加前缀声称『与 mid-turn drain 字节一致（两路径共享前缀）』属错误语义 ——
            //   空闲=processTextPrompt 原文 / mid-turn=wrapCommandText 前缀为 CC 真源分化，用户已拍板
            //   对齐 CC 双证 → 空闲路径去前缀回退原文（task-notification 与 prompt 统一发原文）。
            // [Fix-P2 · Issue 2] userPrompt 统一 cmd.value() 原文（slash 与非 slash 路径一致）：
            //   技能内容不再作 prompt 覆盖 —— 已由 executeQueuedInput 在 run 前落 isMeta DB 消息
            //   （persistSlashMeta），LlmAgentLoop.run 经 listForResumeExcluding 从历史重载（对齐 P1
            //   ChatService userPrompt=req.content()；CC 可见 user 消息实为 XML metadata，web 以原始
            //   /command 气泡等价，登记差异）。
            String promptValue = cmd.value();
            RunRequest req = RunRequest.session(
                promptValue, sessionUuid, null, config, modelName, null,
                null, null, null);
            log.info("CronIdleExecutor: 启动 agent_loop, mode={}, prompt长度={}, model={}, sessionId={}",
                cmd.mode(), promptValue.length(), modelName, sessionUuid);
            // [queue-first B3] 真实会话命令 → 注入 streamContext（镜像 ChatService.processUserMessage
            //   setStreamContext：wsTemplate + sessionId + userMessageId），否则助手回复不推 STOMP 前端收不到。
            //   「真实会话」判定 = sessionUuid（与下方 replayAndPersist :640 同源）：非 null 非 GLOBAL 才推流
            //   —— 覆盖 busy-queued / cron / task-notification（子代理/后台任务完成后主 agent 处理回复用户可见，
            //   2026-08-27 联调修复）；headless（task-notification 会话已删 / DURABLE 已关 → sessionUuid=null）
            //   与全局（GLOBAL_SESSION_KEY）不推流（无前端会话可收）。
            if (sessionUuid != null && !GLOBAL_SESSION_KEY.equals(sessionUuid) && wsTemplate != null) {
                loop.setStreamContext(wsTemplate, cmd.sessionId(), cmd.uuid());
                if (log.isInfoEnabled()) {
                    log.info("CronIdleExecutor: 真实会话注入 streamContext session={} userMsgId={} mode={} workload={}",
                        cmd.sessionId(), cmd.uuid(), cmd.mode(), cmd.workload());
                }
            }
            // 批次X Q2: DURABLE 任务有 boundProject 锚 → runWithCwdOverride 注入执行线程 cwd override
            // （对齐 CC cwd.ts:12-14），CwdResolution.getCwd 四层解析命中 override 层解析到创建项目；
            // 无锚（null/空白）→ 直接 run 兜底 user.dir（现状不变）。
            // 批次乙（cron-mem）: 同分支把 boundProject 作为该回合【项目身份】整体注入 loop
            // （per-run override → resolveSessionProjectRoot 首行命中 → workspaceDir + AutoMemPaths
            // ThreadLocal 同时锚 boundProject，对齐 CC fire 回合 projectRoot=创建项目 memory 归属
            // useScheduledTasks.ts:71-82 + paths.ts:223-235）——补齐批次X 仅对齐 cwd 的 memory 缺口。
            // [cron-complete 修复] 本轮耗时锚点（publishCompleteEvent duration_ms 装配用 · 与
            //   ChatService.processUserMessage :330 同款 turn 墙钟近似）。
            long turnStartMs = System.currentTimeMillis();
            AgentState runState;
            if (boundProject != null && !boundProject.isBlank()) {
                log.info("CronIdleExecutor: 恢复 DURABLE cron 项目上下文 boundProject={} "
                        + "（批次X Q2：对齐 CC durable 文件位置锚项目 cronTasks.ts:74-83，"
                        + "CwdResolution 解析到创建项目而非 user.dir；批次乙 cron-mem："
                        + "memory/workspaceDir 项目身份注入）", boundProject);
                loop.setCronProjectRootOverride(boundProject);
                runState = CwdResolution.runWithCwdOverride(boundProject, () -> loop.run(req));
            } else {
                runState = loop.run(req);
            }
            // [cron-fire-visible · 目标2] cron 触发结果落库 · 对齐 CC onFireTask（useScheduledTasks.ts:110-113）
            //   cron 结果落 transcript + 用户可见回复：复用 ChatService.replayAndPersist 把本轮
            //   assistant/tool/final 落 DB（user 已在上方 createQueuedUserMessage 落库，replay 跳过；
            //   注入历史经 prePersistedMessageIds 跳过防重）+ tool_call/tool_result STOMP 推前端
            //   （realtimeToolCallsPushed 去重，streamContext 已注入 cron → 助手内容已实时流式推送）。
            //   门控三条件：
            //   ① sessionUuid 真实会话（非 null 非 GLOBAL）——headless（DURABLE 已关 / task-notification
            //      已删会话 / missed 通知）无 transcript 不落库（对齐 [cron-durable-session-fire] headless 语义）；
            //   ② runState 非 null（run() 抛异常 → 无终态可落，外层 executeQueuedInput catch 已 log.error）；
            //   ③ chatService 注入（非 Spring 单测 null → 跳过不阻断 loop）。
            //   wsTemplate null（非 Spring 单测）→ sendAndLog null 守卫跳过 STOMP 仅落库。
            if (runState != null && chatService != null
                    && sessionUuid != null && !GLOBAL_SESSION_KEY.equals(sessionUuid)) {
                String streamTopic = "/topic/sessions/" + sessionUuid + "/stream";
                try {
                    chatService.replayAndPersist(sessionUuid, cmd.uuid(), runState, streamTopic, wsTemplate);
                    // [cron-complete 修复] 推 message.complete 收口（userMessageId=cron user 消息 id，
                    //   对齐 effectiveEventUserMessageId 语义 = state.lastUserMessageId()=cmd.uuid()）：
                    //   复用 ChatService publishCompleteEvent（正常 turn 同款装配），前端 finalize cron 块，
                    //   不再残留 streams（根治被后续用户 turn complete 混收口倒挂）。realAssistantId=null →
                    //   方法内部回落末条 assistant 真实 id。
                    chatService.publishCompleteEvent(sessionUuid, cmd.uuid(), runState, streamTopic, wsTemplate,
                        turnStartMs, null);
                    if (log.isInfoEnabled()) {
                        log.info("CronIdleExecutor: cron 触发结果落库+complete 收口完成 session={} mode={}"
                                + "（对齐 CC onFireTask 结果落 transcript + 正常 turn complete 收口）",
                            sessionUuid, cmd.mode());
                    }
                } catch (Exception e) {
                    log.error("CronIdleExecutor: cron 触发结果落库失败 session={}: {}",
                        sessionUuid, e.toString(), e);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("CronIdleExecutor: cron 结果跳过落库 sessionUuid={} runStateNull={} chatServiceNull={}"
                        + "（headless 无 transcript / run 异常 / 非 Spring 单测）",
                    sessionUuid, runState == null, chatService == null);
            }
        } finally {
            RequestContext.clear();
            AutoMemPaths.restoreCurrentProjectRoot(prevProjectRoot);
            // 批次乙 cron-mem: 清空 per-run 项目身份 override（防线程池复用串台，同 prevProjectRoot
            // capture/restore 模式；prototype 实例本随 fire 丢弃，显式清空双保险）
            loop.clearCronProjectRootOverride();
        }
    }

    /**
     * [P2 · slash 消费兜底] prompt 型命令技能级 model 覆盖 · 对齐 CC processSlashCommand.tsx:917
     * （model: command.model）。无技能 model → null（沿用主模型，非 slash 路径不受影响）。
     */
    private static String slashModelOverride(SlashCommandInterceptor.SlashResolution slash) {
        if (slash.command() != null && slash.command().getModel() != null
                && !slash.command().getModel().isBlank()) {
            return slash.command().getModel();
        }
        return null;
    }

    /**
     * [P2 · slash 消费兜底] 非查询型 slash 终态收口（shouldQuery=false · 对齐 CC local/local-jsx/
     *   unknown/userInvocable-false/fork 占位不起模型 turn，processSlashCommand.tsx:333-721）。
     *
     * <ul>
     *   <li><b>local</b>：intercept 内部已经 {@code UserInputDispatcher.dispatchResult} 本地执行
     *       （handler 副作用已生效），有结果文本 → 落库 + 推会话流（真实会话可见，镜像
     *       ChatService 非查询型路径 :618-645）；无结果文本（skip，CC :679-682 messages:[]）→ 无消息。</li>
     *   <li><b>未知命令</b>：intercept 返回 "Unknown skill: X"（command==null）→ warn 披露，
     *       结果文本同落库 + 推送（错误提示用户可见，对齐 CC :333-361）。</li>
     * </ul>
     *
     * @param cmd          被消费命令
     * @param slash        intercept 分派结果（handled=true 且 shouldQuery=false）
     * @param userMessageId 该轮 flow userMessageId（消费落库的 user 气泡 id）
     */
    private void handleNonQueryingSlash(NotificationQueue.QueueItem cmd,
                                        SlashCommandInterceptor.SlashResolution slash, String userMessageId) {
        String cmdName = slash.command() != null
            ? slash.command().getName() : extractCommandName(cmd.value());
        if (slash.command() == null) {
            log.warn("CronIdleExecutor: 未知 slash 命令不启动 agent_loop: cmd={}（对齐 CC "
                    + "processSlashCommand.tsx:333-361 'Unknown skill' shouldQuery=false）", cmdName);
        } else {
            log.info("CronIdleExecutor: slash 非查询型命令本地执行完成，不起 agent_loop: cmd={}"
                    + "（对齐 CC local/local-jsx shouldQuery=false :657-722）", cmdName);
        }
        if (slash.resultText() == null) {
            return;   // local skip → 无消息（CC :679-682 messages:[]）
        }
        String resultId = slash.resultMessageId() != null ? slash.resultMessageId()
            : "msg-slash-" + UUID.randomUUID().toString().substring(0, 8);
        boolean realSession = cmd.sessionId() != null && !cmd.sessionId().isBlank();
        if (realSession && messageService != null) {
            try {
                messageService.createQueuedUserMessage(cmd.sessionId(), resultId, slash.resultText(),
                    OffsetDateTime.now(), false);
            } catch (Exception e) {
                log.warn("CronIdleExecutor: slash 非查询型结果落库失败（best-effort 仅推送）: session={} id={}: {}",
                    cmd.sessionId(), resultId, e.getMessage());
            }
        }
        if (realSession && chatService != null) {
            chatService.publishUserMessageEvent(cmd.sessionId(), resultId, slash.resultText(), false,
                "/topic/sessions/" + cmd.sessionId() + "/stream", wsTemplate);
        }
        log.info("CronIdleExecutor: slash 非查询型结果收口: cmd={} resultText={}chars session={}",
            cmdName, slash.resultText().length(), cmd.sessionId());
    }

    /**
     * [Fix-P2 · Issue 2] prompt 型 slash 技能内容 isMeta 落库 · 镜像 ChatService.java:598-617
     * （slashMetaId 模式，对齐 CC processSlashCommand.tsx:915-918 createUserMessage isMeta:true）。
     *
     * <p><b>WHY（规则九 · 测试验证意图）</b>：技能内容（skillContent）是模型可见但 UI 隐藏的
     * isMeta user 消息。P1 直连路径（ChatService.processUserMessage）已在 run loop 前落 isMeta，
     * resume/压缩按 id 排除当前 user、metaId 独立 id 会被载入历史 → 技能内容随 transcript 持久可恢复。
     * P2 排队路径此前只落 cmd.value() 原文（:463-499，isCron），技能内容仅放 RunRequest.userPrompt
     * 不入 DB → 该轮若被压缩/resume，技能内容永久丢失，转录里只有裸 /cmd。本方法补上这一层，
     * 与 P1 语义对齐。落库顺序（DB created_at ASC）：[raw /command (cmd.uuid()), isMeta 技能内容
     * (metaId)]，与 P1 一致（P1 controller 先落 raw、ChatService 再落 isMeta）。
     *
     * <p><b>无双注入</b>：metaId 独立于 cmd.uuid()（streamUserMessageId），LlmAgentLoop.run 的
     * listForResumeExcluding 排除 cmd.uuid() 但保留 metaId → 历史载入 isMeta 技能内容；userPrompt
     * 已切为 cmd.value() 原文（调用方保证），技能内容只在上下文出现一次。
     *
     * <p><b>best-effort</b>：落库失败不阻断主链（对齐 P1 :613-616 与 cron isMeta 先例）。
     *
     * @param cmd          被消费命令（调用方已保证 sessionId 非空非空白 —— 本方法不重复判空）
     * @param slash        intercept 分派结果（shouldQuery=true 且 metaMessageContent 非空）
     * @param userMessageId 该轮 flow userMessageId（消费落库的 user 气泡 id，日志归组用）
     */
    private void persistSlashMeta(NotificationQueue.QueueItem cmd,
                                  SlashCommandInterceptor.SlashResolution slash, String userMessageId) {
        if (slash.metaMessageContent() == null || slash.metaMessageContent().isEmpty()) {
            return;
        }
        String metaId = "msg-slash-meta-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            if (messageService != null) {
                messageService.createQueuedUserMessage(cmd.sessionId(), metaId,
                    slash.metaMessageContent(), OffsetDateTime.now(), true);
                if (log.isInfoEnabled()) {
                    log.info("CronIdleExecutor: slash prompt 型技能内容 isMeta 落库: session={} id={} chars={} "
                            + "userMessageId={}（对齐 CC :915-918，镜像 ChatService:598-617）",
                        cmd.sessionId(), metaId, slash.metaMessageContent().length(), userMessageId);
                }
            }
        } catch (Exception e) {
            log.warn("CronIdleExecutor: slash isMeta 技能内容落库失败（best-effort 不阻断主链）: "
                + "session={} id={}: {}", cmd.sessionId(), metaId, e.getMessage());
        }
    }

    /** 从 '/' 开头原始输入提取命令名（首个空白分隔，无前导 '/'）· unknown warn 披露用。 */
    private static String extractCommandName(String value) {
        if (value == null) {
            return "?";
        }
        String rest = value.trim();
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        int space = rest.indexOf(' ');
        return space == -1 ? rest : rest.substring(0, space);
    }

    /**
     * [cron-durable-session-fire] 创建会话存活判定 · 会话生命周期权威 = DB 行存在性。
     *
     * <p><b>WHY（意图）</b>: DURABLE fire 的 transcript 归创建会话（[PROBE-DUR 修订 2026-08-22]
     * Java 近似 CRON-D5 单用户：fire 归创建会话；CC 实际注入挂载 scheduler 的活跃会话），
     * 但创建会话已关（SessionService.delete 删行）→ fire 照常执行（headless）但不产生会话 transcript。
     * 判定存活才用创建会话 short（RunRequest + transcript 归创建会话文件）；已关 → null（headless
     * 无 transcript）。与 {@code ChatService.processUserMessage}（:161 sessionMapper.selectOneById
     * 存在性判定）同一权威 —— 会话是否"存在"而非"当前运行中"（RUNNING_SESSIONS 只表活跃 agent_loop，
     * 空闲存活会话不在其中，仍应归创建会话 transcript）。
     *
     * <p><b>键形态</b>: [session-id-short] DURABLE 落库 sessionId 已统一 short {@code "sess-xxx"}
     * （CronCreateTool 直传 ctx.sessionId()），DB 主键同 short → 直接 selectOneById(sessionId)；
     * 存量旧行（派生 UUID 串）经 {@link SessionKeys#originalKey} 兼容反解兜底（@Deprecated 兼容层）。
     *
     * <p><b>null 语义</b>: sessionMapper 未注入（非 Spring 单测）→ fail-open 视为存活（不阻断 fire，
     * 测试可注入 mock 模拟已关）。sessionId null/空白 → 无会话可判 → false（headless 无 transcript）。
     *
     * @param sessionId 创建会话标识（short；存量旧行可为派生 UUID 串）
     * @return true = 会话存活（DB 行存在）；false = 已关 / 无会话 / 未注入且不可判
     */
    private boolean isSessionAlive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (sessionMapper == null) {
            if (log.isDebugEnabled()) {
                log.debug("CronIdleExecutor: sessionMapper 未注入，isSessionAlive fail-open 视为存活 "
                        + "sessionId={}（非 Spring 单测；生产恒注入）", sessionId);
            }
            return true;
        }
        String originalKey = SessionKeys.originalKey(sessionId);
        if (originalKey == null) {
            originalKey = sessionId;
        }
        boolean alive = sessionMapper.selectOneById(originalKey) != null;
        if (log.isDebugEnabled()) {
            log.debug("CronIdleExecutor: isSessionAlive sessionId={} originalKey={} alive={}",
                sessionId, originalKey, alive);
        }
        return alive;
    }

    /**
     * CRON-D5 改3 · 解析命令目标会话 ID（short 直键）。
     *
     * <p>[session-id-short] {@code QueueItem.sessionId()} 非空 → 原样返回（已统一 short 形态，
     * 不再 parseSessionUuid 归一化 —— F2 双形态根因消除）；null/空白（SESSION 无会话 / DURABLE
     * 无项目锚 / 普通 prompt）→ {@link #GLOBAL_SESSION_KEY} 兜底（保持非 null 占位以维持
     * markRunning 计数语义；边界 §7.1 safeGet 兜底不崩）。
     * [cron-durable-session-fire] DURABLE 命令现在携带创建会话 sessionId → 本方法原样透传；
     * 创建会话存活判定（{@link #isSessionAlive}）在 {@link #runOneAgentLoop} 内做，
     * 已关则直接传 null（headless 无 transcript），不经本方法 GLOBAL 兜底。
     */
    private static String resolveSessionUuid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return GLOBAL_SESSION_KEY;
        }
        return sessionId;
    }

    /** 主模型名 — settings.main_model_id → enabled model name（对齐 ChatService:514-515）。 */
    private String resolveMainModelName() {
        if (settingsMapper == null || modelMapper == null) return null;
        SettingsRecord s = settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
        String mainRaw = s != null ? s.getMainModelName() : null;
        if (mainRaw == null || mainRaw.isBlank()) return null;
        // [全名化] settings 存全名/裸名（V28 RENAME main_model_id→main_model_name），走全名反查而非 selectOneById（id 直查会 miss）
        ModelRecord m = com.nexusai.infra.llm.ModelNameResolver.resolve(modelMapper, providerMapper, mainRaw);
        return (m != null && Boolean.TRUE.equals(m.getEnabled())) ? m.getName() : null;
    }

    /** 主模型 config — ModelConfigResolver.resolve（任一步不可用 → null → run() 走 mock，仅 warn）。 */
    private com.nexusai.infra.llm.ProviderConfig resolveMainConfig() {
        if (modelConfigResolver == null) return null;
        String modelName = resolveMainModelName();
        if (modelName == null) return null;
        ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
        return resolved != null ? resolved.config() : null;
    }
}
