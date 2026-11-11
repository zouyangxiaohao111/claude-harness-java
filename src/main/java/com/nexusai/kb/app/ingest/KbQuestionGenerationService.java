package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 问题生成（Doc2Query）· 对齐 WeKnora
 * {@code knowledge_process.go processQuestionGenerationForChunks}（2232）
 * 与 {@code generateQuestionsWithContext}。
 *
 * <h2>它做什么</h2>
 * 为每个文本块生成 N 条「用户会怎么搜这段内容」的问句，写进
 * {@code kb_chunk.metadata.generated_questions}，再重建索引 ——
 * 于是「覆盖率要求是多少」这种问法也能命中写着「应达到 90%」的段落。
 *
 * <h2>⭐⭐ 三条容易搞砸的语义</h2>
 * <ol>
 *   <li><b>问句必须进索引文本</b>（见 {@link KbGeneratedQuestions}）。只存 metadata
 *       不进 FTS/向量 ⇒ 功能看起来做完了，召回率零提升。</li>
 *   <li><b>写回前重读 contentRevision</b>。生成问句要几秒，期间用户可能在编辑分块 ——
 *       对着旧内容生成的问句写进新内容，会把检索带去错误的地方。</li>
 *   <li><b>剔除 {@code <think>} 块</b>。服务端忽略 {@code Thinking=false} 时会把推理行
 *       混进 content，每一行都会被当成一条问句索引进去。</li>
 * </ol>
 *
 * <p><b>⚠️ 不给本类加 {@code @Transactional}</b>（末尾调 reindexKnowledge，FTS 独立连接会自锁）。
 */
