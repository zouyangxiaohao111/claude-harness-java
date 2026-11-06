package com.nexusai.kb.app.wiki;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbWikiPageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wiki 页 → {@code kb_chunk} 同步（阶段 5b 的**生产端**）。
 *
 * <h2>为什么需要它</h2>
 * 源侧建了 {@code ChunkTypeWikiPage} 与 {@code wiki_boost.go} 两端，但**没有生产者**：
 * 全仓没有任何代码写入 {@code chunk_type='wiki_page'} ⇒ 上游的 WIKI_BOOST 实际是空转的。
 * 本仓要让它真的生效，必须自己把 wiki 页索引成块。
 *
 * <h2>⭐ 三条设计取舍</h2>
 * <ol>
 *   <li><b>chunk id 用 {@code wiki-{pageId}}（确定性）</b>：同页重复同步就是同一条 upsert，
 *       ⛔ 用随机 UUID 会每同步一次多一行，检索结果里同一页出现多次。</li>
 *   <li><b>{@code knowledge_id} 填 wiki 页 id</b>：不能填 {@code kb_knowledge} 的 id ——
 *       wiki 页没有对应的知识条目；也⛔ 不造假的知识条目（那会让它出现在文档列表里）。</li>
 *   <li><b>一页一块（{@code chunk_index = 0}）</b>：wiki 页是已合成的整页，再切碎会破坏
 *       「跨文档交叉引用」这个前提。</li>
 * </ol>
 *
 * <h2>⚠️ 必须在事务提交之后同步</h2>
 * 调用方（{@code KbWikiPageService.applyMergeResult}）是 {@code @Transactional}；
 * FTS5 是虚拟表只能走原生 JDBC，另取连接 ⇒ 事务内写 FTS 会自锁 {@code SQLITE_BUSY}
 * （{@code KbIndexWriter} 类注释记录了同一起事故）。⇒ 用 {@link #afterCommit} 延后。
 */
@Component
public class KbWikiChunkSync {

    private static final Logger log = LoggerFactory.getLogger(KbWikiChunkSync.class);

    /** wiki 块 id 前缀（确定性 id，便于识别与清理）。 */
    static final String CHUNK_ID_PREFIX = "wiki-";

    private final KbChunkMapper chunkMapper;
    private final KbWikiPageMapper pageMapper;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbIndexWriter indexWriter;

    public KbWikiChunkSync(
        KbChunkMapper chunkMapper,
        KbWikiPageMapper pageMapper,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbIndexWriter indexWriter
    ) {
        this.chunkMapper = chunkMapper;
        this.pageMapper = pageMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.indexWriter = indexWriter;
    }

    /** wiki 块的确定性 chunk id。 */
    static String chunkIdOf(String pageId) {
        return CHUNK_ID_PREFIX + pageId;
    }

    /**
     * 把一页 wiki 同步成块并建索引（幂等）。
     *
     * @param page 页面（null 或已删 ⇒ 移除该页的块）
     */
    public void syncPage(KbWikiPageRecord page) {
        if (page == null || page.getId() == null || page.getId().isBlank()) {
            return;
        }
        if (isDeleted(page)) {
            removePage(page.getId());
            return;
        }
        try {
            upsertChunk(page);
            // ⚠️ 索引必须建在「块已落库」之后；无嵌入模型时它自己只建 FTS
            indexWriter.reindexKnowledge(page.getId(), page.getKnowledgeBaseId(),
                embeddingModelOf(page.getKnowledgeBaseId()));
        } catch (RuntimeException e) {
            // 索引是增强：失败不该让 wiki 编辑本身回滚（用户看到的是页已保存）
            log.error("[Wiki] 页面块已写入但索引失败 page={} —— 该页暂时搜不到: {}",
                page.getId(), e.getMessage());
        }
    }

    /**
     * 删除某页对应的 wiki 块与索引。
     *
     * @param pageId 页面 id
     */
    public void removePage(String pageId) {
        if (pageId == null || pageId.isBlank()) {
            return;
        }
        try {
            // 先清索引（向量 + FTS），再删块 —— 顺序反了会留下指向已删块的索引行
            indexWriter.clearIndex(pageId);
            chunkMapper.deleteByQuery(QueryWrapper.create()
                .where("id = ?", chunkIdOf(pageId))
                .and("chunk_type = ?", KbHybridSearcher.Hit.TYPE_WIKI_PAGE));
            log.info("[Wiki] 页面块已移除 page={}", pageId);
        } catch (RuntimeException e) {
            log.error("[Wiki] 移除页面块失败 page={}: {}", pageId, e.getMessage());
        }
    }

    /**
     * 全库重建：upsert 现有全部页，并删除「库内已无对应页」的旧 wiki 块。
     *
     * <p><b>⭐ 为什么要删孤儿</b>：页被删掉时如果同步失败（进程崩溃、异常），
     * 块会留在 {@code kb_chunk} 里继续被检索到 —— 用户会搜到一个已经不存在的页面。
     * 重建时按「本次写到的 id 集合」反查孤儿，是唯一能兜住这种遗漏的地方。
     *
     * @param kbId 知识库 ID
     * @return 同步的页数
     */
    public int syncKnowledgeBase(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return 0;
        }
        List<KbWikiPageRecord> pages = pageMapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId)
            .and("deleted = 0"));
        Set<String> keep = new HashSet<>();
        int synced = 0;
        for (KbWikiPageRecord p : pages) {
            if (p == null || p.getId() == null || isDeleted(p)) {
                continue;
            }
            try {
                upsertChunk(p);
                keep.add(chunkIdOf(p.getId()));
                synced++;
            } catch (RuntimeException e) {
                log.warn("[Wiki] 全库同步时单页失败，跳过 page={}: {}", p.getId(), e.getMessage());
            }
        }
        // 删孤儿（库内 wiki 块里、本次没写到的）
        int removed = 0;
        try {
            List<KbChunkRecord> existing = chunkMapper.selectListByQuery(QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and("chunk_type = ?", KbHybridSearcher.Hit.TYPE_WIKI_PAGE));
            for (KbChunkRecord c : existing) {
                if (c == null || c.getId() == null || keep.contains(c.getId())) {
                    continue;
                }
                chunkMapper.deleteById(c.getId());
                removed++;
            }
        } catch (RuntimeException e) {
            log.warn("[Wiki] 清理孤儿 wiki 块失败 kb={}: {}", kbId, e.getMessage());
        }
        // 索引整库重建（每页一条 knowledge_id，逐页重建才不会互相清掉）
        for (String pageChunkKept : keep) {
            String pageId = pageChunkKept.substring(CHUNK_ID_PREFIX.length());
            try {
                indexWriter.reindexKnowledge(pageId, kbId, embeddingModelOf(kbId));
            } catch (RuntimeException e) {
                log.warn("[Wiki] 重建页索引失败 page={}: {}", pageId, e.getMessage());
            }
        }
        log.info("[Wiki] 全库同步完成 kb={} 页={} 清理孤儿块={}", kbId, synced, removed);
        return synced;
    }

    /**
     * 注册「事务提交后移除」。
     *
     * <p>🔴 为什么不直接调 {@link #removePage}：调用方 {@code deletePage} 是
     * {@code @Transactional}，而清索引要写 FTS5（虚拟表 ⇒ 原生 JDBC 另取连接）⇒
     * 事务内做会自锁 {@code SQLITE_BUSY}。**真机实测过**：页面删了、块与索引全留着，
     * 用户还能搜到一个已不存在的页面。
     *
     * <p>无事务上下文（单测/直接调用）⇒ 立即执行。
     *
     * @param pageId 页面 id
     */
    public void removeAfterCommit(String pageId) {
        if (pageId == null || pageId.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            removePage(pageId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                removePage(pageId);
            }
        });
    }

    /**
     * 注册「事务提交后同步」。
     *
     * <p>无事务上下文（直接调用 / 单测）⇒ 立即同步，否则单测里什么都验不到。
     *
     * @param page 要同步的页（null ⇒ 不动）
     */
    public void syncAfterCommit(KbWikiPageRecord page) {
        if (page == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            syncPage(page);
            return;
        }
        // ⚠️ 必须拷贝一份需要的字段值：afterCommit 时实体可能已被改（同事务内复用对象）
        final String pageId = page.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // afterCommit 里重新读一次，拿到的是提交后的权威状态（页可能已删/已改）
                KbWikiPageRecord fresh = pageMapper.selectOneById(pageId);
                if (fresh == null) {
                    removePage(pageId);
                } else {
                    syncPage(fresh);
                }
            }
        });
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private void upsertChunk(KbWikiPageRecord page) {
        String chunkId = chunkIdOf(page.getId());
        KbChunkRecord existing = chunkMapper.selectOneById(chunkId);
        String title = page.getTitle() == null ? "" : page.getTitle().strip();
        String content = page.getContent() == null ? "" : page.getContent();
        // ⭐ 标题进正文：关键词路才能命中标题词（源侧 wiki 页检索同样按标题召回）
        String body = title.isEmpty() ? content : ("# " + title + "\n\n" + content);
        int len = body.codePointCount(0, body.length());

        boolean isNew = existing == null;
        KbChunkRecord row = isNew ? new KbChunkRecord() : existing;
        row.setId(chunkId);
        row.setKnowledgeBaseId(page.getKnowledgeBaseId());
        // ⚠️ knowledge_id 填页 id（没有对应知识条目；⛔ 不造假行）
        row.setKnowledgeId(page.getId());
        row.setContent(body);
        row.setChunkIndex(0);
        row.setIsEnabled(1);
        row.setStartAt(0);
        row.setEndAt(len);
        row.setChunkType(KbHybridSearcher.Hit.TYPE_WIKI_PAGE);
        // 表头与正文分开：embedding 前置表头，落库正文不含表头（与普通块同口径）
        row.setContextHeader(title);
        if (isNew) {
            // ⚠️ Flex 的 insert 会把 null 也列进 INSERT 清单 ⇒ NOT NULL 列必须显式赋值
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
            row.setCreateTime(com.nexusai.kb.repo.KbTimeFormat.parse(
                com.nexusai.kb.repo.KbTimeFormat.now()));
            row.setUpdateTime(row.getCreateTime());
            chunkMapper.insert(row);
        } else {
            row.setUpdateTime(com.nexusai.kb.repo.KbTimeFormat.parse(
                com.nexusai.kb.repo.KbTimeFormat.now()));
            chunkMapper.update(row, false);
        }
    }

    private String embeddingModelOf(String kbId) {
        try {
            KbKnowledgeBaseRecord kb = knowledgeBaseMapper.selectOneById(kbId);
            if (kb == null || kb.getEmbeddingModelId() == null) {
                return null;
            }
            String id = kb.getEmbeddingModelId().strip();
            // 空串视同未配置（存量库里这个列常见 ''）
            return id.isEmpty() ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isDeleted(KbWikiPageRecord page) {
        Integer d = page.getDeleted();
        return d != null && d != 0;
    }

    /** 供测试/运维：库里现有的 wiki 块（按库）。 */
    public List<KbChunkRecord> listWikiChunks(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return List.of();
        }
        List<KbChunkRecord> got = chunkMapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId)
            .and("chunk_type = ?", KbHybridSearcher.Hit.TYPE_WIKI_PAGE));
        return new ArrayList<>(got == null ? List.of() : got);
    }
}
