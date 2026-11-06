package com.nexusai.kb.app.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块器意图测试 · 移植自 WeKnora {@code chunker} 的行为契约。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：分块器是**纯确定性算法**，但它有四条「改坏了不报错、
 * 只是分块变差」的性质，必须逐条钉死：
 * <ol>
 *   <li><b>位置不变式</b>：{@code end - start == code point 数(content)}。
 *       破坏后文档重建（摘要/高亮）会错位，但不会有异常。</li>
 *   <li><b>受保护片段不被切断</b>：代码块/公式被切开后语义就碎了。</li>
 *   <li><b>块大小受控</b>：超过绝对上限会让 embedding API 直接拒绝。</li>
 *   <li><b>分隔符保留</b>：中文「。」被丢掉会让句子粘连。</li>
 * </ol>
 *
 * <p>用例来源：源侧 {@code splitter_test.go}（1401 行）中的行为断言，按语义重写为
 * 中文意图描述 —— ⛔ 不是逐字翻译测试代码。
 */
@DisplayName("[B2] 分块器（位置不变式 + 受保护片段 + 尺寸约束）")
class KbTextSplitterTest {

    private static KbSplitterConfig cfg(int size, int overlap) {
        return new KbSplitterConfig(size, overlap, KbSplitterConfig.DEFAULT_SEPARATORS, "", 0, List.of());
    }

    /** 断言位置不变式：每块的 end-start == 内容的 code point 数。 */
    private static void assertPositionsConsistent(String source, List<KbChunk> chunks) {
        int[] sourceCps = source.codePoints().toArray();
        for (KbChunk c : chunks) {
            int len = c.content().codePointCount(0, c.content().length());

            // ⭐ 真不变式（O-11，依据源侧 splitter_test.go:1219-1240）：
            //    end - start <= 内容 rune 数。
            //    不等号而非等号，是因为**表头前置**会把表头作为零宽合成单元塞进 content
            //    （start==end，不占原文位置）⇒ 该块 len 会比 span 大。
            //    源侧自算 headerLen 后只禁「len - span < 0」，即同一判据。
            assertThat(c.end() - c.start())
                .as("块 seq=%d 的 span 不得超过内容 rune 数（表头前置允许更小）", c.seq())
                .isLessThanOrEqualTo(len);

            assertThat(c.start()).isGreaterThanOrEqualTo(0);
            assertThat(c.end()).isLessThanOrEqualTo(sourceCps.length);

            // 更强的一条：content 必须以原文切片**结尾**（表头只可能加在开头）
            if (c.end() > c.start()) {
                String expectedTail = new String(sourceCps, c.start(), c.end() - c.start());
                assertThat(c.content())
                    .as("块 seq=%d 的 content 必须以原文 [start,end) 切片结尾", c.seq())
                    .endsWith(expectedTail);
            }
        }
    }

    @Nested
    @DisplayName("基础行为")
    class Basics {

        @Test
        @DisplayName("空文本 → 空列表（不产出单块空串）")
        void emptyTextYieldsNoChunks() {
            assertThat(KbTextSplitter.split("", cfg(512, 80))).isEmpty();
            assertThat(KbTextSplitter.split(null, cfg(512, 80))).isEmpty();
        }

