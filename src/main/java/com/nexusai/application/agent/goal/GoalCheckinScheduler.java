package com.nexusai.application.agent.goal;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.domain.session.SessionGoal;
import com.nexusai.domain.session.SessionService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * 目标 check-in / 评估器重试的常驻定时器 · 对齐 CC claude.exe v2.1.318 的
 * {@code Nn()} / {@code Xs()} / {@code Kp()} / {@code $a()} / {@code Ka()} / {@code Vp()}。
 *
 * <h2>为什么必须是一个常驻 bean（而不是写在 LlmAgentLoop 里）</h2>
 * CC 是单进程 CLI，{@code setTimeout} 挂在进程上，30 分钟后必然执行。
 * 本仓每个 HTTP send 都 new 一个 {@code LlmAgentLoop}（{@code prototype} 作用域），
 * 这一轮跑完对象就回收 ⇒ <b>写在它里面的 30 分钟定时器会随对象一起消亡</b>。
 * ⇒ 必须挂在一个从启动活到关闭的 Spring 单例上；待办清单落 {@code sessions.goal} 列
 * （重启不丢）。
 *
 * <h2>两类定时职责</h2>
 * <ol>
 *   <li><b>deferred check-in</b>（{@link #tick} · CC {@code Kp}）：
 *       目标因后台任务未归位而延迟评估时，按 30 分钟（可配）+ 指数退避 ×2 注入一次
 *       check-in 提示；最多 3 次 idle check-in。</li>
 *   <li><b>evaluator retry</b>：评估器 error 后按 60/300/900 秒三档自动重试
 *       （常量与抖动见 {@link GoalCheckinConfig}）——由 {@code LlmAgentLoop} 在判定
 *       error 时写 {@code deferredSince}，本定时器负责下一拍重新评估。</li>
 * </ol>
 *
 * <p><b>实现取舍：tick 轮询而不是每目标一个 {@code ScheduledFuture}</b>
 * —— 目标会被随时创建/清除/暂停，维护「每个目标一个 future」的增删同步点比扫一遍
 * 活跃目标更容易出错（漏删 → 幽灵定时器）。本仓目标数量是个位数（会话级），
 * 每 30 秒扫一遍的成本可忽略，且天然自愈（重启后由 DB 列重建）。
 *
 * <p><b>为什么提示走 {@link NotificationQueue}</b>：本仓「让某个会话再跑一轮」的唯一
 * 成熟通道就是队列（cron / 排队命令 / 通道消息都走它）。直接调 LlmAgentLoop 会绕过
 * 会话空闲判定与并发保护。
 *
 * <p>⚠️ <b>本类不是 {@code @Component}</b>：由 {@link GoalCheckinSchedulerConfig} 的
 * {@code @Bean} 方法显式构造（要注入 {@code IntSupplier} 读源，Spring 无法按类型装配
 * lambda）。若再加 {@code @Component} 会出现<b>两个实例 ⇒ 两个定时线程</b>。
 */
public class GoalCheckinScheduler {

    private static final Logger log = LoggerFactory.getLogger(GoalCheckinScheduler.class);

    /** tick 间隔 —— 足够细，让 60 秒档的重试也能按时触发。 */
    private static final long TICK_INTERVAL_SECONDS = 30L;

    private final SessionService sessionService;
    private final NotificationQueue notificationQueue;
    /**
     * check-in 分钟数的读源（设置→高级 · settings.goal_checkin_minutes）。
     *
     * <p>{@code IntSupplier} 不是 Spring 能自动装配的类型 ⇒ 由
     * {@code GoalCheckinSchedulerConfig} 的 {@code @Bean} 方法显式构造并注入
     * （⛔ 不在本类写 {@code @Value}：本类是被 {@code new} 出来的话 {@code @Value} 永不执行）。
     */
    private final IntSupplier checkinMinutesSource;

    private ScheduledExecutorService executor;
    private volatile boolean started;

    /**
     * 生产构造器。
     *
     * @param sessionService        会话/目标真源（读写 {@code sessions.goal} 列）
     * @param notificationQueue     让会话再跑一轮的唯一通道
     * @param checkinMinutesSource  check-in 分钟数读源（返回值 ≤0 时用默认 30）
     */
    public GoalCheckinScheduler(SessionService sessionService,
                                @Autowired(required = false) NotificationQueue notificationQueue,
                                IntSupplier checkinMinutesSource) {
        this.sessionService = sessionService;
        this.notificationQueue = notificationQueue;
        this.checkinMinutesSource = checkinMinutesSource;
    }

    /** 启动定时器（幂等）。 */
    @jakarta.annotation.PostConstruct
    public void start() {
        if (started) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "goal-checkin");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tickSafely,
            TICK_INTERVAL_SECONDS, TICK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        started = true;
        log.info("[goal-checkin] 常驻定时器已启动（每 {}s 扫描一次活跃目标；间隔配置见「设置→高级」）",
            TICK_INTERVAL_SECONDS);
    }

    /** 关闭定时器。 */
    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        started = false;
    }

    /** 单次 tick 全包异常 · 定时任务抛异常会被调度器静默丢弃后续 tick，必须自己兜住。 */
    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            log.warn("[goal-checkin] tick 失败（不影响下一次）: {}", t.toString());
        }
    }

    /** 扫描所有仍有目标的行，分别处理 deferred check-in 与 evaluator retry。 */
    void tick() {
        List<SessionGoalSnapshot> goals = loadLiveGoals();
        if (goals.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int checkinMinutes = resolveCheckinMinutes();
        for (SessionGoalSnapshot snap : goals) {
            try {
                tickOne(snap, now, checkinMinutes);
            } catch (Throwable t) {
                log.warn("[goal-checkin] session={} 处理失败: {}", snap.sessionId(), t.toString());
            }
        }
    }

    /** 处理单个目标。 */
    private void tickOne(SessionGoalSnapshot snap, long now, int checkinMinutes) {
        SessionGoal goal = snap.goal();
        if (!goal.isLive()) {
            return;
        }
        // ── ① 评估器重试（独立于 check-in：CC 的 goalInterruptionStreak）──
        if (goal.retryAt() != null && now >= goal.retryAt()) {
            retryEvaluation(snap.sessionId(), goal);
            return;
        }
        // ── ② deferred check-in（后台任务未归位）──
        if (goal.deferredSince() == null) {
            return;
        }
        // check-in 被显式关闭（goal_checkin_minutes = 0）→ 不注入提示，但也不改目标
        if (checkinMinutes <= 0) {
            return;
        }
        long baseMs = GoalCheckinConfig.resolveCheckinIntervalMs(checkinMinutes);
        if (baseMs <= 0) {
            return;
        }
        int count = goal.checkinCount() == null ? 0 : goal.checkinCount();
        long waitMs = GoalCheckinConfig.backoffMs(baseMs, count);
        long waited = now - goal.deferredSince();
        if (waited < waitMs) {
            return;
        }
        if (count >= GoalCheckinConfig.MAX_IDLE_CHECKINS) {
            // 达到 idle 上限 → 只推进 deferredSince（重排 tick），不再注入提示
            // CC original: "idle check-in cap reached — tick re-armed only"
            persistDeferral(snap.sessionId(), goal, now, count, now);
            if (log.isDebugEnabled()) {
                log.debug("[goal-checkin] session={} 已达 idle check-in 上限 {}，仅重排 tick",
                    snap.sessionId(), GoalCheckinConfig.MAX_IDLE_CHECKINS);
            }
            return;
        }
        deliverCheckin(snap.sessionId(), goal, now, waited);
        persistDeferral(snap.sessionId(), goal, now, count + 1, now);
    }

    /**
     * 评估器重试到期 ⇒ 触发一轮重新评估（对齐 CC {@code Vp()}）。
     *
     * <p><b>⚠️ 只清 {@code retryAt}，保留 {@code retryCount}</b>：计数是「这轮目标一共
     * 自动重试过几次」，由 {@code scheduleEvaluatorRetry} 递增判定上限。若在这里一起清零，
     * 每次到点都会把上限重置 ⇒ 变成无限重试（永久性失败 + 守卫复位 = 重试风暴）。
     */
    private void retryEvaluation(String sessionId, SessionGoal goal) {
        // [goal-clear-race 2026-11-04] 写回前存活复核：本类按 tick 快照工作，快照与本次写回之间
        //   有时间窗。用户在窗内点了 × / 发了 /goal clear ⇒ 目标已清，而这里拿旧快照改完写回
        //   = **已清除的目标复活**（真机日志 19:17:18 phase=none → 19:17:20 phase=ACTIVE 就是这个）。
        if (!stillLive(sessionId)) {
            return;
        }
        SessionGoal consumed = goal.consumeRetry();
        sessionService.setGoal(sessionId, consumed);
        String body = "<goal_steering type=\"retry\">\n"
            + "Goal retry: the previous goal check for \"" + goal.condition() + "\" could not be completed. "
            + "Continue toward the goal; the checker will run again at the end of this turn.\n"
            + "</goal_steering>";
        enqueueMetaPrompt(sessionId, body);
        log.info("[goal-checkin] session={} 评估器重试到期 ⇒ 已触发一轮重判（第 {} 次）",
            sessionId, goal.retryCount() == null ? 0 : goal.retryCount());
    }

    /** 注入 check-in 提示（对齐 CC {@code Ha()} 的文案结构）。 */
    private void deliverCheckin(String sessionId, SessionGoal goal, long now, long deferredMs) {
        // [goal-clear-race 2026-11-04] 注入前同样复核：目标已清还注入 check-in，会让模型
        //   继续追一个用户已经取消的目标（白烧一轮 LLM）。
        if (!stillLive(sessionId)) {
            return;
        }
        if (notificationQueue == null) {
            log.warn("[goal-checkin] session={} NotificationQueue 未注入，check-in 无法投递", sessionId);
            return;
        }
        long minutes = Math.max(1, Math.round(deferredMs / 60_000.0));
        String body = "<goal_steering type=\"checkin\">\n"
            + "Goal check-in: the goal \"" + goal.condition() + "\" is still active. "
            + "Its evaluation was deferred for " + minutes + " min while background work ran. "
            + "Check on the background work's progress (e.g. read its output). "
            + "If it is progressing, say so briefly and keep waiting; "
            + "if it is stuck or no longer needed, fix or stop it and continue toward the goal.\n"
            + "</goal_steering>";
        enqueueMetaPrompt(sessionId, body);
        log.info("[goal-checkin] session={} 已注入 check-in（延迟 {} 分钟）", sessionId, minutes);
    }

    /**
     * 把一段提示当作 isMeta 的 prompt 入队，让该会话再跑一轮。
     *
     * <p>只填最少字段：{@code value}（提示正文）+ {@code mode="prompt"} + {@code isMeta=true}
     * + {@code sessionId}（会话归属，CronIdleExecutor 据此判定目标会话是否空闲）。
     */
    private void enqueueMetaPrompt(String sessionId, String text) {
        NotificationQueue.QueueItem item = new NotificationQueue.QueueItem(
            text, "prompt", null, null, java.util.UUID.randomUUID().toString(),
            true, null, true, null, sessionId, null, null);
        notificationQueue.enqueue(item);
    }

    /** 写回 deferred 三字段。 */
    private void persistDeferral(String sessionId, SessionGoal goal, long deferredSince,
                                 int checkinCount, long lastDeferralPassAt) {
        // [goal-clear-race 2026-11-04] 写回前存活复核（理由同 retryEvaluation）。
        //   这是最直接的复活点：deferredSince/checkinCount 的回写会把整份旧快照重新落库。
        if (!stillLive(sessionId)) {
            return;
        }
        SessionGoal updated = goal.withDeferral(deferredSince, checkinCount, lastDeferralPassAt);
        sessionService.setGoal(sessionId, updated);
    }

    /**
     * 「目标是否还活着」复核 · 每个「写回 / 注入」动作前调用。
     *
     * <p><b>WHY</b>：本类按 <b>tick 快照</b>工作（{@link #tick} 先扫出快照列表，再逐个处理），
     * 快照与动作之间存在时间窗。若用户在窗内清除目标，拿旧快照改完写回就等于
     * <b>把已清除的目标复活</b> —— 真机复现：
     * {@code 19:17:18.799 setGoal: phase=none}（清除成功）→
     * {@code 19:17:20.097 setGoal: phase=ACTIVE}（本类写回）。
     *
     * <p>⛔ 不用「条件 UPDATE（WHERE goal IS NOT NULL）」方案：那要改 {@code setGoal} 签名，
     * 波及 13 个调用点；本类目标数量是个位数、30 秒一拍，多读一次 DB 的成本可忽略。
     *
     * @param sessionId 会话 ID
     * @return true = 目标仍在追踪（可以继续写回/注入）；false = 已清除或已终态 ⇒ 放弃本次动作
     */
    private boolean stillLive(String sessionId) {
        try {
            SessionGoal current = sessionService.getGoal(sessionId);
            if (current == null || !current.isLive()) {
                log.info("[goal-checkin] session={} 目标已被清除/已终结 ⇒ 放弃本次写回与提示", sessionId);
                return false;
            }
            return true;
        } catch (Exception e) {
            // 读失败按「存活」处理：宁可写回也不要误丢用户的目标（fail-soft，但留痕）
            log.warn("[goal-checkin] session={} 存活复核读取失败（按存活处理）: {}", sessionId, e.toString());
            return true;
        }
    }

    /** 读配置的 check-in 分钟数（null = 未配置 → 默认 30）。 */
    private int resolveCheckinMinutes() {
        try {
            int configured = checkinMinutesSource == null ? 0 : checkinMinutesSource.getAsInt();
            return configured > 0 ? configured : GoalCheckinConfig.DEFAULT_CHECKIN_MINUTES;
        } catch (Exception e) {
            log.warn("[goal-checkin] 读 check-in 配置失败，用默认 {} 分钟: {}",
                GoalCheckinConfig.DEFAULT_CHECKIN_MINUTES, e.toString());
            return GoalCheckinConfig.DEFAULT_CHECKIN_MINUTES;
        }
    }

    /** 扫出所有仍有目标（goal 列非空）的会话 · 解析失败的跳过（fail-soft）。 */
    private List<SessionGoalSnapshot> loadLiveGoals() {
        List<SessionGoalSnapshot> out = new java.util.ArrayList<>();
        try {
            for (com.nexusai.repository.session.entity.SessionRecord rec : sessionService.listSessionsWithGoal()) {
                SessionGoal g = SessionGoal.fromJson(rec.getGoal());
                if (g != null && g.isLive()) {
                    out.add(new SessionGoalSnapshot(rec.getId(), g));
                }
            }
        } catch (Exception e) {
            log.warn("[goal-checkin] 扫描活跃目标失败: {}", e.toString());
        }
        return out;
    }

    /** 目标 + 所属会话的轻量快照。 */
    record SessionGoalSnapshot(String sessionId, SessionGoal goal) {
    }
}
