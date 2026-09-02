package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P-CC-02] readFileState 双限真 LRU（{@link FileStateCache}）容量配置 + 驱逐行为验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>:
 * 验证 FileStateCache 双限（maxEntries=100 + maxSizeBytes=25MB）<b>同时</b>生效 ——
 * 任一超限立即驱逐最久未访问 entry（LRU）。若有人把任一上限调大一个量级, 测试必须能区分.
 * 双限语义对齐 CC {@code utils/fileStateCache.ts:34-38}（LRUCache {@code max}/{@code maxSize}
 * 同时强制, 任一超限即驱逐）。
 *
 * <p>覆盖维度:
 * <ol>
 *   <li><b>条目数上限</b> — 插入 150 条 1 字节 entry → 恰留 100 条（CC max:100 硬限；
 *       旧 Caffeine maximumWeight-only 实现 150 条全留, 本用例 RED）</li>
 *   <li><b>字节总量上限</b> — 累积 >25MB → 按 LRU 驱逐（CC maxSize:25MB 硬限）</li>
 *   <li><b>单条超限</b> — 单条 >25MB → set 入口 reject（lru-cache maxEntrySize 拒绝：
 *       删除已有同 key、不插入、不驱逐其它 entry）</li>
 *   <li><b>weigher 只算 content 字节</b> — mtime/offset/limit/isPartialView 不计权</li>
 *   <li><b>自定义容量</b> — createFileStateCache(maxEntries, maxSizeBytes) 双参都生效</li>
 *   <li><b>clone 沿用源容量</b> — CC :122-126 cloneFileStateCache 语义</li>
 * </ol>
 */
@DisplayName("[P-CC-02] readFileState FileStateCache 双限真 LRU 容量 + weigher 验证")
class ToolUseContextFileStateTest {

    /**
     * [P-CC-02] 条目数硬限（CC max:100）——旧实现（Caffeine maximumWeight-only, maxEntries 仅
     * "API 对齐"）在此场景 150 条全留, 本用例为真实 RED。
     */
    @Test
    @DisplayName("条目数上限 100: 150 条 1 字节 entry → 恰留 100 条（CC max:100 硬限, 最旧 50 条驱逐）")
    void maxEntries100EvictsOldestWhenOver100() {
        FileStateCache cache = ToolUseContext.createFileStateCache();
        IntStream.range(0, 150).forEach(i ->
            cache.set("path/file_" + i + ".java",
                new ToolUseContext.ReadState(0L, null, null, false, "x")));
        // 双限: 150 条 × 1B = 150B << 25MB（字节限不触发）→ 由条目数限驱逐到 100
        assertThat(cache.size())
            .as("150 条 > maxEntries=100, 应驱逐最旧 50 条, 恰留 100 条")
            .isEqualTo(100);
        assertThat(cache.get("path/file_0.java"))
            .as("最早插入的 key 应被驱逐 (LRU 末尾淘汰)")
            .isNull();
        assertThat(cache.get("path/file_49.java"))
            .as("第 50 条也已被驱逐（恰留 file_50..file_149）")
            .isNull();
        assertThat(cache.get("path/file_50.java"))
            .as("第 51 条开始保留")
            .isNotNull();
        assertThat(cache.get("path/file_149.java"))
            .as("最近插入的 key 必须保留")
            .isNotNull();
    }

