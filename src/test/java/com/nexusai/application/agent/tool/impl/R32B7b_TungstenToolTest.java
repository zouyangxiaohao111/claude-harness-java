package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7b · TungstenTool stub 行为验证.
 *
 * <p><b>WHY (意图验证)</b>: TungstenTool 与 b7a-3 的 MCP stub 是"同 archetype" — 都是
 * 显式未实现的占位工具，但触发场景不同:
 * <ul>
 *   <li>WebBrowser 等 MCP stub — feature flag 控制, 错误消息引用 MCP server 配置.</li>
 *   <li>TungstenTool — {@code nexusai.user.type=ant} 触发, 错误消息是
 *       {@code "feature_not_implemented"} (中文 warn 日志更详细). 这是 fail-loud 契约,
 *       防止"silent success" — 万一 ant 用户误用该 tool 必须立即看到错误.</li>
 * </ul>
 *
 * <p>关键 invariant (CLAUDE.md 规则 9 · 测试验证意图):
 * <ul>
 *   <li>name() 必须 = {@code "TungstenTool"} (对齐 CC AntConfigTool/TungstenTool 命名,
 *       Java 端 {@code ToolNameConstants.TUNGSTEN_TOOL_NAME}).</li>
 *   <li>isEnabled() 永远 false — 即使 ant 注册, LLM 也不可见.</li>
 *   <li>execute() 必须返回 error (不抛异常), 与 b7a-3 stub 一致 — 不挂整个 turn.</li>
 *   <li>warn 日志必须包含中文业务消息 (CLAUDE.md · 中文日志) 和工具名前缀.</li>
 * </ul>
 *
 * <p>本测试类不验证 @ConditionalOnProperty 行为 — 由 {@link R32B7b_TungstenToolConditionalTest}
 * 用 {@link org.springframework.boot.test.context.runner.ApplicationContextRunner} 验证.
 *
 * @see TungstenTool
 */
class R32B7b_TungstenToolTest {

    private final TungstenTool tool = new TungstenTool();

