package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 · teammate 名字走代理解析（对齐 CC roster-first {@code Qt()}），
 * ⛔ 不再落 TEAM_MAILBOX；team mailbox 只保留 {@code team-lead}。
 *
 * <p>WHY（验证意图）：改前 resolver 的 step 4 对「团队名册命中」一律返回 TEAM_MAILBOX，
 * 而 SendMessage 的 TEAM_MAILBOX 分支直接 {@code writeToMailbox} —— teammate 的消息被写进
 * 文件邮箱，但 in-process teammate 的消费点是 {@code AutonomousAgentLoop.pendingUserMessages}，
 * 于是消息静默滞留。本用例锁死「名册非 lead 名字绝不返回 TEAM_MAILBOX」。
 */
@DisplayName("B2 · teammate 路由：代理解析而非文件邮箱")
class TeammateRoutingTest {

    private static final String LEAD_SESSION = "sess-aaaaaaa1";
    private static final String TEAM = "team-x";
    private static final List<String> ROSTER = List.of("team-lead", "alice");

    private AgentNameRegistry agents;
    private MessageRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        agents = new AgentNameRegistry();
        // 退役时解析 transcript 路径：钉到临时目录，⛔ 不读用户真实 config home。
        agents.setSessionDirResolver(id -> java.nio.file.Paths.get(
            System.getProperty("java.io.tmpdir"), "tb2-router-unused"));
        resolver = new MessageRecipientResolver();
        resolver.setPeerSessionRegistry(new PeerSessionRegistry());
        resolver.setAgentNameRegistry(agents);
    }

    private MessageRecipientResolver.Target resolve(String to) {
        return resolver.resolve(new MessageRecipientResolver.Request(to, LEAD_SESSION, TEAM, true, ROSTER));
    }

    @Test
    @DisplayName("a) 已注册的 teammate 名 → NAME_AGENT（agentId=name@team），不是 TEAM_MAILBOX")
    void registeredTeammate_isAgentNotMailbox() {
        agents.register(LEAD_SESSION, "alice", "alice@" + TEAM);

        MessageRecipientResolver.Target target = resolve("alice");

        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_AGENT);
        assertThat(target.agentId()).isEqualTo("alice@" + TEAM);
        assertThat(target.ownerSessionId()).isEqualTo(LEAD_SESSION);
        assertThat(target.kind()).isNotEqualTo(MessageRecipientResolver.Kind.TEAM_MAILBOX);
    }

    @Test
    @DisplayName("b) 只在名册、尚未 spawn（未注册）→ UNRESOLVED（not-found），⛔ 不落 inbox")
    void unspawnedRosterMember_isNotMailbox() {
        MessageRecipientResolver.Target target = resolve("alice");

        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(target.errorMessage()).contains("No agent named 'alice' is reachable");
    }

    @Test
    @DisplayName("c) team-lead → TEAM_MAILBOX（lead 不是可拉起的 local_agent 任务，保留邮箱语义）")
    void teamLead_staysMailbox() {
        MessageRecipientResolver.Target target = resolve("team-lead");

        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.TEAM_MAILBOX);
        assertThat(target.displayName()).isEqualTo("team-lead");
    }

    @Test
    @DisplayName("d) 未知名（不在名册）→ UNRESOLVED，不写任何 mailbox")
    void unknownName_notFound() {
        MessageRecipientResolver.Target target = resolve("ghost");

        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
        assertThat(target.errorMessage()).contains("No agent named 'ghost' is reachable");
    }

    @Test
    @DisplayName("e) 退役 teammate 仍可解析（活跃 → 退役 → not-found），供重拉分支使用")
    void retiredTeammate_stillResolvable() {
        agents.register(LEAD_SESSION, "alice", "alice@" + TEAM);
        agents.retireAgent(LEAD_SESSION, "alice", "alice@" + TEAM, "completed", null);

        MessageRecipientResolver.Target target = resolve("alice");

        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_AGENT);
        assertThat(target.agentId()).isEqualTo("alice@" + TEAM);
        assertThat(agents.resolveActiveEntry(LEAD_SESSION, "alice")).as("活跃表已清").isNull();
        assertThat(agents.retiredEntryById("alice@" + TEAM)).isNotNull();
    }

    @Test
    @DisplayName("f) 名册非 lead 成员在「无 team 上下文」时仍按 not-found（无邮箱可写）")
    void noTeamContext_rosterNameUnresolved() {
        MessageRecipientResolver.Target target = resolver.resolve(
            new MessageRecipientResolver.Request("alice", LEAD_SESSION, null, false, null));

        assertThat(target.kind()).isEqualTo(MessageRecipientResolver.Kind.UNRESOLVED);
    }

    @Test
    @DisplayName("g) PermissionMode 常量未参与判类（回归护栏：判类不依赖权限档）")
    void classificationIndependentOfPermissionMode() {
        assertThat(PermissionMode.DEFAULT).isNotNull();
        agents.register(LEAD_SESSION, "alice", "alice@" + TEAM);
        assertThat(resolve("alice").kind()).isEqualTo(MessageRecipientResolver.Kind.NAME_AGENT);
    }
}
