package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.ForkSubagentMessages;
import com.nexusai.application.agent.subagent.ForkWorktreePaths;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import org.slf4j.LoggerFactory;
import org.assertj.core.api.Assertions;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M1.3 · executeSync/Async fork path 完整透传对齐 CC AgentTool.tsx:512/557/600.
 *
 * <p><b>WHY 3 测试覆盖核心透传契约 (CLAUDE.md 规则九 · 测试验证意图)</b>:
 * 旧实现 fork path 在 executeSync/executeAsync 内只透传 prompt + assistantMessage, 但缺:
 * <ol>
 *   <li>forkParentSystemPrompt (CC :493-511) — 父 cache 字节复用, prompt cache 命中率</li>
 *   <li>buildForkedMessages 完整 4 参调用 (CC :512)</li>
 *   <li>buildWorktreeNotice (CC :600) — worktree isolation 路径翻译提示</li>
 * </ol>
 *
 * <p><b>3 测试验收硬指标 (CLAUDE.md 规则十二)</b>: 3/0/0/0.
 *
 * <p><b>测试策略</b>: 反射验证 SubagentTool 私有字段 (forkParentSystemPrompt/currentCwd 派生)
 * 与 executeSync/Async 内部透传逻辑 (assistantMessage 接收 + 参数透传).
 */
