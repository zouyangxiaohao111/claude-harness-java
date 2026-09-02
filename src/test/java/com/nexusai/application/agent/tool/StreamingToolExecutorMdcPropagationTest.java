package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [reqId MDC 传播] 工具池线程 MDC 捕获-回放传播集成测试（StreamingToolExecutor.executeAsync）。
 *
 * <p>WHY (决策 #65 父 V2/子 V1 工具集分叉): {@code StreamingToolExecutor.executeAsync} 用
 * {@code CompletableFuture.runAsync(..., executor)} 在 fixed-8 池线程执行工具，ThreadLocal 不跨线程
 * —— 不传播则池线程 {@link RequestContext#requestId()}=null → {@code TaskSystemConfig.isTodoV2Enabled()}
 * =false → 子代理（SubagentTool）回落 V1 TodoWrite，而父会话是 V2 Task 组 → 父 V2/子 V1 工具集分叉。
 * 修复 = executeAsync 调度线程（会话线程）capture MDC context map → 任务体开头
 * {@code MDC.setContextMap} → finally restore（{@code withSessionProjectRoot} 内，对齐
 * LlmAgentLoop STREAM_EXECUTOR mdcCtx 先例）。池线程回放后 RequestContext.requestId() 非 null →
 * sync 子代理判交互正确，且 async 子代理线程捕获父 MDC 时非 null（下游 SubagentTool 线程传播）。
 *
 * <p>RED 条件：删除 executeAsync 的 mdcCtx 捕获/回放 → 池线程 RequestContext.requestId() 读 null ≠ req-1。
 */
@DisplayName("reqId MDC 传播 · 工具池线程 MDC 捕获-回放（StreamingToolExecutor.executeAsync）")
class StreamingToolExecutorMdcPropagationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /** 在工具执行线程（池线程）读取 RequestContext 的探针工具。 */
    private static Tool probeTool(AtomicReference<String> seenReqId,
                                  AtomicReference<String> seenSessionId,
                                  AtomicReference<String> threadName) {
        return new Tool() {
            @Override public String name() { return "probe"; }
            @Override public String description() { return "probe RequestContext on tool thread"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                threadName.set(Thread.currentThread().getName());
                seenReqId.set(RequestContext.requestId());
                seenSessionId.set(RequestContext.sessionId());
                return ToolResult.success(call.id(), "done");
            }
        };
    }

    /**
     * 预热池线程：在 MDC 设置前先提交一个 no-op，使 fixed(1) 池线程在此刻创建。
     * WHY: logback MDC 基于纯 {@code ThreadLocal}（logback-classic 1.5.x LogbackMDCAdapter，
     * 已解 jar 类文件确认），<b>不</b>随 new Thread 继承（与 SubagentToolMdcPropagationTest:32-34
     * 「logback MDC 不随 new Thread 继承（实测）」同证）—— 池线程在父线程已设 MDC 后才创建也
     * 不会"继承"测试线程的 MDC，InheritableThreadLocal 机制 logback 并未采用。预热保证池线程
     * 创建于 MDC=null 时，工具任务内唯一 MDC 来源 = executeAsync 的捕获-回放，探针读到值只来自
     * 回放（而非任务间残留）。
     */
    private static void warmUpPool(ExecutorService pool) throws Exception {
        pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("父线程 set(sessionId+reqId) → 工具池线程 requestId()/sessionId() == 父值（捕获-回放传播）")
    void toolPoolThread_readsParentMdc() throws Exception {
        // WHY: 会话线程（测试线程模拟，等价 LlmAgentLoop 循环线程已设 MDC）set 后 add() 调度 →
        //      executeAsync 在调度线程捕获 MDC → runAsync 任务体 setContextMap → 工具在池线程读到父值。
        AtomicReference<String> seenReqId = new AtomicReference<>();
        AtomicReference<String> seenSessionId = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        warmUpPool(pool);
        ToolRegistry registry = new ToolRegistry().register(probeTool(seenReqId, seenSessionId, threadName));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());
        try {
            RequestContext.set("sess-parent", "req-parent");   // 父线程（会话线程）注入 MDC
            exec.add(call("c1", "probe"));                     // 调度线程 = 父线程
            List<ToolResult> results = exec.getRemainingResults();
            assertThat(results).hasSize(1);
            assertThat(threadName.get())
                .as("工具必须在池线程执行（非测试线程）—— 验证跨线程面")
                .isNotEqualTo(Thread.currentThread().getName());
            assertThat(seenReqId.get())
                .as("工具池线程必须读到父线程 requestId（MDC 捕获-回放传播）—— 否则 isTodoV2Enabled()=false 子代理回落 V1")
                .isEqualTo("req-parent");
            assertThat(seenSessionId.get())
                .as("工具池线程必须读到父线程 sessionId（MDC 捕获-回放传播）")
                .isEqualTo("sess-parent");
        } finally {
            RequestContext.clear();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("任务体结束 finally restore → 池线程 MDC 恢复原值（null → 清理，防线程池复用串台）")
    void toolPoolThread_restoresMdcAfterExecution() throws Exception {
        // WHY: 对齐 AutoMemPaths restore（restore 而非 clear —— 线程池复用防泄漏；null 捕获值不 set）。
        //      fixed(1) 池保证同一线程复用：预热创建线程于 MDC=null，工具任务回放后 finally restore null，
        //      再 submit 探针应读到 null（未泄漏到下一任务/会话）。
        AtomicReference<String> seenReqId = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        warmUpPool(pool);
        ToolRegistry registry = new ToolRegistry().register(
            probeTool(seenReqId, new AtomicReference<>(), threadName));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());
        try {
            RequestContext.set("sess-restore", "req-restore");
            exec.add(call("c1", "probe"));
            exec.getRemainingResults();
            assertThat(seenReqId.get())
                .as("工具执行期间池线程读到注入值")
                .isEqualTo("req-restore");

            String afterReqId = pool.submit(RequestContext::requestId).get(5, TimeUnit.SECONDS);
            assertThat(afterReqId)
                .as("任务结束后池线程 MDC 必须 restore 回外层原值（null → 清理，防线程池复用串台）")
                .isNull();
        } finally {
            RequestContext.clear();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("调度线程无 MDC（null 捕获值）→ 不注入，池线程保持空（null 捕获值不 set）")
    void toolPoolThread_nullParentMdc_keepsEmpty() throws Exception {
        // WHY: null 捕获值不 set（保持原状）—— 无会话上下文（cron/后台/启动期）池线程读到 null，
        //      与调度线程同回落语义（cron/后台仅 setSession → requestId() 恒 null → V1）。
        AtomicReference<String> seenReqId = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        warmUpPool(pool);
        ToolRegistry registry = new ToolRegistry().register(
            probeTool(seenReqId, new AtomicReference<>(), threadName));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());
        try {
            RequestContext.clear();
            exec.add(call("c1", "probe"));
            exec.getRemainingResults();
            assertThat(seenReqId.get())
                .as("null 捕获值不注入 —— 池线程 requestId 保持 null（回落语义）")
                .isNull();
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
            java.util.Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> java.util.Collections.unmodifiableSet(java.util.Set.of()));
    }
}
