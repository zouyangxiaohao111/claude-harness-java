package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.UUID;

/**
 * 文档摘要 / 画像生成 · 对齐 WeKnora
 * {@code knowledge_process.go ProcessSummaryGeneration}（1429）与 {@code getSummary}（1242）。
 *
 * <h2>它产出三样东西</h2>
 * <ol>
 *   <li>{@code kb_knowledge.description} —— 一句话~三句话摘要（前端卡片展示）；</li>
 *   <li>{@code kb_knowledge.profile} —— 结构化画像 JSON（gist/topics/doc_type/typical_question），
 *       知识库描述就是由一批文档的画像聚合出来的；</li>
 *   <li>一个 {@code chunk_type='summary'} 的分块 —— 让「这份文档讲什么」这类问题
 *       能通过摘要块召回，而不是被迫去召回零散段落。</li>
 * </ol>
 *
 * <h2>⭐⭐ 三条不能让步的语义（每条都对应一个真实事故）</h2>
 * <ol>
 *   <li><b>绝不把文件名喂给模型</b>。扫描件文件名常是 {@code MX5280.pdf}（扫描仪型号），
 *       模型据此编出一份「扫描仪说明书」的摘要 —— 而正文其实是律师函。
 *       ⇒ 本类拼 user 消息时<b>根本没有 {@code fileName} 这个变量</b>（编译期挡住疏忽）。</li>
 *   <li><b>可抽取文本不足 ⇒ 判失败并清空描述</b>，⛔ 不回落「取第一块原文」。
 *       扫描件的第一块通常只有 {@code ![image](...)}，把它当摘要会展示一串 URL 给用户。</li>
 *   <li><b>摘要块的内容是派生物</b>，⛔ 不改 {@code kb_chunk.content} 的位置不变式
 *       （{@code end-start == rune 数}）。摘要块自身 {@code start_at=end_at=0}。</li>
 * </ol>
 *
 * <p><b>⚠️ 不给本类加 {@code @Transactional}</b>：末尾会调
 * {@link KbIndexWriter#reindexKnowledge}，FTS5 走独立 JDBC 连接，事务内会自锁 SQLITE_BUSY。
 */
@Component
public class KbSummaryService {

