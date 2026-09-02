package com.nexusai.application.agent.mcp;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.nexusai.application.agent.mcp.config.McpProperties;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 · .mcp.json 导入 → DB 写回（Q-09=C：DB 唯一运行时源）。
 *
 * <p><b>WHY (意图验证)</b>: .mcp.json 仅导入入口，导入后 DB 才是运行时源。导入管线
 * 必须：parse（T2）→ scope 合并（T1，同名 local 胜）→ 去重（T3）→ 策略过滤（T4，
 * denied 不入库）→ 审批初始态（T7）→ 按 name upsert（重复导入覆盖不新增）。任一环节
 * 缺失都会让 DB 与 CC 语义不一致。
 *
 * <p>MybatisFlexBootstrap 单例 + 临时 SQLite + Flyway V1..V10，须独立运行。
 */
class McpServerImportTest {

    @TempDir
    static Path tempDir;

    private static McpServerMapper mapper;
    private static McpServerService service;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // MyBatis-Flex mapper 代理全局缓存：3 个 mcp DB 测试共用同一稳定 DB 路径（target/flex-dbtest/flex.db）
        // 保证全局 mapper 只绑一次（9 环境性错误清零，2026-08-11）。
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        String dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(dbUrl);

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, McpServerMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(McpServerMapper.class);

        service = new McpServerService();
        ReflectionTestUtils.setField(service, "mcpServerMapper", mapper);
        ReflectionTestUtils.setField(service, "mcpTransportFactory",
            Mockito.mock(McpTransportFactory.class));
        ReflectionTestUtils.setField(service, "mcpToolPool", Mockito.mock(McpToolPool.class));
        // [impl-I-3 rework #2] start() 内 channelNotificationGate.setAllowedChannelsSupplier（基线修复补 mock）
        ReflectionTestUtils.setField(service, "channelNotificationGate",
            Mockito.mock(ChannelNotificationGate.class));
    }

    @BeforeEach
    void clean() {
        for (McpServerRecord r : mapper.selectAll()) {
            mapper.deleteById(r.getId());
        }
    }

    private Path write(String name, String json) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, json);
        return p;
    }

    private static McpProperties.Policy allowAll() {
        return null; // null = 全放行
    }

    private McpServerService.McpServerImportResult importFile(Path file, McpProperties.Policy policy)
            throws Exception {
        return service.importFromMcpJson(
            Map.of("project", file.toString()), policy,
            new McpServerUtils.ProjectSettings(List.of(), List.of(), false),
            false, false, true);
    }

    @Test
    @DisplayName("3 server .mcp.json 导入 → DB 3 行（approval 初始 pending）")
    void importThreeServers() throws Exception {
        Path f = write("three.mcp.json", """
            {"mcpServers": {
              "a": {"command": "python", "args": ["a.py"]},
              "b": {"command": "python", "args": ["b.py"]},
              "c": {"command": "python", "args": ["c.py"]}
            }}
            """);
        McpServerService.McpServerImportResult r = importFile(f, allowAll());
        assertThat(r.imported()).isEqualTo(3);
        assertThat(mapper.selectAll()).hasSize(3);
        assertThat(mapper.selectOneByName("a").getType()).isEqualTo("stdio");
        assertThat(mapper.selectOneByName("a").getApprovalStatus())
            .as("无 project 审批字段 → 默认 pending（V10 default / utils.ts:405）")
            .isEqualTo("pending");
        assertThat(mapper.selectOneByName("a").getScope())
            .as("project 源导入 → DB scope 列必须为 project（V59，upsertServer 带来源 scope）")
            .isEqualTo("project");
    }

    @Test
    @DisplayName("重复导入同 name → 覆盖更新不新增（name UNIQUE upsert）")
    void reimportOverwritesByName() throws Exception {
        Path f = write("reimport.mcp.json", """
            {"mcpServers": {"dup": {"command": "python", "args": ["v1.py"]}}}
            """);
        service.importFromMcpJson(Map.of("project", f.toString()));
        Path f2 = write("reimport2.mcp.json", """
            {"mcpServers": {"dup": {"command": "python", "args": ["v2.py"]}}}
            """);
        service.importFromMcpJson(Map.of("project", f2.toString()));

        assertThat(mapper.selectAll()).hasSize(1);
        assertThat(mapper.selectOneByName("dup").getArgs()).contains("v2.py");
    }

    @Test
    @DisplayName("scope 同名冲突 → local 版本胜出（T1 优先级）")
    void localWinsOnNameConflict() throws Exception {
        Path project = write("proj.mcp.json", """
            {"mcpServers": {"server1": {"command": "python", "args": ["project.py"]}}}
            """);
        Path local = write("local.mcp.json", """
            {"mcpServers": {"server1": {"command": "python", "args": ["local.py"]}}}
            """);
        service.importFromMcpJson(Map.of("project", project.toString(), "local", local.toString()));

        assertThat(mapper.selectAll()).hasSize(1);
        assertThat(mapper.selectOneByName("server1").getArgs())
            .as("local 优先级最高（CC config.ts:1231-1238），同名必须 local 版本")
            .contains("local.py");
        assertThat(mapper.selectOneByName("server1").getScope())
            .as("local 源胜出 → DB scope 列必须为 local（V59，胜出者来源 scope 落库）")
            .isEqualTo("local");
    }

    @Test
    @DisplayName("denied 策略命中 → 不入库（T4 安全边界）")
    void deniedServerNotImported() throws Exception {
        Path f = write("denied.mcp.json", """
            {"mcpServers": {
              "good": {"command": "python", "args": ["g.py"]},
              "blocked": {"command": "python", "args": ["b.py"]}
            }}
            """);
        McpProperties.Policy policy = new McpProperties.Policy(
            null, List.of(McpProperties.Entry.byName("blocked")), false);
        McpServerService.McpServerImportResult r = importFile(f, policy);
        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.blocked()).containsExactly("blocked");
        assertThat(mapper.selectOneByName("blocked"))
            .as("denylist 命中必须被策略拦截，不入库")
            .isNull();
        assertThat(mapper.selectOneByName("good")).isNotNull();
    }

    @Test
    @DisplayName("http 远程 server → type=http，url 存入 command 列（Java 现有契约）")
    void importHttpServer() throws Exception {
        Path f = write("http.mcp.json", """
            {"mcpServers": {"remote": {"type": "http", "url": "https://example.com/mcp"}}}
            """);
        McpServerService.McpServerImportResult r = importFile(f, allowAll());
        assertThat(r.imported()).isEqualTo(1);
        McpServerRecord row = mapper.selectOneByName("remote");
        assertThat(row.getType()).isEqualTo("http");
        assertThat(row.getCommand()).isEqualTo("https://example.com/mcp");
    }
}
