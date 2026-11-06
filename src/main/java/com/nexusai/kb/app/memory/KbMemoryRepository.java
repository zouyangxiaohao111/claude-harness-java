package com.nexusai.kb.app.memory;

import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbMemoryDocAffinityRecord;
import com.nexusai.kb.repo.entity.KbMemoryItemRecord;
import com.nexusai.kb.repo.entity.KbMemorySubjectRecord;
import com.nexusai.kb.repo.entity.KbMemoryTombstoneRecord;
import com.nexusai.kb.repo.entity.KbMemoryTopicStatRecord;
import com.nexusai.kb.repo.mapper.KbMemoryDocAffinityMapper;
import com.nexusai.kb.repo.mapper.KbMemoryItemMapper;
import com.nexusai.kb.repo.mapper.KbMemorySubjectMapper;
import com.nexusai.kb.repo.mapper.KbMemoryTombstoneMapper;
import com.nexusai.kb.repo.mapper.KbMemoryTopicStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 记忆持久化（批次 W9）· 源侧 {@code internal/application/repository/memory*.go} 的本仓对应物。
 *
 * <p><b>⚠️ 为什么大量用参数化原生 SQL 而不是 Flex 的 QueryChain</b>：
 * 本仓已在 W0–W8 反复踩过 Flex 的三个坑（见 {@code OPEN_ISSUES}）：
 * <ul>
 *   <li>{@code insert} 把 null 也列进 INSERT 清单 ⇒ DB DEFAULT 不生效</li>
 *   <li>{@code UpdateChain.set()} 按字段类型强转；{@code update()} 忽略 null ⇒ 「清空」变「什么都不做」</li>
 *   <li>{@code setRaw} 是字面量拼接（注入面）</li>
 * </ul>
 * 记忆的写入路径全是「有条件地改某一列」，正是这些坑的高发区 ⇒ 一律参数化 SQL。
 *
 * <p><b>⚠️ 逻辑删除</b>：原生 SQL 绕过了 Flex 的自动追加，{@code deleted = 0}
 * 必须<b>逐条显式写</b>；漏写就会把已删的行算进来。
 */
