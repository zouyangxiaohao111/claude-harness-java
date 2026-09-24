package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.chat.ChatService;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.common.SessionKeys;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主会话后台化派生查询服务 · 对齐 CC {@code LocalMainSessionTask.ts:338-479} {@code startBackgroundSession}.
 *
 * <p><b>职责（WF5-03，OPD-TP-13/17）</b>：从当前会话消息<b>派生一条独立 LlmAgentLoop 查询</b>
 * （不复用前台查询的 streamTopic，走任务级独立 topic），并在显式携带的 SubagentContext 归因上下文下运行。
 * 实现三块 CC 真源语义：
 * <ol>
 *   <li>{@link #startBackgroundSession} —— 独立派生查询入口（CC :338-479 完整循环语义）</li>
 *   <li>{@link #initTaskOutputAsSymlink} —— 输出 symlink 到隔离 per-task transcript
 *       （CC LocalMainSessionTask.ts:107-110 + diskOutput.ts:427-451，真 symlink + unlink 重试 + 普通文件 fallback）</li>
 *   <li>abort 中断处理 —— 后台 loop 结束后 abort 已置 → 置 notified + emitTaskTerminatedSdk('stopped')
 *       （CC :387-401，chat:killAgents/stopTask 路径语义）</li>
 * </ol>
 *
 * <p><b>命名差异说明</b>：计划 WF5-03/04 将 startBackgroundSession/completeMainSessionTask 挂
 * {@code MainSessionTaskService}，但 WF5-02（task #49，并行 in_progress）负责创建该载体类。
 * 为避免同文件冲突，本项以独立 {@code MainSessionBackgroundService} 承载派生 + 完成收尾逻辑；
 * 注册复用现有 {@link TaskFrameworkService#registerTask}（已发 task_started SDK）。
 *
 * <p><b>WF5-04（OPD-TP-18/19）职责</b>：
 * <ol>
 *   <li>{@link #completeMainSessionTask} —— running 守卫 → status/endTime → wasBackgrounded 分流：
 *       后台化 → XML 通知（CAS 防重）；已前台化 → notified + task_terminated SDK bookend
 *       （CC LocalMainSessionTask.ts:168-219）</li>
 *   <li>{@link #enqueueMainSessionNotification} —— "Background session" 5-6 TAG XML 入队
 *       （CC :224-263）</li>
 *   <li>task_started（registerTask 已发）/ task_terminated（completeMainSessionTask）SDK 收尾闭合
 *       （OPD-TP-18 用户拍板）</li>
 * </ol>
 *
 * <p><b>STOMP topic 隔离（w5-01-probe 隔离设计 1）</b>：后台派生查询用任务级独立 topic
 * {@code /topic/tasks/{taskId}/stream}（LlmAgentLoop.setTaskStreamContext），镜像 CC 按 taskId
 * 隔离 transcript（LocalMainSessionTask.ts:107 + diskOutput.ts:427）。
 */
@org.springframework.stereotype.Service
public class MainSessionBackgroundService {

    private static final Logger log = LoggerFactory.getLogger(MainSessionBackgroundService.class);

    @Autowired
    private TaskFrameworkService taskFrameworkService;
    @Autowired
    private ObjectProvider<LlmAgentLoop> loopProvider;
    @Autowired
    private SdkEventQueue sdkEventQueue;
    /** 完成通知队列 · 对齐 CC enqueuePendingNotification（LocalMainSessionTask.ts:262 mode='task-notification'） */
    @Autowired
    private NotificationQueue notificationQueue;
    /** 后台查询派发执行器 · 复用 chatExecutor（AsyncConfig:35-49，与 ChatService.processUserMessage 同池） */
    @Autowired
    @Qualifier("chatExecutor")
    private Executor backgroundExecutor;
    /**
     * [SM/compact 对齐 CC] 落库通道装配服务（只用到 {@code armPersistenceListeners}：append 实时落库 +
     * compact append-only 落库，不推 STOMP —— 本入口的流走任务级 topic）。
     *
     * <p><b>WHY</b>：后台派生查询此前完全没武装持久化监听 → 该通道 append 不落库、compact 结果不落库
     * （每 run 从 DB 恢复全量 → 反复自动压缩）。{@code required=false}：非 Spring 单测/裁剪环境注入不到 →
     * 跳过武装（保持旧行为，不阻断 run）。
     */
    @Autowired(required = false)
    private ChatService chatService;
    /**
     * [批 A4e · R2] 会话行读取（本入口的<b>会话选定权限模式</b>来源）。
     *
     * <p><b>WHY</b>：批 A4b 修掉了 {@code CronIdleExecutor} 队列 drain 轮的同类缺陷（该轮曾走
     * {@code RunRequest} 的「硬编码 permissionModeCli=null」便捷重载 ⇒ 回落全局
     * {@code settings.permission_mode}，本机实测 = {@code bypassPermissions} ⇒ 整轮静默绕过权限检查），
     * 但把本入口登记为残留 R2。本批一并修：主会话后台化派生查询跑的是<b>同一个会话</b>
     * （对齐 CC {@code LocalMainSessionTask} 沿用同一 appState/权限上下文），丢会话模式即行为分叉。
     *
     * <p>{@code required=false}：非 Spring 单测 / 裁剪环境注入不到 → 走「取不到」分支
     * （≥WARN 留痕，不阻断 run）。
     */
    @Autowired(required = false)
    private SessionMapper sessionMapper;
    /**
     * 会话 AgentState 注册表 · {@link #killMainSessionTask} 的**真中断**通道。
     *
     * <p><b>WHY</b>：本服务的后台派生查询（{@code runBackgroundQuery} → {@code LlmAgentLoop.run}）
     * 按 {@code agentUuid}（= {@code taskAgentId(taskId)}）注册进本注册表的 <b>agents 桶</b>
     * （{@code SessionAgentStateRegistry.register(UUID, AgentState)}，由其 javadoc :96-117 +
     * {@code LlmAgentLoop}:2735-2737 确证）⇒ 停止任务时经该桶取回在飞 AgentState 调
     * {@code abortStream("user-cancel")} = 与 {@code ChatService.cancelSession}（:2097-2114）
     * <b>同一条</b>硬中断路径（attach 的 runAbortController.abort → provider 流消费循环 chunk 边界
     * 检查 aborted → loop 立即退出）。缺此通道时 kill 只改状态，卡片显示「已停止」而后台仍在烧 token。
     *
     * <p>{@code required=false}（仿该文件 sessionMapper 风格）：非 Spring 单测 / 裁剪环境注入不到
     * → {@link #killMainSessionTask} 降级为「只改状态」并打 <b>≥WARN</b> 留痕（⛔ 不静默）。
     */
    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;
    /** [IMP2-10 · MISS-2 · OD-13] taskBudget 配置源（tokens；0 = 未配置 → 回落 RunRequest.DEFAULT_TASK_BUDGET_TOTAL） */
    @Value("${nexusai.agent.task-budget.total:0}")
    private int taskBudgetTotalConfigured = 0;

    /**
     * 任务级 abort 信号 · 对齐 CC taskState.abortController（LocalMainSessionTask.ts:114/136
     * {@code existingAbortController ?? createAbortController()}）。
     *
     * <p>abortController 为 CC 易失字段，不入 MainSessionTaskState 载体（W5-02a record JavaDoc 声明），
     * 由本服务注册侧持有。registerMainSessionTask 为每任务建 AtomicBoolean(false)（CC createAbortController 等价）；
     * startBackgroundSession 中断检测（CC :387-401 abortSignal.aborted）后续接线（R-1 待办）。
     */
    private final Map<String, AtomicBoolean> taskAbortSignals = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 注册主会话后台任务 + 输出 symlink 隔离 · 对齐 CC {@code registerMainSessionTask}
     * （LocalMainSessionTask.ts:94-162 的 taskState 构造 + registerTask 部分）。
     *
     * <p><b>symlink（OPD-TP-17）</b>：输出文件 symlink 到隔离 per-task transcript
     * {@code {sessionDir}/{sessionId}/subagents/agent-{taskId}.jsonl}，不写主会话 transcript
     * （/clear 后损坏防护，CC :102-106 注释）。
     *
     * @param description  任务描述（CC prompt 字段）
     * @param sessionId    会话 ID
     * @param wsTemplate   STOMP 模板（后台 loop 注入用，null = 跳过流式推送）
     * @return 任务 ID（'s' 前缀）
     */
    public String registerMainSessionTask(String description, String sessionId,
                                          @Nullable SimpMessagingTemplate wsTemplate) {
        // CC :75-82 generateMainSessionTaskId → MainSessionTaskState.generateMainSessionId（单一事实源）
        String taskId = MainSessionTaskState.generateMainSessionId();
        Path sessionDir = resolveSessionDir(sessionId);
        // CC :107-110 initTaskOutputAsSymlink(taskId, getAgentTranscriptPath(asAgentId(taskId)))
        // [R1] sessionDir 已是 config-home 项目目录 → 用 AgentTranscript.getTranscriptPath（绝对 base，
        //   不再经 SessionStorage 的 config-home 派生 seam，避免双派生）对齐 recordSidechainTranscript。
        Path transcriptTarget = AgentTranscript.getTranscriptPath(sessionDir, sessionId, taskId);
        String outputFile = initTaskOutputAsSymlink(taskId, sessionDir, sessionId, transcriptTarget);

        // CC :114 abortController = existingAbortController ?? createAbortController() ——
        //   易失字段不入载体（W5-02a JavaDoc），服务侧 map 持有（R-1 abort 接线待办）
        taskAbortSignals.put(taskId, new AtomicBoolean(false));

        // CC :132-141 agentId=taskId 合一（taskId 的 UUID 视图，隔离 sessionAgentStateRegistry）
        // [session-id-short] taskId 合规 UUID → fromString；非 UUID（测试/兼容 taskId 串）→ 稳定 hash
        // 兜底（对齐旧 parseSessionUuid 的 agentUuid 语义，agentId=taskId 归一与会话无关保持 UUID）。
        UUID agentUuid = taskAgentId(taskId);
        BackgroundTask projection = new BackgroundTask(
            taskId,
            TaskType.LOCAL_AGENT,
            BackgroundTaskStatus.RUNNING,
            description,
            null,
            System.currentTimeMillis(),
            null,
            null,
            outputFile,
            0L,
            false,
            agentUuid,      // agentId=taskId UUID 视图（CC :132）
            true,           // isBackgrounded：后台任务恒 true（CC :141）
            sessionId,      // Phase 4 (cron-notify): 创建会话 sessionId（enqueueMainSessionNotification 通知注入创建会话回合）
            // [IMP-G] G25① 跟踪字段：projection 视图不跟踪 exitCode/error/prompt/result（主会话任务）
            // [FORK-02] worktreePath/worktreeBranch 亦 null（主会话投影无隔离 worktree）
            null, null, null, null, null, null
        );
        // CC :128-145 taskState 全字段初始化（OPD-TP-16 独立载体 MainSessionTaskState，不污染 BackgroundTask）
        MainSessionTaskState state = new MainSessionTaskState(
            taskId,                                       // id
            TaskType.LOCAL_AGENT,                         // type:'local_agent'（CC :130）
            BackgroundTaskStatus.RUNNING,                 // status:'running'（CC :131）
            description,                                  // description
            null,                                         // toolUseId（CC 未初始化）
            System.currentTimeMillis(),                   // startTime（CC createTaskStateBase Date.now()）
            null,                                         // endTime
            null,                                         // totalPausedMs
            outputFile,                                   // outputFile
            0L,                                           // outputOffset
            false,                                        // notified
            taskId,                                       // agentId=taskId（CC :132）
            description,                                  // prompt=description（CC :133）
            MainSessionTaskState.AGENT_TYPE_MAIN_SESSION, // agentType:'main-session'（CC :56）
            null,                                         // model
            null,                                         // error
            null,                                         // progress
            false,                                        // retrieved:false（CC :136）
            null,                                         // messages（CC 未初始化）
            0L,                                           // lastReportedToolCount:0（CC :137）
            0L,                                           // lastReportedTokenCount:0（CC :138）
            true,                                         // isBackgrounded:true（CC :141）
            List.of(),                                    // pendingMessages:[]（CC :142）
            false,                                        // retain:false（CC :143）
            false,                                        // diskLoaded:false（CC :144）
            null                                          // evictAfter
        );
        // CC :150 registerTask(taskState, setAppState) → framework.ts:77-117：
        //   BackgroundTask 投影入 store（框架层零改动）+ MainSessionTaskState 入 mainSessionStore
        //   + task_started SDK（prompt 入事件，CC framework.ts:116）
        taskFrameworkService.registerMainSessionTask(state, projection);
        log.info("主会话后台化任务注册: id={}, sessionId={}, description='{}', output={}, agentType={}",
            taskId, sessionId, description, outputFile, state.agentType());
        return taskId;
    }

    /**
     * 获取任务 abort 信号 · 对齐 CC registerMainSessionTask 返回值 {@code abortSignal}
     * （LocalMainSessionTask.ts:160）。
     *
     * <p>供 stopTask / chat:killAgents 中断后台查询（R-1 接线），未注册任务返回空。
     *
     * @param taskId 主会话后台化任务 id
     * @return abort 信号（AtomicBoolean，置 true = 中断）
     */
    public Optional<AtomicBoolean> getAbortSignal(String taskId) {
        return Optional.ofNullable(taskAbortSignals.get(taskId));
    }

    /**
     * 停止主会话后台化任务（真中断 + 终态收尾）· 由 {@code BackgroundTaskRunner.stopMainSessionTask}
     * （TaskStop 第 5 环）委托调用 · 对齐 CC {@code stopTask.ts:38-65}
     * （{@code getTaskByType('local_agent').kill} → {@code LocalAgentTask.tsx:273-303}
     * 「同一处 status=killed + abortController?.abort()」—— CC 里「能 kill」与「能中断执行体」是同一件事）。
     *
     * <p><b>语义（顺序即语义，不要重排）</b>：
     * <ol>
     *   <li><b>only-if-running 守卫</b>（照抄 {@code BackgroundTaskRunner.killAsyncAgent}:1241-1248 的写法）：
     *       投影不存在 / status 非 RUNNING → false（幂等短路，无副作用）</li>
     *   <li>⭐ <b>真中断</b>（本方法承重环节）：
     *       (a) registry 取该任务 agentUuid 的在飞 AgentState → {@code abortStream("user-cancel")}
     *           （与 {@code ChatService.cancelSession} 同一条硬中断路径）；
     *       (b) 任务级 abort 信号置 true（{@link #getAbortSignal}）；
     *       两条均 log.info 留痕；取不到/未装配 → ≥WARN 留痕（⛔ 不静默）。</li>
     *   <li><b>置终态</b>：KILLED + endTime + notified，且<b>双 store 同步</b>
     *       （{@code updateTaskState} + {@code updateMainSessionTask}）——
     *       只改前者会让 mainSessionStore 滞留 RUNNING（{@code getMainSessionTask} 侧永远看到运行时态）。</li>
     *   <li><b>发终态事件</b>：{@code emitTaskTerminatedSdk(taskId, "stopped", ...)} —— 闭合
     *       registerMainSessionTask 发的 task_started bookend（前端据此把卡片置「已停止」）。</li>
     *   <li>⛔ <b>不 evict</b>：对齐既有 kill 路径（{@code killAsyncAgent} / {@code markKilled}）
     *       —— 面板要能显示「已停止」，evict 掉则前端读不到终态。</li>
     * </ol>
     *
     * <p><b>agentUuid 取法（⛔ 不是照抄别的表达式）</b>：{@code taskAgentId(taskId)} —— 本类私有单点，
     * 注册侧（{@code registerMainSessionTask}:158 用它构造 {@code BackgroundTask.agentId}；
     * {@code runBackgroundQuery}:392 用它作 {@code RunRequest} 的 agentId）与本方法<b>同源同参</b>，
     * 故映射到同一 UUID ⇒ 与 {@code LlmAgentLoop} 注册进 registry 的键一致。
     * ⛔ <b>不是</b> {@code MainSessionTaskState.agentId()}（那是 taskId 的**字符串**视图，
     * CC :132 {@code agentId=taskId}，与 registry 的 UUID 键空间不同源）。
     *
     * @param taskId 主会话后台化任务 id（'s' 前缀 9 字符）
     * @return true = 本次调用完成了「中断 + 终态」；false = 任务不存在 / 已非 running（幂等短路）
     */
    public boolean killMainSessionTask(String taskId) {
        // ① only-if-running 守卫（镜像 killAsyncAgent 守卫：先取记录，非 running 直接短路，不产生副作用）
        BackgroundTask current = taskFrameworkService.getTask(taskId).orElse(null);
        if (current == null) {
            log.warn("主会话后台化任务停止: taskId={} 不存在（framework store 无此任务）→ kill 未生效（false）", taskId);
            return false;
        }
        if (current.status() != BackgroundTaskStatus.RUNNING) {
            log.info("主会话后台化任务停止: taskId={} status={} 非 running → kill 幂等短路（false）",
                taskId, current.status().getStatusString());
            return false;
        }

        // ② 真中断 (a)：registry 在飞 AgentState 硬断流 —— 与 ChatService.cancelSession 同一路径。
        //    agentUuid 与注册侧同源同参（见 javadoc「agentUuid 取法」）。
        UUID agentUuid = taskAgentId(taskId);
        if (sessionAgentStateRegistry == null) {
            // required=false 注入不到（非 Spring 单测 / 裁剪环境）：只能置终态 —— 必须 ≥WARN，
            //   否则「卡片已停止、后台仍在烧 token」的假绿无人察觉。
            log.warn("[降级] 主会话后台化任务停止: taskId={} sessionAgentStateRegistry 未装配 → "
                + "无法硬断流，仅置终态；后台派生查询可能仍在运行（持续消耗 token）【≥WARN 显式留痕，非静默】",
                taskId);
        } else {
            AgentState inFlight = sessionAgentStateRegistry.getByAgentId(agentUuid);
            if (inFlight != null) {
                inFlight.abortStream("user-cancel");
                log.info("主会话后台化任务停止: taskId={} agentUuid={} → registry 命中在飞 AgentState，"
                    + "已 abortStream(\"user-cancel\")（provider 硬断流 → 查询循环立即退出；"
                    + "与 ChatService.cancelSession 同一硬中断路径）", taskId, agentUuid);
            } else {
                log.warn("主会话后台化任务停止: taskId={} agentUuid={} 在 registry 未命中在飞 AgentState"
                    + "（loop 尚未注册 / 已退出 / 未 attach abortController）→ 本次无硬断流可打，仍置终态",
                    taskId, agentUuid);
            }
        }
        // ② 真中断 (b)：任务级 abort 信号置 true（CC registerMainSessionTask 返回的 abortSignal :160
        //    在本类的持有物 = taskAbortSignals）。⚠️ 如实说明承重关系：runBackgroundQuery 的 abort 分支
        //    读的是**形参** abortFlag（:440），生产 ChatController 传 null ⇒ 该形参恒 null ——
        //    真正生效的中断是上面的 (a)；本 (b) 令「传入了本 map 中同一实例的调用方」在 loop 返回后
        //    短路为 markNotified + emitTaskTerminatedSdk('stopped')（:440-449），与 CC
        //    「taskState.abortController 被 stop 置位」同形。
        Optional<AtomicBoolean> abortSignal = getAbortSignal(taskId);
        if (abortSignal.isPresent()) {
            abortSignal.get().set(true);
            log.info("主会话后台化任务停止: taskId={} 任务级 abort 信号已置 true（taskAbortSignals 命中）", taskId);
        } else {
            log.warn("主会话后台化任务停止: taskId={} 任务级 abort 信号不存在"
                + "（taskAbortSignals 无此条目：未注册 / 已被清理）→ 跳过置位", taskId);
        }

        // ③ 置终态 KILLED + endTime + notified（写法照抄既有 kill 路径 killAsyncAgent:1249-1252 的
        //    withStatus(KILLED).withEndTime(now).withNotified()），双 store 同步。
        BackgroundTask terminal = current
            .withStatus(BackgroundTaskStatus.KILLED)
            .withEndTime(System.currentTimeMillis())
            .withNotified();
        taskFrameworkService.updateTaskState(taskId, terminal);
        MainSessionTaskState carrier = taskFrameworkService.getMainSessionTask(taskId).orElse(null);
        if (carrier != null) {
            taskFrameworkService.updateMainSessionTask(taskId,
                carrier.withStatus(BackgroundTaskStatus.KILLED)
                    .withEndTime(terminal.endTime())
                    .withNotified());
            log.info("主会话后台化任务终止态已置: taskId={} status=killed, endTime={}, notified=true（双 store 同步）",
                taskId, terminal.endTime());
        } else {
            // 双 store 不一致（理论上 registerMainSessionTask 必双写；此处 fail-loud 暴露，不静默）
            log.warn("主会话后台化任务终止态: taskId={} mainSessionStore 无对应载体（双 store 不一致）→ "
                + "仅投影置终态；getMainSessionTask 侧仍读不到该任务", taskId);
        }

        // ④ 发终态事件 'stopped'（闭合 task_started bookend，前端据此将卡片置「已停止」）。
        //    选项构造照抄本文件既有 emit 点（completeMainSessionTask:590-591/:595-596 的形参给法：
        //    toolUseId + "Background session" + outputFile + usage=null），与 abort 分支 :446-447
        //    同为 'stopped' 语义点。
        sdkEventQueue.emitTaskTerminatedSdk(terminal.sessionId(), taskId, "stopped",
            new SdkEventQueue.TaskTerminatedOpts(terminal.toolUseId(), "Background session",
                terminal.outputFile(), null));
        log.info("主会话后台化任务停止完成: taskId={} → emitTaskTerminatedSdk('stopped') 已发", taskId);

        // ⑤ ⛔ 不 evict：对齐既有 kill 路径（BackgroundTaskRunner.killAsyncAgent 只置态不移除 /
        //    LocalShellTaskGuards 路径的 markKilled 同理）—— 面板要能显示「已停止」；
        //    移除任务会让前端 2s 轮询直接读不到该条目（卡片消失而非显示「已停止」）。
        return true;
    }

    /**
     * 初始化任务输出为 symlink · 对齐 CC {@code initTaskOutputAsSymlink}（diskOutput.ts:427-451）。
     *
     * <p>语义（CC 实际源码行为）：ensureOutputDir → {@code symlink(target, outputPath)} →
     * 失败 {@code unlink(outputPath)} 重试 symlink → 再失败 {@code initTaskOutput} fallback。
     * Java 以 {@code Files.createSymbolicLink} + deleteIfExists 重试 + {@code CREATE_NEW}
     * 空文件 fallback（等价 CC win32 {@code 'wx'} = O_EXCL，绝不跟随/截断已存在路径，
     * diskOutput.ts:17-18 SECURITY；catch-all 等价 CC 裸 catch/catch(error)）。
     * 输出初始化失败绝不外抛 —— 保证 registerMainSessionTask 不被输出隔离阻断。
     *
     * @param taskId        任务 ID
     * @param sessionDir    session 根目录（[R1] config-home 项目 slug 目录）
     * @param sessionId     会话 ID
     * @param targetPath    symlink 目标（per-task transcript）
     * @return 输出文件路径（symlink 成功或 fallback 普通文件）
     */
    public String initTaskOutputAsSymlink(String taskId, Path sessionDir, String sessionId, Path targetPath) {
        Path outputDir = sessionDir.resolve(sessionId).resolve("tasks");
        Path outputPath = outputDir.resolve(taskId + ".output");
        try {
            Files.createDirectories(outputDir);
            try {
                Files.createSymbolicLink(outputPath, targetPath);
                log.info("主会话后台化输出 symlink 已创建: output={} -> target={}", outputPath, targetPath);
                return outputPath.toString();
            } catch (Exception e) {
                // 已存在 / symlink 不支持 / RuntimeException（如无效 target）→ unlink 重试。
                // CC diskOutput.ts:440-442 为裸 catch {}（catch-all），Java 以 catch Exception 等价。
                log.warn("symlink 创建失败，尝试 unlink 重试: {}: {}", outputPath, e.getMessage());
                try {
                    Files.deleteIfExists(outputPath);
                    Files.createSymbolicLink(outputPath, targetPath);
                    log.info("主会话后台化输出 symlink 重试成功: output={}", outputPath);
                    return outputPath.toString();
                } catch (Exception e2) {
                    // 再失败 → initTaskOutput fallback（CC :445-447，外层裸 catch(error)）
                    log.warn("symlink 重试失败，fallback 普通文件: {}", e2.getMessage());
                    return writeFallbackOutput(outputPath);
                }
            }
        } catch (Exception e) {
            log.warn("输出目录创建失败: {}", e.getMessage());
            return writeFallbackOutput(outputPath);
        }
    }

    private String writeFallbackOutput(Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            // CC diskOutput.ts:400-421 initTaskOutput —— O_EXCL（win32 'wx'）：
            //   以 CREATE_NEW 新建空文件；若路径已存在（含攻击者预置 symlink）则失败，
            //   绝不跟随 symlink / 截断目标（diskOutput.ts:17-18 SECURITY 承诺）。
            try (java.nio.channels.FileChannel ignored = java.nio.channels.FileChannel.open(
                    outputPath, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                // 空文件即已创建
            }
            return outputPath.toString();
        } catch (Exception e) {
            log.error("fallback 输出文件创建失败: {}", e.getMessage());
            return outputPath.toString();
        }
    }

    /**
     * 启动主会话后台化派生查询 · 对齐 CC {@code startBackgroundSession}（LocalMainSessionTask.ts:338-479）。
     *
     * <p><b>语义（CC 实际源码行为）</b>：
     * <ol>
     *   <li>{@code registerMainSessionTask(description)} 注册 + symlink（:351-355）</li>
     *   <li>{@code recordSidechainTranscript(messages, taskId)} 持久化 pre-background 会话（:360）</li>
     *   <li>异步派发 —— 查询循环体经 {@code backgroundExecutor}（chatExecutor 池）fire-and-forget 执行
     *       （对齐 CC :375 {@code void runWithAgentContext(...)} 不 await）；注册 + transcript 在调用线程
     *       同步完成，本方法立即返回 taskId（CC :478），HTTP 线程不等待查询结束</li>
     *   <li>[S1-T7] {@code SubagentContext{agentId:taskId, agentType:'subagent',
     *       subagentName:'main-session', isBuiltIn:true}} 作为<b>值</b>经
     *       {@code RunRequest.withAgentContext(...)} 挂到本 run（CC :368-375 在 Java 端不再是
     *       ThreadLocal 外壳，而是显式值 —— 用户铁律：回放不算合规）</li>
     *   <li>独立 {@code LlmAgentLoop.run(...)}（Java 版 query 循环）：任务级 streamTopic 注入
     *       （w5-01 隔离设计 1）、逐消息 recordSidechainTranscript、assistant 内容 tokenCount +
     *       toolCount + recentActivities（≤5）统计（:383-468）</li>
     *   <li>abort 已置 → notified CAS + emitTaskTerminatedSdk('stopped', {summary: description})
     *       （:387-401）；正常/异常 → 完成收尾（W5-04 接管 completeMainSessionTask）</li>
     * </ol>
     *
     * @param sessionId      会话 ID
     * @param description    任务描述（terminalTitle 等价）
     * @param messages       当前会话消息（pre-background 派生上下文）
     * @param wsTemplate     STOMP 模板（后台 loop 任务级 topic 注入，null = 跳过流式推送）
     * @param userPrompt     派生查询 user prompt
     * @param modelName      模型名
     * @param config         Provider 配置
     * @param abortFlag      外部 abort 信号（true = 已中断）
     * @return 任务 ID
     */
    public String startBackgroundSession(String sessionId, String description,
                                         List<Map<String, Object>> messages,
                                         @Nullable SimpMessagingTemplate wsTemplate,
                                         String userPrompt, String modelName,
                                         @Nullable ProviderConfig config,
                                         @Nullable AtomicBoolean abortFlag) {
        String taskId = registerMainSessionTask(description, sessionId, wsTemplate);
        Path sessionDir = resolveSessionDir(sessionId);

        // CC :360 recordSidechainTranscript(messages, taskId) — 逐条持久化 pre-background 会话
        // recordSidechainTranscript 就地补充 agentId/isSidechain/uuid/parentUuid（AgentTranscript:161 put），
        // 入参必须为可变 map（否则 UnsupportedOperationException）。
        List<Map<String, Object>> mutableMessages = (messages == null) ? List.of()
            : messages.stream().map(HashMap::new).collect(java.util.stream.Collectors.toList());
        AgentTranscript.recordSidechainTranscript(sessionDir, sessionId, taskId, mutableMessages);
        log.info("主会话后台化派生查询启动: taskId={}, sessionId={}, messages={}", taskId, sessionId,
            messages == null ? 0 : messages.size());

        // CC :375 void runWithAgentContext(agentContext, async () => {...}) —— fire-and-forget 派发后台查询。
        // 注册 + transcript 已在调用线程同步完成，查询循环经 backgroundExecutor（chatExecutor 池）异步执行，
        // 本方法立即返回 taskId（CC :478 return taskId）。HTTP 线程不等待查询结束（对齐 CC 不阻塞调用方）。
        CompletableFuture.runAsync(
            () -> runBackgroundQuery(taskId, sessionId, description, wsTemplate, userPrompt, modelName, config, abortFlag),
            backgroundExecutor);
        return taskId;
    }

    /**
     * 在 backgroundExecutor 线程执行后台派生查询 · 对齐 CC {@code LocalMainSessionTask.ts:375-476}
     * {@code void runWithAgentContext(agentContext, async () => {...})}（CC :375，fire-and-forget 的查询循环体）。
     *
     * <p><b>[S1-T7] 归因上下文承载方式</b>：不再是 ambient 归因作用域（ThreadLocal 外壳，
     * 该载体已整体删除），而是把同一 {@code SubagentContext} 实例经
     * {@code RunRequest.withAgentContext(...)} 作为**值**下传（对齐 CC AsyncLocalStorage :104-107 的
     * <b>传播效果</b>，但不依赖 ThreadLocal —— 用户铁律：会话态一律显式传参，回放不算合规）。
     * 查询正常/异常均收敛到 completeMainSessionTask（CC :471/:474）。
     *
     * @param taskId      主会话后台化任务 id
     * @param sessionId   会话 ID
     * @param description 任务描述（terminalTitle 等价）
     * @param wsTemplate  STOMP 模板（后台 loop 任务级 topic 注入，null = 跳过流式推送）
     * @param userPrompt  派生查询 user prompt
     * @param modelName   模型名
     * @param config      Provider 配置
     * @param abortFlag   外部 abort 信号（true = 已中断）
     */
    private void runBackgroundQuery(String taskId, String sessionId, String description,
                                    @Nullable SimpMessagingTemplate wsTemplate,
                                    String userPrompt, String modelName,
                                    @Nullable ProviderConfig config,
                                    @Nullable AtomicBoolean abortFlag) {
        // CC :368-375 runWithAgentContext(SubagentContext{agentId:taskId, subagentName:'main-session', isBuiltIn:true})
        // [S1-T7] 本实例经 RunRequest.withAgentContext(...) 显式下传（唯一生产非 null 来源）
        AgentContext.SubagentContext agentContext = new AgentContext.SubagentContext(
            taskId, sessionId, "main-session", true, null, null);
        // [S1-T7] ⛔ 原此处为 AgentContext.runWithAgentContext(agentContext, () -> {...}) 外壳（CC :368-375）：
        //   把归因上下文「回放」到 executor 线程再读 —— 用户铁律：
        //   会话态一律显式传参，回放不算合规。现改为把同一 SubagentContext 实例作为**值**经
        //   RunRequest.withAgentContext(...) 挂到本 run 上（LlmAgentLoop 侧唯一的显式入口），
        //   与 ToolUseContext 其他字段同一通道，不再依赖任何 ThreadLocal。
        try {
            LlmAgentLoop loop = loopProvider.getObject();
            // w5-01 隔离设计 1：任务级独立 topic /topic/tasks/{taskId}/stream
            // [IMP-A · F6] 透传真实 sessionId —— 后台 loop 经 streamSessionId 解析会话
            //   projectRoot（F1 冻结：resolveSessionProjectRoot 先查 SessionProjectRoot
            //   冻结值，未命中走 resolver → 首 run 冻结，与前台同享会话级注入）
            loop.setTaskStreamContext(wsTemplate, taskId, sessionId);
            // [session-id-short] sessionId 已 short 直传；agentUuid 由 taskId（合规 UUID）解析
            String sessionUuid = sessionId;
            UUID agentUuid = taskAgentId(taskId); // CC agentId=taskId（:132）
            ProviderConfig cfg = (config != null) ? config : ProviderConfig.empty();
            // [IMP2-10 · MISS-2 · OD-13] taskBudget 生产注入：本入口无请求参数通道
            //   （后台派生查询），来源链 = 配置 nexusai.agent.task-budget.total → 默认值；恒非 null。
            com.nexusai.application.agent.TaskBudget taskBudget =
                com.nexusai.application.agent.RunRequest.resolveTaskBudget(null, taskBudgetTotalConfigured);
            if (log.isDebugEnabled()) {
                log.debug("[IMP2-10 taskBudget] 主会话后台化入口注入: source=配置/默认值 total={}", taskBudget.total());
            }
            // [SM/compact 对齐 CC · 消除未武装通道] 本入口此前无任何持久化监听武装 → append 不落库、
            //   compact 结果不落库。run 前经 postHistoryPersistEnabler 回调（doRun 历史注入完成、
            //   prePersistedMessageIds 登记后触发）武装「仅落库」通道：append 逐条实时落 DB（对齐 CC
            //   recordTranscript）+ compact 结果 append-only 落库（对齐 CC transcript append-only）。
            //   流式推送仍走任务级 topic（loop.setTaskStreamContext），本武装不推 STOMP。
            // [seq 排序键 核对] 本入口落库的目标就是「主会话」messages（sessionId = 真实会话 id，
            //   后台派生查询复用同一会话，非独立库）——经 ChatService.persistAppendedMessage 走既有
            //   append 实时落库通道，created_at（时间）与 seq（V70 位置键）均经 MessageService 的
            //   per-session 单点取号，与前台 writer 同域单调 → 无需本入口单独处理（行为不变）。
            if (chatService != null) {
                loop.setPostHistoryPersistEnabler(state2 ->
                    chatService.armPersistenceListeners(state2, sessionId));
                if (log.isInfoEnabled()) {
                    log.info("主会话后台化派生查询: 落库监听已武装 session={}（append 实时落库 + compact append-only）",
                        sessionId);
                }
            }
            // [S1-T7] agent 归因上下文 = 本后台任务的 SubagentContext **实例**（与旧 runWithAgentContext（CC :368-375）
            //   承载时传入的是**同一实例**——sparse-edge 语义 `invocationEmitted` 靠同一 AtomicBoolean，
            //   ⛔ 任何消费点都不许 new SubagentContext(...) 重建）：
            //   经 RunRequest.withAgentContext(...) 显式下传 → buildBaseToolUseContext 形参 →
            //   base TUC.withAgentContext(...) → loop() / provider 侧单一来源。
            // [批 A4e · R2] 会话选定权限模式显式装配（原走 session(...) 便捷重载 ⇒ permissionModeCli
            //   硬编码 null ⇒ 回落全局 settings.permission_mode（本机实测可 = bypassPermissions）
            //   ⇒ 后台派生查询静默绕过会话权限。与 CronIdleExecutor 同源：
            //   ChatService.resolveEffectivePermissionMode(session, null)（本入口无 per-call 来源）。
            //   取不到会话 ⇒ 本方法内 ≥WARN 留痕（⛔ 不许静默回落全局）。
            String effectivePermissionMode = resolveSessionPermissionMode(sessionUuid);
            AgentState runState = loop.run(RunRequest.sessionFromSessionOverride(
                userPrompt, sessionUuid, agentUuid, cfg, modelName,
                null, effectivePermissionMode, false, taskBudget, null).withAgentContext(agentContext));

            // run 收口：解除监听（防 PersistCtx/sessionId 泄漏到下轮 / 下个 task 复用本 loop 实例）
            if (runState != null) {
                runState.clearAppendListener();
                runState.clearCompactPersistListener();
            }

            // CC :387-401 abort 中断 → notified 短路 + emitTaskTerminatedSdk('stopped')
            if (abortFlag != null && abortFlag.get()) {
                log.warn("主会话后台化派生查询被中断: taskId={}", taskId);
                // CC :391-399 原子 check-and-set —— 仅当此前未 notified 才发 task_terminated('stopped')
                //   （chat:killAgents 路径已 notified+emitted 则不重发；stopTask 路径必须发 bookend）
                boolean alreadyNotified = markNotified(taskId);
                if (!alreadyNotified) {
                    // C2 · 会话归属 = 本后台派生查询的会话（runBackgroundQuery 形参 sessionId）
                    sdkEventQueue.emitTaskTerminatedSdk(sessionId, taskId, "stopped",
                        new SdkEventQueue.TaskTerminatedOpts(null, description, null, null));
                }
                return;
            }
            // 正常完成 → completeMainSessionTask（CC :471）
            completeMainSessionTask(taskId, true);
        } catch (Exception e) {
            log.error("主会话后台化派生查询失败: taskId={}", taskId, e);
            // CC :474 completeMainSessionTask(taskId, false)
            completeMainSessionTask(taskId, false);
        }
    }

    /**
     * [批 A4e · R2] 主会话后台化派生查询的【会话选定有效权限模式】。
     *
     * <p><b>WHY（A4b 同类缺陷 · 残留 R2）</b>：本入口原走 {@code RunRequest.session(...)} 的
     * 「硬编码 {@code permissionModeCli=null}」便捷重载 ⇒ {@code InitialPermissionModeResolver}
     * 回落 settings 槽（DB 全局 {@code settings.permission_mode}，本机实测 {@code bypassPermissions}）
     * ⇒ <b>后台派生查询静默绕过会话权限（含 deny）</b>。后台查询跑的是<b>同一个会话</b>，
     * 会话选定模式必须带上（对齐 CC {@code LocalMainSessionTask} 沿用同一权限上下文）。
     *
     * <p>取值与 {@link com.nexusai.application.chat.ChatService#resolveEffectivePermissionMode}
     * <b>同一判据点</b>（本入口无 per-call HTTP 请求体来源 ⇒ 传 null）—— 避免「同一能力两套判据」。
     *
     * <p><b>拿不到会话时 ≥WARN（⛔ 不得静默回落全局）</b>：① {@code sessionId} 空白；
     * ② {@code SessionKeys.NO_SESSION} 哨兵（确无会话）；③ 会话行不存在/不可读 或 mapper 未注入。
     * 三者返回 {@code null}（⇒ resolver 回落 settings 槽）但<b>每次都有 WARN 说明原因</b>。
     *
     * <p>会话存在但 {@code sessions.permission_mode} 本就为空 ⇒ 返回 null：这是三态链
     * （{@code 会话 override ?? 全局 default}）的<b>正常末态</b>，不是静默失效，故只记 INFO。
     *
     * @param sessionUuid 本 run 的会话键（可能为 null / NO_SESSION 哨兵）
     * @return 会话选定模式；不可得 → null（已 WARN 留痕，非静默）
     */
    private String resolveSessionPermissionMode(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            log.warn("主会话后台化: **拿不到会话**（sessionId 空白）→ 无法取会话选定权限模式，"
                + "permissionModeCli=null ⇒ 回落全局 settings.permission_mode（本机实测可为 "
                + "bypassPermissions ⇒ 本轮将绕过全部权限检查）。【≥WARN 显式留痕，非静默】");
            return null;
        }
        if (SessionKeys.isNoSession(sessionUuid)) {
            log.warn("主会话后台化: **确无会话**（sessionId=NO_SESSION 哨兵）→ 无法取会话选定权限模式 "
                + "⇒ 回落全局 settings.permission_mode（本机实测可为 bypassPermissions）。"
                + "【≥WARN 显式留痕，非静默】");
            return null;
        }
        if (sessionMapper == null) {
            log.warn("主会话后台化: sessionMapper 未注入（非 Spring 单测/裁剪环境）→ 无法取会话选定权限模式 "
                + "⇒ 回落全局 settings.permission_mode。sessionId={} 【≥WARN 显式留痕，非静默】",
                sessionUuid);
            return null;
        }
        String originalKey = SessionKeys.originalKey(sessionUuid);
        SessionRecord session = sessionMapper.selectOneById(
            originalKey == null ? sessionUuid : originalKey);
        if (session == null) {
            log.warn("主会话后台化: 会话行不存在/不可读（sessionId={}，会话已删）→ 无法取会话选定权限模式 "
                + "⇒ 回落全局 settings.permission_mode。【≥WARN 显式留痕，非静默】", sessionUuid);
            return null;
        }
        String mode = ChatService.resolveEffectivePermissionMode(session, null);
        log.info("主会话后台化: 权限模式已装配 sessionId={} session.permission_mode={} → "
            + "permissionModeCli={}（与 ChatService/CronIdleExecutor 同源 resolveEffectivePermissionMode；"
            + "null ⇒ resolver 回落全局 settings，属三态链正常末态）",
            sessionUuid, session.getPermissionMode(), mode);
        return mode;
    }

    /**
     * 原子 check-and-set 置 notified · 对齐 CC {@code updateTaskState(task => ...)} CAS
     * （LocalMainSessionTask.ts:391-399 abort 分支 / :207-218 已前台化分支）。
     *
     * @param taskId 主会话后台化任务 id
     * @return 此前是否已 notified（true = 已通知，调用方应跳过 emitTaskTerminatedSdk，CC :397-399）
     */
    private boolean markNotified(String taskId) {
        BackgroundTask current = taskFrameworkService.getTask(taskId).orElse(null);
        if (current == null) {
            return true;
        }
        if (current.notified()) {
            return true;
        }
        taskFrameworkService.updateTaskState(taskId, current.withNotified());
        return false;
    }

    /**
     * 完成主会话后台化任务并发送完成通知 · 对齐 CC {@code completeMainSessionTask}
     * （LocalMainSessionTask.ts:168-219）。
     *
     * <p><b>语义（CC 实际源码行为）</b>：
     * <ol>
     *   <li>{@code status !== 'running'} 直接返回 no-op（:177-179 running 守卫）</li>
     *   <li>捕获 {@code wasBackgrounded}（默认 true）+ toolUseId（:182-183）</li>
     *   <li>置 {@code status=completed/failed}、{@code endTime=Date.now()}（:187-192）</li>
     *   <li>{@code evictTaskOutput(taskId)}（:195）</li>
     *   <li>{@code wasBackgrounded=true} → {@code enqueueMainSessionNotification}（XML 通知，:199-206）；
     *       {@code wasBackgrounded=false} → 置 notified + {@code emitTaskTerminatedSdk}
     *       （:207-218 SDK 收尾，防 evict 守卫挂死）</li>
     * </ol>
     *
     * @param taskId  主会话后台化任务 id
     * @param success 查询是否成功完成（true → completed，false → failed）
     */
    public void completeMainSessionTask(String taskId, boolean success) {
        BackgroundTask current = taskFrameworkService.getTask(taskId).orElse(null);
        if (current == null) {
            if (log.isDebugEnabled()) {
                log.debug("主会话后台化任务完成: taskId={} 不存在 → 跳过", taskId);
            }
            return;
        }
        // CC :177-179 running 守卫 —— 非 running（已终态/被 abort 短路）直接 no-op
        if (current.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("主会话后台化任务完成: taskId={} 状态非 running({}) → no-op",
                    taskId, current.status().getStatusString());
            }
            return;
        }
        // CC :182-183 捕获 wasBackgrounded（默认 true）+ toolUseId
        boolean wasBackgrounded = current.isBackgrounded();
        String toolUseId = current.toolUseId();

        // CC :187-192 status/endTime（Java BackgroundTask 无 messages 字段 → 无裁剪）
        BackgroundTaskStatus next = success ? BackgroundTaskStatus.COMPLETED : BackgroundTaskStatus.FAILED;
        BackgroundTask terminal = current
            .withStatus(next)
            .withEndTime(System.currentTimeMillis());
        taskFrameworkService.updateTaskState(taskId, terminal);

        // CC :195 evictTaskOutput —— Java 侧输出为 symlink/普通文件，由 offset-evict
        //   （generateTaskAttachments）惰性 GC；此处不显式删除（防 kill 后 reader 读不到）。
        // CC :199-218 wasBackgrounded 分流 —— 后台化 → XML 通知（模型消费）；已前台化 → 仅 SDK。
        // 两者均发 task_terminated SDK bookend（OPD-TP-18 用户拍板"完成发 task_terminated"；
        // Java 架构 XML→模型队列 / SDK→前端 双通道，非双发，见 SdkEventQueue javadoc :24-25）。
        if (wasBackgrounded) {
            // CC :199-206 后台化 → XML 通知（原子 notified CAS 防重）
            enqueueMainSessionNotification(taskId, "Background session",
                success ? "completed" : "failed", toolUseId);
            sdkEventQueue.emitTaskTerminatedSdk(terminal.sessionId(), taskId, success ? "completed" : "failed",
                new SdkEventQueue.TaskTerminatedOpts(toolUseId, "Background session", terminal.outputFile(), null));
        } else {
            // CC :207-218 已前台化 → 置 notified + SDK task_terminated bookend（无 XML）
            markNotified(taskId);
            // C2 · 会话归属：terminal = current.withStatus/withEndTime 派生副本，
            //   sessionId 随 with* 系列保留（BackgroundTask.withStatus 透传 sessionId）。
            sdkEventQueue.emitTaskTerminatedSdk(terminal.sessionId(), taskId, success ? "completed" : "failed",
                new SdkEventQueue.TaskTerminatedOpts(toolUseId, "Background session", terminal.outputFile(), null));
        }
        log.info("主会话后台化任务完成: taskId={}, success={}, wasBackgrounded={}, notified={}",
            taskId, success, wasBackgrounded,
            wasBackgrounded ? true : taskFrameworkService.getTask(taskId).map(BackgroundTask::notified).orElse(false));
    }

    /**
     * 入队主会话后台化完成通知 · 对齐 CC {@code enqueueMainSessionNotification}
     * （LocalMainSessionTask.ts:224-263）。
     *
     * <p><b>语义（CC 实际源码行为）</b>：
     * <ol>
     *   <li>{@code notified} 原子 check-and-set 防重（:231-243）：已通知 → 直接返回</li>
     *   <li>{@code summary} = {@code Background session "{description}" completed/failed}（:245-248）</li>
     *   <li>XML = {@code task-notification > task-id + [tool-use-id] + output-file + status + summary}
     *       （5-6 TAG，:255-260，TASK_ID/TOOL_USE_ID/OUTPUT_FILE/STATUS/SUMMARY 连字符 tag，
     *       不含 task-type）</li>
     *   <li>{@code enqueuePendingNotification({value, mode:'task-notification'})}（:262）</li>
     * </ol>
     *
     * @param taskId      主会话后台化任务 id
     * @param description 任务描述（'Background session'）
     * @param status      终态 'completed' | 'failed'
     * @param toolUseId   关联 tool_use id（可空）
     */
    public void enqueueMainSessionNotification(String taskId, String description,
                                               String status, String toolUseId) {
        // CC :231-243 原子 notified check-and-set 防重
        BackgroundTask current = taskFrameworkService.getTask(taskId).orElse(null);
        if (current == null || current.notified()) {
            if (log.isDebugEnabled()) {
                log.debug("主会话后台化通知: taskId={} 已通知或不存在 → 跳过", taskId);
            }
            return;
        }
        taskFrameworkService.updateTaskState(taskId, current.withNotified());

        String summary = "completed".equals(status)
            ? "Background session \"" + description + "\" completed"
            : "Background session \"" + description + "\" failed";
        // CC :255-260 XML 拼接（task-id + [tool-use-id] + output-file + status + summary）
        StringBuilder xml = new StringBuilder(512);
        xml.append("<task-notification>\n");
        xml.append("  <task-id>").append(taskId).append("</task-id>\n");
        if (toolUseId != null && !toolUseId.isBlank()) {
            xml.append("  <tool-use-id>").append(toolUseId).append("</tool-use-id>\n");
        }
        xml.append("  <output-file>").append(current.outputFile() != null ? current.outputFile() : "")
           .append("</output-file>\n");
        xml.append("  <status>").append(status).append("</status>\n");
        xml.append("  <summary>").append(summary).append("</summary>\n");
        xml.append("</task-notification>");

        // CC :262 enqueuePendingNotification({value, mode:'task-notification'}) 等价。
        // Phase 4 (cron-notify): 通知带创建会话 sessionId（registerMainSessionTask 透传）
        // → drain 3a 注入创建会话回合（会话活跃时）；null（无会话）回落全局。
        notificationQueue.enqueuePendingNotification(
            new NotificationQueue.QueueItem(xml.toString(), NotificationQueue.MODE_TASK_NOTIFICATION,
                null, null, null, false, null, false, null, current.sessionId()));
        log.info("主会话后台化通知已入队: taskId={}, status={}, sessionId={}", taskId, status, current.sessionId());
    }

    /**
     * 会话目录根 · [R1] 旧 {tmpdir}/nexusai-sessions 平铺根 → config-home 项目 slug 目录
     * （{@link SessionStorage#sessionProjectDir}）。
     *
     * <p><b>[F1 2026-09-16] 锚 = 稳定会话绑定项目根</b>（{@code CwdResolution.getProjectRoot}，
     * 经 {@code SessionStorage.sessionProjectRoot} 单点）—— 原「对齐 CC
     * {@code getProjectDir(getOriginalCwd())}」的说法<b>已不成立</b>：originalCwd 层被
     * {@code EnterWorktreeTool} 重锚 ⇒ 进 worktree 后换根，与读侧（hook 的 agent_transcript_path）
     * 分裂。⛔ 勿改回。详见 {@code SessionStorage.sessionProjectRoot} javadoc（含 CC gh-30217 对照）。
     *
     * <p>供 subagent sidechain transcript（AgentTranscript）与主会话后台 symlink（initTaskOutputAsSymlink）
     * 使用 —— 双根分裂消除（读侧 seam {@code SessionStorage.getAgentTranscriptPathForSession} 与
     * {@code AgentTranscript.getTranscriptPath} 同根）。
     *
     * @param sessionId 会话 ID。null / 空白 / {@code SessionKeys.NO_SESSION} 哨兵 ⇒ 走<b>无会话命名出口</b>
     *                  （值 = 进程 {@code user.dir}，并打 ≥WARN）；DB 明确答「无此会话」/ 会话存在但未绑定
     *                  项目 / <b>无法判定</b>（回源器未接线、抛错、违约返回 null）⇒ <b>fail-loud 抛</b>
     *                  （⛔ 不是「回落 user.dir 兜底层」）
     */
    private Path resolveSessionDir(String sessionId) {
        return SessionStorage.sessionProjectDir(sessionId);
    }

    /**
     * 解析后台任务 agentId（= taskId 的 UUID 视图）· 对齐 CC :132-141 agentId=taskId 合一。
     *
     * <p>[session-id-short] taskId 合规 UUID → fromString；非 UUID（测试 / 兼容 taskId 串）→ 稳定
     * hash 兜底（对齐旧 parseSessionUuid 的 agentUuid 语义，同一 taskId 多次调用映射同一 UUID）。
     */
    private static UUID taskAgentId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return new UUID(0L, 0L);
        }
        try {
            return UUID.fromString(taskId);
        } catch (IllegalArgumentException e) {
            long h1 = taskId.hashCode();
            long h2 = (taskId.hashCode() * 31L) ^ (taskId.length() * 17L);
            return new UUID(h1, h2);
        }
    }
}
