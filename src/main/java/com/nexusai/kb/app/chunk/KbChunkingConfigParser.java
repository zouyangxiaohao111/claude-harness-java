package com.nexusai.kb.app.chunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 把知识库的 {@code chunking_config} JSON 解析成 {@link KbSplitterConfig}。
 *
 * <p><b>为什么单独一个类</b>：DB 里存的是<b>源侧原始 JSON 字符串</b>
 * （{@code kb_knowledge_base.chunking_config}，默认值逐字抄自源侧 DDL），
 * 而分块器只认强类型配置。两者之间的字段名映射（{@code split_markers} vs
 * {@code separators}）不写清楚，下一批人一定会踩。
 *
 * <p><b>兼容两种键名</b>：
 * <ul>
 *   <li>{@code split_markers} —— 源侧 <b>DDL 默认值</b>里用的名字</li>
 *   <li>{@code separators} —— 源侧 <b>Go 结构体</b>里用的名字</li>
 * </ul>
 * 两者是同一语义（{@code KbSplitterConfig#separators}）。若都缺省则用代码默认值。
 *
 * <p><b>容错策略</b>：JSON 非法 / 字段类型不对 ⇒ 记 warn 后回落默认配置，
 * ⛔ 不让一个坏配置把整条入库流程打死（源侧同样是「取不到就用默认」）。
 */
public final class KbChunkingConfigParser {

    private static final Logger log = LoggerFactory.getLogger(KbChunkingConfigParser.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private KbChunkingConfigParser() {
    }

    /**
     * 解析。
     *
     * @param json 知识库的 {@code chunking_config} 原文（可 null / 空 / 非法）
     * @return 强类型配置（永不返回 null；非法输入回落 {@link KbSplitterConfig#defaults()}）
     */
    public static KbSplitterConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return KbSplitterConfig.defaults();
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            log.warn("[分块配置] JSON 解析失败，回落默认配置 原因={}", e.getMessage());
            return KbSplitterConfig.defaults();
        }
        if (root == null || !root.isObject()) {
            log.warn("[分块配置] 不是 JSON 对象，回落默认配置");
            return KbSplitterConfig.defaults();
        }

        int chunkSize = intOf(root, "chunk_size", KbSplitterConfig.DEFAULT_CHUNK_SIZE);
        int chunkOverlap = intOf(root, "chunk_overlap", KbSplitterConfig.DEFAULT_CHUNK_OVERLAP);
        List<String> separators = stringListOf(root, "split_markers");
        if (separators.isEmpty()) {
            separators = stringListOf(root, "separators");
        }
        if (separators.isEmpty()) {
            separators = KbSplitterConfig.DEFAULT_SEPARATORS;
        }
        String strategy = textOf(root, "strategy");
        int tokenLimit = intOf(root, "token_limit", 0);
        List<String> languages = stringListOf(root, "languages");

        return new KbSplitterConfig(
            chunkSize, chunkOverlap, separators, strategy, tokenLimit, languages)
            .normalized();
    }

    private static int intOf(JsonNode root, String field, int fallback) {
        JsonNode node = root.get(field);
        if (node == null || !node.isNumber()) {
            return fallback;
        }
        return node.asInt(fallback);
    }

    private static String textOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || !node.isTextual() ? "" : node.asText("");
    }

    private static List<String> stringListOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out;
    }
}
