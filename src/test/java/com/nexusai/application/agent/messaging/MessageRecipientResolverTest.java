package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收件人解析：判类顺序、sess- 位置、同名跨 session 不串、已注销 endpoint 不复活。
 */
@DisplayName("MessageRecipientResolver · 判类 / 归属 / 不串台")
class MessageRecipientResolverTest {

    private static final String SELF = "sess-aaaaaaa1";
    private static final String OTHER = "sess-bbbbbbb2";

    private PeerSessionRegistry sessions;
    private AgentNameRegistry agents;
    private MessageRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        sessions = new PeerSessionRegistry();
        sessions.register(new PeerSessionRegistry.Registration(
            SELF, "alpha", "/p", PermissionMode.DEFAULT, 1L));
        sessions.register(new PeerSessionRegistry.Registration(
            OTHER, "beta", "/p", PermissionMode.DEFAULT, 1L));
        agents = new AgentNameRegistry();
        agents.register(SELF, "researcher", "a1111111111111111");
        agents.register(OTHER, "researcher", "a2222222222222222");
        resolver = new MessageRecipientResolver();
        resolver.setPeerSessionRegistry(sessions);
        resolver.setAgentNameRegistry(agents);
    }

    private MessageRecipientResolver.Target resolve(String to) {
        return resolver.resolve(new MessageRecipientResolver.Request(to, SELF, null, false));
    }

    @Test
    @DisplayName("远端 session 形 → UNSUPPORTED_REMOTE（不放本地 mailbox）")
    void remoteIsUnsupported() {
        assertThat(resolve("session_abc").kind())
            .isEqualTo(MessageRecipientResolver.Kind.UNSUPPORTED_REMOTE);
        assertThat(resolve("uds:/tmp/x.sock").kind())
            .isEqualTo(MessageRecipientResolver.Kind.UNSUPPORTED_REMOTE);
        assertThat(resolve("cse_abc").kind())
            .isEqualTo(MessageRecipientResolver.Kind.UNSUPPORTED_REMOTE);
    }

    @Test
    @DisplayName("sess- 扩展：live → SESSION；不存在 → UNRESOLVED")
    void nexusSession() {
        MessageRecipientResolver.Target hit = resolve("sess-bbbbbbb2");
        assertThat(hit.kind()).isEqualTo(MessageRecipientResolver.Kind.SESSION);
        assertThat(hit.sessionId()).isEqualTo(OTHER);
        assertThat(hit.selfSent()).isFalse();

        MessageRecipientResolver.Target miss = resolve("sess-00000000");
        assertThat(miss.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(miss.errorMessage()).contains("no live session");
    }

    @Test
    @DisplayName("main = 来源自身会话（主会话发送 ⇒ selfSent=true）")
    void mainIsSelf() {
        MessageRecipientResolver.Target target = resolve("main");
        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.MAIN);
        assertThat(target.sessionId()).isEqualTo(SELF);
        assertThat(target.selfSent()).isTrue();
    }

    @Test
    @DisplayName("自己的 sess- id / 自己的 name [ref] 同样是「发给自身」")
    void ownAddressesAreSelf() {
        MessageRecipientResolver.Target byId = resolve(SELF);
        assertThat(byId.kind()).isEqualTo(MessageRecipientResolver.Kind.SESSION);
        assertThat(byId.selfSent()).isTrue();

        String ownRef = sessions.resolve(SELF).orElseThrow().ref();
        MessageRecipientResolver.Target byName = resolve("alpha [" + ownRef + "]");
        assertThat(byName.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_SESSION);
        assertThat(byName.selfSent()).isTrue();
    }

    @Test
    @DisplayName("子代理 / teammate 发回主会话不是「发给自身」（senderAgentId 非 null）")
    void childAgentToMainIsNotSelf() {
        String ownRef = sessions.resolve(SELF).orElseThrow().ref();
        String agentId = "a1111111111111111";

        MessageRecipientResolver.Target main = resolver.resolve(
            new MessageRecipientResolver.Request("main", SELF, null, false, null, agentId));
        assertThat(main.kind()).isEqualTo(MessageRecipientResolver.Kind.MAIN);
        assertThat(main.selfSent()).isFalse();

        MessageRecipientResolver.Target byId = resolver.resolve(
            new MessageRecipientResolver.Request(SELF, SELF, null, false, null, agentId));
        assertThat(byId.kind()).isEqualTo(MessageRecipientResolver.Kind.SESSION);
        assertThat(byId.selfSent()).isFalse();

        MessageRecipientResolver.Target byName = resolver.resolve(
            new MessageRecipientResolver.Request("alpha [" + ownRef + "]", SELF, null, false, null, agentId));
        assertThat(byName.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_SESSION);
        assertThat(byName.selfSent()).isFalse();
    }

    @Test
    @DisplayName("agentId：归属本会话才可路由；别人的/已注销 → UNRESOLVED 且不回落")
    void agentOwnership() {
        MessageRecipientResolver.Target own = resolve("a1111111111111111");
        assertThat(own.kind()).isEqualTo(MessageRecipientResolver.Kind.AGENT);
        assertThat(own.agentId()).isEqualTo("a1111111111111111");

        MessageRecipientResolver.Target other = resolve("a2222222222222222");
        assertThat(other.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(other.mustNotFallback()).isTrue();

        agents.unregister(SELF, "researcher", "a1111111111111111");
        // [B1 退役表] 注销 ≠ 名字消失：活跃表删除后按「活跃 → 退役 → not-found」解析，
        // 仍能拿到原 agentId（SendMessage 据此走 transcript 恢复 / 重拉）。
        MessageRecipientResolver.Target gone = resolve("a1111111111111111");
        assertThat(gone.kind()).as("注销后经退役表仍可寻址（CC names keep working after completion）")
            .isEqualTo(MessageRecipientResolver.Kind.AGENT);
        assertThat(agents.resolveActiveEntry(SELF, "researcher")).as("活跃表已清").isNull();
    }

    @Test
    @DisplayName("同名跨 session 不串：本会话名命中自己的 agent，别人的同名 agent 不可见")
    void sameNameAcrossSessionsNotMixed() {
        MessageRecipientResolver.Target mine = resolve("researcher");
        assertThat(mine.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_AGENT);
        assertThat(mine.agentId()).isEqualTo("a1111111111111111");
        assertThat(mine.ownerSessionId()).isEqualTo(SELF);
    }

    @Test
    @DisplayName("name [ref]：ref 匹配 → NAME_SESSION；ref 不匹配 → 不命中且不回落")
    void nameWithRef() {
        MessageRecipientResolver.Target ok = resolve("beta [" + PeerAddressing.refFor(OTHER) + "]");
        assertThat(ok.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_SESSION);
        assertThat(ok.sessionId()).isEqualTo(OTHER);

        MessageRecipientResolver.Target bad = resolve("beta [000000]");
        assertThat(bad.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(bad.mustNotFallback()).isTrue();
    }

    @Test
    @DisplayName("同名 live 会话 → AMBIGUOUS（明确列出，不随机选）")
    void ambiguousNames() {
        sessions.register(new PeerSessionRegistry.Registration(
            "sess-ccccccc3", "beta", "/p", PermissionMode.DEFAULT, 1L));
        MessageRecipientResolver.Target target = resolve("beta");
        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.AMBIGUOUS);
        assertThat(target.errorMessage()).contains(OTHER).contains("sess-ccccccc3");
    }

    @Test
    @DisplayName("team mailbox 只保留 team-lead；非 lead 名册成员一律代理解析（未注册 = not-found，不落 inbox）")
    void teamMailboxOnlyForLead() {
        MessageRecipientResolver.Target noTeam = resolve("ghost");
        assertThat(noTeam.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);

        // 有 team 但名册里没有该名字 → 仍 UNRESOLVED（⛔ 不落 inbox），并给出候选
        MessageRecipientResolver.Target notInRoster = resolver.resolve(
            new MessageRecipientResolver.Request("mat", SELF, "team-x", true, List.of("team-lead", "mate")));
        assertThat(notInRoster.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(notInRoster.errorMessage()).contains("No agent named").contains("mate");

        // 名册里的非 lead 成员：未在 AgentNameRegistry 注册 ⇒ not-found（CC roster-first + breaking：
        // 不再写 teammate inbox）。注册后（B1 spawn 侧）由 resolveName step 1 命中为 NAME_AGENT。
        MessageRecipientResolver.Target memberUnregistered = resolver.resolve(
            new MessageRecipientResolver.Request("mate", SELF, "team-x", true, List.of("team-lead", "mate")));
        assertThat(memberUnregistered.kind())
            .isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);

        agents.register(SELF, "mate", "alice@team-x");
        MessageRecipientResolver.Target memberRegistered = resolver.resolve(
            new MessageRecipientResolver.Request("mate", SELF, "team-x", true, List.of("team-lead", "mate")));
        assertThat(memberRegistered.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_AGENT);
        assertThat(memberRegistered.agentId()).isEqualTo("alice@team-x");

        // team-lead（不是可拉起的 local_agent）→ 唯一仍走 mailbox 的收件名
        MessageRecipientResolver.Target lead = resolver.resolve(
            new MessageRecipientResolver.Request("team-lead", SELF, "team-x", true, List.of("team-lead", "mate")));
        assertThat(lead.kind()).isEqualTo(MessageRecipientResolver.Kind.TEAM_MAILBOX);
    }

    @Test
    @DisplayName("worker → team-lead 仍走 team mailbox（不是发给自身）")
    void workerToTeamLeadIsMailbox() {
        MessageRecipientResolver.Target target = resolver.resolve(
            new MessageRecipientResolver.Request("team-lead", SELF, "team-x", true,
                List.of("team-lead", "mate"), "mate@team-x"));
        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.TEAM_MAILBOX);
        assertThat(target.selfSent()).isFalse();
    }

    @Test
    @DisplayName("ListAgents 的 `name [-]` 形态按裸名解析（teammate 无 ref）")
    void noRefMarkerStripped() {
        agents.register(SELF, "mate", "alice@team-x");
        MessageRecipientResolver.Target target = resolver.resolve(
            new MessageRecipientResolver.Request("mate [-]", SELF, "team-x", true,
                List.of("team-lead", "mate")));
        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_AGENT);
        assertThat(target.agentId()).isEqualTo("alice@team-x");
    }
}
