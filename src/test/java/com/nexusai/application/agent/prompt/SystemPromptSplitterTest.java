package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-06] {@link SystemPromptSplitter#splitSysPromptPrefix} 3 模式意图测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>skipGlobalCache 模式</b>（MCP tools 存在）滤 boundary + attribution(null)/prefix(org)/rest(org）—
 *       MCP 时代 system prompt 不做 global cache（api.ts:327-364）。</li>
 *   <li><b>boundary 命中模式</b> 静态→global / 动态→null / attribution(null)/prefix(null)（api.ts:368-397）。
 *       <b>I-8</b>: boundary 字符串绝不发送。</li>
 *   <li><b>默认模式</b> 全 org（api.ts:412-435）——3P provider 或 boundary 缺失。</li>
 * </ol>
 */
class SystemPromptSplitterTest {

    private static final String BOUNDARY = SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY;
    private static final String PREFIX = "You are Claude Code, Anthropic's official CLI for Claude.";
    private static final String ATTRIBUTION = "x-anthropic-billing-header: cc=1";

    // ═══════════════════ 模式 1：skipGlobalCache ═══════════════════

    @Test
    @DisplayName("skipGlobalCache 模式 → 滤 boundary + attribution(null)/prefix(org)/rest(org)（api.ts:327-364）")
    void skipGlobalCacheMode_filtersBoundaryAndScopes() {
        List<SystemPromptBlock> result = SystemPromptSplitter.splitSysPromptPrefix(
            List.of(ATTRIBUTION, PREFIX, "staticBlock", BOUNDARY, "dynamicBlock"), true, true);
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(new SystemPromptBlock(ATTRIBUTION, CacheScope.NULL));
        assertThat(result.get(1)).isEqualTo(new SystemPromptBlock(PREFIX, CacheScope.ORG));
        assertThat(result.get(2).text()).as("rest 以 \\n\\n join").isEqualTo("staticBlock\n\ndynamicBlock");
        assertThat(result.get(2).cacheScope()).isEqualTo(CacheScope.ORG);
        assertThat(result).as("I-8: boundary 不发送").extracting(SystemPromptBlock::text)
            .doesNotContain(BOUNDARY);
    }

    // ═══════════════════ 模式 2：boundary 命中 ═══════════════════

    @Test
    @DisplayName("boundary 命中模式 → 静态 global / 动态 null / attribution+prefix null（api.ts:368-397）")
    void boundaryMode_staticGlobalDynamicNull() {
        List<SystemPromptBlock> result = SystemPromptSplitter.splitSysPromptPrefix(
            List.of(ATTRIBUTION, PREFIX, "staticA", BOUNDARY, "dynamicB"), true, false);
        assertThat(result).hasSize(4);
        assertThat(result.get(0)).isEqualTo(new SystemPromptBlock(ATTRIBUTION, CacheScope.NULL));
        assertThat(result.get(1)).isEqualTo(new SystemPromptBlock(PREFIX, CacheScope.NULL));
        assertThat(result.get(2)).isEqualTo(new SystemPromptBlock("staticA", CacheScope.GLOBAL));
        assertThat(result.get(3)).isEqualTo(new SystemPromptBlock("dynamicB", CacheScope.NULL));
        assertThat(result).extracting(SystemPromptBlock::text).doesNotContain(BOUNDARY);
    }

    @Test
    @DisplayName("boundary 命中 → 静态多段 join + 动态多段 join（≤4 block 红线，claude.ts:3214-3216）")
    void boundaryMode_joinsMultiSegmentBlocks() {
        List<SystemPromptBlock> result = SystemPromptSplitter.splitSysPromptPrefix(
            List.of("s1", "s2", BOUNDARY, "d1", "d2"), true, false);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).text()).isEqualTo("s1\n\ns2");
        assertThat(result.get(0).cacheScope()).isEqualTo(CacheScope.GLOBAL);
        assertThat(result.get(1).text()).isEqualTo("d1\n\nd2");
        assertThat(result.get(1).cacheScope()).isEqualTo(CacheScope.NULL);
    }

    // ═══════════════════ 模式 3：默认 ═══════════════════

    @Test
    @DisplayName("默认模式 → attribution(null)/prefix(org)/rest(org)，boundary 当普通内容（api.ts:412-435）")
    void defaultMode_allOrgScopes() {
        List<SystemPromptBlock> result = SystemPromptSplitter.splitSysPromptPrefix(
            List.of(ATTRIBUTION, PREFIX, "content"), false, false);
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(new SystemPromptBlock(ATTRIBUTION, CacheScope.NULL));
        assertThat(result.get(1)).isEqualTo(new SystemPromptBlock(PREFIX, CacheScope.ORG));
        assertThat(result.get(2)).isEqualTo(new SystemPromptBlock("content", CacheScope.ORG));
    }

    @Test
    @DisplayName("默认模式：useGlobalCache=false 时 boundary 不触发（I-8 依旧：单串无 boundary 字符串残留）")
    void defaultMode_boundaryNotSplitWhenGlobalCacheOff() {
        List<SystemPromptBlock> result = SystemPromptSplitter.splitSysPromptPrefix(
            List.of("pre", BOUNDARY, "post"), false, false);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("pre\n\n" + BOUNDARY + "\n\npost");
        assertThat(result.get(0).cacheScope()).isEqualTo(CacheScope.ORG);
    }

    @Test
    @DisplayName("null/空 输入 → 空列表（不 NPE）")
    void emptyInput_returnsEmpty() {
        assertThat(SystemPromptSplitter.splitSysPromptPrefix(null, false, false)).isEmpty();
        assertThat(SystemPromptSplitter.splitSysPromptPrefix(List.of(), true, true)).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-SP2-07 SP-01 △2] 空串元素按 CC falsy 语义剔除（api.ts:337/374/416 !prompt）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("模式 1 · 空串元素剔除（CC api.ts:337 !prompt）→ rest join 无空串伪段")
    void skipGlobalCacheMode_emptyStringsFiltered() {
        List<SystemPromptBlock> blocks = SystemPromptSplitter.splitSysPromptPrefix(
            List.of(PREFIX, "", "a", "", "b"), true, true);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).text()).isEqualTo(PREFIX);
        assertThat(blocks.get(1).text())
            .as("空串按 falsy 剔除 → rest join 仅 a/b（现实现保留空串产出 \\n\\na\\n\\nb）")
            .isEqualTo("a\n\nb");
    }

    @Test
    @DisplayName("模式 2 · 空串元素不进入 static/dynamic join（CC api.ts:374 !block）")
    void boundaryMode_emptyStringsFiltered() {
        List<SystemPromptBlock> blocks = SystemPromptSplitter.splitSysPromptPrefix(
            List.of(PREFIX, "", "s1", "", BOUNDARY, "d1", ""), true, false);
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1).cacheScope()).isEqualTo(CacheScope.GLOBAL);
        assertThat(blocks.get(1).text())
            .as("static join 不得含空串伪段（现实现产出 \\n\\ns1）")
            .isEqualTo("s1");
        assertThat(blocks.get(2).cacheScope()).isEqualTo(CacheScope.NULL);
        assertThat(blocks.get(2).text())
            .as("dynamic join 不得含空串伪段（现实现产出 d1\\n\\n）")
            .isEqualTo("d1");
    }

    @Test
    @DisplayName("模式 3 · 空串元素剔除（CC api.ts:416 !block）→ rest join 无空串伪段")
    void defaultMode_emptyStringsFiltered() {
        List<SystemPromptBlock> blocks = SystemPromptSplitter.splitSysPromptPrefix(
            List.of(PREFIX, "", "a", "", "b"), false, false);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).text()).isEqualTo(PREFIX);
        assertThat(blocks.get(1).text())
            .as("空串按 falsy 剔除 → rest join 仅 a/b（现实现保留空串产出 \\n\\na\\n\\nb）")
            .isEqualTo("a\n\nb");
    }
}
