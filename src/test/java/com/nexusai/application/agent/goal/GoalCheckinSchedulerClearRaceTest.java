package com.nexusai.application.agent.goal;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.domain.session.SessionGoal;
import com.nexusai.domain.session.SessionService;
import com.nexusai.repository.session.entity.SessionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [goal-clear-race · 2026-11-04] 调度器「写回 / 注入前存活复核」契约测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：用户实测「点 × 清除后目标又回来」。真机日志实证：
 * <pre>
 * 19:17:18.799 setGoal: phase=none        ← 清除成功（DB 已置空）
 * 19:17:20.097 setGoal: phase=ACTIVE      ← 本调度器用 tick 快照写回，把目标复活了
 * </pre>
 * 本类是「tick 扫描快照 → 逐个处理」的结构，快照与写回之间存在时间窗。用户在窗内清除目标后，
 * 拿旧快照改完写回 = 已清除的目标复活。修复 = 每个写回/注入动作前重读 DB，目标已不在就放弃。
 *
 * <p>两条用例成对，缺一不可：
 * <ol>
 *   <li><b>已清除 ⇒ 不写回不注入</b> —— 锁住复活缺陷；</li>
 *   <li><b>仍存活 ⇒ 照常注入并写回</b> —— 锁住「守卫别把正常路径也拦掉」
 *       （只测第一条的话，把守卫写成 {@code return false} 恒放弃也能全绿 = 假绿）。</li>
 * </ol>
 */
@DisplayName("[goal-clear-race] 调度器写回前存活复核")
class GoalCheckinSchedulerClearRaceTest {

    private static final String SID = "sess-goal-race";

    /** 造一条「goal 列里有目标、deferredSince 已在 31 分钟前」的会话行（check-in 必然到期）。 */
    private static SessionRecord deferredRow(long deferredSince) {
        SessionGoal g = new SessionGoal(
            "e2e 全绿", SessionGoal.Phase.ACTIVE, 0,
            System.currentTimeMillis(), 0L, null,
            deferredSince, 0, deferredSince, null, null);
        SessionRecord rec = new SessionRecord();
        rec.setId(SID);
        rec.setGoal(SessionGoal.toJson(g));
        return rec;
    }

    /** 同一份目标的「存活」形态（供重读返回）。 */
    private static SessionGoal liveGoal(long deferredSince) {
        return new SessionGoal(
            "e2e 全绿", SessionGoal.Phase.ACTIVE, 0,
            System.currentTimeMillis(), 0L, null,
            deferredSince, 0, deferredSince, null, null);
    }

