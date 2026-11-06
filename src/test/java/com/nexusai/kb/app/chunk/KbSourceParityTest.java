package com.nexusai.kb.app.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源侧行为对照测试 · 逐条复刻 WeKnora {@code splitter_test.go} 的断言。
 *
 * <p><b>为什么需要它（与 {@link KbTextSplitterTest} 的分工）</b>：
 * {@code KbTextSplitterTest} 测的是「我们自己的意图」（位置不变式、受保护片段等）；
 * 本文件测的是 <b>「与源侧行为一致」</b> —— 每个用例的期望值<b>直接抄自源侧测试</b>
 * （{@code splitter_test.go} 的 {@code want} 字段），不是我编的。
 *
 * <p><b>⚠️ 本机未安装 Go，无法跑源侧 oracle 做实时差分</b>
 * ⇒ 用源侧测试里<b>已经固化下来的期望值</b>作为 oracle。这是次优但可验证的做法：
 * 源侧作者写下这些 {@code want} 时是通过的；只要我们输出相同，即行为对齐。
 * 该限制已登记在 {@code OPEN_ISSUES.md} O-10。
 *
 * <p>覆盖的源侧用例：
 * <ul>
 *   <li>{@code TestSplitText_BasicASCII}（{@code :10}）</li>
 *   <li>{@code TestSplitText_ChineseText_StartEndAreRuneOffsets}（{@code :26}）</li>
 *   <li>{@code TestSplitText_ChineseMultiChunk_StartEndConsistency}（{@code :53}）</li>
 *   <li>{@code TestSplitText_MixedChineseAndASCII}（{@code :97}）</li>
 *   <li>{@code TestSplitText_RecursiveSeparators_NoOversizeChunks}（{@code :176}）</li>
 *   <li>{@code TestSplitText_SingleCharChinese}（{@code :208}）</li>
 *   <li>{@code TestSplitText_OverlapChunks_NonNegativeStart}（{@code :257}）</li>
 *   <li>{@code TestFindSemanticOverlapBoundary_PriorityThenEarliest}（{@code :273}，10 个子用例全抄）</li>
 *   <li>{@code TestFindSemanticOverlapBoundary_NoConfiguredSemanticSeparator}（{@code :345}）</li>
 *   <li>{@code TestFindSemanticOverlapBoundary_IgnoresProtectedContent}（{@code :359}）</li>
 *   <li>{@code TestFindSemanticOverlapBoundary_FiltersEligibilityBeforePriorityAndPosition}（{@code :371}）</li>
 * </ul>
 */
@DisplayName("[B2] 源侧行为对照（期望值抄自 splitter_test.go）")
class KbSourceParityTest {

    // ── TestSplitText_BasicASCII（源侧 :10）─────────────────────────

    @Test
    @DisplayName("BasicASCII：拼接所有块 == 原文")
    void basicAsciiRoundTrip() {
        String text = "Hello world. This is a test.";
        KbSplitterConfig cfg = new KbSplitterConfig(
            100, 0, List.of(". "), "", 0, List.of());

        List<KbChunk> chunks = KbTextSplitter.split(text, cfg);

        assertThat(chunks).isNotEmpty();
        StringBuilder combined = new StringBuilder();
        for (KbChunk c : chunks) {
            combined.append(c.content());
        }
        assertThat(combined.toString()).isEqualTo(text);
    }

    // ── TestSplitText_ChineseText_StartEndAreRuneOffsets（源侧 :26）──

    @Test
    @DisplayName("中文：Start/End 是 rune 偏移，不是 byte 偏移")
    void chineseStartEndAreRuneOffsets() {
        String text = "你好世界这是一个测试文本用于检验分割位置";
        int runeCount = text.codePointCount(0, text.length());
        // ⚠️ 源侧此处断言「rune 数 != byte 数」，因为 Go 的 len(string) 是 UTF-8 字节数（中文 3 字节）。
        //    Java 的 String.length() 是 UTF-16 code unit 数（BMP 中文每字 1 个）⇒ 与 rune 数相等，
        //    所以这里改断言「UTF-8 字节数 != rune 数」—— 才是源侧想验的那件事（防止误用字节偏移）。
        int utf8Bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(utf8Bytes)
            .as("测试前提：必须有非 ASCII 字符（UTF-8 字节数 != rune 数），否则测不出字节/rune 混淆")
            .isNotEqualTo(runeCount);

        KbSplitterConfig cfg = new KbSplitterConfig(
            100, 0, List.of("\n"), "", 0, List.of());
        List<KbChunk> chunks = KbTextSplitter.split(text, cfg);

        assertThat(chunks).hasSize(1);
        KbChunk c = chunks.get(0);
        assertThat(c.start()).isZero();
        assertThat(c.end())
            .as("End 必须是 rune 数（%d），而不是 byte 数（%d）", runeCount, utf8Bytes)
            .isEqualTo(runeCount);
    }

