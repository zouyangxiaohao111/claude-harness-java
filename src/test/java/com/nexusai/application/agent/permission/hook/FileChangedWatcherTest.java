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
 * [Session H14] FileChangedWatcher 真实 FS 监听 · 对齐 CC fileChangedWatcher.ts:22-191.
 *
 * <p>WHY (规则九 · 测试验证意图): CC 用 chokidar 监听真实文件系统 (persistent +
 * ignoreInitial + awaitWriteFinish 500ms), 文件变更触发 FileChanged hooks — 这是
 * "hook 监听 .envrc/.env 配置变更 → 热加载" 的核心机制. Java 端旧实现是内存 Map +
 * 手动 notifyChange (5%), 无真实监听. 本测试锁定:
 * <ul>
 *   <li><b>真实 FS 监听</b>: 文件写入 → WatchService 事件 → executeFileChangedHooks (CC :67-78)</li>
 *   <li><b>resolveWatchPaths</b>: matcher ".envrc|.env" 按 {@code |} 拆分 + join cwd (CC :48-65)</li>
 *   <li><b>动态 watch paths</b>: updateWatchPaths → 重起 watcher (CC :108-131)</li>
 *   <li><b>cwd 切换</b>: old!=new → executeCwdChangedHooks (CC :133-175)</li>
 * </ul>
 */
@DisplayName("[H14] FileChangedWatcher 真实 FS 监听")
class FileChangedWatcherTest {

    @TempDir
    Path tempDir;

    private FileChangedWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.dispose();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 1. resolveWatchPaths — matcher 按 | 拆分 + join cwd
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("resolveWatchPaths: '.envrc|.env' 按 | 拆分并 join cwd (CC :48-65)")
    void resolveWatchPaths_splitsMatcherAndJoinsCwd() {
        // WHY: CC matcher 字段是 "cwd 内待监听文件名, | 分隔" (CC :53-54);
        //       拆分后 join(currentCwd, name). 若合并错误, 监听路径指向错误位置 → hook 永不触发.
        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);

        List<String> paths = watcher.resolveWatchPaths(
            java.util.Map.of(HookEventType.FILE_CHANGED,
                List.of(new HookMatcher(".envrc|.env", List.of()))),
            tempDir.toString());

