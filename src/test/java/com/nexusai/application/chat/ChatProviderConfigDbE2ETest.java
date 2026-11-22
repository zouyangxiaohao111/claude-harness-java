package com.nexusai.application.chat;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.provider.Provider;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.provider.dto.ProviderType;
import com.nexusai.model.provider.dto.ProviderUpdateRequest;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [保密 2026-11-22 · 真库 E2E] 对话请求装配链的「真值」取证。
 *
 * <p>直接调用 {@link ChatService#buildConfigForModel(String)}（包级可见），走
 * Model 名解析 → Provider 行读取 → 凭据解密 → {@code assembleProviderConfig} 全链；
 * 数据库是真 SQLite + 真 Flyway 迁移，加密是真 AES（一次性测试密钥）。
 *
 * <p>断言的就是「发模型不会因为脱敏而坏」：组出来的 {@link ProviderConfig} 里必须是
 * 解密后的真 key 与真自定义 header，且**在把掩码 DTO 原样回写之后仍然如此**。
 */
class ChatProviderConfigDbE2ETest {

    private static final Path DB_PATH = Path.of("target", "flex-dbtest", "chat-provider-config-e2e.db");
    private static final String API_KEY = "sk-live-abcdefgh1234";
    private static final String PROVIDER_ID = "prov-chat-e2e";

    private static ProviderMapper providerMapper;
    private static ModelMapper modelMapper;
    private static ProviderService providerService;
    private static ChatService chatService;
    private static CryptoUtil cryptoUtil;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Files.createDirectories(DB_PATH.getParent());
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH.toString() + suffix));
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
        Flyway.configure().dataSource(ds)
            .locations("filesystem:" + migrationDir().toAbsolutePath().toString().replace('\\', '/'))
            .baselineOnMigrate(true).load().migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, ProviderMapper.class, ModelMapper.class);
        MybatisFlexBootstrap boot = MybatisFlexBootstrap.getInstance();
        providerMapper = boot.getMapper(ProviderMapper.class);
        modelMapper = boot.getMapper(ModelMapper.class);

        cryptoUtil = new CryptoUtil(Base64.getEncoder().encodeToString(new byte[32]));
        providerService = new ProviderService();
        ReflectionTestUtils.setField(providerService, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(providerService, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(providerService, "cryptoUtil", cryptoUtil);
        ReflectionTestUtils.setField(providerService, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());

        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(chatService, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(chatService, "providerService", providerService);
    }

    @BeforeEach
    void seed() throws Exception {
        exec("DELETE FROM models");
        exec("DELETE FROM providers");

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
        p.setExtraHeaders(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(headers));
        p.setEnabled(true);
        p.setAuthMode(ProviderService.AUTH_MODE_API_KEY);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        providerMapper.insert(ProviderRecord.fromDomain(p));

        exec("INSERT INTO models(id, provider_id, name, tag, type, enabled)"
            + " VALUES ('model-chat-e2e','" + PROVIDER_ID + "','e2e-model','DS','chat',1)");
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute(sql);
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
    @DisplayName("buildConfigForModel：真库里组出的是解密后的真 key + 真自定义 header")
    void buildConfig_usesRealCredentialAndHeaders() {
        ProviderConfig config = chatService.buildConfigForModel("e2e-model");
        assertThat(config.apiKey()).isEqualTo(API_KEY);
        assertThat(config.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(config.extraHeaders()).containsEntry("X-Auth-Token", "tok-1234567890");
        assertThat(config.extraHeaders()).containsEntry("X-Trace-Id", "trace-1");

        // 全名形态同样要能解析
        ProviderConfig byFullName = chatService.buildConfigForModel("e2e-provider/e2e-model");
        assertThat(byFullName.apiKey()).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("掩码 DTO 回写之后：DB 未变，buildConfigForModel 仍拿真 key/真 header")
    void afterMaskedEchoUpdate_stillRealForModelCall() {
        ProviderDto dto = providerService.getById(PROVIDER_ID);
        assertThat(dto.apiKeyMasked()).isEqualTo("****1234");
        assertThat(dto.extraHeaders()).containsEntry("X-Auth-Token", "****7890");

        providerService.update(PROVIDER_ID, new ProviderUpdateRequest(
            null, null, null, dto.apiKeyMasked(),
            new LinkedHashMap<>(dto.extraHeaders()), null, null));

        ProviderConfig config = chatService.buildConfigForModel("e2e-model");
        assertThat(config.apiKey()).as("发模型必须用真 key").isEqualTo(API_KEY);
        assertThat(config.extraHeaders())
            .as("发模型必须用真鉴权头").containsEntry("X-Auth-Token", "tok-1234567890");
    }
}
