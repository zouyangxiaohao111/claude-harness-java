package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * Wiki 目录（{@code kb_wiki_folder}）· V266 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_folders}（000061_wiki_page_hierarchy）。
 *
 * <h2>⭐ 页面 ↔ 目录的关系方向</h2>
 * 页面通过 {@code kb_wiki_page.folder_id} 指向本表 {@code id}；
 * 本表<b>不存</b>页面列表。原因是「一个页面只在一个目录」，
 * 反向列表在查询时拼更省事（也避免两处不同步）。
 *
 * <h2>⭐⭐ 目录名必须复用，不能造同义词</h2>
 * 源侧 {@code WikiTaxonomyPlanPrompt} 的第一条规则就是「已有目录能装下就逐字复用」。
 * 否则一批文档会造出「春节习俗」和「春节 / 传统习俗」两个目录，树就散了。
 * 本表的 {@code (knowledge_base_id, parent_id, name)} 唯一索引就是这条的兜底。
 */
@Table(
    value = "kb_wiki_folder",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbWikiFolderRecord extends KbBaseRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 所属知识库 ID。 */
    private String knowledgeBaseId;

    /** 父目录 ID（空串 = 根）。 */
    private String parentId;

    /** 目录名（同级唯一）。 */
    private String name;

    /** 物化路径（如 {@code 地点/地址}）。 */
    private String path;

    /** 层级深度（0 = 顶级）。源侧限制 ≤2 级，限制在服务层。 */
    private Integer depth;

    /** 同级排序。 */
    private Integer sortOrder;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getParentId() { return parentId; }
    public void setParentId(String v) { this.parentId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getPath() { return path; }
    public void setPath(String v) { this.path = v; }
    public Integer getDepth() { return depth; }
    public void setDepth(Integer v) { this.depth = v; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer v) { this.sortOrder = v; }
}
