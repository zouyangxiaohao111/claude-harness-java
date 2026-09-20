package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.chat.ChatService;
import com.nexusai.application.chat.SlashCommandInterceptor;
import com.nexusai.common.SessionKeys;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.session.entity.SessionRecord;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
     * {@code QueueItem.sessionId} 透传创建会话 short（改3：RunRequest 真实 short + 显式会话归组），
     * 对齐 CC 单进程 ambient"任务即属创建会话"语义。DURABLE fire（boundProject!=null）创建会话
     * 存活 → 创建会话 short；已关 → null（headless 无 transcript，见 {@link #runOneAgentLoop}）。
     * CC 单进程单主会话；Java 多会话 → cron 任务归组创建会话。
     *
     * <p>[session-id-short] GLOBAL 占位键由 UUID(0,0)c001 → {@code "global"} → <b>[cwd3 2026-09-15]
     * {@link SessionKeys#NO_SESSION}</b>：保持非 null 以维持 markRunning 计数语义（markRunning(null)
     * 早退漏计数）；真实会话恒 "sess-" 前缀不冲突。
     *
     * <p><b>[cwd3 · 用户裁定 2026-09-15 步骤 1b] 为什么必须换成哨兵（⛔ 不是防御性改动）</b>：
     * 本键是「结构上确无会话」的合法路径在<b>唯一会话槽位</b>上的占位 —— 它经
     * {@link #resolveSessionUuid(String)} 流进 {@code RunRequest.sessionId}，下游
     * {@code CwdResolution.getCwd} 会拿它解析 cwd。步骤 2 把 cwd 域的「DB 明确答『无此会话』」
     * 从「回落进程 user.dir」改成 <b>fail-loud 抛</b>后，若本键仍是 {@code "global"}，它就是一个
     * DB 查不到的普通串 ⇒ 会撞 fail-loud <b>直接抛崩</b>。改用 {@link SessionKeys#NO_SESSION} 后，
     * {@link SessionKeys#isNoSession(String)} 在 {@code CwdResolution} 顶部<b>短路</b>到命名无会话
     * 出口（不查 DB、不打「伪造 id」告警、不打「DB 无此会话」告警，改打
     * {@code warnNoSessionSentinel}）。
     *
     * <p><b>产出点穷举（1b 覆盖面，2026-09-15 grep 实测）</b>：① {@link #resolveSessionUuid}
     * （{@code :1177}，null/空白 ⇒ 本键）—— 覆盖 cron 命令（{@link #poll}）与
     * {@code MODE_TASK_NOTIFICATION} 的 2 参 task-notification（{@link com.nexusai.application.agent.tasks.ChannelNotification}
     * / {@code CommandHookExecutor}，见 D8）；② <b>不经本方法</b>的
     * {@link #surfaceMissedOneShots}（{@code :216-218}）—— 它用 6 参 {@code QueueItem} 硬编码
     * {@code sessionId=null}，与 {@code schedules} 行无关（启动期 missed 通知，行已删、N 条聚合无单一
     * 会话），故 1a「REST 强制会话锚」改不动它，只能靠换键覆盖（D7）。
     */
    public static final String GLOBAL_SESSION_KEY = SessionKeys.NO_SESSION;

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
     * [cron-fire-visible] 结果落库 · cron 触发 run 结束后复用 {@code ChatService#replayAndPersist}
     *   （**已删**；[实时落库 2026-09-03] 后主链路改为 appendListener 逐条实时落库，cron 同 SPI。
     *   原语义：落库 assistant/tool/final，user 已落库跳过，注入历史经
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
     *
     * <p><b>[OD-D7] 消费侧收窄后三个使用点</b>：① {@link #poll} mainThreadConsumable 谓词逐条跳过
     * cron workload 项（门关不再冻结整段 poll）；② {@link #surfaceMissedAtStartup} missed 表面 gate
     * （保留）；③ TestJob.fire producer gate（OPD-Cron-07-h「关闭后已注册任务立即停止」，保留，
     * 属另一文件）。CC 队列消费（queueProcessor.ts:52-87 / useQueueProcessor.ts:48-67）零 cron 引用。
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

    // ============================================================================================
    // [P1 · 2026-09-18 返工] 「出队之后才失败」的自愈装置 —— 回队（不丢件）+ 事件驱动抑制窗口
    //
    //   缺陷类（复核实测）：{@code cronExecutor.execute(...)} 在**出队之后**才抛
    //   RejectedExecutionException（内层提交，{@link #executeQueuedInput}）⇒ 条目既不在队列、
    //   也永不会被送达（只有一行 log.error）⇒ 静默丢件。M3 的「出队前守卫」判据**不可能**覆盖它：
    //   执行器「此刻是否仍接受任务」无法在执行之前判定（只能试）。
    //   ⇒ 修法不是再造一层守卫，而是**回队**：本批条目不消失（症状从「永久静默丢」→「延迟送达」）。
    // ============================================================================================

    /**
     * [P1] 回队后「事件驱动自触发」的抑制窗口时长（ms）。
     *
     * <p><b>WHY（防毫秒级热循环）</b>：{@link #executeQueuedInput} 提交失败时把整批**回队**，而
     * {@code NotificationQueue.enqueuePendingNotification} 会 {@code fireOnChange()} →（NOTIFY
     * 线程）{@code onQueueChanged}（0 延迟主路径）⇒ 若通道仍不可用，就会形成
     * 「回队 → 自触发 → 立即再出队 → 再失败 → 再回队」的**毫秒级**热循环（每轮 2 行 INFO +
     * 1 行 ERROR + 审计记录 + 一次虚拟线程创建），不可接受。开窗后该事件被丢弃 ⇒ 重试节奏收敛到
     * 下面这条天然限速通道。
     *
     * <p>⛔ <b>3s 兜底轮询**不**受本窗口约束</b>：它挂在 {@code @Scheduled(fixedDelay = 3000)} 上，
     * 由调度器限速（每 3s 至多一拍），**结构上不可能**热循环 —— 它正是本批的「重试节奏」。
     */
    private static final long REQUEUE_EVENT_SUPPRESS_MS = 1000L;

    /** [P1] 事件驱动抑制窗口截止时刻（ms epoch）；0 = 未抑制。 */
    private final AtomicLong eventDispatchSuppressedUntilMs = new AtomicLong(0L);

    /**
     * [P1] 连续「出队后失败」次数（成功提交后归零）。
     * <p>只用于日志如实披露「这是第几次重试」，**不参与任何判据**（判据在抑制窗口上，见上）。
     */
    private final AtomicInteger consecutiveDispatchFailures = new AtomicInteger(0);

    /**
     * [P2] 「通道探测失败」WARN 的节流间隔（ms）—— 通道不可用时条目**不出队**（无churn），但每次
     * 入口调用都会探一次 ⇒ 不加节流就会每 3s 一条 WARN（本类既有约定：自身绝不能成为噪声源）。
     */
    private static final long PROBE_WARN_THROTTLE_MS = 10_000L;

    /** [P2] 上次探测失败 WARN 的时刻（ms epoch）。 */
    private final AtomicLong lastProbeWarnAtMs = new AtomicLong(0L);

    /**
     * [M4 · 2026-09-18 子代理投递修复] 「同批提交后 N 秒未见开跑」判定阈值（秒）。
     *
     * <p>判据 = <b>该批 uuid + submittedAtMs</b>（见 {@link #executeQueuedInput}），
     * ⛔ <b>不得</b>改用 {@code processing} 闸时长 —— 闸在一次长 run 期间恒 true，会系统性误报。
     * 阈值取值：正常「提交→开跑」是毫秒级（虚拟线程），10s 已远超正常抖动。
     */
    private static final long DISPATCH_STALL_WARN_SECONDS = 10L;

    /**
     * [M4] 提交后开跑看门狗 · 单守护线程（daemon）。
     *
     * <p>⛔ <b>不得</b>复用 {@code cronExecutor}（它正是本判据要盯的那条「可能被永久占住」的通道，
     * 用它做看门狗 = 被占住时看门狗自己也发不出告警，判据失效）。
     */
    private static final java.util.concurrent.ScheduledExecutorService DISPATCH_WATCHDOG =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cron-dispatch-watchdog");
            t.setDaemon(true);
            return t;
        });

    /** [M4] 日志用短预览 · value 前 60 字符（null → 空串）。 */
    private static String preview60(String value) {
        if (value == null) return "";
        return value.length() <= 60 ? value : value.substring(0, 60);
    }

    /** [M4] 该批条目 uuid 列表（日志判据用；cron 命令 uuid 可为 null）。 */
    private static List<String> uuidsOf(List<NotificationQueue.QueueItem> commands) {
        return commands.stream()
            .map(NotificationQueue.QueueItem::uuid)
            .collect(Collectors.toList());
    }

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

    /**
     * [M4 补 · 2026-09-18 返工] 「这一批<b>跑得起来</b>吗」的<b>唯一判据</b>（M3 原则的可执行形式）。
     *
     * <p>真实执行需要<b>两条</b>通道，缺一不可：{@code cronExecutor}（把 poll 任务提交出去）与
     * {@code loopProvider}（{@link #runAgentLoop} 直取 {@code loopProvider.getObject()}，见本类
     * {@code :1039}）。而 {@link #poll} 一经调用即出队（{@code dequeue}/{@code dequeueAllMatching}
     * 皆「取出即删」）⇒ 若在「跑不起来」的通道上出队，条目<b>既不在队列、也永不被送达</b>
     * = 静默丢件（本批修的事故同类症状）。
     *
     * <p>⛔ 故<b>所有</b>出队受理点（{@link #onQueueChanged} 事件驱动 / {@link #pollScheduled} 3s
     * 兜底）与出队回调兜底（{@link #executeQueuedInput}）必须判<b>同一条件</b> —— 本轮修的正是
     * 「出队前只判 {@code cronExecutor}、回调里才判 {@code loopProvider}」的不对称：{@code loopProvider}
     * 缺失时条目先被取走、再由回调顶部丢弃，而 [M4] INFO 仍声称「已出队并提交 / poll 已开跑」
     * （假绿：日志是对的、结论是错的）。判据单点在本方法 ⇒ 两处守卫不再各持一份拷贝可以漂移。
     *
     * <p>⚠️ 生产环境两条通道恒在（{@code cronExecutor} = AsyncConfig 恒定义 @Bean；
     * {@code loopProvider} = Spring 恒注入的 {@code ObjectProvider}）⇒ 本判据的 false 分支实际只在
     * 非 Spring 单测可达；但它是「跑不起来就别出队」的<b>前提条件</b>，必须与回调兜底同源。
     *
     * <p><b>[P2 · 2026-09-18 返工] 判据第三层：通道对象存在 ≠ 通道可用</b> —— 复核实测：{@code loopProvider}
     * 字段非 null（Spring 恒注入 {@link ObjectProvider}，见 {@link #loopChannelProducesInstance()} 的
     * 前提实跑）但 {@code getObject()} 抛 {@code NoSuchBeanDefinitionException}（容器里根本没有
     * {@code LlmAgentLoop} bean 定义）时，原判据为 true ⇒ 出队 ⇒ 任务体逐条 catch 吞掉 ⇒ 条目永久消失。
     * ⇒ 出队前必须**探一次「能不能真的产出实例」**（{@link #loopChannelProducesInstance()}）。
     */
    private boolean canDispatch() {
        return cronExecutor != null && loopProvider != null && loopChannelProducesInstance();
    }

    /**
     * [P2 · 2026-09-18 返工] 「通道**可用**」探测 —— 字段非 null 只说明 Spring 注入了一个
     * {@link ObjectProvider}，**不**保证它能产出实例（无 bean 定义时 {@code getObject()} 直接抛）。
     *
     * <p><b>为什么用 {@code getObject()} 而不是 {@code getIfAvailable()}</b>：本探针要判的是
     * 「{@link #runAgentLoop}（{@code loopProvider.getObject()}）能不能跑起来」，用**逐字相同**的那个
     * 操作探测才等价（{@code getIfAvailable()} 是另一种操作：它把 NoSuchBeanDefinitionException 吞成
     * null，其余异常照抛）。且本仓 30+ 处 provider 桩只桩 {@code getObject()}，换 API 会引入一层无谓的
     * 测试改写面。
     *
     * <p><b>[F1 · 2026-09-18 返工] 噪声在源头消除（探针本身零输出）</b>：复核实测本探针是
     * <b>本批新引入的噪声源</b> —— {@code getObject()} 每次新建一个 prototype bean，其 {@code @Autowired}
     * setter 链里的静态桥 {@code setSessionMapper → SessionToolDisableConfig.setSessionMapper}
     * 原先<b>无条件</b>打一行 INFO ⇒ 队列非空时 ≈1 行/3s ≈ 2.9 万行/天（与本批修掉的 57600 行/天
     * 同量级）。修法 = 把那一行改成**只在桥接真的变化时**记录（{@code SessionToolDisableConfig:61-75}）
     * —— 探针这一侧不改判据、不加状态，探针的日志产出为 <b>0 行</b>。
     *
     * <p>⚠️ <b>两条被否的候选与代价（如实登记）</b>：
     * <ul>
     *   <li><b>改用不实例化的判据</b>（{@code getBeanNamesForType} / {@code containsBeanDefinition}）：
     *       只能答「有没有 bean 定义」，<b>答不了「定义在但创建失败」</b>（构造器依赖缺失 ⇒
     *       {@code BeanCreationException}）—— 而后者正是本第三层存在的意义 ⇒ 会<b>把 P2 修回去</b>
     *       （否决）。</li>
     *   <li><b>给探针加「已实证可产出」的缓存</b>（省掉每拍一次实例化）：与上面的源头静音<b>功能冗余</b>
     *       ⇒ 单点变异（只回退任一侧）测试恒绿、无法鉴别，且引入「缓存为真但 bean 定义已变」的
     *       陈旧假设（本批教训：冗余守卫 = 判据失效）。实测那份实例化代价 = 一次对象分配 + 一轮
     *       依赖注入（<b>无 I/O、无线程</b>，见下方原副作用核查），相对一次 agent turn 可忽略
     *       ⇒ 选择<b>保留忠实探针</b>（每次都真的问容器一次），不引入状态（否决）。</li>
     * </ul>
     *
     * <p><b>原副作用核查（要求先核，核完结论：可接受）</b>：{@code LlmAgentLoop} 是
     * {@code @Component @Scope("prototype")}（{@code LlmAgentLoop.java:198-200}）⇒ 本探针会**多实例化
     * 一个 bean**，且与真正要跑的那个不是同一个。实测该实例化面：
     * <ul>
     *   <li>构造器（1 参 {@code @Autowired} 构造器 → 委托 3 参）只做字段赋值，<b>无 I/O、无线程</b>；</li>
     *   <li>{@code LlmAgentLoop} **无** {@code @PostConstruct} / {@code InitializingBean} /
     *       {@code @EventListener} / {@code ApplicationListener}（grep 实测）⇒ 不存在「探针实例被注册成
     *       监听器」这类累积副作用；</li>
     *   <li>带副作用的 setter 只有静态桥（{@code setSessionMapper} → staticSessionMapper +
     *       SessionToolDisableConfig、{@code setAgentNameRegistry}、{@code setCoordinatorModeBean} →
     *       静态 {@code coordinatorMode}），写入值来自**同一批单例 bean** ⇒ 与真实实例写入的值逐字相同，
     *       <b>幂等</b>；其中仅 {@code setSessionMapper} 原先会打 INFO（已修，见上）。</li>
     * </ul>
     *
     * <p>⛔ 探针失败**不**抛（只返回 false）：本方法是守卫，它在 {@link #executeQueuedInput}（条目**已**
     * 出队）也被调用 ⇒ 若在此抛出，已出队的条目会连同异常一起丢（没有回队的机会）。
     */
    private boolean loopChannelProducesInstance() {
        ObjectProvider<LlmAgentLoop> provider = loopProvider;
        if (provider == null) {
            return false;
        }
        try {
            provider.getObject();
            return true;
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            long last = lastProbeWarnAtMs.get();
            if (now - last >= PROBE_WARN_THROTTLE_MS && lastProbeWarnAtMs.compareAndSet(last, now)) {
                log.warn("CronIdleExecutor: [P2] loopProvider 无法产出 LlmAgentLoop 实例"
                    + "（字段非 null ≠ 通道可用）→ 本批不出队，条目留队列待通道恢复"
                    + "（探测用与运行路径逐字相同的 getObject()）。本 WARN 每 {}ms 至多一条: {}",
                    PROBE_WARN_THROTTLE_MS, e.toString());
            } else if (log.isDebugEnabled()) {
                log.debug("CronIdleExecutor: [P2] loopProvider 探测失败（WARN 节流中，"
                    + "{}ms 内不重复告警）: {}", PROBE_WARN_THROTTLE_MS, e.toString());
            }
            return false;
        }
    }

    /**
     * {@link #canDispatch()} == false 时的**阻塞原因**（单点，与判据同源；⛔ 不得另起一份拷贝）。
     * 只在 {@code canDispatch()} 为假时调用（此时至少有一层不成立）。
     *
     * <p>措辞必须如实点出**哪一层**不成立：字段缺失（非 Spring 单测）与「字段在但产出不了实例」
     * 是两类完全不同的故障，笼统一句「未注入」会把它们混为一谈（本批修的正是这类假绿措辞）。
     */
    private String dispatchBlockReason() {
        String missing = missingDispatchDeps();
        if (!"无".equals(missing)) {
            return missing + " 未注入（非 Spring 环境）";
        }
        return "loopProvider 无法产出 LlmAgentLoop 实例（bean 缺失 / 实例化失败；字段非 null ≠ 通道可用）";
    }

    /**
     * {@link #canDispatch()} 的**字段层**反面：未注入的依赖名（供 fail-loud 日志如实点出缺哪条通道，
     * 而非笼统一句「未注入」把两条通道混为一谈）。两者都在位时返回 {@code "无"} —— 此时
     * {@link #dispatchBlockReason()} 继续判第三层（能不能产出实例）。
     */
    private String missingDispatchDeps() {
        if (cronExecutor == null && loopProvider == null) return "cronExecutor/loopProvider";
        if (cronExecutor == null) return "cronExecutor";
        if (loopProvider == null) return "loopProvider";
        return "无";
    }

    /**
     * [P1 · 2026-09-18 返工] 出队后失败 ⇒ <b>整批回队</b>（条目不消失）+ 打开事件驱动抑制窗口。
     *
     * <p><b>为什么必须回队而不是只告警</b>：{@link #poll} 的出队是「取出即删」（{@code dequeue} /
     * {@code dequeueAllMatching}）⇒ 出队之后的任何失败若只 log，条目就**既不在队列、也永不被送达**，
     * 用户侧症状正是本批要修的那条（「跑完了但主代理收不到结果」= 静默丢件）。
     *
     * <p><b>回队为何安全（不会重复执行）</b>：本方法当前只有一个调用点类别 ——
     * {@code cronExecutor.execute(...)} 抛错（任务体**从未启动**）或出队后复核判据为假 ⇒ 本批任何
     * 副作用（落库 / 推流 / {@code run()}）都还没发生 ⇒ 重新投递到「尚未消费」状态是幂等的。
     *
     * <p><b>回队后的重试节奏（防死循环设计）</b>：
     * <ol>
     *   <li>先开抑制窗口（{@link #REQUEUE_EVENT_SUPPRESS_MS}）**再**入队 —— 顺序不可换：入队会
     *       {@code fireOnChange()}，窗口未开时事件驱动会**立即**把同一批再取走 → 再失败 → 再回队
     *       = 毫秒级热循环；</li>
     *   <li>唯一的重试通道 = 3s 兜底轮询（{@code @Scheduled(fixedDelay = 3000)} 天然限速）⇒
     *       重试节奏 ≤ 1 次/3s，且**绝不忙等**（无 sleep、无自旋）；</li>
     *   <li>通道恢复（提交成功）时 {@link #consecutiveDispatchFailures} 归零。</li>
     * </ol>
     * ⛔ 没有做「指数退避后放弃」：放弃 = 丢件，与本批原则冲突；本设计选择「一直留在队列里等」
     * （内存队列，长跑失败态下条目占用极低），重试只由限速通道驱动。
     *
     * @param commands   本批条目（出队时的那一份，原样回队）
     * @param batchUuids 本批 uuid（日志判据；cron 命令 uuid 可为 null）
     * @param reason     失败原因（如实措辞，写进 ERROR）
     * @param cause      底层异常；null = 判据为假（非异常路径）
     */
    private void requeueAfterDispatchFailure(List<NotificationQueue.QueueItem> commands,
                                             List<String> batchUuids, String reason, Throwable cause) {
        int attempt = consecutiveDispatchFailures.incrementAndGet();
        // ① 先开窗，再入队（顺序不可换，见方法 JavaDoc）。
        eventDispatchSuppressedUntilMs.set(System.currentTimeMillis() + REQUEUE_EVENT_SUPPRESS_MS);
        if (notificationQueue == null) {
            // 回队不可能（队列未注入，仅非 Spring 单测可达）⇒ ⛔ 绝不静默：如实 ERROR 点出丢失的 uuids。
            log.error("CronIdleExecutor: [P1] 出队后无法投递（{}）且 NotificationQueue 未注入 ⇒ **无法回队**，"
                + "本批确实丢失 {} 条 uuids={}（非静默；与本类「不丢件」原则的已知例外，仅非 Spring 单测可达）: {}",
                reason, commands == null ? 0 : commands.size(), batchUuids,
                cause == null ? "无异常（通道判据为假）" : cause.toString());
            return;
        }
        int requeued = 0;
        if (commands != null) {
            for (NotificationQueue.QueueItem cmd : commands) {
                if (cmd == null) continue;
                // 保留 priority（QueueItem.priority 非 null 时原样）；fallback = LATER（重投属「稍后再来」）。
                notificationQueue.enqueuePendingNotification(cmd);
                requeued++;
            }
        }
        log.error("CronIdleExecutor: [P1] 出队后无法投递（{}）⇒ 已**回队** {} 条（条目不丢；连续第 {} 次失败）"
            + " uuids={} 下一步：条目留在队列，由 3s 兜底轮询重试；事件驱动入口已抑制 {}ms"
            + "（防「回队→自触发→再出队→再失败」毫秒级热循环）: {}",
            reason, requeued, attempt, batchUuids, REQUEUE_EVENT_SUPPRESS_MS,
            cause == null ? "无异常（通道判据为假）" : cause.toString());
    }


    /** [P3] 队列变更回调 · AtomicBoolean 合并自触发与突发风暴；闸内提交 poll（幂等）。 */
    private void onQueueChanged() {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        // [B2 · 2026-09-18 返工] 闸自 CAS 起**全段**都必须有释放兜底。
        //   原实现把 try 起点放在「提交侧 INFO」**之后** ⇒ 该日志行（或 submittedAtMs/Thread.getName）
        //   若抛 Throwable（OOME / appender 异常）则闸永久 true ⇒ 事件驱动与 3s 兜底两条入口
        //   **同时**永久停摆（与 M3 守卫同类，且比它更早）。
        //   形态 = submitted 标志 + finally 收口：只有「成功提交」（任务体会在自身 finally 释放闸）
        //   才不由本方法释放；其余一切出口（含 Error）都就地释放。
        //   ⛔ 原 catch 只捕 RuntimeException ⇒ 漏 Error（虚拟线程 Thread.start 失败抛 OOME 是 Error）。
        boolean submitted = false;
        try {
            // [P4 · 2026-09-18 返工] 空队列短路 —— 与 {@link #pollScheduled} 的 B1 短路**同款**。
            //   缺陷（复核实测）：B1 只把空队列短路加在 3s 兜底入口，**事件驱动分支漏了** ⇒ 空队列连发
            //   3 次 onQueueChanged = 6 行 [M4] INFO（本批新引入的噪声回归的一半）。
            //   判据复用既有 notificationQueue.hasCommandsInQueue()（pollScheduled/poll 同款），不新建语义。
            //   ⚠️ 短路 return 仍走下方 finally 释放闸（⛔ 绝不能带着闸 return）。
            if (notificationQueue == null || !notificationQueue.hasCommandsInQueue()) {
                if (log.isDebugEnabled()) {
                    log.debug("CronIdleExecutor: [P4] 队列变更 → 队列无命令，不提交也不出队"
                            + "（空队列不打 INFO —— 本类不得成为噪声源，与 3s 兜底 B1 短路同款）");
                }
                return;
            }
            // [P1 · 2026-09-18 返工] 回队自触发抑制窗口 —— 提交失败回队后，本入口（0 延迟主路径）是
            //   「回队 → 自触发 → 立即再出队 → 再失败」热循环的唯一成因 ⇒ 窗口内丢弃该事件（条目仍在
            //   队列，由限速的 3s 兜底轮询重试）。详见 REQUEUE_EVENT_SUPPRESS_MS 的 WHY。
            long nowMs = System.currentTimeMillis();
            long suppressedUntilMs = eventDispatchSuppressedUntilMs.get();
            if (nowMs < suppressedUntilMs) {
                if (log.isDebugEnabled()) {
                    log.debug("CronIdleExecutor: [P1] 队列变更 → 回队自触发抑制窗口内（剩余 {}ms），"
                        + "不提交也不出队（条目留队列，由 3s 兜底轮询重试）",
                        suppressedUntilMs - nowMs);
                }
                return;
            }
            // [M4 补 · 2026-09-18 返工] 出队前守卫 = canDispatch()（与 pollScheduled / executeQueuedInput
            //   判**同一条件**）。⛔ 原文只判 `cronExecutor != null` ⇒ loopProvider 缺失时仍会提交 poll
            //   → 出队（取出即删）→ 回调顶部早退丢弃 ⇒ 条目静默消失，且 [M4] INFO 照样报「已出队并提交」。
            //   [P2 · 2026-09-18 返工] 判据内又加了第三层：「字段非 null ≠ 能产出实例」（见 canDispatch）。
            if (canDispatch()) {
                // [M4] 提交侧 INFO（本行只声称「已提交」，**不**声称开跑）——与任务体首行
                //   「poll 已开跑」成对，交付/延迟可判。⛔ 原「轮询消费队列并启动 agent_loop」式措辞
                //   是本事故的盲区 L5（假绿日志）。
                long submittedAtMs = System.currentTimeMillis();
                String submittedBy = Thread.currentThread().getName();
                if (log.isInfoEnabled()) {
                    log.info("CronIdleExecutor: [M4] 队列变更 → 提交 poll 到执行器（尚未开跑）"
                            + " thread={} submittedAtMs={}", submittedBy, submittedAtMs);
                }
                cronExecutor.execute(() -> {
                    try {
                        if (log.isInfoEnabled()) {
                            log.info("CronIdleExecutor: [M4] poll 已开跑（事件驱动）提交→开跑 延迟={}ms"
                                    + " thread={} 提交线程={}",
                                System.currentTimeMillis() - submittedAtMs,
                                Thread.currentThread().getName(), submittedBy);
                        }
                        poll(this::executeQueuedInput);
                    } finally {
                        processing.set(false);
                    }
                });
                submitted = true;
            }
            // else: canDispatch()==false（非 Spring 单测缺 cronExecutor / loopProvider）→ 无可用异步
            //       通道 ⇒ **不提交、不出队**（条目留队列 = 延迟送达，对齐 M3「跑得起来才出队」）；
            //       闸由 finally 就地释放。fail-loud 交给 3s 兜底那处 WARN（本事件驱动路径若每条队列
            //       变更都告警会成噪声源，与本类既有「不得成为噪声源」约定冲突）。
            //   [P2 · 2026-09-18 返工] 原因措辞改用 dispatchBlockReason()（判据单点）：字段缺失与
            //   「字段在但产出不了实例」必须分开说，⛔ 不得再笼统一句「未注入」。
            else if (log.isDebugEnabled()) {
                log.debug("CronIdleExecutor: [M4 补] 队列变更 → 不提交也不出队（{}，"
                        + "本批跑不起来；条目留队列待通道恢复后取）", dispatchBlockReason());
            }
        } catch (RuntimeException | Error e) {
            // [M3/B2 守卫] 提交失败（执行器已关闭/拒绝）或提交前的日志/取值抛错（含 OOME=Error）
            //   → 任务体永不执行 ⇒ 闸必须就地释放，否则 processing 恒 true 会让事件驱动与 3s 兜底
            //   两条入口**同时**永久停摆（M3 后兜底也走本闸 ⇒ 原「兜底可自愈」不再成立）。
            log.error("CronIdleExecutor: 提交 poll 到执行器失败（闸将由 finally 就地释放）: {}",
                e.toString(), e);
        } finally {
            if (!submitted) {
                processing.set(false);
            }
        }
    }

    /**
     * 定时轮询入口（对齐 CC 1s tick 量级 → 3s fixedDelay，实施登记）。
     * [P3] 保留作兜底（防通知丢失），事件驱动已覆盖 0 延迟主路径。
     *
     * <p><b>[M3 · 2026-09-18] 与 {@link #onQueueChanged} 同构（出队只发生在「跑得起来」的地方）</b>：
     * 原实现直接在本 {@code @Scheduled} 线程（scheduling-1）调 {@code poll} —— <b>绕过 {@code processing}
     * 闸</b>，即在一条「可能被永久占住的通道之外」出队 ⇒ 事故链「3s 线程出队 {@code remove}（取出即删）
     * → 提交给已死的 cron-idle-1 → 无 ack 无回队 → 永久静默丢」正是从这来的。改为闸内提交到
     * {@code cronExecutor} 后再 poll ⇒ 执行器不空时条目<b>根本不会被取走</b>（症状从「永久静默丢」
     * →「<b>延迟送达</b>」）；顺带消除 core=1/queue=100 + CallerRunsPolicy 把整轮 run 内联到
     * scheduling-1 的风险；并为 M2 提供「同一时刻只有一个 poll」的原子性前提。
     */
    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void pollScheduled() {
        if (notificationQueue == null) {
            log.warn("CronIdleExecutor: notificationQueue 未注入，轮询跳过");
            return;
        }
        if (!processing.compareAndSet(false, true)) {
            // 已有 poll 在跑（事件驱动或上一轮兜底）→ 跳过本轮，条目留在队列，下轮/事件驱动再取
            if (log.isDebugEnabled()) {
                log.debug("CronIdleExecutor: 3s 兜底轮询跳过（processing 闸已被占，说明已有 poll 在跑）");
            }
            return;
        }
        // [B2 · 2026-09-18 返工] 闸自 CAS 起**全段**必须有释放兜底：本方法挂在固定 3s 节奏上，
        //   原实现把 try 起点放在「提交侧 INFO」**之后**、catch 只捕 RuntimeException ⇒
        //   日志行/取值抛 Throwable（含 OOME=Error）或提交失败时闸可能永久 true ⇒ 两条入口
        //   （事件驱动 + 本兜底）**同时**永久停摆。形态 = submitted 标志 + finally 收口。
        boolean submitted = false;
        try {
            // [B1 · 2026-09-18 返工] 空队列短路 —— 本批新引入的无条件 INFO 噪声回归：
            //   提交侧 INFO + 任务体首行 INFO 在**空队列**下也每 3s 打 2 行 = 57600 行/天（约 8MB），
            //   而改前旧实现空队列**一行不打**。同仓先例 WebSocketDeliveryDiagnostics 逐字写
            //   「本类自身绝不能变成噪声源」。判据复用既有 notificationQueue.hasCommandsInQueue()
            //   （poll 顶部同款），不新建语义。
            //   ⚠️ 短路 return 也走下方 finally 释放闸（⛔ 绝不能带着闸 return）。
            if (!notificationQueue.hasCommandsInQueue()) {
                if (log.isDebugEnabled()) {
                    log.debug("CronIdleExecutor: 3s 兜底轮询：队列无命令，跳过"
                            + "（空队列不打 INFO —— 本类不得成为噪声源）");
                }
                return;
            }
            if (!canDispatch()) {
                // [M3 补 · 2026-09-18 / M4 补 · 2026-09-18 返工] 依赖未注入（非 Spring 环境）⇒ **绝不出队**。
                //   ⛔ 原实现在此就地 poll，而 poll 出队（取出即删）后回调 executeQueuedInput 会在其
                //   顶部命中同一条「cronExecutor/loopProvider 未注入」早退 ⇒ **批次出队即丢弃、永不送达**；
                //   而此处原 INFO 却声称「本批已出队并提交」= 假绿措辞（本批修的正是同类：日志即证据）。
                //   [M3] 原则「出队只发生在**跑得起来**的地方」——本分支恰好是 M3 漏掉的最后一处：
                //   缺任一条通道 ⇒ executeQueuedInput 恒早退 ⇒ 这里**永远跑不起来** ⇒ 不得出队。
                //   ⭐ [M4 补] 判据从「只判 cronExecutor」扩为 canDispatch()（**判两条通道**）—— 复核实测：
                //   loopProvider 为 null 而 cronExecutor 非 null 时，原文照样提交→出队→丢弃（条目
                //   afterSubmit=1/afterRun=0，日志里「本批已出队并提交执行器」照样出现）；回调兜底
                //   判的是两条、出队前守卫只判一条 = 同一判据的两个拷贝漂移。现两处 + 回调同源 canDispatch()。
                //   条目留队列（延迟送达），待注入后再取；生产环境两条通道恒在（cronExecutor = AsyncConfig
                //   恒定义 @Bean / loopProvider = Spring 恒注入 ObjectProvider），本分支只在非 Spring 单测可达。
                //   [P2 · 2026-09-18 返工] 原因措辞改用 dispatchBlockReason()（判据单点，与 canDispatch 同源）：
                //   多出第三类原因「字段在但 loopProvider 产出不了实例」（复核实测可达）。
                log.warn("CronIdleExecutor: 3s 兜底轮询：{} →"
                        + " 本批不提交、也不出队，条目留在队列（防「出队即丢弃」；对齐 M3「跑得起来才出队」）",
                    dispatchBlockReason());
                return;
            }
            long submittedAtMs = System.currentTimeMillis();
            String submittedBy = Thread.currentThread().getName();
            if (log.isInfoEnabled()) {
                log.info("CronIdleExecutor: [M4] 3s 兜底轮询 → 提交 poll 到执行器（尚未开跑）"
                        + " thread={} submittedAtMs={}", submittedBy, submittedAtMs);
            }
            cronExecutor.execute(() -> {
                try {
                    if (log.isInfoEnabled()) {
                        log.info("CronIdleExecutor: [M4] poll 已开跑（3s 兜底轮询）提交→开跑 延迟={}ms"
                                + " thread={} 提交线程={}",
                            System.currentTimeMillis() - submittedAtMs,
                            Thread.currentThread().getName(), submittedBy);
                    }
                    boolean processed = poll(this::executeQueuedInput);
                    if (processed && log.isInfoEnabled()) {
                        // ⚠️ 本行只声称「已出队并提交执行器」——**不等于** agent_loop 已开跑。
                        //   原措辞「轮询消费队列并启动 agent_loop」是假绿：事故中该行出现 5 次而真正启动 0 次。
                        //   开跑证据 = 同批 uuid 的「[M4] 已开跑」行；未开跑 = 10s 后的 WARN。
                        log.info("CronIdleExecutor: [M4] 3s 兜底轮询：本批已出队并提交执行器（尚未开跑；"
                                + "开跑证据见同批 uuid 的「[M4] 已开跑」行，对齐 CC processQueueIfReady）");
                    }
                } finally {
                    processing.set(false);
                }
            });
            submitted = true;
        } catch (RuntimeException | Error e) {
            // [M3/B2 守卫] 提交失败 ⇒ 任务体永不执行 ⇒ 闸必须就地释放（由 finally 执行），
            //   否则两条入口同时永久停摆（M3 后兜底也走本闸）。
            log.error("CronIdleExecutor: 3s 兜底轮询提交失败（闸将由 finally 就地释放）: {}",
                e.toString(), e);
        } finally {
            if (!submitted) {
                processing.set(false);
            }
        }
    }

    /**
     * 消费语义 — 对齐 CC queueProcessor.ts:52-87 processQueueIfReady。
     *
     * @param executeInput 命令消费回调（测试可注入 fake）
     * @return 是否消费并启动
     */
    public boolean poll(Consumer<List<NotificationQueue.QueueItem>> executeInput) {
        // [OD-D7] 整队列 cron 门已删除 · CC 真源：queueProcessor.ts:52-87 processQueueIfReady 与
        // useQueueProcessor.ts:48-67 全函数零 cron 引用 —— 队列消费（回合间 drain）与 cron 开关无关；
        // isKilled (cronScheduler.ts:231) 只 gate 调度 tick（cron 项是否 fire 入队）。原实现把门放在
        // poll() 顶部 → 门关冻结整段 poll（busy-queued/task-notification 空闲消费也被停）属自创过宽。
        // OD-D7 收窄：cron skip 下沉到 mainThreadConsumable 谓词（下方逐条跳过 WORKLOAD_CRON 项），
        // 非 cron 命令照常消费（门关不空转）。TestJob.fire producer gate（OPD-Cron-07-h「关闭后已注册
        // 任务立即停止」）保留 —— 本处只管消费侧语义。
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
            // [OD-D7] cron 开关收窄 · 门关仅跳过 cron workload 项，busy-queued/task-notification 照常
            //   空闲消费（对齐 CC：queueProcessor.ts:52-87 / useQueueProcessor.ts:48-67 队列消费零 cron
            //   引用；isKilled 只 gate 调度 tick cronScheduler.ts:231）。判别样式 = mode=prompt +
            //   workload='cron'（TestJob fire 入队唯一样式，探查已证）。cron 项被 skip 后留队列
            //   （producer gate 已停 fire，存量项等门开或由 missed surface 兜底）。null 未注入 →
            //   fail-open 视为开（不 skip）。log.debug 记录每次 skip（原整段 warn 改为逐条 debug）。
            if (cronGates != null && !cronGates.isKairosCronEnabled()
                    && NotificationQueue.WORKLOAD_CRON.equals(cmd.workload())) {
                if (log.isDebugEnabled()) {
                    log.debug("CronIdleExecutor: 定时功能已关闭，跳过 cron workload 命令 mode={} "
                            + "value前20字符={}（OD-D7 收窄：仅 cron 停，busy-queued/通知照常；"
                            + "对齐 CC queueProcessor.ts 零 cron 引用）",
                        cmd.mode(), cmd.value() != null && cmd.value().length() > 20
                            ? cmd.value().substring(0, 20) : cmd.value());
                }
                return false;
            }
            String target = resolveSessionUuid(cmd.sessionId());      // sessionId==null → GLOBAL_SESSION_KEY
            // [M2 · 2026-09-18] 判别从 isSessionRunning（running 单态）扩为 isSessionActive
            //   （dispatching || running，对齐 CC QueryGuard.isActive QueryGuard.ts:99-101）：
            //   dispatching = 「该会话条目已出队、异步链尚未开跑」的保留态。⛔ 若只判 running，
            //   M1 改虚拟线程后提交不再全序 ⇒ 同会话可在缺口内被连续出队两批（TOCTOU）。
            //   逐条跳过语义不变（修饿死：跳过运行中会话的命令，空闲会话照常处理）。
            if (LlmAgentLoop.isSessionActive(target)) return false;   // 目标会话活跃（dispatching/running）→ 逐条跳过
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
            //   busy-queued 现由运行中 turn 的工具边界 mid-turn 消费（[C3 2026-09-19] LlmAgentLoop
            //   的 mid-turn drain 位于 messagesForQuery 快照之前 ⇒ 产物进紧接的那一轮请求 = 同轮可见；
            //   drainForQuery 不再过滤 workload）；本路径仅兜底「当前轮结束仍残留 busy-queued」——纯文本轮末无更多
            //   工具边界注入、最后一次 drain 之后入队、后台 loop 未捞的消息（CC useQueueProcessor.ts:48-67
            //   turn 结束兜底消费语义）。与 mid-turn 注入互斥：运行中 isSessionRunning 跳过，turn 结束
            //   后 mid-turn drain 不再发生 → 无双发。
            return true;                                              // 空闲会话 cron/通知/busy-queued → 代跑
        };
        Optional<NotificationQueue.QueueItem> nextOpt = notificationQueue.peek(mainThreadConsumable);
        if (nextOpt.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("CronIdleExecutor: 无可消费主线程命令（门关 cron skip / 会话运行中 / 真实会话"
                        + "用户 prompt / 仅 subagent 均可能），跳过（CC queueProcessor.ts:64-66）；"
                        + "onQueueChanged/3s 轮询会再触发");
            }
            return false;
        }
        NotificationQueue.QueueItem next = nextOpt.get();
        // [3d] 批量归组键：[session-id-short] QueueItem.sessionId 已 short 裸 equals 直键
        // （原 canonicalUuid 归一化铁律失去前提 —— CRON-D5 F2 双形态根因消除）。
        // sessionId==null 全局命令归组到 GLOBAL_SESSION_KEY（= SessionKeys.NO_SESSION 哨兵键，
        // [cwd3 步骤 1b]），同组不混会话。
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
        // 串行 runOneAgentLoop 按各自 QueueItem.sessionId 显式建会话上下文，混会话批次会串台。
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
     * [OD-D6] 剔除 null / blank value 的通知 · 对齐 handlePromptSubmit.ts:500 continue 语义
     * （cmd.value() 为 null/blank → 跳过）。批量分支先过滤再判批（反射器 MAJOR-1 定死），
     * 过滤后 size<2 回落逐条 for（现状）。
     */
    private static List<NotificationQueue.QueueItem> filterNonBlank(List<NotificationQueue.QueueItem> commands) {
        if (commands == null) return List.of();
        return commands.stream()
            .filter(c -> c != null && c.value() != null && !c.value().isBlank())
            .toList();
    }

    /**
     * [OD-D6] 纯 task-notification 批判定（反射器 MAJOR-1 定死）：
     * ≥2 条且全部 mode=task-notification 且非 slash（无 prompt/cron/slash 混批）→ true。
     * 混批（busy-queued 真实用户 prompt / cron / slash 与 task-notification 同批）→ false 回落逐条现状
     * （保留红线 4：混批不合并）。
     */
    private static boolean isPureTaskNotificationBatch(List<NotificationQueue.QueueItem> commands) {
        if (commands == null || commands.size() < 2) return false;
        for (NotificationQueue.QueueItem c : commands) {
            if (!NotificationQueue.MODE_TASK_NOTIFICATION.equals(c.mode()) || isSlashCommand(c)) return false;
        }
        return true;
    }

    /**
     * 真实消费（对齐 CC executeQueuedInput）：batch 命令串行各启动一轮 agent_loop，
     * 每命令独立 run（对齐 CC handlePromptSubmit 逐命令独立 user message）。
     *
     * <p><b>[M2 · 2026-09-18] 「同会话不并发」的守点已从「池串行」迁到「per-session 保留态」</b>：
     * 原注释自认依赖 {@code cronExecutor} core=1（提交全序是假安全）。[M1] 改虚拟线程后提交不再全序，
     * 改由本方法顶部 {@code LlmAgentLoop.reserve}（出队点同步占位）+ 任务体 {@code finally} 释放守住
     * （逐字对齐 CC {@code QueryGuard} dispatching）。同会话条目在保留期内被 {@code poll} 的
     * {@code isSessionActive} 谓词跳过 ⇒ 留在队列等待，不再并发、更不丢件。
     */
    private void executeQueuedInput(List<NotificationQueue.QueueItem> commands) {
        // [M2 · 2026-09-18] 出队点同步占位（CC QueryGuard dispatching）—— 本方法是 poll() 出队后
        //   **同步**调用的消费回调（poll :444/:457 executeInput.accept），故「占位」与「出队」在同一
        //   时刻发生（无窗口）。占位后本会话在 isSessionActive 上恒 true ⇒ 后续 poll 不会再把同会话
        //   条目取出（条目留在队列 = 延迟送达，而非「取出即删」的静默丢）。
        //   键 = resolveSessionUuid(cmd.sessionId())，与 :387 谓词的判别键同源（含 GLOBAL 哨兵）。
        //   同批同会话（poll 的批量归组键已保证），取首条即可。
        // [B2 · 2026-09-18 返工] try 起点提到 reserve **之前**。
        //   原实现 reserve 成功后到 try 起点之间（取键 / uuids / preview60 / 提交侧 INFO）任何
        //   Throwable 都会让 reserveKey **永久留在 DISPATCHING_SESSIONS**（唯一释放点原在任务体
        //   finally 与提交 catch）⇒ 该会话 isSessionActive 恒 true ⇒ 该会话的 cron/子代理完成通知
        //   **永不再出队**（与事故原症状同类）。且原 catch 只捕 RuntimeException ⇒ M1 后每任务新建
        //   虚拟线程，Thread.start() 抛 OOME 是 Error，同样永久泄漏。
        //   ⛔ 保留态生命周期**必须长于本方法**（跨到异步任务体结束）⇒ 不能用「外层 finally 无条件
        //   释放」。形态 = reservedFlag（跨 lambda 可见的 holder，裸局部变量因二次赋值非
        //   effectively-final）+ submitted 标志：仅当**未成功提交**（任务体永不执行）才就地释放。
        //   注：reserveKey / batchUuids 在 reserve 之前求值（此刻无保留态 ⇒ 抛错不泄漏），
        //   且让 catch 内的日志能引用 batchUuids。
        // [F3 · P-b · 2026-09-18 返工] 前置求值（原裸在方法体里、在任何 try 之外）现包一层 try。
        //   这三条语句本身都不抛业务异常（纯映射 / 静态解析 / JDK 构造），但<b>OOME 级 Throwable</b>
        //   会让它们带着「已出队、从未被处理」的条目逃出本方法 —— 出队是「取出即删」，逃出即
        //   静默丢件。此刻零副作用（无落库 / 无推流 / 无 run）⇒ 回队幂等。
        //   ⚠️ 声明留在 try 外（catch 日志与提交后的看门狗都要用；且需 effectively-final 供 lambda 捕获），
        //   取值顺序 = uuidsOf 先（纯映射，最不可能抛）。
        List<String> batchUuids;
        String reserveKey;
        java.util.concurrent.atomic.AtomicBoolean reservedFlag;
        try {
            batchUuids = uuidsOf(commands);
            reserveKey = resolveSessionUuid(commands.isEmpty() ? null : commands.get(0).sessionId());
            reservedFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        } catch (RuntimeException | Error e) {
            // 回队失败自身也回队不了（队列写失败）时由 requeueAfterDispatchFailure 内如实 ERROR 留痕。
            requeueAfterDispatchFailure(commands, List.of(),
                "任务体前置求值失败（" + e.getClass().getSimpleName() + "；任务体从未启动）", e);
            throw e;
        }
        boolean submitted = false;
        try {
            // [P2 · 2026-09-18 返工] 出队后复核（判据与两处出队前守卫同源 canDispatch()）。
            //   ⛔ 原实现：本分支只 log.error「未注入，命令无法执行（丢弃 N 条）」= **静默丢件的最后一道**
            //   （条目已被 poll 取出即删，本分支就是本批要修的事故症状本身）。现改为**回队**（不丢件）：
            //   条目回队列 = 延迟送达，通道恢复后由 3s 兜底轮询取走。
            if (!canDispatch()) {
                requeueAfterDispatchFailure(commands, batchUuids,
                    "出队后复核 canDispatch()==false: " + dispatchBlockReason(), null);
                return;
            }
            boolean reserved = LlmAgentLoop.reserve(reserveKey);
            reservedFlag.set(reserved);
            if (!reserved && log.isWarnEnabled()) {
                // 保留失败（该会话已 running 或已被前一批 dispatching 占住）= TOCTOU 窗口被撞上。
                // 策略：**照常执行**（绝不丢件 —— 丢件是本事故的原症状），只记录「本批未占位」
                // （并发窗口与改动前相同，不新增退化）。
                log.warn("CronIdleExecutor: [M2] 会话保留占位失败（已 running / 已 dispatching）"
                        + " reserveKey={} 条数={} uuids={} —— 本批照常执行但不占位（防丢件）",
                    reserveKey, commands.size(), uuidsOf(commands));
            }
            long submittedAtMs = System.currentTimeMillis();
            String submittedBy = Thread.currentThread().getName();
            java.util.concurrent.atomic.AtomicBoolean started =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            if (log.isInfoEnabled()) {
                // [M4] 提交侧：如实措辞「已出队并提交执行器（**尚未开跑**）」+ uuids + 内容前 60 字符。
                //   ⛔ 绝不写「已启动 agent_loop」—— 提交 ≠ 开跑（本事故盲区 L5）。
                log.info("CronIdleExecutor: [M4] 出队 {} 条并提交执行器（尚未开跑）reserveKey={} uuids={}"
                        + " 内容前60={} submittedAtMs={}",
                    commands.size(), reserveKey, batchUuids,
                    commands.stream().map(c -> preview60(c.value())).collect(Collectors.toList()),
                    submittedAtMs);
            }
            cronExecutor.execute(() -> {
            // [F3 · P-g · 2026-09-18 返工] 任务体**未消费游标** —— 本批中尚未进入处理（零副作用）的
            //   条目起点。任何 Throwable 逃出任务体时，只有 [cursor, size) 段可安全回队；
            //   [0, cursor) 段已进入处理（user 消息可能已落库、message.user 可能已推流）⇒ 回队会
            //   重复落库 / 重复推送，按本批已确立的「已消费不回队」语义只留显式 ERROR。
            //   ⚠️ 用 AtomicInteger（跨 lambda 可见的 holder；裸 int 无法在 lambda 内自增并被捕获）。
            java.util.concurrent.atomic.AtomicInteger consumedCursor =
                new java.util.concurrent.atomic.AtomicInteger(0);
            try {
                // [M4] 任务体**首行**「已开跑」标志（提交 → 已开跑 配对 + 延迟）。
                //   ⚠️ 不复用 :915/:899 的「启动 agent_loop」—— 那在 runOneAgentLoop 内，前面隔着
                //   落库 user 消息 / 推 message.user / slash 分派，会把「开跑了但卡在落库」误读成
                //   「没开跑」。本行 = 任务真的被某条线程取起来执行了。
                started.set(true);
                if (log.isInfoEnabled()) {
                    log.info("CronIdleExecutor: [M4] 已开跑 提交→开跑 延迟={}ms thread={} reserveKey={} uuids={}",
                        System.currentTimeMillis() - submittedAtMs,
                        Thread.currentThread().getName(), reserveKey, batchUuids);
                }
            // [OD-D6] 纯 task-notification 批（≥2 条，无 slash/无 prompt 混批，值非空）→ 一次 run 合并注入
            //   （对齐 CC handlePromptSubmit.ts processUserInput 循环收集 N 条 → 一次 onQuery(N messages)
            //   → 1 轮 1 assistant，:513/:560；修复 N 条重复 assistant 全文 bug）。
            //   filterNonBlank 剔除 null/blank value 的通知（对齐 :500 continue 语义）；过滤后 size<2 → 回落逐条 for（现状）。
            List<NotificationQueue.QueueItem> batch = filterNonBlank(commands);
            if (isPureTaskNotificationBatch(batch)) {
                // [F3 · P-g] 整批一次性 run（CC onQuery 单点语义）⇒ 整批视为**已消费**
                //   （失败即整批丢，由下方 catch 显式 ERROR 披露 cmdUuids）——不得把整批回队
                //   （会重复落库/重复推送，且 CC 语义本就是「一次 run 要么整批成功要么整批丢」）。
                consumedCursor.set(commands.size());
                try {
                    runOneAgentLoopBatch(batch);
                    if (log.isInfoEnabled()) {
                        log.info("CronIdleExecutor: OD-D6 批量合并空闲 task-notification，{} 条通知合并一轮"
                                + "（对齐 CC onQuery(N messages)）cmdUuids={}",
                            batch.size(),
                            batch.stream().map(NotificationQueue.QueueItem::uuid).collect(Collectors.toList()));
                    }
                } catch (Exception e) {
                    // [OD-D6 异常隔离] 整批一次性 run 失败即整批丢（CC onQuery 单点语义）；
                    // 必须 log.error 披露 cmdUuids，绝不静默吞（对齐 :576 中文日志风格）
                    log.error("CronIdleExecutor: OD-D6 批量合并 run 失败，{} 条通知整批丢失 cmdUuids={}: {}",
                        batch.size(),
                        batch.stream().map(NotificationQueue.QueueItem::uuid).collect(Collectors.toList()),
                        e.toString(), e);
                }
                return;  // 整批已处理，跳过逐条 for
            }
            for (int ci = 0; ci < commands.size(); ci++) {
                NotificationQueue.QueueItem cmd = commands.get(ci);
                // [F3 · P-g] 进入本条的**处理块**即视为「已消费」（保守：本条之后的任何一步都可能
                //   已产生副作用 —— emitDrained / 落库 user 消息 / 推 message.user / run）。
                //   逃逸 Throwable 时本段不回队（回队 = 同一 uuid 再落库再推送）；未进入处理块的
                //   剩余条目才回队（见任务体 catch (Throwable)）。
                consumedCursor.set(ci + 1);
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
                            // [busy 附件快照] 携非图片附件快照（busy-queued，enqueueBusyPrompt 构造）→
                            //   8 参重载落 V63 user_attachments：端后兜底路径（本 turn 无后续工具边界
                            //   → 残留 busy 消息由本执行器起新轮消费）与 mid-turn drain 实时落库路径
                            //   落出同一份快照 —— 否则「消息在轮末才被消费」这条常见路径的
                            //   user_attachments 仍恒 NULL（F5 后气泡附件胶囊照样消失）。
                            //   无快照（cron / 纯文本 busy）仍走 5 参（invoked overload 不变）。
                            MessageCreatedResponse created;
                            if (cmd.userAttachments() != null && !cmd.userAttachments().isEmpty()) {
                                created = messageService.createQueuedUserMessage(
                                    cmd.sessionId(), cmd.uuid(), cmd.value(), OffsetDateTime.now(), isCron,
                                    null /* queuedOrigin：端后兜底不标 busy-queued，与 5 参路径同语义 */,
                                    null /* imagePasteIds：busy 图由 AM 回写 / doRun 注册链承载，本处不重复 */,
                                    cmd.userAttachments());
                            } else {
                                created = messageService.createQueuedUserMessage(
                                    cmd.sessionId(), cmd.uuid(), cmd.value(), OffsetDateTime.now(), isCron);
                            }
                            // 落库后真实 user 消息 id（cron uuid=null 时 createQueuedUserMessage 内部 generateId 兜底）
                            if (created != null && created.userMessageId() != null) {
                                persistedUserId = created.userMessageId();
                                consumedUserId = persistedUserId;
                            }
                            if (log.isInfoEnabled()) {
                                log.info("CronIdleExecutor: prompt 落库 user 消息 session={} id={} workload={}"
                                        + " userAttachments={}（busy 附件快照 → V63 列，F5 气泡附件胶囊）",
                                    cmd.sessionId(), persistedUserId, cmd.workload(),
                                    cmd.userAttachments() == null ? 0 : cmd.userAttachments().size());
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
                    //     （会话上下文显式传递 / DURABLE boundProject override / streamContext / replayAndPersist）。
                    //   - local / local-jsx / 未知命令 / fork 占位（shouldQuery=false）→ 非查询型终态：
                    //     local 在 intercept 内部经 UserInputDispatcher.dispatchResult 本地执行，有结果
                    //     文本则落库 + 推会话流（真实会话可见），不起 LLM turn（CC local/local-jsx
                    //     shouldQuery=false :657-722；未知命令 "Unknown skill" :333-361）。
                    //   - 解析抛异常 → 外层 catch 兜底 log.error，不阻断循环。
                    if (isSlashCommand(cmd) && slashInterceptor != null) {
                        // [CRON-D5 改2 · 批 3c] 原「恢复创建会话 MDC（local handler 依赖裸 MDC 会话槽）」
                        //   已删：会话标识改由**显式形参**直传（intercept(cmd.sessionId(), ...)）→
                        //   UserInputDispatcher 的 handler 形参为 (args, sessionId, inFlightUserMessageId)
                        //   → 本地执行链不再有经 MDC 读会话的通道，也就无需线程池复用的还原装置。
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
                        // [批 4b-1] 原 projectRoot ThreadLocal 的 capture/restore 成对块已删
                        //   （载体删除，无可回放对象；用户铁律：会话态一律显式传参）。
                    }
                    runOneAgentLoop(cmd);
                } catch (Exception e) {
                    // [F3 · P-h · 2026-09-18 返工] 补 **uuid**（原日志只有前 20 字符 ⇒ 无法与 [M4]
                    //   提交/开跑行按 uuid 对齐，「这条到底跑到哪一步」不可判）。
                    //   ⛔ 本分支**不回队**（已核）：到达此处时本条的 user 消息可能已落库
                    //   （上方 createQueuedUserMessage，指定 id = cmd.uuid()）且 message.user 可能
                    //   已推流（publishUserMessageEvent 同 id）⇒ 回队 = 同 uuid 再落一次 + 再推一次
                    //   （重复落库 / 前端重复气泡）。按本批已确立的「已消费不回队」语义，改为**显式
                    //   ERROR 带 uuid**。P-h（探测通过后 runAgentLoop 内 loopProvider.getObject() 变
                    //   不可用）正落在这条路径上：出队前的 canDispatch() 探针已判过一次，此处是
                    //   「探测通过后又不可用」的竞态，条目按已消费处理，但绝不静默（本行留痕）。
                    log.error("CronIdleExecutor: 执行入队 prompt 失败（本条不回队：可能已落库/已推流）"
                            + " uuid={} reserveKey={} command前20字符={}",
                        cmd.uuid(), reserveKey,
                        cmd.value() != null ? cmd.value().substring(0, Math.min(20, cmd.value().length())) : "",
                        e);
                }
            }
            } catch (Throwable t) {
                // [F3 · P-g · 2026-09-18 返工] 逃出任务体的 **Error 级** Throwable（逐条
                //   catch (Exception) 兜不住）—— 原实现让「尚未执行的条目」随异常一起消失（无回队）。
                //   本 catch 只回队 [cursor, size) 段：该段**保证零副作用**（从未进入处理块）⇒ 回队幂等；
                //   [0, cursor) 段已进入处理（见 consumedCursor 的 WHY）⇒ 不回队，只显式 ERROR。
                int cursor = Math.min(consumedCursor.get(), commands.size());
                List<NotificationQueue.QueueItem> notConsumed = cursor >= commands.size()
                    ? List.of()
                    : new java.util.ArrayList<>(commands.subList(cursor, commands.size()));
                List<String> notConsumedUuids = uuidsOf(notConsumed);
                log.error("CronIdleExecutor: [F3] 任务体逃逸未捕获的 Throwable（{}）—— 本批 {} 条："
                        + "已进入处理 {} 条（⛔ 不回队：可能已落库/已推流，重投会重复落库重复推送），"
                        + "未进入处理 {} 条 uuids={} reserveKey={}",
                    t.toString(), commands.size(), cursor, notConsumed.size(), notConsumedUuids, reserveKey, t);
                if (!notConsumed.isEmpty()) {
                    requeueAfterDispatchFailure(notConsumed, notConsumedUuids,
                        "任务体逃逸 Throwable（" + t.getClass().getSimpleName() + "）；仅未消费条目回队", t);
                }
            } finally {
                // [M2 · 2026-09-18] 保留态释放（dispatching → idle）—— 本 finally 是**唯一**释放点，
                //   覆盖任务体**所有**出口：`:531` 批量早退 return / 逐条 `continue`（:610 空值、
                //   :617 userInvocable 拒绝、:673 slash 终态）/ 正常走完 / 任何 RuntimeException。
                //   ⛔ 漏一条 = 该会话永久 dispatching ⇒ **新的静默卡死**（本批最需要防的退化）。
                //   ⛔ 仅当本次 reserve 成功才释放（reserve 失败的批次不得误清他人保留）。
                //   [B2] 用 reservedFlag holder（裸局部变量非 effectively-final，lambda 不可捕获）+
                //   getAndSet(false) 保证**恰好释放一次**（提交侧 catch 与任务体 finally 双路径，
                //   后者先跑时前者不得再清一次 —— 否则会误清本批之后他人的保留）。
                if (reservedFlag.getAndSet(false)) {
                    LlmAgentLoop.cancelReservation(reserveKey);
                }
            }
            });
            submitted = true;
            // [P1 · 2026-09-18 返工] 通道恢复（提交成功）⇒ 连续失败计数归零。
            //   ⛔ 抑制窗口不在这里显式清除：它是**时间窗**，且只作用于事件驱动入口（见其 WHY）。
            consecutiveDispatchFailures.set(0);
            // [M4] 「同批提交后 N 秒未见开跑」WARN —— 判据 = 该批 uuid + submittedAtMs（task 体首行置位），
            //   ⛔ 不用 processing 闸时长（闸在一次长 run 期间恒 true，会系统性误报）。
            DISPATCH_WATCHDOG.schedule(() -> {
                if (!started.get()) {
                    log.warn("CronIdleExecutor: [M4] 同批提交后 {}s 未见开跑 reserveKey={} uuids={}"
                            + " submittedAtMs={} 提交线程={} —— 执行器可能被长 run 占住或任务被搁置"
                            + "（判据=该批 uuid+submittedAtMs，非 processing 闸时长）",
                        DISPATCH_STALL_WARN_SECONDS, reserveKey, batchUuids, submittedAtMs, submittedBy);
                }
            }, DISPATCH_STALL_WARN_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (RuntimeException | Error e) {
            // [B2] 提交失败（执行器拒绝等）或提交前/看门狗登记时任何 Throwable（含 Error：M1 后每任务
            //   新建虚拟线程，Thread.start() 抛 OOME 是 Error，原 catch (RuntimeException) 漏捕）
            //   → 任务体永远不会跑 ⇒ 就地释放，防保留态永久泄漏。
            //   ⛔ 仅当**未成功提交**才释放（已提交 = 任务体在跑，由其 finally 释放；此处误清会
            //   提前放开保留 ⇒ 同会话并发）。
            if (!submitted) {
                if (reservedFlag.getAndSet(false)) {
                    LlmAgentLoop.cancelReservation(reserveKey);
                }
                // [P1 · 2026-09-18 返工] 提交失败 ⇒ 整批**回队**（条目不消失）+ 开事件驱动抑制窗口。
                //   ⛔ 原实现只有 log.error + rethrow ⇒ 条目「已出队、永不会再跑」= 静默丢件
                //   （复核实测：queue.size=0 / runCalled=0，条目永久消失）。
                //   回队安全性：本分支只在任务体**从未启动**时到达（`cronExecutor.execute` 抛出，或
                //   提交前任何 Throwable）⇒ 本批任何副作用（落库 / 推流 / run）都还没发生 ⇒ 幂等。
                //   [P2] 出队后复核失败（canDispatch()==false）走上面 try 内的 return，同样回队。
                requeueAfterDispatchFailure(commands, batchUuids,
                    "提交执行器失败（任务体从未启动）", e);
            }
            log.error("CronIdleExecutor: 提交执行器失败（未成功提交则已就地释放保留态并**回队**）"
                    + "reserveKey={} uuids={}: {}", reserveKey, batchUuids, e.toString(), e);
            throw e;
        }
    }

    /**
     * 启动一轮 agent_loop — 镜像 ChatService.processUserMessage 依赖注入
     * （tokenBudget/config/memory/recovery），主线程 agentId=null（对齐 CC 主线程契约）。
     *
     * <p><b>[CRON-D5 改2 + 改3]</b>（cron 后台任务会话上下文对齐 CC）：cronExecutor 线程无会话上下文
     * （ThreadLocal 不跨线程），cron 触发的 agent_loop 工作目录域此前全回落 user.dir（跨会话 cwd
     * 错位）。改2 = 消费前恢复创建会话上下文 —— <b>[批 3c] 原「写裸 MDC 会话槽」已删</b>，会话标识
     * 改由**显式载体**直传（{@link RunRequest#session}/{@link RunRequest#sessionBatch} 的 sessionUuid、
     * {@code loop.setStreamContext(ws, cmd.sessionId(), …)}、{@code chatService.armRealTimePersist}）；
     * 改3 = {@link RunRequest} 用真实创建会话 UUID（非 GLOBAL 常量）→ {@code markRunning/isSessionRunning}
     * 归组创建会话 + {@code CwdResolution.getCwd(sessionId)} 解析到创建会话 boundProject/sessionCwd
     * （对齐 CC 单进程 ambient：任务即属创建会话）。SESSION scope cron 时 log.info 中文记录并就地
     * 显式打印 {@code sessionId=...}（批 3c：日志前缀不再从 MDC 取）；DURABLE/无 sessionId 回落 GLOBAL（现状）。
     *
     * <p><b>[批次X Q2 + 批 1 · 方向 C]</b>（DURABLE 项目锚 · 对齐 CC durable 文件位置锚项目）：
     * SESSION 任务走 {@code cmd.sessionId()} 恢复（会话仍存活才可命中 boundProject）；DURABLE 任务锚从
     * {@code cmd.boundProject()}（V23 bound_project 列，TestJob fire 经 QueueItem 透传）取 ——
     * <b>[批 1]</b> 非空时经 {@link RunRequest#withBoundProject(String)} 挂到本 run 的
     * {@link RunRequest} 上（<b>显式值传参</b>：对齐 CC 把 cwd 作为值带在队列命令上
     * useScheduledTasks.ts:52/:110 {@code currentDir: getCwd()}；CC 的
     * {@code utils/cwd.ts:4} AsyncLocalStorage 在 cron 路径零调用），
     * {@code LlmAgentLoop} 从 {@code RunRequest.boundProject()} 取出 → workspaceDir + memory 项目身份
     * + base TUC {@code effectiveCwd}（本回合 cwd）。
     * <b>原两条 ThreadLocal 通道已删</b>（① {@code CwdResolution.runWithCwdOverride}；
     * ② {@code LlmAgentLoop.setCronProjectRootOverride}）—— 派生线程读不到 ThreadLocal 会静默落
     * user.dir（正是用户 2026-09-13 裁定的失效模式）。
     * 无项目锚的形态有两类，缺值语义按用户裁定 1 分离：无锚且无 sessionId（全局 cron / 无会话直建
     * DURABLE）→ 项目根无处解析、回落 user.dir/configHome ⇒ <b>WARN</b> 留痕；无锚但有 sessionId
     * （SESSION fire）→ 项目根由 sessionId 解析（值未缺失，未跳过）⇒ INFO。
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
        runAgentLoop(cmd, null, null);
    }

    /**
     * [P2 · slash 消费兜底] 技能级 model 覆盖重载。
     *
     * @param modelOverride  技能级 model 覆盖（CC processSlashCommand.tsx:917 model: command.model）；
     *                       null → 主模型
     */
    private void runOneAgentLoop(NotificationQueue.QueueItem cmd, String modelOverride) {
        runAgentLoop(cmd, modelOverride, null);
    }

    /**
     * [OD-D6] 批量合并入口 · 同批 ≥2 条纯 task-notification 一次 run（对齐 CC handlePromptSubmit.ts
     *   循环收集 N 条 → 一次 onQuery(newMessages) → 1 轮 1 assistant）。
     *
     * <p>职责（反射器 MINOR-3 定死）：首条 {@code cmds.get(0)} 走完整判定链（DURABLE / R4 已删会话 →
     *   sessionUuid、setStreamContext、实时落库 SPI 武装、cwd override —— 全部复用
     *   {@link #runAgentLoop} 首条逻辑）；后续 N-1 条 value 作 {@code extraPrompts} 经
     *   {@link RunRequest#sessionBatch} 与首条原文合并注入（idle 形态、isMeta=false）。
     *   userMessageId / streamContext / 落库归属均用<b>首条</b> cmd.uuid()（同批同 session，收口归属正确）。
     *
     * @param cmds 已过滤（filterNonBlank）的纯 task-notification 批，size≥2
     */
    private void runOneAgentLoopBatch(List<NotificationQueue.QueueItem> cmds) {
        List<String> extraPrompts = cmds.subList(1, cmds.size()).stream()
            .map(NotificationQueue.QueueItem::value)
            .collect(Collectors.toList());
        runAgentLoop(cmds.get(0), null, extraPrompts);
    }

    /**
     * [OD-D6] 启动一轮 agent_loop 公共实现 · 由 {@link #runOneAgentLoop}（单条）/ {@link #runOneAgentLoopBatch}
     *   （批量）汇聚。
     *
     * <p>{@code extraPrompts} 非空 → 批量模式：RunRequest 用 {@link RunRequest#sessionBatch}
     *   （首条原文 + 后续 N-1 条原文，od-d6-batch-plan §3.2）；null → 单条模式（现状
     *   RunRequest.session，行为零变化）。
     *
     * @param cmd           首条命令（批量 = 首条；单条 = 该条）
     * @param modelOverride 技能级 model 覆盖（CC processSlashCommand.tsx:917）；null → 主模型
     * @param extraPrompts  批量模式下后续 N-1 条原文；null/空 = 单条模式
     */
    private void runAgentLoop(NotificationQueue.QueueItem cmd, String modelOverride, List<String> extraPrompts) {
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
        // [session-id-short] QueueItem.sessionId 已统一 short（"sess-xxx"），直键使用
        // （原 CRON-D5 F2 originalKey 反解派生 UUID 串的键形态双形态已消除）。
        // [批 3c] 原「CRON-D5 改2：恢复创建会话 MDC（setSession）+ finally clear 防线程池串台」已删：
        //   会话标识不再经 MDC 载体传播（载体已随本批删除）。cron run 的会话来源全部是显式载体 ——
        //   RunRequest.session/sessionBatch(sessionUuid)（下方）、loop.setStreamContext(ws, cmd.sessionId(), …)、
        //   chatService.armRealTimePersist(state, sessionUuid, …)：值随调用直传，无线程槽残留面。
        //   [批 4b-1] projectRoot 线程槽的 capture/restore 亦已删除（载体删除，无可回放对象）。
        if (sessionId != null && !sessionId.isBlank()) {
            log.info("CronIdleExecutor: cron 命令会话上下文 sessionId={} "
                    + "（批 3c：显式载体直传 RunRequest/streamContext/落库，不再写裸 MDC）", sessionId);
        } else if (log.isWarnEnabled()) {
            log.warn("CronIdleExecutor: cmd 无 sessionId → 本 run 无会话锚（回落全局会话/user.dir，"
                + "DURABLE 或兼容路径，CRON-D5）: mode={} workload={}", cmd.mode(), cmd.workload());
        }
        // [批 3c] 原「清裸 MDC 会话槽」（与已删的 setSession 成对）已删：本 run 不再写任何
        //   界面/日志 MDC 槽。
        // [批 4b-1] 原 AutoMemPaths.restoreCurrentProjectRoot(prevProjectRoot) 已删：
        //   projectRoot ThreadLocal 载体删除，本 fire 不再捕获/回放任何线程槽。
        // [批 1 · 方向 C] 原 finally 的 loop.clearCronProjectRootOverride()（per-run 项目身份
        //   override 清空）随该实例字段一并删除：项目锚改由 RunRequest 承载（req 随本 fire
        //   局部变量丢弃 → 无线程池串台面，无需清空装置）。
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
        // [批 A4b · 模式传递] 队列 drain 起轮必须带上【会话选定的有效权限模式】。
        //   ⛔ 原实现两条 drain 路径（sessionBatch :897 / session :912）都走「无 permissionModeCli 的
        //   便捷重载」⇒ 该字段硬编码 null ⇒ InitialPermissionModeResolver 回落 settings 槽
        //   （DB 全局 settings.permission_mode；本机实测 = bypassPermissions）⇒ **整轮静默绕过全部
        //   权限检查（含 deny）**。与 ChatService.processUserMessage（传 per-call ?? 会话 override）
        //   两条路径行为不一致。CronIdleExecutor 无 per-call 来源 ⇒ 取会话 override，与 ChatService
        //   共用同一判据点 ChatService.resolveEffectivePermissionMode。
        //   拿不到会话（headless / GLOBAL 哨兵 / DB 行缺失）⇒ 该方法内部 ≥WARN 显式留痕，不许静默。
        String effectivePermissionMode = resolveSessionPermissionMode(sessionUuid, cmd);
        // [OD-D6] 批量模式（extraPrompts 非空）→ RunRequest.sessionBatch：首条原文 + 后续 N-1 条原文
        //   一次 run（对齐 CC onQuery(newMessages)）；null → 单条模式（现状 RunRequest.session）。
        RunRequest req;
        if (extraPrompts != null && !extraPrompts.isEmpty()) {
            List<String> allPrompts = new ArrayList<>(extraPrompts.size() + 1);
            allPrompts.add(promptValue);
            allPrompts.addAll(extraPrompts);
            req = RunRequest.sessionBatch(allPrompts, sessionUuid, null, config, modelName, null,
                effectivePermissionMode, false, null);
            if (log.isInfoEnabled()) {
                log.info("CronIdleExecutor: 启动 agent_loop（OD-D6 批量 {} 条）, mode={}, model={}, sessionId={}, permissionMode={}",
                    allPrompts.size(), cmd.mode(), modelName, sessionUuid, effectivePermissionMode);
            }
        } else {
            // [OD-D5] 端后兜底携附件：残留 busy-queued（cmd.attachments() = 入队时已过校验/已解析的
            //   全类型附件，见 ChatService.busyQueuedResolvedAttachments）→ 12 参 RunRequest.session
            //   附件重载（[批 A4b] 由原 10 参升级：补 permissionModeCli + dangerouslySkipPermissions
            //   —— 附件形态不变）→ doRun registerRunPromptImages + registerRunPromptPdfs +
            //   buildMediaAttachmentNotes/buildLargeImagePathNotes 单次注册/注入（enqueue 未预登记
            //   pending 桶 → 无双份；reflector MAJOR-5）。task-notification/cron 无附件 →
            //   cmd.attachments() 空列表，行为零变化。sessionBatch 无需补附件（OD-D6 batch 仅
            //   task-notification，不携附件）。
            //   [attach-busy-resolve 2026-09-18] 原注释举「≤5MB base64 image」为例已作废 ——
            //   cmd.attachments() 现为全类型已解析附件（mid-turn drain 未消费到此的项由本兜底承接）。
            //   [批 A4b] dangerouslySkipPermissions 恒 false：drain 侧无 per-call HTTP 请求体来源，
            //   不得凭空置 true（置 true 会新增一条绕过面）。
            //   [批 A4e] 改走 sessionFromSessionOverride：把值的<b>槽位</b>（会话 override 列）
            //   显式写在调用点上（来源标注 SESSION_OVERRIDE），与 sessionBatch 路径同源。
            //   ⛔ 不得退回任何硬编码 null permissionModeCli 的便捷重载 —— 那会声明
            //   NOT_APPLICABLE 却携带真实会话 ⇒ LlmAgentLoop 守护 WARN（批 A4b 型缺陷）。
            req = RunRequest.sessionFromSessionOverride(
                promptValue, sessionUuid, null, config, modelName, null,
                effectivePermissionMode, false, null, cmd.attachments());
            log.info("CronIdleExecutor: 启动 agent_loop, mode={}, prompt长度={}, model={}, sessionId={}, attachments={}, permissionMode={}",
                cmd.mode(), promptValue.length(), modelName, sessionUuid,
                cmd.attachments() == null ? 0 : cmd.attachments().size(), effectivePermissionMode);
        }
        // [批 1 · 方向 C] DURABLE 项目锚显式传参（QueueItem.boundProject → RunRequest.boundProject）：
        //   本 run 的 cwd / memory 项目身份由【值】承载、跨线程可见，替代原两条 ThreadLocal 通道
        //   （CwdResolution.runWithCwdOverride + LlmAgentLoop.setCronProjectRootOverride）。
        //   缺值语义（用户 2026-09-13 裁定 1）：
        //     (a) 本该有却没有 ⇒ 不适用（DURABLE 无项目锚是本仓已知合法形态）；
        //     (b) 本路径不需要 ⇒ 可跳过，但必须 ≥ WARN 可观测（⛔ 不得只 DEBUG）——
        //         「无锚且无 sessionId」（全局 cron / 无会话直建 DURABLE）时项目根无处可解析，
        //         回落 user.dir/configHome，故 WARN；「无锚但有 sessionId」（SESSION fire）
        //         项目根由 sessionId 解析（值不缺失，未被跳过）→ INFO 留痕即可。
        if (boundProject != null && !boundProject.isBlank()) {
            req = req.withBoundProject(boundProject);
            log.info("CronIdleExecutor: 显式项目锚已挂载 RunRequest boundProject={} "
                    + "mode={} sessionId={}（批 1 方向 C：本显式锚替代了原两条**已删** ThreadLocal cwd "
                    + "通道，对齐 CC 值随队列命令直传；[S2 F-07] 其中 override 通道的全仓载体亦已删除）",
                boundProject, cmd.mode(), sessionId);
        } else if (sessionId == null || sessionId.isBlank()) {
            log.warn("CronIdleExecutor: 本 run 无项目锚（QueueItem.boundProject 空）且无 sessionId"
                    + "→ cwd/memory 项目根无处可解析，回落 user.dir/configHome（合法 (b) 类跳过，"
                    + "但必须可观测：mode={} workload={}）", cmd.mode(), cmd.workload());
        } else {
            log.info("CronIdleExecutor: 本 run 无显式项目锚（QueueItem.boundProject 空）→ 项目根由 sessionId 解析"
                    + "（session={} mode={}，值未缺失，非跳过）", sessionId, cmd.mode());
        }
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
        // [实时落库 2026-09-03] cron run 前武装实时落库 SPI（与主会话同一 ChatService.armRealTimePersist）：
        //   doRun 历史注入完成后回调 → setAppendListener，cron 轮 assistant/tool/snip_boundary 逐条实时落库
        //   （对齐 CC onFireTask 结果实时写 transcript）。门控三条件：真实会话（非 GLOBAL）/ chatService
        //   注入 / loop.run 前。传 cmd.uuid()（=cron user 消息 id）作 DB user_message_id 归属根
        //   （对齐原 replayAndPersist lastUserMessageId = cmd.uuid()）。cron 无 queued-user → user 分支天然跳过。
        if (chatService != null && sessionUuid != null && !GLOBAL_SESSION_KEY.equals(sessionUuid)) {
            String persistTopic = "/topic/sessions/" + sessionUuid + "/stream";
            org.springframework.messaging.simp.SimpMessagingTemplate persistWs = wsTemplate;
            loop.setPostHistoryPersistEnabler(state ->
                chatService.armRealTimePersist(state, sessionUuid, persistTopic, persistWs, cmd.uuid()));
            if (log.isInfoEnabled()) {
                log.info("CronIdleExecutor: 实时落库 SPI 已武装 session={} userMsgId={} mode={}"
                        + "（对齐 CC onFireTask 逐条实时写 transcript）",
                    sessionUuid, cmd.uuid(), cmd.mode());
            }
        }
        // [批 1 · 方向 C] 项目锚（boundProject）已在 req 上显式挂载（见上方 withBoundProject），
        //   本处直接 run —— 不再经 ThreadLocal 通道注入执行线程：
        //   原两条通道已删：① CwdResolution.runWithCwdOverride（ThreadLocal CURRENT_OVERRIDE，
        //   派生线程读不到 → 工具链落 user.dir 的静默错值）；② loop.setCronProjectRootOverride
        //   （实例字段 + Consumption 端写 AutoMemPaths ThreadLocal）。现 lane：值在 req 上
        //   → resolveSessionProjectRoot(boundProject) → workspaceDir + base TUC effectiveCwd。
        // [cron-complete 修复] 本轮耗时锚点（publishCompleteEvent duration_ms 装配用 · 与
        //   ChatService.processUserMessage :330 同款 turn 墙钟近似）。
        long turnStartMs = System.currentTimeMillis();
        // [C6 · 停止键可见性] drain run 起轮 / 收口推会话状态事件（与 ChatService.processUserMessage
        //   :871「1) session.status=thinking」同款契约）：前端据此把该会话标为「运行中」→ 显示停止键。
        //   WHY 必须补：此前 drain 路径**不推任何 status** ⇒ 用户在本页看着该会话，后台 drain 起的 run
        //   在 UI 上毫无迹象（前端本地 activeStreams 只在「本页发送」时登记、思考/等待权限阶段更无 chunk）
        //   ⇒ 无从终止。thinking/idle 成对推且 idle 放 finally（且恒为【最后】一条，见下方 A2 注释）
        //   ⇒ run 抛异常也不会把前端永久留在「运行中」。
        pushSessionStatus(wsTemplate, sessionUuid, cmd.uuid(), "thinking", "drain run 起轮");
        AgentState runState;
        try {
            runState = loop.run(req);

            // [C6 · A2 · 事件顺序] 收口（解除落库监听 + complete + title）必须先于 idle 推送 ——
            //   与主路径 ChatService.runAgentLoop 同序（complete :1120 → idle :1131 finally）。
            //   WHY：前端的「服务端运行态 true→false 收口边沿」在 idle 那一刻触发，该边沿上的对账会调
            //   chatStore.finalizeBlocks 把残留流式块**定稿**（useServerRunning.ts · useServerRunningReconcile）
            //   ——若 idle 先于 complete 推，块就会在 complete 之前被定稿、流式块列表被清空 ⇒ 紧随其后的
            //   那条 complete 的 finalizeBlocks 变成 no-op ⇒ 丢 turn 级 usage / 成本 / 上下文快照，且提前一拍。
            //   ⛔ 与「退订 / 丢帧」无关：前端当前会话的 stream 订阅是**常驻**的（useChatSocket.ts:547
            //   `if (!active[sid] && sid !== sessionIdRef.current)` ⇒ 本会话不退订）。
            if (runState != null) {
                // [实时落库] run 返回后收口：解除 appendListener（防泄漏 / 下轮误触发）——恒在 runState 非 null
                //   时执行（未武装时 listener 恒 null，clear 无害），对齐主会话 processUserMessage 收口语义。
                runState.clearAppendListener();
                // [SM/compact 对齐 CC] 同步解除压缩落库监听（armRealTimePersist 同点武装，防泄漏下轮）
                runState.clearCompactPersistListener();
            }
            if (runState != null && chatService != null
                    && sessionUuid != null && !GLOBAL_SESSION_KEY.equals(sessionUuid)) {
                String streamTopic = "/topic/sessions/" + sessionUuid + "/stream";
                try {
                    // [cron-complete 修复] 推 message.complete 收口（userMessageId=cron user 消息 id，
                    //   对齐 effectiveEventUserMessageId 语义 = state.lastUserMessageId()=cmd.uuid()）：
                    //   复用 ChatService publishCompleteEvent（正常 turn 同款装配），前端 finalize cron 块，
                    //   不再残留 streams（根治被后续用户 turn complete 混收口倒挂）。realAssistantId=null →
                    //   方法内部回落末条 assistant 真实 id。wsTemplate null → sendAndLog 守卫跳过推送仅落库。
                    chatService.publishCompleteEvent(sessionUuid, cmd.uuid(), runState, streamTopic, wsTemplate,
                        turnStartMs, null);
                    if (log.isInfoEnabled()) {
                        log.info("CronIdleExecutor: cron 触发实时落库+complete 收口完成 session={} mode={}"
                                + "（对齐 CC onFireTask 结果实时写 transcript + 正常 turn complete 收口）",
                            sessionUuid, cmd.mode());
                    }
                } catch (Exception e) {
                    log.error("CronIdleExecutor: cron 触发 complete 收口失败 session={}: {}",
                        sessionUuid, e.toString(), e);
                }
                // [title-cc-align] CronIdleExecutor 收口补 title 生成 · 对齐 CC 所有路径汇聚 onQuery
                //   都会检查 title（initReplBridge.ts:349-378 onUserMessage）：busy-queued（用户消息）
                //   计数生效；cron/task-notification（非 title-worthy，isMeta=true）计数不增，幂等安全。
                //   OD-D6 批量合并轮同样走本收口（runAgentLoop 公共路径）→ title 对合并轮生效。
                //   sessionUuid 真实会话（headless null → 本分支不达）；cmd.uuid()=该轮 user 消息 id。
                try {
                    SessionRecord s = sessionMapper != null ? sessionMapper.selectOneById(sessionUuid) : null;
                    if (s != null) {
                        chatService.maybeGenerateTitle(s, cmd.uuid(),
                            runState.lastAssistant() == null ? "" : runState.lastAssistant(), wsTemplate);
                    }
                } catch (Exception e) {
                    log.warn("CronIdleExecutor: 收口 title 生成失败 session={}: {}",
                        sessionUuid, e.getMessage());
                }
            } else if (runState != null) {
                if (log.isDebugEnabled()) {
                    log.debug("CronIdleExecutor: cron 结果跳过收口 sessionUuid={} chatServiceNull={}"
                            + "（headless 无 transcript / 非 Spring 单测）",
                        sessionUuid, chatService == null);
                }
            }
        } finally {
            // idle 恒在此推（run 抛异常也不把前端永久留在「运行中」），但必须【最后】推 ——
            //   顺序契约见上方注释（与主路径一致）。
            pushSessionStatus(wsTemplate, sessionUuid, cmd.uuid(), "idle", "drain run 收口");
        }
    }

    /**
     * [C6 · 停止键可见性] 推会话状态事件（{@code session.status} · 契约与 ChatService 同源）。
     *
     * <p>drain 路径（排队命令 / cron / 任务通知）此前不推任何 {@code session.status} —— 前端因此
     * 完全不知道「本会话有 run 在跑」（前端本地簿记只登记本页发送的 turn）⇒ UI 无停止键。
     * 本方法把 drain run 的起轮（{@code thinking}）/ 收口（{@code idle}）补进同一条会话级事件流：
     * topic {@code /topic/sessions/{sessionId}/stream}（与 chunk / complete 同一 topic，前端已常驻订阅）。
     *
     * <p>守卫：无 wsTemplate（非 STOMP 路径）/ 无真实会话（headless / {@code GLOBAL_SESSION_KEY} 哨兵）
     * ⇒ 静默跳过（无前端会话可收）。推送失败仅 WARN 不阻断 run。
     *
     * <p><b>真源不是本事件</b>：本事件只是「实时信号」；「该会话是否在跑」的权威判据是
     * {@link LlmAgentLoop#isSessionActive}（见 {@code ChatController#running} 端点）——
     * 前端漏收本事件时由那次查询重建。
     */
    private void pushSessionStatus(org.springframework.messaging.simp.SimpMessagingTemplate ws,
                                   String sessionId, String userMessageId, String status, String why) {
        if (ws == null || sessionId == null || sessionId.isBlank() || GLOBAL_SESSION_KEY.equals(sessionId)) {
            return;
        }
        try {
            String topic = "/topic/sessions/" + sessionId + "/stream";
            ws.convertAndSend(topic, com.nexusai.eventbus.ws.SessionStatusEvent.of(sessionId, userMessageId, status));
            if (log.isInfoEnabled()) {
                log.info("CronIdleExecutor: STOMP → topic={} type=session.status status={} session={}（{}）",
                    topic, status, sessionId, why);
            }
        } catch (Exception e) {
            log.warn("CronIdleExecutor: 会话状态推送失败 status={} session={}: {}", status, sessionId, e.getMessage());
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
     * [批 A4b] 队列 drain 起轮的【会话选定有效权限模式】。
     *
     * <p><b>WHY</b>：drain 轮此前不带 {@code permissionModeCli}（RunRequest 便捷重载硬编码 null）⇒
     * {@code InitialPermissionModeResolver} 回落 settings 槽（DB 全局 {@code settings.permission_mode}，
     * 本机实测 {@code bypassPermissions}）⇒ <b>整轮静默绕过全部权限检查（含 deny）</b>。
     * 本方法取会话 override，与 HTTP 入口 {@code ChatService.processUserMessage}
     * （{@code per-call ?? 会话 override}）<b>共用同一判据点</b>
     * {@link ChatService#resolveEffectivePermissionMode}（drain 侧无 per-call ⇒ 传 null）。
     *
     * <p><b>拿不到会话时必须 ≥WARN（⛔ 不得静默回落全局）</b>：三种不可得情形分别留痕 ——
     * ① {@code sessionUuid == null}（headless：DURABLE 创建会话已关 / 无会话直建）；
     * ② {@code sessionUuid} 是 {@link SessionKeys#NO_SESSION} 哨兵（全局 cron / 普通 prompt，确无会话）；
     * ③ DB 行不存在（会话已删）或 sessionMapper 未注入（非 Spring 单测）。
     * 三者返回 null（⇒ resolver 回落 settings 槽），但<b>每次都有 WARN 说明回落原因</b>。
     *
     * <p>会话存在但 {@code sessions.permission_mode} 本就为空 ⇒ 返回 null：这是设计的三态链
     * （{@code 会话 override ?? 全局 default}）的<b>正常末态</b>，不是「静默失效」，故只记 INFO。
     *
     * @param sessionUuid 本 run 的会话键（可能为 null / NO_SESSION 哨兵）
     * @param cmd         队列命令（留痕用）
     * @return 会话选定模式；不可得 → null（已 WARN 留痕，非静默）
     */
    private String resolveSessionPermissionMode(String sessionUuid, NotificationQueue.QueueItem cmd) {
        if (sessionUuid == null) {
            log.warn("CronIdleExecutor: 队列 drain 轮**拿不到会话**（headless：DURABLE 创建会话已关 / "
                + "无会话直建）→ 无法取会话选定权限模式，permissionModeCli=null ⇒ 回落全局 "
                + "settings.permission_mode（本机实测可为 bypassPermissions ⇒ 该轮将绕过全部权限检查）"
                + "。【≥WARN 显式留痕，非静默】mode={} workload={}",
                cmd.mode(), cmd.workload());
            return null;
        }
        if (SessionKeys.isNoSession(sessionUuid)) {
            log.warn("CronIdleExecutor: 队列 drain 轮**确无会话**（sessionUuid=NO_SESSION 哨兵：全局 cron / "
                + "普通 prompt）→ 无法取会话选定权限模式 ⇒ 回落全局 settings.permission_mode"
                + "（本机实测可为 bypassPermissions）。【≥WARN 显式留痕，非静默】mode={} workload={}",
                cmd.mode(), cmd.workload());
            return null;
        }
        SessionRecord session = loadSessionRecord(sessionUuid);
        if (session == null) {
            log.warn("CronIdleExecutor: 队列 drain 轮会话行不存在/不可读（sessionUuid={}，会话已删或 "
                + "sessionMapper 未注入）→ 无法取会话选定权限模式 ⇒ 回落全局 settings.permission_mode"
                + "【≥WARN 显式留痕，非静默】mode={} workload={}",
                sessionUuid, cmd.mode(), cmd.workload());
            return null;
        }
        String mode = ChatService.resolveEffectivePermissionMode(session, null);
        log.info("CronIdleExecutor: 队列 drain 轮权限模式已装配 sessionUuid={} session.permission_mode={} "
            + "→ permissionModeCli={}（与 ChatService 同源 resolveEffectivePermissionMode；null ⇒ resolver "
            + "回落全局 settings，属三态链正常末态）",
            sessionUuid, session.getPermissionMode(), mode);
        return mode;
    }

    /**
     * [批 A4b] 按会话键读会话行（drain 侧权限模式来源）。
     *
     * <p>键形态与 {@link #isSessionAlive} 同一权威：short 直键 {@code selectOneById}，存量旧行经
     * {@link SessionKeys#originalKey} 兼容反解兜底。
     *
     * @param sessionId 会话键（short）
     * @return 会话行；键空白 / mapper 未注入 / DB 无行 → null
     */
    private SessionRecord loadSessionRecord(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionMapper == null) {
            return null;
        }
        String originalKey = SessionKeys.originalKey(sessionId);
        if (originalKey == null) {
            originalKey = sessionId;
        }
        return sessionMapper.selectOneById(originalKey);
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
     *
     * <p>[cwd3 · 步骤 1b] {@link #GLOBAL_SESSION_KEY} 的值现在是 {@link SessionKeys#NO_SESSION}
     * 哨兵 ⇒ 返回值在 cwd 域被 {@code CwdResolution} 顶部的 {@code SessionKeys.isNoSession} 短路
     * <b>显式识别</b>，走命名无会话出口 {@code getCwdForNonSession()}（不查 DB、不打「伪造 id」
     * 告警）—— 而不再是「DB 查无此会话」的 unknown 分支（步骤 2 起该分支 fail-loud 抛）。
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
