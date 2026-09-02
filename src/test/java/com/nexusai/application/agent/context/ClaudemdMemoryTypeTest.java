package com.nexusai.application.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClaudemdMemoryType 条件值域 · 对齐 CC {@code Open-ClaudeCode/src/utils/memory/types.ts:3-12}
 * {@code MEMORY_TYPE_VALUES}（'TeamMem' 仅 feature('TEAMMEM') 开启时在 union :9）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：CC 中 {@code MemoryType} 是 union
 * 类型，{@code feature('TEAMMEM')} 编译期宏关时 'TeamMem' 根本不在类型值域 —— 类型层面
 * 不可能产生/解析该值。Java 枚举常量静态存在，必须用静态门控（IMP-CM-08
 * {@code FeatureFlags.teamMem()} 接线 → {@link ClaudemdMemoryType#setTeamMemEnabled(boolean)}）
 * 表达同一条件值域：门控关（默认）时 {@code fromCcName("TeamMem")} → null、
 * {@code activeValues()} 不含 TEAM_MEM；门控开时恢复。AutoMem 恒在值域（CC :8 非条件 spread）。
 * 若该行为变更而测试不报错，说明值域门控已失效。
 */
@DisplayName("[IMP-CM-11 / OPD-CM3-35 H1] ClaudemdMemoryType 条件值域（memory/types.ts:3-12）")
class ClaudemdMemoryTypeTest {

    @AfterEach
    void resetGate() {
        // 静态门控跨测试残留会污染 —— 每个用例后归位默认关（对齐 CC 发行默认 feature('TEAMMEM') off）
        ClaudemdMemoryType.setTeamMemEnabled(false);
    }

    @Test
    @DisplayName("TEAMMEM 门控关（默认）→ fromCcName(\"TeamMem\") 返回 null（值不在 union）")
    void fromCcNameTeamMemGatedOff() {
        assertThat(ClaudemdMemoryType.fromCcName("TeamMem"))
            .as("CC memory/types.ts:9 条件 spread：feature('TEAMMEM') 关时 'TeamMem' 不在 MEMORY_TYPE_VALUES → 不可解析")
            .isNull();
    }

    @Test
    @DisplayName("TEAMMEM 门控开 → fromCcName(\"TeamMem\") 返回 TEAM_MEM")
    void fromCcNameTeamMemGatedOn() {
        ClaudemdMemoryType.setTeamMemEnabled(true);
        assertThat(ClaudemdMemoryType.fromCcName("TeamMem"))
            .as("CC memory/types.ts:9：feature('TEAMMEM') 开时 'TeamMem' 在 MEMORY_TYPE_VALUES → 可解析")
            .isEqualTo(ClaudemdMemoryType.TEAM_MEM);
    }

    @Test
    @DisplayName("AUTO_MEM 恒在值域（CC :8 非条件）—— 门控关不影响 AutoMem 解析")
    void fromCcNameAutoMemUnconditional() {
        // AutoMem 在 MEMORY_TYPE_VALUES 恒在（memory/types.ts:8），不随 TEAMMEM 门控变化
        assertThat(ClaudemdMemoryType.fromCcName("AutoMem"))
            .isEqualTo(ClaudemdMemoryType.AUTO_MEM);
    }

    @Test
    @DisplayName("activeValues() 门控关 → 不含 TEAM_MEM（值域 = User/Project/Local/Managed/AutoMem）")
    void activeValuesGatedOff() {
        assertThat(ClaudemdMemoryType.activeValues())
            .as("CC memory/types.ts:3-12 条件 spread：关时 MEMORY_TYPE_VALUES 无 'TeamMem'")
            .containsExactlyInAnyOrder(
                ClaudemdMemoryType.USER, ClaudemdMemoryType.PROJECT,
                ClaudemdMemoryType.LOCAL, ClaudemdMemoryType.MANAGED,
                ClaudemdMemoryType.AUTO_MEM);
    }

    @Test
    @DisplayName("activeValues() 门控开 → 含 TEAM_MEM（值域 = 全部 6 值）")
    void activeValuesGatedOn() {
        ClaudemdMemoryType.setTeamMemEnabled(true);
        assertThat(ClaudemdMemoryType.activeValues())
            .as("CC memory/types.ts:9：开时 'TeamMem' 进入 MEMORY_TYPE_VALUES")
            .containsExactlyInAnyOrder(
                ClaudemdMemoryType.USER, ClaudemdMemoryType.PROJECT,
                ClaudemdMemoryType.LOCAL, ClaudemdMemoryType.MANAGED,
                ClaudemdMemoryType.AUTO_MEM, ClaudemdMemoryType.TEAM_MEM);
    }

    @Test
    @DisplayName("门控状态可查询 isTeamMemEnabled()（默认关，set 后同步）")
    void gateStateQueriable() {
        assertThat(ClaudemdMemoryType.isTeamMemEnabled())
            .as("默认关，对齐 CC 发行默认 feature('TEAMMEM') off")
            .isFalse();
        ClaudemdMemoryType.setTeamMemEnabled(true);
        assertThat(ClaudemdMemoryType.isTeamMemEnabled()).isTrue();
    }

    @Test
    @DisplayName("其余 4 值恒可解析（User/Project/Local/Managed 不受门控）")
    void otherValuesAlwaysParseable() {
        List<ClaudemdMemoryType> parsed = List.of(
            ClaudemdMemoryType.fromCcName("User"),
            ClaudemdMemoryType.fromCcName("Project"),
            ClaudemdMemoryType.fromCcName("Local"),
            ClaudemdMemoryType.fromCcName("Managed"));
        assertThat(parsed)
            .as("User/Project/Local/Managed 恒在 MEMORY_TYPE_VALUES（memory/types.ts:4-7）")
            .containsExactly(
                ClaudemdMemoryType.USER, ClaudemdMemoryType.PROJECT,
                ClaudemdMemoryType.LOCAL, ClaudemdMemoryType.MANAGED);
    }
}
