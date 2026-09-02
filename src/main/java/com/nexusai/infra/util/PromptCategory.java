package com.nexusai.infra.util;

import java.util.Map;
import java.util.function.Supplier;

/**
 * PromptCategory · 对齐 CC utils/promptCategory.ts.
 *
 * <p>L1 语义: 决定 analytics 用 prompt category。
 * <ul>
 *   <li>{@link #getQuerySourceForAgent(agentType, isBuiltInAgent)} → {@code agent:builtin:NAME} / {@code agent:custom} / {@code agent:default}</li>
 *   <li>{@link #getQuerySourceForREPL(settings)} → {@code repl_main_thread} / {@code repl_main_thread:outputStyle:NAME} / {@code repl_main_thread:outputStyle:custom}</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态 method 接受 Supplier&lt;Map&gt; for settings (testable)</li>
 *   <li><b>A2 Golden Trace</b>: agentType=Explore + isBuiltInAgent=true → 'agent:builtin:Explore';built-in=Explore → 'repl_main_thread:outputStyle:Explore';custom style → 'repl_main_thread:outputStyle:custom'</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output</li>
 *   <li><b>A4 边界</b>: null/undefined agentType → 'agent:default';null settings → 'repl_main_thread'</li>
 *   <li><b>A5 业务场景</b>: Explore agent 派单 → analytics 上报 'agent:builtin:Explore';用户使用 /output-style custom → 'repl_main_thread:outputStyle:custom'</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS conditional ternary chain → Java if/else chain;
 * TS cast AS QuerySource → Java String + caller type;TS 输出样式 config Map →
 * Java 注入式 Map 检查 (testable)。
 */
public final class PromptCategory {

    public static final String DEFAULT_REPL = "repl_main_thread";
    public static final String DEFAULT_AGENT = "agent:default";
    public static final String CUSTOM_AGENT = "agent:custom";

    private PromptCategory() {}

    /**
     * Compute query source for agent usage.
     *
     * @param agentType       agent name (undefined/null → 'agent:default')
     * @param isBuiltInAgent true if BuiltInAgent
     * @return query source string
     */
    public static String getQuerySourceForAgent(String agentType, boolean isBuiltInAgent) {
        if (isBuiltInAgent) {
            return agentType != null && !agentType.isEmpty()
                ? "agent:builtin:" + agentType
                : DEFAULT_AGENT;
        }
        return CUSTOM_AGENT;
    }

    /**
     * Compute query source for REPL based on outputStyle setting.
     *
     * @param settingsSupplier returns settings Map; null/empty → use defaults (DEFAULT_REPL)
     * @param builtInStylesSupplier returns the built-in OUTPUT_STYLE_CONFIG map; null → empty
     * @return query source string
     */
    public static String getQuerySourceForREPL(
        Supplier<Map<String, Object>> settingsSupplier,
        Map<String, ?> builtInStylesSupplier) {
        if (settingsSupplier == null) return DEFAULT_REPL;
        Map<String, Object> settings = settingsSupplier.get();
        if (settings == null) return DEFAULT_REPL;
        Object styleObj = settings.get("outputStyle");
        String style = styleObj == null ? null : styleObj.toString();
        if (style == null || style.isEmpty()) return DEFAULT_REPL;
        Map<String, ?> builtIn = builtInStylesSupplier == null ? Map.of() : builtInStylesSupplier;
        boolean isBuiltIn = builtIn.containsKey(style);
        return isBuiltIn
            ? "repl_main_thread:outputStyle:" + style
            : "repl_main_thread:outputStyle:custom";
    }
}