    // ── TestSplitText_ChineseMultiChunk_StartEndConsistency（源侧 :53）─

    @Test
    @DisplayName("中文多块：位置不变式 + rune 切片还原")
    void chineseMultiChunkConsistency() {
        String line = "这是一段中文内容用于测试分割功能是否正确。";
        String text = (line + "\n\n").repeat(20).replaceAll("\n+$", "");

        KbSplitterConfig cfg = new KbSplitterConfig(
            30, 5, List.of("\n\n", "\n", "。"), "", 0, List.of());
        List<KbChunk> chunks = KbTextSplitter.split(text, cfg);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        assertRuneSlicesMatch(text, chunks);
    }

    // ── TestSplitText_MixedChineseAndASCII（源侧 :97）────────────────

    @Test
    @DisplayName("中英混排：单块且跨度为 rune 数")
    void mixedChineseAscii() {
        String text = "Hello你好World世界Test测试";
        KbSplitterConfig cfg = new KbSplitterConfig(
            100, 0, List.of("\n"), "", 0, List.of());

        List<KbChunk> chunks = KbTextSplitter.split(text, cfg);

        assertThat(chunks).hasSize(1);
        int expectedRuneLen = text.codePointCount(0, text.length());
        assertThat(chunks.get(0).end() - chunks.get(0).start()).isEqualTo(expectedRuneLen);
    }

    // ── TestSplitText_RecursiveSeparators_NoOversizeChunks（源侧 :176）─

    @Test
    @DisplayName("递归分隔符：没有块超过 1.5x chunkSize")
    void recursiveSeparatorsNoOversizeChunks() {
        String body = "This is one fairly short line of text.\n".repeat(50);
        String text = "lead paragraph that is short.\n\n" + body;

        KbSplitterConfig cfg = new KbSplitterConfig(
            300, 30, List.of("\n\n", "\n", ". "), "", 0, List.of());
        List<KbChunk> chunks = KbTextSplitter.split(text, cfg);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        int maxAllowed = cfg.chunkSize() * 3 / 2;
        for (KbChunk c : chunks) {
            int len = c.content().codePointCount(0, c.content().length());
            assertThat(len)
                .as("块 seq=%d 长度 %d 超过 1.5x chunkSize(%d) ⇒ 递归切分缺失",
                    c.seq(), len, maxAllowed)
                .isLessThanOrEqualTo(maxAllowed);
        }
    }

    // ── TestSplitText_SingleCharChinese（源侧 :208）──────────────────

