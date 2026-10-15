package com.nexusai.domain.session;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * [cache-savings] {@code SessionDto.cacheHitRate} <b>派生</b>真库契约（SQLite + Flyway +
 * MybatisFlexDbTestSupport，⛔ 无 {@code @SpringBootTest}）。
 *
 * <h2>WHY（规则九：这条守的是哪类真实故障）</h2>
 * 前端删掉「自算缓存命中率」后，F5 重拉（消息从 DB 重新加载、没有实时 STOMP 帧）只能靠会话读取路径
 * 给值 —— 若 {@code SessionService.toDto} 不派生该字段（或派生用了第二套公式），底部「缓存%」在
 * 刷新后会静默变成「不显示」/错值，而这<b>不会报错</b>，只会被用户当成「今天没缓存」。
 * 本类用真库把三条口径钉死：
 * <ol>
 *   <li><b>anthropic</b>：分母 = input + cacheRead + cacheCreation（input 不含 cache）；</li>
 *   <li><b>非 anthropic（deepseek/openai）</b>：分母 = input（input 已含 cache hit，再加会虚高）；</li>
 *   <li><b>无数据</b>（列 null/空白/解析失败/分母 0）→ {@code null}（⛔ 不输出 NaN/Infinity、不臆造 0）。</li>
 * </ol>
 *
 * <h2>RED（改坏哪条会翻红）</h2>
 * <ul>
 *   <li>{@code toDto} 不再派生（字段恒 null）→ 有 usage 的两个用例红；</li>
 *   <li>派生处自带公式（忘记按协议分派）→ anthropic 用例红（{@code 800/200 = 4.0}）；</li>
 *   <li>解析失败不 fail-soft（抛异常）→ 损坏 JSON 用例红（会话详情接口 500）。</li>
 * </ul>
 */
@DisplayName("[cache-savings] SessionDto.cacheHitRate 派生契约（F5 重拉后仍要有值）")
class SessionCacheHitRateDerivationTest {

    private static final String SID = "sess-cache-hit";
    private static final String ANTHROPIC = "claude-x";
    private static final String OPENAI = "deepseek-chat";

