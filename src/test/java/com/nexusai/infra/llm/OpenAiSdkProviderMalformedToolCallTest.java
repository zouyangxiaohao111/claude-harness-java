package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.openai.models.ChatCompletionChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [fix-toolcall-fault A] 幽灵 tool_call 槽（有 id、name 恒空、args 空）容错 · 对齐 CC 同形。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：上游 vLLM（openai 兼容端点）流式响应偶发
 * 幽灵 tool_call 槽 —— id 有值、{@code function.name} 恒 null、arguments 空。旧实现
 * {@code OpenAiToolCallAccumulator.toBlock()} 把空 name 原样交给 {@link ToolUseBlock} 构造器
 * （:21-23 {@code name.isBlank()} → IllegalArgumentException）→ buildAssistantMessage
 * （OpenAiSdkProvider:344 onAssistantMessage 回调内，外层 :359 catch）→ onError → LlmAgentLoop
 * 判不可重试 → STREAM_ERROR 退出 ⇒ <b>整个回合死</b>（子代理"没干成活"的根因）。
 *
 * <p><b>CC 同形依据</b>：CC 对 name 原样拷贝、不校验（claude.ts:1995-2000
 * {@code content_block_start} → {@code {...part.content_block, input:''}}）；坏 name 由工具层容错
 * —— StreamingToolExecutor.ts:77-102 / toolExecution.ts:369-410 查不到工具 → 产
 * {@code is_error} 的 tool_result {@code <tool_use_error>Error: No such tool available: {name}</tool_use_error>}
 * → <b>回合继续</b>（回喂模型自纠）。
 *
 * <p>本仓选型 = 哨兵名（而非原样放行空名）：见 toBlock() 内 javadoc —— 空名 tool_call 在
 * 回放侧（OpenAiSdkProvider:1030-1033）被丢弃 → 后续 tool 消息无配对 → OpenAI 400。
 *
 * <p>变异点：把 toBlock() 的哨兵映射去掉 → 本测试红（抛 IllegalArgumentException）。
 */
