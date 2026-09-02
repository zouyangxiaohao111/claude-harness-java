package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemPromptSectionCache 会话级缓存语义测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC 缓存是 per-section name-keyed
 * {@code Map<string, string|null>}（state.ts:203/:1641-1653），compute 返回 null
 * 也必须缓存（I-3）。null 段"命中不重算"与 {@code /clear} 失效、token 节约直接相关：
 * <ul>
 *   <li>set 无条件写回含 null（I-3 写侧，对照 CC setSystemPromptSectionCacheEntry 不判空）；</li>
 *   <li>has 按 key 存在性而非值非空判定（I-3 读侧：null 也视为已缓存）；</li>
 *   <li>clear 清空全部 → 后续 resolve 必须重算（/clear 后新鲜求值的前提）。</li>
 * </ul>
 */
class SystemPromptSectionCacheTest {

    @Test
    @DisplayName("set/get/has：写入值可读回，has 判定键存在")
    void setGetHas_basicSemantics() {
        SystemPromptSectionCache cache = new SystemPromptSectionCache();

        assertThat(cache.has("identity")).as("初始未缓存").isFalse();
        cache.set("identity", "identity-content");

        assertThat(cache.has("identity")).isTrue();
        assertThat(cache.get("identity")).isEqualTo("identity-content");
    }

    @Test
    @DisplayName("I-3 写侧：set 无条件写回 null（CC state.ts:1645-1650 不判空）")
    void set_nullValueIsStored() {
        SystemPromptSectionCache cache = new SystemPromptSectionCache();

        cache.set("nullable", null);

        assertThat(cache.has("nullable"))
            .as("null 值条目仍占 key → has=true（CC cache.has 键存在判定）")
            .isTrue();
        assertThat(cache.get("nullable"))
            .as("get 返回缓存值 null（I-3 读侧）")
            .isNull();
    }

    @Test
    @DisplayName("I-3 全链：null 段二次 resolve 命中不重算（per-section 缓存 /clear 失效与 token 节约前提）")
    void nullSection_isCached_notRecomputedOnHit() {
        SystemPromptSectionCache cache = new SystemPromptSectionCache();
        int[] computeCount = {0};
        SystemPromptSection section = SystemPromptSections.systemPromptSection("nullable", () -> {
            computeCount[0]++;
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        registry.register(section);

        java.util.List<String> first = registry.resolveAll(cache);
        java.util.List<String> second = registry.resolveAll(cache);

        assertThat(first).containsExactly((String) null);
        assertThat(second).containsExactly((String) null);
        assertThat(computeCount[0])
            .as("null 值被缓存 → 二次 resolve 命中短路，compute 只调 1 次（I-3 + I-1）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("clear：清空后 has=false，/clear 失效语义（CC clearSystemPromptSectionState state.ts:1652-1653）")
    void clear_wipesAllEntries() {
        SystemPromptSectionCache cache = new SystemPromptSectionCache();
        cache.set("a", "va");
        cache.set("b", "vb");

        cache.clear();

        assertThat(cache.has("a")).as("clear 后 a 不再缓存").isFalse();
        assertThat(cache.has("b")).as("clear 后 b 不再缓存").isFalse();
        assertThat(cache.get("a")).isNull();
    }

    @Test
    @DisplayName("跨会话隔离：两个 cache 实例互不串扰（CC 会话级 STATE.new Map() state.ts:399）")
    void twoCacheInstances_doNotInterfere() {
        SystemPromptSectionCache sessionA = new SystemPromptSectionCache();
        SystemPromptSectionCache sessionB = new SystemPromptSectionCache();

        sessionA.set("identity", "session-a-content");

        assertThat(sessionA.has("identity")).as("会话 A 已缓存").isTrue();
        assertThat(sessionB.has("identity"))
            .as("会话 B 无此缓存 → 跨会话隔离（不共享 Map）")
            .isFalse();
        assertThat(sessionB.get("identity")).isNull();
    }
}