    private static SessionMapper sessionMapper;
    private static ModelMapper modelMapper;
    private static ProviderMapper providerMapper;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, SessionMapper.class, ModelMapper.class, ProviderMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        modelMapper = MybatisFlexBootstrap.getInstance().getMapper(ModelMapper.class);
        providerMapper = MybatisFlexBootstrap.getInstance().getMapper(ProviderMapper.class);
    }

    @BeforeEach
    void seed() {
        for (SessionRecord s : sessionMapper.selectAll()) {
            sessionMapper.deleteById(s.getId());
        }
        for (ModelRecord m : modelMapper.selectAll()) {
            modelMapper.deleteById(m.getId());
        }
        for (ProviderRecord p : providerMapper.selectAll()) {
            providerMapper.deleteById(p.getId());
        }
        provider("p-anth", "anth", "anthropic");
        provider("p-oa", "oa", "openai_sdk");
        model("m-claude", "p-anth", ANTHROPIC);
        model("m-deepseek", "p-oa", OPENAI);
    }

    /** 真 SessionService（只注入读路径需要的三个 mapper）。 */
    private static SessionService service() {
        SessionService s = new SessionService();
        ReflectionTestUtils.setField(s, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(s, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(s, "providerMapper", providerMapper);
        return s;
    }

    @Test
    @DisplayName("anthropic：分母含 cache 两项（800/(200+800+100)），⛔ 不是 800/200")
    void anthropicBucketDispatchesCacheIntoDenominator() {
        session(SID, "{\"" + ANTHROPIC + "\":{\"inputTokens\":200,\"outputTokens\":50,"
            + "\"cacheReadInputTokens\":800,\"cacheCreationInputTokens\":100}}");

        SessionDto dto = service().getById(SID);

        assertThat(dto.cacheHitRate()).isCloseTo(800.0 / 1100.0, within(1e-9));
        assertThat(dto.totalTokens()).as("既有字段不受影响（input+output）").isEqualTo(250L);
    }

    @Test
    @DisplayName("非 anthropic（deepseek）：分母只能是 input（input 已含 cache hit）→ 500/1300")
    void nonAnthropicBucketUsesInputOnly() {
        session(SID, "{\"" + OPENAI + "\":{\"inputTokens\":1300,\"outputTokens\":130,"
            + "\"cacheReadInputTokens\":500,\"cacheCreationInputTokens\":0}}");

        SessionDto dto = service().getById(SID);

        assertThat(dto.cacheHitRate()).isCloseTo(500.0 / 1300.0, within(1e-9));
    }

    @Test
    @DisplayName("多模型桶：逐桶按自己的模型名判定 anthropic，分子分母分别加总后一次相除")
    void mixedBucketsCombineNumeratorAndDenominator() {
        session(SID, "{\"" + ANTHROPIC + "\":{\"inputTokens\":200,\"outputTokens\":50,"
            + "\"cacheReadInputTokens\":800,\"cacheCreationInputTokens\":100},"
            + "\"" + OPENAI + "\":{\"inputTokens\":1300,\"outputTokens\":130,"
            + "\"cacheReadInputTokens\":500,\"cacheCreationInputTokens\":0}}");

        SessionDto dto = service().getById(SID);

        assertThat(dto.cacheHitRate()).as("(800+500) / (1100+1300)，⛔ 不是两个比率的平均")
            .isCloseTo(1300.0 / 2400.0, within(1e-9));
    }

    @Test
    @DisplayName("无数据：列 null / 空白 / 损坏 JSON / 分母 0 → null（⛔ 不输出 NaN/Infinity、不臆造 0）")
    void noDataYieldsNull() {
        session(SID, null);
        assertThat(service().getById(SID).cacheHitRate()).as("列 null").isNull();

        sessionMapper.deleteById(SID);
        session(SID, "   ");
        assertThat(service().getById(SID).cacheHitRate()).as("空白列").isNull();

        sessionMapper.deleteById(SID);
        session(SID, "{不是 JSON");
        assertThat(service().getById(SID).cacheHitRate())
            .as("损坏 JSON → 解析 fail-soft（⛔ 不得抛异常把会话详情接口打成 500）").isNull();

        sessionMapper.deleteById(SID);
        session(SID, "{\"" + OPENAI + "\":{\"inputTokens\":0,\"outputTokens\":10,"
            + "\"cacheReadInputTokens\":0,\"cacheCreationInputTokens\":0}}");
        assertThat(service().getById(SID).cacheHitRate()).as("prompt 总量为 0（无 prompt token）").isNull();
    }

    @Test
    @DisplayName("解析单点：与 CostTracker.parseModelUsageJson 同源（同一份库数据不得解析出两个结果）")
    void parseReusesCostTrackerSinglePoint() {
        String json = "{\"" + OPENAI + "\":{\"inputTokens\":1300,\"outputTokens\":130,"
            + "\"cacheReadInputTokens\":500,\"cacheCreationInputTokens\":0}}";
        session(SID, json);

        assertThat(CostTracker.parseModelUsageJson(json))
            .as("解析入口公开供会话读路径复用").containsKey(OPENAI);
        assertThat(service().getById(SID).cacheHitRate())
            .isCloseTo(500.0 / 1300.0, within(1e-9));
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────────────────────

    private static void provider(String id, String name, String type) {
        ProviderRecord p = new ProviderRecord();
        p.setId(id);
        p.setName(name);
        p.setType(type);
        p.setBaseUrl("http://localhost/" + name);
        p.setApiKeyHash("hash-" + id);
        p.setApiKeyMasked("sk-****" + id);
        p.setEnabled(true);
        providerMapper.insertSelective(p);
    }

    private static void model(String id, String providerId, String name) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setProviderId(providerId);
        m.setName(name);
        m.setTag("DS");
        m.setEnabled(true);
        modelMapper.insertSelective(m);
    }

    private static void session(String id, String modelUsageJson) {
        SessionRecord s = new SessionRecord();
        s.setId(id);
        s.setModelTag("DS");
        s.setModelName(OPENAI);
        s.setTitle("cache-hit-fixture");
        s.setTime("刚刚");
        s.setSessionGroup("current");
        s.setCreatedAt("2026-10-13T10:00:00+08:00");
        s.setUpdatedAt("2026-10-13T10:00:00+08:00");
        s.setModelUsageJson(modelUsageJson);
        sessionMapper.insertSelective(s);
    }
}
