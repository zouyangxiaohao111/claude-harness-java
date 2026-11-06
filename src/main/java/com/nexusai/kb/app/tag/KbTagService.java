package com.nexusai.kb.app.tag;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbKnowledgeTagRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeTagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 知识库标签 · 批次 W4（源侧「标签管理」4 端点）+ W5（批量打标）· V269。
 *
 * <h2>⭐⭐ 标签是「知识库级定义 + 文档级关联」两层</h2>
 * 源侧 000063 明确 DROP 了 {@code knowledges.tag_id} 单值列，换成
 * {@code knowledge_tag_relations} 多对多。本仓照此：一个文档可以打多个标签。
 * ⛔ 别为了省事在 {@code kb_knowledge} 上再加一个 {@code tag_id}。
 *
 * <h2>⚠️ 所有写入走参数化原生 SQL</h2>
 * {@code kb_knowledge_tag_relation} 是<b>复合主键</b>，Flex 的 {@code insertOrUpdate}
 * 无法表达；且关联行的语义是「有/没有」，取消打标 = DELETE（不软删）。
 */
@Service
public class KbTagService {

    private static final Logger log = LoggerFactory.getLogger(KbTagService.class);

    private final KbKnowledgeTagMapper tagMapper;
    private final JdbcTemplate jdbc;

    @Autowired
    public KbTagService(KbKnowledgeTagMapper tagMapper, JdbcTemplate jdbc) {
        this.tagMapper = tagMapper;
        this.jdbc = jdbc;
    }

