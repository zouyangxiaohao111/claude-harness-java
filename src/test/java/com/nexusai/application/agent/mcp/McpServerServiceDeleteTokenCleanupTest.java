package com.nexusai.application.agent.mcp;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.mcp.config.McpConfigAddValidator;
import com.nexusai.application.agent.mcp.config.McpConfigDedup;
import com.nexusai.application.agent.mcp.config.McpConfigFileWriter;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [S03 CE-13] delete → OAuth 凭据清理测试 · 对齐 CC remove 命令语义（mcp.tsx:80-83
 * clearServerTokensFromLocalStorage + clearMcpClientConfig）。
 *
 * <p><b>WHY（意图验证）</b>: delete(server) 此前只删 {@code mcp_servers} 行——token/
 * client_secret 永留 DB（凭据生命周期残缺，CE-13 违反）。本测试断言：
 * <ol>
 *   <li>delete 后按 serverKey 调用 {@code McpOAuthTokenService.delete}（token 行）</li>
 *   <li>delete 后按 serverKey 调用 {@code clearClientSecret}（预配置 client_secret 表）</li>
 *   <li>serverKey 计算与 {@code McpAuthHeaderProvider.serverKey} 完全同键
 *       （McpOAuth.getServerKey(name, type, command, headers-as-env)）——否则清理错行</li>
 * </ol>
 */
class McpServerServiceDeleteTokenCleanupTest {

    @TempDir
    static Path tempDir;

    private static McpServerMapper mapper;
    private static McpServerService service;
    private static McpOAuthTokenService tokenService;

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

        // create/delete 经 mcp-add 校验链 + 配置源写回（AC-1 双写）：project 写 .mcp.json
        // 走 CwdResolution override → @TempDir，避免污染真实工作区。
        CwdResolution.setCurrentOverride(tempDir.toString());
        service = new McpServerService();
        ReflectionTestUtils.setField(service, "mcpServerMapper", mapper);
        tokenService = Mockito.mock(McpOAuthTokenService.class);
        ReflectionTestUtils.setField(service, "mcpOAuthTokenService", tokenService);
        ReflectionTestUtils.setField(service, "channelNotificationGate",
            Mockito.mock(com.nexusai.application.agent.mcp.ChannelNotificationGate.class));
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
        // @BeforeAll 共享 mock：每次重置调用记录，防跨测试 verify 计数累积
        Mockito.reset(tokenService);
    }


    @Test
    @DisplayName("[CE-13] delete → token 行 + 预配置 client_secret 清理，serverKey 与 McpAuthHeaderProvider 同键")
    void delete_cleansOAuthCredentials_sameServerKeyAsHeaderProvider() {
        // 远程 http server：headers 承载于 env（upsert 时把远程 server 的 headers 存入 env 保留）。
        // mcp-add 三分发后 url 走独立 url 字段（非 command 字段）——远程 server 须经 11 参构造填 url。
        Map<String, String> headers = Map.of("Authorization", "Bearer abc");
        McpServerDto created = service.create(new McpCreateRequest(
            "cleanup-srv", null, null, null, true, "http",
            "https://mcp.example.com/mcp", headers, null, null, "project"));

        service.delete(created.id());

        // serverKey 一致性断言：delete 清理键 = McpAuthHeaderProvider.serverKey(config) 同函数产物
        String expectedKey = McpOAuth.getServerKey("cleanup-srv", "http",
            "https://mcp.example.com/mcp", headers);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(tokenService).delete(keyCaptor.capture());
        assertThat(keyCaptor.getValue())
            .as("清理键必须与 McpAuthHeaderProvider.serverKey 同键（否则清理错行）")
            .isEqualTo(expectedKey);
        verify(tokenService).clearClientSecret(expectedKey);
        // DB 行确已删除
        assertThat(mapper.selectOneById(created.id())).isNull();
    }

    @Test
    @DisplayName("[CE-13] delete → stdio server（无 headers）env=null → 空 headers 键一致")
    void delete_stdioServer_usesEmptyHeaders() {
        McpServerDto created = service.create(new McpCreateRequest(
            "stdio-srv", "node server.js", List.of("--flag"), Map.of(), true, "stdio"));

        service.delete(created.id());

        String expectedKey = McpOAuth.getServerKey("stdio-srv", "stdio", "node server.js", Map.of());
        verify(tokenService).delete(expectedKey);
        verify(tokenService).clearClientSecret(expectedKey);
    }
}
