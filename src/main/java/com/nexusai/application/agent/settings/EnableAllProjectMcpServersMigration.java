package com.nexusai.application.agent.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server approval fields migration · 对齐 CC migrations/migrateEnableAllProjectMcpServersToSettings.ts.
 *
 * <p>L1 语义: 把 MCP server approval 字段从 projectConfig 迁移到 localSettings (更好的管理一致性).
 *            - enableAllProjectMcpServers → settings (如缺) + remove from project
 *            - enabledMcpjsonServers → merge 去重 + remove from project
 *            - disabledMcpjsonServers → merge 去重 + remove from project
 *            3 字段都缺 → no-op.
 *            异常 → logError + logEvent error, 不抛 (不阻断启动).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: migrateEnableAllProjectMcpServersToSettings() → void;
 *       ProjectConfig 3 字段 + LocalSettings 3 字段;无新字段 → no-op;</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 读 projectConfig → 检查 3 字段是否存 →
 *       (缺) → return; (有) → 读 existingSettings → 构造 updates (去重 merge) →
 *       updateSettingsForSource + saveCurrentProjectConfig (剔除) + logEvent success.</li>
 *   <li><b>A3</b>: 状态: NO_FIELDS → UPDATING → DONE / FAILED (catch).</li>
 *   <li><b>A4</b>: enableAll 已迁移 → 不重复写 settings 但仍 remove from project;
 *       enabledMcpjsonServers 重复 → Set 去重;
 *       异常 → logError + logEvent error (e_xxx).</li>
 *   <li><b>A5</b>: 真实场景 — 老 project config 有 enableAllProjectMcpServers=true →
 *       迁移到 localSettings + 从 project config 删除 + logEvent success.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `getCurrentProjectConfig()` → 注入式 Supplier;
 *                    TS `saveCurrentProjectConfig(c => ...)` → 注入式 Consumer&lt;UnaryOperator&gt;;
 *                    TS `getSettingsForSource('localSettings')` → 注入式 SettingsReader;
 *                    TS `updateSettingsForSource` → 注入式 SettingsUpdater;
 *                    TS `logEvent` → 注入式 EventLogger;
 *                    TS `logError` → 注入式 ErrorLogger.
 */
public final class EnableAllProjectMcpServersMigration {

    private static final Logger log = LoggerFactory.getLogger(EnableAllProjectMcpServersMigration.class);
    private static final String LOCAL_SETTINGS = "localSettings";
    private static final String EVENT_SUCCESS = "tengu_migrate_mcp_approval_fields_success";
    private static final String EVENT_ERROR = "tengu_migrate_mcp_approval_fields_error";

    public static final String FIELD_ENABLE_ALL = "enableAllProjectMcpServers";
    public static final String FIELD_ENABLED_SERVERS = "enabledMcpjsonServers";
    public static final String FIELD_DISABLED_SERVERS = "disabledMcpjsonServers";

    private final Supplier<ProjectConfig> projectConfigReader;
    private final Consumer<UnaryOperator<ProjectConfig>> projectConfigSaver;
    private final Supplier<LocalSettings> localSettingsReader;
    private final SettingsUpdater settingsUpdater;
    private final Consumer<String> eventLogger;
    private final Consumer<Throwable> errorLogger;

    public EnableAllProjectMcpServersMigration(Supplier<ProjectConfig> projectConfigReader,
                                                Consumer<UnaryOperator<ProjectConfig>> projectConfigSaver,
                                                Supplier<LocalSettings> localSettingsReader,
                                                SettingsUpdater settingsUpdater,
                                                Consumer<String> eventLogger,
                                                Consumer<Throwable> errorLogger) {
        this.projectConfigReader = Objects.requireNonNull(projectConfigReader);
        this.projectConfigSaver = Objects.requireNonNull(projectConfigSaver);
        this.localSettingsReader = Objects.requireNonNull(localSettingsReader);
        this.settingsUpdater = Objects.requireNonNull(settingsUpdater);
        this.eventLogger = Objects.requireNonNull(eventLogger);
        this.errorLogger = Objects.requireNonNull(errorLogger);
    }

    /** Project config (CC 最小子集 + 其余字段保留 · 对齐 configWithoutFields 对象展开). */
    public record ProjectConfig(
        Boolean enableAllProjectMcpServers,
        List<String> enabledMcpjsonServers,
        List<String> disabledMcpjsonServers,
        Map<String, Object> otherFields
    ) {
        public static final ProjectConfig EMPTY = new ProjectConfig(null, null, null, Map.of());
    }

    /** Local settings (CC 最小子集). */
    public record LocalSettings(
        Boolean enableAllProjectMcpServers,
        List<String> enabledMcpjsonServers,
        List<String> disabledMcpjsonServers
    ) {
        public static final LocalSettings EMPTY = new LocalSettings(null, null, null);
    }

