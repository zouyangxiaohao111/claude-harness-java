package com.nexusai.application.agent.settings;

import com.nexusai.application.agent.skill.NexusaiPaths;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings sync API 类型 · 对齐 CC services/settingsSync/types.ts.
 *
 * <p>L1 语义: UserSyncData = (userId, version, lastModified, checksum, content{entries}); 含 5 字段 fetch + upload result.
 *
 * <p>L3 (Java idiom): TS `z.record(z.string(), z.string())` → `Map<String,String>`; ISO timestamp → `String`.
 */
public final class SettingsSyncTypes {

    private SettingsSyncTypes() {}

    /** CC UserSyncContentSchema — entries map (key → UTF-8 string content). */
    public record UserSyncContent(Map<String, String> entries) {
        public static UserSyncContent empty() {
            return new UserSyncContent(Map.of());
        }
    }

    /** CC UserSyncDataSchema — 5 字段 GET /user_settings 响应. */
    public record UserSyncData(
        String userId,
        long version,
        String lastModified,    // ISO 8601 timestamp
        String checksum,        // MD5 hash
        UserSyncContent content
    ) {}

    /** CC SettingsSyncFetchResult — 6 字段 fetch 结果. */
    public record SettingsSyncFetchResult(
        boolean success,
        UserSyncData data,
        Boolean isEmpty,        // true if 404 (no data exists)
        String error,
        Boolean skipRetry
    ) {}

    /** CC SettingsSyncUploadResult — 4 字段 upload 结果. */
    public record SettingsSyncUploadResult(
        boolean success,
        String checksum,
        String lastModified,
        String error
    ) {}

    /** CC SYNC_KEYS — 4 个 sync entry key (USER_SETTINGS / USER_MEMORY / projectSettings / projectMemory). */
    public static String userSettingsKey() {
        return "~/" + NexusaiPaths.getAppName() + "/settings.json";
    }

    public static String userMemoryKey() {
        return "~/" + NexusaiPaths.getAppName() + "/CLAUDE.md";
    }

    /**
     * 项目 settings 同步键 · <b>[决策 D2 口径]</b>：项目 nexusai settings 采用项目内
     * {@code .nexusai} 布局（NexusaiPaths.getProjectDirName()），同步键随之改为
     * {@code projects/{id}/.nexusai/settings.local.json}（键仅作同步标识，D2 不导入/不读取
     * claude settings）。
     */
    public static String projectSettingsKey(String projectId) {
        // [T3/#21] .nexusai → 动态 appName（决策 D1/D6）：同步标识随 appName 联动
        return "projects/" + projectId + "/" + NexusaiPaths.getProjectDirName() + "/settings.local.json";
    }

    public static String projectMemoryKey(String projectId) {
        return "projects/" + projectId + "/CLAUDE.local.md";
    }

    /** 工具: 用 entries + 其他字段构造空 UserSyncData 模板 (测试用). */
    public static Map<String, String> sampleEntries() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("model", "opus");
        e.put("theme", "dark");
        return e;
    }
}