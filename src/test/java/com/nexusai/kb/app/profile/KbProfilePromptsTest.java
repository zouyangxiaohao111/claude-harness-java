package com.nexusai.kb.app.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbProfilePrompts} 与 {@link KbProfileService#sanitize} 的纯函数测试 · 批次 W4。
 */
class KbProfilePromptsTest {

    @Nested
    @DisplayName("提示词")
    class Prompts {

        @Test
        @DisplayName("系统提示词含三条硬约束")
        void systemHasThreeRules() {
            String s = KbProfilePrompts.SYSTEM;
            // ① 只依据标题
            assertThat(s).contains("只依据");
            // ② 不许营销用语
            assertThat(s).contains("营销");
            // ③ 主题不明确时如实说
            assertThat(s).contains("不明确");
            // ④ 只输出正文（防模型加标题/项目符号）
            assertThat(s).contains("只输出描述正文");
        }

        @Test
        @DisplayName("用户消息列出全部标题（上限 200）")
        void userMessageListsTitles() {
            List<String> titles = List.of("文档甲", "文档乙");
            String msg = KbProfilePrompts.userMessage("测试库", titles);
            assertThat(msg).contains("测试库").contains("共 2 条")
                .contains("- 文档甲").contains("- 文档乙");
        }

        @Test
        @DisplayName("超过 200 条要写「其余 N 条略」而不是静默截断")
        void truncatesWithNotice() {
            List<String> titles = new java.util.ArrayList<>();
            for (int i = 0; i < 250; i++) {
                titles.add("文档" + i);
            }
            String msg = KbProfilePrompts.userMessage("库", titles);
            assertThat(msg).contains("其余 50 条略");
            // 第 201 条起不应出现
            assertThat(msg).doesNotContain("- 文档200");
        }

        @Test
        @DisplayName("空白标题被跳过，不产生空行项")
        void skipsBlankTitles() {
            String msg = KbProfilePrompts.userMessage("库",
                java.util.Arrays.asList("甲", "  ", null, "乙"));
            assertThat(msg).doesNotContain("- \n").contains("- 甲").contains("- 乙");
        }

        @Test
        @DisplayName("库名为空时用「（未命名）」占位")
        void unnamedLibrary() {
            assertThat(KbProfilePrompts.userMessage(null, List.of("a")))
                .contains("（未命名）");
        }

        @Test
        @DisplayName("空库有固定文案（⛔ 不调模型）")
        void emptyProfileIsFixed() {
            assertThat(KbProfilePrompts.EMPTY_PROFILE).isNotEmpty();
            assertThat(KbProfilePrompts.EMPTY_PROFILE).contains("暂无内容");
        }
    }

    @Nested
    @DisplayName("输出清洗")
    class Sanitize {

        @Test
        @DisplayName("去掉 ``` 围栏（模型很爱把纯文本也包起来）")
        void stripsCodeFence() {
            assertThat(KbProfileService.sanitize("```\n这是一段描述。\n```"))
                .isEqualTo("这是一段描述。");
        }

        @Test
        @DisplayName("去掉「描述：」这类前缀")
        void stripsPrefix() {
            assertThat(KbProfileService.sanitize("描述：这是一段描述。"))
                .isEqualTo("这是一段描述。");
        }

        @Test
        @DisplayName("去掉首尾成对引号")
        void stripsQuotes() {
            assertThat(KbProfileService.sanitize("\"这是一段描述。\""))
                .isEqualTo("这是一段描述。");
        }

        @Test
        @DisplayName("null / 空白返回空串")
        void handlesNullAndBlank() {
            assertThat(KbProfileService.sanitize(null)).isEmpty();
            assertThat(KbProfileService.sanitize("   ")).isEmpty();
        }

        @Test
        @DisplayName("超长截断到上限")
        void truncatesLongOutput() {
            String longText = "字".repeat(2000);
            assertThat(KbProfileService.sanitize(longText)).hasSize(1000);
        }

        @Test
        @DisplayName("正常文本原样返回（⛔ 不过度清洗）")
        void leavesNormalTextAlone() {
            String normal = "该知识库覆盖农村污水治理政策与运维规范。";
            assertThat(KbProfileService.sanitize(normal)).isEqualTo(normal);
        }
    }

    @Nested
    @DisplayName("生成结果")
    class Result {

        /** 造一个最小画像对象（record 的 profile 字段 2026-11-11 起是对象）。 */
        private KbKnowledgeBaseProfile profile(String gist) {
            KbKnowledgeBaseProfile p = new KbKnowledgeBaseProfile();
            p.setGist(gist);
            return p;
        }

        @Test
        @DisplayName("只有 generated 表示真的调了模型")
        void usedModelOnlyForGenerated() {
            assertThat(new KbProfileService.Result(profile("x"), "generated", 1, "m").usedModel()).isTrue();
            assertThat(new KbProfileService.Result(profile("x"), "empty", 0, "m").usedModel()).isFalse();
            assertThat(new KbProfileService.Result(profile("x"), "unavailable", 0, "m").usedModel()).isFalse();
            assertThat(new KbProfileService.Result(profile("x"), "failed", 0, "m").usedModel()).isFalse();
            // ⭐ 2026-11-11 新增状态：聚合未变 ⇒ 沿用旧描述 ⇒ **没有**调模型
            assertThat(new KbProfileService.Result(profile("x"), "unchanged", 1, "m").usedModel()).isFalse();
        }

        @Test
        @DisplayName("gist() 从画像对象取正文")
        void gistReadsProfile() {
            assertThat(new KbProfileService.Result(profile("覆盖污水治理"), "generated", 1, "m").gist())
                .isEqualTo("覆盖污水治理");
            assertThat(new KbProfileService.Result(null, "failed", 0, null).gist()).isEmpty();
        }
    }

    @Nested
    @DisplayName("知识库级聚合（照源站 BuildKnowledgeBaseProfileAggregate）")
    class Aggregate {

        /** 一篇带画像的文档。 */
        private void addDoc(KbProfileAggregate agg, String id, String title, String profileJson) {
            agg.add(id, title, "md", null, "2026-01-01 00:00:00", List.of(), profileJson);
        }

        @Test
        @DisplayName("统计文档数与带画像的文档数")
        void countsDocuments() {
            KbProfileAggregate agg = new KbProfileAggregate();
            addDoc(agg, "d1", "甲", "{\"gist\":\"讲排水的\",\"topics\":[\"排水\"],\"doc_type\":\"规范\",\"typical_question\":\"排水怎么做\"}");
            addDoc(agg, "d2", "乙", null);
            assertThat(agg.documentCount()).isEqualTo(2);
            assertThat(agg.profiledCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("主题按出现次数降序（同数按首次出现）")
        void topicsOrderedByCount() {
            KbProfileAggregate agg = new KbProfileAggregate();
            // ⚠️ 插入顺序与期望顺序**故意相反**，且次数**必须不同**：
            //    「排水」只出现 1 次但先插，「管网」出现 2 次后插。
            //    ⇒ 只有真的按 count 降序排，才会得到「管网, 排水」。
            //    ⚠️ 次数相同就排不出来（稳定排序保持插入序）⇒ 那样的数据是假绿。
            addDoc(agg, "d1", "甲", "{\"topics\":[\"排水\"]}");
            addDoc(agg, "d2", "乙", "{\"topics\":[\"管网\"]}");
            addDoc(agg, "d3", "丙", "{\"topics\":[\"管网\"]}");
            assertThat(agg.orderedTopics()).containsExactly("管网", "排水");
        }

        @Test
        @DisplayName("主题键大小写/空白归一 ⇒ 算同一个主题")
        void topicKeyNormalized() {
            KbProfileAggregate agg = new KbProfileAggregate();
            addDoc(agg, "d1", "甲", "{\"topics\":[\"Drainage\"]}");
            addDoc(agg, "d2", "乙", "{\"topics\":[\"drainage\"]}");
            assertThat(agg.orderedTopics()).hasSize(1);
        }

        @Test
        @DisplayName("指纹与文档顺序无关（同批文档恒等）")
        void hashStableAcrossOrder() {
            KbProfileAggregate a = new KbProfileAggregate();
            addDoc(a, "d1", "甲", "{\"gist\":\"x\"}");
            addDoc(a, "d2", "乙", "{\"gist\":\"y\"}");
            KbProfileAggregate b = new KbProfileAggregate();
            addDoc(b, "d2", "乙", "{\"gist\":\"y\"}");
            addDoc(b, "d1", "甲", "{\"gist\":\"x\"}");
            assertThat(a.hash()).isEqualTo(b.hash());
        }

        @Test
        @DisplayName("内容变了 ⇒ 指纹变了（这才需要重新调模型）")
        void hashChangesWithContent() {
            KbProfileAggregate a = new KbProfileAggregate();
            addDoc(a, "d1", "甲", "{\"gist\":\"x\"}");
            KbProfileAggregate b = new KbProfileAggregate();
            addDoc(b, "d1", "甲", "{\"gist\":\"改了\"}");
            assertThat(a.hash()).isNotEqualTo(b.hash());
        }

        @Test
        @DisplayName("没有文档 ⇒ 空聚合")
        void emptyWhenNoDocs() {
            assertThat(new KbProfileAggregate().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("典型问题按主题轮转取样，不重复")
        void sampleQuestionsSpread() {
            KbProfileAggregate agg = new KbProfileAggregate();
            addDoc(agg, "d1", "甲", "{\"topics\":[\"排水\"],\"typical_question\":\"排水怎么排\"}");
            addDoc(agg, "d2", "乙", "{\"topics\":[\"管网\"],\"typical_question\":\"管网怎么建\"}");
            assertThat(agg.sampleQuestions()).containsExactly("排水怎么排", "管网怎么建");
        }
    }
}
