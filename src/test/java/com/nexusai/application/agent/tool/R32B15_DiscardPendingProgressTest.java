package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b15 C7 + C8 + C10 + C11 · discard 完整 + pendingProgress + getCompletedResults
 * + interruptible state 综合验证 · 对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:413-415, 454-456} +
 * {@code StreamingToolExecutor.ts:407-440, 254-260}.
 *
 * <p><b>WHY (意图验证)</b>: 6 项 b15 状态机+错误修复的状态机部分集中在 StreamingToolExecutor.
 * 验证完整链路: discard 短路 → synthetic fallback, pendingProgress 入队 → 消费者 peek,
 * getCompletedResults 非阻塞 drain → 不重复 yield, interruptible state 同步.
 *
 * <p><b>关键不变式</b>:
 * <ul>
 *   <li>discard 后 add 工具立即拿到 synthetic streaming_fallback error.</li>
 *   <li>getRemainingResults 在 discard 时不阻塞等待 (生成 synthetic 短路返回).</li>
 *   <li>pendingProgress 入队后可 peek; clearPendingProgress 后队列为空.</li>
 *   <li>getCompletedResults 不重复返回已 drain 工具.</li>
 *   <li>所有 executing 工具 interruptBehavior="cancel" → interruptible=true; 否则 false.</li>
 * </ul>
 */
