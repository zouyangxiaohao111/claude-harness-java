package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.PermissionDecisionReason.SandboxOverride.SandboxOverrideReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SandboxOverrideReason enum 化测试 · 对齐 CC {@code Open-ClaudeCode/src/types/permissions.ts:299-302}
 *
 * <p><b>WHY (意图验证)</b>: 把 {@link PermissionDecisionReason.SandboxOverride#reason} 从
 * {@code String} 改为 enum 是契约硬化——CC 真源字面量联合 {@code 'excludedCommand' |
 * 'dangerouslyDisableSandbox'} 是封闭集合 (closed set)，任何拼写错误都会让
 * Java 端 OTel source 映射静默失效。本测试 3 项强制保证：
 *
 * <ul>
 *   <li><b>L1 严格对齐 CC</b>: 枚举值与 CC 字面量 1:1 对应（{@link SandboxOverrideReason#ccLiteral}）
 *       — 拼写错误会立即在测试中暴露</li>
 *   <li><b>L2 拒收 null</b>: compact ctor 拒绝 null reason (向后兼容 R32-b12 既有防御)</li>
 *   <li><b>L3 OTel source 映射</b>: {@code decisionReasonToOTelSource} 对 SandboxOverride
 *       显式返回 {@code "config"} (CC 真源 default case: toolExecution.ts:240-244)</li>
 * </ul>
 *
 * <p><b>WHY RED-GREEN 必跑 (Pattern #14)</b>: 任何修改 SandboxOverride.reason 字段类型的
 * commit 都必须让本测试先 RED 后 GREEN——只绿过的测试无验证力。
 */
class SandboxOverrideReasonEnumTest {

    @Test
    @DisplayName("L1: enum 严格对齐 CC 字面量 'excludedCommand' 和 'dangerouslyDisableSandbox'")
    void enumValuesAlignWithCcLiterals() {
        // CC 字面量联合 'excludedCommand' | 'dangerouslyDisableSandbox' (types/permissions.ts:299-302)
        // 封闭集合, 不允许第 3 个值
        SandboxOverrideReason[] values = SandboxOverrideReason.values();
        assertThat(values)
            .as("SandboxOverrideReason 必须严格 2 值, 对齐 CC 字面量联合")
            .containsExactly(
                SandboxOverrideReason.EXCLUDED_COMMAND,
                SandboxOverrideReason.DANGEROUSLY_DISABLE_SANDBOX
            );
        // CC 原始字面量严格匹配 (snake_case friendly mapping)
        // Java enum 是 SCREAMING_SNAKE, CC literal 是 camelCase
        assertThat(SandboxOverrideReason.EXCLUDED_COMMAND.ccLiteral())
            .as("EXCLUDED_COMMAND.ccLiteral 必须等于 CC 'excludedCommand'")
            .isEqualTo("excludedCommand");
        assertThat(SandboxOverrideReason.DANGEROUSLY_DISABLE_SANDBOX.ccLiteral())
            .as("DANGEROUSLY_DISABLE_SANDBOX.ccLiteral 必须等于 CC 'dangerouslyDisableSandbox'")
            .isEqualTo("dangerouslyDisableSandbox");
    }

    @Test
    @DisplayName("L2: compact ctor 拒收 null reason (向后兼容 R32-b12 既有防御)")
    void compactCtorRejectsNullReason() {
        assertThatThrownBy(() -> new PermissionDecisionReason.SandboxOverride(null))
            .as("SandboxOverride.reason (enum) null 必须立即抛 IllegalArgumentException")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("L3: decisionReasonToOTelSource 对 SandboxOverride 返 'config' (CC default case)")
    void decisionSourceForSandboxOverrideReturnsConfig() {
        // EXCLUDED_COMMAND → "config" (CC toolExecution.ts:240-244 default case)
        var reasonExcluded = new PermissionDecisionReason.SandboxOverride(
            SandboxOverrideReason.EXCLUDED_COMMAND);
        assertThat(PermissionDecisionReason.decisionReasonToOTelSource(
                reasonExcluded, PermissionBehavior.ALLOW))
            .as("SandboxOverride(EXCLUDED_COMMAND) + ALLOW → 'config'")
            .isEqualTo("config");
        // DANGEROUSLY_DISABLE_SANDBOX → "config" (CC default case, 与 behavior 无关)
        var reasonDangerously = new PermissionDecisionReason.SandboxOverride(
            SandboxOverrideReason.DANGEROUSLY_DISABLE_SANDBOX);
        assertThat(PermissionDecisionReason.decisionReasonToOTelSource(
                reasonDangerously, PermissionBehavior.DENY))
            .as("SandboxOverride(DANGEROUSLY_DISABLE_SANDBOX) + DENY → 'config'")
            .isEqualTo("config");
    }
    @Test
    @DisplayName("L4: fromString 解析 CC 字面量 'excludedCommand' → EXCLUDED_COMMAND (REQ-F-03)")
    void fromStringParsesExcludedCommand() {
        assertThat(SandboxOverrideReason.fromString("excludedCommand"))
            .as("CC 字面量 'excludedCommand' → EXCLUDED_COMMAND")
            .isEqualTo(SandboxOverrideReason.EXCLUDED_COMMAND);
    }

    @Test
    @DisplayName("L4: fromString 解析 CC 字面量 'dangerouslyDisableSandbox' → DANGEROUSLY_DISABLE_SANDBOX (REQ-F-03)")
    void fromStringParsesDangerouslyDisableSandbox() {
        assertThat(SandboxOverrideReason.fromString("dangerouslyDisableSandbox"))
            .as("CC 字面量 'dangerouslyDisableSandbox' → DANGEROUSLY_DISABLE_SANDBOX")
            .isEqualTo(SandboxOverrideReason.DANGEROUSLY_DISABLE_SANDBOX);
    }

    @Test
    @DisplayName("L4: fromString 对 null / 未知 / 大小写变体 / 别名返回 null (封闭集合不猜测)")
    void fromStringRejectsUnknownLiterals() {
        // 严格语义 (仿 TaskStatus.fromString, tasks.ts:333-339 safeParse→null):
        // 封闭集合外一律 null, 不做大小写折叠/蛇形别名/未来字面量猜测 ——
        // 拼写错误立即暴露, 不会静默映射成错误语义.
        assertThat(SandboxOverrideReason.fromString(null))
            .as("null → null")
            .isNull();
        assertThat(SandboxOverrideReason.fromString(""))
            .as("空串 → null")
            .isNull();
        assertThat(SandboxOverrideReason.fromString("ExcludedCommand"))
            .as("大小写变体 → null (CC 字面量严格小写 camelCase)")
            .isNull();
        assertThat(SandboxOverrideReason.fromString("excluded_command"))
            .as("蛇形别名 → null (CC 无 alias)")
            .isNull();
        assertThat(SandboxOverrideReason.fromString("adminOverride"))
            .as("未来第 3 字面量 → null (当前封闭集合仅 2 值)")
            .isNull();
    }
}