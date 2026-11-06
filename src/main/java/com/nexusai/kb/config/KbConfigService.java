package com.nexusai.kb.config;

import com.nexusai.domain.provider.ModelService;
import com.nexusai.kb.config.entity.KbConfigRecord;
import com.nexusai.kb.config.mapper.KbConfigMapper;
import com.nexusai.model.provider.dto.ModelDto;
import com.nexusai.model.provider.dto.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 知识库域配置读写（{@code kb_config}）· 批次 R0 · V273。
 *
 * <p><b>⭐ 这里是知识库配置的唯一真源</b>（用户 2026-11-05 裁定「单开一张 kb_config」）。
 * 之前这些值住在公共 {@code settings} 单行里，导致两个问题：
 * ① 多会话同时改 {@code settings} 会互相覆盖（W8 实际丢过一次字段）；
 * ② 知识库将来整块拆出去时，还要把公共表一起带走。
 *
 * <p><b>⭐ 2026-11-05 最终裁定（本条覆盖旧注释）</b>：settings 上的 8 个
 * {@code kb_*} 列<b>全部</b>搬到本表，两个模型列去前缀 ⇒
 * {@code embedding_model_id} / {@code rerank_model_id}。
 *
 * <p><b>⭐ 模型「自动选用」</b>：模型列留 NULL 不再等于「没有向量检索」——
 * 读路径会退回「同类型第一个启用的模型」（见 {@link #readEmbeddingModelId()}）。
 * 这样用户只配了一个 embedding 模型时，入库与问答立刻能走向量路，
 * 不需要先到某个设置页把「知识库模型」再选一遍。
 *
 * <p><b>⚠️ 写路径一律参数化 SQL</b>：本仓已反复踩过 Flex 的三个坑
 * （{@code insert} 把 null 列进 INSERT 使 DEFAULT 失效 / {@code UpdateChain.update()} 忽略 null
 * 使「清空」变成「什么都不做」/ {@code setRaw} 是字面量拼接）。这里的语义正好是
 * 「有条件地改某一列 + 有时要清空」，是那些坑的高发区。
 *
 * <p><b>⚠️ 三态语义</b>（与 SettingsService 的既有约定一致）：
 * 读方法返回 {@code null} = 未配置；写方法传 {@code null} = 不改，
 * 传空串 = 清空（回落默认/自动选用）。
 */
@Service
public class KbConfigService {

    private static final Logger log = LoggerFactory.getLogger(KbConfigService.class);

    private static final String SINGLETON_ID = KbConfigRecord.SINGLETON_ID;

    @Autowired private KbConfigMapper mapper;
    @Autowired private DataSource dataSource;

    /**
     * 模型目录（用于「未显式配置时自动选用」）。
     *
     * <p>⚠️ {@code required = false}：单测只验配置读写时不装配模型层；
     * 为 null 时自动选用直接返回 null（= 不重排/只走关键词），不是错误。
     */
    @Autowired(required = false)
    private ModelService modelService;

    // ── 读 ────────────────────────────────────────────────

    /**
     * 读整行（懒建：表里没行时补一行，保证后续 UPDATE 有效）。
     *
     * @return 配置行（不会为 null）
     */
    public KbConfigRecord read() {
        KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
        if (r != null) {
            return r;
        }
        KbConfigRecord fresh = new KbConfigRecord();
        fresh.setId(SINGLETON_ID);
        try {
            mapper.insert(fresh);
            return fresh;
        } catch (RuntimeException e) {
            // 并发首调撞主键 ⇒ 重读
            KbConfigRecord again = mapper.selectOneById(SINGLETON_ID);
            if (again != null) {
                return again;
            }
            throw e;
        }
    }

    /**
     * 全局默认检索驱动。
     *
     * @return 驱动类型；未配置返回 {@code null}（调用方回落 sqlite）
     */
    public String readRetrieverEngine() {
        try {
            KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
            return r == null ? null : blankToNull(r.getRetrieverEngine());
        } catch (RuntimeException e) {
            // fail-soft：读失败按「未配置」处理，让上层回落默认驱动。
            // ⛔ 不因一次 DB 抖动就认为用户清空了配置。
            log.warn("[kb_config] 读检索驱动失败，按未配置处理: {}", e.toString());
            return null;
        }
    }

    /**
     * 默认存储后端 ID。
     *
     * @return 后端 ID；未配置返回 {@code null}（调用方回落内置本地后端）
     */
    public String readDefaultStorageBackendId() {
        try {
            KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
            return r == null ? null : blankToNull(r.getDefaultStorageBackendId());
        } catch (RuntimeException e) {
            log.warn("[kb_config] 读默认存储后端失败，按未配置处理: {}", e.toString());
            return null;
        }
    }

    /**
     * 全局默认向量模型 ID —— <b>含自动选用</b>。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>{@code kb_config.embedding_model_id} 显式值</li>
     *   <li>都没有 ⇒ 取模型目录里<b>第一个启用的 {@code embedding} 模型</b>（按 id 排序，结果确定）</li>
     * </ol>
     *
     * <p><b>为什么要有第 2 步</b>：本仓「模型配置」页里配了 embedding 模型，
     * 但此前没人把它选成「知识库默认」⇒ 入库只建关键词索引、问答只走关键词，
     * <b>向量路一次都没跑过</b>，而且不报任何错。自动选用让「配了就能用」。
     *
     * @return 模型 ID；既未配置、也没有启用的 embedding 模型时返回 {@code null}
     *         （调用方按「只走关键词」处理，不是错误）
     */
    public String readEmbeddingModelId() {
        return readModelId(KbConfigRecord::getEmbeddingModelId, ModelType.embedding);
    }

    /**
     * 全局默认重排模型 ID —— <b>含自动选用</b>（语义同 {@link #readEmbeddingModelId()}）。
     *
     * @return 模型 ID；没有启用的 rerank 模型时返回 {@code null}（= 不重排）
     */
    public String readRerankModelId() {
        return readModelId(KbConfigRecord::getRerankModelId, ModelType.rerank);
    }

    /**
     * 读模型配置（原始值，<b>不含</b>自动选用的结果）。
     *
     * <p>供设置端点回显「用户到底配了什么」—— 若这里也返回自动选用的值，
     * 用户会以为自己配过，之后模型被禁用时行为变化却找不到原因。
     *
     * @return 两个字段都可能为 {@code null}（= 未显式配置）
     */
    public ModelConfig readModelConfig() {
        try {
            KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
            return r == null
                ? new ModelConfig(null, null)
                : new ModelConfig(blankToNull(r.getEmbeddingModelId()),
                                  blankToNull(r.getRerankModelId()));
        } catch (RuntimeException e) {
            log.warn("[kb_config] 读模型配置失败: {}", e.toString());
            return new ModelConfig(null, null);
        }
    }

    /**
     * 读指定类型的模型配置，未显式配置时自动选用。
     *
     * @param getter 从配置行取显式值
     * @param type   模型类型（用于自动选用）
     */
    private String readModelId(java.util.function.Function<KbConfigRecord, String> getter, ModelType type) {
        String explicit = null;
        try {
            KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
            explicit = r == null ? null : blankToNull(getter.apply(r));
        } catch (RuntimeException e) {
            // fail-soft：读配置失败不该让整轮检索/入库失败，降级到自动选用。
            log.warn("[kb_config] 读 {} 模型配置失败，尝试自动选用: {}", type, e.toString());
        }
        if (explicit != null) {
            return explicit;
        }
        return autoPick(type);
    }

    /**
     * 自动选用：同类型、已启用、按 id 排序的第一个模型。
     *
     * <p>⚠️ 按 id 排序是为了<b>确定性</b> —— {@code selectAll()} 没有 ORDER BY，
     * 不加排序时同一个库重启后可能选到别的模型，向量空间随之变化，
     * 表现为「同一篇文档今天搜得到、明天搜不到」。
     */
    private String autoPick(ModelType type) {
        if (modelService == null) {
            return null;
        }
        try {
            List<ModelDto> all = modelService.listAll();
            if (all == null || all.isEmpty()) {
                return null;
            }
            java.util.Optional<String> picked = all.stream()
                .filter(Objects::nonNull)
                .filter(ModelDto::enabled)
                .filter(m -> m.type() == type)
                .sorted(Comparator.comparing(ModelDto::id))
                .map(ModelDto::id)
                .findFirst();
            if (picked.isPresent()) {
                log.info("[kb_config] 未显式配置 {} 模型，自动选用 {}", type, picked.get());
            }
            return picked.orElse(null);
        } catch (RuntimeException e) {
            log.warn("[kb_config] 自动选用 {} 模型失败: {}", type, e.toString());
            return null;
        }
    }

    /**
     * 长期记忆工作区开关。
     *
     * <p>⚠️ <b>未配置默认 true</b>：记忆开箱可用，但写入模式默认
     * {@code explicit_only}（不调模型、不花钱），要自动蒸馏才需显式打开。
     *
     * @return 是否开启
     */
    public boolean readMemoryEnabled() {
        try {
            KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
            if (r == null || r.getMemoryEnabled() == null) {
                return true;
            }
            return r.getMemoryEnabled() != 0;
        } catch (RuntimeException e) {
            log.warn("[kb_config] 读记忆开关失败，按关闭处理: {}", e.toString());
            return false;
        }
    }

    /**
     * 记忆写入模式。
     *
     * @return {@code explicit_only} / {@code auto}；未配置返回 {@code null}（调用方回落 explicit_only）
     */
    public String readMemoryWriteMode() {
        try {
            KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
            return r == null ? null : blankToNull(r.getMemoryWriteMode());
        } catch (RuntimeException e) {
            log.warn("[kb_config] 读记忆写入模式失败: {}", e.toString());
            return null;
        }
    }

    /** 记忆容量上限；{@code null} = 用默认 200。 */
    public Integer readMemoryMaxItems() {
        try {
            KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
            return r == null ? null : r.getMemoryMaxItems();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 兴趣提升阈值；{@code null} = 用默认 3。 */
    public Integer readMemoryInterestThreshold() {
        try {
            KbConfigRecord r = mapper.selectOneById(SINGLETON_ID);
            return r == null ? null : r.getMemoryInterestThreshold();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── 写 ────────────────────────────────────────────────

    /**
     * 写全局默认检索驱动。
     *
     * @param engineType {@code null} = 不改；{@code ""} = 清空（回落默认）；其它 = 覆盖
     */
    public void updateRetrieverEngine(String engineType) {
        if (engineType == null) {
            return;
        }
        read();  // 确保行存在
        exec("UPDATE kb_config SET retriever_engine = ?, update_time = datetime('now') WHERE id = ?",
            blankToNull(engineType), SINGLETON_ID);
        log.info("[kb_config] 检索驱动已更新: {}", blankToNull(engineType));
    }

    /**
     * 写默认存储后端。
     *
     * @param backendId {@code null} = 不改；{@code ""} = 清空（回落内置本地）；其它 = 覆盖
     */
    public void updateDefaultStorageBackendId(String backendId) {
        if (backendId == null) {
            return;
        }
        read();
        exec("UPDATE kb_config SET default_storage_backend_id = ?, update_time = datetime('now') WHERE id = ?",
            blankToNull(backendId), SINGLETON_ID);
        log.info("[kb_config] 默认存储后端已更新: {}", blankToNull(backendId));
    }

    /**
     * 写记忆配置（四字段一起，{@code null} = 不改）。
     *
     * <p>⚠️ 用 {@code CASE WHEN ? THEN ? ELSE 列 END} 表达「有值才更新」：
     * Flex 的 {@code UpdateChain.update()} 会把 null 当成「不更新」，
     * 那样「关闭记忆」这类写 0 的操作虽然能过，但「清空写入模式」会静默失败。
     * 这里一次 SQL 解决，且全是参数绑定（无字面量拼接）。
     */
    public void updateMemoryConfig(Boolean enabled, String writeMode,
                                   Integer maxItems, Integer interestThreshold) {
        if (enabled == null && writeMode == null && maxItems == null && interestThreshold == null) {
            return;
        }
        read();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE kb_config SET "
                     + "memory_enabled = CASE WHEN ? THEN ? ELSE memory_enabled END, "
                     + "memory_write_mode = CASE WHEN ? THEN ? ELSE memory_write_mode END, "
                     + "memory_max_items = CASE WHEN ? THEN ? ELSE memory_max_items END, "
                     + "memory_interest_threshold = CASE WHEN ? THEN ? ELSE memory_interest_threshold END, "
                     + "update_time = datetime('now') "
                     + "WHERE id = ?")) {
            ps.setBoolean(1, enabled != null);
            ps.setObject(2, enabled == null ? null : (enabled ? 1 : 0));
            ps.setBoolean(3, writeMode != null);
            ps.setString(4, blankToNull(writeMode));
            ps.setBoolean(5, maxItems != null);
            ps.setObject(6, maxItems);
            ps.setBoolean(7, interestThreshold != null);
            ps.setObject(8, interestThreshold);
            ps.setString(9, SINGLETON_ID);
            ps.executeUpdate();
            log.info("[kb_config] 记忆配置已更新: enabled={} mode={} max={} threshold={}",
                enabled, writeMode, maxItems, interestThreshold);
        } catch (Exception e) {
            log.warn("[kb_config] 写记忆配置失败: {}", e.toString());
            throw new IllegalStateException("保存记忆配置失败：" + e.getMessage(), e);
        }
    }

    /**
     * 写模型全局默认。
     *
     * <p>⚠️ 三态（与 {@link #updateMemoryConfig} 同款）：{@code null} = 不改，
     * {@code ""} = 清空（回到「自动选用」），其它 = 设为该模型 ID。
     *
     * <p>用 {@code CASE WHEN ? THEN ? ELSE 列 END} 而不是 Flex 的
     * {@code UpdateChain.update()}：后者把 null 当「不更新」，
     * 于是「清空」会静默失败 —— 这正是本项要修的问题之一。
     */
    public void updateModelConfig(String embeddingModelId, String rerankModelId) {
        if (embeddingModelId == null && rerankModelId == null) {
            return;
        }
        read();  // 确保行存在
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE kb_config SET "
                     + "embedding_model_id = CASE WHEN ? THEN ? ELSE embedding_model_id END, "
                     + "rerank_model_id = CASE WHEN ? THEN ? ELSE rerank_model_id END, "
                     + "update_time = datetime('now') "
                     + "WHERE id = ?")) {
            ps.setBoolean(1, embeddingModelId != null);
            ps.setString(2, blankToNull(embeddingModelId));
            ps.setBoolean(3, rerankModelId != null);
            ps.setString(4, blankToNull(rerankModelId));
            ps.setString(5, SINGLETON_ID);
            ps.executeUpdate();
            log.info("[kb_config] 模型配置已更新: embedding={} rerank={}",
                blankToNull(embeddingModelId), blankToNull(rerankModelId));
        } catch (Exception e) {
            log.warn("[kb_config] 写模型配置失败: {}", e.toString());
            throw new IllegalStateException("保存知识库模型配置失败：" + e.getMessage(), e);
        }
    }

    /**
     * 知识库模型全局默认（原始值 · 供端点在回显时区分「显式配置」与「自动选用」）。
     *
     * @param embeddingModelId 向量模型 ID；{@code null} = 未显式配置
     * @param rerankModelId    重排模型 ID；{@code null} = 未显式配置
     */
    public record ModelConfig(String embeddingModelId, String rerankModelId) {
    }

    // ── 问答链参数（批次 QA-1 · V277）──────────────────────────────

    /** 默认值单点（源侧 {@code config/config.yaml:9-22}）。 */
    static final int DEF_EMBEDDING_TOP_K = 30;
    static final double DEF_VECTOR_THRESHOLD = 0.2;
    static final double DEF_KEYWORD_THRESHOLD = 0.3;
    static final double DEF_RERANK_THRESHOLD = 0.3;
    static final int DEF_RERANK_TOP_K = 30;

    /**
     * 最终进上下文的段数上限。
     *
     * <p>⚠️ <b>本仓新增，源侧没有这一项</b>（源侧 rerank_top_k=30 直接全进上下文）。
     * 理由：单段上限 2000 码点 × 30 段 = 6 万字符，会把上下文预算打满并挤掉对话历史。
     * 要与源侧完全一致 ⇒ 把它配成与 rerank_top_k 相同的值。
     */
    static final int DEF_CONTEXT_MAX_CHUNKS = 12;
    static final int DEF_MAX_ROUNDS = 5;
    static final double DEF_FAQ_SCORE_BOOST = 1.0;
    static final double DEF_FAQ_DIRECT_ANSWER_THRESHOLD = 0.9;
    static final int DEF_WEB_SEARCH_MAX_RESULTS = 5;
    static final int DEF_WEB_FETCH_TOP_N = 3;

    /**
     * 问答链参数快照 —— <b>默认值只在这里出现一次</b>。
     *
     * <p><b>WHY 要有这个 record</b>：18 个参数若各调用点自己写「null ⇒ 30」，
     * 迟早出现「检索阶段当 30、合并阶段当 12」的分叉，且改默认值要全库找。
     * 所有阶段只认本对象 ⇒ 默认值改一行，全链一致。
     *
     * <p>⚠️ {@code maxRounds == 0} 是<b>有意义的配置</b>（显式关闭多轮），
     * 与「未配置」不同 ⇒ 本 record 里它恒为已解析值，调用方不要再拿 null 判。
     */
    public record QaParams(
        int embeddingTopK, double vectorThreshold, double keywordThreshold,
        double rerankThreshold, int rerankTopK, int contextMaxChunks,
        int maxRounds, boolean enableRewrite, boolean enableQueryExpansion,
        boolean faqPriorityEnabled, double faqScoreBoost, double faqDirectAnswerThreshold,
        int webSearchMaxResults, boolean webFetchEnabled, int webFetchTopN,
        boolean dataAnalysisEnabled, String contextTemplate, String intentPrompts,
        boolean kbToolsEnabled
    ) {
    }

    /**
     * 读问答链参数（未配置项一律回落默认）。
     *
     * <p>读失败（表不存在 / 迁移未跑）⇒ 全默认 + WARN，⛔ 不抛：
     * 问答链是主路径，配置读不出来不该让整轮失败。
     *
     * @return 已解析参数（不会为 null）
     */
    public QaParams readQaParams() {
        KbConfigRecord r;
        try {
            r = read();
        } catch (RuntimeException e) {
            log.warn("[kb_config] 读问答参数失败，全部使用默认值: {}", e.getMessage());
            r = null;
        }
        return new QaParams(
            intOr(r == null ? null : r.getEmbeddingTopK(), DEF_EMBEDDING_TOP_K),
            dblOr(r == null ? null : r.getVectorThreshold(), DEF_VECTOR_THRESHOLD),
            dblOr(r == null ? null : r.getKeywordThreshold(), DEF_KEYWORD_THRESHOLD),
            dblOr(r == null ? null : r.getRerankThreshold(), DEF_RERANK_THRESHOLD),
            intOr(r == null ? null : r.getRerankTopK(), DEF_RERANK_TOP_K),
            intOr(r == null ? null : r.getContextMaxChunks(), DEF_CONTEXT_MAX_CHUNKS),
            // ⚠️ maxRounds 的 0 有意义 ⇒ 只有 null 才回落默认
            intOr(r == null ? null : r.getMaxRounds(), DEF_MAX_ROUNDS),
            boolOr(r == null ? null : r.getEnableRewrite(), true),
            boolOr(r == null ? null : r.getEnableQueryExpansion(), true),
            boolOr(r == null ? null : r.getFaqPriorityEnabled(), false),
            dblOr(r == null ? null : r.getFaqScoreBoost(), DEF_FAQ_SCORE_BOOST),
            dblOr(r == null ? null : r.getFaqDirectAnswerThreshold(), DEF_FAQ_DIRECT_ANSWER_THRESHOLD),
            intOr(r == null ? null : r.getWebSearchMaxResults(), DEF_WEB_SEARCH_MAX_RESULTS),
            boolOr(r == null ? null : r.getWebFetchEnabled(), false),
            intOr(r == null ? null : r.getWebFetchTopN(), DEF_WEB_FETCH_TOP_N),
            boolOr(r == null ? null : r.getDataAnalysisEnabled(), false),
            r == null ? null : r.getContextTemplate(),
            r == null ? null : r.getIntentPrompts(),
            boolOr(r == null ? null : r.getKbToolsEnabled(), false));
    }

    /**
     * 写问答链参数（每个参数 {@code null} = 不改）。
     *
     * <p>⚠️ 与 {@link #updateMemoryConfig} 同款三态：{@code CASE WHEN ? THEN ? ELSE 列 END}。
     * ⛔ 不用 Flex 的 {@code UpdateChain.update()}（null 当「不更新」⇒ 清空静默失败）。
     *
     * <p>⚠️ 本方法**不校验**数值范围（阈值 0~1 等）—— 那是端点 DTO 的职责，
     * 服务层只负责准确保存调用方给的值。
     */
    public void updateQaParams(QaParamsUpdate u) {
        if (u == null || u.isEmpty()) {
            return;
        }
        read();  // 确保行存在
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE kb_config SET "
                     + "embedding_top_k = CASE WHEN ? THEN ? ELSE embedding_top_k END, "
                     + "vector_threshold = CASE WHEN ? THEN ? ELSE vector_threshold END, "
                     + "keyword_threshold = CASE WHEN ? THEN ? ELSE keyword_threshold END, "
                     + "rerank_threshold = CASE WHEN ? THEN ? ELSE rerank_threshold END, "
                     + "rerank_top_k = CASE WHEN ? THEN ? ELSE rerank_top_k END, "
                     + "context_max_chunks = CASE WHEN ? THEN ? ELSE context_max_chunks END, "
                     + "max_rounds = CASE WHEN ? THEN ? ELSE max_rounds END, "
                     + "enable_rewrite = CASE WHEN ? THEN ? ELSE enable_rewrite END, "
                     + "enable_query_expansion = CASE WHEN ? THEN ? ELSE enable_query_expansion END, "
                     + "faq_priority_enabled = CASE WHEN ? THEN ? ELSE faq_priority_enabled END, "
                     + "faq_score_boost = CASE WHEN ? THEN ? ELSE faq_score_boost END, "
                     + "faq_direct_answer_threshold = CASE WHEN ? THEN ? ELSE faq_direct_answer_threshold END, "
                     + "web_search_max_results = CASE WHEN ? THEN ? ELSE web_search_max_results END, "
                     + "web_fetch_enabled = CASE WHEN ? THEN ? ELSE web_fetch_enabled END, "
                     + "web_fetch_top_n = CASE WHEN ? THEN ? ELSE web_fetch_top_n END, "
                     + "data_analysis_enabled = CASE WHEN ? THEN ? ELSE data_analysis_enabled END, "
                     + "context_template = CASE WHEN ? THEN ? ELSE context_template END, "
                     + "intent_prompts = CASE WHEN ? THEN ? ELSE intent_prompts END, "
                     + "kb_tools_enabled = CASE WHEN ? THEN ? ELSE kb_tools_enabled END, "
                     + "update_time = datetime('now') "
                     + "WHERE id = ?")) {
            int i = 1;
            i = bind(ps, i, u.embeddingTopK());
            i = bind(ps, i, u.vectorThreshold());
            i = bind(ps, i, u.keywordThreshold());
            i = bind(ps, i, u.rerankThreshold());
            i = bind(ps, i, u.rerankTopK());
            i = bind(ps, i, u.contextMaxChunks());
            i = bind(ps, i, u.maxRounds());
            i = bind(ps, i, u.enableRewrite() == null ? null : (u.enableRewrite() ? 1 : 0));
            i = bind(ps, i, u.enableQueryExpansion() == null ? null : (u.enableQueryExpansion() ? 1 : 0));
            i = bind(ps, i, u.faqPriorityEnabled() == null ? null : (u.faqPriorityEnabled() ? 1 : 0));
            i = bind(ps, i, u.faqScoreBoost());
            i = bind(ps, i, u.faqDirectAnswerThreshold());
            i = bind(ps, i, u.webSearchMaxResults());
            i = bind(ps, i, u.webFetchEnabled() == null ? null : (u.webFetchEnabled() ? 1 : 0));
            i = bind(ps, i, u.webFetchTopN());
            i = bind(ps, i, u.dataAnalysisEnabled() == null ? null : (u.dataAnalysisEnabled() ? 1 : 0));
            i = bind(ps, i, u.contextTemplate() == null ? null : blankToNull(u.contextTemplate()));
            i = bind(ps, i, u.intentPrompts() == null ? null : blankToNull(u.intentPrompts()));
            i = bind(ps, i, u.kbToolsEnabled() == null ? null : (u.kbToolsEnabled() ? 1 : 0));
            ps.setString(i, SINGLETON_ID);
            ps.executeUpdate();
            log.info("[kb_config] 问答参数已更新");
        } catch (Exception e) {
            log.warn("[kb_config] 写问答参数失败: {}", e.toString());
            throw new IllegalStateException("保存问答参数失败：" + e.getMessage(), e);
        }
    }

    /**
     * 写参数载荷：每个字段 {@code null} = 不改（与 {@link #readQaParams()} 的已解析快照不同）。
     */
    public record QaParamsUpdate(
        Integer embeddingTopK, Double vectorThreshold, Double keywordThreshold,
        Double rerankThreshold, Integer rerankTopK, Integer contextMaxChunks, Integer maxRounds,
        Boolean enableRewrite, Boolean enableQueryExpansion,
        Boolean faqPriorityEnabled, Double faqScoreBoost, Double faqDirectAnswerThreshold,
        Integer webSearchMaxResults, Boolean webFetchEnabled, Integer webFetchTopN,
        Boolean dataAnalysisEnabled, String contextTemplate, String intentPrompts,
        Boolean kbToolsEnabled
    ) {
        /** 全空 ⇒ 无需写库。 */
        public boolean isEmpty() {
            return embeddingTopK == null && vectorThreshold == null && keywordThreshold == null
                && rerankThreshold == null && rerankTopK == null && contextMaxChunks == null
                && maxRounds == null
                && enableRewrite == null && enableQueryExpansion == null
                && faqPriorityEnabled == null && faqScoreBoost == null
                && faqDirectAnswerThreshold == null && webSearchMaxResults == null
                && webFetchEnabled == null && webFetchTopN == null && dataAnalysisEnabled == null
                && contextTemplate == null && intentPrompts == null && kbToolsEnabled == null;
        }
    }

    /** 绑定一个「有值才更新」的三态参数（{@code CASE WHEN ? THEN ? ELSE 列 END}）。 */
    private static int bind(PreparedStatement ps, int idx, Object value) throws Exception {
        ps.setBoolean(idx, value != null);
        ps.setObject(idx + 1, value);
        return idx + 2;
    }

    private static int intOr(Integer v, int def) {
        return v == null ? def : v;
    }

    private static double dblOr(Double v, double def) {
        return v == null ? def : v;
    }

    private static boolean boolOr(Integer v, boolean def) {
        return v == null ? def : v != 0;
    }

    // ── 内部 ──────────────────────────────────────────────

    private void exec(String sql, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i] == null ? null : String.valueOf(args[i]));
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写 kb_config 失败：" + e.getMessage(), e);
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
