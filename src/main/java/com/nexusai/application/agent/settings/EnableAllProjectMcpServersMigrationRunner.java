package com.nexusai.application.agent.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.settings.EnableAllProjectMcpServersMigration.LocalSettings;
import com.nexusai.application.agent.settings.EnableAllProjectMcpServersMigration.ProjectConfig;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * EnableAllProjectMcpServersMigration 启动期接线 · 对齐 CC main.tsx runMigrations（:326-345/:950）.
 *
 * <p>L1 语义: 应用启动时执行 MCP server 审批字段迁移（projectConfig 3 字段
 * enableAllProjectMcpServers / enabledMcpjsonServers / disabledMcpjsonServers → localSettings），
 * 保旧设置平滑迁移。migrate() 幂等（3 字段全缺 no-op）+ 异常不抛 → 每次启动无条件调用安全
 * （对齐 CC migrateEnableAllProjectMcpServersToSettings.ts:17-118，异常 logError + logEvent error
 * 不抛，不阻断启动）。
 *
 * <p>L2 契约:
 * <ul>
 *   <li>存储通道: FileConfigStorage settings.json 顶层 key {@code projectConfig}（迁移源）与
 *       {@code localSettings}（迁移目标）两个嵌套对象。Java 端用同一个 settings.json 表达
 *       CC 的 project settings 与 local settings 两源。</li>
 *   <li><b>projectConfigReader</b>: 读 projectConfig 3 审批字段 → {@link ProjectConfig}
 *       （其它字段进 otherFields 保留）。</li>
 *   <li><b>projectConfigSaver</b>: migrate() 剔除被迁移字段后写回 projectConfig
 *       （只剔除被迁移字段，其余字段原样保留，对齐 CC configWithoutFields）。</li>
 *   <li><b>localSettingsReader / settingsUpdater</b>: localSettings 读 + merge 写回。</li>
 *   <li><b>eventLogger / errorLogger</b>: 数据流日志（migrate 成功/失败中文 log）。</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS getCurrentProjectConfig/getSettingsForSource → FileConfigStorage
 * readSettings；TS saveCurrentProjectConfig/updateSettingsForSource → FileConfigStorage
 * writeSettings；migrate() 本身已注入式实现（6 slot），本类只负责装配 + 启动触发。
 */
@Component
public class EnableAllProjectMcpServersMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnableAllProjectMcpServersMigrationRunner.class);

    /** settings.json 顶层 key · project config（迁移源，对齐 CC getCurrentProjectConfig）。 */
    private static final String PROJECT_CONFIG = "projectConfig";
    /** settings.json 顶层 key · localSettings（迁移目标，对齐 CC getSettingsForSource('localSettings')）。 */
    private static final String LOCAL_SETTINGS = "localSettings";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileConfigStorage storage;

    public EnableAllProjectMcpServersMigrationRunner(FileConfigStorage storage) {
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        EnableAllProjectMcpServersMigration migration = new EnableAllProjectMcpServersMigration(
            this::readProjectConfig,
            this::saveProjectConfig,
            this::readLocalSettings,
            this::updateLocalSettings,
            event -> log.info("[McpApprovalMigration] 迁移事件: {}", event),
            err -> log.error("[McpApprovalMigration] 迁移失败: {}", String.valueOf(err)));
        try {
            migration.migrate();
            log.info("[McpApprovalMigration] 启动迁移执行完成（幂等，3 字段全缺 no-op）");
        } catch (Exception e) {
            // migrate() 内部已 catch 不抛；此处兜底 fail-soft（对齐 CC 迁移异常不抛、启动不失败）
            log.error("[McpApprovalMigration] 启动迁移异常（fail-soft 不阻断启动）: {}", e.getMessage(), e);
        }
    }

    /** projectConfigReader · 读 projectConfig 3 审批字段 → ProjectConfig（其它字段进 otherFields）。 */
    private ProjectConfig readProjectConfig() {
        Map<String, Object> obj = readObject(PROJECT_CONFIG);
        return new ProjectConfig(
            readBoolean(obj, EnableAllProjectMcpServersMigration.FIELD_ENABLE_ALL),
            readStringList(obj, EnableAllProjectMcpServersMigration.FIELD_ENABLED_SERVERS),
            readStringList(obj, EnableAllProjectMcpServersMigration.FIELD_DISABLED_SERVERS),
            minusFields(obj, EnableAllProjectMcpServersMigration.FIELD_ENABLE_ALL,
                EnableAllProjectMcpServersMigration.FIELD_ENABLED_SERVERS,
                EnableAllProjectMcpServersMigration.FIELD_DISABLED_SERVERS));
    }

    /** projectConfigSaver · migrate() 剔除被迁移字段后写回 projectConfig（其余字段保留）。 */
    private void saveProjectConfig(UnaryOperator<ProjectConfig> op) {
        ProjectConfig next = op.apply(readProjectConfig());
        Map<String, Object> stored = new LinkedHashMap<>();
        if (next.enableAllProjectMcpServers() != null) {
            stored.put(EnableAllProjectMcpServersMigration.FIELD_ENABLE_ALL,
                next.enableAllProjectMcpServers());
        }
        if (next.enabledMcpjsonServers() != null) {
            stored.put(EnableAllProjectMcpServersMigration.FIELD_ENABLED_SERVERS,
                next.enabledMcpjsonServers());
        }
        if (next.disabledMcpjsonServers() != null) {
            stored.put(EnableAllProjectMcpServersMigration.FIELD_DISABLED_SERVERS,
                next.disabledMcpjsonServers());
        }
        if (next.otherFields() != null) {
            stored.putAll(next.otherFields());
        }
        storage.writeSettings(List.of(PROJECT_CONFIG), stored);
        if (log.isDebugEnabled()) {
            log.debug("[McpApprovalMigration] projectConfig 写回完成, 字段数={}", stored.size());
        }
    }

    /** localSettingsReader · 读 localSettings 3 字段 → LocalSettings。 */
    private LocalSettings readLocalSettings() {
        Map<String, Object> obj = readObject(LOCAL_SETTINGS);
        return new LocalSettings(
            readBoolean(obj, EnableAllProjectMcpServersMigration.FIELD_ENABLE_ALL),
            readStringList(obj, EnableAllProjectMcpServersMigration.FIELD_ENABLED_SERVERS),
            readStringList(obj, EnableAllProjectMcpServersMigration.FIELD_DISABLED_SERVERS));
    }

    /** settingsUpdater · 更新 localSettings 对象（merge）。 */
    private void updateLocalSettings(String source, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Map<String, Object> current = readObject(LOCAL_SETTINGS);
        current.putAll(updates);
        storage.writeSettings(List.of(LOCAL_SETTINGS), current);
        if (log.isDebugEnabled()) {
            log.debug("[McpApprovalMigration] localSettings 更新完成: {}", updates.keySet());
        }
    }

    /** 读 settings.json 顶层 object（projectConfig / localSettings）。 */
    private Map<String, Object> readObject(String topKey) {
        Object v = storage.readSettings(List.of(topKey));
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            return new LinkedHashMap<>(typed);
        }
        if (v instanceof JsonNode n && n.isObject()) {
            return new LinkedHashMap<>(MAPPER.convertValue(n, Map.class));
        }
        return new LinkedHashMap<>();
    }

    private static Boolean readBoolean(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Boolean b ? b : null;
    }

    private static List<String> readStringList(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return null;
    }

    private static Map<String, Object> minusFields(Map<String, Object> obj, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>(obj);
        for (String k : keys) {
            out.remove(k);
        }
        return out;
    }
}
