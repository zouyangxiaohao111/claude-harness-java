package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.messaging.MessageRecipientResolver;
import com.nexusai.application.agent.messaging.PeerIdleSubscriptions;
import com.nexusai.application.agent.messaging.PeerInboundPolicy;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SendMessage 跨会话路由：判类顺序、远端拒绝、结构化禁跨会话、hold 不入队、未接线不假成功。
 */
@DisplayName("SendMessageTool · 跨会话路由")
class SendMessageToolPeerTest {

    private static final String SELF = "sess-aaaaaaa1";
    private static final String OTHER = "sess-bbbbbbb2";

    private SendMessageTool tool;
    private PeerSessionRegistry sessions;
    private AgentNameRegistry agents;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private String setting = "accept";
    private final AtomicInteger deliveries = new AtomicInteger();
    private boolean portWired = true;

    @BeforeEach
    void setUp() {
        // 单测无 DB / 未绑定项目根：显式登记会话 cwd（生产由 ChatService 启动锚写入），
        // 否则 ToolUseContext 构造期 CwdResolution 会 fail-loud，用例与实现无关地全红。
        com.nexusai.application.agent.agent.SessionCwdHolder.set(
            SELF, System.getProperty("java.io.tmpdir"));
        sessions = new PeerSessionRegistry();
        sessions.register(new PeerSessionRegistry.Registration(
            SELF, "alpha", "/p", PermissionMode.DEFAULT, 1L));
        sessions.register(new PeerSessionRegistry.Registration(
            OTHER, "beta", "/p", PermissionMode.DEFAULT, 1L));
        agents = new AgentNameRegistry();
        policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> setting);
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(sessions);
        router.setPeerInboundPolicy(policy);
        router.setIdleSubscriptions(new PeerIdleSubscriptions());
        MessageRecipientResolver resolver = new MessageRecipientResolver();
        resolver.setPeerSessionRegistry(sessions);
        resolver.setAgentNameRegistry(agents);
        tool = new SendMessageTool(new TeamHelpers());
        tool.setAgentNameRegistry(agents);
        tool.setRecipientResolver(resolver);
        tool.setPeerMessageRouter(router);
    }

    private void wirePort(PeerMessageRouter.Status status) {
        portWired = true;
        router.setDeliveryPort((targetSessionId, epoch, envelope, body) -> {
            deliveries.incrementAndGet();
            return new PeerMessageRouter.Receipt(status, envelope.messageId(), "stub", null);
        });
    }

    private static ToolUseContext tuc(String sessionId) {
        return new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT);
    }

    /** 主会话本身：{@code agentId == null}（对齐 CC {@code !context.agentId}）。 */
    private static ToolUseContext mainTuc(String sessionId) {
        return new ToolUseContext(null, sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT);
    }

    /** 子代理 / teammate：sessionId 继承父会话，agentId 非 null。 */
    private static ToolUseContext childTuc(String parentSessionId) {
        return tuc(parentSessionId);
    }

    private Object execute(String to, String text) {
        return execute(tuc(SELF), to, text);
    }

    private Object execute(ToolUseContext ctx, String to, String text) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", to);
        input.put("summary", "s");
        input.put("message", text);
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        return ((ToolResult<?>) tool.execute(call, ctx)).data();
    }

    private Object executeStructured(String to) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", to);
        ObjectNode message = input.putObject("message");
        message.put("type", "shutdown_request");
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        return ((ToolResult<?>) tool.execute(call, tuc(SELF))).data();
    }

    @Test
    @DisplayName("远端 session 形 → Error 且不投递（绝不放本地 mailbox）")
    void remoteIsRejected() {
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        assertThat(String.valueOf(execute("session_abc", "hi")))
            .startsWith("Error:").contains("remote cross-session");
        assertThat(deliveries).hasValue(0);

        Object structured = executeStructured("session_abc");
        assertThat(String.valueOf(structured)).startsWith("Error:");
    }

    @Test
    @DisplayName("sess- 指向 live 会话 → 首行 CC 同形回执 + 第二行 message_id")
    void nexusSessionDelivers() {
        // WHY: 对齐 CC 2.1.328 实测回执。address = ListAgents 的可回信地址（name [ref]），
        //   首行只说「已发到本机另一个会话」，不声称模型已处理；第二行保留 message_id 便于排查。
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        Object data = execute("sess-bbbbbbb2", "hi");
        assertThat(data).isInstanceOf(String.class);
        String[] lines = ((String) data).split("\n", -1);
        assertThat(lines[0])
            .matches("sent to beta \\[[0-9a-f]{6}\\] \u2014 another Claude session on this machine");
        assertThat(lines[1]).startsWith("message_id: peer-");
        assertThat((String) data).doesNotContain("Error:").doesNotContain("Message delivered to");
        assertThat(deliveries).hasValue(1);
    }

    @Test
    @DisplayName("未接线的 DeliveryPort → Error（不假成功）")
    void notWiredIsError() {
        portWired = false;
        router.setDeliveryPort(null);
        assertThat(String.valueOf(execute("sess-bbbbbbb2", "hi")))
            .startsWith("Error:").contains("not wired");
    }

    @Test
    @DisplayName("hold 不入队、不唤醒，工具回 Error: message held")
    void holdDoesNotDeliver() {
        setting = "hold";
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        String data = String.valueOf(execute("sess-bbbbbbb2", "hi"));
        assertThat(data).startsWith("Error:").contains("held").contains("message_id=peer-");
        assertThat(deliveries).hasValue(0);
    }

    @Test
    @DisplayName("结构化消息发往 session/sess- → Error")
    void structuredToSessionRejected() {
        assertThat(String.valueOf(executeStructured("sess-bbbbbbb2")))
            .startsWith("Error:").contains("structured messages cannot be sent to a cross-session address");
        assertThat(String.valueOf(executeStructured("main")))
            .startsWith("Error:").contains("structured messages cannot be sent");
    }

    @Test
    @DisplayName("不存在的 sess- → Error（不落 mailbox）")
    void unknownNexusSession() {
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        assertThat(String.valueOf(execute("sess-00000000", "hi")))
            .startsWith("Error:").contains("no live session");
        assertThat(deliveries).hasValue(0);
    }

    // ───────── 发给自身：CC 原文拒绝，零投递（不保留自投递能力） ─────────

    @Test
    @DisplayName("主会话发给 main（自己的会话）→ Error（CC 原文）且零投递")
    void mainToOwnSessionRejected() {
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        String data = String.valueOf(execute(mainTuc(SELF), "main", "note to self"));
        assertThat(data).startsWith("Error:")
            .contains("is this session's own address")
            .contains("would only come back to this conversation")
            .contains("there is no one else at that address to send to");
        assertThat(deliveries).hasValue(0);
    }

    @Test
    @DisplayName("主会话发给自己的 sess- id / name [ref] → 同样拒绝且零投递")
    void mainToOwnAddressesRejected() {
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        String ownRef = sessions.resolve(SELF).orElseThrow().ref();
        for (String to : List.of(SELF, "alpha [" + ownRef + "]")) {
            String data = String.valueOf(execute(mainTuc(SELF), to, "hi"));
            assertThat(data).as(to).startsWith("Error:")
                .contains("would only come back to this conversation");
        }
        assertThat(deliveries).hasValue(0);
    }

    @Test
    @DisplayName("notify_when_idle + 自身地址 → 同样拒绝（不登记 idle 订阅）")
    void notifyWhenIdleToSelfRejected() {
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", "main");
        input.put("summary", "s");
        input.put("message", "hi");
        input.put("notify_when_idle", true);
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        String data = String.valueOf(((ToolResult<?>) tool.execute(call, mainTuc(SELF))).data());
        assertThat(data).startsWith("Error:")
            .contains("would only come back to this conversation");
        assertThat(deliveries).hasValue(0);
    }

    @Test
    @DisplayName("结构化消息发给自身 → CC 自身地址文案（不是跨会话文案）")
    void structuredToSelfRejected() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", "main");
        ObjectNode message = input.putObject("message");
        message.put("type", "shutdown_request");
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        String data = String.valueOf(((ToolResult<?>) tool.execute(call, mainTuc(SELF))).data());
        assertThat(data).startsWith("Error:")
            .contains("would only come back to this conversation");
    }

    @Test
    @DisplayName("同名但不同会话（带自己的 ref 前缀相同、ref 指向对方）→ 正常投递")
    void sameNameDifferentSessionDelivers() {
        String third = "sess-ccccccc3";
        sessions.register(new PeerSessionRegistry.Registration(
            third, "alpha", "/p", PermissionMode.DEFAULT, 1L));
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        String otherRef = sessions.resolve(third).orElseThrow().ref();
        String data = String.valueOf(execute(mainTuc(SELF), "alpha [" + otherRef + "]", "hi"));
        // 重名会话在名册里会被加后缀（alpha-xxxxxx），回执地址按注册名走。
        assertThat(data).startsWith("sent to alpha").doesNotContain("Error:");
        assertThat(deliveries).hasValue(1);
    }

    @Test
    @DisplayName("主会话 → 子代理（同名不同 agent）正常入队；子代理 → 主会话正常投递")
    void mainAndSubagentCanMessageEachOther() {
        agents.register(SELF, "researcher", "a1111111111111111");
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        setting = "hold"; // 同会话记录投递不经过 hold 策略

        Object toAgent = execute(mainTuc(SELF), "researcher", "do x");
        assertThat(String.valueOf(toAgent)).doesNotContain("Error:");
        assertThat(agents.hasPending("a1111111111111111")).isTrue();

        Object toMain = execute(childTuc(SELF), "main", "result");
        assertThat(String.valueOf(toMain)).startsWith("sent to alpha [");
        assertThat(deliveries).hasValue(1);
    }

    @Test
    @DisplayName("notify_when_idle 只对会话地址成立（子代理 → Error）")
    void notifyWhenIdleOnlyForSessions() {
        agents.register(SELF, "researcher", "a1111111111111111");
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", "researcher");
        input.put("summary", "s");
        input.put("message", "hi");
        input.put("notify_when_idle", true);
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        assertThat(String.valueOf(((ToolResult<?>) tool.execute(call, tuc(SELF))).data()))
            .startsWith("Error:").contains("notify_when_idle");
        assertThat(agents.hasPending("a1111111111111111")).isFalse();
    }

    @Test
    @DisplayName("同名跨 session 不串：别的会话的同名子代理不会被投递")
    void sameNameOtherSessionNotQueued() {
        agents.register(OTHER, "researcher", "a2222222222222222");
        assertThat(String.valueOf(execute("researcher", "hi"))).startsWith("Error:");
        assertThat(agents.hasPending("a2222222222222222")).isFalse();
    }

    private static PeerMessageRouter.ApprovalPrincipal principal(String sessionId, boolean trusted) {
        return new PeerMessageRouter.ApprovalPrincipal() {
            @Override
            public String sessionId() {
                return sessionId;
            }

            @Override
            public String requestId() {
                return "req-1";
            }

            @Override
            public boolean trustedUi() {
                return trusted;
            }
        };
    }

    private String holdOne() {
        setting = "hold";
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        String error = String.valueOf(execute("sess-bbbbbbb2", "hi"));
        int idx = error.indexOf("message_id=");
        return error.substring(idx + "message_id=".length(), error.indexOf(')', idx));
    }

    @Test
    @DisplayName("listHeld/rejectHeld：不可信主体或无归属 → 空/false，且 held 不出队")
    void heldRequiresTrustedOwner() {
        String messageId = holdOne();
        assertThat(router.listHeld(OTHER, principal(OTHER, false))).isEmpty();
        assertThat(router.listHeld(SELF, principal(OTHER, true))).isEmpty();
        assertThat(router.rejectHeld(messageId, principal(OTHER, false))).isFalse();
        assertThat(router.listHeld(OTHER, principal(OTHER, true))).hasSize(1);
        assertThat(router.rejectHeld(messageId, principal(OTHER, true))).isTrue();
        assertThat(router.listHeld(OTHER, principal(OTHER, true))).isEmpty();
    }

    @Test
    @DisplayName("approveHeld：不可信 → APPROVAL_REJECTED 且保留 pending；可信 + accept → 投递")
    void approveHeldOnlyTrusted() {
        String messageId = holdOne();
        assertThat(router.approveHeld(messageId, principal(OTHER, false)).status())
            .isEqualTo(PeerMessageRouter.Status.APPROVAL_REJECTED);
        assertThat(router.listHeld(OTHER, principal(OTHER, true))).hasSize(1);

        setting = "accept";
        assertThat(router.approveHeld(messageId, principal(OTHER, true)).status())
            .isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        assertThat(deliveries).hasValue(1);
        assertThat(router.listHeld(OTHER, principal(OTHER, true))).isEmpty();
    }

    @Test
    @DisplayName("hop-chain：目标首次重复不拒（对齐 CC maxSelfHops=10）；出现满 10 次才 LOOP_REJECTED")
    void hopChainLoopRejected() {
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        long targetEpoch = sessions.resolve(OTHER).orElseThrow().endpointEpoch();
        // 首次重复（正常 A→B→A 往返）必须放行 —— CC 是「≥10 次」才判环，不是首次命中即拒。
        PeerMessageRouter.SendRequest firstRepeat = new PeerMessageRouter.SendRequest(
            OTHER, targetEpoch, "beta", SELF, "alpha", "alpha [aaaaaa]", "hi", "s",
            false, false, List.of(com.nexusai.application.agent.messaging.PeerEnvelope.hopToken(OTHER)));
        assertThat(router.send(firstRepeat, PermissionMode.DEFAULT).status())
            .isEqualTo(PeerMessageRouter.Status.ACCEPTED);
        assertThat(deliveries).hasValue(1);

        // 目标在链中出现满 10 次 → LOOP_REJECTED 且不投递
        java.util.List<String> tenHits = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenHits.add(com.nexusai.application.agent.messaging.PeerEnvelope.hopToken(OTHER));
        }
        PeerMessageRouter.SendRequest loop = new PeerMessageRouter.SendRequest(
            OTHER, targetEpoch, "beta", SELF, "alpha", "alpha [aaaaaa]", "hi", "s",
            false, false, tenHits);
        PeerMessageRouter.Receipt receipt = router.send(loop, PermissionMode.DEFAULT);
        assertThat(receipt.status()).isEqualTo(PeerMessageRouter.Status.LOOP_REJECTED);
        assertThat(receipt.detail()).contains("hop-loop");
        assertThat(deliveries).hasValue(1);
    }

    @Test
    @DisplayName("notify_when_idle 成功投递后登记 idle 订阅（回执第三行保留）")
    void idleSubscriptionRegistered() {
        wirePort(PeerMessageRouter.Status.ACCEPTED);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", "sess-bbbbbbb2");
        input.put("summary", "s");
        input.put("message", "hi");
        input.put("notify_when_idle", true);
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        String receipt = (String) ((ToolResult<?>) tool.execute(call, tuc(SELF))).data();
        assertThat(receipt).startsWith("sent to beta [");
        assertThat(receipt).contains("\nmessage_id: peer-");
        assertThat(receipt).contains("\nidle_subscription: subscribed");
    }
}
