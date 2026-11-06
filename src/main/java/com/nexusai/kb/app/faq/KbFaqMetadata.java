package com.nexusai.kb.app.faq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * FAQ 条目的元数据与渲染 · 批次 W5。
 *
 * <p><b>源侧对照</b>：WeKnora {@code internal/types/faq.go} 的
 * {@code FAQChunkMetadata} / {@code Sanitize} / {@code Normalize} / {@code buildFAQChunkContent}。
 *
 * <h2>⭐⭐ FAQ 条目不是独立表，而是 {@code kb_chunk} 的一行</h2>
 * 见 O-3：源侧同样如此（{@code chunk_type='faq'}，字段全在 {@code chunk.metadata}）。
 * 本类只负责「元数据 JSON ↔ Java 对象」与「供索引的正文渲染」，不碰数据库。
 *
 * <h2>⭐⭐ 三个「不这么做就静默出错」的点</h2>
 * <ol>
 *   <li><b>反例问题绝不进 content</b> —— 反例是「这句话不该命中」，
 *       写进索引正文就会变成可被召回的文本，等于把反例变成正例。</li>
 *   <li><b>答案是否进 content 取决于 index_mode</b> ——
 *       {@code question_only} 只索引问法（答案不参与召回）；{@code question_answer} 才带上答案。</li>
 *   <li><b>归一化只用于哈希/向量，不改库里的原文</b> —— 原文是用户写的，
 *       归一化（小写、全角转半角、去标点）只用于判重与 embedding 输入。</li>
 * </ol>
 */
public final class KbFaqMetadata {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 索引模式：只索引问法（源侧默认，见 CreateFAQEntry）。 */
    public static final String INDEX_MODE_QUESTION_ONLY = "question_only";
    /** 索引模式：问法 + 答案一起索引。 */
    public static final String INDEX_MODE_QUESTION_ANSWER = "question_answer";

    /** 答案策略：返回全部。 */
    public static final String STRATEGY_ALL = "all";
    /** 答案策略：随机返回一条。 */
    public static final String STRATEGY_RANDOM = "random";

    /** 「未分类」标签名（源侧 {@code UntaggedTagName}）。 */
    public static final String UNTAGGED_TAG_NAME = "未分类";

    /** {@code kb_chunk.flags} 的「推荐」位（源侧 {@code ChunkFlagRecommended = 1<<0}）。 */
    public static final int FLAG_RECOMMENDED = 1;

    /** {@code kb_chunk.status} 的「已索引」（源侧 {@code ChunkStatusIndexed = 2}）。 */
    public static final int STATUS_INDEXED = 2;

    private KbFaqMetadata() {
    }

    /**
     * 一条 FAQ 的元数据。
     *
     * @param standardQuestion  标准问
     * @param similarQuestions  相似问
     * @param negativeQuestions 反例（⛔ 不进索引正文）
     * @param answers           答案
     * @param answerStrategy    {@code all} / {@code random}
     * @param version           版本（新建为 1）
     * @param source            来源（本仓固定 {@code faq}）
     */
    public record Entry(
        String standardQuestion,
        List<String> similarQuestions,
        List<String> negativeQuestions,
        List<String> answers,
        String answerStrategy,
        int version,
        String source
    ) {
        /** 是否有答案（没有答案的条目对用户无用）。 */
        public boolean hasAnswers() {
            return answers != null && !answers.isEmpty();
        }
    }

