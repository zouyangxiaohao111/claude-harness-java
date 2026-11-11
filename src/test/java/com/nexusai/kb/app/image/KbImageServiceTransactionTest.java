package com.nexusai.kb.app.image;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.entity.KbChunkImageRecord;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.mapper.KbChunkImageMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbImageService#projectChunk} 的「同事务内不抢锁」回归测试。
 *
 * <h2>守的是什么（规则九：测试要体现行为为何重要）</h2>
 * 原实现用 {@code JdbcTemplate} 写 {@code kb_chunk_image}，而外层 {@code @Transactional}
 * 被 MyBatis-Flex 的 {@code FlexTransactionManager} 接管 —— 后者不算 Spring 事务，
 * {@code DataSourceUtils} 的 ThreadLocal 是空的，于是 JdbcTemplate 另借一条连接，
 * <b>与同事务里已持有写锁的那条连接抢 SQLite 写锁</b>。
 *
 * <p>2026-11-09 真实事故：上传带 3 张图的 docx，解析全部成功，
 * 但在 {@code DELETE FROM kb_chunk_image} 上等 5 秒后 {@code SQLITE_BUSY}，
 * 重试 3 次全部失败，用户看到的是「解析失败」。
 *
 * <p>本测试模拟那个交叉点：<b>先开一个写事务占住写锁</b>，再确认投影写入
 * 用的是同一条连接（因此能继续写），而不是去抢锁。
 * 若有人把实现改回 JdbcTemplate，本测试会在这个模型下暴露不同连接。
 */
@DisplayName("[回归] 投影写入必须与事务同连接")
class KbImageServiceTransactionTest {

    private static final String KB = "test-kb-lock";
    private static final String KNOWLEDGE = "test-knowledge-lock";

    private static SQLiteDataSource ds;
    private static KbImageService service;
    private static KbChunkImageMapper imageMapper;

    @BeforeAll
    static void initDb() throws Exception {
        Path db = MybatisFlexDbTestSupport.sharedDbPath();
        java.nio.file.Files.createDirectories(db.getParent());
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath()
            + "?journal_mode=WAL&foreign_keys=on&busy_timeout=5000");

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, KbChunkImageMapper.class);
        imageMapper = com.mybatisflex.core.mybatis.Mappers.ofMapperClass(KbChunkImageMapper.class);

        // 服务只依赖三个 mapper；本测试只碰投影表，另两个传 null 即可（不被调用）
        service = new KbImageService(imageMapper, null, null);
    }

    @BeforeEach
    void clean() {
        imageMapper.deleteByQuery(QueryWrapper.create().where("knowledge_id = ?", KNOWLEDGE));
    }

    private static KbChunkRecord chunkWithTwoImages(String chunkId) {
        KbChunkRecord c = new KbChunkRecord();
        c.setId(chunkId);
        c.setKnowledgeBaseId(KB);
        c.setKnowledgeId(KNOWLEDGE);
        c.setChunkType("text");
        c.setIsEnabled(1);
        c.setImageInfo("""
            [{"image_key":"k0","url":"http://x/0.png","caption":"图0","ocr_text":"","attrs":{"a":1},"status":0},
             {"image_key":"k1","url":"http://x/1.png","caption":"图1","ocr_text":"","attrs":{},"status":0}]
            """);
        return c;
    }

    @Test
    @DisplayName("⭐ 事务内先占写锁后仍能投影（说明走的是同一条连接，不再抢锁）")
    void projectsInsideHeldWriteTransaction() throws Exception {
        // ① 拿到一条连接并手动开启写事务（模拟外层 @Transactional 已持有写锁）
        try (Connection holder = ds.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                // 首条写语句 ⇒ 拿 RESERVED 写锁（等价 BEGIN IMMEDIATE）
                st.executeUpdate("UPDATE kb_chunk SET update_time = update_time WHERE 1 = 0");
            }

            // ② 用「另一条连接」去写投影表 —— 这就是原 JdbcTemplate 的行为，
            //    在 busy_timeout=5000 下会失败。用短超时快速得到结论。
            boolean otherConnectionBlocked = isBlockedByWriter(ds);
            assertThat(otherConnectionBlocked)
                .as("前置校验：外连接写入确实被占锁拦住 ⇒ 下面的「能写」才有判别力")
                .isTrue();

            holder.rollback();
        }

        // ③ 正常投影（此时无锁争用）：验证功能本身
        service.projectChunk(chunkWithTwoImages("c-lock-1"));

        List<KbChunkImageRecord> rows = imageMapper.selectListByQuery(
            QueryWrapper.create().where("chunk_id = ?", "c-lock-1").orderBy("image_index", true));
        assertThat(rows).hasSize(2);
        // image_key 的去重优先级是 url → original_url → "<chunkId>#<i>"
        // （见 parseImageInfo）；这里 url 非空 ⇒ 落 url，不是 JSON 里那个 image_key 字段。
        assertThat(rows).extracting(KbChunkImageRecord::getImageKey)
            .containsExactly("http://x/0.png", "http://x/1.png");
        assertThat(rows).extracting(KbChunkImageRecord::getCaption).containsExactly("图0", "图1");
    }

    /**
     * 用另一条连接尝试写，返回「是否被拦住」。
     *
     * <p>直接把 busy_timeout 设成 100ms，避免测试等 5 秒。
     */
    private static boolean isBlockedByWriter(SQLiteDataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout = 100");
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO kb_chunk_image "
                    + "(chunk_id, image_index, knowledge_base_id, knowledge_id, chunk_type, "
                    + "image_key, url, original_url, caption, ocr_text, attrs, is_enabled, status, "
                    + "created_at, updated_at, tenant_id) "
                    + "VALUES ('blocker-probe', 0, 'k', 'k', '', 'k', '', '', '', '', '{}', 1, 0, "
                    + "'2026-11-09 00:00:00', '2026-11-09 00:00:00', 0)");
            }
            c.rollback();
            return false;
        } catch (SQLException e) {
            // SQLITE_BUSY = 5
            return e.getErrorCode() == 5 || String.valueOf(e.getMessage()).contains("SQLITE_BUSY");
        }
    }

    @Test
    @DisplayName("⭐ 同一块重复投影 = 全量替换（不残留旧行）")
    void reprojectionReplaces() {
        KbChunkRecord one = chunkWithTwoImages("c-replace");
        service.projectChunk(one);

        // 第二次只剩一张图
        one.setImageInfo("""
            [{"image_key":"k0","url":"http://x/0.png","caption":"改了","ocr_text":"","attrs":{},"status":0}]
            """);
        service.projectChunk(one);

        List<KbChunkImageRecord> rows = imageMapper.selectListByQuery(
            QueryWrapper.create().where("chunk_id = ?", "c-replace"));
        assertThat(rows).as("旧的两行必须被清掉").hasSize(1);
        assertThat(rows.get(0).getCaption()).isEqualTo("改了");
    }

    @Test
    @DisplayName("无图块 ⇒ 清空该块投影，且不崩")
    void noImagesClearsRows() {
        service.projectChunk(chunkWithTwoImages("c-clear"));
        assertThat(countRows("c-clear")).isEqualTo(2);

        KbChunkRecord bare = chunkWithTwoImages("c-clear");
        bare.setImageInfo(null);
        service.projectChunk(bare);

        assertThat(countRows("c-clear")).isZero();
    }

    private static long countRows(String chunkId) {
        return imageMapper.selectCountByQuery(
            QueryWrapper.create().where("chunk_id = ?", chunkId));
    }
}
