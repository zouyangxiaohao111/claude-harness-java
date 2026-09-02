package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [IMP-SUB-14 返工] D12 调用方接线聚焦测试（CLAUDE.md 规则九 · 测试验证意图）。
 *
 * <p><b>WHY</b>: AgentModelResolverTest 仅验证 resolver 单例（AgentModelResolver.resolve 本身），
 * <strong>不覆盖本任务实际行为变更——调用方接线</strong>：
 * <ul>
 *   <li>{@code doExecute:1690} {@code getAgentModel(selectedAgent, isForkPath ? null : model)}
 *       —— 非 fork path 把 tool model（sonnet/opus/haiku）传入 resolve 走完整解析链
 *       （aliasMatchesParentTier 父档位继承防降级，issue #30815）+ parseUserSpecifiedModel；
 *       旧绕过 {@code model != null ? model : getAgentModel(...)} 会把 tool model 当原始串直用。</li>
 *   <li>{@code isForkPath ? null : model} —— fork path tool model 恒 null（CC AgentTool.tsx:418
 *       {@code isForkPath ? undefined : model}），不注入 tool model。</li>
 *   <li>{@code executeResumeAsync:2801} {@code AgentModelResolver.resolve(sel.model().orElse(null),
 *       resolveParentModel(), null, null)} —— resume 模型经 resolve 链（aliasMatchesParentTier / parse
 *       生效），不再用原始 agent 模型串（CC resumeAgent.ts:151-156 + :179 model:undefined 内部解析）。</li>
 * </ul>
 *
 * <p><b>RED 依据（本测试要防的回归）</b>: 把 {@code doExecute} 改回旧绕过 / 把 fork 分支改回注入 tool model /
 * 把 {@code executeResumeAsync} 改回 {@code sel.model().orElse(defaultModel)}，任一回归都会让对应断言 RED。
 *
 * <p><b>环境变量稳健性</b>: CC getAgentModel 最高优先级是 {@code CLAUDE_CODE_SUBAGENT_MODEL} env override
 * （agent.ts:43-45）——测试机/CI 若设了该 env，resolvedModel/effectiveModel 会是 env 值（env 恒压过
 * tool/agent model，任何接线都拿不到父精确串）。故：
 * <ul>
 *   <li>关键断言<strong>不依赖 env 是否设置</strong>：resolvedModel/effectiveModel 必须<strong>不是</strong>
 *       原始 tool/agent model 串（旧绕过会原样透传原始串 → RED），且 debug 日志必须显示 toolModel
 *       /agentModel 确实传入了 resolve（env 压过时仍走接线，只是结果被 env 覆盖）。</li>
 *   <li>env 未设置时补强断言：resolvedModel/effectiveModel == 父精确串（aliasMatchesParentTier 命中
 *       继承父档位）或 == parse 展开结果（aliasMatchesParentTier 未命中）——完整链语义可观察。</li>
 * </ul>
 *
 * <p><b>可观测 seam</b>: 三处接线点均带 D12 数据流 debug 日志（数据流日志规范），测试经 ListAppender
 * 捕获 {@code doExecute} / {@code executeResumeAsync} 实际产出的 resolvedModel/effectiveModel，
 * 而非重复 resolver 单例逻辑。
 */
