package com.nexusai.kb.app.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbEmbeddingPort;
import com.nexusai.kb.app.retrieval.KbVectorCodec;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbImageEmbeddingRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbImageEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 图片向量通道（{@code kb_image_embedding}）· 两档语义，<b>不伪造图片语义向量</b>。
 *
 * <h2>⭐ 两档行为</h2>
 * <ol>
 *   <li><b>已配置</b> {@code nexusai.knowledge.image-vector.model}：把该图的
 *       {@code caption} / {@code ocr_text} <b>分别</b>按文本模型编码
 *       （{@code source = caption_text / ocr_text}）。本仓没有多模态 embedding 模型，
 *       所以这不是「图片语义向量」，而是「图片内文字/描述」的向量 —— 如实标注来源。</li>
 *   <li><b>未配置</b>：服务照常可用（不抛异常），但图片向量查询明确返回「未配置」
 *       并留 info 日志，⛔ 不写假向量、⛔ 不返回假数据。</li>
 * </ol>
 *
 * <h2>编码通道</h2>
 * 复用检索层既有的 {@link KbEmbeddingPort}（生产实现转调 {@code EmbeddingService}，
 * 与 {@code KbIndexWriter} 完全同一条链）——⛔ 不新建 provider、不新起模型通路。
 *
 * <h2>去重与失败语义</h2>
 * <ul>
 *   <li>唯一键 {@code (knowledge_base_id, image_key, source, model_id)}；
 *       重跑索引时若 {@code content_hash} 未变 ⇒ 跳过（不重复编码）。</li>
 *   <li>逐条编码，单条失败只 WARN，不影响其它图片（fail-soft）。
 *       失败的那条不写行 —— 宁可少一条，也不要一条错的。</li>
 * </ul>
 *
 * <h2>⚠️ 不改现有检索链</h2>
 * 本类只写/读自己的表；{@code KbIndexWriter} / {@code KbHybridSearcher} /
 * {@code KbSqliteRetrievalEngine} / {@code KbImageEnrichService} 一律不动。
 * 查询结果需由调用方自行决定如何融合（本类不算分、不改权重）。
 */
@Service
public class KbImageVectorService {

