package com.nexusai.apis.stats;

import com.nexusai.domain.stats.StatsService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [usage-gateway] {@code GET /api/v1/stats} 的 <b>JSON 契约</b>（前端「统计」标签页四维取数口）。
 *
 * <h2>WHY（规则九：这条守的是哪类真实故障）</h2>
 * 前端按<b>字段名</b>读这份 JSON（{@code totals.cacheSavingsYuan} 上屏「缓存省了多少钱」、
 * {@code byModel[].cacheHitRate} 画命中率、{@code byDay[].date} 做 X 轴）。record 组件名 ⇄ JSON 键
 * 一旦被改名/删字段，前端不报错，只会静默显示 {@code undefined} 或「统计不可用」。
 * 本类把前端消费的每个键钉死，并额外守住两条<b>本次行为变更</b>（L1）：
 * <ol>
 *   <li>{@code byModel[].costUSD} 与 {@code byModel[].anthropic} <b>必须不存在</b>
 *       （改 {@code costYuan}；协议分派已内化到 {@code cacheHitRate}）—— 若有人「顺手」加回来，
 *       前端会重新按 anthropic 标志分派求和，命中率/费用口径重新分叉；</li>
 *   <li>{@code byDay[]} 带 {@code calls}（调用发生日口径的新字段）。</li>
 * </ol>
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>给任一端点字段加 {@code @JsonProperty("...")} 改名（或改 record 组件名）→ 对应 jsonPath 红；</li>
 *   <li>控制器不转发 {@code from}/{@code to}（漏参）→ 参数透传用例红；</li>
 *   <li>非法区间的异常类型换成 {@code IllegalArgumentException} → 400 用例红（变 500）。</li>
 * </ul>
 */
@DisplayName("[usage-gateway] GET /api/v1/stats · JSON 契约（前端统计页四维取数口）")
class StatsEndpointContractTest {

    private static final String FROM = "2026-10-13T00:00:00+08:00";
    private static final String TO = "2026-10-15T00:00:00+08:00";