@DisplayName("[IMP-SUB-14 返工] D12 调用方接线（doExecute 非 fork 全链 / fork null / resume resolve 链）")
class SubagentToolD12ModelWiringTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试机/CI 可能设置 CC env override（最高优先级，agent.ts:43-45）——断言须区分处理。 */
    private static final String ENV_OVERRIDE = System.getenv("CLAUDE_CODE_SUBAGENT_MODEL");

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

    /** 9 参构造器 · defaultModel 即父模型（mainLoop=null → resolveParentModel() 回落 defaultModel）。 */
    private static SubagentTool toolWithParent(String parentModel) throws Exception {
        return new SubagentTool(
            List.of(), null, null, null, parentModel, "", null,
            Files.createTempDirectory("d12-wiring"), List.of());
    }

    /** minimal tool_use block · subagent_type 可选（缺省 + fork gate on → fork path）。 */
    private static ToolUseBlock toolCall(String subagentType, String model) {
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "test subagent");
        input.put("prompt", "do the task");
        if (subagentType != null) {
            input.put("subagent_type", subagentType);
        }
        if (model != null) {
            input.put("model", model);
        }
        return new ToolUseBlock("tool-d12", "Agent", input);
    }

    /**
     * 反射调用私有 doExecute（对齐 ForkFallbackSourceTest invokeDoExecute 模式）。
     *
     * <p>【R1-D12 返工】生产签名已由 4 参升级为 5 参（SubagentTool.java:1487）——
     * 在 {@code ctx} 与 {@code agentOptions} 之间插入 {@code java.util.function.Consumer<Tool.ToolProgress> onProgress}
     * （泛型运行时擦除为 raw {@code Consumer}，对齐 CC AgentTool.tsx:783-810 sync 路径 onProgress 上报，
     * IMP-SUB-28 A5 透传）。反射参数类型列表必须同步，否则 {@code getDeclaredMethod} 抛
     * {@code NoSuchMethodException} → 断言前先 AssertionError（RED）。invoke 实参相应补一个 {@code null}。
     */
    private static void invokeDoExecute(SubagentTool tool, ToolUseBlock call) {
        try {
            Method m = SubagentTool.class.getDeclaredMethod("doExecute", ToolUseBlock.class,
                ToolUseContext.class,
                java.util.function.Consumer.class,
                Class.forName("com.nexusai.application.agent.subagent.createSubagentContext$AgentOptions"),
                Class.forName("com.nexusai.application.agent.subagent.ForkSubagentMessages$Message"));
            m.setAccessible(true);
            try {
                m.invoke(tool, call, null, null, null, null);
            } catch (Throwable ignored) {
                // 下游 llmProviderFactory=null 抛异常属预期 — 断言只依赖 D12 接线日志（接线点先于下游失败）
            }
        } catch (Exception e) {
            throw new AssertionError("doExecute 反射调用失败", e);
        }
    }

    /** executeResumeAsync 会 spawn daemon worker 并发打日志 → 断言前必须快照（防 ConcurrentModificationException）。 */
    private List<ILoggingEvent> snapshot() {
        return new ArrayList<>(appender.list);
    }

    private static String resolveExpected() {
        // env override 存在 → 任何接线结果都被 env 压过（CC agent.ts:43-45），无法观察父档位继承
        if (ENV_OVERRIDE != null && !ENV_OVERRIDE.isBlank()) {
            return null;
        }
        return "claude-sonnet-4-5";
    }

    // ─────────────────────────── doExecute 非 fork path ───────────────────────────

    @Test
    @DisplayName("非 fork: tool 'sonnet' + 父 sonnet → 走完整链（继承父精确串，非原始串）")
    void doExecute_nonFork_toolModelGoesThroughFullChain() throws Exception {
        // GIVEN: 父模型 sonnet 档位（defaultModel 承载）+ subagent_type 显式（非 fork path）+ tool model "sonnet"
        SubagentTool tool = toolWithParent("claude-sonnet-4-5");

        // WHEN: 触发 doExecute 非 fork path
        invokeDoExecute(tool, toolCall("general-purpose", "sonnet"));

        // THEN: ① D12 日志必须显示 toolModel=sonnet 确实传入（env 压过时仍走接线，只是结果被 env 覆盖）
        //       ② resolvedModel 必须 != 原始 "sonnet"（旧绕过 model != null ? model : ... 会原样透传 → RED）
        //       ③ env 未设置 → resolvedModel == 父精确串（aliasMatchesParentTier 命中继承父档位）
        List<ILoggingEvent> logs = snapshot();
        assertThat(logs)
            .as("非 fork path tool model 必须传入 getAgentModel 走完整解析链（CC AgentTool.tsx:418），"
                + "且 resolvedModel 不能是原始 tool model 串")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("D12 模型解析")
                .contains("isForkPath=false")
                .contains("toolModel=sonnet")
                .doesNotContain("resolvedModel=sonnet"));
        String expected = resolveExpected();
        if (expected != null) {
            assertThat(logs)
                .as("env 未设置时 tool 'sonnet' + 父 sonnet → 必须继承父精确串（aliasMatchesParentTier）")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("resolvedModel=" + expected));
        }
    }

    @Test
    @DisplayName("非 fork: tool 'opus' + 父 sonnet → 走完整链（parse 展开 opus 默认，非原始串）")
    void doExecute_nonFork_toolModelNotMatchingParent_parseActive() throws Exception {
        // GIVEN: 父模型 sonnet 档位 + tool model "opus"（父档位不匹配 → 走 parse 而非继承）
        SubagentTool tool = toolWithParent("claude-sonnet-4-5");

        invokeDoExecute(tool, toolCall("general-purpose", "opus"));

        // THEN: ① toolModel=opus 确实传入 ② resolvedModel != 原始 "opus"（旧绕过会原样透传 → RED）
        //       ③ env 未设置 → resolvedModel == "claude-opus-4-6"（parse 展开裸家族别名）
        List<ILoggingEvent> logs = snapshot();
        assertThat(logs)
            .as("tool model 不匹配父档位时必须走 parse（aliasMatchesParentTier 未命中 → 展开别名），"
                + "且 resolvedModel 不能是原始 'opus'")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("D12 模型解析")
                .contains("isForkPath=false")
                .contains("toolModel=opus")
                .doesNotContain("resolvedModel=opus"));
        String expected = resolveExpected();
        if (expected != null) {
            assertThat(logs)
                .as("env 未设置时 tool 'opus' + 父 sonnet → parse 展开为 claude-opus-4-6")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("resolvedModel=claude-opus-4-6"));
        }
    }

    // ─────────────────────────── doExecute fork path ───────────────────────────

    @Test
    @DisplayName("fork path: 输入带 tool model 但 isForkPath → toolModel=null（不注入，CC :418 undefined）")
    void doExecute_forkPath_passesNullToolModel() throws Exception {
        // GIVEN: 父模型 sonnet + 输入 model="sonnet"（但 subagent_type 缺省 + fork gate on → fork path）
        SubagentTool tool = toolWithParent("claude-sonnet-4-5");

        invokeDoExecute(tool, toolCall(null, "sonnet"));

        // THEN: D12 日志 toolModel=null（isForkPath ? null : model 三元）—— 不注入 tool model
        //   RED 依据: 若 fork 分支误注入 tool model → toolModel=sonnet（注入原始串）。
        assertThat(snapshot())
            .as("fork path tool model 必须传 null（CC AgentTool.tsx:418 isForkPath ? undefined : model），"
                + "不注入 tool model")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("D12 模型解析")
                .contains("isForkPath=true")
                .contains("toolModel=null"));
    }

    // ─────────────────────────── executeResumeAsync resolve 链 ───────────────────────────

    @Test
    @DisplayName("resume: agent model 'sonnet' + 父 sonnet → 经 resolve 链（继承父精确串，非原始串）")
    void executeResumeAsync_modelGoesThroughResolveChain_inheritsParent() throws Exception {
        // GIVEN: 父模型 sonnet + resume agent 定义 model='sonnet'（裸家族别名）
        SubagentTool tool = toolWithParent("claude-sonnet-4-5");
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.registerAsyncAgent(any(), any(), any(), any(), any(), any()))
            .thenReturn(new BackgroundTask("resume-1", TaskType.LOCAL_AGENT,
                BackgroundTaskStatus.RUNNING, "resume", null,
                System.currentTimeMillis(), null, null, "/tmp/resume-1.out", 0L, false));
        when(runner.taskAbortController("resume-1")).thenReturn(new AbortController());
        tool.setBackgroundTaskRunner(runner);
        AgentDefinition sel = AgentDefinition.CustomAgentDefinition.builder(
            "resume-agent", "resume desc", "userSettings", "resume prompt body")
            .model("sonnet").build();

        // WHEN: 触发 resume（effectiveModel 在调度线程同步计算 + D12 日志）
        tool.executeResumeAsync(UUID.randomUUID(), "continue", sel, "resume",
            null, List.of(), null, null);

        // THEN: ① agentModel=sonnet 确实传入 resolve ② effectiveModel != 原始 "sonnet"
        //       （旧实现 sel.model().orElse(defaultModel) 会原样透传 → RED）
        //       ③ env 未设置 → effectiveModel == 父精确串（aliasMatchesParentTier 命中继承父档位）
        List<ILoggingEvent> logs = snapshot();
        assertThat(logs)
            .as("resume 模型必须经 resolve 链（CC resumeAgent.ts:151-156 + :179 model:undefined 内部解析），"
                + "且 effectiveModel 不能是原始 agent model 串")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("executeResumeAsync D12 模型解析")
                .contains("agentModel=sonnet")
                .contains("parentModel=claude-sonnet-4-5")
                .doesNotContain("effectiveModel=sonnet"));
        String expected = resolveExpected();
        if (expected != null) {
            assertThat(logs)
                .as("env 未设置时 agent 'sonnet' + 父 sonnet → 必须继承父精确串（aliasMatchesParentTier）")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("effectiveModel=" + expected));
        }
    }

    @Test
    @DisplayName("resume: agent model 'opus' + 父 sonnet → 经 resolve 链（parse 展开 opus 默认，非原始串）")
    void executeResumeAsync_modelGoesThroughResolveChain_parseActive() throws Exception {
        // GIVEN: 父模型 sonnet + resume agent 定义 model='opus'（父档位不匹配 → 走 parse）
        SubagentTool tool = toolWithParent("claude-sonnet-4-5");
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.registerAsyncAgent(any(), any(), any(), any(), any(), any()))
            .thenReturn(new BackgroundTask("resume-2", TaskType.LOCAL_AGENT,
                BackgroundTaskStatus.RUNNING, "resume", null,
                System.currentTimeMillis(), null, null, "/tmp/resume-2.out", 0L, false));
        when(runner.taskAbortController("resume-2")).thenReturn(new AbortController());
        tool.setBackgroundTaskRunner(runner);
        AgentDefinition sel = AgentDefinition.CustomAgentDefinition.builder(
            "resume-agent", "resume desc", "userSettings", "resume prompt body")
            .model("opus").build();

        tool.executeResumeAsync(UUID.randomUUID(), "continue", sel, "resume",
            null, List.of(), null, null);

        // THEN: ① agentModel=opus 确实传入 ② effectiveModel != 原始 "opus"（旧实现会原样透传 → RED）
        //       ③ env 未设置 → effectiveModel == "claude-opus-4-6"（parse 展开裸家族别名）
        List<ILoggingEvent> logs = snapshot();
        assertThat(logs)
            .as("resume 模型必须经 resolve 链 parse（aliasMatchesParentTier 未命中 → 展开别名），"
                + "且 effectiveModel 不能是原始 'opus'")
            .anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("executeResumeAsync D12 模型解析")
                .contains("agentModel=opus")
                .contains("parentModel=claude-sonnet-4-5")
                .doesNotContain("effectiveModel=opus"));
        String expected = resolveExpected();
        if (expected != null) {
            assertThat(logs)
                .as("env 未设置时 agent 'opus' + 父 sonnet → parse 展开为 claude-opus-4-6")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("effectiveModel=claude-opus-4-6"));
        }
    }
}
