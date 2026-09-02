package com.nexusai.domain.oauth_account;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import com.nexusai.repository.oauth_account.entity.AccountOAuthTokenRecord;
import com.nexusai.repository.oauth_account.mapper.AccountOAuthTokenMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2 · AccountOAuthTokenService 账号级 OAuth token 持久化（save/read/delete/隔离/可空语义）。
 *
 * <p><b>WHY (意图验证)</b>: CC 把账号 OAuth 凭据持久化到 keychain 单 key claudeAiOauth
 * （auth.ts saveOAuthTokensIfNeeded 写、getClaudeAIOAuthTokens 读同一 entry）并跨连接复用。
 * Java 泛化为 provider|identity 复合键：认证完成后写入，后续会话必须能读回同一账号 token；
 * 若 read 失效则每次会话都重新授权（认证链死循环）；复合键隔离语义保证 github|alice 不串读到
 * github|bob 或 gitlab|alice；GitHub 无 refresh_token / 不过期 token 的 NULL 字段不被改写。
 *
 * <p>MybatisFlexBootstrap 单例 + 临时 SQLite + Flyway V1..V14，须独立运行。
 */
class AccountOAuthTokenServiceTest {

    @TempDir
    static Path tempDir;

    private static AccountOAuthTokenMapper accountOAuthTokenMapper;
    private static AccountOAuthTokenService service;
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

        MybatisFlexDbTestSupport.resetAndStart(ds, AccountOAuthTokenMapper.class);
        accountOAuthTokenMapper = MybatisFlexBootstrap.getInstance().getMapper(AccountOAuthTokenMapper.class);

