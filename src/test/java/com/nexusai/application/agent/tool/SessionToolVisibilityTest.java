package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Brief/IM/无人值守的单一可见性解析器（schema 源）。 */
class SessionToolVisibilityTest {

    private static final String SID = "sess-vis";
    private SessionRecord row;
    private SessionMapper mapper;

    @BeforeEach
    void setUp() {
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        SessionToolVisibility.resetGlobalFlags();
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> null, () -> null));
        MemoryBareModeConfig.reset();
        com.nexusai.common.SessionProjectRoot.setForSession(SID,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        row = new SessionRecord();
        row.setId(SID);
        mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(row);
        when(mapper.selectImToolOverrides(any())).thenReturn(null);
        SessionToolDisableConfig.setSessionMapper(mapper);
        SessionModeState.setSessionMapper(mapper);
        // coordinator 会话列读源：resolver 实例共用同一个 mapper mock。
        com.nexusai.application.agent.prompt.PromptAlignSettingsResolver resolver =
            new com.nexusai.application.agent.prompt.PromptAlignSettingsResolver();
        resolver.setSessionMapper(mapper);
        com.nexusai.application.agent.prompt.PromptAlignSettingsResolver.setStaticResolver(resolver);
    }

    @AfterEach
    void tearDown() {
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        SessionToolVisibility.resetGlobalFlags();
        LlmAgentLoop.setCoordinatorMode(null);
        com.nexusai.application.agent.prompt.PromptAlignSettingsResolver.setStaticResolver(null);
        com.nexusai.common.SessionProjectRoot.clearSession(SID);
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(com.nexusai.application.agent.tool.ToolUseBlock call) {
                return ToolResult.success(call.id(), name);
            }
        };
    }

    private List<String> schemaNames() {
        ToolUseContext tuc = ToolUseContext.of(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SID, PermissionMode.DEFAULT,
            List.of(tool("Bash"), tool("AskUserQuestion"), tool("SendUserMessage"),
                tool("SendImAsUser"), tool("ListImChats"),
                tool("ReadImMessages"), tool("SearchImContacts"),
                tool("wechat_search_messages"), tool("wechat_list_conversations"),
                tool("wechat_read_conversation")),
            "", AbortController.NOOP, List.of(), null, null, Map.of(), false, "");
        return LlmAgentLoop.sessionVisibleTools(tuc, QuerySource.USER).stream().map(Tool::name).toList();
    }

    @Test
    void briefNullHidesChannelImButKeepsLocalWechatTools() {
        // brief_mode=null（新会话）⇒ SendUserMessage 与「渠道类」IM 工具默认不可见。
        // 本机微信记录工具不依赖渠道绑定到本会话 ⇒ 立即暴露（用户 2026-11-22 裁定）。
        assertThat(schemaNames())
            .doesNotContain("SendUserMessage", "SendImAsUser", "ListImChats",
                "ReadImMessages", "SearchImContacts")
            .contains("Bash", "AskUserQuestion",
                "wechat_search_messages", "wechat_list_conversations",
                "wechat_read_conversation");
    }

    @Test
    void explicitOverrideFalseStillHidesLocalWechatTool() {
        // 「默认可用」只作用于无覆盖分支：用户显式关掉某个微信工具时仍必须隐藏。
        when(mapper.selectImToolOverrides(any()))
            .thenReturn("{\"wechat_search_messages\":false}");
        assertThat(schemaNames())
            .doesNotContain("wechat_search_messages")
            .contains("wechat_list_conversations");
    }

    @Test
    void briefOnRevealsSendUserMessageOnlyAndKeepsAsk() {
        row.setBriefMode(1);
        assertThat(schemaNames()).contains("SendUserMessage", "AskUserQuestion")
            .doesNotContain("SendImAsUser");
    }

    @Test
    void imOverrideEnablesOnlyThatToolAndKeepsUnrelatedDisabled() {
        row.setBriefMode(1);
        row.setDisabledTools("[\"Bash\"]");
        when(mapper.selectImToolOverrides(any())).thenReturn("{\"ListImChats\":true}");
        assertThat(schemaNames()).contains("ListImChats", "SendUserMessage")
            .doesNotContain("Bash", "SendImAsUser");
    }

    @Test
    void unattendedExcludesAskWithoutTouchingBrief() {
        row.setBriefMode(1);
        row.setUnattendedMode(1);
        assertThat(schemaNames()).doesNotContain("AskUserQuestion").contains("SendUserMessage");
    }

    @Test
    void globalKillSwitchHidesSendUserMessageEvenWhenBriefOn() {
        row.setBriefMode(1);
        SessionToolVisibility.setBriefGloballyEnabled(false);
        assertThat(schemaNames()).doesNotContain("SendUserMessage");
    }

    @Test
    void headlessNoSessionExcludesAsk() {
        // 无头定时任务：无会话 + 非交互 ⇒ 没有可应答 UI，Ask 不得进 schema。
        ToolUseContext tuc = ToolUseContext.of(
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(),
            com.nexusai.common.SessionKeys.NO_SESSION,
            PermissionMode.DEFAULT,
            List.of(tool("Bash"), tool("AskUserQuestion"), tool("SendUserMessage")),
            "", AbortController.NOOP, List.of(), null, null, Map.of(), true, "");
        assertThat(LlmAgentLoop.sessionVisibleTools(tuc, QuerySource.USER).stream().map(Tool::name).toList())
            .doesNotContain("AskUserQuestion")
            .contains("Bash");
    }

    @Test
    void unattendedCoordinatorRevealsAllImTools() {
        // [coordinator-unattended] 无人值守 + 协调者 ⇒ IM 8 个默认全部放行
        // （brief=0 也放 SendUserMessage：会话级 opt-in 视为已开）。
        row.setUnattendedMode(1);
        row.setCoordinatorMode(1);
        assertThat(schemaNames())
            .contains("SendUserMessage", "SendImAsUser", "ListImChats", "ReadImMessages",
                "SearchImContacts", "wechat_search_messages", "wechat_list_conversations",
                "wechat_read_conversation")
            .doesNotContain("AskUserQuestion");
    }

    @Test
    void unattendedWithoutCoordinatorStillHidesChannelImTools() {
        row.setUnattendedMode(1);
        assertThat(schemaNames())
            .doesNotContain("SendUserMessage", "SendImAsUser", "ListImChats",
                "ReadImMessages", "SearchImContacts")
            .contains("wechat_search_messages");
    }

    @Test
    void coordinatorWithoutUnattendedStillHidesChannelImTools() {
        row.setCoordinatorMode(1);
        assertThat(schemaNames())
            .doesNotContain("SendUserMessage", "SendImAsUser", "ListImChats",
                "ReadImMessages", "SearchImContacts", "wechat_search_messages");
    }

    @Test
    void explicitImOverrideFalseWinsOverUnattendedCoordinator() {
        // 条件默认只作用于「无覆盖」分支：显式关闭仍然生效。
        row.setUnattendedMode(1);
        row.setCoordinatorMode(1);
        when(mapper.selectImToolOverrides(any())).thenReturn("{\"ListImChats\":false}");
        assertThat(schemaNames()).doesNotContain("ListImChats").contains("SendImAsUser");
    }

    @Test
    void globalKillSwitchStillWinsInUnattendedCoordinator() {
        row.setUnattendedMode(1);
        row.setCoordinatorMode(1);
        SessionToolVisibility.setBriefGloballyEnabled(false);
        assertThat(schemaNames()).doesNotContain("SendUserMessage").contains("SendImAsUser");
    }

    @Test
    void snapshotIsBoundToSessionAndFreezesTheRound() {
        row.setBriefMode(0);
        SessionToolVisibility.Snapshot snap = SessionToolVisibility.capture(SID);
        Map<String, Object> extras = SessionToolVisibility.attach(Map.of(), snap);
        // 本轮切 Brief：快照保持不变（下一轮生效）
        row.setBriefMode(1);
        assertThat(SessionToolVisibility.resolve(SID, extras).blocks("SendUserMessage")).isTrue();
        // 会话不匹配 ⇒ 绝不复用该快照，实时解析
        assertThat(SessionToolVisibility.resolve("sess-other", extras).blocks("SendUserMessage")).isFalse();
        // 快照不能被外部 JSON 伪造（Map 不是 Snapshot 实例）
        assertThat(SessionToolVisibility.resolve(SID, Map.of(SessionToolVisibility.SNAPSHOT_KEY, Map.of()))
            .blocks("SendUserMessage")).isFalse();
    }
}
