package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.permission.hook.StopHookPipeline;

import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 轮末 computer-use 清理（{@code StopHookPipeline.cleanupComputerUseAfterTurn}）单测。
 *
 * <p><b>WHY（规则 9）</b>：轮末清理有三条硬约束，任何一条破了都会造成"桌面被永久锁死"
 * 或"子代理把主线程的状态搅乱"：
 * <ol>
 *   <li><b>主线程才释放</b>：子代理释放会让主线程误判锁状态（CCB
 *       {@code src/query/stopHooks.ts:169-180} 的注释明确指出）。</li>
 *   <li><b>真释放</b>：不是"打个日志说跳过"，而是真的把锁行清掉。</li>
 *   <li><b>失败静默</b>：轮末清理不是关键路径，抛异常会打断整轮（CCB 空 catch）。</li>
 * </ol>
 */
class ComputerUseCleanupTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource ds;
    private ComputerUseLockService lockService;

    @BeforeEach
    void setUp() throws Exception {
        ds = ComputerUseTestSqlite.create(tempDir, "cu-cleanup.db");
        ComputerUseTestSqlite.createLockTable(ds);
        lockService = new ComputerUseLockService(ds, true);
        ComputerUseLockService.install(lockService);
    }

    @AfterEach
    void tearDown() {
        ComputerUseLockService.install(null);
    }

    @Test
    @DisplayName("主线程轮末 ⇒ 真释放会话锁（锁行被清空）")
    void mainThreadReleasesLock() {
        assertThat(lockService.tryAcquire("sess-1")).isTrue();
        assertThat(readHolder()).isEqualTo("sess-1");

        boolean gate = StopHookPipeline.cleanupComputerUseAfterTurn(null, "sess-1");

        assertThat(gate).isTrue();
        assertThat(readHolder()).isNull();
    }

    @Test
    @DisplayName("子代理 ⇒ 纯跳过（不释放主线程的锁）")
    void subagentSkipsRelease() {
        assertThat(lockService.tryAcquire("sess-1")).isTrue();

        boolean gate = StopHookPipeline.cleanupComputerUseAfterTurn("sub-1", "sess-1");

        assertThat(gate).isFalse();
        assertThat(readHolder()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("失败静默：未装配锁服务 / 释放抛异常都不影响轮末流程")
    void failuresAreSilent() throws Exception {
        ComputerUseLockService.install(null);
        assertThatCode(() -> StopHookPipeline.cleanupComputerUseAfterTurn(null, "sess-1"))
                .doesNotThrowAnyException();

        // 装一个指向已删库的锁服务 ⇒ 释放时 DB 异常 ⇒ 仍不抛
        ComputerUseLockService broken = new ComputerUseLockService(
                ComputerUseTestSqlite.create(tempDir, "cu-broken.db"), true);
        ComputerUseLockService.install(broken);
        assertThatCode(() -> StopHookPipeline.cleanupComputerUseAfterTurn(null, "sess-1"))
                .doesNotThrowAnyException();
        assertThat(StopHookPipeline.cleanupComputerUseAfterTurn(null, "sess-1")).isTrue();
    }

    @Test
    @DisplayName("旧签名（无 sessionId）保持可用：只做门控判断，不释放任何锁")
    void legacyOverloadStillWorks() {
        assertThat(lockService.tryAcquire("sess-1")).isTrue();

        assertThat(StopHookPipeline.cleanupComputerUseAfterTurn("sub-1")).isFalse();
        assertThat(StopHookPipeline.cleanupComputerUseAfterTurn(null)).isTrue();

        // 无 sessionId ⇒ 不释放（锁仍在）
        assertThat(readHolder()).isEqualTo("sess-1");
    }

    private String readHolder() {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_id FROM computer_use_lock WHERE id = 1")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
