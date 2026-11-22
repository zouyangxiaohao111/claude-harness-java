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
 * @param scope           DURABLE|SESSION（⚠️ [cwd3 · D6] <b>创建后不可变</b>：非 null ⇒
 *                        {@code ValidationException} ⇒ 400，见 {@code ScheduleService#update}）
 * @param sessionId       创建时绑定的会话（⚠️ [cwd3 · D6] <b>创建后不可变</b>：非 null ⇒ 400）
 * @param agentId         teammate agentId（可空 = 不改）
 * @param sessionMode     [V306] 执行上下文形态（A/B/C/D；可空 = 不改）。⚠️ 与 {@code sessionId}
 *                        不同，它**可以改**（设计稿 ⑥：「会话归属和调度类型可以改，但已造的会话不能改」）：
 *                        改形态会解除旧的会话绑定（会话本身保留在左栏），由新形态在下次 fire 时决定用哪个会话
 * @param notifyChats     [V306] 通知目标聊天 id 列表（可空 = 不改；空列表 = 清空 ⇒ 不通知）
 * @param notifyChannelIds [V306] 冗余渠道 id（可空 = 不改；服务端不依赖它做投递）
 * @param mainThreadAgent [V317] 任务指定的「专家」（主线程 agent）。<b>null = 不改</b>；
 *                        <b>空串 = 清空</b>（回落默认专家）；非空 = 设置。
 *                        仅 {@code NEW_EACH}/{@code NEW_ONCE} 形态可非空，
 *                        {@code REUSE}/{@code NONE} 传非空 ⇒ 400（不静默忽略）。
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
    String agentId,
    SessionMode sessionMode,
    java.util.List<String> notifyChats,
    java.util.List<String> notifyChannelIds,
    String mainThreadAgent
) {
    /**
     * V306 兼容构造器：V306 之前的 10 参调用点（测试夹具）不用改。
     * 三个新字段取 {@code null} = 不改，与 partial-update 语义一致。
     */
    public ScheduleUpdateRequest(
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
    ) {
        this(name, kind, cron, intervalSeconds, runAt, command, description,
            scope, sessionId, agentId, null, null, null, null);
    }
}
