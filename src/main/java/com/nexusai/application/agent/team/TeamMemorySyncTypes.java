package com.nexusai.application.agent.team;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Team Memory Sync Types · 对齐 CC services/teamMemorySync/types.ts.
 *
 * <p>L1 语义: 仓库级 team memory sync API 的数据载体。
 *            - TeamMemoryContent: flat key-value 存储（CC TeamMemoryContentSchema）.
 *            - TeamMemoryData: 完整 GET /api/claude_code/team_memory 响应（CC TeamMemoryDataSchema）.
 *            - SkippedSecretFile: gitleaks 检测到的 secret 文件（CC SkippedSecretFile 类型）.
 *            - SyncState: 跨 sync 函数传递的纯数据载体（CC SyncState / createSyncState，index.ts:100-127）.
 *
 * <p>sync result 载体（FetchResult/PushResult/HashesResult/UploadResult）与结构化 413 解析
 * 内联于 {@link com.nexusai.application.agent.memory.TeamMemoryHttpClient}（生产事实载体，对齐 CC
 * index.ts 返回类型契约）；本文件不再重复建模 —— OPD-R2-TMS-02 裁决删除 5 个 0 消费 stub record
 * （D-11..D-15）。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: TeamMemoryData 6 字段;Content 2 字段;SkippedSecretFile 3 字段;结构化 413
 *       3 嵌套字段（HttpClient.upload 内联解析）.</li>
 *   <li><b>A2 Golden Trace</b>: 数据完整;success true/false;conflict 412;skippedSecrets 数组;
 *       checksum 版本号 lastModified 全到位.</li>
 *   <li><b>A3</b>: 状态: SUCCESS / NOT_MODIFIED (304) / EMPTY (404) / CONFLICT (412) / ERROR.</li>
 *   <li><b>A4</b>: serverErrorCode=null 默认;serverMaxEntries=null;checksum 必填 (sha256: 前缀);
 *       entryChecksums Optional (forward-compat);errorType 枚举值.</li>
 *   <li><b>A5</b>: 真实场景 — org admin push team memory → 412 conflict → 重试或合并;
 *       gitleaks 检测到 secret → skipRetry.</li>
 * </ul>
 *
 * <p>L3 升级: TS Zod schema → Java records (无运行时校验,但 type safety);
 *          TS `z.record(z.string(), z.string())` → Java Map&lt;String,String&gt;;
 *          TS union types → Java sealed-ish enum (字符串字面量).
 */
public final class TeamMemorySyncTypes {

    private TeamMemorySyncTypes() {}

    /** CC TeamMemoryContent — flat key-value 存储. */
    public record TeamMemoryContent(
        Map<String, String> entries,
        Map<String, String> entryChecksums
    ) {
        public static TeamMemoryContent empty() {
            return new TeamMemoryContent(new LinkedHashMap<>(), null);
        }
    }

    /** CC TeamMemoryData — 完整 GET 响应. */
    public record TeamMemoryData(
        String organizationId,
        String repo,
        long version,
        String lastModified,        // ISO 8601
        String checksum,             // sha256:<hex>
        TeamMemoryContent content
    ) {}

    /** CC SkippedSecretFile — gitleaks 检测. */
    public record SkippedSecretFile(
        String path,
        String ruleId,
        String label
    ) {}

    /**
     * SyncState 数据对象 · CC original: {@code SyncState}（index.ts:100-118）+ {@code createSyncState}
     * （index.ts:121-127）。由 watcher 每 session 创建一次，穿过所有 sync 函数（避免模块级可变状态，
     * 测试天然隔离）。
     *
     * <p><b>不是状态机</b>（DEL-M-16）：旧 TeamMemorySyncService.SyncState 是 IDLE→READING→…→DONE 的
     * 生命周期枚举，CC 无此语义。CC SyncState 是纯数据载体：
     * <ul>
     *   <li>{@code lastKnownChecksum} — 上次已知服务端 ETag（条件请求用）</li>
     *   <li>{@code serverChecksums} — 认为服务端持有的 per-key 内容 hash（pull 时来自服务端
     *       entryChecksums，push 成功后来自本地 hash；用于计算 delta）</li>
     *   <li>{@code serverMaxEntries} — 从结构化 413 学到的服务端 max_entries 上限；未学到前为 null
     *       （服务端上限按 org GB 可调，无正确客户端默认值）</li>
     * </ul>
     */
    public static final class SyncState {
        /** 上次已知服务端 checksum（ETag）· CC index.ts:101-103。 */
        public String lastKnownChecksum;
        /** 认为服务端持有的 per-key 内容 hash（sha256:hex）· CC index.ts:104-109。 */
        public final java.util.Map<String, String> serverChecksums;
        /** 从结构化 413 学到的服务端 max_entries 上限（未知为 null）· CC index.ts:110-118。 */
        public Integer serverMaxEntries;

        public SyncState(String lastKnownChecksum,
                         java.util.Map<String, String> serverChecksums,
                         Integer serverMaxEntries) {
            this.lastKnownChecksum = lastKnownChecksum;
            this.serverChecksums = serverChecksums;
            this.serverMaxEntries = serverMaxEntries;
        }

        /** 字段镜像（CC createSyncState：lastKnownChecksum=null, serverChecksums=new Map(), serverMaxEntries=null）。 */
        public static SyncState create() {
            return new SyncState(null, new java.util.LinkedHashMap<>(), null);
        }
    }

}
