package com.nexusai.application.agent.tool;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * FileReadingLimits · 对齐 CC tools/FileReadTool/limits.ts.
 *
 * <p>L1 语义: Read tool 输出尺寸上限配置。
 * 两个 cap: maxSizeBytes (总文件大小,默认 256KB;预读 stat 校验) +
 * maxTokens (实际输出 token,默认 25000;读后校验)。
 * 优先级 (CC): env var > GrowthBook > DEFAULT_*.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: DEFAULT_MAX_OUTPUT_TOKENS=25000 常量 + resolve(envMaxTokens, gbMaxTokens)→int helper + Limits record</li>
 *   <li><b>A2 Golden Trace</b>: env override 优先 over GB;env invalid → undefined → fall to GB;GB invalid → DEFAULT</li>
 *   <li><b>A3 纯函数</b>: 静态 method;env supplier 注入 (testable)</li>
 *   <li><b>A4 边界</b>: env null/empty/non-numeric → undefined;negative → undefined;0 → undefined</li>
 *   <li><b>A5 业务场景</b>: 用户设 CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS=50000 → override env 200% → Read 工具 max_tokens=50000</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS {@code memoize((): T => ...)} → Java record 字段缓存;
 * TS ternary default 链 → Java static method 分层 fallback;
 * TS parseInt + isNaN → Java try/catch + Integer.parseInt。
 */
public final class FileReadingLimits {

    /** Default token cap for Read tool output. */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 25_000;
    /** Default byte cap for Read tool input file. */
    public static final int DEFAULT_MAX_SIZE_BYTES = 256 * 1024;

    public record Limits(
        int maxTokens,
        int maxSizeBytes,
        Boolean includeMaxSizeInPrompt,
        Boolean targetedRangeNudge) {}

    /**
     * per-session Read 输出上限覆写 · 对齐 CC Tool.ts:251-254
     * {@code fileReadingLimits?: { maxTokens?: number, maxSizeBytes?: number }}.
     *
     * <p>两字段均 nullable：null = 未覆写（回退 {@link #resolve(Supplier, Supplier, Supplier)}
     * 默认）。<b>不做防御校验</b> —— 对齐 CC 用法 {@code fileReadingLimits?.maxSizeBytes ?? defaults.maxSizeBytes}
     * （FileReadTool.ts:505-507）：override 值原样透传（CC 仅对默认值 getDefaultFileReadingLimits
     * 校验，对 per-session override 无校验 —— maxTokens=0 会原样生效，等价 CC nullish 合并语义）。
     */
    public record Override(Integer maxTokens, Integer maxSizeBytes) {}

    private FileReadingLimits() {}

    /**
     * Resolve env var override value. Returns null if env unset/invalid.
     * Mirrors CC getEnvMaxTokens().
     */
    public static Integer resolveEnvMaxTokens(String envValue) {
        if (envValue == null || envValue.isEmpty()) return null;
        try {
            int parsed = Integer.parseInt(envValue.trim());
            if (parsed > 0) return parsed;
        } catch (NumberFormatException e) {
            // ignore
        }
        return null;
    }

    /**
     * Resolve final maxTokens with priority: env > GrowthBook > DEFAULT.
     */
    public static int resolveMaxTokens(Integer envMaxTokens, Integer gbMaxTokens) {
        if (envMaxTokens != null && envMaxTokens > 0) return envMaxTokens;
        if (gbMaxTokens != null && gbMaxTokens > 0) return gbMaxTokens;
        return DEFAULT_MAX_OUTPUT_TOKENS;
    }

