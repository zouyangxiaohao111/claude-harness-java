package com.nexusai.infra.schedule;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把「消息扫描」注册进 Quartz，并清理旧扫描规则留下的 job。
 *
 * <h2>为什么不用 Flyway 写注册（照 {@link QuartzJobClassMigration} 的既有做法）</h2>
 * QRTZ 表由 Quartz 自己建（{@code spring.quartz.jdbc.initialize-schema: always}），
 * 首次启动时 Flyway 可能先于它执行 ⇒ 迁移里 INSERT/UPDATE 一张还不存在的表会直接失败。
 * 放在 {@link ApplicationRunner} 里则保证「应用起来了 ⇒ 表一定存在（或确实没有）」。
 * <p>⚠️ 而且这条任务<b>没有业务表</b>：它是系统内置的固定周期任务，
 * 「扫哪些渠道」的配置在 {@code im_scan_channel} 表里，不在 schedules 表里
 * ⇒ 不混进用户可见的定时任务列表（与 {@code WeChatLogSyncScheduler} 同一思路）。
 *
 * <h2>为什么要清旧 job</h2>
 * 旧的「扫描规则」把 job 注册在 {@code im-scan} 组里，{@code JOB_CLASS_NAME} 是
 * {@code com.nexusai.domain.im_scan.ImScanJob} —— 那个类已经删了。
 * Quartz 用 JDBC JobStore，job 跨重启存活 ⇒ 库里还留着指向「不存在的类」的 job 行，
 * 每次 fire 都会反射失败、刷一屏 Quartz 错误日志。
 * ⇒ 启动时把 {@code im-scan} 组整个删掉（那个机制已整体退役，留着只会报错）。
 */
@Component
public class ImMessageSyncScheduler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImMessageSyncScheduler.class);

    /** 本任务的 job 名（固定 ⇒ 全局只有一个）。 */
    public static final String JOB_NAME = "im-message-sync";
    /** 内置任务分组（与 {@code WeChatLogSyncScheduler} 同组，便于一眼认出是内置的）。 */
    public static final String GROUP = "nexusai-builtin";
    /** 旧「扫描规则」机制的 job 组 —— 其 Job 类已删除，启动时清空。 */
    public static final String LEGACY_GROUP = "im-scan";

    /** 默认周期：每 5 分钟（cron 6 段：秒 分 时 日 月 周）。 */
    public static final String DEFAULT_CRON = "0 0/5 * * * ?";

    @Autowired(required = false)
    private Scheduler scheduler;

    /** 扫描周期（可配；默认每 5 分钟）。 */
    @Value("${nexusai.im.message-sync.cron:" + DEFAULT_CRON + "}")
    private String cron;

    @Override
    public void run(ApplicationArguments args) {
        if (scheduler == null) {
            log.info("[ImMessageSync] 未装配 Quartz Scheduler，消息扫描任务未注册");
            return;
        }
        cleanupLegacyJobs();
        register();
    }

    /**
     * 清掉旧扫描规则留下的 job（其 Job 类已删除 ⇒ 留着只会每次 fire 反射失败）。
     *
     * <p>⛔ 只碰 {@code im-scan} 组，不动别的组（用户自己的定时任务在 {@code schedules} 组里）。
     */
    private void cleanupLegacyJobs() {
        try {
            java.util.Set<JobKey> keys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(LEGACY_GROUP));
            if (keys == null || keys.isEmpty()) {
                return;
            }
            int removed = 0;
            for (JobKey key : keys) {
                if (scheduler.deleteJob(key)) {
                    removed++;
                }
            }
            log.info("[ImMessageSync] 已清理旧扫描规则的 Quartz job {} 个（组 {}）",
                removed, LEGACY_GROUP);
        } catch (SchedulerException e) {
            log.warn("[ImMessageSync] 清理旧扫描规则 job 失败（不影响新任务注册）: {}", e.toString());
        }
    }

    /**
     * 注册「消息扫描」job（幂等：已存在则先删旧的再挂新的，保证 cron 改动能生效）。
     */
    private void register() {
        String cronExpr = (cron == null || cron.isBlank()) ? DEFAULT_CRON : cron.trim();
        try {
            JobKey jobKey = JobKey.jobKey(JOB_NAME, GROUP);
            TriggerKey triggerKey = TriggerKey.triggerKey(JOB_NAME, GROUP);

            JobDetail job = JobBuilder.newJob(ImMessageSyncJob.class)
                .withIdentity(jobKey)
                .withDescription("扫描已启用渠道的新消息并落库（不唤醒 agent）")
                .storeDurably()
                .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpr)
                    // 停机期间的漏跑不补跑：补跑会把停机期间攒的消息一次性灌进来
                    .withMisfireHandlingInstructionDoNothing())
                .build();

            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }
            scheduler.scheduleJob(job, trigger);
            log.info("[ImMessageSync] 消息扫描任务已注册: job={}/{} cron={}", GROUP, JOB_NAME, cronExpr);
        } catch (Exception e) {
            // 注册失败不能让应用起不来（功能不可用，但用户还能用别的）
            log.error("[ImMessageSync] 消息扫描任务注册失败（功能不可用）: {}", e.toString(), e);
        }
    }
}
