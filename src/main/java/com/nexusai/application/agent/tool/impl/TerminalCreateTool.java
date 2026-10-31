package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
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
 * {@code terminal_create} · 在<b>本会话</b>新建一个集成终端（<b>写操作</b>：会起一个真实 shell 进程）。
 *
 * <h2>产品口径（用户 2026-10-29 变更后，照做）</h2>
 * 用户已放行「Agent 可以创建/写入终端」（此前只允许读）。本类只做「创建」，
 * <b>cwd 由后端按会话解析</b>（{@link TerminalService#create} → 会话项目根，解析失败回落
 * {@code user.home} 并 WARN）—— ⛔ 工具<b>不接受</b> cwd 入参，避免模型把 shell 起在任意目录。
 *
 * <h2>权限（⛔ 不允许无条件执行）</h2>
 * {@link #checkPermissions} 返回 {@link PermissionResult.Passthrough}（<b>不是</b> Allow）——
 * 与 Bash 同一层语义：交通用管线，
 * <ul>
 *   <li>deny 规则（1a）→ 拒绝；</li>
 *   <li>bypass 模式（2a）→ 放行（用户已在全局关掉弹窗，对齐 Bash）；</li>
 *   <li>whole-tool allow 规则（2b）→ 放行（用户点过「始终允许」）；</li>
 *   <li>其余 → 兜底层（3）转 <b>Ask</b>，弹审批框由用户确认。</li>
 * </ul>
 * 即「需审批」由既有 10 层管线判定，⛔ 本类不自己判、不绕管线。
 *
 * <h2>开关</h2>
 * {@code nexusai.terminal.agent-write-enabled}（默认 true）<b>AND</b>
 * {@code nexusai.terminal.agent-read-enabled}（默认 true）。见 {@link #isEnabled()} 的说明。
 */
@Component
public class TerminalCreateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TerminalCreateTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具名常量（单一来源：{@link ToolNameConstants#TERMINAL_CREATE_TOOL_NAME}）。 */
    public static final String NAME = ToolNameConstants.TERMINAL_CREATE_TOOL_NAME;

    private final TerminalService terminalService;

    /** 写工具开关 + 读工具开关（总开关语义：读关掉 ⇒ 写也一并不可见）。 */
    private final boolean writeEnabled;
    private final boolean readEnabled;

    public TerminalCreateTool(TerminalService terminalService,
                              @Value("${nexusai.terminal.agent-write-enabled:true}") boolean writeEnabled,
                              @Value("${nexusai.terminal.agent-read-enabled:true}") boolean readEnabled) {
        if (terminalService == null) {
            throw new IllegalArgumentException("terminalService is null");
        }
        this.terminalService = terminalService;
        this.writeEnabled = writeEnabled;
        this.readEnabled = readEnabled;
        if (writeEnabled && !readEnabled) {
            log.warn("[terminal_create] agent-write-enabled=true 但 agent-read-enabled=false ⇒ "
                + "写工具按总开关语义一并隐藏（写终端却不允许读输出视为配置错误，请同时开启读开关）");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Create a NEW integrated terminal (spawns a real shell process) for the current session. "
            + "WRITE OPERATION: it starts a process and REQUIRES USER APPROVAL before running "
            + "(same approval path as the Bash tool). The working directory is resolved by the backend "
            + "from the session's project root — you cannot choose it. Use terminal_write to type into "
            + "the terminal and terminal_read to see its output.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");

        ObjectNode shell = JSON.createObjectNode();
        shell.put("type", "string");
        shell.put("description", "Shell to launch (must be on the backend whitelist). "
            + "Omit to use the backend's auto-detected default shell.");
        properties.set("shell", shell);

        ObjectNode cols = JSON.createObjectNode();
        cols.put("type", "integer");
        cols.put("minimum", 1);
        cols.put("maximum", TerminalService.MAX_COLS);
        cols.put("description", "Initial columns (default " + TerminalService.DEFAULT_COLS + ").");
        properties.set("cols", cols);

        ObjectNode rows = JSON.createObjectNode();
        rows.put("type", "integer");
        rows.put("minimum", 1);
        rows.put("maximum", TerminalService.MAX_ROWS);
        rows.put("description", "Initial rows (default " + TerminalService.DEFAULT_ROWS + ").");
        properties.set("rows", rows);

        return root;
    }

    /**
     * 可见性：写开关 AND 读开关（<b>总开关语义</b>，用户二选一后由本类选定并在此写明）。
     *
     * <p><b>WHY 不是两者独立</b>：只关读、留着写，等于「允许 Agent 往终端打字但禁止它看结果」——
     * 一旦写出去的是交互式命令（需要回答 y/n 或密码），Agent 既看不到状态也无法纠错，
     * 比两者全关更危险。故读开关=总开关：关掉即四个终端工具全部隐藏；
     * 写开关只用于「保留只读、禁止写」这一档。构造期若配成 write=true/read=false 会打 WARN。
     */
    @Override
    public boolean isEnabled() {
        return writeEnabled && readEnabled;
    }

    /** 起进程 = 有副作用，不并发。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /** 写操作（对齐 CC {@code isReadOnly=false}）；审批由管线负责，不在这里给自己发 allow。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    /**
     * 需审批：返回 {@link PermissionResult.Passthrough}，交 10 层管线（deny / bypass / allow 规则 / 兜底 ask）。
     *
     * <p>⛔ 不要改成 {@code Allow}（那会无条件执行）；⛔ 也不要 {@code Ask}（会绕过用户的
     * 「始终允许」规则与 bypass 模式，比 Bash 更严）。Passthrough 是 Bash 自身的写法
     * （{@code BashTool:3732}），本工具与它同级。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("[terminal_create] checkPermissions → Passthrough（需审批，交通用管线；与 Bash 同级）");
        }
        return new PermissionResult.Passthrough(
            "terminal_create 会启动一个真实 shell 进程，需要用户确认（与 Bash 同级审批）",
            new PermissionDecisionReason.Other("terminal_create write operation"),
            List.of(),
            null,
            null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;
        if (!TerminalToolSupport.hasSession(sessionId)) {
            log.warn("[terminal_create] 无会话上下文，拒绝执行: toolUseId={} sessionId={}", call.id(), sessionId);
            return ToolResult.error(call.id(), TerminalToolSupport.NO_SESSION_MESSAGE);
        }

        String shell = call.input().path("shell").asText("").trim();
        if (shell.isEmpty()) {
            shell = null;   // null ⇒ TerminalService 用后端探测的默认 shell
        }
        int cols = readInt(call.input(), "cols");
        int rows = readInt(call.input(), "rows");

        TerminalInfo info;
        try {
            info = terminalService.create(sessionId, shell, cols, rows);
        } catch (com.nexusai.infra.exception.ValidationException e) {
            // shell 不在白名单 / 存活终端数超限 —— 给模型可自纠的原文
            log.info("[terminal_create] 会话 {} 创建被拒绝（Validation）: {}", sessionId, e.getMessage());
            return ToolResult.error(call.id(), "创建终端被拒绝：" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("[terminal_create] 会话 {} 创建失败: shell={} {}", sessionId, shell, e.toString());
            return ToolResult.error(call.id(), "创建终端失败：" + e.getMessage());
        }

        log.info("[terminal_create] 已创建终端: session={} terminal={} shell={} cwd={} alive={}（写操作，已过权限审批）",
            sessionId, info.id(), info.shell(), info.cwd(), info.alive());
        return ToolResult.success(call.id(), "已创建终端 id=" + info.id()
            + " shell=" + TerminalToolSupport.shellShortName(info.shell())
            + " cwd=" + info.cwd());
    }

    /** 缺省 / 非法 ⇒ 0（{@link TerminalService} 内部按 {@code <=0} 回落默认尺寸）。 */
    private static int readInt(JsonNode input, String field) {
        if (input == null) {
            return 0;
        }
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) {
            return 0;
        }
        return node.asInt(0);
    }
}
