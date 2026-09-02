package com.nexusai.application.agent.tool.impl;

import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.AgentToolSection;
import com.nexusai.application.agent.subagent.ForkSubagentAgentDefinition;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-SUB] fork fallback（SubagentTool.doExecute :1615-1625）派生 forkParentSystemPrompt 的源验证。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: fork path 的 {@code forkParentSystemPrompt}
 * 透传给子代理（CC runAgent.ts:508-509 override.systemPrompt 语义）—— 父 cache 字节优先（prompt
 * cache prefix byte-identical）；父字节缺失时 fallback 到 {@code getEffectiveSystemPrompt}
 * （SubagentTool.java:3188-3220），最终落到 {@link AgentToolSection#get()}（CC getAgentToolSection
 * 非 fork 变体），而非残留旧文本。
 *
 * <p><b>可观测 seam</b>: doExecute fork path 内部经 executeAsync 日志
 * {@code forkParams.forkParentSystemPrompt.length=N}（SubagentTool.java:2547-2552，grep 验证）
 * 暴露派生结果。下游 llmProviderFactory=null 抛异常属预期，断言只依赖该日志（catch Throwable 后校验）。
 *
 * <p>[R2-ForkFallback] 反射 5 参签名: doExecute 现为 5 参
 * {@code (ToolUseBlock, ToolUseContext, Consumer<Tool.ToolProgress> onProgress,
 * AgentOptions, ForkSubagentMessages.Message)}（SubagentTool.java:1487-1490，grep 验证）。
 * onProgress 第 3 参对齐 CC AgentTool.tsx:250 tool 函数第 5 参
 * {@code onProgress?}（IMP-SUB-28 A5 接线）；测试传 null（非流式语义）。
 *
 * <p><b>预期长度</b>: fork 子代理 {@code ForkSubagentAgentDefinition.getSystemPrompt()=""}
 * （CC forkSubagent.ts:70 明确 unused，对齐 A6）→ fallback 长度 = 0 + 2 + AgentToolSection 长度。
 */
@DisplayName("[IMP-SP-SUB] fork fallback 派生 forkParentSystemPrompt: 父字节优先, 否则 AgentToolSection")
class SubagentToolForkFallbackSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Logger subagentLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureDebugLogs() {
        subagentLogger = (Logger) LoggerFactory.getLogger(SubagentTool.class);
        subagentLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        subagentLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        subagentLogger.detachAppender(appender);
    }

    /** minimal tool_use block · subagent_type 缺省（undefined）→ fork path（CC :322）。 */
    private static ToolUseBlock forkCall() {
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Fork child task");
        input.put("prompt", "Subagent prompt for fork child");
        return new ToolUseBlock("tool-fork-test", "Agent", input);
    }

    private static void invokeDoExecute(SubagentTool tool, ToolUseBlock call, ToolUseContext ctx) {
        try {
            // [R2-ForkFallback] 5 参签名: (ToolUseBlock, ToolUseContext,
            //   Consumer<Tool.ToolProgress> onProgress, AgentOptions, ForkSubagentMessages.Message)
            //   SubagentTool.java:1487-1490 (grep 验证) · onProgress 对齐 CC AgentTool.tsx:250
            Method m = SubagentTool.class.getDeclaredMethod("doExecute", ToolUseBlock.class,
                ToolUseContext.class,
                java.util.function.Consumer.class,
                Class.forName("com.nexusai.application.agent.subagent.createSubagentContext$AgentOptions"),
                Class.forName("com.nexusai.application.agent.subagent.ForkSubagentMessages$Message"));
            m.setAccessible(true);
            try {
                m.invoke(tool, call, ctx, null, null, null);
            } catch (Throwable ignored) {
                // 下游 (llmProviderFactory=null) 抛异常属预期 — 断言只依赖 executeAsync 派生日志
            }
        } catch (Exception e) {
            throw new AssertionError("doExecute 反射调用失败", e);
        }
    }

    @Test
    @DisplayName("ctx.renderedSystemPrompt 缺失 → fallback = getEffectiveSystemPrompt (AgentToolSection 新源)")
    void forkFallback_noParentBytes_usesAgentToolSection() {
        // GIVEN: 默认 SubagentTool（fork gate on）+ 无父字节 (ctx=null)
        SubagentTool tool = new SubagentTool();

        // WHEN: 触发 fork path
        invokeDoExecute(tool, forkCall(), null);

        // THEN: forkParentSystemPrompt = "" + "\n\n" + AgentToolSection.get()
        int expected = ForkSubagentAgentDefinition.create().getSystemPrompt(null, List.of()).length()
            + 2 + AgentToolSection.get().length();
        assertThat(appender.list)
            .as("ctx=null → forkParentSystemPrompt 必须 fallback 到 getEffectiveSystemPrompt(AgentToolSection)")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("executeAsync S3 透传")
                .contains("forkParentSystemPrompt.length=" + expected));
    }

    @Test
    @DisplayName("ctx.renderedSystemPrompt 非空 → 复用父字节（CC runAgent.ts:508-509）")
    void forkFallback_parentBytesPresent_keepsRenderedSystemPrompt() {
        // GIVEN: ctx 携带父 cache 字节（13 参兼容构造器；sessionId/mode 为必填校验）
        SubagentTool tool = new SubagentTool();
        ToolUseContext ctx = new ToolUseContext(
            null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            null, List.of(), null, null, List.of(),
            null, null, null, false, "parent-cache-bytes");

        // WHEN: 触发 fork path
        invokeDoExecute(tool, forkCall(), ctx);

        // THEN: forkParentSystemPrompt 长度 == 父字节长度（不落 AgentToolSection fallback）
        assertThat(appender.list)
            .as("ctx.renderedSystemPrompt 非空 → forkParentSystemPrompt 必须复用父字节")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("executeAsync S3 透传")
                .contains("forkParentSystemPrompt.length=" + "parent-cache-bytes".length()));
    }

    @Test
    @DisplayName("fork fallback: 父 AgentState 可得 → rebuild 父完整有效提示（custom+append，非 AgentToolSection）")
    void forkFallback_stateAvailable_rebuildsParentEffectiveSystemPrompt() {
        // WHY (IMP-PA-FORK-03 · CC AgentTool.tsx:499-511): renderedSystemPrompt 不可得（罕见）时 CC
        //   用 getSystemPrompt + buildEffectiveSystemPrompt 重建「父完整有效 system prompt」——
        //   custom 非空替换 default、append 恒末尾（systemPrompt.ts:118-121）。旧实现错误产出
        //   selectedAgent.getSystemPrompt() + "\n\n" + AgentToolSection.get()（FORK-12 △）。
        // GIVEN: 父会话 AgentState（custom="custom-parent" + append="append-parent" + currentModel）
        SubagentTool tool = new SubagentTool();
        SessionAgentStateRegistry reg = new SessionAgentStateRegistry();
        AgentState state = new AgentState("custom-parent", "sess-fork03", null, "append-parent");
        state.setCurrentModel("test-model");
        reg.register("sess-fork03", state);
        tool.setSessionAgentStateRegistry(reg);
        //   + 父 ctx（renderedSystemPrompt 空 → 触发 fallback；sessionId 指向已注册 state）
        ToolUseContext ctx = new ToolUseContext(
            null, "sess-fork03", PermissionMode.DEFAULT,
            null, List.of(), null, null, List.of(),
            null, null, null, false, "");

        // WHEN: 触发 fork path（ctx 非 null + rendered 空 → fallback 重建）
        invokeDoExecute(tool, forkCall(), ctx);

        // THEN: forkParentSystemPrompt = "custom-parent" + "\n\n" + "append-parent"
        //   （EffectiveSystemPromptBuilder 组装；custom 替换 default、append 恒末尾）
        int expected = "custom-parent".length() + 2 + "append-parent".length();
        assertThat(appender.list)
            .as("fork fallback 必须重建父完整有效提示（custom+append），而非 selectedAgent+AgentToolSection")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("executeAsync S3 透传")
                .contains("forkParentSystemPrompt.length=" + expected));
    }

    @Test
    @DisplayName("rebuild: 父 custom 空 → default 组装 + append 恒末尾（生产代表性路径）")
    void rebuild_defaultAssembly_appendsAppendAtEnd() throws Exception {
        // WHY (IMP-PA-FORK-03): fallback 触发条件 = renderedSystemPrompt 空 = 父无 custom 提示
        //   （base TUC renderedSystemPrompt 承载 state.systemPrompt()），故生产代表性路径是
        //   default 组装 + append（CC buildEffectiveSystemPrompt :115-122）。断言 default 组装
        //   已运行（intro section "You are an interactive agent"，ResumeServiceTest:295 同款）
        //   + append 恒末尾（systemPrompt.ts:121）。
        SubagentTool tool = new SubagentTool();
        AgentState state = new AgentState(null, "sess-fork03b", null, "append-parent");
        state.setCurrentModel("test-model");
        ToolUseContext ctx = new ToolUseContext(
            null, "sess-fork03b", PermissionMode.DEFAULT,
            null, List.of(), null, null, List.of(),
            null, null, null, false, "");
        Method m = SubagentTool.class.getDeclaredMethod("rebuildForkParentSystemPrompt",
            AgentState.class, ToolUseContext.class);
        m.setAccessible(true);
        String rebuilt = (String) m.invoke(tool, state, ctx);
        assertThat(rebuilt)
            .as("父 custom 空 → 必须 default 组装 + append 恒末尾（CC buildEffectiveSystemPrompt）")
            .isNotBlank()
            .contains("You are an interactive agent")   // default 组装已运行
            .endsWith("\n\nappend-parent");             // append 恒末尾
    }

    @Test
    @DisplayName("fork fallback: 父 AgentState 不可得 → 回落旧 getEffectiveSystemPrompt（现行为保持）")
    void forkFallback_stateUnavailable_fallsBackToOldPath() {
        // WHY (IMP-PA-FORK-03): 父状态不可得（registry 未注入/会话无状态，罕见/测试）时不得伪造
        //   父提示 —— 保持旧路径（selectedAgent + AgentToolSection），零行为变化。
        SubagentTool tool = new SubagentTool();
        // 注入空 registry（有 bean 但会话无状态）→ state=null → 回落旧路径
        tool.setSessionAgentStateRegistry(new SessionAgentStateRegistry());
        ToolUseContext ctx = new ToolUseContext(
            null, "sess-fork03c", PermissionMode.DEFAULT,
            null, List.of(), null, null, List.of(),
            null, null, null, false, "");

        invokeDoExecute(tool, forkCall(), ctx);

        // THEN: forkParentSystemPrompt = FORK_AGENT.getSystemPrompt(0) + "\n\n" + AgentToolSection.get()
        int expected = ForkSubagentAgentDefinition.create().getSystemPrompt(null, List.of()).length()
            + 2 + AgentToolSection.get().length();
        assertThat(appender.list)
            .as("会话无 AgentState → forkParentSystemPrompt 必须回落旧 getEffectiveSystemPrompt")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("executeAsync S3 透传")
                .contains("forkParentSystemPrompt.length=" + expected));
    }
}
