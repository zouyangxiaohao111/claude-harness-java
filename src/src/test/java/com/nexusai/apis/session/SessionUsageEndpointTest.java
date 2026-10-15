package com.nexusai.apis.session;

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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [usage-gateway] {@code GET /api/v1/sessions/{id}/usage} 的 <b>JSON 契约</b>（前端「用量」页子代理行的取数口）。
 *
 * <h2>WHY（规则九：这条守的是哪类真实故障）</h2>
 * 前端按<b>字段名</b>读这份 JSON（`agents[].taskId` 做关联、`costYuan/calls/inputTokens/...` 直接上屏），
 * 而 record 组件的字段名 ⇄ JSON 键靠 Jackson 默认策略 —— 一旦有人加了
 * {@code @JsonProperty} 改名、把 record 换成普通类漏了 getter、或改了字段名，前端不会报错，
 * 只会静默显示「统计不可用」或 {@code undefined}（此前 {@code byCategory}/{@code byAgent} 两维
 * 正是这种静默面）。因此本类把前端消费的每个键都钉死，并守住三条边界：
 * <ol>
 *   <li><b>字段名与类型</b>：`taskId` 可为 null（主 agent / 不可换算）、`costYuan` 为数值、层级字段齐全；</li>
 *   <li><b>空白 sessionId → 400</b>（{@link ValidationException} → GlobalExceptionHandler），⛔ 不是 500；</li>
 *   <li><b>无明细 → 200 + 空列表</b>（fail-soft：空会话不得 404/500，否则前端弹窗会显示加载失败）。</li>
 * </ol>
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>给任一端点字段加 {@code @JsonProperty("...")} 改名（或改 record 组件名）→ 对应 jsonPath 红；</li>
 *   <li>把空 sessionId 的异常类型换成 {@code IllegalArgumentException} → 400 用例红（变 500）；</li>
 *   <li>空会话改抛 404 → fail-soft 用例红。</li>
 * </ul>
 */
@DisplayName("[usage-gateway] GET /sessions/{id}/usage · JSON 契约（前端子代理行取数口）")
class SessionUsageEndpointTest {

    private static final String SID = "sess-contract";

