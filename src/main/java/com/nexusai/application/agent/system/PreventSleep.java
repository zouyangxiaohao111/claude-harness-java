package com.nexusai.application.agent.system;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS 防休眠服务 · 对齐 CC services/preventSleep.ts.
 *
 * <p>L1 语义: macOS 上阻止 idle sleep,让 Claude 长任务不被打断.
 *            使用 caffeinate -i -t 300 (5分钟) spawn 子进程,4 分钟间隔重启 (留 buffer).
 *            refCount 引用计数:startPreventSleep inc,stopPreventSleep dec;为 0 时停止;
 *            forceStopPreventSleep 强制停止 (cleanup 用).
 *            仅在 process.platform === 'darwin' 时生效,其他平台 no-op.
 *            unref 子进程避免 keep Node alive;SIGKILL 立即终止.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 个公开 API (startPreventSleep/stopPreventSleep/forceStopPreventSleep);
 *       refCount 引用计数;restartInterval 重启定时器;
 *       注入式 processSpawner (testable);cleanup registry 调用.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — start: refCount 0→1 → spawn caffeinate + 启动 interval;
 *       stop: refCount 1→0 → clear interval + kill caffeinate;
 *       forceStop: refCount = 0 → 立即 stop;
 *       重启 interval: 4 分钟后 refCount>0 → kill + respawn.</li>
 *   <li><b>A3</b>: 状态机: IDLE (refCount=0, no process) → ACTIVE (refCount≥1, process running);
 *       PLATFORM_OFF (非 darwin, no-op 状态).</li>
 *   <li><b>A4</b>: 已经在跑 → startPreventSleep 不重复 spawn;refCount=0 时 stopPreventSleep 不操作;
 *       非 darwin → 全部 no-op;spawn 抛错 → catch + caffeinateProcess=null.</li>
 *   <li><b>A5</b>: 真实场景 — Claude 处理长 API 请求 → startPreventSleep → caffeinate 阻止 idle sleep;
 *       4 分钟后 interval 重启 caffeinate (避免 -t 过期);
 *       任务完成 → stopPreventSleep → 解除阻止 sleep.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `process.platform === 'darwin'` → 注入式 BooleanSupplier;
 *                    TS `child_process.spawn` → 注入式 ProcessSpawner (no real subprocess);
 *                    TS `setInterval(...).unref()` → Java ScheduledExecutorService;
 *                    TS cleanup registry → 注入式 CleanupRegistrar.
 */
public final class PreventSleep {

    private static final Logger log = LoggerFactory.getLogger(PreventSleep.class);
    private static final long CAFFEINATE_TIMEOUT_SECONDS = 300;
    private static final long RESTART_INTERVAL_MS = 4 * 60 * 1000;

    private final BooleanSupplier isMacOs;
    private final ProcessSpawner processSpawner;
    private final IntervalScheduler intervalScheduler;
    private final Consumer<String> debugLogger;
    private final CleanupRegistrar cleanupRegistrar;

    private int refCount = 0;
    private SpawnedProcess caffeinateProcess = null;
    private IntervalHandle restartInterval = null;
    private boolean cleanupRegistered = false;

    public PreventSleep(BooleanSupplier isMacOs,
                          ProcessSpawner processSpawner,
                          IntervalScheduler intervalScheduler,
                          Consumer<String> debugLogger,
                          CleanupRegistrar cleanupRegistrar) {
        this.isMacOs = Objects.requireNonNull(isMacOs);
        this.processSpawner = Objects.requireNonNull(processSpawner);
        this.intervalScheduler = Objects.requireNonNull(intervalScheduler);
        this.debugLogger = Objects.requireNonNull(debugLogger);
        this.cleanupRegistrar = Objects.requireNonNull(cleanupRegistrar);
    }

    /** CC startPreventSleep — 引用计数 inc,首调用时 spawn. */
    public void startPreventSleep() {
        refCount++;
        if (refCount == 1) {
            spawnCaffeinate();
            startRestartInterval();
        }
    }

    /** CC stopPreventSleep — 引用计数 dec,归零时 stop. */
    public void stopPreventSleep() {
        if (refCount > 0) {
            refCount--;
        }
        if (refCount == 0) {
            stopRestartInterval();
            killCaffeinate();
        }
    }

    /** CC forceStopPreventSleep — 强制停止 (cleanup). */
    public void forceStopPreventSleep() {
        refCount = 0;
        stopRestartInterval();
        killCaffeinate();
    }

    /** 当前 refCount (for test). */
    public int refCount() { return refCount; }

    /** 是否 caffeinate 在跑 (for test). */
    public boolean isCaffeinateRunning() { return caffeinateProcess != null; }

    private void startRestartInterval() {
        if (!isMacOs.getAsBoolean()) {
            return;
        }
        if (restartInterval != null) {
            return;
        }
        restartInterval = intervalScheduler.scheduleAtFixedRate(() -> {
            if (refCount > 0) {
                debugLogger.accept("Restarting caffeinate to maintain sleep prevention");
                killCaffeinate();
                spawnCaffeinate();
            }
        }, RESTART_INTERVAL_MS);
    }

    private void stopRestartInterval() {
        if (restartInterval != null) {
            restartInterval.cancel();
            restartInterval = null;
        }
    }

    private void spawnCaffeinate() {
        if (!isMacOs.getAsBoolean()) {
            return;
        }
        if (caffeinateProcess != null) {
            return;
        }

        if (!cleanupRegistered) {
            cleanupRegistered = true;
            cleanupRegistrar.register(this::forceStopPreventSleep);
        }

        try {
            caffeinateProcess = processSpawner.spawn(
                "caffeinate",
                new String[]{"-i", "-t", String.valueOf(CAFFEINATE_TIMEOUT_SECONDS)},
                new SpawnOptions(true /* detached */, true /* unref */)
            );
            caffeinateProcess.onExit(() -> {
                caffeinateProcess = null;
            });
            caffeinateProcess.onError(err -> {
                debugLogger.accept("caffeinate spawn error: " + err);
                caffeinateProcess = null;
            });
            debugLogger.accept("Started caffeinate to prevent sleep");
        } catch (Exception e) {
            debugLogger.accept("caffeinate spawn exception: " + e.getMessage());
            caffeinateProcess = null;
        }
    }

    private void killCaffeinate() {
        if (caffeinateProcess != null) {
            SpawnedProcess proc = caffeinateProcess;
            caffeinateProcess = null;
            try {
                proc.kill();
                debugLogger.accept("Stopped caffeinate, allowing sleep");
            } catch (Exception e) {
                // process may have already exited — silently ignore
            }
        }
    }

    /** Spawned process abstraction. */
    public interface SpawnedProcess {
        void kill();
        void onExit(Runnable callback);
        void onError(Consumer<String> callback);
    }

    /** Spawn options. */
    public record SpawnOptions(boolean detached, boolean unref) {}

    /** Process spawner (注入). */
    @FunctionalInterface
    public interface ProcessSpawner {
        SpawnedProcess spawn(String command, String[] args, SpawnOptions options);
    }

    /** Interval handle (cancel-able). */
    public interface IntervalHandle {
        void cancel();
    }

    /** Interval scheduler (注入). */
    @FunctionalInterface
    public interface IntervalScheduler {
        IntervalHandle scheduleAtFixedRate(Runnable task, long periodMs);
    }

    /** Cleanup registrar (注入). */
    @FunctionalInterface
    public interface CleanupRegistrar {
        void register(Runnable cleanup);
    }
}
