package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.common.RequestContext;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S07 · 验收 1/4] 会话态 --channels 注入测试（RED→GREEN）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: CC 的 session 白名单是真实会话态（state.ts:1676-1682
 * {@code STATE.allowedChannels} + main.tsx:1692-1696 {@code parseChannelEntries → setAllowedChannels}），
 * 而 Java 旧接线恒空（{@code setAllowedChannelsSupplier(List::of)}）→ gate 门序[3 session]
 * 恒 skip → 入站 channel 功能生产不可达。本测试验证 ChannelSessionAllowlist（sessionId 键控
 * 注册表 + MDC 当前请求解析）注入后：写白名单 → 放行 → 入队端到端可达；无白名单 → fail-closed
 * 安全默认不倒退（S07.md §5 验收 1/4）。
 *
 * <p>server-kind entry 经 allowlist 门需要 dev=true（CC channelNotification.ts:302-313：
 * allowlist schema 仅 plugin，server entry 恒不匹配除非 dev 豁免）——本测试按 CC 语义构造
 * {@code new ChannelEntry("server", "my-server", null, true)}，dev 位是 CC 既有的 per-entry
 * 信任声明（main.tsx 侧 --dangerously-load-development-channels 产物），非本 Session 新增能力。
 */
class ChannelSessionAllowlistTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clearMdc() {
        RequestContext.clear();
    }

    /** 门序全过（capability + channelsEnabled + session + server-kind dev 豁免）→ register。 */
    private static ChannelNotificationGate gateFor(List<ChannelAllowlist.ChannelEntry> sessionEntries) {
        ChannelNotificationGate gate = new ChannelNotificationGate(
            () -> true, List::of,
            () -> List.of(new ChannelAllowlistEntry("anthropic", "slack")),
            ChannelNotificationGate::escapeXmlAttr);
        gate.setAllowedChannelsSupplier(() -> sessionEntries);
        return gate;
    }

    @Test
    @DisplayName("① 真实会话白名单（含 server）+ 当前会话 MDC → gate 门序[3 session] 放行 → register（验收 1 放行臂）")
    void sessionAllowlist_injectsAndGateRegisters() {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();
        allowlist.setForSession("sess-1", List.of(
            new ChannelAllowlist.ChannelEntry("server", "my-server", null, true)));

        RequestContext.setSession("sess-1");
        List<ChannelAllowlist.ChannelEntry> current = allowlist.currentRequestSupplier().get();
        assertThat(current)
            .as("currentRequestSupplier 必须解析当前 MDC 会话的白名单（非恒空）")
            .hasSize(1)
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactly("my-server");

        ChannelNotificationGate gate = gateFor(current);
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "my-server", new ChannelNotificationGate.ServerCapabilities(Map.of("claude/channel", Map.of())), null);
        assertThat(r.action())
            .as("会话白名单命中 + 门序全过 → register（旧恒空接线必 SESSION skip，本断言对旧实现必失败）")
            .isEqualTo("register");
    }

    @Test
    @DisplayName("② 未 setForSession（同会话但无白名单）→ 空表 fail-closed → SESSION skip（验收 4）")
    void noAllowlistForSession_failsClosed() {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();

        RequestContext.setSession("sess-1");
        assertThat(allowlist.currentRequestSupplier().get())
            .as("未写入白名单的会话 → 空表（fail-closed）")
            .isEmpty();

        ChannelNotificationGate gate = gateFor(allowlist.currentRequestSupplier().get());
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "my-server", new ChannelNotificationGate.ServerCapabilities(Map.of("claude/channel", Map.of())), null);
        assertThat(r.action()).isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.SESSION);
    }

    @Test
    @DisplayName("③ 无会话上下文（MDC 空）→ 空表 fail-closed；其它会话白名单不影响当前会话（会话隔离）")
    void noSessionContext_orOtherSession_failsClosed() {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();
        allowlist.setForSession("sess-A", List.of(
            new ChannelAllowlist.ChannelEntry("server", "server-a", null, true)));

        // 无会话上下文 → 空表
        RequestContext.clear();
        assertThat(allowlist.currentRequestSupplier().get()).isEmpty();

        // 会话隔离：session-B 读不到 session-A 的白名单
        RequestContext.setSession("sess-B");
        assertThat(allowlist.currentRequestSupplier().get())
            .as("会话隔离：session-B 白名单不得被 session-A 污染")
            .isEmpty();

        // session-A 自身仍可读（写/读同键一致）
        RequestContext.setSession("sess-A");
        assertThat(allowlist.currentRequestSupplier().get())
            .hasSize(1)
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactly("server-a");
    }

    @Test
    @DisplayName("④ clearSession 后 → 空表 fail-closed（会话销毁清理缝）")
    void clearSession_failsClosedAfter() {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();
        allowlist.setForSession("sess-1", List.of(
            new ChannelAllowlist.ChannelEntry("server", "my-server", null, true)));
        allowlist.clearSession("sess-1");

        RequestContext.setSession("sess-1");
        assertThat(allowlist.currentRequestSupplier().get())
            .as("clearSession 后 → 空表 fail-closed")
            .isEmpty();
    }

    @Test
    @DisplayName("⑤ 端到端：真实 supplier 接线 gate → register → 推送 channel 通知 → 入队（含 origin）→ drainForQuery 消费（验收 1『通知入队』）")
    void endToEnd_sessionAllowlist_registersAndEnqueues() throws Exception {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();
        // plugin-kind entry（gate 门序[4] marketplace + [5] ledger 走真实校验路径）
        allowlist.setForSession("sess-1", List.of(
            new ChannelAllowlist.ChannelEntry("plugin", "slack", "anthropic", false)));

        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        ChannelServer server = new ChannelServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        NotificationQueue queue = new NotificationQueue();
        ChannelNotificationGate gate = new ChannelNotificationGate(
            () -> true, List::of,
            () -> List.of(new ChannelAllowlistEntry("anthropic", "slack")),
            ChannelNotificationGate::escapeXmlAttr);
        // [S07] 真实会话态接线：gate 的 allowedChannelsSupplier = ChannelSessionAllowlist.currentRequestSupplier()
        gate.setAllowedChannelsSupplier(allowlist.currentRequestSupplier());
        pool.setChannelNotification(new ChannelNotification(queue));
        pool.setChannelNotificationGate(gate);
        pool.setPluginSourceResolver(name -> "slack@anthropic");

        RequestContext.setSession("sess-1");
        pool.assembleToolPool("plugin:slack:1.0.0", config());

        pair[1].sendNotification(ChannelNotification.NOTIFICATION_METHOD,
            Map.of("content", "hello from slack", "meta", Map.of("chat_id", "123")));
        awaitTrue(queue::hasCommandsInQueue);

        NotificationQueue.QueueItem item = queue.dequeue().orElseThrow();
        assertThat(item.mode()).isEqualTo(NotificationQueue.MODE_PROMPT);
        assertThat(item.value()).startsWith("<channel source=\"plugin:slack:1.0.0\"");
        // [S07] 入队携带 origin（CC useManageMCPConnections.ts:528）
        assertThat(item.origin()).as("channel 入队必须携带 origin（CC origin:{kind:'channel',server}）").isNotNull();
        assertThat(item.origin().kind()).isEqualTo("channel");
        assertThat(item.origin().server()).isEqualTo("plugin:slack:1.0.0");

        // 主线程消费（Q-35）：重新入队同一项后 drainForQuery 消费该 prompt 项
        // （dequeue() 已取出 → 队列空；drainForQuery 消费语义由 ChannelInboundNotificationTest ⑥
        //  与 ChannelInjectionUntrustedBranchTest ① 覆盖，此处验证 origin 全链透传不丢）。
        // [3a] channel 项 sessionId=null → 无会话主线程（currentSessionId=null，测试等价全局执行器）消费。
        queue.enqueue(item);
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(false, null, null);
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).origin().kind()).isEqualTo("channel");
        assertThat(drained.get(0).origin().server()).isEqualTo("plugin:slack:1.0.0");
    }
    static class FakeFactory extends McpTransportFactory {
        private final McpTransport transport;
        FakeFactory(McpTransport transport) { this.transport = transport; }
        @Override
        public McpTransport create(McpTransport.TransportConfig config) { return transport; }
    }

    private static McpTransport.TransportConfig config() {
        return new McpTransport.TransportConfig("inproc", List.of(), Map.of(), null, null);
    }

    /** fake MCP server：initialize 声明 experimental['claude/channel'] + tools/list（channel server 形态）。 */
    static class ChannelServer {
        ChannelServer(InProcessMcpTransport server) {
            server.start(config());
            server.setRequestHandler((method, params) -> {
                switch (method) {
                    case "initialize":
                        return Map.of(
                            "protocolVersion", "2024-11-05",
                            "serverInfo", Map.of("name", "slack-server", "version", "1.0.0"),
                            "capabilities", Map.of(
                                "tools", Map.of(),
                                "experimental", Map.of("claude/channel", Map.of())));
                    case "tools/list":
                        return Map.of("tools", List.of(
                            Map.of("name", "send_message", "description", "Send a message",
                                "inputSchema", Map.of("type", "object"))));
                    default:
                        return Map.of();
                }
            });
        }
    }

    private static void awaitTrue(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timeout: async channel notification handler did not complete");
            }
            Thread.sleep(10);
        }
    }
}
