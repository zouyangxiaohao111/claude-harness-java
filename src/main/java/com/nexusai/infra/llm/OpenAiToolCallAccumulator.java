package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolUseBlock;

/**
 * 单个 tool_call 的累积上下文。
 *
 * <p>OpenAI 协议中 tool_call 是分块到达的：
 * 第一块给 id/type/name，后续块只给 arguments 的部分（JSON 字符串按字符拼）。
 *
 * <p>由 {@link OpenAiSdkProvider} 共享。
 */
public class OpenAiToolCallAccumulator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public int index;
    public String id;      // 第一块设定
    public String type;    // 通常 "function"
    public String name;    // 第一块设定
    public String args = "";   // 跨块拼字符串

    public ToolUseBlock toBlock() {
        JsonNode input;
        try {
            input = (args == null || args.isEmpty())
                ? JSON.createObjectNode()
                : JSON.readTree(args);
        } catch (Exception e) {
            // arguments 不是合法 JSON（罕见）→ 用空对象 + 原始字符串
            ObjectNode wrapper = JSON.createObjectNode();
            wrapper.put("_raw", args == null ? "" : args);
            input = wrapper;
        }
        return new ToolUseBlock(id, name, input);
    }

    /**
     * 等 args JSON 能 parse 成 object 即返回 true（含空对象 {@code {}}）。
     *
     * <p>对齐 AnthropicSdkProvider:2813 宽松语义（id+name 非空即 complete）+ CC 无"参数非空"要求：
     * 模型产出无参工具调用时 arguments 为 "{}"（或 ""），是合法完整调用 → onToolCallComplete 必须
     * 触发，否则空参工具永不进执行器（OpenAI 400 "insufficient tool messages following tool_calls"
     * 根因 1.1）。原实现 {@code parsed.size() > 0} 把空参工具恒判为不完整，卡死整个混合批。
     *
     * <p>注意：arguments="" 时 readTree("") 抛异常 → 仍返回 false。这是有意的——流式 with-args 工具
     * 的 chunk1 就是 arguments:""，不能把 "" 直接视为完整（会提前把带参工具回调成空参）；该场景由
     * OpenAiSdkProvider.parseChunk 的 finish_reason 流结束补发处理（fix-toolcalls-400 A-2）。
     */
    public boolean isComplete() {
        if (id == null || id.isEmpty() || name == null || name.isEmpty() || args == null) {
            return false;
        }
        try {
            JsonNode parsed = JSON.readTree(args);
            return parsed.isObject();
        } catch (Exception e) {
            return false;
        }
    }
}
