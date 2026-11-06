package com.nexusai.kb.app.wiki;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.entity.KbWikiFolderRecord;
import com.nexusai.kb.repo.entity.KbWikiPageIssueRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRevisionRecord;
import com.nexusai.kb.repo.mapper.KbWikiFolderMapper;
import com.nexusai.kb.repo.mapper.KbWikiPageIssueMapper;
import com.nexusai.kb.repo.mapper.KbWikiPageMapper;
import com.nexusai.kb.repo.mapper.KbWikiPageRevisionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Wiki 页面/目录持久化 · W2-6。
 *
 * <h2>⭐⭐ 两条必须在一个事务里的不变量</h2>
 * <ol>
 *   <li><b>写页面 + 写版本快照</b>：版本是 append-only 证据链。
 *       若页面写成功而版本没写，回滚时就<b>没有可回滚到的点</b> ——
 *       用户点「回滚」会拿到 null，而且不报错。</li>
 *   <li><b>{@code folder_id} 与它的冗余投影</b>（{@code category_path} / {@code wiki_path} /
 *       {@code depth}）：三者必须同步更新。分两次写 ⇒ 中途失败会留下
 *       「目录树显示 A、面包屑显示 B」的矛盾状态。</li>
 * </ol>
 *
 * <h2>⚠️ {@code slug} 唯一性只在「未删除范围内」</h2>
 * DDL 的部分唯一索引靠 {@code WHERE deleted = 0} 表达。Flex 的
 * {@code deleteById} 会改写成 {@code UPDATE deleted = 1} ⇒ 索引不再冲突，
 * 同名页面可以重建。但<b>物理删除</b>（原生 SQL）会破坏这个假设 ⇒
 * 本类不提供物理删除。
 */
@Service
public class KbWikiPageService {

    private static final Logger log = LoggerFactory.getLogger(KbWikiPageService.class);

    private final KbWikiPageMapper pageMapper;
    private final KbWikiFolderMapper folderMapper;
    private final KbWikiPageRevisionMapper revisionMapper;
    private final KbWikiPageIssueMapper issueMapper;

