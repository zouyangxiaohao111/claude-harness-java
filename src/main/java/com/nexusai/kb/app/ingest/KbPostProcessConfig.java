package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.kb.app.wiki.KbWikiConfig;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后处理（增强）配置解析 · 对齐 WeKnora
 * {@code internal/application/service/knowledge_process_config.go} +
 * {@code knowledge_post_process.go:216-224} 的 {@code willSpawn*} 判据。
 *
 * <p><b>⭐⭐ 三条「不按配置来就会静默出错」的规则</b>：
 * <ol>
 *   <li><b>配置缺省 ≠ 关闭</b>：{@code auto_tag_config} 为 null / 缺 {@code enabled} ⇒ 关闭；
 *       而 Wiki 的 {@code auto_generate} 反过来 —— <b>null 表示开启</b>
 *       （老库不写这个字段，若按「缺省=关闭」解释，升级后会静默停掉已有 Wiki）。
 *       ⇒ {@code auto_generate} 必须走 {@link KbWikiConfig#isAutoGenerateEnabled()}，
 *       ⛔ 不要在本类里自己判 null。</li>
 *   <li><b>没有模型 = 不可执行</b>：摘要/问题/打标的模型为空时，源侧仍会派发任务再立刻失败
 *       （占一个槽位、多一次重试）。本仓把「没有模型」判在<b>派发前</b>，
 *       让文档直接完成 —— 少一次空转，用户也不会看到「处理失败」。</li>
 *   <li><b>问题生成依赖嵌入模型</b>：源侧 {@code kb.NeedsEmbeddingModel()}。
 *       只建 FTS 的知识库不该跑 Doc2Query（生成的问句没有向量可写）。</li>
 * </ol>
 *
 * @param summaryEnabled   是否生成摘要
 * @param summaryModelId   摘要模型（空 = 不可执行）
 * @param question         问题生成配置
 * @param autoTag          自动打标配置
 * @param wikiEnabled      是否自动生成 Wiki = 开关开着 **且** 有模型（{@code auto_generate} 三态语义）
 * @param wikiModelId      Wiki 生成模型 = {@code wiki_config.synthesis_model_id} → 回退 {@code summary_model_id}
 * @param graphEnabled     是否抽图谱 = {@code indexing_strategy.graph_enabled} **且** {@code extract_config.enabled}
 *                         **且** 有模型（源侧 {@code KnowledgeBase.IsGraphEnabled()}）
 */
public record KbPostProcessConfig(
    boolean summaryEnabled,
    String summaryModelId,
    QuestionGeneration question,
    AutoTag autoTag,
    boolean wikiEnabled,
    String wikiModelId,
    boolean graphEnabled
) {

    private static final Logger log = LoggerFactory.getLogger(KbPostProcessConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 问题生成的每块默认问句数（源侧 {@code ProcessQuestionGeneration}: ctx<=0 ⇒ 3，上限 10）。 */
    public static final int DEFAULT_QUESTION_COUNT = 3;

    /** 自动打标默认最多打几个标签（{@code AutoTagConfig.Normalize} 的默认）。 */
    public static final int DEFAULT_MAX_TAGS = 5;

    /**
     * 问题生成配置（对应 {@code kb_knowledge_base.question_generation_config} JSON）。
     *
     * @param enabled            是否开启
     * @param modelId            问句模型（空 = 继承摘要模型）
     * @param questionCount      每块生成几条（1..10）
     * @param customInstructions 额外指令（可空）
     * @param embeddingModelId   知识库的嵌入模型（空 ⇒ 不能跑）
     */
    public record QuestionGeneration(
        boolean enabled, String modelId, int questionCount,
        String customInstructions, String embeddingModelId
    ) {
        /** 是否真的可以跑（开关 + 有嵌入模型）。 */
        public boolean runnable() {
            return enabled && embeddingModelId != null && !embeddingModelId.isBlank();
        }
    }

    /**
     * 自动打标配置（对应 {@code kb_knowledge_base.auto_tag_config} JSON）。
     *
     * @param enabled      是否开启
     * @param modelId      分类模型（空 = 继承摘要模型）
     * @param maxTags      最多打几个（1..10）
     * @param skipIfTagged 已有标签的文档是否跳过（源侧 <b>null ⇒ true</b>）
     */
    public record AutoTag(boolean enabled, String modelId, int maxTags, boolean skipIfTagged) {
        /** 是否真的可以跑（开关 + 有模型）。 */
        public boolean runnable() {
            return enabled && modelId != null && !modelId.isBlank();
        }
    }

    /** 全部关闭（单测 / 兜底用）。 */
    public static final KbPostProcessConfig NONE = new KbPostProcessConfig(
        false, "", new QuestionGeneration(false, "", DEFAULT_QUESTION_COUNT, "", ""),
        new AutoTag(false, "", DEFAULT_MAX_TAGS, true), false, "", false);

    /**
     * 从知识库行解析。
     *
     * @param kb 知识库行（null ⇒ {@link #NONE}）
     * @return 解析结果（永不 null）
     */
    public static KbPostProcessConfig resolve(KbKnowledgeBaseRecord kb) {
        if (kb == null) {
            return NONE;
        }
        String summaryModel = trim(kb.getSummaryModelId());

        JsonNode q = parse(kb.getQuestionGenerationConfig(), "question_generation_config");
        boolean qEnabled = bool(q, "enabled", false);
        String qModel = trim(text(q, "model_id"));
        if (qModel.isEmpty()) {
            qModel = summaryModel;          // 源侧：空 ⇒ 继承摘要模型
        }
        int qCount = clamp(intVal(q, "question_count", DEFAULT_QUESTION_COUNT), 1, 10);

        JsonNode t = parse(kb.getAutoTagConfig(), "auto_tag_config");
        boolean tEnabled = bool(t, "enabled", false);
        String tModel = trim(text(t, "model_id"));
        if (tModel.isEmpty()) {
            tModel = summaryModel;
        }
        int tMax = clamp(intVal(t, "max_tags", DEFAULT_MAX_TAGS), 1, 10);
        // ⚠️ 源侧 SkipIfTagged 是 *bool：null ⇒ true（AutoTagConfig.ShouldSkipIfTagged）
        boolean skipIfTagged = t == null || !t.hasNonNull("skip_if_tagged")
            || t.get("skip_if_tagged").asBoolean(true);

        // Wiki 模型：wiki_config.synthesis_model_id → 回退 summary_model_id
        // （源侧 wiki_ingest_batch.go:404 「Empty uses summary_model_id」）
        KbWikiConfig wikiConfig = KbWikiConfig.parse(kb.getWikiConfig());
        String wikiModel = trim(wikiConfig.getSynthesisModelId());
        if (wikiModel.isEmpty()) {
            wikiModel = summaryModel;
        }

        return new KbPostProcessConfig(
            // 摘要默认开（源侧 ProcessConfig.SummaryEnabled = true），但没有模型就不派发
            !summaryModel.isEmpty(),
            summaryModel,
            new QuestionGeneration(qEnabled, qModel, qCount,
                trim(text(q, "custom_instructions")), trim(kb.getEmbeddingModelId())),
            new AutoTag(tEnabled, tModel, tMax, skipIfTagged),
            // ⭐ Wiki 是否「可执行」= 开关开着 **且** 有模型。
            //    只判开关的话，没配模型的知识库也会派发一个必然 skip 的 Wiki 任务，
            //    白白把文档推进 finalizing 再立刻放出来（用户会看到状态闪一下「处理中」）。
            wikiConfig.isAutoGenerateEnabled() && !wikiModel.isEmpty(),
            wikiModel,
            // ⭐ 图谱开关是**两个**字段的合取（源侧 IsGraphEnabled）：
            //    indexing_strategy.graph_enabled 是「要图谱」，extract_config.enabled 是「抽什么」。
            //    只判其中一个 ⇒ 用户以为自己开了图谱，实际什么都没抽。
            //    外加「有模型」—— 与 W6 其余三条同口径（开关 ∧ 依赖就绪）。
            graphEnabled(kb) && !summaryModel.isEmpty());
    }

    /**
     * 图谱开关：{@code indexing_strategy.graph_enabled} ∧ {@code extract_config.enabled}。
     *
     * <p>⭐ 为什么必须两个都判：{@code graph_enabled} 表示「这个库要图谱」，
     * {@code extract_config} 表示「抽哪些实体/关系」。源侧
     * {@code KnowledgeBase.IsGraphEnabled()} 就是这两者的合取。
     * 只判前者 ⇒ 用户勾了「启用图谱」但没配抽取规则 ⇒ 抽出来的图毫无领域约束。
     *
     * @param kb 知识库行
     * @return 是否开启
     */
    public static boolean graphEnabled(KbKnowledgeBaseRecord kb) {
        if (kb == null) {
            return false;
        }
        JsonNode strategy = parse(kb.getIndexingStrategy(), "indexing_strategy");
        if (!bool(strategy, "graph_enabled", false)) {
            return false;
        }
        JsonNode extract = parse(kb.getExtractConfig(), "extract_config");
        return bool(extract, "enabled", false);
    }

    // ── JSON 小工具（全部 fail-soft：坏配置按「未配置」处理，不让整个入库失败）──

    private static JsonNode parse(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            log.warn("[后处理] 配置不是合法 JSON，按未配置处理 what={} 原因={}", what, e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null) {
            return "";
        }
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static boolean bool(JsonNode n, String field, boolean fallback) {
        if (n == null) {
            return fallback;
        }
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asBoolean(fallback);
    }

    private static int intVal(JsonNode n, String field, int fallback) {
        if (n == null) {
            return fallback;
        }
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asInt(fallback);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
