package com.nexusai.application.agent.agent;

import com.nexusai.application.agent.skill.NexusaiPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentMemorySnapshot 快照三态 + 初始化/替换 · 对齐 CC agentMemorySnapshot.ts:98-186.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>三态 none/initialize/prompt-update</b>（agentMemorySnapshot.ts:98-144）——决定
 *       loadAgentsDir 首次是否拷贝快照（initialize）还是仅提示新快照（prompt-update）。
 *       误判会把项目共享快照错误覆盖用户本地 memory 或漏掉更新。</li>
 *   <li><b>initializeFromSnapshot / replaceFromSnapshot</b>（:149-186）——真实临时目录集成
 *       （非 mock seam），验证拷贝内容 + synced 元数据落盘 + replace 删本地孤儿 .md。</li>
 *   <li><b>getSyncedJsonPath 感知 scope</b>——D3 修复：user/project 两种 scope 的 synced
 *       元数据必须落在各自 agent memory 目录，否则 scope 间相互覆盖。</li>
 * </ol>
 *
 * <p>快照目录形如 &lt;cwd&gt;/&lt;getProjectDirName()&gt;/agent-memory-snapshots/&lt;agentType&gt;/；
 * synced 元数据落在 &lt;agentMemoryDir(agentType, scope)&gt;/.snapshot-synced.json。
 */
class AgentMemorySnapshotTest {

    @TempDir Path tempDir;

    private Path cwd;
    private Path memoryBase;
    private AgentMemoryDirectory dir;
    private AgentMemorySnapshot snapshot;

    @BeforeEach
    void setUp() {
        cwd = tempDir.resolve("cwd");
        memoryBase = tempDir.resolve("membase");
        // user scope → memoryBase/agent-memory/<type>；project scope → cwd/<getProjectDirName()>/agent-memory/<type>（决策 D6 项目写迁移）
        dir = new AgentMemoryDirectory(
            cwd::toString,
            () -> memoryBase,
            () -> null,
            () -> cwd,
            s -> s,
            path -> { /* fire-and-forget mkdir: 由 snapshot 真实 Files 创建 */ },
            () -> null,
            () -> true,
            com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
        snapshot = new AgentMemorySnapshot(cwd::toString, dir);
    }

    private Path snapshotDir(String agentType) {
        return cwd.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-snapshots").resolve(agentType);
    }

    private Path userMemDir(String agentType) {
        return memoryBase.resolve("agent-memory").resolve(agentType);
    }

    private void writeSnapshotMeta(String agentType, String updatedAt) throws Exception {
        Files.createDirectories(snapshotDir(agentType));
        Files.writeString(snapshotDir(agentType).resolve("snapshot.json"),
            "{\"updatedAt\":\"" + updatedAt + "\"}");
    }

    @Test
    @DisplayName("无 snapshot.json → none（agentMemorySnapshot.ts:110-112）")
    void check_no_snapshot_is_none() throws Exception {
        // WHY: 项目无快照 = 不初始化、不提示，agent memory 保持现状。
        assertThat(snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER))
            .isEqualTo(AgentMemorySnapshot.SnapshotCheckResult.none());
    }

