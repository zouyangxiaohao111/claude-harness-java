package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Column;
import com.nexusai.kb.repo.KbSqliteLocalDateTimeTypeHandler;

import java.time.LocalDateTime;

/**
 * 知识库域实体基类 · 审计字段对齐 zjky 框架 {@code BaseDO} / {@code TenantBaseDO}。
 *
 * <p><b>为什么需要这个基类（而不是照抄 NexusAI 现有实体的写法）</b>：
 * NexusAI 现有 19 张表用 {@code created_at TEXT}，且**没有** {@code creator} / {@code updater} /
 * {@code deleted} / {@code tenant_id}。用户 2026-11-03 裁定「新建的知识库表统一采用 zjky 框架的形式」
 * ⇒ 知识库域的表全部走本基类，与现有表**并存**（不互相干扰）。
 *
 * <p><b>源侧真源</b>（反编译 {@code zjky-spring-boot-starter-mybatis-2026.2.10.jar}，
 * {@code com.zjky.pro.framework.mybatis.core.dataobject.BaseDO}）：
 * <table>
 *   <tr><th>Java 字段</th><th>DB 列</th><th>类型</th><th>MyBatis-Plus 注解</th></tr>
 *   <tr><td>createTime</td><td>create_time</td><td>LocalDateTime</td><td>@TableField(fill=INSERT)</td></tr>
 *   <tr><td>updateTime</td><td>update_time</td><td>LocalDateTime</td><td>@TableField(fill=INSERT_UPDATE)</td></tr>
 *   <tr><td>creator</td><td>creator</td><td>String</td><td>@TableField(fill=INSERT, jdbcType=VARCHAR)</td></tr>
 *   <tr><td>updater</td><td>updater</td><td>String</td><td>@TableField(fill=INSERT_UPDATE, jdbcType=VARCHAR)</td></tr>
 *   <tr><td>deleted</td><td>deleted</td><td>Boolean</td><td>@TableLogic</td></tr>
 *   <tr><td>tenantId</td><td>tenant_id</td><td>Long</td><td>（TenantBaseDO）</td></tr>
 * </table>
 *
 * <p><b>MyBatis-Flex 等价物</b>（NexusAI 用 Flex 1.10.0，pom 里没有 MyBatis-Plus）：
 * <ul>
 *   <li>{@code @TableLogic} → {@link Column#isLogicDelete()}（Flex 原生支持）</li>
 *   <li>{@code tenantId} → {@link Column#tenantId()}</li>
 *   <li>{@code @TableField(fill=...)} 的自动填充 → 由 {@link com.nexusai.kb.repo.KbAuditInsertListener}
 *       + {@link com.nexusai.kb.repo.KbAuditUpdateListener} 承接
 *       （在 {@code @Table(onInsert=..., onUpdate=...)} 上挂）</li>
 * </ul>
 *
 * <p><b>⚠️ 时间格式铁律</b>：{@link LocalDateTime} 在 sqlite-jdbc 下直接写库会产出
 * {@code "2026-11-03T09:05"}（T 分隔、秒可省略），而 SQLite {@code datetime('now')} 产出
 * {@code "2026-11-03 12:26:09"}（空格分隔）。空格 {@code 0x20} &lt; {@code T} {@code 0x54}
 * ⇒ 混存时 {@code ORDER BY create_time} 结果错误（实测 09:05 排在 12:26:09 之后）。
 * ⇒ 所有写入路径**必须**经 {@link com.nexusai.kb.repo.KbTimeFormat#format(LocalDateTime)}
 * 统一格式化。证据见 {@code docs/zjkycode/specs/weknora-replica/OPEN_ISSUES.md} O-7。
 */
public abstract class KbBaseRecord {

    /** 创建者（对齐 zjky BaseDO.creator；单机场景可为空串）。 */
    private String creator;

    /**
     * 创建时间（对齐 zjky BaseDO.createTime；DB 列 create_time TEXT）。
     *
     * <p>{@code typeHandler} 把 {@link LocalDateTime} 统一格式化为
     * {@code yyyy-MM-dd HH:mm:ss}（与 SQLite {@code datetime('now')} 同款），
     * 避免 {@code LocalDateTime.toString()} 的 {@code T} 分隔符打乱字典序。
     */
    @Column(typeHandler = KbSqliteLocalDateTimeTypeHandler.class)
    private LocalDateTime createTime;

    /** 更新者（对齐 zjky BaseDO.updater）。 */
    private String updater;

    /**
     * 更新时间（对齐 zjky BaseDO.updateTime；DB 列 update_time TEXT）。
     *
     * <p>{@code typeHandler} 同上，保证落库形态与 {@code create_time} 一致。
     */
    @Column(typeHandler = KbSqliteLocalDateTimeTypeHandler.class)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志（对齐 zjky BaseDO.deleted）。
     *
     * <p>{@code @Column(isLogicDelete = true)} ⇒ Flex 查询自动追加
     * {@code AND deleted = 0}，且调用 {@code deleteById} 时改为 UPDATE。
     * <p>DB 列类型为 {@code INTEGER}（SQLite 无 {@code bit} 类型），0 = 未删 / 1 = 已删。
     */
    @Column(isLogicDelete = true)
    private Integer deleted;

    /**
     * 租户编号（对齐 zjky TenantBaseDO.tenantId）。
     *
     * <p>{@code @Column(tenantId = true)} 让 Flex 在插入时自动填租户值。
     * <p>NexusAI 当前是单机应用、恒 0；列保留是为了将来接入 zjky 租户框架时不用改表。
     */
    @Column(tenantId = true)
    private Long tenantId;

    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getUpdater() { return updater; }
    public void setUpdater(String updater) { this.updater = updater; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}
