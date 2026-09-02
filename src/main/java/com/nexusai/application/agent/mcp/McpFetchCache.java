package com.nexusai.application.agent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * per-server LRU fetch 缓存 · 对齐 CC {@code utils/memoize.ts:234-280 memoizeWithLRU} 返回的
 * {@code memoized.cache}（lru-cache，{@code max} = {@code MCP_FETCH_CACHE_SIZE}）。
 *
 * <p>CC 原名：{@code memoizeWithLRU(...).cache}（memoize.ts:234-280，cache key =
 * {@code cacheFn(client) = client.name}）。maxCacheSize 由 CC {@code client.ts:1726
 * MCP_FETCH_CACHE_SIZE = 20} 决定——本类构造入参即为该值（{@link McpToolPool#MCP_FETCH_CACHE_SIZE}）。
 *
 * <p>语义对齐：
 * <ul>
 *   <li>LRU 淘汰：{@link LinkedHashMap#get} 提升访问序（accessOrder=true），容量满后
 *       {@code removeEldestEntry} 淘汰最久未用条目（CC lru-cache {@code max} eviction）</li>
 *   <li>{@link #get}：命中提升访问序（对齐 memoized 函数内部 {@code cache.get(key)} 提升 recency）</li>
 *   <li>{@link #delete}：对齐 CC {@code cache.delete(name)}（client.ts:1389-1396/1666-1672、
 *       useManageMCPConnections.ts:631/:681/:717/:723/:724 的缓存失效点）</li>
 * </ul>
 *
 * <p>线程安全：方法级 synchronized（list_changed 通知从 transport 分发线程写入，
 * fetch* 从调用方线程读取，二者可能并发）。
 *
 * @param <T> 缓存值类型（List&lt;McpResource&gt; / List&lt;Command&gt; / List&lt;McpToolEntry&gt;）
 */
public class McpFetchCache<T> {

    /** CC MCP_FETCH_CACHE_SIZE client.ts:1726 — 缓存容量（按 server 名键控，防多 server 无界增长）。 */
    private final int maxSize;
    /** accessOrder=true → get/put 提升访问序，容量满淘汰最久未用（CC lru-cache max eviction）。 */
    private final Map<String, T> cache;

    public McpFetchCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, T> eldest) {
                return size() > McpFetchCache.this.maxSize;
            }
        };
    }

    /**
     * 取缓存值（miss 返回 null）· 对齐 CC memoized 函数内部 {@code cache.get(key)}。
     *
     * <p>注意：本方法的 get 会提升访问序（LRU 语义），与 CC 暴露的 {@code cache.get = peek}
     * （不提升）略有差异——Java 端 fetch 方法即 memoized 函数本身，需提升 recency 保证
     * LRU 淘汰正确性。
     *
     * @param serverName CC cache key = client.name
     * @return 缓存值；miss 返回 null
     */
    public synchronized T get(String serverName) {
        return cache.get(serverName);
    }

    /**
     * 写入缓存 · 对齐 CC {@code cache.set(key, result)}（memoizeWithLRU 内 fetch 后 set）。
     *
     * @param serverName CC cache key = client.name
     * @param value      fetch 结果（含 fail-soft 的空 list，CC 同样缓存 []）
     */
    public synchronized void put(String serverName, T value) {
        cache.put(serverName, value);
    }

    /**
     * 删除单个 server 的缓存 · 对齐 CC {@code cache.delete(name)}
     * （client.ts:1389-1396 onclose / :1666-1672 clearServerCache /
     * useManageMCPConnections.ts:631/:681/:717/:723/:724 list_changed 失效点）。
     *
     * @param serverName CC cache key = client.name
     */
    public synchronized void delete(String serverName) {
        cache.remove(serverName);
    }

    /** 清空全部缓存 · 对齐 CC {@code cache.clear()}（memoize.ts cache API）。 */
    public synchronized void clear() {
        cache.clear();
    }

    /** 当前缓存条目数 · 对齐 CC {@code cache.size()}。 */
    public synchronized int size() {
        return cache.size();
    }

    /**
     * 是否命中 · 对齐 CC {@code cache.has(key)}。
     *
     * @param serverName CC cache key = client.name
     * @return true = 存在（不提升访问序）
     */
    public synchronized boolean has(String serverName) {
        return cache.containsKey(serverName);
    }
}
