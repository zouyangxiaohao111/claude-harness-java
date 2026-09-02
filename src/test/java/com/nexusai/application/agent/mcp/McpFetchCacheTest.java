package com.nexusai.application.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-15: McpFetchCache（per-server LRU 缓存）单测 · 对齐 CC
 * {@code utils/memoize.ts:234-280 memoizeWithLRU.cache}（lru-cache max=MCP_FETCH_CACHE_SIZE）。
 *
 * <p>RED→GREEN：本测试先于 McpFetchCache 实现运行 → 断言失败（类不存在）；
 * 实现后转绿。
 */
@DisplayName("P2-15 McpFetchCache per-server LRU 缓存")
class McpFetchCacheTest {

    @Test
    @DisplayName("LRU 淘汰：cap 20 满后最久未用先出（memoize.ts:242 lru-cache max eviction）")
    void lruEviction_evictsLeastRecentlyUsed_whenFull() {
        // WHY: CC MCP_FETCH_CACHE_SIZE=20（client.ts:1726）防止多 MCP server 无界增长——
        // 容量满后必须淘汰最久未用条目，否则内存无界（CC 曾因 lodash memoize 300MB+）
        McpFetchCache<String> cache = new McpFetchCache<>(20);
        for (int i = 0; i < 20; i++) {
            cache.put("s" + i, "v" + i);
        }
        assertThat(cache.size()).isEqualTo(20);

        // get("s0") 提升访问序 → s0 变最新，s1 变最久未用
        assertThat(cache.get("s0")).isEqualTo("v0");
        cache.put("s20", "v20"); // 容量满 → 淘汰最久未用 = s1

        assertThat(cache.size()).isEqualTo(20);
        assertThat(cache.has("s0")).isTrue();  // 刚被访问 → 保留
        assertThat(cache.has("s1")).isFalse(); // 最久未用 → 淘汰
        assertThat(cache.get("s20")).isEqualTo("v20");
        assertThat(cache.get("s1")).isNull();  // 被淘汰后 miss
    }

    @Test
    @DisplayName("delete：单个 server 缓存移除（client.ts:1389-1396/1666-1672 失效点）")
    void delete_removesSingleServerEntry() {
        McpFetchCache<String> cache = new McpFetchCache<>(20);
        cache.put("a", "va");
        cache.put("b", "vb");
        cache.delete("a");
        assertThat(cache.has("a")).isFalse();
        assertThat(cache.has("b")).isTrue();
        assertThat(cache.size()).isEqualTo(1);
        // delete 不存在的 key 幂等（对齐 CC cache.delete(name)）
        cache.delete("nope");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("clear/has/size：memoize.ts cache API（clear/size/has/get(peek)）")
    void clearHasSize_apiShape() {
        McpFetchCache<Integer> cache = new McpFetchCache<>(5);
        assertThat(cache.has("x")).isFalse();
        assertThat(cache.get("x")).isNull();
        assertThat(cache.size()).isZero();

        cache.put("x", 1);
        cache.put("y", 2);
        assertThat(cache.has("x")).isTrue();
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("y")).isEqualTo(2);

        cache.clear();
        assertThat(cache.size()).isZero();
        assertThat(cache.has("x")).isFalse();
        assertThat(cache.has("y")).isFalse();
    }

    @Test
    @DisplayName("cap=1 边界：每次 put 都淘汰旧条目")
    void capOne_evictsOnEveryPut() {
        McpFetchCache<String> cache = new McpFetchCache<>(1);
        cache.put("a", "va");
        cache.put("b", "vb");
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.has("a")).isFalse();
        assertThat(cache.get("b")).isEqualTo("vb");
    }
}
