package com.nexusai.kb.app.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki 提示词 · W2-2。
 *
 * <p><b>WHY</b>：提示词是「规格」不是「文案」。
 * 占位符少一个 ⇒ 模型拿到半截数据，输出看起来正常但内容错；
 * 铁律段丢了 ⇒ 模型开始编造/润色，而且<b>不报错</b>。
 * ⇒ 这些断言守的是「能不能把错误挡在生成之前」。
 */
@DisplayName("[W2] Wiki 提示词")
class KbWikiPromptsTest {

    @Test
    @DisplayName("⭐ 每个模板的占位符齐全（少一个就跑不通，且不会报错）")
    void allPlaceholdersPresent() {
        Map<String, String> templates = Map.ofEntries(
            Map.entry("CANDIDATE_SLUG_USER", KbWikiPrompts.CANDIDATE_SLUG_USER),
            Map.entry("SUMMARY_USER", KbWikiPrompts.SUMMARY_USER),
            Map.entry("CHUNK_CITATION_USER", KbWikiPrompts.CHUNK_CITATION_USER),
            Map.entry("DEDUPLICATION_USER", KbWikiPrompts.DEDUPLICATION_USER),
            Map.entry("TAXONOMY_PLAN_USER", KbWikiPrompts.TAXONOMY_PLAN_USER),
            Map.entry("PAGE_MODIFY_USER", KbWikiPrompts.PAGE_MODIFY_USER),
            Map.entry("PAGE_MODIFY_CONTINUATION_USER", KbWikiPrompts.PAGE_MODIFY_CONTINUATION_USER),
            Map.entry("INDEX_INTRO_USER", KbWikiPrompts.INDEX_INTRO_USER));

        Map<String, List<String>> required = KbWikiPrompts.requiredPlaceholders();

        assertThat(templates.keySet())
            .as("requiredPlaceholders 与实际模板必须一一对应（加了模板忘了登记同样会漏）")
            .containsExactlyInAnyOrderElementsOf(required.keySet());

        required.forEach((name, placeholders) -> {
            String body = templates.get(name);
            for (String p : placeholders) {
                assertThat(body)
                    .as("%s 缺少占位符 %s —— 模型会拿到半截数据", name, p)
                    .contains(p);
            }
        });
    }

    @Test
    @DisplayName("⭐⭐ 三条铁律必须逐字出现")
    void threeInvariants() {
        // 1) 文件名不喂给模型 —— 体现在摘要页 slug 用 ID（这里验提示词侧不出现「文件名/标题」诱导）
        assertThat(KbWikiPrompts.SUMMARY_SYSTEM)
            .as("⭐⭐ 必须明确禁止从文件名/标题推断，否则扫描件会诱发幻觉摘要")
            .contains("不许从文件名")
            .contains("不许猜主题");

        // 2) chunk id 不进正文 —— 系统提示必须同时说「不许新写」和「删掉历史遗留」
        assertThat(KbWikiPrompts.PAGE_MODIFY_SYSTEM)
            .as("⭐⭐ 只写「不许新写」不够：老页面里的 [c003] 会被原样留下来")
            .contains("不许出现 chunk 编号")
            .contains("编辑时必须删掉");

        // 3) 编译器不是作家
        assertThat(KbWikiPrompts.PAGE_MODIFY_SYSTEM)
            .as("⭐⭐ 这条决定「不能润色」—— 润色就等于允许编造")
            .contains("编译器")
            .contains("不是作家");
    }

    @Test
    @DisplayName("⭐ 13 条页面合并规则的关键几条都在")
    void pageModifyRules() {
        String s = KbWikiPrompts.PAGE_MODIFY_SYSTEM;
        assertThat(s).contains("强制有据");       // 规则 2
        assertThat(s).contains("不许编造");       // 规则 3
        assertThat(s).contains("矛盾与更新");     // 规则 3 的冲突处理
        assertThat(s).contains("共享源上下文");   // 规则 4
        assertThat(s).contains("优先级");         // 规则 5
        assertThat(s).contains("轻度重排");       // 规则 6
        assertThat(s).contains("不许过度结构化"); // 规则 7
        assertThat(s).contains("修辞填充词");     // 规则 8
        assertThat(s).contains("保持归属");       // 规则 9
        assertThat(s).contains("不许自造 slug");  // 规则 11
        assertThat(s).contains("逐字复制");       // 规则 12
        assertThat(s).contains("SUMMARY:");       // 规则 13
    }

