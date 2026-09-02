package com.nexusai.application.agent.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supported settings registry · 对齐 CC tools/ConfigTool/supportedSettings.ts.
 *
 * <p>CC source: tools/ConfigTool/supportedSettings.ts (211 LOC).
 * Single SettingConfig record + SUPPORTED_SETTINGS map + 4 helper methods.
 * Conditional settings based on feature flags (AUTO_THEME/TRANSCRIPT_CLASSIFIER/VOICE_MODE/etc).
 */
public final class SupportedSettings {

    private static final Logger log = LoggerFactory.getLogger(SupportedSettings.class);

    public record SettingConfig(
        String source,            // "global" or "settings"
        String type,              // "boolean" or "string"
        String description,
        List<String> path,        // null = use key.split('.')
        java.util.Set<String> options,
        java.util.List<String> getOptionsResult,  // pre-computed (when no getOptions)
        String appStateKey,
        Boolean validateOnWriteOk,
        String validateOnWriteError,
        Object formatOnReadResult
    ) {
        public static SettingConfig of(String source, String type, String description) {
            return new SettingConfig(source, type, description, null, null, null, null, null, null, null);
        }
    }

    private final BooleanSupplier autoThemeSupplier;
    private final BooleanSupplier transcriptClassifierSupplier;
    private final BooleanSupplier voiceModeSupplier;
    private final BooleanSupplier bridgeModeSupplier;
    private final BooleanSupplier kairosSupplier;
    private final BooleanSupplier kairosPushSupplier;
    private final BooleanSupplier isAntSupplier;
    private final Supplier<List<String>> modelOptionsSupplier;
    private final Function<String, java.util.concurrent.CompletableFuture<ValidationResult>> modelValidator;
    private final Supplier<String> remoteControlAtStartupSupplier;
    private final List<String> editorModes;
    private final List<String> notificationChannels;
    private final List<String> teammateModes;
    private final List<String> themeNames;
    private final List<String> themeSettings;

    public SupportedSettings(BooleanSupplier autoThemeSupplier,
                             BooleanSupplier transcriptClassifierSupplier,
                             BooleanSupplier voiceModeSupplier,
                             BooleanSupplier bridgeModeSupplier,
                             BooleanSupplier kairosSupplier,
                             BooleanSupplier kairosPushSupplier,
                             BooleanSupplier isAntSupplier,
                             Supplier<List<String>> modelOptionsSupplier,
                             Function<String, java.util.concurrent.CompletableFuture<ValidationResult>> modelValidator,
                             Supplier<String> remoteControlAtStartupSupplier,
                             List<String> editorModes,
                             List<String> notificationChannels,
                             List<String> teammateModes,
                             List<String> themeNames,
                             List<String> themeSettings) {
        this.autoThemeSupplier = autoThemeSupplier;
        this.transcriptClassifierSupplier = transcriptClassifierSupplier;
        this.voiceModeSupplier = voiceModeSupplier;
        this.bridgeModeSupplier = bridgeModeSupplier;
        this.kairosSupplier = kairosSupplier;
        this.kairosPushSupplier = kairosPushSupplier;
        this.isAntSupplier = isAntSupplier;
        this.modelOptionsSupplier = modelOptionsSupplier;
        this.modelValidator = modelValidator;
        this.remoteControlAtStartupSupplier = remoteControlAtStartupSupplier;
        this.editorModes = editorModes != null ? editorModes : List.of();
        this.notificationChannels = notificationChannels != null ? notificationChannels : List.of();
        this.teammateModes = teammateModes != null ? teammateModes : List.of();
        this.themeNames = themeNames != null ? themeNames : List.of();
        this.themeSettings = themeSettings != null ? themeSettings : List.of();
    }

    public record ValidationResult(boolean valid, String error) {}

    /** CC isSupported. */
    public boolean isSupported(String key) {
        return getAll().containsKey(key);
    }

    /** CC getConfig. */
    public SettingConfig getConfig(String key) {
        return getAll().get(key);
    }

    /** CC getAllKeys. */
    public List<String> getAllKeys() {
        return List.copyOf(getAll().keySet());
    }

    /** CC getOptionsForSetting. */
    public List<String> getOptionsForSetting(String key) {
        SettingConfig c = getAll().get(key);
        if (c == null) return null;
        if (c.options() != null) return List.copyOf(c.options());
        if (c.getOptionsResult() != null) return c.getOptionsResult();
        return null;
    }

