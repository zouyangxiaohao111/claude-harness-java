package com.nexusai.application.agent.mcp;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.mcp.config.McpConfigAddValidator;
import com.nexusai.application.agent.mcp.config.McpConfigFileWriter;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.mcp.dto.McpStatus;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [R2-2 单 server 启动路径接 needs-auth] McpServerService.start() 连接期 401 → 产出
 * {@code mcp__<server>__authenticate} 伪工具 · status=running。
 *
 * <p><b>WHY (意图验证)</b>: R1 S3 已接线 {@link McpToolPool#processBatchServer}（批连接/启动预取），
 * 但 {@link McpServerService#start}（REST 单 server 显式启动）此前对连接期 401（{@link McpAuthError}）
 * 直接抛 RuntimeException → status=error，模型看不到伪工具、无法发起 OAuth。R2-2 在
 * {@link McpToolPool#assembleToolPool}（start() 走此路径）接线 needs-auth：401 → 返回
 * authenticate 伪工具注册项（对齐 CC connectToServer → type='needs-auth' → 调用方产
 * createMcpAuthTool，client.ts:1105-1107/:1121-1123/:2331），start() 注册伪工具并置 running；
 * OAuth 成功（{@code setMcpAuthToolSwapHandler → replaceServerToolsAfterAuth}）后替换真实工具。
 *
 * <p>MybatisFlexBootstrap 单例，与其它 mcp DB 测试共用稳定 DB 路径（target/flex-dbtest/flex.db，
 * 全局 mapper 只绑一次，各自 clean() 保证数据隔离）。
 */
class McpServerStartNeedsAuthTest {

    @TempDir
    static Path tempDir;

    private static McpServerMapper mapper;
    private static McpServerService service;

    @BeforeAll
    static void setUpDatabase() throws Exception {
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
        // 真实 McpToolPool（非 mock）：start() 走 assembleToolPool → AuthFailTransport 连接期
        // initialize 返回 401 → McpAuthError → needs-auth 伪工具路径。
        McpToolPool pool = new McpToolPool(
            new McpNeedsAuthWiringTest.AuthFailFactory(),
            new ToolRegistry(),
            new JsonRpcMcpClient());
        ReflectionTestUtils.setField(service, "mcpToolPool", pool);
        // test() 用（本测试不触发）；start() 内 channelNotificationGate.setAllowedChannelsSupplier 需非 null
        ReflectionTestUtils.setField(service, "mcpTransportFactory",
            Mockito.mock(McpTransportFactory.class));
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

    @Test
    @DisplayName("start() 连接期 401 → 注册 mcp__<server>__authenticate 伪工具且 status=running")
    void start_connect401_registersAuthPseudoTool() {
        // mcp-add 三分发：远程 http 走 url 独立字段（非 command 字段）
        McpServerDto created = service.create(new McpCreateRequest(
            "auth-srv", null, null, null, true, "http",
            "http://localhost:9", null, null, null, "project"));

        McpServerDto started = service.start(created.id());

        // start() 不因 needs-auth 失败——status=running，伪工具进入 LLM 池（模型可见可触发 OAuth）
        assertThat(started.status()).isEqualTo(McpStatus.running);
        List<Tool> tools = service.getCurrentTools();
        assertThat(tools).extracting(Tool::name)
            .contains("mcp__auth-srv__authenticate");
        Tool authTool = tools.stream()
            .filter(t -> "mcp__auth-srv__authenticate".equals(t.name()))
            .findFirst().orElseThrow();
        assertThat(authTool).isInstanceOf(McpAuthTool.class);
        assertThat(authTool.isEnabled()).isTrue();
    }
}
