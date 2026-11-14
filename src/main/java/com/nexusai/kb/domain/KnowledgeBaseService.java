package com.nexusai.kb.domain;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.CreateKnowledgeBaseRequest;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import com.nexusai.kb.dto.UpdateKnowledgeBaseRequest;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库领域服务 · 批次 B1a（知识库本体 CRUD）。
 *
 * <p><b>源侧对照</b>：WeKnora {@code internal/application/service/knowledgebase*.go}。
 * 本批只实现 CRUD 主路径；复制 / 重建索引 / 画像生成等依赖后续批次的表。
 *
 * <p><b>错误契约</b>（与 NexusAI 现有 Controller 一致，{@code GlobalExceptionHandler} 转 RFC 7807）：
 * <ul>
 *   <li>{@link ValidationException} → 400（业务校验失败，非 Bean Validation）</li>
 *   <li>{@link NotFoundException} → 404</li>
 * </ul>
 *
 * <p><b>类型白名单</b>：源侧 {@code type} 只有 {@code document | faq | wiki} 三值
 * （{@code knowledgebase.go:65 enums:"document,faq,wiki"}，空 = document）。
 * 本服务在入口处校验 —— ⛔ 不是无限定字符串。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** 合法知识库类型（源侧 enums:"document,faq,wiki"）。 */
    private static final Set<String> ALLOWED_TYPES = Set.of("document", "faq", "wiki");

    /** 类型未指定时的默认值（源侧 gorm default:'document'）。 */
    private static final String DEFAULT_TYPE = "document";

    /**
     * 分块配置默认值 · 逐字等于源侧 {@code 000000_init.up.sql} 的 DEFAULT 表达式。
     *
     * <p>⚠️ 必须在 Service 显式写入，不能只依赖 DB 默认值：
     * MyBatis-Flex 的 insert 会**把 null 字段也列进 INSERT 列清单**（实测：报
     * {@code NOT NULL constraint failed: kb_knowledge_base.chunking_config}），
     * DB 的 {@code DEFAULT} 只在**整列缺省**时生效 ⇒ null 显式写入会顶掉默认值。
     */
    static final String DEFAULT_CHUNKING_CONFIG =
        "{\"chunk_size\": 512, \"chunk_overlap\": 50, "
            + "\"split_markers\": [\"\\n\\n\", \"\\n\", \"。\"], \"keep_separator\": true}";

    /** 图片处理配置默认值 · 逐字等于源侧 DEFAULT。 */
    static final String DEFAULT_IMAGE_PROCESSING_CONFIG =
        "{\"enable_multimodal\": false, \"model_id\": \"\"}";

    /** 对象存储配置默认值 · 源侧 {@code cos_config} DEFAULT '{}'。 */
    static final String DEFAULT_COS_CONFIG = "{}";

    /** VLM 配置默认值 · 源侧 {@code vlm_config} DEFAULT '{}'。 */
    static final String DEFAULT_VLM_CONFIG = "{}";

    /**
     * 图谱存储配置默认值（V281）· {@code '{}'} = 用本地 SQLite 两张表。
     *
     * <p>⭐ 2026-11-11 用户要求「兼容 Neo4j，可配远程实例」：配了
     * {@code {"store":"neo4j",...}} 该库就写远程，否则本地。每库一份，所以放在这一行。
     */
    static final String DEFAULT_GRAPH_STORE_CONFIG = "{}";

    private final KbKnowledgeBaseMapper mapper;

    @Autowired
    public KnowledgeBaseService(KbKnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 检索驱动注册表（W1 · V268）。
     *
     * <p>⚠️ 用字段注入而不改构造器签名：本类有手工 {@code new} 的测试点，
     * 改签名会连锁改所有调用方；且这是「配置校验」这类旁路能力，
     * 没有它业务照常跑（只是少了提前校验）。
     */
    @Autowired(required = false)
    private com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry engineRegistry;

    /**
     * 知识库画像自动生成触发器（2026-11-11）。
     *
     * <p>⚠️ 字段注入而非构造器：本类有手工 {@code new} 的测试点，改构造器签名会连锁改所有调用方。
     */
    @Autowired(required = false)
    private com.nexusai.kb.app.profile.KbProfileTrigger profileTrigger;

    /**
     * 列表 · 按「置顶优先 + 创建时间倒序」。
     *
     * <p>⛔ 不返回假数据：源侧 {@code chunk_count} / {@code knowledge_count} 等统计字段
     * 依赖 B2 的文档表，本批不返回（DTO 里就没有这些字段）。
     *
     * @return 知识库列表（无数据时返回空列表，不返回 null）
     */
    public List<KnowledgeBaseDto> list() {
        List<KbKnowledgeBaseRecord> rows = mapper.selectListByQuery(
            QueryWrapper.create()
                .orderBy("is_pinned", false)   // 置顶在前
                .orderBy("create_time", false) // 新的在前
                .orderBy("id", true));         // 稳定排序兜底
        List<KnowledgeBaseDto> result = new ArrayList<>(rows.size());
        for (KbKnowledgeBaseRecord row : rows) {
            result.add(toDto(row));
        }
        if (log.isDebugEnabled()) {
            log.debug("[知识库] 列表查询完成，返回 {} 条", result.size());
        }
        return result;
    }

    /**
     * 单条查询。
     *
     * @param id 知识库 ID
     * @return 知识库
     * @throws NotFoundException 不存在（或被逻辑删除 —— Flex 自动过滤）
     */
    public KnowledgeBaseDto getById(String id) {
        KbKnowledgeBaseRecord row = requireRow(id);
        return toDto(row);
    }

    /**
     * 创建。
     *
     * <p>源侧必填只有 {@code name}；{@code embedding_model_id} / {@code summary_model_id}
     * 在 DB 上是 NOT NULL，但源侧允许留空（"A field you leave out keeps its default."）
     * ⇒ 留空时写入空串（⛔ 不臆造模型 ID）。
     *
     * @param req 创建请求
     * @return 创建后的知识库
     * @throws ValidationException 名称空白 / 类型非法
     */
    @Transactional
    public KnowledgeBaseDto create(CreateKnowledgeBaseRequest req) {
        String name = trimToNull(req.name());
        if (name == null) {
            throw new ValidationException("知识库名不能为空");
        }
        String type = normalizeType(req.type());

        KbKnowledgeBaseRecord row = new KbKnowledgeBaseRecord();
        row.setId(UUID.randomUUID().toString());
        row.setName(name);
        row.setDescription(req.description());
        row.setType(type);
        // 源侧默认值：非临时库、未置顶
        row.setIsTemporary(0);
        row.setIsPinned(0);
        // ⚠️ Flex 的 insert 会把 null 字段也写进 INSERT 列清单 ⇒ DB DEFAULT 不生效
        //    （实测 NOT NULL constraint failed）⇒ 默认值必须在 Service 显式填。
        row.setChunkingConfig(
            isBlank(req.chunkingConfig()) ? DEFAULT_CHUNKING_CONFIG : req.chunkingConfig());
        // 图片处理配置同样有 DB 默认值 ⇒ 留空时必须显式填默认（Flex 会把 null 写进 INSERT）。
        row.setImageProcessingConfig(
            isBlank(req.imageProcessingConfig())
                ? DEFAULT_IMAGE_PROCESSING_CONFIG : req.imageProcessingConfig().trim());
        // ⭐ 本次开放的其余 8 项配置（源侧创建时同一个请求就带上）：
        //    留空 = 不设（JSON 配置落 NULL，下游按「未配置」处理；两个 ID 落 NULL = 不绑）。
        setIfPresent(req.wikiConfig(), row::setWikiConfig);
        setIfPresent(req.asrConfig(), row::setAsrConfig);
        setIfPresent(req.questionGenerationConfig(), row::setQuestionGenerationConfig);
        setIfPresent(req.autoTagConfig(), row::setAutoTagConfig);
        setIfPresent(req.profileConfig(), row::setProfileConfig);
        setIfPresent(req.faqConfig(), row::setFaqConfig);
        setIfPresent(req.vectorStoreId(), row::setVectorStoreId);
        setIfPresent(req.storageBackendId(), row::setStorageBackendId);
        row.setCosConfig(DEFAULT_COS_CONFIG);
        row.setVlmConfig(DEFAULT_VLM_CONFIG);
        // ⚠️ 必须显式填：Flex 会把 null 也列进 INSERT ⇒ DB 的 DEFAULT '{}' 不生效，
        //    直接报 NOT NULL constraint failed（本类第 3 次踩这个坑，见上面 chunking_config 的注释）。
        row.setGraphStoreConfig(DEFAULT_GRAPH_STORE_CONFIG);
        // ⭐ 索引策略（RAG / Wiki / 图谱的开关）—— 源侧创建时同一个请求就带上它
        //    （KnowledgeBaseEditorModal.vue:1690）。bounds = 只接受 JSON 对象，
        //    其它形态当作「未配置」（null），⛔ 不要静默塞一个空串让下游 parse 成脏数据。
        if (req.indexingStrategy() != null && !req.indexingStrategy().isBlank()) {
            String strategy = req.indexingStrategy().trim();
            if (!strategy.startsWith("{")) {
                throw new ValidationException("indexing_strategy 必须是 JSON 对象");
            }
            row.setIndexingStrategy(strategy);
        }
        // ⭐ 抽取配置（extract_config）—— 图谱判据是 graph_enabled ∧ extract_config.enabled
        //    两字段合取（KbPostProcessConfig.graphEnabled）⇒ 创建时只写前一个等于没开。
        //    规则与上面的 indexing_strategy 完全一致：空白 ⇒ 不设（保持 DB 默认），
        //    非 JSON 对象 ⇒ 400（⛔ 不静默塞脏串让下游 parse 成「未配置」）。
        if (req.extractConfig() != null && !req.extractConfig().isBlank()) {
            String extract = req.extractConfig().trim();
            if (!extract.startsWith("{")) {
                throw new ValidationException("extract_config 必须是 JSON 对象");
            }
            row.setExtractConfig(extract);
        }
        // ⭐ V286 图片管线三项：null = 用默认（开关 true；管线 default）。
        //    ⚠️ 必须显式填：Flex 会把 null 也列进 INSERT ⇒ DB 的 DEFAULT 不生效，
        //    直接报 NOT NULL constraint failed（与 chunking_config 同一个坑）。
        row.setImageCaptionEnabled(onOffOrDefault(req.imageCaptionEnabled()));
        row.setImageOcrEnabled(onOffOrDefault(req.imageOcrEnabled()));
        row.setImagePipeline(requireImagePipeline(req.imagePipeline()));
        // DB 列 NOT NULL 且无默认值 ⇒ 留空时写空串
        row.setEmbeddingModelId(req.embeddingModelId() == null ? "" : req.embeddingModelId());
        row.setSummaryModelId(req.summaryModelId() == null ? "" : req.summaryModelId());

        mapper.insert(row);
        log.info("[知识库] 已创建 id={} name={} type={}", row.getId(), row.getName(), row.getType());

        // 回读：拿 DB 生成的 create_time / update_time 与默认 JSON
        return toDto(requireRow(row.getId()));
    }

    /**
     * 更新 · 局部更新语义（null = 不改这一项）。
     *
     * @param id  知识库 ID
     * @param req 更新请求
     * @return 更新后的知识库
     * @throws NotFoundException 不存在
     * @throws ValidationException 名称传了但为空
     */
    @Transactional
    public KnowledgeBaseDto update(String id, UpdateKnowledgeBaseRequest req) {
        KbKnowledgeBaseRecord row = requireRow(id);

        if (req.name() != null) {
            String name = trimToNull(req.name());
            if (name == null) {
                throw new ValidationException("知识库名不能为空");
            }
            row.setName(name);
        }
        if (req.description() != null) {
            row.setDescription(req.description());
        }
        if (req.chunkingConfig() != null) {
            row.setChunkingConfig(req.chunkingConfig());
        }
        if (req.embeddingModelId() != null) {
            // ⚠️ 源侧约束「有文档后不可改 embedding_model_id」——B2 落地文档表后必须在此加校验
            row.setEmbeddingModelId(req.embeddingModelId());
        }
        if (req.summaryModelId() != null) {
            row.setSummaryModelId(req.summaryModelId());
        }
        // ⭐ V286 图片管线三项：null = 不改（PUT 的局部更新语义）。
        if (req.imageCaptionEnabled() != null) {
            row.setImageCaptionEnabled(req.imageCaptionEnabled() ? 1 : 0);
        }
        if (req.imageOcrEnabled() != null) {
            row.setImageOcrEnabled(req.imageOcrEnabled() ? 1 : 0);
        }
        if (req.imagePipeline() != null) {
            // ⚠️ 该列 NOT NULL 且是枚举 ⇒ 空串归默认（"清空" 无意义），非法值 400。
            row.setImagePipeline(requireImagePipeline(req.imagePipeline()));
        }
        // ⭐ 本次开放的 9 项配置：null = 不改；"" = 清空（两列及 JSON 列均可空）。
        setIfNotNull(req.wikiConfig(), row::setWikiConfig);
        setIfNotNull(req.imageProcessingConfig(), row::setImageProcessingConfig);
        setIfNotNull(req.asrConfig(), row::setAsrConfig);
        setIfNotNull(req.questionGenerationConfig(), row::setQuestionGenerationConfig);
        setIfNotNull(req.autoTagConfig(), row::setAutoTagConfig);
        setIfNotNull(req.profileConfig(), row::setProfileConfig);
        setIfNotNull(req.faqConfig(), row::setFaqConfig);
        setIfNotNull(req.vectorStoreId(), row::setVectorStoreId);
        setIfNotNull(req.storageBackendId(), row::setStorageBackendId);
        if (req.retrieverEngine() != null) {
            // 三态：null=不改；""=清空（回落全局默认）；值=覆盖。
            String engine = trimToNull(req.retrieverEngine());
            if (engine != null && engineRegistry != null) {
                // ⚠️ 提前校验：保存一个未接入的驱动会让「上传并索引」白做，
                //    直到检索时才失败。这里就挡住。
                try {
                    engineRegistry.require(engine);
                } catch (IllegalStateException e) {
                    throw new ValidationException(e.getMessage());
                }
            }
            row.setRetrieverEngine(engine);
        }

        // updateTime 由 KbAuditUpdateListener 自动刷新。
        //
        // ⚠️ 必须用 `update(row, false)`（显式写 NULL）—— 默认的 `update(row)` 会**忽略 null 字段**，
        //    于是「传 '' 清空 retriever_engine」会变成「什么都不做」（静默无效，实测复现）。
        //    row 是从库里读出的完整实体，把其余字段原样写回无副作用。
        boolean clearingRetrieverEngine =
            req.retrieverEngine() != null && trimToNull(req.retrieverEngine()) == null;
        mapper.update(row, !clearingRetrieverEngine);
        log.info("[知识库] 已更新 id={} name={} retrieverEngine={}{}", id, row.getName(),
            row.getRetrieverEngine(), clearingRetrieverEngine ? "（已清空）" : "");
        return toDto(requireRow(id));
    }

    /**
     * 删除 · 逻辑删除（Flex 的 {@code deleteById} 对 {@code @Column(isLogicDelete=true)}
     * 字段会改写为 UPDATE deleted=1）。
     *
     * <p>⚠️ 与源侧的差异：源侧 {@code deleted_at} 记录删除<b>时刻</b>；
     * zjky 的 {@code deleted} 只是布尔标志 ⇒ <b>不记录何时删除</b>。
     * 这是用户 2026-11-03 裁定「严格照 zjky」的直接后果。
     *
     * @param id 知识库 ID
     * @throws NotFoundException 不存在
     */
    @Transactional
    public void delete(String id) {
        requireRow(id);
        mapper.deleteById(id);
        log.info("[知识库] 已逻辑删除 id={}", id);
    }

    /**
     * 局部更新（{@code PATCH}）· 批次 W5。
     *
     * <h2>⭐⭐ 必须区分「没传」与「传了 null」</h2>
     * {@code PUT} 用 record 接收，Jackson 无法区分这两者；
     * 而对 FAQ 来说差别是致命的：随手 PATCH 一下 name 不应该把 {@code faq_config} 清空。
     * ⇒ 这里收原始 {@code JsonNode}，用 {@code body.has(field)} 判断「是否传了」。
     *
     * <h2>⚠️ 与 PUT 的分工</h2>
     * PUT = 已知字段的「提交表单」语义；PATCH = 「我只有这几个改动」。
     * 两者都保留（源侧同样两个端点都存在）。
     *
     * @param id   知识库 ID
     * @param body 原始请求体
     * @return 更新后的知识库
     */
    @Transactional
    public KnowledgeBaseDto patch(String id, com.fasterxml.jackson.databind.JsonNode body) {
        KbKnowledgeBaseRecord row = requireRow(id);
        if (body == null || !body.isObject()) {
            throw new ValidationException("请求体必须是 JSON 对象");
        }
        boolean changed = false;

        if (body.has("name")) {
            String name = trimToNull(text(body, "name"));
            if (name == null) {
                throw new ValidationException("知识库名不能为空");
            }
            row.setName(name);
            changed = true;
        }
        if (body.has("description")) {
            row.setDescription(text(body, "description"));
            changed = true;
        }
        if (body.has("type")) {
            // ⚠️ 类型可改听起来无害，但 FAQ 库改成 document 会让所有 FAQ 条目变成孤儿
            //    （条目挂在 type=faq 的容器 knowledge 下）。源侧 PATCH 不支持改 type。
            throw new ValidationException("知识库类型不可修改");
        }
        // 各类 JSON 配置：传 null = 清空（显式），没传 = 不动
        changed |= setJsonField(body, "chunking_config", row::setChunkingConfig);
        changed |= setJsonField(body, "faq_config", row::setFaqConfig);
        changed |= setJsonField(body, "image_processing_config", row::setImageProcessingConfig);
        changed |= setJsonField(body, "auto_tag_config", row::setAutoTagConfig);
        // ⭐ 2026-11-11：记下 profile_config 变更前后的开关状态。
        //    源站 knowledgebase.go:756-758：开关从关变开时**立即**生成一次描述
        //    （「Turning automatic description generation on should produce a description now,
        //    not after the next upload.」）。
        boolean profileEnabledBefore = profileEnabled(row.getProfileConfig());
        changed |= setJsonField(body, "profile_config", row::setProfileConfig);
        boolean profileEnabledAfter = profileEnabled(row.getProfileConfig());
        changed |= setJsonField(body, "question_generation_config", row::setQuestionGenerationConfig);
        changed |= setJsonField(body, "wiki_config", row::setWikiConfig);
        changed |= setJsonField(body, "extract_config", row::setExtractConfig);
        changed |= setJsonField(body, "asr_config", row::setAsrConfig);
        changed |= setJsonField(body, "vlm_config", row::setVlmConfig);
        changed |= setJsonField(body, "graph_store_config", row::setGraphStoreConfig);
        changed |= setJsonField(body, "indexing_strategy", row::setIndexingStrategy);
        changed |= setJsonField(body, "storage_provider_config", row::setStorageProviderConfig);

        if (body.has("embedding_model_id")) {
            row.setEmbeddingModelId(orEmpty(text(body, "embedding_model_id")));
            changed = true;
        }
        if (body.has("summary_model_id")) {
            row.setSummaryModelId(orEmpty(text(body, "summary_model_id")));
            changed = true;
        }
        if (body.has("storage_backend_id")) {
            row.setStorageBackendId(orEmpty(text(body, "storage_backend_id")));
            changed = true;
        }
        // ⭐ V286 图片管线三项：没传 = 不动；传 null = 回默认（true / default）。
        if (body.has("image_caption_enabled")) {
            row.setImageCaptionEnabled(switchOf(body.get("image_caption_enabled"), "image_caption_enabled"));
            changed = true;
        }
        if (body.has("image_ocr_enabled")) {
            row.setImageOcrEnabled(switchOf(body.get("image_ocr_enabled"), "image_ocr_enabled"));
            changed = true;
        }
        if (body.has("image_pipeline")) {
            row.setImagePipeline(requireImagePipeline(text(body, "image_pipeline")));
            changed = true;
        }
        if (body.has("retriever_engine")) {
            String engine = trimToNull(text(body, "retriever_engine"));
            if (engine != null && engineRegistry != null) {
                try {
                    engineRegistry.require(engine);
                } catch (IllegalStateException e) {
                    throw new ValidationException(e.getMessage());
                }
            }
            row.setRetrieverEngine(engine);
            changed = true;
        }

        if (!changed) {
            // ⚠️ 没有可识别字段时不要静默返回 200 —— 用户会以为改成功了。
            throw new ValidationException("请求体里没有任何可更新的字段");
        }
        // ⚠️ `update(row, false)`：显式写 NULL（清空配置必须走这条，见缺陷 9）
        mapper.update(row, false);
        log.info("[知识库] 已局部更新 id={} name={}", id, row.getName());
        // ⭐ 2026-11-11：开关刚被打开 ⇒ 立即生成一次画像（照源站 knowledgebase.go:756-758）
        if (!profileEnabledBefore && profileEnabledAfter && profileTrigger != null) {
            profileTrigger.trigger(id, false);
        }
        return toDto(requireRow(id));
    }

    /**
     * 读 {@code profile_config.enabled}（画像自动生成开关）。
     *
     * <p>⚠️ 解析失败一律当「关」：一个坏 JSON 不该让「关→开」的判定变成真。
     */
    private static boolean profileEnabled(String profileConfigJson) {
        if (profileConfigJson == null || profileConfigJson.isBlank()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(profileConfigJson);
            return n != null && n.isObject() && n.path("enabled").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 取 JSON 文本；返回是否改动。传了 null ⇒ 置 null（清空）。 */
    private static boolean setJsonField(com.fasterxml.jackson.databind.JsonNode body,
                                        String field,
                                        java.util.function.Consumer<String> setter) {
        if (!body.has(field)) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode v = body.get(field);
        if (v == null || v.isNull()) {
            setter.accept(null);
        } else if (v.isTextual()) {
            // 部分客户端把 JSON 配置当字符串传 ⇒ 原样接收（不重复编码）
            setter.accept(v.asText());
        } else {
            setter.accept(v.toString());
        }
        return true;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode body, String field) {
        com.fasterxml.jackson.databind.JsonNode v = body.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 图片管线 · 请求里的 Boolean → 1/0（null = 默认 1）。 */
    private static int onOffOrDefault(Boolean v) {
        return v == null || v ? 1 : 0;
    }

    /**
     * 图片管线 · 值域校验 + 归一。
     *
     * <p>只允许 {@code default} / {@code smartocr}（大小写不敏感）；空白 ⇒ default；
     * 其它值 ⇒ 400。⛔ 不静默存脏值：{@code image_pipeline} 是
     * {@code KbImageEnrichService} 的分支判据，存进去一个拼错的值等于
     * 「开关设了但实际走默认管线」，用户完全看不出来。
     *
     * @param raw 原始值
     * @return {@code default} 或 {@code smartocr}
     * @throws ValidationException 值域外
     */
    private static String requireImagePipeline(String raw) {
        if (!com.nexusai.kb.app.image.KbImageSettings.isValidPipeline(raw)) {
            throw new ValidationException("image_pipeline 只能是 default / smartocr，收到: " + raw);
        }
        return com.nexusai.kb.app.image.KbImageSettings.normalizePipeline(raw);
    }

    /**
     * PATCH 的开关字段解析：接受布尔 / 0-1 数字（前端两种都可能传）；JSON null ⇒ 1（回默认）。
     *
     * @throws ValidationException 其它类型（字符串、对象、数组）
     */
    private static int switchOf(com.fasterxml.jackson.databind.JsonNode v, String field) {
        if (v == null || v.isNull()) {
            return 1;
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? 1 : 0;
        }
        if (v.isNumber()) {
            return v.asInt() != 0 ? 1 : 0;
        }
        throw new ValidationException(field + " 必须是布尔值，收到: " + v);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 创建语义 · 「传了就写、留空不写」：null / 空白 ⇒ 不设（列可空，保持 NULL）。
     *
     * <p>与 update 的 {@link #setIfNotNull} 不同 —— 创建时空白串没有「清空」含义，
     * 只是前端没填，落 NULL 比落 "" 更贴近「未配置」。
     */
    private static void setIfPresent(String raw, java.util.function.Consumer<String> setter) {
        if (raw != null && !raw.isBlank()) {
            setter.accept(raw.trim());
        }
    }

    /** 更新语义 · null = 不改；非 null（含 ""）= 写入（"" 用于解绑 / 清空）。 */
    private static void setIfNotNull(String raw, java.util.function.Consumer<String> setter) {
        if (raw != null) {
            setter.accept(raw);
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private KbKnowledgeBaseRecord requireRow(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        // Flex 的 selectOneById 对逻辑删除字段自动追加 deleted = 0
        KbKnowledgeBaseRecord row = mapper.selectOneById(id);
        if (row == null) {
            throw new NotFoundException("知识库 " + id + " 不存在");
        }
        return row;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 类型规范化：空白 → document；非白名单 → 400。
     *
     * @param raw 原始类型
     * @return 规范化后的类型
     * @throws ValidationException 类型不在白名单
     */
    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TYPE;
        }
        String t = raw.trim().toLowerCase();
        if (!ALLOWED_TYPES.contains(t)) {
            throw new ValidationException("知识库类型只能是 document / faq / wiki，收到: " + raw);
        }
        return t;
    }

    /** 实体 → DTO。 */
    private static KnowledgeBaseDto toDto(KbKnowledgeBaseRecord r) {
        return new KnowledgeBaseDto(
            r.getId(),
            r.getName(),
            r.getDescription(),
            r.getType(),
            r.getIsTemporary() != null && r.getIsTemporary() != 0,
            r.getChunkingConfig(),
            r.getImageProcessingConfig(),
            r.getEmbeddingModelId(),
            r.getSummaryModelId(),
            r.getExtractConfig(),
            r.getVectorStoreId(),
            r.getStorageBackendId(),
            r.getRetrieverEngine(),
            r.getIsPinned() != null && r.getIsPinned() != 0,
            r.getPinnedAt(),
            r.getGeneratedProfile(),
            KbTimeFormat.format(r.getCreateTime()),
            KbTimeFormat.format(r.getUpdateTime()),
            // ⭐ 读侧补出：前端靠 indexing_strategy.wiki_enabled 决定 Wiki/图谱 tab 是否显示
            r.getIndexingStrategy(),
            r.getWikiConfig(),
            // ⭐ 读侧补出（B7）：前端开关状态靠这几项回显。只写不读 ⇒ 保存后开关仍显示关闭。
            r.getQuestionGenerationConfig(),
            r.getAutoTagConfig(),
            r.getAsrConfig(),
            r.getProfileConfig(),
            r.getFaqConfig(),
            // ⭐ V281：图谱存储配置（本地 / 远程 Neo4j）。同上，读侧必须回传，
            //    否则设置弹窗保存后仍显示「本地」，用户以为没生效。
            r.getGraphStoreConfig(),
            // ⭐ V286：图片管线三项。读侧必须回传（开关状态回显），
            //    列 NOT NULL，null 只可能是未回填的旧对象 ⇒ 按默认 true/true/default 出站。
            r.getImageCaptionEnabled() == null || r.getImageCaptionEnabled() != 0,
            r.getImageOcrEnabled() == null || r.getImageOcrEnabled() != 0,
            r.getImagePipeline() == null || r.getImagePipeline().isBlank()
                ? com.nexusai.kb.app.image.KbImageSettings.PIPELINE_DEFAULT
                : r.getImagePipeline());
    }
}
