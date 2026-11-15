package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 定义与 CC v2.1.325 的字段对齐测试。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>CC 的 agent frontmatter 里有三个容易被“解析了但没人用”的字段：
 * {@code autoCompactWindow}（只收窄子代理窗口）、{@code observer} / {@code observerMessage}
 * （观察者机制）。本用例锁定解析层，防止字段名写错/类型丢失导致静默失效。
 */
@DisplayName("[agent-align] Agent 定义字段解析")
class AgentDefinitionAlignmentTest {

    @TempDir
    Path root;

    private Map<String, AgentDefinition> load(String frontmatter) throws Exception {
        Path agents = root.resolve("agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("obs-agent.md"),
            "---\n" + frontmatter + "\n---\n\nYou are the observed agent.\n",
            StandardCharsets.UTF_8);
        return loadAgentsDir.load(root, "userSettings", null);
    }

    @Test
    @DisplayName("autoCompactWindow / observer / observerMessage 解析进定义")
    void parsesCcAgentFields() throws Exception {
        Map<String, AgentDefinition> agents = load(
            "name: obs-agent\n"
                + "description: observe me\n"
                + "autoCompactWindow: 120000\n"
                + "observer: reviewer-a, reviewer-b\n"
                + "observerMessage: focus on regressions\n");

        AgentDefinition def = agents.get("obs-agent");
        assertThat(def).isNotNull();
        assertThat(def.autoCompactWindow()).contains(120000);
        assertThat(def.observer()).contains("reviewer-a, reviewer-b");
        assertThat(def.observerMessage()).contains("focus on regressions");
    }

    @Test
    @DisplayName("observeSubagents=false 解析进定义（禁止 fan-out）")
    void parsesObserveSubagents() throws Exception {
        Map<String, AgentDefinition> agents = load(
            "name: obs-agent\ndescription: d\nobserveSubagents: false\n");

        assertThat(agents.get("obs-agent").observeSubagents()).contains(false);
    }

    @Test
    @DisplayName("未声明时三个字段为 empty（不产生默认值）")
    void absentFields_areEmpty() throws Exception {
        Map<String, AgentDefinition> agents = load("name: obs-agent\ndescription: plain\n");

        AgentDefinition def = agents.get("obs-agent");
        assertThat(def).isNotNull();
        assertThat(def.autoCompactWindow()).isEmpty();
        assertThat(def.observer()).isEmpty();
        assertThat(def.observerMessage()).isEmpty();
    }
}
