package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeamHelpers;
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

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // configHome 指向临时目录: {configHome}/teams/{team}/inboxes/{agent}.json
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
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
        return ToolUseContext.of(UUID.randomUUID(), "");
    }

    /** 带 appState 桥的 ToolUseContext · SendMessage 经 getTeamName(appState.teamContext) 取 team 名。 */
    private ToolUseContext teamCtx(Map<String, Object> appState) {
        return ToolUseContext.of(
                UUID.randomUUID(), "",
                PermissionMode.DEFAULT,
                java.util.List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP,
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
        tc.put("teammates", new LinkedHashMap<>());
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
    @DisplayName("validateInput: 结构化消息 to=\"*\" 禁广播 → 拒绝（CC :678-684）")
    void validateInput_rejectsStructuredBroadcast() {
        // WHY: CC :678-684 结构化消息（shutdown/plan 决策）不可广播，必须单播到指定 teammate。
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "*");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_request");
        msg.put("reason", "wrap up");
        Tool.ValidationResult result = tool.validateInput(input, plainCtx());
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("9");
        assertThat(result.message()).contains("structured messages cannot be broadcast");
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
    @DisplayName("broadcast: 经 teamFile.members 枚举收件人（排除 sender），旧 member_* 恒空死代码删除（⊕-12）")
    void broadcast_usesTeamFileMembers() throws Exception {
        // WHY: CC handleBroadcast :220-226 recipients = teamFile.members 排除 sender（大小写不敏感）。
        //      旧 Java listMembers 枚举 member_* 文件（全仓无写入方 → 广播恒空，功能不可用）；
        //      IMP-G2 改按 config.json members 数组 → 广播真正按团队配置投递。
        Map<String, Object> appState = teamContext();
        writeTeamConfig("team-lead", "mate"); // 广播收件人 = members 排除 sender(team-lead) → [mate]

        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "*");
        input.put("message", "all hands");
        input.put("summary", "all hands");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(out.get("recipients").toString()).contains("mate");

        // mate 收到广播，sender(team-lead) 被排除（CC :222-224）
        assertThat(inboxPath("mate")).exists();
        JsonNode mateMessages = json.readTree(Files.readString(inboxPath("mate")));
        assertThat(mateMessages).hasSize(1);
        assertThat(mateMessages.get(0).get("text").asText()).isEqualTo("all hands");
        assertThat(inboxPath("team-lead")).doesNotExist();
    }

    @Test
    @DisplayName("broadcast: 无成员可广播 → recipients:[]（CC :228-236），不写任何 inbox")
    void broadcast_noRecipients_returnsEmpty() throws Exception {
        Map<String, Object> appState = teamContext();
        writeTeamConfig("team-lead"); // 仅 sender 自己

        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "*");
        input.put("message", "anyone?");
        input.put("summary", "anyone?");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).contains("No teammates to broadcast to");
        assertThat(out.get("recipients").isEmpty()).isTrue();
        assertThat(inboxPath("team-lead")).doesNotExist();
    }

    @Test
    @DisplayName("broadcast: 无 team context → 拒绝（CC :199-203）")
    void broadcast_withoutTeamContext_rejected() {
        // WHY: CC :199-203 广播必须处于 team context（teamName 必填）——否则不知投递哪个 team。
        // 注：isToolErrorData 前缀表（LlmAgentLoop.java:9515-9560）不含 "Not in a team context"——
        //   拒绝经「消息内容 + 不写任何 inbox」双断言验证（意图：无 team 时广播被拦截）。
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "*");
        input.put("message", "anyone?");
        input.put("summary", "anyone?");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), plainCtx());
        assertThat(result.data()).asString().contains("Not in a team context");
        assertThat(inboxPath("team-lead")).as("无 team 时广播不得写任何 inbox").doesNotExist();
    }

    @Test
    @DisplayName("plain 消息输出 {success, message, routing}，无 message_id/delivered_to/type/timestamp（⊕-11）")
    void plainMessage_outputHasCcRoutingShape() throws Exception {
        // WHY: CC handleMessage :175-187 输出 {success, message, routing:{sender, target, ...}}——
        //      旧 {message_id, delivered_to, type, timestamp}（Java-only）删除。
        Map<String, Object> appState = teamContext();
        SendMessageTool tool = newTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "mate");
        input.put("message", "status update");
        input.put("summary", "status");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo("Message sent to mate's inbox");
        assertThat(out.has("message_id")).as("旧 message_id 字段应删除").isFalse();
        assertThat(out.has("delivered_to")).as("旧 delivered_to 字段应删除").isFalse();
        assertThat(out.has("type")).as("旧 type 字段应删除").isFalse();
        assertThat(out.has("timestamp")).as("旧 timestamp 字段应删除").isFalse();
        JsonNode routing = out.get("routing");
        assertThat(routing.get("sender").asText()).isEqualTo("team-lead");
        assertThat(routing.get("target").asText()).isEqualTo("@mate");
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
        mate.put("name", "mate");
        mate.put("color", "#ff0000");
        mates.put("mate@" + TEAM, mate);
        tc.put("teammates", mates);
        when(sessionService.getTeamContext(anyString())).thenReturn(tc);

        SendMessageTool tool = newTool();
        ReflectionTestUtils.setField(tool, "sessionService", sessionService);
        writeTeamConfig("team-lead", "mate");

        ObjectNode input = json.createObjectNode();
        input.put("to", "mate");
        input.put("message", "from store");
        input.put("summary", "from store");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(new LinkedHashMap<>()));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        // mate 收到消息且路由到 TEAM 的 inbox（store teamName 生效）
        assertThat(inboxPath("mate")).exists();
        JsonNode mateMessages = json.readTree(Files.readString(inboxPath("mate")));
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
        input.put("to", "mate");
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
        assertThat(evt.to()).isEqualTo("mate");
        assertThat(evt.text()).isEqualTo("status update");
        assertThat(evt.summary()).isEqualTo("status");
    }

    @Test
    @DisplayName("[B1] handleBroadcast 写 inbox 后推一条 to=\"*\" 广播事件（按 lead 会话 topic）")
    void broadcast_publishesSingleWildcardEvent() throws Exception {
        // WHY: 广播为团队级事件（to=\"*\"）——前端团队消息流渲染一条广播而非每收件人一条。
        //   [stomp-lead-session 方案 3] topic 按 lead 会话：/topic/sessions/{leadSessionId}/team-messages。
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

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        ArgumentCaptor<TeammateMessageEvent> captor = ArgumentCaptor.forClass(TeammateMessageEvent.class);
        verify(ws).convertAndSend(eq("/topic/sessions/sess-1/team-messages"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("teammate.message");
        assertThat(captor.getValue().to()).as("广播 to 必须为 *（团队广播语义）").isEqualTo("*");
        assertThat(captor.getValue().text()).isEqualTo("all hands");
        assertThat(captor.getValue().summary()).isEqualTo("all hands");
    }

    @Test
    @DisplayName("[B1] ws 未注入 → 跳过推送不抛（既有无 WebSocket 场景容错）")
    void publish_withoutWs_skipsWithoutNpe() throws Exception {
        // WHY: 无 WebSocket 场景（测试直构/headless）→ 工具主流程不因出站推送 NPE。
        Map<String, Object> appState = teamContext();
        writeTeamConfig("team-lead", "mate");
        SendMessageTool tool = newTool(); // 不注入 ws

        ObjectNode input = json.createObjectNode();
        input.put("to", "mate");
        input.put("message", "no ws");
        input.put("summary", "no ws");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // inbox 仍写入（B1 不改变既有落库语义）
        assertThat(inboxPath("mate")).exists();
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
        input.put("to", "mate");
        input.put("message", "no lead");
        input.put("summary", "no lead");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // 反查失败 → 不得推送任何 STOMP 事件（绝无回退全局 /topic/teams/...）·
        //   用 any(TeammateMessageEvent.class) 消解 Spring convertAndSend(String,Object) 双重载歧义
        verify(ws, never()).convertAndSend(anyString(), any(TeammateMessageEvent.class));
        // inbox 仍写入（跳过推送不影响既有 mailbox 投递语义）
        assertThat(inboxPath("mate")).exists();
    }
}
