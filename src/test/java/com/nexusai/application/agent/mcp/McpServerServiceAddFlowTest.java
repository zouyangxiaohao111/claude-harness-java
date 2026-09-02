package com.nexusai.application.agent.mcp;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.mcp.config.McpConfigAddValidator;
import com.nexusai.application.agent.mcp.config.McpConfigFileWriter;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpOAuthRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CC {@code claude mcp add} → Java REST create/update/delete 单写契约（DB 唯一源，
 * 用户拍板 2026-08-30）验证。
 *
 * <p><b>WHY (意图验证)</b>: 用户拍板 MCP 写只写 DB、读只读 DB，双写已删——create/update/
 * delete 不再写 .mcp.json/.nexusai.json 配置源文件（McpConfigFileWriter 的配置源写/读方法
 * 已全部删除），list/get 也不再读文件（读侧统一走 DB）。本测试锁定 DB 唯一源语义：
 * <ol>
 *   <li><b>create 单写 DB</b>：只落 DB 行，不写 .mcp.json；scope 持久化到 DB scope 列（V59）</li>
 *   <li><b>远程 server（sse/http）三分发</b>：url/headers/oauth 只落 DB（url→command 列、
 *       headers→env 列、oauth→env 镜像键 {@code __mcp_oauth__}），oauth 读侧回读自 DB 镜像</li>
 *   <li><b>clientSecret → keychain</b>：仅 sse/http + clientId 同时存在才落库，不进 config/文件</li>
 *   <li><b>重复名 → 409 非 500</b>：校验链 g 防 DB UNIQUE 抛 DataIntegrityViolation</li>
 *   <li><b>delete / update</b>：只删/改 DB 行，不碰配置源文件</li>
 * </ol>
 */
class McpServerServiceAddFlowTest {

    @TempDir
    static Path tempDir;

