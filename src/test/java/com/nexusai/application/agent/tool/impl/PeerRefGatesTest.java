package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.messaging.MessageRecipientResolver;
import com.nexusai.application.agent.messaging.PeerAddressing;
import com.nexusai.application.agent.messaging.PeerIdleSubscriptions;
import com.nexusai.application.agent.messaging.PeerInboundPolicy;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.messaging.PeerRefPins;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ref 派生 + pin 门 + 冒充门（CC 2.1.328 实测）。
 *
 * <p>CC 原文（exe 十进制偏移）：
 * <ul>
 *   <li>pin 失败 356149269：{@code Not sent: ListAgents has not listed that session id in this session,
 *       or last listed it under another name. Call ListAgents and copy the name and brackets as printed.}</li>
 *   <li>冒充失败 355937466：{@code 'X' is the name-and-ref token this session advertises for ITSELF, yet
 *       a different session record on this machine claims it — not sent. A record impersonating this
 *       session is suspicious: ask the user. (If you meant yourself, there is no one to send to.)}</li>
 *   <li>ref 长度 317883861：{@code Bc=6, nxi=12}；派生 317889547：{@code Hc(kind,id)} +
 *       相邻最长公共前缀 + 1。</li>
 * </ul>
 */
@DisplayName("SendMessage · ref 派生 / pin 门 / 冒充门")
class PeerRefGatesTest {

    /** 自身会话：hash("session:sess-gggggg01") = 9b0f974a6a8df8f957b4eabb → ref 9b0f97。 */
    private static final String SELF = "sess-gggggg01";
    private static final String SELF_REF = "9b0f97";
    /** 与她同 6 位前缀的会话（哈希碰撞对，离线算得）→ 用来构造「冒充我」的记录。 */
    private static final String HIJACK = "sess-p15381690";
    /** pinned 形态（CC Hl：session_01 + 22 位 base58）——列表打印时方括号里用 id 本身。 */
    private static final String PINNED = "session_01" + "1".repeat(22);
    private static final String NORMAL = "sess-cccccccc";

    private SendMessageTool tool;
    private ListAgentsTool listTool;
    private PeerSessionRegistry sessions;
    private PeerRefPins pins;
    private PeerInboundPolicy policy;
    private PeerMessageRouter router;
    private final AtomicInteger deliveries = new AtomicInteger();

