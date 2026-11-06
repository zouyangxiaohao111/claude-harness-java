package com.nexusai.kb.app.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki Finalize 阶段 · W2-6。
 *
 * <p><b>WHY</b>：这一层有两个顺序性缺陷，做错了<b>不报错</b>只会让结果差一点：
 * ① 补链与清死链的顺序反了 ⇒ 补出新的死链；
 * ② 有 ingest 在跑时剪枝 ⇒ 剪掉正在被写入的目录。
 */
@DisplayName("[W2] Wiki Finalize")
class KbWikiFinalizeStageTest {

    private static KbWikiFinalizeStage.PageView page(
        String slug, String title, String type, String content) {
        return new KbWikiFinalizeStage.PageView(slug, title, type, content, "摘要");
    }

    @Nested
    @DisplayName("① auto_generate 守卫")
    class AutoGenerateGuard {

        @Test
        @DisplayName("⭐⭐ 关闭 ⇒ 完全跳过（⛔ 不做任何变更）")
        void disabledSkipsEverything() {
            KbWikiConfig cfg = KbWikiConfig.parse("{\"auto_generate\": false}");
            var r = new KbWikiFinalizeStage(null).finalize(
                List.of(page("entity/a", "A", "entity", "正文 [[entity/missing|x]]")),
                cfg, List.of(), false, "m");

            assertThat(r.skipped()).isTrue();
            assertThat(r.pagesToUpdate())
                .as("⭐⭐ 关掉自动生成后还去清死链/补链 = 用户关不掉这个功能")
                .isEmpty();
            assertThat(r.deadLinksRemoved()).isZero();
        }

