package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.hook.ManagedPolicySettingsSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 企业管控 permission-rules 策略判定器 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/permissions/permissionsLoader.ts} 的
 * {@code shouldAllowManagedPermissionRulesOnly}（:31-36）与
 * {@code shouldShowAlwaysAllowOptions}（:42-44）。
 *
 * <p><b>WHY (IMP-3 G1 / P1-G1)</b>: hooks 侧 managed-only 门控已实现
 * （{@link com.nexusai.application.agent.permission.hook.HooksSettings#shouldAllowManagedHooksOnly}，
 * hooksConfigSnapshot.ts:62-76），而 permission-rules 侧同名能力完全缺失——Java 全仓
 * {@code allowManagedPermissionRulesOnly} 仅 1 命中（settings schema 定义），无任何功能门控。
 * 本类补上判定函数，供 {@link PermissionUpdatePersister}（写盘早退）与
 * {@link PermissionContextBuilder}（加载过滤）两处接线。
 *
 * <p><b>CC 真源键</b>: {@code policySettings.allowManagedPermissionRulesOnly}（permissionsLoader.ts:33），
 * 严格 {@code === true}。读取通道复用 {@link ManagedPolicySettingsSupplier}（等价 CC
 * {@code getSettingsForSource('policySettings')?.[key]}），与 hooks 侧同源同键语义
 * （同一 managed-settings.json 文件、同一 {@code nexusai.policy.path} 配置）。
 *
 * <p><b>local-only 约束</b>: 本类不向外发送任何数据，仅本地策略文件查询。
 *
 * @see ManagedPolicySettingsSupplier
 */
@Component
public class PermissionManagedPolicy {

    private static final Logger log = LoggerFactory.getLogger(PermissionManagedPolicy.class);

    /** 企业 managed policy 文件读取器（等价 CC getSettingsForSource('policySettings')）。 */
    private final ManagedPolicySettingsSupplier managedPolicySettingsSupplier;

    /**
     * Spring 注入构造器。
     *
     * @param managedPolicySettingsSupplier 企业 policy 文件读取器（非 null）
     */
    public PermissionManagedPolicy(ManagedPolicySettingsSupplier managedPolicySettingsSupplier) {
        if (managedPolicySettingsSupplier == null) {
            throw new IllegalArgumentException("managedPolicySettingsSupplier is null");
        }
        this.managedPolicySettingsSupplier = managedPolicySettingsSupplier;
    }

    /**
     * 是否仅允许 managed permission rules · 对齐 CC permissionsLoader.ts:31-36
     * {@code shouldAllowManagedPermissionRulesOnly}：读 {@code policySettings.allowManagedPermissionRulesOnly}
     * 严格 {@code === true}。
     *
     * @return true = 仅允许 managed（policy）规则生效，用户可编辑源被门控
     */
    public boolean shouldAllowManagedPermissionRulesOnly() {
        Object v = managedPolicySettingsSupplier.get("allowManagedPermissionRulesOnly");
        boolean managedOnly = Boolean.TRUE.equals(v);
        if (log.isDebugEnabled()) {
            log.debug("PermissionManagedPolicy.shouldAllowManagedPermissionRulesOnly: {} (policySettings.allowManagedPermissionRulesOnly={})",
                managedOnly, v);
        }
        return managedOnly;
    }

    /**
     * 是否显示 "always allow" 选项 · 对齐 CC permissionsLoader.ts:42-44
     * {@code shouldShowAlwaysAllowOptions}：managed-only 时隐藏（{@code !shouldAllowManagedPermissionRulesOnly()}）。
     *
     * @return true = 显示 "always allow" 选项；false = managed-only，隐藏
     */
    public boolean shouldShowAlwaysAllowOptions() {
        return !shouldAllowManagedPermissionRulesOnly();
    }
}
