package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [T3 · 权限写键同源] 用户批准 teammate 的权限请求时，<b>写入会话（写键）</b>必须与
 * <b>弹窗投递会话（投递键）</b>是同一个会话 —— 否则「授权写进 A 会话、弹窗却挂在 B 会话」
 * ⇒ 授权被写到一个没人看的会话行 ⇒ 下次照样弹（本任务定位的断裂）。
 *
 * <h2>本类为什么是「防回归」而不是「修 bug」</h2>
 * <p>T3 派单书要求先判定 T1 的效果：<b>T1 已让 teammate 的执行 TUC 携带 Leader 归属</b>
 * （{@link com.nexusai.application.agent.team.SpawnInProcess#spawnInProcessTeammate} 把
 * (LeaderSessionId, LeaderCwd) 物化成最小父 TUC → {@code createSubagentContext.create} 走
 * hasParent 分支 ⇒ {@code ctx.sessionId()} 不再是 {@code SessionKeys.NO_SESSION} 哨兵，
 * 而是 Leader 会话）。因此三个写键现场（{@code WebSocketPermissionPrompter.applyAndPersistUpdates} /
 * {@code ToolPermissionGate.applyAndPersistPermissionUpdates} 两处 / bridge racer 的
 * {@code ctx.sessionId()}）**自动自愈** —— 它们用的 {@code ctx.sessionId()} 与投递用的
 * {@link WebSocketPermissionPrompter#owningSessionId} 现在指向同一会话。按派单书「若已自愈：
 * 不重复改动，改为加防回归测试」，本类不修改那三处，只把「写键 == 投递键」钉成回归不变量。
 *
 * <h2>断言标的（全部是**实跑产物**，不是中间字段自称）</h2>
 * <ol>
 *   <li><b>投递键</b> = 真实 {@code prompt()} 经 STOMP 推出去的 topic 里的会话段
 *       （{@code /topic/sessions/{会话}/permission-requests}）；</li>
 *   <li><b>写键</b> = 真实 {@code onResponse(allow, updatedPermissions)} 触发持久化时，
 *       经 {@code SessionPermissionOverlay.persistSessionUpdates} 传给
 *       {@link SessionMapper#selectOneById} 的会话键（该键 = {@code applyAndPersistUpdates}
 *       里的同一个局部量 {@code sessionId}，也供 settings 写盘与
 *       {@code ToolPermissionGate} 的等价单点使用）；</li>
 *   <li><b>bridge 键</b> = bridge 竞速 racer 出站请求携带的 {@code sessionId}
 *       （改前 teammate 会发到 {@code /topic/sessions/no-session/…} 死 topic）。</li>
 * </ol>
 *
 * <h2>鉴别力（反向实验已实跑，见报告）</h2>
 * <p>正向用例从**真实 spawn 链**取 teammate 的工具执行 TUC（T1 归属链路尾端的真实产物），
 * 不是手搓 ctx。反向实验：把 {@code AutonomousAgentLoop.runOneTurn} 末实参改回 {@code null}
 * （= 断开 T1 的父 TUC 通道）⇒ 本类第 1 个用例必红（写键跌回 {@code no-session}，
 * 与投递键（仍经 teammateIdentity.parentSessionId 解析为 Leader）不再相等）。
 */
@DisplayName("[T3] 权限写键 == 投递键（teammate 授权落到与弹窗同一会话）")
class PermissionWriteKeyEqualsDeliveryKeyTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    /** teammate 的 TUC sessionId 实机值 = 「确无会话」哨兵（T1 前/降级路径才会出现）。 */
    private static final String NO_SESSION_SENTINEL = "no-session";
    /** Leader（= 有 UI 的会话）的 sessionId。 */
    private static final String LEADER_SESSION = "sess-leader-t3";
    /** 非 teammate（主会话）的 sessionId。 */
    private static final String PLAIN_SESSION = "sess-plain-t3";
    /** 投递 topic 前缀/后缀（{@link WebSocketPermissionPrompter#topicFor}）。 */
    private static final String TOPIC_PREFIX = "/topic/sessions/";
    private static final String TOPIC_SUFFIX = "/permission-requests";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.experimental.agent-teams", "true");
        // 无 DB 的合成会话（与全局 JUnit 扩展同语义：本环境「确无会话」，非「无法判定」）。
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
    // 用例 1：真实 teammate 链 → 写键 == 投递键（本次任务的核心不变量）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("真实 teammate 链：持久化会话键 == 弹窗投递会话键 == bridge 请求会话键 == Leader 会话")
    void teammateRealChain_writeKeyEqualsDeliveryKey() throws Exception {
        // ── 1) 走真实 spawn 链，取 T1 归属链路尾端的 teammate 工具执行 TUC ──
        CtxProbeTool probe = new CtxProbeTool();
        ToolRegistry registry = new ToolRegistry().register(probe);
        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        spawner.setSubagentExecutor(subagentExecutor(registry, scriptedProviderFactory()));
        Path leaderProject = tempDir.resolve("leader-project");

        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("alice", "team-t3", "do X", null, false, null,
                "general-purpose", leaderProject.toString()),
            new SpawnInProcess.SpawnContext(LEADER_SESSION, "tu-t3"));
        try {
            assertThat(out.success()).as("spawn 成功").isTrue();
            assertThat(probe.executed.await(30, TimeUnit.SECONDS))
                .as("teammate 的工具轮必须在 30s 内跑完（否则夹具没驱动到链路）").isTrue();
            ToolUseContext teammateCtx = probe.ctx.get();
            assertThat(teammateCtx).as("探针必须拿到工具执行池上的 TUC").isNotNull();

            // ── 2) T1 归属前置条件：teammate 的**自身会话键**已是 Leader 会话 ──
            //   ⭐ 这条是「写键 == 投递键」能成立的**唯一**依据：写键的三个现场都取
            //   ctx.sessionId()，而投递键取 owningSessionId(ctx)。若 T1 的归属断链，
            //   ctx.sessionId() 会跌回 no-session 哨兵，两键立刻分叉（反向实验即打在这里）。
            assertThat(teammateCtx.sessionId())
                .as("teammate 执行 TUC 的自身会话键必须是 Leader 会话（T1 归属链的产物；"
                    + "改前为 no-session 哨兵 ⇒ 写键会落到幻影会话行）")
                .isEqualTo(LEADER_SESSION);
            assertThat(teammateCtx.teammateIdentity())
                .as("teammate 身份必须已盖章在 TUC 上（投递键 owningSessionId 的判据来源）")
                .isNotNull();
            assertThat(teammateCtx.teammateIdentity().parentSessionId())
                .as("teammate 身份携带的 Leader 会话必须与自身会话键同源")
                .isEqualTo(LEADER_SESSION);

            // ── 3) 用**这个真实 ctx** 跑一次真实 prompt() → 捕获三个键 ──
            CapturedKeys keys = driveRealPrompter(teammateCtx, "req-team-t3");

            assertThat(keys.deliveryTopicSession())
                .as("投递键 = 有 UI 的 Leader 会话（owningSessionId）").isEqualTo(LEADER_SESSION);
            assertThat(keys.persistedSession())
                .as("写键 = 持久化实际落到的会话（SessionPermissionOverlay → SessionMapper.selectOneById）"
                    + "；必须是 Leader 会话 —— 改前落到 no-session ⇒ 授权被丢弃 ⇒ 下次照样弹")
                .isEqualTo(LEADER_SESSION);
            assertThat(keys.bridgeSession())
                .as("bridge 竞速 racer 出站请求的会话必须同源（改前发到 no-session 死 topic）")
                .isEqualTo(LEADER_SESSION);

            // ⭐ 本任务的核心断言：写键与投递键**就是同一个会话键**（同源，非各自取键后恰好相等）
            assertThat(keys.persistedSession())
                .as("权限写键必须 == 投递键（否则「授权写进 A、弹窗挂 B」）")
                .isEqualTo(keys.deliveryTopicSession());
            assertThat(keys.bridgeSession())
                .as("bridge 键必须 == 投递键（同一 Leader 会话的确认表面）")
                .isEqualTo(keys.deliveryTopicSession());
        } finally {
            spawner.registry().kill(out.taskId());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 用例 2：非 teammate（主会话）→ 两键同源 = ctx.sessionId()（零变化回归）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("非 teammate：持久化会话键 == 投递会话键 == ctx.sessionId()（既有语义零变化）")
    void plainSession_writeKeyEqualsDeliveryKey() throws Exception {
        ToolUseContext plainCtx = ToolUseContext.of(AGENT_ID, PLAIN_SESSION);
        CapturedKeys keys = driveRealPrompter(plainCtx, "req-plain-t3");

        assertThat(keys.deliveryTopicSession()).isEqualTo(PLAIN_SESSION);
        assertThat(keys.persistedSession()).isEqualTo(PLAIN_SESSION);
        assertThat(keys.bridgeSession()).isEqualTo(PLAIN_SESSION);
        assertThat(keys.persistedSession()).isEqualTo(keys.deliveryTopicSession());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 夹具：真实 prompt() 跑一遍，捕获三个键
    // ════════════════════════════════════════════════════════════════════════

    /** 三个键的实跑产物。 */
    private record CapturedKeys(String deliveryTopicSession, String persistedSession, String bridgeSession) {}

    /**
     * 用给定 ctx 跑一次真实 {@link WebSocketPermissionPrompter#prompt}（后台线程，阻塞语义），
     * 待 STOMP 出站后以 {@code onResponse(allow, updatedPermissions)} 回灌，捕获：
     * ① 出站 topic 的会话段（投递键）；② 持久化实际落到的会话（写键）；
     * ③ bridge racer 出站请求的会话（bridge 键）。
     *
     * <p>updatedPermissions 用 CC 线格式（{@code type/destination/behavior/rules}）的
     * {@code destination=SESSION} 条目 —— 它是 {@code applyAndPersistUpdates} 步骤 0
     * （会话列写入，{@code SessionPermissionOverlay.persistSessionUpdates}）的触发物，
     * 该步骤无条件先于「无 permissionContext 早退」执行 ⇒ 写键恒可观测。
     *
     * <p>SessionMapper 用默认 Answer 捕获入参（避免对 {@code selectOneById} 泛型重载打桩的
     * 匹配歧义，同 {@code WebSocketPermissionPrompterDeliverySessionTest} 的既有做法）；
     * 返回 null ⇒ persistSessionUpdates 走「会话行不存在」WARN 分支，不触碰任何真实 DB。
     */
    private CapturedKeys driveRealPrompter(ToolUseContext ctx, String requestId) throws Exception {
        AtomicReference<String> publishedTopic = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class, inv -> {
            if ("convertAndSend".equals(inv.getMethod().getName())) {
                publishedTopic.set((String) inv.getArgument(0));
                published.countDown();
            }
            return null;
        });

        // 写键捕获点：persistSessionUpdates 传给 SessionMapper 的会话键
        List<String> persistedSessions = new CopyOnWriteArrayList<>();
        SessionMapper sessionMapper = mock(SessionMapper.class, inv -> {
            if ("selectOneById".equals(inv.getMethod().getName()) && inv.getArguments().length > 0) {
                persistedSessions.add(String.valueOf(inv.getArguments()[0]));
            }
            return null;
        });

        // bridge 键捕获点：startBridgeRace 出站请求携带的 sessionId
        List<String> bridgeSessions = new CopyOnWriteArrayList<>();
        BridgePermissionCallbacks bridgeCallbacks = new BridgePermissionCallbacks() {
            @Override
            public void sendRequest(String sessionId, String requestId0, String toolName, JsonNode displayInput,
                                   String toolUseId, String description, List<PermissionUpdate> suggestions,
                                   String blockedPath) {
                bridgeSessions.add(sessionId);
            }

            @Override
            public Runnable onResponse(String requestId0, Consumer<BridgeResponse> handler) {
                return () -> { };
            }

            @Override
            public void cancelRequest(String requestId0) { }

            @Override
            public void sendResponse(String requestId0, BridgeResponse response) { }

            @Override
            public boolean resolve(String requestId0, BridgeResponse response) {
                return false;
            }
        };

        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 60_000);
        prompter.setSessionMapper(sessionMapper);
        prompter.wireRacersForTesting(bridgeCallbacks, null);

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "perm-key-test");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<PermissionResult> pending = exec.submit(() -> prompter.prompt(
                new StubTool(), JSON.objectNode().put("command", "ls"),
                new PermissionDecisionReason.Other("test"), ctx, requestId,
                new PermissionPromptDetails("list dirs", List.of(), null, null, false)));

            assertThat(published.await(5, TimeUnit.SECONDS))
                .as("prompt 必须把弹窗推到某个 topic（否则用户永远看不到）").isTrue();
            String topic = publishedTopic.get();
            String deliverySession = sessionFromPermissionTopic(topic);
            assertThat(deliverySession).as("投递 topic 必须形如 " + TOPIC_PREFIX + "{会话}" + TOPIC_SUFFIX
                + "，实际=" + topic).isNotNull();

            // 用户批准「总是允许」（destination=SESSION）⇒ 触发 apply + persist（写键现场）
            ObjectNode update = JSON.objectNode();
            update.put("type", "addRules");
            update.put("destination", "session");
            update.put("behavior", "allow");
            ArrayNode rules = update.putArray("rules");
            rules.addObject().put("toolName", "Bash");
            prompter.onResponse(requestId, "allow", List.of(update), null, null);

            PermissionResult result = pending.get(5, TimeUnit.SECONDS);
            assertThat(result).as("用户允许后工具线程必须能继续")
                .isInstanceOf(PermissionResult.Allow.class);

            assertThat(persistedSessions)
                .as("已批准的 SESSION 档更新必须经持久化通道（写键现场）—— 否则本用例断言不到写键")
                .hasSize(1);
            assertThat(bridgeSessions)
                .as("bridge racer 必须出站一次请求（bridge 键现场）").hasSize(1);
            return new CapturedKeys(deliverySession, persistedSessions.get(0), bridgeSessions.get(0));
        } finally {
            exec.shutdownNow();
        }
    }

    /** 从 {@code /topic/sessions/{会话}/permission-requests} 提取会话段。 */
    private static String sessionFromPermissionTopic(String topic) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX) || !topic.endsWith(TOPIC_SUFFIX)) {
            return null;
        }
        return topic.substring(TOPIC_PREFIX.length(), topic.length() - TOPIC_SUFFIX.length());
    }

    /** 探针工具：把工具执行池线程上的 TUC 原样记下来（T1 归属链尾端的真实产物）。 */
    static final class CtxProbeTool implements Tool {
        final AtomicReference<ToolUseContext> ctx = new AtomicReference<>();
        final CountDownLatch executed = new CountDownLatch(1);

        @Override public String name() { return "CtxProbe"; }

        @Override public String description() { return "records the tool-execution ToolUseContext"; }

        @Override public JsonNode inputSchema() { return JSON.objectNode(); }

        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "probed");
        }

        @Override public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
            if (ctx != null) {
                this.ctx.set(ctx);
            }
            executed.countDown();
            return ToolResult.success(call.id(), "probed");
        }
    }

    /** 弹窗用的最小工具桩（不参与权限判定，仅承载 name/description）。 */
    static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "bash"; }
        @Override public JsonNode inputSchema() { return JSON.objectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    /** 脚本化 LLM：第 1 次调用投递 {@code CtxProbe} 的 tool_use，第 2 次起文本收尾。 */
    private static LlmProviderFactory scriptedProviderFactory() {
        AtomicInteger calls = new AtomicInteger();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (calls.incrementAndGet() == 1) {
                onMsg.accept(new AssistantMessage("probing", "tool_calls",
                    List.of(new ToolUseBlock("tu-probe", "CtxProbe", JSON.objectNode())),
                    "", null, 10L));
            } else {
                onMsg.accept(new AssistantMessage("done", "stop", List.of(), "", null, 10L));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    private static SubagentExecutor subagentExecutor(ToolRegistry registry, LlmProviderFactory factory) {
        SubagentExecutor executor = new SubagentExecutor(registry, null, null, factory, null, "test-model", "sys");
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        executor.setContextFactory(ctxFactory);
        return executor;
    }
}
