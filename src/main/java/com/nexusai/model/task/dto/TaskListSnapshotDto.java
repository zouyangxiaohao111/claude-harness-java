package com.nexusai.model.task.dto;

import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * [task-v2-merge] 任务清单快照（GET /api/v1/tasks/list 响应）· V1（TodoWrite，sessions.todos 列）与
 * V2（TaskCreate，TaskService 文件）<b>互斥</b>合并返回——一个会话只用其一，端点两者都查，前端按非空方显示。
 *
 * <p>V1/V2 互斥判定：{@code TaskSystemConfig.isTodoV2Enabled()}（决策 #65：Web 会话交互 → V2 默认开；
 * cron/后台 → V1）。普通 Web 会话 taskListId = sessionId。
 *
 * @param taskListId 任务列表 ID（V2 TaskService.getTaskListId；V1 会话 ID）
 * @param v2Tasks    V2 任务（TaskService 文件 → {@link TaskItemDto}）
 * @param v1Todos    V1 todo（sessions.todos 列 → {@link TodoWriteTool.TodoItem}）
 * @param updatedAt  快照时间戳（ms）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskListSnapshotDto(
        String taskListId,
        List<TaskItemDto> v2Tasks,
        List<TodoWriteTool.TodoItem> v1Todos,
        long updatedAt
) {}
