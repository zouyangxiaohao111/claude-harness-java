package com.nexusai.application.agent.workflow;

import jakarta.annotation.Nullable;

/**
 * WorkflowService.launch 入参 · CC original: {@code Pick<WorkflowInput, 'script'|'name'|'scriptPath'
 * |'args'|'description'|'resumeFromRunId'|'title'|'maxConcurrency'>} (service.ts:54-64)。
 *
 * <p>{@code resolveSource} 三源解析（service.ts:141-179）：script > scriptPath（readFile）> name
 * （{@code resolveNamedWorkflow}，缺 → 抛 Error）；{@code workflowName = name ?? title ?? 'workflow'}
 * （service.ts:153，所以 /workflows 面板不会在同一个默认名 'workflow' 下堆积）。
 *
 * @param script          内联脚本源码 · CC original: script (service.ts:55)，可选（与 name/scriptPath 三选一）
 * @param name            命名 workflow 名 · CC original: name (service.ts:56)，可选
 * @param scriptPath      脚本文件路径 · CC original: scriptPath (service.ts:57)，可选
 * @param args            传给脚本的执行参数 · CC original: args (service.ts:58)，可选
 * @param description     工作流描述（注册为 task summary）· CC original: description (service.ts:59)，可选
 * @param resumeFromRunId resume 复用已有 runId（读其 journal）· CC original: resumeFromRunId
 *                        (service.ts:60)，可选
 * @param title           工作流标题（name 缺省时作 workflowName 兜底）· CC original: title
 *                        (service.ts:61)，可选
 * @param maxConcurrency  单 run 并发槽 · CC original: maxConcurrency (service.ts:62)，可选
 *                        （缺省 → DEFAULT_MAX_CONCURRENCY，引擎钳制到 MAX_CONCURRENCY_CAP）
 */
public record LaunchInput(
        @Nullable String script,
        @Nullable String name,
        @Nullable String scriptPath,
        @Nullable Object args,
        @Nullable String description,
        @Nullable String resumeFromRunId,
        @Nullable String title,
        @Nullable Integer maxConcurrency
) {
}
