package com.nexusai.application.agent.subagent;

/**
 * 子代理 resume 失败错误码 · 对齐 claude.exe v2.1.325 的 {@code subagent_resume_*} 族。
 *
 * <p>CC 的码与文案是<b>分开</b>的：码用于分类/遥测，文案面向用户（都带
 * 「Start a new agent if the work is still needed.」的出口）。
 *
 * <p><b>sidecar 不是「agent 定义」</b>：CC 的 sidecar（{@code <agentId>.meta.json}）存的是这个
 * agent 的<b>运行记录</b>（cwd / worktreePath / isFork / stoppedByUser / spawnDepth /
 * forkedSkillName …）。本仓对应物 = {@link AgentTranscript.AgentMetadata}（description / agentType /
 * worktreePath 等）——字段少于 CC，但机制有对应物，不是 N/A。
 *
 * <p>本仓没有对应机制的码<b>不伪造</b>（见各常量注释）：
 * <ul>
 *   <li>{@code forked_skill_resume_scoping_*}——本仓无 fork-skill scoping 记录；</li>
 *   <li>{@code diskless}（无 storageV5 持久化后端、只能内存镜像的会话）——本仓恒有 configHome 存储，
 *       不出现 {@code _diskless} 形态。</li>
 * </ul>
 */
public enum ResumeErrorCode {
    /** transcript 缺失 / 不可读 → 无法从中断处继续。 */
    AGENT_TRANSCRIPT_UNRESUMABLE("agent_transcript_unresumable"),
    /** 找不到该 agent 的 transcript（CC :67-69 的 "No transcript found for agent ID"）。 */
    AGENT_TRANSCRIPT_MISSING("agent_transcript_missing"),
    /** 连续消息链断裂（no_leaf / no_agent_messages）。 */
    AGENT_TRANSCRIPT_NO_CHAIN("no_agent_messages"),
    /**
     * 该 agent 仍在运行，或已有并发 resume 在进行 → 拒绝重复 resume。
     *
     * <p>CC 文案（逐字）：{@code Agent <id> is already running or being resumed}。CC 侧为普通
     * Error（{@code Yte}），无专门遥测码；本仓为可分类使用本码名（命名沿用 {@code subagent_resume_*} 族）。
     */
    SUBAGENT_RESUME_ALREADY_RUNNING("subagent_resume_already_running"),
    /**
     * stop 已请求、但旧 worker 尚未退出 → 等它退出再 resume。
     *
     * <p>CC 文案（逐字）：{@code Agent <id> is still stopping — its previous run was stopped but has
     * not exited. Re-run <stop command> on it or wait for it to exit before resuming.}（exe 里
     * {@code ${ga}} 由 chunk-a45kmdcb 导出、字面量未取证到；本仓的停止入口就是 TaskStop 工具，
     * 故文案写 {@code TaskStop}）。
     */
    SUBAGENT_RESUME_STILL_STOPPING("subagent_resume_still_stopping"),
    /**
     * 该 agent 是被用户显式停止的，且本次 resume 不是用户发起（模型自动/系统触发）→ 拒绝。
     *
     * <p>CC 文案（逐字，{@code jgr()}）：{@code Agent <id> was stopped and won't be resumed.
     * Treat its work as cancelled; only launch a new agent if the user explicitly asks.}
     * CC 异常类名 {@code AgentStoppedByUserError}。用户显式 resume（/resume）不受此门限制。
     */
    AGENT_STOPPED_BY_USER("agent_stopped_by_user"),
    /** fork resume 需要重建父 system prompt，但主会话上下文不可得。 */
    SUBAGENT_RESUME_FORK_DROPPED("subagent_resume_fork_dropped"),
    /** 后台 agent 能力关闭且会话不再跟踪该 agent。 */
    SUBAGENT_RESUME_NO_RULE("subagent_resume_no_rule_diskless"),
    /** 目录与记录不一致 / 不可进入。 */
    SUBAGENT_RESUME_CWD_REFUSED("subagent_resume_cwd_refused"),
    /** 记录里声明了 worktree，但无法在该 worktree 中恢复。 */
    SUBAGENT_RESUME_WORKTREE_REFUSED("subagent_resume_worktree_refused");

    private final String code;

    ResumeErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** resume 失败 · 携带 CC 错误码与面向用户的文案。 */
    public static final class ResumeException extends RuntimeException {
        private final ResumeErrorCode errorCode;

        public ResumeException(ResumeErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public ResumeErrorCode errorCode() {
            return errorCode;
        }
    }

    /** CC 的通用出口句（逐字）。 */
    public static final String START_NEW_AGENT_SUFFIX =
        " Start a new agent if the work is still needed.";
}
