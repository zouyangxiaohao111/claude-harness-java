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
 * R32-b7b · SuggestBackgroundPRTool stub 行为验证.
 *
 * <p><b>WHY (意图验证)</b>: SuggestBackgroundPRTool 与 TungstenTool 是"同 archetype"
 * (都是 fail-loud stub), 但触发场景不同:
 * <ul>
 *   <li>TungstenTool — {@code nexusai.user.type=ant} 触发, 仅 ant 用户注册.</li>
 *   <li>SuggestBackgroundPRTool — {@code nexusai.user.type=ant} 触发, 仅 ant 用户注册
 *       (CC tools.ts:20-23 require 条件 {@code USER_TYPE === 'ant'} + :216 注册三元;
 *       无独立 feature 门控).</li>
 * </ul>
 *
 * <p>关键 invariant (CLAUDE.md 规则 9):
 * <ul>
 *   <li>name() 必须 = {@code "SuggestBackgroundPR"} (对齐 CC SuggestBackgroundPRTool).</li>
 *   <li>isEnabled() 永远 false — 即使 ant 注册开启, LLM 也不可见 (真实能力未实现,
 *       CC 行为源码缺失; 对齐 CC tools.ts:325-326 isEnabled 双闸进 schema).</li>
 *   <li>execute() 返回 error (不抛异常), 与 b7a-3 stub 一致.</li>
 *   <li>warn 日志必须包含中文业务消息 (CLAUDE.md · 中文日志).</li>
 * </ul>
 *
 * @see SuggestBackgroundPRTool
 */
class R32B7b_SuggestBackgroundPRToolTest {

    private final SuggestBackgroundPRTool tool = new SuggestBackgroundPRTool();

    private Logger logger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(SuggestBackgroundPRTool.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void detachAppender() {
        if (logger != null && listAppender != null) {
            logger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Test
    @DisplayName("name() 返回 'SuggestBackgroundPR' (对齐 CC SuggestBackgroundPRTool 命名)")
    void nameAlignsWithCc() {
        // WHY: stub 的 name() 必须严格 = ToolNameConstants 常量 — ToolRegistry 按 name
        // 查表分发, 命名不一致 = LLM 调不到该 stub
        assertThat(tool.name())
            .as("SuggestBackgroundPRTool stub name 必须 = ToolNameConstants.SUGGEST_BACKGROUND_PR_TOOL_NAME")
            .isEqualTo(ToolNameConstants.SUGGEST_BACKGROUND_PR_TOOL_NAME)
            .isEqualTo("SuggestBackgroundPR");
    }

    @Test
    @DisplayName("description() 非空 + 提及 background pull request / suggest / not implemented")
    void descriptionMentionsSuggest() {
        // WHY: description 是 LLM 看到工具时的第一信号; 应明确"建议后台 PR (未实现)",
        // 防止 LLM 误以为可调用. description 实际文本: "Suggest a background pull
        // request (not implemented)." 因此断言"pull request"字面量, 不写 "PR" (拼写不显式)
        String desc = tool.description();
        assertThat(desc)
            .as("description 必须非空且提及 background pull request 概念")
            .isNotBlank()
            .containsIgnoringCase("background")
            .containsIgnoringCase("pull request");
    }

    @Test
    @DisplayName("inputSchema() 返回合法 object schema (additionalProperties=true)")
    void inputSchemaIsValid() {
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.isObject()).isTrue();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("isEnabled() 永远 false (flag 开启也不可见)")
    void isEnabledAlwaysFalse() {
        // WHY: stub 模式 LLM 不可见; 即使 nexusai.user.type=ant 注册了 bean, LLM 也不应看到
        assertThat(tool.isEnabled())
            .as("stub.isEnabled() 必须永远 false; 真实能力尚未实现")
            .isFalse();
    }

    @Test
    @DisplayName("execute(ToolUseBlock) → ToolResult.error 不抛异常")
    void executeSingleArgDoesNotThrow() {
        ToolUseBlock call = new ToolUseBlock("call-sbpr-1", "SuggestBackgroundPR",
            JsonNodeFactory.instance.objectNode());
        AgentToolResult result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult tr = (ToolResult) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isTrue();
    }

    @Test
    @DisplayName("execute(call, ctx) 双参重载 → error content = 'feature_not_implemented'")
    void executeTwoArgErrorContent() {
        // WHY: error content 是 fail-loud 契约的核心 — LLM 收到 "feature_not_implemented"
        // 才能知道这是未实现 tool 而非其他错误
        ToolUseBlock call = new ToolUseBlock("call-sbpr-2", "SuggestBackgroundPR",
            JsonNodeFactory.instance.objectNode());
        ToolResult<String> result = (ToolResult<String>) tool.execute(call, null);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(result.data())
            .as("SuggestBackgroundPRTool error content 必须 = 'feature_not_implemented'")
            .isEqualTo("feature_not_implemented");
    }

    @Test
    @DisplayName("execute(call, ctx) → 中文 warn 日志 + 工具名前缀")
    void executeEmitsChineseWarnLog() {
        // WHY: CLAUDE.md 要求中文日志 — 这是运维/用户唯一反馈通道
        ToolUseBlock call = new ToolUseBlock("call-sbpr-3", "SuggestBackgroundPR",
            JsonNodeFactory.instance.objectNode());
        listAppender.list.clear();
        tool.execute(call, null);

        assertThat(listAppender.list)
            .as("execute() 必须 emit 至少 1 条 warn 日志")
            .isNotEmpty()
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                String formatted = event.getFormattedMessage();
                assertThat(formatted)
                    .as("warn 日志必须包含工具名前缀 'SuggestBackgroundPRTool'")
                    .contains("SuggestBackgroundPRTool");
            });
    }

    @Test
    @DisplayName("ToolUseContext=null 不应 NPE (ctx 可选)")
    void executeAcceptsNullContext() {
        ToolUseBlock call = new ToolUseBlock("call-sbpr-4", "SuggestBackgroundPR",
            JsonNodeFactory.instance.objectNode());
        AgentToolResult result = tool.execute(call, null);
        assertThat(result).isNotNull();
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
    }

    @Test
    @DisplayName("name() 暴露为 public static final NAME 常量 (供其他类引用)")
    void nameConstantExposed() {
        assertThat(SuggestBackgroundPRTool.NAME)
            .as("SuggestBackgroundPRTool.NAME 必须 public static final, 值 = name()")
            .isEqualTo(tool.name())
            .isEqualTo("SuggestBackgroundPR");
    }
}