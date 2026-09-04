package com.nexusai.application.agent;

import com.nexusai.application.agent.tool.ToolNameConstants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel 豁免行为测试（vision-defer-model 2026-09-03）。
 *
 * <p>WHY（CLAUDE.md 规则 9）：vision_analyze 懒加载豁免是「ant 多模态懒 / 文本模型直发」的装配层
 * 判据。工具侧 shouldDefer=true（想懒），由本豁免按主模型能力决定是否真懒：
 * <ul>
 *   <li>mapper null（无法判模型）→ <b>保守剔除直发</b>——绝不能把 vision_analyze 留在 deferred 让
 *       文本模型拿不到（历史 Read 空图死循环 / fork 视觉子代理递归根因）；</li>
 *   <li>deferred 不含 vision_analyze → no-op（其它 defer 工具不受波及）；</li>
 *   <li>deferred null → 装配容忍不抛。</li>
 * </ul>
 * ant+多模态保留懒的组合分支依赖 DB mapper（isAnthropic/modelSupportsImage 各自有既有覆盖），
 * 此处锁定纯函数安全边界。
 */
class LlmAgentLoopVisionDeferExemptTest {

    private static Set<String> deferredWithVision() {
        Set<String> s = new LinkedHashSet<>();
        s.add("ToolSearch");
        s.add(ToolNameConstants.VISION_ANALYZE_TOOL_NAME);
        return s;
    }

    @Test
    @DisplayName("mapper null（无法判模型能力）→ vision_analyze 从 deferred 剔除（保守直发）")
    void mapperNull_removesVisionAnalyze() {
        Set<String> deferred = deferredWithVision();
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(deferred, null, null, "deepseek-chat");
        assertThat(deferred)
            .as("mapper 未注入无法判 ant+多模态 → 保守剔除，vision_analyze schema 直发（不赌模型会 ToolSearch 激活）")
            .doesNotContain(ToolNameConstants.VISION_ANALYZE_TOOL_NAME)
            .contains("ToolSearch");
    }

    @Test
    @DisplayName("deferred 不含 vision_analyze → no-op（其它 defer 工具不受影响）")
    void noVisionAnalyze_noop() {
        Set<String> deferred = new LinkedHashSet<>();
        deferred.add("ToolSearch");
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(deferred, null, null, "deepseek-chat");
        assertThat(deferred).containsExactly("ToolSearch");
    }

    @Test
    @DisplayName("deferred null → 不抛（装配容忍，llmToolsArray 空工具集安全）")
    void nullDeferred_noThrow() {
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(null, null, null, "deepseek-chat");
    }
}