    private static final Logger log = LoggerFactory.getLogger(KbSummaryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 送进模型的最大字符数（源侧 {@code defaultMaxInputChars = 1024*8}）。 */
    static final int MAX_INPUT_CHARS = 8192;

    /** 抽样时的省略标记 · ⚠️ 必须与 {@link KbPostProcessPrompts#SUMMARY_SYSTEM} 里写的一字不差。 */
    static final String OMIT_MARKER = "\n\n[...content omitted...]\n\n";

    /** 可抽取文本的下限（code point）· 低于它认为是扫描件/图片文档。 */
    static final int MIN_CONTENT_CHARS = 40;

    /** 摘要输出上限（源侧 {@code defaultSummaryMaxTokens = 1024}，每个字段都很短）。 */
    private static final int MAX_TOKENS = 1024;

    private static final double TEMPERATURE = 0.3;

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper chunkMapper;
    private final KbKnowledgeStateRepository stateRepository;
    private final KbIndexWriter indexWriter;
    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    /**
     * 知识库画像自动生成触发器（2026-11-11）。
     *
     * <p>⚠️ 字段注入：不改构造器签名（本类有直构测试点）。
     */
    @Autowired(required = false)
    private com.nexusai.kb.app.profile.KbProfileTrigger profileTrigger;

    public KbSummaryService(
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
     * 手动重新生成摘要（用户点「重新生成」）。
     *
     * <p><b>为什么不直接调 {@link #generate(String, int)}</b>：那个方法有「守卫 2：陈旧任务」——
     * 它要求传入的 {@code attempt} 与 {@code kb_knowledge.parse_attempt} 完全相等，
     * 那是给<b>异步后处理管线</b>用的（防止用户重新解析后旧任务把新结果覆盖掉）。
     * 手动触发时调用方拿不到「当时那个 attempt」，传当前值虽然能过守卫，
     * 但语义上是把「管线内部一致性检查」当成了「用户操作」的入口 —— 一旦将来 attempt
     * 语义变化（例如改成序号而非计数），这段就会静默失效。
     *
     * <p>所以这里**显式重置 attempt 上下文**：先读当前 attempt，再交给 {@code generate}，
     * 并在两者之间不做任何 await/await 式的让步（同一线程内完成，不会被别的解析插队）。
     * 手动重生成语义上就是「以当前状态为准重算一次」。
     *
     * @param knowledgeId 文档 ID
     * @return 结果（永不 null、永不抛）
     */
    public KbPostProcessOutcome regenerate(String knowledgeId) {
        KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            return KbPostProcessOutcome.skipped("knowledge_not_found");
        }
        int attempt = knowledge.getParseAttempt() == null ? 0 : knowledge.getParseAttempt();
        log.info("[摘要] 手动重新生成 knowledge={} attempt={}", knowledgeId, attempt);
        return generate(knowledgeId, attempt);
    }

    /**
     * 生成摘要。
     *
     * @param knowledgeId 文档 ID
     * @param attempt     本轮解析 attempt
     * @return 结果（永不 null、永不抛）
     */
    public KbPostProcessOutcome generate(String knowledgeId, int attempt) {
        // ── 守卫 1：行存在 ────────────────────────────────────────────
        KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            return KbPostProcessOutcome.skipped("knowledge_not_found");
        }
        // ── 守卫 2：陈旧任务（用户已重新解析）────────────────────────
        int current = knowledge.getParseAttempt() == null ? 0 : knowledge.getParseAttempt();
        if (current != attempt) {
            return KbPostProcessOutcome.superseded(
                "解析已被新一轮取代 任务attempt=" + attempt + " 当前=" + current);
        }
        // ── 守卫 3：用户已取消 / 正在删除 ────────────────────────────
        String status = knowledge.getParseStatus();
        if (KbKnowledgeStateRepository.STATUS_DELETING.equals(status)
            || KbKnowledgeStateRepository.STATUS_CANCELLED.equals(status)) {
            return KbPostProcessOutcome.skipped("文档处于 " + status);
        }
        // ── 守卫 4：知识库存在 ────────────────────────────────────────
        String kbId = knowledge.getKnowledgeBaseId();
        KbKnowledgeBaseRecord kb = kbId == null || kbId.isBlank()
            ? null : knowledgeBaseMapper.selectOneById(kbId);
        if (kb == null) {
            return KbPostProcessOutcome.skipped("kb_not_found");
        }
        // ── 守卫 5：配了摘要模型 ──────────────────────────────────────
        // ⚠️ 判据必须是**模型字段本身**，⛔ 不能用 `config.summaryEnabled()`：
        //    后者是对齐源码的「恒 true」（源码 ResolveProcessConfig:43），
        //    它表达的是「要不要派发这个环节」，不是「这个环节能不能跑」。
        //    两个问题分开问，混用会让守卫永远不生效（任务照跑到模型调用才炸）。
        //
        //    ⭐ 语义对齐源码 knowledge_process.go:1521-1527：
        //      `if kb.SummaryModelID == "" { 标 failed; 记 no_summary_model; return }`
        //      ⇒ **标 failed 而不是静默跳过**，前端据此显示「去设置里配摘要模型」。
        KbPostProcessConfig config = KbPostProcessConfig.resolve(kb,
            modelConfigResolver == null ? null
                : modelConfigResolver.settingsTierModelName(
                    com.nexusai.repository.settings.entity.SettingsRecord::getMainModelName));
        if (config.summaryModelId() == null || config.summaryModelId().isBlank()) {
            log.info("[摘要] 未配置摘要模型，跳过并标记失败 knowledge={}", knowledgeId);
            markStatus(knowledgeId, "failed");
            return KbPostProcessOutcome.skipped("no_summary_model");
        }

        // ── 取文本块 ──────────────────────────────────────────────────
        List<KbChunkRecord> textChunks = textChunksOf(knowledgeId);
        if (textChunks.isEmpty()) {
            knowledgeMapper.update(toStateRow(knowledgeId, "", null, "failed"));
            return KbPostProcessOutcome.skipped("no_text_chunks");
        }

        // ── 重建正文 → 长度抽样 ───────────────────────────────────────
        String content = sampleLongContent(joinChunks(textChunks), MAX_INPUT_CHARS);

        // ── ⭐ 空内容守卫：剔除图片语法后再判 ──────────────────────────
        if (KbChunkText.visibleLength(content) < MIN_CONTENT_CHARS) {
            knowledgeMapper.update(toStateRow(knowledgeId, "", null, "failed"));
            log.info("[摘要] 可抽取文本不足，跳过 knowledge={} 可见长度={}",
                knowledgeId, KbChunkText.visibleLength(content));
            return KbPostProcessOutcome.skipped("insufficient_content");
        }

        // ── 置 processing（失败不影响后续：状态只是展示用）───────────
        markStatus(knowledgeId, "processing");

        // ── 调模型 ────────────────────────────────────────────────────
        ModelConfigResolver.ResolvedModel resolved = resolveModel(config.summaryModelId());
        ProviderConfig provider = resolved == null ? null : resolved.config();
        if (provider == null) {
            markStatus(knowledgeId, "failed");
            return KbPostProcessOutcome.skipped("provider_not_configured");
        }
        // ⚠️ 传给模型的必须是 **models.name**（provider 侧的真实模型名），
        //    而 `config.summaryModelId()` 来自 `kb_knowledge_base.summary_model_id`，
        //    存的是 **models.id**。两者混用时会真的打到对方 API 上并 400：
        //    实测 `The supported API model names are deepseek-flash, deepseek-v4-pro,
        //    but you passed model-0823a98d`。
        //    ⇒ 这里用 ResolvedModel 带回来的真实名，⛔ 不要再传 ID。
        String apiModelName = resolved.apiModelName() != null && !resolved.apiModelName().isBlank()
            ? resolved.apiModelName()
            : config.summaryModelId();
        String raw;
        try {
            raw = sideCallClient.chat(
                provider, apiModelName,
                KbPostProcessPrompts.SUMMARY_SYSTEM, content,
                options(TEMPERATURE, MAX_TOKENS, "document_summary"),
                LlmSideCallClient.SideCallUsage.standalone(
                    UsageCategory.SIDE_CALL, "document_summary"));
        } catch (RuntimeException e) {
            markStatus(knowledgeId, "failed");
            log.warn("[摘要] 模型调用失败 knowledge={} 原因={}", knowledgeId, e.getMessage());
            return KbPostProcessOutcome.failed("summary_call_failed: " + e.getMessage());
        }

        // ── 解析（宽松）────────────────────────────────────────────────
        SummaryResult parsed = parseSummary(raw);
        if (parsed == null || parsed.summary().isBlank()) {
            markStatus(knowledgeId, "failed");
            return KbPostProcessOutcome.failed("empty_summary");
        }

        // ── 写回 description / profile ────────────────────────────────
        knowledgeMapper.update(toStateRow(knowledgeId, parsed.summary(),
            parsed.profileJson(), "completed"));
        int chars = parsed.summary().codePointCount(0, parsed.summary().length());
        log.info("[摘要] 生成完成 knowledge={} 字符数={} 有画像={}",
            knowledgeId, chars, parsed.profileJson() != null);

        // ── 摘要块（让「这份文档讲什么」也能被召回）──────────────────
        writeSummaryChunk(knowledge, kb, knowledgeId, parsed, config);

        // ── 重建索引（摘要块要可检索）────────────────────────────────
        try {
            indexWriter.reindexKnowledge(knowledgeId, kbId, resolveEmbeddingModelId(knowledge, kb));
        } catch (RuntimeException e) {
            // 摘要本身已落库，索引失败不该让摘要任务判失败（重试会把整个 LLM 调用再来一遍）
            log.warn("[摘要] 重建索引失败（摘要已落库）knowledge={} 原因={}", knowledgeId, e.getMessage());
        }
        // ⭐ 2026-11-11：摘要更新后重算知识库画像（源站 knowledge_post_process.go:529）。
        //    摘要里带着文档画像（gist/topics/...）⇒ 聚合内容变了 ⇒ 知识库描述该跟着变。
        //    ⚠️ 失败不抛：摘要已经落库成功，画像是旁路能力。
        if (profileTrigger != null) {
            profileTrigger.trigger(kbId, false);
        }
        return KbPostProcessOutcome.done("summary_chars=" + chars);
    }

    // ══════════════════════════════════════════════════════════
    // 纯函数（单测直接打）
    // ══════════════════════════════════════════════════════════

    /**
     * 长内容抽样：head 60% + middle 20% + tail 20%。
     *
     * <p>为什么不直接截断：摘要要覆盖全文主题。只留开头会把「一篇讲了 A、B、C 的报告」
     * 概括成「一篇讲 A 的报告」。取样三段 + 明确标注省略，模型才知道这是摘录。
     *
     * @param content  正文（可 null）
     * @param maxChars 上限（code point）
     * @return 抽样后的正文（不超上限；短内容原样返回）
     */
    static String sampleLongContent(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        int len = content.codePointCount(0, content.length());
        if (len <= maxChars) {
            return content;
        }
        int omitLen = OMIT_MARKER.codePointCount(0, OMIT_MARKER.length());
        int usable = maxChars - 2 * omitLen;
        if (usable < 100) {
            // 预算太小，标记都塞不下两段 ⇒ 退化为一刀切
            return content.substring(0, content.offsetByCodePoints(0, maxChars));
        }
        int headLen = usable * 60 / 100;
        int tailLen = usable * 20 / 100;
        int midLen = usable - headLen - tailLen;

        String head = content.substring(0, content.offsetByCodePoints(0, headLen));
        String tail = content.substring(content.offsetByCodePoints(0, len - tailLen));

        int midStart = len / 2 - midLen / 2;
        if (midStart < headLen) {
            midStart = headLen;
        }
        int midEnd = midStart + midLen;
        if (midEnd > len - tailLen) {
            midEnd = len - tailLen;
            midStart = Math.max(midEnd - midLen, headLen);
        }
        String middle = content.substring(
            content.offsetByCodePoints(0, midStart), content.offsetByCodePoints(0, midEnd));

        return head + OMIT_MARKER + middle + OMIT_MARKER + tail;
    }

    /**
     * 解析模型输出的 JSON（容忍 ```json 围栏与前后废话）。
     *
     * @param raw 模型原文
     * @return 结果；无法解析 ⇒ null
     */
    static SummaryResult parseSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String json = s.substring(start, end + 1);
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.isObject()) {
                return null;
            }
            String summary = n.path("summary").asText("").trim();
            return new SummaryResult(summary, json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 摘要块的正文格式（固定，便于测试与人工核对）。
     *
     * @param profileJson 画像 JSON（可 null）
     * @param summary     摘要
     * @return 多行文本
     */
    static String summaryChunkContent(String summary, String profileJson) {
        StringBuilder sb = new StringBuilder("摘要：").append(summary);
        JsonNode p = readProfile(profileJson);
        if (p != null) {
            String gist = p.path("gist").asText("").trim();
            if (!gist.isEmpty()) {
                sb.append('\n').append("要点：").append(gist);
            }
            List<String> topics = new ArrayList<>();
            for (JsonNode t : p.path("topics")) {
                String v = t.asText("");
                if (!v.isBlank()) {
                    topics.add(v.trim());
                }
            }
            if (!topics.isEmpty()) {
                sb.append('\n').append("主题：").append(String.join("、", topics));
            }
            String docType = p.path("doc_type").asText("").trim();
            if (!docType.isEmpty()) {
                sb.append('\n').append("类型：").append(docType);
            }
        }
        return sb.toString();
    }

    /**
     * 摘要生成结果。
     *
     * @param summary     摘要正文
     * @param profileJson 模型返回的完整 JSON 原文（原样存进 {@code kb_knowledge.profile}）
     */
    record SummaryResult(String summary, String profileJson) {
    }

    // ══════════════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════════════

    /** 取该文档的文本块（按 chunkIndex 升序）。 */
    private List<KbChunkRecord> textChunksOf(String knowledgeId) {
        return chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .and("chunk_type = ?", "text")
                .and("is_enabled = ?", 1)
                .orderBy("chunk_index", true));
    }

