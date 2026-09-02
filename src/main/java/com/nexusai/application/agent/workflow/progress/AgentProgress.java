package com.nexusai.application.agent.workflow.progress;

import jakarta.annotation.Nullable;

/**
 * 单个子 agent 的进度快照 · CC original: {@code AgentProgress}
 * (Open-ClaudeCode/src/workflow/progress/store.ts:4-19)。
 *
 * <p>store 归约 agent_started / agent_progress / agent_done 三个事件维护本快照；
 * 引擎盖章的 {@code id} 精确关联 started/done（修掉旧 LIFO 竞态）。
 *
 * @param id          引擎盖章的唯一 id · CC original: id (store.ts:6)
 * @param label       仅展示 · CC original: label? (store.ts:7)，可选
 * @param phase       归属阶段 · CC original: phase? (store.ts:8)，可选
 * @param status      运行中/结束 · CC original: status (store.ts:9)
 * @param resultKind  结束时的结果类型 · CC original: resultKind? (store.ts:10)，可选
 * @param outputShape ok 分支输出形状（object/text）· CC original: outputShape? (store.ts:12)，可选
 * @param model       实际解析出的 model id · CC original: model? (store.ts:14)，可选
 * @param tokenCount  累计上下文 token · CC original: tokenCount? (store.ts:16)，可选
 * @param toolCount   累计工具调用数 · CC original: toolCount? (store.ts:18)，可选
 */
public record AgentProgress(
        int id,
        @Nullable String label,
        @Nullable String phase,
        Status status,
        @Nullable String resultKind,
        @Nullable String outputShape,
        @Nullable String model,
        @Nullable Integer tokenCount,
        @Nullable Integer toolCount
) {

    /** 运行中/结束 · CC original: 'running' | 'done' (store.ts:9)。 */
    public enum Status {
        /** 运行中。 */
        RUNNING,
        /** 已结束（done·ok / done·dead / done·skipped）。 */
        DONE
    }
}
