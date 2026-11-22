package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.messaging.PeerAddressing;
import com.nexusai.application.agent.messaging.PeerRefPins;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ListAgents 契约：canonical/alias、能力门、live 数据源、分节与截断、标题隔离；
 * B4 = teammate 去重 / 状态 / ref 规则 / pin。
 */
@DisplayName("ListAgents · 契约")
class ListAgentsContractTest {

    private static final String SELF = "sess-aaaaaaa1";
    private static final String OTHER = "sess-bbbbbbb2";
    private static final String THIRD = "sess-ccccccc3";

    private ListAgentsTool tool;
    private PeerSessionRegistry sessions;
    private AgentNameRegistry registry;
    private PeerRefPins pins;

    @BeforeEach
    void setUp() {
        // ToolUseContext 构造期经 CwdResolution 解析会话项目根（fail-loud）：合成会话须显式绑定
        // 一个真实存在的目录，否则纯 JUnit 环境（回源器未接线）会抛 UnresolvedProjectRootException。
        SessionProjectRoot.setForSession(SELF, System.getProperty("user.dir"));
        sessions = new PeerSessionRegistry();
        sessions.register(new PeerSessionRegistry.Registration(
            SELF, "alpha", "/p", PermissionMode.DEFAULT, 1L));
        sessions.register(new PeerSessionRegistry.Registration(
            OTHER, "beta", "/p", PermissionMode.DEFAULT, 1L));
        registry = new AgentNameRegistry();
        pins = new PeerRefPins();
        tool = new ListAgentsTool();
        tool.setPeerSessionRegistry(sessions);
        tool.setAgentNameRegistry(registry);
        tool.setTeamHelpers(new TeamHelpers());
        tool.setRefPins(pins);
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    private static ToolUseContext tuc() {
        return new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SELF, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT);
    }