    @BeforeEach
    void setUp() {
        sessions = new PeerSessionRegistry();
        sessions.register(new PeerSessionRegistry.Registration(
            SELF, "alpha", "/p", PermissionMode.DEFAULT, 1L));
        sessions.register(new PeerSessionRegistry.Registration(
            PINNED, "beta", "/p", PermissionMode.DEFAULT, 1L));
        sessions.register(new PeerSessionRegistry.Registration(
            NORMAL, "gamma", "/p", PermissionMode.DEFAULT, 1L));
        sessions.register(new PeerSessionRegistry.Registration(
            HIJACK, "hijack", "/p", PermissionMode.DEFAULT, 1L));

        pins = new PeerRefPins();
        policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> "accept");
        router = new PeerMessageRouter();
        router.setPeerSessionRegistry(sessions);
        router.setPeerInboundPolicy(policy);
        router.setIdleSubscriptions(new PeerIdleSubscriptions());
        router.setDeliveryPort((targetSessionId, epoch, envelope, body) -> {
            deliveries.incrementAndGet();
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                envelope.messageId(), "stub", null);
        });

        MessageRecipientResolver resolver = new MessageRecipientResolver();
        resolver.setPeerSessionRegistry(sessions);
        resolver.setAgentNameRegistry(new AgentNameRegistry());

        tool = new SendMessageTool(new TeamHelpers());
        tool.setRefPins(pins);
        tool.setRecipientResolver(resolver);
        tool.setPeerMessageRouter(router);

        listTool = new ListAgentsTool();
        listTool.setPeerSessionRegistry(sessions);
        listTool.setAgentNameRegistry(new AgentNameRegistry());
        listTool.setTeamHelpers(new TeamHelpers());
        listTool.setRefPins(pins);
    }

    private static ToolUseContext tuc(String sessionId) {
        // 15 参构造器显式给 effectiveCwd：10 参构造器会走 CwdResolution（需 DB 项目根），
        // 非 Spring 测试环境没有库 ⇒ 抛 UnresolvedProjectRootException。
        return new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT, Map.of(), false, "", java.nio.file.Path.of("D:/tmp"), null);
    }

    private String send(String to) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", to);
        input.put("summary", "s");
        input.put("message", "hi");
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        return String.valueOf(((ToolResult<?>) tool.execute(call, tuc(SELF))).data());
    }

    @SuppressWarnings("unchecked")
    private String list() {
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(),
            ListAgentsTool.NAME, JsonNodeFactory.instance.objectNode());
        Map<String, Object> data = (Map<String, Object>)
            ((ToolResult<?>) listTool.execute(call, tuc(SELF))).data();
        return (String) data.get("listing");
    }

    // ── 派生规则（CC QX / Hc / Bc,nxi）─────────────────────────────────────

    @Test
    @DisplayName("ref：kind 参与哈希；6..12 位小写 hex；整页唯一；pinned id 用 id 本身")
    void refDerivation() {
        assertThat(PeerAddressing.refFor("session", "sess-0123abcd")).hasSize(6);
        assertThat(PeerAddressing.refFor("session", "sess-0123abcd"))
            .isNotEqualTo(PeerAddressing.refFor("agent", "sess-0123abcd"));
        assertThat(PeerAddressing.refFor("session", "sess-0123abcd")).matches("[0-9a-f]{6}");
        assertThat(PeerAddressing.hashToken("session", "sess-0123abcd")).hasSize(24);
        // pinned 形态（bridge/远端行）：方括号 ref = 会话 id 本身
        assertThat(PeerAddressing.refFor("session", PINNED)).isEqualTo(PINNED);

        List<PeerAddressing.RefIdentity> identities = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            identities.add(new PeerAddressing.RefIdentity("session", String.format("sess-%08x", i)));
        }
        List<String> refs = PeerAddressing.assignRefs(identities,
            List.of(new PeerAddressing.RefIdentity("session", SELF)));
        assertThat(refs).hasSize(100).doesNotHaveDuplicates();
        for (String ref : refs) {
            assertThat(ref).matches("[0-9a-f]{6,12}");
        }

        // 长度规则：相邻哈希最长公共前缀 + 1，下限 6、上限 12
        assertThat(PeerAddressing.refLength("abcdef1234567890abcdef12", "abcdef12345", null))
            .isEqualTo(12);
        assertThat(PeerAddressing.refLength("abcdef1234567890abcdef12", "abcdef123", null))
            .isEqualTo(10);
        assertThat(PeerAddressing.refLength("abcdef1234567890abcdef12", null, null)).isEqualTo(6);
        assertThat(PeerAddressing.refLength("abcdef1234567890abcdef12",
            "abcdef1234567890abcdef1x", null)).isEqualTo(12);
    }

    @Test
    @DisplayName("两套解析器：pinned 只认 session_/cse_/local_；6..12 hex 走短 ref 解析")
    void twoParsers() {
        assertThat(PeerAddressing.pinnedRef("beta [" + PINNED + "]")).isEqualTo(PINNED);
        assertThat(PeerAddressing.pinnedRef("beta [session_01BBB]")).isNull();
        assertThat(PeerAddressing.pinnedRef("beta [abcdef]")).isNull();
        assertThat(PeerAddressing.parseShortRef("beta [abcdef]").ref()).isEqualTo("abcdef");
        assertThat(PeerAddressing.parseShortRef("beta [0123456789ab]").ref())
            .isEqualTo("0123456789ab");
        assertThat(PeerAddressing.parseShortRef("beta [" + PINNED + "]")).isNull();
        assertThat(PeerAddressing.parseShortRef("beta [abcde]")).isNull();
        // 注入点：我的 ref 前缀
        assertThat(PeerAddressing.advertisesSelf("hijack [" + SELF_REF + "]", SELF)).isTrue();
        assertThat(PeerAddressing.advertisesSelf("hijack [deadbe]", SELF)).isFalse();
    }

    // ── 门 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a. 未 ListAgents 的 pinned session ref → pin 拒（CC 原文）且零投递")
    void unlistedPinnedRefRejected() {
        String out = send("beta [" + PINNED + "]");
        assertThat(out).startsWith("Error: Not sent: ListAgents has not listed that session id in "
            + "this session, or last listed it under another name. Call ListAgents and copy the name "
            + "and brackets as printed.");
        assertThat(deliveries).hasValue(0);
    }

    @Test
    @DisplayName("b. ListAgents 后同一 ref → 放行并送达")
    void listedPinnedRefDelivered() {
        list();
        String out = send("beta [" + PINNED + "]");
        assertThat(out).doesNotContain("Not sent:").startsWith("sent to beta [");
        assertThat(deliveries).hasValue(1);
    }

    @Test
    @DisplayName("c. 6..12 位短 hex ref 不经 pin（未列过也送达）")
    void shortHexRefSkipsPin() {
        String ref = PeerAddressing.refFor("session", NORMAL);
        assertThat(ref).matches("[0-9a-f]{6}");
        String out = send("gamma [" + ref + "]");
        assertThat(out).doesNotContain("Not sent:").startsWith("sent to gamma [");
        assertThat(deliveries).hasValue(1);
    }

    @Test
    @DisplayName("d. 命中自身身份但目标不是自己 → 冒充拒（CC 原文）且零投递")
    void impersonationRejected() {
        String out = send("hijack [" + SELF_REF + "]");
        assertThat(out).startsWith("Error: 'hijack [" + SELF_REF + "]' is the name-and-ref token "
            + "this session advertises for ITSELF, yet a different session record on this machine "
            + "claims it \u2014 not sent. A record impersonating this session is suspicious: ask the "
            + "user. (If you meant yourself, there is no one to send to.)");
        assertThat(deliveries).hasValue(0);
    }

    @Test
    @DisplayName("e. 跨进程重启（新 PeerRefPins）→ 未列过即拒")
    void pinsDoNotSurviveProcessRestart() {
        list();
        assertThat(pins.size(SELF)).isPositive();

        PeerRefPins restarted = new PeerRefPins();
        tool.setRefPins(restarted);
        String out = send("beta [" + PINNED + "]");
        assertThat(out).contains("ListAgents has not listed that session id in this session");
        assertThat(deliveries).hasValue(0);
    }

    @Test
    @DisplayName("f. ListAgents 打印的地址可原样回填并送达（同名不同会话仍靠 ref 区分）")
    void addressRoundTrips() {
        String listing = list();
        String line = null;
        for (String candidate : listing.split("\n")) {
            if (candidate.contains("beta [")) {
                line = candidate.trim();
                break;
            }
        }
        assertThat(line).isNotNull();
        String address = line.substring(0, line.indexOf("  \u00b7")).trim();
        assertThat(address).isEqualTo("beta [" + PINNED + "]");
        String out = send(address);
        assertThat(out).doesNotContain("Not sent:").startsWith("sent to " + address);
        assertThat(deliveries).hasValue(1);
    }

    @Test
    @DisplayName("列表里换名字仍被 pin 拒（last listed it under another name）")
    void renamedPinRejected() {
        list();
        String out = send("beta-alias [" + PINNED + "]");
        // 名字不匹配 → 既可能落 not-found（无该名 live 会话），也绝不放行
        assertThat(out).startsWith("Error:");
        assertThat(deliveries).hasValue(0);
    }
}
