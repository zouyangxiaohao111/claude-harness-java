package com.nexusai.kb.app.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki 交叉链接注入 · W2-7。
 *
 * <p><b>WHY</b>：这条链路错的形态全都<b>肉眼可见但不会报错</b> ——
 * 代码块里被插了链接、遇到 {@code STORAGE} 就把 {@code RAG} 链上、
 * 一个词链 8 次。单测守的就是这五条规则。
 */
@DisplayName("[W2] Wiki 交叉链接")
class KbWikiLinkifierTest {

    private static List<KbWikiLinkifier.LinkRef> refs(String... slugAndName) {
        java.util.List<KbWikiLinkifier.LinkRef> out = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < slugAndName.length; i += 2) {
            out.add(new KbWikiLinkifier.LinkRef(slugAndName[i], slugAndName[i + 1]));
        }
        return out;
    }

    @Nested
    @DisplayName("⭐⭐ 禁用区间（插进去就破坏原语法）")
    class ForbiddenSpans {

        @Test
        @DisplayName("⭐⭐ 围栏代码块内不注入")
        void noFencedCode() {
            String md = "说明：\n```java\nint x = RAG;\n```\n正文提到 RAG 一次。";
            var r = KbWikiLinkifier.linkify(md,
                refs("concept/rag", "RAG"), "entity/x");

            assertThat(r.content()).contains("int x = RAG;")
                .as("⭐⭐ 代码块里插 [[...]] 会破坏代码，用户复制就走样")
                .contains("[[concept/rag|RAG]]");
            assertThat(r.content().indexOf("int x = RAG;")).isGreaterThan(-1);
        }

        @Test
        @DisplayName("⭐⭐ 行内代码内不注入")
        void noInlineCode() {
            String md = "字段 `RAG` 是简称，正文里的 RAG 是概念。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/rag", "RAG"), null);
            assertThat(r.content()).contains("`RAG`");
            assertThat(r.content()).contains("[[concept/rag|RAG]]");
        }

        @Test
        @DisplayName("⭐⭐ 已有 [[...]] 内不重复注入")
        void noNestedWikiLink() {
            String md = "见 [[entity/acme|Acme]] 与 Acme 的另一处。";
            var r = KbWikiLinkifier.linkify(md, refs("entity/acme", "Acme"), null);
            // 已有链接 → slug 已 used ⇒ 不再注入第二处
            assertThat(r.content()).isEqualTo(md);
            assertThat(r.changed()).as("已有链接时不应改动").isFalse();
        }

        @Test
        @DisplayName("⭐ Markdown 链接 / 图片内不注入")
        void noMarkdownLinksOrImages() {
            String md = "看 [RAG 文档](http://x/rag) 和 ![RAG 图](http://x/r.png)。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/rag", "RAG"), null);
            assertThat(r.content()).isEqualTo(md)
                .as("URL 文本里插链接会破坏 markdown 结构");
        }

        @Test
        @DisplayName("⭐ 自动链接 <...> 内不注入")
        void noAutolink() {
            String md = "见 <https://example.com/RAG> 页面。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/rag", "RAG"), null);
            assertThat(r.content()).isEqualTo(md);
        }

        @Test
        @DisplayName("⭐ 未闭合围栏 ⇒ 余下全文都算禁用（保守）")
        void unclosedFenceSwallowsRest() {
            String md = "```\nRAG 在代码里\n没闭合";
            var r = KbWikiLinkifier.linkify(md, refs("concept/rag", "RAG"), null);
            assertThat(r.content()).isEqualTo(md);
        }
    }

    @Nested
    @DisplayName("⭐ 长名优先")
    class LongestFirst {

        @Test
        @DisplayName("⭐「污水处理设施」必须先于「污水处理」命中")
        void longerNameWins() {
            String md = "农村污水处理设施的建设要求。";
            var r = KbWikiLinkifier.linkify(md, refs(
                "concept/污水处理", "污水处理",
                "entity/污水处理设施", "污水处理设施"), null);

            assertThat(r.content())
                .as("⭐⭐ 若按输入顺序处理：「污水处理」先吃掉前缀 ⇒ 长名永远匹配不上")
                .contains("[[entity/污水处理设施|污水处理设施]]");
            assertThat(r.content()).doesNotContain("[[concept/污水处理|污水处理]]");
        }
    }

    @Nested
    @DisplayName("⭐ 只链一次")
    class OnceOnly {

        @Test
        @DisplayName("⭐ 同页面正文里出现 3 次 ⇒ 只链第 1 次")
        void linksOnlyFirstOccurrence() {
            String md = "RAG 很重要。RAG 还能用。再说 RAG 一次。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/rag", "RAG"), null);

            assertThat(r.content().split("\\[\\[concept/rag\\|RAG]]", -1).length - 1)
                .as("⭐ 全链会让正文变成蓝色海洋，且渲染成本暴涨")
                .isEqualTo(1);
            assertThat(r.content()).startsWith("[[concept/rag|RAG]] 很重要。");
        }
    }

    @Nested
    @DisplayName("⭐⭐ 中文单字跳过")
    class SingleHanChar {

        @Test
        @DisplayName("⭐⭐ 单字页「水」不注入（会把「水平」「风沙」也链上）")
        void skipsSingleHanChar() {
            String md = "水资源的水平衡很重要。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/水", "水"), null);

            assertThat(r.content())
                .as("⭐⭐ 中文无词边界 ⇒ 单字子串匹配必然误伤。源侧明确跳过这一档")
                .isEqualTo(md);
            assertThat(r.changed()).isFalse();
        }

        @Test
        @DisplayName("两字中文可以注入")
        void allowsTwoHanChars() {
            String md = "污水治理要求。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/污水", "污水"), null);
            assertThat(r.content()).contains("[[concept/污水|污水]]");
        }
    }

    @Nested
    @DisplayName("⭐⭐ ASCII 词边界")
    class AsciiWordBoundary {

        @Test
        @DisplayName("⭐⭐ STORAGE 里的 RAG 不能链（无词边界）")
        void noMatchInsideWord() {
            String md = "STORAGE 是一个词。RAG 是另一个。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/rag", "RAG"), null);

            assertThat(r.content())
                .as("⭐⭐ 只做 indexOf 会把 STORAGE 里的 RAG 链上 —— 用户点开看到完全无关的页面")
                .contains("STORAGE 是一个词。[[concept/rag|RAG]] 是另一个。");
        }

        @Test
        @DisplayName("纯中文不需要词边界")
        void cjkNoBoundaryNeeded() {
            String md = "这是污水处理。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/污水", "污水"), null);
            assertThat(r.content()).contains("[[concept/污水|污水]]处理");
        }
    }

    @Nested
    @DisplayName("⭐ 自链接不注入")
    class SelfLink {

        @Test
        @DisplayName("⭐ 页面不链自己")
        void noSelfLink() {
            String md = "RAG 的说明。";
            var r = KbWikiLinkifier.linkify(md, refs("concept/rag", "RAG"), "concept/rag");
            assertThat(r.content()).isEqualTo(md);
            assertThat(r.changed()).isFalse();
        }
    }

    @Nested
    @DisplayName("工具函数")
    class Helpers {

        @Test
        @DisplayName("extractWikiSlug：[[a|b]] → a，[[a]] → a")
        void extractSlug() {
            assertThat(KbWikiLinkifier.extractWikiSlug("entity/a|显示名")).isEqualTo("entity/a");
            assertThat(KbWikiLinkifier.extractWikiSlug("entity/a")).isEqualTo("entity/a");
            assertThat(KbWikiLinkifier.extractWikiSlug("   ")).isEmpty();
            assertThat(KbWikiLinkifier.extractWikiSlug("")).isEmpty();
            assertThat(KbWikiLinkifier.extractWikiSlug(null)).isEmpty();
        }

        @Test
        @DisplayName("isSingleHanChar 只对单个汉字为真")
        void singleHanChar() {
            assertThat(KbWikiLinkifier.isSingleHanChar("水")).isTrue();
            assertThat(KbWikiLinkifier.isSingleHanChar("污水")).isFalse();
            assertThat(KbWikiLinkifier.isSingleHanChar("a")).as("ASCII 单字符不是汉字").isFalse();
            assertThat(KbWikiLinkifier.isSingleHanChar("😀")).isFalse();
            assertThat(KbWikiLinkifier.isSingleHanChar("")).isFalse();
            assertThat(KbWikiLinkifier.isSingleHanChar(null)).isFalse();
        }

        @Test
        @DisplayName("hasWordBoundary 检查两侧")
        void wordBoundary() {
            String s = "STORAGE";
            // "RAG" 在 s 里位于 3..6，左侧是 O（单词字符）⇒ 无边界
            assertThat(KbWikiLinkifier.hasWordBoundary(s, 3, 6)).isFalse();
            // 独立的 RAG
            String s2 = "a RAG b";
            assertThat(KbWikiLinkifier.hasWordBoundary(s2, 2, 5)).isTrue();
        }

        @Test
        @DisplayName("空输入安全")
        void emptyInputs() {
            assertThat(KbWikiLinkifier.linkify(null, refs("a", "b"), null).content()).isEmpty();
            assertThat(KbWikiLinkifier.linkify("", refs("a", "b"), null).content()).isEmpty();
            assertThat(KbWikiLinkifier.linkify("abc", null, null).content()).isEqualTo("abc");
            assertThat(KbWikiLinkifier.linkify("abc", List.of(), null).content()).isEqualTo("abc");
        }
    }
}
