package com.nexusai.kb.agent;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.agent.entity.KbAgentRecord;
import com.nexusai.kb.agent.mapper.KbAgentMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 智能体扩展配置（{@code kb_agent}）· 读写语义与作用域解析。
 *
 * <p><b>WHY 这些断言</b>：这一层出错全是静默的 ——
 * <ul>
 *   <li><b>「未配置」被当成「不允许」</b> ⇒ 智能体查不到任何库，用户以为知识库空了；</li>
 *   <li><b>「all」被写成白名单</b> ⇒ 新加的库对老智能体不可见；</li>
 *   <li><b>脏 JSON 直接抛</b> ⇒ 一个手改坏的值让整轮问答失败。</li>
 * </ul>
 */
@DisplayName("[W10] kb_agent 扩展配置")
class KbAgentServiceTest {

    private static KbAgentMapper mapper;
    private static KbAgentService service;
    private static Path dbPath;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, KbAgentMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(KbAgentMapper.class);

        service = new KbAgentService();
        setField(service, "mapper", mapper);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_agent");
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    // ── 落库与读取 ─────────────────────────────────────────

    @Test
    @DisplayName("新建后能读回（含 NOT NULL 默认列）")
    void createThenRead() {
        service.upsert("agri-assistant", new KbAgentService.UpdateRequest(
            "selected", List.of("kb-1", "kb-2"), true, 10, 0.3, 0.5, 5, 0.5, true));

        KbAgentRecord r = service.find("agri-assistant");
        assertThat(r).isNotNull();
        assertThat(r.getKbSelectionMode()).isEqualTo("selected");
        assertThat(r.getKnowledgeBases()).isEqualTo("[\"kb-1\",\"kb-2\"]");
        assertThat(r.getRetrieveKbOnlyWhenMentioned()).isEqualTo(1);
        assertThat(r.getEmbeddingTopK()).isEqualTo(10);
        assertThat(r.getCitationEnabled()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ null = 不改该项（不是清空）")
    void nullMeansNoChange() {
        service.upsert("a", new KbAgentService.UpdateRequest(
            "selected", List.of("kb-1"), true, 10, null, null, null, null, null));
        service.upsert("a", new KbAgentService.UpdateRequest(
            null, null, null, 99, null, null, null, null, null));

        KbAgentRecord r = service.find("a");
        assertThat(r.getEmbeddingTopK()).isEqualTo(99);
        assertThat(r.getKbSelectionMode())
            .as("⭐ 只改 top_k 时库范围不能被改掉")
            .isEqualTo("selected");
        assertThat(r.getRetrieveKbOnlyWhenMentioned()).isEqualTo(1);
    }

    @Test
    @DisplayName("非法选择模式 ⇒ 400（不是静默存进去）")
    void invalidSelectionModeRejected() {
        assertThatThrownBy(() -> service.upsert("a", new KbAgentService.UpdateRequest(
            "everything", null, null, null, null, null, null, null, null)))
            .hasMessageContaining("kb_selection_mode");
    }

    @Test
    @DisplayName("阈值越界 ⇒ 400")
    void invalidThresholdRejected() {
        assertThatThrownBy(() -> service.upsert("a", new KbAgentService.UpdateRequest(
            null, null, null, null, 1.5, null, null, null, null)))
            .hasMessageContaining("keyword_threshold");
    }

    // ── ⭐ 三值语义 ─────────────────────────────────────────

    @Test
    @DisplayName("⭐ 无扩展行 ⇒ null（不限制），不是空列表")
    void noRowMeansUnrestricted() {
        assertThat(service.resolveKnowledgeBaseIds("not-configured"))
            .as("⭐ null = 全部库；若返回空列表会让这个智能体什么都查不到")
            .isNull();
    }

    @Test
    @DisplayName("all ⇒ null（不限制）")
    void allMeansUnrestricted() {
        service.upsert("a", new KbAgentService.UpdateRequest(
            "all", List.of("ignored"), null, null, null, null, null, null, null));
        assertThat(service.resolveKnowledgeBaseIds("a")).isNull();
    }

    @Test
    @DisplayName("none ⇒ 空列表（明确不检索）")
    void noneMeansEmpty() {
        service.upsert("a", new KbAgentService.UpdateRequest(
            "none", null, null, null, null, null, null, null, null));
        assertThat(service.resolveKnowledgeBaseIds("a")).isEmpty();
    }

    @Test
    @DisplayName("selected ⇒ 解析出白名单（去空、去重、trim）")
    void selectedParsesWhitelist() {
        service.upsert("a", new KbAgentService.UpdateRequest(
            "selected", List.of(" kb-1 ", "kb-1", "", "kb-2"), null,
            10, null, null, null, null, null));
        assertThat(service.resolveKnowledgeBaseIds("a")).containsExactly("kb-1", "kb-2");
    }

    @Test
    @DisplayName("⭐ 脏 JSON ⇒ 空列表而不是抛异常")
    void corruptJsonDegrades() throws Exception {
        service.upsert("a", new KbAgentService.UpdateRequest(
            "selected", List.of("kb-1"), null, null, null, null, null, null, null));
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE kb_agent SET knowledge_bases = 'not json' WHERE id = 'a'");
        }
        assertThat(service.resolveKnowledgeBaseIds("a"))
            .as("⭐ 一个手改坏的值不能让整轮问答失败")
            .isEmpty();
    }

    // ── 孤儿与删除 ─────────────────────────────────────────

    @Test
    @DisplayName("listExtensionIds 能发现孤儿（MD 已删、配置仍在）")
    void listExtensionIds() {
        service.upsert("ghost-a", new KbAgentService.UpdateRequest(
            "all", null, null, null, null, null, null, null, null));
        assertThat(service.listExtensionIds()).contains("ghost-a");
    }

    @Test
    @DisplayName("删除是幂等的")
    void deleteIsIdempotent() {
        service.upsert("a", new KbAgentService.UpdateRequest(
            "all", null, null, null, null, null, null, null, null));
        service.deleteExtension("a");
        assertThat(service.find("a")).isNull();
        // 再删一次不抛
        service.deleteExtension("a");
    }

    @Test
    @DisplayName("空 agent id ⇒ 400")
    void blankIdRejected() {
        assertThatThrownBy(() -> service.upsert("  ", new KbAgentService.UpdateRequest(
            "all", null, null, null, null, null, null, null, null)))
            .hasMessageContaining("agent id");
    }
}
