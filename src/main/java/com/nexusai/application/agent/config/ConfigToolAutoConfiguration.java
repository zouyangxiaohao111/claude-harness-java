package com.nexusai.application.agent.config;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.settings.SupportedSettings;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.tool.ConfigToolPrompt;
import com.nexusai.application.agent.tool.impl.ConfigToolImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * [R32-b7b-2 Phase 5] ConfigTool Spring 自动配置 · 条件注册.
 *
 * <h2>条件注册</h2>
 * <p>单条件 {@code @ConditionalOnProperty} 守卫 — 仅当 {@code nexusai.user.type=ant}
 * (ant build profile) 时激活. 对齐 CC {@code tools.ts:214}
 * {@code ...(process.env.USER_TYPE === 'ant' ? [ConfigTool] : [])} — 无额外 opt-in,
 * ant 部署即暴露 (OPD-12 用户已拍板).
 *
 * <h2>依赖</h2>
 * <p>所有 {@code @Bean} 依赖 {@link SupportedSettings} + {@link FileConfigStorage} —
 * 由 R7a-2 wiring 保证 (Phase 2/3 注入).
 *
 * <h2>注册的 Bean</h2>
 * <ul>
 *   <li>{@link ConfigToolPrompt} — 提示词渲染器 (依赖 SupportedSettings)</li>
 *   <li>{@link BiConsumer}{@code <PermissionMode, String>} — permissions.defaultMode
 *       写入时的即时同步 sink (依赖 LlmAgentLoop 可空)</li>
 *   <li>{@link ConfigToolImpl} — ConfigTool 主 bean (Phase 4 全参构造)</li>
 * </ul>
 *
 * @see ConfigToolImpl
 * @see ConfigToolPrompt
 * @see SupportedSettings
 * @see FileConfigStorage
 */
@Configuration
@ConditionalOnProperty(prefix = "nexusai.user", name = "type", havingValue = "ant")
public class ConfigToolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConfigToolAutoConfiguration.class);

    @Bean
    public ConfigToolPrompt configToolPrompt(SupportedSettings supportedSettings) {
        log.info("[ConfigToolAutoConfiguration] 注册 ConfigToolPrompt (ant-only)");
        Supplier<List<ConfigToolPrompt.ModelOption>> modelOptionsSupplier = () -> {
            if (supportedSettings == null) return ConfigToolPrompt.defaultModelOptions();
            List<String> raw = supportedSettings.getOptionsForSetting("model");
            if (raw == null || raw.isEmpty()) return ConfigToolPrompt.defaultModelOptions();
            return raw.stream()
                .map(v -> new ConfigToolPrompt.ModelOption(v, "model: " + v, null))
                .toList();
        };
        return new ConfigToolPrompt(supportedSettings, modelOptionsSupplier);
    }

    @Bean
    public BiConsumer<PermissionMode, String> permissionModeSync(
            @org.springframework.beans.factory.annotation.Autowired(required = false) LlmAgentLoop llmAgentLoop) {
        return (mode, source) -> {
            if (llmAgentLoop != null) {
                llmAgentLoop.setDefaultPermissionMode(mode, source);
            }
        };
    }

    @Bean
    public ConfigToolImpl configTool(SupportedSettings supportedSettings,
                                     FileConfigStorage configStorage,
                                     ConfigToolPrompt configToolPrompt) {
        log.info("[ConfigToolAutoConfiguration] 注册 ConfigToolImpl (ant-only, 对齐 CC tools.ts:214 USER_TYPE=ant)");
        return new ConfigToolImpl(supportedSettings, configStorage, configToolPrompt);
    }
}
