package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.messaging.MessageRecipientResolver;
import com.nexusai.application.agent.messaging.PeerEnvelope;
import com.nexusai.application.agent.messaging.PeerExecutionOrigin;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷 3 回归：<b>hop 链传播 + CC 阈值</b>（{@code maxSelfHops=10} / {@code maxChainLength=28}）。
 *
 * <h2>WHY</h2>
 * <p>改造前 {@code SendMessageTool.deliverCrossSession} 构造 {@code SendRequest} 时传
 * {@code List.of()} ⇒ A→B→A 的往返链永远只有一段 ⇒ {@code LOOP_REJECTED} 在生产链路不可达，
 * 两个会话可以无限乒乓。
 *
 * <p>现在：prior 链唯一来源 = 服务端类型化载体 {@code ctx.peerExecutionOrigin().hopChain()}，
 * 新链 = prior + 当前发送方 token；判定（顺序对齐 CC）：
 * <ol>
 *   <li>目标会话 token 在链中出现 {@code >= 10} 次 ⇒ {@code hop-loop}（首次重复<b>不</b>拒）；</li>
 *   <li>新链长度 {@code > 28} ⇒ {@code hop-runaway}（32 只是格式/截断边界）。</li>
 * </ol>
 *
 * <h2>RED teeth</h2>
 * <p>把 prior 链改回 {@code List.of()} ⇒ 第 2、3 条红（链永远是 1 段，环/超长都测不出来）。
 */
@DisplayName("SendMessageTool · hop 链传播与 CC 阈值")
class PeerHopChainPropagationTest {

    private static final String A = "sess-aaaaaa01";
    private static final String B = "sess-bbbbbb02";

    private SendMessageTool tool;
    private PeerSessionRegistry sessions;
    private PeerMessageRouter router;
    private final List<PeerEnvelope> delivered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessions = new PeerSessionRegistry();
        sessions.register(new PeerSessionRegistry.Registration(
            A, "alpha", "/p", PermissionMode.DEFAULT, 1L));
        sessions.register(new PeerSessionRegistry.Registration(
            B, "beta", "/p", PermissionMode.DEFAULT, 1L));