    private MockMvc mockMvc;
    private StatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = mock(StatsService.class);
        StatsController controller = new StatsController();
        ReflectionTestUtils.setField(controller, "statsService", statsService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static StatsService.StatsResult sample() {
        StatsService.Range range = new StatsService.Range(FROM, TO, true);
        StatsService.Totals totals = new StatsService.Totals(
            2, 3, 1680, 1300, 180, 1300, 100, 3.5,
            0.9, 1.0, 0.2, 0.9, 4.4, 2.4, 0.5416666,
            1, 1, 0, 1, 0.5);
        StatsService.DayStat day = new StatsService.DayStat("2026-10-13", 1, 1100, 1.0);
        StatsService.ModelStat model = new StatsService.ModelStat(
            "deepseek-chat", 2, 1300, 180, 500, 0, 1480, 1.5, 0.5, 0.4, 0.1, 0.0, 0.384615);
        StatsService.CategoryStat cat = new StatsService.CategoryStat(
            "MAIN", 3, 1300, 180, 1300, 100, 3.5, 1, 1, 0, 2, 1);
        StatsService.AgentStat agent = new StatsService.AgentStat(
            "sess-1", "MAIN", null, 0, null, 3, 1300, 180, 1300, 100, 3.5, 1, 1, 0, 2, 1);
        return new StatsService.StatsResult(range, totals, List.of(day), List.of(model), List.of(cat), List.of(agent));
    }

    @Test
    @DisplayName("200 · 前端消费的每个键都在（range / totals 20 键 / byDay / byModel / byCategory / byAgent）")
    void jsonShapeMatchesFrontContract() throws Exception {
        when(statsService.aggregate(any(), any())).thenReturn(sample());

        mockMvc.perform(get("/api/v1/stats").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            // range 回显
            .andExpect(jsonPath("$.range.from").value(FROM))
            .andExpect(jsonPath("$.range.to").value(TO))
            .andExpect(jsonPath("$.range.filtered").value(true))
            // totals：会话/调用/token 四通道 + 费用六列 + 缓存节省/命中率 + 结局计数 + LEGACY 单列
            .andExpect(jsonPath("$.totals.sessionCount").value(2))
            .andExpect(jsonPath("$.totals.calls").value(3))
            .andExpect(jsonPath("$.totals.tokenCount").value(1680))
            .andExpect(jsonPath("$.totals.inputTokens").value(1300))
            .andExpect(jsonPath("$.totals.outputTokens").value(180))
            .andExpect(jsonPath("$.totals.cacheReadTokens").value(1300))
            .andExpect(jsonPath("$.totals.cacheWriteTokens").value(100))
            .andExpect(jsonPath("$.totals.costYuan").value(3.5))
            .andExpect(jsonPath("$.totals.costInput").value(0.9))
            .andExpect(jsonPath("$.totals.costOutput").value(1.0))
            .andExpect(jsonPath("$.totals.costCacheRead").value(0.2))
            .andExpect(jsonPath("$.totals.costCacheWrite").value(0.9))
            .andExpect(jsonPath("$.totals.costNoCacheInput").value(4.4))
            .andExpect(jsonPath("$.totals.cacheSavingsYuan").value(2.4))
            .andExpect(jsonPath("$.totals.cacheHitRate").value(0.5416666))
            .andExpect(jsonPath("$.totals.estimatedCalls").value(1))
            .andExpect(jsonPath("$.totals.abortedCalls").value(1))
            .andExpect(jsonPath("$.totals.failedCalls").value(0))
            .andExpect(jsonPath("$.totals.legacyCalls").value(1))
            .andExpect(jsonPath("$.totals.legacyCostYuan").value(0.5))
            // byDay：调用发生日 + calls
            .andExpect(jsonPath("$.byDay[0].date").value("2026-10-13"))
            .andExpect(jsonPath("$.byDay[0].calls").value(1))
            .andExpect(jsonPath("$.byDay[0].tokenCount").value(1100))
            .andExpect(jsonPath("$.byDay[0].costYuan").value(1.0))
            // byModel：新增 calls / totalTokens / 四分项 / cacheHitRate；⛔ 不得再有 costUSD / anthropic
            .andExpect(jsonPath("$.byModel[0].model").value("deepseek-chat"))
            .andExpect(jsonPath("$.byModel[0].calls").value(2))
            .andExpect(jsonPath("$.byModel[0].inputTokens").value(1300))
            .andExpect(jsonPath("$.byModel[0].outputTokens").value(180))
            .andExpect(jsonPath("$.byModel[0].cacheReadInputTokens").value(500))
            .andExpect(jsonPath("$.byModel[0].cacheCreationInputTokens").value(0))
            .andExpect(jsonPath("$.byModel[0].totalTokens").value(1480))
            .andExpect(jsonPath("$.byModel[0].costYuan").value(1.5))
            .andExpect(jsonPath("$.byModel[0].costInput").value(0.5))
            .andExpect(jsonPath("$.byModel[0].costOutput").value(0.4))
            .andExpect(jsonPath("$.byModel[0].costCacheRead").value(0.1))
            .andExpect(jsonPath("$.byModel[0].costCacheWrite").value(0.0))
            .andExpect(jsonPath("$.byModel[0].cacheHitRate").value(0.384615))
            // L1 行为变更：costUSD 已删（改 costYuan）、anthropic 标志已删（协议分派内化到 cacheHitRate）
            .andExpect(jsonPath("$.byModel[0].costUSD").doesNotExist())
            .andExpect(jsonPath("$.byModel[0].anthropic").doesNotExist())
            // byCategory / byAgent：字段集与改造前逐字一致（前端两维直接消费）
            .andExpect(jsonPath("$.byCategory[0].category").value("MAIN"))
            .andExpect(jsonPath("$.byCategory[0].calls").value(3))
            .andExpect(jsonPath("$.byCategory[0].estimatedCalls").value(1))
            .andExpect(jsonPath("$.byCategory[0].streamCalls").value(2))
            .andExpect(jsonPath("$.byCategory[0].nonStreamCalls").value(1))
            .andExpect(jsonPath("$.byCategory[0].costYuan").value(3.5))
            .andExpect(jsonPath("$.byAgent[0].agentId").value("sess-1"))
            .andExpect(jsonPath("$.byAgent[0].category").value("MAIN"))
            .andExpect(jsonPath("$.byAgent[0].depth").value(0))
            .andExpect(jsonPath("$.byAgent[0].calls").value(3))
            .andExpect(jsonPath("$.byAgent[0].costYuan").value(3.5));
    }

    @Test
    @DisplayName("from/to 查询参数必须原样透传给 StatsService（漏转发 → 区间过滤静默失效）")
    void passesRangeParamsToService() throws Exception {
        when(statsService.aggregate(any(), any())).thenReturn(sample());

        mockMvc.perform(get("/api/v1/stats")
                .param("from", FROM)
                .param("to", TO)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        verify(statsService).aggregate(FROM, TO);
    }

    @Test
    @DisplayName("非法区间（ValidationException）→ 400（⛔ 不是 500）")
    void invalidRangeIsBadRequest() throws Exception {
        when(statsService.aggregate(any(), any()))
            .thenThrow(new ValidationException("时间参数非法"));

        mockMvc.perform(get("/api/v1/stats").param("from", "昨天").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }
}
