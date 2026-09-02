package com.nexusai.repository.schedule.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

@Table("schedules")
public class ScheduleRecord {
    @Id private String id;
    private String name;
    private String kind;            // 'cron'|'once'|'interval'
    private String cron;
    private Integer intervalSeconds;
    private String runAt;
    private String command;
    private String description;
    private String lastRunAt;
    private String lastRunStatus;
    /**
     * 任务创建时间（epoch ms）。CC original: CronTask.createdAt
     * (Open-ClaudeCode/src/utils/cronTasks.ts:37)。必填 number，写盘必有
     * (cronTasks.ts:208 createdAt: Date.now())；missed 判定锚点
     * nextCronRunMs(t.cron, t.createdAt) (cronTasks.ts:455)。
     */
    private Long createdAt;
    /**
     * 是否豁免 recurringMaxAgeMs 自动过期。CC original: CronTask.permanent
     * (Open-ClaudeCode/src/utils/cronTasks.ts:57)。可选布尔，缺省 false=非豁免
     * (cronScheduler.ts:59 !t.permanent && nowMs - t.createdAt >= maxAgeMs)。
     */
    private Boolean permanent;
    /**
     * 调度范围（DURABLE|SESSION）。CC original: CronTask.durable
     * (Open-ClaudeCode/src/utils/cronTasks.ts:63)。durable 是 runtime-only flag：
     * durable=false → session-scoped（cronTasks.ts:211-213 addSessionCronTask 仅存内存）；
     * durable=true → 落盘（writeCronTasks strip durable，cronTasks.ts:175）。
     * Java 侧用户拍板维持 SQLite 只补字段：SESSION 任务仍落库 + Quartz 注册（修僵尸），
     * scope 列落库使重启后 selectAll 可辨识（R-1）。映射入口 CronCreateTool:449
     * (durable ? DURABLE : SESSION)。[批次X 行号修正：旧 Javadoc 写 :160，实际
     * effectiveDurable 判定在 :395、scope 分支在 :449（对齐经验 R1"行号易漂"；
     * 批次X 返工 R3 再修正 :447→:449、:393-394→:395，原修正行号自身漂移 2 行）]
     */
    private String scope;
    /**
     * SESSION 任务的绑定 session id（s14 session 归属）。用于 sessionJobs 内存索引
     * 归因 + cleanupBySession 会话结束清理（ScheduleService.sessionJobs）。与
     * agentId（fire 路由）是两个独立字段，不得合并（旧设计把 teammate agentId 映射为
     * sessionId 导致 D4 返工）。DURABLE 任务为 null。
     */
    private String sessionId;
    /**
     * 创建任务的 teammate agentId。CC original: CronTask.agentId（字段声明
     * cronTasks.ts:69；addCronTask 第 5 参 cronTasks.ts:199；addSessionCronTask 按
     * agentId 条件透传 cronTasks.ts:212 ({ ...task, ...(agentId ? { agentId } : {}) })；
     * CronCreateTool.ts:126 getTeammateContext()?.agentId 作实参）。
     *
     * <p>语义：teammate 创建的 SESSION（durable=false）任务设此值，TestJob.fire 按
     * dto.agentId() 分发到该 teammate 队列（useScheduledTasks.ts:92 if(task.agentId)）。
     * 主线程创建 / DURABLE 任务为 null。Java 侧用户拍板（OPD-D4-GAP-5 方案 A）落库
     * V9 agent_id 列（CC 虽 runtime-only 不写盘，Java SESSION 本就落库 OPD-Cron-02）。
     */
    private String agentId;
    /**
     * DURABLE 任务的创建会话绑定项目（V23 bound_project 列 · 批次X Q2）。
     *
     * <p>CC original: 无字段（CronTask 类型无 cwd/dir/project，cronTasks.ts:30-70）。
     * CC durable 任务的"项目锚"= 文件位置 &lt;projectRoot&gt;/.claude/scheduled_tasks.json
     * （cronTasks.ts:74-83 getCronFilePath = join(dir ?? getProjectRoot(), ...)）；
     * Java 全局单表无法从存储位置推断项目锚，须每任务一列显式存。
     *
     * <p>取值语义（用户拍板 Q2）：DURABLE 任务创建时填<b>创建会话的绑定项目</b>
     * （SessionProjectRoot.getForSession(sessionId)，对齐 CC STATE.projectRoot 启动/绑定
     * 目录 state.ts:511-513/523-525，非 sessionCwd —— sessionCwd 会随会话内 cd 漂移）；
     * 无会话 REST 直建（sessionId=null）→ null（fire 兜底 user.dir，已知差异：
     * CC 所有 durable 任务都在会话里创建）。SESSION 任务恒 null（其项目锚由 sessionId
     * 恢复路径承载，CronIdleExecutor.runOneAgentLoop SESSION 分支）。
     *
     * <p>fire 消费：CronIdleExecutor 对 DURABLE（sessionId=null）任务把本列值注入执行
     * 线程项目上下文（对齐 CC fire 复用已绑定会话 cwd），使 CwdResolution.getCwd 解析到
     * 创建项目而非 user.dir。
     */
    private String boundProject;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public Integer getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(Integer intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    public String getRunAt() { return runAt; }
    public void setRunAt(String runAt) { this.runAt = runAt; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(String lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Boolean getPermanent() { return permanent; }
    public void setPermanent(Boolean permanent) { this.permanent = permanent; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getBoundProject() { return boundProject; }
    public void setBoundProject(String boundProject) { this.boundProject = boundProject; }
}