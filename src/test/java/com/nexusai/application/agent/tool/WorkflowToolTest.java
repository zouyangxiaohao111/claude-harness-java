package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.impl.WorkflowTool;
import com.nexusai.application.agent.workflow.LaunchInput;
import com.nexusai.application.agent.workflow.LaunchResult;
import com.nexusai.application.agent.workflow.WorkflowPorts;
import com.nexusai.application.agent.workflow.WorkflowService;
import com.nexusai.application.agent.workflow.WorkflowServiceImpl;
import com.nexusai.application.agent.workflow.progress.RunProgress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkflowTool 测试 · 对齐 CC {@code tool/WorkflowTool.ts:84-172 call()} +
 * {@code tool/schema.ts workflowInputSchema}。
 *
 * <p><b>WHY（P1 D-5）</b>：WorkflowTool 是 WORKFLOW_SCRIPTS 门控下的真实现（从 OPD-10 注册桩
 * 升级），其 inputSchema（8 字段全 optional + additionalProperties:false）与 execute 的
 * 「三源解析 → parseScript 快速校验 → WorkflowService.launch detached run」链路均无测试，
 * 改动易静默回归。</p>
 *
 * <p><b>覆盖</b>：
 * <ol>
 *   <li>inputSchema 8 字段反射校验（全 optional + additionalProperties:false）</li>
 *   <li>execute → WorkflowService.launch（detached run，返回 run_id）</li>
 *   <li>脚本校验失败 → 错误回模型（不进后台，launch 不被调用）</li>
 *   <li>maxConcurrency min1/max16</li>
 * </ol>
 * 另附：unknownKeysPolicy=STRIP（对齐 CC z.object strip）、isEnabled 门控、renderToolUseMessage。
 */
class WorkflowToolTest {

    private static final Field CACHED_FIELD = cachedField();

