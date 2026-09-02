package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Batch2 B1] leader inbox teammate 消息注入 LLM loop · 对齐 CC attachments.ts:959-960
 * maybe('teammate_mailbox') + getTeammateMailboxAttachments :3614-3796。
 *
 * <p>WHY（规则九 · 验证意图）：
 * <ul>
 *   <li><b>注入</b>：teammate→leader 消息必须以 meta user message 注入 leader 下一轮 LLM query
 *       （否则 leader 看不到队友回复 —— 探查 B1 P0 断链）；</li>
 *   <li><b>构建后标已读</b>：注入后 inbox 消息标已读（CC「build before mark read」），二次调用不重复注入；</li>
 *   <li><b>过滤</b>：结构化协议消息（permission_request 等）不注入、不标已读（CC :3673-3675，
 *       防抢 useInboxPoller 的 handler 消息）；</li>
 *   <li><b>idle 折叠</b>：同 agent 多条 idle_notification 只保留最新（CC :3726-3747）；</li>
 *   <li><b>门控</b>：agent-swarms 关闭 / 无 teamContext / 非 leader → 原样返回（不读 inbox、不标已读）。</li>
 * </ul>
 */
@DisplayName("Batch2 B1 · leader inbox teammate 消息注入（maybeInjectTeammateMailbox）")
class TeammateMailboxAttachmentInjectTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        // 启用 agent-swarms（CC isAgentSwarmsEnabled gate）
        System.setProperty("nexusai.experimental.agent-teams", "true");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.experimental.agent-teams");
        System.clearProperty("nexusai.team.name");
        TaskSystemConfig.clearForTest();
    }

    /** 带 appState 桥的 ToolUseContext · 对齐 CC ToolUseContext.getAppState/setAppState。 */
    private ToolUseContext appStateCtx(Map<String, Object> appState) {
        return ToolUseContext.of(
                UUID.randomUUID(), "",
                PermissionMode.DEFAULT,
                List.of(), "", AbortController.NOOP,
                List.of(), null, PermissionMode.DEFAULT, Map.of(),
                false, "", null, null, null, null,
                prev -> appState,
                updater -> {
                    Map<String, Object> next = updater.apply(appState);
                    appState.clear();
                    appState.putAll(next);
                },
                null, null);
    }

    private Map<String, Object> teamContextAppState(String teamName) {
        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", teamName));
        return appState;
    }

    private List<ChatMessageDto> inject(String teamName) {
        return AgentLoopContext.maybeInjectTeammateMailbox(
                null, null, appStateCtx(teamContextAppState(teamName)), List.of());
    }

    // ═══════════════════════ 注入 + 标已读 ═══════════════════════

    @Test
    @DisplayName("leader inbox 有未读 teammate 消息 → 注入 1 条 meta user message（teammate-message XML）并标已读")
    void leaderInbox_injectsMetaUserMessage_marksRead() {
        String team = "inject-team";
        TeammateMailbox.writeToMailbox("team-lead",
                TeammateMailbox.TeammateMessage.of("researcher", "找到方案了", TeammateMailbox.isoNow(), "cyan"),
                team);

        List<ChatMessageDto> injected = inject(team);

        assertThat(injected).as("必须追加 1 条 meta user message").hasSize(1);
        ChatMessageDto last = injected.get(0);
        assertThat(last.role()).isEqualTo(Role.user);
        assertThat(last.isMeta()).as("对齐 CC createUserMessage({..., isMeta:true})，不污染用户转录").isTrue();
        assertThat(last.content()).contains("<teammate-message teammate_id=\"researcher\" color=\"cyan\">");
        assertThat(last.content()).contains("找到方案了");

        // 构建后标已读：二次调用不重复注入
        List<ChatMessageDto> second = inject(team);
        assertThat(second).as("注入后 inbox 已标已读 → 二次调用不注入").isEmpty();
    }

    @Test
    @DisplayName("多条消息逐条渲染、\\n\\n join，队尾追加（对齐 CC push tail）")
    void leaderInbox_multipleMessages_joinDoubleNewline() {
        String team = "multi-team";
        TeammateMailbox.writeToMailbox("team-lead",
                TeammateMailbox.TeammateMessage.of("a", "one", TeammateMailbox.isoNow(), null), team);
        TeammateMailbox.writeToMailbox("team-lead",
                TeammateMailbox.TeammateMessage.of("b", "two", TeammateMailbox.isoNow(), null), team);

        List<ChatMessageDto> injected = inject(team);
        assertThat(injected).hasSize(1);
        assertThat(injected.get(0).content()).contains("<teammate-message teammate_id=\"a\">\none\n</teammate-message>");
        assertThat(injected.get(0).content()).contains("\n\n<teammate-message teammate_id=\"b\">");
        assertThat(injected.get(0).content()).contains("two");
    }

    // ═══════════════════════ 过滤 + idle 折叠 ═══════════════════════

    @Test
    @DisplayName("结构化协议消息（permission_request）→ 不注入、不标已读（CC :3673-3675）")
    void leaderInbox_protocolMessage_filteredAndLeftUnread() {
        String team = "proto-team";
        // 权限请求消息（结构化协议 —— useInboxPoller 的 handler 消息，附件不得吞掉）
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "permission_request");
        req.put("request_id", "perm-1");
        req.put("agent_id", "worker1");
        req.put("tool_name", "Bash");
        req.put("tool_use_id", "tu-1");
        req.put("description", "run ls");
        req.put("input", Map.of());
        req.put("permission_suggestions", List.of());
        TeammateMailbox.writeToMailbox("team-lead",
                TeammateMailbox.TeammateMessage.of("worker1", TeammateMailbox.toCompactJson(req),
                        TeammateMailbox.isoNow(), null), team);

        List<ChatMessageDto> injected = inject(team);
        assertThat(injected).as("permission_request 必须被过滤（不注入 LLM）").isEmpty();
        // 不标已读 —— SwarmLeaderPermissionDispatcher 仍能读到
        assertThat(TeammateMailbox.readUnreadMessages("team-lead", team))
                .as("结构化协议消息保持未读，供 useInboxPoller 路由").hasSize(1);
    }

    @Test
    @DisplayName("同 agent 多条 idle_notification → 只保留最新（CC :3726-3747）")
    void leaderInbox_idleNotifications_collapsedToLatest() {
        String team = "idle-team";
        TeammateMailbox.writeToMailbox("team-lead",
                TeammateMailbox.TeammateMessage.of("worker1",
                        TeammateMailbox.toCompactJson(TeammateMailbox.createIdleNotification(
                                "worker1", "available", "first-summary", null, null, null)),
                        TeammateMailbox.isoNow(), null), team);
        TeammateMailbox.writeToMailbox("team-lead",
                TeammateMailbox.TeammateMessage.of("worker1",
                        TeammateMailbox.toCompactJson(TeammateMailbox.createIdleNotification(
                                "worker1", "available", "second-summary", null, null, null)),
                        TeammateMailbox.isoNow(), null), team);

        List<ChatMessageDto> injected = inject(team);
        assertThat(injected).hasSize(1);
        assertThat(injected.get(0).content())
                .as("只保留最新 idle_notification")
                .contains("second-summary")
                .doesNotContain("first-summary");
    }

    // ═══════════════════════ 门控 ═══════════════════════

    @Test
    @DisplayName("agent-swarms 关闭 → 原样返回（不读 inbox、不标已读，CC :3617-3619）")
    void gated_agentSwarmsDisabled() {
        System.clearProperty("nexusai.experimental.agent-teams");
        String team = "off-team";
        TeammateMailbox.writeToMailbox("team-lead",
                TeammateMailbox.TeammateMessage.of("r", "hi", TeammateMailbox.isoNow(), null), team);

        List<ChatMessageDto> result = AgentLoopContext.maybeInjectTeammateMailbox(
                null, null, appStateCtx(teamContextAppState(team)), List.of());

        assertThat(result).as("agent-swarms 关 → 原样（不注入）").isEmpty();
        assertThat(TeammateMailbox.readUnreadMessages("team-lead", team))
                .as("agent-swarms 关 → 不标已读").hasSize(1);
    }

    @Test
    @DisplayName("无 teamContext / tuc null → 原样返回（CC getTeamName appState.teamContext 缺失）")
    void gated_noTeamContext() {
        // 无 teamContext
        List<ChatMessageDto> noCtx = AgentLoopContext.maybeInjectTeammateMailbox(
                null, null, appStateCtx(new LinkedHashMap<>()), List.of());
        assertThat(noCtx).as("无 teamContext → 原样").isEmpty();

        // tuc null
        List<ChatMessageDto> nullTuc = AgentLoopContext.maybeInjectTeammateMailbox(
                null, null, null, List.of());
        assertThat(nullTuc).as("tuc null → 原样").isEmpty();
    }

    @Test
    @DisplayName("in-process teammate 上下文（TeammateContext 存在）→ 原样（防误读 leader inbox，CC :3690-3692）")
    void gated_inProcessTeammate() {
        String team = "mate-team";
        TeammateMailbox.writeToMailbox("team-lead",
                TeammateMailbox.TeammateMessage.of("r", "hi", TeammateMailbox.isoNow(), null), team);
        // 模拟 teammate 线程：TeammateContext 当前上下文非 null
        com.nexusai.application.agent.team.TeammateContext ctx = com.nexusai.application.agent.team.TeammateContext.create(
                new com.nexusai.application.agent.team.TeammateContext.TeammateConfig(
                        "mate@t", "mate", "t", null, false, "s",
                        com.nexusai.infra.util.AbortControllerFactory.create()));

        List<ChatMessageDto> result = com.nexusai.application.agent.team.TeammateContext.runWithTeammateContext(ctx,
                () -> AgentLoopContext.maybeInjectTeammateMailbox(null, null,
                        appStateCtx(teamContextAppState(team)), new ArrayList<>()));

        assertThat(result).as("teammate 上下文 → 不读 leader inbox").isEmpty();
    }
}
