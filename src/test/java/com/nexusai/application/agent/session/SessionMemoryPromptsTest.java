package com.nexusai.application.agent.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-M-P1-3 · SessionMemoryPrompts 保真测试（generateSectionReminders / truncate /
 * analyzeSectionSizes 逐字对齐 CC prompts.ts:134-324）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: section 提醒文本是喂给提取 fork 的
 * 指令（prompts.ts:164-196），降序 + "Prioritize keeping Current State/Errors &amp;
 * Corrections" 是 CC 的压缩引导语义；truncate 必须<b>整行边界</b>截断（:298-324），
 * 字符中间截断会让截断后的 session memory 产生残缺行。本测试锁定：
 * <ol>
 *   <li>generateSectionReminders：oversized 按 token 降序 + CRITICAL overBudget 指令
 *       （含 Prioritize keeping）+ 超限列表前缀语义</li>
 *   <li>truncateSessionMemoryForCompact：超限以整行边界收尾 + 截断标记；未超限原样保留</li>
 *   <li>analyzeSectionSizes：trim 后 join 的 roughTokenCountEstimation（Math.round(len/4)）</li>
 * </ol>
 */
@DisplayName("[IMP-M-P1-3] SessionMemoryPrompts 保真（提醒降序/行边界截断/section 分析）")
class SessionMemoryPromptsTest {

    private final SessionMemoryPrompts prompts = new SessionMemoryPrompts();

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · generateSectionReminders（prompts.ts:164-196）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("overBudget + 超限 → CRITICAL 完整指令（Prioritize keeping）+ 降序列表 + Oversized 前缀")
    void generateSectionReminders_overBudgetDescendingWithPrioritize() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("# Small", 1500);
        sizes.put("# A", 2500);
        sizes.put("# C", 3000);

        String r = prompts.generateSectionReminders(sizes, 13000);

