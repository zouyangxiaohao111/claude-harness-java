package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.agent.tool.impl.CronCreateTool;
import com.nexusai.application.agent.tool.impl.SendMessageTool;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [S1-T2b · 生产链断言] teammate 身份从 <b>真实 spawn 入口</b> 一路到达 <b>工具执行池线程上的工具</b>。
 *
 * <p><b>WHY 本类必须存在（它是本批唯一「不是夹具自证」的断言）</b>：T2/T3 删掉了旧的
 * 「ThreadLocal 捕获 → 派生线程回放」三段式，改为 TUC 显式载体。若只在夹具里手工
 * {@code ctx.withTeammateIdentity(...)} 再断言消费点，则只能证明「字段被读到」，
 * <b>证明不了生产链真的把身份送到了那个字段</b>（撰写本批报告时已自认该缺口）。
 * 本类从 {@link SpawnInProcess#spawnInProcessTeammate} 起走<b>全真实路径</b>：
 *
 * <pre>
 *   SpawnInProcess.spawnInProcessTeammate(config)          ← 真实（身份在此构造）
 *     → runner 线程 (new Thread)
 *     → AutonomousAgentLoop.runTeammateLoop / runOneTurn   ← 真实（身份取自 taskState.identity()）
 *     → SubagentExecutor.executeStreaming(..., identity)   ← 真实（per-call 形参）
 *     → stampSubagentLoopContext(...) 盖 TUC              ← 真实（唯一盖章点）
 *     → AgentLoopContext.toolExecContext 派生 per-turn TUC ← 真实（wither 透传）
 *     → StreamingToolExecutor → ForkJoinPool 工具线程      ← 真实
 *     → CronCreateTool.execute 读 ctx.teammateIdentity()   ← 真实（身份的唯一消费点）
 * </pre>
 *
 * <p>唯一替身是 LLM（{@code LlmProvider} mock，与身份传播无关）与 {@code ScheduleService}
 * mock（外部 IO）。<b>断言对象 = 工具真正落库的 {@code ScheduleCreateRequest.agentId}</b>
 * （CC {@code CronCreateTool.ts:126 getTeammateContext()?.agentId} 的 Java 等价物）——
 * 它是身份在链尾的**可观测产物**，不是中间字段自称。
 *
 * <p><b>鉴别力（正反双向 + 反向实验）</b>：
 * <ul>
 *   <li>正向：teammate 链 → {@code agentId == "alice@team-x"}（= spawn 配置派生的身份）；</li>
 *   <li>反向：同一条真实 {@code SubagentExecutor.executeStreaming} 但<b>不带</b> teammate 身份
 *       （= 普通 Agent-tool 子代理 / workflow / hook 路径）→ {@code agentId == null}。
 *       两侧同时锁定 ⇒ 判据既非恒真也非恒假。</li>
 *   <li>反向实验配方（本类可证伪）：把 {@code stampSubagentLoopContext} 里的
 *       {@code .withTeammateIdentity(teammateIdentity)} 删除（或让
 *       {@code runOneTurn} 传 null）⇒ 正向用例必须红（agentId 变 null）。</li>
 * </ul>
 */
@DisplayName("S1-T2b · teammate 身份生产链（spawn → 工具池线程工具消费）")
class TeammateIdentityProductionChainTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.experimental.agent-teams", "true");
        // 无 DB 的合成会话：与全局 JUnit 扩展同语义（「确无会话」，非「无法判定」）。
        // [cwd3 步骤 2] ⛔ 必须是 sessionlessEnvironment 而不是 unknown —— 后者语义是「DB 明确答
        //   无此会话」，cwd 域自步骤 2 起对该态 fail-loud 抛（本类经 ToolUseContext 构造链触发）。
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.sessionlessEnvironment());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.task.config-dir");
        System.clearProperty("nexusai.experimental.agent-teams");
        SessionProjectRoot.setDbResolver(null);
        TaskSystemConfig.clearForTest();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 夹具：真实 CronCreateTool（身份消费点）+ 脚本化 LLM（1st 调工具，2nd 文本收尾）
    // ════════════════════════════════════════════════════════════════════════

    /** 工具调用的输入：SESSION scope（durable=false）⇒ agentId 字段由 teammate 身份填充。 */
    private static ObjectNode cronInput() {
        ObjectNode input = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        input.put("cron", "0 9 * * *");
        input.put("prompt", "run smoke test");
        input.put("durable", false);
        return input;
    }

    /**
     * 脚本化 LLM：第 1 次调用投递 {@code CronCreate} 的 tool_use，第 2 次起投递文本收尾。
     * {@code turnFinished} 在第 2 次模型调用时 countDown ⇒ 用例可确定「工具轮已跑完」。
     */
    private static LlmProviderFactory scriptedProviderFactory(CountDownLatch turnFinished) {
        AtomicInteger calls = new AtomicInteger();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (calls.incrementAndGet() == 1) {
                onMsg.accept(new AssistantMessage("creating cron", "tool_calls",
                    List.of(new ToolUseBlock("tu-cron", "CronCreate", cronInput())), "", null, 10L));
            } else {
                onMsg.accept(new AssistantMessage("done", "stop", List.of(), "", null, 10L));
                turnFinished.countDown();
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [P0-6 · D1①] shutdown 决策身份生产链（teammate 工具路径 → 真 abort / 真成员名）
    // ════════════════════════════════════════════════════════════════════════

    /** shutdown_response 工具输入（对齐 SendMessageToolTest 的 {@code {to, message{type,approve,request_id}}} 形）。 */
    private static ObjectNode shutdownResponseInput(boolean approve) {
        ObjectNode input = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        input.put("to", "team-lead");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_response");
        msg.put("approve", approve);
        if (!approve) {
            // CC SendMessageTool.ts:705-715 + 本仓 :369-374：拒绝时 reason 必填（否则 validateInput 挡下）
            msg.put("reason", "still working");
        }
        // ⚠ request_id 用**生产原形**（CC InProcessBackend.ts:225 / 本仓 TeamDeleteTool 的
        //   "shutdown-{全形 agentId}-{ts}"）。它对 AgentIdFormatter.parseRequestId 是**不可解析**的
        //   （ts 段落到 "alice" ⇒ parseLong 失败 ⇒ null ⇒ 回落字面量 "teammate"）——这正是原缺陷。
        //   判别力来源：若实现退回「只反解 request_id」，本用例必红。
        msg.put("request_id", "shutdown-alice@team-x-1699999999999");
        return input;
    }

    /** 第 1 次调用投递 {@code SendMessage(shutdown_response)} 的 tool_use，第 2 次起文本收尾。 */
    private static LlmProviderFactory scriptedShutdownResponseProvider(
            CountDownLatch turnFinished, boolean approve) {
        AtomicInteger calls = new AtomicInteger();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (calls.incrementAndGet() == 1) {
                onMsg.accept(new AssistantMessage("responding", "tool_calls",
                    List.of(new ToolUseBlock("tu-shutdown", "SendMessage", shutdownResponseInput(approve))),
                    "", null, 10L));
            } else {
                onMsg.accept(new AssistantMessage("done", "stop", List.of(), "", null, 10L));
                turnFinished.countDown();
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    /** team-lead 收件箱里**最后一条含 marker 的**消息（teammate loop 之后还会追加 idle_notification）。 */
    private com.fasterxml.jackson.databind.JsonNode teamLeadInboxMessage(String team, String marker)
            throws Exception {
        Path p = tempDir.resolve("teams").resolve(team).resolve("inboxes").resolve("team-lead.json");
        assertThat(p).as("team-lead 收件箱必须存在").exists();
        String raw = Files.readString(p);
        List<com.fasterxml.jackson.databind.JsonNode> msgs = jsonToList(raw);
        return msgs.stream()
            .filter(m -> m.path("text").asText().contains(marker))
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError(
                "收件箱里没有含 '" + marker + "' 的消息，实际=" + raw));
    }

    private static java.util.List<com.fasterxml.jackson.databind.JsonNode> jsonToList(String s)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode root =
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
        List<com.fasterxml.jackson.databind.JsonNode> out = new ArrayList<>();
        root.forEach(out::add);
        return out;
    }

    @Test
    @DisplayName("[P0-6 · D1①] 生产链：teammate 工具路径发 shutdown_response(approve) → **真 abort** + 确认消息 from=真实成员名")
    void shutdownApproval_throughProductionChain_reallyAborts() throws Exception {
        // WHY（规则九）：改动前 handleShutdownApproval 只解析 request_id，而请求侧编号由 TeamDeleteTool
        //   生成为 "shutdown-{member}-{ts}"（**无 @**）⇒ parseRequestId 恒 null ⇒ 回落字面量
        //   "teammate" ⇒ findByAgentName("teammate") 恒落空 ⇒ **永不 abort**（「关机」只是封信）。
        //   本用例走真实 spawn 链把身份送到工具池线程上的 SendMessageTool，断言链尾的**可观测产物**
        //   = 生命周期控制器真被 abort（不是中间字段自称）。
        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        SendMessageTool sendMessage = new SendMessageTool(new TeamHelpers());
        sendMessage.setSpawnInProcess(spawner);
        ToolRegistry registry = new ToolRegistry().register(sendMessage);
        CountDownLatch turnFinished = new CountDownLatch(1);
        spawner.setSubagentExecutor(
            subagentExecutor(registry, scriptedShutdownResponseProvider(turnFinished, true)));

        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("alice", "team-x", "do X", null, false, null),
            new SpawnInProcess.SpawnContext("sess-1", "tu-1"));
        try {
            assertThat(out.success()).as("spawn 成功").isTrue();
            AutonomousAgentLoop loop = spawner.registry().get(out.taskId()).orElseThrow();

            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && !loop.isAborted()) {
                Thread.sleep(50);
            }
            assertThat(loop.isAborted())
                .as("⭐ 生产链上 teammate 批准 shutdown 必须**真 abort** 生命周期控制器"
                    + "（改前：request_id 反解失败 → 回落 'teammate' → 定位落空 → 永不 abort）")
                .isTrue();

            com.fasterxml.jackson.databind.JsonNode approved =
                teamLeadInboxMessage("team-x", "shutdown_approved");
            assertThat(approved.get("from").asText())
                .as("⭐ 确认消息 from 必须是**真实成员名**（身份取自显式 ctx，不是 'teammate'）")
                .isEqualTo("alice");
        } finally {
            spawner.registry().kill(out.taskId());
        }
    }

    @Test
    @DisplayName("[P0-6 · D1③ 同根因同修] 生产链：拒绝路径 team-lead 邮箱的 from 是真实成员名（不是 'teammate'）")
    void shutdownRejection_throughProductionChain_usesRealMemberName() throws Exception {
        // WHY：handleShutdownRejection 与 handleShutdownApproval 是同一能力的两个分支，改动前
        //   两处各自解析 request_id ⇒ 同一断链。此处锁定拒绝路径同样取显式身份。
        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        SendMessageTool sendMessage = new SendMessageTool(new TeamHelpers());
        sendMessage.setSpawnInProcess(spawner);
        ToolRegistry registry = new ToolRegistry().register(sendMessage);
        CountDownLatch turnFinished = new CountDownLatch(1);
        spawner.setSubagentExecutor(
            subagentExecutor(registry, scriptedShutdownResponseProvider(turnFinished, false)));

        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("alice", "team-x", "do X", null, false, null),
            new SpawnInProcess.SpawnContext("sess-3", "tu-3"));
        try {
            assertThat(turnFinished.await(30, TimeUnit.SECONDS))
                .as("拒绝不 abort ⇒ 收尾轮必须跑完（否则夹具没驱动到链路）").isTrue();

            com.fasterxml.jackson.databind.JsonNode rejected =
                teamLeadInboxMessage("team-x", "shutdown_rejected");
            assertThat(rejected.get("from").asText())
                .as("⭐ 拒绝消息 from 必须是真实成员名 'alice'（改前为 'teammate'）")
                .isEqualTo("alice");
        } finally {
            spawner.registry().kill(out.taskId());
        }
    }

    /** 真实 CronCreateTool（+ mock ScheduleService 作为外部 IO 替身）。 */
    private static CronCreateTool cronTool(ScheduleService svc) {
        when(svc.listAll()).thenReturn(List.of());
        // 展示名无碰撞（同 CronCreateToolCcContractTest.stubNoNameCollision：原样返回入参 base）
        when(svc.nextAvailableName(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return new CronCreateTool(svc, CronEnabledGates.DEFAULTS);
    }

    private static SubagentExecutor subagentExecutor(ToolRegistry registry, LlmProviderFactory factory) {
        SubagentExecutor executor = new SubagentExecutor(registry, null, null, factory, null, "test-model", "sys");
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        executor.setContextFactory(ctxFactory);
        return executor;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 正向：生产 teammate 链
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("正向：spawn → runner → teammate loop → 工具池线程 SubagentExecutor 盖章 TUC → CronCreateTool 读到 teammate 身份")
    void teammateChain_identityReachesToolOnPoolThread() throws Exception {
        ScheduleService svc = Mockito.mock(ScheduleService.class);
        ToolRegistry registry = new ToolRegistry().register(cronTool(svc));
        CountDownLatch turnFinished = new CountDownLatch(1);

        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        spawner.setSubagentExecutor(subagentExecutor(registry, scriptedProviderFactory(turnFinished)));

        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("alice", "team-x", "do X", null, false, null),
            new SpawnInProcess.SpawnContext("sess-1", "tu-1"));
        try {
            assertThat(out.success()).as("spawn 成功").isTrue();
            assertThat(turnFinished.await(30, TimeUnit.SECONDS))
                .as("teammate 的工具轮 + 收尾轮必须在 30s 内跑完（否则夹具没驱动到链路）").isTrue();

            ArgumentCaptor<ScheduleCreateRequest> captor =
                ArgumentCaptor.forClass(ScheduleCreateRequest.class);
            verify(svc, Mockito.atLeastOnce()).create(captor.capture());

            // ⭐ 断言对象 = 工具真正落库的 agentId（身份在链尾的可观测产物）
            assertThat(captor.getValue().agentId())
                .as("teammate 身份必须经真实链到达工具执行池线程上的 CronCreateTool"
                    + "（改前：ThreadLocal 回放；改后：TUC 显式载体 + SubagentExecutor 盖章）")
                .isEqualTo("alice@team-x");
            assertThat(captor.getValue().agentId())
                .as("同一身份两处取值必须同源：schedule 落库 agentId == spawn 产出的 agentId")
                .isEqualTo(out.agentId());
        } finally {
            spawner.registry().kill(out.taskId());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 反向：同一条真实 executeStreaming，但不带 teammate 身份（普通子代理路径）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("反向：不带 teammate 身份的同一生产方法（普通子代理路径）→ agentId 必须为 null（判据有鉴别力）")
    void plainSubagentPath_hasNoTeammateIdentity() throws Exception {
        // WHY：与正向用例同一工具、同一脚本化 LLM，唯一差别 = 是否经 teammate 路径传入身份。
        //   若实现把判据写成恒真（如回落到进程级槽 / 硬编码），本用例红。
        ScheduleService svc = Mockito.mock(ScheduleService.class);
        ToolRegistry registry = new ToolRegistry().register(cronTool(svc));
        CountDownLatch turnFinished = new CountDownLatch(1);
        SubagentExecutor executor = subagentExecutor(registry, scriptedProviderFactory(turnFinished));

        // 真实生产方法（Agent-tool 子代理路径）：无 teammate 身份 → 五参重载
        executor.executeStreaming("do X", null, "test-model", null, null);

        assertThat(turnFinished.await(30, TimeUnit.SECONDS))
            .as("子代理工具轮必须跑完").isTrue();
        ArgumentCaptor<ScheduleCreateRequest> captor =
            ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc, Mockito.atLeastOnce()).create(captor.capture());
        assertThat(captor.getValue().agentId())
            .as("非 teammate 路径不得伪造 teammate 身份（恒 null）").isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 结构性对照：teammate 身份不落到「运行中的其他会话」上（无残留槽）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("隔离：teammate 链跑完后再走普通子代理路径，身份不得残留（无进程级/静态槽）")
    void identityDoesNotLeakToFollowingPlainRun() throws Exception {
        // WHY：把身份放进任何进程级槽 / 可变字段（而非 per-call 形参）都会让「上一条 teammate 运行」
        //   污染「下一条普通子代理运行」——这正是本批要消灭的跨会话串台形态。本用例顺序执行两次
        //   真实运行，第二次必须干净。
        ScheduleService svc = Mockito.mock(ScheduleService.class);
        ToolRegistry registry = new ToolRegistry().register(cronTool(svc));

        CountDownLatch firstDone = new CountDownLatch(1);
        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        spawner.setSubagentExecutor(subagentExecutor(registry, scriptedProviderFactory(firstDone)));
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("bob", "team-y", "do Y", null, false, null),
            new SpawnInProcess.SpawnContext("sess-2", "tu-2"));
        try {
            assertThat(firstDone.await(30, TimeUnit.SECONDS)).as("teammate 轮跑完").isTrue();
        } finally {
            spawner.registry().kill(out.taskId());
        }

        // 第二条：普通子代理路径（无 teammate 身份）—— 独立 mock 的 ScheduleService，便于区分归属
        ScheduleService svc2 = Mockito.mock(ScheduleService.class);
        CountDownLatch secondDone = new CountDownLatch(1);
        SubagentExecutor plain = subagentExecutor(
            new ToolRegistry().register(cronTool(svc2)),
            scriptedProviderFactory(secondDone));
        plain.executeStreaming("do Z", null, "test-model", null, null);

        assertThat(secondDone.await(30, TimeUnit.SECONDS)).as("普通子代理轮跑完").isTrue();
        ArgumentCaptor<ScheduleCreateRequest> captor =
            ArgumentCaptor.forClass(ScheduleCreateRequest.class);
        verify(svc2, Mockito.atLeastOnce()).create(captor.capture());
        assertThat(captor.getValue().agentId())
            .as("前一条 teammate 的身份不得残留到下一条运行（否则说明载体是进程级/可变字段）")
            .isNull();
    }
}
