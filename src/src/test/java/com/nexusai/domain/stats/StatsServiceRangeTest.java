package com.nexusai.domain.stats;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.usage.entity.LlmUsageCallRecord;
import com.nexusai.repository.usage.mapper.LlmUsageCallMapper;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * [usage-gateway] {@code StatsService} 区间聚合<b>真库</b>契约（SQLite + Flyway + MybatisFlexDbTestSupport，
 * ⛔ 无 {@code @SpringBootTest}）。
 *
 * <h2>WHY（规则九：这条守的是哪类真实故障）</h2>
 * 四维统计（totals / byDay / byModel / byCategory / byAgent）此前<b>分源</b>：
 * totals/byDay/byModel 读 {@code sessions} 快照（{@code total_cost_yuan} + {@code model_usage_json}
 * + {@code created_at}），byCategory/byAgent 读 {@code llm_usage_calls} 明细表。同一页面两个数字对不上、
 * 且「会话创建日 ≠ 调用日」的会话把花费记在创建日。重写后<b>唯一真源 = 明细表</b>，本类把这条钉死，
 * 并把「改回快照源」的反向实验留在夹具里。
 *
 * <h2>RED（改坏哪条会翻红）</h2>
 * <ul>
 *   <li><b>totals 与 byCategory 分源</b>（把任一侧换回 {@code sessions.total_cost_yuan}）→
 *       对账用例红（夹具里 {@code sessions.total_cost_yuan = 99.99}，而明细合计 3.5）；</li>
 *   <li><b>byDay 回到「会话创建日」</b> → byDay 用例红（夹具会话创建于 2026-01-01，
 *       调用发生在 2026-10-13/14）；</li>
 *   <li><b>区间边界写错</b>（{@code > from} 或 {@code <= to}）→ 边界用例红（恰在 {@code to} 的行必须被排除）；</li>
 *   <li><b>命中率忘记按协议分派分母</b> → anthropic 行会算出 &gt;1 的「命中率」（{@code 800/200 = 4.0}）→ 用例红；</li>
 *   <li><b>LEGACY 行被摊分进分项</b>（拿 {@code cost_yuan} 补 NULL 分项）→ 分项用例红。</li>
 * </ul>
 *
 * <h2>时区无关性</h2>
 * 夹具时间串与查询边界都由<b>同一</b> {@link ZoneId#systemDefault()} 渲染（写入侧
 * {@code OffsetDateTime.ofInstant(..., systemDefault()).toString()} 同款），因此断言在 UTC 与
 * {@code +08:00} 机器上等价 —— ⛔ 不硬编码 {@code +08:00} 字面量（那会在别的时区机器上让
 * 「本地日期」断言假红）。
 */
@DisplayName("[usage-gateway] StatsService 区间聚合 · 真库（明细表单一真源）")
class StatsServiceRangeTest {

    private static final String S1 = "sess-range-1";
    private static final String S2 = "sess-range-2";
    private static final String SUB_AGENT = "a0123456789abcdef";
    private static final String MODEL_ANTHROPIC = "claude-x";
    private static final String MODEL_OPENAI = "deepseek-chat";

    private static LlmUsageCallMapper usageMapper;
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

        MybatisFlexDbTestSupport.resetAndStart(ds, LlmUsageCallMapper.class, SessionMapper.class,
            ModelMapper.class, ProviderMapper.class);
        usageMapper = MybatisFlexBootstrap.getInstance().getMapper(LlmUsageCallMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        modelMapper = MybatisFlexBootstrap.getInstance().getMapper(ModelMapper.class);
        providerMapper = MybatisFlexBootstrap.getInstance().getMapper(ProviderMapper.class);
    }

    /** 每个用例：清表 → 重建 providers/models/sessions/calls 夹具（区间断言互不串场）。 */
    @BeforeEach
    void seed() {
        clearAll();

        provider("p-anth", "anth", "anthropic");
        provider("p-oa", "oa", "openai_sdk");
        model("m-claude", "p-anth", MODEL_ANTHROPIC, "CL");
        model("m-deepseek", "p-oa", MODEL_OPENAI, "DS");

        // 会话 S1：created_at = 2026-01-01（**创建日 ≠ 调用日**）+ total_cost_yuan = 99.99
        //   （反向实验哨兵：若 totals/byCategory 任一侧换回 sessions 快照 → 期望值不再是 3.5）
        session(S1, local(2026, 1, 1, 0, 0), 99.99, "{\"deepseek-chat\":{\"inputTokens\":1,\"outputTokens\":1}}");
        session(S2, local(2026, 1, 2, 0, 0), 88.88, null);

        // A：S1 主 agent · deepseek（非 anthropic）· 10-13
        usageMapper.insertSelective(priced("uc-a", S1, S1, "MAIN", null, 0, null, MODEL_OPENAI,
            local(2026, 10, 13, 10, 0),
            1000, 100, 500, 0, 1.0, 0.5, 0.4, 0.1, 0.0, 1.2, "SETTLED", "UPSTREAM", 1, 120L));
        // B：S1 子代理 · claude（anthropic）· 10-14
        usageMapper.insertSelective(priced("uc-b", S1, SUB_AGENT, "SUBAGENT", S1, 1, "alice", MODEL_ANTHROPIC,
            local(2026, 10, 14, 9, 0),
            200, 50, 800, 100, 2.0, 0.4, 0.6, 0.1, 0.9, 3.2, "SETTLED", "UPSTREAM", 1, 300L));
        // C：S2 主 agent · deepseek · 10-14 · **LEGACY**（V101 前历史行：分项列 NULL，只有总额）
        LlmUsageCallRecord legacy = base("uc-c", S2, S2, "MAIN", null, 0, null, MODEL_OPENAI,
            local(2026, 10, 14, 20, 0), 300, 30, null, null, 0.5, "ABORTED", "ESTIMATED", 0, 40L);
        legacy.setCostDetailSource("LEGACY");
        usageMapper.insertSelective(legacy);
        // D：S2 主 agent · 无模型名 · 10-16（用于「只带 from 不限 to」时仍应被计入）
        LlmUsageCallRecord unnamed = base("uc-d", S2, S2, "MAIN", null, 0, null, null,
            local(2026, 10, 16, 12, 0), 50, 5, null, null, 9.9, "SETTLED", "UPSTREAM", 0, 10L);
        unnamed.setCostDetailSource("LEGACY");
        usageMapper.insertSelective(unnamed);
    }

    private static StatsService stats() {
        StatsService s = new StatsService();
        ReflectionTestUtils.setField(s, "usageCallMapper", usageMapper);
        ReflectionTestUtils.setField(s, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(s, "providerMapper", providerMapper);
        return s;
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 1) 单一真源对账（含反向实验）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("totals 与 byCategory 同源对账：明细合计 == totals（改回 sessions 快照即红 · 哨兵 99.99）")
    void totalsAndByCategoryShareSingleSource() {
        StatsService.StatsResult r = stats().aggregate(local(2026, 10, 13, 0, 0), local(2026, 10, 15, 0, 0));

        // 反向实验哨兵：sessions 快照里的值是 99.99（若任一维度读它 → 下面 3.5 变 99.99 量级）
        assertThat(sessionMapper.selectOneById(S1).getTotalCostYuan())
            .as("夹具必须保留 sessions 快照的 99.99（反向实验的前提）").isEqualTo(99.99);

        // 区间内：A(1.0) + B(2.0) + C(0.5) = 3.5（D 在 10-16，被 to 排除）
        assertThat(r.totals().costYuan()).as("totals 费用 = 明细合计")
            .isCloseTo(3.5, within(1e-9));
        assertThat(r.byCategory().stream().mapToDouble(StatsService.CategoryStat::costYuan).sum())
            .as("byCategory 费用求和必须与 totals.costYuan 相等（改前：一个读快照、一个读明细 → 不等）")
            .isCloseTo(r.totals().costYuan(), within(1e-9));
        assertThat(r.byModel().stream().mapToDouble(StatsService.ModelStat::costYuan).sum())
            .as("byModel 费用同样必须与 totals 对齐").isCloseTo(r.totals().costYuan(), within(1e-9));

        assertThat(r.totals().calls()).isEqualTo(3);
        assertThat(r.totals().sessionCount())
            .as("sessionCount = 区间内**有调用**的会话数（COUNT(DISTINCT session_id)，语义已变更）").isEqualTo(2);
        assertThat(r.totals().tokenCount())
            .as("tokenCount = input + output（⛔ 不含 cache）")
            .isEqualTo((1000 + 100) + (200 + 50) + (300 + 30));

        assertThat(r.range().filtered()).isTrue();
        assertThat(r.range().from()).isEqualTo(local(2026, 10, 13, 0, 0));
        assertThat(r.range().to()).isEqualTo(local(2026, 10, 15, 0, 0));
    }

    @Test
    @DisplayName("byCategory 字段集逐字保留（calls/token/费用/估算·中断·失败/流式计数）+ 费用降序")
    void byCategoryKeepsFieldSet() {
        StatsService.StatsResult r = stats().aggregate(local(2026, 10, 13, 0, 0), local(2026, 10, 15, 0, 0));
        Map<String, StatsService.CategoryStat> byCat = r.byCategory().stream()
            .collect(Collectors.toMap(StatsService.CategoryStat::category, Function.identity()));

        StatsService.CategoryStat sub = byCat.get("SUBAGENT");
        assertThat(sub.calls()).isEqualTo(1);
        assertThat(sub.inputTokens()).isEqualTo(200);
        assertThat(sub.outputTokens()).isEqualTo(50);
        assertThat(sub.cacheReadTokens()).isEqualTo(800);
        assertThat(sub.cacheWriteTokens()).isEqualTo(100);
        assertThat(sub.streamCalls()).isEqualTo(1);
        assertThat(sub.estimatedCalls()).isZero();
        assertThat(sub.abortedCalls()).isZero();

        StatsService.CategoryStat main = byCat.get("MAIN");
        assertThat(main.calls()).isEqualTo(2);
        assertThat(main.inputTokens()).isEqualTo(1300);
        assertThat(main.streamCalls()).as("A 流式 + C 非流式").isEqualTo(1);
        assertThat(main.nonStreamCalls()).isEqualTo(1);
        assertThat(main.estimatedCalls()).as("C 的 token 是估算值（单列，不可当账单）").isEqualTo(1);
        assertThat(main.abortedCalls()).as("C 是中断尝试（上游可能仍计费）").isEqualTo(1);
        assertThat(main.failedCalls()).isZero();

        assertThat(r.byCategory()).extracting(StatsService.CategoryStat::category)
            .as("费用降序：SUBAGENT 2.0 > MAIN 1.5").containsExactly("SUBAGENT", "MAIN");
    }

    @Test
    @DisplayName("byAgent 层级：主 agent depth=0 / 子代理 depth=1 且带父链（字段集逐字保留）")
    void byAgentKeepsHierarchy() {
        StatsService.StatsResult r = stats().aggregate(local(2026, 10, 13, 0, 0), local(2026, 10, 15, 0, 0));
        Map<String, StatsService.AgentStat> byId = r.byAgent().stream()
            .collect(Collectors.toMap(StatsService.AgentStat::agentId, Function.identity()));

        assertThat(r.byAgent()).extracting(StatsService.AgentStat::depth)
            .as("层级序（父在前）").containsExactly(0, 0, 1);

        StatsService.AgentStat child = byId.get(SUB_AGENT);
        assertThat(child.category()).isEqualTo("SUBAGENT");
        assertThat(child.parentAgentId()).isEqualTo(S1);
        assertThat(child.label()).isEqualTo("alice");
        assertThat(child.calls()).isEqualTo(1);
        assertThat(child.costYuan()).isCloseTo(2.0, within(1e-9));

        StatsService.AgentStat main = byId.get(S1);
        assertThat(main.category()).isEqualTo("MAIN");
        assertThat(main.parentAgentId()).isNull();
        assertThat(main.calls()).isEqualTo(1);
        assertThat(main.costYuan()).isCloseTo(1.0, within(1e-9));
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 2) byDay = 调用发生日（不是会话创建日）
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("byDay 按**调用发生日**分组（会话创建于 2026-01-01 的夹具仍归到 10-13/10-14）")
    void byDayUsesCallDateNotSessionCreationDate() {
        StatsService.StatsResult r = stats().aggregate(local(2026, 10, 13, 0, 0), local(2026, 10, 15, 0, 0));

        assertThat(r.byDay()).extracting(StatsService.DayStat::date)
            .as("调用发生日升序；会话创建日 2026-01-01 不得出现（改前口径会把花费记在创建日）")
            .containsExactly(dateKey(2026, 10, 13), dateKey(2026, 10, 14));

        StatsService.DayStat d13 = r.byDay().get(0);
        assertThat(d13.calls()).isEqualTo(1);
        assertThat(d13.tokenCount()).isEqualTo(1100);
        assertThat(d13.costYuan()).isCloseTo(1.0, within(1e-9));

        StatsService.DayStat d14 = r.byDay().get(1);
        assertThat(d14.calls()).isEqualTo(2);
        assertThat(d14.tokenCount()).as("B(250) + C(330)").isEqualTo(580);
        assertThat(d14.costYuan()).isCloseTo(2.5, within(1e-9));
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 3) 区间过滤：from 含 / to 不含 · 跨天 · epoch ms · 空区间 · 非法参数
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("区间边界：from 含 / to 不含（恰在 to 的行必须被排除）· 跨天")
    void rangeBoundaries() {
        // 恰在边界上的行（10-14T00:00 整）—— 用于钉死 to 的**半开**语义
        LlmUsageCallRecord atBoundary = priced("uc-edge", S2, S2, "MAIN", null, 0, null, MODEL_OPENAI,
            local(2026, 10, 14, 0, 0),
            10, 1, 0, 0, 7.7, 7.7, 0.0, 0.0, 0.0, 7.7, "SETTLED", "UPSTREAM", 0, 5L);
        usageMapper.insertSelective(atBoundary);

        // to = 10-14T00:00（不含）→ 只剩 10-13 的 A
        StatsService.StatsResult beforeBoundary = stats().aggregate(null, local(2026, 10, 14, 0, 0));
        assertThat(beforeBoundary.totals().calls()).as("边界行在 to 上 → 排除（to 不含）").isEqualTo(1);
        assertThat(beforeBoundary.byDay()).extracting(StatsService.DayStat::date)
            .containsExactly(dateKey(2026, 10, 13));
        assertThat(beforeBoundary.range().filtered()).isTrue();
        assertThat(beforeBoundary.range().from()).isNull();

        // from = 10-14T00:00（含）→ 边界行 + B + C + D
        StatsService.StatsResult fromBoundary = stats().aggregate(local(2026, 10, 14, 0, 0), null);
        assertThat(fromBoundary.totals().calls()).as("边界行在 from 上 → 计入（from 含）").isEqualTo(4);
        assertThat(fromBoundary.byDay()).extracting(StatsService.DayStat::date)
            .as("跨天：10-14 / 10-15(若时区偏移使 D 落此) / 10-16 的本地日期序")
            .contains(dateKey(2026, 10, 14), dateKey(2026, 10, 16));

        // 半开区间 [10-14, 10-15) → 边界行 + B + C（D 在 10-16 被排除）
        StatsService.StatsResult window = stats().aggregate(local(2026, 10, 14, 0, 0), local(2026, 10, 15, 0, 0));
        assertThat(window.totals().calls()).isEqualTo(3);
        assertThat(window.totals().costYuan()).isCloseTo(7.7 + 2.0 + 0.5, within(1e-9));

        // epoch 毫秒与 ISO 文本等价（同一时刻两种写法必须得到同一结果）
        StatsService.StatsResult byEpoch = stats().aggregate(
            String.valueOf(epochMs(2026, 10, 14, 0, 0)), String.valueOf(epochMs(2026, 10, 15, 0, 0)));
        assertThat(byEpoch.totals().calls()).as("epoch ms 与 ISO-8601 边界等价（同一行集）")
            .isEqualTo(window.totals().calls());
        assertThat(byEpoch.totals().costYuan()).isCloseTo(window.totals().costYuan(), within(1e-9));
        assertThat(byEpoch.byDay()).extracting(StatsService.DayStat::date)
            .isEqualTo(window.byDay().stream().map(StatsService.DayStat::date).toList());
    }

    @Test
    @DisplayName("空区间（区间内无调用）→ 全零 + 空数组 + 命中率 null（fail-soft，不抛）")
    void emptyInterval() {
        StatsService.StatsResult r = stats().aggregate(local(2027, 1, 1, 0, 0), local(2027, 1, 2, 0, 0));

        assertThat(r.totals().calls()).isZero();
        assertThat(r.totals().sessionCount()).isZero();
        assertThat(r.totals().costYuan()).isZero();
        assertThat(r.totals().cacheHitRate()).as("无 prompt token → null（不得 NaN/Infinity）").isNull();
        assertThat(r.totals().cacheSavingsYuan()).isZero();
        assertThat(r.byDay()).isEmpty();
        assertThat(r.byModel()).isEmpty();
        assertThat(r.byCategory()).isEmpty();
        assertThat(r.byAgent()).isEmpty();
    }

    @Test
    @DisplayName("非法时间参数 → ValidationException（⇒ 400，⛔ 不是 500）")
    void invalidRangeIsValidationError() {
        assertThatThrownBy(() -> stats().aggregate("昨天", null))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> stats().aggregate(null, "2026-13-45T99:99:99+08:00"))
            .isInstanceOf(ValidationException.class);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 4) 分项费用 / 缓存节省 / LEGACY 不摊分
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("costYuan/分项/缓存节省口径：PRICED 五列求和 + LEGACY 单列统计（不摊分、不伪造）")
    void costBreakdownAndLegacySeparation() {
        StatsService.StatsResult r = stats().aggregate(local(2026, 10, 13, 0, 0), local(2026, 10, 15, 0, 0));
        StatsService.Totals t = r.totals();

        assertThat(t.costYuan()).as("总额含 LEGACY 行的 cost_yuan（0.5）").isCloseTo(3.5, within(1e-9));
        assertThat(t.costInput()).isCloseTo(0.9, within(1e-9));
        assertThat(t.costOutput()).isCloseTo(1.0, within(1e-9));
        assertThat(t.costCacheRead()).isCloseTo(0.2, within(1e-9));
        assertThat(t.costCacheWrite()).isCloseTo(0.9, within(1e-9));
        assertThat(t.costNoCacheInput()).as("反事实项（不计入 costYuan）").isCloseTo(4.4, within(1e-9));
        assertThat(t.cacheSavingsYuan())
            .as("节省 = noCache - (input + cacheRead + cacheWrite) = 4.4 - 2.0")
            .isCloseTo(2.4, within(1e-9));
        assertThat(t.costInput() + t.costOutput() + t.costCacheRead() + t.costCacheWrite())
            .as("PRICED 行满足 cost_yuan == 四分项之和（3.0），差额恰为 LEGACY 行的 0.5")
            .isCloseTo(t.costYuan() - t.legacyCostYuan(), within(1e-9));

        assertThat(t.legacyCalls()).as("C 是唯一 LEGACY 行").isEqualTo(1);
        assertThat(t.legacyCostYuan()).isCloseTo(0.5, within(1e-9));
        assertThat(t.estimatedCalls()).isEqualTo(1);
        assertThat(t.abortedCalls()).isEqualTo(1);
        assertThat(t.failedCalls()).isZero();
        assertThat(t.cacheReadTokens()).isEqualTo(1300);
        assertThat(t.cacheWriteTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("缓存节省为负（反事实列缺失/公式异常）→ 钳到 0（WARN），⛔ 不把负数推给前端")
    void cacheSavingsClampedAtZero() {
        clearCalls();
        // 反事实项 (0.1) 小于实际分项之和 (1.0) → 节省算出来是负的
        usageMapper.insertSelective(priced("uc-neg", S1, S1, "MAIN", null, 0, null, MODEL_OPENAI,
            local(2026, 10, 20, 10, 0),
            100, 10, 0, 0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.1, "SETTLED", "UPSTREAM", 0, 10L));

        StatsService.Totals t = stats().aggregate(local(2026, 10, 20, 0, 0), local(2026, 10, 21, 0, 0)).totals();
        assertThat(t.cacheSavingsYuan()).as("负节省钳到 0（否则前端会显示「节省 -0.9 元」）").isZero();
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 5) 命中率：anthropic / 非 anthropic 两条分支 + promptTotal = 0 → null
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cacheHitRate 按协议分派分母：anthropic = input+cacheRead+cacheWrite；非 anthropic = input")
    void cacheHitRateDispatchesPerProtocol() {
        StatsService.StatsResult r = stats().aggregate(local(2026, 10, 13, 0, 0), local(2026, 10, 15, 0, 0));
        Map<String, StatsService.ModelStat> byModel = r.byModel().stream()
            .collect(Collectors.toMap(StatsService.ModelStat::model, Function.identity()));

        // anthropic：分母 = 200 + 800 + 100 = 1100 → 800/1100。若不按协议分派（只除 input）会得到 4.0
        StatsService.ModelStat claude = byModel.get(MODEL_ANTHROPIC);
        assertThat(claude.cacheHitRate()).as("anthropic 分母含 cache 两项（⛔ 不是 800/200=4.0）")
            .isCloseTo(800.0 / 1100.0, within(1e-9));
        assertThat(claude.cacheHitRate()).isLessThanOrEqualTo(1.0);
        assertThat(claude.calls()).isEqualTo(1);
        assertThat(claude.totalTokens()).as("totalTokens = input + output").isEqualTo(250);
        assertThat(claude.cacheReadInputTokens()).isEqualTo(800);
        assertThat(claude.cacheCreationInputTokens()).isEqualTo(100);
        assertThat(claude.costYuan()).isCloseTo(2.0, within(1e-9));

        // 非 anthropic（deepseek）：input 已含 cache hit → 分母只能是 input（1300）→ 500/1300
        StatsService.ModelStat deepseek = byModel.get(MODEL_OPENAI);
        assertThat(deepseek.calls()).as("A + C 两行同模型").isEqualTo(2);
        assertThat(deepseek.inputTokens()).isEqualTo(1300);
        assertThat(deepseek.cacheHitRate()).as("非 anthropic 分母 = input（⛔ 再加 cacheRead 会虚高）")
            .isCloseTo(500.0 / 1300.0, within(1e-9));

        // totals 命中率 = 逐模型分子分母分别加总后相除（⛔ 不是两行比率的平均）
        assertThat(r.totals().cacheHitRate())
            .isCloseTo(1300.0 / 2400.0, within(1e-9));
    }

    @Test
    @DisplayName("promptTotal = 0（无 prompt token 的调用）→ cacheHitRate = null（不得 NaN/Infinity、不臆造 0）")
    void cacheHitRateIsNullWhenPromptTotalIsZero() {
        clearCalls();
        usageMapper.insertSelective(priced("uc-zero", S1, S1, "MAIN", null, 0, null, MODEL_OPENAI,
            local(2026, 10, 21, 10, 0),
            0, 10, 0, 0, 0.1, 0.1, 0.0, 0.0, 0.0, 0.1, "SETTLED", "UPSTREAM", 0, 10L));

        StatsService.StatsResult r = stats().aggregate(local(2026, 10, 21, 0, 0), local(2026, 10, 22, 0, 0));
        assertThat(r.totals().cacheHitRate()).isNull();
        assertThat(r.byModel()).hasSize(1);
        assertThat(r.byModel().get(0).cacheHitRate()).isNull();
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 夹具构造
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 清表（⛔ 不能用 {@code deleteByQuery(QueryWrapper.create())}：MyBatis-Flex 拒绝无 where 的全表
     * 删除 → {@code MybatisFlexException}，夹具直接红）。
     */
    private static void clearAll() {
        clearCalls();
        for (SessionRecord s : sessionMapper.selectAll()) {
            sessionMapper.deleteById(s.getId());
        }
        for (ModelRecord m : modelMapper.selectAll()) {
            modelMapper.deleteById(m.getId());
        }
        for (ProviderRecord p : providerMapper.selectAll()) {
            providerMapper.deleteById(p.getId());
        }
    }

    private static void clearCalls() {
        for (LlmUsageCallRecord r : usageMapper.selectAll()) {
            usageMapper.deleteById(r.getId());
        }
    }

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

    private static void model(String id, String providerId, String name, String tag) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setProviderId(providerId);
        m.setName(name);
        m.setTag(tag);
        m.setEnabled(true);
        modelMapper.insertSelective(m);
    }

    private static void session(String id, String createdAt, double totalCostYuan, String modelUsageJson) {
        SessionRecord s = new SessionRecord();
        s.setId(id);
        s.setModelTag("DS");
        s.setModelName(MODEL_OPENAI);
        s.setTitle("range-fixture");
        s.setTime("刚刚");
        s.setSessionGroup("current");
        s.setCreatedAt(createdAt);
        s.setUpdatedAt(createdAt);
        s.setTotalCostYuan(totalCostYuan);
        s.setModelUsageJson(modelUsageJson);
        sessionMapper.insertSelective(s);
    }

    /** PRICED 行（五列分项齐全 · 满足 cost_yuan == 四分项之和 的写入侧不变式）。 */
    private static LlmUsageCallRecord priced(String id, String sessionId, String agentId, String category,
                                              String parentAgentId, int depth, String label, String modelName,
                                              String startedAt, int input, int output, Integer cacheRead,
                                              Integer cacheWrite, double costYuan, double costInput,
                                              double costOutput, double costCacheRead, double costCacheWrite,
                                              double costNoCacheInput, String phase, String tokenSource,
                                              int stream, long elapsedMs) {
        LlmUsageCallRecord r = base(id, sessionId, agentId, category, parentAgentId, depth, label, modelName,
            startedAt, input, output, cacheRead, cacheWrite, costYuan, phase, tokenSource, stream, elapsedMs);
        r.setCostInput(costInput);
        r.setCostOutput(costOutput);
        r.setCostCacheRead(costCacheRead);
        r.setCostCacheWrite(costCacheWrite);
        r.setCostNoCacheInput(costNoCacheInput);
        r.setCostDetailSource("PRICED");
        return r;
    }

    /** 基础行（不含分项列 · 调用方按 PRICED / LEGACY 补）。 */
    private static LlmUsageCallRecord base(String id, String sessionId, String agentId, String category,
                                           String parentAgentId, int depth, String label, String modelName,
                                           String startedAt, int input, int output, Integer cacheRead,
                                           Integer cacheWrite, double costYuan, String phase,
                                           String tokenSource, int stream, long elapsedMs) {
        LlmUsageCallRecord r = new LlmUsageCallRecord();
        r.setId(id);
        r.setSessionId(sessionId);
        r.setAgentId(agentId);
        r.setCategory(category);
        r.setParentAgentId(parentAgentId);
        r.setDepth(depth);
        r.setLabel(label);
        r.setModelName(modelName);
        r.setSdkModelName(modelName);
        r.setProviderType("anthropic".equals(modelName) ? "anthropic" : "openai_sdk");
        r.setQuerySource("USER");
        r.setStream(stream);
        r.setPhase(phase);
        r.setTokenSource(tokenSource);
        r.setInputTokens(input);
        r.setOutputTokens(output);
        r.setCacheReadInputTokens(cacheRead);
        r.setCacheCreationInputTokens(cacheWrite);
        r.setCostYuan(costYuan);
        r.setPeakHour(0);
        r.setStatus("ok");
        r.setStartedAt(startedAt);
        r.setElapsedMs((int) elapsedMs);
        return r;
    }

    // ── 时间（统一用**系统时区**渲染：与写入侧同款，见类 javadoc 的时区无关性）────────────────

    private static String local(int y, int mo, int d, int h, int mi) {
        LocalDateTime ldt = LocalDateTime.of(y, mo, d, h, mi);
        return OffsetDateTime.of(ldt, ZoneId.systemDefault().getRules().getOffset(ldt)).toString();
    }

    private static long epochMs(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** byDay 的日期键（本地日期 · 与 {@code substr(started_at,1,10)} 同口径）。 */
    private static String dateKey(int y, int mo, int d) {
        return String.format("%04d-%02d-%02d", y, mo, d);
    }
}
