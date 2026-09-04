package com.nexusai.application.agent;

import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [usage-push] AgentState run 级累计单测（accumulateRunUsage / runUsage）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: complete.usage 口径 = 本轮累计（对齐 CC result.usage =
 * QueryEngine.ts:790-816 totalUsage += message.usage）——累计错了 complete 就错。三条意图：
 * <ol>
 *   <li><b>初始零</b>：AgentState 每 run 新建 → runUsage() 恒全零（非 null 哨兵，complete 事件形状稳定）；</li>
 *   <li><b>累计</b>：多条 usage accumulateRunUsage 后 runUsage() = 4 token 字段各分量之和
 *       （cache null → 0 补齐，不污染）；</li>
 *   <li><b>null no-op</b>：accumulateRunUsage(null) 不抛不改状态（无 usage 上报消息跳过）。</li>
 * </ol>
 */
@DisplayName("[usage-push] AgentState run 级累计")
class AgentStateRunUsageTest {

    @Test
    @DisplayName("初始 runUsage() = 全零哨兵（非 null，4 token 字段 0）")
    void initialRunUsageIsZeroSentinel() {
        AgentState state = new AgentState("sys", "sess-x", null);
        AgentUsage u = state.runUsage();
        assertThat(u).as("runUsage() 恒非 null（complete 事件形状稳定）").isNotNull();
        assertThat(u.inputTokens()).isZero();
        assertThat(u.outputTokens()).isZero();
        assertThat(u.cacheReadInputTokens()).isZero();
        assertThat(u.cacheCreationInputTokens()).isZero();
    }

    @Test
    @DisplayName("多条 usage 累计：4 token 字段各分量求和（cache null → 0 补齐）")
    void accumulatesMultipleUsages() {
        AgentState state = new AgentState("sys", "sess-x", null);
        // 第一条：input 1000 / output 500 / cacheRead 200 / cacheCreate 100
        state.accumulateRunUsage(new AgentUsage(1000L, 500L, 100L, 200L, null, null, null));
        // 第二条：input 2000 / output 800 / cache 字段 null（→ 0 补齐不污染）
        state.accumulateRunUsage(new AgentUsage(2000L, 800L, null, null, null, null, null));

        AgentUsage total = state.runUsage();
        assertThat(total.inputTokens())
            .as("input = 1000+2000").isEqualTo(3000L);
        assertThat(total.outputTokens())
            .as("output = 500+800").isEqualTo(1300L);
        assertThat(total.cacheReadInputTokens())
            .as("cacheRead = 200+0").isEqualTo(200L);
        assertThat(total.cacheCreationInputTokens())
            .as("cacheCreation = 100+0").isEqualTo(100L);
    }

    @Test
    @DisplayName("accumulateRunUsage(null) no-op：不抛异常、runUsage() 不变（仍全零）")
    void accumulateNullIsNoop() {
        AgentState state = new AgentState("sys", "sess-x", null);
        state.accumulateRunUsage(new AgentUsage(1000L, 500L, null, null, null, null, null));
        state.accumulateRunUsage(null);   // null → no-op
        assertThat(state.runUsage().inputTokens()).isEqualTo(1000L);
        assertThat(state.runUsage().outputTokens()).isEqualTo(500L);
    }
}
