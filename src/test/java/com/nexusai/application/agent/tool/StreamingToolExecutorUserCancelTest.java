package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [user-cancel] 工具卡死时，用户停止必须让 turn 退出。
 *
 * <p><b>缺陷现场（2026-11-13 实测）</b>：{@code mcp__computer-use__open_application} 的
 * MCP future 卡 5min+，{@code AgentLoopContext.runTools} 在
 * {@code StreamingToolExecutor.drainNextBatch} 的 {@code CompletableFuture.anyOf(...).join()}
 * 上永久阻塞；用户点停止（{@code AgentState.abortStream("user-cancel")}）只打断 LLM 流，
 * turn 永远停在 running。
 *
 * <p><b>判据（含正向对照）</b>：
 * <ol>
 *   <li>探针工具必须真的开始执行（否则本用例空绿）；</li>
 *   <li>未取消时 drain 必须仍在等待（证明卡点真实存在，而非工具已结束）；</li>
 *   <li>取消后 drain 必须在 2s 内返回，结果为 {@code user_interrupted} synthetic
 *       （CC REJECT_MESSAGE），且 {@code isError=true}。</li>
 * </ol>
 *
 * <p><b>反向实验</b>：把 {@code drainNextBatch} 的 {@code get(250ms)} 改回
 * {@code join()}（或删掉 {@code userCancelled()} 早退分支）⇒ 第 3 步超时 ⇒ 本用例变红。
 */
@DisplayName("[user-cancel] 工具卡死时用户停止必须让 drain 立即收口")
class StreamingToolExecutorUserCancelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 永不返回的工具（模拟 MCP tools/call 卡在无超时 future 上）。 */
    private static final class HangingTool implements Tool {
        final CountDownLatch started = new CountDownLatch(1);

        @Override public String name() { return "HangProbe"; }

        @Override public String description() { return "hang probe"; }

        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }

        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            started.countDown();
            try {
                // 无超时的外部调用等价物：永不返回
                new CountDownLatch(1).await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success(call.id(), "unreachable");
        }
    }

    @Test
    @DisplayName("工具阻塞时 abortStream('user-cancel') → drain 在 2s 内返回 user_interrupted synthetic")
    void userCancelUnblocksHangingToolDrain() throws Exception {
        HangingTool tool = new HangingTool();
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = new StreamingToolExecutor(
                new ToolRegistry().register(tool),
                ToolUseContext.of(UUID.randomUUID(), "sess-cancel-probe"));
        exec.setAgentState(state);
        exec.add(new ToolUseBlock("hang-1", "HangProbe", JSON.createObjectNode()), null, null);

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<List<ToolResult>> drain =
                    CompletableFuture.supplyAsync(exec::getRemainingResults, caller);

            assertThat(tool.started.await(2, TimeUnit.SECONDS))
                    .as("正向对照：探针工具必须真的开始执行（否则本用例空绿）")
                    .isTrue();

            // 覆盖 ≥2 个 250ms 轮询周期：未取消时 drain 必须还挂着
            Thread.sleep(700);
            assertThat(drain.isDone())
                    .as("正向对照：未取消时不得返回 —— 证明工具确实卡住（修复前此处也成立）")
                    .isFalse();

            state.abortStream("user-cancel");

            List<ToolResult> results = drain.get(2, TimeUnit.SECONDS);
            assertThat(results).as("卡住的那个工具必须拿到一条 synthetic 结果").hasSize(1);
            assertThat(String.valueOf(results.get(0).data()))
                    .as("文案必须是 CC REJECT_MESSAGE（user_interrupted），不是 streaming_fallback")
                    .contains("doesn't want to proceed")
                    .doesNotContain("Streaming fallback");
            assertThat(exec.getResultErrorFlags())
                    .as("synthetic 结果必须标记 isError")
                    .containsEntry("hang-1", true);
        } finally {
            caller.shutdownNow();
        }
    }
}