    @Test
    @DisplayName("⭐⭐ 冲突时必须「拒绝」而不是硬塞（源侧强调两次）")
    void conflictMustReject() {
        assertThat(KbWikiPrompts.PAGE_MODIFY_USER)
            .contains("先做冲突检查")
            .contains("必须拒绝这部分新信息")
            .as("⭐⭐ 只写「注意是否讲同一事物」太软；必须明确写「拒绝」")
            .contains("不要把它加进页面");
    }

    @Test
    @DisplayName("⭐ 去重必须给出正例和反例（只给正例模型会过度合并）")
    void dedupeHasBothExamples() {
        String s = KbWikiPrompts.DEDUPLICATION_SYSTEM;
        assertThat(s).contains("应该合并").contains("不应该");
        // 版本不同不能并
        assertThat(s).contains("GPT-4").contains("GPT-3.5");
        // 子集关系不能并
        assertThat(s).contains("子集关系");
        // 中文场景的证件类反例（源侧特意列了，本仓保留）
        assertThat(s).contains("居民身份证").contains("工作居住证");
        assertThat(s).contains("宁可同一事物建两个页面");
    }

    @Test
    @DisplayName("⭐ 续写必须要求「第一个字符紧接着」且不许重复")
    void continuationRules() {
        String s = KbWikiPrompts.PAGE_MODIFY_CONTINUATION_SYSTEM;
        assertThat(s).contains("不许重复已写内容");
        assertThat(s).contains("第一个字符");
        assertThat(s).contains("逐字追加");
        assertThat(s).contains("(complete)")
            .as("⭐ 已写完时必须能终止，否则会无限续写");
    }

    @Test
    @DisplayName("索引页介绍 ⛔ 不许生成目录（目录由系统拼）")
    void indexIntroNoToc() {
        assertThat(KbWikiPrompts.INDEX_INTRO_SYSTEM).contains("不许生成目录列表");
        assertThat(KbWikiPrompts.INDEX_INTRO_UPDATE_SYSTEM).contains("不许生成目录列表");
        assertThat(KbWikiPrompts.INDEX_INTRO_UPDATE_SYSTEM)
            .as("⭐ 增量更新必须「最小改动」，否则每轮都会重写整段")
            .contains("最小改动");
    }

    @Test
    @DisplayName("⭐ 目录规划：≤2 级 + 复用已有名 + 每页恰好一次")
    void taxonomyRules() {
        String s = KbWikiPrompts.TAXONOMY_PLAN_SYSTEM;
        assertThat(s).contains("最多 2 级");
        assertThat(s).contains("逐字复用");
        assertThat(s).contains("恰好出现一次");
        assertThat(s).contains("不许用 `entity` / `concept` 当目录名");
    }

    @Test
    @DisplayName("⭐⭐ 三档粒度文本各不相同（抹平区分度=召回下降）")
    void granularityTiersDistinct() {
        String focused = KbWikiPrompts.granularityBlock("focused");
        String standard = KbWikiPrompts.granularityBlock("standard");
        String exhaustive = KbWikiPrompts.granularityBlock("exhaustive");

        assertThat(focused).contains("聚焦").contains("3-7");
        assertThat(standard).contains("标准");
        assertThat(exhaustive).contains("穷尽");

        assertThat(focused).isNotEqualTo(standard);
        assertThat(standard).isNotEqualTo(exhaustive);
        assertThat(focused).isNotEqualTo(exhaustive);

        assertThat(KbWikiPrompts.granularityBlock("unknown"))
            .as("非法粒度回落 standard")
            .isEqualTo(standard);
        assertThat(KbWikiPrompts.granularityBlock(null)).isEqualTo(standard);
    }

    @Test
    @DisplayName("⭐ 提示词里不许出现未替换的其它占位符语法（防格式化冲突）")
    void noAccidentalFormatSpecifiers() {
        // ⚠️ 我们不用 String.format，但若有人加了 %s 会静默失效
        assertThat(KbWikiPrompts.SUMMARY_SYSTEM).doesNotContain("%s");
        assertThat(KbWikiPrompts.PAGE_MODIFY_USER).doesNotContain("%s");
    }
}