    /**
     * 从用户输入构造元数据（对齐源侧 {@code sanitizeFAQEntryPayload}）。
     *
     * <p>⚠️ 默认 answer_strategy = {@code all}，默认 index 走 question_only（由调用方决定渲染）。
     *
     * @throws IllegalArgumentException 标准问为空 / 无答案 / 策略非法
     */
    public static Entry fromPayload(String standardQuestion, List<String> similarQuestions,
                                    List<String> negativeQuestions, List<String> answers,
                                    String answerStrategy) {
        String sq = standardQuestion == null ? "" : standardQuestion.trim();
        if (sq.isEmpty()) {
            throw new IllegalArgumentException("标准问不能为空");
        }
        String strategy = STRATEGY_ALL;
        if (answerStrategy != null && !answerStrategy.isBlank()) {
            String s = answerStrategy.trim().toLowerCase(Locale.ROOT);
            if (!s.equals(STRATEGY_ALL) && !s.equals(STRATEGY_RANDOM)) {
                throw new IllegalArgumentException("answer_strategy 必须是 'all' 或 'random'");
            }
            strategy = s;
        }
        List<String> ans = sanitizeList(answers);
        if (ans.isEmpty()) {
            throw new IllegalArgumentException("至少提供一个答案");
        }
        return new Entry(sq, sanitizeList(similarQuestions), sanitizeList(negativeQuestions),
            ans, strategy, 1, "faq");
    }

