package com.nexusai.repository.usage;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.cost.SessionUsageLedger;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.domain.stats.StatsService;
import com.nexusai.infra.llm.LlmCallRecord;
import com.nexusai.infra.llm.LlmUsagePhase;
import com.nexusai.infra.llm.UsageAttribution;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.infra.llm.UsageTokenSource;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [usage-gateway] {@code llm_usage_calls} 明细表<b>真库</b>落表 + 前端两维聚合契约。
 *
 * <h2>WHY（规则九：不落这条会静默丢在哪）</h2>
 * 前端「统计」标签页的「按调用类型 / 按代理层级」两维直接吃 {@code GET /api/v1/stats} 的
 * {@code byCategory} / {@code byAgent}，而这两维的数据源只有一处：{@code SessionUsageLedger.persist()}
 * → {@link LlmUsageCallMapper#insert}。这条链路此前**没有任何真库用例**（既有账本用例只跑内存聚合，
 * 不注入 mapper），因此存在两种会静默失血的故障：
 * <ol>
 *   <li><b>列名/类型映射错</b>：实体字段与 V98 建表脚本对不上 → 每次 {@code insert} 抛异常 →
 *       {@code persist()} 只记 WARN + 计数（设计如此：统计失败不得中断 LLM 调用链）⇒
 *       <b>接口永远返回空数组，前端永远显示「暂无明细数据」，无人发现</b>；</li>
 *   <li><b>可空列崩插入</b>：中断/失败调用没有 cache token 与 errorKind，若实体/建表不允许 null ⇒
 *       恰好丢掉「最需要被看见」的那部分调用（流中断、失败）。</li>
 * </ol>
 * 本类用真 SQLite + Flyway 打通「账本记账 → 落表 → 读回 → StatsService 聚合」全链，并钉住前端所依赖的
 * 聚合语义（分组键、排序、计数口径）。
 *
 * <h2>RED（改坏哪条会翻红）</h2>
 * <ul>
 *   <li>{@code persist()} 里漏 set 任一列（或列名写错）→ 全字段往返用例红；</li>
 *   <li>把可空列改成基本类型/加 NOT NULL → 中断用例红；</li>
 *   <li>{@code aggregateByCategory} 的排序从「费用降序」改成别的 → 排序断言红；</li>
 *   <li>{@code aggregateByAgent} 丢掉 parent/depth → 层级断言红。</li>
 * </ul>
 */
@DisplayName("[usage-gateway] llm_usage_calls 落表 + byCategory/byAgent 聚合契约（前端两维数据源）")
class LlmUsageCallStorageTest {

    private static final String SESSION = "sess-usage-storage";
    private static final String MAIN_AGENT = SESSION;
    private static final String CHILD_AGENT = "agent-child-1";

    private static LlmUsageCallMapper mapper;
    private static SessionMapper sessionMapper;

    /** 真库 + 全量迁移（含 V98 建表 / V99 会话列）。 */
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

        MybatisFlexDbTestSupport.resetAndStart(ds, LlmUsageCallMapper.class, SessionMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(LlmUsageCallMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
    }

    @BeforeEach
    void cleanTable() {
        for (LlmUsageCallRecord r : mapper.selectAll()) {
            mapper.deleteById(r.getId());
        }
    }

    /**
     * 计价替身：固定 1.23 元 / 高峰档（只为让落表用例断言到非零金额与非零标志）。
     *
     * <p>V101：账本已改为单点 {@code price(...)}（总额 = {@code breakdown.totalYuan()}），故桩必须打在
     * {@code price} 上并把分项也填齐（{@code calculateCostYuan} 的旧桩会返回 null → 落表变 LEGACY +
     * 金额 0 → 本类所有 cost==1.23 断言红）。分项四项之和恰为总额，与 V101 不变式一致。
     */
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

    /** 造一条完整归属的调用记录（流式 + 已结算）。 */
    private static LlmCallRecord streamCall(String callId, UsageAttribution who, String modelName,
                                            AgentUsage u, long elapsedMs) {
        return new LlmCallRecord(callId, who, modelName, modelName + "-sdk", "anthropic",
            "USER", true, LlmUsagePhase.SETTLED, UsageTokenSource.UPSTREAM, u,
            0.0, false, null, "ok", null, 1_700_000_000_000L, elapsedMs);
    }

    @Test
    @DisplayName("账本记账 → 真库落表 → 读回：25 列逐字段一致（列名/类型错即整表静默为空）")
    void ledgerPersistsFullRow() {
        SessionUsageLedger ledger = new SessionUsageLedger(SESSION, fixedPriceCalculator(), mapper);
        UsageAttribution who = UsageAttribution.top(SESSION, MAIN_AGENT, UsageCategory.MAIN, null);
        String callId = LlmCallRecord.newCallId();

        ledger.record(streamCall(callId, who, "claude-sonnet-4-20250514",
            usage(1500, 300, 900L, 120L), 4321L));

        LlmUsageCallRecord row = mapper.selectOneById(callId);
        assertThat(row).as("账本记账必须落表（未落表 → 前端两维永远空）").isNotNull();
        assertThat(row.getSessionId()).isEqualTo(SESSION);
        assertThat(row.getAgentId()).isEqualTo(MAIN_AGENT);
        assertThat(row.getCategory()).isEqualTo("MAIN");
        assertThat(row.getParentAgentId()).as("主 agent 无父").isNull();
        assertThat(row.getDepth()).isEqualTo(0);
        assertThat(row.getModelName()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(row.getSdkModelName()).isEqualTo("claude-sonnet-4-20250514-sdk");
        assertThat(row.getProviderType()).isEqualTo("anthropic");
        assertThat(row.getQuerySource()).isEqualTo("USER");
        assertThat(row.getStream()).as("流式标志").isEqualTo(1);
        assertThat(row.getPhase()).isEqualTo("SETTLED");
        assertThat(row.getTokenSource()).as("上游真实值").isEqualTo("UPSTREAM");
        assertThat(row.getInputTokens()).isEqualTo(1500);
        assertThat(row.getOutputTokens()).isEqualTo(300);
        assertThat(row.getCacheReadInputTokens()).isEqualTo(900);
        assertThat(row.getCacheCreationInputTokens()).isEqualTo(120);
        assertThat(row.getCostYuan()).as("计价结果落库（¥/元）").isEqualTo(1.23);
        assertThat(row.getPeakHour()).as("高峰档标志").isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo("ok");
        assertThat(row.getErrorKind()).as("成功调用无错误种类").isNull();
        assertThat(row.getStartedAt()).as("ISO-8601 字符串（前端按耗时展示，不解析时间）").isNotBlank();
        assertThat(row.getElapsedMs()).isEqualTo(4321);
        assertThat(row.getCreatedAt())
            .as("created_at 必须由 DB DEFAULT 填充（改回 insert() 会显式写 NULL → 约束失败 → 整表静默为空）")
            .isNotBlank();
        assertThat(ledger.persistFailureCount()).as("落表失败计数必须为 0").isZero();
    }

    @Test
    @DisplayName("中断/失败行（无 cache、无父、有 errorKind）同样能落表 —— 最需要被看见的调用不得丢")
    void abortedRowWithoutCachePersists() {
        SessionUsageLedger ledger = new SessionUsageLedger(SESSION, fixedPriceCalculator(), mapper);
        UsageAttribution who = UsageAttribution.top(SESSION, MAIN_AGENT, UsageCategory.MAIN, null)
            .child(CHILD_AGENT, UsageCategory.SUBAGENT, "alice");
        String callId = LlmCallRecord.newCallId();
        LlmCallRecord aborted = new LlmCallRecord(callId, who, "m", "m-sdk", "openai",
            "SUBAGENT", false, LlmUsagePhase.ABORTED, UsageTokenSource.UNKNOWN,
            usage(0, 0, null, null), 0.0, false, null, "aborted", "AbortException", 1_700_000_000_000L, 250L);

        ledger.record(aborted);

        LlmUsageCallRecord row = mapper.selectOneById(callId);
        assertThat(row).as("中断调用也必须落表（统计要覆盖取消/中断场景）").isNotNull();
        assertThat(row.getPhase()).isEqualTo("ABORTED");
        assertThat(row.getTokenSource()).as("无数据不伪造 → UNKNOWN").isEqualTo("UNKNOWN");
        assertThat(row.getCacheReadInputTokens()).isNull();
        assertThat(row.getCacheCreationInputTokens()).isNull();
        assertThat(row.getErrorKind()).isEqualTo("AbortException");
        assertThat(row.getParentAgentId()).as("子代理归属链落库（层级树的数据源）").isEqualTo(MAIN_AGENT);
        assertThat(row.getDepth()).isEqualTo(1);
        assertThat(row.getLabel()).isEqualTo("alice");
    }

    @Test
    @DisplayName("StatsService 两维聚合：分组键 / 费用降序 / 层级序 / 估算·中断·失败计数（前端直接消费）")
    void statsAggregatesByCategoryAndAgent() {
        SessionUsageLedger ledger = new SessionUsageLedger(SESSION, fixedPriceCalculator(), mapper);
        UsageAttribution main = UsageAttribution.top(SESSION, MAIN_AGENT, UsageCategory.MAIN, null);
        UsageAttribution child = main.child(CHILD_AGENT, UsageCategory.SUBAGENT, "alice");

        // 同价（1.23/次）→ 费用差靠行数体现：MAIN 2 行、SUBAGENT 1 行
        ledger.record(streamCall(LlmCallRecord.newCallId(), main, "m",
            usage(100, 10, null, null), 100L));
        ledger.record(streamCall(LlmCallRecord.newCallId(), main, "m",
            usage(200, 20, null, null), 100L));
        ledger.record(new LlmCallRecord(LlmCallRecord.newCallId(), child, "m", "m-sdk", "openai",
            "SUBAGENT", true, LlmUsagePhase.ABORTED, UsageTokenSource.ESTIMATED,
            usage(50, 5, null, null), 0.0, false, null, "aborted", null, 1_700_000_000_000L, 90L));

        StatsService stats = new StatsService();
        ReflectionTestUtils.setField(stats, "usageCallMapper", mapper);

        StatsService.StatsResult result = stats.aggregate();

        // --- byCategory：按费用降序（前端条形图按金额取比例），计数口径 = 每次尝试一行 ---
        List<StatsService.CategoryStat> byCategory = result.byCategory();
        assertThat(byCategory).extracting(StatsService.CategoryStat::category)
            .as("费用降序：主对话 2 次 > 子代理 1 次").containsExactly("MAIN", "SUBAGENT");
        StatsService.CategoryStat mainCat = byCategory.get(0);
        assertThat(mainCat.calls()).isEqualTo(2);
        assertThat(mainCat.inputTokens()).isEqualTo(300);
        assertThat(mainCat.outputTokens()).isEqualTo(30);
        assertThat(mainCat.costYuan()).isCloseTo(2.46, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(mainCat.streamCalls()).isEqualTo(2);
        assertThat(mainCat.nonStreamCalls()).isZero();
        assertThat(mainCat.estimatedCalls()).as("MAIN 无估算行").isZero();
        assertThat(mainCat.abortedCalls()).isZero();

        StatsService.CategoryStat subCat = byCategory.get(1);
        assertThat(subCat.calls()).isEqualTo(1);
        assertThat(subCat.estimatedCalls()).as("估算值单独计数（不可当账单）").isEqualTo(1);
        assertThat(subCat.abortedCalls()).as("中断单独计数（上游可能仍计费）").isEqualTo(1);

        // --- byAgent：层级序（父在前）+ 父子链接（前端缩进靠 depth） ---
        List<StatsService.AgentStat> byAgent = result.byAgent();
        Map<String, StatsService.AgentStat> byId = byAgent.stream()
            .collect(Collectors.toMap(StatsService.AgentStat::agentId, Function.identity()));
        assertThat(byAgent).extracting(StatsService.AgentStat::agentId)
            .as("层级序：父先于子").containsExactly(MAIN_AGENT, CHILD_AGENT);
        assertThat(byId.get(MAIN_AGENT).depth()).isZero();
        assertThat(byId.get(MAIN_AGENT).parentAgentId()).isNull();
        assertThat(byId.get(MAIN_AGENT).calls()).isEqualTo(2);
        assertThat(byId.get(CHILD_AGENT).depth()).isEqualTo(1);
        assertThat(byId.get(CHILD_AGENT).parentAgentId()).as("前端据此还原父子链").isEqualTo(MAIN_AGENT);
        assertThat(byId.get(CHILD_AGENT).label()).isEqualTo("alice");
        assertThat(byId.get(CHILD_AGENT).category()).isEqualTo("SUBAGENT");
        assertThat(byId.get(CHILD_AGENT).costYuan()).isCloseTo(1.23, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("节点身份按归类优先级取：子代理节点不得被它自己的旁路调用命名（首行是旁路也显示「子代理」）")
    void nodeIdentityPrefersAgentOwnCategory() {
        SessionUsageLedger ledger = new SessionUsageLedger(SESSION, fixedPriceCalculator(), mapper);
        UsageAttribution main = UsageAttribution.top(SESSION, MAIN_AGENT, UsageCategory.MAIN, null);
        String hexAgentId = "a0123456789abcdef";
        UsageAttribution subagent = main.child(hexAgentId, UsageCategory.SUBAGENT, "general-purpose");
        // 先写入该子代理发起的旁路调用（首行 = SIDE_CALL），再写入子代理本体的调用
        ledger.record(streamCall(LlmCallRecord.newCallId(),
            new UsageAttribution(SESSION, hexAgentId, UsageCategory.SIDE_CALL, SESSION, 1, "skill-catalog-summary"),
            "m", usage(50, 5, null, null), 50L));
        ledger.record(streamCall(LlmCallRecord.newCallId(), subagent, "m", usage(100, 10, null, null), 100L));

        StatsService stats = new StatsService();
        ReflectionTestUtils.setField(stats, "usageCallMapper", mapper);

        StatsService.SessionAgentUsage node = stats.sessionUsage(SESSION).agents().stream()
            .filter(a -> hexAgentId.equals(a.agentId())).findFirst().orElseThrow();
        assertThat(node.calls()).as("两行都算进同一节点").isEqualTo(2);
        assertThat(node.category()).as("身份取 agent 自身归类（SUBAGENT），⛔ 不是首行的 SIDE_CALL")
            .isEqualTo("SUBAGENT");
        assertThat(node.label()).as("label 取身份行（子代理名），不是旁路用途名")
            .isEqualTo("general-purpose");

        // 全量视图同口径
        StatsService.AgentStat all = stats.aggregate().byAgent().stream()
            .filter(a -> hexAgentId.equals(a.agentId())).findFirst().orElseThrow();
        assertThat(all.category()).isEqualTo("SUBAGENT");
        assertThat(all.label()).isEqualTo("general-purpose");
    }

    @Test
    @DisplayName("同一 agent 的两种 id 渲染（a+16hex 与 UUID）必须并成一个节点 —— 历史行不得裂成两行")
    void canonicalizesAgentIdAcrossRenderings() {
        SessionUsageLedger ledger = new SessionUsageLedger(SESSION, fixedPriceCalculator(), mapper);
        UsageAttribution main = UsageAttribution.top(SESSION, MAIN_AGENT, UsageCategory.MAIN, null);
        String hexAgentId = "a0123456789abcdef";
        String uuidForm = "01234567-89ab-cdef-0000-000000000000";   // packAgentId(hex).toString()
        // 本体调用（hex 形式）+ 其旁路调用（归一前写入的历史形态：UUID 形式）
        ledger.record(streamCall(LlmCallRecord.newCallId(),
            main.child(hexAgentId, UsageCategory.SUBAGENT, "general-purpose"), "m",
            usage(100, 10, null, null), 100L));
        ledger.record(streamCall(LlmCallRecord.newCallId(),
            new UsageAttribution(SESSION, uuidForm, UsageCategory.SIDE_CALL, SESSION, 1, "skill-catalog-summary"),
            "m", usage(300, 30, null, null), 300L));

        StatsService stats = new StatsService();
        ReflectionTestUtils.setField(stats, "usageCallMapper", mapper);

        // 夹具只造了子代理的两行（本体 hex + 旁路 UUID）→ 归并后应当**只有 1 个节点**：
        //   若身份未归一 ⇒ 2 个节点（同名两行、费用被拆开）
        List<StatsService.SessionAgentUsage> agents = stats.sessionUsage(SESSION).agents();
        assertThat(agents).extracting(StatsService.SessionAgentUsage::agentId)
            .as("两种渲染必须并成一个节点（否则前端同名两行、费用被拆开）")
            .containsExactly(hexAgentId);
        StatsService.SessionAgentUsage child = agents.stream()
            .filter(a -> hexAgentId.equals(a.agentId())).findFirst().orElseThrow();
        assertThat(child.calls()).as("本体 1 次 + 旁路 1 次 = 2 次（同一节点）").isEqualTo(2);
        assertThat(child.inputTokens()).isEqualTo(400);
        assertThat(child.taskId()).as("归一后仍可换算 taskId（前端关联键）")
            .isEqualTo(uuidForm);

        // 全量视图同口径（同样只有 1 个节点）
        StatsService.StatsResult all = stats.aggregate();
        assertThat(all.byAgent()).extracting(StatsService.AgentStat::agentId)
            .containsExactly(hexAgentId);
    }

    @Test
    @DisplayName("会话级视图：只含本会话节点 · taskId 由 a+16hex 确定性换算（前端按 task_id 关联）· 层级序")
    void sessionUsageScopesBySessionAndConvertsTaskId() {
        SessionUsageLedger ledger = new SessionUsageLedger(SESSION, fixedPriceCalculator(), mapper);
        UsageAttribution main = UsageAttribution.top(SESSION, MAIN_AGENT, UsageCategory.MAIN, null);
        String hexAgentId = "a0123456789abcdef";
        UsageAttribution child = main.child(hexAgentId, UsageCategory.SUBAGENT, "alice");

        ledger.record(streamCall(LlmCallRecord.newCallId(), main, "m", usage(100, 10, null, null), 200L));
        ledger.record(streamCall(LlmCallRecord.newCallId(), child, "m", usage(300, 30, null, null), 500L));

        // 另一会话的行（用来验证「只含本会话」，不串台）
        String otherSession = "sess-other";
        SessionUsageLedger otherLedger = new SessionUsageLedger(otherSession, fixedPriceCalculator(), mapper);
        otherLedger.record(streamCall(LlmCallRecord.newCallId(),
            UsageAttribution.top(otherSession, otherSession, UsageCategory.MAIN, null), "m",
            usage(9999, 999, null, null), 100L));

        StatsService stats = new StatsService();
        ReflectionTestUtils.setField(stats, "usageCallMapper", mapper);

        StatsService.SessionUsage usage = stats.sessionUsage(SESSION);

        assertThat(usage.sessionId()).isEqualTo(SESSION);
        assertThat(usage.agents()).extracting(StatsService.SessionAgentUsage::agentId)
            .as("只含本会话节点（other 会话 9999 token 不得串入）")
            .containsExactly(MAIN_AGENT, hexAgentId);
        StatsService.SessionUsageTotals totals = usage.totals();
        assertThat(totals.calls()).isEqualTo(2);
        assertThat(totals.inputTokens()).isEqualTo(400);
        assertThat(totals.outputTokens()).isEqualTo(40);
        assertThat(totals.costYuan()).isCloseTo(2.46, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(totals.elapsedMs()).as("模型调用耗时求和（200+500）").isEqualTo(700);
        assertThat(usage.byCategory()).extracting(StatsService.CategoryStat::category)
            .containsExactlyInAnyOrder("MAIN", "SUBAGENT");

        StatsService.SessionAgentUsage mainNode = usage.agents().get(0);
        assertThat(mainNode.taskId()).as("主 agent 用会话短 id，无对应 task").isNull();
        assertThat(mainNode.depth()).isZero();

        StatsService.SessionAgentUsage childNode = usage.agents().get(1);
        assertThat(childNode.taskId())
            .as("a+16hex → packAgentId().toString() = 任务注册表 taskId（前端关联键）")
            .isEqualTo("01234567-89ab-cdef-0000-000000000000");
        assertThat(childNode.parentTaskId()).isNull();
        assertThat(childNode.parentAgentId()).isEqualTo(MAIN_AGENT);
        assertThat(childNode.depth()).isEqualTo(1);
        assertThat(childNode.label()).isEqualTo("alice");
        assertThat(childNode.calls()).isEqualTo(1);
        assertThat(childNode.inputTokens()).isEqualTo(300);
        assertThat(childNode.elapsedMs()).isEqualTo(500);
        assertThat(childNode.costYuan()).isCloseTo(1.23, org.assertj.core.data.Offset.offset(1e-9));

        // 空白 sessionId → fail loud（ValidationException 由 GlobalExceptionHandler 映射为 400）
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stats.sessionUsage("  "))
            .isInstanceOf(com.nexusai.infra.exception.ValidationException.class);
    }
}
