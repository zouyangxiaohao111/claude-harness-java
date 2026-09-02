package com.nexusai.application.agent.settings.config;

import com.nexusai.application.agent.settings.SupportedSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-2 · Phase 2 · SupportedSettingsConfig 构造器注入验证.
 *
 * <p><b>WHY (意图验证)</b>: SupportedSettingsConfig 是唯一 setting registry 源, 把
 * {@link SupportedSettings} 注册为 Spring @Bean. 构造器注入 14 个参数:
 * <ul>
 *   <li>BooleanSupplier × 7 — feature flags (autoTheme/transcriptClassifier/voiceMode/
 *       bridgeMode/kairos/kairosPush/isAnt)</li>
 *   <li>Supplier × 3 — modelOptions/modelValidator/remoteControlAtStartup</li>
 *   <li>List × 5 — DEFAULT_EDITOR_MODES/notificationChannels/teammateModes/themeNames/themeSettings</li>
 * </ul>
 *
 * <p>关键验证:
 * <ul>
 *   <li>无 nexusai.feature.* 配置 → 全部 feature flag 默认 false (NexusAI non-ant 定位)</li>
 *   <li>model options 委托 supplier — 配置阶段不求值, 动态语义保留 (避免重启才能换模型)</li>
 *   <li>支持的 truthy 值 (true/1/yes/on) 都识别为 true, 其他视为 false</li>
 *   <li>isAnt=true → SupportedSettings 含 ant-only setting (classifierPermissionsEnabled)</li>
 *   <li>voiceMode=true → 含 voiceEnabled; kairos=true → 含 kairos-related 3 项</li>
 * </ul>
 *
 * <p>不修改 SupportedSettings.java 本身 (CLAUDE.md 规则 3 外科手术式修改):
 * 本测试只验证 SupportedSettingsConfig 把 Environment flag 正确转换为 boolean/string supplier.
 *
 * @see SupportedSettingsConfig
 */
class R32B7a2_SupportedSettingsConfigTest {

    private final SupportedSettingsConfig config = new SupportedSettingsConfig();

    /** 构造 @Bean (内部会读 env). */
    private SupportedSettings buildWithEnv(MockEnvironment env) {
        return config.supportedSettings(env);
    }

    @Test
    @DisplayName("默认无配置 → 全部 feature flag 默认 false (non-ant 定位)")
    void allFeatureFlagsDefaultFalse() {
        // WHY: NexusAI backend 当前定位是 non-ant 用户; default = false 安全语义.
        // 若意外全开, 会触发 voiceEnabled/bridgeMode/Kairos 等 ant-only setting,
        // 误导 LLM 渲染 prompt / 启用未注入的运行时服务.
        SupportedSettings ss = buildWithEnv(new MockEnvironment());

        // 验证 isAnt=false → 不应包含 ant-only setting
        assertThat(ss.isSupported("classifierPermissionsEnabled"))
            .as("isAnt=false → ant-only setting 不暴露")
            .isFalse();

        // voiceMode=false → voiceEnabled 不暴露
        assertThat(ss.isSupported("voiceEnabled"))
            .as("voiceMode=false → voiceEnabled 不暴露")
            .isFalse();

        // bridgeMode=false → remoteControlAtStartup 不暴露
        assertThat(ss.isSupported("remoteControlAtStartup"))
            .as("bridgeMode=false → remoteControlAtStartup 不暴露")
            .isFalse();

        // kairos / kairosPush=false → kairos-related 3 项不暴露
        assertThat(ss.isSupported("taskCompleteNotifEnabled")).isFalse();
        assertThat(ss.isSupported("inputNeededNotifEnabled")).isFalse();
        assertThat(ss.isSupported("agentPushNotifEnabled")).isFalse();
    }