    @Test
    @DisplayName("有 snapshot.json 但本地无 .md → initialize（首次从快照拷贝，:124-126）")
    void check_snapshot_no_local_memory_is_initialize() throws Exception {
        // WHY: 快照存在 + 本地 agent memory 目录为空 → 首次初始化（copySnapshotToLocal +
        // saveSyncedMeta），否则 agent 丢失项目共享记忆。
        writeSnapshotMeta("my-agent", "2026-08-05T10:00:00Z");
        AgentMemorySnapshot.SnapshotCheckResult r =
            snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER);
        assertThat(r.action()).isEqualTo("initialize");
        assertThat(r.snapshotTimestamp()).isEqualTo("2026-08-05T10:00:00Z");
    }

    @Test
    @DisplayName("本地有 .md 且 snapshot 比 syncedFrom 新 → prompt-update（:133-141）")
    void check_local_memory_snapshot_newer_is_prompt_update() throws Exception {
        // WHY: 本地已有记忆但项目快照更新（或从未 sync）→ 提示用户更新（前端 dialog），
        // 而非自动覆盖 —— 自动 replace 会丢用户本地新增记忆。
        writeSnapshotMeta("my-agent", "2026-08-05T10:00:00Z");
        Files.createDirectories(userMemDir("my-agent"));
        Files.writeString(userMemDir("my-agent").resolve("local.md"), "local memory");

        // 无 syncedFrom → prompt-update
        assertThat(snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER)
                .action()).isEqualTo("prompt-update");

        // 有 syncedFrom 但 snapshot 更新 → prompt-update
        Files.createDirectories(userMemDir("my-agent"));
        Files.writeString(userMemDir("my-agent").resolve(".snapshot-synced.json"),
            "{\"syncedFrom\":\"2026-08-04T10:00:00Z\"}");
        assertThat(snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER)
                .action()).isEqualTo("prompt-update");
    }

    @Test
    @DisplayName("本地 .md 存在且 syncedFrom 不旧于 snapshot → none（已同步，:143）")
    void check_local_memory_synced_up_to_date_is_none() throws Exception {
        // WHY: 已同步到最新 → 既不初始化也不提示，避免重复打扰。
        writeSnapshotMeta("my-agent", "2026-08-05T10:00:00Z");
        Files.createDirectories(userMemDir("my-agent"));
        Files.writeString(userMemDir("my-agent").resolve("local.md"), "local memory");
        Files.writeString(userMemDir("my-agent").resolve(".snapshot-synced.json"),
            "{\"syncedFrom\":\"2026-08-05T10:00:00Z\"}");
        assertThat(snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER)
                .action()).isEqualTo("none");
    }

    @Test
    @DisplayName("getSyncedJsonPath 感知 scope：user/project 落在各自 agent memory 目录（D3 修复）")
    void getSyncedJsonPath_is_scope_aware() {
        // WHY: D3 偏移——旧单参 agentMemoryDirSupplier 忽略 scope，user/project 的 synced
        // 元数据会写进同一路径，scope 切换时错误判定已同步。CC getSyncedJsonPath(agentType, scope)
        // 用 getAgentMemoryDir(agentType, scope)（agentMemorySnapshot.ts:39-41）。
        Path userPath = snapshot.getSyncedJsonPath("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER);
        Path projectPath = snapshot.getSyncedJsonPath("my-agent", AgentMemoryDirectory.AgentMemoryScope.PROJECT);
        assertThat(userPath).isEqualTo(userMemDir("my-agent").resolve(".snapshot-synced.json"));
        assertThat(projectPath).isEqualTo(
            cwd.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory").resolve("my-agent").resolve(".snapshot-synced.json"));
        assertThat(userPath).isNotEqualTo(projectPath);
    }

    @Test
    @DisplayName("initializeFromSnapshot 拷贝快照 .md 到本地 + 写 synced 元数据（:149-159）")
    void initialize_from_snapshot_copies_files_and_saves_synced() throws Exception {
        // WHY: 首次初始化必须把项目快照的非 snapshot.json 文件全部拷入本地 agent memory 目录，
        // 并写 syncedFrom 元数据使后续 check 返回 none。
        Path snapDir = snapshotDir("my-agent");
        Files.createDirectories(snapDir);
        Files.writeString(snapDir.resolve("topic.md"), "shared knowledge");
        Files.writeString(snapDir.resolve("snapshot.json"), "{\"updatedAt\":\"2026-08-05T10:00:00Z\"}");

        snapshot.initializeFromSnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER, "2026-08-05T10:00:00Z");

        assertThat(Files.readString(userMemDir("my-agent").resolve("topic.md"))).isEqualTo("shared knowledge");
        assertThat(Files.readString(userMemDir("my-agent").resolve(".snapshot-synced.json")))
            .contains("\"syncedFrom\":\"2026-08-05T10:00:00Z\"");
        // snapshot.json 本身不拷贝到本地 memory
        assertThat(Files.exists(userMemDir("my-agent").resolve("snapshot.json"))).isFalse();
    }

    @Test
    @DisplayName("replaceFromSnapshot 删除本地孤儿 .md 后拷入新快照（:164-186）")
    void replace_from_snapshot_removes_stale_local_files() throws Exception {
        // WHY: replace 语义 = 以项目快照为准，本地孤儿（不在快照中的）旧 .md 必须删除，
        // 否则残留记忆与快照冲突。
        Files.createDirectories(userMemDir("my-agent"));
        Files.writeString(userMemDir("my-agent").resolve("stale.md"), "outdated");

        Path snapDir = snapshotDir("my-agent");
        Files.createDirectories(snapDir);
        Files.writeString(snapDir.resolve("fresh.md"), "fresh knowledge");

        snapshot.replaceFromSnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER, "2026-08-05T10:00:00Z");

        assertThat(Files.exists(userMemDir("my-agent").resolve("stale.md"))).isFalse();
        assertThat(Files.readString(userMemDir("my-agent").resolve("fresh.md"))).isEqualTo("fresh knowledge");
    }

    @Test
    @DisplayName("initialize 后 check 转 none（synced 闭环，:143）")
    void initialize_then_check_returns_none() throws Exception {
        // WHY: 初始化成功后 syncedFrom 写入 + 本地出现 .md → 后续 check 应返回 none（快照已应用），
        // 避免重复初始化。快照必须含 .md 文件，否则初始化后本地仍无 .md → 仍是 initialize。
        writeSnapshotMeta("my-agent", "2026-08-05T10:00:00Z");
        Files.writeString(snapshotDir("my-agent").resolve("topic.md"), "shared");
        snapshot.initializeFromSnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER, "2026-08-05T10:00:00Z");
        assertThat(snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER)
                .action()).isEqualTo("none");
    }

    @Test
    @DisplayName("快照目录/JSON 路径形态（agentMemorySnapshot.ts:31-37）")
    void snapshot_paths_shape() {
        assertThat(snapshot.getSnapshotDirForAgent("my-agent"))
            .isEqualTo(cwd.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-snapshots").resolve("my-agent"));
        assertThat(snapshot.getSnapshotJsonPath("my-agent"))
            .isEqualTo(cwd.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-snapshots").resolve("my-agent").resolve("snapshot.json"));
    }

    @Test
    @DisplayName("宽松日期解析：空格分隔 LocalDateTime 与 date-only（对齐 CC new Date 宽松比较）")
    void lenient_date_parsing_matches_cc() throws Exception {
        // WHY: [FIX-AM REQ-M-19] CC :135 用 new Date(a) > new Date(b) 宽松比较；旧 Java 仅
        //   Instant.parse 严格 ISO-8601。快照 updatedAt 用空格分隔（"2026-08-05 10:00:00"）
        //   或 date-only（"2026-08-05"）时旧实现解析失败回退 0L → 比较语义与 CC 漂移。
        //   宽松降级后：空格格式仍能正确比较新旧。
        Files.createDirectories(userMemDir("my-agent"));
        Files.writeString(userMemDir("my-agent").resolve("local.md"), "local");
        // snapshot 用空格分隔格式，syncedFrom 用严格 ISO——旧 Instant 严格解析会失败回退 0L
        writeSnapshotMeta("my-agent", "2026-08-05 10:00:00");
        Files.writeString(userMemDir("my-agent").resolve(".snapshot-synced.json"),
            "{\"syncedFrom\":\"2026-08-04T10:00:00Z\"}");
        // snapshot(2026-08-05 10:00) > syncedFrom(2026-08-04 10:00) → prompt-update（对齐 CC 宽松比较）
        assertThat(snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER)
                .action()).isEqualTo("prompt-update");
    }

    @Test
    @DisplayName("AM-03：copySnapshotToLocal mkdir 失败显式传播（mkdir 在 try 外，agentMemorySnapshot.ts:63）")
    void copy_mkdir_failure_propagates() throws Exception {
        // WHY: CC mkdir(localMemDir,{recursive:true}) 在 try 外 → 失败抛出 → initializeFromSnapshot 中止
        //   （旧 Java createDirectories 在 try 内 catch 吞掉 → 快照初始化静默失败，△-1）。
        // 确定性失败：memoryBase/agent-memory 以普通文件存在 → createDirectories 抛 FileAlreadyExistsException。
        Path userMemBase = memoryBase.resolve("agent-memory");
        Files.createDirectories(memoryBase);
        Files.writeString(userMemBase, "blocking file");
        writeSnapshotMeta("my-agent", "2026-08-05T10:00:00Z");
        assertThatThrownBy(() -> snapshot.initializeFromSnapshot("my-agent",
            AgentMemoryDirectory.AgentMemoryScope.USER, "2026-08-05T10:00:00Z"))
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("AM-03：saveSyncedMeta mkdir 失败显式传播（mkdir 在 try 外，agentMemorySnapshot.ts:86）")
    void saveSyncedMeta_mkdir_failure_propagates() throws Exception {
        // WHY: CC saveSyncedMeta mkdir 在 try 外 → 失败抛出；writeFile 才在 try 内（:88-92）。
        Path userMemBase = memoryBase.resolve("agent-memory");
        Files.createDirectories(memoryBase);
        Files.writeString(userMemBase, "blocking file");
        assertThatThrownBy(() -> snapshot.markSnapshotSynced("my-agent",
            AgentMemoryDirectory.AgentMemoryScope.USER, "2026-08-05T10:00:00Z"))
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("AM-03：拷贝循环文件失败被外层 catch 吞掉，synced 元数据仍写（agentMemorySnapshot.ts:65-76/:79-93）")
    void copy_loop_failure_contained_but_initialize_continues() throws Exception {
        // WHY: CC copy 循环无内层 try → 首个 writeFile 失败中止剩余文件，外层 catch logForDebugging 吞掉
        //   → initializeFromSnapshot 继续 saveSyncedMeta（CC :157-158 顺序）。
        Path snapDir = snapshotDir("my-agent");
        Files.createDirectories(snapDir);
        Files.writeString(snapDir.resolve("snapshot.json"), "{\"updatedAt\":\"2026-08-05T10:00:00Z\"}");
        Files.writeString(snapDir.resolve("topic.md"), "shared knowledge");
        // 本地同名目录使 writeString(topic.md) 抛 FileAlreadyExistsException（首个即败）
        Path userMemDir = userMemDir("my-agent");
        Files.createDirectories(userMemDir.resolve("topic.md"));
        snapshot.initializeFromSnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER, "2026-08-05T10:00:00Z");
        // 失败被外层 catch 吞掉（不传播）；synced 元数据仍写入
        assertThat(Files.readString(userMemDir.resolve(".snapshot-synced.json")))
            .contains("\"syncedFrom\":\"2026-08-05T10:00:00Z\"");
    }

    @Test
    @DisplayName("AM-03/G-71：拷贝循环首个失败中止剩余文件（agentMemorySnapshot.ts:69-72 无内层 try）")
    void copy_aborts_remaining_files_on_first_failure() throws Exception {
        // WHY: CC 逐文件 readFile+writeFile 无内层 try → 首个失败直接跳到外层 catch，剩余文件不再尝试
        //   （△-2 全有全无 vs 旧 Java 逐文件 try 继续）。目录枚举顺序：a.md 字典序在 b.md 前
        //   （NTFS/多数 FS 按名排序；ext4 按创建序，a.md 先创建），失败项恒在前。
        Path snapDir = snapshotDir("my-agent");
        Files.createDirectories(snapDir);
        Files.writeString(snapDir.resolve("snapshot.json"), "{\"updatedAt\":\"2026-08-05T10:00:00Z\"}");
        Files.writeString(snapDir.resolve("a.md"), "first");
        Files.writeString(snapDir.resolve("b.md"), "second");
        Path userMemDir = userMemDir("my-agent");
        Files.createDirectories(userMemDir.resolve("a.md")); // a.md 写失败
        snapshot.initializeFromSnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER, "2026-08-05T10:00:00Z");
        // 首败中止 → b.md 不得被拷贝（旧实现会跳过 a.md 继续拷贝 b.md → RED）
        assertThat(Files.exists(userMemDir.resolve("b.md"))).isFalse();
    }

    @Test
    @DisplayName("AM-03/G-71：replace 删除段失败被吞掉，拷贝+synced 仍执行（agentMemorySnapshot.ts:174-185）")
    void replace_delete_failure_still_copies_and_syncs() throws Exception {
        // WHY: CC 删除段单层 try（:174-183）→ 首个 unlink 失败中止删除段（catch 忽略），随后仍
        //   copySnapshotToLocal + saveSyncedMeta（:184-185）。旧 Java 逐文件 try 继续删除（△-2）。
        org.junit.jupiter.api.Assumptions.assumeTrue(
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("dos"),
            "dos 只读属性不可用（非 Windows），跳过删除失败用例");
        Path userMemDir = userMemDir("my-agent");
        Path stale = userMemDir.resolve("stale.md");
        Files.createDirectories(userMemDir);
        Files.writeString(stale, "outdated");
        // Windows dos:readonly → Files.delete 抛 AccessDeniedException（确定性 unlink 失败）
        Files.setAttribute(stale, "dos:readonly", true);
        try {
            Path snapDir = snapshotDir("my-agent");
            Files.createDirectories(snapDir);
            Files.writeString(snapDir.resolve("fresh.md"), "fresh knowledge");
            snapshot.replaceFromSnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER, "2026-08-05T10:00:00Z");
            assertThat(Files.readString(userMemDir.resolve("fresh.md"))).isEqualTo("fresh knowledge");
            assertThat(Files.readString(userMemDir.resolve(".snapshot-synced.json")))
                .contains("\"syncedFrom\":\"2026-08-05T10:00:00Z\"");
            assertThat(Files.exists(stale)).isTrue(); // 删除失败被吞掉，stale 保留
        } finally {
            try { Files.setAttribute(stale, "dos:readonly", false); } catch (Exception ignored) { }
        }
    }

    @Test
    @DisplayName("AM-04：任一日期 Invalid → 比较 false → none（0L 回退移除，agentMemorySnapshot.ts:135）")
    void invalid_date_comparison_is_none() throws Exception {
        // WHY: CC new Date(updatedAt) > new Date(syncedFrom) 任一 Invalid Date → false → none（偏向不打扰）。
        //   旧 Java parseDate 回退 0L → updatedAt(有效) > syncedFrom(0) → prompt-update（△-3，RED）。
        writeSnapshotMeta("my-agent", "2026-08-05T10:00:00Z");
        Files.createDirectories(userMemDir("my-agent"));
        Files.writeString(userMemDir("my-agent").resolve("local.md"), "local");
        Files.writeString(userMemDir("my-agent").resolve(".snapshot-synced.json"),
            "{\"syncedFrom\":\"not-a-date\"}");
        assertThat(snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER)
            .action()).isEqualTo("none");
    }

    @Test
    @DisplayName("AM-06：saveSyncedMeta JSON 序列化转义（jsonStringify，agentMemorySnapshot.ts:89）")
    void save_synced_meta_json_escapes() throws Exception {
        // WHY: 旧实现字符串拼接不转义 → updatedAt 含引号/反斜杠写坏 synced JSON → readSyncedMeta=null
        //   → 恒 prompt-update 重试（△-6）。CC jsonStringify 转义引号/反斜杠。
        String tricky = "2026-08-05T10:00:00\"Z\\x";
        snapshot.markSnapshotSynced("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER, tricky);
        Path synced = userMemDir("my-agent").resolve(".snapshot-synced.json");
        assertThat(Files.readString(synced)).contains("\\\"");
        AgentMemorySnapshot.SyncedMeta meta =
            snapshot.readSyncedMeta("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER);
        assertThat(meta).isNotNull();
        assertThat(meta.syncedFrom()).isEqualTo(tricky);
    }

    @Test
    @DisplayName("AM-10：symlink→.md 不计为本地记忆文件（dirent.isFile 不 follow，agentMemorySnapshot.ts:119）")
    void symlink_md_not_counted_as_local_memory() throws Exception {
        // WHY: CC dirent.isFile() 不 follow symlink；Java Files.isRegularFile 默认 follow（△-9）。
        //   仅 symlink→.md（可解析）时：CC 无真实 .md → initialize；旧 Java count → prompt-update（RED）。
        writeSnapshotMeta("my-agent", "2026-08-05T10:00:00Z");
        Path userMemDir = userMemDir("my-agent");
        Files.createDirectories(userMemDir);
        Path target = tempDir.resolve("target.md");
        Files.writeString(target, "real");
        Path link = userMemDir.resolve("link.md");
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "symlink 创建失败（无权限/无 Developer Mode），跳过 symlink 用例: " + e.getMessage());
            return;
        }
        assertThat(snapshot.checkAgentMemorySnapshot("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER)
            .action()).isEqualTo("initialize");
    }
}
