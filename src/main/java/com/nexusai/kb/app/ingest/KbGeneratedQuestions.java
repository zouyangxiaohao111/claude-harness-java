package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成的辅助问句（Doc2Query）· 存于 {@code kb_chunk.metadata} 的读写口径。
 *
 * <p><b>为什么问句要进索引正文，而不是只存着</b>：
 * Doc2Query 的意义是「用户用问句搜，也能命中这段正文」。若问句只落在 metadata 里、
 * 不进 FTS / 向量输入，那它们对召回率<b>零贡献</b> —— 功能看起来做完了，实际完全无效。
 * ⇒ 索引器 {@link com.nexusai.kb.app.retrieval.KbIndexWriter} 在
 * 建 FTS 与算向量时都会把这里的问句拼进去。
 *
 * <p><b>⭐⭐ 为什么拼接只影响「索引文本」，不改 {@code kb_chunk.content}</b>：
 * {@code content} 与 {@code start_at/end_at} 有位置不变式（{@code end-start == content 的 rune 数}），
 * 往正文里塞问句会直接破坏它 ⇒ 文档重建、UI 高亮、分块编辑全部错位。
 *
 * <p>JSON 形态（写在 chunk.metadata 里，与其他 key 并存）：
 * <pre>
 * {"generated_questions":[{"id":"q...","question":"...","content_revision":3}],
 *  "generated_questions_revision":3}
 * </pre>
 */
public final class KbGeneratedQuestions {

    private static final Logger log = LoggerFactory.getLogger(KbGeneratedQuestions.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** metadata 里的问句数组键名（与源侧 {@code DocumentChunkMetadata.GeneratedQuestions} 对齐）。 */
    public static final String KEY = "generated_questions";

    private KbGeneratedQuestions() {
    }

    /**
     * 读出问句文本列表。
     *
     * @param metadataJson chunk.metadata 原文（可 null / 坏 JSON）
     * @return 问句列表（永不 null；坏数据 ⇒ 空列表，⛔ 不抛 —— 一条脏 metadata 不该让整个索引失败）
     */
    public static List<String> read(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(metadataJson);
            JsonNode arr = root == null ? null : root.get(KEY);
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                String q = n.isTextual() ? n.asText() : n.path("question").asText("");
                if (q != null && !q.isBlank()) {
                    out.add(q.trim());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.warn("[问句] metadata 解析失败，按无问句处理 原因={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 拼成追加到索引文本尾部的片段。
     *
     * @param metadataJson chunk.metadata
     * @return 以换行开头的片段（无问句时返回空串，便于直接字符串相加）
     */
    public static String indexText(String metadataJson) {
        List<String> questions = read(metadataJson);
        if (questions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String q : questions) {
            sb.append('\n').append(q);
        }
        return sb.toString();
    }
}
