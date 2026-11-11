package com.nexusai.kb.app.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块配置解析 · 本批新增 4 个键（父子 3 + 解析引擎规则 1）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：配置解析是「读错了也不报错」的典型层 ——
 * 键名拼错、类型判错（数字当布尔）、数组项结构不符，结果都是一份<b>看起来正常</b>的默认配置，
 * 用户勾了父子分块却仍按普通切分入库。故必须钉住「这些键真的被读到」。
 *
 * <p>另外两条容错约定也要钉：
 * <ol>
 *   <li>尺寸越界 ⇒ clamp 到区间（而不是回落默认）；{@code <=0} ⇒ 用默认（源侧 {@code <=0} 同语义）</li>
 *   <li>坏规则项被丢弃，但<b>不</b>牵连其它好规则</li>
 * </ol>
 */
@DisplayName("[B0] 分块配置解析（父子 3 键 + parser_engine_rules）")
class KbChunkingConfigParserTest {

    @Test
    @DisplayName("7 个键全给 ⇒ 逐字段读到位")
    void readsAllNewKeys() {
        String json = """
            {
              "chunk_size": 512,
              "chunk_overlap": 80,
              "enable_parent_child": true,
              "parent_chunk_size": 2048,
              "child_chunk_size": 256,
              "parser_engine_rules": [
                {"file_types": ["pdf"], "engine": "KbAnydocParser"},
                {"file_types": ["docx", ".DOC"], "engine": "KbOfficeParser"}
              ]
            }
            """;

        KbSplitterConfig cfg = KbChunkingConfigParser.parse(json);

        assertThat(cfg.enableParentChild()).isTrue();
        assertThat(cfg.parentChunkSize()).isEqualTo(2048);
        assertThat(cfg.childChunkSize()).isEqualTo(256);
        assertThat(cfg.parserEngineRules()).hasSize(2);
        assertThat(cfg.parserEngineRules().get(0).fileTypes()).containsExactly("pdf");
        assertThat(cfg.parserEngineRules().get(0).engine()).isEqualTo("KbAnydocParser");
        assertThat(cfg.parserEngineRules().get(1).fileTypes()).containsExactly("docx", ".DOC");
        assertThat(cfg.parserEngineRules().get(1).engine()).isEqualTo("KbOfficeParser");
    }

    @Test
    @DisplayName("缺省 ⇒ 父子关闭 + 4096/384 + 规则为空（行为与引入前一致）")
    void defaultsWhenAbsent() {
        KbSplitterConfig cfg = KbChunkingConfigParser.parse("{\"chunk_size\": 512}");

        assertThat(cfg.enableParentChild()).isFalse();
        assertThat(cfg.parentChunkSize()).isEqualTo(KbSplitterConfig.DEFAULT_PARENT_CHUNK_SIZE);
        assertThat(cfg.childChunkSize()).isEqualTo(KbSplitterConfig.DEFAULT_CHILD_CHUNK_SIZE);
        assertThat(cfg.parserEngineRules()).isEmpty();
    }

    @Test
    @DisplayName("enable_parent_child 接受数字 0/1（DDL 存的是 0/1）")
    void booleanAcceptsNumericForm() {
        assertThat(KbChunkingConfigParser.parse("{\"enable_parent_child\": 1}").enableParentChild())
            .isTrue();
        assertThat(KbChunkingConfigParser.parse("{\"enable_parent_child\": 0}").enableParentChild())
            .isFalse();
    }

    @Test
    @DisplayName("尺寸 clamp：父 512..8192、子 64..2048；<=0 回落默认")
    void sizesAreClamped() {
        assertThat(KbChunkingConfigParser.parse(
            "{\"parent_chunk_size\": 10, \"child_chunk_size\": 99999}").normalized())
            .satisfies(c -> {
                assertThat(c.parentChunkSize()).isEqualTo(KbSplitterConfig.MIN_PARENT_CHUNK_SIZE);
                assertThat(c.childChunkSize()).isEqualTo(KbSplitterConfig.MAX_CHILD_CHUNK_SIZE);
            });
        assertThat(KbChunkingConfigParser.parse(
            "{\"parent_chunk_size\": 99999, \"child_chunk_size\": 1}").normalized())
            .satisfies(c -> {
                assertThat(c.parentChunkSize()).isEqualTo(KbSplitterConfig.MAX_PARENT_CHUNK_SIZE);
                assertThat(c.childChunkSize()).isEqualTo(KbSplitterConfig.MIN_CHILD_CHUNK_SIZE);
            });
        assertThat(KbChunkingConfigParser.parse(
            "{\"parent_chunk_size\": 0, \"child_chunk_size\": -5}").normalized())
            .satisfies(c -> {
                assertThat(c.parentChunkSize()).isEqualTo(KbSplitterConfig.DEFAULT_PARENT_CHUNK_SIZE);
                assertThat(c.childChunkSize()).isEqualTo(KbSplitterConfig.DEFAULT_CHILD_CHUNK_SIZE);
            });
    }

    @Test
    @DisplayName("坏规则项被丢弃，好规则保留（不整表回落默认）")
    void malformedRulesAreDroppedIndividually() {
        String json = """
            {"parser_engine_rules": [
              {"file_types": ["pdf"], "engine": "KbAnydocParser"},
              {"file_types": [], "engine": "KbOfficeParser"},
              {"file_types": ["xlsx"]},
              {"file_types": "not-an-array", "engine": "KbTextParser"},
              "not-an-object",
              {"file_types": ["pptx"], "engine": "KbOfficeParser"}
            ]}
            """;

        List<ParserEngineRule> rules = KbChunkingConfigParser.parse(json).parserEngineRules();

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).engine()).isEqualTo("KbAnydocParser");
        assertThat(rules.get(1).engine()).isEqualTo("KbOfficeParser");
        assertThat(rules.get(1).fileTypes()).containsExactly("pptx");
    }

    @Test
    @DisplayName("非法 JSON ⇒ 回落默认（不抛）")
    void invalidJsonFallsBack() {
        KbSplitterConfig cfg = KbChunkingConfigParser.parse("{not json");
        assertThat(cfg.enableParentChild()).isFalse();
        assertThat(cfg.chunkSize()).isEqualTo(KbSplitterConfig.DEFAULT_CHUNK_SIZE);
    }

    @Test
    @DisplayName("规则匹配：两侧规范化（大小写 / 前导点 / 空白）")
    void ruleMatchesWithNormalization() {
        ParserEngineRule rule = new ParserEngineRule(List.of(".PDF", " Docx "), "KbAnydocParser");
        assertThat(rule.matches("pdf")).isTrue();
        assertThat(rule.matches(".PDF")).isTrue();
        assertThat(rule.matches("docx")).isTrue();
        assertThat(rule.matches("DOCX")).isTrue();
        assertThat(rule.matches("xlsx")).isFalse();
        assertThat(rule.matches("")).isFalse();
        assertThat(rule.matches(null)).isFalse();
    }
}