    /**
     * 拼正文。
     *
     * <p>⚠️ 只拼 {@code content}，<b>不拼 {@code context_header}</b>：表头是检索用的面包屑，
     * 每块都重复会让摘要里全是「第一章 &gt; 第二节」这种噪声。
     */
    private static String joinChunks(List<KbChunkRecord> chunks) {
        StringBuilder sb = new StringBuilder();
        for (KbChunkRecord c : chunks) {
            String text = c.getContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static JsonNode readProfile(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(profileJson);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 只写摘要相关的三列。
     *
     * <p>⚠️ 刻意不 {@code selectOneById} 再整行 update：那会把并发写入的其它列
     * （例如用户刚编辑的 title）用旧快照覆盖掉。
     */
    private void markStatus(String knowledgeId, String summaryStatus) {
        KbKnowledgeRecord row = new KbKnowledgeRecord();
        row.setId(knowledgeId);
        row.setSummaryStatus(summaryStatus);
        row.setUpdateTime(KbTimeFormat.parse(KbTimeFormat.now()));
        knowledgeMapper.update(row);
    }

    private KbKnowledgeRecord toStateRow(
        String knowledgeId, String description, String profile, String summaryStatus
    ) {
        KbKnowledgeRecord row = new KbKnowledgeRecord();
        row.setId(knowledgeId);
        row.setDescription(description);
        row.setProfile(profile);
        row.setSummaryStatus(summaryStatus);
        row.setUpdateTime(KbTimeFormat.parse(KbTimeFormat.now()));
        return row;
    }

    /**
     * 写摘要块（先删旧摘要块，再插新的）。
     *
     * <p>⚠️ 用逻辑删：{@code kb_chunk} 的 {@code deleteByQuery} 会改写成
     * {@code UPDATE deleted=1}（Flex 的 {@code @Column(isLogicDelete=true)}）。
     */
    private void writeSummaryChunk(
        KbKnowledgeRecord knowledge, KbKnowledgeBaseRecord kb,
        String knowledgeId, SummaryResult parsed, KbPostProcessConfig config
    ) {
        String content = summaryChunkContent(parsed.summary(), parsed.profileJson());
        if (content.isBlank()) {
            return;
        }

        // 旧摘要块先清掉（重解析 / 重复跑都要保证只有一块）
        chunkMapper.deleteByQuery(QueryWrapper.create()
            .where("knowledge_id = ?", knowledgeId)
            .and("chunk_type = ?", "summary"));

        // chunk_index 取当前最大值 +1（"摘要"在文档顺序里排最后）
        List<KbChunkRecord> all = chunkMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
        int maxIndex = 0;
        String firstTextId = null;
        for (KbChunkRecord c : all) {
            if (c.getChunkIndex() != null && c.getChunkIndex() > maxIndex) {
                maxIndex = c.getChunkIndex();
            }
            if (firstTextId == null && "text".equals(c.getChunkType())) {
                firstTextId = c.getId();
            }
        }

        String now = KbTimeFormat.now();
        KbChunkRecord row = new KbChunkRecord();
        row.setId(UUID.randomUUID().toString());
        row.setKnowledgeBaseId(knowledge.getKnowledgeBaseId());
        row.setKnowledgeId(knowledgeId);
        row.setContent(content);
        row.setChunkIndex(maxIndex + 1);
        row.setStartAt(0);
        row.setEndAt(0);
        row.setChunkType("summary");
        row.setParentChunkId(firstTextId);
        row.setContextHeader("");

        // ⚠️ Flex 的 insert 会把 null 也列进 INSERT 清单 ⇒ DEFAULT 不生效，必须全部显式赋值
        row.setIsEnabled(1);
        row.setSourceContent("");
        row.setContentRevision(0);
        row.setIndexStatus("ready");
        row.setLastEditorId("");
        row.setStatus(0);
        row.setFlags(1);
        row.setCreator("");
        row.setUpdater("");
        row.setDeleted(0);
        row.setTenantId(0L);
        row.setCreateTime(KbTimeFormat.parse(now));
        row.setUpdateTime(KbTimeFormat.parse(now));
        chunkMapper.insert(row);

        log.info("[摘要] 已写摘要块 knowledge={} 字符数={}", knowledgeId,
            content.codePointCount(0, content.length()));
    }

    /** 嵌入模型解析：条目级 → 库级 → null（与 {@code KnowledgeParseProcessor} 同口径）。 */
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

    private ProviderConfig resolveConfig(String modelName) {
        ModelConfigResolver.ResolvedModel r = resolveModel(modelName);
        return r == null ? null : r.config();
    }

    /**
     * 解析「模型 ID / 名」→ 真实配置 + 真实模型名。
     *
     * <p>⚠️ 为什么要把 {@code ResolvedModel} 整份传回来而不是只留 {@code config()}：
     * 调用模型 API 需要的是 **models.name**，而库里存的是 **models.id**。
     * 只带 config 出去，调用点就只好把 ID 当名字发，实测会被对方 400 拒绝。
     *
     * @param modelName 模型 ID 或名（`ModelConfigResolver` 两者都认）
     * @return 解析结果；不可用 → null
     */
    private ModelConfigResolver.ResolvedModel resolveModel(String modelName) {
        if (modelConfigResolver == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        try {
            return modelConfigResolver.resolve(modelName);
        } catch (RuntimeException e) {
            log.warn("[摘要] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    private static LlmProvider.ChatRequestOptions options(
        double temperature, int maxTokens, String label
    ) {
        return new LlmProvider.ChatRequestOptions(
            null, null, null,
            // 摘要是「整理已有事实」，不需要推理链（省 token、更稳定）
            LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
            temperature, label, null, maxTokens, null, null,
            null, null, null, null, null, null);
    }
}