    @Test
    @DisplayName("单个中文字：1 块，范围 [0,1)")
    void singleCharChinese() {
        KbSplitterConfig cfg = new KbSplitterConfig(
            10, 0, List.of("\n"), "", 0, List.of());

        List<KbChunk> chunks = KbTextSplitter.split("你", cfg);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).start()).isZero();
        assertThat(chunks.get(0).end()).isEqualTo(1);
    }

    // ── TestSplitText_OverlapChunks_NonNegativeStart（源侧 :257）─────

    @Test
    @DisplayName("有重叠时 Start 不得为负、End 不得小于 Start")
    void overlapChunksNonNegativeStart() {
        String text = "中文测试内容，".repeat(50);
        KbSplitterConfig cfg = new KbSplitterConfig(
            20, 5, List.of("，"), "", 0, List.of());

        List<KbChunk> chunks = KbTextSplitter.split(text, cfg);

        for (KbChunk c : chunks) {
            assertThat(c.start()).as("块 seq=%d 的 Start 不得为负", c.seq()).isGreaterThanOrEqualTo(0);
            assertThat(c.end())
                .as("块 seq=%d 的 End 不得小于 Start", c.seq())
                .isGreaterThanOrEqualTo(c.start());
        }
    }

    // ── TestFindSemanticOverlapBoundary_PriorityThenEarliest（源侧 :273）─
    //
    // ⚠️ 这 10 个子用例的 want 值逐字抄自源侧测试表。
    //    源侧用 findSemanticOverlapBoundary(text)（=minEnd 0），断言的是「边界之后的尾巴」。

    @Test
    @DisplayName("语义边界：优先级优先，同优先级取最早（10 个源侧用例）")
    void semanticBoundaryPriorityThenEarliest() {
        record Case(String name, String text, String want) {
        }
        List<Case> cases = List.of(
            new Case("段落胜过更早的句号与换行",
                "第一句。第二句\n普通换行后的内容\n\n最后一段", "最后一段"),
            new Case("同优先级取最早句号",
                "第一句。第二句。第三句", "第二句。第三句"),
            new Case("Windows 段落断",
                "第一段。\r\n\r\n第二段", "第二段"),
            new Case("换行胜过更早的句号",
                "第一句。第一行\n第二行", "第二行"),
            new Case("Windows 换行",
                "第一句。第一行\r\n第二行", "第二行"),
            new Case("中文问号",
                "第一句？第二句", "第二句"),
            new Case("中文感叹号",
                "第一句！第二句", "第二句"),
            new Case("英文句点后必须有空格",
                "First sentence. Second sentence", "Second sentence"),
            new Case("英文问号后必须有空格",
                "Question? Answer", "Answer"),
            new Case("英文感叹号后必须有空格",
                "Warning! Continue", "Continue")
        );

        for (Case c : cases) {
            int end = KbSemanticBoundary.findBoundaryEndingAtOrAfter(c.text(), 0);
            assertThat(end)
                .as("用例「%s」应找到语义边界", c.name())
                .isGreaterThanOrEqualTo(0);
            String tail = c.text().substring(c.text().offsetByCodePoints(0, end));
            assertThat(tail)
                .as("用例「%s」的重叠尾巴", c.name())
                .isEqualTo(c.want());
        }
    }

    // ── TestFindSemanticOverlapBoundary_NoConfiguredSemanticSeparator（源侧 :345）─

    @Test
    @DisplayName("无语义分隔符时不得误判边界（含 URL、IP、版本号）")
    void noBoundaryWhenNoSemanticSeparator() {
        List<String> texts = List.of(
            "没有任何语义分隔符的连续文本",
            "只有，逗号；分号：冒号",
            "version1.2 remains one unit",
            "address 192.168.1.1 remains one unit",
            "see https://ex.com?q=1&foo=bar"
        );
        for (String text : texts) {
            int end = KbSemanticBoundary.findBoundaryEndingAtOrAfter(text, 0);
            assertThat(end)
                .as("文本「%s」不应被判定出语义边界（尤其 URL/IP 里的点后没空格）", text)
                .isEqualTo(-1);
        }
    }

    // ── TestFindSemanticOverlapBoundary_IgnoresProtectedContent（源侧 :359）─

    @Test
    @DisplayName("受保护片段内的句点不算边界（行内代码/代码块/公式）")
    void ignoresProtectedContent() {
        List<String> texts = List.of(
            "代码是 `fmt.Println(\"hello. world\")` 后续内容",
            "代码块 ```go\nfmt.Println(\"hello. world\")\n``` 后续内容",
            "公式 $$x. y$$ 后续内容"
        );
        for (String text : texts) {
            int end = KbSemanticBoundary.findBoundaryEndingAtOrAfter(text, 0);
            assertThat(end)
                .as("文本「%s」里的句点位于受保护片段内，不应被当作边界", text)
                .isEqualTo(-1);
        }
    }

    // ── TestFindSemanticOverlapBoundary_FiltersEligibilityBeforePriorityAndPosition（源侧 :371）─

    @Test
    @DisplayName("先按可用性过滤再比优先级：更早但不可用的候选不能压掉更晚可用的")
    void filterEligibilityBeforePriority() {
        // 用例 1：更早的句号在 minEnd 之前（不可用）⇒ 应选后面的句号
        String t1 = "a。bc。XYZ";
        int end1 = KbSemanticBoundary.findBoundaryEndingAtOrAfter(t1, 4);
        assertThat(end1).isGreaterThanOrEqualTo(0);
        assertThat(t1.substring(t1.offsetByCodePoints(0, end1))).isEqualTo("XYZ");

        // 用例 2：段落断在 minEnd 之前（不可用）⇒ 应选后面的问号
        String t2 = "\n\nx? tail";
        int end2 = KbSemanticBoundary.findBoundaryEndingAtOrAfter(t2, 3);
        assertThat(end2).isGreaterThanOrEqualTo(0);
        assertThat(t2.substring(t2.offsetByCodePoints(0, end2))).isEqualTo("tail");
    }

    /** 断言：每块的 rune 切片与 content 逐字相等（源侧 :70-92 的核心断言）。 */
    private static void assertRuneSlicesMatch(String source, List<KbChunk> chunks) {
        int[] runes = source.codePoints().toArray();
        for (KbChunk c : chunks) {
            int contentRuneLen = c.content().codePointCount(0, c.content().length());
            assertThat(c.end() - c.start())
                .as("块 seq=%d：End-Start 必须等于内容 rune 数", c.seq())
                .isEqualTo(contentRuneLen);
            assertThat(c.start()).isGreaterThanOrEqualTo(0);
            assertThat(c.end()).isLessThanOrEqualTo(runes.length);

            StringBuilder sliced = new StringBuilder();
            for (int i = c.start(); i < c.end(); i++) {
                sliced.appendCodePoint(runes[i]);
            }
            assertThat(sliced.toString())
                .as("块 seq=%d：按 rune 切片必须与 content 逐字相等", c.seq())
                .isEqualTo(c.content());
        }
    }
}
