package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.HookCommand;
import com.nexusai.application.agent.permission.hook.PromptHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Wave2-J1] 插件 {@code hooks/hooks.json} 的 snake_case schema · 对齐 claude.exe v2.1.325
 * {@code pg()} 插件侧变体：{@code on_failure / status_message / async_rewake / rewake_message /
 * rewake_summary / args}，且插件侧 {@code on_failure} 缺省/非法值是 <b>block</b>
 * （CC {@code .nullish().catch("block")}），与 settings camelCase {@code onFailure} 缺省
 * {@code continue} 不同。
 *
 * <p><b>WHY</b>: 两侧缺省值相反。若把插件 hook 按 settings 规则解析成 {@code continue}，
 * 插件作者依赖的 fail-closed 守卫会静默变成 fail-open。
 */
@DisplayName("[Wave2-J1] 插件 hooks.json snake_case 解析 + on_failure 缺省 block")
class PluginHookSnakeCaseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HookCommand parseAny(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        return PluginLoader.parsePluginHookCommand(node, "p");
    }

    private CommandHook parse(String json) throws Exception {
        return (CommandHook) parseAny(json);
    }

    @Test
    @DisplayName("完整 snake_case 条目逐字段解析")
    void fullSnakeCaseEntry() throws Exception {
        CommandHook hook = parse("""
            {
              "type": "command",
              "command": "node hook.js",
              "args": ["a", "b"],
              "if": "Read(*)",
              "timeout": 5,
              "on_failure": "continue",
              "status_message": "checking",
              "once": true,
              "async": true,
              "async_rewake": true,
              "rewake_message": "wmsg",
              "rewake_summary": "wsum"
            }
            """);

        assertThat(hook).isNotNull();
        assertThat(hook.command()).isEqualTo("node hook.js");
        assertThat(hook.args()).containsExactly("a", "b");
        assertThat(hook.ifCondition()).isEqualTo("Read(*)");
        assertThat(hook.timeout()).isEqualTo(5);
        assertThat(hook.onFailure()).isEqualTo("continue");
        assertThat(hook.statusMessage()).isEqualTo("checking");
        assertThat(hook.once()).isTrue();
        assertThat(hook.asyncFlag()).isTrue();
        assertThat(hook.asyncRewake()).isTrue();
        assertThat(hook.rewakeMessage()).isEqualTo("wmsg");
        assertThat(hook.rewakeSummary()).isEqualTo("wsum");
    }

    @Test
    @DisplayName("on_failure 缺失 → block（插件侧缺省，与 settings continue 相反）")
    void missingOnFailureDefaultsToBlock() throws Exception {
        CommandHook hook = parse("{\"type\":\"command\",\"command\":\"x\"}");
        assertThat(hook).isNotNull();
        assertThat(hook.onFailure()).isEqualTo("block");
    }

    @Test
    @DisplayName("on_failure 非法值 → block（CC .catch(\"block\")）")
    void invalidOnFailureFallsBackToBlock() throws Exception {
        CommandHook hook = parse("{\"type\":\"command\",\"command\":\"x\",\"on_failure\":\"bogus\"}");
        assertThat(hook).isNotNull();
        assertThat(hook.onFailure()).isEqualTo("block");
    }

    @Test
    @DisplayName("[Wave2-J2] prompt 类型已接线（不再返回 null）")
    void promptTypeNowParsed() throws Exception {
        HookCommand hook = parseAny("{\"type\":\"prompt\",\"prompt\":\"check it\",\"continue_on_block\":true}");
        assertThat(hook).isInstanceOf(PromptHook.class);
        assertThat(((PromptHook) hook).continueOnBlock()).isTrue();
    }

    @Test
    @DisplayName("字符串简写 → command + 插件侧缺省 block")
    void textualShorthand() throws Exception {
        CommandHook hook = parse("\"echo hi\"");
        assertThat(hook).isNotNull();
        assertThat(hook.command()).isEqualTo("echo hi");
        assertThat(hook.onFailure()).isEqualTo("block");
    }

    @Test
    @DisplayName("camelCase 旧写法仍兼容（statusMessage / onFailure）")
    void camelCaseStillAccepted() throws Exception {
        CommandHook hook = parse("""
            {"type":"command","command":"x","statusMessage":"m","onFailure":"continue"}
            """);
        assertThat(hook).isNotNull();
        assertThat(hook.statusMessage()).isEqualTo("m");
        assertThat(hook.onFailure()).isEqualTo("continue");
    }
}