    /**
     * 标签列表。
     *
     * @param kbId           知识库
     * @param withStats      是否带统计（文档数）。源侧 {@code KnowledgeTagWithStats}
     * @return 标签列表（按 sort_order, seq_id, name 排序）
     */
    public List<TagView> list(String kbId, boolean withStats) {
        requireKbId(kbId);
        List<KbKnowledgeTagRecord> rows = tagMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .orderBy("sort_order", true)
                .orderBy("seq_id", true)
                .orderBy("name", true));
        List<TagView> out = new ArrayList<>(rows.size());
        for (KbKnowledgeTagRecord r : rows) {
            Integer count = null;
            if (withStats) {
                count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kb_knowledge_tag_relation WHERE tag_id = ?",
                    Integer.class, r.getId());
            }
            out.add(toView(r, count));
        }
        return out;
    }

    /**
     * 创建标签。
     *
     * @param kbId  知识库
     * @param name  标签名（同库内唯一，空 ⇒ 400）
     * @param color 颜色（可空）
     * @param sortOrder 排序（可空 ⇒ 0）
     * @return 新建标签
     * @throws ConflictException 同库内重名
     */
    @Transactional
    public TagView create(String kbId, String name, String color, Integer sortOrder) {
        requireKbId(kbId);
        String n = requireName(name);
        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM kb_knowledge_tag WHERE knowledge_base_id = ? AND name = ? AND deleted = 0",
            Integer.class, kbId, n);
        if (exists != null && exists > 0) {
            throw new ConflictException("标签「" + n + "」已存在");
        }
        Integer maxSeq = jdbc.queryForObject(
            "SELECT COALESCE(MAX(seq_id), 0) FROM kb_knowledge_tag WHERE knowledge_base_id = ?",
            Integer.class, kbId);

        KbKnowledgeTagRecord row = new KbKnowledgeTagRecord();
        row.setId(UUID.randomUUID().toString());
        row.setKnowledgeBaseId(kbId);
        row.setName(n);
        row.setColor(color == null ? "" : color);
        row.setSortOrder(sortOrder == null ? 0 : sortOrder);
        row.setSeqId((maxSeq == null ? 0 : maxSeq) + 1);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        // ⚠️ Flex 的 insert 会把 null 也列进 INSERT ⇒ 显式给 NOT NULL 列赋值
        row.setCreator("");
        row.setUpdater("");
        row.setDeleted(0);
        row.setTenantId(0L);
        tagMapper.insert(row);
        log.info("[标签] 已创建 kb={} id={} name={}", kbId, row.getId(), n);
        return toView(tagMapper.selectOneById(row.getId()), 0);
    }

    /**
     * 更新标签（局部语义：null = 不改）。
     *
     * @throws ConflictException 改名后与同库其它标签重名
     */
    @Transactional
    public TagView update(String kbId, String tagId, String name, String color, Integer sortOrder) {
        requireKbId(kbId);
        KbKnowledgeTagRecord row = requireTag(kbId, tagId);
        if (name != null) {
            String n = requireName(name);
            if (!n.equals(row.getName())) {
                Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kb_knowledge_tag WHERE knowledge_base_id = ? AND name = ? "
                        + "AND id <> ? AND deleted = 0",
                    Integer.class, kbId, n, tagId);
                if (dup != null && dup > 0) {
                    throw new ConflictException("标签「" + n + "」已存在");
                }
            }
            row.setName(n);
        }
        if (color != null) {
            row.setColor(color);
        }
        if (sortOrder != null) {
            row.setSortOrder(sortOrder);
        }
        row.setUpdateTime(LocalDateTime.now());
        // ⚠️ `update(row, false)`：显式写 NULL。这里 color 传 "" 表示清空，
        //    用 update(row) 会把 "" 当作「无变化」（Flex 忽略 null 但也忽略等价判断差异）。
        tagMapper.update(row, false);
        log.info("[标签] 已更新 kb={} id={} name={}", kbId, tagId, row.getName());
        return toView(requireTag(kbId, tagId), null);
    }

    /**
     * 删除标签（逻辑删）+ 清掉所有关联行。
     *
     * <p>⚠️ 关联行是<b>物理删</b>：保留已删标签的关联行没有意义，
     * 而且会让「按 tag 统计文档数」把已删标签算进去。
     */
    @Transactional
    public void delete(String kbId, String tagId) {
        requireKbId(kbId);
        requireTag(kbId, tagId);
        // ⚠️ 两处都用 jdbc：同一事务混用 Flex mapper 与 JdbcTemplate 写库 ⇒ SQLITE_BUSY（实测）
        int unlinked = jdbc.update("DELETE FROM kb_knowledge_tag_relation WHERE tag_id = ?", tagId);
        jdbc.update("UPDATE kb_knowledge_tag SET deleted = 1, update_time = ? WHERE id = ?",
            KbTimeFormat.format(LocalDateTime.now()), tagId);
        log.info("[标签] 已删除 kb={} id={} 同时解除 {} 个文档关联", kbId, tagId, unlinked);
    }

    /**
     * 批量打标（W5）：给若干文档设置标签。
     *
     * <p>源侧 {@code PUT /knowledge/tags} 的语义是 <b>替换</b>：
     * 传进来的 {@code tag_ids} 就是最终集合，未列出的一律解除。
     * ⚠️ 这与「追加」不同 —— 追加语义会让前端无法取消某个标签。
     *
     * @param kbId         知识库
     * @param knowledgeIds 目标文档
     * @param tagIds       目标标签（空数组 = 全部清除）
     * @return 实际写入的关联行数
     */
    @Transactional
    public int setTags(String kbId, List<String> knowledgeIds, List<String> tagIds) {
        requireKbId(kbId);
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            throw new ValidationException("knowledge_ids 不能为空");
        }
        // 先校验标签都属于该库，避免把 A 库标签打到 B 库文档上（跨库脏数据）
        List<String> validTags = new ArrayList<>();
        if (tagIds != null) {
            for (String tid : tagIds) {
                KbKnowledgeTagRecord t = tagMapper.selectOneById(tid);
                if (t == null) {
                    throw new NotFoundException("标签 " + tid + " 不存在");
                }
                if (!kbId.equals(t.getKnowledgeBaseId())) {
                    throw new ValidationException("标签 " + tid + " 不属于知识库 " + kbId);
                }
                validTags.add(tid);
            }
        }

        int written = 0;
        String now = KbTimeFormat.format(LocalDateTime.now());
        for (String kid : knowledgeIds) {
            Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_knowledge WHERE id = ? AND knowledge_base_id = ? AND deleted = 0",
                Integer.class, kid, kbId);
            if (exists == null || exists == 0) {
                throw new NotFoundException("文档 " + kid + " 不存在于知识库 " + kbId);
            }
            // 替换语义：先清后插
            jdbc.update("DELETE FROM kb_knowledge_tag_relation WHERE knowledge_id = ?", kid);
            for (String tid : validTags) {
                written += jdbc.update(
                    "INSERT INTO kb_knowledge_tag_relation "
                        + "(knowledge_id, tag_id, knowledge_base_id, created_at, tenant_id) "
                        + "VALUES (?,?,?,?,0)",
                    kid, tid, kbId, now);
            }
        }
        log.info("[标签] 批量打标 kb={} 文档数={} 标签数={} 写入关联={}",
            kbId, knowledgeIds.size(), validTags.size(), written);
        return written;
    }

    /** 取某个文档的标签列表。 */
    public List<TagView> listForKnowledge(String knowledgeId) {
        List<String> ids = jdbc.queryForList(
            "SELECT tag_id FROM kb_knowledge_tag_relation WHERE knowledge_id = ?",
            String.class, knowledgeId);
        List<TagView> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            KbKnowledgeTagRecord t = tagMapper.selectOneById(id);
            if (t != null) {
                out.add(toView(t, null));
            }
        }
        return out;
    }

    /**
     * 按名称查找标签，不存在则创建（源侧 {@code FindOrCreateTagByName}）。
     *
     * <p>FAQ 打标依赖它：源侧「未传 tag」时自动落到「未分类」标签，
     * 所以每个 FAQ 库至少会有一个「未分类」。
     *
     * @return 标签（含内部分 UUID）
     */
    public TagView findOrCreateByName(String kbId, String name) {
        requireKbId(kbId);
        String n = requireName(name);
        List<KbKnowledgeTagRecord> existing = tagMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and("name = ?", n));
        if (!existing.isEmpty()) {
            return toView(existing.get(0), null);
        }
        try {
            return create(kbId, n, "", null);
        } catch (ConflictException e) {
            // 并发下另一个线程刚建好 —— 重查一次即可
            List<KbKnowledgeTagRecord> again = tagMapper.selectListByQuery(
                QueryWrapper.create()
                    .where("knowledge_base_id = ?", kbId)
                    .and("name = ?", n));
            if (!again.isEmpty()) {
                return toView(again.get(0), null);
            }
            throw e;
        }
    }

    /** 按内部分 UUID 取标签（可空）。 */
    public KbKnowledgeTagRecord findById(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return null;
        }
        return tagMapper.selectOneById(tagId);
    }

    /** 按库内 seq_id 取标签（可空）。FAQ 条目的 tag_id 对外暴露的就是它。 */
    public KbKnowledgeTagRecord findBySeqId(String kbId, long seqId) {
        if (seqId <= 0) {
            return null;
        }
        List<KbKnowledgeTagRecord> rows = tagMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and("seq_id = ?", seqId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private KbKnowledgeTagRecord requireTag(String kbId, String tagId) {
        if (tagId == null || tagId.isBlank()) {
            throw new ValidationException("标签 ID 不能为空");
        }
        KbKnowledgeTagRecord row = tagMapper.selectOneById(tagId);
        if (row == null) {
            throw new NotFoundException("标签 " + tagId + " 不存在");
        }
        if (!kbId.equals(row.getKnowledgeBaseId())) {
            // ⚠️ 不返回「不属于该库」的细节给外部？—— 这里返回 404 更合适，
            //    避免暴露「这个 ID 存在于别的库」。但内部调用需要区分，故用 404 文案。
            throw new NotFoundException("标签 " + tagId + " 不存在于知识库 " + kbId);
        }
        return row;
    }

    private static void requireKbId(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("标签名不能为空");
        }
        String n = name.trim();
        if (n.length() > 64) {
            throw new ValidationException("标签名不能超过 64 字符");
        }
        return n;
    }

    private static TagView toView(KbKnowledgeTagRecord r, Integer knowledgeCount) {
        if (r == null) {
            return null;
        }
        return new TagView(
            r.getId(), r.getKnowledgeBaseId(), r.getName(), r.getColor(),
            r.getSortOrder(), r.getSeqId(), knowledgeCount,
            KbTimeFormat.format(r.getCreateTime()), KbTimeFormat.format(r.getUpdateTime()));
    }

    /** 标签出站视图（对齐源侧 {@code KnowledgeTagWithStats}，全 snake_case）。 */
    public record TagView(
        @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
        @com.fasterxml.jackson.annotation.JsonProperty("color") String color,
        @com.fasterxml.jackson.annotation.JsonProperty("sort_order") Integer sortOrder,
        @com.fasterxml.jackson.annotation.JsonProperty("seq_id") Integer seqId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_count") Integer knowledgeCount,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") String createdAt,
        @com.fasterxml.jackson.annotation.JsonProperty("updated_at") String updatedAt
    ) {
    }
}
