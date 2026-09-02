package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [V-TOK] CostTracker 桶合并引擎 + 会话持久化桥测试。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：跨 turn 会话累计是验收 5 的核心 ——
 * {@code computeModelUsageIncrement}/addToTotalSessionCost 必须镜像 CC cost-tracker.ts:250-276
 * （tokens/costUSD 累加、contextWindow/maxOutputTokens 取 last）；save/restore 经 sessions 列
 * round-trip 一致（含 NULL 列零累计）。任一偏差都会让跨 turn 的 total_cost_usd/modelUsage 失真。
 */
@DisplayName("[V-TOK] CostTracker 桶合并 + 会话持久化")
class CostTrackerTest {

    @Test
    @DisplayName("computeModelUsageIncrement 镜像 CC addToTotalModelUsage 单次增量")
    void computeModelUsageIncrement_mirrorsCcAddToTotalModelUsage() {
        AgentUsage usage = new AgentUsage(100L, 50L, 20L, 30L,
            new AgentUsage.ServerToolUse(2L, 1L), "standard", null);
        CostTracker.ModelUsage inc = CostTracker.computeModelUsageIncrement(
            0.01, usage, "deepseek-v4-flash", 1_048_576, 384_000);
        assertThat(inc.inputTokens()).as("inputTokens = usage.input_tokens").isEqualTo(100L);
        assertThat(inc.outputTokens()).as("outputTokens = usage.output_tokens").isEqualTo(50L);
        assertThat(inc.cacheReadInputTokens()).as("cacheRead = ??0").isEqualTo(30L);
        assertThat(inc.cacheCreationInputTokens()).isEqualTo(20L);
        assertThat(inc.webSearchRequests()).as("webSearch = server_tool_use.web_search_requests ?? 0")
            .isEqualTo(2L);
        assertThat(inc.costUSD()).isEqualTo(0.01);
        assertThat(inc.contextWindow()).as("contextWindow = last（本次传入）").isEqualTo(1_048_576);
        assertThat(inc.maxOutputTokens()).as("maxOutputTokens = last").isEqualTo(384_000);
    }

    @Test
    @DisplayName("addToTotalSessionCost 跨多次调用累计（tokens/costUSD 累加，窗口取 last）")
    void addToTotalSessionCost_accumulatesAcrossCalls() {
        CostTracker tracker = new CostTracker();
        tracker.addToTotalSessionCost(0.01, new AgentUsage(100L, 50L, 10L, 20L, null, null, null),
            "deepseek-v4-flash", 1_000_000, 300_000);
        tracker.addToTotalSessionCost(0.02, new AgentUsage(200L, 100L, 0L, 0L, null, null, null),
            "deepseek-v4-flash", 1_048_576, 384_000);

        CostTracker.ModelUsage agg = tracker.modelUsage().get("deepseek-v4-flash");
        assertThat(agg).as("同模型桶跨调用合并").isNotNull();
        assertThat(agg.inputTokens()).as("inputTokens 跨调用累加").isEqualTo(300L);
        assertThat(agg.outputTokens()).as("outputTokens 跨调用累加").isEqualTo(150L);
        assertThat(agg.costUSD()).as("costUSD 跨调用累加").isCloseTo(0.03, within(1e-9));
        assertThat(agg.contextWindow()).as("contextWindow 取 last 调用值").isEqualTo(1_048_576);
        assertThat(agg.maxOutputTokens()).as("maxOutputTokens 取 last 调用值").isEqualTo(384_000);
        assertThat(tracker.totalCostYuan()).isCloseTo(0.03, within(1e-9));
    }

    @Test
    @DisplayName("save/restore round-trip：sessions 列写→读回一致")
    void saveRestore_roundTrip_sessionsColumnsConsistent() {
        SessionMapper sm = mock(SessionMapper.class);
        SessionRecord row = new SessionRecord();
        row.setId("sess-1");
        when(sm.selectOneById("sess-1")).thenReturn(row);
        AtomicReference<SessionRecord> written = new AtomicReference<>();
        doAnswer(inv -> { written.set(inv.getArgument(0)); return 1; }).when(sm).update(any());
        CostTracker tracker = new CostTracker();
        ReflectionTestUtils.setField(tracker, "sessionMapper", sm);

        // GIVEN: AgentState（生产单源）累计完成
        AgentState state = new AgentState("sys");
        state.addSessionInputTokens(500);
        state.addSessionCostYuan(0.05);
        state.mergeSessionModelUsage("deepseek-v4-flash",
            CostTracker.computeModelUsageIncrement(0.05,
                new AgentUsage(500L, 300L, 0L, 0L, null, null, null),
                "deepseek-v4-flash", 1_048_576, 384_000));

        // WHEN: save（写列）→ restore（读列）
        tracker.saveCurrentSessionCosts("sess-1", state);
        assertThat(written.get()).as("save 必须经 sessionMapper.update 写入").isNotNull();
        assertThat(written.get().getTotalCostYuan()).isEqualTo(0.05);
        assertThat(written.get().getModelUsageJson()).as("model_usage_json 含模型键")
            .contains("deepseek-v4-flash");

        CostTracker.RestoredSessionCosts restored = tracker.restoreCostStateForSession("sess-1");
        assertThat(restored).as("restore 必须返回快照（非 null）").isNotNull();
        assertThat(restored.totalCostYuan()).as("restore 花费 = save 花费").isEqualTo(0.05);
        assertThat(restored.inputTokens()).as("restore input 累计 = save input").isEqualTo(500L);
        assertThat(restored.modelUsage().get("deepseek-v4-flash").inputTokens()).isEqualTo(500L);
    }

    @Test
    @DisplayName("restore：列 NULL → 零累计（存量行容错）")
    void restore_nullColumns_zeroCumulative() {
        SessionMapper sm = mock(SessionMapper.class);
        SessionRecord row = new SessionRecord();
        row.setId("sess-1");   // total_cost_yuan / model_usage_json 恒 null
        when(sm.selectOneById("sess-1")).thenReturn(row);
        CostTracker tracker = new CostTracker();
        ReflectionTestUtils.setField(tracker, "sessionMapper", sm);

        CostTracker.RestoredSessionCosts restored = tracker.restoreCostStateForSession("sess-1");
        assertThat(restored).isNotNull();
        assertThat(restored.totalCostYuan()).as("NULL 列 → 零累计").isEqualTo(0.0);
        assertThat(restored.inputTokens()).isEqualTo(0L);
        assertThat(restored.modelUsage()).isEmpty();
    }
}
