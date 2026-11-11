package com.nexusai.kb.app.ingest;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.app.parser.KbOfficeParser;
import com.nexusai.kb.app.parser.KbParserRegistry;
import com.nexusai.kb.app.parser.KbTextParser;
import com.nexusai.kb.domain.KnowledgeIngestService;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
import com.nexusai.kb.repo.mapper.KbIngestTaskMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库编排意图测试 · 批次 B2c。
 *
 * <p><b>WHY 这些用例必须存在</b>：本批有三条「写错了不会立刻报错、但线上会永久卡死或静默污染」的性质，
 * 每条都对应源侧一个真实事故记录：
 * <ol>
 *   <li><b>并发递减只能提升一次</b>：源侧注释记录了 {@code finalizing} 永久卡死 +
 *       {@code pending_subtasks_count} 停在非零的 bug，根因是「先 SELECT 判计数」。
 *       本用例用 10 个线程真并发打同一行，断言恰好 1 个提升。</li>
 *   <li><b>陈旧任务必须一字节都不写</b>：用户重新解析后，上一轮任务若继续写，
 *       会把旧分块塞进新轮。用例构造 attempt 不匹配并断言 {@code kb_chunk} 行数为 0。</li>
 *   <li><b>deleting 守卫</b>：用户在等它停，不是等它跑完。</li>
 * </ol>
 */
@DisplayName("[B2c] 入库编排（并发提升 + 陈旧任务 + 删除守卫 + 端到端）")
class KnowledgeIngestTest {

