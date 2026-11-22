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
    SUBAGENT_RESUME_WORKTREE_REFUSED("subagent_resume_worktree_refused"),
    /**
     * 会话级并发槽已满 · 对齐 CC {@code roomForResume} 返回 undefined 时的
     * {@code session_full}（claude.exe 2.1.328 偏移 356172104
     * {@code S("resume",void 0,{degradedClass:"session_full"})}）。
     *
     * <p>CC 原文（{@code uSa}，偏移 326610900）：{@code Agent "<name>" was not resumed, and your
     * message was not delivered: this session has no room for another agent now, or has started as
     * many as it may between two messages from the user. Go on without it, or say in your final
     * response what you needed from it.} <b>不排队</b>。
     */
    SESSION_FULL("session_full"),
    /**
     * 自动续跑次数已用尽 · 对齐 CC {@code resumes_spent}（偏移 353419208
     * {@code if(Pt>=yYa)return"resumes_spent"}，{@code yYa=5}）。CC 只在计划阶段静默剔除候选、
     * 无面向用户文案；本仓对模型自动 resume 显式拒绝并给出出口句（不伪造 CC 原文）。
     */
    SUBAGENT_RESUME_RESUMES_SPENT("subagent_resume_resumes_spent");

    /** CC {@code yYa=5}（偏移 315610267）· 每 agent 自动续跑上限。 */
    public static final int MAX_RESTART_RESUMES = 5;

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

    /**
     * 无 transcript · 对齐 CC {@code ef{transcriptMissing:true}}（偏移 333965575）。
     *
     * <p>继承 {@link com.nexusai.infra.exception.NotFoundException} 以保持 REST 404 契约
     * （CommandController 注释的既有语义），同时携带 {@link #AGENT_TRANSCRIPT_MISSING}
     * 让 SendMessage 回执能保留 {@code no_transcript} 分档 —— 两者不再互斥。
     */
    public static final class ResumeTranscriptMissingException extends com.nexusai.infra.exception.NotFoundException {
        public ResumeTranscriptMissingException(String message) {
            super(message);
        }

        public ResumeErrorCode errorCode() {
            return AGENT_TRANSCRIPT_MISSING;
        }
    }

    /** CC 的通用出口句（逐字）。 */
    public static final String START_NEW_AGENT_SUFFIX =
        " Start a new agent if the work is still needed.";

    // ────────────────────────────────────────────────────────────────────
    // 失败分档 · 五档 + 遥测映射 / 面向用户原文
    //   对齐 CC claude.exe 2.1.328：
    //   · Ks(err) 偏移 356128040：k9 → "not_reachable"、t2e → "still_stopping"、
    //     ef → "no_transcript"、其他 → "resume_failed"；
    //   · 配额满 → {}",degradedClass:"session_full"（偏移 356172104）。
    //   v9（AgentResumeInProgressError）在 SendMessage 侧被单独捕获并<b>软化</b>为入队
    //   （偏移 356173200 区），不进失败分档；其余档位见下。
    // ────────────────────────────────────────────────────────────────────

    /**
     * 码 → 遥测档（CC {@code Ks}）。{@code v9} 在本仓对应
     * {@link #SUBAGENT_RESUME_ALREADY_RUNNING}，仅在调用方未软化时落入这里。
     */
    public static String telemetryCode(ResumeErrorCode code) {
        if (code == null) {
            return "resume_failed";
        }
        switch (code) {
            case AGENT_STOPPED_BY_USER:
                return "not_reachable";
            case SUBAGENT_RESUME_ALREADY_RUNNING:
                return "in_progress";
            case SUBAGENT_RESUME_STILL_STOPPING:
                return "still_stopping";
            case AGENT_TRANSCRIPT_MISSING:
                return "no_transcript";
            case SESSION_FULL:
                return "session_full";
            default:
                return "resume_failed";
        }
    }

    /** 同 {@link #telemetryCode}（SendMessage 回执字段名沿用 CC 的 {@code degradedClass}）。 */
    public static String degradedClass(ResumeErrorCode code) {
        return telemetryCode(code);
    }

    /**
     * k9 原文（{@code wqn}，偏移 333965575）：
     * {@code Agent <id> was stopped and won't be resumed. Treat its work as cancelled; only launch
     * a new agent if the user explicitly asks.}
     */
    public static String stoppedByUserText(String agentId) {
        return "Agent " + agentId + " was stopped and won't be resumed. "
            + "Treat its work as cancelled; only launch a new agent if the user explicitly asks.";
    }

    /**
     * SendMessage 侧 k9 原文（{@code Ns}，偏移 356171000）：
     * {@code Agent "<name>" was stopped by the user and was not resumed. Treat its work as
     * cancelled; only start a new agent for it if the user explicitly asks.}
     */
    public static String stoppedByUserSendText(String agentName) {
        return "Agent \"" + agentName + "\" was stopped by the user and was not resumed. "
            + "Treat its work as cancelled; only start a new agent for it if the user explicitly asks.";
    }

    /** k9 门 B（准入后复核）原文：{@code Agent <id> was stopped meanwhile, so this delivery was dropped.} */
    public static String stoppedMeanwhileText(String agentId) {
        return "Agent " + agentId + " was stopped meanwhile, so this delivery was dropped.";
    }

    /** t2e 原文（偏移 333965575）：{@code Re-run <stop command> on it or wait for it to exit before resuming.} */
    public static String stillStoppingText(String agentId) {
        return "Agent " + agentId + " is still stopping \u2014 its previous run was stopped "
            + "but has not exited. Re-run TaskStop on it or wait for it to exit before resuming.";
    }

    /** 调用方自己被停（t2e 另一种）：原文见 findings 三-②。 */
    public static String callerStoppedText() {
        return "This agent has been stopped and its stop is still completing; "
            + "it cannot resume other agents.";
    }

    /**
     * 无 transcript（CC {@code ef.transcriptMissing}，偏移 333965575 + {@code zs} 偏移 356128040）：
     * {@code Agent "X" could not be resumed: No transcript found for agent ID: <id>.
     * Start a new agent if the work is still needed.}
     */
    public static String transcriptMissingText(String agentName, String agentId) {
        return "Agent \"" + agentName + "\" could not be resumed: No transcript found for agent ID: "
            + agentId + "." + START_NEW_AGENT_SUFFIX;
    }

    /**
     * 配额满（CC {@code uSa}，偏移 326610900 逐字）。
     */
    public static String sessionFullText(String agentName) {
        return "Agent \"" + agentName + "\" was not resumed, and your message was not delivered: "
            + "this session has no room for another agent now, or has started as many as it may "
            + "between two messages from the user. Go on without it, or say in your final response "
            + "what you needed from it.";
    }

    /**
     * v9 软化（CC 偏移 356173200 区逐字）：
     * {@code <name> is already waking; message queued for its next tool round
     * (not delivered if the agent turns out to have been stopped).}
     */
    public static String alreadyWakingText(String agentName) {
        return agentName + " is already waking; message queued for its next tool round "
            + "(not delivered if the agent turns out to have been stopped).";
    }

    /** v9 且连 task 记录都没了（CC 同段）：{@code ... Retry shortly.} */
    public static String alreadyWakingNoRecordText(String agentName) {
        return agentName + " is being resumed by another caller and its task record is gone; "
            + "the message was not delivered. Retry shortly.";
    }

    /** 其余失败（resume_failed）· 与 CC「not resumed / message was not delivered」同构。 */
    public static String notResumedText(String agentName, String detail) {
        return "Agent \"" + agentName + "\" was not resumed, and your message was not delivered: "
            + detail;
    }

    /** 逐出分支 meta 不存在（CC {@code dSa}，偏移 326609933 逐字）。 */
    public static String metaMissingText(String agentId) {
        return "Not sent. This session has no saved settings for agent " + agentId
            + " that it can read: the agent never ran here, or its settings were lost."
            + START_NEW_AGENT_SUFFIX;
    }

    /** 逐出分支 meta 读失败（CC {@code cSa}，偏移 326610135 逐字）。 */
    public static String metaUnreadableText(String agentId) {
        return "Not sent. The saved settings of agent " + agentId
            + " could not be read just now. Send the message again; start a new agent only if "
            + "that fails too.";
    }
}
