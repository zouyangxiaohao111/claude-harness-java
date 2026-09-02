package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.infra.util.AbortControllerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Spawn In Process · 对齐 CC utils/swarm/spawnInProcess.ts（W8-01 生产化，DEL-29）。
 *
 * <p><b>生产基底（OPD-TP-06）</b>：{@code team/SpawnInProcess} 是 teammate 生产化三基底之一，
 * 承担 CC {@code spawnInProcessTeammate}（spawnInProcess.ts:104-216）等价职责：
 * <ol>
 *   <li><b>agentId = name@team</b>（formatAgentId，spawnInProcess.ts:112）</li>
 *   <li><b>taskId = generateTaskId('in_process_teammate')</b> → 't' 前缀 + 8 位随机
 *       （Task.ts:98-105 TASK_ID_PREFIXES + :98 generateTaskId）</li>
 *   <li><b>独立 abortController</b>（createAbortController，spawnInProcess.ts:122——不受 leader 中断）</li>
 *   <li><b>TeammateIdentity + TeammateContext</b>（spawnInProcess.ts:128-147）</li>
 *   <li><b>permissionMode = planModeRequired ? 'plan' : 'default'</b>（spawnInProcess.ts:173）</li>
 *   <li><b>registerTask 桥接</b>（spawnInProcess.ts:191 → framework.ts:77-117）
 *       ——经 {@link InProcessTeammateTaskRegistry} 落 BackgroundTask 状态层</li>
 *   <li><b>启动 runTeammateLoop</b>（fire-and-forget，对齐 CC InProcessBackend.spawn
 *       spawnInProcess.ts → startInProcessTeammate）——使状态机<b>生产可达</b>（非死代码）</li>
 * </ol>
 *
 * <p><b>R1 阻断项（生产调用方）</b>：本类创建并接线 {@link AutonomousAgentLoop} 实例，
 * {@code runTeammateLoop/kill/complete/fail} 因此有生产调用方
 * （grep {@code AutonomousAgentLoop} 非自身 ≥1）。
 *
 * <p>abort 用 {@link AbortControllerFactory}（infra 已有）替代 CompletableFuture.cancel（S-8 修正）。
 */
@Component
public class SpawnInProcess {

    private static final Logger log = LoggerFactory.getLogger(SpawnInProcess.class);

    /** taskId 字母表 · 对齐 CC Task.ts:93-97 TASK_ID_ALPHABET（36 位，抵御 symlink 暴力） */
    private static final String TASK_ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    private static final SecureRandom RNG = new SecureRandom();

    /** 注册表（spawn 生产化核心：registerTask 桥接 + loop 持有）· 惰性创建，见 {@link #registry()} */
    private volatile InProcessTeammateTaskRegistry registry;

    @Autowired(required = false) private TaskFrameworkService taskFrameworkService;
    @Autowired(required = false) private SdkEventQueue sdkEventQueue;
    @Autowired(required = false) private TaskService taskService;
    @Autowired(required = false) private SubagentExecutor subagentExecutor;
    @Autowired(required = false) private TeamHelpers teamHelpers;
    /** W8-04 REWORK: 会话消息库 · 完成通知链（outboundSink → task_status attachment 落库）。 */
    @Autowired(required = false) private com.nexusai.domain.session.MessageService messageService;
    /**
     * [team-panel-backend-bugfix2] Team 状态推送单点 · spawn 成员落盘后发 member_joined
     * （/topic/sessions/{leadSessionId}/team-status，前端面板刷新）。可选注入：未注入（测试直构）→
     * 跳过推送（对齐 TeamCreateTool teamStatusPublisher 模式）。
     */
    @Autowired(required = false) private TeamStatusPublisher teamStatusPublisher;

    /**
     * 测试/接线用构造器：注入 TaskFrameworkService（BackgroundTask 状态层）。
     */
    public SpawnInProcess(TaskFrameworkService taskFrameworkService) {
        this.taskFrameworkService = taskFrameworkService;
        this.registry = new InProcessTeammateTaskRegistry(taskFrameworkService);
    }

    /** Spring 构造器（@Component；TaskFrameworkService 经字段注入）。 */
    public SpawnInProcess() {
        // registry 惰性创建：Spring 字段注入完成后 registry() 首次调用才建
    }

