package com.nexusai.application.agent.file;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.FileHistoryState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FileHistoryService 单测 · 对齐 CC fileHistory.ts fileHistoryTrackEdit 三阶段语义。
 *
 * <p>WHY 覆盖意图（CLAUDE.md 规则九）：
 * <ul>
 *   <li><b>trackEdit 幂等</b>：同文件二次 trackEdit 不得重算 v1 备份（防"二次编辑污染 pre-edit 内容"，
 *       CC fileHistory.ts:98-105 注释）。断言第二次调用后存储的仍是<b>同一对象引用</b>
 *       （idempotent 守卫提前 return，未重算 backupTime）。</li>
 *   <li><b>门控关闭零副作用</b>：fileCheckpointingEnabled=false 时 trackEdit/makeSnapshot 完全 no-op
 *       （CC fileHistory.ts:89-91 首行守卫）。</li>
 *   <li><b>新建文件备份</b>：ENOENT → {@code backupFileName=null}（CC fileHistory.ts:755-761 文件不存在标记）。</li>
 *   <li><b>makeSnapshot 序号递增</b>：每次 makeSnapshot 递增 snapshotSequence（CC fileHistory.ts:302），
 *       供 useGitDiffStats 活动信号。</li>
 * </ul>
 */
class FileHistoryServiceTest {

    @TempDir
    Path tempDir;

    /** createBackup 真落盘后须隔离 nexusai 自有根，防止备份写入真实 ~/.nexusai（测试污染）。 */
    @BeforeEach
    void setUpConfigDir() {
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
    }

    @AfterEach
    void tearDownConfigDir() {
        ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
        NexusaiPaths.setAppNameOverride(null);
    }

    @Test
    @DisplayName("trackEdit 幂等：同文件二次 trackEdit 不重算 v1 备份（防二次编辑污染 pre-edit 内容）")
    void trackEdit_idempotent_secondEditDoesNotRecomputeV1Backup() throws Exception {
        FileHistoryService svc = new FileHistoryService(() -> true);
        svc.makeSnapshot("msg-1");

        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "pre-edit content");
        String abs = file.toAbsolutePath().normalize().toString();

