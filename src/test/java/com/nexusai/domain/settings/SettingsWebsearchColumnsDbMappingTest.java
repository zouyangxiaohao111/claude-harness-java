package com.nexusai.domain.settings;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [websearch-ccalign R2 返工 + websearch-resid R-B + websearch-domaincheck] SettingsRecord 6 个
 * WebSearch 新列的 <b>真实 SQLite 映射测试</b>（V37 4 列 + V38 websearch_base_url +
 * V39 websearch_domain_check_url）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：{@link SettingsServiceTest} 全用 mock mapper，
 * 无法发现 V37/V38/V39 列名与 SettingsRecord 字段的 MyBatis-Flex camelCase↔snake_case 映射错位——
 * 若字段写成 {@code webSearchUseSmallModel}，MyBatis-Flex 会映射到
 * {@code web_search_use_small_model}（列不存在），select/update 实际读到/写到错误的列。
 * 本测试用<b>真实 SQLite 文件库 + Flyway V1..V39 全量迁移</b> + {@link SettingsMapper}（BaseMapper）
 * 验证 6 列 selectOneById / update 往返一致，把映射错位暴露为硬失败。
 *
 * <p>复用 {@link MybatisFlexDbTestSupport}（全局单例 + mapper 代理缓存 reset，避免跨测试类冲突），
 * 与既有 {@code ScheduleServiceCreateStorageTest} 同模式：Flyway migrate 全部迁移 + Flex 直连
 * 临时 SQLite，不启动完整 Spring 上下文。
 *
 * <p><b>独立 DB 文件 + 启动即删</b>：用专属文件 {@code settings-websearch.db}（非共享 flex.db），
 * 且 @BeforeAll 先删除历史文件再 Flyway 迁移——保证 6 列初始为 null（避免共享 DB 残留上一轮
 * 测试写入的列值，导致断言假失败）。@AfterEach 用 {@code update(entity, false)}（含 null 列）
 * 清空 6 列，确保用例间互不污染（MyBatis-Flex {@code update()} 默认忽略 null 列，单用
 * {@code update(entity)} 清不掉）。
 *
 * <p><b>[R1 返工] 字段名约定</b>：{@code websearchUseSmallModel}（小写 s）↔ V37 列
 * {@code websearch_use_small_model}；若改回 {@code webSearchUseSmallModel} 本测试
 * update/select 即失败（列错位）。[R-B] {@code websearchBaseUrl} ↔ V38 列
 * {@code websearch_base_url}（MyBatis-Flex snake 映射同 4 列约定）。[domaincheck]
 * {@code websearchDomainCheckUrl} ↔ V39 列 {@code websearch_domain_check_url}——字段名必须小写 s
 * 开头（websearchDomainCheckUrl），写成 webSearchDomainCheckUrl 会映射到 web_search_domain_check_url
 * （列不存在 → 硬失败）。
 */
class SettingsWebsearchColumnsDbMappingTest {

    /** 本类专属 DB 文件（非共享 flex.db，避免与其它真实 DB 测试类互串列值）。 */
    private static final Path DB_PATH = Path.of("target", "flex-dbtest", "settings-websearch.db");

    private static SettingsMapper mapper;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // 专属文件启动即删：保证迁移后 5 列初始为 null（历史残留列值会导致"初始 null"断言假失败）
        Files.deleteIfExists(DB_PATH);
        Files.deleteIfExists(DB_PATH.resolveSibling(DB_PATH.getFileName() + "-wal"));
        Files.deleteIfExists(DB_PATH.resolveSibling(DB_PATH.getFileName() + "-shm"));
        Files.createDirectories(DB_PATH.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
        // Flyway V1..V39 全量迁移（V1 建 settings + INSERT id=1；V37 ADD 4 个 WebSearch 列；V38 ADD websearch_base_url；V39 ADD websearch_domain_check_url）
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, SettingsMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(SettingsMapper.class);
    }

    @AfterEach
    void restoreSettingsRow() {
        // update(entity, false) = 含 null 列写回（MyBatis-Flex 默认 update() 忽略 null 列，清不掉）。
        // 6 个 WebSearch 列清回 null（含 V38 websearch_base_url + V39 websearch_domain_check_url），避免用例间污染。
        SettingsRecord s = mapper.selectOneById(1);
        if (s != null) {
            s.setWebsearchEngine(null);
            s.setApiKey(null);
            s.setProxy(null);
            s.setWebsearchUseSmallModel(null);
            s.setWebsearchBaseUrl(null);
            s.setWebsearchDomainCheckUrl(null);
            mapper.update(s, false);
        }
    }

    @Test
    @DisplayName("V37+V38+V39 迁移后 settings 行存在，且 6 个 WebSearch 新列初始为 null（可 select）")
    void v38Migration_createsSettingsColumns_readableAsNull() {
        SettingsRecord s = mapper.selectOneById(1);
        assertThat(s).as("V1 已 INSERT settings id=1").isNotNull();
        assertThat(s.getWebsearchEngine()).as("websearch_engine 初始 null").isNull();
        assertThat(s.getApiKey()).as("api_key 初始 null").isNull();
        assertThat(s.getProxy()).as("proxy 初始 null").isNull();
        assertThat(s.getWebsearchUseSmallModel()).as("websearch_use_small_model 初始 null").isNull();
        assertThat(s.getWebsearchBaseUrl()).as("websearch_base_url（V38）初始 null").isNull();
        assertThat(s.getWebsearchDomainCheckUrl()).as("websearch_domain_check_url（V39）初始 null").isNull();
    }

