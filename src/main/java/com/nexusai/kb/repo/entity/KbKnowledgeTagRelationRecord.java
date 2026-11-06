package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbSqliteLocalDateTimeTypeHandler;

import java.time.LocalDateTime;

/**
 * 文档 ↔ 标签关联（{@code kb_knowledge_tag_relation}）· V269 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code knowledge_tag_relations}（000063）。
 *
 * <p><b>⚠️ 复合主键</b>：Flex 不支持在实体上声明复合主键，本表用 {@code (knowledge_id, tag_id)}
 * 唯一索引兜底，实体侧只标 {@code knowledgeId} 为 {@code @Id}（{@code KeyType.None}，
 * 插入时不生成）—— 实际写库一律走参数化原生 SQL（见 {@code KbTagService}），
 * 因此 Flex 的主键推断不会参与。⛔ 不要用 {@code insertOrUpdate} 写这张表。
 *
 * <p><b>为什么不带 deleted</b>：关联行语义上是「有 / 没有」，取消打标直接 DELETE。
 * 保留 {@code created_at} 便于按打标时间排序。
 */
@Table("kb_knowledge_tag_relation")
public class KbKnowledgeTagRelationRecord {

    /** 文档 ID（逻辑主键之一）。 */
    @Id(keyType = com.mybatisflex.annotation.KeyType.None)
    private String knowledgeId;

    /** 标签 ID（逻辑主键之一）。 */
    private String tagId;

    /** 冗余的知识库 ID：按库统计打标情况时免 join。 */
    private String knowledgeBaseId;

    @Column(typeHandler = KbSqliteLocalDateTimeTypeHandler.class)
    private LocalDateTime createdAt;

    @Column(tenantId = true)
    private Long tenantId;

    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getTagId() { return tagId; }
    public void setTagId(String v) { this.tagId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }
}
