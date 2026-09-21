package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SystemPromptSections 双工厂测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC 用两个独立工厂区分缓存语义
 * （systemPromptSections.ts:20-25 cacheBreak=false vs :32-38 cacheBreak=true）。
 * cacheBreak 决定 per-section 缓存是否参与（IMP-SP-02 resolve 短路），
 * 工厂把「该 section 是否破缓存」编码进构造，而不是留给调用方——测试钉死这一契约：
 * <ul>
 *   <li>systemPromptSection → cacheBreak=false（可缓存，/clear 前不重算）</li>
 *   <li>dangerousUncachedSystemPromptSection → 机制保留：reason 必填 + 段名必须在白名单
 *       （理由值不落 record）；⚠️ 白名单<b>当前为空表</b>（对齐 CC 2.1.278
 *       「{@code cacheBreak:true} 计数 0」）⇒ 该工厂当前对<b>任何</b>段名 fail loud，
 *       ⛔ 不是「已废弃可删」</li>
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
    @DisplayName("dangerousUncached 机制：白名单为空表 ⇒ 任何段名都 fail loud；reason 仍必填在先（CC:32-38）")
    void dangerousUncached_emptyWhitelist_guardsStillFailLoud() {
        SystemPromptSection.ComputeFn compute = noop();

        // ① reason 必填（CC TS 签名级强制）：空白 reason 先于白名单判定 fail loud
        assertThatThrownBy(() -> SystemPromptSections.dangerousUncachedSystemPromptSection(
            "mcp_instructions", compute, "  "))
            .as("理由为空白 ⇒ fail loud（CC _reason 为必填形参，systemPromptSections.ts:32-38）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("理由");

        // ② 白名单空表 ⇒ 连 2.1.88 时代唯一的 mcp_instructions 也不再获批
        assertThatThrownBy(() -> SystemPromptSections.dangerousUncachedSystemPromptSection(
            "mcp_instructions", compute, "MCP 状态每轮变化需破缓存"))
            .as("白名单 0 条（CC 2.1.278 cacheBreak:true 计数 0）⇒ 原唯一条目也不再获批")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("白名单");

        // ③ 任意新段名同样 fail loud（⛔ 不允许「顺手加一个易失段」悄悄打掉前缀缓存）
        assertThatThrownBy(() -> SystemPromptSections.dangerousUncachedSystemPromptSection(
            "some_new_volatile_section", compute, "随手加的易失段"))
            .as("段名不在白名单 ⇒ fail loud")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("守卫双点：record 紧凑构造器同守卫（⛔ 直接 new 也不能绕过，cacheBreak=true 恒被拒）")
    void recordCompactConstructor_sameGuard() {
        SystemPromptSection.ComputeFn compute = noop();

        assertThatThrownBy(() -> new SystemPromptSection("rogue_section", compute, true))
            .as("SystemPromptSection 紧凑构造器同样守卫 cacheBreak=true 的段名")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("白名单");

        // 2.1.88 时代的白名单段名同样被拒（白名单已清空）
        assertThatThrownBy(() -> new SystemPromptSection("mcp_instructions", compute, true))
            .as("白名单为空 ⇒ 连 mcp_instructions 也不能构造 cacheBreak=true")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("白名单内容：对齐 CC 2.1.278 后为 0 条（cacheBreak:true 计数 0）")
    void cacheBreakWhitelist_isEmpty() {
        assertThat(SystemPromptCacheBreakWhitelist.allowedNames())
            .as("重算白名单已清空（对齐 2.1.278「全份提示没有一个段每轮破坏缓存」）")
            .isEmpty();
        assertThat(SystemPromptCacheBreakWhitelist.size())
            .as("条目数 0")
            .isZero();
        assertThat(SystemPromptCacheBreakWhitelist.isAllowed("mcp_instructions"))
            .as("2.1.88 时代的唯一白名单段名现已不再获批")
            .isFalse();
        assertThat(SystemPromptCacheBreakWhitelist.reasonFor("mcp_instructions"))
            .as("无登记理由（条目已移除）")
            .isNull();
    }

    @Test
    @DisplayName("compute：async 契约——CompletableFuture 可携带 null 结果（CC string|null 联合）")
    void compute_carriesNullResult() {
        SystemPromptSection.ComputeFn nullCompute = () -> CompletableFuture.completedFuture(null);

        SystemPromptSection section = SystemPromptSections.systemPromptSection("nullable", nullCompute);

        assertThat(section.compute().compute().join()).as("CC string|null，null 合法（I-3 null 值被缓存）").isNull();
    }
}
