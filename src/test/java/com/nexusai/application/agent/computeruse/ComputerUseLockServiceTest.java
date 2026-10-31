package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * computer-use 跨会话锁单测（真 SQLite 临时库）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：这把锁是「同一台机器上只有一个会话能控制桌面」
 * 的唯一保证。它的正确性判据是<b>条件 UPDATE 的 affected rows</b> ——
 * 单看"两个线程同时抢"很容易写出"看起来互斥、其实两边都成功"的实现。
 * <ul>
 *   <li><b>互斥</b>：会话 A 持锁时，会话 B 抢锁必须失败（且 A 的锁不被改写）。</li>
 *   <li><b>只有持有者能释放</b>：B 释放 A 的锁必须无效（否则 B 能抢走 A 的桌面）。</li>
 *   <li><b>陈旧锁回收</b>：持有者进程已死 ⇒ 锁可被回收（否则一次崩溃就永久锁死桌面）。</li>
 * </ul>
 */
class ComputerUseLockServiceTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource ds;
    private ComputerUseLockService lockService;

    @BeforeEach
    void setUp() throws Exception {
        ds = ComputerUseTestSqlite.create(tempDir, "cu-lock.db");
        ComputerUseTestSqlite.createLockTable(ds);
        lockService = new ComputerUseLockService(ds, true);
    }

    @Test
    @DisplayName("首个会话抢锁成功，第二个会话被阻塞（互斥）")
    void secondSessionIsBlocked() {
        assertThat(lockService.tryAcquire("sess-A")).isTrue();

        ComputerUseLockService.LockState state = lockService.check("sess-B");
        assertThat(state.blocked()).isTrue();
        assertThat(state.holderSessionId()).isEqualTo("sess-A");

        // B 抢锁失败，且 A 的锁不被改写
        assertThat(lockService.tryAcquire("sess-B")).isFalse();
        assertThat(readHolder()).isEqualTo("sess-A");
    }

    @Test
    @DisplayName("自己重入不失败（CCB computerUseLock.ts:177 REENTRANT）")
    void reentrantAcquireSucceeds() {
        assertThat(lockService.tryAcquire("sess-A")).isTrue();
        assertThat(lockService.tryAcquire("sess-A")).isTrue();
        assertThat(lockService.check("sess-A").status())
                .isEqualTo(ComputerUseLockService.LockStatus.HELD_BY_SELF);
    }

    @Test
    @DisplayName("只有持有者能释放（非持有者释放无效，锁不被清）")
    void onlyOwnerCanRelease() {
        assertThat(lockService.tryAcquire("sess-A")).isTrue();

        assertThat(lockService.release("sess-B")).isFalse();
        assertThat(readHolder()).isEqualTo("sess-A");

        assertThat(lockService.release("sess-A")).isTrue();
        assertThat(readHolder()).isNull();
        // 幂等：再释放一次返回 false
        assertThat(lockService.release("sess-A")).isFalse();
    }

    @Test
    @DisplayName("持有进程已死 ⇒ 陈旧锁被回收（CCB computerUseLock.ts:110-122 / 184-195）")
    void staleLockIsRecovered() {
        // 手工写入一条指向"已死进程"的锁（pid = 一个几乎不可能存活的大数）
        executeUpdate("UPDATE computer_use_lock SET session_id = 'sess-dead', pid = 2147483646, "
                + "acquired_at = 1 WHERE id = 1");

        assertThat(lockService.check("sess-B").blocked()).isFalse();
        assertThat(lockService.tryAcquire("sess-B")).isTrue();
        assertThat(readHolder()).isEqualTo("sess-B");
    }

    @Test
    @DisplayName("开关关闭时不碰库（默认关 ⇒ 出包不产生任何 DB 写）")
    void disabledServiceIsNoop() {
        ComputerUseLockService disabled = new ComputerUseLockService(ds, false);
        assertThat(disabled.check("sess-A").free()).isTrue();
        assertThat(disabled.tryAcquire("sess-A")).isTrue();
        assertThat(disabled.release("sess-A")).isFalse();
        assertThat(readHolder()).isNull();
    }

    @Test
    @DisplayName("空闲态检查返回 FREE")
    void freeWhenIdle() {
        ComputerUseLockService.LockState state = lockService.check("sess-A");
        assertThat(state.free()).isTrue();
        assertThat(state.holderSessionId()).isNull();
    }

    // ── 内部辅助 ──

    private String readHolder() {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_id FROM computer_use_lock WHERE id = 1")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void executeUpdate(String sql) {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
