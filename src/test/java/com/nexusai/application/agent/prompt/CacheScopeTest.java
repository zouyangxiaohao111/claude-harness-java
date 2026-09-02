package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheScope 值域测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC CacheScope 是三态联合
 * （'global' | 'org' | null，utils/api.ts:80-83），Java 以枚举成员忠实编码。
 * 值域<b>恰为</b> GLOBAL/ORG/NULL —— 多一个或少一个成员即与 CC 契约漂移
 * （IMP-SP-06 序列化时 NULL 须映射为不输出 cache_control.scope）。
 */
class CacheScopeTest {

    @Test
    @DisplayName("值域恰为 GLOBAL/ORG/NULL（CC CacheScope = 'global'|'org' + null 联合）")
    void values_exactlyThreeMembers() {
        assertThat(CacheScope.values())
            .as("CC 三态联合的忠实编码，不得增减成员")
            .containsExactly(CacheScope.GLOBAL, CacheScope.ORG, CacheScope.NULL);
    }

    @Test
    @DisplayName("NULL 语义=该 block 不参与缓存（独立于 GLOBAL/ORG）")
    void nullScope_isDistinctCacheParticipant() {
        assertThat(CacheScope.NULL)
            .as("NULL 是枚举成员而非 Java null 字段，可被 SystemPromptBlock 直接承载")
            .isNotSameAs(CacheScope.GLOBAL)
            .isNotSameAs(CacheScope.ORG);
    }
}
