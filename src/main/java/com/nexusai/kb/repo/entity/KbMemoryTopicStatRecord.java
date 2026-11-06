package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 主题计数（{@code kb_memory_topic_stat}）· V272 建表 · 批次 W9。
 *
 * <p><b>源侧对照</b>：WeKnora {@code memory_topic_stats}（{@code 000084_memory.up.sql}）。
 *
 * <p><b>⭐ 为什么先计数再提升</b>：单次提问是噪音，同一主题跨几次会话才是信号。
 * 达阈值才提升为 {@code interest} 条目。
 *
 * <p><b>⚠️ {@code rejectedAt} 非空 = 用户停止跟踪了这个主题</b> ——
 * 不是删行，是标记。删了行下次又会被自动提升，用户会觉得「说了别记还记」。
 */
@Table(
    value = "kb_memory_topic_stat",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbMemoryTopicStatRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String subjectId;
    private String normalizedKey;
    private String topic;

    private Integer hits;
    private String lastSeenAt;
    private String promotedAt;

    /** 非空 = 用户拒绝了该主题，不再自动提升。 */
    private String rejectedAt;

    /** 同一主题的不同说法（JSON 数组文本）。 */
    private String aliases;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { this.subjectId = v; }
    public String getNormalizedKey() { return normalizedKey; }
    public void setNormalizedKey(String v) { this.normalizedKey = v; }
    public String getTopic() { return topic; }
    public void setTopic(String v) { this.topic = v; }
    public Integer getHits() { return hits; }
    public void setHits(Integer v) { this.hits = v; }
    public String getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(String v) { this.lastSeenAt = v; }
    public String getPromotedAt() { return promotedAt; }
    public void setPromotedAt(String v) { this.promotedAt = v; }
    public String getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(String v) { this.rejectedAt = v; }
    public String getAliases() { return aliases; }
    public void setAliases(String v) { this.aliases = v; }
}