    /** 反射取 WorkflowServiceImpl.cached 私有静态字段（测试注入 fake service 用）。 */
    private static Field cachedField() {
        try {
            Field f = WorkflowServiceImpl.class.getDeclaredField("cached");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FakeWorkflowService fakeService;

    @BeforeEach
    void injectFakeService() throws Exception {
        fakeService = new FakeWorkflowService();
        CACHED_FIELD.set(null, fakeService);
    }

    @AfterEach
    void restoreSingleton() throws Exception {
        CACHED_FIELD.set(null, null);
    }

    // ═══════════════════ inputSchema 8 字段反射校验 ═══════════════════

    @Test
    @DisplayName("inputSchema 恰 8 字段 + additionalProperties:false + 无 required（全 optional）")
    void inputSchemaHasExactlyEightOptionalFields() {
        JsonNode schema = new WorkflowTool().inputSchema();

        assertEquals("object", schema.get("type").asText());
        assertFalse(schema.has("required"), "CC z.object 全字段 optional，无 required 数组（schema.ts:4-41）");
        assertFalse(schema.get("additionalProperties").asBoolean(),
                "广告层 additionalProperties=false（zod v4 z.object toJSONSchema 实测，WorkflowTool.java:209）");

        Set<String> fields = new java.util.HashSet<>();
        schema.get("properties").fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of(
                        "script", "name", "scriptPath", "args",
                        "resumeFromRunId", "description", "title", "maxConcurrency"),
                fields,
                "8 字段逐字对齐 CC workflowInputSchema（schema.ts:4-41，非任务描述里的 meta/isolation/model）");
    }

    @Test
    @DisplayName("maxConcurrency：type=integer + minimum=1 + maximum=16（z.number().int().min(1).max(16)）")
    void maxConcurrencyBoundsAreMin1Max16() {
        JsonNode mc = new WorkflowTool().inputSchema().get("properties").get("maxConcurrency");

        assertEquals("integer", mc.get("type").asText());
        assertEquals(1, mc.get("minimum").asInt(), "z.min(1)");
        assertEquals(16, mc.get("maximum").asInt(), "z.max(16)（hard ceiling，默认 3）");
    }

    @Test
    @DisplayName("unknownKeysPolicy=STRIP（对齐 CC z.object 默认 strip，不拒未知键）")
    void unknownKeysPolicyIsStrip() {
        assertEquals(Tool.UnknownKeysPolicy.STRIP,
                new WorkflowTool().unknownKeysPolicy(),
                "CC schema.ts:4 z.object 默认 strip（WorkflowTool.java:213-223）");
    }

    @Test
    @DisplayName("isEnabled 门控：WORKFLOW_SCRIPTS 关（默认）→ false；开 → true")
    void isEnabledGatedByWorkflowScriptsFlag() {
        // WHY: CC tools.ts:129-133 WorkflowTool = feature('WORKFLOW_SCRIPTS') ? create : null。
        assertFalse(new WorkflowTool().isEnabled(), "默认 FeatureFlags.ALL_DISABLED → flag 关不暴露");

        // 21 参 record 位置序：workflowScripts = index 14（FeatureFlags.java:85-103）
        FeatureFlags enabled = new FeatureFlags(
                false, false, false, false, false, false, false, false, false, false,
                false, false, false, false, true, false, false, false, false, false, false);
        assertTrue(new WorkflowTool(enabled).isEnabled(), "flag 开 → 工具暴露");
    }

    // ═══════════════════ execute → WorkflowService.launch（detached run） ═══════════════════

    @Test
    @DisplayName("合法内联脚本 → launch 被调，回 'Workflow started' + run_id（detached run）")
    void executeLaunchesDetachedRunAndReturnsRunId() {
        WorkflowTool tool = new WorkflowTool();
        ToolUseBlock call = new ToolUseBlock("toolu-1", WorkflowTool.NAME,
                JsonNodeFactory.instance.objectNode().put("script", "return 42"));

        AgentToolResult<?> result = tool.execute(call);

        assertEquals(1, fakeService.launchCalls(), "合法脚本必须到达 WorkflowService.launch（CC WorkflowTool.ts:139-157）");
        assertEquals("return 42", fakeService.lastInput.script(), "inline script 原样透传");
        assertTrue(result instanceof ToolResult, "成功 → ToolResult");
        String data = ((ToolResult<?>) result).data().toString();
        assertTrue(data.contains("Workflow started (running in the background)."),
                "启动消息对齐 CC WorkflowTool.ts:159-171");
        assertTrue(data.contains("run_id: run-123"), "launch 返回 runId 透出给模型");
        assertTrue(data.contains("script: <inline run run-123>"), "inline 无 workflowFile → <inline run <id>> 占位");
    }

    @Test
    @DisplayName("maxConcurrency 透传至 LaunchInput（引擎侧信号量 clamp，工具不吞）")
    void executePassesMaxConcurrencyThrough() {
        WorkflowTool tool = new WorkflowTool();
        ToolUseBlock call = new ToolUseBlock("toolu-2", WorkflowTool.NAME,
                JsonNodeFactory.instance.objectNode()
                        .put("script", "return 42")
                        .put("maxConcurrency", 6));

        tool.execute(call);

        assertEquals(Integer.valueOf(6), fakeService.lastInput.maxConcurrency(),
                "maxConcurrency 经 LaunchInput 透传（WorkflowTool.java:324-328）");
    }

    // ═══════════════════ 脚本校验失败 → 错误回模型，不进后台 ═══════════════════

    @Test
    @DisplayName("脚本校验失败 → ToolResult.error 回模型，launch 不被调用（不进后台）")
    void scriptValidationFailureReturnsErrorWithoutLaunch() {
        WorkflowTool tool = new WorkflowTool();
        // CC WorkflowTool.ts:98-107 parseScript 快速校验；import 违反沙箱规则 → ScriptError
        ToolUseBlock call = new ToolUseBlock("toolu-3", WorkflowTool.NAME,
                JsonNodeFactory.instance.objectNode()
                        .put("script", "import { foo } from 'bar'\nreturn foo()"));

        AgentToolResult<?> result = tool.execute(call);

        assertEquals(0, fakeService.launchCalls(),
                "校验失败必须短路（CC WorkflowTool.ts:100-107：on failure return error, do not enter background）");
        assertTrue(result instanceof ToolResult, "错误也走 ToolResult（Tool 契约不抛异常）");
        String data = ((ToolResult<?>) result).data().toString();
        assertTrue(data.contains("script validation failed"), "错误消息前缀逐字对齐 CC WorkflowTool.ts:104");
        assertTrue(data.contains("import is not supported"), "透出 parser 的具体原因让模型自纠");
    }

    @Test
    @DisplayName("三源全缺 → 'One of script, name, or scriptPath must be provided' 错误")
    void missingScriptSourceReturnsError() {
        WorkflowTool tool = new WorkflowTool();
        ToolUseBlock call = new ToolUseBlock("toolu-4", WorkflowTool.NAME,
                JsonNodeFactory.instance.objectNode().put("description", "no source"));

        AgentToolResult<?> result = tool.execute(call);

        assertEquals(0, fakeService.launchCalls());
        assertTrue(((ToolResult<?>) result).data().toString()
                .contains("One of script, name, or scriptPath must be provided"));
    }

    // ═══════════════════ renderToolUseMessage（CC WorkflowTool.ts:76-82） ═══════════════════

    @Test
    @DisplayName("renderToolUseMessage：resume / name / scriptPath / inline / unknown")
    void renderToolUseMessagePriority() {
        WorkflowTool tool = new WorkflowTool();
        JsonNodeFactory f = JsonNodeFactory.instance;

        assertEquals("Workflow resume: r1",
                tool.renderToolUseMessage(f.objectNode().put("resumeFromRunId", "r1")));
        assertEquals("Workflow: named",
                tool.renderToolUseMessage(f.objectNode().put("name", "named")));
        assertEquals("Workflow: /tmp/x.js",
                tool.renderToolUseMessage(f.objectNode().put("scriptPath", "/tmp/x.js")));
        assertEquals("Workflow: inline",
                tool.renderToolUseMessage(f.objectNode().put("script", "return 1")));
        assertEquals("Workflow: unknown",
                tool.renderToolUseMessage(f.objectNode()));
    }

    // ═══════════════════ Fake WorkflowService ═══════════════════

    /** 记录 launch 调用的 fake service（仅 execute→launch 链路需要 launch；其余接口空实现）。 */
    static final class FakeWorkflowService implements WorkflowService {

        private int launchCount;
        LaunchInput lastInput;

        /** launch 调用次数（测试断言用）。 */
        int launchCalls() {
            return launchCount;
        }

        @Override
        public WorkflowPorts ports() {
            return null;
        }

        @Override
        public CompletableFuture<LaunchResult> launch(LaunchInput input, ToolUseContext ctx, Object canUseTool) {
            launchCount++;
            lastInput = input;
            return CompletableFuture.completedFuture(new LaunchResult("run-123", null));
        }

        @Override
        public void kill(String runId) {
        }

        @Override
        public boolean killAgent(String runId, int agentId) {
            return false;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<RunProgress> listRuns() {
            return List.of();
        }

        @Override
        public RunProgress getRun(String runId) {
            return null;
        }

        @Override
        public CompletableFuture<RunProgress> getRunAsync(String runId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void loadPersistedRuns() {
        }

        @Override
        public Runnable subscribe(Runnable listener) {
            return () -> {
            };
        }

        @Override
        public List<String> listNamed(String workflowDir) {
            return List.of();
        }
    }
}
