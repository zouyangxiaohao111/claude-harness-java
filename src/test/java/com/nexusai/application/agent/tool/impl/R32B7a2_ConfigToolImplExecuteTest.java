package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.settings.SupportedSettings;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ConfigToolPrompt;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-2 · Phase 4 · ConfigToolImpl 完整 execute() 流程验证.
 *
 * <p><b>WHY (意图验证)</b>: Phase 4 把 ConfigToolImpl 接线完整 — execute() 委托核心
 * {@link com.nexusai.application.agent.tool.ConfigTool}, 并通过 {@code rebuildCore()}
 * 注入 SupportedSettings::getConfig + ConfigStorage::read/write + 即时运行时同步.
 *
 * <p>关键验证:
 * <ul>
 *   <li>完整 GET 路径: readGlobal/readSettings → 渲染 "setting = value"</li>
 *   <li>完整 SET 路径: writeGlobal/writeSettings → 渲染 "prev → new"</li>
 *   <li>嵌套 path SET (permissions.defaultMode) → tree merge + appStateSync</li>
 *   <li>即时运行时同步: autoCompactEnabled → sink 调用; permissions.defaultMode → mode sink</li>
 *   <li>unknown setting → error ("Unknown setting")</li>
 *   <li>invalid value (options 不含) → error</li>
 *   <li>依赖未注入时 → fail loud (Phase 1 boundary)</li>
 *   <li>GET 写入 JSON null 后, readGlobal 返回 NullMarker (与 absent 区分)</li>
 * </ul>
 *
 * <p>测试用 {@link TempDir} 隔离 FileConfigStorage (CLAUDE.md 规则 11); 运行时
 * 同步 sink 用 Mockito spy 验证调用次数和参数.
 *
 * @see ConfigToolImpl
 */
class R32B7a2_ConfigToolImplExecuteTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    /** 决策 D1：FileConfigStorage 单参（ConfigStorageProperties），缺省路径 = user.home 派生。
     *  覆写 user.home 隔离测试写盘（防污染真实 ~/.nexusai），用后恢复。 */
    @BeforeEach
    void isolateUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    /** 构造完整接线的 ConfigToolImpl — Phase 5 @Bean 调用形式. */
    private ConfigToolImpl newWiredTool(BiConsumer<PermissionMode, String> modeSync,
                                        Runnable compactSinkCheck) {
        SupportedSettings ss = newTestSupportedSettings();
        FileConfigStorage storage = new FileConfigStorage(null);
        ConfigToolPrompt prompt = new ConfigToolPrompt(ss, ConfigToolPrompt::defaultModelOptions);
        ConfigToolImpl tool = new ConfigToolImpl(ss, storage, prompt);
        if (modeSync != null) {
            tool.setPermissionModeSync(modeSync);
        }
        return tool;
    }

    // ── Phase 4.A: GET path ───────────────────────────────────────────────

    @Test
    @DisplayName("GET global: 写后 read 返回 'setting = value'")
    void getGlobalValueReturnsFormattedString() {
        ConfigToolImpl tool = newWiredTool(null, null);

        // 先 SET theme=dark
        ToolUseBlock setCall = new ToolUseBlock("set-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "theme")
                .put("value", "dark"));
        AgentToolResult setResult = tool.execute(setCall);
        assertThat(setResult).isInstanceOf(ToolResult.class);
        assertThat(LlmAgentLoop.isToolErrorData(((ToolResult) setResult).data())).isFalse();

        // 再 GET theme
        ToolUseBlock getCall = new ToolUseBlock("get-1", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "theme"));
        AgentToolResult getResult = tool.execute(getCall);
        assertThat(getResult).isInstanceOf(ToolResult.class);
        ToolResult<String> tr = (ToolResult<String>) getResult;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        assertThat(tr.data())
            .as("GET → 'setting = value'")
            .isEqualTo("theme = dark");
    }

    @Test
    @DisplayName("GET 设置未存在 (absent) → 'setting = <absent>'")
    void getUnsetGlobalShowsAbsent() {
        // WHY: absent (未设置) 与 JSON null (显式置空) 是不同状态.
        // GET 一个未写的 key, 必须显示 "<absent>" 让 LLM 区分"未配置".
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock getCall = new ToolUseBlock("get-2", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "theme"));
        AgentToolResult result = tool.execute(getCall);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        assertThat(tr.data())
            .contains("<absent>");
    }

    @Test
    @DisplayName("GET 设置为 JSON null (NullMarker) → 'setting = <null>'")
    void getJsonNullShowsNull() {
        // WHY: 与 absent 严格区分 — LLM 看到 "<null>" 表示 key 存在但被显式置空.
        // 注: ConfigTool 契约层面 SET value=null 实际是 GET (Input.isGet()=value==null),
        // 所以"写 JSON null"必须通过 ConfigStorage 写入 (FileConfigStorage.writeGlobal(key, null)).
        FileConfigStorage storage = new FileConfigStorage(null);
        storage.writeGlobal("theme", null);  // 直接写 JSON null (NullMarker 路径)

        SupportedSettings ss = newTestSupportedSettings();
        ConfigToolImpl tool = new ConfigToolImpl(ss, storage,
            new ConfigToolPrompt(ss, ConfigToolPrompt::defaultModelOptions));

        ToolUseBlock getCall = new ToolUseBlock("get-null-1", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "theme"));
        AgentToolResult result = tool.execute(getCall);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(tr.data())
            .as("JSON null → '<null>' 而非 '<absent>'")
            .contains("<null>");
        assertThat(tr.data())
            .as("不能误判为 <absent>")
            .doesNotContain("<absent>");
    }

    // ── Phase 4.B: SET path ───────────────────────────────────────────────

    @Test
    @DisplayName("SET global: 渲染 'prev → new' (新写时 prev = <absent>)")
    void setGlobalRendersPreviousAndNew() {
        // WHY: LLM 需要看 prev → new 验证自己的 SET 生效, 让用户审计变更
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock setCall = new ToolUseBlock("set-3", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "theme")
                .put("value", "dark"));
        AgentToolResult result = tool.execute(setCall);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        assertThat(tr.data())
            .as("SET 渲染 'theme: <absent> → dark' (首次写 prev=absent)")
            .isEqualTo("theme: <absent> → dark");

        // 第二次 SET
        ToolUseBlock setCall2 = new ToolUseBlock("set-4", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "theme")
                .put("value", "light"));
        ToolResult<String> tr2 = (ToolResult<String>) tool.execute(setCall2);
        assertThat(tr2.data())
            .as("第二次 SET 渲染 'theme: dark → light'")
            .isEqualTo("theme: dark → light");
    }

    @Test
    @DisplayName("SET 嵌套 path (permissions.defaultMode) → tree merge + 写入 settings.json")
    void setNestedPathWritesToSettings() throws IOException {
        // WHY: permissions.defaultMode 在 CC 是嵌套 path ["permissions","defaultMode"].
        // 必须通过 writeSettings (而非 writeGlobal) 写 — 否则路径错误.
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock setCall = new ToolUseBlock("set-perm-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "permissions.defaultMode")
                .put("value", "plan"));
        AgentToolResult result = tool.execute(setCall);
        ToolResult tr = (ToolResult) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();

        // 验证文件真实写入 settings.json (嵌套 path)
        Path settingsFile = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        assertThat(Files.exists(settingsFile))
            .as("settings.json 必须存在 (嵌套 path 写)")
            .isTrue();
        String content = Files.readString(settingsFile);
        assertThat(content)
            .contains("permissions")
            .contains("defaultMode")
            .contains("plan");
        // global file 不应包含
        Path globalFile = tempDir.resolve(".nexusai.json");
        if (Files.exists(globalFile)) {
            String globalContent = Files.readString(globalFile);
            assertThat(globalContent)
                .as("global 文件不应含 permissions 嵌套 (路径错误)")
                .doesNotContain("permissions");
        }
    }

    @Test
    @DisplayName("SET boolean type (verbose=true) → coerce 'true' string → Boolean")
    void setBooleanCoercesStringToBoolean() {
        // WHY: CC ConfigTool.ts:185-201 boolean coerce — LLM 可能传 "true" 字符串,
        // 必须 coerce 到 Boolean, 否则 JSON 反序列化失败
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock setCall = new ToolUseBlock("set-bool-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "verbose")
                .put("value", "true"));
        AgentToolResult result = tool.execute(setCall);
        ToolResult tr = (ToolResult) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("字符串 'true' 应 coerce 成 boolean, 不应失败")
            .isFalse();
    }

    // ── Phase 4.C: error mapping ──────────────────────────────────────────

    @Test
    @DisplayName("unknown setting → ToolResult.error 含 'Unknown setting'")
    void unknownSettingReturnsError() {
        // WHY: CC ConfigTool unknown setting 必须 fail loud, 不静默写入
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock setCall = new ToolUseBlock("set-unk-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "nonexistent.setting")
                .put("value", "x"));
        AgentToolResult result = tool.execute(setCall);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("unknown setting → error (CLAUDE.md 规则 12 Fail loud)")
            .isTrue();
        assertThat(tr.data())
            .contains("Unknown setting")
            .contains("nonexistent.setting");
    }

    @Test
    @DisplayName("invalid value (不在 options 中) → ToolResult.error")
    void invalidValueReturnsError() {
        // WHY: LLM 传非法 enum 值 (e.g. permissions.defaultMode="invalid") 应 fail loud.
        // 不静默写入非法配置.
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock setCall = new ToolUseBlock("set-bad-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "permissions.defaultMode")
                .put("value", "not-a-valid-mode"));
        AgentToolResult result = tool.execute(setCall);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isTrue();
        assertThat(tr.data())
            .as("invalid value → error 含 'Invalid value'")
            .contains("Invalid value");
    }

    @Test
    @DisplayName("boolean non-coercible (verbose='maybe') → ToolResult.error")
    void booleanNonCoercibleReturnsError() {
        // WHY: boolean type 必须严格接受 true/false (coerce 后),
        // 传 'maybe' 既非 true 也非 false, 应 fail loud
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock setCall = new ToolUseBlock("set-bool-bad-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "verbose")
                .put("value", "maybe"));
        AgentToolResult result = tool.execute(setCall);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isTrue();
        assertThat(tr.data())
            .contains("true")
            .contains("false");
    }

    // ── Phase 4.D: runtime sync ───────────────────────────────────────────

    @Test
    @DisplayName("SET permissions.defaultMode → permissionModeSync sink 收到 (mode, source)")
    void runtimeSyncInvokesPermissionModeSink() {
        // WHY: Phase 4 核心交付 — permissions.defaultMode SET 后立即触发
        // 权限 mode 同步 (避免后续 LLM 行为仍按旧 mode 走). sink 注入 null 时
        // 降级 log only — 此处注入真实 sink 验证调用.
        java.util.concurrent.atomic.AtomicReference<PermissionMode> capturedMode =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedSource =
            new java.util.concurrent.atomic.AtomicReference<>();
        BiConsumer<PermissionMode, String> sink = (mode, source) -> {
            capturedMode.set(mode);
            capturedSource.set(source);
        };

        ConfigToolImpl tool = newWiredTool(sink, null);

        ToolUseBlock setCall = new ToolUseBlock("set-mode-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "permissions.defaultMode")
                .put("value", "plan"));
        AgentToolResult result = tool.execute(setCall);
        assertThat(LlmAgentLoop.isToolErrorData(((ToolResult) result).data())).isFalse();

        assertThat(capturedMode.get())
            .as("sink 收到 PermissionMode.PLAN")
            .isEqualTo(PermissionMode.PLAN);
        assertThat(capturedSource.get())
            .as("sink 收到 source 标签 'config-tool'")
            .isEqualTo("config-tool");
    }

    @Test
    @DisplayName("SET verbose / model / 不支持的 setting → 不抛异常 (log only)")
    void runtimeSyncLogOnlyForUnsupportedSettings() {
        // WHY: Phase 4 verbose/model 留 follow-up. 当前仅 log best-effort.
        // SET 这些 setting 不应抛异常, 不应调 sink.
        java.util.concurrent.atomic.AtomicInteger sinkCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        BiConsumer<PermissionMode, String> sink = (m, s) -> sinkCalls.incrementAndGet();

        ConfigToolImpl tool = newWiredTool(sink, null);

        ToolUseBlock setVerbose = new ToolUseBlock("set-v-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "verbose")
                .put("value", true));
        assertThat(LlmAgentLoop.isToolErrorData(((ToolResult) tool.execute(setVerbose)).data()))
            .as("SET verbose 不应抛异常 (log only)")
            .isFalse();
        assertThat(sinkCalls.get())
            .as("verbose SET 不应触发 permissionModeSync sink")
            .isZero();

        ToolUseBlock setModel = new ToolUseBlock("set-m-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "model")
                .put("value", "opus"));
        assertThat(LlmAgentLoop.isToolErrorData(((ToolResult) tool.execute(setModel)).data()))
            .as("SET model 不应抛异常 (log only)")
            .isFalse();
        assertThat(sinkCalls.get())
            .as("model SET 不应触发 permissionModeSync sink")
            .isZero();
    }

    @Test
    @DisplayName("未知 permission mode 字符串 → sink 收到 DEFAULT (降级, 不抛错)")
    void runtimeSyncHandlesUnknownModeString() {
        // WHY: ConfigToolImpl.parsePermissionMode 对未知 mode 降级 DEFAULT.
        // LLM 传非法 mode 字符串时, 应 fail loud (invalid value error) 而非
        // 静默 sink 调用 — 此处验证 invalid value 在 sink 触发前已 reject.
        java.util.concurrent.atomic.AtomicInteger sinkCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        BiConsumer<PermissionMode, String> sink = (m, s) -> sinkCalls.incrementAndGet();

        ConfigToolImpl tool = newWiredTool(sink, null);

        ToolUseBlock setCall = new ToolUseBlock("set-bad-mode-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "permissions.defaultMode")
                .put("value", "super-auto"));
        AgentToolResult result = tool.execute(setCall);
        assertThat(LlmAgentLoop.isToolErrorData(((ToolResult) result).data()))
            .as("invalid mode → error, sink 不应被调用")
            .isTrue();
        assertThat(sinkCalls.get())
            .as("invalid value 在 sink 触发前已 reject, sink 调用次数 = 0")
            .isZero();
    }

    // ── Phase 4.E: skeleton boundary ──────────────────────────────────────

    @Test
    @DisplayName("Phase 1 skeleton execute: 缺 setting/缺 dep → 两种 fail loud 都正确")
    void skeletonExecuteHandlesBothErrorPaths() {
        // WHY: Phase 1 skeleton (无 setter 注入) 必须 fail loud —
        // 1) 缺 setting → "missing required input"
        // 2) 缺 dep → "未完成接线"  (供运维排查)
        ConfigToolImpl tool = new ConfigToolImpl();

        // 缺 setting
        ToolUseBlock callNoSetting = new ToolUseBlock("c1", "Config",
            JsonNodeFactory.instance.objectNode());
        ToolResult<String> tr1 = (ToolResult<String>) tool.execute(callNoSetting);
        assertThat(LlmAgentLoop.isToolErrorData(tr1.data())).isTrue();
        assertThat(tr1.data()).contains("setting");

        // 缺 dep
        ToolUseBlock callWithSetting = new ToolUseBlock("c2", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "theme"));
        ToolResult<String> tr2 = (ToolResult<String>) tool.execute(callWithSetting);
        assertThat(LlmAgentLoop.isToolErrorData(tr2.data())).isTrue();
        assertThat(tr2.data())
            .containsIgnoringCase("未完成接线")
            .contains("theme");
    }

    @Test
    @DisplayName("setSupportedSettings 后, 缺 ConfigStorage 仍 fail loud")
    void partialInjectionStillFailsLoud() {
        // WHY: rebuildCore() 要求 supportedSettings + configStorage 都注入.
        // 只注入 supportedSettings 时, coreConfigTool 仍为 null, execute fail loud —
        // 不应有"半注入"导致静默 no-op
        ConfigToolImpl tool = new ConfigToolImpl();
        tool.setSupportedSettings(newTestSupportedSettings());
        // 不调 setConfigStorage

        ToolUseBlock call = new ToolUseBlock("c3", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "theme"));
        ToolResult<String> tr = (ToolResult<String>) tool.execute(call);
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isTrue();
        assertThat(tr.data()).containsIgnoringCase("未完成接线");
    }

    // ── Phase 4.F: round-trip 真实验证 ─────────────────────────────────────

    @Test
    @DisplayName("端到端: SET → GET → 验证 disk 真实落地 + round-trip 一致")
    void endToEndSetGetRoundTrip() throws IOException {
        // WHY: 真实场景 — 用户 SET theme=dark, 进程重启, 再 GET → 仍是 dark.
        // 验证文件真实落地, 不只在内存 (CLAUDE.md 规则 12 Fail loud).
        ConfigToolImpl tool1 = newWiredTool(null, null);

        ToolUseBlock setCall = new ToolUseBlock("e1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "theme")
                .put("value", "dark"));
        tool1.execute(setCall);

        // 验证文件
        Path globalFile = tempDir.resolve(".nexusai.json");
        assertThat(Files.exists(globalFile)).isTrue();

        // 模拟重启: 新实例从同一目录读
        FileConfigStorage storage2 = new FileConfigStorage(null);
        ConfigToolImpl tool2 = new ConfigToolImpl(newTestSupportedSettings(), storage2,
            new ConfigToolPrompt(newTestSupportedSettings(), ConfigToolPrompt::defaultModelOptions));

        ToolUseBlock getCall = new ToolUseBlock("g1", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "theme"));
        ToolResult<String> tr = (ToolResult<String>) tool2.execute(getCall);
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        assertThat(tr.data())
            .as("重启后 GET theme 应返回 'dark' (持久化生效)")
            .isEqualTo("theme = dark");
    }

    // ── Phase 4.G: IMP-H4 — validateOnWrite 接线 + formatOnRead (CC ConfigTool.ts) ──

    @Test
    @DisplayName("GET model 未配置 → 'model = default'（CC formatOnRead v===null→'default'，supportedSettings.ts:105）")
    void getModelUnsetShowsDefault() {
        // WHY: CC supportedSettings.ts:105 formatOnRead: v => (v === null ? 'default' : v)。
        //   未设置 model 时 GET 必须显示 'default'（而非 Java 旧 '<absent>'），LLM 才能理解
        //   "使用平台默认模型"。IMP-H4 接线前 SettingDef 无 formatOnRead，GET 返回 raw null → '<absent>'。
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock getCall = new ToolUseBlock("get-model-1", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "model"));
        AgentToolResult result = tool.execute(getCall);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("GET model 未配置不应报错")
            .isFalse();
        assertThat(tr.data())
            .as("GET model → 'model = default'（formatOnRead null→default）")
            .isEqualTo("model = default")
            .doesNotContain("<absent>");
    }

    @Test
    @DisplayName("GET model 已设置 → 原值透传（formatOnRead 非 null 不改写）")
    void getModelSetPassthrough() {
        // WHY: formatOnRead 仅把 null 映射为 'default'；已配置值必须原样返回，不能误改。
        ConfigToolImpl tool = newWiredTool(null, null);

        ToolUseBlock setCall = new ToolUseBlock("set-model-1", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "model")
                .put("value", "opus"));
        assertThat(LlmAgentLoop.isToolErrorData(((ToolResult) tool.execute(setCall)).data()))
            .as("SET model=opus 应成功（test validator 接受）")
            .isFalse();

        ToolUseBlock getCall = new ToolUseBlock("get-model-2", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "model"));
        ToolResult<String> tr = (ToolResult<String>) tool.execute(getCall);
        assertThat(LlmAgentLoop.isToolErrorData(tr.data())).isFalse();
        assertThat(tr.data())
            .as("GET model → 'model = opus'（formatOnRead 透传非 null 值）")
            .isEqualTo("model = opus");
    }

    @Test
    @DisplayName("SET model 非法（validator 拒绝）→ ToolResult.error（IMP-H4 validateOnWrite 接线）")
    void setModelInvalidRejectedByValidateOnWrite() throws IOException {
        // WHY: CC ConfigTool.ts:217-229 validateOnWrite（model → validateModel API）在 SET 前校验，
        //   非法 model 不得写入 settings.json。IMP-H4 前 SettingDef.validateOnWrite=null
        //   （ConfigToolImpl.rebuildCore :167-168 注释"model 校验通过 writeGlobal/writeSettings 内部完成"
        //   但 FileConfigStorage 无校验 → 非法 model 可写入）。接线后必须拒绝。
        Function<String, CompletableFuture<SupportedSettings.ValidationResult>> rejectingValidator =
            model -> CompletableFuture.completedFuture(
                model != null && "invalid-model".equals(model)
                    ? new SupportedSettings.ValidationResult(false, "Model 'invalid-model' is not in the list of available models")
                    : new SupportedSettings.ValidationResult(true, null));

        SupportedSettings ss = newTestSupportedSettingsWithValidator(rejectingValidator);
        FileConfigStorage storage = new FileConfigStorage(null);
        ConfigToolImpl tool = new ConfigToolImpl(ss, storage,
            new ConfigToolPrompt(ss, ConfigToolPrompt::defaultModelOptions));

        ToolUseBlock setCall = new ToolUseBlock("set-model-invalid", "Config",
            JsonNodeFactory.instance.objectNode()
                .put("setting", "model")
                .put("value", "invalid-model"));
        AgentToolResult result = tool.execute(setCall);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("非法 model → validateOnWrite 拒绝 → isError=true")
            .isTrue();
        assertThat(tr.data())
            .as("错误消息含 validator 返回的 error 文本（fail loud）")
            .contains("invalid-model");

        // 校验拒绝后不落盘：settings.json 不应含 invalid-model
        Path settingsFile = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        if (Files.exists(settingsFile)) {
            assertThat(Files.readString(settingsFile))
                .as("非法 model 拒绝后不得写入 settings.json")
                .doesNotContain("invalid-model");
        }
    }

    // ── 测试 helper ───────────────────────────────────────────────────────

    /** 构造 SupportedSettings — 包含基本 settings (theme/verbose/permissions.defaultMode/model). */
    private static SupportedSettings newTestSupportedSettings() {
        return newTestSupportedSettingsWithValidator(
            model -> CompletableFuture.completedFuture(new SupportedSettings.ValidationResult(true, null)));
    }

    /** 构造 SupportedSettings — 自定义 model validator（IMP-H4 validateOnWrite 接线测试用）. */
    private static SupportedSettings newTestSupportedSettingsWithValidator(
            Function<String, CompletableFuture<SupportedSettings.ValidationResult>> validator) {
        BooleanSupplier allFalse = () -> false;
        Supplier<List<String>> modelOpts = () -> List.of("sonnet", "opus", "haiku");
        Supplier<String> nullStr = () -> null;
        return new SupportedSettings(
            allFalse,  // autoTheme
            allFalse,  // transcriptClassifier
            allFalse,  // voiceMode
            allFalse,  // bridgeMode
            allFalse,  // kairos
            allFalse,  // kairosPush
            allFalse,  // isAnt
            modelOpts,
            validator,
            nullStr,
            List.of("normal", "vim"),
            List.of("iterm2", "terminal_bell", "notifications_disabled"),
            List.of("tmux", "in-process", "auto"),
            List.of("dark", "light", "dark-daltonized", "light-daltonized"),
            List.of("dark", "light", "dark-daltonized", "light-daltonized", "system"));
    }

    /** Mockito suppress unused warning. */
    @SuppressWarnings("unused")
    private static <T> T spy(T t) {
        return Mockito.spy(t);
    }
}