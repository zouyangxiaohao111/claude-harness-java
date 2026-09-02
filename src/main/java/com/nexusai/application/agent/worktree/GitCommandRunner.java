package com.nexusai.application.agent.worktree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * s18 git 子进程封装 — 对齐 CC execFileNoThrowWithCwd (worktree.ts:168-177 run_git).
 *
 * <p>L1 行为: 不抛异常 (CC 返回 {code, stdout, stderr}); 截断 stdout/stderr 防止 OOM;
 * GIT_TERMINAL_PROMPT=0 + GIT_ASKPASS='' 防挂起 (CC worktree.ts P2-9).
 */
public final class GitCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(GitCommandRunner.class);

    /** 单次 stdout/stderr 截断上限 (CC 默认 100KB, 防止大仓库 diff 撑爆内存) */
    public static final int MAX_OUTPUT_BYTES = 1024 * 1024; // 1 MiB

    /** 单次 git 命令超时 (CC 无显式 timeout, 用 60s 防止 hang) */
    public static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private GitCommandRunner() {
        // utility class
    }

    /**
     * git 命令执行结果 — 对齐 CC execFileNoThrowWithCwd 返回值.
     */
    public record Result(int exitCode, String stdout, String stderr) {

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public boolean hasStdout() {
            return stdout != null && !stdout.isEmpty();
        }
    }

    /**
     * 在指定目录下执行 git 子命令 — 对齐 CC execFileNoThrowWithCwd.
     *
     * @param cwd    工作目录 (通常是 git repo root 或 worktree 路径)
     * @param gitArgs git 子命令 + 参数 (例如 {@code ["worktree", "add", "-B", "wt/feat", "/path"]})
     * @return 执行结果, 不抛异常
     */
    public static Result run(Path cwd, String... gitArgs) {
        return run(cwd, DEFAULT_TIMEOUT_SECONDS, gitArgs);
    }

    public static Result run(Path cwd, long timeoutSeconds, String... gitArgs) {
        if (gitArgs == null || gitArgs.length == 0) {
            return new Result(-1, "", "no git args provided");
        }
        List<String> cmd = new ArrayList<>(gitArgs.length + 1);
        cmd.add("git");
        cmd.addAll(Arrays.asList(gitArgs));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        // 防 git 挂起等交互 (CC worktree.ts P2-9)
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();
            // 异步读 stdout/stderr 在 StringBuilder 中累积 (CC P2: 截断到 MAX_OUTPUT_BYTES)
            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();
            Thread stdoutReader = new Thread(() -> drainWithCap(process.getInputStream(), stdoutBuf),
                    "git-stdout-reader");
            stdoutReader.setDaemon(true);
            stdoutReader.start();
            Thread stderrReader = new Thread(() -> drainWithCap(process.getErrorStream(), stderrBuf),
                    "git-stderr-reader");
            stderrReader.setDaemon(true);
            stderrReader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("GitCommandRunner: git {} timed out after {}s in cwd={}",
                        String.join(" ", gitArgs), timeoutSeconds, cwd);
                stdoutReader.interrupt();
                stderrReader.interrupt();
                return new Result(-1, "", "git command timed out after " + timeoutSeconds + "s");
            }

            // 等 reader 线程完成读取剩余字节 (最多 5s, 避免 reader 卡住)
            stdoutReader.join(5000);
            stderrReader.join(5000);

            int exitCode = process.exitValue();
            String stdout = stdoutBuf.toString();
            String stderr = stderrBuf.toString();
            if (log.isDebugEnabled()) {
                log.debug("GitCommandRunner: git {} cwd={} exitCode={} stdout.length={} stderr.length={}",
                        String.join(" ", gitArgs), cwd, exitCode,
                        stdout.length(), stderr.length());
            }
            return new Result(exitCode, stdout, stderr);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("GitCommandRunner: git {} failed in cwd={}: {}",
                    String.join(" ", gitArgs), cwd, e.getMessage());
            return new Result(-1, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 把 stream 内容读入 buf, 最多累积 {@link #MAX_OUTPUT_BYTES} 字节 (防止 OOM).
     */
    private static void drainWithCap(java.io.InputStream stream, StringBuilder buf) {
        byte[] chunk = new byte[8192];
        int total = 0;
        try {
            int n;
            while ((n = stream.read(chunk)) != -1) {
                if (total + n > MAX_OUTPUT_BYTES) {
                    int allowed = MAX_OUTPUT_BYTES - total;
                    if (allowed > 0) {
                        buf.append(new String(chunk, 0, allowed, StandardCharsets.UTF_8));
                        total += allowed;
                    }
                    // 剩余字节丢弃
                    break;
                }
                buf.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                total += n;
            }
        } catch (IOException ignored) {
            // reader interrupted / stream closed — best-effort drain
        }
    }

    /**
     * 同步读 stream + 截断到 {@link #MAX_OUTPUT_BYTES}.
     * 注意: 此方法必须在 process 结束后调用 (即 waitFor 返回 true 后).
     */
    private static String readTruncated(java.io.InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_OUTPUT_BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}