    /**
     * 渲染成 {@code kb_chunk.content}（供 FTS / 向量索引）。
     *
     * <p>⭐ 与源侧 {@code buildFAQChunkContent} 逐行一致：
     * <pre>
     * Q: 标准问
     * Similar Questions:
     * - 相似问1
     * Answers:              ← 仅 index_mode=question_answer 时出现
     * - 答案1
     * </pre>
     * ⛔ 反例<b>永不</b>出现。
     */
    public static String renderContent(Entry entry, String indexMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Q: ").append(entry.standardQuestion()).append('\n');
        if (entry.similarQuestions() != null && !entry.similarQuestions().isEmpty()) {
            sb.append("Similar Questions:\n");
            for (String q : entry.similarQuestions()) {
                sb.append("- ").append(q).append('\n');
            }
        }
        if (INDEX_MODE_QUESTION_ANSWER.equals(indexMode)
            && entry.answers() != null && !entry.answers().isEmpty()) {
            sb.append("Answers:\n");
            for (String a : entry.answers()) {
                sb.append("- ").append(a).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * FAQ 条目的<b>回答用</b>正文（对齐源侧 {@code chat_pipeline/merge_faq.go:buildFAQAnswerContent}）。
     *
     * <p><b>⭐⭐ 与 {@link #renderContent} 的区别（不能混用）</b>：
     * <ul>
     *   <li>{@code renderContent} 是<b>索引</b>内容 —— 要给相似问法也建索引，
     *       所以带 {@code Similar Questions}；</li>
     *   <li>本方法是<b>回答</b>内容 —— 模型只看标准问题 + 答案，
     *       相似问法属于检索侧的事，塞进上下文只会让模型把问法当答案的一部分。</li>
     * </ul>
     * 源侧这两段也是分开的（{@code buildFAQChunkContent} vs {@code buildFAQAnswerContent}），
     * 混用会表现为「回答里莫名出现一串相似问题」。
     *
     * @param entry 条目
     * @return {@code Q: ...\nAnswer:\n- ...}；问题与答案都空 → 空串
     */
    public static String answerContent(Entry entry) {
        if (entry == null) {
            return "";
        }
        String question = entry.standardQuestion() == null ? "" : entry.standardQuestion().strip();
        List<String> answers = new ArrayList<>();
        if (entry.answers() != null) {
            for (String a : entry.answers()) {
                if (a != null && !a.isBlank()) {
                    answers.add(a.strip());
                }
            }
        }
        if (question.isEmpty() && answers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!question.isEmpty()) {
            sb.append("Q: ").append(question).append('\n');
        }
        if (!answers.isEmpty()) {
            sb.append("Answer:\n");
            for (String a : answers) {
                sb.append("- ").append(a).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 判重用的归一化文本（对齐源侧 {@code Normalize}）。
     *
     * <p>⚠️ 只用于比较与哈希，⛔ 不改库里存的原文。
     * 规则：trim → 转小写 → 全角转半角 → 去常见标点与空白。
     */
    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(question.length());
        for (char c : question.toCharArray()) {
            if (c >= '！' && c <= '～') {
                // 全角 ASCII 段 ⇒ 半角（FF01..FF5E 对应 21..7E）
                sb.append((char) (c - 0xFEE0));
            } else if (c == '　') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().toLowerCase(Locale.ROOT);
        // 去掉标点与空白（保留中日韩文字与字母数字）
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 字符串列表清洗：trim、去空、去重（保持顺序）。
     *
     * <p>与源侧 {@code SanitizeStrings} 一致 —— 去重是<b>精确</b>去重，
     * 归一化去重在判重阶段单独做（见 {@link #normalize}）。
     */
    public static List<String> sanitizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String t = v.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    // ── JSON 序列化 ─────────────────────────────────────────────────

    /** 元数据 → JSON 文本（写进 {@code kb_chunk.metadata}）。 */
    public static String toJson(Entry entry) {
        ObjectNode node = JSON.createObjectNode();
        node.put("standard_question", entry.standardQuestion());
        putArray(node, "similar_questions", entry.similarQuestions());
        putArray(node, "negative_questions", entry.negativeQuestions());
        putArray(node, "answers", entry.answers());
        node.put("answer_strategy", entry.answerStrategy());
        node.put("version", entry.version());
        node.put("source", entry.source());
        return node.toString();
    }

    private static void putArray(ObjectNode node, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            // ⚠️ 空数组写 [] 而不是省略：读端拿到的结构稳定，
            //    前端不需要区分「没有这个字段」与「空列表」。
            node.set(field, JSON.createArrayNode());
            return;
        }
        ArrayNode arr = JSON.createArrayNode();
        for (String v : values) {
            arr.add(v);
        }
        node.set(field, arr);
    }

    /**
     * JSON 文本 → 元数据。
     *
     * <p>⚠️ 解析失败返回 null 而不是抛 —— 调用方（列表接口）遇到一条坏数据
     * 应该跳过它并继续，而不是让整个 FAQ 列表 500。
     */
    public static Entry fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) {
                return null;
            }
            String strategy = text(root, "answer_strategy");
            if (strategy.isEmpty()) {
                strategy = STRATEGY_ALL;
            }
            return new Entry(
                text(root, "standard_question"),
                stringList(root, "similar_questions"),
                stringList(root, "negative_questions"),
                stringList(root, "answers"),
                strategy,
                root.has("version") ? root.get("version").asInt(1) : 1,
                text(root, "source"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode v = root.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static List<String> stringList(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || !v.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(v.size());
        for (JsonNode n : v) {
            if (!n.isNull() && !n.asText("").isBlank()) {
                out.add(n.asText());
            }
        }
        return out;
    }

    // ── 索引模式解析 ────────────────────────────────────────────────

    /**
     * 从知识库的 {@code faq_config} JSON 解析索引模式。
     *
     * <p>⭐ 默认值是 {@code question_only}（<b>不是</b> question_answer）——
     * 与源侧 {@code CreateFAQEntry} 里的 {@code indexMode := FAQIndexModeQuestionOnly} 一致。
     * ⚠️ 这与 {@code KnowledgeBase.EnsureDefaults()} 给 FAQConfig 的默认值
     * （{@code question_answer}）不同，是源侧自身的一处不一致，照抄既有行为。
     */
    public static String resolveIndexMode(String faqConfigJson) {
        if (faqConfigJson == null || faqConfigJson.isBlank()) {
            return INDEX_MODE_QUESTION_ONLY;
        }
        try {
            JsonNode root = JSON.readTree(faqConfigJson);
            if (root == null || !root.isObject()) {
                return INDEX_MODE_QUESTION_ONLY;
            }
            JsonNode mode = root.get("index_mode");
            if (mode == null || mode.isNull()) {
                return INDEX_MODE_QUESTION_ONLY;
            }
            String m = mode.asText("").trim().toLowerCase(Locale.ROOT);
            return INDEX_MODE_QUESTION_ANSWER.equals(m) ? INDEX_MODE_QUESTION_ANSWER
                : INDEX_MODE_QUESTION_ONLY;
        } catch (Exception e) {
            return INDEX_MODE_QUESTION_ONLY;
        }
    }
}
