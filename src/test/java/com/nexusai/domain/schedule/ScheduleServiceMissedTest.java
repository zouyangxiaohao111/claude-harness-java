package com.nexusai.domain.schedule;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRON-B4 聚焦测试：missed 启动追回支持方法。
 *
 * <p><b>WHY (意图验证)</b>: CC 在启动时对"上次调度时错过"的 one-shot 任务表面给用户，
 * 先 AskUserQuestion 确认再执行（cronScheduler.ts:194-227）；missed 判定锚点是 createdAt
 * 的 next fire（cronTasks.ts:453-458）。若 Java 漏判（如 standard "0 0 9 * * ?" 全通配
 * 在 Quartz 抛异常被 catch→null）会把最常用 cron 永判非 missed（S-05 语义反转）；若不防
 * 重复，重启同批任务会反复骚扰用户。recurring 任务由 check() 正常补跑，启动时不得表面
 * （cronScheduler.ts:195-196）。fence 必须比 prompt 内最长反引号 run 长，否则 prompt 内
 * ``` 会提前闭合 fence 造成自注入（cronScheduler.ts:555-562）。
 *
 * <p>时间确定性：{@code @BeforeAll} 固定默认时区为 UTC（CC 用进程本地时区 cron.ts:9），
 * 期望值用同一 ZoneId.systemDefault() 推算。纯函数/删除用例 mock ScheduleMapper 与
 * QuartzScheduleService（同 ScheduleServiceAgedTest 约定，不引入 MybatisFlexBootstrap）。
 */
class ScheduleServiceMissedTest {

    private ScheduleMapper mapper;
    private QuartzScheduleService quartz;
    private ScheduleService service;

    @BeforeAll
    static void fixZoneToUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ScheduleMapper.class);
        quartz = mock(QuartzScheduleService.class);
        service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService", quartz);
    }

    private ScheduleRecord record(String id, String kind, String cron, Long createdAt) {
        ScheduleRecord r = new ScheduleRecord();
        r.setId(id);
        r.setKind(kind);
        r.setCron(cron);
        r.setCreatedAt(createdAt);
        r.setCommand("run tests");
        return r;
    }

    /** UTC 锚点 epoch ms（与 fixZoneToUtc 一致，测试确定性）。 */
    private static long utc(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli();
    }

    // ============ findMissedTasks 纯函数（E-10，recurring 计入） ============

    @Test
    @DisplayName("one-shot missed 命中：createdAt 过去的今晨 cron，next(createdAt) < now → missed")
    void oneShotMissedDetected() {
        long now = utc(2024, 1, 15, 12, 0);
        // createdAt 2024-01-13 12:00 UTC；next 9am after = 2024-01-14 09:00 < now → missed
        ScheduleRecord r = record("sch-1", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0));

        List<ScheduleRecord> missed = service.findMissedTasks(List.of(r), now);

        assertThat(missed)
            .as("标准 cron 双通配符必须可算 next（Quartz 会抛，分钟步进必须兜住），且 next<now 判 missed")
            .containsExactly(r);
    }

    @Test
    @DisplayName("recurring 也计入 raw findMissedTasks（CC cronTasks.ts:453 无 recurring 过滤）")
    void recurringCountedInRawFindMissed() {
        long now = utc(2024, 1, 15, 12, 0);
        // createdAt 2024-01-14 10:00 → next 2024-01-15 09:00 < now → missed
        ScheduleRecord r = record("sch-rec", "cron", "0 0 9 * * ?", utc(2024, 1, 14, 10, 0));

        List<ScheduleRecord> missed = service.findMissedTasks(List.of(r), now);

        assertThat(missed)
            .as("recurring 窗口在停机期间错过也算 missed（cronScheduler.ts 注释），但启动只表面 one-shot")
            .containsExactly(r);
    }

    @Test
    @DisplayName("非 missed 排除：createdAt 在今天 9 点之后 → next(createdAt) > now，不计 missed")
    void notMissedWhenNextInFuture() {
        long now = utc(2024, 1, 15, 12, 0);
        // createdAt 2024-01-15 10:00（今日 9 点后）→ next = 2024-01-16 09:00 > now → 非 missed
        ScheduleRecord r = record("sch-f", "once", "0 0 9 * * ?", utc(2024, 1, 15, 10, 0));

        List<ScheduleRecord> missed = service.findMissedTasks(List.of(r), now);

        assertThat(missed).as("未来才 fire 的任务不算 missed").isEmpty();
    }

    @Test
    @DisplayName("无效 cron → nextCronRunMs null → 不计 missed（对齐 cronTasks.ts:305 返回 null）")
    void invalidCronNeverMissed() {
        long now = utc(2024, 1, 15, 12, 0);
        ScheduleRecord badMinute = record("sch-bad1", "once", "99 9 * * *", utc(2024, 1, 13, 12, 0));
        ScheduleRecord badArity = record("sch-bad2", "once", "0 9 * *", utc(2024, 1, 13, 12, 0));
        // B5-4 补：6 段非法例（分钟 99 越界 → quartz6FieldsInRange 闸 null，B5-1 偏差#1 兜底闸门）
        ScheduleRecord badSix = record("sch-bad4", "once", "0 99 9 * * ?", utc(2024, 1, 13, 12, 0));
        ScheduleRecord noCron = record("sch-bad3", "once", null, utc(2024, 1, 13, 12, 0));

        assertThat(service.findMissedTasks(List.of(badMinute, badArity, badSix, noCron), now))
            .as("非法 cron/字段数错误/6 段越界/null cron 全部 next=null，永判非 missed")
            .isEmpty();
    }

    @Test
    @DisplayName("createdAt 为 null（B1 旧行无锚点）→ 不判 missed")
    void nullCreatedAtNeverMissed() {
        long now = utc(2024, 1, 15, 12, 0);
        ScheduleRecord r = record("sch-old", "once", "0 0 9 * * ?", null);

        assertThat(service.findMissedTasks(List.of(r), now))
            .as("旧行无 createdAt 锚点，不判 missed（对齐 isRecurringTaskAged B1 兼容）")
            .isEmpty();
    }

    // ============ findMissedForStartup（E-09，只表面 one-shot + missedAsked 防重复） ============

    @Test
    @DisplayName("启动只表面 one-shot：recurring missed 被过滤（cronScheduler.ts:196 !t.recurring）")
    void startupFiltersOutRecurring() {
        long now = utc(2024, 1, 15, 12, 0);
        ScheduleRecord rec = record("sch-rec", "cron", "0 0 9 * * ?", utc(2024, 1, 14, 10, 0));
        ScheduleRecord one = record("sch-one", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0));

        List<ScheduleRecord> surfaced = service.findMissedForStartup(List.of(rec, one), now);

        assertThat(surfaced)
            .as("recurring 由 check() 正常补跑，启动表面只含 one-shot")
            .containsExactly(one);
    }

    @Test
    @DisplayName("missedAsked 防重复：同一批记录二次调用不重报（cronScheduler.ts:196/200）")
    void missedAskedPreventsReSurfacing() {
        long now = utc(2024, 1, 15, 12, 0);
        ScheduleRecord one = record("sch-one", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0));
        List<ScheduleRecord> batch = List.of(one);

        List<ScheduleRecord> first = service.findMissedForStartup(batch, now);
        List<ScheduleRecord> second = service.findMissedForStartup(batch, now);

        assertThat(first).containsExactly(one);
        assertThat(second)
            .as("已表面过的 missed 任务不得重复骚扰用户（missedAsked.add 后过滤）")
            .isEmpty();
    }

    // ============ buildMissedTaskNotification（E-11，fence + AskUserQuestion 先问后执行） ============

    @Test
    @DisplayName("通知含 AskUserQuestion 先问后执行文案 + fence 包裹 prompt（cronScheduler.ts:545-549）")
    void notificationAsksBeforeExecute() {
        ScheduleRecord one = record("sch-one", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0));
        one.setCommand("run the build");

        String notif = service.buildMissedTaskNotification(List.of(one));

        assertThat(notif)
            .contains("The following one-shot scheduled task was missed while Claude was not running.")
            // CRON-B4-3 决策 #12 (OPD-EL-01)：CC cronScheduler.ts:544 写死
            // ".claude/scheduled_tasks.json" 介质被用户拍板覆写为中性 "the scheduled task store"
            .contains("already been removed from the scheduled task store.")
            .contains("Do NOT execute this prompt yet.")
            .contains("First use the AskUserQuestion tool to ask whether to run it now.")
            .contains("Only execute if the user confirms.")
            .contains("[0 0 9 * * ?, created 2024-01-13T12:00:00Z]")
            .contains("```\nrun the build\n```")
            .doesNotContain("these prompts");
    }

    @Test
    @DisplayName("prompt 内含 ``` 时 fence 长度递增，防自注入（cronScheduler.ts:555-562）")
    void fenceLongerThanBacktickRun() {
        ScheduleRecord one = record("sch-one", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0));
        one.setCommand("```\ncode\n```");   // 最长反引号 run = 3 → fence = 4 个反引号

        String notif = service.buildMissedTaskNotification(List.of(one));

        assertThat(notif)
            .as("fence 必须比 prompt 内最长反引号 run 长，否则 prompt 内 ``` 提前闭合 fence 造成自注入")
            .contains("````\n```\ncode\n```\n````");
    }

    @Test
    @DisplayName("多任务通知使用复数文案：s were + these prompts（cronScheduler.ts:543-549 plural）")
    void pluralWordingForMultiple() {
        ScheduleRecord a = record("sch-a", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0));
        ScheduleRecord b = record("sch-b", "once", "0 0 18 * * ?", utc(2024, 1, 13, 12, 0));

        String notif = service.buildMissedTaskNotification(List.of(a, b));

        assertThat(notif)
            .contains("The following one-shot scheduled tasks were missed while Claude was not running.")
            .contains("They have already been removed")
            .contains("Do NOT execute these prompts yet.")
            .contains("ask whether to run each one now.")
            .doesNotContain("task was missed");
    }

    // ============ removeMissedTasks（E-12，表面后删除 = unregister + deleteById + sessionJobs） ============

    @Test
    @DisplayName("removeMissedTasks 对每个 id 执行 unregister + deleteById + sessionJobs 同步")
    void removeMissedUnregistersAndDeletes() {
        ScheduleRecord a = record("sch-a", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0));
        ScheduleRecord b = record("sch-b", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0));
        when(mapper.selectOneById("sch-a")).thenReturn(a);
        when(mapper.selectOneById("sch-b")).thenReturn(b);
        @SuppressWarnings("unchecked")
        Map<String, CopyOnWriteArrayList<String>> jobs =
            (Map<String, CopyOnWriteArrayList<String>>) ReflectionTestUtils.getField(service, "sessionJobs");
        jobs.put("sess-9", new CopyOnWriteArrayList<>(new ArrayList<>(List.of("sch-a", "sch-other"))));

        int deleted = service.removeMissedTasks(List.of("sch-a", "sch-b"));

        assertThat(deleted).as("两个都存在 → 删除 2 条").isEqualTo(2);
        verify(quartz).unregisterSchedule("sch-a");
        verify(mapper).deleteById("sch-a");
        verify(quartz).unregisterSchedule("sch-b");
        verify(mapper).deleteById("sch-b");
        assertThat(jobs.get("sess-9"))
            .as("表面后删除同步移除 sessionJobs 索引（对齐 CC removeSessionCronTasks）")
            .containsExactly("sch-other");
    }

    @Test
    @DisplayName("removeMissedTasks 对不存在的 id 幂等跳过，不误删")
    void removeMissedSkipsMissing() {
        when(mapper.selectOneById("sch-gone")).thenReturn(null);

        int deleted = service.removeMissedTasks(List.of("sch-gone"));

        assertThat(deleted).isZero();
        verify(mapper, never()).deleteById("sch-gone");
        verify(quartz, never()).unregisterSchedule("sch-gone");
    }

    // ============ surfaceMissedForStartup（CRON-F5，编排：selectAll → DURABLE 过滤 → 表面 → 通知 → 删除） ============

    private ScheduleRecord recordScoped(String id, String kind, String cron, Long createdAt, String scope) {
        ScheduleRecord r = record(id, kind, cron, createdAt);
        r.setScope(scope);
        return r;
    }

    @Test
    @DisplayName("surfaceMissedForStartup：仅 DURABLE scope 表面并删除，SESSION 排除（对齐 CC file-backed only cronScheduler.ts:159-161）")
    void surfaceOnlyPersistentScope() {
        // WHY: CC 只加载 file-backed tasks（cronScheduler.ts:159-161 "Session tasks (durable:false)
        // are NOT loaded here"）。SESSION 任务仅 session 生命周期，启动时不得进入 missed 表面，
        // 否则 removeMissedTasks 会误删用户正在使用的 session 调度。
        ScheduleRecord persistent = recordScoped("sch-persist", "once", "0 0 9 * * ?",
            utc(2024, 1, 13, 12, 0), "DURABLE");
        ScheduleRecord session = recordScoped("sch-session", "once", "0 0 9 * * ?",
            utc(2024, 1, 13, 12, 0), "SESSION");
        long now = utc(2024, 1, 14, 12, 0);
        when(mapper.selectAll()).thenReturn(List.of(persistent, session));
        when(mapper.selectOneById("sch-persist")).thenReturn(persistent);

        Optional<String> notification = service.surfaceMissedForStartup(now);

        assertThat(notification).as("仅 DURABLE missed 被表面 → 通知非空").isPresent();
        assertThat(notification.get())
            .as("通知 header 指示先 AskUserQuestion 再执行")
            .contains("Do NOT execute this prompt yet")
            .contains("First use the AskUserQuestion tool");
        verify(mapper).deleteById("sch-persist");       // surface-then-delete（cronScheduler.ts:218-223）
        verify(quartz).unregisterSchedule("sch-persist");
        verify(mapper, never()).deleteById("sch-session"); // SESSION 排除，绝不误删
        verify(quartz, never()).unregisterSchedule("sch-session");
    }

    @Test
    @DisplayName("surfaceMissedForStartup：全 SESSION / 未来未到期 → Optional.empty 不删除")
    void emptyWhenNoPersistentMissed() {
        // WHY: 无 missed 必须静默返回（CC load(initial) missed.length===0 直接 return），
        // 且绝不能触发任何删除。
        ScheduleRecord future = recordScoped("sch-future", "once", "0 0 9 * * ?",
            utc(2024, 1, 13, 12, 0), "DURABLE");
        ScheduleRecord session = recordScoped("sch-session", "once", "0 0 9 * * ?",
            utc(2024, 1, 13, 12, 0), "SESSION");
        long now = utc(2024, 1, 13, 8, 0);   // 早于 next fire（09:00），无 missed
        when(mapper.selectAll()).thenReturn(List.of(future, session));

        Optional<String> notification = service.surfaceMissedForStartup(now);

        assertThat(notification).isEmpty();
        verify(mapper, never()).deleteById(anyString());
        verify(quartz, never()).unregisterSchedule(anyString());
    }

    @Test
    @DisplayName("surfaceMissedForStartup：surface→remove→notification 全链（对齐 CC onFire + removeCronTasks 顺序）")
    void surfaceThenRemoveThenNotify() {
        // WHY: 编排链必须完整 —— selectAll → findMissedForStartup(表面) → buildMissedTaskNotification(通知)
        // → removeMissedTasks(删除)。若只表面不删（check() 会重放原始 prompt）或只删不通知
        // （用户永远不知道任务被删），任一断裂都偏离 CC cronScheduler.ts:194-227。
        ScheduleRecord a = recordScoped("sch-a", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0), "DURABLE");
        ScheduleRecord b = recordScoped("sch-b", "once", "0 30 10 * * ?", utc(2024, 1, 13, 12, 0), "DURABLE");
        long now = utc(2024, 1, 14, 12, 0);
        when(mapper.selectAll()).thenReturn(List.of(a, b));
        when(mapper.selectOneById("sch-a")).thenReturn(a);
        when(mapper.selectOneById("sch-b")).thenReturn(b);

        Optional<String> notification = service.surfaceMissedForStartup(now);

        assertThat(notification).isPresent();
        assertThat(notification.get())
            .as("复数 header：两条 missed → 'tasks were' + 'these prompts'")
            .contains("one-shot scheduled tasks were missed")
            .contains("Do NOT execute these prompts yet")
            .contains("[0 0 9 * * ?, created");   // 每条 block 含 meta cron（cronScheduler.ts:553）
        verify(mapper).deleteById("sch-a");
        verify(mapper).deleteById("sch-b");
        verify(quartz).unregisterSchedule("sch-a");
        verify(quartz).unregisterSchedule("sch-b");
    }

    // ============ IMPL-10 (NEW-12): missed 事件遥测 ============

    @Test
    @DisplayName("IMPL-10: 有 missed → tengu_scheduled_task_missed 载荷 {count:2, taskIds:'sch-a,sch-b'}（CC cronScheduler.ts:205-212）")
    void missedEventEmittedWhenMissedExists() {
        ScheduleRecord a = recordScoped("sch-a", "once", "0 0 9 * * ?", utc(2024, 1, 13, 12, 0), "DURABLE");
        ScheduleRecord b = recordScoped("sch-b", "once", "0 30 10 * * ?", utc(2024, 1, 13, 12, 0), "DURABLE");
        long now = utc(2024, 1, 14, 12, 0);
        when(mapper.selectAll()).thenReturn(List.of(a, b));
        when(mapper.selectOneById("sch-a")).thenReturn(a);
        when(mapper.selectOneById("sch-b")).thenReturn(b);
        Telemetry telemetry = mock(Telemetry.class);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        service.surfaceMissedForStartup(now);

        verify(telemetry).recordEvent(eq("tengu_scheduled_task_missed"),
            eq(Map.<String, Object>of("count", 2, "taskIds", "sch-a,sch-b")));
    }

    @Test
    @DisplayName("IMPL-10: 无 missed → missed 事件 0 次调用（CC missed.length===0 不发射）")
    void missedEventNotEmittedWhenNone() {
        ScheduleRecord future = recordScoped("sch-future", "once", "0 0 9 * * ?",
            utc(2024, 1, 13, 12, 0), "DURABLE");
        ScheduleRecord session = recordScoped("sch-session", "once", "0 0 9 * * ?",
            utc(2024, 1, 13, 12, 0), "SESSION");
        long now = utc(2024, 1, 13, 8, 0);
        when(mapper.selectAll()).thenReturn(List.of(future, session));
        Telemetry telemetry = mock(Telemetry.class);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        Optional<String> notification = service.surfaceMissedForStartup(now);

        assertThat(notification).isEmpty();
        verify(telemetry, never()).recordEvent(anyString(), any());
    }

    // ============ 6 字段统一解析器（Session CRON-B1-2，R4 修复） ============

    @Test
    @DisplayName("6 字段 Quartz recurring 判 missed：工具创建任务不再恒不判 missed（R4 修复）")
    void sixFieldRecurringMissedDetected() {
        // WHY: CronCreateTool 经 joinVariants 存 Quartz 6 段（'0 30 9 ? * 2' = 周一 09:30）。
        // 旧私有 5 字段解析副本对 6 段恒返回 null → 工具创建任务永远不判 missed（OPD-RV-R4）。
        // 改用统一解析器后 6 字段 recurring 必须与 5 字段同判 missed（CC cronTasks.ts:453 无 recurring 过滤）。
        long now = utc(2024, 1, 15, 12, 0);
        ScheduleRecord r = record("sch-6f", "cron", "0 30 9 ? * 2", utc(2024, 1, 14, 8, 0));
        // createdAt 2024-01-14 08:00（周日）；next = 周一 2024-01-15 09:30 < now → missed

        assertThat(service.findMissedTasks(List.of(r), now))
            .as("6 字段 Quartz 存储串经统一解析器归一后必须判 missed（旧实现恒 null→非 missed）")
            .containsExactly(r);
    }

    @Test
    @DisplayName("|| OR 变体存串判 missed：双约束 recurring 窗口错过也算 missed")
    void orVariantMissedDetected() {
        // WHY: 双约束 cron 存 '0 0 9 1 * ?||0 0 9 ? * 2'（每月 1 号 OR 周一 09:00），
        // 统一解析器按变体取 min（cron.ts:151-158 OR 语义）才能正确判 missed。
        long now = utc(2024, 1, 15, 12, 0);
        ScheduleRecord r = record("sch-or", "cron", "0 0 9 1 * ?||0 0 9 ? * 2", utc(2024, 1, 14, 8, 0));
        // createdAt 2024-01-14 08:00（周日）；min(next)= 周一 2024-01-15 09:00 < now → missed

        assertThat(service.findMissedTasks(List.of(r), now))
            .as("|| 双约束变体存串必须按 OR 取 min 判 missed")
            .containsExactly(r);
    }

    @Test
    @DisplayName("null cron once 仍非 missed（统一解析器 null → next=null）")
    void nullCronStillNeverMissed() {
        long now = utc(2024, 1, 15, 12, 0);
        ScheduleRecord noCron = record("sch-nullc", "once", null, utc(2024, 1, 13, 12, 0));

        assertThat(service.findMissedTasks(List.of(noCron), now))
            .as("null cron → 统一解析器 next=null → 不判 missed（对齐 cronTasks.ts:305）")
            .isEmpty();
    }
}
