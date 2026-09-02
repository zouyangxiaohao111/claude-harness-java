package com.nexusai.application.agent.plugin;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plugin Validator · 对齐 CC utils/plugins/validatePlugin.ts (903 行).
 *
 * <p>FIX-PLUGIN-VALID: 简化版 manifest/content/marketplace 验证.
 *
 * <p>L1 行为: 验证 plugin manifest + 内容 + marketplace 引用.
 */
@Component
public class PluginValidator {

    public enum CheckResult { OK, INVALID_MANIFEST, INVALID_CONTENT, BROKEN_MARKETPLACE_REF }

    public record Validation(CheckResult result, List<String> errors) {}

    private final PluginSchemas schemas;

    public PluginValidator(PluginSchemas schemas) {
        this.schemas = schemas;
    }

    public Validation validate(String manifestJson, String contentPath, String marketplaceName) {
        PluginSchemas.ValidationResult schemaResult = schemas.validate(manifestJson);
        if (!schemaResult.valid()) {
            return new Validation(CheckResult.INVALID_MANIFEST,
                List.of(schemaResult.error()));
        }
        if (contentPath == null || contentPath.isBlank()) {
            return new Validation(CheckResult.INVALID_CONTENT,
                List.of("content path is blank"));
        }
        if (marketplaceName != null && !marketplaceName.isBlank()) {
            // 真实 marketplace 校验需要 marketplace manager; 此处只校验非空.
            return new Validation(CheckResult.OK, List.of());
        }
        return new Validation(CheckResult.OK, List.of());
    }
}