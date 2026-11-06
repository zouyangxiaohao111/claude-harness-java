package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 3 · {@link KbQaEntityExtract} 的纯规则测试。
 *
 * <p>⚠️ 本类只测<b>可以不碰数据库/模型就断言</b>的两条规则：
 * <ol>
 *   <li>「按库展开」的去重与保序（{@code groupByKb}）</li>
 *   <li>空结果形态（{@code Outcome.empty()}）</li>
 * </ol>
 *
 * <p><b>为什么不测「未开图谱零调用」</b>：那需要注入 {@code KbKnowledgeBaseMapper} +
 * {@code ModelConfigResolver} + 计数 {@code LlmSideCallClient}，而这三个都是具体的
 * Spring 组件（非接口、字段注入），单测里造出来只能靠反射塞字段 —— 那种测试验的是
 * 「反射塞对了没」，不是业务规则。<b>该判据改由真机日志验</b>（见 RUN_LOG 判据 3）：
 * 未开图谱时日志必须出现「均未开启图谱，跳过抽取（未调模型）」且无模型调用记录。
 */
class KbQaEntityExtractTest {

    @Test
    @DisplayName("按库展开：同一个实体列表给每个开启的库")
    void groupByKbCoversAllEnabledKbs() {
        Map<String, List<String>> got = KbQaEntityExtract.groupByKb(
            List.of("区生态环境局", "污水处理站"), List.of("kb-a", "kb-b"));

        assertThat(got).hasSize(2);
        assertThat(got.get("kb-a")).containsExactly("区生态环境局", "污水处理站");
        assertThat(got.get("kb-b")).containsExactly("区生态环境局", "污水处理站");
    }

    @Test
    @DisplayName("⭐ 实体名去重且保持首次出现顺序")
    void groupByKbDedupesKeepingOrder() {
        Map<String, List<String>> got = KbQaEntityExtract.groupByKb(
            List.of("乙", "甲", "乙", "丙", "甲"), List.of("kb-a"));

        assertThat(got.get("kb-a")).containsExactly("乙", "甲", "丙");
    }

    @Test
    @DisplayName("null / 空白实体名被丢弃，不产生空串条目")
    void groupByKbDropsBlankNames() {
        Map<String, List<String>> got = KbQaEntityExtract.groupByKb(
            java.util.Arrays.asList("甲", null, "  ", "", "乙"), List.of("kb-a"));

        assertThat(got.get("kb-a")).containsExactly("甲", "乙");
    }

    @Test
    @DisplayName("实体名前后空白被剥掉（模型常带空格）")
    void groupByKbStripsWhitespace() {
        assertThat(KbQaEntityExtract.groupByKb(List.of("  甲  "), List.of("kb-a")).get("kb-a"))
            .containsExactly("甲");
    }

    @Test
    @DisplayName("没有开启的库 ⇒ 空 map（⛔ 不能给空列表的 key）")
    void groupByKbEmptyEnabled() {
        assertThat(KbQaEntityExtract.groupByKb(List.of("甲"), List.of())).isEmpty();
        assertThat(KbQaEntityExtract.groupByKb(List.of("甲"), null)).isEmpty();
    }

    @Test
    @DisplayName("实体名全空 ⇒ 空 map（不产生「库 → []」）")
    void groupByKbNoNames() {
        assertThat(KbQaEntityExtract.groupByKb(List.of(), List.of("kb-a"))).isEmpty();
        assertThat(KbQaEntityExtract.groupByKb(java.util.Arrays.asList(null, "  "),
            List.of("kb-a"))).isEmpty();
        assertThat(KbQaEntityExtract.groupByKb(null, List.of("kb-a"))).isEmpty();
    }

