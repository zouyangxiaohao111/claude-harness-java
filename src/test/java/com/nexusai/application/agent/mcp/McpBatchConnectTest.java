package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [impl-I-4 T2] 批连接 local/remote 分组并发测试（CC getMcpToolsCommandsAndResources client.ts:2226-2403）。
 *
 * <p>WHY（规则九）：旧实现 start() 逐个串行 join，N 个 enabled server 启动耗时随数量线性；
 * 一个慢 server 阻塞后续全部。CC pMap slot 释放制 + local/remote 分组并发上限
 * （local=3 / remote=20，:552-561）要求慢 server 只占一个 slot。
 */
@DisplayName("[impl-I-4 T2] 批连接并发")
class McpBatchConnectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonRpcMcpClient client = new JsonRpcMcpClient();

    private McpTransport.TransportConfig stdioConfig(String cmd) {
        return new McpTransport.TransportConfig("python", List.of(cmd), Map.of(), null, null, "stdio");
    }

    private McpTransport.TransportConfig httpConfig(String url) {
        return new McpTransport.TransportConfig(null, null, Map.of(), null, null, "http");
    }

    @Test
    @DisplayName("local 组并发上限 ≤ 3（慢 server 不阻塞同组 slot）")
    void localGroup_concurrencyCeiling() {
        SlowFactory factory = new SlowFactory(100);
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), client);
        List<McpToolPool.McpServerConfigEntry> entries = List.of(
            new McpToolPool.McpServerConfigEntry("a", stdioConfig("a")),
            new McpToolPool.McpServerConfigEntry("b", stdioConfig("b")),
            new McpToolPool.McpServerConfigEntry("c", stdioConfig("c")),
            new McpToolPool.McpServerConfigEntry("d", stdioConfig("d")));  // 4 个 local（cap=3）
        // CopyOnWriteArrayList：onConnectionAttempt 从并发 worker 线程回调（processBatched runAsync），
        // 普通 ArrayList.add 非线程安全 → 并发 add 可能丢失元素致偶发断言失败（合并后已观测）
        List<String> called = new CopyOnWriteArrayList<>();
        SlowTransport.maxActive.set(0);
        long start = System.nanoTime();
        pool.getMcpToolsCommandsAndResources(entries,
            (name, config, tools, commands, resources) -> called.add(name)).join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // 4 个 100ms server，并发 3 → ~200ms（2 批）；串行会 ~400ms
        assertThat(SlowTransport.maxActive.get()).isLessThanOrEqualTo(3);
        assertThat(elapsedMs).isLessThan(300);
        assertThat(called).containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    @Test
    @DisplayName("single-thread executor 注入 → 并发 1（测试保序）")
    void singleThreadExecutor_serializes() {
        SlowFactory factory = new SlowFactory(20);
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), client);
        List<McpToolPool.McpServerConfigEntry> entries = List.of(
            new McpToolPool.McpServerConfigEntry("a", stdioConfig("a")),
            new McpToolPool.McpServerConfigEntry("b", stdioConfig("b")));
        SlowTransport.maxActive.set(0);
        pool.getMcpToolsCommandsAndResources(entries,
            (name, config, tools, commands, resources) -> {}, Executors.newSingleThreadExecutor()).join();
        assertThat(SlowTransport.maxActive.get()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("remote（http）组各自并发上限，失败 server → 空回调 fail-soft")
    void remoteGroup_andFailSoft() {
        FailFactory factory = new FailFactory();
        McpToolPool pool = new McpToolPool(factory, new ToolRegistry(), client);
        List<McpToolPool.McpServerConfigEntry> entries = List.of(
            new McpToolPool.McpServerConfigEntry("r1", httpConfig("http://a")),
            new McpToolPool.McpServerConfigEntry("r2", httpConfig("http://b")),
            new McpToolPool.McpServerConfigEntry("r3", httpConfig("http://c")),
            new McpToolPool.McpServerConfigEntry("r4", httpConfig("http://d")));
        // CopyOnWriteArrayList：回调从并发 worker 线程调用（同 localGroup_concurrencyCeiling）
        List<String> ok = new CopyOnWriteArrayList<>();
        List<String> failed = new CopyOnWriteArrayList<>();
        pool.getMcpToolsCommandsAndResources(entries,
            (name, config, tools, commands, resources) -> {
                if (name.equals("r1")) ok.add(name); else failed.add(name);
            }).join();
        // r1 成功回调；r2/r3/r4 失败 → 空回调 fail-soft（不抛，不阻断）
        assertThat(ok).containsExactly("r1");
        assertThat(failed).containsExactlyInAnyOrder("r2", "r3", "r4");
    }

    @Test
    @DisplayName("空 server 列表 → 立即完成")
    void emptyServers_completesImmediately() {
        McpToolPool pool = new McpToolPool(new McpTransportFactory(), new ToolRegistry(), client);
        pool.getMcpToolsCommandsAndResources(List.of(),
            (name, config, tools, commands, resources) -> {}).join();
    }

    // ═══════════ [impl-I-4 F1/F2 rework] 批路径注册 + 并发安全 ═══════════

    /**
     * WHY（规则九，反射 F1）：批连接（getMcpToolsCommandsAndResources/processBatchServer）首轮只
     * fetchTools+回调，不 serverTools.put / toolRegistry.register → 默认 prefetch-on-startup:true 预取
     * 走批路径后 activeServers() 为空 → T7 ReadMcpResourceTool + ListMcpResourcesTool/SubagentTool 对
     * 预取 server 报「Server not found」（T3 与 T7 互斥）。本测试验批连接 → activeServers → readResource
     * 端到端链路，杜绝互斥残留。
     */
    @Test
    @DisplayName("[F1] 批连接填充 serverTools + toolRegistry，端到端 readResource 成功")
    void batchConnect_populatesServerTools_andReadResourceWorks() {
        ToolRegistry registry = new ToolRegistry();
        McpToolPool pool = new McpToolPool(new ResourcesToolFactory(), registry, client);
        List<McpToolPool.McpServerConfigEntry> entries = List.of(
            new McpToolPool.McpServerConfigEntry("mock", stdioConfig("mock")));
        pool.getMcpToolsCommandsAndResources(entries,
            (name, config, tools, commands, resources) -> {}).join();
        // 批路径必须填 serverTools（activeServers 数据源）
        assertThat(pool.activeServers()).contains("mock");
        assertThat(pool.getServerTools("mock")).isPresent();
        // 工具已注册进 ToolRegistry（对齐 assembleToolPool :704 注册语义）
        assertThat(registry.has("mcp__mock__echo")).isTrue();
        // 端到端：批连接 → activeServers → readResource 真往返（resources/read 返回 mock 内容）
        assertThat(pool.readResource("mock", "mock://docs/readme"))
            .isEqualTo("Mock readme content for testing.");
    }

    /**
     * WHY（规则九，反射 F2）：activeTransports/serverConfigKeys/serverConfigs/serverTools/
     * serverCapabilities 原为 LinkedHashMap（非线程安全），T2 批连接 3/20 线程池并发 put → 数据竞态
     * （丢条目/CME）。改 ConcurrentHashMap 后 20 并发 server 全部进入 activeServers 且工具全部注册。
     */
    @Test
    @DisplayName("[F2] 批连接 20 server 并发：共享状态 Map 无数据竞态，全部 activeServers + 工具注册")
    void batchConnect_20Concurrent_noDataRace() {
        ToolRegistry registry = new ToolRegistry();
        McpToolPool pool = new McpToolPool(new ResourcesToolFactory(), registry, client);
        List<McpToolPool.McpServerConfigEntry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(new McpToolPool.McpServerConfigEntry("srv-" + i, httpConfig("http://srv-" + i)));
        }
        pool.getMcpToolsCommandsAndResources(entries,
            (name, config, tools, commands, resources) -> {}).join();
        // 并发 put 无丢条目（LinkedHashMap 并发会 CME/丢条目）
        assertThat(pool.activeServers()).hasSize(20);
        for (int i = 0; i < 20; i++) {
            assertThat(pool.getServerTools("srv-" + i)).isPresent();
            assertThat(registry.has("mcp__srv-" + i + "__echo")).isTrue();
        }
    }

    // ═══════════ fakes ═══════════

    /** start() 阻塞 sleepMs 模拟慢连接，跟踪最大并发。 */
    static class SlowFactory extends McpTransportFactory {
        private final long sleepMs;

        SlowFactory(long sleepMs) { this.sleepMs = sleepMs; }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return new SlowTransport(sleepMs);
        }
    }

    static class SlowTransport implements McpTransport {
        static final AtomicInteger maxActive = new AtomicInteger();
        private final long sleepMs;

        SlowTransport(long sleepMs) { this.sleepMs = sleepMs; }

        @Override
        public void start(McpTransport.TransportConfig config) {
            int cur = SLOW_ACTIVE.incrementAndGet();
            maxActive.accumulateAndGet(cur, Math::max);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SLOW_ACTIVE.decrementAndGet();
            }
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                result.putObject("capabilities");
            } else if ("tools/list".equals(method)) {
                result.putArray("tools");
            } else if ("resources/list".equals(method)) {
                result.putArray("resources");
            } else if ("prompts/list".equals(method)) {
                result.putArray("prompts");
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void sendNotification(String method, Object params) {}

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {}

        @Override
        public void close() {}

        @Override
        public State getState() { return State.CONNECTED; }
    }

    private static final AtomicInteger SLOW_ACTIVE = new AtomicInteger();

    /** r1 正常，r2/r3/r4 connect 抛错（fail-soft 空回调）。 */
    static class FailFactory extends McpTransportFactory {
        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            String name = config != null ? config.serverName() : null;
            return new FailTransport("r1".equals(name));
        }
    }

    static class FailTransport implements McpTransport {
        private final boolean ok;

        FailTransport(boolean ok) { this.ok = ok; }

        @Override
        public void start(McpTransport.TransportConfig config) {
            if (!ok) throw new IllegalStateException("connect failed");
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            result.putObject("capabilities");
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void sendNotification(String method, Object params) {}

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {}

        @Override
        public void close() {}

        @Override
        public State getState() { return State.CONNECTED; }
    }

    /** [F1/F2] tools/list 返回 1 个 echo 工具 + resources/read 返回 mock 内容。 */
    static class ResourcesToolFactory extends McpTransportFactory {
        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return new ResourcesToolTransport();
        }
    }

    static class ResourcesToolTransport implements McpTransport {
        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                // tools 能力必须声明（fetchTools 门控 caps.toolsList = capabilities.tools.isObject()）
                result.putObject("capabilities").putObject("tools").putObject("resources");
            } else if ("tools/list".equals(method)) {
                result.putArray("tools").addObject().put("name", "echo")
                    .putObject("inputSchema").put("type", "object");
            } else if ("resources/list".equals(method)) {
                result.putArray("resources");
            } else if ("prompts/list".equals(method)) {
                result.putArray("prompts");
            } else if ("resources/read".equals(method)) {
                result.putArray("contents").addObject()
                    .put("uri", "mock://docs/readme")
                    .put("mimeType", "text/plain")
                    .put("text", "Mock readme content for testing.");
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void sendNotification(String method, Object params) {}

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {}

        @Override
        public void close() {}

        @Override
        public State getState() { return State.CONNECTED; }
    }
}
