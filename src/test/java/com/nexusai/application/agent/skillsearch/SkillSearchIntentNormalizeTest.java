package com.nexusai.application.agent.skillsearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [prompt-align TOOLS-05] skillSearch intentNormalize 模块测试 · 对齐 CC
 * {@code services/skillSearch/intentNormalize.ts}。
 *
 * <p><b>WHY (意图验证)</b>: CC 真源（intentNormalize.ts）
 * <pre>
 *   export async function normalizeQueryIntent(query: string): Promise<string> {
 *     const trimmed = query.trim()
 *     if (!trimmed) return trimmed
 *     if (!isIntentNormalizeEnabled()) return trimmed
 *     if (!/[一-鿿]/.test(trimmed)) return trimmed   // ASCII fast path
 *     const cached = cache.get(trimmed) ...
 *     const capped = trimmed.slice(0, MAX_QUERY_CHARS)
 *     const keywords = await callHaiku(capped)
 *     const result = keywords ? `${trimmed} ${keywords}` : trimmed
 *     ...
 *     return result
 *   }
 * </pre>
 * 门控关/非 CJK/无 LLM/失败 → 返回原串（绝不抛）；成功 → {@code <original> <keywords>}。
 */
class SkillSearchIntentNormalizeTest {

    // ─────────── 1. 门控关 → 原串（不调 LLM） ───────────

    @Test
    @DisplayName("TOOLS-05-1: 门控关（DB=false）→ 返回原串，LLM 零调用")
    void gateOff_returnsOriginal_noLlmCall() {
        AtomicInteger calls = new AtomicInteger();
        SkillSearchIntentNormalize.Default normalize =
            new SkillSearchIntentNormalize.Default(() -> false,
                (sys, user) -> { calls.incrementAndGet(); return "optimize code"; });

        assertThat(normalize.normalizeQueryIntent("帮我优化代码"))
            .as("门控关 → 原串（CC :90-92）")
            .isEqualTo("帮我优化代码");
        assertThat(calls.get()).as("门控关 → Haiku 零调用").isZero();
    }

    // ─────────── 2. 非 CJK 快路径 → 原串 ───────────

    @Test
    @DisplayName("TOOLS-05-2: 非 CJK 查询 → ASCII 快路径返回原串（CC :96-97 正则 [一-鿿]）")
    void asciiQuery_fastPath() {
        AtomicInteger calls = new AtomicInteger();
        SkillSearchIntentNormalize.Default normalize =
            new SkillSearchIntentNormalize.Default(() -> true,
                (sys, user) -> { calls.incrementAndGet(); return "refactor code"; });

        assertThat(normalize.normalizeQueryIntent("refactor this module"))
            .as("无 CJK → 原串跳过 LLM（CC :96-97）")
            .isEqualTo("refactor this module");
        assertThat(calls.get()).isZero();
    }

    // ─────────── 3. 成功 → `<original> <keywords>` ───────────

    @Test
    @DisplayName("TOOLS-05-3: 中文查询 + 门控开 → 原串+关键词（CC :105 result）")
    void cjkQuery_success_concatenates() {
        SkillSearchIntentNormalize.Default normalize =
            new SkillSearchIntentNormalize.Default(() -> true,
                (sys, user) -> "OPTIMIZE Code!  Performance");

        String result = normalize.normalizeQueryIntent("帮我优化代码的性能");

        assertThat(result)
            .as("成功 → `<original> <sanitized keywords>`（CC :105）")
            .isEqualTo("帮我优化代码的性能 optimize code performance");
    }

    // ─────────── 4. Haiku 失败/空 → 回落原串 ───────────

    @Test
    @DisplayName("TOOLS-05-4: Haiku 抛异常 → 回落原串（CC :124-127 优雅回落，绝不抛）")
    void haikuFailure_fallsBackToOriginal() {
        SkillSearchIntentNormalize.Default normalize =
            new SkillSearchIntentNormalize.Default(() -> true,
                (sys, user) -> { throw new RuntimeException("timeout"); });

        assertThat(normalize.normalizeQueryIntent("帮我分析代码"))
            .as("Haiku 失败 → 原串（CC :124-127）")
            .isEqualTo("帮我分析代码");
    }

    // ─────────── 5. 无 Haiku 通道 → 原串 ───────────

    @Test
    @DisplayName("TOOLS-05-5: 无 Haiku 通道（invoker null）→ 原串，不浪费 LLM 调用")
    void noHaikuChannel_returnsOriginal() {
        SkillSearchIntentNormalize.Default normalize =
            new SkillSearchIntentNormalize.Default(() -> true, null);

        assertThat(normalize.normalizeQueryIntent("帮我重构模块"))
            .as("无 LLM 通道 → 原串")
            .isEqualTo("帮我重构模块");
    }

    // ─────────── 6. LRU 缓存：同查询复用 ───────────

    @Test
    @DisplayName("TOOLS-05-6: LRU 缓存命中复用（第二次不调 Haiku）")
    void cacheHit_reusesResult() {
        AtomicInteger calls = new AtomicInteger();
        SkillSearchIntentNormalize.Default normalize =
            new SkillSearchIntentNormalize.Default(() -> true,
                (sys, user) -> { calls.incrementAndGet(); return "analyze code"; });

        String first = normalize.normalizeQueryIntent("帮我分析代码");
        String second = normalize.normalizeQueryIntent("帮我分析代码");

        assertThat(first).isEqualTo(second);
        assertThat(calls.get()).as("缓存命中 → Haiku 仅一次调用（CC :99-104）").isEqualTo(1);
    }

    // ─────────── 7. 门控 DB 优先 + env 回落 ───────────

    @Test
    @DisplayName("TOOLS-05-7: 空查询 → 原串（空串，CC :88-89）")
    void blankQuery_returnsBlank() {
        SkillSearchIntentNormalize.Default normalize =
            new SkillSearchIntentNormalize.Default(() -> true, null);

        assertThat(normalize.normalizeQueryIntent("   "))
            .as("空白查询 → 空白（CC :88-89）")
            .isEqualTo("");
    }
}
