package com.nexusai.kb.app.ingest;

import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.chunk.KbChunkingConfigParser;
import com.nexusai.kb.app.chunk.KbSplitterConfig;
import com.nexusai.kb.app.chunk.KbTextSplitter;
import com.nexusai.kb.app.image.KbImageStorage;
import com.nexusai.kb.app.ingest.span.KbSpan;
import com.nexusai.kb.app.ingest.span.KbSpanTracker;
import com.nexusai.kb.app.parser.KbImageRef;
import com.nexusai.kb.app.parser.KbMarkdownImageResolver;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbParsedDocument;
import com.nexusai.kb.app.parser.KbParserRegistry;
import com.nexusai.kb.app.parser.KbSourceBlock;
import com.nexusai.kb.app.parser.KbSourceIndex;
import com.nexusai.kb.app.parser.KbSourceLocator;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「解析 → 图片落盘 → 分块 → 落库 → 收尾状态」的主编排器。
 *
 * <p><b>守卫顺序（每一条都对应源侧一个真实事故）</b>：
 * <ol>
 *   <li><b>行不存在</b> ⇒ 直接返回（任务幂等：文档被删了，任务不该报错重试）</li>
 *   <li><b>attempt 不匹配</b> ⇒ 直接返回。用户已重新解析，本任务属于上一轮，
 *       写进去会污染新轮的 chunk 与计数（源侧 {@code attemptSuperseded}）</li>
 *   <li><b>状态是 deleting / cancelled</b> ⇒ 直接返回。用户在等它停，不是等它跑完</li>
 *   <li><b>置 processing 失败且当前也不是 processing</b> ⇒ 直接返回（被并发改走）</li>
 * </ol>
 *
 * <p><b>事务边界</b>：解析与分块在事务外（CPU 密集、可能几十秒，不能占着 SQLite 写锁）；
 * 只有 {@link KbChunkWriter#replaceChunks} 与状态收尾各自短事务。
 *
 * <h2>⭐ 图片链路（本类是本仓唯一接线点）</h2>
 * 顺序不能调换：
 * <ol>
 *   <li><b>解析</b>：解析器把图写进 {@code workDir}（系统临时目录）并返回 {@code parsed.images()}</li>
 *   <li><b>搬走</b>：{@link KbImageStorage#store} 把图复制到持久化存储，得到可访问 URL</li>
 *   <li><b>切分</b>：对 {@code parsed.text()} 切块（坐标系 = 原文字符码点）</li>
 *   <li><b>归属</b>：按图片在原文中的位置找到覆盖它的块（见 {@link #assignImages}）</li>
 *   <li><b>落库</b>：{@code KbChunkWriter} 同事务写 {@code kb_chunk.image_info} + 投影表</li>
 *   <li><b>清理</b>：{@code finally} 递归删 {@code workDir}（此时图已经在持久化存储里）</li>
 * </ol>
 * ⛔ 第 2 步必须在第 6 步之前，否则临时目录一删图片就没了 —— 这正是本批修复的缺陷。
 * ⛔ 第 4 步必须在第 3 步之后（要用块边界），但<b>不受</b>任何 markdown 重写影响 ——
 * 本批<b>不重写 markdown</b>（见 {@link #assignImages} 的说明）。
 */
@Component
public class KnowledgeParseProcessor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeParseProcessor.class);

    /** 单次处理的结果，供 worker 决定任务标 done 还是标失败。 */
    public enum Outcome {
        /** 处理成功（或无可处理内容但流程走完）。 */
        COMPLETED,
        /** 跳过（幂等 / 陈旧任务 / 用户已取消）。⚠️ 不是失败，重试没意义。 */
        SKIPPED,
        /** 处理失败，应走重试/死信。 */
        FAILED
    }

    /**
     * PDF 的分页标题（与 {@code KbPdfParser#pageHeading} 逐字一致）。
     *
     * <p>PDF 的 markdown 由「{@code ## 第 N 页} + 正文」逐页拼成，
     * 这是在文本坐标系里定位「第 N 页从哪个字符开始」的唯一稳定锚点。
     * ⚠️ 常量在此重复一份是<b>有意的</b>：本批不允许改 parser 包，而两侧必须逐字相同，
     * 一旦漂移就会静默把图挂错页 —— 故在 {@code assignImages} 里用 {@code String.format} 现场拼，
     * 不用正则硬编码。
     */
    private static final String PDF_PAGE_HEADING_FORMAT = "## 第 %d 页";

    /**
     * Office 图片占位（与 {@code KbMarkdownHandler} 的 {@code ![image]} / {@code ![image](alt)} 同源）。
     *
     * <p>⚠️ <b>括号是可选的</b>：{@code KbMarkdownHandler} 只在 {@code <img alt>} 非空时才追加
     * {@code (alt)}，实测 Tika 对多数 Office 内嵌图不给 alt ⇒ 产出的是<b>裸 {@code ![image]}</b>。
     * 只匹配 {@code ![..](...)} 会漏掉这些占位，图就全被挂到首块。
     */
    private static final Pattern MARKDOWN_IMAGE =
        Pattern.compile("!\\[[^\\]]*\\](?:\\([^)]*\\))?");

    /**
     * 解析产出空正文时的失败文案（<b>逐字</b>写在这里，测试与实现同一个常量）。
     *
     * <p>为什么必须失败而不是「完成但 0 分块」：源站对空内容一律 {@code failed} ——
     * 空文档没有任何可检索内容，标 completed 只会让用户在「已完成」里反复搜不到，
     * 且没有任何可排查的线索。
     */
    static final String EMPTY_PARSE_MESSAGE =
        "解析结果为空：文件无可提取文本（扫描件请确认视觉模型已配置，或改用 OCR 引擎）";

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbParserRegistry parserRegistry;
    private final KbKnowledgeStateRepository stateRepository;
    private final KbChunkWriter chunkWriter;
    private final KbIndexWriter indexWriter;

    /**
     * 检索驱动注册表（W1）。
     *
     * <p>⚠️ {@link #indexWriter} 仍保留：部分内部工具方法需要直连本地索引
     * （例如测试/维护脚本）；<b>入库主路径</b>一律经注册表，
     * 否则会出现「检索查 A 驱动、写入写 B 驱动」的静默分叉。
     */
    private final com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry engineRegistry;
    private final com.nexusai.kb.config.KbConfigService kbConfigService;

    /**
     * 图片持久化存储。
     *
     * <p>⚠️ 可为 null（单测直构场景）：为 null 时图片链路<b>整体跳过</b>并留 info 日志
     * —— ⛔ 不静默、也不让解析失败（图文混排文档的正文仍然有价值）。
     */
    private final KbImageStorage imageStorage;

    /**
     * 后处理 fan-out（W6）。
     *
     * <p>开关不再写死在这里 —— 由 {@link KbPostProcessStage} 按知识库配置解析
     * （{@code summary_model_id} / {@code question_generation_config} /
     * {@code auto_tag_config} / {@code wiki_config}）。
     * 本类只负责「解析成功之后叫它一声」。
     */
    private final KbPostProcessStage postProcessStage;

    /**
     * span 树写入口（trace 观测层）· 批 2 接线。
     *
     * <p><b>为什么用字段注入而不是构造器参数</b>：本类有<b>两个</b>构造器且都被单测直构使用
     * ⇒ 改签名会连坐现有测试。字段注入 + {@code required = false} 让「老测试 / 精简装配」
     * 全程走「不记 span」的降级路径，业务行为与改造前逐字一致。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KbSpanTracker spanTracker;

    public KnowledgeParseProcessor(
        KbKnowledgeMapper knowledgeMapper,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbParserRegistry parserRegistry,
        KbKnowledgeStateRepository stateRepository,
        KbChunkWriter chunkWriter,
        KbIndexWriter indexWriter,
        com.nexusai.kb.config.KbConfigService kbConfigService,
        com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry engineRegistry,
        KbPostProcessStage postProcessStage
    ) {
        this(knowledgeMapper, knowledgeBaseMapper, parserRegistry, stateRepository, chunkWriter,
            indexWriter, kbConfigService, engineRegistry, postProcessStage, null);
    }

    /**
     * 生产构造器（带图片存储）。
     *
     * <p>⚠️ 本类有多个构造器 ⇒ Spring 必须靠 {@code @Autowired} 选中这一个
     * （只有一个构造器时才可省略）。
     *
     * @param imageStorage 图片持久化存储；{@code null} = 不落图（单测直构）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeParseProcessor(
        KbKnowledgeMapper knowledgeMapper,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbParserRegistry parserRegistry,
        KbKnowledgeStateRepository stateRepository,
        KbChunkWriter chunkWriter,
        KbIndexWriter indexWriter,
        com.nexusai.kb.config.KbConfigService kbConfigService,
        com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry engineRegistry,
        KbPostProcessStage postProcessStage,
        KbImageStorage imageStorage
    ) {
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.parserRegistry = parserRegistry;
        this.stateRepository = stateRepository;
        this.chunkWriter = chunkWriter;
        this.indexWriter = indexWriter;
        this.kbConfigService = kbConfigService;
        this.engineRegistry = engineRegistry;
        this.postProcessStage = postProcessStage;
        this.imageStorage = imageStorage;
    }

    /**
     * 处理一条 parse 任务。
     *
     * @param task 已认领（state=running）的任务
     * @return 结果分类
     */
    public Outcome processParse(KbIngestTaskRecord task) {
        String knowledgeId = task.getKnowledgeId();

        KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            log.info("[入库] 知识条目不存在，跳过任务 task={} knowledge={}", task.getId(), knowledgeId);
            return Outcome.SKIPPED;
        }

        // 守卫 1：陈旧任务
        int taskAttempt = task.getAttempt() == null ? 0 : task.getAttempt();
        int currentAttempt = knowledge.getParseAttempt() == null ? 0 : knowledge.getParseAttempt();
        if (taskAttempt != currentAttempt) {
            log.info("[入库] 陈旧任务，跳过 task={} knowledge={} 任务attempt={} 当前attempt={}",
                task.getId(), knowledgeId, taskAttempt, currentAttempt);
            return Outcome.SKIPPED;
        }

        // 守卫 2：用户已取消 / 正在删除
        String status = knowledge.getParseStatus();
        if (KbKnowledgeStateRepository.STATUS_DELETING.equals(status)
            || KbKnowledgeStateRepository.STATUS_CANCELLED.equals(status)) {
            log.info("[入库] 文档处于 {}，跳过处理 task={} knowledge={}", status, task.getId(), knowledgeId);
            return Outcome.SKIPPED;
        }

        // 守卫 3：置 processing（失败时容忍「已经是 processing」的重试场景）
        if (!stateRepository.markProcessing(knowledgeId)) {
            KbKnowledgeRecord reloaded = knowledgeMapper.selectOneById(knowledgeId);
            if (reloaded == null
                || !KbKnowledgeStateRepository.STATUS_PROCESSING.equals(reloaded.getParseStatus())) {
                log.info("[入库] 状态已变，跳过 task={} knowledge={}", task.getId(), knowledgeId);
                return Outcome.SKIPPED;
            }
        }

        Path workDir = null;
        // 本次解析已落盘的图片存储键（失败时回滚，避免留下没人引用的孤儿文件）
        List<String> storedKeys = new ArrayList<>();
        // ⭐ trace：本轮 attempt 以 kb_knowledge.parse_attempt 为准（见 openTraceAttempt）。
        int traceAttempt = openTraceAttempt(knowledgeId, currentAttempt);
        // 「尚未收口」的 stage —— catch 里据此把失败记到正确的阶段上（null ⇒ 只收口 root）
        KbSpan openStage = null;
        try {
            Path file = resolveFile(knowledge);
            workDir = Files.createTempDirectory("kb-parse-");
            KbParseContext ctx = KbParseContext.defaults().withWorkDir(workDir);

            // ⭐ 分块配置要在解析之前取：它的 parser_engine_rules 决定「用哪个解析器」
            //    （源站 knowledge_process.go:4389 `ChunkingConfig.ResolveParserEngine(fileType)`）。
            KbSplitterConfig splitterConfig = splitterConfigOf(knowledge);

            // ⭐ 解析路由：知识库配了 parser_engine_rules ⇒ 按规则选；否则走 @Order 默认顺序
            KbSpan docreaderSpan = beginTraceStage(knowledgeId, traceAttempt, "docreader",
                traceJson("file", file.getFileName() == null ? null : file.getFileName().toString()));
            openStage = docreaderSpan;
            KbParsedDocument parsed = com.nexusai.kb.app.parser.KbParserEngineRouter.parse(
                parserRegistry, file, ctx, splitterConfig.parserEngineRules());
            endTraceStage(docreaderSpan, traceJson(
                "chars", parsed.text().length(), "images", parsed.images().size(),
                "engine", parsed.metadata().get(KbParsedDocument.META_PARSER)));
            openStage = null;

            // ⭐ ⓪ 行尾归一（源侧 knowledge_create.go:1427 也在上游归一）。
            //   WHY 必须在切分之前：KbTextSplitter.split **不内部归一**（见 KbTextSplitter:360 注释：
            //   不改文本以免破坏调用方坐标），而 assignImages 用「页标题字符偏移」定位图片。
            //   两处若一个用含 CRLF 的原文、一个用归一文，坐标系就不一致 ⇒ 图片静默挂错块。
            //   ⇒ 下面 split / assignImages 必须用同一个（归一后的）字符串。
            String raw = parsed.text();
            String normalized = KbTextSplitter.normalizeLineEndings(raw);
            int crCount = countCr(raw);
            log.info("[入库] 行尾归一 knowledge={} 原长={} 归一后={} CR数={}",
                knowledgeId, raw.length(), normalized.length(), crCount);

            // ⭐ ⓪.5 空正文护栏（源站口径：解析成功但没有任何可提取文本 ⇒ failed）
            //   WHY 必须在 persistImages / 切分 / 建索引 / fanOut 之前：空正文会被切成 0 块，
            //   但旧实现照样 markIndexed + COMPLETED —— 用户看到「已完成」却一条内容都搜不到，
            //   而且全程没有任何报错。
            //   例外：解析器明确产出了图片 ⇒ 纯图文档（扫描件/照片），图片理解链会补文字。
            if (hasNoExtractableText(normalized)) {
                int producedImages = parsed.images() == null ? 0 : parsed.images().size();
                String sourceName = fileNameOf(parsed, file);
                String parserName = parsed.metadata().get(KbParsedDocument.META_PARSER);
                long fileBytes = fileBytesOf(file);
                if (producedImages > 0) {
                    log.warn("[入库] 正文为空但有 {} 张图，按纯图文档继续（图片理解链补文字）"
                            + "knowledge={} 文件={} 解析器={} 文件字节数={}",
                        producedImages, knowledgeId, sourceName, parserName, fileBytes);
                } else {
                    log.warn("[入库] 解析结果为空（无任何可提取文本）knowledge={} 文件={} 解析器={} 文件字节数={}",
                        knowledgeId, sourceName, parserName, fileBytes);
                    // ⛔ 不写分块、不建索引、不 fanOut 后处理：空文档没有任何可检索内容，
                    //    继续走完流程只会让用户看到「已完成却搜不到」。
                    failTraceStage(docreaderSpan, "EMPTY_CONTENT", EMPTY_PARSE_MESSAGE, null);
                    finalizeTraceAttemptFailed(knowledgeId, traceAttempt);
                    stateRepository.markFailed(knowledgeId, EMPTY_PARSE_MESSAGE);
                    return Outcome.FAILED;
                }
            }

            // ⭐ ① 先把图片搬到持久化存储：workDir 在 finally 里会被递归删掉
            //    ⭐ ①.0 先补抽「只存在于正文里」的图：解析引擎只吐 Markdown 时，
            //    parsed.images() 里没有任何图（图片二进制从未落盘）—— 对齐源侧 ImageResolver，
            //    从 data URI / HTML img / 相对路径 / 远程 URL 里把图找回来。
            //    ⛔ 只抽图，不改正文：分块 start/end 与溯源定位依赖原文偏移。
            parsed = withMarkdownImages(knowledge, parsed, file, workDir);
            KbSpan multimodalSpan = beginTraceStage(knowledgeId, traceAttempt, "multimodal",
                traceJson("phase", "persist", "file", fileNameOf(parsed, file)));
            openStage = multimodalSpan;
            List<KbChunk.Image> storedImages = persistImages(knowledge, parsed.images(), storedKeys);
            if (storedImages.isEmpty()) {
                // 纯文本文档：skipped（「我们选择不跑」）而不是 pending/running
                skipTraceStage(multimodalSpan, "无图片");
            } else {
                endTraceStage(multimodalSpan, traceJson("images", storedImages.size()));
            }
            openStage = null;

            // ⭐ ② 切分（坐标系 = 归一后文本的 code point 偏移）
            //    父子模式：父块用 parentChunkSize、子块用 childChunkSize；
            //    子块再走「归图片 → 加溯源 → 落库（带 parent_chunk_id）」。
            //    ⚠️ 子块的重叠按源站 DeriveParentChildConfigs（strategy.go:246）取 childSize/5。
            List<KbChunk> textChunks;
            List<KbChunk> parentChunks = List.of();
            KbSpan chunkingSpan = beginTraceStage(knowledgeId, traceAttempt, "chunking",
                traceJson("parentChild", splitterConfig.enableParentChild()));
            openStage = chunkingSpan;
            if (splitterConfig.enableParentChild()) {
                KbSplitterConfig parentCfg = new KbSplitterConfig(
                    splitterConfig.parentChunkSize(), splitterConfig.chunkOverlap(),
                    splitterConfig.separators(), splitterConfig.strategy(),
                    splitterConfig.tokenLimit(), splitterConfig.languages());
                KbSplitterConfig childCfg = new KbSplitterConfig(
                    splitterConfig.childChunkSize(),
                    Math.max(splitterConfig.childChunkSize() / 5, 0),
                    splitterConfig.separators(), splitterConfig.strategy(),
                    splitterConfig.tokenLimit(), splitterConfig.languages());
                KbTextSplitter.ParentChildResult pc =
                    KbTextSplitter.splitParentChild(normalized, parentCfg, childCfg);
                textChunks = pc.children();
                parentChunks = pc.parents();
                log.info("[入库] 父子分块 knowledge={} 子块={} 保留父块={}",
                    knowledgeId, textChunks.size(), parentChunks.size());
            } else {
                textChunks = KbTextSplitter.split(normalized, splitterConfig);
            }

            // ⭐ ③ 把图片归属到块（与 ② 同一份字符串，坐标系一致）
            List<KbChunk> chunks = assignImages(normalized, textChunks, storedImages, knowledgeId);

            // ⭐ ④ 溯源：构造分块时就算好定位，随分块一次落库（源站 knowledge_process.go:4271）
            //    ⛔ 不再有「写完之后按 chunk_index 逐块 UPDATE」的中间态：
            //    分块行一旦存在，source_locators 就是最终值。
            chunks = attachSourceLocators(chunks, parsed, normalized, knowledgeId);

            // 父块同样要溯源（源站 ParsedParentChunk.SourceLocators，knowledge_process.go:597）
            if (!parentChunks.isEmpty()) {
                parentChunks = attachSourceLocators(parentChunks, parsed, normalized, knowledgeId);
            }

            KbChunkWriter.WriteResult written =
                chunkWriter.replaceChunks(knowledge, chunks, parentChunks);
            endTraceStage(chunkingSpan, traceJson(
                "chunks", chunks.size(), "parents", parentChunks.size(),
                "bytes", written.contentBytes()));
            openStage = null;

            // ⭐ 建索引（向量 + FTS）· 必须在转 enabled 之前 —— 否则文档已可检索但索引还没建
            //    （用户会看到「已完成」却搜不到内容）。源侧同样先 BatchIndex 再 finalizeIndexed。
            //
            // ⭐ W1：写入走驱动注册表 —— 与检索同源。若知识库配了外部驱动而写入仍走本地
            //    sqlite，就会出现「检索查 A、写入写 B」的分叉（文档显示已完成但永远搜不到，
            //    且没有任何报错）。这里必须先解析驱动，未接入的驱动会在 index() 抛 fail-loud。
            KbKnowledgeBaseRecord kb = kbOf(knowledge);
            String embedModelId = resolveEmbeddingModelId(knowledge, kb);
            KbSpan embeddingSpan = beginTraceStage(knowledgeId, traceAttempt, "embedding",
                traceJson("model", embedModelId));
            openStage = embeddingSpan;
            com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine engine =
                engineRegistry.require(engineRegistry.resolveEngineType(knowledge.getKnowledgeBaseId()));
            engine.index(
                knowledge.getKnowledgeBaseId(),
                java.util.List.of(new com.nexusai.kb.app.retrieval.engine
                    .KbRetrievalEngine.IndexDocument(
                        null, knowledgeId, null, null)),
                // ⚠️ 没有嵌入模型不是错误（用户可能只用关键词检索）⇒ 传 null 只建 FTS
                embedModelId == null || embedModelId.isBlank() ? null : embedModelId);

            // ⭐ 分块落库后才允许检索可见（源侧 finalizeIndexedKnowledgeState）
            if (!stateRepository.markIndexed(knowledgeId, written.contentBytes())) {
                log.warn("[入库] 分块已写但未能置 enabled（状态已变）knowledge={}", knowledgeId);
            }
            // 没有嵌入模型不是错误：只建了关键词索引 ⇒ skipped（源站把这种情况记成 skipped）
            if (embedModelId == null || embedModelId.isBlank()) {
                skipTraceStage(embeddingSpan, "未配置嵌入模型（仅关键词检索）");
            } else {
                endTraceStage(embeddingSpan, traceJson("chunks", chunks.size()));
            }
            openStage = null;

            // ⭐ 后处理 fan-out：按知识库配置决定是否进入 finalizing 并派发子任务。
            //    ⚠️ 必须在 markIndexed 之后 —— 子任务要读已落库的分块；
            //       且文档此刻已可检索，即便后处理全失败也不影响基础问答。
            postProcessStage.fanOut(knowledge, kb, currentAttempt);
            log.info("[入库] 解析完成 knowledge={} 字符数={} 分块数={} 图片数={}",
                knowledgeId, parsed.text().length(), chunks.size(), written.imageCount());
            return Outcome.COMPLETED;

        } catch (Exception e) {
            log.error("[入库] 解析失败 knowledge={} 原因={}", knowledgeId, e.getMessage(), e);
            // trace：失败记到「尚未收口」的那个 stage 上；没有则只收口 root
            failTraceStage(openStage, failCodeOf(openStage), describe(e), e);
            finalizeTraceAttemptFailed(knowledgeId, traceAttempt);
            // ⭐ 已落盘的图片必须回滚：否则解析失败重试会不断堆积孤儿文件
            rollbackImages(storedKeys);
            stateRepository.markFailed(knowledgeId, describe(e));
            return Outcome.FAILED;
        } finally {
            cleanup(workDir);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // trace：五阶段 span 写入（对标源站 knowledge.go:307-437 的薄封装）
    // ════════════════════════════════════════════════════════════════════════

    /** 只为把 input/output 快照拼成 JSON 文本（span 列是 TEXT）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper TRACE_JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 开本轮 attempt，返回「本次 span 使用」的 attempt 号。
     *
     * <p><b>⭐⭐ 为什么不能直接用 {@code openAttempt().attempt()}</b>：
     * {@code kb_knowledge.parse_attempt} 是 attempt 的<b>既有真源</b>，而
     * {@code KbSpanTracker#openAttempt} 自己算 {@code MAX(attempt)+1}。两者会不一致：
     * <ul>
     *   <li>本功能上线前的历史文档：{@code parse_attempt} 已经是 3，span 表还是空的（latest=0）；</li>
     *   <li>reparse 后 span 写入失败 / 上次进程在 openAttempt 之前崩溃；</li>
     *   <li>span 表比 {@code parse_attempt} 领先（理论上不该发生，仍以真源为准）。</li>
     * </ul>
     * 直接用 {@code openAttempt} 的返回值会让两轮的 span 串到同一 attempt 里，
     * 前端按 attempt 切换时间线时整棵树错位。规则：
     * <ol>
     *   <li>{@code latest < currentAttempt}：循环 {@code openAttempt} 补齐到 currentAttempt
     *       （中间轮次的 root 会被下一次 openAttempt 置 {@code ATTEMPT_SUPERSEDED}，语义正确）；</li>
     *   <li>{@code latest >= currentAttempt}：沿用 currentAttempt（本轮 root 缺失时，
     *       stage 会以「无父行」记录 —— tracker 自带的降级，且 finalizeAttempt 是幂等的）；</li>
     *   <li>任何异常 ⇒ 仍返回 currentAttempt：trace 是旁路，⛔ 绝不影响入库结果。</li>
     * </ol>
     */
    private int openTraceAttempt(String knowledgeId, int currentAttempt) {
        if (spanTracker == null || knowledgeId == null || knowledgeId.isBlank() || currentAttempt <= 0) {
            return currentAttempt;
        }
        try {
            int latest = spanTracker.latestAttempt(knowledgeId);
            if (latest >= currentAttempt) {
                if (spanTracker.lookupSpanByName(knowledgeId, currentAttempt, "knowledge_processing") == null) {
                    log.warn("[入库] span 表已有 attempt={}，但本轮 attempt={} 缺 root（按无父 stage 记录）knowledge={}",
                        latest, currentAttempt, knowledgeId);
                }
                return currentAttempt;
            }
            // 逐步推进，直到 span 表的 MAX(attempt) 追上 parse_attempt。
            // 上限 16 只为防病态数据无限插入；正常情况循环 1 次（新文档）或 1~2 次（重解析）。
            int guard = 0;
            int lastAttempt = 0;
            while (guard < 16 && spanTracker.latestAttempt(knowledgeId) < currentAttempt) {
                lastAttempt = spanTracker.openAttempt(knowledgeId).attempt();
                guard++;
            }
            if (lastAttempt != currentAttempt) {
                log.warn("[入库] span attempt 未能对齐 knowledge={} 目标={} 实际={}（可能是 attempt 跳跃过大）",
                    knowledgeId, currentAttempt, lastAttempt);
            }
            return currentAttempt;
        } catch (RuntimeException e) {
            log.warn("[入库] 开启 span attempt 失败（降级为不记 span 的父节点）knowledge={} attempt={} 原因={}",
                knowledgeId, currentAttempt, e.getMessage());
            return currentAttempt;
        }
    }

    /** 开一个 stage span；tracker 缺失 / attempt 非法 ⇒ null（调用方与 tracker 都容忍 null）。 */
    private KbSpan beginTraceStage(String knowledgeId, int attempt, String stage, String inputJson) {
        if (spanTracker == null || attempt <= 0) {
            return null;
        }
        return spanTracker.beginStage(knowledgeId, attempt, stage, inputJson);
    }

    /** 成功收口一个 stage。 */
    private void endTraceStage(KbSpan span, String outputJson) {
        if (spanTracker != null) {
            spanTracker.endSpan(span, outputJson);
        }
    }

    /** 跳过（「我们选择不跑」）。 */
    private void skipTraceStage(KbSpan span, String reason) {
        if (spanTracker != null) {
            spanTracker.skipSpan(span, reason);
        }
    }

    /** 失败收口一个 stage（span 为 null ⇒ no-op）。 */
    private void failTraceStage(KbSpan span, String errorCode, String message, Throwable detail) {
        if (spanTracker != null) {
            spanTracker.failSpan(span, errorCode, message, detail);
        }
    }

    /** 收口本轮 root 为 failed（幂等；找不到 root 只 warn）。 */
    private void finalizeTraceAttemptFailed(String knowledgeId, int attempt) {
        if (spanTracker != null && attempt > 0) {
            spanTracker.finalizeAttempt(knowledgeId, attempt, "failed", null);
        }
    }

    /** 未收口 stage 对应的错误码（可读性优先于「统一一个码」）。 */
    private static String failCodeOf(KbSpan span) {
        if (span == null) {
            return "PARSE_FAILED";
        }
        return switch (span.name == null ? "" : span.name) {
            case "docreader" -> "DOCREADER_FAILED";
            case "chunking" -> "CHUNK_FAILED";
            case "embedding" -> "INDEX_FAILED";
            case "multimodal" -> "MULTIMODAL_FAILED";
            case "postprocess" -> "POSTPROCESS_FAILED";
            default -> "PARSE_FAILED";
        };
    }

    /** {@code Object...} 形式的键值对 → JSON 文本；无参数 / 序列化失败 ⇒ null。 */
    private static String traceJson(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        try {
            return TRACE_JSON.writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 溯源定位（source_locators）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 按分块区间算出源位置，附加到 {@link KbChunk#sourceLocators}（JSON 字符串）。
     *
     * <h2>⭐ 为什么是「构造时算好」而不是「落库后 UPDATE」</h2>
     * 源站 {@code knowledge_process.go:4263-4271} 在构造 {@code ParsedChunk} 时就带上
     * {@code SourceLocators: sourceIndex.Locators(c.Start, c.End)}，随分块一次落库。
     * 后置逐块 UPDATE 有三个坏处（本仓此前正是这种写法，已改掉）：
     * <ol>
     *   <li><b>N 条 UPDATE</b>：1000 块的文档 = 1000 条短事务，全是纯开销；一次写是同一批插入。</li>
     *   <li><b>中间态</b>：{@code replaceChunks} 与回写之间，
     *       {@code kb_chunk} 行已存在但 {@code source_locators} 还是 NULL ——
     *       「分块存在即完整」这条不变量被打破（虽然文档此时仍 processing，用户看不到）。</li>
     *   <li><b>坐标耦合</b>：UPDATE 靠 {@code (knowledge_id, chunk_index)} 对行，
     *       一旦 chunk_index 语义变化（父子块模式）就会静默更新到错的行。</li>
     * </ol>
     *
     * <h2>⭐ 坐标系</h2>
     * 源块是「按解析器 Markdown 记的码点区间」，而分块用的是<b>行尾归一后的文本</b>
     * ⇒ 必须先 {@link KbSourceIndex#remap} 再建索引。CRLF→LF 每删一个 {@code \r}
     * 都会让后面所有偏移左移一格，跳步会让引用整体偏移却<b>不报错</b>。
     *
     * <h2>⭐ 三个指标分开报（⛔ 不要退回只打一个覆盖率）</h2>
     * <ol>
     *   <li><b>块覆盖率</b> = 有定位的块 / 总块数 —— 唯一对全部类型都成立的指标；</li>
     *   <li><b>码点覆盖率</b> = 只对 {@code text} 类型有意义（docx/slide/sheet 的 locator
     *       没有码点区间，拿它算会恒为 0%，看着像「整条链路没生效」，实际只是类型不同）；</li>
     *   <li><b>序号型定位条数</b> = docx/slide/sheet 那类只有序号没有码点区间的小条数。</li>
     * </ol>
     *
     * @param chunks     已回填图片的分块（其 {@code start/end} 是归一后文本的码点偏移）
     * @param parsed     解析产物（含源块）
     * @param normalized 行尾归一后的正文（与分块同一份）
     * @param knowledgeId 仅用于日志
     * @return 附加了 {@code sourceLocators} 的新分块列表（无定位时该字段为 null）
     */
    List<KbChunk> attachSourceLocators(List<KbChunk> chunks, KbParsedDocument parsed,
                                       String normalized, String knowledgeId) {
        if (chunks == null || chunks.isEmpty()) {
            log.info("[溯源] 无分块，跳过源位置计算 knowledge={}", knowledgeId);
            return chunks;
        }
        List<KbSourceBlock> parserBlocks = parsed == null ? List.of() : parsed.sourceBlocks();
        if (parserBlocks.isEmpty()) {
            log.warn("[溯源] knowledge={} 解析器未产出 source block（类型={}）⇒ {} 个分块无源定位",
                knowledgeId, parsed == null ? "null" : parsed.metadata().get(KbParsedDocument.META_PARSER),
                chunks.size());
            return chunks;
        }
        int parsedCp = parsed.text().codePointCount(0, parsed.text().length());
        List<KbSourceBlock> mapped = parsed.text().equals(normalized)
            ? parserBlocks
            : KbSourceIndex.remap(parserBlocks, parsed.text(), normalized);
        KbSourceIndex index = KbSourceIndex.of(normalized, mapped);
        if (index.isEmpty()) {
            log.warn("[溯源] knowledge={} 重映射后无有效 source block，{} 个分块无源定位（原块={} 码点={}→{})",
                knowledgeId, chunks.size(), parserBlocks.size(), parsedCp,
                normalized == null ? 0 : normalized.codePointCount(0, normalized.length()));
            return chunks;
        }

        int withLocator = 0;
        int withoutLocator = 0;
        long coveredCp = 0;
        long totalCp = 0;
        int typedLocators = 0;
        List<Integer> serializeFailures = new ArrayList<>();
        List<KbChunk> out = new ArrayList<>(chunks.size());
        for (KbChunk chunk : chunks) {
            totalCp += Math.max(0, chunk.end() - chunk.start());
            List<KbSourceLocator> locs = index.locators(chunk.start(), chunk.end());
            if (locs.isEmpty()) {
                withoutLocator++;
                log.warn("[溯源] knowledge={} chunk={} 无源定位（parser 未产出 block 或区间未覆盖）区间=[{}, {})",
                    knowledgeId, chunk.seq(), chunk.start(), chunk.end());
                out.add(chunk.withSourceLocators(null));
                continue;
            }
            withLocator++;
            int covered = 0;
            for (KbSourceLocator loc : locs) {
                // text 类型才有码点区间（docx/sheet/slide 只有序号），故码点覆盖率只累加 text
                if (KbSourceLocator.TYPE_TEXT.equals(loc.type())
                    && loc.start() != null && loc.end() != null) {
                    covered += Math.max(0, loc.end() - loc.start());
                } else {
                    typedLocators++;
                }
            }
            coveredCp += covered;
            String json = KbSourceLocator.toJson(locs);
            if (json == null) {
                // 序列化失败不该让整篇文档入库失败：定位是补充信息，丢了只是不能跳转
                serializeFailures.add(chunk.seq());
            }
            out.add(chunk.withSourceLocators(json));
        }
        // ⚠️ 两个覆盖率指标必须分开报：
        //   ①「块覆盖率」= 有定位的块 / 总块数 —— 唯一对全部类型都成立的指标；
        //   ②「码点覆盖率」= 只对 text 类型有意义（docx/sheet/slide 的 locator 没有码点区间，
        //      拿它算覆盖率会恒为 0%，看着像「整条链路没生效」，实际只是类型不同）。
        double chunkCoverage = chunks.isEmpty() ? 0.0 : (100.0 * withLocator / chunks.size());
        String cpCoverage = totalCp <= 0
            ? "n/a"
            : String.format(Locale.ROOT, "%.1f%%", 100.0 * coveredCp / totalCp);
        log.info("[溯源] knowledge={} chunks={} 有定位={} 无定位={} 块覆盖率={}% 码点覆盖率={}"
                + "（仅 text 类型）序号型定位={} 源块={} 重映射后块={}",
            knowledgeId, chunks.size(), withLocator, withoutLocator,
            String.format(Locale.ROOT, "%.1f", chunkCoverage), cpCoverage,
            typedLocators, parserBlocks.size(), mapped.size());
        if (!serializeFailures.isEmpty()) {
            log.warn("[溯源] knowledge={} 序列化失败的块={}（共 {} 个）",
                knowledgeId, serializeFailures, serializeFailures.size());
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 图片链路
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 把「正文里的图片引用」补进解析产物（对齐源侧 {@code ImageResolver}）。
     *
     * <p><b>为什么需要</b>：只吐 Markdown 的解析引擎（anydoc / 纯文本类）不会产出任何图片文件，
     * 图片只剩正文里的引用形态；{@code parsed.images()} 因此为空，分块投影里也就没有图。
     * 本方法调 {@link KbMarkdownImageResolver} 把这些引用落回 {@code workDir/images}，
     * 与解析器已产出的图片<b>按绝对路径去重</b>后合并。
     *
     * <p>⛔ <b>不改 {@code parsed.text()}</b>：分块坐标与溯源定位依赖原文字符偏移。
     *
     * <p><b>fail-soft</b>：抽图失败只 WARN 并返回原产物 —— 图片是补充材料，
     * 不该让一份正文完好的文档解析失败。
     *
     * @param knowledge 知识条目（仅用于日志）
     * @param parsed    解析产物
     * @param file      原始文件（相对路径引用的基准目录）
     * @param workDir   本次解析临时目录
     * @return 合并后的产物（无新增图片时原样返回同一个实例）
     */
    private KbParsedDocument withMarkdownImages(KbKnowledgeRecord knowledge, KbParsedDocument parsed,
                                                Path file, Path workDir) {
        if (parsed == null) {
            return null;
        }
        List<KbImageRef> extracted;
        try {
            extracted = KbMarkdownImageResolver.resolve(parsed.text(), file, workDir);
        } catch (RuntimeException e) {
            log.warn("[图片] 正文图片引用抽取失败，跳过（正文照常入库）knowledge={} 原因={}",
                knowledge.getId(), e.toString());
            return parsed;
        }
        if (extracted == null || extracted.isEmpty()) {
            return parsed;
        }
        // 去重键 = 绝对路径（解析器已产出的图优先，语义相同只是来源不同）
        Map<String, KbImageRef> merged = new LinkedHashMap<>();
        for (KbImageRef ref : parsed.images()) {
            String key = imagePathKey(ref);
            if (key != null) {
                merged.putIfAbsent(key, ref);
            }
        }
        int before = merged.size();
        int added = 0;
        for (KbImageRef ref : extracted) {
            String key = imagePathKey(ref);
            if (key == null) {
                continue;
            }
            if (merged.putIfAbsent(key, ref) == null) {
                added++;
            }
        }
        if (added == 0) {
            log.info("[图片] 正文图片引用 {} 张全部与解析器产出重复 knowledge={}", extracted.size(), knowledge.getId());
            return parsed;
        }
        log.info("[图片] 正文图片引用已合并 knowledge={} 解析器产出={} 正文新增={} 去重后={}",
            knowledge.getId(), before, added, merged.size());
        return new KbParsedDocument(parsed.text(), List.copyOf(merged.values()),
            parsed.metadata(), parsed.sourceBlocks());
    }

    /** 图片去重键（规范化绝对路径）；路径为空返回 null（不可去重，直接丢）。 */
    private static String imagePathKey(KbImageRef ref) {
        if (ref == null || ref.imagePath() == null || ref.imagePath().isBlank()) {
            return null;
        }
        try {
            return Path.of(ref.imagePath()).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return ref.imagePath();
        }
    }

    /**
     * 把解析器产出的临时图片搬到持久化存储，返回已带 URL 的图片列表。
     *
     * <p><b>逐图 fail-soft</b>：单张图读/写失败只记 WARN，继续处理其余图片 ——
     * 图片是补充材料，不该让整篇文档解析失败（与解析器内部的口径一致）。
     *
     * <p><b>过滤</b>（源侧 {@code image_resolver.go:42-68}）：
     * 字节数 &lt; {@link KbImageStorage#MIN_IMAGE_BYTES} 判为图标丢弃。
     * ⚠️ 本仓 {@code KbImageRef} <b>没有宽高字段</b> ⇒ 只能按字节过滤；
     * 源侧「宽或高 &lt; 64px」那一条本批做不了（要改 parser 才能拿到尺寸）。
     * 尺寸信息在 {@code attrs} 里以「解析器若提供则带上」的形式预留，不伪造。
     *
     * @param knowledge 知识条目
     * @param refs      解析器产出的图片引用（temp 绝对路径）
     * @param storedKeys 出参：本次落盘的存储键（失败回滚用）
     * @return 已落盘的图片（顺序与 {@code refs} 一致，已跳过失败与被过滤的）
     */
    private List<KbChunk.Image> persistImages(KbKnowledgeRecord knowledge, List<KbImageRef> refs,
                                              List<String> storedKeys) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        if (imageStorage == null) {
            log.info("[图片] 未装配图片存储，跳过 {} 张图（正文照常入库）knowledge={}",
                refs.size(), knowledge.getId());
            return List.of();
        }
        List<KbChunk.Image> out = new ArrayList<>(refs.size());
        int index = 0;
        int dedupeSkip = 0;
        // 同一次解析里同一路径可能出现多次（PDF 同一张图被两页引用）⇒ 复用已存的 URL
        Map<String, KbChunk.Image> byPath = new LinkedHashMap<>();
        for (KbImageRef ref : refs) {
            if (ref == null || ref.imagePath() == null || ref.imagePath().isBlank()) {
                continue;
            }
            KbChunk.Image reused = byPath.get(ref.imagePath());
            if (reused != null) {
                dedupeSkip++;
                out.add(reused);
                continue;
            }
            index++;
            Path source;
            try {
                source = Path.of(ref.imagePath());
                if (!Files.isRegularFile(source)) {
                    log.info("[图片] 临时文件不存在，跳过 knowledge={} path={}",
                        knowledge.getId(), ref.imagePath());
                    continue;
                }
            } catch (RuntimeException e) {
                log.warn("[图片] 路径非法，跳过 knowledge={} path={} 原因={}",
                    knowledge.getId(), ref.imagePath(), e.toString());
                continue;
            }
            String imageId = String.format(Locale.ROOT, "img-%04d", index);
            try {
                KbImageStorage.Stored stored = imageStorage.store(knowledge, source, imageId);
                if (stored == null) {
                    continue;   // 被判为图标（store 内部已记日志）
                }
                storedKeys.add(stored.storageKey());
                KbChunk.Image image = toChunkImage(ref, stored);
                byPath.put(ref.imagePath(), image);
                out.add(image);
            } catch (IOException | RuntimeException e) {
                log.warn("[图片] 落盘失败，跳过该图（正文照常入库）knowledge={} path={} 原因={}",
                    knowledge.getId(), ref.imagePath(), e.toString());
            }
        }
        log.info("[图片] 持久化完成 knowledge={} 解析产出={} 可用={} 复用={}",
            knowledge.getId(), refs.size(), out.size(), dedupeSkip);
        return out;
    }

    /** 解析器引用 + 落盘结果 → 块图片（字段名必须与 {@code KbImageService#parseImageInfo} 一致）。 */
    private static KbChunk.Image toChunkImage(KbImageRef ref, KbImageStorage.Stored stored) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("source_kind", ref.sourceKind());
        attrs.put("page_index", ref.pageIndex());
        attrs.put("size_bytes", stored.sizeBytes());
        attrs.put("storage_key", stored.storageKey());
        // 尺寸：读得到才写（⛔ 不写 -1 假值，前端据此排版会得到错的比例）
        if (stored.width() > 0 && stored.height() > 0) {
            attrs.put("width", stored.width());
            attrs.put("height", stored.height());
        }
        String original = ref.imagePath();
        int slash = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
        String fileName = slash >= 0 ? original.substring(slash + 1) : original;
        return new KbChunk.Image(
            stored.url(),              // key：当前恒等于 url（源侧 url → original_url → chunk#index 的优先顺序，这里 url 恒有值）
            stored.url(),
            fileName,
            ref.caption(),
            "",
            attrs,
            0);
    }

    /**
     * 把图片归属到覆盖它的分块。
     *
     * <h2>⭐ 归属策略（方案 a：按原文位置）</h2>
     * 图片在原文里出现的位置分两路取得：
     * <ol>
     *   <li><b>PDF</b>（{@code pageIndex >= 1}）：markdown 由 {@code ## 第 N 页} + 正文逐页拼成 ⇒
     *       找 {@code "## 第 N 页"} 的字符偏移，它所在页的正文从该标题之后开始；
     *       同页多图按其在同页里的出现顺序平摊（无更细锚点时的最近似值）。</li>
     *   <li><b>Office / 其它</b>（{@code pageIndex == 0}）：markdown 里有 {@code ![image](alt)} 占位
     *       （见 {@code KbMarkdownHandler}）⇒ 按占位出现顺序与图片列表顺序 1:1 对齐。</li>
     * </ol>
     * 找到位置后：原文字符偏移 → code point 偏移（{@code KbChunk.start/end} 是 code point），
     * 取覆盖该位置的块（{@code start <= pos < end}）；位置落在块外（例如正好在块间隙）时
     * 挂到<b>位置之前最近的一块</b>，没有则为第一块。
     *
     * <h2>⛔ 本批不重写 markdown</h2>
     * 源侧把 markdown 里的图片引用原地替换成 URL。本批<b>不做</b>，因为：
     * <ul>
     *   <li>重写会改变文本长度 ⇒ {@code [start,end)} 位置不变式与分块边界全部漂移，
     *       而 {@code KbChunkRecord} 的 {@code start_at/end_at} 被文档重建依赖；</li>
     *   <li>图片 URL 已经落在 {@code kb_chunk_image} 投影表里，前端渲染时用投影替换占位即可，
     *       不需要动正文（正文被检索/embedding 用，掺 URL 反而稀释语义）。</li>
     * </ul>
     *
     * <h2>已知不精确</h2>
     * <ul>
     *   <li>PDF 同页多图会挤在同一块附近（页内没有逐图的字符锚点 —— 解析器只给 pageIndex）；</li>
     *   <li>Office 图占位数量与图片列表数量不等时按较小值对齐，多出的图挂最后一块，
     *       并打 WARN（不静默丢弃）。</li>
     * </ul>
     *
     * @param text        原文（markdown，与切分同源）
     * @param chunks      文本分块
     * @param images      已落盘图片
     * @param knowledgeId 仅用于日志
     * @return 回填过图片的分块（无图时原样返回）
     */
    List<KbChunk> assignImages(String text, List<KbChunk> chunks,
                               List<KbChunk.Image> images, String knowledgeId) {
        if (images == null || images.isEmpty()) {
            return chunks;
        }
        if (chunks == null || chunks.isEmpty()) {
            log.warn("[图片] 无分块可归属，{} 张图将被丢弃 knowledge={}", images.size(), knowledgeId);
            return chunks;
        }
        String src = text == null ? "" : text;

        // 每张图在原文中的字符偏移（-1 = 未定位）
        int[] charPositions = new int[images.size()];
        for (int i = 0; i < charPositions.length; i++) {
            charPositions[i] = -1;
        }
        // PDF：按页标题定位
        Map<Integer, Integer> pageHeadingPos = new HashMap<>();
        // 非 PDF：按 markdown 图片占位顺序定位
        List<Integer> placeholderPositions = new ArrayList<>();
        Matcher m = MARKDOWN_IMAGE.matcher(src);
        while (m.find()) {
            placeholderPositions.add(m.start());
        }

        int placeholderCursor = 0;
        int overflowCount = 0;
        for (int i = 0; i < images.size(); i++) {
            KbChunk.Image img = images.get(i);
            Object pageObj = img.attrs().get("page_index");
            int pageIndex = pageObj instanceof Number n ? n.intValue() : 0;
            if (pageIndex >= 1) {
                Integer headingPos = pageHeadingPos.get(pageIndex);
                if (headingPos == null) {
                    headingPos = src.indexOf(String.format(Locale.ROOT, PDF_PAGE_HEADING_FORMAT, pageIndex));
                    pageHeadingPos.put(pageIndex, headingPos);
                }
                // 页标题找不到（PDF 截断到 200 页后，尾部页的图没有对应标题）⇒ 保留 -1，走"挂首块"
                charPositions[i] = headingPos;
            } else if (placeholderCursor < placeholderPositions.size()) {
                charPositions[i] = placeholderPositions.get(placeholderCursor++);
            } else {
                // 占位不够（图片数多于占位）：挂到最后一个占位之后，等价于「文档尾部」
                charPositions[i] = placeholderPositions.isEmpty()
                    ? src.length()
                    : placeholderPositions.get(placeholderPositions.size() - 1);
                overflowCount++;
            }
        }
        if (overflowCount > 0) {
            log.warn("[图片] markdown 图片占位少于图片数 knowledge={} 占位={} 图片={} 多出的 {} 张挂末块",
                knowledgeId, placeholderPositions.size(), images.size(), overflowCount);
        }

        // ⭐ 字符偏移 → code point 偏移（KbChunk 的坐标系）。
        //    ⚠️ 必须单趟扫描：String#codePointCount(0, pos) 每次都要从头数，
        //    2M 字符 × 数百张图 = 数亿次遍历，长文档会明显拖慢入库。
        int[] cpPositions = new int[images.size()];
        int[] order = new int[images.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // 按字符位置升序扫（Java 数组装箱排序：图片数是个位/百位，开销可忽略）
        Integer[] boxed = new Integer[order.length];
        for (int i = 0; i < boxed.length; i++) {
            boxed[i] = i;
        }
        java.util.Arrays.sort(boxed, (a, b) -> Integer.compare(charPositions[a], charPositions[b]));
        int scannedChars = 0;
        int cpCount = 0;
        for (int idx : boxed) {
            int target = charPositions[idx] < 0 ? 0 : Math.min(charPositions[idx], src.length());
            if (target > scannedChars) {
                cpCount += src.codePointCount(scannedChars, target);
                scannedChars = target;
            }
            cpPositions[idx] = cpCount;
        }

        Map<Integer, List<KbChunk.Image>> byChunkSeq = new LinkedHashMap<>();
        int unresolved = 0;
        for (int i = 0; i < images.size(); i++) {
            if (charPositions[i] < 0) {
                unresolved++;
            }
            int seq = chunkSeqAt(chunks, cpPositions[i]);
            byChunkSeq.computeIfAbsent(seq, k -> new ArrayList<>()).add(images.get(i));
        }
        if (unresolved > 0) {
            log.warn("[图片] {} 张图未能在原文定位，已挂到首块 knowledge={}", unresolved, knowledgeId);
        }

        List<KbChunk> out = new ArrayList<>(chunks.size());
        for (KbChunk c : chunks) {
            List<KbChunk.Image> own = byChunkSeq.get(c.seq());
            out.add(own == null ? c : c.withImages(own));
        }
        log.info("[图片] 归属完成 knowledge={} 图片={} 含图块={} 未定位={}",
            knowledgeId, images.size(), byChunkSeq.size(), unresolved);
        return out;
    }

    /**
     * 找覆盖该 code point 位置的块序号。
     *
     * @param chunks 分块（按 seq 升序，区间可能因重叠而不严格互斥）
     * @param cpPos  原文 code point 偏移
     * @return 块 seq；位置在所有块之前 → 首块；在所有块之后 → 末块
     */
    private static int chunkSeqAt(List<KbChunk> chunks, int cpPos) {
        // 重叠块时取最靠前的那块（读起来更自然：图出现在块的起头附近）
        for (KbChunk c : chunks) {
            if (cpPos >= c.start() && cpPos < c.end()) {
                return c.seq();
            }
        }
        int best = chunks.get(0).seq();
        for (KbChunk c : chunks) {
            if (c.start() <= cpPos) {
                best = c.seq();
            } else {
                break;
            }
        }
        return best;
    }

    /** 回滚已落盘的图片（尽力而为，不抛错）。 */
    private void rollbackImages(List<String> storedKeys) {
        if (imageStorage == null || storedKeys == null || storedKeys.isEmpty()) {
            return;
        }
        log.info("[图片] 解析失败，回滚已落盘图片 {} 张", storedKeys.size());
        for (String key : storedKeys) {
            imageStorage.delete(key);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 其余编排辅助
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 从 knowledge / knowledgeBase 解析出实际要用的嵌入模型 ID。
     *
     * <p><b>优先级</b>：知识条目自己的 {@code embedding_model_id} &gt; 知识库的。
     * 这个顺序与源侧一致（条目可覆盖库级默认）。
     *
     * <p>⚠️ 返回 null 表示「没有可用模型」—— 不是错误，调用方会只建关键词索引。
     */
    private String resolveEmbeddingModelId(KbKnowledgeRecord knowledge, KbKnowledgeBaseRecord kb) {
        // ① 知识条目级（最具体）
        String own = knowledge.getEmbeddingModelId();
        if (own != null && !own.isBlank()) {
            return own;
        }
        // ② 知识库级（每库可覆盖）
        if (kb != null && kb.getEmbeddingModelId() != null && !kb.getEmbeddingModelId().isBlank()) {
            return kb.getEmbeddingModelId();
        }
        // ③ 全局默认（kb_config）· 用户 2026-11-04 裁定「全局默认 + 每库可覆盖」
        //    ⭐ 2026-11-05：readEmbeddingModelId() 在未显式配置时会**自动选用**第一个启用的
        //    embedding 模型 —— 用户只配了一个模型时，向量路立刻生效，不需要再去选一次。
        //    ⚠️ kbConfigService 可为 null（单测场景）⇒ 视为「未配置」，不是错误
        return kbConfigService == null ? null : kbConfigService.readEmbeddingModelId();
    }

    /** 取知识库行（可能为 null：库被删但文档还在）。 */
    private KbKnowledgeBaseRecord kbOf(KbKnowledgeRecord knowledge) {
        String kbId = knowledge.getKnowledgeBaseId();
        return kbId == null || kbId.isBlank() ? null : knowledgeBaseMapper.selectOneById(kbId);
    }

    /**
     * 取文档原文路径。
     *
     * @param knowledge 知识条目
     * @return 文件路径
     * @throws IllegalStateException 无文件路径或文件不存在
     */
    private static Path resolveFile(KbKnowledgeRecord knowledge) {
        String raw = knowledge.getFilePath();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("知识条目未关联文件路径 knowledge=" + knowledge.getId());
        }
        Path path = Path.of(raw);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("文件不存在: " + raw);
        }
        return path;
    }

    /**
     * 分块配置：优先取知识库的 {@code chunking_config}，取不到回落默认。
     *
     * @param knowledge 知识条目
     * @return 分块配置
     */
    private KbSplitterConfig splitterConfigOf(KbKnowledgeRecord knowledge) {
        String json = null;
        String kbId = knowledge.getKnowledgeBaseId();
        if (kbId != null && !kbId.isBlank()) {
            KbKnowledgeBaseRecord kb = knowledgeBaseMapper.selectOneById(kbId);
            if (kb != null) {
                json = kb.getChunkingConfig();
            }
        }
        return KbChunkingConfigParser.parse(json);
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /**
     * 归一化正文是否「没有任何可提取文本」。
     *
     * <p>判据两条，任一成立即为空：
     * <ol>
     *   <li>{@code trim()} 后为空；</li>
     *   <li>移除全部空白字符后长度为 0。</li>
     * </ol>
     *
     * <p><b>为什么第二条不是冗余</b>：{@link String#trim()} 只裁 U+0020 及以下的 ASCII 空白，
     * 中文文档里常见的全角空格 U+3000 / 不换行空格 U+00A0 会让 {@code trim()} 判为非空 ——
     * 但它确实一个文字都没有，照常入库就是「已完成但搜不到」。
     * ⚠️ 用 {@link Character#isWhitespace(int)} + {@link Character#isSpaceChar(int)} 而不是正则 {@code \s}：
     * 后者默认只认 ASCII 空白，且 {@code isWhitespace} 有意排除不换行空格（须靠 {@code isSpaceChar} 补）。
     *
     * @param text 归一化后的正文（可 null）
     * @return true = 没有任何可提取文字
     */
    static boolean hasNoExtractableText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 源文件名（仅用于日志）：优先取解析产物里的 {@code fileName}，取不到回落到磁盘文件名。
     */
    private static String fileNameOf(KbParsedDocument parsed, Path file) {
        String fromParsed = parsed == null
            ? null : parsed.metadata().get(KbParsedDocument.META_FILE_NAME);
        if (fromParsed != null && !fromParsed.isBlank()) {
            return fromParsed;
        }
        Path name = file == null ? null : file.getFileName();
        return name == null ? "" : name.toString();
    }

    /**
     * 源文件字节数（仅用于日志）。
     *
     * <p>取不到时返回 -1：⛔ 不让「打日志」这一步与业务结果耦合（量不到大小不该把解析判失败）。
     */
    private static long fileBytesOf(Path file) {
        try {
            return file == null ? -1L : Files.size(file);
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * 统计 CR 数量（用于「行尾归一到没归到」的可观测性）。
     *
     * <p>WHY 单独打这一行：CRLF 会让逐行正则失配、标题识别归零，而现象是「分块结果莫名其妙变差」，
     * 没有这个数字就只能靠猜。
     */
    static int countCr(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\r') {
                n++;
            }
        }
        return n;
    }

    private static void cleanup(Path workDir) {
        if (workDir == null) {
            return;
        }
        try {
            try (var stream = Files.walk(workDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 清理失败不影响业务结果
                    }
                });
            }
        } catch (IOException e) {
            log.debug("[入库] 临时目录清理失败 dir={} 原因={}", workDir, e.getMessage());
        }
    }
}
