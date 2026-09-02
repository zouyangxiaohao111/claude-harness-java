package com.nexusai.application.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BrowserWsChannel 真实 WebSocket 通信桥验证 · nexusai-in-chrome 扩展 → WS → Java 后端。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>全局单连接 + sessionId 透传</b>：一个扩展连接服务所有会话（对齐 CCB tabs_context_mcp
 *       「每个对话创建自己的新 tab」）——tool_call 消息带 sessionId 供扩展定位/创建该会话的 tab 组；
 *       透传丢失 → 扩展无法区分会话，多会话并行浏览器自动化断裂。</li>
 *   <li><b>结果按 callId 匹配（与 sessionId 无关）</b>：多会话并行时各 send 线程阻塞等待各自的
 *       callId，扩展回传 tool_result 按 id 路由到发起调用的请求线程；按 sessionId 路由结果 → 并发
 *       会话互相拿错结果。</li>
 *   <li><b>无连接 fail loud</b>：扩展未连接时调用浏览器工具必须明确报错（「浏览器扩展未连接」），
 *       不得静默假成功 —— 模型需要知道先连扩展。</li>
 *   <li><b>超时 fail loud</b>：扩展不响应时不能无限阻塞 agent 工作线程 —— 30s 超时返回错误，
 *       让调用方（工具执行器）能继续/重试。</li>
 *   <li><b>tool_error 透传</b>：扩展侧执行失败（如元素不存在）必须把错误文案带回模型，
 *       模型据此调整策略，而非看到假成功。</li>
 *   <li><b>hello 注册全局连接 + 断开清理</b>：hello 不再要求 sessionId（全局连接）；断开不清理 →
 *       死连接挂着，send 发往无效连接。</li>
 * </ul>
 */
