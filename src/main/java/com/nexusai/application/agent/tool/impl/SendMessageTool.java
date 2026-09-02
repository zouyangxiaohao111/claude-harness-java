package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.InProcessTeammateTaskRegistry;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.util.AgentIdFormatter;
import com.nexusai.infra.util.SwarmConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SendMessageTool · 对齐 CC SendMessageTool.ts 的文件型 mailbox 投递（IMP-G2 组 6-2 重构）。
 *
 * <p>IMP-G2（组 6-2，TR-G3-⊕-07..12）重构要点（全部以 CC 实际 TS 源码为准）：
 * <ul>
 *   <li><b>message discriminatedUnion（⊕-08）</b>：{@code message} 为 {@code z.union([string,
 *       StructuredMessage])}（SendMessageTool.ts:82-85）。StructuredMessage 经
 *       {@code discriminatedUnion('type')} 3 种（:46-65）：{@code shutdown_request{reason?}} /
 *       {@code shutdown_response{request_id, approve, reason?}} /
 *       {@code plan_approval_response{request_id, approve, feedback?}}。旧扁平 8 值
 *       {@code type} 枚举（Java-only plain_text/idle_notification/permission_request/
 *       permission_response/task_assignment）删除。</li>
 *   <li><b>from 派生（⊕-07）</b>：删除输入 {@code from}（:67-87 无此键）；sender 经
 *       {@code getAgentName() || (isTeammate() ? 'teammate' : TEAM_LEAD_NAME)} 派生（:157-158）。</li>
 *   <li><b>name@team 拒绝（⊕-09）</b>：validateInput 拒绝 {@code to} 含 {@code @}（:623-630）。</li>
 *   <li><b>team 名（⊕-10）</b>：删除 {@code team-{sessionId前8}} 合成；team 名取
 *       {@code getTeamName(appState.teamContext)}（:156），Java 等价
 *       {@link Teammate#getTeamName(String)}（in-process &gt; dynamic &gt; teamContext）。</li>
 *   <li><b>输出契约（⊕-11）</b>：删除 {@code message_id/delivered_to/type/timestamp}；
 *       plain 消息输出 {@code {success, message, routing}}（:175-187），广播
 *       {@code {success, message, recipients[], routing}}（:252-265）。</li>
 *   <li><b>广播经 teamFile.members（⊕-12）</b>：收件人 = {@code teamFile.members} 的 name
 *       （排除 sender，大小写不敏感，:220-226）；旧 {@code member_*} 文件枚举删除（无写入方死代码）。</li>
 * </ul>
 *
 * <p>信封 text 语义（对齐 CC 实际 TS 源码，不信注释）：
 * <ul>
 *   <li>普通消息（handleMessage :149-189）：text = 原始内容 + summary/color 信封字段；</li>
 *   <li>shutdown_request（handleShutdownRequest :268-303）：text = 结构化消息 JSON
 *       {@code {type:'shutdown_request', requestId, from, reason?, timestamp}}
 *       （teammateMailbox.ts:720-728 schema，requestId 格式 {@code shutdown-{ts}@{target}}）；</li>
 *   <li>shutdown_approved（handleShutdownApproval :305-399）：text = {@code {type:'shutdown_approved',
 *       requestId, from, timestamp}}（teammateMailbox.ts:737-746）；</li>
 *   <li>shutdown_rejected（handleShutdownRejection :401-432）：text = {@code {type:'shutdown_rejected',
 *       requestId, from, reason, timestamp}}（teammateMailbox.ts:755-763）。</li>
 * </ul>
 *
 * <p>校验在 {@link #validateInput}（CC :604-718）：{@code to} 非空 / 无 {@code @} / string 消息
 * {@code summary} 必填 / 结构化禁广播 / {@code shutdown_response} 必须发 team-lead / 拒绝时
 * {@code reason} 必填（均 errorCode 9）。
 */
@Component
public class SendMessageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SendMessageTool.class);

    public static final String NAME = "SendMessage";

    /** 结构化 shutdown_request 消息 type · 对齐 CC SendMessageTool.ts:49 + teammateMailbox.ts:721。 */
    private static final String TYPE_SHUTDOWN_REQUEST = "shutdown_request";

    /** 结构化 shutdown_response 决策 type · 对齐 CC SendMessageTool.ts:53 + :890-897。 */
    private static final String TYPE_SHUTDOWN_RESPONSE = "shutdown_response";

    /** 批准确认消息 type · 对齐 CC teammateMailbox.ts:739（shutdown_approved）。 */
    private static final String TYPE_SHUTDOWN_APPROVED = "shutdown_approved";

    /** 拒绝确认消息 type · 对齐 CC teammateMailbox.ts:757（shutdown_rejected）。 */
    private static final String TYPE_SHUTDOWN_REJECTED = "shutdown_rejected";

    /** 结构化 plan_approval_response 决策 type · 对齐 CC SendMessageTool.ts:59 + teammateMailbox.ts:704。 */
    private static final String TYPE_PLAN_APPROVAL_RESPONSE = "plan_approval_response";

    /** 拒绝 shutdown_request 时 reason 必填（CC SendMessageTool.ts:706-715）。 */
    private static final String VALIDATE_ERR_CODE = "9";

    private final TeamHelpers teamHelpers;

    /**
     * [A4] 会话级 teamContext 读写 · 对齐 CC appState.teamContext（TeamCreateTool.ts:201-216）的
     * 会话列承载（sessions.team_context）。可选注入（规则 8，构造器不动）：未注入（测试/手动直构）
     * → 回退 ctx.getAppState()（同轮内存态，不破坏既有测试构造）。
     */
    @Autowired(required = false)
    private SessionService sessionService;

    /**
     * W8-GAP-02: teammate 任务定位入口 · 对齐 CC appState.tasks + findTeammateTaskByAgentId
     * （InProcessTeammateTask.tsx:92-108）。TaskStopTool.java:36-37 同款字段注入（@Component）。
     * 测试 / 手动直构时未注入 → approve 路径 warn + 不 abort（对齐 CC :362-364 不失败）。
     */
    @Autowired(required = false)
    private SpawnInProcess spawnInProcess;

    /**
     * [IMP-G4 C7] 会话级 name→agentId 注册表 · 对齐 CC {@code appState.agentNameRegistry}
     * 读点（SendMessageTool.ts:804 {@code appState.agentNameRegistry.get(input.to)}）。
     * 写点 = {@link com.nexusai.application.agent.tool.impl.SubagentTool} async spawn（AgentTool.tsx:703-712）。
     * 未注入（测试/手动直构）→ 按名 in-process 路由降级 mailbox（不破坏既有调用）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.subagent.AgentNameRegistry agentNameRegistry;

    /**
     * [team-frontend-channel B1] STOMP 出站模板 · writeToMailbox 后推送
     * {@code /topic/sessions/{leadSessionId}/team-messages}（leadSessionId 从 team config 反查，
     * 只推给创建者会话，防多会话互收泄漏；前端消息流展示，design doc §2.2 方案 A）。
     * 可选注入（规则 8，构造器不动）：未注入（无 WebSocket 场景/测试直构）→ 跳过推送，不破坏既有调用。
     */
    @Autowired(required = false)
    private org.springframework.messaging.simp.SimpMessagingTemplate ws;

    /**
     * [team-panel-backend-bugfix2] 任务释放 · shutdown approve 时 unassignTeammateTasks('shutdown')
     * （对齐 CC useInboxPoller.ts:735-741 释放 teammate 未完成任务）。可选注入：未注入（测试直构）
     * → 跳过任务释放（不破坏既有构造）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.tasks.TaskService taskService;

    /**
     * [team-panel-backend-bugfix2] Team 状态推送单点 · shutdown approve 移除成员后发 member_left
     * （/topic/sessions/{leadSessionId}/team-status，前端面板刷新）。可选注入：未注入（测试直构）→
     * 跳过推送（对齐 TeamCreateTool teamStatusPublisher 模式）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.team.TeamStatusPublisher teamStatusPublisher;

    @Autowired
    public SendMessageTool(TeamHelpers teamHelpers) {
        this.teamHelpers = teamHelpers;
    }

    /** 测试/接线用 setter（spawnInProcess · approve abort 定位 teammate 任务）· 对齐 TaskStopTool setter 模式。 */
    public void setSpawnInProcess(SpawnInProcess spawnInProcess) {
        this.spawnInProcess = spawnInProcess;
    }

    /** [IMP-G4 C7] 测试/接线用 setter（agentNameRegistry · 按名 in-process 子代理路由）· 对齐 setSpawnInProcess 模式。 */
    public void setAgentNameRegistry(com.nexusai.application.agent.subagent.AgentNameRegistry agentNameRegistry) {
        this.agentNameRegistry = agentNameRegistry;
    }

    /** [team-frontend-channel B1] 测试/接线用 setter（ws · STOMP 出站模板）· 对齐 LeaderPermissionConfirmBridge.setWs 模式。 */
    public void setWs(org.springframework.messaging.simp.SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    /** [team-panel-backend-bugfix2] 测试/接线用 setter（taskService · shutdown approve 释放任务）. */
    public void setTaskService(com.nexusai.application.agent.tasks.TaskService taskService) {
        this.taskService = taskService;
    }

    /** [team-panel-backend-bugfix2] 测试/接线用 setter（teamStatusPublisher · shutdown 移除成员后 member_left 推送）. */
    public void setTeamStatusPublisher(com.nexusai.application.agent.team.TeamStatusPublisher teamStatusPublisher) {
        this.teamStatusPublisher = teamStatusPublisher;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 搜索提示 · 对齐 CC SendMessageTool.ts:523 searchHint。 */
    @Override
    public String searchHint() {
        return "send messages to agent teammates (swarm protocol)";
    }

    /** 用户可见名 · 对齐 CC SendMessageTool.ts:526-528 userFacingName() → 'SendMessage'。 */
    @Override
    public String userFacingName() {
        return "SendMessage";
    }

    @Override
    public String description() {
        // 对齐 CC SendMessageTool.ts:720-722 DESCRIPTION（prompt.ts:3 'Send a message to another agent'）
        return "Send a message to another agent";
    }

    /** 是否延迟执行 · 对齐 CC SendMessageTool.ts:533 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /**
     * agent-swarms 门控 · 对齐 CC SendMessageTool.ts:535-537 {@code isEnabled() { return isAgentSwarmsEnabled() }}。
     *
     * <p>与 TeamCreateTool.ts:88-89 / TeamDeleteTool.ts:46-47 同门（tools.ts:228-230 门控起始点）：
     * 未开启 agent-swarms 时 SendMessageTool 不进 LLM schema，避免模型对未启用功能发起无效 swarm 调用。
     * 语义全见 {@link TaskSystemConfig#isAgentSwarmsEnabled()}（CC agentSwarmsEnabled.ts:24-44）。
     */
    @Override
    public boolean isEnabled() {
        // CC SendMessageTool.ts:535-537 isEnabled() { return isAgentSwarmsEnabled() } ·
        // 未开启 agent-swarms 时 SendMessageTool 不进 LLM schema（tools.ts:228 门控起始点）。
        boolean enabled = TaskSystemConfig.isAgentSwarmsEnabled();
        if (log.isDebugEnabled()) {
            log.debug("[SendMessageTool] isEnabled() = {}（isAgentSwarmsEnabled 门控，CC SendMessageTool.ts:535-537）", enabled);
        }
        return enabled;
    }

    /**
     * 只读判定 · 对齐 CC SendMessageTool.ts:539-541
     * {@code isReadOnly(input) { return typeof input.message === 'string' }}。
     * string 消息只写收件方 inbox（无本地读副作用外写），判只读可并发；结构化消息不可并发。
     */
    @Override
    public boolean isReadOnly(JsonNode input) {
        JsonNode message = input != null ? input.get("message") : null;
        return message != null && message.isTextual();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // CC SendMessageTool.ts:68-75 to: z.string()（recipient name 或 "*"；UDS_INBOX feature 关闭 → 无 bridge/uds 描述）
        ObjectNode to = props.putObject("to");
        to.put("type", "string");
        to.put("description",
                "Recipient: teammate name, or \"*\" for broadcast to all teammates");

        // CC SendMessageTool.ts:76-81 summary（string 消息必填的 UI 预览）
        ObjectNode summary = props.putObject("summary");
        summary.put("type", "string");
        summary.put("description",
            "A 5-10 word summary shown as a preview in the UI (required when message is a string).");

        // CC SendMessageTool.ts:82-85 message: z.union([z.string(), StructuredMessage()]) ·
        // StructuredMessage = discriminatedUnion('type', 3 种)（:46-65）
        ObjectNode message = props.putObject("message");
        message.put("description",
                "Plain text message content, or a structured swarm message "
                        + "(shutdown_request / shutdown_response / plan_approval_response).");
        ArrayNode oneOf = message.putArray("oneOf");
        ObjectNode text = oneOf.addObject();
        text.put("type", "string");
        text.put("description", "Plain text message content");
        ObjectNode structured = oneOf.addObject();
        structured.put("type", "object");
        ObjectNode sProps = structured.putObject("properties");
        ObjectNode sType = sProps.putObject("type");
        sType.put("type", "string");
        sType.putArray("enum")
                .add(TYPE_SHUTDOWN_REQUEST)
                .add(TYPE_SHUTDOWN_RESPONSE)
                .add(TYPE_PLAN_APPROVAL_RESPONSE);
        sType.put("description",
                "Structured message type (discriminatedUnion 'type', CC SendMessageTool.ts:47-64)");
        ObjectNode sRequestId = sProps.putObject("request_id");
        sRequestId.put("type", "string");
        sRequestId.put("description",
                "shutdown_response / plan_approval_response 回显的 request_id "
                        + "（CC original: request_id SendMessageTool.ts:54/:60）。");
        ObjectNode sApprove = sProps.putObject("approve");
        sApprove.put("type", "boolean");
        sApprove.put("description",
                "shutdown_response / plan_approval_response 决策：true=批准，false=拒绝"
                        + "（CC original: approve SendMessageTool.ts:55/:61）。");
        ObjectNode sReason = sProps.putObject("reason");
        sReason.put("type", "string");
        sReason.put("description",
                "shutdown_request 原因（可选）；shutdown_response 拒绝理由（approve=false 必填，"
                        + "CC SendMessageTool.ts:50/:56/:706-715）。");
        ObjectNode sFeedback = sProps.putObject("feedback");
        sFeedback.put("type", "string");
        sFeedback.put("description",
                "plan_approval_response 拒绝时的反馈（approve=false 可选，缺省 'Plan needs revision'，"
                        + "CC SendMessageTool.ts:62 + :909）。");
        structured.putArray("required").add("type");

        schema.putArray("required").add("to");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * [IT-5] 未知键运行时策略 = STRIP · 对齐 CC SendMessageTool.ts:67
     * {@code inputSchema = lazySchema(() => z.object({...}))} —— z.object 默认 strip
     * 未知键（safeParse 不报 unrecognized_keys）。
     *
     * <p>:93 广告 {@code additionalProperties=false} 保留：zod v4 toJSONSchema 对
     * z.object 实测输出 additionalProperties:false，广告层与 CC 逐字一致；
     * 运行时放行由本策略承担（广告与运行时分离）。
     */
    @Override
    public Tool.UnknownKeysPolicy unknownKeysPolicy() {
        return Tool.UnknownKeysPolicy.STRIP;
    }

    /**
     * 输入校验 · 对齐 CC SendMessageTool.ts:604-718 validateInput（errorCode 9）。
     *
     * <p>顺序（UDS_INBOX feature 关闭 → bridge/uds 分支 N/A，:612-622/:631-666 跳过）：
     * <ol>
     *   <li>{@code to} 非空（:605-611）；</li>
     *   <li>{@code to} 含 {@code @} → 拒绝（:623-630）；</li>
     *   <li>string 消息 {@code summary} 必填（:667-676）；</li>
     *   <li>结构化消息 {@code to:"*"} 禁广播（:678-684）；</li>
     *   <li>{@code shutdown_response} 必须发 {@code team-lead}（:694-703）；</li>
     *   <li>{@code shutdown_response} 拒绝时 {@code reason} 必填（:705-715）。</li>
     * </ol>
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String to = readString(input, "to");
        if (to == null || to.trim().isEmpty()) {
            // CC :605-611 to must not be empty
            return ValidationResult.fail(VALIDATE_ERR_CODE, "to must not be empty");
        }
        if (to.contains("@")) {
            // CC :623-630 拒绝 name@team 地址 —— 每 session 仅一 team
            return ValidationResult.fail(VALIDATE_ERR_CODE,
                    "to must be a bare teammate name or \"*\" — there is only one team per session");
        }
        JsonNode message = input.get("message");
        if (message != null && message.isTextual()) {
            // CC :667-676 string 消息 summary 必填
            String summary = readString(input, "summary");
            if (summary == null || summary.trim().isEmpty()) {
                return ValidationResult.fail(VALIDATE_ERR_CODE,
                        "summary is required when message is a string");
            }
            return ValidationResult.pass();
        }
        if (message != null && message.isObject()) {
            if ("*".equals(to)) {
                // CC :678-684 结构化消息禁广播
                return ValidationResult.fail(VALIDATE_ERR_CODE,
                        "structured messages cannot be broadcast (to: \"*\")");
            }
            String type = message.path("type").asText();
            if (TYPE_SHUTDOWN_RESPONSE.equals(type) && !SwarmConstants.TEAM_LEAD_NAME.equals(to)) {
                // CC :694-703 shutdown_response 必须发 team-lead
                return ValidationResult.fail(VALIDATE_ERR_CODE,
                        "shutdown_response must be sent to \"" + SwarmConstants.TEAM_LEAD_NAME + "\"");
            }
            if (TYPE_SHUTDOWN_RESPONSE.equals(type)
                    && !message.path("approve").asBoolean(false)
                    && blank(readString(message, "reason"))) {
                // CC :705-715 拒绝 shutdown 时 reason 必填
                return ValidationResult.fail(VALIDATE_ERR_CODE,
                        "reason is required when rejecting a shutdown request");
            }
        }
        return ValidationResult.pass();
    }

    /**
     * 自动分类器输入 · 对齐 CC SendMessageTool.ts:571-583 toAutoClassifierInput。
     * string → {@code "to {to}: {message}"}；结构化 → 按 type 摘要（approve/reject 区分）。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        if (input == null) {
            return "";
        }
        JsonNode message = input.get("message");
        String to = input.path("to").asText();
        if (message != null && message.isTextual()) {
            // CC :573-574 `to ${input.to}: ${input.message}`
            return "to " + to + ": " + message.asText();
        }
        if (message != null && message.isObject()) {
            String type = message.path("type").asText();
            switch (type) {
                case TYPE_SHUTDOWN_REQUEST -> {
                    // CC :576-577 `shutdown_request to ${input.to}`
                    return "shutdown_request to " + to;
                }
                case TYPE_SHUTDOWN_RESPONSE -> {
                    // CC :578-580 `shutdown_response ${approve?'approve':'reject'} ${request_id}`
                    boolean approve = message.path("approve").asBoolean(false);
                    return "shutdown_response " + (approve ? "approve" : "reject")
                            + " " + message.path("request_id").asText();
                }
                case TYPE_PLAN_APPROVAL_RESPONSE -> {
                    // CC :581-582 `plan_approval ${approve?'approve':'reject'} to ${input.to}`
                    boolean approve = message.path("approve").asBoolean(false);
                    return "plan_approval " + (approve ? "approve" : "reject") + " to " + to;
                }
                default -> { return ""; }
            }
        }
        return "";
    }

    /**
     * 观测字段补全 · 对齐 CC SendMessageTool.ts:543-569 backfillObservableInput。
     *
     * <p>为 hook / canUseTool / observer 填充 {@code type/recipient/content/request_id/approve}
     * 观测字段（broadcast→'broadcast' / plain→'message' / 结构化→其 type）；<b>不影响</b>最终
     * {@code execute()} 入参（Java 契约：backfill 仅观测，返回拷贝）。
     */
    @Override
    public JsonNode backfillObservableInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            return input;
        }
        ObjectNode node = input.deepCopy();
        String to = node.path("to").asText(null);
        if (to == null) {
            return node;
        }
        JsonNode message = node.get("message");
        if ("*".equals(to)) {
            // CC :547-549 broadcast
            node.put("type", "broadcast");
            if (message != null && message.isTextual()) {
                node.put("content", message.asText());
            }
        } else if (message != null && message.isTextual()) {
            // CC :550-553 message
            node.put("type", "message");
            node.put("recipient", to);
            node.put("content", message.asText());
        } else if (message != null && message.isObject()) {
            // CC :554-568 结构化 → type/recipient/request_id/approve/content(reason ?? feedback)
            String type = message.path("type").asText();
            node.put("type", type);
            node.put("recipient", to);
            if (message.has("request_id") && !message.path("request_id").isNull()) {
                node.put("request_id", message.path("request_id").asText());
            }
            if (message.has("approve") && !message.path("approve").isNull()) {
                node.put("approve", message.path("approve").asBoolean());
            }
            JsonNode reason = message.get("reason");
            JsonNode feedback = message.get("feedback");
            String content = reason != null && reason.isTextual() ? reason.asText()
                    : (feedback != null && feedback.isTextual() ? feedback.asText() : null);
            if (content != null) {
                node.put("content", content);
            }
        }
        return node;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 消息路由 · 对齐 CC SendMessageTool.ts:741-913 call。
     *
     * <p>路由（UDS_INBOX feature 关闭 → bridge/uds 分支 N/A，:742-798 跳过）：
     * <ol>
     *   <li>string 消息：{@code to:"*"} → {@link #handleBroadcast}；否则 {@link #handleMessage}
     *       （:876-881）；</li>
     *   <li>结构化消息：{@code to:"*"} → 拒绝（:883-885）；按 {@code type} 分发（:887-912）：
     *       shutdown_request → handleShutdownRequest；shutdown_response →
     *       approve?handleShutdownApproval:handleShutdownRejection；plan_approval_response →
     *       approve?handlePlanApproval:handlePlanRejection。</li>
     * </ol>
     *
     * <p>in-process 子代理路由（CC :800-874，按名 queue/resume）归属 IMP-G4（agentNameRegistry
     * 接线任务），本期不在此实现。
     */
    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String to = readString(input, "to");
        if (to == null) {
            return ToolResult.error(call.id(), "missing required input: to");
        }

        JsonNode message = input.get("message");
        if (message == null || message.isNull()) {
            return ToolResult.error(call.id(), "missing required input: message");
        }

        if (message.isTextual()) {
            String content = message.asText();
            if ("*".equals(to)) {
                return handleBroadcast(call, content, readString(input, "summary"), ctx);
            }
            // [IMP-G4 C7] in-process 子代理按名路由 · 对齐 CC SendMessageTool.ts:800-813：
            //   appState.agentNameRegistry.get(input.to) → 命中注册名 → queuePendingMessage
            //   （子 agent 下一轮 SubagentExecutor runSubagentQueryLoop drain 消费）。非注册名 →
            //   降级 handleMessage（mailbox）。agentNameRegistry 未注入 → 降级。
            AgentToolResult routed = routeToRegisteredSubagent(call, to, content);
            if (routed != null) {
                return routed;
            }
            return handleMessage(call, to, content, readString(input, "summary"), ctx);
        }

        if (message.isObject()) {
            // CC :883-885 结构化消息禁广播（validateInput 也拦，此处防御）
            if ("*".equals(to)) {
                return ToolResult.error(call.id(), "structured messages cannot be broadcast");
            }
            String type = message.path("type").asText();
            switch (type) {
                case TYPE_SHUTDOWN_REQUEST -> {
                    // CC :888-889 handleShutdownRequest(input.to, input.message.reason, context)
                    return handleShutdownRequest(call, to, readString(message, "reason"), ctx);
                }
                case TYPE_SHUTDOWN_RESPONSE -> {
                    // CC :890-897 approve → handleShutdownApproval(request_id) / reject → handleShutdownRejection(request_id, reason!)
                    String requestId = readString(message, "request_id");
                    if (requestId == null || requestId.isBlank()) {
                        return ToolResult.error(call.id(), "request_id is required for shutdown_response");
                    }
                    boolean approve = message.path("approve").asBoolean(false);
                    if (approve) {
                        return handleShutdownApproval(call, requestId, ctx);
                    }
                    String reason = readString(message, "reason");
                    if (reason == null || reason.isBlank()) {
                        return ToolResult.error(call.id(), "reason is required when rejecting a shutdown request");
                    }
                    return handleShutdownRejection(call, requestId, reason, ctx);
                }
                case TYPE_PLAN_APPROVAL_RESPONSE -> {
                    // CC :898-911 approve → handlePlanApproval(to, request_id) / reject → handlePlanRejection(to, request_id, feedback ?? 'Plan needs revision')
                    String requestId = readString(message, "request_id");
                    if (requestId == null || requestId.isBlank()) {
                        return ToolResult.error(call.id(), "request_id is required for plan_approval_response");
                    }
                    boolean approve = message.path("approve").asBoolean(false);
                    if (approve) {
                        return handlePlanApproval(call, to, requestId, ctx);
                    }
                    String feedback = readString(message, "feedback");
                    if (feedback == null || feedback.isBlank()) {
                        feedback = "Plan needs revision";
                    }
                    return handlePlanRejection(call, to, requestId, feedback, ctx);
                }
                default -> {
                    return ToolResult.error(call.id(), "unknown message type: " + type);
                }
            }
        }

        return ToolResult.error(call.id(), "message must be a string or a structured message object");
    }

    /**
     * [IMP-G4 C7] in-process 子代理按名路由 · 对齐 CC SendMessageTool.ts:800-813：
     * {@code registered = appState.agentNameRegistry.get(input.to)} → 命中注册名 →
     * {@code queuePendingMessage(agentId, message, setAppState)}（子 agent 下一轮
     * SubagentExecutor runSubagentQueryLoop drain 消费，CC :807-813 "queued for delivery ... at its
     * next tool round"）。未命中注册名 / 未注入 agentNameRegistry → 返回 null（调用方降级 handleMessage）。
     *
     * <p>不做 stopped/resume 分支（CC :815-874 auto-resume）：Java resumeAgentBackground 由
     * ResumeService 承载，归属后续任务；注册名命中即投递待办队列，stopped 场景降级 mailbox
     * （不静默失败）。结构化消息不在此路由（CC 仅 string 消息走 in-process 分支 :801）。
     *
     * @param call    工具调用块
     * @param to      收件人（注册名）
     * @param content 消息文本
     * @return 路由结果；非注册名 / 未接线 → null
     */
    private AgentToolResult routeToRegisteredSubagent(ToolUseBlock call, String to, String content) {
        if (agentNameRegistry == null) {
            return null;
        }
        String agentId = agentNameRegistry.resolve(to);
        if (agentId == null) {
            return null;
        }
        // 已注册 → 待办队列投递（CC queuePendingMessage 等价），子 agent 下一轮消费
        agentNameRegistry.queue(agentId, content);
        if (log.isDebugEnabled()) {
            log.debug("[SendMessageTool] [IMP-G4 C7] in-process 子代理按名路由: to='{}' agentId={} "
                    + "消息长度={} 已入待办队列 (CC SendMessageTool.ts:800-813 queuePendingMessage)",
                to, agentId, content == null ? 0 : content.length());
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", true);
        out.put("message", "Message queued for delivery to " + to
                + " at its next tool round.");
        return ToolResult.success(call.id(), out);
    }

    /**
     * 普通消息投递 · 对齐 CC handleMessage（SendMessageTool.ts:149-189）。
     *
     * <p>sender = {@code getAgentName() || (isTeammate() ? 'teammate' : TEAM_LEAD_NAME)}（:157-158）；
     * 写 mailbox 信封含 {@code summary/color}（:161-171）；输出
     * {@code {success, message, routing:{sender, senderColor, target:"@X", targetColor, summary, content}}}
     * （:175-187）。
     */
    private AgentToolResult handleMessage(ToolUseBlock call, String recipientName, String content,
                                          String summary, ToolUseContext ctx) {
        String teamName = resolveTeamName(ctx);
        String senderName = resolveSenderName();
        String senderColor = Teammate.getTeammateColor();

        try {
            TeammateMailbox.writeToMailbox(recipientName,
                    new TeammateMailbox.TeammateMessage(senderName, content,
                            TeammateMailbox.isoNow(), false, senderColor, summary),
                    teamName);
            log.info("[SendMessageTool] sent message from={} to={} team={}", senderName, recipientName, teamName);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("[SendMessageTool] send failed: {}", e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }

        // [team-frontend-channel B1] 写 inbox 成功 → 出站 topic（前端消息流实时展示，design doc §2.2 方案 A）
        publishTeammateMessage(teamName, senderName, recipientName, content, summary, senderColor);

        String recipientColor = findTeammateColor(ctx, recipientName);

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", true);
        out.put("message", "Message sent to " + recipientName + "'s inbox");
        ObjectNode routing = out.putObject("routing");
        routing.put("sender", senderName);
        if (senderColor != null) {
            routing.put("senderColor", senderColor);
        }
        routing.put("target", "@" + recipientName);
        if (recipientColor != null) {
            routing.put("targetColor", recipientColor);
        }
        if (summary != null) {
            routing.put("summary", summary);
        }
        routing.put("content", content);
        return ToolResult.success(call.id(), out.toString());
    }

    /**
     * 广播 · 对齐 CC handleBroadcast（SendMessageTool.ts:191-266）。
     *
     * <p><b>IMP-G2 ⊕-12</b>：收件人 = {@code teamFile.members} 的 name（排除 sender，大小写不敏感，
     * :220-226），非旧 {@code member_*} 文件枚举。team context 必填（:199-203）且 team 文件存在
     * （:205-208）；无成员 → {@code {success, message:'No teammates to broadcast to...', recipients:[]}}
     * （:228-236）；输出 {@code {success, message, recipients[], routing}}（:252-265）。
     */
    private AgentToolResult handleBroadcast(ToolUseBlock call, String content, String summary,
                                            ToolUseContext ctx) {
        String teamName = resolveTeamName(ctx);
        if (teamName == null || teamName.isBlank()) {
            // CC :199-203 无 team context → 拒绝
            return ToolResult.error(call.id(),
                    "Not in a team context. Create a team with Teammate spawnTeam first, or set CLAUDE_CODE_TEAM_NAME.");
        }
        if (!teamHelpers.teamExists(teamName)) {
            // CC :205-208 team 文件不存在 → 拒绝
            return ToolResult.error(call.id(), "Team \"" + teamName + "\" does not exist");
        }
        String senderName = resolveSenderName();
        String senderColor = Teammate.getTeammateColor();

        // CC :220-226 recipients = members.filter(m => m.name.toLowerCase() !== senderName.toLowerCase())
        List<String> recipients = teamHelpers.listMemberNames(teamName).stream()
                .filter(m -> !m.equalsIgnoreCase(senderName))
                .toList();

        if (recipients.isEmpty()) {
            // CC :228-236 无成员可广播
            return ToolResult.success(call.id(),
                    "{\"success\":true,\"message\":\"No teammates to broadcast to (you are the only team member)\",\"recipients\":[]}");
        }

        for (String recipientName : recipients) {
            try {
                TeammateMailbox.writeToMailbox(recipientName,
                        new TeammateMailbox.TeammateMessage(senderName, content,
                                TeammateMailbox.isoNow(), false, senderColor, summary),
                        teamName);
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.warn("[SendMessageTool] broadcast 写 {} inbox 失败: {}", recipientName, e.getMessage());
            }
        }
        log.info("[SendMessageTool] broadcast from={} to {} teammates in team={}", senderName, recipients.size(), teamName);
        // [team-frontend-channel B1] 广播写 inbox 完成 → 出站 topic（to="*" 一条，团队广播语义）
        publishTeammateMessage(teamName, senderName, "*", content, summary, senderColor);

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", true);
        out.put("message", "Message broadcast to " + recipients.size() + " teammate(s): "
                + String.join(", ", recipients));
        ArrayNode recips = out.putArray("recipients");
        recipients.forEach(recips::add);
        ObjectNode routing = out.putObject("routing");
        routing.put("sender", senderName);
        if (senderColor != null) {
            routing.put("senderColor", senderColor);
        }
        routing.put("target", "@team");
        if (summary != null) {
            routing.put("summary", summary);
        }
        routing.put("content", content);
        return ToolResult.success(call.id(), out.toString());
    }

    /**
     * [team-frontend-channel B1] teammate 消息出站 · writeToMailbox 后统一推送
     * {@code /topic/sessions/{leadSessionId}/team-messages}（leadSessionId 从 team config 反查，
     * 只推给创建者会话；design doc §2.2 方案 A outboundEvent）。
     *
     * <p><b>WHY 按 lead 会话（stomp-lead-session 方案 3）</b>：原全局 team topic
     * {@code /topic/teams/{teamName}/messages} 会被同 team 名的其它会话收到（多会话互收泄漏）；
     * 目标改为只推创建者（lead）会话。teammate 发起消息时 {@code ctx.sessionId()} 是 teammate 会话
     * 而非 lead，故必须 config 反查（单一真源）。
     *
     * <p>ws 未注入 / 无 team → debug 跳过；反查 leadSessionId 为空 → warn 跳过（不回退全局 team topic，
     * 防跨会话泄漏）；推送失败 → log.warn 不阻断。结构化消息（shutdown_* / plan_approval_response）
     * 不推本事件（D8：协议控制消息非聊天流）。
     *
     * @param teamName 目标 team（teamContext.teamName）
     * @param from     发送方 agent 名
     * @param to       收件名（单播 = 收件 agent 名；广播 = "*"）
     * @param text     消息文本
     * @param summary  UI 预览摘要（可空）
     * @param color    发送方颜色（可空）
     */
    private void publishTeammateMessage(String teamName, String from, String to,
                                        String text, String summary, String color) {
        if (ws == null || teamName == null || teamName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SendMessageTool] STOMP 未注入/无 team，跳过 teammate 消息推送: to={}", to);
            }
            return;
        }
        String leadSessionId = teamHelpers.leadSessionId(teamName);
        if (leadSessionId == null || leadSessionId.isBlank()) {
            log.warn("[SendMessageTool] 反查 leadSessionId 为空，跳过 teammate 消息推送 to={} team={}"
                    + "（不回退全局 topic，防跨会话泄漏）", to, teamName);
            return;
        }
        try {
            ws.convertAndSend("/topic/sessions/" + leadSessionId + "/team-messages",
                com.nexusai.eventbus.ws.TeammateMessageEvent.of(teamName, from, to, text, summary, color));
            log.info("[SendMessageTool] 已推送 teammate 消息 /topic/sessions/{}/team-messages from={} to={}",
                leadSessionId, from, to);
        } catch (Exception e) {
            log.warn("[SendMessageTool] STOMP 推送 teammate 消息失败（不阻断工具）: {}", e.toString());
        }
    }

    /**
     * shutdown_request 投递 · 对齐 CC handleShutdownRequest（SendMessageTool.ts:268-303）。
     *
     * <p>text = {@code JSON.stringify(createShutdownRequestMessage)}（:278-289）；requestId 格式
     * {@code shutdown-{ts}@{target}}（agentId.ts:62-68 generateRequestId）；输出
     * {@code {success, message, request_id, target}}（:295-302）。
     */
    private AgentToolResult handleShutdownRequest(ToolUseBlock call, String targetName, String reason,
                                                  ToolUseContext ctx) {
        String teamName = resolveTeamName(ctx);
        String senderName = Teammate.getAgentName() != null ? Teammate.getAgentName()
                : SwarmConstants.TEAM_LEAD_NAME;
        String requestId = "shutdown-" + System.currentTimeMillis() + "@" + targetName;

        TeammateMailbox.ShutdownRequestMessage shutdownMsg =
                TeammateMailbox.createShutdownRequestMessage(requestId, senderName, reason);
        try {
            TeammateMailbox.writeToMailbox(targetName,
                    new TeammateMailbox.TeammateMessage(senderName,
                            TeammateMailbox.toCompactJson(shutdownMsg),
                            TeammateMailbox.isoNow(), false, Teammate.getTeammateColor(), null),
                    teamName);
            log.info("[SendMessageTool] shutdown_request 写入 mailbox target={} requestId={} from={}",
                    targetName, requestId, senderName);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("[SendMessageTool] shutdown_request 写入失败 target={}: {}", targetName, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", true);
        out.put("message", "Shutdown request sent to " + targetName + ". Request ID: " + requestId);
        out.put("request_id", requestId);
        out.put("target", targetName);
        return ToolResult.success(call.id(), out.toString());
    }

    /**
     * shutdown 批准 · 对齐 CC handleShutdownApproval（SendMessageTool.ts:305-399）。
     *
     * <p>顺序（CC :330-366）：先写 shutdown_approved 到 team-lead mailbox（:330-346），再经
     * findTeammateTaskByAgentId 找本 in-process teammate 任务 → task.abortController.abort()
     *（:356-357，生命周期级——审批即退出整个 teammate）；找不到任务 → :362-364 warning 但仍返回
     * success（:392-398，不失败）。agent 名经 requestId 解析（Java in-process 无 teammate context
     * 时 getAgentName() 为 null 的兜底；CC 用 getAgentId/getAgentName 的 paneId/backendType 差异
     * 登记受控残留 SM-15）。
     *
     * @param requestId shutdown_response 回显的 request_id（内含被 shutdown 的 teammate 名）
     */
    private AgentToolResult handleShutdownApproval(ToolUseBlock call, String requestId, ToolUseContext ctx) {
        String agentName = extractAgentNameFromRequestId(requestId);
        if (agentName == null) {
            agentName = "teammate";
        }
        String teamName = resolveTeamName(ctx);
        // 1. 先写确认到 team-lead mailbox（CC :330-346 createShutdownApprovedMessage + writeToMailbox）
        try {
            TeammateMailbox.ShutdownApprovedMessage approved =
                    TeammateMailbox.createShutdownApprovedMessage(requestId, agentName, null, null);
            TeammateMailbox.writeToMailbox(SwarmConstants.TEAM_LEAD_NAME,
                    new TeammateMailbox.TeammateMessage(agentName,
                            TeammateMailbox.toCompactJson(approved),
                            TeammateMailbox.isoNow(), false, Teammate.getTeammateColor(), null),
                    teamName);
            log.info("[SendMessageTool] shutdown_approved 写入 team-lead mailbox requestId={} from={}",
                    requestId, agentName);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("[SendMessageTool] shutdown_approved 写入失败 requestId={}: {}", requestId, e.getMessage());
        }
        // 2. 找 in-process teammate 任务并 abort 生命周期控制器（CC :353-365）
        AutonomousAgentLoop loop = findTeammateLoop(agentName);
        if (loop != null && loop.taskState() != null && loop.taskState().abortController() != null) {
            loop.taskState().abortController().abort();
            log.info("[SendMessageTool] shutdown approve: abort 生命周期控制器 agent={}（in-process teammate 退出）",
                    agentName);
        } else {
            log.warn("[SendMessageTool] shutdown approve: 未找到 in-process teammate 任务/abortController "
                    + "agent={}（CC :362-364 同语义，不失败）", agentName);
        }
        // 3. [team-panel-backend-bugfix2] 自动离开全链路：移除成员 + 释放未完成任务 + 发 member_left
        //   + 同步 team_context.teammates（对齐 CC useInboxPoller.ts:727 removeTeammateFromTeamFile
        //   + :735-741 unassignTeammateTasks('shutdown')；Java 无 poller 注入，本方法为唯一同时
        //   持有 teamName+agentName 的离开点，集中收口）。
        if (teamName != null && teamHelpers != null) {
            try {
                String teammateAgentId = SpawnInProcess.formatAgentId(agentName, teamName);
                boolean removed = teamHelpers.removeMemberByAgentId(teamName, teammateAgentId);
                if (removed && teamStatusPublisher != null) {
                    teamStatusPublisher.publish(teamName, "member_left");
                }
                teamHelpers.syncTeamContextTeammates(teamName);
                if (taskService != null) {
                    taskService.unassignTeammateTasks(teamName, teammateAgentId, agentName, "shutdown");
                }
                log.info("[SendMessageTool] shutdown approve: 移除成员+释放任务 agent={} team={} removed={}"
                        + "（CC useInboxPoller.ts:727-741 对齐）", agentName, teamName, removed);
            } catch (Exception e) {
                log.warn("[SendMessageTool] shutdown approve 清理失败 agent={} team={}: {}",
                        agentName, teamName, e.getMessage());
            }
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", true);
        out.put("message", "Shutdown approved. Sent confirmation to team-lead. Agent " + agentName
                + " is now exiting.");
        out.put("request_id", requestId);
        return ToolResult.success(call.id(), out.toString());
    }

    /**
     * shutdown 拒绝 · 对齐 CC handleShutdownRejection（SendMessageTool.ts:401-432）。
     *
     * <p>只写 shutdown_rejected 到 team-lead mailbox（:408-423），<b>不 abort</b>（拒绝 = 继续工作）。
     */
    private AgentToolResult handleShutdownRejection(ToolUseBlock call, String requestId,
                                                    String reason, ToolUseContext ctx) {
        String agentName = extractAgentNameFromRequestId(requestId);
        if (agentName == null) {
            agentName = "teammate";
        }
        String teamName = resolveTeamName(ctx);
        try {
            TeammateMailbox.ShutdownRejectedMessage rejected =
                    TeammateMailbox.createShutdownRejectedMessage(requestId, agentName, reason);
            TeammateMailbox.writeToMailbox(SwarmConstants.TEAM_LEAD_NAME,
                    new TeammateMailbox.TeammateMessage(agentName,
                            TeammateMailbox.toCompactJson(rejected),
                            TeammateMailbox.isoNow(), false, Teammate.getTeammateColor(), null),
                    teamName);
            log.info("[SendMessageTool] shutdown_rejected 写入 team-lead mailbox requestId={} from={} reason={}",
                    requestId, agentName, reason);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("[SendMessageTool] shutdown_rejected 写入失败 requestId={}: {}", requestId, e.getMessage());
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", true);
        out.put("message", "Shutdown rejected. Reason: \"" + reason + "\". Continuing to work.");
        out.put("request_id", requestId);
        return ToolResult.success(call.id(), out.toString());
    }

    /**
     * plan 批准 · 对齐 CC handlePlanApproval（SendMessageTool.ts:434-476）。
     *
     * <p><b>只有 team-lead 能批准/拒绝 plan</b>（:442-446 isTeamLead 校验）；modeToInherit =
     * leader 当前 mode，leader 处于 plan 时继承 'default'（:448-449）；写
     * {@code {type:'plan_approval_response', requestId, approved:true, timestamp, permissionMode}}
     * 到 recipient mailbox（:451-457）；<b>不 abort</b>。输出 {@code {success, message, request_id}}。
     */
    private AgentToolResult handlePlanApproval(ToolUseBlock call, String recipient, String requestId,
                                               ToolUseContext ctx) {
        // CC :442-446 仅 team-lead 可批准/拒绝 plan · 对齐 teammate.ts:171-198 isTeamLead(teamContext)
        if (!isTeamLead(resolveTeamName(ctx))) {
            log.warn("[SendMessageTool] plan_approval_response 被非 team-lead 调用（CC :442-446 拒绝）");
            return ToolResult.error(call.id(),
                    "Only the team lead can approve plans. Teammates cannot approve their own or other plans.");
        }
        // CC :448-449 modeToInherit = leaderMode === 'plan' ? 'default' : leaderMode
        PermissionMode leaderMode = ctx != null ? ctx.mode() : null;
        PermissionMode modeToInherit =
                leaderMode == PermissionMode.PLAN ? PermissionMode.DEFAULT : leaderMode;
        String permissionModeCc = modeToInherit != null
                ? ToolPermissionGate.modeToCcString(modeToInherit) : "default";
        try {
            TeammateMailbox.writeToMailbox(recipient,
                    new TeammateMailbox.TeammateMessage(SwarmConstants.TEAM_LEAD_NAME,
                            planApprovalMessage(requestId, true, null, permissionModeCc),
                            TeammateMailbox.isoNow(), false, null, null),
                    resolveTeamName(ctx));
            log.info("[SendMessageTool] plan_approval_response approved=true 写入 mailbox "
                    + "requestId={} recipient={} permissionMode={}", requestId, recipient, permissionModeCc);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("success", true);
            out.put("message", "Plan approved for " + recipient
                    + ". They will receive the approval and can proceed with implementation.");
            out.put("request_id", requestId);
            return ToolResult.success(call.id(), out.toString());
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("[SendMessageTool] plan_approval_response 写入失败 requestId={}: {}", requestId, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    /**
     * plan 拒绝 · 对齐 CC handlePlanRejection（SendMessageTool.ts:478-518）。
     *
     * <p>feedback 缺省 'Plan needs revision'（:509）；写 {@code {type:'plan_approval_response',
     * requestId, approved:false, feedback, timestamp}}（:493-499）。
     */
    private AgentToolResult handlePlanRejection(ToolUseBlock call, String recipient, String requestId,
                                                String feedback, ToolUseContext ctx) {
        // CC :487-491 仅 team-lead 可拒绝 plan
        if (!isTeamLead(resolveTeamName(ctx))) {
            log.warn("[SendMessageTool] plan_approval_response 被非 team-lead 调用（CC :487-491 拒绝）");
            return ToolResult.error(call.id(),
                    "Only the team lead can reject plans. Teammates cannot reject their own or other plans.");
        }
        try {
            TeammateMailbox.writeToMailbox(recipient,
                    new TeammateMailbox.TeammateMessage(SwarmConstants.TEAM_LEAD_NAME,
                            planApprovalMessage(requestId, false, feedback, null),
                            TeammateMailbox.isoNow(), false, null, null),
                    resolveTeamName(ctx));
            log.info("[SendMessageTool] plan_approval_response approved=false 写入 mailbox "
                    + "requestId={} recipient={} feedback={}", requestId, recipient, feedback);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("success", true);
            out.put("message", "Plan rejected for " + recipient + " with feedback: \"" + feedback + "\"");
            out.put("request_id", requestId);
            return ToolResult.success(call.id(), out.toString());
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("[SendMessageTool] plan_approval_response 写入失败 requestId={}: {}", requestId, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    /**
     * CC teammate.ts:171-198 isTeamLead(teamContext) Java 等价 · 判定当前执行者是否 team lead。
     *
     * <p>teamName 为 null（无 teamContext，CC :178-180 {@code !teamContext?.leadAgentId → false}）→
     * 非 lead。leadAgentId 经 {@link TeamHelpers#leadAgentId} 从 team 配置 {@code leadAgentId} 解析，
     * 委托 {@link Teammate#isTeamLead(String)} 单一真源。
     *
     * @param teamName 目标 team（CC teamContext.teamName 等价），null → 非 lead
     * @return true=team lead（可批准/拒绝 plan）；false=非 lead
     */
    private boolean isTeamLead(String teamName) {
        if (teamName == null || teamName.isBlank()) {
            // CC :178-180 !teamContext?.leadAgentId → false（无 team context 恒非 lead）
            return false;
        }
        return Teammate.isTeamLead(teamHelpers.leadAgentId(teamName));
    }

    /**
     * W8-GAP-R2: plan_approval_response 信封 text 编码 · 对齐 CC teammateMailbox.ts:702-711
     * PlanApprovalResponseMessageSchema：{type, requestId, approved, feedback?, timestamp, permissionMode?}。
     * 注意 schema 字段名是 approved（非输入侧 approve，CC :706）。
     */
    private String planApprovalMessage(String requestId, boolean approved, String feedback,
                                       String permissionModeCc) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", TYPE_PLAN_APPROVAL_RESPONSE);
        node.put("requestId", requestId);
        node.put("approved", approved);
        node.put("timestamp", TeammateMailbox.isoNow());
        if (feedback != null) {
            node.put("feedback", feedback);
        }
        if (permissionModeCc != null) {
            node.put("permissionMode", permissionModeCc);
        }
        return node.toString();
    }

    /**
     * W8-GAP-02: 从 request_id 提取被 shutdown 的 teammate 名 · 对齐 CC agentId.ts:62-84
     * generateRequestId（{type}-{ts}@{agentId}）+ parseRequestId。
     *
     * <p>Java encodeText 生成的 request_id = {@code shutdown-{ts}@{recipientName}}（裸名），
     * 也兼容 {@code @name@team} 全量 agentId（parseAgentId 取 name 段）。
     */
    private String extractAgentNameFromRequestId(String requestId) {
        if (requestId == null) {
            return null;
        }
        AgentIdFormatter.RequestId parsed = AgentIdFormatter.parseRequestId(requestId);
        if (parsed == null || parsed.agentId() == null || parsed.agentId().isBlank()) {
            return null;
        }
        AgentIdFormatter.AgentId full = AgentIdFormatter.parseAgentId(parsed.agentId());
        return full != null ? full.agentName() : parsed.agentId();
    }

    /**
     * W8-GAP-02: 经 registry 找 in-process teammate loop · 对齐 CC findTeammateTaskByAgentId
     * （InProcessTeammateTask.tsx:92-108，偏好 running/存活）。未注入 spawnInProcess 时返回 null
     * （测试/手动直构，approve 路径降级为仅写确认，对齐 CC :362-364 不失败）。
     */
    private AutonomousAgentLoop findTeammateLoop(String agentName) {
        if (agentName == null || spawnInProcess == null) {
            return null;
        }
        InProcessTeammateTaskRegistry registry = spawnInProcess.registry();
        if (registry == null) {
            return null;
        }
        Optional<AutonomousAgentLoop> loop = registry.findByAgentName(agentName);
        return loop.orElse(null);
    }

    /**
     * sender 名派生 · 对齐 CC SendMessageTool.ts:157-158/:210-211
     * {@code getAgentName() || (isTeammate() ? 'teammate' : TEAM_LEAD_NAME)}。
     * 删除旧 {@code from} 输入（⊕-07）与 {@code "lead@default"} 回退（旧 resolveFrom）。
     */
    private String resolveSenderName() {
        String agentName = Teammate.getAgentName();
        if (agentName != null && !agentName.isBlank()) {
            return agentName;
        }
        return Teammate.isTeammate() ? "teammate" : SwarmConstants.TEAM_LEAD_NAME;
    }

    /**
     * team 名解析 · 对齐 CC {@code getTeamName(appState.teamContext)}（SendMessageTool.ts:156）
     * 与 teammate.ts:111-118（in-process &gt; dynamic &gt; teamContext.teamName）。
     *
     * <p><b>IMP-G2 ⊕-10</b>：删除旧 {@code team-{sessionId前8}} 合成（defaultTeamName）；
     * Java 等价 {@link Teammate#getTeamName(String)}（teamContext.teamName 经
     * {@code ctx.getAppState()} 读取）。无任何 team context → null（mailbox 回退 'default'）。
     *
     * @param ctx 工具调用上下文（可为 null）
     * @return team 名；无 → null
     */
    private String resolveTeamName(ToolUseContext ctx) {
        return Teammate.getTeamName(teamNameFromContext(ctx));
    }

    /**
     * 读取 teamContext Map（跨工具统一读）· 对齐 CC {@code appState.teamContext}
     * （SendMessageTool.ts:156，同 TeamCreateTool.ts:134 / TeamDeleteTool.ts:74 读取模式）。
     *
     * <p>[A4] store 优先（sessions.team_context 列，跨工具/回合持久），appState 回退（同轮内存态）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readTeamContext(ToolUseContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (sessionService != null && ctx.sessionId() != null) {
            Map<String, Object> teamContext = sessionService.getTeamContext(ctx.sessionId());
            if (teamContext != null) {
                return teamContext;
            }
        }
        if (ctx.getAppState() == null) {
            return null;
        }
        Map<String, Object> appState = ctx.getAppState().apply(null);
        if (appState == null) {
            return null;
        }
        Object tc = appState.get(TeamCreateTool.APPSTATE_TEAM_CONTEXT);
        if (!(tc instanceof Map<?, ?> teamContext)) {
            return null;
        }
        return (Map<String, Object>) teamContext;
    }

    /**
     * 读 teamContext.teamName · 对齐 CC {@code appState.teamContext?.teamName}
     * （SendMessageTool.ts:156，同 TeamCreateTool.ts:134 / TeamDeleteTool.ts:74 读取模式）。
     */
    private String teamNameFromContext(ToolUseContext ctx) {
        Map<String, Object> teamContext = readTeamContext(ctx);
        if (teamContext == null) {
            return null;
        }
        Object name = teamContext.get(TeamCreateTool.TEAM_CONTEXT_NAME);
        return (name instanceof String s && !s.isBlank()) ? s : null;
    }

    /**
     * 收件 teammate 颜色 · 对齐 CC findTeammateColor（SendMessageTool.ts:133-147）：
     * 遍历 {@code teamContext.teammates} 找 name 匹配的 color（store 优先，appState 回退）。
     */
    private String findTeammateColor(ToolUseContext ctx, String name) {
        Map<String, Object> teamContext = readTeamContext(ctx);
        if (teamContext == null) {
            return null;
        }
        // teamContext.teammates 键（CC TeamCreateTool.ts:204-210 setAppState teammates）
        Object teammates = teamContext.get("teammates");
        if (!(teammates instanceof Map<?, ?> teammatesMap)) {
            return null;
        }
        for (Object v : teammatesMap.values()) {
            if (v instanceof Map<?, ?> teammate) {
                Object nm = teammate.get("name");
                if (name != null && name.equals(nm)) {
                    Object color = teammate.get("color");
                    return color instanceof String s && !s.isBlank() ? s : null;
                }
            }
        }
        return null;
    }

    private String readString(JsonNode input, String key) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return null;
        }
        return input.get(key).asText();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
