package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.settings.SupportedSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-2 · Phase 4 · ConfigToolPrompt 双向兼容验证.
 *
 * <p><b>WHY (意图验证)</b>: ConfigToolPrompt 是 ConfigTool 的 prompt 渲染器.
 * Phase 4 重构为可注入实例 (Spring 注入 SupportedSettings + Supplier<List<ModelOption>>).
 * 但保留原 static API 以兼容既有 {@code ConfigToolPromptTest}.
 *
 * <p>关键 invariant (CLAUDE.md 规则 3 外科手术式修改 — 不破坏既有调用):
 * <ul>
 *   <li>static {@code generate} / {@code defaultRegistry} / {@code defaultModelOptions}
 *       仍可用, 行为不变</li>
 *   <li>新增实例构造器 {@code ConfigToolPrompt(SupportedSettings, Supplier)} 接受 Spring 注入</li>
 *   <li>实例方法 {@code renderFull} / {@code renderDescription} / {@code renderSettingEntries}
 *       / {@code renderModelOptions} 正常工作</li>
 *   <li>无参构造器 (Phase 1) 仍可创建, 不依赖 Spring</li>
 *   <li>{@code renderDescription()} 返回静态 {@link ConfigToolPrompt#DESCRIPTION}</li>
 * </ul>
 *
 * <p>测试验证: 同一类两种 API 风格共存, 既有 static 测试不破, 新增实例路径生效.
 *
 * @see ConfigToolPrompt
 */
class R32B7a2_ConfigToolPromptDualCompatTest {

    // ── 静态 API 兼容性 (Phase 1 既有 contract) ───────────────────────────

    @Test
    @DisplayName("static generate() 仍可用: 3 settings → 含 Global/Project + Model 段")
    void staticGenerateStillWorks() {
        // WHY: 既有 ConfigToolPromptTest 验证 static generate. Phase 4 不破坏
        // 该 API — 这是双向兼容的核心 invariant (CLAUDE.md 规则 3).
        String prompt = ConfigToolPrompt.generate(
            ConfigToolPrompt::defaultRegistry,
            ConfigToolPrompt::defaultModelOptions,
            false);

        assertThat(prompt)
            .contains("Global Settings")
            .contains("Project Settings")
            .contains("Model");
        // theme / permissions.defaultMode 出现在合适段
        assertThat(prompt).contains("theme");
        assertThat(prompt).contains("permissions.defaultMode");
        // model 设置不单独出现在 "list" 段 (单独 section)
        // 因 model 在 generate() 中被 skip (CC 行为)
        assertThat(prompt.split("### Global Settings")[1].split("### Project")[0])
            .as("model 不出现在 Global Settings 段 (单独 section)")
            .doesNotContain("- model:");
    }

    @Test
    @DisplayName("static defaultRegistry() 含 theme/verbose/voiceEnabled/permissions.defaultMode")
    void defaultRegistryHasExpectedKeys() {
        // WHY: 测试 helper 的 invariant — 必须含 4 个典型 setting 才能
        // 覆盖 global/project/boolean/enum 维度
        Map<String, ConfigToolPrompt.SettingEntry> reg = ConfigToolPrompt.defaultRegistry();
        assertThat(reg).containsKeys("theme", "verbose", "voiceEnabled", "permissions.defaultMode");
        assertThat(reg.get("theme").source()).isEqualTo("global");
        assertThat(reg.get("permissions.defaultMode").source()).isEqualTo("project");
    }

    @Test
    @DisplayName("static defaultModelOptions() 含 sonnet/opus/haiku")
    void defaultModelOptionsHasExpectedModels() {
        List<ConfigToolPrompt.ModelOption> opts = ConfigToolPrompt.defaultModelOptions();
        assertThat(opts)
            .extracting(ConfigToolPrompt.ModelOption::value)
            .contains("opus", "sonnet", "haiku");
    }

    @Test
    @DisplayName("static generate() voiceEnabled=false → voiceEnabled 不出现在 prompt")
    void voiceEnabledSkippedWhenFalse() {
        // WHY: CC ConfigTool.ts voiceEnabled 在 voiceMode=false 时不渲染,
        // 因为该 setting 对用户不可见. 锁定该 skip 行为.
        String prompt = ConfigToolPrompt.generate(
            ConfigToolPrompt::defaultRegistry,
            ConfigToolPrompt::defaultModelOptions,
            false);
        // voiceEnabled entry 不出现 (skip)
        assertThat(prompt).doesNotContain("- voiceEnabled:");
    }

    @Test
    @DisplayName("static generate() voiceEnabled=true → voiceEnabled 出现")
    void voiceEnabledIncludedWhenTrue() {
        String prompt = ConfigToolPrompt.generate(
            ConfigToolPrompt::defaultRegistry,
            ConfigToolPrompt::defaultModelOptions,
            true);
        assertThat(prompt).contains("- voiceEnabled:");
    }

    @Test
    @DisplayName("static generate() theme 含 options 列表 (dark, light)")
    void themeOptionsShownAsQuotedList() {
        // WHY: CC ConfigTool.ts:170-178 enum options 渲染为 quoted list
        // "opt1", "opt2"; LLM 据此知道枚举可选值.
        String prompt = ConfigToolPrompt.generate(
            ConfigToolPrompt::defaultRegistry,
            ConfigToolPrompt::defaultModelOptions,
            false);
        assertThat(prompt)
            .contains("\"dark\"")
            .contains("\"light\"");
    }

    @Test
    @DisplayName("static generate() boolean type 显示 'true/false'")
    void booleanTypeShowsTrueFalse() {
        // WHY: CC ConfigTool.ts:178 boolean 类型不写 options 列表,
        // 而是显示 ": true/false" 让 LLM 知道是布尔
        String prompt = ConfigToolPrompt.generate(
            ConfigToolPrompt::defaultRegistry,
            ConfigToolPrompt::defaultModelOptions,
            false);
        assertThat(prompt)
            .contains("true/false")
            .contains("verbose");
    }

    // ── 实例 API (Phase 4 新增) ───────────────────────────────────────────

    @Test
    @DisplayName("无参构造器: 实例仍可创建, 渲染降级到 static defaultRegistry")
    void noArgConstructorStillWorks() {
        // WHY: Phase 1 创建的 ConfigToolPrompt() 必须仍可创建 —
        // Tool.description() 在无 Spring 注入时降级到 DESCRIPTION.
        ConfigToolPrompt p = new ConfigToolPrompt();
        assertThat(p).isNotNull();
        // 无注入时, renderSettingEntries 降级到 defaultRegistry
        assertThat(p.renderSettingEntries())
            .as("无注入时降级到 defaultRegistry")
            .containsKey("theme");
    }

    @Test
    @DisplayName("renderDescription() 返回静态 DESCRIPTION (Phase 4 不变)")
    void renderDescriptionReturnsStaticConstant() {
        ConfigToolPrompt p = new ConfigToolPrompt();
        assertThat(p.renderDescription())
            .isEqualTo(ConfigToolPrompt.DESCRIPTION);
    }

    @Test
    @DisplayName("实例构造器 + SupportedSettings: renderSettingEntries 从 registry 派生")
    void instanceConstructorWithSupportedSettings() {
        // WHY: Spring 注入路径 (Phase 5 @Bean). SupportedSettings 暴露的全部
        // setting 应在 renderSettingEntries 中出现.
        SupportedSettings ss = newTestSupportedSettings();
        ConfigToolPrompt p = new ConfigToolPrompt(ss, ConfigToolPrompt::defaultModelOptions);

        Map<String, ConfigToolPrompt.SettingEntry> entries = p.renderSettingEntries();
        assertThat(entries)
            .as("SupportedSettings 暴露的全部 setting 出现在 entries")
            .containsKeys("theme", "verbose", "autoCompactEnabled", "permissions.defaultMode");
    }

    @Test
    @DisplayName("renderModelOptions() 委托给注入的 supplier")
    void renderModelOptionsUsesInjectedSupplier() {
        // WHY: model options 由 Phase 5 @Bean 派生 (supportedSettings.getOptionsForSetting("model")).
        // supplier 行为异常 (返回 null/empty/throw) 必须降级 default, 不破 prompt.
        Supplier<List<ConfigToolPrompt.ModelOption>> empty = () -> List.of();
        ConfigToolPrompt p = new ConfigToolPrompt(null, empty);
        assertThat(p.renderModelOptions())
            .as("supplier 返回空 → 降级 defaultModelOptions")
            .isEqualTo(ConfigToolPrompt.defaultModelOptions());
    }

    @Test
    @DisplayName("renderModelOptions() supplier 抛异常 → 降级 default (resilience)")
    void renderModelOptionsHandlesSupplierException() {
        Supplier<List<ConfigToolPrompt.ModelOption>> broken = () -> {
            throw new RuntimeException("simulated");
        };
        ConfigToolPrompt p = new ConfigToolPrompt(null, broken);
        // 不应抛异常, 而是降级到 default
        assertThat(p.renderModelOptions())
            .as("supplier 异常不应传播, 降级到 default (best-effort)")
            .isEqualTo(ConfigToolPrompt.defaultModelOptions());
    }

    @Test
    @DisplayName("renderFull(false): 完整 prompt 含 Global/Project/Model 段")
    void renderFullProducesCompletePrompt() {
        SupportedSettings ss = newTestSupportedSettings();
        ConfigToolPrompt p = new ConfigToolPrompt(ss, ConfigToolPrompt::defaultModelOptions);

        String prompt = p.renderFull(false);
        assertThat(prompt)
            .contains("Global Settings")
            .contains("Project Settings")
            .contains("Model")
            .contains("## Examples");
    }

    @Test
    @DisplayName("renderFull(true) vs renderFull(false): voiceEnabled 行为差异")
    void renderFullVoiceEnabledDifference() {
        // WHY: voiceEnabled setting 是 conditional (由 voiceMode flag 控制);
        // 必须 SupportedSettings(voiceMode=true) 才暴露 voiceEnabled,
        // 否则 registry 根本没这条 setting, voiceEnabled flag 也无法影响渲染.
        SupportedSettings ss = newTestSupportedSettings(true /* voiceMode */);
        ConfigToolPrompt p = new ConfigToolPrompt(ss, ConfigToolPrompt::defaultModelOptions);

        String withVoice = p.renderFull(true);
        String withoutVoice = p.renderFull(false);
        assertThat(withVoice).contains("- voiceEnabled:");
        assertThat(withoutVoice).doesNotContain("- voiceEnabled:");
    }

    @Test
    @DisplayName("实例与 static 渲染结果对齐 (相同输入 → 相同输出, except dynamic parts)")
    void instanceAndStaticPromptsShareStructure() {
        // WHY: Phase 4 重构不应破坏既有 static 测试. 实例渲染路径应与 static
        // 路径产出一致的 4-section 结构 (Global/Project/Model/Examples).
        SupportedSettings ss = newTestSupportedSettings();
        ConfigToolPrompt p = new ConfigToolPrompt(ss, ConfigToolPrompt::defaultModelOptions);

        String inst = p.renderFull(false);
        String stat = ConfigToolPrompt.generate(
            ConfigToolPrompt::defaultRegistry,
            ConfigToolPrompt::defaultModelOptions,
            false);

        // 验证二者都含相同的段标题 (4 段)
        for (String section : new String[]{
            "## Usage", "## Configurable settings list",
            "### Global Settings", "### Project Settings",
            "## Model", "## Examples"}) {
            assertThat(inst)
                .as("实例 prompt 含段 '%s'", section)
                .contains(section);
            assertThat(stat)
                .as("static prompt 含段 '%s'", section)
                .contains(section);
        }
    }

    @Test
    @DisplayName("实例 SettingEntry options 从 SupportedSettings.getOptionsForSetting 派生")
    void instanceEntryOptionsFromSupportedSettings() {
        // WHY: renderSettingEntries 必须用 SupportedSettings 的 options
        // (动态, feature flag 联动), 不能用 defaultRegistry 的静态列表.
        SupportedSettings ss = newTestSupportedSettings();
        ConfigToolPrompt p = new ConfigToolPrompt(ss, ConfigToolPrompt::defaultModelOptions);

        Map<String, ConfigToolPrompt.SettingEntry> entries = p.renderSettingEntries();
        ConfigToolPrompt.SettingEntry themeEntry = entries.get("theme");
        assertThat(themeEntry).isNotNull();
        assertThat(themeEntry.options())
            .as("theme options 从 SupportedSettings 派生 (默认 4 个 dark/light/dark-daltonized/light-daltonized)")
            .isNotEmpty()
            .contains("dark", "light");
    }

    // ── 测试 helper ───────────────────────────────────────────────────────

    /** 构造一个 minimal SupportedSettings 用于测试实例 API. */
    private static SupportedSettings newTestSupportedSettings() {
        return newTestSupportedSettings(false);
    }

    /** 构造 SupportedSettings, 可选 voiceMode flag (用于 voiceEnabled conditional 测试). */
    private static SupportedSettings newTestSupportedSettings(boolean voiceModeOn) {
        // 全部 boolean supplier → false; supplier / list 提供默认空集
        java.util.function.BooleanSupplier allFalse = () -> false;
        java.util.function.BooleanSupplier voiceModeSupplier = () -> voiceModeOn;
        java.util.function.Supplier<java.util.List<String>> emptyList = () -> java.util.List.of();
        java.util.function.Supplier<String> nullStr = () -> null;
        java.util.function.Function<String, java.util.concurrent.CompletableFuture<SupportedSettings.ValidationResult>> validator =
            model -> java.util.concurrent.CompletableFuture.completedFuture(
                new SupportedSettings.ValidationResult(true, null));
        return new SupportedSettings(
            allFalse,  // autoTheme
            allFalse,  // transcriptClassifier
            voiceModeSupplier,  // voiceMode
            allFalse,  // bridgeMode
            allFalse,  // kairos
            allFalse,  // kairosPush
            allFalse,  // isAnt
            emptyList, // modelOptionsSupplier
            validator,
            nullStr,   // remoteControlAtStartup
            java.util.List.of("normal", "vim"),               // editorModes
            java.util.List.of("iterm2", "terminal_bell", "notifications_disabled"),  // notificationChannels
            java.util.List.of("tmux", "in-process", "auto"),  // teammateModes
            java.util.List.of("dark", "light", "dark-daltonized", "light-daltonized"),  // themeNames
            java.util.List.of("dark", "light", "dark-daltonized", "light-daltonized", "system")  // themeSettings
        );
    }
}