    private static ToolUseContext tucAsTeammate() {
        return tuc().withTeammateIdentity(new TeammateIdentity(
            "helper@team", "helper", "team", null, false, SELF));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolUseContext ctx) {
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(),
            ListAgentsTool.NAME, JsonNodeFactory.instance.objectNode());
        return (Map<String, Object>) ((ToolResult<?>) tool.execute(call, ctx)).data();
    }

    private Map<String, Object> data() {
        return data(tuc());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static String section(String listing, String startTitle, String nextTitle) {
        int a = listing.indexOf(startTitle);
        if (a < 0) {
            return "";
        }
        int b = nextTitle == null ? -1 : listing.indexOf(nextTitle, a);
        return b < 0 ? listing.substring(a) : listing.substring(a, b);
    }

    private static List<String> refsFor(String text, String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(Pattern.quote("  " + name + " [")
            + "([0-9a-f]+)]").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("canonical ListAgents + alias ListPeers；能力门默认关、sysprop 可开")
    void nameAliasAndGate() {
        assertThat(tool.name()).isEqualTo("ListAgents");
        assertThat(tool.aliases()).containsExactly("ListPeers");

        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.feature.messaging-beyond-own-agents", "false");
        System.setProperty("nexusai.swarms.killswitch", "true");
        System.setProperty("nexusai.user.type", "x");
        assertThat(tool.isEnabled()).isFalse();

        System.setProperty("nexusai.feature.messaging-beyond-own-agents", "true");
        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("listing：排除自身、包含其他 live 会话；标题进不可信围栏")
    void listingExcludesSelfAndFencesTitles() {
        String listing = (String) data().get("listing");
        assertThat(listing).contains("Peer sessions").contains("beta [")
            .doesNotContain("alpha [").contains("other-sessions nonce=")
            .contains("untrusted=\"true\"");
    }

    @Test
    @DisplayName("B4-a：teammate 只出现在 Teammates，不再重复出现在 Subagents")
    void teammateIsNotDuplicatedInSubagents() {
        registry.register(SELF, "alice", "alice@team");
        registry.register(SELF, "worker", "a00000000000000a1");

        String listing = (String) data().get("listing");
        assertThat(count(listing, "alice [")).as("同一实体只出现一次").isEqualTo(1);
        assertThat(section(listing, "Subagents", "Teammates"))
            .contains("worker [").doesNotContain("alice");
        assertThat(section(listing, "Teammates", "Peer sessions"))
            .containsPattern("alice \\[[0-9a-f]+]  \u00b7  teammate  \u00b7  running");
    }

    @Test
    @DisplayName("B4-b：同名不同实体 ref 不同，且两条地址都能按要求回填")
    void sameNameDifferentEntitiesHaveDistinctRefs() {
        registry.register(SELF, "alice", "alice@team");
        sessions.register(new PeerSessionRegistry.Registration(
            THIRD, "alice", "/p", PermissionMode.DEFAULT, 1L));

        String listing = (String) data().get("listing");
        String teammates = section(listing, "Teammates", "Peer sessions");
        String peers = section(listing, "Peer sessions", null);
        String teammateRef = refsFor(teammates, "alice").get(0);
        String sessionRef = refsFor(peers, "alice").get(0);

        assertThat(teammateRef).as("同名不同实体 ref 必须不同").isNotEqualTo(sessionRef);
        // 回填：两套地址按 (name, ref) 解析回各自身份（ref 前缀匹配 / 方括号解析）。
        PeerAddressing.ParsedName t = PeerAddressing.parseNameWithRef("alice [" + teammateRef + "]");
        PeerAddressing.ParsedName s = PeerAddressing.parseNameWithRef("alice [" + sessionRef + "]");
        assertThat(t.name()).isEqualTo("alice");
        assertThat(s.name()).isEqualTo("alice");
        assertThat(t.ref()).isEqualTo(teammateRef);
        assertThat(s.ref()).isEqualTo(sessionRef);
        assertThat(PeerAddressing.refMatches("agent", "alice@team", t.ref())).isTrue();
        assertThat(PeerAddressing.refMatches("session", THIRD, s.ref())).isTrue();
    }

    @Test
    @DisplayName("B4-c：含 <img onerror> 的他人标题只输出转义文本，围栏覆盖整行")
    void maliciousTitleOnlyEmitsEscapedText() {
        sessions.register(new PeerSessionRegistry.Registration(
            THIRD, "<img src=x onerror=alert(1)>", "/p", PermissionMode.DEFAULT, 1L));

        String listing = (String) data().get("listing");
        assertThat(listing).doesNotContain("<img").doesNotContain("<img src");
        assertThat(listing).contains("&lt;img");
        Matcher tag = Pattern.compile("<[^>]*>").matcher(listing);
        while (tag.find()) {
            assertThat(tag.group())
                .matches("<other-sessions nonce=\"[0-9a-f]+\" untrusted=\"true\">"
                    + "|</other-sessions nonce=\"[0-9a-f]+\">");
        }
        assertThat(listing).containsPattern(
            "&lt;img-src=x-onerror=alert\\(1\\)&gt; \\[[0-9a-f]+]  \u00b7  session  \u00b7  ");
    }

    @Test
    @DisplayName("B4-d：>100 行截断 + note；截断行不入 pin，已打印行才入 pin")
    void truncatedRowsAreNotPinned() {
        for (int i = 0; i < 105; i++) {
            sessions.register(new PeerSessionRegistry.Registration(
                String.format("sess-cccc%04x", i), "peer-" + i, "/p",
                PermissionMode.DEFAULT, 1L));
        }
        Map<String, Object> data = data();
        String listing = (String) data.get("listing");
        assertThat(listing).contains("more not shown");
        assertThat(listing.length()).isLessThanOrEqualTo(10_000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notes = (List<Map<String, Object>>) data.get("notes");
        assertThat(notes).extracting(n -> n.get("kind")).contains("list_truncated");

        Map<String, String> pinned = pins.snapshot(SELF);
        assertThat(pinned).hasSize(100);
        assertThat(pinned.values()).allMatch(name -> listing.contains("  " + name + " ["));
    }

    @Test
    @DisplayName("B4-e：已退役 teammate 按 stopped 呈现（且不属于 Subagents）")
    void retiredTeammateShowsStopped() {
        ListAgentsTool teammateTool = new ListAgentsTool();
        teammateTool.setPeerSessionRegistry(sessions);
        teammateTool.setAgentNameRegistry(registry);
        teammateTool.setTeamHelpers(new RosterTeamHelpers(List.of("alice", "helper")));
        teammateTool.setRefPins(pins);

        registry.register(SELF, "alice", "alice@team");
        registry.retireAgent(SELF, "alice", "alice@team", "killed", "user");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ((ToolResult<?>) teammateTool.execute(
            new ToolUseBlock(UUID.randomUUID().toString(), ListAgentsTool.NAME,
                JsonNodeFactory.instance.objectNode()), tucAsTeammate())).data();
        String listing = (String) data.get("listing");

        assertThat(listing).contains("Teammates");
        assertThat(listing).containsPattern("alice \\[[0-9a-f]+]  \u00b7  teammate  \u00b7  stopped");
        assertThat(count(listing, "alice [")).isEqualTo(1);
        assertThat(section(listing, "Subagents", "Teammates")).doesNotContain("alice");
        assertThat(listing).doesNotContain("helper [");  // 自身不作为可发送目标
    }

    /** 名册桩（TeamHelpers 读盘；单测直接给成员表）。 */
    private static final class RosterTeamHelpers extends TeamHelpers {
        private final List<String> names;

        private RosterTeamHelpers(List<String> names) {
            this.names = List.copyOf(names);
        }

        @Override
        public List<String> listMemberNames(String teamName) {
            return names;
        }
    }
}
