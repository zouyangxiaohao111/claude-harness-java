package com.nexusai.model.schedule.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

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

    /**
     * V306 · 执行上下文形态（A/B/C/D）。null → {@link SessionMode#DEFAULT}（REUSE）。
     *
     * <p>语义：{@code REUSE} 复用 sessionId 指的会话；{@code NEW_EACH} 每次触发新建；
     * {@code NEW_ONCE} 首次触发新建后固定复用（任务删才删会话）；{@code NONE} 不产生会话。
     */
    SessionMode sessionMode,

    /**
     * V306 · 通知目标聊天 id（来自 {@code im_chat.id}）。空/null = 不通知。
     *
     * <p>两级选择（先渠道再聊天）在 UI 侧完成，服务端只收最终 id 列表。
     */
    List<String> notifyChats,

    /** V306 · 冗余渠道 id（UI 传，可空；服务端不依赖它做逻辑）。 */
    List<String> notifyChannelIds,

    /**
     * V317 · 任务指定的「专家」（主线程 agent）· 可选（null/空白 = 不指定）。
     *
     * <p>仅 {@link SessionMode#NEW_EACH} / {@link SessionMode#NEW_ONCE} 形态可传非空值；
     * {@code REUSE} / {@code NONE} 传非空 ⇒ 服务端抛 {@code ValidationException} 拒绝
     * （⛔ 不静默忽略：那会让用户以为设置生效了）。
     */
    String mainThreadAgent,

    @JsonIgnore String id                           // CRON-F1: CronCreateTool one-shot 预生成 id（jitter taskId 依赖），REST @JsonIgnore 不可注入；正常 null → 服务端 generateId
) {

    /**
     * 旧签名兼容构造（12 参 · V306 之前）。
     *
     * <p><b>为什么保留</b>：{@code new ScheduleCreateRequest(...)} 在工具路径
     * （{@code CronCreateTool}）、REST 归一化（{@code ScheduleController}）和 13 处单测里
     * 都在用；加字段若不给旧签名，这些调用点全部编译失败。
     *
     * <p>语义等价于「不指定形态/通知目标」⇒ {@code sessionMode=null}（→ 服务端回落
     * {@link SessionMode#DEFAULT} = REUSE）、{@code notifyChats=null}（不通知）。
     * 这正是 V306 之前的既有行为。
     */
    public ScheduleCreateRequest(
        String name, ScheduleKind kind, String cron, Integer intervalSeconds, String runAt,
        String command, String description, ScheduleScope scope, String sessionId,
        String agentId, String boundProject, String id) {
        this(name, kind, cron, intervalSeconds, runAt, command, description, scope, sessionId,
            agentId, boundProject, null, null, null, null, id);
    }
}
