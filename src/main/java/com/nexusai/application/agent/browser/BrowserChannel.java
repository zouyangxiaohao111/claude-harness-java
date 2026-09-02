package com.nexusai.application.agent.browser;

import java.util.Map;

/**
 * 浏览器扩展 WebSocket 转发通道 · nexusai-in-chrome 自研扩展 → WebSocket → Java 后端。
 *
 * <p><b>阶段说明</b>：本阶段（工具面）只定义接口 + 预留转发点；真实 WS 实现由后续批次注入
 * （扩展监听原生消息 → Native Host 桥接 → 转发到 Java WebSocket 端点）。未注入实现
 * （{@code channel == null}）时，{@link BrowserMcpTool} 执行 <b>fail loud</b> 返回
 * 「浏览器扩展未连接，请先连接 NexusAI in Chrome 扩展」，不让模型静默失败。
 *
 * <p>对齐 CCB：CC/CCB 的 nexusai-in-chrome = 浏览器自动化 MCP，18 个 BROWSER_TOOLS
 * （{@code @ant/claude-for-chrome-mcp/src/browserTools.ts}）由 Chrome 扩展执行，经 Native Host
 * 桥接。Java Web 架构用「自研 Chrome 扩展 → WebSocket → Java 后端」，本接口即该转发通道的
 * 工具面抽象。
 */
public interface BrowserChannel {

    /**
     * 转发一次浏览器工具调用给 Chrome 扩展（经 WebSocket 通道）。
     *
     * <p>语义对齐 CCB：CC 端 MCP client 收到工具调用后把 {@code name + inputSchema 扁平参数}
     * 转发给扩展（@ant/claude-for-chrome-mcp 的 BROWSER_TOOLS 每个 tool 的 execute 最终落到
     * 扩展侧 action）。Java 端等价：把本次工具入参原样转发，返回扩展执行结果文本。
     *
     * <p><b>多会话并行（browser-mcp-align）</b>：一个扩展连接服务所有会话。{@code sessionId}
     * 由调用方（{@link BrowserMcpTool} 读 {@link com.nexusai.common.RequestContext#sessionId()}）
     * 传入，透传在 {@code tool_call} 消息里 —— 扩展按它定位/创建该会话的 tab 组（对齐 CCB
     * tabs_context_mcp「每个对话创建自己的新 tab」）。结果回传仍按 {@code callId} 匹配，与
     * {@code sessionId} 无关。
     *
     * @param sessionId 当前会话 ID（扩展按它定位/创建该会话的 tab 组）
     * @param tool      工具原名（<b>无</b> {@code mcp__nexusai-in-chrome__} 前缀，如 {@code "read_page"}；
     *                  全名见 {@link BrowserMcpTool#name()}）
     * @param args      工具入参（CCB inputSchema 语义的扁平 Map，key = schema properties 名）
     * @return 扩展执行结果文本（对齐 CCB 工具返回给 MCP client 的 content 文本）
     * @throws Exception 通道不可用 / 扩展未应答 / 转发失败（调用方 {@link BrowserMcpTool}
     *                   catch 后转 {@link com.nexusai.application.agent.tool.ToolResult#error}）
     */
    String send(String sessionId, String tool, Map<String, Object> args) throws Exception;
}
