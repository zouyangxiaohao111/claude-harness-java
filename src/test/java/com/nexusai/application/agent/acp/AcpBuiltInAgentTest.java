package com.nexusai.application.agent.acp;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentDefinitionRegistry;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.subagent.loadAgentsDir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACP agent 是<b>内置定义</b>（不写用户家目录 md）· registry 可见、6 组折叠不丢弃、规格能解析。
 *
 * <p>锁的是一个真实陷阱：{@code loadAgentsDir.getActiveAgentsFromList} 只按
 * {@code isBuiltIn} / source 字面量归组，未识别的 source 会被<b>静默丢弃</b> ——
 * 那样 {@code BuiltInAgents.get()} 能取到、registry 却查不到，派发时 AgentNotFound。
 */
@DisplayName("[acp] ACP 内置 agent")
class AcpBuiltInAgentTest {

    private static AgentDefinition.ExternalAgentDefinition acpDef() {
        AgentDefinition def = BuiltInAgents.get("acp-external");
        assertThat(def).isInstanceOf(AgentDefinition.ExternalAgentDefinition.class);
        return (AgentDefinition.ExternalAgentDefinition) def;
    }

    @Test
    @DisplayName("BuiltInAgents.get('acp-external') 是 external 定义，runtime=acp，source=built-in")
    void definitionShape() {
        AgentDefinition.ExternalAgentDefinition def = acpDef();
        assertThat(def.runtime()).isEqualTo("acp");
        assertThat(def.source()).isEqualTo("built-in");
        assertThat(BuiltInAgents.isBuiltIn(def)).isTrue();
    }

    @Test
    @DisplayName("registry 能看到它（findAgent 命中）")
    void registrySeesIt() {
        Map<String, AgentDefinition> builtIn = new LinkedHashMap<>();
        for (AgentDefinition def : BuiltInAgents.getBuiltInAgents()) {
            builtIn.put(def.agentType(), def);
        }
        AgentDefinitionRegistry registry = new AgentDefinitionRegistry(builtIn, List.of());

        assertThat(registry.findAgent("acp-external")).isNotNull();
        assertThat(registry.findAgent("acp-external").agentType()).isEqualTo("acp-external");
    }

    @Test
    @DisplayName("6 组折叠不丢弃（built-in 组判据含 source=built-in）")
    void survivesSourceFold() {
        List<AgentDefinition> folded =
                loadAgentsDir.getActiveAgentsFromList(BuiltInAgents.getBuiltInAgents());

        assertThat(folded.stream().map(AgentDefinition::agentType))
                .contains("acp-external");
    }

    @Test
    @DisplayName("Agent 工具描述里能看到 acp-external（模型可点名派发）")
    void visibleInAgentToolPrompt() {
        String prompt = com.nexusai.application.agent.tool.impl.SubagentToolPrompt.getPrompt(
                BuiltInAgents.getBuiltInAgents(), false, null,
                com.nexusai.application.agent.tool.impl.SubagentToolPrompt.PromptOptions
                        .of(false, false, false, false));

        assertThat(prompt).contains("acp-external");
    }

    @Test
    @DisplayName("ACP 规格解析走 AcpRuntimeSpec（不是 ExternalRuntimeCatalog）：有 dsh ⇒ --profile acp")
    void resolvesThroughAcpSpec() {
        AcpRuntimeSpec.Resolution resolution =
                AcpRuntimeSpec.tryResolve(acpDef(), null, Path.of(System.getProperty("java.io.tmpdir")));

        if (resolution.isAvailable()) {
            assertThat(resolution.spec().argv()).contains("--profile", "acp");
        } else {
            // 本机没装 ACP 适配器：必须是「ACP 可执行文件未找到」这句人话，而不是 catalog 的未知 runtime
            assertThat(resolution.unavailableReason()).contains("ACP");
        }
    }
}
