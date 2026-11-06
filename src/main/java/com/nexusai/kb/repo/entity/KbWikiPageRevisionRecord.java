package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * Wiki 页面版本快照（{@code kb_wiki_page_revision}）· V267 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_page_revisions}（000075 迁移）。
 *
 * <h2>⭐ append-only</h2>
 * 只在每次页面合并时插入一条，⛔ 不更新、不删除。
 * 回滚 = <b>把旧版本内容复制回主表，并再插一条新版本</b>（而不是删旧版本）——
 * 历史必须可追溯，否则「回滚」会变成「抹掉证据」。
 *
 * <h2>⚠️ {@code editSource} 三种来源要分清</h2>
 * {@code auto}（AI 自动合并）/ {@code manual}（用户手工编辑）/ {@code rollback}（回滚产生）。
 * 混在一起会让「这条内容是谁写的」无法回答 —— 而这是审计的基本问题。
 */
@Table(
    value = "kb_wiki_page_revision",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbWikiPageRevisionRecord extends KbBaseRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 所属知识库 ID。 */
    private String knowledgeBaseId;

    /** 所属页面 ID。 */
    private String pageId;

    /** 页面 slug（源侧端点按 slug 查版本）。 */
    private String slug;

    /** 对应主表的 {@code version}。同一页面同一版本只应有一条。 */
    private Integer version;

    /** 该版本的标题。 */
    private String title;

    /** 该版本的页面类型。 */
    private String pageType;

    /** 该版本的状态。 */
    private String status;

    /** 该版本的正文快照。 */
    private String content;

    /** 该版本的摘要快照。 */
    private String summary;

    /** 该版本的别名（JSON 数组字符串）。 */
    private String aliases;

    /** 本版本来源：auto / manual / rollback。 */
    private String editSource;

    /** 编辑者标识（自动生成时为空）。 */
    private String editorId;

    /** 本版本产生时刻（文本，格式与其它时间列一致）。 */
    private String editedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getPageId() { return pageId; }
    public void setPageId(String v) { this.pageId = v; }
    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer v) { this.version = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getPageType() { return pageType; }
    public void setPageType(String v) { this.pageType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public String getAliases() { return aliases; }
    public void setAliases(String v) { this.aliases = v; }
    public String getEditSource() { return editSource; }
    public void setEditSource(String v) { this.editSource = v; }
    public String getEditorId() { return editorId; }
    public void setEditorId(String v) { this.editorId = v; }
    public String getEditedAt() { return editedAt; }
    public void setEditedAt(String v) { this.editedAt = v; }
}
