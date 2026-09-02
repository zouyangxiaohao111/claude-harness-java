package com.nexusai.application.agent.team;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TeamHelpers 补齐 CC teamHelpers.ts 导出函数的契约测试（T-C）。
 *
 * <p>WHY 本测试验证意图（规则九，对齐 CC 实际 TS 源码行为，不信注释）：
 * <ul>
 *   <li>{@code sanitizeName/sanitizeAgentName} 是 tmux window / worktree / 确定性 agent ID 的
 *       清洗入口 —— team 文件路径互认依赖这两个纯函数（CC teamHelpers.ts:100/:108）；</li>
 *   <li>成员函数（removeTeammateFromTeamFile/removeMemberFromTeam/setMemberMode/
 *       setMultipleMemberModes/setMemberActive/syncTeammateMode）操作 config.json {@code members}
 *       数组 —— 与 TeamDiscovery 读取的 CC TeamFile.members 同构，缺一即 team 成员模型残缺；</li>
 *   <li>hiddenPaneIds 增删 + session 清理追踪（register/unregister/cleanupSessionTeams）与
 *       cleanupTeamDirectories 是 CC session 退出清理链（teamHelpers.ts:235-683）。</li>
 * </ul>
 *
 * <p>RED 证据：T-C 前这些方法不存在（teamHelpers 仅 10 方法），test-compile 失败即 RED。
 */
class TeamHelpersTest {

    private static final String TEAM = "research-team";

    private final TeamHelpers helpers = new TeamHelpers();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    private void writeConfig(String json) {
        helpers.writeConfig(TEAM, json);
    }

    private String readConfig() {
        return helpers.readConfig(TEAM);
    }

    private static String membersJson(String... memberObjects) {
        return "{\"leadAgentId\":\"lead@research-team\",\"members\":["
            + String.join(",", memberObjects) + "]}";
    }

    private static String member(String agentId, String name, String tmuxPaneId, String mode, Boolean isActive) {
        StringBuilder sb = new StringBuilder("{\"agentId\":\"").append(agentId)
            .append("\",\"name\":\"").append(name).append("\"");
        if (tmuxPaneId != null) {
            sb.append(",\"tmuxPaneId\":\"").append(tmuxPaneId).append("\"");
        }
        if (mode != null) {
            sb.append(",\"mode\":\"").append(mode).append("\"");
        }
        if (isActive != null) {
            sb.append(",\"isActive\":").append(isActive);
        }
        return sb.append("}").toString();
    }

    @Test
    void sanitizeName_replacesNonAlnumWithDashAndLowercases() {
        assertThat(TeamHelpers.sanitizeName("Research Team@X")).isEqualTo("research-team-x");
        assertThat(TeamHelpers.sanitizeName("ABC_def")).isEqualTo("abc-def");
    }

    @Test
    void sanitizeAgentName_replacesAtWithDash() {
        assertThat(TeamHelpers.sanitizeAgentName("researcher@my-team")).isEqualTo("researcher-my-team");
    }

    @Test
    void leadAgentId_readsCamelCaseField() {
        // WHY: CC teamHelpers.ts:68 TeamFile.leadAgentId（camelCase）是 config.json 落盘字段；
        //      lead_agent_id（snake_case）仅是 TeamCreateTool.ts:55 工具 Output 返回契约。
        //      若误读 snake_case，team lead 身份解析恒 null → SendMessageTool plan 审批守卫恒非 lead。
        writeConfig("{\"leadAgentId\":\"lead@research-team\",\"members\":[]}");
        assertThat(helpers.leadAgentId(TEAM)).isEqualTo("lead@research-team");

        // 无 leadAgentId 键 → null（不抛）
        writeConfig("{\"members\":[]}");
        assertThat(helpers.leadAgentId(TEAM)).isNull();
    }

    @Test
    void removeTeammateFromTeamFile_removesByAgentIdOrName() {
        writeConfig(membersJson(
            member("a1@t", "researcher", "p1", "default", true),
            member("a2@t", "reviewer", "p2", "default", true)));

        assertThat(helpers.removeTeammateFromTeamFile(TEAM, "a2@t", null)).isTrue();
        // 按 name 移除剩余成员
        assertThat(helpers.removeTeammateFromTeamFile(TEAM, null, "researcher")).isTrue();
        // 成员已空 → 再移除返回 false
        assertThat(helpers.removeTeammateFromTeamFile(TEAM, "a1@t", null)).isFalse();
    }

