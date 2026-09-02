package com.nexusai.application.schedule;

import com.nexusai.repository.schedule.entity.ScheduleRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-C2 misfire 策略断言（对齐 CC 先问后执行 + reschedule-from-now；cron 分支 DO_NOTHING
 * 为已拍板差异 NEW-16「recurring missed fire 不补跑」，详见 QuartzScheduleService buildTrigger
 * cron 分支注释）。
 *
 * <p>WHY：CC 对 missed one-shot 是"先问后执行，不自动 fire"（cronScheduler.ts L195-216 missed
 * 仅 one-shot + L203 nextFireAt=Infinity + L547-549 "Do NOT execute yet...AskUserQuestion"），
 * 对 recurring 是"从 now 重算防 catch-up"（cronScheduler.ts L315-324）。Java 端用 Quartz
 * misfire 指令表达：
 * <ul>
 *   <li>cron → CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING (2)：停机/阻塞跨过 fire 点
 *       （&gt;misfire 阈值 60s 缺省）的 recurring fire 丢弃不补跑（NEW-16 已拍板：CC check()
 *       首见 tick 补跑一次 cronScheduler.ts:264-283，Java 不补）。</li>
 *   <li>once → SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT (4)：
 *       跳过 missed 那次，repeatCount=0 无剩余 → 不再 fire（等价"不自动补跑 one-shot"）。</li>
 *   <li>interval → 同上 (4)：跳过 missed 保持固定周期，不 catch-up（对齐 CC recurring
 *       reschedule-from-now）。</li>
 * </ul>
 * SimpleScheduleBuilder 无 withMisfireHandlingInstructionDoNothing（javap quartz-2.5.0
 * 自验），NextWithRemainingCount 是其"跳过 missed 不立即 fire"的等价表达。
 *
 * <p>misfire 为声明式配置，运行时 misfire 行为难以在短时单测触发，故断言
 * getMisfireInstruction() 配置值；若未来有人改回 FireNow(1)/默认 SmartPolicy(0)，
 * 本测试即失败，守护"重启不自动补跑 missed"这一不变量。
 */
class QuartzScheduleServiceMisfireTest {

    private Scheduler scheduler;
    private QuartzScheduleService service;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "MisfireTestScheduler");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        props.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();

        service = new QuartzScheduleService();
        // 字段注入为 @Autowired，单测直接 ReflectionTestUtils 注入真实 Scheduler
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown();
    }

    @Test
    @DisplayName("cron 分支 misfire=DO_NOTHING：停机/阻塞跨过 fire 点的 recurring fire 丢弃不补跑（NEW-16 已拍板，vs CC 首见补跑一次 cronScheduler.ts:264-283）")
    void cronTriggerUsesDoNothingMisfire() throws Exception {
        ScheduleRecord rec = new ScheduleRecord();
        rec.setId("sch-cron");
        rec.setName("cron-test");
        rec.setKind("cron");
        rec.setCron("0 0 9 * * ?");

        service.registerSchedule(rec);

        Trigger trigger = scheduler.getTrigger(TriggerKey.triggerKey("schedule-sch-cron-trigger"));
        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(trigger.getMisfireInstruction())
            .as("cron 缺失补跑必须关闭（SmartPolicy 0 会按 FireOnceNow 补跑）")
            .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
    }

    @Test
    @DisplayName("once 分支 misfire=RESCHEDULE_NEXT_WITH_REMAINING_COUNT：重启 missed 不自动 fire（对齐 CC 先问后执行）")
    void onceTriggerSkipsMissedFire() throws Exception {
        ScheduleRecord rec = new ScheduleRecord();
        rec.setId("sch-once");
        rec.setName("once-test");
        rec.setKind("once");
        rec.setRunAt("2099-01-01T00:00:00Z");

        service.registerSchedule(rec);

        Trigger trigger = scheduler.getTrigger(TriggerKey.triggerKey("schedule-sch-once-trigger"));
        assertThat(trigger).isInstanceOf(SimpleTrigger.class);
        assertThat(trigger.getMisfireInstruction())
            .as("one-shot 重启自动补跑（FireNow 1）与 CC ask-user 语义相反，必须关闭")
            .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);
    }

    @Test
    @DisplayName("interval 分支 misfire=RESCHEDULE_NEXT_WITH_REMAINING_COUNT：跳过 missed 保持固定周期不 catch-up")
    void intervalTriggerSkipsMissedFire() throws Exception {
        ScheduleRecord rec = new ScheduleRecord();
        rec.setId("sch-interval");
        rec.setName("interval-test");
        rec.setKind("interval");
        rec.setIntervalSeconds(60);

        service.registerSchedule(rec);

        Trigger trigger = scheduler.getTrigger(TriggerKey.triggerKey("schedule-sch-interval-trigger"));
        assertThat(trigger).isInstanceOf(SimpleTrigger.class);
        assertThat(trigger.getMisfireInstruction())
            .as("interval 重启 catch-up（FireNow 1）必须关闭，跳过 missed 保固定周期")
            .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);
    }
}
