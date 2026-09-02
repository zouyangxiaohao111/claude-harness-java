package com.nexusai.model.hook.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.AgentHook;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookSource;
import com.nexusai.application.agent.permission.hook.HttpHook;
import com.nexusai.application.agent.permission.hook.IndividualHookConfig;
import com.nexusai.application.agent.permission.hook.PromptHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HookItemDto} / {@link HookCommandConfigDto} 单元测试（纯映射 + Jackson 序列化）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）:
 * <ol>
 *   <li><b>4 子类型映射</b>——前端 HookPanel 按 config.type 渲染不同展示（types.ts:836-849），
 *       CommandHook→command / PromptHook·AgentHook→prompt / HttpHook→url；若映射错位（如
 *       CommandHook 的 command 落到 prompt 字段），HookPanel 渲染空行。</li>
 *   <li><b>event/source 枚举名</b>——前端 HookItem.event/source 按字符串比较分组（types.ts:855-865），
 *       UPPER_SNAKE（SESSION_START/USER_SETTINGS）是约定形状。</li>
 *   <li><b>NON_NULL 省略</b>——null 子类型字段（matcher/pluginName/config.command 等）JSON 省略，
 *       type 判别器只出现一次（不泄漏 domain record 的重复 type），保证前端 TS 可解析。</li>
 * </ol>
 */
class HookItemDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("CommandHook → config.type='command' + command，prompt/url=null（前端 HookCommandConfig 扁平 6 字段）")
    void commandHook_mapsToCommandTypeAndCommand() {
        CommandHook hook = new CommandHook("echo hi", null, null, null, "running echo", Boolean.TRUE, null, null);
        HookItemDto dto = HookItemDto.from(new IndividualHookConfig(
            HookEventType.SESSION_START, hook, "Write", HookSource.USER_SETTINGS, null));

        assertThat(dto.event()).isEqualTo("SESSION_START");
        assertThat(dto.source()).isEqualTo("USER_SETTINGS");
        assertThat(dto.config().type()).isEqualTo("command");
        assertThat(dto.config().command()).isEqualTo("echo hi");
        assertThat(dto.config().statusMessage()).isEqualTo("running echo");
        assertThat(dto.config().once()).isTrue();
        assertThat(dto.config().prompt()).isNull();
        assertThat(dto.config().url()).isNull();
    }

    @Test
    @DisplayName("PromptHook → config.type='prompt' + prompt（LLM 评估 hook）")
    void promptHook_mapsToPrompt() {
        PromptHook hook = new PromptHook("assess the change", null, null, null, null, null);
        HookItemDto dto = HookItemDto.from(new IndividualHookConfig(
            HookEventType.PRE_TOOL_USE, hook, null, HookSource.LOCAL_SETTINGS, null));

        assertThat(dto.config().type()).isEqualTo("prompt");
        assertThat(dto.config().prompt()).isEqualTo("assess the change");
        assertThat(dto.config().command()).isNull();
        assertThat(dto.config().url()).isNull();
    }

    @Test
    @DisplayName("AgentHook → config.type='agent' + prompt（子 agent 验证 hook）")
    void agentHook_mapsToPrompt() {
        AgentHook hook = new AgentHook("verify tests pass", null, null, null, null, null);
        HookItemDto dto = HookItemDto.from(new IndividualHookConfig(
            HookEventType.STOP, hook, null, HookSource.BUILTIN_HOOK, null));

        assertThat(dto.config().type()).isEqualTo("agent");
        assertThat(dto.config().prompt()).isEqualTo("verify tests pass");
        assertThat(dto.config().command()).isNull();
    }

    @Test
    @DisplayName("HttpHook → config.type='http' + url，pluginName 原样透传（仅 pluginHook 来源有）")
    void httpHook_mapsToUrl() {
        HttpHook hook = new HttpHook("https://example.com/hook", null, null, null, null, null, null);
        HookItemDto dto = HookItemDto.from(new IndividualHookConfig(
            HookEventType.POST_TOOL_USE, hook, null, HookSource.POLICY_SETTINGS, "my-plugin"));

        assertThat(dto.config().type()).isEqualTo("http");
        assertThat(dto.config().url()).isEqualTo("https://example.com/hook");
        assertThat(dto.config().command()).isNull();
        assertThat(dto.config().prompt()).isNull();
        assertThat(dto.pluginName()).isEqualTo("my-plugin");
        assertThat(dto.source()).isEqualTo("POLICY_SETTINGS");
    }

    @Test
    @DisplayName("matcher/pluginName null → JSON 省略（NON_NULL），type 判别器只出现一次（无 domain record 泄漏）")
    void nullFields_omittedInJson() throws Exception {
        PromptHook hook = new PromptHook("assess", null, null, null, null, null);
        HookItemDto dto = HookItemDto.from(new IndividualHookConfig(
            HookEventType.PRE_TOOL_USE, hook, null, HookSource.LOCAL_SETTINGS, null));

        String json = objectMapper.writeValueAsString(dto);
        assertThat(json)
            .as("null matcher/pluginName 必须 JSON 省略")
            .doesNotContain("matcher").doesNotContain("pluginName");
        assertThat(json).contains("\"event\":\"PRE_TOOL_USE\"");
        assertThat(json).contains("\"config\":{\"type\":\"prompt\",\"prompt\":\"assess\"}");
        assertThat(json).contains("\"source\":\"LOCAL_SETTINGS\"");
        // type 判别器只出现一次（扁平 config 内；若泄漏 domain record 的 @JsonTypeInfo type 会重复）
        assertThat(json.split("\"type\"")).as("type 字段恰出现 1 次").hasSize(2);
    }

    @Test
    @DisplayName("非空 matcher/pluginName → JSON 保留（可被前端 HookItem.matcher/pluginName 消费）")
    void nonNullMatcherAndPlugin_serialized() throws Exception {
        CommandHook hook = new CommandHook("npm test", null, null, null, null, null, null, null);
        HookItemDto dto = HookItemDto.from(new IndividualHookConfig(
            HookEventType.POST_TOOL_USE, hook, "Bash(npm *)", HookSource.PLUGIN_HOOK, "npm-plugin"));

        String json = objectMapper.writeValueAsString(dto);
        assertThat(json).contains("\"matcher\":\"Bash(npm *)\"");
        assertThat(json).contains("\"pluginName\":\"npm-plugin\"");
        assertThat(json).contains("\"source\":\"PLUGIN_HOOK\"");
    }

    @Test
    @DisplayName("from(null) → null（null 防御，不 NPE）")
    void nullInput_returnsNull() {
        assertThat(HookItemDto.from(null)).isNull();
        assertThat(HookCommandConfigDto.from(null)).isNull();
    }
}
