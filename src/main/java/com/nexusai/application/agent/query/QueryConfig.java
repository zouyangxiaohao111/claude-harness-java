package com.nexusai.application.agent.query;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * QueryConfig · 对齐 CC query/config.ts.
 *
 * <p>L1 语义: query() 入口的不可变配置快照。可被未来的纯 reducer (state, event, config)
 * 形式化调度器使用。包含:
 * <ul>
 *   <li>{@code sessionId} — 当前 session id</li>
 *   <li>{@code gates.streamingToolExecution} — Statsig tengu_streaming_tool_execution2</li>
 *   <li>{@code gates.emitToolUseSummaries} — env {@code CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES}</li>
 *   <li>{@code gates.isAnt} — {@code process.env.USER_TYPE === 'ant'}</li>
 *   <li>{@code gates.fastModeEnabled} — <b>恒 false</b>（F3 用户拍板恒关 2026-08-22：非 Anthropic
 *       无 fast-mode 服务端；原 CC !CLAUDE_CODE_DISABLE_FAST_MODE fastMode.ts:39 / Java
 *       NEXUSAI_DISABLE_FAST_MODE env 路已删除）</li>
 * </ul>
 *
 * <p><b>V-PF-4 对齐</b>：{@code unattendedRetryEnabled} 字段已删除——CC query/config.ts gates
 * 无等价字段（CC 持久重试门控仅经 {@code isPersistentRetryEnabled()} 直接读 env，
 * withRetry.ts:100-104），Java 侧唯一门控来源为
 * {@link com.nexusai.application.agent.recovery.ErrorClassifier#isPersistentRetryEnabled()}
 * （同读 {@code NEXUSAI_UNATTENDED_RETRY}）。旧字段 0 消费方（grep 自验），删除避免双源漂移。
 *
 * <p>L2 契约 (4 Release Gate):
 * <ul>
 *   <li><b>A1</b>: record (sessionId, gates) 字段顺序与 CC 一致</li>
 *   <li><b>A2 Golden Trace</b>: buildQueryConfig() → 实时读 4 个 gate</li>
 *   <li><b>A3 不可变</b>: record 不可变;gates record 也不可变</li>
 *   <li><b>A4 边界</b>: 空字符串 sessionId 不拒绝;null env values 静默 false</li>
 *   <li><b>A5 业务场景</b>: ant 用户的 streaming 工具调用 gates enabled</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Statsig cache → Java Supplier (caller-wired);
 * TS env var → Java supplier isTruthy (caller-wired, 测试可控)。
 */
public record QueryConfig(String sessionId, Gates gates, int maxStructuredOutputRetries) {

    public static final int DEFAULT_MAX_STRUCTURED_OUTPUT_RETRIES = 5;

    /** R32-b14 向后兼容构造器：旧 2 参调用默认 5 次。 */
    public QueryConfig(String sessionId, Gates gates) {
        this(sessionId, gates, DEFAULT_MAX_STRUCTURED_OUTPUT_RETRIES);
    }

    public QueryConfig {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(gates, "gates");
    }

    /**
     * 门控集合 · 对齐 CC query/config.ts gates。
     *
     * <p><b>V-PF-4</b>：{@code unattendedRetryEnabled} 已删除（CC query/config.ts 无此字段，
     * 持久重试门控唯一来源为 ErrorClassifier.isPersistentRetryEnabled 读 env，见类注释）。
     * 余 4 gate 均对齐 CC query/config.ts:22-25。
     */
    public record Gates(
        boolean streamingToolExecution,
        boolean emitToolUseSummaries,
        boolean isAnt,
        boolean fastModeEnabled) {

        public Gates {
            // record — no validation
        }
    }

    public static QueryConfig buildQueryConfig(
        String sessionId,
        Supplier<Boolean> streamingToolExecution,
        Supplier<Boolean> emitToolUseSummaries,
        Supplier<Boolean> isAnt,
        Supplier<Boolean> fastModeEnabled) {
        return buildQueryConfig(sessionId, streamingToolExecution, emitToolUseSummaries,
            isAnt, fastModeEnabled, () -> DEFAULT_MAX_STRUCTURED_OUTPUT_RETRIES);
    }

    /** R32-b14 完整工厂：最后一个 supplier 绑定 MAX_STRUCTURED_OUTPUT_RETRIES。 */
    public static QueryConfig buildQueryConfig(
        String sessionId,
        Supplier<Boolean> streamingToolExecution,
        Supplier<Boolean> emitToolUseSummaries,
        Supplier<Boolean> isAnt,
        Supplier<Boolean> fastModeEnabled,
        Supplier<Integer> maxStructuredOutputRetries) {
        Integer retries = maxStructuredOutputRetries == null
            ? null : maxStructuredOutputRetries.get();
        return new QueryConfig(
            sessionId,
            new Gates(
                streamingToolExecution.get(),
                emitToolUseSummaries.get(),
                isAnt.get(),
                fastModeEnabled.get()),
            retries == null ? DEFAULT_MAX_STRUCTURED_OUTPUT_RETRIES : retries);
    }

    /**
     * CC-compatible env parser：缺失/非数字回退 5；0 和负数保持 parseInt 语义。
     */
    public static int parseMaxStructuredOutputRetries(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_STRUCTURED_OUTPUT_RETRIES;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_STRUCTURED_OUTPUT_RETRIES;
        }
    }
}
