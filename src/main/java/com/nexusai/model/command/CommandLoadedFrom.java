package com.nexusai.model.command;

import java.util.Locale;

/**
 * Command 加载来源枚举 · 对齐 CC types/command.ts CommandBase.loadedFrom（:191-197）
 * + skills/loadSkillsDir.ts LoadedFrom type（:67-74）。
 *
 * <p>与 {@link CommandSource}（CC PromptCommand.source，command.ts:32）是<b>两个独立字段</b>：
 * source 表达「谁定义的命令」（{@code SettingSource | 'builtin' | 'mcp' | 'plugin' | 'bundled'}），
 * loadedFrom 表达「命令从哪个渠道加载」（6 值联合）。Java 旧架构把两者合一为 Command.source
 * （M20 △ 根因），本枚举独立建模后，SkillRegistry 过滤（CC commands.ts:568-598）与 MCP 安全闸
 * （CC loadSkillsDir.ts:374 {@code loadedFrom !== 'mcp'}）改以本字段判别，消除 managed 误当 bundled
 * 与 commands_DEPRECATED 折叠进 USER 两个行为 bug。
 *
 * <p>null 等价 CC undefined（default 构造留 null）；CommandRecord/CommandDto 均显式字段构造，
 * 本字段不参与序列化外泄（对齐 BudgetTracker local-only 红线）。
 */
public enum CommandLoadedFrom {
    /**
     * CC original: 'commands_DEPRECATED'（types/command.ts:192 / loadSkillsDir.ts:68）。
     *
     * <p>legacy /commands/ 目录命令（loadSkillsDir.ts:608 {@code loadedFrom: 'commands_DEPRECATED'}）。
     * 被 getSlashCommandToolSkills 明确排除（commands.ts:595-597），仅 getSkillToolCommands
     * allowlist（commands.ts:576）放行。
     */
    COMMANDS_DEPRECATED,
    /**
     * CC original: 'skills'（types/command.ts:193 / loadSkillsDir.ts:69）。
     *
     * <p>磁盘 /skills/ 目录技能 —— managed/user/project/additional 四源共用
     * （loadSkillsDir.ts:467 {@code loadedFrom: 'skills'}，managed 经 source='policySettings' :688
     * 加载，绝非 bundled）。
     */
    SKILLS,
    /** CC original: 'plugin'（types/command.ts:194 / loadSkillsDir.ts:70）—— marketplace 插件命令。 */
    PLUGIN,
    /**
     * CC original: 'managed'（types/command.ts:195 / loadSkillsDir.ts:71）。
     *
     * <p>类型声明含此值但 CC 当前四磁盘源实际不产出（loadSkillsDir.ts:467 恒 'skills'）；
     * 保留 MANAGED 值维持类型完整（对 policySettings 未来区分备留），文档标注 CC 当前不产出。
     */
    MANAGED,
    /**
     * CC original: 'bundled'（types/command.ts:196 / loadSkillsDir.ts:72）。
     *
     * <p>捆绑技能（bundledSkills.ts:89 {@code source:'bundled', loadedFrom:'bundled'}）与内置插件
     * 技能（builtinPlugins.ts:149-150 同值）。
     */
    BUNDLED,
    /**
     * CC original: 'mcp'（types/command.ts:197 / loadSkillsDir.ts:73）。
     *
     * <p>MCP skill:// 资源技能（getMcpSkillCommands 过滤键 commands.ts:554 {@code loadedFrom === 'mcp'}）；
     * MCP prompts（client.ts:2072）不设本值（prompts 非 skill，utils.ts:82-93 判别）。
     */
    MCP;

    /**
     * 从 CC 风格字符串严格解析 6 值 · 对齐 CC loadedFrom 字段语义
     * （null / blank / 未知 → null 等价 CC undefined）。
     *
     * <p>仅接受 CC snake_case 六值（commands_deprecated / skills / plugin / managed / bundled / mcp），
     * 未知字符串不猜测折叠（区别于 {@link CommandSource#fromString} 的宽松 USER 兜底）。
     *
     * @param value CC snake_case 值（大小写不敏感，trim 后比较）
     * @return 命中 6 值之一；null / blank / 未知 → null（CC undefined）
     */
    public static CommandLoadedFrom fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "commands_deprecated" -> COMMANDS_DEPRECATED;
            case "skills" -> SKILLS;
            case "plugin" -> PLUGIN;
            case "managed" -> MANAGED;
            case "bundled" -> BUNDLED;
            case "mcp" -> MCP;
            default -> null;
        };
    }
}
