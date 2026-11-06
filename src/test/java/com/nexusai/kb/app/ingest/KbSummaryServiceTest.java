package com.nexusai.kb.app.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbSummaryService} 纯函数测试 · 批次 W6。
 *
 * <p>重点：抽样策略（覆盖全文而不是只取开头）与 JSON 宽松解析。
 */
class KbSummaryServiceTest {

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @Nested
    @DisplayName("长内容抽样")
    class Sampling {

        @Test
        @DisplayName("短内容原样返回（不加任何标记）")
        void shortContentUntouched() {
            assertThat(KbSummaryService.sampleLongContent("短正文", 100)).isEqualTo("短正文");
        }

        @Test
        @DisplayName("null ⇒ 空串")
        void nullSafe() {
            assertThat(KbSummaryService.sampleLongContent(null, 100)).isEmpty();
        }

        @Test
        @DisplayName("⭐⭐ 长内容同时保留开头与结尾（只取开头会把多主题文档概括成单主题）")
        void keepsHeadAndTail() {
            String content = "开头" + repeat('中', 10000) + "结尾";
            String out = KbSummaryService.sampleLongContent(content, 1000);
            assertThat(out).startsWith("开头");
            assertThat(out).endsWith("结尾");
        }

        @Test
        @DisplayName("⭐⭐ 抽样标记与提示词里写的字面量一字不差（否则模型读不到「这是摘录」的指令）")
        void omitMarkerMatchesPrompt() {
            String out = KbSummaryService.sampleLongContent(repeat('字', 20000), 500);
            assertThat(out).contains("[...content omitted...]");
            assertThat(KbPostProcessPrompts.SUMMARY_SYSTEM).contains("[...content omitted...]");
        }

        @Test
        @DisplayName("抽样后长度不超过上限")
        void withinLimit() {
            String out = KbSummaryService.sampleLongContent(repeat('字', 50000), 2000);
            assertThat(out.codePointCount(0, out.length())).isLessThanOrEqualTo(2000);
        }

        @Test
        @DisplayName("预算极小时宁可一刀切也不输出畸形结果")
        void tinyBudget() {
            String out = KbSummaryService.sampleLongContent(repeat('字', 1000), 50);
            assertThat(out.codePointCount(0, out.length())).isLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("emoji 按 code point 处理，不会切碎代理对")
        void emojiNotSplit() {
            String out = KbSummaryService.sampleLongContent("🌊".repeat(3000), 200);
            // 若切在代理对中间会产生 \uFFFD 或非法字符
            assertThat(out).doesNotContain("\uFFFD");
            out.codePoints().forEach(cp ->
                assertThat(Character.isValidCodePoint(cp)).isTrue());
        }
    }

    @Nested
    @DisplayName("JSON 解析")
    class Parsing {

        @Test
        @DisplayName("纯 JSON")
        void plainJson() {
            KbSummaryService.SummaryResult r = KbSummaryService.parseSummary(
                "{\"summary\":\"这是摘要\",\"gist\":\"要点\"}");
            assertThat(r).isNotNull();
            assertThat(r.summary()).isEqualTo("这是摘要");
        }

        @Test
        @DisplayName("带 ```json 围栏")
        void fenced() {
            KbSummaryService.SummaryResult r = KbSummaryService.parseSummary(
                "```json\n{\"summary\":\"摘要\"}\n```");
            assertThat(r).isNotNull();
            assertThat(r.summary()).isEqualTo("摘要");
        }

        @Test
        @DisplayName("JSON 前后有废话（模型的「好的，以下是结果：」）")
        void surroundingProse() {
            KbSummaryService.SummaryResult r = KbSummaryService.parseSummary(
                "好的，以下是结果：\n{\"summary\":\"摘要\"}\n希望能帮到你");
            assertThat(r).isNotNull();
            assertThat(r.summary()).isEqualTo("摘要");
        }

        @Test
        @DisplayName("profile 保留完整 JSON 原文（供 kb_knowledge.profile 落库）")
        void profileKeepsFullJson() {
            KbSummaryService.SummaryResult r = KbSummaryService.parseSummary(
                "{\"summary\":\"摘要\",\"gist\":\"要点\",\"topics\":[\"甲\"],\"doc_type\":\"报告\"}");
            assertThat(r.profileJson()).contains("\"gist\"").contains("\"topics\"")
                .contains("\"doc_type\"");
        }

        @Test
        @DisplayName("坏输入 ⇒ null")
        void badInputs() {
            assertThat(KbSummaryService.parseSummary(null)).isNull();
            assertThat(KbSummaryService.parseSummary("")).isNull();
            assertThat(KbSummaryService.parseSummary("没有 JSON")).isNull();
            assertThat(KbSummaryService.parseSummary("{坏的")).isNull();
            assertThat(KbSummaryService.parseSummary("[1,2]")).isNull();
        }

        @Test
        @DisplayName("summary 为空串仍能解析出来（由调用方判 empty_summary）")
        void emptySummaryParsed() {
            KbSummaryService.SummaryResult r = KbSummaryService.parseSummary(
                "{\"summary\":\"  \",\"gist\":\"有要点\"}");
            assertThat(r).isNotNull();
            assertThat(r.summary()).isEmpty();
        }
    }

    @Nested
    @DisplayName("摘要块正文格式")
    class ChunkContent {

        @Test
        @DisplayName("完整画像 ⇒ 四行齐全")
        void allFields() {
            String c = KbSummaryService.summaryChunkContent("这是摘要",
                "{\"gist\":\"要点\",\"topics\":[\"甲\",\"乙\"],\"doc_type\":\"报告\"}");
            assertThat(c).isEqualTo("摘要：这是摘要\n要点：要点\n主题：甲、乙\n类型：报告");
        }

        @Test
        @DisplayName("只有摘要 ⇒ 只有一行（不输出空行）")
        void summaryOnly() {
            assertThat(KbSummaryService.summaryChunkContent("摘要", null)).isEqualTo("摘要：摘要");
            assertThat(KbSummaryService.summaryChunkContent("摘要", "{}")).isEqualTo("摘要：摘要");
        }

        @Test
        @DisplayName("空 topics 数组 ⇒ 不输出「主题：」行")
        void emptyTopics() {
            String c = KbSummaryService.summaryChunkContent("摘要",
                "{\"gist\":\"要点\",\"topics\":[],\"doc_type\":\"报告\"}");
            assertThat(c).doesNotContain("主题：");
        }

        @Test
        @DisplayName("topics 里的空白项被忽略")
        void blankTopicsDropped() {
            String c = KbSummaryService.summaryChunkContent("摘要",
                "{\"topics\":[\"甲\",\"  \",\"乙\"]}");
            assertThat(c).contains("主题：甲、乙");
        }

        @Test
        @DisplayName("坏 profile JSON ⇒ 只输出摘要行（不抛）")
        void badProfile() {
            assertThat(KbSummaryService.summaryChunkContent("摘要", "{坏")).isEqualTo("摘要：摘要");
        }
    }
}
