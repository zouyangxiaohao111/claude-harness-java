package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;
import com.nexusai.kb.repo.KbSqliteLocalDateTimeTypeHandler;

import java.time.LocalDateTime;

/**
 * 分块编辑历史（{@code kb_chunk_revision}）· V269 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code chunk_revisions}（000078_chunk_editing_and_custom_metadata）。
 *
 * <p><b>⚠️ append-only</b>：编辑 / 回滚都<b>只插入</b>，⛔ 从不 UPDATE 或删除历史行。
 * 「回滚到 v2」= 先把当前内容写成一条新 revision，再把 v2 内容写回 {@code kb_chunk}，
 * 于是版本号继续递增（v4、v5…）。这样任何时刻都能回到任意历史点，
 * 而不会出现「回滚把历史覆盖掉、再也回不去」。
 *
 * <p><b>为什么不复用 {@code kb_wiki_page_revision}</b>：两者的对象与生命周期不同
 * （分块会随重解析整批替换；Wiki 页只在 Finalize 时更新），混表会让删除语义纠缠。
 */
@Table(
    value = "kb_chunk_revision",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbChunkRevisionRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String knowledgeBaseId;
    private String knowledgeId;
    private String chunkId;

    /** 版本号（从 1 起，同一 chunk 内唯一）。 */
    private Integer revision;

    /** 该版本的正文快照。 */
    private String content;

    /** 该版本是否启用。 */
    private Integer isEnabled;

    private String editorId;

    /** {@code user} / {@code rollback} / {@code reparse}。 */
    private String editSource;

    @Column(typeHandler = KbSqliteLocalDateTimeTypeHandler.class)
    private LocalDateTime editedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String v) { this.chunkId = v; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer v) { this.revision = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public Integer getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Integer v) { this.isEnabled = v; }
    public String getEditorId() { return editorId; }
    public void setEditorId(String v) { this.editorId = v; }
    public String getEditSource() { return editSource; }
    public void setEditSource(String v) { this.editSource = v; }
    public LocalDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(LocalDateTime v) { this.editedAt = v; }
}
