package com.nexusai.kb.app.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbVisionCaller;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 图片理解（caption + OCR）· 把 {@code kb_chunk.image_info} 里只有文件名的图片补上真实描述。
 *
 * <h2>⭐⭐ 这条链原来缺的是什么</h2>
 * 解析器只负责把图片<b>落盘</b>并往 {@code image_info} 写 {@code caption = 资源名}
 * （Office 内嵌图就是 {@code image1.png} 这种）。源侧在解析完成后还会走一轮
 * VLM（{@code image_multimodal.go:898-912}）把每张图<b>读懂</b>：一句话描述 + 图内文字，
 * 分别写进 {@code caption} / {@code ocr_text}，再以 {@code image_caption} /
 * {@code image_ocr} 子块进索引 —— 这样「图里写了什么」也能被检索到。
 *
 * <p>本类补的就是这一段。<b>通道复用</b> {@link KbVisionCaller}（与 PDF 扫描页补强、
 * 独立图片解析器同一个实现 {@code KbVisionModelCaller}，也就是
 * {@code VisionAnalyzeTool} 背后那套 {@code settings.multimodalModelName} +
 * {@code LlmSideCallClient}），⛔ 不另起一条模型通路。
 *
 * <h2>⭐ 为什么写回 image_info 而不是新建 image_ocr 分块</h2>
 * 本仓检索链没有「子块 = 独立 chunk_type」这一层：{@code KbIndexWriter} 按
 * {@code kb_chunk} 全表建索引，问答链（{@code KbMergeStage}）也只认 text / faq。
 * 若照搬源侧建 {@code image_ocr} 子块，等于造出一批<b>永远不会被检索</b>的行
 * （功能看起来做了、实际无效）。⇒ 这里把文字放进 {@code image_info}，
 * 并由 {@link #imageTextForIndex} 拼进索引文本 —— 检索侧不需要任何改动就能命中图内文字。
 *
 * <h2>失败语义 · 逐图 fail-soft</h2>
 * 单图模型调用失败只记 WARN 并保留原 caption，其余图继续；
 * 视觉模型未配置 ⇒ 整体跳过并留 info（不算失败，正文早已入库可检索）。
 */
@Service
public class KbImageEnrichService {

    private static final Logger log = LoggerFactory.getLogger(KbImageEnrichService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单张图的 OCR 文本上限（防止模型复述整篇文档，撑爆索引文本）。 */
    private static final int MAX_OCR_CHARS = 4000;

    /** 单张图的 caption 上限（提示词要 40 字，留足余量给模型跑偏）。 */
    private static final int MAX_CAPTION_CHARS = 200;

    private final KbChunkMapper chunkMapper;
    private final KbImageStorage imageStorage;
    private final KbVisionCaller visionCaller;
    /**
     * 图片投影表服务。
     *
     * <p>⚠️ 可为 null（单测直构）：为 null 时只写 {@code kb_chunk.image_info} 不刷新投影，
     * 并留 info 日志 —— ⛔ 不静默。
     */
    private final KbImageService imageService;

    /**
     * 生产构造器（4 参）。
     *
     * <p>⚠️ 本类有<b>两个</b>构造器 ⇒ Spring 必须靠 {@code @Autowired} 选中这一个
     * （只有一个构造器时才可省略）。漏了会报
     * {@code No default constructor found}（实测过一次）。
     */
    @Autowired
    public KbImageEnrichService(KbChunkMapper chunkMapper,
                                KbImageStorage imageStorage,
                                @Autowired(required = false) KbVisionCaller visionCaller,
                                @Autowired(required = false) KbImageService imageService) {
        this.chunkMapper = chunkMapper;
        this.imageStorage = imageStorage;
        this.visionCaller = visionCaller;
        this.imageService = imageService;
    }

    /** 兼容构造（单测：不刷新投影）。 */
    public KbImageEnrichService(KbChunkMapper chunkMapper,
                                KbImageStorage imageStorage,
                                KbVisionCaller visionCaller) {
        this(chunkMapper, imageStorage, visionCaller, null);
    }

    /**
     * 为某文档所有含图分块补 caption / OCR。
     *
     * <p><b>⚠️ 必须在事务外调用</b>：内部要调模型（秒级）+ 逐块 UPDATE，
     * 放进 {@code @Transactional} 会长时间持有 SQLite 写锁。
     *
     * @param knowledgeId 文档 ID
     * @return 补成功的图片数
     */
    public int enrichKnowledge(String knowledgeId) {
        return enrichKnowledge(knowledgeId, null);
    }

    /**
     * 为某文档所有含图分块补 caption / OCR · <b>可指定模型</b>。
     *
     * <p>⭐ 2026-11-11 用户裁定「VLLM 视觉模型…单独选择也能其效果」：
     * {@code modelIdOrName} 来自知识库 {@code image_processing_config.model_id}；
     * 为空 ⇒ 视觉调用器自动回落全局 {@code settings.multimodalModelName}。
     *
     * @param knowledgeId   文档 ID
     * @param modelIdOrName 库级指定模型（null/空 = 跟随全局默认）
     * @return 补成功的图片数
     */
    public int enrichKnowledge(String knowledgeId, String modelIdOrName) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return 0;
        }
        if (imageStorage == null || visionCaller == null) {
            log.info("[图片] 视觉调用通道缺失，跳过 caption/OCR knowledge={}", knowledgeId);
            return 0;
        }
        final String model = modelIdOrName == null || modelIdOrName.isBlank() ? null : modelIdOrName.trim();
        // ⭐ 判「有没有视觉能力」不能只看全局档位：知识库可能自己指定了模型，
        //    此时即使 `settings.multimodalModelName` 没配，也该跑（用户裁定「单独选择也能其效果」）。
        //    只有「库级没指定 且 全局不可用」才是真的没能力。
        if (!visionCaller.isAvailable() && model == null) {
            log.info("[图片] 视觉能力未配置，跳过 caption/OCR knowledge={}", knowledgeId);
            return 0;
        }
        if (model != null) {
            log.info("[图片] 使用知识库指定视觉模型 model={} knowledge={}", model, knowledgeId);
        } else {
            log.info("[图片] 使用全局多模态默认模型 knowledge={}", knowledgeId);
        }
        List<KbChunkRecord> chunks = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .and("image_info IS NOT NULL")
                .and("image_info <> ''")
                .orderBy("chunk_index", true));
        int done = 0;
        int attempted = 0;
        for (KbChunkRecord chunk : chunks) {
            ArrayNode images = readImages(chunk.getImageInfo());
            if (images == null || images.isEmpty()) {
                continue;
            }
            boolean changed = false;
            for (JsonNode node : images) {
                if (!(node instanceof ObjectNode img)) {
                    continue;
                }
                Path local = localPathOf(img);
                if (local == null) {
                    // 对象存储后端拿不到本地路径 ⇒ 本批不支持（⛔ 不静默假装做过）
                    log.info("[图片] 存储后端不提供本地路径，跳过该图 chunk={} key={}",
                        chunk.getId(), textOf(img, "url"));
                    continue;
                }
                attempted++;
                if (describeInto(img, local, chunk.getId(), model)) {
                    changed = true;
                    done++;
                }
            }
            if (changed) {
                String json = images.toString();
                chunkMapper.update(updateOf(chunk, json));
                // ⭐⭐ 必须同步刷新投影表。图库列表读的是 `kb_chunk_image`，
                //    只改 `kb_chunk.image_info` 会让「接口返回新 caption、图库显示旧文件名」
                //    —— 而且不报错（实测过一次：image_info 里是真实描述，
                //    投影表里还是 `image3.png`、ocr_text 长度 0）。
                // ⚠️ 用手里这份 json：重新 selectOneById 可能读到事务前的旧值
                //    （同 KbKnowledgeExtService#updateImageInfo 的踩坑注释）。
                if (imageService != null) {
                    chunk.setImageInfo(json);
                    imageService.projectChunk(chunk);
                }
                log.info("[图片] 已回写 image_info chunk={} images={}", chunk.getId(), images.size());
            }
        }
        log.info("[图片] 理解完成 knowledge={} 含图块={} 尝试={} 成功={}",
            knowledgeId, chunks.size(), attempted, done);
        return done;
    }

    /**
     * 把 {@code image_info} 里的图内文字拼成索引文本片段。
     *
     * <p>⭐ 这是「OCR 真的有用」的唯一判据：只写进 {@code image_info} 而不进索引，
     * 检索侧永远命中不到 ⟺ 白跑一次模型调用。由 {@code KbIndexWriter#indexTextOf} 拼接。
     *
     * @param imageInfo {@code kb_chunk.image_info} 原文（可 null）
     * @return 拼接片段（无图/无文字 ⇒ 空串）
     */
    public static String imageTextForIndex(String imageInfo) {
        ArrayNode images = readImages(imageInfo);
        if (images == null || images.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : images) {
            String caption = textOf(n, "caption");
            String ocr = textOf(n, "ocr_text");
            // ⛔ 跳过「文件名当 caption」的占位值：那是解析器给的资源名（image1.png），
            //    拼进索引只会让「png」这种词互相干扰。
            if (!caption.isEmpty() && !looksLikeFileName(caption)) {
                sb.append('\n').append(caption);
            }
            if (!ocr.isEmpty()) {
                sb.append('\n').append(ocr);
            }
        }
        return sb.toString();
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 调视觉模型描述一张图，成功则写进该 image 节点。 */
    private boolean describeInto(ObjectNode img, Path local, String chunkId, String model) {
        boolean ok = false;
        try {
            String caption = visionCaller.describeImageWithModel(local, KbImagePrompts.CAPTION,
                KbParseContext.defaults(), model);
            if (!KbImagePrompts.isNoCaption(caption)) {
                img.put("caption", clip(caption, MAX_CAPTION_CHARS));
                ok = true;
            }
        } catch (Exception e) {
            log.warn("[图片] caption 生成失败 chunk={} file={} 原因={}",
                chunkId, local.getFileName(), e.getMessage());
        }
        try {
            String ocr = visionCaller.describeImageWithModel(local, KbImagePrompts.OCR,
                KbParseContext.defaults(), model);
            if (!KbImagePrompts.isNoTextReply(ocr)) {
                img.put("ocr_text", clip(ocr, MAX_OCR_CHARS));
                ok = true;
            }
        } catch (Exception e) {
            log.warn("[图片] OCR 生成失败 chunk={} file={} 原因={}",
                chunkId, local.getFileName(), e.getMessage());
        }
        if (log.isInfoEnabled()) {
            log.info("[图片] 理解单图 chunk={} file={} caption={} ocrLen={}",
                chunkId, local.getFileName(),
                textOf(img, "caption").length(), textOf(img, "ocr_text").length());
        }
        return ok;
    }

    /** image 节点的存储键 → 本地绝对路径（对象存储 ⇒ null）。 */
    private Path localPathOf(ObjectNode img) {
        JsonNode attrs = img.get("attrs");
        String key = attrs == null || attrs.isNull() ? "" : textOf(attrs, "storage_key");
        if (key.isEmpty()) {
            return null;
        }
        try {
            Path p = imageStorage.localPath(key);
            return p != null && java.nio.file.Files.isRegularFile(p) ? p : null;
        } catch (RuntimeException e) {
            log.warn("[图片] 解析本地路径失败 key={} 原因={}", key, e.toString());
            return null;
        }
    }

    /** 只更新 image_info / update_time，避免整行覆盖（并发编辑丢字段）。 */
    private static KbChunkRecord updateOf(KbChunkRecord src, String imageInfoJson) {
        KbChunkRecord patch = new KbChunkRecord();
        patch.setId(src.getId());
        patch.setImageInfo(imageInfoJson);
        patch.setUpdateTime(com.nexusai.kb.repo.KbTimeFormat.parse(
            com.nexusai.kb.repo.KbTimeFormat.now()));
        return patch;
    }

    private static ArrayNode readImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return n instanceof ArrayNode arr ? arr : null;
        } catch (Exception e) {
            log.warn("[图片] image_info 不是合法 JSON 数组，跳过：{}", e.getMessage());
            return null;
        }
    }

    private static String textOf(JsonNode n, String field) {
        if (n == null) {
            return "";
        }
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /**
     * 是否是「文件名式」占位说明（{@code image1.png} / {@code office-image-001.png}）。
     *
     * <p>判据 = 以图片扩展名结尾且不含空白（真实描述几乎不会长这样）。
     */
    static boolean looksLikeFileName(String caption) {
        String t = caption.strip().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty() || t.indexOf(' ') >= 0) {
            return false;
        }
        return t.endsWith(".png") || t.endsWith(".jpg") || t.endsWith(".jpeg")
            || t.endsWith(".gif") || t.endsWith(".webp") || t.endsWith(".bmp");
    }
}
