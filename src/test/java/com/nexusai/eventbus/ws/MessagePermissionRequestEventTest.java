package com.nexusai.eventbus.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * WF-11 G1/G2 · MessagePermissionRequestEvent 对齐 CC serializeDecisionReason + tool_use_id。
 *
 * <p>覆盖（对齐 CC Open-ClaudeCode/src/cli/structuredIO.ts:64-91 serializeDecisionReason）：
 * <ul>
 *   <li>rule/mode/subcommandResults/permissionPromptTool → undefined（DTO null → 事件省略 reason）</li>
 *   <li>hook/asyncAgent/sandboxOverride/workingDir/safetyCheck/other → reason.reason</li>
 *   <li>classifier → 门控开启时 reason.reason；门控关闭 → undefined</li>
 *   <li>tool_use_id 字段（OD-WF1-01 G 族契约，前端关联具体工具调用）</li>
 * </ul>
 */
class MessagePermissionRequestEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── serializeDecisionReason：CC undefined 分支（rule/mode/subcommandResults/permissionPromptTool）──

    @Test
    @DisplayName("null reason → null（CC if (!reason) return undefined）")
    void nullReasonSerializesToNull() {
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(null, false)).isNull();
        assertThat(MessagePermissionRequestEvent.toDto(null, false)).isNull();
    }

    @Test
    @DisplayName("Rule → undefined（CC case 'rule' return undefined；弹窗不显示规则归因文案）")
    void ruleSerializesToNull() {
        PermissionDecisionReason reason = new PermissionDecisionReason.Rule(new PermissionRule(
            PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW,
            PermissionRuleValue.wholeTool("Bash")));
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false)).isNull();
        assertThat(MessagePermissionRequestEvent.toDto(reason, false)).isNull();
    }

    @Test
    @DisplayName("Mode → undefined（CC case 'mode' return undefined）")
    void modeSerializesToNull() {
        PermissionDecisionReason reason = new PermissionDecisionReason.Mode(PermissionMode.DEFAULT);
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false)).isNull();
    }

    @Test
    @DisplayName("SubcommandResults → undefined（CC case 'subcommandResults' return undefined）")
    void subcommandResultsSerializesToNull() {
        PermissionDecisionReason reason = new PermissionDecisionReason.SubcommandResults(Map.of());
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false)).isNull();
    }

    @Test
    @DisplayName("PermissionPromptTool → undefined（CC case 'permissionPromptTool' return undefined）")
    void permissionPromptToolSerializesToNull() {
        PermissionDecisionReason reason =
            new PermissionDecisionReason.PermissionPromptTool("MyExternalTool", null);
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false)).isNull();
    }

    // ── serializeDecisionReason：reason.reason 分支 ──

    @Test
    @DisplayName("Hook → hook.reason（CC case 'hook' return reason.reason）")
    void hookSerializesToReason() {
        PermissionDecisionReason reason =
            new PermissionDecisionReason.Hook("PreToolUse:Read", "user", "blocked by hook");
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false))
            .isEqualTo("blocked by hook");
    }

    @Test
    @DisplayName("AsyncAgent → asyncAgent.reason")
    void asyncAgentSerializesToReason() {
        PermissionDecisionReason reason = new PermissionDecisionReason.AsyncAgent("async denied");
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false))
            .isEqualTo("async denied");
    }

    @Test
    @DisplayName("SandboxOverride → sandboxOverride.reason.ccLiteral（CC reason='excludedCommand' 字面量）")
    void sandboxOverrideSerializesToReason() {
        PermissionDecisionReason reason = new PermissionDecisionReason.SandboxOverride(
            PermissionDecisionReason.SandboxOverride.SandboxOverrideReason.EXCLUDED_COMMAND);
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false))
            .isEqualTo("excludedCommand");
    }

    @Test
    @DisplayName("WorkingDir → workingDir.reason")
    void workingDirSerializesToReason() {
        PermissionDecisionReason reason = new PermissionDecisionReason.WorkingDir("outside working dir");
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false))
            .isEqualTo("outside working dir");
    }

    @Test
    @DisplayName("SafetyCheck → safetyCheck.reason")
    void safetyCheckSerializesToReason() {
        PermissionDecisionReason reason = new PermissionDecisionReason.SafetyCheck(".git/ detected", true);
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false))
            .isEqualTo(".git/ detected");
    }

    @Test
    @DisplayName("Other → other.reason")
    void otherSerializesToReason() {
        PermissionDecisionReason reason = new PermissionDecisionReason.Other("manual reason");
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false))
            .isEqualTo("manual reason");
    }

    // ── serializeDecisionReason：classifier 门控 ──

    @Test
    @DisplayName("Classifier + 门控开启 → classifier.reason（CC feature(BASH_CLASSIFIER||TRANSCRIPT_CLASSIFIER) && type==='classifier'）")
    void classifierWithGateOnSerializesToReason() {
        PermissionDecisionReason reason =
            new PermissionDecisionReason.Classifier("auto-mode", "classified dangerous");
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, true))
            .isEqualTo("classified dangerous");
        assertThat(MessagePermissionRequestEvent.toDto(reason, true).getReason())
            .isEqualTo("classified dangerous");
    }

    @Test
    @DisplayName("Classifier + 门控关闭 → undefined（CC 门控外 switch 无 classifier case → 隐式 undefined）")
    void classifierWithGateOffSerializesToNull() {
        PermissionDecisionReason reason =
            new PermissionDecisionReason.Classifier("auto-mode", "classified dangerous");
        assertThat(MessagePermissionRequestEvent.serializeDecisionReason(reason, false)).isNull();
        assertThat(MessagePermissionRequestEvent.toDto(reason, false)).isNull();
    }

    // ── G2 tool_use_id 契约 ──

    @Test
    @DisplayName("of 完整工厂携带 toolUseId（CC can_use_tool payload tool_use_id）")
    void ofCarriesToolUseId() {
        PermissionDecisionReason reason = new PermissionDecisionReason.Other("ask me");
        MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
            "sess-1", "req-1", "tooluse-42", "Read",
            JSON.createObjectNode().put("path", "/a"), reason, "desc",
            List.of(), null, false);
        assertThat(event.getRequestId()).isEqualTo("req-1");
        assertThat(event.getToolUseId()).as("前端响应据此关联到具体工具调用").isEqualTo("tooluse-42");
        assertThat(event.getReason()).isNotNull();
        assertThat(event.getReason().getReason()).isEqualTo("ask me");
    }

    @Test
    @DisplayName("5 参 of 兜底：toolUseId = requestId，reason 序列化（Other → reason.reason）")
    void fiveArgOfFallsBackToolUseIdToRequestId() {
        MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
            "sess-1", "req-9", "Read",
            JSON.createObjectNode(), new PermissionDecisionReason.Other("why"));
        assertThat(event.getToolUseId()).isEqualTo("req-9");
        assertThat(event.getReason().getReason()).isEqualTo("why");
    }

    @Test
    @DisplayName("serializeDecisionReason 返回 null 时 DTO 为 null → 事件 JSON 省略 reason 字段（对齐 CC decision_reason undefined）")
    void nullDtoOmittedFromJson() throws Exception {
        MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
            "sess-1", "req-1", "tu-1", "Bash",
            JSON.createObjectNode(), new PermissionDecisionReason.Rule(new PermissionRule(
                PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool("Bash"))),
            "desc", List.of(), null, false);
        String json = JSON.writeValueAsString(event);
        assertThat(json).doesNotContain("\"reason\"");
    }

    // ── perm-timeout #132 · workerBadgeColor 契约（对齐 CC WorkerBadgeProps.color）──

    @Test
    @DisplayName("[perm-timeout #132] workerBadgeColor 非 null → JSON 含字段（leader inbox 彩色徽标）")
    void workerBadgeColor_serializesWhenPresent() throws Exception {
        // WHY: CC useInboxPoller.ts:292 entry.workerBadge={name,color} + WorkerBadge.tsx:8 前端
        //   渲染彩色徽标 —— 事件必须携带 color 字段，否则前端拿不到 worker 区分色。
        MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
            "sess-1", "req-1", "tu-1", "Bash",
            JSON.createObjectNode(), new PermissionDecisionReason.Other("leader_inbox"),
            "desc", List.of(), null, null, "cyan", false);
        String json = JSON.writeValueAsString(event);
        assertThat(json).contains("\"workerBadgeColor\":\"cyan\"");
        assertThat(event.getWorkerBadgeColor()).isEqualTo("cyan");
    }

    @Test
    @DisplayName("[perm-timeout #132] workerBadgeColor null → JSON 省略字段（@JsonInclude NON_NULL，主 loop 弹窗无 badge）")
    void workerBadgeColor_nullOmittedFromJson() throws Exception {
        // WHY: 主 loop 权限弹窗无 worker badge —— 缺省 null 时必须省略字段（前端向后兼容，
        //   不破坏既有事件 JSON shape）。
        MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
            "sess-1", "req-1", "tu-1", "Bash",
            JSON.createObjectNode(), new PermissionDecisionReason.Other("ask me"),
            "desc", List.of(), null, false);
        String json = JSON.writeValueAsString(event);
        assertThat(json).doesNotContain("workerBadgeColor");
        assertThat(event.getWorkerBadgeColor()).isNull();
    }
}
