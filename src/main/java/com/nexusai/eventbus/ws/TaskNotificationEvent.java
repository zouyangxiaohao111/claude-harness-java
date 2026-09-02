package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 后台任务终态通知（结构化 STOMP 直推）· CC original: TaskNotificationSdkEvent
 * （sdkEventQueue.ts:41-54）。
 *
 * <p>topic: {@code /topic/tasks}（复用 FR-5 既有 SDK 事件 topic；事件自带 {@code sessionId}
 * 供前端按会话过滤）。
 *
 * <p><b>[cron-task-inject-align C8 · 决策8]</b>：SdkEventQueue.TaskNotificationEvent 仅在 turn
 * 顶部经 LlmAgentLoop drain（:3885-3893）后推 /topic/tasks —— 空闲路径 / 无 turn 无 STOMP 推送。
 * 本事件由 {@code BackgroundTaskRunner.emitTerminatedSdk} 单点直推，使<b>空闲路径也收到</b>
 * 结构化 task_notification。
 *
 * <p>字段命名贴合既有 SdkEventQueue.TaskNotificationEvent 契约：{@code task_id} / {@code status} /
 * {@code summary}（snake_case @JsonProperty，与 FR-5 一致）；{@code sessionId} 由
 * {@link StreamEvent} 承载（JSON 名 {@code sessionId}）。
 *
 * <p>CC original: {@code task_id} / {@code status} / {@code summary}
 * （sdkEventQueue.ts:41-54，TaskNotificationSdkEvent 三字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskNotificationEvent extends StreamEvent {

    /** 任务 ID · CC original: TaskNotificationSdkEvent.task_id（sdkEventQueue.ts:41-54） */
    @JsonProperty("task_id")
    private final String taskId;

    /** 终态 · CC original: TaskNotificationSdkEvent.status —— 'completed' | 'failed' | 'stopped'（sdkEventQueue.ts:41-54） */
    private final String status;

    /** 任务描述摘要 · CC original: TaskNotificationSdkEvent.summary（sdkEventQueue.ts:41-54） */
    private final String summary;

    /**
     * @param sessionId 创建会话 id（可 null —— headless 任务无会话；前端按此过滤）
     * @param taskId    任务 id（同时复用 StreamEvent userMessageId 槽位作锚点 id）
     * @param status    终态串（'completed' | 'failed' | 'stopped'）
     * @param summary   任务描述摘要（task.description()）
     */
    public TaskNotificationEvent(String sessionId, String taskId, String status, String summary) {
        super("task.notification", sessionId, taskId);
        this.taskId = taskId;
        this.status = status;
        this.summary = summary;
    }

    public String getTaskId() { return taskId; }
    public String getStatus() { return status; }
    public String getSummary() { return summary; }
}