    @Test
    @DisplayName("check-in 到期但目标已被清除 ⇒ 不注入提示、不写回（不复活已清目标）")
    void clearedGoalIsNotResurrected() {
        long deferredSince = System.currentTimeMillis() - 31 * 60_000L;
        SessionService sessionService = mock(SessionService.class);
        NotificationQueue queue = mock(NotificationQueue.class);

        // 快照阶段：goal 列里还有这条目标（扫描发生在用户点 × 之前）
        when(sessionService.listSessionsWithGoal()).thenReturn(List.of(deferredRow(deferredSince)));
        // 重读阶段：用户已点 × ⇒ 目标没了
        when(sessionService.getGoal(SID)).thenReturn(null);

        GoalCheckinScheduler scheduler = new GoalCheckinScheduler(sessionService, queue, () -> 30);
        scheduler.tick();

        verify(sessionService, never()).setGoal(anyString(), any());
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("目标仍存活 ⇒ 照常注入 check-in 并写回（守卫不得误伤正常路径）")
    void liveGoalStillGetsCheckin() {
        long deferredSince = System.currentTimeMillis() - 31 * 60_000L;
        SessionService sessionService = mock(SessionService.class);
        NotificationQueue queue = mock(NotificationQueue.class);

        when(sessionService.listSessionsWithGoal()).thenReturn(List.of(deferredRow(deferredSince)));
        // 重读阶段：目标仍在追踪
        when(sessionService.getGoal(SID)).thenReturn(liveGoal(deferredSince));

        GoalCheckinScheduler scheduler = new GoalCheckinScheduler(sessionService, queue, () -> 30);
        scheduler.tick();

        verify(queue).enqueue(any());
        verify(sessionService).setGoal(anyString(), any());
    }

    /** retry 已到期的目标行。 */
    private static SessionRecord retryRow(long retryAt, int retryCount) {
        SessionGoal g = new SessionGoal(
            "e2e 全绿", SessionGoal.Phase.ACTIVE, 1,
            System.currentTimeMillis(), 0L, null,
            null, null, null, retryAt, retryCount);
        SessionRecord rec = new SessionRecord();
        rec.setId(SID);
        rec.setGoal(SessionGoal.toJson(g));
        return rec;
    }

    @Test
    @DisplayName("评估器重试到期 ⇒ steering 必须带 workload（否则被当成用户 prompt 永不消费）")
    void retrySteering_carriesWorkload() {
        long retryAt = System.currentTimeMillis() - 1_000L;
        SessionService sessionService = mock(SessionService.class);
        NotificationQueue queue = mock(NotificationQueue.class);

        when(sessionService.listSessionsWithGoal()).thenReturn(List.of(retryRow(retryAt, 1)));
        // 重读：目标仍存活（stillLive 守卫放行）
        when(sessionService.getGoal(SID)).thenReturn(new SessionGoal(
            "e2e 全绿", SessionGoal.Phase.ACTIVE, 1,
            System.currentTimeMillis(), 0L, null,
            null, null, null, retryAt, 1));

        GoalCheckinScheduler scheduler = new GoalCheckinScheduler(sessionService, queue, () -> 30);
        scheduler.tick();

        ArgumentCaptor<NotificationQueue.QueueItem> cap =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(cap.capture());
        assertThat(cap.getValue().workload())
            .as("workload=null 会被 CronIdleExecutor 当成用户 prompt 永不打捞 ⇒ 重试卡死")
            .isEqualTo(GoalCheckinScheduler.WORKLOAD_GOAL_STEERING);
    }

    private static NotificationQueue.QueueItem queuedSteering() {
        return new NotificationQueue.QueueItem(
            "steering", "prompt", null, null, null, true,
            GoalCheckinScheduler.WORKLOAD_GOAL_STEERING, true, null, SID, null, null);
    }

    @Test
    @DisplayName("队列已有同类 steering ⇒ 重试不重复注入，只重排 retryAt（对齐 CC hasQueued）")
    void retryWithQueuedSteering_rearmsOnly() {
        long retryAt = System.currentTimeMillis() - 1_000L;
        SessionService sessionService = mock(SessionService.class);
        NotificationQueue queue = mock(NotificationQueue.class);
        when(sessionService.listSessionsWithGoal()).thenReturn(List.of(retryRow(retryAt, 1)));
        when(sessionService.getGoal(SID)).thenReturn(new SessionGoal(
            "e2e 全绿", SessionGoal.Phase.ACTIVE, 1,
            System.currentTimeMillis(), 0L, null,
            null, null, null, retryAt, 1));
        when(queue.peek(any())).thenReturn(java.util.Optional.of(queuedSteering()));

        new GoalCheckinScheduler(sessionService, queue, () -> 30).tick();

        verify(queue, never()).enqueue(any());
        verify(sessionService).setGoal(anyString(), any());   // 只重排 retryAt，保证不丢
    }

    @Test
    @DisplayName("队列已有同类 steering ⇒ check-in 本轮跳过（不注入、不推进计数）")
    void checkinWithQueuedSteering_skipped() {
        long deferredSince = System.currentTimeMillis() - 31 * 60_000L;
        SessionService sessionService = mock(SessionService.class);
        NotificationQueue queue = mock(NotificationQueue.class);
        when(sessionService.listSessionsWithGoal()).thenReturn(List.of(deferredRow(deferredSince)));
        when(sessionService.getGoal(SID)).thenReturn(liveGoal(deferredSince));
        when(queue.peek(any())).thenReturn(java.util.Optional.of(queuedSteering()));

        new GoalCheckinScheduler(sessionService, queue, () -> 30).tick();

        verify(queue, never()).enqueue(any());
        verify(sessionService, never()).setGoal(anyString(), any());
    }
}
