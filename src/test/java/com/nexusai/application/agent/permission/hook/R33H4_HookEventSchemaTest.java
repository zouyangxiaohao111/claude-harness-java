package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H4] Hook 事件 schema 契约补全 · 对齐 CC 真源:
 * <ul>
 *   <li>{@code Open-ClaudeCode/src/entrypoints/sdk/coreSchemas.ts:387-411} BaseHookInputSchema 6 顶层字段</li>
 *   <li>{@code coreSchemas.ts:414-423} PreToolUse tool_use_id 必传</li>
 *   <li>{@code coreSchemas.ts:436-446} PostToolUse tool_use_id 必传</li>
 *   <li>{@code coreSchemas.ts:425-434} PermissionRequest permission_suggestions optional</li>
 *   <li>{@code coreSchemas.ts:627-643} Elicitation requested_schema optional</li>
 *   <li>{@code coreSchemas.ts:747-754} EXIT_REASONS 6 值</li>
 *   <li>{@code types/hooks.ts:243-246} HookBlockingError 结构化 record {blockingError, command}</li>
 *   <li>{@code types/hooks.ts:248-258} PermissionRequestResult sealed union (allow/deny)</li>
 * </ul>
 *
 * <p>WHY (规则九): 本测试验证 H4 契约补全的 6 条核心意图:
 * <ol>
 *   <li>HookEvent 携带 3 个 CC BaseHookInput 顶层字段 (transcriptPath/cwd/permissionMode)</li>
 *   <li>PreToolUse 工厂携带 tool_use_id (CC 必传)</li>
 *   <li>PermissionRequest 工厂携带 permission_suggestions</li>
 *   <li>Elicitation 工厂携带 requested_schema</li>
 *   <li>PermissionRequestResult 是 sealed interface + Allow/Deny 2 record (对齐 CC union)</li>
 *   <li>HookBlockingError 是结构化 record {blockingError, command} 而非 String</li>
 * </ol>
 *
 * @since Session H4
 */
