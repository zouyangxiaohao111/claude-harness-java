package com.nexusai.model.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 前端环境声明枚举 · DEC-8 web 扩展（CC 无 client-env 概念，Java web 新增入口）。
 *
 * <p>CC CLI 端 availability 门控（meetsAvailabilityRequirement commands.ts:417-443）以
 * {@code isClaudeAISubscriber()/isUsing3PServices()/isFirstPartyAnthropicBaseUrl()} 认证态为信号源
 * （agent 循环无请求上下文，不可注入请求头）；DEC-8 为 web 端新增<b>前端环境声明</b>入口：
 * controller 接收 {@code X-Client-Env} 请求头（react|mobile）透传到
 * {@code SkillRegistry.filterByClientEnv}，按声明环境过滤 availability 不匹配的命令。
 *
 * <p><b>映射（单点可调 · 待主 agent + 用户确认）</b>：{@link #satisfies(CommandAvailability)}
 * 默认 {@code react→CONSOLE}、{@code mobile→CLAUDE_AI}。业务合理性未证实（web React 前端可能经
 * claude.ai OAuth 应→CLAUDE_AI 而非直连 console）——映射隔离在单方法内，反转或双命中只需改本方法
 * 与对应测试断言。
 *
 * <p>解析严格 2 值（仿 {@link CommandAvailability#fromString}）：null / blank / 未知值 → null
 * （= universal，无环境声明默认放行，web 兼容）；未知值额外 log.warn（前端拼写错误静默变全放行的
 * 风险缓解，CLAUDE.md 规则 12 显式失败）。
 */
public enum ClientEnv {
    /** Web React 前端（经 {@code X-Client-Env: react} 声明）· 默认映射 CONSOLE 可用性。 */
    REACT("react"),
    /** 移动端（经 {@code X-Client-Env: mobile} 声明）· 默认映射 CLAUDE_AI 可用性。 */
    MOBILE("mobile");

    private static final Logger log = LoggerFactory.getLogger(ClientEnv.class);

    /** X-Client-Env 请求头值（'react' / 'mobile'）。 */
    private final String headerValue;

    ClientEnv(String headerValue) {
        this.headerValue = headerValue;
    }

    /** X-Client-Env 请求头值（'react' / 'mobile'）。 */
    public String headerValue() {
        return headerValue;
    }

    /**
     * 从 X-Client-Env 请求头严格解析 2 值 · DEC-8 web 扩展。
     *
     * <p>null / blank / 未知值 → null（universal 放行，web 兼容：前端不传环境头 / 拼写错误均不触发
     * 过滤链）。未知值 log.warn 显式暴露（CLAUDE.md 规则 12：前端拼写错误会静默变全放行）。
     *
     * @param header X-Client-Env 请求头原值（大小写不敏感，trim 后比较）
     * @return 命中 REACT/MOBILE；null / blank / 未知 → null
     */
    public static ClientEnv fromHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        for (ClientEnv env : values()) {
            if (env.headerValue().equals(normalized)) {
                return env;
            }
        }
        // 未知值 → universal（web 兼容），log.warn 显式暴露拼写错误风险
        log.warn("[ClientEnv] 未知 X-Client-Env 值 '{}' → 按 universal 放行（前端拼写错误会静默变全放行）",
            header);
        return null;
    }

    /**
     * 本环境是否满足命令的 availability 声明 · DEC-8 web 扩展（CC 无 client-env，单点可调映射）。
     *
     * <p>默认映射：{@code react→CONSOLE}、{@code mobile→CLAUDE_AI}（⚠ 待主 agent + 用户确认，
     * 业务合理性未证实——web React 前端可能经 claude.ai OAuth 应→CLAUDE_AI；反转或双命中仅需改本方法）。
     *
     * @param a 命令声明的 availability 类型（CommandAvailability，非 null）
     * @return 本环境命中该声明 → true
     */
    public boolean satisfies(CommandAvailability a) {
        return switch (this) {
            case REACT -> a == CommandAvailability.CONSOLE;
            case MOBILE -> a == CommandAvailability.CLAUDE_AI;
        };
    }
}
