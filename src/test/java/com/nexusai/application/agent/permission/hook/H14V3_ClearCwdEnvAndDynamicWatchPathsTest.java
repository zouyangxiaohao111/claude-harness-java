package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H14 v3 Gap④] clearCwdEnvFiles + dynamicWatchPaths 回填.
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: v2 对抗复验残留缺口④ "clearCwdEnvFiles +
 * dynamicWatchPaths 回填缺失"。CC onCwdChangedForHooks (fileChangedWatcher.ts:133-175)
 * 做了两件 Java 端缺失的事：
 * <ol>
 *   <li><b>clearCwdEnvFiles()</b> (sessionEnvironment.ts:33-46) — 清空
 *       {@code filechanged-hook-*.sh} / {@code cwdchanged-hook-*.sh} 环境文件，防止旧 cwd 的
 *       hook 环境残留泄漏到新 cwd 的 hook 执行。</li>
 *   <li><b>dynamicWatchPaths 回填</b> — handleFileEvent (fileChangedWatcher.ts:86-89) 拿到
 *       hook 结果中的 {@code watchPaths} 后调 {@code updateWatchPaths(watchPaths)}；onCwdChangedForHooks
 *       (:160-161) 把 hook 结果 {@code watchPaths} 存为 {@code dynamicWatchPaths} 并重启。</li>
 * </ol>
 *
 * <p><b>本测试验证</b>:
 * <ul>
 *   <li>onCwdChangedForHooks → cwd-hook 环境文件被清空（Java 等价：清空 nexusai-hooks 临时目录下
 *       filechanged/cwdchanged-hook-*.sh）</li>
 *   <li>handleFileEvent → hook 结果返回 watchPaths → 动态扩展监听（updateWatchPaths）</li>
 * </ul>
 *
 * @see FileChangedWatcher
 * @since H14 v3 残留缺口修复
 */
@DisplayName("[H14 v3 Gap④] clearCwdEnvFiles + dynamicWatchPaths 回填")
class H14V3_ClearCwdEnvAndDynamicWatchPathsTest {

    @TempDir
    Path tempDir;

    private FileChangedWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.dispose();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. clearCwdEnvFiles — cwd-hook 环境文件被清空
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("onCwdChangedForHooks → 清空 filechanged/cwdchanged-hook-*.sh 环境文件")
    void onCwdChangedForHooks_clearsCwdEnvFiles() throws Exception {
        // WHY: CC onCwdChangedForHooks (fileChangedWatcher.ts:147) await clearCwdEnvFiles()
        //      — 清空 cwd 相关 hook 环境文件，防止旧 cwd 残留 env 泄漏到新 cwd 的 hook 执行。
        //      Java 无 session env cache → 实现等价机制：清空 nexusai-hooks 临时目录下
        //      filechanged-hook-*.sh / cwdchanged-hook-*.sh。
        Path hooksDir = Path.of(System.getProperty("java.io.tmpdir"), "nexusai-hooks");
        Files.createDirectories(hooksDir);
        Path fileChanged = hooksDir.resolve("filechanged-hook-0.sh");
        Path cwdChanged = hooksDir.resolve("cwdchanged-hook-0.sh");
        Files.writeString(fileChanged, "export STALE=1");
        Files.writeString(cwdChanged, "export STALE=1");

        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);

        watcher.onCwdChangedForHooks("/old/cwd", tempDir.toString());

        assertThat(Files.readString(fileChanged))
            .as("filechanged-hook-*.sh 必须被清空 (CC clearCwdEnvFiles, 防旧 cwd env 残留)")
            .isEmpty();
        assertThat(Files.readString(cwdChanged))
            .as("cwdchanged-hook-*.sh 必须被清空 (CC clearCwdEnvFiles, 防旧 cwd env 残留)")
            .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. dynamicWatchPaths 回填 — handleFileEvent 拿 hook watchPaths → 动态扩展监听
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("handleFileEvent → hook 返回 watchPaths → 动态扩展监听新路径")
    void handleFileEvent_backfillsDynamicWatchPaths() throws Exception {
        // WHY: CC handleFileEvent (fileChangedWatcher.ts:86-89) —
        //      executeFileChangedHooks().then(({results, watchPaths}) => {
        //        if (watchPaths.length > 0) updateWatchPaths(watchPaths)
        //      })  — hook 结果可动态扩展监听路径 (L3 嵌套 memory 触发源)。
        //      Java HookExecutor 返回 void → watchPaths 静默丢失，动态扩展缺失。
        Path watched = tempDir.resolve("initial.txt");
        Files.writeString(watched, "a");

        Path dynamic = tempDir.resolve("dynamic.txt");
        Files.writeString(dynamic, "d");

        RecordingHookExecutor executor = new RecordingHookExecutor();
        executor.watchPathsToReturn = List.of(dynamic.toString());
        watcher = new FileChangedWatcher(executor);
        watcher.setWatchPaths(List.of(watched.toString()));
        watcher.startWatching();

        // 触发 FileChanged 事件 (写已监听文件) → handleFileEvent 应拿到 watchPaths → updateWatchPaths
        CountDownLatch fired = executor.awaitFileChange();
        Files.writeString(watched, "a2");
        assertThat(fired.await(10, TimeUnit.SECONDS)).isTrue();

        // 动态扩展后，新路径必须被监听到 (写 dynamic.txt → 触发 FileChanged)。
        // handleFileEvent 在 poll 线程上 countDown 后才 updateWatchPaths/重启 watcher —
        // 首次 countDown 与重启完成之间存在竞态窗口，故用重试写入 (模拟真实"文件随时间变化").
        CountDownLatch dynamicFired = executor.awaitFileChange();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean detected = false;
        while (System.nanoTime() < deadline && !detected) {
            Files.writeString(dynamic, "d2");
            detected = dynamicFired.await(300, TimeUnit.MILLISECONDS);
        }
        assertThat(detected)
            .as("hook 返回的 dynamic watchPaths 必须被回填并监听 (CC handleFileEvent :86-89)")
            .isTrue();
        assertThat(executor.fileChanges)
            .anySatisfy(c -> assertThat(c.path()).endsWith("dynamic.txt"));
    }

