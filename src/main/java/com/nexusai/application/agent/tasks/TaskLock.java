package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 任务文件锁 · 对齐 CC proper-lockfile（tasks.ts:97-108 LOCK_OPTIONS）
 *
 * <p>使用 Java NIO FileLock 实现跨进程互斥访问。
 * 对齐 CC 的 LOCK_OPTIONS：
 * <ul>
 *   <li>retries: 30（最多重试 30 次）</li>
 *   <li>minTimeout: 5ms（最小等待时间）</li>
 *   <li>maxTimeout: 100ms（最大等待时间）</li>
 * </ul>
 *
 * <h2>两档锁粒度（对齐 CC tasks.ts 实际 lockfile.lock 目标）</h2>
 * <ul>
 *   <li><b>列表级锁</b>：锁文件 = {@code tasksDir/.lock}（{@link #withLock} /
 *       {@link #withLockAndReturn}），用于 createTask（CC tasks.ts:293）与
 *       resetTaskList（CC tasks.ts:154）。</li>
 *   <li><b>文件级锁</b>：锁文件 = 目标任务文件同名的 {@code .lock} 兄弟文件
 *       （{@code {taskId}.json.lock}，镜像 proper-lockfile 的 {@code ${file}.lock}，
 *       也是 CC lockfile.lock(taskPath) 对任务文件的真实锁目标；{@link #withFileLock} /
 *       {@link #withFileLockAndReturn}），用于 updateTask（CC tasks.ts:386）与
 *       claimTask（CC tasks.ts:566）。</li>
 * </ul>
 *
 * <p><b>OverlappingFileLockException 处理</b>：同 JVM 多线程对同一锁文件并发
 * {@code tryLock()} 会抛 {@link OverlappingFileLockException}（RuntimeException，
 * 不被 {@code catch (IOException)} 捕获），现行版本直接上抛导致不重试。此处将其
 * 视为「本次未获取锁」，走与超时/IO 失败一致的指数退避重试。
 *
 * <h2>使用方式</h2>
 * <pre>
 * // 列表级锁（createTask / resetTaskList）
 * TaskLock.withLock(tasksDir, () -> {
 *     // 临界区：读 HWM + 写任务文件
 * });
 * // 文件级锁（updateTask / claimTask）
 * TaskLock.withFileLock(taskPath, () -> {
 *     // 临界区：读写单个任务文件
 * });
 * </pre>
 *
 * @see TaskService
 */
public class TaskLock {

    private static final Logger log = LoggerFactory.getLogger(TaskLock.class);

    /**
     * 对齐 CC LOCK_OPTIONS（tasks.ts:102-108）
     */
    private static final int MAX_RETRIES = 30;
    private static final long MIN_TIMEOUT_MS = 5;
    private static final long MAX_TIMEOUT_MS = 100;

    private static final String LOCK_FILE_NAME = ".lock";

    /**
     * 获取任务列表级锁的锁文件路径（列表级锁，对齐 CC tasks.ts:504-506 getTaskListLockPath）
     */
    public static Path getLockPath(Path tasksDir) {
        return tasksDir.resolve(LOCK_FILE_NAME);
    }

    /**
     * 获取任务文件级锁的锁文件路径 · 镜像 proper-lockfile 的 {@code ${file}.lock}
     *
     * <p>目标文件 {@code {taskId}.json} 的锁文件 = 同名追加 {@code .lock} 的兄弟文件
     * {@code {taskId}.json.lock}。不锁任务文件本身，避免 Windows 下 deleteTask /
     * resetTaskList 对持锁任务文件 deleteIfExists 抛 AccessDenied 冲突。
     */
    public static Path getFileLockPath(Path targetPath) {
        return Paths.get(targetPath.toString() + ".lock");
    }

    /**
     * 确保列表级锁文件存在（对齐 CC tasks.ts:511-523 ensureTaskListLockFile）
     *
     * <p>并发线程同时创建锁文件时 {@code Files.createFile} 抛 FileAlreadyExistsException
     * （check-then-create 竞态）— 视为 EEXIST 正常继续（对齐 proper-lockfile mkdir 锁的
     * EEXIST 重试语义），不得上抛导致临界区丢失。
     */
    private static Path ensureLockFile(Path tasksDir) throws IOException {
        Path lockPath = getLockPath(tasksDir);
        Files.createDirectories(lockPath.getParent());
        try {
            Files.createFile(lockPath);
        } catch (FileAlreadyExistsException e) {
            // EEXIST：锁文件已存在（并发创建竞态），正常继续
        }
        return lockPath;
    }

    /**
     * 确保文件级锁文件存在
     *
     * <p>proper-lockfile 要求锁目标文件已存在，此处创建/复用它。
     *
     * <p>并发线程同时创建锁文件时 {@code Files.createFile} 抛 FileAlreadyExistsException
     * （check-then-create 竞态）— 视为 EEXIST 正常继续（对齐 proper-lockfile mkdir 锁的
     * EEXIST 重试语义），不得上抛导致临界区丢失（teammate mailbox 并发写实测 20 写丢 3）。
     */
    private static Path ensureFileLockFile(Path targetPath) throws IOException {
        Path lockPath = getFileLockPath(targetPath);
        Files.createDirectories(lockPath.getParent());
        try {
            Files.createFile(lockPath);
        } catch (FileAlreadyExistsException e) {
            // EEXIST：锁文件已存在（并发创建竞态），正常继续
        }
        return lockPath;
    }

    /**
     * 执行带锁的操作（列表级锁）· 对齐 CC lockfile.lock() + release()
     *
     * @param tasksDir 任务目录路径（锁文件 = tasksDir/.lock）
     * @param action   临界区操作
     * @throws LockAcquisitionException 获取锁失败
     */
    public static void withLock(Path tasksDir, Runnable action) {
        Path lockPath;
        try {
            lockPath = ensureLockFile(tasksDir);
        } catch (IOException e) {
            throw new LockAcquisitionException("Failed to create lock file: " + e.getMessage(), e);
        }
        withRetryLoop(lockPath, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带锁的操作并返回结果（列表级锁）· 对齐 CC lockfile.lock() + release()
     *
     * @param tasksDir 任务目录路径（锁文件 = tasksDir/.lock）
     * @param action   临界区操作（返回值）
     * @return 操作结果
     * @throws LockAcquisitionException 获取锁失败
     */
    public static <T> T withLockAndReturn(Path tasksDir, Supplier<T> action) {
        Path lockPath;
        try {
            lockPath = ensureLockFile(tasksDir);
        } catch (IOException e) {
            throw new LockAcquisitionException("Failed to create lock file: " + e.getMessage(), e);
        }
        return withRetryLoop(lockPath, action);
    }

    /**
     * 执行带锁的操作（文件级锁）· 对齐 CC updateTask/claimTask 的 lockfile.lock(taskPath)
     *
     * <p>锁文件 = 目标任务文件同名 {@code .lock} 兄弟文件（镜像 proper-lockfile）。
     *
     * @param targetPath 任务文件路径（{taskId}.json）
     * @param action     临界区操作
     * @throws LockAcquisitionException 获取锁失败
     */
    public static void withFileLock(Path targetPath, Runnable action) {
        Path lockPath;
        try {
            lockPath = ensureFileLockFile(targetPath);
        } catch (IOException e) {
            throw new LockAcquisitionException("Failed to create file lock: " + e.getMessage(), e);
        }
        withRetryLoop(lockPath, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带锁的操作并返回结果（文件级锁）· 对齐 CC updateTask/claimTask 的 lockfile.lock(taskPath)
     *
     * @param targetPath 任务文件路径（{taskId}.json）
     * @param action     临界区操作（返回值）
     * @return 操作结果
     * @throws LockAcquisitionException 获取锁失败
     */
    public static <T> T withFileLockAndReturn(Path targetPath, Supplier<T> action) {
        Path lockPath;
        try {
            lockPath = ensureFileLockFile(targetPath);
        } catch (IOException e) {
            throw new LockAcquisitionException("Failed to create file lock: " + e.getMessage(), e);
        }
        return withRetryLoop(lockPath, action);
    }

    /**
     * 指数退避重试获取锁 · 列表级锁与文件级锁共用同一重试循环
     *
     * <p>对齐 CC retries: { retries: 30, minTimeout: 5, maxTimeout: 100 }。
     * 同 JVM 多线程并发 {@code tryLock} 抛 {@link OverlappingFileLockException}
     * （RuntimeException）时视为本次未获取锁，走退避重试而非直接上抛。
     *
     * @param lockPath 锁文件路径
     * @param action   临界区操作（返回值）
     * @return 操作结果
     * @throws LockAcquisitionException 重试耗尽仍未获取锁
     */
    private static <T> T withRetryLoop(Path lockPath, Supplier<T> action) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock != null) {
                    try {
                        return action.get();
                    } finally {
                        lock.release();
                    }
                }
            } catch (IOException e) {
                log.warn("获取锁失败，第 {} 次尝试：{}", attempt + 1, e.getMessage());
            } catch (OverlappingFileLockException e) {
                // 同 JVM 并发 tryLock 抛 RuntimeException，视为锁争用，退避重试
                if (log.isDebugEnabled()) {
                    log.debug("同 JVM 重叠文件锁（OverlappingFileLockException），第 {} 次尝试：{}", attempt + 1, e.getMessage());
                }
            }

            // 指数退避：minTimeout * 2^attempt，但不超过 maxTimeout
            long delay = Math.min(MIN_TIMEOUT_MS * (1L << attempt), MAX_TIMEOUT_MS);
            try {
                TimeUnit.MILLISECONDS.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new LockAcquisitionException("Interrupted while waiting for lock", ie);
            }
        }

        throw new LockAcquisitionException(
            "Failed to acquire lock after " + MAX_RETRIES + " retries for lockPath=" + lockPath);
    }

    /**
     * 锁获取异常
     */
    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message) {
            super(message);
        }

        public LockAcquisitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