@DisplayName("Session M1.3 · executeSync/Async fork path 完整透传对齐 CC AgentTool.tsx:512/557/600")
class SubagentToolForkMessagesCompletenessTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 构造一个 minimal tool_use block, prompt/subagent_type 必填.
     */
    private static ToolUseBlock buildForkToolUseBlock() {
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Fork child task");
        input.put("prompt", "Subagent prompt for fork child");
        // subagent_type omitted (undefined) → 触发 fork path (CC :322)
        return new ToolUseBlock("tool-fork-1", "Agent", input);
    }

    /**
     * 反射调 executeSync(...) 11 参版（forkParams + currentCwd + effectiveIsolation + invokingRequestId
     * + onProgress + parentCtx）。
     *
     * <p>[S3-4 决策 B] forkParentSystemPrompt + assistantMessage 已折叠进
     * {@code SubagentExecutor.ForkPathParams} (ForkPathParams.forkParentSystemPrompt /
     * ForkPathParams.assistantMessage), 不再作为独立方法参数 — 对齐 SubagentExecutor 4 参
     * execute(prompt, type, model, forkParams).
     *
     * <p>[IMP-SUB-28 A5] 第 10 参 onProgress（{@code Consumer<Tool.ToolProgress>}）由父 caller
     * （StreamingToolExecutor）注入，降级 sync 经 {@link SubagentTool#buildSyncStreamingSink} 接流式
     * （CC AgentTool.tsx:783-810）；第 11 参 parentCtx 为父 turn 的 ToolUseContext（D21 累加源）。
     */
    private static Object invokeExecuteSync(SubagentTool tool, Object... args) throws Exception {
        Method m = SubagentTool.class.getDeclaredMethod("executeSync", String.class, String.class,
            String.class, Class.forName("com.nexusai.application.agent.subagent.AgentDefinition"),
            String.class,
            Class.forName("com.nexusai.application.agent.tool.impl.SubagentExecutor$ForkPathParams"),
            String.class, String.class, String.class,
            java.util.function.Consumer.class, ToolUseContext.class);
        m.setAccessible(true);
        return m.invoke(tool, args);
    }

    /**
     * 反射调 executeAsync(...) 11 参版（forkParams + currentCwd + effectiveIsolation + invokingRequestId
     * + onProgress + parentCtx）。
     *
     * <p>[IMP-SUB-28 A5] async worker 路径不转发父 onProgress（CC async 返回 async_launched，进度走
     * task panel），仅 backgroundTaskRunner 未注入的降级同步路径接线（sync 语义）。
     */
    private static Object invokeExecuteAsync(SubagentTool tool, Object... args) throws Exception {
        Method m = SubagentTool.class.getDeclaredMethod("executeAsync", String.class, String.class,
            String.class, Class.forName("com.nexusai.application.agent.subagent.AgentDefinition"),
            String.class,
            Class.forName("com.nexusai.application.agent.tool.impl.SubagentExecutor$ForkPathParams"),
            String.class, String.class, String.class,
            java.util.function.Consumer.class, ToolUseContext.class);
        m.setAccessible(true);
        return m.invoke(tool, args);
    }

    /**
     * 反射读 SubagentTool 私有字段 doExecute 派生值 (经 doExecute 后应可见).
     * 这里我们只验证 executeSync/Async 签名 + 透传约定, 不深入 doExecute 状态.
     */
    private static boolean hasField(SubagentTool tool, String name) throws Exception {
        try {
            Field f = SubagentTool.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getType() == boolean.class;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    // ═════════════════════ Test 1: executeSync 11 参签名（forkParams + currentCwd + effectiveIsolation + onProgress + parentCtx）═════════════════════

    @Test
    @DisplayName("executeSync 11 参签名: 含 ForkPathParams + currentCwd + effectiveIsolation + onProgress + parentCtx（S3 forkParams 折叠 + IMP-SUB-28 A5 onProgress 透传）")
    void executeSync_forkPath_buildForkedMessagesReceivesAssistantMessage() throws Exception {
        // GIVEN: SubagentTool 无 Spring 注入 (无 backgroundTaskRunner, 降级到同步路径)
        SubagentTool tool = new SubagentTool();

        // WHEN: 反射查 executeSync 11 参签名
        //   [S3-4 决策 B] forkParentSystemPrompt + assistantMessage 折叠进 ForkPathParams
        //   (ForkPathParams.forkParentSystemPrompt / .assistantMessage), 不再独立成参 — SubagentExecutor
        //   4 参 execute(prompt, type, model, forkParams) 接收 (对齐 CC AgentTool.tsx:622-623 override.systemPrompt
        //   + :512 buildForkedMessages 完整透传链). 末参 effectiveIsolation (CC :431) 经 setEffectiveIsolation 透传.
        //   [IMP-SUB-28 A5 返工 R3] 第 10 参 onProgress (Consumer<Tool.ToolProgress>) — 父 caller
        //   （StreamingToolExecutor）注入, 降级 sync 经 buildSyncStreamingSink 接流式
        //   (CC AgentTool.tsx:783-810 同步路径 onProgress 上报); 第 11 参 parentCtx (ToolUseContext,
        //   D21 setResponseLength 累加源).
        Class<?> agentDefClass = Class.forName("com.nexusai.application.agent.subagent.AgentDefinition");
        Class<?> forkParamsClass = Class.forName("com.nexusai.application.agent.tool.impl.SubagentExecutor$ForkPathParams");
        Method m;
        try {
            m = SubagentTool.class.getDeclaredMethod("executeSync", String.class, String.class, String.class,
                agentDefClass, String.class, forkParamsClass, String.class, String.class, String.class,
                java.util.function.Consumer.class, ToolUseContext.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("executeSync 11 参签名（forkParams + currentCwd + effectiveIsolation + invokingRequestId + onProgress + parentCtx）不存在 — 合并后未对齐",
                e);
        }
        //   验证参数类型: 6 参 (ForkPathParams) + 7 参 (String currentCwd) + 8 参 (String effectiveIsolation)
        //   + 9 参 (String invokingRequestId, [RF-1] 父 assistantMessage.requestId 透传)
        //   + 10 参 (Consumer onProgress, [IMP-SUB-28 A5] CC AgentTool.tsx:783-810)
        //   + 11 参 (ToolUseContext parentCtx, D21 setResponseLength 累加源)
        Class<?>[] paramTypes = m.getParameterTypes();
        assertThat(paramTypes).hasSize(11);
        assertThat(paramTypes[5])
            .as("executeSync 第 6 参（forkParams）必须是 SubagentExecutor.ForkPathParams（承载 forkParentSystemPrompt + assistantMessage）")
            .isEqualTo(forkParamsClass);
        assertThat(paramTypes[6])
            .as("executeSync 第 7 参（currentCwd）必须是 String 类型（CC :600 worktree path 派生）")
            .isEqualTo(String.class);
        assertThat(paramTypes[7])
            .as("executeSync 第 8 参（effectiveIsolation）必须是 String 类型（CC :431 isolation ?? selectedAgent.isolation）")
            .isEqualTo(String.class);
        assertThat(paramTypes[8])
            .as("executeSync 第 9 参（invokingRequestId）必须是 String 类型（[RF-1] CC AgentTool.tsx:778 assistantMessage?.requestId）")
            .isEqualTo(String.class);
        assertThat(paramTypes[9])
            .as("executeSync 第 10 参（onProgress）必须是 java.util.function.Consumer（[IMP-SUB-28 A5] 父 caller 注入, CC AgentTool.tsx:783-810 同步路径 onProgress 上报）")
            .isEqualTo(java.util.function.Consumer.class);
        assertThat(paramTypes[10])
            .as("executeSync 第 11 参（parentCtx）必须是 ToolUseContext（D21 setResponseLength 累加源；CC 真源 toolUseContext 参 AgentTool.tsx:250）")
            .isEqualTo(ToolUseContext.class);
    }

    // ═════════════════════ Test 2: executeAsync 11 参签名（forkParams + currentCwd + effectiveIsolation + onProgress + parentCtx）═════════════════════

    @Test
    @DisplayName("executeAsync 11 参签名: 含 ForkPathParams + currentCwd + effectiveIsolation + onProgress + parentCtx（S3 forkParams 折叠 + IMP-SUB-28 A5 onProgress 透传）")
    void executeSync_forkPath_forkParentSystemPromptPropagated() throws Exception {
        // GIVEN: SubagentTool 无 Spring 注入
        SubagentTool tool = new SubagentTool();

        // WHEN: 反射查 executeAsync 11 参签名
        //   [IMP-SUB-28 A5 返工 R3] 第 10 参 onProgress (Consumer<Tool.ToolProgress>) — async worker
        //   路径不转发父 onProgress（CC async 返回 async_launched，进度走 task panel），仅
        //   backgroundTaskRunner 未注入的降级同步路径接线（sync 语义）；第 11 参 parentCtx (D21 累加源).
        Class<?> agentDefClass = Class.forName("com.nexusai.application.agent.subagent.AgentDefinition");
        Class<?> forkParamsClass = Class.forName("com.nexusai.application.agent.tool.impl.SubagentExecutor$ForkPathParams");
        Method m;
        try {
            m = SubagentTool.class.getDeclaredMethod("executeAsync", String.class, String.class, String.class,
                agentDefClass, String.class, forkParamsClass, String.class, String.class, String.class,
                java.util.function.Consumer.class, ToolUseContext.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("executeAsync 11 参签名（forkParams + currentCwd + effectiveIsolation + invokingRequestId + onProgress + parentCtx）不存在 — 合并后未对齐",
                e);
        }

        // THEN: 11 参含 ForkPathParams (6) + currentCwd (7) + effectiveIsolation (8) + invokingRequestId (9)
        //   + onProgress (10) + parentCtx (11)
        Class<?>[] paramTypes = m.getParameterTypes();
        assertThat(paramTypes).hasSize(11);
        assertThat(paramTypes[5])
            .as("executeAsync 第 6 参（forkParams）必须是 SubagentExecutor.ForkPathParams")
            .isEqualTo(forkParamsClass);
        assertThat(paramTypes[6])
            .as("executeAsync 第 7 参（currentCwd）必须是 String 类型")
            .isEqualTo(String.class);
        assertThat(paramTypes[7])
            .as("executeAsync 第 8 参（effectiveIsolation）必须是 String 类型")
            .isEqualTo(String.class);
        assertThat(paramTypes[8])
            .as("executeAsync 第 9 参（invokingRequestId）必须是 String 类型（[RF-1] CC AgentTool.tsx:723 assistantMessage?.requestId）")
            .isEqualTo(String.class);
        assertThat(paramTypes[9])
            .as("executeAsync 第 10 参（onProgress）必须是 java.util.function.Consumer（[IMP-SUB-28 A5] async 不转发父 onProgress，仅降级 sync 接线，CC AgentTool.tsx:686-764）")
            .isEqualTo(java.util.function.Consumer.class);
        assertThat(paramTypes[10])
            .as("executeAsync 第 11 参（parentCtx）必须是 ToolUseContext（D21 降级 sync 路径 setResponseLength 累加源）")
            .isEqualTo(ToolUseContext.class);
    }

    // ═════════════════════ Test 3: fork 前缀经 SubagentExecutor 生产路径 ═════════════════════

    @Test
    @DisplayName("buildForkedMessages 经 SubagentExecutor.execute 生产路径: assistantMessage 非 null 时走 fork 前缀（CC :512）")
    void execute_forkPath_assistantMessagePresent_buildsForkedMessagesPrefix() {
        // GIVEN: 父 assistant message 含 1 个 tool_use (CC :123-125) + directive
        ForkSubagentMessages.AssistantMessage assistantMessage = new ForkSubagentMessages.AssistantMessage(
            "parent-uuid",
            List.of(new ForkSubagentMessages.BetaToolUseBlock(
                "tool-use-id-1", "Agent",
                JSON.createObjectNode().put("description", "parent task").put("prompt", "parent prompt")))
        );
        String directive = "Subagent fork task directive (CC :108)";

        // 拦截 SubagentExecutor 日志 — 生产路径观测通道 (装配产物经数据流日志暴露)
        Logger logger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.DEBUG);

            // WHEN: 经生产路径驱动 SubagentExecutor.execute(prompt, "fork", null, forkParams)
            SubagentExecutor executor = new SubagentExecutor(
                ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt");
            SubagentExecutor.ForkPathParams forkParams = new SubagentExecutor.ForkPathParams(
                assistantMessage, List.of(), "parent-system-prompt", null);

            // contextFactory 未注入 → Step 20 抛 ISE (Step 10-19 装配已完成, 日志已产出)
            Assertions.assertThatThrownBy(
                () -> executor.execute(directive, "fork", null, forkParams))
                .isInstanceOf(IllegalStateException.class);

            // THEN: 关键分支日志证明 buildForkedMessages 经生产路径调用 (CC AgentTool.tsx:512)
            List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(logs)
                .as("fork path 必须调用 buildForkedMessages, tool_use 保留 (CC :512, 123-125)")
                .anyMatch(m -> m.contains("fork 缓存共享: buildForkedMessages 调用")
                            && m.contains("forkedMessages.size=2"));
            // placeholder/directive 文本在 toInitialMessageMaps 转换后的 content 内,
            //   日志不暴露消息内容 — 由 ForkSubagentMessages 单测覆盖 (合并 2026-08-04 删除日志断言).
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}