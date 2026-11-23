package com.nexusai.apis.subagent;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeEnvStore;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeSpec;
import com.nexusai.application.agent.acp.AcpProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「可派发 agent 的 available」判据：ACP 走 AcpRuntimeSpec（配置 bin → dsh），
 * 而不是 ExternalRuntimeSpec 的「找名为 acp 的二进制」。
 */
@DisplayName("[acp] 外部 agent 可用性判据")
class SubagentRuntimeAvailabilityTest {

    @BeforeEach
    void resetEnvStore() {
        ExternalRuntimeEnvStore.replace(java.util.Map.of());
    }

    @Test
    @DisplayName("内置 ACP agent 的可用性 = 本机是否有 dsh（与 AcpRuntimeSpec 同源）")
    void builtinAcpAvailabilityMatchesDsh() {
        AgentDefinition.ExternalAgentDefinition acp =
                (AgentDefinition.ExternalAgentDefinition) BuiltInAgents.get("acp-external");
        boolean hasDsh = ExternalRuntimeSpec.resolveExecutable("dsh") != null;

        assertThat(SubagentRuntimeController.isExternalAgentAvailable(acp)).isEqualTo(hasDsh);
    }

    @Test
    @DisplayName("普通 runtime：bin 存在 ⇒ 可用；bin 不存在 ⇒ 不可用")
    void ordinaryRuntimeUsesBinExistence() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                AcpProcess.isWindows() ? "java.exe" : "java").toString();
        AgentDefinition.ExternalAgentDefinition ok = AgentDefinition.ExternalAgentDefinition
                .builder("probe-agent", "d", "userSettings", "")
                .runtime("claude").bin(javaBin).build();
        assertThat(SubagentRuntimeController.isExternalAgentAvailable(ok)).isTrue();

        // ⚠️ 不能用 runtime("claude")：resolveBin 找不到指定 bin 时会回落「runtime 同名可执行文件」，
        //    本机装了 claude 就会误判为可用。用一个必然不存在的 runtime 名。
        AgentDefinition.ExternalAgentDefinition missing = AgentDefinition.ExternalAgentDefinition
                .builder("probe-agent-2", "d", "userSettings", "")
                .runtime("no-such-runtime-xyz").build();
        assertThat(SubagentRuntimeController.isExternalAgentAvailable(missing)).isFalse();
    }
}
