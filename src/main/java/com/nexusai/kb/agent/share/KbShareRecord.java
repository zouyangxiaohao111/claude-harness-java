package com.nexusai.kb.agent.share;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;
import com.nexusai.kb.repo.entity.KbBaseRecord;

/**
 * 资源分享记录（{@code kb_share}）· V275 建表 · 批次 W11。
 *
 * <p>源侧有 {@code kb_shares} 与 {@code agent_shares} 两张同构表，
 * 本仓合并为一张，用 {@code resourceType} 区分（{@code knowledge_base} | {@code agent}）。
 *
 * <p><b>⚠️ 本仓没有接收方</b>（用户 2026-11-05 裁定「C 建表但无接收方」）：
 * 本仓是单机单用户（W9 已裁「单机单主体」），没有第二个组织/用户。
 * 表与端点照做，但**共享记录不会产生任何跨用户效果** —— 这一点在
 * {@code OPEN_ISSUES.md} 如实登记，⛔ 不假装能跨用户共享。
 *
 * <p>保留本表的价值：将来接入多用户/多租户时不用改表结构。
 *
 * <p>源侧对照：{@code kb_shares}（{@code 000012_organizations.up.sql:57}），
 * 字段 {@code knowledge_base_id / organization_id / shared_by_user_id /
 * source_tenant_id / permission(admin|editor|viewer)}。
 */
@Table(
    value = "kb_share",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbShareRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    /** {@code knowledge_base} | {@code agent}（源侧对应 kb_shares / agent_shares）。 */
    private String resourceType;

    private String resourceId;

    /** 本仓恒为 {@value KbShareService#LOCAL_ORG}；接入多用户后才有真实值。 */
    private String organizationId;

    private String sharedByUserId;

    private Integer sourceTenantId;

    /** admin | editor | viewer。 */
    private String permission;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String v) { this.resourceType = v; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String v) { this.resourceId = v; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String v) { this.organizationId = v; }
    public String getSharedByUserId() { return sharedByUserId; }
    public void setSharedByUserId(String v) { this.sharedByUserId = v; }
    public Integer getSourceTenantId() { return sourceTenantId; }
    public void setSourceTenantId(Integer v) { this.sourceTenantId = v; }
    public String getPermission() { return permission; }
    public void setPermission(String v) { this.permission = v; }
}
