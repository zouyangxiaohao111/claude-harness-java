package com.nexusai.kb.app.image;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.entity.KbChunkImageRecord;
import com.nexusai.kb.repo.mapper.KbChunkImageMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针：验证 Flex 通道写 {@code kb_chunk_image} 的三条前提。
 *
 * <p><b>为什么需要这个探针（不是「以防万一」）</b>：
 * {@code KbImageService.projectChunk} 目前用 {@code JdbcTemplate} 写投影表，而外层
 * {@code @Transactional} 被 MyBatis-Flex 的 {@code FlexTransactionManager} 接管 ——
 * 后者只生成 XID、<b>不绑定 Spring 的 DataSourceUtils</b>，于是 {@code JdbcTemplate}
 * 另借一条连接，与同事务的 Flex 写抢 SQLite 写锁 ⇒ {@code SQLITE_BUSY}（实测 3 次重试全败）。
 * 改成走 {@code imageMapper} 是修法，但有三件事<b>必须实测而不能猜</b>：
 * <ol>
 *   <li>{@code @Column(tenantId = true)} 会不会在 insert 时覆盖手工 set 的值；</li>
 *   <li>复合主键 {@code (chunk_id, image_index)} 用 {@code insertBatch} 会不会走 upsert 分支；</li>
 *   <li>{@code KbSqliteLocalDateTimeTypeHandler} 是否真的输出 {@code yyyy-MM-dd HH:mm:ss}。</li>
 * </ol>
 *
 * <p>⚠️ 本类只读写自己新建的行，且每例前按 chunk_id 前缀清理，不碰真实业务数据。
 */
@DisplayName("[探针] kb_chunk_image Flex 通道三条前提")
class ImageProjectionFlexProbe {

    private static final String CHUNK = "probe-chunk-1";

    private static KbChunkImageMapper mapper;

    @BeforeAll
    static void initDb() throws Exception {
        Path db = MybatisFlexDbTestSupport.sharedDbPath();
        java.nio.file.Files.createDirectories(db.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath()
            + "?journal_mode=WAL&foreign_keys=on&busy_timeout=5000");

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, KbChunkImageMapper.class);
        mapper = com.mybatisflex.core.mybatis.Mappers.ofMapperClass(KbChunkImageMapper.class);
    }

    @BeforeEach
    void clean() {
        mapper.deleteByQuery(QueryWrapper.create().where("chunk_id LIKE ?", "probe-%"));
    }

    private static KbChunkImageRecord row(int index, Long tenantId) {
        KbChunkImageRecord r = new KbChunkImageRecord();
        r.setChunkId(CHUNK);
        r.setImageIndex(index);
        r.setKnowledgeBaseId("probe-kb");
        r.setKnowledgeId("probe-knowledge");
        r.setChunkType("text");
        r.setImageKey("probe-key-" + index);
        r.setUrl("http://example.invalid/" + index + ".png");
        r.setOriginalUrl("");
        r.setCaption("cap" + index);
        r.setOcrText("");
        r.setAttrs("{}");
        r.setIsEnabled(1);
        r.setStatus(0);
        r.setCreatedAt(LocalDateTime.of(2026, 11, 9, 15, 36, 7));
        r.setUpdatedAt(LocalDateTime.of(2026, 11, 9, 15, 36, 7));
        r.setTenantId(tenantId);
        return r;
    }

    @Test
    @DisplayName("① insertBatch 可用，且复合主键的第二列 image_index 真的落库")
    void batchInsertWritesCompositeKey() {
        List<KbChunkImageRecord> rows = new ArrayList<>();
        rows.add(row(0, 0L));
        rows.add(row(1, 0L));
        mapper.insertBatch(rows);

        List<KbChunkImageRecord> back = mapper.selectListByQuery(
            QueryWrapper.create().where("chunk_id = ?", CHUNK).orderBy("image_index", true));

        assertThat(back).as("两行都要落库（复合主键第二列不能丢）").hasSize(2);
        assertThat(back).extracting(KbChunkImageRecord::getImageIndex).containsExactly(0, 1);
        assertThat(back).extracting(KbChunkImageRecord::getImageKey)
            .containsExactly("probe-key-0", "probe-key-1");
    }

    @Test
    @DisplayName("① -b 删掉再插 = 全量替换语义（与原生 SQL 版一致）")
    void deleteThenInsertReplacesAll() {
        mapper.insertBatch(List.of(row(0, 0L), row(1, 0L)));

        // 模拟 projectChunk 的「先 delete 再 insert」
        mapper.deleteByQuery(QueryWrapper.create().where("chunk_id = ?", CHUNK));
        mapper.insertBatch(List.of(row(0, 0L)));

        List<KbChunkImageRecord> back = mapper.selectListByQuery(
            QueryWrapper.create().where("chunk_id = ?", CHUNK));
        assertThat(back).as("旧的两行必须没了").hasSize(1);
        assertThat(back.get(0).getImageIndex()).isZero();
    }

    @Test
    @DisplayName("② tenantId：手工 set 0 后落库仍是 0（不被 TenantFactory 覆盖成 null）")
    void tenantIdPreserved() {
        mapper.insertBatch(List.of(row(0, 0L)));

        // 用 Row 走原生列读取，避开实体映射，直接看库里真值
        java.util.List<com.mybatisflex.core.row.Row> rows =
            com.mybatisflex.core.row.Db.selectListBySql(
                "SELECT tenant_id FROM kb_chunk_image WHERE chunk_id = ?", CHUNK);
        Object tenant = rows.isEmpty() ? null : rows.get(0).get("tenant_id");

        assertThat(tenant)
            .as("tenant_id 列是 NOT NULL DEFAULT 0；若被写成 null 会炸或落默认值")
            .isNotNull();
        assertThat(((Number) tenant).longValue()).isZero();
    }

    @Test
    @DisplayName("③ 时间列输出 yyyy-MM-dd HH:mm:ss（与 KbTimeFormat 逐字节一致，不是 ISO 的 T）")
    void timeFormatMatchesSqliteConvention() {
        mapper.insertBatch(List.of(row(0, 0L)));

        // 用 Row 走原生列读取，避开实体映射，直接看库里真值
        java.util.List<com.mybatisflex.core.row.Row> rows =
            com.mybatisflex.core.row.Db.selectListBySql(
                "SELECT created_at FROM kb_chunk_image WHERE chunk_id = ?", CHUNK);
        Object created = rows.isEmpty() ? null : rows.get(0).get("created_at");

        assertThat(created).as("created_at 不能是 null").isNotNull();
        assertThat(created.toString())
            .as("必须是空格分隔 19 字符，否则 ORDER BY 时间序会错")
            .isEqualTo("2026-11-09 15:36:07");
        assertThat(created.toString()).doesNotContain("T");
    }
}
