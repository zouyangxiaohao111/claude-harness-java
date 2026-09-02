package com.nexusai.application.agent.workflow;

import java.util.List;

/**
 * agent() hook 入参，也是 journal key 的输入 · CC original: {@code AgentRunParams}
 * (Open-ClaudeCode/packages/workflow-engine/src/types.ts:13-28)。
 *
 * <p><b>关键语义</b>：{@code label} / {@code phase} 是 display-only，被
 * {@code canonicalParams} 剔除（journal.ts:8-14），<b>不参与 journal key</b>——换 label/phase
 * 不导致 resume 失配（W-1d 最高危点，见 P0-plan §8.1 R3）。
 *
 * @param prompt       CC original: {@code prompt} (types.ts:14) — agent 提示词
 * @param schema       CC original: {@code schema?} (types.ts:15) — JSON Schema；提供时 agent 返回
 *                     校验过的对象而非文本（P0 以 Object 透传，W-1c 校验边界处理）
 * @param model        CC original: {@code model?} (types.ts:16) — 模型 id
 * @param maxTokens    CC original: {@code maxTokens?} (types.ts:17) — 输出 token 上限（透传 agent 后端）
 * @param agentType    CC original: {@code agentType?} (types.ts:18) — 自定义 subagent 类型（registry 解析）
 * @param isolation    CC original: {@code isolation?: 'worktree'} (types.ts:19) — 仅 'worktree' 一个值
 * @param allowedTools CC original: {@code allowedTools?: string[]} (types.ts:20) — 允许工具清单
 * @param label        CC original: {@code label?} (types.ts:21) — 仅展示；不进 journal key
 * @param phase        CC original: {@code phase?} (types.ts:22) — 仅展示；不进 journal key
 */
public record AgentRunParams(
        String prompt,
        Object schema,
        String model,
        Integer maxTokens,
        String agentType,
        String isolation,
        List<String> allowedTools,
        String label,
        String phase
) {
}
