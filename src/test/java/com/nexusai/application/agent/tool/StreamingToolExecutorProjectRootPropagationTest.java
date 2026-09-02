package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-C D2-A/F3] 工具池线程 projectRoot 捕获-回放传播集成测试。
 *
 * <p>WHY (M-04 分叉点): {@code StreamingToolExecutor.executeAsync} 用
 * {@code CompletableFuture.runAsync(..., executor)} 在 fixed-8 池线程执行工具，
 * ThreadLocal 不跨线程 —— 不传播则工具体内 {@link AutoMemPaths#currentSessionProjectRoot()}
 * 读回落值（CLAUDE_PROJECT_DIR env ?? config-home），而非会话绑定 P。
 * 修复 = executeAsync 调度线程（会话线程）capture → 任务体开头 set → finally restore
 * （{@code withSessionProjectRoot}，对齐 LlmAgentLoop.run() :1637/:1645）。
 *
 * <p>RED 条件：删除 {@code withSessionProjectRoot} 包裹 → 池线程读到回落值 ≠ P。
 */
@DisplayName("IMP-C · 工具池线程 projectRoot 捕获-回放传播（StreamingToolExecutor.executeAsync）")
class StreamingToolExecutorProjectRootPropagationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AutoMemPaths.resetCurrentProjectRoot();
    }

    /** 在工具执行线程（池线程）读取会话 projectRoot 的探针工具。 */
    private static Tool probeTool(AtomicReference<String> seen, AtomicReference<String> threadName) {
        return new Tool() {
            @Override public String name() { return "probe"; }
            @Override public String description() { return "probe currentSessionProjectRoot on tool thread"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                threadName.set(Thread.currentThread().getName());
                seen.set(AutoMemPaths.currentSessionProjectRoot());
                return ToolResult.success(call.id(), "done");
            }
        };
    }

    @Test
    @DisplayName("会话绑定 P → 工具池线程 currentSessionProjectRoot()==P（捕获-回放传播）")
    void toolPoolThread_readsSessionProjectRoot() throws Exception {
        // WHY: 会话线程（测试线程模拟）setCurrentProjectRoot(P) 后 add() 调度 → executeAsync
        //      在调度线程捕获 P → runAsync 任务体 set → 工具 execute 在池线程读到 P。
        String P = Files.createTempDirectory("imp-c-tool-proj").toString();
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        ToolRegistry registry = new ToolRegistry().register(probeTool(seen, threadName));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());
        try {
            AutoMemPaths.setCurrentProjectRoot(P);          // 会话线程绑定
            exec.add(call("c1", "probe"));                  // 调度线程 = 会话线程
            List<ToolResult> results = exec.getRemainingResults();
            assertThat(results).hasSize(1);
            assertThat(threadName.get())
                .as("工具必须在池线程执行（非测试线程）—— 验证跨线程面")
                .isNotEqualTo(Thread.currentThread().getName());
            assertThat(seen.get())
                .as("工具池线程必须读到会话绑定 projectRoot（捕获-回放传播）")
                .isEqualTo(P);
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("任务体结束 finally restore → 池线程 projectRoot 恢复原值（防串台回归）")
    void toolPoolThread_restoresAfterExecution() throws Exception {
        // WHY: ODF-A1-R2 返工动机 = 防跨会话污染（AutoMemPaths:56-60）；restore 而非 remove。
        //      fixed(1) 池保证同一线程复用：工具完成后线程回池，再 submit 探针应读到
        //      恢复后的外层原值（null = 未绑定回落）。
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        ToolRegistry registry = new ToolRegistry().register(probeTool(seen, threadName));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());
        try {
            AutoMemPaths.setCurrentProjectRoot("C:/restore-check");
            exec.add(call("c1", "probe"));
            exec.getRemainingResults();
            assertThat(seen.get())
                .as("工具执行期间池线程读到注入值")
                .isEqualTo("C:/restore-check");

            String after = pool.submit(AutoMemPaths::captureCurrentProjectRoot).get(5, TimeUnit.SECONDS);
            assertThat(after)
                .as("任务结束后池线程 projectRoot 必须 restore 回外层原值（防线程池复用串台）")
                .isNull();
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("调度线程无绑定（null 捕获值）→ 不注入，池线程保持回落语义")
    void toolPoolThread_nullCapture_keepsFallback() throws Exception {
        // WHY: null 捕获值不 set（保持回落）—— 池线程与无会话上下文线程读到同一回落值。
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        ToolRegistry registry = new ToolRegistry().register(probeTool(seen, threadName));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());
        try {
            // 不 setCurrentProjectRoot —— 调度线程捕获 null
            exec.add(call("c1", "probe"));
            exec.getRemainingResults();
            String expectedFallback = AutoMemPaths.currentSessionProjectRoot(); // 测试线程同回落链
            assertThat(seen.get())
                .as("null 捕获值不注入 —— 池线程保持回落（CLAUDE_PROJECT_DIR env ?? config-home）")
                .isEqualTo(expectedFallback);
        } finally {
            pool.shutdownNow();
        }
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseContext context() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", new AbortController(), List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> java.util.Collections.unmodifiableSet(java.util.Set.of()));
    }
}
