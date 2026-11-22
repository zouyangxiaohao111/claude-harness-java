package com.nexusai.model.schedule.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 响应：Schedule 完整信息。
 *
 * <p>V306 新增三个字段：{@code sessionMode}（执行上下文形态）、{@code notifyChats}
 * （通知目标聊天 id）、{@code notifyChannelIds}（冗余渠道 id）。
 */
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
    String lastRunStatus,                             // "ok" | "error" | "cancelled" | null
    ScheduleScope scope,                              // s14-P1-5: DURABLE | SESSION
    String sessionId,                                 // s14-P1-5: scope=SESSION 时绑定的 session
    String agentId,                                   // CRON-D4: CC original: CronTask.agentId (cronTasks.ts:69) · teammate 创建者; 由 create→toDto 填充 (V9 agent_id 列), 主线程/DURABLE 恒 null
    String boundProject,                              // 批次X Q2: DURABLE 任务创建会话绑定项目 (V23 bound_project 列); 由 create→toDto 填充, SESSION/无会话恒 null; fire 时经 AgentJob→QueueItem 透传到 CronIdleExecutor 恢复项目上下文

    /** V306 · 执行上下文形态（A/B/C/D）· 缺省 {@link SessionMode#REUSE}。 */
    SessionMode sessionMode,

    /**
     * V306 · 通知目标（聊天 id 列表，来自 {@code im_chat.id}）。
     *
     * <p>空列表 = 不通知（结果只在「运行记录」里看）。「通知本人」= 其中一个 id
     * 是「机器人 ↔ 你」那条 p2p 会话。存 id 而不是平台名：只选渠道不知道发给谁。
     */
    List<String> notifyChats,

    /** V306 · 冗余渠道 id（列表展示省一次 join）。 */
    List<String> notifyChannelIds,

    /**
     * V317 · 任务指定的「专家」（主线程 agent）· {@code schedules.main_thread_agent}。
     *
     * <p>null/空 = 不指定（沿用会话列，与改动前行为完全一致）。
     * 仅 {@link SessionMode#NEW_EACH} / {@link SessionMode#NEW_ONCE} 形态可非空。
     */
    String mainThreadAgent
) {
    /**
     * V306 兼容构造器：保持 V306 之前的 14 参调用点（约 10 个测试夹具）不用改。
     *
     * <p>⚠️ 加字段会打断**所有** `new ScheduleDto(...)` —— 与其改 40 处调用点，
     * 不如留一个旧签名委托到 canonical（同 {@code ScheduleCreateRequest} 的处理方式）。
     * 三个新字段取缺省：形态 {@code null}（读侧按 {@link SessionMode#DEFAULT} 处理）、
     * 通知目标 {@code null}（= 不通知，与空列表同义）。
     */
    public ScheduleDto(
        String id,
        String name,
        ScheduleKind kind,
        String cron,
        Integer intervalSeconds,
        String runAt,
        String command,
        String description,
        OffsetDateTime lastRunAt,
        String lastRunStatus,
        ScheduleScope scope,
        String sessionId,
        String agentId,
        String boundProject
    ) {
        this(id, name, kind, cron, intervalSeconds, runAt, command, description,
            lastRunAt, lastRunStatus, scope, sessionId, agentId, boundProject,
            null, null, null);
    }

    /**
     * V317 兼容构造器：保持 V317 之前的 17 参调用点（各 AgentJob/工具契约测试夹具）不用改。
     *
     * <p>「专家」缺省 {@code null} = 不指定，与 V317 之前的行为完全一致。
     */
    public ScheduleDto(
        String id,
        String name,
        ScheduleKind kind,
        String cron,
        Integer intervalSeconds,
        String runAt,
        String command,
        String description,
        OffsetDateTime lastRunAt,
        String lastRunStatus,
        ScheduleScope scope,
        String sessionId,
        String agentId,
        String boundProject,
        SessionMode sessionMode,
        List<String> notifyChats,
        List<String> notifyChannelIds
    ) {
        this(id, name, kind, cron, intervalSeconds, runAt, command, description,
            lastRunAt, lastRunStatus, scope, sessionId, agentId, boundProject,
            sessionMode, notifyChats, notifyChannelIds, null);
    }
}
