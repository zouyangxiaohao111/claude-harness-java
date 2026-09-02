package com.nexusai.application.agent.team;

import com.nexusai.infra.util.AbortControllerFactory;

import java.util.List;
import java.util.Set;

/**
 * In-process teammate 任务状态载体 · 对齐 CC tasks/InProcessTeammateTask/types.ts:22-76
 * InProcessTeammateTaskState。
 *
 * <p>CC 真源（grep 实证 types.ts:22-76）：{@code InProcessTeammateTaskState = TaskStateBase & {...}}。
 * Java 侧 {@code TaskStateBase} 状态层由 {@link com.nexusai.application.agent.tasks.BackgroundTask}
 * 承载（id/type/status/notified/endTime/outputFile），本 record 承载 teammate 专属扩展字段。
 *
 * <p><b>Runtime only, not serialized</b>（对齐 CC types.ts:36-38）：{@code abortController /
 * currentWorkAbortController / unregisterCleanup / onIdleCallbacks} 为运行时字段，不落盘
 * （CC 注释 "Runtime only, not serialized to disk"，types.ts:36-38/:71）。
 *
 * @param taskId                任务 ID（'t' 前缀，CC Task.ts:98-105）
 * @param identity              teammate 身份（CC types.ts:27）
 * @param prompt                初始 prompt（CC types.ts:30）
 * @param model                 可选模型覆盖（CC types.ts:32）
 * @param awaitingPlanApproval  是否等待 plan 批准（CC types.ts:41）
 * @param permissionMode        权限模式（'plan'/'default'，CC types.ts:44）
 * @param error                 错误信息（CC types.ts:47）
 * @param messages              UI 镜像消息（cap 50，CC types.ts:53）
 * @param inProgressToolUseIDs  执行中的工具调用 ID（CC types.ts:56）
 * @param pendingUserMessages   待交付用户消息队列（CC types.ts:59）
 * @param isIdle                是否空闲（CC types.ts:66）
 * @param shutdownRequested     是否已请求关闭（CC types.ts:67）
 * @param lastReportedToolCount 上次报告的工具数（CC types.ts:74）
 * @param lastReportedTokenCount 上次报告的 token 数（CC types.ts:75）
 * @param abortController       生命周期 abort（runtime only, CC types.ts:36）
 * @param currentWorkAbortController 本轮 work abort（runtime only, CC types.ts:37）
 * @param unregisterCleanup     cleanup 反注册（runtime only, CC types.ts:38）
 * @param onIdleCallbacks       空闲回调（runtime only, CC types.ts:71）
 */
public record InProcessTeammateTaskState(
    String taskId,
    TeammateIdentity identity,
    String prompt,
    String model,
    boolean awaitingPlanApproval,
    String permissionMode,
    String error,
    List<String> messages,
    Set<String> inProgressToolUseIDs,
    List<String> pendingUserMessages,
    boolean isIdle,
    boolean shutdownRequested,
    int lastReportedToolCount,
    int lastReportedTokenCount,
    AbortControllerFactory.AbortControllerRef abortController,
    AbortControllerFactory.AbortControllerRef currentWorkAbortController,
    Runnable unregisterCleanup,
    List<Runnable> onIdleCallbacks
) {
    /** W8-02 REWORK: 带 error 的新副本 · 对齐 CC failed 转换 error: errorMessage（inProcessRunner.ts:1490）。 */
    public InProcessTeammateTaskState withError(String newError) {
        return new InProcessTeammateTaskState(
            taskId, identity, prompt, model, awaitingPlanApproval, permissionMode,
            newError, messages, inProgressToolUseIDs, pendingUserMessages, isIdle,
            shutdownRequested, lastReportedToolCount, lastReportedTokenCount,
            abortController, currentWorkAbortController, unregisterCleanup, onIdleCallbacks);
    }

    /** W8-02 REWORK: 带 isIdle 的新副本 · 对齐 CC failed 转换 isIdle:true（inProcessRunner.ts:1491）。 */
    public InProcessTeammateTaskState withIsIdle(boolean newIsIdle) {
        return new InProcessTeammateTaskState(
            taskId, identity, prompt, model, awaitingPlanApproval, permissionMode,
            error, messages, inProgressToolUseIDs, pendingUserMessages, newIsIdle,
            shutdownRequested, lastReportedToolCount, lastReportedTokenCount,
            abortController, currentWorkAbortController, unregisterCleanup, onIdleCallbacks);
    }

    /** W8-02 REWORK (GAP-6): 带 currentWorkAbortController 的新副本 · 对齐 CC inProcessRunner.ts:1059-1063
     *  轮开始时存入任务状态（UI 追踪/中止本轮），:1280-1284 轮末清空。 */
    public InProcessTeammateTaskState withCurrentWorkAbortController(
            AbortControllerFactory.AbortControllerRef newCurrentWorkAbortController) {
        return new InProcessTeammateTaskState(
            taskId, identity, prompt, model, awaitingPlanApproval, permissionMode,
            error, messages, inProgressToolUseIDs, pendingUserMessages, isIdle,
            shutdownRequested, lastReportedToolCount, lastReportedTokenCount,
            abortController, newCurrentWorkAbortController, unregisterCleanup, onIdleCallbacks);
    }
}
