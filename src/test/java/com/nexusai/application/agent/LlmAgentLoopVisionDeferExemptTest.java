package com.nexusai.application.agent;

import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel 豁免行为测试
 * （vision-defer-model 2026-09-03 / R12 2026-09-12 判据单点化）。
 *
 * <p>WHY（CLAUDE.md 规则 9）：vision_analyze 懒加载豁免是「能用 tool_reference 且多模态 → 懒 /
 * 否则直发」的装配层判据。工具侧 shouldDefer=true（想懒），由本豁免决定是否真懒。R12 把判据从
 * provider-only（{@code ContextUsageCalculator.isAnthropic}，第二个 provider 判源）换成单点
 * {@code ToolSearchService.toolReferenceUsable(providerType, modelName)}（provider 源 =
 * {@code tuc.effectiveProviderType()}）。四格真相表：
 * <ul>
 *   <li>anthropic + 多模态 + 非 haiku（sonnet）→ tool_reference 可用 → <b>保留懒</b>；</li>
 *   <li>anthropic + 非多模态（文本模型）→ 无 Read 直给通道 → <b>剔除直发</b>；</li>
 *   <li>非 anthropic（openai 系）+ 多模态 → 无 tool_reference → <b>剔除直发</b>；</li>
 *   <li>anthropic + haiku + 多模态 → <b>无 tool_reference ⇒ 懒加载不可达</b> → <b>剔除直发</b>
 *       （R12 有意行为变更：旧判据「是 anthropic」会保留懒 → 模型永远搜不出 vision_analyze）；</li>
 *   <li>mapper null（无法判模型能力）→ <b>保守剔除直发</b>（绝不能把 vision_analyze 留在 deferred
 *       让文本模型拿不到 —— 历史 Read 空图死循环 / fork 视觉子代理递归根因）；</li>
 *   <li>deferred 不含 vision_analyze → no-op；deferred null → 装配容忍不抛。</li>
 * </ul>
 */
class LlmAgentLoopVisionDeferExemptTest {

    /** providerMapper 非 null 即可（裸模型名走 ModelNameResolver 历史兼容路径，不触 provider 查询）。 */
    private static final ProviderMapper PROVIDER_MAPPER = mock(ProviderMapper.class);

    private static Set<String> deferredWithVision() {
        Set<String> s = new LinkedHashSet<>();
        s.add("ToolSearch");
        s.add(ToolNameConstants.VISION_ANALYZE_TOOL_NAME);
        return s;
    }

    /** modelMapper 解析出 type 指定、enabled 的模型（裸名 → ModelNameResolver 历史兼容路径 selectListByQuery）。 */
    private static ModelMapper stubModelType(String modelName, String type) {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setName(modelName);
        m.setType(type);
        m.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(m));
        return modelMapper;
    }

    @Test
    @DisplayName("anthropic + sonnet + 多模态 → 保留懒加载（tool_reference 可用 + Read 直给通道）")
    void anthropicSonnetMultimodal_keepsVisionAnalyzeDeferred() {
        Set<String> deferred = deferredWithVision();
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(deferred, "anthropic",
            stubModelType("claude-sonnet-4-6", "multimodal"), PROVIDER_MAPPER, "claude-sonnet-4-6");
        assertThat(deferred)
            .as("anthropic×sonnet 支持 tool_reference 且多模态 → vision_analyze 保留懒（省 schema token）")
            .contains(ToolNameConstants.VISION_ANALYZE_TOOL_NAME)
            .contains("ToolSearch");
    }

    @Test
    @DisplayName("anthropic + sonnet + 非多模态（文本）→ vision_analyze 剔除直发（无 Read 直给通道）")
    void anthropicSonnetTextOnly_removesVisionAnalyze() {
        Set<String> deferred = deferredWithVision();
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(deferred, "anthropic",
            stubModelType("claude-sonnet-4-6", "chat"), PROVIDER_MAPPER, "claude-sonnet-4-6");
        assertThat(deferred)
            .as("文本模型 vision_analyze 是唯一视觉通道 → 直发（不赌 ToolSearch 激活）")
            .doesNotContain(ToolNameConstants.VISION_ANALYZE_TOOL_NAME)
            .contains("ToolSearch");
    }

    @Test
    @DisplayName("非 anthropic（openai_compatible）+ 多模态 → vision_analyze 剔除直发（无 tool_reference）")
    void nonAnthropicMultimodal_removesVisionAnalyze() {
        Set<String> deferred = deferredWithVision();
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(deferred, "openai_compatible",
            stubModelType("deepseek-v4-vision-exp", "multimodal"), PROVIDER_MAPPER, "deepseek-v4-vision-exp");
        assertThat(deferred)
            .as("非 anthropic 无 tool_reference → 懒加载不可达 → 强制直发（deepseek vision-exp 唯一视觉通道）")
            .doesNotContain(ToolNameConstants.VISION_ANALYZE_TOOL_NAME)
            .contains("ToolSearch");
    }

    @Test
    @DisplayName("[R12 有意变更] anthropic + haiku + 多模态 → vision_analyze 剔除直发（haiku 无 tool_reference，懒加载不可达）")
    void anthropicHaikuMultimodal_removesVisionAnalyze() {
        // WHY（R12）：旧判据只看 provider==anthropic → haiku 会「保留懒」；但 haiku 不支持
        //   tool_reference（ToolSearchService 负向名单 'haiku'）⇒ 模型永远搜不出 vision_analyze ⇒
        //   懒加载等于不可达。新判据 toolReferenceUsable=false → 直发 fail-safe（符合用户「懒加载的
        //   前提是 tool_reference 自带」）。变异：把判据回退成 "anthropic".equalsIgnoreCase(providerType)
        //   → 本用例必须转红（证明新判据 load-bearing）。
        Set<String> deferred = deferredWithVision();
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(deferred, "anthropic",
            stubModelType("claude-haiku-4-5", "multimodal"), PROVIDER_MAPPER, "claude-haiku-4-5");
        assertThat(deferred)
            .as("haiku 无 tool_reference → 懒加载不可达 → 剔除直发（schema 直给）")
            .doesNotContain(ToolNameConstants.VISION_ANALYZE_TOOL_NAME)
            .contains("ToolSearch");
    }

    @Test
    @DisplayName("mapper null（无法判模型能力）→ vision_analyze 从 deferred 剔除（保守直发）")
    void mapperNull_removesVisionAnalyze() {
        Set<String> deferred = deferredWithVision();
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(deferred, "anthropic", null, null, "deepseek-chat");
        assertThat(deferred)
            .as("mapper 未注入无法判多模态 → 保守剔除，vision_analyze schema 直发（不赌模型会 ToolSearch 激活）")
            .doesNotContain(ToolNameConstants.VISION_ANALYZE_TOOL_NAME)
            .contains("ToolSearch");
    }

    @Test
    @DisplayName("deferred 不含 vision_analyze → no-op（其它 defer 工具不受影响）")
    void noVisionAnalyze_noop() {
        Set<String> deferred = new LinkedHashSet<>();
        deferred.add("ToolSearch");
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(deferred, "anthropic", null, null, "deepseek-chat");
        assertThat(deferred).containsExactly("ToolSearch");
    }

    @Test
    @DisplayName("deferred null → 不抛（装配容忍，llmToolsArray 空工具集安全）")
    void nullDeferred_noThrow() {
        LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel(null, "anthropic", null, null, "deepseek-chat");
    }
}
