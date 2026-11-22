package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.messaging.MessageRecipientResolver;
import com.nexusai.application.agent.messaging.PeerIdleSubscriptions;
import com.nexusai.application.agent.messaging.PeerInboundPolicy;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import com.nexusai.eventbus.ws.TeammateMessageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IMP-G2 · SendMessageTool 对齐 CC SendMessageTool.ts（组 6-2，TR-G3-⊕-07..12）聚焦测试。
 *
 * <p>WHY（规则九，验证意图）：
 * <ul>
 *   <li><b>message discriminatedUnion（⊕-08）</b>：CC {@code message} 为
 *       {@code z.union([string, StructuredMessage])}（SendMessageTool.ts:82-85）——旧扁平 8 值
 *       {@code type} 枚举删除；schema 必须表达 oneOf 且无顶层 {@code type/from} 平铺字段；</li>
 *   <li><b>name@team 拒绝（⊕-09）</b>：CC validateInput :623-630 拒绝 {@code @}——避免歧义
 *       （每 session 仅一 team），测试锁定 {@code validateInput} 语义层；</li>
 *   <li><b>string 消息 summary 必填（CC :667-676）</b>：UI 预览需要摘要；</li>
 *   <li><b>结构化禁广播（CC :678-684）</b>：shutdown/plan 决策必须单播，不可群发；</li>
 *   <li><b>广播经 teamFile.members（⊕-12）</b>：CC handleBroadcast :220-226 枚举
 *       {@code teamFile.members}（排除 sender）——旧 {@code member_*} 文件枚举无写入方恒空，
 *       广播从"不可用"变为"按团队配置可用"；</li>
 *   <li><b>输出契约（⊕-11）</b>：plain 消息输出 {@code {success, message, routing}}，不再有
 *       {@code message_id/delivered_to/type/timestamp}（CC :175-187）。</li>
 * </ul>
 */
@DisplayName("SendMessageTool IMP-G2 对齐（discriminatedUnion + teamFile.members 广播 + 校验）")
class SendMessageToolTest {

    private static final String TEAM = "research-team";