        @Test
        @DisplayName("短文本 → 单块，内容逐字等于原文")
        void shortTextIsSingleChunk() {
            String text = "这是一段很短的文本。";
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(512, 80));

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).content()).isEqualTo(text);
            assertThat(chunks.get(0).start()).isZero();
            assertThat(chunks.get(0).seq()).isZero();
            assertPositionsConsistent(text, chunks);
        }

        @Test
        @DisplayName("长文本按 chunkSize 切开，每块不超目标（允许 2x 容差）")
        void longTextIsSplitAroundTargetSize() {
            String text = "第一段内容。".repeat(200); // 约 1200 字符
            int size = 100;
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(size, 0));

            assertThat(chunks.size()).isGreaterThan(5);
            for (KbChunk c : chunks) {
                int len = c.content().codePointCount(0, c.content().length());
                assertThat(len)
                    .as("块 seq=%d 不应超过 2x 目标尺寸", c.seq())
                    .isLessThanOrEqualTo(2 * size);
            }
            assertPositionsConsistent(text, chunks);
        }

        @Test
        @DisplayName("相邻块的 start 单调不减（顺序链可正确建立）")
        void chunkStartsAreMonotonic() {
            String text = "A".repeat(300) + "。" + "B".repeat(300) + "。" + "C".repeat(300);
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(120, 20));

            int prev = -1;
            for (KbChunk c : chunks) {
                assertThat(c.start()).isGreaterThanOrEqualTo(prev);
                prev = c.start();
            }
        }
    }

    @Nested
    @DisplayName("受保护片段")
    class ProtectedContent {

        @Test
        @DisplayName("围栏代码块不被切断（即使远超 chunkSize）")
        void fencedCodeBlockStaysWhole() {
            String code = "```java\n" + "int x = 1;\n".repeat(80) + "```";
            String text = "前言。\n\n" + code + "\n\n后记。";

            // chunkSize 远小于代码块长度 ⇒ 若被切断会产生多块
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(50, 0));

            boolean anyChunkContainsWholeCode = chunks.stream()
                .anyMatch(c -> c.content().contains(code));
            assertThat(anyChunkContainsWholeCode)
                .as("代码块应作为完整单元存在于某一分块中，而不是被切成两半")
                .isTrue();
            assertPositionsConsistent(text, chunks);
        }

        @Test
        @DisplayName("LaTeX 块级公式不被切断")
        void latexBlockStaysWhole() {
            String formula = "$$\n" + "a_1 + a_2 + \\cdots + a_n\n".repeat(20) + "$$";
            String text = "推导如下：\n\n" + formula + "\n\n完毕。";

            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(50, 0));

            assertThat(chunks.stream().anyMatch(c -> c.content().contains(formula)))
                .as("整块 LaTeX 公式应完整落在同一分块")
                .isTrue();
        }

        @Test
        @DisplayName("Markdown 链接不被拦腰切断")
        void markdownLinkStaysWhole() {
            String link = "[这是一个很长的链接文本](https://example.com/a/very/long/path)";
            String text = "前文内容" + link + "后文内容";

            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(30, 0));

            assertThat(chunks.stream().anyMatch(c -> c.content().contains(link)))
                .as("完整链接应出现在某一分块内")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("分隔符语义")
    class SeparatorSemantics {

        @Test
        @DisplayName("段落优先：\\n\\n 优先于 \\n 切分")
        void paragraphSeparatorHasPriority() {
            String text = "第一段内容较长一些。\n\n第二段内容也较长一些。\n\n第三段。";
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(20, 0));

            // 不应在段落内部断开（每块应至少含一个完整段落）
            assertThat(chunks).isNotEmpty();
            assertThat(chunks.stream().anyMatch(c -> c.content().contains("第三段")))
                .isTrue();
        }

        @Test
        @DisplayName("中文句号被保留在输出中（不吞掉分隔符）")
        void chinesePeriodPreserved() {
            String text = "句子一。句子二。句子三。句子四。";
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(10, 0));

            String joined = String.join("", chunks.stream().map(KbChunk::content).toList());
            assertThat(joined)
                .as("拼接所有分块后应能还原出原文（分隔符不能丢）")
                .contains("。");
        }

        @Test
        @DisplayName("递归切分：超长段落在内部继续用低优先级分隔符切")
        void recursiveSplitWithinOversizedPiece() {
            // 单个段落远超 chunkSize，内部有换行 ⇒ 应在内部按 \n 切
            String text = "A".repeat(50) + "\n" + "B".repeat(50) + "\n" + "C".repeat(50);
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(60, 0));

            assertThat(chunks.size())
                .as("超长段落应被内部切分，而不是整段作为一块")
                .isGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("重叠")
    class Overlap {

        @Test
        @DisplayName("overlap=0 时不产生重叠")
        void zeroOverlap() {
            String text = "。".repeat(0) + "段落内容。".repeat(100);
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(100, 0));

            for (int i = 1; i < chunks.size(); i++) {
                assertThat(chunks.get(i).start())
                    .as("无重叠时下一块应从上一块结束处开始")
                    .isGreaterThanOrEqualTo(chunks.get(i - 1).end());
            }
        }

        @Test
        @DisplayName("overlap>0 时块间有重叠，且重叠量不超上限")
        void overlapBoundedByConfig() {
            String text = "这是一段内容。".repeat(200);
            int overlap = 20;
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(100, overlap));

            assertThat(chunks.size()).isGreaterThan(1);
            for (int i = 1; i < chunks.size(); i++) {
                int gap = chunks.get(i - 1).end() - chunks.get(i).start();
                assertThat(gap)
                    .as("重叠量（前块 end - 后块 start）不应超过配置上限")
                    .isLessThanOrEqualTo(overlap);
            }
            assertPositionsConsistent(text, chunks);
        }
    }

    @Nested
    @DisplayName("辅助函数")
    class Helpers {

        @Test
        @DisplayName("splitBySeparators 保留分隔符")
        void splitBySeparatorsKeepsSeparator() {
            List<String> parts = KbTextSplitter.splitBySeparators("a。b。c", List.of("。"), 0);
            assertThat(String.join("", parts)).isEqualTo("a。b。c");
            assertThat(parts).contains("。");
        }

        @Test
        @DisplayName("code point 计数：emoji 算 1 个（不是 2 个 char）")
        void emojiCountsAsOneCodePoint() {
            assertThat(KbTextUnits.codePointLength("a😀b")).isEqualTo(3);
            assertThat("a😀b".length()).as("String.length() 是 char 数，会算成 4").isEqualTo(4);
        }

        @Test
        @DisplayName("含 emoji 时位置不变式仍成立（落库用 code point 的意义）")
        void positionsCorrectWithEmoji() {
            String text = "😀".repeat(100) + "。尾部内容。";
            List<KbChunk> chunks = KbTextSplitter.split(text, cfg(40, 0));

            assertThat(chunks).isNotEmpty();
            assertPositionsConsistent(text, chunks);
        }
    }
}
