package com.nexusai.application.agent;

import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具注入生产验证（前后端联调修复）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: LlmAgentLoop 主循环 base TUC 的 availableTools
 * 源自 buildBaseToolUseContext 的 {@code toolRegistry.all()}。Spring prototype 走构造器 1（无
 * {@code @Autowired} 构造器）+ 字段无 {@code @Autowired} → toolRegistry 恒 null → baseTools=空 →
 * 模型请求无工具 → DeepSeek 无工具可调，需工具的任务（读 md 文档等）输出垃圾
 * （{@code <nores>} 占位 + 思考片段）。本测试锁定「Spring 创建的 LlmAgentLoop 必须注入非空
 * ToolRegistry」，防回归。
 */
@SpringBootTest(classes = com.nexusai.NexusAiApplication.class)
@DisplayName("[联调修复] Spring 创建 LlmAgentLoop 注入 ToolRegistry")
class LlmAgentLoopToolRegistryInjectionTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("prototype LlmAgentLoop.toolRegistry 非空且含工具 → 模型请求带工具定义")
    void springLoopHasToolRegistry() throws Exception {
        // GIVEN: Spring prototype 每次 getObject() 全新实例 + 字段 @Autowired 注入
        LlmAgentLoop loop = ctx.getBean(LlmAgentLoop.class);

        // WHEN/THEN: toolRegistry 必须注入（修复前恒 null）
        Field f = LlmAgentLoop.class.getDeclaredField("toolRegistry");
        f.setAccessible(true);
        ToolRegistry tr = (ToolRegistry) f.get(loop);
        assertThat(tr)
            .as("Spring prototype 必须注入 ToolRegistry（否则模型请求无工具，复杂任务输出垃圾）")
            .isNotNull();
        assertThat(tr.all())
            .as("注入的 ToolRegistry 必须含工具（主循环 base TUC availableTools 非空）")
            .isNotEmpty();
    }
}