    /**
     * Build a Limits record using injected env/GB suppliers.
     *
     * @param envSupplier returns raw env string (e.g. {@code process.env.CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS}); null/empty yields null
     * @param gbSupplier returns raw GrowthBook value (Integer); null yields null
     */
    public static Limits resolve(Supplier<String> envSupplier, Supplier<Integer> gbSupplier) {
        String envRaw = envSupplier == null ? null : envSupplier.get();
        Integer envVal = resolveEnvMaxTokens(envRaw);
        Integer gbVal = gbSupplier == null ? null : gbSupplier.get();
        int tokens = resolveMaxTokens(envVal, gbVal);
        int bytes = gbSupplier == null ? DEFAULT_MAX_SIZE_BYTES
            : (gbVal == null ? DEFAULT_MAX_SIZE_BYTES : DEFAULT_MAX_SIZE_BYTES);
        return new Limits(tokens, bytes, null, null);
    }

    /** Variant with IntSupplier for env override (parsed outside). */
    public static Limits resolve(IntSupplier envOverrideSupplier,
                                 Supplier<Integer> gbSupplier) {
        Integer envVal = envOverrideSupplier == null ? null : envOverrideSupplier.getAsInt();
        Integer gbVal = gbSupplier == null ? null : gbSupplier.get();
        int tokens = resolveMaxTokens(envVal, gbVal);
        return new Limits(tokens, DEFAULT_MAX_SIZE_BYTES, null, null);
    }

    /**
     * getDefaultFileReadingLimits() 等价 · 对齐 CC limits.ts:53-92（接入实际 env/GB 供应方）。
     *
     * <p>优先级（CC 真源）：
     * <ul>
     *   <li>maxTokens = env({@code CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS}, limits.ts:24-33) &gt;
     *       GB({@code tengu_amber_wren}.maxTokens, limits.ts:70-74) &gt; DEFAULT_MAX_OUTPUT_TOKENS</li>
     *   <li>maxSizeBytes = GB({@code tengu_amber_wren}.maxSizeBytes, 有限 &amp;&amp; &gt;0, limits.ts:60-65) &gt;
     *       DEFAULT_MAX_SIZE_BYTES（CC maxSizeBytes <b>无 env 层</b>）</li>
     * </ul>
     *
     * <p>L3（Java idiom）：TS {@code memoize((): FileReadingLimits => ...)} 每次调用解析（Java 无全局
     * memoize，调用方按需缓存）；TS defensive 逐字段校验（非法 → DEFAULT，无 cap=0 路径）→
     * Java {@link #resolveEnvMaxTokens} + {@code >0} 判断。
     *
     * <p><b>[OPD-D1-01] 接线</b>：{@link com.nexusai.application.agent.tool.impl.ReadFileTool} 以
     * {@code () -> System.getenv("CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS")} + {@code @Value} 注入的
     * GB 属性为真实供应方调用本方法，产出默认上限；per-session override（ctx.fileReadingLimits）
     * 在工具侧按 {@code override ?? default} 覆盖（CC FileReadTool.ts:505-507）。
     *
     * @param envSupplier          env 原始值供应方（{@code CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS}）；null/空/非法 → null
     * @param gbMaxTokensSupplier  GB maxTokens 供应方（Integer）；null/&lt;=0 → 忽略（fall to DEFAULT）
     * @param gbMaxSizeBytesSupplier GB maxSizeBytes 供应方（Integer）；null/&lt;=0 → DEFAULT_MAX_SIZE_BYTES
     * @return 默认上限（env/GB override 后，无则 CC 硬编码默认）
     */
    public static Limits resolve(Supplier<String> envSupplier,
                                 Supplier<Integer> gbMaxTokensSupplier,
                                 Supplier<Integer> gbMaxSizeBytesSupplier) {
        String envRaw = envSupplier == null ? null : envSupplier.get();
        Integer gbTokens = gbMaxTokensSupplier == null ? null : gbMaxTokensSupplier.get();
        Integer gbBytes = gbMaxSizeBytesSupplier == null ? null : gbMaxSizeBytesSupplier.get();
        int tokens = resolveMaxTokens(resolveEnvMaxTokens(envRaw), gbTokens);
        int bytes = (gbBytes != null && gbBytes > 0) ? gbBytes : DEFAULT_MAX_SIZE_BYTES;
        return new Limits(tokens, bytes, null, null);
    }
}
