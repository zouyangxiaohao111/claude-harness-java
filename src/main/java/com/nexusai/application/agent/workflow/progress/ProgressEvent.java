package com.nexusai.application.agent.workflow.progress;

import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.WorkflowMeta;
import jakarta.annotation.Nullable;

/**
 * 引擎进度事件判别联合 · CC original: {@code ProgressEvent} (types.ts:85-125).
 *
 * <p>⚠️ 探查报告曾称「7 类」，实际 TS 源码是 <b>8 个变体</b>（types.ts:85-125）——
 * 早期版本误把承载 result 的 {@code agent_done} 与显式 early-exit 的 {@code log} 排除。
 * 按真源建模 8 类：run_started / phase_started / phase_done / agent_started / agent_done /
 * agent_progress / log / run_done。
 *
 * <p><b>所有变体都带 {@code runId}</b>，adapter 借此把事件路由到对应 task（支持多 workflow 并发）。
 * 引擎 {@code makeHooks} 的 emit()（hooks.ts:52-57）自动注入 runId，hooks 内部只用不带 runId 的
 * 载荷（HookProgressInit，hooks.ts:35-45）——Java 端若分层，可同样用「内部载荷 + 外层补 runId」。
 *
 * <p>store 归约（progress/store.ts）：{@code log} 被 store 显式 early-exit 忽略（面板无 log 视图，
 * 避免无谓 snapshot 重建），但 bus 上仍广播给其他订阅者。
 */
public sealed interface ProgressEvent {

    /**
     * 事件归属的 runId · CC original: {@code runId}（所有变体公共字段，types.ts:85-125）.
     *
     * @return 本次 workflow run 的唯一 id
     */
    String runId();

    /**
     * 运行开始 · CC original: {@code { type: 'run_started', runId, workflowName, meta: WorkflowMeta | null }}
     * (types.ts:87-91).
     *
     * <p>store 设 status='running'、declaredPhases（meta.phases[].title）、description（meta.description）。
     *
     * @param runId        本次 run 唯一 id
     * @param workflowName 工作流名 · CC original: workflowName (types.ts:89)
     * @param meta         声明期元数据（无 meta 时为 null）· CC original: meta (types.ts:90)
     */
    record RunStarted(String runId, String workflowName, @Nullable WorkflowMeta meta)
            implements ProgressEvent {
    }

    /**
     * 阶段开始 · CC original: {@code { type: 'phase_started', runId, phase }} (types.ts:92).
     *
     * <p>store 无则 push {title,'running'} 并设 currentPhase。
     *
     * @param runId 本次 run 唯一 id
     * @param phase 阶段名 · CC original: phase (types.ts:93)
     */
    record PhaseStarted(String runId, String phase) implements ProgressEvent {
    }

    /**
     * 阶段结束 · CC original: {@code { type: 'phase_done', runId, phase }} (types.ts:93).
     *
     * <p>store 该 phase.status='done'；currentPhase===phase 则清 null。
     *
     * @param runId 本次 run 唯一 id
     * @param phase 阶段名 · CC original: phase (types.ts:94)
     */
    record PhaseDone(String runId, String phase) implements ProgressEvent {
    }

    /**
     * 子 agent 启动 · CC original: {@code { type: 'agent_started', runId, agentId, label?, phase? }}
     * (types.ts:95-100).
     *
     * <p>agentId 为引擎盖章的数字唯一 id（agentIdSeq 自增，精确关联 started/done，修掉旧 LIFO 竞态）。
     *
     * @param runId   本次 run 唯一 id
     * @param agentId 引擎盖章的子 agent 唯一 id · CC original: agentId (types.ts:98)
     * @param label   仅展示 · CC original: label (types.ts:99)，可选
     * @param phase   归属阶段（缺省回退 currentPhase）· CC original: phase (types.ts:100)，可选
     */
    record AgentStarted(String runId, int agentId, @Nullable String label, @Nullable String phase)
            implements ProgressEvent {
    }

    /**
     * 子 agent 结束 · CC original: {@code { type: 'agent_done', runId, agentId, label?, phase?, result }}
     * (types.ts:101-108).
     *
     * <p>含 journal 命中重放路径、skip 路径；store ok 分支补 outputShape/tokenCount/toolCount/model，
     * dead/skipped 只留 resultKind。
     *
     * @param runId   本次 run 唯一 id
     * @param agentId 引擎盖章的子 agent 唯一 id · CC original: agentId (types.ts:104)
     * @param label   仅展示 · CC original: label (types.ts:105)，可选
     * @param phase   归属阶段 · CC original: phase (types.ts:106)，可选
     * @param result  agent 运行结果 · CC original: result (types.ts:107)
     */
    record AgentDone(String runId, int agentId, @Nullable String label, @Nullable String phase,
                     AgentRunResult result) implements ProgressEvent {
    }

    /**
     * 高频实时进度 · CC original: {@code { type: 'agent_progress', runId, agentId, label?, phase?,
     * tokenCount, toolCount }} (types.ts:109-117).
     *
     * <p>store 仅更新这两个计数（受「每条 agent 消息一次」节流）。
     *
     * @param runId      本次 run 唯一 id
     * @param agentId    引擎盖章的子 agent 唯一 id · CC original: agentId (types.ts:112)
     * @param label      仅展示 · CC original: label (types.ts:113)，可选
     * @param phase      归属阶段 · CC original: phase (types.ts:114)，可选
     * @param tokenCount 已累计 token 数 · CC original: tokenCount (types.ts:115)
     * @param toolCount  已累计工具调用数 · CC original: toolCount (types.ts:116)
     */
    record AgentProgress(String runId, int agentId, @Nullable String label, @Nullable String phase,
                         int tokenCount, int toolCount) implements ProgressEvent {
    }

    /**
     * 日志事件 · CC original: {@code { type: 'log', runId, message }} (types.ts:118).
     *
     * <p>命名用 {@code WorkflowLog} 而非 {@code Log}：{@code java.util.logging.Logger} 已占用 Log 类名
     * 直觉，Java 比 TS 对类名歧义更敏感。store 显式 early-exit 忽略本事件（面板无 log 视图），
     * 但 bus 上仍广播给其他订阅者。
     *
     * @param runId   本次 run 唯一 id
     * @param message 日志消息 · CC original: message (types.ts:119)
     */
    record WorkflowLog(String runId, String message) implements ProgressEvent {
    }

    /**
     * 运行终态 · CC original: {@code { type: 'run_done', runId, status, returnValue?, error? }}
     * (types.ts:120-125).
     *
     * <p>store 覆盖终态；shutdown-kill 也路由到 killed。
     *
     * @param runId       本次 run 唯一 id
     * @param status      终态 · CC original: status (types.ts:122)
     * @param returnValue 返回值 · CC original: returnValue (types.ts:123)，可选
     * @param error       错误信息 · CC original: error (types.ts:124)，可选
     */
    record RunDone(String runId, RunStatus status, @Nullable Object returnValue, @Nullable String error)
            implements ProgressEvent {
    }

    /**
     * run 终态枚举 · CC original: {@code 'completed' | 'failed' | 'killed'} (types.ts:122).
     */
    enum RunStatus {
        /** 正常完成。 */
        COMPLETED,
        /** 失败（runAgent-threw 等非 abort 错误）。 */
        FAILED,
        /** 被杀（shutdown-kill / abort）。 */
        KILLED
    }
}
