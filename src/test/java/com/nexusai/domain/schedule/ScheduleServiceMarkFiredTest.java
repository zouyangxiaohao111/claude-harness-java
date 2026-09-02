package com.nexusai.domain.schedule;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRON-F4 · {@link ScheduleService#markFired} 聚焦测试（沿用 ScheduleServiceAgedTest
 * mock mapper 模式，不引入 MybatisFlexBootstrap）。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: CC markCronTasksFired（cronTasks.ts:261-278）fire 后把
 * lastFiredAt 写回任务行，Java 等价用一条 updateByQuery 批量写 lastRunAt=now +
 * lastRunStatus=ok。三个不变量必须镜像：
 * <ul>
 *   <li>ids 空 → 0 行更新（对齐 {@code ids.length===0 return}，cronTasks.ts:266）</li>
 *   <li>非空 → 单 SQL 批量写（对齐 CC 一次 read-modify-write，cronTasks.ts:269-277）；
 *       patch 只含 lastRunAt/lastRunStatus（ignoreNulls=true，其余 null 字段不写）</li>
 *   <li>返回受影响行数（0 = 无命中 no-op，对齐 {@code if (!changed) return}）</li>
 * </ul>
 */
class ScheduleServiceMarkFiredTest {

    private ScheduleMapper mapper;
    private ScheduleService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ScheduleMapper.class);
        service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService",
            mock(QuartzScheduleService.class));
    }

    @Test
    @DisplayName("ids 空 → 返回 0 且不触 DB（CC cronTasks.ts:266 ids.length===0 return）")
    void emptyIdsNoOp() {
        int rows = service.markFired(List.of(), OffsetDateTime.now());

        assertThat(rows).as("空 ids 必须 0 行更新（CC :266 直接 return）").isZero();
        verify(mapper, never()).updateByQuery(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("非空 ids → updateByQuery 单 SQL 批量写 lastRunAt/lastRunStatus（ignoreNulls=true）")
    void nonEmptyIdsBatchesLastRunAt() {
        OffsetDateTime firedAt = OffsetDateTime.now();
        when(mapper.updateByQuery(any(), eq(true), any())).thenReturn(2);

        int rows = service.markFired(List.of("sch-a", "sch-b"), firedAt);

        assertThat(rows).as("返回受影响行数（CC changed=true 才 write）").isEqualTo(2);
        verify(mapper).updateByQuery(
            argThat(p -> p instanceof ScheduleRecord r
                && r.getLastRunAt() != null
                && r.getLastRunAt().equals(firedAt.toString())
                && "ok".equals(r.getLastRunStatus())),
            eq(true),
            argThat(q -> q instanceof QueryWrapper));
    }

    @Test
    @DisplayName("无命中 → 返回 0（对齐 CC if (!changed) return no-op）")
    void noMatchReturnsZero() {
        when(mapper.updateByQuery(any(), eq(true), any())).thenReturn(0);

        int rows = service.markFired(List.of("sch-missing"), OffsetDateTime.now());

        assertThat(rows).as("无命中 0 行 = no-op（CC :276 !changed return）").isZero();
    }
}
