package com.nexusai.kb.app.ingest;

import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbAutoTagService} 纯函数测试 · 批次 W6。
 *
 * <p><b>⭐ {@code selectTagIds} 是全类最危险的地方</b>：它把模型的「序号」翻译成
 * 真实标签 ID。越界判断写错会把标签打到<b>别的文档</b>上，且不报错。
 */
class KbAutoTagServiceTest {

    private static final List<String> IDS = List.of("tag-A", "tag-B", "tag-C");

    private static KbAutoTagService.Match m(int index, double conf) {
        return new KbAutoTagService.Match(index, conf);
    }

    @Nested
    @DisplayName("序号映射")
    class SelectTagIds {

        @Test
        @DisplayName("序号 1 ⇒ 第一个候选")
        void indexIsOneBased() {
            assertThat(KbAutoTagService.selectTagIds(IDS, List.of(m(1, 0.9)), 5))
                .containsExactly("tag-A");
        }

        @Test
        @DisplayName("序号 3 ⇒ 第三个候选（不是越界）")
        void lastIndexValid() {
            assertThat(KbAutoTagService.selectTagIds(IDS, List.of(m(3, 0.9)), 5))
                .containsExactly("tag-C");
        }

        @Test
        @DisplayName("⭐⭐ 序号 0 / 4 越界被丢弃（模型有时从 0 开始数）")
        void outOfRangeDropped() {
            assertThat(KbAutoTagService.selectTagIds(IDS, List.of(m(0, 0.99), m(4, 0.99)), 5))
                .isEmpty();
        }

        @Test
        @DisplayName("⭐ 置信度刚好 0.75 ⇒ 保留（阈值是闭区间）")
        void exactThresholdKept() {
            assertThat(KbAutoTagService.selectTagIds(IDS, List.of(m(1, 0.75)), 5))
                .containsExactly("tag-A");
        }

        @Test
        @DisplayName("置信度 0.749 ⇒ 丢弃")
        void belowThresholdDropped() {
            assertThat(KbAutoTagService.selectTagIds(IDS, List.of(m(1, 0.749)), 5)).isEmpty();
        }

        @Test
        @DisplayName("⭐ 全部低于阈值 ⇒ 空（失败即不选，不给个「最像的」凑数）")
        void allLow() {
            assertThat(KbAutoTagService.selectTagIds(IDS,
                List.of(m(1, 0.5), m(2, 0.4), m(3, 0.3)), 5)).isEmpty();
        }

        @Test
        @DisplayName("重复序号去重")
        void dedup() {
            assertThat(KbAutoTagService.selectTagIds(IDS,
                List.of(m(1, 0.9), m(1, 0.85)), 5)).containsExactly("tag-A");
        }

        @Test
        @DisplayName("按置信度降序取（高置信度优先进 maxTags）")
        void sortedByConfidence() {
            assertThat(KbAutoTagService.selectTagIds(IDS,
                List.of(m(1, 0.80), m(3, 0.99)), 1)).containsExactly("tag-C");
        }

        @Test
        @DisplayName("超过 maxTags 时截断")
        void respectsMaxTags() {
            assertThat(KbAutoTagService.selectTagIds(IDS,
                List.of(m(1, 0.9), m(2, 0.9), m(3, 0.9)), 2)).hasSize(2);
        }

        @Test
        @DisplayName("maxTags <= 0 ⇒ 空")
        void zeroMaxTags() {
            assertThat(KbAutoTagService.selectTagIds(IDS, List.of(m(1, 1.0)), 0)).isEmpty();
        }

        @Test
        @DisplayName("空候选 / null 匹配 ⇒ 空（不抛）")
        void emptyInputs() {
            assertThat(KbAutoTagService.selectTagIds(List.of(), List.of(m(1, 1.0)), 5)).isEmpty();
            assertThat(KbAutoTagService.selectTagIds(null, List.of(m(1, 1.0)), 5)).isEmpty();
            assertThat(KbAutoTagService.selectTagIds(IDS, null, 5)).isEmpty();
        }

        @Test
        @DisplayName("候选 ID 为空白 ⇒ 丢弃")
        void blankIdDropped() {
            assertThat(KbAutoTagService.selectTagIds(
                List.of("", "tag-B"), List.of(m(1, 1.0), m(2, 0.9)), 5))
                .containsExactly("tag-B");
        }
    }

    @Nested
    @DisplayName("解析模型输出")
    class ParseMatches {

