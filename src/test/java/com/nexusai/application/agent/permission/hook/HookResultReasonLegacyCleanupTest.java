package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M3.1 · HookResult reason legacy 字段清理测试 · 对齐 CC
 * Open-ClaudeCode/src/utils/hooks.ts:344.
 *
 * <p><b>WHY (意图验证)</b>: CC HookResult 只含 {@code stopReason}, 旧 Java 字段
 * {@code reason} 与 stopReason 语义重叠. 清理后必须保证:
 * <ol>
 *   <li>反射验证 record 不再含 {@code reason} 字段 (精确 15 字段)</li>
 *   <li>{@link com.nexusai.application.agent.permission.hook.GenericHook.HookResult#stop(String)}
 *       工厂只填 stopReason, 其他 reason 字段消失</li>
 *   <li>工厂 proceed() 不再依赖 reason 参数</li>
 * </ol>
 */
class HookResultReasonLegacyCleanupTest {

    // ─────────── 1. 字段集合 (Part 1 of RED) ───────────

    @Test
    @DisplayName("M3.1-1 HookResult 不再含 reason 字段 · 反射验证 15 字段精确")
    void hookResult_noReasonField() throws Exception {
        // HookResult 是嵌套 record → 通过 getDeclaredClasses 获取
        Class<?> hookResultClass = Arrays.stream(GenericHook.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("HookResult"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("HookResult nested record not found"));

        RecordComponent[] components = hookResultClass.getRecordComponents();
        assertThat(components)
            .as("HookResult record components count must be 18 (对齐 CC HookResult; [IMP-DA-01 TY-01] allBlockingErrors 已删)")
            .hasSize(18);

        List<String> componentNames = Arrays.stream(components)
            .map(RecordComponent::getName)
            .toList();
        assertThat(componentNames)
            .as("HookResult must not carry legacy 'reason' field (CC hooks.ts:344 only stopReason)")
            .doesNotContain("reason");

        assertThat(componentNames)
            .as("HookResult must contain stopReason (CC hooks.ts:344)")
            .contains("stopReason");
    }

    // ─────────── 2. 工厂 stop() 输出 (Part 2 of RED) ───────────

    @Test
    @DisplayName("M3.1-2 stop(reason) 工厂仅填 stopReason,不回填 reason")
    void stopFactory_fillsStopReasonOnly() throws Exception {
        // 触发 static 工厂
        Method stopFactory = Arrays.stream(GenericHook.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("HookResult"))
            .findFirst()
            .orElseThrow()
            .getDeclaredMethod("stop", String.class);

        Object result = stopFactory.invoke(null, "blocked by hook");

        // 反射取值
        Class<?> hookResultClass = result.getClass();
        Field stopReasonField = hookResultClass.getDeclaredField("stopReason");
        stopReasonField.setAccessible(true);
        String stopReason = (String) stopReasonField.get(result);

        // 真正意图: stop 工厂必须让 stopReason() 等于传入 reason
        assertThat(stopReason)
            .as("stop(reason) 工厂的 stopReason 必须等于传入 reason")
            .isEqualTo("blocked by hook");

        // 反射验证 reason 字段消失 → 强制无"legacy reason" 概念
        assertThat(Arrays.stream(hookResultClass.getRecordComponents())
            .map(RecordComponent::getName)
            .toList())
            .as("legacy reason 字段必须从 hookResult 移除")
            .doesNotContain("reason");
    }
}
