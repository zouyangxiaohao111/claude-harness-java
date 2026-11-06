package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 已忘掉的语句（{@code kb_memory_tombstone}）· V272 建表 · 批次 W9。
 *
 * <p><b>源侧对照</b>：WeKnora {@code memory_tombstones}（{@code 000084_memory.up.sql}）。
 *
 * <p><b>⭐⭐ 没有 content 列，这是刻意的</b>：
 * 源侧注释原文「this table exists to stop distillation from re-adding what was dropped,
 * not to retain it」。要求「忘掉」的东西不该在另一张表里以另一个名字留着。
 * <p>⇒ 只存 {@code topic} + {@code fingerprint}（正文的 sha256）。
 *
 * <p><b>为什么需要两种匹配</b>：重新抽出来的语句措辞通常略有不同，
 * 指纹对不上 ⇒ 还要认「它来自的那条源消息此前产出过被拒的记忆」。
 */
@Table(
    value = "kb_memory_tombstone",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbMemoryTombstoneRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String subjectId;
    private String topic;
    private String fingerprint;
    private String sourceMessageId;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { this.subjectId = v; }
    public String getTopic() { return topic; }
    public void setTopic(String v) { this.topic = v; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String v) { this.fingerprint = v; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String v) { this.sourceMessageId = v; }
}
