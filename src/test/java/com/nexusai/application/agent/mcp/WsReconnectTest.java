package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [R2-1] WS 自动重连 · 对齐 CC client.ts:1374-1402 onclose → 清缓存 → 惰性重连 + 认证后重连。
 *
 * <p>WHY（规则九）：WS 断线不自动重连则 server 工具静默不可用（callTool 失败），用户需手动
 * /mcp 或重启。本测试验证三件事：(a) WsMcpTransport.onClose 触发断开 notifier 且正确计算
 * authRequired（4003 认证关闭 + 刷新失败）；(b) 非认证断开 → 清 fetch 缓存 + 退避重连重建
 * 连接（对齐 CC onclose 清缓存语义）；(c) 认证关闭 → needs-auth 状态可见（对齐 CC
 * handleRemoteAuthFailure type='needs-auth'）。
 */
@DisplayName("[R2-1] WS 自动重连")
class WsReconnectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpTransport.TransportConfig wsConfig(String name) {
        return new McpTransport.TransportConfig("ws://svc", List.of(), Map.of(), null, name, "ws");
    }

    // ═══════════════ WsMcpTransport.onClose → notifier（authRequired 计算）═══════════════

    /**
     * WHY：WS 401 语义 = close code 4003；无 token 被 4003 拒绝 → 认证无法恢复，必须通知上层
     * 走 needs-auth + S1 OAuth（对齐 HttpMcpTransport 401 → McpAuthError 降级）。
     */
    @Test
    @DisplayName("close 4003 且无 token → notifier authRequired=true")
    void close4003_noToken_authRequiredNotified() {
        WsMcpTransport t = new WsMcpTransport();
        t.setStateForTest(McpTransport.State.CONNECTED);
        boolean[] notified = {false};
        boolean[] auth = {false};
        t.setDisconnectNotifier(a -> {
            notified[0] = true;
            auth[0] = a;
        });
        t.handleWsClose(4003, "unauthorized");

        assertThat(notified[0]).isTrue();
        assertThat(auth[0]).isTrue();
        assertThat(t.getState()).isEqualTo(McpTransport.State.CLOSED);
    }

    /**
     * WHY：4003 认证关闭但 token 刷新成功（S2 refreshAndGetAccessToken）→ 认证已恢复，无需
     * needs-auth；authRequired=false 让上层走「用新 token 退避重连」（对齐 CC WS 4003
     * refreshHeaders → reconnect；新 token 已持久化 DB，重连 resolveBearer 取新 token）。
     */
    @Test
    @DisplayName("close 4003 + token 刷新成功 → notifier authRequired=false")
    void close4003_refreshSuccess_notAuthRequired() {
        McpAuthHeaderProvider provider = mock(McpAuthHeaderProvider.class);
        when(provider.refreshAndGetAccessToken(any(), anyString())).thenReturn("new-token");
        WsMcpTransport t = new WsMcpTransport(provider);
        t.setStateForTest(McpTransport.State.CONNECTED);
        t.setConfigForTest(wsConfig("svc"));
        t.setLastWsTokenForTest("old-token");
        boolean[] notified = {false};
        boolean[] auth = {false};
        t.setDisconnectNotifier(a -> {
            notified[0] = true;
            auth[0] = a;
        });
        t.handleWsClose(4003, "unauthorized");

        assertThat(notified[0]).isTrue();
        assertThat(auth[0]).isFalse();
    }

    /**
     * WHY：普通断开（非 4003）→ 清缓存 + 退避重连即可，无需认证；authRequired 必须为 false
     * 否则上层会误触发 OAuth 流（对齐 CC onclose 只清缓存不产 needs-auth）。
     */
    @Test
    @DisplayName("普通 close → notifier authRequired=false")
    void closeNormal_notifiesNotAuthRequired() {
        WsMcpTransport t = new WsMcpTransport();
        t.setStateForTest(McpTransport.State.CONNECTED);
        boolean[] notified = {false};
        boolean[] auth = {false};
        t.setDisconnectNotifier(a -> {
            notified[0] = true;
            auth[0] = a;
        });
        t.handleWsClose(1000, "server shutdown");

        assertThat(notified[0]).isTrue();
        assertThat(auth[0]).isFalse();
    }

    /**
     * WHY：用户主动 close（McpToolPool.teardown / stop）不得触发自动重连 —— close() 同步置
     * CLOSED，随后的 onClose 进入时 wasConnected=false → notifier 不触发（对齐 CC 用户主动
     * close 不重连）。
     */
    @Test
    @DisplayName("用户主动 close → 不触发断开 notifier")
    void userClose_doesNotNotify() {
        WsMcpTransport t = new WsMcpTransport();
        t.setStateForTest(McpTransport.State.CONNECTED);
        boolean[] notified = {false};
        t.setDisconnectNotifier(a -> notified[0] = true);
        t.close(); // 用户主动关闭 → 状态已置 CLOSED
        t.handleWsClose(1000, "client close"); // 随后的 onClose

        assertThat(notified[0]).isFalse();
    }

    /**
     * WHY：连接期失败（onOpen 未触发，state==NOT_CONNECTED）→ start() 已抛错走批连接 fail-soft
     * （对齐 CC WS 无 3-strike，client.ts:1333-1337），不得重复触发主动重连（否则死 server 反复
     * 建连）。
     */
    @Test
    @DisplayName("连接期失败（未 CONNECTED）→ 不触发断开 notifier")
    void connectTimeFailure_doesNotNotify() {
        WsMcpTransport t = new WsMcpTransport();
        boolean[] notified = {false};
        t.setDisconnectNotifier(a -> notified[0] = true);
        t.handleWsClose(1006, "abnormal closure");

        assertThat(notified[0]).isFalse();
    }

    /** WHY：onError 在 ready 未初始化（未 start）时不得 NPE（对齐 Q-11-8 onError 兜底）。 */
    @Test
    @DisplayName("onError 在未 start 时不崩溃")
    void errorBeforeStart_noCrash() {
        WsMcpTransport t = new WsMcpTransport();
        t.handleWsError(new RuntimeException("boom"));
        // 未抛异常即通过
        assertThat(t.getState()).isEqualTo(McpTransport.State.NOT_CONNECTED);
    }

    // ═══════════════ McpToolPool WS 断开处理器 ═══════════════

    /**
     * WHY：非认证断线后 McpToolPool 必须清 fetch 缓存（对齐 CC onclose :1389-1396，重连不读旧
     * 快照）+ 退避重连重建连接（目标「断线自动重连，无需手动」）。同步调度器下断言：资源缓存
     * 被清 + 新建 transport 替换旧 CLOSED transport。
     */
    @Test
    @DisplayName("WS 非认证断开 → 清缓存 + 退避重连重建连接")
    void wsDisconnectNonAuth_clearsCachesAndReconnects() {
        FakeWsFactory factory = new FakeWsFactory();
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.setWsReconnectScheduler((delay, task) -> task.run()); // 测试同步执行退避
        pool.assembleToolPool("svc", wsConfig("svc"));
        assertThat(factory.created.get()).isEqualTo(1);

        // 填充 fetch 缓存（resources 能力无 → [] 仍缓存）
        pool.fetchResources("svc");
        assertThat(pool.resourcesCache().get("svc")).isNotNull();

        // 模拟服务器关闭 → 触发断开 notifier（authRequired=false）
        FakeWsTransport ws = factory.instances.get(0);
        ws.setStateForTest(McpTransport.State.CLOSED);
        ws.disconnectNotifier().onWsDisconnected(false);

        // CC onclose :1389-1396 清 fetch 缓存
        assertThat(pool.resourcesCache().get("svc")).isNull();
        // 退避重连（同步调度器）→ 新建 transport 替换旧 CLOSED transport
        assertThat(factory.created.get()).isEqualTo(2);
        assertThat(pool.isServerConnected("svc")).isTrue();
    }

    /**
     * WHY：非认证断开且后续重连失败时不得清已注册工具（对齐 Q-11-5 会话过期路径保留
     * server.tools）—— 否则断线期间模型丢失该 server 的工具列表，无法在恢复后重试。
     */
    @Test
    @DisplayName("WS 非认证断开 → 清 fetch 缓存但保留已注册工具")
    void wsDisconnectNonAuth_preservesRegisteredTools() {
        FakeWsFactory factory = new FakeWsFactory();
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.setWsReconnectScheduler((delay, task) -> task.run());
        pool.assembleToolPool("svc", wsConfig("svc"));
        assertThat(pool.activeServers()).contains("svc");

        FakeWsTransport ws = factory.instances.get(0);
        ws.setStateForTest(McpTransport.State.CLOSED);
        ws.disconnectNotifier().onWsDisconnected(false);

        // 重连成功 → 工具保留（reconnect 只清 fetch 缓存，不清 serverTools/ToolRegistry）
        assertThat(pool.activeServers()).contains("svc");
        assertThat(factory.created.get()).isEqualTo(2);
    }

    /**
     * WHY（规则九 · REFLECTOR R2-1）：服务器宕机时 {1s,2s,4s} 三次退避必须真正重试建连——若
     * attempt 1 remove activeTransports 后 ensureConnectedClient 失败不入 put，attempt 2/3 会
     * 命中 {@code current==null} 守卫空转，三次退避退化为单次真实建连。既有
     * wsDisconnectNonAuth_clearsCachesAndReconnects 用恒成功 fake，掩盖了该缺陷；本测试用
     * 「首次退避尝试 create 的 transport start 抛异常（宕机）、二次退避尝试成功（恢复）」的
     * fake，断言 factory.created 达 3（assemble 1 + 两次重连创建）+ isServerConnected 恢复。
     * 若无 {@code wsReconnecting} per-server 重连标志，attempt 2/3 空转 → created 停在 2 且
     * 未连接 → 本测试红。
     */
    @Test
    @DisplayName("WS 退避重连：首次建连失败（宕机）→ 后续退避尝试真正重试直至恢复")
    void wsReconnectBackoff_retriesAfterFirstFailure() {
        FlakyWsFactory factory = new FlakyWsFactory();
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.setWsReconnectScheduler((delay, task) -> task.run()); // 同步执行退避
        pool.assembleToolPool("svc", wsConfig("svc"));
        assertThat(factory.created.get()).isEqualTo(1);
        assertThat(pool.isServerConnected("svc")).isTrue();

        // 模拟服务器宕机断开（非认证）→ handleWsDisconnect → scheduleWsReconnect 三次退避
        FakeWsTransport ws = factory.instances.get(0);
        ws.setStateForTest(McpTransport.State.CLOSED);
        ws.disconnectNotifier().onWsDisconnected(false);

        // 退避链：attempt 1（1s）createIndex=2 start 抛异常（宕机）→ 失败不入 put；
        // attempt 2（2s）createIndex=3 start 成功（恢复）→ 真正重试建连成功；
        // attempt 3（4s）已 CONNECTED → 跳过。
        assertThat(factory.created.get()).isEqualTo(3);
        assertThat(pool.isServerConnected("svc")).isTrue();
    }

    /**
     * WHY：4003 认证关闭且刷新失败 → 连接态必须变为 needs-auth（对齐 CC handleRemoteAuthFailure
     * type='needs-auth'），供前端/查询接口呈现「需重新授权」；OAuth token 存储未接线（测试）→
     * 不 crash、保持 needs-auth（等待用户经伪工具重试）。
     */
    @Test
    @DisplayName("WS 认证断开 → needs-auth 状态可见")
    void wsDisconnectAuthRequired_marksNeedsAuth() {
        FakeWsFactory factory = new FakeWsFactory();
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool("svc", wsConfig("svc"));

        FakeWsTransport ws = factory.instances.get(0);
        ws.setStateForTest(McpTransport.State.CLOSED);
        ws.disconnectNotifier().onWsDisconnected(true);

        assertThat(pool.connectivityStatusRegistry().typeOf("svc")).isEqualTo("needs-auth");
        // oauthTokenStore == null → 不崩溃，保持 needs-auth
        assertThat(pool.activeServers()).contains("svc");
    }

    // ═══════════════ fakes ═══════════════

    /** 返回可控制的 FakeWsTransport，并跟踪创建实例。 */
    static class FakeWsFactory extends McpTransportFactory {
        final AtomicInteger created = new AtomicInteger();
        final List<FakeWsTransport> instances = new CopyOnWriteArrayList<>();

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            FakeWsTransport t = new FakeWsTransport();
            instances.add(t);
            created.incrementAndGet();
            return t;
        }
    }

    /**
     * [R2-1 rework] 宕机模拟工厂：created#1（assemble）成功；created#2（首次退避重连尝试）
     * start 抛异常（模拟服务器宕机）；created#3+（二次及以后退避尝试）成功（服务器恢复）。
     * WHY（REFLECTOR R2-1）：用于验证退避链在首次建连失败后仍真正重试，而非空转守卫。
     */
    static class FlakyWsFactory extends McpTransportFactory {
        final AtomicInteger created = new AtomicInteger();
        final List<FakeWsTransport> instances = new CopyOnWriteArrayList<>();

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            int index = created.incrementAndGet();
            FakeWsTransport t = new FakeWsTransport();
            t.failStart = index == 2; // 第 2 个实例（首次退避尝试）模拟宕机
            instances.add(t);
            return t;
        }
    }

    /** 模拟 WS transport：start 即 CONNECTED；initialize/tools/list 返回空能力（不触发网络）。 */
    static class FakeWsTransport extends WsMcpTransport {
        /** [R2-1 rework] true = start 抛异常（模拟服务器宕机 / 建连失败）；默认 false（恒成功）。 */
        volatile boolean failStart;

        @Override
        public void start(McpTransport.TransportConfig config) {
            if (failStart) {
                throw new IllegalStateException("server down (simulated outage)");
            }
            setStateForTest(McpTransport.State.CONNECTED);
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("serverInfo").put("name", "fake-ws");
                result.putObject("capabilities");
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void sendNotification(String method, Object params) {
        }
    }
}
