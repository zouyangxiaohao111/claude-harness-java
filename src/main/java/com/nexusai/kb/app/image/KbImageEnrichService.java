package com.nexusai.kb.app.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbVisionCaller;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <h2>⭐ caption / OCR 的落点（V-E5：独立子块，对齐源站）</h2>
 * 本仓原先把 caption/OCR 拼进正文块的索引文本（{@code KbIndexWriter#indexTextOf}）。
 * 现在对齐源站改为<b>独立子块</b>：每张图写 {@code chunk_type='image_caption'} 与
 * {@code chunk_type='image_ocr'} 两条 {@code kb_chunk}，{@code parent_chunk_id} 指向
 * 该图所属正文块。这样：
 * <ul>
 *   <li>子块本身进索引（{@code KbIndexWriter} 全表建索引、只跳父块）⇒ 图内文字可被检索；</li>
 *   <li>正文块的索引文本不再拼 caption/OCR（否则同一段文字被索引两遍）；</li>
 *   <li>命中子块后由 {@code KbMergeStage} 展开父块正文 ⇒ 上下文是正文而不是一句 caption。</li>
 * </ul>
 * 开关：{@code nexusai.knowledge.image.sub-chunks-enabled}（默认 true）。
 * 关闭时退回旧行为（只写 {@code image_info}，由 {@link #imageTextForIndex} 拼进索引文本）。
 * ⚠️ 两个开关读取点必须同值：本类（写子块）+ {@link com.nexusai.kb.app.retrieval.KbIndexWriter}（拼不拼索引）。
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

    /** 图片模型调用的默认并发度（源站 enrichment 池为 12，本仓取保守值 8）。 */
    static final int DEFAULT_IMAGE_CONCURRENCY = 8;

    /** 图片独立子块的默认开关（true = 对齐源站；false = 旧行为：拼进正文块索引文本）。 */
    public static final boolean DEFAULT_SUB_CHUNKS_ENABLED = true;

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
     * 图片<b>模型调用</b>的并发度（默认 8）。
     *
     * <p>⭐ 源站是「每张图一个 enrichment 任务、池 12 并发」；本仓在单个任务内做等价改进，
     * 只把模型调用并行化。<b>⚠️ 写库恒在主线程串行</b> —— 多线程同时 UPDATE 会撞
     * SQLite 单写锁（{@code SQLITE_BUSY}）。
     *
     * <p>⚠️ 用字段注入而不是构造器参数：本类已有两个构造器（生产 4 参 + 单测 3 参），
     * 再加一个带 {@code @Value} 的 5 参构造器只会让装配更难判。单测直构时该字段保持
     * 默认值（不启 Spring）。配置项 ⛔ 不写进 {@code application.yml}，按需用环境变量/启动参数覆盖。
     */
    @Value("${nexusai.knowledge.vision.image-concurrency:8}")
    private int imageConcurrency = DEFAULT_IMAGE_CONCURRENCY;

    /**
     * 是否写「图片独立子块」（{@code image_caption} / {@code image_ocr}）。
     *
     * <p>默认 true（对齐源站）。关闭 ⇒ 退回旧行为：不写子块，caption/OCR 只在
     * {@code image_info} 里、由 {@code KbIndexWriter} 拼进正文块索引文本。
     * ⚠️ {@code KbIndexWriter} 读的是<b>同一个配置键</b>，两处必须同值，否则会出现
     * 「子块也写了、正文块也拼了」⇒ 同一段文字被索引两遍。
     */
    @Value("${nexusai.knowledge.image.sub-chunks-enabled:true}")
    private boolean imageSubChunksEnabled = DEFAULT_SUB_CHUNKS_ENABLED;

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

    /** 测试/诊断用：改「图片独立子块」开关（Spring 环境下由 {@code @Value} 注入）。 */
    void setImageSubChunksEnabled(boolean enabled) {
        this.imageSubChunksEnabled = enabled;
    }

    /**
     * 为某文档所有含图分块补 caption / OCR。
     *
     * <p><b>⚠️ 必须在事务外调用</b>：内部要调模型（秒级）+ 逐块 UPDATE，
     * 放进 {@code @Transactional} 会长时间持有 SQLite 写锁。
     *
     * <p><b>执行模型（对齐源站「每张图一个 enrichment 任务」）</b>：
     * <ol>
     *   <li><b>收集</b>：一次遍历所有含图分块，把「分块 + 图片节点 + 本地路径」提成任务；</li>
     *   <li><b>并发调模型</b>：固定大小线程池（{@link #imageConcurrency}，默认 8）；</li>
     *   <li><b>串行写库</b>：结果在主线程逐块回收 → 逐块 UPDATE + 刷新投影。
     *       ⛔ 绝不让工作线程写库 —— SQLite 单写锁，多线程 UPDATE 会撞 {@code SQLITE_BUSY}。</li>
     * </ol>
     * 线程池在方法结束（含异常路径）一定 {@code shutdown}，不泄漏。
     *
     * @param knowledgeId 文档 ID
     * @return 补成功的图片数
     */
    public int enrichKnowledge(String knowledgeId) {
        return enrichKnowledge(knowledgeId, null, KbImageSettings.DEFAULTS);
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
        return enrichKnowledge(knowledgeId, modelIdOrName, KbImageSettings.DEFAULTS);
    }

    /**
     * 为某文档所有含图分块补 caption / OCR · <b>可指定模型与图片管线配置</b>。
     *
     * <h2>⭐⭐ 三个开关的生效点（对齐源站 image_pipeline_default / smartocr）</h2>
     * <ul>
     *   <li>{@code captionEnabled=false} ⇒ 不调 caption 提示词；</li>
     *   <li>{@code ocrEnabled=false} ⇒ 不调 OCR 提示词（smartocr 下即使观察到有文字也不调）；</li>
     *   <li><b>两个都 false</b> ⇒ 直接返回 0，<b>连线程池都不建</b>（下面第一段就返回）；</li>
     *   <li>{@code pipeline=smartocr} ⇒ 先一次「观察」调用（图片属性
     *       {@code contain.text} / {@code contain.data_visual} + 描述），再按源站策略
     *       {@link KbImageAttrDecision#decideOcr} 决定是否花第二次调用做 OCR；
     *       观察失败 / 属性解析不出 ⇒ 按 {@code ocr_on_unobserved}（默认 true）继续 OCR。</li>
     * </ul>
     *
     * <p><b>⚠️ 配置由调用方读库后冻结成本对象传入</b>（{@code KbIngestWorker}）：
     * 不在这里逐图回库读，避免「N 张图 N 次读库」以及入库途中配置被改导致前后半批不一致。
     *
     * @param knowledgeId   文档 ID
     * @param modelIdOrName 库级指定模型（null/空 = 跟随全局默认）
     * @param settings      图片管线配置（null ⇒ {@link KbImageSettings#DEFAULTS}）
     * @return 补成功的图片数
     */
    public int enrichKnowledge(String knowledgeId, String modelIdOrName, KbImageSettings settings) {
        final KbImageSettings cfg = settings == null ? KbImageSettings.DEFAULTS : settings;
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return 0;
        }
        // ⭐ 两个开关都关 ⇒ 直接返回：连分块查询与线程池都不做（用户明确要求「不建线程池」）。
        if (!cfg.anyEnabled()) {
            log.info("[图片] caption/OCR 两个开关都关闭，跳过图片理解 knowledge={} caption={} ocr={} pipeline={}",
                knowledgeId, cfg.captionEnabled(), cfg.ocrEnabled(), cfg.pipeline());
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
        long startedNs = System.nanoTime();
        List<KbChunkRecord> chunks = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .and("image_info IS NOT NULL")
                .and("image_info <> ''")
                .orderBy("chunk_index", true));

        // ① 收集：只做「读 JSON + 定位本地文件」，不调模型。
        List<ImageTask> tasks = collectTasks(chunks);
        int concurrency = Math.max(1, imageConcurrency);
        if (tasks.isEmpty()) {
            log.info("[图片] 理解完成 knowledge={} 含图块={} 待处理图片=0 并发={} 成功=0 失败=0 写库块=0"
                    + " caption={} ocr={} pipeline={} 耗时={}ms",
                knowledgeId, chunks.size(), concurrency,
                cfg.captionEnabled(), cfg.ocrEnabled(), cfg.pipeline(), elapsedMs(startedNs));
            return 0;
        }
        log.info("[图片] 开始理解 knowledge={} 含图块={} 待处理图片={} 并发={} caption={} ocr={} pipeline={}",
            knowledgeId, chunks.size(), tasks.size(), concurrency,
            cfg.captionEnabled(), cfg.ocrEnabled(), cfg.pipeline());

        // ② 并发调模型（结果只回收到内存，不落库）。
        List<ImageOutcome> outcomes = runModelCalls(tasks, model, concurrency, cfg);

        // ③ 主线程串行写库：任务按「分块 → 图序」排列，同一分块的图集中在一段区间里。
        int done = 0;
        int failed = 0;
        int written = 0;
        // 各分支命中数（日志用：判「开关到底有没有生效」只看这四个数就够）
        int captionHits = 0;
        int ocrHits = 0;
        int ocrSkippedByDecision = 0;
        // 图片独立子块：按任务顺序收集（chunk_index 升序 ⇒ 「同一张图挂第一个块」天然成立）
        List<KbChunkRecord> pendingSubChunks = new ArrayList<>();
        Set<String> seenImageKeys = new HashSet<>();
        int i = 0;
        while (i < tasks.size()) {
            KbChunkRecord chunk = tasks.get(i).chunk();
            ArrayNode images = tasks.get(i).images();
            boolean changed = false;
            while (i < tasks.size() && tasks.get(i).chunk() == chunk) {
                ImageTask task = tasks.get(i);
                ImageOutcome outcome = outcomes.get(i);
                i++;
                if (outcome == null) {
                    // 线程池被中断 / 调用异常 ⇒ 该图按失败计，原 caption 保留
                    failed++;
                    continue;
                }
                if (outcome.caption() != null) {
                    task.node().put("caption", outcome.caption());
                    changed = true;
                    captionHits++;
                }
                if (outcome.ocr() != null) {
                    task.node().put("ocr_text", outcome.ocr());
                    changed = true;
                    ocrHits++;
                }
                if (outcome.ocrSkippedByDecision()) {
                    ocrSkippedByDecision++;
                }
                if (outcome.ok()) {
                    done++;
                } else {
                    // 模型回「无内容 / 无文字」不算成功，但也不算异常
                    failed++;
                }
                if (log.isInfoEnabled()) {
                    log.info("[图片] 理解单图 chunk={} file={} caption={} ocrLen={}",
                        chunk.getId(), task.local().getFileName(),
                        textOf(task.node(), "caption").length(),
                        textOf(task.node(), "ocr_text").length());
                }
            }
            if (changed) {
                String json = images.toString();
                chunkMapper.update(updateOf(chunk, json));
                written++;
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
            // ⭐ 独立子块收集：用**最终** caption/OCR（含上一轮保留的值），
            //    但父块正文 / image_info 本身不变（图库仍显示描述）。
            if (imageSubChunksEnabled) {
                collectImageSubChunks(chunk, images, seenImageKeys, pendingSubChunks);
            }
        }

        // ④ 写图片独立子块（先删旧的再插 —— 重试/重新理解不能越积越多）
        // ⚠️ 只在**本轮至少有一张图成功**时替换：done==0 说明模型全挂，此时子块行会拿到新 id
        //    而调用方（KbIngestWorker）在 done==0 时不会重建索引 ⇒ 新行没索引、旧索引指向已删块，
        //    图内文字会从「可检索」变成「不可检索」。⇒ 全失败时保留旧子块与旧索引，什么都不动。
        int subChunkRows = 0;
        if (imageSubChunksEnabled && done > 0) {
            subChunkRows = replaceImageSubChunks(knowledgeId, pendingSubChunks);
        }
        log.info("[图片] 理解完成 knowledge={} 含图块={} 待处理图片={} 并发={} 成功={} 失败={} 写库块={}"
                + " 开关 caption={} ocr={} pipeline={} ocr_on_unobserved={} 命中 caption={} ocr={}"
                + " 属性判定跳过 OCR={}"
                + " 子块开关={} 子块={} 子块图={} 耗时={}ms",
            knowledgeId, chunks.size(), tasks.size(), concurrency, done, failed, written,
            cfg.captionEnabled(), cfg.ocrEnabled(), cfg.pipeline(), cfg.ocrOnUnobserved(),
            captionHits, ocrHits, ocrSkippedByDecision,
            imageSubChunksEnabled, subChunkRows, seenImageKeys.size(), elapsedMs(startedNs));
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

    // ── 图片独立子块 ────────────────────────────────────────────────────

    /**
     * 收集一个正文块里<b>尚未写过</b>的图片子块（caption / OCR 各一条）。
     *
     * <p><b>⭐ 去重口径（对齐源站）</b>：同一张图可能被多个正文块引用
     * （同一图片在文档里出现两次、或被重叠分块各切到一次）。按图片 key
     * （{@code url → original_url → attrs.storage_key}）去重，<b>只挂第一个块</b>
     * —— 否则同一段图内文字会进索引多次，检索结果里重复出现。
     *
     * <p>任务顺序 = {@code chunk_index} 升序 ⇒ 「第一个块」= 序号最小的那个块。
     *
     * @param parent        该图所属正文块（将成为 {@code parent_chunk_id}）
     * @param images        该块的 {@code image_info}（<b>已回写最终值</b>）
     * @param seenImageKeys 跨块去重集合（入参出参）
     * @param out           待写入的子块行
     */
    private void collectImageSubChunks(KbChunkRecord parent, ArrayNode images,
                                       Set<String> seenImageKeys, List<KbChunkRecord> out) {
        if (images == null || images.isEmpty()) {
            return;
        }
        int index = 0;
        for (JsonNode n : images) {
            int current = index++;
            if (!(n instanceof ObjectNode img)) {
                continue;
            }
            String key = imageKeyOf(img);
            if (key.isEmpty()) {
                // 无任何可辨识地址 ⇒ 用「块 + 图序」兜底（只在本块内唯一，不影响跨块去重语义）
                key = parent.getId() + "#" + current;
            }
            // ⛔ 先占位再写：同一张图的 caption/ocr 两条子块共享同一个 key
            if (!seenImageKeys.add(key)) {
                continue;
            }
            String caption = textOf(img, "caption");
            // 与 imageTextForIndex 同口径：文件名式占位值（image1.png）不进索引
            if (!caption.isEmpty() && !looksLikeFileName(caption)) {
                out.add(imageSubChunk(parent, KbChunk.CHUNK_TYPE_IMAGE_CAPTION,
                    caption, key, "caption"));
            }
            String ocr = textOf(img, "ocr_text");
            // OCR 为空 ⇒ 不写（⛔ 不写空正文行：索引里多一条无内容命中）
            if (!ocr.isEmpty()) {
                out.add(imageSubChunk(parent, KbChunk.CHUNK_TYPE_IMAGE_OCR, ocr, key, "ocr"));
            }
        }
    }

    /**
     * 建一条图片子块行。
     *
     * <p>位置口径：{@code start_at/end_at} 复制父块区间（父块缺失时为 0），
     * {@code source_locators} 也复制父块（子块与父块来自同一处原文，定位应一致）。
     * <b>⛔ 不写 {@code image_info}</b>：否则下一次图片理解会把子块当成「含图块」再处理一遍。
     *
     * @param parent   父块
     * @param type     {@code image_caption} / {@code image_ocr}
     * @param content  正文（caption 或 OCR 文本）
     * @param imageKey 图片去重键（写入 metadata，供合并阶段带回前端）
     * @param kind     {@code caption} / {@code ocr}（metadata 标记）
     */
    private static KbChunkRecord imageSubChunk(KbChunkRecord parent, String type, String content,
                                               String imageKey, String kind) {
        KbChunkRecord row = new KbChunkRecord();
        row.setId(UUID.randomUUID().toString());
        row.setKnowledgeBaseId(parent.getKnowledgeBaseId());
        row.setKnowledgeId(parent.getKnowledgeId());
        row.setContent(content);
        row.setChunkIndex(parent.getChunkIndex() == null ? 0 : parent.getChunkIndex());
        row.setIsEnabled(1);
        row.setStartAt(parent.getStartAt() == null ? 0 : parent.getStartAt());
        row.setEndAt(parent.getEndAt() == null ? 0 : parent.getEndAt());
        row.setPreChunkId(null);
        row.setNextChunkId(null);
        row.setParentChunkId(parent.getId());
        row.setChunkType(type);
        row.setContextHeader(parent.getContextHeader() == null ? "" : parent.getContextHeader());
        row.setSourceLocators(parent.getSourceLocators());
        ObjectNode meta = JSON.createObjectNode();
        meta.put("image_key", imageKey);
        meta.put("image_kind", kind);
        row.setMetadata(meta.toString());

        // ⚠️ 与 KbChunkWriter#newRow 同口径：Flex 的 insert 会把 null 也列进 INSERT 清单，
        //    带 DEFAULT 的 NOT NULL 列必须显式赋值。
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
        String now = KbTimeFormat.now();
        row.setCreateTime(KbTimeFormat.parse(now));
        row.setUpdateTime(KbTimeFormat.parse(now));
        return row;
    }

    /**
     * 用新子块替换该文档的图片子块（先逻辑删旧、再插新）。
     *
     * <p><b>为什么必须删旧</b>：图片理解任务失败会整任务重试，重新理解入口也会再调一次
     * ⇒ 不删就是 2 倍、3 倍的行数，检索结果里同一段图内文字重复出现。
     * ⚠️ 只删 {@code image_caption/image_ocr}。
     *
     * @param knowledgeId 文档 ID
     * @param rows        新子块（空 = 只清旧行，例如本次所有 caption 都还是占位文件名）
     * @return 写入的子块行数
     */
    private int replaceImageSubChunks(String knowledgeId, List<KbChunkRecord> rows) {
        chunkMapper.deleteByQuery(QueryWrapper.create()
            .where("knowledge_id = ?", knowledgeId)
            .and("chunk_type IN (?, ?)",
                KbChunk.CHUNK_TYPE_IMAGE_CAPTION, KbChunk.CHUNK_TYPE_IMAGE_OCR));
        for (KbChunkRecord row : rows) {
            chunkMapper.insert(row);
        }
        return rows.size();
    }

    /** 图片去重键：{@code url → original_url → attrs.storage_key}（对齐 {@code KbChunk.Image#key} 的优先序）。 */
    private static String imageKeyOf(JsonNode img) {
        String url = textOf(img, "url");
        if (!url.isEmpty()) {
            return url;
        }
        String originalUrl = textOf(img, "original_url");
        if (!originalUrl.isEmpty()) {
            return originalUrl;
        }
        JsonNode attrs = img.get("attrs");
        return attrs == null || attrs.isNull() ? "" : textOf(attrs, "storage_key");
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /**
     * 收集所有待处理的单图任务（本方法<b>不调模型</b>，只看数据 + 文件是否存在）。
     *
     * <p>任务顺序 = 分块顺序 → 块内图序，和原串行实现一致；后面按同一顺序回写，
     * 因此写库顺序与改动前逐字节可比（同一分块的图集中在一段连续区间里）。
     */
    private List<ImageTask> collectTasks(List<KbChunkRecord> chunks) {
        List<ImageTask> tasks = new ArrayList<>();
        for (KbChunkRecord chunk : chunks) {
            ArrayNode images = readImages(chunk.getImageInfo());
            if (images == null || images.isEmpty()) {
                continue;
            }
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
                tasks.add(new ImageTask(chunk, images, img, local));
            }
        }
        return tasks;
    }

    /**
     * 用固定大小线程池并发调模型，按任务顺序回收结果（纯内存，不碰数据库）。
     *
     * <p><b>线程池生命周期</b>：无论正常返回、提交失败还是回收时被中断，
     * {@code finally} 一定 {@code shutdownNow()} ⇒ 不泄漏线程。
     *
     * <p><b>失败语义</b>：单图失败已在 {@link #describeOne} 内 WARN 并折叠成
     * 「空结果」，这里只需对 {@code Future} 层面的异常（Error / 被中断）兜底 ——
     * 对应槽位保留 null，调用方按「该图未成功、原 caption 不动」处理。
     *
     * @return 与 {@code tasks} 等长的结果列表（未回收到的槽位为 null）
     */
    private List<ImageOutcome> runModelCalls(List<ImageTask> tasks, String model, int concurrency,
                                             KbImageSettings cfg) {
        List<ImageOutcome> outcomes = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            outcomes.add(null);
        }
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, imageThreadFactory());
        try {
            List<Future<ImageOutcome>> futures = new ArrayList<>(tasks.size());
            for (ImageTask task : tasks) {
                futures.add(pool.submit(() -> describeOne(task, model, cfg)));
            }
            // 不再提交新任务：空闲线程在回收期间就能退出（回收本身仍按提交顺序阻塞等待）
            pool.shutdown();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    outcomes.set(i, futures.get(i).get());
                } catch (InterruptedException e) {
                    // 恢复中断标记 + 停止回收：剩余图保留原 caption（fail-soft）
                    Thread.currentThread().interrupt();
                    log.warn("[图片] 等待模型结果被中断，剩余 {} 张图不再补描述", tasks.size() - i);
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    ImageTask t = tasks.get(i);
                    log.warn("[图片] 模型调用异常 chunk={} file={} 原因={}",
                        t.chunk().getId(), t.local().getFileName(), cause.toString());
                }
            }
            return outcomes;
        } finally {
            // ⭐ 异常路径也必须走到：否则线程池会一直活着
            pool.shutdownNow();
        }
    }

    /**
     * 调视觉模型描述一张图（工作线程里执行，<b>只读文件、不写任何共享状态</b>）。
     *
     * <p>caption / OCR 各自 fail-soft：一边失败不影响另一边，也不会影响别的图。
     * {@code ok} 语义与改动前完全一致 = caption 或 OCR 至少一项拿到了有效内容。
     *
     * <p>⭐ 管线分支（V286）：
     * <ul>
     *   <li>{@code smartocr} ⇒ 走 {@link #describeSmartOcr}（一次观察 + 视情况 OCR）；</li>
     *   <li>{@code default} ⇒ 两个开关分别决定 caption / OCR 各调不调。</li>
     * </ul>
     */
    private ImageOutcome describeOne(ImageTask task, String model, KbImageSettings cfg) {
        if (cfg.smartOcr()) {
            return describeSmartOcr(task, model, cfg);
        }
        String captionOut = null;
        String ocrOut = null;
        boolean ok = false;
        if (cfg.captionEnabled()) {
            try {
                String caption = visionCaller.describeImageWithModel(task.local(), KbImagePrompts.CAPTION,
                    KbParseContext.defaults(), model);
                if (!KbImagePrompts.isNoCaption(caption)) {
                    captionOut = clip(caption, MAX_CAPTION_CHARS);
                    ok = true;
                }
            } catch (Exception e) {
                log.warn("[图片] caption 生成失败 chunk={} file={} 原因={}",
                    task.chunk().getId(), task.local().getFileName(), e.getMessage());
            }
        }
        if (cfg.ocrEnabled()) {
            try {
                String ocr = visionCaller.describeImageWithModel(task.local(), KbImagePrompts.OCR,
                    KbParseContext.defaults(), model);
                if (!KbImagePrompts.isNoTextReply(ocr)) {
                    ocrOut = clip(ocr, MAX_OCR_CHARS);
                    ok = true;
                }
            } catch (Exception e) {
                log.warn("[图片] OCR 生成失败 chunk={} file={} 原因={}",
                    task.chunk().getId(), task.local().getFileName(), e.getMessage());
            }
        }
        return new ImageOutcome(captionOut, ocrOut, ok, false);
    }

    /**
     * smartocr 管线：先「观察」一次（图片属性 + 描述），再由属性决定要不要花第二次调用做 OCR。
     *
     * <p><b>⭐ 判定逐字对齐源站</b>（{@code image_attr_decision.go DecideOCR}）：
     * {@code contain.text == "block" || contain.data_visual == true} 才 OCR；
     * 判定所读的属性<b>没观察到</b> ⇒ 既不算命中、也不算否定 ⇒ 交给管线的
     * {@link KbImageSettings#ocrOnUnobserved()}（源站 {@code ocr_on_unobserved}，默认 true）。
     * ⛔ 之前这里只判「有没有文字」，等于恒做 OCR。
     *
     * <p><b>⚠️ 观察调用失败（异常 / 超时）</b>：属性表为空 ⇒ 走「没观察到」分支 ⇒
     * 默认继续 OCR（宁可多跑一次，也不静默丢字）。观察回包空白 / 纯散文 / 值全非法也按
     * 同一分支处理（源站 {@code observation.result = none|failed}）。
     *
     * <p>开关约束：{@code captionEnabled=false} ⇒ 观察出的描述不落库（观察调用仍要做，
     * 因为属性才是判定输入）；{@code ocrEnabled=false} ⇒ 即使判定要 OCR 也不调。
     */
    private ImageOutcome describeSmartOcr(ImageTask task, String model, KbImageSettings cfg) {
        String captionOut = null;
        String ocrOut = null;
        boolean ok = false;
        KbImagePrompts.Observed observed;
        boolean observationFailed = false;
        try {
            observed = KbImagePrompts.parseObserve(
                visionCaller.describeImageWithModel(task.local(), KbImagePrompts.OBSERVE,
                    KbParseContext.defaults(), model));
        } catch (Exception e) {
            log.warn("[图片] 观察调用失败，按 ocr_on_unobserved={} 处理 chunk={} file={} 原因={}",
                cfg.ocrOnUnobserved(), task.chunk().getId(), task.local().getFileName(), e.getMessage());
            observed = KbImagePrompts.Observed.unobserved(null);
            observationFailed = true;
        }
        if (cfg.captionEnabled() && !KbImagePrompts.isNoCaption(observed.caption())) {
            captionOut = clip(observed.caption(), MAX_CAPTION_CHARS);
            ok = true;
        }
        boolean wantOcr = KbImageAttrDecision.decideOcr(observed.attrs(), cfg.ocrOnUnobserved());
        if (wantOcr && cfg.ocrEnabled()) {
            try {
                String ocr = visionCaller.describeImageWithModel(task.local(), KbImagePrompts.OCR,
                    KbParseContext.defaults(), model);
                if (!KbImagePrompts.isNoTextReply(ocr)) {
                    ocrOut = clip(ocr, MAX_OCR_CHARS);
                    ok = true;
                }
            } catch (Exception e) {
                log.warn("[图片] smartocr 的 OCR 失败 chunk={} file={} 原因={}",
                    task.chunk().getId(), task.local().getFileName(), e.getMessage());
            }
        } else if (!wantOcr && log.isDebugEnabled()) {
            // 两种「不 OCR」分开记：观察没答案（失败/无属性）≠ 属性观察到了、投票反对
            log.debug("[图片] 跳过 OCR chunk={} file={} 原因={} attrs={}",
                task.chunk().getId(), task.local().getFileName(),
                (observationFailed || !observed.observed()) ? "skipped_observation_failed"
                    : "skipped_attr_policy",
                observed.attrs());
        }
        return new ImageOutcome(captionOut, ocrOut, ok, !wantOcr);
    }

    /** 有界线程池的线程工厂（守护线程 + 可辨识名字，便于排障「是不是我起的」）。 */
    private static ThreadFactory imageThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "kb-image-enrich-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    /**
     * 单图任务。
     *
     * @param chunk  所属分块（写库与日志用）
     * @param images 该分块的 {@code image_info} 数组（主线程据此序列化回库）
     * @param node   该图所在的 JSON 节点（<b>只在主线程</b>被改）
     * @param local  本地图片路径（已确认存在）
     */
    private record ImageTask(KbChunkRecord chunk, ArrayNode images, ObjectNode node, Path local) {
    }

    /**
     * 单图模型调用结果。
     *
     * @param caption        新 caption（null = 没拿到有效描述，保留原值）
     * @param ocr            新 OCR（null = 没拿到有效文字，保留原值）
     * @param ok             至少一项有效（与原实现返回值口径一致）
     * @param ocrSkippedByDecision smartocr 的属性判定结论是「不 OCR」（含观察失败且
     *                             {@code ocr_on_unobserved=false} 的情形；日志统计用）
     */
    private record ImageOutcome(String caption, String ocr, boolean ok, boolean ocrSkippedByDecision) {
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
