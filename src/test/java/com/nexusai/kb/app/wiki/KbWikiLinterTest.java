package com.nexusai.kb.app.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki 健康检查 · W2-7。
 *
 * <p><b>WHY</b>：Lint 是「用户看到的问题清单」。判错两类的代价都很高：
 * 误报 ⇒ 用户被无效告警淹没，真问题被淹没；
 * 漏报 ⇒ 坏链/过期引用长期存在，页面可信度崩掉。
 */
@DisplayName("[W2] Wiki Lint")
class KbWikiLinterTest {

    private static final String LONG_CONTENT = "这是一段足够长的正文内容，用于通过空内容检查。"
        + "它需要至少五十个字符才能算作正常页面，所以这里再补充一些文字凑够长度。";

    private static KbWikiLinter.PageSnapshot page(
        String slug, String title, String type, String content
    ) {
        return new KbWikiLinter.PageSnapshot(slug, title, type, content,
            List.of(), List.of(), List.of());
    }

    private static KbWikiLinter.PageSnapshot page(
        String slug, String title, String type, String content,
        List<String> out, List<String> in, List<String> refs
    ) {
        return new KbWikiLinter.PageSnapshot(slug, title, type, content, out, in, refs);
    }

    private static List<String> types(KbWikiLinter.Report r) {
        return r.issues().stream().map(KbWikiLinter.Issue::type).toList();
    }

    @Nested
    @DisplayName("空库")
    class Empty {

        @Test
        @DisplayName("空库 ⇒ 满分且无问题")
        void emptyIsHealthy() {
            var r = KbWikiLinter.run(List.of(), Set.of());
            assertThat(r.healthScore()).isEqualTo(100);
            assertThat(r.issues()).isEmpty();
            assertThat(r.summary()).contains("为空");
        }

        @Test
        @DisplayName("null 安全")
        void nullSafe() {
            assertThat(KbWikiLinter.run(null, null).healthScore()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("① 孤儿页")
    class Orphan {

        @Test
        @DisplayName("⭐ 无入链 ⇒ orphan warning（可修复=false）")
        void noInbound() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT)), Set.of());

            assertThat(types(r)).contains(KbWikiLinter.ORPHAN_PAGE);
            var issue = r.issues().stream()
                .filter(i -> i.type().equals(KbWikiLinter.ORPHAN_PAGE)).findFirst().orElseThrow();
            assertThat(issue.severity()).isEqualTo(KbWikiLinter.SEVERITY_WARNING);
            assertThat(issue.autoFixable())
                .as("孤儿页不能自动修 —— 「往哪链」是内容决策，不是机械操作")
                .isFalse();
        }

        @Test
        @DisplayName("⭐⭐ index 页豁免（索引页天然无入链）")
        void indexExempt() {
            var r = KbWikiLinter.run(List.of(
                page("index/root", "索引", "index", LONG_CONTENT)), Set.of());
            assertThat(types(r)).doesNotContain(KbWikiLinter.ORPHAN_PAGE);
        }