    @Test
    @DisplayName("Outcome.empty() 的 isEmpty / enabledKbs 语义")
    void emptyOutcomeSemantics() {
        KbQaEntityExtract.Outcome empty = KbQaEntityExtract.Outcome.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.enabledKbs()).isZero();
        assertThat(empty.entitiesByKb()).isEmpty();
    }

    @Test
    @DisplayName("enabledKbs > 0 但没抽到实体 ⇒ isEmpty 仍为 true（用于跳过 4c）")
    void enabledButNoEntitiesIsStillEmpty() {
        KbQaEntityExtract.Outcome outcome =
            new KbQaEntityExtract.Outcome(Map.of(), 2);
        assertThat(outcome.isEmpty()).isTrue();
        assertThat(outcome.enabledKbs()).isEqualTo(2);
    }

    // ── 模型输出解析（查询期格式：{"entity": "..."}）────────────────────

    @Test
    @DisplayName("标准查询期输出：{\"entity\":...} 数组")
    void parseStdEntityFormat() {
        assertThat(KbQaEntityExtract.parseEntityNames(
            "[{\"entity\":\"区生态环境局\"},{\"entity\":\"污水处理站\"}]"))
            .containsExactly("区生态环境局", "污水处理站");
    }

    @Test
    @DisplayName("⭐⭐ 带 ```json 围栏（模型最常见的输出形态）")
    void parseFenced() {
        assertThat(KbQaEntityExtract.parseEntityNames("""
            ```json
            [
              {"entity": "Romeo and Juliet"},
              {"entity": "William Shakespeare"}
            ]
            ```
            """)).containsExactly("Romeo and Juliet", "William Shakespeare");
    }

    @Test
    @DisplayName("JSON 前后有废话 ⇒ 抠出数组片段")
    void parseSurroundedByProse() {
        assertThat(KbQaEntityExtract.parseEntityNames(
            "好的，以下是抽取结果：\n[{\"entity\":\"甲\"}]\n希望有帮助"))
            .containsExactly("甲");
    }

    @Test
    @DisplayName("容错键名：entity 缺失时退到 name / title")
    void parseFallbackKeys() {
        assertThat(KbQaEntityExtract.parseEntityNames(
            "[{\"name\":\"甲\"},{\"title\":\"乙\"},{\"entity\":\"丙\"}]"))
            .containsExactly("甲", "乙", "丙");
    }

    @Test
    @DisplayName("空值 / 非字符串 / 空数组 ⇒ 空列表，不抛")
    void parseDegenerate() {
        assertThat(KbQaEntityExtract.parseEntityNames(null)).isEmpty();
        assertThat(KbQaEntityExtract.parseEntityNames("")).isEmpty();
        assertThat(KbQaEntityExtract.parseEntityNames("[]")).isEmpty();
        assertThat(KbQaEntityExtract.parseEntityNames("不是 JSON")).isEmpty();
        assertThat(KbQaEntityExtract.parseEntityNames("[{\"entity\":null}]")).isEmpty();
        assertThat(KbQaEntityExtract.parseEntityNames("[{\"entity\":123}]")).isEmpty();
        assertThat(KbQaEntityExtract.parseEntityNames("[{\"entity\":\"  \"}]")).isEmpty();
    }

    @Test
    @DisplayName("重复实体名去重且保序")
    void parseDedupes() {
        assertThat(KbQaEntityExtract.parseEntityNames(
            "[{\"entity\":\"乙\"},{\"entity\":\"甲\"},{\"entity\":\"乙\"}]"))
            .containsExactly("乙", "甲");
    }

    @Test
    @DisplayName("实体名前后空白被剥掉")
    void parseStrips() {
        assertThat(KbQaEntityExtract.parseEntityNames("[{\"entity\":\"  甲  \"}]"))
            .containsExactly("甲");
    }

    @Test
    @DisplayName("user 消息形状对齐源侧 QAPromptGenerator.User")
    void userMessageShape() {
        String msg = KbQaEntityExtract.userMessage("什么是湿地");
        assertThat(msg).startsWith("# Question\nQ: 什么是湿地");
        assertThat(msg).endsWith("# Answer\nA: ");
    }
}
