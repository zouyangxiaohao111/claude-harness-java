package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.kb.app.tag.KbTagService;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档自动打标 · 对齐 WeKnora
 * {@code knowledge_auto_tag.go Handle}（112）/ {@code classifyExistingTags} /
 * {@code validateAutoTagMatches}。
 *
 * <h2>它做什么</h2>
 * 文档解析完成后，让模型<b>从该知识库已有的标签里</b>挑出最匹配的几个，
 * 追加进 {@code kb_knowledge_tag_relation}。
 *
 * <h2>⭐⭐ 三条硬语义</h2>
 * <ol>
 *   <li><b>绝不允许自创标签</b>。模型只能回编号，编号再映射回真实 tag_id；
 *       越界/重复/低置信度全部丢弃。让模型自由生成标签会污染用户的标签体系
 *       （标签管理里出现一堆模型编的词，且无法与人工标签区分）。</li>
 *   <li><b>追加而不是替换</b>（{@code INSERT OR IGNORE}）。
 *       用「替换」语义会把用户手工打的标签一起抹掉。</li>
 *   <li><b>不占后处理槽位</b>（见 {@link KbPostProcessStage}）。打标失败不该把文档
 *       卡在「处理中」—— 标签是锦上添花，不是文档可用的前提。</li>
 * </ol>
 */
@Component
public class KbAutoTagService {

