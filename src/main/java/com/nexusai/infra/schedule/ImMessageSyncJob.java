package com.nexusai.infra.schedule;

import com.nexusai.domain.im_message.ImMessageSyncService;
import com.nexusai.domain.im_message.ImMessageSyncService.SyncSummary;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Quartz Job · 「消息扫描」（V315 的 {@code im_scan_channel} 那一套）。
 *
 * <h2>它是什么</h2>
 * 一条<b>系统内置</b>的固定周期任务：每 5 分钟把已启用渠道的新消息拉下来、幂等落进
 * {@code im_message}。用户不能增删这条任务，只能配置「<b>扫哪些渠道</b>」
 * （{@code im_scan_channel.enabled}）。
 *
 * <h2>🔴 它不唤醒 agent</h2>
 * 旧的「扫描规则」机制（{@code im_scan_rule}）是「定时筛本机微信记录 → 命中就入队唤醒 agent」，
 * 那套已删干净。本 Job 只做<b>数据同步</b>：
 * ⛔ 不调 {@code NotificationQueue.enqueue}、⛔ 不建会话、⛔ 不跑提示词、⛔ 不发消息。
 * 「要不要看这些消息」由 agent 任务自己用 {@code ReadImMessages} 读表判断。
 *
 * <h2>为什么用 {@link DisallowConcurrentExecution}</h2>
 * 同一个 job 不允许自己叠着自己跑：扫描是「拉外部平台 + 写库」，一轮没跑完时第二轮再起
 * 会让同一个会话被两个线程同时拉（虽然落库有幂等键兜底，但会白拉一遍、也会让
 * {@code last_scan_at} 的语义变模糊）。
 * <p>Quartz 的这个注解是<b>按 JobKey</b> 生效的（本任务只有一个固定 key）⇒ 正好覆盖。
 *
 * <p>与 {@link AgentJob} 同范式：Spring bean + Quartz 反射实例化，
 * 依赖用 {@code @Autowired(required = false)}（单测直 new 时为 null ⇒ 静默跳过）。
 */
@Component
@DisallowConcurrentExecution
public class ImMessageSyncJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ImMessageSyncJob.class);

    @Autowired(required = false)
    private ImMessageSyncService syncService;

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        if (syncService == null) {
            log.warn("[Quartz] ImMessageSyncJob 未装配 ImMessageSyncService，本轮跳过");
            return;
        }
        try {
            SyncSummary summary = syncService.runOnce();
            log.info("[Quartz] ImMessageSyncJob 完成: 实例 {} / 会话 {} / 新增 {} / 失败实例 {}",
                summary.instances(), summary.chats(), summary.newMessages(), summary.failedInstances());
        } catch (Exception e) {
            // ⛔ 不往外抛：失败原因已经写进 im_scan_channel.last_error（用户能在界面上看到），
            //    再抛给 Quartz 只会变成一行看不懂的 Quartz 堆栈。
            log.error("[Quartz] ImMessageSyncJob 执行失败", e);
        }
    }
}
