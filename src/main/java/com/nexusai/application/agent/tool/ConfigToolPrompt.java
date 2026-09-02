package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.settings.SupportedSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * ConfigTool prompt 生成器 · 对齐 CC tools/ConfigTool/prompt.ts generatePrompt.
 *
 * <p>L1 语义: 遍历 SUPPORTED_SETTINGS registry → 拼 settings 列表 (global/project 分类) +
 *            动态 model options 段.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `generate(SupportedSettingsRegistry, ModelOptionsProvider, voiceEnabled) → String` 签名</li>
 *   <li><b>A2 Golden Trace</b>: 3 settings (2 global + 1 project) → 含 "Global Settings" + "Project Settings" 段; model 段在最后</li>
 *   <li><b>A3</b>: skip 'model' (单独 section); skip voiceEnabled when !voiceEnabled; boolean type 显示 'true/false'</li>
 *   <li><b>A4</b>: settings 含 options 时显示 "opt1", "opt2" 列表</li>
 *   <li><b>A5</b>: 真实场景 — theme: "dark", "light" + verbose: true/false + model: 动态 options</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Supplier 注入 registry + model options (CC getOptionsForSetting/getModelOptions);
 *                    LinkedHashMap 保插入顺序 (CC Object.entries).
 *
 * <h2>[R32-b7a-2 Phase 4] 实例化重构</h2>
 * <ul>
 *   <li>新增 {@link #ConfigToolPrompt(SupportedSettings, Supplier)} 公共构造器, 接受
 *       {@link SupportedSettings} bean 与 model options supplier.</li>
 *   <li>新增实例方法 {@link #renderFull(boolean)} 与 {@link #renderDescription()}.</li>
 *   <li><b>保留</b>原 static {@link #generate} / {@link #defaultRegistry()} / {@link #defaultModelOptions()}
 *       以兼容现有测试 (CC {@code SupportedSettingsTest} / {@code ConfigToolPromptTest}).</li>
 *   <li>新增 {@link #renderSettingEntries()} 实例方法,从 {@link SupportedSettings} registry
 *       动态派生 SettingEntry 列表,避免重复 registry.</li>
 * </ul>
 */
public class ConfigToolPrompt {

    public static final String DESCRIPTION = "Get or set Claude Code configuration settings.";

    /** 简化版 setting entry. */
    public record SettingEntry(
        String key,
        String description,
        String type,       // "boolean" | "string" | "enum"
        List<String> options,  // 可选,enum 类型用
        String source      // "global" | "project"
    ) {}

    /** model option. */
    public record ModelOption(String value, String description, String descriptionForModel) {}

    /**
     * [R32-b7a-2 Phase 4] 实例字段 — 支持 Spring 注入.
     * Phase 1/2 时均为 null,Phase 4 由 ConfigToolAutoConfiguration 注入.
     */
    private final SupportedSettings supportedSettings;
    private final Supplier<List<ModelOption>> modelOptionsSupplier;

    /** voice-enabled flag (CC isVoiceGrowthBookEnabled()) — 决定 voiceEnabled setting 是否出现在 prompt. */
    private final Supplier<Boolean> voiceEnabledSupplier;

    /** 默认无参构造器 — 保留向后兼容 (Phase 1 + tests use static methods only). */
    public ConfigToolPrompt() {
        this(null, null, null);
    }

    /**
     * [Phase 4] 全参构造器 — Spring 注入.
     *
     * @param supportedSettings setting registry (Phase 2 bean);null 时降级到 defaultRegistry().
     * @param modelOptionsSupplier model options supplier;null 时降级到 defaultModelOptions().
     * @param voiceEnabledSupplier 语音 feature flag supplier;null 时视作 false (不出现在 prompt).
     */
    public ConfigToolPrompt(SupportedSettings supportedSettings,
                            Supplier<List<ModelOption>> modelOptionsSupplier,
                            Supplier<Boolean> voiceEnabledSupplier) {
        this.supportedSettings = supportedSettings;
        this.modelOptionsSupplier = modelOptionsSupplier;
        this.voiceEnabledSupplier = voiceEnabledSupplier == null ? () -> false : voiceEnabledSupplier;
    }

    /** [Phase 4] 简化构造器 — 默认 voice false. */
    public ConfigToolPrompt(SupportedSettings supportedSettings,
                            Supplier<List<ModelOption>> modelOptionsSupplier) {
        this(supportedSettings, modelOptionsSupplier, null);
    }

    /**
     * [Phase 4] 实例方法 — 渲染完整 prompt.
     *
     * @param voiceEnabled 是否启用 voice mode;true → 包含 voiceEnabled setting
     * @return 完整 prompt 字符串
     */
    public String renderFull(boolean voiceEnabled) {
        return generate(this::renderSettingEntries, this::renderModelOptions, voiceEnabled);
    }

    /** [Phase 4] 实例方法 — 渲染简短描述 (供 Tool.description 使用). */
    public String renderDescription() {
        return DESCRIPTION;
    }

    /** [Phase 4] 实例方法 — 从 SupportedSettings 派生 SettingEntry 列表. */
    public Map<String, SettingEntry> renderSettingEntries() {
        if (supportedSettings == null) {
            return defaultRegistry();
        }
        Map<String, SupportedSettings.SettingConfig> all = supportedSettings.getAll();
        Map<String, SettingEntry> out = new LinkedHashMap<>();
        for (var entry : all.entrySet()) {
            String key = entry.getKey();
            SupportedSettings.SettingConfig cfg = entry.getValue();
            List<String> options = supportedSettings.getOptionsForSetting(key);
            out.put(key, new SettingEntry(
                key,
                cfg.description(),
                cfg.type(),
                options,
                cfg.source()));
        }
        return out;
    }

    /** [Phase 4] 实例方法 — 渲染 model options 列表. */
    public List<ModelOption> renderModelOptions() {
        if (modelOptionsSupplier == null) {
            return defaultModelOptions();
        }
        try {
            List<ModelOption> opts = modelOptionsSupplier.get();
            return opts == null || opts.isEmpty() ? defaultModelOptions() : opts;
        } catch (Exception e) {
            return defaultModelOptions();
        }
    }

    // ── 兼容原 static API (Phase 1 tests / legacy callers) ─────────────────

    /**
     * 生成 ConfigTool 完整 prompt.
     *
     * @param settingsProvider 支持的 settings registry (key → SettingEntry)
     * @param modelOptionsProvider 动态 model option 列表 (Provider model section)
     * @param voiceEnabled VOICE_MODE feature flag (语音设置是否可见)
     * @return 完整 prompt 字符串
     */
    public static String generate(
            Supplier<Map<String, SettingEntry>> settingsProvider,
            Supplier<List<ModelOption>> modelOptionsProvider,
            boolean voiceEnabled) {
        Map<String, SettingEntry> settings = settingsProvider.get();
        java.util.List<String> globalLines = new java.util.ArrayList<>();
        java.util.List<String> projectLines = new java.util.ArrayList<>();

        for (var entry : settings.entrySet()) {
            String key = entry.getKey();
            SettingEntry cfg = entry.getValue();
            // skip model (单独 section)
            if ("model".equals(key)) continue;
            // skip voiceEnabled when kill-switch off (CC `!isVoiceGrowthBookEnabled()`)
            if (!voiceEnabled && "voiceEnabled".equals(key)) continue;

            StringBuilder line = new StringBuilder("- ").append(key);
            if (cfg.options() != null && !cfg.options().isEmpty()) {
                line.append(": ");
                line.append(cfg.options().stream()
                    .map(o -> "\"" + o + "\"").collect(Collectors.joining(", ")));
            } else if ("boolean".equals(cfg.type())) {
                line.append(": true/false");
            }
            line.append(" - ").append(cfg.description());
            if ("global".equals(cfg.source())) {
                globalLines.add(line.toString());
            } else {
                projectLines.add(line.toString());
            }
        }

        String modelSection = generateModelSection(modelOptionsProvider);

        return "Get or set Claude Code configuration settings.\n\n" +
            "  View or change Claude Code settings. Use when the user requests configuration changes, " +
            "asks about current settings, or when adjusting a setting would benefit them.\n\n" +
            "\n## Usage\n" +
            "- **Get current value:** Omit the \"value\" parameter\n" +
            "- **Set new value:** Include the \"value\" parameter\n\n" +
            "## Configurable settings list\n" +
            "The following settings are available for you to change:\n\n" +
            "### Global Settings (stored in ~/.nexusai.json)\n" +
            String.join("\n", globalLines) + "\n\n" +
            "### Project Settings (stored in settings.json)\n" +
            String.join("\n", projectLines) + "\n\n" +
            modelSection + "\n" +
            "## Examples\n" +
            "- Get theme: { \"setting\": \"theme\" }\n" +
            "- Set dark theme: { \"setting\": \"theme\", \"value\": \"dark\" }\n" +
            "- Enable vim mode: { \"setting\": \"editorMode\", \"value\": \"vim\" }\n" +
            "- Enable verbose: { \"setting\": \"verbose\", \"value\": true }\n" +
            "- Change model: { \"setting\": \"model\", \"value\": \"opus\" }\n" +
            "- Change permission mode: { \"setting\": \"permissions.defaultMode\", \"value\": \"plan\" }\n";
    }

    private static String generateModelSection(Supplier<List<ModelOption>> modelOptionsProvider) {
        try {
            List<ModelOption> options = modelOptionsProvider.get();
            if (options == null || options.isEmpty()) return "## Model\n- model - Override the default model (sonnet, opus, haiku, best, or full model ID)\n";
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (ModelOption o : options) {
                String value = o.value() == null ? "null/\"default\"" : "\"" + o.value() + "\"";
                String desc = o.descriptionForModel() != null ? o.descriptionForModel() : o.description();
                lines.add("  - " + value + ": " + desc);
            }
            return "## Model\n" +
                "- model - Override the default model. Available options:\n" +
                String.join("\n", lines) + "\n";
        } catch (Exception e) {
            return "## Model\n- model - Override the default model (sonnet, opus, haiku, best, or full model ID)\n";
        }
    }

    /** 测试/扩展 helper: 默认示例 registry. */
    public static Map<String, SettingEntry> defaultRegistry() {
        Map<String, SettingEntry> m = new LinkedHashMap<>();
        m.put("theme", new SettingEntry("theme", "UI theme", "enum",
            List.of("dark", "light"), "global"));
        m.put("verbose", new SettingEntry("verbose", "Enable verbose logging", "boolean",
            null, "global"));
        m.put("voiceEnabled", new SettingEntry("voiceEnabled", "Enable voice mode", "boolean",
            null, "global"));
        m.put("permissions.defaultMode", new SettingEntry("permissions.defaultMode",
            "Default permission mode", "enum", List.of("plan", "auto", "default"), "project"));
        return m;
    }

    /** 测试 helper: 默认 model options. */
    public static List<ModelOption> defaultModelOptions() {
        return List.of(
            new ModelOption("opus", "Claude Opus 4.6", null),
            new ModelOption("sonnet", "Claude Sonnet 4.6", null),
            new ModelOption("haiku", "Claude Haiku 4.5", null));
    }
}