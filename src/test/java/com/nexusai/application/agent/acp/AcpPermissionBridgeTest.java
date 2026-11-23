package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限桥 · options → allow/cancelled 往返；危险命令先于策略；审批链异常不得放行。
 */
@DisplayName("[acp] AcpPermissionBridge 权限往返")
class AcpPermissionBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CWD = Path.of(System.getProperty("java.io.tmpdir"));

    private static JsonNode params(String kind, Map<String, Object> rawInput,
                                   List<Map<String, String>> options) {
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("toolCallId", "t-1");
        toolCall.put("title", "Run");
        toolCall.put("kind", kind);
        if (rawInput != null) {
            toolCall.put("rawInput", rawInput);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", "s-1");
        params.put("toolCall", toolCall);
        params.put("options", options);
        return JSON.valueToTree(params);
    }

    private static List<Map<String, String>> allowAndReject() {
        return List.of(
                Map.of("optionId", "opt-allow", "name", "Allow", "kind", "allow_once"),
                Map.of("optionId", "opt-reject", "name", "Reject", "kind", "reject_once"));
    }

    private static String outcome(JsonNode response) {
        return response.path("outcome").path("outcome").asText();
    }

    @Test
    @DisplayName("allow 策略 ⇒ 第一个 allow 选项的 optionId")
    void allowPolicySelectsFirstAllowOption() {
        AcpPermissionBridge bridge = new AcpPermissionBridge(null, null, CWD, AcpPermissionBridge.POLICY_ALLOW);
        JsonNode response = bridge.decide(params("execute", Map.of("command", "mvn test"), allowAndReject()));

        assertThat(outcome(response)).isEqualTo("selected");
        assertThat(response.path("outcome").path("optionId").asText()).isEqualTo("opt-allow");
    }

    @Test
    @DisplayName("allow 策略但没有任何 allow 选项 ⇒ cancelled（不放行）")
    void allowPolicyWithoutAllowOptionCancels() {
        AcpPermissionBridge bridge = new AcpPermissionBridge(null, null, CWD, AcpPermissionBridge.POLICY_ALLOW);
        JsonNode response = bridge.decide(params("execute", Map.of("command", "ls"),
                List.of(Map.of("optionId", "opt-reject", "name", "Reject", "kind", "reject_once"))));

        assertThat(outcome(response)).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("reject 策略 ⇒ 一律 cancelled")
    void rejectPolicyCancels() {
        AcpPermissionBridge bridge = new AcpPermissionBridge(null, null, CWD, AcpPermissionBridge.POLICY_REJECT);
        assertThat(outcome(bridge.decide(params("execute", Map.of("command", "ls"), allowAndReject()))))
                .isEqualTo("cancelled");
    }

    @Test
    @DisplayName("危险命令即使 allow 策略也 cancelled，且不碰审批门")
    void dangerousCommandBypassesAllowPolicy() {
        ToolPermissionGate gate = mock(ToolPermissionGate.class);
        AcpPermissionBridge bridge = new AcpPermissionBridge(gate, tuc(), CWD, AcpPermissionBridge.POLICY_ALLOW);

        JsonNode response = bridge.decide(params("execute", Map.of("command", "rm -rf /"), allowAndReject()));

        assertThat(outcome(response)).isEqualTo("cancelled");
        verify(gate, never()).check(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("越界路径即使 allow 策略也 cancelled")
    void escapingPathBypassesAllowPolicy() {
        AcpPermissionBridge bridge = new AcpPermissionBridge(null, null, CWD, AcpPermissionBridge.POLICY_ALLOW);
        JsonNode response = bridge.decide(params("edit",
                Map.of("path", CWD.resolve("..").resolve("x.txt").toString()), allowAndReject()));

        assertThat(outcome(response)).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("ask 策略：审批门 ALLOW ⇒ 回第一个 allow optionId；DENY ⇒ cancelled")
    void askPolicyUsesPermissionGate() {
        ToolPermissionGate gate = mock(ToolPermissionGate.class);
        when(gate.check(any(), any(), any(), any(), any(), any()))
                .thenReturn(ToolPermissionGate.DecisionResult.allow());
        AcpPermissionBridge allowBridge = new AcpPermissionBridge(gate, tuc(), CWD, AcpPermissionBridge.POLICY_ASK);

        JsonNode allowed = allowBridge.decide(params("execute", Map.of("command", "mvn test"), allowAndReject()));
        assertThat(outcome(allowed)).isEqualTo("selected");
        assertThat(allowed.path("outcome").path("optionId").asText()).isEqualTo("opt-allow");

        ToolPermissionGate denyGate = mock(ToolPermissionGate.class);
        when(denyGate.check(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ToolPermissionGate.DecisionResult(ToolPermissionGate.Decision.DENY, null));
        AcpPermissionBridge denyBridge = new AcpPermissionBridge(denyGate, tuc(), CWD, AcpPermissionBridge.POLICY_ASK);

        assertThat(outcome(denyBridge.decide(params("execute", Map.of("command", "mvn test"), allowAndReject()))))
                .isEqualTo("cancelled");
    }

    @Test
    @DisplayName("ask 策略：审批门抛异常 / 未注入 ⇒ cancelled（绝不放行）")
    void askPolicyFailsClosed() {
        ToolPermissionGate throwing = mock(ToolPermissionGate.class);
        when(throwing.check(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        AcpPermissionBridge bridge = new AcpPermissionBridge(throwing, tuc(), CWD, AcpPermissionBridge.POLICY_ASK);
        assertThat(outcome(bridge.decide(params("execute", Map.of("command", "ls"), allowAndReject()))))
                .isEqualTo("cancelled");

        AcpPermissionBridge noGate = new AcpPermissionBridge(null, null, CWD, AcpPermissionBridge.POLICY_ASK);
        assertThat(outcome(noGate.decide(params("execute", Map.of("command", "ls"), allowAndReject()))))
                .isEqualTo("cancelled");
    }

    private static ToolUseContext tuc() {
        ToolUseContext tuc = mock(ToolUseContext.class);
        when(tuc.permissionContext()).thenReturn(null);
        return tuc;
    }
}
