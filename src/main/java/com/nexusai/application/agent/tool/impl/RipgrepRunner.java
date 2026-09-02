package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * ripgrep 子进程调用器 · 严格对齐 CC {@code utils/ripgrep.ts} 真源
 * （{@code Open-ClaudeCode/src/utils/ripgrep.ts}，GrepTool 引入真实 rg 引擎，G13⑥ / A6）。
 *
 * <p><b>WHY（替换 Java 近似实现）</b>：旧 GrepTool 用 {@code Files.walk} + Java {@code Pattern}
 * 自建搜索（正则方言 / glob 语义 / VCS 排除均偏离 CC）。本类以 ProcessBuilder 调用真实 rg
 * 二进制，镜像 CC {@code ripGrep}（ripgrep.ts:345-463）参数管线：
 * <ul>
 *   <li><b>二进制打包</b> —— CC {@code getRipgrepConfig}（ripgrep.ts:31-65）从
 *       {@code vendor/ripgrep/{os}-{arch}/rg[.exe]} 解析；Java 从 classpath
 *       {@code rg/{os}-{arch}/rg[.exe]} 提取（win32→rg.exe；linux/darwin→rg）到临时缓存目录。</li>
 *   <li><b>超时</b> —— CC :128-133：WSL 60s / 其余 20s，超时抛 {@link RipgrepTimeoutError}
 *       （CC :98-106 RipgrepTimeoutError 等价）。</li>
 *   <li><b>code=1 空结果</b> —— CC :376-380：退出码 1 = 无匹配 → 空列表。</li>
 *   <li><b>MAX_BUFFER 20MB</b> —— CC :80 MAX_BUFFER_SIZE=20_000_000：stdout 超限截断，
 *       部分结果丢最后一行（:428-430）。</li>
 *   <li><b>EAGAIN 单线程重试</b> —— CC :87-92/:390-409：stderr 含 "os error 11" 或
 *       "Resource temporarily unavailable" 时用 {@code -j 1} 重试一次。</li>
 *   <li><b>abort 接线</b> —— CC :139 signal 选项：取消时 kill 子进程，返回部分结果
 *       （无结果时抛 {@link RipgrepTimeoutError}，对齐 CC isTimeout 含 ABORT_ERR :413-416）。</li>
 *   <li><b>CR 剥离</b> —— CC :370 {@code line.replace(/\r$/, '')}：Windows 行尾兼容。</li>
 * </ul>
 *
 * <p><b>二进制资源</b>：6 平台二进制（arm64-darwin/arm64-linux/arm64-win32/x64-darwin/x64-linux/
 * x64-win32）复制自 CC {@code package/vendor/ripgrep/}（rg 14.1.1），打包进
 * {@code src/main/resources/rg/}。运行时按 {@code os.name}/{@code os.arch} 解析对应平台，
 * 首次使用从 classpath 提取到 {@code ${java.io.tmpdir}/nexusai-rg/{version}/{os}-{arch}/} 缓存
 * （Spring Boot fat-jar 内资源不可直接执行，须先落盘；POSIX 设置可执行位）。
 */
public final class RipgrepRunner {

    private static final Logger log = LoggerFactory.getLogger(RipgrepRunner.class);

    /** CC ripgrep.ts:80 MAX_BUFFER_SIZE = 20MB。 */
    static final int MAX_BUFFER_SIZE = 20_000_000;

    /** CC ripgrep.ts:130 默认超时 20s（WSL 60s）。 */
    static final int DEFAULT_TIMEOUT_MS = 20_000;
    static final int WSL_TIMEOUT_MS = 60_000;

    /** rg 版本（缓存目录版本隔离 · 对齐 CC MACRO.VERSION 语义）。 */
    static final String RG_VERSION = "14.1.1";

    /** 缓存二进制路径（进程内 memoize，double-checked locking）。 */
    private static volatile Path cachedBinary;

    /** 自定义超时异常 · 对齐 CC {@code RipgrepTimeoutError}（ripgrep.ts:98-106）。 */
    public static final class RipgrepTimeoutError extends Exception {
        private final List<String> partialResults;

        public RipgrepTimeoutError(String message, List<String> partialResults) {
            super(message);
            this.partialResults = partialResults;
        }

