package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpNotificationHandler;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentMcpServers;
import com.nexusai.application.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.RunRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-I-9 Q-30] 子代理继承 MCP 连接 · 对齐 CC runAgent.ts:653-656/104-110/213-217/197-210.
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: 子代理不应重复连接父已认证的 MCP server，
 * 而应继承父连接数组（parentClients = 父实际连接对象）。旧实现 {@code SubagentExecutor.initializeAgentMcp}
 * 硬编码 {@code List.of()}（EV-8），嵌套第 2 层收到的是 int 计数而非连接对象。本测试锁语义：
 * <ol>
 *   <li>父 context 带 1 个 parent connection，子 agent 无 frontmatter MCP → InitResult.clients()
 *       非空（=父连接，共享继承）</li>
 *   <li>子有 inline MCP → mergedClients = 父+子</li>
 *   <li>cleanup 只调 newlyCreated（父 connection.cleanup 不被调）</li>
 *   <li>嵌套第 2 层收到第 1 层 mergedClients（连接对象传递，非 int）</li>
 *   <li>复用父已建连接不重建 transport（连接对象同一引用）</li>
 * </ol>
 */
@DisplayName("[MCP-I-9 Q-30] 子代理继承 MCP 连接（连接对象传递，非 int）")
class SubagentMcpInheritanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 可追踪 cleanup 的 parent client. */
    static class TrackedClient implements AgentMcpServers.McpServerConnection {
        final String name;
        final AtomicInteger cleanups = new AtomicInteger();
        TrackedClient(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public List<Tool> getTools() { return List.of(); }
        @Override public void cleanup() { cleanups.incrementAndGet(); }
    }

    /** fake transportFactory: 每次 create 返回一个可追踪 close 的 fake transport. */
    static class FakeFactory extends McpTransportFactory {
        final AtomicInteger closeCount = new AtomicInteger();
        @Override public McpTransport create(McpTransport.TransportConfig config) {
            return new FakeTransport(closeCount);
        }
    }

    /** fake transport: initialize/tools/list 返固定 JSON, close() 计数. */
    static class FakeTransport implements McpTransport {
        private final AtomicInteger closeCount;
        FakeTransport(AtomicInteger closeCount) { this.closeCount = closeCount; }
        @Override public void start(McpTransport.TransportConfig config) {}
        @Override public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            if ("initialize".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                r.putObject("serverInfo").put("name", "fake-server");
                // [S05 A6] capabilities.tools 门控（truthy {}）→ tools/list 才发生
                r.putObject("capabilities").putObject("tools");
                return CompletableFuture.completedFuture(r);
            }
            if ("tools/list".equals(method)) {
                ObjectNode r = MAPPER.createObjectNode();
                ArrayNode tools = r.putArray("tools");
                ObjectNode tool = tools.addObject();
                tool.put("name", "read_file");
                tool.put("description", "Read a file");
                return CompletableFuture.completedFuture(r);
            }
            return CompletableFuture.completedFuture(MAPPER.createObjectNode());
        }
        @Override public void sendNotification(String method, Object params) {}
        @Override public void setNotificationHandler(String method, McpNotificationHandler handler) {}
        @Override public void close() { closeCount.incrementAndGet(); }
        @Override public State getState() { return State.CONNECTED; }
    }

    @BeforeEach
    void resetMemoizeCache() {
        // [S05 A6] 静态 memoize 缓存跨测试污染（既有测试复用 spec 名 agent-server/a/b）→ 每用例复位
        AgentMcpServers.clearConnectionCache();
    }

    private static Supplier<Map<String, Object>> noLock() { return Map::of; }

    /** 构造一个带 frontmatter MCP 的 AgentDefinition 需要 SubagentExecutor 的 resolveAgentDefinition；
     *  这里直接测 AgentMcpServers.initialize + SubagentExecutor.initializeAgentMcp（package-private seam）. */

    @Test
    @DisplayName("父连接 1 个 + 子无 frontmatter MCP → InitResult.clients() = 父连接（共享继承）")
    void noFrontmatter_returnsParentClients() {
        // WHY: 无 frontmatter MCP 时子 agent 应直接复用父连接（CC runAgent.ts:104-110），
        // 不新建连接。旧实现返 List.of()（EV-8）→ 嵌套子代理丢失父 MCP 能力。
        TrackedClient parent = new TrackedClient("parent-server");
        FakeFactory factory = new FakeFactory();
        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of()),        // 无 frontmatter mcpServers
            List.of(parent),
            factory,
            "userSettings",
            noLock(),
            60_000,
            null);  // [S05] elicitation 状态机（测试 null = 不接 elicitation）

        assertThat(result.clients())
            .as("无 frontmatter → 直接返 parentClients（共享继承）")
            .hasSize(1)
            .containsExactly(parent);
        assertThat(factory.closeCount.get()).isZero();
    }

    @Test
    @DisplayName("父连接 + 子 inline MCP → mergedClients = 父 + 子")
    void inlineMcp_mergesParentAndAgent() {
        TrackedClient parent = new TrackedClient("parent-server");
        FakeFactory factory = new FakeFactory();
        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of(new AgentMcpServers.McpServerSpec("agent-server", "cmd", List.of(), Map.of()))),
            List.of(parent),
            factory,
            "userSettings",
            noLock(),
            60_000,
            null);

        // 父 + agent 各自连接
        assertThat(result.clients()).hasSize(2);
        assertThat(result.clients().get(0)).isSameAs(parent);
        assertThat(result.clients().get(1).name()).isEqualTo("agent-server");
    }

    @Test
    @DisplayName("cleanup 只调 newlyCreated（父 connection.cleanup 不被调）")
    void cleanup_onlyNewlyCreated() {
        TrackedClient parent = new TrackedClient("parent-server");
        FakeFactory factory = new FakeFactory();
        AgentMcpServers.InitResult result = AgentMcpServers.initialize(
            Optional.of(List.of(new AgentMcpServers.McpServerSpec("agent-server", "cmd", List.of(), Map.of()))),
            List.of(parent),
            factory,
            "userSettings",
            noLock(),
            60_000,
            null);

        result.cleanup().run();
        assertThat(factory.closeCount.get()).isEqualTo(1);   // agent newly created 被清
        assertThat(parent.cleanups.get()).isZero();           // 父共享连接不清
    }

    @Test
    @DisplayName("嵌套第 2 层收到第 1 层 mergedClients（连接对象传递，非 int）")
    void nestedLayer2_receivesLayer1MergedClients() {
        // 第 1 层：子 agent 带 inline MCP，mergedClients = 父 + agent
        TrackedClient parent = new TrackedClient("parent-server");
        FakeFactory factory = new FakeFactory();
        AgentMcpServers.InitResult layer1 = AgentMcpServers.initialize(
            Optional.of(List.of(new AgentMcpServers.McpServerSpec("agent-server", "cmd", List.of(), Map.of()))),
            List.of(parent),
            factory,
            "userSettings",
            noLock(),
            60_000,
            null);

        // 第 2 层：以第 1 层 mergedClients 为 parentClients
        AgentMcpServers.InitResult layer2 = AgentMcpServers.initialize(
            Optional.of(List.of()),         // 第 2 层无 frontmatter MCP
            layer1.clients(),
            factory,
            "userSettings",
            noLock(),
            60_000,
            null);

        // 第 2 层收到第 1 层 mergedClients 的同一对象引用（连接对象传递，非 int 计数）
        assertThat(layer2.clients())
            .as("嵌套第 2 层必须收到第 1 层 mergedClients（连接对象传递）")
            .hasSize(2)
            .containsExactlyElementsOf(layer1.clients());
        assertThat(layer2.clients().get(0)).isSameAs(parent);
        assertThat(layer2.clients().get(1)).isSameAs(layer1.clients().get(1));
    }

    @Test
    @DisplayName("复用父已建连接不重建 transport（连接对象同一引用）")
    void reusesParentConnection_noTransportRebuild() {
        TrackedClient parent = new TrackedClient("parent-server");
        FakeFactory factory = new FakeFactory();
        // 同一 parent 连接对象传给两次 initialize → mergedClients 中都是同一引用
        AgentMcpServers.InitResult r1 = AgentMcpServers.initialize(
            Optional.of(List.of(new AgentMcpServers.McpServerSpec("a", "cmd", List.of(), Map.of()))),
            List.of(parent), factory, "userSettings", noLock(), 60_000, null);
        AgentMcpServers.InitResult r2 = AgentMcpServers.initialize(
            Optional.of(List.of(new AgentMcpServers.McpServerSpec("b", "cmd", List.of(), Map.of()))),
            r1.clients(), factory, "userSettings", noLock(), 60_000, null);

        // parent 连接对象在 r1/r2 中都复用同一引用（不重建 transport）
        assertThat(r1.clients().get(0)).isSameAs(parent);
        assertThat(r2.clients().get(0)).isSameAs(parent);
    }

    @Test
    @DisplayName("SubagentExecutor.initializeAgentMcp 透传 parentClients（Step 15 seam）")
    void executorInitializeAgentMcp_passesParentClients() {
        // WHY: SubagentExecutor.initializeAgentMcp 是主链 Step 15 入口；旧实现硬编码
        // List.of()（EV-8），parentClients 被丢弃。本 seam 测试改签名后透传生效。
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "gpt-4", "");
        TrackedClient parent = new TrackedClient("parent-server");
        FakeFactory factory = new FakeFactory();
        executor.setMcpTransportFactory(factory);

        // 无 frontmatter mcpServers（mcpServers Optional.empty() → initializeAgentMcp 走共享继承分支）
        AgentDefinition def = AgentDefinition.CustomAgentDefinition.builder(
            "test", "test-agent", "userSettings", "prompt").build();
        AgentMcpServers.InitResult result = executor.initializeAgentMcp(def, List.of(parent));

        assertThat(result.clients())
            .as("executor Step 15 必须把 parentClients 透传进 AgentMcpServers.initialize")
            .containsExactly(parent);
    }

    @Test
    @DisplayName("[Q-09-R2-1] 主链 base TUC mcpServerConnections 含活跃池连接包装（顶层继承）")
    void topLevel_baseTucInheritsActivePoolConnections() {
        // WHY: Q-09-R2-1 裁决「主链活跃池连接注入 base TUC mcpServerConnections」——
        // 顶层子代理应继承主链已连接的活跃池（对齐 CC runAgent.ts:653-656
        // initializeAgentMcpServers(agentDefinition, toolUseContext.options.mcpClients)，
        // parentClients 来源 = 主链活跃池）。旧实现 LlmAgentLoop.buildBaseToolUseContext
        // 恒空 List.of()（EV-S09-24/25/26）→ 顶层子代理拿不到主链 MCP 连接。
        Tool readTool = TestContexts.dummyTool("mcp__fs__read_file");
        Tool writeTool = TestContexts.dummyTool("mcp__fs__write_file");
        Tool bashTool = TestContexts.dummyTool("Bash");
        McpServerService service = mock(McpServerService.class);
        when(service.getCurrentTools()).thenReturn(List.of(readTool, writeTool, bashTool));

        LlmProvider provider = mock(LlmProvider.class);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = new LlmAgentLoop(factory, null, new ToolRegistry());
        loop.setMcpServerService(service);
        loop.setTokenBudgetChecker(new TokenBudgetChecker());
        loop.setQueryConfig(new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)));
        doAnswer(inv -> {
            ToolUseContext tuc = loop.getCurrentToolUseContext();
            if (tuc != null) {
                tucRef.set(tuc);
            }
            Consumer<String> onChunk = inv.getArgument(9);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("hello");
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        AgentState state = loop.run(RunRequest.session(
            "hello", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null /* 主线程 agentId=null */,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).as("run() 必须返回非 null AgentState").isNotNull();
        ToolUseContext tuc = tucRef.get();
        assertThat(tuc).as("per-turn TUC 必须非 null（base TUC 派生）").isNotNull();
        List<AgentMcpServers.McpServerConnection> conns = tuc.mcpServerConnections();
        assertThat(conns)
            .as("主链 base TUC 必须含活跃池连接包装（Q-09-R2-1；修复前恒空 List.of() → RED）")
            .hasSize(1);
        assertThat(conns.get(0).name()).isEqualTo("fs");
        assertThat(conns.get(0).getTools())
            .as("连接包装 getTools 含活跃池工具（非 mcp 工具被过滤）")
            .containsExactly(readTool, writeTool);
        // cleanup no-op：共享池连接不被 agent cleanup 清（CC runAgent.ts:196-210 共享 client 不清）
        conns.get(0).cleanup();
        assertThat(conns.get(0).getTools()).containsExactly(readTool, writeTool);
    }

    @Test
    @DisplayName("[Q-09-R2-1] mcpServerService null → base TUC 连接恒空（无回归）")
    void topLevel_noService_connectionsEmpty() {
        // 反向：未注入 McpServerService（测试/无 MCP 装配）→ buildBaseMcpServerConnections
        // 返空列表，保持旧行为（无回归）。
        LlmProvider provider = mock(LlmProvider.class);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = new LlmAgentLoop(factory, null, new ToolRegistry());
        loop.setTokenBudgetChecker(new TokenBudgetChecker());
        loop.setQueryConfig(new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)));
        doAnswer(inv -> {
            ToolUseContext tuc = loop.getCurrentToolUseContext();
            if (tuc != null) {
                tucRef.set(tuc);
            }
            Consumer<String> onChunk = inv.getArgument(9);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("hello");
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        AgentState state = loop.run(RunRequest.session(
            "hello", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).isNotNull();
        ToolUseContext tuc = tucRef.get();
        assertThat(tuc).isNotNull();
        assertThat(tuc.mcpServerConnections())
            .as("mcpServerService null → base TUC 连接恒空（无回归）")
            .isEmpty();
    }
}
