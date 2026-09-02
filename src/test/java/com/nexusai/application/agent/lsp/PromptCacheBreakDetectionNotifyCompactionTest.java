package com.nexusai.application.agent.lsp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * notifyCompaction 接线测试 · 对齐 CC services/api/promptCacheBreakDetection.ts:689-698.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 压缩合法地减少消息数，下个 API 的
 * cache_read 会自然下降。若不重置 cache-read 基线，checkResponseForCacheBreak 会把这个
 * "预期下降"误报为 cache break（BQ 2026-03-01 统计显示 20% 的 tengu_prompt_cache_break
 * 事件是误报）。CC 的 {@code notifyCompaction} 把 {@code prevCacheReadTokens} 重置为 null，
 * 使下个响应跳过下降比较——本测试锁定该"误报防护"契约:
 * <ol>
 *   <li><b>有 notifyCompaction</b>: 压缩后 cache-read 大幅下降 → <b>不</b>触发 break 事件</li>
 *   <li><b>无 notifyCompaction（对照）</b>: 同样的下降 → 触发 break 事件（证明差异确由
 *       notifyCompaction 造成，而非测试数据本身不触发）</li>
 * </ol>
 *
 * <p>本测试也是 IMP-08 验收项 5（"notifyCompaction( 调用点存在"）的测试侧接线点；
 * 生产侧调用点由 IMP-04/07/10 在压缩成功路径接入（IMP-08 不越权改造主流程）。
 */
@DisplayName("[IMP-08] notifyCompaction 压缩后 cache-read 基线重置（误报防护）")
class PromptCacheBreakDetectionNotifyCompactionTest {

    private static final String QUERY_SOURCE = "repl_main_thread";
    private static final String MODEL = "sonnet";

    @Test
    @DisplayName("notifyCompaction 后 cache-read 大幅下降不误报 cache break")
    void notifyCompactionSuppressesFalsePositiveBreak() {
        List<PromptCacheBreakDetection.CacheBreakResult> events = new ArrayList<>();
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events::add);

        // 第一次 API success: 建立 cache-read 基线 10000
        detector.recordPromptState(snapshot());
        detector.checkResponseForCacheBreak(QUERY_SOURCE, 10_000, 0, null, null, "req-1");

        // 第二次 record 同状态（无 pending changes）
        detector.recordPromptState(snapshot());

        // 压缩成功 → notifyCompaction 重置 cache-read 基线
        detector.notifyCompaction(QUERY_SOURCE, null);

        // 压缩后下个 API: cache-read 从 10000 掉到 2000（预期下降，非 break）
        detector.checkResponseForCacheBreak(QUERY_SOURCE, 2_000, 8_000, null, null, "req-2");

        assertThat(events).as("压缩后 cache-read 下降不应被误报为 cache break").isEmpty();
    }

    @Test
    @DisplayName("对照：不调 notifyCompaction 时同样下降会被检测为 cache break")
    void withoutNotifyCompactionSameDropIsDetectedAsBreak() {
        List<PromptCacheBreakDetection.CacheBreakResult> events = new ArrayList<>();
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events::add);

        // 第一次 API success: 建立 cache-read 基线 10000
        detector.recordPromptState(snapshot());
        detector.checkResponseForCacheBreak(QUERY_SOURCE, 10_000, 0, null, null, "req-1");

        // 第二次 record 同状态（无 pending changes）
        detector.recordPromptState(snapshot());

        // 没有 notifyCompaction → 同样的 cache-read 下降触发 break
        detector.checkResponseForCacheBreak(QUERY_SOURCE, 2_000, 8_000, null, null, "req-2");

        assertThat(events)
            .as("无 notifyCompaction 时 cache-read 从 10000→2000（drop>2000 且 <95%）应报 break")
            .hasSize(1);
        assertThat(events.get(0).detected()).isTrue();
    }

    private static PromptCacheBreakDetection.PromptStateSnapshot snapshot() {
        return new PromptCacheBreakDetection.PromptStateSnapshot(
            List.of(Map.of("type", "text", "text", "system prompt")),
            List.of(Map.of("name", "Bash", "description", "run bash")),
            QUERY_SOURCE, MODEL, null,
            false, "", List.of(), false, false, false,
            null, null);
    }
}
