package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 输出文件增长看门狗 — L1 对齐 CC LocalShellTask.tsx:24-103 startStallWatchdog
 *
 * <h2>CC 常量 (LocalShellTask.tsx:24-26)</h2>
 * <ul>
 *   <li>STALL_CHECK_INTERVAL_MS = 5_000</li>
 *   <li>STALL_THRESHOLD_MS = 45_000</li>
 *   <li>STALL_TAIL_BYTES = 1024</li>
 * </ul>
 *
 * <h2>CC 行为流程 (LocalShellTask.tsx:46-103)</h2>
 * <ol>
 *   <li>周期检查输出文件 offset (每 STALL_CHECK_INTERVAL_MS)</li>
 *   <li>若文件 offset 增长 → 重置计时器</li>
 *   <li>若 STALL_THRESHOLD_MS 内 offset 未增长 → 读取尾部 STALL_TAIL_BYTES 字节</li>
 *   <li>若 looksLikePrompt(tail) = true → 触发 kill callback + 自动停止 (one-shot)</li>
 * </ol>
 *
 * <h2>CC PROMPT_PATTERNS (LocalShellTask.tsx:32-38 扩展版, ≥14 patterns)</h2>
 */
public class StallWatchdog {

    private static final Logger log = LoggerFactory.getLogger(StallWatchdog.class);

    /** CC LocalShellTask.tsx:24 — STALL_CHECK_INTERVAL_MS = 5_000 */
    static final long CC_STALL_CHECK_INTERVAL_MS = 5_000;

    /** CC LocalShellTask.tsx:25 — STALL_THRESHOLD_MS = 45_000 */
    static final long CC_STALL_THRESHOLD_MS = 45_000;

    /** CC LocalShellTask.tsx:26 — STALL_TAIL_BYTES = 1024 */
    private static final int STALL_TAIL_BYTES = 1024;

    // ============================================================
    // CC PROMPT_PATTERNS — ≥14 regex patterns
    // 对齐 CC LocalShellTask.tsx:32-38 + 扩展交互式提示检测
    // ============================================================

