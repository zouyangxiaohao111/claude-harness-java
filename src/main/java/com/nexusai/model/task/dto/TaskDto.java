package com.nexusai.model.task.dto;

import com.nexusai.application.agent.tasks.BackgroundTask;
import jakarta.annotation.Nullable;

/**
 * 异步任务清单前端 DTO · CC original: {@code TaskStateBase}
 * (Open-ClaudeCode/src/utils/task/Task.ts:45-57) 的投影字段。
 *
 * <p><b>WHY（规则九 · 意图）</b>：后端 {@link BackgroundTask} 为 Java record 全量 18 字段
 * （含 totalPausedMs/outputFile/outputOffset/notified/exitCode/error/prompt/result 等内部态），
 * 前端「异步任务清单」模块（任务 tab 内嵌 + 查看更多弹窗）只消费 9 个展示字段。本 DTO 显式
 * 裁剪 + 把 Java 大写枚举归一 CC 小写值域（{@code type}: local_bash/local_agent/...，
 * {@code status}: pending/running/completed/failed/killed）——前端按 CC 契约消费
 * （docs/team-perm-timeout-frontend-prompt.md §6 {@code BackgroundTaskDto}）。
 * {@code isBackgrounded} 是 CC 前台→后台切换标记（Ctrl+B task:background）的透出字段——前端
 * AsyncTasksPanel.tsx:59 按 {@code t.isBackgrounded !== false} 过滤只显示真后台任务，前台 bash
 * 工具卡任务（未转后台）隐藏，故 DTO 必须透出该字段（对齐前端 BackgroundTaskDto.isBackgrounded）。
 *
 * @param id          任务 id · CC original: id (Task.ts:46)
 * @param type        任务类型小写 · CC original: type (Task.ts:47)
 * @param status      任务状态小写 · CC original: status (Task.ts:48)
 * @param description 人类可读描述 · CC original: description (Task.ts:49)
 * @param toolUseId   关联 tool_use block id · CC original: toolUseId? (Task.ts:50)
 * @param startTime   开始时间戳 · CC original: startTime (Task.ts:51)
 * @param endTime     终态时间戳 · CC original: endTime? (Task.ts:52)
 * @param agentId     拥有此任务的子代理 UUID 串（主线程 spawn 为 null）· CC original:
 *                    taskId===agentId 合一（LocalAgentTask.tsx:197-262）
 * @param isBackgrounded 是否已后台化 · true=真异步后台任务（spawn/Ctrl+B 后台化）·
 *                       false=前台同步（前台 Bash 直执行，注册后立即完成）· CC original:
 *                       isBackgrounded (LocalShellTask.tsx:281 / LocalAgentTask.tsx:565)
 * @param ownerName   in-process teammate 的 agent 名（对齐 CC Task.owner 的匹配源·
 *                    identity.agentName，types.ts:13）；非 teammate 任务为 null
 * @param activity    进行中 teammate 的最近活动摘要（对齐 CC
 *                    {@code TaskListV2} 第二行 {@code progress.recentActivities} 合并结果）；
 *                    非 teammate / 无活动 / 非 running 为 null
 * @param sessionId   创建该后台任务的会话 id（{@link BackgroundTask#sessionId()}）· null = 无会话归属
 *                    （main-thread spawn 无 MDC / 测试直构）。<b>WHY 透出</b>：侧栏
 *                    「正在运行」判定需要把「仍有后台工作的会话」与主 turn 的运行态取并集
 *                    （GET /api/v1/tasks/active），前端只能靠该字段把任务归回会话。
 */
public record TaskDto(
        String id,
        String type,
        String status,
        String description,
        @Nullable String toolUseId,
        long startTime,
        @Nullable Long endTime,
        @Nullable String agentId,
        @Nullable Boolean isBackgrounded,
        @Nullable String ownerName,
        @Nullable String activity,
        @Nullable String sessionId
) {

    /**
     * 从后端 {@link BackgroundTask} 映射（枚举 → CC 小写值域，agentId UUID → 串）。
     * 不含 teammate 活动（无 registry 数据源）；需要活动时用 {@link #from(BackgroundTask, String, String)}。
     *
     * @param task 后端统一 store 中的任务快照
     * @return 前端 DTO
     */
    public static TaskDto from(BackgroundTask task) {
        return from(task, null, null);
    }

    /**
     * 带 teammate 活动信息的映射 · in-process teammate 任务由 {@code TaskController} 从
     * {@code InProcessTeammateTaskRegistry} 取 {@code identity.agentName} 与活动摘要后传入。
     *
     * @param task      后端统一 store 中的任务快照
     * @param ownerName teammate agent 名（非 teammate 传 null）
     * @param activity  活动摘要（无活动/非 running 传 null）
     * @return 前端 DTO
     */
    public static TaskDto from(BackgroundTask task, @Nullable String ownerName, @Nullable String activity) {
        return new TaskDto(
                task.id(),
                task.type().getTypeString(),
                task.status().getStatusString(),
                task.description(),
                task.toolUseId(),
                task.startTime(),
                task.endTime(),
                task.agentId() != null ? task.agentId().toString() : null,
                task.isBackgrounded(),
                ownerName,
                activity,
                task.sessionId()
        );
    }
}
