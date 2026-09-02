package com.nexusai.application.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件状态缓存 · 对齐 CC {@code utils/fileStateCache.ts:30-93 FileStateCache}（Java 直译）。
 *
 * <p><b>CC original:</b> {@code utils/fileStateCache.ts:30-93}（2026-08-05 拍板严格对齐 CC）:
 * <pre>
 * export class FileStateCache {
 *   private cache: LRUCache&lt;string, FileState&gt;
 *   constructor(maxEntries: number, maxSizeBytes: number) {
 *     this.cache = new LRUCache({ max: maxEntries, maxSize: maxSizeBytes,
 *       sizeCalculation: value =&gt; Math.max(1, Buffer.byteLength(value.content)) })
 *   }
 *   ...
 * }
 * </pre>
 *
 * <p><b>双限语义（CC 核心）</b>: {@code max}（条目数上限）与 {@code maxSize}（字节总量上限）
 * <b>同时生效</b> —— 任一超限立即驱逐最久未访问 entry（LRU）。Java 端用
 * {@link LinkedHashMap} {@code accessOrder=true} 实现真 LRU：迭代序 = 最旧→最新，
 * {@code put} 已存在 key 与 {@code get} 都会把该 entry 移到最新（访问序）。
 *
 * <p><b>WHY 自写而非 Caffeine（规则三：CC 复杂 Java 就复杂）</b>: Caffeine 的
 * {@code maximumSize} 与 {@code maximumWeight} 互斥（requireState 强校验），单 cache 无法
 * 同时表达 CC 的"100 条 + 25MB"双限。旧实现（L+ round 5）只设 {@code maximumWeight}，
 * 条目数上限"隐式"（注释自述 maxEntries 仅作 API 对齐）——小文件场景可驻留远超 100 条，
 * 偏离 CC {@code max:100} 硬限（可观察：dedup 命中集不同）。自写双限 LRU 精确复刻
 * CC 双限 + 真 LRU 驱逐序（Caffeine 是 W-TinyLFU 近似，LinkedHashMap accessOrder 是纯 LRU）。
 *
 * <p><b>weigher 对齐</b>（{@code fileStateCache.ts:37}）: {@code Math.max(1, content UTF-8 字节)}，
 * 只算 {@code content} 字段，mtime/offset/limit/isPartialView 元数据不计权
 * （{@link ToolUseContext#weightOf}）。
 *
 * <p><b>key 归一化</b>: CC 内部 {@code path.normalize(key)}（:42/:46/:51/:55）；Java 端所有
 * key 由 {@link ToolUseContext#keyForReadFileState} 统一派生（guard.resolve + toAbsolutePath +
 * normalize 双重保险，ToolUseContext.java:899-902），本类不重复归一（单点等价，避免双轨）。
 *
 * <p><b>单条超限语义（CC lru-cache maxEntrySize reject 路径）</b>: 单条 weight &gt;
 * maxSizeBytes 时 {@link #set} 直接拒绝——删除已有同 key、不插入、<b>不驱逐其它 entry</b>。
 * lru-cache {@code set()} 内 {@code if (maxEntrySize && size > maxEntrySize) {
 * #delete(k, 'set'); return }}（maxEntrySize 默认 = maxSize；v10.4.3 index.js:904-907 /
 * v11.5.2 index.js:919-927 自验，E-PCC-02-07）。仅<em>累积</em>超限（calculatedSize +
 * 新 weight &gt; maxSizeBytes）或条目数超限才触发 LRU 驱逐循环（见
 * {@link #evictWhileOverLimit()}）。
 *
 * <p><b>线程安全</b>: 会话级 dedup 缓存，读写并发度低；全方法 {@code synchronized} 足够
 * （Caffeine 无锁读的收益在本场景无意义，且其 lazy eviction 反而引入不确定驱逐时机）。
 */
public final class FileStateCache {

    private static final Logger log = LoggerFactory.getLogger(FileStateCache.class);

    /** 条目数上限（CC original: {@code max}，fileStateCache.ts:18 READ_FILE_STATE_CACHE_SIZE=100） */
    private final int maxEntries;
    /** 字节总量上限（CC original: {@code maxSize}，fileStateCache.ts:22 DEFAULT_MAX_CACHE_SIZE_BYTES=25MB） */
    private final long maxSizeBytes;
    /** 当前驻留字节总量（weigher 只算 content 字节；CC original: calculatedSize，:74-76） */
    private long calculatedSize;
    /** accessOrder=true → 迭代序 = 最旧（LRU）→ 最新；驱逐取迭代首元素 */
    private final LinkedHashMap<String, ToolUseContext.ReadState> cache;

    /**
     * 构造双限 LRU 缓存 · 对齐 CC {@code fileStateCache.ts:33-39} 构造器。
     *
     * @param maxEntries   条目数上限（CC original: {@code max}）
     * @param maxSizeBytes 字节总量上限（CC original: {@code maxSize}）
     */
    public FileStateCache(int maxEntries, long maxSizeBytes) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0: " + maxEntries);
        }
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSizeBytes must be > 0: " + maxSizeBytes);
        }
        this.maxEntries = maxEntries;
        this.maxSizeBytes = maxSizeBytes;
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * 读取 entry 并触碰 LRU 序 · CC original: {@code get(key) { return this.cache.get(normalize(key)) } }
     * （fileStateCache.ts:41-43）。缺失返回 null。
     */
    public synchronized ToolUseContext.ReadState get(String key) {
        return cache.get(key);
    }
    /**
     * 判断 key 是否存在（不触碰 LRU 序）· CC original: {@code has(key) { return this.cache.has(normalize(key)) } }
     * （fileStateCache.ts:50-52；lru-cache has 不更新 recency，与 get 语义区分）。
     */
    public synchronized boolean has(String key) {
        return cache.containsKey(key);
    }

    /**
     * 写入 entry（同 key 覆盖并触碰 LRU 序）。
     * CC original: {@code set(key, value)}（fileStateCache.ts:45-48）。
     *
     * <p><b>单条超限走 reject</b>（lru-cache {@code #set} 内
     * {@code if (maxEntrySize && size > maxEntrySize) { #delete(k, 'set'); return }}，
     * v11.5.2 index.js:919-927）：新 weight &gt; maxSizeBytes 时删除已有同 key、
     * 不插入、不驱逐其它 entry；仅当插入后<em>累积</em>超限（calculatedSize &gt;
     * maxSizeBytes）或条目数超限时才同步驱逐最久未访问 entry 直至双限满足。
     */
    public synchronized void set(String key, ToolUseContext.ReadState value) {
        int weight = ToolUseContext.weightOf(value);
        // CC lru-cache maxEntrySize reject: 单条 weight > maxSizeBytes 时删除已有同 key、
        // 不插入、不驱逐其它 entry (v11.5.2 index.js:919-927 #set 前置拒绝; maxEntrySize
        // 默认 = maxSize, index.js:302). 与 CC 分歧场景: Edit/Write 写回 >25MB 文件时
        // CC 保留其余 entry, 旧 Java 驱逐循环会连带清空非空缓存 (R1).
        if (weight > maxSizeBytes) {
            delete(key);
            if (log.isDebugEnabled()) {
                log.debug("FileStateCache: 单条超限 reject (maxSizeBytes={}) key={} weight={} 不插入且不驱逐其它 entry, 剩余条目={} 剩余字节={}",
                    maxSizeBytes, key, weight, cache.size(), calculatedSize);
            }
            return;
        }
        ToolUseContext.ReadState old = cache.put(key, value);
        if (old != null) {
            calculatedSize -= ToolUseContext.weightOf(old);
        }
        calculatedSize += weight;
        evictWhileOverLimit();
    }

    /**
     * 删除 entry · CC original: {@code delete(key)}（fileStateCache.ts:54-56，LRUCache.delete 返回 boolean）。
     *
     * @return 原 key 存在并删除返回 true
     */
    public synchronized boolean delete(String key) {
        ToolUseContext.ReadState removed = cache.remove(key);
        if (removed != null) {
            calculatedSize -= ToolUseContext.weightOf(removed);
            return true;
        }
        return false;
    }

    /** 清空全部 entry · CC original: {@code clear()}（fileStateCache.ts:58-60，runAgent.ts:828 清理语义） */
    public synchronized void clear() {
        cache.clear();
        calculatedSize = 0;
    }

    /** 当前条目数 · CC original: {@code get size()}（fileStateCache.ts:62-64） */
    public synchronized int size() {
        return cache.size();
    }

    /** 条目数上限 · CC original: {@code get max()}（fileStateCache.ts:66-68；clone 沿用源容量用，:123） */
    public int max() {
        return maxEntries;
    }

    /** 字节总量上限 · CC original: {@code get maxSize()}（fileStateCache.ts:70-72；clone 沿用源容量用，:123） */
    public long maxSize() {
        return maxSizeBytes;
    }

    /** 当前驻留字节总量 · CC original: {@code get calculatedSize()}（fileStateCache.ts:74-76） */
    public synchronized long calculatedSize() {
        return calculatedSize;
    }

    /**
     * 全部 entry 快照迭代器（最旧→最新）· CC original: {@code entries(): Generator}
     * （fileStateCache.ts:82-84）。快照迭代避免迭代期修改抛 CME（CC 生成器对调用方无此保证，
     * Java 快照更安全，语义等价：cacheToObject 等价物 compact.ts:518 取全量）。
     */
    public synchronized Iterator<Map.Entry<String, ToolUseContext.ReadState>> entries() {
        return List.copyOf(cache.entrySet()).iterator();
    }

    /**
     * 驱逐循环 · 对齐 CC lru-cache 内部 eviction（构造选项 {@code max}/{@code maxSize}
     * 同时强制，fileStateCache.ts:34-38）：条目数达 {@code max} 时新 key 插入前先驱逐最旧
     * （lru-cache {@code #set} addition 分支 {@code size === max ? #evict(false)}，
     * v11.5.2 index.js:933）；累积字节超限时 {@code while (entrySize > maxSize) evict()}
     * 从最旧（LRU）开始逐出（v11.5.2 index.js #addItemSize）。Java 端在插入后统一
     * {@code while (size > maxEntries || calculatedSize > maxSizeBytes)} 驱逐，与 CC 终态
     * 一致（均从 LRU 端逐出直至双限满足）。单条超限 entry 不进入本循环——在
     * {@link #set} 入口被 reject（见类 javadoc 单条超限语义）。
     */
    private void evictWhileOverLimit() {
        Iterator<Map.Entry<String, ToolUseContext.ReadState>> it = cache.entrySet().iterator();
        while ((cache.size() > maxEntries || calculatedSize > maxSizeBytes) && it.hasNext()) {
            Map.Entry<String, ToolUseContext.ReadState> eldest = it.next();  // 迭代序 = 最旧（LRU）
            it.remove();
            long weight = ToolUseContext.weightOf(eldest.getValue());
            calculatedSize -= weight;
            if (log.isDebugEnabled()) {
                log.debug("FileStateCache: 容量驱逐 (maxEntries={}, maxSizeBytes={}) key={} weight={} 剩余条目={} 剩余字节={}",
                    maxEntries, maxSizeBytes, eldest.getKey(), weight, cache.size(), calculatedSize);
            }
        }
    }
}
