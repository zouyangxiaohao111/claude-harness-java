package com.nexusai.application.agent.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in plugin 注册表 · 对齐 CC plugins/builtinPlugins.ts.
 *
 * <p>L1 语义: CLI 内置 plugins 管理 (与 bundled skills 不同 — builtin 可 enable/disable).
 *            - registerBuiltinPlugin: 注册 (key=name).
 *            - isBuiltinPluginId: id 以 `@builtin` 结尾.
 *            - getBuiltinPluginDefinition: 按 name 查.
 *            - getBuiltinPlugins: 按 user settings (enabledPlugins[id]) 切分 enabled/disabled;
 *              isAvailable()=false 跳过.
 *            - getBuiltinPluginSkillCommands: 从 enabled plugins 提取 skills 为 Command.
 *            - clearBuiltinPlugins: 测试用.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 6 公开 API;BUILTIN_MARKETPLACE_NAME='builtin';
 *       LoadedPlugin 6 字段 (name/manifest/path/source/repository/enabled/isBuiltin/hooksConfig/mcpServers);
 *       enabled 优先级: user pref > plugin default > true.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — register 2 plugins → getBuiltinPlugins →
 *       split into enabled/disabled based on settings + isAvailable;
 *       getBuiltinPluginSkillCommands → 返回 enabled plugins 的 skills 数组.</li>
 *   <li><b>A3</b>: 状态: REGISTERED → AVAILABLE (isAvailable=true) → ENABLED/DISABLED;
 *       isAvailable=false → 完全跳过.</li>
 *   <li><b>A4</b>: userSetting 未定义 → defaultEnabled (默认 true);
 *       isAvailable() throws → caught by truthy evaluation;
 *       clearBuiltinPlugins → 全清.</li>
 *   <li><b>A5</b>: 真实场景 — register 2 个 plugins (1 enabled by default + 1 disabled) →
 *       user 启用 disabled → getBuiltinPlugins 切分正确.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `Map<string, BuiltinPluginDefinition>` → Java LinkedHashMap;
 *                    TS `getSettings_DEPRECATED()` → 注入式 Supplier (testable);
 *                    TS `(definition.isAvailable ?? (() => true))` → 注入式 BooleanSupplier;
 *                    TS Command type → Java Command record (skill commands).
 */
