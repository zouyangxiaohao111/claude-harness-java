package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.apis.task.TaskController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「main-session 任务 kill 可达性 + 真中断 + 终态收尾」+「stop-all 假成功修正」· 意图验证。
 *
 * <h2>WHY（规则九：测试验证意图，而非仅验证行为）—— 这条链路为何重要</h2>
 * <ul>
 *   <li><b>可达性</b>：主会话后台化任务（{@code MainSessionBackgroundService.registerMainSessionTask}
 *       → {@code TaskFrameworkService.registerMainSessionTask}）**不经 spawn**（只写 framework 统一
 *       store 的 {@code store} + {@code mainSessionStore}，**不写** {@code BackgroundTaskRunner} 的本地
 *       {@code tasks} 地图）⇒ {@code stopTask} 首行 {@code tasks.get(taskId)} miss。用户看到卡片
 *       「运行中」、点 ⏹ 却拿不到停止结果 —— 前端 2s 轮询据此反复 register ⇒ 无限回环幽灵卡片。</li>
 *   <li><b>真中断</b>：只把状态改成 KILLED 而不断后台查询循环 = 「卡片说已停止、后台还在烧 token」
 *       的<b>假绿</b>（比不修更坏：用户以为省了钱）。真中断靠取回在飞 {@code AgentState} 调
 *       {@code abortStream("user-cancel")} —— 与 {@code ChatService.cancelSession} 同一条硬中断路径。
 *       T3 就是这条假绿的牙。</li>
 *   <li><b>判别器纪律</b>：主会话任务与 spawn 出来的子代理任务 <b>type 相同</b>
 *       （都是 {@code local_agent}）⇒ 只能靠「在不在 mainSessionStore 里」判别；
 *       ⛔ 不许用 taskId 的 's' 前缀（那是 {@code MainSessionTaskState} 的私有常量）。
 *       T2 证明「不截胡 + 前缀不算数」。</li>
 *   <li><b>stop-all 如实回报</b>：原实现恒 {@code success:true} ⇒ 用户点了「全部停止」看到成功，
 *       实际有任务没停掉（假成功）。T5 是这条的牙。</li>
 * </ul>
 *
 * <h2>反向实验（每条都实跑过，贴的是「注释掉修复后」的原始红输出 —— 见本次交付 negativeExperiment）</h2>
 * <ul>
 *   <li>T1 反向：不装配 {@code setMainSessionBackgroundService} ⇒ 第 5 环返回 null ⇒ {@code stopTask}
 *       不再 ok（红）。</li>
 *   <li>T2 反向：去掉归属判别（直接用投影，不看 mainSessionStore）⇒ 普通 local_agent 被第 5 环认领
 *       ⇒ 第一条断言（isNull）变红。</li>
 *   <li>T3 反向：注释掉 {@code killMainSessionTask} 里 {@code abortStream(...)} 那一行 ⇒
 *       controller 不被取消 ⇒ T3 变红（证明测试能区分「只改状态」与「真中断」）。</li>
 * </ul>
 *
 * <p><b>⚠️ 与派单书的一处已知偏差（如实登记，不粉饰）</b>：派单书预期「未装配 ⇒ NOT_FOUND」。
 * 实测本 worktree 里 {@code stopTask} 已有「刀 3」的 {@code stopLocalAgentTask}（按
 * {@code type==LOCAL_AGENT} 从统一 store 认领），它排在主会话第 5 环<b>之后</b>、NOT_FOUND <b>之前</b>
 * ⇒ 第 5 环缺席时该任务被刀 3 认领并返回 <b>NOT_RUNNING</b>（不是 NOT_FOUND）。
 * 故本测试的判别断言用 {@code ok()} 的 true/false（等价强度：改动前 ok=false、改动后 ok=true），
 * 并在反向实验里贴实测的 NOT_RUNNING 输出。
 *
 * <p>纯 JUnit 直构 + Mockito（⛔ 无 {@code @SpringBootTest}：那会迁移用户真库）。
 */
@DisplayName("[第 5 环] 主会话后台化任务 stopTask：可达性 + 真中断 + 终态收尾 + stop-all 口径")
class MainSessionTaskStopTest {

    /** 测试上下文（真 store / 真 registry / 真 runner）。 */
    private record Ctx(
        BackgroundTaskRunner runner,
        TaskFrameworkService framework,
        SdkEventQueue sdk,
        NotificationQueue nq,
        MainSessionBackgroundService service,
        SessionAgentStateRegistry registry
    ) {}