    /** 来源会话 id · 供子代理注册表 / peer 会话注册表按 owner 匹配。 */
    private static final String SELF = "sess-aaaaaaa1";

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // configHome 指向临时目录: {configHome}/teams/{team}/inboxes/{agent}.json
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        // 带 sessionId 的 ToolUseContext 会经 CwdResolution 解析 cwd；测试会话未入库 ⇒ 显式登记，
        // 避免构造器 fail-loud（与 TeamControllerTest 同款夹具）。
        SessionCwdHolder.set(SELF, tempDir.toAbsolutePath().normalize().toString());
    }

    @AfterEach
    void tearDown() {
        SessionCwdHolder.clear(SELF);
        TaskSystemConfig.clearForTest();
    }

    private Path inboxPath(String agent) {
        return tempDir.resolve("teams").resolve(TEAM).resolve("inboxes").resolve(agent + ".json");
    }

    private SendMessageTool newTool() {
        return new SendMessageTool(new TeamHelpers());
    }

    private ToolUseBlock block(String name, ObjectNode input) {
        return new ToolUseBlock(UUID.randomUUID().toString(), name, input);
    }

    private ToolUseContext plainCtx() {
        return ToolUseContext.of(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), "");
    }

    /** 带 appState 桥的 ToolUseContext · SendMessage 经 getTeamName(appState.teamContext) 取 team 名。 */
    private ToolUseContext teamCtx(Map<String, Object> appState) {
        return teamCtx(appState, "");
    }

    /** 同上，但带真实来源会话 id（结构类负例要按 owner 匹配子代理 / peer 会话）。 */
    private ToolUseContext teamCtx(Map<String, Object> appState, String sessionId) {
        return ToolUseContext.of(
                com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId,
                PermissionMode.DEFAULT,
                java.util.List.of(), "", AbortController.NOOP,
                java.util.List.of(), null, PermissionMode.DEFAULT, Map.of(),
                false, "", null, null, null, null,
                prev -> appState,
                updater -> {
                    Map<String, Object> next = updater.apply(appState);
                    appState.clear();
                    appState.putAll(next);
                },
                null, null);
    }

    private Map<String, Object> teamContext() {
        Map<String, Object> appState = new LinkedHashMap<>();
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("teamName", TEAM);
        tc.put("leadAgentId", "team-lead@" + TEAM);
        // [cc-notfound-align] mailbox 现在要求收件人命中团队名册（CC exe resolver）——
        //   单播测试的 "mate" 必须是真实成员，否则分支会按 not-found 拒绝。
        Map<String, Object> mate = new LinkedHashMap<>();
        mate.put("name", "mate");
        Map<String, Object> mates = new LinkedHashMap<>();
        mates.put("mate@" + TEAM, mate);
        tc.put("teammates", mates);
        appState.put("teamContext", tc);
        return appState;
    }

    /** 写 team config.json（CC TeamFile 形状）· 供广播 teamFile.members 枚举 + leadSessionId 反查。 */
    private void writeTeamConfig(String... memberNames) {
        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = json.createObjectNode();
        config.put("name", TEAM);
        config.put("createdAt", System.currentTimeMillis());
        config.put("leadAgentId", "team-lead@" + TEAM);
        // [stomp-lead-session 方案 3] 出站 topic 按 lead 会话推送 → config 需含 leadSessionId
        config.put("leadSessionId", "sess-1");
        com.fasterxml.jackson.databind.node.ArrayNode members = config.putArray("members");
        for (String name : memberNames) {
            ObjectNode member = members.addObject();
            member.put("agentId", name + "@" + TEAM);
            member.put("name", name);
            member.put("joinedAt", System.currentTimeMillis());
            member.put("tmuxPaneId", "");
            member.set("subscriptions", json.createArrayNode());
        }
        helpers.writeConfig(TEAM, config.toString());
    }

    @Test
    @DisplayName("inputSchema: message 为 oneOf union（string + 结构化），无顶层 from/type 平铺字段（⊕-07/08）")
    void inputSchema_messageIsUnion_noFlatFromOrType() {
        // WHY: CC SendMessageTool.ts:67-87 inputSchema = z.object({to, summary?, message: z.union([...])})——
        //      无顶层 from（⊕-07）、无扁平 type 枚举（⊕-08）；message 经 discriminatedUnion 3 种结构化。
        JsonNode schema = newTool().inputSchema();
        JsonNode props = schema.get("properties");
        assertThat(props.has("from")).as("旧 from 输入字段应删除（CC :67-87 无此键）").isFalse();
        assertThat(props.has("type")).as("旧扁平 type 枚举应删除（CC message 为 union）").isFalse();
        assertThat(props.has("message")).isTrue();
        JsonNode message = props.get("message");
        assertThat(message.has("oneOf")).as("message 必须是 z.union → oneOf").isTrue();
        JsonNode structured = message.get("oneOf").get(1);
        assertThat(structured.get("properties").get("type").get("enum").toString())
                .contains("shutdown_request", "shutdown_response", "plan_approval_response");
        assertThat(structured.get("required").toString()).contains("type");
        assertThat(schema.get("required").toString()).contains("to");
    }

    @Test
    @DisplayName("validateInput: to 含 @（name@team）→ 拒绝 errorCode 9（⊕-09）")
    void validateInput_rejectsNameAtTeam() {
        // WHY: CC :623-630 拒绝 @ ——每 session 仅一 team，name@team 地址语义歧义。
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "mate@" + TEAM);
        input.put("message", "hello");
        input.put("summary", "hello");
        Tool.ValidationResult result = tool.validateInput(input, plainCtx());
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("9");
        assertThat(result.message()).contains("bare teammate name");
    }

    @Test
    @DisplayName("validateInput: string 消息缺 summary → 拒绝 errorCode 9（CC :667-676）")
    void validateInput_requiresSummaryForString() {
        // WHY: CC :667-676 string 消息 summary 必填（UI 预览）；缺省则模型无法提供摘要预览。
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "mate");
        input.put("message", "hello");
        Tool.ValidationResult result = tool.validateInput(input, plainCtx());
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("9");
        assertThat(result.message()).isEqualTo("summary is required when message is a string");
    }

    @Test
    @DisplayName("validateInput: to=\"*\" 被硬拒（CC 2.1.328 原文），任何消息都不写 mailbox")
    void validateInput_rejectsBroadcast_withCcText() {
        // WHY: 2026-11-20 用户裁定「彻底删除 to:\"*\"」——对齐 CC 2.1.328 硬拒，只允许单一收件人。
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "*");
        input.put("message", "all hands");
        input.put("summary", "all hands");
        Tool.ValidationResult result = tool.validateInput(input, plainCtx());
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("9");
        assertThat(result.message()).isEqualTo(
                "broadcast (to: \"*\") is no longer supported — send a message per recipient");

        // 直接 execute 同样拒绝，且不产生任何 inbox（无 team 上下文也不猜收件人）。
        AgentToolResult<?> executed = tool.execute(block("SendMessage", input), plainCtx());
        assertThat(String.valueOf(executed.data())).isEqualTo(
                "broadcast (to: \"*\") is no longer supported — send a message per recipient");
        assertThat(inboxPath("team-lead")).doesNotExist();
        assertThat(inboxPath("mate")).doesNotExist();
    }

    @Test
    @DisplayName("validateInput: 结构化消息 + to=\"*\" 同样被拒（只能指定单一收件人）")
    void validateInput_rejectsBroadcastForStructuredMessage() {
        // WHY: shutdown/plan 决策必须单播 —— 广播删除后不因消息类型而放行。
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "*");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_request");
        msg.put("reason", "wrap up");
        Tool.ValidationResult result = tool.validateInput(input, plainCtx());
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("9");
        assertThat(result.message()).isEqualTo(
                "broadcast (to: \"*\") is no longer supported — send a message per recipient");

        // 直接 execute（结构性消息）也不得落任何 mailbox。
        AgentToolResult<?> executed = tool.execute(block("SendMessage", input), plainCtx());
        assertThat(String.valueOf(executed.data())).isEqualTo(
                "broadcast (to: \"*\") is no longer supported — send a message per recipient");
        assertThat(inboxPath("team-lead")).doesNotExist();
    }

    @Test
    @DisplayName("isReadOnly: string 消息 true / 结构化 false（CC :539-541）")
    void isReadOnly_matchesMessageType() {
        // WHY: CC :539-541 typeof input.message === 'string' → 只读可并发；结构化决策写 mailbox 非只读。
        SendMessageTool tool = newTool();
        ObjectNode stringInput = json.createObjectNode();
        stringInput.put("to", "mate");
        stringInput.put("message", "hello");
        assertThat(tool.isReadOnly(stringInput)).isTrue();

        ObjectNode structuredInput = json.createObjectNode();
        structuredInput.put("to", "team-lead");
        ObjectNode msg = structuredInput.putObject("message");
        msg.put("type", "shutdown_response");
        msg.put("approve", true);
        msg.put("request_id", "shutdown-1@mate");
        assertThat(tool.isReadOnly(structuredInput)).isFalse();
    }

    @Test
    @DisplayName("execute: to=\"*\" 即使有 team/peer/子代理也只返回拒绝、零投递（广播已删除）")
    void execute_broadcast_neverDeliversToAnyTarget() throws Exception {
        // WHY（2026-11-20 用户裁定）：广播能力彻底删除 ⇒ team 名册成员、跨会话 peer、子代理
        //   三类目标一个都不许因 "*" 被投递，且不落任何 mailbox / 不排队子代理消息。
        Map<String, Object> appState = teamContext();
        writeTeamConfig("team-lead", "mate", "peer-one", "worker-one");

        // 跨会话 peer（本机另一个会话）· 名字出现在 team 名册里也不得被广播命中
        PeerSessionRegistry sessions = new PeerSessionRegistry();
        sessions.register(new PeerSessionRegistry.Registration(
            "sess-ccccccc3", "peer-one", "/p", PermissionMode.DEFAULT, 1L));
        // 子代理（本会话注册）· 同样不得被广播命中
        AgentNameRegistry agents = new AgentNameRegistry();
        agents.register(SELF, "worker-one", "a1111111111111111");

        MessageRecipientResolver resolver = new MessageRecipientResolver();
        resolver.setPeerSessionRegistry(sessions);
        resolver.setAgentNameRegistry(agents);

        PeerInboundPolicy policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> "accept");
        PeerMessageRouter router = new PeerMessageRouter();
        router.setPeerSessionRegistry(sessions);
        router.setPeerInboundPolicy(policy);
        router.setIdleSubscriptions(new PeerIdleSubscriptions());
        AtomicInteger peerDeliveries = new AtomicInteger();
        router.setDeliveryPort((targetSessionId, epoch, envelope, body) -> {
            peerDeliveries.incrementAndGet();
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "stub", null);
        });

        SendMessageTool tool = newTool();
        tool.setRecipientResolver(resolver);
        tool.setPeerMessageRouter(router);
        tool.setAgentNameRegistry(agents);

        ObjectNode input = json.createObjectNode();
        input.put("to", "*");
        input.put("message", "all hands");
        input.put("summary", "all hands");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState, SELF));

        assertThat(String.valueOf(result.data())).isEqualTo(
                "broadcast (to: \"*\") is no longer supported — send a message per recipient");
        for (String name : java.util.List.of("mate", "team-lead", "peer-one", "worker-one")) {
            assertThat(inboxPath(name)).as("to=* 不得给 %s 写 mailbox", name).doesNotExist();
        }
        assertThat(peerDeliveries).as("to=* 不得投递给跨会话 peer").hasValue(0);
        assertThat(agents.hasPending("a1111111111111111"))
            .as("to=* 不得给子代理排队消息").isFalse();
    }

    @Test
    @DisplayName("plain 消息输出 {success, message, routing}，无 message_id/delivered_to/type/timestamp（⊕-11）")
    void plainMessage_outputHasCcRoutingShape() throws Exception {
        // WHY: CC handleMessage :175-187 输出 {success, message, routing:{sender, target, ...}}——
        //      旧 {message_id, delivered_to, type, timestamp}（Java-only）删除。
        Map<String, Object> appState = teamContext();
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        // [B2] 非 lead 名册成员改走代理队列（⛔ 不再写 inbox）——本用例只验证 handleMessage
        // 输出形状，收件人改用唯一仍走 mailbox 的 team-lead。
        input.put("to", "team-lead");
        input.put("message", "status update");
        input.put("summary", "status");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo("Message sent to team-lead's inbox");
        assertThat(out.has("message_id")).as("旧 message_id 字段应删除").isFalse();
        assertThat(out.has("delivered_to")).as("旧 delivered_to 字段应删除").isFalse();
        assertThat(out.has("type")).as("旧 type 字段应删除").isFalse();
        assertThat(out.has("timestamp")).as("旧 timestamp 字段应删除").isFalse();
        JsonNode routing = out.get("routing");
        assertThat(routing.get("sender").asText()).isEqualTo("team-lead");
        assertThat(routing.get("target").asText()).isEqualTo("@team-lead");
    }

    @Test
    @DisplayName("[cc-notfound-align] 收件名不在名册 → Error + Did you mean，且不写任何 inbox（CC not-found）")
    void plainMessage_unknownRecipient_failsLoud_withCandidate() {
        // WHY: CC exe 的 not-found 分支返回 success:false + `No agent named 'X' is reachable.
        //   Did you mean: …?`，⛔ 不落 inbox。旧实现「有 team 上下文就写 inbox」会把消息吞掉
        //   （实测：spawn 名 peer-inline-card / 发送名 inline-card → inbox 永久 read:false）。
        Map<String, Object> appState = teamContext();
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "mat");
        input.put("message", "hi");
        input.put("summary", "hi");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(String.valueOf(result.data()))
            .contains("No agent named 'mat'")
            .contains("mate");
        assertThat(inboxPath("mat")).as("名册外的收件人不得写 inbox").doesNotExist();
    }

    @Test
    @DisplayName("team 名/颜色从会话 store 读（A4：跨回合取 team 上下文，变异点：只读 no-op appState 则路由回退 default）")
    void plainMessage_readsTeamContextFromSessionStore() throws Exception {
        // WHY: [A4] SendMessageTool.ts:156 team 名从 teamContext 取——会话列承载后须从 store 读，
        //   TeamCreate 上一回合落列（LlmAgentLoop appStateRef 每轮重建，per-request no-op 读不到）
        //   → 跨回合发消息才能路由到正确 team inbox + teammate color。变异点：仍只读 appState →
        //   store 有 team 却路由回退 'default'（mailbox 兜底），跨回合消息投递断链。
        SessionService sessionService = mock(SessionService.class);
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("teamName", TEAM);
        tc.put("leadAgentId", "team-lead@" + TEAM);
        Map<String, Object> mates = new LinkedHashMap<>();
        Map<String, Object> mate = new LinkedHashMap<>();
        mate.put("name", "team-lead");
        mate.put("color", "#ff0000");
        mates.put("team-lead@" + TEAM, mate);
        tc.put("teammates", mates);
        when(sessionService.getTeamContext(anyString())).thenReturn(tc);

        SendMessageTool tool = newTool();
        ReflectionTestUtils.setField(tool, "sessionService", sessionService);
        writeTeamConfig("team-lead", "mate");

        // [B2] 非 lead 名册成员不再写 inbox ⇒ 用 team-lead 验证 store 团队名/颜色路由。
        ObjectNode input = json.createObjectNode();
        input.put("to", "team-lead");
        input.put("message", "from store");
        input.put("summary", "from store");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(new LinkedHashMap<>()));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        // team-lead 收到消息且路由到 TEAM 的 inbox（store teamName 生效）
        assertThat(inboxPath("team-lead")).exists();
        JsonNode mateMessages = json.readTree(Files.readString(inboxPath("team-lead")));
        assertThat(mateMessages).hasSize(1);
        assertThat(mateMessages.get(0).get("text").asText()).isEqualTo("from store");
        // color 从 store teamContext.teammates 读取（findTeammateColor store-first）
        JsonNode routing = out.get("routing");
        assertThat(routing.get("targetColor").asText()).isEqualTo("#ff0000");
    }

    // ═══════════════════════ [B1] 消息出站 topic（design doc §2.2 方案 A）═══════════════════════

    @Test
    @DisplayName("[B1] handleMessage 写 inbox 后推 /topic/sessions/{leadSessionId}/team-messages（type=teammate.message）")
    void plainMessage_publishesTeammateMessageEvent() throws Exception {
        // WHY: [B1] SendMessageTool 写 inbox 后须额外 convertAndSend（design doc §2.2 方案 A）——
        //   只进队长 LLM 无前端通道，消息流无法展示。变异点：不加出站 → 前端订阅不到 teammate 消息。
        //   [stomp-lead-session 方案 3] topic 按 lead 会话：/topic/sessions/{leadSessionId}/team-messages。
        Map<String, Object> appState = teamContext();
        writeTeamConfig("team-lead", "mate");
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SendMessageTool tool = newTool();
        tool.setWs(ws);

        ObjectNode input = json.createObjectNode();
        input.put("to", "team-lead");
        input.put("message", "status update");
        input.put("summary", "status");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        ArgumentCaptor<TeammateMessageEvent> captor = ArgumentCaptor.forClass(TeammateMessageEvent.class);
        verify(ws).convertAndSend(eq("/topic/sessions/sess-1/team-messages"), captor.capture());
        TeammateMessageEvent evt = captor.getValue();
        assertThat(evt.type()).as("出站事件 type 必须为 teammate.message").isEqualTo("teammate.message");
        assertThat(evt.teamName()).isEqualTo(TEAM);
        assertThat(evt.from()).as("sender = resolveSenderName()（无 teammate context → team-lead）").isEqualTo("team-lead");
        assertThat(evt.to()).isEqualTo("team-lead");
        assertThat(evt.text()).isEqualTo("status update");
        assertThat(evt.summary()).isEqualTo("status");
    }

    @Test
    @DisplayName("execute: to=\"*\" 不推任何 STOMP 事件（通配广播事件随广播能力一并删除）")
    void execute_broadcast_publishesNoWildcardEvent() throws Exception {
        // WHY: 原 handleBroadcast 会推一条 to="*" 的团队广播事件；广播删除后不得再出现任何
        //   "broadcast" 形态的出站事件（否则前端仍显示「已广播」而实际没人收到）。
        Map<String, Object> appState = teamContext();
        writeTeamConfig("team-lead", "mate");
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SendMessageTool tool = newTool();
        tool.setWs(ws);

        ObjectNode input = json.createObjectNode();
        input.put("to", "*");
        input.put("message", "all hands");
        input.put("summary", "all hands");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(String.valueOf(result.data())).isEqualTo(
                "broadcast (to: \"*\") is no longer supported — send a message per recipient");
        verify(ws, never()).convertAndSend(anyString(), any(TeammateMessageEvent.class));
        assertThat(inboxPath("mate")).doesNotExist();
    }

    @Test
    @DisplayName("[B1] ws 未注入 → 跳过推送不抛（既有无 WebSocket 场景容错）")
    void publish_withoutWs_skipsWithoutNpe() throws Exception {
        // WHY: 无 WebSocket 场景（测试直构/headless）→ 工具主流程不因出站推送 NPE。
        Map<String, Object> appState = teamContext();
        writeTeamConfig("team-lead", "mate");
        SendMessageTool tool = newTool(); // 不注入 ws

        ObjectNode input = json.createObjectNode();
        input.put("to", "team-lead");
        input.put("message", "no ws");
        input.put("summary", "no ws");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // inbox 仍写入（B1 不改变既有落库语义）
        assertThat(inboxPath("team-lead")).exists();
    }

    @Test
    @DisplayName("[stomp-lead-session 方案 3] config 缺 leadSessionId → 跳过推送不回退全局 topic（防跨会话泄漏），inbox 仍写")
    void publish_withoutLeadSessionId_skipsPush_andKeepsInboxWrite() throws Exception {
        // WHY: 反查 leadSessionId 失败时若回退全局 /topic/teams/{team}/messages，会被同 team 名的其它
        //   会话收到（多会话互收泄漏）——必须跳过推送（方案 3 裁决：绝不回退全局 topic）。
        //   变异点：回退全局 topic → 会话 B 收到会话 A 的 teammate 消息。
        Map<String, Object> appState = teamContext();
        // 写一个不含 leadSessionId 的 team config（合法遗留 config，缺该键）
        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = json.createObjectNode();
        config.put("name", TEAM);
        config.put("createdAt", System.currentTimeMillis());
        config.put("leadAgentId", "team-lead@" + TEAM);
        com.fasterxml.jackson.databind.node.ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", "team-lead@" + TEAM);
        lead.put("name", "team-lead");
        lead.put("joinedAt", System.currentTimeMillis());
        lead.put("tmuxPaneId", "");
        lead.set("subscriptions", json.createArrayNode());
        helpers.writeConfig(TEAM, config.toString());

        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SendMessageTool tool = newTool();
        tool.setWs(ws);

        ObjectNode input = json.createObjectNode();
        input.put("to", "team-lead");
        input.put("message", "no lead");
        input.put("summary", "no lead");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // 反查失败 → 不得推送任何 STOMP 事件（绝无回退全局 /topic/teams/...）·
        //   用 any(TeammateMessageEvent.class) 消解 Spring convertAndSend(String,Object) 双重载歧义
        verify(ws, never()).convertAndSend(anyString(), any(TeammateMessageEvent.class));
        // inbox 仍写入（跳过推送不影响既有 mailbox 投递语义）
        assertThat(inboxPath("team-lead")).exists();
    }
}
