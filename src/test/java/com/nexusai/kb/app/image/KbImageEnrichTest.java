package com.nexusai.kb.app.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片理解测试 · 守两条「静默失效」：
 *
 * <ol>
 *   <li><b>「图里没字」不能当字存下来</b>。模型对纯装饰图会回 {@code No text content.} /
 *       {@code NO_CONTENT}，照存的话图库里每张图都挂一句假 OCR，检索还会命中
 *       {@code no text content} 这种短语。</li>
 *   <li><b>OCR 必须真的进索引</b>。写进 {@code image_info} 但不进索引 = 白跑一次模型调用，
 *       而且完全看不出来（库里明明有字，就是搜不到）。</li>
 * </ol>
 */
class KbImageEnrichTest {

    @Nested
    @DisplayName("「没有文字」的模型回包识别")
    class NoTextReply {

        @Test
        @DisplayName("源站约定的 No text content.（含句号）算空")
        void noTextContent() {
            assertThat(KbImagePrompts.isNoTextReply("No text content.")).isTrue();
            assertThat(KbImagePrompts.isNoTextReply("No text content")).isTrue();
            assertThat(KbImagePrompts.isNoTextReply("  NO TEXT CONTENT.  ")).isTrue();
        }

        @Test
        @DisplayName("⭐ 中文与其它已知形态也算空")
        void otherForms() {
            assertThat(KbImagePrompts.isNoTextReply("无文字")).isTrue();
            assertThat(KbImagePrompts.isNoTextReply("没有文字。")).isTrue();
            assertThat(KbImagePrompts.isNoTextReply("")).isTrue();
            assertThat(KbImagePrompts.isNoTextReply(null)).isTrue();
        }

        @Test
        @DisplayName("⭐⭐ 真实文字不能被误判成空（否则 OCR 整段消失且不报错）")
        void realTextIsNotNoText() {
            assertThat(KbImagePrompts.isNoTextReply("No text content specified in section 3")).isFalse();
            assertThat(KbImagePrompts.isNoTextReply("杭州市上城区 2024 年")).isFalse();
            assertThat(KbImagePrompts.isNoTextReply("见下表第 2 行")).isFalse();
        }
    }

    @Nested
    @DisplayName("caption 无效值识别")
    class NoCaption {

        @Test
        @DisplayName("约定的 NO_CONTENT 不写进 caption")
        void markerRejected() {
            assertThat(KbImagePrompts.isNoCaption("NO_CONTENT")).isTrue();
            assertThat(KbImagePrompts.isNoCaption("no_content.")).isTrue();
            assertThat(KbImagePrompts.isNoCaption("\"NO_CONTENT\"")).isTrue();
            assertThat(KbImagePrompts.isNoCaption("")).isTrue();
            assertThat(KbImagePrompts.isNoCaption(null)).isTrue();
        }

        @Test
        @DisplayName("真实描述照常保留")
        void realCaptionKept() {
            assertThat(KbImagePrompts.isNoCaption("三类场景的投入占比饼图")).isFalse();
        }
    }

    @Nested
    @DisplayName("⭐ 图内文字进索引（否则 OCR 白跑）")
    class IndexText {

        @Test
        @DisplayName("caption + ocr 都进索引文本")
        void bothIncluded() {
            String info = "[{\"caption\":\"某场景示意图\",\"ocr_text\":\"识别到的表格文字\"}]";
            String text = KbImageEnrichService.imageTextForIndex(info);
            assertThat(text).contains("某场景示意图").contains("识别到的表格文字");
        }

        @Test
        @DisplayName("⭐⭐ 文件名式占位 caption 不进索引（image1.png 只会污染检索）")
        void fileNameCaptionSkipped() {
            String info = "[{\"caption\":\"image1.png\",\"ocr_text\":\"\"}]";
            assertThat(KbImageEnrichService.imageTextForIndex(info)).isEmpty();
            String info2 = "[{\"caption\":\"office-image-001.png\",\"ocr_text\":\"\"}]";
            assertThat(KbImageEnrichService.imageTextForIndex(info2)).isEmpty();
        }

        @Test
        @DisplayName("无图 / 坏 JSON ⇒ 空串（不能让索引失败）")
        void emptyOrBroken() {
            assertThat(KbImageEnrichService.imageTextForIndex(null)).isEmpty();
            assertThat(KbImageEnrichService.imageTextForIndex("")).isEmpty();
            assertThat(KbImageEnrichService.imageTextForIndex("not-json")).isEmpty();
            assertThat(KbImageEnrichService.imageTextForIndex("{}")).isEmpty();
        }

        @Test
        @DisplayName("文件名判据只认「无空白的图片后缀」（真实描述通常带空格）")
        void fileNameHeuristic() {
            assertThat(KbImageEnrichService.looksLikeFileName("image2.jpg")).isTrue();
            assertThat(KbImageEnrichService.looksLikeFileName("OFFICE-IMAGE-003.PNG")).isTrue();
            assertThat(KbImageEnrichService.looksLikeFileName("一张 image 截图")).isFalse();
            assertThat(KbImageEnrichService.looksLikeFileName("饼图.png 占了 30%")).isFalse();
        }
    }
}
