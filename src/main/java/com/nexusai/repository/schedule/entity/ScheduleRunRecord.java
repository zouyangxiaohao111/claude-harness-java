package com.nexusai.repository.schedule.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * 定时任务的一次执行记录（V306 {@code schedule_runs} 表）。
 *
 * <p><b>为什么需要独立表</b>：现状 {@code schedules.last_run_status='ok'} 是在
 * <b>任务入队那一刻</b>写的（{@code AgentJob.applyFireLifecycle} → {@code ScheduleService.markFired}），
 * agent loop 之后才异步跑 ⇒ <b>跑崩了仍显示「成功」</b>（假信号）。本表记录 run
 * <b>真正结束时</b>的结果，并保留输出摘要供「运行记录」展示。
 *
 * <p><b>为什么不能塞进 QRTZ 表</b>：{@code QRTZ_JOB_DETAILS} 只有 job 定义（无结果列）；
 * {@code QRTZ_FIRED_TRIGGERS} 只存「正在执行」的触发，且 Quartz 触发完成即删行
 * —— 它是运行时锁表，不是历史表。
 *
 * <p><b>状态</b>：{@code running} → {@code ok} / {@code error} / {@code cancelled}。
 * {@code cancelled} 是「用户点了停止」，语义上不是失败（不推 IM 告警）。
 */
@Table("schedule_runs")
public class ScheduleRunRecord {

    @Id private String id;

    /** 指向 {@code schedules.id}。任务被删后本列保留（历史可读），故不加外键。 */
    private String scheduleId;

    /** 冗余存任务名 —— 任务删了也能看懂这条记录是什么。 */
    private String scheduleName;

    /**
     * 本轮<b>实际</b>用的形态（降级后的值，不是创建时配置）。
     * 例：配置 {@code NEW_ONCE} 但专属会话被删 → 本轮降级 {@code NEW_EACH}，这里记 NEW_EACH。
     */
    private String sessionMode;

    /** 触发来源：{@code quartz}（定时）| {@code manual}（REST runNow）。 */
    private String triggerSource;

    /** {@code running} | {@code ok} | {@code error} | {@code cancelled}。 */
    private String status;

    private String startedAt;
    private String finishedAt;
    private Long durationMs;

    /** 本轮实际用的会话 id；{@code NONE} 形态为空。 */
    private String sessionId;

    /** 本轮执行的 prompt（截断后）。 */
    private String prompt;

    /** 结果摘要（{@code AgentState.lastAssistant()} 截断）。 */
    private String outputSummary;

    /** 失败原因（{@code AgentState.lastError()} / 异常消息）。 */
    private String error;

    /**
     * 每个通知目标的投递结果 · JSON 数组：
     * {@code [{"chatId","chatName","platform","ok","error"}]}。
     * null = 本轮没配通知目标（不通知）。
     */
    private String notifyResultJson;

    private String createdAt;

    // ============== getters/setters ==============

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }
    public String getScheduleName() { return scheduleName; }
    public void setScheduleName(String scheduleName) { this.scheduleName = scheduleName; }
    public String getSessionMode() { return sessionMode; }
    public void setSessionMode(String sessionMode) { this.sessionMode = sessionMode; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getOutputSummary() { return outputSummary; }
    public void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getNotifyResultJson() { return notifyResultJson; }
    public void setNotifyResultJson(String notifyResultJson) { this.notifyResultJson = notifyResultJson; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
