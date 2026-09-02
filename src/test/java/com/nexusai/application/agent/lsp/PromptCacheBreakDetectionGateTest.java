package com.nexusai.application.agent.lsp;

import com.nexusai.application.agent.loop.FeatureFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-06] {@link PromptCacheBreakDetection} feature 门控意图测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9)</b>: OPD-SP-14 默认关——关时 record/check 必须 no-op，
 * 不产生跟踪状态（否则 feature 未启用却消耗内存 / 触发事件）。开时（显式 enabled=true）
 * 行为保持既有契约。
 */
class PromptCacheBreakDetectionGateTest {

    private static final String QUERY_SOURCE = "agent:default";

    @BeforeEach
    void resetSharedState() {
        // PREVIOUS 为类级静态 Map（CC Map<string, PreviousState>），跨实例共享 → 每测前清空
        new PromptCacheBreakDetection(r -> {}).resetPromptCacheBreakDetection();
    }

    private PromptCacheBreakDetection.PromptStateSnapshot snapshot() {
        return new PromptCacheBreakDetection.PromptStateSnapshot(
            List.of(Map.of("type", "text", "text", "sys")),
            List.of(Map.of("name", "toolA")),
            QUERY_SOURCE, "claude-sonnet-4-6", "agent-1",
            false, "", List.of(), false, false, false, null, null);
    }

    @Test
    @DisplayName("gatedBy(ALL_DISABLED) → record/check no-op，零跟踪状态（OPD-SP-14 默认关）")
    void gatedByDisabled_noop() {
        PromptCacheBreakDetection detector = PromptCacheBreakDetection.gatedBy(FeatureFlags.ALL_DISABLED);
        detector.recordPromptState(snapshot());
        assertThat(detector.getTrackedSourceCount()).as("关时 record 必须 no-op").isZero();
        detector.checkResponseForCacheBreak(QUERY_SOURCE, 10_000, 0, null, "agent-1", "req-1");
        assertThat(detector.getTrackedSourceCount()).isZero();
        detector.notifyCompaction(QUERY_SOURCE, "agent-1");
        detector.notifyCacheDeletion(QUERY_SOURCE, "agent-1");
        assertThat(detector.getTrackedSourceCount()).isZero();
    }

    @Test
    @DisplayName("显式 enabled=false 构造 → no-op；enabled=true（默认构造）→ 正常跟踪")
    void explicitDisabled_vsDefaultEnabled() {
        PromptCacheBreakDetection off = new PromptCacheBreakDetection(events -> {}, false);
        off.recordPromptState(snapshot());
        assertThat(off.getTrackedSourceCount()).isZero();

        PromptCacheBreakDetection on = new PromptCacheBreakDetection(events -> {});
        on.recordPromptState(snapshot());
        assertThat(on.getTrackedSourceCount()).as("默认构造 enabled=true → 跟踪生效（向后兼容）").isEqualTo(1);
    }
}
