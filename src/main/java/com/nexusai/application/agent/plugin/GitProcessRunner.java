package com.nexusai.application.agent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git 进程执行器 · 对齐 CC {@code utils/execFileNoThrow.ts:89} {@code execFileNoThrowWithCwd}.
 *
 * <p><b>L2 契约</b>（Session MPL2）：
 * <ul>
 *   <li><b>args 数组</b>（非 {@code sh -c} 字符串）——与 {@link ExecSyncWrapper} 的 shell 字符串区分；
 *       任何 git 命令都必须以 args 数组形态传给 git 可执行文件，规避 shell 注入/引号语义。</li>
 *   <li><b>cwd + env + timeout + destroyForcibly</b>：CC execa 的 timeout 命中后 SIGTERM kill；
 *       Java 等价 {@code waitFor(timeoutMs)} 失败 → {@link Process#destroyForcibly()}，
 *       {@code error} 字段含 {@code timed out}（对齐 CC enhanceGitPull/CloneErrorMessages 的
 *       {@code result.error?.includes('timed out')} 判定 :660/:910）。</li>
 *   <li><b>永不抛异常</b>：非零退出 / IOException / 超时 → {@link Result}（对齐 CC
 *       {@code reject:false} :execFileNoThrow.ts:128）。</li>
 *   <li><b>stdin 忽略</b>：CC execa {@code stdin:'ignore'} → Java redirectInput NUL/dev/null
 *       （配合 GIT_TERMINAL_PROMPT=0 防止 git 凭据交互卡死）。</li>
 * </ul>
 *
 * <p><b>git 路径解析</b>（CC {@code utils/git.ts:212-216} {@code gitExe = whichSync('git') || 'git'}）：
 * Windows 直调 {@code git.exe} 规避 {@code .cmd/.bat}（ProcessBuilder 不执行 .cmd）。
 */
public final class GitProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(GitProcessRunner.class);

    /** CC DEFAULT_PLUGIN_GIT_TIMEOUT_MS = 120 * 1000（marketplaceManager.ts:515）. */
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    /** 注入式 executor（测试 mock），对齐 ExecSyncWrapper.Executor 模式。 */
    public interface Executor {
        Result exec(List<String> args, String cwd, Map<String, String> env, long timeoutMs);
    }

    /** 命令结果 · CC {@code { stdout, stderr, code, error? }}（execFileNoThrow.ts:102-120）. */
    public record Result(int exitCode, String stdout, String stderr, String error) {
        public boolean ok() {
            return exitCode == 0;
        }

        /** error 兜底空串（CC 多处判 {@code error?.includes(...)}）。 */
        public String errorOrBlank() {
            return error == null ? "" : error;
        }
    }

    private final Executor executor;
    private final String gitExecutable;

    public GitProcessRunner() {
        this(ProcessExecutor.INSTANCE, resolveGitExecutable());
    }

    public GitProcessRunner(Executor executor, String gitExecutable) {
        this.executor = executor;
        this.gitExecutable = gitExecutable;
    }

    public String gitExecutable() {
        return gitExecutable;
    }

    /** 默认 timeout 执行（CC 各 git 调用统一 getPluginGitTimeoutMs）。 */
    public Result run(List<String> args, String cwd, Map<String, String> env) {
        return run(args, cwd, env, getPluginGitTimeoutMs());
    }

    public Result run(List<String> args, String cwd, Map<String, String> env, long timeoutMs) {
        List<String> fullArgs = new java.util.ArrayList<>(args.size() + 1);
        fullArgs.add(gitExecutable);
        fullArgs.addAll(args);
        if (log.isDebugEnabled()) {
            log.debug("git 执行：cwd={} args={} timeout={}ms", cwd, fullArgs, timeoutMs);
        }
        return executor.exec(fullArgs, cwd, env, timeoutMs);
    }

    /**
     * GIT_NO_PROMPT_ENV · CC marketplaceManager.ts:510-513
     * {@code { GIT_TERMINAL_PROMPT: '0', GIT_ASKPASS: '' }}。
     */
    public static Map<String, String> gitNoPromptEnv() {
        return Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_ASKPASS", "");
    }

    /**
     * 默认 git 超时 · CC getPluginGitTimeoutMs（:517-526）：
     * {@code CLAUDE_CODE_PLUGIN_GIT_TIMEOUT_MS} env 正整数 → 该值，否则 120s。
     */
    public static long getPluginGitTimeoutMs() {
        String envValue = System.getenv("CLAUDE_CODE_PLUGIN_GIT_TIMEOUT_MS");
        if (envValue != null) {
            try {
                int parsed = Integer.parseInt(envValue.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法值回退默认（CC parseInt 返回 NaN → 默认）
            }
        }
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * git 可执行文件路径解析 · CC gitExe（git.ts:212-216 {@code whichSync('git') || 'git'}）。
     * Windows 优先 {@code git.exe}（规避 {@code .cmd/.bat}：ProcessBuilder 不直接执行 .cmd）。
     */
    public static String resolveGitExecutable() {
        boolean win = File.separatorChar == '\\';
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] names = win ? new String[] {"git.exe", "git"} : new String[] {"git"};
            for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path d;
                try {
                    d = Paths.get(dir);
                } catch (Exception e) {
                    continue;
                }
                for (String name : names) {
                    Path cand = d.resolve(name);
                    if (Files.isRegularFile(cand)) {
                        return cand.toString();
                    }
                }
            }
        }
        return "git";
    }

    /** 从 git URL 中脱敏凭据（user:pass@）用于日志。 */
    public static String redactUrlCredentials(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("://([^:@/\\s]+):([^@\\s]+)@", "://$1:***@");
    }

    /**
     * 真实子进程 executor · CC execa（execFileNoThrow.ts:128-142）。
     *
     * <p>stdout/stderr 用两个 daemon 线程并行消费，防大输出 pipe 死锁；stdin 重定向 NUL/dev/null。
     */
    enum ProcessExecutor implements Executor {
        INSTANCE;

        private static final String DEV_NULL = File.separatorChar == '\\' ? "NUL" : "/dev/null";

        @Override
        public Result exec(List<String> args, String cwd, Map<String, String> env, long timeoutMs) {
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(args);
                if (cwd != null && !cwd.isBlank()) {
                    pb.directory(new File(cwd));
                }
                if (env != null && !env.isEmpty()) {
                    pb.environment().putAll(env);
                }
                pb.redirectInput(new File(DEV_NULL));
                final Process proc = pb.start();
                p = proc;

                CompletableFuture<String> stdoutF =
                    CompletableFuture.supplyAsync(() -> readAll(proc.getInputStream()));
                CompletableFuture<String> stderrF =
                    CompletableFuture.supplyAsync(() -> readAll(proc.getErrorStream()));

                boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    String out = stdoutF.join();
                    String err = stderrF.join();
                    return new Result(-1, out, err,
                        "Command timed out after " + timeoutMs + "ms");
                }
                int code = p.exitValue();
                return new Result(code, stdoutF.join(), stderrF.join(), null);
            } catch (IOException e) {
                return new Result(-1, "", "", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (p != null) {
                    p.destroyForcibly();
                }
                return new Result(-1, "", "", "Interrupted: " + e.getMessage());
            }
        }

        private static String readAll(InputStream in) {
            try (BufferedReader r =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            } catch (IOException e) {
                return "";
            }
        }
    }
}
