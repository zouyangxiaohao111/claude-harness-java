package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [S1-T3 · 环 A4/A5] 流式工具执行链的 teammate 身份改为 <b>TUC 显式载体</b>（不再有 thread-local）。
 *
 * <p><b>覆盖的真实线程链</b>（非同线程 mock）：
 * <pre>
 *   调度线程（任意 —— 本测试用 STREAM_EXECUTOR 虚拟线程，对齐生产 SSE 回调）
 *     → StreamingToolExecutor.add() 读 ctx.teammateIdentity() 快照到 TrackedTool
 *     → CompletableFuture.runAsync ForkJoinPool（tool-exec 池线程）执行 SubagentTool.execute(..., identity)
 *     → CC AgentTool.tsx:272/278 守卫按 identity 判定
 * </pre>
 *
 * <p><b>WHY（规则九）</b>：原实现是「loop 线程捕获 ThreadLocal → 虚拟线程回放 → 工具线程再回放」
 * 三段式；plain ThreadLocal 不跨线程，任一环漏回放即静默降级为「恒非 teammate」⇒ 守卫在生产
 * 不触发（teammate 能 spawn teammate，违反 CC）。本批改为显式载体后，<b>任何线程</b>上只要
 * TUC 带身份，守卫就命中 —— 本测试的两个方向（有身份 / 无身份）锁定该判据既非恒真也非恒假。
 *
 * <p>⚠️ 本测试<b>刻意不设任何 thread-local</b>（虚拟线程与池线程均为干净线程）：若实现回退到
 * 读 ThreadLocal，正向用例必然失败 —— 这就是「不再有 ThreadLocal 载体」的可证伪点。
 */