        @Test
        @DisplayName("标准 JSON")
        void normal() {
            List<KbAutoTagService.Match> out = KbAutoTagService.parseMatches(
                "{\"matches\":[{\"index\":1,\"confidence\":0.92}]}");
            assertThat(out).hasSize(1);
            assertThat(out.get(0).index()).isEqualTo(1);
            assertThat(out.get(0).confidence()).isEqualTo(0.92);
        }

        @Test
        @DisplayName("带 ```json 围栏")
        void fenced() {
            assertThat(KbAutoTagService.parseMatches(
                "```json\n{\"matches\":[{\"index\":2,\"confidence\":0.8}]}\n```")).hasSize(1);
        }

        @Test
        @DisplayName("空 matches 数组 ⇒ 空列表（不是 null）")
        void emptyMatches() {
            assertThat(KbAutoTagService.parseMatches("{\"matches\":[]}")).isEmpty();
        }

        @Test
        @DisplayName("缺 matches 键 ⇒ 空列表")
        void missingKey() {
            assertThat(KbAutoTagService.parseMatches("{\"other\":1}")).isEmpty();
        }

        @Test
        @DisplayName("⭐⭐ 完全解析不了 ⇒ null（调用方判失败可重试，而不是当成「没匹配」）")
        void unparseable() {
            assertThat(KbAutoTagService.parseMatches(null)).isNull();
            assertThat(KbAutoTagService.parseMatches("")).isNull();
            assertThat(KbAutoTagService.parseMatches("我没有匹配的标签")).isNull();
        }

        @Test
        @DisplayName("缺字段默认 0（会被后续阈值判掉）")
        void missingFields() {
            List<KbAutoTagService.Match> out = KbAutoTagService.parseMatches(
                "{\"matches\":[{\"index\":1}]}");
            assertThat(out.get(0).confidence()).isZero();
        }
    }

    @Nested
    @DisplayName("拼正文")
    class BuildContent {

        private static KbChunkRecord chunk(String content, int startAt) {
            KbChunkRecord c = new KbChunkRecord();
            c.setContent(content);
            c.setStartAt(startAt);
            return c;
        }

        @Test
        @DisplayName("包含文档名与已有摘要")
        void includesTitleAndDescription() {
            KbKnowledgeRecord k = new KbKnowledgeRecord();
            k.setTitle("农污管理办法");
            k.setDescription("讲了验收标准");
            String c = KbAutoTagService.buildDocumentContent(k, List.of(chunk("正文", 0)));
            assertThat(c).contains("文档名：农污管理办法").contains("已有摘要：讲了验收标准")
                .contains("正文");
        }

        @Test
        @DisplayName("⭐ 与摘要提示词不同：这里**允许**用文档名（打标只需从既有标签里选）")
        void titleAllowedHere() {
            KbKnowledgeRecord k = new KbKnowledgeRecord();
            k.setTitle("MX5280.pdf");
            assertThat(KbAutoTagService.buildDocumentContent(k, List.of())).contains("MX5280.pdf");
        }

        @Test
        @DisplayName("没有标题/摘要 ⇒ 不出现空标签行")
        void noEmptyLabels() {
            String c = KbAutoTagService.buildDocumentContent(
                new KbKnowledgeRecord(), List.of(chunk("正文", 0)));
            assertThat(c).isEqualTo("正文");
        }

        @Test
        @DisplayName("跳过空白分块")
        void skipsBlankChunks() {
            String c = KbAutoTagService.buildDocumentContent(new KbKnowledgeRecord(),
                List.of(chunk("甲", 0), chunk("   ", 1), chunk("乙", 2)));
            assertThat(c).isEqualTo("甲\n\n乙");
        }

        @Test
        @DisplayName("超长被截到 16000 code point")
        void truncates() {
            String c = KbAutoTagService.buildDocumentContent(new KbKnowledgeRecord(),
                List.of(chunk("字".repeat(20000), 0)));
            assertThat(c.codePointCount(0, c.length())).isEqualTo(16000);
        }

        @Test
        @DisplayName("null 分块列表 / null 文档 ⇒ 不抛")
        void nullSafe() {
            assertThat(KbAutoTagService.buildDocumentContent(null, null)).isEmpty();
            assertThat(KbAutoTagService.buildDocumentContent(new KbKnowledgeRecord(), null)).isEmpty();
        }
    }
}
