package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询扩展变体生成 · 对齐源侧 {@code query_expansion.go:expandQueries}。
 *
 * <p><b>WHY 这些断言（规则九）</b>：扩展的唯一目的是「在主检索召回不足时补长尾」。
 * 它有两个必须守住的边界，任一破了都是净损失：
 * <ol>
 *   <li><b>不能生成与原查询相同的变体</b> —— 那只是把同一次检索再跑一遍，纯浪费；</li>
 *   <li><b>不能无限生成</b> —— 每个变体 × 每个库都是一次检索，5 个变体 × 10 个库 = 50 次。</li>
 * </ol>
 * ⇒ 断言既验「能生成有意义的变体」，也验「不会重复 / 不会超量」。
 */
@DisplayName("[QA 对齐] 查询扩展变体")
class KbQueryExpansionTest {

    @Test
    @DisplayName("去疑问词 + 去停用词都能产出有意义的变体")
    void producesUsefulVariants() {
        List<String> v = KbQueryExpansion.expand(
            List.of("如何配置农村污水处理设施的巡检周期？"));
        assertThat(v).isNotEmpty();
        // 去疑问词前缀
        assertThat(v).anyMatch(s -> !s.startsWith("如何"));
        // 变体都不等于原句
        assertThat(v).doesNotContain("如何配置农村污水处理设施的巡检周期？");
    }

    @Test
    @DisplayName("原查询本身不会被当成变体（否则等于重复检索）")
    void excludesOriginalQueries() {
        String q = "污水处理设施覆盖率要求";
        List<String> v = KbQueryExpansion.expand(List.of(q));
        assertThat(v).doesNotContain(q);
    }

    @Test
    @DisplayName("去重：大小写不同的同一串只出现一次")
    void dedupesCaseInsensitively() {
        List<String> v = KbQueryExpansion.expand(List.of("SLA 要求是什么"));
        long slaCount = v.stream().filter(s -> s.equalsIgnoreCase("sla")).count();
        assertThat(slaCount).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("变体数量上限 5（防止检索次数爆炸）")
    void capsVariantCount() {
        String q = "一,二二二,三三三三,四四四四四,五五五五五五,六六六六六六六,七七七七七七七七,"
            + "八八八八八八八八八,九九九九九九九九九九,十十十十十十十十十十";
        List<String> v = KbQueryExpansion.expand(List.of(q));
        assertThat(v).hasSizeLessThanOrEqualTo(KbQueryExpansion.MAX_VARIANTS);
    }

    @Test
    @DisplayName("引号里的短语会被单独抽出来（引号集合与源侧一致）")
    void extractsQuotedPhrases() {
        List<String> v = KbQueryExpansion.expand(
            List.of("请问「农村生活污水处理技术规范」里关于巡检的要求"));
        assertThat(v).contains("农村生活污水处理技术规范");
    }

    @Test
    @DisplayName("《》不抽（⭐ 源侧正则里没有 《》 —— 此处保持忠实，已登记为已知差距）")
    void bookTitleMarksAreNotExtracted() {
        List<String> v = KbQueryExpansion.expand(
            List.of("请问《农村生活污水处理技术规范》里关于巡检的要求"));
        assertThat(v).doesNotContain("农村生活污水处理技术规范");
    }

    @Test
    @DisplayName("空输入 / 空白输入 → 空列表（不抛、不造无意义变体）")
    void handlesEmptyInput() {
        assertThat(KbQueryExpansion.expand(null)).isEmpty();
        assertThat(KbQueryExpansion.expand(List.of())).isEmpty();
        assertThat(KbQueryExpansion.expand(List.of("  "))).isEmpty();
    }

    @Test
    @DisplayName("太短的串不进变体（< 3 码点，检索无意义）")
    void dropsTooShortVariants() {
        List<String> v = KbQueryExpansion.expand(List.of("a, b, c, 的"));
        assertThat(v).allMatch(s -> s.codePointCount(0, s.length()) >= 3);
    }

    @Test
    @DisplayName("关键词提取：中文连续段按 bigram 切且过滤停用词")
    void keywordsUseBigramAndDropStopwords() {
        List<String> kw = KbQueryExpansion.extractKeywords("的巡检周期和覆盖率");
        assertThat(kw).isNotEmpty();
        assertThat(kw).doesNotContain("的", "和");
        // bigram 至少能切出「巡检」「周期」这类片段
        assertThat(kw).anyMatch(s -> s.contains("巡检"));
    }
}
