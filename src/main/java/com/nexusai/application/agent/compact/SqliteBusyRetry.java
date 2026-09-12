package com.nexusai.application.agent.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * compact 落库的 {@code SQLITE_BUSY_SNAPSHOT} <b>重试单点</b> —— 全仓 5 条落库通道共用。
 *
 * <h2>WHY（规则九：为什么必须「共用」而不是各写各的）</h2>
 * compact 结果落库共 5 条通道（① auto / ② reactive / ③ manual {@code /compact} / ④ SM / ⑤ partial），
 * 出口只有 {@code MessageService.appendPostCompactMessages} 一个（{@code @Transactional}，先 SELECT
 * knownIds 拿<b>读快照</b> → 再 INSERT/UPDATE）。WAL 下降级提交顶掉读快照后，该事务内<b>任何写</b>都抛
 * {@code SQLITE_BUSY_SNAPSHOT}（SQLite 不允许把过期读快照升级为写事务，JDBC 的 {@code busy_timeout}
 * 对本错误不生效）。
 *
 * <p>该失败模式对 5 条通道<b>完全同源</b>（同一出口方法、同一 SQLite 并发形状），但修复时只给 ⑤ partial
 * 加了重试 → 策略分裂成「5 次 vs 0 次」两档，①②③④ 命中即失败（内存已换压缩视图、DB 未落 → 下轮从 DB
 * 恢复压缩前全量 → <b>每轮重复压缩</b>）。本类把「判别 + 次数 + 换新事务 + fail-loud」收成一处，
 * 使「同源故障 = 同策略」在结构上成立（改次数/退避/判别口径只改这一个文件）。
 *
 * <h2>CC 对照（为什么 CC 侧没有这个东西）</h2>
 * CC 压缩后是<b>纯文件追加</b>（{@code utils/sessionStorage.ts:1015 insertMessageChain} →
 * {@code :1272 enqueueWrite} → {@code :659 drainWriteQueue} → {@code :648 appendToFile} →
 * {@code :650 fsAppendFile}，O_APPEND 单写），<b>没有「读快照」概念</b> ⇒ 结构上不可能出该错。
 * CC 全仓 {@code sessionStorage.ts} 内 {@code retry|backoff|attempt|busy} 仅 1 处注释命中，
 * <b>零行重试代码</b>（唯一"重试"是 ENOENT 时 {@code mkdir -p} 后再 append 一次，
 * {@code sessionStorage.ts:649-656}）。本类是我们「共享 SQLite + 多会话」相对 CC 的<b>增量</b>，
 * 不是 CC 对齐物。
 *
 * <h2>前提（调用方义务 · 违反则重试无效）</h2>
 * <b>每次尝试必须换新事务</b>：{@code BUSY_SNAPSHOT} = 读快照已死，同事务内重试<b>无用</b>（快照无法
 * 刷新）。故：
 * <ul>
 *   <li>{@link #executeWithBusyRetry} —— 依赖 action 内的 {@code @Transactional} 方法<b>每次调用天然开
 *       新事务</b>（本仓调用方 {@code ChatService} / {@code CompactCommand} / {@code LlmAgentLoop}
 *       均<b>无</b> {@code @Transactional}，Spring 代理下每次调用即新事务，已核实）；</li>
 *   <li>{@link #executeInTxWithBusyRetry} —— {@code TransactionTemplate.execute} 每次调用都开新事务；</li>
 *   <li>两者都在入口断言「调用时无活动事务」，若已存在则 <b>fail loud（log.warn）</b> —— 此时重试无效，
 *       属于调用方误加外层 {@code @Transactional} 的结构性错误，不能静默。</li>
 * </ul>
 *
 * <h2>幂等（重试为什么安全）</h2>
 * 失败的一次尝试整体回滚、DB 无残留；{@code appendPostCompactMessages} 的 boundary/summary id 来自
 * 上游 DTO（稳定），重插不产生重复行（seq / created_at 分配器只产生跳号，不产生回退）。
 */
public final class SqliteBusyRetry {

    private static final Logger log = LoggerFactory.getLogger(SqliteBusyRetry.class);

    /**
     * 落库最大尝试次数（首次 + 4 次重试）；有限次数，耗尽则 {@code fail loud}。
     *
     * <p>原为 {@code PartialCompactService.MAX_WRITE_ATTEMPTS}（⑤ partial 独有）—— 现为 5 通道共用，
     * <b>不得</b>在别处再定义一个同义常量（那正是「策略分裂」的复发路径）。
     */
    public static final int MAX_WRITE_ATTEMPTS = 5;

    private SqliteBusyRetry() {
    }

    /**
     * {@code SQLITE_BUSY} / {@code SQLITE_BUSY_SNAPSHOT} 判别（可重试错误）· 沿 cause 链匹配消息
     * 「SQLITE_BUSY」（同时覆盖 {@code SQLITE_BUSY}(5) 与 {@code SQLITE_BUSY_SNAPSHOT}(517) 两种文案）。
     *
     * <p><b>不</b>按 {@code org.sqlite.SQLiteException} 类型匹配：生产异常可能被 MyBatis / Spring
     * 事务层包装，消息仍留在 cause 链上，但最外层类型不一定是它。
     *
     * @param t 待判别异常（null → false）
     * @return true = 可重试的 SQLite BUSY 类错误
     */
    public static boolean isSqliteBusy(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("SQLITE_BUSY")) {
                return true;
            }
            Throwable next = cur.getCause();
            cur = (next == cur) ? null : next;
        }
        return false;
    }

    /**
     * 普通通道重试包装（①②③④⑤ 无显式事务分支）· 命中 BUSY 则<b>重新调用 action</b>（= 换新事务）。
     *
     * <p>前提：{@code action} 内的写方法自带 {@code @Transactional} 且调用链上没有外层事务 —— 每次
     * {@code action.get()} 即一次新的数据库事务（见类 JavaDoc「前提」）。
     *
     * @param op     操作名（日志用，如 {@code "[compact-persist] append-only 落库"}）
     * @param action 单次尝试的写动作（必须自带事务语义 / 每次调用开新事务）
     * @param <T>    返回值类型
     * @return action 的返回值（成功那次）
     * @throws RuntimeException 非 BUSY 错误 → <b>立即</b>抛出（不重试）；BUSY 且 {@link #MAX_WRITE_ATTEMPTS}
     *                          次耗尽 → 抛最后一次 BUSY（fail loud，调用方按失败处置）
     */
    public static <T> T executeWithBusyRetry(String op, Supplier<T> action) {
        warnIfAmbientTransaction(op, "重试换新事务");
        RuntimeException lastBusy = null;
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                T result = action.get();
                if (attempt > 1) {
                    log.warn("[SqliteBusyRetry] {}: 第 {} 次尝试成功（前 {} 次 SQLITE_BUSY_SNAPSHOT，已换新事务重试）",
                        op, attempt, attempt - 1);
                } else if (log.isDebugEnabled()) {
                    log.debug("[SqliteBusyRetry] {}: 第 1 次尝试成功（未触发重试）", op);
                }
                return result;
            } catch (RuntimeException e) {
                if (!isSqliteBusy(e)) {
                    // 非 BUSY（约束冲突 / SQL 语法 / 代码 bug）立即抛出：重试会把真 bug 掩盖成「偶发失败」，
                    // 且不可重试错误重试纯属浪费时间（规则十二 fail loud）。
                    throw e;
                }
                lastBusy = e;
                log.warn("[SqliteBusyRetry] {}: 第 {}/{} 次尝试命中 SQLITE_BUSY_SNAPSHOT（换新事务重试）: {}",
                    op, attempt, MAX_WRITE_ATTEMPTS, e.getMessage());
            }
        }
        log.error("[SqliteBusyRetry] {}: {} 次尝试均因 SQLITE_BUSY_SNAPSHOT 失败，放弃（fail loud）",
            op, MAX_WRITE_ATTEMPTS);
        throw lastBusy;
    }

    /**
     * 显式事务通道重试包装（⑤ partial）· 每次尝试经 {@code template} 开<b>新事务</b>，把
     * {@code primaryWrite} 与 {@code extraSameTxAction} 放进<b>同一事务</b>。
     *
     * <p><b>WHY 需要 extraSameTxAction（原子性）</b>：partial 落库必须把
     * {@code appendPostCompactMessages} 与 {@code sessionService.updateConversationId} 放在同一事务
     * （任一失败整块回滚，不留「boundary 落了而 conversationId 没更新」的半写）。本重载保留该原子性：
     * 两个动作都在同一次 {@code template.execute} 回调内，回滚/提交边界与修复前逐位一致；
     * ①②③④ 的 {@code primaryWrite} 只做落库、{@code extraSameTxAction} 传 {@code null}。
     *
     * @param op               操作名（日志用）
     * @param template         事务模板（调用方持有，如 {@code new TransactionTemplate(txManager)}）
     * @param primaryWrite     主写动作（compact 落库）
     * @param extraSameTxAction 需与主写同事务的额外动作（null = 无）
     * @param <T>              primaryWrite 的返回值类型
     * @return primaryWrite 的返回值（成功那次）
     */
    public static <T> T executeInTxWithBusyRetry(String op,
                                                 TransactionTemplate template,
                                                 Supplier<T> primaryWrite,
                                                 Runnable extraSameTxAction) {
        return executeWithBusyRetry(op, () -> template.execute(status -> {
            T written = primaryWrite.get();
            if (extraSameTxAction != null) {
                extraSameTxAction.run();
            }
            return written;
        }));
    }

    /**
     * 调用时若已存在活动事务 → 本次重试<b>无法</b>换新读快照（同事务内重试无用）→ fail loud。
     *
     * <p>不是断言失败（不阻断业务），而是把「结构性错误」显式化：本仓 compact 落库的 4 个调用点
     * （{@code ChatService} / {@code CompactCommand} / {@code PartialCompactService}）均无
     * {@code @Transactional}，故生产恒不触发；一旦触发即说明调用链被新增的外层事务包住，必须修。
     */
    private static void warnIfAmbientTransaction(String op, String what) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("[SqliteBusyRetry] {}: 调用时已存在活动事务 → {} 拿不到新读快照，BUSY_SNAPSHOT 重试"
                + "可能无效（SQLite 不允许同事务内刷新快照）。调用链不得对本重试包装加外层 @Transactional", op, what);
        }
    }
}