        @Test
        @DisplayName("⭐ 未设置（null 字段）⇒ 照常执行")
        void defaultEnabled() {
            KbWikiConfig cfg = KbWikiConfig.parse("{}");
            var r = new KbWikiFinalizeStage(null).finalize(
                List.of(page("entity/a", "A", "entity", "正文")), cfg, List.of(), false, null);
            assertThat(r.skipped())
                .as("不跳过（虽然没模型，清死链仍应跑）")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("③ 清死链")
    class DeadLinks {

        @Test
        @DisplayName("⭐⭐ 死链还原成显示名（⛔ 不删文字）")
        void replacesWithDisplayName() {
            var r = KbWikiFinalizeStage.cleanDeadLinks(
                "见 [[entity/missing|这个页面]] 与正常 [[entity/live|活着]]。",
                Set.of("entity/live"));

            assertThat(r.removed()).isEqualTo(1);
            assertThat(r.content())
                .as("⭐⭐ 死链的文字内容通常是对的，只是目标没了 —— 删文字会丢事实")
                .contains("见 这个页面 与")
                .contains("[[entity/live|活着]]")
                .doesNotContain("[[entity/missing");
        }

        @Test
        @DisplayName("⭐ 没有显示名时用 slug 末段")
        void noPipeUsesLastSegment() {
            var r = KbWikiFinalizeStage.cleanDeadLinks(
                "见 [[entity/missing-page]] 结束。", Set.of());
            assertThat(r.content()).contains("见 missing-page 结束。");
        }

        @Test
        @DisplayName("全部存活 ⇒ 不改动")
        void allLive() {
            String content = "[[entity/a|x]] 和 [[entity/b|y]]";
            var r = KbWikiFinalizeStage.cleanDeadLinks(content, Set.of("entity/a", "entity/b"));
            assertThat(r.content()).isEqualTo(content);
            assertThat(r.removed()).isZero();
        }

        @Test
        @DisplayName("多条死链都清")
        void multipleDead() {
            var r = KbWikiFinalizeStage.cleanDeadLinks(
                "[[a/x|甲]] [[a/y|乙]] [[a/z|丙]]", Set.of("a/x"));
            assertThat(r.removed()).isEqualTo(2);
            assertThat(r.content()).contains("[[a/x|甲]]").contains("乙").contains("丙");
        }
    }

    @Nested
    @DisplayName("⭐⭐ 顺序：先清死链再补链")
    class Ordering {

        @Test
        @DisplayName("⭐⭐ 被删页面的名字不该在补链阶段被重新链上")
        void noRelinkAfterClean() {
            // 页 A 的正文提到「Ghost」，但 Ghost 页已经不存在了
            var pages = List.of(
                page("entity/a", "A", "entity", "这里提到 Ghost 这个名字。"),
                page("index/root", "索引", "index", "索引"));

            var r = new KbWikiFinalizeStage(null).finalize(
                pages, KbWikiConfig.parse("{}"), List.of(), false, null);

            assertThat(r.pagesToUpdate().getOrDefault("entity/a", ""))
                .as("⭐⭐ Ghost 不在存活集里 ⇒ 不该被链。"
                    + "若先补后清，这里会先链上再被清掉，白做一轮")
                .doesNotContain("[[entity/ghost");
        }

        @Test
        @DisplayName("⭐ 存活实体被补链")
        void linksLiveEntity() {
            var pages = List.of(
                page("entity/a", "A", "entity", "这里提到 Acme 公司。"),
                page("entity/acme", "Acme", "entity", "Acme 的介绍正文"));

            var r = new KbWikiFinalizeStage(null).finalize(
                pages, KbWikiConfig.parse("{}"), List.of(), false, null);

            assertThat(r.pagesToUpdate().getOrDefault("entity/a", ""))
                .contains("[[entity/acme|Acme]]");
            assertThat(r.crossLinksAdded()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("⑤ 剪枝延后")
    class PruneDeferral {

        @Test
        @DisplayName("⭐⭐ 有 ingest 在跑 ⇒ 剪枝延后")
        void deferredWhenIngestRunning() {
            var r = new KbWikiFinalizeStage(null).finalize(
                List.of(page("entity/a", "A", "entity", "足够长的正文内容用于通过检查这一关")),
                KbWikiConfig.parse("{}"), List.of(), true, null);

            assertThat(r.pruneDeferred())
                .as("⭐⭐ 边剪边写会剪掉正在被写入的目录，导致页面丢失归属")
                .isTrue();
        }

        @Test
        @DisplayName("无 ingest ⇒ 不延后")
        void notDeferred() {
            var r = new KbWikiFinalizeStage(null).finalize(
                List.of(page("entity/a", "A", "entity", "足够长的正文内容用于通过检查这一关")),
                KbWikiConfig.parse("{}"), List.of(), false, null);
            assertThat(r.pruneDeferred()).isFalse();
        }

        @Test
        @DisplayName("⭐ 空目录可剪；有页面的目录不可剪")
        void prunableFolders() {
            var prunable = KbWikiFinalizeStage.prunableFolders(
                List.of("地点", "地点/地址", "人物"),
                Set.of("地点/地址"));

            assertThat(prunable).containsExactly("人物")
                .as("「地点」有被保留的子目录 ⇒ 不能剪；「地点/地址」在用 ⇒ 不能剪");
        }

        @Test
        @DisplayName("⭐ 父子都空 ⇒ 都剪掉（深→浅）")
        void prunesDeepFirst() {
            var prunable = KbWikiFinalizeStage.prunableFolders(
                List.of("空A", "空A/空B"), Set.of());
            assertThat(prunable).containsExactly("空A/空B", "空A");
        }

        @Test
        @DisplayName("无目录 ⇒ 空列表")
        void emptyFolders() {
            assertThat(KbWikiFinalizeStage.prunableFolders(List.of(), Set.of())).isEmpty();
            assertThat(KbWikiFinalizeStage.prunableFolders(null, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("工具")
    class Helpers {

        @Test
        @DisplayName("⭐ 只把 entity/concept 当可链接目标")
        void onlyEntityConcept() {
            var refs = KbWikiFinalizeStage.buildLinkRefs(List.of(
                page("entity/a", "A", "entity", ""),
                page("concept/b", "B", "concept", ""),
                page("summary/c", "C", "summary", ""),
                page("index/root", "索引", "index", "")));

            assertThat(refs).hasSize(2);
            assertThat(refs.stream().map(KbWikiLinkifier.LinkRef::slug))
                .as("⭐ summary 页是文档级概览，被链进实体语境会让概念混淆")
                .containsExactly("entity/a", "concept/b");
        }

        @Test
        @DisplayName("countWikiLinks 计数")
        void countsLinks() {
            assertThat(KbWikiFinalizeStage.countWikiLinks("[[a|x]] 和 [[b|y]]")).isEqualTo(2);
            assertThat(KbWikiFinalizeStage.countWikiLinks("无链接")).isZero();
            assertThat(KbWikiFinalizeStage.countWikiLinks(null)).isZero();
        }

        @Test
        @DisplayName("空页面列表 ⇒ 跳过")
        void emptySkips() {
            var r = new KbWikiFinalizeStage(null).finalize(
                List.of(), KbWikiConfig.parse("{}"), List.of(), false, null);
            assertThat(r.skipped()).isTrue();
        }

        @Test
        @DisplayName("⭐ 索引页不参与清死链/补链（由系统重建）")
        void indexPageUntouched() {
            var pages = List.of(
                page("entity/acme", "Acme", "entity", "Acme 的完整介绍正文内容需要足够长才行"),
                page("index/root", "索引", "index", "见 [[entity/missing|x]] 和 Acme"));

            var r = new KbWikiFinalizeStage(null).finalize(
                pages, KbWikiConfig.parse("{}"), List.of(), false, null);

            assertThat(r.pagesToUpdate())
                .as("索引页由系统按真实页面列表重建，不该走文本清理路径")
                .doesNotContainKey("index/root");
        }
    }
}
