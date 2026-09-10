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
 *   <li>emitToolUseSummaries — env NEXUSAI_EMIT_TOOL_USE_SUMMARIES · <b>默认 false</b>（2026-09-10
 *       用户拍板反转 W9-01；对齐 CC query/config.ts:36-38 未设 env 即关）</li>
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
            // [W9-01 OPD-TS-29 反转 · 2026-09-10 用户拍板] 默认回归 false，严格对齐 CC。
            //   沿革：W9-01 当时明知「CC 默认 env-off」仍有意把 Java 端默认改成 true，理由=「先实际接通
            //     出站链路并验证跑通」；该验证目标已达成，故按对齐 CC 铁律回归 false。
            //   CC 真源：claude-code-best/src/query/config.ts:36-38
            //     emitToolUseSummaries: isEnvTruthy(process.env.CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES)
            //     未设该 env 时 isEnvTruthy(undefined) → envUtils.ts:33 `if (!envVar) return false` → **关**。
            //   需要开启时显式置 env / yml：NEXUSAI_EMIT_TOOL_USE_SUMMARIES=true（"1"/"yes" 亦可）。
            () -> isTruthy(env.getProperty("NEXUSAI_EMIT_TOOL_USE_SUMMARIES", "false")),
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