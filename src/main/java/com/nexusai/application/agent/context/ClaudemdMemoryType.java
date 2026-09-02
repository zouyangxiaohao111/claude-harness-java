package com.nexusai.application.agent.context;

import java.util.Locale;

/**
 * claudemd 记忆文件类型 · 对齐 CC {@code Open-ClaudeCode/src/utils/memory/types.ts:3-12}
 * {@code MEMORY_TYPE_VALUES}（User/Project/Local/Managed/AutoMem，TeamMem 条件值域）。
 *
 * <p><b>为什么独立枚举（不并入索引域）</b>：与 {@code agent/memory/MemoryType}
 * （索引域 USER/FEEDBACK/...) 语义并存易混淆 —— 本枚举是<b>文件来源类型</b>
 * （谁写的 CLAUDE.md），索引域是<b>记忆内容分类</b>（用户反馈/项目事实）。包隔离
 * （context 包）+ JavaDoc 区分。
 *
 * <p>CC original: {@code MemoryType}（memory/types.ts:3-12）。CC 值为 PascalCase
 * （'User'/'Project'/...），Java 枚举常量按惯例 UPPER_SNAKE，经 {@link #ccName()}
 * 还原 CC 字面量。
 *
 * <p><b>[IMP-CM-11 / OPD-CM3-35 H1] 条件值域</b>：CC {@code MEMORY_TYPE_VALUES}
 * 中 {@code 'TeamMem'} 仅当 {@code feature('TEAMMEM')} 开启时在 union（memory/types.ts:9
 * {@code ...(feature('TEAMMEM') ? (['TeamMem'] as const) : [])}）；{@code 'AutoMem'} 恒在
 * union（memory/types.ts:8，非条件值）。Java 侧以 {@link #setTeamMemEnabled(boolean)}
 * 模拟该编译期宏（OPD-CM3-10/B03 · IMP-CM-08 {@code FeatureFlags.teamMem()}，默认关对齐
 * CC 发行默认）：开关关时 {@link #fromCcName(String)} 解析 "TeamMem" → null、且
 * {@link #activeValues()} 值域不含 TEAM_MEM；开关开时恢复。其余 5 值不受门控。
 */
public enum ClaudemdMemoryType {

    /** 用户全局指令 · CC 'User'（{@code ~/.claude/CLAUDE.md}） */
    USER,
    /** 项目指令（入库）· CC 'Project'（{@code CLAUDE.md} / {@code .claude/CLAUDE.md}） */
    PROJECT,
    /** 本地私有项目指令（不入库）· CC 'Local'（{@code CLAUDE.local.md}） */
    LOCAL,
    /** 托管策略指令 · CC 'Managed'（{@code getManagedFilePath()/CLAUDE.md}） */
    MANAGED,
    /** auto-memory 入口（MEMORY.md）· CC 'AutoMem'（恒在 union，非 TEAMMEM 门控） */
    AUTO_MEM,
    /** team memory 入口 · CC 'TeamMem'（feature('TEAMMEM') 门控 · 条件值域） */
    TEAM_MEM;

    /** TEAMMEM 编译期宏模拟 · 默认关（对齐 CC 发行默认 feature('TEAMMEM') off）· IMP-CM-08
     *  {@code FeatureFlags.teamMem()} 经 {@link #setTeamMemEnabled(boolean)} 接线。 */
    private static volatile boolean teamMemEnabled = false;

    /**
     * [IMP-CM-11] 设置 TEAMMEM 门控 · 对齐 CC {@code feature('TEAMMEM')}
     * （memory/types.ts:9，编译期宏，Java 用可配置开关模拟 · OPD-CM3-10/B03）。
     *
     * @param enabled {@code true} = TeamMem 进入值域；{@code false} = 移除（默认）
     */
    public static void setTeamMemEnabled(boolean enabled) {
        teamMemEnabled = enabled;
    }

    /**
     * [IMP-CM-11] 当前 TEAMMEM 门控态 · 对齐 CC {@code feature('TEAMMEM')}
     * （memory/types.ts:9）· 默认 false。
     */
    public static boolean isTeamMemEnabled() {
        return teamMemEnabled;
    }

    /**
     * [IMP-CM-11] 当前激活值域 · 对齐 CC {@code MEMORY_TYPE_VALUES}
     * （memory/types.ts:3-12）——TEAMMEM 关时不含 TeamMem（CC :9 条件 spread），开时含；
     * AutoMem 恒在（CC :8）。消费方如需遍历"全部合法类型"须用本方法而非 {@code values()}
     * （Java 枚举常量静态存在，值域条件由本方法表达）。
     *
     * @return 当前值域（不可变列表）
     */
    public static java.util.List<ClaudemdMemoryType> activeValues() {
        if (teamMemEnabled) {
            return java.util.List.of(USER, PROJECT, LOCAL, MANAGED, AUTO_MEM, TEAM_MEM);
        }
        return java.util.List.of(USER, PROJECT, LOCAL, MANAGED, AUTO_MEM);
    }

    /**
     * 还原 CC 字面量（'User'/'AutoMem' 等）· CC original: {@code MemoryType}
     * （memory/types.ts:3-12）。
     */
    public String ccName() {
        if (this == AUTO_MEM) {
            return "AutoMem";
        }
        if (this == TEAM_MEM) {
            return "TeamMem";
        }
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * 由 CC 字面量解析 · 未知值 → null（CC union 类型编译期约束，Java 运行时宽容）。
     *
     * <p><b>[IMP-CM-11] 条件值域</b>：TEAMMEM 门控关时（默认）"TeamMem" → null
     * （对齐 CC memory/types.ts:9 条件 spread，值不在 union 即不可解析）；门控开时 → TEAM_MEM。
     */
    public static ClaudemdMemoryType fromCcName(String ccName) {
        if (ccName == null) {
            return null;
        }
        return switch (ccName) {
            case "User" -> USER;
            case "Project" -> PROJECT;
            case "Local" -> LOCAL;
            case "Managed" -> MANAGED;
            case "AutoMem" -> AUTO_MEM;
            case "TeamMem" -> teamMemEnabled ? TEAM_MEM : null;
            default -> null;
        };
    }
}
