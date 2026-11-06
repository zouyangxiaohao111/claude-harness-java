package com.nexusai.kb.app.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbChunkText} 测试 · 批次 W6。
 *
 * <p>这个类存在的唯一理由：扫描件解析出来的块只有 {@code ![image](url)}，
 * 它的 {@code isBlank()} 是 false ⇒ 守卫失效 ⇒ 模型对着一串 URL 编造答案。
 */
class KbChunkTextTest {

    @Nested
    @DisplayName("图片语法")
    class Images {

        @Test
        @DisplayName("⭐⭐ 只有图片引用的块 ⇒ 判为无文字（URL 不算内容）")
        void imageOnlyIsEmpty() {
            String c = "![image](http://127.0.0.1:8080/files/kb/ab/cd/p1.png)";
            assertThat(KbChunkText.hasExtractableText(c)).isFalse();
            assertThat(KbChunkText.visibleLength(c)).isZero();
        }

        @Test
        @DisplayName("多张图片拼接仍判无文字")
        void manyImages() {
            String c = "![图1](http://x/a.png)\n![图2](http://x/b.png)\n![图3](http://x/c.png)";
            assertThat(KbChunkText.hasExtractableText(c)).isFalse();
        }

        @Test
        @DisplayName("图片 + 少量真文字 ⇒ 按真文字算长度")
        void imagePlusText() {
            String c = "![image](http://x/a.png)\n第一章 总则";
            assertThat(KbChunkText.visibleLength(c)).isEqualTo(5);   // 「第一章 总则」= 5 个非空白
        }
    }

    @Nested
    @DisplayName("裸链接")
    class BareUrls {

        @Test
        @DisplayName("只有裸链接 ⇒ 无文字")
        void bareUrlOnly() {
            assertThat(KbChunkText.hasExtractableText("https://example.com/a/very/long/path")).isFalse();
        }

        @Test
        @DisplayName("链接文字保留（它往往就是正文）")
        void linkTextKept() {
            String c = "详见 [农村污水治理导则](http://x/y)";
            // 「详见 [农村污水治理导则]」= 2 + 1 + 10 = 13（方括号保留，URL 被剔除）
            assertThat(KbChunkText.visibleLength(c)).isEqualTo(13);
        }
    }

    @Nested
    @DisplayName("阈值")
    class Threshold {

        @Test
        @DisplayName("空 / null ⇒ 无文字")
        void empty() {
            assertThat(KbChunkText.hasExtractableText(null)).isFalse();
            assertThat(KbChunkText.hasExtractableText("")).isFalse();
            assertThat(KbChunkText.hasExtractableText("   \n\t ")).isFalse();
        }

        @Test
        @DisplayName("⭐⭐ 9 个字不算（少于阈值 10）")
        void belowThreshold() {
            assertThat(KbChunkText.visibleLength("一二三四五六七八九")).isEqualTo(9);
            assertThat(KbChunkText.hasExtractableText("一二三四五六七八九")).isFalse();
        }

        @Test
        @DisplayName("10 个字算")
        void atThreshold() {
            assertThat(KbChunkText.hasExtractableText("一二三四五六七八九十")).isTrue();
        }

        @Test
        @DisplayName("空白不计入长度")
        void whitespaceIgnored() {
            assertThat(KbChunkText.visibleLength("一 二\n三\t四")).isEqualTo(4);
        }

        @Test
        @DisplayName("emoji 按 code point 计（不按 char 计）")
        void emojiCountedAsOne() {
            // 🌊 = 1 个 code point / 2 个 char
            assertThat(KbChunkText.visibleLength("🌊🌊🌊🌊🌊")).isEqualTo(5);
        }
    }
}
