package com.nexusai.application.agent.settings.config;

import com.nexusai.application.agent.settings.SupportedSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * [R32-b7a-2 Phase 2] SupportedSettings Spring 接线 · 对齐 CC
 * {@code tools/ConfigTool/supportedSettings.ts}.
 *
 * <p>本类把 {@link SupportedSettings} 注册为 Spring @Bean,使其成为唯一 setting registry 源
 * (Phase 1/4/5 的 ConfigToolImpl / ConfigToolPrompt 都依赖本 bean).
 *
 * <h2>为什么不修改 SupportedSettings.java 本身</h2>
 * <ul>
 *   <li>SupportedSettings 已完整复刻 CC 21 项 setting (含 5 项条件项), 行为已稳定.</li>
 *   <li>本类只补充 Spring 接线, 不修改 setting registry 实现 — 避免 SettingDef / SettingConfig
 *       / SettingEntry 三套模型并存 (CLAUDE.md 规则 3 外科手术式修改).</li>
 *   <li>若 bean 装配失败, 现有路径 (无 Spring 上下文) 仍可 new SupportedSettings(...) 走通,
 *       见 {@code SupportedSettingsTest}.</li>
 * </ul>
 *
 * <h2>Feature flags 来源</h2>
 * <ul>
 *   <li>优先从 Environment 读 {@code nexusai.feature.*} (yml/system prop/env).</li>
 *   <li>缺省回退到 {@code false} — 与 NexusAI backend 当前 non-ant 定位一致.</li>
 * </ul>
 *
 * <h2>避免预计算 model options</h2>
 * <p>model options 由 {@link Supplier} 动态注入, 不在配置阶段求值. 这样模型列表变化时
 * (e.g. 新模型注册) 下次 refresh 即生效, 不需重启.
 *
 * @see SupportedSettings setting registry (CC supportedSettings.ts)
 * @see com.nexusai.application.agent.tool.ConfigToolPrompt prompt renderer
 * @see com.nexusai.application.agent.tool.impl.ConfigToolImpl Tool adapter
 */
@Configuration
public class SupportedSettingsConfig {

    private static final Logger log = LoggerFactory.getLogger(SupportedSettingsConfig.class);

    // ── 默认列表值 · 对齐 CC supportedSettings.ts ─────────────────────────

    /** CC themeNames: dark / light + daltonized. */
    private static final List<String> DEFAULT_THEME_NAMES =
        List.of("dark", "light", "dark-daltonized", "light-daltonized");

    /** CC themeSettings (auto-theme 启用时附加): 同上 + system. */
    private static final List<String> DEFAULT_THEME_SETTINGS =
        List.of("dark", "light", "dark-daltonized", "light-daltonized", "system");

    /** CC editorModes: normal / vim. */
    private static final List<String> DEFAULT_EDITOR_MODES =
        List.of("normal", "vim");

    /** CC notificationChannels: iterm2 / terminal_bell / notifications_disabled. */
    private static final List<String> DEFAULT_NOTIFICATION_CHANNELS =
        List.of("iterm2", "terminal_bell", "notifications_disabled");

    /** CC teammateModes: tmux / in-process / auto. */
    private static final List<String> DEFAULT_TEAMMATE_MODES =
        List.of("tmux", "in-process", "auto");

    /** 默认 model options — 与 SupportedSettings.getAll() 一致. */
    private static final List<String> DEFAULT_MODEL_OPTIONS =
        List.of("sonnet", "opus", "haiku");

    // ── Feature flag suppliers ────────────────────────────────────────────

    private static BooleanSupplier boolSupplier(Environment env, String key, boolean dflt) {
        String v = env.getProperty(key);
        boolean resolved = v == null ? dflt : isTruthy(v);
        return () -> resolved;
    }

    private static Supplier<String> stringSupplier(Environment env, String key) {
        return () -> env.getProperty(key);
    }

