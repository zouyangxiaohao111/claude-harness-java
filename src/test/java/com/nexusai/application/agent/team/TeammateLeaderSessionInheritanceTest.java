package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [T1 · 消除 no-session] teammate 的<b>执行 TUC</b> 必须携带 Leader 归属（sessionId + cwd）。
 *
 * <p><b>WHY 本类必须存在（规则九 · 测试验证意图）</b>：改前 teammate 的执行 TUC 由
 * {@code createSubagentContext.create(null, standaloneOverrides)} 生成（AutonomousAgentLoop 调
 * {@code executeStreaming} 时 {@code parentTucOverride} 传 null）⇒ sessionId 落
 * {@code SessionKeys.NO_SESSION} 哨兵 ⇒ 用户看到的三症状：
 * <ul>
 *   <li>teammate 读写用户项目文件被判「在工作目录之外」（effectiveCwd 落到进程 user.dir）；</li>
 *   <li>transcript 目录 / file-history 桶挂幻影会话键（跨会话撞桶）；</li>
 *   <li>权限授权落不到任何真实会话（按 "no-session" 载入会话级规则 = 空桶）。</li>
 * </ul>
 * 断言对象 = <b>工具执行池线程上工具真实读到的</b> {@code ctx.sessionId()} / {@code ctx.effectiveCwd()}
 * （链路尾端的可观测产物），不是中间字段自称。
 *
 * <p><b>夹具真实度</b>：唯一替身 = LLM（{@code LlmProvider} mock）与「无 DB 的合成会话」解析器
 * （{@code SessionProjectRoot.setDbResolver → sessionlessEnvironment}，与全局 JUnit 扩展同语义）。
 * 正向用例从 {@link SpawnInProcess#spawnInProcessTeammate} 起走全真实链：
 * <pre>
 *   SpawnInProcess.spawnInProcessTeammate（装 Leader 归属父 TUC）
 *     → runner 线程 → AutonomousAgentLoop.runTeammateLoop / runOneTurn（透传父 TUC）
 *     → SubagentExecutor.executeStreaming（形参 parentTucOverride）
 *     → createSubagentContext.create（hasParent 分支继承 sessionId/effectiveCwd）
 *     → Step 18 → runSubagentQueryLoop → StreamingToolExecutor → 工具池线程工具读 ctx
 * </pre>
 *
 * <p><b>鉴别力（正反双向 + 反向实验）</b>：
 * <ul>
 *   <li>正向：带 Leader 归属 ⇒ sessionId == Leader 会话、effectiveCwd == Leader cwd；</li>
 *   <li>反向：同一条真实 {@code runOneTurn}，<b>不设</b> {@code setLeaderParentTuc} ⇒ 同一探针
 *       必须读到 {@code no-session} 哨兵。两侧同时锁定 ⇒ 判据既非恒真也非恒假。</li>
 *   <li>反向实验配方（本类可证伪，实测已跑 RED）：把 {@code AutonomousAgentLoop.runOneTurn} 末实参
 *       改回 {@code null}（= 断开父 TUC 通道）⇒ 正向用例必红（sessionId 变 "no-session"）。</li>
 * </ul>
 *
 * <p>A-4 伴改（继承来的会话键不得清）的断言在同包落地：
 * {@code com.nexusai.application.agent.tool.impl.SubagentExecutorInheritedSessionHookKeyTest}。
 */
@DisplayName("T1 · teammate 执行上下文携带 Leader 归属（消除 no-session 断链）")
class TeammateLeaderSessionInheritanceTest {

    /** Leader（team lead）的会话键 —— 断言「teammate 继承的就是它」。 */
    private static final String LEADER_SESSION = "sess-leader-t1";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.experimental.agent-teams", "true");
        // 无 DB 的合成会话：与全局 JUnit 扩展同语义（「本环境确无会话」，非「无法判定」）。
        // ⛔ 必须是 sessionlessEnvironment 而不是 unknown —— 后者语义是「DB 明确答无此会话」，
        //   cwd 域对该态 fail-loud 抛（会炸在 transcript 目录解析上）。
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
    // 夹具：探针工具（记录 TUC 会话键 / 有效 cwd）+ 脚本化 LLM
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 探针工具：把「工具池线程上 TUC 的会话键与有效 cwd」记下来 —— 这是本任务断言标的
     * （teammate 读写文件时 PathGuard / 权限层看到的正是这两个值）。
     */
    static final class ProbeTool implements Tool {
        final AtomicReference<String> sessionId = new AtomicReference<>();
        final AtomicReference<Path> effectiveCwd = new AtomicReference<>();
        final CountDownLatch executed = new CountDownLatch(1);

        @Override public String name() { return "ProbeContext"; }

        @Override public String description() { return "records ctx.sessionId()/ctx.effectiveCwd()"; }

        @Override public JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }

        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "probed");
        }

        @Override public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
            if (ctx != null) {
                sessionId.set(ctx.sessionId());
                effectiveCwd.set(ctx.effectiveCwd());
            }
            executed.countDown();
            return ToolResult.success(call.id(), "probed");
        }
    }

    /** 脚本化 LLM：第 1 次调用投递 {@code ProbeContext} 的 tool_use，第 2 次起文本收尾。 */
    private static LlmProviderFactory scriptedProviderFactory() {
        AtomicInteger calls = new AtomicInteger();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (calls.incrementAndGet() == 1) {
                onMsg.accept(new AssistantMessage("probing", "tool_calls",
                    List.of(new ToolUseBlock("tu-probe", "ProbeContext",
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())),
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

    // ════════════════════════════════════════════════════════════════════════
    // 正向：真实 spawn 链 → 工具池线程读到的就是 Leader 归属
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("正向（真实 spawn 链）：工具池线程 TUC.sessionId == Leader 会话、effectiveCwd == Leader cwd")
    void teammateToolContext_carriesLeaderSessionAndCwd() throws Exception {
        ProbeTool probe = new ProbeTool();
        ToolRegistry registry = new ToolRegistry().register(probe);
        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        spawner.setSubagentExecutor(subagentExecutor(registry, scriptedProviderFactory()));

        // Leader cwd 由 spawn 配置显式给出（= SpawnInProcess 已算好的那一个，⛔ 下游不另起路径算法）
        Path leaderProject = tempDir.resolve("leader-project");
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig("alice", "team-t1", "do X", null, false, null,
                "general-purpose", leaderProject.toString()),
            new SpawnInProcess.SpawnContext(LEADER_SESSION, "tu-t1"));
        try {
            assertThat(out.success()).as("spawn 成功").isTrue();
            assertThat(probe.executed.await(30, TimeUnit.SECONDS))
                .as("teammate 的工具轮必须在 30s 内跑完（否则夹具没驱动到链路）").isTrue();

            assertThat(probe.sessionId.get())
                .as("⭐ teammate 工具上下文必须继承 Leader 会话键 —— 改前为 SessionKeys.NO_SESSION 哨兵"
                    + "（后果：权限/transcript/file-history 全挂幻影会话键）")
                .isEqualTo(LEADER_SESSION);
            assertThat(probe.effectiveCwd.get())
                .as("⭐ teammate 有效工作目录必须是 Leader 的 cwd —— 改前为进程 user.dir"
                    + "（后果：teammate 读写用户项目文件判『在工作目录之外』）")
                .isEqualTo(leaderProject.toAbsolutePath());

            // 接线：Leader 归属装在 loop 上（显式传参，⛔ 不是 ThreadLocal / 进程级槽回放）
            AutonomousAgentLoop loop = spawner.registry().get(out.taskId()).orElseThrow();
            assertThat(loop.leaderParentTuc())
                .as("SpawnInProcess 必须把 Leader 归属父 TUC 装到运行循环上").isNotNull();
            assertThat(loop.leaderParentTuc().sessionId()).isEqualTo(LEADER_SESSION);
        } finally {
            spawner.registry().kill(out.taskId());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 反向：不带 Leader 归属 → 同一探针落到 no-session 哨兵（判据有鉴别力）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("反向：不带 Leader 归属时同一探针读到 no-session 哨兵（判据非恒真）")
    void withoutLeaderAttribution_contextFallsToNoSessionSentinel() throws Exception {
        // WHY：与正向用例同一工具、同一脚本化 LLM，唯一差别 = 是否经 setLeaderParentTuc 携带归属。
        //   若实现把判据写成恒真（sessionId 硬编码 / 落到某个进程级槽），本用例红。
        ProbeTool probe = new ProbeTool();
        ToolRegistry registry = new ToolRegistry().register(probe);
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setSubagentExecutor(subagentExecutor(registry, scriptedProviderFactory()));
        // ⛔ 刻意不调 loop.setLeaderParentTuc(...) —— 模拟「无会话来源」的降级路径
        loop.runOneTurn("do X", null);

        assertThat(probe.executed.await(10, TimeUnit.SECONDS)).as("工具轮必须跑完").isTrue();
        assertThat(probe.sessionId.get())
            .as("无 Leader 归属 ⇒ 保持 standalone 降级（哨兵值不变，⛔ 哨兵本身未被删除）")
            .isEqualTo(SessionKeys.NO_SESSION);
    }
}
