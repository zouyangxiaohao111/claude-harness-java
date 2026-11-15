package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * ObserverReport 工具 · 对齐 claude.exe v2.1.325 的 {@code ObserverReport}（off≈328,477,xxx）。
 *
 * <p><b>CC 语义</b>：后台观察者（observer agent）发现「即将放大的错误 / 被忽略的约束 /
 * 该看到的先例」时，用这个工具汇报；<b>预期稳态是沉默</b>——大多数 digest 不值得回复。
 *
 * <p>本仓落点：报告以 {@code workload="observer-report"} 的队列项投递到观察者所属会话，
 * 由 CronIdleExecutor/当前 turn 消费（带 workload ⇒ 空闲会话会被代跑，不会滞留）。
 */
@Component
public class ObserverReportTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ObserverReportTool.class);

    public static final String NAME = "ObserverReport";
    /** 队列 workload 标记（与 goal-steering 同机制，保证空闲会话可被消费）。 */
    static final String WORKLOAD_OBSERVER_REPORT = "observer-report";

    private NotificationQueue notificationQueue;

    /** 通知队列（required=false：未装配时 execute 明确报错，不静默吞报告）。 */
    @Autowired(required = false)
    public void setNotificationQueue(NotificationQueue notificationQueue) {
        this.notificationQueue = notificationQueue;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Report something genuinely useful about the agent you are observing "
            + "(a mistake about to compound, a missed constraint, prior art it should see). "
            + "The expected steady state is silence: most digests warrant no report at all.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("message").put("type", "string")
            .put("description", "What the observed agent should know. Be specific and short.");
        props.putObject("urgency").put("type", "string")
            .put("description", "low | normal | high (default normal)");
        schema.putArray("required").add("message");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("observer_report_allow"),
            null, false, null, List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String message = call.input().path("message").asText("");
        if (message.isBlank()) {
            return ToolResult.error(call.id(), "message is required");
        }
        String urgency = call.input().path("urgency").asText("normal");
        String sessionId = ctx != null ? ctx.sessionId() : null;
        if (notificationQueue == null || sessionId == null || sessionId.isBlank()) {
            log.warn("[ObserverReport] 无法投递（queue={} session={}）", notificationQueue != null, sessionId);
            return ToolResult.error(call.id(), "ObserverReport 无法投递：缺少会话或通知队列");
        }
        String xml = "<observer-report urgency=\"" + esc(urgency) + "\">\n"
            + esc(message) + "\n</observer-report>";
        notificationQueue.enqueue(new NotificationQueue.QueueItem(
            xml, NotificationQueue.MODE_TASK_NOTIFICATION, NotificationQueue.Priority.NEXT,
            null, UUID.randomUUID().toString(), true, WORKLOAD_OBSERVER_REPORT,
            true, null, sessionId, null, null));
        log.info("[ObserverReport] 已投递报告 session={} urgency={} len={}",
            sessionId, urgency, message.length());
        return ToolResult.success(call.id(), "Observer report delivered");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
