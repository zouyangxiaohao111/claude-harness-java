package com.nexusai.application.agent.settings;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validation Tips · 对齐 CC utils/settings/validationTips.ts.
 *
 * <p>FIX-SETTINGS-VALID: validation tip matcher.
 *
 * <p>L1 行为: 给定错误信息, 返回对应的修复提示.
 */
@Component
public class ValidationTips {

    public record Tip(String errorCode, String message, String suggestion) {}

    private static final List<Tip> TIPS = List.of(
        new Tip("INVALID_KEY", "Unknown settings key", "Check allowed keys in docs"),
        new Tip("INVALID_VALUE", "Value type mismatch", "Use correct type (string/bool/int)"),
        new Tip("READONLY", "Cannot modify built-in setting", "Settings.json vs user settings"),
        new Tip("MISSING_FIELD", "Required field missing", "Add the missing field"),
        new Tip("INVALID_PERMISSION", "Permission rule malformed", "Check syntax in docs")
    );

    public List<Tip> match(String errorCode) {
        return TIPS.stream().filter(t -> t.errorCode().equals(errorCode)).toList();
    }
}