    /**
     * 阶段 5b 生产端：把页面同步成可检索的 chunk。
     *
     * <p>⚠️ {@code required = false}：同步是增强，不该让页面写入本身失败。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KbWikiChunkSync chunkSync;

    public KbWikiPageService(
        KbWikiPageMapper pageMapper,
        KbWikiFolderMapper folderMapper,
        KbWikiPageRevisionMapper revisionMapper,
        KbWikiPageIssueMapper issueMapper
    ) {
        this.pageMapper = pageMapper;
        this.folderMapper = folderMapper;
        this.revisionMapper = revisionMapper;
        this.issueMapper = issueMapper;
    }

    // ══════════════════════════════════════════════════════════
    // 查询
    // ══════════════════════════════════════════════════════════

    /** 列出某知识库的全部页面（不含 archived）。 */
    public List<KbWikiPageRecord> listPages(String kbId) {
        requireKb(kbId);
        return pageMapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId)
            .and("status <> ?", "archived")
            .orderBy("wiki_path", true)
            .orderBy("sort_order", true)
            .orderBy("slug", true));
    }

    /** 按 slug 取页面；不存在 ⇒ 404。 */
    public KbWikiPageRecord getBySlug(String kbId, String slug) {
        requireKb(kbId);
        String normalized = KbWikiSlug.normalize(slug);
        if (normalized == null) {
            throw new ValidationException("非法的 wiki slug: " + slug);
        }
        KbWikiPageRecord row = pageMapper.selectOneByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId)
            .and("slug = ?", normalized));
        if (row == null) {
            throw new NotFoundException("Wiki 页面不存在: " + slug);
        }
        return row;
    }

    /** 按 slug 取页面；不存在返回 null（内部用，不抛）。 */
    public KbWikiPageRecord findPage(String kbId, String slug) {
        if (kbId == null || slug == null) {
            return null;
        }
        return pageMapper.selectOneByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId)
            .and("slug = ?", slug));
    }

    /** 现有页面快照（供 Reduce 做合并基线）。 */
    public List<KbWikiMapStage.ExistingPage> existingPagesFor(String kbId) {
        List<KbWikiPageRecord> rows = listPages(kbId);
        List<KbWikiMapStage.ExistingPage> out = new ArrayList<>(rows.size());
        for (KbWikiPageRecord r : rows) {
            out.add(new KbWikiMapStage.ExistingPage(
                r.getSlug(), r.getTitle(), parseStringArray(r.getAliases())));
        }
        return out;
    }

    /** 特定 slug 的 Reduce 基线；不存在返回 null。 */
    public KbWikiReduceStage.ExistingWikiPage existingPageFor(String kbId, String slug) {
        KbWikiPageRecord r = findPage(kbId, slug);
        if (r == null) {
            return null;
        }
        return new KbWikiReduceStage.ExistingWikiPage(
            r.getTitle(), r.getContent(), r.getSummary(),
            parseStringArray(r.getAliases()), r.getPageType(),
            parseStringArray(r.getSourceRefs()), parseStringArray(r.getChunkRefs()));
    }

    /** 全部目录。 */
    public List<KbWikiFolderRecord> listFolders(String kbId) {
        requireKb(kbId);
        return folderMapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId)
            .orderBy("path", true));
    }

    /** 现有目录路径（如 "地点/地址"），供目录规划复用。 */
    public List<String> existingFolderPaths(String kbId) {
        List<String> out = new ArrayList<>();
        for (KbWikiFolderRecord f : listFolders(kbId)) {
            if (f.getPath() != null && !f.getPath().isBlank()) {
                out.add(f.getPath());
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════
    // 写入
    // ══════════════════════════════════════════════════════════

    /**
     * 应用 Reduce 结果：新建或更新页面，并写一条版本快照。
     *
     * <p>⭐ 版本快照<b>同事务</b>写入（见类注释不变量 1）。
     *
     * @param kbId   知识库 ID
     * @param slug   页面 slug
     * @param result Reduce 结果（changed() 为 false 时直接返回）
     * @param editSource 编辑来源：ingest / manual / revert
     * @return 落库后的页面；未改动返回 null
     */
    @Transactional
    public KbWikiPageRecord applyMergeResult(
        String kbId, String slug, KbWikiReduceStage.MergeResult result, String editSource
    ) {
        requireKb(kbId);
        if (result == null || !result.changed()) {
            return null;
        }

        KbWikiPageRecord existing = findPage(kbId, slug);
        boolean isNew = existing == null;
        KbWikiPageRecord row = isNew ? new KbWikiPageRecord() : existing;

        if (isNew) {
            row.setId(UUID.randomUUID().toString());
            row.setKnowledgeBaseId(kbId);
            row.setSlug(slug);
            // ⚠️ 以下每一列都必须显式赋值 —— MyBatis-Flex 的 insert 会把 null 字段
            //    也列进 INSERT 清单 ⇒ DDL 里的 DEFAULT 不生效（O-8 同类坑）。
            //    实测：漏了 parent_slug 直接撞 `NOT NULL constraint failed: kb_wiki_page.parent_slug`。
            row.setParentSlug("");
            row.setFolderId("");           // 根目录
            row.setCategoryPath("[]");
            row.setWikiPath("");
            row.setDepth(0);
            row.setSortOrder(0);
            row.setVersion(1);
            row.setStatus("published");
            row.setInLinks("[]");
            row.setOutLinks("[]");
            row.setPageMetadata("{}");
            row.setContent("");
            row.setSummary("");
            row.setAliases("[]");
            row.setSourceRefs("[]");
            row.setChunkRefs("[]");
        }

        // ⚠️ 所有 NOT NULL 列都要 null 兜底 —— result 里的字段本身可空
        //    （例如 summary 可能确实没有），但 DB 列是 NOT NULL DEFAULT ''
        //    ⇒ 直接 setSummary(null) 会把上面初始化好的 "" 覆盖掉（实测 500）。
        row.setTitle(nullToEmpty(result.title()));
        row.setContent(nullToEmpty(result.content()));
        row.setSummary(nullToEmpty(result.summary()));
        row.setPageType(result.pageType() == null || result.pageType().isBlank()
            ? "entity" : result.pageType());
        row.setAliases(toJsonArray(result.aliases()));
        row.setSourceRefs(toJsonArray(result.sourceRefs()));
        row.setChunkRefs(toJsonArray(result.chunkRefs()));

        // ⭐ out_links 从正文里的 [[...]] 重新扫（正文是唯一真源）
        List<String> outLinks = extractOutLinks(result.content());
        row.setOutLinks(toJsonArray(outLinks));

        if (!isNew) {
            row.setVersion(row.getVersion() == null ? 2 : row.getVersion() + 1);
        }

        if (isNew) {
            pageMapper.insert(row);
        } else {
            // ⚠️ update(row, false)：允许写 NULL（summary 可能确实为空）
            pageMapper.update(row, false);
        }

        // ⭐ 版本快照（同事务）
        writeRevision(row, editSource);

        // 反向链接重建（in_links 是 out_links 的镜像，必须全局重算）
        rebuildInLinks(kbId);

        // ⭐ 阶段 5b 生产端：同步成可检索块。
        //   ⚠️ 必须延后到事务提交后 —— FTS 是虚拟表只能走原生 JDBC 另取连接，
        //   在事务里写会自锁 SQLITE_BUSY（见 KbWikiChunkSync 类注释）。
        if (chunkSync != null) {
            chunkSync.syncAfterCommit(row);
        }

        log.info("[Wiki] 页面已{} kb={} slug={} 版本={} 出链={}",
            isNew ? "创建" : "更新", kbId, slug, row.getVersion(), outLinks.size());
        return row;
    }

    /**
     * 手工把页面移动到指定目录（覆盖已有归属）。
     *
     * <h2>⭐⭐ 为什么不复用 {@link #applyMergeResult}</h2>
     * 那个方法会<b>重新查库</b>取一个新的 record 再写回 —— 调用方在这里设的
     * folder 字段会被那个新对象覆盖掉。实测症状：{@code wiki_path} 恒为空、
     * {@code depth} 恒 0，<b>不报错</b>，用户点「移动」看起来成功、刷新后又回根目录。
     * ⇒ 目录字段必须在本方法内<b>同一个对象</b>上设置并写库。
     *
     * @param kbId 知识库 ID
     * @param slug 页面 slug
     * @param path 目标目录路径（空 = 根）
     * @return 落库后的页面
     */
    @Transactional
    public KbWikiPageRecord movePageToFolder(String kbId, String slug, List<String> path) {
        KbWikiPageRecord page = getBySlug(kbId, slug);
        String folderId = ensureFolderPath(kbId, path);
        page.setFolderId(folderId);
        applyFolderProjection(page, path);
        page.setVersion((page.getVersion() == null ? 1 : page.getVersion()) + 1);
        pageMapper.update(page, false);
        writeRevision(page, "manual");
        log.info("[Wiki] 页面已移动 kb={} slug={} path={}", kbId, slug,
            page.getWikiPath() == null || page.getWikiPath().isBlank() ? "（根）" : page.getWikiPath());
        return page;
    }

    /**
     * 建一条版本快照。
     *
     * <p>⚠️ append-only：⛔ 不做 UPDATE。回滚会<b>再插一条</b>而不是删旧的。
     */
    private void writeRevision(KbWikiPageRecord page, String editSource) {
        KbWikiPageRevisionRecord rev = new KbWikiPageRevisionRecord();
        rev.setId(UUID.randomUUID().toString());
        rev.setKnowledgeBaseId(page.getKnowledgeBaseId());
        rev.setPageId(page.getId());
        rev.setSlug(page.getSlug());
        rev.setVersion(page.getVersion());
        rev.setTitle(page.getTitle());
        rev.setPageType(page.getPageType());
        rev.setStatus(page.getStatus());
        rev.setContent(page.getContent());
        rev.setSummary(page.getSummary());
        rev.setAliases(page.getAliases());
        rev.setEditSource(editSource == null ? "ingest" : editSource);
        // ⚠️ NOT NULL DEFAULT '' 也救不了 —— Flex 的 insert 会把 null 显式写进去，
        //    DB DEFAULT 不生效（实测 NOT NULL constraint failed: kb_wiki_page_revision.editor_id）。
        rev.setEditorId(nullToEmpty(page.getUpdater()));
        rev.setEditedAt(com.nexusai.kb.repo.KbTimeFormat.now());
        // 其余 NOT NULL 列显式补齐（title/page_type/status/content/summary/aliases 由上面 set 过，
        //   edit_source 已设；这里补可能为 null 的）
        if (rev.getVersion() == null) {
            rev.setVersion(page.getVersion() == null ? 1 : page.getVersion());
        }
        if (rev.getStatus() == null) {
            rev.setStatus(page.getStatus() == null ? "published" : page.getStatus());
        }
        if (rev.getPageType() == null) {
            rev.setPageType(page.getPageType() == null ? "entity" : page.getPageType());
        }
        if (rev.getTitle() == null) {
            rev.setTitle("");
        }
        if (rev.getSummary() == null) {
            rev.setSummary("");
        }
        if (rev.getContent() == null) {
            rev.setContent("");
        }
        if (rev.getAliases() == null) {
            rev.setAliases("[]");
        }
        revisionMapper.insert(rev);
    }

    /** 版本历史（新→旧）。 */
    public List<KbWikiPageRevisionRecord> revisions(String kbId, String slug) {
        getBySlug(kbId, slug);   // 404 检查
        return revisionMapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId)
            .and("slug = ?", slug)
            .orderBy("version", false));
    }

    /**
     * 回滚到指定版本。
     *
     * <p>⭐⭐ <b>回滚 = 再插一条新版本</b>，不是删历史。
     * 删历史会让「谁在什么时候改回去」这件事永久丢失。
     *
     * @param kbId    知识库 ID
     * @param slug    页面 slug
     * @param version 目标版本号
     * @return 回滚后的页面
     */
    @Transactional
    public KbWikiPageRecord revert(String kbId, String slug, int version) {
        KbWikiPageRecord page = getBySlug(kbId, slug);
        KbWikiPageRevisionRecord target = revisionMapper.selectOneByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId)
            .and("slug = ?", slug)
            .and("version = ?", version));
        if (target == null) {
            throw new NotFoundException("版本不存在: " + slug + " v" + version);
        }
        page.setTitle(target.getTitle());
        page.setContent(target.getContent());
        page.setSummary(target.getSummary());
        page.setAliases(target.getAliases());
        page.setPageType(target.getPageType());
        page.setVersion((page.getVersion() == null ? 1 : page.getVersion()) + 1);
        page.setOutLinks(toJsonArray(extractOutLinks(target.getContent())));
        pageMapper.update(page, false);
        writeRevision(page, "revert");
        rebuildInLinks(kbId);
        log.info("[Wiki] 页面已回滚 kb={} slug={} 目标版本={} 新版本={}",
            kbId, slug, version, page.getVersion());
        return page;
    }

    /** 逻辑删除页面。 */
    @Transactional
    public void deletePage(String kbId, String slug) {
        KbWikiPageRecord page = getBySlug(kbId, slug);
        pageMapper.deleteById(page.getId());
        rebuildInLinks(kbId);
        // ⭐ 页删了，检索块必须跟着走 —— 否则用户还能搜到一个已不存在的页面。
        //   ⚠️ 与 applyMergeResult 同理：本方法是 @Transactional，清索引要写 FTS（原生 JDBC
        //   另取连接）⇒ 事务内做会自锁 SQLITE_BUSY（实测 2026-11-06，页面删了块还在）。
        //   ⇒ 必须延后到提交后。
        if (chunkSync != null) {
            chunkSync.removeAfterCommit(page.getId());
        }
        log.info("[Wiki] 页面已删除 kb={} slug={}", kbId, slug);
    }

    /**
     * 重建全库反向链接。
     *
     * <p>⚠️ {@code in_links} 是 {@code out_links} 的<b>镜像</b>，不能增量维护：
     * 页面 A 删掉后，所有曾链到 A 的页面的 in_links 都要变。
     * 增量维护漏一条就会产生「Lint 说没孤儿，实际有」的矛盾。
     */
    @Transactional
    public void rebuildInLinks(String kbId) {
        List<KbWikiPageRecord> pages = listPages(kbId);
        Map<String, Set<String>> inLinks = new LinkedHashMap<>();
        for (KbWikiPageRecord p : pages) {
            for (String out : parseStringArray(p.getOutLinks())) {
                inLinks.computeIfAbsent(out, k -> new LinkedHashSet<>()).add(p.getSlug());
            }
        }
        for (KbWikiPageRecord p : pages) {
            Set<String> in = inLinks.getOrDefault(p.getSlug(), Set.of());
            String newValue = toJsonArray(new ArrayList<>(in));
            if (!newValue.equals(p.getInLinks())) {
                // ⚠️ 定向列更新：只改 in_links，避免把别的并发改动覆盖回去。
                //    ⛔ 不能用 mapper.update(row)：那会整行写回，把并发改的其他列也覆盖掉。
                //    ⛔ 不能用 setRaw()：那是字面量拼接（注入面）。
                com.mybatisflex.core.update.UpdateChain.of(pageMapper)
                    .set(new com.mybatisflex.core.query.QueryColumn("in_links"), newValue)
                    .where("id = ?", p.getId())
                    .update();
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 目录
    // ══════════════════════════════════════════════════════════

    /**
     * 确保目录链存在（按 1-2 级路径逐级创建）。
     *
     * @param kbId 知识库 ID
     * @param path 目录路径（如 ["地点", "地址"]）
     * @return 最末级目录 ID；path 为空 ⇒ 空串（根）
     */
    @Transactional
    public String ensureFolderPath(String kbId, List<String> path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String parentId = "";
        StringBuilder acc = new StringBuilder();
        String lastId = "";
        for (String seg : path) {
            if (seg == null || seg.isBlank()) {
                continue;
            }
            String segTrim = seg.trim();
            acc = acc.length() == 0 ? new StringBuilder(segTrim)
                : acc.append('/').append(segTrim);
            String fullPath = acc.toString();

            KbWikiFolderRecord existing = folderMapper.selectOneByQuery(QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and("path = ?", fullPath));
            if (existing != null) {
                parentId = existing.getId();
                lastId = existing.getId();
                continue;
            }
            KbWikiFolderRecord f = new KbWikiFolderRecord();
            f.setId(UUID.randomUUID().toString());
            f.setKnowledgeBaseId(kbId);
            f.setParentId(parentId);
            f.setName(segTrim);
            f.setPath(fullPath);
            f.setDepth(acc.toString().split("/").length - 1);
            f.setSortOrder(0);
            folderMapper.insert(f);
            parentId = f.getId();
            lastId = f.getId();
            log.info("[Wiki] 新建目录 kb={} path={}", kbId, fullPath);
        }
        return lastId;
    }

    /** 把页面的目录归属设好（同时同步冗余投影）。 */
    @Transactional
    public void assignFolder(String kbId, String slug, List<String> path) {
        KbWikiPageRecord page = getBySlug(kbId, slug);
        // ⚠️ 用户手工移动过的页面要保持原位（manual edits are authoritative）
        if (page.getFolderId() != null && !page.getFolderId().isBlank()) {
            return;
        }
        String folderId = ensureFolderPath(kbId, path);
        page.setFolderId(folderId);
        applyFolderProjection(page, path);
        pageMapper.update(page, false);
    }

    /** 同步 folder_id 的三个冗余投影。 */
    private static void applyFolderProjection(KbWikiPageRecord page, List<String> path) {
        if (path == null || path.isEmpty()) {
            page.setCategoryPath("[]");
            page.setWikiPath("");
            page.setDepth(0);
            return;
        }
        page.setCategoryPath(toJsonArray(path));
        page.setWikiPath(String.join("/", path));
        page.setDepth(path.size());
    }

    // ══════════════════════════════════════════════════════════
    // Lint 问题
    // ══════════════════════════════════════════════════════════

    /**
     * 写入 Lint 结果。
     *
     * <p>⭐ 语义：{@code pending} 的旧问题先清掉重写；
     * 已 {@code resolved} 的问题<b>若再次出现</b> ⇒ 新建一条 pending
     * （⛔ 不把旧的改回 pending，那会抹掉「曾经修好过」这个事实）；
     * 已 {@code ignored} 的 ⇒ 重算时跳过（用户明确说不用管）。
     */
    @Transactional
    public int replacePendingIssues(String kbId, List<KbWikiLinter.Issue> issues) {
        Set<String> ignored = new LinkedHashSet<>();
        for (KbWikiPageIssueRecord r : issueMapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId).and("status = ?", "ignored"))) {
            // suspected_knowledge_ids 存的是 targetSlug 的单元素数组
            List<String> targets = parseStringArray(r.getSuspectedKnowledgeIds());
            ignored.add(key(r.getSlug(), r.getIssueType(),
                targets.isEmpty() ? null : targets.get(0)));
        }
        // 清掉旧的 pending
        issueMapper.deleteByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId).and("status = ?", "pending"));

        int written = 0;
        for (KbWikiLinter.Issue issue : issues) {
            if (ignored.contains(key(issue.pageSlug(), issue.type(), issue.targetSlug()))) {
                continue;
            }
            KbWikiPageIssueRecord row = new KbWikiPageIssueRecord();
            row.setId(UUID.randomUUID().toString());
            row.setKnowledgeBaseId(kbId);
            row.setSlug(issue.pageSlug());
            row.setIssueType(issue.type());
            row.setSeverity(issue.severity());
            row.setDescription(issue.description());
            row.setSuspectedKnowledgeIds(toJsonArray(
                issue.targetSlug() == null ? List.of() : List.of(issue.targetSlug())));
            row.setStatus("pending");
            row.setReportedBy("lint");
            issueMapper.insert(row);
            written++;
        }
        log.info("[Wiki] Lint 问题已写入 kb={} 新增={} 跳过(ignored)={}",
            kbId, written, issues.size() - written);
        return written;
    }

    private static String key(String slug, String type, String target) {
        return slug + "\u0000" + type + "\u0000" + (target == null ? "" : target);
    }

    /** 列出问题（可按 status 过滤）。 */
    public List<KbWikiPageIssueRecord> listIssues(String kbId, String status) {
        requireKb(kbId);
        QueryWrapper q = QueryWrapper.create().where("knowledge_base_id = ?", kbId);
        if (status != null && !status.isBlank()) {
            q.and("status = ?", status);
        }
        q.orderBy("severity", true).orderBy("slug", true);
        return issueMapper.selectListByQuery(q);
    }

    /** 更新单个问题状态。 */
    public KbWikiPageIssueRecord updateIssueStatus(String kbId, String issueId, String status) {
        if (!"pending".equals(status) && !"resolved".equals(status) && !"ignored".equals(status)) {
            throw new ValidationException("非法的问题状态: " + status);
        }
        KbWikiPageIssueRecord row = issueMapper.selectOneById(issueId);
        if (row == null || !kbId.equals(row.getKnowledgeBaseId())) {
            throw new NotFoundException("Lint 问题不存在: " + issueId);
        }
        row.setStatus(status);
        issueMapper.update(row, false);
        return row;
    }

    // ══════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════

    /** 从正文里扫 {@code [[slug|name]]} 的出链（去重保序）。 */
    public static List<String> extractOutLinks(String content) {
        Set<String> out = new LinkedHashSet<>();
        if (content == null) {
            return List.of();
        }
        int i = 0;
        while (true) {
            int start = content.indexOf("[[", i);
            if (start < 0) {
                break;
            }
            int close = content.indexOf("]]", start + 2);
            if (close < 0) {
                break;
            }
            String inner = content.substring(start + 2, close);
            String slug = KbWikiLinkifier.extractWikiSlug(inner);
            if (!slug.isEmpty()) {
                out.add(slug);
            }
            i = close + 2;
        }
        return new ArrayList<>(out);
    }

    /** JSON 数组字符串 → List。 */
    public static List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (!n.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(n.size());
            n.forEach(x -> {
                if (x != null && !x.isNull()) {
                    out.add(x.asText());
                }
            });
            return out;
        } catch (Exception e) {
            log.warn("[Wiki] JSON 数组解析失败，按空处理: {}", e.toString());
            return List.of();
        }
    }

    /** List → JSON 数组字符串。 */
    public static String toJsonArray(List<String> list) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** null → 空串（NOT NULL 列兜底；Flex 会把 null 显式写进 INSERT）。 */
    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static void requireKb(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
    }
}
