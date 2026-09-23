package com.nexusai.application.agent.team;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.SessionPermissionOverlay;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.common.SessionKeys;
import com.nexusai.infra.util.AbortControllerFactory;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
 *   <li><b>TeammateIdentity</b>（spawnInProcess.ts:128-135；[S1-T4] 原先并行的 ThreadLocal
 *       运行时载体已删除，身份只此一份纯数据）</li>
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
     * [T1-A2] 会话 mapper · 供 teammate 的「最小父 TUC」挂 Leader <b>会话列活读桥</b>
     * （{@link SessionPermissionOverlay#leaderAppStateReader}）。
     *
     * <p>可选注入：未注入（单测直构 / 早期启动）⇒ 读桥恒空快照 + 本类 <b>WARN</b> 留痕
     * （⛔ 不静默 —— 静默的后果正是「批准了却还弹」这个用户可见缺陷）。
     */
    @Autowired(required = false) private SessionMapper sessionMapper;

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

    /** 测试/接线用 setter（sessionMapper · Leader 会话列活读桥）. */
    public void setSessionMapper(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
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
     *
     * <p>[S1-T4] 原 {@code teammateContext} 组件（ThreadLocal 运行时载体槽）已删：该槽全仓
     * <b>只写不读</b>（{@code .teammateContext()} 0 命中），身份数据由 {@code taskState.identity()}
     * 承载（纯数据、随 taskState 显式传递）。
     */
    public record InProcessSpawnOutput(
        boolean success,
        String agentId,
        String taskId,
        AbortControllerFactory.AbortControllerRef abortController,
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
     *   <li>TeammateIdentity（:128-135；[S1-T4] 原 ThreadLocal 运行时载体已删）</li>
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

        // parentSessionId = Leader's session（CC :125 getSessionId）
        // ── [fail-loud · 2026-09-22 用户裁定] 取不到 Leader 真实会话键 ⇒ 直接抛，⛔ 不回落 ──
        // WHY（改前缺陷 · 已读码复核）: 改前本行是
        //   `parentSessionId = context.parentSessionId() != null ? ... : TaskService.getTaskListId(null, null)`。
        //   TaskService.getTaskListId 的最终回退是**进程级共享 UUID**（PROCESS_SESSION_ID，TaskService:335，
        //   永不返回 null/blank），中间还可能命中 nexusai.team.name（**team 名**）/ nexusai.taskListId
        //   （任务列表键）—— 于是「真 Leader 会话取不到」（config.json 无 leadSessionId / 读取失败 /
        //   SubagentTool 侧 ctx.sessionId() 为 null）被静默替换成一个**看起来像会话的伪造键**喂下去：
        //   TeammateIdentity.parentSessionId / loop.setTaskListId / teammate 执行 TUC 的 sessionId
        //   全挂在该幻影键上 ⇒ CwdResolution / SessionStorage 按「未知会话」在**链路深处**抛
        //   （报错点离根因很远、无「Leader 会话缺失」上下文），权限/transcript/file-history 亦锚到
        //   不存在的会话。用户裁定：「取不到就报错」「⛔ 不许回落到进程 UUID / team 名 / 任何看起来像
        //   会话的伪造值」。
        // 位置：**在 try 之前**抛出（⛔ 不能被本方法末尾的 catch (Exception) 吞成
        //   InProcessSpawnOutput(false,...)）—— 调用方须真实看到异常：
        //   Agent 工具路径 = SubagentTool.spawnTeammate（异常穿出 tool.execute → StreamingToolExecutor
        //   catch(Throwable) 转 ToolResult.error 给模型）；REST 路径 = TeamController.spawnMember
        //   （catch → ConflictException → 409，沿用该端点既有「spawn 失败 = 409」契约）。
        // 边界：只拦 teammate 这一条链；「确无会话」的合法降级路径（入站 MCP / 无会话 plan provider /
        //   workflow worker / standalone fork 子代理）仍走 SessionKeys.NO_SESSION + CwdResolution 命名出口。
        String parentSessionId = requireLeaderSessionId(context, config);

        try {
            // 独立 AbortController（不受 leader 中断，CC :122）
            AbortControllerFactory.AbortControllerRef abortController = AbortControllerFactory.create();

            // ── [T1 · 消除 no-session] Leader 归属（sessionId + cwd）物化为「最小父 TUC」 ──
            // WHY（改前断链）: 本条链上 Leader 归属**本来就算出来了**，但只喂给「元数据 / 任务键」——
            //   parentSessionId 只进 TeammateIdentity（:240）与 loop.setTaskListId（:285，语义是任务列表
            //   桶键、不是 TUC.sessionId），leaderCwd 只作 appendTeamMember 的第 8 实参（算完即丢）。
            //   而 AutonomousAgentLoop.runOneTurn 调 teammate 入口时不带父 TUC ⇒
            //   SubagentExecutor.effectiveParentTuc == null ⇒ createSubagentContext.create(null, ...) 走
            //   hasParent=false 的 standalone 分支 ⇒ sessionId 置 SessionKeys.NO_SESSION 哨兵 ⇒
            //   transcript 目录 / effectiveCwd / 权限会话规则 / file-history / hook 桶**全部挂在幻影会话键**上
            //   （teammate 读写用户项目文件判「在工作目录之外」、授权落不到任何真实会话）。
            // 改法（对齐 CC/CCB 形态）: CCB inProcessRunner.ts:892-902 / :1197-1200 的 teammate **直接复用
            //   Leader 的 toolUseContext**（CC 一进程=一会话，sessionId/cwd 天生继承）；本仓一 JVM 多会话，
            //   该「继承」必须显式落在 TUC 快照上（同 SubagentExecutor.java:2297-2302 注释自述的取舍）
            //   ⇒ 这里把已算好的 (LeaderSessionId, LeaderCwd) 装成一个**最小父 TUC**，仅承载归属。
            // ⛔ 不伪造 identity: agentId 传 null（本 TUC 不是某个 agent 的身份；子代理 agentId 由
            //   create() 的 overrides 覆盖，父 agentId 不被 ToolUseContext.with() 读取）。
            // ⛔ 不用 ThreadLocal / 全局单例（会话态一律显式传参）：载体 = AutonomousAgentLoop 的实例字段，
            //   而该 loop 恒由本方法 `new`（1 loop = 1 teammate，生产无 Spring 注入点）。
            // ⛔ 不硬删 NO_SESSION 哨兵: 它仍服务入站 MCP / 无会话 plan provider / workflow worker 等
            //   合法降级路径（grep SessionKeys.NO_SESSION 多处消费）——本改动只让 **teammate 这条路**
            //   不再走到它。
            // [fail-loud] 走到这里 parentSessionId 必为**真实会话键**（requireLeaderSessionId 已把关）
            //   ⇒ leaderParentTuc 无条件构造（原「无会话 → null → 下游 NO_SESSION 降级」分支已删：
            //   那条降级正是本任务要消除的幻影会话键来源）。
            String leaderCwd = resolveSpawnCwd(config, parentSessionId);
            // effectiveCwd 显式钉为上面已算好的 leaderCwd（⛔ 不在下游另起一套路径算法；
            //   ToolUseContext 规范构造器的兜底 CwdResolution.getCwd(sessionId) 与 resolveSpawnCwd
            //   是同一个函数，此处显式传入还额外覆盖「config.cwd() 显式指定 cwd」的场景）。
            // ── [T1-A2 · 2026-09-22] 再挂 Leader 会话列活读桥（getAppState）──
            // WHY: 会话列（sessions.session_permission_rules）的**唯一生产读点**挂在
            //   LlmAgentLoop.doRun（:3443-3457 → appStateRef），teammate 不走那条链 ⇒ 改前它的父 TUC
            //   getAppState 是紧凑构造器兜底的恒等函数（ToolUseContext.java:469-470）⇒
            //   AgentLoopContext.mergeAppStatePermissionRules（:1604-1607）恒早退 ⇒
            //   「用户弹窗批准 → 下一轮同一工具调用仍弹」（= 用户最初的抱怨形态）。
            //   修法 = 给父 TUC 挂上与主会话**同语义**的读桥（读桥本体 =
            //   SessionPermissionOverlay.leaderAppStateReader，与 doRun 读同一份列解码）。
            //   ⛔ 必须活读（批准发生在 spawn 之后是常态），⛔ 不新增第二套状态机制。
            if (sessionMapper == null) {
                log.warn("[spawnInProcessTeammate] sessionMapper 未注入 ⇒ teammate 的父 TUC 无 Leader 会话列"
                    + "读桥：本轮起 SESSION 档授权（本会话允许编辑/允许读某目录）对 teammate **不可见**"
                    + "（批准后仍会再弹）—— agent={} leaderSession={}", agentId, parentSessionId);
            }
            ToolUseContext leaderParentTuc = buildLeaderParentTuc(parentSessionId, leaderCwd);

            // identity（纯数据载体）
            TeammateIdentity identity = new TeammateIdentity(
                agentId, config.name(), config.teamName(), config.color(),
                config.planModeRequired(), parentSessionId);

            // [S1-T4] 原 ThreadLocal 运行时载体（create 分支）已删：身份唯一载体 =
            //   上方 identity（纯数据）；abortController 亦已由 taskState.abortController 承载
            //   （生命周期同一对象，无信息丢失）。

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
            // [T1 · 消除 no-session] 把 Leader 归属交给运行循环 → runOneTurn 作为父 TUC 透传
            //   teammate 命名入口 executeTeammateTurn ⇒ createSubagentContext.create 走 hasParent
            //   分支继承 sessionId / effectiveCwd（详见本方法内 leaderParentTuc 构造处注释）。
            loop.setLeaderParentTuc(leaderParentTuc);
            // 数据流日志（CLAUDE.md 编码后必须添加数据流日志 · 中文）：一眼可见 teammate 的会话归属
            if (log.isInfoEnabled()) {
                log.info("[spawnInProcessTeammate] Leader 归属已注入 teammate 执行上下文（数据流）: "
                    + "agent={} leaderSession={} leaderCwd={}（teammate 执行 TUC 将继承 sessionId/effectiveCwd；"
                    + "[fail-loud] leaderSession 恒为真实会话键 —— 取不到时本方法已在入口抛 "
                    + "MissingLeaderSessionException，此处不存在 null/伪造值）",
                    agentId, parentSessionId, leaderCwd);
            }
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
                            // [T1] 复用方法开头已算好的 leaderCwd（原为内联 resolveSpawnCwd 调用 =
                            //   算完即丢，正是本任务定位的 Leader cwd 丢弃点）。
                            "in-process", leaderCwd, "in-process"));
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
            // [S1-T4] 原 GAP-R1 的「线程包裹 teammate 上下文 → runTeammateLoop」
            //   线程包裹已删：身份不再经 ThreadLocal 承载 —— 唯一来源是 taskState.identity()
            //   （纯数据），由 AutonomousAgentLoop 显式传给下游（TaskService.getTaskListId /
            //   SubagentExecutor 的 TUC 装配链），无需任何跨线程回放（用户铁律：回放不算合规）。
            Thread runner = new Thread(() -> loop.runTeammateLoop(prompt),
                "teammate-" + agentId);
            runner.setDaemon(true);
            runner.start();
            log.info("[spawnInProcessTeammate] Started agent execution for {}", agentId);

            return new InProcessSpawnOutput(true, agentId, taskId, abortController, null);
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error during spawn";
            log.warn("[spawnInProcessTeammate] Failed to spawn {}: {}", agentId, errorMessage);
            return new InProcessSpawnOutput(false, agentId, taskId, null, errorMessage);
        }
    }

    /**
     * <b>取 Leader 真实会话键 · 取不到即 fail-loud</b>（2026-09-22 用户裁定：teammate 这条路上
     * 取不到会话就报错，⛔ 不回落到任何「看起来像会话」的伪造值）。
     *
     * <p><b>四条拒绝判据（顺序即优先级；任一命中即抛 {@link MissingLeaderSessionException}）</b>：
     * <ol>
     *   <li><b>null/空白</b> —— 改前由 {@code TaskService.getTaskListId(null, null)} 顶替（最终回退
     *       进程级共享 UUID）。触发源：team config.json 无 {@code leadSessionId} / 反查失败 /
     *       SubagentTool 侧 {@code ctx.sessionId()} 为 null。</li>
     *   <li><b>{@link SessionKeys#NO_SESSION} 哨兵</b> —— 「确无会话」哨兵是**合法降级路径**的标记，
     *       不是会话键；把它当 Leader 会话喂下去 = 幻影会话桶（同一类伪造，只是形态显式）。</li>
     *   <li><b>{@code TaskService} 的进程级兜底 UUID</b> —— 即改前回落的那个值本身。它可能已经被
     *       <b>上游落盘</b>（如旧版 TeamCreateTool 在无会话时把 cleanupKey 当 leadSessionId 写进
     *       config.json）⇒ 仅在入口判 null 拦不住，必须按值识别（{@link TaskService#isFallbackProcessSessionId}）。</li>
     *   <li><b>等于本 team 的 team 名</b> —— {@code TaskService.getTaskListId} 优先级 3 会返回
     *       {@code nexusai.team.name}（**team 名**），改前同样可能被当成 sessionId 喂下去。</li>
     * </ol>
     *
     * <p>⛔ 本方法<b>不</b>校验「该会话在 DB 里真实存在」：那属 SessionStorage/CwdResolution 的
     * 职责（会按「未知会话」fail-loud 抛），且部分合法链路（合成会话/测试夹具）本就不落 DB。
     * 本条只保证「喂给 teammate 的不是伪造值」。
     *
     * @param context 调用方传入的 spawn 上下文（Leader 会话的来源）
     * @param config  spawn 配置（仅用于把 team 名纳入判据 + 错误信息上下文）
     * @return 真实会话键（非 null/非空白、非哨兵、非进程兜底 UUID、非 team 名）
     * @throws MissingLeaderSessionException 取不到真实会话键（含环节/期望/实际/如何修 四要素）
     */
    private static String requireLeaderSessionId(SpawnContext context, InProcessSpawnConfig config) {
        String candidate = context != null ? context.parentSessionId() : null;
        String link = "SpawnInProcess.spawnInProcessTeammate（teammate 启动的 Leader 会话解析）";
        String expected = "一个真实 Lead 会话键（显式载体：ToolUseContext.sessionId() / "
            + "team config.json leadSessionId）";
        String fix = "由调用方显式传入真实会话键：Agent 工具路径 = ToolUseContext.sessionId()"
            + "（须非空，⛔ 不得经 TaskService.getTaskListId 兜底）；REST 路径 = 建 team 时"
            + "TeamCreateTool 落盘的 config.json leadSessionId（由会话内 ctx.sessionId() 写入）。";

        if (candidate == null || candidate.isBlank()) {
            throw new MissingLeaderSessionException(link, expected,
                "null/空白（SpawnContext=" + context + "，teamName=" + config.teamName()
                    + "）—— 改前此处会回落 TaskService.getTaskListId(null, null) 的进程级共享 UUID",
                fix);
        }
        if (SessionKeys.isNoSession(candidate)) {
            throw new MissingLeaderSessionException(link, expected,
                "SessionKeys.NO_SESSION 哨兵（\"no-session\"）—— 哨兵是「确无会话」标记，不是会话键",
                fix);
        }
        if (TaskService.isFallbackProcessSessionId(candidate)) {
            throw new MissingLeaderSessionException(link, expected,
                "TaskService 进程级兜底 UUID " + candidate + "（= 改前本路径的回落值；"
                    + "多会话 JVM 下为全进程共享，可能已被上游落盘进 config.json）",
                fix);
        }
        if (config.teamName() != null && !config.teamName().isBlank()
                && config.teamName().equals(candidate)) {
            throw new MissingLeaderSessionException(link, expected,
                "team 名 \"" + candidate + "\"（TaskService.getTaskListId 优先级 3 的 nexusai.team.name "
                    + "返回值，不是会话键）",
                fix);
        }
        return candidate;
    }

    /**
     * 解析 teammate cwd · 对齐 CC spawnMultiAgent.ts:337 {@code workingDir = cwd || getCwd()}。
     *
     * <p>config.cwd() 非 null 优先（spawn 输入显式指定）；缺省取会话 cwd（对齐
     * TeamCreateTool.leadCwd 同款，{@code CwdResolution.getCwd(sessionId)}）；
     * 无 sessionId 回落 user.dir。
     *
     * <p>[批 3c] 会话来源显式化：sessionId 由调用方 {@code spawnInProcessTeammate} 从
     * {@link SpawnContext#parentSessionId()} 显式传入（= Leader 会话）；⛔ 不再读 MDC
     * （teammate 线程为 {@code new Thread}，ThreadLocal/MDC 不跨线程）。
     *
     * @param sessionId 当前会话 id（可 null/空白 → 回落 user.dir）
     */
    private static String resolveSpawnCwd(InProcessSpawnConfig config, String sessionId) {
        if (config.cwd() != null && !config.cwd().isBlank()) {
            return config.cwd();
        }
        String cwd = com.nexusai.application.agent.agent.CwdResolution.getCwd(sessionId);
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }

    // ════════════════════════════════════════════════════════════════════
    // [T1] teammate 的「最小父 TUC」构造（sessionId + effectiveCwd + 会话列读桥）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 构造 teammate 的 Leader 归属父 TUC（<b>生产唯一入口</b>）= 本实例的 {@code sessionMapper}
     * 经 {@link SessionPermissionOverlay#leaderAppStateReader} 变成会话列活读桥 → 交给
     * {@link #buildLeaderParentTuc(String, String, Function)} 装 TUC + 钉 effectiveCwd。
     *
     * <p>把「读桥构造」收进本方法（而不是散在 {@code spawnInProcessTeammate} 里）的理由：
     * 单测可用 {@code setSessionMapper} 注入假 mapper 后<b>逐字调用生产同一条链</b>
     * （无需启动 spawn 线程 / LLM）⇒ 若有人把读桥摘掉/换成恒等（= 用户抱怨的缺陷形态），
     * 用例立刻变红（已实测），而不是靠「读代码觉得对」。
     *
     * @param leaderSessionId Leader 会话键（真实键，由 {@link #requireLeaderSessionId} 把关）
     * @param leaderCwd       Leader 工作目录（已解析）
     */
    ToolUseContext buildLeaderParentTuc(String leaderSessionId, String leaderCwd) {
        return buildLeaderParentTuc(leaderSessionId, leaderCwd,
            SessionPermissionOverlay.leaderAppStateReader(
                sessionMapper, leaderSessionId, PermissionMode.DEFAULT));
    }

    /**
     * 构造 teammate 的 Leader 归属父 TUC = {@link #minimalLeaderParentTuc} + 显式钉 effectiveCwd。
     *
     * <p>抽取为 package-private static 的理由（本仓既有惯例，见 {@code SubagentExecutor.withEffectiveCwd}）：
     * 单测可<b>与生产逐字同源</b>地构造该 TUC（无需启动 spawn 线程 / LLM），从而确定性断言
     * 「会话列规则能被 teammate 的 per-turn permCtx 读到」；若日后有人把 getAppState 读桥摘掉
     * （或把 withEffectiveCwd 改回置 null），用例立刻变红。
     *
     * @param leaderSessionId      Leader 会话键（真实键，由 {@link #requireLeaderSessionId} 把关）
     * @param leaderCwd            Leader 工作目录（已解析）
     * @param leaderAppStateReader Leader 会话列活读桥（{@code getAppState}）
     */
    static ToolUseContext buildLeaderParentTuc(String leaderSessionId, String leaderCwd,
            Function<Map<String, Object>, Map<String, Object>> leaderAppStateReader) {
        // [T1-A1 承重] withEffectiveCwd 必须**保留** source.getAppState()（本批已改），
        //   否则这里挂好的读桥会在派生时被置 null ⇒ 恒等函数 ⇒ 合并恒早退。
        return SubagentExecutor.withEffectiveCwd(
            minimalLeaderParentTuc(leaderSessionId, leaderAppStateReader),
            Paths.get(leaderCwd));
    }

    /**
     * 「最小父 TUC」：仅承载 <b>Leader 归属</b>（sessionId + getAppState 读桥），
     * ⛔ 不承载 agent 身份 / 工具面（那些由 {@code createSubagentContext.create} 的 overrides 决定）。
     *
     * <p>为什么用 20 参便利工厂（{@code ToolUseContext.of(..., getAppState, ...)}，即
     * 「R32-b15 Stage 3.2 C2 4 桥接字段」重载）：父 TUC 需要一个**非恒等**的 {@code getAppState}
     * ——这是会话级授权（SESSION 档）进入 teammate per-turn permCtx 的唯一通道
     * （{@code AgentLoopContext.mergeAppStatePermissionRules} :1604）；该重载正是为此保留的
     * C2 桥接工厂（其余 43 个字段由紧凑构造器兜底）。
     *
     * <p>⛔ agentId 传 null：本 TUC 不是某个 agent 的身份（子代理 agentId 由 create() 的 overrides
     * 覆盖；父 agentId 不被 {@code ToolUseContext.with()} 读取）。
     */
    private static ToolUseContext minimalLeaderParentTuc(String leaderSessionId,
            Function<Map<String, Object>, Map<String, Object>> leaderAppStateReader) {
        return ToolUseContext.of(
            null,                          // agentId（本 TUC 只承载归属，不是身份）
            leaderSessionId,               // sessionId = Leader 会话
            PermissionMode.DEFAULT,        // mode（TUC.mode 非权限档；权限档走 permCtx）
            List.of(),                     // availableTools（不进工具执行面）
            "",                            // taskListId
            AbortController.NOOP,          // abortController（执行用 abort 由 teammate loop 持有）
            List.of(),                     // messages
            null,                          // permissionContext
            PermissionMode.DEFAULT,        // permissionMode
            Map.of(),                      // mcpClients
            false,                         // isNonInteractiveSession
            "",                            // renderedSystemPrompt
            null,                          // effectiveCwd（由 withEffectiveCwd 显式钉为 leaderCwd）
            null,                          // inProgressToolUseIDs
            null,                          // toolDecisions
            null,                          // onCompactProgress
            leaderAppStateReader,          // ★ getAppState = Leader 会话列活读桥
            null,                          // setAppState（写通道本批不改，见 SubagentExecutor.withEffectiveCwd 注释）
            null,                          // setStreamMode
            null);                         // setSDKStatus
    }
}
