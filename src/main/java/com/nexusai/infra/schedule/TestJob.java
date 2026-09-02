package com.nexusai.infra.schedule;

import com.nexusai.application.agent.subagent.AgentMessageBus;
import com.nexusai.application.agent.subagent.AgentMessageBus.InboxMessage;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quartz Job bean for Schedule feature (C4).
 *
 * <p>v1 (修补前): 仅 log, ScheduleEntity.command 字段未消费, 核心业务链路断裂.
 *
 * <p>v2 (s14 修补): 注入 ScheduleService + NotificationQueue (Spring beans),
 * 触发时查 ScheduleEntity, enqueue notification 到 agent_loop (对齐 CC onFireTask +
 * enqueuePendingNotification).
 *
 * <p>v3 (CRON-D1 对齐 CC enqueueForLead, useScheduledTasks.ts:71-82): fire 时把
 * <b>原始 prompt</b> (dto.command()) 以 mode='prompt' + priority=LATER + isMeta=true +
 * workload=WORKLOAD_CRON 入队统一 notificationQueue。移除旧版 XML 通知包装与 LLM 回调死代码
 * （CC 无 LLM 回调概念）.
 *
 * <p>v4 (CRON-D4 对齐 CC onFireTask, useScheduledTasks.ts:91-108): fire 按
 * {@code dto.agentId()} 分发——非空 → teammate 路由（findTeammateByAgentId 等价 →
 * 非 terminal → AgentMessageBus.sendToAgent 注入 prompt；teammate 不存在/terminal →
 * scheduleService.delete 孤儿清理）；空 → 保持 v3 lead 入队。agentId 非空分支
 * <b>绝不</b>入 lead 队列（CC :92-99 return 短路，C5 不变量）。
 *
 * <p>v5 (CRON-F2 运行时门控, 对齐 CC isKilled 每 tick): execute() 开头检查
 * {@link CronEnabledGates#isKairosCronEnabled()}——门关 → 直接 return 不 fire
 * （对齐 cronScheduler.ts:231 check() 每 tick 顶部 {@code if (isKilled?.()) return} +
 * useScheduledTasks.ts:119 {@code isKilled: () => !isKairosCronEnabled()}；OPD-Cron-07-h
 * 拍板「关闭后已注册任务立即停止」）。null（未注入）→ fail-open 视为开。
 *
 * <p>v6 (CRON-F4 接线 fire-then-delete, 对齐 CC cronScheduler.ts:299-344+358-369): fire 分发
 * （teammate 注入 / lead 入队 / 孤儿清理）统一后调 {@link ScheduleService#deleteAfterFire}——
 * one-shot→删 / aged recurring→删 / 未 aged recurring→保留；保留时调
 * {@link ScheduleService#markFired} 写 lastRunAt=now（对齐 CC markCronTasksFired,
 * cronTasks.ts:261-278）。兑现 ChatService:447「残留任务下次 fire 时由 deleteAfterFire 兜底」声明。
 *
 * <p>The {@code @Component} annotation makes this a singleton Spring bean, but Quartz
 * requires Job classes to be public and have a public no-arg constructor. Quartz
 * instantiates the Job class via reflection on each fire. SpringBeanJobFactory 是更规范
 * 模式, 但 v1 用 setter 注入简化.
 */
@Component
public class TestJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(TestJob.class);

    @Autowired(required = false)
    private ScheduleService scheduleService;

    @Autowired(required = false)
    private NotificationQueue notificationQueue;

    @Autowired(required = false)
    private BackgroundTaskRunner backgroundTaskRunner;

    @Autowired(required = false)
    private AgentMessageBus agentMessageBus;

    /**
     * 运行时门控 · CC original: isKilled (cronScheduler.ts:231 check() 每 tick 顶部 gate /
     * useScheduledTasks.ts:119 isKilled: () => !isKairosCronEnabled())。对齐 CC isKairosCronEnabled
     * (ScheduleCronTool/prompt.ts:36-45)。null（未注入）→ fail-open，与 CronCreateTool:141
     * null→关 行为偏差已登记（CRON-F2 concern）。
     */
    @Autowired(required = false)
    private CronEnabledGates cronGates;

    /**
     * IMPL-10 (NEW-12): P2 遥测 · 对齐 CC logEvent (cronScheduler.ts:205-212/288-292/308-312)。
     * 无 bean（非 Spring 单测手动 new，Quartz 反射实例由 AutowireCapableBeanJobFactory autowireBean
     * 注入）→ null → 静默跳过，不影响 fire 行为。
     */
    @Autowired(required = false)
    private Telemetry telemetry;

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        String scheduleId = ctx.getJobDetail().getJobDataMap().getString("scheduleId");
        String scheduleName = ctx.getJobDetail().getJobDataMap().getString("scheduleName");
        String scheduleKind = ctx.getJobDetail().getJobDataMap().getString("scheduleKind");
        log.info("[Quartz] TestJob fired for schedule id={} name={} kind={} fireTime={}",
            scheduleId, scheduleName, scheduleKind, ctx.getFireTime());
        // CRON-B4-4（决策 #15 / OPD-EL-04）：共享 fire body，Quartz worker 路径忽略返回值
        fire(scheduleId);
    }

    /**
     * CRON-B4-4（决策 #15 / OPD-EL-04）：once 任务同步 fire · 供 REST runNow 同步路径调用。
     *
     * <p>与 {@link #execute} 共享 {@link #fire} body，但无 JobExecutionContext（同步调用无 ctx）。
     * runNow once 路径依赖本方法在返回前完成 fire-then-delete（deleteAfterFire 同步删行），
     * 避免 triggerNow 异步入队后 worker 读行已删的竞态（fire 丢失）。
     *
     * @param scheduleId 调度 id
     * @return true = 已完成 fire 分发（gate 通过 + 任务存在 + 已入队/路由 + 生命周期）；false = 未 fire
     */
    public boolean fireSynchronously(String scheduleId) {
        return fire(scheduleId);
    }

    /**
     * 共享 fire body · CC original: onFireTask 分发（cronScheduler.ts:293-297）+ fire-then-delete
     * （cronScheduler.ts:299-344 aged 判定 → 删/保留）。
     *
     * <p>编排链：gate（isKilled 每 tick）→ getById → command 非空 → route/enqueue →
     * applyFireLifecycle（deleteAfterFire + markFired）。Quartz worker 路径忽略返回值（行为不变），
     * 同步路径（{@link #fireSynchronously}）以返回值判 executed。
     *
     * @param scheduleId 调度 id
     * @return true = 已完成 fire 分发；false = 未 fire（门控关 / 注入缺失 / 数据缺失 / 异常）
     */
    private boolean fire(String scheduleId) {
        // CRON-F2 运行时门控 · CC original: isKilled (cronScheduler.ts:231 check() 每 tick 顶部
        // `if (isKilled?.()) return` — bail before firing anything; useScheduledTasks.ts:119
        // isKilled: () => !isKairosCronEnabled())。OPD-Cron-07-h 拍板「关闭后已注册任务立即停」：
        // 门关 → 已注册 Quartz 任务不 fire（不查 dto、不入队、不触发 teammate 路由/孤儿清理）。
        // null（未注入）→ fail-open 视为开，保持现状。
        if (cronGates != null && !cronGates.isKairosCronEnabled()) {
            log.warn("[Quartz] CRON-F2: 定时功能已关闭（isKairosCronEnabled=false），跳过 fire "
                    + "scheduleId={}（对齐 CC isKilled 每 tick gate, cronScheduler.ts:231）",
                scheduleId);
            return false;
        }

        if (scheduleService == null) {
            log.warn("[Quartz] CRON-D1 LIMIT: ScheduleService 未注入, 仅 log");
            return false;
        }
        try {
            ScheduleDto dto = scheduleService.getById(scheduleId);
            if (dto == null) {
                log.warn("[Quartz] CRON-D1: ScheduleDto null for id={}", scheduleId);
                return false;
            }
            // CC 语义: 'prompt' 字段 → 原始待执行 prompt 注入 (非通知摘要)
            // ScheduleDto 是 record, accessor 是 command() 而非 getCommand()
            String command = dto.command();
            if (command == null || command.isBlank()) {
                log.warn("[Quartz] CRON-D1: ScheduleDto id={} command 字段为空", scheduleId);
                return false;
            }
            // IMPL-10 (NEW-12): fire 事件 · CC cronScheduler.ts:283 now>=next → :288-292 logEvent →
            // :293-297 分发 —— fire 决策（gate/数据校验）后、分发前发射，每到点 tick 一次
            emitFireTelemetry(scheduleId, dto.kind());
            String agentId = dto.agentId();
            if (agentId != null && !agentId.isBlank()) {
                // CRON-D4: teammate 路由或孤儿清理（CC useScheduledTasks.ts:92-99 return 短路，
                // agentId 非空分支绝不入 lead —— C5 不变量）
                routeToTeammateOrCleanup(scheduleId, agentId, command);
            } else {
                // agentId 为空 → 保持 CRON-D1 lead 入队（对齐 CC onFireTask :110-114 lead 路径）
                // CRON-D5 改1: 透传 dto.sessionId()（SESSION scope 非空 / DURABLE null），
                // 消费线程（CronIdleExecutor）据此恢复 MDC + cwd 归组创建会话。
                // 批次X Q2: DURABLE 任务透传 dto.boundProject()（创建会话绑定项目，V23 列），
                // 消费线程据此恢复项目上下文（CwdResolution 解析到创建项目而非 user.dir）。
                enqueueLead(scheduleId, command, dto.sessionId(), dto.boundProject());
            }
            // CRON-F4: fire 后生命周期 —— 对所有 fire 路径生效（teammate 注入 / lead 入队 / 孤儿清理）。
            // 注意 routeToTeammateOrCleanup 内部 Bean 未注入时 early return，此时未真正 fire，
            // 不进入生命周期（fail-closed，不产生幻影 markFired）。
            applyFireLifecycle(scheduleId);
            return true;
        } catch (Exception e) {
            log.error("[Quartz] CRON-D1: 入队失败 for id={}", scheduleId, e);
            return false;
        }
    }

    /**
     * IMPL-10 (NEW-12): fire 事件遥测 · CC original: {@code logEvent('tengu_scheduled_task_fire',
     * {recurring, taskId})}（cronScheduler.ts:288-292，check() 内 {@code now >= next} 判定
     * :283 通过后、onFireTask/onFire 分发 :293-297 之前发射；每 tick 每任务一次，含 aged
     * 任务最后一次 fire，同 tick 后续由 deleteAfterFire 补发 expired）。
     *
     * <p>位置语义对齐 CC：gate 关（Java fire() :139-144 return false，CC :231 isKilled
     * return）、任务不存在、command 空白（数据校验失败）均不发；runNow 同步路径（Java 独有，
     * OPD-Cron-09-8 保留）共享本 fire body，同样发射（CC 载荷无触发源字段，不区分）。
     *
     * <p>recurring 判定对齐 {@link ScheduleService#isRecurringKind}（:345-348）：kind==cron
     * 或 kind==interval → true；once → false（CC {@code t.recurring ?? false}，载荷 key
     * 逐字保留：recurring / taskId）。telemetry null（未注入）→ 静默跳过，不改变 fire 行为。
     *
     * @param scheduleId 任务 id（CC 载荷 taskId）
     * @param kind       ScheduleKind（cron/once/interval）
     */
    private void emitFireTelemetry(String scheduleId, ScheduleKind kind) {
        if (telemetry == null) {
            return;
        }
        boolean recurring = kind == ScheduleKind.cron || kind == ScheduleKind.interval;
        telemetry.recordEvent("tengu_scheduled_task_fire",
            Map.of("recurring", recurring, "taskId", scheduleId));
        if (log.isDebugEnabled()) {
            log.debug("[Quartz] IMPL-10: tengu_scheduled_task_fire 发射 scheduleId={} recurring={} "
                    + "（对齐 CC cronScheduler.ts:288-292）",
                scheduleId, recurring);
        }
    }

    /**
     * CRON-F4: fire 后生命周期（fire-then-delete）· 对齐 CC cronScheduler.ts:299-344 + 358-369.
     *
     * <p>CC 原文（cronScheduler.ts:302-343）：fire 分发（onFireTask）后立即做 aged 判定：
     * recurring 且未 aged → 保留并写 lastFiredAt（firedFileRecurring 累积 →
     * markCronTasksFired 批量写，cronTasks.ts:261-278）；one-shot 或 aged recurring → 删除
     * （session → removeSessionCronTasks 同步；file → removeCronTasks 异步）。删除/保留
     * 对所有 fire 生效（含 teammate 注入 / lead 入队 / 孤儿清理）。
     *
     * <p>Java 等价：{@link ScheduleService#deleteAfterFire} 返回 true=删 / false=保留（recurring
     * 未 aged）；保留时调 {@link ScheduleService#markFired} 写 lastRunAt=now + lastRunStatus=ok
     * （对齐 CC t.lastFiredAt = firedAt，cronTasks.ts:272）。孤儿路径 routeToTeammateOrCleanup
     * 已 delete 后，此处 deleteAfterFire 报「任务不存在」warn + markFired 0 行 no-op —— 幂等，
     * 对齐 CC 二次 removeCronTasks（useScheduledTasks.ts:106 + cronScheduler.ts:329/336 双删）。
     *
     * <p>recurring 保留依赖 Quartz CronTrigger 自动重排（CC 显式重算 newNext；Java 由 Quartz
     * 下次 cron 到点再 fire，deleteAfterFire=false 即不 unregister）。
     *
     * @param scheduleId 本次 fire 的调度 id
     */
    private void applyFireLifecycle(String scheduleId) {
        try {
            OffsetDateTime firedAt = OffsetDateTime.now();
            // CC cronScheduler.ts:302 aged 判定 → deleteAfterFire 决策（true=删 / false=保留）
            boolean deleted = scheduleService.deleteAfterFire(scheduleId);
            if (!deleted) {
                // CC cronScheduler.ts:324/358-360 markCronTasksFired 批量写 lastFiredAt（Java: lastRunAt）
                int rows = scheduleService.markFired(List.of(scheduleId), firedAt);
                log.info("[Quartz] CRON-F4: fire 后保留 recurring，回写 lastRunAt scheduleId={} "
                        + "firedAt={} rows={}（对齐 CC cronScheduler.ts:358-360 markCronTasksFired）",
                    scheduleId, firedAt, rows);
            } else {
                // CC cronScheduler.ts:325-344 删除路径 —— one-shot / aged recurring fire 后删
                log.info("[Quartz] CRON-F4: fire 后删除（one-shot/aged recurring）scheduleId={} "
                        + "firedAt={}（对齐 CC cronScheduler.ts:325-344 fire-then-delete）",
                    scheduleId, firedAt);
            }
        } catch (Exception e) {
            log.error("[Quartz] CRON-F4: 生命周期执行失败 scheduleId={}（不阻断 fire 分发）",
                scheduleId, e);
        }
    }

    /**
     * CRON-D4: teammate 路由或孤儿清理 · 对齐 CC onFireTask 分支
     * (Open-ClaudeCode/src/hooks/useScheduledTasks.ts:92-108).
     *
     * <p>CC 语义：
     * <pre>
     * if (task.agentId) {                                  // :92
     *   const teammate = findTeammateTaskByAgentId(task.agentId, tasks)  // :93-96
     *   if (teammate && !isTerminalTaskStatus(teammate.status)) {        // :97
     *     injectUserMessageToTeammate(teammate.id, task.prompt)          // :98
     *     return                                                          // :99
     *   }
     *   logForDebugging(...)                                              // :101-105
     *   void removeCronTasks([task.id])                                   // :106
     *   return                                                            // :107
     * }
     * </pre>
     *
     * <p>等价实现：
     * <ul>
     *   <li>teammate 存在且非 terminal → {@code agentMessageBus.sendToAgent(agentId,
     *       new InboxMessage("user_message", prompt))}（等价 CC injectUserMessageToTeammate
     *       InProcessTeammateTask.tsx:68-84 把 prompt 注入 teammate 消息队列；CC :78
     *       pendingUserMessages 追加）；return</li>
     *   <li>teammate 不存在或 terminal（CC :97 不成立）→ 孤儿 cron 删除：
     *       {@code scheduleService.delete(scheduleId)}（等价 CC :106 removeCronTasks，
     *       Java ScheduleService.delete = unregister + deleteById + sessionJobs 同步）;
     *       return</li>
     * </ul>
     *
     * <p>WHY（意图）：teammate 已消失/终态的任务 cron 若继续每 tick fire 只会投到空处
     * （CC :103-105 注释 "clean up the orphaned cron so it doesn't keep firing into nowhere"）。
     * agentId 非空分支绝不入 lead 队列（CC :99 return 短路）。
     *
     * <p>Quartz 反射实例化 Job：BackgroundTaskRunner/AgentMessageBus 未注入时
     * （required=false + null 短路），无法路由也不入 lead，fail-closed 记录 warn 后 return。
     *
     * @param scheduleId 调度 id（孤儿清理目标）
     * @param agentId    teammate agentId（ScheduleDto.agentId 透传）
     * @param command    待注入 prompt（ScheduleDto.command）
     */
    private void routeToTeammateOrCleanup(String scheduleId, String agentId, String command) {
        if (backgroundTaskRunner == null || agentMessageBus == null) {
            log.warn("[Quartz] CRON-D4 LIMIT: BackgroundTaskRunner/AgentMessageBus 未注入, "
                    + "teammate cron id={} agentId={} 无法路由, 不 lead 入队", scheduleId, agentId);
            return;
        }
        // CC :93-96 findTeammateTaskByAgentId → BackgroundTaskRunner.findTeammateByAgentId
        BackgroundTask teammate = backgroundTaskRunner.findTeammateByAgentId(agentId);
        // CC :97 teammate && !isTerminalTaskStatus(teammate.status)
        if (teammate != null && !teammate.status().isTerminal()) {
            // CC :98 injectUserMessageToTeammate — 等价把 prompt 注入 teammate inbox
            agentMessageBus.sendToAgent(agentId,
                new InboxMessage(InboxMessage.TYPE_USER_MESSAGE, command));
            log.info("[Quartz] CRON-D4: teammate cron 注入 prompt, scheduleId={}, "
                    + "agentId={}, taskId={}, prompt长度={}",
                scheduleId, agentId, teammate.id(), command.length());
            return;
        }
        // CC :101-108 teammate 已消失/终态 → 孤儿 cron 清理 (removeCronTasks 等价 delete)
        log.warn("[Quartz] CRON-D4: teammate agentId={} 已消失或终态(status={}), "
                + "清理孤儿 cron scheduleId={} (对齐 CC useScheduledTasks.ts:101-108)",
            agentId, teammate != null ? teammate.status().getStatusString() : "无匹配任务", scheduleId);
        scheduleService.delete(scheduleId);
    }

    /**
     * v3 lead 入队 · 对齐 CC enqueueForLead (useScheduledTasks.ts:71-82 + :110-114).
     *
     * <p>value=原始 prompt, mode='prompt', priority='later', isMeta=true,
     * workload=WORKLOAD_CRON; G-13 主线程 agentId=null.
     *
     * <p><b>CRON-D5 改1</b>（会话归组）: {@code sessionId} 透传创建会话 id（SESSION scope 非空 /
     * DURABLE null）写入 {@code QueueItem.sessionId}。CC 单进程单会话 ambient 上下文无需存储；
     * Java 多会话 web 服务队列是跨线程异步边界（Quartz worker 线程 MDC 到消费线程已丢失），
     * sessionId 必须随队列项存活，消费线程（CronIdleExecutor.runOneAgentLoop）恢复 MDC +
     * cwd 归组创建会话。
     *
     * <p><b>批次X Q2</b>（DURABLE 项目锚恢复）: {@code boundProject} 透传创建会话绑定项目
     * （ScheduleDto.boundProject，DB bound_project 列 V23；SESSION scope / 无会话直建为 null）
     * 写入 {@code QueueItem.boundProject}。对齐 CC durable 任务的项目锚=文件位置
     * （cronTasks.ts:74-83，一个项目一个文件）；Java 全局单表跨线程边界必须随队列项存活，
     * 消费线程（CronIdleExecutor.runOneAgentLoop）据此把项目上下文注入执行线程
     * （CwdResolution.runWithCwdOverride），使 CwdResolution.getCwd 解析到创建项目而非 user.dir。
     *
     * <p><b>[cron-durable-session-fire]</b> {@code sessionId} 透传创建会话 id（SESSION scope 非空 /
     * DURABLE scope 创建于会话也非空，CronCreateTool DURABLE 分支存创建会话）：CC durable fire 归
     * 创建会话（useScheduledTasks.ts:71-82）→ transcript 写创建会话文件；Java 消费线程
     * （CronIdleExecutor.runOneAgentLoop）判定创建会话存活 → transcript 归创建会话；已关 →
     * headless 无 transcript。{@code scheduleId} 透传调度 id（TestJob.execute JobDataMap:99 取到）
     * 写入 {@code QueueItem.scheduleId}（原 per-task 虚拟键派生源已删，现仅供日志归组/诊断）。
     *
     * @param scheduleId   调度 id（日志归组/诊断）
     * @param command      原始待执行 prompt
     * @param sessionId    创建会话 id（ScheduleDto.sessionId，DB scope/session_id 列优先）；
     *                     SESSION 生命周期绑定 / DURABLE 归属对话（创建会话）
     * @param boundProject 创建会话绑定项目（ScheduleDto.boundProject，DB bound_project 列 V23）；
     *                     SESSION scope / 无会话直建为 null
     */
    private void enqueueLead(String scheduleId, String command, String sessionId, String boundProject) {
        if (notificationQueue == null) {
            log.warn("[Quartz] CRON-D1 LIMIT: NotificationQueue 未注入, 仅 log");
            return;
        }
        // [cron-userMessageId] cron 命令入队生成 user 消息 id（msg-xxx）：createQueuedUserMessage 与
        //   setStreamContext 共用 cmd.uuid() → 实时 chunk userMessageId 与 DB 落库一致（此前 uuid=null →
        //   随机兜底 id + streamUserMessageId=null → 前端链条错乱）
        QueueItem item = new QueueItem(
            command, "prompt", NotificationQueue.Priority.LATER, null,
            generateId("msg"), true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId, boundProject,
            scheduleId);
        notificationQueue.enqueue(item);
        log.info("[Quartz] CRON-D1: enqueued 原始 prompt 入队, scheduleId={}, command长度={}, "
                + "mode={}, isMeta={}, workload={}, sessionId={}, boundProject={}",
            scheduleId, command.length(), item.mode(), item.isMeta(), item.workload(), sessionId,
            boundProject);
    }

    /** 消息 id 生成（msg-8hex · 与 ChatService.generateId 同构，cron user 消息落库/推流共用键） */
    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
