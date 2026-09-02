package com.nexusai.application.agent.workflow;

import com.nexusai.application.agent.tool.AbortController;

/**
 * 后台任务生命周期注册器 · CC original: {@code TaskRegistrar}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:53-89)。
 *
 * <p>WorkflowPorts 8 项依赖抽象之一「后台任务 / React setAppState」。
 * <ul>
 *   <li>{@link #register} 创建 AbortController + taskId，返回 {@code {runId, signal}}
 *       （引擎 detached 执行 + kill 用 signal abort）</li>
 *   <li>{@link #complete}/{@link #fail}/{@link #kill} 路由后台任务终态</li>
 *   <li>{@link #registerAgentAbort}/{@link #unregisterAgentAbort}/{@link #killAgent} —
 *       agent 级精确 abort（单个 agent 不影响同 run 其他 agent）</li>
 *   <li>{@link #pendingAction} — P0 恒 null（v1 skip/retry 缝保留）</li>
 * </ul>
 *
 * <p>Java 端落内存 {@code Map<String, RunBinding> bindings}（P0，不接
 * {@code TaskType.LOCAL_WORKFLOW} 真 runner——P3 W-4b）。
 *
 * @see WorkflowPortsImpl
 */
public interface TaskRegistrar {

    /**
     * 注册一个后台任务 · CC original: ports.ts:58-68。
     *
     * <p>adapter 创建 AbortController 存进 task 状态，返回 runId + signal（引擎 detached 执行 + kill）。
     * <b>runId 语义（DEC-P0-05，对齐 ports.ts:106）</b>：{@code runId = opts.runId ?? 生成Id}
     * ——resume 复用外部 runId（读其 journal）。
     *
     * @param opts 注册选项（workflowName 必填；runId 可选，resume 复用）
     * @param host 不透明 HostHandle（内含 toolUseContext.setAppState 等）
     * @return {@code {runId, signal}}
     */
    RegisterResult register(RegisterOpts opts, HostHandle host);

    /**
     * 任务正常完成 · CC original: ports.ts:69-70。runId 未登记 → no-op。
     *
     * @param runId   CC original: {@code runId}
     * @param summary CC original: {@code summary?} — 完成摘要（可空）
     */
    void complete(String runId, String summary);

    /**
     * 任务失败 · CC original: ports.ts:71。runId 未登记 → no-op。
     *
     * @param runId CC original: {@code runId}
     * @param error CC original: {@code error} — 失败错误信息
     */
    void fail(String runId, String error);

    /**
     * 任务被杀 · CC original: ports.ts:72。
     * <b>同时 abort 全部 in-flight agentAbortControllers</b>（防边界时序——backend 漏 task abort）。
     *
     * @param runId CC original: {@code runId}
     */
    void kill(String runId);

    /**
     * 登记 agent 级 AbortController · CC original: ports.ts:77-78（可选）。
     * backend 启动 agent 时调用，使 {@code service.kill(runId, agentId)} 可精确 abort 单个 agent。
     * 幂等：同 agentId 重复登记覆盖。
     *
     * @param runId          CC original: {@code runId}
     * @param agentId        CC original: {@code agentId}（引擎层数字序号）
     * @param abortController CC original: {@code ac}
     */
    void registerAgentAbort(String runId, int agentId, AbortController abortController);

    /**
     * 注销 agent 级 AbortController · CC original: ports.ts:80-82（可选，幂等）。
     *
     * @param runId   CC original: {@code runId}
     * @param agentId CC original: {@code agentId}
     */
    void unregisterAgentAbort(String runId, int agentId);

    /**
     * 精确 abort 单个 agent · CC original: ports.ts:85-87（可选）。
     * 返回是否命中（false = agent 已完成/不存在）；不影响同 run 其他 agent，
     * workflow 继续（被 abort 的 agent 返回 dead → null）。
     *
     * @param runId   CC original: {@code runId}
     * @param agentId CC original: {@code agentId}
     * @return 命中并 abort → true；未命中 → false
     */
    boolean killAgent(String runId, int agentId);

    /**
     * 当前 pending skip/retry 动作 · CC original: ports.ts:88-89。
     * P0 恒 null（v1 缝保留）。
     *
     * @param runId CC original: {@code runId}
     * @return {@code {kind:'skip'|'retry'}} | null
     */
    PendingAction pendingAction(String runId);

    /**
     * 注册选项 · CC original: ports.ts:58-66 {@code opts}。
     *
     * @param workflowName CC original: {@code workflowName} — 必填
     * @param workflowFile CC original: {@code workflowFile?} — 脚本路径（可空）
     * @param summary      CC original: {@code summary?} — 任务摘要（可空）
     * @param toolUseId    CC original: {@code toolUseId?} — 工具调用 id（可空）
     * @param runId        CC original: {@code runId?} — resume 复用已有 runId；缺省生成新 id
     */
    record RegisterOpts(String workflowName, String workflowFile, String summary,
                        String toolUseId, String runId) {
        /** 便利构造：workflowFile 缺省空串（对齐 CC 注册时 workflowFile ?? ''） */
        public RegisterOpts {
            workflowFile = workflowFile != null ? workflowFile : "";
        }
    }

    /**
     * 注册结果 · CC original: ports.ts:67-68 {@code {runId, signal}}。
     *
     * @param runId  CC original: {@code runId}
     * @param signal CC original: {@code signal}（AbortSignal）— Java 用现有
     *               {@link AbortController}（isCancelled()/abort()）等价
     */
    record RegisterResult(String runId, AbortController signal) {
    }

    /**
     * pending skip/retry 动作 · CC original: ports.ts:88 {@code {kind:'skip'|'retry'}|null}。
     *
     * @param kind CC original: {@code kind} — 仅 'skip' | 'retry'
     */
    record PendingAction(String kind) {
        /** 校验 kind 值域（对齐 CC 字面量联合） */
        public PendingAction {
            if (kind != null && !"skip".equals(kind) && !"retry".equals(kind)) {
                throw new IllegalArgumentException("PendingAction.kind 仅允许 skip|retry，实际: " + kind);
            }
        }
    }
}
