package com.nexusai.kb.domain;

import com.nexusai.kb.app.ingest.KbIngestTaskQueue;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbRerankStage;
import com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry;
import com.nexusai.kb.dto.KnowledgeDto;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传落库的<b>事务边界</b>与 <b>SQLITE_BUSY 重试</b>契约。
 *
 * <h2>WHY（2026-11-12 真机缺陷：4 个 PDF 只进去 2 个）</h2>
 * 原始实现把「100MB 落盘 + 边写边算 SHA-256」（实测 ~540ms）包在 {@code @Transactional} 里，
 * 而事务第一句 {@code requireKnowledgeBase} 就已经拿住了 WAL <b>读快照</b>。同窗口里解析 worker
 * 提交了任意一次写（认领任务 / 写 chunk）⇒ 随后的 {@code INSERT} 必抛
 * {@code SQLITE_BUSY_SNAPSHOT}（SQLite 不允许把过期读快照升级成写事务，{@code busy_timeout}
 * 对它无效）⇒ 整个请求 500，行根本没插进去，前端只看到「少了一个文件」。
 *
 * <p>两个契约各锁一半：
 * <ol>
 *   <li><b>慢 IO 在事务外</b> —— 读上传流期间 {@code isActualTransactionActive()} 必须为 false；</li>
 *   <li><b>写库在事务内 + BUSY 整事务重来</b> —— 第一次 insert 抛 BUSY，第二次成功，且
 *       重试耗尽时把刚落盘的孤儿文件删掉。</li>
 * </ol>
 */
class KnowledgeServiceBusyRetryTest {

