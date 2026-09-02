package com.nexusai.infra.util;

import java.util.function.Supplier;

/**
 * ManagedPathResolver · 对齐 CC utils/settings/managedPath.ts.
 */
public final class ManagedPathResolver {

    public static final String WINDOWS_PATH = "C:\\Program Files\\ClaudeCode";
    public static final String LINUX_PATH = "/etc/claude-code";
    public static final String MAC_PATH_PREFIX = "/Library/Application Support/ClaudeCode";
    public static final String MANAGED_DIR_NAME = "managed-settings.d";

    private ManagedPathResolver() {}

    public static String getManagedFilePath(Supplier<String> platformSupplier,
                                            Supplier<String> userTypeSupplier,
                                            Supplier<String> overrideSupplier) {
        String userType = userTypeSupplier == null ? null : userTypeSupplier.get();
        if ("ant".equals(userType) && overrideSupplier != null) {
            String override = overrideSupplier.get();
            if (override != null && !override.isEmpty()) return override;
        }
        String platform = platformSupplier == null ? "linux" : platformSupplier.get();
        if (platform == null) platform = "linux";
        if (platform.contains("mac") || platform.contains("darwin")) {
            return MAC_PATH_PREFIX;
        }
        if (platform.contains("win")) {
            return WINDOWS_PATH;
        }
        return LINUX_PATH;
    }
}
