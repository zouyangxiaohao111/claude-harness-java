package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.util.AbortControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [W8-GAP-01] SubagentTool teammate spawn 生产分支测试 · 对齐 CC AgentTool.tsx:263-320。
 *
 * <p>验证：
 * <ul>
 *   <li>name + team_name + swarms 启用 → 走 {@link SpawnInProcess#spawnInProcessTeammate}，
 *       返回 {@code status:'teammate_spawned'} + teammate_id/name/team_name（CC :287-308）</li>
 *   <li>mode='plan' → {@code plan_mode_required=true}（CC :296）</li>
 *   <li>swarms 未启用 + team_name → fail loud error "not yet available"（CC :263-264），不触发 spawn</li>
 *   <li>spawn 失败 → 透传 error，不静默返回成功（CC handleSpawnInProcess :909）</li>
 * </ul>
 */
@DisplayName("W8-GAP-01 · SubagentTool teammate spawn 生产分支（CC AgentTool.tsx:287-320）")
class SubagentToolTeammateSpawnBranchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SpawnInProcess spawner;
    private SubagentTool tool;

    @BeforeEach
    void setUp() throws Exception {
        spawner = mock(SpawnInProcess.class);
        tool = new SubagentTool(
            List.of(), null, null, null, "gpt-4", "", null,
            Files.createTempDirectory("wf8-gap01"), List.of());
        tool.setSpawnInProcess(spawner);
        // swarms 启用（对齐 TaskUpdateToolMailboxNotifyTest.java:60 模式）
        System.setProperty("nexusai.experimental.agent-teams", "true");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.experimental.agent-teams");
    }

    /** teammate tool_use 块：description+prompt+name+team_name（+可选 mode）。 */
    private static ToolUseBlock teammateCall(String mode) {
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Do research");
        input.put("prompt", "Research X");
        input.put("name", "researcher");
        input.put("team_name", "research-team");
        if (mode != null) {
            input.put("mode", mode);
        }
        return new ToolUseBlock("tool-teammate-test", "Agent", input);
    }

    private static SpawnInProcess.InProcessSpawnOutput successOut() {
        return new SpawnInProcess.InProcessSpawnOutput(
            true, "researcher@research-team", "t1a2b3c4d", null, null, null);
    }

    @Test
    @DisplayName("name+team_name → spawnInProcessTeammate + teammate_spawned（CC AgentTool.tsx:287-308）")
    void teammateSpawn_withNameAndTeam_spawnsInProcess() throws Exception {
        // WHY: GAP-01 阻断缺口 = SubagentTool 只记录 name/team_name 不 spawn。
        //   对齐 CC :287 `if (teamName && name)` → spawnTeammate → spawnMultiAgent.ts:899
        //   spawnInProcessTeammate；返回 :307 status:'teammate_spawned'。
        when(spawner.spawnInProcessTeammate(any(), any())).thenReturn(successOut());

        ToolResult<?> result = tool.execute(teammateCall(null), null, null);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode data = (JsonNode) result.data();
        assertThat(data.get("status").asText())
            .as("CC AgentTool.tsx:307 status='teammate_spawned'")
            .isEqualTo("teammate_spawned");
        assertThat(data.get("teammate_id").asText()).isEqualTo("researcher@research-team");
        assertThat(data.get("agent_id").asText()).isEqualTo("researcher@research-team");
        assertThat(data.get("name").asText()).isEqualTo("researcher");
        assertThat(data.get("team_name").asText()).isEqualTo("research-team");
        assertThat(data.get("is_splitpane").asBoolean())
            .as("in-process spawn is_splitpane=false（spawnMultiAgent.ts:1028）")
            .isFalse();

        ArgumentCaptor<SpawnInProcess.InProcessSpawnConfig> configCaptor =
            ArgumentCaptor.forClass(SpawnInProcess.InProcessSpawnConfig.class);
        verify(spawner).spawnInProcessTeammate(configCaptor.capture(), any());
        SpawnInProcess.InProcessSpawnConfig config = configCaptor.getValue();
        assertThat(config.name()).isEqualTo("researcher");
        assertThat(config.teamName()).isEqualTo("research-team");
        assertThat(config.prompt()).isEqualTo("Research X");
        assertThat(config.planModeRequired()).isFalse();
    }

    @Test
    @DisplayName("mode='plan' → plan_mode_required=true（CC AgentTool.tsx:296）")
    void teammateSpawn_planMode_setsPlanModeRequired() throws Exception {
        // WHY: CC :296 `plan_mode_required: spawnMode === 'plan'`（spawnMode = input.mode :247）。
        //   plan 权限模式 teammate 必须进 plan gate 待批准（spawnInProcess.ts:173）。
        when(spawner.spawnInProcessTeammate(any(), any())).thenReturn(successOut());

        ToolResult<?> result = tool.execute(teammateCall("plan"), null, null);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        ArgumentCaptor<SpawnInProcess.InProcessSpawnConfig> captor =
            ArgumentCaptor.forClass(SpawnInProcess.InProcessSpawnConfig.class);
        verify(spawner).spawnInProcessTeammate(captor.capture(), any());
        assertThat(captor.getValue().planModeRequired())
            .as("mode='plan' → plan_mode_required=true（CC AgentTool.tsx:296）")
            .isTrue();
    }

    @Test
    @DisplayName("swarms 未启用 + team_name → 不再 fail（[去套餐] CC 套餐门控已注释，用户拍板暂时不过滤）")
    void teammateSpawn_swarmsDisabled_errorsNotAvailable() throws Exception {
        // WHY: [2026-08-24 用户拍板] 当前无套餐概念，CC "not yet available on your plan" 套餐/门控
        //   过滤已注释（SubagentTool spawn 分支）。swarms 开关不再拦截 team spawn——即使
        //   isAgentSwarmsEnabled()=false，team_name 也直接透传走 team 分支（resolvedTeamName=teamName）。
        //   变异点：若误恢复套餐过滤 → 本测试 fail（spawner 未被调用 + error 消息）。
        System.clearProperty("nexusai.experimental.agent-teams");
        when(spawner.spawnInProcessTeammate(any(), any())).thenReturn(successOut());

        ToolResult<?> result = tool.execute(teammateCall(null), null, null);

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("swarms 关不再报 'not yet available'（套餐过滤已注释）")
            .isFalse();
        verify(spawner).spawnInProcessTeammate(any(), any());
    }

    @Test
    @DisplayName("spawn 失败 → 透传 error，不静默返回成功")
    void teammateSpawn_failedSpawn_returnsError() throws Exception {
        // WHY: spawnInProcessTeammate 失败（InProcessSpawnOutput.success=false）必须透传 error，
        //   否则父 agent 以为 teammate 已 spawn 而去 SendMessage → 幽灵对端。
        when(spawner.spawnInProcessTeammate(any(), any())).thenReturn(
            new SpawnInProcess.InProcessSpawnOutput(
                false, "researcher@research-team", null, null, null, "boom"));

        ToolResult<?> result = tool.execute(teammateCall(null), null, null);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(String.valueOf(result.data())).contains("Failed to spawn in-process teammate");
    }

    // ═══════════════════════════════════════════════════════════════════
    // [W8-GAP-01 · 守卫分支] :273 / :279 守卫（teammate 不能嵌套 spawn）
    //   参考 CronCreateToolCcContractTest.java:132-138 runWithTeammateContext 注入模式
    // ═══════════════════════════════════════════════════════════════════

    /** 构造 in-process teammate 上下文（对齐 CronCreateToolCcContractTest:132-134）。 */
    private static TeammateContext inProcessTeammate() {
        return new TeammateContext(
            "researcher", "peer", "research-team", null, false, null,
            AbortControllerFactory.create());
    }

    @Test
    @DisplayName("teammate context + name+team_name → CC:273 守卫拒绝嵌套 spawn（teammate 不能 spawn teammate）")
    void teammateGuard_cannotSpawnTeammate() throws Exception {
        // WHY: CC AgentTool.tsx:272-273 `isTeammate() && teamName && name` → throw。
        //   teammate 已位于扁平 roster，再传 name 会触发 spawnTeammate 制造无来源嵌套队友，
        //   干扰 lead 的归属判定（CC 注释 :267-271）。守卫必须先于 spawn 生效，绝不能静默放行。
        when(spawner.spawnInProcessTeammate(any(), any())).thenReturn(successOut());

        ToolResult<?> result = TeammateContext.runWithTeammateContext(
            inProcessTeammate(), () -> tool.execute(teammateCall(null), null, null));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("teammate 不能 spawn teammate → 守卫返回 error")
            .isTrue();
        assertThat(String.valueOf(result.data()))
            .as("CC AgentTool.tsx:273 精确消息")
            .contains("Teammates cannot spawn other teammates");
        verifyNoInteractions(spawner);
    }

    @Test
    @DisplayName("teammate context + name+team_name+run_in_background=true → 守卫拒绝后台 spawn（CC:273 先于 :279，CC 同序）")
    void teammateGuard_backgroundSpawnRejected() throws Exception {
        // WHY: in-process teammate 生命周期绑定 lead 进程（CC AgentTool.tsx:275-277），
        //   不得后台 spawn。CC 顺序 :272 isTeammate+name 先于 :278 in-process+run_in_background，
        //   故带 name 时 CC:273 消息先抛（与 CC 行为逐字一致）；断言 error + spawner 零交互
        //   证明守卫层拒绝，后台 spawn 绝不落入 spawnTeammate。
        when(spawner.spawnInProcessTeammate(any(), any())).thenReturn(successOut());
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Do research");
        input.put("prompt", "Research X");
        input.put("name", "researcher");
        input.put("team_name", "research-team");
        input.put("run_in_background", true);
        ToolUseBlock call = new ToolUseBlock("tool-teammate-bg", "Agent", input);

        ToolResult<?> result = TeammateContext.runWithTeammateContext(
            inProcessTeammate(), () -> tool.execute(call, null, null));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("teammate 后台 spawn → 守卫返回 error（拒绝）")
            .isTrue();
        assertThat(String.valueOf(result.data()))
            .as("CC 同序：:272 isTeammate+name 先于 :278 → CC:273 消息")
            .contains("Teammates cannot spawn other teammates");
        verifyNoInteractions(spawner);
    }

    @Test
    @DisplayName("teammate context + 无 name + team_name + run_in_background=true → CC:279 守卫拒绝（GAP-R3 可达性修复）")
    void teammateGuard_noNameBackgroundSpawnRejected() throws Exception {
        // WHY: CC AgentTool.tsx:278-280 `isInProcessTeammate() && teamName && run_in_background === true`
        //   → throw，是独立顶层守卫，不依赖 name。GAP-R3 修复前 Java 把该守卫嵌套于 name 分支
        //   （旧 :1181）且被 isTeammate ⊇ isInProcessTeammate 遮蔽 → 结构不可达，无 name 后台
        //   spawn 落入 fork 路径被 shouldRunAsync 尾段 `&& !inProcessTeammate` 静默降级同步
        //   （GAP-R3 缺陷后果；Re-think REWORK-1 已把该尾段从 shouldRunAsync 删除 —— CC:567
        //   公式无 inProcessTeammate 项, R1 后 tool 线程 context 可见若保留会误伤 teammate
        //   的 fork 异步 spawn —— 故本守卫现为 in-process teammate 后台拦截的唯一层）。
        //   本测试锁定 CC:279 真触发场景：无 name + run_in_background=true + in-process teammate
        //   + team_name → 守卫返回 CC:279 精确消息，绝不落入 fork/子 agent spawn 路径。
        when(spawner.spawnInProcessTeammate(any(), any())).thenReturn(successOut());
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Do research");
        input.put("prompt", "Research X");
        // 无 name —— CC:279 触发场景（:272 需 name 不触发）
        input.put("team_name", "research-team");
        input.put("run_in_background", true);
        ToolUseBlock call = new ToolUseBlock("tool-teammate-bg-noname", "Agent", input);

        ToolResult<?> result = TeammateContext.runWithTeammateContext(
            inProcessTeammate(), () -> tool.execute(call, null, null));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("无 name 后台 spawn from in-process teammate → CC:279 守卫返回 error")
            .isTrue();
        assertThat(String.valueOf(result.data()))
            .as("CC AgentTool.tsx:279 精确消息")
            .contains("In-process teammates cannot spawn background agents");
        verifyNoInteractions(spawner);
    }

    @Test
    @DisplayName("teammate context + 无 name 无 team_name + run_in_background=true → CC:279 守卫拒绝（resolveTeamName appState.teamContext 等价）")
    void teammateGuard_noNameNoTeamNameBackgroundSpawnRejected() throws Exception {
        // WHY: CC resolveTeamName :1396 = `input.team_name || appState.teamContext?.teamName` —
        //   teammate 上下文自带 teamName（SpawnInProcess.java:211 config.teamName() 构造），
        //   即使调用未传 team_name，CC :279 仍命中（appState.teamContext?.teamName 兜底）。
        //   Java 等价 = guardTeammateTeamName 从 TeammateContext 取 teamName（GAP-R3 新增兜底）。
        when(spawner.spawnInProcessTeammate(any(), any())).thenReturn(successOut());
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Do research");
        input.put("prompt", "Research X");
        // 无 name + 无 team_name —— 仅凭 teammate 上下文 teamName 触发 :279
        input.put("run_in_background", true);
        ToolUseBlock call = new ToolUseBlock("tool-teammate-bg-noctxteam", "Agent", input);

        ToolResult<?> result = TeammateContext.runWithTeammateContext(
            inProcessTeammate(), () -> tool.execute(call, null, null));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("teammate 上下文 teamName 兜底 → CC:279 守卫返回 error")
            .isTrue();
        assertThat(String.valueOf(result.data()))
            .as("CC AgentTool.tsx:279 精确消息")
            .contains("In-process teammates cannot spawn background agents");
        verifyNoInteractions(spawner);
    }
}
