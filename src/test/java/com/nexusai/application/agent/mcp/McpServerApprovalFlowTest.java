package com.nexusai.application.agent.mcp;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.mcp.config.McpConfigAddValidator;
import com.nexusai.application.agent.mcp.config.McpConfigFileWriter;
import com.nexusai.application.agent.mcp.config.McpJsonConfigParser;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.model.mcp.McpServer;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.mcp.dto.McpStatus;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T7 · 审批状态机（Q-25: pending→确认→启用/拒绝）。
 *
 * <p><b>WHY (意图验证)</b>: CC getProjectMcpServerStatus（utils.ts:351-406）对 project
 * .mcp.json server 判定 approved/rejected/pending；pending 未确认不可 start。Java 导入
 * 后初始态是 pending（V10 default），必须由 approve/reject 端点流转，start 门控拒绝
 * pending/disabled。若门控缺失，导入的 server 未经确认就可用（安全边界破坏）。
 *
 * <p>MybatisFlexBootstrap 单例 + 临时 SQLite + Flyway V1..V10，须独立运行。
 */
class McpServerApprovalFlowTest {

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
        McpToolPool pool = Mockito.mock(McpToolPool.class);
        Mockito.when(pool.assembleToolPool(Mockito.anyString(), Mockito.any()))
            .thenReturn(List.of());
        Mockito.when(pool.fetchMcpSkills(Mockito.anyString())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "mcpToolPool", pool);
        // [impl-I-3 rework #2] start() 内 channelNotificationGate.setAllowedChannelsSupplier（基线修复补 mock）
        ReflectionTestUtils.setField(service, "channelNotificationGate",
            Mockito.mock(ChannelNotificationGate.class));
        // [S07] start() 内 channelSessionAllowlist.currentRequestSupplier()（真实会话态注入，需非 null）
        ReflectionTestUtils.setField(service, "channelSessionAllowlist",
            new ChannelSessionAllowlist());
        // [mcp-add] create 经校验链 + 配置源写回（AC-1 双写）：project 写 .mcp.json 走 cwd override
        CwdResolution.setCurrentOverride(tempDir.toString());
        ReflectionTestUtils.setField(service, "addValidator", new McpConfigAddValidator(null, null));
        ReflectionTestUtils.setField(service, "configFileWriter", new McpConfigFileWriter(null, null));
    }

    @AfterAll
    static void tearDownOverride() {
        CwdResolution.clearCurrentOverride();
    }

    @BeforeEach
    void clean() {
        for (McpServerRecord r : mapper.selectAll()) {
            mapper.deleteById(r.getId());
        }
    }

    /** 造一个 pending 审批态的 server（导入初始态 = V10 default）。 */
    private String insertPending(String name) {
        McpServer s = new McpServer();
        s.setId("mcp-pend-" + name);
        s.setName(name);
        s.setCommand("python");
        s.setArgs(null);
        s.setEnv(null);
        s.setStatus("stopped");
        s.setEnabled(Boolean.FALSE);
        s.setType("stdio");
        s.setApprovalStatus("pending");
        s.setCreatedAt(java.time.OffsetDateTime.now()
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        mapper.insert(McpServerRecord.fromDomain(s));
        return s.getId();
    }

    @Test
    @DisplayName("pending 审批未过 → start 拒绝（ConflictException 409）")
    void pendingCannotStart() {
        String id = insertPending("pend-srv");
        assertThatThrownBy(() -> service.start(id))
            .as("pending 未确认不可 start（对齐 CC utils.ts:405 默认 pending 分支）")
            .isInstanceOf(ConflictException.class);
        McpServerDto after = service.getById(id);
        assertThat(after.approvalStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("approve → approved + enabled=true → start 成功")
    void approveEnablesAndStarts() {
        String id = insertPending("approve-srv");
        McpServerDto approved = service.approve(id);
        assertThat(approved.approvalStatus()).isEqualTo("approved");
        assertThat(approved.enabled()).isTrue();

        McpServerDto started = service.start(id);
        assertThat(started.status()).isEqualTo(McpStatus.running);
    }

    @Test
    @DisplayName("reject → rejected + enabled=false → start 拒绝")
    void rejectDisablesAndBlocksStart() {
        String id = insertPending("reject-srv");
        McpServerDto rejected = service.reject(id);
        assertThat(rejected.approvalStatus()).isEqualTo("rejected");
        assertThat(rejected.enabled()).isFalse();

        assertThatThrownBy(() -> service.start(id))
            .as("rejected 且 enabled=false 不可 start")
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("REST create → approved（用户显式意图，非 .mcp.json 导入）")
    void restCreateIsApproved() {
        McpServerDto created = service.create(new McpCreateRequest(
            "manual-srv", "python", List.of(), Map.of(), Boolean.TRUE, "stdio"));
        assertThat(created.approvalStatus()).isEqualTo("approved");
        assertThat(created.type()).isEqualTo("stdio");
        assertThat(created.enabled()).isTrue();
    }

    @Test
    @DisplayName("getProjectMcpServerStatus: disabledMcpjsonServers 命中 → rejected；enableAll → approved；默认 pending")
    void projectStatusRouting() {
        // enabledMcpjsonServers 命中 → approved
        McpServerUtils.ProjectSettings enabled = new McpServerUtils.ProjectSettings(
            List.of("good"), List.of(), false);
        assertThat(McpServerUtils.getProjectMcpServerStatus(
            "good", enabled, false, false, true)).isEqualTo("approved");

        // disabledMcpjsonServers 命中 → rejected
        McpServerUtils.ProjectSettings disabled = new McpServerUtils.ProjectSettings(
            List.of(), List.of("bad"), false);
        assertThat(McpServerUtils.getProjectMcpServerStatus(
            "bad", disabled, false, false, true)).isEqualTo("rejected");

        // 无命中 → pending
        McpServerUtils.ProjectSettings none = new McpServerUtils.ProjectSettings(
            List.of(), List.of(), false);
        assertThat(McpServerUtils.getProjectMcpServerStatus(
            "new", none, false, false, true)).isEqualTo("pending");
    }

    @Test
    @DisplayName("解析器产出 env 展开后的 server（T5 导入前序）")
    void parserProducesExpandedServer() {
        Map<String, Object> config = Map.of("mcpServers", Map.of("s",
            Map.of("command", "${TOKEN}/bin")));
        McpJsonConfigParser.ParseResult r = McpJsonConfigParser.parseMcpConfig(
            config, true, "project", new EnvExpansion(k -> "tok123"));
        assertThat(r.servers().get("s").get("command")).isEqualTo("tok123/bin");
    }
}