@DisplayName("[fix-toolcall-fault A] 幽灵 tool_call 槽（name 空）不再杀死回合")
class OpenAiSdkProviderMalformedToolCallTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 幽灵槽：id 有值、name 空、args 空（上游实测形态）。 */
    private static OpenAiToolCallAccumulator ghost(String id, String name, String args) {
        OpenAiToolCallAccumulator acc = new OpenAiToolCallAccumulator();
        acc.index = 0;
        acc.id = id;
        acc.type = "function";
        acc.name = name;
        acc.args = args;
        return acc;
    }

    @Test
    @DisplayName("A-1 toBlock: name=null 幽灵槽不再抛（哨兵名映射）· 旧实现 IllegalArgumentException 杀死回合")
    void toBlock_nullName_doesNotThrow_usesSentinel() {
        // WHY: buildAssistantMessage 对每个 accumulator 无条件 toBlock()；其中任意一个抛异常 →
        //   provider onError → STREAM_ERROR → 整回合死（用户报障"子代理没干成活"的直接根因）。
        OpenAiToolCallAccumulator acc = ghost("call_ghost", null, "");

        assertThatCode(acc::toBlock).as("空 name 不得抛异常（对齐 CC 原样放行/工具层容错）")
            .doesNotThrowAnyException();
        ToolUseBlock block = acc.toBlock();
        assertThat(block.id()).isEqualTo("call_ghost");
        assertThat(block.name())
            .as("空 name → 哨兵名（工具层据此产 No such tool available 的 error tool_result）")
            .isEqualTo(OpenAiToolCallAccumulator.MALFORMED_TOOL_CALL_NAME);
        assertThat(block.input()).as("空 args → 空对象 input（既有兜底）").isNotNull();
        assertThat(block.input().size()).isZero();
    }

    @Test
    @DisplayName("A-2 toBlock: name=\"\" 与 name=\"  \"（纯空白）同走哨兵分支（口径与 ToolUseBlock.isBlank 统一）")
    void toBlock_blankName_usesSentinel() {
        // WHY: ToolUseBlock 构造器判据是 isBlank()（空串 + 纯空白），accumulator 原判据是 isEmpty()
        //   —— 纯空白 name 会穿透到构造器抛异常（口径不一致的洞）。
        assertThat(ghost("call_e", "", "").toBlock().name())
            .isEqualTo(OpenAiToolCallAccumulator.MALFORMED_TOOL_CALL_NAME);
        assertThat(ghost("call_ws", "   ", "").toBlock().name())
            .isEqualTo(OpenAiToolCallAccumulator.MALFORMED_TOOL_CALL_NAME);
    }

    @Test
    @DisplayName("A-3 isComplete: 纯空白 name 判不完整（与 ToolUseBlock.isBlank 口径统一）")
    void isComplete_whitespaceName_false() {
        // WHY: 口径统一要求（isEmpty vs isBlank 对齐）。纯空白 name 不是合法调用身份 → 不得在
        //   流式过程中被当成"完整调用"回调进执行器（只有 buildAssistantMessage 收尾才映射哨兵）。
        assertThat(ghost("call_ws", "   ", "{}").isComplete())
            .as("空白 name → isComplete=false（原 isEmpty 判据恒 true）").isFalse();
        assertThat(ghost("call_ok", "Bash", "{}").isComplete())
            .as("对照组：正常 name + 空对象 args 仍 isComplete=true（不得误伤）").isTrue();
    }

    @Test
    @DisplayName("A-4 buildAssistantMessage: 含幽灵槽的流式响应产块成功（不再 STREAM_ERROR 退出）")
    void buildAssistantMessage_withGhostSlot_keepsBlock() {
        // WHY: 幽灵槽必须进 assistant message（不能静默丢弃）—— 后续工具层据此产 error tool_result
        //   并回喂模型；丢弃会让 [assistant(tool_use)] 与 [tool(tool_result)] 失配（OpenAI 400）。
        OpenAiStreamState state = new OpenAiStreamState();
        state.toolCalls.put(0, ghost("call_ghost", null, ""));
        state.toolCalls.put(1, ghost("call_real", "Bash", "{\"command\":\"ls\"}"));
        state.finishReason = "tool_calls";

        assertThatCode(() -> new OpenAiSdkProvider().buildAssistantMessage(state))
            .as("含幽灵槽的流式响应不得抛（旧实现 IllegalArgumentException → onError → 回合死）")
            .doesNotThrowAnyException();

        List<ToolUseBlock> blocks = new OpenAiSdkProvider().buildAssistantMessage(state).toolCalls();
        assertThat(blocks).as("幽灵槽 + 正常槽都必须保留").hasSize(2);
        assertThat(blocks.get(0).name()).isEqualTo(OpenAiToolCallAccumulator.MALFORMED_TOOL_CALL_NAME);
        assertThat(blocks.get(1).name()).isEqualTo("Bash");
    }

    @Test
    @DisplayName("A-5 parseChunk 端到端: vLLM 幽灵槽 chunk（name:null）→ 流不中断 + 收尾产出哨兵块")
    void parseChunk_ghostSlotFromWire_streamSurvives() throws Exception {
        // WHY: 上游实测形态（http://192.168.20.118:8000/v1 · qwen3.8-27b-fp8）——id 有、name 恒 null、
        //   args 空。此路径旧实现：parseChunk 不抛（isComplete 恒 false 不回调）→ 收尾
        //   buildAssistantMessage 抛 → STREAM_ERROR → 整回合死。
        ChatCompletionChunk ghostChunk = com.openai.core.ObjectMappers.jsonMapper().convertValue(
            JSON.readTree("{\"id\":\"chatcmpl-ghost\",\"object\":\"chat.completion.chunk\","
                + "\"created\":0,\"model\":\"qwen3\",\"choices\":[{\"index\":0,\"delta\":"
                + "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_ghost_wire\","
                + "\"type\":\"function\",\"function\":{\"name\":null,\"arguments\":\"\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}"),
            ChatCompletionChunk.class);

        OpenAiStreamState state = new OpenAiStreamState();
        assertThatCode(() -> new OpenAiSdkProvider().parseChunk(ghostChunk, state, null, null, null,
            ConcurrentHashMap.newKeySet())).doesNotThrowAnyException();

        assertThat(state.toolCalls).as("幽灵槽必须被累积（供收尾映射哨兵名）").hasSize(1);
        assertThat(state.toolCalls.get(0).id).isEqualTo("call_ghost_wire");

        List<ToolUseBlock> blocks = new OpenAiSdkProvider().buildAssistantMessage(state).toolCalls();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).name())
            .as("收尾必须产出哨兵名块（工具层 → No such tool available error tool_result → 回合继续）")
            .isEqualTo(OpenAiToolCallAccumulator.MALFORMED_TOOL_CALL_NAME);
    }

    @Test
    @DisplayName("A-6 正名赋值防御: 后续 chunk 的空 name 不得抹掉已到的真名（防上游分块异常造幽灵槽）")
    void parseChunk_laterBlankNameChunk_doesNotEraseRealName() throws Exception {
        // WHY: OpenAI 流式协议 name 只在首块给一次；若上游后续块重复发 name:""（vLLM 偶发），旧实现
        //   ifPresent 直接覆盖 → 真名被抹成空 → 幽灵槽（本缺陷的另一条成因路径）。仅非空才覆盖。
        ChatCompletionChunk first = com.openai.core.ObjectMappers.jsonMapper().convertValue(
            JSON.readTree("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                + "\"model\":\"qwen3\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_x\",\"type\":\"function\","
                + "\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]},"
                + "\"finish_reason\":null}]}"),
            ChatCompletionChunk.class);
        ChatCompletionChunk second = com.openai.core.ObjectMappers.jsonMapper().convertValue(
            JSON.readTree("{\"id\":\"c2\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                + "\"model\":\"qwen3\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"\"}}]},"
                + "\"finish_reason\":null}]}"),
            ChatCompletionChunk.class);

        OpenAiStreamState state = new OpenAiStreamState();
        OpenAiSdkProvider provider = new OpenAiSdkProvider();
        provider.parseChunk(first, state, null, null, null, ConcurrentHashMap.newKeySet());
        provider.parseChunk(second, state, null, null, null, ConcurrentHashMap.newKeySet());

        assertThat(state.toolCalls.get(0).name)
            .as("空 name 块不得抹掉真名（仅非空才覆盖）").isEqualTo("Bash");
        assertThat(state.toolCalls.get(0).isComplete())
            .as("真名保留 → 该调用仍是完整可执行调用").isTrue();
    }
}
