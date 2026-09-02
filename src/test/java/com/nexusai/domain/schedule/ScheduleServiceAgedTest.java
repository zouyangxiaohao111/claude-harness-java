package com.nexusai.domain.schedule;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.config.CronJitterProperties;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRON-B3 聚焦测试：aged 纯函数 + deleteAfterFire fire-then-delete 支持方法。
 *
 * <p><b>WHY (意图验证)</b>: CC 没有独立后台清理循环，过期 recurring 只在 fire 时刻由
 * {@code isRecurringTaskAged} 判定（cronScheduler.ts:302-343），aged 任务 fire 后必须删除，
 * 否则 7 天过期任务永不被清理（E-16）。CC cronScheduler.ts:53-60 定义三个不变量
 * （maxAge=0 永不 aged / permanent 豁免 / 边界 {@code >=}），Java 必须镜像，否则 aged
 * recurring 不会在 fire 后被删除；one-shot 任务 fire 后必须 auto-delete（E-13）以释放
 * MAX_JOBS 配额，即使 aged 判定为 false。Java 偏离 CC 的 {@code >}（旧 cleanupExpiredRecurring）
 * 会在 ageMs 恰好等于 maxAgeMs 时漏删。
 *
 * <p>纯函数用例直接构造 {@link ScheduleRecord} 不依赖 DB；deleteAfterFire 用
 * Mockito mock {@link ScheduleMapper}/{@link QuartzScheduleService}（不引入
 * MybatisFlexBootstrap，避免与 ScheduleServiceCreateStorageTest 的 Flex 单例在同 JVM 混跑冲突），
 * 聚焦验证决策逻辑（one-shot 必删 / 未 aged recurring 保留 / aged recurring 删 / sessionJobs 同步）。
 */
class ScheduleServiceAgedTest {

