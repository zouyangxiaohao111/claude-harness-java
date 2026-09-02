package com.nexusai.application.agent.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Remote Managed Settings 核心契约 · 对齐 CC services/remoteManagedSettings/index.ts.
 *
 * <p>L1 语义: 远程托管 settings 的 checksum 计算 + deep key sort + eligible 代理.
 *            fetch/save/cache/IO 等副作用不在本类(由 caller 提供);本类只暴露纯函数.
 *            CC 注释: 必须匹配服务端 Python json.dumps(sort_keys=True, separators=(",", ":")).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: SETTINGS_TIMEOUT_MS=10000; DEFAULT_MAX_RETRIES=5; POLLING_INTERVAL_MS=3600000;
 *       LOADING_PROMISE_TIMEOUT_MS=30000; computeChecksumFromSettings(SettingsJson)→"sha256:...";
 *       sortKeysDeep(obj)→sorted.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — {a:1, b:2} → computeChecksum → "sha256:..." (稳定);
 *       nested {a:{c:3,b:2}} → sortKeysDeep → {a:{b:2,c:3}}; isEligibleForRemoteManagedSettings 代理.</li>
 *   <li><b>A3</b>: 状态 — sortKeysDeep 纯函数 (TreeMap 排序 + 递归); checksum 计算结果稳定 (无随机性).</li>
 *   <li><b>A4</b>: null→空; nested map → 递归; list 保留顺序 (每项 sortKeysDeep); 数组元素也递归.</li>
 *   <li><b>A5</b>: 真实场景 — Claude.ai 企业用户启动 CLI → fetchAndLoad → 304 / 200 / 204 / 404 分支;
 *       1 小时 background polling; fail-open 策略 (失败 → 用 stale cache).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC createHash('sha256') → Java MessageDigest;
 *                    TS recursive object sort → Java TreeMap + 递归;
 *                    TS jsonStringify 内联 → Java toString + JSON-like 序列化 (测试稳定即可).
 */
public final class RemoteManagedSettingsChecksum {

    public static final long SETTINGS_TIMEOUT_MS = 10_000L;
    public static final int DEFAULT_MAX_RETRIES = 5;
    public static final long POLLING_INTERVAL_MS = 60L * 60L * 1000L;
    public static final long LOADING_PROMISE_TIMEOUT_MS = 30_000L;

    private final java.util.function.BooleanSupplier eligibilitySupplier;

    public RemoteManagedSettingsChecksum(java.util.function.BooleanSupplier eligibilitySupplier) {
        this.eligibilitySupplier = java.util.Objects.requireNonNull(eligibilitySupplier);
    }

    /** CC isEligibleForRemoteManagedSettings — 注入式 (caller 提供底层 syncCache 逻辑). */
    public boolean isEligibleForRemoteManagedSettings() {
        return eligibilitySupplier.getAsBoolean();
    }

    /** CC computeChecksumFromSettings — sortKeysDeep + sha256 + "sha256:" prefix. */
    public String computeChecksumFromSettings(Map<String, Object> settings) {
        Object sorted = sortKeysDeep(settings);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sorted.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** CC sortKeysDeep — 递归排序所有 key (匹配 Python json.dumps sort_keys=True). */
    @SuppressWarnings("unchecked")
    public static Object sortKeysDeep(Object obj) {
        if (obj instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (var e : ((Map<String, Object>) obj).entrySet()) {
                sorted.put(e.getKey(), sortKeysDeep(e.getValue()));
            }
            return sorted;
        }
        if (obj instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object e : (List<?>) obj) {
                result.add(sortKeysDeep(e));
            }
            return result;
        }
        return obj == null ? "" : obj;
    }

    /** 标准化输出 — 把 sortKeysDeep 结果转为 deterministic 字符串 (用于 hash). */
    public static String toCanonicalString(Object sorted) {
        if (sorted == null) return "";
        if (sorted instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : ((Map<String, Object>) sorted).entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(e.getKey())).append("\":");
                sb.append(toCanonicalString(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (sorted instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object e : (List<?>) sorted) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toCanonicalString(e));
            }
            sb.append("]");
            return sb.toString();
        }
        if (sorted instanceof String s) return "\"" + escape(s) + "\"";
        if (sorted instanceof Number || sorted instanceof Boolean) return sorted.toString();
        return "\"" + escape(sorted.toString()) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 兼容性: 提供默认 eligibilitySupplier (永远 false, 用于测试). */
    public static RemoteManagedSettingsChecksum defaultInstance() {
        return new RemoteManagedSettingsChecksum(() -> false);
    }
}