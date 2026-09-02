package com.nexusai.model.command;

import java.util.Locale;

/**
 * 命令可用性声明枚举 · 对齐 CC types/command.ts CommandAvailability（:169-173）
 * {@code type CommandAvailability = 'claude-ai' | 'console'}。
 *
 * <p>命令经 {@code availability} 声明可用的认证/供应商类型（CC types/command.ts:164-168 注释：
 * 「Commands without availability are available everywhere. Commands with availability are only
 * shown if the user matches at least one of the listed auth types」）。门控判定见
 * {@code SkillRegistry.meetsAvailabilityRequirement}（commands.ts:417-443）：
 * <ul>
 *   <li>{@link #CLAUDE_AI} — claude.ai OAuth 订阅用户（Pro/Max/Team/Enterprise 经 claude.ai，
 *       CC auth.ts:1564-1570 isClaudeAISubscriber）</li>
 *   <li>{@link #CONSOLE} — 直连 api.anthropic.com 的 Console API key 用户（非 claude.ai OAuth、
 *       非 3P、直连 1P base URL，commands.ts:426-433）</li>
 * </ul>
 *
 * <p>null / undefined availability = universal（所有用户可用）。Web 端默认无 claude-ai/console
 * 订阅模型（DEC-8 待主 agent + 用户拍板），SkillRegistry 未注入 AvailabilityAuthState 时
 * 默认态（subscriber=false / using3P=false / firstParty=true）下所有现有技能 availability=null
 * → universal 直通，运行时行为零变化。
 *
 * <p>DEC-8（web 扩展）：前端环境声明入口 —— controller 接收 {@code X-Client-Env} 请求头
 * （react|mobile）经 {@link ClientEnv#satisfies(CommandAvailability)} 单点映射到本枚举值
 * （默认 react→CONSOLE、mobile→CLAUDE_AI，映射待主 agent + 用户确认，单点可调），
 * 再经 {@code SkillRegistry.filterByClientEnv} 过滤。内部链 getAllCommands 认证门控
 * （commands.ts:417-443 auth 态判定）与 REST 链 client-env 门控（请求头）是双门控共存，
 * 信号源不同，勿合并。
 */
public enum CommandAvailability {
    /** CC original: 'claude-ai'（types/command.ts:170）——claude.ai OAuth 订阅用户。 */
    CLAUDE_AI("claude-ai"),
    /** CC original: 'console'（types/command.ts:172）——直连 api.anthropic.com 的 Console API key 用户。 */
    CONSOLE("console");

    /** CC original snake_case 值（types/command.ts:170/:172）。 */
    private final String ccValue;

    CommandAvailability(String ccValue) {
        this.ccValue = ccValue;
    }

    /** CC original snake_case 值（'claude-ai' / 'console'）。 */
    public String ccValue() {
        return ccValue;
    }

    /**
     * 从 CC 风格字符串严格解析 2 值 · 对齐 CC availability 字段语义
     * （null / blank / 未知 → null 等价 CC undefined）。
     *
     * <p>仅接受 CC 二值（claude-ai / console），未知字符串不猜测折叠
     * （仿 {@link CommandLoadedFrom#fromString} 严格模式）。
     *
     * @param value CC 字符串值（大小写不敏感，trim 后比较）
     * @return 命中 2 值之一；null / blank / 未知 → null（CC undefined）
     */
    public static CommandAvailability fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "claude-ai" -> CLAUDE_AI;
            case "console" -> CONSOLE;
            default -> null;
        };
    }
}
