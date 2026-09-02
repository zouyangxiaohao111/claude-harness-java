package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemPromptSectionDetail;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemTokenCounts;
import com.nexusai.infra.llm.CountTokensClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RES-R5-1] {@link SystemPromptTokenCounter} 意图测试 · 重建对齐 CC
 * analyzeContext.ts:272-318 countSystemTokens + :261-270 extractSectionName
 * + services/tokenEstimation.ts:124-201 countTokensWithAPI（真实 API 计数）。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>: /context 的 system prompt 明细段依赖此计数。
 * <ol>
 *   <li><b>boundary / 空串混入 section</b> 会污染"总 token = Σ section token"恒等式，且 boundary
 *       是缓存标记、不属可读内容（analyzeContext.ts:287）。</li>
 *   <li><b>名称提取</b>（heading → 可读名；无 heading → 首非空行截 40）决定展示可读性，
 *       截断边界（>40 截 / =40 不截）偏移会破坏 UI 预览一致（analyzeContext.ts:263-269）。</li>
 *   <li><b>逐 section API 计数</b>（analyzeContext.ts:299-303 + tokenEstimation.ts:124-201）——
 *       计数委托真实 countTokens API，不再是本地 rough（round(len/4)）。若退化回 rough 主路径
 *       （忽略计数器 / 用内容长度估算），本测试变红。</li>
 *   <li><b>失败→0 语义</b>（analyzeContext.ts:308 {@code tokens||0}）——某 section API 失败/null
 *       必须记 0，不得让 null 击穿破坏总 token 恒等式。</li>
 *   <li><b>空数组/全 boundary 短路</b> 必须返回 {@code {0, []}} 而非报错，且不得调用计数器
 *       （analyzeContext.ts:295-297）。</li>
 * </ol>
 */
class SystemPromptTokenCounterTest {

    private static final String BOUNDARY = SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY;

    /** 固定返回 7 的计数器（结构断言用，不关心数值）。 */
    private static final CountTokensClient FIXED_7 = content -> 7;

    @Test
    @DisplayName("空数组 / 仅 boundary+空串 → {0, []} 短路，且不调用计数器（analyzeContext.ts:283-297）")
    void boundaryAndEmpty_areFiltered_toEmptyCounts() {
        CountTokensClient neverCalled = content -> {
            throw new AssertionError("namedEntries 为空不得调用计数器（analyzeContext.ts:295-297 短路）");
        };
        SystemTokenCounts empty = SystemPromptTokenCounter.count(List.of(), Map.of(), neverCalled);
        assertThat(empty.systemPromptTokens()).isZero();
        assertThat(empty.systemPromptSections()).isEmpty();

        SystemTokenCounts onlyBoundary = SystemPromptTokenCounter.count(List.of(BOUNDARY, ""), Map.of(), neverCalled);
        assertThat(onlyBoundary.systemPromptTokens()).isZero();
        assertThat(onlyBoundary.systemPromptSections()).isEmpty();
    }

    @Test
    @DisplayName("heading → 首个 markdown heading 提取并 trim（analyzeContext.ts:263-266）")
    void heading_extractsFirstMarkdownHeading() {
        SystemTokenCounts result = SystemPromptTokenCounter.count(List.of("## Tools\nline2\nline3"), Map.of(), FIXED_7);
        assertThat(result.systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .containsExactly("Tools");
        assertThat(result.systemPromptSections()).extracting(SystemPromptSectionDetail::tokens)
            .containsExactly(7);
    }

    @Test
    @DisplayName("无 heading → 首非空行；>40 字符截 40+'…'，=40 不截（analyzeContext.ts:268-269）")
    void noHeading_fallsBackToFirstNonEmptyLineWith40Preview() {
        // 跳过前导空行取首个非空行
        SystemTokenCounts shortSection = SystemPromptTokenCounter.count(
            List.of("\n\nfirst non-empty line\nnext"), Map.of(), FIXED_7);
        assertThat(shortSection.systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .containsExactly("first non-empty line");

        // =40 字符不截断（CC :269 length > 40 才截）
        String forty = "x".repeat(40);
        SystemTokenCounts atLimit = SystemPromptTokenCounter.count(List.of(forty), Map.of(), FIXED_7);
        assertThat(atLimit.systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .containsExactly(forty);

        // >40 字符截 40 + '…'
        SystemTokenCounts over = SystemPromptTokenCounter.count(List.of("x".repeat(41)), Map.of(), FIXED_7);
        assertThat(over.systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .containsExactly("x".repeat(40) + "…");
    }

    @Test
    @DisplayName("systemContext 非空条目并入（key 作 name），空条目排除（analyzeContext.ts:290-293）")
    void systemContext_nonEmptyEntriesMergedEmptyExcluded() {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("gitStatus", "## branch master\non branch master");
        ctx.put("emptyCtx", "");
        SystemTokenCounts result = SystemPromptTokenCounter.count(List.of("# Static"), ctx, FIXED_7);
        assertThat(result.systemPromptSections()).hasSize(2);
        assertThat(result.systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .containsExactly("Static", "gitStatus");
    }

    @Test
    @DisplayName("逐 section 委托计数器（API 计数），null→0（analyzeContext.ts:299-317 + :308 tokens||0）")
    void perSection_delegatesToCounter_nullBecomesZero() {
        // 计数器按顺序返回 [100, null, 50]（第二个 section API 失败 → null → 0）
        AtomicInteger idx = new AtomicInteger();
        CountTokensClient counter = content -> switch (idx.getAndIncrement()) {
            case 0 -> 100;
            case 1 -> null;   // API 失败 → tokens=0（analyzeContext.ts:308）
            default -> 50;
        };
        SystemTokenCounts result = SystemPromptTokenCounter.count(
            List.of("# A", "# B", "# C"), Map.of(), counter);

        assertThat(result.systemPromptSections()).extracting(SystemPromptSectionDetail::tokens)
            .as("section token = 计数器返回值 || 0")
            .containsExactly(100, 0, 50);
        assertThat(result.systemPromptTokens())
            .as("总 token = Σ section token（失败 section 计 0）")
            .isEqualTo(150);
        // 展示恒等式：总 token = Σ section token
        assertThat(result.systemPromptSections().stream()
            .mapToInt(SystemPromptSectionDetail::tokens).sum())
            .isEqualTo(result.systemPromptTokens());
    }

    @Test
    @DisplayName("计数器按 namedEntries 过滤后顺序逐条接收内容（boundary/空串/空 context 不传）")
    void counterReceivesOnlyFilteredContents_inOrder() {
        List<String> received = new ArrayList<>();
        CountTokensClient counter = content -> {
            received.add(content);
            return 3;
        };
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("ctx1", "context-value");
        ctx.put("ctxEmpty", "");
        SystemTokenCounts result = SystemPromptTokenCounter.count(
            List.of("## Static", BOUNDARY, "## Dynamic", ""), ctx, counter);

        assertThat(received).as("计数器仅接收过滤后的非空 section 内容（按 effective 顺序，再 systemContext）")
            .containsExactly("## Static", "## Dynamic", "context-value");
        assertThat(result.systemPromptTokens()).isEqualTo(9);
    }

    @Test
    @DisplayName("boundary 位于分段中间 → 仅 boundary 自身剔除，两侧 section 仍计数")
    void boundaryBetweenSections_onlyItselfRemoved() {
        SystemTokenCounts result = SystemPromptTokenCounter.count(
            List.of("# Static", BOUNDARY, "# Dynamic"), Map.of(), FIXED_7);
        assertThat(result.systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .containsExactly("Static", "Dynamic");
    }
}
