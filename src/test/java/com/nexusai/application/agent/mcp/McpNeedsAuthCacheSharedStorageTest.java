package com.nexusai.application.agent.mcp;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.domain.mcp_oauth.McpNeedsAuthCacheStore;
import com.nexusai.repository.mcp_oauth.mapper.McpNeedsAuthCacheMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [R2-3 跨实例共享] needs-auth 缓存 DB 兜底一致性测试（对齐 CC mcp-needs-auth-cache.json 文件共享语义）。
 *
 * <p><b>WHY (意图验证)</b>: R1 的 {@link McpNeedsAuthCache} 是纯内存 ConcurrentHashMap —— 单实例
 * 部署下 401 → 缓存置位 → 后续批连接跳过（对齐 CC isMcpAuthCached client.ts:280-287）。但多实例
 * 部署（共享 DB）下实例 A 置位的 needs-auth 对实例 B 不可见 → 实例 B 每次批连接都对同一 401
 * server 重复网络往返。R2-3 引入 {@link McpNeedsAuthCacheStore}（mcp_needs_auth_cache 表）作
 * 共享兜底：实例 A setCached 写穿 DB → 实例 B isCached 读共享源命中。本测试用两个独立
 * {@link McpNeedsAuthCache} 实例 + 同一 store（共享 DB）模拟「两个 JVM 实例」，验证：
 * <ol>
 *   <li>实例 A 置位 → 实例 B 命中（跨实例读共享源优先）</li>
 *   <li>任一实例 clear → 其它实例缓存失效（对齐 CC clearMcpAuthCache unlink 整文件）</li>
 *   <li>实例 B 用 DB 共享 cachedAt 判定 TTL（非本机 now），过期时间戳 → 不命中</li>
 *   <li>store=null → 纯内存（R1 语义不变，未接线共享存储不抛）</li>
 * </ol>
 */
class McpNeedsAuthCacheSharedStorageTest {

    @TempDir
    static Path tempDir;

    private static McpNeedsAuthCacheMapper mapper;
    private static McpNeedsAuthCacheStore store;

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

        // 本类只绑 McpNeedsAuthCacheMapper（其它表在 Flyway 建好，未映射不读写）
        MybatisFlexDbTestSupport.resetAndStart(ds, McpNeedsAuthCacheMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(McpNeedsAuthCacheMapper.class);

        store = new McpNeedsAuthCacheStore();
        ReflectionTestUtils.setField(store, "mcpNeedsAuthCacheMapper", mapper);
    }

    @BeforeEach
    void clean() {
        mapper.clearAll();
    }

    /** 模拟一个 JVM 实例的缓存（共享 store → 同一 DB）。 */
    private McpNeedsAuthCache instance() {
        McpNeedsAuthCache cache = new McpNeedsAuthCache();
        cache.setStore(store);
        return cache;
    }

    // ───────────── 跨实例读共享源优先（实例 A 置位 → 实例 B 命中）─────────────

    /**
     * WHY：多实例部署下实例 A 连接期 401 置位（CC setMcpAuthCacheEntry client.ts:293-309），
     * 实例 B 必须能在 TTL 内跳过对同一 server 的连接（CC isMcpAuthCached client.ts:280-287）——
     * 否则实例 B 每 15min 重复一次 connect-401 + OAuth discovery 网络往返（client.ts:2301-2314）。
     */
    @Test
    @DisplayName("实例 A setCached → 实例 B isCached 命中（共享 DB 兜底）")
    void instanceA_set_instanceB_readsShared() {
        McpNeedsAuthCache instanceA = instance();
        McpNeedsAuthCache instanceB = instance();

        instanceA.setCached("srv-http");

        assertThat(instanceB.isCached("srv-http")).isTrue();
        // 写穿 DB 落行（证明非仅本机内存）
        assertThat(mapper.selectOneById("srv-http")).isNotNull();
    }

    // ───────────── clear 全量失效（对齐 CC unlink 整文件）─────────────

    /**
     * WHY：CC clearMcpAuthCache 对整个文件 unlink（client.ts:311-316）→ 所有 server 的 needs-auth
     * 缓存一并失效（OAuth 认证成功 McpAuthTool.ts:139）。任一实例 clear 后，其它实例也必须看到
     * 失效——否则已认证 server 在实例 B 仍被跳过连接。
     */
    @Test
    @DisplayName("实例 A clear → 实例 B isCached 失效（共享存储全量清除）")
    void clear_onOneInstance_invalidatesOthers() {
        McpNeedsAuthCache instanceA = instance();
        McpNeedsAuthCache instanceB = instance();
        instanceA.setCached("srv-http");
        assertThat(instanceB.isCached("srv-http")).isTrue();

        instanceA.clear();

        assertThat(instanceB.isCached("srv-http")).isFalse();
        // DB 行也清空（对齐 CC unlink 文件语义）
        assertThat(mapper.selectAll()).isEmpty();
    }

    // ───────────── TTL 判定用共享 cachedAt（非本机 now）─────────────

    /**
     * WHY：实例 B 读共享源后若用本机 now 而非共享 cachedAt 判 TTL，实例 B 的 TTL 窗口会从
     * 「读到时刻」重新起算 → 实际缓存时长翻倍（实例 A 的 15min + 实例 B 的 15min）。对齐 CC
     * 文件语义：isMcpAuthCached 用 entry.timestamp 判 TTL（client.ts:286），DB 兜底必须同样
     * 用共享 cachedAt。直接向 DB 写「已过期」时间戳 → 实例 B 必须判定不命中。
     */
    @Test
    @DisplayName("共享源过期时间戳 → 实例 B 不命中（TTL 用共享 cachedAt 而非本机 now）")
    void expiredSharedTimestamp_instanceBNotCached() {
        McpNeedsAuthCache instanceB = instance();
        long expired = System.currentTimeMillis() - McpNeedsAuthCache.TTL_MS - 1000L;
        store.save(new com.nexusai.model.mcp_oauth.McpNeedsAuthEntry("srv-http", expired));

        assertThat(instanceB.isCached("srv-http")).isFalse();
    }

    // ───────────── store=null → 纯内存（R1 语义不变）─────────────

    /**
     * WHY：未接线共享存储（测试 new 实例 / Spring 未注入）必须保持 R1 纯内存行为且不抛——
     * McpToolPool 惰性自建实例（needsAuth()）与既有单实例测试依赖该路径。store=null 时
     * setCached/isCached/clear 均正常（本地读写，无 DB 依赖）。
     */
    @Test
    @DisplayName("store=null → 纯内存缓存（R1 语义不变，不抛）")
    void noStore_pureInMemory() {
        McpNeedsAuthCache cache = new McpNeedsAuthCache();

        assertThat(cache.isCached("srv-http")).isFalse();
        cache.setCached("srv-http");
        assertThat(cache.isCached("srv-http")).isTrue();
        cache.clear();
        assertThat(cache.isCached("srv-http")).isFalse();
    }
}