        /** 超时前已返回的部分结果（对齐 CC partialResults 字段）。 */
        public List<String> partialResults() {
            return partialResults;
        }
    }

    /**
     * [A6] 执行 ripgrep · 对齐 CC {@code ripGrep}（ripgrep.ts:345-463）。
     *
     * @param args            rg 参数（不含 target；GrepTool 按 GrepTool.ts:330-441 组装）
     * @param target          搜索根路径（rg 末参）
     * @param abortController 取消信号（可为 null → NOOP）
     * @return 输出行列表（stdout trim/split/CR 剥离/filter；退出码 1 → 空列表）
     * @throws RipgrepTimeoutError 超时 / abort 且无部分结果（CC :446-454）
     * @throws IOException         rg 二进制缺失/不可执行/IO 失败（CC ENOENT/EACCES/EPERM → reject :384-388）
     */
    public List<String> ripGrep(List<String> args, String target, AbortController abortController)
            throws RipgrepTimeoutError, IOException {
        return ripGrep(args, target, abortController, defaultTimeoutMillis());
    }

    /** 带显式超时的重载（测试用 · 生产走 {@link #defaultTimeoutMillis()}）。 */
    List<String> ripGrep(List<String> args, String target, AbortController abortController, long timeoutMillis)
            throws RipgrepTimeoutError, IOException {
        AbortController abort = abortController != null ? abortController : AbortController.NOOP;
        return ripGrepOnce(args, target, abort, timeoutMillis, false);
    }

    /**
     * 单次 rg 调用 · 返回后 EAGAIN 分支交给外层 {@link #ripGrepOnce} 递归重试（singleThread=true）。
     */
    private List<String> ripGrepOnce(List<String> args, String target, AbortController abort,
                                     long timeoutMillis, boolean singleThread) throws RipgrepTimeoutError, IOException {
        // abort 已触发 → 对齐 CC spawn signal 已取消语义：空结果（无部分输出）
        if (abort.isCancelled()) {
            return List.of();
        }

        Path rgPath = ensureRgBinary();
        List<String> command = new ArrayList<>();
        command.add(rgPath.toString());
        if (singleThread) {
            // CC :126 单线程重试仅本次调用（-j 1，不持久化）
            command.add("-j");
            command.add("1");
        }
        command.addAll(args);
        command.add(target);

        // 本次调用整体 timeout（CC :128-133 允许 env 覆写，Java 先取默认；env 覆写登记后续批次）
        if (log.isDebugEnabled()) {
            log.debug("RipgrepRunner: 调用 rg {} target={} timeoutMs={}（CC ripgrep.ts:345-463）",
                String.join(" ", command.subList(1, command.size())), target, timeoutMillis);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // CC :384-388 ENOENT/EACCES/EPERM → reject
            log.error("RipgrepRunner: rg 启动失败（二进制={}），拒绝搜索", rgPath, e);
            throw e;
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        AtomicBoolean stdoutCapped = new AtomicBoolean(false);
        AtomicBoolean stderrCapped = new AtomicBoolean(false);
        Thread outThread = readerThread(process.getInputStream(), stdout, stdoutCapped, "rg-stdout");
        Thread errThread = readerThread(process.getErrorStream(), stderr, stderrCapped, "rg-stderr");

        // abort 接线 · 对齐 CC :139 signal 选项：取消 → kill 子进程
        AtomicBoolean aborted = new AtomicBoolean(false);
        Consumer<AbortController> onAbort = ac -> {
            aborted.set(true);
            process.destroyForcibly();
        };
        abort.onCancel(onAbort);

        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                // CC :175-182 超时 kill（SIGTERM→5s 后 SIGKILL；Windows 直接 kill）
                process.destroyForcibly();
                // 等进程真正退出（destroyForcibly 后应立即返回）
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            finished = false;
        } finally {
            abort.removeOnCancel(onAbort);
        }

        joinQuietly(outThread);
        joinQuietly(errThread);

        int exitCode = process.isAlive() ? -1 : process.exitValue();
        boolean timedOut = !finished;
        String out = stdout.toString();
        String err = stderr.toString();
        boolean overflow = stdoutCapped.get();

        if (log.isDebugEnabled()) {
            log.debug("RipgrepRunner: rg 退出 code={} timedOut={} aborted={} overflow={} stdoutBytes={} stderrBytes={}",
                exitCode, timedOut, aborted.get(), overflow, out.length(), err.length());
        }

        // 成功：code 0 = 有匹配，code 1 = 无匹配（CC :192-194）—— 未超限时直接返回
        if (!overflow && !timedOut && (exitCode == 0 || exitCode == 1)) {
            return splitLines(out);
        }

        // EAGAIN 重试（CC :87-92/:394-409）：仅本次非单线程调用触发一次
        if (!singleThread && isEagainError(err)) {
            if (log.isDebugEnabled()) {
                log.debug("RipgrepRunner: rg EAGAIN 错误，改用单线程 -j 1 重试（CC :394-409）");
            }
            return ripGrepOnce(args, target, abort, timeoutMillis, true);
        }

        // 错误路径：isTimeout 含 超时 / abort（CC :413-416 ABORT_ERR 等价）
        boolean isTimeoutLike = timedOut || aborted.get();
        List<String> lines = splitLines(out);
        if ((isTimeoutLike || overflow) && !lines.isEmpty()) {
            // CC :428-430 超时/缓冲溢出丢最后一行（可能不完整）
            lines = new ArrayList<>(lines.subList(0, lines.size() - 1));
        }
        if (isTimeoutLike && lines.isEmpty()) {
            // CC :446-454 超时无结果 → 抛 RipgrepTimeoutError（让模型知道搜索未完成）
            throw new RipgrepTimeoutError(
                "Ripgrep search timed out after " + (isWsl() ? 60 : 20) + " seconds. "
                    + "The search may have matched files but did not complete in time. "
                    + "Try searching a more specific path or pattern.",
                List.of());
        }
        if (!isTimeoutLike) {
            // 其他非致命错误（含缓冲溢出）：返回部分结果（CC :432-442/:456 resolve(lines)）
            if (log.isDebugEnabled()) {
                log.debug("RipgrepRunner: rg 非致命错误，返回部分结果 lines={} stderr={}", lines.size(), err);
            }
        }
        return lines;
    }