    /**
     * 真服务上下文：{@code MainSessionBackgroundService} 为真实例（T3/T4/T5 用）。
     *
     * @param assembleMainRing true = 装配第 5 环（{@code setMainSessionBackgroundService}）；
     *                         false = <b>反向实验</b>态（未装配）
     */
    private Ctx realCtx(boolean assembleMainRing) {
        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService framework = new TaskFrameworkService(sdk);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        MainSessionBackgroundService service = new MainSessionBackgroundService();
        ReflectionTestUtils.setField(service, "taskFrameworkService", framework);
        ReflectionTestUtils.setField(service, "sdkEventQueue", sdk);
        ReflectionTestUtils.setField(service, "notificationQueue", nq);
        ReflectionTestUtils.setField(service, "sessionAgentStateRegistry", registry);
        BackgroundTaskRunner runner = new BackgroundTaskRunner(nq, framework, sdk);
        if (assembleMainRing) {
            runner.setMainSessionBackgroundService(service);
        }
        return new Ctx(runner, framework, sdk, nq, service, registry);
    }

    /**
     * fake-kill 上下文：主会话服务为 Mockito mock 且 {@code killMainSessionTask} 恒 true
     * （T1/T5 用 —— 把「可达性」与「中断内部实现」解耦，只验第 5 环认领与 stop-all 口径）。
     */
    private Ctx fakeKillCtx(boolean assembleMainRing) {
        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService framework = new TaskFrameworkService(sdk);
        MainSessionBackgroundService fake = mock(MainSessionBackgroundService.class);
        when(fake.killMainSessionTask(anyString())).thenReturn(true);
        BackgroundTaskRunner runner = new BackgroundTaskRunner(nq, framework, sdk);
        if (assembleMainRing) {
            runner.setMainSessionBackgroundService(fake);
        }
        return new Ctx(runner, framework, sdk, nq, fake, new SessionAgentStateRegistry());
    }

    /** 注册一条真的主会话后台化任务（走生产注册路径 ⇒ agentUuid 与 kill 侧同源）。 */
    private String registerMainSession(Ctx ctx) {
        return ctx.service().registerMainSessionTask("bg query", "sess-x", null);
    }

    /**
     * 直接在 framework 层注册一条主会话后台化任务（双 store 写入，与生产
     * {@code MainSessionBackgroundService.registerMainSessionTask} 的 store 效果等价）。
     *
     * <p>用于 {@code fakeKillCtx}（服务是 mock，其 registerMainSessionTask 未打桩会返回 null）——
     * 把「注册」与「kill」解耦，让可达性测试不依赖被 mock 的服务。
     */
    private String registerMainSessionViaFramework(Ctx ctx) {
        String taskId = MainSessionTaskState.generateMainSessionId();
        UUID agentUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String outputFile = "unused.output";
        BackgroundTask projection = new BackgroundTask(
            taskId, TaskType.LOCAL_AGENT, BackgroundTaskStatus.RUNNING,
            "bg query", null, now, null, null,
            outputFile, 0L, false, agentUuid, true, "sess-x",
            null, null, null, null, null, null);
        MainSessionTaskState state = new MainSessionTaskState(
            taskId, TaskType.LOCAL_AGENT, BackgroundTaskStatus.RUNNING,
            "bg query", null, now, null, null,
            outputFile, 0L, false,
            taskId, "bg query", MainSessionTaskState.AGENT_TYPE_MAIN_SESSION,
            null, null, null, false, null,
            0L, 0L, true, List.of(), false, false, null);
        ctx.framework().registerMainSessionTask(state, projection);
        return taskId;
    }

    // ───────────────────────────── T1 可命中性 ─────────────────────────────

    @Test
    @DisplayName("T1 可命中性：只注册在统一 store 的主会话任务，stopTask 回 ok（不再有不可停的卡片）")
    void t1_mainSessionTaskStopTask_hitsFifthRing() {
        Ctx ctx = fakeKillCtx(true);
        String taskId = registerMainSessionViaFramework(ctx);

        // 前置旁证：该任务确实**不在** runner 本地 tasks 地图（否则走的是上方主分支，不是第 5 环）
        assertThat(ctx.runner().getTask(taskId))
            .as("前置：主会话后台化任务不应进 runner 本地 tasks（只进统一 store）").isEmpty();
        assertThat(ctx.runner().listAllTasks())
            .as("前置：listAllTasks 合并视图能列出它（= 用户看到的「运行中」卡片）")
            .extracting(BackgroundTask::id).contains(taskId);

        BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask(taskId);

        // 改动前：本地 tasks miss + 4 条回退按 type 全不认领 ⇒ ok()=false（NOT_FOUND / 刀 3 的
        //   NOT_RUNNING）⇒ 前端点停止拿不到成功结果 ⇒ 卡片永久「运行中」。
        assertThat(result.ok())
            .as("主会话后台化任务必须被第 5 环认领并停止成功（ok()=false = 修复缺失）").isTrue();
        assertThat(result.taskType()).as("task_type 用实际类型 local_agent").isEqualTo("local_agent");
        assertThat(result.command()).as("command 承载 description（CC stopTask.ts:97）").isEqualTo("bg query");
    }

