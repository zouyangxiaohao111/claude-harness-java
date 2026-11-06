package com.nexusai.kb.config;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.config.mapper.KbConfigMapper;
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
 * 问答链参数（{@code kb_config} 的 18 列）· 默认值单点 + 三态写入。
 *
 * <p><b>WHY 这些断言（规则九 · 测试验证意图）</b>：
 * 这 18 个参数决定整条 RAG 链的行为（检索条数 / 四个阈值 / 多轮窗口 / 联网 / FAQ 策略）。
 * 它们读错的方式全是<b>静默</b>的：
 * <ul>
 *   <li><b>「未配置」与「配成默认值」混为一谈</b> ⇒ 改代码默认值时，已经显式配成旧默认值的库
 *       会被悄悄改行为；</li>
 *   <li><b>{@code max_rounds = 0} 被当成「未配置」</b> ⇒ 用户显式关掉多轮，系统却按 5 轮跑，
 *       把旧对话塞进每一轮；</li>
 *   <li><b>写 null 被当成「清空」</b> ⇒ 前端只改一个阈值，其它 17 项全被抹掉。</li>
 * </ul>
 * ⇒ 断言覆盖：默认值、0 的语义、null 不改、单项写入不串味。
 */
@DisplayName("[QA-1] kb_config 问答链参数")
class KbConfigQaParamsTest {

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

