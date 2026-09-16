package com.nexusai.repository.mcp_channel_allowlist;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.domain.mcp_channel_allowlist.ChannelAllowlistService;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import com.nexusai.repository.mcp_channel_allowlist.mapper.ChannelAllowlistMapper;
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
 * <p>用 MybatisFlexBootstrap（无 Spring）直连<b>共享稳定</b> SQLite + Flyway V1..V74，
 * 不启动完整应用上下文。
 *
 * <p><b>[E5 · 本段原文已过时]</b> 原文写「注意 MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=ChannelAllowlistServiceTest test}）」并自建
 * {@code FlexConfiguration + FlexDataSource + new FlexSqlSessionFactoryBuilder().build(...)}、
 * 走独立 {@code @TempDir} 库。该写法<b>已废弃</b>：
 * <ol>
 *   <li><b>改写全局静态</b>：{@code FlexSqlSessionFactoryBuilder.build(Configuration)} 内部调
 *       {@code initGlobalConfig} ⇒ {@code FlexGlobalConfig.setSqlSessionFactory} /
 *       {@code setConfiguration}（字节码实测：{@code build(Configuration)} 偏移 36 →
 *       {@code initGlobalConfig}）⇒ <b>绕过全仓约定的</b>
 *       {@code MybatisFlexDbTestSupport.resetAndStart} <b>单例重置</b>，并在
 *       {@code MybatisFlexBootstrap.start()} 的 {@code started.compareAndSet(false,true)} 门禁下
 *       留下「已 start、mapper 集合却不同」的单例状态。
 *       ⚠️ <b>照实声明</b>：批 P2 用 legacy 写法探针类与本类同 JVM 混跑（alphabetical /
 *       reversealphabetical 两种顺序）<b>未能复现红灯</b>（aligned 类的 {@code resetAndStart}
 *       会自愈）⇒ 本条是<b>隐患</b>而非可复现的现存缺陷；本件价值 = 统一范式，⛔ 非修红。</li>
 *   <li><b>本仓已有正范式</b>：其余 22 个 Flex DB 测试类一律走
 *       {@link MybatisFlexDbTestSupport#resetAndStart} + {@link MybatisFlexDbTestSupport#sharedDbPath()}
 *       （重置全局单例 / mapper 代理缓存后复用同一稳定库），本类原是全仓<b>唯一漏网</b>。</li>
 * </ol>
 * <p>autocommit 语义<b>不变</b>：bootstrap 路径 {@code Mappers$MapperHandler.openSession()} 走
 * {@code SqlSessionFactory.openSession(ExecutorType, true)} —— 字节码实测在压入
 * {@code executorType} 后紧接 {@code iconst_1}（即 autoCommit=true）⇒ 每条语句独立提交，
 * SQLite 写锁即时释放，与原先的 {@code factory.openSession(true)} 等价。
 * 现与其它 Flex 测试类<b>可混跑</b>。
 */
class ChannelAllowlistServiceTest {

    private static ChannelAllowlistMapper mapper;
    private static ChannelAllowlistService service;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // 共享稳定 DB + 重置 MyBatis-Flex 全局状态（mapper 代理缓存/单例），避免跨测试类冲突
        // （见 MybatisFlexDbTestSupport）。
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        // Flyway 迁移 V1..V74（V11 为 mcp_channel_allowlist 表；V10 编号被兄弟 worktree
        // mcp-i1-config 的 mcp_servers type/approval 占用，本表迁移号协调为 V11）
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, ChannelAllowlistMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(ChannelAllowlistMapper.class);

        service = new ChannelAllowlistService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
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
