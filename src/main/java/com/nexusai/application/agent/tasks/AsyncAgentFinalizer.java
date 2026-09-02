package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步 Agent 终态化封装 · 对齐 CC LocalAgentTask.tsx:197-262 lifecycle cleanup.
 *
 * <p>异步 sub-agent worker 完成后, 统一通过本类写入 BackgroundTaskRunner 终态:
 * <ul>
 *   <li>{@code result != null} → {@link BackgroundTaskRunner#completeAsyncAgent} (写 COMPLETED)</li>
 *   <li>{@code result == null} → {@link BackgroundTaskRunner#failAsyncAgent} (写 FAILED, reason="null result")</li>
 *   <li>{@link #finalizeKilled} → {@link BackgroundTaskRunner#killAsyncAgent} (写 KILLED, killed 三态)</li>
 * </ul>
 *
 * <p>[S4 P1 差异项 6] runAsyncAgentLifecycle 三态路由 (CC agentToolUtils.ts:624/659/673):
 * completed / killed / failed 三态收敛. killed 路径 (CC :640-668 AbortError → killAsyncAgent +
 * extractPartialResult) 经 {@code finalizeKilled} 路由到 killAsyncAgent — task 终态 + 通知由
 * BackgroundTaskRunner.killAsyncAgent 内置 (buildEnqueueShellNotification), 部分结果承载在
 * {@link AsyncAgentResult#summary()} (对齐 CC extractPartialResult :658).
 *
 * <p>WHY 封装: sub-agent worker 路径里 try/catch 容易写出"成功/失败双路径重复 enqueue"
 * 或"忘记在失败路径 enqueue"的 bug. finalizer 把所有"已经走到终态"语义统一收敛.
 *
 * <p>本类为薄封装, 状态推进逻辑 (CAS / evict / enqueue) 仍在
 * {@link BackgroundTaskRunner#transitionToTerminal} 内 — finalizer 仅做路由.
 */
public final class AsyncAgentFinalizer {

    private final BackgroundTaskRunner runner;

    public AsyncAgentFinalizer(BackgroundTaskRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("runner 不能为 null");
        }
        this.runner = runner;
    }

    /**
     * 终态化入口 (completed / failed).
     *
     * @param taskId task id (= agentId.toString())
     * @param result 非 null → completeAsyncAgent; null → failAsyncAgent("null result")
     */
    public void finalize(String taskId, AsyncAgentResult result) {
        if (result == null) {
            log.info("AsyncAgentFinalizer: result=null, 走失败路径 task={}", taskId);
            runner.failAsyncAgent(taskId, "null result");
            return;
        }
        log.info("AsyncAgentFinalizer: 写入完成结果 task={} summaryLen={} toolCount={} durationMs={} totalTokens={}",
            taskId, result.summary() != null ? result.summary().length() : 0,
            result.totalToolUseCount(), result.totalDurationMs(), result.totalTokens());
        runner.completeAsyncAgent(taskId, result);
    }

    /**
     * killed 三态路由 · 对齐 CC runAsyncAgentLifecycle AbortError 路径 (agentToolUtils.ts:640-668).
     *
     * <p>killAsyncAgent (CC killAsyncAgent) 原子推进 KILLED + enqueue killed 通知; 幂等
     * (only-if-running 守卫, TaskStop 已置 killed 时短路). 部分结果 (CC extractPartialResult)
     * 承载于 {@code result.summary()} 并经 2 参 killAsyncAgent 写入通知 {@code <result>} 段
     * (CC LocalAgentTask.tsx:249 resultSection) — [S4-1 残差 ②] 收尾.
     *
     * @param taskId task id (= agentId.toString())
     * @param result killed 结果 (summary = 部分结果文本)
     */
    public void finalizeKilled(String taskId, AsyncAgentResult result) {
        if (result == null) {
            log.info("AsyncAgentFinalizer.finalizeKilled: result=null, 走失败路径 task={}", taskId);
            runner.failAsyncAgent(taskId, "null result (killed path)");
            return;
        }
        boolean killed = runner.killAsyncAgent(taskId, result);
        log.info("AsyncAgentFinalizer.finalizeKilled: task={} killed={} partialLen={} totalTokens={}",
            taskId, killed, result.summary() != null ? result.summary().length() : 0,
            result.totalTokens());
    }

    private static final Logger log = LoggerFactory.getLogger(AsyncAgentFinalizer.class);
}