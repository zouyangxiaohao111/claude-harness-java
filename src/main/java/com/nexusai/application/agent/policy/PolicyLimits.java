package com.nexusai.application.agent.policy;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Policy Limits 配额校验 · 对齐 CC settings/teamMemory max file/entries limits.
 *
 * <p>L1 语义: 上传前/导入前校验是否超限. 超限时返回结构化错误 (含限制值 + 实际值),
 * 调用方可基于此回滚或提示用户.
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #checkFileSize} 超过 max → 返回 {@link LimitResult.Denied} 含 actualBytes + maxBytes</li>
 *   <li>{@link #checkEntryCount} 超过 max → 返回 Denied 含 actualEntries + maxEntries</li>
 *   <li>在限制内 → 返回 {@link LimitResult.Allowed}</li>
 *   <li>单条 entry 内容大小另由调用方用 {@link #checkFileSize} 校验</li>
 * </ul>
 */
@Component
public class PolicyLimits {

    /** 单文件最大字节数 (CC settingsSync: 500 KB). */
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 500L * 1024L;

    /** Team memory 最大条目数 (CC teamMemoryTooManyEntries). */
    public static final int DEFAULT_MAX_ENTRIES = 100;

    /** 限制校验结果. */
    public sealed interface LimitResult {
        record Allowed() implements LimitResult {}
        record DeniedFileSize(long actualBytes, long maxBytes) implements LimitResult {}
        record DeniedEntryCount(int actualEntries, int maxEntries) implements LimitResult {}
    }

    /** 限制配置. */
    public record Limits(long maxFileSizeBytes, int maxEntries) {
        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_FILE_SIZE_BYTES, DEFAULT_MAX_ENTRIES);
        }
    }

    /**
     * 校验单个文件内容大小.
     *
     * @param content 文件内容 (按 UTF-8 字节计)
     * @param limits  限制配置
     */
    public LimitResult checkFileSize(String content, Limits limits) {
        if (content == null) {
            return new LimitResult.Allowed();
        }
        long actualBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (actualBytes > limits.maxFileSizeBytes()) {
            return new LimitResult.DeniedFileSize(actualBytes, limits.maxFileSizeBytes());
        }
        return new LimitResult.Allowed();
    }

    /**
     * 校验 entries 总数.
     *
     * @param entries 待上传的 path → content
     * @param limits  限制配置
     */
    public LimitResult checkEntryCount(Map<String, String> entries, Limits limits) {
        int actual = entries == null ? 0 : entries.size();
        if (actual > limits.maxEntries()) {
            return new LimitResult.DeniedEntryCount(actual, limits.maxEntries());
        }
        return new LimitResult.Allowed();
    }
}