        svc.trackEdit(abs, "msg-1", "sess-x");
        FileHistoryState afterFirst = svc.currentState();
        FileHistoryState.FileHistoryBackup first =
            afterFirst.snapshots().get(0).trackedFileBackups().get(abs);
        assertThat(first).isNotNull();
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.backupFileName()).endsWith("@v1");

        // 模拟"编辑后再 trackEdit"（CC: 二次 trackEdit 会污染确定性 {hash}@v1 备份）
        Files.writeString(file, "post-edit content");
        svc.trackEdit(abs, "msg-1", "sess-x");

        FileHistoryState afterSecond = svc.currentState();
        FileHistoryState.FileHistoryBackup second =
            afterSecond.snapshots().get(0).trackedFileBackups().get(abs);
        // 关键：idempotent 守卫提前 return，未重算 → 仍是同一对象引用（backupTime 未变）
        assertThat(second).isSameAs(first);
        assertThat(afterSecond.trackedFiles()).containsExactly(abs);
    }

    @Test
    @DisplayName("门控关闭：fileCheckpointingEnabled=false → trackEdit/makeSnapshot 零副作用")
    void gateOff_zeroSideEffect() throws Exception {
        FileHistoryService svc = new FileHistoryService(() -> false);
        svc.makeSnapshot("msg-1");
        svc.trackEdit(tempDir.resolve("x.txt").toString(), "msg-1", "sess-x");

        FileHistoryState state = svc.currentState();
        assertThat(state.snapshots()).isEmpty();
        assertThat(state.trackedFiles()).isEmpty();
        assertThat(state.snapshotSequence()).isEqualTo(0L);
    }

    @Test
    @DisplayName("新建文件（ENOENT）trackEdit：backupFileName=null（文件不存在标记）")
    void trackEdit_newFile_backupFileNameNull() {
        FileHistoryService svc = new FileHistoryService(() -> true);
        svc.makeSnapshot("msg-1");

        String abs = tempDir.resolve("not-exist.txt").toAbsolutePath().normalize().toString();
        svc.trackEdit(abs, "msg-1", "sess-x");

        FileHistoryState state = svc.currentState();
        FileHistoryState.FileHistoryBackup backup =
            state.snapshots().get(0).trackedFileBackups().get(abs);
        assertThat(backup).isNotNull();
        assertThat(backup.backupFileName()).isNull();   // CC: null 表示该版本文件不存在
        assertThat(backup.version()).isEqualTo(1);
        assertThat(state.trackedFiles()).containsExactly(abs);
    }

    @Test
    @DisplayName("makeSnapshot：新建快照 + snapshotSequence 递增 + trackEdit 不回填序号")
    void makeSnapshot_incrementsSequence_trackEditDoesNot() throws Exception {
        FileHistoryService svc = new FileHistoryService(() -> true);
        svc.makeSnapshot("msg-1");
        svc.makeSnapshot("msg-2");
        assertThat(svc.currentState().snapshotSequence()).isEqualTo(2L);
        assertThat(svc.currentState().snapshots()).hasSize(2);

        Path file = tempDir.resolve("b.txt");
        Files.writeString(file, "content");
        svc.trackEdit(file.toAbsolutePath().normalize().toString(), "msg-2", "sess-x");
        // CC trackEdit 仅回填 mostRecent，不递增 snapshotSequence（fileHistory.ts:135-153）
        assertThat(svc.currentState().snapshotSequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("trackEdit 缺最近快照（loop 钩未调 makeSnapshot）：fail-loud 不抛、不产备份")
    void trackEdit_missingMostRecentSnapshot_noBackup() throws Exception {
        FileHistoryService svc = new FileHistoryService(() -> true);
        Path file = tempDir.resolve("c.txt");
        Files.writeString(file, "content");
        svc.trackEdit(file.toAbsolutePath().normalize().toString(), "msg-1", "sess-x");

        // CC fileHistory.ts:96-100: missing most recent snapshot → logError + return
        assertThat(svc.currentState().snapshots()).isEmpty();
        assertThat(svc.currentState().trackedFiles()).isEmpty();
    }

    @Test
    @DisplayName("createBackup 真落盘：备份文件写入 configDir/file-history/{sessionId}/{hash}@v1，内容=pre-edit、权限与源一致")
    void createBackup_persistsBackupFile_withPreEditContent() throws Exception {
        FileHistoryService svc = new FileHistoryService(() -> true);
        svc.makeSnapshot("msg-1");

        Path file = tempDir.resolve("backup-src.txt");
        Files.writeString(file, "pre-edit content");
        String abs = file.toAbsolutePath().normalize().toString();

        svc.trackEdit(abs, "msg-1", "sess-1");

        // 用 service 自算的 backupFileName 定位备份文件（避免测试重复哈希算法）
        FileHistoryState.FileHistoryBackup backup =
            svc.currentState().snapshots().get(0).trackedFileBackups().get(abs);
        assertThat(backup).isNotNull();
        assertThat(backup.backupFileName()).endsWith("@v1");

        Path backupPath = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "file-history", "sess-1")
                .resolve(backup.backupFileName());
        assertThat(backupPath).exists();
        assertThat(Files.readString(backupPath)).isEqualTo("pre-edit content");

        // 权限与源一致（CC chmod(backupPath, srcStats.mode)；Windows 无 POSIX 视图 → 跳过）
        try {
            Set<PosixFilePermission> srcPerms = Files.getPosixFilePermissions(file);
            Set<PosixFilePermission> backupPerms = Files.getPosixFilePermissions(backupPath);
            assertThat(backupPerms).isEqualTo(srcPerms);
        } catch (UnsupportedOperationException e) {
            // Windows：POSIX 权限视图不支持，跳过权限断言（CC chmod 在 Windows 亦受限）
        }
    }
}
