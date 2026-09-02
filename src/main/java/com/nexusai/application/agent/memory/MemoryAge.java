package com.nexusai.application.agent.memory;

import java.util.function.LongSupplier;

/**
 * Memory age / freshness 工具 · 对齐 CC memdir/memoryAge.ts.
 *
 * <p>L1 语义: 给定 mtime, 返回 days elapsed / human-readable string / freshness caveat.
 *             用于 staleness 提示 (防止模型把过时 file:line citation 当事实).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 个纯函数签名与 CC 一致 (memoryAgeDays/memoryAge/memoryFreshnessText/memoryFreshnessNote)</li>
 *   <li><b>A2 Golden Trace</b>: mtime → days → string (today/yesterday/N days ago) → caveat 拼接</li>
 *   <li><b>A3</b>: clock 注入 (LongSupplier), 测试可控; future mtime clamp 到 0 (无负值)</li>
 *   <li><b>A4</b>: freshness text 空 → note 也空 (≤1 day); text 非空 → note 包装 <system-reminder></li>
 *   <li><b>A5</b>: 真实场景 mtime = now - 47*86400000 → "47 days ago" + caveat 含 "stale" 提示</li>
 * </ul>
 *
 * <p>L3 (Java idiom): LongSupplier 替代 Date.now() 全局; String 不可变; final class + 双 public 构造
 * （无参默认时钟 / LongSupplier 注入时钟，测试可控）。
 */
public final class MemoryAge {

    private static final long ONE_DAY_MS = 86_400_000L;
    private static final String FRESHNESS_TEMPLATE =
        "This memory is %d days old. " +
        "Memories are point-in-time observations, not live state — " +
        "claims about code behavior or file:line citations may be outdated. " +
        "Verify against current code before asserting as fact.";

    private final LongSupplier clock;

    public MemoryAge() {
        this(System::currentTimeMillis);
    }

    /** 测试用: 注入可控时钟. */
    public MemoryAge(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Days elapsed since mtime. Floor-rounded; 0 for today, 1 for yesterday.
     * Negative inputs (future mtime, clock skew) clamp to 0.
     */
    public int memoryAgeDays(long mtimeMs) {
        long delta = clock.getAsLong() - mtimeMs;
        if (delta <= 0) return 0;
        return (int) (delta / ONE_DAY_MS);
    }

    /** Human-readable age: "today" / "yesterday" / "N days ago". */
    public String memoryAge(long mtimeMs) {
        int d = memoryAgeDays(mtimeMs);
        if (d == 0) return "today";
        if (d == 1) return "yesterday";
        return d + " days ago";
    }

    /**
     * Plain-text staleness caveat for memories &gt;1 day old.
     * Returns '' for fresh memories (today/yesterday).
     */
    public String memoryFreshnessText(long mtimeMs) {
        int d = memoryAgeDays(mtimeMs);
        if (d <= 1) return "";
        return String.format(FRESHNESS_TEMPLATE, d);
    }

    /**
     * Per-memory staleness note wrapped in &lt;system-reminder&gt; tags.
     * Returns '' for memories ≤ 1 day old.
     */
    public String memoryFreshnessNote(long mtimeMs) {
        String text = memoryFreshnessText(mtimeMs);
        if (text.isEmpty()) return "";
        return "<system-reminder>" + text + "</system-reminder>\n";
    }
}