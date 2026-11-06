package com.nexusai.kb.app.ingest;

import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.chunk.KbChunkingConfigParser;
import com.nexusai.kb.app.chunk.KbSplitterConfig;
import com.nexusai.kb.app.chunk.KbTextSplitter;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbParsedDocument;
import com.nexusai.kb.app.parser.KbParserRegistry;
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
import java.util.List;

/**
 * 「解析 → 分块 → 落库 → 收尾状态」的主编排器。
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
     * 后处理 fan-out（W6）。
     *
     * <p>开关不再写死在这里 —— 由 {@link KbPostProcessStage} 按知识库配置解析
     * （{@code summary_model_id} / {@code question_generation_config} /
     * {@code auto_tag_config} / {@code wiki_config}）。
     * 本类只负责「解析成功之后叫它一声」。
     */
    private final KbPostProcessStage postProcessStage;

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
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.parserRegistry = parserRegistry;
        this.stateRepository = stateRepository;
        this.chunkWriter = chunkWriter;
        this.indexWriter = indexWriter;
        this.kbConfigService = kbConfigService;
        this.engineRegistry = engineRegistry;
        this.postProcessStage = postProcessStage;
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
        try {
            Path file = resolveFile(knowledge);
            workDir = Files.createTempDirectory("kb-parse-");
            KbParseContext ctx = KbParseContext.defaults().withWorkDir(workDir);

            KbParsedDocument parsed = parserRegistry.parse(file, ctx);

            KbSplitterConfig splitterConfig = splitterConfigOf(knowledge);
            List<KbChunk> chunks = KbTextSplitter.split(parsed.text(), splitterConfig);

            KbChunkWriter.WriteResult written = chunkWriter.replaceChunks(knowledge, chunks);

            // ⭐ 建索引（向量 + FTS）· 必须在转 enabled 之前 —— 否则文档已可检索但索引还没建
            //    （用户会看到「已完成」却搜不到内容）。源侧同样先 BatchIndex 再 finalizeIndexed。
            //
            // ⭐ W1：写入走驱动注册表 —— 与检索同源。若知识库配了外部驱动而写入仍走本地
            //    sqlite，就会出现「检索查 A、写入写 B」的分叉（文档显示已完成但永远搜不到，
            //    且没有任何报错）。这里必须先解析驱动，未接入的驱动会在 index() 抛 fail-loud。
            KbKnowledgeBaseRecord kb = kbOf(knowledge);
            String embedModelId = resolveEmbeddingModelId(knowledge, kb);
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

            // ⭐ 后处理 fan-out：按知识库配置决定是否进入 finalizing 并派发子任务。
            //    ⚠️ 必须在 markIndexed 之后 —— 子任务要读已落库的分块；
            //       且文档此刻已可检索，即便后处理全失败也不影响基础问答。
            postProcessStage.fanOut(knowledge, kb, currentAttempt);
            log.info("[入库] 解析完成 knowledge={} 字符数={} 分块数={}",
                knowledgeId, parsed.text().length(), chunks.size());
            return Outcome.COMPLETED;

        } catch (Exception e) {
            log.error("[入库] 解析失败 knowledge={} 原因={}", knowledgeId, e.getMessage(), e);
            stateRepository.markFailed(knowledgeId, describe(e));
            return Outcome.FAILED;
        } finally {
            cleanup(workDir);
        }
    }

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
