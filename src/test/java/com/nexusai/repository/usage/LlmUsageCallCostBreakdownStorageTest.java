package com.nexusai.repository.usage;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.cost.SessionUsageLedger;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.infra.llm.LlmCallRecord;
import com.nexusai.infra.llm.LlmUsagePhase;
import com.nexusai.infra.llm.UsageAttribution;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.infra.llm.UsageTokenSource;
import com.nexusai.repository.usage.entity.LlmUsageCallRecord;
import com.nexusai.repository.usage.mapper.LlmUsageCallMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [V101] {@code llm_usage_calls} <b>费用分项列</b>（{@code cost_*} 五列 + {@code cost_detail_source}）
 * 的真库落表 / 读回契约（真 SQLite + Flyway 全链，含 V101 增量迁移）。
 *
 * <h2>WHY（规则九：不落这条会静默丢在哪）</h2>
 * 「缓存省了多少钱」完全依赖这五列的可用性，而它们有三个会静默失血的点：
 * <ol>
 *   <li><b>列名/类型映射错</b>（实体字段 ↔ V101 列）：{@code persist()} 只 WARN + 计数 ⇒ 明细表整行为空，
 *       前端「暂无明细数据」，读侧的省钱口径永远算不出来（且无人察觉）；</li>
 *   <li><b>历史行不可插入</b>：若把分项列写成 NOT NULL，V101 之前的行（只有 {@code cost_yuan}）
 *       就再也写不进去 ⇒ 存量对账断档；</li>
 *   <li><b>{@code cost_detail_source} 缺省语义</b>：手工/历史插入不填该列时若没有 DB DEFAULT，
 *       该行会被当成「分项齐全但全 0」（PRICED）⇒ 读侧把「没有数据」当「没有花费」，把账单算小。</li>
 * </ol>
 *
 * <h2>反向实验（哪个变异会让哪条断言变红）</h2>
 * <ul>
 *   <li>{@code persist()} 漏 set 任一分项列（或列名写错）⇒ {@code breakdownColumnsPersistAndReadBack} 红；</li>
 *   <li>V101 把分项列改成 {@code NOT NULL}（或实体用基本类型 double）⇒
 *       {@code legacyRowWithoutBreakdownIsIdentifiable} 红；</li>
 *   <li>V101 的 {@code cost_detail_source} 去掉 {@code DEFAULT 'LEGACY'} ⇒ 同上用例的 LEGACY 断言红；</li>
 *   <li>{@code started_at} 回退成 {@code Instant.toString()}（UTC {@code Z} 结尾）⇒
 *       {@code startedAtIsLocalOffsetAndSortsChronologically} 的格式断言与字典序断言红。</li>
 * </ul>
 */
@DisplayName("[V101] llm_usage_calls 费用分项列落表/读回 + LEGACY 行可识别（真库）")
class LlmUsageCallCostBreakdownStorageTest {

    /** 本类专属会话前缀（clean 只清自己的行，不碰同库其它测试类的数据）。 */
    private static final String SESSION_PREFIX = "sess-v100-breakdown";

    private static LlmUsageCallMapper mapper;

    /** 真库 + 全量迁移（含 V98 建表 / V99 会话列 / V101 分项列 + started_at 索引）。 */
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

        MybatisFlexDbTestSupport.resetAndStart(ds, LlmUsageCallMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(LlmUsageCallMapper.class);
    }

    @BeforeEach
    void cleanOwnRows() {
        for (LlmUsageCallRecord r : mapper.selectAll()) {
            if (r.getSessionId() != null && r.getSessionId().startsWith(SESSION_PREFIX)) {
                mapper.deleteById(r.getId());
            }
        }
    }

    /** 计价替身：分项固定（四项之和 1.23 = 总额，与 V101 不变式一致）/ 高峰档。 */
    private static ModelCostCalculator fixedPriceCalculator() {
        ModelCostCalculator calc = mock(ModelCostCalculator.class);
        when(calc.isPeakHour()).thenReturn(true);
        when(calc.price(any(), any(), anyBoolean()))
            .thenReturn(new ModelCostCalculator.CostBreakdown(1.0, 0.2, 0.02, 0.01, 1.23, 1.5));
        when(calc.contextWindowFor(any())).thenReturn(200_000);
        when(calc.maxOutputFor(any())).thenReturn(8_192L);
        return calc;
    }

    private static AgentUsage usage(long in, long out, Long cacheRead, Long cacheCreation) {
        return new AgentUsage(in, out, cacheCreation, cacheRead, null, null, null);
    }

    /** 一条完整归属的调用记录（流式 + 已结算 · 未计价，由账本补价）。 */
    private static LlmCallRecord streamCall(String callId, UsageAttribution who, String modelName,
                                            AgentUsage u, long startedAtMs) {
        return new LlmCallRecord(callId, who, modelName, modelName + "-sdk", "anthropic",
            "USER", true, LlmUsagePhase.SETTLED, UsageTokenSource.UPSTREAM, u,
            0.0, false, null, "ok", null, startedAtMs, 4321L);
    }

