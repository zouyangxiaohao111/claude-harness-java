package com.nexusai.kb.app.ingest;

import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库任务回收 · 扫「超期 {@code running}」任务并判死（源侧 housekeeping 的等价物）。
 *
 * <p><b>源侧对照</b>：WeKnora {@code internal/application/service/knowledge_housekeeping.go}
 * （{@code HousekeepingService.runSweep}；cron 每 5 分钟一次，字面量见该文件
 * {@code Start} 方法）。
 *
 * <p>⚠️ 此处<b>不抄 cron 字面量</b>：它含「星号 + 斜杠」，写进 javadoc 会提前闭合注释
 * （实测 2026-11-06 编译报一片「非法字符」）。
 *
 * <h2>⭐⭐ 为什么必须有这道兜底</h2>
 * {@link KbIngestTaskQueue#claimNext()} 把任务置 {@code running} 之后，只有
 * {@code markDone} / {@code markFailure} 能把它挪走。若<b>进程在中途退出</b>
 * （杀进程、崩溃、机器重启）或解析线程永久卡死，这两条路都不会被调用
 * ⇒ 该行永远 {@code running}，对应文档永远 {@code processing}（用户在界面上看到
 * 一个永不停止的转圈）。没有任何重试会捡起 {@code running} 行 —— {@code claimNext}
 * 只查 {@code queued}。
 *
 * <p>{@link KbIngestWorker} 进程内无法自救（它自己就是死掉的那个），所以回收只能
 * 由<b>定时扫描</b>完成。
 *
 * <h2>⭐ 时间判据 = {@code started_at}（不是 {@code created_at}）</h2>
 * V260 建表时没有 {@code updated_at}，只有 {@code created_at} / {@code started_at} /
 * {@code finished_at} 三列。用 {@code created_at} 会把「排队很久才被认领」的任务
 * 误判为超期（排队时长与执行时长被混为一谈）；而 {@code started_at} 由
 * {@link KbIngestTaskQueue#claimNext()} 在<b>认领那一刻</b>写入，正是「开始执行」的
 * 精确时刻，且重试时会重新认领并覆盖 ⇒ 无需新增列、无需迁移。
 *
 * <p>兜底 {@code COALESCE(started_at, created_at)}：{@code started_at} 理论上对
 * {@code running} 行恒非空（只有 {@code claimNext} 会写入该状态），但老数据/手工改库
 * 的行可能为空 —— 退到入队时间，方向偏保守（更早判为超期）。此处如实登记：
 * 若真出现 {@code started_at} 为空的行，判据是近似值。
 *
 * <p>⚠️ <b>时区口径</b>：本判据只比<b>本表内应用写入</b>的两列。{@code enqueue()}
 * 显式把 {@code created_at} 写成 {@link KbTimeFormat#now()}（<b>本地时区</b>），与
 * {@code started_at} 同源 ⇒ 两者可比。但建表默认值用的是 SQLite
 * {@code datetime('now')}（<b>UTC</b>）—— 只有绕过 {@code enqueue()} 直接 INSERT
 * 的行才会带上默认值，那种行跨时区差 8 小时，判据会偏早。当前代码路径不存在这种行。
 *
 * <h2>⭐ 为什么不会误杀正常短任务</h2>
 * <ol>
 *   <li>只扫 {@code state='running'}；{@code queued} 是「积压」不是「卡死」，一律不动。</li>
 *   <li>判据是「<b>开始执行</b>已超过阈值分钟数」（默认 130 分钟，照抄源站 2h + 10min）。
 *       1024 页扫描版 PDF + 视觉补强可能跑很久，阈值必须留足余量。</li>
 *   <li>UPDATE 带 {@code AND state = 'running'}：与 {@code markFailure} 同款条件写，
 *       已被人改写的行不会被拽回来。</li>
 *   <li>文档状态只在「该文档<b>再无</b>其它活任务」时才置 failed（见
 *       {@link #hasLiveTask}）—— 还有子任务在排队的文档属于积压，不是孤儿。</li>
 * </ol>
 *
 * <h2>调度线程池（2026-11-12 已配置）</h2>
 * {@code spring.task.scheduling.pool.size = 8} + 线程名前缀 {@code nexusai-sched-}
 * （{@code application.yml}）：所有 {@code @Scheduled} 方法不再共用 Spring 默认的<b>单线程</b>，
 * 本扫描不会占住 {@link KbIngestWorker} 的入库 tick（源站把这些拆成独立 asynq 池，
 * {@code internal/types/task.go:17-23}；Spring 侧最近的等价物就是有界调度池）。
 * 仍保留每轮 {@link #MAX_SWEEP_PER_TICK} 条上限 —— 一轮异常积压不该让单次扫描跑很久。
 */
@Component
public class KbIngestHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(KbIngestHousekeeping.class);

    /**
     * 扫描间隔：5 分钟（对齐源侧 {@code knowledge_housekeeping.go:111 Start} 的每 5 分钟一次）。
     */
    private static final long SWEEP_INTERVAL_MS = 300_000L;

    /**
     * 超期阈值默认值：<b>130 分钟</b>（照抄源站两段口径叠加）。
     *
     * <ul>
     *   <li>{@code DocumentProcessTimeout} 默认 <b>2 小时</b>
     *       —— {@code D:/tmp/WeKnora/internal/config/config.go:231}
     *       {@code DefaultDocumentProcessTimeout = 2 * time.Hour}：单份文档处理任务的总时限；</li>
     *   <li>housekeeping 阈值 {@code max(1h, DocumentProcessTimeout) + 10min}
     *       —— {@code internal/application/service/knowledge_housekeeping.go:911-917}。</li>
     * </ul>
     * ⇒ 2h + 10min = <b>130 分钟</b>。本仓原来的 30 分钟会把「正跑着的 1024 页扫描 PDF」
     * 当成卡死回收（源站不会）—— 属阈值口径偏离，不是保守。
     */
    public static final long DEFAULT_STALE_TIMEOUT_MINUTES = 130L;

    /** 超期阈值（分钟）· 可配 {@code nexusai.knowledge.ingest.stale-timeout-minutes}。 */
    @org.springframework.beans.factory.annotation.Value(
        "${nexusai.knowledge.ingest.stale-timeout-minutes:130}")
    private long staleTimeoutMinutes = DEFAULT_STALE_TIMEOUT_MINUTES;

    /** 单轮最多回收多少条（见类注释「可能与 KbIngestWorker 抢调度线程」）。 */
    private static final int MAX_SWEEP_PER_TICK = 200;

    /** 回收原因（写进 {@code kb_ingest_task.error_message} 与 {@code kb_knowledge.error_message}）。 */
    static final String TIMEOUT_MESSAGE = "任务超时（worker 可能已退出）";

    /** 文档可被判死的前置状态：只有这两态会「永远转圈」。 */
    private static final List<String> STUCK_PARSE_STATUSES = List.of("processing", "finalizing");

    private final JdbcTemplate jdbc;

    public KbIngestHousekeeping(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 扫超期 {@code running} 任务并回收。
     *
     * <p>流程（逐条）：
     * <ol>
     *   <li>带条件 UPDATE 把任务置 {@code dead} + 写超时原因（影响行数 = 0 说明被别人
     *       抢先处理了，跳过）；</li>
     *   <li>该文档若再无任何活任务（{@code queued} 或未超期的 {@code running}），
     *       把 {@code processing} / {@code finalizing} 置为 {@code failed}。</li>
     * </ol>
     *
     * <p>⛔ 不开事务：每一步都是独立的短写。本仓事务管理器是
     * {@code FlexTransactionManager}，长事务持 SQLite 写锁会与
     * {@link KbIngestWorker} 的认领、活动流写入互相等待（{@code SQLITE_BUSY} 历史问题）；
     * 且本操作<b>幂等</b>，中途失败下一轮重试即可。
     */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void sweepStaleRunning() {
        try {
            doSweep();
        } catch (RuntimeException e) {
            // 调度线程抛异常会被静默吞掉且后续 tick 可能停止 ⇒ 必须自己兜住（同 KbIngestWorker）
            log.error("[入库回收] 本轮扫描异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 单次扫描（可测入口）。
     *
     * @return 本轮回收的任务条数
     */
    public int doSweep() {
        String now = KbTimeFormat.now();
        String cutoff = KbTimeFormat.format(
            LocalDateTime.now().minusMinutes(staleTimeoutMinutes));

        List<StaleTask> candidates = jdbc.query(
            "SELECT id, knowledge_id FROM kb_ingest_task "
                + "WHERE state = ? "
                + "AND COALESCE(started_at, created_at) < ? "
                + "ORDER BY id "
                + "LIMIT " + MAX_SWEEP_PER_TICK,
            (rs, i) -> new StaleTask(rs.getLong(1), rs.getString(2)),
            KbIngestTaskRecord.STATE_RUNNING, cutoff);
        if (candidates.isEmpty()) {
            return 0;
        }

        int recoveredTasks = 0;
        int recoveredDocs = 0;
        for (StaleTask t : candidates) {
            // ⚠️ 条件写：与 markFailure 同款。别人已改写的行（派单里不存在，但将来可能有
            //    人工修复工具）不会被拽回来。
            int updated = jdbc.update(
                "UPDATE kb_ingest_task SET state = ?, error_message = ?, finished_at = ? "
                    + "WHERE id = ? AND state = ?",
                KbIngestTaskRecord.STATE_DEAD, TIMEOUT_MESSAGE, now,
                t.id(), KbIngestTaskRecord.STATE_RUNNING);
            if (updated == 0) {
                continue;
            }
            recoveredTasks++;

            // ⭐ 文档只在「没有别的活任务」时才判死 —— 还有子任务在排队 = 积压，不是孤儿
            if (hasLiveTask(t.knowledgeId(), cutoff)) {
                log.info("[入库回收] 任务超时但文档仍有活任务，保留文档状态 task={} knowledge={}",
                    t.id(), t.knowledgeId());
                continue;
            }
            recoveredDocs += jdbc.update(
                "UPDATE kb_knowledge SET parse_status = ?, error_message = ?, update_time = ? "
                    + "WHERE id = ? AND parse_status IN "
                    + "(" + placeholders(STUCK_PARSE_STATUSES.size()) + ")",
                docFailureArgs(t.knowledgeId(), now));
        }

        if (recoveredTasks > 0) {
            log.warn("[入库回收] 回收超期 running 任务 {} 条（阈值 {} 分钟），"
                    + "其中 {} 个文档置为 failed cutoff={}",
                recoveredTasks, staleTimeoutMinutes, recoveredDocs, cutoff);
        }
        return recoveredTasks;
    }

    /**
     * 该文档是否还有「活」任务。
     *
     * <p>活 = {@code queued}（排队中会被 worker 认领）或<b>未超期</b>的 {@code running}
     * （另一个 worker 正在正常跑）。仅超期的 {@code running} 不算 —— 那正是要被回收的。
     *
     * @param knowledgeId 知识条目 ID
     * @param cutoff      超期分界（{@code started_at} 早于它的 running 视为已死）
     * @return true = 仍有活任务（文档不该判死）
     */
    private boolean hasLiveTask(String knowledgeId, String cutoff) {
        Integer live = jdbc.queryForObject(
            "SELECT COUNT(*) FROM kb_ingest_task "
                + "WHERE knowledge_id = ? AND (state = ? "
                + "OR (state = ? AND COALESCE(started_at, created_at) >= ?))",
            Integer.class,
            knowledgeId, KbIngestTaskRecord.STATE_QUEUED,
            KbIngestTaskRecord.STATE_RUNNING, cutoff);
        return live != null && live > 0;
    }

    /** 拼 {@code ?,?,?}（⛔ 不字符串插值状态值，只插占位符个数）。 */
    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    /**
     * 组装「文档判死」UPDATE 的参数，顺序与 SQL 的占位符逐位对应：
     * {@code SET parse_status, error_message, update_time} → {@code WHERE id} →
     * {@code WHERE parse_status IN (...)}。
     *
     * @param knowledgeId 知识条目 ID
     * @param now         当前时间串（{@code yyyy-MM-dd HH:mm:ss}）
     * @return 参数数组
     */
    private static Object[] docFailureArgs(String knowledgeId, String now) {
        Object[] args = new Object[4 + STUCK_PARSE_STATUSES.size()];
        args[0] = "failed";
        args[1] = TIMEOUT_MESSAGE;
        args[2] = now;
        args[3] = knowledgeId;
        for (int i = 0; i < STUCK_PARSE_STATUSES.size(); i++) {
            args[4 + i] = STUCK_PARSE_STATUSES.get(i);
        }
        return args;
    }

    /** 一条超期任务（只取回收需要的两列）。 */
    private record StaleTask(long id, String knowledgeId) {
    }
}
