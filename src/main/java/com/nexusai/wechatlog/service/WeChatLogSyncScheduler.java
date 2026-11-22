package com.nexusai.wechatlog.service;

import jakarta.annotation.PostConstruct;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 把 {@link WeChatLogSyncJob} 注册到 Quartz。
 *
 * <h2>为什么单独一个注册器</h2>
 * 项目里的 {@code QuartzScheduleService} 负责「用户自定义的 schedule 记录」同步，
 * 那是业务数据驱动的；本任务是产品内置的一条固定周期任务，不写业务表。
 * 分开注册可以避免把内置任务混进用户可见的 schedule 列表。
 *
 * <h2>⚠️ 默认不开启</h2>
 * {@code nexusai.wechatlog.sync.enabled} 默认 false。原因：这条链路会读取用户私聊记录，
 * 必须在用户明确开启后才跑。开启后才注册触发器。
 */
@Component
public class WeChatLogSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeChatLogSyncScheduler.class);

    private static final String JOB_NAME = "wechat-log-sync";
    private static final String GROUP = "nexusai-builtin";

    @Autowired
    @Lazy
    private Scheduler scheduler;

    @Value("${nexusai.wechatlog.sync.enabled:false}")
    private boolean enabled;

    /** 同步周期（cron）。默认每小时第 7 分钟（避开整点高峰）。 */
    @Value("${nexusai.wechatlog.sync.cron:0 7 * * * ?}")
    private String cron;

    @PostConstruct
    public void register() {
        if (!enabled) {
            log.info("微信聊天记录定时同步未启用（nexusai.wechatlog.sync.enabled=false）");
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(JOB_NAME, GROUP);
            TriggerKey triggerKey = TriggerKey.triggerKey(JOB_NAME, GROUP);

            JobDetail job = JobBuilder.newJob(WeChatLogSyncJob.class)
                    .withIdentity(jobKey)
                    .withDescription("把本机微信聊天记录同步进 NexusAI 索引")
                    .storeDurably()
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                            .withMisfireHandlingInstructionDoNothing())
                    .build();

            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }
            scheduler.scheduleJob(job, trigger);
            log.info("微信聊天记录定时同步已注册: cron={}", cron);
        } catch (SchedulerException e) {
            log.warn("注册微信聊天记录定时同步失败（功能不可用，但不影响启动）", e);
        }
    }

    /**
     * 运行时启停（供设置页开关调用）。
     *
     * @param on true=注册，false=移除
     */
    public void setEnabled(boolean on) {
        try {
            JobKey jobKey = JobKey.jobKey(JOB_NAME, GROUP);
            if (on) {
                if (!scheduler.checkExists(jobKey)) {
                    register();
                }
            } else if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("微信聊天记录定时同步已停止");
            }
        } catch (SchedulerException e) {
            log.warn("切换微信同步开关失败", e);
        }
    }
}
