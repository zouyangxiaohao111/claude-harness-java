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
 * @param image            图片理解配置（caption / OCR）
 */
public record KbPostProcessConfig(
    boolean summaryEnabled,
    String summaryModelId,
    QuestionGeneration question,
    AutoTag autoTag,
    boolean wikiEnabled,
    String wikiModelId,
    boolean graphEnabled,
    ImageUnderstanding image
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
     * @param indexingReady      知识库的索引策略是否要求嵌入（`vector_enabled || keyword_enabled`）
     */
    public record QuestionGeneration(
        boolean enabled, String modelId, int questionCount,
        String customInstructions, boolean indexingReady
    ) {
        /**
         * 是否真的可以跑（开关 ∧ 索引策略要求嵌入）。
         *
         * <p>⭐ <b>判据对齐源站</b> {@code knowledge_post_process.go:217}：
         * <pre>willSpawnQuestion = len(textChunks) &gt; 0 &amp;&amp; kb.NeedsEmbeddingModel() &amp;&amp; Enabled</pre>
         * 而 {@code NeedsEmbeddingModel()} = {@code IndexingStrategy.NeedsEmbedding()}
         * = <b>{@code VectorEnabled || KeywordEnabled}</b>（{@code indexing_strategy.go:34-36}）。
         *
         * <p>⛔ <b>不能判「embedding_model_id 非空」</b>：本仓曾这么写，2026-11-12 实测——
         * 一个 {@code vector_enabled/keyword_enabled} 全开、只是没配嵌入模型的知识库里
         * （「合同库测试」babed158），点「辅助召回问题」后端 18ms 返回
         * {@code SKIPPED / question_no_indexing}、一条都不生成；而按源站口径它<b>应该</b>生成
         * （问句写进 chunk metadata，索引由 FTS 兜底即可，索引层本就支持无嵌入模型）。
         *
         * <p>⚠️ 与同文件摘要那条（见 {@code resolve} 里 {@code true} 的注释）是同一类错误的两个实例：
         * <b>把「依赖就绪」写进派发判据 ⇒ 静默不执行，用户只看到「点了没反应」</b>。
         */
        public boolean runnable() {
            return enabled && indexingReady;
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

    /**
     * 图片理解配置（对应 {@code kb_knowledge_base.image_processing_config} JSON）。
     *
     * <p><b>⭐ 对齐源侧两个判据</b>：
     * <ul>
     *   <li>{@code enable_multimodal} 对应源侧 {@code VLMConfig.IsEnabled()}
     *       （{@code knowledgebase.go:733}：{@code Enabled && ModelID != ""}）。</li>
     *   <li>{@code model_id} 是该库指定的图片理解模型；源侧没有它时<b>回落到知识库默认</b>
     *       （{@code image_multimodal.go:941-975} 的 resolveVLM 先读库配置）。
     *       ⇒ 本仓空值 = 用全局多模态档位（{@code settings.multimodalModelName}），
     *       与 {@link KbVisionModelCaller} 的取值口径一致。</li>
     * </ul>
     *
     * @param enabled 是否开启图片理解（caption + OCR）
     * @param modelId 该库指定的视觉模型（用户裁定 2026-11-11：「可以选，默认使用全局默认」）。
     *                <b>空 = 跟随全局</b> {@code settings.multimodalModelName}；
     *                <b>有值 = 该库专属</b>，经 {@code IMAGE_UNDERSTAND} 任务 payload 透传到
     *                {@code KbImageEnrichService#enrichKnowledge(id, model)} → 视觉调用器。
     *                指定模型不可用时视觉调用器会 WARN 并回落全局（不让整篇图片全无描述）。
     */
    public record ImageUnderstanding(boolean enabled, String modelId) {
        /**
         * 是否应该派发图片理解任务。
         *
         * <p>⚠️ <b>不看模型是否已配置</b>：与 {@code KbVisionCaller#isAvailable()} 同一分层原则 ——
         * 没配模型时由执行期 fail-soft 跳过并留日志，而不是在这里静默不派发
         * （后者会让「开关开了但什么也没发生」无法解释）。
         */
        public boolean runnable() {
            return enabled;
        }
    }

    /** 全部关闭（单测 / 兜底用）。 */
    public static final KbPostProcessConfig NONE = new KbPostProcessConfig(
        false, "", new QuestionGeneration(false, "", DEFAULT_QUESTION_COUNT, "", false),
        new AutoTag(false, "", DEFAULT_MAX_TAGS, true), false, "", false,
        new ImageUnderstanding(false, ""));

    /**
     * 从知识库行解析。
     *
     * @param kb 知识库行（null ⇒ {@link #NONE}）
     * @return 解析结果（永不 null）
     */
    public static KbPostProcessConfig resolve(KbKnowledgeBaseRecord kb) {
        return resolve(kb, null);
    }

    /**
     * 从知识库行解析 · <b>可带全局默认模型</b>。
     *
     * <h2>⭐⭐ 为什么需要 fallbackChatModel（2026-11-11 用户裁定 A）</h2>
     * 设置面板「LLM 大语言模型」的占位文案写的是「跟随全局默认」，但此前后端**没有**这个回落：
     * 未选模型 ⇒ {@code summary_model_id = ""} ⇒ 下面这条链全部静默失效：
     * <ul>
     *   <li>图谱：{@code graphEnabled(kb) && !summaryModel.isEmpty()} 恒 false
     *       ⇒ {@code TYPE_GRAPH} 任务**根本不派发**，零日志零报错；</li>
     *   <li>摘要：执行期标 {@code failed} + {@code no_summary_model}；</li>
     *   <li>Wiki / AI 问题生成 / 自动打标：同一条 {@code summaryModel} 派生链一起失效。</li>
     * </ul>
     * ⇒ 传全局默认（{@code settings.main_model_name} 解析出的全名）进来，让那句文案名副其实。
     *
     * @param kb                  知识库行（null ⇒ {@link #NONE}）
     * @param fallbackChatModel   全局默认大模型（`provider/model` 全名或 name/id）；null/空 ⇒ 不回落
     * @return 解析结果（永不 null）
     */
    public static KbPostProcessConfig resolve(KbKnowledgeBaseRecord kb, String fallbackChatModel) {
        if (kb == null) {
            return NONE;
        }
        String summaryModel = trim(kb.getSummaryModelId());
        // ⭐ 空 ⇒ 用全局默认（用户裁定 A：「如果是空的话 使用全局默认」）
        if (summaryModel.isEmpty() && fallbackChatModel != null && !fallbackChatModel.isBlank()) {
            summaryModel = fallbackChatModel.trim();
            log.info("[后处理] 知识库未配置 LLM 模型 ⇒ 使用全局默认 model={} kb={}",
                summaryModel, kb.getId());
        }

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
            // ⭐ 摘要恒开（对齐源码 `ResolveProcessConfig:43` 的 `SummaryEnabled: true`）。
            //
            // ⛔ 这里**不能**加「有模型才派发」的判据 —— 本仓曾这么写过，是错的：
            //    源码 `knowledge_post_process.go:216` 的判据只有
            //    `eff.SummaryEnabled && len(textChunks) > 0`，**不看模型**；
            //    模型缺失的处理在**任务执行时**（`knowledge_process.go:1521-1527`）：
            //      `if kb.SummaryModelID == "" { 标 summary_status=failed; 记 no_summary_model; return }`
            //
            //    两种写法对用户完全不同：
            //    · 源码：没配模型 ⇒ 任务照派发 ⇒ 状态变 **failed** + 原因 `no_summary_model`
            //      ⇒ 前端能显示「未配置摘要模型」并给出「去设置里配」的入口。
            //    · 本仓旧写法：没配模型 ⇒ **根本不派发** ⇒ 状态永远停在 `none`
            //      ⇒ 界面永远「尚未生成」，且没有任何错误、查不到原因。
            //    后者的失效是**静默的**，用户只能猜。
            //    ⇒ 判据保持恒 true；模型缺失由 {@code KbSummaryService.generate} 的
            //      「守卫 5：配了摘要模型」在执行期兜住并落 failed 状态。
            true,
            summaryModel,
            new QuestionGeneration(qEnabled, qModel, qCount,
                trim(text(q, "custom_instructions")), questionIndexingReady(kb)),
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
            graphEnabled(kb) && !summaryModel.isEmpty(),
            imageUnderstanding(kb));
    }

    /**
     * 图片理解配置：{@code image_processing_config.enable_multimodal} + {@code model_id}。
     *
     * <p>⚠️ 这个字段（{@code kb_knowledge_base.image_processing_config}）在本批之前
     * <b>只有写入、没有读取</b>：DDL 有默认值、PATCH 能存、设置弹窗没入口、
     * 入库链也不看它 —— 用户开了什么都不会发生，且没有任何日志。
     * 本方法把它接进后处理派发判据。
     *
     * @param kb 知识库行
     * @return 配置（永不 null）
     */
    public static ImageUnderstanding imageUnderstanding(KbKnowledgeBaseRecord kb) {
        if (kb == null) {
            return new ImageUnderstanding(false, "");
        }
        JsonNode cfg = parse(kb.getImageProcessingConfig(), "image_processing_config");
        return new ImageUnderstanding(
            bool(cfg, "enable_multimodal", false),
            trim(text(cfg, "model_id")));
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

    /**
     * 问句生成的前提之一：索引策略是否「要求嵌入」。
     *
     * <p>⭐ 对齐源站 {@code NeedsEmbeddingModel()}（{@code knowledgebase.go:1021-1023}）
     * = {@code IndexingStrategy.NeedsEmbedding()}（{@code indexing_strategy.go:34-36}）
     * = <b>{@code VectorEnabled || KeywordEnabled}</b>。
     *
     * <p>⚠️ <b>不是</b>「{@code embedding_model_id} 非空」—— 那一条本仓曾用在
     * {@link QuestionGeneration#runnable()} 里，2026-11-12 实测导致
     * 「索引策略全开但没配嵌入模型」的知识库点「辅助召回问题」时静默跳过。
     *
     * <p>⚠️ fail-soft：配置缺失 / 坏 JSON ⇒ false（按「未配置」处理，与源站结构体零值同口径）。
     *
     * @param kb 知识库行
     * @return true = 向量索引或关键词索引至少开了一个
     */
    public static boolean questionIndexingReady(KbKnowledgeBaseRecord kb) {
        if (kb == null) {
            return false;
        }
        JsonNode strategy = parse(kb.getIndexingStrategy(), "indexing_strategy");
        return bool(strategy, "vector_enabled", false)
            || bool(strategy, "keyword_enabled", false);
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
