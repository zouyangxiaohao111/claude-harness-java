package com.nexusai.application.agent.acp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stopReason → 本仓 status 的映射 · ⛔ 未知绝不映射成 completed。
 */
@DisplayName("[acp] stopReason 映射")
class AcpStopReasonTest {

    @Test
    @DisplayName("end_turn ⇒ completed；cancelled ⇒ aborted")
    void terminalReasons() {
        assertThat(AcpAgentRunner.mapStopReason("end_turn")).isEqualTo("completed");
        assertThat(AcpAgentRunner.mapStopReason("cancelled")).isEqualTo("aborted");
    }

    @Test
    @DisplayName("max_tokens / max_turn_requests / refusal / 未知 / 空 ⇒ failed")
    void uncleanReasonsAreFailures() {
        assertThat(AcpAgentRunner.mapStopReason("max_tokens")).isEqualTo("failed");
        assertThat(AcpAgentRunner.mapStopReason("max_turn_requests")).isEqualTo("failed");
        assertThat(AcpAgentRunner.mapStopReason("refusal")).isEqualTo("failed");
        assertThat(AcpAgentRunner.mapStopReason("future_thing")).isEqualTo("failed");
        assertThat(AcpAgentRunner.mapStopReason("")).isEqualTo("failed");
        assertThat(AcpAgentRunner.mapStopReason(null)).isEqualTo("failed");
    }
}
