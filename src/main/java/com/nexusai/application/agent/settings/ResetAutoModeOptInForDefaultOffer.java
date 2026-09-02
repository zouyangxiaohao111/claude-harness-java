package com.nexusai.application.agent.settings;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reset auto-mode opt-in 一次性迁移 · 对齐 CC migrations/resetAutoModeOptInForDefaultOffer.ts.
 *
 * <p>L1 语义: 一次性 migration.清掉接受了旧版 2-选项 AutoModeOptInDialog 但 default 不是 auto
 *            的用户的 skipAutoPermissionPrompt,让 dialog 重新浮现 (新版本含 "make it my default mode" 选项).
 *            守卫存在 GlobalConfig (~/.nexusai.json) 而非 settings.json,这样 settings reset 不会重新激活.
 *            仅在 getAutoModeEnabledState() === 'enabled' 时跑 (opt-in 用户清掉会从 carousel 移除 auto,自身失效).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: reset() → void;3 个守卫 (feature flag / already reset / not enabled);
 *       主条件 (skipAutoPermissionPrompt=true + defaultMode≠auto) → 调 updateSettingsForSource 清字段 + logEvent;
 *       finally 段 saveGlobalConfig 写 hasReset=true (幂等). 注入式 supplier/consumer.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — feature=true → config 缺 hasReset → enabled → user.skipAuto=true + defaultMode≠auto → updateSettings + logEvent + saveGlobalConfig(hasReset=true).
 *       短路: feature=false / already reset / not enabled → 直接 return.</li>
 *   <li><b>A3</b>: 状态机: NOT_RESET → RESET;3 个守卫 (feature/alreadyReset/enabled) 决定主链是否执行.
 *       try/catch 包整段,异常 logError 但不抛 (与 CC 一致).</li>
 *   <li><b>A4</b>: 已 reset (hasReset=true) → 短路;enabled state 非 'enabled' → 短路;
 *       settings 缺 skipAutoPermissionPrompt 或 defaultMode==='auto' → 不动 settings (但仍写 hasReset).</li>
 *   <li><b>A5</b>: 真实场景 — 老 ant 用户按 Shift+Tab 进入旧 dialog + 默认非 auto → 接受 dialog 但 skipAuto=true;
 *       跑 migration → 清 skipAuto + logEvent + GlobalConfig 写 hasReset=true (下次启动不再跑).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `feature('TRANSCRIPT_CLASSIFIER')` → 注入式 BooleanSupplier;
 *                    TS `getGlobalConfig()` → 注入式 Supplier&lt;GlobalConfig&gt;;
 *                    TS `saveGlobalConfig(c => ...)` → 注入式 Consumer&lt;UnaryOperator&lt;GlobalConfig&gt;&gt;;
 *                    TS `logEvent` / `logError` → 注入式 Consumer&lt;String&gt;.
 */
public final class ResetAutoModeOptInForDefaultOffer {

    private static final Logger log = LoggerFactory.getLogger(ResetAutoModeOptInForDefaultOffer.class);

    private static final String ENABLED_STATE = "enabled";
    private static final String AUTO_MODE = "auto";

    private final BooleanSupplier featureFlag;            // TRANSCRIPT_CLASSIFIER
    private final Supplier<GlobalConfig> configReader;
    private final Consumer<UnaryOperator<GlobalConfig>> configSaver;
    private final Supplier<String> autoModeStateSupplier;  // 'enabled' / 'opt-in' / other
    private final Supplier<UserSettings> userSettingsReader;
    private final UserSettingsUpdater userSettingsUpdater;
    private final Consumer<String> eventLogger;           // logEvent
    private final Consumer<Throwable> errorLogger;        // logError

    public ResetAutoModeOptInForDefaultOffer(BooleanSupplier featureFlag,
                                              Supplier<GlobalConfig> configReader,
                                              Consumer<UnaryOperator<GlobalConfig>> configSaver,
                                              Supplier<String> autoModeStateSupplier,
                                              Supplier<UserSettings> userSettingsReader,
                                              UserSettingsUpdater userSettingsUpdater,
                                              Consumer<String> eventLogger,
                                              Consumer<Throwable> errorLogger) {
        this.featureFlag = Objects.requireNonNull(featureFlag);
        this.configReader = Objects.requireNonNull(configReader);
        this.configSaver = Objects.requireNonNull(configSaver);
        this.autoModeStateSupplier = Objects.requireNonNull(autoModeStateSupplier);
        this.userSettingsReader = Objects.requireNonNull(userSettingsReader);
        this.userSettingsUpdater = Objects.requireNonNull(userSettingsUpdater);
        this.eventLogger = Objects.requireNonNull(eventLogger);
        this.errorLogger = Objects.requireNonNull(errorLogger);
    }

    /** CC GlobalConfig — 仅暴露本 migration 需要的 1 字段. */
    public record GlobalConfig(boolean hasResetAutoModeOptInForDefaultOffer) {
        public static final GlobalConfig DEFAULT = new GlobalConfig(false);
    }

    /** CC UserSettings 最小子集 — 仅 2 字段. */
    public record UserSettings(boolean skipAutoPermissionPrompt, String defaultMode) {}

    /** CC updateSettingsForSource(userSettings, {skipAutoPermissionPrompt: undefined}) 等价. */
    @FunctionalInterface
    public interface UserSettingsUpdater {
        void clearSkipAutoPermissionPrompt();
    }

    /** CC resetAutoModeOptInForDefaultOffer — 主链. */
    public void reset() {
        if (!featureFlag.getAsBoolean()) {
            return;
        }
        GlobalConfig config = configReader.get();
        if (config.hasResetAutoModeOptInForDefaultOffer()) {
            return;
        }
        if (!ENABLED_STATE.equals(autoModeStateSupplier.get())) {
            return;
        }

        try {
            UserSettings user = userSettingsReader.get();
            if (user != null
                && user.skipAutoPermissionPrompt()
                && !AUTO_MODE.equals(user.defaultMode())) {
                userSettingsUpdater.clearSkipAutoPermissionPrompt();
                eventLogger.accept("tengu_migrate_reset_auto_opt_in_for_default_offer");
            }

            // 始终写 hasReset (幂等) — 即使 settings 没满足条件,GlobalConfig 也要守卫下一次不再跑
            configSaver.accept(c -> c.hasResetAutoModeOptInForDefaultOffer()
                ? c
                : new GlobalConfig(true));
        } catch (Throwable error) {
            errorLogger.accept(new RuntimeException(
                "Failed to reset auto mode opt-in: " + error, error));
        }
    }
}