    @Test
    @DisplayName("nexusai.user.type.ant=true → SupportedSettings 含 classifierPermissionsEnabled")
    void antFlagEnablesAntOnlySetting() {
        // WHY: isAnt=true 是 ant 用户注册 ConfigTool 的前置条件 (Phase 5);
        // 必须验证 env flag 正确转 boolean. CC isAnt()=true → registry 暴露 ant-only.
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexusai.user.type.ant", "true");

        SupportedSettings ss = buildWithEnv(env);
        assertThat(ss.isSupported("classifierPermissionsEnabled"))
            .as("isAnt=true → ant-only setting 暴露")
            .isTrue();
    }

    @Test
    @DisplayName("truthy 值 (1/yes/on) 都被识别为 true (大小写不敏感)")
    void truthyValuesAllResolveToTrue() {
        // WHY: ConfigToolPrompt/SupportedSettings 多个 boolean flag;
        // 不同运维习惯用 true/1/yes/on. 必须全部识别, 否则误关重要功能.
        for (String truthy : new String[]{"true", "TRUE", "True", "1", "yes", "YES", "on", "ON"}) {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("nexusai.user.type.ant", truthy);

            SupportedSettings ss = buildWithEnv(env);
            assertThat(ss.isSupported("classifierPermissionsEnabled"))
                .as("truthy 值 '%s' 必须识别为 true", truthy)
                .isTrue();
        }
    }

    @Test
    @DisplayName("model options 委托 supplier — 配置阶段不求值 (动态语义保留)")
    void modelOptionsUseLazySupplier() {
        // WHY: model 列表可能在运行时变化 (新模型注册). 配置阶段预求值会
        // 冻结列表, 后续 refresh 不生效. Supplier<List<String>> 注入确保
        // 每次 SupportedSettings.getAll() 调用时都重新读 env.
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexusai.feature.model-options", "gpt-4o,claude-sonnet,custom-model");

        SupportedSettings ss = buildWithEnv(env);

        // 第一次读 — 当前 env 列表
        assertThat(ss.getOptionsForSetting("model"))
            .containsExactly("gpt-4o", "claude-sonnet", "custom-model");

        // 修改 env (模拟新模型注册) — 应立即生效 (非 cached)
        env.setProperty("nexusai.feature.model-options", "new-model-1,new-model-2");
        assertThat(ss.getOptionsForSetting("model"))
            .as("Supplier 语义: 修改 env 后立即可见, 不需重启")
            .containsExactly("new-model-1", "new-model-2");

        // 移除 env (空字符串) → 降级 DEFAULT
        env.setProperty("nexusai.feature.model-options", "");
        assertThat(ss.getOptionsForSetting("model"))
            .as("env 缺省 → 降级 DEFAULT (sonnet/opus/haiku)")
            .contains("sonnet");
    }

    @Test
    @DisplayName("voiceMode=true → voiceEnabled setting 暴露")
    void voiceModeEnablesVoiceSetting() {
        // WHY: voiceMode 是 conditional setting 的开关; ConfigToolPrompt
        // 据此决定 voiceEnabled 是否出现在 prompt (CC isVoiceGrowthBookEnabled).
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexusai.feature.voice-mode", "true");

        SupportedSettings ss = buildWithEnv(env);
        assertThat(ss.isSupported("voiceEnabled"))
            .as("voiceMode=true → voiceEnabled 暴露")
            .isTrue();
    }

    @Test
    @DisplayName("kairos=true → kairos-related 3 项 setting 暴露")
    void kairosEnablesThreeRelatedSettings() {
        // WHY: kairos 是推送通知平台集成; 任一 flag 开启都暴露全部 3 项
        // (taskCompleteNotifEnabled / inputNeededNotifEnabled / agentPushNotifEnabled).
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexusai.feature.kairos", "true");

        SupportedSettings ss = buildWithEnv(env);
        assertThat(ss.isSupported("taskCompleteNotifEnabled")).isTrue();
        assertThat(ss.isSupported("inputNeededNotifEnabled")).isTrue();
        assertThat(ss.isSupported("agentPushNotifEnabled")).isTrue();
    }