    private static final Logger log = LoggerFactory.getLogger(KbAutoTagService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 候选标签上限（源侧 {@code maximumAutoTagCandidates = 500}）。 */
    static final int MAX_CANDIDATES = 500;

    /** 送进模型的正文上限（源侧 {@code maximumAutoTagContentRunes = 16000}）。 */
    static final int MAX_CONTENT_CHARS = 16000;

    /** 置信度下限（源侧 {@code minimumAutoTagConfidence = 0.75}）。 */
    static final double MIN_CONFIDENCE = 0.75;

    private static final int MAX_TOKENS = 1024;
    private static final double TEMPERATURE = 0.1;

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper chunkMapper;
    private final KbKnowledgeStateRepository stateRepository;
    private final KbTagService tagService;
    private final JdbcTemplate jdbc;
    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    public KbAutoTagService(
        KbKnowledgeMapper knowledgeMapper,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbChunkMapper chunkMapper,
        KbKnowledgeStateRepository stateRepository,
        KbTagService tagService,
        JdbcTemplate jdbc,
        LlmSideCallClient sideCallClient
    ) {
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.chunkMapper = chunkMapper;
        this.stateRepository = stateRepository;
        this.tagService = tagService;
        this.jdbc = jdbc;
        this.sideCallClient = sideCallClient;
    }

    /**
     * 给一篇文档自动打标。
     *
     * @param knowledgeId 文档 ID
     * @param attempt     本轮解析 attempt
     * @return 结果（永不 null、永不抛）
     */
    public KbPostProcessOutcome classify(String knowledgeId, int attempt) {
        // ── 守卫 ──────────────────────────────────────────────────────
        KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            return KbPostProcessOutcome.skipped("knowledge_not_found");
        }
        int current = knowledge.getParseAttempt() == null ? 0 : knowledge.getParseAttempt();
        if (current != attempt) {
            return KbPostProcessOutcome.superseded(
                "解析已被新一轮取代 任务attempt=" + attempt + " 当前=" + current);
        }
        String status = knowledge.getParseStatus();
        if (KbKnowledgeStateRepository.STATUS_DELETING.equals(status)
            || KbKnowledgeStateRepository.STATUS_CANCELLED.equals(status)
            || KbKnowledgeStateRepository.STATUS_FAILED.equals(status)) {
            return KbPostProcessOutcome.skipped("文档处于 " + status);
        }
        String kbId = knowledge.getKnowledgeBaseId();
        KbKnowledgeBaseRecord kb = kbId == null || kbId.isBlank()
            ? null : knowledgeBaseMapper.selectOneById(kbId);
        if (kb == null) {
            return KbPostProcessOutcome.skipped("kb_not_found");
        }
        // 源侧：只对「文档型」知识库打标（FAQ 库的条目不参与）
        if (!"document".equals(kb.getType())) {
            return KbPostProcessOutcome.skipped("kb_not_document");
        }
        KbPostProcessConfig config = KbPostProcessConfig.resolve(kb);
        if (!config.autoTag().runnable()) {
            return KbPostProcessOutcome.skipped("auto_tag_disabled");
        }

        // ── 候选标签 ──────────────────────────────────────────────────
        List<KbTagService.TagView> all = tagService.list(kbId, false);
        if (all.isEmpty()) {
            return KbPostProcessOutcome.skipped("no_candidate_tags");
        }
        boolean truncated = all.size() > MAX_CANDIDATES;
        List<KbTagService.TagView> candidates = truncated
            ? all.subList(0, MAX_CANDIDATES) : all;
        if (truncated) {
            log.warn("[打标] 知识库 {} 有 {} 个标签，只分类前 {} 个",
                kbId, all.size(), MAX_CANDIDATES);
        }

        // ── 已有标签（已打过就跳过，省一次 LLM 调用）──────────────────
        Set<String> existing = new HashSet<>();
        for (KbTagService.TagView t : tagService.listForKnowledge(knowledgeId)) {
            existing.add(t.id());
        }
        if (!existing.isEmpty() && config.autoTag().skipIfTagged()) {
            return KbPostProcessOutcome.skipped("already_tagged");
        }

        // ── 正文 ──────────────────────────────────────────────────────
        String content = buildDocumentContent(knowledge, chunksOf(knowledgeId));
        if (content.isBlank()) {
            return KbPostProcessOutcome.skipped("no_text_chunks");
        }

        // ── 调模型 ────────────────────────────────────────────────────
        String modelId = config.autoTag().modelId();
        ProviderConfig provider = resolveConfig(modelId);
        if (provider == null) {
            return KbPostProcessOutcome.skipped("provider_not_configured");
        }
        List<String> names = new ArrayList<>(candidates.size());
        List<String> ids = new ArrayList<>(candidates.size());
        for (KbTagService.TagView t : candidates) {
            names.add(t.name());
            ids.add(t.id());
        }
        int maxTags = config.autoTag().maxTags();
        List<Match> matches;
        try {
            String raw = sideCallClient.chat(
                provider, modelId,
                KbPostProcessPrompts.autoTagSystem(maxTags),
                KbPostProcessPrompts.autoTagUser(names, content),
                options(TEMPERATURE, MAX_TOKENS, "document_auto_tag"),
                LlmSideCallClient.SideCallUsage.standalone(
                    UsageCategory.SIDE_CALL, "document_auto_tag"));
            matches = parseMatches(raw);
        } catch (RuntimeException e) {
            log.warn("[打标] 模型调用失败 knowledge={} 原因={}", knowledgeId, e.getMessage());
            return KbPostProcessOutcome.failed("auto_tag_call_failed: " + e.getMessage());
        }
        if (matches == null) {
            return KbPostProcessOutcome.failed("auto_tag_bad_response");
        }

        // ── 编号 → 真实 ID ────────────────────────────────────────────
        List<String> selected = selectTagIds(ids, matches, maxTags);
        if (selected.isEmpty()) {
            return KbPostProcessOutcome.skipped("no_matching_tags");
        }

        // ── 追加写关系表（⛔ 不是替换）───────────────────────────────
        String now = KbTimeFormat.now();
        int added = 0;
        for (String tagId : selected) {
            if (existing.contains(tagId)) {
                continue;
            }
            try {
                added += jdbc.update(
                    "INSERT OR IGNORE INTO kb_knowledge_tag_relation"
                        + " (knowledge_id, tag_id, knowledge_base_id, created_at, tenant_id)"
                        + " VALUES (?, ?, ?, ?, 0)",
                    knowledgeId, tagId, kbId, now);
            } catch (RuntimeException e) {
                log.warn("[打标] 写关系失败 knowledge={} tag={} 原因={}",
                    knowledgeId, tagId, e.getMessage());
            }
        }
        log.info("[打标] knowledge={} 候选={} 匹配={} 新增={} 模型={}",
            knowledgeId, candidates.size(), selected.size(), added, modelId);
        if (added == 0) {
            return KbPostProcessOutcome.skipped("all_matches_already_attached");
        }
        return KbPostProcessOutcome.done("added=" + added);
    }

    // ══════════════════════════════════════════════════════════
    // 纯函数（单测直接打）
    // ══════════════════════════════════════════════════════════

    /**
     * 模型给的一条匹配。
     *
     * @param index      候选标签序号（1 起）
     * @param confidence 置信度 0..1
     */
    record Match(int index, double confidence) {
    }

