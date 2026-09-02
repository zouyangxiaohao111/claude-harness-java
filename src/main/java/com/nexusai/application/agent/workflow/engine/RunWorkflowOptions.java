package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.HostHandle;
import com.nexusai.application.agent.workflow.WorkflowPorts;

/**
 * 引擎 run 入参 · CC original: {@code RunWorkflowOptions}
 * (Open-ClaudeCode/packages/workflow-engine/src/engine/runWorkflow.ts:12-29)。
 *
 * <p><b>W-1e 收口编译契约</b>：本类为 W-1e WorkflowServiceImpl.launch 的 detached 调用所需；
 * 完整引擎（W-1c WorkflowRunEngine）落地时以此契约为准（P0-core-doc §4.3）。
 *
 * @param script        已解析的脚本源码 · CC original: script (runWorkflow.ts:14)
 * @param args          传给脚本的参数 · CC original: args? (runWorkflow.ts:15)，可选
 * @param runId         本次 run id · CC original: runId (runWorkflow.ts:16)
 * @param workflowName  工作流名 · CC original: workflowName? (runWorkflow.ts:17)，可选
 * @param ports         8 项端口聚合 · CC original: ports (runWorkflow.ts:18)
 * @param host          不透明 host 句柄 · CC original: host (runWorkflow.ts:19)
 * @param signal        取消信号（同 workflow signal；Java 用现有 AbortController 等价 AbortSignal）·
 *                      CC original: signal (runWorkflow.ts:20)
 * @param cwd           工作目录 · CC original: cwd (runWorkflow.ts:21)
 * @param budgetTotal   token 预算上限（null=不限）· CC original: budgetTotal (runWorkflow.ts:22)
 * @param maxConcurrency 单 run 并发槽（缺省 → DEFAULT_MAX_CONCURRENCY，引擎钳制）·
 *                      CC original: maxConcurrency? (runWorkflow.ts:24)，可选
 * @param resume        resume：加载既有 journal 并重放 · CC original: resume? (runWorkflow.ts:26)
 * @param scriptChanged resume 时脚本源码哈希变化 → 忽略 journal 全量重跑 · CC original: scriptChanged?
 *                      (runWorkflow.ts:28)
 */
public record RunWorkflowOptions(
        String script,
        Object args,
        String runId,
        String workflowName,
        WorkflowPorts ports,
        HostHandle host,
        AbortController signal,
        String cwd,
        Integer budgetTotal,
        Integer maxConcurrency,
        boolean resume,
        boolean scriptChanged
) {
}
