package com.nexusai.application.agent.settings.storage;

import java.util.List;

/**
 * [R32-b7a-2 Phase 1/3] ConfigTool 持久化 facade 接口 · 对齐 CC
 * {@code utils/settings/saveGlobalConfig} + {@code utils/settings/updateSettingsForSource}.
 *
 * <p>CC 行为拆解 (CC ConfigTool.ts:264-298):
 * <ul>
 *   <li><b>global source</b> (e.g. theme, verbose, autoCompactEnabled, fileCheckpointingEnabled):
 *       写入 {@code ~/.nexusai.json} 顶层 key.</li>
 *   <li><b>settings source</b> (e.g. model, permissions.defaultMode, language, autoMemoryEnabled):
 *       写入 {@code settings.json} 嵌套路径 (e.g. {@code ["permissions","defaultMode"]}).</li>
 * </ul>
 *
 * <h2>Phase 边界</h2>
 * <ul>
 *   <li><b>Phase 1 (本 PR)</b>: 仅定义 interface,ConfigToolImpl 引用类型.
 *       <b>impl 不在本 PR — Phase 3 创建 FileConfigStorage.</b></li>
 *   <li><b>Phase 3</b>: FileConfigStorage 实现: Jackson tree merge / 原子写 / 写锁 /
 *       cache invalidation / round-trip 保证.</li>
 * </ul>
 *
 * <h2>区分 absent 与 JSON null</h2>
 * <ul>
 *   <li>{@link #readGlobal(String)} 返回 {@code null} ⇔ key 缺失 (absent).</li>
 *   <li>{@link #readSettings(List)} 返回 {@code null} ⇔ path 缺失 (absent);返回 {@link NullMarker}
 *       ⇔ key 存在但值为 JSON null.</li>
 * </ul>
 *
 * <h2>不变量 (供 Phase 3 impl 遵守)</h2>
 * <ul>
 *   <li>write 后立即 read 必须返回 newValue (round-trip).</li>
 *   <li>concurrent write 必须串行化 (ReentrantLock).</li>
 *   <li>写失败 (磁盘满/权限) 必须抛 {@link ConfigStorageException},不静默吞.</li>
 * </ul>
 *
 * @see com.nexusai.application.agent.tool.ConfigTool 核心契约 (CC ConfigTool.ts:111-411)
 */
public interface ConfigStorage {

    /** Marker for JSON null (path 存在但值为 null). */
    Object NullMarker = new Object() {
        @Override
        public String toString() { return "<null>"; }
    };

    /**
     * 读取 global 配置 (e.g. {@code ~/.nexusai.json}) 顶层 key.
     *
     * @param key global 配置顶层 key (e.g. {@code "theme"}, {@code "verbose"})
     * @return 当前值;null = absent (key 不存在). CC ConfigTool.ts:180 getValue 路径.
     */
    Object readGlobal(String key);

    /**
     * 写入 global 配置. <b>CC saveGlobalConfig 全替换</b> — Java 端采用 merge-on-write 语义
     * (避免覆盖其他 setting).
     *
     * @param key   global 配置 key
     * @param value 新值 (null = 显式置 JSON null)
     * @throws ConfigStorageException 写失败
     */
    void writeGlobal(String key, Object value);

    /**
     * 删除 global 配置 key. 对齐 CC saveGlobalConfig 删 key 行为.
     *
     * @param key 要移除的 key
     * @throws ConfigStorageException 写失败
     */
    void unsetGlobal(String key);

    /**
     * 读取嵌套 path 配置 (e.g. {@code ["permissions","defaultMode"]}).
     *
     * @param path JSON 嵌套路径 (非空,至少 1 段)
     * @return 当前值;{@link #NullMarker} = JSON null;{@code null} = absent (path 不存在)
     * @throws ConfigStorageException 读取失败 (除 absent 外)
     */
    Object readSettings(List<String> path);

    /**
     * 写入嵌套 path 配置. Jackson tree merge 语义 — 不影响同级其他 path.
     *
     * @param path  嵌套路径 (非空)
     * @param value 新值 (null = 显式置 JSON null)
     * @throws ConfigStorageException 写失败
     */
    void writeSettings(List<String> path, Object value);

    /**
     * 删除嵌套 path 配置 key (path 整段移除).
     *
     * @param path 要移除的路径
     * @throws ConfigStorageException 写失败
     */
    void unsetSettings(List<String> path);

    /**
     * 写后通知 — 供 cache invalidation (Phase 3 FileConfigStorage 触发).
     * ConfigToolImpl 不直接调用;可被配置监听器消费.
     *
     * <p>Phase 3 impl 应在 write/unset 完成后回调;Phase 1 仅声明契约.
     *
     * @param listener 监听器 (非 null)
     */
    void addChangeListener(ConfigChangeListener listener);

    /**
     * 移除监听器.
     *
     * @param listener 待移除监听器
     */
    void removeChangeListener(ConfigChangeListener listener);

    /** 写后变更事件. */
    record ConfigChange(String source, List<String> path, Object value) {
        public ConfigChange {
            source = source == null ? "" : source;
            path = path == null ? List.of() : List.copyOf(path);
        }
    }

    /** 变更监听器 (functional interface). */
    @FunctionalInterface
    interface ConfigChangeListener {
        void onChange(ConfigChange change);
    }

    /** 持久化异常 — 写失败 / 读失败 (除 absent). */
    class ConfigStorageException extends RuntimeException {
        public ConfigStorageException(String message, Throwable cause) {
            super(message, cause);
        }
        public ConfigStorageException(String message) {
            super(message);
        }
    }
}