    private Logger logger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachAppender() {
        // WHY: 用 ListAppender 拦截 slf4j 日志而非 stdout — 测试不依赖 logback 配置,
        // 保证断言稳定 (其他 logger 配置变更不会污染本测试).
        logger = (Logger) LoggerFactory.getLogger(TungstenTool.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void detachAppender() {
        // WHY: 必须清理 — 否则 ListAppender 累积事件, 影响后续测试, 也会内存泄漏
        if (logger != null && listAppender != null) {
            logger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Test
    @DisplayName("name() 返回 'TungstenTool' (对齐 CC AntConfigTool/TungstenTool 命名)")
    void nameAlignsWithCc() {
        // WHY: stub 的 name() 必须严格 = ToolNameConstants 常量 — ToolRegistry 按 name
        // 查表分发, 命名不一致 = LLM 调不到该 stub. 这是 fail-loud 契约.
        assertThat(tool.name())
            .as("TungstenTool stub name 必须 = ToolNameConstants.TUNGSTEN_TOOL_NAME")
            .isEqualTo(ToolNameConstants.TUNGSTEN_TOOL_NAME)
            .isEqualTo("TungstenTool");
    }

    @Test
    @DisplayName("description() 非空 + 提及 tungsten / ant")
    void descriptionMentionsAnt() {
        // WHY: description 是 LLM 看到工具时的第一信号; 应明确"ant-only, not implemented",
        // 防止 LLM 误以为可调用
        String desc = tool.description();
        assertThat(desc)
            .as("description 必须非空且提及 tungsten / ant")
            .isNotBlank()
            .containsIgnoringCase("tungsten")
            .containsIgnoringCase("ant");
    }

    @Test
    @DisplayName("inputSchema() 返回合法 object schema (additionalProperties=true)")
    void inputSchemaIsValid() {
        // WHY: LLM 根据 schema 生成 JSON input. stub 不知道真实 Tungsten terminal schema,
        // 用 {"type":"object","additionalProperties":true} 通用 schema 兜底.
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.isObject()).isTrue();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("isEnabled() 永远 false (ant 用户也不可见)")
    void isEnabledAlwaysFalse() {
        // WHY: stub 模式 LLM 不可见; 即使 ant=true 注册了 bean, LLM 也不应看到 Tungsten.
        // 若 isEnabled()=true, ToolRegistry 会把它注册到 LLM 工具列表, 调一次失败.
        assertThat(tool.isEnabled())
            .as("stub.isEnabled() 必须永远 false; 真实能力尚未实现")
            .isFalse();
    }

    @Test
    @DisplayName("execute(ToolUseBlock) → ToolResult.error 不抛异常 (双参委托)")
    void executeSingleArgDoesNotThrow() {
        // WHY: Tool 契约 "错误不抛" — stub.execute 必须返回 ToolResult.error 而非
        // 抛 RuntimeException, 避免 LlmAgentLoop 整个 turn 挂掉
        ToolUseBlock call = new ToolUseBlock("call-tungsten-1", "TungstenTool",
            JsonNodeFactory.instance.objectNode());
        AgentToolResult result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult tr = (ToolResult) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("stub 永远返回 error (未实现错误)")
            .isTrue();
    }

    @Test
    @DisplayName("execute(call, ctx) 双参重载 → error content = 'feature_not_implemented'")
    void executeTwoArgErrorContent() {
        // WHY: error content 是 fail-loud 契约的核心 — LLM 收到 "feature_not_implemented"
        // 才能知道这是未实现 tool 而非其他错误. 锁定此字符串以防止未来误改.
        ToolUseBlock call = new ToolUseBlock("call-tungsten-2", "TungstenTool",
            JsonNodeFactory.instance.objectNode());
        ToolResult<String> result = (ToolResult<String>) tool.execute(call, null);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(result.data())
            .as("TungstenTool error content 必须 = 'feature_not_implemented'")
            .isEqualTo("feature_not_implemented");
    }

    @Test
    @DisplayName("execute(call, ctx) → 中文 warn 日志 + 工具名前缀")
    void executeEmitsChineseWarnLog() {
        // WHY: CLAUDE.md 要求中文日志 — 这是运维/用户唯一反馈通道.
        // 日志必须包含工具名 (TungstenTool) 和中文业务消息 (ant 未启用/调用提示),
        // 便于 grep + 排障.
        ToolUseBlock call = new ToolUseBlock("call-tungsten-3", "TungstenTool",
            JsonNodeFactory.instance.objectNode());
        listAppender.list.clear();
        tool.execute(call, null);

        // 至少有 1 条 WARN
        assertThat(listAppender.list)
            .as("execute() 必须 emit 至少 1 条 warn 日志")
            .isNotEmpty()
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                String formatted = event.getFormattedMessage();
                assertThat(formatted)
                    .as("warn 日志必须包含工具名前缀 'TungstenTool'")
                    .contains("TungstenTool");
            });
    }

    @Test
    @DisplayName("ToolUseContext=null 不应 NPE (ctx 可选)")
    void executeAcceptsNullContext() {
        // WHY: 老调用方可能传 null ctx; stub 不依赖 ctx, 必须容忍
        ToolUseBlock call = new ToolUseBlock("call-tungsten-4", "TungstenTool",
            JsonNodeFactory.instance.objectNode());
        AgentToolResult result = tool.execute(call, null);
        assertThat(result).isNotNull();
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
    }

    @Test
    @DisplayName("name() 暴露为 public static final NAME 常量 (供其他类引用)")
    void nameConstantExposed() {
        // WHY: 未来 ToolRegistry / McpToolPool 等组件可能引用 stub.NAME 做匹配;
        // NAME 必须 public static final 防止误改
        assertThat(TungstenTool.NAME)
            .as("TungstenTool.NAME 必须 public static final, 值 = name()")
            .isEqualTo(tool.name())
            .isEqualTo("TungstenTool");
    }
}