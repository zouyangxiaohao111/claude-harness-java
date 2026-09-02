package com.nexusai.model.schedule.dto;

import java.time.OffsetDateTime;

/** 响应：Schedule 完整信息 */
public record ScheduleDto(
    String id,
    String name,
    ScheduleKind kind,
    String cron,
    Integer intervalSeconds,
    String runAt,
    String command,
    String description,
    OffsetDateTime lastRunAt,
    String lastRunStatus,                             // "ok" | "error" | null
    ScheduleScope scope,                              // s14-P1-5: DURABLE | SESSION
    String sessionId,                                 // s14-P1-5: scope=SESSION 时绑定的 session
    String agentId,                                   // CRON-D4: CC original: CronTask.agentId (cronTasks.ts:69) · teammate 创建者; 由 create→toDto 填充 (V9 agent_id 列), 主线程/DURABLE 恒 null
    String boundProject                               // 批次X Q2: DURABLE 任务创建会话绑定项目 (V23 bound_project 列); 由 create→toDto 填充, SESSION/无会话恒 null; fire 时经 TestJob→QueueItem 透传到 CronIdleExecutor 恢复项目上下文
) {}
