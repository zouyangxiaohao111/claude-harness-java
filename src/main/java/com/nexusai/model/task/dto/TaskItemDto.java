package com.nexusai.model.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.tasks.Task;

import java.util.List;
import java.util.Map;

/**
 * V2 任务清单前端 DTO · CC original: {@code Task}（TaskSchema，Open-ClaudeCode/src/utils/tasks.ts:76-88）。
 *
 * <p><b>WHY（规则九 · 意图）</b>：用户拍板（2026-08-24）前端任务清单统一走
 * {@link com.nexusai.application.agent.tasks.TaskService#listTasks(String)}——V2（TaskCreate）
 * 文件存储 {@code {configHome}/tasks/{taskListId}}。本 DTO 把后端 {@link Task} record 显式投影
 * 为前端可消费的 9 字段（CC TaskSchema 全量），status 归一 CC 小写值域
 * （pending/in_progress/completed）。与既有 {@link TaskDto}（异步后台任务 BackgroundTask 投影）
 * <b>不同源</b>——两者各自映射不同后端类型，前端任务清单与异步任务清单互不混用。
 *
 * <p><b>无 updatedAt</b>：CC TaskSchema 不含时间戳字段（tasks.ts:76-88 无 createdAt/updatedAt），
 * Java {@link Task} 亦无——前端任务清单不消费时间排序（按磁盘 readdir 自然序，CC tasks.ts:443-456）。
 *
 * <p><b>null 省略</b>：{@code @JsonInclude(NON_NULL)} 对齐 CC jsonStringify 省略 undefined
 * （activeForm/owner 未赋值即省略键，与 {@link Task} 落盘形状一致，对齐 Task.java:57 先例）。
 *
 * @param id          任务 ID · CC original: id (tasks.ts:77)
 * @param subject     任务标题 · CC original: subject (tasks.ts:78)
 * @param description 任务描述 · CC original: description (tasks.ts:79)
 * @param activeForm  进行中表单文本（spinner 显示）· CC original: activeForm? (tasks.ts:80)
 * @param owner       所有者 agent ID · CC original: owner? (tasks.ts:81)
 * @param status      状态小写 · CC original: status (tasks.ts:82)
 * @param blocks      此任务阻塞的任务 ID 列表 · CC original: blocks (tasks.ts:83)
 * @param blockedBy   阻塞此任务的任务 ID 列表 · CC original: blockedBy (tasks.ts:84)
 * @param metadata    任意元数据 · CC original: metadata? (tasks.ts:85)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskItemDto(
        String id,
        String subject,
        String description,
        String activeForm,
        String owner,
        String status,
        List<String> blocks,
        List<String> blockedBy,
        Map<String, Object> metadata
) {

    /**
     * 从后端 {@link Task} 映射 · status 经 {@link Task.TaskStatus#toValue()} 输出 CC 小写值域
     * （对齐 Task.java {@code @JsonValue} 落盘语义，tasks.ts:69 TASK_STATUSES）。
     *
     * @param task V2 任务文件 record（{@code TaskService.listTasks} 返回值）
     * @return 前端 DTO
     */
    public static TaskItemDto from(Task task) {
        return new TaskItemDto(
                task.id(),
                task.subject(),
                task.description(),
                task.activeForm(),
                task.owner(),
                task.status().toValue(),
                task.blocks(),
                task.blockedBy(),
                task.metadata()
        );
    }
}
