package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.settings.SupportedSettings;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ConfigTool;
import com.nexusai.application.agent.tool.ConfigToolNames;
import com.nexusai.application.agent.tool.ConfigToolPrompt;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * [R32-b7a-2 Phase 4] ConfigTool 工具适配器 · 对齐 CC {@code tools/ConfigTool/ConfigTool.ts}.
 *
 * <p>本类是 ConfigTool 的 {@link Tool} 接口适配器,负责把 LLM 的 JSON tool call 转换到
 * 核心领域契约 {@link com.nexusai.application.agent.tool.ConfigTool}.
 *
 * <h2>Phase 边界</h2>
 * <ul>
 *   <li><b>Phase 1</b>: 实现 Tool 接口骨架 (schema/permission/describe). execute() 在依赖未注入
 *       时返回 {@link ToolResult#error} — 诚实边界.</li>
 *   <li><b>Phase 2</b>: SupportedSettings 注册为 Spring bean, 注入构造器.</li>
 *   <li><b>Phase 3</b>: ConfigStorage interface + FileConfigStorage impl 接入.</li>
 *   <li><b>Phase 4 (本 PR)</b>: ConfigToolPrompt 改为可注入实例 + ConfigToolImpl 接线 +
 *       即时运行时同步 (autoCompactEnabled / verbose / permissions.defaultMode).</li>
 *   <li><b>Phase 5</b>: 条件 {@code @Bean} 注册 (ant + enabled).</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>不复刻 registry</b>: setting 查询/校验/选项全部委托给 {@link SupportedSettings} —
 *       避免 SettingDef / SettingConfig / SettingEntry 三套模型并存.</li>
 *   <li><b>Tool 接口完整</b>: schema/permission/execute/error 全部按 CC 协议覆盖.</li>
 *   <li><b>Fail loud</b>: 依赖未注入时返回明确错误,避免静默 no-op 误导 LLM.</li>
 * </ul>
 *
 * @see com.nexusai.application.agent.tool.ConfigTool 核心领域契约 (CC:111-411)
 * @see com.nexusai.application.agent.tool.ConfigToolPrompt 提示词生成器 (CC: prompt.ts)
 * @see com.nexusai.application.agent.settings.SupportedSettings setting registry (CC: supportedSettings.ts)
 */
public class ConfigToolImpl implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ConfigToolImpl.class);

    /** 最大结果字符数 — CC ConfigTool.ts:466. 高阈值便于展示完整 model options. */
    private static final long MAX_RESULT_CHARS = 100_000L;

    /**
     * Phase 2/3/4 注入的依赖. 字段为 nullable 以兼容阶段性 Spring 装配.
     */
    private SupportedSettings supportedSettings;
    private ConfigStorage configStorage;
    private ConfigToolPrompt configToolPrompt;

    /** Phase 4 构造的核心委托 (CC ConfigTool contract 实例). */
    private ConfigTool coreConfigTool;

    /**
     * Phase 4 可选运行时同步 sink — AutoCompactor 注入后, 写入 {@code autoCompactEnabled}
     * 立即影响自动压缩判定 (而非仅写文件). verbose/model/permissions 等类似.
     * GR-3: 旧压缩编排器已删除，autoCompactEnabled 运行时同步归 AutoCompactor
     * （CC getGlobalConfig().autoCompactEnabled，autoCompact.ts:156-157）。
     */
    private AutoCompactor autoCompactor;

    /**
     * Phase 4 可选权限 mode setter — 写入 {@code permissions.defaultMode} 立即影响后续权限层.
     * 类型为 {@link BiConsumer}{@code <PermissionMode, String>} — 第一个参数目标 mode,
     * 第二个为 source 标签 (e.g. "config-tool");null 时降级到 log only.
     */
    private BiConsumer<PermissionMode, String> permissionModeSync;

    /**
     * [IMP-T G15] AnalyticsTracker 遥测统一通道 · 对齐 CC logEvent('tengu_config_tool_changed')
     * （ConfigTool.ts:383-387）。
     *
     * <p>null → no-op（未注入/测试场景不破坏既有调用）。
     */
    @Autowired(required = false)
    private AnalyticsTracker analyticsTracker;

    /** [IMP-T G15] 遥测通道注入（非 Spring 场景 / 测试）。 */
    public void setAnalyticsTracker(AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
    }

    public ConfigToolImpl() {
        // 默认构造器 — Phase 5 条件 @Bean 调用时通过 setter 注入.
        log.info("[ConfigToolImpl] 实例化 (Phase 4 skeleton; 待 setter 注入)");
    }

    /**
     * Phase 4 全参构造器 — Phase 5 {@code ConfigToolAutoConfiguration} 调用.
     */
    public ConfigToolImpl(SupportedSettings supportedSettings,
                          ConfigStorage configStorage,
                          ConfigToolPrompt configToolPrompt) {
        this.supportedSettings = supportedSettings;
        this.configStorage = configStorage;
        this.configToolPrompt = configToolPrompt;
        rebuildCore();
        log.info("[ConfigToolImpl] 实例化 (Phase 4 接线: settings={}, storage={}, prompt={})",
            supportedSettings != null, configStorage != null, configToolPrompt != null);
    }

    // ── 依赖注入 setter (供 Phase 2/3/4 + Phase 5 调用) ─────────────────────

    /** Phase 2: SupportedSettings bean 注入. */
    public void setSupportedSettings(SupportedSettings supportedSettings) {
        this.supportedSettings = supportedSettings;
        rebuildCore();
    }

    /** Phase 3: ConfigStorage bean 注入. */
    public void setConfigStorage(ConfigStorage configStorage) {
        this.configStorage = configStorage;
        rebuildCore();
    }

    /** Phase 4: ConfigToolPrompt 实例注入 (替代 static 调用). */
    public void setConfigToolPrompt(ConfigToolPrompt configToolPrompt) {
        this.configToolPrompt = configToolPrompt;
    }

    /**
     * Phase 4: AutoCompactor 注入 — 触发 {@code autoCompactEnabled} 写入时即时同步.
     * Phase 5 / follow-up PR 可注入;required=false 兼容早期 boot.
     */
    @Autowired(required = false)
    public void setAutoCompactor(AutoCompactor autoCompactor) {
        this.autoCompactor = autoCompactor;
        rebuildCore();
        if (autoCompactor != null) {
            log.info("[ConfigToolImpl] AutoCompactor 已注入 — autoCompactEnabled 写入将即时同步");
        }
    }

    @Autowired(required = false)
    public void setPermissionModeSync(BiConsumer<PermissionMode, String> permissionModeSync) {
        this.permissionModeSync = permissionModeSync;
    }

    /** 重建内部 ConfigTool 委托实例. */
    private void rebuildCore() {
        if (supportedSettings == null || configStorage == null) {
            this.coreConfigTool = null;
            return;
        }
        // Phase 4 接线: 构造 ConfigTool (contract), 委托 reader/writer + 运行时同步.
        BiConsumer<String, String> appStateSync = this::applyRuntimeSync;
        // SettingConfig → SettingDef 适配 (contract 类用内部 record;registry 用 SupportedSettings.SettingConfig).
        java.util.function.Function<String, ConfigTool.SettingDef> settingLookup = key -> {
            SupportedSettings.SettingConfig cfg = supportedSettings.getConfig(key);
            if (cfg == null) return null;
            List<String> path = cfg.path() != null
                ? cfg.path()
                : List.of(key.split("\\."));
            List<String> options = cfg.options() != null
                ? new java.util.ArrayList<>(cfg.options())
                : null;
            // [IMP-H4] CC validateOnWrite (model → validateModel API) 接线:
            //   SupportedSettings.validateOnWriteFn 返回 CompletableFuture (异步);
            //   ConfigTool.SettingDef.validateOnWrite 为同步 Function — 用 join() 桥.
            //   (默认 defaultModelValidator 返回已完成 future, join 立即返回; 无阻塞.)
            java.util.function.Function<Object, ConfigTool.ValidationResult> validateOnWrite = null;
            java.util.function.Function<Object, java.util.concurrent.CompletableFuture<SupportedSettings.ValidationResult>> vw =
                supportedSettings.validateOnWriteFn(key);
            if (vw != null) {
                validateOnWrite = v -> {
                    SupportedSettings.ValidationResult r = vw.apply(v).join();
                    return new ConfigTool.ValidationResult(r.valid(), r.error());
                };
            }
            // [IMP-H4] CC formatOnRead (GET 展示) 接线: model null→'default' / remoteControlAtStartup 动态.
            java.util.function.Function<Object, Object> formatOnRead = supportedSettings.formatOnReadFn(key);
            if (log.isDebugEnabled() && (validateOnWrite != null || formatOnRead != null)) {
                log.debug("[ConfigToolImpl] rebuildCore: setting={} validateOnWrite={} formatOnRead={}",
                    key, validateOnWrite != null, formatOnRead != null);
            }
            return new ConfigTool.SettingDef(
                cfg.source(),
                path,
                cfg.type(),
                options,
                null,  // boolean coerce 不需要 — ConfigTool.coerceBoolean 静态方法已处理
                validateOnWrite,
                formatOnRead
            );
        };
        this.coreConfigTool = new ConfigTool(
            settingLookup,
            configStorage::readGlobal,
            configStorage::writeGlobal,
            configStorage::readSettings,
            configStorage::writeSettings,
            appStateSync);
        log.info("[ConfigToolImpl] rebuildCore: 核心 ConfigTool 已构造 (runtime sync={})",
            autoCompactor != null || permissionModeSync != null);
    }

    /**
     * 即时运行时同步 · 对齐 CC {@code setAppState} 调用.
     *
     * <p>仅对存在 runtime 注入的 setting 触发即时同步;未注入时仅 log best-effort.
     * 优先级: 写文件 (ConfigStorage 必触发) + 即时通知 (有 sink 时).
     *
     * <ul>
     *   <li>{@code autoCompactEnabled} → AutoCompactor.setAutoCompactEnabled (若注入)</li>
     *   <li>{@code permissions.defaultMode} → permissionModeSync (若注入)</li>
     *   <li>{@code verbose} → logback level 调整 (留 follow-up;当前仅 log)</li>
     *   <li>{@code model} → [R4 重做] log only (持久化由 coreConfigTool.call() 写入 settings,
     *       读路径走 {@code LlmAgentLoop.getModelForCall()} 优先级链)</li>
     * </ul>
     */
    private void applyRuntimeSync(String setting, String value) {
        try {
            switch (setting) {
                case "autoCompactEnabled" -> {
                    if (autoCompactor != null) {
                        boolean enabled = Boolean.parseBoolean(value);
                        autoCompactor.setAutoCompactEnabled(enabled);
                        log.info("[ConfigToolImpl] 运行时同步: autoCompactEnabled={}", enabled);
                    } else {
                        log.info("[ConfigToolImpl] 运行时同步 (log only): autoCompactEnabled={}", value);
                    }
                }
                case "permissions.defaultMode" -> {
                    if (permissionModeSync != null) {
                        PermissionMode mode = parsePermissionMode(value);
                        permissionModeSync.accept(mode, "config-tool");
                        log.info("[ConfigToolImpl] 运行时同步: permissions.defaultMode={}", mode);
                    } else {
                        log.info("[ConfigToolImpl] 运行时同步 (log only): permissions.defaultMode={}", value);
                    }
                }
                case "verbose" ->
                    log.info("[ConfigToolImpl] 运行时同步 (log only): verbose={}", value);
                case "model" ->
                    // [R32-b7b-2 R4 重做 + P2-2 修复] model 不再走 in-memory field.
                    // 持久化已由 coreConfigTool.call() (ConfigTool.java:165 settingsWriter.accept)
                    // 触发 — 此处仅记录数据流日志, 严格对齐 CC: 模型变更通过 settings 持久层.
                    // 读路径由 LlmAgentLoop.getModelForCall() 优先级链 (env / settings / fallback) 接管.
                    // [P2-2] 不回显 model 原值 (避免 outbound log 包含 model name).
                    log.info("[ConfigToolImpl] model 设置已持久化 (CC 对齐 — 读路径走 getModelForCall()), present={}",
                        value != null && !value.isBlank());
                default -> { /* 其他 setting 不触发即时同步 */ }
            }
        } catch (Exception ex) {
            log.warn("[ConfigToolImpl] 运行时同步失败: setting={}, value={}, err={}",
                setting, value, ex.getMessage());
        }
    }

    /**
     * [IMP-T G15] tengu_config_tool_changed 遥测 · 对齐 CC ConfigTool.ts:383-387
     * {@code logEvent('tengu_config_tool_changed', {setting, value: String(finalValue)})}。
     *
     * <p>{@code setting} / {@code value} 均经 {@link AnalyticsTracker#verified} 包装
     * （CC {@code AnalyticsMetadata_I_VERIFIED_THIS_IS_NOT_CODE_OR_FILEPATHS} 标记，ConfigTool.ts:385-386）。
     * <b>受控差异</b>：{@code model} setting 的 value 脱敏为 {@code <redacted>}（对齐既有
     * [R32-b7b-2 P2-2]「model 不回显原值」隐私决策，本类 :494/:510 同款）。
     *
     * @param setting  setting key（e.g. "model"）
     * @param newValue CC finalValue（coerced 值；null → "null"）
     */
    private void emitConfigToolChanged(String setting, Object newValue) {
        if (analyticsTracker == null) {
            return;
        }
        String valueStr = newValue == null ? "null" : String.valueOf(newValue);
        if ("model".equals(setting)) {
            valueStr = "<redacted>";
        }
        analyticsTracker.logEvent("tengu_config_tool_changed",
            Map.<String, Object>of(
                "setting", AnalyticsTracker.verified(setting),
                "value", AnalyticsTracker.verified(valueStr)));
        if (log.isDebugEnabled()) {
            log.debug("[ConfigToolImpl] [IMP-T G15] 遥测 tengu_config_tool_changed: setting={}",
                setting);
        }
    }

    /**
     * [G20④] AppState 写回 · 对齐 CC ConfigTool.ts:356-362 {@code config.appStateKey} → setAppState。
     *
     * <p>CC 语义（supportedSettings.ts:13-27 SyncableAppStateKey）：仅声明了 {@code appStateKey}
     * 的 setting（verbose / model / alwaysThinkingEnabled）在 SET 成功后把值同步到会话 AppState
     * （Java {@link AppState} 对应 key：verbose / mainLoopModel / thinkingEnabled），供前端/运行时
     * 立即读取。Java 经 {@code ctx.setAppState().accept(updater)} 写会话级 AppState（per-session 实例，
     * 多会话互不影响）；ctx 为 null（无会话上下文路径）→ 跳过。
     *
     * @param setting  setting key（e.g. "model"）
     * @param newValue CC finalValue（coerced 值）
     * @param ctx      工具调用上下文（可为 null）
     */
    private void syncAppStateToCtx(String setting, Object newValue, ToolUseContext ctx) {
        if (ctx == null || ctx.setAppState() == null || newValue == null) {
            return;
        }
        String appKey = null;
        try {
            if (supportedSettings != null) {
                SupportedSettings.SettingConfig cfg = supportedSettings.getConfig(setting);
                if (cfg != null) {
                    appKey = cfg.appStateKey();
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[ConfigToolImpl] appStateKey 读取失败: setting={} err={}", setting, e.toString());
            }
        }
        if (appKey == null || appKey.isBlank()) {
            return;
        }
        final String key = appKey;
        final Object value = newValue;
        ctx.setAppState().accept(prev -> {
            java.util.Map<String, Object> next = new java.util.LinkedHashMap<>(
                prev != null ? prev : java.util.Map.of());
            next.put(key, value);
            return next;
        });
        if (log.isInfoEnabled()) {
            log.info("[ConfigToolImpl] AppState 写回: setting={} appKey={}（G20④ CC "
                + "ConfigTool.ts:356-362 appStateKey 语义，会话级）", setting, key);
        }
    }

    /** 把字符串值解析为 PermissionMode. 未知值视为 DEFAULT. */
    private static PermissionMode parsePermissionMode(String value) {
        if (value == null) return PermissionMode.DEFAULT;
        // 支持 CC 风格 (acceptEdits / dontAsk / plan / default) 与 CC enum 风格
        // (accept_edits / dont_ask) 两种输入 — 兼容 LLM 历史 transcript.
        String normalized = value.replace("-", "_").replace(" ", "");
        try {
            return PermissionMode.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ex) {
            // 尝试把首字母小写后的形式作为 camelCase 解析 (acceptEdits → ACCEPTEDITS).
            // 通过在 camelCase 边界插入下划线恢复 SCREAMING_SNAKE_CASE.
            String camelSplit = normalized.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
            try {
                return PermissionMode.valueOf(camelSplit.toUpperCase());
            } catch (IllegalArgumentException ex2) {
                log.warn("[ConfigToolImpl] 未知 permission mode: {}, 降级 DEFAULT", value);
                return PermissionMode.DEFAULT;
            }
        }
    }

    // ── Tool 接口实现 ──────────────────────────────────────────────────────

    @Override
    public String name() {
        return ConfigToolNames.CONFIG_TOOL_NAME;
    }

    @Override
    public String description() {
        // Phase 4: 使用注入 prompt renderer;未注入时降级 static DESCRIPTION.
        return configToolPrompt != null
            ? configToolPrompt.renderDescription()
            : ConfigToolPrompt.DESCRIPTION;
    }

    @Override
    public String prompt() {
        // Phase 4 完整 prompt — 注入后渲染;未注入时返回 null (Tool 协议允许).
        if (configToolPrompt == null) return null;
        return configToolPrompt.renderFull(false);
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");
        ObjectNode setting = props.putObject("setting");
        setting.put("type", "string");
        setting.put("description",
            "Setting key (e.g. \"theme\", \"permissions.defaultMode\"). Required.");

        ObjectNode value = props.putObject("value");
        value.put("type", "string");
        value.put("description",
            "New value to set. Omit for GET (read current value).");

        // CC strict mode = true, additionalProperties 不允许.
        schema.put("additionalProperties", false);

        schema.putArray("required").add("setting");
        return schema;
    }

    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");
        props.putObject("success").put("type", "boolean");
        props.putObject("operation").putArray("enum").add("GET").add("SET");
        props.putObject("setting").put("type", "string");
        props.putObject("value").put("type", "string");
        props.putObject("previousValue").put("type", "string");
        props.putObject("newValue").put("type", "string");
        props.putObject("error").put("type", "string");

        return schema;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        // CC ConfigTool.ts:437 isConcurrencySafe=true (GET/SET 都声明安全).
        // Java 端保留 CC 行为;实际并发安全由 ConfigStorage 内部锁保证 (Phase 3).
        return true;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        // GET (无 value) → 只读;SET (有 value) → 写.
        return input == null || !input.has("value");
    }

    @Override
    public boolean shouldDefer(JsonNode input) {
        // CC ConfigTool.ts:446 shouldDefer=true — 等待挂起工具执行完后再应用.
        return true;
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_CHARS;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        boolean readOnly = isReadOnly(input);
        String setting = input != null && input.has("setting") ? input.get("setting").asText() : "";
        if (readOnly) {
            // GET 自动允许 (CC ConfigTool.ts:411 checkPermissions — GET allow).
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("ConfigTool GET auto-allow"),
                null, false, null, List.of());
        }
        // SET 必须 Ask 用户确认 (改变后续 LLM 行为,影响面大).
        String message = "ConfigTool 写入配置 " + (setting == null || setting.isBlank() ? "" : setting);
        return new PermissionResult.Ask(
            message,
            new PermissionDecisionReason.Other("ConfigTool SET requires confirmation"),
            List.of(),
            null,
            input,
            null, // [Session H P2-7] metadata: Map.of() → null (PermissionMetadata sealed interface, CC types/permissions.ts:167-169)
            false,null,
            List.of());
    }

    @Override
    public String renderToolUseMessage(JsonNode input) {
        if (input == null) return null;
        String setting = input.has("setting") ? input.get("setting").asText() : "";
        if (!input.has("value")) {
            return "读取配置 " + setting;
        }
        Object value = input.get("value");
        return "写入配置 " + setting + " = " + value;
    }

    @Override
    public String toAutoClassifierInput(JsonNode input) {
        if (input == null) return name();
        String setting = input.has("setting") ? input.get("setting").asText() : "";
        boolean readOnly = isReadOnly(input);
        return name() + " " + (readOnly ? "get " : "set ") + setting;
    }

    // ── execute 主入口 ─────────────────────────────────────────────────────

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        // 1. 校验 setting 必填
        if (input == null || !input.has("setting") || input.get("setting").asText().isBlank()) {
            log.warn("[ConfigToolImpl] execute: missing setting input");
            return ToolResult.error(call.id(), "missing required input: setting");
        }
        String setting = input.get("setting").asText().trim();

        // 2. 依赖未齐备时 fail loud (CLAUDE.md 规则 12).
        if (coreConfigTool == null) {
            log.warn("[ConfigToolImpl] execute called before Phase 2/3 接线完成: setting={}", setting);
            return ToolResult.error(call.id(),
                "ConfigTool 未完成接线 (需要 Phase 2/3 完成 SupportedSettings + ConfigStorage 注入). "
                    + "当前 setting=" + setting);
        }

        // 3. 构造 ConfigTool.Input 并委托核心契约.
        JsonNode valueNode = input.has("value") ? input.get("value") : null;
        // Convert JsonNode → Java object for ConfigTool (contract).
        Object javaValue = (valueNode == null || valueNode.isNull()) ? null : jsonNodeToJava(valueNode);
        ConfigTool.Input contractInput = new ConfigTool.Input(setting, javaValue);

        ConfigTool.Output out;
        try {
            out = coreConfigTool.call(contractInput);
        } catch (Exception ex) {
            log.error("[ConfigToolImpl] execute 失败: setting={}, err={}", setting, ex.getMessage());
            return ToolResult.error(call.id(), "execute failed: " + ex.getMessage());
        }

        // 4. 映射 Output → ToolResult (对齐 CC success/operation/setting/value 结构).
        if (!out.success()) {
            // [R32-b7b-2 P2-2 修复] model setting 不回显 value 原值 (避免 outbound log 包含 model name)
            if ("model".equals(setting)) {
                log.info("[ConfigToolImpl] {} {} → FAIL: {} (value 回显已脱敏)",
                    out.operation(), setting, out.error());
            } else {
                log.info("[ConfigToolImpl] {} {} = {} → FAIL: {}",
                    out.operation(), setting, javaValue, out.error());
            }
            return ToolResult.error(call.id(),
                (out.error() == null ? "unknown error" : out.error())
                    + (out.setting() == null ? "" : " (setting=" + out.setting() + ")"));
        }

        if (out.operation() == ConfigTool.Operation.GET) {
            String valueStr = formatValue(out.value());
            String msg = setting + " = " + valueStr;
            // [R32-b7b-2 P2-2 修复] model setting GET 不回显 value 原值
            if ("model".equals(setting)) {
                log.info("[ConfigToolImpl] GET {} → <redacted> (model 回显已脱敏)", setting);
            } else {
                log.info("[ConfigToolImpl] GET {} → {}", setting, valueStr);
            }
            return ToolResult.success(call.id(), msg);
        }
        // SET
        // [G20④] AppState 写回 · 按 CC appStateKey 语义（ConfigTool/supportedSettings.ts:13-27）：
        //   verbose→'verbose' / model→'mainLoopModel' / alwaysThinkingEnabled→'thinkingEnabled'。
        //   会话级 per-session 实例（ctx.setAppState），多会话互不影响（OPD-TR-H2-06 拍板接线）。
        syncAppStateToCtx(setting, out.newValue(), ctx);
        // [IMP-T G15] 遥测 tengu_config_tool_changed（CC ConfigTool.ts:383-387）
        emitConfigToolChanged(setting, out.newValue());

        String prev = formatValue(out.previousValue());
        String next = formatValue(out.newValue());
        String msg = setting + ": " + prev + " → " + next;
        // [R32-b7b-2 P2-2 修复] model setting SET 不回显 prev/next 原值 (避免 outbound log 包含 model name)
        if ("model".equals(setting)) {
            log.info("[ConfigToolImpl] SET {} → <redacted> (model 回显已脱敏)", setting);
        } else {
            log.info("[ConfigToolImpl] SET {} → {} (prev={})", setting, next, prev);
        }
        return ToolResult.success(call.id(), msg);
    }

    /** JsonNode → Java Object (CC ConfigTool.Input.value 类型映射). */
    private static Object jsonNodeToJava(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isDouble() || node.isFloat()) return node.doubleValue();
        if (node.isTextual()) return node.asText();
        return node.asText(); // fallback: stringify
    }

    /** 格式化 Java 值为字符串 (展示用). */
    private static String formatValue(Object v) {
        if (v == null) return "<absent>";
        if (v == ConfigStorage.NullMarker) return "<null>";
        return String.valueOf(v);
    }
}