@Repository
public class KbMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(KbMemoryRepository.class);

    @Autowired private KbMemorySubjectMapper subjectMapper;
    @Autowired private KbMemoryItemMapper itemMapper;
    @Autowired private KbMemoryTombstoneMapper tombstoneMapper;
    @Autowired private KbMemoryTopicStatMapper topicStatMapper;
    @Autowired private KbMemoryDocAffinityMapper docAffinityMapper;
    @Autowired private javax.sql.DataSource dataSource;

    // ── subject ────────────────────────────────────────────

    /**
     * 取主体；不存在则创建一条。
     *
     * <p>⚠️ 用「先查后建 + 撞唯一索引时重查」而不是纯 insert-or-ignore：
     * 并发首调时两个线程都可能查不到，必须让其中一个撞索引后再读。
     */
    public KbMemorySubjectRecord ensureSubject(KbMemoryScope scope) {
        KbMemorySubjectRecord existing = findSubject(scope);
        if (existing != null) {
            return existing;
        }
        KbMemorySubjectRecord r = new KbMemorySubjectRecord();
        r.setId("mem-subj-" + UUID.randomUUID().toString().substring(0, 8));
        r.setSubjectId(scope.subjectId());
        r.setEnabled(1);
        r.setBlockText("");
        r.setItemCount(0);
        try {
            subjectMapper.insert(r);
            return r;
        } catch (RuntimeException e) {
            // 并发下撞唯一索引 —— 重查
            KbMemorySubjectRecord again = findSubject(scope);
            if (again != null) {
                return again;
            }
            throw e;
        }
    }

    public KbMemorySubjectRecord findSubject(KbMemoryScope scope) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_subject WHERE subject_id = ? AND deleted = 0")) {
            ps.setString(1, scope.subjectId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapSubject(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取记忆主体失败: " + e.getMessage(), e);
        }
    }

    /** 用户自己的开关。 */
    public void updateSubjectEnabled(KbMemoryScope scope, boolean enabled) {
        ensureSubject(scope);
        exec("UPDATE kb_memory_subject SET enabled = ?, update_time = ? WHERE subject_id = ? AND deleted = 0",
            enabled ? 1 : 0, KbTimeFormat.now(), scope.subjectId());
    }

    /** 覆盖常驻块与条目数。 */
    public void updateSubjectBlock(KbMemoryScope scope, String blockText, int itemCount) {
        exec("UPDATE kb_memory_subject SET block_text = ?, block_updated_at = ?, item_count = ?, "
                + "update_time = ? WHERE subject_id = ? AND deleted = 0",
            blockText, KbTimeFormat.now(), itemCount, KbTimeFormat.now(), scope.subjectId());
    }

    /** 推进抽取水位（只在成功抽取后调用）。 */
    public void updateExtractCursor(KbMemoryScope scope, LocalDateTime cursor) {
        exec("UPDATE kb_memory_subject SET extract_cursor = ?, last_extracted_at = ?, "
                + "update_time = ? WHERE subject_id = ? AND deleted = 0",
            KbTimeFormat.format(cursor), KbTimeFormat.now(), KbTimeFormat.now(), scope.subjectId());
    }

    /** 标记抽取任务已排队（去抖）。传 null = 清除标记。 */
    public void markExtractScheduled(KbMemoryScope scope, LocalDateTime at) {
        exec("UPDATE kb_memory_subject SET extract_scheduled_at = ?, update_time = ? "
                + "WHERE subject_id = ? AND deleted = 0",
            at == null ? null : KbTimeFormat.format(at), KbTimeFormat.now(), scope.subjectId());
    }

    /** 记录整理时刻（forced = 「立刻整理」的独立时钟）。 */
    public void markConsolidated(KbMemoryScope scope, boolean forced) {
        String column = forced ? "forced_consolidated_at" : "consolidated_at";
        exec("UPDATE kb_memory_subject SET " + column + " = ?, update_time = ? "
            + "WHERE subject_id = ? AND deleted = 0", KbTimeFormat.now(), KbTimeFormat.now(), scope.subjectId());
    }

    // ── item ───────────────────────────────────────────────

    public List<KbMemoryItemRecord> listItems(KbMemoryScope scope, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM kb_memory_item WHERE subject_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(scope.subjectId());
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        // ⚠️ importance 降序 + 创建时间降序：重要性相同的按新旧排，避免顺序不稳定
        sql.append(" ORDER BY importance DESC, create_time DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<KbMemoryItemRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapItem(rs));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取记忆列表失败: " + e.getMessage(), e);
        }
    }

    public long countItems(KbMemoryScope scope, String status) {
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM kb_memory_item WHERE subject_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(scope.subjectId());
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new IllegalStateException("统计记忆数失败: " + e.getMessage(), e);
        }
    }

    public KbMemoryItemRecord getItem(KbMemoryScope scope, String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_item WHERE id = ? AND subject_id = ? AND deleted = 0")) {
            ps.setString(1, id);
            ps.setString(2, scope.subjectId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapItem(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取记忆条目失败: " + e.getMessage(), e);
        }
    }

    /** 按归一化键找 active 条目（双时态的判定依据）。 */
    public KbMemoryItemRecord findActiveByKey(KbMemoryScope scope, String normalizedKey) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_item WHERE subject_id = ? AND normalized_key = ? "
                     + "AND status = ? AND deleted = 0 ORDER BY create_time DESC LIMIT 1")) {
            ps.setString(1, scope.subjectId());
            ps.setString(2, normalizedKey);
            ps.setString(3, KbMemoryTypes.STATUS_ACTIVE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapItem(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("按键查记忆失败: " + e.getMessage(), e);
        }
    }

    /** 该主体所有 active 的、同 kind 的条目（包含式去重用）。 */
    public List<KbMemoryItemRecord> listActiveByKind(KbMemoryScope scope, String kind) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_item WHERE subject_id = ? AND kind = ? AND status = ? "
                     + "AND deleted = 0")) {
            ps.setString(1, scope.subjectId());
            ps.setString(2, kind);
            ps.setString(3, KbMemoryTypes.STATUS_ACTIVE);
            try (ResultSet rs = ps.executeQuery()) {
                List<KbMemoryItemRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapItem(rs));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取同类记忆失败: " + e.getMessage(), e);
        }
    }

    /** 常驻块的来源条目（profile / preference / interest 的 active）。 */
    public List<KbMemoryItemRecord> listResident(KbMemoryScope scope) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM kb_memory_item WHERE subject_id = ? AND status = ? AND deleted = 0 "
                + "AND kind IN (");
        for (int i = 0; i < KbMemoryTypes.RESIDENT_KINDS.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY importance DESC, create_time DESC");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, scope.subjectId());
            ps.setString(2, KbMemoryTypes.STATUS_ACTIVE);
            int idx = 3;
            for (String kind : KbMemoryTypes.RESIDENT_KINDS) {
                ps.setString(idx++, kind);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<KbMemoryItemRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapItem(rs));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取常驻记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 插入新条目。
     *
     * <p>⚠️ 全列显式赋值（含审计列）：原生 INSERT 不会触发 Flex 的审计监听器，
     * 靠 DB DEFAULT 又会被「显式写 null」破坏。这里一次写全，行为确定。
     */
    public void insertItem(KbMemoryItemRecord r) {
        exec("INSERT INTO kb_memory_item (id, subject_id, kind, content, topic, normalized_key, "
                + "importance, origin, status, source_session_id, source_message_id, valid_from, "
                + "invalid_at, expires_at, superseded_by, last_used_at, use_count, "
                + "creator, create_time, updater, update_time, deleted, tenant_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0)",
            r.getId(), r.getSubjectId(), r.getKind(), r.getContent(), r.getTopic(),
            r.getNormalizedKey(), r.getImportance(), r.getOrigin(), r.getStatus(),
            r.getSourceSessionId(), r.getSourceMessageId(),
            r.getValidFrom() == null ? KbTimeFormat.now() : r.getValidFrom(),
            r.getInvalidAt(), r.getExpiresAt(), r.getSupersededBy(), r.getLastUsedAt(),
            r.getUseCount() == null ? 0 : r.getUseCount(),
            "", KbTimeFormat.now(), "", KbTimeFormat.now());
    }

    /** 更新条目的可编辑字段（内容 / 键 / 重要性）。 */
    public void updateItemContent(KbMemoryScope scope, String id, String content,
                                  String normalizedKey, int importance) {
        exec("UPDATE kb_memory_item SET content = ?, normalized_key = ?, importance = ?, "
                + "origin = ?, update_time = ? WHERE id = ? AND subject_id = ? AND deleted = 0",
            content, normalizedKey, importance, KbMemoryTypes.ORIGIN_MANUAL,
            KbTimeFormat.now(), id, scope.subjectId());
    }

    /**
     * 双时态取代：旧条目标 superseded + invalid_at + superseded_by。
     *
     * <p>⚠️ <b>不删内容</b> —— 这是「明确记录过去以为过什么」的唯一机制。
     */
    public void supersede(KbMemoryScope scope, String oldId, String newId) {
        exec("UPDATE kb_memory_item SET status = ?, invalid_at = ?, superseded_by = ?, update_time = ? "
                + "WHERE id = ? AND subject_id = ? AND deleted = 0",
            KbMemoryTypes.STATUS_SUPERSEDED, KbTimeFormat.now(), newId,
            KbTimeFormat.now(), oldId, scope.subjectId());
    }

    /** 状态迁移（confirm / reject / archive / expire）。 */
    public void updateStatus(KbMemoryScope scope, String id, String status) {
        exec("UPDATE kb_memory_item SET status = ?, update_time = ? "
            + "WHERE id = ? AND subject_id = ? AND deleted = 0", status, KbTimeFormat.now(), id, scope.subjectId());
    }

    /** 物理删除（用户主动忘掉）。 */
    public void deleteItem(KbMemoryScope scope, String id) {
        exec("DELETE FROM kb_memory_item WHERE id = ? AND subject_id = ?", id, scope.subjectId());
    }

    /**
     * 清空：只清条目，<b>不动墓碑</b>。
     *
     * <p>⚠️ 墓碑保留是对的：用户清空后，后台不应该在下一轮把同样的话重新抽出来。
     */
    public long deleteAllItems(KbMemoryScope scope) {
        return exec("DELETE FROM kb_memory_item WHERE subject_id = ?", scope.subjectId());
    }

    /** 语义召回候选：active + 未过期。 */
    public List<KbMemoryItemRecord> listRecallCandidates(KbMemoryScope scope) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_item WHERE subject_id = ? AND status = ? AND deleted = 0 "
                     + "AND (expires_at IS NULL OR expires_at > ?)")) {
            ps.setString(1, scope.subjectId());
            ps.setString(2, KbMemoryTypes.STATUS_ACTIVE);
            ps.setString(3, KbTimeFormat.now());
            try (ResultSet rs = ps.executeQuery()) {
                List<KbMemoryItemRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapItem(rs));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取召回候选失败: " + e.getMessage(), e);
        }
    }

    /** 记一次使用（use_count + last_used_at）。 */
    public void markUsed(KbMemoryScope scope, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            exec("UPDATE kb_memory_item SET use_count = use_count + 1, last_used_at = ?, update_time = ? "
                    + "WHERE id = ? AND subject_id = ? AND deleted = 0",
                KbTimeFormat.now(), KbTimeFormat.now(), id, scope.subjectId());
        }
    }

    // ── tombstone ──────────────────────────────────────────

    public boolean hasTombstone(KbMemoryScope scope, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }
        return countQuery("SELECT COUNT(*) FROM kb_memory_tombstone WHERE subject_id = ? "
            + "AND fingerprint = ? AND deleted = 0", scope.subjectId(), fingerprint) > 0;
    }

    /**
     * 这条源消息此前是否产出过被用户拒掉的记忆。
     *
     * <p>⚠️ 重新抽出来的语句措辞常常不同、指纹对不上 ⇒ 必须再认一次来源消息。
     * <b>只对 {@code extracted} 生效</b>：用户明说「记住这个」必须永远能写进去。
     */
    public boolean hasTombstoneForMessage(KbMemoryScope scope, String sourceMessageId, int windowSeconds) {
        if (sourceMessageId == null || sourceMessageId.isBlank()) {
            return false;
        }
        String since = KbTimeFormat.format(LocalDateTime.now().minusSeconds(windowSeconds));
        return countQuery("SELECT COUNT(*) FROM kb_memory_tombstone WHERE subject_id = ? "
            + "AND source_message_id = ? AND create_time >= ? AND deleted = 0",
            scope.subjectId(), sourceMessageId, since) > 0;
    }

    /** 记墓碑（幂等：同 fingerprint 已存在则忽略）。 */
    public void addTombstone(KbMemoryScope scope, String topic, String fingerprint, String sourceMessageId) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return;
        }
        if (hasTombstone(scope, fingerprint)) {
            return;
        }
        exec("INSERT OR IGNORE INTO kb_memory_tombstone (id, subject_id, topic, fingerprint, "
                + "source_message_id, creator, create_time, updater, update_time, deleted, tenant_id) "
                + "VALUES (?,?,?,?,?,'',?,'',?,0,0)",
            "mem-tomb-" + UUID.randomUUID().toString().substring(0, 8),
            scope.subjectId(), topic, fingerprint, sourceMessageId,
            KbTimeFormat.now(), KbTimeFormat.now());
    }

    // ── topic stat ─────────────────────────────────────────

    public KbMemoryTopicStatRecord findTopic(KbMemoryScope scope, String normalizedKey) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_topic_stat WHERE subject_id = ? AND normalized_key = ? "
                     + "AND deleted = 0")) {
            ps.setString(1, scope.subjectId());
            ps.setString(2, normalizedKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapTopic(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取主题计数失败: " + e.getMessage(), e);
        }
    }

    public List<KbMemoryTopicStatRecord> listUnpromotedTopics(KbMemoryScope scope, int limit, int offset) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_topic_stat WHERE subject_id = ? AND deleted = 0 "
                     + "AND promoted_at IS NULL AND rejected_at IS NULL "
                     + "ORDER BY hits DESC, last_seen_at DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, scope.subjectId());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<KbMemoryTopicStatRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapTopic(rs));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取主题列表失败: " + e.getMessage(), e);
        }
    }

    public long countUnpromotedTopics(KbMemoryScope scope) {
        return countQuery("SELECT COUNT(*) FROM kb_memory_topic_stat WHERE subject_id = ? AND deleted = 0 "
            + "AND promoted_at IS NULL AND rejected_at IS NULL", scope.subjectId());
    }

    public KbMemoryTopicStatRecord getTopicById(KbMemoryScope scope, String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_topic_stat WHERE id = ? AND subject_id = ? AND deleted = 0")) {
            ps.setString(1, id);
            ps.setString(2, scope.subjectId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapTopic(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取主题失败: " + e.getMessage(), e);
        }
    }

    /** 计数 +1（不存在则建）。 */
    public KbMemoryTopicStatRecord bumpTopic(KbMemoryScope scope, String normalizedKey, String topic) {
        KbMemoryTopicStatRecord existing = findTopic(scope, normalizedKey);
        if (existing == null) {
            exec("INSERT OR IGNORE INTO kb_memory_topic_stat (id, subject_id, normalized_key, topic, hits, "
                    + "last_seen_at, promoted_at, rejected_at, aliases, creator, create_time, updater, "
                    + "update_time, deleted, tenant_id) VALUES (?,?,?,?,1,?,NULL,NULL,'[]','',?,'',?,0,0)",
                "mem-topic-" + UUID.randomUUID().toString().substring(0, 8),
                scope.subjectId(), normalizedKey, topic, KbTimeFormat.now(),
                KbTimeFormat.now(), KbTimeFormat.now());
            return findTopic(scope, normalizedKey);
        }
        exec("UPDATE kb_memory_topic_stat SET hits = hits + 1, last_seen_at = ?, update_time = ? "
                + "WHERE id = ? AND subject_id = ? AND deleted = 0",
            KbTimeFormat.now(), KbTimeFormat.now(), existing.getId(), scope.subjectId());
        return getTopicById(scope, existing.getId());
    }

    public void markTopicPromoted(KbMemoryScope scope, String id) {
        exec("UPDATE kb_memory_topic_stat SET promoted_at = ?, update_time = ? "
            + "WHERE id = ? AND subject_id = ? AND deleted = 0", KbTimeFormat.now(), KbTimeFormat.now(), id, scope.subjectId());
    }

    /** 停止跟踪：不删行，打拒绝标记（否则下次又被自动提升）。 */
    public void markTopicRejected(KbMemoryScope scope, String id) {
        exec("UPDATE kb_memory_topic_stat SET rejected_at = ?, update_time = ? "
            + "WHERE id = ? AND subject_id = ? AND deleted = 0", KbTimeFormat.now(), KbTimeFormat.now(), id, scope.subjectId());
    }

    // ── doc affinity ───────────────────────────────────────

    public List<KbMemoryDocAffinityRecord> listDocuments(KbMemoryScope scope, int limit, int offset) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_doc_affinity WHERE subject_id = ? AND deleted = 0 "
                     + "ORDER BY hits DESC, last_used_at DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, scope.subjectId());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<KbMemoryDocAffinityRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapDoc(rs));
                }
                return out;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取文档亲和列表失败: " + e.getMessage(), e);
        }
    }

    public long countDocuments(KbMemoryScope scope) {
        return countQuery("SELECT COUNT(*) FROM kb_memory_doc_affinity WHERE subject_id = ? AND deleted = 0",
            scope.subjectId());
    }

    /**
     * 批量读「这些文档各被用过多少次」（阶段 5c 记忆亲和的读侧，对齐源侧
     * {@code MemoryService.DocumentAffinity}）。
     *
     * <p>⭐ 为什么批量而不是逐个查：重排结果动辄几十上百个文档，
     * 逐个查等于几十次往返 SQLite，问答延迟肉眼可见地变长。
     *
     * @param scope      记忆作用域（决定 subject）
     * @param knowledgeIds 文档 id（null/空 ⇒ 空 map）
     * @return 文档 id → 命中次数；没有记录的文档不出现在 map 里
     */
    public java.util.Map<String, Integer> documentAffinity(
        KbMemoryScope scope, java.util.List<String> knowledgeIds
    ) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.List<String> ids = knowledgeIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT knowledge_id, hits FROM kb_memory_doc_affinity "
            + "WHERE subject_id = ? AND deleted = 0 AND knowledge_id IN (" + placeholders + ")";
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope.subjectId());
            for (int i = 0; i < ids.size(); i++) {
                ps.setString(i + 2, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kid = rs.getString("knowledge_id");
                    int hits = rs.getInt("hits");
                    if (kid != null && !kid.isBlank()) {
                        out.put(kid, hits);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            // ⚠️ 亲和是增强：读失败不该让整轮问答失败，返回空 map 即可
            throw new IllegalStateException("读取文档亲和批量失败: " + e.getMessage(), e);
        }
    }

    public KbMemoryDocAffinityRecord getDocById(KbMemoryScope scope, String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM kb_memory_doc_affinity WHERE id = ? AND subject_id = ? AND deleted = 0")) {
            ps.setString(1, id);
            ps.setString(2, scope.subjectId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapDoc(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取文档亲和失败: " + e.getMessage(), e);
        }
    }

    public void deleteDocument(KbMemoryScope scope, String id) {
        exec("DELETE FROM kb_memory_doc_affinity WHERE id = ? AND subject_id = ?", id, scope.subjectId());
    }

    /** 记录一次引用（不存在则建）。 */
    public void bumpDocAffinity(KbMemoryScope scope, String knowledgeId, String knowledgeBaseId, String title) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return;
        }
        long existing = countQuery("SELECT COUNT(*) FROM kb_memory_doc_affinity WHERE subject_id = ? "
            + "AND knowledge_id = ? AND deleted = 0", scope.subjectId(), knowledgeId);
        if (existing == 0) {
            exec("INSERT INTO kb_memory_doc_affinity (id, subject_id, knowledge_id, knowledge_base_id, title, "
                    + "hits, last_used_at, creator, create_time, updater, update_time, deleted, tenant_id) "
                    + "VALUES (?,?,?,?,?,1,?,'',?,'',?,0,0)",
                "mem-doc-" + UUID.randomUUID().toString().substring(0, 8),
                scope.subjectId(), knowledgeId, knowledgeBaseId == null ? "" : knowledgeBaseId,
                title == null ? "" : title, KbTimeFormat.now(),
                KbTimeFormat.now(), KbTimeFormat.now());
            return;
        }
        exec("UPDATE kb_memory_doc_affinity SET hits = hits + 1, last_used_at = ?, update_time = ? "
                + "WHERE subject_id = ? AND knowledge_id = ? AND deleted = 0",
            KbTimeFormat.now(), KbTimeFormat.now(), scope.subjectId(), knowledgeId);
    }

    // ── 内部 ──────────────────────────────────────────────

    /** 执行写 SQL，返回影响行数。 */
    private long exec(String sql, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, java.util.Arrays.asList(args));
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("记忆写库失败: " + e.getMessage(), e);
        }
    }

    private long countQuery(String sql, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, java.util.Arrays.asList(args));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new IllegalStateException("记忆计数失败: " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws Exception {
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            if (v == null) {
                ps.setObject(i + 1, null);
            } else if (v instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (v instanceof Long n) {
                ps.setLong(i + 1, n);
            } else {
                ps.setString(i + 1, String.valueOf(v));
            }
        }
    }

    private static KbMemorySubjectRecord mapSubject(ResultSet rs) throws Exception {
        KbMemorySubjectRecord r = new KbMemorySubjectRecord();
        r.setId(rs.getString("id"));
        r.setSubjectId(rs.getString("subject_id"));
        r.setEnabled(rs.getInt("enabled"));
        r.setBlockText(rs.getString("block_text"));
        r.setBlockUpdatedAt(rs.getString("block_updated_at"));
        r.setItemCount(rs.getInt("item_count"));
        r.setLastExtractedAt(rs.getString("last_extracted_at"));
        r.setExtractCursor(rs.getString("extract_cursor"));
        r.setPendingSessions(rs.getString("pending_sessions"));
        r.setExtractScheduledAt(rs.getString("extract_scheduled_at"));
        r.setConsolidatedAt(rs.getString("consolidated_at"));
        r.setForcedConsolidatedAt(rs.getString("forced_consolidated_at"));
        return r;
    }

    private static KbMemoryItemRecord mapItem(ResultSet rs) throws Exception {
        KbMemoryItemRecord r = new KbMemoryItemRecord();
        r.setId(rs.getString("id"));
        r.setSubjectId(rs.getString("subject_id"));
        r.setKind(rs.getString("kind"));
        r.setContent(rs.getString("content"));
        r.setTopic(rs.getString("topic"));
        r.setNormalizedKey(rs.getString("normalized_key"));
        r.setImportance(rs.getInt("importance"));
        r.setOrigin(rs.getString("origin"));
        r.setStatus(rs.getString("status"));
        r.setSourceSessionId(rs.getString("source_session_id"));
        r.setSourceMessageId(rs.getString("source_message_id"));
        r.setValidFrom(rs.getString("valid_from"));
        r.setInvalidAt(rs.getString("invalid_at"));
        r.setExpiresAt(rs.getString("expires_at"));
        r.setSupersededBy(rs.getString("superseded_by"));
        r.setLastUsedAt(rs.getString("last_used_at"));
        r.setUseCount(rs.getInt("use_count"));
        r.setCreateTime(KbTimeFormat.parse(rs.getString("create_time")));
        return r;
    }

    private static KbMemoryTopicStatRecord mapTopic(ResultSet rs) throws Exception {
        KbMemoryTopicStatRecord r = new KbMemoryTopicStatRecord();
        r.setId(rs.getString("id"));
        r.setSubjectId(rs.getString("subject_id"));
        r.setNormalizedKey(rs.getString("normalized_key"));
        r.setTopic(rs.getString("topic"));
        r.setHits(rs.getInt("hits"));
        r.setLastSeenAt(rs.getString("last_seen_at"));
        r.setPromotedAt(rs.getString("promoted_at"));
        r.setRejectedAt(rs.getString("rejected_at"));
        r.setAliases(rs.getString("aliases"));
        return r;
    }

    private static KbMemoryDocAffinityRecord mapDoc(ResultSet rs) throws Exception {
        KbMemoryDocAffinityRecord r = new KbMemoryDocAffinityRecord();
        r.setId(rs.getString("id"));
        r.setSubjectId(rs.getString("subject_id"));
        r.setKnowledgeId(rs.getString("knowledge_id"));
        r.setKnowledgeBaseId(rs.getString("knowledge_base_id"));
        r.setTitle(rs.getString("title"));
        r.setHits(rs.getInt("hits"));
        r.setLastUsedAt(rs.getString("last_used_at"));
        return r;
    }
}
