package com.nexusai.application.agent.workflow.progress;

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * 一次 workflow run 的进度快照 · CC original: {@code RunProgress}
 * (Open-ClaudeCode/src/workflow/progress/store.ts:21-38)。
 *
 * <p>W-1e WorkflowService {@code listRuns()/getRun()} 的返回类型（面板/工具用）；
 * store 由 {@link ProgressStore} 从 bus 事件归约维护。
 *
 * @param runId          本次 run 唯一 id · CC original: runId (store.ts:22)
 * @param workflowName   工作流名 · CC original: workflowName (store.ts:23)
 * @param status         运行态 · CC original: status (store.ts:24)
 * @param phases         运行中/已结束的阶段 · CC original: phases (store.ts:25)
 * @param declaredPhases 声明期阶段（run_started.meta.phases[].title；无 meta 时空列表）·
 *                       CC original: declaredPhases (store.ts:27)
 * @param currentPhase   当前阶段（null=无）· CC original: currentPhase (store.ts:28)
 * @param agents         子 agent 进度快照 · CC original: agents (store.ts:29)
 * @param agentCount     子 agent 数 · CC original: agentCount (store.ts:30)
 * @param returnValue    completed 时的返回值 · CC original: returnValue? (store.ts:31)，可选
 * @param error          failed 时的错误信息 · CC original: error? (store.ts:32)，可选
 * @param startedAt      run_started 时间戳（面板算时长用）· CC original: startedAt (store.ts:34)
 * @param description    工作流描述（run_started.meta.description）· CC original: description?
 *                       (store.ts:36)，可选
 * @param updatedAt      最近一次事件时间戳 · CC original: updatedAt (store.ts:37)
 */
public record RunProgress(
        String runId,
        String workflowName,
        Status status,
        List<Phase> phases,
        List<String> declaredPhases,
        @Nullable String currentPhase,
        List<AgentProgress> agents,
        int agentCount,
        @Nullable Object returnValue,
        @Nullable String error,
        long startedAt,
        @Nullable String description,
        long updatedAt
) {

    /** 运行态 · CC original: 'running' | 'completed' | 'failed' | 'killed' (store.ts:24)。 */
    public enum Status {
        /** 运行中。 */
        RUNNING,
        /** 正常完成。 */
        COMPLETED,
        /** 失败。 */
        FAILED,
        /** 被杀（shutdown-kill / abort）。 */
        KILLED
    }

    /**
     * 阶段状态 · CC original: {@code phases[]} = {@code {title, status:'running'|'done'}} (store.ts:25)。
     *
     * @param title  阶段名
     * @param status 阶段态
     */
    public record Phase(String title, PhaseState status) {
    }

    /** 阶段态 · CC original: 'running' | 'done' (store.ts:25)。 */
    public enum PhaseState {
        /** 运行中。 */
        RUNNING,
        /** 已结束。 */
        DONE
    }

    /** 新建构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 从现有实例复制一份到构建器（store 归约 on-copy 用）。 */
    public Builder toBuilder() {
        return new Builder()
                .runId(runId)
                .workflowName(workflowName)
                .status(status)
                .phases(phases)
                .declaredPhases(declaredPhases)
                .currentPhase(currentPhase)
                .agents(agents)
                .agentCount(agentCount)
                .returnValue(returnValue)
                .error(error)
                .startedAt(startedAt)
                .description(description)
                .updatedAt(updatedAt);
    }

    /** 可变构建器（ProgressStore 归约事件时 on-copy 更新，保持记录不可变 + 线程安全）。 */
    public static final class Builder {
        private String runId;
        private String workflowName;
        private Status status;
        private List<Phase> phases;
        private List<String> declaredPhases;
        private String currentPhase;
        private List<AgentProgress> agents;
        private int agentCount;
        private Object returnValue;
        private String error;
        private long startedAt;
        private String description;
        private long updatedAt;

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder workflowName(String workflowName) {
            this.workflowName = workflowName;
            return this;
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder phases(List<Phase> phases) {
            this.phases = phases;
            return this;
        }

        public Builder declaredPhases(List<String> declaredPhases) {
            this.declaredPhases = declaredPhases;
            return this;
        }

        public Builder currentPhase(String currentPhase) {
            this.currentPhase = currentPhase;
            return this;
        }

        public Builder agents(List<AgentProgress> agents) {
            this.agents = agents;
            return this;
        }

        public Builder agentCount(int agentCount) {
            this.agentCount = agentCount;
            return this;
        }

        public Builder returnValue(Object returnValue) {
            this.returnValue = returnValue;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder startedAt(long startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder updatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public RunProgress build() {
            return new RunProgress(
                    runId, workflowName, status, phases, declaredPhases, currentPhase,
                    agents, agentCount, returnValue, error, startedAt, description, updatedAt);
        }
    }
}
