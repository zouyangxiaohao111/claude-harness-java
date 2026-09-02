package com.nexusai.application.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

/**
 * 合并锁 · 对齐 CC {@code services/autoDream/consolidationLock.ts} 单 lock-mtime 语义。
 *
 * <p><b>L1 语义（CC consolidationLock.ts:1-5）</b>：锁文件 mtime 即 lastConsolidatedAt，
 * body 为持有者 PID。锁位于 memory 目录内（{@code getAutoMemPath()} 等价），随 git-root
 * 作用域 + override 可写。
 *
 * <p><b>L2 契约</b>：
 * <ul>
 *   <li><b>readLastConsolidatedAt</b>（consolidationLock.ts:29-36）—— 单 stat 读锁文件
 *       mtime；无锁返回 0。失败 catch → 0（CC fail-open，OPD-M-32 回归 CC 语义）。</li>
 *   <li><b>tryAcquireConsolidationLock</b>（consolidationLock.ts:46-84）—— stat+readFile
 *       读 mtime+PID；stale 窗口（1h，consolidationLock.ts:18）内且 PID 存活 → null（阻塞）；
 *       否则 mkdir+writeFile(pid)+re-read 校验 PID 一致（双 reclaimer 竞态 loser bail）；
 *       返回 priorMtime??0。PID 存活 = {@code ProcessHandle.of(pid).isPresent()}
 *       （CC isProcessRunning genericProcessUtils.ts:20-28 process.kill(pid,0) 等价）。</li>
 *   <li><b>rollbackConsolidationLock</b>（consolidationLock.ts:91-108）—— priorMtime==0
 *       → unlink（恢复无文件）；否则 writeFile('') + utimes 回退 mtime（清 PID body 防止
 *       自身存活进程误判为持有者）。rollback 失败仅日志（下次触发延迟到 minHours）。</li>
 *   <li><b>recordConsolidation</b>（consolidationLock.ts:130-140）—— 手动 /dream 乐观盖章：
 *       mkdir recursive + 写 PID body；任一失败仅 debug 日志（best-effort 不传播）。</li>
 * </ul>
 *
 * <p><b>L3 (Java idiom)</b>: TS async fs（stat/readFile/mkdir/writeFile/utimes/unlink）→
 * Java NIO 同步调用（同进程内单锁消费者，无并发竞态）。
 *
 * <p><b>recordConsolidation 已实现</b>（consolidationLock.ts:130-140 · {@link #recordConsolidation()}）：
 * OPD-CM5-E-06 推翻旧 OPD-M-30 裁决 —— CC /dream skill（skills/bundled/dream.ts:32
 * {@code await recordConsolidation()}）消费该函数，非死代码；Java 补齐写锁实现，调用方由
 * /dream 手动整合（REST 端点）与 AutoDreamConsolidator doDream 接线。
 */