@DisplayName("BrowserWsChannel WebSocket 通信桥（全局连接 · 多会话并行）")
class BrowserWsChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clearMdc() {
        com.nexusai.common.RequestContext.clear();
    }

    @Test
    @DisplayName("send：转发 tool_call（type/id/sessionId/tool/args 正确）并返回 tool_result.result 文本")
    void sendForwardsToolCallAndReturnsResult() throws Exception {
        BrowserWsChannel channel = new BrowserWsChannel();
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        // 模拟扩展：收到 tool_call 后同步回 tool_result（result 为字符串）
        doAnswer(inv -> {
            TextMessage tm = inv.getArgument(0);
            JsonNode sent = JSON.readTree(tm.getPayload());
            ObjectNode resp = JSON.createObjectNode();
            resp.put("type", "tool_result");
            resp.put("id", sent.path("id").asText());
            resp.put("result", "标题: 示例页面");
            channel.resolve(sent.path("id").asText(), resp);
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));

        channel.register(ws);
        String result = channel.send("sess-a", "read_page", Map.of("tabId", 1));

        assertThat(result)
            .as("tool_result.result 为字符串时原样返回（模型看到的工具输出）")
            .isEqualTo("标题: 示例页面");

        // 断言转发的 tool_call 协议形状（对齐类 Javadoc 消息协议：全局连接 + sessionId 透传）
        org.mockito.ArgumentCaptor<TextMessage> captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(captor.capture());
        JsonNode sent = JSON.readTree(captor.getValue().getPayload());
        assertThat(sent.path("type").asText()).isEqualTo("tool_call");
        assertThat(sent.path("id").asText())
            .as("callId 必须为 UUID（唯一关联 tool_result）")
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(sent.path("sessionId").asText())
            .as("tool_call 必须携带 sessionId（扩展按它定位/创建该会话的 tab 组）")
            .isEqualTo("sess-a");
        assertThat(sent.path("tool").asText()).isEqualTo("read_page");
        assertThat(sent.path("args").path("tabId").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("send：result 为 JSON 对象时返回紧凑 JSON（非字符串结果）")
    void returnsCompactJsonWhenResultIsObject() throws Exception {
        BrowserWsChannel channel = new BrowserWsChannel();
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        doAnswer(inv -> {
            TextMessage tm = inv.getArgument(0);
            JsonNode sent = JSON.readTree(tm.getPayload());
            ObjectNode resp = JSON.createObjectNode();
            resp.put("type", "tool_result");
            resp.put("id", sent.path("id").asText());
            resp.set("result", JSON.createObjectNode().put("tabId", 1).put("title", "示例"));
            channel.resolve(sent.path("id").asText(), resp);
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));

        channel.register(ws);
        String result = channel.send("sess-b", "tabs_context_mcp", Map.of());

        assertThat(result).as("对象结果序列化为紧凑 JSON（模型可解析的结构化输出）").isEqualTo("{\"tabId\":1,\"title\":\"示例\"}");
    }

    @Test
    @DisplayName("多会话并行：同一全局连接，两个会话各发 tool_call → 各自 callId 结果正确回传")
    void multiSessionParallelOnGlobalConnection() throws Exception {
        BrowserWsChannel channel = new BrowserWsChannel();
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        // 模拟扩展：并发收到两个 tool_call（不同 sessionId），按各自 callId 异步回 tool_result
        doAnswer(inv -> {
            TextMessage tm = inv.getArgument(0);
            JsonNode sent = JSON.readTree(tm.getPayload());
            CompletableFuture.runAsync(() -> {
                ObjectNode resp = JSON.createObjectNode();
                resp.put("type", "tool_result");
                resp.put("id", sent.path("id").asText());
                resp.put("result", "会话 " + sent.path("sessionId").asText() + " 结果");
                channel.resolve(sent.path("id").asText(), resp);
            });
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));

        channel.register(ws);
        // 两个会话并行调用（各自请求线程）
        CompletableFuture<String> fA = CompletableFuture.supplyAsync(() -> {
            try {
                return channel.send("sess-A", "get_page_text", Map.of("tabId", 1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture<String> fB = CompletableFuture.supplyAsync(() -> {
            try {
                return channel.send("sess-B", "get_page_text", Map.of("tabId", 2));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(fA.join())
            .as("sess-A 的 tool_call 必须路由到 sess-A 的 tool_result（callId 匹配，与 sessionId 无关）")
            .isEqualTo("会话 sess-A 结果");
        assertThat(fB.join())
            .as("sess-B 的 tool_call 必须路由到 sess-B 的 tool_result（callId 匹配，与 sessionId 无关）")
            .isEqualTo("会话 sess-B 结果");
        assertThat(channel.connectedCount()).as("全局连接只有一个（一个扩展服务所有会话）").isEqualTo(1);
    }

    @Test
    @DisplayName("无扩展连接 → fail loud 抛「浏览器扩展未连接」")
    void failsLoudWhenExtensionNotConnected() {
        BrowserWsChannel channel = new BrowserWsChannel();

        assertThatThrownBy(() -> channel.send("sess-none", "read_page", Map.of("tabId", 1)))
            .as("无扩展连接时必须 fail loud（对齐 BrowserMcpTool.EXTENSION_NOT_CONNECTED_MESSAGE）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("浏览器扩展未连接");
    }

    @Test
    @DisplayName("扩展已断开（isOpen=false）→ fail loud「浏览器扩展未连接」")
    void failsLoudWhenSessionClosed() {
        BrowserWsChannel channel = new BrowserWsChannel();
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(false);
        channel.register(ws);

        assertThatThrownBy(() -> channel.send("sess-a", "read_page", Map.of("tabId", 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("浏览器扩展未连接");
    }

    @Test
    @DisplayName("扩展不响应 → 超时 fail loud（短超时 100ms）")
    void timesOutWhenExtensionNoResponse() {
        BrowserWsChannel channel = new BrowserWsChannel(100);
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        // 不 resolve —— 模拟扩展无响应
        when(ws.getId()).thenReturn("ws-1");
        channel.register(ws);

        assertThatThrownBy(() -> channel.send("sess-a", "read_page", Map.of("tabId", 1)))
            .as("扩展超时未应答必须 fail loud（不无限阻塞 agent 工作线程）")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("超时");

        assertThat(channel.pendingCount())
            .as("超时后挂起的 future 必须清理（防止泄漏 + 迟到响应幂等忽略）")
            .isZero();
    }

    @Test
    @DisplayName("扩展返回 tool_error → 抛异常携带错误文案（模型可见）")
    void throwsWhenExtensionReturnsToolError() throws Exception {
        BrowserWsChannel channel = new BrowserWsChannel();
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        doAnswer(inv -> {
            TextMessage tm = inv.getArgument(0);
            JsonNode sent = JSON.readTree(tm.getPayload());
            ObjectNode resp = JSON.createObjectNode();
            resp.put("type", "tool_error");
            resp.put("id", sent.path("id").asText());
            resp.put("error", "元素 ref_1 不存在");
            channel.resolve(sent.path("id").asText(), resp);
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));

        channel.register(ws);

        assertThatThrownBy(() -> channel.send("sess-a", "form_input", Map.of("ref", "ref_1", "value", "x", "tabId", 1)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("元素 ref_1 不存在");
    }

    @Test
    @DisplayName("hello 注册全局连接（无需 sessionId）→ send 可用；断开清理 → send fail loud")
    void helloBindsGlobalConnectionAndDisconnectCleansUp() throws Exception {
        BrowserWsChannel channel = new BrowserWsChannel();
        BrowserWebSocketHandler handler = new BrowserWebSocketHandler(channel);
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        when(ws.getId()).thenReturn("ws-1");
        when(ws.getAttributes()).thenReturn(new java.util.HashMap<>());

        // 扩展握手：hello 不再要求 sessionId（全局连接）
        handler.handleTextMessage(ws, new TextMessage("{\"type\":\"hello\"}"));
        assertThat(channel.connectedCount()).as("hello 后扩展必须注册为全局连接").isEqualTo(1);

        // 注册后 send 可转发（mock 同步回 tool_result）
        doAnswer(inv -> {
            TextMessage tm = inv.getArgument(0);
            JsonNode sent = JSON.readTree(tm.getPayload());
            ObjectNode resp = JSON.createObjectNode();
            resp.put("type", "tool_result");
            resp.put("id", sent.path("id").asText());
            resp.put("result", "ok");
            channel.resolve(sent.path("id").asText(), resp);
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));
        assertThat(channel.send("sess-a", "read_page", Map.of("tabId", 1))).isEqualTo("ok");

        // 断开清理：连接关闭 → 全局引用清空 → send fail loud
        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
        assertThat(channel.connectedCount()).as("连接关闭后全局引用必须清理").isZero();
        assertThatThrownBy(() -> channel.send("sess-a", "read_page", Map.of("tabId", 1)))
            .hasMessageContaining("浏览器扩展未连接");
    }

    @Test
    @DisplayName("handler：tool_result 按 id 路由完成挂起 future（经 handler 全链路）")
    void handlerRoutesToolResultToPending() throws Exception {
        BrowserWsChannel channel = new BrowserWsChannel();
        BrowserWebSocketHandler handler = new BrowserWebSocketHandler(channel);
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        when(ws.getId()).thenReturn("ws-1");

        handler.handleTextMessage(ws, new TextMessage("{\"type\":\"hello\"}"));

        // 模拟扩展收到 tool_call 后，把 tool_result 经 handler 回传（真实链路）
        doAnswer(inv -> {
            TextMessage tm = inv.getArgument(0);
            JsonNode sent = JSON.readTree(tm.getPayload());
            handler.handleTextMessage(ws, new TextMessage(
                "{\"type\":\"tool_result\",\"id\":\"" + sent.path("id").asText() + "\",\"result\":{\"text\":\"页面文本\"}}"));
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));

        String result = channel.send("sess-a", "get_page_text", Map.of("tabId", 1));
        assertThat(result).as("经 handler 路由的 tool_result 必须回到 send 调用方").contains("页面文本");
    }

    @Test
    @DisplayName("handler：hello 无 sessionId → 接受并注册全局连接（不再关闭 1008）")
    void handlerAcceptsHelloWithoutSessionId() throws Exception {
        BrowserWsChannel channel = new BrowserWsChannel();
        BrowserWebSocketHandler handler = new BrowserWebSocketHandler(channel);
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("ws-global");

        handler.handleTextMessage(ws, new TextMessage("{\"type\":\"hello\"}"));

        assertThat(channel.connectedCount())
            .as("hello 无需 sessionId（全局连接，扩展 popup 一次连接服务所有会话）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("新连接替换旧连接（旧连接关闭 4000，全局只保留 1 个连接）")
    void newConnectionReplacesOld() throws Exception {
        BrowserWsChannel channel = new BrowserWsChannel();
        WebSocketSession oldWs = mock(WebSocketSession.class);
        when(oldWs.isOpen()).thenReturn(true);
        when(oldWs.getId()).thenReturn("ws-old");
        WebSocketSession newWs = mock(WebSocketSession.class);
        when(newWs.isOpen()).thenReturn(true);
        when(newWs.getId()).thenReturn("ws-new");

        channel.register(oldWs);
        channel.register(newWs);

        verify(oldWs).close(new CloseStatus(BrowserWsChannel.CLOSE_CODE_REPLACED, "replaced by newer connection"));
        assertThat(channel.connectedCount()).as("全局连接只保留 1 个（新连接覆盖旧连接）").isEqualTo(1);
        // 旧连接断开（afterConnectionClosed）不得误删新连接（身份感知 compareAndSet）
        channel.unregisterByWsSession(oldWs);
        assertThat(channel.connectedCount()).as("旧连接注销不得误删新连接").isEqualTo(1);
    }

    @Test
    @DisplayName("send：sessionId 为空 → fail loud（扩展无法定位 tab 组的调用点明确报错）")
    void failsLoudWhenNoSessionContext() {
        BrowserWsChannel channel = new BrowserWsChannel();

        assertThatThrownBy(() -> channel.send(null, "read_page", Map.of("tabId", 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("无法确定当前会话");
        assertThatThrownBy(() -> channel.send("   ", "read_page", Map.of("tabId", 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("无法确定当前会话");
    }
}
