package com.nexusai.application.agent.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * bypassPermissions 禁用门（killswitch）· 对齐 CC
 * {@code bypassPermissionsKillswitch.ts} + {@code permissionSetup.ts:1265-1431}。
 *
 * <h2>覆盖的 CC 函数</h2>
 * <ul>
 *   <li>{@link #shouldDisableBypassPermissions(BooleanSupplier)} — CC
 *       {@code shouldDisableBypassPermissions()}（permissionSetup.ts:1265-1267），
 *       异步 Statsig 门 {@code checkSecurityRestrictionGate('tengu_disable_bypass_permissions_mode')}
 *       —— Java 无异步 gate infra，建模为注入 {@link BooleanSupplier}（默认 false，见 concerns）。</li>
 *   <li>{@link #isBypassPermissionsModeDisabled(BooleanSupplier, boolean)} — CC
 *       {@code isBypassPermissionsModeDisabled()}（permissionSetup.ts:1371-1384），
 *       同步 cached Statsig 门 || settings.disableBypassPermissionsMode==='disable'。</li>
 *   <li>{@link #isBypassPermissionsModeAvailable(PermissionMode, boolean, BooleanSupplier, boolean)}
 *       — CC {@code isBypassPermissionsModeAvailable} 三条件公式（permissionSetup.ts:938-944），
 *       F1-BY 收敛统一计算（原内联于 PermissionContextBuilder.buildPermissionContextCore）。</li>
 *   <li>{@link #createDisabledBypassPermissionsContext(ToolPermissionContext)} — CC
 *       {@code createDisabledBypassPermissionsContext()}（permissionSetup.ts:1389-1406）：
 *       mode==='bypassPermissions' → setMode default；返回 isBypassPermissionsModeAvailable=false。</li>
 *   <li>{@link #checkAndDisableBypassPermissionsIfNeeded(ToolPermissionContext, BooleanSupplier)}
 *       — CC {@code checkAndDisableBypassPermissionsIfNeeded()}（bypassPermissionsKillswitch.ts:17-41）：
 *       run-once 旗标 + isBypassPermissionsModeAvailable 短路 + Statsig 门判定 + 禁用降级。</li>
 *   <li>{@link #resetBypassPermissionsCheck()} — CC {@code resetBypassPermissionsCheck()}
 *       （bypassPermissionsKillswitch.ts:53），/login 后重置 run-once 旗标。</li>
 * </ul>
 *
 * <h2>run-once 旗标</h2>
 * <p>CC 用模块级 {@code let bypassPermissionsCheckRan = false}（bypassPermissionsKillswitch.ts:17）。
 * Java 端映射为 {@code @Component} 单例的实例字段 {@link AtomicBoolean}（会话/应用级 run-once）。
 * 测试可用 fresh instance 验证 run-once 语义（每个实例旗标独立，确定性）。
 *
 * <h2>Java 返回约定（对齐 CC setAppState）</h2>
 * <p>CC {@code checkAndDisableBypassPermissionsIfNeeded} 通过 React {@code setAppState} 原地更新 state；
 * Java 无 React state，等价建模为<b>返回</b>（可能已禁用降级的）新上下文，调用方据返回值替换会话级
 * context。无变化时返回原引用。
 */
@Component
public class BypassPermissionsKillswitch {

    private static final Logger log = LoggerFactory.getLogger(BypassPermissionsKillswitch.class);

    /**
     * CC bypassPermissionsKillswitch.ts:17 —— {@code let bypassPermissionsCheckRan = false}。
     *
     * <p>run-once：首个 query 前仅检查一次，保证拿到最新 gate 值。
     */
    private final AtomicBoolean bypassPermissionsCheckRan = new AtomicBoolean(false);

    /**
     * CC {@code shouldDisableBypassPermissions()}（permissionSetup.ts:1265-1267）。
     *
     * @param securityRestrictionGate CC {@code checkSecurityRestrictionGate('tengu_disable_bypass_permissions_mode')}
     *                                等价（null → 不禁用）
     * @return 是否应禁用 bypassPermissions（异步 Statsig 门）
     */
    public static boolean shouldDisableBypassPermissions(BooleanSupplier securityRestrictionGate) {
        return securityRestrictionGate != null && securityRestrictionGate.getAsBoolean();
    }

    /**
     * CC {@code isBypassPermissionsModeDisabled()}（permissionSetup.ts:1371-1384）。
     *
     * <p>同步版本：cached Statsig 门 || settings.disableBypassPermissionsMode==='disable'。
     *
     * @param statsigCachedGate                 CC {@code checkStatsigFeatureGate_CACHED_MAY_BE_STALE(...)}
     *                                          （null → false）
     * @param settingsDisableBypassPermissionsMode settings.permissions.disableBypassPermissionsMode === 'disable'
     * @return 是否禁用 bypassPermissions
     */
    public static boolean isBypassPermissionsModeDisabled(
            BooleanSupplier statsigCachedGate, boolean settingsDisableBypassPermissionsMode) {
        boolean growthBookDisable = statsigCachedGate != null && statsigCachedGate.getAsBoolean();
        return growthBookDisable || settingsDisableBypassPermissionsMode;
    }

    /**
     * CC {@code isBypassPermissionsModeAvailable} 三条件公式（permissionSetup.ts:938-944）。
     *
     * <p><b>CC 真源（不信注释，Read TS 实测）</b>：
     * <pre>
     * const growthBookDisableBypassPermissionsMode =
     *     checkStatsigFeatureGate_CACHED_MAY_BE_STALE('tengu_disable_bypass_permissions_mode')
     * const settingsDisableBypassPermissionsMode =
     *     settings.permissions?.disableBypassPermissionsMode === 'disable'
     * const isBypassPermissionsModeAvailable =
     *     (permissionMode === 'bypassPermissions' || allowDangerouslySkipPermissions) &amp;&amp;
     *     !growthBookDisableBypassPermissionsMode &amp;&amp;
     *     !settingsDisableBypassPermissionsMode
     * </pre>
     *
     * <p>收敛三条件：{@code (mode==bypassPermissions || dangerouslySkip) && !org门 && !settings.disable}。
     * 这是启动时<b>一次性</b>计算的 ToolPermissionContext 字段；per-turn 重建保留原值、不重算
     * （CC {@code applyPermissionUpdate} setMode 用 {@code {...context, mode}} spread，PermissionUpdate.ts:60-67）。
     *
     * @param mode                               当前权限模式（CC permissionMode）
     * @param allowDangerouslySkipPermissions    CC allowDangerouslySkipPermissions（--dangerously-skip-permissions）
     * @param statsigCachedGate                  CC growthBookDisableBypassPermissionsMode =
     *                                           checkStatsigFeatureGate_CACHED_MAY_BE_STALE(...)（null → false）
     * @param settingsDisableBypassPermissionsMode CC settingsDisableBypassPermissionsMode =
     *                                           settings.permissions?.disableBypassPermissionsMode === 'disable'
     * @return 是否允许 bypassPermissions mode（三条件全真）
     */
    public static boolean isBypassPermissionsModeAvailable(
            PermissionMode mode,
            boolean allowDangerouslySkipPermissions,
            BooleanSupplier statsigCachedGate,
            boolean settingsDisableBypassPermissionsMode) {
        boolean modeAllowsBypass = mode == PermissionMode.BYPASS_PERMISSIONS
                || allowDangerouslySkipPermissions;
        boolean growthBookDisable = statsigCachedGate != null && statsigCachedGate.getAsBoolean();
        return modeAllowsBypass && !growthBookDisable && !settingsDisableBypassPermissionsMode;
    }

    /**
     * CC {@code createDisabledBypassPermissionsContext()}（permissionSetup.ts:1389-1406）。
     *
     * <p>mode==='bypassPermissions' → 降级为 default；返回 isBypassPermissionsModeAvailable=false 的新上下文
     * （ToolPermissionContext 不可变，无 withMode 辅助 → 整字段重建）。
     *
     * @param currentContext 当前上下文
     * @return 禁用 bypassPermissions 后的新上下文
     */
    public static ToolPermissionContext createDisabledBypassPermissionsContext(
            ToolPermissionContext currentContext) {
        Objects.requireNonNull(currentContext, "currentContext");
        PermissionMode mode = currentContext.mode();
        if (mode == PermissionMode.BYPASS_PERMISSIONS) {
            // CC :1393-1398 —— applyPermissionUpdate setMode 'default'（destination: session）
            mode = PermissionMode.DEFAULT;
        }
        // CC :1401-1405 —— {...updatedContext, isBypassPermissionsModeAvailable: false}
        return new ToolPermissionContext(
                mode,
                currentContext.alwaysAllowRules(),
                currentContext.alwaysDenyRules(),
                currentContext.alwaysAskRules(),
                currentContext.additionalWorkingDirectories(),
                false,
                currentContext.isAutoModeAvailable(),
                currentContext.strippedDangerousRules(),
                currentContext.shouldAvoidPermissionPrompts(),
                currentContext.awaitAutomatedChecksBeforeDialog(),
                currentContext.prePlanMode());
    }

    /**
     * CC {@code checkAndDisableBypassPermissionsIfNeeded()}（bypassPermissionsKillswitch.ts:17-41）。
     *
     * <p>顺序（逐条对照 CC）：
     * <ol>
     *   <li>run-once 旗标已置 → 原样返回（:22-25）</li>
     *   <li>{@code !ctx.isBypassPermissionsModeAvailable} → 原样返回（:27-29）</li>
     *   <li>{@code !shouldDisableBypassPermissions()} → 原样返回（:31-33）</li>
     *   <li>否则 → {@code createDisabledBypassPermissionsContext}（:35-40）</li>
     * </ol>
     *
     * @param ctx                     当前工具权限上下文
     * @param securityRestrictionGate 异步 Statsig 门（{@code checkSecurityRestrictionGate} 等价）
     * @return 可能已禁用降级的上下文（无变化返回原引用）
     */
    public ToolPermissionContext checkAndDisableBypassPermissionsIfNeeded(
            ToolPermissionContext ctx, BooleanSupplier securityRestrictionGate) {
        Objects.requireNonNull(ctx, "ctx");
        if (bypassPermissionsCheckRan.getAndSet(true)) {
            // CC :22-25 —— run-once
            return ctx;
        }
        if (!ctx.isBypassPermissionsModeAvailable()) {
            // CC :27-29
            return ctx;
        }
        if (!shouldDisableBypassPermissions(securityRestrictionGate)) {
            // CC :31-33
            return ctx;
        }
        log.warn("BypassPermissionsKillswitch: bypassPermissions 被 Statsig 门禁用（异步检查），"
                + "降级到 default mode");
        return createDisabledBypassPermissionsContext(ctx);
    }

    /**
     * CC {@code resetBypassPermissionsCheck()}（bypassPermissionsKillswitch.ts:53）。
     *
     * <p>/login 后重置 run-once 旗标，使门检查随新 org 重新执行。
     */
    public void resetBypassPermissionsCheck() {
        bypassPermissionsCheckRan.set(false);
        if (log.isDebugEnabled()) {
            log.debug("BypassPermissionsKillswitch: run-once 旗标已重置（/login 后重新检查）");
        }
    }
}
