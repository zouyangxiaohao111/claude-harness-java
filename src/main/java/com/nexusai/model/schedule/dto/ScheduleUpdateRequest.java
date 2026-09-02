package com.nexusai.model.schedule.dto;

/**
 * POST /api/v1/schedules/{id} 部分更新请求体（FIX-3 / RV-C-03 G3/G4）。
 *
 * <p><b>为何不复用 {@link ScheduleCreateRequest}</b>（规则七显式暴露，避免语义错位）：
 * <ul>
 *   <li>{@code ScheduleCreateRequest.name} 是 {@code @NotBlank}（create 必填 name），update 是
 *       partial-update 应允许只改 cron/description 而 name 不动 → 需去 {@code @NotBlank}；</li>
 *   <li>{@code ScheduleCreateRequest.id} 是 {@code @JsonIgnore}（CronCreateTool one-shot 预生成 id
 *       jitter 依赖），update 的 id 走路径变量（{@code @PathVariable}），请求体不应再有 id 字段。</li>
 * </ul>
 * 全字段可选（null = 不更新该字段），由 {@link com.nexusai.domain.schedule.ScheduleService#update}
 * 仅覆盖非 null 字段。
 *
 * <p>CC original: RemoteTriggerTool.ts:120-126 {@code update → POST base/{trigger_id}，body 为
 * trigger 记录}（remote-trigger 域，非 cron 域 cronTasks.ts 无 update 的 C07 裁定）。
 *
 * @param name            触发任务名（可空 = 不改）
 * @param kind            调度 kind（cron|once|interval；可空 = 不改）
 * @param cron            kind=cron 的表达式（可空 = 不改）
 * @param intervalSeconds kind=interval 的间隔秒（可空 = 不改）
 * @param runAt           kind=once 的 ISO 8601 执行时间（可空 = 不改）
 * @param command         执行命令（可空 = 不改）
 * @param description     描述（可空 = 不改）
 * @param scope           DURABLE|SESSION（可空 = 不改）
 * @param sessionId       scope=SESSION 的绑定会话（可空 = 不改）
 * @param agentId         teammate agentId（可空 = 不改）
 */
public record ScheduleUpdateRequest(
    String name,
    ScheduleKind kind,
    String cron,
    Integer intervalSeconds,
    String runAt,
    String command,
    String description,
    ScheduleScope scope,
    String sessionId,
    String agentId
) {}
