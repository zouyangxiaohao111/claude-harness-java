package com.nexusai.application.agent.computeruse;

import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 单测用的临时 SQLite 库助手（<b>只碰临时文件，绝不碰用户真库</b>）。
 *
 * <p><b>WHY 需要真的 sqlite</b>：跨会话锁的判成败依据是<b>条件 UPDATE 的 affected rows</b>
 * —— 这个语义只有真 SQLite 才能验（假实现验不了 CAS）。故单测起一个临时文件库。
 */
final class ComputerUseTestSqlite {

    private ComputerUseTestSqlite() {
        throw new AssertionError("utility class");
    }

    /**
     * 建一个临时 sqlite 文件库并返回其 DataSource。
     *
     * @param dir  临时目录
     * @param name 文件名
     * @return DataSource
     * @throws IOException 建文件失败
     */
    static SQLiteDataSource create(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file.toString().replace('\\', '/'));
        return ds;
    }

    /**
     * 建 {@code computer_use_lock} 表（与 V251 迁移同结构）。
     *
     * @param ds 数据源
     * @throws SQLException 建表失败
     */
    static void createLockTable(SQLiteDataSource ds) throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS computer_use_lock ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                    + "session_id TEXT, pid INTEGER, acquired_at INTEGER)");
            st.execute("INSERT OR IGNORE INTO computer_use_lock (id, session_id, pid, acquired_at) "
                    + "VALUES (1, NULL, NULL, NULL)");
        }
    }
}
