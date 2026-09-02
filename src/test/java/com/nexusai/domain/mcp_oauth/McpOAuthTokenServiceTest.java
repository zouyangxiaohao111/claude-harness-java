package com.nexusai.domain.mcp_oauth;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.nexusai.application.agent.mcp.McpOAuth;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import com.nexusai.repository.mcp_oauth.entity.McpOAuthTokenRecord;
import com.nexusai.repository.mcp_oauth.mapper.McpOAuthClientConfigMapper;
import com.nexusai.repository.mcp_oauth.mapper.McpOAuthTokenMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 · McpOAuthTokenService 持久化（save/read/update/delete/clientSecret）。
 *
 * <p><b>WHY (意图验证)</b>: CC 把 OAuth 凭据持久化到 keychain 并跨连接复用
 * （saveTokens/saveClientInformation/saveDiscoveryState 全写 mcpOAuth[serverKey]）。
 * Java 用 DB 对齐：认证完成写入后，后续连接必须能读回同一 serverKey 的 token；
 * 若 read 失效则每次连接都重新 401/重新认证（认证链死循环）。
 *
 * <p>MybatisFlexBootstrap 单例 + 临时 SQLite + Flyway V1..V12，须独立运行。
 */
class McpOAuthTokenServiceTest {

    @TempDir
    static Path tempDir;

    private static McpOAuthTokenMapper tokenMapper;
    private static McpOAuthClientConfigMapper clientConfigMapper;
    private static McpOAuthTokenService service;
    private static Flyway flyway;
    private static SQLiteDataSource ds;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // 共享稳定 DB + 重置 MyBatis-Flex 全局状态（mapper 代理缓存/单例），避免跨测试类冲突（见 MybatisFlexDbTestSupport）。
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        flyway = Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load();
        flyway.migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, McpOAuthTokenMapper.class, McpOAuthClientConfigMapper.class);
        tokenMapper = MybatisFlexBootstrap.getInstance().getMapper(McpOAuthTokenMapper.class);
        clientConfigMapper = MybatisFlexBootstrap.getInstance().getMapper(McpOAuthClientConfigMapper.class);

        service = new McpOAuthTokenService();
        ReflectionTestUtils.setField(service, "mcpOAuthTokenMapper", tokenMapper);
        ReflectionTestUtils.setField(service, "mcpOAuthClientConfigMapper", clientConfigMapper);
    }

    @AfterAll
    static void tearDown() {
        try {
            ds.setUrl("jdbc:sqlite:" + tempDir.resolve("mcp-oauth-token.db"));
        } catch (Throwable ignored) {
            // 单例清理：关闭数据源连接
        }
    }

    @BeforeEach
    void clean() {
        for (McpOAuthTokenRecord r : tokenMapper.selectAll()) {
            tokenMapper.deleteById(r.getServerKey());
        }
        for (var r : clientConfigMapper.selectAll()) {
            clientConfigMapper.deleteById(r.getServerKey());
        }
    }

    private McpOAuthToken sampleToken(String name) {
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey(McpOAuth.getServerKey(name, "sse", "http://example.com", null));
        t.setServerName(name);
        t.setServerUrl("http://example.com");
        t.setAccessToken("access-" + name);
        t.setRefreshToken("refresh-" + name);
        t.setExpiresAt(System.currentTimeMillis() + 3600_000L);
        t.setScope("read write");
        t.setClientId("client-" + name);
        t.setClientSecret("secret-" + name);
        t.setStepUpScope(null);
        t.setDiscoveryState("{\"authorizationServerUrl\":\"http://as.example.com\"}");
        return t;
    }

    @Test
    @DisplayName("save → read 读回同 serverKey 全字段（认证链可复用凭据）")
    void saveThenRead() {
        McpOAuthToken t = sampleToken("srvA");
        service.save(t);

        McpOAuthToken got = service.read(t.getServerKey());
        assertThat(got).as("save 后必须能按 serverKey 读回").isNotNull();
        assertThat(got.getServerName()).isEqualTo("srvA");
        assertThat(got.getAccessToken()).isEqualTo("access-srvA");
        assertThat(got.getRefreshToken()).isEqualTo("refresh-srvA");
        assertThat(got.getScope()).isEqualTo("read write");
        assertThat(got.getClientId()).isEqualTo("client-srvA");
        assertThat(got.getClientSecret()).isEqualTo("secret-srvA");
        assertThat(got.getDiscoveryState()).contains("authorizationServerUrl");
    }

    @Test
    @DisplayName("save 同 serverKey 覆盖更新（update 路径幂等）")
    void saveUpdatesExisting() {
        McpOAuthToken t = sampleToken("srvB");
        service.save(t);
        McpOAuthToken updated = sampleToken("srvB");
        updated.setAccessToken("access-new");
        updated.setExpiresAt(0L);
        service.save(updated);

        McpOAuthToken got = service.read(t.getServerKey());
        assertThat(got.getAccessToken())
            .as("同 serverKey 再次 save 必须覆盖旧 token（CC saveTokens 幂等语义）")
            .isEqualTo("access-new");
        assertThat(got.getExpiresAt()).isEqualTo(0L);
        assertThat(got.getServerName()).isEqualTo("srvB");
    }

    @Test
    @DisplayName("delete → read null（token 失效后不可复用）")
    void deleteThenReadNull() {
        McpOAuthToken t = sampleToken("srvC");
        service.save(t);
        service.delete(t.getServerKey());
        assertThat(service.read(t.getServerKey()))
            .as("delete 后 read 必须 null（认证链不再复用已撤销凭据）")
            .isNull();
    }

    @Test
    @DisplayName("clientSecret 存取删：saveClientSecret → getClientSecret → clearClientSecret")
    void clientSecretLifecycle() {
        String key = McpOAuth.getServerKey("srvD", "http", "http://example.com", null);
        assertThat(service.getClientSecret(key))
            .as("未配置时 client_secret 为 null（clientInformation 二级回退未命中）")
            .isNull();

        service.saveClientSecret(key, "pre-secret");
        assertThat(service.getClientSecret(key))
            .as("saveClientSecret 后必须读回预配置 secret")
            .isEqualTo("pre-secret");

        service.clearClientSecret(key);
        assertThat(service.getClientSecret(key))
            .as("clearClientSecret 后必须清空")
            .isNull();
    }

    @Test
    @DisplayName("不同 serverName 同 URL 不共用凭据（getServerKey 隔离语义）")
    void differentServersIsolated() {
        McpOAuthToken a = sampleToken("srvE1");
        McpOAuthToken b = sampleToken("srvE2");
        assertThat(a.getServerKey()).as("serverName 不同 → 主键不同（不共用凭据）").isNotEqualTo(b.getServerKey());
        service.save(a);
        assertThat(service.read(b.getServerKey()))
            .as("srvE2 不应读到 srvE1 的 token")
            .isNull();
    }
}
