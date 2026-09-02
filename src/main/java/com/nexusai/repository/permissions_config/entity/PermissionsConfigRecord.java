package com.nexusai.repository.permissions_config.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * MyBatis-Flex 持久化记录：{@code permissions_config} 表行（单行配置 id=1）。
 *
 * <p>对齐 CC Statsig org 门 {@code 'tengu_disable_bypass_permissions_mode'}
 * （permissionSetup.ts:701 / :934 / :1374）。本项目无 Statsig infra，以数据库单行开关替代
 * （用户拍板方案 A）。{@code disableBypassPermissions} 列映射 {@code disable_bypass_permissions}
 * （MyBatis-Flex 默认 camelCase → snake_case）。
 *
 * <p>单行配置：{@code id} 恒 1（V16 DDL {@code CHECK (id=1)}），与
 * {@link com.nexusai.repository.settings.entity.SettingsRecord}（settings 单例表）同风格。
 */
@Table("permissions_config")
public class PermissionsConfigRecord {
    @Id private Integer id;                       // always 1（单行配置）
    private Integer disableBypassPermissions;     // 0=不禁用 / 1=禁用（CC gate）
    private String createdAt;
    private String updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getDisableBypassPermissions() { return disableBypassPermissions; }
    public void setDisableBypassPermissions(Integer disableBypassPermissions) { this.disableBypassPermissions = disableBypassPermissions; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
