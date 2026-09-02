package com.nexusai.apis.mcp;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import org.mockito.Mockito;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入站 MCP server（Spring AI）接入契约测试 · 替代旧 McpServerEndpointTest（固守抛异常）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：Q-23 拍板"用 Spring Boot/Spring AI 现成
 * MCP server 暴露 ToolRegistry 工具"。本测试验证 4 项意图而非骨架抛异常：
 * <ol>
 *   <li><b>tools/list</b> 返回 ToolRegistry 已注册工具（isEnabled + SPECIAL_TOOLS 过滤，
 *       description = prompt() ?? description()，inputSchema 携带）—— 对齐 CC
 *       {@code entrypoints/mcp.ts:59-97}。</li>
 *   <li><b>tools/call 成功</b> 返回 text 内容 —— 对齐 CC {@code mcp.ts:159-168}。</li>
 *   <li><b>tools/call 错误路径</b>（未注册工具 / isEnabled=false / 权限 deny / 执行失败）
 *       返回 {@code isError:true}，<b>不抛到 HTTP 层（不 500）</b> —— 对齐 CC
 *       {@code mcp.ts:170-186} catch → isError。</li>
 *   <li><b>路径不撞车</b>：/mcp 归 Spring AI 端点接管（真实 MCP client 连通即证），
 *       /api/v1/mcp 仍归出站 CRUD（McpServerController）。</li>
 * </ol>
 *
 * <p><b>最小上下文（历史形态）</b>：本测试用 {@link MinimalMcpContextConfig} 仅装配
 * {@link ToolRegistry} + 5 个探针工具 + {@link InboundMcpServerConfig} + Boot Web/Jackson +
 * Spring AI MCP server 自动配置，不扫描应用包（工具集可控、无文件/进程副作用），
 * DB/Flyway/Quartz 自动配置已排除。全量应用上下文的生产启动路径已由
 * {@link McpFullContextStartupTest} 覆盖（RES-04：修复 HooksConfigSnapshot /
 * ContextAnalyzeService 双构造器缺陷 + ExitPlanModeTool additionalProperties 后，全量
 * @SpringBootTest 可通过）。
 *
 * <p>探针工具（{@link McpProbeTool}）：成功回显 / 权限 deny / 执行失败 / 运行期禁用开关 /
 * SPECIAL_TOOLS 同名（验证过滤），安全驱动各类结果契约，避免真实工具的文件/进程副作用。
 * transport 用 streamable HTTP（application.yml {@code spring.ai.mcp.server.protocol=streamable}
 * → 单端点 /mcp），客户端 {@link HttpClientStreamableHttpTransport}。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = InboundMcpServerTest.MinimalMcpContextConfig.class
)
class InboundMcpServerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private List<Tool> allToolBeans;

    private McpSyncClient client;

    /**
     * 最小上下文：Boot Web/Jackson + Spring AI MCP server 自动配置（@EnableAutoConfiguration
     * 排除 DB 族），仅注册探针工具 + ToolRegistry + 入站 MCP 装配。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        QuartzAutoConfiguration.class,
    })
    @Import(InboundMcpServerConfig.class)
    static class MinimalMcpContextConfig {

        @Bean
        ToolRegistry testToolRegistry(List<Tool> tools) {
            // 与 ToolRegistry 构造器同源：全部 Tool bean → earlyTools（ApplicationReadyEvent 注册）
            return new ToolRegistry(tools);
        }

        @Bean
        Tool mcpEchoProbe() {
            return new McpProbeTool("mcp_echo_probe", "echo desc", "echo prompt", false, false);
        }

        @Bean
        Tool mcpPermDenyProbe() {
            return new McpProbeTool("mcp_perm_deny_probe", "perm desc", null, true, false);
        }

        @Bean
        Tool mcpFailProbe() {
            return new McpProbeTool("mcp_fail_probe", "fail desc", null, false, true);
        }

        @Bean
        Tool mcpToggleProbe() {
            return new McpProbeTool("mcp_toggle_probe", "toggle desc", null, false, false);
        }

        /** 与 SPECIAL_TOOLS 同名 —— 验证 tools/list 过滤掉内部调度工具（不暴露给 MCP client）。 */
        @Bean
        Tool mcpSpecialNamedProbe() {
            return new McpProbeTool("StructuredOutput", "special desc", null, false, false);
        }

        /**
         * S01 (INFRA-B1-2) 装配修复：ToolRegistry 以 {@code @Resource(name="todoTaskTools")}
         * 按名注入本 bean（ToolRegistry.java:60-62，{@code @Lazy}）——最小上下文此前缺该
         * bean → ApplicationReadyEvent init() 触发懒解析 NoSuchBeanDefinitionException →
         * 上下文启动失败（7/7 ERROR）。空 List 走 ToolRegistry.init:126-133 的 isEmpty
         * debug-skip（对齐生产 flag-off 全关 → 无待注册工具），不注册任何工具；
         * bean 类型 {@code List<Tool>} 不会被 {@code @Autowired List<Tool>} 集合注入展开
         * （EV-RG-023 E4）→ 5 探针工具集与 7 断言不变。生产 todoTaskTools @Bean
         * （ToolRegistrationConfig.java:194-195）不受影响。
         */
        @Bean("todoTaskTools")
        List<Tool> todoTaskTools() {
            return List.of();
        }

        /**
         * S06 装配：入站权限门全量管线所需 bean —— 真实 {@link ToolPermissionGate}
         * （PermissionPipeline 无参构造 + mock prompter，经 createSpringBean）+ 空上下文
         * 工厂（对齐 CC getEmptyToolPermissionContext，无 loader、三桶空 →
         * 1c 层工具自决语义不变：echo Allow→执行、perm_deny 走 1c/1d 层 Deny→isError）。
         * [v4 OPD-WF8-02-GS-01] 工厂回归 CC 空上下文，不再合并全量规则。
         */
        @Bean
        ToolPermissionGate inboundPermissionGate() {
            return ToolPermissionGate.createSpringBean(
                new PermissionPipeline(), Mockito.mock(PermissionPrompter.class));
        }

        @Bean
        InboundPermissionContextFactory inboundPermissionContextFactory() {
            return new InboundPermissionContextFactory();
        }
    }

    @BeforeEach
    void connect() {
        HttpClientStreamableHttpTransport transport =
            HttpClientStreamableHttpTransport.builder("http://localhost:" + port + "/mcp").build();
        client = McpClient.sync(transport).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    private Set<String> mcpToolNames() {
        List<McpSchema.Tool> tools = client.listTools().tools();
        return tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
    }

    private McpSchema.Tool mcpTool(String name) {
        return client.listTools().tools().stream()
            .filter(t -> name.equals(t.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("MCP tools/list 未包含工具 " + name));
    }

    // ── 意图 1 · tools/list ────────────────────────────────────────────────

    @Test
    @DisplayName("tools/list 返回 ToolRegistry 已注册工具：isEnabled+SPECIAL_TOOLS 过滤 + description=prompt()??description() + inputSchema")
    void listToolsExposesRegisteredToolSet() {
        Set<String> names = mcpToolNames();

        // 探针工具全部进入快照（enabled 启动期为 true）
        assertThat(names)
            .as("MCP tools/list 必须暴露启动期已注册且启用的工具")
            .contains("mcp_echo_probe", "mcp_perm_deny_probe", "mcp_fail_probe", "mcp_toggle_probe");

        // SPECIAL_TOOLS 同名探针不得暴露（对齐 ToolRegistry.getTools :517-521 过滤）
        assertThat(names)
            .as("SPECIAL_TOOLS（如 StructuredOutput）不得暴露给 MCP client（内部调度工具）")
            .doesNotContain("StructuredOutput");

        // 与 ToolRegistry.getTools(null) 工具名集合一致（快照源 List<Tool> 与注册表同源）
        Set<String> registryNames = toolRegistry.getTools(null).stream()
            .map(Tool::name).collect(Collectors.toSet());
        assertThat(names)
            .as("MCP tools/list 快照必须与 ToolRegistry.getTools(空权限上下文) 对齐（CC mcp.ts:64）")
            .isEqualTo(registryNames);

        // description = prompt() ?? description()（对齐 ToolRegistry.toOpenAiToolsArray:424-428）
        assertThat(mcpTool("mcp_echo_probe").description())
            .as("prompt() 非 null → description 取 prompt()（CC mcp.ts:85-89）")
            .isEqualTo("echo prompt");
        assertThat(mcpTool("mcp_perm_deny_probe").description())
            .as("prompt() null → description 回退 description()（CC mcp.ts:85-89）")
            .isEqualTo("perm desc");

        // inputSchema 携带（对齐 CC mcp.ts:90 inputSchema: zodToJsonSchema(...)）
        assertThat(mcpTool("mcp_echo_probe").inputSchema()).isNotNull();
    }

    // ── 意图 2 · tools/call 成功 ───────────────────────────────────────────

    @Test
    @DisplayName("tools/call 成功路径：调用已注册工具返回 text 内容（不 isError）")
    void callToolSuccessReturnsText() {
        McpSchema.CallToolResult result = client.callTool(
            new McpSchema.CallToolRequest("mcp_echo_probe", Map.of("msg", "hi")));

        assertThat(Boolean.TRUE.equals(result.isError()))
            .as("成功路径不得标 isError（CC mcp.ts:159-168）")
            .isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
            .as("text = 工具结果（CC: typeof finalResult === 'string' ? finalResult : jsonStringify）")
            .isEqualTo("echo:hi");
    }

    // ── 意图 3 · tools/call 错误路径（均 isError:true，不 500）──────────────

    @Test
    @DisplayName("tools/call 未注册工具 → 协议级 JSON-RPC 错误（客户端抛 McpError），不 500（与 CC isError 语义差异已登记）")
    void callUnknownToolReturnsIsError() {
        // SDK 在 ToolCallback 之下处理未注册工具：返回 JSON-RPC error code=-32602
        // （McpAsyncServer.callTool 实证），客户端抛 McpError —— 绝不 HTTP 500。
        // 对齐差异登记：CC mcp.ts:107 throw + catch → isError:true；Java SDK 走协议错误
        // （transport 层差异，isEnabled/权限/执行错误路径仍走 isError:true）。
        assertThatThrownBy(() -> client.callTool(
            new McpSchema.CallToolRequest("no_such_tool_xyz", Map.of())))
            .isInstanceOf(io.modelcontextprotocol.spec.McpError.class)
            .hasMessageContaining("Unknown tool");
    }

    @Test
    @DisplayName("tools/call isEnabled=false → isError:true（运行期禁用，CC mcp.ts:138-140）")
    void callDisabledToolReturnsIsError() {
        McpProbeTool toggle = (McpProbeTool) allToolBeans.stream()
            .filter(t -> "mcp_toggle_probe".equals(t.name()))
            .findFirst().orElseThrow();
        // 启动期 enabled=true 已进 tools/list 快照；运行期翻转禁用
        toggle.setEnabled(false);

        McpSchema.CallToolResult result = client.callTool(
            new McpSchema.CallToolRequest("mcp_toggle_probe", Map.of()));

        assertThat(Boolean.TRUE.equals(result.isError()))
            .as("运行期禁用工具必须返回 isError:true（CC mcp.ts:138-140）")
            .isTrue();
    }

    @Test
    @DisplayName("tools/call 权限 deny → isError:true（checkPermissions 最小权限门，CC hasPermissionsToUseTool）")
    void callPermissionDenyReturnsIsError() {
        McpSchema.CallToolResult result = client.callTool(
            new McpSchema.CallToolRequest("mcp_perm_deny_probe", Map.of()));

        assertThat(Boolean.TRUE.equals(result.isError()))
            .as("权限 deny 必须返回 isError:true（入站非交互最小语义）")
            .isTrue();
    }

    @Test
    @DisplayName("tools/call 执行失败 → isError:true（isToolErrorData 门识别 ToolResult.error，CC catch 路径）")
    void callExecutionFailureReturnsIsError() {
        // [R3 / tool_v3 IMP-C2] AgentToolResult.isError() 已删，错误检测改由
        // InboundMcpToolProvider 经 LlmAgentLoop.isToolErrorData(data) 前缀门推导
        // （McpProbeTool fail 文案 "Error: probe execution failure" 命中 "Error:" 前缀）→
        // 转异常由 Spring AI 包装 isError:true（对齐 CC mcp.ts:170-186 catch 路径）。
        McpSchema.CallToolResult result = client.callTool(
            new McpSchema.CallToolRequest("mcp_fail_probe", Map.of()));

        assertThat(Boolean.TRUE.equals(result.isError()))
            .as("执行失败（ToolResult.error 命中 isToolErrorData 门）必须返回 isError:true（CC mcp.ts:170-186 catch）")
            .isTrue();
    }

    // ── 意图 4 · 路径归属不撞车 ───────────────────────────────────────────

    @Test
    @DisplayName("路径归属：/mcp 由 Spring AI 端点接管（client 连通即证），/api/v1/mcp 仍为 CRUD（不撞车）")
    void mcpPathDoesNotCollideWithCrudController() {
        // client.connect() + initialize() 已成功连通 http://localhost:{port}/mcp →
        // 证明 /mcp 已由 Spring AI 端点接管（原 McpServerEndpoint 骨架已删除）。
        assertThat(client.isInitialized())
            .as("MCP client 必须成功初始化（/mcp 由 Spring AI 端点接管）")
            .isTrue();

        // 出站 CRUD 控制器路径不变，与 /mcp 不撞车（若撞车 context 启动即 ambiguous mapping 失败）
        String controllerPath = McpServerController.class.getAnnotation(RequestMapping.class).value()[0];
        assertThat(controllerPath)
            .as("出站 CRUD 控制器路径必须保持 /api/v1/mcp")
            .isEqualTo("/api/v1/mcp");
        assertThat("/mcp").isNotEqualTo(controllerPath);
    }
}
