package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.FunctionDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [G4] strict 透传接线 · 4 条件门控分支 + 三链路透传 + 门控失败无 strict 断言。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：CC api.ts:185-192 的 4 条件门控
 * {@code strictToolsEnabled && tool.strict===true && options.model && modelSupportsStructuredOutputs}
 * 必须在 Java 侧三链路（ToolRegistry JSON 意图层 / Anthropic toSdkTool / OpenAI toOpenAiSdkTool）
 * 全部接线，且任一条件失败都静默降级不传——否则：意图层写了 strict 但 provider 不读（透传断裂），
 * 或 provider 无门控直传（Bedrock/Vertex/LiteLLM 代理 400，OPD-28 决策核心动机）。
 *
 * <p>两层分工（对齐 api.ts:185-192）：意图层只判前两条件（flag && tool.strict → JSON strict:true），
 * 模型层判后两条件（model != null + 白名单 + firstParty），各自可独立单测。
 *
 * @see StructuredOutputsSupport
 * @see ToolRegistry#toOpenAiToolsArray()
 * @see AnthropicSdkProvider#toSdkTool(JsonNode, boolean)
 * @see OpenAiSdkProvider#toOpenAiSdkTool(JsonNode, boolean)
 */
class StrictPassthroughGateTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @AfterEach
    void clearFlag() {
        System.clearProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY);
    }

    // ══════════════════════ 意图层 · ToolRegistry.toOpenAiToolsArray ══════════════════════

    @Test
    @DisplayName("意图层：flag 开 && tool.strict() → JSON fn.strict=true")
    void registryWritesStrictWhenFlagAndToolStrict() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        ToolRegistry registry = new ToolRegistry().register(strictTool("strict_test_tool"));
        ArrayNode arr = registry.toOpenAiToolsArray();
        assertThat(arr.get(0).get("function").get("strict").asBoolean())
            .as("flag && tool.strict() 必须写 fn.strict=true（CC api.ts:185-192）").isTrue();
    }

    @Test
    @DisplayName("意图层：flag 关 → 即使 tool.strict() 也不写 strict")
    void registryOmitsStrictWhenFlagOff() {
        System.clearProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY);
        ToolRegistry registry = new ToolRegistry().register(strictTool("strict_test_tool"));
        ArrayNode arr = registry.toOpenAiToolsArray();
        assertThat(arr.get(0).get("function").has("strict"))
            .as("flag 关时必须无 strict 字段（CC strictToolsEnabled=false 早退）").isFalse();
    }

    @Test
    @DisplayName("意图层：flag 开但 tool.strict()=false → 不写 strict")
    void registryOmitsStrictWhenToolNotStrict() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        ToolRegistry registry = new ToolRegistry().register(plainTool("plain_test_tool"));
        ArrayNode arr = registry.toOpenAiToolsArray();
        assertThat(arr.get(0).get("function").has("strict"))
            .as("tool.strict()=false 时必须无 strict 字段（CC tool.strict===true 不满足）").isFalse();
    }

    // ══════════════════════ Anthropic 模型层 · toSdkTool ══════════════════════

    @Test
    @DisplayName("Anthropic：4 条件全过 → SDK Tool.strict()=true")
    void anthropicTransmitsStrictWhenGatePasses() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        MessageCreateParamsToolCheck.checkStrict(anthropicParams("claude-sonnet-4-5-20250929", true), true);
    }

    @Test
    @DisplayName("Anthropic：flag 关 → strict 静默降级（SDK 无 strict 字段）")
    void anthropicDropsStrictWhenFlagOff() {
        System.clearProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY);
        MessageCreateParamsToolCheck.checkStrict(anthropicParams("claude-sonnet-4-5-20250929", true), false);
    }

    @Test
    @DisplayName("Anthropic：model null → strict 静默降级")
    void anthropicDropsStrictWhenModelNull() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        MessageCreateParamsToolCheck.checkStrict(anthropicParams(null, true), false);
    }

    @Test
    @DisplayName("Anthropic：白名单外模型 → strict 静默降级")
    void anthropicDropsStrictWhenModelNotWhitelisted() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        MessageCreateParamsToolCheck.checkStrict(anthropicParams("claude-3-5-sonnet-20241022", true), false);
    }

    @Test
    @DisplayName("Anthropic：非 firstParty（自定义 baseUrl，Bedrock/Vertex/LiteLLM）→ strict 静默降级防 400")
    void anthropicDropsStrictWhenNotFirstParty() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        MessageCreateParamsToolCheck.checkStrict(anthropicParams("claude-sonnet-4-5-20250929", false), false);
    }

    // ══════════════════════ OpenAI 模型层 · toOpenAiSdkTool ══════════════════════

    @Test
    @DisplayName("OpenAI：4 条件全过 → FunctionDefinition.strict()=true")
    void openAiTransmitsStrictWhenGatePasses() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        ChatCompletionCreateParams params = openAiParams("gpt-4o-2024-08-06");
        FunctionDefinition fn = params.tools().orElseThrow().get(0).function();
        assertThat(fn.strict())
            .as("OpenAI 白名单模型+flag 开 → strict 必须透传（Java 多 provider 扩展 ⊕）").hasValue(true);
    }

    @Test
    @DisplayName("OpenAI：flag 关 → strict 静默降级")
    void openAiDropsStrictWhenFlagOff() {
        System.clearProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY);
        ChatCompletionCreateParams params = openAiParams("gpt-4o");
        FunctionDefinition fn = params.tools().orElseThrow().get(0).function();
        assertThat(fn.strict()).as("flag 关 → OpenAI 请求无 strict 字段").isEmpty();
    }

    @Test
    @DisplayName("OpenAI：白名单外模型 → strict 静默降级")
    void openAiDropsStrictWhenModelNotWhitelisted() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        ChatCompletionCreateParams params = openAiParams("gpt-3.5-turbo");
        FunctionDefinition fn = params.tools().orElseThrow().get(0).function();
        assertThat(fn.strict()).as("OpenAI 白名单外模型 → 不传 strict").isEmpty();
    }

    @Test
    @DisplayName("OpenAI：model null → strict 静默降级（CC options.model 条件不满足，同 Anthropic 分支）")
    void openAiDropsStrictWhenModelNull() {
        System.setProperty(StructuredOutputsSupport.TENGU_TOOL_PEAR_PROPERTY, "true");
        // WHY：CC api.ts:189 `options.model` 是 4 条件门控之一——model 缺失时视为不支持 strict，
        //    必须静默降级，否则空 model 场景会直传 strict 触发网关 400。
        ChatCompletionCreateParams params = openAiParams(null);
        FunctionDefinition fn = params.tools().orElseThrow().get(0).function();
        assertThat(fn.strict()).as("OpenAI model null → 请求无 strict 字段").isEmpty();
    }

    // ══════════════════════ firstParty 判定边界 ══════════════════════

    @Test
    @DisplayName("firstParty 判定：null/blank baseUrl → true（默认 api.anthropic.com）")
    void firstPartyNullBlankIsFirstParty() {
        // WHY：CC providers.ts:25-37 —— baseUrl 未配置/默认即 first-party（生产 config.baseUrl 可能为 null），
        //    误判为自定义代理会错误地吞掉 strict 透传。
        assertThat(StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl(null))
            .as("null baseUrl=默认 1P → first-party").isTrue();
        assertThat(StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl("   "))
            .as("blank baseUrl=默认 1P → first-party").isTrue();
    }

    @Test
    @DisplayName("firstParty 判定：api.anthropic.com → true；Bedrock/Vertex/LiteLLM 自定义 URL → false")
    void firstPartyCustomUrlNotFirstParty() {
        // WHY：CC betas.ts:146 —— 非 firstParty/foundry provider 必须不传 strict（Bedrock/Vertex 回 400，
        //    OPD-28 决策核心动机）。自定义 URL 与 foundry 不可区分时 Java 保守判 false（safe direction）。
        assertThat(StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl("https://api.anthropic.com"))
            .as("官方 api.anthropic.com → first-party").isTrue();
        assertThat(StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl("https://bedrock-runtime.us-east-1.amazonaws.com"))
            .as("Bedrock ARN 域名非 1P → 不传 strict 防 400").isFalse();
        assertThat(StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl("https://llm.proxy.example.com/v1"))
            .as("LiteLLM/自定义代理 URL 非 1P → 不传 strict 防 400").isFalse();
    }

    // ══════════════════════ 工具 ══════════════════════

    private static Tool strictTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "strict test tool"; }
            @Override public JsonNode inputSchema() { return OM.createObjectNode(); }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
            @Override public boolean strict() { return true; }
        };
    }

    private static Tool plainTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "plain test tool"; }
            @Override public JsonNode inputSchema() { return OM.createObjectNode(); }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
        };
    }

    private static com.anthropic.models.messages.MessageCreateParams anthropicParams(String model, boolean firstParty) {
        return AnthropicSdkProvider.buildMessageParams(
            model, (List<SystemPromptBlock>) null, List.of(), toolsArrayWithStrict("strict_tool"),
            null, null, null, null, null, null, firstParty);
    }

    private static ChatCompletionCreateParams openAiParams(String model) {
        // OpenAI SDK 校验 messages 必填 → 塞一条 user 消息（与 toOpenAiSdkTool strict 断言无关）。
        List<ChatMessageDto> history = List.of(
            new ChatMessageDto(null, null, Role.user, null, "hi", null, null, null, null, null,
                null, null, null, null, null, java.util.List.of(), java.util.List.of()));
        return OpenAiSdkProvider.buildRequestParams(
            model, null, history, toolsArrayWithStrict("strict_tool"), null, false, null, null, null);
    }

    private static ArrayNode toolsArrayWithStrict(String name) {
        ArrayNode arr = OM.createArrayNode();
        ObjectNode tool = arr.addObject();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", name);
        fn.put("description", "d");
        fn.putObject("parameters");
        fn.put("strict", true);
        return arr;
    }

    /** anthropic SDK Tool.strict 断言助手（避免 SDK Tool 与 Java Tool 接口同名冲突）。 */
    private static final class MessageCreateParamsToolCheck {
        static void checkStrict(com.anthropic.models.messages.MessageCreateParams params, boolean expectStrict) {
            com.anthropic.models.messages.Tool tool = params.tools().orElseThrow().get(0).tool().orElseThrow();
            assertThat(tool.strict())
                .as(expectStrict ? "门控全过必须透传 strict" : "门控失败必须静默降级（请求无 strict 字段）")
                .isEqualTo(expectStrict ? java.util.Optional.of(true) : java.util.Optional.empty());
        }
    }
}
