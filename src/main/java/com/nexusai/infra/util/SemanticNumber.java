package com.nexusai.infra.util;

import java.util.regex.Pattern;

/**
 * SemanticNumber · 对齐 CC utils/semanticNumber.ts.
 */
public final class SemanticNumber {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private SemanticNumber() {}

    /**
     * Parse a value as a Long (integer) or Double (decimal).
     * Returns the input as-is for invalid input.
     */
    public static Object parseNumber(Object value) {
        if (value == null) return null;
        if (value instanceof String) {
            String s = (String) value;
            if (NUMBER_PATTERN.matcher(s).matches()) {
                try {
                    if (s.contains(".")) {
                        return Double.parseDouble(s);
                    }
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
            return value;
        }
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        if (value instanceof Number) {
            return value;
        }
        return value;
    }

    public static double parseNumberOrDefault(Object value, double defaultValue) {
        Object result = parseNumber(value);
        if (result instanceof Number) return ((Number) result).doubleValue();
        return defaultValue;
    }
}