    /**
     * 把模型返回的序号映射回真实标签 ID。
     *
     * <p>丢弃规则（源侧 {@code validateAutoTagMatches}）：
     * 置信度 &lt; 0.75、序号越界、重复、超过 maxTags。
     *
     * @param candidateIds 候选标签 ID（顺序 = 编号 - 1）
     * @param matches      模型返回的匹配（可 null）
     * @param maxTags      最多选几个
     * @return 选中的 ID（按置信度降序；永不 null）
     */
    static List<String> selectTagIds(
        List<String> candidateIds, List<Match> matches, int maxTags
    ) {
        if (candidateIds == null || candidateIds.isEmpty() || matches == null || maxTags <= 0) {
            return List.of();
        }
        List<Match> sorted = new ArrayList<>(matches);
        sorted.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));

        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>(maxTags);
        for (Match m : sorted) {
            if (m.confidence() < MIN_CONFIDENCE) {
                continue;
            }
            if (m.index() < 1 || m.index() > candidateIds.size()) {
                continue;
            }
            String id = candidateIds.get(m.index() - 1);
            if (id == null || id.isBlank() || !seen.add(id)) {
                continue;
            }
            out.add(id);
            if (out.size() >= maxTags) {
                break;
            }
        }
        return out;
    }

    /**
     * 解析模型返回的 {@code {"matches":[{"index":1,"confidence":0.9}]}}。
     *
     * @param raw 模型原文
     * @return 匹配列表；结构不可识别 ⇒ null（调用方判失败，可重试）
     */
    static List<Match> parseMatches(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(s.substring(start, end + 1));
            JsonNode arr = root == null ? null : root.get("matches");
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            List<Match> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                out.add(new Match(n.path("index").asInt(0), n.path("confidence").asDouble(0)));
            }
            return out;
        } catch (Exception e) {
            log.warn("[打标] 模型输出解析失败 原因={}", e.getMessage());
            return null;
        }
    }

    /**
     * 拼打标用的正文。
     *
     * <p>包含「文档名」与「已有摘要」：这两项是稳定的高层语义，比逐块正文更能代表文档主题。
     * ⚠️ 与摘要提示词不同 —— 那里明确禁止用文件名（会诱发幻觉），
     * 这里允许，因为打标只是「从既有标签里选」，不需要模型凭空描述。
     *
     * @param knowledge 文档
     * @param chunks    文本类分块（按 startAt 升序）
     * @return 正文（不超过 {@link #MAX_CONTENT_CHARS}）
     */
    static String buildDocumentContent(KbKnowledgeRecord knowledge, List<KbChunkRecord> chunks) {
        StringBuilder sb = new StringBuilder();
        String title = knowledge == null ? null : knowledge.getTitle();
        if (title != null && !title.isBlank()) {
            sb.append("文档名：").append(title.trim());
        }
        String desc = knowledge == null ? null : knowledge.getDescription();
        if (desc != null && !desc.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("已有摘要：").append(desc.trim());
        }
        if (chunks != null) {
            for (KbChunkRecord c : chunks) {
                if (c == null || c.getContent() == null || c.getContent().isBlank()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(c.getContent().trim());
            }
        }
        String s = sb.toString();
        int len = s.codePointCount(0, s.length());
        if (len <= MAX_CONTENT_CHARS) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, MAX_CONTENT_CHARS));
    }

    // ══════════════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════════════

    /** 取参与打标的分块（text / image_ocr / image_caption，按 startAt 升序）。 */
    private List<KbChunkRecord> chunksOf(String knowledgeId) {
        List<KbChunkRecord> rows = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .and("is_enabled = ?", 1));
        List<KbChunkRecord> out = new ArrayList<>();
        for (KbChunkRecord c : rows) {
            String type = c.getChunkType();
            if ("text".equals(type) || "image_ocr".equals(type) || "image_caption".equals(type)) {
                out.add(c);
            }
        }
        out.sort((a, b) -> Integer.compare(
            a.getStartAt() == null ? 0 : a.getStartAt(),
            b.getStartAt() == null ? 0 : b.getStartAt()));
        return out;
    }

    private ProviderConfig resolveConfig(String modelName) {
        if (modelConfigResolver == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        try {
            ModelConfigResolver.ResolvedModel r = modelConfigResolver.resolve(modelName);
            return r == null ? null : r.config();
        } catch (RuntimeException e) {
            log.warn("[打标] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    private static LlmProvider.ChatRequestOptions options(
        double temperature, int maxTokens, String label
    ) {
        return new LlmProvider.ChatRequestOptions(
            null, null, null,
            LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
            temperature, label, null, maxTokens, null, null,
            null, null, null, null, null, null);
    }
}
