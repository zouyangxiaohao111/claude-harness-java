package com.nexusai.application.agent.browser;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * nexusai-in-chrome 浏览器工具注册配置 · 18 个 BROWSER_TOOLS 移植为 Java Tool。
 *
 * <p><b>注册通道</b>：本 {@code @Bean} 返回类型是 {@code List<Tool>}（非 {@code Tool}），Spring
 * 集合注入 {@code @Autowired List<Tool>} 不展开其元素 → 由 {@link ToolRegistry} 以
 * {@code @Resource(name = "browserMcpTools")} 显式注入并在 {@code init()} 内
 * {@link ToolRegistry#registerAll(List)} 逐个注册（对齐 CC tools.ts getAllBaseTools 扁平工具数组
 * 语义；B5 OPD-TOOL-02-1 todoTaskTools 同款通道）。
 *
 * <p>注册后模型 tools 列表可见 {@code mcp__nexusai-in-chrome__*} 18 工具（ToolRegistry 已注册 →
 * {@code toOpenAiToolsArray} 逐工具发射）。{@link BrowserChannel} 已由
 * {@link BrowserWsChannel}（WebSocket 通信桥，端点 {@code /ws/browser}）注入 → execute 真实
 * 转发给 Chrome 扩展执行；bean 缺失（部分测试上下文未装配 WS 配置）→ null → execute fail loud。
 */
@Configuration
public class BrowserMcpToolConfig {

    private static final Logger log = LoggerFactory.getLogger(BrowserMcpToolConfig.class);

    /**
     * @param browserChannel 转发通道；已由 {@link BrowserWsChannel} 注入（真实 WS 转发）。
     *                       {@code required=false} 容错：部分测试上下文无该 bean → null → execute
     *                       fail loud（向后兼容）。
     */
    @Bean
    public List<Tool> browserMcpTools(@Autowired(required = false) BrowserChannel browserChannel) {
        List<Tool> tools = BrowserToolRegistry.createTools(browserChannel);
        log.info("注册 {} 个 nexusai-in-chrome 浏览器工具 → ToolRegistry（BrowserChannel 注入={}）",
            tools.size(), browserChannel != null);
        return tools;
    }
}
