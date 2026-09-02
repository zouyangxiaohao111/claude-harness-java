package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * IMP-E-4（OPD-CM5-E-06）· ConsolidationLock.recordConsolidation 测试。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：CC /dream skill 在 prompt 构建时乐观盖章
 * （dream.ts:32 {@code await recordConsolidation()}）—— 手动整合后锁 mtime=now（即
 * lastConsolidatedAt），自动合并的时间门（minHours）不会立刻重跑。recordConsolidation 必须：
 * <ol>
 *   <li>mkdir recursive + 写 PID body —— memory 目录可能尚不存在（手动 /dream 先于任何
 *       自动触发，consolidationLock.ts:132-134）；</li>
 *   <li>best-effort —— 任一失败仅日志不抛异常（否则 /dream 命令被锁文件 IO 炸断，
 *       consolidationLock.ts:135-139）。</li>
 * </ol>
 */
@DisplayName("[IMP-E-4] ConsolidationLock.recordConsolidation 手动 /dream 盖章")
class ConsolidationLockTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("recordConsolidation 写锁文件 body=当前 PID 且 mtime=now（consolidationLock.ts:130-134）")
    void recordConsolidation_writesLockWithPidBody() throws IOException {
        // WHY: /dream 手动整合后锁 mtime 即 lastConsolidatedAt —— 自动合并时间门不立刻重跑
        Path mem = tempDir.resolve("mem");
        Files.createDirectories(mem);
        ConsolidationLock lock = new ConsolidationLock(mem);

        lock.recordConsolidation();

        Path lockFile = mem.resolve(ConsolidationLock.LOCK_FILE);
        assertThat(lockFile).exists();
        assertThat(Files.readString(lockFile).trim())
            .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
        // 盖章后 readLastConsolidatedAt > 0（≈now）
        assertThat(lock.readLastConsolidatedAt()).isPositive();
    }

    @Test
    @DisplayName("recordConsolidation 在 memory 目录不存在时 mkdir recursive（consolidationLock.ts:132-133）")
    void recordConsolidation_createsMissingMemoryDir() throws IOException {
        // WHY: 手动 /dream 可能先于任何自动触发（CC 注释 "Memory dir may not exist yet"）
        Path mem = tempDir.resolve("mem"); // 不创建目录
        ConsolidationLock lock = new ConsolidationLock(mem);

        lock.recordConsolidation();

        assertThat(Files.isDirectory(mem)).isTrue();
        Path lockFile = mem.resolve(ConsolidationLock.LOCK_FILE);
        assertThat(lockFile).exists();
        assertThat(Files.readString(lockFile).trim())
            .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
    }

    @Test
    @DisplayName("recordConsolidation best-effort：父路径被文件占位 → 不抛异常仅日志（consolidationLock.ts:135-139）")
    void recordConsolidation_failureIsSilentBestEffort() throws IOException {
        // WHY: 盖章失败不得炸断 /dream 命令 —— CC catch → logForDebugging，不传播
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "i am a file"); // 占位为普通文件 → createDirectories 抛 IOException
        ConsolidationLock lock = new ConsolidationLock(blocker.resolve("mem"));

        assertThatCode(lock::recordConsolidation).doesNotThrowAnyException();

        // mkdir 先失败 → 未走到 writeFile，锁文件不落盘
        assertThat(blocker.resolve("mem").resolve(ConsolidationLock.LOCK_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("recordConsolidation 覆盖既有锁：body 改写为当前 PID（writeFile 截断 · consolidationLock.ts:134）")
    void recordConsolidation_overwritesExistingLock() throws IOException {
        // WHY: 重复手动 /dream 时锁 body 必须为最新持有者 PID（陈旧 PID 不误导 isProcessRunning 回收）
        Path mem = tempDir.resolve("mem");
        Files.createDirectories(mem);
        ConsolidationLock lock = new ConsolidationLock(mem);
        Files.writeString(mem.resolve(ConsolidationLock.LOCK_FILE), "12345");

        lock.recordConsolidation();

        assertThat(Files.readString(mem.resolve(ConsolidationLock.LOCK_FILE)).trim())
            .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
    }
}
