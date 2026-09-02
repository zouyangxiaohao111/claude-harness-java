package com.nexusai.model.command.dto;

/**
 * /resume 执行请求体 · 对齐 CC resumeAgentBackground 入参 {agentId, prompt}
 * （Open-ClaudeCode/src/tools/AgentTool/resumeAgent.ts:48-54）。
 *
 * @param agentId 待恢复 sub-agent 的标识，a+16hex（{@code AgentContext.createAgentId()} 产物，'a'+16 hex，
 *                D18/B2 拍板对齐 CC asAgentId ids.ts:31-33 纯 cast；旧格式 UUID string 亦兼容兜底）。
 *                REST 层不校验 UUID 格式（任意 string 均可入参，CommandController.toResumeAgentId 经
 *                S-12 pack 桥 → UUID(msb,0)，ResumeService.unpackAgentId 还原 a+16hex 双键查
 *                transcript miss → 404，等价 CC resumeAgent.ts:67-69）；transcript 文件名
 *                {@code agent-{agentId}.jsonl} 的 key
 * @param prompt  resume 时追加给 agent 的新用户指令（CC resumeAgent.ts:170
 *                {@code createUserMessage({ content: prompt })}）
 */
public record ResumeExecuteRequest(
        String agentId,
        String prompt
) {
}
