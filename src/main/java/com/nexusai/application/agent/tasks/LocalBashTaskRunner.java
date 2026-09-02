package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.bash.ShellExecutor;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 本地 Bash 任务执行器 — CC LocalShellTask.tsx 的 Java 对等
 *
 * <p>使用 ProcessBuilder 执行 bash 命令 (L3: Python subprocess → Java ProcessBuilder)
 * <br>输出写入 outputFile (CC: shellCommand.background(taskId))
 */
public class LocalBashTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalBashTaskRunner.class);

    /** 输出截断上限 (CC: BashTool 50k chars) */
    private static final int OUTPUT_LIMIT = 50_000;

    // ── T1: 5GB size-watchdog · 对齐 CC ShellCommand.ts:239-261 + diskOutput.ts:30-31 ──
    /** 后台任务输出文件大小上限 · CC original: MAX_TASK_OUTPUT_BYTES (diskOutput.ts:30) = 5 * 1024^3。 */
    private static final long MAX_TASK_OUTPUT_BYTES = 5L * 1024L * 1024L * 1024L;
    /** size-watchdog 轮询间隔 · CC original: SIZE_WATCHDOG_INTERVAL_MS (ShellCommand.ts:54) = 5_000。 */
    private static final long SIZE_WATCHDOG_INTERVAL_MS = 5_000L;
    /** 被 size-watchdog 杀死的进程退出码 = 128 + SIGKILL(9) = 137（对齐 CC #doKill(SIGKILL), ShellCommand.ts:48/252）。 */
    static final int KILLED_FOR_SIZE_EXIT_CODE = 137;

    /** 用户 kill（{@link #killProcess()}）后结果退出码 = 128 + SIGKILL(9) = 137 ·
     *  CC original: {@code SIGKILL = 137}（ShellCommand.ts:49）+ {@code #doKill 恒 code ?? SIGKILL}
     *  （:343）。G2-3 修复：Windows taskkill 后 {@code process.exitValue()} 自然码可能 ≠137。 */
    static final int EXIT_SIGKILL = 137;
    /** size-watchdog kill 消息 · CC original: prependStderr('Background command killed: output file exceeded 5GB',
     *  ShellCommand.ts:318-322 + MAX_TASK_OUTPUT_BYTES_DISPLAY='5GB' diskOutput.ts:31)。Java 文件模式
     *  stderr 行写盘带 {@code [stderr] } 前缀（appendOutputLine），故前缀保留以与文件格式一致。 */
    static final String KILLED_FOR_SIZE_MESSAGE = "[stderr] Background command killed: output file exceeded 5GB";

    // s13-P1-3 修复: 移除 120s 硬超时 (CC background task 无限时长, 仅由用户主动 kill)
    // 原 TIMEOUT_SECONDS = 120 在 "pip install torch 10 分钟" 等真实长任务场景下过早 kill
    // waitFor 改为无 timeout 参数版本, 进程由 process.destroy() / process.destroyForcibly() 显式控制
    // 注意: 本类仅服务于 background 模式 (类注释 "对齐 CC shellCommand.background(taskId)"),
    //   同步命令应使用 BashTool 主路径 (有 CC 的 2-min timeout 兜底)

    /** s13-p2: 当前运行的子进程引用 — 供 cancel() 时 destroyForcibly 使用 */
    private volatile Process currentProcess;

    /**
     * G2-3: 用户 kill 标记 · 对齐 CC {@code #doKill 恒 code ?? SIGKILL=137}（ShellCommand.ts:48,343）。
     * {@link #killProcess()} 杀路径置 true，{@link #execute} 读回把结果退出码映射为 137
     * （Windows taskkill 后 {@code process.exitValue()} 自然码可能 ≠137，须显式映射）。
     */
    private volatile boolean killed;

    /**
     * G1-2: 执行完成信号 · 对齐 CC {@code shellCommand.result} promise
     * （LocalShellTask.tsx:331-366 / :445-472 result.then）。
     *
     * <p><b>WHY</b>：前台任务转后台（{@code backgroundExistingForegroundTask}）后，BackgroundTaskRunner
     * 的完成 watcher 须等待该前台进程自然结束再推进终态 + 通知。Java 无 promise，以
     * {@code CountDownLatch} 承载"execute 完成"信号（execute 在 G5 BashTool 线程运行，
     * watcher 经 {@link #awaitCompletion()} 阻塞等待，无竞态）。
     */
    private final CountDownLatch completionLatch = new CountDownLatch(1);

    /**
     * G1-2: 最近一次 {@link #execute(String, String)} 的 BashResult · execute 返回前填充
     * （含 killedForSize 合成 exitCode=137 的结果）；异常路径/未执行 → null。后台化 watcher
     * 经 {@link #getLastResult()} 读取以组装终态任务（exitCode/通知）。
     */
    private volatile BashResult lastResult;

    /**
     * Bash 执行结果
     */
    public record BashResult(int exitCode, String stdout, String stderr) {}

    /**
     * 解析后台任务快照 · 对齐 CC buildExecCommand 内 {@code access(snapshotFilePath)} 复验
     * （bashProvider.ts:93-103）+ {@code getSpawnArgs}（:200-206）。
     *
     * <p>复用 {@link ShellExecutor#getOrCreateSnapshot()} 会话级缓存（首调可能阻塞 ≤10s 生成，
     * 对齐 CC provider 创建时 {@code createAndSaveSnapshot}，bashProvider.ts:63-68）；快照中途
     * 消失（tmpdir 清理）→ 返回 null（命令回退 {@code -l} login shell，bashProvider.ts:93-103 同款）。
     *
     * <p><b>包可见（package-private）</b>：供同包单测子类覆盖注入临时快照路径，验证后台命令
     * 带用户环境（规则九，不污染全局快照缓存）。
     *
     * @return 有效快照路径；无/失效 → null（调用方回退 {@code -l} login shell）
     */
    Path resolveBackgroundSnapshot() {
        Optional<Path> snap = ShellExecutor.getOrCreateSnapshot();
        if (snap.isPresent() && ShellExecutor.isSnapshotValid(snap.get())) {
            return snap.get();
        }
        return null;
    }

    /**
     * 同步执行 bash 命令 — 运行期逐行流式写入输出文件 · 对齐 CC shellCommand.background(taskId)
     *
     * <p>R5 (W4-02): stdout/stderr 每行实时 append 到 outputFile (readStream → appendOutputLine),
     * 进程运行中文件即增长 — TaskOutputTool 轮询 (TaskOutput.ts:81-164) + StallWatchdog 增长检测实时可见.
     * 进程结束后不重写文件 (CC flushAndCleanup 仅 flush, 不重写 — LocalShellTask.tsx:515-520).
     *
     * <p>T1: 文件模式下启动 5GB size-watchdog（对齐 CC ShellCommand.ts:239-261）——输出文件超
     * {@link #MAX_TASK_OUTPUT_BYTES} 且进程存活 → SIGKILL 杀进程，防 stuck append loop 打满磁盘
     * （CC 768GB incident）。杀进程结果经 {@link BashResult} 携带（exitCode=137 + stderr 前缀消息，
     * 对齐 CC #handleExit prependStderr）。
     *
     * @param command    bash 命令
     * @param outputFile 输出文件路径 (可为 null — 不写文件)
     * @return 执行结果
     */
    public BashResult execute(String command, String outputFile) throws IOException, InterruptedException {
        return execute(command, outputFile, MAX_TASK_OUTPUT_BYTES);
    }

    /**
     * 可注入 size-watchdog 阈值的 execute · T1 测试用合成小阈值（真实 5GB 不跑）；生产委托
     * {@link #execute(String, String)} 走 5GB 常量。逻辑与 2 参同构（watchdog 判定阈值为入参）。
     *
     * @param command    bash 命令
     * @param outputFile 输出文件路径 (可为 null — 不写文件)
     * @param maxBytes   size-watchdog 大小上限（生产 5GB；测试注入小阈值合成超限场景）
     * @return 执行结果
     */
    BashResult execute(String command, String outputFile, long maxBytes) throws IOException, InterruptedException {
        log.info("LocalBashTaskRunner: executing command");
        if (log.isDebugEnabled()) {
            log.debug("LocalBashTaskRunner: command='{}'", abbreviate(command, 200));
        }

        // 统一 bash/zsh（对齐 CC findSuitableShell Shell.ts:73-137）：Windows 走 Git Bash，
        // 非 Windows 探测 bash/zsh。找不到 → IllegalStateException（CC 同款显式错误，fail-loud）。
        // cwd-align-ext：后台任务进程 cwd = 会话 cwd（CC Shell.ts:218 pwd() → spawn {cwd}）；
        //   无 sessionId（后台线程 MDC 不传播）回落 user.dir（方案 1，零行为变化）。
        // G2-1 后台快照接线：后台任务接用户环境（快照 source），对齐 CC buildExecCommand
        //   source 前缀（bashProvider.ts:161-167）+ getSpawnArgs（:200-206 有快照 -c 无 -l /
        //   无快照回退 -c -l）。后台不需要 cwd 回写（对齐 CC 后台语义不更新 cwd），故用
        //   wrapForBackground（仅 source 快照，无 pwd -P >| track，与前台 wrapForExec 的差别）。
        //   快照生成失败/超时 → resolveBackgroundSnapshot 返回 null → 三参 bash 加 -l login shell
        //   （bashProvider.ts:93-103 同款），不阻塞后台启动、不破坏既有行为。
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        Path snapshot = resolveBackgroundSnapshot();
        String wrappedCommand = ShellExecutor.wrapForBackground(command, snapshot);
        ProcessBuilder pb = ShellExecutor.bash(wrappedCommand,
            cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", "."),
            snapshot);
        pb.redirectErrorStream(false);

        Process process = pb.start();
        this.currentProcess = process; // s13-p2: 保存引用供 cancel() 时 destroyForcibly

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // R5 流式接线 (W4-02): readStream 逐行 append 写 outputFile — 对齐 CC shellCommand.background
        //   (ShellCommand.ts:349-366 stdoutToFile 文件模式) — 运行期 outputFile 即增长,
        //   TaskOutputTool 轮询 (TaskOutput.ts:81-164 #tick) + StallWatchdog (Files.size 增长检测) 实时可见.
        //   进程结束后不再重写文件 (CC flushAndCleanup 仅 flush 磁盘缓冲, 不重写 — LocalShellTask.tsx:515-520).
        Thread stdoutReader = new Thread(
            () -> readStream(process.getInputStream(), stdout, OUTPUT_LIMIT, outputFile, false), "bash-stdout");
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        Thread stderrReader = new Thread(
            () -> readStream(process.getErrorStream(), stderr, OUTPUT_LIMIT, outputFile, true), "bash-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        // ── T1: 5GB size-watchdog · 对齐 CC ShellCommand.ts:239-261 #startSizeWatchdog ──
        // background 文件模式（stdoutToFile）stuck append loop 会把磁盘打满（CC 768GB incident,
        // ShellCommand.ts:357 注释）。文件模式下启动 watchdog 线程，每 5s stat 输出文件大小，
        // 超 maxBytes 且进程存活 → destroyForcibly(SIGKILL)（对齐 CC #doKill, :252）。进程自然退出后
        // 由 finally {@link #stopSizeWatchdog} 停止（对齐 CC #clearSizeWatchdog, :232-237 / #cleanupListeners :219）。
        BashResult result = null;
        boolean[] killedForSize = { false };
        Thread sizeWatchdog = startSizeWatchdog(process, outputFile, killedForSize, maxBytes);
        try {
            process.waitFor();  // s13-P1-3: 无 timeout, 进程由 kill 显式控制 (waitFor() 阻塞至进程自然结束)
            // 无需检查返回值 — 进程正常结束 = 继续执行; 异常路径在 catch 块

            stdoutReader.join(2000);
            stderrReader.join(2000);

            int exitCode = process.exitValue();
            String out = stdout.toString();
            String err = stderr.toString();

            if (killedForSize[0]) {
                // 对齐 CC #handleExit killedForSize → prependStderr (ShellCommand.ts:318-322):
                //   stderr = "Background command killed: output file exceeded 5GB" + 原 stderr
                exitCode = KILLED_FOR_SIZE_EXIT_CODE;
                err = err.isEmpty() ? KILLED_FOR_SIZE_MESSAGE : KILLED_FOR_SIZE_MESSAGE + " " + err;
                log.warn("LocalBashTaskRunner: task killed by size-watchdog（输出超限）pid={} outputFile={}",
                    process.pid(), outputFile);
            } else if (killed) {
                // G2-3: 用户 kill（killProcess）→ 退出码映射 137（对齐 CC #doKill 恒 code ?? SIGKILL=137,
                //   ShellCommand.ts:48/343）——Windows taskkill 后 process.exitValue() 自然码可能 ≠137。
                exitCode = EXIT_SIGKILL;
                log.warn("LocalBashTaskRunner: task killed by user（killProcess）pid={} → exitCode=137",
                    process.pid());
            }

            log.info("LocalBashTaskRunner: exitCode={}", exitCode);
            result = new BashResult(exitCode, out, err);
        } finally {
            stopSizeWatchdog(sizeWatchdog);
            // G1-2: 填充 lastResult + 释放完成信号（对齐 CC shellCommand.result promise resolve）——
            //   watcher awaitCompletion() 唤醒；异常路径 result=null 亦 countDown 防 watcher 悬挂。
            this.lastResult = result;
            this.completionLatch.countDown();
        }
        return result;
    }

    /**
     * 强制杀死子进程 — 对齐 CC killTask → shellCommand.kill() → {@code #doKill(SIGKILL)}
     * （ShellCommand.ts:337-343 treeKill 杀整棵进程树）。
     *
     * <p>G1-1: 由 {@link ProcessTreeKiller#killTree} 承接 — Windows {@code taskkill /F /T /PID}
     * 杀整树 / POSIX 先枚举后代再杀根（对齐 treeKill SIGKILL 语义，防 {@code bash -c "sleep 999 & wait"}
     * 的 sleep 成孤儿）。s13-p2 原 {@code p.destroyForcibly()} 只杀 bash 直接子进程。
     * <br>杀后 waitFor(5s) 确认终止；优雅降级: 若 waitFor 超时, log warning 但不抛异常
     * <br>Process already exited 检测: isAlive() 检查避免对已终止进程操作
     */
    public void killProcess() {
        Process p = this.currentProcess;
        if (p == null || !p.isAlive()) {
            if (log.isDebugEnabled() && p != null) {
                log.debug("LocalBashTaskRunner: process already exited, pid={}", p.pid());
            }
            return; // Process already exited — 优雅降级
        }
        // G2-3: 先置 killed 标记再杀（execute() 读回映射退出码 137；对齐 CC #doKill
        //   恒 code ?? SIGKILL=137, ShellCommand.ts:48/343——Windows taskkill 后 exitValue() 可能 ≠137）。
        this.killed = true;
        log.info("LocalBashTaskRunner: killing process tree pid={}", p.pid());
        ProcessTreeKiller.killTree(p); // G1-1: 对齐 CC treeKill(pid,'SIGKILL')
        try {
            boolean terminated = p.waitFor(5, TimeUnit.SECONDS);
            if (!terminated) {
                log.warn("LocalBashTaskRunner: process pid={} did not terminate within 5s after process-tree kill",
                    p.pid());
            } else if (log.isDebugEnabled()) {
                log.debug("LocalBashTaskRunner: process pid={} terminated after process-tree kill", p.pid());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LocalBashTaskRunner: interrupted while waiting for process kill, pid={}", p.pid());
        }
    }

    /**
     * 检查子进程是否仍在运行 — s13-p2
     */
    public boolean isProcessAlive() {
        Process p = this.currentProcess;
        return p != null && p.isAlive();
    }

    /**
     * 当前子进程 pid · G1-1 进程树杀验证用（测试/日志；未启动/已结束 → -1）。
     *
     * @return bash 根进程 pid；无进程 → -1
     */
    public long getPid() {
        Process p = this.currentProcess;
        return p != null ? p.pid() : -1L;
    }

    /**
     * G1-2: 等待当前执行完成 · 对齐 CC {@code shellCommand.result} promise
     * （LocalShellTask.tsx:331-366 / :445-472 result.then）。
     *
     * <p>前台任务转后台后，{@code BackgroundTaskRunner.backgroundExistingForegroundTask} 的
     * 完成 watcher 调用本方法阻塞等待 execute 结束（execute 在 G5 BashTool 线程运行），
     * 随后经 {@link #getLastResult()} 读取结果推进终态。
     *
     * @throws InterruptedException 等待中断（调用方须复位中断标记）
     */
    public void awaitCompletion() throws InterruptedException {
        this.completionLatch.await();
    }

    /**
     * G1-2: 最近一次 execute 的 BashResult · execute 返回前填充；异常路径/未执行 → null。
     * 供 {@code BackgroundTaskRunner} 后台化 watcher 组装终态任务（exitCode + 通知）。
     *
     * @return 最近一次 execute 结果；未完成/异常 → null
     */
    public BashResult getLastResult() {
        return this.lastResult;
    }

    /**
     * G5-1/G5-2: 接管前台进程引用 · 让 {@code BackgroundTaskRunner.backgroundExistingForegroundTask}
     * 的守卫（{@link #isProcessAlive()}）能看到 BashTool 前台 spawn 的 Process（BashTool 前台不走
     * 本类 {@link #execute}，进程在 BashTool 线程运行）。registerForeground 前由 BashTool 调用。
     *
     * @param process 前台 bash 进程（转后台后可被 cancel/kill 杀）
     */
    public void adoptForegroundProcess(Process process) {
        this.currentProcess = process;
        if (log.isDebugEnabled()) {
            log.debug("LocalBashTaskRunner: 接管前台进程 pid={}（G5-1/G5-2 adoptForegroundProcess）",
                process != null ? process.pid() : -1L);
        }
    }

    /**
     * G5-1/G5-2: 前台进程完成信号 · BashTool 后台化 completion watcher 在进程自然结束后调用：
     * 填充 {@link #lastResult}（BackgroundTaskRunner {@code completeForegroundBackgroundedTask} 读取
     * 组装终态） + 释放 {@link #completionLatch}（{@link #awaitCompletion()} 由此唤醒）。
     *
     * <p>与 {@link #execute} 路径互斥：execute 内部分支在 finally 填 lastResult + countDown；
     * 本方法供前台转后台场景（进程不经 execute 运行）填充，幂等（countDown 原子）。
     *
     * @param result 前台进程终态结果（exitCode/stdout/stderr）
     */
    public void signalForegroundCompletion(BashResult result) {
        this.lastResult = result;
        this.completionLatch.countDown();
        if (log.isDebugEnabled()) {
            log.debug("LocalBashTaskRunner: 前台进程完成信号已发送 exitCode={}（G5-1/G5-2 signalForegroundCompletion）",
                result != null ? result.exitCode() : -1);
        }
    }

    // ── T1: 5GB size-watchdog 实现 · 对齐 CC ShellCommand.ts:239-261 ──

    /**
     * 输出文件是否超 size 上限 · 单测可直调（规则九：判定方法独立可测）。
     *
     * <p>对齐 CC #startSizeWatchdog 的 stat 判定（ShellCommand.ts:241-253）：文件不存在（首次写入前
     * ENOENT）/ IO 异常 → 跳过 tick（false，不误杀进程，对齐 CC :255-257 catch）。
     *
     * @param outputFile 输出文件路径
     * @param maxBytes   大小上限（生产 5GB；测试注入小阈值合成超限）
     * @return true = 文件存在且大小 &gt; maxBytes
     */
    static boolean isOutputExceeded(Path outputFile, long maxBytes) {
        try {
            return Files.size(outputFile) > maxBytes;
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("LocalBashTaskRunner: size-watchdog stat 失败（ENOENT/IO）跳过 tick: {}", e.toString());
            }
            return false;
        }
    }

    /**
     * 启动 5GB size-watchdog · 对齐 CC ShellCommand.ts:239-261 #startSizeWatchdog。
     *
     * <p>后台任务 stdout/stderr 逐行直写 outputFile（文件模式），stuck append loop 可把磁盘打满
     * （CC 768GB incident）。每 {@link #SIZE_WATCHDOG_INTERVAL_MS}（5s）stat 输出文件大小，超 maxBytes
     * 且进程仍存活 → {@link ProcessTreeKiller#killTree}（对齐 CC {@code #doKill(SIGKILL)} treeKill 杀
     * 整树，ShellCommand.ts:252/337-343）+ 置 killedForSize。
     * 进程自然退出后由调用方 finally {@link #stopSizeWatchdog} 停止（对齐 CC {@code #clearSizeWatchdog}
     * :232-237 / {@code #cleanupListeners} :219）。daemon 线程，不阻塞主执行。
     *
     * @param process       受监控的子进程
     * @param outputFile    输出文件路径（null/blank → 无文件可判，返回 null 不启动）
     * @param killedForSize 单元素布尔数组（watchdog 触发后置 true，供 execute 读回组装 BashResult）
     * @param maxBytes      大小上限（生产 5GB；测试注入小阈值）
     * @return watchdog 线程；未启动（无输出文件）→ null
     */
    private static Thread startSizeWatchdog(Process process, String outputFile, boolean[] killedForSize, long maxBytes) {
        if (outputFile == null || outputFile.isBlank()) {
            return null;
        }
        Path outputPath = Path.of(outputFile);
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(SIZE_WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (killedForSize[0]) {
                    break; // 已触发（对齐 CC watchdog cleared check, :247-248）
                }
                if (!process.isAlive()) {
                    break; // 进程已自然退出（对齐 CC :247 status 非 backgrounded 之外的等价退出）
                }
                if (isOutputExceeded(outputPath, maxBytes)) {
                    killedForSize[0] = true;
                    log.warn("LocalBashTaskRunner: size-watchdog 触发（输出文件超过上限）pid={} maxBytes={} → SIGKILL",
                        process.pid(), maxBytes);
                    // G1-1: 对齐 CC #doKill(SIGKILL) treeKill 杀整树（ShellCommand.ts:252/337-343），
                    //   原 process.destroyForcibly() 只杀 bash 直接子进程，stuck append loop 的
                    //   子进程（如 `yes | head` 管道链）可能残留。
                    ProcessTreeKiller.killTree(process);
                    break;
                }
            }
        }, "bash-size-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 停止 size-watchdog · 对齐 CC #clearSizeWatchdog（ShellCommand.ts:232-237）。 */
    private static void stopSizeWatchdog(Thread sizeWatchdog) {
        if (sizeWatchdog != null) {
            sizeWatchdog.interrupt();
        }
    }

    /**
     * 逐行读取流 — stdout/stderr 均实时 append 写入 outputFile (R5 流式接线).
     *
     * <p>对齐 CC 文件模式 (TaskOutput.ts:24-27 双流直写文件): 每读到一行立即落盘,
     * TaskOutputTool 轮询 / StallWatchdog 增长检测即可实时读取. stderr 行加
     * {@code [stderr] } 前缀 (CC 管道模式 DiskTaskOutput.append 同款 — TaskOutput.ts:183).
     *
     * <p>文件 append 与内存 StringBuilder 同受 {@link #OUTPUT_LIMIT} 门控 —
     * 防 background 长任务磁盘打满由 {@link #startSizeWatchdog}（T1 · 对齐 CC ShellCommand.ts:239-261
     * size-watchdog kill 机制）承接：输出文件超 5GB → SIGKILL 杀进程.
     *
     * @param stream     进程 stdout/stderr 流
     * @param sb         内存累积缓冲 (BashResult 返回契约)
     * @param limit      输出截断上限
     * @param outputFile 输出文件路径 (null/blank 则不落盘)
     * @param isErr      是否 stderr 行
     */
    private void readStream(InputStream stream, StringBuilder sb, int limit, String outputFile, boolean isErr) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                boolean underLimit;
                synchronized (sb) {
                    underLimit = sb.length() < limit;
                    if (underLimit) {
                        sb.append(line).append('\n');
                    }
                }
                if (underLimit && outputFile != null && !outputFile.isBlank()) {
                    appendOutputLine(outputFile, line, isErr);
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("LocalBashTaskRunner stream read error: {}", e.toString());
        }
    }

    /**
     * 流式 append 写入 (per-line) · 对齐 CC shellCommand.background 实时重定向.
     *
     * <p>每行 stdout/stderr 立即写入文件, 供 TaskOutputTool 轮询实时读取
     * (TaskOutput.ts:81-164 #tick 轮询文件尾部). stderr 行加 {@code [stderr] } 前缀
     * (CC 管道模式 DiskTaskOutput.append 同款 — TaskOutput.ts:183).
     *
     * @param path  输出文件路径
     * @param line  单行内容 (不含换行符)
     * @param isErr 是否是 stderr 行
     */
    public void appendOutputLine(String path, String line, boolean isErr) {
        if (path == null || path.isBlank()) return;
        try {
            Path filePath = Path.of(path);
            Files.createDirectories(filePath.getParent());
            String prefix = isErr ? "[stderr] " : "";
            Files.writeString(filePath, prefix + line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("LocalBashTaskRunner: appendOutputLine failed: {}", e.getMessage());
        }
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }
}
