package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部运行时型 AgentDefinition 的契约回归。
 *
 * <p>WHY（这条最容易踩的坑）：{@code getActiveAgentsFromList} 用**六组 source 白名单**折叠
 * （见 {@link loadAgentsDir#getActiveAgentsFromList}）。若外部 agent 的 {@code source()}
 * 不是 userSettings/projectSettings/flagSettings/policySettings 之一，它会被<b>静默丢弃</b>
 * —— 于是「配置对了、编译过了、日志也没报错」，但模型在 Agent 工具里永远选不到。
 * 本测试把这条钉死。
 */
@DisplayName("[external-agent] ExternalAgentDefinition 契约")
class ExternalAgentDefinitionTest {

    private static AgentDefinition.ExternalAgentDefinition.Builder base() {
        return AgentDefinition.ExternalAgentDefinition.builder(
                        "claude-external", "用本机 Claude Code 执行任务", "userSettings", "你是外部执行者")
                .runtime("claude")
                .args(List.of("-p", "{prompt}", "--output-format", "stream-json"));
    }

    @Test
    @DisplayName("缺 runtime ⇒ 构造失败（不能产出一个跑不起来却不报错的 agent）")
    void missingRuntime_throws() {
        var builder = AgentDefinition.ExternalAgentDefinition.builder(
                "x", "y", "userSettings", "z").args(List.of("-p", "{prompt}"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime");
    }

    @Test
    @DisplayName("promptVia=argv 但 args 无 {prompt} ⇒ 构造失败（任务传不进去是静默缺陷）")
    void argvWithoutPromptPlaceholder_throws() {
        var builder = AgentDefinition.ExternalAgentDefinition.builder(
                        "x", "y", "userSettings", "z")
                .runtime("claude")
                .args(List.of("-p", "--output-format", "text"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{prompt}");
    }

    @Test
    @DisplayName("promptVia=stdin 时无需 {prompt} 占位")
    void stdinMode_doesNotRequirePlaceholder() {
        var def = AgentDefinition.ExternalAgentDefinition.builder(
                        "x", "y", "userSettings", "z")
                .runtime("claude")
                .promptVia("stdin")
                .args(List.of("--output-format", "json"))
                .build();

        assertThat(def.promptVia()).isEqualTo("stdin");
    }

    @Test
    @DisplayName("不支持的 CC 字段恒 empty（= 不支持，不是「空值」）")
    void unsupportedCcFields_areEmpty() {
        var def = base().build();

        assertThat(def.tools()).isEmpty();
        assertThat(def.disallowedTools()).isEmpty();
        assertThat(def.hooks()).isEmpty();
        assertThat(def.mcpServers()).isEmpty();
        assertThat(def.skills()).isEmpty();
        assertThat(def.isolation()).isEmpty();
        assertThat(def.memory()).isEmpty();
        assertThat(def.requiredMcpServers()).isEmpty();
    }

    @Test
    @DisplayName("type guard: isExternal 命中，其余三个不命中")
    void typeGuards() {
        var def = base().build();

        assertThat(AgentDefinition.isExternal(def)).isTrue();
        assertThat(AgentDefinition.isBuiltIn(def)).isFalse();
        assertThat(AgentDefinition.isCustom(def)).isFalse();
        assertThat(AgentDefinition.isPlugin(def)).isFalse();
    }

    @Test
    @DisplayName("source 落在六组白名单内 ⇒ 不会被 getActiveAgentsFromList 丢弃")
    void sourceMustSurviveActiveAgentsFold() {
        var external = base().build();
        var custom = AgentDefinition.CustomAgentDefinition.builder(
                "some-custom", "custom", "userSettings", "body").build();

        List<AgentDefinition> active =
                loadAgentsDir.getActiveAgentsFromList(List.of(external, custom));

        assertThat(active).extracting(AgentDefinition::agentType)
                .contains("claude-external", "some-custom");
    }

    @Test
    @DisplayName("回归：source 若不在六组内 ⇒ 会被折叠丢弃（这就是必须复用 source 的原因）")
    void unknownSource_isSilentlyDropped() {
        var external = AgentDefinition.ExternalAgentDefinition.builder(
                        "ext", "y", "externalRuntime", "z")
                .runtime("claude")
                .args(List.of("-p", "{prompt}"))
                .build();

        List<AgentDefinition> active = loadAgentsDir.getActiveAgentsFromList(List.of(external));

        assertThat(active).isEmpty();
    }

    @Test
    @DisplayName("默认能力位保守：不流式 / 不续跑 / 不校验 / 禁递归")
    void defaultCapabilitiesAreConservative() {
        var def = base().build();

        assertThat(def.capabilities().streaming()).isFalse();
        assertThat(def.capabilities().resume()).isFalse();
        assertThat(def.capabilities().outputSchema()).isFalse();
        assertThat(def.capabilities().depthLimit()).isZero();
    }
}