    @Test
    @DisplayName("onCwdChangedForHooks → hook 返回 watchPaths → 存为 dynamicWatchPaths 并重启")
    void onCwdChangedForHooks_backfillsDynamicWatchPaths() throws Exception {
        // WHY: CC onCwdChangedForHooks (fileChangedWatcher.ts:160-161) —
        //      dynamicWatchPaths = hookResult.watchPaths → 重启 watcher 后监听新路径。
        //      Java 端仅重解析 matcher 路径，从不回填 hook 结果 watchPaths。
        Path oldDir = tempDir.resolve("old");
        java.nio.file.Files.createDirectories(oldDir);
        Path oldEnv = oldDir.resolve(".envrc");
        Files.writeString(oldEnv, "export OLD=1");

        Path newDir = tempDir.resolve("new");
        java.nio.file.Files.createDirectories(newDir);
        Path newEnv = newDir.resolve(".envrc");
        Files.writeString(newEnv, "export NEW=1");
        Path dynamic = tempDir.resolve("hook-dynamic.txt");
        Files.writeString(dynamic, "d");

        RecordingHookExecutor executor = new RecordingHookExecutor();
        executor.cwdWatchPathsToReturn = List.of(dynamic.toString());
        watcher = new FileChangedWatcher(executor);
        HooksConfigSnapshot snapshot = snapshotWithFileChangedMatcher(".envrc");
        watcher.setHooksConfigSnapshot(snapshot);
        watcher.initialize(oldDir.toString());

        watcher.onCwdChangedForHooks(oldDir.toString(), newDir.toString());

        CountDownLatch fired = executor.awaitFileChange();
        Files.writeString(dynamic, "d2");
        assertThat(fired.await(10, TimeUnit.SECONDS))
            .as("onCwdChangedForHooks 必须回填 hook 结果的 watchPaths 并重启监听 (CC :160-161)")
            .isTrue();
        assertThat(executor.fileChanges)
            .anySatisfy(c -> assertThat(c.path()).endsWith("hook-dynamic.txt"));
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static HooksConfigSnapshot snapshotWithFileChangedMatcher(String matcher) {
        HooksSettings settings = new HooksSettings();
        settings.loadFromSource(HookSource.USER_SETTINGS.name(),
            List.of(new IndividualHookConfig(
                HookEventType.FILE_CHANGED,
                new CommandHook("echo changed", null, null, null, null, null, null, null),
                matcher,
                HookSource.USER_SETTINGS,
                null)));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        return snapshot;
    }

    /** 记录 FileChanged/CwdChanged hook 调用 + 可返回 watchPaths 的假执行器. */
    private static final class RecordingHookExecutor implements FileChangedWatcher.HookExecutor {
        final List<FileChange> fileChanges = new CopyOnWriteArrayList<>();
        final List<CwdChange> cwdChanges = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch changeLatch = new CountDownLatch(1);
        volatile List<String> watchPathsToReturn = List.of();
        volatile List<String> cwdWatchPathsToReturn = List.of();

        CountDownLatch awaitFileChange() {
            changeLatch = new CountDownLatch(1);
            return changeLatch;
        }

        @Override
        public FileChangedWatcher.EnvHookResult executeFileChangedHooks(String path, String event) {
            fileChanges.add(new FileChange(path, event));
            changeLatch.countDown();
            return new FileChangedWatcher.EnvHookResult(watchPathsToReturn, List.of(), List.of());
        }

        @Override
        public FileChangedWatcher.EnvHookResult executeCwdChangedHooks(String oldCwd, String newCwd) {
            cwdChanges.add(new CwdChange(oldCwd, newCwd));
            return new FileChangedWatcher.EnvHookResult(cwdWatchPathsToReturn, List.of(), List.of());
        }
    }

    private record FileChange(String path, String event) {}

    private record CwdChange(String oldCwd, String newCwd) {}
}
