package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 记忆条目（{@code kb_memory_item}）· V272 建表 · 批次 W9。
 *
 * <p><b>源侧对照</b>：WeKnora {@code types.MemoryItem}（{@code internal/types/memory.go:288}）。
 *
 * <p><b>⭐ 矛盾不删除，用双时态 supersede</b>：
 * 新条目与某条 {@code active} 条目 {@code normalizedKey} 相同 ⇒ 旧条目标
 * {@code superseded} + {@code invalidAt} + {@code supersededBy}，<b>内容保留</b>。
 * ⇒「上个月它以为是什么」仍然可以回答，用户能看到为什么变了。
 * 只有用户主动删除才物理删除（并留墓碑）。
 */
@Table(
    value = "kb_memory_item",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbMemoryItemRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String subjectId;

    /** profile | preference | fact | task | interest */
    private String kind;

    private String content;

    /**
     * 这句话说的是「关于什么」（可读主题）。
     *
     * <p>⚠️ 与 {@code normalizedKey} 一起存：问题常常只提主题，而语句本身只带取值
     * （「已经迁到 PostgreSQL」）⇒ 主题是唯一的检索抓手。
     */
    private String topic;

    /** 归一化主题键：同键的 active 条目 = 同一事实的不同时期。 */
    private String normalizedKey;

    private Integer importance;

    /** explicit | extracted | manual —— ⚠️ 只有 extracted 走墓碑拦截。 */
    private String origin;

    /** active | superseded | archived | pending —— ⚠️ pending 绝不注入提示词。 */
    private String status;

    private String sourceSessionId;
    private String sourceMessageId;

    /** 这条事实从何时开始成立。 */
    private String validFrom;

    /** 何时被取代（supersede 时写）。 */
    private String invalidAt;

    /**
     * 这条事实什么时候不再值得回忆（「本周把迁移做完」这类）。
     *
     * <p>⚠️ 没有它，在办事项会永远留在上下文里，慢慢变成一张「早就做完的事」清单。
     */
    private String expiresAt;

    /** 取代它的那条条目 ID。 */
    private String supersededBy;

    private String lastUsedAt;
    private Integer useCount;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { this.subjectId = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public String getTopic() { return topic; }
    public void setTopic(String v) { this.topic = v; }
    public String getNormalizedKey() { return normalizedKey; }
    public void setNormalizedKey(String v) { this.normalizedKey = v; }
    public Integer getImportance() { return importance; }
    public void setImportance(Integer v) { this.importance = v; }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { this.origin = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(String v) { this.sourceSessionId = v; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String v) { this.sourceMessageId = v; }
    public String getValidFrom() { return validFrom; }
    public void setValidFrom(String v) { this.validFrom = v; }
    public String getInvalidAt() { return invalidAt; }
    public void setInvalidAt(String v) { this.invalidAt = v; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String v) { this.expiresAt = v; }
    public String getSupersededBy() { return supersededBy; }
    public void setSupersededBy(String v) { this.supersededBy = v; }
    public String getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(String v) { this.lastUsedAt = v; }
    public Integer getUseCount() { return useCount; }
    public void setUseCount(Integer v) { this.useCount = v; }
}