    /**
     * [P-CC-02] 字节总量硬限（CC maxSize:25MB）——同步驱逐（无 Caffeine lazy eviction 窗口）,
     * 稳态条目数可精确断言。
     */
    @Test
    @DisplayName("字节总量上限 25MB: 200 条 600KB = 120MB → 驱逐至 42 条 (600KB×42=24.6MB ≤ 25MB)")
    void maxWeight25MbEvictsWhenAccumulatedOverLimit() {
        FileStateCache cache = ToolUseContext.createFileStateCache();
        String content600Kb = "x".repeat(600 * 1024);  // 600KB
        IntStream.range(0, 200).forEach(i ->
            cache.set("path/file_" + i + ".java",
                new ToolUseContext.ReadState(0L, null, null, false, content600Kb)));
        // 稳态: floor(25MB / 600KB) = 42 条 (42×614400=24.6MB ≤ 25MB; 43 条即超限)
        assertThat(cache.size())
            .as("600KB×42=24.6MB ≤ 25MB, 600KB×43>25MB → 稳态恰 42 条（同步驱逐确定性）")
            .isEqualTo(42);
        assertThat(cache.get("path/file_0.java"))
            .as("最早插入的 key 应被驱逐 (LRU 末尾淘汰)")
            .isNull();
        assertThat(cache.get("path/file_199.java"))
            .as("最近插入的 key 必须保留")
            .isNotNull();
    }
    /**
     * [P-CC-02] 单条超限语义（CC lru-cache maxEntrySize reject: set 入口拒绝——
     * 删除已有同 key、不插入、不驱逐其它 entry; v11.5.2 index.js:919-927）;
     * 多条累积超限时按 LRU 驱逐旧条目。
     *
     * <p>R1 返工: 旧实现 set 先 put 再驱逐循环, 非空缓存写 30MB 会把旧 entry 连带清空;
     * 场景 C/D 为此回归的 RED 断言（旧实现失败）。
     */
    @Test
    @DisplayName("字节总量上限 25MB: 单条 30MB reject 保留旧 entry · 两条 20MB 累积 40MB → 第一条驱逐")
    void maxWeight25MbEvictsWhenContentOverLimit() {
        // 场景 A: 空缓存 set 单条 30MB > 25MB → reject 不插入, cache 空
        FileStateCache cacheA = ToolUseContext.createFileStateCache();
        cacheA.set("a/big1.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(30 * 1024 * 1024)));
        assertThat(cacheA.size())
            .as("单条 30MB > 25MB maxSize, reject 不插入（lru-cache maxEntrySizeExceeded）")
            .isZero();
        assertThat(cacheA.get("a/big1.bin")).isNull();

        // 场景 B: 两条各 20MB = 40MB > 25MB → 累积超限驱逐第一条, 剩第二条
        FileStateCache cacheB = ToolUseContext.createFileStateCache();
        cacheB.set("a/big1.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(20 * 1024 * 1024)));
        cacheB.set("a/big2.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(20 * 1024 * 1024)));
        assertThat(cacheB.size())
            .as("累积 40MB > 25MB, cache 应只剩 1 条 (big2)")
            .isEqualTo(1);
        assertThat(cacheB.get("a/big1.bin"))
            .as("最早插入的 key 应被驱逐")
            .isNull();
        assertThat(cacheB.get("a/big2.bin"))
            .as("最近插入的 key 必须保留")
            .isNotNull();

        // 场景 C (R1 RED): 非空缓存 + 单条 30MB → reject, 旧 entry 全部保留、大 key 不存在
        // (CC 分歧场景: Edit/Write 写回 >25MB 文件时保留其余 entry; 旧 Java 驱逐循环会连带清空)
        FileStateCache cacheC = ToolUseContext.createFileStateCache();
        cacheC.set("old/keep1.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(1024)));
        cacheC.set("old/keep2.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "y".repeat(1024)));
        cacheC.set("a/big3.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(30 * 1024 * 1024)));
        assertThat(cacheC.size())
            .as("单条 30MB reject 不应触发驱逐, 2 条旧 entry 全保留")
            .isEqualTo(2);
        assertThat(cacheC.get("old/keep1.bin"))
            .as("旧 entry keep1 必须保留 (reject 不驱逐其它 entry)")
            .isNotNull();
        assertThat(cacheC.get("old/keep2.bin"))
            .as("旧 entry keep2 必须保留 (reject 不驱逐其它 entry)")
            .isNotNull();
        assertThat(cacheC.get("a/big3.bin"))
            .as("超限大 key 不存在 (reject 不插入)")
            .isNull();

        // 场景 D (R1 RED): 覆盖已存在 key 且超限 → 旧 key 被删除、不插入、其它 entry 保留
        // (CC #delete(k,'set'): v11.5.2 index.js:919-927)
        FileStateCache cacheD = ToolUseContext.createFileStateCache();
        cacheD.set("a/small.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(1024)));
        cacheD.set("a/big4.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "y".repeat(1024)));
        cacheD.set("a/small.bin",
            new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(30 * 1024 * 1024)));
        assertThat(cacheD.size())
            .as("覆盖超限: 旧 small 被 delete(key), 不插入, big4 保留 → 剩 1 条")
            .isEqualTo(1);
        assertThat(cacheD.get("a/small.bin"))
            .as("同 key 超限覆盖后旧值被删除 (CC #delete(k,'set'))")
            .isNull();
        assertThat(cacheD.get("a/big4.bin"))
            .as("其它 entry 不受 reject 影响")
            .isNotNull();
    }
    /**
     * [P-CC-02] weigher 只算 content 字节, 不算 mtime/offset/limit/isPartialView
     * （CC fileStateCache.ts:37 sizeCalculation 对齐）。
     */
    @Test
    @DisplayName("weigher 只算 content 字节: content=null 兜底 1 字节 · 元数据不计入 weight")
    void weigherOnlyCountsContentBytes() {
        // null content → weight=1 (对齐 CC Math.max(1, Buffer.byteLength(undefined)) = 1)
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(999999L, 100, 200, true, null)))
            .as("content=null → weigher 兜底 1 字节")
            .isEqualTo(1);

        // 大 mtime + offset + limit + isPartialView=true 不污染 weight
        // 创建 100 条 content=null 但元数据 100 字节的 entry → 累计 weight = 100 字节
        FileStateCache cache = ToolUseContext.createFileStateCache();
        for (int i = 0; i < 100; i++) {
            cache.set("path/file_" + i + ".java",
                new ToolUseContext.ReadState(System.currentTimeMillis(), 999, 999, true, null));
        }
        assertThat(cache.size())
            .as("100 条 null content entry: 每条 weight=1, 累计 100 字节 << 25MB 且条数 ≤ 100 → 全留")
            .isEqualTo(100);
    }

    /**
     * [P-CC-02] weightOf 单测 — 覆盖 ASCII / UTF-8 / 空 / null（CC fileStateCache.ts:37 直译）。
     */
    @Test
    @DisplayName("weightOf: ASCII = char 数 · UTF-8 多字节字符按 UTF-8 字节 · 空字符串 = 1")
    void weightOfEdgeCases() {
        // ASCII: 1 字符 = 1 字节
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(0L, null, null, false, "abc"))).isEqualTo(3);
        // UTF-8 中文: "中" = 3 字节
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(0L, null, null, false, "中"))).isEqualTo(3);
        // 空字符串: Math.max(1, 0) = 1 (对齐 CC)
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(0L, null, null, false, ""))).isEqualTo(1);
        // null content: 兜底 1
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(0L, null, null, false, null))).isEqualTo(1);
        // null state: 兜底 1
        assertThat(ToolUseContext.weightOf(null)).isEqualTo(1);
    }

    /**
     * [P-CC-02] createFileStateCache(maxEntries, maxSizeBytes) 双参都生效
     * （CC createFileStateCacheWithSizeLimit(maxEntries, maxSizeBytes) 语义, :101-106）。
     */
    @Test
    @DisplayName("createFileStateCache(maxEntries, maxSizeBytes): 条数限 (3,100) 第 4 条驱逐 · 字节限 (10,50) 单条 100B 逐出")
    void createFileStateCacheWithCustomCapacity() {
        // 容量 (3, 100): 4 条 1 字节 entry → 条目数限 3 先触发, 驱逐最旧
        FileStateCache small = ToolUseContext.createFileStateCache(3, 100);
        small.set("a", new ToolUseContext.ReadState(0L, null, null, false, "1"));  // 1B
        small.set("b", new ToolUseContext.ReadState(0L, null, null, false, "2"));  // 1B
        small.set("c", new ToolUseContext.ReadState(0L, null, null, false, "3"));  // 1B
        small.set("d", new ToolUseContext.ReadState(0L, null, null, false, "4"));  // 1B
        assertThat(small.size())
            .as("4 条 > maxEntries=3, 字节 4B < 100 → 条目数限驱逐, 恰留 3 条")
            .isEqualTo(3);
        assertThat(small.get("a"))
            .as("最旧的 a 被驱逐")
            .isNull();
        assertThat(small.get("d"))
            .as("最新的 d 保留")
            .isNotNull();

        // 字节限 (10, 50): 单条 100B > 50B → 每条 set 入口 reject 不插入, cache 恒空
        FileStateCache weightTest = ToolUseContext.createFileStateCache(10, 50);
        for (int i = 0; i < 10; i++) {
            weightTest.set("big_" + i,
                new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(100)));
        }
        assertThat(weightTest.size())
            .as("10 条 100B, 每条单条 weight 100B > 50B → 全部 reject 不插入, 恒空")
            .isZero();
    }

    /**
     * [P-CC-02] cloneFileStateCache — 复制初始 entries + 子父独立 + 子容量沿用父（CC :122-126）。
     */
    @Test
    @DisplayName("cloneFileStateCache: 复制初始 entries + 子父独立 + 子沿用父容量约束")
    void cloneFileStateCacheCopiesEntriesAndIndependence() {
        ToolUseContext parent = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT);
        // 父 cache 插入 3 条
        parent.readFileState().set("a.txt",
            new ToolUseContext.ReadState(0L, null, null, false, "content_a"));
        parent.readFileState().set("b.txt",
            new ToolUseContext.ReadState(0L, null, null, false, "content_b"));
        parent.readFileState().set("c.txt",
            new ToolUseContext.ReadState(0L, null, null, false, "content_c"));
        assertThat(parent.readFileState().size()).isEqualTo(3);

        // 克隆
        FileStateCache child = ToolUseContext.cloneFileStateCache(parent.readFileState());
        assertThat(child.size()).isEqualTo(3);

        // 子 cache 修改不影响父
        child.set("d.txt",
            new ToolUseContext.ReadState(0L, null, null, false, "content_d"));
        assertThat(child.size()).isEqualTo(4);
        assertThat(parent.readFileState().size())
            .as("子 cache 修改不影响父")
            .isEqualTo(3);

        // 父 cache 修改不影响子
        parent.readFileState().delete("a.txt");
        assertThat(parent.readFileState().size()).isEqualTo(2);
        assertThat(child.get("a.txt"))
            .as("父 delete 后, 子 cache 仍持有 a.txt (clone 时已 fork)")
            .isNotNull();

        // 验证子 cache 也受双限约束 (沿用父 100/25MB)
        for (int i = 0; i < 200; i++) {
            child.set("path_" + i + ".txt",
                new ToolUseContext.ReadState(0L, null, null, false, "x".repeat(600 * 1024)));
        }
        assertThat(child.size())
            .as("clone 出来的子 cache 也受 maxSizeBytes=25MB 约束")
            .isLessThan(200);
    }

    /**
     * [P-CC-02] clone 沿用<b>源 cache</b>容量（CC fileStateCache.ts:122-126:
     * {@code createFileStateCacheWithSizeLimit(cache.max, cache.maxSize)}）——
     * 自定义容量源 clone 后仍受源条数限约束。
     */
    @Test
    @DisplayName("cloneFileStateCache: 自定义容量源 (3,100) clone 后沿用 3/100, 第 4 条驱逐")
    void cloneFileStateCachePreservesSourceCapacity() {
        FileStateCache source = ToolUseContext.createFileStateCache(3, 100);
        source.set("a", new ToolUseContext.ReadState(0L, null, null, false, "1"));
        source.set("b", new ToolUseContext.ReadState(0L, null, null, false, "2"));
        source.set("c", new ToolUseContext.ReadState(0L, null, null, false, "3"));

        FileStateCache cloned = ToolUseContext.cloneFileStateCache(source);
        assertThat(cloned.max()).isEqualTo(3);
        assertThat(cloned.maxSize()).isEqualTo(100L);
        assertThat(cloned.size()).isEqualTo(3);

        // 第 4 条 → 条目数限 3 驱逐最旧
        cloned.set("d", new ToolUseContext.ReadState(0L, null, null, false, "4"));
        assertThat(cloned.size()).isEqualTo(3);
        assertThat(cloned.get("a")).isNull();
        assertThat(cloned.get("d")).isNotNull();
    }
}
