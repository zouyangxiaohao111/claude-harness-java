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

        @Test
        @DisplayName("只有 generated 表示真的调了模型")
        void usedModelOnlyForGenerated() {
            assertThat(new KbProfileService.Result("x", "generated", 1, "m").usedModel()).isTrue();
            assertThat(new KbProfileService.Result("x", "empty", 0, "m").usedModel()).isFalse();
            assertThat(new KbProfileService.Result("x", "unavailable", 0, "m").usedModel()).isFalse();
            assertThat(new KbProfileService.Result("x", "failed", 0, "m").usedModel()).isFalse();
        }
    }
}
