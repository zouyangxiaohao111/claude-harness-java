package com.nexusai.application.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ConfigTool 核心契约 (get/set settings) · 对齐 CC tools/ConfigTool/ConfigTool.ts:111-411.
 *
 * <p>L1 语义: get / set Claude Code 配置项.
 *            - Input: setting (path, e.g. "permissions.defaultMode") + optional value.
 *            - Operation:
 *              (1) isSupported → false → return Error "Unknown setting"
 *              (2) value == undefined → GET (lookup by path)
 *              (3) value 给定 → coerce boolean → check options → validateOnWrite → write
 *                  (global source → saveGlobalConfig; userSettings → updateSettingsForSource).
 *            - Voice / remoteControlAtStartup 副作用在主类外处理, 本类只覆盖核心读写契约.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: Input record(setting, value); Output record(success, operation, setting,
 *       value, previousValue, newValue, error); Operation enum GET/SET;</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — GET (path lookup) → success=true, value=current;
 *       SET (coerce + validate + write) → success=true, previousValue/newValue;
 *       unsupported → success=false, error="Unknown setting: ..."</li>
 *   <li><b>A3</b>: 状态 — READY (open path) / UNSUPPORTED (unknown setting);
 *       数据不变式 — write 后 read 必返回 newValue (round-trip).</li>
 *   <li><b>A4</b>: setting=null/blank → UNSUPPORTED; value="true"/"false" string → coerce boolean;
 *       options 不包含 → error="Invalid value"; boolean non-coercible → error=require true/false.</li>
 *   <li><b>A5</b>: 真实场景 — 设置 "permissions.defaultMode"="plan" → next GET 返回 "plan";
 *       设置 "model"="claude-sonnet-4-6" → next GET 返回该值; path 多层 (defaultMode 在 permissions
 *       子对象下) → buildNestedObject 嵌套写入.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC buildTool({...}) → Java record Input/Output + 静态 call() 方法;
 *                    TS setAppState / saveGlobalConfig → 注入式 BiConsumer (上层 wiring);
 *                    validateOnWrite async → 同步 validateFn (本类支持调用方包装 async);
 *                    zod schema → Java record + 静态校验.
 */
public final class ConfigTool {

    private static final Logger log = LoggerFactory.getLogger(ConfigTool.class);

    public enum Operation { GET, SET }

    /** CC input schema. */
    public record Input(String setting, Object value) {
        public Input {
            setting = setting == null ? "" : setting.trim();
        }
        public boolean isGet() { return value == null; }
    }

    /** CC output schema. */
    public record Output(
        boolean success,
        Operation operation,
        String setting,
        Object value,
        Object previousValue,
        Object newValue,
        String error) {

        public static Output error(String setting, String errorMsg) {
            return new Output(false, null, setting, null, null, null, errorMsg);
        }
        public static Output getResult(String setting, Object value) {
            return new Output(true, Operation.GET, setting, value, null, null, null);
        }
        public static Output setResult(String setting, Object previousValue, Object newValue) {
            return new Output(true, Operation.SET, setting, newValue, previousValue, newValue, null);
        }
    }

    /** CC supportedSettings — 单个 setting 的元数据. */
    public record SettingDef(
        String source,            // "global" 或 "settings"
        List<String> path,        // ["permissions", "defaultMode"] 等
        String type,              // "boolean" / "string" / "number" / "enum"
        List<String> options,     // nullable — 仅 enum 校验
        java.util.function.Predicate<Object> coerce,  // nullable — boolean string coerce
        java.util.function.Function<Object, ValidationResult> validateOnWrite,  // nullable — CC validateOnWrite (同步桥)
        java.util.function.Function<Object, Object> formatOnRead                // nullable — CC formatOnRead (GET 展示)
    ) {}

    public record ValidationResult(boolean valid, String error) {
        public static ValidationResult ok() { return new ValidationResult(true, null); }
        public static ValidationResult fail(String error) { return new ValidationResult(false, error); }
    }

    /** 注册表注入 — 调用方提供 supportedSettings lookup + 数据读写. */
    private final Function<String, SettingDef> settingLookup;
    private final Function<String, Object> globalReader;
    private final BiConsumer<String, Object> globalWriter;
    private final Function<List<String>, Object> settingsReader;
    private final BiConsumer<List<String>, Object> settingsWriter;
    private final BiConsumer<String, String> appStateSync;

