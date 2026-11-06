package com.nexusai.kb.config;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.domain.provider.ModelService;
import com.nexusai.kb.config.mapper.KbConfigMapper;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库域配置（{@code kb_config}）· 读写语义 + 模型自动选用。
 *
 * <p><b>WHY 这些断言</b>：配置读错的失败方式全是<b>静默</b>的 ——
 * <ul>
 *   <li><b>「null 不改」被写成「null 清空」</b> ⇒ 前端只传一个字段，另一个被悄悄抹掉</li>
 *   <li><b>「空串」与「未配置」混存</b> ⇒ 回落判断永远命中空串，下层配置被永久遮蔽</li>
 *   <li><b>模型没显式配就当作「没有」</b> ⇒ 向量索引不建、检索只走关键词，而且不报错
 *       （2026-11-05 实测：用户已配 1 个 embedding 模型，但向量路一次都没跑过）</li>
 * </ul>
 */
@DisplayName("[R0] kb_config 读写 + 模型自动选用")
class KbConfigServiceTest {

    private static KbConfigMapper mapper;
    private static KbConfigService service;
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

        MybatisFlexDbTestSupport.resetAndStart(ds, KbConfigMapper.class,
            ModelMapper.class, ProviderMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(KbConfigMapper.class);

        // 真实 ModelService（自动选用要读 models 表），字段注入 ⇒ 反射塞 mapper
        ModelService modelService = new ModelService();
        setField(modelService, "modelMapper",
            MybatisFlexBootstrap.getInstance().getMapper(ModelMapper.class));
        setField(modelService, "providerMapper",
            MybatisFlexBootstrap.getInstance().getMapper(ProviderMapper.class));

        service = new KbConfigService();
        setField(service, "mapper", mapper);
        setField(service, "dataSource", ds);
        setField(service, "modelService", modelService);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM models");
            s.executeUpdate("DELETE FROM providers");
            s.executeUpdate("DELETE FROM kb_config");
            s.executeUpdate("INSERT INTO kb_config (id) VALUES ('kb')");
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

    // ── 模型配置三态 ────────────────────────────────────────

    @Test
    @DisplayName("写入后能读回（原始值）")
    void writeThenReadRaw() {
        service.updateModelConfig("emb-1", "rerank-1");
        KbConfigService.ModelConfig cfg = service.readModelConfig();
        assertThat(cfg.embeddingModelId()).isEqualTo("emb-1");
        assertThat(cfg.rerankModelId()).isEqualTo("rerank-1");
    }

    @Test
    @DisplayName("⭐ 传 null ⇒ 不改该项（而不是清空）")
    void nullMeansNoChange() {
        service.updateModelConfig("emb-1", "rerank-1");
        service.updateModelConfig(null, "rerank-2");

        KbConfigService.ModelConfig cfg = service.readModelConfig();
        assertThat(cfg.embeddingModelId())
            .as("⭐ null = 不改 ⇒ 前端只传一个字段时另一个不能被抹掉")
            .isEqualTo("emb-1");
        assertThat(cfg.rerankModelId()).isEqualTo("rerank-2");
    }

    @Test
    @DisplayName("⭐ 传空串 ⇒ 清空（归一成 NULL，不留空串）")
    void blankClears() {
        service.updateModelConfig("emb-1", "rerank-1");
        service.updateModelConfig("", "");

        KbConfigService.ModelConfig cfg = service.readModelConfig();
        assertThat(cfg.embeddingModelId())
            .as("⭐ 空串必须归一成 NULL —— 否则回落判断会命中空串，遮蔽下层配置")
            .isNull();
        assertThat(cfg.rerankModelId()).isNull();

        // 物理库也必须是 NULL，不是 ''
        assertThat(mapper.selectOneById("kb").getEmbeddingModelId()).isNull();
    }

    @Test
    @DisplayName("空白串按未配置读回（模拟历史脏数据）")
    void whitespaceTreatedAsUnset() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE kb_config SET embedding_model_id = '   ' WHERE id = 'kb'");
        }
        assertThat(service.readModelConfig().embeddingModelId()).isNull();
    }

    @Test
    @DisplayName("行缺失 ⇒ 懒建一行，读回「未配置」")
    void lazyCreateRow() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_config");
        }
        assertThat(mapper.selectOneById("kb")).isNull();
        assertThat(service.readModelConfig().embeddingModelId()).isNull();
        // read() 会补行，保证后续 UPDATE 有效
        service.read();
        assertThat(mapper.selectOneById("kb")).isNotNull();
    }

    // ── ⭐ 自动选用 ────────────────────────────────────────

    @Test
    @DisplayName("⭐ 未显式配置 ⇒ 自动选用第一个启用的 embedding 模型")
    void autoPickEmbedding() throws Exception {
        insertModel("model-b", "emb-b", "embedding", 1);
        insertModel("model-a", "emb-a", "embedding", 1);

        assertThat(service.readEmbeddingModelId())
            .as("按 id 排序取第一个 ⇒ 结果确定，不随 selectAll 顺序漂移")
            .isEqualTo("model-a");
    }

    @Test
    @DisplayName("自动选用跳过停用模型与非目标类型")
    void autoPickSkipsDisabledAndOtherTypes() throws Exception {
        insertModel("model-000", "chat-1", "chat", 1);
        insertModel("model-001", "emb-off", "embedding", 0);
        insertModel("model-002", "emb-on", "embedding", 1);

        assertThat(service.readEmbeddingModelId()).isEqualTo("model-002");
        assertThat(service.readRerankModelId())
            .as("没有启用的 rerank 模型 ⇒ null（不重排），不是拿 chat 顶替")
            .isNull();
    }

    @Test
    @DisplayName("⭐ 显式配置优先于自动选用")
    void explicitBeatsAutoPick() throws Exception {
        insertModel("model-a", "emb-a", "embedding", 1);
        service.updateModelConfig("model-explicit", null);

        assertThat(service.readEmbeddingModelId()).isEqualTo("model-explicit");
    }

    @Test
    @DisplayName("⭐ readModelConfig 返回原始值，不回填自动选用的结果")
    void rawConfigDoesNotIncludeAutoPick() throws Exception {
        insertModel("model-a", "emb-a", "embedding", 1);

        assertThat(service.readEmbeddingModelId()).as("解析层能拿到").isEqualTo("model-a");
        assertThat(service.readModelConfig().embeddingModelId())
            .as("⭐ 端点回显必须是「用户配了什么」——回填自动值会让用户以为配过，"
                + "之后模型被禁用时行为变化却找不到原因")
            .isNull();
    }

    @Test
    @DisplayName("清空后回到自动选用")
    void clearFallsBackToAutoPick() throws Exception {
        insertModel("model-a", "emb-a", "embedding", 1);
        service.updateModelConfig("model-explicit", null);
        service.updateModelConfig("", null);

        assertThat(service.readEmbeddingModelId()).isEqualTo("model-a");
    }

    // ── 检索驱动 / 存储后端 / 记忆 ─────────────────────────

    @Test
    @DisplayName("检索驱动三态 + 空串清空")
    void retrieverEngineThreeState() {
        service.updateRetrieverEngine("milvus");
        assertThat(service.readRetrieverEngine()).isEqualTo("milvus");

        service.updateRetrieverEngine(null);
        assertThat(service.readRetrieverEngine()).as("null = 不改").isEqualTo("milvus");

        service.updateRetrieverEngine("");
        assertThat(service.readRetrieverEngine()).isNull();
    }

    @Test
    @DisplayName("默认存储后端三态 + 空串清空")
    void storageBackendThreeState() {
        service.updateDefaultStorageBackendId("sb-1");
        assertThat(service.readDefaultStorageBackendId()).isEqualTo("sb-1");

        service.updateDefaultStorageBackendId(null);
        assertThat(service.readDefaultStorageBackendId()).as("null = 不改").isEqualTo("sb-1");

        service.updateDefaultStorageBackendId("");
        assertThat(service.readDefaultStorageBackendId()).isNull();
    }

    @Test
    @DisplayName("记忆配置：未配置默认开启，四字段可写可清")
    void memoryConfig() {
        assertThat(service.readMemoryEnabled())
            .as("未配置默认 true（写入模式默认 explicit_only ⇒ 不花钱）")
            .isTrue();

        service.updateMemoryConfig(false, "auto", 500, 5);
        assertThat(service.readMemoryEnabled()).isFalse();
        assertThat(service.readMemoryWriteMode()).isEqualTo("auto");
        assertThat(service.readMemoryMaxItems()).isEqualTo(500);
        assertThat(service.readMemoryInterestThreshold()).isEqualTo(5);

        // 只改一个 ⇒ 其它不变
        service.updateMemoryConfig(true, null, null, null);
        assertThat(service.readMemoryEnabled()).isTrue();
        assertThat(service.readMemoryWriteMode()).isEqualTo("auto");
    }

    // ── 辅助 ──────────────────────────────────────────────

    private static void insertModel(String id, String name, String type, int enabled) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT OR IGNORE INTO providers "
                + "(id, name, type, base_url, api_key_hash, api_key_masked) VALUES "
                + "('p1', 'p1', 'openai_compatible', 'http://localhost', 'h', 'm')");
            s.executeUpdate("INSERT INTO models (id, provider_id, name, tag, type, enabled) VALUES ("
                + "'" + id + "', 'p1', '" + name + "', 'T', '" + type + "', " + enabled + ")");
        }
    }
}
