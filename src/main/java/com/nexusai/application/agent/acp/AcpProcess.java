package com.nexusai.application.agent.acp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ACP 子进程生命周期 · spawn / stderr 排空 / 协作式收尾 / 进程树强杀。
 *
 * <p><b>为什么单独一个类</b>：{@link AcpTransport} 既要在真进程上工作，也要能在单测里
 * 直接吃一对内存流（不启进程）。把「进程」从「帧传输」里拆出来，测试就不必依赖
 * {@code dsh} 或 JVM 子进程。
 *
 * <p><b>Windows 三件事</b>（照 {@code ExternalAgentRunner} 的既有结论，不自己发明）：
 * 裸命令名要先解析成绝对路径（{@code .cmd}/{@code .bat}）；杀进程树必须
 * {@code taskkill /T /F}；stdin/stdout 明确 UTF-8（⛔ 不照抄 {@code StdioMcpTransport}
 * 的平台默认字符集）。
 */
public final class AcpProcess {

    private static final Logger log = LoggerFactory.getLogger(AcpProcess.class);

    /** stderr 保留行数上限（只做诊断，不进模型上下文）。 */
    private static final int STDERR_MAX_LINES = 500;

    private AcpProcess() {
    }

    /**
     * 启动子进程。
     *
     * @param argv 完整 argv（含可执行文件绝对路径）
     * @param cwd  工作目录（null = 继承当前进程 cwd）
     * @param env  追加的环境变量（null = 只继承父进程环境）
     * @return 已启动进程；stderr 已由 daemon 线程开始排空
     */
    public static Started start(List<String> argv, Path cwd, Map<String, String> env) throws IOException {
        if (argv == null || argv.isEmpty()) {
            throw new AcpException("ACP 启动参数为空");
        }
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(argv))
                .directory(cwd != null ? cwd.toFile() : null)
                .redirectErrorStream(false);
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }
        Process process = pb.start();

        List<String> stderrBuf = new ArrayList<>();
        Thread stderrThread = new Thread(() -> drainStderr(process, stderrBuf), "acp-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        return new Started(process, stderrThread, stderrBuf);
    }

    /**
     * 协作式收尾 · 照 DSH {@code disposeAcpChild} 的阶梯：关 stdin（给子进程 flush 与
     * 退出自身子进程的窗口）→ 等 {@code eofGraceMs} → 还没退就杀整棵进程树。
     *
     * <p>⛔ 与一次性 CLI 相反：ACP 的 stdin 是协议通道，prompt 之前不能关。
     */
    public static void quiesce(Process process, long eofGraceMs) {
        if (process == null) {
            return;
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignore) {
            // 进程已退出 / 流已关闭
        }
        try {
            if (process.waitFor(Math.max(0L, eofGraceMs), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        killTree(process);
    }

    /** 杀整个进程树（Windows 必须 /T，否则子进程成孤儿）。幂等。 */
    public static void killTree(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            if (isWindows()) {
                new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(process.pid()))
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignore) {
            // 退化为下面的通用 destroy
        }
        try {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (Exception ignore) {
            process.destroyForcibly();
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void drainStderr(Process process, List<String> sink) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (sink) {
                    if (sink.size() < STDERR_MAX_LINES) {
                        sink.add(line);
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[acp-stderr] {}", line);
                }
            }
        } catch (IOException ignore) {
            // 进程已退出 / 流已关闭
        }
    }

    /** 已启动进程 + 其 stderr 缓冲区。 */
    public static final class Started {
        private final Process process;
        private final Thread stderrThread;
        private final List<String> stderrBuf;

        Started(Process process, Thread stderrThread, List<String> stderrBuf) {
            this.process = process;
            this.stderrThread = stderrThread;
            this.stderrBuf = stderrBuf;
        }

        public Process process() {
            return process;
        }

        public void awaitStderr(long ms) {
            try {
                stderrThread.join(Math.max(1L, ms));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public String stderrTail(int maxChars) {
            String joined;
            synchronized (stderrBuf) {
                joined = String.join("\n", stderrBuf);
            }
            if (maxChars > 0 && joined.length() > maxChars) {
                return joined.substring(joined.length() - maxChars);
            }
            return joined;
        }
    }
}
