package com.nexusai.application.agent.config;

import com.nexusai.application.agent.query.QueryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.function.Supplier;

/**
 * QueryConfigAutoConfiguration · 默认 Spring bean 让 ChatService 注入不为 null.
 *
 * <p>STREAM-P1-FIX: 此前 ChatService.setQueryConfig() 调用路径上无 bean,
 * 导致 LlmAgentLoop.queryConfig 字段为 null → 走老路径 (hardcoded default).
 * 本配置从 Environment 读取 4 个 gate, 给生产路径真实门控.
 *
 * <p>对齐 CC query/config.ts:
 * <ul>
 *   <li>streamingToolExecution — Statsig tengu_streaming_tool_execution2</li>
 *   <li>emitToolUseSummaries — env NEXUSAI_EMIT_TOOL_USE_SUMMARIES</li>
 *   <li>isAnt — env USER_TYPE=ant</li>
 *   <li>fastModeEnabled — <b>恒 false</b>（F3 用户拍板恒关：非 Anthropic 无 fast-mode 服务端；原 CC
 *       CLAUDE_CODE_DISABLE_FAST_MODE fastMode.ts:39 / Java NEXUSAI_DISABLE_FAST_MODE env 路已删除）</li>
 * </ul>
 *
 * <p><b>V-PF-4</b>：unattendedRetryEnabled 已删——CC query/config.ts gates 无此字段，持久重试
 * 门控唯一来源为 {@code ErrorClassifier.isPersistentRetryEnabled()} 直接读 env
 * （NEXUSAI_UNATTENDED_RETRY，CC original: CLAUDE_CODE_UNATTENDED_RETRY withRetry.ts:102）。
 */
@Configuration
public class QueryConfigAutoConfiguration {

    @Bean
    public QueryConfig queryConfig(Environment env) {
        return QueryConfig.buildQueryConfig(
            "default-session",
            () -> isTruthy(env.getProperty("STREAMING_TOOL_EXECUTION", "true")),
            // [W9-01 OPD-TS-29] 默认改 true（未设 env 即开启）· CC 默认 env-off，用户拍板 Java 端默认开启
            //   以便实际接通出站链路（NEXUSAI_EMIT_TOOL_USE_SUMMARIES=true 显式开启 / 显式 "false" 关闭）
            () -> isTruthy(env.getProperty("NEXUSAI_EMIT_TOOL_USE_SUMMARIES", "true")),
            () -> "ant".equalsIgnoreCase(env.getProperty("USER_TYPE", "")),
            // [F3 用户拍板恒关] 非 Anthropic 无 fast-mode 服务端 → fastModeEnabled 恒 false；
            //   原 NEXUSAI_DISABLE_FAST_MODE env 路删除（CC CLAUDE_CODE_DISABLE_FAST_MODE fastMode.ts:39 无服务端支撑）
            () -> false,
            () -> QueryConfig.parseMaxStructuredOutputRetries(
                env.getProperty("MAX_STRUCTURED_OUTPUT_RETRIES"))
        );
    }

    @Bean
    public com.nexusai.application.agent.query.TokenBudgetChecker tokenBudgetChecker() {
        return new com.nexusai.application.agent.query.TokenBudgetChecker();
    }

    private static boolean isTruthy(String s) {
        if (s == null) return false;
        return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("1") || s.equalsIgnoreCase("yes");
    }
}