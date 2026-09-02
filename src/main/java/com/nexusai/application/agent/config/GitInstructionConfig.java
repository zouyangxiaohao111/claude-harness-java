package com.nexusai.application.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * git 指令包含判定 · 对齐 CC {@code shouldIncludeGitInstructions}（gitSettings.ts:13-18）。
 *
 * <p><b>CC 真源</b>：{@code Open-ClaudeCode/src/utils/gitSettings.ts:13-18}
 * <pre>{@code
 * export function shouldIncludeGitInstructions(): boolean {
 *   const envVal = process.env.CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS
 *   if (isEnvTruthy(envVal)) return false
 *   if (isEnvDefinedFalsy(envVal)) return true
 *   return getInitialSettings().includeGitInstructions ?? true
 * }
 * }</pre>
 * 三分支：env truthy（1/true/yes/on，envUtils.ts:32-37）→ false；env 已定义但 falsy → true；
 * env 未定义 → {@code settings.includeGitInstructions ?? true}。
 *
 * <p><b>Java 独有增强（SP-10 △1，对齐 MemoryBareModeConfig 同构）</b>：新增配置通道
 * {@code nexusai.git.include-instructions}（application.yml，注释未设置 = 未配置），
 * CC 无对应配置项。优先级：
 * <ol>
 *   <li>{@code nexusai.git.include-instructions} 显式设置（Spring {@code @Value} 注入，
 *       tri-state Boolean：null = 未配置）</li>
 *   <li>{@code CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS} env：truthy → false；
 *       defined falsy → true（对齐 CC 三分支前两支）</li>
 *   <li>默认 {@code true}（对齐 CC {@code settings ?? true}）</li>
 * </ol>
 *
 * <p><b>静态桥接</b>：{@link #shouldIncludeGitInstructions()} 为静态（GitStatusProvider 非 Spring
 * 组件、无构造注入），Spring 注入值经构造器桥接到静态字段。POJO {@code new} 场景（单测）不触发
 * Spring 构造 → 桥接为 null → 走 env 回退 → 默认 true；测试用 {@link #reset()} 防跨测试污染。
 */
@Component
public class GitInstructionConfig {

    private static final Logger log = LoggerFactory.getLogger(GitInstructionConfig.class);

    /** CC original: CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS（gitSettings.ts:14） */
    private static final String DISABLE_GIT_INSTRUCTIONS_ENV = "CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS";

    /** Spring 注入的配置值桥接（{@code nexusai.git.include-instructions}）；null = 未配置 = 走 env/默认 */
    private static volatile Boolean springConfigured;

    /** 测试辅助：env 读取覆盖开关（Java 无法进程内改 env，对齐 MemoryBareModeConfig 同款缝隙） */
    private static boolean envOverrideSet;
    private static String envOverrideValue;

    /**
     * @param configured {@code nexusai.git.include-instructions} 配置值（null = 未配置）
     */
    public GitInstructionConfig(@Value("${nexusai.git.include-instructions:#{null}}") Boolean configured) {
        springConfigured = configured;
        if (configured != null) {
            log.info("GitInstructionConfig 启动注入 nexusai.git.include-instructions={}（Java 独有增强，CC gitSettings.ts 无对应配置）", configured);
        }
    }

    /**
     * 是否包含 git 指令 · 对齐 CC {@code shouldIncludeGitInstructions()}（gitSettings.ts:13-18）。
     *
     * <p>消费点：{@code GitStatusProvider.getGitStatus()} 生产入口门控（SP-10 △1）——
     * 关闭时无 gitStatus 块（{@code null}）；默认开启（对齐 CC {@code settings ?? true}）。
     *
     * @return true = 包含 git 指令（默认）；false = 抑制 gitStatus 块
     */
    public static boolean shouldIncludeGitInstructions() {
        String envVal = currentEnvValue();
        if (isEnvTruthy(envVal)) {
            return false;
        }
        if (envVal != null) {
            // env 已定义但 falsy → true（CC isEnvDefinedFalsy，gitSettings.ts:16）
            return true;
        }
        return springConfigured != null ? springConfigured : true;
    }

    /** 当前 env 通道取值 · 测试覆盖时读 {@link #envOverrideValue}，否则读进程 env。 */
    private static String currentEnvValue() {
        return envOverrideSet ? envOverrideValue : System.getenv(DISABLE_GIT_INSTRUCTIONS_ENV);
    }

    /**
     * 测试辅助：覆盖 env 通道取值（Java 无法进程内改 env）。null = 模拟 env 未设置。
     */
    static void setEnvOverride(String value) {
        envOverrideSet = true;
        envOverrideValue = value;
    }

    /**
     * 测试辅助：置 configured 桥接值（null = 复位为未配置，走 env/默认）。
     */
    public static void setConfiguredForTest(Boolean configured) {
        springConfigured = configured;
    }

    /**
     * 测试辅助：重置静态桥接与 env 覆盖（防跨测试 / 跨 Spring 上下文污染）。
     */
    public static void reset() {
        springConfigured = null;
        envOverrideSet = false;
        envOverrideValue = null;
    }

    /** CC original: isEnvTruthy（envUtils.ts:32-37）——truthy 集合 1/true/yes/on（大小写不敏感、trim 后）。 */
    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }
}
