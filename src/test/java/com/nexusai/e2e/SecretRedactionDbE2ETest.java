package com.nexusai.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.apis.settings.SettingsController;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.config.McpConfigAddValidator;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.provider.Provider;
import com.nexusai.model.provider.dto.ProviderCreateRequest;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.provider.dto.ProviderType;
import com.nexusai.model.provider.dto.ProviderUpdateRequest;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.model.settings.dto.SettingsResponse;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [保密 2026-11-22 · 真库 E2E] 脱敏改造的端到端合同：真 SQLite + 真 Flyway 迁移 + 真 MyBatis-Flex，
 * 走 Controller → Service → DB 整链（不起 Spring，不碰用户真库；库文件在 target/flex-dbtest）。
 *
 * <p>锁死三件 mock 测不出来、只能靠真列取证的事：
 * <ol>
 *   <li>出站响应 JSON 不含明文（settings key / 代理口令）；</li>
 *   <li>掩码回写<b>不覆盖</b> DB 真值（api_key / 密文 / extra_headers / env 逐列核对）；</li>
 *   <li>真实请求装配的内部读链仍拿真值：{@code getDecryptedApiKey}（真 AES 往返）、
 *       {@code getPlainExtraHeaders}、{@code readDbApiKeyPlain}、{@code readDbWebsearchProxy}。</li>
 * </ol>
 * 第 3 条是「发模型会不会坏」的直接证据。
 */
class SecretRedactionDbE2ETest {
    private static final Path DB_PATH = Path.of("target", "flex-dbtest", "secret-redaction-e2e.db");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "sk-live-abcdefgh1234";
    private static final String PROXY = "http://user:pw-123456@127.0.0.1:7897";
    private static final String MCP_TOKEN = "ghp-1234567890";
    private static final String PROVIDER_ID = "prov-e2e";
    private static final String MCP_ID = "mcp-e2e";