public final class BuiltinPluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(BuiltinPluginRegistry.class);
    public static final String BUILTIN_MARKETPLACE_NAME = "builtin";

    private final Map<String, BuiltinPluginDefinition> plugins = new LinkedHashMap<>();
    private final Supplier<Settings> settingsSupplier;

    public BuiltinPluginRegistry(Supplier<Settings> settingsSupplier) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier);
    }

    /** Settings 最小子集. */
    public record Settings(Map<String, Boolean> enabledPlugins) {
        public static final Settings EMPTY = new Settings(Map.of());
    }

    /** BuiltinPluginDefinition. */
    public record BuiltinPluginDefinition(
        String name,
        String description,
        String version,
        Boolean defaultEnabled,
        List<String> allowedTools,
        String argumentHint,
        String whenToUse,
        String model,
        Boolean disableModelInvocation,
        Boolean userInvocable,
        List<PluginHook> hooks,
        List<String> mcpServers,
        List<SkillDefinition> skills,
        BooleanSupplier isAvailable          // 注入式
    ) {}

    public record PluginHook(String event, String command) {}

    /**
     * CC original: BundledSkillDefinition（builtinPlugins.ts:132-159 skillDefinitionToCommand 消费）。
     *
     * <p>P2-15 字段契约补齐：CC skillDefinitionToCommand 读 definition 的
     * model（:144）/ hooks（:157）/ context（:158）/ agent（:159）/ isEnabled（:160）
     * 五字段 + hasUserSpecifiedDescription:true（:137）/ isHidden:!(userInvocable??true)（:161）/
     * progressMessage:'running'（:162）。Java record 缺省 = CC undefined（skillToCommand 统一落位缺省）。
     */
    public record SkillDefinition(String name, String description, List<String> allowedTools,
                                    String argumentHint, String whenToUse, Boolean disableModelInvocation,
                                    Boolean userInvocable, String getPrompt,
                                    String model, String hooks, String context, String agent,
                                    java.util.function.BooleanSupplier isEnabled) {
        /** 8 参兼容构造器 · 新增字段后保留既有调用方（model/hooks/context/agent/isEnabled 缺省 = null）。 */
        public SkillDefinition(String name, String description, List<String> allowedTools,
                               String argumentHint, String whenToUse, Boolean disableModelInvocation,
                               Boolean userInvocable, String getPrompt) {
            this(name, description, allowedTools, argumentHint, whenToUse, disableModelInvocation,
                userInvocable, getPrompt, null, null, null, null, null);
        }
    }

    /** LoadedPlugin. */
    public record LoadedPlugin(
        String name,
        Manifest manifest,
        String path,                          // sentinel: 'builtin'
        String source,                         // name@builtin
        String repository,                     // name@builtin
        boolean enabled,
        boolean isBuiltin,
        List<PluginHook> hooksConfig,
        List<String> mcpServers
    ) {}

    public record Manifest(String name, String description, String version) {}

    /** CC registerBuiltinPlugin. */
    public void registerBuiltinPlugin(BuiltinPluginDefinition definition) {
        plugins.put(definition.name(), definition);
    }

    /** CC isBuiltinPluginId — id 以 @builtin 结尾. */
    public static boolean isBuiltinPluginId(String pluginId) {
        return pluginId != null && pluginId.endsWith("@" + BUILTIN_MARKETPLACE_NAME);
    }

    /** CC getBuiltinPluginDefinition. */
    public BuiltinPluginDefinition getBuiltinPluginDefinition(String name) {
        return plugins.get(name);
    }

    /** CC getBuiltinPlugins — split enabled/disabled. */
    public BuiltinPlugins getBuiltinPlugins() {
        Settings settings = settingsSupplier.get();
        Map<String, Boolean> enabledPluginsMap = settings.enabledPlugins() != null
            ? settings.enabledPlugins() : Map.of();
        List<LoadedPlugin> enabled = new ArrayList<>();
        List<LoadedPlugin> disabled = new ArrayList<>();

        for (Map.Entry<String, BuiltinPluginDefinition> e : plugins.entrySet()) {
            BuiltinPluginDefinition def = e.getValue();
            if (def.isAvailable() != null && !def.isAvailable().getAsBoolean()) {
                continue;
            }
            String pluginId = e.getKey() + "@" + BUILTIN_MARKETPLACE_NAME;
            Boolean userSetting = enabledPluginsMap.get(pluginId);
            boolean isEnabled = userSetting != null
                ? userSetting
                : (def.defaultEnabled() == null || def.defaultEnabled());
            LoadedPlugin lp = new LoadedPlugin(
                e.getKey(),
                new Manifest(e.getKey(), def.description(), def.version()),
                BUILTIN_MARKETPLACE_NAME,
                pluginId, pluginId,
                isEnabled,
                true,
                def.hooks(),
                def.mcpServers()
            );
            if (isEnabled) enabled.add(lp); else disabled.add(lp);
        }
        return new BuiltinPlugins(enabled, disabled);
    }

    /** CC getBuiltinPluginSkillCommands — enabled plugins 的 skills → Command[]. */
    public List<Command> getBuiltinPluginSkillCommands() {
        BuiltinPlugins bp = getBuiltinPlugins();
        List<Command> commands = new ArrayList<>();
        for (LoadedPlugin lp : bp.enabled()) {
            BuiltinPluginDefinition def = plugins.get(lp.name());
            if (def == null || def.skills() == null) continue;
            for (SkillDefinition skill : def.skills()) {
                commands.add(skillToCommand(skill));
            }
        }
        return commands;
    }

    /** CC clearBuiltinPlugins — test helper. */
    public void clearBuiltinPlugins() {
        plugins.clear();
    }

    /** Plugin list result. */
    public record BuiltinPlugins(List<LoadedPlugin> enabled, List<LoadedPlugin> disabled) {}

    /**
     * Skill → Command · 对齐 CC skillDefinitionToCommand（builtinPlugins.ts:132-159）全字段。
     *
     * <p>P2-15 字段契约补齐（DRF-PC-4）：CC 逐字段
     * hasUserSpecifiedDescription:true（:137）/ model（:144）/ disableModelInvocation??false（:145）/
     * userInvocable??true（:146）/ isEnabled??(()=>true)（:160）/ isHidden:!(userInvocable??true)（:161）/
     * progressMessage:'running'（:162）/ hooks（:157）/ context（:158）/ agent（:159）。
     * Java record 缺省 = CC undefined（model/hooks/context/agent 缺省 null，isEnabled 缺省恒 true）。
     */
    private Command skillToCommand(SkillDefinition def) {
        boolean userInvocable = def.userInvocable() == null || def.userInvocable();
        return new Command(
            "prompt",
            def.name(),
            def.description(),
            true,                                          // hasUserSpecifiedDescription（CC :137）
            def.allowedTools() != null ? def.allowedTools() : List.of(),  // :138 ?? []
            def.argumentHint(),
            def.whenToUse(),
            def.model(),                                   // :144
            def.disableModelInvocation() != null && def.disableModelInvocation(),  // :145 ?? false
            userInvocable,                                 // :146 ?? true
            def.hooks(),                                   // :157
            def.context(),                                 // :158
            def.agent(),                                   // :159
            def.isEnabled() != null ? def.isEnabled() : () -> true,  // :160 ?? (() => true)
            !userInvocable,                                // :161 isHidden: !(userInvocable ?? true)
            "running",                                     // :162 progressMessage: 'running'
            () -> def.getPrompt()  // injected getter
        );
    }

    /** CC Command (skill variant) · 对齐 builtinPlugins.ts:132-159 skillDefinitionToCommand 全字段。 */
    public record Command(String type, String name, String description,
                          boolean hasUserSpecifiedDescription, List<String> allowedTools,
                          String argumentHint, String whenToUse, String model,
                          boolean disableModelInvocation, boolean userInvocable,
                          String hooks, String context, String agent,
                          java.util.function.BooleanSupplier isEnabled,
                          boolean isHidden, String progressMessage,
                          java.util.function.Supplier<String> getPrompt) {}
}
