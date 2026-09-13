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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S07 · 验收 1/4] 会话态 --channels 注入测试（RED→GREEN）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: CC 的 session 白名单是真实会话态（state.ts:1676-1682
 * {@code STATE.allowedChannels} + main.tsx:1692-1696 {@code parseChannelEntries → setAllowedChannels}），
 * 而 Java 旧接线恒空（{@code setAllowedChannelsSupplier(List::of)}）→ gate 门序[3 session]
 * 恒 skip → 入站 channel 功能生产不可达。本测试验证 ChannelSessionAllowlist（sessionId 键控
 * 注册表 + <b>显式</b>会话查表）注入后：写白名单 → 放行 → 入队端到端可达；无白名单 → fail-closed
 * 安全默认不倒退（S07.md §5 验收 1/4）。
 *
 * <p><b>[批 3b] 本测试改写要点</b>：旧版本把会话来源写成「测试线程设 ambient 会话槽」后调
 * {@code allowlist.currentRequestSupplier().get()} —— 那是「测试线程设 → 同线程读回」的
 * 零覆盖力夹具（经 ThreadLocal 自证，生产派生线程上恒失败）。现改为：
 * <ol>
 *   <li>① ~ ④ 直接用 {@code sessionLookup().apply(sessionId)}（纯函数，无 ThreadLocal）；</li>
 *   <li>⑤ 端到端走<b>真实派生线程</b>（McpToolPool 的 connectWorker）：断言求值线程 ≠ 断言线程、
 *       且显式传入的会话才是放行原因；</li>
 *   <li>⑥ 反向对照：显式 sessionId=null → 门序[3] SESSION skip（fail-closed）。</li>
 * </ol>
 *
 * <p><b>[批 3c]</b>：ambient 会话槽（裸 MDC）已整类删除 ⇒ ⑤ 中「写调用方线程残留值当反向对照 +
 * 在派生线程上捕获 ambient 会话断言为 null」的装置与断言已删（该线程上 ambient 路线在结构上
 * 取不到会话）；显式会话值断言与 fail-closed 断言全部保留。
 *
 * <p>server-kind entry 经 allowlist 门需要 dev=true（CC channelNotification.ts:302-313：
 * allowlist schema 仅 plugin，server entry 恒不匹配除非 dev 豁免）——本测试按 CC 语义构造
 * {@code new ChannelEntry("server", "my-server", null, true)}，dev 位是 CC 既有的 per-entry
 * 信任声明（main.tsx 侧 --dangerously-load-development-channels 产物），非本 Session 新增能力。
 */
class ChannelSessionAllowlistTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 门序全过（capability + channelsEnabled + session + server-kind dev 豁免）→ register。 */
    private static ChannelNotificationGate gateFor(Function<String, List<ChannelAllowlist.ChannelEntry>> lookup) {
        ChannelNotificationGate gate = new ChannelNotificationGate(
            () -> true, List::of,
            () -> List.of(new ChannelAllowlistEntry("anthropic", "slack")),
            ChannelNotificationGate::escapeXmlAttr);
        gate.setAllowedChannelsProvider(lookup);
        return gate;
    }

    @Test
    @DisplayName("① 真实会话白名单（含 server）+ 显式 sessionId → gate 门序[3 session] 放行 → register（验收 1 放行臂）")
    void sessionAllowlist_injectsAndGateRegisters() {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();
        allowlist.setForSession("sess-1", List.of(
            new ChannelAllowlist.ChannelEntry("server", "my-server", null, true)));

        List<ChannelAllowlist.ChannelEntry> current = allowlist.sessionLookup().apply("sess-1");
        assertThat(current)
            .as("sessionLookup 必须按显式 sessionId 解析白名单（非恒空、不读 ThreadLocal）")
            .hasSize(1)
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactly("my-server");

        ChannelNotificationGate gate = gateFor(allowlist.sessionLookup());
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "my-server", new ChannelNotificationGate.ServerCapabilities(Map.of("claude/channel", Map.of())),
            null, "sess-1");
        assertThat(r.action())
            .as("会话白名单命中 + 门序全过 → register（旧恒空接线必 SESSION skip，本断言对旧实现必失败）")
            .isEqualTo("register");
    }

    @Test
    @DisplayName("② 未 setForSession（同会话但无白名单）→ 空表 fail-closed → SESSION skip（验收 4）")
    void noAllowlistForSession_failsClosed() {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();

        assertThat(allowlist.sessionLookup().apply("sess-1"))
            .as("未写入白名单的会话 → 空表（fail-closed）")
            .isEmpty();

        ChannelNotificationGate gate = gateFor(allowlist.sessionLookup());
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "my-server", new ChannelNotificationGate.ServerCapabilities(Map.of("claude/channel", Map.of())),
            null, "sess-1");
        assertThat(r.action()).isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.SESSION);
    }

    @Test
    @DisplayName("③ sessionId 为 null/blank → 空表 fail-closed；其它会话白名单不影响当前会话（会话隔离）")
    void noSessionContext_orOtherSession_failsClosed() {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();
        allowlist.setForSession("sess-A", List.of(
            new ChannelAllowlist.ChannelEntry("server", "server-a", null, true)));

        // 无会话（null / blank）→ 空表
        assertThat(allowlist.sessionLookup().apply(null)).isEmpty();
        assertThat(allowlist.sessionLookup().apply("")).isEmpty();
        assertThat(allowlist.sessionLookup().apply("   ")).isEmpty();

        // 会话隔离：session-B 读不到 session-A 的白名单
        assertThat(allowlist.sessionLookup().apply("sess-B"))
            .as("会话隔离：session-B 白名单不得被 session-A 污染")
            .isEmpty();

        // session-A 自身仍可读（写/读同键一致）
        assertThat(allowlist.sessionLookup().apply("sess-A"))
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

        assertThat(allowlist.sessionLookup().apply("sess-1"))
            .as("clearSession 后 → 空表 fail-closed")
            .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // ⑤⑥ [批 3b] 真实派生线程（connectWorker）实证 —— 显式会话 vs ambient ThreadLocal
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY（规则九 · 验证意图）：channel 门序[3 session] 在 {@code McpToolPool.connectWorker}
     * <b>池线程</b>求值（不是 REST/Tomcat 线程）。旧实现经 ambient 会话槽（裸 MDC）
     * 取会话，靠 {@code connectTransport} 的「MDC 回放到 connectWorker」装置兜住 —— 回放属
     * 「用 ThreadLocal 冒充显式传递」。本测试锁死修复后的因果链：
     * <ul>
     *   <li>门序求值确实发生在<b>派生线程</b>（线程名 ≠ 断言线程名）；</li>
     *   <li>放行的唯一原因是<b>显式传入</b>的 {@code connectSessionId}（值语义跨线程）。</li>
     * </ul>
     *
     * <p><b>[批 3c] 语义消失（已登记待裁定）</b>：原用例还在派生线程上捕获 ambient 会话读取并断言为
     * null（证明放行不来自 ThreadLocal），并在调用方线程写一個「别的会话」的裸 MDC 当反向对照。
     * 该 ambient 会话槽已整类删除 ⇒ 两处装置与 `gateEvalThreadMdc.isNull()` 断言删除；
     * 派生线程 / 显式会话值两条断言保留。
     */
    @Test
    @DisplayName("⑤ 端到端（真实 connectWorker 线程）：门序[3] 用显式 sessionId 放行")
    void endToEnd_gateResolvesOnDerivedThread_viaExplicitSession() throws Exception {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();
        // plugin-kind entry（gate 门序[4] marketplace + [5] ledger 走真实校验路径）
        allowlist.setForSession("sess-1", List.of(
            new ChannelAllowlist.ChannelEntry("plugin", "slack", "anthropic", false)));

        // ── 取样：门序求值线程 + 该线程收到的显式会话（不改语义，纯观察包装） ──
        AtomicReference<String> gateEvalThread = new AtomicReference<>(null);
        AtomicReference<String> explicitSidSeen = new AtomicReference<>(null);
        AtomicInteger gateEvalCount = new AtomicInteger();
        Function<String, List<ChannelAllowlist.ChannelEntry>> observed = sid -> {
            gateEvalCount.incrementAndGet();
            gateEvalThread.set(Thread.currentThread().getName());
            explicitSidSeen.set(sid);
            return allowlist.sessionLookup().apply(sid);
        };

        McpToolPool pool = newPool(observed, new NotificationQueue()).pool();

        String callerThreadName = Thread.currentThread().getName();

        pool.assembleToolPool("plugin:slack:1.0.0", config(), "sess-1");

        assertThat(gateEvalCount.get()).as("门序[3] 必须被求值一次").isEqualTo(1);
        assertThat(gateEvalThread.get())
            .as("门序[3] 必须在派生线程（connectWorker 池线程）求值，而非断言线程")
            .isNotNull()
            .isNotEqualTo(callerThreadName);
        assertThat(explicitSidSeen.get())
            .as("门序[3] 收到的会话必须等于调用方显式传入的 connectSessionId")
            .isEqualTo("sess-1");
    }

    /**
     * WHY：显式传 null = 无会话（启动预取 / 惰性重连等本就不带会话的路径）→ channel 门序[3]
     * 恒 SESSION skip，handler 不注册（fail-closed，与 CC「server 未列入 --channels」同向）。
     *
     * <p><b>[批 3c] 语义消失（已登记待裁定）</b>：原用例的「即便调用方线程有 stale MDC」反向对照
     * 依赖已整类删除的裸 MDC 会话槽 ⇒ 装置删除；「显式 null → fail-closed」的核心断言原样保留。
     */
    @Test
    @DisplayName("⑥ 反向对照：显式 sessionId=null → 门序[3] SESSION skip，handler 不注册（fail-closed）")
    void endToEnd_nullSession_failsClosed() throws Exception {
        ChannelSessionAllowlist allowlist = new ChannelSessionAllowlist();
        allowlist.setForSession("sess-1", List.of(
            new ChannelAllowlist.ChannelEntry("plugin", "slack", "anthropic", false)));

        NotificationQueue queue = new NotificationQueue();
        PoolFixture fx = newPool(allowlist.sessionLookup(), queue);

        // [批 3c] 语义消失：原此处 set("sess-1", ...) 在调用方线程写一个合法会话当反向对照
        //   （证明本路径不读 ambient）。该裸 MDC 槽已整类删除 ⇒ 装置删除。
        fx.pool().assembleToolPool("plugin:slack:1.0.0", config(), null);

        // 服务端推送入站 channel 通知（handler 未注册 → 静默忽略）
        fx.server().sendNotification(ChannelNotification.NOTIFICATION_METHOD,
            Map.of("content", "hello from slack", "meta", Map.of("chat_id", "123")));
        Thread.sleep(300); // 入站 handler 异步派发：给「未注册 → 静默忽略」路径机会（若误注册则会入队）

        assertThat(queue.hasCommandsInQueue())
            .as("显式无会话 → 门序[3] SESSION skip → handler 未注册 → 入站 channel 消息不入队")
            .isFalse();
    }

    // ────────────────────────────────────────────────────────────────────
    // 夹具
    // ────────────────────────────────────────────────────────────────────

    /** 真实 McpToolPool + 内存传输对（server 侧留着供推送入站通知）。 */
    private record PoolFixture(McpToolPool pool, InProcessMcpTransport server) {}

    private static PoolFixture newPool(Function<String, List<ChannelAllowlist.ChannelEntry>> lookup,
                                       NotificationQueue queue) {
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        new ChannelServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        ChannelNotificationGate gate = new ChannelNotificationGate(
            () -> true, List::of,
            () -> List.of(new ChannelAllowlistEntry("anthropic", "slack")),
            ChannelNotificationGate::escapeXmlAttr);
        gate.setAllowedChannelsProvider(lookup);
        pool.setChannelNotification(new ChannelNotification(queue));
        pool.setChannelNotificationGate(gate);
        pool.setPluginSourceResolver(name -> "slack@anthropic");
        return new PoolFixture(pool, pair[1]);
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