        assertThat(paths)
            .as("matcher 按 | 拆分 + join cwd")
            .containsExactlyInAnyOrder(
                tempDir.resolve(".envrc").toString(),
                tempDir.resolve(".env").toString());
    }

    // ════════════════════════════════════════════════════════════════
    // 2b. [CCJ-EXEC-04] 文件级过滤 + 全路径契约
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[CCJ-EXEC-04] 同目录无关文件变更不触发（chokidar 文件级监听语义）")
    void realFsWatch_unrelatedFileInSameDir_doesNotFire() throws Exception {
        // WHY: CC chokidar 监听具体文件（fileChangedWatcher.ts:67-78）——只有被监听文件变更
        //       才触发 executeFileChangedHooks。旧 Java WatchService 注册父目录 → 目录内任意
        //       文件变更均触发（误触发 hook、副作用×N）。
        Path watched = tempDir.resolve(".envrc");
        Files.writeString(watched, "export A=1");
        Path unrelated = tempDir.resolve("unrelated.log");
        Files.writeString(unrelated, "noise");

        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);
        watcher.setWatchPaths(List.of(watched.toString()));
        watcher.startWatching();

        // 写无关文件 → 必须不触发（等 2 个 awaitWriteFinish 窗口确认静默）
        Files.writeString(unrelated, "noise2");
        Thread.sleep(AWAIT_WRITE_FINISH_MS * 2);
        assertThat(executor.fileChanges)
            .as("同目录无关文件变更不得触发 executeFileChangedHooks（文件级过滤）")
            .isEmpty();

        // 写被监听文件 → 触发（正控）
        CountDownLatch changeFired = executor.awaitFileChange();
        Files.writeString(watched, "export A=2");
        assertThat(changeFired.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("[CCJ-EXEC-04] file_path = 被监听文件全路径（非 basename）")
    void realFsWatch_filePath_isFullPath() throws Exception {
        // WHY: CC handleFileEvent(path) 的 path 是 chokidar 事件全路径（fileChangedWatcher.ts:80-85）
        //       → file_path 数据契约=全路径。旧 Java 传 basename（WatchEvent.context）。
        //       HookMatcherEngine FILE_CHANGED 用 basename(file_path) 匹配（:219-221），
        //       全路径不影响匹配，仅数据契约修正。
        Path watched = tempDir.resolve(".envrc");
        Files.writeString(watched, "export A=1");

        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);
        watcher.setWatchPaths(List.of(watched.toString()));
        watcher.startWatching();

        CountDownLatch changeFired = executor.awaitFileChange();
        Files.writeString(watched, "export A=2");
        assertThat(changeFired.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(executor.fileChanges)
            .anySatisfy(c -> {
                assertThat(c.path())
                    .as("file_path 必须是全路径（dir.resolve(changed).normalize()）")
                    .isEqualTo(watched.toAbsolutePath().normalize().toString());
            });
    }

    @Test
    @DisplayName("[CCJ-EXEC-05] env hook 结果通知链：systemMessages→notify(false)、失败输出→notify(true)")
    void handleFileEvent_notifiesSystemMessagesAndFailures() throws Exception {
        // WHY: CC fileChangedWatcher.ts:86-106 — systemMessages → notify(msg,false)；
        //       !r.succeeded && r.output → notify(output,true)；异常 → notify(err,true)。
        //       旧 Java HookExecutor 只返回 watchPaths → 通知静默丢弃。
        Path watched = tempDir.resolve(".envrc");
        Files.writeString(watched, "export A=1");
        java.util.concurrent.CopyOnWriteArrayList<String> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CopyOnWriteArrayList<Boolean> errorFlags = new java.util.concurrent.CopyOnWriteArrayList<>();
        RecordingHookExecutor executor = new RecordingHookExecutor();
        executor.fileResult = new FileChangedWatcher.EnvHookResult(
            List.of(), List.of("env reloaded"), List.of("hook failed: boom"));
        watcher = new FileChangedWatcher(executor, (text, isError) -> {
            notifications.add(text);
            errorFlags.add(isError);
        });
        watcher.setWatchPaths(List.of(watched.toString()));
        watcher.startWatching();

        CountDownLatch changeFired = executor.awaitFileChange();
        Files.writeString(watched, "export A=2");
        assertThat(changeFired.await(10, TimeUnit.SECONDS)).isTrue();

        // 通知在 executeFileChangedHooks 返回后由 poll 线程投递（latch 先于通知）→ 轮询等待投递完成
        long deadline = System.currentTimeMillis() + 10_000;
        while (notifications.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(notifications).contains("env reloaded", "hook failed: boom");
        assertThat(errorFlags.get(notifications.indexOf("env reloaded"))).isFalse();
        assertThat(errorFlags.get(notifications.indexOf("hook failed: boom"))).isTrue();
    }

    private static final long AWAIT_WRITE_FINISH_MS = 500L;

    // ════════════════════════════════════════════════════════════════
    // 2. 真实 FS 监听 — 文件写入 → executeFileChangedHooks
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("监听文件写入 → executeFileChangedHooks 被调用 (CC :67-78)")
    void realFsWatch_fileChange_firesFileChangedHooks() throws Exception {
        // WHY: 真实 FS 监听是本次升级核心 — 旧实现 (内存 Map + notifyChange) 在文件系统
        //       上根本没有 watcher. 验证: 初始化 watcher 监听 tempDir/.envrc → 写入文件 →
        //       executeFileChangedHooks 收到 (change, path).
        Path watched = tempDir.resolve(".envrc");
        Files.writeString(watched, "export A=1");

        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);
        watcher.setWatchPaths(List.of(watched.toString()));
        watcher.startWatching();

        CountDownLatch changeFired = executor.awaitFileChange();

        // 触发真实文件系统事件
        Files.writeString(watched, "export A=2");

        assertThat(changeFired.await(10, TimeUnit.SECONDS))
            .as("WatchService 必须在文件变更后触发 executeFileChangedHooks")
            .isTrue();
        assertThat(executor.fileChanges)
            .anySatisfy(change -> {
                assertThat(change.path()).endsWith(".envrc");
                assertThat(change.event()).isEqualTo("change");
            });
    }

    // ════════════════════════════════════════════════════════════════
    // 3. 动态 watch paths — updateWatchPaths → 重起 watcher
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("updateWatchPaths 变化 → 重起 watcher 监听新路径 (CC :108-131)")
    void updateWatchPaths_newPaths_restartsWatcher() throws Exception {
        // WHY: hook 输出可返回 watchPaths 动态扩展监听 (CC :86-89); 路径变化必须
        //       重起 watcher, 否则新增监听不生效.
        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);
        Path initial = tempDir.resolve("a.txt");
        Files.writeString(initial, "a");
        watcher.setWatchPaths(List.of(initial.toString()));
        watcher.startWatching();

        // 动态更新: 新增 b.txt 监听
        Path added = tempDir.resolve("b.txt");
        Files.writeString(added, "b");
        watcher.updateWatchPaths(List.of(initial.toString(), added.toString()));

        CountDownLatch changeFired = executor.awaitFileChange();
        Files.writeString(added, "b2");

        assertThat(changeFired.await(10, TimeUnit.SECONDS))
            .as("动态添加的路径必须被新 watcher 监听到")
            .isTrue();
        assertThat(executor.fileChanges)
            .anySatisfy(c -> assertThat(c.path()).endsWith("b.txt"));
    }

    // ════════════════════════════════════════════════════════════════
    // 4. cwd 切换 — old != new → executeCwdChangedHooks
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("onCwdChangedForHooks: old!=new → executeCwdChangedHooks (CC :133-175)")
    void onCwdChangedForHooks_differentCwd_firesCwdChangedHooks() {
        // WHY: cwd 切换时 CC 重读配置 + clearCwdEnvFiles + executeCwdChangedHooks +
        //       重起 watcher (CC :133-175). Java 端必须触发 CwdChanged hook.
        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);

        watcher.onCwdChangedForHooks("/old/cwd", tempDir.toString());

        assertThat(executor.cwdChanges)
            .as("old!=new 必须触发 executeCwdChangedHooks")
            .containsExactly(new CwdChange("/old/cwd", tempDir.toString()));
    }

    @Test
    @DisplayName("onCwdChangedForHooks: old==new → no-op (CC :137)")
    void onCwdChangedForHooks_sameCwd_noop() {
        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);

        watcher.onCwdChangedForHooks("/same", "/same");

        assertThat(executor.cwdChanges).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════
    // 5. [H14-FIX] 生产接线 — initialize 自动解析快照路径 + cwd 切换重解析
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造带 FILE_CHANGED matcher 的配置快照 (对齐 CC getHooksConfigFromSnapshot).
     */
    private static HooksConfigSnapshot snapshotWithFileChangedMatcher(String matcher) {
        HooksSettings settings = new HooksSettings();
        settings.loadFromSource(HookSource.USER_SETTINGS.name(),
            List.of(new IndividualHookConfig(
                HookEventType.FILE_CHANGED,
                new CommandHook("echo changed", null, null, null, null, null, null, null),
                matcher,
                HookSource.USER_SETTINGS,
                null)));
        return new HooksConfigSnapshot(settings);
    }

    @Test
    @DisplayName("initialize 自动解析快照中的监听路径并开始真实监听 (无需手动 setWatchPaths)")
    void initialize_snapshot_resolvesWatchPaths() throws Exception {
        // WHY: 生产接线: initialize 从 HooksConfigSnapshot 自动解析监听路径 (H14-FIX)，
        //       无需手动 setWatchPaths；快照无 CwdChanged/FileChanged hooks 时门控跳过（CC :34-36）。
        Path watched = tempDir.resolve(".envrc");
        Files.writeString(watched, "export A=1");

        HooksConfigSnapshot snapshot = snapshotWithFileChangedMatcher(".envrc");
        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);
        watcher.setHooksConfigSnapshot(snapshot);

        watcher.initialize(tempDir.toString());
        CountDownLatch changeFired = executor.awaitFileChange();
        Files.writeString(watched, "export A=2");

        assertThat(changeFired.await(10, TimeUnit.SECONDS))
            .as("initialize 必须自动解析快照中的监听路径并开始真实监听 (无需手动 setWatchPaths)")
            .isTrue();
        assertThat(executor.fileChanges)
            .anySatisfy(c -> assertThat(c.path()).endsWith(".envrc"));
    }

    @Test
    @DisplayName("[H14-FIX] initialize 无快照时幂等启动 (running 不再重复) ")
    void initialize_withoutSnapshot_isIdempotent() {
        // WHY: 生产 LlmAgentLoop.run() 每次会话都会调 initialize(cwd);
        //      无快照 (非 Spring / 手动 new) 时也要幂等 (running flag 防重复 WatchService).
        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);

        watcher.initialize(tempDir.toString());
        watcher.initialize(tempDir.toString());

        // 二次 initialize 不抛异常 (幂等), 且 watcher 已 running
        // (通过 dispose 不抛异常验证生命周期健康)
        watcher.dispose();
        watcher = null;
    }

    @Test
    @DisplayName("[H14-FIX] onCwdChangedForHooks 重解析新 cwd 的监听路径 (CC :172-174)")
    void onCwdChangedForHooks_reResolvesWatchPathsAgainstNewCwd() throws Exception {
        // WHY: 生产 cwd 切换 (EnterWorktree) 后, 新 cwd 下的 .envrc/.env 才应被监听.
        //      CC :172-174 initialized 时基于新 cwd 重解析路径后重起 watcher.
        HooksConfigSnapshot snapshot = snapshotWithFileChangedMatcher(".envrc");
        RecordingHookExecutor executor = new RecordingHookExecutor();
        watcher = new FileChangedWatcher(executor);
        watcher.setHooksConfigSnapshot(snapshot);

        // 第一次初始化: 监听 oldCwd/.envrc
        Path oldDir = tempDir.resolve("old");
        java.nio.file.Files.createDirectories(oldDir);
        Path oldEnv = oldDir.resolve(".envrc");
        Files.writeString(oldEnv, "export OLD=1");
        watcher.initialize(oldDir.toString());

        // cwd 切换 → 新目录, 应监听新目录 .envrc
        Path newDir = tempDir.resolve("new");
        java.nio.file.Files.createDirectories(newDir);
        Path newEnv = newDir.resolve(".envrc");
        Files.writeString(newEnv, "export NEW=1");
        watcher.onCwdChangedForHooks(oldDir.toString(), newDir.toString());

        CountDownLatch changeFired = executor.awaitFileChange();
        Files.writeString(newEnv, "export NEW=2");

        assertThat(changeFired.await(10, TimeUnit.SECONDS))
            .as("cwd 切换后 watcher 必须重新监听新 cwd 下的配置文件")
            .isTrue();
        // WatchEvent.context() 只返回文件名 (HookMatcherEngine.java:139 FileChanged 用 basename 匹配),
        // 断言事件确实在新 cwd 监听建立后被触发.
        assertThat(executor.fileChanges)
            .anySatisfy(c -> assertThat(c.path()).endsWith(".envrc"));
    }

    // ════════════════════════════════════════════════════════════════
    // 假执行器
    // ════════════════════════════════════════════════════════════════

    /** 记录 FileChanged/CwdChanged hook 调用的假执行器. */
    private static final class RecordingHookExecutor implements FileChangedWatcher.HookExecutor {
        final List<FileChange> fileChanges = new CopyOnWriteArrayList<>();
        final List<CwdChange> cwdChanges = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch changeLatch = new CountDownLatch(1);
        // [CCJ-EXEC-05] 可注入的 hook 结果（null = 空结果）
        volatile FileChangedWatcher.EnvHookResult fileResult;
        volatile FileChangedWatcher.EnvHookResult cwdResult;

        CountDownLatch awaitFileChange() {
            changeLatch = new CountDownLatch(1);
            return changeLatch;
        }

        @Override
        public FileChangedWatcher.EnvHookResult executeFileChangedHooks(String path, String event) {
            fileChanges.add(new FileChange(path, event));
            changeLatch.countDown();
            return fileResult != null ? fileResult : FileChangedWatcher.EnvHookResult.empty();
        }

        @Override
        public FileChangedWatcher.EnvHookResult executeCwdChangedHooks(String oldCwd, String newCwd) {
            cwdChanges.add(new CwdChange(oldCwd, newCwd));
            return cwdResult != null ? cwdResult : FileChangedWatcher.EnvHookResult.empty();
        }
    }

    private record FileChange(String path, String event) {}

    private record CwdChange(String oldCwd, String newCwd) {}
}
