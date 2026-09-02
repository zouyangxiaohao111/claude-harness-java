package com.nexusai.model.schedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/schedules 请求 · PATCH 也复用此结构（全字段可选） */
public record ScheduleCreateRequest(
    @NotBlank @Size(max = 64) String name,
    ScheduleKind kind,                              // 必填
    String cron,                                    // kind=cron 时必填
    Integer intervalSeconds,                        // kind=interval 时必填
    String runAt,                                   // kind=once 时必填（ISO 8601）
    String command,
    String description,
    ScheduleScope scope,                            // s14-P1-5: DURABLE (default) | SESSION
    String sessionId,                               // s14-P1-5: scope=SESSION 时绑定 session（生命周期）；[cron-durable-session-fire] DURABLE 也存创建会话（归属对话/注入目标，fire 存活时 transcript 归创建会话）
    String agentId,                                 // D4: teammate agentId（CC original: CronTask.agentId cronTasks.ts:69 / CronCreateTool.ts:126）；主线程/DURABLE=null
    String boundProject,                            // 批次X Q2: DURABLE 任务创建会话绑定项目（CC original: 无字段——CC durable 项目锚=文件位置 cronTasks.ts:74-83；Java 全局单表须显式落列）；无会话 REST 直建→null；SESSION 恒 null
    @JsonIgnore String id                           // CRON-F1: CronCreateTool one-shot 预生成 id（jitter taskId 依赖），REST @JsonIgnore 不可注入；正常 null → 服务端 generateId
) {}
