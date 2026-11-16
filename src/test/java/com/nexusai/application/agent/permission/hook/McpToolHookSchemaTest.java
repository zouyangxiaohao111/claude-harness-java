package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Wave1-C 配置 schema 测试 · 验证 claude.exe v2.1.325 的 5 类 hook 判别联合
 * (command/prompt/agent/http/mcp_tool) 能被真实反序列化，且新增字段不丢。
 *
 * <p><b>WHY（这是本仓唯一的 mcp_tool 防线）</b>: 生产加载链
 * {@code MultiSourceHooksConfigLoader.parseHooksConfig} 用
 * {@code convertValue(hooksNode, Map<String, List<HookMatcher>>)} 一次性解析整份
 * hooks 配置。若 Jackson 不认识 {@code "type":"mcp_tool"}，整条 matcher 反序列化失败
 * ⇒ 该 settings 源的<b>全部</b> hook 静默丢弃（不是只跳过这一条）。故断言必须落在
 * 「真实 settings 形状」上，而不是单独 new 一个 record。
 *
 * <p>mapper 配置镜像 Spring Boot（{@code ParameterNamesModule} + 未知字段不炸），
 * 因为生产用的是容器注入的 ObjectMapper。
 */
class McpToolHookSchemaTest {

    /** 生产等价 mapper：ParameterNamesModule + FAIL_ON_UNKNOWN_PROPERTIES=false。 */
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new ParameterNamesModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void mcpToolHook_throughSettingsShape_keepsAllFields() throws Exception {
        String hooks = """
            {"PreToolUse":[{"matcher":"Read","hooks":[
              {"type":"mcp_tool","server":"filesystem","tool":"scan_secrets",
               "input":{"path":"${tool_input.file_path}"},"if":"Read(*)","timeout":7,
               "statusMessage":"扫描密钥","once":true}
            ]}]}
            """;

        Map<String, List<HookMatcher>> parsed =
            mapper.readValue(hooks, new TypeReference<Map<String, List<HookMatcher>>>() { });

        List<HookCommand> cmds = parsed.get("PreToolUse").get(0).hooks();
        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0)).isInstanceOf(McpToolHook.class);

        McpToolHook hook = (McpToolHook) cmds.get(0);
        assertThat(hook.server()).isEqualTo("filesystem");
        assertThat(hook.tool()).isEqualTo("scan_secrets");
        assertThat(hook.input()).containsEntry("path", "${tool_input.file_path}");
        assertThat(hook.ifCondition()).isEqualTo("Read(*)");
        assertThat(hook.timeout()).isEqualTo(7);
        assertThat(hook.statusMessage()).isEqualTo("扫描密钥");
        assertThat(hook.once()).isTrue();
        assertThat(hook.hookType()).isEqualTo(HookCommand.HookType.MCP_TOOL);
        assertThat(hook.type()).isEqualTo("mcp_tool");
    }

    @Test
    void commandHook_newFields_areDeserialized() throws Exception {
        String json = """
            {"type":"command","command":"/bin/scan","args":["--path","C:/a b/c"],
             "onFailure":"block","asyncRewake":true,
             "rewakeMessage":"[[scan]]","rewakeSummary":"扫描失败"}
            """;

        CommandHook hook = (CommandHook) mapper.readValue(json, HookCommand.class);

        assertThat(hook.args()).containsExactly("--path", "C:/a b/c");
        assertThat(hook.onFailure()).isEqualTo("block");
        assertThat(hook.rewakeMessage()).isEqualTo("[[scan]]");
        assertThat(hook.rewakeSummary()).isEqualTo("扫描失败");
        assertThat(hook.asyncRewake()).isTrue();
    }

    @Test
    void commandHook_legacyEightFields_stillDeserialize() throws Exception {
        String json = "{\"type\":\"command\",\"command\":\"echo hi\",\"shell\":\"bash\"}";

        CommandHook hook = (CommandHook) mapper.readValue(json, HookCommand.class);

        assertThat(hook.command()).isEqualTo("echo hi");
        assertThat(hook.args()).isNull();
        assertThat(hook.onFailure()).isNull();
        assertThat(hook.cloud()).isNull();
    }

    @Test
    void promptAndHttp_newFields_areDeserialized() throws Exception {
        String promptJson = """
            {"type":"prompt","prompt":"检查","continueOnBlock":true}
            """;
        PromptHook prompt = (PromptHook) mapper.readValue(promptJson, HookCommand.class);
        assertThat(prompt.continueOnBlock()).isTrue();

        String httpJson = """
            {"type":"http","url":"https://example.test/h","onFailure":"block"}
            """;
        HttpHook http = (HttpHook) mapper.readValue(httpJson, HookCommand.class);
        assertThat(http.onFailure()).isEqualTo("block");
    }

    @Test
    void unknownFieldOnKnownType_doesNotThrow() {
        // 生产 mapper 关掉 FAIL_ON_UNKNOWN_PROPERTIES：CC 后续加字段不应让整源失效
        String json = """
            {"type":"command","command":"echo hi","someFutureField":123}
            """;
        assertThatCode(() -> mapper.readValue(json, HookCommand.class)).doesNotThrowAnyException();
    }

    @Test
    void legacyConvenienceConstructor_stillWorksAndLeavesNewFieldsNull() {
        CommandHook hook = new CommandHook("echo hi", null, null, null, null, null, null, null);

        assertThat(hook.command()).isEqualTo("echo hi");
        assertThat(hook.args()).isNull();
        assertThat(hook.onFailure()).isNull();
        assertThat(hook.rewakeMessage()).isNull();
        assertThat(hook.rewakeSummary()).isNull();
        assertThat(hook.cloud()).isNull();
    }

    @Test
    void serialization_writesCcTypeLiteral() throws Exception {
        McpToolHook hook = new McpToolHook("fs", "read", Map.of("path", "/tmp/a"), "Read(*)", 3, null, null);

        String json = mapper.writeValueAsString(hook);

        assertThat(json).contains("\"type\":\"mcp_tool\"");
        assertThat(json).contains("\"server\":\"fs\"");
    }
}
