package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * [IMP-E2 rework F3] connectTransport initialize 超时 → 半初始化 transport 不泄漏 → 下次重连成功。
 *
 * <p><b>WHY (规则九)</b>: CC 连接超时 race（client.ts:1048-1077）超时分支显式
 * {@code transport.close()}（client.ts:1056-1059）；Java {@code connectTransport} 原实现
 * {@code activeTransports.put} + {@code transport.start} 置 CONNECTED 后对 initialize
 * {@code orTimeout}，超时触发 → join 抛但<b>未 close / 未 activeTransports.remove</b> → 半初始化
 * transport 泄漏至 activeTransports。下次 {@code ensureConnectedClient} 命中
 * {@code existing != null && key.equals(existingKey) && state != CLOSED} → <b>复用半初始化
 * transport</b>，未完成 initialize 即打 tools/call 再失败（F3 缺陷）。本测试锁定：initialize 超时
 * → 清理（close + remove）→ 下次调用全新建连成功。若缺失清理，第二次调用复用半初始化 T2
 * （tools/call 抛「transport not initialized」）→ 本测试红。
 */
@DisplayName("[IMP-E2 rework F3] initialize 超时 → 半初始化 transport 清理 → 重连成功")
class McpToolPoolConnectTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER = "svc";

    private static McpTransport.TransportConfig httpConfig(String name) {
        return new McpTransport.TransportConfig("http://svc:3000", List.of(), Map.of(), null, name, "http");
    }

    /**
     * 场景还原（复用前置）：① 首次装配成功（serverConfigKeys 存 key + activeTransports 有 T1）；
     * ② 会话过期 → {@code activeTransports.remove}（保留 serverConfigKeys key，对齐 CC
     * clearServerCache）→ 重试建连 T2，其 initialize 超时失败；③ 工厂恢复 → 再次调用。
     * <ul>
     *   <li><b>有 F3 清理</b>：T2 被 close + remove → ③ 全新建连 T3 → tools/call 成功（created=3）</li>
     *   <li><b>无 F3 清理</b>：T2 滞留 activeTransports（state=CONNECTED）+ key 仍匹配 → ③ 复用 T2
     *       → tools/call 抛「transport not initialized」→ 测试红</li>
     * </ul>
     */
    @Test
    @DisplayName("initialize 超时 → 半初始化 transport 清理 → 下次重连成功")
    void initializeTimeout_cleansHalfInitTransport_thenReconnectSucceeds() {
        ConnectTimeoutFactory factory = new ConnectTimeoutFactory(/*initTimeoutAtIndex=*/1,
            /*sessionExpiredToolsCall=*/true);
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());

        // ① 首次装配成功：T1 创建，key 入库，工具已注册（复用前置）
        pool.assembleToolPool(SERVER, httpConfig(SERVER));
        assertThat(factory.created.get()).isEqualTo(1);

        // ② 会话过期 → 清连接 memo（保留 key）→ 重试建连 T2（initialize 超时失败）
        Throwable first = catchThrowable(() -> pool.callTool(SERVER, "my_tool", Map.of("x", 1)).join());
        assertThat(first).as("initialize 超时应使首重连调用失败").isNotNull();
        assertThat(factory.created.get()).isEqualTo(2);
        assertThat(factory.instances.get(1).closed.get())
            .as("[F3] 半初始化 T2 必须被 close（对齐 CC 超时分支 transport.close()）").isTrue();

        // ③ 工厂恢复 → 再次调用：必须全新建连（T3），不得复用半初始化 T2
        JsonNode result = pool.callTool(SERVER, "my_tool", Map.of("x", 1)).join();
        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("ok-after-reconnect");
        assertThat(factory.created.get())
            .as("[F3] 重连应全新建 transport（T1 装配 + T2 超时失败 + T3 重连成功）").isEqualTo(3);
    }

    /** 控制 transport 序列行为的工厂：第 idx 个 initialize 超时；tools/call 首调会话过期。 */
    static class ConnectTimeoutFactory extends McpTransportFactory {
        final AtomicInteger created = new AtomicInteger();
        final List<FakeTransport> instances = new CopyOnWriteArrayList<>();
        /** 第几个 transport 的 initialize 超时（模拟连接超时，CC 30s race）；其余正常。 */
        final int initTimeoutAtIndex;
        /** true → 所有 transport 的 tools/call 均先抛一次会话过期（触发 activeTransports.remove 保留 key）。 */
        final boolean sessionExpiredToolsCall;

        ConnectTimeoutFactory(int initTimeoutAtIndex, boolean sessionExpiredToolsCall) {
            this.initTimeoutAtIndex = initTimeoutAtIndex;
            this.sessionExpiredToolsCall = sessionExpiredToolsCall;
        }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            int idx = created.getAndIncrement();
            // 会话过期仅首个 transport（装配后首个调用触发 activeTransports.remove 保留 key）；
            // 后续 transport 恢复正常 tools/call（重连成功的 T3 不得再抛会话过期）。
            FakeTransport t = new FakeTransport(idx == initTimeoutAtIndex,
                sessionExpiredToolsCall && idx == 0);
            instances.add(t);
            return t;
        }
    }

    /** 模拟 HTTP transport：start 即 CONNECTED；initialize 可超时失败；tools/call 可会话过期。 */
    static class FakeTransport implements McpTransport {
        private final AtomicReference<State> state = new AtomicReference<>(State.NOT_CONNECTED);
        private final AtomicBoolean closed = new AtomicBoolean();
        /** true → initialize 超时失败（模拟 30s 连接超时）且 tools/call 抛「not initialized」。 */
        final boolean initTimeout;
        /** true → tools/call 首调会话过期。 */
        final boolean sessionExpiredToolsCall;

        FakeTransport(boolean initTimeout, boolean sessionExpiredToolsCall) {
            this.initTimeout = initTimeout;
            this.sessionExpiredToolsCall = sessionExpiredToolsCall;
        }

        @Override
        public void start(TransportConfig config) {
            state.set(State.CONNECTED);
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            if ("initialize".equals(method)) {
                if (initTimeout) {
                    // 与 orTimeout 触发同构：join() 抛 CompletionException(TimeoutException)
                    return CompletableFuture.failedFuture(
                        new TimeoutException("MCP server \"svc\" connection timed out (test)"));
                }
                ObjectNode r = MAPPER.createObjectNode();
                r.putObject("serverInfo").put("name", "fake-http");
                r.putObject("capabilities");
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/list".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                r.putArray("tools");
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/call".equals(method)) {
                if (initTimeout) {
                    // 半初始化 transport 未完成 initialize：复用路径（无 F3 清理）应在此失败
                    return CompletableFuture.failedFuture(
                        new IllegalStateException("transport not initialized"));
                }
                if (sessionExpiredToolsCall) {
                    return CompletableFuture.failedFuture(
                        new McpSessionExpiredException(SERVER, "MCP server \"svc\" session expired (test)"));
                }
                ObjectNode r = MAPPER.createObjectNode();
                r.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", "ok-after-reconnect");
                return CompletableFuture.completedFuture(r);
            }
            return CompletableFuture.failedFuture(new IllegalStateException("unexpected method " + method));
        }

        @Override
        public void sendNotification(String method, Object params) {
        }

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {
        }

        @Override
        public void close() {
            closed.set(true);
            state.set(State.CLOSED);
        }

        @Override
        public State getState() {
            return state.get();
        }
    }
}
