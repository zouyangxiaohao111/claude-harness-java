package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S2] SubagentTool 契约方法覆写测试 · 对齐 CC AgentTool.tsx:1264-1266 + :229。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>:
 * <ol>
 *   <li>isReadOnly=true（CC :1264-1266）让 StreamingToolExecutor 并行调度；Tool.java:270 default
 *       false → 串行退化，与 CC isConcurrencySafe=true 并行语义冲突（探查 §8.3 第 2 条）。</li>
 *   <li>maxResultSizeChars=100_000（CC :229）；Tool.java:376 default 50_000 → 长结果被截断减半。</li>
 *   <li>不覆写 shouldDefer → isDeferredTool 返回 false（CC AgentTool.tsx 无 shouldDefer 字段，
 *       Tool.ts:442 可选 → undefined → prompt.ts:107 `=== true` 判 false → Agent turn-1 可用）。</li>
 * </ol>
 * 测试验证意图：这些覆写/缺省是"并发调度 + 完整结果 + turn-1 可用"契约，缺一即退化。
 */
@DisplayName("Session S2 · SubagentTool 契约方法覆写 (isReadOnly + maxResultSizeChars + shouldDefer)")
class SubagentToolContractOverrideTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("isReadOnly(anyInput)==true 委托权限给底层工具 (CC AgentTool.tsx:1264-1266)")
    void isReadOnly_returnsTrue_delegatingToUnderlyingTools() {
        // GIVEN
        SubagentTool tool = new SubagentTool();

        // WHEN/THEN: 任意输入均返回 true（CC isReadOnly() { return true; }）
        assertThat(tool.isReadOnly(JSON.createObjectNode())).isTrue();
        assertThat(tool.isReadOnly(null)).isTrue();
    }

    @Test
    @DisplayName("maxResultSizeChars()==100_000 (CC AgentTool.tsx:229)")
    void maxResultSizeChars_returns100000() {
        // GIVEN
        SubagentTool tool = new SubagentTool();

        // WHEN/THEN: CC :229 maxResultSizeChars: 100_000；default 50_000 会截断
        assertThat(tool.maxResultSizeChars()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("shouldDefer 不覆写 → isDeferredTool==false (CC AgentTool 无 shouldDefer 字段，turn-1 可用)")
    void shouldDefer_notOverridden_soAgentAvailableTurn1() {
        // GIVEN: SubagentTool 未覆写 shouldDefer（对齐 CC AgentTool.tsx 无 shouldDefer 字段，
        //        Tool.ts:442 readonly shouldDefer?: boolean 可选 → 缺省 undefined）
        SubagentTool tool = new SubagentTool();

        // WHEN: ToolSearchService 判定其是否为 deferred
        boolean deferred = ToolSearchService.isDeferredTool(tool, null);

        // THEN: false —— Agent 工具 turn 1 即可用，不被 ToolSearch 从主循环 schema 剔除
        // WHY (规则九): CC prompt.ts:107 默认规则 `tool.shouldDefer === true` 才 defer；
        //              Agent 缺省 undefined → false → 永不 defer，消除"Agent 需 ToolSearch 加载"。
        assertThat(deferred).isFalse();
    }
}