        @Test
        @DisplayName("有入链 ⇒ 不是孤儿")
        void hasInbound() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT,
                    List.of(), List.of("entity/b"), List.of())), Set.of());
            assertThat(types(r)).doesNotContain(KbWikiLinter.ORPHAN_PAGE);
        }
    }

    @Nested
    @DisplayName("② 死链")
    class BrokenLink {

        @Test
        @DisplayName("⭐⭐ 出链指向不存在页面 ⇒ error + 可自动修")
        void detectsBroken() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT,
                    List.of("entity/missing"), List.of("entity/b"), List.of())), Set.of());

            var issue = r.issues().stream()
                .filter(i -> i.type().equals(KbWikiLinter.BROKEN_LINK)).findFirst().orElseThrow();
            assertThat(issue.severity()).isEqualTo(KbWikiLinter.SEVERITY_ERROR);
            assertThat(issue.targetSlug()).isEqualTo("entity/missing");
            assertThat(issue.autoFixable())
                .as("死链可自动修（去掉链接保留文字）")
                .isTrue();
        }

        @Test
        @DisplayName("指向现存页面 ⇒ 不报")
        void linkToLive() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT,
                    List.of("entity/b"), List.of("entity/b"), List.of()),
                page("entity/b", "B", "entity", LONG_CONTENT,
                    List.of(), List.of("entity/a"), List.of())), Set.of());
            assertThat(types(r)).doesNotContain(KbWikiLinter.BROKEN_LINK);
        }

        @Test
        @DisplayName("⭐ 自链也算（自己存在 ⇒ 不报死链）")
        void selfLinkNotBroken() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT,
                    List.of("entity/a"), List.of("entity/a"), List.of())), Set.of());
            assertThat(types(r)).doesNotContain(KbWikiLinter.BROKEN_LINK);
        }
    }

    @Nested
    @DisplayName("③ 空内容")
    class EmptyContent {

        @Test
        @DisplayName("⭐ < 50 字符 ⇒ empty_content warning")
        void tooShort() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", "太短了")), Set.of());
            assertThat(types(r)).contains(KbWikiLinter.EMPTY_CONTENT);
        }

        @Test
        @DisplayName("⭐ 按码点计数（emoji 不算 2 个）")
        void countsCodePoints() {
            // 49 个 emoji = 49 码点，但 98 个 UTF-16 code unit
            String emoji = "😀".repeat(49);
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", emoji)), Set.of());
            assertThat(types(r))
                .as("⭐ 用 length() 会把 49 码点算成 98 ⇒ 漏报")
                .contains(KbWikiLinter.EMPTY_CONTENT);
        }

        @Test
        @DisplayName("够长 ⇒ 不报")
        void longEnough() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT)), Set.of());
            assertThat(types(r)).doesNotContain(KbWikiLinter.EMPTY_CONTENT);
        }
    }

    @Nested
    @DisplayName("④ 过期来源")
    class StaleRef {

        @Test
        @DisplayName("⭐⭐ 引用已删文档 ⇒ error")
        void detectsStale() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT,
                    List.of(), List.of("x"), List.of("doc-alive", "doc-deleted"))),
                Set.of("doc-alive"));

            var issues = r.issues().stream()
                .filter(i -> i.type().equals(KbWikiLinter.STALE_REF)).toList();
            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).targetSlug()).isEqualTo("doc-deleted");
            assertThat(issues.get(0).severity()).isEqualTo(KbWikiLinter.SEVERITY_ERROR);
        }

        @Test
        @DisplayName("⭐ liveKnowledgeIds=null ⇒ 跳过该检查（无法判断）")
        void nullSkipsCheck() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT,
                    List.of(), List.of("x"), List.of("doc-1"))), null);
            assertThat(types(r)).doesNotContain(KbWikiLinter.STALE_REF);
        }
    }

    @Nested
    @DisplayName("⑤ 缺链")
    class MissingCrossRef {

        @Test
        @DisplayName("⭐ 正文提到实体名但没链 ⇒ info（不可自动修）")
        void detectsMissing() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT + " 提到了 Acme 公司。"),
                page("entity/acme", "Acme", "entity", LONG_CONTENT)), Set.of());

            var issues = r.issues().stream()
                .filter(i -> i.type().equals(KbWikiLinter.MISSING_CROSS_REF)).toList();
            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).targetSlug()).isEqualTo("entity/acme");
            assertThat(issues.get(0).severity()).isEqualTo(KbWikiLinter.SEVERITY_INFO);
            assertThat(issues.get(0).autoFixable())
                .as("「该不该链」是内容决策 ⇒ 不自动改")
                .isFalse();
        }

        @Test
        @DisplayName("⭐ 已链 ⇒ 不报")
        void alreadyLinked() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT + " 提到了 Acme 公司。",
                    List.of("entity/acme"), List.of(), List.of()),
                page("entity/acme", "Acme", "entity", LONG_CONTENT)), Set.of());
            assertThat(types(r)).doesNotContain(KbWikiLinter.MISSING_CROSS_REF);
        }

        @Test
        @DisplayName("⭐ 大小写不敏感")
        void caseInsensitive() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT + " mentions acme corp."),
                page("entity/acme", "ACME Corp.", "entity", LONG_CONTENT)), Set.of());
            assertThat(types(r)).contains(KbWikiLinter.MISSING_CROSS_REF);
        }

        @Test
        @DisplayName("⭐ 检查自己不对自己报")
        void skipsSelf() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT + " 本页讲 A。")), Set.of());
            assertThat(types(r)).doesNotContain(KbWikiLinter.MISSING_CROSS_REF);
        }
    }

    @Nested
    @DisplayName("⑥ 重复 slug")
    class DuplicateSlug {

        @Test
        @DisplayName("⭐ 同 slug 出现两次 ⇒ error")
        void detectsDuplicate() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A1", "entity", LONG_CONTENT),
                page("entity/a", "A2", "entity", LONG_CONTENT)), Set.of());

            var issues = r.issues().stream()
                .filter(i -> i.type().equals(KbWikiLinter.DUPLICATE_SLUG)).toList();
            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).description()).contains("2 次");
        }
    }

    @Nested
    @DisplayName("健康分与汇总")
    class HealthScore {

        @Test
        @DisplayName("⭐ 每条死链扣 5 分（绝对值，不是比例）")
        void brokenPenalty() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", LONG_CONTENT,
                    List.of("entity/x", "entity/y"), List.of("z"), List.of())), Set.of());
            // 100 - 2*5 = 90（孤儿页 0%：有入链）
            assertThat(r.healthScore()).isEqualTo(90);
        }

        @Test
        @DisplayName("⭐ 每条空页扣 3 分")
        void emptyPenalty() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", "短", List.of(), List.of("x"), List.of())), Set.of());
            // 100 - 3（空内容）= 97；有入链所以不扣孤儿
            assertThat(r.healthScore()).isEqualTo(97);
        }

        @Test
        @DisplayName("⭐ 分数不低于 0")
        void floorAtZero() {
            List<KbWikiLinter.PageSnapshot> many = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) {
                many.add(page("entity/" + i, "T" + i, "entity", "短",
                    List.of("entity/missing"), List.of(), List.of()));
            }
            assertThat(KbWikiLinter.run(many, Set.of()).healthScore()).isEqualTo(0);
        }

        @Test
        @DisplayName("⭐ 分级计数与总结")
        void countsBySeverity() {
            var r = KbWikiLinter.run(List.of(
                page("entity/a", "A", "entity", "短", List.of("entity/missing"), List.of(), List.of())),
                Set.of());
            assertThat(r.errorCount()).isEqualTo(1);     // 死链
            assertThat(r.warningCount())
                .as("这条数据同时触发两个 warning：空内容 + 无入链（孤儿）。"
                    + "⚠️ 写测试数据时必须把「同一条数据会命中几条检查」数清楚 —— "
                    + "我这里首版只算了空内容，漏了孤儿页")
                .isEqualTo(2);
            assertThat(r.summary()).contains("个问题");
        }
    }
}
