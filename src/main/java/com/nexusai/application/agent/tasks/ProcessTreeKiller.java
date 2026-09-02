package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 跨平台进程树杀工具 — 对齐 CC {@code #doKill} → {@code treeKill(pid, 'SIGKILL')}
 * （Open-ClaudeCode/src/utils/ShellCommand.ts:337-343）。
 *
 * <p><b>WHY（G1-1 · 规则九 · 意图验证）</b>：CC spawn bash 用 {@code detached: true}
 * （bashProvider.ts:75，Shell.ts:334）使 bash 成为独立进程组，{@code treeKill(pid,'SIGKILL')}
 * 杀整棵进程树——含 {@code bash -c "sleep 999 & wait"} 的 sleep 子进程（ShellCommand.ts:337-343）。
 * Java 旧实现 {@code Process.destroyForcibly()} 只杀 bash 直接子进程，sleep 成孤儿
 * （LocalBashTaskRunner.killProcess 旧 :173 / size-watchdog 旧 :257）。本工具对齐 treeKill
 * 的 SIGKILL 语义：
 * <ul>
 *   <li><b>Windows</b>：{@code taskkill /F /T /PID &lt;pid&gt;} 杀整树（tree-kill 包 Windows
 *       实现同款——强制 + 含子树）；失败回落 {@link #killTreeByDescendants}</li>
 *   <li><b>POSIX / 兜底</b>：先 {@code process.toHandle().descendants()} 枚举后代（自底向上逐个
 *       destroyForcibly），再杀根进程——<b>必须先枚举再杀根</b>（destroyForcibly 后
 *       descendants 可能已不可枚举）</li>
 * </ul>
 *
 * <p>SIGKILL 语义下被 kill 的进程退出码 = 128 + 9 = 137（CC {@code SIGKILL=137}，ShellCommand.ts:49；
 * Java {@link LocalBashTaskRunner#KILLED_FOR_SIZE_EXIT_CODE} 同值）。
 */
public final class ProcessTreeKiller {

    private static final Logger log = LoggerFactory.getLogger(ProcessTreeKiller.class);

    /** Windows 平台判定 · 等价 CC {@code getPlatform() === 'windows'}（platform.ts:18）。 */
    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Windows taskkill 等待终止的秒数上限（对齐 CC 同步 treeKill 的阻塞语义，防永久挂起）。 */
    private static final long TASKKILL_TIMEOUT_SECONDS = 5L;

    private ProcessTreeKiller() {
    }

    /**
     * 杀整棵进程树 · 对齐 CC {@code treeKill(pid, 'SIGKILL')}（ShellCommand.ts:340）。
     *
     * <p>Windows 主路径 taskkill /F /T /PID；失败（taskkill 不可用/超时）与 POSIX 均走
     * {@link #killTreeByDescendants} 兜底。
     *
     * @param process 待杀的根进程（bash）
     */
    public static void killTree(Process process) {
        if (process == null) {
            return;
        }
        if (IS_WINDOWS && killTreeWindows(process.pid())) {
            return;
        }
        killTreeByDescendants(process);
    }

    // ── Windows：taskkill /F /T /PID（对齐 tree-kill 包 Windows 实现）──

    /**
     * Windows 树杀 · {@code taskkill /F /T /PID &lt;pid&gt;}（/F 强制、/T 含子树）。
     *
     * @param pid 根进程 pid
     * @return true = taskkill 确认终止（exit 0）；false = 失败/超时（调用方回落 descendants）
     */
    private static boolean killTreeWindows(long pid) {
        log.info("ProcessTreeKiller: Windows taskkill /F /T 杀整树 pid={}", pid);
        try {
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("ProcessTreeKiller: taskkill 成功 pid={}", pid);
                }
                return true;
            }
            if (log.isDebugEnabled()) {
                log.debug("ProcessTreeKiller: taskkill 未确认终止 pid={} done={} exit={}，回落 descendants 兜底",
                    pid, done, p.exitValue());
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("ProcessTreeKiller: taskkill 失败 pid={} e={}，回落 descendants 兜底", pid, e.toString());
            }
        }
        return false;
    }

    // ── 跨平台 Java 兜底：先枚举后代（自底向上）再杀根 ──

    /**
     * 先 {@code process.toHandle().descendants()} 枚举后代（自底向上逐个 destroyForcibly），
     * 再杀根进程。对齐 CC {@code treeKill(pid,'SIGKILL')} 杀整树语义。
     *
     * <p><b>必须先枚举再杀根</b>：destroyForcibly 根进程后 descendants 可能已不可枚举
     * （Java 文档不保证 kill 后 descendants() 有效）。故先快照后代列表，逐个杀后代，
     * 最后杀根。
     *
     * @param process 根进程
     */
    private static void killTreeByDescendants(Process process) {
        List<ProcessHandle> descendants;
        try {
            descendants = process.toHandle().descendants().toList();
        } catch (Exception e) {
            // descendants() 本身失败（进程已退出）→ 退化为杀根
            descendants = List.of();
            if (log.isDebugEnabled()) {
                log.debug("ProcessTreeKiller: descendants 枚举失败 pid={} e={}，仅杀根", process.pid(), e.toString());
            }
        }
        for (ProcessHandle h : descendants) {
            if (h.isAlive()) {
                if (log.isDebugEnabled()) {
                    log.debug("ProcessTreeKiller: 杀后代 pid={}（子树成员）", h.pid());
                }
                h.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            if (log.isDebugEnabled()) {
                log.debug("ProcessTreeKiller: 杀根进程 pid={}", process.pid());
            }
            process.destroyForcibly();
        }
    }
}
