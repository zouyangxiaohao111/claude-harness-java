package com.nexusai.application.agent.team;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-01 · InProcessTeammateTaskState/TeammateIdentity 载体 + SpawnInProcess 生产化。
 *
 * <p><b>WHY（规则九）</b>：
 * <ul>
 *   <li><b>taskId 't' 前缀</b>（CC spawnInProcess.ts:113 generateTaskId('in_process_teammate')
 *       → Task.ts:98-105 TASK_ID_PREFIXES in_process_teammate='t'）：taskId 前缀是 teammate 与
 *       bash('b')/agent('a') 等任务类型区分的唯一稳定标记（stopTask 分发 / 清理逻辑依赖），
 *       若前缀错则 kill 时无法按类型路由。</li>
 *   <li><b>permissionMode = planModeRequired ? 'plan' : 'default'</b>（CC spawnInProcess.ts:173）：
 *       计划模式必填的 teammate 若初始 permissionMode 不是 'plan'，首轮工具调用会绕过 plan
 *       门禁直接执行（S-3 安全语义）。</li>
 *   <li><b>registerTask 桥接</b>（CC framework.ts:77-117）：spawn 必须把 teammate 状态注册进
 *       BackgroundTask 状态层（task_started SDK 书签 + kill/complete/fail 状态机可见），否则
 *       kill(taskId) 找不到任务、SDK 无开始事件。</li>
 *   <li><b>生产调用方</b>（R1 阻断项）：runTeammateLoop/kill/complete/fail 必须有生产调用方
 *       （grep AutonomousAgentLoop 非自身 ≥1），否则状态机是死代码——本测试验证 spawn 真实
 *       创建并接线 AutonomousAgentLoop 实例。</li>
 * </ul>
 */
@DisplayName("W8-01 · spawn 生产化（状态载体 + SpawnInProcess.spawnInProcessTeammate）")
class InProcessTeammateSpawnTest {

    @TempDir
    Path tempDir;

    /** 记录 updateTaskState 最后一次写入的 terminal state（kill 后 eager evict 前捕获） */
    static final class RecordingFramework extends TaskFrameworkService {
        final List<BackgroundTask> writes = new ArrayList<>();
        RecordingFramework(SdkEventQueue sdkQueue) { super(sdkQueue); }
        @Override
        public void updateTaskState(String taskId, BackgroundTask newState) {
            writes.add(newState);
            super.updateTaskState(taskId, newState);
        }
    }

