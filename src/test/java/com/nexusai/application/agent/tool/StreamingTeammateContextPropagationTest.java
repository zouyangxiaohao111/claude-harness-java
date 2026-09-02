package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [GAP-R1] 流式路径 teammate context 跨线程传播 · 对齐 CC AsyncLocalStorage 跨异步传播。
 *
 * <p>覆盖真实线程链（非同线程 mock）：
 * <pre>
 *   runner 线程(已被 runWithTeammateContext 包, 对齐 SpawnInProcess:285)
 *     → STREAM_EXECUTOR 虚拟线程(SSE 回调 add, 对齐 LlmAgentLoop:3359/3306)
 *     → StreamingToolExecutor.add():563 捕获 t.capturedTeammateContext
 *     → CompletableFuture.runAsync ForkJoinPool 工具 execute(StreamingToolExecutor:1128/1540)
 *     → SubagentTool.execute → isTeammate()/isInProcessTeammate() 守卫
 * </pre>
 *
 * <p>WHY: 缺陷 = 虚拟线程不继承创建线程的 plain ThreadLocal → add() 捕获 null →
 * 工具 execute 跳过 runWithTeammateContext → isTeammate() 恒 false → CC AgentTool.tsx:272/278
 * 守卫（"Teammates cannot spawn other teammates" / "cannot spawn background agents"）生产不触发。
 * 修复 = loop 线程捕获 + 虚拟线程回放（与 LlmAgentLoop MDC 回放同模式）。
 */
@DisplayName("GAP-R1 · 流式路径 teammate context 跨线程传播（真实虚拟线程池）")
class StreamingTeammateContextPropagationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** STREAM_EXECUTOR 等价 · 对齐 LlmAgentLoop:206 newVirtualThreadPerTaskExecutor。 */
    private ExecutorService streamExecutor;

    @BeforeEach
    void setUp() {
        streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
        // swarms 启用（对齐 SubagentToolTeammateSpawnBranchTest 模式）
        System.setProperty("nexusai.experimental.agent-teams", "true");
    }

    @AfterEach
    void tearDown() {
        streamExecutor.shutdownNow();
        System.clearProperty("nexusai.experimental.agent-teams");
    }

    private static TeammateContext newTeammateCtx(String agentId) {
        return TeammateContext.create(new TeammateContext.TeammateConfig(
            agentId, "researcher", "team-x", null, false, "parent-session", null));
    }

    private static ToolUseBlock teammateSpawnCall() {
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Do research");
        input.put("prompt", "Research X");
        input.put("name", "researcher");
        input.put("team_name", "team-x");
        return new ToolUseBlock("tool-stream-teammate", "Agent", input);
    }

    /** 组装真实 SubagentTool（mock spawner）+ ToolRegistry + StreamingToolExecutor。 */
    private static StreamingToolExecutor buildStreamingExec() throws Exception {
        SubagentTool tool = new SubagentTool(
            List.of(), null, null, null, "gpt-4", "", null,
            Files.createTempDirectory("wf8-gap-r1"), List.of());
        tool.setSpawnInProcess(mock(SpawnInProcess.class));
        return new StreamingToolExecutor(new ToolRegistry().register(tool));
    }

    @Test
    @DisplayName("runner 捕获 + 虚拟线程回放 → add() 捕获正确 context → SubagentTool 守卫触发（CC AgentTool.tsx:272）")
    void streamingAdd_withReplay_guardFires() throws Exception {
        // WHY: 修复生效 = STREAM_EXECUTOR 边界捕获/回放（LlmAgentLoop GAP-R1）使流式 add() 捕获到
        //   runner 的 TeammateContext → 工具 execute 恢复 → isTeammate() 命中 → CC :272
        //   "Teammates cannot spawn other teammates" 守卫返回错误（teammate 不能再 spawn teammate）。
        TeammateContext ctx = newTeammateCtx("researcher@team-x");
        StreamingToolExecutor exec = buildStreamingExec();

        // ── runner 线程: 被 runWithTeammateContext 包（对齐 SpawnInProcess:285）──
        final CountDownLatch addDone = new CountDownLatch(1);
        TeammateContext.runWithTeammateContext(ctx, () -> {
            // ── STREAM_EXECUTOR 边界: loop 线程捕获, 虚拟线程内回放后 add()（对齐 LlmAgentLoop 修复）──
            final TeammateContext capturedOnRunner = TeammateContext.getTeammateContext();
            streamExecutor.execute(() -> {
                try {
                    TeammateContext.runWithTeammateContext(capturedOnRunner, () -> {
                        exec.add(teammateSpawnCall(), null, null); // add():563 捕获
                        return null;
                    });
                } finally {
                    addDone.countDown();
                }
            });
            try {
                if (!addDone.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("流式 add() 未在 5s 内完成");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return null;
        });

        // ── 工具执行线程（ForkJoinPool）已由 runWithTeammateContext(captured) 恢复 ──
        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).hasSize(1);
        ToolResult<?> r = results.get(0);
        assertThat(exec.getResultErrorFlags().get("tool-stream-teammate"))
            .as("isTeammate() 命中 → CC AgentTool.tsx:272 守卫错误（证明 context 经虚拟线程链传播到工具执行线程）")
            .isTrue();
        assertThat(String.valueOf(r.data()))
            .contains("Teammates cannot spawn other teammates");
    }

    @Test
    @DisplayName("虚拟线程不回放（原缺陷形态）→ add() 捕获 null → 守卫不触发 → 走 spawn（证明缺陷存在）")
    void streamingAdd_withoutReplay_guardNotFires() throws Exception {
        // WHY: 对照组 = 缺陷形态。plain ThreadLocal 不跨虚拟线程（JDK 25 实证）→ 虚拟线程直接 add()
        //   捕获 null → execute 不包装 → isTeammate() false → 守卫不触发 → SubagentTool 走 spawn。
        //   证明 GAP-R1 修复（回放）是守卫生产生效的必要条件。
        SubagentTool tool = new SubagentTool(
            List.of(), null, null, null, "gpt-4", "", null,
            Files.createTempDirectory("wf8-gap-r1-control"), List.of());
        SpawnInProcess spawner = mock(SpawnInProcess.class);
        when(spawner.spawnInProcessTeammate(any(), any()))
            .thenReturn(new SpawnInProcess.InProcessSpawnOutput(
                true, "researcher@team-x", "t1a2b3c4d", null, null, null));
        tool.setSpawnInProcess(spawner);
        StreamingToolExecutor exec = new StreamingToolExecutor(new ToolRegistry().register(tool));

        // ── runner 线程设 ctx, 但虚拟线程直接 add()（无回放, 缺陷形态）──
        TeammateContext ctx = newTeammateCtx("researcher@team-x");
        final CountDownLatch addDone = new CountDownLatch(1);
        TeammateContext.runWithTeammateContext(ctx, () -> {
            streamExecutor.execute(() -> {
                try {
                    exec.add(teammateSpawnCall(), null, null); // 虚拟线程无 context → 捕获 null
                } finally {
                    addDone.countDown();
                }
            });
            try {
                if (!addDone.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("流式 add() 未在 5s 内完成");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return null;
        });

        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).hasSize(1);
        ToolResult<?> r = results.get(0);
        assertThat(exec.getResultErrorFlags().get("tool-stream-teammate"))
            .as("无回放 → isTeammate() false → 守卫不触发（缺陷形态: teammate 可 spawn teammate, 违反 CC）")
            .isFalse();
        JsonNode data = (JsonNode) r.data();
        assertThat(data.get("status").asText()).isEqualTo("teammate_spawned");
    }
}
