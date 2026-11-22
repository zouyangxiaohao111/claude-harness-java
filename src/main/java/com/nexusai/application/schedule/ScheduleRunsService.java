package com.nexusai.application.schedule;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.repository.schedule.entity.ScheduleRunRecord;
import com.nexusai.repository.schedule.mapper.ScheduleRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 定时任务运行记录（V306 {@code schedule_runs}）。
 *
 * <h2>为什么需要它</h2>
 * 现状 {@code schedules.last_run_status='ok'} 是<b>入队那一刻</b>写的
 * （{@code AgentJob.applyFireLifecycle} → {@code ScheduleService.markFired}），
 * agent loop 之后才异步跑 ⇒ <b>跑崩了仍显示「成功」</b>。
 * 本服务把「开始」和「结束」两个时点分开记，{@code status} 只有 run 真正结束时才定。
 *
 * <h2>两个写点</h2>
 * <ol>
 *   <li>{@link #start}：{@code AgentJob.fire} 入队成功后插一行 {@code running}；</li>
 *   <li>{@link #finish}：{@code CronIdleExecutor} 收口时按真实结果更新
 *       （{@code ok} / {@code error} / {@code cancelled}）。</li>
 * </ol>
 *
 * <p><b>为什么不塞 QRTZ 表</b>：{@code QRTZ_JOB_DETAILS} 无结果列；
 * {@code QRTZ_FIRED_TRIGGERS} 是运行时锁表，Quartz 触发完即删行。
 *
 * <p><b>保留期</b>：30 天（{@link #purgeOlderThanDays}）。**不清理 C 形态专属会话** ——
 * 那是另一件事（{@code SessionMode.NEW_ONCE} 的会话跟任务生命周期走），本服务只删运行记录行。
 */
@Service
public class ScheduleRunsService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunsService.class);

    /** 输出摘要上限（运行记录列表 + 详情都要能看，超长截断）。 */
    private static final int SUMMARY_MAX = 2000;

    /** prompt 落库上限。 */
    private static final int PROMPT_MAX = 2000;

    @Autowired(required = false) private ScheduleRunMapper runMapper;

    /**
     * 记录一次「开始执行」。
     *
     * <p>入队成功后立刻插行 —— 这样即使 run 崩在收口前，也能在「运行记录」里看到一条
     * 卡在 {@code running} 的记录（比完全没痕迹好排查）。
     *
     * @param dto         任务 DTO
     * @param sessionMode 本轮**实际**用的形态（可能是降级后的值）
     * @param sessionId   本轮实际用的会话（可为 null）
     * @param trigger     {@code quartz} | {@code manual}
     * @return 运行记录 id；未注入 mapper / 异常 → null（不阻断 fire）
     */
    public String start(ScheduleDto dto, String sessionMode, String sessionId, String trigger) {
        if (runMapper == null || dto == null) return null;
        try {
            ScheduleRunRecord r = new ScheduleRunRecord();
            r.setId("run-" + java.util.UUID.randomUUID().toString().substring(0, 8));
            r.setScheduleId(dto.id());
            r.setScheduleName(dto.name() == null ? "" : dto.name());
            r.setSessionMode(sessionMode == null ? "REUSE" : sessionMode);
            r.setTriggerSource(trigger == null ? "quartz" : trigger);
            r.setStatus("running");
            r.setStartedAt(now());
            r.setSessionId(sessionId);
            r.setPrompt(truncate(dto.command(), PROMPT_MAX));
            r.setCreatedAt(now());
            runMapper.insert(r);
            log.info("[ScheduleRuns] start run={} schedule={} mode={} session={} trigger={}",
                r.getId(), dto.id(), r.getSessionMode(), sessionId, r.getTriggerSource());
            return r.getId();
        } catch (Exception e) {
            // 不阻断 fire：任务照常执行，只是没有运行记录
            log.error("[ScheduleRuns] 写入 running 行失败 schedule={}: {}", dto.id(), e.toString(), e);
            return null;
        }
    }

    /**
     * 记录一次「执行结束」。
     *
     * @param runId     {@link #start} 返回的 id；null → 无记录可更新（no-op）
     * @param status    {@code ok} | {@code error} | {@code cancelled}
     * @param summary   结果摘要（{@code AgentState.lastAssistant()} 截断）
     * @param error     失败原因
     * @param sessionId 本轮实际用的会话（形态 NEW_EACH 时为新建的那个）
     */
    public void finish(String runId, String status, String summary, String error, String sessionId) {
        if (runMapper == null || runId == null) return;
        try {
            ScheduleRunRecord patch = new ScheduleRunRecord();
            patch.setStatus(status);
            patch.setFinishedAt(now());
            patch.setOutputSummary(truncate(summary, SUMMARY_MAX));
            patch.setError(truncate(error, SUMMARY_MAX));
            if (sessionId != null && !sessionId.isBlank()) {
                patch.setSessionId(sessionId);
            }
            // duration 由 SQL 侧算不了（SQLite 有 julianday 但时区易错），在 Java 侧算：
            ScheduleRunRecord existing = runMapper.selectOneById(runId);
            if (existing != null && existing.getStartedAt() != null) {
                try {
                    Instant startTs = OffsetDateTime.parse(existing.getStartedAt()).toInstant();
                    patch.setDurationMs(ChronoUnit.MILLIS.between(startTs, Instant.now()));
                } catch (Exception ignore) {
                    // 时间串解析不了就不写耗时（不影响状态）
                }
            }
            // ignoreNulls=true：只更新 patch 里非 null 的列（sessionId 为空时不覆盖原值）
            runMapper.updateByQuery(patch, true, QueryWrapper.create().eq("id", runId));
            log.info("[ScheduleRuns] finish run={} status={} durationMs={}",
                runId, status, patch.getDurationMs());
        } catch (Exception e) {
            log.error("[ScheduleRuns] 更新运行记录失败 run={}: {}", runId, e.toString(), e);
        }
    }

    /**
     * 收口：按任务 id 找到<b>最近一条 running</b> 行并更新。
     *
     * <p><b>为什么不用 runId</b>：{@code runId} 产生于 {@code AgentJob.fire}（producer 线程），
     * 而要更新它的是 {@code CronIdleExecutor}（consumer 线程）。把 runId 透传过去需要给
     * {@code QueueItem} 加字段（该类已有 8 个兼容构造器，改动面大）。用
     * {@code (schedule_id, status='running')} 取最近一条即可 —— 同一任务不会并发 fire
     * （Quartz {@code isNonconcurrent} + 队列串行消费）。
     *
     * @param scheduleId 任务 id（{@code QueueItem.scheduleId()}）
     * @param status     {@code ok} | {@code error} | {@code cancelled}
     */
    public void finishLatestRunning(String scheduleId, String status, String summary,
                                    String error, String sessionId) {
        if (runMapper == null || scheduleId == null || scheduleId.isBlank()) return;
        try {
            List<ScheduleRunRecord> running = runMapper.selectListByQuery(QueryWrapper.create()
                .eq("schedule_id", scheduleId)
                .eq("status", "running")
                .orderBy("started_at", false)
                .limit(1));
            if (running.isEmpty()) {
                log.debug("[ScheduleRuns] 无 running 行可收口 schedule={}（可能未注入 mapper 或已收口）",
                    scheduleId);
                return;
            }
            finish(running.get(0).getId(), status, summary, error, sessionId);
        } catch (Exception e) {
            log.error("[ScheduleRuns] 收口查询失败 schedule={}: {}", scheduleId, e.toString(), e);
        }
    }

    /**
     * 每个任务「最近一次运行」的状态（V306）。
     *
     * <p><b>为什么需要它</b>：{@code schedules.last_run_status} 是<b>入队那一刻</b>写的
     * （{@code markFired}），跑崩了仍显示 ok —— 假信号。列表的绿点/红点应读本表最新一条。
     *
     * <p><b>口径</b>：每任务取 {@code started_at} 最新的一条；{@code running} 原样返回
     * （调用方可显示「运行中」）。一次查询 + 内存分组，避免逐任务 N+1。
     *
     * @return scheduleId → status（{@code running}/{@code ok}/{@code error}/{@code cancelled}）
     */
    public java.util.Map<String, String> latestStatusBySchedule() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (runMapper == null) return out;
        try {
            // 取最近 500 条（50 个任务上限 × 10 次历史，足够覆盖每任务最新一条）
            List<ScheduleRunRecord> recent = runMapper.selectListByQuery(QueryWrapper.create()
                .orderBy("started_at", false)
                .limit(500));
            for (ScheduleRunRecord r : recent) {
                // 列表已按时间倒序 ⇒ 首次出现即该任务最新一条
                out.putIfAbsent(r.getScheduleId(), r.getStatus());
            }
        } catch (Exception e) {
            log.warn("[ScheduleRuns] 查询最新状态失败（列表回落 schedules.last_run_status）: {}",
                e.toString());
        }
        return out;
    }

    /** 某任务的运行记录（时间倒序，最近 N 条）。 */
    public List<ScheduleRunRecord> listBySchedule(String scheduleId, int limit) {
        if (runMapper == null || scheduleId == null) return List.of();
        return runMapper.selectListByQuery(QueryWrapper.create()
            .eq("schedule_id", scheduleId)
            .orderBy("started_at", false)
            .limit(Math.max(1, limit)));
    }

    /** 全部运行记录（时间倒序）。 */
    public List<ScheduleRunRecord> listRecent(int limit) {
        if (runMapper == null) return List.of();
        return runMapper.selectListByQuery(QueryWrapper.create()
            .orderBy("started_at", false)
            .limit(Math.max(1, limit)));
    }

    /**
     * 清理 N 天前的运行记录。
     *
     * <p>⚠️ 只删 {@code schedule_runs} 行，<b>不动会话</b> —— C 形态的专属会话生命周期
     * 跟任务走（任务删了才删），不适用 30 天规则；B 形态的一次性会话由会话侧清理器负责。
     *
     * @return 删除行数
     */
    public int purgeOlderThanDays(int days) {
        if (runMapper == null || days <= 0) return 0;
        String cutoff = OffsetDateTime.now(ZoneId.systemDefault())
            .minusDays(days).toString();
        int n = runMapper.deleteByQuery(QueryWrapper.create().lt("started_at", cutoff));
        if (n > 0) {
            log.info("[ScheduleRuns] 清理 {} 天前的运行记录 {} 条（cutoff={}）", days, n, cutoff);
        }
        return n;
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
