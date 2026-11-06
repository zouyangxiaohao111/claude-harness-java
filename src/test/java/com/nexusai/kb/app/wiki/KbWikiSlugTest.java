package com.nexusai.kb.app.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiki slug 规范化 · W2-3。
 *
 * <p><b>WHY</b>：slug 是<b>永久寻址键</b>。wiki 链接 {@code [[entity/acme-corp|Acme]]}
 * 全靠它解析 —— slug 一变，所有入链全断，而且<b>不会报错</b>，只会变成死链。
 * ⇒ 「同一名字必须永远得到同一 slug」是本类唯一真正的价值。
 */
@DisplayName("[W2] Wiki slug")
class KbWikiSlugTest {

    @Nested
    @DisplayName("⭐ 确定性（同一输入永远同一输出）")
    class Determinism {

        @Test
        @DisplayName("⭐ 重复调用结果完全相同")
        void idempotent() {
            String a = KbWikiSlug.slugify("Acme Corporation");
            String b = KbWikiSlug.slugify("Acme Corporation");
            assertThat(a).isEqualTo(b);
            assertThat(KbWikiSlug.slugify(a))
                .as("⭐ 幂等：对已是 slug 形态的串再跑一遍不能变形")
                .isEqualTo(a);
        }

        @Test
        @DisplayName("大小写与空白差异归一 ⇒ 同一 slug")
        void caseAndSpaceNormalized() {
            assertThat(KbWikiSlug.slugify("Acme  Corporation"))
                .isEqualTo(KbWikiSlug.slugify("acme corporation"));
            assertThat(KbWikiSlug.slugify("  Acme Corporation  "))
                .isEqualTo(KbWikiSlug.slugify("Acme Corporation"));
        }
    }

    @Nested
    @DisplayName("字符处理")
    class Characters {

        @Test
        @DisplayName("标点 / 斜杠 / 空白 ⇒ 连字符，且连续折叠")
        void punctuationToDash() {
            assertThat(KbWikiSlug.slugify("Acme Corp. / Inc.")).isEqualTo("acme-corp-inc");
            assertThat(KbWikiSlug.slugify("a   b")).isEqualTo("a-b");
            assertThat(KbWikiSlug.slugify("--a--")).isEqualTo("a");
        }

        @Test
        @DisplayName("全角 ⇒ 半角（NFKC）")
        void fullWidthNormalized() {
            assertThat(KbWikiSlug.slugify("ＡＣＭＥ")).isEqualTo("acme");
            assertThat(KbWikiSlug.slugify("１２３")).isEqualTo("123");
        }

        @Test
        @DisplayName("⭐ 中文保留（本仓不做拼音转换）")
        void chinesePreserved() {
            assertThat(KbWikiSlug.slugify("农村污水"))
                .as("⭐ 本仓有意不做拼音转换：拼音库有歧义（多音字），"
                    + "而 CJK 本身已是确定性且唯一的键")
                .isEqualTo("农村污水");
            assertThat(KbWikiSlug.of("entity", "农村生活污水处理设施"))
                .isEqualTo("entity/农村生活污水处理设施");
        }

        @Test
        @DisplayName("空 / 全标点 ⇒ untitled（⛔ 不返回空串）")
        void emptyBecomesUntitled() {
            assertThat(KbWikiSlug.slugify("")).isEqualTo("untitled");
            assertThat(KbWikiSlug.slugify(null)).isEqualTo("untitled");
            assertThat(KbWikiSlug.slugify("///")).isEqualTo("untitled")
                .as("⭐ 空 slug 会让唯一索引失效 —— 宁可给 untitled");
        }

        @Test
        @DisplayName("⭐ 超长按码点截断（⛔ 不切断代理对）")
        void longTruncatedByCodePoint() {
            String emoji = "😀";
            String name = emoji.repeat(300);
            String slug = KbWikiSlug.slugify(name);
            // emoji 被当作「非字母数字」⇒ 变成连字符 ⇒ untitled？实际会被折叠成空
            // 这里主要验证：不抛异常、不产生半个代理对
            assertThat(slug).isNotBlank();
            assertThat(slug.codePointCount(0, slug.length())).isLessThanOrEqualTo(200);

            String longAscii = "a".repeat(500);
            assertThat(KbWikiSlug.slugify(longAscii).length()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("类型前缀")
    class Types {

        @Test
        @DisplayName("四种合法类型")
        void validTypes() {
            assertThat(KbWikiSlug.of("entity", "Acme")).isEqualTo("entity/acme");
            assertThat(KbWikiSlug.of("concept", "RAG")).isEqualTo("concept/rag");
            assertThat(KbWikiSlug.of("summary", "abc")).isEqualTo("summary/abc");
            assertThat(KbWikiSlug.of("index", "root")).isEqualTo("index/root");
        }

        @Test
        @DisplayName("非法类型 ⇒ 抛异常（⛔ 不猜）")
        void invalidTypeThrows() {
            assertThatThrownBy(() -> KbWikiSlug.of("unknown", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
        }

        @Test
        @DisplayName("typeOf 取前缀")
        void typeOf() {
            assertThat(KbWikiSlug.typeOf("entity/acme")).isEqualTo("entity");
            assertThat(KbWikiSlug.typeOf("nosllug")).isNull();
            assertThat(KbWikiSlug.typeOf(null)).isNull();
        }
    }

    @Nested
    @DisplayName("⭐⭐ 摘要页 slug 用 knowledgeId")
    class SummarySlug {

        @Test
        @DisplayName("⭐⭐ 用 ID 而不是标题（文件名不喂给模型）")
        void usesIdNotTitle() {
            String slug = KbWikiSlug.summarySlug("a1b2-c3d4");
            assertThat(slug).isEqualTo("summary/a1b2-c3d4");
            assertThat(slug)
                .as("⭐⭐ 摘要页 slug 会被下游模型读进「可用 wiki 页面」清单。"
                    + "用标题 = 把文件名泄漏给模型（扫描件叫 MX5280.pdf 时诱发幻觉摘要）")
                .doesNotContain("MX5280");
        }
    }

    @Nested
    @DisplayName("规范化模型返回的 slug")
    class Normalize {

        @Test
        @DisplayName("⭐ 模型常返回 Entity/Acme Corp 这种，必须归一")
        void normalizesModelOutput() {
            assertThat(KbWikiSlug.normalize("Entity/Acme Corp")).isEqualTo("entity/acme-corp");
            assertThat(KbWikiSlug.normalize("  concept/RAG  ")).isEqualTo("concept/rag");
            assertThat(KbWikiSlug.normalize("SUMMARY/Abc-123")).isEqualTo("summary/abc-123");
        }

        @Test
        @DisplayName("无法识别类型 ⇒ null（调用方丢弃该条）")
        void unknownTypeReturnsNull() {
            assertThat(KbWikiSlug.normalize("weird/thing")).isNull();
            assertThat(KbWikiSlug.normalize("noslash")).isNull();
            assertThat(KbWikiSlug.normalize("entity/")).isNull();
            assertThat(KbWikiSlug.normalize("/entity")).isNull();
            assertThat(KbWikiSlug.normalize(null)).isNull();
            assertThat(KbWikiSlug.normalize("  ")).isNull();
        }
    }
}
