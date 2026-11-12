package com.nexusai.kb.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.CreateKnowledgeBaseRequest;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import com.nexusai.kb.dto.UpdateKnowledgeBaseRequest;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V286「知识库图片管线三项」· 迁移 + 读写回环。
 *
 * <h2>WHY（每一条都对应一种「看起来做了、其实没生效」）</h2>
 * <ol>
 *   <li><b>列真的存在且有默认值</b>：ADD COLUMN 写错列名 → 实体 setter 照常「成功」，
 *       落库时 SQL 报错或字段永远 null。</li>
 *   <li><b>默认值 1 / 1 / 'default'</b>：老库升级后必须与改造前行为一致
 *       （改造前恒跑 caption + OCR）；若默认写成 0，全量库会静默停止补图。</li>
 *   <li><b>写 → GET 读回</b>：只写不读（或读侧 DTO 漏字段）⇒ 保存成功但界面永远显示旧值，
 *       用户以为没保存。本类逐字段比对「写什么读回什么」。</li>
 *   <li><b>值域</b>：{@code image_pipeline} 只允许 default / smartocr，其它值 400
 *       —— 存进去一个拼错的值等于「设了但静默走默认管线」。</li>
 * </ol>
 *
 * <p>⛔ 不启 Spring（无 {@code @SpringBootTest}）；迁移用真 SQLite + 真 Flyway，
 * 服务用 {@link MybatisFlexDbTestSupport} 直连同一个测试库。
 */
@DisplayName("[V286] 知识库图片管线三项（迁移 + 创建/更新/读回）")
class KbImagePipelineConfigDbTest {

    private static final String CAPTION_COL = "image_caption_enabled";
    private static final String OCR_COL = "image_ocr_enabled";
    private static final String PIPELINE_COL = "image_pipeline";

    @TempDir
    Path tempDir;

    // ── 迁移 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("反向对照：迁到 V285 时三列『不在』→ 迁到最新后『在』，且默认值 1/1/'default'")
    void migrationAddsColumnsWithDefaults() throws Exception {
        Path dbFile = tempDir.resolve("v286-ab.db");

        // WHEN ①：只迁到 V285（本迁移未应用）
        migrateTo(dbFile, "285");
        try (Connection conn = open(dbFile)) {
            assertThat(columnNames(conn))
                .as("V285 状态下三列必须【不在】（否则下面的对照无意义）")
                .doesNotContain(CAPTION_COL, OCR_COL, PIPELINE_COL);
        }

        // WHEN ②：同一库继续迁到最新
        migrateFully(dbFile);
        try (Connection conn = open(dbFile)) {
            assertThat(historySuccess(conn, "286")).as("V286 必须 success=1").isEqualTo(1);
            assertThat(columns(conn))
                .as("三列必须存在").extracting(Column::name)
                .contains(CAPTION_COL, OCR_COL, PIPELINE_COL);
            assertThat(columns(conn))
                .as("三个开关列必须 NOT NULL（null 会被下游当「未配置」而绕过默认）")
                .filteredOn(c -> c.name().equals(CAPTION_COL) || c.name().equals(OCR_COL)
                    || c.name().equals(PIPELINE_COL))
                .allMatch(c -> c.notNull() != 0, "三列都应 NOT NULL");
            assertThat(defaultOf(conn, CAPTION_COL)).as("caption 默认 1 = 老库行为不变").isEqualTo("1");
            assertThat(defaultOf(conn, OCR_COL)).as("ocr 默认 1").isEqualTo("1");
            assertThat(defaultOf(conn, PIPELINE_COL)).as("管线默认 default")
                .isEqualTo("'default'");
        }
    }

    // ── 服务读写回环（真库 + Flex）───────────────────────────────────

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("DTO 回环（写什么读回什么）")
    class RoundTrip {

        private KnowledgeBaseService service;
        private Path dbPath;

        @BeforeAll
        void setUpDatabase() throws Exception {
            dbPath = MybatisFlexDbTestSupport.sharedDbPath();
            Files.createDirectories(dbPath.getParent());
            SQLiteDataSource ds = new SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
            Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();
            MybatisFlexDbTestSupport.resetAndStart(ds, KbKnowledgeBaseMapper.class);
            service = new KnowledgeBaseService(
                MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeBaseMapper.class));
        }

