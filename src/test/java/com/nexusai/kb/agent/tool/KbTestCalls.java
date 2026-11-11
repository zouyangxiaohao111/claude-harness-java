package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolUseBlock;

/**
 * 测试辅助：从 JSON 字符串造 {@link ToolUseBlock}。
 *
 * <p>抽出来避免每个测试类重复 5 行样板；解析失败直接抛（测试应当立刻失败，
 * 而不是拿到一个空 input 继续跑出误导性的绿）。
 */
final class KbTestCalls {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KbTestCalls() {
    }

    /**
     * @param json 工具入参 JSON 文本
     * @return 可用于 {@code tool.execute(...)} 的调用块
     */
    static ToolUseBlock call(String json) {
        try {
            JsonNode input = MAPPER.readTree(json);
            return new ToolUseBlock("toolu_test", "test_tool", input);
        } catch (Exception e) {
            throw new IllegalArgumentException("测试用 JSON 解析失败：" + json, e);
        }
    }
}