    @Test
    @DisplayName("T1 回归：未装配主会话服务 ⇒ 第 5 环不认领，fail-closed 落回既有分发路径（不 NPE）")
    void t1_unassembled_fallsBackGracefully() {
        // 【反向实验的固化形态】未装配 ⇒ 第 5 环返回 null ⇒ 落回刀 3 的 store 回退（NOT_RUNNING，
        //   见类注释的已知偏差）。关键断言：绝不能是 ok()（否则就是「没接线也报成功」的假绿）。
        Ctx ctx = fakeKillCtx(false);
        String taskId = registerMainSessionViaFramework(ctx);

        BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask(taskId);

        assertThat(result.ok()).as("未装配 ⇒ 不得报停止成功").isFalse();
        assertThat(result.errorCode())
            .as("未装配 ⇒ 落回既有分发路径的确定错误码（本仓为刀 3 的 NOT_RUNNING）")
            .isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING);
    }

    @Test
    @DisplayName("T1 回归：不存在的任务 ⇒ 仍是 NOT_FOUND（第 5 环不越界）")
    void t1_unknownTask_stillNotFound() {
        Ctx ctx = fakeKillCtx(true);
        BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask("no-such-task-at-all");
        assertThat(result.errorCode()).isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_FOUND);
    }

    // ───────────────────────────── T2 判别器隔离 ─────────────────────────────

    @Test
    @DisplayName("T2 判别器隔离：同一次运行里，第 5 环只认 main-session，不截胡 type 相同的普通 local_agent")
    void t2_discriminator_doesNotStealNormalLocalAgent() {
        Ctx ctx = realCtx(true);

        // ① 普通 local_agent（spawn 路径：进 runner 本地 tasks + 统一 store，但**不在** mainSessionStore）
        UUID agentId = UUID.randomUUID();
        ctx.runner().registerAsyncAgent(agentId, "普通子代理", "prompt", "general-purpose", null, "sess-trk");
        String normalTaskId = agentId.toString();

        // ② 主会话后台化任务（只在统一 store + mainSessionStore）
        String mainTaskId = registerMainSession(ctx);

        // 同一次运行、同一 runner：第 5 环（私有，经反射直调）对二者的判别结果必须相反
        Object forNormal = ReflectionTestUtils.invokeMethod(ctx.runner(), "stopMainSessionTask", normalTaskId);
        Object forMain = ReflectionTestUtils.invokeMethod(ctx.runner(), "stopMainSessionTask", mainTaskId);

        assertThat(forNormal)
            .as("普通 local_agent（type 同为 local_agent）**不得**被第 5 环认领 —— "
                + "否则按 type 相同的误判会截胡正常子代理的停止链路")
            .isNull();
        assertThat(forMain)
            .as("主会话后台化任务必须被第 5 环认领（判别器 = 在不在 mainSessionStore）")
            .isNotNull();
    }

    @Test
    @DisplayName("T2 判别器纪律：'s' 前缀本身**不是**判据（不存在的 s 前缀 id 仍 NOT_FOUND）")
    void t2_sPrefixAloneGrantsNothing() {
        // 判别器必须用「在不在 mainSessionStore」（TaskFrameworkService.getMainSessionTask），
        // ⛔ 不是 taskId 的 's' 前缀 —— 后者是 MainSessionTaskState 的 **private** 实现常量
        //   （MAIN_SESSION_ID_PREFIX），按前缀判别等于把私有实现细节复制成第二事实源。
        // 本用例把该纪律钉死：一个有 's' 前缀但**不在 store** 的 id 不得被第 5 环认领。
        Ctx ctx = realCtx(true);
        BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask("s00000000");

        assertThat(result.errorCode())
            .as("'s' 前缀不构成认领依据 —— 仍应是 NOT_FOUND（前缀判别会让它变成 NOT_RUNNING/ok）")
            .isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_FOUND);
    }

    // ───────────────────────────── T3 真中断有判别力 ─────────────────────────────

    @Test
    @DisplayName("T3 ⭐ 真中断：killMainSessionTask 必须 abort 在飞 AgentState（不是只改状态）")
    void t3_kill_trulyAbortsInFlightAgentState() {
        // WHY（本批最重要的牙）：只置终态而不 abort = 「卡片说已停止、后台还在烧 token」的假绿。
        //   真中断通道 = registry（agents 桶，键 = agentUuid）取回在飞 AgentState →
        //   abortStream("user-cancel") → attach 的 runAbortController.abort → provider 硬断流。
        //   ⭐ agentUuid 的取法：从注册产物的投影读（BackgroundTask.agentId）——
        //   与 MainSessionBackgroundService 内部 taskAgentId(taskId) **同源**，测试不重算私有哈希。
        Ctx ctx = realCtx(true);
        String taskId = registerMainSession(ctx);
        UUID agentUuid = ctx.framework().getTask(taskId).orElseThrow().agentId();
        assertThat(agentUuid).as("注册必须把 agentUuid 写进投影 agentId（真中断靠它查 registry）").isNotNull();

        // 真 AbortController + 真 registry：模拟后台查询已 attach 在飞状态
        AgentState inFlight = new AgentState("sys", "sess-x", agentUuid);
        AbortController abortController = new AbortController();
        inFlight.attachAbortController(abortController);
        ctx.registry().register(agentUuid, inFlight);
        assertThat(abortController.isCancelled()).as("前置：kill 前未取消").isFalse();

        boolean killed = ctx.service().killMainSessionTask(taskId);

        assertThat(killed).as("killMainSessionTask 必须生效（true）").isTrue();
        assertThat(abortController.isCancelled())
            .as("⭐ 必须 abort 在飞 AgentState 的 controller —— 否则后台查询循环继续跑（假绿："
                + "卡片显示已停止、token 继续烧）。这条断言就是「只改状态」与「真中断」的分界线")
            .isTrue();
        assertThat(abortController.reason())
            .as("reason 与 ChatService.cancelSession 同源（'user-cancel'）").isEqualTo("user-cancel");
        assertThat(inFlight.cancelled()).as("协式 flag 同时置位（loop 轮询退出兜底）").isTrue();
    }

    @Test
    @DisplayName("T3 降级如实留痕：registry 未命中在飞状态 ⇒ 仍置终态（不静默、不 NPE）")
    void t3_missingInFlightState_stillTerminates() {
        // 后台 loop 已退出 / 尚未注册时点停止：无硬断流可打，但终态必须落地（否则卡片永挂「运行中」）。
        // 该分支在实现里打 ≥WARN（⛔ 不静默）——「无法中断」与「已中断」必须可区分。
        Ctx ctx = realCtx(true);
        String taskId = registerMainSession(ctx);
        // ⛔ 刻意不 register 任何 AgentState

        assertThat(ctx.service().killMainSessionTask(taskId)).isTrue();
        assertThat(ctx.framework().getTask(taskId)).get()
            .extracting(BackgroundTask::status).isEqualTo(BackgroundTaskStatus.KILLED);
    }

    @Test
    @DisplayName("T3 幂等：非 running（已终态）⇒ killMainSessionTask 返回 false 且不重复 abort")
    void t3_nonRunning_idempotentShortCircuit() {
        Ctx ctx = realCtx(true);
        String taskId = registerMainSession(ctx);
        assertThat(ctx.service().killMainSessionTask(taskId)).isTrue();
        assertThat(ctx.service().killMainSessionTask(taskId))
            .as("only-if-running 守卫：二次 kill 短路（幂等）").isFalse();
    }

    // ───────────────────────────── T4 终态一致性 ─────────────────────────────

    @Test
    @DisplayName("T4 终态一致性：① KILLED ② notified ③ mainSessionStore 同步 ④ SDK 终态事件 ⑤ 未 evict")
    void t4_terminalStateConsistency() {
        Ctx ctx = realCtx(true);
        String taskId = registerMainSession(ctx);
        ctx.sdk().drainSdkEvents("sess-x"); // 排空 registerMainSessionTask 已发的 task_started

        assertThat(ctx.service().killMainSessionTask(taskId)).isTrue();

        // ① 投影置终态 KILLED + endTime
        BackgroundTask projection = ctx.framework().getTask(taskId).orElseThrow();
        assertThat(projection.status()).as("① 投影必须置 KILLED").isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(projection.endTime()).as("① endTime 必须置位").isNotNull();
        // ② notified（evict 三闸之一；也是「不再补发完成通知」的防重标记）
        assertThat(projection.notified()).as("② notified 必须为 true").isTrue();
        // ③ mainSessionStore 同步终态（只改投影会让该 store 滞留 RUNNING —— RK-w5-1 双写同步）
        MainSessionTaskState carrier = ctx.framework().getMainSessionTask(taskId).orElseThrow();
        assertThat(carrier.status())
            .as("③ mainSessionStore 载体必须同步为终态（否则 getMainSessionTask 侧永远 RUNNING）")
            .isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(carrier.notified()).as("③ 载体 notified 同步").isTrue();
        // ④ SDK 终态 bookend（前端据此把卡片置「已停止」）
        assertThat(ctx.sdk().drainSdkEvents("sess-x"))
            .as("④ 必须 emit task_terminated('stopped') 闭合 task_started（前端据此收敛卡片）")
            .anyMatch(e -> e.event() instanceof SdkEventQueue.TaskNotificationEvent se
                && taskId.equals(se.taskId()) && "stopped".equals(se.status()));
        // ⑤ 未 evict（面板要能显示「已停止」；evict 掉则前端读不到该条目）
        assertThat(ctx.framework().getTask(taskId)).as("⑤ 投影不得被 evict").isPresent();
        assertThat(ctx.framework().getMainSessionTask(taskId)).as("⑤ 载体不得被 evict").isPresent();
    }

    // ───────────────────────────── T5 stop-all 口径 ─────────────────────────────

    @Test
    @DisplayName("T5 stop-all 口径：NOT_RUNNING/已终态不算失败 ⇒ success=true failed=0；真错 ⇒ success=false failed=1")
    void t5_stopAllHonestSuccess() {
        // 用**真**服务（不是 mock）：本用例要核 stop-all 的计数口径 —— 需要一个真会把 store
        //   置终态的 kill（否则第一轮之后任务仍 RUNNING，第二轮会再被计一次，口径就测不准）。
        Ctx ctx = realCtx(true);
        // 一条 RUNNING 的主会话任务（可被正常停掉）
        String runningMainTask = registerMainSession(ctx);
        // 一条**已终态**任务（stop-all 会跳过它；它不参与计数，也不得被误判为失败）
        String terminalTaskId = "t-" + UUID.randomUUID();
        ctx.framework().registerTask(new BackgroundTask(
            terminalTaskId, TaskType.MONITOR_MCP, BackgroundTaskStatus.COMPLETED,
            "已完成的监控任务", null, System.currentTimeMillis(), System.currentTimeMillis(), null,
            "unused.output", 0L, false, null, true, "sess-x",
            null, null, null, null, null, null));

        TaskController controller = new TaskController(ctx.runner(), ctx.framework(), null);

        // ── 第一轮：只有可停任务 + 已终态任务 ⇒ 全绿（success=true, failed=0, stopped=1）
        Map<String, Object> green = controller.stopAll("sess-x");
        assertThat(green.get("success"))
            .as("全部可停 + 跳过已终态 ⇒ 无失败（NOT_RUNNING/终态**不算失败**：幂等语义）")
            .isEqualTo(true);
        assertThat(green.get("failed")).as("失败计数必须为 0").isEqualTo(0);
        assertThat(green.get("stopped")).as("主会话任务应被计入已停").isEqualTo(1);
        assertThat(ctx.framework().getTask(runningMainTask)).get()
            .extracting(BackgroundTask::status).isEqualTo(BackgroundTaskStatus.KILLED);

        // ── 第二轮：掺入一条会让 stopTask **真失败**的任务
        //   （monitor_mcp 在 store 里 RUNNING，但本 ctx 未装配 monitorMcpTaskRunner
        //    ⇒ stopTask 走到 NOT_FOUND ⇒ 真错误 ⇒ failed=1）
        String failingTaskId = "m-" + UUID.randomUUID();
        ctx.framework().registerTask(new BackgroundTask(
            failingTaskId, TaskType.MONITOR_MCP, BackgroundTaskStatus.RUNNING,
            "无 runner 的监控任务", null, System.currentTimeMillis(), null, null,
            "unused.output", 0L, false, null, true, "sess-x",
            null, null, null, null, null, null));

        Map<String, Object> red = controller.stopAll("sess-x");
        assertThat(red.get("success"))
            .as("⭐ 有任务真没停掉 ⇒ success 必须为 false（原实现恒 true = 假成功，本批修掉）")
            .isEqualTo(false);
        assertThat(red.get("failed")).as("真失败的必须计入 failed（NOT_FOUND 等真错误）").isEqualTo(1);
        assertThat(red.get("stopped")).as("本轮无成功停止（主会话任务上轮已终态、被跳过）").isEqualTo(0);
    }
}
