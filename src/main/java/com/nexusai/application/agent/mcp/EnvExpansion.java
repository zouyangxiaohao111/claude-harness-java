package com.nexusai.application.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 配置中环境变量展开 · 对齐 CC services/mcp/envExpansion.ts expandEnvVarsInString.
 *
 * <p>L1 语义: 在字符串值中展开 ${VAR} 与 ${VAR:-default} 语法. 缺失变量被记录到 missingVars
 *            并保留原 ${...} 字面量 (允许调试 + 调用方错误报告).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `expand(String) → ExpansionResult` (expanded + missingVars)</li>
 *   <li><b>A2 Golden Trace</b>: "${HOME}/bin" → "$HOME" env → "/Users/x/bin" + []; "${X:-default}" → "default" + []</li>
 *   <li><b>A3</b>: 纯函数 + Function&lt;String,String&gt; env 注入 (测试可控); 相同输入 → 相同输出</li>
 *   <li><b>A4</b>: 缺失无 default → missingVars 加 varName + expanded 保留原 ${VAR}</li>
 *   <li><b>A5</b>: 真实 MCP env 字符串 — "API_KEY=${KEY}, HOST=${HOST:-localhost}" 完整展开</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Pattern.compile + Matcher 替代 JS regex.replace 回调; Function 注入 env;
 *                    List.copyOf 不可变返回.
 */
public final class EnvExpansion {

    /** CC envExpansion.ts:16 — ${VAR} 与 ${VAR:-default} 匹配. */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Function<String, String> envLookup;

    public EnvExpansion() {
        this(System::getenv);
    }

    /** 测试用: 注入 env 查找函数. */
    public EnvExpansion(Function<String, String> envLookup) {
        this.envLookup = envLookup;
    }

    /** 展开结果 record. */
    public record ExpansionResult(String expanded, List<String> missingVars) {}

    /** 展开字符串中的 ${VAR} / ${VAR:-default}. */
    public ExpansionResult expand(String value) {
        if (value == null || value.isEmpty()) {
            return new ExpansionResult(value == null ? null : "", List.of());
        }
        List<String> missing = new ArrayList<>();
        Matcher m = VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varContent = m.group(1);
            String[] parts = varContent.split(":-", 2);
            String varName = parts[0];
            String defaultValue = parts.length > 1 ? parts[1] : null;

            String envValue = envLookup.apply(varName);
            String replacement;
            if (envValue != null) {
                replacement = envValue;
            } else if (defaultValue != null) {
                replacement = defaultValue;
            } else {
                missing.add(varName);
                replacement = m.group(0);  // 保留原 ${VAR} 字面量
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return new ExpansionResult(sb.toString(), List.copyOf(missing));
    }

    /** 工具: 批量展开 Map<String,String> (典型 MCP env 配置). */
    public Map<String, ExpansionResult> expandAll(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return Map.of();
        java.util.LinkedHashMap<String, ExpansionResult> out = new java.util.LinkedHashMap<>();
        for (var e : entries.entrySet()) {
            out.put(e.getKey(), expand(e.getValue()));
        }
        return Map.copyOf(out);
    }
}