    private static KbKnowledgeMapper knowledgeMapper;
    private static KbKnowledgeBaseMapper knowledgeBaseMapper;
    private static KbChunkMapper chunkMapper;
    private static KbIngestTaskMapper taskMapper;
    private static KbKnowledgeStateRepository stateRepository;
    private static KbIngestTaskQueue queue;
    private static KnowledgeParseProcessor parseProcessor;
    private static KbIngestWorker worker;
    private static Path dbPath;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds,
            KbKnowledgeMapper.class,
            KbKnowledgeBaseMapper.class,
            KbChunkMapper.class,
            KbEmbeddingMapper.class,
            KbIngestTaskMapper.class);

        knowledgeMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeMapper.class);
        knowledgeBaseMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeBaseMapper.class);
        chunkMapper = MybatisFlexBootstrap.getInstance().getMapper(KbChunkMapper.class);
        taskMapper = MybatisFlexBootstrap.getInstance().getMapper(KbIngestTaskMapper.class);

        stateRepository = new KbKnowledgeStateRepository(knowledgeMapper);
        queue = new KbIngestTaskQueue(taskMapper);

        // 用真实注册表（只装文本解析器：本批端到端用 .txt，Office/PDF 另有专项测试）
        KbParserRegistry registry = new KbParserRegistry(
            List.of(new KbTextParser()));
        KbChunkWriter chunkWriter = new KbChunkWriter(chunkMapper);
        // B3：索引写入器（本测试不配嵌入模型 ⇒ 只建 FTS 关键词索引）
        KbIndexWriter indexWriter = new KbIndexWriter(
            MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class),
            chunkMapper,
            new NoopEmbeddingPort(),
            ds);
        parseProcessor = new KnowledgeParseProcessor(
            knowledgeMapper, knowledgeBaseMapper, registry, stateRepository, chunkWriter, indexWriter,
            null,  // B10：本测试不涉及全局默认模型，传 null 走「未配置」分支
            // W1：驱动注册表 —— 必须与生产同源（走真实 sqlite 驱动，而非绕过）
            sqliteEngineRegistry(indexWriter, chunkMapper),
            // W6：后处理 fan-out（本测试的知识库未配模型/开关 ⇒ 不派发子任务）
            new KbPostProcessStage(chunkMapper, stateRepository, queue));
        worker = new KbIngestWorker(queue, parseProcessor, noopSummary(), noopQuestions(),
            noopAutoTag(), noopWiki(), noopGraph(), noopImageEnrich(), indexWriter,
            knowledgeMapper, knowledgeBaseMapper, stateRepository);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_chunk");
            s.executeUpdate("DELETE FROM kb_ingest_task");
            s.executeUpdate("DELETE FROM kb_knowledge");
            s.executeUpdate("DELETE FROM kb_knowledge_base");
        }
    }

    // ── 用例 1：并发递减 + 只提升一次 ──────────────────────────────────

    @Test
    @DisplayName("10 线程并发 finalizeSubtask：恰好 1 个提升为 completed，计数归 0")
    void concurrentFinalizePromotesExactlyOnce() throws Exception {
        final int slots = 10;
        String knowledgeId = insertKnowledge("processing", slots);

        assertThat(stateRepository.setFinalizing(knowledgeId, slots)).isTrue();

        AtomicInteger promotions = new AtomicInteger();
        AtomicInteger decrements = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(slots);
        ExecutorService pool = Executors.newFixedThreadPool(slots);
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < slots; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    KbKnowledgeStateRepository.FinalizeResult r =
                        stateRepository.finalizeSubtask(knowledgeId);
                    if (r.promoted()) {
                        promotions.incrementAndGet();
                    }
                    if (r.decremented()) {
                        decrements.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS))
            .as("并发任务必须在 30 秒内全部完成（SQLite 锁等待不应超时）")
            .isTrue();
        pool.shutdownNow();

        assertThat(errors).as("并发执行不应抛异常: %s", errors).isEmpty();
        assertThat(decrements.get()).as("每个线程都成功递减一个槽位").isEqualTo(slots);
        assertThat(promotions.get())
            .as("⭐ 只能有 1 个线程把状态翻成 completed（带条件 UPDATE 的原子性）")
            .isEqualTo(1);

        Row row = readKnowledge(knowledgeId);
        assertThat(row.parseStatus).isEqualTo("completed");
        assertThat(row.pendingSubtasks).isZero();
        assertThat(row.errorMessage).isEmpty();
        assertThat(row.processedAt).isNotBlank();
    }

    @Test
    @DisplayName("⭐ 槽位未归零时绝不提升：5 个槽位只剩 1 个时状态必须仍是 finalizing")
    void notPromotedUntilCounterReachesZero() throws Exception {
        String knowledgeId = insertKnowledge("processing", 0);
        assertThat(stateRepository.setFinalizing(knowledgeId, 5)).isTrue();

        // 释放 4 个槽位：计数 5→1，⛔ 此时绝不能变 completed
        for (int i = 0; i < 4; i++) {
            KbKnowledgeStateRepository.FinalizeResult r = stateRepository.finalizeSubtask(knowledgeId);
            assertThat(r.decremented()).isTrue();
            assertThat(r.promoted())
                .as("还剩 %d 个槽位未完成，不得提升为 completed", 4 - i)
                .isFalse();
        }
        Row afterFour = readKnowledge(knowledgeId);
        assertThat(afterFour.parseStatus)
            .as("⭐ 计数未归零时状态必须仍为 finalizing（提前 completed = 提前告诉用户已完成）")
            .isEqualTo("finalizing");
        assertThat(afterFour.pendingSubtasks).isEqualTo(1);

        // 释放最后一个：这才允许提升
        KbKnowledgeStateRepository.FinalizeResult last = stateRepository.finalizeSubtask(knowledgeId);
        assertThat(last.decremented()).isTrue();
        assertThat(last.promoted()).isTrue();
        assertThat(readKnowledge(knowledgeId).parseStatus).isEqualTo("completed");
        assertThat(readKnowledge(knowledgeId).pendingSubtasks).isZero();
    }

    @Test
    @DisplayName("槽位已为 0 时的重复回调：不递减、不重复提升")
    void repeatedFinalizeAfterZeroIsNoop() throws Exception {
        String knowledgeId = insertKnowledge("processing", 1);
        stateRepository.setFinalizing(knowledgeId, 1);

        KbKnowledgeStateRepository.FinalizeResult first = stateRepository.finalizeSubtask(knowledgeId);
        assertThat(first.decremented()).isTrue();
        assertThat(first.promoted()).isTrue();

        KbKnowledgeStateRepository.FinalizeResult second = stateRepository.finalizeSubtask(knowledgeId);
        assertThat(second.decremented()).isFalse();
        assertThat(second.promoted()).as("已是 completed，不会被再次提升").isFalse();
    }

    // ── 用例 2：陈旧任务不写任何东西 ──────────────────────────────────

    @Test
    @DisplayName("attempt 不匹配的陈旧任务：不写分块、不改状态")
    void staleAttemptWritesNothing() throws Exception {
        Path file = tempTextFile("陈旧任务测试内容" + "甲".repeat(600));
        String knowledgeId = insertKnowledgeWithFile("pending", 0, file);
        // 库里 attempt = 2（用户已重新解析）
        setAttempt(knowledgeId, 2);

        // 任务携带旧 attempt = 1
        long taskId = queue.enqueue(knowledgeId, KbIngestTaskRecord.TYPE_PARSE, 1, null);
        worker.drainBatch(1);

        assertThat(countChunks(knowledgeId)).as("⭐ 陈旧任务不得写入任何分块").isZero();
        Row row = readKnowledge(knowledgeId);
        assertThat(row.parseStatus).as("状态不得被陈旧任务改写").isEqualTo("pending");
        assertThat(readTaskState(taskId)).isEqualTo(KbIngestTaskRecord.STATE_DONE);
    }

    // ── 用例 3：deleting 守卫 ────────────────────────────────────────

    @Test
    @DisplayName("parse_status=deleting 时执行解析任务：不处理、状态不变")
    void deletingStatusSkipsProcessing() throws Exception {
        Path file = tempTextFile("删除中不应处理" + "乙".repeat(600));
        String knowledgeId = insertKnowledgeWithFile("deleting", 0, file);

        long taskId = queue.enqueue(knowledgeId, KbIngestTaskRecord.TYPE_PARSE, 0, null);
        worker.drainBatch(1);

        assertThat(countChunks(knowledgeId)).isZero();
        assertThat(readKnowledge(knowledgeId).parseStatus).isEqualTo("deleting");
        assertThat(readTaskState(taskId)).isEqualTo(KbIngestTaskRecord.STATE_DONE);
    }

    @Test
    @DisplayName("cancelled 时执行解析任务：不处理")
    void cancelledStatusSkipsProcessing() throws Exception {
        Path file = tempTextFile("已取消不应处理" + "丙".repeat(600));
        String knowledgeId = insertKnowledgeWithFile("cancelled", 0, file);

        queue.enqueue(knowledgeId, KbIngestTaskRecord.TYPE_PARSE, 0, null);
        worker.drainBatch(1);

        assertThat(countChunks(knowledgeId)).isZero();
        assertThat(readKnowledge(knowledgeId).parseStatus).isEqualTo("cancelled");
    }

    // ── 用例 4：端到端 ───────────────────────────────────────────────

    @Test
    @DisplayName("端到端：真实文本文件 → 提交 → worker → completed + 分块与顺序链正确")
    void endToEndParsesAndPersistsChunks() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append("第").append(i).append("段：这是一段用于验证分块与顺序链的中文正文内容。\n\n");
        }
        Path file = tempTextFile(body.toString());

        String baseId = insertKnowledgeBase();
        String knowledgeId = insertKnowledgeWithBase("unprocessed", 0, file, baseId);
        setAttempt(knowledgeId, 0);

        KnowledgeIngestService service = new KnowledgeIngestService(
            knowledgeMapper, stateRepository, queue);
        long taskId = service.submitParse(knowledgeId);

        assertThat(readTaskState(taskId)).isEqualTo(KbIngestTaskRecord.STATE_QUEUED);

        worker.drainBatch(1);
        // ⭐ 解析完成后会 fan-out 摘要任务（源码语义：只判「有文本块」，不看模型，
        //    模型缺失在执行期才标 failed）。drainBatch(1) 只跑一条 ⇒ 再跑一轮把摘要消费掉，
        //    否则 pending_subtasks 差 1、文档卡在 finalizing（实测过）。
        worker.drainBatch(4);

        Row row = readKnowledge(knowledgeId);
        assertThat(row.parseStatus).as("⭐ 解析成功后必须 completed").isEqualTo("completed");
        assertThat(row.processedAt).isNotBlank();
        assertThat(row.pendingSubtasks).isZero();
        assertThat(row.enableStatus)
            .as("⭐⭐ 检索链路只查 enabled —— 解析成功后必须转 enabled，否则文档永远搜不到")
            .isEqualTo("enabled");
        assertThat(row.storageSize)
            .as("分块落库后应记下 storage_size（B3 前是分块内容字节总和的近似）")
            .isPositive();

        List<ChunkRow> chunks = readChunks(knowledgeId);
        assertThat(chunks).as("必须产出分块").isNotEmpty();

        // chunk_index 从 0 连续
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).chunkIndex).as("chunk_index 必须从 0 连续递增").isEqualTo(i);
        }
        // 顺序链：首块 pre 为空，末块 next 为空，中间首尾相接
        assertThat(chunks.get(0).preChunkId).isNull();
        assertThat(chunks.get(chunks.size() - 1).nextChunkId).isNull();
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).nextChunkId)
                .as("第 %d 块的 next 必须指向第 %d 块", i, i + 1)
                .isEqualTo(chunks.get(i + 1).id);
            assertThat(chunks.get(i + 1).preChunkId)
                .as("第 %d 块的 pre 必须指向第 %d 块", i + 1, i)
                .isEqualTo(chunks.get(i).id);
        }
        // 位置不变式：end - start <= content 的字符数（O-11 真判据）
        String fullText = Files.readString(file, StandardCharsets.UTF_8);
        int totalRunes = fullText.codePointCount(0, fullText.length());
        for (ChunkRow c : chunks) {
            int contentRunes = c.content.codePointCount(0, c.content.length());
            assertThat(c.startAt).isGreaterThanOrEqualTo(0);
            assertThat(c.endAt).isLessThanOrEqualTo(totalRunes);
            assertThat(c.endAt - c.startAt)
                .as("块 %d 的 span 不得超过内容字符数（表头前置例外）", c.chunkIndex)
                .isLessThanOrEqualTo(contentRunes);
        }
    }

    @Test
    @DisplayName("重新解析：旧分块被逻辑删除，新分块 topology 重建")
    void reparseReplacesChunks() throws Exception {
        String text = "甲".repeat(1200);
        Path file = tempTextFile(text);
        String baseId = insertKnowledgeBase();
        String knowledgeId = insertKnowledgeWithBase("unprocessed", 0, file, baseId);
        setAttempt(knowledgeId, 0);

        KnowledgeIngestService service = new KnowledgeIngestService(
            knowledgeMapper, stateRepository, queue);
        service.submitParse(knowledgeId);
        worker.drainBatch(1);
        int firstRound = countChunks(knowledgeId);
        assertThat(firstRound).isPositive();

        service.submitParse(knowledgeId);
        worker.drainBatch(1);
        int secondRound = countChunks(knowledgeId);

        assertThat(secondRound).as("重解析后可见分块数应为新一轮的数量").isPositive();
        // 物理库里旧行仍在（逻辑删），但可见行只有新一轮
        assertThat(countAllChunkRowsIncludingDeleted(knowledgeId))
            .as("旧分块是逻辑删除，物理行保留")
            .isEqualTo(firstRound + secondRound);
    }

    // ── 用例 5：真实 Office 文档端到端 ────────────────────────────────

    @Test
    @DisplayName("真实 .docx 端到端：Tika 解析 → 分块 → completed")
    void endToEndRealDocx() throws Exception {
        Path docx = writeRealDocx();
        String baseId = insertKnowledgeBase();
        String knowledgeId = insertKnowledgeWithBase("unprocessed", 0, docx, baseId);

        // 用装了 Office + 文本解析器的注册表（真实链路）
        KbParserRegistry registry = new KbParserRegistry(
            List.of(new KbOfficeParser(), new KbTextParser()));
        KnowledgeParseProcessor processor = new KnowledgeParseProcessor(
            knowledgeMapper, knowledgeBaseMapper, registry, stateRepository,
            new KbChunkWriter(chunkMapper),
            new KbIndexWriter(
                MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class),
                chunkMapper, new NoopEmbeddingPort(), sqlite()),
            null,
            sqliteEngineRegistry(
                new KbIndexWriter(
                    MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class),
                    chunkMapper, new NoopEmbeddingPort(), sqlite()),
                chunkMapper),
            new KbPostProcessStage(chunkMapper, stateRepository, queue));
        KbIngestWorker realWorker = new KbIngestWorker(queue, processor, noopSummary(),
            noopQuestions(), noopAutoTag(), noopWiki(), noopGraph(), noopImageEnrich(),
            // 这两个用例不测图片理解重索引 ⇒ indexWriter 传 null（reindexQuietly 会短路）
            null, knowledgeMapper, knowledgeBaseMapper, stateRepository);

        new KnowledgeIngestService(knowledgeMapper, stateRepository, queue)
            .submitParse(knowledgeId);
        realWorker.drainBatch(1);
        // 同上：解析后 fan-out 的摘要在第二轮消费（本测试知识库未配模型 ⇒ 执行期标 failed 并释放槽位）
        realWorker.drainBatch(4);

        Row row = readKnowledge(knowledgeId);
        assertThat(row.parseStatus).as("真实 docx 必须解析成功").isEqualTo("completed");
        assertThat(row.errorMessage).isEmpty();

        List<ChunkRow> chunks = readChunks(knowledgeId);
        assertThat(chunks).as("真实 docx 必须产出分块").isNotEmpty();
        assertThat(chunks.get(0).content)
            .as("解析结果必须真的含 docx 里的中文正文（证明不是空解析）")
            .contains("知识库入库端到端验证");
    }

    // ── 用例 5b：source_locators 真的落库 ──────────────────────────────

    /**
     * <b>WHY 必须有这一条</b>：{@code kb_chunk.source_locators} 在本批之前<b>全库恒为 NULL</b>，
     * 而「列存在 + 实体有 getter + 前端有类型」让缺陷看起来像「只是前端没渲染」。
     * 判据只能是<b>入完库回表读这一列</b>：算法单测全绿也不能证明它被写进去了。
     *
     * <p>同时断言两件事：①列非 NULL 且是合法 JSON 数组；②内容里真的带 docx 定位（{@code "type":"docx"}）。
     * 只断言①会被「写了个空对象」骗过。
     */
    @Test
    @DisplayName("⭐ 端到端：kb_chunk.source_locators 非 NULL 且内容是真的 docx 定位")
    void endToEndWritesSourceLocators() throws Exception {
        Path docx = writeRealDocx();
        String baseId = insertKnowledgeBase();
        String knowledgeId = insertKnowledgeWithBase("unprocessed", 0, docx, baseId);

        KbParserRegistry registry = new KbParserRegistry(
            List.of(new KbOfficeParser(), new KbTextParser()));
        KnowledgeParseProcessor processor = new KnowledgeParseProcessor(
            knowledgeMapper, knowledgeBaseMapper, registry, stateRepository,
            new KbChunkWriter(chunkMapper),
            new KbIndexWriter(
                MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class),
                chunkMapper, new NoopEmbeddingPort(), sqlite()),
            null,
            sqliteEngineRegistry(
                new KbIndexWriter(
                    MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class),
                    chunkMapper, new NoopEmbeddingPort(), sqlite()),
                chunkMapper),
            new KbPostProcessStage(chunkMapper, stateRepository, queue),
            // ⭐ 溯源随分块一次落库（KbChunk.sourceLocators → KbChunkWriter），
            //    故这里不再需要给编排器传 chunkMapper
            null);
        KbIngestWorker realWorker = new KbIngestWorker(queue, processor, noopSummary(),
            noopQuestions(), noopAutoTag(), noopWiki(), noopGraph(), noopImageEnrich(),
            // 这两个用例不测图片理解重索引 ⇒ indexWriter 传 null（reindexQuietly 会短路）
            null, knowledgeMapper, knowledgeBaseMapper, stateRepository);

        new KnowledgeIngestService(knowledgeMapper, stateRepository, queue).submitParse(knowledgeId);
        realWorker.drainBatch(1);
        // 解析后 fan-out 的摘要任务在第二轮消费（同上，模型未配 ⇒ 执行期标 failed 并释放槽位）
        realWorker.drainBatch(4);

        assertThat(readKnowledge(knowledgeId).parseStatus).isEqualTo("completed");

        List<String> locators = readSourceLocators(knowledgeId);
        assertThat(locators)
            .as("⭐ source_locators 不能全是 NULL（这正是本批修复的缺陷）")
            .isNotEmpty();
        assertThat(locators.get(0))
            .as("必须是 docx 定位，而不是随便写了个 JSON")
            .contains("\"type\":\"docx\"");
    }

    // ── 用例 6：取消 ────────────────────────────────────────────────
    @Test
    @DisplayName("取消：置 cancelled 并作废未认领任务")
    void cancelMarksCancelledAndDropsQueued() throws Exception {
        Path file = tempTextFile("取消测试" + "丁".repeat(600));
        String knowledgeId = insertKnowledgeWithFile("pending", 0, file);

        KnowledgeIngestService service = new KnowledgeIngestService(
            knowledgeMapper, stateRepository, queue);
        long taskId = service.submitParse(knowledgeId);
        service.cancel(knowledgeId);

        assertThat(readKnowledge(knowledgeId).parseStatus).isEqualTo("cancelled");
        assertThat(readTaskState(taskId)).as("取消后未认领任务必须被作废").isEqualTo(KbIngestTaskRecord.STATE_DEAD);
    }

    // ── 用例 6：任务认领不重复 ──────────────────────────────────────

    @Test
    @DisplayName("并发认领：10 条任务被 3 个 worker 抢，每条只被认领一次")
    void concurrentClaimNeverDuplicates() throws Exception {
        String knowledgeId = insertKnowledge("pending", 0);
        final int taskCount = 10;
        for (int i = 0; i < taskCount; i++) {
            queue.enqueue(knowledgeId, KbIngestTaskRecord.TYPE_NOOP, 0, null);
        }

        List<Long> claimedIds = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int w = 0; w < 3; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    KbIngestTaskRecord t;
                    while ((t = queue.claimNext()) != null) {
                        claimedIds.add(t.getId());
                    }
                } catch (Exception ignored) {
                    // 并发下 SQLite 可能偶发 BUSY，不影响「不重复」这条断言
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(claimedIds).as("同一任务不得被认领两次").doesNotHaveDuplicates();
        assertThat(claimedIds.size()).isLessThanOrEqualTo(taskCount);
    }

    // ── 支撑 ────────────────────────────────────────────────────────

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    /**
     * 真实 sqlite 驱动注册表（W1）。
     *
     * <p>⚠️ 为什么测试也要走注册表而不是直接调 {@code indexWriter}：
     * 入库路径已经改成「按驱动分派」—— 若测试继续直接调实现，
     * 就会漏掉「驱动解析错 / 未注册」这类装配缺陷（真机才炸）。
     */
    private static com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry
    sqliteEngineRegistry(KbIndexWriter writer, KbChunkMapper chunkMapper) {
        return new com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry(
            java.util.List.<com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine>of(
                new com.nexusai.kb.app.retrieval.engine.KbSqliteRetrievalEngine(
                    new com.nexusai.kb.app.retrieval.KbHybridSearcher(
                        chunkMapper,
                        MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class),
                        null,
                        sqlite()),
                    writer,
                    MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class),
                    sqlite())));
    }

    private static Path tempTextFile(String content) throws Exception {
        Path f = Files.createTempFile("kb-ingest-", ".txt");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    /**
     * 造一个真实 .docx（POI XWPF）。
     *
     * <p>⚠️ 用真实二进制而不是改扩展名的文本文件：只有真格式才能证明
     * 「Tika → 分块 → 落库」整条链路真的跑通。
     */
    private static Path writeRealDocx() throws Exception {
        Path file = Files.createTempFile("kb-ingest-", ".docx");
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                 new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            doc.createParagraph().createRun().setText("知识库入库端到端验证");
            for (int i = 0; i < 40; i++) {
                doc.createParagraph().createRun()
                    .setText("第" + i + "段：这是用于验证真实 docx 解析与分块落库的中文正文段落。");
            }
            try (var out = Files.newOutputStream(file)) {
                doc.write(out);
            }
        }
        return file;
    }

    private static String insertKnowledgeBase() throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO kb_knowledge_base (id, name, type, chunking_config, "
                    + "image_processing_config, cos_config, vlm_config, "
                    + "embedding_model_id, summary_model_id) VALUES ('" + id + "', '测试库', 'document', "
                    + "'{\"chunk_size\": 512, \"chunk_overlap\": 80, "
                    + "\"split_markers\": [\"\\n\\n\", \"\\n\", \"。\"]}', '{}', '{}', '{}', '', '')");
        }
        return id;
    }

    private static String insertKnowledge(String parseStatus, int pendingSubtasks) throws Exception {
        String baseId = insertKnowledgeBase();
        return insertKnowledgeRow(parseStatus, pendingSubtasks, null, baseId);
    }

    private static String insertKnowledgeWithFile(String parseStatus, int pending, Path file) throws Exception {
        String baseId = insertKnowledgeBase();
        return insertKnowledgeRow(parseStatus, pending, file.toString(), baseId);
    }

    private static String insertKnowledgeWithBase(
        String parseStatus, int pending, Path file, String baseId) throws Exception {
        return insertKnowledgeRow(parseStatus, pending, file.toString(), baseId);
    }

    private static String insertKnowledgeRow(
        String parseStatus, int pending, String filePath, String baseId) throws Exception {
        String id = UUID.randomUUID().toString();
        String path = filePath == null ? "" : filePath.replace("'", "''");
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                // ⚠️ enable_status 必须显式写 'disabled'（模拟创建路径）：DDL 默认是 'enabled'，
                //    若沿用默认值，「解析后才转 enabled」这条断言就永远成立（假绿）。
                "INSERT INTO kb_knowledge (id, knowledge_base_id, type, title, source, "
                    + "parse_status, pending_subtasks_count, file_path, parse_attempt, enable_status) "
                    + "VALUES ('"
                    + id + "', '" + baseId + "', 'document', '测试文档', 'manual', '"
                    + parseStatus + "', " + pending + ", '" + path + "', 0, 'disabled')");
        }
        return id;
    }

    private static void setAttempt(String knowledgeId, int attempt) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE kb_knowledge SET parse_attempt = " + attempt
                + " WHERE id = '" + knowledgeId + "'");
        }
    }

    private static int countChunks(String knowledgeId) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM kb_chunk WHERE knowledge_id = '" + knowledgeId
                     + "' AND deleted = 0")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int countAllChunkRowsIncludingDeleted(String knowledgeId) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM kb_chunk WHERE knowledge_id = '" + knowledgeId + "'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String readTaskState(long taskId) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT state FROM kb_ingest_task WHERE id = " + taskId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Row readKnowledge(String knowledgeId) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT parse_status, pending_subtasks_count, IFNULL(error_message, ''), "
                     + "IFNULL(processed_at, ''), enable_status, storage_size "
                     + "FROM kb_knowledge WHERE id = '" + knowledgeId + "'")) {
            if (!rs.next()) {
                throw new IllegalStateException("知识条目不存在: " + knowledgeId);
            }
            return new Row(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6));
        }
    }

    private static List<ChunkRow> readChunks(String knowledgeId) throws Exception {
        List<ChunkRow> out = new ArrayList<>();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT id, chunk_index, content, start_at, end_at, "
                     + "pre_chunk_id, next_chunk_id FROM kb_chunk WHERE knowledge_id = '"
                     + knowledgeId + "' AND deleted = 0 ORDER BY chunk_index")) {
            while (rs.next()) {
                out.add(new ChunkRow(
                    rs.getString(1), rs.getInt(2), rs.getString(3),
                    rs.getInt(4), rs.getInt(5),
                    rs.getString(6), rs.getString(7)));
            }
        }
        return out;
    }

    /**
     * 读 {@code kb_chunk.source_locators}（跳过 NULL 行）。
     *
     * <p>⚠️ 必须用 {@code getString} 而不是「先判空再取」：{@code wasNull} 的判定顺序在这里
     * 直接影响「NULL 行是否被算成一条空串」—— 算进去会让断言 {@code isNotEmpty()} 假绿。
     */
    private static List<String> readSourceLocators(String knowledgeId) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT source_locators FROM kb_chunk WHERE knowledge_id = '"
                     + knowledgeId + "' AND deleted = 0 ORDER BY chunk_index")) {
            while (rs.next()) {
                String v = rs.getString(1);
                if (v != null && !v.isBlank()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** 本测试不配嵌入模型 ⇒ 该端口不会被调用（配了也只是兜底）。 */
    private static final class NoopEmbeddingPort
        implements com.nexusai.kb.app.retrieval.KbEmbeddingPort {
        @Override
        public java.util.List<float[]> embed(String modelId, java.util.List<String> inputs) {
            throw new IllegalStateException("测试不应调用嵌入服务");
        }
    }

    private record Row(String parseStatus, int pendingSubtasks, String errorMessage, String processedAt,
                       String enableStatus, long storageSize) {
    }

    private record ChunkRow(String id, int chunkIndex, String content, int startAt, int endAt,
                            String preChunkId, String nextChunkId) {
    }

    // ── W6 后处理子服务的桩 ───────────────────────────────────────────
    // 本测试只验「解析 → 分块 → 索引 → 状态」，不验证后处理。
    // 桩一律返回 skipped，既不会真调模型，也不会动槽位账。

    private static KbSummaryService noopSummary() {
        return new KbSummaryService(knowledgeMapper, knowledgeBaseMapper, chunkMapper,
            stateRepository, null, null) {
            @Override
            public KbPostProcessOutcome generate(String knowledgeId, int attempt) {
                return KbPostProcessOutcome.skipped("test-stub");
            }
        };
    }

    private static KbQuestionGenerationService noopQuestions() {
        return new KbQuestionGenerationService(knowledgeMapper, knowledgeBaseMapper, chunkMapper,
            stateRepository, null, null) {
            @Override
            public KbPostProcessOutcome generateBatch(String knowledgeId, int attempt,
                                                      int batchIndex, List<String> chunkIds,
                                                      int questionCount) {
                return KbPostProcessOutcome.skipped("test-stub");
            }
        };
    }

    private static KbAutoTagService noopAutoTag() {
        return new KbAutoTagService(knowledgeMapper, knowledgeBaseMapper, chunkMapper,
            stateRepository, null, null, null) {
            @Override
            public KbPostProcessOutcome classify(String knowledgeId, int attempt) {
                return KbPostProcessOutcome.skipped("test-stub");
            }
        };
    }

    private static com.nexusai.kb.app.graph.KbGraphExtractionService noopGraph() {
        return new com.nexusai.kb.app.graph.KbGraphExtractionService(
            knowledgeMapper, knowledgeBaseMapper, chunkMapper, null, null) {
            @Override
            public com.nexusai.kb.app.graph.KbGraphOutcome extract(
                String knowledgeId, int attempt, String modelName) {
                return com.nexusai.kb.app.graph.KbGraphOutcome.skipped("test-stub");
            }
        };
    }

    private static com.nexusai.kb.app.image.KbImageEnrichService noopImageEnrich() {
        return new com.nexusai.kb.app.image.KbImageEnrichService(chunkMapper, null, null) {
            @Override
            public int enrichKnowledge(String knowledgeId) {
                return 0;
            }
        };
    }

    private static KbWikiTaskHandler noopWiki() {
        return new KbWikiTaskHandler(knowledgeMapper, null) {
            @Override
            public KbPostProcessOutcome run(String knowledgeId, int attempt, String modelName) {
                return KbPostProcessOutcome.skipped("test-stub");
            }
        };
    }
}
