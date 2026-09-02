package com.nexusai.application.schedule;

import com.nexusai.application.agent.tool.cron.CronExpressionConverter;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-F3 · recurring 双约束 OR 多 trigger 注册（OPD-Cron-T1-05，对齐 CC cron.ts:151-158）。
 *
 * <p>WHY：CC 对 dom/dow 双约束 cron 是「任一匹配即触发」（OR 语义），而 Quartz 单 CronTrigger
 * 对 dom 与 dow 同时具体化时按 AND 解释——故 Java 侧把双约束拆成 2 个 6 段变体
 * （dom-only + dow-only，{@link CronExpressionConverter#VARIANT_SEPARATOR} "||" join 存进单
 * cron 字段），注册时拆回逐变体建 CronTrigger 挂到同一 JobDetail，并集 = CC OR。本测试守护
 * 该接线：双约束 → 2 trigger（各自有效），单约束 → 1 trigger，重注册变体数变化时旧 trigger 全清。
 */
class QuartzScheduleServiceCronOrTest {

    private Scheduler scheduler;
    private QuartzScheduleService service;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "CronOrTestScheduler");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        props.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();

        service = new QuartzScheduleService();
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown();
    }

    private static ScheduleRecord cron(String id, String cron) {
        ScheduleRecord rec = new ScheduleRecord();
        rec.setId(id);
        rec.setName(id + "-test");
        rec.setKind("cron");
        rec.setCron(cron);
        return rec;
    }

    private List<? extends Trigger> triggersOf(String id) throws Exception {
        return scheduler.getTriggersOfJob(JobKey.jobKey("schedule-" + id));
    }

    @Test
    @DisplayName("双约束 '0 9 1 * 1' recurring → 2 个 CronTrigger（dom 侧 + dow 侧，并集 = CC OR）")
    void doubleConstraint_registersTwoTriggers() throws Exception {
        // 生产 join 串：CronCreateTool 对 "0 9 1 * 1" 生成 cronForSchedule
        String joinCron = CronExpressionConverter.joinVariants(
            CronExpressionConverter.toQuartzCronVariants("0 9 1 * 1"));
        assertThat(joinCron).isEqualTo("0 0 9 1 * ?||0 0 9 ? * 2");

        service.registerSchedule(cron("sch-or", joinCron));

        List<? extends Trigger> triggers = triggersOf("sch-or");
        assertThat(triggers)
            .as("双约束必须注册 2 个 trigger（dom-only + dow-only），任一匹配即触发")
            .hasSize(2);
        for (Trigger t : triggers) {
            assertThat(t).isInstanceOf(CronTrigger.class);
            assertThat(((CronTrigger) t).getMisfireInstruction())
                .as("多 trigger 沿用 DO_NOTHING：阻塞跨过 fire 点不补跑")
                .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        }
        // 变体各自有效且 key 带序号后缀（区分两 trigger）
        assertThat(CronExpression.isValidExpression(((CronTrigger) triggers.get(0)).getCronExpression())).isTrue();
        assertThat(CronExpression.isValidExpression(((CronTrigger) triggers.get(1)).getCronExpression())).isTrue();
        assertThat(triggers.get(0).getKey().getName()).isEqualTo("schedule-sch-or-trigger-0");
        assertThat(triggers.get(1).getKey().getName()).isEqualTo("schedule-sch-or-trigger-1");
    }

    @Test
    @DisplayName("单约束（dom-only / dow-only / 双通配 / 6 段透传）→ 仍 1 个 trigger（沿用既有 key）")
    void singleConstraint_registersOneTrigger() throws Exception {
        service.registerSchedule(cron("sch-dom", "0 0 9 1 * ?"));
        service.registerSchedule(cron("sch-dow", "0 0 9 ? * 2"));
        service.registerSchedule(cron("sch-any", "0 0 9 ? * *"));
        service.registerSchedule(cron("sch-six", "0 30 9 * * ?"));

        assertThat(triggersOf("sch-dom")).hasSize(1);
        assertThat(triggersOf("sch-dom").get(0).getKey().getName())
            .as("单变体沿用既有 trigger key（schedule-<id>-trigger）")
            .isEqualTo("schedule-sch-dom-trigger");
        assertThat(triggersOf("sch-dow")).hasSize(1);
        assertThat(triggersOf("sch-any")).hasSize(1);
        assertThat(triggersOf("sch-six")).hasSize(1);
    }

    @Test
    @DisplayName("重注册变体数变化（2→1）→ 旧 trigger 全清，仅剩新集合（幂等）")
    void reschedule_variantCountShrink() throws Exception {
        service.registerSchedule(cron("sch-r", "0 0 9 1 * ?||0 0 9 ? * 2"));
        assertThat(triggersOf("sch-r")).hasSize(2);

        // 单约束重注册：2→1，旧的 -0/-1 两个 trigger 必须被删干净
        service.registerSchedule(cron("sch-r", "0 0 9 1 * ?"));

        List<? extends Trigger> triggers = triggersOf("sch-r");
        assertThat(triggers).as("重注册后仅剩 1 个 trigger，旧序号 suffix 全部清除").hasSize(1);
        assertThat(triggers.get(0).getKey().getName()).isEqualTo("schedule-sch-r-trigger");
    }

    @Test
    @DisplayName("once / interval 回归：仍单 trigger，不受多 trigger 改造影响")
    void onceAndInterval_stillSingleTrigger() throws Exception {
        ScheduleRecord once = new ScheduleRecord();
        once.setId("sch-once");
        once.setKind("once");
        once.setRunAt("2099-01-01T00:00:00Z");
        service.registerSchedule(once);
        assertThat(triggersOf("sch-once")).hasSize(1);
        assertThat(triggersOf("sch-once").get(0)).isInstanceOf(SimpleTrigger.class);

        ScheduleRecord interval = new ScheduleRecord();
        interval.setId("sch-int");
        interval.setKind("interval");
        interval.setIntervalSeconds(60);
        service.registerSchedule(interval);
        assertThat(triggersOf("sch-int")).hasSize(1);
        assertThat(triggersOf("sch-int").get(0)).isInstanceOf(SimpleTrigger.class);
    }
}
