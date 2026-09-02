package com.nexusai.application.agent.prompt;

/**
 * 输出风格配置 · 对齐 CC {@code OutputStyleConfig}
 * （CC original: {@code type OutputStyleConfig = { name: string; description: string; prompt: string; source: SettingSource | 'built-in' | 'plugin'; keepCodingInstructions?: boolean } }
 * (constants/outputStyles.ts:11-18)）。
 *
 * <p>CC 中该配置由 {@code getOutputStyleConfig()} 从设置/插件加载，随 output_style 动态 section
 * 与 intro section 使用。Java 以 record 承载，source 收敛为字符串（CC 为
 * {@code SettingSource | 'built-in' | 'plugin'} 联合）。
 *
 * <p>门控语义（prompts.ts:564-566）：
 * <pre>{@code
 * outputStyleConfig === null || outputStyleConfig.keepCodingInstructions === true
 *   ? getSimpleDoingTasksSection()   // 注入
 *   : null                           // doingTasks 被 Output Style 取代
 * }</pre>
 * 故 {@link #keepCodingInstructions()} 用 {@code Boolean}（三态：null=未声明、false=不保留、
 * true=保留），与 CC optional 字段一致。
 *
 * @param name                风格名 · CC original: name (outputStyles.ts:12)
 * @param description         风格描述 · CC original: description (outputStyles.ts:13)
 * @param prompt              风格提示全文 · CC original: prompt (outputStyles.ts:14)
 * @param source              风格来源 · CC original: source（SettingSource|'built-in'|'plugin'，outputStyles.ts:15）
 * @param keepCodingInstructions 是否保留 coding instructions · CC original: keepCodingInstructions?
 *                               (outputStyles.ts:16)，null=未声明
 */
public record OutputStyleConfig(
    String name,
    String description,
    String prompt,
    String source,
    Boolean keepCodingInstructions
) {

    /**
     * 简化工厂：name/prompt 双参（source/description/keepCodingInstructions 可空）。
     *
     * @param name  风格名 · CC original: name (outputStyles.ts:12)
     * @param prompt 风格提示全文 · CC original: prompt (outputStyles.ts:14)
     * @return 未声明 source/keepCodingInstructions 的配置（keepCodingInstructions=null 按 CC
     *         optional 语义：null 不等于 true，doingTasks 仍注入）
     */
    public static OutputStyleConfig of(String name, String prompt) {
        return new OutputStyleConfig(name, null, prompt, null, null);
    }
}
