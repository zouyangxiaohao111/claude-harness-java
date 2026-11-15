package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.MonitorMcpTaskRunner;
import com.nexusai.application.agent.tasks.MonitorTaskRunner;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * Monitor 工具 · 对齐 claude.exe v2.1.325 的 MonitorTool（off≈164,355,xxx）。
 *
 * <p><b>CC 真契约</b>（本次用 REA 从二进制挖出，取代此前的推断实现）：
 * <ul>
 *   <li>{@code command} 与 {@code ws} <b>二选一</b>（原文 {@code exactly one of command or ws}）；</li>
 *   <li>{@code command}：<i>Shell command or script. Each stdout line is an event; exit ends the watch.</i></li>
 *   <li>{@code description}：给人看的监控描述（任务面板与通知摘要）；</li>
 *   <li>{@code timeout_ms}：到点自动结束；{@code allowed_domains}：命令模式域名白名单。</li>
 * </ul>
 *
 * <p>工具说明原文：<i>When you are about to check a log, process or job again and again to
 * catch each new event, use it and not repeated Bash calls or sleeps.</i>
 *
 * <p>门控：{@code MONITOR_TOOL} feature flag（默认关，对齐 CC flag-off null）。
 */
public class MonitorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MonitorTool.class);

    public static final String NAME = ToolNameConstants.MONITOR_TOOL_NAME;

    private final FeatureFlags featureFlags;
    /** 历史 MCP 状态监控执行器（保留构造兼容；command/ws 模式不用它）。 */
    private final MonitorMcpTaskRunner monitorMcpTaskRunner;
    /** CC 契约的命令/连接执行器。 */
    private final MonitorTaskRunner monitorTaskRunner;
    /** 配置强制开启（nexusai.feature.monitor-tool=true）· 与 CC 的实验 flag 并存。 */
    private final boolean forceEnabled;
    /** 权限判定复用 Bash 链（setter 注入，避免改注册方法签名）。 */
    private BashTool bashTool;

    public MonitorTool() {
        this(FeatureFlags.ALL_DISABLED, null, null, false);
    }

    public MonitorTool(FeatureFlags featureFlags) {
        this(featureFlags, null, null, false);
    }

    /** 旧构造器（MCP 模式）兼容。 */
    public MonitorTool(FeatureFlags featureFlags, MonitorMcpTaskRunner monitorMcpTaskRunner) {
        this(featureFlags, monitorMcpTaskRunner, null, false);
    }

    public MonitorTool(FeatureFlags featureFlags, MonitorMcpTaskRunner monitorMcpTaskRunner,
                       MonitorTaskRunner monitorTaskRunner) {
        this(featureFlags, monitorMcpTaskRunner, monitorTaskRunner, false);
    }

    public MonitorTool(FeatureFlags featureFlags, MonitorMcpTaskRunner monitorMcpTaskRunner,
                       MonitorTaskRunner monitorTaskRunner, boolean forceEnabled) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
        this.monitorMcpTaskRunner = monitorMcpTaskRunner;
        this.monitorTaskRunner = monitorTaskRunner;
        this.forceEnabled = forceEnabled;
    }

    @Autowired(required = false)
    public void setBashTool(BashTool bashTool) {
        this.bashTool = bashTool;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Watch, monitor, or keep an eye on a process/log/command or WebSocket — "
            + "stream each stdout line as a live notification. "
            + "Use it when you are about to check a log, process or job again and again to "
            + "catch each new event, instead of repeated Bash calls or sleeps.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("command").put("type", "string").put("description",
            "Shell command or script. Each stdout line is an event; exit ends the watch.");
        props.putObject("ws").put("type", "string").put("description",
            "WebSocket URL (ws:// or wss://). Each message is an event.");
        props.putObject("description").put("type", "string").put("description",
            "Short human-readable description of what you are monitoring (shown in notifications).");
        props.putObject("timeout_ms").put("type", "integer").put("description",
            "How long the monitor may run before it is stopped.");
        ObjectNode domains = props.putObject("allowed_domains");
        domains.put("type", "array");
        domains.putObject("items").put("type", "string");
        domains.put("description", "allowed_domains applies only to a command monitor");
        schema.putArray("required").add("description");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        return forceEnabled || featureFlags.monitorTool();
    }

    /**
     * 权限 · command 模式复用 Bash 权限链（对齐 CC bashToolHasPermission 结构）；
     * ws 模式由 execute 做协议与域名校验。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        String command = input != null ? input.path("command").asText("") : "";
        if (!command.isBlank() && bashTool != null) {
            return bashTool.checkPermissions(input, ctx);
        }
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("monitor_allow"),
            null, false, null, List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        if (monitorTaskRunner == null) {
            return ToolResult.error(call.id(),
                "Monitor 工具不可用：MonitorTaskRunner 未装配（MONITOR_TOOL 需 task 系统运行时）");
        }
        JsonNode input = call.input();
        String command = input.path("command").asText("");
        String ws = input.path("ws").asText("");
        boolean hasCommand = !command.isBlank();
        boolean hasWs = !ws.isBlank();
        if (hasCommand == hasWs) {
            return ToolResult.error(call.id(), "Exactly one of command or ws is required");
        }
        String description = input.path("description").asText("");
        if (description.isBlank()) {
            return ToolResult.error(call.id(), "description is required");
        }
        long timeoutMs = input.path("timeout_ms").asLong(0L);
        if (timeoutMs < 0) {
            return ToolResult.error(call.id(), "timeout_ms must be >= 0");
        }
        List<String> domains = new ArrayList<>();
        JsonNode domainsNode = input.path("allowed_domains");
        if (domainsNode.isArray()) {
            for (JsonNode d : domainsNode) {
                if (d.isTextual()) {
                    domains.add(d.asText());
                }
            }
        }
        String sessionId = ctx != null ? ctx.sessionId() : null;
        var agentId = ctx != null ? ctx.agentId() : null;
        try {
            String taskId = hasCommand
                ? monitorTaskRunner.startCommand(command, description, timeoutMs, call.id(), agentId, sessionId)
                : monitorTaskRunner.startWebSocket(ws, domains, description, timeoutMs, call.id(), agentId, sessionId);
            log.info("[MonitorTool] started taskId={} mode={} desc={}",
                taskId, hasCommand ? "command" : "ws", description);
            return ToolResult.success(call.id(),
                "Monitor started: " + taskId + "\nUse TaskOutput to read the stream; TaskStop to stop it.");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.id(), e.getMessage());
        } catch (Exception e) {
            log.error("[MonitorTool] start failed: {}", e.getMessage(), e);
            return ToolResult.error(call.id(), "Monitor failed to start: " + e.getMessage());
        }
    }
}
