package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbSqliteLocalDateTimeTypeHandler;

import java.time.LocalDateTime;

/**
 * 活动日志（{@code kb_audit_log}）· V269 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code audit_logs}（000044）。本仓只服务知识库活动流，
 * 列名与源侧保持一致（前端契约 {@code action / outcome / actor_user_id / created_at ...}）。
 *
 * <p><b>⭐ 为什么主键是自增整数而不是 UUID</b>：源侧活动流用 {@code after_id} 做游标翻页，
 * 参数类型是整数。用 UUID 就没有「比这条更新」的稳定比较，翻页会漏。
 *
 * <p><b>⚠️ append-only</b>：只插入，从不更新 / 删除。
 */
@Table("kb_audit_log")
public class KbAuditLogRecord {

    /** 自增主键（游标翻页用）。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /** 作用域类型（本域恒 {@code knowledge_base}）。 */
    private String scopeType;

    /** 作用域 ID = 知识库 ID。 */
    private String scopeId;

    private String targetType;
    private String targetId;
    private String targetUserId;

    private String actorUserId;
    private String actorRole;

    /** 动作：create / update / delete / reparse / upload / copy / pin / move / tag ... */
    private String action;

    /** success / failure / denied。 */
    private String outcome;

    private String requestMethod;
    private String requestPath;

    /** 结构化明细（JSON 文本）。 */
    private String details;

    @Column(typeHandler = KbSqliteLocalDateTimeTypeHandler.class)
    private LocalDateTime createdAt;

    @Column(tenantId = true)
    private Long tenantId;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String v) { this.scopeType = v; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String v) { this.scopeId = v; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String v) { this.targetType = v; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String v) { this.targetId = v; }
    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String v) { this.targetUserId = v; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String v) { this.actorUserId = v; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String v) { this.actorRole = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String v) { this.requestMethod = v; }
    public String getRequestPath() { return requestPath; }
    public void setRequestPath(String v) { this.requestPath = v; }
    public String getDetails() { return details; }
    public void setDetails(String v) { this.details = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }
}
