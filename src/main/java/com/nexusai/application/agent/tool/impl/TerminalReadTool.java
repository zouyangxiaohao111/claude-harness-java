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
import com.nexusai.infra.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code terminal_read} · 读<b>本会话</b>某个已开终端的最近输出（<b>只读</b>，已去 ANSI）。
 *
 * <h2>产品口径（用户已裁定，⛔ 不要扩权）</h2>
 * 对齐 Codex 桌面版 {@code read_thread_terminal}：<b>只能读，不能写</b>。终端只由用户手动创建；
 * Agent <b>不创建、不写入、不关闭</b>终端。执行命令继续走 Bash 工具，<b>不进</b>集成终端。
 * 故本类<b>只调用</b> {@link TerminalService#list} + {@link TerminalService#snapshot}，
 * 绝不触碰 create / write / resize / close（两条通道分离）。
 *
 * <h2>入参</h2>
 * <ul>
 *   <li>{@code terminalId?} — 缺省（或空白）= 本会话<b>最近启动</b>的终端
 *       （{@link TerminalService#list} 按启动时间升序，取最后一条）。</li>
 *   <li>{@code lines?} — 默认 {@value TerminalToolSupport#DEFAULT_LINES}，
 *       上限 {@value TerminalToolSupport#MAX_LINES}（超出钳到上限）。</li>
 * </ul>
 *
 * <h2>输出</h2>
 * <pre>
 * terminal=&lt;id&gt; shell=&lt;shell 短名&gt; alive=&lt;true|false&gt; cwd=&lt;cwd&gt;
 * &lt;最后 N 行（已去 ANSI）&gt;
 * </pre>
 * 终端不存在 ⇒ {@code 未找到该终端：<id>}；会话无终端 ⇒
 * {@link TerminalToolSupport#NO_TERMINALS_MESSAGE}（与 {@code terminal_list} 逐字同源）。
 *
 * <p>进程自然退出后缓冲仍保留（{@code alive=false}），故「构建为什么失败」仍读得到 ——
 * 这是 {@link TerminalService} 的既有口径，本类不改。
 */
@Component
public class TerminalReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TerminalReadTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具名常量（单一来源：{@link ToolNameConstants#TERMINAL_READ_TOOL_NAME}）。 */
    public static final String NAME = ToolNameConstants.TERMINAL_READ_TOOL_NAME;

    private final TerminalService terminalService;

    /** 只读终端工具总开关（默认开）；关闭时两个终端工具均不出现在工具列表里。 */
    private final boolean enabled;

    public TerminalReadTool(TerminalService terminalService,
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
        return "Read the recent output of an integrated terminal opened by the user in the current "
            + "session (READ-ONLY, ANSI stripped). This tool NEVER creates, writes to, or closes a "
            + "terminal — terminals are created only by the user, and the agent cannot create one; "
            + "use the Bash tool to run commands instead. Use it to check dev-server or build status. "
            + "terminalId defaults to the most recently started terminal; lines defaults to 100 "
            + "(max 500).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");

        ObjectNode terminalId = JSON.createObjectNode();
        terminalId.put("type", "string");
        terminalId.put("description", "Terminal id from terminal_list. "
            + "Defaults to the most recently started terminal in this session.");
        properties.set("terminalId", terminalId);

        ObjectNode lines = JSON.createObjectNode();
        lines.put("type", "integer");
        lines.put("minimum", 1);
        lines.put("maximum", TerminalToolSupport.MAX_LINES);
        lines.put("description", "How many trailing lines to return. Default "
            + TerminalToolSupport.DEFAULT_LINES + ", max " + TerminalToolSupport.MAX_LINES + ".");
        properties.set("lines", lines);

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

    /** 读取操作（对齐 CC {@code isSearchOrReadCommand} 的 {@code isRead} 语义，供 UI 折叠）。 */
    @Override
    public SearchReadKind searchReadKind(JsonNode input) {
        return SearchReadKind.IS_READ;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;
        if (!TerminalToolSupport.hasSession(sessionId)) {
            log.warn("[terminal_read] 无会话上下文，拒绝执行: toolUseId={} sessionId={}", call.id(), sessionId);
            return ToolResult.error(call.id(), TerminalToolSupport.NO_SESSION_MESSAGE);
        }

        List<TerminalInfo> terminals = terminalService.list(sessionId);
        if (terminals.isEmpty()) {
            log.info("[terminal_read] 会话 {} 还没有终端", sessionId);
            return ToolResult.success(call.id(), TerminalToolSupport.NO_TERMINALS_MESSAGE);
        }

        String requestedId = call.input().path("terminalId").asText("").trim();
        TerminalInfo target;
        if (requestedId.isEmpty()) {
            // list() 按启动时间升序 ⇒ 最后一条 = 最近启动
            target = TerminalToolSupport.mostRecent(terminals);
        } else {
            target = TerminalToolSupport.findById(terminals, requestedId);
            if (target == null) {
                log.info("[terminal_read] 会话 {} 未找到终端 {}（现有 {} 个）",
                    sessionId, requestedId, terminals.size());
                return ToolResult.error(call.id(), TerminalToolSupport.NOT_FOUND_PREFIX + requestedId);
            }
        }

        int lines = TerminalToolSupport.clampLines(readLines(call.input()));
        String body;
        try {
            body = terminalService.snapshot(sessionId, target.id(), lines);
        } catch (NotFoundException e) {
            // list() 与 snapshot() 之间被显式 close 的竞态：同上文案，fail-loud 不抛
            log.info("[terminal_read] 终端 {} 在读取前被关闭", target.id());
            return ToolResult.error(call.id(), TerminalToolSupport.NOT_FOUND_PREFIX + target.id());
        }

        String text = body == null || body.isEmpty()
            ? TerminalToolSupport.metaLine(target)
            : TerminalToolSupport.metaLine(target) + "\n" + body;
        log.info("[terminal_read] 会话 {} 读取终端 {}（请求 {} 行，alive={}，返回 {} 字符）",
            sessionId, target.id(), lines, target.alive(), text.length());
        return ToolResult.success(call.id(), text);
    }

    /**
     * 取 {@code lines} 入参：缺失 / null ⇒ {@code null}（交给 {@link TerminalToolSupport#clampLines}
     * 回落默认值）；非数字 ⇒ {@code asInt(0)} = 0 ⇒ 同样回落默认值（不报错，不缺省成 0 行）。
     */
    private static Integer readLines(JsonNode input) {
        if (input == null) {
            return null;
        }
        JsonNode node = input.get("lines");
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asInt(0);
    }
}
