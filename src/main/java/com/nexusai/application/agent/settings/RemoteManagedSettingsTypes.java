package com.nexusai.application.agent.settings;

import java.util.Map;

/**
 * Remote managed settings 响应类型 · 对齐 CC services/remoteManagedSettings/types.ts.
 *
 * <p>L1 语义: 远程托管 settings — 含 uuid (settings UUID) + checksum + settings dict (key → unknown value).
 *            用 permissive z.record() 避免循环依赖, 完整 validation 在 index.ts 用 SettingsSchema.safeParse 后做.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: RemoteManagedSettingsResponse 3 字段 + RemoteManagedSettingsFetchResult 5 字段</li>
 *   <li><b>A2 Golden Trace</b>: success=true + settings 不为 null → 有效 settings; null settings 表示 304 Not Modified</li>
 *   <li><b>A3</b>: record 不可变; settings 嵌套 Map&lt;String,Object&gt; (CC `z.record(z.string(), z.unknown())`)</li>
 *   <li><b>A4</b>: skipRetry=true 用于 auth error 等不再重试场景</li>
 *   <li><b>A5</b>: 真实响应解析 — {uuid, checksum, settings: {key1: val1}} → 完整 record</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `z.record(z.string(), z.unknown()) as z.ZodType&lt;SettingsJson&gt;` → Java `Map&lt;String,Object&gt;`;
 *                    `SettingsJson` 在 Java 端由 settings 包定义; uuid/checksum String.
 */
public final class RemoteManagedSettingsTypes {

    private RemoteManagedSettingsTypes() {}

    /** CC RemoteManagedSettingsResponse — uuid + checksum + settings dict. */
    public record RemoteManagedSettingsResponse(
        String uuid,
        String checksum,
        Map<String, Object> settings
    ) {}

    /** CC RemoteManagedSettingsFetchResult — 5 字段 fetch 结果. */
    public record RemoteManagedSettingsFetchResult(
        boolean success,
        Map<String, Object> settings,  // null 表示 304 Not Modified (cache valid)
        String checksum,
        String error,
        Boolean skipRetry               // true 不再重试 (auth error 等)
    ) {
        public static RemoteManagedSettingsFetchResult notModified(String checksum) {
            return new RemoteManagedSettingsFetchResult(true, null, checksum, null, null);
        }
    }
}