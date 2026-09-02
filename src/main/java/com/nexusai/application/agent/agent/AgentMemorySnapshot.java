package com.nexusai.application.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent memory snapshot persistence · 对齐 CC tools/AgentTool/agentMemorySnapshot.ts (197 LOC).
 *
 * <p>Snapshot dir  = &lt;cwd&gt;/.nexusai/agent-memory-snapshots/&lt;agentType&gt;/（决策 D6 项目级写迁移）
 * Synced meta     = &lt;agentMemoryDir(agentType, scope)&gt;/.snapshot-synced.json
 *
 * <p>[IMP-M-P2-2] DEL-M-30：删除 with* 测试助手 + Dirent record + 函数式接口 seam
 * （FileLister/Reader/Writer/Deleter/DirMaker/JsonParser/JsonStringify）——测试零引用，CC 用真实 fs，
 * 改真实 {@link java.nio.file.Files} 操作。getSyncedJsonPath 改经 {@link AgentMemoryDirectory#getAgentMemoryDir}
 * 感知 scope（修复 D3 偏移：单参 agentMemoryDirSupplier 忽略 scope 会把三种 scope 的 synced 元数据
 * 写到同一路径）。
 */
public final class AgentMemorySnapshot {

    private static final Logger log = LoggerFactory.getLogger(AgentMemorySnapshot.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SNAPSHOT_BASE = "agent-memory-snapshots";
    private static final String SNAPSHOT_JSON = "snapshot.json";
    private static final String SYNCED_JSON = ".snapshot-synced.json";

    public record SnapshotMeta(String updatedAt) {}
    public record SyncedMeta(String syncedFrom) {}
    public record SnapshotCheckResult(String action, String snapshotTimestamp) {
        public static SnapshotCheckResult none() { return new SnapshotCheckResult("none", null); }
        public static SnapshotCheckResult initialize(String ts) { return new SnapshotCheckResult("initialize", ts); }
        public static SnapshotCheckResult promptUpdate(String ts) { return new SnapshotCheckResult("prompt-update", ts); }
    }

    private final Supplier<String> cwdSupplier;
    private final AgentMemoryDirectory agentMemoryDirectory;

    public AgentMemorySnapshot(Supplier<String> cwdSupplier,
                               AgentMemoryDirectory agentMemoryDirectory) {
        this.cwdSupplier = Objects.requireNonNull(cwdSupplier);
        this.agentMemoryDirectory = Objects.requireNonNull(agentMemoryDirectory);
    }

    /**
     * CC getSnapshotDirForAgent（agentMemorySnapshot.ts:31-33）·
     * &lt;cwd&gt;/.nexusai/agent-memory-snapshots/&lt;agentType&gt;/（决策 D6 项目级写迁移）
     */
    public Path getSnapshotDirForAgent(String agentType) {
        return Paths.get(cwdSupplier.get(), NexusaiPaths.getProjectDirName(), SNAPSHOT_BASE, agentType);
    }

    /** CC getSnapshotJsonPath（agentMemorySnapshot.ts:35-37）. */
    public Path getSnapshotJsonPath(String agentType) {
        return getSnapshotDirForAgent(agentType).resolve(SNAPSHOT_JSON);
    }

    /** CC getSyncedJsonPath（agentMemorySnapshot.ts:39-41）= getAgentMemoryDir(agentType, scope) + .snapshot-synced.json. */
    public Path getSyncedJsonPath(String agentType, AgentMemoryDirectory.AgentMemoryScope scope) {
        return agentMemoryDirectory.getAgentMemoryDir(agentType, scope).resolve(SYNCED_JSON);
    }

    /** CC readJsonFile（agentMemorySnapshot.ts:43-54）· 真实 Files 读取 + JSON 解析，失败 → null. */
    private JsonNode readJsonFile(Path path) {
        try {
            String content = Files.readString(path);
            return MAPPER.readTree(content);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentMemorySnapshot] 读取 JSON 失败（按无文件处理）: {} - {}", path, e.getMessage());
            }
            return null;
        }
    }

    /** CC snapshotMetaSchema（updatedAt 非空字符串）· 对齐 agentMemorySnapshot.ts:14-18. */
    public SnapshotMeta readSnapshotMeta(String agentType) {
        JsonNode root = readJsonFile(getSnapshotJsonPath(agentType));
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode updatedAt = root.get("updatedAt");
        if (updatedAt == null || !updatedAt.isTextual() || updatedAt.asText().isEmpty()) {
            return null;
        }
        return new SnapshotMeta(updatedAt.asText());
    }

    /** CC syncedMetaSchema（syncedFrom 非空字符串）· 对齐 agentMemorySnapshot.ts:20-25. */
    public SyncedMeta readSyncedMeta(String agentType, AgentMemoryDirectory.AgentMemoryScope scope) {
        JsonNode root = readJsonFile(getSyncedJsonPath(agentType, scope));
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode syncedFrom = root.get("syncedFrom");
        if (syncedFrom == null || !syncedFrom.isTextual() || syncedFrom.asText().isEmpty()) {
            return null;
        }
        return new SyncedMeta(syncedFrom.asText());
    }

    /**
     * CC checkAgentMemorySnapshot（agentMemorySnapshot.ts:98-144）· 快照三态：
     * <ol>
     *   <li>无 snapshot.json → {@code none}</li>
     *   <li>本地无 .md 文件 → {@code initialize}（snapshot.updatedAt）</li>
     *   <li>syncedFrom 缺失 或 snapshot.updatedAt &gt; syncedFrom → {@code prompt-update}（snapshot.updatedAt）</li>
     * </ol>
     *
     * @return {@link SnapshotCheckResult} 三态结果（action: none/initialize/prompt-update）
     */
    public SnapshotCheckResult checkAgentMemorySnapshot(String agentType,
                                                       AgentMemoryDirectory.AgentMemoryScope scope) {
        SnapshotMeta snapshotMeta = readSnapshotMeta(agentType);
        if (snapshotMeta == null) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentMemorySnapshot] checkAgentMemorySnapshot 无 snapshot.json → none: agentType={}", agentType);
            }
            return SnapshotCheckResult.none();
        }

        Path localMemDir = agentMemoryDirectory.getAgentMemoryDir(agentType, scope);
        boolean hasLocalMemory = hasLocalMemoryFile(localMemDir);

        if (!hasLocalMemory) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentMemorySnapshot] checkAgentMemorySnapshot 本地无 .md → initialize: agentType={} snapshot={}",
                    agentType, snapshotMeta.updatedAt());
            }
            return SnapshotCheckResult.initialize(snapshotMeta.updatedAt());
        }

        SyncedMeta syncedMeta = readSyncedMeta(agentType, scope);
        if (syncedMeta == null
            || isNewerThan(snapshotMeta.updatedAt(), syncedMeta.syncedFrom())) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentMemorySnapshot] checkAgentMemorySnapshot snapshot 比 syncedFrom 新 → prompt-update: agentType={} snapshot={} syncedFrom={}",
                    agentType, snapshotMeta.updatedAt(),
                    syncedMeta != null ? syncedMeta.syncedFrom() : "(无 synced 元数据)");
            }
            return SnapshotCheckResult.promptUpdate(snapshotMeta.updatedAt());
        }
        return SnapshotCheckResult.none();
    }

    /** 本地 agent memory 目录是否存在 .md 文件（agentMemorySnapshot.ts:116-122 readdir dirents.some endsWith('.md')）. */
    private boolean hasLocalMemoryFile(Path localMemDir) {
        try (Stream<Path> stream = Files.list(localMemDir)) {
            // [AM-10/OPD-R2-AM-10] CC dirent.isFile() 不 follow symlink（agentMemorySnapshot.ts:119）
            //   → NOFOLLOW_LINKS 显式排除 symlink→.md（△-9：旧 Files.isRegularFile 默认 follow）。
            return stream.anyMatch(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                && p.getFileName().toString().endsWith(".md"));
        } catch (Exception e) {
            // 目录不存在 / 不可读（CC catch → hasLocalMemory=false）
            return false;
        }
    }

    public void initializeFromSnapshot(String agentType,
                                       AgentMemoryDirectory.AgentMemoryScope scope,
                                       String snapshotTimestamp) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[AgentMemorySnapshot] 从项目快照初始化 agent memory: agentType={} scope={}", agentType, scope);
        }
        copySnapshotToLocal(agentType, scope);
        saveSyncedMeta(agentType, scope, snapshotTimestamp);
    }

    /**
     * CC replaceFromSnapshot（agentMemorySnapshot.ts:164-186）· 用快照替换本地 agent memory
     * （先删本地 .md 防孤儿，再拷贝）。
     */
    public void replaceFromSnapshot(String agentType,
                                     AgentMemoryDirectory.AgentMemoryScope scope,
                                     String snapshotTimestamp) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[AgentMemorySnapshot] 用项目快照替换 agent memory: agentType={} scope={}", agentType, scope);
        }
        Path localMemDir = agentMemoryDirectory.getAgentMemoryDir(agentType, scope);
        // [AM-03/OPD-R2-AM-03] CC 删除段单层 try（agentMemorySnapshot.ts:174-183）→ 首个 unlink 失败
        //   中止剩余删除（catch 忽略），随后仍 copySnapshotToLocal + saveSyncedMeta（:184-185）。
        //   旧 Java 逐文件 try-catch 继续删除（△-2）。dirent.isFile() 不 follow → NOFOLLOW_LINKS。
        try (Stream<Path> stream = Files.list(localMemDir)) {
            for (Path p : stream.toList()) {
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    && p.getFileName().toString().endsWith(".md")) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (Exception e) {
            // 目录可能不存在 / 删除失败（CC catch → 忽略，中止删除段）
            if (log.isDebugEnabled()) {
                log.debug("[AgentMemorySnapshot] replaceFromSnapshot 删除本地 .md 中止（首个失败/目录不可读）: {}", e.getMessage());
            }
        }
        copySnapshotToLocal(agentType, scope);
        saveSyncedMeta(agentType, scope, snapshotTimestamp);
    }

    /** CC markSnapshotSynced（agentMemorySnapshot.ts:191-197）· 不改变本地 memory，仅写 synced 元数据. */
    public void markSnapshotSynced(String agentType,
                                    AgentMemoryDirectory.AgentMemoryScope scope,
                                    String snapshotTimestamp) throws IOException {
        saveSyncedMeta(agentType, scope, snapshotTimestamp);
    }

    /** CC copySnapshotToLocal（agentMemorySnapshot.ts:56-77）· 拷贝快照目录非 snapshot.json 文件到本地 memory. */
    void copySnapshotToLocal(String agentType, AgentMemoryDirectory.AgentMemoryScope scope) throws IOException {
        Path snapshotDir = getSnapshotDirForAgent(agentType);
        Path localMemDir = agentMemoryDirectory.getAgentMemoryDir(agentType, scope);
        // [AM-03/OPD-R2-AM-03] CC mkdir(localMemDir,{recursive:true}) 在 try 外（:63）→ 失败显式传播
        //   （旧 Java createDirectories 在 try 内 catch 吞掉 → 快照初始化静默失败，△-1）。
        Files.createDirectories(localMemDir);
        try (Stream<Path> stream = Files.list(snapshotDir)) {
            for (Path p : stream.toList()) {
                // dirent.isFile() 不 follow symlink（:68）→ NOFOLLOW_LINKS
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    || SNAPSHOT_JSON.equals(p.getFileName().toString())) {
                    continue;
                }
                // [AM-03] CC 逐文件无内层 try（:69-72）→ 首个失败中止剩余文件，由外层 catch
                //   logForDebugging 吞掉（△-2 全有全无 vs 旧 Java 跳过失败文件继续）。
                String content = Files.readString(p);
                Files.writeString(localMemDir.resolve(p.getFileName().toString()), content);
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentMemorySnapshot] 拷贝快照目录失败（剩余文件中止）: {} - {}", snapshotDir, e.getMessage());
            }
        }
    }

    /** CC saveSyncedMeta（agentMemorySnapshot.ts:79-93）· 写 syncedFrom 元数据 JSON. */
    void saveSyncedMeta(String agentType,
                         AgentMemoryDirectory.AgentMemoryScope scope,
                         String snapshotTimestamp) throws IOException {
        Path syncedPath = getSyncedJsonPath(agentType, scope);
        Path localMemDir = agentMemoryDirectory.getAgentMemoryDir(agentType, scope);
        // [AM-03] CC mkdir 在 try 外（:86）→ 失败显式传播（writeFile 才在 try 内 :88-92）
        Files.createDirectories(localMemDir);
        try {
            // [AM-06/OPD-R2-AM-06] CC jsonStringify({syncedFrom: ts})（:89）→ Jackson 序列化
            //   转义引号/反斜杠/控制字符（△-6：旧字符串拼接写坏 synced JSON）。
            Files.writeString(syncedPath, MAPPER.writeValueAsString(java.util.Map.of("syncedFrom", snapshotTimestamp)));
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentMemorySnapshot] 保存 synced 元数据失败: {} - {}", syncedPath, e.getMessage());
            }
        }
    }

    /**
     * 日期宽松解析（[FIX-AM REQ-M-19] + [AM-04/OPD-R2-AM-04] 对齐 CC :135
     * {@code new Date(...) > new Date(...)}）。
     *
     * <p>JS {@code new Date()} 解析宽松（ISO-8601 / 空格分隔 LocalDateTime / date-only / 其他可识别
     * 文本均可用）。旧 Java {@link java.time.Instant#parse} 仅严格 ISO-8601，失败回退 0L →
     * updatedAt 可解析 + syncedFrom 不可解析时误判 prompt-update（△-3）。
     *
     * <p>AM-04：<b>0L 回退移除</b>——不可解析返回 {@code null}，比较侧按 CC「任一 Invalid Date →
     * false → none」（偏向不打扰）；空格分隔格式按 <b>本地时区</b> 解析（CC new Date 缺时区按本地，
     * 旧 Java 按 UTC 偏移）；ISO Instant / date-only 按 UTC（JS 规范 date-only = UTC 零点）。
     *
     * @return 解析后的 epoch 毫秒；不可解析 → null
     */
    private static Long parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        // 1. ISO-8601 Instant（CC new Date(isoString) 常规路径）
        try {
            return java.time.Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {
            // 降级下一种格式
        }
        // 2. 空格分隔 LocalDateTime（无 'T'）· CC new Date 缺时区按本地时区（AM-04 时区基准对齐）
        try {
            return java.time.LocalDateTime.parse(s.replace(' ', 'T'))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 降级下一种格式
        }
        // 3. date-only（CC new Date('2026-08-05') 按 UTC 零点）
        try {
            return java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentMemorySnapshot] parseDate 宽松解析失败（按 Invalid Date 处理）: {}", s);
            }
            return null;
        }
    }

    /** [AM-04] CC {@code new Date(updatedAt) > new Date(syncedFrom)}（agentMemorySnapshot.ts:135）· 任一 Invalid Date → false. */
    private static boolean isNewerThan(String updatedAt, String syncedFrom) {
        Long updated = parseDate(updatedAt);
        Long synced = parseDate(syncedFrom);
        if (updated == null || synced == null) {
            return false;
        }
        return updated > synced;
    }
}
