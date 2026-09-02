package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S1 P1-6] BuiltInAgentDefinition.create 工厂扩展测试.
 *
 * <p>旧 create 仅 4 参 (agentType/whenToUse/tools/systemPromptFn), 其余 17 字段全
 * Optional.empty(), 无法设置 disallowedTools/model/color/omitClaudeMd/background/
 * criticalSystemReminder/permissionMode — 修复 P0-2 (Explore/Plan/verification
 * disallowedTools) 的前置阻塞. 本期 Builder 补齐 (CC 用对象字面量无构造器, Java 习语).
 */
class BuiltInAgentDefinitionCreateFactoryTest {

    @Test
    @DisplayName("Builder 可设置 disallowedTools/model/color/omitClaudeMd/background/criticalSystemReminder/permissionMode")
    void create_factory_accepts_disallowedTools_model_color_omitClaudeMd() {
        // WHY: P0-2 前置 — 若 create 无法设置这些字段, Explore/Plan/verification 无法设
        // disallowedTools 黑名单, 工具语义反转无从落地.
        AgentDefinition def = AgentDefinition.BuiltInAgentDefinition.builder(
                "test-agent", "when to use", (ctx, dirs) -> "prompt")
            .disallowedTools(List.of("Edit", "Write"))
            .model("haiku")
            .color("red")
            .omitClaudeMd(true)
            .background(true)
            .criticalSystemReminder_EXPERIMENTAL("CRITICAL: VERIFICATION-ONLY task")
            .permissionMode("dontAsk")
            .build();

        assertThat(def.disallowedTools()).hasValue(List.of("Edit", "Write"));
        assertThat(def.model()).hasValue("haiku");
        assertThat(def.color()).hasValue("red");
        assertThat(def.omitClaudeMd()).hasValue(true);
        assertThat(def.background()).hasValue(true);
        assertThat(def.criticalSystemReminder_EXPERIMENTAL()).hasValue("CRITICAL: VERIFICATION-ONLY task");
        assertThat(def.permissionMode()).hasValue("dontAsk");
        assertThat(def.source()).isEqualTo("built-in");
        assertThat(def.getSystemPrompt(null, List.of())).isEqualTo("prompt");
    }

    @Test
    @DisplayName("旧 4 参 create 保留委托 builder.build() (5 个 caller 零破坏)")
    void legacy_create_4arg_delegates_to_builder() {
        // WHY: 旧 4 参 create 由 BuiltInAgents 5 处 + SubagentExecutorForkModeTest 调用,
        // 保留委托避免无谓 churn (用户授权可破约但本期保留).
        AgentDefinition def = AgentDefinition.BuiltInAgentDefinition.create(
                "legacy", "when", List.of("Read"), (ctx, dirs) -> "prompt");
        assertThat(def.agentType()).isEqualTo("legacy");
        assertThat(def.tools()).hasValue(List.of("Read"));
        assertThat(def.getSystemPrompt(null, List.of())).isEqualTo("prompt");
        assertThat(def.source()).isEqualTo("built-in");
    }

    @Test
    @DisplayName("Builder 未设字段缺省 Optional.empty (对齐 CC undefined)")
    void builder_unset_fields_default_empty() {
        // WHY: CC 对象字面量缺省字段 = undefined; Java Builder 未设字段应为 Optional.empty,
        // 使 resolveAgentTools 走 '全部工具' 兜底.
        AgentDefinition def = AgentDefinition.BuiltInAgentDefinition.builder("m", "w", (ctx, dirs) -> "").build();
        assertThat(def.tools()).isEmpty();
        assertThat(def.disallowedTools()).isEmpty();
        assertThat(def.model()).isEmpty();
        assertThat(def.omitClaudeMd()).isEmpty();
    }

    @Test
    @DisplayName("callback 是函数类型 (Runnable) 而非 String · 对齐 CC callback?: () => void")
    void callback_is_runnable_function_type_not_string() {
        // WHY (R2-CALLBACK): CC BuiltInAgentDefinition.callback 是 () => void 函数类型
        // (loadAgentsDir.ts:139), 在 query 循环结束后被调用 (runAgent.ts:812-814)。
        // 旧实现误建模为 Optional<String>, 无法承载"agent 完成后回调"语义。
        // 对齐后 builder.callback 接受 Runnable 且可被调用。
        // RED: 变更前 callback(String) 不接受 lambda → 本测试编译失败。
        boolean[] invoked = {false};
        AgentDefinition.BuiltInAgentDefinition def = AgentDefinition.BuiltInAgentDefinition.builder(
                "cb-agent", "when", (ctx, dirs) -> "prompt")
            .callback(() -> invoked[0] = true)
            .build();

        assertThat(def.callback()).isPresent();
        def.callback().get().run();
        assertThat(invoked[0]).isTrue();
    }
}
