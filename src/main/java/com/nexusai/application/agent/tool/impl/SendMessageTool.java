package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.messaging.MessageRecipientResolver;
import com.nexusai.application.agent.messaging.PeerAddressing;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.InProcessTeammateTaskRegistry;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.team.TeammateIdentity;
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
 *       {@link Teammate#getTeamName(TeammateIdentity, String)}（in-process &gt; dynamic &gt; teamContext）。</li>
 *   <li><b>输出契约（⊕-11）</b>：删除 {@code message_id/delivered_to/type/timestamp}；
 *       plain 消息输出 {@code {success, message, routing}}（:175-187）。</li>
 *   <li><b>广播删除（2026-11-20 用户裁定，对齐 CC 2.1.328）</b>：{@code to:"*"} 不再支持 ——
 *       {@link #validateInput} 最前按精确相等硬拒（{@code broadcast (to: "*") is no longer
 *       supported — send a message per recipient}），零 mailbox 写入；旧 {@code handleBroadcast}
 *       与通配 STOMP 事件已删。多个收件人必须逐个 {@code SendMessage}。</li>
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
 * <p>校验在 {@link #validateInput}（CC :604-718）：{@code to} 非空 / 无 {@code @} / 不得是
 * {@code "*"}（广播已删除）/ string 消息 {@code summary} 必填 / {@code shutdown_response} 必须发
 * team-lead / 拒绝时 {@code reason} 必填（均 errorCode 9）。
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

    /** 已删除的广播收件人 · 精确相等才拒（不把 {@code "*x"} 之类当成广播）。 */
    private static final String BROADCAST_REMOVED_TARGET = "*";

    /** 广播删除回执 · CC 2.1.328 原文（to:"*" 硬拒，不写任何 mailbox）。 */
    private static final String BROADCAST_REMOVED =
        "broadcast (to: \"*\") is no longer supported — send a message per recipient";

    /**
     * agent-live 回执尾句 · 对齐 CC exe（`Message queued for delivery to X at its next tool round.`
     * 之后追加）：子代理若先结束会被 resume 并再次发完成通知，⛔ 不要据此重发。
     */
    private static final String RESUME_ON_FINISH_HINT =
        " If it finishes first, it is resumed with the message and notifies again, "
        + "so wait for that notification before resending.";

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

    /** [agent-resume-align] 已结束子代理的 resume 通道（CC：「a send resumes it from its transcript」）。 */
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<
        com.nexusai.application.agent.subagent.ResumeService> resumeServiceProvider;

    /** 运行态判定（taskId === agentId）· CC auto-resume 只在 agent 已停止时触发。 */
    @Autowired(required = false)
    private com.nexusai.application.agent.tasks.BackgroundTaskRunner backgroundTaskRunner;

    /** [stop-double-write] 内存停止标记（CC Fat）· 未注入 → 只探磁盘伴生/meta。 */
    @Autowired(required = false)
    private com.nexusai.application.agent.subagent.StopMarkers stopMarkers;

    /**
     * [lineage] 会话用量账本注册表 · 子代理父子链的唯一真源（节点 {@code parentAgentId} 由
     * spawn 点写入、跨 turn 由 {@code usage_tree_json} 恢复）。未注入 → lineage 恒 false。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.cost.SessionUsageAccountingRegistry usageAccountingRegistry;

    /** [8 步判定 3] observer 探测（observer → 不可达）。未注入 → 跳过该步（旧行为）。 */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.nexusai.application.agent.subagent.AgentObserverService agentObserverService;

    /** [8 步判定 7] 逐出 teammate 的 in-process 重拉缝。未注入 → teammate 回落 subagent resume。 */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.nexusai.application.agent.subagent.TeammateRelauncher teammateRelauncher;

    /**
     * [B3 配额] 会话级并发槽（与 spawn 共用）· resume 前先给确定回执，真正的占槽在
     * {@code SpawnInProcess} 启动门（避免检查/占用竞态超发）。未注入 → 不做前置判定。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.subagent.ResumeQuota resumeQuota;

    /**
     * 会话目录解析缝（stop-by-person / evicted meta 探测用）· 生产 =
     * {@code ResumeService.resolveSessionDir}，单测注入临时目录（⛔ 不写用户真实 config home）。
     */
    private volatile java.util.function.Function<String, java.nio.file.Path> sessionDirResolver =
        com.nexusai.application.agent.subagent.ResumeService::resolveSessionDir;

    /** 测试/接线用 setter（会话目录解析缝）。 */
    public void setSessionDirResolver(
            java.util.function.Function<String, java.nio.file.Path> resolver) {
        this.sessionDirResolver = resolver != null
            ? resolver : com.nexusai.application.agent.subagent.ResumeService::resolveSessionDir;
    }

    /** 测试/接线用 setter（停止标记）。 */
    public void setStopMarkers(com.nexusai.application.agent.subagent.StopMarkers stopMarkers) {
        this.stopMarkers = stopMarkers;
    }

    /** 测试/接线用 setter（observer 探测）。 */
    public void setAgentObserverService(
            com.nexusai.application.agent.subagent.AgentObserverService agentObserverService) {
        this.agentObserverService = agentObserverService;
    }

    /** 测试/接线用 setter（teammate 重拉）。 */
    public void setTeammateRelauncher(
            com.nexusai.application.agent.subagent.TeammateRelauncher teammateRelauncher) {
        this.teammateRelauncher = teammateRelauncher;
    }

    /** 测试/接线用 setter（会话级并发槽）。 */
    public void setResumeQuota(com.nexusai.application.agent.subagent.ResumeQuota resumeQuota) {
        this.resumeQuota = resumeQuota;
    }

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

    /**
     * [跨会话 A 批] 收件人解析器（session/main/agent/sess-/name 判类 + live 校验）。
     * 未注入 → 只有旧 name/team 路径；结构类地址明确报错，绝不静默落 mailbox。
     */
    @Autowired(required = false)
    private MessageRecipientResolver recipientResolver;

    /** [跨会话 A 批] 跨会话路由（hold / loop / 投递确认）。未注入 = 跨会话不可用。 */
    @Autowired(required = false)
    private PeerMessageRouter peerMessageRouter;

    /**
     * [ref 门] 本会话「ListAgents 实际列过的行」pin 集合（CC {@code UMr}）。
     * 未注入（测试直构且不设 setter）→ 无法证明列过 ⇒ pinned 形态一律按 pin 失败处理（fail-closed）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.messaging.PeerRefPins refPins;

    /** [ref 门] 测试/接线用 setter（与 ListAgentsTool 共用同一实例）。 */
    public void setRefPins(com.nexusai.application.agent.messaging.PeerRefPins pins) {
        this.refPins = pins;
    }

    /** [跨会话 A 批] 测试/接线用 setter。 */
    public void setRecipientResolver(MessageRecipientResolver resolver) {
        this.recipientResolver = resolver;
    }

    /** [跨会话 A 批] 测试/接线用 setter。 */
    public void setPeerMessageRouter(PeerMessageRouter router) {
        this.peerMessageRouter = router;
    }

    /** 测试/接线用 setter（resume 通道）。 */
    public void setResumeServiceProvider(org.springframework.beans.factory.ObjectProvider<
            com.nexusai.application.agent.subagent.ResumeService> p) {
        this.resumeServiceProvider = p;
    }

    /** 测试/接线用 setter（运行态判定）。 */
    public void setBackgroundTaskRunner(
            com.nexusai.application.agent.tasks.BackgroundTaskRunner runner) {
        this.backgroundTaskRunner = runner;
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
        return "Send a message to another agent. Peer messages need no receipt-style reply.";
    }

    /**
     * 工具使用说明 · CC original: {@code tools/SendMessageTool/prompt.ts getPrompt()}（逐字迁移）。
     *
     * <p><b>为什么必须补</b>（2026-10-21）：此前只迁了 description 一行，模型看不到
     * 「to 能填什么 / 广播很贵 / 回复要复制 from / 结构化协议怎么回」——
     * 结果就是主代理不会用这个工具去找/继续已有子代理（用户反馈「感知不到子代理」）。
     *
     * <p>唯一删减：CC 的 {@code UDS_INBOX} 特性分支（跨会话 socket / bridge peer）。
     * 本仓无该特性（无 ListPeers 工具、无 uds/bridge 收件人），保留会让模型调用不存在的目标。
     */
    @Override
    public String prompt() {
        return """
            # SendMessage

            Send a message to another agent.

            ```json
            {"to": "researcher", "summary": "assign task 1", "message": "start on task #1"}
            ```

            | `to` | |
            |---|---|
            | `"researcher"` | Teammate / subagent by name |
            | `"alice [ab12cd]"` | Address from ListAgents (name + short ref) |
            | `"main"` | Your own session |
            | `"a...-16hex"` | Raw agentId (from a spawn result) |
            | `"sess-xxxxxxxx"` | Nexus extension session id (CC has no such prefix) |

            `notify_when_idle: true` asks a cross-session target to notify you once when it becomes idle; it is only valid for plain text messages sent to a session address.

            There is no broadcast: each call reaches exactly one recipient. To reach several agents, call this tool once per recipient (ListAgents lists them). Sending to `"*"` is rejected and writes nothing.

            Your plain text output is NOT visible to other agents — to communicate, you MUST call this tool. Messages from teammates are delivered automatically; you don't check an inbox. Refer to agents by name — names keep working after an agent completes (a send resumes it from its transcript). Use the raw `agentId` (format `a...-...`) from its spawn result only when the agent has no name, or when a newer agent took the name (latest wins). When relaying, don't quote the original — it's already rendered to the user. Remote transports (uds/bridge/did/`session_`/`cse_`) are not supported in this build.

            Receiving a message from another session does not require a receipt-style reply: don't send a message whose only purpose is to confirm receipt. Reply only when it moves the work forward. This is collaboration guidance, not a hard restriction.

            ## Protocol responses (legacy)

            If you receive a JSON message with `type: "shutdown_request"` or `type: "plan_approval_request"`, respond with the matching `_response` type — echo the `request_id`, set `approve` true/false:

            ```json
            {"to": "team-lead", "message": {"type": "shutdown_response", "request_id": "...", "approve": true}}
            {"to": "researcher", "message": {"type": "plan_approval_response", "request_id": "...", "approve": false, "feedback": "add error handling"}}
            ```

            Approving shutdown terminates your process. Rejecting plan sends the teammate back to revise. Don't originate `shutdown_request` unless asked. Don't send structured JSON status messages — use TaskUpdate.""".trim();
    }

    /** 是否延迟执行 · 对齐 CC SendMessageTool.ts:533 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /**
     * 常驻初始 tools 数组 · 用户 2026-11-13 裁定「AskUserQuestion 与 SendMessage 都要常驻」。
     *
     * <p><b>⚠️ 相对 CC 的刻意偏离</b>：exe 2.1.323 里 CC 的 SendMessage 只有 {@code shouldDefer:!0}；
     * 它强制常驻的是另一个工具 {@code SendUserMessage}（{@code BRIEF_TOOL_NAME}，即本仓 {@link BriefTool}）。
     * 本仓团队协作链路以 SendMessage 为主，故一并豁免。
     *
     * <p>生效路径：{@code ToolSearchService.isDeferredTool} 首条规则 {@code alwaysLoad} 优先级高于
     * {@code shouldDefer} ⇒ 不进 {@code deferredToolNames} ⇒ 不发 {@code defer_loading}、不进
     * {@code deferred_tools_delta}。
     *
     * @return 恒 {@code true}
     */
    @Override
    public boolean alwaysLoad() {
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
        // agent-swarms（旧门）OR 跨会话部署门（新门）——两者都关时工具不进 LLM schema。
        // deny / 白名单过滤在外部仍优先，本方法只决定可见性。
        boolean enabled = TaskSystemConfig.isAgentSwarmsEnabled()
            || TaskSystemConfig.isMessagingBeyondOwnAgentsEnabled();
        if (log.isDebugEnabled()) {
            log.debug("[SendMessageTool] isEnabled() = {}（agentSwarms OR messagingBeyondOwnAgents）", enabled);
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

        // CC SendMessageTool.ts:68-75 to: z.string()（单一 recipient name；UDS_INBOX feature 关闭 → 无 bridge/uds 描述；
        //   2026-11-20 广播 "*" 删除 → schema 不再广告广播）
        ObjectNode to = props.putObject("to");
        to.put("type", "string");
        to.put("description",
                "Recipient: teammate or subagent name, a ListAgents address \"name [ref]\", "
                        + "the literal \"main\" for your own session, a raw agentId (a...-16hex), "
                        + "or the Nexus extension \"sess-xxxxxxxx\" (not a CC prefix). "
                        + "Exactly one recipient per call; broadcast (\"*\") is no longer supported — "
                        + "send one message per recipient.");

        // [跨会话 A 批] notify_when_idle：一次性 idle 订阅（仅纯文本 + 跨会话会话地址）
        ObjectNode notifyWhenIdle = props.putObject("notify_when_idle");
        notifyWhenIdle.put("type", "boolean");
        notifyWhenIdle.put("description",
            "When true, ask the target session to notify you once when it goes idle/exits. "
                + "Only valid for plain text messages sent to a cross-session address.");

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
     *   <li>{@code to} 精确等于 {@code "*"} → 拒绝（CC 2.1.328 已删除广播，见常
     *       {@link #BROADCAST_REMOVED}）；</li>
     *   <li>{@code to} 非空（:605-611）；</li>
     *   <li>{@code to} 含 {@code @} → 拒绝（:623-630）；</li>
     *   <li>string 消息 {@code summary} 必填（:667-676）；</li>
     *   <li>{@code shutdown_response} 必须发 {@code team-lead}（:694-703）；</li>
     *   <li>{@code shutdown_response} 拒绝时 {@code reason} 必填（:705-715）。</li>
     * </ol>
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String to = readString(input, "to");
        if (BROADCAST_REMOVED_TARGET.equals(to)) {
            // CC 2.1.328：广播能力已硬删 —— 只允许单一收件人，绝不落任何 mailbox。
            return ValidationResult.fail(VALIDATE_ERR_CODE, BROADCAST_REMOVED);
        }
        if (to == null || to.trim().isEmpty()) {
            // CC :605-611 to must not be empty
            return ValidationResult.fail(VALIDATE_ERR_CODE, "to must not be empty");
        }
        if (to.contains("@")) {
            // CC :623-630 拒绝 name@team 地址 —— 每 session 仅一 team
            return ValidationResult.fail(VALIDATE_ERR_CODE,
                    "to must be a bare teammate name — there is only one team per session");
        }
        // [跨会话 A 批] notify_when_idle 只允许纯文本
        boolean notifyWhenIdle = input.path("notify_when_idle").asBoolean(false);
        if (notifyWhenIdle) {
            JsonNode notifyMessage = input.get("message");
            if (notifyMessage != null && notifyMessage.isObject()) {
                return ValidationResult.fail(VALIDATE_ERR_CODE,
                        "notify_when_idle is only supported for plain text messages");
            }
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
     * 观测字段（plain→'message' / 结构化→其 type）；<b>不影响</b>最终
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
        if (message != null && message.isTextual()) {
            // CC :550-553 message（广播分支随 to:"*" 删除已移除）
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
     *   <li>string 消息：{@link #handleMessage} 或跨会话/子代理路由（:876-881）；</li>
     *   <li>结构化消息：按 {@code type} 分发（:887-912）：shutdown_request →
     *       handleShutdownRequest；shutdown_response →
     *       approve?handleShutdownApproval:handleShutdownRejection；plan_approval_response →
     *       approve?handlePlanApproval:handlePlanRejection。</li>
     * </ol>
     *
     * <p>广播（{@code to:"*"}）已删除：{@link #validateInput} 前置拒绝；此处再按精确相等兜底，
     * 保证直接调用 {@code execute} 时也绝不写 mailbox。
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
        if (BROADCAST_REMOVED_TARGET.equals(to)) {
            // 广播能力已删除：一律单一收件人（validateInput 已拦，此处覆盖直接 execute 的调用方）。
            return ToolResult.error(call.id(), BROADCAST_REMOVED);
        }

        JsonNode message = input.get("message");
        if (message == null || message.isNull()) {
            return ToolResult.error(call.id(), "missing required input: message");
        }

        if (message.isTextual()) {
            String content = message.asText();
            return routeTextMessage(call, to, content, readString(input, "summary"),
                input.path("notify_when_idle").asBoolean(false), ctx);
        }

        if (message.isObject()) {
            // [跨会话 A 批] 结构化消息禁止发往跨会话地址（shutdown/plan 不得被当成用户批复）
            AgentToolResult crossSessionError = rejectStructuredCrossSession(call, to, ctx);
            if (crossSessionError != null) {
                return crossSessionError;
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
     * @param summary SendMessage 的 5-10 词 UI 摘要（前端通知条优先展示；可空）
     * @return 路由结果；非注册名 / 未接线 → null
     */
    private AgentToolResult routeToRegisteredSubagent(ToolUseBlock call, String to, String content,
                                                       String summary, ToolUseContext ctx) {
        if (agentNameRegistry == null) {
            return null;
        }
        String owner = ctx != null ? ctx.sessionId() : null;
        // 解析顺序（用户裁定）：活跃表 → 退役表 → not-found。
        //   registry.resolveEntry 已按该顺序实现；退役条目仍然带回 agentId，
        //   后续按「停/逐出」分支处理（不再像旧实现那样注销即 not-found）。
        com.nexusai.application.agent.subagent.AgentNameRegistry.AgentNameEntry entry =
            agentNameRegistry.resolveEntry(owner, to);
        if (entry == null) {
            return null;
        }
        return deliverToResolvedAgent(call, to, entry.agentId(), content, summary, ctx);
    }

    /**
     * 文本消息路由（判类顺序 session → main → agent → Nexus sess- → name）。
     *
     * <p>远端 session 形一律 Error 且<b>绝不放本地 mailbox</b>；session/main 走
     * {@link PeerMessageRouter}，失败即 Error；name 未命中只有 team 上下文才保留 mailbox。
     */
    private AgentToolResult routeTextMessage(ToolUseBlock call, String to, String content,
                                             String summary, boolean notifyWhenIdle,
                                             ToolUseContext ctx) {
        PeerAddressing.Parsed parsed = PeerAddressing.classify(to);
        if (parsed.kind() == PeerAddressing.Kind.SESSION) {
            return ToolResult.error(call.id(),
                "Error: remote cross-session messaging is not supported in this build");
        }
        String teamName = resolveTeamName(ctx);
        boolean hasTeam = teamName != null && !teamName.isBlank();
        List<String> teamMemberNames = teamMemberNames(teamName, ctx);

        if (recipientResolver == null) {
            // 未接线也要拦住「发给自己」：main / 本会话 sess- id 在没有 resolver 时无法解析投递，
            // 但必须给 CC 原文而不是「not wired」。
            if (isSelfAddressWithoutResolver(to, ctx)) {
                return selfAddressError(call, to);
            }
            if (parsed.kind() != PeerAddressing.Kind.NAME) {
                return ToolResult.error(call.id(),
                    "Error: cross-session messaging is not wired in this build");
            }
            AgentToolResult legacy = routeToRegisteredSubagent(call, to, content, summary, ctx);
            if (legacy != null) {
                return legacy;
            }
            if (notifyWhenIdle) {
                return ToolResult.error(call.id(),
                    "Error: notify_when_idle requires a live cross-session target");
            }
            // [cc-notfound-align] 旧路径同样要求收件人真实存在且**只能是 team-lead**：
            //   名册里的非 lead 成员是 teammate（CC roster-first），其投递必须走代理队列；
            //   本降级路径没有 resolver/loop 接线 ⇒ 一律 not-found 报错，⛔ 不落 inbox。
            if (!hasTeam || !memberHit(teamMemberNames, to)
                || !SwarmConstants.TEAM_LEAD_NAME.equals(PeerAddressing.normalizeName(to))) {
                return ToolResult.error(call.id(), "Error: " + noAgentNamed(to, teamMemberNames, ctx));
            }
            return handleMessage(call, to, content, summary, ctx);
        }

        String sourceSessionId = ctx != null ? ctx.sessionId() : null;
        MessageRecipientResolver.Target target = recipientResolver.resolve(
            new MessageRecipientResolver.Request(to, sourceSessionId, teamName, hasTeam,
                teamMemberNames, senderAgentId(ctx)));

        // [CC 对齐] 解析到本会话自身（主会话 → main / 自己的 sess- id / 自己的地址）⇒ 直接 Error。
        //   判定在 resolver（Target.selfSent），此处零投递、零 mailbox、零入队。
        //   子代理/teammate 的 sessionId 继承父会话，发回主会话时 selfSent=false（不算发给自己）。
        if (target.selfSent()) {
            return selfAddressError(call, to);
        }

        switch (target.kind()) {
            case UNSUPPORTED_REMOTE:
                return ToolResult.error(call.id(),
                    "Error: remote cross-session messaging is not supported in this build");
            case AMBIGUOUS:
                return ToolResult.error(call.id(), "Error: " + target.errorMessage());
            case AGENT:
            case NAME_AGENT:
                if (notifyWhenIdle) {
                    return ToolResult.error(call.id(),
                        "Error: notify_when_idle requires a cross-session session address, not a subagent");
                }
                return deliverToSubagent(call, to, target.agentId(), content, summary, ctx);
            case SESSION:
            case NAME_SESSION:
            case MAIN:
                return deliverCrossSession(call, target, content, summary, notifyWhenIdle, ctx);
            case TEAM_MAILBOX:
                return handleMessage(call, to, content, summary, ctx);
            case UNRESOLVED:
            default:
                // [cc-notfound-align] 对齐 CC 的 not-found：一律 success:false + 候选，
                //   ⛔ 不再「有 team 上下文就写 inbox」（那条降级正是名字写错时消息被吞掉的原因）。
                return ToolResult.error(call.id(), "Error: " + target.errorMessage());
        }
    }

    /**
     * 发送方 agent 身份 · 决定「目标会话 == 来源会话」是否算发给自己。
     *
     * <p>主会话 {@code ctx.agentId() == null}（对齐 CC {@code !context.agentId}）；子代理 /
     * teammate 都有 agentId，且 {@code sessionId} 继承父会话（{@code createSubagentContext}）——
     * 它们发回主会话属于正常投递，不得判成 selfSent。
     *
     * @param ctx 工具上下文（可 null）
     * @return agentId 串；null = 主会话本身
     */
    private static String senderAgentId(ToolUseContext ctx) {
        return ctx == null || ctx.agentId() == null ? null : ctx.agentId().toString();
    }

    /**
     * 未接线 resolver 时的自身地址判定（兜底，不放宽）：只有主会话才可能发给自己。
     *
     * <p>{@code main} 恒等于主会话自身；{@code sess-xxxxxxxx} 与本会话 id 逐字相等时同样是自身。
     *
     * @param to  写入的收件地址
     * @param ctx 工具上下文
     * @return true = 发给自己
     */
    private static boolean isSelfAddressWithoutResolver(String to, ToolUseContext ctx) {
        if (ctx == null || ctx.agentId() != null || ctx.sessionId() == null
            || ctx.sessionId().isBlank()) {
            return false;
        }
        PeerAddressing.Parsed parsed = PeerAddressing.classify(to);
        if (parsed.kind() == PeerAddressing.Kind.MAIN) {
            return true;
        }
        return parsed.kind() == PeerAddressing.Kind.NEXUS_SESSION
            && ctx.sessionId().equals(parsed.raw());
    }

    /**
     * 自身地址错误回执 · 文案 = CC 2.1.328 原文，零投递。
     *
     * @param call 工具调用块
     * @param to   写入的收件地址
     * @return Error 结果
     */
    private static AgentToolResult selfAddressError(ToolUseBlock call, String to) {
        return ToolResult.error(call.id(),
            "Error: " + MessageRecipientResolver.selfAddressFailure(to));
    }

    private AgentToolResult deliverToSubagent(ToolUseBlock call, String to, String agentId,
                                              String content, String summary, ToolUseContext ctx) {
        if (agentNameRegistry == null || agentId == null) {
            return ToolResult.error(call.id(), "Error: no live agent, teammate, or session named " + to);
        }
        return deliverToResolvedAgent(call, to, agentId, content, summary, ctx);
    }

    /**
     * 已解析 agentId 的投递 · 八步判定顺序（用户照 CC 2.1.328 裁定）。
     *
     * <ol>
     *   <li>not-found —— 在调用方（{@code noAgentNamed}），本方法不写 mailbox；</li>
     *   <li>停止/逐出三类 + {@code stoppedByPerson} → 拒（{@code was stopped by the user}）；</li>
     *   <li>observer → 不可达；</li>
     *   <li>live：running → 入队下一轮；status≠running → resume 并声明「若先收尾则带消息 resume」；</li>
     *   <li>agent-stopped-by-user → 拒；</li>
     *   <li>agent-stopped → 配额 + 读 transcript 续跑；</li>
     *   <li>agent-evicted → 读 meta：teammate 重拉 in-process；subagent 同 stopped；
     *       meta 不存在/读失败两档分别拒（CC dSa/cSa）；</li>
     *   <li>失败分档由 {@link #resumeStoppedAgent} 保留（v9 软化 / t2e / k9 / transcriptMissing / resume_failed）。</li>
     * </ol>
     *
     * <p>CC 原文（offset 356171000 区）：{@code if((r.kind==="agent-stopped"||r.kind===
     * "agent-stopped-by-user"||r.kind==="agent-evicted")&&pSa(r.agentId)) return Ns(...)}。
     * 本仓「受限模式」= in-process 名册路径恒受限（无跨会话放宽分支），故该门恒生效。
     */
    private AgentToolResult deliverToResolvedAgent(ToolUseBlock call, String to, String agentId,
                                                   String content, String summary,
                                                   ToolUseContext ctx) {
        com.nexusai.application.agent.tasks.BackgroundTask task = backgroundTaskRunner == null
            ? null : backgroundTaskRunner.getTask(agentId).orElse(null);
        boolean running = task != null
            && task.status() == com.nexusai.application.agent.tasks.BackgroundTaskStatus.RUNNING;
        com.nexusai.application.agent.subagent.RetiredAgentRegistry.RetiredAgent retired =
            agentNameRegistry.retiredEntryById(agentId);
        // CC Qt() roster-first：名册非 lead 名字 = teammate（不先读 taskKind）。
        boolean teammate = isTeammateRecipient(to, agentId, ctx);

        // ── 2. 停止/逐出/被用户停 三类统一先判 stoppedByPerson（CC 顺序：先于 observer）──
        //   [lineage] 仅「后代发起、恢复其祖先」时旁路（成员→lead / 子代理→父代理），
        //   交给下面的恢复入口（那里有同一条门的完整判据 + 配额 + 重拉）。
        //   ⛔ 父恢复子（lead→member、主代理→子代理）不是 lineage，照样 k9。
        //   ⛔ lineage 只由 ctx 身份 + 真实名册/账本现算。
        boolean stoppedByPerson = stoppedByPerson(agentId, ctx);
        boolean lineageBypass = false;
        if (!running && stoppedByPerson) {
            lineageBypass = teammate
                ? lineageForTeammateResume(ctx, resolveTeamName(ctx), agentId, to)
                : lineageForSubagentResume(ctx, agentId);
        }
        if (!running && stoppedByPerson && !lineageBypass) {
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                .stoppedByUserSendText(to), "not_reachable");
        }

        // ── 3. observer → 不可达（CC isObserver(agentId) → not_reachable）──
        if (agentObserverService != null && agentObserverService.isObserverAgentId(agentId)) {
            return failure(call, "Agent \"" + to + "\" is an observer and cannot be messaged directly.",
                "not_reachable");
        }

        // ── 4. agent-live ──
        if (running) {
            if (teammate) {
                // teammate 消费点是自己的常驻 wait 循环（AutonomousAgentLoop.pendingUserMessages），
                // ⛔ 不是 AgentNameRegistry 队列（那条只被 SubagentExecutor 子代理循环 drain）。
                return queueToTeammateNextToolRound(call, to, agentId, content, summary, ctx);
            }
            return queueForNextToolRound(call, to, agentId, content, summary, ctx);
        }
        // ── 4b. teammate 的 stopped/evicted 一律 in-process 重拉（CC resumeInProcessTeammate），
        //    ⛔ 不走 subagent transcript resume：teammate 的 agentId 是 name@team，不是 UUID。──
        if (teammate) {
            return resumeTeammate(call, to, agentId, content, ctx);
        }
        if (task != null) {
            // 记录在、但不是 running（pending / 终态）：CC agent-live 分支走 resume，
            // 并声明「若先收尾则带消息 resume」。配额/门/分档都在 ResumeService。
            AgentToolResult resumed = resumeStoppedAgent(call, to, agentId, content, summary, ctx,
                true);
            if (resumed != null) {
                return resumed;
            }
        }

        // ── 7. agent-evicted：task 记录已不在（退役表命中且 runner 无记录）──
        boolean evicted = retired != null && task == null;
        if (evicted) {
            // 逐出的 teammate 与 stopped 走同一条 in-process 重拉（CC resumeInProcessTeammate），
            // ⛔ 不走 subagent transcript resume。停止门 / 配额 / transcript 重放 / 成功双清
            // 都收在 resumeTeammate 单点实现里。
            if (teammate) {
                return resumeTeammate(call, to, agentId, content, ctx);
            }
            String sessionId = ctx != null ? ctx.sessionId() : null;
            String key = transcriptKey(agentId);
            java.nio.file.Path dir = resolveSessionDirQuiet(sessionId);
            if (dir != null) {
                AgentTranscript.MetadataState state = AgentTranscript.metadataState(dir, sessionId, key);
                if (state == AgentTranscript.MetadataState.MISSING) {
                    return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                        .metaMissingText(agentId), "not_reachable");
                }
                if (state == AgentTranscript.MetadataState.UNREADABLE) {
                    return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                        .metaUnreadableText(agentId), "not_reachable");
                }
                AgentTranscript.MetaExtras extras = AgentTranscript.readMetaExtras(dir, sessionId, key);
                if (extras.teammate()) {
                    // sidecar 说它是 teammate（roster 未命中时的兜底）：同样走 in-process 重拉。
                    return resumeTeammate(call, to, agentId, content, ctx);
                }
            }
            // taskKind === undefined → subagent → 与 agent-stopped 同一条路（CC Qt() 分流）。
        }

        // ── 5/6. agent-stopped-by-user（已被步骤 2 拦下）/ agent-stopped → resume ──
        AgentToolResult resumed = resumeStoppedAgent(call, to, agentId, content, summary, ctx,
            false);
        if (resumed != null) {
            return resumed;
        }
        // resume 通道未接线（plain 测试/裁剪构建）→ 保持旧的入队兜底（不静默丢消息）。
        return queueForNextToolRound(call, to, agentId, content, summary, ctx);
    }

    /**
     * teammate 投递 · 对齐 CC「Teammate "&lt;x&gt;" is already running; queued your message for its
     * next turn.」/「Message queued for delivery to &lt;x&gt; at its next tool round.」
     * （claude.exe 2.1.328 偏移 176785314 / 176801104）。
     *
     * <p><b>单一来源</b>：teammate 的唯一消费点是 {@link AutonomousAgentLoop#waitForNextPromptOrShutdown()}
     * 的第 1 优先级队列 {@code pendingUserMessages}（公共入口 {@link AutonomousAgentLoop#injectUserMessage}）。
     * ⛔ 不同时写 {@link com.nexusai.application.agent.subagent.AgentNameRegistry} 的待办队列
     * （那条只被子代理 query loop drain，对 teammate 永远没有读者 = 静默滞留）。
     *
     * <p>正文按 {@link AutonomousAgentLoop#formatAsTeammateMessage} 包裹：pendingUserMessages 被
     * 当作「user 消息纯文本」消费（不二次包裹），故发送侧必须自己带上同一份
     * {@code <teammate-message>} 形态，使 teammate 看到的 prompt 与文件 mailbox 时代一致。
     */
    private AgentToolResult queueToTeammateNextToolRound(ToolUseBlock call, String to, String agentId,
                                                         String content, String summary,
                                                         ToolUseContext ctx) {
        AutonomousAgentLoop loop = findTeammateLoop(agentId, null);
        if (loop == null) {
            // 任务记录还在 running 但 loop 不可达 = 本构建缺接线；⛔ 不静默写别处（会被当成功但永不消费）。
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode.notResumedText(
                to, "the teammate is running but its in-process execution loop is not reachable."),
                "resume_failed");
        }
        String sender = resolveSenderName(ctx);
        String color = Teammate.getTeammateColor(identityOf(ctx));
        loop.injectUserMessage(AutonomousAgentLoop.formatAsTeammateMessage(
            sender, content, color, summary));
        log.info("[SendMessageTool] teammate 按名入队（下一 tool 轮消费）: to='{}' agentId={} from={} "
            + "消息长度={} (CC queue for next tool round)", to, agentId, sender,
            content == null ? 0 : content.length());
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", true);
        out.put("agentId", agentId);
        out.put("delivery", "queued");
        out.put("message", "Message queued for delivery to " + to + " at its next tool round.");
        return ToolResult.success(call.id(), out);
    }

    /**
     * teammate 非运行态投递 · 对齐 CC {@code resumeInProcessTeammate}（偏移 356099332）：
     * 读 transcript/sidecar → 同会话重新拉起 in-process teammate，并把本条消息作为它的下一轮 prompt。
     *
     * <p>与 subagent 的差异（CC {@code Qt()} 分流）：
     * <ul>
     *   <li>meta 缺失/不可读 ⛔ 不用 dSa/cSa 拒绝 —— teammate 允许
     *       {@code (no prior transcript)} 重拉（偏移 356174861）；</li>
     *   <li>不查 agentId 是否为 UUID（teammate 是 {@code name@team}）。</li>
     * </ul>
     * 重拉实现由 B3 的 {@link com.nexusai.application.agent.subagent.TeammateRelauncher} 提供
     * （正文条数 N 的文案也在那一批）；未接线/失败 → resume_failed 分档，⛔ 不谎报成功。
     */
    private AgentToolResult resumeTeammate(ToolUseBlock call, String to, String agentId,
                                           String content, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;
        String name = to;
        String teamName = resolveTeamName(ctx);
        String agentType = null;
        java.nio.file.Path dir = resolveSessionDirQuiet(sessionId);
        if (dir != null && sessionId != null) {
            AgentTranscript.MetaExtras extras =
                AgentTranscript.readMetaExtras(dir, sessionId, transcriptKey(agentId));
            if (extras != null) {
                if (!blank(extras.name())) {
                    name = extras.name();
                }
                if (!blank(extras.teamName())) {
                    teamName = extras.teamName();
                }
                agentType = extras.agentType();
            }
        }
        // ── [B3] 用户 Stop 门（k9）· 对齐 CC 偏移 333967189 / 356171000：
        //   (meta.stoppedByUser ‖ 伴生文件 ‖ 内存 stoppedByUser) && !userInitiated && !lineage → 拒。
        //   本入口 = 模型经 SendMessage 自动恢复 ⇒ userInitiated=false。
        //   [lineage] 真实触发点 = team config 的 leadAgentId：lead 是成员的父 ⇒
        //   只有「成员恢复 lead（后代发起祖先）」算血统；lead/主会话恢复成员（父恢复子）
        //   与兄弟成员之间一律不算。⛔ 判定只读 ctx 身份 + team config，不读工具入参。
        boolean lineage = lineageForTeammateResume(ctx, teamName, agentId, name);
        String stopRefusal = com.nexusai.application.agent.subagent.TeammateResumeGate.refusalText(
            dir, sessionId, agentId, name, false, lineage, stopMarkers);
        if (stopRefusal != null) {
            log.info("[SendMessageTool] teammate 恢复被用户停止标记拒绝: to='{}' agentId={} "
                + "（CC AgentStoppedByUserError / k9）", to, agentId);
            return failure(call, stopRefusal, "not_reachable");
        }
        if (teammateRelauncher == null) {
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                .notResumedText(to, "in-process teammate relaunch is not available in this build."),
                "resume_failed");
        }
        if (blank(teamName)) {
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                .notResumedText(to, "the teammate has no team saved, so it cannot be started again."),
                "resume_failed");
        }
        // ── [B3 配额] 会话满 → session_full（CC uSa 原文），⛔ 不排队、不发起重拉 ──
        if (resumeQuota != null && !resumeQuota.hasRoom(sessionId)) {
            log.info("[SendMessageTool] teammate 恢复被并发槽拒绝: to='{}' session={} occupied={}/{} "
                + "（session_full，不排队）", to, sessionId, resumeQuota.inUse(sessionId),
                resumeQuota.max());
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                .sessionFullText(to), "session_full");
        }
        // ── [B3 重拉] 按 transcript 全量重建历史（CC rwe → 同一变形链）──
        java.util.List<com.nexusai.application.agent.subagent.AgentMessage> resumeMessages =
            com.nexusai.application.agent.subagent.ResumeService.loadTeammateResumeMessages(
                dir, sessionId, transcriptKey(agentId));
        com.nexusai.application.agent.subagent.TeammateRelauncher.TeammateRelaunchResult result =
            teammateRelauncher.relaunch(name, teamName, sessionId, agentType, content, resumeMessages);
        if (result != null && result.success()) {
            // 恢复成功 → 双清（内存标记 + 磁盘伴生/meta；CC eKt）
            com.nexusai.application.agent.subagent.TeammateResumeGate.clearAfterResume(
                dir, sessionId, agentId, stopMarkers);
            log.info("[SendMessageTool] teammate 已重拉 in-process: to='{}' name={} team={} "
                + "newAgentId={} 重放历史={} 条", to, name, teamName, result.agentId(),
                result.resumedMessageCount());
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("success", true);
            out.put("agentId", result.agentId());
            out.put("message", teammateResumedMessage(name, result.resumedMessageCount()));
            return ToolResult.success(call.id(), out);
        }
        String errorClass = result != null && result.errorClass() != null
            ? result.errorClass() : "resume_failed";
        String errorMessage = result != null && result.errorMessage() != null
            ? result.errorMessage() : "the teammate could not be started again.";
        if ("session_full".equals(errorClass)) {
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                .sessionFullText(to), "session_full");
        }
        return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
            .notResumedText(to, errorMessage), "resume_failed");
    }

    /**
     * teammate 恢复的 lineage 判定（只读真实名册，⛔ 不读工具入参）。
     *
     * <p>数据源：team config {@code leadAgentId}（{@link TeamHelpers#leadAgentId}）。
     * lead 是每个成员的父 ⇒ 只有<b>成员恢复 lead（后代发起祖先）</b>为 lineage：
     * <ul>
     *   <li>发起方是<b>同队成员</b>、目标是 lead ⇒ true；</li>
     *   <li>lead / 主会话恢复成员（父恢复子）、同队另一成员（兄弟）、不同 team / 子代理 ⇒ false。</li>
     * </ul>
     *
     * @param ctx          工具上下文（身份唯一来源）
     * @param teamName     目标 team
     * @param targetAgentId 目标完整 agentId（name@team）
     * @param targetName   目标名（agentId 缺失时的兜底拼装）
     * @return true = 成员（后代）恢复 lead（祖先）
     */
    private boolean lineageForTeammateResume(ToolUseContext ctx, String teamName,
                                             String targetAgentId, String targetName) {
        if (teamHelpers == null || teamName == null || teamName.isBlank()) {
            return false;
        }
        String lead = teamHelpers.leadAgentId(teamName);
        if (lead == null || lead.isBlank()) {
            return false;
        }
        String target = !blank(targetAgentId)
            ? targetAgentId
            : (targetName == null ? "" : targetName) + "@" + teamName;
        TeammateIdentity self = identityOf(ctx);
        if (self == null || blank(self.teamName())) {
            // 主会话（即 lead 本人）/ 子代理发起：不是「成员恢复 lead」
            return false;
        }
        if (!self.teamName().equals(teamName)) {
            return false; // 跨 team 不是同一条血统
        }
        return com.nexusai.application.agent.subagent.AgentLineage.teammateLinked(
            Teammate.getAgentId(self), target, lead);
    }

    /**
     * 子代理恢复的 lineage 判定 · 父子链取会话用量账本（spawn 写入的 parentAgentId）。
     *
     * <p>发起方 = {@code ctx.agentId()}（orchestrator 盖章的身份）；主会话为 null
     * （账本根节点）→ 恒不是后代。⛔ 判定完全不读工具入参。
     *
     * @return true = 发起方是目标的<b>后代</b>（目标在其祖先链上）；父恢复子 → false
     */
    private boolean lineageForSubagentResume(ToolUseContext ctx, String agentId) {
        if (usageAccountingRegistry == null || ctx == null || agentId == null) {
            return false;
        }
        com.nexusai.application.agent.cost.SessionUsageLedger ledger =
            usageAccountingRegistry.peek(ctx.sessionId());
        if (ledger == null) {
            return false;
        }
        String initiator = ctx.agentId() != null ? ctx.agentId().toString() : null;
        return com.nexusai.application.agent.subagent.ResumeService.computeLineage(
            ledger, initiator, agentId, ctx.sessionId());
    }

    /**
     * teammate 重拉成功文案 · 对齐 CC（claude.exe 2.1.328 偏移 356176932 逐字）：
     * {@code Teammate "X" was not running; resumed it as an in-process teammate with N prior
     * messages and your message as its next prompt.}
     * / 无 transcript 变体 {@code ... (no prior transcript) with your message as its next prompt.}
     *
     * @param to 收件名（模型写的那个名字）
     * @param n  实际重放的 transcript 条数（与传给 relauncher 的列表长度同源）
     */
    static String teammateResumedMessage(String to, int n) {
        String middle = n > 0
            ? "with " + n + " prior messages and your message as its next prompt."
            : "(no prior transcript) with your message as its next prompt.";
        return "Teammate \"" + to + "\" was not running; resumed it as an in-process teammate "
            + middle;
    }

    /**
     * 「这个收件名是 teammate 吗」· 对齐 CC {@code Qt()}（偏移 356127769 / 356099285）的
     * roster-first 判类：
     * <ol>
     *   <li>团队名册里命中且不是 {@code team-lead} ⇒ teammate（不先读 sidecar taskKind）；</li>
     *   <li>否则看活体：本会话 registry 有该 agentId 的 loop，或任务类型是
     *       {@code IN_PROCESS_TEAMMATE}（覆盖 kill 已从名册移除、30s 展示窗口内的情形）。</li>
     * </ol>
     * 都没有 ⇒ 按 subagent（旧的 transcript resume 路径，零变化）。
     */
    private boolean isTeammateRecipient(String to, String agentId, ToolUseContext ctx) {
        String candidate = MessageRecipientResolver.stripNoRefMarker(to);
        if (!blank(candidate) && !"team-lead".equals(PeerAddressing.normalizeName(candidate))) {
            String teamName = resolveTeamName(ctx);
            if (memberHit(teamMemberNames(teamName, ctx), candidate)) {
                return true;
            }
        }
        if (agentId == null) {
            return false;
        }
        if (findTeammateLoop(agentId, null) != null) {
            return true;
        }
        return backgroundTaskRunner != null && backgroundTaskRunner.getTask(agentId)
            .map(t -> t.type() == com.nexusai.application.agent.tasks.TaskType.IN_PROCESS_TEAMMATE)
            .orElse(false);
    }

    private AgentToolResult queueForNextToolRound(ToolUseBlock call, String to, String agentId,
                                                  String content, String summary,
                                                  ToolUseContext ctx) {
        // [agent-notice] 入队即生成稳定 ID，并携带来源/摘要/会话：子 agent 消费时落成一条
        //   user 消息（author=from, subtype=agent_message, isMeta=true），前端据此显示通知条。
        String sender = resolveSenderName(ctx);
        String origin = Teammate.isTeammate(identityOf(ctx)) ? "peer" : "coordinator";
        agentNameRegistry.queue(agentId,
            ctx != null ? ctx.sessionId() : null, sender, content, summary, origin);
        if (log.isDebugEnabled()) {
            log.debug("[SendMessageTool] in-process 子代理按名路由: to='{}' agentId={} from={} origin={} "
                    + "消息长度={} 已入待办队列 (CC queuePendingMessage)", to, agentId, sender, origin,
                content == null ? 0 : content.length());
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", true);
        out.put("message", "Message queued for delivery to " + to + " at its next tool round."
            + RESUME_ON_FINISH_HINT);
        return ToolResult.success(call.id(), out);
    }

    /** 停止意图判定（内存标记 → 退役记录 → 磁盘伴生/meta）· 异常即「视为已停」（CC pSa ??!0）。 */
    private boolean stoppedByPerson(String agentId, ToolUseContext ctx) {
        try {
            if (stopMarkers != null && stopMarkers.isStopped(agentId)) {
                return true;
            }
            com.nexusai.application.agent.subagent.RetiredAgentRegistry.RetiredAgent r =
                agentNameRegistry != null ? agentNameRegistry.retiredEntryById(agentId) : null;
            if (r != null && r.stoppedByUser()) {
                return true;
            }
            String sessionId = ctx != null ? ctx.sessionId() : null;
            java.nio.file.Path dir = resolveSessionDirQuiet(sessionId);
            if (dir == null) {
                return false;
            }
            String key = transcriptKey(agentId);
            return AgentTranscript.isStoppedByUserOrMeta(dir, sessionId, key)
                || AgentTranscript.isStoppedByUserOrMeta(dir, sessionId, agentId);
        } catch (Exception e) {
            // CC pSa: catch → ??!0（缺省/异常 = 拒绝）
            return true;
        }
    }

    private java.nio.file.Path resolveSessionDirQuiet(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            java.util.function.Function<String, java.nio.file.Path> resolver = sessionDirResolver;
            return resolver != null ? resolver.apply(sessionId) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** agentId 的 transcript 键（a+16hex 原样；其它形态原样，旧 UUID 键不再还原 —— Breaking）。 */
    private static String transcriptKey(String agentId) {
        String canonical = com.nexusai.application.agent.subagent.AgentContext.canonicalAgentId(agentId);
        return canonical != null ? canonical : agentId;
    }

    /** 分档失败回执（degradedClass 保留 CC 的档名，⛔ 不压成一句 Could not resume）。 */
    private AgentToolResult failure(ToolUseBlock call, String message, String degradedClass) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("success", false);
        if (degradedClass != null) {
            out.put("degradedClass", degradedClass);
        }
        out.put("message", message);
        return ToolResult.success(call.id(), out);
    }

    /** 跨会话投递（失败一律 Error，不假成功、不落 mailbox）。 */
    private AgentToolResult deliverCrossSession(ToolUseBlock call,
                                                MessageRecipientResolver.Target target,
                                                String content, String summary,
                                                boolean notifyWhenIdle, ToolUseContext ctx) {
        // 可达性口径（⛔ 本方法不另做判定，避免第三套真源）：
        //   ① MessageRecipientResolver 已按 DB 判存在（记录在且未删；删除即拒），
        //      并在缺 endpoint 时按需登记；② 代次 / accepting 由 router 再核一次。
        //   故这里拿到的 sessionId + endpointEpoch 已是可投递快照（DB-only 会话此时已被唤醒登记）。
        if (peerMessageRouter == null) {
            return ToolResult.error(call.id(),
                "Error: cross-session messaging is not wired in this build");
        }
        String sourceSessionId = ctx != null ? ctx.sessionId() : null;
        if (sourceSessionId == null || sourceSessionId.isBlank()) {
            return ToolResult.error(call.id(), "Error: cannot determine source session for cross-session send");
        }
        // [ref 门] 判类/解析之后、任何投递副作用之前：pin → 冒充。失败 = Error 且零投递。
        String gateFailure = preSendGateFailure(target, sourceSessionId);
        if (gateFailure != null) {
            return ToolResult.error(call.id(), "Error: " + gateFailure);
        }
        PermissionMode sourceMode = ctx.permissionMode() != null ? ctx.permissionMode() : ctx.mode();
        String sourceName = resolveSenderName(ctx);
        String sourceAddress = PeerAddressing.formatAddress(sourceName, sourceSessionId);
        // [hop 链传播] 回复 peer 时必须把入站链带上（prior + 本段），否则 A→B→A 往返链永远只有一段、
        //   自环/链长判定在生产不可达。来源唯一 = 服务端类型化载体 ctx.peerExecutionOrigin()
        //   （⛔ 不读模型正文 / structuredOutput / 前端自报）。
        List<String> inboundHopChain = inboundHopChain(ctx);
        // 同会话记录投递（子代理 / teammate → 主会话）：target 与 source 是同一个会话记录，
        // 但发送方不是主会话本身（selfSent 在 routeTextMessage 已拦）。路由层据此跳过刹车/限速/
        // hop 自环与入站 hold（这不是「别人发来的消息」），信封 admission=auto/self-sent。
        boolean sameSessionDelivery = sourceSessionId.equals(target.sessionId());
        PeerMessageRouter.SendRequest request = new PeerMessageRouter.SendRequest(
            target.sessionId(), target.endpointEpoch(), target.displayName(),
            sourceSessionId, sourceName, sourceAddress, content, summary,
            notifyWhenIdle, sameSessionDelivery, inboundHopChain);
        PeerMessageRouter.Receipt receipt = peerMessageRouter.send(request, sourceMode);

        switch (receipt.status()) {
            case ACCEPTED: {
                // [SendMessage 成功回执对齐 CC 2.1.328] 跨会话成功 = 一句话回执（与 CC 实测同形）：
                //   首行 "sent to <address> — another Claude session on this machine"，
                //   第二行 "message_id: <id>" 保留排查线索。
                //   <address> 用 ListAgents 的可回信地址（name / name [ref]），模型可直接回填 to。
                //   ⛔ 不声称「模型已处理」——只说明已发到本机另一个会话（目标在安全轮界自行排队执行）。
                //   自投递（主会话 → 自己）已在 routeTextMessage 直接拒绝，这里没有「自投递回执」。
                StringBuilder message = new StringBuilder()
                    .append("sent to ").append(replyableAddress(target))
                    .append(" \u2014 another Claude session on this machine")
                    .append('\n').append("message_id: ").append(receipt.messageId());
                if (receipt.idleSubscription() != null) {
                    message.append('\n').append("idle_subscription: ")
                        .append(receipt.idleSubscription());
                }
                return ToolResult.success(call.id(), message.toString());
            }
            case HELD:
                return ToolResult.error(call.id(), "Error: message held for approval (message_id="
                    + receipt.messageId() + "): " + receipt.detail());
            case REFUSED:
                return ToolResult.error(call.id(), "Error: message refused by the receiving session: "
                    + receipt.detail());
            case LOOP_REJECTED:
                return ToolResult.error(call.id(), "Error: hop-loop — message rejected to avoid a "
                    + "peer messaging loop: " + receipt.detail());
            case HOP_RUNAWAY:
                return ToolResult.error(call.id(), "Error: hop-runaway — message rejected because the "
                    + "peer relay chain is too long: " + receipt.detail());
            case NOT_WIRED:
                return ToolResult.error(call.id(),
                    "Error: cross-session delivery is not wired in this build");
            default:
                return ToolResult.error(call.id(), "Error: cross-session delivery failed ("
                    + receipt.status() + "): " + receipt.detail());
        }
    }

    /**
     * 发送前两道 ref 门 · 对齐 CC 2.1.328（顺序 = pin 先、冒充后）。
     *
     * <ol>
     *   <li><b>pin</b>（exe 356121546 {@code Lt} / 356149269）：写入地址的方括号 ref 是 pinned 形态
     *       （{@code session_}/{@code cse_}/{@code local_}，CC {@code $Ut}）时，该 ref 必须是本会话
     *       ListAgents <b>实际打印过</b>的行，且写入地址要逐字等于 {@code <打印的名字> [<ref>]}。
     *       否则原文：{@link PeerAddressing#PIN_FAILURE}。短 hex ref（6..12）不走这条。
     *       <p>CC 原文：{@code if(Lt(Is(v,o.toolUseId)?.writtenTo,e.to,o.session)) return {data:{success:!1,
     *       message:`Not sent: ${Su} has not listed that session id in this session, or last listed it
     *       under another name. Call ${Su} and copy the name and brackets as printed.`}}}（{@code Su="ListAgents"}）。</li>
     *   <li><b>冒充</b>（exe 355937466 {@code CKn}/{@code RKn}）：写入 ref 命中本进程自身广告的
     *       session 身份（{@code oxi(ref, ownMessagingSocket)}），但解析出的目标不是自己
     *       （{@code !Apn(sock)}）⇒ 原文 {@link PeerAddressing#IMPERSONATION_FAILURE_TEMPLATE}。</li>
     * </ol>
     *
     * @param target          已解析的收件目标（{@code raw()} = 模型写入的原始 to）
     * @param sourceSessionId 发送方会话
     * @return 失败原文；null = 放行
     */
    private String preSendGateFailure(MessageRecipientResolver.Target target, String sourceSessionId) {
        if (target == null) {
            return null;
        }
        String writtenTo = target.raw();
        String pinnedRef = PeerAddressing.pinnedRef(writtenTo);
        if (pinnedRef != null) {
            // fail-closed：没有 pin 集合（未接线/新进程）时无法证明列过 ⇒ 拒。
            String listedName = refPins == null ? null : refPins.listedName(sourceSessionId, pinnedRef);
            String normalized = writtenTo == null ? "" : writtenTo.trim();
            if (listedName == null || !normalized.equals(listedName + " [" + pinnedRef + "]")) {
                return PeerAddressing.PIN_FAILURE;
            }
        }
        if (PeerAddressing.advertisesSelf(writtenTo, sourceSessionId)) {
            String resolvedSessionId = target.sessionId();
            if (resolvedSessionId != null && !resolvedSessionId.equals(sourceSessionId)) {
                return PeerAddressing.impersonationFailure(writtenTo);
            }
        }
        return null;
    }

    /**
     * 跨会话成功回执里的目标地址 · 与 ListAgents 的「可回信地址」同形
     * （{@code name}，有 ref 时 {@code name [ref]}）——模型可原样回填 {@code to}。
     *
     * @param target 解析出的收件目标
     * @return 可回信地址（永不返回 null）
     */
    private static String replyableAddress(MessageRecipientResolver.Target target) {
        String name = target.displayName();
        if (name == null || name.isBlank()) {
            name = target.sessionId();
        }
        if (name == null || name.isBlank()) {
            name = target.raw();
        }
        String ref = target.ref();
        if (ref != null && !ref.isBlank()) {
            return name + " [" + ref + "]";
        }
        return name == null ? "" : name;
    }

    /**
     * 当前回合的入站 hop 链（无 peer 来源 → 空链）。
     *
     * @param ctx 工具上下文（可为 null）
     * @return 不可变 prior 链
     */
    private static List<String> inboundHopChain(ToolUseContext ctx) {
        if (ctx == null) {
            return List.of();
        }
        com.nexusai.application.agent.messaging.PeerExecutionOrigin origin = ctx.peerExecutionOrigin();
        if (origin == null || origin.hopChain() == null) {
            return List.of();
        }
        return origin.hopChain();
    }

    /**
     * 结构化消息发往跨会话地址 → 明确 Error（禁止把 shutdown/plan 当用户批复）。
     *
     * @return 错误结果；非跨会话地址 → null
     */
    private AgentToolResult rejectStructuredCrossSession(ToolUseBlock call, String to,
                                                         ToolUseContext ctx) {
        // 顺序：先判「发给自身」（主会话 → 自己的地址 ⇒ CC 原文，零投递/零 mailbox），
        // 再判普通跨会话地址。子代理 / teammate 发回主会话不属于「自身」分支。
        if (recipientResolver != null) {
            String sourceSessionId = ctx != null ? ctx.sessionId() : null;
            String teamName = resolveTeamName(ctx);
            MessageRecipientResolver.Target target = recipientResolver.resolve(
                new MessageRecipientResolver.Request(to, sourceSessionId, teamName,
                    teamName != null && !teamName.isBlank(),
                    teamMemberNames(teamName, ctx), senderAgentId(ctx)));
            if (target.selfSent()) {
                return selfAddressError(call, to);
            }
            if (target.kind() == MessageRecipientResolver.Kind.NAME_SESSION
                || target.kind() == MessageRecipientResolver.Kind.SESSION
                || target.kind() == MessageRecipientResolver.Kind.MAIN
                || target.kind() == MessageRecipientResolver.Kind.UNSUPPORTED_REMOTE) {
                return ToolResult.error(call.id(),
                    "Error: structured messages cannot be sent to a cross-session address");
            }
            if (target.kind() == MessageRecipientResolver.Kind.AMBIGUOUS) {
                return ToolResult.error(call.id(), "Error: " + target.errorMessage());
            }
            return null;
        }
        if (isSelfAddressWithoutResolver(to, ctx)) {
            return selfAddressError(call, to);
        }
        PeerAddressing.Kind kind = PeerAddressing.classify(to).kind();
        if (kind == PeerAddressing.Kind.SESSION
            || kind == PeerAddressing.Kind.MAIN
            || kind == PeerAddressing.Kind.NEXUS_SESSION) {
            return ToolResult.error(call.id(),
                "Error: structured messages cannot be sent to a cross-session address");
        }
        return null;
    }

    /** agent 是否仍在运行（taskId === agentId）。无法判定 → 视为运行中（保持旧的入队行为）。 */
    private boolean isAgentRunning(String agentId) {
        if (backgroundTaskRunner == null || agentId == null) {
            return true;
        }
        return backgroundTaskRunner.getTask(agentId)
            .map(t -> t.status()
                == com.nexusai.application.agent.tasks.BackgroundTaskStatus.RUNNING)
            .orElse(false);
    }

    /**
     * 已停止 agent 的 resume · 对齐 CC「a send resumes it from its transcript」。
     *
     * <p><b>失败分档（五档，⛔ 不压成一句 {@code Could not resume}）</b>：
     * <ul>
     *   <li>v9（already running / resuming）→ <b>软化</b>：不报错，把消息挂到对方下一轮
     *       {@code "<name> is already waking; message queued for its next tool round
     *       (not delivered if the agent turns out to have been stopped)."}</li>
     *   <li>t2e → {@code still_stopping}（含「调用方自己被停」文案）；</li>
     *   <li>k9 → {@code not_reachable}（k9 原文）；</li>
     *   <li>transcriptMissing → {@code no_transcript}；</li>
     *   <li>其他 → {@code resume_failed}。</li>
     * </ul>
     * 回执字段 {@code degradedClass} 与 CC 遥测档同名（{@code Ks}，偏移 356128040）。
     */
    private AgentToolResult resumeStoppedAgent(ToolUseBlock call, String to, String agentId,
                                               String content, String summary, ToolUseContext ctx,
                                               boolean declareOnFinish) {
        if (resumeServiceProvider == null) {
            return null;
        }
        com.nexusai.application.agent.subagent.ResumeService resume =
            resumeServiceProvider.getIfAvailable();
        if (resume == null) {
            return null;
        }
        String resumeKey =
            com.nexusai.application.agent.subagent.ResumeService.parseAgentIdString(agentId);
        String sessionId = ctx != null ? ctx.sessionId() : null;
        if (resumeKey == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        // t2e 的另一半：调用方自己已请求停止且尚未退出 → 它不能再恢复别人（CC 原文逐字）。
        String callerAgentId = ctx != null && ctx.agentId() != null ? ctx.agentId().toString() : null;
        if (callerAgentId != null && backgroundTaskRunner != null
                && backgroundTaskRunner.isStopPending(callerAgentId)) {
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                .callerStoppedText(), "still_stopping");
        }
        try {
            // 模型发起的自动 resume（非用户显式）：用户 TaskStop 过的 agent 会被 stoppedByUser 门拒绝
            // （对齐 CC J=false → AgentStoppedByUserError）。
            java.nio.file.Path sessionDir = resolveSessionDirQuiet(sessionId);
            if (sessionDir == null) {
                sessionDir = com.nexusai.application.agent.subagent.ResumeService
                    .resolveSessionDir(sessionId);
            }
            // [lineage] 血统由 ResumeService 按「发起方 agentId + 会话用量账本里的父子链」现算
            //   （对齐 CC 偏移 333967189：lineage 存在 → 跳过 stoppedByUser 探测与 k9）。
            //   ⛔ callerAgentId 来自 ToolUseContext（orchestrator 盖章），不由工具入参自报。
            resume.resumeAgentBackgroundForLineage(agentId, content, sessionDir, sessionId, null, false,
                callerAgentId);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("success", true);
            out.put("resumedAgentId", agentId);
            out.put("revivedAgentId", agentId);
            // CC 成功文案逐字：`Resuming agent <name7>`（ws() 把名字截 7 位，偏移 356128040）。
            out.put("message", "Resuming agent " + shortAgentName(to)
                + (declareOnFinish ? RESUME_ON_FINISH_HINT : ""));
            log.info("[SendMessageTool] [agent-resume-align] 已停止子代理按名热启动: to={} agentId={}",
                to, agentId);
            return ToolResult.success(call.id(), out);
        } catch (com.nexusai.application.agent.subagent.ResumeErrorCode.ResumeTranscriptMissingException e) {
            log.warn("[SendMessageTool] resume 无 transcript to={} agentId={}: {}", to, agentId, e.getMessage());
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                .transcriptMissingText(to, agentId), "no_transcript");
        } catch (com.nexusai.application.agent.subagent.ResumeErrorCode.ResumeException e) {
            return failureForResumeError(call, to, agentId, content, summary, ctx, e);
        } catch (Exception e) {
            log.warn("[SendMessageTool] resume 已结束子代理失败 to={} agentId={}: {}", to, agentId, e.toString());
            return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                .notResumedText(to, e.getMessage()), "resume_failed");
        }
    }

    /** 按 {@link com.nexusai.application.agent.subagent.ResumeErrorCode} 分档（v9 走软化入队）。 */
    private AgentToolResult failureForResumeError(ToolUseBlock call, String to, String agentId,
                                                  String content, String summary, ToolUseContext ctx,
                                                  com.nexusai.application.agent.subagent.ResumeErrorCode.ResumeException e) {
        com.nexusai.application.agent.subagent.ResumeErrorCode code = e.errorCode();
        log.warn("[SendMessageTool] resume 分档失败 to={} agentId={} code={} detail={}",
            to, agentId, code, e.getMessage());
        switch (code) {
            case AGENT_STOPPED_BY_USER:
                return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                    .stoppedByUserSendText(to), "not_reachable");
            case SUBAGENT_RESUME_STILL_STOPPING:
                return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                    .stillStoppingText(agentId), "still_stopping");
            case SESSION_FULL:
                return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                    .sessionFullText(to), "session_full");
            case SUBAGENT_RESUME_ALREADY_RUNNING: {
                // v9 软化：CC 捕获 v9 后不报错，改投 pendingMessages（下一轮工具轮消费），
                //   并明确「若它其实已被停则不投递」。
                String sender = resolveSenderName(ctx);
                String origin = Teammate.isTeammate(identityOf(ctx)) ? "peer" : "coordinator";
                agentNameRegistry.queue(agentId, ctx != null ? ctx.sessionId() : null,
                    sender, content, summary, origin);
                ObjectNode out = JsonNodeFactory.instance.objectNode();
                out.put("success", true);
                out.put("message", com.nexusai.application.agent.subagent.ResumeErrorCode
                    .alreadyWakingText(to));
                return ToolResult.success(call.id(), out);
            }
            case SUBAGENT_RESUME_RESUMES_SPENT:
                return failure(call, e.getMessage(), "resume_failed");
            default:
                return failure(call, com.nexusai.application.agent.subagent.ResumeErrorCode
                    .notResumedText(to, e.getMessage()), "resume_failed");
        }
    }

    /** CC {@code ws()}：a+16hex 截 7 位，其余原样。 */
    private static String shortAgentName(String name) {
        if (name == null) {
            return "";
        }
        return name.matches("a[0-9a-fA-F]{16}") ? name.substring(0, 7) : name;
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
        String senderName = resolveSenderName(ctx);
        String senderColor = Teammate.getTeammateColor(identityOf(ctx));

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
     * @param to       收件名（单播 = 收件 agent 名）
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
        String senderName = Teammate.getAgentName(identityOf(ctx)) != null
                ? Teammate.getAgentName(identityOf(ctx))
                : SwarmConstants.TEAM_LEAD_NAME;
        String requestId = "shutdown-" + System.currentTimeMillis() + "@" + targetName;

        TeammateMailbox.ShutdownRequestMessage shutdownMsg =
                TeammateMailbox.createShutdownRequestMessage(requestId, senderName, reason);
        try {
            TeammateMailbox.writeToMailbox(targetName,
                    new TeammateMailbox.TeammateMessage(senderName,
                            TeammateMailbox.toCompactJson(shutdownMsg),
                            TeammateMailbox.isoNow(), false,
                            Teammate.getTeammateColor(identityOf(ctx)), null),
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
     * success（:392-398，不失败）。
     *
     * <p><b>[P0-6 · D1①] 身份来源改显式 ctx</b>：CC 用 {@code getAgentId()/getAgentName()}
     *（SendMessageTool.ts:338-343,388），**不解析 request_id**。原实现只解析 request_id，而本仓
     * request_id 由 TeamDeleteTool 生成为 {@code "shutdown-{member}-{ts}"}（**无 `@`**）⇒
     * {@link AgentIdFormatter#parseRequestId} 必返回 null ⇒ 回落字面量 "teammate" ⇒
     * {@code findByAgentName("teammate")} 必落空 ⇒ <b>永不 abort</b>（「关机」只是封信）。
     * request_id 反解<b>降级为 ctx 无身份时的兜底并保留</b>（测试/无身份路径仍可定位）。
     *
     * @param requestId shutdown_response 回显的 request_id（ctx 无身份时的兜底线索）
     */
    private AgentToolResult handleShutdownApproval(ToolUseBlock call, String requestId, ToolUseContext ctx) {
        // [P0-6 · D1①] 主用显式身份（ctx.teammateIdentity()）；反解仅作兜底。
        ShutdownActor actor = resolveShutdownActor(ctx, requestId);
        String agentName = actor.agentName();
        String agentId = actor.agentId();
        String teamName = resolveTeamName(ctx);
        // 1. 先写确认到 team-lead mailbox（CC :330-346 createShutdownApprovedMessage + writeToMailbox）
        try {
            TeammateMailbox.ShutdownApprovedMessage approved =
                    TeammateMailbox.createShutdownApprovedMessage(requestId, agentName, null, null);
            TeammateMailbox.writeToMailbox(SwarmConstants.TEAM_LEAD_NAME,
                    new TeammateMailbox.TeammateMessage(agentName,
                            TeammateMailbox.toCompactJson(approved),
                            TeammateMailbox.isoNow(), false,
                            Teammate.getTeammateColor(identityOf(ctx)), null),
                    teamName);
            log.info("[SendMessageTool] shutdown_approved 写入 team-lead mailbox requestId={} from={}",
                    requestId, agentName);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("[SendMessageTool] shutdown_approved 写入失败 requestId={}: {}", requestId, e.getMessage());
        }
        // 2. 找 in-process teammate 任务并 abort 生命周期控制器（CC :353-365）
        // [P0-6 · D1①] 定位主用 registry.findByAgentId(agentId)（与 CC
        //   InProcessTeammateTask.tsx:126-146 findTeammateTaskByAgentId 同键 = identity.agentId）；
        //   顺带消掉 findByAgentName 的 equalsIgnoreCase **裸名匹配**误伤 —— registry 是进程级
        //   单例 map，裸名可命中别的 team/会话的同名 teammate。ctx 无身份时降级裸名兜底。
        AutonomousAgentLoop loop = findTeammateLoop(agentId, agentName);
        if (loop != null && loop.taskState() != null && loop.taskState().abortController() != null) {
            loop.taskState().abortController().abort();
            log.info("[SendMessageTool] shutdown approve: abort 生命周期控制器 agent={} agentId={}"
                    + "（in-process teammate 退出）", agentName, agentId);
        } else {
            log.warn("[SendMessageTool] shutdown approve: 未找到 in-process teammate 任务/abortController "
                    + "agent={} agentId={}（CC :362-364 同语义，不失败）", agentName, agentId);
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
        // [P0-6 · D1③/同根因同修] 与 handleShutdownApproval 用**同一**身份来源 + 同一 requestId 兜底
        //   （CC SendMessageTool.ts:434-439 同款）。原实现只解析 request_id ⇒ 同一断链（回落到
        //   字面量 "teammate"）⇒ team-lead 邮箱里的 shutdown_rejected 的 from/message 是 "teammate"
        //   而不是真实成员名。
        ShutdownActor actor = resolveShutdownActor(ctx, requestId);
        String agentName = actor.agentName();
        String teamName = resolveTeamName(ctx);
        try {
            TeammateMailbox.ShutdownRejectedMessage rejected =
                    TeammateMailbox.createShutdownRejectedMessage(requestId, agentName, reason);
            TeammateMailbox.writeToMailbox(SwarmConstants.TEAM_LEAD_NAME,
                    new TeammateMailbox.TeammateMessage(agentName,
                            TeammateMailbox.toCompactJson(rejected),
                            TeammateMailbox.isoNow(), false,
                            Teammate.getTeammateColor(identityOf(ctx)), null),
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
        if (!isTeamLead(ctx, resolveTeamName(ctx))) {
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
        if (!isTeamLead(ctx, resolveTeamName(ctx))) {
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
     * 委托 {@link Teammate#isTeamLead(TeammateIdentity, String)} 单一真源。
     *
     * @param teamName 目标 team（CC teamContext.teamName 等价），null → 非 lead
     * @return true=team lead（可批准/拒绝 plan）；false=非 lead
     */
    private boolean isTeamLead(ToolUseContext ctx, String teamName) {
        if (teamName == null || teamName.isBlank()) {
            // CC :178-180 !teamContext?.leadAgentId → false（无 team context 恒非 lead）
            return false;
        }
        // [S1-T6] 身份改读显式 TUC 载体（原读 ThreadLocal）。
        return Teammate.isTeamLead(identityOf(ctx), teamHelpers.leadAgentId(teamName));
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
     * shutdown 决策主体（approve / reject 共用）· [P0-6 · D1①] 身份来源单点化。
     *
     * <p>WHY 单一来源：approve 与 reject 是同一能力的两个分支，若各自实现身份解析就会重演本仓
     * 「同一能力两套判据」的历史缺陷 —— 一处修好、另一处仍哑。
     *
     * @param agentId   全形 agentId（name@team）；仅 ctx 有身份时非 null
     * @param agentName 裸 agent 名（恒非 null；ctx 无身份时为 request_id 反解值或字面量 "teammate"）
     */
    private record ShutdownActor(String agentId, String agentName) {}

    /**
     * [P0-6 · D1①] 解析 shutdown 决策主体 —— <b>显式 ctx 身份优先，request_id 反解降级为兜底</b>。
     *
     * <p>对齐 CC：{@code SendMessageTool.ts:338-343,388} 用 {@code getAgentId()/getAgentName()}，
     * <b>不解析 request_id</b>。本仓保留反解仅覆盖 ctx 无身份的历史/测试路径（否则该路径变哑）。
     */
    private ShutdownActor resolveShutdownActor(ToolUseContext ctx, String requestId) {
        TeammateIdentity self = identityOf(ctx);
        String agentName = self != null ? self.agentName() : null;
        String agentId = self != null ? self.agentId() : null;
        if (agentName == null || agentName.isBlank()) {
            agentName = extractAgentNameFromRequestId(requestId);
            agentId = null;
            if (log.isDebugEnabled()) {
                log.debug("[SendMessageTool] shutdown 决策身份：ctx 无身份，降级 request_id 反解 agent={}（requestId={}）",
                        agentName, requestId);
            }
        }
        if (agentName == null) {
            agentName = "teammate";
        }
        return new ShutdownActor(agentId, agentName);
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
     *
     * <p>[P0-6 · D1①] 主用 {@code findByAgentId(agentId)}（CC 同键 = {@code identity.agentId}）；
     * agentId 为 null（ctx 无身份）时才降级到裸名 {@code findByAgentName(agentName)}。
     *
     * @param agentId   全形 agentId（name@team）；ctx 无身份时为 null
     * @param agentName 裸 agent 名（兜底键）
     */
    private AutonomousAgentLoop findTeammateLoop(String agentId, String agentName) {
        if (spawnInProcess == null) {
            return null;
        }
        InProcessTeammateTaskRegistry registry = spawnInProcess.registry();
        if (registry == null) {
            return null;
        }
        if (agentId != null && !agentId.isBlank()) {
            Optional<AutonomousAgentLoop> byId = registry.findByAgentId(agentId);
            if (byId.isPresent()) {
                return byId.get();
            }
            if (log.isDebugEnabled()) {
                log.debug("[SendMessageTool] findTeammateLoop: findByAgentId 未命中，降级裸名兜底 agentId={} agent={}",
                        agentId, agentName);
            }
        }
        if (agentName == null) {
            return null;
        }
        return registry.findByAgentName(agentName).orElse(null);
    }

    /**
     * sender 名派生 · 对齐 CC SendMessageTool.ts:157-158/:210-211
     * {@code getAgentName() || (isTeammate() ? 'teammate' : TEAM_LEAD_NAME)}。
     * 删除旧 {@code from} 输入（⊕-07）与 {@code "lead@default"} 回退（旧 resolveFrom）。
     */
    private String resolveSenderName(ToolUseContext ctx) {
        // [S1-T6] 身份改读显式 TUC 载体（原读 ThreadLocal：工具执行池线程恒 null ⇒ 恒走
        //   TEAM_LEAD_NAME 分支）。ctx 可为 null → identity null（与旧缺失语义等价）。
        TeammateIdentity identity = identityOf(ctx);
        String agentName = Teammate.getAgentName(identity);
        if (agentName != null && !agentName.isBlank()) {
            return agentName;
        }
        return Teammate.isTeammate(identity) ? "teammate" : SwarmConstants.TEAM_LEAD_NAME;
    }

    /**
     * [S1-T6] 从工具上下文取 teammate 身份（null-safe）· 唯一取值点（防多处各自解引用漂移）。
     */
    private static TeammateIdentity identityOf(ToolUseContext ctx) {
        return ctx != null ? ctx.teammateIdentity() : null;
    }

    /**
     * 团队名册收件人集合 · 对齐 CC resolver 的 mailbox 前置校验（exe `me(...)`：名册命中才 mailbox）。
     *
     * <p>来源三处合并：{@code teamContext.teammates}（会话列 / 同轮 appState）、team config
     * members（{@link TeamHelpers#listMemberNames}）、{@code team-lead}（CC 对 {@code n===nd}
     * 直接走 mailbox）。无 team → 空列表（不存在可写的 inbox）。
     *
     * @param teamName team 名（null/空白 → 空）
     * @param ctx      工具上下文
     * @return 名册名（保持发现顺序，去重）
     */
    @SuppressWarnings("unchecked")
    private List<String> teamMemberNames(String teamName, ToolUseContext ctx) {
        if (teamName == null || teamName.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        names.add(SwarmConstants.TEAM_LEAD_NAME);
        Map<String, Object> teamContext = readTeamContext(ctx);
        if (teamContext != null) {
            Object mates = teamContext.get("teammates");
            if (mates instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value instanceof Map<?, ?> member) {
                        Object name = member.get("name");
                        if (name instanceof String s && !s.isBlank() && !names.contains(s)) {
                            names.add(s);
                        }
                    }
                }
            }
        }
        try {
            for (String member : teamHelpers.listMemberNames(teamName)) {
                if (member != null && !member.isBlank() && !names.contains(member)) {
                    names.add(member);
                }
            }
        } catch (Exception e) {
            log.warn("[SendMessageTool] 读团队名册失败 team={}（按已收集项继续校验）: {}",
                teamName, e.getMessage());
        }
        return names;
    }

    /** 收件名是否命中团队名册（归一化比较，与 {@code Request#hasTeamMember} 同规则）。 */
    private static boolean memberHit(List<String> memberNames, String to) {
        if (memberNames == null || memberNames.isEmpty() || to == null || to.isBlank()) {
            return false;
        }
        String normalized = PeerAddressing.normalizeName(to);
        for (String member : memberNames) {
            if (member != null && PeerAddressing.normalizeName(member).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 未命中收件人回执 · 对齐 CC exe not-found 分支：
     * {@code No agent named 'X' is reachable. Did you mean: a, b?}；无候选时给拼写/后台 agent id 指引。
     * 候选池 = 团队名册 + 本会话已注册子代理名（共用 {@link MessageRecipientResolver#closestNames}）。
     */
    private String noAgentNamed(String to, List<String> teamMemberNames, ToolUseContext ctx) {
        List<String> pool = new ArrayList<>();
        if (teamMemberNames != null) {
            pool.addAll(teamMemberNames);
        }
        if (agentNameRegistry != null) {
            String owner = ctx != null ? ctx.sessionId() : null;
            for (var entry : agentNameRegistry.entriesForOwner(owner)) {
                pool.add(entry.name());
            }
        }
        List<String> candidates = MessageRecipientResolver.closestNames(to, pool, 5);
        if (candidates.isEmpty()) {
            return "No agent named '" + to + "' is reachable. "
                + "Check the spelling, or use the agent ID from a background agent's spawn result.";
        }
        return "No agent named '" + to + "' is reachable. Did you mean: "
            + String.join(", ", candidates) + "?";
    }

    /**
     * team 名解析 · 对齐 CC {@code getTeamName(appState.teamContext)}（SendMessageTool.ts:156）
     * 与 teammate.ts:111-118（in-process &gt; dynamic &gt; teamContext.teamName）。
     *
     * <p><b>IMP-G2 ⊕-10</b>：删除旧 {@code team-{sessionId前8}} 合成（defaultTeamName）；
     * Java 等价 {@link Teammate#getTeamName(TeammateIdentity, String)}（teamContext.teamName 经
     * {@code ctx.getAppState()} 读取）。无任何 team context → null（mailbox 回退 'default'）。
     *
     * @param ctx 工具调用上下文（可为 null）
     * @return team 名；无 → null
     */
    private String resolveTeamName(ToolUseContext ctx) {
        // [S1-T6] 身份改读显式 TUC 载体（原读 ThreadLocal）。
        return Teammate.getTeamName(identityOf(ctx), teamNameFromContext(ctx));
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
