package com.nexusai.application.agent.settings;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Settings Validator · 对齐 CC utils/settings/validateEditTool.ts.
 *
 * <p>FIX-SETTINGS-VALID: settings-file Edit input validator.
 *
 * <p>L1 行为: 给定 settings key + 候选 value, 验证合法.
 */
@Component
public class SettingsValidator {

    public enum Status { OK, INVALID_KEY, INVALID_VALUE, READONLY }

    public record ValidationResult(Status status, String message) {}

    /** 已知合法 settings keys. */
    private static final List<String> ALLOWED_KEYS = List.of(
        "model", "effort", "theme", "autoUpdates", "verbose",
        "permissions", "outputStyle", "telemetry", "cleanupPeriodDays"
    );

    public ValidationResult validate(String key, Object value) {
        if (key == null || key.isBlank()) {
            return new ValidationResult(Status.INVALID_KEY, "key is blank");
        }
        if (!ALLOWED_KEYS.contains(key)) {
            return new ValidationResult(Status.INVALID_KEY, "unknown key: " + key);
        }
        if (value == null) {
            return new ValidationResult(Status.INVALID_VALUE, "value is null");
        }
        return new ValidationResult(Status.OK, "valid");
    }
}