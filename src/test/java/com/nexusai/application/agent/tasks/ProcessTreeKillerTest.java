package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.bash.ShellResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1-1 进程树杀 · 对齐 CC {@code #doKill → treeKill(pid,'SIGKILL')}（ShellCommand.ts:337-343）。
 *
 * <p><b>WHY（规则九 · 意图验证）</b>：CC spawn bash 用 {@code detached: true}（bashProvider.ts:75，
 * Shell.ts:334）独立进程组，treeKill 杀整棵进程树——含 {@code bash -c "sleep 999 & wait"} 的 sleep
 * 子进程。Java 旧实现 {@code p.destroyForcibly()} 只杀 bash 直接子进程，sleep 成孤儿。本测试锁定：
 * <ul>
 *   <li>killProcess 树杀后，bash 的<b>所有后代</b>（含 sleep）均不再存活（对齐 CC SIGKILL 语义）</li>
 *   <li>size-watchdog 触发路径同样树杀（bash 直连后代 sleep 一并终止）</li>
 * </ul>
 * 若 destroyForcibly 只杀 bash，sleep 进程存活 → 测试 RED（孤儿进程泄漏）。
 */
@DisplayName("[G1-1] 进程树杀（killProcess / size-watchdog 均树杀整棵，防 bash 子进程孤儿）")
class ProcessTreeKillerTest {

    @TempDir
    Path tempDir;

    /** 是否有可用 bash/zsh（Windows 走 Git Bash）· ShellResolver 找不到 → 跳过。 */
    private static boolean shellAvailable() {
        try {
            ShellResolver.resolveShell();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 进程 pid 是否仍存活（不存在/已死 → false） */
    private static boolean pidAlive(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        return h.map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * 枚举进程后代 pid 列表 · 提取为独立方法使调用方返回值为 effectively-final
     * （供 lambda 捕获）。P0 编译修复：原 try/catch 双赋值非 effectively-final，
     * 且 blank final 会被 javac 判 "might already be assigned"（JLS 16.2.15 保守
     * 分析），故收敛到 helper 返回值。枚举失败（进程已退出）→ 空列表（对齐原 catch 兜底）。
     *
     * @param pid 根进程 pid
     * @return 后代 pid 列表（枚举失败 → {@link List#of()}）
     */
    private static List<Long> listDescendants(long pid) {
        try {
            return ProcessHandle.of(pid)
                .map(ph -> ph.descendants().map(ProcessHandle::pid).toList())
                .orElse(List.of());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 简单轮询等待（mvn 测试类路径无 awaitility 依赖，手写兜底） */
    private static void awaitUntil(BooleanSupplier cond, String desc) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + desc, e);
            }
        }
        throw new AssertionError("等待超时: " + desc);
    }

    @Test
    @DisplayName("killProcess 杀整棵进程树：bash 后代 sleep 一并终止（不再孤儿）")
    void killProcess_killsWholeTree_sleepDescendantTerminated() {
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        LocalBashTaskRunner runner = new LocalBashTaskRunner();
        // bash -c "sleep 999 & wait" —— sleep 是 bash 的直接子进程（旧实现 destroyForcibly 会遗漏它）
        Thread t = new Thread(() -> {
            try {
                runner.execute("sleep 999 & wait", null);
            } catch (Exception ignored) {
                // 预期被树杀
            }
        }, "treekill-test");
        t.setDaemon(true);
        t.start();
        awaitUntil(runner::isProcessAlive, "bash 进程启动");
        long bashPid = runner.getPid();
        assertThat(bashPid).isPositive();

        // 杀前枚举后代（含 sleep）——先快照再杀根（destroyForcibly 后 descendants 可能已不可枚举）
        // P0 编译修复：提取为 helper 返回值（effectively-final，lambda 捕获安全）——
        //   原 try/catch 双赋值非 effectively-final，且 blank final 会被 javac 判
        //   "might already be assigned"（本文件 size-watchdog 用例的 descendantsBefore.stream() 曾报错）。
        List<Long> descendantsBefore = listDescendants(bashPid);
        assertThat(descendantsBefore).as("bash 应至少有 sleep 后代").isNotEmpty();

        runner.killProcess();

        // bash 根进程 + 全部后代均终止
        awaitUntil(() -> !pidAlive(bashPid), "bash 根进程终止");
        for (long childPid : descendantsBefore) {
            assertThat(pidAlive(childPid))
                .as("bash 后代 pid=%d 应被树杀（对齐 CC treeKill SIGKILL 整树语义）", childPid)
                .isFalse();
        }
    }

    @Test
    @DisplayName("size-watchdog 触发路径树杀：bash 后代 sleep 一并终止（超限杀整树）")
    void sizeWatchdog_killsWholeTree_sleepDescendantTerminated() {
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        Path out = tempDir.resolve("watchdog-tree.out");
        LocalBashTaskRunner runner = new LocalBashTaskRunner();
        // 写满小阈值输出后 sleep 保持存活 —— size-watchdog 触发后必须连 sleep 一起树杀
        // （stuck append loop 的派生子进程残留 = 磁盘打满防护失效的残留面）
        Thread t = new Thread(() -> {
            try {
                runner.execute(
                    "yes '0123456789abcdefghijklmnopqrstuvwxyz' | head -c 65536; sleep 999",
                    out.toString(), 1024);
            } catch (Exception ignored) {
                // 预期被 size-watchdog 树杀
            }
        }, "watchdog-tree");
        t.setDaemon(true);
        t.start();
        awaitUntil(runner::isProcessAlive, "bash 进程启动");
        long bashPid = runner.getPid();

        // P0 编译修复：提取为 helper 返回值（effectively-final，供下方 lambda
        //   descendantsBefore.stream() 捕获；原 try/catch 双赋值非 effectively-final，
        //   blank final 又被 javac 判 "might already be assigned"）。
        List<Long> descendantsBefore = listDescendants(bashPid);

        // 等 size-watchdog 触发（5s 间隔 + 小阈值）→ bash 被杀
        awaitUntil(() -> !pidAlive(bashPid), "size-watchdog 树杀 bash");
        // 短暂容忍后代终止传播（不同平台 kill 传播时序差异）
        awaitUntil(() -> descendantsBefore.stream().noneMatch(ProcessTreeKillerTest::pidAlive),
            "size-watchdog 树杀全部后代");
        for (long childPid : descendantsBefore) {
            assertThat(pidAlive(childPid))
                .as("size-watchdog 杀整树：bash 后代 pid=%d 应一并终止", childPid)
                .isFalse();
        }
        t.interrupt();
    }
}
