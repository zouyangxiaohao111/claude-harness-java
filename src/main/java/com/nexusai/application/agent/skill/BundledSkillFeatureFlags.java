package com.nexusai.application.agent.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bundled skill 注册 feature flag · 对齐 CC {@code src/skills/bundled/index.ts} 编译期
 * {@code feature('...')} 门控（bun bundle）在 Java 的运行时等价（Spring 配置源）。
 *
 * <p>CC 真源（探查-skill.md P2-4 E1/E6）：{@code bun:bundle} 编译期把 {@code feature()} 常量折叠——
 * 生产 bundle（package/cli.js G15）中 AGENT_TRIGGERS / AGENT_TRIGGERS_REMOTE / BUILDING_CLAUDE_APPS
 * 三个 flag 编译为 true，if 块被优化掉 → loop / scheduleRemoteAgents / claudeApi 无条件注册；
 * KAIROS / KAIROS_DREAM / REVIEW_ARTIFACT / RUN_SKILL_GENERATOR 编译为 false 且对应源文件（dream.ts /
 * hunter.ts / runSkillGenerator.ts）在 CC checkout 中不存在（幽灵引用，E7），生产 bundle 永不注册，
 * Java 不实现（登记为 CC 上游缺陷，concern #25）。
 *
 * <p>默认三 flag true + mcpSkills false（{@link #DEFAULTS}，P1-9 对齐 CC 生产 DCE）——默认 14 skill
 * 注册集不变，门控为可配置开关。record + {@code @ConfigurationProperties} 仿 {@code McpProperties/McpConfig}
 * 既有惯例（CLAUDE.md 接口用 Spring）。
 */
@ConfigurationProperties(prefix = "nexusai.skill.features")
public record BundledSkillFeatureFlags(
    /**
     * loop 技能注册门控 · CC original: {@code feature('AGENT_TRIGGERS')} bundled/index.ts:47。
     * 生产 bundle 默认 true（cli.js G15 loop 无条件注册，E6）。
     */
    boolean agentTriggers,
    /**
     * schedule 技能注册门控 · CC original: {@code feature('AGENT_TRIGGERS_REMOTE')} bundled/index.ts:56。
     * 生产 bundle 默认 true（cli.js G15 schedule 无条件注册，E6）。
     */
    boolean agentTriggersRemote,
    /**
     * claude-api 技能注册门控 · CC original: {@code feature('BUILDING_CLAUDE_APPS')} bundled/index.ts:64。
     * 生产 bundle 默认 true（cli.js G15 claudeApi 无条件注册，E6）。
     */
    boolean buildingClaudeApps,
    /**
     * MCP 技能门控 · CC original: {@code feature('MCP_SKILLS')} commands.ts:550/:558 +
     * client.ts:117/{@code fetchMcpSkillsForClient}。CC 生产 bundle 该常量折叠为 false（mcpSkills.ts
     * DCE，探查-skill.md §2.1 concern #23）——默认 false 对齐 CC 生产（P1-9，2026-08-16 拍板 Java
     * 默认关），需启用时在 yml {@code nexusai.skill.features.mcp-skills: true} 开启（kill-switch
     * 语义反转为默认关、显式开）。
     */
    boolean mcpSkills
) {

    /**
     * CC 生产 bundle 默认值：{@code @ConfigurationProperties} 未配置 / 测试非 Spring 构造时保持
     * 注册集与 CC 生产一致（loop/schedule/claude-api 默认注册；mcpSkills 默认 false 对齐 CC 生产 DCE，P1-9）。
     */
    public static final BundledSkillFeatureFlags DEFAULTS = new BundledSkillFeatureFlags();

    public BundledSkillFeatureFlags() {
        this(true, true, true, false);
    }
}
