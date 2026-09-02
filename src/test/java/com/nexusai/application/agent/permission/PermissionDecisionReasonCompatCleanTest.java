package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [permissions_v3 WF-1] PermissionDecisionReason.Classifier 3-arg（含 mode 字段）兼容壳清理验证 ·
 * DEL-WF1-01.
 *
 * <p><b>WHY (意图验证)</b>: 旧实现 Classifier record 有 3 个字段 {@code (classifier, mode, reason)}
 * （R32-b13 B9 引入）, 偏离 CC 类型契约 —— CC {@code types/permissions.ts:303-307} 分类器变体仅
 * {@code {type:'classifier', classifier, reason}} 无 mode 字段, auto-mode 语义由
 * {@code classifier} 字段值 {@code 'auto-mode'} 承载（CC 构造侧 permissions.ts:907/923 +
 * toolExecution.ts:1078 retry hook 触发条件）. 按用户拍板 OD-WF1-02（DEL-WF1-01）删除 mode 字段.
 * 本测试反射验证 Classifier record 只有 2-arg canonical ctor {@code (classifier, reason)},
 * 无 3-arg ctor（mode 兼容壳已删）.
 *
 * <p><b>Pattern #11 bypass 关闭</b>: 兼容壳本质上就是"为旧代码绕开新契约"的 bypass, 必须按
 * 用户授权 + Pattern #11 关闭, 不允许"skip guard" 参数保留.
 *
 * <h2>测试用例 (3 项)</h2>
 * <ol>
 *   <li>{@link #classifierConstructor_no3ArgVersion()} — 反射验证无 3-arg ctor（mode 兼容壳已删）</li>
 *   <li>{@link #classifierConstructor_has2ArgCanonicalOnly()} — 反射验证仅有 2-arg canonical ctor</li>
 *   <li>{@link #classifierCtor_classifierFieldCarriesAutoMode()} — classifier 字段承载 auto-mode
 *       （CC permissions.ts:907/923 构造侧语义）</li>
 * </ol>
 *
 * @since permissions_v3 WF-1（2026-08-17）
 */
class PermissionDecisionReasonCompatCleanTest {

    // ─────────── 1. 反射验证无 3-arg ctor ───────────

    @Test
    @DisplayName("DEL-WF1-01 classifierConstructor_no3ArgVersion · 反射验证无 3-arg Classifier ctor")
    void classifierConstructor_no3ArgVersion() {
        Constructor<?>[] constructors = PermissionDecisionReason.Classifier.class.getDeclaredConstructors();
        assertThat(constructors)
            .as("Classifier record 应只有 1 个 canonical 2-arg ctor (3-arg mode 兼容壳 DEL-WF1-01 已删)")
            .hasSize(1);

        // 显式验证无 3-arg ctor: 遍历所有 ctor, 检查参数数量 != 3
        boolean has3ArgCtor = Arrays.stream(constructors)
            .anyMatch(c -> c.getParameterCount() == 3);
        assertThat(has3ArgCtor)
            .as("DEL-WF1-01 删除 3-arg Classifier ctor 后, 反射必须找不到任何 3 参数 ctor")
            .isFalse();
    }

    // ─────────── 2. 反射验证仅有 2-arg canonical ctor ───────────

    @Test
    @DisplayName("DEL-WF1-01 classifierConstructor_has2ArgCanonicalOnly · 仅有 2-arg canonical ctor")
    void classifierConstructor_has2ArgCanonicalOnly() {
        Constructor<?>[] constructors = PermissionDecisionReason.Classifier.class.getDeclaredConstructors();
        Constructor<?> canonical = constructors[0];

        assertThat(canonical.getParameterCount())
            .as("canonical ctor 应为 2 参 (classifier, reason)")
            .isEqualTo(2);

        // 参数类型断言: (String, String) — 对齐 CC classifier 变体 {classifier: string, reason: string}
        assertThat(canonical.getParameterTypes())
            .as("canonical ctor 参数类型应为 (String, String)")
            .containsExactly(String.class, String.class);
    }

    // ─────────── 3. classifier 字段承载 auto-mode 语义 ───────────

    @Test
    @DisplayName("DEL-WF1-01 classifierCtor_classifierFieldCarriesAutoMode · classifier 字段承载 auto-mode")
    void classifierCtor_classifierFieldCarriesAutoMode() {
        // [WF-1] auto-mode 语义落入 classifier 字段（CC permissions.ts:907/923 构造侧）
        PermissionDecisionReason.Classifier autoMode =
            new PermissionDecisionReason.Classifier("auto-mode", "reason text");

        assertThat(autoMode.classifier()).isEqualTo("auto-mode");
        assertThat(autoMode.reason()).isEqualTo("reason text");

        // bash_allow 等其他 classifier 值不受影响（不触发 retry hook）
        PermissionDecisionReason.Classifier bashAllow =
            new PermissionDecisionReason.Classifier("bash_allow", "reason text");
        assertThat(bashAllow.classifier()).isEqualTo("bash_allow");
    }
}
