package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemPromptBlock record 承载测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC SystemPromptBlock = { text, cacheScope: CacheScope|null }
 * （utils/api.ts:81-84）。block 是发送边界拆分后的最小单元，text 与 cacheScope 必须一起被
 * 忠实承载——cacheScope=null 的 block（NULL）表示不参与缓存，是拆分结果里合法且常见的状态。
 */
class SystemPromptBlockTest {

    @Test
    @DisplayName("record：正确承载 text + GLOBAL cacheScope（CC 静态部分 → 'global'）")
    void block_carriesTextAndGlobalScope() {
        SystemPromptBlock block = new SystemPromptBlock("static content", CacheScope.GLOBAL);

        assertThat(block.text()).isEqualTo("static content");
        assertThat(block.cacheScope()).isEqualTo(CacheScope.GLOBAL);
    }

    @Test
    @DisplayName("record：cacheScope=NULL 承载不参与缓存的动态 block（CC cacheScope:null）")
    void block_carriesNullScope() {
        SystemPromptBlock block = new SystemPromptBlock("dynamic content", CacheScope.NULL);

        assertThat(block.text()).isEqualTo("dynamic content");
        assertThat(block.cacheScope()).as("NULL 是枚举成员（CC null 联合），承载不参与缓存的 block").isEqualTo(CacheScope.NULL);
    }

    @Test
    @DisplayName("record：ORG 作用域独立承载（CC 'org'）")
    void block_carriesOrgScope() {
        SystemPromptBlock block = new SystemPromptBlock("org content", CacheScope.ORG);

        assertThat(block.cacheScope()).isEqualTo(CacheScope.ORG);
    }
}
