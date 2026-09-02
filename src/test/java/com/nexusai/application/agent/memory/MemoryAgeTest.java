package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryAge 新鲜度分级 · 对齐 CC memdir/memoryAge.ts:1-53.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 明确注释——models are
 * poor at date arithmetic, a raw ISO timestamp doesn't trigger staleness
 * reasoning the way "47 days ago" does（memoryAge.ts:11-13）。memory 的
 * file:line 引用会随代码演进过期，模型却把过期引用当事实（memoryAge.ts:28-31
 * 注释：the citation makes the stale claim sound more authoritative）。
 * 本测试锁定该意图的三个可判据：
 * <ol>
 *   <li>days → human-readable 字符串分级（today/yesterday/N days ago）</li>
 *   <li>新鲜度 caveat 仅在 &gt;1 天出现（≤1 天是噪声，memoryAge.ts:23-24）</li>
 *   <li>未来 mtime（时钟偏差）clamp 到 0，绝不出现负天数（memoryAge.ts:7）</li>
 * </ol>
 *
 * <p>clock 用 {@link java.util.function.LongSupplier} 注入（MemoryAge.java:38），
 * 测试可控，不依赖真实 wall-clock。
 */
@DisplayName("[IMP-M-C-2] MemoryAge 新鲜度分级 + clamp + caveat 门控")
class MemoryAgeTest {

    private static final long DAY_MS = 86_400_000L;
    private static final long NOW = 1_752_000_000_000L; // 固定"现在"基准

    private MemoryAge age() {
        return new MemoryAge(() -> NOW);
    }

    @Test
    @DisplayName("今天 → 0 天 → 'today'，无 staleness caveat")
    void todayNoCaveat() {
        MemoryAge a = age();
        long todayMtime = NOW - 3_600_000L; // 1 小时前

        assertThat(a.memoryAgeDays(todayMtime)).isEqualTo(0);
        assertThat(a.memoryAge(todayMtime)).isEqualTo("today");
        assertThat(a.memoryFreshnessText(todayMtime)).isEmpty();
        assertThat(a.memoryFreshnessNote(todayMtime)).isEmpty();
    }

    @Test
    @DisplayName("昨天 → 1 天 → 'yesterday'，仍无 caveat（≤1 天属新鲜）")
    void yesterdayNoCaveat() {
        MemoryAge a = age();
        long yesterdayMtime = NOW - DAY_MS;

        assertThat(a.memoryAgeDays(yesterdayMtime)).isEqualTo(1);
        assertThat(a.memoryAge(yesterdayMtime)).isEqualTo("yesterday");
        assertThat(a.memoryFreshnessText(yesterdayMtime)).isEmpty();
    }

    @Test
    @DisplayName("47 天前 → '47 days ago' + caveat 含 stale 语义 + system-reminder 包装")
    void stale47Days() {
        MemoryAge a = age();
        long oldMtime = NOW - 47 * DAY_MS;

        assertThat(a.memoryAgeDays(oldMtime)).isEqualTo(47);
        assertThat(a.memoryAge(oldMtime)).isEqualTo("47 days ago");

        String text = a.memoryFreshnessText(oldMtime);
        assertThat(text)
            .contains("47 days old")
            .contains("may be outdated");
        assertThat(a.memoryFreshnessNote(oldMtime))
            .startsWith("<system-reminder>")
            .endsWith("</system-reminder>\n")
            .contains(text);
    }

    @Test
    @DisplayName("未来 mtime（时钟偏差）clamp 到 0——绝不出现负天数")
    void futureMtimeClampsToZero() {
        MemoryAge a = age();
        long futureMtime = NOW + 5 * DAY_MS;

        assertThat(a.memoryAgeDays(futureMtime)).isEqualTo(0);
        assertThat(a.memoryAge(futureMtime)).isEqualTo("today");
        assertThat(a.memoryFreshnessText(futureMtime)).isEmpty();
    }

    @Test
    @DisplayName("floor-rounded：不足 1 天（23h59m）不算 1 天（memoryAge.ts:3 floor 语义）")
    void floorRounding() {
        MemoryAge a = age();
        long mtime = NOW - (DAY_MS - 60_000L); // 23h59m 前

        assertThat(a.memoryAgeDays(mtime)).isEqualTo(0);
    }
}
