package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [impl-I-3 T2] 入站通知接线测试（RED→GREEN）· 对齐 CC useManageMCPConnections.ts:507-530
 * （handler 注册 + wrapChannelMessage 包裹 + enqueue {mode:'prompt', priority:'next',
 * isMeta:true, skipSlashCommands:true}）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: Q-35 入站 channel 消息必须作用户级消息入队
 * NotificationQueue 被主循环（LlmAgentLoop drainForQuery :2589）消费 —— 若 handler 不注册
 * / 包裹语义漂移 / meta 注入 / skipSlashCommands 语义错，channel 入站链路死链或产生
 * 属性注入风险。
 */
class ChannelInboundNotificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SERVER = "plugin:slack:1.0.0";
    private static final ChannelAllowlist.ChannelEntry SLACK_ENTRY =
        new ChannelAllowlist.ChannelEntry("plugin", "slack", "anthropic", false);

    /** 注册通过 + 有 ledger 条目的 gate（McpToolPool 集成用例）。 */
    private static ChannelNotificationGate passingGate() {
        return new ChannelNotificationGate(
            () -> true, () -> List.of(SLACK_ENTRY),
            () -> List.of(new ChannelAllowlistEntry("anthropic", "slack")),
            ChannelNotificationGate::escapeXmlAttr);
    }

    /** skip gate（channelsEnabled=false → DISABLED，handler 不注册用例）。 */
    private static ChannelNotificationGate disabledGate() {
        return new ChannelNotificationGate(
            () -> false, () -> List.of(SLACK_ENTRY),
            () -> List.of(new ChannelAllowlistEntry("anthropic", "slack")),
            ChannelNotificationGate::escapeXmlAttr);
    }

    private static McpTransport.TransportConfig config() {
        return new McpTransport.TransportConfig("inproc", List.of(), Map.of(), null, null);
    }

    // ─────────────────── ①③⑤ wrap + enqueue 语义（纯 ChannelNotification） ───────────────────

    @Test
    @DisplayName("① handler 收到 {content, meta} → 入队 mode=prompt/isMeta=true/skipSlashCommands=true/value 以 <channel source=> 开头")
    void receiveNotification_enqueuesPromptMode() {
        NotificationQueue queue = new NotificationQueue();
        ChannelNotification notif = new ChannelNotification(queue);
        notif.setGate(passingGate());

        boolean ok = notif.receiveNotification(SERVER, JSON.createObjectNode()
            .put("content", "hello")
            .set("meta", JSON.createObjectNode().put("chat_id", "123")));
        assertThat(ok).isTrue();

        assertThat(queue.hasCommandsInQueue()).isTrue();
        NotificationQueue.QueueItem item = queue.dequeue().orElseThrow();
        assertThat(item.mode()).as("CC enqueue L523 mode:'prompt'").isEqualTo(NotificationQueue.MODE_PROMPT);
        assertThat(item.priority()).as("CC enqueue L524 priority:'next'").isEqualTo(NotificationQueue.Priority.NEXT);
        assertThat(item.isMeta()).as("CC enqueue L525 isMeta:true（系统生成 UI 隐藏模型可见）").isTrue();
        assertThat(item.skipSlashCommands()).as("CC enqueue L529 skipSlashCommands:true").isTrue();
        assertThat(item.agentId()).as("主线程消息 agentId=null（LlmAgentLoop :2593 prompt 注入）").isNull();
        assertThat(item.value()).startsWith("<channel source=\"plugin:slack:1.0.0\"");
        assertThat(item.value()).contains("\nhello\n</channel>");
    }

    @Test
    @DisplayName("② meta 非法 key（x=\" injected=\"y）被 SAFE_META_KEY 过滤 + ③ content 原样嵌入（仅属性转义，非 content 转义）")
    void metaInjectionFiltered_andContentEmbeddedRaw() {
        NotificationQueue queue = new NotificationQueue();
        ChannelNotification notif = new ChannelNotification(queue);
        notif.setGate(passingGate());

        notif.receiveNotification(SERVER, JSON.createObjectNode()
            .put("content", "a<b&c>d\"e")
            .set("meta", JSON.createObjectNode()
                .put("chat_id", "123")
                .put("x=\" injected=\"y", "evil")));

        NotificationQueue.QueueItem item = queue.dequeue().orElseThrow();
        // 非法 meta key（含属性注入尝试）被过滤 → 不在 value 中
        assertThat(item.value()).doesNotContain("injected");
        assertThat(item.value()).contains("chat_id=\"123\"");
        // CC wrapChannelMessage L113-116：content 原样嵌入 \n{content}\n（不转义），
        // 仅 source 属性 + meta 属性值经 escapeXmlAttr 转义 → 断言 content 含 < & 原样保留
        assertThat(item.value()).contains("\na<b&c>d\"e\n</channel>");
    }

    @Test
    @DisplayName("⑤ 以 / 开头的 content 仍按纯文本入队（skipSlashCommands=true 语义）")
    void slashPrefixedContent_enqueuedAsPlainText() {
        NotificationQueue queue = new NotificationQueue();
        ChannelNotification notif = new ChannelNotification(queue);
        notif.setGate(passingGate());

        notif.receiveNotification(SERVER, JSON.createObjectNode().put("content", "/slack message"));

        NotificationQueue.QueueItem item = queue.dequeue().orElseThrow();
        assertThat(item.skipSlashCommands()).as("skipSlashCommands=true → '/' 开头仍按纯文本送模型（不触发 slash 命令）").isTrue();
        assertThat(item.value()).contains("\n/slack message\n</channel>");
    }

    @Test
    @DisplayName("null / 非文本 content → reject（不入队）")
    void nullContent_rejected() {
        NotificationQueue queue = new NotificationQueue();
        ChannelNotification notif = new ChannelNotification(queue);
        notif.setGate(passingGate());

        assertThat(notif.receiveNotification(SERVER, null)).isFalse();
        assertThat(notif.receiveNotification(SERVER, JSON.createObjectNode().put("content", 42))).isFalse();
        assertThat(queue.hasCommandsInQueue()).isFalse();
    }

    // ─────────────────── ④ 集成: gate skip → handler 不注册（McpToolPool） ───────────────────

    @Test
    @DisplayName("④ gate 返回 skip 的 server → handler 不注册（推送入站通知被静默忽略，连接保持）")
    void gateSkip_serverDoesNotRegisterHandler() throws Exception {
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        ChannelServer server = new ChannelServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        NotificationQueue queue = new NotificationQueue();
        pool.setChannelNotification(new ChannelNotification(queue));
        pool.setChannelNotificationGate(disabledGate());
        pool.setPluginSourceResolver(name -> "slack@anthropic");

        pool.assembleToolPool(SERVER, config());

        // 服务端推送入站 channel 通知 → 无 handler → 静默忽略（queue 恒空）
        pair[1].sendNotification(ChannelNotification.NOTIFICATION_METHOD,
            Map.of("content", "hello", "meta", Map.of("chat_id", "123")));
        Thread.sleep(150);
        assertThat(queue.hasCommandsInQueue())
            .as("gate skip → handler 不注册 → 入站通知不消费（CC L183-186）")
            .isFalse();
    }

    // ─────────────────── 注册 → handler 接线（McpToolPool 集成） ───────────────────

    @Test
    @DisplayName("gate register → handler 注册 → 推送入站通知被包裹入队")
    void gateRegister_handlerConsumesInbound() throws Exception {
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        ChannelServer server = new ChannelServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        NotificationQueue queue = new NotificationQueue();
        pool.setChannelNotification(new ChannelNotification(queue));
        pool.setChannelNotificationGate(passingGate());
        pool.setPluginSourceResolver(name -> "slack@anthropic");

        pool.assembleToolPool(SERVER, config());

        pair[1].sendNotification(ChannelNotification.NOTIFICATION_METHOD,
            Map.of("content", "hello", "meta", Map.of("chat_id", "123")));
        awaitTrue(queue::hasCommandsInQueue);

        NotificationQueue.QueueItem item = queue.dequeue().orElseThrow();
        assertThat(item.mode()).isEqualTo(NotificationQueue.MODE_PROMPT);
        assertThat(item.value()).startsWith("<channel source=\"plugin:slack:1.0.0\"");
        assertThat(item.value()).contains("chat_id=\"123\"");
    }

    // ─────────────────── ⑥ 集成: drainForQuery(false, null, null) 全局主线程消费 ───────────────────

    @Test
    @DisplayName("⑥ 入队 → drainForQuery(false, null, null) 全局主线程消费该 prompt 项（Q-35 主循环消费）")
    void enqueue_thenDrainForQueryMainThreadConsumes() {
        NotificationQueue queue = new NotificationQueue();
        ChannelNotification notif = new ChannelNotification(queue);
        notif.setGate(passingGate());
        notif.receiveNotification(SERVER, JSON.createObjectNode().put("content", "hi from slack"));

        // [3a] channel 项 sessionId=null（ChannelNotification:128 9-arg 构造无会话归属）→
        // 具体会话 turn 一律不捞（交 CronIdleExecutor）；无会话主线程（currentSessionId=null，测试等价
        // 全局执行器）消费全局命令，此处验证 origin 全链透传经 drain 不丢。
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(false, null, null);
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).mode()).isEqualTo(NotificationQueue.MODE_PROMPT);
        assertThat(drained.get(0).value()).startsWith("<channel source=\"plugin:slack:1.0.0\"");
        assertThat(queue.hasCommandsInQueue()).isFalse();
    }

    // ─────────────────── ⑦ 能力信号: Capabilities.fromInitializeResult 解析 experimental ───────────────────

    @Test
    @DisplayName("⑦ Capabilities.fromInitializeResult 解析 capabilities.experimental['claude/channel']（R2-1）")
    void fromInitializeResult_parsesExperimental() {
        com.fasterxml.jackson.databind.node.ObjectNode caps = JSON.createObjectNode();
        caps.putObject("experimental").putObject("claude/channel").put("enabled", true);
        com.fasterxml.jackson.databind.node.ObjectNode root = JSON.createObjectNode();
        root.set("capabilities", caps);

        JsonRpcMcpClient.Capabilities parsed = JsonRpcMcpClient.Capabilities.fromInitializeResult(root);
        assertThat(parsed.experimental()).as("R2-1: experimental 字段必须解析出 claude/channel key").containsKey("claude/channel");
        assertThat(parsed.experimental().get("claude/channel")).isInstanceOf(Map.class);

        // 无 experimental → Map.of()（CC capabilities.experimental 缺失 → undefined）
        JsonRpcMcpClient.Capabilities plain = JsonRpcMcpClient.Capabilities.fromInitializeResult(
            JSON.createObjectNode().set("capabilities", JSON.createObjectNode()));
        assertThat(plain.experimental()).isEmpty();
    }

    // ─────────────────── ⑧ Phase 4 (cron-notify): 入站 channel 通知带创建会话 sessionId ───────────────────

    @Test
    @DisplayName("⑧ 带 sessionId 入队 → 创建会话 turn drain 注入该 channel 消息（cron-notify）")
    void enqueue_withSessionId_drainsToThatSessionTurn() {
        NotificationQueue queue = new NotificationQueue();
        ChannelNotification notif = new ChannelNotification(queue);
        notif.setGate(passingGate());
        String createSession = "sess-channel-cn1";
        notif.receiveNotification(SERVER, JSON.createObjectNode().put("content", "hello from slack"), createSession);

        // 创建会话 turn drain 注入（3a: 只捞本会话命令）——先 drain 再断言，避免 dequeueAll 清空
        NotificationQueue.QueueItem drained = queue.drainForQuery(false, null,
            createSession).stream()
            .filter(i -> NotificationQueue.MODE_PROMPT.equals(i.mode()))
            .findFirst().orElse(null);
        assertThat(drained).as("创建会话 turn 必须捞到自己的 channel 消息（cron-notify）").isNotNull();
        assertThat(drained.value()).contains("hello from slack");
        // 通知必须带创建会话 sessionId（handler 注册时 MDC 捕获）
        assertThat(com.nexusai.common.SessionKeys.canonicalUuid(drained.sessionId()))
            .as("channel 通知必须携带创建会话 sessionId（CC enqueue 注入当前会话的 Java 显式化）")
            .isEqualTo(createSession);
    }

    @Test
    @DisplayName("⑨ 无会话上下文 → sessionId=null 回落全局（CronIdleExecutor 代跑）")
    void enqueue_noSession_fallsBackGlobal() {
        NotificationQueue queue = new NotificationQueue();
        ChannelNotification notif = new ChannelNotification(queue);
        notif.setGate(passingGate());
        notif.receiveNotification(SERVER, JSON.createObjectNode().put("content", "hi"));

        NotificationQueue.QueueItem item = queue.dequeueAll().stream()
            .filter(i -> NotificationQueue.MODE_PROMPT.equals(i.mode()))
            .findFirst().orElseThrow();
        assertThat(item.sessionId()).as("无会话上下文 → sessionId=null（回落全局）").isNull();
    }

    // ─────────────────── test harness（对齐 McpListChangedNotificationTest） ───────────────────

    static class FakeFactory extends McpTransportFactory {
        private final McpTransport transport;
        FakeFactory(McpTransport transport) { this.transport = transport; }
        @Override
        public McpTransport create(McpTransport.TransportConfig config) { return transport; }
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

    /** 轮询等待异步通知处理器完成（InProcess 分发在 ForkJoinPool 线程执行）。 */
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
