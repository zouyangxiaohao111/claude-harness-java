package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;

/**
 * 测试专用假知识库工具 · 仅用于验证 {@code SessionToolCategory.of} 的
 * 「实现类在 {@code com.nexusai.kb.} 包下 → KB」这条判据。
 *
 * <p><b>为什么必须在 {@code com.nexusai.kb} 包下</b>：分类判据是按<b>实现类全限定名</b>前缀
 * 判定的，匿名类/内部类的包名恒等于测试类所在包 ⇒ 无法在 {@code com.nexusai.xxx} 的测试类里
 * 造出一个「属于 kb 包」的假工具。故单独落一个测试源文件在该包下。
 *
 * <p>⛔ 本类只存在于 {@code src/test/java}，不进生产包；不实现任何真实知识库行为。
 */
public class FakeKbToolForCategoryTest implements Tool {

    private final String name;

    public FakeKbToolForCategoryTest(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "test-only fake kb tool";
    }

    @Override
    public JsonNode inputSchema() {
        return null;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        throw new UnsupportedOperationException("test-only fake kb tool never executes");
    }
}