    /** 只负责「开/提交/回滚」，不碰真实数据库 —— 足够让 {@code TransactionTemplate} 与
     *  {@code TransactionSynchronizationManager} 如实反映「当前是否有活动事务」。 */
    private static final PlatformTransactionManager TX = new AbstractPlatformTransactionManager() {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op
        }
    };

    @TempDir
    Path storage;

    private KbKnowledgeMapper knowledgeMapper;
    private KbKnowledgeBaseMapper knowledgeBaseMapper;
    private KbKnowledgeStateRepository stateRepository;
    private KbIngestTaskQueue queue;
    private KnowledgeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        knowledgeMapper = mock(KbKnowledgeMapper.class);
        knowledgeBaseMapper = mock(KbKnowledgeBaseMapper.class);
        stateRepository = mock(KbKnowledgeStateRepository.class);
        queue = mock(KbIngestTaskQueue.class);

        KbKnowledgeBaseRecord kb = new KbKnowledgeBaseRecord();
        kb.setId("kb-1");
        kb.setType("document");
        kb.setEmbeddingModelId("model-embed");
        when(knowledgeBaseMapper.selectOneById("kb-1")).thenReturn(kb);

        // 查重恒空（不重复）
        when(knowledgeMapper.selectListByQuery(any())).thenReturn(List.of());
        when(stateRepository.beginAttempt(anyString())).thenReturn(1);
        // 落库后的回读
        when(knowledgeMapper.selectOneById(anyString())).thenAnswer(inv -> {
            KbKnowledgeRecord r = new KbKnowledgeRecord();
            r.setId(inv.getArgument(0));
            r.setKnowledgeBaseId("kb-1");
            r.setType("file");
            r.setParseStatus("pending");
            r.setEnableStatus("disabled");
            return r;
        });

        service = new KnowledgeService(
            knowledgeMapper, knowledgeBaseMapper, stateRepository, queue,
            mock(KbHybridSearcher.class),
            mock(KbRerankStage.class),
            null,
            mock(KbChunkMapper.class),
            mock(KbRetrievalEngineRegistry.class),
            mock(DataSource.class));
        service.setTransactionManager(TX);
    }

    @Test
    @DisplayName("慢 IO（落盘 + SHA-256）必须在事务外；INSERT 必须在事务内")
    void slowIoRunsOutsideTransaction() {
        AtomicBoolean readSawTx = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();
        byte[] body = "合同正文 hello".getBytes(StandardCharsets.UTF_8);
        InputStream content = new ByteArrayInputStream(body) {
            @Override
            public synchronized int read() {
                if (TransactionSynchronizationManager.isActualTransactionActive()) readSawTx.set(true);
                reads.incrementAndGet();
                return super.read();
            }

            @Override
            public synchronized int read(byte[] b, int off, int len) {
                if (TransactionSynchronizationManager.isActualTransactionActive()) readSawTx.set(true);
                reads.incrementAndGet();
                return super.read(b, off, len);
            }
        };

        AtomicBoolean insertSawTx = new AtomicBoolean(false);
        when(knowledgeMapper.insert(any(KbKnowledgeRecord.class))).thenAnswer(inv -> {
            insertSawTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });

        service.createFromFile("kb-1", "合同.txt", content, storage, null);

        assertThat(reads.get()).as("落盘必须真的读过流").isGreaterThan(0);
        assertThat(readSawTx.get())
            .as("落盘/哈希期间存在活动事务 ⇒ 又回到「长事务持读快照」，并发写一来 INSERT 必 BUSY")
            .isFalse();
        assertThat(insertSawTx.get())
            .as("INSERT 必须与查重/入队在同一事务内（否则半写：行有了任务没入队）")
            .isTrue();
    }

    @Test
    @DisplayName("SQLITE_BUSY_SNAPSHOT ⇒ 换新事务重来，第二次成功")
    void retriesOnBusySnapshot() throws IOException {
        AtomicInteger inserts = new AtomicInteger();
        when(knowledgeMapper.insert(any(KbKnowledgeRecord.class))).thenAnswer(inv -> {
            if (inserts.incrementAndGet() == 1) {
                throw new UncategorizedSQLException(
                    "insert kb_knowledge", "### Error updating database.",
                    new SQLException("[SQLITE_BUSY_SNAPSHOT] Another database connection has "
                        + "already written to the database (database is locked)"));
            }
            return 1;
        });

        KnowledgeDto dto = service.createFromFile("kb-1", "a.txt",
            new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), storage, null);

        assertThat(dto).isNotNull();
        assertThat(inserts.get()).as("BUSY 必须整事务重来（不是同事务内重试）").isEqualTo(2);
        verify(knowledgeMapper, times(2)).insert(any(KbKnowledgeRecord.class));
        // 成功那次落的盘必须还在（不能因第一次回滚就删）
        try (Stream<Path> files = Files.list(storage.resolve("kb-1"))) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("重试耗尽仍 BUSY ⇒ 抛错并把孤儿文件删掉")
    void deletesOrphanFileWhenBusyExhausted() throws IOException {
        when(knowledgeMapper.insert(any(KbKnowledgeRecord.class))).thenAnswer(inv -> {
            throw new UncategorizedSQLException(
                "insert kb_knowledge", "### Error updating database.",
                new SQLException("[SQLITE_BUSY_SNAPSHOT] database is locked"));
        });

        assertThatThrownBy(() -> service.createFromFile("kb-1", "b.txt",
            new ByteArrayInputStream("y".getBytes(StandardCharsets.UTF_8)), storage, null))
            .hasMessageContaining("SQLITE_BUSY");

        try (Stream<Path> files = Files.list(storage.resolve("kb-1"))) {
            assertThat(files.count())
                .as("行没落成 ⇒ 刚落的盘是孤儿，必须删掉（100MB x N 不清理会撑爆磁盘）")
                .isZero();
        }
    }

    @Test
    @DisplayName("非 BUSY 的错误不重试（立即抛出）")
    void doesNotRetryOnNonBusyError() {
        AtomicInteger inserts = new AtomicInteger();
        when(knowledgeMapper.insert(any(KbKnowledgeRecord.class))).thenAnswer(inv -> {
            inserts.incrementAndGet();
            throw new IllegalStateException("duplicate key / 代码 bug");
        });

        assertThatThrownBy(() -> service.createFromFile("kb-1", "c.txt",
            new ByteArrayInputStream("z".getBytes(StandardCharsets.UTF_8)), storage, null))
            .hasMessageContaining("duplicate key");
        assertThat(inserts.get()).as("非 BUSY 重试会把真 bug 掩盖成「偶发失败」").isEqualTo(1);
    }
}