    @Test
    @DisplayName("记账 → 落表 → 读回：分项五列逐字段一致 + cost_detail_source='PRICED' + 总额 = 分项之和")
    void breakdownColumnsPersistAndReadBack() {
        String session = SESSION_PREFIX + "-priced";
        SessionUsageLedger ledger = new SessionUsageLedger(session, fixedPriceCalculator(), mapper);
        UsageAttribution who = UsageAttribution.top(session, session, UsageCategory.MAIN, null);
        String callId = LlmCallRecord.newCallId();

        ledger.record(streamCall(callId, who, "claude-sonnet-4-20250514",
            usage(1500, 300, 900L, 120L), 1_700_000_000_000L));

        LlmUsageCallRecord row = mapper.selectOneById(callId);
        assertThat(row).as("账本记账必须落表（未落表 → 读侧省不出任何钱）").isNotNull();
        assertThat(row.getCostInput()).as("input 分项落表（列名/类型错即红）").isEqualTo(1.0);
        assertThat(row.getCostOutput()).isEqualTo(0.2);
        assertThat(row.getCostCacheRead()).isEqualTo(0.02);
        assertThat(row.getCostCacheWrite()).isEqualTo(0.01);
        assertThat(row.getCostNoCacheInput()).as("反事实项（无 cache 时 input 侧全价）").isEqualTo(1.5);
        assertThat(row.getCostDetailSource()).as("本次调用在计价点算出分项 → PRICED")
            .isEqualTo("PRICED");
        assertThat(row.getCostYuan())
            .as("不变式：总额 = input + output + cacheRead + cacheWrite")
            .isEqualTo(row.getCostInput() + row.getCostOutput() + row.getCostCacheRead() + row.getCostCacheWrite());
        assertThat(ledger.persistFailureCount()).as("落表失败计数必须为 0").isZero();
    }

    @Test
    @DisplayName("V101 之前的历史行（手工插入不填分项）可插入且被识别为 LEGACY（分项列可空 + DB DEFAULT）")
    void legacyRowWithoutBreakdownIsIdentifiable() {
        String session = SESSION_PREFIX + "-legacy";
        LlmUsageCallRecord legacy = new LlmUsageCallRecord();
        legacy.setId("uc-v100legacy1");
        legacy.setSessionId(session);
        legacy.setAgentId(session);
        legacy.setCategory("MAIN");
        legacy.setPhase("SETTLED");
        legacy.setTokenSource("UPSTREAM");
        legacy.setModelName("claude-sonnet-4-20250514");
        legacy.setInputTokens(1000);
        legacy.setOutputTokens(100);
        legacy.setCostYuan(0.5);
        legacy.setStartedAt("2026-01-01T00:00:00+08:00");
        // ⛔ 刻意不填 costInput/…/costNoCacheInput 与 costDetailSource：模拟「V101 迁移之前」的行。
        //    分项列可空 + cost_detail_source 有 DB DEFAULT 'LEGACY'，故这条插入必须成功。
        mapper.insertSelective(legacy);

        LlmUsageCallRecord row = mapper.selectOneById("uc-v100legacy1");
        assertThat(row).as("历史行必须可插入（分项列若 NOT NULL 即整库对账断档）").isNotNull();
        assertThat(row.getCostInput()).as("历史行无分项 → NULL（⛔ 不是 0）").isNull();
        assertThat(row.getCostOutput()).isNull();
        assertThat(row.getCostCacheRead()).isNull();
        assertThat(row.getCostCacheWrite()).isNull();
        assertThat(row.getCostNoCacheInput()).isNull();
        assertThat(row.getCostDetailSource())
            .as("不填该列 → 吃迁移 DEFAULT 'LEGACY'（⛔ 不得被当成「分项全 0 的 PRICED」，否则读侧把无数据当无花费）")
            .isEqualTo("LEGACY");
        assertThat(row.getCostYuan()).as("历史行只有总额可用（总额列 V98 就有）").isEqualTo(0.5);
    }

    @Test
    @DisplayName("started_at：本地时区带偏移 ISO-8601（⛔ 非 UTC 'Z'），且同秒内字典序 == 时间序")
    void startedAtIsLocalOffsetAndSortsChronologically() {
        String session = SESSION_PREFIX + "-time";
        SessionUsageLedger ledger = new SessionUsageLedger(session, fixedPriceCalculator(), mapper);
        UsageAttribution who = UsageAttribution.top(session, session, UsageCategory.MAIN, null);
        // 同一秒的整秒 + 带毫秒两条：正是 DM1 裁定要修的错序场景
        String earlierId = LlmCallRecord.newCallId();
        String laterId = LlmCallRecord.newCallId();
        ledger.record(streamCall(earlierId, who, "m", usage(1, 1, null, null), 1_700_000_000_000L));
        ledger.record(streamCall(laterId, who, "m", usage(1, 1, null, null), 1_700_000_000_645L));

        String earlier = mapper.selectOneById(earlierId).getStartedAt();
        String later = mapper.selectOneById(laterId).getStartedAt();

        assertThat(earlier)
            .as("本地时区 + 偏移的 ISO-8601 文本（⛔ 不是 UTC 'Z' 结尾）")
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?[+-]\\d{2}:\\d{2}");
        assertThat(earlier).doesNotEndWith("Z");
        assertThat(OffsetDateTime.parse(earlier).toInstant())
            .as("表示形式变了，时刻本身不得漂移")
            .isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(earlier)
            .as("字典序 == 时间序（区间过滤可直接字符串比较并吃 idx_llm_usage_calls_started_at）")
            .isLessThan(later);
        assertThat(OffsetDateTime.parse(later).toInstant()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_645L));

        // 真库按字符串区间过滤（读侧范围查询的口径）：只应命中 later 这一条
        List<LlmUsageCallRecord> ranged = mapper.selectListByQuery(
            com.mybatisflex.core.query.QueryWrapper.create()
                .where("started_at >= ? AND started_at <= ?", later, later));
        assertThat(ranged).extracting(LlmUsageCallRecord::getId)
            .as("started_at 字符串区间闭区间过滤应精确命中 later 行（'...38' < '...38.645' 的字典序前提）")
            .containsExactly(laterId);
    }
}
