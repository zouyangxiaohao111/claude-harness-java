package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.mcp.McpSessionExpiredException;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S05 B7/B8 + R2-06 X-1] AgentMcpTool 统一执行引擎：进度三态 + MAX_SESSION_RETRIES=1
 * 会话重试 + 请求侧 meta（claudecode/toolUseId）· 对齐 CC client.ts:1840-1936/:3096。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: 旧执行面无进度（B7 缺）、无会话重试
 * （B8 缺，session-expired 直接失败）、无请求侧 meta（R2-06 X-1 缺）。本测试锁：
 * <ol>
 *   <li>进度三态载荷：toolUseID / data.type=mcp_progress / status=started|completed|failed /
 *       serverName / toolName / completed+failed 带 elapsedTimeMs</li>
 *   <li>会话过期重试恰 1 次：首调 McpSessionExpiredException → resetSession → 重试成功；
 *       重试仍失败 → 抛原错误 + failed 进度（不吞、不无限重试）</li>
 *   <li>请求侧 meta：channel 收到的 meta 含 {@code claudecode/toolUseId}=call.id
 *       （CC :1840-1843 → :3096 _meta）</li>
 * </ol>
 */
@DisplayName("[S05 B7/B8/X-1] AgentMcpTool 进度三态 + 会话重试 + 请求侧 meta")
class AgentMcpToolProgressSessionRetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 记录 call/resetSession 的 fake channel · 响应可编程。 */
    static class RecordingChannel implements AgentMcpServers.McpToolChannel {
        final AtomicInteger callCount = new AtomicInteger();
        final AtomicInteger resetCount = new AtomicInteger();
        final List<Map<String, Object>> receivedMetas = new ArrayList<>();
        /** 每次 call 的响应 future 供给器（null 队列耗尽时用默认）。 */
        final java.util.Queue<CompletableFuture<JsonNode>> responses = new java.util.ArrayDeque<>();

        @Override
        public CompletableFuture<JsonNode> call(Map<String, Object> args, Map<String, Object> meta) {
            callCount.incrementAndGet();
            receivedMetas.add(meta);
            CompletableFuture<JsonNode> r = responses.poll();
            if (r == null) {
                r = okResult("ok");
            }
            return r;
        }

        @Override
        public void resetSession() {
            resetCount.incrementAndGet();
        }
    }

    static CompletableFuture<JsonNode> okResult(String text) {
        ObjectNode r = MAPPER.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", false);
        return CompletableFuture.completedFuture(r);
    }

    static CompletableFuture<JsonNode> sessionExpired() {
        return CompletableFuture.failedFuture(
            new McpSessionExpiredException("fs", "MCP server \"fs\" session expired"));
    }

    private static ToolUseBlock call(String id) {
        return new ToolUseBlock(id, "mcp__fs__read", MAPPER.createObjectNode());
    }

    private static List<Tool.ToolProgress> collect() {
        return new ArrayList<>();
    }

    private static AgentMcpTool tool(RecordingChannel channel) {
        return new AgentMcpTool("fs", "read", "mcp__fs__read",
            MAPPER.createObjectNode(), null, null, "Read",
            channel, 60_000, null);
    }

    @Test
    @DisplayName("首调会话过期 → resetSession 重试恰 1 次 → completed 进度 + 结果（B8）")
    void sessionExpired_retriesOnce_thenCompletes() {
        RecordingChannel channel = new RecordingChannel();
        channel.responses.add(sessionExpired());   // 首调失败
        // 次调走默认 okResult
        AgentMcpTool t = tool(channel);
        List<Tool.ToolProgress> progress = collect();

        AgentToolResult<?> raw = t.execute(call("tu-1"), null, progress::add);

        // 重试恰 1 次（MAX_SESSION_RETRIES=1，CC client.ts:1859/1913-1922）
        assertThat(channel.resetCount.get()).as("会话过期必须 resetSession 恰 1 次").isEqualTo(1);
        assertThat(channel.callCount.get()).as("重试后总调用 2 次").isEqualTo(2);
        assertThat(raw).isInstanceOf(ToolResult.class);
        ToolResult<?> result = (ToolResult<?>) raw;
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(String.valueOf(result.data())).contains("ok");

        // 进度三态载荷（B7）
        assertThat(progress.stream().map(p -> String.valueOf(((Map<?, ?>) p.data()).get("status"))).toList())
            .as("进度序列必须为 started → completed（无 failed）")
            .containsExactly("started", "completed");
        Tool.ToolProgress started = progress.get(0);
        assertThat(started.toolUseId()).isEqualTo("tu-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> startedData = (Map<String, Object>) started.data();
        assertThat(startedData.get("type")).isEqualTo("mcp_progress");
        assertThat(startedData.get("serverName")).isEqualTo("fs");
        assertThat(startedData.get("toolName")).isEqualTo("read");
        assertThat(startedData).doesNotContainKey("elapsedTimeMs");
        @SuppressWarnings("unchecked")
        Map<String, Object> completedData = (Map<String, Object>) progress.get(1).data();
        assertThat(completedData.get("status")).isEqualTo("completed");
        assertThat(completedData.get("elapsedTimeMs")).as("completed 必须带 elapsedTimeMs").isNotNull();

        // 请求侧 meta（R2-06 X-1 · CC :1840-1843 → :3096）
        assertThat(channel.receivedMetas).hasSize(2);
        assertThat(channel.receivedMetas.get(0))
            .as("tools/call 请求侧 meta 必须含 claudecode/toolUseId")
            .containsEntry("claudecode/toolUseId", "tu-1");
        assertThat(channel.receivedMetas.get(1)).containsEntry("claudecode/toolUseId", "tu-1");
    }

    @Test
    @DisplayName("重试仍会话过期 → 抛原错误 + failed 进度（不吞、不无限重试）")
    void sessionExpired_retryStillFails_emitsFailed() {
        RecordingChannel channel = new RecordingChannel();
        channel.responses.add(sessionExpired());
        channel.responses.add(sessionExpired());   // 重试也失败
        AgentMcpTool t = tool(channel);
        List<Tool.ToolProgress> progress = collect();

        AgentToolResult<?> raw = t.execute(call("tu-2"), null, progress::add);

        assertThat(channel.resetCount.get()).as("重试恰 1 次后放弃").isEqualTo(1);
        assertThat(channel.callCount.get()).isEqualTo(2);
        assertThat(raw).isInstanceOf(ToolResult.class);
        ToolResult<?> result = (ToolResult<?>) raw;
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("重试仍失败必须返错误结果").isTrue();
        assertThat(progress.stream().map(p -> String.valueOf(((Map<?, ?>) p.data()).get("status"))).toList())
            .as("进度序列必须为 started → failed")
            .containsExactly("started", "failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> failedData = (Map<String, Object>) progress.get(1).data();
        assertThat(failedData.get("elapsedTimeMs")).as("failed 必须带 elapsedTimeMs").isNotNull();
    }

    @Test
    @DisplayName("正常调用（无会话过期）→ started/completed 各 1 次，无 resetSession")
    void normalCall_emitsStartedAndCompleted_noReset() {
        RecordingChannel channel = new RecordingChannel();
        AgentMcpTool t = tool(channel);
        List<Tool.ToolProgress> progress = collect();

        AgentToolResult<?> raw = t.execute(call("tu-3"), null, progress::add);

        assertThat(LlmAgentLoop.isToolErrorData(raw.data())).isFalse();
        assertThat(channel.resetCount.get()).isZero();
        assertThat(progress.stream().map(p -> String.valueOf(((Map<?, ?>) p.data()).get("status"))).toList())
            .containsExactly("started", "completed");
    }
}
