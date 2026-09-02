package com.nexusai.application.agent.permission.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [OPD-24 G1] per-tool toAutoClassifierInput 投影接线测试 · 对齐 CC
 * Open-ClaudeCode/src/utils/permissions/yoloClassifier.ts:383-424（toCompactBlock）
 * + :1019-1028（'' 短路 ALLOW）。
 *
 * <p><b>WHY (意图验证)</b>: 分类器转录必须消费各工具 {@code toAutoClassifierInput}
 * 投影（CC :400 {@code tool.toAutoClassifierInput(input) ?? input}），而非 ChatMessageDto
 * 纯文本原始 JSON —— 投影是「无安全相关性工具 '' 跳过 + action 短路 ALLOW」的基础。
 * 覆盖：action 投影被消费 / '' 短路 / 异常回退 raw / 历史 '' block 跳过 / alias 查表 /
 * 查不到工具跳过。
 *
 * <p>[S06 重构] 契约变更（OPD-WF6-01）：fake LLM 返回 CC 2-stage XML（{@code <block>no</block>}
 * 放行），断言布尔 {@code shouldBlock}（⊕-02）；action 转录经 {@code <transcript>} 包裹
 * （CC classifyYoloActionXml :760-766）。
 */
class YoloClassifierPerToolProjectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final YoloPromptBuilder promptBuilder = new YoloPromptBuilder();

    // ═══════ 1. action 投影被消费（classify 集成 + builder）═══════

    @Test
    @DisplayName("OPD-24 G1: classify 消费 Bash 投影（command），不再用原始 JSON —— 对齐 CC BashTool.tsx:442-444 + :400")
    void actionProjection_consumedByClassify() throws Exception {
        ToolRegistry registry = registryWithBashProjection("ls -la");
        AtomicReference<String> capturedUserMessage = new AtomicReference<>();
        AtomicBoolean llmCalled = new AtomicBoolean(false);
        LlmProvider fake = new FakeLlm() {
            @Override
            public LlmRawResponse chatWithRaw(ProviderConfig config, String modelName,
                                              String systemPrompt, String userMessage) {
                llmCalled.set(true);
                capturedUserMessage.set(userMessage);
                // [S06] CC 2-stage XML：stage1 <block>no</block> → fast allow（yoloClassifier.ts:807-823）
                return new LlmRawResponse(
                    "<block>no</block>",
                    "msg_stage1_proj", null, "req_stage1");
            }
        };
        YoloClassifierImpl classifier = newClassifier(fake, registry);

        JsonNode input = objectMapper.createObjectNode().put("command", "ls -la");
        YoloClassifierResult result = classifier.classify("Bash", input, List.of(), null)
            .get(10, TimeUnit.SECONDS);

        assertThat(result.shouldBlock()).as("投影非空 → 必须走 LLM 并返回放行（shouldBlock=false）").isFalse();
        assertThat(llmCalled).as("投影非空 → LLM 必须被调用").isTrue();
        assertThat(capturedUserMessage.get())
            .as("action 转录必须是投影块 \"Bash ls -la\\n\"（CC :416），不得含原始 JSON 键 command")
            .contains("Bash ls -la")
            .doesNotContain("\"command\"");
    }

    @Test
    @DisplayName("OPD-24 G1: toCompactBlock 输出投影串（CC :416 `${block.name} ${s}\\n`）")
    void toCompactBlock_returnsProjectionLine() {
        ToolRegistry registry = registryWithBashProjection("ls -la");
        String block = promptBuilder.toCompactBlock("Bash",
            objectMapper.createObjectNode().put("command", "ls -la"), lookupFrom(registry));
        assertThat(block).isEqualTo("Bash ls -la\n");
    }

    // ═══════ 2. '' 短路 ALLOW ═══════

    @Test
    @DisplayName("OPD-24 G1: action 投影为 '' → 短路 ALLOW（不调 LLM），reason 对齐 CC :1021-1028")
    void emptyProjection_shortCircuitsAllow() throws Exception {
        ToolRegistry registry = registryWithBashProjection("");
        AtomicBoolean llmCalled = new AtomicBoolean(false);
        LlmProvider fake = new FakeLlm() {
            @Override
            public LlmRawResponse chatWithRaw(ProviderConfig config, String modelName,
                                              String systemPrompt, String userMessage) {
                llmCalled.set(true);
                return new LlmRawResponse("<block>no</block>", "msg_stage1_never", null, "req_stage1");
            }
        };
        YoloClassifierImpl classifier = newClassifier(fake, registry);

        JsonNode input = objectMapper.createObjectNode().put("command", "ls -la");
        YoloClassifierResult result = classifier.classify("Bash", input, List.of(), null)
            .get(10, TimeUnit.SECONDS);

        assertThat(result.shouldBlock()).isFalse();
        assertThat(result.reason())
            .as("'' 短路 reason 对齐 CC :1023-1028 'Tool declares no classifier-relevant input'")
            .isEqualTo("Tool declares no classifier-relevant input");
        assertThat(llmCalled).as("'' 短路 → LLM 不得被调用").isFalse();
    }

    @Test
    @DisplayName("OPD-24 G1: toCompactBlock 投影为 '' → 返回 ''（跳过块，CC :411）")
    void toCompactBlock_emptyProjection_skips() {
        ToolRegistry registry = registryWithBashProjection("");
        String block = promptBuilder.toCompactBlock("Bash",
            objectMapper.createObjectNode().put("command", "ls -la"), lookupFrom(registry));
        assertThat(block).isEqualTo("");
    }

    // ═══════ 3. 投影异常回退 raw ═══════

    @Test
    @DisplayName("OPD-24 G1: toAutoClassifierInput 抛异常 → 回退 raw input JSON（CC :402-408），不崩")
    void projectionException_fallsBackRaw() {
        ToolRegistry registry = registryWithThrowingBash();
        String block = promptBuilder.toCompactBlock("Bash",
            objectMapper.createObjectNode().put("command", "ls -la"), lookupFrom(registry));
        assertThat(block)
            .as("异常回退 raw → \"Bash {\"command\":\"ls -la\"}\\n\"（CC :402-408 catch→encoded=input）")
            .isEqualTo("Bash {\"command\":\"ls -la\"}\n");
    }

    // ═══════ 4. 历史 '' block 跳过 + user 保留 ═══════

    @Test
    @DisplayName("OPD-24 G1: 历史 assistant toolCall 投影为 '' → 跳过该块；user 文本保留（CC :389-417/:418-422）")
    void historyEmptyProjectionBlock_skipped() {
        ToolRegistry registry = registryWithBashProjection().putTaskCreate();
        List<ChatMessageDto> transcript = List.of(
            userMessage("hello"),
            assistantWithToolCalls(List.of(
                new ToolCallDto("id1", "Bash", "{\"command\":\"rm -rf /\"}", null, null),
                new ToolCallDto("id2", "TaskCreate", "{\"subject\":\"fix bug\"}", null, null)
            ))
        );
        List<YoloPromptBuilder.CompactMessage> entries =
            promptBuilder.buildTranscriptEntries(transcript, lookupFrom(registry));

        YoloPromptBuilder.CompactMessage asst = entries.stream()
            .filter(e -> "assistant".equals(e.role())).findFirst().orElseThrow();
        assertThat(asst.content())
            .as("Bash 投影 '' 跳过（CC :411）；TaskCreate 投影保留")
            .contains("TaskCreate fix bug")
            .doesNotContain("Bash");
        YoloPromptBuilder.CompactMessage user = entries.stream()
            .filter(e -> "user".equals(e.role())).findFirst().orElseThrow();
        assertThat(user.content()).as("user 分支与 lookup 无关（CC :418-422）").isEqualTo("User: hello\n");
    }

    // ═══════ 4b. IMP-6 OPD-WF6-01-RV: 1-stage 分类器消费投影 ═══════

    @Test
    @DisplayName("IMP-6 OPD-WF6-01-RV: 1-stage 分类器（twoStageClassifier=false）消费 Bash 投影，"
        + "userMessage 含 \"Bash ls -la\" 不含原始 JSON（CC :1040-1061 + :1151-1155）")
    void oneStage_actionProjection_consumed() throws Exception {
        ToolRegistry registry = registryWithBashProjection("ls -la");
        AtomicReference<String> capturedUserMessage = new AtomicReference<>();
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        LlmProvider fake = new FakeLlm() {
            @Override
            public AssistantMessage chatWithOptionsMessage(ProviderConfig config, String modelName,
                                                           String systemPrompt, String userMessage,
                                                           LlmProvider.ChatRequestOptions options) {
                capturedUserMessage.set(userMessage);
                capturedOptions.set(options);
                JsonNode input = new ObjectMapper().createObjectNode()
                    .put("thinking", "ok")
                    .put("shouldBlock", false)
                    .put("reason", "read-only ls");
                return new AssistantMessage("", "tool_calls",
                    List.of(new ToolUseBlock("call_1", YoloPromptBuilder.CLASSIFY_RESULT_TOOL_NAME, input)),
                    "", null, new AgentUsage(1L, 1L, 0L, 0L, null, null, null), null);
            }
        };
        YoloClassifierImpl classifier = newClassifierOneStage(fake, registry);

        JsonNode input = objectMapper.createObjectNode().put("command", "ls -la");
        YoloClassifierResult result = classifier.classify("Bash", input, List.of(), null)
            .get(10, TimeUnit.SECONDS);

        assertThat(result.shouldBlock()).as("1-stage 解析 shouldBlock=false → 放行").isFalse();
        assertThat(capturedOptions.get()).as("1-stage 必须走工具协议（tools + tool_choice）").isNotNull();
        assertThat(capturedOptions.get().toolChoice().name()).isEqualTo("classify_result");
        assertThat(capturedUserMessage.get())
            .as("action 转录必须是投影块 \"Bash ls -la\"（CC :416），不得含原始 JSON 键 command")
            .contains("Bash ls -la")
            .doesNotContain("\"command\"");
    }

    // ═══════ 5. alias 查表 ═══════

    @Test
    @DisplayName("OPD-24 G1: 历史 toolCall 用 alias（老名）→ 同一 Tool 投影被消费（CC buildToolLookup :364-374 alias 双路径）")
    void aliasLookup_resolvesSameToolProjection() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(aliasTool("ReadFile", List.of("read"), "file projection"));
        List<ChatMessageDto> transcript = List.of(
            assistantWithToolCalls(List.of(
                new ToolCallDto("id1", "read", "{\"file_path\":\"/tmp/x\"}", null, null)
            ))
        );
        List<YoloPromptBuilder.CompactMessage> entries =
            promptBuilder.buildTranscriptEntries(transcript, lookupFrom(registry));
        YoloPromptBuilder.CompactMessage asst = entries.stream()
            .filter(e -> "assistant".equals(e.role())).findFirst().orElseThrow();
        // CC :416 `${block.name} ${s}\n` —— 前缀是转录里的块名（alias "read"），非工具规范名
        assertThat(asst.content())
            .as("alias 'read' 经 lookup 解析到 ReadFile 工具消费其投影，但序列化前缀=转录块名 'read'（CC :416）")
            .isEqualTo("read file projection\n");
    }

    // ═══════ 6. 查不到工具跳过 ═══════

    @Test
    @DisplayName("OPD-24 G1: 历史 toolCall 工具不在 registry → 跳过该块（CC :390-391 lookup 查不到 → ''）")
    void unknownTool_blockSkipped() {
        ToolRegistry registry = new ToolRegistry(); // 空 registry：查不到任何工具
        List<ChatMessageDto> transcript = List.of(
            assistantWithToolCalls(List.of(
                new ToolCallDto("id1", "GhostTool", "{\"x\":1}", null, null)
            ))
        );
        List<YoloPromptBuilder.CompactMessage> entries =
            promptBuilder.buildTranscriptEntries(transcript, lookupFrom(registry));
        assertThat(entries)
            .as("查不到工具 → assistant 块全跳过，条目不存在（CC :390-391）")
            .isEmpty();
    }

    // ═══════ helpers ═══════

    /** 构造真实 YoloClassifierImpl + 注入带投影 Bash stub 的 registry。 */
    private YoloClassifierImpl newClassifier(LlmProvider fakeProvider, ToolRegistry registry) {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        Mockito.when(factory.getProvider(Mockito.any(), Mockito.nullable(String.class))).thenReturn(fakeProvider);
        YoloClassifierImpl classifier = new YoloClassifierImpl(
            promptBuilder, new YoloTokenEstimator(), factory,
            "claude-fast", 30, resolver,
            true /* two-stage XML：本测试 fake LLM 返回 CC 2-stage XML <block> 协议（OPD-WF6-01 重构） */);
        classifier.setToolRegistry(registry);
        return classifier;
    }

    /**
     * [IMP-6 OPD-WF6-01-RV] 构造 1-stage 分类器（twoStageClassifier=false）· 与
     * {@link #newClassifier} 同构，仅 two-stage 开关为 false（对齐 CC 默认 1-stage）。
     */
    private YoloClassifierImpl newClassifierOneStage(LlmProvider fakeProvider, ToolRegistry registry) {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        Mockito.when(factory.getProvider(Mockito.any(), Mockito.nullable(String.class))).thenReturn(fakeProvider);
        YoloClassifierImpl classifier = new YoloClassifierImpl(
            promptBuilder, new YoloTokenEstimator(), factory,
            "claude-fast", 30, resolver,
            false /* 1-stage classify_result 工具协议（CC 默认） */);
        classifier.setToolRegistry(registry);
        return classifier;
    }

    /** 与生产 buildProjectionLookup 相同的 name+alias → Tool 查表（CC buildToolLookup :364-374）。 */
    private Map<String, Tool> lookupFrom(ToolRegistry registry) {
        Map<String, Tool> lookup = new HashMap<>();
        for (Tool tool : registry.all()) {
            lookup.put(tool.name(), tool);
            for (String alias : tool.aliases()) {
                if (alias != null && !alias.isBlank()) {
                    lookup.put(alias, tool);
                }
            }
        }
        return lookup;
    }

    /** Bash stub registry，toAutoClassifierInput 返回固定投影串（CC BashTool.tsx:442-444 投影语义）。 */
    private ToolRegistry registryWithBashProjection(String projection) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(bashTool("Bash", projection));
        return registry;
    }

    private ToolRegistry registryWithThrowingBash() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub Bash throwing"; }
            @Override public JsonNode inputSchema() { return objectMapper.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub ok");
            }
            @Override public String toAutoClassifierInput(JsonNode input) {
                throw new IllegalStateException("bad input shape");
            }
        });
        return registry;
    }

    /** 通用 stub 工具：name + 固定投影。 */
    private Tool bashTool(String name, String projection) {
        JsonNode schema = objectMapper.createObjectNode();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub ok");
            }
            @Override public String toAutoClassifierInput(JsonNode input) { return projection; }
        };
    }

    /** alias 工具：name + aliases，toAutoClassifierInput 返回固定投影串。 */
    private Tool aliasTool(String name, List<String> aliases, String projection) {
        JsonNode schema = objectMapper.createObjectNode();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public List<String> aliases() { return aliases; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub ok");
            }
            @Override public String toAutoClassifierInput(JsonNode input) { return projection; }
        };
    }

    /** 附加 TaskCreate stub（投影=subject），供历史 '' 跳过场景。 */
    private static final class TaskCreateAdder {
        private final ToolRegistry registry;
        TaskCreateAdder(ToolRegistry registry) { this.registry = registry; }
        ToolRegistry putTaskCreate() {
            registry.register(new Tool() {
                @Override public String name() { return "TaskCreate"; }
                @Override public String description() { return "stub TaskCreate"; }
                @Override public JsonNode inputSchema() { return new ObjectMapper().createObjectNode(); }
                @Override public AgentToolResult execute(ToolUseBlock call) {
                    return ToolResult.success(call.id(), "stub ok");
                }
                @Override public String toAutoClassifierInput(JsonNode input) {
                    return input != null && input.get("subject") != null
                        ? input.get("subject").asText() : "";
                }
            });
            return registry;
        }
    }

    private TaskCreateAdder registryWithBashProjection() {
        return new TaskCreateAdder(registryWithBashProjection(""));
    }

    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(null, null, Role.user, null, content, null, null, null, null,
            null, null, null, null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto assistantWithToolCalls(List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(null, null, Role.assistant, null, null, null, toolCalls, null,
            null, null, null, null, null, null, null, List.of(), List.of());
    }

    /** 最小 fake LLM：默认抽象方法抛不支持（YoloClassifier 路径只用 chatWithRaw，M3.2）。 */
    private abstract static class FakeLlm implements LlmProvider {
        @Override public String type() { return "fake"; }
        @Override public String chat(ProviderConfig config, String modelName,
                                     String systemPrompt, String userMessage) {
            throw new UnsupportedOperationException("YoloClassifier 路径必须走 chatWithRaw (M3.2)");
        }
        // [MERGE-FIX] 合并后 LlmProvider.stream 已收敛为 blocks 单一契约（⊕C-1）
        @Override public void stream(ProviderConfig config, String modelName,
                                     List<SystemPromptBlock> systemPromptBlocks,
                                     List<ChatMessageDto> history, ArrayNode tools,
                                     Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                                     String effortValue, String querySource,
                                     Consumer<String> onChunk, Consumer<AssistantMessage> onAssistantMessage,
                                     Consumer<ToolUseBlock> onToolCallComplete,
                                     Consumer<String> onReasoningChunk, Runnable onStreamingFallback,
                                     AbortController abortController,
                                     Consumer<Throwable> onError, Runnable onComplete) {
            throw new UnsupportedOperationException("YoloClassifier 路径不使用 stream");
        }
    }
}
