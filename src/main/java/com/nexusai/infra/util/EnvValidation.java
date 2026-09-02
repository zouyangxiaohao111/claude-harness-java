package com.nexusai.infra.util;

public final class EnvValidation {
    public record EnvVarValidationResult(
        long effective,
        Status status,
        String message) {
        public enum Status { valid, capped, invalid }
    }
    private EnvValidation() {}
    public static EnvVarValidationResult validateBoundedIntEnvVar(
        String name, String value, long defaultValue, long upperLimit) {
        if (value == null || value.isEmpty()) {
            return new EnvVarValidationResult(defaultValue, EnvVarValidationResult.Status.valid, null);
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return new EnvVarValidationResult(defaultValue, EnvVarValidationResult.Status.invalid,
                "Invalid value \"" + value + "\" (using default: " + defaultValue + ")");
        }
        if (parsed <= 0) {
            return new EnvVarValidationResult(defaultValue, EnvVarValidationResult.Status.invalid,
                "Invalid value \"" + value + "\" (using default: " + defaultValue + ")");
        }
        if (parsed > upperLimit) {
            return new EnvVarValidationResult(upperLimit, EnvVarValidationResult.Status.capped,
                "Capped from " + parsed + " to " + upperLimit);
        }
        return new EnvVarValidationResult(parsed, EnvVarValidationResult.Status.valid, null);
    }
}
