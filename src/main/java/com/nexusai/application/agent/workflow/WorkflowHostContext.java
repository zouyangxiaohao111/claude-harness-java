package com.nexusai.application.agent.workflow;

/**
 * 引擎从 host 提取的即用上下文 · CC original: {@code WorkflowHostContext}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:115-123)。
 *
 * @param handle      CC original: {@code handle} (ports.ts:117) — 不透明 HostHandle
 *                    （内含 toolUseContext/canUseTool/parentMessage，透传给 AgentRunner）
 * @param cwd         CC original: {@code cwd} (ports.ts:118) — 工作目录
 *                    （核心层用 projectRoot 而非 getCwd，防 worktree/子目录 desync）
 * @param budgetTotal CC original: {@code budgetTotal} (ports.ts:119) — token 预算上限；null=不限
 *                    （turn 级预算注入点，未来从 settings 读取）
 * @param toolUseId   CC original: {@code toolUseId?} (ports.ts:120-122) — 核心侧工具调用 id
 *                    （透传给任务注册）；可空
 */
public record WorkflowHostContext(HostHandle handle, String cwd, Integer budgetTotal, String toolUseId) {
}
