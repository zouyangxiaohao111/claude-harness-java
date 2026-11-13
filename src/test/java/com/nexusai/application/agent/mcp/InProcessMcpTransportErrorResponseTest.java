package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [必达] 进程内 MCP 传输：请求必须拿到<b>终态</b>，不允许静默丢包。
 *
 * <p><b>缺陷现场（2026-11-13 实测）</b>：{@code deliverRequest} 的 {@code catch (Exception)}
 * 包不住 {@code Error}，且 {@code peer.requestHandler == null} 分支只 warn 后 return
 * ⇒ 发送侧 {@code pending} future 永远不完成（上层 {@code McpServerTool} 卡在
 * {@code fn.call().join()}，实测把整轮 turn 卡死 5min+）。
 *
 * <p><b>反向实验</b>：把 {@code catch (Throwable)} 改回 {@code catch (Exception)}，或删掉
 * no-handler 分支的 {@code completePendingExceptionally} ⇒ 两个用例都在 {@code get(2s)} 超时 ⇒ 变红。
 */
@DisplayName("[必达] InProcessMcpTransport：handler 抛 Error / 无 handler 都必须让调用方拿到终态")
class InProcessMcpTransportErrorResponseTest {

    @Test
    @DisplayName("handler 抛 Error（StackOverflowError）→ 调用方 future 立即异常完成，而不是永久挂起")
    void handlerThrowsError_failsCallerFuture() {
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        InProcessMcpTransport client = pair[0];
        InProcessMcpTransport server = pair[1];
        client.start(null);
        server.start(null);
        server.setRequestHandler((method, params) -> {
            throw new StackOverflowError("probe-error");
        });

        CompletableFuture<JsonNode> future = client.sendRequest("tools/call", Map.of("name", "probe"));

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .as("★ Error 必须被兜底成 error 响应；修复前此处 TimeoutException（27.8h 默认超时）")
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("peer 无 requestHandler → 调用方 future 立即异常完成（原实现只 warn 后静默丢弃）")
    void noRequestHandler_failsCallerFuture() {
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        InProcessMcpTransport client = pair[0];
        InProcessMcpTransport server = pair[1];
        client.start(null);
        server.start(null);
        // 故意不注册 requestHandler

        CompletableFuture<JsonNode> future = client.sendRequest("tools/call", Map.of("name", "probe"));

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .as("★ 无处理器也必须回终态；修复前此处永久 pending")
                .isInstanceOf(ExecutionException.class);
    }
}
