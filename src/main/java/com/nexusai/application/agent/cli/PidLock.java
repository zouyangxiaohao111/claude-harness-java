package com.nexusai.application.agent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PID-based version lock · 对齐 CC utils/nativeInstaller/pidLock.ts (434 行).
 *
 * <p>L1: lock 文件写入 PID + acquire/release;读回校验 PID 存活;
 *      2h mtime fallback (lock 文件超过 2h 视为过期);
 *      进程死时 (ProcessHandle.of(pid) empty) 自动清理.
 *
 * <p>L2 契约: 错误码 "INSTALL_IN_PROGRESS"; lock 文件后缀 .lock.
 */
public final class PidLock {

    private static final Logger log = LoggerFactory.getLogger(PidLock.class);

    /** Lock 目录中 .lock 文件超过此时间视为过期. */
    public static final long MAX_LOCK_AGE_MS = 2 * 60 * 60 * 1000L;

    /** 错误码常量 — 与 TS installer.ts 字面同形. */
    public static final String INSTALL_IN_PROGRESS = "INSTALL_IN_PROGRESS";

    private final Path locksDir;
    private final ConcurrentHashMap<String, ReentrantLock> inProcessLocks = new ConcurrentHashMap<>();

    public PidLock(Path locksDir) {
        this.locksDir = locksDir;
    }

    /** 获取 version 对应锁;抛 {@link InstallLockException} 表示锁被持有. */
    public AcquiredLock acquire(String version) {
        ReentrantLock lk = inProcessLocks.computeIfAbsent(version, v -> new ReentrantLock());
        lk.lock();
        try {
            Files.createDirectories(locksDir);
            Path lockPath = locksDir.resolve(version + ".lock");
            if (Files.exists(lockPath)) {
                if (isLockValid(lockPath)) {
                    throw new InstallLockException(INSTALL_IN_PROGRESS,
                        "another install in progress for version=" + version);
                }
                // 死锁 or 过期:清理
                log.warn("PidLock stale lock detected for version={} — cleaning", version);
                try {
                    Files.deleteIfExists(lockPath);
                } catch (IOException ioe) {
                    log.warn("delete stale lock failed: {}", ioe.getMessage());
                }
            }
            // 原子写入 PID (写 tmp + rename)
            long pid = ProcessHandle.current().pid();
            Path tmp = locksDir.resolve(version + ".lock.tmp");
            Files.write(tmp, Long.toString(pid).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, lockPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // 回读校验
            String content = Files.readString(lockPath, StandardCharsets.UTF_8).trim();
            if (!Long.toString(pid).equals(content)) {
                throw new InstallLockException(INSTALL_IN_PROGRESS,
                    "lock round-trip mismatch for version=" + version);
            }
            if (log.isInfoEnabled()) log.info("PidLock acquired version={} pid={}", version, pid);
            return new AcquiredLock(lockPath, version);
        } catch (IOException ioe) {
            lk.unlock();
            throw new InstallLockException("LOCK_IO_ERROR",
                "failed to acquire pid lock: " + ioe.getMessage());
        } catch (RuntimeException re) {
            lk.unlock();
            throw re;
        }
    }

    private boolean isLockValid(Path lockPath) throws IOException {
        Long pid = readPid(lockPath);
        if (pid == null) return false;
        if (ProcessHandle.of(pid).isPresent()) {
            return true; // PID 存活
        }
        // PID 已死 → 检查 mtime fallback
        FileTime ft = Files.getLastModifiedTime(lockPath);
        long ageMs = System.currentTimeMillis() - ft.toMillis();
        return ageMs < MAX_LOCK_AGE_MS;
    }

    private Long readPid(Path lockPath) {
        try {
            String s = Files.readString(lockPath, StandardCharsets.UTF_8).trim();
            return Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 释放锁;持有者调用一次. */
    public void release(AcquiredLock lk) {
        try {
            Files.deleteIfExists(lk.path());
        } catch (IOException ioe) {
            log.warn("release lock delete failed: {}", ioe.getMessage());
        }
        ReentrantLock rl = inProcessLocks.get(lk.version());
        if (rl != null && rl.isHeldByCurrentThread()) {
            rl.unlock();
        }
        if (log.isInfoEnabled()) log.info("PidLock released version={}", lk.version());
    }

    public record AcquiredLock(Path path, String version) {}

    public static class InstallLockException extends RuntimeException {
        private final String code;
        public InstallLockException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }
}