    /** stdout/stderr 异步读取线程（防子进程输出阻塞管道死锁）· MAX_BUFFER 截断。 */
    private static Thread readerThread(InputStream in, StringBuilder sink, AtomicBoolean capped, String name) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    if (capped.get()) {
                        continue;
                    }
                    synchronized (sink) {
                        if (sink.length() >= MAX_BUFFER_SIZE) {
                            capped.set(true);
                            continue;
                        }
                        int room = MAX_BUFFER_SIZE - sink.length();
                        sink.append(new String(buf, 0, Math.min(n, room), StandardCharsets.UTF_8));
                        if (n > room) {
                            capped.set(true);
                        }
                    }
                }
            } catch (IOException ignored) {
                // 子进程被杀 / 流关闭 → 读取自然结束（对齐 CC :149-167 无显式错误处理）
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

    /** 输出行拆分 · 对齐 CC :367-371（trim / split('\n') / 去 CR / filter(Boolean)）。 */
    static List<String> splitLines(String stdout) {
        String trimmed = stdout.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(trimmed.split("\n"))
            .map(line -> line.endsWith("\r") ? line.substring(0, line.length() - 1) : line)
            .filter(line -> !line.isEmpty())
            .toList();
    }

    /** EAGAIN 检测 · 对齐 CC :87-92（os error 11 / Resource temporarily unavailable）。 */
    static boolean isEagainError(String stderr) {
        return stderr != null
            && (stderr.contains("os error 11") || stderr.contains("Resource temporarily unavailable"));
    }

    /** WSL 检测（CC getPlatform()==='wsl' 等价）：Linux + /proc/version 含 microsoft。 */
    static boolean isWsl() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            return false;
        }
        try {
            String version = Files.readString(Path.of("/proc/version"));
            return version.toLowerCase().contains("microsoft");
        } catch (IOException e) {
            return false;
        }
    }

    /** 默认超时 · CC :128-133：WSL 60s / 其余 20s。 */
    static long defaultTimeoutMillis() {
        return isWsl() ? WSL_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
    }

    /** 当前平台 rg 二进制资源路径 · 如 {@code /rg/x64-win32/rg.exe}（CC :58-62 等价）。 */
    static String resourceName() {
        return "/rg/" + platformDirName() + "/" + (isWindows() ? "rg.exe" : "rg");
    }

    /** 平台目录名 · 如 {@code x64-win32}（CC {@code ${process.arch}-${process.platform}}）。 */
    static String platformDirName() {
        return resolveArch() + "-" + resolveOs();
    }

    /** os.name → CC process.platform（win32/linux/darwin）。 */
    static String resolveOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        return "linux";
    }

    /** os.arch → CC process.arch（x64/arm64）。 */
    static String resolveArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            return "x64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        // 未知架构 → 默认 x64（fail-loud 由二进制缺失兜底）
        log.warn("RipgrepRunner: 未知 os.arch='{}'，回退 x64（若二进制缺失将 fail-loud）", arch);
        return "x64";
    }

    private static boolean isWindows() {
        return "win32".equals(resolveOs());
    }

    /**
     * 解析 rg 二进制 · 首次从 classpath 提取到临时缓存目录（fat-jar 内资源不可直接执行）。
     *
     * <p>缓存路径 {@code ${java.io.tmpdir}/nexusai-rg/{RG_VERSION}/{os}-{arch}/rg[.exe]}；
     * 缺失或与资源大小不符时重新提取（临时文件 + 原子移动防半写）；POSIX 置可执行位。
     */
    static Path ensureRgBinary() throws IOException {
        Path p = cachedBinary;
        if (p != null && Files.isRegularFile(p)) {
            return p;
        }
        synchronized (RipgrepRunner.class) {
            if (cachedBinary != null && Files.isRegularFile(cachedBinary)) {
                return cachedBinary;
            }
            cachedBinary = extractRgBinary();
            return cachedBinary;
        }
    }

    private static Path extractRgBinary() throws IOException {
        String resource = resourceName();
        String platform = platformDirName();
        String exe = isWindows() ? "rg.exe" : "rg";
        Path cacheBase = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
        Path cacheDir = cacheBase.resolve("nexusai-rg").resolve(RG_VERSION).resolve(platform);
        Path bin = cacheDir.resolve(exe);

        if (Files.isRegularFile(bin) && binSizeMatches(bin, resource)) {
            return bin;
        }

        Files.createDirectories(cacheDir);
        try (InputStream in = RipgrepRunner.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(
                    "rg binary not bundled on classpath: " + resource + "（CC getRipgrepConfig ripgrep.ts:58-62 等价，缺失时 fail-loud）");
            }
            Path tmp = cacheDir.resolve(exe + ".tmp" + System.nanoTime());
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // 临时文件可能残留，清理后抛出
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清理失败不掩盖原始错误
                }
                throw e;
            }
            makeExecutable(tmp);
            try {
                Files.move(tmp, bin, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, bin, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("RipgrepRunner: rg 二进制就绪 → {}（来源 classpath {}）", bin, resource);
        }
        return bin;
    }

    /** 校验缓存二进制与资源大小一致（防半写/损坏）。 */
    private static boolean binSizeMatches(Path bin, String resource) throws IOException {
        try (InputStream in = RipgrepRunner.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            long resourceSize = in.available();
            if (resourceSize <= 0) {
                // available() 对某些流实现不可靠（jar entry 缓冲）→ 无法判定，信任缓存
                return true;
            }
            long binSize = Files.size(bin);
            if (resourceSize == binSize) {
                return true;
            }
            if (log.isDebugEnabled()) {
                log.debug("RipgrepRunner: 缓存 rg 大小不符（cache={} resource={}），重新提取", binSize, resourceSize);
            }
            return false;
        }
    }

    /** POSIX 置可执行位（Windows no-op）。 */
    private static void makeExecutable(Path p) {
        try {
            if (!p.toFile().setExecutable(true, false) && !isWindows()) {
                log.warn("RipgrepRunner: 设置 rg 可执行位失败（{}）", p);
            }
        } catch (SecurityException e) {
            log.warn("RipgrepRunner: 设置 rg 可执行位被安全策略拒绝（{}）", p, e);
        }
    }
}
