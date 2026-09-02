package com.nexusai.repository.mcp_channel_allowlist;

import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.core.mybatis.FlexSqlSessionFactoryBuilder;
import com.nexusai.domain.mcp_channel_allowlist.ChannelAllowlistService;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import com.nexusai.repository.mcp_channel_allowlist.mapper.ChannelAllowlistMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q-37 ledger 白名单 DB 表 CRUD 意图测试（impl-I-3 T3）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: Q-37 拍板把 CC GrowthBook 白名单
 * （channelAllowlist.ts:37-44）落 {@code mcp_channel_allowlist} DB 表。本测试验证
 * create → listAll 命中 → isAllowed true → delete → isAllowed false 全链路落库，
 * 而非仅内存——重启后 selectAll 仍可按 DB 行辨识白名单（R-1 僵尸类缺陷防线）。
 *
 * <p>用 MybatisFlexBootstrap（无 Spring）直连临时 SQLite + Flyway V1..V11，
 * 不启动完整应用上下文。注意 MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=ChannelAllowlistServiceTest test}）。
 */
class ChannelAllowlistServiceTest {

    @TempDir
    static Path tempDir;

    private static ChannelAllowlistMapper mapper;
    private static ChannelAllowlistService service;
    private static SqlSession session;

    @BeforeAll
    static void setUpDatabase() {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("channel-allowlist.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(dbUrl);

        // Flyway 迁移 V1..V11（V11 为 mcp_channel_allowlist 表；V10 编号被兄弟 worktree
        // mcp-i1-config 的 mcp_servers type/approval 占用，本表迁移号协调为 V11）
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        // 独立 SqlSessionFactory（不用 MybatisFlexBootstrap 单例——全仓 ScheduleServiceCreateStorageTest
        // 也占该单例，串跑会因已 start 而忽略 addMapper → BindingException。独立 factory 与其它
        // Flex 测试类共存）。
        FlexConfiguration configuration = new FlexConfiguration();
        // MyBatis-Flex 要求 FlexDataSource 包装（ClassCastException 直接暴露，不静默）
        com.mybatisflex.core.datasource.FlexDataSource flexDs =
            new com.mybatisflex.core.datasource.FlexDataSource("primary", ds);
        configuration.setEnvironment(new Environment("dev", new JdbcTransactionFactory(), flexDs));
        configuration.addMapper(ChannelAllowlistMapper.class);
        SqlSessionFactory factory = new FlexSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);   // autocommit: 每条语句独立提交（SQLite 写锁即时释放）
        mapper = session.getMapper(ChannelAllowlistMapper.class);

        service = new ChannelAllowlistService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mapper", mapper);
    }

    /** 释放 SQLite 连接 → @TempDir 清理能删除 db 文件（否则文件锁致删除失败）。 */
    @AfterAll
    static void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        var rows = mapper.selectAll();
        if (!rows.isEmpty()) {
            mapper.deleteBatchByIds(rows.stream().map(r -> r.getId()).toList());
        }
    }

    @Test
    @DisplayName("create → listAll 命中 → isAllowed true → delete → isAllowed false（落库非仅内存）")
    void createListIsAllowedDelete_fullCycle() {
        ChannelAllowlistEntry created = service.create("anthropic", "slack");
        assertThat(created.marketplace()).isEqualTo("anthropic");
        assertThat(created.plugin()).isEqualTo("slack");

        // 直接读 DB（绕过 service 内存缓存反查）→ 行已落库
        List<ChannelAllowlistEntry> rows = service.listAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).marketplace()).isEqualTo("anthropic");
        assertThat(rows.get(0).plugin()).isEqualTo("slack");
        assertThat(rows.get(0).createdAt()).as("createdAt 必须落库（V11 DEFAULT datetime('now')）")
            .isNotNull();

        // CC channelAllowlist.ts 纯比对（channelNotification.ts:288-290 gate allowlist 步）
        assertThat(service.isAllowed("anthropic", "slack")).isTrue();
        assertThat(service.isAllowed("anthropic", "evil")).isFalse();

        // 删除（用 DB 真 id）→ 白名单失效
        com.nexusai.repository.mcp_channel_allowlist.entity.ChannelAllowlistRecord rec =
            mapper.selectAll().get(0);
        service.delete(rec.getId());
        assertThat(service.listAll()).isEmpty();
        assertThat(service.isAllowed("anthropic", "slack")).isFalse();
    }

    @Test
    @DisplayName("isAllowlisted(pluginSource) 按 CC pluginIdentifier.ts:51-57 语义比对")
    void isAllowlisted_matchesCcParseSemantics() {
        service.create("anthropic", "slack");

        // CC 真源: 'slack@anthropic' → {name:'slack', marketplace:'anthropic'} → 命中
        assertThat(service.isAllowlisted("slack@anthropic")).isTrue();
        // bare 无 marketplace → false（channelAllowlist.ts:70-72）
        assertThat(service.isAllowlisted("slack")).isFalse();
        // null / empty → false
        assertThat(service.isAllowlisted(null)).isFalse();
        assertThat(service.isAllowlisted("")).isFalse();
    }

    @Test
    @DisplayName("UNIQUE(marketplace, plugin) 约束 + 空参校验 fail-loud")
    void duplicateOrBlank_areRejected() {
        service.create("anthropic", "slack");
        // 重复 {marketplace, plugin} → SQLite UNIQUE 约束抛异常（fail-loud，不静默覆盖）
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create("anthropic", "slack"))
            .as("重复 {marketplace, plugin} 必须抛异常（UNIQUE 约束，fail-loud）")
            .isInstanceOf(Exception.class);
        // 空 marketplace / plugin → IllegalArgumentException（fail-loud）
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> service.create(null, "slack"));
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> service.create("anthropic", ""));
    }
}
