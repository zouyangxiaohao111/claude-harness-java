package com.nexusai.kb.agent.share;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.share.mapper.KbShareMapper;
import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资源分享（{@code kb_share}）· 批次 W11。
 *
 * <p><b>WHY 这些断言</b>：本仓**没有接收方**，所以最容易出的错不是「分享不生效」，
 * 而是「**假装生效**」：
 * <ul>
 *   <li>响应里不标 {@code effective:false} ⇒ 前端和用户以为别人能看到；</li>
 *   <li>{@code /shared-knowledge-bases} 返回自己的库 ⇒ 看起来「有人共享给我」；</li>
 *   <li>知识库与智能体的分享记录混在同一类里 ⇒ 智能体分享污染知识库列表。</li>
 * </ul>
 * ⇒ 这三条必须显式断言。
 */
@DisplayName("[W11] 知识库/智能体分享")
class KbShareServiceTest {

    private static KbShareMapper mapper;
    private static Path dbPath;
    private KbShareService service;

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

        MybatisFlexDbTestSupport.resetAndStart(ds, KbShareMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(KbShareMapper.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_share");
        }
        service = new KbShareService();
        setField(service, "mapper", mapper);
        setField(service, "knowledgeBaseService", permissiveKnowledgeBaseService());
    }

    // ── 知识库分享 ─────────────────────────────────────────

    @Test
    @DisplayName("⭐ 分享记录如实标 effective:false 并带原因")
    void shareMarksIneffective() {
        Map<String, Object> resp = service.shareKnowledgeBase("kb-1", "editor");

        assertThat(resp.get("permission")).isEqualTo("editor");
        assertThat(resp.get("resource_type")).isEqualTo("knowledge_base");
        assertThat(resp.get("effective")).as("本仓没有接收方，必须如实标 false").isEqualTo(false);
        assertThat(resp.get("note")).asString().contains("单机单用户");
    }

    @Test
    @DisplayName("⭐ /shared-knowledge-bases 恒空（不拿自己的库冒充）")
    void sharedToMeAlwaysEmpty() {
        service.shareKnowledgeBase("kb-1", "viewer");

        assertThat(service.sharedKnowledgeBasesToMe()).as("没有「别人」").isEmpty();
        assertThat(service.sharedAgentsToMe()).isEmpty();
    }

    @Test
    @DisplayName("重复分享同一资源 ⇒ 409")
    void duplicateShareConflicts() {
        service.shareKnowledgeBase("kb-1", "viewer");
        assertThatThrownBy(() -> service.shareKnowledgeBase("kb-1", "admin"))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("权限缺省 = viewer；非法值 ⇒ 400")
    void permissionDefaultAndValidation() {
        assertThat(service.shareKnowledgeBase("kb-1", null).get("permission")).isEqualTo("viewer");
        assertThatThrownBy(() -> service.shareKnowledgeBase("kb-2", "superuser"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("admin");
    }

    @Test
    @DisplayName("改权限 / 取消分享")
    void updateThenDelete() {
        Map<String, Object> created = service.shareKnowledgeBase("kb-1", "viewer");
        String shareId = (String) created.get("id");

        Map<String, Object> updated = service.updateKnowledgeBaseShare("kb-1", shareId, "admin");
        assertThat(updated.get("permission")).isEqualTo("admin");

        service.deleteKnowledgeBaseShare("kb-1", shareId);
        assertThat(service.listKnowledgeBaseShares("kb-1")).isEmpty();
    }

    @Test
    @DisplayName("⭐ 智能体分享不污染知识库列表（resource_type 隔离）")
    void agentShareNotVisibleInKbList() {
        service.shareKnowledgeBase("kb-1", "viewer");
        service.shareAgent("agent-1", "viewer");

        assertThat(service.listKnowledgeBaseShares("kb-1")).hasSize(1);
        assertThat(service.listAgentShares("agent-1")).hasSize(1);
        assertThat(service.listAgentShares("kb-1")).as("库里没有 agent-1").isEmpty();
    }

    @Test
    @DisplayName("跨资源改/删 ⇒ 404（不能拿 A 的 shareId 去操作 B）")
    void cannotCrossResource() {
        service.shareKnowledgeBase("kb-1", "viewer");
        service.shareAgent("agent-1", "viewer");
        String kbShareId = (String) service.listKnowledgeBaseShares("kb-1").get(0).get("id");

        assertThatThrownBy(() -> service.updateKnowledgeBaseShare("kb-2", kbShareId, "admin"))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteAgentShare("agent-1", kbShareId))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("知识库不存在（service 抛 404）⇒ 分享失败，不留孤儿记录")
    void missingKnowledgeBaseRejected() {
        KbShareService svc = new KbShareService();
        setFieldQuietly(svc, "mapper", mapper);
        setFieldQuietly(svc, "knowledgeBaseService", strictKnowledgeBaseService());

        assertThatThrownBy(() -> svc.shareKnowledgeBase("kb-missing", "viewer"))
            .isInstanceOf(NotFoundException.class);
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT COUNT(*) FROM kb_share WHERE deleted = 0");
            rs.next();
            assertThat(rs.getInt(1)).as("校验失败不能留下记录").isZero();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 辅助 ───────────────────────────────────────────────

    private static KnowledgeBaseService permissiveKnowledgeBaseService() {
        return new KnowledgeBaseService((KbKnowledgeBaseMapper) null) {
            @Override
            public com.nexusai.kb.dto.KnowledgeBaseDto getById(String id) {
                return null;
            }
        };
    }

    /** 任何库都判为不存在 —— 用于验证「先校验再落库」。 */
    private static KnowledgeBaseService strictKnowledgeBaseService() {
        return new KnowledgeBaseService((KbKnowledgeBaseMapper) null) {
            @Override
            public com.nexusai.kb.dto.KnowledgeBaseDto getById(String id) {
                throw new NotFoundException("知识库不存在: " + id);
            }
        };
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 " + name + " 失败", e);
        }
    }

    private static void setFieldQuietly(Object target, String name, Object value) {
        setField(target, name, value);
    }

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }
}