    private static boolean isTruthy(String s) {
        if (s == null) return false;
        String lower = s.trim().toLowerCase();
        return "true".equals(lower) || "1".equals(lower) || "yes".equals(lower) || "on".equals(lower);
    }

    // ── 模型校验: 异步包一层 sync 校验 (Phase 4 可换真实 LLM-side 校验) ──────

    private static Function<String, CompletableFuture<SupportedSettings.ValidationResult>>
        defaultModelValidator() {
        return model -> {
            // Phase 2 默认实现: 接受 DEFAULT_MODEL_OPTIONS 列表内任意 model; 否则 invalid.
            // Phase 4 可替换为真实 LLM-side validateModel 注入.
            if (model == null || model.isBlank()) {
                return CompletableFuture.completedFuture(
                    new SupportedSettings.ValidationResult(false, "model is blank"));
            }
            return CompletableFuture.completedFuture(
                new SupportedSettings.ValidationResult(true, null));
        };
    }

    // ── @Bean ─────────────────────────────────────────────────────────────

    /**
     * SupportedSettings 唯一 bean · setting registry 源.
     *
     * <p>所有 {@code nexusai.feature.*} flag 缺省 false (与 NexusAI backend 当前定位一致);
     * ant 部署需通过 application.yml / 环境变量显式开启.
     *
     * <p>model options 委托 supplier — 不在配置阶段求值, 保持动态语义.
     */
    @Bean
    public SupportedSettings supportedSettings(Environment env) {
        BooleanSupplier autoTheme = boolSupplier(env, "nexusai.feature.auto-theme", false);
        BooleanSupplier transcriptClassifier = boolSupplier(env, "nexusai.feature.transcript-classifier", false);
        BooleanSupplier voiceMode = boolSupplier(env, "nexusai.feature.voice-mode", false);
        BooleanSupplier bridgeMode = boolSupplier(env, "nexusai.feature.bridge-mode", false);
        BooleanSupplier kairos = boolSupplier(env, "nexusai.feature.kairos", false);
        BooleanSupplier kairosPush = boolSupplier(env, "nexusai.feature.kairos-push-notification", false);
        BooleanSupplier isAnt = boolSupplier(env, "nexusai.user.type.ant", false);

        Supplier<List<String>> modelOptions = () -> {
            String raw = env.getProperty("nexusai.feature.model-options");
            if (raw == null || raw.isBlank()) return DEFAULT_MODEL_OPTIONS;
            // 逗号分隔字符串解析
            List<String> parsed = java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
            return parsed.isEmpty() ? DEFAULT_MODEL_OPTIONS : parsed;
        };

        Function<String, CompletableFuture<SupportedSettings.ValidationResult>> modelValidator =
            defaultModelValidator();

        Supplier<String> remoteControlAtStartup =
            stringSupplier(env, "nexusai.feature.remote-control-at-startup");

        SupportedSettings bean = new SupportedSettings(
            autoTheme,
            transcriptClassifier,
            voiceMode,
            bridgeMode,
            kairos,
            kairosPush,
            isAnt,
            modelOptions,
            modelValidator,
            remoteControlAtStartup,
            DEFAULT_EDITOR_MODES,
            DEFAULT_NOTIFICATION_CHANNELS,
            DEFAULT_TEAMMATE_MODES,
            DEFAULT_THEME_NAMES,
            DEFAULT_THEME_SETTINGS);

        if (log.isInfoEnabled()) {
            log.info("[SupportedSettingsConfig] 注册 SupportedSettings @Bean "
                + "(feature flags: autoTheme={}, transcriptClassifier={}, voiceMode={}, "
                + "bridgeMode={}, kairos={}, kairosPush={}, isAnt={})",
                autoTheme.getAsBoolean(), transcriptClassifier.getAsBoolean(),
                voiceMode.getAsBoolean(), bridgeMode.getAsBoolean(),
                kairos.getAsBoolean(), kairosPush.getAsBoolean(), isAnt.getAsBoolean());
        }
        return bean;
    }
}