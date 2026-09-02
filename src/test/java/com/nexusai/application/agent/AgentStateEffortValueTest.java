package com.nexusai.application.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C-31] AgentState.effortValue 字段测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>@JsonIgnore local-only 红线</b> — effortValue 与 budgetTracker / currentToolUseContext
 *       同属 local-only 状态（CLAUDE.md BudgetTracker 架构红线：绝不序列化到 outbound DTO /
 *       STOMP / WebSocket / EventPublisher payload）。若漏标 @JsonIgnore，经
 *       {@code objectMapper.writeValueAsString(state)} 即泄漏。反射序列化断言防回归。</li>
 *   <li><b>跨压缩结转</b> — effort 值随 AgentState 实例贯穿压缩（replaceMessages 不清除，
 *       对齐 CC appState 会话级语义 + budgetTracker 先例 :354-364），压缩后 skill effort
 *       继续作用于后续 LLM 调用。</li>
 * </ol>
 *
 * @see AgentState#effortValue
 * @see AgentState#setEffortValue
 */
class AgentStateEffortValueTest {

    @Test
    @DisplayName("effortValue set/get 往返（SkillToolImpl contextModifier 写入 / LlmAgentLoop:2762 消费）")
    void setGet_roundTrip() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        assertThat(state.effortValue()).as("初始 effortValue 为 null（= 不注入 effort）").isNull();
        state.setEffortValue("high");
        assertThat(state.effortValue()).as("set 后读取一致").isEqualTo("high");
        state.setEffortValue("max");
        assertThat(state.effortValue()).as("可覆盖为 max（max 降级在 provider 层处理）").isEqualTo("max");
        state.setEffortValue(null);
        assertThat(state.effortValue()).as("可清空为 null").isNull();
    }

    @Test
    @DisplayName("@JsonIgnore：effortValue 字段必须标注 local-only（CLAUDE.md BudgetTracker 红线防泄漏）")
    void jsonIgnore_localOnlyNotSerialized() throws Exception {
        // AgentState 无 public getter（方法式 accessor），Jackson 默认不序列化 → 无法用
        // writeValueAsString 直接断言；改断言 @JsonIgnore 注解本体（红线第一道闸：
        // 漏标则 objectMapper.writeValueAsString(state) 即泄漏 budgetTracker 同款字段）。
        Field field = AgentState.class.getDeclaredField("effortValue");
        assertThat(field.isAnnotationPresent(JsonIgnore.class))
            .as("effortValue 必须 @JsonIgnore（local-only 红线 · 绝不序列化 outbound DTO/STOMP/EventPublisher）")
            .isTrue();
    }

    @Test
    @DisplayName("跨压缩结转：replaceMessages 后 effortValue 字段存活（budgetTracker 先例 :354-364）")
    void replaceMessages_keepsEffortValue() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.setEffortValue("medium");
        // 压缩 = replaceMessages（CompactContext 压缩路径调用），字段必须存活
        state.replaceMessages(List.of(new ChatMessageDto(
            "m2", null, com.nexusai.model.session.dto.Role.user, "user", "summary", null,
            List.of(), null, null, null, null, null, null, null, null, List.of(), List.of())));
        assertThat(state.effortValue())
            .as("压缩（replaceMessages）不清除 effortValue → 跨压缩结转语义")
            .isEqualTo("medium");
    }
}
