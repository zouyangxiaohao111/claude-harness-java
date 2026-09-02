package com.nexusai.apis.mcp;

import com.nexusai.NexusAiApplication;
import com.nexusai.application.agent.permission.hook.HooksConfigSnapshot;
import com.nexusai.application.agent.context.ContextAnalyzeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-04 · 全量 @SpringBootTest 生产启动验证.
 *
 * <p><b>WHY (意图验证)</b>: 修双构造器 @Component/@Service 缺陷（HooksConfigSnapshot /
 * ContextAnalyzeService 无 @Autowired + 无默认构造器 → Spring 多构造器歧义/容器依赖声明序），
 * 并验证 Spring AI 入站 MCP server（spring-ai-starter-mcp-server-webmvc）在<b>全量应用上下文</b>
 * 下可启动（对齐 CC entrypoints/mcp.ts startMCPServer：ToolRegistry 工具经 InboundMcpToolProvider
 * 暴露 tools/list + tools/call）。{@link InboundMcpServerTest} 用最小上下文规避缺陷 bean——
 * 本测试补全量启动路径，作为其生产形态覆盖。
 */
@SpringBootTest(classes = NexusAiApplication.class)
class McpFullContextStartupTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("全量上下文可启动：修复后的双构造器 bean 装配成功")
    void contextLoadsWithFixedDualConstructors() {
        // WHY: HooksConfigSnapshot / ContextAnalyzeService 曾因双构造器无 @Autowired + 无默认
        // 构造器导致全量 @SpringBootTest 无法启动（InboundMcpServerTest javadoc :53-56 登记）。
        // RES-04 加 @Autowired 后容器必须能实例化二者（fail-loud：装配失败即本测试红）。
        assertThat(ctx.getBean(HooksConfigSnapshot.class))
            .as("HooksConfigSnapshot 双构造器修复后必须可注入")
            .isNotNull();
        assertThat(ctx.getBean(ContextAnalyzeService.class))
            .as("ContextAnalyzeService 双构造器修复后必须可注入")
            .isNotNull();
    }

    @Test
    @DisplayName("Spring AI 入站 MCP server 自动装配 bean 就绪")
    void springAiMcpServerBeansReady() {
        // WHY: 生产 /mcp 路径由 spring-ai-starter-mcp-server-webmvc 自动配置（application.yml
        // spring.ai.mcp.server.enabled=true）。InboundMcpServerConfig 是 ToolRegistry → MCP 工具
        // 暴露的桥（对齐 CC mcp.ts startMCPServer）。全量上下文必须含该配置 + Spring AI MCP
        // server 自动配置（ToolRegistry 在册工具可被 tools/list 暴露）。
        assertThat(ctx.getBean(InboundMcpServerConfig.class))
            .as("InboundMcpServerConfig（入站 MCP 桥）必须装配")
            .isNotNull();
        assertThat(ctx.containsBean(
            "org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration"))
            .as("Spring AI MCP server StreamableHttp WebMvc 自动配置必须注册（/mcp 端点由它接管，"
                + "对齐 CC entrypoints/mcp.ts startMCPServer）")
            .isTrue();
        assertThat(ctx.containsBean("inboundMcpToolCallbacks"))
            .as("ToolRegistry 工具必须经 inboundMcpToolCallbacks 暴露给 Spring AI MCP server"
                + "（tools/list + tools/call，对齐 CC mcp.ts:59-97/:99-188）")
            .isTrue();
    }
}
