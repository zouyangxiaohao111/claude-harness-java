package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * [JS-05 GAP-9] beta-tracing OTEL hook 通道聚焦测试（对齐 CC executeHooks
 * hooks.ts:2070-2084 hook_execution_start + :2946-2963 hook_execution_complete
 * + :5005-5022 getHookDefinitionsForTelemetry）。
 *
 * <p>验证单元（RED→GREEN，E4-XP-W67-04）：
 * <ol>
 *   <li><b>门控关</b>（未设 ENABLE_BETA_TRACING_DETAILED / BETA_TRACING_ENDPOINT）→
 *       执行 PreToolUse 不发射 hook_execution_start/complete（对齐 CC isBetaTracingEnabled
 *       false 时 executeHooks 不发射 OTEL 事件）</li>
 *   <li><b>门控开</b> → 执行 PreToolUse 发射 hook_execution_start + hook_execution_complete，
 *       属性集对齐 CC hooks.ts:2076-2084/2946-2963（hook_event / hook_name / num_hooks /
 *       managed_only / hook_definitions / hook_source + num_success / num_blocking /
 *       num_non_blocking_error / num_cancelled）</li>
 *   <li><b>hook_definitions 载荷形状</b>对齐 CC getHookDefinitionsForTelemetry
 *       （hooks.ts:5005-5022）：programmatic hook → {type:'function', name:'function'}</li>
 *   <li><b>PostToolUse 批路径</b>同样发射（hook_name=PostToolUse:Bash）</li>
 * </ol>
 *
 * <p>WHY（规则九 · 测试验证意图）：beta-tracing 是 CC 的内部/allowlist 门控遥测通道，意图是
 * 在门控开启时把 hook 执行批的形状（hook_definitions 序列化 + 成功/阻断/错误/取消计数）送入
 * OTEL，门控关闭时零副作用。本测试锁住：门控是真正的开关（开→事件，关→零事件），载荷形状
 * 与 CC 一致。
 *
 * <p>门控 env 经 {@link HookRegistry#setBetaTracingEnvOverride(String, String)} 测试缝注入
 * （Java 无法进程内改 env，镜像 MemoryBareModeConfig.setEnvOverride 模式；生产路径恒读真实 env）。
 */
@DisplayName("[JS-05 GAP-9] beta-tracing OTEL hook 通道（hook_execution_start/complete + hook_definitions）")
class HookBetaTracingTelemetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseContext ctx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
    }

    /** 捕获 logOTelEvent(eventName, attrs) 调用的 telemetry spy（不触发真实 OTel emit）。 */
    private static Telemetry capturingTelemetry(List<Object[]> otelEvents) {
        Telemetry telemetry = spy(new Telemetry());
        doAnswer(inv -> {
            otelEvents.add(new Object[]{inv.getArgument(0), inv.getArgument(1)});
            return null;
        }).when(telemetry).logOTelEvent(anyString(), any());
        return telemetry;
    }

    @SuppressWarnings("unchecked")
    private static Object attr(List<Object[]> events, String eventName, String key) {
        for (Object[] e : events) {
            if (eventName.equals(e[0])) {
                Map<String, Object> attrs = (Map<String, Object>) e[1];
                if (attrs.containsKey(key)) {
                    return attrs.get(key);
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("门控关 → PreToolUse 不发射 hook_execution_start/complete（对齐 CC isBetaTracingEnabled false）")
    void gateOff_noBetaTracingEvents() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("hook-a", (toolName, input, c) -> AggregatedHookResult.proceed());
        List<Object[]> otelEvents = new ArrayList<>();
        registry.setTelemetry(capturingTelemetry(otelEvents));
        // 不设 beta env 覆盖 → 门控关（生产默认，零副作用）
        registry.executePreToolUse("Bash", JSON.createObjectNode(), ctx(), "tu-1");

        long startCount = otelEvents.stream()
            .filter(e -> "hook_execution_start".equals(e[0])).count();
        long completeCount = otelEvents.stream()
            .filter(e -> "hook_execution_complete".equals(e[0])).count();
        assertThat(startCount)
            .as("门控关 → hook_execution_start 零发射（CC isBetaTracingEnabled() false 不发射）")
            .isZero();
        assertThat(completeCount)
            .as("门控关 → hook_execution_complete 零发射")
            .isZero();
    }

    @Test
    @DisplayName("门控开 → PreToolUse 发射 hook_execution_start/complete，属性集对齐 CC")
    void gateOn_preToolUseEmitsStartComplete() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("hook-a", (toolName, input, c) -> AggregatedHookResult.proceed());
        registry.registerPreToolUse("hook-b", (toolName, input, c) -> AggregatedHookResult.proceed());
        registry.setBetaTracingEnvOverride("1", "http://localhost:4318");
        List<Object[]> otelEvents = new ArrayList<>();
        registry.setTelemetry(capturingTelemetry(otelEvents));

        registry.executePreToolUse("Bash", JSON.createObjectNode(), ctx(), "tu-1");

        // ── hook_execution_start (CC hooks.ts:2076-2084) ──
        assertThat(attr(otelEvents, "hook_execution_start", "hook_event"))
            .as("hook_event 属性对齐 CC")
            .isEqualTo("PreToolUse");
        assertThat(attr(otelEvents, "hook_execution_start", "hook_name"))
            .as("hook_name = hookEvent:matchQuery (CC hooks.ts:1986)")
            .isEqualTo("PreToolUse:Bash");
        assertThat(attr(otelEvents, "hook_execution_start", "num_hooks"))
            .as("num_hooks = matchingHooks.length（含 programmatic，CC hooks.ts:2079）")
            .isEqualTo("2");
        assertThat(attr(otelEvents, "hook_execution_start", "managed_only"))
            .as("managed_only = String(shouldAllowManagedHooksOnly())")
            .isEqualTo("false");
        assertThat(attr(otelEvents, "hook_execution_start", "hook_source"))
            .as("hook_source = merged（managedOnly false 时，CC hooks.ts:2083-2084）")
            .isEqualTo("merged");
        assertThat(attr(otelEvents, "hook_execution_start", "hook_definitions"))
            .as("hook_definitions 载荷非空")
            .isNotNull();

        // ── hook_execution_complete (CC hooks.ts:2946-2963) ──
        assertThat(attr(otelEvents, "hook_execution_complete", "hook_event"))
            .isEqualTo("PreToolUse");
        assertThat(attr(otelEvents, "hook_execution_complete", "hook_name"))
            .isEqualTo("PreToolUse:Bash");
        assertThat(attr(otelEvents, "hook_execution_complete", "num_hooks"))
            .isEqualTo("2");
        assertThat(attr(otelEvents, "hook_execution_complete", "num_success"))
            .as("2 个 programmatic hook 均 proceed → num_success=2")
            .isEqualTo("2");
        assertThat(attr(otelEvents, "hook_execution_complete", "num_blocking")).isEqualTo("0");
        assertThat(attr(otelEvents, "hook_execution_complete", "num_non_blocking_error")).isEqualTo("0");
        assertThat(attr(otelEvents, "hook_execution_complete", "num_cancelled")).isEqualTo("0");
        assertThat(attr(otelEvents, "hook_execution_complete", "hook_source")).isEqualTo("merged");
    }

    @Test
    @DisplayName("hook_definitions 载荷形状对齐 CC（programmatic → {type:'function', name:'function'}）")
    void hookDefinitionsShape() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("hook-a", (toolName, input, c) -> AggregatedHookResult.proceed());
        registry.setBetaTracingEnvOverride("1", "http://localhost:4318");
        List<Object[]> otelEvents = new ArrayList<>();
        registry.setTelemetry(capturingTelemetry(otelEvents));

        registry.executePreToolUse("Bash", JSON.createObjectNode(), ctx(), "tu-1");

        String defs = (String) attr(otelEvents, "hook_execution_start", "hook_definitions");
        assertThat(defs)
            .as("programmatic Java hook → CC function 分支 {type:'function', name:'function'} (hooks.ts:5014)")
            .isEqualTo("[{\"type\":\"function\",\"name\":\"function\"}]");
    }

    @Test
    @DisplayName("门控开 → PostToolUse 批路径同样发射（hook_name=PostToolUse:Bash）")
    void gateOn_postToolUseEmitsStart() {
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("hook-a",
            (toolName, input, result, c, stopHookActive) -> GenericHook.HookResult.proceed());
        registry.setBetaTracingEnvOverride("1", "http://localhost:4318");
        List<Object[]> otelEvents = new ArrayList<>();
        registry.setTelemetry(capturingTelemetry(otelEvents));

        registry.executePostToolUse("Bash", JSON.createObjectNode(),
            ToolResult.success("tu-1", "ok"), ctx(), false);

        assertThat(attr(otelEvents, "hook_execution_start", "hook_name"))
            .as("PostToolUse 批路径 hook_name 对齐 CC")
            .isEqualTo("PostToolUse:Bash");
        assertThat(attr(otelEvents, "hook_execution_complete", "hook_event"))
            .as("PostToolUse 批路径 complete 事件")
            .isEqualTo("PostToolUse");
    }

    @Test
    @DisplayName("hook_definitions 配置驱动分支形状对齐 CC（command/prompt/http 三种类型）")
    void hookDefinitionsShape_configuredBranches() {
        // [E4 JS-05] 补充配置驱动分支的 hook_definitions 形状验证（既有 hookDefinitionsShape
        //   仅覆盖 programmatic function 分支）。CC getHookDefinitionsForTelemetry
        //   (hooks.ts:5005-5017): command→{type:'command',command} / prompt→{type:'prompt',prompt}
        //   / http→{type:'http',command:url}。Java getHookDefinitionsForTelemetry 对 matched
        //   (配置驱动 HookCommand) 走 COMMAND/PROMPT/HTTP 分支——本测试经 registered matcher 通道
        //   注册三种类型配置 hook，锁全分支形状。
        //   WHY（规则九）：beta-tracing 载荷 hook_definitions 的意图是把"这批 hook 定义"序列化给
        //   OTEL 后端；只测 programmatic 分支会让配置驱动 hook 的形状缺口静默通过。
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, new PermissionRuleValueParser()));
        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Bash",
            null, null, null, null,
            List.of(
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                new PromptHook("summarize", null, null, null, null, null),
                new HttpHook("http://localhost:8080/hook", null, null, null, null, null, null)));
        registry.setBetaTracingEnvOverride("1", "http://localhost:4318");
        List<Object[]> otelEvents = new ArrayList<>();
        registry.setTelemetry(capturingTelemetry(otelEvents));

        registry.executePreToolUse("Bash", JSON.createObjectNode(), ctx(), "tu-1");

        String defs = (String) attr(otelEvents, "hook_execution_start", "hook_definitions");
        assertThat(defs)
            .as("command → {type:'command', command} (CC hooks.ts:5008-5009)")
            .contains("[{\"type\":\"command\",\"command\":\"echo hi\"}");
        assertThat(defs)
            .as("prompt → {type:'prompt', prompt} (CC hooks.ts:5010-5011)")
            .contains("{\"type\":\"prompt\",\"prompt\":\"summarize\"}");
        assertThat(defs)
            .as("http → {type:'http', command:url} (CC hooks.ts:5012-5013)")
            .contains("{\"type\":\"http\",\"command\":\"http://localhost:8080/hook\"}");
    }

    @Test
    @DisplayName("[R-3] 门控开 + 全 internal 批 → start/complete 均不发射（对齐 CC fast-path hooks.ts:2036-2067）")
    void gateOn_allInternal_noOrphanComplete() {
        // [R-3] all-internal 批次 (matched 空 && 无非 internal programmatic hook)：
        //   Java 门控 !matched.isEmpty() || userSnapshotCount > 0 为假 ⟺ CC
        //   userHooks.length == 0 → CC fast-path (hooks.ts:2019-2067) 早退，只跑 callback +
        //   只发 tengu_repl_hook_finished，start/complete 均不发射。
        //   WHY（规则九）：修前 complete 无条件发射（仅方法内 isBetaTracingEnabled 门控），
        //   全 internal 批 start 不发射却 complete 发射 → 孤儿 complete（无匹配 start），
        //   遥测数据异常。修后与 start 同条件门控，锁 CC fast-path 语义，零孤儿。
        HookRegistry registry = new HookRegistry();
        boolean[] internalRan = {false};
        registry.registerPreToolUseInternal("internal-a",
            (toolName, input, c) -> {
                internalRan[0] = true;
                return AggregatedHookResult.proceed();
            });
        registry.setBetaTracingEnvOverride("1", "http://localhost:4318");
        List<Object[]> otelEvents = new ArrayList<>();
        registry.setTelemetry(capturingTelemetry(otelEvents));

        registry.executePreToolUse("Bash", JSON.createObjectNode(), ctx(), "tu-1");

        // 批确实执行了 internal callback（快照非空，CC fast-path 只跑 callback）
        assertThat(internalRan[0])
            .as("all-internal 批 internal callback 仍执行（CC fast-path 只跑 callback）")
            .isTrue();
        long startCount = otelEvents.stream()
            .filter(e -> "hook_execution_start".equals(e[0])).count();
        long completeCount = otelEvents.stream()
            .filter(e -> "hook_execution_complete".equals(e[0])).count();
        assertThat(startCount)
            .as("[R-3] 门控开 + 全 internal → hook_execution_start 零发射（CC fast-path userHooks.length==0 早退）")
            .isZero();
        assertThat(completeCount)
            .as("[R-3] 门控开 + 全 internal → hook_execution_complete 零发射（无孤儿 complete，与 start 同条件门控）")
            .isZero();
    }
}
