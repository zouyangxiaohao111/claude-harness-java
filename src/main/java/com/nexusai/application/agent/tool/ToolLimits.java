package com.nexusai.application.agent.tool;

/**
 * ToolLimits · 对齐 CC constants/toolLimits.ts.
 *
 * <p>L1 语义: 工具结果大小的全局上限常量 — 系统级 cap,独立于单个 tool
 * 声明的 {@code maxResultSizeChars}。
 * <ul>
 *   <li>{@link #DEFAULT_MAX_RESULT_SIZE_CHARS} — 单个 tool result 默认 cap (50K chars)</li>
 *   <li>{@link #MAX_TOOL_RESULT_TOKENS} — 单个 tool result token 上限 (100K)</li>
 *   <li>{@link #BYTES_PER_TOKEN} — token 估算 (4 bytes/token)</li>
 *   <li>{@link #MAX_TOOL_RESULT_BYTES} — {@code MAX_TOOL_RESULT_TOKENS * BYTES_PER_TOKEN}</li>
 *   <li>{@link #MAX_TOOL_RESULTS_PER_MESSAGE_CHARS} — 单条 user message 内 tool result 块累计 (200K chars)</li>
 *   <li>{@link #TOOL_SUMMARY_MAX_LENGTH} — tool 摘要字符串最大长度 (50 chars)</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 6 {@code public static final long} 常量,字段顺序与 CC 一致</li>
 *   <li><b>A2 Golden Trace</b>: 读取即得值;{@link #MAX_TOOL_RESULT_BYTES} =
 *       {@code MAX_TOOL_RESULT_TOKENS * BYTES_PER_TOKEN} = 100_000 * 4 = 400_000</li>
 *   <li><b>A3 不可变</b>: 全部 {@code final} + 编译时常量 (除 MAX_TOOL_RESULT_BYTES 派生外)</li>
 *   <li><b>A4 边界</b>: 全部 long ≥ 0;无 null/边界条件</li>
 *   <li><b>A5 业务场景</b>: 并行 N 个 tool → 单 message 累积最大
 *       {@code MAX_TOOL_RESULTS_PER_MESSAGE_CHARS}=200K;每个 tool 单独
 *       默认 {@code DEFAULT_MAX_RESULT_SIZE_CHARS}=50K</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS {@code export const} → Java {@code public static final};
 * TS 派生常量 ({@code MAX_TOOL_RESULT_TOKENS * BYTES_PER_TOKEN}) 显式
 * 命名为 Java {@code static final long} = 表达式,允许编译期常量展开。
 */
public final class ToolLimits {

    private ToolLimits() {
        // 常量容器
    }

    /** Default per-tool-result size cap (chars); system-wide ceiling regardless of tool declaration. */
    public static final long DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000L;

    /** Token upper bound for a single tool result. ~400KB text (4 bytes/token). */
    public static final long MAX_TOOL_RESULT_TOKENS = 100_000L;

    /** Conservative token estimation: 4 bytes per token. */
    public static final long BYTES_PER_TOKEN = 4L;

    /** Bytes-derived token ceiling. */
    public static final long MAX_TOOL_RESULT_BYTES = MAX_TOOL_RESULT_TOKENS * BYTES_PER_TOKEN;

    /**
     * Aggregate chars across all {@code tool_result} blocks within a single user message.
     * When exceeded, largest blocks are persisted to disk and replaced with previews.
     */
    public static final long MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200_000L;

    /** Max character length for tool summary strings in compact views. */
    public static final long TOOL_SUMMARY_MAX_LENGTH = 50L;
}
