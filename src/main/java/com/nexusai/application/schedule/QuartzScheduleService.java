package com.nexusai.application.schedule;

import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.application.agent.tool.config.CronJitterProperties;
import com.nexusai.application.agent.tool.cron.CronExpressionConverter;
import com.nexusai.application.agent.tool.cron.CronJitter;
import com.nexusai.infra.schedule.TestJob;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * C4 Schedule 后端：把 ScheduleRecord 同步到 Quartz Scheduler。
 *
 * <p>JobKey 命名规则：{@code "schedule-" + scheduleId}（如 {@code schedule-sch-3a7f9d12}）
 *
 * <p>三种 trigger：
 * <ul>
 *   <li>kind=cron     → CronTrigger（正常周期），cron 表达式必须能 parse（否则抛
 *       ValidationException）；CRON-B2-1 recurring 首触发抖动（决策 #1）开启时另加 kickstart
 *       SimpleTrigger（startAt=t1+jitter，repeatCount=0）双 trigger，首 fire 由 kickstart 承担、
 *       周期由 CronTrigger 承担（对齐 CC jitteredNextCronRunMs cronTasks.ts:381-398）</li>
 *   <li>kind=once     → SimpleTrigger，startTime=runAt（parseOffsetDateTime），repeatCount=0</li>
 *   <li>kind=interval → SimpleTrigger，repeatInterval=intervalSeconds*1000，repeatCount=-1（无限）</li>
 * </ul>
 *
 * <p>registerSchedule 是幂等的：job 已存在时先删该 job 下全部旧 trigger 再挂新集合（统一处理
 * 双约束 OR 的变体数变化）；job 不存在时直接 scheduleJob。deleteSchedule 删 job + 关联 trigger。
 *
 * <p>kind=cron 双约束 OR（OPD-Cron-T1-05）：cron 字段可能含 {@link CronExpressionConverter#VARIANT_SEPARATOR}
 * join 的多变体串，本服务拆回逐变体注册多个 CronTrigger 挂到同一 JobDetail（任一匹配即触发，
 * 对齐 CC cron.ts:151-158 dom/dow OR 语义）；单变体仍单 trigger。
 */
@Service
public class QuartzScheduleService {

    private static final Logger log = LoggerFactory.getLogger(QuartzScheduleService.class);

    @Autowired private Scheduler scheduler;

    /**
     * CRON-B2-1 · recurring 首触发抖动配置源（application.yml {@code nexusai.cron.jitter.*}）。
     * CC original: getCronJitterConfig (cronJitterConfig.ts:67-75)；{@code @Autowired(required=false)}
     * 缺省（无 bean / 单测直 new）→ null → buildTrigger 回退 {@link CronJitter.CronJitterConfig#DEFAULT}
     * （对齐 CC DEFAULT_CRON_JITTER_CONFIG cronTasks.ts:348-355）。
     */
    @Autowired(required = false)
    private CronJitterProperties jitterProps;

    /** 把 schedule 同步进 Quartz；如果 job 已存在则 reschedule 触发器 */
    public void registerSchedule(ScheduleRecord s) {
        String id = s.getId();
        JobKey jobKey = jobKey(id);
        JobDataMap data = new JobDataMap();
        data.put("scheduleId", s.getId());
        data.put("scheduleName", s.getName());
        data.put("scheduleKind", s.getKind());

        List<Trigger> triggers;
        try {
            triggers = buildTrigger(s);
        } catch (ValidationException ve) {
            throw ve;
        } catch (Exception e) {
            throw new ValidationException("Failed to build trigger for schedule " + id
                + " (kind=" + s.getKind() + "): " + e.getMessage());
        }

        try {
            if (scheduler.checkExists(jobKey)) {
                // reschedule：先删 job 下全部旧 trigger，再挂新集合（统一处理双约束 OR 的
                // 变体数变化：双约束 2 trigger ↔ 单约束 1 trigger；job 存在但 trigger 缺失的
                // 异常态 getTriggersOfJob 为空 → 直接重挂，与正常路径合并）
                for (Trigger old : scheduler.getTriggersOfJob(jobKey)) {
                    scheduler.unscheduleJob(old.getKey());
                }
                scheduler.scheduleJob(scheduler.getJobDetail(jobKey), new LinkedHashSet<>(triggers), true);
                log.info("[Quartz] Rescheduled job={} triggers={} kind={}", id, triggers.size(), s.getKind());
            } else {
                JobDetail jobDetail = JobBuilder.newJob(TestJob.class)
                    .withIdentity(jobKey)
                    .usingJobData(data)
                    .storeDurably()
                    .build();
                scheduler.scheduleJob(jobDetail, new LinkedHashSet<>(triggers), true);
                log.info("[Quartz] Scheduled job={} triggers={} kind={} firstStart={}",
                    id, triggers.size(), s.getKind(),
                    triggers.isEmpty() ? null : triggers.get(0).getStartTime());
            }
        } catch (SchedulerException e) {
            throw new RuntimeException("Quartz schedule failed for " + id + ": " + e.getMessage(), e);
        }
    }

    /** 立即触发已存在的 job（async） */
    public boolean triggerNow(String id) {
        try {
            JobKey jobKey = jobKey(id);
            if (!scheduler.checkExists(jobKey)) {
                log.warn("[Quartz] triggerNow: job {} not found", id);
                return false;
            }
            scheduler.triggerJob(jobKey);
            log.info("[Quartz] triggerNow fired job={}", id);
            return true;
        } catch (SchedulerException e) {
            log.warn("[Quartz] triggerNow failed for {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** 移除 job + 关联 trigger；job 不存在视为 ok */
    public boolean unregisterSchedule(String id) {
        try {
            boolean removed = scheduler.deleteJob(jobKey(id));
            log.info("[Quartz] delete job={} removed={}", id, removed);
            return removed;
        } catch (SchedulerException e) {
            log.warn("[Quartz] delete job={} failed: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * CRON-B3-2（决策 #8 / open-decisions.md R-1 补充）：判断 schedule 是否已在 Quartz 完整注册。
     *
     * <p>CC 对齐：cronScheduler.ts:179-227 load() 全量重建语义 —— 启动时以权威存储（DB）为准，
     * QRTZ 为持久调度器。job 存在但 trigger 为空（半僵尸态，见 {@link #registerSchedule} 幂等注释
     * :97-102 重挂路径）也判<b>未注册</b> → 由 {@code ScheduleService.reconcileQuartzAtStartup}
     * 补 registerSchedule 重挂，防「DB 有任务 QRTZ 不 fire」的僵尸（防僵尸核心判据）。
     *
     * @param id schedule id
     * @return true = job 与至少一个 trigger 均存在（已完整注册）；false = job 缺失或 trigger 空
     */
    public boolean hasRegistered(String id) {
        try {
            JobKey key = jobKey(id);
            boolean jobExists = scheduler.checkExists(key);
            // getTriggersOfJob 对不存在的 job 返回空列表（Quartz 语义），无需空集合回退
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(key);
            boolean registered = jobExists && !triggers.isEmpty();
            if (log.isDebugEnabled()) {
                log.debug("[Quartz] hasRegistered id={} jobExists={} triggers={} registered={}"
                        + "（对齐 CC load() 全量重建 cronScheduler.ts:179-227）",
                    id, jobExists, triggers.size(), registered);
            }
            return registered;
        } catch (SchedulerException e) {
            log.warn("[Quartz] hasRegistered 查询失败 id={}: {}，按未注册处理（reconcile 幂等重挂）",
                id, e.getMessage());
            return false;
        }
    }

    /**
     * CRON-B3-2（决策 #8）：枚举 Quartz 中全部 schedule- 前缀 job 的 id 列表（孤儿检测）。
     *
     * <p>CC 对齐：cronScheduler.ts:179-227 load() 全量重建 —— 对账需知道 QRTZ 侧全量 job 集合，
     * 与 DB id 集比对出孤儿（QRTZ 有 job 但 DB 无记录，如 delete 崩溃中间态）。只剥
     * {@link #SCHEDULE_JOB_PREFIX} 前缀返回 scheduleId，避免把 Spring @Scheduled 等其它 job
     * 误判进对账范围。
     *
     * @return scheduleId 列表（无匹配 → 空列表）
     */
    public List<String> listRegisteredJobIds() {
        try {
            List<String> ids = new ArrayList<>();
            for (JobKey key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
                String name = key.getName();
                if (name.startsWith(SCHEDULE_JOB_PREFIX)) {
                    ids.add(name.substring(SCHEDULE_JOB_PREFIX.length()));
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[Quartz] listRegisteredJobIds 枚举 {} 个 schedule job"
                        + "（对齐 CC load() 全量重建 cronScheduler.ts:179-227）", ids.size());
            }
            return ids;
        } catch (SchedulerException e) {
            log.warn("[Quartz] listRegisteredJobIds 枚举失败: {}，按空列表处理", e.getMessage());
            return List.of();
        }
    }

    // ============== trigger builders ==============

    private List<Trigger> buildTrigger(ScheduleRecord s) throws ParseException {
        String kind = s.getKind();
        if (kind == null) {
            throw new ValidationException("Schedule 'kind' is required (cron|once|interval)");
        }
        JobKey jobKey = jobKey(s.getId());
        TriggerKey triggerKey = TriggerKey.triggerKey(triggerKeyName(s.getId()));

        switch (kind) {
            case "cron" -> {
                String expr = s.getCron();
                if (expr == null || expr.isBlank()) {
                    throw new ValidationException("kind=cron requires 'cron' field");
                }
                // 双约束 OR（OPD-Cron-T1-05）：CronCreateTool 用 VARIANT_SEPARATOR "||"
                // join 多变体存进单 cron 字段，此处拆回逐变体建 CronTrigger（任一匹配即
                // 触发，对齐 CC cron.ts:151-158 dom/dow OR 语义）；单变体/6 段无分隔符 → 单元素
                List<String> variants = CronExpressionConverter.splitVariants(expr);
                if (variants == null || variants.isEmpty()) {
                    throw new ValidationException("Invalid cron expression: '" + expr + "'");
                }
                // CRON-B2-1 recurring 首触发抖动（决策 #1）· 对齐 CC jitteredNextCronRunMs
                // (cronTasks.ts:381-398) + first-sight 锚点 lastFiredAt ?? createdAt
                // (cronScheduler.ts:264-276)。Java 生产 create 路径 createdAt 恰为注册时刻
                // （ScheduleService.create:137 先行落库），故 anchor 优先 createdAt（可精确测试）、
                // 缺省（单测直构 record）回退 System.currentTimeMillis()。
                // taskId = schedule id 后 8 hex（对齐 one-shot 路径 CronCreateTool.java:367；
                // jitterFrac cronTasks.ts:362-365 只读前 8 hex，非 hex → 0 = 无抖动）。
                String taskId = s.getId().length() > 8
                    ? s.getId().substring(s.getId().length() - 8)
                    : s.getId();
                long anchor = s.getCreatedAt() != null ? s.getCreatedAt() : System.currentTimeMillis();
                CronJitter.CronJitterConfig jitterCfg = jitterProps != null
                    ? jitterProps.toConfig()
                    : CronJitter.CronJitterConfig.DEFAULT;
                Long t1 = CronExpressionConverter.nextCronRunMs(expr, anchor);
                Long jittered = CronJitter.jitteredNextCronRunMs(expr, anchor, taskId, jitterCfg);
                // 决策 #1 仅首触发抖动（Quartz 无法存周期偏移，每周期抖动为残留未对齐，
                // 登记 CRON-B2-1-progress concerns）。jittered>t1（jitter 前向，cronTasks.ts:393-397）
                // → kickstart SimpleTrigger 首触发 + CronTrigger 正常周期双 trigger；
                // jittered==null/==t1（frac=0 无抖动 / t2 无匹配 cronTasks.ts:392 直发 t1）→ 纯
                // CronTrigger 回退（对齐 CC 非 hex→0 与 pinned-date 直发 t1）。
                boolean kickstart = jittered != null && t1 != null && jittered > t1;
                // 防双发/跳发：CronTrigger 首 fire 必须推到 t2（首 fire 落在 jittered 之后）。
                // Quartz computeFirstFireTime 用 getFireTimeAfter(startAt-1000ms)（javap
                // quartz-2.5.0 CronTriggerImpl 自验），startAt 钳到 max(jittered, t1+1000L) 保证
                // startAt-1000>=t1 → 首 fire=t2，亚秒级 jitter 也不会落回 t1 与 kickstart 双发。
                long cronStartAt = kickstart ? Math.max(jittered, t1 + 1000L) : anchor;
                if (log.isDebugEnabled()) {
                    log.debug("[Quartz] buildTrigger kind=cron scheduleId={} taskId={} anchor={} t1={} "
                            + "jittered={} kickstart={} cronStartAt={}（决策 #1 首触发抖动）",
                        s.getId(), taskId, anchor, t1, jittered, kickstart, cronStartAt);
                }
                List<Trigger> triggers = new ArrayList<>(variants.size() + (kickstart ? 1 : 0));
                for (int i = 0; i < variants.size(); i++) {
                    String v = variants.get(i);
                    if (!CronExpression.isValidExpression(v)) {
                        throw new ValidationException("Invalid cron expression: '" + v + "'");
                    }
                    // 单变体沿用既有 trigger key（schedule-<id>-trigger）保幂等；
                    // 多变体加序号后缀（schedule-<id>-trigger-<idx>）
                    TriggerKey key = variants.size() == 1
                        ? triggerKey
                        : TriggerKey.triggerKey(triggerKeyName(s.getId()) + "-" + i);
                    CronTrigger trigger;
                    if (kickstart) {
                        // startAt=cronStartAt：把 CronTrigger 首 fire 从 t1 推到 t2，避免与
                        // kickstart（t1+jitter）双发；正常周期仍由本 CronTrigger 承担
                        trigger = TriggerBuilder.newTrigger()
                            .withIdentity(key)
                            .forJob(jobKey)
                            .startAt(new Date(cronStartAt))
                            // DoNothing = 停机/阻塞跨过 fire 点（>misfire 阈值 60s 缺省，
                            // application.yml 未覆盖）的 recurring fire 直接丢弃、下次按原 cron
                            // 排程；CC 对等场景 check() 首见 tick 补跑一次（cronScheduler.ts:264-270
                            // + :283 now>=next）再从 now 重排（:315-324）；差异=少执行，已拍板接受
                            // 并文档化（NEW-16/△-7），不违反 OPD-Cron-09-2（该决策管 one-shot 启动
                            // 表面，CC recurring 亦不表面 cronScheduler.ts:189-191/:196）
                            .withSchedule(CronScheduleBuilder.cronSchedule(v)
                                .withMisfireHandlingInstructionDoNothing())
                            .build();
                    } else {
                        trigger = TriggerBuilder.newTrigger()
                            .withIdentity(key)
                            .forJob(jobKey)
                            .withSchedule(CronScheduleBuilder.cronSchedule(v)
                                .withMisfireHandlingInstructionDoNothing())
                            .build();
                    }
                    triggers.add(trigger);
                    if (log.isDebugEnabled()) {
                        log.debug("[Quartz] buildTrigger kind=cron misfire=DO_NOTHING scheduleId={} "
                                + "trigger={} cron={} startAt={}", s.getId(), key, v,
                            kickstart ? cronStartAt : null);
                    }
                }
                if (kickstart) {
                    // 首触发 kickstart：startAt=jittered（t1 后的确定性偏移点，cronTasks.ts:381-398
                    // 摊开 :00 打爆点），repeatCount=0 只 fire 一次，之后周期全由 CronTrigger 承担。
                    // misfire=RESCHEDULE_NEXT_WITH_REMAINING_COUNT + repeatCount=0 无剩余 → 重启
                    // 错过 kickstart 首触发即丢弃；CC 首见会补跑一次（cronScheduler.ts:264-283）；
                    // 差异=少执行，NEW-16 接受文档化；不违反 OPD-Cron-09-2（少执行不产生未经确认
                    // 的自动执行）。
                    SimpleTrigger kick = TriggerBuilder.newTrigger()
                        .withIdentity(TriggerKey.triggerKey(triggerKeyName(s.getId()) + "-kickstart"))
                        .forJob(jobKey)
                        .startAt(new Date(jittered))
                        .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withRepeatCount(0)
                            .withMisfireHandlingInstructionNextWithRemainingCount())
                        .build();
                    triggers.add(kick);
                    if (log.isDebugEnabled()) {
                        log.debug("[Quartz] buildTrigger kind=cron 追加 kickstart 首触发 scheduleId={} "
                                + "startAt={}（jitteredNextCronRunMs cronTasks.ts:381-398）",
                            s.getId(), jittered);
                    }
                }
                return triggers;
            }
            case "once" -> {
                String runAt = s.getRunAt();
                if (runAt == null || runAt.isBlank()) {
                    throw new ValidationException("kind=once requires 'runAt' field (ISO 8601)");
                }
                Date start = parseDate(runAt);
                SimpleTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .startAt(start)
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withRepeatCount(0)            // 跑一次
                        // NextWithRemainingCount：跳过 missed 那次（repeatCount=0 无剩余
                        // → 不再 fire），重启不自动补跑 one-shot（对齐 CC 先问后执行
                        // cronScheduler.ts:195-216 + L547-549 "Do NOT execute yet...AskUserQuestion"）
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                    .build();
                if (log.isDebugEnabled()) {
                    log.debug("[Quartz] buildTrigger kind=once misfire=RESCHEDULE_NEXT_WITH_REMAINING_COUNT scheduleId={} runAt={}", s.getId(), runAt);
                }
                return List.of(trigger);
            }
            case "interval" -> {
                Integer secs = s.getIntervalSeconds();
                if (secs == null || secs <= 0) {
                    throw new ValidationException("kind=interval requires positive 'intervalSeconds'");
                }
                SimpleTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .startAt(new Date(System.currentTimeMillis() + secs * 1000L))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(secs)
                        .withRepeatCount(-1)           // 无限重复
                        // NextWithRemainingCount：跳过 missed 保持固定周期，重启不 catch-up
                        // （对齐 CC recurring reschedule-from-now cronScheduler.ts:315-324）
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                    .build();
                if (log.isDebugEnabled()) {
                    log.debug("[Quartz] buildTrigger kind=interval misfire=RESCHEDULE_NEXT_WITH_REMAINING_COUNT scheduleId={} intervalSeconds={}", s.getId(), secs);
                }
                return List.of(trigger);
            }
            default -> throw new ValidationException("Unknown schedule kind: " + kind);
        }
    }

    /** JobKey 前缀 · 命名规则 "schedule-" + scheduleId（jobKey() 与 listRegisteredJobIds 共用单一真源）。 */
    private static final String SCHEDULE_JOB_PREFIX = "schedule-";

    private static JobKey jobKey(String id) {
        return JobKey.jobKey(SCHEDULE_JOB_PREFIX + id);
    }

    private static String triggerKeyName(String id) {
        return "schedule-" + id + "-trigger";
    }

    private static Date parseDate(String iso) {
        // 接受 ISO 8601 偏移量 (OffsetDateTime) 或无时区 (LocalDateTime → 视作系统默认时区)
        try {
            return Date.from(OffsetDateTime.parse(iso).toInstant());
        } catch (Exception ignore) {
            // 继续尝试无时区
        }
        try {
            return Date.from(LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            throw new ValidationException("Invalid 'runAt' (need ISO 8601 like 2025-06-14T15:30:00Z): " + iso);
        }
    }
}
