package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.terminal.TerminalInfo;
import com.nexusai.application.terminal.TerminalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code terminal_list} · 列出<b>本会话</b>已开的集成终端（<b>只读</b>）。
 *
 * <h2>产品口径（用户已裁定，⛔ 不要扩权）</h2>
 * 对齐 Codex 桌面版的终端设计：终端<b>只由用户手动创建</b>；Agent <b>不创建、不写入</b>终端。
 * Agent 能做的只有「读」—— 本工具列清单，{@link TerminalReadTool} 读输出。执行命令继续走
 * Bash 工具，<b>不进</b>集成终端（两条通道分离）。故本类<b>只调用</b>
 * {@link TerminalService#list}，绝不触碰 create / write / resize / close。
 *
 * <h2>输出（纯文本，每条一行）</h2>
 * <pre>
 * &lt;id&gt; | &lt;shell 短名&gt; | 运行中|已退出 | cwd=&lt;cwd&gt; | 启动于 &lt;ISO-8601&gt;
 * </pre>
 * 无终端 ⇒ {@link TerminalToolSupport#NO_TERMINALS_MESSAGE}（与 {@code terminal_read} 逐字同源）。
 *
 * <h2>注册与权限</h2>
 * <ul>
 *   <li>注册：{@code @Component} + {@code ToolRegistry} 构造器 {@code @Autowired List<Tool>}
 *       （与 GlobTool / ReadFileTool 同一通道，无额外注册点）。</li>
 *   <li>权限：不 override {@code checkPermissions} ⇒ 继承 {@link Tool} 默认 {@code Allow}
 *       （对齐 Read/Glob 的「只读不审批」）；{@code isReadOnly=true} 声明语义。</li>
 *   <li>开关：{@code nexusai.terminal.agent-read-enabled}（默认 true）为 false 时
 *       {@link #isEnabled()} 返回 false ⇒ 不进入 LLM 工具列表（{@code ToolRegistry#getTools}
 *       / {@code toOpenAiToolsArray} 均按 isEnabled 过滤）。</li>
 * </ul>
 */
@Component
public class TerminalListTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TerminalListTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具名常量（单一来源：{@link ToolNameConstants#TERMINAL_LIST_TOOL_NAME}）。 */
    public static final String NAME = ToolNameConstants.TERMINAL_LIST_TOOL_NAME;

    private final TerminalService terminalService;

    /** 只读终端工具总开关（默认开）；关闭时两个终端工具均不出现在工具列表里。 */
    private final boolean enabled;

    public TerminalListTool(TerminalService terminalService,
                            @Value("${nexusai.terminal.agent-read-enabled:true}") boolean enabled) {
        if (terminalService == null) {
            throw new IllegalArgumentException("terminalService is null");
        }
        this.terminalService = terminalService;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "List the integrated terminals opened by the user in the current session. "
            + "READ-ONLY: this tool NEVER creates, writes to, or closes a terminal — terminals "
            + "are created only by the user, and the agent cannot create one. "
            + "Use it to see which terminals exist (id, shell, running/exited, cwd, start time) "
            + "before reading one with terminal_read; use the Bash tool to run commands instead.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** 纯读内存快照，可与其他只读工具并发。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 只读语义声明（权限链的 allow 由 {@link Tool#checkPermissions} 默认实现给出）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        // 无 ctx（旧 API / 无会话直调）⇒ 没有 sessionId，无法查终端（工具按会话隔离）
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;
        if (!TerminalToolSupport.hasSession(sessionId)) {
            log.warn("[terminal_list] 无会话上下文，拒绝执行: toolUseId={} sessionId={}", call.id(), sessionId);
            return ToolResult.error(call.id(), TerminalToolSupport.NO_SESSION_MESSAGE);
        }

        List<TerminalInfo> terminals = terminalService.list(sessionId);
        if (terminals.isEmpty()) {
            log.info("[terminal_list] 会话 {} 还没有终端", sessionId);
            return ToolResult.success(call.id(), TerminalToolSupport.NO_TERMINALS_MESSAGE);
        }

        StringBuilder sb = new StringBuilder();
        for (TerminalInfo info : terminals) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(TerminalToolSupport.listLine(info));
        }
        log.info("[terminal_list] 会话 {} 终端数={}（只读列表，未创建/未写入）", sessionId, terminals.size());
        return ToolResult.success(call.id(), sb.toString());
    }
}