    private ScheduleMapper mapper;
    private QuartzScheduleService quartz;
    private ScheduleService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ScheduleMapper.class);
        quartz = mock(QuartzScheduleService.class);
        service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService", quartz);
    }

    // ============ 纯函数 isRecurringTaskAged ============

    private ScheduleRecord record(String kind, Long createdAt, Boolean permanent) {
        ScheduleRecord r = new ScheduleRecord();
        r.setId("sch-test");
        r.setKind(kind);
        r.setCreatedAt(createdAt);
        r.setPermanent(permanent);
        return r;
    }

    @Test
    @DisplayName("aged 边界：ageMs 恰好等于 maxAgeMs → true（S-02 边界 >=，非 >）")
    void agedAtExactBoundary() {
        long now = 1_000_000L;
        long maxAge = 60_000L;
        ScheduleRecord r = record("cron", now - maxAge, Boolean.FALSE);
        assertThat(service.isRecurringTaskAged(r, now, maxAge))
            .as("ageMs 恰好等于 maxAgeMs 必须 true（CC cronScheduler.ts:59 >=，旧实现 > 会漏删）")
            .isTrue();
    }

    @Test
    @DisplayName("permanent 豁免：createdAt 远超 maxAge 但 permanent=true → 永不 aged")
    void permanentNeverAged() {
        long now = 1_000_000L;
        long maxAge = 60_000L;
        ScheduleRecord r = record("cron", now - 999_000_000L, Boolean.TRUE);
        assertThat(service.isRecurringTaskAged(r, now, maxAge))
            .as("permanent 任务豁免 7 天过期（CC !t.permanent，cronScheduler.ts:59）")
            .isFalse();
    }

    @Test
    @DisplayName("maxAgeMs == 0 → 永不 aged（CC 0 = unlimited，cronTasks.ts:343）")
    void zeroMaxAgeNeverAged() {
        long now = 1_000_000L;
        ScheduleRecord r = record("cron", now - 999_000_000L, Boolean.FALSE);
        assertThat(service.isRecurringTaskAged(r, now, 0))
            .as("maxAge=0 表示不限，永不 aged（cronScheduler.ts:58 maxAgeMs===0）")
            .isFalse();
    }

    @Test
    @DisplayName("kind=once → aged 判定 false（one-shot 不参与 recurring 过期判定）")
    void onceKindAgedFalse() {
        long now = 1_000_000L;
        long maxAge = 60_000L;
        ScheduleRecord r = record("once", now - 999_000_000L, Boolean.FALSE);
        assertThat(service.isRecurringTaskAged(r, now, maxAge))
            .as("one-shot 不参与 recurring 过期判定（CC t.recurring 为 false）")
            .isFalse();
    }

    @Test
    @DisplayName("createdAt 为 null（旧行未写）→ 永不 aged（B1 兼容，不老化无 createdAt 的行）")
    void nullCreatedAtNeverAged() {
        long now = 1_000_000L;
        ScheduleRecord r = record("cron", null, Boolean.FALSE);
        assertThat(service.isRecurringTaskAged(r, now, 60_000L))
            .as("旧行 createdAt=NULL 不老化（B1 迁移兼容）")
            .isFalse();
    }

    // ============ deleteAfterFire fire-then-delete ============

    @Test
    @DisplayName("one-shot fire 后 deleteAfterFire 仍删（aged=false 但 one-shot 必删，E-13 释放 MAX_JOBS）")
    void deleteAfterFireDeletesOneShot() {
        ScheduleRecord r = record("once", 1_000L, Boolean.FALSE);
        when(mapper.selectOneById(r.getId())).thenReturn(r);

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).as("one-shot fire 后必须删除（CC :315 else 分支）").isTrue();
        verify(mapper).deleteById(r.getId());
        verify(quartz).unregisterSchedule(r.getId());
    }

    @Test
    @DisplayName("recurring 未 aged fire 后 deleteAfterFire 保留（返回 false，供 WF-D reschedule）")
    void deleteAfterFireKeepsYoungRecurring() {
        long now = System.currentTimeMillis();
        ScheduleRecord r = record("cron", now - 1_000L, Boolean.FALSE); // 刚创建 1 秒
        when(mapper.selectOneById(r.getId())).thenReturn(r);

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).as("recurring 未 aged → 保留并 reschedule（CC :315）").isFalse();
        verify(mapper, never()).deleteById(r.getId());
        verify(quartz, never()).unregisterSchedule(r.getId());
    }

    @Test
    @DisplayName("aged recurring fire 后 deleteAfterFire 删除（7 天过期 fire-then-delete）")
    void deleteAfterFireDeletesAgedRecurring() {
        long now = System.currentTimeMillis();
        ScheduleRecord r = record("cron", now - CronJitterProperties.DEFAULTS.recurringMaxAgeMs(), Boolean.FALSE);
        when(mapper.selectOneById(r.getId())).thenReturn(r);

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).as("aged recurring fire 后必须删除（E-16 fire-then-delete）").isTrue();
        verify(mapper).deleteById(r.getId());
        verify(quartz).unregisterSchedule(r.getId());
    }

    @Test
    @DisplayName("配置 recurringMaxAgeMs=60s 时，createdAt 61s 前的 recurring fire 后删除（aged 用运行时配置值，非常量 7d）")
    void deleteAfterFireUsesConfiguredMaxAgeWhenAged() {
        // 注入 jitterProps（recurringMaxAgeMs=60s）→ aged 判定必须用新值，而非编译期常量 7d（决策 #14 OPD-EL-03）
        ReflectionTestUtils.setField(service, "jitterProps",
            new CronJitterProperties(0.1, 900_000L, 90_000L, 0L, 30, 60_000L));
        long now = System.currentTimeMillis();
        ScheduleRecord r = record("cron", now - 61_000L, Boolean.FALSE); // 61s 前 > 60s 阈值
        when(mapper.selectOneById(r.getId())).thenReturn(r);

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).as("61s 前创建 ≥ 60s 阈值 → aged，fire 后删除（CC cronScheduler.ts:302 读 jitterCfg.recurringMaxAgeMs）")
            .isTrue();
        verify(mapper).deleteById(r.getId());
        verify(quartz).unregisterSchedule(r.getId());
    }

    @Test
    @DisplayName("配置 recurringMaxAgeMs=60s 时，createdAt 30s 前的 recurring fire 后保留（未 aged）")
    void deleteAfterFireUsesConfiguredMaxAgeWhenYoung() {
        ReflectionTestUtils.setField(service, "jitterProps",
            new CronJitterProperties(0.1, 900_000L, 90_000L, 0L, 30, 60_000L));
        long now = System.currentTimeMillis();
        ScheduleRecord r = record("cron", now - 30_000L, Boolean.FALSE); // 30s 前 < 60s 阈值
        when(mapper.selectOneById(r.getId())).thenReturn(r);

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).as("30s 前创建 < 60s 阈值 → 未 aged，保留 reschedule（CC :315）").isFalse();
        verify(mapper, never()).deleteById(r.getId());
        verify(quartz, never()).unregisterSchedule(r.getId());
    }

    @Test
    @DisplayName("id 不存在 → 返回 false 且无删除动作（记录 warn）")
    void deleteAfterFireNotFoundNoOp() {
        when(mapper.selectOneById("sch-missing")).thenReturn(null);

        boolean deleted = service.deleteAfterFire("sch-missing");

        assertThat(deleted).isFalse();
        verify(mapper, never()).deleteById(org.mockito.ArgumentMatchers.anyString());
        verify(quartz, never()).unregisterSchedule(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("删除后 sessionJobs 内存索引全列表同步移除（CC removeSessionCronTasks 同步，cronScheduler.ts:329）")
    void deleteAfterFireSyncsSessionJobs() {
        ScheduleRecord r = record("once", 1_000L, Boolean.FALSE);
        r.setId("sch-sess");
        when(mapper.selectOneById("sch-sess")).thenReturn(r);
        @SuppressWarnings("unchecked")
        Map<String, CopyOnWriteArrayList<String>> jobs =
            (Map<String, CopyOnWriteArrayList<String>>) ReflectionTestUtils.getField(service, "sessionJobs");
        jobs.put("sess-9", new CopyOnWriteArrayList<>(List.of("sch-sess", "sch-other")));

        boolean deleted = service.deleteAfterFire("sch-sess");

        assertThat(deleted).isTrue();
        assertThat(jobs.get("sess-9"))
            .as("删除后 sessionJobs 索引必须移除该 id（对齐 CC removeSessionCronTasks）")
            .containsExactly("sch-other");
    }

    // ============ IMPL-10 (NEW-12): expired 事件遥测 ============

    @Test
    @DisplayName("IMPL-10: aged recurring 删除 → tengu_scheduled_task_expired 载荷 {taskId, ageHours}（CC cronScheduler.ts:308-312）")
    void expiredEventEmittedOnAgedRecurringDelete() {
        long now = System.currentTimeMillis();
        ScheduleRecord r = record("cron", now - CronJitterProperties.DEFAULTS.recurringMaxAgeMs(), Boolean.FALSE);
        when(mapper.selectOneById(r.getId())).thenReturn(r);
        Telemetry telemetry = mock(Telemetry.class);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).isTrue();
        // createdAt = now-7d，deleteAfterFire 内 now'≈now（ms 级偏差）→ ageHours=168 稳定（floor 语义）
        verify(telemetry).recordEvent(eq("tengu_scheduled_task_expired"),
            eq(Map.<String, Object>of("taskId", r.getId(),
                "ageHours", CronJitterProperties.DEFAULTS.recurringMaxAgeMs() / 1000 / 60 / 60)));
    }

    @Test
    @DisplayName("IMPL-10: young recurring 保留 → expired 事件 0 次（CC 仅 aged 路径发射）")
    void expiredEventNotEmittedForYoungRecurring() {
        long now = System.currentTimeMillis();
        ScheduleRecord r = record("cron", now - 1_000L, Boolean.FALSE);
        when(mapper.selectOneById(r.getId())).thenReturn(r);
        Telemetry telemetry = mock(Telemetry.class);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).isFalse();
        verify(telemetry, never()).recordEvent(anyString(), any());
    }

    @Test
    @DisplayName("IMPL-10: one-shot 删除 → expired 事件 0 次（CC :315-344 仅 aged 路径有 logEvent）")
    void expiredEventNotEmittedForOneShotDelete() {
        ScheduleRecord r = record("once", 1_000L, Boolean.FALSE);
        when(mapper.selectOneById(r.getId())).thenReturn(r);
        Telemetry telemetry = mock(Telemetry.class);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).isTrue();
        verify(telemetry, never()).recordEvent(anyString(), any());
    }

    @Test
    @DisplayName("IMPL-10: telemetry null（未注入）→ aged recurring 删除行为不变、不抛异常")
    void expiredWithNullTelemetrySkipsSilently() {
        long now = System.currentTimeMillis();
        ScheduleRecord r = record("cron", now - CronJitterProperties.DEFAULTS.recurringMaxAgeMs(), Boolean.FALSE);
        when(mapper.selectOneById(r.getId())).thenReturn(r);
        // 不注入 telemetry（setUp 后默认 null）→ emitExpiredTelemetry 静默 return，不影响删除语义

        boolean deleted = service.deleteAfterFire(r.getId());

        assertThat(deleted).isTrue();
        verify(mapper).deleteById(r.getId());
        verify(quartz).unregisterSchedule(r.getId());
    }
}
