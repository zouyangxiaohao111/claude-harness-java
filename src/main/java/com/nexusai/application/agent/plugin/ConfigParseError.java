package com.nexusai.application.agent.plugin;

/**
 * 配置文件解析错误 · 对齐 CC {@code utils/errors.ts:39-49 ConfigParseError}.
 *
 * <pre>
 * export class ConfigParseError extends Error {
 *   filePath: string
 *   defaultConfig: unknown
 *   constructor(message, filePath, defaultConfig) { ...; this.name = 'ConfigParseError' }
 * }
 * </pre>
 *
 * <p>用于 known_marketplaces.json 等配置文件的损坏/非法解析。抛出该异常即表示<b>不得覆盖</b>
 * 损坏文件（load→mutate→save 路径必须用抛错版 load，Safe 版返回 {} 会导致保存把损坏文件
 * 覆盖成仅剩新条目，永久丢失用户其他条目 —— CC marketplaceManager.ts:301-308 注释）。
 *
 * <p>{@code defaultConfig} 保留 CC 的 defaultConfig 字段（本文件解析时该值恒为 null，
 * 后续模块如以缺省配置回退时填充）。
 */
public class ConfigParseError extends RuntimeException {

    /** 出问题的配置文件路径. */
    private final String filePath;
    /** 应使用的缺省配置（CC 字段；本模块不填 → null）. */
    private final Object defaultConfig;

    public ConfigParseError(String message, String filePath, Object defaultConfig) {
        super(message);
        this.filePath = filePath;
        this.defaultConfig = defaultConfig;
    }

    public String filePath() {
        return filePath;
    }

    public Object defaultConfig() {
        return defaultConfig;
    }
}
