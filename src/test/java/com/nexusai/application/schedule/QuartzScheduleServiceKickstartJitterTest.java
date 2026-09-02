package com.nexusai.application.schedule;

import com.nexusai.application.agent.tool.cron.CronExpressionConverter;
import com.nexusai.application.agent.tool.cron.CronJitter;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-B2-1 · recurring 首触发抖动 kickstart 接线（决策 #1，对齐 CC cronTasks.ts:381-398）。
 *
 * <p>WHY：CC 对 recurring 首见锚点用 jitteredNextCronRunMs（cronScheduler.ts:264-276）把首 fire
 * 从 cron 边界（{@code 0 * * * *} 的 :00 打爆点）摊到 [t1, t2) 内的确定性偏移；Java 侧 Quartz
 * CronTrigger 无法存"周期偏移"，故 buildTrigger 对 cron 分支追加 kickstart SimpleTrigger
 * （startAt=t1+jitter，repeatCount=0）承担首 fire，CronTrigger 正常周期承担后续。本测试守护：
 * <ul>
 *   <li>8-hex taskId 抖动前向 → 产生 kickstart + CronTrigger 双 trigger</li>
 *   <li>kickstart.startAt 精确 = jitteredNextCronRunMs(cron, createdAt, taskId, DEFAULT)</li>
 *   <li>kickstart 只 fire 一次（repeatCount=0，周期由 CronTrigger 承担）</li>
 *   <li>CronTrigger 首 fire = t2 &gt; kickstart.startAt（防双发/跳发，锁 Quartz at/after vs
 *       strictly-after 语义：computeFirstFireTime 用 getFireTimeAfter(startAt-1000ms)，
 *       startAt=max(jittered, t1+1000) 保证亚秒 jitter 也不落回 t1）</li>
 *   <li>frac=0（00000000）→ jittered==t1 → 纯 CronTrigger 回退（对齐 CC 非 hex→0 无抖动）</li>
 *   <li>多变体（双约束 OR）→ 单 kickstart 覆盖全 OR 首 fire + 每变体 CronTrigger</li>
 *   <li>kickstart 与 CronTrigger 同一 JobKey、TriggerKey 互异（Quartz 拒绝同名 trigger）</li>
 *   <li>同 cron 两个 8-hex 任务 → 各自 kickstart.startAt 均前向且互异（thundering herd 缓解）</li>
 * </ul>
 */
class QuartzScheduleServiceKickstartJitterTest {

    /** 固定 createdAt 锚点（2027-01-15 16:00 CST）· buildTrigger anchor 优先 createdAt 可精确断言。 */
    private static final long ANCHOR = 1_800_000_000_000L;
    /** 后 8 位 hex → frac=0x3a7f9d12/2^32≈0.229 → 抖动前向。 */
    private static final String HEX_ID = "sch-3a7f9d12";
    /** 后 8 位全 0 → frac=0 → jittered==t1 → 无 kickstart。 */
    private static final String ZERO_ID = "sch-00000000";
    /** 6 段 Quartz，每日 9 点。 */
    private static final String CRON = "0 0 9 * * ?";
    /** HEX_ID 的后 8 hex（= buildTrigger 取 id 后 8 hex 的 taskId）。 */
    private static final String TASK_ID = "3a7f9d12";

