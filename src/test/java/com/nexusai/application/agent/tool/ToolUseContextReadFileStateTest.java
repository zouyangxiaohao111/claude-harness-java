package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session L+ · {@link ToolUseContext#readFileState} 字段契约验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * R1 把 dedup 状态从 ReadFileTool 实例字段上提到 TUC 字段, 跨工具共享 + 父→子透传.
 * <p>[P-CC-02] 类型由 Caffeine Cache 升级为 {@link FileStateCache}（双限真 LRU,
 * 对齐 CC {@code utils/fileStateCache.ts:30-93 FileStateCache}）. 关键不变量:
 * <ol>
 *   <li><b>默认空 Cache</b> — 新建 ctx 拿到的 readFileState 不为 null, 可直接 set/get</li>
 *   <li><b>可变性</b> — ReadFileTool / EditFileTool 等会直接 .set(), FileStateCache 是可变 thread-safe</li>
 *   <li><b>父子隔离</b> — createSubagentContext 必须 clone, 避免子 Agent 写 dedup 污染父 cache
 *       (但 ReadState 本身是 immutable record, 改值 = set 替换 = 不会污染 entry)</li>
 *   <li><b>clone 语义</b> — cloneFileStateCache 后子修改不影响父 (子独立 FileStateCache 实例)</li>
 *   <li><b>[P-CC-02] 双限容量</b> — maxEntries=100 + maxSizeBytes=25MB（用户 2026-08-05
 *       拍板严格对齐 CC fileStateCache.ts:18/:22）</li>
 * </ol>
 */
@DisplayName("Session L+ · ToolUseContext.readFileState 跨工具共享 + 父→子透传 + FileStateCache 双限真 LRU")
class ToolUseContextReadFileStateTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    @Test
    @DisplayName("默认 readFileState: 新建 ctx 自动获得非 null 空 FileStateCache —— 工具可立即 .set()")
    void defaultReadFileStateIsEmptyMutableCache() {
        ToolUseContext ctx = ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT);

        FileStateCache cache = ctx.readFileState();
        assertThat(cache).isNotNull();
        assertThat(cache.size()).isZero();

        // 可变性: ReadFileTool.dispatchText 会 .set(), FileStateCache 自身 thread-safe + 可变.
        cache.set("path/to/file.txt",
            new ToolUseContext.ReadState(System.currentTimeMillis(), 1, 2000, false, null));
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("父子 clone 透传: cloneFileStateCache 后子 ctx 拿到父 cache 的浅克隆, 父仍持有原 entry")
    void parentChildCloneSemantics() {
        // 步骤 1: 父 ctx 登记一个 dedup entry
        ToolUseContext parent = ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT);
        long mtime = System.currentTimeMillis();
        parent.readFileState().set("src/Main.java",
            new ToolUseContext.ReadState(mtime, 1, 2000, false, null));
        assertThat(parent.readFileState().size()).isEqualTo(1);

        // 步骤 2: 走 ToolUseContext.cloneFileStateCache() 显式 clone (本测试不调 createSubagentContext
        //   避免构造重型参数; 直接调用工厂方法)
        FileStateCache clonedChildCache =
            ToolUseContext.cloneFileStateCache(parent.readFileState());

        // 步骤 3: 子 ctx 持有 clone 后的 Cache (用 18 参 ctor 模拟)
        ToolUseContext child = new ToolUseContext(
            UUID.randomUUID(), SESSION_ID, PermissionMode.DEFAULT,
            java.util.Map.of(), java.util.List.of(), "",
            AbortController.NOOP, java.util.List.of(),
            null, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "",
            null, null, java.util.Map.of(), null,
            clonedChildCache);

        // 验证: 子 ctx 拿到与父相同 entry (浅克隆, 同一 ReadState 实例)
        assertThat(child.readFileState().size()).isEqualTo(1);
        assertThat(child.readFileState().get("src/Main.java").mtimeMillis()).isEqualTo(mtime);

        // 验证: 父修改不会影响子 (clone 是 Cache 引用分离, entry 共享)
        //  - 但子 .set() 一个新 entry 不影响父
        child.readFileState().set("src/Child.java",
            new ToolUseContext.ReadState(mtime, 1, 2000, false, null));
        assertThat(child.readFileState().size()).isEqualTo(2);
        assertThat(parent.readFileState().size()).isEqualTo(1);  // 父不变

        // 父 set 一个新 entry, 子不变 (clone 已 fork)
        parent.readFileState().set("src/Parent.java",
            new ToolUseContext.ReadState(mtime, 1, 2000, false, null));
        assertThat(parent.readFileState().size()).isEqualTo(2);
        assertThat(child.readFileState().size()).isEqualTo(2);  // 子不变
    }

    @Test
    @DisplayName("ReadState immutable record: 字段 final, 子 Agent 替换 entry 不影响父持有 entry")
    void readStateIsImmutable() {
        long mtime = 12345L;
        // [L+ round 3] ReadState 加 content 字段
        ToolUseContext.ReadState original = new ToolUseContext.ReadState(mtime, 1, 2000, false, null);

        // 父 cache 持有 entry
        ToolUseContext parent = ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT);
        parent.readFileState().set("a.txt", original);
        assertThat(parent.readFileState().get("a.txt")).isSameAs(original);

        // 子 ctx 通过 cloneFileStateCache 拿到同一 entry 引用 (浅克隆, 同一 ReadState 实例)
        FileStateCache childCache =
            ToolUseContext.cloneFileStateCache(parent.readFileState());
        ToolUseContext child = new ToolUseContext(
            UUID.randomUUID(), SESSION_ID, PermissionMode.DEFAULT,
            java.util.Map.of(), java.util.List.of(), "",
            AbortController.NOOP, java.util.List.of(),
            null, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "",
            null, null, java.util.Map.of(), null,
            childCache);

        // 子 cache 的 entry 仍指向父的同一 ReadState 实例
        assertThat(child.readFileState().get("a.txt")).isSameAs(original);
        assertThat(child.readFileState().get("a.txt").mtimeMillis()).isEqualTo(mtime);

        // 子 .set() 替换 entry: 新 ReadState 实例, 不影响父持有的原 entry
        long newMtime = 67890L;
        child.readFileState().set("a.txt",
            new ToolUseContext.ReadState(newMtime, 2, 100, true, null));
        assertThat(parent.readFileState().get("a.txt")).isSameAs(original);  // 父 entry 不变
        assertThat(parent.readFileState().get("a.txt").mtimeMillis()).isEqualTo(mtime);
        assertThat(child.readFileState().get("a.txt").mtimeMillis()).isEqualTo(newMtime);
    }

    @Test
    @DisplayName("ReadState.full/ReadState.window 工厂: 全文读 + 窗口读 一行构造 (语义化 API)")
    void readStateFactoryMethods() {
        long mtime = 999L;
        ToolUseContext.ReadState full = ToolUseContext.ReadState.full(mtime, "hello\n");
        assertThat(full.mtimeMillis()).isEqualTo(mtime);
        assertThat(full.offset()).isNull();
        assertThat(full.limit()).isNull();
        assertThat(full.content()).isEqualTo("hello\n");

        // 窗口读: offset/limit=具体值, isPartialView=false
        // [L+ GAP-C] 旧断言 isPartialView=true 锁死错误语义: CC 的 isPartialView 仅
        // memory 注入场景 (attachments.ts:1749), Read/窗口路径恒 falsy (FileReadTool.ts:1032-1037
        // 从不写该字段; 窗口 entry falsy 可过 Edit/Write 门禁 FileEditTool.ts:276).
        ToolUseContext.ReadState window = ToolUseContext.ReadState.window(mtime, 10, 50);
        assertThat(window.mtimeMillis()).isEqualTo(mtime);
        assertThat(window.offset()).isEqualTo(10);
        assertThat(window.limit()).isEqualTo(50);
        assertThat(window.isPartialView())
            .as("GAP-C: 窗口读 isPartialView=false (CC 仅 memory 注入才 true)")
            .isFalse();
        assertThat(window.content()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [P-CC-02] FileStateCache 双限配置验证 (用户 2026-08-05 拍板严格对齐 CC 100/25MB)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[P-CC-02] READ_FILE_STATE_CACHE_SIZE = 100 · 用户 2026-08-05 拍板严格对齐 CC (fileStateCache.ts:18)")
    void readFileStateCacheSizeIsCc100() {
        assertThat(ToolUseContext.READ_FILE_STATE_CACHE_SIZE)
            .as("条目数上限 100 (CC original: READ_FILE_STATE_CACHE_SIZE=100, utils/fileStateCache.ts:18)")
            .isEqualTo(100);
    }

    @Test
    @DisplayName("[P-CC-02] DEFAULT_MAX_CACHE_SIZE_BYTES = 25MB · 用户 2026-08-05 拍板严格对齐 CC (fileStateCache.ts:22)")
    void defaultMaxCacheSizeIs25Mb() {
        assertThat(ToolUseContext.DEFAULT_MAX_CACHE_SIZE_BYTES)
            .as("字节上限 25MB (CC original: DEFAULT_MAX_CACHE_SIZE_BYTES=25*1024*1024, utils/fileStateCache.ts:22)")
            .isEqualTo(25L * 1024L * 1024L);
    }

    @Test
    @DisplayName("[P-CC-02] weightOf: Math.max(1, content 字节) · 对齐 CC weigher 语义")
    void weightOfReflectsContentBytes() {
        // null content → 兜底 1 字节 (对齐 CC Math.max(1, ...))
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(0L, null, null, false, null))).isEqualTo(1);
        // null state → 兜底 1 字节
        assertThat(ToolUseContext.weightOf(null)).isEqualTo(1);
        // 非空 content → content.getBytes(UTF_8).length (ASCII 等长, 中文 UTF-8 3 字节/字)
        String ascii = "hello";  // 5 bytes
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(0L, null, null, false, ascii))).isEqualTo(5);
        String chinese = "你好";  // 6 bytes UTF-8
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(0L, null, null, false, chinese))).isEqualTo(6);
        // 空字符串 → Math.max(1, 0) = 1 (对齐 CC)
        assertThat(ToolUseContext.weightOf(
            new ToolUseContext.ReadState(0L, null, null, false, ""))).isEqualTo(1);
    }

    @Test
    @DisplayName("[P-CC-02] cloneFileStateCache: null 兜底 + 子父隔离")
    void cloneFileStateCacheNullAndIndependence() {
        // null source → 新空 cache
        FileStateCache empty = ToolUseContext.cloneFileStateCache(null);
        assertThat(empty).isNotNull();
        assertThat(empty.size()).isZero();

        // 非空 source → clone 后子父隔离
        ToolUseContext parent = ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT);
        parent.readFileState().set("a.txt",
            new ToolUseContext.ReadState(0L, 1, 2000, false, "hello"));
        FileStateCache child =
            ToolUseContext.cloneFileStateCache(parent.readFileState());
        assertThat(child.size()).isEqualTo(1);

        // 子 set 不影响父
        child.set("b.txt",
            new ToolUseContext.ReadState(0L, 1, 2000, false, "world"));
        assertThat(child.size()).isEqualTo(2);
        assertThat(parent.readFileState().size())
            .as("子 set 新 entry 不应影响父")
            .isEqualTo(1);
    }
}