        @BeforeEach
        void cleanRows() throws Exception {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbPath.toAbsolutePath());
                 Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM kb_knowledge_base");
            }
        }

        @Test
        @DisplayName("⭐⭐ 创建传 false/false/smartocr ⇒ GET 读回同值，且真落库")
        void createThenGetReturnsSameValues() throws Exception {
            KnowledgeBaseDto created = service.create(req(false, false, "smartocr"));

            KnowledgeBaseDto got = service.getById(created.id());
            assertThat(got.imageCaptionEnabled()).isFalse();
            assertThat(got.imageOcrEnabled()).isFalse();
            assertThat(got.imagePipeline()).isEqualTo("smartocr");
            assertThat(created.imageCaptionEnabled()).as("POST 的响应体也要带新值").isFalse();
            assertThat(created.imagePipeline()).isEqualTo("smartocr");

            // raw SQL：确认不是「只在返回值里改了一下」
            assertThat(rawInt(created.id(), CAPTION_COL)).isZero();
            assertThat(rawInt(created.id(), OCR_COL)).isZero();
            assertThat(rawText(created.id(), PIPELINE_COL)).isEqualTo("smartocr");
        }

        @Test
        @DisplayName("⭐ 创建不传（null）⇒ 默认 true/true/default")
        void createWithNullsUsesDefaults() {
            KnowledgeBaseDto created = service.create(req(null, null, null));

            assertThat(created.imageCaptionEnabled()).isTrue();
            assertThat(created.imageOcrEnabled()).isTrue();
            assertThat(created.imagePipeline()).isEqualTo("default");
        }

        @Test
        @DisplayName("⭐ PUT 局部更新：只传一个开关 ⇒ 另两个保持原值")
        void updateKeepsUntouchedFields() {
            KnowledgeBaseDto kb = service.create(req(false, false, "smartocr"));

            KnowledgeBaseDto updated = service.update(kb.id(), new UpdateKnowledgeBaseRequest(
                null, null, null, null, null, null, true, null, null));
            assertThat(updated.imageCaptionEnabled()).isTrue();
            assertThat(updated.imageOcrEnabled()).isFalse();
            assertThat(updated.imagePipeline()).isEqualTo("smartocr");

            KnowledgeBaseDto back = service.getById(kb.id());
            assertThat(back.imageCaptionEnabled()).isTrue();
            assertThat(back.imageOcrEnabled()).isFalse();
            assertThat(back.imagePipeline()).isEqualTo("smartocr");
        }

        @Test
        @DisplayName("⭐ PATCH：布尔与 0/1 都接受，读回一致")
        void patchAcceptsBooleanAndNumber() {
            KnowledgeBaseDto kb = service.create(req(true, true, "default"));

            ObjectMapper json = new ObjectMapper();
            ObjectNode body = json.createObjectNode();
            body.put("image_caption_enabled", false);
            body.put("image_ocr_enabled", 0);
            body.put("image_pipeline", "smartocr");
            service.patch(kb.id(), body);

            KnowledgeBaseDto back = service.getById(kb.id());
            assertThat(back.imageCaptionEnabled()).isFalse();
            assertThat(back.imageOcrEnabled()).isFalse();
            assertThat(back.imagePipeline()).isEqualTo("smartocr");
        }

        @Test
        @DisplayName("⭐⭐ 非法管线值 ⇒ 400（创建 / PUT / PATCH 三个入口都要拦）")
        void rejectsUnknownPipeline() {
            assertThatThrownBy(() -> service.create(req(null, null, "attrs")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("image_pipeline");

            KnowledgeBaseDto kb = service.create(req(null, null, null));
            assertThatThrownBy(() -> service.update(kb.id(), new UpdateKnowledgeBaseRequest(
                null, null, null, null, null, null, null, null, "attrs")))
                .isInstanceOf(ValidationException.class);

            ObjectNode body = new ObjectMapper().createObjectNode();
            body.put("image_pipeline", "attrs");
            assertThatThrownBy(() -> service.patch(kb.id(), body))
                .isInstanceOf(ValidationException.class);

            assertThat(service.getById(kb.id()).imagePipeline())
                .as("被拒后库里必须保持原值").isEqualTo("default");
        }

        private CreateKnowledgeBaseRequest req(Boolean caption, Boolean ocr, String pipeline) {
            return new CreateKnowledgeBaseRequest("图片管线库", null, "document", null,
                "emb", "sum", null, null, caption, ocr, pipeline);
        }

        private int rawInt(String id, String column) throws Exception {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbPath.toAbsolutePath());
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT " + column + " FROM kb_knowledge_base WHERE id = '" + id + "'")) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }

        private String rawText(String id, String column) throws Exception {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbPath.toAbsolutePath());
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT " + column + " FROM kb_knowledge_base WHERE id = '" + id + "'")) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** 表列信息（PRAGMA table_info）。 */
    private record Column(String name, int notNull, String defaultValue) {
    }

    private static List<String> columnNames(Connection conn) throws Exception {
        return columns(conn).stream().map(Column::name).toList();
    }

    private static List<Column> columns(Connection conn) throws Exception {
        List<Column> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(kb_knowledge_base)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cols.add(new Column(rs.getString("name"), rs.getInt("notnull"),
                    rs.getString("dflt_value")));
            }
        }
        return cols;
    }

    private static String defaultOf(Connection conn, String column) throws Exception {
        return columns(conn).stream()
            .filter(c -> c.name().equals(column))
            .map(Column::defaultValue)
            .findFirst()
            .orElse(null);
    }

    private static void migrateFully(Path dbFile) {
        Flyway.configure().dataSource(ds(dbFile)).locations("classpath:db/migration")
            .baselineOnMigrate(true).load().migrate();
    }

    private static void migrateTo(Path dbFile, String version) {
        Flyway.configure().dataSource(ds(dbFile)).locations("classpath:db/migration")
            .baselineOnMigrate(true).target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private static SQLiteDataSource ds(Path dbFile) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return ds;
    }

    private static Connection open(Path dbFile) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
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
}
