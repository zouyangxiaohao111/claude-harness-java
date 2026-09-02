package com.nexusai.application.agent.mcp;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.mcp.config.McpConfigAddValidator;
import com.nexusai.application.agent.mcp.config.McpConfigFileWriter;
import com.nexusai.infra.exception.ConflictException;
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
 * T6 · enabled 启停门控补行为语义。
 *
 * <p><b>WHY (意图验证)</b>: DB {@code enabled} 布尔此前无行为消费（⊕-9）。对齐 CC
 * isMcpServerDisabled（config.ts:1528-1536）+ setMcpServerEnabled（config.ts:1553-1578）：
 * disabled server 不可 start；PATCH enabled=false 对 running server 级联 stop。
 * 若门控缺失，disabled server 仍能运行（启停语义失效）。
 *
 * <p>MybatisFlexBootstrap 单例，须独立运行（不与其它 Flex 测试类混跑同一 JVM）。
 */
class McpServerEnabledGateTest {

    @TempDir
    static Path tempDir;

    private static McpServerMapper mapper;
    private static McpServerService service;
    /** 工具池 mock（assembleToolPool 调用计数用于断言「不重复 start」幂等）。 */
    private static McpToolPool pool;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // MyBatis-Flex mapper 代理全局缓存（Mappers.MAPPER_OBJECTS 静态）：同 mapper 接口绑不同 DB 的
        // 多个测试类无法共享 JVM（后绑者不生效，mapper 指向已清理的 @TempDir）。故 3 个 mcp DB 测试
        // 共用同一稳定 DB 路径（target/flex-dbtest/flex.db），全局 mapper 只绑一次；各测试 clean() 保证数据隔离。
        // （9 环境性错误清零，2026-08-11）
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
        pool = Mockito.mock(McpToolPool.class);
        Mockito.when(pool.assembleToolPool(Mockito.anyString(), Mockito.any()))
            .thenReturn(List.of());
        Mockito.when(pool.fetchMcpSkills(Mockito.anyString())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "mcpToolPool", pool);
        // [impl-I-3 rework #2] start() 内 channelNotificationGate.setAllowedChannelsSupplier
        // （I-3 增加 start() 接线，本测试未 mock → NPE；基线修复补 mock）
        ReflectionTestUtils.setField(service, "channelNotificationGate",
            Mockito.mock(ChannelNotificationGate.class));
        // [S07] start() 内 channelSessionAllowlist.currentRequestSupplier()（真实会话态注入，需非 null）
        ReflectionTestUtils.setField(service, "channelSessionAllowlist",
            new ChannelSessionAllowlist());
        // [mcp-add] create/update 经校验链 + 配置源写回（AC-1 双写）：project 写 .mcp.json 走 cwd override
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

    private McpServerDto create(String name, boolean enabled) {
        return service.create(new McpCreateRequest(
            name, "python", List.of(), Map.of(), enabled, "stdio"));
    }

    @Test
    @DisplayName("create(disabled=true) → start 抛业务异常（409）且 status 仍 stopped")
    void disabledCannotStart() {
        McpServerDto created = create("d-srv", false);
        assertThat(created.enabled()).isFalse();

        assertThatThrownBy(() -> service.start(created.id()))
            .as("disabled server 不可 start（对齐 CC isMcpServerDisabled config.ts:1534）")
            .isInstanceOf(ConflictException.class);
        assertThat(service.getById(created.id()).status()).isEqualTo(McpStatus.stopped);
    }

    @Test
    @DisplayName("enabled=true → start 成功")
    void enabledCanStart() {
        McpServerDto created = create("e-srv", true);
        McpServerDto started = service.start(created.id());
        assertThat(started.status()).isEqualTo(McpStatus.running);
    }

    @Test
    @DisplayName("PATCH enabled=false 对 running server → 级联 stop（status 变 stopped）")
    void disablingRunningServerCascadesStop() {
        McpServerDto created = create("cascade-srv", true);
        McpServerDto started = service.start(created.id());
        assertThat(started.status()).isEqualTo(McpStatus.running);

        McpServerDto updated = service.update(created.id(),
            new McpCreateRequest("cascade-srv", "python", List.of(), Map.of(), Boolean.FALSE, "stdio"));
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.status())
            .as("PATCH enabled=false 对 running → 级联 stop（对齐 CC setMcpServerEnabled 禁用）")
            .isEqualTo(McpStatus.stopped);
    }


    @Test
    @DisplayName("PATCH enabled=true（stopped）→ 自动 start → status=running（S06，对齐 CC toggleMcpServer enable）")
    void enablingStoppedServerAutoStarts() {
        McpServerDto created = create("auto-srv", false);
        assertThat(created.status()).isEqualTo(McpStatus.stopped);

        McpServerDto updated = service.update(created.id(),
            new McpCreateRequest("auto-srv", "python", List.of(), Map.of(), Boolean.TRUE, "stdio"));
        assertThat(updated.enabled()).isTrue();
        assertThat(updated.status())
            .as("PATCH enabled=true 必须自动 start（对齐 CC toggleMcpServer enable 分支："
                + "setMcpServerEnabled(true) 落盘 → pending → 自动重连）")
            .isEqualTo(McpStatus.running);
        assertThat(service.getById(created.id()).status())
            .as("DB 状态必须已落 running（start 重读 DB 过既有门后落库）")
            .isEqualTo(McpStatus.running);
    }

    @Test
    @DisplayName("PATCH enabled=true（running）→ 不重复 start（幂等，仍 running）")
    void enablingRunningServerDoesNotRestart() {
        McpServerDto created = create("idem-srv", true);
        McpServerDto started = service.start(created.id());
        assertThat(started.status()).isEqualTo(McpStatus.running);

        McpServerDto updated = service.update(created.id(),
            new McpCreateRequest("idem-srv", "python", List.of(), Map.of(), Boolean.TRUE, "stdio"));
        assertThat(updated.status())
            .as("running 状态 PATCH enabled=true 不得重复 start（CC toggle 仅 disabled 态触发重连）")
            .isEqualTo(McpStatus.running);
        Mockito.verify(pool, Mockito.times(1)).assembleToolPool(Mockito.anyString(), Mockito.any());
    }
    @Test
    @DisplayName("isMcpServerDisabled(name) 公共判定（enabled=false → true）")
    void isDisabledPublicJudge() {
        create("off", false);
        create("on", true);
        assertThat(service.isMcpServerDisabled("off")).isTrue();
        assertThat(service.isMcpServerDisabled("on")).isFalse();
        assertThat(service.isMcpServerDisabled("missing")).isTrue();
    }
}
