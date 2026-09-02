package com.nexusai.application.agent.subagent;

/**
 * resume 后端返回值 · 对齐 CC {@code ResumeAgentResult}
 * （Open-ClaudeCode/src/tools/AgentTool/resumeAgent.ts:37-41）。
 *
 * <p>前端消费 {@code POST /builtins/resume/execute} 响应，凭 {@code agentId} / {@code outputFile}
 * 轮询后台任务输出（BackgroundTaskRunner taskId === agentId 合一）。
 *
 * @param agentId     原 sub-agent UUID（taskId 复用；transcript 写新键，agentId override 待后续补 SubagentExecutor）
 * @param description 人类可读描述（meta.description 或 "(resumed)"）
 * @param outputFile  后台任务输出文件路径（BackgroundTaskRunner.taskOutputPath 唯一根）
 */
public record ResumeAgentResult(
        String agentId,
        String description,
        String outputFile
) {
}
