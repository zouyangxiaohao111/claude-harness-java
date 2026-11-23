package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.app.AppEnablement;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.config.SessionEnabledAppsConfig;
import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [app-selection] 本轮 MCP allowlist · schema 侧与执行侧同一判据。
 *
 * <p>P0 风险：只做 disabled 黑名单会对未来新增 server 天然泄漏；这里验证 allowlist
 * （只保留选中 server 的 {@code mcp__<server>__*}）覆盖 schema（{@code sessionVisibleTools}）
 * 与执行侧池裁剪（{@code filterByMcpAllowlist}）且结果一致。
 *
 * <p>变异自检：把 {@link SessionToolVisibility#isMcpServerAllowed} 改成恒 true，
 * 本类 {@code singleSelection_*} / {@code emptySelection_*} / {@code futureServer_*} 必须变红。
 */
@DisplayName("app-selection · MCP allowlist（schema 与执行侧同判据）")
class SessionToolVisibilityAllowlistTest {

    private static final String SID = "sess-allow";
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
        SessionEnabledAppsConfig.setSessionMapper(mapper);
        AppEnablement.reset();
    }

    @AfterEach
    void tearDown() {
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        SessionEnabledAppsConfig.reset();
        AppEnablement.reset();
        SessionToolVisibility.resetGlobalFlags();
        SessionToolCategory.reset();
        LlmAgentLoop.setCoordinatorMode(null);
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

    /** A 两个工具 + B 一个 + 未来新增 server（never-selected）一个 + 非 MCP 工具一个。 */
    private static List<Tool> pool() {
        return List.of(
            tool("Bash"),
            tool("mcp__A__t1"),
            tool("mcp__A__t2"),
            tool("mcp__B__t1"),
            tool("mcp__future-server__t1"));
    }

    /** [V337] 启动集 = 会话列 enabled_apps（null 列 = 未设置 → defaultOpen 种子 / 无种子则不裁剪）。 */
    private void applyEnabled(List<String> enabledApps) {
        row.setEnabledApps(enabledApps == null
            ? null : com.alibaba.fastjson2.JSON.toJSONString(enabledApps));
    }

    private List<String> visibleNames(List<String> enabledApps, List<Tool> tools) {
        applyEnabled(enabledApps);
        SessionToolVisibility.Snapshot snap = SessionToolVisibility.capture(SID, false);
        Map<String, Object> extras = SessionToolVisibility.attach(Map.of(), snap);
        ToolUseContext tuc = ToolUseContext.of(
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SID,
            PermissionMode.DEFAULT, tools, "", AbortController.NOOP, List.of(), null, null,
            Map.of(), false, "");
        ToolUseContext withExtras = tuc.withRequestExtras(extras);
        return LlmAgentLoop.sessionVisibleTools(withExtras, QuerySource.USER)
            .stream().map(Tool::name).toList();
    }

    @Test
    @DisplayName("未指定（null）：不裁剪，全部 MCP 工具保留（与历史行为一致）")
    void noSelection_keepsAllMcpTools() {
        assertThat(visibleNames(null, pool()))
            .containsExactlyInAnyOrder("Bash", "mcp__A__t1", "mcp__A__t2",
                "mcp__B__t1", "mcp__future-server__t1");
    }

    @Test
    @DisplayName("选中 A：只暴露 A 的工具（B 被裁掉）")
    void singleSelection_onlyThatServer() {
        assertThat(visibleNames(List.of("A"), pool()))
            .containsExactlyInAnyOrder("Bash", "mcp__A__t1", "mcp__A__t2");
    }

    @Test
    @DisplayName("未来新增 server（未选中）：天然不泄漏（allowlist 而非黑名单）")
    void futureServer_neverLeaks() {
        assertThat(visibleNames(List.of("A", "B"), pool()))
            .doesNotContain("mcp__future-server__t1")
            .contains("mcp__A__t1", "mcp__B__t1");
    }

    @Test
    @DisplayName("显式空数组：所有 MCP 工具不可见，非 MCP 工具不受影响")
    void emptySelection_removesAllMcpTools() {
        assertThat(visibleNames(List.of(), pool())).containsExactly("Bash");
    }

    @Test
    @DisplayName("server 名按 MCP 口径规范化后匹配（空格 → 下划线）")
    void selectionMatchesNormalizedServerName() {
        assertThat(visibleNames(List.of("My App"),
            List.of(tool("Bash"), tool("mcp__My_App__t1"), tool("mcp__Other__t1"))))
            .containsExactlyInAnyOrder("Bash", "mcp__My_App__t1");
    }

    @Test
    @DisplayName("执行侧池裁剪与 schema 侧结果一致（同一纯函数）")
    void executionSideMatchesSchema() {
        applyEnabled(List.of("A"));
        SessionToolVisibility.Snapshot snap = SessionToolVisibility.capture(SID, false);
        List<String> executionNames = SessionToolVisibility
            .filterByMcpAllowlist(pool(), snap.allowedMcpServers())
            .stream().map(Tool::name).toList();

        assertThat(executionNames)
            .containsExactlyInAnyOrderElementsOf(visibleNames(List.of("A"), pool()));
        // blocks 与 isMcpServerAllowed 必须互为反命题（同一判据的两面）
        for (Tool t : pool()) {
            assertThat(snap.blocks(t.name()))
                .isEqualTo(!SessionToolVisibility.isMcpServerAllowed(
                    snap.allowedMcpServers(), t.name()));
        }
    }

    @Test
    @DisplayName("enabled_apps 未设置 + 已安装 defaultOpen 种子：按种子裁剪")
    void enabledNull_usesDefaultOpenSeed() {
        AppEnablement.installDefaultOpenKeysSupplier(() -> java.util.Set.of("A"));

        assertThat(visibleNames(null, pool()))
            .containsExactlyInAnyOrder("Bash", "mcp__A__t1", "mcp__A__t2");
    }

    @Test
    @DisplayName("种子 fail-soft：供应器返回 null ⇒ 不裁剪")
    void seedUnavailable_noClip() {
        AppEnablement.installDefaultOpenKeysSupplier(() -> null);

        assertThat(visibleNames(null, pool()))
            .containsExactlyInAnyOrder("Bash", "mcp__A__t1", "mcp__A__t2",
                "mcp__B__t1", "mcp__future-server__t1");
    }

    @Test
    @DisplayName("BR/CU 是内置工具不是应用：allowlist 不裁（与执行池同结果）")
    void browserAndComputerUse_notGatedByAppAllowlist() {
        List<Tool> pool = List.of(tool("Bash"),
            tool("mcp__nexusai-in-chrome__read_page"),
            tool("mcp__computer-use__screenshot"),
            tool("mcp__A__t1"),
            tool("mcp__B__t1"));

        // 只启动 MCP 应用 A：BR/CU 仍保留（它们走工具面板，不走应用层门）
        assertThat(visibleNames(List.of("A"), pool))
            .containsExactlyInAnyOrder("Bash",
                "mcp__nexusai-in-chrome__read_page",
                "mcp__computer-use__screenshot",
                "mcp__A__t1");

        // 显式全不启动：BR/CU 仍保留，其它 MCP 被裁
        applyEnabled(List.of());
        SessionToolVisibility.Snapshot snap = SessionToolVisibility.capture(SID, false);
        List<String> execution = SessionToolVisibility
            .filterByMcpAllowlist(pool, snap.allowedMcpServers())
            .stream().map(Tool::name).toList();
        assertThat(execution).containsExactlyInAnyOrder("Bash",
            "mcp__nexusai-in-chrome__read_page",
            "mcp__computer-use__screenshot");
    }

    @Test
    @DisplayName("per-turn selectedApps 不进 extras 门：只做强调，不影响 schema")
    void emphasisDoesNotGateSchema() {
        applyEnabled(List.of("A"));
        SessionToolVisibility.Snapshot snap = SessionToolVisibility.capture(SID, false);
        Map<String, Object> extras = SessionToolVisibility.attach(
            Map.of(SessionToolVisibility.SELECTED_APPS_KEY, List.of("B")), snap);
        ToolUseContext tuc = ToolUseContext.of(
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SID,
            PermissionMode.DEFAULT, pool(), "", AbortController.NOOP, List.of(), null, null,
            Map.of(), false, "")
            .withRequestExtras(extras);

        assertThat(LlmAgentLoop.sessionVisibleTools(tuc, QuerySource.USER)
            .stream().map(Tool::name).toList())
            .contains("mcp__A__t1", "mcp__A__t2")
            .doesNotContain("mcp__B__t1");
    }
}
