package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部运行时 agent 的 **markdown frontmatter 解析**回归。
 *
 * <p>WHY：{@code AgentDefinition} 的构造正确性已由 {@code ExternalAgentDefinitionTest} 覆盖，
 * 但用户实际写的是 {@code agents/*.md}。这条链路（frontmatter Map → Builder）若接错，
 * 表现是「文件放对了、日志说解析成功、但 runtime/args 全是默认值」—— 典型的静默缺陷。
 */
@DisplayName("[external-agent] agents/*.md frontmatter 解析")
class ExternalAgentFrontmatterTest {

    private static void writeAgent(Path baseDir, String fileName, String content) throws Exception {
        Path agents = baseDir.resolve("agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve(fileName), content);
    }

    @Test
    @DisplayName("runtime/bin/args/promptVia/streamFormat/capabilities 全部落位")
    void parsesAllExternalFields(@TempDir Path baseDir) throws Exception {
        writeAgent(baseDir, "claude-external.md", """
                ---
                name: claude-external
                description: 用本机 Claude Code CLI 执行任务
                sub-runtime: claude
                bin: claude
                args:
                  - "-p"
                  - "{prompt}"
                  - "--output-format"
                  - "stream-json"
                  - "--verbose"
                promptVia: argv
                streamFormat: claude-stream-json
                timeoutMs: 120000
                capabilities:
                  streaming: true
                  resume: true
                  outputSchema: false
                  depthLimit: 0
                ---
                你是一个外部执行者。
                """);

        Map<String, AgentDefinition> loaded = loadAgentsDir.load(baseDir, "projectSettings");

        assertThat(loaded).containsKey("claude-external");
        AgentDefinition def = loaded.get("claude-external");
        assertThat(def).isInstanceOf(AgentDefinition.ExternalAgentDefinition.class);

        var ext = (AgentDefinition.ExternalAgentDefinition) def;
        assertThat(ext.runtime()).isEqualTo("claude");
        assertThat(ext.bin()).contains("claude");
        assertThat(ext.args()).containsExactly(
                "-p", "{prompt}", "--output-format", "stream-json", "--verbose");
        assertThat(ext.promptVia()).isEqualTo("argv");
        assertThat(ext.streamFormat()).isEqualTo("claude-stream-json");
        assertThat(ext.timeoutMs()).isEqualTo(120_000L);
        assertThat(ext.capabilities().streaming()).isTrue();
        assertThat(ext.capabilities().resume()).isTrue();
        assertThat(ext.capabilities().outputSchema()).isFalse();
        assertThat(ext.capabilities().depthLimit()).isZero();
        assertThat(ext.getSystemPrompt(null, null)).contains("外部执行者");
        // source 必须落在六组白名单（否则 registry 折叠会静默丢弃）
        assertThat(ext.source()).isEqualTo("projectSettings");
    }

    @Test
    @DisplayName("缺 capabilities ⇒ 保守默认（不流式/不续跑/禁递归）")
    void missingCapabilities_usesConservativeDefaults(@TempDir Path baseDir) throws Exception {
        writeAgent(baseDir, "minimal.md", """
                ---
                name: minimal
                description: 最小外部 agent
                sub-runtime: codex
                args: ["exec", "{prompt}"]
                ---
                """);

        var ext = (AgentDefinition.ExternalAgentDefinition)
                loadAgentsDir.load(baseDir, "projectSettings").get("minimal");

        assertThat(ext.capabilities().streaming()).isFalse();
        assertThat(ext.capabilities().resume()).isFalse();
        assertThat(ext.capabilities().outputSchema()).isFalse();
        assertThat(ext.capabilities().depthLimit()).isZero();
        assertThat(ext.timeoutMs()).isEqualTo(600_000L);   // 默认 10 分钟
    }

    @Test
    @DisplayName("写了外部字段但缺 sub-runtime ⇒ 不丢弃，退化为普通自定义 agent（并告警）")
    void missingRuntime_fallsBackToCustomAgent(@TempDir Path baseDir) throws Exception {
        writeAgent(baseDir, "forgot-runtime.md", """
                ---
                name: forgot-runtime
                description: 忘了写 sub-runtime
                bin: claude
                args: ["-p", "{prompt}"]
                ---
                """);

        Map<String, AgentDefinition> loaded = loadAgentsDir.load(baseDir, "projectSettings");

        // 关键：不能因为漏写 runtime 就静默丢掉这个 agent（用户会以为配置生效了）
        assertThat(loaded).containsKey("forgot-runtime");
        assertThat(loaded.get("forgot-runtime"))
                .isInstanceOf(AgentDefinition.CustomAgentDefinition.class);
    }

    @Test
    @DisplayName("runtime 非法但 args 缺 {prompt} ⇒ 跳过并留 ERROR（不产出传不到任务的 agent）")
    void invalidExternalConfig_isSkipped(@TempDir Path baseDir) throws Exception {
        writeAgent(baseDir, "broken-argv.md", """
                ---
                name: broken-argv
                description: 有 runtime 但 argv 没有占位
                sub-runtime: claude
                args: ["-p", "--output-format", "text"]
                ---
                """);

        Map<String, AgentDefinition> loaded = loadAgentsDir.load(baseDir, "projectSettings");

        assertThat(loaded).doesNotContainKey("broken-argv");
    }

    @Test
    @DisplayName("普通自定义 agent（无 runtime）不受影响，仍是 CustomAgentDefinition")
    void normalCustomAgent_stillCustom(@TempDir Path baseDir) throws Exception {
        writeAgent(baseDir, "reviewer.md", """
                ---
                name: reviewer
                description: 代码审查
                tools: Read, Grep
                ---
                你是审查员。
                """);

        Map<String, AgentDefinition> loaded = loadAgentsDir.load(baseDir, "projectSettings");

        assertThat(loaded.get("reviewer")).isInstanceOf(AgentDefinition.CustomAgentDefinition.class);
    }
}
