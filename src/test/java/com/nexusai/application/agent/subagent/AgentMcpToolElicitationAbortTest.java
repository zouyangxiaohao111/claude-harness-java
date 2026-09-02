package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.mcp.McpElicitationStateMachine;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S05 B9] AgentMcpTool elicitation 接入（-32042 → 共享状态机）+ abort 传播 ·
 * 对齐 CC callMCPToolWithUrlElicitationRetry（client.ts:2813-3027）+ signal（:1869/:2958-2962）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: 旧执行面不接 -32042（elicitation 缺失 →
 * 需要用户打开 URL 的 MCP 工具直接报错）、abort 不传播（取消后 in-flight 调用悬挂）。
 * 本测试锁：
 * <ol>
 *   <li>decline 路径：-32042（合法 elicitations）+ auto-decline responder → decline 文本
 *       content（isError=false，CC :3008-3016，fail-closed）</li>
 *   <li>重试超 MAX_URL_ELICITATION_RETRIES=3 → 抛原错误（CC :2872-2874）→ failed 进度</li>
 *   <li>abort 传播：ctx.abortController().abort() → in-flight future.cancel → failed 进度 +
 *       错误结果（不悬挂）</li>
 * </ol>
 */
@DisplayName("[S05 B9] AgentMcpTool elicitation 接入 + abort 传播")
class AgentMcpToolElicitationAbortTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** transport 层 -32042 错误消息形态 · 镜像生产 transport（HttpMcpTransport:323 等
     *  {@code "JSON-RPC error: " + node.get("error")}，JsonNode.toString() 引号字段名）。 */
    static String elicitationErrorJson(String elicitationId) {
        ObjectNode err = MAPPER.createObjectNode();
        ObjectNode error = err.putObject("error");
        error.put("code", -32042).put("message", "URL elicitation required");
        ObjectNode data = error.putObject("data");
        data.putArray("elicitations").addObject()
            .put("mode", "url")
            .put("url", "http://localhost/approve")
            .put("elicitationId", elicitationId)
            .put("message", "需要用户打开 URL 审批");
        return "JSON-RPC error: " + err.path("error");
    }

    static CompletableFuture<JsonNode> elicitationError(String elicitationId) {
        return CompletableFuture.failedFuture(
            new IllegalStateException(elicitationErrorJson(elicitationId)));
    }

    /** 恒返 -32042 的 fake channel（可配置 elicitationId）。 */
    static class ElicitationChannel implements AgentMcpServers.McpToolChannel {
        final String elicitationId;
        final AtomicInteger callCount = new AtomicInteger();
        ElicitationChannel(String elicitationId) { this.elicitationId = elicitationId; }

        @Override
        public CompletableFuture<JsonNode> call(Map<String, Object> args, Map<String, Object> meta) {
            callCount.incrementAndGet();
            return elicitationError(elicitationId);
        }

        @Override
        public void resetSession() {}
    }

    /** 永不完成的 fake channel（abort 传播用）。 */
    static class HangingChannel implements AgentMcpServers.McpToolChannel {
        @Override
        public CompletableFuture<JsonNode> call(Map<String, Object> args, Map<String, Object> meta) {
            return new CompletableFuture<>();
        }
        @Override
        public void resetSession() {}
    }

    private static ToolUseBlock call(String id) {
        return new ToolUseBlock(id, "mcp__fs__read", MAPPER.createObjectNode());
    }

    private static List<String> statuses(List<Tool.ToolProgress> progress) {
        return progress.stream()
            .map(p -> String.valueOf(((Map<?, ?>) p.data()).get("status")))
            .toList();
    }

    @Test
    @DisplayName("-32042 + auto-decline responder → decline 文本 content（isError=false，CC :3008-3016）")
    void elicitation_decline_returnsDeclineTextContent() {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        // fail-closed：responder 未接线默认 auto-decline；显式接线保证确定性
        machine.setResponder((serverName, e) -> "decline");
        ElicitationChannel channel = new ElicitationChannel("e-1");
        AgentMcpTool t = new AgentMcpTool("fs", "read", "mcp__fs__read",
            MAPPER.createObjectNode(), null, null, "Read",
            channel, 60_000, machine);
        List<Tool.ToolProgress> progress = new ArrayList<>();

        AgentToolResult<?> raw = t.execute(call("tu-1"), null, progress::add);

        assertThat(raw).isInstanceOf(ToolResult.class);
        ToolResult<?> result = (ToolResult<?>) raw;
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("decline 是正常 content 而非错误结果（CC :3013-3016 返回 content 文本）")
            .isFalse();
        assertThat(String.valueOf(result.data()))
            .contains("URL elicitation was declined")
            .contains("read");
        // decline 路径不发射 completed（与 McpServerTool 生产轨一致：decline 直接返回）
        assertThat(statuses(progress)).containsExactly("started");
    }

    @Test
    @DisplayName("重试超 MAX_URL_ELICITATION_RETRIES=3 → 抛原错误 + failed 进度（CC :2872-2874）")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void elicitation_retryExhausted_throwsOriginalError() throws Exception {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        // accept（Phase 1 同意）→ Phase 2 等待用户 Retry now；测试线程驱动 retryConfirm
        machine.setResponder((serverName, e) -> "accept");
        // 兜底：任一决策迟到 → 超时 fail-closed（decline 文本），防悬挂
        machine.setDecisionTimeoutMs(5_000);
        ElicitationChannel channel = new ElicitationChannel("e-loop");
        AgentMcpTool t = new AgentMcpTool("fs", "read", "mcp__fs__read",
            MAPPER.createObjectNode(), null, null, "Read",
            channel, 60_000, machine);
        List<Tool.ToolProgress> progress = new ArrayList<>();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentToolResult<?>> fut = executor.submit(
                () -> t.execute(call("tu-2"), null, progress::add));
            // 用户点 Retry now ×3（每次消费一个 awaitRetryDecision；earlyDecisions 缓存兜底竞态）
            for (int i = 0; i < 3; i++) {
                Thread.sleep(100);
                machine.retryConfirm("e-loop");
            }
            AgentToolResult<?> raw = fut.get(20, TimeUnit.SECONDS);

            assertThat(raw).isInstanceOf(ToolResult.class);
            ToolResult<?> result = (ToolResult<?>) raw;
            assertThat(LlmAgentLoop.isToolErrorData(result.data()))
                .as("重试超 3 次必须抛原 -32042 错误（CC :2872-2874 throw error）")
                .isTrue();
            assertThat(String.valueOf(result.data())).contains("-32042");
            assertThat(statuses(progress))
                .as("最终必须 failed 进度（CC :1925-1936）")
                .last()
                .isEqualTo("failed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("abort 传播：ctx.abortController().abort() → in-flight future.cancel → failed + 错误结果（不悬挂）")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void abort_cancelsInFlightFuture_returnsError() throws Exception {
        AbortController abortController = new AbortController();
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of(), "", abortController);
        AgentMcpTool t = new AgentMcpTool("fs", "read", "mcp__fs__read",
            MAPPER.createObjectNode(), null, null, "Read",
            new HangingChannel(), 60_000, null);
        List<Tool.ToolProgress> progress = new ArrayList<>();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentToolResult<?>> fut = executor.submit(
                () -> t.execute(call("tu-3"), ctx, progress::add));
            Thread.sleep(200);          // 让 in-flight future 挂起
            abortController.abort();    // 用户取消 → onCancel → future.cancel(true)

            AgentToolResult<?> raw = fut.get(10, TimeUnit.SECONDS);
            assertThat(raw).isInstanceOf(ToolResult.class);
            ToolResult<?> result = (ToolResult<?>) raw;
            assertThat(LlmAgentLoop.isToolErrorData(result.data()))
                .as("abort 后必须返错误结果（不悬挂）")
                .isTrue();
            assertThat(statuses(progress))
                .as("abort 传播后必须 failed 进度")
                .containsExactly("started", "failed");
            // 监听器必须清理（onCancel/removeOnCancel 配对，防泄漏）
            assertThat(abortController.listenerCount()).as("abort 监听器必须已移除").isZero();
        } finally {
            executor.shutdownNow();
        }
    }
}