    public ConfigTool(
            Function<String, SettingDef> settingLookup,
            Function<String, Object> globalReader,
            BiConsumer<String, Object> globalWriter,
            Function<List<String>, Object> settingsReader,
            BiConsumer<List<String>, Object> settingsWriter,
            BiConsumer<String, String> appStateSync) {
        this.settingLookup = Objects.requireNonNull(settingLookup);
        this.globalReader = Objects.requireNonNull(globalReader);
        this.globalWriter = Objects.requireNonNull(globalWriter);
        this.settingsReader = Objects.requireNonNull(settingsReader);
        this.settingsWriter = Objects.requireNonNull(settingsWriter);
        this.appStateSync = appStateSync == null ? (k, v) -> {} : appStateSync;
    }

    /** CC ConfigTool.call — 主入口. */
    public Output call(Input input) {
        // 1. 支持性检查
        SettingDef def = settingLookup.apply(input.setting());
        if (def == null) {
            return Output.error(input.setting(), "Unknown setting: \"" + input.setting() + "\"");
        }

        // 2. GET — CC ConfigTool.ts:138-140 formatOnRead (e.g. model null → 'default')
        if (input.isGet()) {
            Object current = readValue(def);
            Object displayValue = def.formatOnRead() != null
                ? def.formatOnRead().apply(current)
                : current;
            return Output.getResult(input.setting(), displayValue);
        }

        // 3. SET — coerce + validate + write
        Object coerced = input.value();
        if ("boolean".equals(def.type())) {
            coerced = coerceBoolean(coerced);
            if (!(coerced instanceof Boolean)) {
                return new Output(false, Operation.SET, input.setting(), null, null, null,
                    input.setting() + " requires true or false.");
            }
        }
        // options 校验
        if (def.options() != null && !def.options().isEmpty()
                && !def.options().contains(String.valueOf(coerced))) {
            return new Output(false, Operation.SET, input.setting(), null, null, null,
                "Invalid value \"" + input.value() + "\". Options: " + String.join(", ", def.options()));
        }
        // validateOnWrite
        if (def.validateOnWrite() != null) {
            ValidationResult vr = def.validateOnWrite().apply(coerced);
            if (!vr.valid()) {
                return new Output(false, Operation.SET, input.setting(), null, null, null, vr.error());
            }
        }
        // read previous
        Object previous = readValue(def);
        // write
        try {
            if ("global".equals(def.source())) {
                if (def.path().isEmpty()) {
                    return new Output(false, Operation.SET, input.setting(), null, null, null,
                        "Invalid setting path");
                }
                globalWriter.accept(def.path().get(0), coerced);
            } else {
                settingsWriter.accept(def.path(), coerced);
            }
        } catch (Exception ex) {
            log.warn("ConfigTool write failed for {}: {}", input.setting(), ex.getMessage());
            return new Output(false, Operation.SET, input.setting(), null, previous, null,
                ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
        // sync appState (best-effort)
        if (def.path() != null && !def.path().isEmpty()) {
            appStateSync.accept(input.setting(), String.valueOf(coerced));
        }
        return Output.setResult(input.setting(), previous, coerced);
    }

    /** CC getValue — 按 source + path 读取. */
    public Object readValue(SettingDef def) {
        if ("global".equals(def.source())) {
            if (def.path().isEmpty()) return null;
            return globalReader.apply(def.path().get(0));
        }
        return settingsReader.apply(def.path());
    }

    /** 静态辅助 — boolean string coerce. CC ConfigTool.ts:185-201. */
    public static Object coerceBoolean(Object value) {
        if (value instanceof Boolean) return value;
        if (value instanceof String s) {
            String lower = s.toLowerCase().trim();
            if ("true".equals(lower)) return Boolean.TRUE;
            if ("false".equals(lower)) return Boolean.FALSE;
        }
        return value;
    }

    /** CC buildNestedObject — path → 嵌套对象. */
    public static Map<String, Object> buildNestedObject(List<String> path, Object value) {
        if (path == null || path.isEmpty()) return Map.of();
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < path.size() - 1; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put(path.get(i), next);
            cursor = next;
        }
        cursor.put(path.get(path.size() - 1), value);
        return root;
    }
}