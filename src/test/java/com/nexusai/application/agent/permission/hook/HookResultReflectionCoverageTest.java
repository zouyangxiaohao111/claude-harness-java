package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M · P3-5 · HookResult 反射构造路径覆盖测试.
 *
 * <p><b>WHY (意图验证)</b>: Session I P3-1 (HookResult 21 字段对齐 CC Open-ClaudeCode/src/utils/hooks.ts:338-357)
 * + Session J (HookResult reason legacy 清理, AggregatedHookResult 字段对齐) 落地后, 必须验证:
 *   <li>{@link HookResult} 14 字段 record 反射验证 (字段数对齐 CC + hook + S07 permissionRequestResult)</li>
 *   <li>{@link HookResult} 4 factory methods: proceed / stop / stop-with-blockingError / withRetry</li>
 *   <li>{@link AggregatedHookResult} 16 字段 record 反射验证</li>
 *   <li>关键 caller (HookRegistry / FrontmatterHooks 等) 的构造路径覆盖</li>
 * </ol>
 *
 * <p>反射构造路径覆盖测试的 WHY: Session J 整合期间发现 AggregatedHookResult 字段数与 CC 不一致
 * (16 vs 18), 静态分析 + 测试断言都会漏掉 record 字段数错误 (M2.1 教训). 反射验证字段数 +
 * factory methods 可在编译期/单测期捕获 contract drift, 避免 Session J 类似问题重现.
 *
 * <h2>测试用例 (3 项)</h2>
 * <ol>
 *   <li>{@link #hookResult_recordHas14Fields()} — HookResult 14 字段数反射验证</li>
 *   <li>{@link #hookResult_factoryMethodsPresent()} — HookResult 4 factory methods 反射验证</li>
 *   <li>{@link #aggregatedHookResult_recordHas16Fields()} — AggregatedHookResult 16 字段数反射验证</li>
 * </ol>
 *
 */
class HookResultReflectionCoverageTest {

    // ─────────── 1. HookResult 14 字段反射验证 ───────────

    @Test
    @DisplayName("M4.2-R1 + S07 + △-01 + IMP-DA-01 HookResult record 必须有 18 字段 (H3 加 hook + S07 恢复 permissionRequestResult + △-01 恢复 4 awaiting; allBlockingErrors 已删)")
    void hookResult_recordHas14Fields() {
        // [CC 真源] Open-ClaudeCode/src/utils/hooks.ts:338-357 (HookResult interface 含
        //   permissionRequestResult 字段) — Session M P1-2 撤回 5 awaiting 字段
        //   (initialUserMessage/elicitationResponse/watchPaths/elicitationResultResponse →
        //   ParsedHookJSONOutput 承载; permissionRequestResult 于 S07 恢复顶层回填,
        //   对齐 CC hooks.ts:2882-2886 yield), Session H3 加 hook (hooks.ts:356) → 14 字段.
        //   [2026-08-12 △-01] 恢复 4 awaiting 字段到顶层 (对齐 CC hooks.ts:348/352-355) → 18 字段.
        //   [IMP-DA-01 TY-01] allBlockingErrors (原 IMP-HOOKS-S6 追加, 19 字段) 已删除 —
        //   对齐 CC HookResult 无此字段; blocking 附件改由折叠层并入 message 列表逐条注入.
        Constructor<?>[] ctors = HookResult.class.getDeclaredConstructors();
        assertThat(ctors)
            .as("HookResult 必须有 1 个 canonical constructor (record)")
            .hasSize(1);

        Constructor<?> ctor = ctors[0];
        assertThat(ctor.getParameterCount())
            .as("HookResult canonical constructor 必须接受 18 参数 (对齐 CC HookResult 字段)")
            .isEqualTo(18);

        // 反射验证所有字段名 (按 CC 真源顺序, snake_case → camelCase)
        String[] fieldNames = Arrays.stream(HookResult.class.getRecordComponents())
            .map(rc -> rc.getName())
            .toArray(String[]::new);

        assertThat(fieldNames)
            .as("HookResult 字段名集合必须包含 18 字段 (对齐 CC HookResult, 无 allBlockingErrors)")
            .containsExactlyInAnyOrder(
                "preventContinuation",
                "blockingError",
                "systemMessages",
                "additionalContexts",
                "message",
                "updatedInput",
                "updatedMCPToolOutput",
                "retry",
                "hookPermissionDecisionReason",
                "outcome",
                "stopReason",
                "permissionBehavior",
                "permissionRequestResult",
                "hook",
                "initialUserMessage",
                "watchPaths",
                "elicitationResponse",
                "elicitationResultResponse"
            );
    }

    // ─────────── 2. HookResult 4 factory methods 反射验证 ───────────

    @Test
    @DisplayName("M4.2-R2 HookResult 必须有 4 个 factory methods (proceed/stop/stop with blockingError/withRetry)")
    void hookResult_factoryMethodsPresent() {
        // [CC 真源] HookResult factory semantics:
        //   - proceed()    → 全部字段 null/false (passthrough, 0 干预)
        //   - stop(reason) → preventContinuation=true + reason (CC stop hook)
        //   - stop(reason, blockingError) → + blockingError (CC exit 2 stderr)
        //   - withRetry()  → retry=true (CC hooks.ts:2887-2892 yield {retry})
        Method[] declaredMethods = HookResult.class.getDeclaredMethods();
        String[] factoryNames = Arrays.stream(declaredMethods)
            .filter(m -> Modifier.isStatic(m.getModifiers()))
            .filter(m -> m.getReturnType() == HookResult.class)
            .map(Method::getName)
            .toArray(String[]::new);

        assertThat(factoryNames)
            .as("HookResult factory methods 必须包含 proceed, stop (1-arg), stop (2-arg), withRetry")
            .contains("proceed", "stop", "withRetry");

        // 验证 stop 有 1-arg 和 2-arg 两个重载
        long stopOverloads = Arrays.stream(declaredMethods)
            .filter(m -> Modifier.isStatic(m.getModifiers()))
            .filter(m -> m.getName().equals("stop"))
            .filter(m -> m.getReturnType() == HookResult.class)
            .count();
        assertThat(stopOverloads)
            .as("HookResult.stop 必须有 2 个重载 (1-arg reason, 2-arg reason+blockingError)")
            .isEqualTo(2);
    }

    // ─────────── 3. AggregatedHookResult 16 字段反射验证 ───────────

    @Test
    @DisplayName("M4.2-A1 + IMP-HOOKS-S6 + IMP-DA-02 AggregatedHookResult record 必须有 16 字段 (对齐 CC AHR)")
    void aggregatedHookResult_recordHas16Fields() {
        // [CC 真源] Open-ClaudeCode/src/utils/hooks.ts:359-376 (AggregatedHookResult, 16 字段)
        //   Session P0-3 落地 16 字段 (与 HookResult 21 字段语义重叠但不同 record).
        //   [IMP-HOOKS-S6 CCJ-T6-19] 曾追加 systemMessage (hook_system_message 附件载体) → 17 字段.
        //   [IMP-DA-02 DEL-WF1-TY-02] systemMessages 聚合字段已删除 → 16 字段 (对齐 CC AHR,
        //   hooks.ts:359-376 无此字段; systemMessage 经 foldSystemMessages 折叠进 message 通道,
        //   StreamingToolExecutor.injectPreToolUseHookAttachments 逐条 appendAttachment).
        Constructor<?>[] ctors = AggregatedHookResult.class.getDeclaredConstructors();
        assertThat(ctors)
            .as("AggregatedHookResult 必须有 1 个 canonical constructor (record)")
            .hasSize(1);

        Constructor<?> ctor = ctors[0];
        assertThat(ctor.getParameterCount())
            .as("AggregatedHookResult canonical constructor 必须接受 16 参数 (对齐 CC AHR 16 字段, systemMessages 已删)")
            .isEqualTo(16);

        // 验证 factory method proceed() 存在
        Method[] declaredMethods = AggregatedHookResult.class.getDeclaredMethods();
        boolean hasProceed = Arrays.stream(declaredMethods)
            .anyMatch(m -> Modifier.isStatic(m.getModifiers())
                && m.getName().equals("proceed")
                && m.getReturnType() == AggregatedHookResult.class);
        assertThat(hasProceed)
            .as("AggregatedHookResult 必须有 proceed() factory method")
            .isTrue();
    }

}