@Component
public class KbQuestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(KbQuestionGenerationService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单次调用输出上限（源侧 2048，为 reasoning 型模型留余量）。 */
    private static final int MAX_TOKENS = 2048;

    private static final double TEMPERATURE = 0.7;

    /** 推理块（{@code <think>...</think>}）· 其内容是过程，不是问句。 */
    private static final Pattern THINK_BLOCK =
        Pattern.compile("(?s)<think[^>]*>.*?</think>", Pattern.CASE_INSENSITIVE);

    /** 行首编号/项目符号。 */
    private static final String LEADING_NOISE = "0123456789.-*) ";

    /**
     * 最短问句长度 —— <b>按 UTF-8 字节计</b>，与源侧 {@code len(line) > 5} 同口径。
     *
     * <p>⚠️ Go 的 {@code len()} 是<b>字节</b>，Java 的 {@code length()} 是 UTF-16 单元。
     * 照字面把 5 搬成 {@code codePointCount > 5} 会变得比源侧严得多：
     * 中文 5 个字 = 15 字节（源侧保留），但只有 5 个 code point（会误删）。
     * ⇒ 必须用 {@code getBytes(UTF_8).length}。
     */
    static final int MIN_QUESTION_BYTES = 5;

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper chunkMapper;
    private final KbKnowledgeStateRepository stateRepository;
    private final KbIndexWriter indexWriter;
    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    public KbQuestionGenerationService(
        KbKnowledgeMapper knowledgeMapper,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbChunkMapper chunkMapper,
        KbKnowledgeStateRepository stateRepository,
        KbIndexWriter indexWriter,
        LlmSideCallClient sideCallClient
    ) {
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.chunkMapper = chunkMapper;
        this.stateRepository = stateRepository;
        this.indexWriter = indexWriter;
        this.sideCallClient = sideCallClient;
    }

    /**
     * 为一批分块生成问句。
     *
     * @param knowledgeId   文档 ID
     * @param attempt       本轮解析 attempt
     * @param batchIndex    批次序号（仅用于日志）
     * @param chunkIds      本批分块 ID（按文档顺序）
     * @param questionCount 每块生成几条（调用方已夹到 1..10）
     * @return 结果（永不 null、永不抛）
     */
    public KbPostProcessOutcome generateBatch(
        String knowledgeId, int attempt, int batchIndex,
        List<String> chunkIds, int questionCount
    ) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return KbPostProcessOutcome.skipped("empty_batch");
        }

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
            || KbKnowledgeStateRepository.STATUS_CANCELLED.equals(status)) {
            return KbPostProcessOutcome.skipped("文档处于 " + status);
        }
        String kbId = knowledge.getKnowledgeBaseId();
        KbKnowledgeBaseRecord kb = kbId == null || kbId.isBlank()
            ? null : knowledgeBaseMapper.selectOneById(kbId);
        if (kb == null) {
            return KbPostProcessOutcome.skipped("kb_not_found");
        }
        KbPostProcessConfig config = KbPostProcessConfig.resolve(kb,
            modelConfigResolver == null ? null
                : modelConfigResolver.settingsTierModelName(
                    com.nexusai.repository.settings.entity.SettingsRecord::getMainModelName));
        if (!config.question().runnable()) {
            return KbPostProcessOutcome.skipped("question_disabled");
        }

        // ── 模型 ──────────────────────────────────────────────────────
        String modelId = config.question().modelId();
        // ⚠️ 传给模型 API 的必须是 **models.name**（provider 侧真实模型名），
        //    而 `question_generation_config.model_id` / 继承来的 `summary_model_id`
        //    存的是 **models.id**。两者混用会真的打到对方 API 上并 400：
        //    实测 `The supported API model names are deepseek-flash, deepseek-v4-pro,
        //    but you passed model-0823a98d`。
        //    ⇒ 与 KbSummaryService / KbGraphExtractionService / Wiki 三阶段同构：
        //      解析后取 `apiModelName`，非空白才覆盖，否则回退原值。
        ModelConfigResolver.ResolvedModel resolved = resolveModel(modelId);
        ProviderConfig provider = resolved == null ? null : resolved.config();
        String apiModelName = resolved != null
            && resolved.apiModelName() != null && !resolved.apiModelName().isBlank()
            ? resolved.apiModelName() : modelId;
        if (provider == null) {
            return KbPostProcessOutcome.skipped("provider_not_configured");
        }
        int limit = Math.max(1, Math.min(10, questionCount));

        // ── 逐块处理 ──────────────────────────────────────────────────
        int processed = 0;
        int failed = 0;
        int emptyChunks = 0;
        int total = 0;

        for (int i = 0; i < chunkIds.size(); i++) {
            String chunkId = chunkIds.get(i);
            KbChunkRecord chunk = chunkMapper.selectOneById(chunkId);
            if (chunk == null) {
                emptyChunks++;
                continue;
            }
            String content = chunk.getContent();
            if (!KbChunkText.hasExtractableText(content)) {
                // ⭐ 图片占位块：模型只会对着 URL 编造，也容易复述 few-shot 示例
                emptyChunks++;
                continue;
            }

            // 前后文：批内相邻块；批次边界取库里 chunk_index 前后各一（同文档）
            String prev = i > 0 ? contentOf(chunkIds.get(i - 1)) : neighborContent(chunk, -1);
            String next = i + 1 < chunkIds.size()
                ? contentOf(chunkIds.get(i + 1)) : neighborContent(chunk, +1);

            int revisionAtCall = chunk.getContentRevision() == null ? 0 : chunk.getContentRevision();
            List<String> questions;
            try {
                String prompt = KbPostProcessPrompts.questionPrompt(
                    content, prev, next, knowledge.getTitle(), limit,
                    config.question().customInstructions());
                String raw = sideCallClient.chat(
                    provider, apiModelName, null, prompt,
                    options(TEMPERATURE, MAX_TOKENS, "question_generation"),
                    LlmSideCallClient.SideCallUsage.standalone(
                        UsageCategory.SIDE_CALL, "question_generation"));
                questions = parseQuestions(raw, limit);
            } catch (RuntimeException e) {
                failed++;
                log.warn("[问句] 模型调用失败 knowledge={} chunk={} 原因={}",
                    knowledgeId, chunkId, e.getMessage());
                continue;
            }
            if (questions.isEmpty()) {
                continue;
            }

            // ⭐ 写回前重读 revision：生成期间用户可能已编辑该块
            KbChunkRecord latest = chunkMapper.selectOneById(chunkId);
            if (latest == null) {
                continue;
            }
            int nowRevision = latest.getContentRevision() == null ? 0 : latest.getContentRevision();
            if (nowRevision != revisionAtCall) {
                log.info("[问句] 分块在生成期间被编辑，跳过 chunk={} 版本 {}→{}",
                    chunkId, revisionAtCall, nowRevision);
                continue;
            }

            String merged = mergeQuestions(latest.getMetadata(), questions, nowRevision);
            KbChunkRecord update = new KbChunkRecord();
            update.setId(chunkId);
            update.setMetadata(merged);
            update.setUpdateTime(KbTimeFormat.parse(KbTimeFormat.now()));
            chunkMapper.update(update);

            processed++;
            total += questions.size();
        }

        // ── 全部块都失败（且一块都没成功）⇒ 交给队列重试 ────────────
        if (processed == 0 && failed > 0) {
            return KbPostProcessOutcome.failed(
                "全部块生成失败 batch=" + batchIndex + " failed=" + failed);
        }
        if (processed == 0) {
            return KbPostProcessOutcome.skipped(
                "无可生成块 batch=" + batchIndex + " 空块=" + emptyChunks);
        }

        // ── 重建索引（问句要能被检索到）──────────────────────────────
        try {
            indexWriter.reindexKnowledge(knowledgeId, kbId,
                resolveEmbeddingModelId(knowledge, kb));
        } catch (RuntimeException e) {
            // 问句已写进 metadata，索引失败重试会重复调模型 ⇒ 记日志但不判失败
            log.warn("[问句] 重建索引失败（问句已落库）knowledge={} 原因={}",
                knowledgeId, e.getMessage());
        }
        log.info("[问句] 批次完成 knowledge={} batch={} 处理块={} 跳过块={} 失败块={} 问句数={}",
            knowledgeId, batchIndex, processed, emptyChunks, failed, total);
        return KbPostProcessOutcome.done(
            "questions=" + total + " chunks=" + processed + " failed=" + failed);
    }

    // ══════════════════════════════════════════════════════════
    // 纯函数（单测直接打）
    // ══════════════════════════════════════════════════════════

    /**
     * 解析模型输出为问句列表。
     *
     * <p>顺序：去推理块 → 按行拆 → trim → 去行首编号/符号 → 再过短过滤 → 截到 limit。
     *
     * @param raw   模型原文
     * @param limit 最多几条
     * @return 问句列表（永不 null）
     */
    static List<String> parseQuestions(String raw, int limit) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || limit <= 0) {
            return out;
        }
        String cleaned = THINK_BLOCK.matcher(raw).replaceAll(" ");
        for (String line : cleaned.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            // 去行首编号：「1. 」「- 」「* 」「) 」
            int i = 0;
            while (i < s.length() && LEADING_NOISE.indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            s = s.substring(i).trim();
            if (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MIN_QUESTION_BYTES) {
                continue;
            }
            out.add(s);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /**
     * 把问句合并进 chunk.metadata（保留原有其它 key）。
     *
     * <p>⭐⭐ 必须 merge，不能整体覆盖 —— metadata 里还有 {@code faq}、{@code image} 等
     * 其它子系统写的字段，覆盖掉会让它们静默丢失。
     *
     * @param oldJson   原 metadata（可 null / 坏 JSON ⇒ 视为空对象）
     * @param questions 问句列表
     * @param revision  生成时的 contentRevision
     * @return 新 metadata JSON
     */
    static String mergeQuestions(String oldJson, List<String> questions, int revision) {
        ObjectNode root = JSON.createObjectNode();
        if (oldJson != null && !oldJson.isBlank()) {
            try {
                JsonNode n = JSON.readTree(oldJson);
                if (n != null && n.isObject()) {
                    root = (ObjectNode) n;
                }
            } catch (Exception e) {
                log.warn("[问句] 原 metadata 不是合法 JSON，按空对象处理 原因={}", e.getMessage());
            }
        }
        ArrayNode arr = JSON.createArrayNode();
        for (String q : questions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "q" + Math.abs((q + revision).hashCode()));
            item.put("question", q);
            item.put("content_revision", revision);
            arr.add(JSON.valueToTree(item));
        }
        root.set(KbGeneratedQuestions.KEY, arr);
        root.put("generated_questions_revision", revision);
        return root.toString();
    }

    // ══════════════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════════════

    private String contentOf(String chunkId) {
        if (chunkId == null) {
            return null;
        }
        KbChunkRecord c = chunkMapper.selectOneById(chunkId);
        return c == null ? null : c.getContent();
    }

    /**
     * 批次边界的上下文：按 {@code chunk_index} 找相邻块。
     *
     * @param chunk    当前块
     * @param delta    -1 前一块 / +1 后一块
     * @return 相邻块正文；不存在 ⇒ null
     */
    private String neighborContent(KbChunkRecord chunk, int delta) {
        Integer index = chunk.getChunkIndex();
        if (index == null) {
            return null;
        }
        List<KbChunkRecord> found = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", chunk.getKnowledgeId())
                .and("chunk_type = ?", "text")
                .and("chunk_index = ?", index + delta)
                .limit(1));
        return found.isEmpty() ? null : found.get(0).getContent();
    }

    private static String resolveEmbeddingModelId(
        KbKnowledgeRecord knowledge, KbKnowledgeBaseRecord kb
    ) {
        String own = knowledge.getEmbeddingModelId();
        if (own != null && !own.isBlank()) {
            return own;
        }
        if (kb != null && kb.getEmbeddingModelId() != null && !kb.getEmbeddingModelId().isBlank()) {
            return kb.getEmbeddingModelId();
        }
        return null;
    }

    /**
     * 解析「模型 ID / 名」→ 真实配置 + 真实模型名。
     *
     * <p>⚠️ 必须整份返回 {@code ResolvedModel} 而不是只留 {@code config()}：
     * 调 API 要的是 **models.name**，而库里存的是 **models.id**（见调用点注释）。
     */
    private ModelConfigResolver.ResolvedModel resolveModel(String modelName) {
        if (modelConfigResolver == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        try {
            return modelConfigResolver.resolve(modelName);
        } catch (RuntimeException e) {
            log.warn("[问句] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    private ProviderConfig resolveConfig(String modelName) {
        if (modelConfigResolver == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        try {
            ModelConfigResolver.ResolvedModel r = modelConfigResolver.resolve(modelName);
            return r == null ? null : r.config();
        } catch (RuntimeException e) {
            log.warn("[问句] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
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
