package com.nexusai.domain.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.model.settings.dto.SettingsResponse;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [SendMessage 跨会话 · E 设置持久化] {@code settings.cross_session_inbound} 的
 * <b>真 SQLite 三态合同</b>（直接构造 SettingsService，不加载 Spring 上下文）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：这一列的全部要害是「三态不能被 merge 语义吞掉」——
 * {@link SettingsService#update} 整体是「null = 不覆盖」的 PATCH：
 * <ul>
 *   <li>省略字段必须保持旧值（前端只改一个开关时，别的设置不能被清空）；</li>
 *   <li>显式 {@code auto} 必须把列写成 <b>NULL</b>（= 运行时按权限模式自动），
 *       若只把内存实体置 null 而不 {@code update(entity, false)}，DB 会留旧值 ⇒
 *       用户点「自动」后实际仍是「拒绝」（本仓 subagent_default_runtime 同款前科）；</li>
 *   <li>非法值必须 400 且不改列（Fail loud，不静默折叠）。</li>
 * </ul>
 * 这些都只能在真实列上取证：mock mapper 只能证明「调了哪个重载」，证明不了「列变成了 NULL」。
 *
 * <p><b>迁移验证</b>：Flyway 直接读 {@code src/main/resources/db/migration}（真文件，非
 * 编译产物），断言 V321 历史 success=1 且列存在于真实 DDL；反向对照先迁到 V302（列不在）
 * 再迁到最新（列在），排除「列本来就有」的等价解释。
 *
 * <p><b>JSON 实际映射读回</b>：PUT/GET 共用 {@link SettingsDto}，用真实 ObjectMapper 走
 * 「省略键 / auto / accept」→ DTO → GET 序列化 → {@link SettingsResponse} 扁平化的整链，
 * 防止「字段加了但 Jackson 读写不通」这类静默失败。
 *
 * <p>⛔ 不启动 Spring、不触碰真 configHome；数据库是临时文件（target/flex-dbtest 专属文件）。
 */
@DisplayName("[V321 peer-settings] crossSessionInbound 三态 · 真 sqlite")
class SettingsServicePeerInboundTest {

    private static final String COLUMN = "cross_session_inbound";

    private static final Path DB_PATH = Path.of("target", "flex-dbtest", "settings-peer-inbound.db");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SettingsMapper mapper;
    private static SettingsService service;
    private static SQLiteDataSource dataSource;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // 专属文件启动即删：保证列初值 null（历史残留会导致「GET 返回 null」断言假失败）
        Files.deleteIfExists(DB_PATH);
        Files.deleteIfExists(DB_PATH.resolveSibling(DB_PATH.getFileName() + "-wal"));
        Files.deleteIfExists(DB_PATH.resolveSibling(DB_PATH.getFileName() + "-shm"));
        Files.createDirectories(DB_PATH.getParent());

        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
        migrateFully(dataSource);

        MybatisFlexDbTestSupport.resetAndStart(dataSource, SettingsMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(SettingsMapper.class);

        // 直接 new（POJO），只注入 mapper；@PostConstruct 不触发 ⇒ 不连接任何生产配置根。
        service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
    }

    @AfterEach
    void clearColumn() {
        SettingsRecord s = mapper.selectOneById(1);
        if (s != null) {
            s.setCrossSessionInbound(null);
            // 显式写 NULL：MyBatis-Flex 默认 update(entity) 会跳过 null 字段
            mapper.update(s, false);
        }
    }

    // ══════════════════════════ 迁移 ══════════════════════════

    @Test
    @DisplayName("V321 应用后：历史 success=1，真实 DDL 有 cross_session_inbound（TEXT、可空）")
    void v321_applied_columnExistsNullableText() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH.toAbsolutePath())) {
            assertThat(historySuccess(conn, "321"))
                .as("flyway_schema_history 记录 V321 且 success=1").isEqualTo(1);

            ColumnDef def = findColumn(conn, COLUMN);
            assertThat(def).as("V321 ALTER 后 settings 表必须有 cross_session_inbound").isNotNull();
            // SQLite 的 PRAGMA type 原样返回声明的类型
            assertThat(def.type).as("必须是 TEXT（nullable 策略/哨兵值语义）").isEqualTo("TEXT");
            assertThat(def.notNull).as("必须可空：NULL = 未显式配置（运行时 auto）").isZero();
        }
    }

    @Test
    @DisplayName("反向对照：迁到 V302 时列『不在』→ 再迁到最新后列『在』（同一库，只差 V321）")
    void columnPresentOnlyAfterV321Applied(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("peer-inbound-ab.db");

        migrateTo(dbFile, "302");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            assertThat(historySuccess(conn, "321"))
                .as("V321 未应用时历史表不应有 V321 行").isEqualTo(-1);
            assertThat(findColumn(conn, COLUMN))
                .as("反向对照前提：V302 状态下该列必须【不在】（否则对照无意义）").isNull();
        }

        migrateFully(dbFile);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            assertThat(historySuccess(conn, "321")).isEqualTo(1);
            assertThat(findColumn(conn, COLUMN))
                .as("应用 V321 后该列必须【在】—— 两轮只差『V321 是否应用』").isNotNull();
        }
    }

    // ══════════════════════════ 三态 ══════════════════════════

    @Test
    @DisplayName("默认：新库列为 NULL；GET 返回 null（= 未设置，运行时 auto，不默认 accept）")
    void freshRow_columnIsNull_getReturnsNull() throws Exception {
        assertThat(rawColumn()).as("新库 cross_session_inbound 初始 NULL").isNull();
        assertThat(service.get().crossSessionInbound()).as("GET 必须原样返回 null").isNull();
    }

    @Test
    @DisplayName("PUT 省略字段 ⇒ 保持旧值（不被 PATCH 的 null 语义清掉）")
    void omittedField_keepsExistingValue() throws Exception {
        setRaw("hold");

        SettingsDto updated = service.update(dto("{}"));

        assertThat(rawColumn()).as("省略字段不得改动真实列").isEqualTo("hold");
        assertThat(updated.crossSessionInbound()).as("PUT 响应 = 落库后真实值").isEqualTo("hold");
        assertThat(service.get().crossSessionInbound()).isEqualTo("hold");
    }

    @Test
    @DisplayName("显式 auto ⇒ 真实列被写成 NULL（不是留在旧值），GET 读回 null")
    void explicitAuto_clearsColumnToNull() throws Exception {
        setRaw("accept");

        SettingsDto updated = service.update(dto("{\"crossSessionInbound\":\"auto\"}"));

        assertThat(rawColumn())
            .as("auto 必须把列写成 NULL；留旧值 ⇒ 用户点「自动」后实际仍是 accept（静默失败）")
            .isNull();
        assertThat(updated.crossSessionInbound()).as("PUT 响应必须是 null（前端据此显示「自动」）").isNull();
        assertThat(service.get().crossSessionInbound()).isNull();
    }

    @Test
    @DisplayName("accept / hold / refuse ⇒ 真实列与 GET 均往返一致")
    void explicitValues_roundTripThroughRealColumn() throws Exception {
        for (String v : List.of("accept", "hold", "refuse")) {
            SettingsDto updated = service.update(dto("{\"crossSessionInbound\":\"" + v + "\"}"));

            assertThat(rawColumn()).as("显式 %s 必须落真实列", v).isEqualTo(v);
            assertThat(updated.crossSessionInbound()).as("PUT 响应 = %s", v).isEqualTo(v);
            assertThat(service.get().crossSessionInbound()).as("GET 读回 = %s", v).isEqualTo(v);
        }
    }

    @Test
    @DisplayName("非法值（bogus / 空串）⇒ ValidationException(400)，且真实列保持原值")
    void invalidValue_rejectedAndColumnUnchanged() throws Exception {
        setRaw("refuse");

        for (String bad : List.of("bogus", "")) {
            assertThatThrownBy(() -> service.update(dto("{\"crossSessionInbound\":\"" + bad + "\"}")))
                .as("非法值 %s 必须 fail loud（不能被静默接受/折叠）", bad)
                .isInstanceOf(ValidationException.class);
            assertThat(rawColumn()).as("非法值 %s 不得改列", bad).isEqualTo("refuse");
        }
    }

    // ══════════════════════════ JSON 实际映射读回 ══════════════════════════

    @Test
    @DisplayName("JSON 链：省略键→null、accept→accept；GET 序列化 + SettingsResponse 扁平化读回一致")
    void jsonMapping_roundTripsThroughDtoAndUnwrappedResponse() throws Exception {
        // PUT 请求体：只有 crossSessionInbound
        SettingsDto req = JSON.readValue("{\"crossSessionInbound\":\"accept\"}", SettingsDto.class);
        assertThat(req.crossSessionInbound()).isEqualTo("accept");

        service.update(req);
        SettingsDto got = service.get();

        JsonNode dtoJson = JSON.readTree(JSON.writeValueAsString(got));
        assertThat(dtoJson.get("crossSessionInbound").asText())
            .as("SettingsDto 出站 JSON 必须带 crossSessionInbound").isEqualTo("accept");

        // SettingsResponse 已 @JsonUnwrapped：内层字段必须摊平到顶层
        JsonNode resJson = JSON.readTree(
            JSON.writeValueAsString(new SettingsResponse(got, "nexusai", "C:/tmp/config")));
        assertThat(resJson.get("crossSessionInbound").asText())
            .as("GET 响应（扁平化）必须能读到 crossSessionInbound").isEqualTo("accept");
        assertThat(resJson.has("settings")).as("不得出现嵌套 settings 壳").isFalse();

        // 反序列化回 PUT 入参类型不报错，值保留
        SettingsDto back = JSON.readValue(JSON.writeValueAsString(got), SettingsDto.class);
        assertThat(back.crossSessionInbound()).isEqualTo("accept");
    }

    // ══════════════════════════ helpers ══════════════════════════

    /** 只带 crossSessionInbound 的 PUT 请求体（其余字段省略 = null，走真实 JSON 反序列化）。 */
    private static SettingsDto dto(String json) throws Exception {
        return JSON.readValue(json, SettingsDto.class);
    }

    private static void setRaw(String value) {
        SettingsRecord s = mapper.selectOneById(1);
        assertThat(s).as("settings singleton 行必须存在（V1 已 INSERT）").isNotNull();
        s.setCrossSessionInbound(value);
        mapper.update(s);
    }

    private String rawColumn() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT cross_session_inbound FROM settings WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 迁移目录：真源文件（支持从 backend/ 或仓库根启动测试）。 */
    private static Path migrationDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p : List.of(cwd.resolve("src/main/resources/db/migration"),
                              cwd.resolve("backend/src/main/resources/db/migration"))) {
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("找不到 db/migration（cwd=" + cwd + "）");
    }

    private static Flyway flyway(Path dbFile) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        String location = "filesystem:" + migrationDir().toAbsolutePath().toString().replace('\\', '/');
        return Flyway.configure().dataSource(ds).locations(location).baselineOnMigrate(true).load();
    }

    private static void migrateFully(SQLiteDataSource ds) {
        String location = "filesystem:" + migrationDir().toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure().dataSource(ds).locations(location).baselineOnMigrate(true).load().migrate();
    }

    private static void migrateFully(Path dbFile) {
        flyway(dbFile).migrate();
    }

    private static void migrateTo(Path dbFile, String version) {
        Flyway.configure()
            .dataSource(dsFor(dbFile))
            .locations("filesystem:" + migrationDir().toAbsolutePath().toString().replace('\\', '/'))
            .baselineOnMigrate(true)
            .target(MigrationVersion.fromVersion(version))
            .load()
            .migrate();
    }

    private static SQLiteDataSource dsFor(Path dbFile) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return ds;
    }

    private static int historySuccess(Connection conn, String version) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT success FROM flyway_schema_history WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static ColumnDef findColumn(Connection conn, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(settings)")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<String> all = new ArrayList<>();
                while (rs.next()) {
                    String col = rs.getString("name");
                    all.add(col);
                    if (name.equals(col)) {
                        return new ColumnDef(rs.getString("type"), rs.getInt("notnull"));
                    }
                }
                assertThat(all).as("PRAGMA table_info(settings) 应返回真实列集合").isNotEmpty();
                return null;
            }
        }
    }

    private record ColumnDef(String type, int notNull) {}
}
