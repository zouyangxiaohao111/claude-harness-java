package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.lsp.PromptCacheBreakDetection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP2-08] SubagentExecutor {@code cleanupAgentTracking} 接线测试 ·
 * 对齐 CC runAgent.ts:824-826 {@code if (feature('PROMPT_CACHE_BREAK_DETECTION'))
 * cleanupAgentTracking(agentId)}（finally 块内，正常/abort/error 三路均执行）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>：✗-14 接线缺口 —— 子代理结束必须删除 PREVIOUS 对应 key，
 * 否则 feature 开启后旧 tracking 残留导致 cache-break 误报。旧实现为 @Deprecated no-op，
 * 本测试断言：feature 开 → 真实删除；feature 关 / 未注入 → no-op（默认关零行为变化）。
 *
 * <p><b>测试方式</b>：finally 接线本身由 grep 硬指标兜底（{@code cleanupAgentTracking} 命中
 * executeStreaming Step 21 finally 调用点），本测试直接验证 seam 方法语义（对齐
 * {@code SubagentExecutorSessionHookCleanupTest} 直测 cleanupSessionHooks 的先例）。
 * PREVIOUS 为类级静态 Map（CC previousStateBySource 模块级），跨实例共享 → 每测前清空。
 */
@DisplayName("[IMP-SP2-08] SubagentExecutor cleanupAgentTracking 接线")
class SubagentExecutorCacheBreakCleanupTest {

    @BeforeEach
    void resetSharedState() {
        new PromptCacheBreakDetection(r -> {}).resetPromptCacheBreakDetection();
    }

    /** promptCacheBreakDetection=true 的 FeatureFlags（第 4 位，record 其余恒 false）。 */
    private static FeatureFlags featureOn() {
        return new FeatureFlags(false, false, false, true,
            false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
    }

    private SubagentExecutor executor(FeatureFlags flags) {
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "model", "system-prompt");
        executor.setFeatureFlags(flags);
        return executor;
    }

    private void recordAgentOne(PromptCacheBreakDetection detector) {
        detector.recordPromptState(new PromptCacheBreakDetection.PromptStateSnapshot(
            List.of(Map.of("type", "text", "text", "sys")),
            List.of(Map.of("name", "toolA")),
            "agent:default", "claude-sonnet-4-6", "agent-1",
            false, "", List.of(), false, false, false, null, null));
    }

    @Test
    @DisplayName("feature 开 → cleanupAgentTracking 删除 PREVIOUS 对应 key（CC runAgent.ts:824-826 + promptCacheBreakDetection.ts:700-702）")
    void featureOn_removesPreviousStateForKey() {
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events -> {}); // enabled=true
        recordAgentOne(detector);
        assertThat(detector.getTrackedSourceCount()).as("record 后 PREVIOUS 有 1 条").isEqualTo(1);

        executor(featureOn()).cleanupAgentTracking("agent-1");

        assertThat(detector.getTrackedSourceCount())
            .as("子代理结束清理该 agentId 的 tracking（CC previousStateBySource.delete(agentId)）")
            .isZero();
    }

    @Test
    @DisplayName("feature 关（ALL_DISABLED）→ cleanupAgentTracking no-op，PREVIOUS 保留（默认关零行为变化）")
    void featureOff_noop_keepsPreviousState() {
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events -> {});
        recordAgentOne(detector);

        executor(FeatureFlags.ALL_DISABLED).cleanupAgentTracking("agent-1");

        assertThat(detector.getTrackedSourceCount())
            .as("feature 关 → 不清理（OPD-SP-14 默认关）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("featureFlags 未注入（null）→ cleanupAgentTracking no-op（装配缺省 = 默认关行为）")
    void featureFlagsNull_noop() {
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events -> {});
        recordAgentOne(detector);

        new SubagentExecutor(null, null, null, null, null, "model", "system-prompt")
            .cleanupAgentTracking("agent-1");

        assertThat(detector.getTrackedSourceCount()).as("未注入 → 门控短路 → 不清理").isEqualTo(1);
    }

    @Test
    @DisplayName("cleanupAgentTracking(null) 安全 no-op 不抛异常（finally 异常路径不掩盖原始异常）")
    void nullAgentId_isNoop() {
        // 不抛异常即为通过（对齐 cleanupSessionHooks(null) 先例）
        executor(featureOn()).cleanupAgentTracking(null);
    }
}
