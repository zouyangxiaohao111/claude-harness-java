package com.nexusai.infra.util;

/**
 * Auto-mode 全局静态状态 · 对齐 CC autoModeState（state.ts / permissionSetup.ts）。
 *
 * <p>[P2 · OPD-WF1-CFG-v4-04 拍板：补「未缓存 undefined」三态] CC {@code getAutoModeEnabledStateIfCached()}
 * （permissionSetup.ts:1335-1352）返回 {@code AutoModeEnabledState | undefined}：无缓存（冷启动，
 * GrowthBook 未初始化）返回 undefined —— 同步断路器检查（initialPermissionModeFromCLI :717-720）
 * 不能把「未取到」与「已取到且 disabled」混为一谈：前者<b>延迟</b>到 verifyAutoModeGateAccess，
 * 后者<b>立即阻断</b>。Java 端原 boolean {@code autoModeCircuitBroken} 只有 false/true 两态，
 * 无法表达「未取到」→ 本类以 {@link AutoModeConfigState} 三态建模（UNDEFINED / ENABLED / DISABLED），
 * {@link #isAutoModeCircuitBroken()} 保持 boolean 消费面（UNDEFINED → 不阻断，等价 CC undefined）。
 */
public final class AutoModeState {
    private static volatile boolean autoModeActive = false;
    private static volatile boolean autoModeFlagCli = false;
    /** auto-mode 配置三态 · 对齐 CC getAutoModeEnabledStateIfCached 的 undefined 三态（permissionSetup.ts:1335-1352）。 */
    private static volatile AutoModeConfigState autoModeConfigState = AutoModeConfigState.UNDEFINED;

    /**
     * auto-mode 配置状态三态 · 对齐 CC {@code AutoModeEnabledState | undefined}
     * （permissionSetup.ts:1335-1352，undefined = 未取到）。
     */
    public enum AutoModeConfigState {
        /** 未取到（CC undefined）——冷启动/未解析，不阻断，延迟到 verifyAutoModeGateAccess。 */
        UNDEFINED,
        /** 已取到且非 disabled（CC 'enabled'/'opt-in'）——不阻断。 */
        ENABLED,
        /** 已取到且 disabled（CC 'disabled'）——立即阻断（circuit breaker）。 */
        DISABLED
    }

    private AutoModeState() {}

    public static void setAutoModeActive(boolean active) { autoModeActive = active; }
    public static boolean isAutoModeActive() { return autoModeActive; }
    public static void setAutoModeFlagCli(boolean passed) { autoModeFlagCli = passed; }
    public static boolean getAutoModeFlagCli() { return autoModeFlagCli; }

    /**
     * 写入断路器状态 · 对齐 CC {@code setAutoModeCircuitBroken}（permissionSetup.ts:1099，
     * {@code setAutoModeCircuitBroken(enabledState === 'disabled' || disabledBySettings)}）。
     *
     * <p>[P2 三态] true → {@link AutoModeConfigState#DISABLED}（已取到且 disabled）；
     * false → {@link AutoModeConfigState#ENABLED}（已取到且非 disabled）——不再有「未取到」歧义。
     *
     * @param broken 断路器是否激活（CC enabled==='disabled'）
     */
    public static void setAutoModeCircuitBroken(boolean broken) {
        autoModeConfigState = broken ? AutoModeConfigState.DISABLED : AutoModeConfigState.ENABLED;
    }

    /**
     * 断路器是否激活 · 对齐 CC {@code isAutoModeCircuitBroken() ?? false}（permissionSetup.ts:1284）。
     *
     * <p>[P2 三态] 仅 {@link AutoModeConfigState#DISABLED}（已取到且 disabled）为 true；
     * {@link AutoModeConfigState#UNDEFINED}（未取到）<b>不阻断</b>（等价 CC undefined →
     * 延迟到 verifyAutoModeGateAccess，permissionSetup.ts:1341-1343 注释语义）。
     *
     * @return true = circuit broken（auto 进入/重入被阻断）
     */
    public static boolean isAutoModeCircuitBroken() {
        return autoModeConfigState == AutoModeConfigState.DISABLED;
    }

    /**
     * CC {@code getAutoModeEnabledStateIfCached} 等价（permissionSetup.ts:1344-1352）。
     *
     * <p>返回 {@code AutoModeEnabledState | undefined}：{@link AutoModeConfigState#UNDEFINED}
     * → {@code null}（Java 表达 undefined，未取到）；{@link AutoModeConfigState#DISABLED} →
     * {@code "disabled"}；{@link AutoModeConfigState#ENABLED} → {@code "enabled"}。
     * 同步断路器检查（initialPermissionModeFromCLI :718）消费语义：
     * {@code === 'disabled'} 才阻断，null/其它不阻断。
     *
     * @return "disabled" / "enabled" / null（未取到）
     */
    public static String getAutoModeEnabledStateIfCached() {
        return switch (autoModeConfigState) {
            case UNDEFINED -> null;
            case DISABLED -> "disabled";
            case ENABLED -> "enabled";
        };
    }

    public static void resetForTesting() {
        autoModeActive = false;
        autoModeFlagCli = false;
        autoModeConfigState = AutoModeConfigState.UNDEFINED;
    }
}
