package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.AgentHook;
import com.nexusai.application.agent.permission.hook.HookCommand;
import com.nexusai.application.agent.permission.hook.HttpHook;
import com.nexusai.application.agent.permission.hook.McpToolHook;
import com.nexusai.application.agent.permission.hook.PromptHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Wave2-J2] 插件 hooks.json 5 类 discriminated union 解析（command/prompt/agent/http/mcp_tool）。
 *
 * <p><b>WHY</b>: 插件侧只解析 command 时，其它 4 类条目会被静默丢弃（用户配了没反应）。
 * 缺必填字段必须返回 null（调用方跳过），⛔ 不猜默认值。
 */
@DisplayName("[Wave2-J2] 插件 hooks.json 5 类型解析")
class PluginHookTypesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HookCommand parse(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        return PluginLoader.parsePluginHookCommand(node, "p");
    }

    @Test
    @DisplayName("prompt：prompt/if/timeout/model/continue_on_block/status_message/once")
    void promptType() throws Exception {
        HookCommand hook = parse("""
            {"type":"prompt","prompt":"verify","if":"Bash(*)","timeout":9,
             "model":"m1","continue_on_block":true,"status_message":"s","once":true}
            """);
        assertThat(hook).isInstanceOf(PromptHook.class);
        PromptHook p = (PromptHook) hook;
        assertThat(p.prompt()).isEqualTo("verify");
        assertThat(p.ifCondition()).isEqualTo("Bash(*)");
        assertThat(p.timeout()).isEqualTo(9);
        assertThat(p.model()).isEqualTo("m1");
        assertThat(p.continueOnBlock()).isTrue();
        assertThat(p.statusMessage()).isEqualTo("s");
        assertThat(p.once()).isTrue();
    }

    @Test
    @DisplayName("agent：prompt/if/timeout/model/status_message/once")
    void agentType() throws Exception {
        HookCommand hook = parse("""
            {"type":"agent","prompt":"verify tests","timeout":30,"model":"haiku","once":false}
            """);
        assertThat(hook).isInstanceOf(AgentHook.class);
        AgentHook a = (AgentHook) hook;
        assertThat(a.prompt()).isEqualTo("verify tests");
        assertThat(a.timeout()).isEqualTo(30);
        assertThat(a.model()).isEqualTo("haiku");
        assertThat(a.once()).isFalse();
    }

    @Test
    @DisplayName("http：url/headers/allowed_env_vars/on_failure 缺省 block")
    void httpType() throws Exception {
        HookCommand hook = parse("""
            {"type":"http","url":"https://h/x","timeout":4,
             "headers":{"Authorization":"Bearer $TOKEN","X-Num":5},
             "allowed_env_vars":["TOKEN"],"status_message":"posting"}
            """);
        assertThat(hook).isInstanceOf(HttpHook.class);
        HttpHook h = (HttpHook) hook;
        assertThat(h.url()).isEqualTo("https://h/x");
        assertThat(h.timeout()).isEqualTo(4);
        // 非文本 header 值被跳过
        assertThat(h.headers()).containsEntry("Authorization", "Bearer $TOKEN")
            .doesNotContainKey("X-Num");
        assertThat(h.allowedEnvVars()).containsExactly("TOKEN");
        assertThat(h.statusMessage()).isEqualTo("posting");
        assertThat(h.onFailure()).isEqualTo("block");
    }

    @Test
    @DisplayName("mcp_tool：server/tool/input/if/timeout/status_message/once")
    void mcpToolType() throws Exception {
        HookCommand hook = parse("""
            {"type":"mcp_tool","server":"fs","tool":"scan","input":{"path":"${tool_input.file_path}"},
             "if":"Read(*)","timeout":7,"status_message":"scanning","once":true}
            """);
        assertThat(hook).isInstanceOf(McpToolHook.class);
        McpToolHook m = (McpToolHook) hook;
        assertThat(m.server()).isEqualTo("fs");
        assertThat(m.tool()).isEqualTo("scan");
        assertThat(m.input()).containsEntry("path", "${tool_input.file_path}");
        assertThat(m.ifCondition()).isEqualTo("Read(*)");
        assertThat(m.timeout()).isEqualTo(7);
        assertThat(m.once()).isTrue();
    }

    @Test
    @DisplayName("缺必填字段 → null（不猜默认值）")
    void missingRequiredFieldsReturnNull() throws Exception {
        assertThat(parse("{\"type\":\"prompt\"}")).isNull();
        assertThat(parse("{\"type\":\"agent\"}")).isNull();
        assertThat(parse("{\"type\":\"http\"}")).isNull();
        assertThat(parse("{\"type\":\"mcp_tool\",\"server\":\"fs\"}")).isNull();
        assertThat(parse("{\"type\":\"mcp_tool\",\"tool\":\"t\"}")).isNull();
        assertThat(parse("{\"type\":\"command\"}")).isNull();
    }

    @Test
    @DisplayName("未知 type → null")
    void unknownTypeReturnsNull() throws Exception {
        assertThat(parse("{\"type\":\"script\",\"script\":\"x\"}")).isNull();
    }

    @Test
    @DisplayName("无 type 的对象按 command（兼容旧条目）")
    void missingTypeTreatedAsCommand() throws Exception {
        HookCommand hook = parse("{\"command\":\"echo hi\"}");
        assertThat(hook).isInstanceOf(com.nexusai.application.agent.permission.hook.CommandHook.class);
    }
}
