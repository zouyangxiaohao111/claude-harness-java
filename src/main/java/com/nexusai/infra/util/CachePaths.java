package com.nexusai.infra.util;

import java.util.function.Supplier;

public final class CachePaths {
    private static final int MAX_SANITIZED_LENGTH = 200;

    private CachePaths() {}

    public static String sanitizePath(String name, java.util.function.LongSupplier djb2HashFn) {
        if (name == null) return null;
        String sanitized = name.replaceAll("[^a-zA-Z0-9]", "-");
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return sanitized;
        }
        long hash = djb2HashFn == null ? 0 : Math.abs(djb2HashFn.getAsLong());
        return sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-" + Long.toString(hash, 36);
    }

    public static String getProjectDir(String cwd, java.util.function.LongSupplier djb2HashFn) {
        return sanitizePath(cwd, djb2HashFn);
    }

    public static String baseLogs(Supplier<String> baseCacheSupplier, String cwd, java.util.function.LongSupplier djb2HashFn) {
        return baseCacheSupplier.get() + "/" + getProjectDir(cwd, djb2HashFn);
    }

    public static String errors(Supplier<String> baseCacheSupplier, String cwd, java.util.function.LongSupplier djb2HashFn) {
        return baseLogs(baseCacheSupplier, cwd, djb2HashFn) + "/errors";
    }

    public static String mcpLogs(Supplier<String> baseCacheSupplier, String cwd, String serverName, java.util.function.LongSupplier djb2HashFn) {
        return baseLogs(baseCacheSupplier, cwd, djb2HashFn) + "/mcp-logs-" + sanitizePath(serverName, djb2HashFn);
    }
}
