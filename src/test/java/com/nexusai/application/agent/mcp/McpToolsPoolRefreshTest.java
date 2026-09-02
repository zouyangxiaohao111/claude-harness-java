package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.skill.CreateSkillCommand;
import com.nexusai.application.agent.skill.McpSkillBuilders;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S04 (B4): tools/list_changed → LLM 工具池（McpServerService.mcpTools）刷新链集成测试（RED→GREEN）。
 *
 * <p>对齐 CC {@code useManageMCPConnections.ts:618-665}（tools/list_changed 处理器 → 缓存失效 →
 * {@code updateServer({...client, tools: newTools})} :656 —— LLM 池前缀组替换，:255-258）。
 *
 * <p>链路：InProcessMcpTransport.createLinkedPair() —— [0]=客户端（McpToolPool 持有），[1]=服务端
 * （可变工具列表 fake：首轮 tools/list 返回 [toolA]，切换后返回 [toolB]）。真实 McpServerService
 * （new + ReflectionTestUtils 注入 mock McpServerMapper / 真实 McpToolPool / mock
 * ChannelNotificationGate / mock McpTransportFactory）+ FakeFactory → {@code svc.start("1")} 走真实
 * 接线（含 setToolsPoolRefresher 注入点）→ 预热后服务端推送 {@code notifications/tools/list_changed}
 * → 处理器缓存失效 + 重取 → toolsPoolRefresher → {@code McpServerService.refreshMcpTools} 前缀重建
 * mcpTools。
 *
 * <p>断言（验收 1）：通知后 {@code getCurrentTools()} 含 {@code mcp__svr__toolB}、不含
 * {@code mcp__svr__toolA} —— LLM 池唯一源已刷新（RED：旧实现只更新 serverTools，mcpTools 不更新）。
 */
@DisplayName("S04 tools/list_changed → LLM 工具池刷新链")
class McpToolsPoolRefreshTest {

    private static final McpTransport.TransportConfig CONFIG =
        new McpTransport.TransportConfig("inproc", List.of(), Map.of(), null, null);

