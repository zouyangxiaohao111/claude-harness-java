package com.nexusai.infra.schedule;

import org.quartz.impl.jdbcjobstore.DBSemaphore;
import org.quartz.impl.jdbcjobstore.LockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SQLite 适配的 Quartz 集群信号量（lockHandler）。
 *
 * <p>CC 跨进程单一调度者锁意图（Open-ClaudeCode/src/utils/cronTasksLock.ts:64-91 O_EXCL 独占创建、
 * cronScheduler.ts:350 if(isOwner) 门控）由 Quartz 集群模式等价表达（OPD-Cron-04-01 拍板）：
 * 跨 JVM 只有拿到 QRTZ_LOCKS 行写锁的节点执行触发，避免双发。Java 端不复刻 O_EXCL/PID lease。
 *
 * <p>SQLite 无 {@code SELECT ... FOR UPDATE}（StdRowLockSemaphore 默认 SQL 不可用），
 * 本类以「幂等写语句抢占 SQLite 独占写锁（RESERVED）」实现跨进程互斥：
 * <ul>
 *   <li>连接已处于 autocommit=false 事务（JobStoreSupport.getNonManagedTXConnection），
 *       xerial 惰性 BEGIN —— executeSQL 的<b>首条语句</b>必须是写语句，才能一次性拿到 RESERVED 写锁
 *       （等价 BEGIN IMMEDIATE 语义）；若首条是 SELECT 只拿 SHARED，随后升级在竞争下会 SQLITE_BUSY。</li>
 *   <li>{@code INSERT OR IGNORE INTO {0}LOCKS(...)} 即使用户行已存在仍计为写语句 → 仍获取 RESERVED 写锁
 *       （已实证：行已存在时 INSERT OR IGNORE 后并发写会 SQLITE_BUSY）。</li>
 *   <li>锁在 JobStore 事务 commit/rollback 时由 SQLite 释放（继承 {@link DBSemaphore#releaseLock(String)}
 *       只清 ThreadLocal 可重入集合，不碰 DB）。</li>
 * </ul>
 *
 * <p>配置（application.yml quartz 段）：{@code org.quartz.jobStore.lockHandler.class=
 * com.nexusai.infra.schedule.SQLiteClusterSemaphore}。
 */
public class SQLiteClusterSemaphore extends DBSemaphore {

    private static final Logger log = LoggerFactory.getLogger(SQLiteClusterSemaphore.class);

    /** CC 对照：Quartz 集群锁即 StdRowLockSemaphore 的 SELECT FOR UPDATE 行锁，SQLite 以写锁替代。 */
    private static final String SELECT_FOR_LOCK =
            "SELECT * FROM {0}LOCKS WHERE SCHED_NAME = {1} AND LOCK_NAME = ?";

    /** 幂等写语句：作为 executeSQL 首条语句抢占 SQLite 独占写锁（等价 BEGIN IMMEDIATE）。 */
    private static final String INSERT_OR_IGNORE_LOCK =
            "INSERT OR IGNORE INTO {0}LOCKS(SCHED_NAME, LOCK_NAME) VALUES ({1}, ?)";

    private int maxRetry = 3;
    private long retryPeriod = 1000L;

    public SQLiteClusterSemaphore() {
        // 仿 StdRowLockSemaphore 默认构造：tablePrefix=QRTZ_，schedName=null（JobStoreSupport 后续注入）
        super("QRTZ_", null, SELECT_FOR_LOCK, INSERT_OR_IGNORE_LOCK);
    }

    /** 重试次数上限：StdRowLockSemaphore 默认 3 次（javap 自验 quartz-2.5.0）。 */
    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    /** 重试周期 ms：StdRowLockSemaphore 默认 1000ms（javap 自验 quartz-2.5.0）。 */
    public void setRetryPeriod(long retryPeriod) {
        this.retryPeriod = retryPeriod;
    }

    /**
     * 执行加锁：先以幂等写（INSERT OR IGNORE）抢 SQLite 独占写锁，再确认行存在。
     *
     * <p>sql1/sql2 为 DBSemaphore.obtainLock 传入的 expandedSQL / expandedInsertSQL
     * （已替换 {0}/{1} 为 tablePrefix/schedName，与 StdRowLockSemaphore.executeSQL 同源用法）。
     *
     * @param conn     JobStoreSupport 提供的非托管事务连接（autocommit=false）
     * @param lockName 锁名（如 TRIGGER_ACCESS）
     * @param sql1     只读确认 SQL（SELECT 行存在性，仅读不破坏写锁）
     * @param sql2     幂等写 SQL（INSERT OR IGNORE，本实现的首条执行语句）
     */
    @Override
    protected void executeSQL(Connection conn, String lockName, String sql1, String sql2)
            throws LockException {
        // 首条语句必须为写语句：INSERT OR IGNORE 在 WAL 下获得 RESERVED 写锁（跨 JVM 单写者互斥）。
        // 行已存在时仍计为写语句（已实证 SQLITE_BUSY），绝不使用 SELECT ... FOR UPDATE。
        SQLException lastError = null;
        for (int i = 0; i < maxRetry; i++) {
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql2)) {
                    ps.setString(1, lockName);
                    ps.execute();
                }
                // 确认行存在（仅读，不破坏写锁）
                try (PreparedStatement ps = conn.prepareStatement(sql1)) {
                    ps.setString(1, lockName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new LockException(
                                    "Inserted lock row for lock: '" + lockName + "' but could not read it back");
                        }
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("SQLite 集群锁已获取: lock='{}' 线程={}（INSERT OR IGNORE 抢占写锁）",
                            lockName, Thread.currentThread().getName());
                }
                return;
            } catch (SQLException e) {
                lastError = e;
                if (log.isDebugEnabled()) {
                    log.debug("SQLite 集群锁获取失败(第 {}/{} 次): lock='{}' 错误={}",
                            i + 1, maxRetry, lockName, e.getMessage());
                }
                try {
                    Thread.sleep(retryPeriod);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LockException("Interrupted while waiting for lock '" + lockName + "'", ie);
                }
            }
        }
        throw new LockException("Failed to acquire SQLite cluster lock '" + lockName + "' after "
                + maxRetry + " attempts: " + lastError, lastError);
    }
}
