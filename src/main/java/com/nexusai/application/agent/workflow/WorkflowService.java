package com.nexusai.application.agent.workflow;

import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.workflow.progress.RunProgress;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * WorkflowService — 工具（U7）与面板（U9）共享的唯一入口 · CC original: {@code WorkflowService}
 * (Open-ClaudeCode/src/workflow/service.ts:49-93)。
 *
 * <p>职责：
 * <ul>
 *   <li>{@code ports}：共享 WorkflowPorts；工具描述符透传给引擎。</li>
 *   <li>{@code launch}：parse script → taskRegistrar.register（得 runId+signal）→
 *       <b>detached</b> runWorkflow → 完成时路由到 complete/fail/kill。</li>
 *   <li>{@code kill/listRuns/getRun/getRunAsync/loadPersistedRuns/subscribe/listNamed}：
 *       面板与工具的辅助查询（P2 补齐 getRunAsync/loadPersistedRuns/subscribe）。</li>
 * </ul>
 */
public interface WorkflowService {

    /**
     * 共享 ports（工具描述符用）· CC original: {@code ports} (service.ts:51)。
     *
     * @return WorkflowPorts 8 项聚合
     */
    WorkflowPorts ports();

    /**
     * 面板/工具启动 workflow · CC original: {@code launch(input, toolUseContext, canUseTool)}
     * (service.ts:53-67)：parse script → register → detached runWorkflow。
     *
     * <p>三源解析（service.ts:141-179）：script > scriptPath > name；脚本编译期校验失败抛
     * {@code IllegalArgumentException("Script validation failed: ...")}（service.ts:190-194，
     * 不进后台）。
     *
     * @param input       脚本三源 + args/description/resume/title/maxConcurrency
     * @param ctx         工具调用上下文（buildHost 载荷：cwd/toolUseId/agentId）
     * @param canUseTool  权限判定函数 · CC original: {@code CanUseToolFn}
     *                    （src/hooks/useCanUseTool.tsx:27）；Java 侧核心层为
     *                    {@code HookPermissionResolver.CanUseTool}，bundle 以 Object 不透明承载透传
     * @return {@code {runId, scriptPath?}} 的异步结果
     */
    CompletableFuture<LaunchResult> launch(LaunchInput input, ToolUseContext ctx, Object canUseTool);

    /**
     * 杀掉一个 run · CC original: {@code kill(runId)} (service.ts:68) → taskRegistrar.kill。
     *
     * @param runId 目标 run id
     */
    void kill(String runId);

    /**
     * 精确 abort 单个 agent · CC original: {@code killAgent(runId, agentId): boolean} (service.ts:73-76)。
     * 不影响同 run 其他 agent（workflow 继续；被 abort 的 agent 返回 dead → null）。
     *
     * @param runId   目标 run id
     * @param agentId 引擎层 agent 数字序号
     * @return 是否命中（false = agent 已完成/不存在）
     */
    boolean killAgent(String runId, int agentId);

    /**
     * 进程退出/配置卸载时清理 · CC original: {@code shutdown()} (service.ts:78-84)。
     * 只 kill running（避免孤儿任务）；completed/failed 不受影响；幂等。
     */
    void shutdown();

    /**
     * 全部 run 进度 · CC original: {@code listRuns()} (service.ts:85)。
     *
     * @return RunProgress 列表（按 updatedAt 降序）
     */
    List<RunProgress> listRuns();

    /**
     * 按 runId 取进度 · CC original: {@code getRun(runId)} (service.ts:86)。
     *
     * @param runId 目标 run id
     * @return RunProgress 或 null
     */
    RunProgress getRun(String runId);

    /**
     * 异步按 runId 查找 · CC original: {@code getRunAsync(runId)} (service.ts:85-89，
     * 实现 :285-289)。
     *
     * <p>内存命中直接返回；miss 则从磁盘 {@code state.json} 读（不注入内存）。用于"按 runId
     * 取历史返回"场景；面板展示用 {@link #loadPersistedRuns} + {@link #listRuns}。
     *
     * @param runId 目标 run id
     * @return RunProgress 或 null（内存 + 磁盘双 miss）
     */
    CompletableFuture<RunProgress> getRunAsync(String runId);

    /**
     * 扫描磁盘并把历史 run 的 state.json 水合进 store · CC original: {@code loadPersistedRuns()}
     * (service.ts:87-90，实现 :290-309)。
     *
     * <p>进程单例只扫一次磁盘（{@code persistedLoaded} flag）；重复调用立即返回。扫描失败 log +
     * 重置 flag 允许下次重试（不阻断面板，service.ts:302-308）。最多水合最新
     * {@code LOAD_PERSISTED_LIMIT=20} 个（防止面板标签行被历史淹没，service.ts:25-30）。
     */
    void loadPersistedRuns();

    /**
     * 订阅快照变更 · CC original: {@code subscribe(listener)} (service.ts:91，实现 :310)。
     * 委托 {@code store.subscribe}。
     *
     * @param listener 变更通知（每次 store 快照重建触发）
     * @return 退订 Runnable
     */
    Runnable subscribe(Runnable listener);

    /**
     * 列举命名 workflow · CC original: {@code listNamed(workflowDir?)} (service.ts:92-93)。
     *
     * @param workflowDir 显式目录（null → 决策 D6/D7：{@code <projectRoot>/.{appName}/workflows} nexusai 优先
     *                    + {@code .claude/workflows} 回落合并）
     * @return 去扩展名、字典序排序的工作流名（nexusai 优先去重）
     */
    List<String> listNamed(String workflowDir);
}
