package com.nexusai.application.agent.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * RES-03 · EnableAllProjectMcpServersMigration 启动期接线测试.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC main.tsx runMigrations 启动迁移——应用启动时把老 project
 * config 的 3 个 MCP 审批字段（enableAllProjectMcpServers / enabledMcpjsonServers /
 * disabledMcpjsonServers）平滑迁移到 localSettings，并剔除出 project config（其余字段保留）；
 * 迁移幂等（3 字段全缺 no-op）；异常 fail-soft 不阻断启动（CC
 * migrateEnableAllProjectMcpServersToSettings.ts:113-117 logError + logEvent error 不抛）。
 */
class EnableAllProjectMcpServersMigrationStartupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROJECT_CONFIG = "projectConfig";
    private static final String LOCAL_SETTINGS = "localSettings";

    @TempDir
    Path tmpDir;

    private String originalUserHome;

    /** 决策 D1：FileConfigStorage 单参（ConfigStorageProperties），缺省路径 = user.home 派生。
     *  覆写 user.home 隔离测试写盘（防污染真实 ~/.nexusai），用后恢复。 */
    @BeforeEach
    void isolateUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tmpDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private FileConfigStorage newStorage() {
        return new FileConfigStorage(null);
    }

    /** 预置 project config（含 3 审批字段 + 1 个无关字段）。 */
    private static Map<String, Object> oldProjectConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enableAllProjectMcpServers", true);
        cfg.put("enabledMcpjsonServers", List.of("a", "b"));
        cfg.put("disabledMcpjsonServers", List.of("blocked"));
        cfg.put("unrelatedSetting", "keep-me");
        return cfg;
    }

    /** 读 settings.json 顶层 object（projectConfig / localSettings）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(FileConfigStorage storage, String topKey) {
        Object v = storage.readSettings(List.of(topKey));
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (v instanceof JsonNode n && n.isObject()) {
            return MAPPER.convertValue(n, Map.class);
        }
        return Map.of();
    }

    @Test
    @DisplayName("老 project config 3 审批字段 → 迁移到 localSettings + project config 剔除（其余字段保留）")
    void oldProjectFieldsMigratedToLocalSettings() {
        FileConfigStorage storage = newStorage();
        storage.writeSettings(List.of(PROJECT_CONFIG), oldProjectConfig());

        new EnableAllProjectMcpServersMigrationRunner(storage).run(null);

        Map<String, Object> local = readMap(storage, LOCAL_SETTINGS);
        assertThat(local.get("enableAllProjectMcpServers"))
            .as("enableAll 字段必须迁移进 localSettings")
            .isEqualTo(true);
        assertThat(local.get("enabledMcpjsonServers"))
            .as("enabledMcpjsonServers 必须去重 merge 进 localSettings")
            .isEqualTo(List.of("a", "b"));
        assertThat(local.get("disabledMcpjsonServers"))
            .as("disabledMcpjsonServers 必须 merge 进 localSettings")
            .isEqualTo(List.of("blocked"));

        Map<String, Object> project = readMap(storage, PROJECT_CONFIG);
        assertThat(project)
            .as("被迁移字段必须从 project config 剔除（对齐 CC configWithoutFields）")
            .doesNotContainKeys("enableAllProjectMcpServers", "enabledMcpjsonServers", "disabledMcpjsonServers");
        assertThat(project.get("unrelatedSetting"))
            .as("未迁移字段必须原样保留（旧实现只重建 3 已知字段 → 丢其它字段）")
            .isEqualTo("keep-me");
    }

    @Test
    @DisplayName("幂等：无迁移字段 → no-op，二次执行结果不变")
    void noFieldsIsNoOpIdempotent() {
        FileConfigStorage storage = newStorage();
        // 只有无关字段，无 3 审批字段 → migrate() 必须 no-op，不写 localSettings
        storage.writeSettings(List.of(PROJECT_CONFIG), Map.of("unrelatedSetting", "keep-me"));

        EnableAllProjectMcpServersMigrationRunner runner = new EnableAllProjectMcpServersMigrationRunner(storage);
        runner.run(null);
        Map<String, Object> localAfterFirst = readMap(storage, LOCAL_SETTINGS);
        assertThat(localAfterFirst)
            .as("无审批字段 → 不产生 localSettings（no-op，对齐 CC :29-31）")
            .isEmpty();

        runner.run(null);
        assertThat(readMap(storage, LOCAL_SETTINGS))
            .as("二次执行结果不变（幂等）")
            .isEqualTo(localAfterFirst);
    }

    @Test
    @DisplayName("异常 fail-soft：持久化失败 → 启动不失败（migrate 内部 catch 不抛）")
    void storageFailureDoesNotBreakStartup() {
        FileConfigStorage normal = newStorage();
        normal.writeSettings(List.of(PROJECT_CONFIG), oldProjectConfig());
        // 写路径抛异常（模拟磁盘满 / 权限），读路径仍正常（同一 tmp dir）—— migrate 必须
        // 捕获并转 errorLogger/EVENT_ERROR，不向启动抛出（CC :113-117）。
        FileConfigStorage throwing = new FileConfigStorage(null) {
            @Override
            public void writeSettings(List<String> path, Object value) {
                throw new RuntimeException("disk full");
            }
        };

        assertThatCode(() -> new EnableAllProjectMcpServersMigrationRunner(throwing).run(null))
            .as("迁移异常被捕获 → run() 不抛，启动不失败（fail-soft）")
            .doesNotThrowAnyException();
    }
}