    private static McpServerMapper mapper;
    private static McpServerService service;
    private static McpOAuthTokenService tokenService;
    private static FileConfigStorage storage;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // describeMcpConfigFilePath(filePath 展示) 走 CwdResolution override（避免读到真实 user.dir）
        CwdResolution.setCurrentOverride(tempDir.toString());

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
        tokenService = Mockito.mock(McpOAuthTokenService.class);
        ReflectionTestUtils.setField(service, "mcpOAuthTokenService", tokenService);
        McpToolPool pool = Mockito.mock(McpToolPool.class);
        when(pool.assembleToolPool(Mockito.anyString(), Mockito.any())).thenReturn(List.of());
        when(pool.fetchMcpSkills(Mockito.anyString())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "mcpToolPool", pool);
        ReflectionTestUtils.setField(service, "mcpTransportFactory",
            Mockito.mock(McpTransportFactory.class));
        ReflectionTestUtils.setField(service, "channelNotificationGate",
            Mockito.mock(ChannelNotificationGate.class));
        ReflectionTestUtils.setField(service, "channelSessionAllowlist",
            new ChannelSessionAllowlist());
        // mcp-add 校验链（真实组件，enterprise/policy 缺省放行）+ 路径描述器（DB 唯一源：
        // 仅 describeMcpConfigFilePath 一个使用点；storage mock 供「不写配置源」verify）
        ReflectionTestUtils.setField(service, "addValidator", new McpConfigAddValidator(null, null));
        storage = Mockito.mock(FileConfigStorage.class);
        ReflectionTestUtils.setField(service, "configFileWriter",
            new McpConfigFileWriter(storage, null));
        // XAA feature 门控：测试环境不读真实 env（避免宿主机器 CLAUDE_CODE_ENABLE_XAA 影响），
        // 显式关 → 对齐 CC 生产默认 feature 关
        service.setXaaEnabledGate(() -> false);
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
        Mockito.reset(tokenService, storage);
    }

    private static McpCreateRequest stdio(String name, String command) {
        return new McpCreateRequest(name, command, List.of(), Map.of(), true, "stdio",
            null, null, null, null, "project");
    }

    private Path projectMcpJson() {
        return tempDir.resolve(".mcp.json");
    }

    // ── create 单写 DB（DB 唯一源） ──

    @Test
    @DisplayName("create stdio → 只落 DB 行（scope=project 列 V59），不写 .mcp.json")
    void create_stdio_writesDbRowWithScope_notFile() {
        McpServerDto dto = service.create(stdio("my-srv", "python"));

        // DB 侧（DB 唯一源，运行时源）
        McpServerRecord row = mapper.selectOneByName("my-srv");
        assertThat(row).as("create 必须 upsert DB（运行时源即时生效）").isNotNull();
        assertThat(row.getCommand()).isEqualTo("python");
        assertThat(row.getApprovalStatus()).isEqualTo("approved");
        assertThat(row.getScope()).as("create scope 必须落 DB scope 列（V59，DB 唯一源）").isEqualTo("project");
        // 文件侧：DB 唯一源 → 不写 .mcp.json（配置源写方法已删）
        assertThat(Files.exists(projectMcpJson())).as("create 不得写 .mcp.json（DB 唯一源）").isFalse();
        // DTO 契约（filePath 由 describeMcpConfigFilePath 描述，仅展示）
        assertThat(dto.scope()).isEqualTo("project");
        assertThat(dto.filePath()).isEqualTo(Path.of(tempDir.toString(), ".mcp.json").toString());
    }

    @Test
    @DisplayName("create 远程 http → url/headers/oauth 只落 DB（env 镜像键），不写 .mcp.json")
    void create_http_remote_oauthMirrorInDbEnv_notFile() {
        Map<String, String> headers = Map.of("Authorization", "Bearer xyz");
        McpCreateRequest req = new McpCreateRequest("remote-srv", null, null, null, true, "http",
            "https://mcp.example.com/mcp", headers,
            new McpOAuthRequest("client-1", "8080", null, null), null, "project");
        McpServerDto dto = service.create(req);

        // DB = 唯一权威配置源：oauth 独立镜像键（__mcp_oauth__，DB 唯一源），无文件条目
        McpServerRecord row = mapper.selectOneByName("remote-srv");
        assertThat(row.getCommand()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(row.getEnv()).as("headers 承载于 env 列").contains("Authorization");
        assertThat(row.getEnv()).as("oauth 必须镜像入 DB env 保留键（list/get 从 DB 回读）")
            .contains(McpOAuth.ENV_OAUTH_MIRROR_KEY);
        assertThat(Files.exists(projectMcpJson())).as("create 不得写 .mcp.json（DB 唯一源）").isFalse();
        // DTO 反解（供前端回显）
        assertThat(dto.url()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(dto.headers()).containsEntry("Authorization", "Bearer xyz");
        assertThat(dto.oauth()).containsEntry("clientId", "client-1");
    }

    @Test
    @DisplayName("create 重复名 → 409 非 500（校验链 g 防 DB UNIQUE 抛 DataIntegrityViolation）")
    void create_duplicateName_conflict409_not500() {
        service.create(stdio("dup-srv", "python"));
        // 同名再次 create：DB 已有同名行（校验链 g project 分支命中 existingDb）→ 409
        assertThatThrownBy(() -> service.create(stdio("dup-srv", "python")))
            .as("重复名必须 409（前端读 Problem.detail），不得落 DB UNIQUE 抛 500")
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already exists");
        // DB 仍只有一行（未重复插入）
        assertThat(mapper.selectAll()).hasSize(1);
    }

    @Test
    @DisplayName("create 名字非法 → 400 + CC 文案（校验链 a）")
    void create_invalidName_validation400() {
        assertThatThrownBy(() -> service.create(stdio("my.server", "python")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid name my.server. Names can only contain letters, numbers, hyphens, and underscores.");
        assertThat(mapper.selectAll()).isEmpty();
    }

    @Test
    @DisplayName("create sse 缺 url → 400 URL is required")
    void create_sseMissingUrl_validation400() {
        McpCreateRequest req = new McpCreateRequest("sse-srv", null, null, null, true, "sse",
            null, null, null, null, "project");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("URL is required for SSE transport.");
    }

    // ── clientSecret → keychain（addCommand.ts:168-183） ──

    @Test
    @DisplayName("clientSecret + clientId → 落 keychain（mcp_oauth_client_config），不进 config/文件")
    void create_clientSecretWithClientId_savedToKeychain_notInConfig() {
        Map<String, String> headers = Map.of("Authorization", "Bearer xyz");
        McpCreateRequest req = new McpCreateRequest("remote-secret", null, null, null, true, "http",
            "https://mcp.example.com/mcp", headers,
            new McpOAuthRequest("client-1", "8080", null, null), "shh-secret", "project");
        service.create(req);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(tokenService).saveClientSecret(keyCap.capture(), eq("shh-secret"));
        String expectedKey = McpOAuth.getServerKey("remote-secret", "http",
            "https://mcp.example.com/mcp", headers);
        assertThat(keyCap.getValue())
            .as("clientSecret 落库键必须与 McpAuthHeaderProvider.serverKey 同键（否则消费/清理错行）")
            .isEqualTo(expectedKey);
        // 不进 DB env（CC 语义：secret 只在 keychain；DB 唯一源下 DB env 也不得含明文 secret）
        McpServerRecord row = mapper.selectOneByName("remote-secret");
        assertThat(row.getEnv()).as("clientSecret 不得写入 DB env").doesNotContain("shh-secret")
            .doesNotContain("clientSecret");
        assertThat(Files.exists(projectMcpJson())).as("create 不得写 .mcp.json（DB 唯一源）").isFalse();
    }

    @Test
    @DisplayName("仅 clientSecret 无 clientId → 忽略不落库（CC addCommand.ts:168-171）")
    void create_clientSecretNoClientId_ignored() {
        McpCreateRequest req = new McpCreateRequest("remote-no-id", null, null, null, true, "http",
            "https://mcp.example.com/mcp", null,
            new McpOAuthRequest(null, null, null, null), "shh-secret", "project");
        service.create(req);
        verify(tokenService, never()).saveClientSecret(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    @DisplayName("callbackPort 纯字母串 → NaN 丢弃不报错、不进 oauth（门禁修正 1）")
    void create_callbackPortAlphabetDropped() {
        // WHY: CC addCommand.ts:156-166 —— parseInt("abc")=NaN 为 falsy → oauth 条件与展开均短路
        // → 静默丢弃、不报错。若 Java 抛错（如 Integer.parseInt NFE），会拒绝 CC 接受的输入。
        McpCreateRequest req = new McpCreateRequest("remote-nan", null, null, null, true, "http",
            "https://mcp.example.com/mcp", null,
            new McpOAuthRequest("client-1", "abc", null, null), null, "project");
        McpServerDto dto = service.create(req);
        assertThat(dto.oauth()).as("callbackPort NaN 必须被丢弃，不进 oauth").doesNotContainKey("callbackPort");
        // DB 正常落（不报错）
        assertThat(mapper.selectOneByName("remote-nan")).isNotNull();
    }

    @Test
    @DisplayName("callbackPort=\"0\" + clientId → 静默丢弃 callbackPort、保留 clientId（CC falsy 门）")
    void create_callbackPortZero_withClientId_callbackPortDropped() {
        // WHY: CC addCommand.ts:159-166 —— parseInt(\"0\",10)=0 为 falsy → 展开省略 callbackPort
        // （options.clientId || callbackPort || xaa 为真因 clientId，但 ...(callbackPort ? {...} : {})
        // 对 0 短路）。若 Java 把 0 写进 oauth，schema 的 z.number().int().positive() 会抛
        // 「Number must be greater than 0」→ 400，拒绝 CC 接受的输入（0 是合法 falsy 而非非法值）。
        McpCreateRequest req = new McpCreateRequest("remote-zero", null, null, null, true, "http",
            "https://mcp.example.com/mcp", null,
            new McpOAuthRequest("client-1", "0", null, null), null, "project");
        McpServerDto dto = service.create(req);
        assertThat(dto.oauth()).as("callbackPort=0 必须被丢弃，不进 oauth")
            .containsEntry("clientId", "client-1").doesNotContainKey("callbackPort");
        // DB 正常落（不报错）
        assertThat(mapper.selectOneByName("remote-zero")).isNotNull();
    }

    @Test
    @DisplayName("callbackPort=\"0\" 无 clientId/xaa → 整个 oauth 缺失、服务器正常添加（CC falsy 门短路）")
    void create_callbackPortZero_noClientId_oauthAbsent() {
        // WHY: CC addCommand.ts:159-166 —— 无 clientId 且 callbackPort=0（falsy）且无 xaa →
        // options.clientId || callbackPort || xaa 整体为 falsy → oauth=undefined，serverConfig 无
        // oauth 键，服务器正常添加。若 Java 把 oauth 视为存在（含 callbackPort:0）会触发 schema
        // 400「Number must be greater than 0」，拒绝 CC 接受的输入。
        McpCreateRequest req = new McpCreateRequest("remote-zero-no-id", null, null, null, true, "http",
            "https://mcp.example.com/mcp", null,
            new McpOAuthRequest(null, "0", null, null), null, "project");
        McpServerDto dto = service.create(req);
        assertThat(dto.oauth()).as("callbackPort=0 无 clientId/xaa → oauth 整体缺失").isNull();
        // DB 正常落（不报错）
        assertThat(mapper.selectOneByName("remote-zero-no-id")).isNotNull();
    }

    // ── XAA add-time fail-fast（addCommand.ts:103-122） ──

    @Test
    @DisplayName("xaa=true + feature 关（CLAUDE_CODE_ENABLE_XAA 缺省）→ create 400，服务器不落")
    void create_xaaFeatureOff_rejected400() {
        // WHY: CC addCommand.ts:104-108 —— xaa 时强制 isXaaEnabled（env 缺省 false）→ cliError。
        // Java 若放行会接受 CC 拒绝的输入（弱化行为）。feature 关时 xaa=true 必须 400 且 DB 不落。
        McpCreateRequest req = new McpCreateRequest("remote-xaa", null, null, null, true, "http",
            "https://mcp.example.com/mcp", null,
            new McpOAuthRequest("client-1", null, null, true), "secret-1", "project");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Error: --xaa requires CLAUDE_CODE_ENABLE_XAA=1 in your environment");
        assertThat(mapper.selectAll()).as("xaa 拒绝后服务器不得落库").isEmpty();
    }

    // ── scope 落 DB 列（V59，DB 唯一源） ──

    @Test
    @DisplayName("create scope=user → DB 行 scope=user（V59），不写 .nexusai.json")
    void create_scopeUser_writesDbRowScope_notGlobalFile() {
        McpCreateRequest req = new McpCreateRequest("user-srv", "python", List.of(), Map.of(),
            true, "stdio", null, null, null, null, "user");
        service.create(req);

        McpServerRecord row = mapper.selectOneByName("user-srv");
        assertThat(row).as("user scope create 必须落 DB").isNotNull();
        assertThat(row.getScope()).as("create scope=user → DB scope 列必须为 user（V59，DB 唯一源）")
            .isEqualTo("user");
        // DB 唯一源：不写 FileConfigStorage 全局配置源（user scope 写回分支已删）
        verify(storage, never()).writeGlobal(Mockito.anyString(), Mockito.any());
    }

    @Test
    @DisplayName("create scope=local → DB 行 scope=local（DB 唯一源：local 可写，对齐 CC addMcpConfig local 分支）")
    void create_scopeLocal_writesDbRowScope() {
        // WHY: 旧 Java「受控残留」因 FileConfigStorage 无 projects.<absPath>.mcpServers 嵌套能力
        // 对 local 显式 409；DB 唯一源改造删掉了文件写回 → local 不再受文件存储能力限制，
        // 对齐 CC addMcpConfig config.ts:729-738（local 走 saveCurrentProjectConfig，可写）。
        // scope 持久化到 DB 列（V59），读侧统一从 DB 取。
        McpCreateRequest req = new McpCreateRequest("local-srv", "python", List.of(), Map.of(),
            true, "stdio", null, null, null, null, "local");
        McpServerDto dto = service.create(req);

        McpServerRecord row = mapper.selectOneByName("local-srv");
        assertThat(row).as("local scope create 必须落 DB（DB 唯一源可写）").isNotNull();
        assertThat(row.getScope()).as("create scope=local → DB scope 列必须为 local（V59）").isEqualTo("local");
        assertThat(dto.scope()).isEqualTo("local");
    }

    @Test
    @DisplayName("create scope=dynamic → 409 不可写（config.ts:705）")
    void create_scopeDynamic_conflict() {
        McpCreateRequest req = new McpCreateRequest("dyn-srv", "python", List.of(), Map.of(),
            true, "stdio", null, null, null, null, "dynamic");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server to scope: dynamic");
    }

    @Test
    @DisplayName("create scope 非法 → 400 + CC 文案")
    void create_scopeInvalid_validation400() {
        McpCreateRequest req = new McpCreateRequest("x-srv", "python", List.of(), Map.of(),
            true, "stdio", null, null, null, null, "bogus");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid scope: bogus. Must be one of: local, user, project, dynamic, enterprise, claudeai, managed");
    }

    // ── stdio 非阻断警告（addCommand.ts:239-274） ──

    @Test
    @DisplayName("URL-as-command（未显式 transport）→ 非阻断警告进 warnings[]，create 仍成功")
    void create_urlAsCommand_stdio_warningNonBlocking() {
        McpCreateRequest req = new McpCreateRequest("warn-srv", "http://localhost:9", List.of(),
            Map.of(), true, null, null, null, null, null, "project");
        McpServerDto dto = service.create(req);
        assertThat(dto.warnings()).anySatisfy(w ->
            assertThat(w).contains("looks like a URL"));
        assertThat(mapper.selectOneByName("warn-srv")).isNotNull();
    }

    // ── delete / update 反向一致性（G5-3 remove / G5-2 编辑） ──

    @Test
    @DisplayName("delete → 只删 DB 行（不写 .mcp.json，配置源移除方法已删）")
    void delete_removesDbRow_only() {
        McpServerDto dto = service.create(stdio("del-srv", "python"));
        // DB 唯一源：create 从始至终不写 .mcp.json
        assertThat(Files.exists(projectMcpJson())).isFalse();

        service.delete(dto.id());

        assertThat(mapper.selectOneById(dto.id())).as("DB 行必须删除").isNull();
        assertThat(Files.exists(projectMcpJson())).as("delete 不产生 .mcp.json（DB 唯一源）").isFalse();
    }

    @Test
    @DisplayName("update 改名 → 只改 DB 行（新名写入、旧名移除），不写 .mcp.json")
    void update_rename_updatesDbRow_only() {
        McpServerDto created = service.create(stdio("old-srv", "python"));

        McpCreateRequest renameReq = new McpCreateRequest("new-srv", null, List.of(), Map.of(),
            null, "stdio", null, null, null, null, "project");
        McpServerDto updated = service.update(created.id(), renameReq);

        assertThat(updated.name()).isEqualTo("new-srv");
        McpServerRecord row = mapper.selectOneByName("new-srv");
        assertThat(row).as("DB 必须改名").isNotNull();
        assertThat(row.getScope()).as("update scope 落 DB scope 列（V59）").isEqualTo("project");
        assertThat(mapper.selectOneByName("old-srv")).isNull();
        assertThat(Files.exists(projectMcpJson())).as("update 不写 .mcp.json（DB 唯一源）").isFalse();
    }

    // ── finding 1：oauth DB env 镜像（user scope 远程 server 回读不依赖 .mcp.json） ──

    @Test
    @DisplayName("user scope 远程 http → oauth 落 DB env 镜像键；listAll 从 DB 回读 oauth（finding 1）")
    void userScopeRemote_oauthMirrorInDb_readBackInList() {
        // WHY: DB 唯一源 —— DB 无 oauth 列，oauth 权威在 DB env 镜像键（__mcp_oauth__）。
        // applyServerConfig 把 oauth 序列化入 env 保留键（base64 承载），toDto 反解，list/get
        // 只读 DB。若镜像缺失，create 有 oauth 而 GET 为 null（DTO 漂移）。
        Map<String, String> headers = Map.of("Authorization", "Bearer xyz");
        McpCreateRequest req = new McpCreateRequest("remote-user", null, null, null, true, "http",
            "https://mcp.example.com/mcp", headers,
            new McpOAuthRequest("client-1", "8080", null, null), null, "user");
        service.create(req);

        // DB env 镜像：oauth 序列化入保留键（base64 承载，naive serializeEnv 可往返）
        McpServerRecord row = mapper.selectOneByName("remote-user");
        assertThat(row.getEnv()).as("oauth 必须镜像入 DB env 保留键（list/get 从 DB 唯一源回读）")
            .contains(McpOAuth.ENV_OAUTH_MIRROR_KEY);

        // listAll 反解：DB 唯一源 —— 读只读 DB（无 .mcp.json 文件兜底），oauth 恒可从 DB 镜像回读
        McpServerDto listed = service.listAll().stream()
            .filter(d -> "remote-user".equals(d.name())).findFirst().orElseThrow();
        assertThat(listed.oauth()).as("listAll 必须回读 user scope 远程 server 的 oauth")
            .containsEntry("clientId", "client-1");
        assertThat(listed.headers()).containsEntry("Authorization", "Bearer xyz");
        assertThat(listed.headers()).as("headers 不含内部镜像键")
            .doesNotContainKey(McpOAuth.ENV_OAUTH_MIRROR_KEY);
        assertThat(listed.env()).as("DTO env 不暴露内部镜像键")
            .doesNotContainKey(McpOAuth.ENV_OAUTH_MIRROR_KEY);
    }

    // ── finding 5：update 改 scope → DB scope 列更新（V59，无旧配置文件条目孤儿问题） ──

    @Test
    @DisplayName("update 改 scope（user→project）→ DB scope 列更新为 project（DB 唯一源，无文件条目需清理）")
    void update_scopeChange_updatesDbScopeColumn() {
        // WHY: 旧实现 update 只写新 scope 配置文件，旧 scope（如 .nexusai.json）条目成孤儿，
        // 需旧配置源条目 best-effort 清除。DB 唯一源改造后 scope 由 DB 列（V59）唯一承载，
        // update 改 scope = 直接更新 DB scope 列，不存在跨文件孤儿条目。
        McpCreateRequest createReq = new McpCreateRequest("scope-srv", "python", List.of(),
            Map.of(), true, "stdio", null, null, null, null, "user");
        McpServerDto created = service.create(createReq);
        assertThat(mapper.selectOneByName("scope-srv").getScope()).isEqualTo("user");

        // 改 scope：user → project
        McpCreateRequest updateReq = new McpCreateRequest(null, null, List.of(), Map.of(),
            null, "stdio", null, null, null, null, "project");
        service.update(created.id(), updateReq);

        // DB 唯一源：scope 列直接更新，无「旧 user 条目」残留概念（无文件写回）
        assertThat(mapper.selectOneByName("scope-srv").getScope())
            .as("update 改 scope 必须更新 DB scope 列（V59，DB 唯一源）")
            .isEqualTo("project");
        verify(storage, never()).writeGlobal(Mockito.anyString(), Mockito.any());
    }
}