    /**
     * 注册表访问 · 惰性创建：若构造器未建（Spring 路径），用当前
     * {@link #taskFrameworkService} 字段（Spring 已注入）创建。
     */
    public InProcessTeammateTaskRegistry registry() {
        InProcessTeammateTaskRegistry r = registry;
        if (r == null) {
            synchronized (this) {
                r = registry;
                if (r == null) {
                    r = new InProcessTeammateTaskRegistry(taskFrameworkService);
                    registry = r;
                }
            }
        }
        return r;
    }

    /** 测试/接线用 setter（sdkEventQueue · 终端 SDK 链）. */
    public void setSdkEventQueue(SdkEventQueue sdkEventQueue) {
        this.sdkEventQueue = sdkEventQueue;
    }

    /** 测试/接线用 setter（taskService · tryClaimNextTask）. */
    public void setTaskService(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 测试/接线用 setter（subagentExecutor · runAgent 等价委托）. */
    public void setSubagentExecutor(SubagentExecutor subagentExecutor) {
        this.subagentExecutor = subagentExecutor;
    }

    /** 测试/接线用 setter（teamHelpers · kill 时 removeMemberByAgentId）. */
    public void setTeamHelpers(TeamHelpers teamHelpers) {
        this.teamHelpers = teamHelpers;
    }

    /** 测试/接线用 setter（teamStatusPublisher · spawn 成员落盘后 member_joined 推送）. */
    public void setTeamStatusPublisher(TeamStatusPublisher teamStatusPublisher) {
        this.teamStatusPublisher = teamStatusPublisher;
    }

    /** 测试/接线用 setter（messageService · 完成通知链 outboundSink 落库）. */
    public void setMessageService(com.nexusai.domain.session.MessageService messageService) {
        this.messageService = messageService;
    }

    // ════════════════════════════════════════════════════════════════════
    // 数据契约 · 对齐 CC spawnInProcess.ts InProcessSpawnConfig/InProcessSpawnOutput
    // ════════════════════════════════════════════════════════════════════

    /**
     * spawn 配置 · 对齐 CC spawnInProcess.ts:59-72 InProcessSpawnConfig。
     *
     * <p>[Batch2 S1] 扩展 {@code agentType}/{@code cwd}（CC appendTeamMember 需要，
     * spawnMultiAgent.ts:497/505）：teammate 落盘 config.json members 时携带 agent_type 与 cwd。
     */
    public record InProcessSpawnConfig(
        String name,
        String teamName,
        String prompt,
        String color,
        boolean planModeRequired,
        String model,
        String agentType,
        String cwd
    ) {
        /**
         * 6 参便捷构造器 · 既有测试/调用点缺省 agentType/cwd=null（最小改动，非兼容壳）。
         */
        public InProcessSpawnConfig(String name, String teamName, String prompt, String color,
                                    boolean planModeRequired, String model) {
            this(name, teamName, prompt, color, planModeRequired, model, null, null);
        }
    }

    /**
     * spawn 上下文 · 对齐 CC spawnInProcess.ts:51-54 SpawnContext（setAppState + toolUseId）。
     */
    public record SpawnContext(String parentSessionId, String toolUseId) {}

    /**
     * spawn 输出 · 对齐 CC spawnInProcess.ts:77-90 InProcessSpawnOutput。
     */
    public record InProcessSpawnOutput(
        boolean success,
        String agentId,
        String taskId,
        AbortControllerFactory.AbortControllerRef abortController,
        TeammateContext teammateContext,
        String error
    ) {}

    // ════════════════════════════════════════════════════════════════════
    // spawnInProcessTeammate
    // ════════════════════════════════════════════════════════════════════

    /**
     * 生成 agentId · 对齐 CC utils/agentId.ts:25-27 formatAgentId（name@team）。
     */
    public static String formatAgentId(String agentName, String teamName) {
        return agentName + "@" + teamName;
    }

    /**
     * 生成 taskId · 对齐 CC Task.ts:98-105 generateTaskId：前缀 + 8 位随机（36 字母表）。
     */
    public static String generateTaskId() {
        StringBuilder id = new StringBuilder("t"); // TASK_ID_PREFIXES in_process_teammate='t'
        for (int i = 0; i < 8; i++) {
            id.append(TASK_ID_ALPHABET.charAt(RNG.nextInt(TASK_ID_ALPHABET.length())));
        }
        return id.toString();
    }

    /**
     * Spawn 一个 in-process teammate · 对齐 CC spawnInProcess.ts:104-216 spawnInProcessTeammate。
     *
     * <p>流程：
     * <ol>
     *   <li>agentId = formatAgentId(name, teamName)（:112）</li>
     *   <li>taskId = generateTaskId（'t' 前缀，:113）</li>
     *   <li>独立 abortController（:122）</li>
     *   <li>parentSessionId = context.parentSessionId（:125 getSessionId）</li>
     *   <li>TeammateIdentity（:128-135）+ TeammateContext（:139-147）</li>
     *   <li>description = {@code `${name}: ${prompt.substring(0,50)}...`}（:155）</li>
     *   <li>taskState 全量初始化（permissionMode = planModeRequired?'plan':'default'，:157-180）</li>
     *   <li>registerTask 桥接（:191 → registry）</li>
     *   <li>创建 + 接线 AutonomousAgentLoop → 启动 runTeammateLoop（fire-and-forget，生产可达）</li>
     * </ol>
     *
     * @return SpawnOutput（success/agentId/taskId/abortController/teammateContext）
     */
    public InProcessSpawnOutput spawnInProcessTeammate(InProcessSpawnConfig config, SpawnContext context) {
        String agentId = formatAgentId(config.name(), config.teamName());
        String taskId = generateTaskId();
        log.info("[spawnInProcessTeammate] Spawning {} (taskId: {})", agentId, taskId);
        try {
            // 独立 AbortController（不受 leader 中断，CC :122）
            AbortControllerFactory.AbortControllerRef abortController = AbortControllerFactory.create();

            // parentSessionId = Leader's session（CC :125 getSessionId）
            String parentSessionId = context != null && context.parentSessionId() != null
                ? context.parentSessionId() : TaskService.getTaskListId();

            // identity（纯数据载体）
            TeammateIdentity identity = new TeammateIdentity(
                agentId, config.name(), config.teamName(), config.color(),
                config.planModeRequired(), parentSessionId);

            // teammateContext（ThreadLocal 运行时载体，CC :139-147 createTeammateContext）
            TeammateContext teammateContext = TeammateContext.create(
                new TeammateContext.TeammateConfig(
                    agentId, config.name(), config.teamName(), config.color(),
                    config.planModeRequired(), parentSessionId, abortController));

            // description = `${name}: ${prompt.substring(0,50)}...`（CC :155）
            String prompt = config.prompt() != null ? config.prompt() : "";
            String description = config.name() + ": "
                + (prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt);

            // taskState 全量初始化（CC :157-180）
            InProcessTeammateTaskState taskState = new InProcessTeammateTaskState(
                taskId, identity, prompt, config.model(),
                false, // awaitingPlanApproval（CC :170）
                config.planModeRequired() ? "plan" : "default", // permissionMode（CC :173）
                null, // error
                new ArrayList<>(), // messages（CC :179）
                new HashSet<>(),   // inProgressToolUseIDs
                new ArrayList<>(), // pendingUserMessages（CC :178）
                false, // isIdle（CC :174）
                false, // shutdownRequested（CC :175）
                0,     // lastReportedToolCount（CC :176）
                0,     // lastReportedTokenCount（CC :177）
                abortController,
                null, // currentWorkAbortController
                // [A2] unregisterCleanup · 对齐 CC spawnInProcess.ts:183-188 registerCleanup(() => abortController.abort())
                //   会话删除时经 registry().cleanupSession 调用 → abort 生命周期控制器，runner 线程
                //   （runTeammateLoop 轮循 isAborted）检测 abort 自然退出
                //   （CC "Task state will be updated by the execution loop when it detects abort"）。
                () -> {
                    if (log.isInfoEnabled()) {
                        log.info("[spawnInProcessTeammate] cleanup: abort teammate {} (taskId={})", agentId, taskId);
                    }
                    abortController.abort();
                },
                new ArrayList<>() // onIdleCallbacks
            );

            // 创建 + 接线 AutonomousAgentLoop（生产调用方，R1 阻断项）
            AutonomousAgentLoop loop = new AutonomousAgentLoop();
            loop.setAgentId(agentId);
            loop.setAgentName(config.name());
            loop.setTeamName(config.teamName());
            loop.setTaskId(taskId);
            loop.setTaskListId(parentSessionId);
            loop.setAbortController(abortController);
            loop.setModel(config.model());
            loop.setTaskState(taskState);
            if (taskFrameworkService != null) loop.setTaskFrameworkService(taskFrameworkService);
            if (sdkEventQueue != null) loop.setSdkEventQueue(sdkEventQueue);
            if (taskService != null) loop.setTaskService(taskService);
            if (subagentExecutor != null) loop.setSubagentExecutor(subagentExecutor);
            if (teamHelpers != null) loop.setTeamHelpers(teamHelpers);

            // W8-04 REWORK（反射器 E4）: 生产接线 outboundSink —— teammate 终端转换（completed/
            // failed/killed）产出的 task_status attachment 经 sink 落父会话消息库，使 GET /messages
            // 折叠链有真实 in_process_teammate 输入（否则附件被丢弃，折叠链在生产为 no-op 透传）。
            if (messageService != null) {
                loop.setOutboundSink(dto -> {
                    try {
                        messageService.appendMessage(dto);
                    } catch (Exception e) {
                        // [Fix C] 诊断可观测性：MyBatis-Flex/SQLite 包装异常 getMessage() 常为 null/空
                        //   （主工作区 20:33 实证 e.getMessage() 空）——补异常类名便于定位根因
                        //   （messages.role NOT NULL 违反 / session_id FK）。
                        log.warn("[spawnInProcessTeammate] teammate 终端 task_status attachment 落库失败 "
                            + "agent={} ({})：{}", agentId, e.getClass().getSimpleName(), e.getMessage());
                    }
                });
                log.info("[spawnInProcessTeammate] 已接线 outboundSink → MessageService.appendMessage "
                    + "（teammate 终端 task_status 通知链落库） agent={}", agentId);
            }

            // registerTask 桥接 + loop 注册（CC :191 → framework.ts:77-117）；
            // toolUseId 透传（CC spawnInProcess.ts:162 context.toolUseId）
            String toolUseId = context != null ? context.toolUseId() : null;
            // Fix 0（NPE）: Spring 路径走无参构造（:74-76），registry 字段为 null —— 直接字段访问
            //   registry.register 必 NPE（主工作区 20:33 前实证）。改用 registry() 惰性创建（:82-94）：
            //   Spring 字段注入完成后 taskFrameworkService 已就绪，首次调用才建 InProcessTeammateTaskRegistry。
            registry().register(taskState, loop, toolUseId);
            log.info("[spawnInProcessTeammate] Registered {} in task store (taskId={})", agentId, taskId);

            // [Batch2 S1] appendTeamMember 写 config.json members（Java 保留该持久化供
            //   TeamDiscovery（读 members）/ 广播（SendMessageTool listMemberNames）/ TeamDelete
            //   （活跃成员守卫）可见 —— 否则 spawn 的 teammate 不可见（探查 S1 断链）。
            //   [team-panel-backend-bugfix2 修正注释] CC 真源为 spawnMultiAgent.ts:988-993
            //   handleSpawnInProcess 内联 teamFile.members.push + writeTeamFileAsync（:995-1009），
            //   非「已移除 appendTeamMember」；team 文件缺失时 CC 抛错（'Team ... does not exist.
            //   Call spawnTeam first'）。Java 以 appendTeamMember 命名等价实现，append 失败不阻断
            //   spawn（放宽为 warn，Batch2 S1 设计决策记录差异）。
            //   Fix B：appendTeamMember 返回 false（team 不存在 / 无 members 数组）必须明确打 warn
            //   「未写入」，禁止再谎报「已写 config.json members」（主工作区 20:33 根因链误导）。
            if (teamHelpers != null) {
                try {
                    boolean appended = teamHelpers.appendTeamMember(config.teamName(),
                        new TeamHelpers.TeamMemberRef(agentId, config.name(), config.agentType(),
                            config.model(), config.prompt(), config.color(), config.planModeRequired(),
                            "in-process", resolveSpawnCwd(config), "in-process"));
                    if (appended) {
                        log.info("[spawnInProcessTeammate] 已写 config.json members: agentId={} team={}",
                            agentId, config.teamName());
                        // [team-panel-backend-bugfix2] 成员加入事件 + 会话级 team_context.teammates 同步
                        //   member_joined：Java Web 事件扩展触发点（对齐 TeamController.addMember:260）；
                        //   syncTeamContextTeammates：对齐 CC spawnMultiAgent.ts:974-982 spawn 并入 teammates。
                        if (teamStatusPublisher != null) {
                            teamStatusPublisher.publish(config.teamName(), "member_joined");
                        }
                        teamHelpers.syncTeamContextTeammates(config.teamName());
                    } else {
                        log.warn("[spawnInProcessTeammate] 未能写入 config.json members: team={} "
                            + "不存在或无 members 数组（CC spawnMultiAgent.ts:988-993 抛错；Java 放宽为 "
                            + "warn 不阻断 spawn）", config.teamName());
                    }
                } catch (Exception e) {
                    log.warn("[spawnInProcessTeammate] appendTeamMember 异常 agent={}: {}",
                        agentId, e.getMessage());
                }
            }

            // 启动运行循环（fire-and-forget，对齐 CC InProcessBackend.spawn startInProcessTeammate）。
            // GAP-R1: runner 线程全程包 runWithTeammateContext —— 对齐 CC inProcessRunner.ts:1160
            //   `runWithTeammateContext(teammateContext, () => runAgent(...))`（teammateContext
            //   于 spawnInProcess.ts:139-147 createTeammateContext 构造，与 taskState/loop 同批）。
            //   使 runner 线程（runTeammateLoop 全程）持 teammate 上下文；
            //   工具执行线程的上下文由 StreamingToolExecutor.executeAsync 捕获传播
            //   （ThreadLocal 不跨线程，Java 需手动桥接 AsyncLocalStorage 的自动传播语义）。
            // [reqId MDC 传播] 调度线程（工具池线程，经 StreamingToolExecutor 回放已含父 MDC）捕获
            //   MDC context map → teammate runner 线程回放（实测：logback MDC 不随 new Thread 继承，
            //   须显式回放）。WHY: runner 线程（runTeammateLoop→SubagentExecutor.executeStreaming）
            //   RequestContext.requestId()=null → isTodoV2Enabled()=false → teammate 子代理回落 V1
            //   TodoWrite、父 V2/子 V1 工具集分叉（决策 #65 在 team 路径重演）。回放后 runner 线程
            //   同帧 requestId 可见。
            final java.util.Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
            Thread runner = new Thread(() -> {
                    // [reqId MDC 传播] 线程体开头回放父 MDC → 任务结束 restore 线程原值（成对，防泄漏）。
                    java.util.Map<String, String> prevRunnerMdc = MDC.getCopyOfContextMap();
                    if (mdcCtx != null) {
                        MDC.setContextMap(mdcCtx);
                    }
                    try {
                        // void 兼容：runWithTeammateContext 返回值丢弃（Runnable 块 lambda 不可 return）。
                        TeammateContext.runWithTeammateContext(teammateContext,
                            () -> {
                                loop.runTeammateLoop(prompt);
                                return null;
                            });
                    } finally {
                        // [reqId MDC 传播] 成对 restore 线程原值（null → 清理，防线程复用泄漏）。
                        if (prevRunnerMdc != null) {
                            MDC.setContextMap(prevRunnerMdc);
                        } else {
                            MDC.clear();
                        }
                    }
                },
                "teammate-" + agentId);
            runner.setDaemon(true);
            runner.start();
            log.info("[spawnInProcessTeammate] Started agent execution for {}", agentId);

            return new InProcessSpawnOutput(true, agentId, taskId, abortController, teammateContext, null);
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error during spawn";
            log.warn("[spawnInProcessTeammate] Failed to spawn {}: {}", agentId, errorMessage);
            return new InProcessSpawnOutput(false, agentId, taskId, null, null, errorMessage);
        }
    }

    /**
     * 解析 teammate cwd · 对齐 CC spawnMultiAgent.ts:337 {@code workingDir = cwd || getCwd()}。
     *
     * <p>config.cwd() 非 null 优先（spawn 输入显式指定）；缺省取会话 cwd（对齐
     * TeamCreateTool.leadCwd 同款，CwdResolution.getCwd(RequestContext.sessionId())）；
     * 无 sessionId 回落 user.dir。
     */
    private static String resolveSpawnCwd(InProcessSpawnConfig config) {
        if (config.cwd() != null && !config.cwd().isBlank()) {
            return config.cwd();
        }
        String cwd = com.nexusai.application.agent.agent.CwdResolution.getCwd(com.nexusai.common.RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }
}
