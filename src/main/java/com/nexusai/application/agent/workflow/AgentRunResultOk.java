package com.nexusai.application.agent.workflow;

/**
 * {@code ok} 变体 · CC original: {@code {kind:'ok', output, usage:{outputTokens}, model?, toolCount?, tokenCount?}}
 * (Open-ClaudeCode/packages/workflow-engine/src/types.ts:44-51)。
 *
 * @param output       CC original: {@code output} — agent 文本/结构化输出
 * @param outputTokens CC original: {@code usage.outputTokens} — 输出 token 数（journal 命中不扣预算，
 *                     live 路径 {@code budget.addOutputTokens} 累计）
 * @param model        CC original: {@code model?} — 实际解析出的模型 id（仅展示）
 * @param toolCount    CC original: {@code toolCount?} — agent 运行期工具调用数（仅展示）
 * @param tokenCount   CC original: {@code tokenCount?} — 完成时总上下文 token（仅展示）
 */
public record AgentRunResultOk(String output, int outputTokens, String model,
                               Integer toolCount, Integer tokenCount) implements AgentRunResult {
}
