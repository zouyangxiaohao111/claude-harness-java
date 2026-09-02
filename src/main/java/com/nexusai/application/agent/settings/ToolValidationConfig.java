package com.nexusai.application.agent.settings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ToolValidationConfig · 对齐 CC utils/settings/toolValidationConfig.ts.
 *
 * <p>L1 语义: tool validation configuration — file pattern tools list +
 * bash prefix tools list + custom validation rules。
 * <ul>
 *   <li>{@code FILE_PATTERN_TOOLS} — Read/Write/Edit/Glob/NotebookRead/NotebookEdit (接受 *.ts, src/**)</li>
 *   <li>{@code BASH_PREFIX_TOOLS} — Bash (接受 * 通配 + command:* 旧语法)</li>
 *   <li>{@code CUSTOM_VALIDATION} — WebSearch (no wildcards) + WebFetch (domain: prefix)</li>
 *   <li>{@code isFilePatternTool(name)} + {@code isBashPrefixTool(name)} + {@code getCustomValidation(name)} helpers</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 List + 1 Map + 3 静态 helper 方法 + ValidationResult record</li>
 *   <li><b>A2 Golden Trace</b>: isFilePatternTool("Read")=true;isFilePatternTool("Bash")=false;isBashPrefixTool("Bash")=true;WebSearch content "foo*"→invalid;WebFetch "https://..."→invalid;WebFetch "domain:example.com"→valid</li>
 *   <li><b>A3 纯函数</b>: stateless;同 input→同 output</li>
 *   <li><b>A4 边界</b>: null tool name→false;unknown tool→false custom validation</li>
 *   <li><b>A5 业务场景</b>: tool permission validation 阻止错误 pattern (e.g. WebFetch("https://..."))</li>
 * </ul>
 *
 * <p>L3 升级: TS Record → Java Map;
 * TS const Set → Java Set.of immutable;
 * TS function validation → Java BiPredicate 注入式.
 */
public final class ToolValidationConfig {

    public record ValidationResult(
        boolean valid, String error, String suggestion, List<String> examples) {

        public static ValidationResult ok() {
            return new ValidationResult(true, null, null, List.of());
        }

        public static ValidationResult fail(String error, String suggestion, List<String> examples) {
            return new ValidationResult(false, error, suggestion, examples);
        }
    }

    public static final Set<String> FILE_PATTERN_TOOLS = Set.of(
        "Read", "Write", "Edit", "Glob", "NotebookRead", "NotebookEdit");

    public static final Set<String> BASH_PREFIX_TOOLS = Set.of("Bash");

    public static final Map<String, java.util.function.Function<String, ValidationResult>> CUSTOM_VALIDATION;

    static {
        CUSTOM_VALIDATION = new HashMap<>();
        // WebSearch doesn't support wildcards or complex patterns
        CUSTOM_VALIDATION.put("WebSearch", content -> {
            if (content.contains("*") || content.contains("?")) {
                return ValidationResult.fail(
                    "WebSearch does not support wildcards",
                    "Use exact search terms without * or ?",
                    Arrays.asList("WebSearch(claude ai)", "WebSearch(typescript tutorial)"));
            }
            return ValidationResult.ok();
        });
        // WebFetch uses domain: prefix for hostname-based permissions
        CUSTOM_VALIDATION.put("WebFetch", content -> {
            if (content.contains("://") || content.startsWith("http")) {
                return ValidationResult.fail(
                    "WebFetch permissions use domain format, not URLs",
                    "Use \"domain:hostname\" format",
                    Arrays.asList("WebFetch(domain:example.com)", "WebFetch(domain:github.com)"));
            }
            if (!content.startsWith("domain:")) {
                return ValidationResult.fail(
                    "WebFetch permissions must use \"domain:\" prefix",
                    "Use \"domain:hostname\" format",
                    Arrays.asList("WebFetch(domain:example.com)", "WebFetch(domain:*.google.com)"));
            }
            return ValidationResult.ok();
        });
    }

    private ToolValidationConfig() {}

    public static boolean isFilePatternTool(String toolName) {
        return toolName != null && FILE_PATTERN_TOOLS.contains(toolName);
    }

    public static boolean isBashPrefixTool(String toolName) {
        return toolName != null && BASH_PREFIX_TOOLS.contains(toolName);
    }

    public static java.util.function.Function<String, ValidationResult> getCustomValidation(String toolName) {
        return toolName == null ? null : CUSTOM_VALIDATION.get(toolName);
    }
}
