package com.nexusai.model.command;

/**
 * Command 来源枚举 · 对齐 CC command.ts PromptCommand.source（command.ts:32，
 * {@code SettingSource | 'builtin' | 'mcp' | 'plugin' | 'bundled'}）
 *
 * <p>本枚举表达<b> source 字段</b>（「谁定义的命令」），与 CC CommandBase.loadedFrom
 * （command.ts:191-197，加载渠道）是<b>两个独立字段</b> —— 后者由
 * {@link CommandLoadedFrom} 独立建模（M20 △ 根因：Java 旧架构合一为 source）。
 * 'commands_deprecated' / 'managed' 是 loadedFrom 值而非 source 值，不再在此折叠。
 */
public enum CommandSource {
    /** 内置命令（不可删除、不可编辑） · CC original: {@code 'builtin'}（types/command.ts:32） */
    BUILTIN,
    /**
     * 用户全局配置源技能（{@code getClaudeConfigHomeDir()/skills/}）· CC original:
     * {@code 'userSettings'}（constants.ts:9, loadSkillsDir.ts:689）。
     *
     * <p>P2-19 前 SettingSource 的 5 值（userSettings/projectSettings/localSettings/flagSettings/
     * policySettings）全部折叠进本值，导致遥测 {@code skill_source} 桶塌缩（project→user）。
     * P2-19 拆分后本值仅代表 userSettings 源；project/local/flag 由独立枚举值表达。
     */
    USER,
    /**
     * 项目级配置源技能（{@code .claude/skills} + additionalDirs/.claude/skills +
     * 动态技能目录）· CC original: {@code 'projectSettings'}（constants.ts:12，
     * loadSkillsDir.ts:695/704/941）。P2-19 拆分：旧实现 project/additional/bare/dynamic
     * 源折叠进 {@link #USER} → 遥测 {@code skill_source} 桶塌缩为 userSettings。
     */
    PROJECT_SETTINGS,
    /**
     * 本地（gitignored）配置源 · CC original: {@code 'localSettings'}（constants.ts:15）。
     * 技能加载路径当前不产出本值（loadSkillsDir 仅 managed/user/project 三目录），
     * 保留以完整表达 CC SettingSource 联合（markdown 命令/历史 DB 可能落到本值）。
     */
    LOCAL_SETTINGS,
    /**
     * 命令行 flag 配置源 · CC original: {@code 'flagSettings'}（constants.ts:18）。
     * 同 {@link #LOCAL_SETTINGS}：完整表达 CC SettingSource 联合。
     */
    FLAG_SETTINGS,
    /**
     * managed（policySettings）源技能 · CC original: {@code 'policySettings'}
     * （constants.ts:21, loadSkillsDir.ts:688
     * {@code loadSkillsFromSkillsDir(managedSkillsDir, 'policySettings')}）。
     */
    POLICY_SETTINGS,
    /** 插件提供的命令 · CC original: {@code 'plugin'}（types/command.ts:32） */
    PLUGIN,
    /** MCP 服务器提供的技能 · CC original: {@code 'mcp'} */
    MCP,
    /** 捆绑技能（classpath 内置 .md 文件）· CC original: {@code 'bundled'}（bundledSkills.ts:88） */
    BUNDLED;

    /**
     * 从 CC 风格字符串解析 source 值 · 对齐 CC source 字段语义。
     *
     * <p>宽松兜底 USER（DB/历史源字符串无法精确表达 SettingSource 时落用户源）；
     * null/blank/未知 → USER。'commands_deprecated' / 'managed' 是 loadedFrom 值，
     * 不在此折叠（deleteList P2-21，由 {@link CommandLoadedFrom#fromString} 独立解析）。
     *
     * <p>解析时剥离下划线（{@code project_settings} ↔ {@code projectsettings} 同义），
     * 兼容 {@code CommandRecord} 序列化（{@code name().toLowerCase()} 产出带下划线形式）
     * 与 CC snake_case 直存形式。
     */
    public static CommandSource fromString(String value) {
        if (value == null || value.isBlank()) return USER;
        return switch (value.toLowerCase(java.util.Locale.ROOT).trim().replace("_", "")) {
            case "builtin" -> BUILTIN;
            case "user", "skills", "usersettings" -> USER;   // 'skills' = SettingSource 折叠代理（历史 DB 兼容）
            case "projectsettings" -> PROJECT_SETTINGS;      // CC original: 'projectSettings'（constants.ts:12）
            case "localsettings" -> LOCAL_SETTINGS;          // CC original: 'localSettings'（constants.ts:15）
            case "flagsettings" -> FLAG_SETTINGS;            // CC original: 'flagSettings'（constants.ts:18）
            case "policysettings" -> POLICY_SETTINGS;        // CC original: 'policySettings'（constants.ts:21）
            case "plugin" -> PLUGIN;
            case "mcp" -> MCP;
            case "bundled" -> BUNDLED;
            default -> USER;
        };
    }

    /** 是否为不可删除的系统内置源 */
    public boolean isSystem() {
        return this == BUILTIN || this == BUNDLED;
    }
}