    @BeforeEach
    void registerBuilders() {
        // fetchMcpSkills 内部经 McpSkillBuilders.get() 取函数引用，须先注册
        // （对齐 ToolRegistrationConfig.skillRegistry() @Bean init + McpSkillsDiscoveryTest）
        McpSkillBuilders.register(new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields));
    }

    @Test
    @DisplayName("tools/list_changed 后 getCurrentTools() 含新工具 toolB、不含已删工具 toolA（CC :656 updateServer）")
    void listChanged_refreshesLlmToolPool() throws Exception {
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        MutableToolsServer server = new MutableToolsServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        McpServerService service = new McpServerService();
        McpServerMapper mapper = Mockito.mock(McpServerMapper.class);
        McpServerRecord record = new McpServerRecord();
        record.setId("1");
        record.setName("svr");
        record.setCommand("inproc");
        record.setEnabled(Boolean.TRUE);
        record.setApprovalStatus("approved");
        record.setType("inproc");
        Mockito.when(mapper.selectOneById("1")).thenReturn(record);
        ReflectionTestUtils.setField(service, "mcpServerMapper", mapper);
        ReflectionTestUtils.setField(service, "mcpToolPool", pool);
        ReflectionTestUtils.setField(service, "mcpTransportFactory",
            Mockito.mock(McpTransportFactory.class));
        ReflectionTestUtils.setField(service, "channelNotificationGate",
            Mockito.mock(ChannelNotificationGate.class));
        // [S07] start() 内 channelSessionAllowlist.currentRequestSupplier()（真实会话态注入，需非 null）
        ReflectionTestUtils.setField(service, "channelSessionAllowlist",
            new ChannelSessionAllowlist());

        // start() 走真实接线：assemble（initialize + tools/list）+ addMcpTool 进 LLM 池 + 通知处理器注册
        service.start("1");
        assertThat(service.getCurrentTools()).extracting(Tool::name)
            .contains("mcp__svr__toolA");

        // 服务端工具集变化（toolA 删除、toolB 新增）→ 推送 tools/list_changed
        server.switchToToolB();
        pair[1].sendNotification("notifications/tools/list_changed", Map.of());
        awaitTrue(() -> service.getCurrentTools().stream()
            .anyMatch(t -> "mcp__svr__toolB".equals(t.name())));

        // LLM 池刷新：新工具可调（在池）、已删工具不可调（出池）
        assertThat(service.getCurrentTools()).extracting(Tool::name)
            .as("tools/list_changed 后 LLM 工具池必须含新工具 toolB（CC :656 updateServer 全状态刷新）")
            .contains("mcp__svr__toolB");
        assertThat(service.getCurrentTools()).extracting(Tool::name)
            .as("已删工具 toolA 必须从 LLM 工具池移除（前缀组替换语义，CC :255-258 reject 旧前缀组）")
            .doesNotContain("mcp__svr__toolA");
    }

    @Test
    @DisplayName("默认未注入 toolsPoolRefresher → tools/list_changed no-op 不抛")
    void listChanged_withoutToolsPoolRefresher_noOp() throws Exception {
        // WHY: 默认 toolsPoolRefresher 是 no-op（镜像 skillPoolRefresher/promptPoolRefresher 模式）。
        // 未接线时 tools/list_changed 不得 NPE/抛异常 —— 纯 McpToolPool 使用场景（不经
        // McpServerService）缓存失效 + serverTools 更新行为不受影响。
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        MutableToolsServer server = new MutableToolsServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());

        pool.assembleToolPool("svr", CONFIG);
        assertThat(pool.fetchTools("svr")).hasSize(1);
        int toolsBefore = server.toolsList.get();

        pair[1].sendNotification("notifications/tools/list_changed", Map.of());
        awaitTrue(() -> server.toolsList.get() >= toolsBefore + 1);

        // 走到这里即证明 no-op 不抛（缓存失效 + serverTools 更新行为不受影响）
        assertThat(server.toolsList.get()).isGreaterThanOrEqualTo(toolsBefore + 1);
        assertThat(pool.fetchTools("svr")).hasSize(1);
    }

    // ── helpers（镜像 McpListChangedNotificationTest） ──

    /** fake factory: 每次 create 返回指定 transport（本测试为 InProcess 客户端侧 pair[0]）. */
    static class FakeFactory extends McpTransportFactory {
        private final McpTransport transport;

        FakeFactory(McpTransport transport) { this.transport = transport; }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return transport;
        }
    }

    /**
     * fake MCP server：记录 tools/list 往返计数，工具列表可变（首轮 [toolA] → switchToToolB 后
     * [toolB]）。initialize 声明 tools.listChanged=true（门控注册处理器）。
     */
    static class MutableToolsServer {
        final java.util.concurrent.atomic.AtomicInteger toolsList =
            new java.util.concurrent.atomic.AtomicInteger();
        private final AtomicBoolean useToolB = new AtomicBoolean(false);

        MutableToolsServer(InProcessMcpTransport server) {
            server.start(CONFIG);
            server.setRequestHandler((method, params) -> {
                switch (method) {
                    case "initialize":
                        return Map.of(
                            "protocolVersion", "2024-11-05",
                            "serverInfo", Map.of("name", "svr-server", "version", "1.0.0"),
                            "capabilities", Map.of(
                                "tools", Map.of("listChanged", true),
                                "resources", Map.of("listChanged", true),
                                "prompts", Map.of("listChanged", true)));
                    case "tools/list":
                        toolsList.incrementAndGet();
                        return Map.of("tools", List.of(
                            useToolB.get()
                                ? Map.of("name", "toolB", "description", "Tool B", "inputSchema", Map.of())
                                : Map.of("name", "toolA", "description", "Tool A", "inputSchema", Map.of())));
                    case "resources/list":
                        return Map.of("resources", List.of());
                    case "prompts/list":
                        return Map.of("prompts", List.of());
                    default:
                        return Map.of();
                }
            });
        }

        void switchToToolB() {
            useToolB.set(true);
        }
    }

    /** 轮询等待异步通知处理器完成（InProcess 分发在 ForkJoinPool 线程执行）。 */
    private static void awaitTrue(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timeout: async list_changed handler did not complete");
            }
            Thread.sleep(10);
        }
    }
}