    private MockMvc mockMvc;
    private StatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = mock(StatsService.class);
        SessionController controller = new SessionController();
        ReflectionTestUtils.setField(controller, "statsService", statsService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static StatsService.SessionUsage sample() {
        StatsService.SessionUsageTotals totals = new StatsService.SessionUsageTotals(
            3, 400, 40, 100, 0, 1.23, 700, 1, 1, 0, 3, 0,
            // [cache-savings] 末尾 8 字段（分项费用 / 缓存节省 / 命中率 / LEGACY 行数）
            0.5, 0.4, 0.2, 0.0, 2.0, 1.3, 0.25, 1);
        StatsService.CategoryStat cat = new StatsService.CategoryStat(
            "SUBAGENT", 3, 400, 40, 100, 0, 1.23, 1, 1, 0, 3, 0);
        StatsService.SessionAgentUsage main = new StatsService.SessionAgentUsage(
            SID, null, "MAIN", null, null, 0, null,
            1, 100, 10, 0, 0, 1.0, 200, 0, 0, 0, 1, 0,
            0.4, 0.3, 0.17, 0.0, 1.5, 0.63, 0.5, 1);
        StatsService.SessionAgentUsage child = new StatsService.SessionAgentUsage(
            "a0123456789abcdef", "01234567-89ab-cdef-0000-000000000000", "SUBAGENT", SID, null, 1, "alice",
            2, 300, 30, 100, 0, 0.23, 500, 1, 1, 0, 2, 0,
            0.1, 0.1, 0.03, 0.0, 0.5, 0.37, 0.75, 0);
        return new StatsService.SessionUsage(SID, totals, List.of(cat), List.of(main, child));
    }

    @Test
    @DisplayName("200 · 前端消费的每个键都在（totals / byCategory / agents[].taskId 等）")
    void jsonShapeMatchesFrontContract() throws Exception {
        when(statsService.sessionUsage(SID)).thenReturn(sample());

        mockMvc.perform(get("/api/v1/sessions/{id}/usage", SID).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(SID))
            // totals：模型调用次数 + 四 token 通道 + 费用 + 模型耗时 + 估算/中断/失败/流式标志
            .andExpect(jsonPath("$.totals.calls").value(3))
            .andExpect(jsonPath("$.totals.inputTokens").value(400))
            .andExpect(jsonPath("$.totals.outputTokens").value(40))
            .andExpect(jsonPath("$.totals.cacheReadTokens").value(100))
            .andExpect(jsonPath("$.totals.cacheWriteTokens").value(0))
            .andExpect(jsonPath("$.totals.costYuan").value(1.23))
            .andExpect(jsonPath("$.totals.elapsedMs").value(700))
            .andExpect(jsonPath("$.totals.estimatedCalls").value(1))
            .andExpect(jsonPath("$.totals.abortedCalls").value(1))
            .andExpect(jsonPath("$.totals.failedCalls").value(0))
            .andExpect(jsonPath("$.totals.streamCalls").value(3))
            .andExpect(jsonPath("$.totals.nonStreamCalls").value(0))
            // [cache-savings] 分项费用 / 缓存节省 / 命中率 / LEGACY 行数（前端「缓存省了多少钱」直接上屏）
            .andExpect(jsonPath("$.totals.costInput").value(0.5))
            .andExpect(jsonPath("$.totals.costOutput").value(0.4))
            .andExpect(jsonPath("$.totals.costCacheRead").value(0.2))
            .andExpect(jsonPath("$.totals.costCacheWrite").value(0.0))
            .andExpect(jsonPath("$.totals.costNoCacheInput").value(2.0))
            .andExpect(jsonPath("$.totals.cacheSavingsYuan").value(1.3))
            .andExpect(jsonPath("$.totals.cacheHitRate").value(0.25))
            .andExpect(jsonPath("$.totals.legacyCalls").value(1))
            // byCategory（与统计页同一口径）
            .andExpect(jsonPath("$.byCategory[0].category").value("SUBAGENT"))
            .andExpect(jsonPath("$.byCategory[0].calls").value(3))
            .andExpect(jsonPath("$.byCategory[0].costYuan").value(1.23))
            // agents：关联键 + 层级 + 计数 + 费用（前端逐字段上屏）
            .andExpect(jsonPath("$.agents[0].agentId").value(SID))
            .andExpect(jsonPath("$.agents[0].taskId").doesNotExist())
            .andExpect(jsonPath("$.agents[0].depth").value(0))
            .andExpect(jsonPath("$.agents[1].agentId").value("a0123456789abcdef"))
            .andExpect(jsonPath("$.agents[1].taskId").value("01234567-89ab-cdef-0000-000000000000"))
            .andExpect(jsonPath("$.agents[1].category").value("SUBAGENT"))
            .andExpect(jsonPath("$.agents[1].parentAgentId").value(SID))
            .andExpect(jsonPath("$.agents[1].parentTaskId").doesNotExist())
            .andExpect(jsonPath("$.agents[1].depth").value(1))
            .andExpect(jsonPath("$.agents[1].label").value("alice"))
            .andExpect(jsonPath("$.agents[1].calls").value(2))
            .andExpect(jsonPath("$.agents[1].inputTokens").value(300))
            .andExpect(jsonPath("$.agents[1].outputTokens").value(30))
            .andExpect(jsonPath("$.agents[1].cacheReadTokens").value(100))
            .andExpect(jsonPath("$.agents[1].cacheWriteTokens").value(0))
            .andExpect(jsonPath("$.agents[1].costYuan").value(0.23))
            .andExpect(jsonPath("$.agents[1].elapsedMs").value(500))
            .andExpect(jsonPath("$.agents[1].estimatedCalls").value(1))
            .andExpect(jsonPath("$.agents[1].abortedCalls").value(1))
            .andExpect(jsonPath("$.agents[1].failedCalls").value(0))
            .andExpect(jsonPath("$.agents[1].streamCalls").value(2))
            .andExpect(jsonPath("$.agents[1].nonStreamCalls").value(0))
            // [cache-savings] 每个 agent 节点的分项/节省/命中率/LEGACY 行数（同 totals 八键，逐节点口径）
            .andExpect(jsonPath("$.agents[1].costInput").value(0.1))
            .andExpect(jsonPath("$.agents[1].costOutput").value(0.1))
            .andExpect(jsonPath("$.agents[1].costCacheRead").value(0.03))
            .andExpect(jsonPath("$.agents[1].costCacheWrite").value(0.0))
            .andExpect(jsonPath("$.agents[1].costNoCacheInput").value(0.5))
            .andExpect(jsonPath("$.agents[1].cacheSavingsYuan").value(0.37))
            .andExpect(jsonPath("$.agents[1].cacheHitRate").value(0.75))
            .andExpect(jsonPath("$.agents[1].legacyCalls").value(0));
    }

    @Test
    @DisplayName("空白 sessionId → 400（ValidationException · ⛔ 不是 500）")
    void blankSessionIdIsBadRequest() throws Exception {
        when(statsService.sessionUsage(anyString()))
            .thenThrow(new ValidationException("sessionId 不能为空"));

        mockMvc.perform(get("/api/v1/sessions/{id}/usage", "   ").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("空会话（无明细）→ 200 + 空列表（fail-soft：前端弹窗不得因此显示加载失败）")
    void emptySessionIsOkWithEmptyLists() throws Exception {
        when(statsService.sessionUsage(SID)).thenReturn(new StatsService.SessionUsage(
            SID, new StatsService.SessionUsageTotals(0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0, 0, 0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, 0),
            List.of(), List.of()));

        mockMvc.perform(get("/api/v1/sessions/{id}/usage", SID).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totals.calls").value(0))
            .andExpect(jsonPath("$.byCategory").isEmpty())
            .andExpect(jsonPath("$.agents").isEmpty());
    }
}
