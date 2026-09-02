package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-I-9 Q-33] /schedule 维持 disabled · 对齐 CC scheduleRemoteAgents.ts:332-333
 * {@code isEnabled: () => feature('tengu_surreal_dali', false) && isPolicyAllowed('allow_remote_sessions')}.
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: CC 默认 feature 关（tengu_surreal_dali=false）
 * → /schedule 命令 disabled。Java 生产注入 {@code () -> false} 桩（BundledSkillsBootstrapper
 * registerScheduleSkill）→ Command.enabled=false。若 isEnabled 误为 true，LLM 会暴露一个
 * 无法工作的远程调度命令（Java 无 claude.ai OAuth / 远程环境基建）。本测试锁：
 * <ol>
 *   <li>feature=false 路（生产桩）→ register() 产出 isEnabled()=false</li>
 *   <li>feature=true 但 policy=false → isEnabled()=false（双开关 AND 语义）</li>
 * </ol>
 */
@DisplayName("[MCP-I-9 Q-33] /schedule isEnabled 维持 disabled（对齐 CC feature 默认关）")
class ScheduleSkillDisabledTest {

    @Test
    @DisplayName("feature=false（生产桩）→ isEnabled 恒 false")
    void featureDisabled_productionStub_isEnabledFalse() {
        // WHY: CC tengu_surreal_dali 默认 false → /schedule disabled（scheduleRemoteAgents.ts:333）
        ScheduleRemoteAgentsSkillRegistrar reg = registrar(() -> false, () -> true);

        assertThat(reg.register().isEnabled().getAsBoolean())
            .as("feature=false → /schedule disabled（对齐 CC 默认门控）")
            .isFalse();
    }

    @Test
    @DisplayName("feature=true 但 policy=false → isEnabled false（双开关 AND）")
    void featureOn_policyOff_isEnabledFalse() {
        // CC :332-333 isEnabled = feature && policy — policy 关则命令仍 disabled
        ScheduleRemoteAgentsSkillRegistrar reg = registrar(() -> true, () -> false);

        assertThat(reg.register().isEnabled().getAsBoolean())
            .as("feature && policy 双开关 → policy=false 仍 disabled")
            .isFalse();
    }

    private static ScheduleRemoteAgentsSkillRegistrar registrar(
            ScheduleRemoteAgentsSkillRegistrar.BooleanSupplier feature,
            ScheduleRemoteAgentsSkillRegistrar.BooleanSupplier policy) {
        return new ScheduleRemoteAgentsSkillRegistrar(
            feature, policy, () -> null, List::of,
            name -> new ScheduleRemoteAgentsSkillRegistrar.EnvironmentResource(name, "default", "cloud"),
            () -> "UTC", List::of, () -> null);
    }
}