    /** (y/n) / [y/n] / (yes/no) */
    private static final Pattern P_YN = Pattern.compile("\\(y/n\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_BRACKET_YN = Pattern.compile("\\[y/n\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_YES_NO = Pattern.compile("\\(yes/no\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_ARE_YOU_SURE = Pattern.compile("\\bare you sure\\b", Pattern.CASE_INSENSITIVE);

    /** Do you / Would you / Shall I / Ready to / Proceed */
    private static final Pattern P_DO_YOU = Pattern.compile(
        "\\b(?:Do you|Would you|Shall I|Are you sure|Ready to|Proceed)\\b.*\\?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_CONTINUE_PROCEED = Pattern.compile(
        "\\b(?:[Cc]ontinue|[Pp]roceed)\\??\\s*$");

    /** Press any key / Press Enter / Press <key> */
    private static final Pattern P_PRESS_KEY = Pattern.compile(
        "[Pp]ress\\s+(?:any\\s+key|[Ee]nter|a\\s+key|\\w+\\s+key|\\w+\\s+to)");

    /** Overwrite? / Replace? / Confirm? */
    private static final Pattern P_OVERWRITE = Pattern.compile("[Oo]verwrite\\??\\s*$");
    private static final Pattern P_CONFIRM = Pattern.compile("[Cc]onfirm\\??\\s*$");

    /** password: / passphrase: / Enter password / sudo / root password */
    private static final Pattern P_PASSWORD = Pattern.compile(
        "\\b(?:[Pp]assword|[Pp]assphrase|[Pp]ass word|[Pp]in)\\s*[:：]\\s*$");
    private static final Pattern P_ENTER_PASS = Pattern.compile(
        "[Ee]nter\\s+(?:your\\s+)?(?:password|passphrase|pass|pin)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SUDO_PASS = Pattern.compile(
        "\\[sudo\\]\\s*password", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_ROOT_PASS = Pattern.compile(
        "(?:[Rr]oot|[Aa]dmin(?:istrator)?)\\s*password\\s*[:：]");

    /** 数据库 / install 确认提示 */
    private static final Pattern P_DB_CONFIRM = Pattern.compile(
        "\\b(?:[Dd]o you want to (?:continue|proceed|install|update|remove|delete))\\b");
    private static final Pattern P_TRUNCATE_DROP = Pattern.compile(
        "\\b(?:[Tt]runcate|[Dd]rop|[Dd]elete|[Ww]ipe)\\b.*\\?\\s*$");

    /** 输入数字 / 输入选择 / 输入 y */
    private static final Pattern P_INPUT_PROMPT = Pattern.compile(
        "\\b(?:[Ee]nter|输入|请?输入|[Tt]ype|请?选择|请?键入|input|select|choose)\\b.*\\s*[:：>]\\s*$");
    private static final Pattern P_ENTER_YN = Pattern.compile(
        "\\b[Ee]nter\\s+[yYnN]\\b");

    /** ≥14 pattern 数组 — 用于 looksLikePrompt 检测 */
    private static final Pattern[] PROMPT_PATTERNS = {
        P_YN, P_BRACKET_YN, P_YES_NO, P_ARE_YOU_SURE,
        P_DO_YOU, P_CONTINUE_PROCEED,
        P_PRESS_KEY,
        P_OVERWRITE, P_CONFIRM,
        P_PASSWORD, P_ENTER_PASS, P_SUDO_PASS, P_ROOT_PASS,
        P_DB_CONFIRM, P_TRUNCATE_DROP,
        P_INPUT_PROMPT, P_ENTER_YN
    };

    // ============================================================
    // 实例字段
    // ============================================================

    private final String taskId;
    private final Path outputFile;
    private final long stallThresholdMs;
    private final long checkIntervalMs;
    private final Runnable onStallAction; // s13-p1: kill callback

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private volatile long lastOffset = -1;      // CC: outputOffset 增量检测
    private volatile long lastGrowthTime;
    private final AtomicBoolean stalled = new AtomicBoolean(false);

    // ============================================================
    // 构造函数
    // ============================================================

    /**
     * 灵活构造函数 — 用于测试 (可自定义 timing)
     *
     * @param taskId          关联任务 ID
     * @param outputFilePath  输出文件路径
     * @param stallThresholdMs 无增长超时阈值 (ms)
     * @param checkIntervalMs  检查间隔 (ms)
     * @param onStallAction   stall 后执行的动作 (kill callback), 可 null
     */
    public StallWatchdog(String taskId, String outputFilePath,
                         long stallThresholdMs, long checkIntervalMs,
                         Runnable onStallAction) {
        this.taskId = taskId;
        this.outputFile = Path.of(outputFilePath);
        this.stallThresholdMs = stallThresholdMs;
        this.checkIntervalMs = checkIntervalMs;
        this.onStallAction = onStallAction;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stall-watchdog-" + taskId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 兼容构造函数 — 无 kill callback (用于旧测试)
     */
    public StallWatchdog(String taskId, String outputFilePath,
                         long stallThresholdMs, long checkIntervalMs) {
        this(taskId, outputFilePath, stallThresholdMs, checkIntervalMs, null);
    }

    // ============================================================
    // CC looksLikePrompt (LocalShellTask.tsx:39-42 扩展版)
    // ============================================================

    /**
     * 检测输出尾部是否包含交互式提示 — L1 对齐 CC LocalShellTask.tsx:39-42
     *
     * <p>提取最后一行 → 遍历所有 PROMPT_PATTERNS → find() 匹配
     *
     * @param tail 输出文件尾部内容 (STALL_TAIL_BYTES 字节)
     * @return true 如果最后一行匹配任一 PROMPT_PATTERNS
     */
    public static boolean looksLikePrompt(String tail) {
        if (tail == null || tail.isEmpty()) {
            return false;
        }
        String[] lines = tail.split("\n");
        // 从最后一行开始找非空行
        String lastLine = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i] != null && !lines[i].trim().isEmpty()) {
                lastLine = lines[i].trim();
                break;
            }
        }
        if (lastLine.isEmpty()) {
            return false;
        }
        for (Pattern p : PROMPT_PATTERNS) {
            if (p.matcher(lastLine).find()) {
                return true;
            }
        }
        return false;
    }

    /** 获取 PROMPT_PATTERNS 数量 — 用于测试验证 ≥14 */
    public static int getPromptPatternsCount() {
        return PROMPT_PATTERNS.length;
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /** 启动周期性检查 — 对齐 CC startStallWatchdog setInterval */
    public synchronized void start() {
        if (future != null && !future.isDone()) {
            if (log.isDebugEnabled()) {
                log.debug("StallWatchdog: already started for task {}", taskId);
            }
            return; // 幂等
        }
        lastGrowthTime = System.currentTimeMillis();
        stalled.set(false);
        future = scheduler.scheduleAtFixedRate(this::check, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        log.info("StallWatchdog: started for task {}, threshold={}ms, interval={}ms",
            taskId, stallThresholdMs, checkIntervalMs);
    }

    /** 停止监控 */
    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        scheduler.shutdownNow();
        log.info("StallWatchdog: stopped for task {}", taskId);
    }

    /** 是否检测到停滞 (prompt stall) */
    public boolean isStalled() {
        return stalled.get();
    }

    // ============================================================
    // CC startStallWatchdog 核心逻辑 (LocalShellTask.tsx:52-103)
    // ============================================================

    /**
     * 周期检查 — L1 对齐 CC LocalShellTask.tsx:52-103
     *
     * <p>CC 流程:
     * <ol>
     *   <li>stat 输出文件 offset</li>
     *   <li>若 offset > lastOffset → 有增长 → 重置计时</li>
     *   <li>若 offset 未变且超过 threshold → readTail → looksLikePrompt</li>
     *   <li>若 looksLikePrompt = true → 执行 onStallAction + 标记 stalled + 自动停止 (one-shot)</li>
     * </ol>
     */
    private void check() {
        try {
            long currentOffset = Files.exists(outputFile) ? Files.size(outputFile) : -1;

            if (currentOffset > lastOffset) {
                // CC :58-60 — offset 有增长 → 重置计时
                lastOffset = currentOffset;
                lastGrowthTime = System.currentTimeMillis();
            } else {
                // CC :62-101 — offset 未增长 → 检查是否超过 threshold
                long elapsed = System.currentTimeMillis() - lastGrowthTime;
                if (elapsed >= stallThresholdMs) {
                    // CC :66-78 — 读取尾部 STALL_TAIL_BYTES 字节
                    String tail = readTail(outputFile, STALL_TAIL_BYTES);
                    if (log.isDebugEnabled()) {
                        log.debug("StallWatchdog: task {} no offset growth for {}ms, tail={}bytes",
                            taskId, elapsed, tail != null ? tail.length() : 0);
                    }

                    // CC :80-94 — 若 looksLikePrompt → kill + one-shot
                    if (looksLikePrompt(tail)) {
                        stalled.set(true);
                        log.warn("StallWatchdog: task {} stalled — prompt detected in output tail", taskId);

                        // s13-p1: 调用 kill callback (BackgroundTaskRunner.kill)
                        if (onStallAction != null) {
                            try {
                                onStallAction.run();
                            } catch (Exception e) {
                                log.error("StallWatchdog: onStallAction failed for task {}: {}", taskId, e.getMessage());
                            }
                        }

                        // CC :94 — 自动停止 (one-shot notification)
                        stop();
                    }
                    // CC: 无 prompt → 继续监控, 不标记 stall
                }
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("StallWatchdog: check error for task {}: {}", taskId, e.getMessage());
            }
        }
    }

    /**
     * 公开的 readTail — 对外暴露 STALL_TAIL_BYTES 默认值 (1024 bytes)
     * 对齐 CC tailFile - 让 BackgroundTaskRunner 在 stall 触发时读取相同大小的尾部.
     */
    public static String readTailForTest(String filePath) {
        return readTail(Path.of(filePath), STALL_TAIL_BYTES);
    }

    /**
     * 读取文件尾部 N 字节 — 对齐 CC tailFile
     */
    private static String readTail(Path file, int tailBytes) {
        try {
            long fileSize = Files.size(file);
            if (fileSize == 0) {
                return "";
            }
            long startPos = Math.max(0, fileSize - tailBytes);
            int readLen = (int) Math.min(tailBytes, fileSize);

            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                byte[] buf = new byte[readLen];
                raf.seek(startPos);
                raf.readFully(buf);
                return new String(buf, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("StallWatchdog: failed to read tail of {}: {}", file, e.getMessage());
            }
            return "";
        }
    }
}