    @Test
    void removeTeammateFromTeamFile_noIdentifier_returnsFalse() {
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));
        assertThat(helpers.removeTeammateFromTeamFile(TEAM, null, null)).isFalse();
    }

    @Test
    void removeMemberFromTeam_removesMemberAndHiddenPaneId() {
        writeConfig("{\"members\":[" + member("a1@t", "researcher", "p1", "default", true)
            + "],\"hiddenPaneIds\":[\"p1\"]}");

        assertThat(helpers.removeMemberFromTeam(TEAM, "p1")).isTrue();
        String config = readConfig();
        assertThat(config).doesNotContain("\"a1@t\"");
        assertThat(config).doesNotContain("\"p1\"");
    }

    @Test
    void setMemberMode_updatesModeAndNoOpWhenUnchanged() {
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));

        assertThat(helpers.setMemberMode(TEAM, "researcher", "acceptEdits")).isTrue();
        assertThat(readConfig()).contains("\"mode\":\"acceptEdits\"");
        // 未变 → true（不写）
        assertThat(helpers.setMemberMode(TEAM, "researcher", "acceptEdits")).isTrue();
        // 成员不存在 → false
        assertThat(helpers.setMemberMode(TEAM, "ghost", "plan")).isFalse();
    }

    @Test
    void setMultipleMemberModes_updatesAllInOneAtomicWrite() {
        writeConfig(membersJson(
            member("a1@t", "researcher", "p1", "default", true),
            member("a2@t", "reviewer", "p2", "default", true)));

        assertThat(helpers.setMultipleMemberModes(TEAM, List.of(
            new TeamHelpers.MemberModeUpdate("researcher", "plan"),
            new TeamHelpers.MemberModeUpdate("reviewer", "acceptEdits")))).isTrue();

        String config = readConfig();
        assertThat(config).contains("\"mode\":\"plan\"").contains("\"mode\":\"acceptEdits\"");
    }

    @Test
    void setMemberActive_updatesActiveAndNoOpWhenUnchanged() {
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));

        helpers.setMemberActive(TEAM, "researcher", false);
        assertThat(readConfig()).contains("\"isActive\":false");
        // 未变 → 返回（不写）
        helpers.setMemberActive(TEAM, "researcher", false);
        assertThat(readConfig()).contains("\"isActive\":false");
    }

    @Test
    void addAndRemoveHiddenPaneId_togglePresence() {
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));

        assertThat(helpers.addHiddenPaneId(TEAM, "pane-9")).isTrue();
        assertThat(readConfig()).contains("\"pane-9\"");
        assertThat(helpers.removeHiddenPaneId(TEAM, "pane-9")).isTrue();
        assertThat(readConfig()).doesNotContain("\"pane-9\"");
    }

    @Test
    void sessionCleanup_registersAndCleansTeamDirectory() {
        helpers.registerTeamForSessionCleanup("sess-a", TEAM);
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));

        helpers.cleanupSessionTeams("sess-a");
        // team 目录 + config.json 已被删除
        assertThat(Files.exists(helpers.configPath(TEAM))).isFalse();
    }

    @Test
    void unregisterTeamForSessionCleanup_preventsCleanup() {
        helpers.registerTeamForSessionCleanup("sess-a", TEAM);
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));
        helpers.unregisterTeamForSessionCleanup(TEAM);

        helpers.cleanupSessionTeams("sess-a");
        // 已 unregister → 不清理
        assertThat(Files.exists(helpers.configPath(TEAM))).isTrue();
    }

    @Test
    void cleanupSessionTeams_onlyCleansOwnSession() {
        // WHY: [A3] 会话级化核心——会话 A 创建的 team 不得被会话 B 的清理钩子删除
        //   （跨会话误删/误防删 = 进程级 Set 的原生缺陷，multi-session-vs-cc-single-session 铁律）。
        //   变异点：删 sess-b 时误删 sess-a team = 跨会话破坏。
        helpers.registerTeamForSessionCleanup("sess-a", TEAM);
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));

        helpers.cleanupSessionTeams("sess-b");
        assertThat(Files.exists(helpers.configPath(TEAM)))
                .as("会话 B 清理不得误删会话 A 的 team")
                .isTrue();

        helpers.cleanupSessionTeams("sess-a");
        assertThat(Files.exists(helpers.configPath(TEAM)))
                .as("会话 A 自身清理应删除其 team")
                .isFalse();
    }

    @Test
    void register_derivedUuid_and_cleanupRawSessKey_match() {
        // WHY: [session-id-short] TeamHelpers 会话桶键 = short 直键（TeamHelpers.java:60-62，
        //   写入侧 TeamCreateTool 与清理侧 SessionService.delete 同 short 键，不再 canonicalUuid 归一）。
        //   同键注册 + 清理须命中同一会话桶 → 会话删除钩子找到 team → 清理（变异点：键不一致 →
        //   孤儿残留）。
        String sessKey = "sess-00000abc";
        helpers.registerTeamForSessionCleanup(sessKey, TEAM);
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));

        // 同一 short 键清理 → 命中 → 删除 team 目录
        helpers.cleanupSessionTeams(sessKey);
        assertThat(Files.exists(helpers.configPath(TEAM)))
                .as("同一 short 键注册 + 清理须命中同一会话桶")
                .isFalse();
    }
    // ═══════════ Batch2 S1 · appendTeamMember（对齐 CC spawnMultiAgent.ts:495-509）═══════════

    @Test
    void appendTeamMember_writesFullMemberShape() throws Exception {
        // WHY: spawn 后 appendTeamMember 写 config.json members —— teammate 对 TeamDiscovery /
        //      广播（listMemberNames）/ TeamDelete（活跃守卫）可见（Batch2 S1 断链）。10 字段
        //      形状对齐 CC spawnMultiAgent.ts:495-509（agentId/name/agentType/model/prompt/color/
        //      planModeRequired/joinedAt/tmuxPaneId/cwd/subscriptions/backendType）。
        writeConfig(membersJson(member("lead@t", "team-lead", "", null, null)));

        boolean appended = helpers.appendTeamMember(TEAM, new TeamHelpers.TeamMemberRef(
                "mate@t", "mate", "researcher", "claude-opus", "do research", "cyan",
                false, "in-process", "/tmp/team", "in-process"));

        assertThat(appended).as("append 必须成功").isTrue();
        String config = readConfig();
        assertThat(config).contains("\"agentId\":\"mate@t\"");
        assertThat(config).contains("\"name\":\"mate\"");
        assertThat(config).contains("\"agentType\":\"researcher\"");
        assertThat(config).contains("\"model\":\"claude-opus\"");
        assertThat(config).contains("\"prompt\":\"do research\"");
        assertThat(config).contains("\"color\":\"cyan\"");
        assertThat(config).contains("\"planModeRequired\":false");
        assertThat(config).contains("\"joinedAt\"");
        assertThat(config).contains("\"tmuxPaneId\":\"in-process\"");
        assertThat(config).contains("\"cwd\":\"/tmp/team\"");
        assertThat(config).contains("\"subscriptions\":[]");
        assertThat(config).contains("\"backendType\":\"in-process\"");
        // teammate 对广播/TeamDiscovery 可见
        assertThat(helpers.listMemberNames(TEAM)).contains("mate");
    }

    @Test
    void appendTeamMember_missingTeam_returnsFalseNoThrow() {
        // WHY: CC appendTeamMember 读 team 文件失败抛错（spawnMultiAgent.ts:490-493 team 消失）；
        //      Java 放宽为 log.warn 返回 false（append 失败不阻断 spawn，Batch2 S1 设计决策记录差异）。
        boolean appended = helpers.appendTeamMember("no-such-team", new TeamHelpers.TeamMemberRef(
                "x@t", "x", null, null, null, null, false, "in-process", null, "in-process"));
        assertThat(appended).as("team 不存在 → false（不抛）").isFalse();
    }

    @Test
    void appendTeamMember_appendsNotReplaces() {
        // WHY: 重复 spawn 追加（CC members.push 语义）——已存在成员不被覆盖，listMemberNames 含两者。
        writeConfig(membersJson(member("lead@t", "team-lead", "", null, null)));
        helpers.appendTeamMember(TEAM, new TeamHelpers.TeamMemberRef(
                "m1@t", "m1", null, null, null, null, false, "in-process", null, "in-process"));
        helpers.appendTeamMember(TEAM, new TeamHelpers.TeamMemberRef(
                "m2@t", "m2", null, null, null, null, false, "in-process", null, "in-process"));
        assertThat(helpers.listMemberNames(TEAM)).containsExactlyInAnyOrder("team-lead", "m1", "m2");
    }

    @Test
    void cleanupSessionTeams_isSessionIsolated_doesNotTouchOtherSession() {
        // WHY: [A1-FIX] sessionCreatedTeams 为进程级 Set 时，删除会话 A 会删掉会话 B 活跃的
        //   config.json/inboxes/tasks（跨会话数据破坏，探查 A1）。会话级化后
        //   cleanupSessionTeams(sessionId) 只清该会话登记的 teams，会话 B 目录不受影响。
        String otherTeam = "other-team";
        helpers.registerTeamForSessionCleanup("sess-1", TEAM);
        helpers.registerTeamForSessionCleanup("sess-2", otherTeam);
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));
        helpers.writeConfig(otherTeam, membersJson(member("b1@t", "researcher", "p1", "default", true)));

        helpers.cleanupSessionTeams("sess-1");

        // 会话 A 的 team 被清理，会话 B 的 team 目录保持活跃
        assertThat(Files.exists(helpers.configPath(TEAM))).isFalse();
        assertThat(Files.exists(helpers.configPath(otherTeam))).isTrue();
    }

    @Test
    void cleanupSessionTeams_unknownSession_isNoOp() {
        // WHY: 清理不存在的 session（未登记 / 已清）不应删除任何目录（幂等 no-op）。
        helpers.registerTeamForSessionCleanup("sess-1", TEAM);
        writeConfig(membersJson(member("a1@t", "researcher", "p1", "default", true)));

        helpers.cleanupSessionTeams("sess-unknown");

        // 未归因到 sess-unknown → team 目录保留
        assertThat(Files.exists(helpers.configPath(TEAM))).isTrue();
    }
}