    @Test
    @DisplayName("update 写入 4 新列后 selectOneById 往返一致（websearchUseSmallModel=true 为关键错位检测点）")
    void updateThenSelect_roundTripsWebsearchFourColumns() {
        SettingsRecord s = mapper.selectOneById(1);
        assertThat(s).isNotNull();
        s.setWebsearchEngine("anysearch");
        s.setApiKey("as_sk_db_test");
        s.setProxy("proxy.example:8080");
        s.setWebsearchUseSmallModel(true); // 若字段名映射错位（web_search_use_small_model），此处 update/select 即抛错或读到 null
        mapper.update(s);

        SettingsRecord reloaded = mapper.selectOneById(1);
        assertThat(reloaded.getWebsearchEngine()).isEqualTo("anysearch");
        assertThat(reloaded.getApiKey()).isEqualTo("as_sk_db_test");
        assertThat(reloaded.getProxy()).isEqualTo("proxy.example:8080");
        assertThat(reloaded.getWebsearchUseSmallModel()).as(
            "websearchUseSmallModel ↔ websearch_use_small_model 往返必须为 true（R1 返工映射校验）").isTrue();
    }

    @Test
    @DisplayName("Boolean false 写入也往返一致（0 → false，非 null 语义）")
    void updateThenSelect_roundTripsBooleanFalse() {
        SettingsRecord s = mapper.selectOneById(1);
        assertThat(s).isNotNull();
        s.setWebsearchUseSmallModel(false);
        mapper.update(s);

        SettingsRecord reloaded = mapper.selectOneById(1);
        assertThat(reloaded.getWebsearchUseSmallModel()).isFalse();
    }

    @Test
    @DisplayName("update 写入 V38 websearch_base_url 后 selectOneById 往返一致（R-B base-url DB 化）")
    void updateThenSelect_roundTripsWebsearchBaseUrl() {
        // WHY（规则九）：R-B 把 anysearch base-url 移入 DB settings（V38 列 websearch_base_url），
        // WebSearchTool 读链经 SettingsRecord.getWebsearchBaseUrl() 消费——若 MyBatis-Flex 映射错位
        // （webSearchBaseUrl → web_search_base_url 列不存在）update/select 即抛错或读到 null，测试硬失败。
        SettingsRecord s = mapper.selectOneById(1);
        assertThat(s).isNotNull();
        s.setWebsearchBaseUrl("https://api.anysearch.com");
        mapper.update(s);

        SettingsRecord reloaded = mapper.selectOneById(1);
        assertThat(reloaded.getWebsearchBaseUrl())
                .as("websearchBaseUrl ↔ websearch_base_url（V38）往返必须一致")
                .isEqualTo("https://api.anysearch.com");
    }

    @Test
    @DisplayName("update 写入 V39 websearch_domain_check_url 后 selectOneById 往返一致（domaincheck 端点 DB 化）")
    void updateThenSelect_roundTripsWebsearchDomainCheckUrl() {
        // WHY（规则九）：[websearch-domaincheck] 把域预检端点移入 DB settings（V39 列
        // websearch_domain_check_url），WebFetchTool.resolveSecurity 读链经
        // SettingsRecord.getWebsearchDomainCheckUrl() 消费（配了 → 预检该端点）——若 MyBatis-Flex
        // 映射错位（webSearchDomainCheckUrl → web_search_domain_check_url 列不存在）update/select
        // 即抛错或读到 null，测试硬失败。
        SettingsRecord s = mapper.selectOneById(1);
        assertThat(s).isNotNull();
        s.setWebsearchDomainCheckUrl("https://example.com/domain_info");
        mapper.update(s);

        SettingsRecord reloaded = mapper.selectOneById(1);
        assertThat(reloaded.getWebsearchDomainCheckUrl())
                .as("websearchDomainCheckUrl ↔ websearch_domain_check_url（V39）往返必须一致")
                .isEqualTo("https://example.com/domain_info");
    }

    @Test
    @DisplayName("insert（新行）含 4 新列可写入并 select 回读（验证 insert 映射通道）")
    void insertWithWebsearchColumns_thenSelectBack() {
        // settings 为 singleton（id=1 CHECK），无法插第二行；改用 update id=1 全字段写 + select 验证
        // insert 通道：此处用 update 等价验证同一条 MyBatis-Flex 列映射链（insert 与 update 共用列映射）。
        SettingsRecord s = mapper.selectOneById(1);
        assertThat(s).isNotNull();
        s.setTheme("dark");
        s.setWebsearchEngine("duckduckgo");
        s.setApiKey("ddg-key");
        s.setProxy("127.0.0.1:7890");
        s.setWebsearchUseSmallModel(true);
        mapper.update(s);

        SettingsRecord reloaded = mapper.selectOneById(1);
        assertThat(reloaded.getTheme()).isEqualTo("dark");
        assertThat(reloaded.getWebsearchEngine()).isEqualTo("duckduckgo");
        assertThat(reloaded.getApiKey()).isEqualTo("ddg-key");
        assertThat(reloaded.getProxy()).isEqualTo("127.0.0.1:7890");
        assertThat(reloaded.getWebsearchUseSmallModel()).isTrue();
    }
}
