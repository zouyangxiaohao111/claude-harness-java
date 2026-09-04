package com.nexusai.domain.stats;

import com.nexusai.domain.stats.StatsService.DayStat;
import com.nexusai.domain.stats.StatsService.ModelStat;
import com.nexusai.domain.stats.StatsService.StatsResult;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StatsService 聚合语义（B2 · CC /stats 维度按天/按模型统计）。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：前端「按天/按模型统计图表」的数据源是 sessions 表的
 * model_usage_json + total_cost_yuan + created_at。本测试钉死聚合契约：
 * <ol>
 *   <li><b>totals</b>——全量会话数 / token 总合（各模型 input+output 求和）/ 花费总合（元）；</li>
 *   <li><b>byDay</b>——created_at 日期分组、token+cost 归日、按日期升序、无 usage 会话 token=0 但 cost 计入；</li>
 *   <li><b>byModel</b>——模型 key 分组、5 字段跨会话累加、按 token 降序；</li>
 *   <li><b>fail-soft</b>——脏 JSON / 无 created_at 不炸，脏 JSON 会话跳过 token 但仍计 cost。</li>
 * </ol>
 * 变异点：任一分组/累加/排序/容错缺失 → 图表数据错位或 500，前端统计失真。
 */
@DisplayName("[B2] StatsService 按天/按模型聚合")
class StatsServiceTest {

    private StatsService service;
    private SessionMapper sessionMapper;