class R32B15_DiscardPendingProgressTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolRegistry registry;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "tool-test-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        registry = new ToolRegistry();
        registry.register(simpleTool("Read", "cancel", true));
        registry.register(simpleTool("Write", "block", false));
    }

    private Tool simpleTool(String name, String interruptBehavior, boolean concurrencySafe) {
        JsonNode schema = JSON.createObjectNode();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test " + name; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok: " + name);
            }
            @Override public boolean isConcurrencySafe(JsonNode input) {
                return concurrencySafe;
            }
            @Override public String interruptBehavior() {
                return interruptBehavior;
            }
        };
    }

    private ToolUseBlock call(String id, String name) {
        JsonNode input = JSON.createObjectNode().put("foo", "bar");
        return new ToolUseBlock(id, name, input);
    }

    @Test
    @DisplayName("C7: discard 后 add 工具立即拿到 synthetic streaming_fallback")
    void discardAfterAddReturnsSynthetic() {
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.discard();
        exec.add(call("t-1", "Read"));

        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("t-1"))
            .as("discard 后 synthetic 结果必须标记 error（IMP-C2 后 isError 由执行器推导）")
            .isTrue();
        assertThat(((String) results.get(0).data())).contains("Streaming fallback");
    }

    @Test
    @DisplayName("C7: getRemainingResults 在 discard 状态不阻塞")
    void getRemainingResultsDoesNotBlockOnDiscard() {
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.add(call("t-1", "Read"));
        exec.discard();

        long start = System.currentTimeMillis();
        List<ToolResult> results = exec.getRemainingResults();
        long elapsed = System.currentTimeMillis() - start;

        // 1s 内应返回 (无 discard 时需等 in-flight promise)
        assertThat(elapsed).isLessThan(1000);
        assertThat(results).isNotEmpty();
        assertThat(exec.getResultErrorFlags().get("t-1"))
            .as("discard 后 synthetic 结果必须标记 error")
            .isTrue();
    }

    @Test
    @DisplayName("C7: discard 后执行中工具不挂起 · 立刻 synthetic")
    void discardDuringExecutionGeneratesSynthetic() throws InterruptedException {
        // 注册一个会阻塞的工具
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tool blocking = new Tool() {
            @Override public String name() { return "Blocking"; }
            @Override public String description() { return "blocking tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                started.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { }
                return ToolResult.success(call.id(), "should not reach here normally");
            }
        };
        registry.register(blocking);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.add(call("t-block", "Blocking"));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        exec.discard();

        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).isNotEmpty();
        // discard 后应得到 synthetic (含 "Streaming fallback"), 而非真实 "should not reach"
        boolean synthetic = results.stream()
            .anyMatch(r -> ((String) r.data()).contains("Streaming fallback"));
        assertThat(synthetic).isTrue();
        assertThat(exec.getResultErrorFlags().get("t-block"))
            .as("discard 时 EXECUTING 工具 synthetic 结果必须标记 error")
            .isTrue();
        release.countDown();
        pool.shutdownNow();
    }

    @Test
    @DisplayName("C8: pendingProgress 入队后可 peek + clear")
    void pendingProgressEnqueueAndClear() {
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.enqueueProgress(new Tool.ToolProgress("t-1", "progress-1"));
        exec.enqueueProgress(new Tool.ToolProgress("t-1", "progress-2"));

        List<Tool.ToolProgress> peeked = exec.peekPendingProgress();
        assertThat(peeked).hasSize(2);
        assertThat(peeked.get(0).data()).isEqualTo("progress-1");

        exec.clearPendingProgress();
        assertThat(exec.peekPendingProgress()).isEmpty();
    }

    @Test
    @DisplayName("C10: getCompletedResults 不重复返回已 drain 工具")
    void getCompletedResultsDoesNotRepeat() throws InterruptedException {
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.add(call("t-1", "Read"));
        exec.add(call("t-2", "Write"));
        // 等工具完成 (后台线程非常快)
        Thread.sleep(100);

        // 第一次 drain
        List<ToolResult> first = exec.getCompletedResults();
        assertThat(first).hasSize(2);

        // 第二次 drain → 空 (drainedIds 已记录)
        List<ToolResult> second = exec.getCompletedResults();
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("C10: getCompletedResults 在工具尚未完成时返回空 (非阻塞)")
    void getCompletedResultsNonBlockingEmpty() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tool slow = new Tool() {
            @Override public String name() { return "Slow"; }
            @Override public String description() { return "slow tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                started.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { }
                return ToolResult.success(call.id(), "slow");
            }
        };
        registry.register(slow);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.add(call("t-slow", "Slow"));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        // 工具未完成 → drain 返回空 (非阻塞, 不等 2s)
        long start = System.currentTimeMillis();
        List<ToolResult> drained = exec.getCompletedResults();
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(500);
        assertThat(drained).isEmpty();

        release.countDown();
        Thread.sleep(100); // 等待 promise 完成

        // 完成后再 drain → 拿到结果
        List<ToolResult> secondDrain = exec.getCompletedResults();
        assertThat(secondDrain).hasSize(1);
        assertThat(exec.getResultErrorFlags()).containsKey("t-slow");
        pool.shutdownNow();
    }

    @Test
    @DisplayName("C8: enqueueProgress 唤醒等待者 · nextEventAvailable 立即完成")
    void enqueueProgressWakesWaiter() throws Exception {
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.add(call("t-1", "Read"));
        exec.getRemainingResults(); // 让工具完成

        // 此时有等待者时, 入队 progress 应唤醒
        java.util.concurrent.CompletableFuture<Void> waiter = exec.nextEventAvailable();
        exec.enqueueProgress(new Tool.ToolProgress("t-1", "wakeup"));
        waiter.get(1, TimeUnit.SECONDS); // 应立即完成
        assertThat(waiter.isDone()).isTrue();
    }

    @Test
    @DisplayName("C11: interruptible state 单 cancel 工具 → true")
    void interruptibleStateAllCancel() {
        AgentState state = new AgentState("test", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.setAgentState(state);

        // Read 工具 interruptBehavior="cancel" (注册时设置)
        exec.add(call("t-1", "Read"));

        assertThat(state.hasInterruptibleToolInProgress()).isTrue();
    }

    @Test
    @DisplayName("C11: interruptible state 含 block 工具 → false")
    void interruptibleStateHasBlock() throws InterruptedException {
        AgentState state = new AgentState("test", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.setAgentState(state);

        // Write 工具 interruptBehavior="block" → interruptible 应为 false
        exec.add(call("t-write", "Write"));
        exec.add(call("t-read", "Read")); // 与 unsafe (Write) 并发 → 等

        // Write 先执行 (或并行); 即便 Read 也设为 cancel, Write 阻断了 interruptible
        Thread.sleep(50); // 给点时间
        assertThat(state.hasInterruptibleToolInProgress()).isFalse();

        // 收尾
        exec.getRemainingResults();
    }

    @Test
    @DisplayName("C11: interruptible state 工具完成后 → false (无 EXECUTING)")
    void interruptibleStateAfterCompletion() throws Exception {
        // 注册一个慢工具, 验证执行中=true, 完成后=false
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tool slowCancel = new Tool() {
            @Override public String name() { return "SlowCancel"; }
            @Override public String description() { return "slow cancel tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public String interruptBehavior() { return "cancel"; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                started.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { }
                return ToolResult.success(call.id(), "done");
            }
        };
        registry.register(slowCancel);

        AgentState state = new AgentState("test", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.setAgentState(state);

        exec.add(call("t-slow", "SlowCancel"));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        // 工具在 EXECUTING (interruptBehavior=cancel) → true
        assertThat(state.hasInterruptibleToolInProgress()).isTrue();

        // 让工具完成
        release.countDown();
        Thread.sleep(100);
        // 完成后无 EXECUTING → false
        assertThat(state.hasInterruptibleToolInProgress()).isFalse();
        pool.shutdownNow();
    }

    @Test
    @DisplayName("C11: setAgentState(null) 保持字段 null · interruptible 同步 noop")
    void setAgentStateNullSafe() {
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.setAgentState(null);
        exec.add(call("t-1", "Read"));
        // 不抛异常即可 (无 state 注入时 interruptible 同步跳过)
    }
}