package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * computer-use 跨会话互斥锁（DB 单行表 + 进程内 {@link ReentrantLock}）。
 *
 * <h2>对齐 CCB 2.8.4 {@code src/utils/computerUse/computerUseLock.ts}</h2>
 * <p>CCB 的锁是**进程级模块变量**（模块级 {@code let} + {@code ~/.claude/computer-use.lock}
 * 文件，{@code O_EXCL} 原子创建）：
 * <ul>
 *   <li>持有者记 {@code sessionId + pid + acquiredAt}（CCB {@code computerUseLock.ts:16-20}）</li>
 *   <li>{@code checkComputerUseLock}（:110-122）只查不拿，且做<b>死 PID 陈旧锁回收</b></li>
 *   <li>{@code tryAcquireComputerUseLock}（:148-195）拿锁；已被自己持有 ⇒ 重入（{@code fresh:false}）</li>
 *   <li>{@code releaseComputerUseLock}（:202-215）<b>只有持有者能释放</b>，幂等</li>
 *   <li>PID 探活 = {@code process.kill(pid, 0)}（:65-71）</li>
 * </ul>
 *
 * <h2>⭐ 本仓增强：DB 表 ⇒ <b>跨进程</b>互斥</h2>
 * <p>CCB 的锁只在本进程内生效（文件锁在进程内 + 同一台机器上其他 CC 进程其实<b>也</b>会
 * 争同一个文件，但 CCB 的桌面能力本身是 per-session 的）。本仓 Web 端<b>一个后端进程同时
 * 服务多个会话</b>，且桌面是<b>机器唯一资源</b> ⇒ 锁落 <b>DB 单行表</b>
 * （{@code computer_use_lock}，V251 建表）⇒ 同机多进程也互斥。
 * <b>这是有意增强，不是缺陷</b>（对齐报告「差异清单」会显式标注）。
 *
 * <h2>SQLite 纪律</h2>
 * <p>⛔ SQLite <b>没有</b> {@code SELECT ... FOR UPDATE} ⇒ 抢锁一律走
 * <b>条件 UPDATE CAS + affected rows 判成败</b>。
 *
 * <h2>进程内锁</h2>
 * <p>额外加一把 {@link ReentrantLock}（用法照抄
 * {@code application/agent/cli/PidLock.java:43} 的 {@code inProcessLocks.computeIfAbsent(...)
 * .lock()}）：把「读行 → 判陈旧 → CAS」这段复合操作在<b>本进程内</b>串行化，
 * 避免同进程多线程各自读到同一旧行后同时发起 CAS。跨进程安全由 DB CAS 保证。
 */
