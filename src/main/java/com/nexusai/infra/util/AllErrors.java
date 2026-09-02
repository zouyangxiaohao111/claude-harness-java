package com.nexusai.infra.util;

import java.util.ArrayList;
import java.util.List;

/**
 * AllErrors · 对齐 CC utils/settings/allErrors.ts.
 */
public final class AllErrors {

    private AllErrors() {}

    public record CombinedErrors<T>(T settings, List<Object> errors) {}

    public record SettingsWithErrors<T>(T settings, List<Object> errors) {}

    public static <T> CombinedErrors<T> combine(
        SettingsWithErrors<T> settings,
        List<String> scopes,
        java.util.function.Function<String, CombinedErrors<T>> mcpErrorsFn) {
        if (mcpErrorsFn == null) return new CombinedErrors<>(settings.settings(), settings.errors());
        List<Object> errors = new ArrayList<>(settings.errors());
        for (String scope : scopes) {
            CombinedErrors<T> mcp = mcpErrorsFn.apply(scope);
            if (mcp != null && mcp.errors() != null) {
                errors.addAll(mcp.errors());
            }
        }
        return new CombinedErrors<>(settings.settings(), errors);
    }
}
