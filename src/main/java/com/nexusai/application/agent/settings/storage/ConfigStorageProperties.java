package com.nexusai.application.agent.settings.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * [R32-b7a-2 Phase 3] ConfigStorage 路径配置 · 对齐 application.yml {@code nexusai.config.*}.
 *
 * <p>路径解析委托 {@link com.nexusai.application.agent.skill.NexusaiPaths#getAppConfigHomeDir()}
 * （{user.home}/.{appName}，appName 默认 nexusai）：global-file 默认
 * {@code {user.home}/.nexusai.json}，settings 文件默认 {@code {user.home}/.{appName}/settings.json}（动态根）。
 *
 * <p>Phase 3 文件 — Phase 5 在 application.yml 写入默认值;Phase 1/2 不引用本类.
 *
 * <p>注: 显式声明 getter 方法, 避免 lombok {@code @Data} 与 {@code @Configuration}
 * 已知冲突 (1b12dc0 修复不完整, 编译失败).
 *
 * @see FileConfigStorage implementation
 */
@Configuration
@ConfigurationProperties(prefix = "nexusai.config")
public class ConfigStorageProperties {
    /** global source 持久化路径 (e.g. {@code ~/.nexusai.json}). */
    private String globalFile;
    /** settings source 持久化路径 (e.g. {@code ~/.nexusai/settings.json}). */
    private SettingsFile settingsFile;

    /**
     * b7a-2 测试兼容构造器: R32B7a2_FileConfigStorageTest 用 new ConfigStorageProperties(globalFile, settingsFile).
     * 保留 2-arg 构造器, 保证 b7a-2 单测不破坏.
     */
    public ConfigStorageProperties(String globalFile, SettingsFile settingsFile) {
        this.globalFile = globalFile;
        this.settingsFile = settingsFile;
    }

    /** Spring 注入用无参构造器 (1b12dc0 record → class 必需). */
    public ConfigStorageProperties() {
    }

    public String getGlobalFile() {
        return globalFile;
    }

    public void setGlobalFile(String globalFile) {
        this.globalFile = globalFile;
    }

    public SettingsFile getSettingsFile() {
        return settingsFile;
    }

    public void setSettingsFile(SettingsFile settingsFile) {
        this.settingsFile = settingsFile;
    }

    /** nested settings source 配置 (便于 yml 嵌套). */
    public record SettingsFile(String path) {
        public SettingsFile {
            if (path == null) path = "settings.json";
        }
    }
}