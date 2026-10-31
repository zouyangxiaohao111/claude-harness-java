package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.tool.Tool;

import java.util.List;

/**
 * 启动期内建 MCP server 的工具提供者 · 由 {@code ToolRegistry.init()}（ApplicationReadyEvent）调用。
 *
 * <p><b>WHY 需要这个接口</b>：内建 MCP server（computer-use）的工具必须<b>在 builtin 之后</b>
 * 注册进 {@link com.nexusai.application.agent.tool.ToolRegistry}，
 * 以保持本仓的「<b>builtin 前缀 + MCP 后缀</b>」分区不变式（S-3，对齐 CC
 * {@code tools.ts:345-367} {@code assembleToolPool(permissionContext, mcpTools)}）。
 * <br>而 {@code ToolRegistry.init()} 是 ApplicationReadyEvent 钩子 ⇒ 内建 server 的装配
 * 必须发生在<b>同一个钩子里、且在 builtin 注册之后</b> ⇒ 用本接口把「装配动作」
 * 交给 server 侧自己实现（避免 tool 包反向依赖 computeruse 包）。
 *
 * <p><b>契约</b>：实现方负责完成「真协议往返装配」（{@code initialize} + {@code tools/list}）
 * 并把产出的 MCP 工具<b>注册进 ToolRegistry</b>（{@link McpToolPool#assembleToolPool}
 * 内部已注册）；返回值仅供观测与日志。
 */
public interface McpServerToolProvider {

    /**
     * 装配该内建 MCP server 的工具（真 MCP 协议往返）并注册进 ToolRegistry。
     *
     * @return 已注册的 MCP 工具列表（开关关闭 / 未接线 ⇒ 空列表）
     */
    List<Tool> assembleMcpTools();
}
