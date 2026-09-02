package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemPromptSections 双工厂测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC 用两个独立工厂区分缓存语义
 * （systemPromptSections.ts:20-25 cacheBreak=false vs :32-38 cacheBreak=true）。
 * cacheBreak 决定 per-section 缓存是否参与（IMP-SP-02 resolve 短路），
 * 工厂把「该 section 是否破缓存」编码进构造，而不是留给调用方——测试钉死这一契约：
 * <ul>
 *   <li>systemPromptSection → cacheBreak=false（可缓存，/clear 前不重算）</li>
 *   <li>dangerousUncachedSystemPromptSection → cacheBreak=true（每轮重算），reason 被忽略</li>
 *   <li>name/compute 透传不变</li>
 * </ul>
 */
class SystemPromptSectionsTest {

    private static SystemPromptSection.ComputeFn noop() {
        return () -> CompletableFuture.completedFuture("value");
    }

    @Test
    @DisplayName("systemPromptSection：cacheBreak=false（CC:20-25 可缓存 section）")
    void systemPromptSection_isCached() {
        SystemPromptSection.ComputeFn compute = noop();

        SystemPromptSection section = SystemPromptSections.systemPromptSection("identity", compute);

        assertThat(section.cacheBreak())
            .as("systemPromptSection 必须 cacheBreak=false（计算一次，/clear 前不重算）")
            .isFalse();
        assertThat(section.name()).isEqualTo("identity");
        assertThat(section.compute()).as("compute 回调必须透传原引用").isSameAs(compute);
    }

    @Test
    @DisplayName("dangerousUncachedSystemPromptSection：cacheBreak=true 且 reason 被忽略（CC:32-38 _reason）")
    void dangerousUncached_isVolatile_reasonIgnored() {
        SystemPromptSection.ComputeFn compute = noop();

        SystemPromptSection section = SystemPromptSections.dangerousUncachedSystemPromptSection(
            "mcp", compute, "MCP 状态每轮变化需破缓存");

        assertThat(section.cacheBreak())
            .as("dangerousUncachedSystemPromptSection 必须 cacheBreak=true（每轮重算）")
            .isTrue();
        assertThat(section.name()).isEqualTo("mcp");
        assertThat(section.compute()).isSameAs(compute);

        // reason 忽略：不同 reason 产生语义相同的易失 section
        SystemPromptSection other = SystemPromptSections.dangerousUncachedSystemPromptSection(
            "mcp", compute, "另一个理由");
        assertThat(other.cacheBreak()).as("reason 不影响 cacheBreak（CC _reason 忽略）").isTrue();
        assertThat(other.name()).as("name 透传不受 reason 影响").isEqualTo("mcp");
    }

    @Test
    @DisplayName("compute：async 契约——CompletableFuture 可携带 null 结果（CC string|null 联合）")
    void compute_carriesNullResult() {
        SystemPromptSection.ComputeFn nullCompute = () -> CompletableFuture.completedFuture(null);

        SystemPromptSection section = SystemPromptSections.systemPromptSection("nullable", nullCompute);

        assertThat(section.compute().compute().join()).as("CC string|null，null 合法（I-3 null 值被缓存）").isNull();
    }
}