        service = new AccountOAuthTokenService();
        ReflectionTestUtils.setField(service, "accountOAuthTokenMapper", accountOAuthTokenMapper);
    }

    @AfterAll
    static void tearDown() {
        try {
            ds.setUrl("jdbc:sqlite:" + tempDir.resolve("account-oauth-token.db"));
        } catch (Throwable ignored) {
            // 单例清理：关闭数据源连接
        }
    }

    @BeforeEach
    void clean() {
        for (AccountOAuthTokenRecord r : accountOAuthTokenMapper.selectAll()) {
            accountOAuthTokenMapper.deleteByQuery(
                QueryWrapper.create().eq("provider", r.getProvider()).eq("identity", r.getIdentity()));
        }
    }

    private AccountOAuthToken sampleToken(String provider, String identity) {
        AccountOAuthToken t = new AccountOAuthToken();
        t.setProvider(provider);
        t.setIdentity(identity);
        t.setAccessToken("access-" + identity);
        t.setRefreshToken("refresh-" + identity);
        t.setExpiresAt(System.currentTimeMillis() + 3600_000L);
        t.setScope("repo user");
        return t;
    }

    @Test
    @DisplayName("save → read 读回同 provider|identity 全字段（认证链跨会话复用凭据）")
    void saveThenRead() {
        AccountOAuthToken t = sampleToken("github", "alice");
        service.save(t);

        AccountOAuthToken got = service.read("github", "alice");
        assertThat(got).as("save 后必须能按 provider|identity 读回").isNotNull();
        assertThat(got.getAccessToken()).isEqualTo("access-alice");
        assertThat(got.getRefreshToken()).isEqualTo("refresh-alice");
        assertThat(got.getExpiresAt()).isNotNull();
        assertThat(got.getScope()).isEqualTo("repo user");
    }

    @Test
    @DisplayName("同 provider|identity 再 save 覆盖更新（幂等）")
    void saveUpdatesExisting() {
        AccountOAuthToken t = sampleToken("github", "bob");
        service.save(t);

        AccountOAuthToken updated = sampleToken("github", "bob");
        updated.setAccessToken("access-new");
        updated.setRefreshToken(null);      // GitHub 无 refresh_token，须覆盖清空旧值
        updated.setExpiresAt(null);         // 不过期 token，须覆盖清空旧过期时间
        service.save(updated);

        AccountOAuthToken got = service.read("github", "bob");
        assertThat(got.getAccessToken())
            .as("同 provider|identity 再次 save 必须覆盖旧 token（CC saveOAuthTokensIfNeeded 幂等语义）")
            .isEqualTo("access-new");
        assertThat(got.getRefreshToken()).as("refreshToken 覆盖为 null 必须生效").isNull();
        assertThat(got.getExpiresAt()).as("expiresAt 覆盖为 null 必须生效").isNull();
    }

    @Test
    @DisplayName("delete → read null（撤销后不可复用）")
    void deleteThenReadNull() {
        AccountOAuthToken t = sampleToken("github", "carol");
        service.save(t);
        service.delete("github", "carol");
        assertThat(service.read("github", "carol"))
            .as("delete 后 read 必须 null（认证链不再复用已撤销凭据）")
            .isNull();
    }

    @Test
    @DisplayName("不同 provider 或不同 identity 相互隔离（复合键隔离语义）")
    void differentAccountsIsolated() {
        service.save(sampleToken("github", "alice"));
        service.save(sampleToken("gitlab", "alice"));

        assertThat(service.read("github", "bob"))
            .as("github|alice 不串读到 github|bob（identity 隔离）").isNull();
        assertThat(service.read("gitlab", "alice"))
            .as("gitlab|alice 独立读回（provider 隔离）").isNotNull();
        assertThat(service.read("gitlab", "alice").getAccessToken()).isEqualTo("access-alice");
    }

    @Test
    @DisplayName("null-safety：read/delete 静默 null，save 缺复合键 fail-loud 抛 IllegalArgumentException")
    void nullSafety() {
        AccountOAuthToken t = new AccountOAuthToken();
        assertThat(service.read(null, "x")).as("provider null 静默返回 null").isNull();
        assertThat(service.read("github", null)).as("identity null 静默返回 null").isNull();
        // save fail-loud：复合键不完整即编程错误（禁止落无键 token，反查永远可命中）
        assertThatThrownBy(() -> service.save(null))
            .as("save(null) 必须抛而非静默 no-op").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(t))
            .as("save(provider/identity 全 null) 必须抛而非静默 no-op").isInstanceOf(IllegalArgumentException.class);
        AccountOAuthToken noIdentity = new AccountOAuthToken();
        noIdentity.setProvider("github");
        noIdentity.setIdentity(null);
        assertThatThrownBy(() -> service.save(noIdentity))
            .as("save(identity=null 但 provider 非 null) 必须抛（复合键第二半缺失）")
            .isInstanceOf(IllegalArgumentException.class);
        service.delete(null, "x");
        service.delete("github", null);
        assertThat(service.read("github", "x")).as("无键 delete 不产生残留").isNull();
    }

    @Test
    @DisplayName("refreshToken=null + expiresAt=null 保存读回不被默认值/非空约束改写（GitHub 无 refresh/不过期语义）")
    void githubNoRefreshNoExpiry() {
        AccountOAuthToken t = new AccountOAuthToken();
        t.setProvider("github");
        t.setIdentity("dave");
        t.setAccessToken("access-dave");
        t.setRefreshToken(null);
        t.setExpiresAt(null);
        t.setScope("repo");
        service.save(t);

        AccountOAuthToken got = service.read("github", "dave");
        assertThat(got).as("GitHub 无 refresh/不过期 token 必须可保存读回").isNotNull();
        assertThat(got.getAccessToken()).isEqualTo("access-dave");
        assertThat(got.getRefreshToken()).as("refreshToken 保持 null（不被改写）").isNull();
        assertThat(got.getExpiresAt()).as("expiresAt 保持 null（NULL=不过期，对齐 CC isOAuthTokenExpired(null)→false）").isNull();
        assertThat(got.getScope()).isEqualTo("repo");
    }

    @Test
    @DisplayName("readLatest(provider)：无记录返回 null，有记录返回最近更新（S6 RemoteTriggerTool 无 identity 读账号级 token）")
    void readLatestReturnsMostRecent() throws InterruptedException {
        // 无记录 → null（RemoteTriggerTool 无 token 场景）
        assertThat(service.readLatest("github")).as("无记录 readLatest 必须 null").isNull();

        // 单账号（CC 单 provider 单账号等价）→ 读回该 token
        service.save(sampleToken("github", "alice"));
        assertThat(service.readLatest("github").getIdentity())
            .as("单账号 readLatest 直接读回该 token").isEqualTo("alice");

        // 多账号并存 → updated_at 最新者胜出
        Thread.sleep(5);  // 确保 updated_at 时间戳递增，orderBy(updated_at DESC) 确定性
        service.save(sampleToken("github", "newer"));
        assertThat(service.readLatest("github").getIdentity())
            .as("多账号 readLatest 必须取 updated_at 最新（newer）").isEqualTo("newer");

        // 其他 provider 隔离
        assertThat(service.readLatest("gitlab")).as("gitlab 无记录 readLatest null").isNull();
    }
}