@DisplayName("S1-T3 · 流式链 teammate 身份显式载体（无 thread-local）")
class StreamingToolExecutorNoThreadLocalIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** STREAM_EXECUTOR 等价 · 对齐 LlmAgentLoop:206 newVirtualThreadPerTaskExecutor。 */
    private ExecutorService streamExecutor;

    @BeforeEach
    void setUp() {
        streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
        System.setProperty("nexusai.experimental.agent-teams", "true");
    }

    @AfterEach
    void tearDown() {
        streamExecutor.shutdownNow();
        System.clearProperty("nexusai.experimental.agent-teams");
    }

    private static TeammateIdentity researcherIdentity() {
        return new TeammateIdentity(
            "researcher@team-x", "researcher", "team-x", null, false, "parent-session");
    }

    private static ToolUseContext tucWithIdentity(TeammateIdentity identity) {
        return new ToolUseContext(java.util.UUID.randomUUID(), "sess-stream-id",
                com.nexusai.application.agent.permission.PermissionMode.DEFAULT, java.util.Map.of())
            .withTeammateIdentity(identity);
    }

    private static ToolUseBlock teammateSpawnCall() {
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Do research");
        input.put("prompt", "Research X");
        input.put("name", "researcher");
        input.put("team_name", "team-x");
        return new ToolUseBlock("tool-stream-teammate", "Agent", input);
    }

    /** 组装真实 SubagentTool（mock spawner）+ ToolRegistry；identity 经 TUC 显式传入。 */
    private static StreamingToolExecutor buildStreamingExec(TeammateIdentity identity,
                                                            SpawnInProcess spawner) throws Exception {
        SubagentTool tool = new SubagentTool(
            List.of(), null, null, null, "gpt-4", "", null,
            Files.createTempDirectory("s1-t3"), List.of());
        tool.setSpawnInProcess(spawner);
        ToolUseContext ctx = identity != null ? tucWithIdentity(identity) : tucWithIdentity(null);
        return new StreamingToolExecutor(new ToolRegistry().register(tool), ctx);
    }

    /** 在「干净」虚拟线程上 add（不设任何 thread-local），等待 add 完成。 */
    private void addOnVirtualThread(StreamingToolExecutor exec, ToolUseBlock call) throws Exception {
        final CountDownLatch addDone = new CountDownLatch(1);
        streamExecutor.execute(() -> {
            try {
                exec.add(call, null, null);
            } finally {
                addDone.countDown();
            }
        });
        if (!addDone.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("流式 add() 未在 5s 内完成");
        }
    }

    @Test
    @DisplayName("正向：TUC 带 teammate 身份 → 虚拟线程 add → 池线程 execute 命中 CC:272 守卫（拒绝 teammate 嵌套 spawn）")
    void teammateIdentityPresent_guardFires() throws Exception {
        TeammateIdentity identity = researcherIdentity();
        SubagentTool tool = new SubagentTool(
            List.of(), null, null, null, "gpt-4", "", null,
            Files.createTempDirectory("s1-t3-fwd"), List.of());
        tool.setSpawnInProcess(mock(SpawnInProcess.class));
        StreamingToolExecutor exec = new StreamingToolExecutor(
            new ToolRegistry().register(tool), tucWithIdentity(identity));

        addOnVirtualThread(exec, teammateSpawnCall());

        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("tool-stream-teammate"))
            .as("identity 经 TUC 显式下传 → isTeammate(identity) 命中 → CC AgentTool.tsx:272 守卫拒绝")
            .isTrue();
        assertThat(String.valueOf(results.get(0).data()))
            .contains("Teammates cannot spawn other teammates");
    }

    @Test
    @DisplayName("反向：TUC 无 teammate 身份（null）→ 同一调用不触发守卫 → 走 spawn（证明判据有鉴别力）")
    void teammateIdentityAbsent_guardDoesNotFire() throws Exception {
        // WHY: 与正向用例**同一夹具、同一调用**，唯一差别 = ctx 的 identity。若实现把判据写成
        //   恒真（如硬编码 true）→ 本用例红；若写成恒假（读不到载体）→ 正向用例红。两侧同时锁定。
        SpawnInProcess spawner = mock(SpawnInProcess.class);
        when(spawner.spawnInProcessTeammate(any(), any()))
            .thenReturn(new SpawnInProcess.InProcessSpawnOutput(
                true, "researcher@team-x", "t1a2b3c4d", null, null));
        StreamingToolExecutor exec = buildStreamingExec(null, spawner);

        addOnVirtualThread(exec, teammateSpawnCall());

        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("tool-stream-teammate"))
            .as("无 identity → 守卫不触发（非 teammate 语义，行为与主会话一致）")
            .isFalse();
        JsonNode data = (JsonNode) results.get(0).data();
        assertThat(data.get("status").asText()).isEqualTo("teammate_spawned");
    }

    @Test
    @DisplayName("环 A4：TrackedTool 的身份快照 = add() 时 ctx 的同一 identity 实例（类型为 TeammateIdentity，非旧 ThreadLocal 载体）")
    void trackedToolSnapshotsSameIdentityInstance() throws Exception {
        TeammateIdentity identity = researcherIdentity();
        StreamingToolExecutor exec = buildStreamingExec(identity, mock(SpawnInProcess.class));

        addOnVirtualThread(exec, teammateSpawnCall());

        Field toolsField = StreamingToolExecutor.class.getDeclaredField("tools");
        toolsField.setAccessible(true);
        Map<?, ?> tools = (Map<?, ?>) toolsField.get(exec);
        Object tracked = tools.get("tool-stream-teammate");
        assertThat(tracked).as("call 已入队").isNotNull();

        Field snapField = tracked.getClass().getDeclaredField("capturedTeammateIdentity");
        snapField.setAccessible(true);
        assertThat(snapField.getType())
            .as("快照字段类型必须是 TeammateIdentity（类型消失 ⇒ 编译期即证伪旧载体）")
            .isEqualTo(TeammateIdentity.class);
        assertThat(snapField.get(tracked))
            .as("add() 从 ctx.teammateIdentity() 取的必须是**同一实例**（sparse-edge 同实例约束）")
            .isSameAs(identity);
    }

    @Test
    @DisplayName("反向（载体侧）：add() 只认 ctx，不认线程 —— 另一干净线程上无 TUC 身份时快照必为 null")
    void snapshotIsNullWithoutCtxIdentityOnPristineThread() throws Exception {
        StreamingToolExecutor exec = buildStreamingExec(null, mock(SpawnInProcess.class));

        addOnVirtualThread(exec, teammateSpawnCall());

        Field toolsField = StreamingToolExecutor.class.getDeclaredField("tools");
        toolsField.setAccessible(true);
        Map<?, ?> tools = (Map<?, ?>) toolsField.get(exec);
        Object tracked = tools.get("tool-stream-teammate");
        Field snapField = tracked.getClass().getDeclaredField("capturedTeammateIdentity");
        snapField.setAccessible(true);
        assertThat(snapField.get(tracked))
            .as("干净线程 + 无 ctx 身份 → 快照 null（若实现改成读 thread-local 且该线程恰有残留，本断言红）")
            .isNull();
    }
}