    @BeforeEach
    void setUp() {
        service = new StatsService();
        sessionMapper = mock(SessionMapper.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
    }

    private static SessionRecord session(String id, String createdAt, Double costYuan, String modelUsageJson) {
        SessionRecord s = new SessionRecord();
        s.setId(id);
        s.setCreatedAt(createdAt);
        s.setTotalCostYuan(costYuan);
        s.setModelUsageJson(modelUsageJson);
        return s;
    }

    @Test
    @DisplayName("聚合：totals + byDay(升序) + byModel(token 降序)，无 usage 会话 token=0 但 cost 计入")
    void aggregate_combinesByDayAndByModel() {
        // WHY: 前端三视图同源——totals 汇总、byDay 折线、byModel 条形，任一分组错误即图表失真。
        when(sessionMapper.selectAll()).thenReturn(List.of(
            session("sess-a", "2026-08-26T10:00:00+08:00", 1.0,
                "{\"model-alpha\":{\"inputTokens\":100,\"outputTokens\":50,\"cacheReadInputTokens\":10,"
                    + "\"cacheCreationInputTokens\":5,\"webSearchRequests\":0,\"costUSD\":0.01,"
                    + "\"contextWindow\":200000,\"maxOutputTokens\":8192},"
                    + "\"model-beta\":{\"inputTokens\":200,\"outputTokens\":30,\"cacheReadInputTokens\":0,"
                    + "\"cacheCreationInputTokens\":0,\"webSearchRequests\":0,\"costUSD\":0.02,"
                    + "\"contextWindow\":200000,\"maxOutputTokens\":8192}}"),
            session("sess-b", "2026-08-26T12:00:00+08:00", 2.0,
                "{\"model-alpha\":{\"inputTokens\":300,\"outputTokens\":100,\"cacheReadInputTokens\":20,"
                    + "\"cacheCreationInputTokens\":0,\"webSearchRequests\":0,\"costUSD\":0.03,"
                    + "\"contextWindow\":200000,\"maxOutputTokens\":8192}}"),
            // 无 model_usage_json → byDay token=0 但 cost 计入
            session("sess-c", "2026-08-27T09:00:00+08:00", 4.0, null),
            // 脏 JSON → fail-soft，跳过该会话 token 但仍计 cost
            session("sess-d", "2026-08-28T09:00:00+08:00", 8.0, "{not-valid-json"))
        );

        StatsResult r = service.aggregate();

        // totals: token = (100+50+200+30)+(300+100)+0+0 = 780; cost = 1+2+4+8 = 15
        assertThat(r.totals().sessionCount()).as("全量会话数").isEqualTo(4);
        assertThat(r.totals().tokenCount()).as("全量 token（input+output 求和）").isEqualTo(780L);
        assertThat(r.totals().costYuan()).as("全量花费（元）").isCloseTo(15.0, within(0.0001));

        // byDay 按日期升序
        assertThat(r.byDay()).extracting(DayStat::date)
            .as("byDay 日期升序").containsExactly("2026-08-26", "2026-08-27", "2026-08-28");
        DayStat d26 = r.byDay().get(0);
        // 08-26 = sess-a(380) + sess-b(400) = 780
        assertThat(d26.tokenCount()).as("08-26 token 归日（sess-a 380 + sess-b 400）").isEqualTo(780L);
        assertThat(d26.costYuan()).as("08-26 cost 归日").isCloseTo(3.0, within(0.0001));
        assertThat(r.byDay().get(1).tokenCount()).as("08-27 无 usage → token=0").isZero();
        assertThat(r.byDay().get(1).costYuan()).as("08-27 无 usage → cost 仍计入").isCloseTo(4.0, within(0.0001));
        assertThat(r.byDay().get(2).tokenCount()).as("08-28 脏 JSON → token=0").isZero();
        assertThat(r.byDay().get(2).costYuan()).as("08-28 脏 JSON → cost 仍计入").isCloseTo(8.0, within(0.0001));

        // byModel 按 token 降序：model-alpha(550) 在前，model-beta(230) 在后
        assertThat(r.byModel()).extracting(ModelStat::model)
            .as("byModel token 降序").containsExactly("model-alpha", "model-beta");
        ModelStat alpha = r.byModel().get(0);
        assertThat(alpha.inputTokens()).isEqualTo(400L);
        assertThat(alpha.outputTokens()).isEqualTo(150L);
        assertThat(alpha.cacheReadInputTokens()).isEqualTo(30L);
        assertThat(alpha.cacheCreationInputTokens()).isEqualTo(5L);
        assertThat(alpha.costUSD()).as("costUSD 各桶累加").isCloseTo(0.04, within(0.0001));
        ModelStat beta = r.byModel().get(1);
        assertThat(beta.inputTokens()).isEqualTo(200L);
        assertThat(beta.outputTokens()).isEqualTo(30L);
        assertThat(beta.costUSD()).isCloseTo(0.02, within(0.0001));
        // [A5-2] 无 mapper 注入（测试直构）→ isAnthropic 回落 false → anthropic=false（deepseek/openai
        //   语义，前端按 input+output 分派 total；前端据此不再对 cache 字段重复求和）
        assertThat(alpha.anthropic()).as("无 mapper → 标志 false（非 anthropic 语义）").isFalse();
        assertThat(beta.anthropic()).isFalse();
    }

    @Test
    @DisplayName("空 sessions → 空结果（totals 零，byDay/byModel 空数组）")
    void aggregate_emptyReturnsZeros() {
        // WHY: 图表首次加载 / 无任何会话 → 必须 200 空结构而非异常（fail-soft，前端可直接渲染）。
        when(sessionMapper.selectAll()).thenReturn(List.of());

        StatsResult r = service.aggregate();

        assertThat(r.totals().sessionCount()).isZero();
        assertThat(r.totals().tokenCount()).isZero();
        assertThat(r.totals().costYuan()).isZero();
        assertThat(r.byDay()).isEmpty();
        assertThat(r.byModel()).isEmpty();
    }

    @Test
    @DisplayName("fail-soft：脏 JSON 会话跳过 token 但仍计 cost；无 created_at 跳过 byDay 但计入 totals")
    void aggregate_invalidJsonAndNullCreatedAt() {
        // WHY: 脏 JSON（历史/半写）+ 空 created_at（脏数据）不应让整表统计 500——分别降级：
        //   token 归零 / 归入 totals 而不归日。变异点：任一处抛异常 → 前端统计不可用。
        when(sessionMapper.selectAll()).thenReturn(List.of(
            session("sess-f", "2026-08-28T09:00:00+08:00", 8.0, "{dirty"),
            session("sess-g", null, 5.0,
                "{\"model-x\":{\"inputTokens\":50,\"outputTokens\":20,\"cacheReadInputTokens\":0,"
                    + "\"cacheCreationInputTokens\":0,\"webSearchRequests\":0,\"costUSD\":0.005,"
                    + "\"contextWindow\":200000,\"maxOutputTokens\":8192}}")
        ));

        StatsResult r = service.aggregate();

        assertThat(r.totals().sessionCount()).as("脏数据会话也计入会话数").isEqualTo(2);
        assertThat(r.totals().tokenCount()).as("脏 JSON 会话 token=0，仅 model-x 70 token").isEqualTo(70L);
        assertThat(r.totals().costYuan()).as("脏 JSON + 无 created_at 会话 cost 均计入").isCloseTo(13.0, within(0.0001));

        // byDay 只含 sess-f（sess-g 无 created_at 跳过）；sess-f 脏 JSON → token=0 但 cost=8
        assertThat(r.byDay()).extracting(DayStat::date)
            .as("无 created_at 会话跳过 byDay").containsExactly("2026-08-28");
        assertThat(r.byDay().get(0).tokenCount()).isZero();
        assertThat(r.byDay().get(0).costYuan()).isCloseTo(8.0, within(0.0001));

        // byModel：model-x 桶跨会话累加（sess-g）
        assertThat(r.byModel()).hasSize(1);
        assertThat(r.byModel().get(0).model()).isEqualTo("model-x");
        assertThat(r.byModel().get(0).inputTokens()).isEqualTo(50L);
        assertThat(r.byModel().get(0).outputTokens()).isEqualTo(20L);
        assertThat(r.byModel().get(0).costUSD()).isCloseTo(0.005, within(0.0001));
    }
}
