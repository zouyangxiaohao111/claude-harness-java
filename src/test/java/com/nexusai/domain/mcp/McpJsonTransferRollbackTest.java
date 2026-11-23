package com.nexusai.domain.mcp;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 导入失败必须整体回滚（[2026-11-23]）：真 SQLite + 真 Mapper，第二个 insert 抛错后
 * 第一个已写行也必须消失（补偿回滚；生产另有 @Transactional 兜底）。
 */
class McpJsonTransferRollbackTest {

    private static McpServerMapper realMapper;

    @TempDir
    static Path temp;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path db = temp.resolve("mcp-transfer-" + UUID.randomUUID() + ".db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE mcp_servers (
                      id TEXT PRIMARY KEY,
                      name TEXT UNIQUE NOT NULL,
                      command TEXT NOT NULL,
                      args TEXT,
                      env TEXT,
                      last_error TEXT,
                      pid INTEGER,
                      enabled INTEGER NOT NULL DEFAULT 1,
                      created_at TEXT NOT NULL DEFAULT (datetime('now')),
                      type TEXT DEFAULT 'stdio',
                      approval_status TEXT NOT NULL DEFAULT 'pending',
                      scope TEXT DEFAULT 'project',
                      auto_start INTEGER NOT NULL DEFAULT 1,
                      config_json TEXT )
                    """);
        }
        MybatisFlexDbTestSupport.resetAndStart(ds, McpServerMapper.class);
        realMapper = MybatisFlexBootstrap.getInstance().getMapper(McpServerMapper.class);
    }

    @AfterAll
    static void cleanUp() {
        if (realMapper != null) {
            for (McpServerRecord r : realMapper.selectAll()) {
                realMapper.deleteById(r.getId());
            }
        }
    }

    @Test
    @DisplayName("第二个 insert 失败 → 第一个已写行被回滚，DB 只剩导入前 seed")
    void failedImportRollsBackPartialWrites() throws Exception {
        McpServerRecord seed = new McpServerRecord();
        seed.setId("mcp-seed");
        seed.setName("seed");
        seed.setType("http");
        seed.setCommand("https://seed/mcp");
        seed.setEnabled(true);
        seed.setAutoStart(true);
        seed.setScope("user");
        seed.setApprovalStatus("approved");
        seed.setCreatedAt("2026-11-23T00:00:00+08:00");
        realMapper.insert(seed);

        AtomicInteger inserts = new AtomicInteger();
        McpServerMapper failing = (McpServerMapper) Proxy.newProxyInstance(
                McpJsonTransferRollbackTest.class.getClassLoader(),
                new Class<?>[]{McpServerMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("insert") && inserts.incrementAndGet() == 2) {
                        throw new RuntimeException("boom");
                    }
                    try {
                        return method.invoke(realMapper, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

        McpJsonTransferService service = new McpJsonTransferService(failing,
                org.mockito.Mockito.mock(com.nexusai.domain.mcp_oauth.McpOAuthTokenService.class));
        service.setBackupDirForTests(temp.resolve("backups"));
        String payload = """
                {"mcpServers":{
                  "a":{"type":"http","url":"https://a/mcp"},
                  "b":{"type":"http","url":"https://b/mcp"}
                }}
                """;

        assertThatThrownBy(() -> service.importJson(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(payload)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        List<McpServerRecord> rows = realMapper.selectAll();
        assertThat(rows).extracting(McpServerRecord::getName).containsExactly("seed");
        assertThat(Files.list(temp.resolve("backups")).count()).isGreaterThanOrEqualTo(1);
        realMapper.deleteById(seed.getId());
    }
}
