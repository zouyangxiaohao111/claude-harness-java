package com.nexusai.application.agent.file;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.FileHistoryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 文件历史服务 · 对齐 CC {@code utils/fileHistory.ts} {@code fileHistoryTrackEdit} + {@code fileHistoryMakeSnapshot}.
 *
 * <p>持有 CC 形状 {@link FileHistoryState} 内存态（初始 {@code snapshots=[]} /
 * {@code trackedFiles=Set.of()} / {@code snapshotSequence=0}，对齐 CC main.tsx:2989-2991）。
 * 供 {@code EditFileTool} / {@code WriteFileTool} 在写盘前调用 {@link #trackEdit} 备份 pre-edit 内容。
 *
 * <h2>三阶段 trackEdit（对齐 CC fileHistory.ts:86-196）</h2>
 * <ol>
 *   <li><b>幂等 v1 检查</b>（Phase 1）：若 mostRecent 快照已含该 trackingPath 的备份 → return，
 *       防二次编辑污染确定性 {@code {hash}@v1} 备份（CC fileHistory.ts:98-105 注释）。</li>
 *   <li><b>createBackup</b>（Phase 2）：stat 源文件，ENOENT → {@code backupFileName=null}（新建文件标记）；
 *       否则计算内容哈希键控 {@code {pathHash}@v1} 备份文件名。</li>
 *   <li><b>回填</b>（Phase 3）：回填 mostRecent 快照的 {@code trackedFileBackups[trackingPath]} +
 *       {@code trackedFiles.add}（CC fileHistory.ts:120-157，不改 snapshotSequence）。</li>
 * </ol>
 *
 * <h2>createBackup 落盘（对齐 CC fileHistory.ts:748-789）</h2>
 * CC {@code createBackup} 会 {@code copyFile} 真实落盘到 {@code {configDir}/file-history/{sessionId}/{hash}@v1}
 * 备份文件（内容=pre-edit 内容、权限与源一致），支持跨重启 rewind。Java 端 {@link #createBackup} 已对齐：
 * {@code Files.copy} + 惰性 mkdir（{@code createDirectories}）+ {@code setPosixFilePermissions} 保留权限，
 * 备份目录键用 {@code ctx.sessionId()}（CC {@code getSessionId()} 语义）。
 *
 * <p>{@code fileHistoryEnabled()} 门控对齐 CC fileHistory.ts:63-71：{@code getGlobalConfig().fileCheckpointingEnabled !== false}。
 * Java 端以 {@link BooleanSupplier} 注入（生产接线读取 {@code ~/.nexusai.json} fileCheckpointingEnabled
 * 留后续，见 concerns），缺省 {@code () -> true}（CC 语义：未显式关闭即 enabled）。Java Web 端无
 * {@code CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING} 环境变量等价。
 */
@Component
public class FileHistoryService {

    private static final Logger log = LoggerFactory.getLogger(FileHistoryService.class);

    /** CC main.tsx:2989-2991 初始状态：snapshots=[] / trackedFiles=Set() / snapshotSequence=0. */
    private volatile FileHistoryState state =
        new FileHistoryState(List.of(), Set.of(), 0L);

    /**
     * fileCheckpointingEnabled !== false 的读取通道 · 对齐 CC fileHistory.ts:64
     * {@code getGlobalConfig().fileCheckpointingEnabled !== false}。缺省 {@code () -> true}
     * （未显式关闭即 enabled，CC 语义）。生产读取 {@code ~/.nexusai.json} 的接线留后续。
     */
    private final BooleanSupplier fileCheckpointingEnabledSupplier;

    public FileHistoryService() {
        this(null);
    }

    public FileHistoryService(BooleanSupplier fileCheckpointingEnabledSupplier) {
        this.fileCheckpointingEnabledSupplier = fileCheckpointingEnabledSupplier == null
            ? () -> true
            : fileCheckpointingEnabledSupplier;
    }

    /** CC {@code fileHistoryEnabled()}（fileHistory.ts:63-71）— 门控是否开启文件历史。 */
    public boolean fileHistoryEnabled() {
        return fileCheckpointingEnabledSupplier.getAsBoolean();
    }

    /** 当前内存态快照 · 供 UI transport 读取（CC useFileHistory 等价）。 */
    public FileHistoryState currentState() {
        return state;
    }

    /**
     * 编辑前备份 · 对齐 CC {@code fileHistoryTrackEdit(updateFileHistoryState, filePath, messageId)}
     * （fileHistory.ts:86-196）三阶段，见类级 JavaDoc。
     *
     * <p>必须在文件被实际写入前调用（备份 pre-edit 内容）。mostRecent 快照缺失时
     * （loop 钩未调 {@link #makeSnapshot}）fail-loud warn 并 return，与 CC
     * {@code logError('FileHistory: Missing most recent snapshot')} 同构（CC fileHistory.ts:108-112）。
     * [FIX-C] 生产接线已落地（{@code LlmAgentLoop.doRun} 每轮 turn 边界调用 {@link #makeSnapshot}），
     * 故此 warn 现仅作防御性保护（对齐 CC 保留同一 guard + {@code tengu_file_history_track_edit_failed}
     * telemetry）：一个正常 run() 必先建快照，仅 loop 外直接调工具才会触发。
     *
     * @param filePath  编辑目标文件绝对路径
     * @param messageId 关联消息 ID（CC parentMessage.uuid surrogate；Java 用 toolUseId/sessionId）
     * @param sessionId 会话 UUID（CC resolveBackupPath 的 getSessionId() 语义，备份目录键；非 messageId surrogate）
     */
    public synchronized void trackEdit(String filePath, String messageId, String sessionId) {
        if (!fileHistoryEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("FileHistoryService: 门控关闭, 跳过 trackEdit: {}", filePath);
            }
            return;
        }
        String trackingPath = normalizeTrackingPath(filePath);

        // Phase 1: 幂等检查 —— mostRecent 已跟踪该文件则 return（CC fileHistory.ts:104-108）
        FileHistoryState captured = state;
        if (captured.snapshots().isEmpty()) {
            // CC fileHistory.ts:108-112: missing most recent snapshot → logError + return
            log.warn("FileHistoryService: 缺失最近快照, 无法 trackEdit（需 loop 钩先调 makeSnapshot）: {}", trackingPath);
            return;
        }
        FileHistoryState.FileHistorySnapshot mostRecent =
            captured.snapshots().get(captured.snapshots().size() - 1);
        if (mostRecent.trackedFileBackups().containsKey(trackingPath)) {
            if (log.isDebugEnabled()) {
                log.debug("FileHistoryService: 最近快照已跟踪该文件, 幂等跳过（防 v1 二次编辑污染）: {}", trackingPath);
            }
            return;
        }

        // Phase 2: 异步备份（Java 同步执行，确定性流程规则五）
        FileHistoryState.FileHistoryBackup backup;
        try {
            backup = createBackup(filePath, 1, sessionId);
        } catch (Exception e) {
            log.error("FileHistoryService: 备份失败, 跳过 trackEdit: {} cause={}", filePath, e.toString());
            return;
        }
        boolean isAddingFile = backup.backupFileName() == null;

        // Phase 3: 回填（CC fileHistory.ts:120-157）。synchronized 下无并发竞争，保留 re-check 结构对齐 CC。
        FileHistoryState fresh = state;
        if (fresh.snapshots().isEmpty()) {
            return;
        }
        FileHistoryState.FileHistorySnapshot mostRecentSnapshot =
            fresh.snapshots().get(fresh.snapshots().size() - 1);
        if (mostRecentSnapshot.trackedFileBackups().containsKey(trackingPath)) {
            return;
        }

        Set<String> updatedTrackedFiles = new LinkedHashSet<>(fresh.trackedFiles());
        updatedTrackedFiles.add(trackingPath);
        Map<String, FileHistoryState.FileHistoryBackup> updatedTrackedFileBackups =
            new HashMap<>(mostRecentSnapshot.trackedFileBackups());
        updatedTrackedFileBackups.put(trackingPath, backup);
        FileHistoryState.FileHistorySnapshot updatedMostRecentSnapshot =
            new FileHistoryState.FileHistorySnapshot(
                mostRecentSnapshot.messageId(), updatedTrackedFileBackups, mostRecentSnapshot.timestamp());

        List<FileHistoryState.FileHistorySnapshot> updatedSnapshots =
            new ArrayList<>(fresh.snapshots());
        updatedSnapshots.set(updatedSnapshots.size() - 1, updatedMostRecentSnapshot);
        // 对齐 CC fileHistory.ts:135-153：trackEdit 不回填 snapshotSequence（仅 makeSnapshot 递增）
        state = new FileHistoryState(updatedSnapshots, updatedTrackedFiles, fresh.snapshotSequence());

        log.info("FileHistoryService: 已跟踪文件修改 {} isNewFile={} version={}",
            trackingPath, isAddingFile, backup.version());
    }

    /**
     * 新建文件历史快照 · 对齐 CC {@code fileHistoryMakeSnapshot}（fileHistory.ts:197-334）
     * 的 Phase 3 提交（新 snapshot + snapshotSequence 递增 + MAX_SNAPSHOTS 封顶）。
     *
     * <p>[FIX-C] 生产接线已落地：{@code LlmAgentLoop.doRun} 在每轮 user turn 边界（用户 prompt
     * 入队后、{@code queryLoop(...)} 前）调用本方法，对齐 CC QueryEngine.ts:645
     * {@code messagesFromUserInput.forEach(m => fileHistoryMakeSnapshot(..., m.uuid))}
     * （另见 REPL.tsx:3094 / handlePromptSubmit.ts:525-539 两处独立 turn 边界快照点）。
     * 偏离声明：CC Phase 2（备份所有已跟踪且已变更文件）本轮未实现——rewind/持久化整合候选，
     * 本期仅建空快照 + 从 mostRecent 继承已跟踪文件备份（CC fileHistory.ts:292-296 继承语义）。
     *
     * @param messageId 关联消息 ID（CC message.uuid surrogate）
     */
    public synchronized void makeSnapshot(String messageId) {
        if (!fileHistoryEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("FileHistoryService: 门控关闭, 跳过 makeSnapshot");
            }
            return;
        }
        FileHistoryState captured = state;

        // CC fileHistory.ts:292-296：继承 mostRecent 已跟踪文件备份到新快照
        Map<String, FileHistoryState.FileHistoryBackup> inherited = new HashMap<>();
        if (!captured.snapshots().isEmpty()) {
            FileHistoryState.FileHistorySnapshot last =
                captured.snapshots().get(captured.snapshots().size() - 1);
            for (String trackingPath : captured.trackedFiles()) {
                FileHistoryState.FileHistoryBackup b = last.trackedFileBackups().get(trackingPath);
                if (b != null) {
                    inherited.put(trackingPath, b);
                }
            }
        }

        FileHistoryState.FileHistorySnapshot newSnapshot =
            new FileHistoryState.FileHistorySnapshot(messageId, inherited, Instant.now());

        List<FileHistoryState.FileHistorySnapshot> all = new ArrayList<>(captured.snapshots());
        all.add(newSnapshot);
        // CC fileHistory.ts:309-310：超 MAX_SNAPSHOTS 驱逐最旧
        if (all.size() > FileHistoryState.MAX_SNAPSHOTS) {
            all = new ArrayList<>(all.subList(all.size() - FileHistoryState.MAX_SNAPSHOTS, all.size()));
        }
        state = new FileHistoryState(all, captured.trackedFiles(), captured.snapshotSequence() + 1);
        if (log.isInfoEnabled()) {
            log.info("FileHistoryService: 新建文件历史快照 messageId={} snapshotSequence={} snapshots={}",
                messageId, state.snapshotSequence(), state.snapshots().size());
        }
    }

    /**
     * 备份单个文件 · 对齐 CC {@code createBackup(filePath, version)}（fileHistory.ts:748-789）。
     *
     * <p>CC 真源落盘语义（fileHistory.ts:756-797）：
     * <ol>
     *   <li>{@code backupFileName = getBackupFileName(filePath, version)}（:756）</li>
     *   <li>{@code backupPath = resolveBackupPath(backupFileName)}（:757，sessionId 目录键）</li>
     *   <li>stat 源文件：ENOENT → {@code backupFileName=null} 备份（新建/已删标记 :767-768），
     *       其余 IO 异常 rethrow（CC 只特判 ENOENT，:770）</li>
     *   <li>copyFile 落盘 + 惰性 mkdir（:778-782：先 copy，ENOENT → mkdir recursive → 重试 copy）</li>
     *   <li>chmod 保留权限（:786 {@code chmod(backupPath, srcStats.mode)}，Windows 无 POSIX 视图 → 降级）</li>
     * </ol>
     *
     * @param sessionId 会话 UUID（CC resolveBackupPath 的 getSessionId() 语义，备份目录键）
     */
    private FileHistoryState.FileHistoryBackup createBackup(String filePath, int version, String sessionId) throws IOException {
        if (filePath == null) {
            // CC fileHistory.ts:752-754: filePath === null → null 备份（无源文件）
            return new FileHistoryState.FileHistoryBackup(null, version, Instant.now());
        }
        Path src = Path.of(filePath);
        String backupFileName = getBackupFileName(filePath, version);
        Path backupPath = resolveBackupPath(backupFileName, sessionId);

        // stat 源文件：ENOENT → null 备份，其余 IO 异常 rethrow（CC fileHistory.ts:763-771 只特判 ENOENT）
        long srcSize;
        try {
            srcSize = Files.readAttributes(src, BasicFileAttributes.class).size();
        } catch (NoSuchFileException e) {
            // CC fileHistory.ts:767-768: ENOENT → 文件不存在标记（null 备份）
            return new FileHistoryState.FileHistoryBackup(null, version, Instant.now());
        }

        // copyFile 落盘 + 惰性 mkdir（CC fileHistory.ts:777-783）
        try {
            Files.copy(src, backupPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException e) {
            // backup 目录缺失 → mkdir recursive + 重试 copy（CC fileHistory.ts:781-782）
            Files.createDirectories(backupPath.getParent());
            Files.copy(src, backupPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // chmod 保留权限（CC fileHistory.ts:786 chmod(backupPath, srcStats.mode)；Windows 无 POSIX 视图 → 降级）
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(src);
            Files.setPosixFilePermissions(backupPath, perms);
        } catch (UnsupportedOperationException e) {
            if (log.isDebugEnabled()) {
                log.debug("FileHistoryService: 平台不支持 POSIX 权限, chmod 降级跳过: {}", backupPath);
            }
        }

        // CC fileHistory.ts:788-791 logEvent('tengu_file_history_backup_file_created') → slf4j 数据流日志替代
        log.info("FileHistoryService: 备份文件已落盘 {} → {} version={} fileSize={}",
            src, backupPath, version, srcSize);

        return new FileHistoryState.FileHistoryBackup(backupFileName, version, Instant.now());
    }

    /**
     * 备份路径 · 对齐 CC {@code resolveBackupPath(backupFileName, sessionId)}（fileHistory.ts:733-741）：
     * {@code join(getClaudeConfigHomeDir(), 'file-history', sessionId || getSessionId(), backupFileName)}；
     * Java 写入基址经 {@link NexusaiPaths#getAppConfigHomeDir()}（决策 D1 自有根）。
     *
     * <p>Java 无全局 {@code getSessionId()}（CC state.ts 会话状态），sessionId 由调用方
     * （{@code ctx.sessionId()}）显式传入（非 messageId surrogate，见 trackEdit JavaDoc）。
     */
    private Path resolveBackupPath(String backupFileName, String sessionId) {
        return Path.of(NexusaiPaths.getAppConfigHomeDir(), "file-history", sessionId, backupFileName);
    }

    /**
     * 备份文件名 · 对齐 CC {@code getBackupFileName(filePath, version)}（fileHistory.ts:725-731）：
     * {@code sha256(filePath).hex.slice(0,16) + '@v' + version}。键控对象是<b>文件路径</b>
     * （非内容），确定性幂等 v1。
     */
    private String getBackupFileName(String filePath, int version) {
        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(filePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {   // 8 bytes = 16 hex chars（CC .slice(0,16)）
                sb.append(String.format("%02x", digest[i]));
            }
            hash = sb.toString();
        } catch (Exception e) {
            hash = Integer.toHexString(filePath.hashCode());
        }
        return hash + "@v" + version;
    }

    /**
     * 归一化 trackingPath · 对齐 CC {@code maybeShortenFilePath}（fileHistory.ts:867-875）
     * 的绝对路径语义。偏离：不做 cwd 相对缩短（Java 端统一用归一化绝对路径作 key，与
     * Edit/Write 工具的 {@code file.toAbsolutePath().normalize().toString()} 一致）。
     */
    private String normalizeTrackingPath(String filePath) {
        try {
            return Path.of(filePath).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return filePath;
        }
    }
}
