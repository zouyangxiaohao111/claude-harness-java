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
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code terminal_write} · 往<b>本会话</b>某个终端<b>输入文本</b>（<b>写操作</b>：等于替用户敲键盘，
 * 默认还会回车执行）。
 *
 * <h2>产品口径（用户 2026-10-29 变更后，照做）</h2>
 * 用户已放行「Agent 可以创建/写入终端」。⛔ 本工具<b>不创建终端</b>：会话没有终端时直接返回
 * {@link TerminalToolSupport#NO_TERMINALS_FOR_WRITE_MESSAGE}（提示用户先开，或先调
 * {@code terminal_create}）。执行一次性命令仍建议走 Bash 工具（两条通道分离）。
 *
 * <h2>回车口径（⚠️ 与派单书字面不同，理由如下）</h2>
 * 派单书要求「submit 默认 true = 末尾补 {@code \n}」。本实现补 <b>{@code \r}</b>（按下回车键）：
 * 集成终端的输入通道是 xterm.js {@code onData} 直连 PTY，回车键发的是 {@code \r}；
 * {@link TerminalService#write} 的 Javadoc 明确写「回车是 {@code \r}」，
 * {@code TerminalServiceSmokeTest} 用的也是 {@code "echo 42\r"}。在 Unix 终端里 {@code \n}
 * 靠 ICRNL 在 <b>canonical</b> 模式下才等价于回车，raw 模式的程序（readline/vim/less、
 * 需要 y/n 确认的交互命令）只认 {@code \r} ⇒ 补 {@code \n} 会出现「写进去了但没执行」。
 *
 * <p>若 {@code data} 已以 {@code \r} / {@code \n} 结尾，则不再重复追加（避免双回车产生空命令行）。
 *
 * <h2>权限（⛔ 不允许无条件执行）</h2>
 * {@link #checkPermissions} 返回 {@link PermissionResult.Passthrough} —— 与 Bash 同级：
 * deny 规则拒绝、bypass 放行、whole-tool allow 规则放行、其余兜底转 <b>Ask</b> 弹审批。
 *
 * <h2>开关</h2>
 * {@code nexusai.terminal.agent-write-enabled}（默认 true）<b>AND</b>
 * {@code nexusai.terminal.agent-read-enabled}（默认 true，作总开关）。见 {@link #isEnabled()}。
 */
@Component
public class TerminalWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TerminalWriteTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具名常量（单一来源：{@link ToolNameConstants#TERMINAL_WRITE_TOOL_NAME}）。 */
    public static final String NAME = ToolNameConstants.TERMINAL_WRITE_TOOL_NAME;

    /**
     * submit=true 时追加的「回车」。
     *
     * <p>⛔ 不是 {@code \n}：见类注释「回车口径」—— xterm.js / 本仓 TerminalService 的回车都是
     * {@code \r}，{@code \n} 在 raw 模式行编辑器里不会提交。
     */
    static final String SUBMIT_KEY = "\r";

    /** 日志里命令预览的最大字符数（对齐 BashTool {@code abbreviate(command, 200)}）。 */
    private static final int LOG_PREVIEW_CHARS = 200;

    private final TerminalService terminalService;

    private final boolean writeEnabled;
    private final boolean readEnabled;

    public TerminalWriteTool(TerminalService terminalService,
                             @Value("${nexusai.terminal.agent-write-enabled:true}") boolean writeEnabled,
                             @Value("${nexusai.terminal.agent-read-enabled:true}") boolean readEnabled) {
        if (terminalService == null) {
            throw new IllegalArgumentException("terminalService is null");
        }
        this.terminalService = terminalService;
        this.writeEnabled = writeEnabled;
        this.readEnabled = readEnabled;
        if (writeEnabled && !readEnabled) {
            log.warn("[terminal_write] agent-write-enabled=true 但 agent-read-enabled=false ⇒ "
                + "写工具按总开关语义一并隐藏（写终端却不允许读输出视为配置错误，请同时开启读开关）");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Type text into an integrated terminal of the current session and (by default) press Enter. "
            + "WRITE OPERATION: this EXECUTES COMMANDS in the user's shell and REQUIRES USER APPROVAL "
            + "before running (same approval path as the Bash tool) — never assume it is free to run. "
            + "It does NOT create a terminal: if the session has none, call terminal_create first "
            + "(or ask the user to open one). Use terminal_read to see the resulting output. "
            + "terminalId defaults to the most recently started terminal.";
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

        ObjectNode data = JSON.createObjectNode();
        data.put("type", "string");
        data.put("description", "Text to send to the terminal (verbatim; newlines are allowed).");
        properties.set("data", data);

        ObjectNode submit = JSON.createObjectNode();
        submit.put("type", "boolean");
        submit.put("description", "true (default) = press Enter after the text so the command runs. "
            + "false = send the text without pressing Enter (e.g. answering a running prompt).");
        properties.set("submit", submit);

        root.putArray("required").add("data");
        return root;
    }

    /** 可见性：写开关 AND 读开关（总开关语义；理由同 {@code TerminalCreateTool#isEnabled()}）。 */
    @Override
    public boolean isEnabled() {
        return writeEnabled && readEnabled;
    }

    /** 写 PTY：不可并发（同一终端两次写会交织）。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /** 写操作（对齐 CC {@code isReadOnly=false}）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    /**
     * 需审批：返回 {@link PermissionResult.Passthrough}（⛔ 不是 Allow），逻辑同
     * {@code TerminalCreateTool#checkPermissions}。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("[terminal_write] checkPermissions → Passthrough（需审批，交通用管线；与 Bash 同级）");
        }
        return new PermissionResult.Passthrough(
            "terminal_write 会向终端写入并可能执行命令，需要用户确认（与 Bash 同级审批）",
            new PermissionDecisionReason.Other("terminal_write write operation"),
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
            log.warn("[terminal_write] 无会话上下文，拒绝执行: toolUseId={} sessionId={}", call.id(), sessionId);
            return ToolResult.error(call.id(), TerminalToolSupport.NO_SESSION_MESSAGE);
        }

        String data = call.input().path("data").asText("");
        if (data.isEmpty()) {
            log.info("[terminal_write] 会话 {} data 为空，拒绝执行", sessionId);
            return ToolResult.error(call.id(), "data 不能为空：请给出要写入终端的文本");
        }

        boolean submit = readSubmit(call.input());

        List<TerminalInfo> terminals = terminalService.list(sessionId);
        if (terminals.isEmpty()) {
            log.info("[terminal_write] 会话 {} 还没有终端（本工具不创建终端）", sessionId);
            return ToolResult.error(call.id(), TerminalToolSupport.NO_TERMINALS_FOR_WRITE_MESSAGE);
        }

        String requestedId = call.input().path("terminalId").asText("").trim();
        TerminalInfo target = requestedId.isEmpty()
            ? TerminalToolSupport.mostRecent(terminals)
            : TerminalToolSupport.findById(terminals, requestedId);
        if (target == null) {
            log.info("[terminal_write] 会话 {} 未找到终端 {}（现有 {} 个）",
                sessionId, requestedId, terminals.size());
            return ToolResult.error(call.id(), TerminalToolSupport.NOT_FOUND_PREFIX + requestedId);
        }

        String payload = (submit ? withEnter(data) : data);
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        try {
            terminalService.write(sessionId, target.id(), payload);
        } catch (NotFoundException e) {
            log.info("[terminal_write] 终端 {} 在写入前被关闭", target.id());
            return ToolResult.error(call.id(), TerminalToolSupport.NOT_FOUND_PREFIX + target.id());
        } catch (ConflictException e) {
            // 终端已退出 / PTY 写入失败（TerminalService 已 WARN 过）
            log.info("[terminal_write] 会话 {} 写入终端 {} 冲突: {}", sessionId, target.id(), e.getMessage());
            return ToolResult.error(call.id(), "写入失败：" + e.getMessage());
        } catch (ValidationException e) {
            log.info("[terminal_write] 会话 {} 写入被拒绝（Validation）: {}", sessionId, e.getMessage());
            return ToolResult.error(call.id(), "写入被拒绝：" + e.getMessage());
        }

        // 日志口径：与 BashTool:1071（log.info("BashTool: $ {}", abbreviate(command, 200))）一致 ——
        //   命令原文进 info 但截断到 200 字符、换行转义。⚠️ 这意味着最多 200 字符的敏感内容可能进日志；
        //   与 Bash 同级是本批的用户口径（「与既有惯例保持一致」）。
        log.info("[terminal_write] 已写入终端: session={} terminal={} bytes={} submit={} data={}",
            sessionId, target.id(), bytes, submit, TerminalToolSupport.previewForLog(data, LOG_PREVIEW_CHARS));
        return ToolResult.success(call.id(), "已写入 " + bytes + " 字节到终端 " + target.id());
    }

    /** {@code submit} 缺省 = true；显式 false 才不回车（非布尔值回落 true，与 schema 默认一致）。 */
    private static boolean readSubmit(JsonNode input) {
        if (input == null) {
            return true;
        }
        JsonNode node = input.get("submit");
        if (node == null || node.isNull()) {
            return true;
        }
        return node.asBoolean(true);
    }

    /** 补回车；{@code data} 已带 {@code \r}/\n 结尾则不重复追加。 */
    private static String withEnter(String data) {
        if (data.endsWith("\r") || data.endsWith("\n")) {
            return data;
        }
        return data + SUBMIT_KEY;
    }
}
