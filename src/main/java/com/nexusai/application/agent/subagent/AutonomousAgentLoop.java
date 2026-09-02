package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.compact.CompactThresholdSystem;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.ClaimTaskResult;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.infra.util.AbortControllerFactory;
import com.nexusai.infra.util.SwarmConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * s17-P1-1/2/4/5 最小可行实现 · 对齐 CC services/team/inProcessRunner.ts:689-868.
 *
 * <p>CC autonomous agent 生命周期:
 * <ol>
 *   <li><b>WORK</b>: 正在执行当前任务 (SubagentExecutor.execute)</li>
 *   <li><b>IDLE</b>: 当前任务完成, 进入 5s poll 等待新工作 (idle_poll: inbox + task 轮询)</li>
 *   <li><b>SHUTDOWN</b>: 收到 shutdown 信号, 退出循环</li>
 * </ol>
 *
 * <p>本类提供状态机 + 主循环, 不实现真实的子任务执行 (P1-3 Dream forked agent 留续).
 *
 * <h2>W8-01 状态机扩展（OPD-TP-06）</h2>
 * <p>生产基底确认: 本类为 {@code subagent/} 三生产基底之一 (TeammateContext/AutonomousAgentLoop/
 * AgentMessageBus), 保留作实施基底。W8-01 在 AutonomousAgentLoop 上补:
 * <ul>
 *   <li><b>终端转换 completed/failed/killed</b> (对齐 CC inProcessRunner.ts:1419-1533
 *       completed/failed + spawnInProcess.ts:227-328 killed): notified:true + endTime +
 *       清 runtime 字段 + <b>alreadyTerminal 守卫防双发</b> (inProcessRunner.ts:1428/:1479)</li>
 *   <li><b>notified/endTime/evict/SDK 链</b>: {@link TaskFrameworkService#evictTerminalTask}
 *       (framework.ts:124-147) + {@link SdkEventQueue#emitTaskTerminatedSdk}
 *       (sdkEventQueue.ts:114-134)</li>
 *   <li><b>{@link #tryAutoClaimAndExecute()} 接线</b>: 恒 false → 真实认领
 *       (对齐 CC tryClaimNextTask inProcessRunner.ts:624-657)</li>
 * </ul>
 */
@Component
public class AutonomousAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AutonomousAgentLoop.class);

    /** s17-P1-2: CC inbox + task poll 间隔 (CC utils/swarm/inProcessRunner.ts:697 POLL_INTERVAL_MS) */
    public static final long IDLE_POLL_INTERVAL_MS = 5_000;

    /** W8-02: 运行循环 poll 间隔 · 对齐 CC inProcessRunner.ts:697 POLL_INTERVAL_MS=500 */
    public static final long POLL_INTERVAL_MS = 500;

    /** s17-P1-2: CC idle_poll 最大持续时间 (60s 后强制 sleep) */
    public static final long IDLE_POLL_MAX_MS = 60_000;

    /** W8-01: kill 后延迟 evict 终端任务 (对齐 CC STOPPED_DISPLAY_MS framework.ts:25=3_000) */
    public static final long STOPPED_DISPLAY_MS = 3_000;

    @Autowired private AgentMessageBus messageBus;

    @Autowired private TaskFrameworkService taskFrameworkService;

    @Autowired private SdkEventQueue sdkEventQueue;

    @Autowired private TaskService taskService;

    /** W1-3: 阈值体系（可选注入）· 对齐 CC inProcessRunner.ts:1073-1076
     *  {@code getAutoCompactThreshold(toolUseContext.options.mainLoopModel)}。Spring bean 由
     *  CompactThresholdConfig 注册（CompactThresholdSystem，含 env override 与 model 窗口解析器）；
     *  required=false —— 测试/未接线（无 bean 上下文）时回落 {@link #autoCompactThresholdTokens} 兜底，
     *  行为与旧实现一致（同 AgentLoopContextFactory:131 注入先例）。 */
    @Autowired(required = false)
    private CompactThresholdSystem compactThresholdSystem;

    /** 当前状态 (state machine). 默认 IDLE (新 agent 启动后等待) */
    private volatile AgentState state = AgentState.IDLE;

    /** 当前 agent ID (用于 inbox 路由). 由调用方 setAgentId() 设置. */
    private volatile String agentId;

    /** W8-01: agent 显示名 (identity.agentName, CC spawnInProcess.ts:128) */
    private volatile String agentName;

    /** W8-01: team 名 (identity.teamName, CC spawnInProcess.ts:130) */
    private volatile String teamName;

    /** W8-01: 当前认领的任务 id (CC taskId) */
    private volatile String taskId;

    /** W8-01: 任务列表 id (taskListId = identity.parentSessionId, CC runner:1019) */
    private volatile String taskListId;

    /** W8-01: 生命周期 abortController (CC spawnInProcess.ts:122 独立 createAbortController) */
    private volatile AbortControllerFactory.AbortControllerRef abortController;

    /** W8-02: 本轮 work abortController (CC inProcessRunner.ts:1056, Escape 只停本轮不杀队友) */
    private volatile AbortControllerFactory.AbortControllerRef currentWorkAbortController;

    /** W8-02: 待交付用户消息队列 · 对齐 CC task.pendingUserMessages (types.ts:59) */
    private final Deque<String> pendingUserMessages = new ArrayDeque<>();

    /** W8-02: idle 回调 · 对齐 CC task.onIdleCallbacks (types.ts:71) */
    private final List<Runnable> onIdleCallbacks = new CopyOnWriteArrayList<>();

    /** W8-02: 是否空闲 (CC task.isIdle, types.ts:66) · 用于防重复 idle 通知 */
    private volatile boolean isIdle = true;

    /** W8-02: model 覆盖 (CC identity.model / config.model, spawnInProcess.ts:169) · 每轮 runAgent 透传 */
    private volatile String model;

    /** T-C: per-teammate 内容替换状态 · 对齐 CC inProcessRunner.ts:1043-1045
     *  {@code teammateReplacementState}（父 toolUseContext.contentReplacementState 存在时
     *  createContentReplacementState，否则 undefined = feature off）。
     *  跨 while 循环迭代持久化（否则每轮 runAgent 从 createSubagentContext 拿 fresh empty state，
     *  重新做 holistic replace-globally-largest 决策，wire prefix 漂移 → cache miss）。
     *  auto-compact 时 reset（CC :1111-1113 createContentReplacementState）。null = feature off。 */
    private volatile ContentReplacementState contentReplacementState;

    /** T-C: auto-compact 阈值（token）兜底值 · 对齐 CC inProcessRunner.ts:1075-1076
     *  {@code tokenCount > getAutoCompactThreshold(mainLoopModel)}。默认值 167_000 = CC 默认模型
     *  auto-compact 阈值（effectiveWindow 200k 上下文 − 20k reserved-for-summary = 180k
     *  − 13k AUTOCOMPACT_BUFFER_TOKENS buffer，autoCompact.ts:72-91 + :62），
     *  作为模型无关下界（等价 {@link #compactThresholdSystem} 按默认 200k 窗口模型动态计算的结果）。
     *  W1-3: 生产路径经 {@link #compactThresholdSystem}（@Autowired 可选注入）
     *  按当前模型每轮动态计算（effectiveWindow − 13_000 buffer，autoCompact.ts:72-91 + env override）；
     *  本字段仅在未注入（测试/未接线）或经 {@link #setAutoCompactThresholdTokens} 显式设置时生效。 */
    private volatile int autoCompactThresholdTokens = 167_000;

    /** W8-02: 运行循环消息镜像 · 对齐 CC task.messages (types.ts:53) · cap 50 经
     *  InProcessTeammateTypes.appendCappedMessage (types.ts:89-101, UI 镜像防 RSS 膨胀) */
    private final java.util.List<String> messages = new ArrayList<>();

    /** W8-01: 状态载体 · 对齐 CC types.ts:22-76 InProcessTeammateTaskState（spawn 产出的
     *  identity/prompt/permissionMode 等纯数据 + runtime-only 控制器）。
     *  <p>由 {@link com.nexusai.application.agent.team.SpawnInProcess#spawnInProcessTeammate}
     *  创建并注入；terminal 转换（complete/fail/kill）与 BackgroundTask 状态层桥接。 */
    private volatile com.nexusai.application.agent.team.InProcessTeammateTaskState taskState;

    /** W8-02: 每轮执行委托 · 对齐 CC runAgent 等价 (SubagentExecutor.executeStreaming) ·
     *  可选注入, 未注入时 runOneTurn 仅记录 (测试/教学回退) */
    private volatile com.nexusai.application.agent.tool.impl.SubagentExecutor subagentExecutor;

    /** W8-02 REWORK: team 文件工具 · kill 时 removeMemberByAgentId（CC spawnInProcess.ts:302-304） */
    private volatile TeamHelpers teamHelpers;

    /**
     * [team-panel-backend-bugfix2] Team 状态推送单点 · kill 移除成员后发 member_left
     * （/topic/sessions/{leadSessionId}/team-status，前端面板刷新）。可选注入：未注入（测试直构）→
     * 跳过推送（对齐 TeamCreateTool teamStatusPublisher 模式）。
     */
    private volatile com.nexusai.application.agent.team.TeamStatusPublisher teamStatusPublisher;

    /** W8-01: alreadyTerminal 守卫 (防 completed/failed/killed 双发, CC :1428/:1479) */
    private final Set<String> terminalTasks = ConcurrentHashMap.newKeySet();

    /** shutdown 标志 (避免阻塞 poll) */
    private volatile boolean shutdownRequested = false;

    /** W8-01: kill 后延迟 evict 调度器 */
    private final ScheduledExecutorService evictScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "teammate-evict");
            t.setDaemon(true);
            return t;
        });

    /**
     * W8-04: 出站消息 sink · 对齐 CC runner 终端转换后 task_status attachment 进入 transcript。
     *
     * <p>teammate 终端转换（completed/failed/killed）时产出 task_status attachment
     * （{@link TeammateMessageFoldingChain#teammateTaskStatusAttachment}），经本 sink 追加到
     * 会话出站消息列表 → GET /messages 折叠链（TeammateMessageFoldingChain.collapse）消费。
     * 未注入时跳过（测试/教学回退，不抛错）。
     */
    private volatile java.util.function.Consumer<com.nexusai.model.session.dto.ChatMessageDto> outboundSink;

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /** W8-01: 测试/接线用 setter (agentName). */
    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    /** W8-01: 测试/接线用 setter (teamName). */
    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    /** W8-01: 测试/接线用 setter (taskId). */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /** W8-01: 测试/接线用 setter (taskListId). */
    public void setTaskListId(String taskListId) {
        this.taskListId = taskListId;
    }

    /** W8-01: 测试/接线用 setter (abortController). */
    public void setAbortController(AbortControllerFactory.AbortControllerRef abortController) {
        this.abortController = abortController;
    }

    /** W8-01: 测试/接线用 setter (taskFrameworkService). */
    public void setTaskFrameworkService(TaskFrameworkService taskFrameworkService) {
        this.taskFrameworkService = taskFrameworkService;
    }

    /** W8-01: 测试/接线用 setter (sdkEventQueue). */
    public void setSdkEventQueue(SdkEventQueue sdkEventQueue) {
        this.sdkEventQueue = sdkEventQueue;
    }

    /** W8-01: 测试/接线用 setter (taskService). */
    public void setTaskService(TaskService taskService) {
        this.taskService = taskService;
    }

    /** W8-02: 测试/接线用 setter (model 覆盖). */
    public void setModel(String model) {
        this.model = model;
    }

    /** T-C: 测试/接线用 setter (per-teammate 内容替换状态 · 父 toolUseContext.contentReplacementState
     *  存在时 SpawnInProcess 注入 {@code ContentReplacementState.create()})。 */
    public void setContentReplacementState(ContentReplacementState contentReplacementState) {
        this.contentReplacementState = contentReplacementState;
    }

    /** T-C: 当前 per-teammate 内容替换状态（测试/诊断）。 */
    public ContentReplacementState contentReplacementState() {
        return contentReplacementState;
    }

    /** T-C: 测试/接线用 setter (auto-compact token 阈值)。 */
    public void setAutoCompactThresholdTokens(int autoCompactThresholdTokens) {
        this.autoCompactThresholdTokens = autoCompactThresholdTokens;
    }

    /** W8-02: 测试/接线用 setter (每轮执行委托). */
    public void setSubagentExecutor(com.nexusai.application.agent.tool.impl.SubagentExecutor subagentExecutor) {
        this.subagentExecutor = subagentExecutor;
    }

    /** W8-02 REWORK: 测试/接线用 setter (team 文件工具 · kill 成员移除). */
    public void setTeamHelpers(TeamHelpers teamHelpers) {
        this.teamHelpers = teamHelpers;
    }

    /** [team-panel-backend-bugfix2] 测试/接线用 setter（teamStatusPublisher · kill 移除成员后 member_left 推送）. */
    public void setTeamStatusPublisher(com.nexusai.application.agent.team.TeamStatusPublisher teamStatusPublisher) {
        this.teamStatusPublisher = teamStatusPublisher;
    }

    /** W8-04: 测试/接线用 setter (outboundSink · teammate 终端 task_status attachment 出站). */
    public void setOutboundSink(java.util.function.Consumer<com.nexusai.model.session.dto.ChatMessageDto> sink) {
        this.outboundSink = sink;
    }

    /** W8-01: 测试/接线用 setter (状态载体 · CC types.ts:22-76). */
    public void setTaskState(com.nexusai.application.agent.team.InProcessTeammateTaskState taskState) {
        this.taskState = taskState;
    }

    /** W8-01: 当前状态载体 (测试/诊断). */
    public com.nexusai.application.agent.team.InProcessTeammateTaskState taskState() {
        return taskState;
    }

    /** W8-02: 当前消息镜像 (测试/诊断). */
    public java.util.List<String> messages() {
        return java.util.List.copyOf(messages);
    }

    public AgentState getState() {
        return state;
    }

    /**
     * s17-P1-1: 状态转换 · WORK / IDLE / SHUTDOWN.
     */
    public void transitionTo(AgentState newState) {
        AgentState old = this.state;
        this.state = newState;
        log.info("[AutonomousAgentLoop] state {} → {} (agent={})",
            old, newState, agentId);
    }

    /**
     * s17-P1-5: 发送 idle_notification 到所有 agents · 对齐 CC inProcessRunner.ts:569-589.
     */
    public void sendIdleNotification() {
        if (agentId == null) return;
        AgentMessageBus.InboxMessage msg = new AgentMessageBus.InboxMessage(
            AgentMessageBus.InboxMessage.TYPE_IDLE_NOTIFICATION,
            "{\"agent\":\"" + agentId + "\",\"state\":\"idle\"}"
        );
        messageBus.broadcastToAll(msg);
        log.debug("[AutonomousAgentLoop] idle_notification sent by agent={}", agentId);
    }

    /**
     * W8-02: 等待结果 · 对齐 CC WaitResult (inProcessRunner.ts:662-680)。
     *
     * @param type    'shutdown_request' | 'new_message' | 'aborted'（CC :674-679）
     * @param from    发送方 agent 名（user/task-list/team-lead/peer）
     * @param text    消息文本（new_message 原始文本 或 shutdown_request 原始 JSON）
     * @param color   发送方颜色（可空）
     * @param summary UI 预览摘要（可空）
     */
    public record WaitResult(String type, String from, String text, String color, String summary) {
        public static final String TYPE_SHUTDOWN_REQUEST = "shutdown_request";
        public static final String TYPE_NEW_MESSAGE = "new_message";
        public static final String TYPE_ABORTED = "aborted";
    }

    /** W8-02: 注入用户消息到 pendingUserMessages 队列 · 对齐 CC InProcessTeammateTask.tsx:68-84
     *  injectUserMessageToTeammate (transcript 视图发送消息时调用). */
    public void injectUserMessage(String message) {
        if (message == null) return;
        synchronized (pendingUserMessages) {
            pendingUserMessages.addLast(message);
        }
        // GAP-5: 对齐 CC InProcessTeammateTask.tsx:79-82 —— injectUserMessageToTeammate 同时把消息
        //        append 到 task.messages（转录视图立即显示），否则 pendingUserMessages 消费后镜像缺失。
        appendMessage("[user] " + message);
        log.info("[AutonomousAgentLoop] 注入用户消息到队列 agent={} pendingSize={}", agentId, pendingUserMessages.size());
    }

    /** W8-02: 注册 idle 回调 · 对齐 CC task.onIdleCallbacks (types.ts:71, leader 经此等待 teammate 空闲). */
    public void addOnIdleCallback(Runnable callback) {
        if (callback != null) onIdleCallbacks.add(callback);
    }

    /**
     * 原子注册 idle 回调（仅当当前非 idle）· 对齐 CC teammate.ts:279-286 的 either-or：
     * {@code if (task.isIdle) onIdle() else 追加 onIdleCallbacks}。
     *
     * <p>Java 侧消除旧实现「addOnIdleCallback 后 check isIdle」的双触发竞态——teammate 在
     * 注册与检查之间转 idle 时，回调会被 {@link #transitionToIdle} 与手动补触发各执行一次
     * （remaining 双递减 → N≥2 时 waitForTeammatesToBecomeIdle 提前完成）。本方法与
     * {@link #transitionToIdle} 同锁（onIdleCallbacks monitor），使注册与置 idle 原子。
     *
     * @return true 已注册（非 idle，等待 {@link #transitionToIdle} 触发）；false 已 idle 未注册（调用方应立即触发）
     */
    public boolean addOnIdleCallbackIfNotIdle(Runnable callback) {
        if (callback == null) {
            return false;
        }
        synchronized (onIdleCallbacks) {
            if (isIdle) {
                return false;
            }
            onIdleCallbacks.add(callback);
            return true;
        }
    }

    /**
     * 是否 running 态的 in-process teammate 任务 · 对齐 CC teammate.ts:208/:223
     * {@code task.type === 'in_process_teammate' && task.status === 'running'}。
     *
     * <p>CC 经 AppState.tasks 过滤（type + status）；Java 侧 task 状态层由
     * {@link TaskFrameworkService} 的 {@link BackgroundTask} 承载，经 {@link #taskId} 反查。
     * 无 taskId / 未接线 taskFrameworkService（测试直构）→ false（非 running）。
     */
    public boolean isRunningInProcessTeammate() {
        if (taskId == null || taskFrameworkService == null) {
            return false;
        }
        BackgroundTask task = taskFrameworkService.getTask(taskId).orElse(null);
        return task != null
            && task.type() == TaskType.IN_PROCESS_TEAMMATE
            && task.status() == BackgroundTaskStatus.RUNNING;
    }

    /** W8-02: 当前 idle 回调数 (测试/诊断). */
    public int idleCallbackCount() {
        return onIdleCallbacks.size();
    }

    /** W8-02: 当前是否空闲 (CC task.isIdle). */
    public boolean isIdle() {
        return isIdle;
    }

    /** T-A: 测试/接线用 setter (isIdle) · 供 Teammate.hasWorkingInProcessTeammates /
     *  waitForTeammatesToBecomeIdle 的 working 分支测试构造 working 态（CC task.isIdle=false）。 */
    public void setIdle(boolean idle) {
        this.isIdle = idle;
    }

    /**
     * W8-02: 等待下一个 prompt 或 shutdown · 对齐 CC waitForNextPromptOrShutdown
     * (inProcessRunner.ts:689-868)。
     *
     * <p>轮询优先级（CC :705-845，grep 自验）：
     * <ol>
     *   <li><b>pendingUserMessages 内存优先</b>（:705-739）——每轮最先检查（pollCount 0 时不 sleep
     *       直接查），transcript 注入的用户消息立即消费</li>
     *   <li><b>文件 mailbox 轮询</b>（:763-845）——shutdown_request &gt; team-lead &gt; FIFO
     *       （AgentMessageBus#pollFileMailbox :186，W8-01 已对齐优先级）</li>
     *   <li><b>task-list 认领</b>（:853-861）——无 mailbox 消息时 tryClaimNextTask</li>
     *   <li><b>abort → 'aborted'</b>（:867）</li>
     * </ol>
     *
     * @return WaitResult; 'aborted' 表示 abort 退出
     */
    public WaitResult waitForNextPromptOrShutdown() {
        int pollCount = 0;
        while (!isAborted()) {
            // 1. pendingUserMessages 内存优先（CC :705-739，pollCount 0 立即查）
            String pending;
            synchronized (pendingUserMessages) {
                pending = pendingUserMessages.pollFirst();
            }
            if (pending != null) {
                log.debug("[AutonomousAgentLoop] 找到 pending user message agent={} poll#{}", agentId, pollCount);
                return new WaitResult(WaitResult.TYPE_NEW_MESSAGE, "user", pending, null, null);
            }

            // 2. poll 间隔（首轮跳过, 立即查 · CC :742-745）
            if (pollCount > 0) {
                sleepQuietly(POLL_INTERVAL_MS);
            }
            pollCount++;

            // 3. abort 检查（CC :748-753）
            if (isAborted()) {
                log.debug("[AutonomousAgentLoop] 等待中 abort agent={} poll#{}", agentId, pollCount);
                return new WaitResult(WaitResult.TYPE_ABORTED, null, null, null, null);
            }

            // 4. 文件 mailbox 轮询（shutdown > team-lead > FIFO, 已标已读）
            if (agentName != null && teamName != null) {
                Optional<AgentMessageBus.MailboxPollResult> polled =
                    AgentMessageBus.pollFileMailbox(agentName, teamName);
                if (polled.isPresent()) {
                    AgentMessageBus.MailboxPollResult m = polled.get();
                    log.debug("[AutonomousAgentLoop] 收到消息 from={} type={} poll#{}", m.from(), m.type(), pollCount);
                    return new WaitResult(m.type(), m.from(), m.text(), m.color(), m.summary());
                }
            }

            // 5. task-list 认领（CC :853-861）
            Optional<String> taskPrompt = tryAutoClaimAndExecute();
            if (taskPrompt.isPresent()) {
                return new WaitResult(WaitResult.TYPE_NEW_MESSAGE, "task-list", taskPrompt.get(), null, null);
            }
        }
        log.debug("[AutonomousAgentLoop] 退出 poll 循环 agent={} abort={}", agentId, isAborted());
        return new WaitResult(WaitResult.TYPE_ABORTED, null, null, null, null);
    }

    /** W8-02: 是否已 abort · 测试/无 abortController 时返回 false. */
    public boolean isAborted() {
        return abortController != null && abortController.aborted().get();
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * W8-02: shutdown_request → 格式化为 teammate-message XML 交模型决策 · 对齐 CC
     * inProcessRunner.ts:1364-1381（shutdown_request 不自动批准, 作为 prompt 交给模型,
     * 模型用 approveShutdown/rejectShutdown 工具决策）+ formatAsTeammateMessage :457-466.
     *
     * @param from            shutdown_request.from（CC :1371 from || 'team-lead'）
     * @param originalMessage shutdown_request 原始 JSON 文本（CC :1372 originalMessage）
     * @return teammate-message XML 包裹的 prompt
     */
    public String formatShutdownRequestAsPrompt(String from, String originalMessage) {
        String sender = from != null && !from.isBlank() ? from : SwarmConstants.TEAM_LEAD_NAME;
        return formatAsTeammateMessage(sender, originalMessage);
    }

    /**
     * W8-02: 轮末置 isIdle + 调用 onIdleCallbacks + 清空回调 · 对齐 CC inProcessRunner.ts:1318-1326
     * （isIdle:true + onIdleCallbacks.forEach + 清空, M-7 唤醒 leader 的 waitForIdle）.
     */
    public void transitionToIdle() {
        // 与 addOnIdleCallbackIfNotIdle 同锁（onIdleCallbacks monitor）：使「注册回调」与
        // 「置 idle + 快照清空」原子，消除 waitForTeammatesToBecomeIdle 旧实现
        // addOnIdleCallback→check isIdle 的双触发竞态（teammate 在注册与检查之间转 idle 时
        // 回调被 transitionToIdle 与手动补触发各执行一次，remaining 双递减 → N≥2 提前完成）。
        // 先置 isIdle=true 再快照清空（CC :1322-1323 forEach 后置 isIdle:true；Java 侧置位先行
        // 保证 addOnIdleCallbackIfNotIdle 观察到终态后不再注册，避免回调注册后永不触发）。
        List<Runnable> callbacks;
        synchronized (onIdleCallbacks) {
            isIdle = true;
            callbacks = new ArrayList<>(onIdleCallbacks);
            onIdleCallbacks.clear();
        }
        for (Runnable cb : callbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                log.warn("[AutonomousAgentLoop] idle 回调执行失败: {}", e.getMessage());
            }
        }
        log.debug("[AutonomousAgentLoop] transitionToIdle agent={} callbacks={}", agentId, callbacks.size());
    }

    /**
     * W8-02: 发送 idle 通知到 team-lead 文件 mailbox · 对齐 CC inProcessRunner.ts:569-589
     * sendIdleNotification → createIdleNotification (teammateMailbox.ts:410-430) →
     * writeToMailbox(TEAM_LEAD_NAME, ...) (runner:583-588)。
     *
     * @param idleReason      'available' | 'interrupted' | 'failed'（runner:1339/:1521, 可空）
     * @param summary         本轮最后 DM 摘要（runner:1340 getLastPeerDmSummary, 可空）
     * @param completedTaskId 完成任务 ID（teammateMailbox.ts:402, 可空）
     * @param completedStatus 'resolved' | 'blocked' | 'failed'（runner:1522, 可空）
     * @param failureReason   失败原因（runner:1523, 可空）
     */
    public void sendIdleNotification(String idleReason, String summary, String completedTaskId,
            String completedStatus, String failureReason) {
        if (agentName == null || teamName == null) return;
        // T-B P-8: 消息体统一走 TeammateMailbox.createIdleNotification（含 completedTaskId），
        // 不再内联 Map 构造（原实现缺 completedTaskId 字段）。
        TeammateMailbox.IdleNotificationMessage notification = TeammateMailbox.createIdleNotification(
            agentName, idleReason, summary, completedTaskId, completedStatus, failureReason);
        String json = TeammateMailbox.toCompactJson(notification);
        TeammateMailbox.writeToMailbox(SwarmConstants.TEAM_LEAD_NAME,
            TeammateMailbox.TeammateMessage.of(agentName, json, TeammateMailbox.isoNow(), null),
            teamName);
        // [Batch2 T1 前置] teammate 转 idle → config.json isActive=false（对齐 CC teammateInit.ts:105
        //   Stop hook {@code void setMemberActive(teamName, agentName, false)}）。否则 appendTeamMember
        //   写入的成员恒无 isActive 字段（= 恒活跃），TeamDelete 活跃守卫/轮询永远被拦截。
        //   fire-and-forget：失败仅日志，不阻断 idle 通知。
        if (teamHelpers != null) {
            try {
                teamHelpers.setMemberActive(teamName, agentName, false);
            } catch (Exception e) {
                log.warn("[AutonomousAgentLoop] setMemberActive(false) 失败 agent={}: {}",
                    agentName, e.getMessage());
            }
        }
        log.info("[AutonomousAgentLoop] 发送 idle_notification 到 team-lead agent={} idleReason={} completedTaskId={}",
            agentName, idleReason, completedTaskId);
    }

    /**
     * W8-02: 消息格式化为 teammate-message XML · 对齐 CC inProcessRunner.ts:457-466
     * formatAsTeammateMessage（teammate-message tag, teammateMailbox.ts 消费侧可识别）。
     */
    public static String formatAsTeammateMessage(String from, String content, String color, String summary) {
        String colorAttr = color != null && !color.isBlank() ? " color=\"" + color + "\"" : "";
        String summaryAttr = summary != null && !summary.isBlank() ? " summary=\"" + summary + "\"" : "";
        return "<teammate-message teammate_id=\"" + from + "\"" + colorAttr + summaryAttr + ">\n"
            + content + "\n</teammate-message>";
    }

    /** W8-02: 2 参便捷重载 · 无 color/summary. */
    public static String formatAsTeammateMessage(String from, String content) {
        return formatAsTeammateMessage(from, content, null, null);
    }

    /**
     * s17-P1-2: idle_poll · 轮询 inbox + task board · 对齐 CC utils/swarm/inProcessRunner.ts:697-748 POLL_INTERVAL_MS.
     *
     * <p>教学版简化: 单一方法模拟 CC idle_poll (5s inbox poll + 任务板扫描).
     *
     * @param timeoutMs 最大轮询持续时间 (60s 后强制 break)
     * @return 第一个非空消息 (inbox > task > null); null 表示 timeout
     */
    public Optional<AgentMessageBus.InboxMessage> idlePoll(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.min(timeoutMs, IDLE_POLL_MAX_MS);
        log.info("[AutonomousAgentLoop] idle_poll start agent={} timeout={}ms",
            agentId, timeoutMs);

        while (System.currentTimeMillis() < deadline && !shutdownRequested) {
            // 1. inbox poll (CC 优先)
            if (agentId != null) {
                Optional<AgentMessageBus.InboxMessage> msg = messageBus.receiveFromAgent(agentId);
                if (msg.isPresent()) {
                    log.info("[AutonomousAgentLoop] idle_poll received inbox msg: type={}",
                        msg.get().type());
                    return msg;
                }
            }

            // (task board scan 已按 D-TS-2 移除: CC 对应物 useTaskListWatcher.ts 是 React UI hook,
            //  Java 后端无 auto-claim 链, 原引用恒 null 删除即等价)

            // 2. sleep 5s
            try {
                Thread.sleep(IDLE_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[AutonomousAgentLoop] idle_poll interrupted");
                return Optional.empty();
            }
        }
        log.debug("[AutonomousAgentLoop] idle_poll timeout agent={} shutdownRequested={}",
            agentId, shutdownRequested);
        return Optional.empty();
    }

    /**
     * s17-P1-4: auto-claim + execute · 对齐 CC inProcessRunner.ts:624-657 tryClaimNextTask.
     *
     * <p>W8-01 接线: 原恒返回 false (W8-J-01), 现真实认领——listTasks 找 available
     * (pending && !owner && blockedBy 全解) → claimTask → updateTask in_progress →
     * formatTaskAsPrompt (CC :624-657 + :595-605 findAvailableTask + :607-615 formatTaskAsPrompt).
     *
     * @return 认领任务的 prompt (CC 返回 string | undefined); 无可用任务返回 empty
     */
    public Optional<String> tryAutoClaimAndExecute() {
        // [team-cc-align fixPlan1] 认领列表目录 = getTaskListId() 动态解析（优先级 2
        //   teammateCtx.teamName，runner 线程已包 runWithTeammateContext）——与 leader 建任务目录
        //   （getTaskListId 同源）一致。原用 taskListId 字段（=parentSessionId，leader 会话）导致
        //   成员认领读 {tasks}/{parentSessionId} 而 leader 任务在 {tasks}/{teamName} → 认领不到
        //   （对齐 CC inProcessRunner.ts:854 idle 轮询 taskListId = teammateCtx.teamName）。
        String effectiveListId = TaskService.getTaskListId();
        if (effectiveListId == null || agentName == null || taskService == null) {
            log.debug("[AutonomousAgentLoop] tryAutoClaimAndExecute 缺 taskListId/agentName/taskService, 跳过");
            return Optional.empty();
        }
        try {
            List<Task> tasks = taskService.listTasks(effectiveListId);
            Optional<Task> available = findAvailableTask(tasks);
            if (available.isEmpty()) {
                return Optional.empty();
            }
            Task candidate = available.get();
            ClaimTaskResult result = taskService.claimTask(effectiveListId, candidate.id(), agentName);
            if (!(result instanceof ClaimTaskResult.Success)) {
                log.debug("[AutonomousAgentLoop] 认领任务 #{} 失败: {}", candidate.id(), result);
                return Optional.empty();
            }
            // CC :630-633 认领后置 in_progress (UI 立即反映)
            taskService.updateTask(effectiveListId, candidate.id(), Map.of("status", Task.TaskStatus.IN_PROGRESS));
            log.info("[AutonomousAgentLoop] 认领任务 #{}: {}", candidate.id(), candidate.subject());
            return Optional.of(formatTaskAsPrompt(candidate));
        } catch (Exception e) {
            log.debug("[AutonomousAgentLoop] 任务列表检查失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 找可认领任务 · 对齐 CC inProcessRunner.ts:595-605 findAvailableTask:
     * pending && !owner && blockedBy 所有未完成任务全部完成 (blockedBy id 不在 unresolved 集合).
     */
    static Optional<Task> findAvailableTask(List<Task> tasks) {
        Set<String> unresolved = new HashSet<>();
        for (Task t : tasks) {
            if (t.status() != Task.TaskStatus.COMPLETED) {
                unresolved.add(t.id());
            }
        }
        for (Task task : tasks) {
            if (task.status() != Task.TaskStatus.PENDING) continue;
            if (task.owner() != null && !task.owner().isBlank()) continue;
            boolean blocked = task.blockedBy().stream().anyMatch(unresolved::contains);
            if (!blocked) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /**
     * 格式化认领任务为 prompt · 对齐 CC inProcessRunner.ts:607-615 formatTaskAsPrompt.
     */
    static String formatTaskAsPrompt(Task task) {
        String prompt = "Complete all open tasks. Start with task #" + task.id() + ": \n\n " + task.subject();
        if (task.description() != null && !task.description().isBlank()) {
            prompt += "\n\n" + task.description();
        }
        return prompt;
    }

    /**
     * W8-01: 终端转换 completed · 对齐 CC inProcessRunner.ts:1419-1461。
     *
     * <p>alreadyTerminal 守卫 (:1428): 非 running 直接 no-op (kill 已置 killed 时
     * 不得翻转为 completed)。转换置 notified:true + endTime + 清 runtime 字段 →
     * evictTaskOutput + evictTerminalTask + emitTaskTerminatedSdk('completed') (仅非 alreadyTerminal)。
     *
     * @return true 实际转换; false 已是终态 (no-op)
     */
    public boolean complete() {
        return transitionToTerminal(BackgroundTaskStatus.COMPLETED, "completed", null);
    }

    /**
     * W8-01: 终端转换 failed · 对齐 CC inProcessRunner.ts:1465-1533。
     *
     * <p>W8-02 REWORK（GAP-2）：error 落盘（taskState.error + BackgroundTask 描述，CC :1490）
     * + 置 isIdle（CC :1491）+ 发 failed idle 通知（CC :1516-1525 sendIdleNotification
     * idleReason:'failed' / completedStatus:'failed' / failureReason —— 无条件发送，对齐 CC）。
     *
     * @param error 失败原因 (CC :1490 error: errorMessage)
     * @return true 实际转换; false 已是终态 (no-op)
     */
    public boolean fail(String error) {
        boolean advanced = transitionToTerminal(BackgroundTaskStatus.FAILED, "failed", error);
        // error 落盘到状态载体 + isIdle（仅在 running→failed 实际转换时；CC :1490-1491）
        if (advanced) {
            if (taskState != null) {
                taskState = taskState.withError(error).withIsIdle(true);
            }
            isIdle = true;
        }
        // failed idle 通知（CC :1516-1525，无条件——已 terminal 时也发，对齐 CC failed 路径）
        sendIdleNotification("failed", null, null, "failed", error);
        return advanced;
    }

    /**
     * W8-01: kill 链 · 对齐 CC spawnInProcess.ts:227-328 killInProcessTeammate。
     *
     * <p>守卫 (:239-247): task 存在 && type==='in_process_teammate' && status==='running'
     * 否则 no-op。abortController.abort() (:256) → 状态 killed + notified:true + endTime
     * (:280-296) → evictTaskOutput + emitTaskTerminatedSdk('stopped') + 3s 后 evictTerminalTask
     * (:306-319, STOPPED_DISPLAY_MS)。notified:true 预置抑制 XML 通知 (:308)。
     *
     * <p>W8-02 REWORK：
     * <ul>
     *   <li><b>GAP-3 去双 evict</b>：kill 仅延迟 3s evict（CC :316-319）；transitionToTerminal
     *       对 KILLED 不再 eager evict（对照 completed/failed 才 eager evict :1453/:1506）。</li>
     *   <li><b>GAP-4 removeMemberByAgentId</b>：kill 后从 team 文件移除成员（CC :302-304），
     *       否则 Team 文件成员残留。</li>
     * </ul>
     *
     * @return true 实际 kill; false task 不存在 / 已非 running
     */
    public boolean kill() {
        if (taskId == null) return false;
        BackgroundTask task = taskFrameworkService != null
            ? taskFrameworkService.getTask(taskId).orElse(null) : null;
        if (task == null) {
            log.debug("[AutonomousAgentLoop] kill: task {} 不存在, no-op", taskId);
            return false;
        }
        if (task.type() != TaskType.IN_PROCESS_TEAMMATE || task.status() != BackgroundTaskStatus.RUNNING) {
            log.debug("[AutonomousAgentLoop] kill: task {} type/status 非 running, no-op", taskId);
            return false;
        }
        if (abortController != null) {
            abortController.abort();
        }
        boolean advanced = transitionToTerminal(BackgroundTaskStatus.KILLED, "stopped", null);
        if (advanced && taskFrameworkService != null) {
            // GAP-4: 从 team 文件移除成员（CC :302-304 removeMemberByAgentId）
            if (teamHelpers != null && teamName != null && agentId != null) {
                try {
                    boolean removed = teamHelpers.removeMemberByAgentId(teamName, agentId);
                    log.info("[AutonomousAgentLoop] kill 移除 team 成员 agent={} removed={}", agentId, removed);
                    if (removed) {
                        // [team-panel-backend-bugfix2] kill 统一发 member_left（所有 kill 路径一致，
                        //   REST killMember 端点不再重复 publish）+ team_context.teammates 同步
                        //   （对齐 CC spawnInProcess.ts:267-275 kill 时按 agentId 移除 teammates）。
                        if (teamStatusPublisher != null) {
                            teamStatusPublisher.publish(teamName, "member_left");
                        }
                        teamHelpers.syncTeamContextTeammates(teamName);
                    }
                } catch (Exception e) {
                    log.warn("[AutonomousAgentLoop] kill 移除 team 成员失败 agent={}: {}", agentId, e.getMessage());
                }
            }
            // CC :318-319 3s 后 evictTerminalTask (STOPPED_DISPLAY_MS)
            String evictTaskId = taskId;
            evictScheduler.schedule(
                () -> taskFrameworkService.evictTerminalTask(evictTaskId),
                STOPPED_DISPLAY_MS, TimeUnit.MILLISECONDS);
        }
        return advanced;
    }

    /**
     * W8-01: 终端转换共用实现 · alreadyTerminal 守卫 + notified/endTime/evict/SDK 链。
     *
     * <p>守卫语义 (CC :1428/:1479): status !== 'running' → alreadyTerminal = true,
     * 不覆盖、不发 SDK (防 killed→completed 翻转 + 双发 bookend)。
     * 推进语义: notified:true + endTime + 清 runtime 字段 (abortController) →
     * {@link TaskFrameworkService#updateTaskState} → {@link SdkEventQueue#emitTaskTerminatedSdk}
     * ('completed'/'failed'/'stopped') → {@link TaskFrameworkService#evictTerminalTask}。
     *
     * <p>W8-02 REWORK（对齐 CC 实际源码，grep 自验）：
     * <ul>
     *   <li><b>GAP-1 onIdleCallbacks 唤醒</b>：终端转换必须调用并清空 idle 回调（CC
     *       completed:1433 / failed:1484 / kill:265 均 {@code onIdleCallbacks?.forEach}）——
     *       否则 leader 经 waitForIdle 注册回调后 teammate 被 kill/complete/fail 时永久阻塞。</li>
     *   <li><b>GAP-2 error 落盘</b>：failed 时 error 追加到 BackgroundTask 描述（持久层暴露
     *       失败现场，CC failed:1490 error: errorMessage）；taskState.error 落盘在 {@link #fail}。</li>
     *   <li><b>GAP-3 evict 语义</b>：completed/failed eager evict（CC :1453/:1506）；<b>killed 仅
     *       延迟 evict</b>（CC spawnInProcess.ts:316-319，STOPPED_DISPLAY_MS 展示窗口）——立即
     *       evict 使窗口失效、延迟 evict 变 no-op。</li>
     * </ul>
     *
     * @param status      目标终态
     * @param sdkStatus   SDK status 串 (completed/failed/stopped, CC emitTaskTerminatedSdk)
     * @param error       失败原因 (failed 时非 null, 写入 description 保留现场)
     * @return true 实际推进; false 已是终态
     */
    private boolean transitionToTerminal(BackgroundTaskStatus status, String sdkStatus,
                                         String error) {
        if (taskId == null || taskFrameworkService == null) return false;
        BackgroundTask current = taskFrameworkService.getTask(taskId).orElse(null);
        if (current == null) {
            log.debug("[AutonomousAgentLoop] {}: task {} 不存在, no-op", sdkStatus, taskId);
            return false;
        }
        // alreadyTerminal 守卫: 非 running (已 killed/completed/failed) 不覆盖
        if (current.status() != BackgroundTaskStatus.RUNNING || terminalTasks.contains(taskId)) {
            log.debug("[AutonomousAgentLoop] {}: task {} 已非 running (status={}), alreadyTerminal no-op",
                sdkStatus, taskId, current.status().getStatusString());
            return false;
        }
        // GAP-1: 唤醒所有 idle 等待者（CC completed:1433/failed:1484/kill:265 onIdleCallbacks.forEach）
        List<Runnable> callbacks = new ArrayList<>(onIdleCallbacks);
        onIdleCallbacks.clear();
        for (Runnable cb : callbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                log.warn("[AutonomousAgentLoop] 终端转换 idle 回调失败: {}", e.getMessage());
            }
        }
        long now = System.currentTimeMillis();
        // GAP-2: failed 时 error 落盘到描述（持久层暴露失败现场）
        String terminalDescription = current.description();
        if (error != null && !error.isBlank() && status == BackgroundTaskStatus.FAILED) {
            terminalDescription = terminalDescription + "\nerror: " + error;
        }
        BackgroundTask terminal = new BackgroundTask(
            current.id(), current.type(), status, terminalDescription,
            current.toolUseId(), current.startTime(), now, current.totalPausedMs(),
            current.outputFile(), current.outputOffset(), true,
            current.agentId(), current.isBackgrounded());
        taskFrameworkService.updateTaskState(taskId, terminal);
        terminalTasks.add(taskId);
        log.info("[AutonomousAgentLoop] {}: task {} → {} (endTime={}, notified=true)",
            sdkStatus, taskId, status.getStatusString(), now);

        if (sdkEventQueue != null) {
            String summary = agentName != null ? agentName : current.description();
            sdkEventQueue.emitTaskTerminatedSdk(taskId, sdkStatus,
                new SdkEventQueue.TaskTerminatedOpts(current.toolUseId(), summary,
                    current.outputFile(), null));
        }
        // W8-04 完成通知链: 终端转换产出 task_status attachment 进入出站消息列表 → 折叠链消费
        // （对齐 CC inProcessRunner.ts:1419-1461 completed 后 task_status attachment 进 transcript +
        //     spawnInProcess.ts:306-319 终端 SDK bookend；Java 侧 sink 追加，GET /messages 折叠）。
        if (outboundSink != null) {
            try {
                String displayName = agentName != null ? agentName : current.description();
                outboundSink.accept(com.nexusai.application.agent.team.TeammateMessageFoldingChain
                    .teammateTaskStatusAttachment(taskId, displayName, sdkStatus, taskListId));
            } catch (Exception e) {
                log.warn("[AutonomousAgentLoop] teammate task_status attachment 出站失败: {}", e.getMessage());
            }
        }
        // GAP-3: killed 仅延迟 evict（kill() 已调度 3s）；completed/failed eager evict（CC :1453/:1506）
        if (status != BackgroundTaskStatus.KILLED) {
            taskFrameworkService.evictTerminalTask(taskId);
        }
        return true;
    }

    /**
     * s17-P1-1: shutdown 信号 · 对齐 CC inProcessRunner.ts:850-868.
     */
    public void requestShutdown() {
        shutdownRequested = true;
        transitionTo(AgentState.SHUTDOWN);
        log.info("[AutonomousAgentLoop] shutdown requested agent={}", agentId);
    }

    public boolean isShutdownRequested() {
        return shutdownRequested;
    }

    /**
     * s17-P1-2 终极: 主循环 driver · 对齐 CC inProcessRunner.ts:850-868.
     *
     * <p>把已有 P1-1/2/4/5 拼接成 WORK → IDLE → SHUTDOWN 编排:
     * <ol>
     *   <li>启动时若非 SHUTDOWN, 重置为 IDLE</li>
     *   <li>每轮 idle_poll (60s/cycle via IDLE_POLL_MAX_MS), 收到消息后 dispatch</li>
     *   <li>{@code InboxMessage.TYPE_SHUTDOWN} 消息 → 调 {@link #requestShutdown()}, 退出循环</li>
     *   <li>{@code InboxMessage.TYPE_TASK_ASSIGNED} → {@link #transitionTo(AgentState) WORK}
     *       → {@link #tryAutoClaimAndExecute()} → {@code IDLE}</li>
     *   <li>其它 (user_message / idle_notification) → 忽略, 留在 IDLE</li>
     *   <li>外部 {@link #requestShutdown()} 在每次 {@link #idlePoll(long)} 顶部检查, 立即退出</li>
     * </ol>
     *
     * <p>阻塞至收到 shutdown 信号. 教学版不直接执行子任务 (P1-3 Dream fork 留续,
     * 真实 worker 调度交给上层).
     */
    public void run() {
        log.info("[AutonomousAgentLoop] run() start agent={}", agentId);
        if (state != AgentState.SHUTDOWN) {
            transitionTo(AgentState.IDLE);
        }
        while (!shutdownRequested) {
            // IDLE phase: poll inbox + try claim (60s max per cycle)
            Optional<AgentMessageBus.InboxMessage> msg = idlePoll(IDLE_POLL_MAX_MS);
            if (shutdownRequested) break;
            if (msg.isEmpty()) {
                // idle cycle timeout, no new work → continue IDLE loop
                continue;
            }
            AgentMessageBus.InboxMessage received = msg.get();
            log.info("[AutonomousAgentLoop] dispatch msg type={} payload={}",
                received.type(), received.payload());
            if (AgentMessageBus.InboxMessage.TYPE_SHUTDOWN.equals(received.type())) {
                requestShutdown();
                break;
            }
            if (AgentMessageBus.InboxMessage.TYPE_TASK_ASSIGNED.equals(received.type())) {
                transitionTo(AgentState.WORK);
                tryAutoClaimAndExecute();
                transitionTo(AgentState.IDLE);
            }
            // 其它类型 (user_message / idle_notification) → 忽略, 留 IDLE
        }
        if (state != AgentState.SHUTDOWN) {
            transitionTo(AgentState.SHUTDOWN);
        }
        log.info("[AutonomousAgentLoop] run() exit agent={}", agentId);
    }

    /**
     * W8-02: 生产化 teammate 运行循环 · 对齐 CC runInProcessTeammate (inProcessRunner.ts:1048-1417)。
     *
     * <p>循环结构（CC :1048 while(!abort && !shouldExit)）：
     * <ol>
     *   <li>每轮创建 <b>currentWorkAbortController</b>（:1056, Escape 只停本轮不杀队友）</li>
     *   <li>{@link #runOneTurn} 委托 SubagentExecutor.executeStreaming（runAgent 等价）, 逐消息更新
     *       messages 镜像（cap 50）</li>
     *   <li>轮末 {@link #transitionToIdle}（isIdle:true + onIdleCallbacks 唤醒 + 清空, :1318-1326）</li>
     *   <li>非重复 idle 发 {@link #sendIdleNotification}（:1333-1342）</li>
     *   <li>{@link #waitForNextPromptOrShutdown} 等待 → 派发 shutdown_request 交模型 / new_message /
     *       aborted（:1363-1416）</li>
     *   <li>退出后 {@link #complete()} 终端转换 + alreadyTerminal 守卫（:1419-1461）</li>
     * </ol>
     *
     * @param initialPrompt 初始 prompt（CC :1006 经 formatAsTeammateMessage('team-lead',...) 包裹）
     */
    public void runTeammateLoop(String initialPrompt) {
        if (agentId == null) {
            log.warn("[AutonomousAgentLoop] runTeammateLoop 缺 agentId, 拒绝启动");
            return;
        }
        transitionTo(AgentState.WORK);
        String currentPrompt = formatAsTeammateMessage(SwarmConstants.TEAM_LEAD_NAME, initialPrompt);
        // GAP-5: 初始 prompt 进消息镜像（CC :1023-1033 appendTeammateMessage）
        appendMessage(currentPrompt);
        boolean shouldExit = false;

        // 启动即认领任务（CC :1019, UI 立显活动）——返回值丢弃, 后续任务由 idle 循环认领（:853-861）
        tryAutoClaimAndExecute();

        try {
            while (!isAborted() && !shouldExit) {
                log.debug("[AutonomousAgentLoop] teammate 处理 prompt: {} (agent={})",
                    currentPrompt.substring(0, Math.min(50, currentPrompt.length())), agentId);

                // 本轮 work abortController（CC :1056, Escape 只停本轮不杀队友）
                currentWorkAbortController = AbortControllerFactory.create();
                // GAP-6: 存入状态载体供 UI 追踪/中止本轮（CC :1059-1063 currentWorkAbortController）
                if (taskState != null) {
                    taskState = taskState.withCurrentWorkAbortController(currentWorkAbortController);
                }

                // 标记 running + 非 idle（CC :1163-1167）
                transitionTo(AgentState.WORK);
                isIdle = false;
                // [Batch2 T1 前置] turn 开始 → config.json isActive=true（对齐 CC REPL.tsx:3634
                //   setMemberActive(teamName, agentName, true)）。与 sendIdleNotification 的 false 成对，
                //   使 TeamDelete 活跃守卫语义正确（工作→active，idle→inactive）。fire-and-forget。
                if (teamHelpers != null) {
                    try {
                        teamHelpers.setMemberActive(teamName, agentName, true);
                    } catch (Exception e) {
                        log.warn("[AutonomousAgentLoop] setMemberActive(true) 失败 agent={}: {}",
                            agentName, e.getMessage());
                    }
                }

                // T-C: auto-compact 检查（CC :1071-1126，先于 runAgent 读 contentReplacementState）
                maybeAutoCompact();

                // 执行本轮（runAgent 等价 · GAP-6 work abort 透传 executeStreaming）
                boolean workWasAborted = runOneTurn(currentPrompt, currentWorkAbortController);

                // 清空本轮 work 控制器（CC :1280-1284）
                currentWorkAbortController = null;
                if (taskState != null) {
                    taskState = taskState.withCurrentWorkAbortController(null);
                }

                // 生命周期 abort 检查（CC :1287-1289）
                if (isAborted()) {
                    break;
                }

                // work abort（Escape）→ 加中断消息到消息镜像（CC :1298-1308 createAssistantAPIErrorMessage）
                if (workWasAborted) {
                    appendMessage("[interrupt] 本轮工作被中断（Escape），返回空闲等待下一条指令");
                }

                // 轮末置 idle + 回调唤醒（CC :1318-1326, M-7）
                boolean wasAlreadyIdle = isIdle;
                transitionToIdle();

                // 非重复 idle 才发 idle_notification（CC :1333-1342 idleReason: workWasAborted ? 'interrupted' : 'available'）
                if (!wasAlreadyIdle) {
                    sendIdleNotification(workWasAborted ? "interrupted" : "available", null, null, null, null);
                } else {
                    log.debug("[AutonomousAgentLoop] 跳过重复 idle 通知 agent={}", agentId);
                }

                // 等待下一条消息或 shutdown（CC :1354-1361）
                WaitResult waitResult = waitForNextPromptOrShutdown();
                if (WaitResult.TYPE_ABORTED.equals(waitResult.type())) {
                    log.debug("[AutonomousAgentLoop] 等待中 abort, 退出循环 agent={}", agentId);
                    shouldExit = true;
                } else {
                    // shutdown_request 交模型决策 / new_message 分派（CC :1363-1416）+ 镜像追加
                    String next = nextPromptForWaitResult(waitResult);
                    if (next != null) {
                        currentPrompt = next;
                    }
                }
            }

            // 终端转换 completed + alreadyTerminal 守卫（CC :1419-1461）
            complete();
            log.info("[AutonomousAgentLoop] teammate 运行循环退出 agent={}", agentId);
        } catch (Exception e) {
            // GAP-2: runOneTurn 执行异常 → failed 终端转换（对齐 CC runInProcessTeammate
            //        catch 块 :1465-1533）；abort 路径（kill）不翻 failed。
            if (isAborted()) {
                log.debug("[AutonomousAgentLoop] teammate 运行循环被 abort 中断 agent={}", agentId);
            } else {
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                log.warn("[AutonomousAgentLoop] teammate 运行循环异常 agent={}: {}", agentId, errorMessage);
                fail(errorMessage);
            }
        }
    }

    /**
     * W8-02 REWORK: 派发 waitResult → 下一轮 prompt · 对齐 CC inProcessRunner.ts:1363-1416。
     *
     * <p>GAP-5: shutdown_request（:1376-1380 appendTeammateMessage）与非 user 新消息
     * （:1402-1406 appendTeammateMessage）追加 messages 镜像；user 消息纯文本（已由
     * {@link #injectUserMessage} 追加，:1390-1391/:1400-1401）。
     *
     * @param waitResult waitForNextPromptOrShutdown 返回（aborted 类型不调用本方法）
     * @return 下一轮 prompt；未知类型返回 null
     */
    public String nextPromptForWaitResult(WaitResult waitResult) {
        if (waitResult == null) {
            return null;
        }
        switch (waitResult.type()) {
            case WaitResult.TYPE_SHUTDOWN_REQUEST -> {
                // shutdown_request 交模型决策（CC :1364-1381, S-2 不再自动批准）+ 镜像（:1376-1380）
                log.debug("[AutonomousAgentLoop] 收到 shutdown_request, 交模型决策 agent={}", agentId);
                String next = formatShutdownRequestAsPrompt(waitResult.from(), waitResult.text());
                appendMessage(next);
                return next;
            }
            case WaitResult.TYPE_NEW_MESSAGE -> {
                if ("user".equals(waitResult.from())) {
                    // user 消息纯文本, 不进镜像（已由 injectUserMessage 追加, CC :1390-1391/:1400-1401）
                    return waitResult.text();
                }
                // 其他 teammate/team-lead XML 包裹 + 镜像（CC :1383-1406）
                String next = formatAsTeammateMessage(waitResult.from(), waitResult.text(),
                    waitResult.color(), waitResult.summary());
                appendMessage(next);
                return next;
            }
            default -> { return null; }
        }
    }

    /**
     * W8-02 REWORK (GAP-6): 执行一轮 · 对齐 CC runAgent 等价委托 (inProcessRunner.ts:1175-1273)。
     *
     * <p>委托 {@link com.nexusai.application.agent.tool.impl.SubagentExecutor#executeStreaming}
     * （Java runAgent 等价入口, SubagentExecutor.java:807）; messageSink 逐消息追加到 messages 镜像
     * （cap 50, 对齐 :1264-1269 appendCappedMessage）。未注入 subagentExecutor 时仅记录日志
     * （测试/教学回退, 不抛错）。
     *
     * <p>GAP-6（CC Escape「只停本轮不杀队友」语义）：本轮 {@code workAbort}（inProcessRunner.ts:1056
     * currentWorkAbortController）经 {@link #bridgeWorkAbortController} 桥接到
     * {@code tool.AbortController} 透传 executeStreaming（CC :1197
     * {@code override: { abortController: currentWorkAbortController }}）——Escape 中止 work 控制器
     * 只停当前轮执行器（query loop state.cancel），生命周期 abortController 不受影响 → 队友存活进 idle。
     *
     * @param prompt   本轮 prompt
     * @param workAbort 本轮 work abortController（CC :1056；可空——非 loop 直接调用时无 per-turn 控制器）
     * @return true 表示本轮被 work abort 中断（CC :1213-1219 workWasAborted）；false = 正常完成 / 生命周期 abort
     */
    public boolean runOneTurn(String prompt, AbortControllerFactory.AbortControllerRef workAbort) {
        if (subagentExecutor == null) {
            log.debug("[AutonomousAgentLoop] subagentExecutor 未注入, 本轮仅记录 (agent={})", agentId);
            appendMessage("[stub] " + prompt);
            return false;
        }
        // GAP-6: AbortControllerRef → tool.AbortController 桥（CC :1197 override.abortController）
        com.nexusai.application.agent.tool.AbortController bridge = bridgeWorkAbortController(workAbort);
        // T-C: per-teammate contentReplacementState 线程化 · 对齐 CC inProcessRunner.ts:1202
        //   {@code contentReplacementState: teammateReplacementState} —— 经 ForkPathParams.contentReplacementState
        //   直带（SubagentExecutor:1489 forkParams != null ? contentReplacementState() : null →
        //   runSubagentQueryLoop → injectContentReplacementState 注入 query loop session state，跨 turn 复用同一实例）。
        //   null（feature off）→ forkParams=null（CC :1043-1045 undefined，loop 每轮 fresh create）。
        com.nexusai.application.agent.tool.impl.SubagentExecutor.ForkPathParams forkParams =
            contentReplacementState != null
                ? new com.nexusai.application.agent.tool.impl.SubagentExecutor.ForkPathParams(
                    null, null, "", null, null, null, null, contentReplacementState)
                : null;
        // GAP-2: 不再吞异常 —— 执行失败向上传播，runTeammateLoop catch → fail()
        //        （对齐 CC runInProcessTeammate try/catch :1465-1533 failed 终端转换）
        // Fix A（根因）: subagentType 必须恒 null（teammate 名如 'alice' 不得当 subagentType）——
        //   对齐 CC inProcessRunner.ts:975-1001 iterationAgentDefinition：teammate 用通用 agent
        //   definition（agentType 仅标签，不查注册表）。传 agentName → SubagentExecutor:1271
        //   effectiveType='alice' → resolveAgentDefinition:2301-2331 → null →
        //   AgentNotFoundException（SubagentExecutor:1286-1300）→ teammate 线程 failed（20:33 实证）。
        //   null → effectiveType=BuiltInAgents.GENERAL_PURPOSE（'general-purpose'，非 null）→
        //   GENERAL_PURPOSE_AGENT 未设 permissionMode（Optional.empty，AgentDefinition:169）→
        //   resolvePermissionMode:2438-2446 → DEFAULT（对齐 CC iterationAgentDefinition permissionMode:'default'）。
        com.nexusai.application.agent.tool.impl.SubagentExecutor.SubagentResult result =
            subagentExecutor.executeStreaming(
                prompt,
                null,
                model,
                forkParams,
                msg -> appendMessage(describeMessage(msg)),
                bridge
            );
        // CC :1204-1219: 生命周期 abort 优先（不归为本轮 work abort）；仅 work abort 时 workWasAborted=true
        return result != null && "aborted".equals(result.status()) && !isAborted();
    }

    /**
     * GAP-6: AbortControllerRef → tool.AbortController 单向桥 · 对齐 CC inProcessRunner.ts:1197
     * {@code override: { abortController: currentWorkAbortController }}。
     *
     * <p>infra {@link AbortControllerFactory.AbortControllerRef}（teammate 循环持有）与
     * {@link com.nexusai.application.agent.tool.AbortController}（SubagentExecutor query loop 消费）
     * 为两类：经 listener 转发 {@code ref.abort() → toolAbort.abort()}（ref 已 abort 时立即同步）。
     * 轮末 ref 与 bridge 一并丢弃（GC 回收，无 listener 泄漏——ref 持有 bridge 引用，同生共死）。
     */
    private com.nexusai.application.agent.tool.AbortController bridgeWorkAbortController(
            AbortControllerFactory.AbortControllerRef ref) {
        com.nexusai.application.agent.tool.AbortController toolAbort =
            new com.nexusai.application.agent.tool.AbortController();
        if (ref != null) {
            if (ref.aborted().get()) {
                toolAbort.abort();
            } else {
                ref.addListener(toolAbort::abort);
            }
        }
        return toolAbort;
    }

    /** W8-02: 消息镜像追加（cap 50, 对齐 CC appendCappedMessage types.ts:89-121）. */
    private void appendMessage(String text) {
        synchronized (messages) {
            messages.add(text);
            if (messages.size() > com.nexusai.application.agent.team.InProcessTeammateTypes.TEAMMATE_MESSAGES_UI_CAP) {
                messages.remove(0);
            }
        }
    }

    /**
     * T-C: auto-compact 触发 · 对齐 CC inProcessRunner.ts:1071-1126。
     *
     * <p>CC :1073-1076 每轮迭代前 {@code tokenCountWithEstimation(allMessages) >
     * getAutoCompactThreshold(mainLoopModel)} → 压缩。Java 侧 messages 镜像（cap 50）估 token
     * （Σ {@code Tokens.roughTokenCountEstimation}），超阈值时
     * reset {@link #contentReplacementState}（CC :1111-1113 createContentReplacementState，清 stale
     * Map 条目防长会话内存无限增长）。
     *
     * <p>W1-3 阈值动态计算：对齐 CC :1073-1076 语义——阈值不写死，按当前模型每轮动态算
     * （{@link CompactThresholdSystem#getAutoCompactThreshold(String)}，effectiveWindow − 13_000 buffer
     * + env override，autoCompact.ts:72-91）。{@link #compactThresholdSystem} 未注入（测试/未接线，
     * 无 Spring bean 上下文）时回落 {@link #autoCompactThresholdTokens} 兜底（含
     * {@link #setAutoCompactThresholdTokens} 测试覆盖场景），行为与旧实现一致。
     *
     * <p>contentReplacementState == null（feature off，CC :1043-1045 undefined 分支）→ no-op。
     * 注：完整 allMessages 全量历史累积（forkContextMessages 线程化）为跨模块后续项（见 T-C concerns），
     * 在此之前 messages 镜像 cap 50 使阈值在真实会话不可达——本方法 reset 语义已对齐，触发依赖后续全量历史。
     */
    void maybeAutoCompact() {
        if (contentReplacementState == null) {
            return;
        }
        int tokenCount = 0;
        synchronized (messages) {
            for (String m : messages) {
                tokenCount += com.nexusai.application.agent.compact.Tokens.roughTokenCountEstimation(m);
            }
        }
        // W1-3: 阈值按当前模型每轮动态计算（CC inProcessRunner.ts:1073-1076
        //   getAutoCompactThreshold(mainLoopModel)）；compactThresholdSystem 未注入时回落静态兜底。
        int threshold = compactThresholdSystem != null
            ? compactThresholdSystem.getAutoCompactThreshold(model)
            : autoCompactThresholdTokens;
        if (tokenCount > threshold) {
            contentReplacementState = ContentReplacementState.create();
            log.info("[AutonomousAgentLoop] auto-compact 触发 agent={} tokenCount={} 阈值={} 来源={}：已 reset contentReplacementState（清 stale Map 条目）",
                agentId, tokenCount, threshold, thresholdSource());
        } else if (log.isDebugEnabled()) {
            log.debug("[AutonomousAgentLoop] auto-compact 检查 agent={} tokenCount={} 阈值={} 来源={}：未达阈值",
                agentId, tokenCount, threshold, thresholdSource());
        }
    }

    /** W1-3: 阈值来源描述（数据流日志）· 动态 = CompactThresholdSystem 按模型计算；兜底 = 静态字段。 */
    private String thresholdSource() {
        return compactThresholdSystem != null
            ? "CompactThresholdSystem 动态计算 (model=" + model + ")"
            : "autoCompactThresholdTokens 兜底";
    }

    /** W8-02: SubagentMessage → 简述文本（消息镜像展示）. */
    private static String describeMessage(com.nexusai.application.agent.tool.impl.SubagentMessage msg) {
        if (msg instanceof com.nexusai.application.agent.tool.impl.SubagentMessage.AssistantMessage am) {
            return "[assistant] " + am.content();
        }
        if (msg instanceof com.nexusai.application.agent.tool.impl.SubagentMessage.UserMessage um) {
            return "[user] " + um.content();
        }
        return "[" + msg.getClass().getSimpleName() + "]";
    }

    /**
     * s17-P1-1: 三态状态枚举 · 对齐 CC inProcessRunner.ts:689-868.
     */
    public enum AgentState {
        /** 正在执行当前认领的任务 */
        WORK,
        /** 当前任务完成, 等待新工作 (5s 轮询 inbox + task) */
        IDLE,
        /** 收到 shutdown 信号, 退出循环 */
        SHUTDOWN
    }
}