    @Test
    @DisplayName("kairosPush=true 也触发 kairos-related 3 项 (kairos OR kairosPush)")
    void kairosPushAloneAlsoEnablesKairosSettings() {
        // WHY: CC kairos-push-notification 是独立 flag 但语义共享 — 任一为 true
        // 即暴露 3 项. 验证 OR 逻辑而非 AND, 否则单一 flag 不会触发推送集成.
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexusai.feature.kairos-push-notification", "true");

        SupportedSettings ss = buildWithEnv(env);
        assertThat(ss.isSupported("taskCompleteNotifEnabled"))
            .as("kairosPush alone → taskCompleteNotifEnabled 暴露")
            .isTrue();
        assertThat(ss.isSupported("inputNeededNotifEnabled"))
            .as("kairosPush alone → inputNeededNotifEnabled 暴露")
            .isTrue();
    }

    @Test
    @DisplayName("non-truthy 值 (false/no/off) 全部视为 false")
    void nonTruthyValuesResolveToFalse() {
        // WHY: 运维禁用功能时可能写 false/no/off; 必须全部识别为关闭.
        // 否则 ant 用户误注册 ConfigTool → 暴露敏感工具.
        for (String falsy : new String[]{"false", "FALSE", "no", "off", "0", ""}) {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("nexusai.user.type.ant", falsy);

            SupportedSettings ss = buildWithEnv(env);
            assertThat(ss.isSupported("classifierPermissionsEnabled"))
                .as("falsy 值 '%s' 必须识别为 false", falsy)
                .isFalse();
        }
    }

    @Test
    @DisplayName("comprehensive: ant + voice + kairos 全开 → 暴露全部 conditional settings")
    void allConditionalFlagsOnExposeAllConditionalSettings() {
        // WHY: 综合验证 — 全部 feature flag 同时开启时, registry 应包含所有
        // conditional settings. 这是 ant 部署的完整形态.
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexusai.user.type.ant", "true");
        env.setProperty("nexusai.feature.voice-mode", "true");
        env.setProperty("nexusai.feature.kairos", "true");

        SupportedSettings ss = buildWithEnv(env);
        assertThat(ss.isSupported("classifierPermissionsEnabled")).isTrue();
        assertThat(ss.isSupported("voiceEnabled")).isTrue();
        assertThat(ss.isSupported("taskCompleteNotifEnabled")).isTrue();
        assertThat(ss.isSupported("inputNeededNotifEnabled")).isTrue();
        assertThat(ss.isSupported("agentPushNotifEnabled")).isTrue();

        // 基础 settings 仍暴露
        assertThat(ss.isSupported("theme")).isTrue();
        assertThat(ss.isSupported("verbose")).isTrue();
        assertThat(ss.isSupported("autoCompactEnabled")).isTrue();
        assertThat(ss.isSupported("model")).isTrue();
        assertThat(ss.isSupported("permissions.defaultMode")).isTrue();
    }

    @Test
    @DisplayName("transcriptClassifier=true → permissions.defaultMode options 包含 auto")
    void transcriptClassifierEnablesAutoModeOption() {
        // WHY: CC permissions.ts 把 transcript-classifier 开启时, permission mode
        // 多了 "auto" 选项 (LLM 自动决策). 验证 Conditional flag 影响 options 列表.
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexusai.feature.transcript-classifier", "true");

        SupportedSettings ss = buildWithEnv(env);
        assertThat(ss.getOptionsForSetting("permissions.defaultMode"))
            .as("transcriptClassifier=true → options 含 auto")
            .contains("auto");
    }

    @Test
    @DisplayName("transcriptClassifier=false → permissions.defaultMode options 不含 auto")
    void transcriptClassifierDisabledRemovesAutoOption() {
        // WHY: 与上一测试对称 — 默认状态下 auto 不在 options 列表, 防止 LLM
        // 试图启用未支持的 mode
        SupportedSettings ss = buildWithEnv(new MockEnvironment());
        assertThat(ss.getOptionsForSetting("permissions.defaultMode"))
            .as("transcriptClassifier=false → options 不含 auto")
            .doesNotContain("auto");
    }
}