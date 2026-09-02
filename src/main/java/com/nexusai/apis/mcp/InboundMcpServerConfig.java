package com.nexusai.apis.mcp;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;

/**
 * 入站 MCP server 工具注册配置 · 对齐 CC {@code Open-ClaudeCode/src/entrypoints/mcp.ts:59-97}
 * ListToolsRequestSchema 的 {@code getTools(getEmptyToolPermissionContext())} 工具集。
 *
 * <h2>WHY 用 {@code List<Tool>} bean 而非运行期 {@link ToolRegistry}</h2>
 * <p>Spring AI 的 MCP server 自动配置（ToolCallbackConverterAutoConfiguration →
 * McpToolUtils.toSyncToolSpecification）在 <b>context refresh</b> 阶段收集
 * {@code ToolCallback} 并<b>快照</b>其 ToolDefinition（name/description/inputSchema）
 * 为 MCP SDK {@code Tool}；而 {@link ToolRegistry#init()} 在
 * {@code ApplicationReadyEvent} 才把工具拷入注册表 —— 若在此处读注册表将得到空集。
 * 故本 bean 直接注入 {@code List<Tool>}（与 ToolRegistry 构造器同源，全部 Tool bean），
 * 在 refresh 期快照工具定义（对齐 CC 启动期快照 + mcp.ts:62 TODO 只暴露 builtin 工具）。
 *
 * <p>运行期 tools/call 仍经 {@link InboundMcpToolProvider} 内的 {@code @Lazy ToolRegistry}
 * 重新解析（isEnabled/权限/替换语义运行时准确）。
 *
 * <h2>过滤（对齐 {@code ToolRegistry.getTools(permCtx)}）</h2>
 * <ul>
 *   <li><b>SPECIAL_TOOLS</b>（ListMcpResources/ReadMcpResource/StructuredOutput）——
 *       内部 dispatch 工具不暴露（对齐 getTools 默认过滤，工具集 = LLM-facing 子集）。</li>
 *   <li><b>isEnabled</b> —— 启动时禁用的工具不进入快照（对齐 CC getTools
 *       {@code filter(t => t.isEnabled())} 启动期语义）。</li>
 * </ul>
 *
 * @see InboundMcpToolProvider
 */
@Configuration
public class InboundMcpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(InboundMcpServerConfig.class);

    /**
     * 注册 ToolCallback bean 集合 → Spring AI WebMvc MCP server 自动配置收集。
     *
     * @param toolBeans    全部 Tool bean（与 ToolRegistry 构造器同源，refresh 期已填充）
     * @param toolRegistry 运行期注册表（@Lazy 懒代理，供 call 时 findToolByName 重解析）
     * @param permissionGate 全量权限管线门（CC hasPermissionsToUseTool，permissions.ts:473-956）
     * @param permissionContextFactory 入站权限上下文工厂（CC getEmptyToolPermissionContext，
     *                                 mcp.ts:102；每调用新鲜构建 + settings 规则合并）
     * @return 每工具一个的 {@link InboundMcpToolProvider}（SPECIAL_TOOLS/isEnabled 已过滤）
     */
    @Bean
    public List<ToolCallback> inboundMcpToolCallbacks(
            List<Tool> toolBeans,
            @Lazy ToolRegistry toolRegistry,
            ToolPermissionGate permissionGate,
            InboundPermissionContextFactory permissionContextFactory) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : toolBeans) {
            if (tool == null) {
                continue;
            }
            // SPECIAL_TOOLS 过滤（对齐 ToolRegistry.getTools :517-521）
            if (ToolNameConstants.SPECIAL_TOOLS.contains(tool.name())) {
                if (log.isDebugEnabled()) {
                    log.debug("InboundMcpServerConfig: 跳过 SPECIAL_TOOL '{}'（不暴露给 MCP client）",
                        tool.name());
                }
                continue;
            }
            // isEnabled 过滤（对齐 CC getTools filter(t => t.isEnabled()) 启动期语义）
            if (!tool.isEnabled()) {
                if (log.isDebugEnabled()) {
                    log.debug("InboundMcpServerConfig: 跳过未启用工具 '{}'（启动期 isEnabled=false）",
                        tool.name());
                }
                continue;
            }
            callbacks.add(new InboundMcpToolProvider(
                tool, toolRegistry, permissionGate, permissionContextFactory));
        }
        log.info("InboundMcpServerConfig: 注册 {} 个入站 MCP 工具（CC entrypoints/mcp.ts tools/list 快照, "
                + "SPECIAL_TOOLS/isEnabled 过滤后, 共 {} 个 Tool bean）",
            callbacks.size(), toolBeans.size());
        return callbacks;
    }
}