    /** CC getPath. */
    public List<String> getPath(String key) {
        SettingConfig c = getAll().get(key);
        if (c == null || c.path() == null) {
            return List.of(key.split("\\."));
        }
        return c.path();
    }

    /**
     * CC validateOnWrite — 返回指定 setting 的异步校验函数 (nullable).
     *
     * <p>CC supportedSettings.ts:104 {@code validateOnWrite: v => validateModel(String(v))}.
     * Java 侧仅 {@code model} 定义校验 (经注入的 {@link #modelValidator}, 默认
     * {@code SupportedSettingsConfig.defaultModelValidator});其余 setting 无校验.
     *
     * @param key setting key (e.g. "model")
     * @return {@code Function<Object, CompletableFuture<ValidationResult>>} 或 null (无校验)
     */
    public Function<Object, java.util.concurrent.CompletableFuture<ValidationResult>> validateOnWriteFn(String key) {
        if (key == null) return null;
        SettingConfig c = getAll().get(key);
        if (c == null || !"model".equals(key) || modelValidator == null) return null;
        return v -> modelValidator.apply(String.valueOf(v));
    }

    /**
     * CC formatOnRead — 返回指定 setting 的 GET 展示格式化函数 (nullable).
     *
     * <p>CC supportedSettings.ts:105 {@code formatOnRead: v => (v === null ? 'default' : v)}
     * (model 未配置 / JSON null → 显示 'default');
     * CC :160 {@code formatOnRead: () => getRemoteControlAtStartup()} (remoteControlAtStartup 动态读取).
     *
     * @param key setting key (e.g. "model")
     * @return {@code Function<Object, Object>} 或 null (无格式化)
     */
    public Function<Object, Object> formatOnReadFn(String key) {
        if (key == null) return null;
        SettingConfig c = getAll().get(key);
        if (c == null) return null;
        if ("model".equals(key)) {
            // CC v === null ? 'default' : v — Java absent(null)/JSON null(NullMarker) 均显示 'default'
            return v -> (v == null || v == com.nexusai.application.agent.settings.storage.ConfigStorage.NullMarker)
                ? "default"
                : v;
        }
        if ("remoteControlAtStartup".equals(key) && remoteControlAtStartupSupplier != null) {
            return v -> remoteControlAtStartupSupplier.get();
        }
        return null;
    }

