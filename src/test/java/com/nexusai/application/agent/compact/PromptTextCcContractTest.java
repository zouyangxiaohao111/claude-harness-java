package com.nexusai.application.agent.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-16 · 提示词文本逐字节对齐测试（△-19）· CC 真源 prompt.ts @ 8e1437ff。
 *
 * <p><b>WHY</b>: 探查 △-19 登记 CompactPrompt 文本偏移（BASE 缺 section-9 尾句 +
 * additional-instructions 块 + example 占位符；PARTIAL 缺 example/尾句/直接引语要求 +
 * 误用 BASE 分析指令）。本测试以 {@link CcPromptFixture}（脚本从 prompt.ts 模板字面量
 * 程序化提取）为真源，断言 CompactPrompt 输出与 CC 逐字节一致——不是子串/语义近似。
 *
 * <p><b>RED→GREEN</b>: 基线（8e1437ff）CompactPrompt 文本缺失上述段落 → 本测试先红；
 * IMP2-16 回填后转绿。
 */
class PromptTextCcContractTest {

    private static final String CI = "User context: focus on the failing tests";

    @Test
    @DisplayName("BASE: buildCompactPrompt(null) 与 CC getCompactPrompt() 输出逐字节一致（含 section-9 尾句/additional-instructions/example）")
    void basePromptByteExact() {
        String expected = CcPromptFixture.CC_NO_TOOLS_PREAMBLE
            + CcPromptFixture.CC_BASE_COMPACT_PROMPT
            + CcPromptFixture.CC_NO_TOOLS_TRAILER;
        assertThat(CompactPrompt.buildCompactPrompt(null)).isEqualTo(expected);
    }

    @Test
    @DisplayName("BASE: customInstructions 注入（\\n\\nAdditional Instructions:\\n 前缀）与 CC 一致")
    void basePromptWithCustomInstructionsByteExact() {
        String expected = CcPromptFixture.CC_NO_TOOLS_PREAMBLE
            + CcPromptFixture.CC_BASE_COMPACT_PROMPT
            + "\n\nAdditional Instructions:\n" + CI
            + CcPromptFixture.CC_NO_TOOLS_TRAILER;
        assertThat(CompactPrompt.buildCompactPrompt(CI)).isEqualTo(expected);
    }

    @Test
    @DisplayName("PARTIAL from: buildPartialCompactPrompt(null, FROM) 与 CC 逐字节一致（PARTIAL 分析指令 + section-9 尾句 + example + 尾句）")
    void partialFromPromptByteExact() {
        String expected = CcPromptFixture.CC_NO_TOOLS_PREAMBLE
            + CcPromptFixture.CC_PARTIAL_COMPACT_PROMPT
            + CcPromptFixture.CC_NO_TOOLS_TRAILER;
        assertThat(CompactPrompt.buildPartialCompactPrompt(null, CompactPrompt.Direction.FROM))
            .isEqualTo(expected);
        // 锚点：必须使用 PARTIAL 变体分析指令（不得复用 BASE）
        assertThat(expected).contains("1. Analyze the recent messages chronologically.");
        assertThat(expected).doesNotContain("1. Chronologically analyze each message and section");
    }

    @Test
    @DisplayName("PARTIAL up_to: buildPartialCompactPrompt(null, UP_TO) 与 CC 逐字节一致（example + 尾句）")
    void partialUpToPromptByteExact() {
        String expected = CcPromptFixture.CC_NO_TOOLS_PREAMBLE
            + CcPromptFixture.CC_PARTIAL_COMPACT_UP_TO_PROMPT
            + CcPromptFixture.CC_NO_TOOLS_TRAILER;
        assertThat(CompactPrompt.buildPartialCompactPrompt(null, CompactPrompt.Direction.UP_TO))
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("PARTIAL: customInstructions 注入与空白跳过语义（CC trim() !== ''）一致")
    void partialPromptCustomInstructionsByteExact() {
        String withCi = CcPromptFixture.CC_NO_TOOLS_PREAMBLE
            + CcPromptFixture.CC_PARTIAL_COMPACT_PROMPT
            + "\n\nAdditional Instructions:\n" + CI
            + CcPromptFixture.CC_NO_TOOLS_TRAILER;
        assertThat(CompactPrompt.buildPartialCompactPrompt(CI, CompactPrompt.Direction.FROM))
            .isEqualTo(withCi);

        // CC: if (customInstructions && customInstructions.trim() !== '') → 空白不注入
        String noCi = CcPromptFixture.CC_NO_TOOLS_PREAMBLE
            + CcPromptFixture.CC_PARTIAL_COMPACT_PROMPT
            + CcPromptFixture.CC_NO_TOOLS_TRAILER;
        assertThat(CompactPrompt.buildPartialCompactPrompt("   ", CompactPrompt.Direction.FROM))
            .isEqualTo(noCi);
    }

    @Test
    @DisplayName("UP_TO 分析指令 = BASE 变体（CC prompt.ts:210 插值 DETAILED_ANALYSIS_INSTRUCTION_BASE）")
    void upToUsesBaseAnalysisInstruction() {
        assertThat(CcPromptFixture.CC_PARTIAL_COMPACT_UP_TO_PROMPT)
            .contains(CcPromptFixture.CC_DETAILED_ANALYSIS_INSTRUCTION_BASE);
    }

    @Test
    @DisplayName("BASE example 块字节锚点：占位符 [...]、File Name 2、'6. All user messages: ' 尾随空格、23 空格缩进尾句")
    void baseExampleByteAnchors() {
        assertThat(CcPromptFixture.CC_BASE_COMPACT_PROMPT).contains("   - [...]\n");
        assertThat(CcPromptFixture.CC_BASE_COMPACT_PROMPT).contains("   - [File Name 2]\n");
        assertThat(CcPromptFixture.CC_BASE_COMPACT_PROMPT).contains("6. All user messages: \n");
        assertThat(CcPromptFixture.CC_BASE_COMPACT_PROMPT)
            .contains("                       If there is a next step, include direct quotes");
    }
}