public final class ComputerUseLockService {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseLockService.class);

    /** 单行表的主键（表内恒有 id=1 这一行）。 */
    public static final int LOCK_ROW_ID = 1;

    /** 进程内锁的等待上限（毫秒）· 超过即判"拿不到锁"，不让权限路径无限阻塞。 */
    private static final long IN_PROCESS_LOCK_TIMEOUT_MS = 2000L;

    /**
     * Spring 装配的实例（供静态的
     * {@code StopHookPipeline.cleanupComputerUseAfterTurn} 取用）· 照抄本仓
     * {@code TaskSystemConfig.installAgentSwarmsSettingsSource} 的静态接线先例。
     */
    private static volatile ComputerUseLockService instance;

    private final DataSource dataSource;
    private final boolean enabled;

    /** 进程内互斥（单锁 ⇒ 退化版 PidLock 的 per-key 锁表）。 */
    private final ReentrantLock inProcessLock = new ReentrantLock();

    /**
     * @param dataSource 数据源（不可为 null）
     * @param enabled    总开关（false ⇒ 全部操作直接返回"未启用"，不碰 DB）
     */
    public ComputerUseLockService(DataSource dataSource, boolean enabled) {
        if (dataSource == null) {
            throw new IllegalArgumentException("ComputerUseLockService 需要 DataSource");
        }
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    /**
     * 静态接线（Spring 侧调用）。
     *
     * @param service 服务实例（可 null = 卸载）
     */
    public static void install(ComputerUseLockService service) {
        instance = service;
    }

    /**
     * 取当前装配的实例（未装配 ⇒ null）。
     *
     * @return 服务实例（可 null）
     */
    public static ComputerUseLockService current() {
        return instance;
    }

    /**
     * 锁状态。
     */
    public enum LockStatus {
        /** 空闲（无持有者）。 */
        FREE,
        /** 本会话已持有（重入）。 */
        HELD_BY_SELF,
        /** 被其它会话持有（阻塞）。 */
        BLOCKED
    }

    /**
     * 锁快照。
     *
     * @param status         锁状态
     * @param holderSessionId 持有者会话 id（无 ⇒ null）
     * @param holderPid      持有者进程 pid（无 ⇒ null）
     */
    public record LockState(LockStatus status, String holderSessionId, Long holderPid) {

        /**
         * @return 是否空闲
         */
        public boolean free() {
            return status == LockStatus.FREE;
        }

        /**
         * @return 是否被其它会话阻塞
         */
        public boolean blocked() {
            return status == LockStatus.BLOCKED;
        }
    }

    /**
     * 查锁状态（<b>只查不拿</b>）· 对齐 CCB {@code checkComputerUseLock}
     * （{@code computerUseLock.ts:110-122}）。
     *
     * <p>死 PID 的陈旧锁在这里被<b>回收</b>（CCB 同款：unlink 后视为 free）。
     *
     * @param sessionId 查询方会话 id
     * @return 锁状态（DB 异常 ⇒ 保守返回 {@link LockStatus#BLOCKED}，避免误放行并发控制）
     */
    public LockState check(String sessionId) {
        if (!enabled) {
            return new LockState(LockStatus.FREE, null, null);
        }
        try {
            Row row = readRow();
            if (row == null || row.sessionId() == null) {
                return new LockState(LockStatus.FREE, null, null);
            }
            if (sessionId != null && sessionId.equals(row.sessionId())) {
                return new LockState(LockStatus.HELD_BY_SELF, row.sessionId(), row.pid());
            }
            if (isProcessAlive(row.pid())) {
                return new LockState(LockStatus.BLOCKED, row.sessionId(), row.pid());
            }
            // 陈旧锁（持有进程已死）⇒ 回收（CCB checkComputerUseLock 同款：unlink 后视为 free）
            if (recoverStale(row, null)) {
                if (log.isInfoEnabled()) {
                    log.info("[computer-use] 回收陈旧 computer-use 锁: 原持有 session={} pid={}（进程已不存在）",
                            row.sessionId(), row.pid());
                }
                return new LockState(LockStatus.FREE, null, null);
            }
            // 回收失败 ⇒ 别的进程刚抢到 / 状态在变 ⇒ 保守判阻塞
            return new LockState(LockStatus.BLOCKED, row.sessionId(), row.pid());
        } catch (SQLException e) {
            log.warn("[computer-use] 读 computer-use 锁失败（保守判阻塞）: {}", e.toString());
            return new LockState(LockStatus.BLOCKED, null, null);
        }
    }

    /**
     * 抢锁（CAS）· 对齐 CCB {@code tryAcquireComputerUseLock}
     * （{@code computerUseLock.ts:148-195}）。
     *
     * <p>语义：
     * <ul>
     *   <li>空闲 ⇒ CAS 写入 ⇒ <b>取得</b></li>
     *   <li>本会话已持有 ⇒ <b>重入</b>（不重复写，返回 true）</li>
     *   <li>被<b>存活</b>的其它会话持有 ⇒ <b>阻塞</b>（返回 false）</li>
     *   <li>持有进程已死 ⇒ 先回收陈旧锁再抢</li>
     * </ul>
     *
     * @param sessionId 抢锁方会话 id（空 ⇒ 直接失败）
     * @return true = 取得（含重入）；false = 被阻塞 / DB 异常
     */
    public boolean tryAcquire(String sessionId) {
        if (!enabled) {
            return true;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        boolean locked = false;
        try {
            locked = inProcessLock.tryLock(IN_PROCESS_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[computer-use] 抢 computer-use 锁被中断: {}", e.toString());
            return false;
        }
        if (!locked) {
            log.warn("[computer-use] 抢 computer-use 锁超时（进程内锁等待 {}ms）: session={}",
                    IN_PROCESS_LOCK_TIMEOUT_MS, sessionId);
            return false;
        }
        try {
            return doAcquire(sessionId);
        } catch (SQLException e) {
            log.warn("[computer-use] 抢 computer-use 锁失败（DB 异常）: session={} err={}",
                    sessionId, e.toString());
            return false;
        } finally {
            inProcessLock.unlock();
        }
    }

    /**
     * 抢锁内部实现（进程内锁已持有）。
     *
     * @param sessionId 抢锁方会话 id
     * @return true = 取得（含重入）
     * @throws SQLException DB 异常
     */
    private boolean doAcquire(String sessionId) throws SQLException {
        Row row = readRow();
        if (row == null) {
            // 表内没有 id=1 这一行（播种未生效）⇒ 直接播种并抢占
            return insertAndAcquire(sessionId);
        }
        if (row.sessionId() == null) {
            return casAcquireFree(sessionId);
        }
        if (sessionId.equals(row.sessionId())) {
            // 重入（CCB: REENTRANT）
            return true;
        }
        if (isProcessAlive(row.pid())) {
            return false;
        }
        // 陈旧锁 ⇒ CAS 回收（条件 = 观察到的旧值）并直接接管
        return recoverStale(row, sessionId);
    }

    /**
     * 空闲态 CAS 抢锁：{@code WHERE id=1 AND (session_id IS NULL OR session_id = ?)}。
     *
     * @param sessionId 抢锁方会话 id
     * @return true = 取得
     * @throws SQLException DB 异常
     */
    private boolean casAcquireFree(String sessionId) throws SQLException {
        long now = System.currentTimeMillis();
        long pid = ProcessHandle.current().pid();
        String sql = "UPDATE computer_use_lock SET session_id = ?, pid = ?, acquired_at = ? "
                + "WHERE id = ? AND (session_id IS NULL OR session_id = ?)";
        int affected = executeUpdate(sql, sessionId, pid, now, LOCK_ROW_ID, sessionId);
        if (affected == 1) {
            if (log.isInfoEnabled()) {
                log.info("[computer-use] 已取得 computer-use 锁: session={} pid={}", sessionId, pid);
            }
            return true;
        }
        return false;
    }

    /**
     * 播种并抢占（表内无 id=1 行时）。
     *
     * @param sessionId 抢锁方会话 id
     * @return true = 取得
     * @throws SQLException DB 异常
     */
    private boolean insertAndAcquire(String sessionId) throws SQLException {
        long now = System.currentTimeMillis();
        long pid = ProcessHandle.current().pid();
        String sql = "INSERT OR IGNORE INTO computer_use_lock (id, session_id, pid, acquired_at) "
                + "VALUES (?, ?, ?, ?)";
        int affected = executeUpdate(sql, LOCK_ROW_ID, sessionId, pid, now);
        return affected == 1;
    }

    /**
     * 陈旧锁回收 CAS：条件 = 观察到的旧 {@code (session_id, pid)}。
     *
     * @param row           观察到的旧行
     * @param newHolderSessionId 接管方会话 id（null = 只清空不接管，对齐 CCB
     *                           {@code checkComputerUseLock} 的 unlink 语义）
     * @return true = 回收成功
     * @throws SQLException DB 异常
     */
    private boolean recoverStale(Row row, String newHolderSessionId) throws SQLException {
        long now = System.currentTimeMillis();
        long pid = ProcessHandle.current().pid();
        String sql;
        Object[] params;
        if (newHolderSessionId == null) {
            sql = "UPDATE computer_use_lock SET session_id = NULL, pid = NULL, acquired_at = NULL "
                    + "WHERE id = ? AND session_id = ? AND pid = ?";
            params = new Object[]{LOCK_ROW_ID, row.sessionId(), row.pid()};
        } else {
            sql = "UPDATE computer_use_lock SET session_id = ?, pid = ?, acquired_at = ? "
                    + "WHERE id = ? AND session_id = ? AND pid = ?";
            params = new Object[]{newHolderSessionId, pid, now, LOCK_ROW_ID, row.sessionId(), row.pid()};
        }
        int affected = executeUpdate(sql, params);
        if (affected == 1) {
            if (log.isInfoEnabled()) {
                log.info("[computer-use] 已回收陈旧 computer-use 锁: 原持有 session={} pid={} → 新持有={}",
                        row.sessionId(), row.pid(), newHolderSessionId);
            }
            return true;
        }
        return false;
    }

    /**
     * 释放锁（<b>只有持有者能释放</b>）· 对齐 CCB {@code releaseComputerUseLock}
     * （{@code computerUseLock.ts:202-215}）。
     *
     * <p>条件 UPDATE：{@code WHERE id=1 AND session_id = ?} ⇒ 非持有者释放时
     * affected=0 ⇒ 返回 false（幂等，不误删别人的锁）。
     *
     * @param sessionId 释放方会话 id
     * @return true = 本次确实释放了（此前确实持有）
     */
    public boolean release(String sessionId) {
        if (!enabled) {
            return false;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        boolean locked = false;
        try {
            locked = inProcessLock.tryLock(IN_PROCESS_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!locked) {
            return false;
        }
        try {
            String sql = "UPDATE computer_use_lock SET session_id = NULL, pid = NULL, acquired_at = NULL "
                    + "WHERE id = ? AND session_id = ?";
            int affected = executeUpdate(sql, LOCK_ROW_ID, sessionId);
            if (affected == 1 && log.isInfoEnabled()) {
                log.info("[computer-use] 已释放 computer-use 锁: session={}", sessionId);
            }
            return affected == 1;
        } catch (SQLException e) {
            log.warn("[computer-use] 释放 computer-use 锁失败: session={} err={}", sessionId, e.toString());
            return false;
        } finally {
            inProcessLock.unlock();
        }
    }

    /**
     * 读 id=1 行。
     *
     * @return 行（不存在 ⇒ null）
     * @throws SQLException DB 异常
     */
    private Row readRow() throws SQLException {
        String sql = "SELECT session_id, pid, acquired_at FROM computer_use_lock WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, LOCK_ROW_ID);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String sessionId = rs.getString(1);
                long pid = rs.getLong(2);
                Long pidBox = rs.wasNull() ? null : pid;
                long acquiredAt = rs.getLong(3);
                Long acquiredBox = rs.wasNull() ? null : acquiredAt;
                return new Row(sessionId, pidBox, acquiredBox);
            }
        }
    }

    /**
     * 执行一条 UPDATE 并返回 affected rows（CAS 判成败的依据）。
     *
     * @param sql    SQL
     * @param params 参数
     * @return affected rows
     * @throws SQLException DB 异常
     */
    private int executeUpdate(String sql, Object... params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    /**
     * PID 存活探活 · 照抄 {@code application/agent/cli/PidLock.java:84-94}
     * （{@code ProcessHandle.of(pid).isPresent()}）。
     *
     * @param pid 进程 pid（可 null）
     * @return true = 进程存活
     */
    private static boolean isProcessAlive(Long pid) {
        if (pid == null || pid <= 0) {
            return false;
        }
        try {
            return ProcessHandle.of(pid).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 陈旧锁回收时的新持有者会话 id（当前调用方）。
     *
     * @param row 旧行
     * @return 新持有者会话 id
     */
    private static String sessionId(Row row) {
        return row.sessionId() == null ? null : row.sessionId();
    }

    /**
     * 锁表行。
     *
     * @param sessionId 持有者会话 id
     * @param pid        持有者 pid
     * @param acquiredAt 取得时刻
     */
    private record Row(String sessionId, Long pid, Long acquiredAt) {
    }
}
