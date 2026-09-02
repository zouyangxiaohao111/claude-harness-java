package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-CF-01 TY-02] HookEvent.data 补类型化输入 record（27 事件）· 对齐 CC
 * {@code coreSchemas.ts:414-765} 各 HookInputSchema。
 *
 * <p>WHY (规则九): OPD-WF1-TY-02 拍板补类型化——旧 {@code data: Map<String,Object>} 弱类型，
 * 事件特定字段无编译期约束（拼写错误静默丢失）。本测试验证三条核心意图:
 * <ol>
 *   <li>27 种事件各有一个类型化 {@link HookEventData} record（sealed interface 恰 27 变体，
 *       dataRecord() 返回对应类型，非裸 Map）</li>
 *   <li>兼容的 {@code data()} KV Map 视图序列化结果与旧 Map 载荷完全一致（JSON 输出不变，
 *       buildJsonInput 透传语义不回归）</li>
 *   <li>{@link HookEventData#fromMap} Map→record 往返可重建类型化数据（enrichBaseFields
 *       agent_type 注入边界）</li>
 * </ol>
 *
 * @since IMP-CF-01 (TY-02)
 */
@DisplayName("[IMP-CF-01] HookEvent.data 27 事件类型化输入 record")
class HookEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode input() {
        return mapper.createObjectNode().put("k", "v");
    }

    @Test
    @DisplayName("sealed HookEventData 恰含 27 个 record 变体（对齐 CC HookInputSchema 27 事件）")
    void hookEventData_hasExactly27PermittedSubtypes() {
        // CC coreSchemas.ts:767-797 HookInputSchema union 恰 27 种事件输入
        assertThat(HookEventData.class.getPermittedSubclasses())
            .as("HookEventData sealed interface 必须恰有 27 个 record 变体（OPD-WF1-TY-02）")
            .hasSize(27);
    }

    @Test
    @DisplayName("27 事件工厂均产出类型化 dataRecord + 兼容 data() KV 视图（序列化载荷不变）")
    void allEventFactories_produceTypedRecord_andDataView() {
        // 每个事件: dataRecord() 是类型化 record（非 Map）；data() 是 snake_case KV 视图
        // 与旧工厂载荷逐键一致（JSON 输出零回归）。
        assertEvent(HookEvent.toolPre("Bash", input(), "s1", "a1", "tid-1"),
            HookEventData.PreToolUse.class, Map.of());
        assertEvent(HookEvent.toolPost("Bash", input(), input(), "s1", "a1", "tid-1"),
            HookEventData.PostToolUse.class, Map.of());
        assertEvent(HookEvent.toolPostFailure("Bash", input(), input(), "boom", true, "tid-1", "s1", "a1"),
            HookEventData.PostToolUseFailure.class,
            Map.of("error", "boom", "is_interrupt", true, "tool_use_id", "tid-1"));
        assertEvent(HookEvent.permissionRequest("Bash", input(), List.of(), "s1", "a1"),
            HookEventData.PermissionRequest.class, Map.of());
        assertEvent(HookEvent.permissionDenied("Bash", input(), "no", "tid-1", "s1", "a1"),
            HookEventData.PermissionDenied.class,
            Map.of("reason", "no", "tool_use_id", "tid-1"));
        assertEvent(HookEvent.notification("s1", "a1", "msg", "t", "info"),
            HookEventData.Notification.class,
            Map.of("message", "msg", "title", "t", "notification_type", "info"));
        assertEvent(HookEvent.userPromptSubmit("s1", "a1", "hello"),
            HookEventData.UserPromptSubmit.class, Map.of("prompt", "hello"));
        assertEvent(HookEvent.sessionStart("s1", "a1", "startup", "sub", "claude-sonnet-4-5"),
            HookEventData.SessionStart.class,
            Map.of("source", "startup", "agent_type", "sub", "model", "claude-sonnet-4-5"));
        assertEvent(HookEvent.sessionEnd("s1", "a1", ExitReasons.CLEAR, "sub"),
            HookEventData.SessionEnd.class,
            Map.of("reason", "clear", "agent_type", "sub"));
        assertEvent(HookEvent.stop("s1", "a1", true, "last", "sub"),
            HookEventData.Stop.class,
            Map.of("stop_hook_active", true, "last_assistant_message", "last", "agent_type", "sub"));
        assertEvent(HookEvent.stopFailure("s1", "a1", "unknown", "details", "last"),
            HookEventData.StopFailure.class,
            Map.of("error", "unknown", "error_details", "details", "last_assistant_message", "last"));
        assertEvent(HookEvent.subagentStart("a1", "sub", "s1"),
            HookEventData.SubagentStart.class,
            Map.of("agent_id", "a1", "agent_type", "sub", "session_id", "s1"));
        assertEvent(HookEvent.subagentStop("a1", "sub", "s1"),
            HookEventData.SubagentStop.class,
            Map.of("agent_id", "a1", "agent_type", "sub", "session_id", "s1"));
        assertEvent(HookEvent.preCompact("s1", "auto"),
            HookEventData.PreCompact.class, Map.of("trigger", "auto"));
        assertEvent(HookEvent.postCompact("s1", "auto", "summary"),
            HookEventData.PostCompact.class,
            Map.of("trigger", "auto", "compact_summary", "summary"));
        assertEvent(HookEvent.setup("s1", "a1", "init"),
            HookEventData.Setup.class, Map.of("trigger", "init"));
        assertEvent(HookEvent.teammateIdle("ta", "team", "s1"),
            HookEventData.TeammateIdle.class,
            Map.of("teammate_name", "ta", "team_name", "team"));
        assertEvent(HookEvent.taskCreated("t1", "subj", "s1", "a1"),
            HookEventData.TaskCreated.class, Map.of("task_id", "t1", "task_subject", "subj"));
        assertEvent(HookEvent.taskCompleted("t1", "subj", "s1", "a1"),
            HookEventData.TaskCompleted.class, Map.of("task_id", "t1", "task_subject", "subj"));
        assertEvent(HookEvent.elicitation("mcp", "msg", "s1"),
            HookEventData.Elicitation.class,
            Map.of("mcp_server_name", "mcp", "message", "msg"));
        assertEvent(HookEvent.elicitationResult("mcp", "accept", "s1"),
            HookEventData.ElicitationResult.class,
            Map.of("mcp_server_name", "mcp", "action", "accept"));
        assertEvent(HookEvent.configChange("user_settings", "/path", "s1"),
            HookEventData.ConfigChange.class,
            Map.of("source", "user_settings", "file_path", "/path"));
        assertEvent(HookEvent.instructionsLoaded("/f", "Project", "session_start", "s1", null, null, null),
            HookEventData.InstructionsLoaded.class,
            Map.of("file_path", "/f", "memory_type", "Project", "load_reason", "session_start"));
        assertEvent(new HookEvent(HookEventType.WORKTREE_CREATE, null, null, null, null, null,
                null, null, null, null, null, null, new HookEventData.WorktreeCreate("wt"), 0),
            HookEventData.WorktreeCreate.class, Map.of("name", "wt"));
        assertEvent(new HookEvent(HookEventType.WORKTREE_REMOVE, null, null, null, null, null,
                null, null, null, null, null, null, new HookEventData.WorktreeRemove("/wt"), 0),
            HookEventData.WorktreeRemove.class, Map.of("worktree_path", "/wt"));
        assertEvent(HookEvent.cwdChanged("/old", "/new", "s1"),
            HookEventData.CwdChanged.class, Map.of("old_cwd", "/old", "new_cwd", "/new"));
        assertEvent(HookEvent.fileChanged("/f", "change", "s1"),
            HookEventData.FileChanged.class, Map.of("file_path", "/f", "event", "change"));
    }

    @Test
    @DisplayName("fromMap 往返重建类型化 record（enrichBaseFields agent_type 注入边界）")
    void fromMap_roundTripsTypedRecord() {
        // CC createBaseHookInput (hooks.ts:309-327)：工具事件序列化前注入 agent_type；
        // fromMap 是 enrichBaseFields 合并后重建类型化数据的咽喉。
        HookEventData pre = HookEventData.fromMap(HookEventType.PRE_TOOL_USE,
            Map.of("agent_type", "explore"));
        assertThat(pre).isInstanceOf(HookEventData.PreToolUse.class);
        assertThat(pre.toMap()).isEqualTo(Map.of("agent_type", "explore"));

        HookEventData failure = HookEventData.fromMap(HookEventType.POST_TOOL_USE_FAILURE,
            Map.of("error", "boom", "is_interrupt", true, "tool_use_id", "tid", "agent_type", "explore"));
        assertThat(failure).isInstanceOf(HookEventData.PostToolUseFailure.class);
        assertThat(failure.toMap()).isEqualTo(
            Map.of("error", "boom", "is_interrupt", true, "tool_use_id", "tid", "agent_type", "explore"));

        // null map → null（无事件特定数据）
        assertThat(HookEventData.fromMap(HookEventType.STOP, null)).isNull();
    }

    @Test
    @DisplayName("data() 兼容 Map 视图 + dataRecord() 类型化 record 双通道一致")
    void dataViewAndRecord_areConsistent() {
        HookEvent stop = HookEvent.stop("s1", "a1", false, "last msg", "explore");
        assertThat(stop.data()).containsEntry("agent_type", "explore").containsEntry("stop_hook_active", false);
        assertThat(stop.dataRecord()).isInstanceOf(HookEventData.Stop.class);
        HookEventData.Stop s = (HookEventData.Stop) stop.dataRecord();
        assertThat(s.stopHookActive()).isFalse();
        assertThat(s.lastAssistantMessage()).isEqualTo("last msg");
        assertThat(s.agentType()).isEqualTo("explore");
        // data() 是 dataRecord().toMap() 的投影，二者等价
        assertThat(stop.data()).isEqualTo(stop.dataRecord().toMap());
    }

    private void assertEvent(HookEvent event, Class<? extends HookEventData> recordType,
                             Map<String, Object> expectedData) {
        assertThat(event.dataRecord())
            .as("事件 %s 必须产出类型化 record %s（非裸 Map）", event.type(), recordType.getSimpleName())
            .isInstanceOf(recordType);
        assertThat(event.data())
            .as("事件 %s 的兼容 data() KV 视图必须与旧 Map 载荷一致（JSON 输出零回归）", event.type())
            .isEqualTo(expectedData);
    }
}