        PeerInboundPolicy policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> "accept");
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(sessions);
        router.setPeerInboundPolicy(policy);
        router.setDeliveryPort((sid, epoch, envelope, body) -> {
            delivered.add(envelope);
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "stub", null);
        });

        MessageRecipientResolver resolver = new MessageRecipientResolver();
        resolver.setPeerSessionRegistry(sessions);
        resolver.setAgentNameRegistry(new AgentNameRegistry());

        tool = new SendMessageTool(new TeamHelpers());
        tool.setAgentNameRegistry(new AgentNameRegistry());
        tool.setRecipientResolver(resolver);
        tool.setPeerMessageRouter(router);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static ToolUseContext tuc(String sessionId, List<String> inboundHopChain) {
        ToolUseContext base = new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT);
        if (inboundHopChain == null) {
            return base;
        }
        PeerExecutionOrigin origin = new PeerExecutionOrigin(
            B, "peer", PeerExecutionOrigin.SOURCE_KIND_PEER,
            "peer-msg-" + inboundHopChain.size(), 1L, "default", "beta [bbbbbb]",
            inboundHopChain);
        return base.withPeerExecutionOrigin(origin);
    }

    private ToolResult<?> send(ToolUseContext ctx, String to, String body) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", to);
        input.put("summary", "s");
        input.put("message", body);
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        return (ToolResult<?>) tool.execute(call, ctx);
    }

    private static List<String> chain(String... sessions) {
        List<String> out = new ArrayList<>();
        for (String s : sessions) {
            out.add(PeerEnvelope.hopToken(s));
        }
        return out;
    }

    /** (A,B) 交替 n 轮 = 2n 段。 */
    private static List<String> pingPong(int rounds) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            out.add(PeerEnvelope.hopToken(A));
            out.add(PeerEnvelope.hopToken(B));
        }
        return out;
    }

    private static List<String> fakeChain(int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            out.add(String.format("%024x", i + 1));
        }
        return out;
    }

    // ───────────────────────── tests ─────────────────────────

    @Test
    @DisplayName("无 peer 来源：新链只有本会话一段（首轮不伪造历史）")
    void firstSendStartsChain() {
        // 跨会话成功回执已改为 CC 同形文本（首行 sent to ...），成功判据 = 非 Error 文本
        Object data = send(tuc(A, null), B, "hi").data();
        assertTrue(data instanceof String s && s.startsWith("sent to "), "首轮发送必须成功，实际=" + data);
        assertEquals(1, delivered.size());
        assertEquals(List.of(PeerEnvelope.hopToken(A)), delivered.get(0).hopChain());
    }

    @Test
    @DisplayName("A→B→A：回复时带上入站链，新链 = prior + 本段（传播生效）")
    void replyPropagatesInboundChain() {
        // A 首发给 B → 链 [A]
        send(tuc(A, null), B, "task");
        assertEquals(List.of(PeerEnvelope.hopToken(A)), delivered.get(0).hopChain());

        // B 收到链 [A] 后回复 A → 链 [A,B]
        ToolUseContext bCtx = tuc(B, delivered.get(0).hopChain());
        send(bCtx, A, "ack");
        assertEquals(chain(A, B), delivered.get(1).hopChain());

        // A 再回 B → 链 [A,B,A]（正常往返不被误伤）
        ToolUseContext aCtx = tuc(A, delivered.get(1).hopChain());
        send(aCtx, B, "follow-up");
        assertEquals(chain(A, B, A), delivered.get(2).hopChain());
        assertEquals(3, delivered.size(), "3 次正常往返必须全部投递");
    }

    @Test
    @DisplayName("第 10 次出现目标会话 → hop-loop 拒投递；第 9 次仍放行")
    void selfHopsThresholdIsTen() {
        // 目标 B 已出现 9 次（(A,B)*9 = 18 段）+ 本段 A ⇒ 19 段，count(B)=9 → 放行
        int before = delivered.size();
        ToolResult<?> ok = send(tuc(A, pingPong(9)), B, "still-ok");
        assertEquals(before + 1, delivered.size(), "第 9 次出现不得误伤");
        assertEquals(19, delivered.get(delivered.size() - 1).hopChain().size());

        // 目标 B 已出现 10 次（(A,B)*10 = 20 段）+ 本段 A ⇒ 21 段，count(B)=10 → 拒
        ToolResult<?> rejected = send(tuc(A, pingPong(10)), B, "loop");
        assertTrue(String.valueOf(rejected.data()).startsWith("Error:"), "必须返回 error");
        assertTrue(String.valueOf(rejected.data()).contains("hop-loop"),
            "原因必须明确：hop-loop，实际=" + rejected.data());
        assertEquals(before + 1, delivered.size(), "hop-loop 不得投递");
    }

    @Test
    @DisplayName("链长 29 → hop-runaway 拒投递；28 段仍放行")
    void chainLengthThresholdIs28() {
        int before = delivered.size();
        // prior 27 段 + 本段 = 28 → 放行
        ToolResult<?> ok = send(tuc(A, fakeChain(27)), B, "len-28");
        assertEquals(before + 1, delivered.size(), "28 段是允许的边界");
        assertEquals(28, delivered.get(delivered.size() - 1).hopChain().size());

        // prior 28 段 + 本段 = 29 → 拒
        ToolResult<?> rejected = send(tuc(A, fakeChain(28)), B, "len-29");
        assertTrue(String.valueOf(rejected.data()).startsWith("Error:"));
        assertTrue(String.valueOf(rejected.data()).contains("hop-runaway"),
            "原因必须明确：hop-runaway，实际=" + rejected.data());
        assertEquals(before + 1, delivered.size(), "hop-runaway 不得投递");
    }

    @Test
    @DisplayName("格式边界：prior 32 段 + 本段 → 截断到最后 32 段（不抛异常、不拒投）")
    void formatCapTruncates() {
        // 32 段会让链长 33 > 28 ⇒ 先被 hop-runaway 拦（32 只是格式上限，不是放行条件）。
        ToolResult<?> rejected = send(tuc(A, fakeChain(32)), B, "len-33");
        assertTrue(String.valueOf(rejected.data()).contains("hop-runaway"));

        PeerEnvelope truncated = PeerEnvelope.create("m-1", "a", A, "alpha",
            PermissionMode.DEFAULT, fakeChain(35), 0L, null, false);
        assertEquals(PeerEnvelope.MAX_HOP_CHAIN, truncated.hopChain().size(),
            "超过 32 段时保留最后 32 段（CC slice），不抛异常");
        assertEquals(PeerEnvelope.hopToken(A),
            truncated.hopChain().get(truncated.hopChain().size() - 1), "本段必须在链尾");
    }
}
