package com.nexusai.kb.app.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeTagRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeTagRelationRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeTagMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeTagRelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库画像生成 · 批次 W4（{@code POST /knowledge-bases/{id}/profile/generate}）。
 *
 * <h2>⭐⭐ 空库不调模型</h2>
 * 见 {@link KbProfilePrompts}：空库调模型必然编造内容。这里在入口就短路，
 * 写入固定文案 {@link KbProfilePrompts#EMPTY_PROFILE}。
 *
 * <h2>⭐ 生成失败保留旧画像</h2>
 * 不能让一次网络抖动把用户已有的画像清空。失败时：不写库、把旧值原样返回、HTTP 200
 * （不是 500 —— 「没生成新的」不等于「请求失败」；前端据此提示「保留原描述」）。
 */
@Service
public class KbProfileService {

    private static final Logger log = LoggerFactory.getLogger(KbProfileService.class);

    /** JSON 工具（口径与 {@code KbSummaryService} 一致）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 画像长度上限（与提示词的「不超过 200 字」配套，防模型超写）。 */
    private static final int MAX_PROFILE_CHARS = 1000;

    /** 温度：描述类文本要稳定，不要发散。 */
    private static final double TEMPERATURE = 0.3;

    /**
     * 补全预算（源站 {@code knowledgeBaseProfileDefaultMaxTokens} = 2048）。
     *
     * <p>⚠️ 为什么不能太小：即使关掉思考，有些模型仍会花一点预算在 thinking 上，
     * 预算太小会返回**空补全**，整个描述就失败了（源站 issue #4221）。
     */
    private static final int MAX_TOKENS = 2048;

    /** 记账标签（与其它旁路调用的命名口径一致）。 */
    private static final String USAGE_LABEL = "kb_profile_generate";

    private final KbKnowledgeBaseMapper baseMapper;
    private final KbKnowledgeMapper knowledgeMapper;
    private final KbActivityService activityService;
    private final KbKnowledgeTagMapper tagMapper;
    private final KbKnowledgeTagRelationMapper tagRelationMapper;

    @Autowired(required = false)
    private LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    @Autowired
    public KbProfileService(KbKnowledgeBaseMapper baseMapper,
                            KbKnowledgeMapper knowledgeMapper,
                            KbActivityService activityService,
                            KbKnowledgeTagMapper tagMapper,
                            KbKnowledgeTagRelationMapper tagRelationMapper) {
        this.baseMapper = baseMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.activityService = activityService;
        this.tagMapper = tagMapper;
        this.tagRelationMapper = tagRelationMapper;
    }

    /**
     * 生成知识库画像（照源站 {@code GenerateKnowledgeBaseProfile}）。
     *
     * <p><b>源站对照</b>：WeKnora {@code internal/application/service/knowledgebase_profile.go:277-337}。
     *
     * <h2>⭐ 流程（照源站）</h2>
     * <ol>
     *   <li><b>聚合</b>：读各文档画像（{@code kb_knowledge.profile}）→ {@link KbProfileAggregate}</li>
     *   <li><b>空聚合</b> ⇒ 写 status=empty，<b>不调模型</b>（空库调模型必然编造）</li>
     *   <li><b>指纹短路</b>：聚合指纹与上次一致 ⇒ 直接返回旧画像，<b>不调模型</b></li>
     *   <li><b>调模型</b>：profile_config.model_id 优先，空则 summary_model_id</li>
     *   <li><b>写回</b>：{@code generated_profile}</li>
     * </ol>
     *
     * @param kbId      知识库
     * @param modelName 指定模型（可空 ⇒ profile_config.model_id ⇒ 再空则 summary_model_id）
     * @param force     true ⇒ 跳过指纹短路，强制重新生成
     */
    public Result generate(String kbId, String modelName) {
        return generate(kbId, modelName, false);
    }

    /** 带 force 的版本（source 侧手动重生成用）。 */
    public Result generate(String kbId, String modelName, boolean force) {
        KbKnowledgeBaseRecord kb = requireBase(kbId);

        // ── 资格判据（照源站 knowledgeBaseProfileEligible）────────────
        // 只有「文档型、非临时」的库才配自动描述（FAQ 库没有文档画像可聚合）。
        if (!isEligible(kb)) {
            return new Result(null, "unsupported", 0, null);
        }

        // ── 聚合 ──────────────────────────────────────────────────────
        KbProfileAggregate agg = buildAggregate(kb);
        String hash = agg.hash();
        KbKnowledgeBaseProfile existing = readProfile(kb);

        if (agg.isEmpty()) {
            // ⚠️ 没有可描述的内容 ⇒ 写空态，**不调模型**
            // （源站：wiped knowledge base 不该继续宣传旧主题）
            KbKnowledgeBaseProfile p = new KbKnowledgeBaseProfile();
            p.setStatus("empty");
            p.setAggregateHash(hash);
            p.setStats(statsOf(agg));
            p.setGeneratedAt(nowIso());
            saveProfile(kbId, toJson(p));
            activityService.record(kbId, KbActivityService.A_PROFILE_GENERATE, "success",
                "knowledge_base", kbId, "{\"documents\":0,\"model_calls\":0}");
            log.info("[画像] 知识库 {} 无可聚合内容，写入空态（未调模型）", kbId);
            return new Result(p, "empty", 0, null);
        }

        // ── 指纹短路：聚合没变就不调模型（源站 existing.IsReady(hash)）──
        if (!force && existing != null && existing.isReady(hash)) {
            log.debug("[画像] 聚合未变，保留原描述 kb={}", kbId);
            return new Result(existing, "unchanged", agg.documentCount(), existing.getModelId());
        }

        // ── 选模型：profile_config.model_id 优先，空则 summary_model_id ──
        String model = firstNonBlank(modelName, profileModelId(kb), kb.getSummaryModelId());
        if (model == null) {
            KbKnowledgeBaseProfile failed = copyOf(existing);
            failed.setStatus("failed");
            failed.setError("未配置模型（profile_config.model_id 或 summary_model_id）");
            failed.setStats(statsOf(agg));
            failed.setAggregateHash(hash);
            failed.setGeneratedAt(nowIso());
            saveProfile(kbId, toJson(failed));
            log.warn("[画像] 未配置模型 kb={}，记录失败状态", kbId);
            return new Result(failed, "failed", agg.documentCount(), null);
        }
        ModelConfigResolver.ResolvedModel resolved = resolveModel(model);
        ProviderConfig config = resolved == null ? null : resolved.config();
        if (config == null || sideCallClient == null) {
            // ⚠️ 不写库：保留旧画像（源站 persistFailure 同理）
            log.warn("[画像] 模型不可用 kb={} model={}，保留原描述", kbId, model);
            return new Result(existing, "unavailable", agg.documentCount(), model);
        }
        // ⚠️⭐ 传给模型的必须是 **models.name**（provider 侧真实模型名），
        //    而 `summary_model_id` / `profile_config.model_id` 存的是 **models.id**。
        //    混用会真的打到对方 API 上并 400（实测：「The supported API model names are
        //    deepseek-flash, deepseek-v4-pro, but you passed model-8869ce11」）。
        String apiModelName = resolved.apiModelName() != null && !resolved.apiModelName().isBlank()
            ? resolved.apiModelName()
            : model;

        // ── 调模型（唯一一次）─────────────────────────────────────────
        KbKnowledgeBaseProfile generated;
        try {
            generated = describeAggregate(config, apiModelName, model, kb, agg);
        } catch (RuntimeException e) {
            KbKnowledgeBaseProfile failed = copyOf(existing);
            failed.setStatus("failed");
            failed.setError(truncate(e.getMessage(), 300));
            failed.setStats(statsOf(agg));
            failed.setAggregateHash(hash);
            failed.setGeneratedAt(nowIso());
            saveProfile(kbId, toJson(failed));
            log.warn("[画像] 调用失败 kb={} 原因={}", kbId, e.getMessage());
            return new Result(failed, "failed", agg.documentCount(), model);
        }
        if (generated == null || generated.getGist() == null || generated.getGist().isBlank()) {
            KbKnowledgeBaseProfile failed = copyOf(existing);
            failed.setStatus("failed");
            failed.setError("模型返回空内容");
            failed.setStats(statsOf(agg));
            failed.setAggregateHash(hash);
            failed.setGeneratedAt(nowIso());
            saveProfile(kbId, toJson(failed));
            return new Result(failed, "empty_response", agg.documentCount(), model);
        }

        generated.setStats(statsOf(agg));
        generated.setAggregateHash(hash);
        generated.setStatus("ready");
        generated.setModelId(model);
        generated.setGeneratedAt(nowIso());
        saveProfile(kbId, toJson(generated));
        activityService.record(kbId, KbActivityService.A_PROFILE_GENERATE, "success",
            "knowledge_base", kbId,
            "{\"documents\":" + agg.documentCount() + ",\"model_calls\":1}");
        log.info("[画像] 已生成 kb={} 文档数={} 主题数={} model={}",
            kbId, agg.documentCount(), generated.getTopics() == null ? 0 : generated.getTopics().size(), model);
        return new Result(generated, "generated", agg.documentCount(), model);
    }

    /**
     * 聚合各文档画像（照源站 {@code BuildAggregate}）。
     *
     * <p>⚠️ 只取**已完成解析且启用**的文档（源站 {@code ListKnowledgeProfileRows}）：
     * {@code parse_status IN (completed, finalizing) AND enable_status = enabled}。
     */
    KbProfileAggregate buildAggregate(KbKnowledgeBaseRecord kb) {
        List<KbKnowledgeRecord> docs = knowledgeMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kb.getId())
                .and("parse_status in ('completed','finalizing')")
                .and("enable_status = 'enabled'")
                .orderBy("create_time", true));
        Map<String, List<String>> tagsByDoc = tagsOf(kb.getId());
        KbProfileAggregate agg = new KbProfileAggregate();
        for (KbKnowledgeRecord d : docs) {
            agg.add(d.getId(), d.getTitle(), d.getFileType(), d.getFolderPath(),
                d.getCreateTime() == null ? null : d.getCreateTime().toString(),
                tagsByDoc.get(d.getId()), d.getProfile());
        }
        return agg;
    }

    /**
     * 读该库下各文档的标签（源站 {@code attachTags}）。
     *
     * <p>一次性把关系表与标签表全捞进内存做映射（避免逐文档查库）。
     */
    private Map<String, List<String>> tagsOf(String kbId) {
        Map<String, List<String>> out = new HashMap<>();
        if (tagRelationMapper == null || tagMapper == null) {
            return out;
        }
        List<KbKnowledgeTagRelationRecord> rels = tagRelationMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_base_id = ?", kbId));
        if (rels == null || rels.isEmpty()) {
            return out;
        }
        List<KbKnowledgeTagRecord> all = tagMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_base_id = ?", kbId));
        Map<String, String> nameById = new HashMap<>();
        if (all != null) {
            for (KbKnowledgeTagRecord t : all) {
                nameById.put(t.getId(), t.getName());
            }
        }
        for (KbKnowledgeTagRelationRecord r : rels) {
            String name = nameById.get(r.getTagId());
            if (name == null || name.isBlank()) {
                continue;
            }
            out.computeIfAbsent(r.getKnowledgeId(), k -> new ArrayList<>()).add(name);
        }
        return out;
    }

    /** 调模型生成描述（照源站 {@code describeAggregate}）。 */
    private KbKnowledgeBaseProfile describeAggregate(
        ProviderConfig config, String apiModelName, String modelId,
        KbKnowledgeBaseRecord kb, KbProfileAggregate agg) {
        String raw = sideCallClient.chat(
            config, apiModelName,
            KbProfilePrompts.AGGREGATE_SYSTEM,
            KbProfilePrompts.aggregateUserMessage(kb.getName(), agg, profileCustomInstructions(kb)),
            options(TEMPERATURE, MAX_TOKENS, USAGE_LABEL),
            LlmSideCallClient.SideCallUsage.standalone(
                com.nexusai.infra.llm.UsageCategory.SIDE_CALL, "kb-profile"));
        var node = readJson(raw);
        KbKnowledgeBaseProfile p = new KbKnowledgeBaseProfile();
        p.setGist(node.path("gist").asText("").trim());
        List<String> topics = new ArrayList<>();
        if (node.path("topics").isArray()) {
            for (var t : node.path("topics")) {
                String v = t.asText("").trim();
                if (!v.isEmpty() && topics.size() < KbProfileAggregate.MAX_TOPICS) {
                    topics.add(v);
                }
            }
        }
        p.setTopics(topics);
        List<String> questions = new ArrayList<>();
        if (node.path("typical_questions").isArray()) {
            for (var q : node.path("typical_questions")) {
                String v = q.asText("").trim();
                if (!v.isEmpty() && questions.size() < KbProfileAggregate.MAX_QUESTIONS) {
                    questions.add(v);
                }
            }
        }
        p.setTypicalQuestions(questions);
        return p;
    }

    /** 读库里已有的画像（解析失败/空 JSON ⇒ null）。 */
    private KbKnowledgeBaseProfile readProfile(KbKnowledgeBaseRecord kb) {
        String raw = kb.getGeneratedProfile();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw);
            if (n == null || !n.isObject()) {
                return null;
            }
            return JSON.convertValue(n, KbKnowledgeBaseProfile.class);
        } catch (Exception e) {
            return null;
        }
    }

    private KbKnowledgeBaseProfile copyOf(KbKnowledgeBaseProfile src) {
        KbKnowledgeBaseProfile p = new KbKnowledgeBaseProfile();
        if (src != null) {
            p.setGist(src.getGist());
            p.setTopics(src.getTopics() == null ? null : new ArrayList<>(src.getTopics()));
            p.setTypicalQuestions(src.getTypicalQuestions() == null
                ? null : new ArrayList<>(src.getTypicalQuestions()));
            p.setStats(src.getStats());
            p.setAggregateHash(src.getAggregateHash());
            p.setModelId(src.getModelId());
            p.setGeneratedAt(src.getGeneratedAt());
        }
        return p;
    }

    private KbKnowledgeBaseProfile.Stats statsOf(KbProfileAggregate agg) {
        KbKnowledgeBaseProfile.Stats s = new KbKnowledgeBaseProfile.Stats();
        s.setDocumentCount(agg.documentCount());
        s.setProfiledCount(agg.profiledCount());
        s.setEarliestAt(agg.earliestAt());
        s.setLatestAt(agg.latestAt());
        s.setFileTypes(agg.fileTypeCounts());
        s.setTags(agg.tagCounts());
        s.setFolders(agg.folderNames());
        s.setRawTopics(agg.rawTopicCounts());
        return s;
    }

    /** 资格判据（照源站 {@code knowledgeBaseProfileEligible}）。 */
    static boolean isEligible(KbKnowledgeBaseRecord kb) {
        if (kb == null || kb.getId() == null || kb.getId().isBlank()) {
            return false;
        }
        Integer tmp = kb.getIsTemporary();
        if (tmp != null && tmp != 0) {
            return false;
        }
        return "document".equalsIgnoreCase(kb.getType());
    }

    /** profile_config.model_id。 */
    private String profileModelId(KbKnowledgeBaseRecord kb) {
        var cfg = readProfileConfig(kb);
        var v = cfg.path("model_id").asText("");
        return v.isBlank() ? null : v.trim();
    }

    /** profile_config.custom_instructions。 */
    private String profileCustomInstructions(KbKnowledgeBaseRecord kb) {
        var cfg = readProfileConfig(kb);
        var v = cfg.path("custom_instructions").asText("");
        return v.isBlank() ? "" : v.trim();
    }

    /** 读 profile_config JSON（坏了 ⇒ 空对象，不炸）。 */
    private com.fasterxml.jackson.databind.JsonNode readProfileConfig(KbKnowledgeBaseRecord kb) {
        String raw = kb.getProfileConfig();
        if (raw == null || raw.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return n == null || !n.isObject() ? MissingNode.getInstance() : n;
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            JsonNode n = JSON.readTree(raw.trim());
            return n == null ? MissingNode.getInstance() : n;
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String nowIso() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 只读画像（不在端点列表里，供其它服务拼提示词）。 */

    /** 只读画像（不在端点列表里，供其它服务拼提示词）。 */
    public String current(String kbId) {
        KbKnowledgeBaseRecord kb = requireBase(kbId);
        return kb.getGeneratedProfile() == null ? "" : kb.getGeneratedProfile();
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private void saveProfile(String kbId, String text) {
        KbKnowledgeBaseRecord row = baseMapper.selectOneById(kbId);
        if (row == null) {
            throw new NotFoundException("知识库 " + kbId + " 不存在");
        }
        row.setGeneratedProfile(text);
        row.setUpdateTime(LocalDateTime.now());
        // `update(row, false)`：显式写 NULL。这里 text 恒非 null，但与其它写点保持同款更安全。
        baseMapper.update(row, false);
    }

    private KbKnowledgeBaseRecord requireBase(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        KbKnowledgeBaseRecord row = baseMapper.selectOneById(kbId);
        if (row == null) {
            throw new NotFoundException("知识库 " + kbId + " 不存在");
        }
        return row;
    }

    /**
     * 构造 ChatRequestOptions（字段顺序照 {@code LlmProvider.ChatRequestOptions} 定义）。
     */
    private static LlmProvider.ChatRequestOptions options(
        double temperature, int maxTokens, String usageLabel) {
        return new LlmProvider.ChatRequestOptions(
            null, null, null,
            // 描述撰写是机械提炼，关掉思考（省时省钱，输出也更稳）
            LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
            temperature, usageLabel,
            null, maxTokens, null, null,
            null, null, null, null,
            null, null);
    }

    /**
     * 解析模型配置。
     *
     * <p>⚠️⭐ 2026-11-11 修一个**既有 bug**：本方法原先只取 {@code resolved.config()}，
     * 调模型时把**模型 ID** 原样传过去 ⇒ 对方 API 直接 400：
     * <pre>
     * The supported API model names are deepseek-flash, deepseek-v4-pro,
     * but you passed model-8869ce11.
     * </pre>
     * ⇒ 必须用 {@code ResolvedModel.apiModelName()}（provider 侧的真实模型名）。
     * 同款正确写法见 {@code KbSummaryService}（它有注释说明这件事）。
     */
    private ModelConfigResolver.ResolvedModel resolveModel(String model) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            return modelConfigResolver.resolve(model);
        } catch (RuntimeException e) {
            log.warn("[画像] 模型配置解析失败 model={} 原因={}", model, e.getMessage());
            return null;
        }
    }

    /**
     * 清洗模型输出：去代码围栏、去首尾引号、去「描述：」前缀、截断超长。
     *
     * <p>⚠️ 模型很爱把纯文本也包进 ``` 里 —— 不清掉的话画像会带着围栏入库，
     * 拼进提示词时看起来像「代码块」，影响后续判定。
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) {
                s = s.substring(0, lastFence);
            }
            s = s.trim();
        }
        // 去掉「描述：」这类前缀（提示词要求不要，但模型偶尔还是加）
        for (String prefix : new String[]{"描述：", "描述:", "知识库描述：", "知识库描述:"}) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
                break;
            }
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.length() > MAX_PROFILE_CHARS) {
            s = s.substring(0, MAX_PROFILE_CHARS);
        }
        return s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * 生成结果。
     *
     * <p>⚠️ 2026-11-11：{@code profile} 从 <b>String</b> 改成 <b>{@link KbKnowledgeBaseProfile} 对象</b>。
     * 原因：前端早就按对象读（{@code r.profile?.gist}），后端返字符串 ⇒ 契约错配，
     * 「AI 生成描述」按钮拿不到 gist。对象形态同时与源站 {@code KnowledgeBaseProfile} 对齐。
     *
     * @param profile       画像对象（可空）
     * @param status        generated | unchanged | empty | unavailable | failed | empty_response | unsupported
     * @param documentCount 参与聚合的文档数
     * @param model         实际使用的模型（可为空）
     */
    public record Result(KbKnowledgeBaseProfile profile, String status, int documentCount, String model) {
        /** 是否真的调用了模型。 */
        public boolean usedModel() {
            return "generated".equals(status);
        }

        /** 画像正文（gist），没有则空串。 */
        public String gist() {
            return profile == null || profile.getGist() == null ? "" : profile.getGist();
        }
    }

    /** 时间格式化（出站）。 */
    public static String formatTime(LocalDateTime t) {
        return KbTimeFormat.format(t);
    }
}
