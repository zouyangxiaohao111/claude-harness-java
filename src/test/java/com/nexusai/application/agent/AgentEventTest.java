package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentEvent sealed 契约测试 · 覆盖 decisions-log §32「前端联动 · token_warning 事件契约」
 * （IMP-BACK-1 TokenWarning record）。
 *
 * <p><b>WHY（契约意图）</b>：TokenWarning 是 STOMP 推送通道的载荷 record，前端按
 * {@code { eventType, sessionId, suppressed, tokenUsage, contextWindow, percentLeft? }}
 * 解析。测试固话：
 * <ol>
 *   <li>record 必须被 sealed interface 允许（channel 契约：AgentEvent 新增 TokenWarning record）;</li>
 *   <li>事件类型字面量后端定 {@code "token_warning"}（前端占位，后端定，decisions-log §32）;</li>
 *   <li>字段语义对齐 CC（suppressed→compactWarningStore, tokenUsage→TokenWarning.tsx props,
 *       contextWindow→getEffectiveContextWindowSize, percentLeft→displayPercentLeft）;</li>
 *   <li>percentLeft 可选（null 时前端自行计算 displayPercentLeft，对齐 TokenWarning.tsx:127/:154）;</li>
 *   <li>序列化 JSON 字段名与契约一致（eventType/sessionId/suppressed/tokenUsage/contextWindow/percentLeft）。</li>
 * </ol>
 */
class AgentEventTest {

    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("契约: TokenWarning 是 AgentEvent sealed interface 的 permit 成员（STOMP 通道载荷）")
    void tokenWarningIsPermittedAgentEvent() {
        AgentEvent e = AgentEvent.TokenWarning.of(SESSION, false, 45_000L, 200_000L, 78);
        assertThat(e).isInstanceOf(AgentEvent.class);
        assertThat(e).isInstanceOf(AgentEvent.TokenWarning.class);
        assertThat(e.sessionId()).isEqualTo(SESSION);
    }

    @Test
    @DisplayName("契约: 事件类型字面量由后端定为 token_warning（前端占位，decisions-log §32）")
    void eventTypeIsBackendDecidedLiteral() {
        AgentEvent.TokenWarning w =
            AgentEvent.TokenWarning.of(SESSION, false, 45_000L, 200_000L, 78);
        assertThat(w.eventType()).isEqualTo(AgentEvent.TokenWarning.EVENT_TYPE);
        assertThat(AgentEvent.TokenWarning.EVENT_TYPE).isEqualTo("token_warning");
    }

    @Test
    @DisplayName("契约: 字段语义对齐 CC（suppressed/tokenUsage/contextWindow/percentLeft）")
    void fieldsAlignToCc() {
        // suppressed=true 对齐 CC suppressCompactWarning()（压缩成功 → compactWarningStore=true）
        AgentEvent.TokenWarning suppressed =
            AgentEvent.TokenWarning.of(SESSION, true, 0L, 200_000L, null);
        assertThat(suppressed.suppressed()).isTrue();
        assertThat(suppressed.percentLeft()).isNull();
        assertThat(suppressed.tokenUsage()).isZero();

        // suppressed=false 对齐 CC clearCompactWarningSuppression()（新压缩开始 → false），
        // tokenUsage/contextWindow/percentLeft 对齐 CC tokenUsage / getEffectiveContextWindowSize / displayPercentLeft
        AgentEvent.TokenWarning warning =
            AgentEvent.TokenWarning.of(SESSION, false, 190_000L, 200_000L, 5);
        assertThat(warning.suppressed()).isFalse();
        assertThat(warning.tokenUsage()).isEqualTo(190_000L);
        assertThat(warning.contextWindow()).isEqualTo(200_000L);
        assertThat(warning.percentLeft()).isEqualTo(5);
    }

    @Test
    @DisplayName("契约: 事件类型为后端唯一事实来源，不允许外部传空/空白 eventType")
    void eventTypeRequiredNonNull() {
        assertThatThrownBy(() -> new AgentEvent.TokenWarning(SESSION, null, false, 0L, 200_000L, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventType");
        assertThatThrownBy(() -> new AgentEvent.TokenWarning(SESSION, "  ", false, 0L, 200_000L, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventType");
    }

    @Test
    @DisplayName("契约: STOMP 序列化 JSON 字段名 = {eventType, sessionId, suppressed, tokenUsage, contextWindow, percentLeft}")
    void serializesToContractJson() throws Exception {
        AgentEvent.TokenWarning w =
            AgentEvent.TokenWarning.of(SESSION, true, 12_345L, 200_000L, 94);
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(w));

        assertThat(json.get("eventType").asText()).isEqualTo("token_warning");
        assertThat(json.get("sessionId").asText()).isEqualTo(SESSION.toString());
        assertThat(json.get("suppressed").asBoolean()).isTrue();
        assertThat(json.get("tokenUsage").asLong()).isEqualTo(12_345L);
        assertThat(json.get("contextWindow").asLong()).isEqualTo(200_000L);
        assertThat(json.get("percentLeft").asInt()).isEqualTo(94);
    }
}
