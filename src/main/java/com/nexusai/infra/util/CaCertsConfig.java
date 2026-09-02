package com.nexusai.infra.util;

import java.util.Map;
import java.util.function.Supplier;

/**
 * CaCertsConfig · 对齐 CC utils/caCertsConfig.ts.
 *
 * <p>L1 语义: 在 init 早期从 global config / user settings 读取 NODE_EXTRA_CA_CERTS,
 * 写入 process.env,使后续 TLS 连接信任自定义 CA。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 (applyExtraCACertsFromConfig + getExtraCertsPathFromConfig) + EnvSetter / EnvSourceSupplier 注入式</li>
 *   <li><b>A2 Golden Trace</b>: env.NODE_EXTRA_CA_CERTS 已设置→return;globalConfig/userSettings 有→apply;无→no-op</li>
 *   <li><b>A3 副作用</b>: setEnv() 写入 process.env NODE_EXTRA_CA_CERTS</li>
 *   <li><b>A4 边界</b>: null env source→no path→no-op;env 异常 catch</li>
 *   <li><b>A5 业务场景</b>: 用户 ~/.claude/settings.json 设 NODE_EXTRA_CA_CERTS=/path/to/cert → init() 调用 → 后续 HTTPS 信任该 CA</li>
 * </ul>
 *
 * <p>L3 升级: TS process.env indexed → Java Consumer setEnv (testable);
 * TS getGlobalConfig / getSettingsForSource → Java Supplier 注入式;
 * TS try/catch 静默 → Java catch (RuntimeException) 静默.
 */
public final class CaCertsConfig {

    public static final String ENV_VAR = "NODE_EXTRA_CA_CERTS";

    private CaCertsConfig() {}

    public interface EnvSetter {
        void set(String key, String value);
        String get(String key);
    }

    /**
     * Apply NODE_EXTRA_CA_CERTS from global config / user settings into process.env
     * early in init, BEFORE any TLS connections.
     */
    public static void applyExtraCACertsFromConfig(
        EnvSetter envSetter,
        Supplier<Map<String, Object>> globalConfigSupplier,
        Supplier<Map<String, Object>> userSettingsSupplier) {
        if (envSetter == null) return;
        // Already set in env → nothing to do
        if (envSetter.get(ENV_VAR) != null) return;
        String path = getExtraCertsPathFromConfig(globalConfigSupplier, userSettingsSupplier);
        if (path != null) {
            envSetter.set(ENV_VAR, path);
        }
    }

    /**
     * Read NODE_EXTRA_CA_CERTS from global config (env) and user settings (env).
     * Settings override global config (same precedence as applyConfigEnvironmentVariables).
     */
    public static String getExtraCertsPathFromConfig(
        Supplier<Map<String, Object>> globalConfigSupplier,
        Supplier<Map<String, Object>> userSettingsSupplier) {
        try {
            String globalVal = null;
            if (globalConfigSupplier != null) {
                Map<String, Object> global = globalConfigSupplier.get();
                Object envObj = global == null ? null : global.get("env");
                if (envObj instanceof Map) {
                    Object v = ((Map<?, ?>) envObj).get(ENV_VAR);
                    if (v != null) globalVal = v.toString();
                }
            }
            String settingsVal = null;
            if (userSettingsSupplier != null) {
                Map<String, Object> settings = userSettingsSupplier.get();
                Object envObj = settings == null ? null : settings.get("env");
                if (envObj instanceof Map) {
                    Object v = ((Map<?, ?>) envObj).get(ENV_VAR);
                    if (v != null) settingsVal = v.toString();
                }
            }
            // Settings override global config
            return settingsVal != null ? settingsVal : globalVal;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
