package com.nexusai.application.agent.tool;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件历史状态 · 对齐 CC {@code utils/fileHistory.ts:39-55} {@code FileHistoryState}.
 *
 * <p>CC 三字段契约（snake_case → Java camelCase）:
 * <table>
 *   <tr><th>Java 字段</th><th>CC 原名</th><th>CC 行号</th></tr>
 *   <tr><td>{@code snapshots}</td><td>{@code snapshots: FileHistorySnapshot[]}</td><td>fileHistory.ts:42</td></tr>
 *   <tr><td>{@code trackedFiles}</td><td>{@code trackedFiles: Set&lt;string&gt;}</td><td>fileHistory.ts:43</td></tr>
 *   <tr><td>{@code snapshotSequence}</td><td>{@code snapshotSequence: number}</td><td>fileHistory.ts:47</td></tr>
 * </table>
 *
 * <p>{@code snapshotSequence} 语义（CC fileHistory.ts:44-46 注释）：单调递增计数器，
 * 每次 {@code makeSnapshot} 都递增（即便旧快照被驱逐），供 useGitDiffStats 作为活动信号
 * （{@code snapshots.length} 到达 {@link #MAX_SNAPSHOTS} 后即封顶）。
 *
 * <p>旧版 {@code record(filePath, lastModified, version)} 是错误简化（不含 CC 的
 * snapshots/trackedFiles/snapshotSequence 三字段），本轮重写为 CC 真源形状。
 * Java 后端 {@code updateFileHistoryState} Consumer 恒 noop（CC forkedAgent.ts:432 实证），
 * 本 record 作为 UI transport 的类型契约 + {@link com.nexusai.application.agent.file.FileHistoryService}
 * 的内存态载体。
 */
public record FileHistoryState(
        // WHY: 文件历史快照列表（CC snapshots, fileHistory.ts:42），最后一条为 most recent
        List<FileHistorySnapshot> snapshots,
        // WHY: 被跟踪的文件路径集合（CC trackedFiles, fileHistory.ts:43），跨快照累积
        Set<String> trackedFiles,
        // WHY: 单调递增快照序号（CC snapshotSequence, fileHistory.ts:47），仅 makeSnapshot 递增
        long snapshotSequence
) {

    /** CC {@code MAX_SNAPSHOTS = 100}（fileHistory.ts:53）—— 快照列表容量上限，超限驱逐最旧。 */
    public static final int MAX_SNAPSHOTS = 100;

    /**
     * 单个文件历史快照 · 对齐 CC {@code FileHistorySnapshot}（fileHistory.ts:33-37）.
     *
     * <table>
     *   <tr><th>Java 字段</th><th>CC 原名</th><th>CC 行号</th></tr>
     *   <tr><td>{@code messageId}</td><td>{@code messageId: UUID}</td><td>fileHistory.ts:34</td></tr>
     *   <tr><td>{@code trackedFileBackups}</td><td>{@code trackedFileBackups: Record&lt;string, FileHistoryBackup&gt;}</td><td>fileHistory.ts:35</td></tr>
     *   <tr><td>{@code timestamp}</td><td>{@code timestamp: Date}</td><td>fileHistory.ts:36</td></tr>
     * </table>
     *
     * <p>偏离说明：CC {@code messageId} 为 {@code UUID}（assistant message uuid，供 rewind-to-message），
     * Java 用 {@code String} 承载 surrogate（toolUseId / sessionId，见 FileHistoryService）；CC
     * {@code timestamp: Date} → Java {@code Instant}（现代 Java 时间类型，语义等价）。
     */
    public record FileHistorySnapshot(
            String messageId,
            Map<String, FileHistoryBackup> trackedFileBackups,
            Instant timestamp
    ) {}

    /**
     * 单个文件备份 · 对齐 CC {@code FileHistoryBackup}（fileHistory.ts:27-31）.
     *
     * <table>
     *   <tr><th>Java 字段</th><th>CC 原名</th><th>CC 行号</th></tr>
     *   <tr><td>{@code backupFileName}</td><td>{@code backupFileName: string | null}</td><td>fileHistory.ts:28</td></tr>
     *   <tr><td>{@code version}</td><td>{@code version: number}</td><td>fileHistory.ts:29</td></tr>
     *   <tr><td>{@code backupTime}</td><td>{@code backupTime: Date}</td><td>fileHistory.ts:30</td></tr>
     * </table>
     *
     * <p>{@code backupFileName == null} 表示该版本文件不存在（新建/已删文件），CC fileHistory.ts:25
     * 注释 {@code null value means the file does not exist in this version}。CC {@code backupTime: Date}
     * → Java {@code Instant}。
     */
    public record FileHistoryBackup(
            String backupFileName,
            int version,
            Instant backupTime
    ) {}
}