    private static final Logger log = LoggerFactory.getLogger(KbImageVectorService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 配置项：图片向量模型 ID（空 = 未配置）。 */
    public static final String MODEL_PROPERTY = "nexusai.knowledge.image-vector.model";

    /** 来源：caption 文本。 */
    public static final String SOURCE_CAPTION = "caption_text";
    /** 来源：OCR 文本。 */
    public static final String SOURCE_OCR = "ocr_text";

    private final KbChunkMapper chunkMapper;
    private final KbImageEmbeddingMapper embeddingMapper;
    private final KbEmbeddingPort embeddingPort;

    /**
     * 图片向量模型 ID。
     *
     * <p>⚠️ 用字段注入而不是构造器参数：本类有「生产 3 参 + 单测 4 参」两个构造器，
     * 再塞一个 {@code @Value} 参数只会让装配更难判（同 {@code KbImageEnrichService} 的做法）。
     * 单测直构时由 4 参构造器显式赋值。
     */
    @Value("${" + MODEL_PROPERTY + ":}")
    private String configuredModel = "";

    /** 生产构造器（Spring 注入）。 */
    @Autowired
    public KbImageVectorService(KbChunkMapper chunkMapper,
                                KbImageEmbeddingMapper embeddingMapper,
                                KbEmbeddingPort embeddingPort) {
        this.chunkMapper = chunkMapper;
        this.embeddingMapper = embeddingMapper;
        this.embeddingPort = embeddingPort;
    }

    /** 单测构造器：显式给出模型 ID（不走 Spring 配置注入）。 */
    public KbImageVectorService(KbChunkMapper chunkMapper,
                                KbImageEmbeddingMapper embeddingMapper,
                                KbEmbeddingPort embeddingPort,
                                String configuredModel) {
        this(chunkMapper, embeddingMapper, embeddingPort);
        this.configuredModel = configuredModel == null ? "" : configuredModel;
    }

    /**
     * 是否已配置图片向量模型。
     *
     * @return 配置为空（null/空白）⇒ {@code false}
     */
    public boolean isConfigured() {
        return configuredModel != null && !configuredModel.isBlank();
    }

    /**
     * 为某文档的全部含图分块建图片向量（caption 与 OCR 分别编码）。
     *
     * @param knowledgeId 知识条目 ID
     * @param kbId        知识库 ID
     * @param modelId     本次使用的模型 ID（空 ⇒ 回落到配置项；配置也为空 ⇒ 明确「未配置」）
     * @return 索引统计（写/跳过/失败/耗时）
     */
    public IndexOutcome indexKnowledge(String knowledgeId, String kbId, String modelId) {
        long startedNs = System.nanoTime();
        String model = modelId != null && !modelId.isBlank() ? modelId : configuredModel;
        if (model == null || model.isBlank()) {
            log.info("[图片向量] 未配置模型（{}），不写任何向量 knowledge={} 库={}",
                MODEL_PROPERTY, knowledgeId, kbId);
            return new IndexOutcome(false, 0, 0, 0, 0, elapsedMs(startedNs));
        }
        if (isBlank(knowledgeId) || isBlank(kbId)) {
            log.info("[图片向量] 文档/知识库 ID 为空，跳过 knowledge={} 库={}", knowledgeId, kbId);
            return new IndexOutcome(true, 0, 0, 0, 0, elapsedMs(startedNs));
        }

        List<KbChunkRecord> chunks;
        try {
            chunks = chunkMapper.selectListByQuery(
                QueryWrapper.create()
                    .where("knowledge_id = ?", knowledgeId)
                    .and("knowledge_base_id = ?", kbId));
        } catch (RuntimeException e) {
            log.warn("[图片向量] 读分块失败，本次跳过 knowledge={} 库={} 原因={}",
                knowledgeId, kbId, e.toString());
            return new IndexOutcome(true, 0, 0, 0, 0, elapsedMs(startedNs));
        }

        Map<String, KbImageEmbeddingRecord> existing = loadExisting(kbId, model);

        int images = 0;
        int written = 0;
        int skipped = 0;
        int failed = 0;
        for (KbChunkRecord chunk : chunks) {
            JsonNode root = readImages(chunk.getImageInfo());
            if (root == null) {
                continue;
            }
            int index = 0;
            for (JsonNode node : root) {
                if (!node.isObject()) {
                    index++;
                    continue;
                }
                images++;
                String imageKey = imageKeyOf(node, chunk.getId(), index);
                index++;

                Outcome c = encodeAndStore(kbId, knowledgeId, chunk.getId(), imageKey,
                    SOURCE_CAPTION, textOf(node, "caption"), model, existing);
                Outcome o = encodeAndStore(kbId, knowledgeId, chunk.getId(), imageKey,
                    SOURCE_OCR, textOf(node, "ocr_text"), model, existing);
                written += count(c, Outcome.WRITTEN) + count(o, Outcome.WRITTEN);
                skipped += count(c, Outcome.SKIPPED) + count(o, Outcome.SKIPPED);
                failed += count(c, Outcome.FAILED) + count(o, Outcome.FAILED);
            }
        }

        long elapsed = elapsedMs(startedNs);
        log.info("[图片向量] 索引完成 knowledge={} 库={} 模型={} 张数={} 写入={} 跳过(hash未变)={} 失败={} 耗时={}ms",
            knowledgeId, kbId, model, images, written, skipped, failed, elapsed);
        return new IndexOutcome(true, images, written, skipped, failed, elapsed);
    }

    /**
     * 图片向量检索（余弦相似度）。
     *
     * <p>查询文本用<b>同一模型</b>编码；只在该知识库、该模型的向量里比
     * （不同模型的向量空间不可比，混算只会给出垃圾排序）。
     *
     * @param kbId      知识库 ID
     * @param queryText 查询文本
     * @param topK      返回条数（&lt;= 0 ⇒ 空 + 原因）
     * @return 按分数降序的命中；未配置/空查询/编码失败 ⇒ 空列表（原因见
     *         {@link #searchImageVectorsWithReason}）
     */
    public List<ImageVectorHit> searchImageVectors(String kbId, String queryText, int topK) {
        return searchImageVectorsWithReason(kbId, queryText, topK).hits();
    }

    /**
     * 同 {@link #searchImageVectors}，但把「为什么空」一并返回（可观测性，⛔ 不静默）。
     *
     * @return 命中列表 + 原因（正常时 {@code reason == null}）
     */
    public SearchOutcome searchImageVectorsWithReason(String kbId, String queryText, int topK) {
        long startedNs = System.nanoTime();
        if (!isConfigured()) {
            String reason = "未配置图片向量模型（" + MODEL_PROPERTY + "）";
            log.info("[图片向量] 查询跳过：{} 库={}", reason, kbId);
            return new SearchOutcome(List.of(), reason);
        }
        if (isBlank(kbId)) {
            String reason = "知识库 ID 为空";
            log.info("[图片向量] 查询跳过：{}", reason);
            return new SearchOutcome(List.of(), reason);
        }
        if (queryText == null || queryText.isBlank()) {
            String reason = "查询文本为空";
            log.info("[图片向量] 查询跳过：{} 库={}", reason, kbId);
            return new SearchOutcome(List.of(), reason);
        }
        if (topK <= 0) {
            String reason = "topK 必须大于 0（当前 " + topK + "）";
            log.info("[图片向量] 查询跳过：{} 库={}", reason, kbId);
            return new SearchOutcome(List.of(), reason);
        }

        String model = configuredModel;
        float[] query;
        try {
            List<float[]> got = embeddingPort.embed(model, List.of(queryText));
            query = got == null || got.isEmpty() ? null : got.get(0);
        } catch (RuntimeException e) {
            String reason = "查询编码失败: " + e.getMessage();
            log.warn("[图片向量] {} 库={} 模型={}", reason, kbId, model);
            return new SearchOutcome(List.of(), reason);
        }
        if (query == null || query.length == 0) {
            String reason = "模型未返回查询向量";
            log.warn("[图片向量] {} 库={} 模型={}", reason, kbId, model);
            return new SearchOutcome(List.of(), reason);
        }
        double queryNorm = norm(query);
        if (queryNorm == 0.0) {
            String reason = "查询向量为零向量，无法计算余弦";
            log.warn("[图片向量] {} 库={}", reason, kbId);
            return new SearchOutcome(List.of(), reason);
        }

        List<KbImageEmbeddingRecord> rows;
        try {
            rows = embeddingMapper.selectListByQuery(
                QueryWrapper.create()
                    .where("knowledge_base_id = ?", kbId)
                    .and("model_id = ?", model));
        } catch (RuntimeException e) {
            String reason = "读向量表失败: " + e.getMessage();
            log.warn("[图片向量] {} 库={}", reason, kbId);
            return new SearchOutcome(List.of(), reason);
        }

        List<ImageVectorHit> hits = new ArrayList<>();
        int dimensionMismatch = 0;
        for (KbImageEmbeddingRecord row : rows) {
            float[] vec;
            try {
                vec = KbVectorCodec.decode(row.getVector());
            } catch (RuntimeException e) {
                log.warn("[图片向量] 解码失败 id={} 原因={}", row.getId(), e.getMessage());
                continue;
            }
            if (vec.length != query.length) {
                dimensionMismatch++;
                continue;
            }
            double rowNorm = norm(vec);
            if (rowNorm == 0.0) {
                continue;
            }
            double score = dot(query, vec) / (queryNorm * rowNorm);
            hits.add(new ImageVectorHit(row.getImageKey(), score, row.getSource()));
        }

        hits.sort(Comparator.comparingDouble(ImageVectorHit::score).reversed()
            .thenComparing(ImageVectorHit::imageKey)
            .thenComparing(ImageVectorHit::source));
        List<ImageVectorHit> top = hits.size() <= topK ? hits : hits.subList(0, topK);
        long elapsed = elapsedMs(startedNs);
        log.info("[图片向量] 查询完成 库={} 模型={} 候选={} 返回={} 维度不符={} 耗时={}ms",
            kbId, model, hits.size(), top.size(), dimensionMismatch, elapsed);
        return new SearchOutcome(List.copyOf(top), null);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 单条文本的编码结果。 */
    private enum Outcome { WRITTEN, SKIPPED, FAILED, IGNORED }

    private static int count(Outcome actual, Outcome expected) {
        return actual == expected ? 1 : 0;
    }

    /**
     * 编码一条文本并落库（单条 fail-soft）。
     *
     * <p>去重判据 = 同一唯一键下 {@code content_hash} 未变 ⇒ 跳过，不重复调模型。
     * 文本变了则先删旧行再写新行（唯一键约束下不能并存）。
     * 编码失败不写行 —— 宁可少一条，也不要一条错的。
     */
    private Outcome encodeAndStore(String kbId, String knowledgeId, String chunkId,
                                   String imageKey, String source, String text,
                                   String model, Map<String, KbImageEmbeddingRecord> existing) {
        if (text == null || text.isBlank()) {
            return Outcome.IGNORED;
        }
        // ⭐ 文件名式 caption（image1.png）不是描述：与检索链同口径跳过，
        //    否则「png」这类噪声词会写进向量空间（见 KbImageEnrichService#looksLikeFileName）
        if (SOURCE_CAPTION.equals(source) && KbImageEnrichService.looksLikeFileName(text)) {
            return Outcome.IGNORED;
        }
        String key = dedupeKey(imageKey, source);
        KbImageEmbeddingRecord old = existing.get(key);
        String hash = sha256(text);
        if (old != null && hash.equals(old.getContentHash())) {
            return Outcome.SKIPPED;
        }

        float[] vector;
        try {
            List<float[]> got = embeddingPort.embed(model, List.of(text));
            vector = got == null || got.isEmpty() ? null : got.get(0);
        } catch (RuntimeException e) {
            log.warn("[图片向量] 单条编码失败（不影响其他）knowledge={} image={} source={} 原因={}",
                knowledgeId, imageKey, source, e.toString());
            return Outcome.FAILED;
        }
        if (vector == null || vector.length == 0) {
            log.warn("[图片向量] 模型未返回向量 knowledge={} image={} source={}",
                knowledgeId, imageKey, source);
            return Outcome.FAILED;
        }

        try {
            if (old != null) {
                embeddingMapper.deleteById(old.getId());
                existing.remove(key);
            }
            KbImageEmbeddingRecord row = new KbImageEmbeddingRecord();
            row.setId(UUID.randomUUID().toString());
            row.setKnowledgeBaseId(kbId);
            row.setKnowledgeId(knowledgeId);
            row.setChunkId(chunkId);
            row.setImageKey(imageKey);
            row.setSource(source);
            row.setModelId(model);
            row.setDim(vector.length);
            row.setVector(KbVectorCodec.encode(vector));
            row.setContentHash(hash);
            row.setCreatedAt(KbTimeFormat.now());
            embeddingMapper.insert(row);
            existing.put(key, row);
            return Outcome.WRITTEN;
        } catch (RuntimeException e) {
            log.warn("[图片向量] 写入失败 knowledge={} image={} source={} 原因={}",
                knowledgeId, imageKey, source, e.toString());
            return Outcome.FAILED;
        }
    }

    /** 读该库 + 该模型下已有的向量行，按唯一键建索引（避免逐条查库）。 */
    private Map<String, KbImageEmbeddingRecord> loadExisting(String kbId, String model) {
        Map<String, KbImageEmbeddingRecord> out = new HashMap<>();
        try {
            List<KbImageEmbeddingRecord> rows = embeddingMapper.selectListByQuery(
                QueryWrapper.create()
                    .where("knowledge_base_id = ?", kbId)
                    .and("model_id = ?", model));
            for (KbImageEmbeddingRecord row : rows) {
                out.put(dedupeKey(row.getImageKey(), row.getSource()), row);
            }
        } catch (RuntimeException e) {
            // 降级：当作「尚无向量」。最坏是多编码一次，⛔ 不能因此整篇不去重/不写
            log.warn("[图片向量] 读已有向量失败，本次按「全部未写」处理 库={} 模型={} 原因={}",
                kbId, model, e.toString());
        }
        return out;
    }

    /** 去重键（imageKey 与 source 都可能含分隔字符，用不可见分隔符拼）。 */
    private static String dedupeKey(String imageKey, String source) {
        return (imageKey == null ? "" : imageKey) + '\u0000' + (source == null ? "" : source);
    }

    /**
     * 图片资源键 · 与 {@code KbImageService} 的投影口径一致：
     * {@code url} → {@code original_url} → {@code "<chunkId>#<index>"}。
     */
    private static String imageKeyOf(JsonNode node, String chunkId, int index) {
        String url = textOf(node, "url");
        if (!url.isEmpty()) {
            return url;
        }
        String original = textOf(node, "original_url");
        if (!original.isEmpty()) {
            return original;
        }
        return chunkId + "#" + index;
    }

    /** 解析 {@code image_info}；坏 JSON 只 WARN 并跳过该块（不让整篇失败）。 */
    private static JsonNode readImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return n != null && n.isArray() ? n : null;
        } catch (Exception e) {
            log.warn("[图片向量] image_info 不是合法 JSON，跳过：{}", e.getMessage());
            return null;
        }
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static double norm(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 理论上不可达（JDK 必备算法）；返回空串会让「同 hash」永不成立 ⇒ 只会多编码
            return "";
        }
    }

    /**
     * 索引统计。
     *
     * @param configured 是否已配置模型（false ⇒ 本次什么都没写）
     * @param images     遍历到的图片张数
     * @param written    实际写入（含覆盖旧值）的向量行数
     * @param skipped    content_hash 未变而跳过的条数
     * @param failed     编码/写入失败的条数
     * @param elapsedMs  耗时
     */
    public record IndexOutcome(boolean configured, int images, int written,
                               int skipped, int failed, long elapsedMs) {
    }

    /**
     * 单条检索命中。
     *
     * @param imageKey 图片资源键
     * @param score    余弦相似度
     * @param source   {@code caption_text} | {@code ocr_text}
     */
    public record ImageVectorHit(String imageKey, double score, String source) {
    }

    /**
     * 检索结果 + 空结果原因。
     *
     * @param hits   命中（正常时按分数降序）
     * @param reason 为空的原因（正常时为 null）——⛔ 不用空列表冒充「查过但没命中」
     */
    public record SearchOutcome(List<ImageVectorHit> hits, String reason) {
    }
}