    private Scheduler scheduler;
    private QuartzScheduleService service;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "KickstartJitterTestScheduler");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        props.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();

        service = new QuartzScheduleService();
        // jitterProps 不注入（@Autowired required=false → null → DEFAULT），scheduler 字段注入真实实例
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
        rec.setCreatedAt(ANCHOR);
        return rec;
    }

    private List<? extends Trigger> triggersOf(String id) throws Exception {
        return scheduler.getTriggersOfJob(JobKey.jobKey("schedule-" + id));
    }

    private static SimpleTrigger kickstartOf(List<? extends Trigger> triggers) {
        for (Trigger t : triggers) {
            if (t.getKey().getName().endsWith("-kickstart")) {
                return (SimpleTrigger) t;
            }
        }
        return null;
    }

    private static CronTrigger cronTriggerOf(List<? extends Trigger> triggers) {
        for (Trigger t : triggers) {
            if (t instanceof CronTrigger) {
                return (CronTrigger) t;
            }
        }
        return null;
    }

    @Test
    @DisplayName("8-hex id recurring cron → kickstart SimpleTrigger + CronTrigger 双 trigger，kickstart=t1+jitter 且 CronTrigger 首 fire=t2>kickstart")
    void singleVariant_recurring_getsKickstartPlusCronTrigger() throws Exception {
        // 期望值：anchor=createdAt，jitterCfg=DEFAULT（service 未注入 jitterProps → DEFAULT）
        Long t1 = CronExpressionConverter.nextCronRunMs(CRON, ANCHOR);
        Long t2 = CronExpressionConverter.nextCronRunMs(CRON, t1);
        Long expected = CronJitter.jitteredNextCronRunMs(CRON, ANCHOR, TASK_ID);
        // 前置：8-hex 抖动必须前向（cronTasks.ts:393-397 jitter>0），否则本用例前提不成立
        assertThat(t1).isNotNull();
        assertThat(t2).isNotNull();
        assertThat(expected)
            .as("8-hex frac≈0.229 的 jitter 必须严格 > 0（t1 之后），否则 kickstart 分支不进入")
            .isGreaterThan(t1);

        service.registerSchedule(cron(HEX_ID, CRON));

        List<? extends Trigger> triggers = triggersOf(HEX_ID);
        assertThat(triggers).as("8-hex recurring cron 必须注册 2 个 trigger（kickstart + CronTrigger）").hasSize(2);

        SimpleTrigger kickstart = kickstartOf(triggers);
        assertThat(kickstart).as("必须存在 key=schedule-<id>-trigger-kickstart 的首触发 SimpleTrigger").isNotNull();
        assertThat(kickstart.getStartTime().getTime())
            .as("kickstart 首触发 = jitteredNextCronRunMs(cron, createdAt, taskId, DEFAULT)（对齐 CC cronTasks.ts:381-398）")
            .isEqualTo(expected.longValue());
        assertThat(kickstart.getStartTime().getTime()).isGreaterThan(t1);
        assertThat(kickstart.getRepeatCount())
            .as("kickstart 只 fire 一次（首触发），后续周期由 CronTrigger 承担")
            .isZero();
        assertThat(kickstart.getMisfireInstruction())
            .as("kickstart missed 首触发不自动补跑（对齐 CC recurring reschedule-from-now）")
            .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);

        // 防双发/跳发：CronTrigger 首 fire 必须 > kickstart（startAt 钳到 max(jittered,t1+1000) 把
        // Quartz computeFirstFireTime 的 getFireTimeAfter(startAt-1000ms) 推到 t2）
        CronTrigger cronTrigger = cronTriggerOf(triggers);
        assertThat(cronTrigger).as("kickstart 之外必须保留正常周期 CronTrigger").isNotNull();
        assertThat(cronTrigger.getNextFireTime()).isNotNull();
        assertThat(cronTrigger.getNextFireTime().getTime())
            .as("CronTrigger 首 fire 必须 = t2（cron 第二匹配），且 > kickstart.startAt（双发=两者同 fire 点）")
            .isEqualTo(t2.longValue());
        assertThat(cronTrigger.getNextFireTime().getTime())
            .isGreaterThan(kickstart.getStartTime().getTime());
        assertThat(cronTrigger.getMisfireInstruction())
            .as("CronTrigger 沿用 DO_NOTHING：阻塞跨过 fire 点不补跑")
            .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
    }

    @Test
    @DisplayName("frac=0（id 后 8 位 00000000）→ jittered==t1 → 纯 CronTrigger 回退，无 kickstart")
    void zeroFracId_noKickstart_pureCronTrigger() throws Exception {
        Long t1 = CronExpressionConverter.nextCronRunMs(CRON, ANCHOR);
        Long jittered = CronJitter.jitteredNextCronRunMs(CRON, ANCHOR, "00000000");
        assertThat(jittered).isEqualTo(t1); // frac=0 → jitter=0 → t1

        service.registerSchedule(cron(ZERO_ID, CRON));

        List<? extends Trigger> triggers = triggersOf(ZERO_ID);
        assertThat(triggers)
            .as("frac=0 → 无抖动 → 纯 CronTrigger 回退（对齐 CC 非 hex→0 无抖动，cronTasks.ts:362-365）")
            .hasSize(1);
        assertThat(triggers.get(0)).isInstanceOf(CronTrigger.class);
        assertThat(kickstartOf(triggers)).isNull();
    }

    @Test
    @DisplayName("双约束 OR 多变体 → 2 CronTrigger + 1 kickstart = 3，kickstart 覆盖全 OR 首 fire")
    void doubleConstraint_kickstartPlusTwoCronTriggers() throws Exception {
        String joinCron = CronExpressionConverter.joinVariants(
            CronExpressionConverter.toQuartzCronVariants("0 9 1 * 1"));
        assertThat(joinCron).isEqualTo("0 0 9 1 * ?||0 0 9 ? * 2");
        Long expected = CronJitter.jitteredNextCronRunMs(joinCron, ANCHOR, TASK_ID);
        assertThat(expected).isNotNull();

        service.registerSchedule(cron(HEX_ID, joinCron));

        List<? extends Trigger> triggers = triggersOf(HEX_ID);
        assertThat(triggers).as("双约束 recurring：每变体 CronTrigger + 单 kickstart = 3").hasSize(3);
        long cronCount = triggers.stream().filter(t -> t instanceof CronTrigger).count();
        long kickCount = triggers.stream().filter(t -> t instanceof SimpleTrigger).count();
        assertThat(cronCount).isEqualTo(2);
        assertThat(kickCount).isEqualTo(1);
        SimpleTrigger kickstart = kickstartOf(triggers);
        assertThat(kickstart.getStartTime().getTime())
            .as("kickstart 用 joined 串（多变体 OR）算 jittered，单 kickstart 覆盖全 OR 首 fire")
            .isEqualTo(expected.longValue());
    }

    @Test
    @DisplayName("kickstart 与 CronTrigger 同一 JobKey、TriggerKey 互异 → 同一 JobDetail 双 trigger")
    void kickstartAndCronTrigger_sameJobKey_distinctTriggerKeys() throws Exception {
        service.registerSchedule(cron(HEX_ID, CRON));

        JobKey jobKey = JobKey.jobKey("schedule-" + HEX_ID);
        assertThat(scheduler.checkExists(jobKey))
            .as("registerSchedule 必须把 job 挂进 scheduler（checkExists 真）")
            .isTrue();
        List<? extends Trigger> triggers = triggersOf(HEX_ID);
        assertThat(triggers).hasSize(2);

        SimpleTrigger kickstart = kickstartOf(triggers);
        CronTrigger cronTrigger = cronTriggerOf(triggers);
        assertThat(kickstart).isNotNull();
        assertThat(cronTrigger).isNotNull();

        // 双 trigger 必须指向同一 JobDetail（同一 jobKey），否则 fire 会落进两个 job、触发两遍
        assertThat(kickstart.getJobKey()).as("kickstart 必须绑定 jobKey=schedule-<id>").isEqualTo(jobKey);
        assertThat(cronTrigger.getJobKey()).as("CronTrigger 必须绑定同一 jobKey").isEqualTo(jobKey);
        // TriggerKey 必须互异：Quartz 拒绝同 jobKey 下同名 trigger（ScheduleAlreadyExistsException）
        assertThat(kickstart.getKey()).isNotEqualTo(cronTrigger.getKey());
        assertThat(kickstart.getKey().getName())
            .as("kickstart trigger key = schedule-<id>-trigger-kickstart")
            .isEqualTo("schedule-" + HEX_ID + "-trigger-kickstart");
        assertThat(cronTrigger.getKey().getName())
            .as("CronTrigger 沿用 schedule-<id>-trigger（幂等）")
            .isEqualTo("schedule-" + HEX_ID + "-trigger");
    }

    @Test
    @DisplayName("同 cron 两个 8-hex 任务 → kickstart 均前向且互异（thundering herd 缓解真实）")
    void jitterForward_spreadsSameCronAcrossTasks() throws Exception {
        // WHY：thundering herd 的核心是"同 cron 多任务首触发必须错开"。用小时级 cron
        // （0.1*1h=6min<DEFAULT cap=15min，frac 差异真实可辨）；日级 cron 会 0.1*24h=2.4h>cap
        // → 所有 frac 都封顶到 t1+cap 同一点，无法证"错开"（cap 下界意图在 CronJitterTest 单测）。
        String hourly = "0 0 * * * ?";
        String idA = "sch-a1b2c3d4";
        String idB = "sch-5e6f7a8b";
        Long t1 = CronExpressionConverter.nextCronRunMs(hourly, ANCHOR);
        assertThat(t1).isNotNull();
        Long expectedA = CronJitter.jitteredNextCronRunMs(hourly, ANCHOR, "a1b2c3d4");
        Long expectedB = CronJitter.jitteredNextCronRunMs(hourly, ANCHOR, "5e6f7a8b");
        long cap = CronJitter.CronJitterConfig.DEFAULT.recurringCapMs();
        // 前向（cronTasks.ts:393-397 jitter>0 → t1+jitter>t1）+ cap 上界
        assertThat(expectedA).isGreaterThan(t1);
        assertThat(expectedB).isGreaterThan(t1);
        assertThat(expectedA).isLessThanOrEqualTo(t1 + cap);
        assertThat(expectedB).isLessThanOrEqualTo(t1 + cap);
        // 不同 taskId → 不同 frac → 不同 jitter → 首触发错开（cronTasks.ts:362-365 确定性散列）
        assertThat(expectedA).isNotEqualTo(expectedB);

        service.registerSchedule(cron(idA, hourly));
        service.registerSchedule(cron(idB, hourly));

        SimpleTrigger kickA = kickstartOf(triggersOf(idA));
        SimpleTrigger kickB = kickstartOf(triggersOf(idB));
        assertThat(kickA).as("idA 8-hex → 必须有 kickstart").isNotNull();
        assertThat(kickB).as("idB 8-hex → 必须有 kickstart").isNotNull();
        assertThat(kickA.getStartTime().getTime()).isEqualTo(expectedA.longValue());
        assertThat(kickB.getStartTime().getTime()).isEqualTo(expectedB.longValue());
    }
}
