package com.nexusai.apis.workflow;

import com.nexusai.application.agent.workflow.progress.AgentProgress;
import com.nexusai.application.agent.workflow.progress.RunProgress;

import java.util.ArrayList;
import java.util.List;

/**
 * Workflow run 前端 DTO · CC original: {@code RunProgress}
 * (Open-ClaudeCode/src/workflow/progress/store.ts:21-38)。
 *
 * <p><b>WHY（规则九 · 意图）</b>：后端 {@link RunProgress.Status} 枚举序列化为大写
 * （RUNNING/COMPLETED/FAILED/KILLED），CC 值域为小写（running/completed/failed/killed，
 * store.ts:24）。前端按 CC 契约消费（面板状态机 + 通知迁移判定），本 DTO 显式映射为
 * CC 小写值域，避免前端感知 Java 枚举命名。
 *
 * @param runId          本次 run 唯一 id · CC original: runId (store.ts:22)
 * @param workflowName   工作流名 · CC original: workflowName (store.ts:23)
 * @param status         运行态（小写：running/completed/failed/killed）· CC original: status (store.ts:24)
 * @param phases         阶段（title + status 小写）· CC original: phases (store.ts:25)
 * @param declaredPhases 声明期阶段 · CC original: declaredPhases (store.ts:27)
 * @param currentPhase   当前阶段 · CC original: currentPhase (store.ts:28)
 * @param agents         子 agent 进度 · CC original: agents (store.ts:29)
 * @param agentCount     子 agent 数 · CC original: agentCount (store.ts:30)
 * @param returnValue    完成返回值 · CC original: returnValue? (store.ts:31)
 * @param error          失败错误 · CC original: error? (store.ts:32)
 * @param startedAt      开始时间戳 · CC original: startedAt (store.ts:34)
 * @param description    工作流描述 · CC original: description? (store.ts:36)
 * @param updatedAt      最近更新 · CC original: updatedAt (store.ts:37)
 */
public record WorkflowRunDto(
        String runId,
        String workflowName,
        String status,
        List<PhaseDto> phases,
        List<String> declaredPhases,
        String currentPhase,
        List<AgentDto> agents,
        int agentCount,
        Object returnValue,
        String error,
        long startedAt,
        String description,
        long updatedAt
) {

    /**
     * 阶段 DTO · CC original: {@code phases[] = {title, status:'running'|'done'}} (store.ts:25)。
     *
     * @param title  阶段名
     * @param status 阶段态（小写 running/done）
     */
    public record PhaseDto(String title, String status) {
    }

    /**
     * 子 agent DTO · CC original: {@code AgentProgress} (store.ts:4-19)。
     *
     * @param id          引擎盖章 id · CC original: id (store.ts:5)
     * @param label       标签 · CC original: label? (store.ts:6)
     * @param phase       所属阶段 · CC original: phase? (store.ts:7)
     * @param status      running/done · CC original: status (store.ts:8)
     * @param resultKind  ok/skipped/dead · CC original: resultKind? (store.ts:9)
     * @param outputShape text/object · CC original: outputShape? (store.ts:11)
     * @param model       模型 id · CC original: model? (store.ts:12)
     * @param tokenCount  累计 token · CC original: tokenCount? (store.ts:13)
     * @param toolCount   累计工具调用 · CC original: toolCount? (store.ts:14)
     */
    public record AgentDto(int id, String label, String phase, String status,
                           String resultKind, String outputShape, String model,
                           Integer tokenCount, Integer toolCount) {
    }

    /**
     * 从后端 RunProgress 映射（status 大小写归一：枚举名 → CC 小写值域）。
     *
     * @param run 后端 store 中的进度快照
     * @return 前端 DTO
     */
    public static WorkflowRunDto from(RunProgress run) {
        if (run == null) {
            return null;
        }
        List<PhaseDto> phases = new ArrayList<>();
        for (RunProgress.Phase ph : run.phases()) {
            phases.add(new PhaseDto(ph.title(), ph.status().name().toLowerCase()));
        }
        List<AgentDto> agents = new ArrayList<>();
        for (AgentProgress a : run.agents()) {
            agents.add(new AgentDto(a.id(), a.label(), a.phase(),
                    a.status().name().toLowerCase(), a.resultKind(), a.outputShape(), a.model(),
                    a.tokenCount(), a.toolCount()));
        }
        return new WorkflowRunDto(
                run.runId(),
                run.workflowName(),
                run.status().name().toLowerCase(),
                phases,
                run.declaredPhases(),
                run.currentPhase(),
                agents,
                run.agentCount(),
                run.returnValue(),
                run.error(),
                run.startedAt(),
                run.description(),
                run.updatedAt()
        );
    }
}