@DisplayName("[H4] Hook 事件 schema 契约补全对齐 CC")
class R33H4_HookEventSchemaTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("HookEvent 携带 CC BaseHookInput 3 顶层字段 transcriptPath/cwd/permissionMode")
    void hookEvent_carriesCwdTranscriptPathPermissionMode() {
        // CC coreSchemas.ts:387-411: session_id, transcript_path, cwd, permission_mode, agent_id, agent_type
        // [H4] 验证 3 顶层字段作为 record component 存在 (反射验证字段定义, 非 null 值验证)
        HookEvent event = HookEvent.sessionStart("sess-1", "agent-1", "startup", null, null);
        java.util.Set<String> fieldNames = java.util.Arrays.stream(HookEvent.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(fieldNames)
            .as("HookEvent record 必须包含 CC BaseHookInputSchema 3 顶层字段 (coreSchemas.ts:389-391)")
            .contains("transcriptPath", "cwd", "permissionMode");
        // 字段 accessor 可调 (即使值为 null, 字段定义存在)
        assertThat(event.transcriptPath()).isNull();  // sessionStart 工厂未提供, 值 null 但字段存在
        assertThat(event.cwd()).isNull();
        assertThat(event.permissionMode()).isNull();
    }

    @Test
    @DisplayName("PreToolUse 工厂携带 tool_use_id (CC coreSchemas.ts:417 必传)")
    void preToolUseFactory_carriesToolUseId() {
        JsonNode input = mapper.createObjectNode().put("k", "v");
        HookEvent event = HookEvent.toolPre("Bash", input, "sess-1", "agent-1", "tool-use-id-123");
        assertThat(event.toolUseId())
            .as("CC PreToolUseHookInputSchema tool_use_id 必传字段 (coreSchemas.ts:417)")
            .isEqualTo("tool-use-id-123");
    }

    @Test
    @DisplayName("PermissionRequest 工厂携带 permission_suggestions (CC coreSchemas.ts:431 optional)")
    void permissionRequestFactory_carriesPermissionSuggestions() {
        JsonNode input = mapper.createObjectNode().put("k", "v");
        List<Map<String, Object>> suggestions = List.of(Map.of("type", "setMode", "mode", "default"));
        HookEvent event = HookEvent.permissionRequest("Bash", input, suggestions, "sess-1", "agent-1");
        assertThat(event.permissionSuggestions())
            .as("CC PermissionRequestHookInputSchema permission_suggestions (coreSchemas.ts:431)")
            .isNotNull()
            .isNotEmpty();
    }

    @Test
    @DisplayName("Elicitation 工厂携带 requested_schema (CC coreSchemas.ts:642 optional)")
    void elicitationFactory_carriesRequestedSchema() {
        Map<String, Object> requestedSchema = Map.of("type", "object", "properties", Map.of());
        HookEvent event = HookEvent.elicitation("mcp-server", "please fill", "sess-1",
            "form", null, null, requestedSchema);
        assertThat(event.requestedSchema())
            .as("CC ElicitationHookInputSchema requested_schema (coreSchemas.ts:642)")
            .isNotNull()
            .isNotEmpty();
    }

    @Test
    @DisplayName("PermissionRequestResult 是 sealed interface + Allow/Deny 2 record (CC types/hooks.ts:248-258)")
    void permissionRequestResult_sealedAllowDenyVariants() {
        PermissionRequestResult allow = new PermissionRequestResult.Allow(
            Map.of("updated", true), List.of());
        PermissionRequestResult deny = new PermissionRequestResult.Deny("not allowed", true);
        assertThat(allow)
            .as("CC PermissionRequestResult allow 变体 (types/hooks.ts:250-253)")
            .isInstanceOf(PermissionRequestResult.Allow.class);
        assertThat(((PermissionRequestResult.Allow) allow).updatedInput())
            .containsEntry("updated", true);
        assertThat(deny)
            .as("CC PermissionRequestResult deny 变体 (types/hooks.ts:254-257)")
            .isInstanceOf(PermissionRequestResult.Deny.class);
        assertThat(((PermissionRequestResult.Deny) deny).message()).isEqualTo("not allowed");
        assertThat(((PermissionRequestResult.Deny) deny).interrupt()).isTrue();
    }

    @Test
    @DisplayName("HookBlockingError 是结构化 record {blockingError, command} 而非 String (CC types/hooks.ts:243-246)")
    void hookBlockingError_isStructuredRecord() {
        HookBlockingError err = new HookBlockingError("exit 2 stderr text", "bash hook.sh");
        assertThat(err.blockingError())
            .as("CC HookBlockingError.blockingError (types/hooks.ts:244)")
            .isEqualTo("exit 2 stderr text");
        assertThat(err.command())
            .as("CC HookBlockingError.command (types/hooks.ts:245)")
            .isEqualTo("bash hook.sh");
        // GenericHook.HookResult.blockingError 必须是 HookBlockingError record, 不是 String
        // CC types/hooks.ts:263: blockingError?: HookBlockingError
        assertThat(GenericHook.HookResult.class.getRecordComponents())
            .as("HookResult.blockingError 字段类型必须是 HookBlockingError record (非 String)")
            .anyMatch(c -> c.getName().equals("blockingError")
                && c.getType().equals(HookBlockingError.class));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // hooks_v3 H-WF4-02 · teammate 收尾段 + permission_mode + Stop 前缀基建
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TeammateIdle 工厂携带 teammate_name/team_name + permissionMode (CC executeTeammateIdleHooks hooks.ts:3709-3729)")
    void teammateIdleFactory_carriesTeammateNameTeamNameAndPermissionMode() {
        // CC executeTeammateIdleHooks: hook_input = createBaseHookInput(permissionMode)
        //   + hook_event_name='TeammateIdle' + teammate_name + team_name (hooks.ts:3716-3721)
        HookEvent basic = HookEvent.teammateIdle("teammate-a", "team-x", "sess-1");
        assertThat(basic.type())
            .as("CC TeammateIdle 事件 (coreTypes.ts:42)")
            .isEqualTo(HookEventType.TEAMMATE_IDLE);
        assertThat(basic.data())
            .as("CC TeammateIdleHookInput teammate_name (hooks.ts:3719)")
            .containsEntry("teammate_name", "teammate-a")
            .containsEntry("team_name", "team-x");

        HookEvent withMode = HookEvent.teammateIdle("teammate-a", "team-x", "default", "sess-1");
        assertThat(withMode.permissionMode())
            .as("CC createBaseHookInput(permissionMode) 恒带 permission_mode (hooks.ts:3717)")
            .isEqualTo("default");
        assertThat(withMode.sessionId()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("Stop 工厂 permissionMode 重载把 permission_mode 落到顶层字段 (CC executeStopHooks hooks.ts:3681)")
    void stopFactory_permissionModeOverload_carriesPermissionMode() {
        // CC executeStopHooks: createBaseHookInput(permissionMode) 恒带 permission_mode
        //   (hooks.ts:3681 Stop 分支; hooks.ts:3672 为 SubagentStop 分支行; appState.toolPermissionContext.mode, stopHooks.ts:177-178)
        // 6 参重载: (sessionId, agentId, stopHookActive, lastAssistantMessage, agentType, permissionMode)
        HookEvent stopEvent = HookEvent.stop("sess-1", null, true, "last msg", null, "default");
        assertThat(stopEvent.permissionMode())
            .as("hooks_v3 决策 5-9/5-W4-2: Stop hook 输入 CC 恒带 permission_mode")
            .isEqualTo("default");
        assertThat(stopEvent.type()).isEqualTo(HookEventType.STOP);
        assertThat(stopEvent.data())
            .as("stop_hook_active 载荷保持")
            .containsEntry("stop_hook_active", true);
    }

    @Test
    @DisplayName("SubagentStop 工厂 permissionMode 重载把 permission_mode 落到顶层字段 (CC executeSubagentStopHooks hooks.ts:3672)")
    void subagentStopFactory_permissionModeOverload_carriesPermissionMode() {
        HookEvent subEvent = HookEvent.subagentStop("agent-1", "sub", "sess-1", true, "/t.jsonl",
            "last msg", "default");
        assertThat(subEvent.permissionMode())
            .as("hooks_v3 决策 5-9/5-W4-2: SubagentStop hook 输入 CC 恒带 permission_mode")
            .isEqualTo("default");
        assertThat(subEvent.type()).isEqualTo(HookEventType.SUBAGENT_STOP);
        assertThat(subEvent.data()).containsEntry("agent_id", "agent-1");
    }

    @Test
    @DisplayName("get*HookMessage 加 CC 前缀 (hooks.ts:1894-1929): Stop/TeammateIdle/TaskCompleted hook feedback")
    void hookMessageFormatters_applyCcFeedbackPrefix() {
        // CC getStopHookMessage (hooks.ts:1894-1896): `Stop hook feedback:\n${blockingError.blockingError}`
        // CC getTeammateIdleHookMessage (hooks.ts:1903-1907): `TeammateIdle hook feedback:\n...`
        // CC getTaskCompletedHookMessage (hooks.ts:1925-1929): `TaskCompleted hook feedback:\n...`
        HookBlockingError err = new HookBlockingError("exit 2 stderr text", "bash hook.sh");
        assertThat(HookEvent.getStopHookMessage(err))
            .as("hooks_v3 决策 5-8/5-W4-1: Stop 阻塞文案前缀")
            .isEqualTo("Stop hook feedback:\nexit 2 stderr text");
        assertThat(HookEvent.getTeammateIdleHookMessage(err))
            .as("hooks_v3 决策 0-1: TeammateIdle 阻塞文案前缀")
            .isEqualTo("TeammateIdle hook feedback:\nexit 2 stderr text");
        assertThat(HookEvent.getTaskCompletedHookMessage(err))
            .as("hooks_v3 决策 0-1: TaskCompleted 阻塞文案前缀")
            .isEqualTo("TaskCompleted hook feedback:\nexit 2 stderr text");
    }
}