        // overBudget CRITICAL 指令逐字（prompts.ts:183-187）
        assertThat(r).contains("CRITICAL: The session memory file is currently ~13000 tokens");
        assertThat(r).contains("exceeds the maximum of 12000 tokens");
        assertThat(r).contains("Prioritize keeping \"Current State\" and \"Errors & Corrections\" accurate and detailed.");
        // 降序列表（C 3000 > A 2500，prompts.ts:169-175）
        assertThat(r).contains("- \"# C\" is ~3000 tokens (limit: 2000)");
        assertThat(r).contains("- \"# A\" is ~2500 tokens (limit: 2000)");
        int idxC = r.indexOf("- \"# C\" is ~3000 tokens");
        int idxA = r.indexOf("- \"# A\" is ~2500 tokens");
        assertThat(idxC).isLessThan(idxA);
        // 超限项不包含未超限 section（# Small 1500 ≤ 2000）
        assertThat(r).doesNotContain("# Small");
        // overBudget → 前缀 "Oversized sections to condense"（prompts.ts:191）
        assertThat(r).contains("Oversized sections to condense");
        assertThat(r).doesNotContain("IMPORTANT: The following sections exceed the per-section limit");
    }

    @Test
    @DisplayName("仅超限未超预算 → IMPORTANT 前缀 + 降序列表；无 CRITICAL")
    void generateSectionReminders_oversizedOnlyImportantsPrefix() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("# B", 4000);
        sizes.put("# D", 2100);

        String r = prompts.generateSectionReminders(sizes, 5000);

        assertThat(r).contains("IMPORTANT: The following sections exceed the per-section limit and MUST be condensed");
        assertThat(r).doesNotContain("CRITICAL");
        assertThat(r).contains("- \"# B\" is ~4000 tokens (limit: 2000)");
        assertThat(r).contains("- \"# D\" is ~2100 tokens (limit: 2000)");
        int idxB = r.indexOf("# B");
        int idxD = r.indexOf("# D");
        assertThat(idxB).isLessThan(idxD);
    }

    @Test
    @DisplayName("无超限且未超预算 → 空串（prompts.ts:177-179）")
    void generateSectionReminders_nothingOversized_returnsEmpty() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("# Fine", 1000);

        assertThat(prompts.generateSectionReminders(sizes, 3000)).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · truncateSessionMemoryForCompact（prompts.ts:256-324）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("超限 section：整行边界收尾 + 截断标记（无字符中间截断）")
    void truncate_overLimitSection_endsAtLineBoundaryWithMarker() {
        // maxCharsPerSection = 2000*4 = 8000；每行 1000 字符 → 第 8 行（7007+1001=8008 > 8000）断
        StringBuilder content = new StringBuilder("# Long\n");
        for (int i = 0; i < 10; i++) {
            content.append("x".repeat(1000)).append("\n");
        }

        SessionMemoryPrompts.TruncationResult r = prompts.truncateSessionMemoryForCompact(content.toString());

        assertThat(r.wasTruncated()).isTrue();
        assertThat(r.content()).startsWith("# Long\n");
        assertThat(r.content()).endsWith("\n[... section truncated for length ...]");
        // 保留整行 1-7（每行 1000 字符），第 8 行起丢弃 → 无残缺行
        String[] lines = r.content().split("\n", -1);
        assertThat(lines).hasSize(10); // header + 7 行 + 空行 + 截断标记
        for (int i = 1; i <= 7; i++) {
            assertThat(lines[i]).hasSize(1000);
        }
        assertThat(lines[8]).isEmpty();
        assertThat(lines[9]).isEqualTo("[... section truncated for length ...]");
    }

    @Test
    @DisplayName("未超限 section：逐字原样保留，无截断")
    void truncate_underLimitSection_unchanged() {
        String content = "# Short\nabc\n# Empty\n\n# End\nlast";
        SessionMemoryPrompts.TruncationResult r = prompts.truncateSessionMemoryForCompact(content);

        assertThat(r.wasTruncated()).isFalse();
        assertThat(r.content()).isEqualTo(content);
    }

    @Test
    @DisplayName("前导单空行：产物保留前导 \\n（CC push 空串元素语义，prompts.ts:274/:303-305）")
    void truncate_singleLeadingBlankLine_preservesLeadingNewline() {
        String content = "\n# Session Title\nbody";
        SessionMemoryPrompts.TruncationResult r = prompts.truncateSessionMemoryForCompact(content);

        assertThat(r.wasTruncated()).isFalse();
        assertThat(r.content()).isEqualTo(content);
        assertThat(r.content()).startsWith("\n# Session Title\n");
    }

    @Test
    @DisplayName("前导单空行 + 超限 section：前导 \\n 保留且截断标记仍追加（E-A2-R1-14 第 3 例）")
    void truncate_singleLeadingBlankLine_withOverLimitSection() {
        StringBuilder content = new StringBuilder("\n# Long\n");
        for (int i = 0; i < 10; i++) {
            content.append("x".repeat(1000)).append("\n");
        }

        SessionMemoryPrompts.TruncationResult r =
            prompts.truncateSessionMemoryForCompact(content.toString());

        assertThat(r.wasTruncated()).isTrue();
        assertThat(r.content()).startsWith("\n# Long\n");
        assertThat(r.content()).endsWith("\n[... section truncated for length ...]");
    }

    @Test
    @DisplayName("null 内容 → 空结果 + 未截断")
    void truncate_nullContent_returnsEmptyNotTruncated() {
        SessionMemoryPrompts.TruncationResult r = prompts.truncateSessionMemoryForCompact(null);

        assertThat(r.content()).isEmpty();
        assertThat(r.wasTruncated()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · analyzeSectionSizes（prompts.ts:134-159）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("analyzeSectionSizes: trim 后 Math.round(len/4)；空内容 section 记 0 token")
    void analyzeSectionSizes_trimAndRoughTokenEstimation() {
        // "# H1\nabcd" → 内容 "abcd"（trim 后 4 字符）→ round(4/4)=1；
        // "# H2\n" 空内容行（split 产 "" 元素）→ currentContent.length>0 → 记 0 token（CC :142/153）
        Map<String, Integer> sizes = prompts.analyzeSectionSizes("# H1\nabcd\n\n# H2\n");
        assertThat(sizes).containsEntry("# H1", 1);
        assertThat(sizes).containsEntry("# H2", 0);

        // 前后空白被 trim：内容 "\n  abcd  \n" → trim 后 "abcd" → 1 token
        Map<String, Integer> sizes2 = prompts.analyzeSectionSizes("# S\n\n  abcd  \n");
        assertThat(sizes2).containsEntry("# S", 1);
    }
}