    private static SettingsMapper settingsMapper;
    private static ProviderMapper providerMapper;
    private static McpServerMapper mcpServerMapper;
    private static SettingsService settingsService;
    private static ProviderService providerService;
    private static McpServerService mcpService;
    private static SettingsController settingsController;
    private static MockMvc mockMvc;
    private static CryptoUtil cryptoUtil;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Files.createDirectories(DB_PATH.getParent());
        deleteDbFiles();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
        Flyway.configure().dataSource(ds)
            .locations("filesystem:" + migrationDir().toAbsolutePath().toString().replace('\\', '/'))
            .baselineOnMigrate(true).load().migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds,
            SettingsMapper.class, ProviderMapper.class, ModelMapper.class, McpServerMapper.class);
        MybatisFlexBootstrap boot = MybatisFlexBootstrap.getInstance();
        settingsMapper = boot.getMapper(SettingsMapper.class);
        providerMapper = boot.getMapper(ProviderMapper.class);
        mcpServerMapper = boot.getMapper(McpServerMapper.class);

        // [真加解密] 用一次性测试密钥构造真 CryptoUtil（隔离临时库，不读生产 key）
        cryptoUtil = new CryptoUtil(Base64.getEncoder().encodeToString(new byte[32]));

        settingsService = new SettingsService();
        ReflectionTestUtils.setField(settingsService, "settingsMapper", settingsMapper);

        providerService = new ProviderService();
        ReflectionTestUtils.setField(providerService, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(providerService, "modelMapper", boot.getMapper(ModelMapper.class));
        ReflectionTestUtils.setField(providerService, "cryptoUtil", cryptoUtil);
        ReflectionTestUtils.setField(providerService, "objectMapper", new ObjectMapper());

        McpToolPool pool = mock(McpToolPool.class);
        when(pool.isServerConnected(anyString())).thenReturn(false);
        McpConfigAddValidator validator = mock(McpConfigAddValidator.class);
        when(validator.ensureConfigScope(any())).thenReturn("project");
        when(validator.ensureTransport(any())).thenReturn("ws");
        mcpService = new McpServerService();
        ReflectionTestUtils.setField(mcpService, "mcpServerMapper", mcpServerMapper);
        ReflectionTestUtils.setField(mcpService, "mcpToolPool", pool);
        ReflectionTestUtils.setField(mcpService, "addValidator", validator);

        settingsController = new SettingsController();
        ReflectionTestUtils.setField(settingsController, "settingsService", settingsService);

        // 真 MVC 派发 + 真 Jackson 序列化（standalone：不起 Spring 上下文，不迁移任何库）
        mockMvc = MockMvcBuilders.standaloneSetup(settingsController).build();
    }

    @BeforeEach
    void seed() throws Exception {
        exec("DELETE FROM providers");
        exec("DELETE FROM mcp_servers");

        SettingsRecord s = settingsMapper.selectOneById(1);
        s.setAutoMemoryEnabled(true);
        s.setApiKey(API_KEY);
        s.setProxy(PROXY);
        s.setHttpProxy(PROXY);
        settingsMapper.update(s);

        String now = java.time.OffsetDateTime.now().toString();
        Provider p = new Provider();
        p.setId(PROVIDER_ID);
        p.setName("e2e-provider");
        p.setType(ProviderType.openai_compatible.name());
        p.setBaseUrl("https://api.example.com");
        p.setApiKeyHash("hash");
        p.setApiKeyMasked("****1234");
        p.setApiKeyEncrypted(cryptoUtil.encrypt(API_KEY));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Auth-Token", "tok-1234567890");
        headers.put("X-Trace-Id", "trace-1");
        p.setExtraHeaders(JSON.writeValueAsString(headers));
        p.setEnabled(true);
        p.setAuthMode(ProviderService.AUTH_MODE_API_KEY);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        providerMapper.insert(ProviderRecord.fromDomain(p));

        McpServerRecord m = new McpServerRecord();
        m.setId(MCP_ID);
        m.setName("e2e-mcp");
        m.setCommand("https://mcp.example.com");
        m.setType("ws");
        m.setApprovalStatus("approved");
        m.setScope("project");
        m.setEnabled(true);
        m.setAutoStart(false);
        m.setCreatedAt(now);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LOG_LEVEL", "debug");
        env.put("GITHUB_TOKEN", MCP_TOKEN);
        m.setEnv(JSON.writeValueAsString(env));
        mcpServerMapper.insert(m);
    }

    private static void deleteDbFiles() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH.toString() + suffix));
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
             java.sql.Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String queryOne(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
             java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Path migrationDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p : List.of(cwd.resolve("src/main/resources/db/migration"),
                              cwd.resolve("backend/src/main/resources/db/migration"))) {
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("找不到 db/migration（cwd=" + cwd + "）");
    }

    @Test
    @DisplayName("E2E settings：GET 掩码 / 掩码回写不改库 / 新值落库 / 运行时读链仍明文")
    void settings_roundTrip_onRealDb() throws Exception {
        SettingsResponse resp = settingsController.get();
        assertThat(resp.settings().apiKey()).isEqualTo("****1234");
        assertThat(resp.settings().proxy()).isEqualTo("http://user:****@127.0.0.1:7897");
        String json = JSON.writeValueAsString(resp);
        assertThat(json).doesNotContain(API_KEY).doesNotContain("pw-123456");

        assertThat(queryOne("SELECT api_key FROM settings WHERE id=1")).isEqualTo(API_KEY);

        // 前端把掩码原样回传（只改别的字段的场景）
        SettingsDto echo = JSON.readValue(
            "{\"apiKey\":\"****1234\",\"proxy\":\"http://user:****@127.0.0.1:7897\"}",
            SettingsDto.class);
        settingsController.update(echo);
        assertThat(queryOne("SELECT api_key FROM settings WHERE id=1")).isEqualTo(API_KEY);
        assertThat(queryOne("SELECT proxy FROM settings WHERE id=1")).isEqualTo(PROXY);

        // 新明文 → 真落库
        settingsController.update(JSON.readValue("{\"apiKey\":\"sk-rotated-9999\"}", SettingsDto.class));
        assertThat(queryOne("SELECT api_key FROM settings WHERE id=1")).isEqualTo("sk-rotated-9999");

        // 服务端内部读链（WebSearch / WebFetch）拿到的仍是真值
        assertThat(settingsService.readDbApiKeyPlain()).isEqualTo("sk-rotated-9999");
        assertThat(settingsService.readDbWebsearchProxy()).isEqualTo(PROXY);
    }

    @Test
    @DisplayName("E2E provider：DTO 掩码，DB 明文未变；模型请求读链拿真 key/真 header")
    void provider_roundTrip_onRealDb() throws Exception {
        ProviderDto dto = providerService.getById(PROVIDER_ID);
        assertThat(dto.apiKeyMasked()).isEqualTo("****1234").doesNotContain("sk-");
        assertThat(dto.extraHeaders()).containsEntry("X-Auth-Token", "****7890");
        assertThat(dto.extraHeaders()).containsEntry("X-Trace-Id", "trace-1");

        String encBefore = queryOne("SELECT api_key_encrypted FROM providers WHERE id='" + PROVIDER_ID + "'");
        assertThat(JSON.readTree(queryOne("SELECT extra_headers FROM providers WHERE id='" + PROVIDER_ID + "'"))
            .get("X-Auth-Token").asText()).isEqualTo("tok-1234567890");

        // 掩码回写（编辑其它字段时前端会把整份 map 带回）
        Map<String, String> echoHeaders = new LinkedHashMap<>(dto.extraHeaders());
        providerService.update(PROVIDER_ID, new ProviderUpdateRequest(
            null, null, null, dto.apiKeyMasked(), echoHeaders, null, null));

        assertThat(queryOne("SELECT api_key_encrypted FROM providers WHERE id='" + PROVIDER_ID + "'"))
            .as("密文不得被掩码覆盖").isEqualTo(encBefore);
        assertThat(JSON.readTree(queryOne("SELECT extra_headers FROM providers WHERE id='" + PROVIDER_ID + "'"))
            .get("X-Auth-Token").asText()).as("真实 token 不得被掩码覆盖").isEqualTo("tok-1234567890");

        // 实际发请求的两条内部读链
        assertThat(providerService.getDecryptedApiKey(PROVIDER_ID)).isEqualTo(API_KEY);
        assertThat(providerService.getPlainExtraHeaders(PROVIDER_ID))
            .containsEntry("X-Auth-Token", "tok-1234567890");
    }

    @Test
    @DisplayName("E2E mcp：DTO 只掩敏感键，DB env 明文；掩码回写不覆盖")
    void mcp_roundTrip_onRealDb() throws Exception {
        McpServerDto dto = mcpService.getById(MCP_ID);
        assertThat(dto.env()).containsEntry("LOG_LEVEL", "debug");
        assertThat(dto.env()).containsEntry("GITHUB_TOKEN", "****7890");
        assertThat(queryOne("SELECT env FROM mcp_servers WHERE id='" + MCP_ID + "'")).contains(MCP_TOKEN);

        Map<String, String> echoEnv = new LinkedHashMap<>(dto.env());
        mcpService.update(MCP_ID, new McpCreateRequest(
            "e2e-mcp", null, null, echoEnv, null, "ws", null, null, null, null, null, null));

        var saved = JSON.readTree(queryOne("SELECT env FROM mcp_servers WHERE id='" + MCP_ID + "'"));
        assertThat(saved.get("GITHUB_TOKEN").asText()).isEqualTo(MCP_TOKEN);
        assertThat(saved.get("LOG_LEVEL").asText()).isEqualTo("debug");
    }

    @Test
    @DisplayName("HTTP GET /api/v1/settings：真 MVC + 真库，响应体无明文")
    void http_getSettings_noPlaintext() throws Exception {
        mockMvc.perform(get("/api/v1/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apiKey").value("****1234"))
            .andExpect(content().string(not(containsString(API_KEY))))
            .andExpect(content().string(not(containsString("pw-123456"))));
    }

    @Test
    @DisplayName("HTTP PUT /api/v1/settings：掩码回写不改库")
    void http_putSettings_maskedEchoKeepsDb() throws Exception {
        mockMvc.perform(put("/api/v1/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"****1234\",\"proxy\":\"http://user:****@127.0.0.1:7897\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apiKey").value("****1234"));

        assertThat(queryOne("SELECT api_key FROM settings WHERE id=1")).isEqualTo(API_KEY);
        assertThat(queryOne("SELECT proxy FROM settings WHERE id=1")).isEqualTo(PROXY);
    }

    @Test
    @DisplayName("HTTP /settings/http-proxy：GET 掩码；PUT 掩码只改主机口时口令从库换回")
    void http_httpProxy_roundTrip() throws Exception {
        mockMvc.perform(get("/api/v1/settings/http-proxy"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proxy").value("http://user:****@127.0.0.1:7897"));

        mockMvc.perform(put("/api/v1/settings/http-proxy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"proxy\":\"http://user:****@10.0.0.2:8080\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proxy").value("http://user:****@10.0.0.2:8080"));

        assertThat(queryOne("SELECT http_proxy FROM settings WHERE id=1"))
            .isEqualTo("http://user:pw-123456@10.0.0.2:8080");
    }
}