public final class ConsolidationLock {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationLock.class);

    /** CC consolidationLock.ts:16 LOCK_FILE —— 锁文件常量名 */
    public static final String LOCK_FILE = ".consolidate-lock";

    /** CC consolidationLock.ts:18 HOLDER_STALE_MS —— 1 小时（PID 复用防护上限） */
    public static final long HOLDER_STALE_MS = 60L * 60L * 1000L;

    private final Path lockPath;

    public ConsolidationLock(Path memoryDir) {
        this.lockPath = memoryDir.resolve(LOCK_FILE);
    }

    /** 锁文件路径（测试/日志观察点）。 */
    public Path lockPath() {
        return lockPath;
    }

    /**
     * 读锁文件 mtime = lastConsolidatedAt · CC original: {@code readLastConsolidatedAt}
     * （consolidationLock.ts:29-36）。无锁 → 0；失败 catch → 0（CC fail-open）。
     */
    public long readLastConsolidatedAt() {
        try {
            return Files.getLastModifiedTime(lockPath).toMillis();
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] readLastConsolidatedAt 失败（无锁或读失败 → 0）: {}",
                    e.getMessage());
            }
            return 0L;
        }
    }

    /**
     * 获取合并锁 · CC original: {@code tryAcquireConsolidationLock}
     * （consolidationLock.ts:46-84）。
     *
     * @return 获取成功 → 获取前 mtime（供 rollback 回退，无锁=0）；被持有/竞态失败 → null
     */
    public Long tryAcquireConsolidationLock() {
        Long mtimeMs = null;
        Integer holderPid = null;
        try {
            mtimeMs = Files.getLastModifiedTime(lockPath).toMillis();
            String raw = Files.readString(lockPath, StandardCharsets.UTF_8);
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                try {
                    holderPid = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    // body 不可解析 → 视为无持有者，允许回收（CC parseInt 非有限 → undefined）
                    holderPid = null;
                }
            }
        } catch (IOException e) {
            // [G-80] 任一读失败（stat/readFile）→ mtimeMs/holderPid 均视为未定义（对齐 CC
            //   Promise.all([stat, readFile]) 任一失败 → catch → 两者 undefined → 无锁路径，
            //   consolidationLock.ts:52-58）。旧实现 stat 成功 + readString 抛 IOException 时
            //   mtimeMs 残留 → 返回旧 mtime（rollback 目标错误，△-4）；此处重置后下方
            //   write PID + re-read 校验照常执行，返回 mtimeMs ?? 0。
            mtimeMs = null;
            holderPid = null;
        }

        if (mtimeMs != null && (System.currentTimeMillis() - mtimeMs) < HOLDER_STALE_MS) {
            if (holderPid != null && isProcessRunning(holderPid)) {
                if (log.isDebugEnabled()) {
                    log.debug("[AutoDream] 锁被存活 PID {} 持有（mtime {}s 前），跳过合并",
                        holderPid, Math.round((System.currentTimeMillis() - mtimeMs) / 1000.0));
                }
                return null;
            }
            // 死 PID 或 body 不可解析 —— 回收
        }

        // memory 目录可能尚不存在（CC :70-71 mkdir recursive）
        Path parent = lockPath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                log.warn("[AutoDream] 创建 memory 目录失败，锁获取失败: {}", e.getMessage());
                return null;
            }
        }
        long myPid = ProcessHandle.current().pid();
        try {
            Files.writeString(lockPath, String.valueOf(myPid),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("[AutoDream] 写锁文件失败: {}", e.getMessage());
            return null;
        }

        // 两个 reclaimer 都写 → 后者 PID 胜出；loser 在 re-read 时退出（CC :74-81）
        try {
            String verify = Files.readString(lockPath, StandardCharsets.UTF_8).trim();
            if (verify.isEmpty() || Integer.parseInt(verify) != myPid) {
                if (log.isDebugEnabled()) {
                    log.debug("[AutoDream] 锁 re-read 校验失败（竞态 loser），放弃合并");
                }
                return null;
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }

        return mtimeMs != null ? mtimeMs : 0L;
    }

    /**
     * 合并失败后回退锁 · CC original: {@code rollbackConsolidationLock}
     * （consolidationLock.ts:91-108）。priorMtime==0 → unlink；否则写空 body + utimes
     * 回退 mtime。失败仅日志（下次触发延迟到 minHours）。
     *
     * <p><b>IMP-M-P2-1 亚秒精度修正</b>：CC {@code const t = priorMtime / 1000; utimes(path, t, t)}
     * （consolidationLock.ts:101-102）—— Node utimes 接收<b>浮点秒</b>，保留亚秒精度；
     * 旧 Java 实现 {@code long seconds = priorMtime / 1000} 整型截断丢失亚秒。改
     * {@code FileTime.fromMillis(priorMtime)} 精确回退（等价 CC 浮点 utimes，无秒截断）。
     *
     * @param priorMtime 获取前 mtime（0 = 获取前无锁文件）
     */
    public void rollbackConsolidationLock(long priorMtime) {
        try {
            if (priorMtime == 0L) {
                Files.deleteIfExists(lockPath);
                return;
            }
            Files.writeString(lockPath, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // CC utimes 浮点秒保留亚秒 → FileTime.fromMillis 精确回退（无秒截断）
            Files.setLastModifiedTime(lockPath, FileTime.fromMillis(priorMtime));
        } catch (IOException e) {
            log.warn("[AutoDream] rollback 失败（下次触发延迟到 minHours）: {}", e.getMessage());
        }
    }

    /**
     * PID 是否存活 · CC original: {@code isProcessRunning}
     * （genericProcessUtils.ts:20-28，process.kill(pid,0) 等价）。
     */
    private static boolean isProcessRunning(int pid) {
        if (pid <= 1) {
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * 手动 /dream 乐观盖章 · CC original: {@code recordConsolidation}
     * （consolidationLock.ts:130-140）。best-effort——在 prompt 构建时执行，无技能完成钩子。
     *
     * <p><b>OPD-CM5-E-06 实现</b>：recordConsolidation 非死代码（CC /dream skill 消费，
     * skills/bundled/dream.ts:32 {@code await recordConsolidation()}）。语义 =
     * {@code mkdir(getAutoMemPath(), { recursive: true })}（= 本锁父目录；手动 /dream 可能先于
     * 任何自动触发，memory 目录尚不存在）+ {@code writeFile(lockPath(), String(process.pid))}
     * （body=当前 PID）；任一失败仅 debug 日志（CC logForDebugging，不传播）。
     *
     * <p>与 {@link #tryAcquireConsolidationLock()} 的区别：tryAcquire 是自动合并的 lock-gate
     * （mkdir/写失败 → 阻断返回 null）；recordConsolidation 是手动盖章（失败静默，/dream 命令
     * 不被锁文件 IO 炸断）。
     */
    public void recordConsolidation() {
        try {
            Path parent = lockPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(lockPath, String.valueOf(ProcessHandle.current().pid()),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] recordConsolidation 写锁失败（best-effort 不传播）: {}",
                    e.getMessage());
            }
        }
    }
}