    @FunctionalInterface
    public interface SettingsUpdater {
        void update(String source, Map<String, Object> updates);
    }

    @FunctionalInterface
    public interface Consumer<T> { void accept(T t); }

    /** CC migrateEnableAllProjectMcpServersToSettings — 主链. */
    public void migrate() {
        ProjectConfig projectConfig = projectConfigReader.get();

        boolean hasEnableAll = projectConfig.enableAllProjectMcpServers() != null;
        boolean hasEnabledServers = projectConfig.enabledMcpjsonServers() != null
            && !projectConfig.enabledMcpjsonServers().isEmpty();
        boolean hasDisabledServers = projectConfig.disabledMcpjsonServers() != null
            && !projectConfig.disabledMcpjsonServers().isEmpty();

        if (!hasEnableAll && !hasEnabledServers && !hasDisabledServers) {
            return;
        }

        try {
            LocalSettings existing = localSettingsReader.get();
            Map<String, Object> updates = new LinkedHashMap<>();
            List<String> fieldsToRemove = new java.util.ArrayList<>();

            // 1. enableAllProjectMcpServers
            if (hasEnableAll) {
                if (existing.enableAllProjectMcpServers() == null) {
                    updates.put(FIELD_ENABLE_ALL, projectConfig.enableAllProjectMcpServers());
                }
                fieldsToRemove.add(FIELD_ENABLE_ALL);
            }

            // 2. enabledMcpjsonServers (去重 merge)
            if (hasEnabledServers) {
                List<String> existingEnabled = existing.enabledMcpjsonServers() != null
                    ? existing.enabledMcpjsonServers() : List.of();
                java.util.Set<String> merged = new java.util.LinkedHashSet<>(existingEnabled);
                merged.addAll(projectConfig.enabledMcpjsonServers());
                updates.put(FIELD_ENABLED_SERVERS, new java.util.ArrayList<>(merged));
                fieldsToRemove.add(FIELD_ENABLED_SERVERS);
            }

            // 3. disabledMcpjsonServers (去重 merge)
            if (hasDisabledServers) {
                List<String> existingDisabled = existing.disabledMcpjsonServers() != null
                    ? existing.disabledMcpjsonServers() : List.of();
                java.util.Set<String> merged = new java.util.LinkedHashSet<>(existingDisabled);
                merged.addAll(projectConfig.disabledMcpjsonServers());
                updates.put(FIELD_DISABLED_SERVERS, new java.util.ArrayList<>(merged));
                fieldsToRemove.add(FIELD_DISABLED_SERVERS);
            }

            if (!updates.isEmpty()) {
                settingsUpdater.update(LOCAL_SETTINGS, updates);
            }

            // 从 project config 移除已迁移字段 · 对齐 CC configWithoutFields（config.ts:98-105
            // 「...configWithoutFields = current minus 3 fields」）：只剔除被迁移字段，其余字段原样保留
            // （旧实现只重建 3 已知字段 → 丢其它字段，违反 CC 对象展开语义）。
            projectConfigSaver.accept(current -> {
                Map<String, Object> other = new LinkedHashMap<>(current.otherFields() == null
                    ? Map.of() : current.otherFields());
                // 被迁移的字段不放进 other（CC 从对象中剔除）
                if (fieldsToRemove.contains(FIELD_ENABLE_ALL)) other.remove(FIELD_ENABLE_ALL);
                if (fieldsToRemove.contains(FIELD_ENABLED_SERVERS)) other.remove(FIELD_ENABLED_SERVERS);
                if (fieldsToRemove.contains(FIELD_DISABLED_SERVERS)) other.remove(FIELD_DISABLED_SERVERS);
                // 未迁移字段保留在原位（对应 CC 展开后仍存在的字段）
                Boolean keepEnableAll = current.enableAllProjectMcpServers() != null
                    && !fieldsToRemove.contains(FIELD_ENABLE_ALL)
                    ? current.enableAllProjectMcpServers() : null;
                List<String> keepEnabled = current.enabledMcpjsonServers() != null
                    && !fieldsToRemove.contains(FIELD_ENABLED_SERVERS)
                    ? current.enabledMcpjsonServers() : null;
                List<String> keepDisabled = current.disabledMcpjsonServers() != null
                    && !fieldsToRemove.contains(FIELD_DISABLED_SERVERS)
                    ? current.disabledMcpjsonServers() : null;
                return new ProjectConfig(keepEnableAll, keepEnabled, keepDisabled, other);
            });

            eventLogger.accept(EVENT_SUCCESS);
        } catch (Exception e) {
            log.error("[McpApprovalMigration] failed: {}", e.getMessage());
            errorLogger.accept(e);
            eventLogger.accept(EVENT_ERROR);
        }
    }
}
