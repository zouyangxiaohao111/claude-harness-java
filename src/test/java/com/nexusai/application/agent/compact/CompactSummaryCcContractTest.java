package com.nexusai.application.agent.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-PA-COMPACT-02/03/04 · CompactSummary.format() 逐字节 CC 契约测试 · 真源 prompt.ts:311-335
 * formatCompactSummary()。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 批次 C 将 format() 三个行为对齐 CC：
 * <ol>
 *   <li>[COMPACT-02] 去除 &lt;analysis&gt; 块用 replace() 无 /g → 只剥首个（Java replaceAll→replaceFirst）</li>
 *   <li>[COMPACT-03] &lt;summary&gt; 替换为原位替换 → 保留标签外 preamble/trailing 文本（原整串赋值丢弃外围）</li>
 *   <li>[COMPACT-04] 空 &lt;summary&gt; 内容仍替换为 "Summary:\n"（移除 isBlank 守卫，空标签不再残留）</li>
 * </ol>
 * 既有 PromptTextCcContractTest 只断言 CompactPrompt（压缩指令模板），不覆盖本方法 —— 本测试补齐
 * format() 字节断言，并锁定典型输入零回归锚点。
 */
class CompactSummaryCcContractTest {

    // ────────────────────────────────────────────────────────────────
    // 锚点 · 典型输入零回归（单 analysis + 单 summary，无标签外文本）
    // CC 期望输出 = "Summary:\n1. Primary Request\n2. Key Concepts"
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("典型输入（单 analysis + 单 summary）输出与 CC 逐字节一致")
    void typicalInputByteExact() {
        String raw = "<analysis>\n"
            + "draft scratchpad\n"
            + "</analysis>\n"
            + "<summary>\n"
            + "1. Primary Request\n"
            + "2. Key Concepts\n"
            + "</summary>\n";
        assertThat(CompactSummary.format(raw))
            .as("典型输入：analysis 草稿剥除，summary 标签原位替换为 Summary:\\n")
            .isEqualTo("Summary:\n1. Primary Request\n2. Key Concepts");
    }

    @Test
    @DisplayName("无标签纯文本原样透传（trim 后）")
    void plainTextPassthrough() {
        assertThat(CompactSummary.format("plain summary text"))
            .isEqualTo("plain summary text");
        assertThat(CompactSummary.format(null)).isEqualTo("");
        assertThat(CompactSummary.format("   ")).isEqualTo("");
    }

    // ────────────────────────────────────────────────────────────────
    // [COMPACT-02] replace() 无 /g → 只剥首个 <analysis> 块
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("多个 <analysis> 块：仅剥首个，第二个保留（CC replace 无 /g）")
    void multipleAnalysisBlocks_onlyFirstStripped() {
        String raw = "<analysis>first draft</analysis>\n"
            + "<analysis>second draft</analysis>\n"
            + "<summary>content</summary>";
        // CC 追踪：剥首个 analysis → "\n<analysis>second draft</analysis>\n<summary>content</summary>"
        //         → summary 原位替换 → "\n<analysis>second draft</analysis>\nSummary:\ncontent" → trim
        assertThat(CompactSummary.format(raw))
            .as("仅首个 analysis 被剥，第二个 analysis 块保留在输出中")
            .isEqualTo("<analysis>second draft</analysis>\nSummary:\ncontent");
    }

    // ────────────────────────────────────────────────────────────────
    // [COMPACT-03] 原位替换 → 保留 <summary> 标签外 preamble/trailing 文本
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("标签外 preamble/trailing 文本原位保留（CC replace 非整串赋值）")
    void surroundingTextPreserved() {
        String raw = "<preamble><analysis>draft</analysis><summary>body</summary><trailing>";
        // CC 追踪：剥首个 analysis → "<preamble><summary>body</summary><trailing>"
        //         → summary 原位替换 → "<preamble>Summary:\nbody<trailing>" → trim 不变
        assertThat(CompactSummary.format(raw))
            .as("preamble <preamble> 与 trailing <trailing> 保留，仅 summary 块原位替换")
            .isEqualTo("<preamble>Summary:\nbody<trailing>");
    }

    // ────────────────────────────────────────────────────────────────
    // [COMPACT-04] 空 <summary> 内容仍替换为 "Summary:\n"（无 isBlank 守卫）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("空 <summary></summary> 不再残留，替换为 Summary:（trim 后）")
    void emptySummaryReplaced() {
        // CC 追踪：content = '' → replace → "Summary:\n" → trim → "Summary:"
        assertThat(CompactSummary.format("<summary></summary>"))
            .as("空 summary 被替换为 Summary:，而非空标签残留")
            .isEqualTo("Summary:");
        // 带外围文本：空 summary 替换保留外围
        assertThat(CompactSummary.format("a<summary></summary>b"))
            .as("外围 a/b 保留，空 summary 原位替换为 Summary:\\n")
            .isEqualTo("aSummary:\nb");
    }

    @Test
    @DisplayName("summary 内容含 $ 字符不被当作组引用（substring 窗口安全）")
    void dollarInContentNotGroupRef() {
        // 若用 Matcher.replaceFirst(replacement) 且 replacement 含 $，$ 会被当组引用/转义；
        // substring 窗口方案天然规避。锚定 $ 代码片段场景（LLM 摘要常见）。
        String raw = "<summary>run `echo $HOME` now</summary>";
        assertThat(CompactSummary.format(raw))
            .as("$ 字面保留，不被解释为捕获组引用")
            .isEqualTo("Summary:\nrun `echo $HOME` now");
    }

    // ────────────────────────────────────────────────────────────────
    // 既有行为保留：连续换行压缩 + 尾随空白 trim
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("连续换行压缩（\n\n+ → \n\n）+ 末尾 trim 仍生效")
    void multiNewlineCompressionRetained() {
        String raw = "<summary>\n\n\nline1\n\n\nline2\n\n\n</summary>";
        assertThat(CompactSummary.format(raw))
            .as("MULTI_NEWLINE 压缩为双换行，trim 收尾")
            .isEqualTo("Summary:\nline1\n\nline2");
    }
}
