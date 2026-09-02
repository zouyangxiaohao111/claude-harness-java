package com.nexusai.infra.util;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * EmbeddedTools · 对齐 CC utils/embeddedTools.ts.
 *
 * <p>L1 语义: ant-native build 配置 — bfs/ugrep embedded in bun binary。
 * <ul>
 *   <li>{@link #hasEmbeddedSearchTools(envSupplier, entrypointSupplier)} → boolean</li>
 *   <li>{@link #embeddedSearchToolsBinaryPath(execPathSupplier)} → String</li>
 * </ul>
 *
 * <p>2 静态方法 + 注入式 Supplier (testable).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 + 注入式 Supplier</li>
 *   <li><b>A2 Golden Trace</b>: env not set→false;env set + entrypoint in [sdk-ts,sdk-py,sdk-cli,local-agent]→false;env set + entrypoint=null→true</li>
 *   <li><b>A3 副作用</b>: 无</li>
 *   <li><b>A4 边界</b>: null supplier→false (safe default);empty entrypoint→true</li>
 *   <li><b>A5 业务场景</b>: ant-native build with embedded search tools</li>
 * </ul>
 */
public final class EmbeddedTools {

    private static final String[] SKIP_ENTRYPOINTS = {
        "sdk-ts", "sdk-py", "sdk-cli", "local-agent"
    };

    private EmbeddedTools() {}

    /**
     * Whether this build has bfs/ugrep embedded in the bun binary.
     */
    public static boolean hasEmbeddedSearchTools(
        BooleanSupplier envSupplier,
        Supplier<String> entrypointSupplier) {
        boolean env = envSupplier == null ? false : envSupplier.getAsBoolean();
        if (!env) return false;
        String e = entrypointSupplier == null ? null : entrypointSupplier.get();
        if (e == null) return true;
        for (String skip : SKIP_ENTRYPOINTS) {
            if (skip.equals(e)) return false;
        }
        return true;
    }

    /**
     * Path to the bun binary containing the embedded search tools.
     */
    public static String embeddedSearchToolsBinaryPath(Supplier<String> execPathSupplier) {
        return execPathSupplier == null ? null : execPathSupplier.get();
    }
}