    /** Build the SUPPORTED_SETTINGS map. */
    public Map<String, SettingConfig> getAll() {
        Map<String, SettingConfig> m = new LinkedHashMap<>();
        m.put("theme", new SettingConfig("global", "string", "Color theme for the UI",
            null, java.util.Set.copyOf(autoThemeSupplier.getAsBoolean() ? themeSettings : themeNames),
            null, null, null, null, null));
        m.put("editorMode", new SettingConfig("global", "string", "Key binding mode",
            null, java.util.Set.copyOf(editorModes), null, null, null, null, null));
        m.put("verbose", new SettingConfig("global", "boolean", "Show detailed debug output",
            null, null, null, "verbose", null, null, null));
        m.put("preferredNotifChannel", new SettingConfig("global", "string", "Preferred notification channel",
            null, java.util.Set.copyOf(notificationChannels), null, null, null, null, null));
        m.put("autoCompactEnabled", new SettingConfig("global", "boolean", "Auto-compact when context is full",
            null, null, null, null, null, null, null));
        m.put("autoMemoryEnabled", new SettingConfig("settings", "boolean", "Enable auto-memory",
            null, null, null, null, null, null, null));
        // [V56 · 用户 2026-08-30 拍板] autoDreamEnabled 改由 DB settings 列 auto_dream_enabled 主控
        //   （默认开，弃 settings.json 文件承载键）。注册保留供 /config 工具枚举配置面（DB 列承载
        //   满足「配置面不悬空」意图，对齐 AutoDreamConsolidator:158-162 备案）；实际读写走
        //   BundledSkillEnabledGates（DB 列），settings.json 不再承载该键
        //   （SettingsSchemaGenerator 已移除 schema 声明）。
        m.put("autoDreamEnabled", new SettingConfig("settings", "boolean",
            "Enable background memory consolidation (DB settings column auto_dream_enabled, V56; default on)",
            null, null, null, null, null, null, null));
        m.put("fileCheckpointingEnabled", new SettingConfig("global", "boolean", "Enable file checkpointing for code rewind",
            null, null, null, null, null, null, null));
        m.put("showTurnDuration", new SettingConfig("global", "boolean",
            "Show turn duration message after responses (e.g., \"Cooked for 1m 6s\")",
            null, null, null, null, null, null, null));
        m.put("terminalProgressBarEnabled", new SettingConfig("global", "boolean",
            "Show OSC 9;4 progress indicator in supported terminals",
            null, null, null, null, null, null, null));
        m.put("todoFeatureEnabled", new SettingConfig("global", "boolean",
            "Enable todo/task tracking", null, null, null, null, null, null, null));

        // model: dynamic getOptions
        List<String> modelOptions = null;
        if (modelOptionsSupplier != null) {
            try { modelOptions = modelOptionsSupplier.get(); } catch (Exception e) { modelOptions = List.of("sonnet", "opus", "haiku"); }
        } else {
            modelOptions = List.of("sonnet", "opus", "haiku");
        }
        m.put("model", new SettingConfig("settings", "string", "Override the default model",
            null, null, modelOptions, "mainLoopModel", null, null, "default"));

        m.put("alwaysThinkingEnabled", new SettingConfig("settings", "boolean",
            "Enable extended thinking (false to disable)",
            null, null, null, "thinkingEnabled", null, null, null));

        // permissions.defaultMode: depends on TRANSCRIPT_CLASSIFIER
        java.util.Set<String> permModes = transcriptClassifierSupplier.getAsBoolean()
            ? java.util.Set.of("default", "plan", "acceptEdits", "dontAsk", "auto")
            : java.util.Set.of("default", "plan", "acceptEdits", "dontAsk");
        m.put("permissions.defaultMode", new SettingConfig("settings", "string", "Default permission mode for tool usage",
            List.of("permissions", "defaultMode"), permModes, null, null, null, null, null));

        m.put("language", new SettingConfig("settings", "string",
            "Preferred language for Claude responses and voice dictation (e.g., \"japanese\", \"spanish\")",
            null, null, null, null, null, null, null));
        m.put("teammateMode", new SettingConfig("global", "string",
            "How to spawn teammates: \"tmux\" for traditional tmux, \"in-process\" for same process, \"auto\" to choose automatically",
            null, java.util.Set.copyOf(teammateModes), null, null, null, null, null));

        // Ant-only setting
        if (isAntSupplier.getAsBoolean()) {
            m.put("classifierPermissionsEnabled", new SettingConfig("settings", "boolean",
                "Enable AI-based classification for Bash(prompt:...) permission rules",
                null, null, null, null, null, null, null));
        }

        // VOICE_MODE
        if (voiceModeSupplier.getAsBoolean()) {
            m.put("voiceEnabled", new SettingConfig("settings", "boolean",
                "Enable voice dictation (hold-to-talk)",
                null, null, null, null, null, null, null));
        }

        // BRIDGE_MODE
        if (bridgeModeSupplier.getAsBoolean()) {
            m.put("remoteControlAtStartup", new SettingConfig("global", "boolean",
                "Enable Remote Control for all sessions (true | false | default)",
                null, null, null, null, null, null,
                remoteControlAtStartupSupplier != null ? remoteControlAtStartupSupplier.get() : null));
        }

        // KAIROS / KAIROS_PUSH_NOTIFICATION
        if (kairosSupplier.getAsBoolean() || kairosPushSupplier.getAsBoolean()) {
            m.put("taskCompleteNotifEnabled", new SettingConfig("global", "boolean",
                "Push to your mobile device when idle after Claude finishes (requires Remote Control)",
                null, null, null, null, null, null, null));
            m.put("inputNeededNotifEnabled", new SettingConfig("global", "boolean",
                "Push to your mobile device when a permission prompt or question is waiting (requires Remote Control)",
                null, null, null, null, null, null, null));
            m.put("agentPushNotifEnabled", new SettingConfig("global", "boolean",
                "Allow Claude to push to your mobile device when it deems it appropriate (requires Remote Control)",
                null, null, null, null, null, null, null));
        }
        return m;
    }
}
