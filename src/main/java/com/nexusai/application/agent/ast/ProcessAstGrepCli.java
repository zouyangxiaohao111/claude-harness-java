package com.nexusai.application.agent.ast;

import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ast-grep 子进程执行器 · 照 {@code RipgrepRunner} 的进程层取舍实现（不经 shell、UTF-8、上限、abort）。
 *
 * <h2>为什么不用 shell</h2>
 * {@link ProcessBuilder} 直接传 argv，模式里的引号 / 换行 / 中文原样到达子进程；走 shell 会在
 * Windows 上把引号语义搞成另一套（本项目已有同类事故面），故绝不拼接命令行字符串。
 *
 * <h2>上限与 kill</h2>
 * <ul>
 *   <li>stdout 行数上限 {@link #MAX_LINES}=100_000：超出即 {@code destroyForcibly()} 并置
 *       {@code outputTruncated=true}（保留已读行，调用方据此报「结果过多，收窄图案」）。</li>
 *   <li>stderr 上限 {@link #MAX_STDERR_LINES}=2_000：超出只丢弃并继续 drain（防管道写满阻塞子进程）。</li>
 * </ul>
 *
 * <h2>abort / timeout</h2>
 * 主线程每 {@link #POLL_MS} 轮询一次：abort 触发 → kill + {@code ABORTED}；超过 timeoutMs →
 * kill + {@code TIMEOUT}。子进程被 kill 后**不**用退出码判错（由调用方按 {@code outputTruncated}
 * / 异常类别决定）。
 */
public final class ProcessAstGrepCli implements AstGrepCli {

    private static final Logger log = LoggerFactory.getLogger(ProcessAstGrepCli.class);

    /** stdout 行数上限（JSONL 每条一个匹配；百万级会撑爆内存，故硬上限 + 截断标记）。 */
    public static final int MAX_LINES = 100_000;

    /** stderr 行数上限（只保留前 N 行做 warnings；剩余 drain 掉防阻塞）。 */
    static final int MAX_STDERR_LINES = 2_000;

    /** 轮询周期（abort/timeout 的响应粒度）。 */
    private static final long POLL_MS = 100L;

    @Override
    public Result run(List<String> args, Path workdir, AbortController abort, long timeoutMs) throws AstCliException {
        AbortController controller = abort != null ? abort : AbortController.NOOP;
        if (controller.isCancelled()) {
            throw new AstCliException(AstCliException.Kind.ABORTED, "ast-grep aborted before start", null);
        }

        Path bin;
        try {
            bin = AstGrepBinary.ensureBinary();
        } catch (IOException e) {
            throw new AstCliException(AstCliException.Kind.SPAWN, "ast-grep binary unavailable: " + e.getMessage(), e);
        }

        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(bin.toString());
        for (String arg : args) {
            command.add(escapeArg(arg));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        // cwd 只影响 ast-grep 的 sgconfig.yml 项目发现；所有文件路径参数都是绝对路径。
        if (workdir != null) {
            pb.directory(workdir.toFile());
        }
        if (log.isDebugEnabled()) {
            log.debug("ProcessAstGrepCli: 执行 ast-grep args={} cwd={} timeoutMs={}",
                args, workdir, timeoutMs);
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new AstCliException(AstCliException.Kind.SPAWN,
                "ast-grep failed to start: " + e.getMessage(), e);
        }

        AtomicBoolean overflow = new AtomicBoolean(false);
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        Thread outThread = drain(process.getInputStream(), stdout, MAX_LINES, overflow, "ast-grep-stdout");
        Thread errThread = drain(process.getErrorStream(), stderr, MAX_STDERR_LINES, null, "ast-grep-stderr");

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        boolean truncated = false;
        try {
            while (true) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    throw new AstCliException(AstCliException.Kind.TIMEOUT,
                        "ast-grep timed out after " + timeoutMs + " ms", null);
                }
                if (process.waitFor(Math.min(POLL_MS, remainingMs), TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (overflow.get()) {
                    truncated = true;
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    break;
                }
                if (controller.isCancelled()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    throw new AstCliException(AstCliException.Kind.ABORTED, "AstGrep aborted by user", null);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AstCliException(AstCliException.Kind.ABORTED, "AstGrep aborted by user", e);
        }

        joinQuietly(outThread);
        joinQuietly(errThread);

        int exitCode = process.isAlive() ? -1 : process.exitValue();
        if (log.isDebugEnabled()) {
            log.debug("ProcessAstGrepCli: 退出 code={} truncated={} stdoutLines={} stderrLines={}",
                exitCode, truncated, stdout.size(), stderr.size());
        }
        return new Result(exitCode, List.copyOf(stdout), List.copyOf(stderr), truncated);
    }

    /**
     * Windows 命令行引号转义（MSVC 规则）· 仅 Windows 生效，其它平台原样返回。
     *
     * <h3>为什么必须自己做</h3>
     * JDK 的 {@code ProcessBuilder} 在 Windows 上把参数<b>原样</b>拼进命令行（只在含空格/制表/引号时
     * 用一对引号包起来，<b>不转义内部引号</b>）。于是 {@code -p} 里带引号的模式（如
     * {@code System.out.println("hi")}）到达子进程时引号已被命令行解析器吃掉，变成
     * {@code System.out.println(hi)} ⇒ 匹配不到且不报错（真实冒烟实测）。
     * 这里按 MSDN「Parsing C++ Command-Line Arguments」把 {@code "} 编成 {@code \"}、
     * 并把紧邻引号的连续反斜杠加倍；JDK 之后是否再包外层引号都不影响解析结果
     * （引号内外的解码规则一致）。
     *
     * @param arg 原始参数
     * @return Windows 命令行安全形态（非 Windows 平台原样）
     */
    static String escapeArg(String arg) {
        if (arg == null || !PlatformKey.isWindows() || arg.isEmpty()) {
            return arg;
        }
        StringBuilder sb = new StringBuilder(arg.length() + 8);
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
                sb.append(c);
            } else if (c == '"') {
                // n 个反斜杠 + 引号 → 2n+1 个反斜杠 + 引号（解码回 n 个反斜杠 + 字面引号）
                for (int j = 0; j <= backslashes; j++) {
                    sb.append('\\');
                }
                sb.append('"');
                backslashes = 0;
            } else {
                backslashes = 0;
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 逐行读取一条管道（UTF-8，剥尾 {@code \r}）。
     *
     * @param cap      行数上限；超出后继续读取但丢弃（{@code overflow != null} 时置位）
     * @param overflow 仅 stdout 传入：超上限标记（主线程据此 kill）
     */
    private static Thread drain(InputStream in, List<String> sink, int cap,
                                AtomicBoolean overflow, String name) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    synchronized (sink) {
                        if (sink.size() >= cap) {
                            if (overflow != null) {
                                overflow.set(true);
                            }
                            continue;
                        }
                        sink.add(line);
                    }
                }
            } catch (IOException ignored) {
                // 进程被 kill / 流关闭 → 读取自然结束
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
