package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.permission.InputSanitizer;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [A1 撤外层] Permission gate + 7 段内层串联注入验证.
 *
 * <p>本测试验证 Session A1 的核心交付物 — 把外层 {@code LlmAgentLoop.applyPermissionFilter}
 * 的权限决策全部搬到 {@link StreamingToolExecutor#executeAsync} 内层, 完全对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:599} {@code checkPermissionsAndCallTool}
 * 单一串联架构. 验证策略:
 * <ol>
 *   <li><b>Setter 注入</b>: 反射验证 {@link StreamingToolExecutor#setInputSanitizer} +
 *       {@link StreamingToolExecutor#setInputValidator} 已添加 (原代码无 setter).</li>
 *   <li><b>6 参构造器</b>: 反射验证新增 5 参便捷构造器
 *       {@code (registry, ctx, handler, gate, hookRegistry)} 已添加.</li>
 *   <li><b>7 段顺序</b>: 通过 source code 顺序验证 executeAsync 内 7 段串联顺序:
 *       cancel → field strip → schema → semantic → hook → gate → decision telemetry.</li>
 *   <li><b>决策 telemetry 注入</b>: 验证 {@code injectDecisionInfo} helper 已添加,
 *       按 callId 归因 (替代原 LlmAgentLoop.emitDecisionTelemetry).</li>
 * </ol>
 *
 * <p><b>WHY 5 个测试</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ul>
 *   <li>{@code executeAsync_runsCancelCheckBeforeGate} — 入口 cancel 短路是 CC 第一道门</li>
 *   <li>{@code executeAsync_runsSchemaValidationBeforeGate} — schema 验证在 hook 之前 (CC order)</li>
 *   <li>{@code executeAsync_runsPreToolUseHookBeforeGate} — hook 串联在 gate 之前</li>
 *   <li>{@code executeAsync_runsGateBeforeExecute} — 权限门是 tool.execute 之前的最后守门</li>
 *   <li>{@code executeAsync_emitsTelemetryOnDecision} — 决策归因是 telemetry 完整性保证</li>
 * </ul>
 */
class PermissionGateInjectionTest {

    private static final String STREAMING_EXECUTOR_PATH =
        "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java";

    // ─────────────────────── 1. 入口 cancel 短路 (CC toolExecution.ts:245 + permissions.ts:1163) ───────────────────────

    /**
     * [A1 撤外层 (a)] executeAsync 入口 cancel 短路 · getAbortReason 检查必须在
     * permissionGate.check 之前, emit tengu_tool_use_cancelled telemetry.
     *
     * <p>WHY 顺序重要: CC line 245-253 abortController.signal.aborted 检查在所有
     * 权限逻辑之前 — cancel 是最高优先级短路. Java 端经 {@code getAbortReason(t)}
     * 覆盖 per-tool/per-session/agent abort (agent 级 cancel 由 abortController.abort()
     * 承载, 对齐 CC getAbortReason 无独立 agent_cancelled 态).
     *
     * <p>[G29① S-2 修正] 旧实现还有第 2 个互斥短路 {@code agentStateRef.cancelled()}
     * (Java 独有 "agent_cancelled" 态) 已删除 — 全库仅剩 getAbortReason 单一 abort
     * 检查 + 单一 emitCancelledTelemetry(t) 发射点, 对齐 CC toolExecution.ts:415 单点检查.
     */
    @Test
    @DisplayName("executeAsync 入口 cancel 短路 (getAbortReason) 在 permissionGate.check 之前 + 注入 telemetry")
    void executeAsync_runsCancelCheckBeforeGate() throws Exception {
        // 1. 验证 setAgentState / agentStateRef 字段已存在 (取消信号关联, 供 appendAttachment 等)
        Method setAgentState = StreamingToolExecutor.class.getDeclaredMethod(
            "setAgentState", com.nexusai.application.agent.AgentState.class);
        assertThat(setAgentState)
            .as("setAgentState 必须存在 (cancel 关联 / appendAttachment 需要 agentStateRef)")
            .isNotNull();

        // 2. 验证 source code 顺序: cancel 检查在 permissionGate.check 之前
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(STREAMING_EXECUTOR_PATH));
        int cancelCheckIdx = source.indexOf("abortReason = getAbortReason(t)");
        int gateCheckIdx = source.indexOf("permissionGate.check(");
        assertThat(cancelCheckIdx)
            .as("executeAsync 必须包含 abortReason = getAbortReason(t) 检查 (a) 入口 cancel 短路")
            .isGreaterThan(0);
        assertThat(gateCheckIdx)
            .as("executeAsync 必须包含 permissionGate.check() 调用 (g) 权限门")
            .isGreaterThan(0);
        assertThat(cancelCheckIdx)
            .as("cancel 检查必须在 permissionGate.check 之前 (CC line 245 abort 在所有权限逻辑之前)")
            .isLessThan(gateCheckIdx);

        // 3. 验证 emitCancelledTelemetry helper + tengu_tool_use_cancelled telemetry.
        //    [Session I P3-2 强化] 防注释欺骗: 仅 contains("emitCancelledTelemetry") 会被
        //    helper 注释文本满足 (弱断言). 必须存在真实调用点字符串 "emitCancelledTelemetry(t)"
        //    (注释里写的是 "emitCancelledTelemetry:" / "复用 emitCancelledTelemetry",
        //    不含 "(t)" 后缀), 且调用点位于 abort 检查 (getAbortReason) 之后.
        //    [G29① S-2] 单发射点: agent-level cancel 独立分支已删除, 不再要求第 2 个调用点.
        assertThat(source).contains("emitCancelledTelemetry");
        assertThat(source).contains("tengu_tool_use_cancelled");
        int firstCallIdx = source.indexOf("emitCancelledTelemetry(t)");
        int abortCheckIdx = source.indexOf("abortReason = getAbortReason(t)");
        assertThat(firstCallIdx)
            .as("必须存在真实调用点 emitCancelledTelemetry(t) (getAbortReason 短路)")
            .isGreaterThan(0);
        assertThat(firstCallIdx)
            .as("调用点必须位于 getAbortReason 检查之后 (事件先于合成错误产出)")
            .isGreaterThan(abortCheckIdx);
    }

    // ─────────────────────── 2. schema 校验 (CC toolExecution.ts:615-680) ───────────────────────

    /**
     * [A1 撤外层 (d)] executeAsync schema 校验 · ToolInputValidator.safeParseSchema 调用
     * 必须在 permissionGate.check 之前; 失败注入 tengu_tool_use_error +
     * tengu_deferred_tool_schema_not_sent (仅 MCP deferred) + SchemaNotSentHint 拼接到 error.
     */
    @Test
    @DisplayName("executeAsync schema 校验在 permissionGate.check 之前 + emit 错误 telemetry")
    void executeAsync_runsSchemaValidationBeforeGate() throws Exception {
        // 1. 验证 setInputValidator setter 已添加 (executeAsync 内部 schema 校验依赖)
        Method setInputValidator = StreamingToolExecutor.class.getDeclaredMethod(
            "setInputValidator", ToolInputValidator.class);
        assertThat(setInputValidator)
            .as("setInputValidator setter 必须存在 (A1 schema 校验前置)")
            .isNotNull();

        // 2. 验证 source code 顺序: schema 校验在 permissionGate.check 之前
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(STREAMING_EXECUTOR_PATH));
        int schemaIdx = source.indexOf("safeParseSchema(");
        int gateCheckIdx = source.indexOf("permissionGate.check(");
        assertThat(schemaIdx)
            .as("executeAsync 必须包含 safeParseSchema 调用 (d) schema 校验")
            .isGreaterThan(0);
        assertThat(gateCheckIdx)
            .as("executeAsync 必须包含 permissionGate.check() 调用")
            .isGreaterThan(0);
        assertThat(schemaIdx)
            .as("schema 校验必须在 permissionGate.check 之前 (CC line 615-680 在权限逻辑之前)")
            .isLessThan(gateCheckIdx);

        // 3. 验证 telemetry emission: tengu_tool_use_error + tengu_deferred_tool_schema_not_sent
        assertThat(source).contains("emitToolUseErrorTelemetry");
        assertThat(source).contains("emitDeferredSchemaTelemetry");
        assertThat(source).contains("tengu_tool_use_error");
        assertThat(source).contains("tengu_deferred_tool_schema_not_sent");
    }

    // ─────────────────────── 3. PreToolUse hook 串联 (CC toolExecution.ts:795) ───────────────────────

    /**
     * [A1 撤外层 (f)] executeAsync PreToolUse hook 串联 · HookRegistry.executePreToolUse
     * 必须在 permissionGate.check 之前 (CC: hook 优先级高于 permission pipeline).
     *
     * <p>WHY hook 在 gate 前: CC line 795 hook 调用在 permission 决策之前 — hook 可以
     * 通过 Deny 直接阻断, 是 bypass-immune 的最高优先级守门. Java 端原本 hook 在
     * {@code applyPermissionFilter} 内 (line 4487), 现搬到内层 executeAsync 入口
     * (line 769+).
     *
     * <p><b>[P0-3 强化]</b> 方法名从 {@code executePreToolUseOutcome} 改为
     * {@code executePreToolUse}, 返回类型升级为
     * {@code AggregatedHookResult} (16 字段)
     * {@code AggregatedHookResult} (16 字段).
     */
    @Test
    @DisplayName("[P0-3] executeAsync PreToolUse hook 在 permissionGate.check 之前 (CC 顺序)")
    void executeAsync_runsPreToolUseHookBeforeGate() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(STREAMING_EXECUTOR_PATH));
        // [P0-3] 检查新方法名 executePreToolUse (替代旧的 executePreToolUseOutcome)
        int hookIdx = source.indexOf("executePreToolUse(");
        int gateCheckIdx = source.indexOf("permissionGate.check(");
        assertThat(hookIdx)
            .as("executeAsync 必须包含 executePreToolUse 调用 (f) PreToolUse hook 串联")
            .isGreaterThan(0);
        assertThat(gateCheckIdx)
            .as("executeAsync 必须包含 permissionGate.check() 调用")
            .isGreaterThan(0);
        assertThat(hookIdx)
            .as("PreToolUse hook 必须在 permissionGate.check 之前 (CC line 795 hook 优先级高)")
            .isLessThan(gateCheckIdx);

        // 验证 hookRegistry 字段已注入 (在 StreamingToolExecutor 构造时注入, 非 null 时真实调用)
        assertThat(source).contains("if (hookRegistry != null");
        // [P0-3] 验证新数据结构 AggregatedHookResult
        assertThat(source).contains("AggregatedHookResult");
    }

    // ─────────────────────── 4. 权限门 (g) (CC query.ts:1062 + useCanUseTool.tsx:27) ───────────────────────

    /**
     * [A1 撤外层 (g)] executeAsync permissionGate.check 在 tool.execute 之前 ·
     * 对齐 CC query.ts:1062 + useCanUseTool.tsx:27 (3 态决策 allow/deny/ask).
     *
     * <p>WHY gate 在 execute 前: gate 是 tool.execute 前的最后守门 (CC line 921+
     * hasPermissionsToUseToolInner); gate=null 时 fallback allow (向后兼容).
     */
    @Test
    @DisplayName("executeAsync permissionGate.check 在 tool.execute 之前 (最后守门)")
    void executeAsync_runsGateBeforeExecute() throws Exception {
        // 1. 验证新增 5 参便捷构造器 (registry, ctx, handler, gate, hookRegistry)
        //   - 原 5 参 (registry, executor, ctx, handler, gate) 不含 hookRegistry;
        //     A1 必须新增含两个的便捷构造器供 LlmAgentLoop.buildStreamingExecutor 调用.
        java.lang.reflect.Constructor<?> ctor5Args = null;
        for (java.lang.reflect.Constructor<?> c : StreamingToolExecutor.class.getDeclaredConstructors()) {
            if (c.getParameterCount() == 5
                && c.getParameterTypes()[0] == ToolRegistry.class
                && c.getParameterTypes()[1] == ToolUseContext.class
                // extendedResultHandler 实际签名为 BiConsumer<AgentToolResult<?>, String>
                // （StreamingToolExecutor 字段 extendedResultHandler 类型一致），非单参 Consumer。
                && c.getParameterTypes()[2] == java.util.function.BiConsumer.class
                && c.getParameterTypes()[3] == ToolPermissionGate.class
                && c.getParameterTypes()[4] == HookRegistry.class) {
                ctor5Args = c;
                break;
            }
        }
        assertThat(ctor5Args)
            .as("必须新增 5 参便捷构造器 (registry, ctx, handler, gate, hookRegistry) "
                + "供 LlmAgentLoop.buildStreamingExecutor 同时注入 gate + hookRegistry")
            .isNotNull();

        // 2. 验证 source code 顺序: gate check 在 tool.execute 之前
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(STREAMING_EXECUTOR_PATH));
        int gateCheckIdx = source.indexOf("permissionGate.check(");
        int toolExecuteIdx = source.indexOf("t.tool.execute(effectiveCall");
        assertThat(gateCheckIdx)
            .as("permissionGate.check 调用必须存在")
            .isGreaterThan(0);
        assertThat(toolExecuteIdx)
            .as("t.tool.execute 调用必须存在")
            .isGreaterThan(0);
        assertThat(gateCheckIdx)
            .as("permissionGate.check 必须在 t.tool.execute 之前 (CC: gate 是最后守门)")
            .isLessThan(toolExecuteIdx);

        // 3. 验证 gate null fallback: null 时退化为 allow (向后兼容单测)
        assertThat(source).contains("if (permissionGate != null && ctx != null && ctx.permissionContext() != null)");
    }

    // ─────────────────────── 5. 决策 telemetry 归因 (h) (CC toolExecution.ts:948-977 + 1001-1022) ───────────────────────

    /**
     * [A1 撤外层 (h)] executeAsync 决策 telemetry 归因 · Allow/Deny 决策按 callId
     * 注入 {@code toolDecisions} map, 供 emitSuccessTelemetry / emitPostToolUseFailureAnalytics
     * 读取注入 logOTelEvent('tool_result') decision_source / decision_type 字段.
     *
     * <p>WHY 归因到内层: 原外层 emitDecisionTelemetry 在 LlmAgentLoop.applyPermissionFilter
     * 内调 (8 处埋点). A1 撤外层后改在 StreamingToolExecutor.injectDecisionInfo (gate check
     * 之后) 单一 inject. 对齐 CC tool_decision OTel event.
     */
    @Test
    @DisplayName("executeAsync 决策 telemetry 归因 (Allow/Deny 各注入 source/decision)")
    void executeAsync_emitsTelemetryOnDecision() throws Exception {
        // 1. 验证 injectDecisionInfo helper 已添加
        Method injectDecisionInfo = null;
        try {
            injectDecisionInfo = StreamingToolExecutor.class.getDeclaredMethod(
                "injectDecisionInfo", java.lang.reflect.Method.class.getDeclaringClass() /* fallback */,
                ToolPermissionGate.DecisionResult.class);
        } catch (NoSuchMethodException e) {
            // Try with TrackedTool parameter (package-private inner class)
            for (java.lang.reflect.Method m : StreamingToolExecutor.class.getDeclaredMethods()) {
                if (m.getName().equals("injectDecisionInfo")) {
                    injectDecisionInfo = m;
                    break;
                }
            }
        }
        assertThat(injectDecisionInfo)
            .as("injectDecisionInfo helper 必须存在 (h) 决策 telemetry 归因")
            .isNotNull();

        // 2. 验证 decision telemetry 实际发出: tengu_tool_use_can_use_tool_allowed/rejected
        //   + tool_decision OTel event + decision_source / decision_type 注入
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(STREAMING_EXECUTOR_PATH));
        assertThat(source).contains("tengu_tool_use_can_use_tool_allowed");
        assertThat(source).contains("tengu_tool_use_can_use_tool_rejected");
        assertThat(source).contains("logOTelEvent(\"tool_decision\"");
        // 3. 验证决策注入调用顺序: gate check → injectDecisionInfo (决策 telemetry 紧跟 gate)
        int injectDecisionIdx = source.indexOf("injectDecisionInfo(t,");
        int gateCheckIdx = source.indexOf("permissionGate.check(");
        assertThat(injectDecisionIdx)
            .as("injectDecisionInfo(t, gateResult) 调用必须存在")
            .isGreaterThan(0);
        assertThat(gateCheckIdx)
            .as("permissionGate.check 调用必须存在")
            .isGreaterThan(0);
        assertThat(gateCheckIdx)
            .as("injectDecisionInfo 必须在 permissionGate.check 之后 (决策紧跟 gate)")
            .isLessThan(injectDecisionIdx);

        // 4. 验证 LlmAgentLoop 不再有 emitDecisionTelemetry / applyPermissionFilter (撤外层完整)
        String llmSource = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java"));
        assertThat(llmSource)
            .as("LlmAgentLoop 不应再有 emitDecisionTelemetry 引用 (A1 已搬内层)")
            .doesNotContain("emitDecisionTelemetry");
        assertThat(llmSource)
            .as("LlmAgentLoop 不应再有 applyPermissionFilter 引用 (A1 已整体删除)")
            .doesNotContain("applyPermissionFilter");
        assertThat(llmSource)
            .as("LlmAgentLoop 不应再有 PermissionFilterResult 引用 (A1 已删除 record)")
            .doesNotContain("PermissionFilterResult");
    }

    // ─────────────────────── 6. LlmAgentLoop 主路径 wiring 验证 (任务 A1 续工) ───────────────────────

    /**
     * [A1 续工] 验证 LlmAgentLoop.buildStreamingExecutor 真的把 permissionGate
     * 注入到 StreamingToolExecutor 内部. 反射验证 5 参构造函数正确写入 permissionGate 字段.
     *
     * <p>WHY 集成测试 (而非仅 mock): 关键风险是 wiring 静默失败 — 构造器拿到 null
     * gate 但 Spring 容器有 bean, 单元测试 mock 一切不能暴露. 这里手 mock
     * LlmAgentLoop 必要依赖 + 反射 setPermissionGate, 调 buildStreamingExecutor,
     * 反射读 exec.permissionGate 字段, 验证非 null.
     */
    @Test
    @DisplayName("LlmAgentLoop 主路径: buildStreamingExecutor 把 permissionGate 注入到 StreamingToolExecutor")
    void streamingExecutor_receivesLlmAgentLoopPermissionGate() throws Exception {
        // [H7-arch Phase 5-2 P3-⑤] buildStreamingExecutor 已 static 化至 AgentLoopContext。
        // 经 ToolExecutionBeans 注入 gate/hook/sanitizer/validator，反射驱动真实构建。
        LlmProviderFactory factoryMock = Mockito.mock(LlmProviderFactory.class);
        PermissionPipeline pipelineMock = Mockito.mock(PermissionPipeline.class);
        PermissionPrompter prompterMock = Mockito.mock(PermissionPrompter.class);
        ToolPermissionGate gateMock = Mockito.mock(ToolPermissionGate.class);
        HookRegistry hookMock = Mockito.mock(HookRegistry.class);
        InputSanitizer sanitizerMock = Mockito.mock(InputSanitizer.class);
        ToolInputValidator validatorMock = Mockito.mock(ToolInputValidator.class);

        // 1. ctx：注入 ToolExecutionBeans（gate 优先路径）+ hookRegistry
        com.nexusai.application.agent.loop.AgentLoopContext.ToolExecutionBeans beans =
            new com.nexusai.application.agent.loop.AgentLoopContext.ToolExecutionBeans(
                null, gateMock, pipelineMock, prompterMock, sanitizerMock, validatorMock, false,
                null /* sandboxManager */,
                null, null, null,
                null /* bashClassifierFeature */);
        ToolRegistry registry = new ToolRegistry();
        com.nexusai.application.agent.loop.AgentLoopContext ctx = TestContexts.agentLoopContext(
            registry, factoryMock, null, null, null, beans, hookMock);

        // 2. per-turn TUC：非空 availableTools（dummy "Bash"）→ 守卫通过
        java.util.UUID agentId = java.util.UUID.randomUUID();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        com.nexusai.application.agent.tool.ToolUseContext perTurnTuc =
            com.nexusai.application.agent.tool.ToolUseContext.of(agentId, sessionId)
                .withAvailableTools(java.util.List.of(TestContexts.dummyTool("Bash")));

        // 3. 静态调 AgentLoopContext.buildStreamingExecutor
        AgentState state = new AgentState("test prompt", sessionId, agentId);
        Object agentOptions = Class.forName(
            "com.nexusai.application.agent.subagent.createSubagentContext$AgentOptions")
            .getMethod("defaultOptions")
            .invoke(null);
        StreamingToolExecutor exec = com.nexusai.application.agent.loop.AgentLoopContext
            .buildStreamingExecutor(ctx, perTurnTuc, state, "test-assistant-id", null, false,
                (com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions) agentOptions, null);

        // 4. 验证 exec 非 null + permissionGate/hookRegistry 真实注入
        assertThat(exec)
            .as("buildStreamingExecutor 必须构建非 null executor（perTurnTuc 有工具）")
            .isNotNull();
        java.lang.reflect.Field execGateField = StreamingToolExecutor.class.getDeclaredField("permissionGate");
        execGateField.setAccessible(true);
        assertThat(execGateField.get(exec))
            .as("StreamingToolExecutor.permissionGate 必须由 AgentLoopContext.buildStreamingExecutor 注入（gate 优先路径）")
            .isSameAs(gateMock);
        java.lang.reflect.Field execHookField = StreamingToolExecutor.class.getDeclaredField("hookRegistry");
        execHookField.setAccessible(true);
        assertThat(execHookField.get(exec))
            .as("StreamingToolExecutor.hookRegistry 必须由 AgentLoopContext.buildStreamingExecutor 注入")
            .isSameAs(hookMock);
    }


    /**
     * [A1 续工] 验证 LlmAgentLoop.main path 优先取 permissionGate 字段 (Spring 路径),
     *   fallback 到 ToolPermissionGate.createSpringBean 工厂 (老路径 / 单测).
     *
     * <p>WHY 双路径: Spring 容器已注入 ToolPermissionGate @Component 时, 字段直接就位;
     *   老路径 / 单测可能只注入 permissionPipeline+permissionPrompter → 工厂即时拼装.
     */
    @Test
    @DisplayName("LlmAgentLoop 主路径: permissionGate 字段优先, 老路径 fallback 到工厂")
    void lLlmAgentLoop_permissionGateFieldPrefersAutowired() throws Exception {
        // 1. 不注入 permissionGate 字段, 只注入 permissionPipeline + permissionPrompter
        LlmProviderFactory factoryMock = Mockito.mock(LlmProviderFactory.class);
        PermissionPipeline pipelineMock = Mockito.mock(PermissionPipeline.class);
        PermissionPrompter prompterMock = Mockito.mock(PermissionPrompter.class);

        LlmAgentLoop loop = new LlmAgentLoop(factoryMock);
        injectField(loop, "permissionPipeline", pipelineMock);
        injectField(loop, "permissionPrompter", prompterMock);
        // permissionGate 不注入 → 字段保持 null

        // 2. 验证字段为 null (fallback 触发条件)
        java.lang.reflect.Field permissionGateField = LlmAgentLoop.class.getDeclaredField("permissionGate");
        permissionGateField.setAccessible(true);
        assertThat(permissionGateField.get(loop))
            .as("permissionGate 字段未注入时为 null, 触发 fallback 路径")
            .isNull();

        // 3. 验证 ToolPermissionGate.createSpringBean 工厂可用 (静态方法 + 签名)
        //    这一步确保 fallback 路径有工具存在.
        Method createSpringBean = ToolPermissionGate.class.getDeclaredMethod(
            "createSpringBean", PermissionPipeline.class, PermissionPrompter.class);
        assertThat(createSpringBean)
            .as("ToolPermissionGate.createSpringBean 工厂方法必须存在 (fallback 路径)")
            .isNotNull();

        // 4. 验证 buildStreamingExecutor 内部确实调用了 fallback (非 null pipeline + prompter)
        //    [H7-arch Phase 5-2 P3-⑤] 工厂已 static 化至 AgentLoopContext，源码静态检查改读该文件
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/loop/AgentLoopContext.java"));
        // "beans.permissionGate() != null" 优先路径
        int preferIdx = source.indexOf("beans.permissionGate() != null");
        // "createSpringBean(beans.permissionPipeline(), beans.permissionPrompter())" fallback
        // [Session H9] 3 参重载 (telemetry 注入 logPermissionDecision) —
        // [canUseTool v2] 4 参重载 (speculativeClassifier + 三 handler) 多行展开, 断言拆行前缀
        //   意图 (fallback 路径存在) 不因 telemetry/分类器参数变化而失效
        int fallbackIdx = source.indexOf("beans.permissionPipeline(), beans.permissionPrompter()");
        assertThat(preferIdx)
            .as("buildStreamingExecutor 必须有 permissionGate 优先路径")
            .isGreaterThan(0);
        assertThat(fallbackIdx)
            .as("buildStreamingExecutor 必须有 createSpringBean fallback 路径")
            .isGreaterThan(0);
        assertThat(preferIdx)
            .as("优先路径必须在 fallback 路径之前 (顺序检查)")
            .isLessThan(fallbackIdx);
    }

    // ─────────────────────── 7. [H7-arch Phase 5-2 P3-③] SubagentExecutor 接入 queryLoop wiring 验证 ─────

    /**
     * [H7-arch Phase 5-2 P3-③] 验证 SubagentExecutor 接入 queryLoop（单一循环源）。
     *   carrierFactory 已删除 → 改经 setContextFactory 注入 AgentLoopContextFactory；
     *   runSubagentQueryLoop 调 queryLoop（对齐 CC runAgent 复用 query()），工具隔离走 base TUC。
     */
    @Test
    @DisplayName("SubagentExecutor 接入 queryLoop + setContextFactory wiring（carrierFactory 已删）")
    void subagentExecutor_wiredToQueryLoop() throws Exception {
        // 1. 验证 setContextFactory setter 存在（替代 setCarrierFactory）
        Method setContextFactory = SubagentExecutor.class.getDeclaredMethod(
            "setContextFactory", com.nexusai.application.agent.loop.AgentLoopContextFactory.class);
        assertThat(setContextFactory).as("setContextFactory 必须存在（P3-③ 替代 carrierFactory）").isNotNull();

        // 2. 验证 carrierFactory 字段/setter 已删除（grep gate 归零）
        for (String name : new String[]{"carrierFactory", "setCarrierFactory"}) {
            try {
                SubagentExecutor.class.getDeclaredField(name);
                SubagentExecutor.class.getDeclaredMethod(name, java.util.function.Supplier.class);
                assertThat(false).as(name + " 必须已删除（P3-③ 删 carrierFactory）").isTrue();
            } catch (NoSuchFieldException | NoSuchMethodException expected) {
                // 已删除 → 符合预期
            }
        }

        // 3. 验证源码 SubagentExecutor 调 queryLoop + factory.shared() + base TUC 工具隔离
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/impl/SubagentExecutor.java"));
        assertThat(source)
            .as("SubagentExecutor 必须调 LlmAgentLoop.queryLoop（单一循环源 · B1 收敛签名）")
            .contains("LlmAgentLoop.queryLoop(queryParams, state,");
        assertThat(source)
            .as("SubagentExecutor 必须构造 SubagentLoopDeps（isMainLoop=false）· P3-③ 持 AgentLoopContext（factory.shared(会话 projectRoot)）")
            .contains("new com.nexusai.application.agent.loop.SubagentLoopDeps(")
            .contains("contextFactory.shared(AutoMemPaths.currentSessionProjectRoot())");
        assertThat(source)
            .as("SubagentExecutor 必须用 base TUC withAvailableTools(effectiveTools)（D7 工具隔离）")
            // IMP-SUB-19 #23: create() 直接返回 ToolUseContext，不再经 toolUseContext() 解包装。
            .contains("subagentCtx.withAvailableTools(allTools)");
        assertThat(source)
            .as("SubagentExecutor 不得再引用 carrier.toLoopContext()（P3-③ 归零）")
            .doesNotContain("carrier.toLoopContext()");
    }

    /**
     * 反射注入 private 字段 (测试用) · 绕过 @Autowired setter/getter 限制.
     */
    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}