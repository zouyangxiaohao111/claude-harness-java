package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RV-10 · WorkingDirectorySource 8 值对齐 CC 类型别名语义。
 *
 * <p><b>WHY（规则九：测试验证意图，而非仅验证行为）</b>：
 * CC 中 {@code WorkingDirectorySource} 不是独立类型，而是 {@code PermissionRuleSource}
 * 的纯类型别名（types/permissions.ts:138 {@code export type WorkingDirectorySource =
 * PermissionRuleSource}）。旧 Java 代码曾误建一个 5 值独立 enum（USER/PROJECT/CLI/SYSTEM/SESSION），
 * 其中 SYSTEM 在 CC 8 值中无对应，构成双轨/影子路径。本测试锁死两条契约：
 * <ol>
 *   <li>{@link PermissionRuleSource} 恰为 CC 的 8 值（snake_case 精确对应）且不含 SYSTEM；</li>
 *   <li>{@link AdditionalWorkingDirectory#source()} 字段类型就是
 *       {@link PermissionRuleSource}（别名语义，非独立 enum）。</li>
 * </ol>
 *
 * <p><b>对齐锚点（CC 真源，行号当次 read 自验）</b>：
 * types/permissions.ts:54-62（PermissionRuleSource 8 值）、
 * types/permissions.ts:138（WorkingDirectorySource = PermissionRuleSource）、
 * types/permissions.ts:145（source: WorkingDirectorySource）。
 */
@DisplayName("RV-10 · WorkingDirectorySource 8 值对齐 CC 纯类型别名")
class WorkingDirectorySourceTest {

    /** CC types/permissions.ts:55-62 的 8 个 snake_case 值 → Java 常量名映射。 */
    private static final Set<String> CC_8_CONSTANTS = Set.of(
            "USER_SETTINGS",      // userSettings
            "PROJECT_SETTINGS",   // projectSettings
            "LOCAL_SETTINGS",     // localSettings
            "FLAG_SETTINGS",      // flagSettings
            "POLICY_SETTINGS",    // policySettings
            "CLI_ARG",            // cliArg
            "COMMAND",            // command
            "SESSION"             // session
    );

    @Test
    @DisplayName("PermissionRuleSource 恰为 CC 8 值且无 SYSTEM 常量")
    void permissionRuleSourceHasExactlyCc8Values() {
        PermissionRuleSource[] values = PermissionRuleSource.values();
        assertThat(values).hasSize(8);

        Set<String> actual = Arrays.stream(values)
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertThat(actual).isEqualTo(CC_8_CONSTANTS);

        // SYSTEM 是旧 5 值 enum 的独有残留，CC 8 值中无对应——必须不存在
        assertThat(actual).doesNotContain("SYSTEM");
    }

    @Test
    @DisplayName("AdditionalWorkingDirectory.source 类型 = PermissionRuleSource（别名语义，非独立 enum）")
    void additionalWorkingDirectorySourceFieldIsPermissionRuleSource() {
        Field sourceField;
        try {
            sourceField = AdditionalWorkingDirectory.class.getDeclaredField("source");
        } catch (NoSuchFieldException e) {
            throw new AssertionError("AdditionalWorkingDirectory 缺少 source 字段", e);
        }
        assertThat(sourceField.getType()).isEqualTo(PermissionRuleSource.class);
    }
}
