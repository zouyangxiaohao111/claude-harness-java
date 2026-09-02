package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.openai.models.ChatCompletionChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [bugfix] OpenAiSdkProvider 流式 tool_calls 解析测试（读 typed 字段而非 _additionalProperties）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: openai-java 0.25.0 的
 * {@code ChatCompletionChunk.Choice.Delta} 把 {@code tool_calls} 声明为 typed 字段
 * {@code toolCalls()}（@JsonProperty 类型化），Stainless 生成类只把<b>未知</b>字段放
 * {@code _additionalProperties}。旧实现 {@code delta._additionalProperties().get("tool_calls")}
 * 恒 null → 流式 tool_calls 全静默丢弃 → state.toolCalls 恒空 → buildAssistantMessage 产出空
 * blocks → 主循环 toolCalls=0 纯文本 NORMAL 退出（只跑 1 轮）。本测试锁定「解析后
 * state.toolCalls 非空且 name/arguments 正确累积」。
 * 变异点：回到旧的 _additionalProperties 读取 → 本测试红。
 */
@DisplayName("[bugfix] OpenAiSdkProvider 流式 tool_calls 解析（typed toolCalls() · 非 _additionalProperties）")
class OpenAiSdkProviderToolCallsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Stainless mapper 从 SSE JSON 构造 chunk（tool_calls 为已知 typed 字段 → 落 toolCalls()）。 */
    private static ChatCompletionChunk chunk(String json) throws Exception {
        return com.openai.core.ObjectMappers.jsonMapper()
            .convertValue(JSON.readTree(json), ChatCompletionChunk.class);
    }

    /** 6 参便捷版 · finish_reason 固定 TOOL_CALLS（既有测试用）。 */
    private static ChatCompletionChunk toolCallChunk(String chunkId, long index, String tcId,
                                                     String type, String name, String arguments) {
        return toolCallChunk(chunkId, index, tcId, type, name, arguments,
            ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS);
    }

    /** 7 参版 · 显式控制 finish_reason（fix-toolcalls-400 A-2 流结束补发测试用；null = 无 finish_reason）。 */
    private static ChatCompletionChunk toolCallChunk(String chunkId, long index, String tcId,
                                                     String type, String name, String arguments,
                                                     ChatCompletionChunk.Choice.FinishReason finishReason) {
        ChatCompletionChunk.Choice.Delta.ToolCall.Builder tcBuilder =
            ChatCompletionChunk.Choice.Delta.ToolCall.builder().index(index);
        if (tcId != null) {
            tcBuilder.id(tcId);
        }
        if (type != null) {
            tcBuilder.type(ChatCompletionChunk.Choice.Delta.ToolCall.Type.of(type));
        }
        if (name != null || arguments != null) {
            ChatCompletionChunk.Choice.Delta.ToolCall.Function.Builder fnBuilder =
                ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder();
            if (name != null) {
                fnBuilder.name(name);
            }
            if (arguments != null) {
                fnBuilder.arguments(arguments);
            }
            tcBuilder.function(fnBuilder.build());
        }
        // SDK Choice.Builder 要求 finish_reason 必填（无 finish_reason 场景用 JSON chunk 构造）。
        return ChatCompletionChunk.builder()
            .id(chunkId)
            .object_(com.openai.core.JsonValue.from("chat.completion.chunk"))
            .created(0L)
            .model("deepseek-chat")
            .addChoice(ChatCompletionChunk.Choice.builder()
                .index(0L)
                .finishReason(finishReason)
                .delta(ChatCompletionChunk.Choice.Delta.builder()
                    .role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT)
                    .addToolCall(tcBuilder.build())
                    .build())
                .build())
            .build();
    }

    @Test
    @DisplayName("含 tool_calls 的流式 chunk → state.toolCalls 非空（name=Bash / arguments / type=function）")
    void parseChunk_typedToolCalls_populatesState() throws Exception {
        // GIVEN: 一个含 tool_calls 的流式 chunk（deepseek 实测：typed toolCalls() present size=1 first=Bash）
        ChatCompletionChunk tcChunk = chunk(
            "{\"id\":\"chatcmpl-t1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"deepseek-chat\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_abc123\",\"type\":\"function\","
                + "\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}"
                + "]},\"finish_reason\":null}]}");

        // WHEN: 解析
        OpenAiStreamState state = new OpenAiStreamState();
        new OpenAiSdkProvider().parseChunk(tcChunk, state, null, null, null,
            ConcurrentHashMap.newKeySet());

        // THEN: typed toolCalls() 必须被读入 state.toolCalls（旧 _additionalProperties 实现恒空 → 红）
        assertThat(state.toolCalls).as("typed toolCalls() 解析后 state.toolCalls 必须非空")
            .hasSize(1);
        OpenAiToolCallAccumulator acc = state.toolCalls.get(0);
        assertThat(acc.id).isEqualTo("call_abc123");
        assertThat(acc.name).isEqualTo("Bash");
        assertThat(acc.args).isEqualTo("{\"command\":\"ls\"}");
        assertThat(acc.type).as("SDK Type.toString() 返回 wire value 'function'").isEqualTo("function");
    }

    @Test
    @DisplayName("跨 chunk 累积：arguments 分块到达 → 拼接完整 + isComplete() + toBlock() 产出 Bash 工具块")
    void parseChunk_accumulatesArgumentsAcrossChunks() throws Exception {
        // WHY: OpenAI 流式协议中 tool_call 第一块给 id/type/name，后续块只给 arguments 的部分
        //   （JSON 字符串按字符拼）。旧实现全丢弃 → 无法累积；新实现必须跨 chunk 拼完整。
        // GIVEN: chunk1 给 id/type/name + 半截 arguments；chunk2 只给剩余 arguments
        OpenAiStreamState state = new OpenAiStreamState();
        ChatCompletionChunk c1 = toolCallChunk("chatcmpl-t2", 0L, "call_xyz", "function", "Bash",
            "{\"comman");
        ChatCompletionChunk c2 = toolCallChunk("chatcmpl-t3", 0L, null, null, null,
            "d\":\"ls\"}");

        new OpenAiSdkProvider().parseChunk(c1, state, null, null, null, ConcurrentHashMap.newKeySet());
        new OpenAiSdkProvider().parseChunk(c2, state, null, null, null, ConcurrentHashMap.newKeySet());

        // THEN: 同一 index 累积同一 accumulator，arguments 拼接完整
        assertThat(state.toolCalls).hasSize(1);
        OpenAiToolCallAccumulator acc = state.toolCalls.get(0);
        assertThat(acc.id).isEqualTo("call_xyz");
        assertThat(acc.name).isEqualTo("Bash");
        assertThat(acc.args).isEqualTo("{\"command\":\"ls\"}");
        assertThat(acc.isComplete()).as("完整 JSON 对象 → isComplete()=true（onToolCallComplete 可回调）").isTrue();
        assertThat(acc.toBlock()).isNotNull();
        assertThat(acc.toBlock().name()).isEqualTo("Bash");
        assertThat(acc.toBlock().input().get("command").asText()).isEqualTo("ls");
    }

    // ── fix-toolcalls-400 A：空参数工具调用合法化（isComplete + 流结束补发）──

    @Test
    @DisplayName("fix-toolcalls-400 A-1: arguments={}（空对象）→ isComplete()==true（空参工具调用合法）")
    void isComplete_emptyObject_true() {
        // WHY: 模型产出无参工具调用时 arguments 为 "{}"。原实现 parsed.size()>0 恒 false → 空参工具
        //   永不进执行器 → [assistant(N calls), tool(S<N results)] → OpenAI 400（根因 1.1）。
        //   空对象 = 完整调用（对齐 AnthropicSdkProvider:2813 宽松语义 + CC 无参数非空要求）。
        OpenAiToolCallAccumulator acc = new OpenAiToolCallAccumulator();
        acc.id = "call_empty";
        acc.name = "Bash";
        acc.args = "{}";
        assertThat(acc.isComplete()).as("{} 空参工具调用必须 isComplete=true").isTrue();
        assertThat(acc.toBlock().input()).as("空参工具 toBlock input 非 null").isNotNull();
        assertThat(acc.toBlock().input().size()).as("{} → 空对象 input").isZero();
    }

    @Test
    @DisplayName("fix-toolcalls-400 A-2: arguments=\"\" + finish_reason=tool_calls → 流结束补发回调恰好 1 次")
    void parseChunk_emptyArgs_finishReason_firesCallbackOnce() throws Exception {
        // WHY: arguments:"" 时 readTree("") 抛异常 → isComplete() 恒 false → A-1 不触发。只有
        //   finish_reason 才可断定无后续参数块，此时补发（对齐 CC 无参 tool_use 照常进执行器）。
        //   completedToolIds.add 守卫保证不双发。
        ChatCompletionChunk chunk = toolCallChunk("chatcmpl-empty", 0L, "call_empty",
            "function", "Bash", "", ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS);
        OpenAiStreamState state = new OpenAiStreamState();
        List<ToolUseBlock> fired = new ArrayList<>();
        new OpenAiSdkProvider().parseChunk(chunk, state, null, fired::add, null,
            ConcurrentHashMap.newKeySet());
        assertThat(fired).as("流结束必须补发 1 次空参 tool_call").hasSize(1);
        assertThat(fired.get(0).id()).isEqualTo("call_empty");
        assertThat(fired.get(0).name()).isEqualTo("Bash");
        assertThat(fired.get(0).input()).as("空参数 → 空对象 input").isNotNull();
        assertThat(fired.get(0).input().size()).isZero();
    }

    @Test
    @DisplayName("fix-toolcalls-400 A-2 反变异: arguments=\"\" 且无 finish_reason → 不提前回调（防带参工具 chunk1 被空参回调）")
    void parseChunk_emptyArgs_noFinish_doesNotFire() throws Exception {
        // WHY: with-args 工具的 chunk1 就是 arguments:""（后续块才给参数）。若无 finish_reason 也补发，
        //   会把带参工具提前回调成空参（completedToolIds 守卫后永不补发完整参数）→ 工具收到空 arguments。
        //   SDK Choice.Builder 要求 finish_reason 必填 → 无 finish_reason 场景用 JSON chunk 构造。
        ChatCompletionChunk chunk = chunk(
            "{\"id\":\"chatcmpl-pre\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"deepseek-chat\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_pre\",\"type\":\"function\","
                + "\"function\":{\"name\":\"Bash\",\"arguments\":\"\"}}"
                + "]},\"finish_reason\":null}]}");
        OpenAiStreamState state = new OpenAiStreamState();
        List<ToolUseBlock> fired = new ArrayList<>();
        new OpenAiSdkProvider().parseChunk(chunk, state, null, fired::add, null,
            ConcurrentHashMap.newKeySet());
        assertThat(fired).as("无 finish_reason 不得补发（A-2 变异点）").isEmpty();
    }

    @Test
    @DisplayName("无 tool_calls 的普通文本 chunk → state.toolCalls 保持空（不误填）")
    void parseChunk_noToolCalls_keepsEmpty() throws Exception {
        // GIVEN: 纯文本 chunk（无 tool_calls 字段）
        ChatCompletionChunk textChunk = chunk(
            "{\"id\":\"chatcmpl-t4\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"deepseek-chat\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                + "\"finish_reason\":null}]}");

        OpenAiStreamState state = new OpenAiStreamState();
        new OpenAiSdkProvider().parseChunk(textChunk, state, null, null, null,
            ConcurrentHashMap.newKeySet());

        assertThat(state.toolCalls).as("无 tool_calls 时不得误填").isEmpty();
    }
}