        MybatisFlexDbTestSupport.resetAndStart(ds, KbConfigMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(KbConfigMapper.class);

        service = new KbConfigService();
        setField(service, "mapper", mapper);
        setField(service, "dataSource", ds);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
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

    /**
     * 构造写入载荷（19 项，未设置的保持 null = 不改）。
     *
     * <p>⚠️ 用 Builder 而不是位置参数：19 个参数全同类型/可空，位置传参错位
     * <b>不会编译失败</b>，只会静默写错列 —— 这正是测试要防的一类缺陷。
     */
    private static final class U {
        Integer embeddingTopK;
        Double vectorThreshold;
        Double keywordThreshold;
        Double rerankThreshold;
        Integer rerankTopK;
        Integer contextMaxChunks;
        Integer maxRounds;
        Boolean enableRewrite;
        Boolean enableQueryExpansion;
        Boolean faqPriorityEnabled;
        Double faqScoreBoost;
        Double faqDirectAnswerThreshold;
        Integer webSearchMaxResults;
        Boolean webFetchEnabled;
        Integer webFetchTopN;
        Boolean dataAnalysisEnabled;
        String contextTemplate;
        String intentPrompts;
        Boolean kbToolsEnabled;

        U topK(Integer v) { this.embeddingTopK = v; return this; }
        U vec(Double v) { this.vectorThreshold = v; return this; }
        U kw(Double v) { this.keywordThreshold = v; return this; }
        U rerankTh(Double v) { this.rerankThreshold = v; return this; }
        U rerankTopK(Integer v) { this.rerankTopK = v; return this; }
        U maxRounds(Integer v) { this.maxRounds = v; return this; }
        U rewrite(Boolean v) { this.enableRewrite = v; return this; }
        U expansion(Boolean v) { this.enableQueryExpansion = v; return this; }
        U faq(Boolean v) { this.faqPriorityEnabled = v; return this; }
        U webFetch(Boolean v) { this.webFetchEnabled = v; return this; }
        U webFetchTopN(Integer v) { this.webFetchTopN = v; return this; }
        U dataAnalysis(Boolean v) { this.dataAnalysisEnabled = v; return this; }
        U template(String v) { this.contextTemplate = v; return this; }
        U intentPrompts(String v) { this.intentPrompts = v; return this; }
        U kbTools(Boolean v) { this.kbToolsEnabled = v; return this; }

        KbConfigService.QaParamsUpdate build() {
            return new KbConfigService.QaParamsUpdate(
                embeddingTopK, vectorThreshold, keywordThreshold, rerankThreshold, rerankTopK,
                contextMaxChunks, maxRounds, enableRewrite, enableQueryExpansion,
                faqPriorityEnabled, faqScoreBoost, faqDirectAnswerThreshold,
                webSearchMaxResults, webFetchEnabled, webFetchTopN, dataAnalysisEnabled,
                contextTemplate, intentPrompts, kbToolsEnabled);
        }
    }

    // ── 默认值 ──────────────────────────────────────────────

    @Test
    @DisplayName("未配置 ⇒ 全部回落代码默认值（源侧 config.yaml 同款）")
    void unsetFallsBackToDefaults() {
        KbConfigService.QaParams p = service.readQaParams();
        assertThat(p.embeddingTopK()).isEqualTo(30);
        assertThat(p.vectorThreshold()).isEqualTo(0.2);
        assertThat(p.keywordThreshold()).isEqualTo(0.3);
        assertThat(p.rerankThreshold()).isEqualTo(0.3);
        assertThat(p.rerankTopK()).isEqualTo(30);
        assertThat(p.contextMaxChunks()).isEqualTo(12);
        assertThat(p.maxRounds()).isEqualTo(5);
        assertThat(p.enableRewrite()).isTrue();
        assertThat(p.enableQueryExpansion()).isTrue();
        assertThat(p.faqPriorityEnabled()).isFalse();
        assertThat(p.webSearchMaxResults()).isEqualTo(5);
        assertThat(p.webFetchEnabled()).isFalse();
        assertThat(p.webFetchTopN()).isEqualTo(3);
        assertThat(p.dataAnalysisEnabled()).isFalse();
        assertThat(p.kbToolsEnabled()).isFalse();
    }

    @Test
    @DisplayName("⭐ max_rounds = 0 必须原样返回（= 显式关闭多轮，不是「未配置」）")
    void explicitZeroMaxRoundsIsPreserved() {
        service.updateQaParams(new U().maxRounds(0).build());
        assertThat(service.readQaParams().maxRounds())
            .as("0 若被当成未配置 ⇒ 用户关掉的多轮会重新打开")
            .isZero();
    }

    @Test
    @DisplayName("阈值配成 0 ⇒ 原样返回 0（= 不过滤，不是「未配置回落 0.2」）")
    void explicitZeroThresholdIsPreserved() {
        service.updateQaParams(new U().vec(0.0).kw(0.0).rerankTh(0.0).build());
        KbConfigService.QaParams p = service.readQaParams();
        assertThat(p.vectorThreshold()).isZero();
        assertThat(p.keywordThreshold()).isZero();
        assertThat(p.rerankThreshold()).isZero();
    }

    // ── 三态写入 ────────────────────────────────────────────

    @Test
    @DisplayName("⭐ 只传一项 ⇒ 其它 17 项保持原值（null = 不改）")
    void nullMeansNoChange() {
        service.updateQaParams(new U().rerankTopK(42).maxRounds(3).faq(true).build());
        service.updateQaParams(new U().topK(50).build());

        KbConfigService.QaParams p = service.readQaParams();
        assertThat(p.embeddingTopK()).isEqualTo(50);
        assertThat(p.rerankTopK()).as("上一批写的 42 不能被这次抹掉").isEqualTo(42);
        assertThat(p.maxRounds()).isEqualTo(3);
        assertThat(p.faqPriorityEnabled()).isTrue();
    }

    @Test
    @DisplayName("全空载荷 ⇒ 不写库、不改值")
    void emptyPayloadIsNoop() {
        service.updateQaParams(new U().build());
        assertThat(service.readQaParams().embeddingTopK()).isEqualTo(30);
    }

    @Test
    @DisplayName("布尔项能正确往返（true/false 都要能写，不能默认成 null 不写）")
    void booleansRoundTrip() {
        service.updateQaParams(new U().rewrite(false).expansion(false).faq(true)
            .webFetch(true).webFetchTopN(5).dataAnalysis(true).kbTools(true).build());
        KbConfigService.QaParams p = service.readQaParams();
        assertThat(p.enableRewrite()).isFalse();
        assertThat(p.enableQueryExpansion()).isFalse();
        assertThat(p.faqPriorityEnabled()).isTrue();
        assertThat(p.webFetchEnabled()).isTrue();
        assertThat(p.webFetchTopN()).isEqualTo(5);
        assertThat(p.dataAnalysisEnabled()).isTrue();
        assertThat(p.kbToolsEnabled()).isTrue();
    }

    @Test
    @DisplayName("模板与意图提示词能写能读（含中文）")
    void templatesRoundTrip() {
        service.updateQaParams(new U()
            .template("{query}\\n---\\n{contexts}")
            .intentPrompts("{\"greeting\":\"简短寒暄\"}")
            .build());
        KbConfigService.QaParams p = service.readQaParams();
        assertThat(p.contextTemplate()).contains("{contexts}");
        assertThat(p.intentPrompts()).contains("简短寒暄");
    }
}
