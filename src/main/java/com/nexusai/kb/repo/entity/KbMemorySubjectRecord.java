package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 记忆主体（{@code kb_memory_subject}）· V272 建表 · 批次 W9。
 *
 * <p><b>源侧对照</b>：WeKnora {@code memory_subjects}（{@code 000084_memory.up.sql}）。
 *
 * <p><b>⭐ 为什么需要这一行而不只是查 items</b>：它缓存了「常驻块」的渲染结果。
 * 常驻块每轮都要注入系统提示词，读路径必须是一次主键查询 ——
 * 不能每次把几十条 profile/preference 拉出来现拼。
 *
 * <p><b>⚠️ 单机单主体</b>（用户 2026-11-05 裁定）：{@code subject_id} 恒为
 * {@code local_user:default}。列保留是为了将来接多用户时只改解析、不改表。
 *
 * <p>⚠️ 可空时间字段用 {@code String} 而不是 {@link java.time.LocalDateTime}：
 * 本仓的 {@code KbSqliteLocalDateTimeTypeHandler} 是非空语义的，
 * 刻意不为几个可空列引入第二套 handler（与 {@code messages} 表的做法一致）。
 * 格式统一为 {@code yyyy-MM-dd HH:mm:ss}（{@code KbTimeFormat}）。
 */
@Table(
    value = "kb_memory_subject",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbMemorySubjectRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String subjectId;

    /** 用户自己的开关（工作区级开关在 settings）。 */
    private Integer enabled;

    /** 渲染好的常驻块（profile / preference / interest 三类）。 */
    private String blockText;

    private String blockUpdatedAt;

    private Integer itemCount;
    private String lastExtractedAt;

    /**
     * 抽取水位线：这个主体说到这一刻为止的内容都已被「考虑过」蒸馏。
     *
     * <p>⚠️ 抽取任务从水位<b>往后</b>扫 ⇒ 一次定时器错过或一轮消息爆发都不会漏。
     */
    private String extractCursor;

    /** 水位之后还有新消息的会话 ID（JSON 数组文本）。 */
    private String pendingSessions;

    /** 抽取任务已排队的时刻 —— 去抖：并发轮次只排一个任务。 */
    private String extractScheduledAt;

    private String consolidatedAt;

    /** 「立刻整理」的独立限流时钟（与每日整理分开，否则每日整理会拒绝按钮）。 */
    private String forcedConsolidatedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { this.subjectId = v; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer v) { this.enabled = v; }
    public String getBlockText() { return blockText; }
    public void setBlockText(String v) { this.blockText = v; }
    public String getBlockUpdatedAt() { return blockUpdatedAt; }
    public void setBlockUpdatedAt(String v) { this.blockUpdatedAt = v; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer v) { this.itemCount = v; }
    public String getLastExtractedAt() { return lastExtractedAt; }
    public void setLastExtractedAt(String v) { this.lastExtractedAt = v; }
    public String getExtractCursor() { return extractCursor; }
    public void setExtractCursor(String v) { this.extractCursor = v; }
    public String getPendingSessions() { return pendingSessions; }
    public void setPendingSessions(String v) { this.pendingSessions = v; }
    public String getExtractScheduledAt() { return extractScheduledAt; }
    public void setExtractScheduledAt(String v) { this.extractScheduledAt = v; }
    public String getConsolidatedAt() { return consolidatedAt; }
    public void setConsolidatedAt(String v) { this.consolidatedAt = v; }
    public String getForcedConsolidatedAt() { return forcedConsolidatedAt; }
    public void setForcedConsolidatedAt(String v) { this.forcedConsolidatedAt = v; }
}
