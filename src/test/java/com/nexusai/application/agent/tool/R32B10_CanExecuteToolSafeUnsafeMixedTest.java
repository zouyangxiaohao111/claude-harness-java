package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** R32-b10 P1-2: safe 与 unsafe 混合时，unsafe 独占期间 safe 不得执行。 */
class R32B10_CanExecuteToolSafeUnsafeMixedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unsafeInFlightRejectsSafeThenSafeRecoversAfterUnsafeCompletes() throws Exception {
        CountDownLatch unsafeEntered = new CountDownLatch(1);
        CountDownLatch releaseUnsafe = new CountDownLatch(1);
        AtomicInteger safeExecutions = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();

        Tool unsafe = blockingTool("mixed_unsafe", false, unsafeEntered, releaseUnsafe, safeExecutions);
        Tool safe = blockingTool("mixed_safe", true, new CountDownLatch(0), new CountDownLatch(0), safeExecutions);
        registry.register(unsafe);
        registry.register(safe);
        StreamingToolExecutor executor = new StreamingToolExecutor(registry, pool, context());

        // 先建立 unsafe in-flight，再加入 [safe, unsafe, safe, unsafe] 混合批次。
        executor.add(call("u0", unsafe.name()));
        assertThat(unsafeEntered.await(10, TimeUnit.SECONDS)).isTrue();
        executor.add(call("s1", safe.name()));
        executor.add(call("u2", unsafe.name()));
        executor.add(call("s3", safe.name()));
        executor.add(call("u4", unsafe.name()));

        Thread.sleep(100);
        assertThat(safeExecutions.get())
            .as("unsafe 执行期间 safe 必须被 canExecuteTool 拒绝")
            .isZero();

        releaseUnsafe.countDown();
        executor.getRemainingResults();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(safeExecutions.get())
            .as("unsafe 完成后，排队的 safe 必须恢复可执行")
            .isEqualTo(2);
    }

    private static Tool blockingTool(String name, boolean safe, CountDownLatch entered,
                                     CountDownLatch release, AtomicInteger safeExecutions) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "mixed concurrency test tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return safe; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                if (safe) safeExecutions.incrementAndGet();
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolResult.success(call.id(), "done");
            }
        };
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseContext context() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", java.nio.file.Paths.get("."),
            current -> Collections.unmodifiableSet(java.util.Set.of()));
    }
}