    private RecordingFramework taskFrameworkService;
    private SpawnInProcess spawner;
    private final List<String> spawnedTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        taskFrameworkService = new RecordingFramework(new SdkEventQueue());
        spawner = new SpawnInProcess(taskFrameworkService);
    }

    @AfterEach
    void tearDown() {
        // 终止后台 runTeammateLoop 线程，避免跨测试泄漏
        for (String taskId : spawnedTaskIds) {
            spawner.registry().kill(taskId);
        }
        System.clearProperty("nexusai.task.config-dir");
        TaskSystemConfig.clearForTest();
    }

    private SpawnInProcess.InProcessSpawnOutput spawn(
            String name, String teamName, String prompt, boolean planModeRequired) {
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig(name, teamName, prompt,
                null, planModeRequired, null),
            new SpawnInProcess.SpawnContext("session-1", "tool-use-1"));
        spawnedTaskIds.add(out.taskId());
        return out;
    }

    /** 在日志线程（= teammate runner 线程）内直接读 RequestContext 的探针 appender。 */
    private static final class MdcRecordingAppender extends AppenderBase<ILoggingEvent> {
        final CopyOnWriteArrayList<Recorded> records = new CopyOnWriteArrayList<>();
        @Override protected void append(ILoggingEvent event) {
            // append() 在产生日志的线程同步执行（logback 默认同步）—— teammate runner 线程日志 =
            //   runner 线程当前 MDC（RequestContext.requestId() 直读，不依赖 event MDC 捕获语义）。
            records.add(new Recorded(
                Thread.currentThread().getName(),
                RequestContext.requestId(),
                RequestContext.sessionId()));
        }
    }

    private record Recorded(String threadName, String reqId, String sessionId) {}

    @Test
    @DisplayName("[reqId MDC 传播] teammate runner 线程继承父 sessionId+reqId（决策 #65 在 team 路径重演）")
    void spawn_teammateRunnerThread_inheritsParentRequestId() throws Exception {
        // WHY (决策 #65 父 V2/子 V1 工具集分叉 · team 路径): runTeammateLoop 在
        //   new Thread("teammate-*") 上执行（spawnInProcessTeammate），logback MDC 不随 new Thread
        //   继承（实测）→ 修复前 runner 线程 RequestContext.requestId()=null → teammate 子代理
        //   （SubagentExecutor.executeStreaming）回落 V1 TodoWrite、父 V2/子 V1 工具集分叉。
        //   修复 = spawn 调度线程（父，经 StreamingToolExecutor 回放已含 MDC）捕获 MDC context map
        //   → runner 线程体开头回放 + finally restore（对齐 SubagentTool async worker 同款模式）。
        //
        // <p><b>可观测 seam（规则九）</b>: runner 线程内 log.info("state ... → WORK")（transitionTo）
        //   与 log.debug("teammate 处理 prompt")(runTeammateLoop 首轮) 触发时，logback appender 在
        //   runner 线程同步执行 → 自定义 appender 在 append() 内直接读 RequestContext（= runner 线程
        //   MDC），据此断言 runner 线程 requestId/sessionId 已回放父值。
        Logger loopLogger = (Logger) LoggerFactory.getLogger(AutonomousAgentLoop.class);
        Level prevLevel = loopLogger.getLevel();
        loopLogger.setLevel(Level.DEBUG);
        MdcRecordingAppender appender = new MdcRecordingAppender();
        appender.start();
        loopLogger.addAppender(appender);
        try {
            RequestContext.set("sess-parent", "req-parent");
            SpawnInProcess.InProcessSpawnOutput out = spawn("researcher", "research-team", "do research", false);
            assertThat(out.success()).as("spawn 必须成功").isTrue();

            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            boolean sawTeammateWithParentMdc = false;
            while (Instant.now().isBefore(deadline) && !sawTeammateWithParentMdc) {
                sawTeammateWithParentMdc = appender.records.stream().anyMatch(r ->
                    r.threadName().startsWith("teammate-")
                        && "req-parent".equals(r.reqId())
                        && "sess-parent".equals(r.sessionId()));
                if (!sawTeammateWithParentMdc) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }

            assertThat(sawTeammateWithParentMdc)
                .as("teammate runner 线程必须继承父线程 requestId/sessionId（MDC 回放）——"
                    + "否则 isTodoV2Enabled()=false → teammate 子代理回落 V1、父 V2/子 V1 工具集分叉（决策 #65 team 路径）")
                .isTrue();
        } finally {
            loopLogger.detachAppender(appender);
            loopLogger.setLevel(prevLevel);
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("spawnInProcessTeammate: taskId 't' 前缀 + agentId name@team + 注册 RUNNING（spawnInProcess.ts:112-113, framework.ts:77-117）")
    void spawn_registersTaskWithTPrefix() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("researcher", "research-team", "do research", false);

        assertThat(out.success()).as("spawn 必须成功").isTrue();
        assertThat(out.agentId()).as("agentId = name@team（spawnInProcess.ts:112 formatAgentId）")
            .isEqualTo("researcher@research-team");
        assertThat(out.taskId()).as("taskId 必须以 't' 前缀（Task.ts:98-105 TASK_ID_PREFIXES）")
            .startsWith("t");

        // registerTask 桥接：任务必须已注册进 BackgroundTask 状态层
        Optional<BackgroundTask> registered = taskFrameworkService.getTask(out.taskId());
        assertThat(registered).as("spawn 必须 registerTask（framework.ts:77-117）").isPresent();
        assertThat(registered.get().type()).isEqualTo(TaskType.IN_PROCESS_TEAMMATE);
        assertThat(registered.get().status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        assertThat(registered.get().toolUseId()).as("toolUseId 透传（spawnInProcess.ts:162 context.toolUseId）")
            .isEqualTo("tool-use-1");
    }

    @Test
    @DisplayName("permissionMode = planModeRequired ? 'plan' : 'default'（spawnInProcess.ts:173）")
    void spawn_setsPermissionModeByPlanModeRequired() {
        SpawnInProcess.InProcessSpawnOutput planOut = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("planner", "t", "plan", null, true, null),
            new SpawnInProcess.SpawnContext("s", null));
        spawnedTaskIds.add(planOut.taskId());
        assertThat(planOut.success()).isTrue();
        Optional<AutonomousAgentLoop> planLoop = spawner.registry().get(planOut.taskId());
        assertThat(planLoop).as("spawn 必须创建并注册 AutonomousAgentLoop").isPresent();
        assertThat(planLoop.get().taskState()).isNotNull();
        assertThat(planLoop.get().taskState().permissionMode()).as("planModeRequired=true → 'plan'")
            .isEqualTo("plan");

        SpawnInProcess.InProcessSpawnOutput defaultOut = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("worker", "t", "work", null, false, null),
            new SpawnInProcess.SpawnContext("s", null));
        spawnedTaskIds.add(defaultOut.taskId());
        Optional<AutonomousAgentLoop> defLoop = spawner.registry().get(defaultOut.taskId());
        assertThat(defLoop).isPresent();
        assertThat(defLoop.get().taskState().permissionMode()).as("planModeRequired=false → 'default'")
            .isEqualTo("default");
    }

    @Test
    @DisplayName("kill 生产可达：registry.kill(taskId) → 状态机 kill（spawnInProcess.ts:227-328）")
    void spawn_killReachesLoopStateMachine() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("worker", "t", "work", false);

        // spawn 必须创建 AutonomousAgentLoop 并注册（生产调用方 ≥1）
        Optional<AutonomousAgentLoop> loopOpt = spawner.registry().get(out.taskId());
        assertThat(loopOpt).as("spawn 必须创建并注册 AutonomousAgentLoop").isPresent();
        AutonomousAgentLoop loop = loopOpt.get();

        boolean killed = spawner.registry().kill(out.taskId());
        assertThat(killed).as("kill 必须真实转换状态机").isTrue();
        assertThat(loop.isAborted()).as("kill 必须 abort 生命周期控制器（spawnInProcess.ts:256）").isTrue();

        // 终态 + notified + endTime（spawnInProcess.ts:280-296）· eager evict 前捕获。
        // kill 返回 true ⇒ transitionToTerminal(KILLED) 已写入一条 KILLED+notified+endTime
        // terminal 状态（kill 路径保证，不受后台 runTeammateLoop 线程后续 complete() 竞争影响）。
        assertThat(taskFrameworkService.writes).as("kill 必须写入 terminal 状态").isNotEmpty();
        assertThat(taskFrameworkService.writes)
            .as("kill 路径必须写入 KILLED + notified:true + endTime（spawnInProcess.ts:280-296）")
            .anyMatch(t -> t.status() == BackgroundTaskStatus.KILLED
                && t.notified()
                && t.endTime() != null);
    }

    @Test
    @DisplayName("W8-04 REWORK E4: spawn 生产接线 outboundSink → MessageService.appendMessage（通知链闭环）")
    void spawn_wiresOutboundSinkToMessageService() throws Exception {
        // WHY: 反射器 E4 —— 之前 outboundSink 无生产接线，终端 task_status attachment 被丢弃，
        //      不进入会话消息库，折叠链 GET /messages 无 in_process_teammate 输入。本测试验证
        //      spawn 生产路径真实接线 sink → MessageService（complete 后落库）。
        com.nexusai.domain.session.MessageService ms =
            org.mockito.Mockito.mock(com.nexusai.domain.session.MessageService.class);
        spawner.setMessageService(ms);
        SpawnInProcess.InProcessSpawnOutput out = spawn("notify", "t", "work", false);

        // 验证 loop 的 outboundSink 已被 spawn 接线（反射器 E4 缺口：接线必须真实存在）
        Optional<AutonomousAgentLoop> loopOpt = spawner.registry().get(out.taskId());
        assertThat(loopOpt).as("spawn 必须创建并注册 AutonomousAgentLoop").isPresent();
        AutonomousAgentLoop loop = loopOpt.get();
        var sinkField = AutonomousAgentLoop.class.getDeclaredField("outboundSink");
        sinkField.setAccessible(true);
        assertThat(sinkField.get(loop)).as("spawn 生产路径必须接线 outboundSink（E4 反证）").isNotNull();

        // complete() → outboundSink → messageService.appendMessage（task_status attachment 落库）
        boolean advanced = spawner.registry().complete(out.taskId());
        assertThat(advanced).as("running 任务应转换成功").isTrue();
        org.mockito.Mockito.verify(ms, org.mockito.Mockito.atLeastOnce())
            .appendMessage(org.mockito.ArgumentMatchers.argThat(dto ->
                dto != null && "attachment".equals(dto.author())
                    && "task_status".equals(dto.subtype())
                    && dto.content() != null
                    && dto.content().contains("\"taskType\":\"in_process_teammate\"")
                    && dto.content().contains("\"status\":\"completed\"")));
    }

    @Test
    @DisplayName("[A2-FIX·05ebab75] cleanupSession 会话键匹配短形态 'sess-xxx'（删会话 abort teammate）")
    void cleanupSession_shortSessionKey_matchesShortParentSessionId() {
        // WHY: 05ebab75 会话 id 双形态统一为 short（'sess-xxx'）—— SessionService.delete 传 raw
        //   'sess-xxx'，identity.parentSessionId() 经 SubagentTool 直传 ctx.sessionId() 同为 short
        //   → cleanupSession 裸 String equals 必中（InProcessTeammateTaskRegistry:125-127 注释，
        //   原 A2-FIX canonicalUuid 归一化比较已删除）。若 parentSessionId 是短形态而 cleanupSession
        //   仍做 canonicalUuid 归一化（或反着来），删会话将漏 abort teammate（会话删除泄漏）。
        //   ⚠️ 本测试原版本断言「raw 'sess-xxx' 命中派生 UUID」——与 05ebab75 删除归一化的设计冲突，
        //   已修正为短形态相等匹配。
        String rawSession = "sess-abc12345";
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("researcher", "research-team", "do research", null, false, null),
            new SpawnInProcess.SpawnContext(rawSession, "tool-use-1"));
        spawnedTaskIds.add(out.taskId());

        // 另一会话的 teammate（不应被误 abort）
        SpawnInProcess.InProcessSpawnOutput other = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("other", "other-team", "other work", null, false, null),
            new SpawnInProcess.SpawnContext("sess-other", "tool-use-2"));
        spawnedTaskIds.add(other.taskId());

        int aborted = spawner.registry().cleanupSession(rawSession);

        assertThat(aborted).as("raw 'sess-xxx' 必须命中同短形态 parentSessionId 的 teammate（05ebab75 裸 equals）")
            .isEqualTo(1);
        AutonomousAgentLoop matched = spawner.registry().get(out.taskId()).orElseThrow();
        assertThat(matched.isAborted()).as("cleanup 必须 abort 生命周期控制器（spawnInProcess.ts:183-188）").isTrue();
        AutonomousAgentLoop unmatched = spawner.registry().get(other.taskId()).orElseThrow();
        assertThat(unmatched.isAborted()).as("其他会话 teammate 不得被误 abort").isFalse();
    }

    @Test
    @DisplayName("Fix 0: Spring 无参构造路径 registry() 惰性创建，spawn 不 NPE（直接 registry.register 字段访问修复）")
    void springNoArgPath_registryLazyInit_noNpe() {
        // WHY: Spring @Component 走无参构造（SpawnInProcess:74-76），registry 字段为 null —— 修复前
        //   spawnInProcessTeammate 直接 registry.register 必 NPE（主工作区 20:33 前实证）；既有测试
        //   全用 new SpawnInProcess(framework)（:68-71 预建 registry），覆盖不到 Spring 路径。
        //   修复 = registry().register（:82-94 惰性创建：首次调用才 new InProcessTeammateTaskRegistry）。
        //   变体（规则九）：无参构造 + reflection 注入 framework → spawn 不抛 NPE 且 registry 已惰性建好。
        SpawnInProcess springSpawner = new SpawnInProcess();  // 无参 = Spring 路径
        org.springframework.test.util.ReflectionTestUtils.setField(
            springSpawner, "taskFrameworkService", taskFrameworkService);

        SpawnInProcess.InProcessSpawnOutput out = springSpawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("researcher", "research-team", "do research", null, false, null),
            new SpawnInProcess.SpawnContext("session-1", "tool-use-1"));

        assertThat(out.success()).as("Spring 路径 spawn 不得因 registry NPE 失败（20:33 实证根因）").isTrue();
        assertThat(springSpawner.registry().get(out.taskId()))
            .as("registry 必须已惰性建好并注册 AutonomousAgentLoop")
            .isPresent();
        // 终止后台 runner 线程（springSpawner 独立 registry，须用其自身 registry kill）
        springSpawner.registry().kill(out.taskId());
    }

    @Test
    @DisplayName("Fix B: appendTeamMember 返回 false（team 不存在）→ 不打'已写'误导日志、不 fail spawn（对齐当前 CC 不校验 team 文件）")
    void appendTeamMember_false_logsWarnAndContinuesSpawn() {
        // WHY: 修复前 appendTeamMember 返回值被忽略，无条件打"已写 config.json members"
        //   （根因链误导：team=test-team 不存在 → 实际未写却谎报）。当前 CC 已移除 appendTeamMember、
        //   spawn 入口只校验 teamName 非空（spawnMultiAgent.ts:858-862）→ append 失败不得 fail spawn；
        //   且不得再谎报"已写"。行为断言：spawn 仍 success + registry 注册；日志断言：warn 含
        //   "未能写入"且 info 不含"已写 config.json members"。
        TeamHelpers th = org.mockito.Mockito.mock(TeamHelpers.class);
        org.mockito.Mockito.when(th.appendTeamMember(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(false);   // team 不存在 / 无 members 数组 → 返回 false
        spawner.setTeamHelpers(th);

        // 日志捕获（SpawnInProcess logger 的 info/warn；append 块在主线程同步执行，spawn() 返回即可断言）
        Logger spawnLogger = (Logger) LoggerFactory.getLogger(SpawnInProcess.class);
        Level prevLevel = spawnLogger.getLevel();
        spawnLogger.setLevel(Level.INFO);
        List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) { events.add(event); }
        };
        appender.start();
        spawnLogger.addAppender(appender);
        try {
            SpawnInProcess.InProcessSpawnOutput out = spawn("researcher", "research-team", "do research", false);

            assertThat(out.success()).as("append 失败不得 fail spawn（对齐当前 CC 不校验 team 文件）").isTrue();
            assertThat(spawner.registry().get(out.taskId())).as("spawn 仍须注册 loop").isPresent();
            assertThat(events.stream().filter(e -> e.getLevel() == Level.WARN))
                .as("append 返回 false 必须打 warn 明示未写入（不得误导）")
                .anyMatch(e -> e.getFormattedMessage().contains("未能写入 config.json members"));
            assertThat(events.stream().filter(e -> e.getLevel() == Level.INFO))
                .as("不得再谎报'已写 config.json members'")
                .noneMatch(e -> e.getFormattedMessage().contains("已写 config.json members"));
        } finally {
            spawnLogger.detachAppender(appender);
            spawnLogger.setLevel(prevLevel);
        }
    }
}
