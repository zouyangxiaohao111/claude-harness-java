package com.nexusai.application.agent.permission.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * File Changed Watcher · 对齐 CC utils/hooks/fileChangedWatcher.ts:22-191.
 *
 * <p>L1 行为: 用 {@link WatchService} (chokidar 的 Java 等价) 监听真实文件系统上的
 * cwd 内 hook 配置文件 (.envrc/.env 等), 文件变更 → 触发 FileChanged hooks;
 * cwd 切换 → 触发 CwdChanged hooks + 重起 watcher. H14 从内存 stub (5%) 升级为真实监听.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: initialize / startWatching / updateWatchPaths / onCwdChangedForHooks /
 *       resolveWatchPaths / dispose + setEnvHookNotifier</li>
 *   <li><b>A2 Golden Trace</b>: 监听 tempDir/.envrc → 写文件 → executeFileChangedHooks
 *       (change, path) 被调用; old!=new → executeCwdChangedHooks</li>
 *   <li><b>A3</b>: WatchService + 单线程轮询线程; 事件去抖 (awaitWriteFinish 500ms 近似);
 *       dispose 关闭 service + 线程</li>
 *   <li><b>A4 边界</b>: 无 hook executor → watch 事件仅日志; 同一路径重复注册去重</li>
 *   <li><b>A5 业务场景</b>: hook 配置热加载 (settings.json 的 FileChanged matcher 监听
 *       .envrc/.env), cwd 切换重起 watcher</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS chokidar watch(paths, opts) → Java WatchService.register;
 * TS awaitWriteFinish {stabilityThreshold:500, pollInterval:200} → Java 事件后 500ms
 * 去抖窗口 (mtime 稳定检测, Concern H14-2); TS module-level 状态 → Java 实例字段.
 */
@Component
public final class FileChangedWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileChangedWatcher.class);

    /** chokidar awaitWriteFinish stabilityThreshold=500ms 等价 (CC fileChangedWatcher.ts:72). */
    private static final long AWAIT_WRITE_FINISH_MS = 500L;

    /**
     * env hook 执行结果 · 对齐 CC hooks.ts:4241-4258 executeEnvHooks
     * {@code {results, watchPaths, systemMessages}}（[CCJ-EXEC-05]）。
     *
     * @param watchPaths      hook 结果中的动态监听路径（CC :4253 {@code results.flatMap(r => r.watchPaths ?? [])}）
     * @param systemMessages  hook 结果中的 systemMessage（CC :4254-4257 {@code results.map(r => r.systemMessage).filter(!!)}）
     * @param failureOutputs  失败结果的输出文本（CC fileChangedWatcher.ts:94-97
     *                        {@code !r.succeeded && r.output → notify(output, true)} 的 Java 映射：
     *                        outcome!=SUCCESS 且 blockingError 文本优先、attachment stderr/stdout 次之）
     */
    public record EnvHookResult(
        java.util.List<String> watchPaths,
        java.util.List<String> systemMessages,
        java.util.List<String> failureOutputs
    ) {
        public EnvHookResult {
            watchPaths = watchPaths == null ? List.of() : java.util.List.copyOf(watchPaths);
            systemMessages = systemMessages == null ? List.of() : java.util.List.copyOf(systemMessages);
            failureOutputs = failureOutputs == null ? List.of() : java.util.List.copyOf(failureOutputs);
        }

        /** 空结果 · CC executeEnvHooks 无结果时 {@code {results:[], watchPaths:[], systemMessages:[]}} 等价. */
        public static EnvHookResult empty() {
            return new EnvHookResult(List.of(), List.of(), List.of());
        }
    }

    /** FileChanged/CwdChanged hook 执行器 · 对齐 CC executeFileChangedHooks/executeCwdChangedHooks. */
    public interface HookExecutor {
        /**
         * 执行 FileChanged hooks · 返回 hook 结果联合
         * （CC hooks.ts:4278-4294 executeFileChangedHooks → {results, watchPaths, systemMessages}）。
         *
         * @param path  fileChanged event file_path（全路径，[CCJ-EXEC-04]）
         * @param event 'change' | 'add' | 'unlink'
         * @return env hook 结果（watchPaths 空 = 无动态监听路径）
         */
        EnvHookResult executeFileChangedHooks(String path, String event);

        /**
         * 执行 CwdChanged hooks · 返回 hook 结果联合
         * （CC hooks.ts:4260-4276 executeCwdChangedHooks → {results, watchPaths, systemMessages}）。
         *
         * @param oldCwd 旧 cwd
         * @param newCwd 新 cwd
         * @return env hook 结果（watchPaths 空 = 无动态监听路径）
         */
        EnvHookResult executeCwdChangedHooks(String oldCwd, String newCwd);
    }

    /**
     * 生产接线执行器 · 委托 HookRegistry.
     *
     * <p>对齐 CC executeFileChangedHooks (hooks.js) → Java {@link HookRegistry#fireFileChanged}
     * + executeCwdChangedHooks → {@link HookRegistry#executeEvent(HookEvent.cwdChanged)}.
     * sessionId 用 null (watcher 是模块级, 无会话归因 — CC 同样).
     *
     * <p>[H14 v3 Gap④ + CCJ-EXEC-05] 返回值 = {@link EnvHookResult}
     * （watchPaths 供 dynamicWatchPaths 回填，CC fileChangedWatcher.ts:86-89 / 160-161；
     * systemMessages/failureOutputs 供 notify 上抛，CC :90-97）。HookRegistry 的
     * fireFileChangedCollectingWatchPaths / executeCwdChangedHooksCollectingWatchPaths 返回该 record。
     */
    public static HookExecutor hookRegistryExecutor(HookRegistry registry) {
        return new HookExecutor() {
            @Override
            public EnvHookResult executeFileChangedHooks(String path, String event) {
                return registry.fireFileChangedCollectingWatchPaths(path, event, null);
            }

            @Override
            public EnvHookResult executeCwdChangedHooks(String oldCwd, String newCwd) {
                return registry.executeCwdChangedHooksCollectingWatchPaths(oldCwd, newCwd, null);
            }
        };
    }

    private volatile HookExecutor hookExecutor;
    private volatile BiConsumer<String, Boolean> notifier; // (text, isError) → setEnvHookNotifier

    /** [H14-FIX] 配置快照 (生产接线注入) · 供 {@link #initialize} 自动解析监听路径. */
    private volatile HooksConfigSnapshot hooksConfigSnapshot;

    private final List<String> watchPaths = new CopyOnWriteArrayList<>();
    private final ExecutorService watcherThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-changed-watcher");
        t.setDaemon(true);
        return t;
    });

    private volatile WatchService watchService;
    private volatile boolean running = false;
    private volatile String currentCwd;

    /** Spring 无参构造器 · {@link #setHookRegistry} 注入执行器. */
    public FileChangedWatcher() {
        this(null, null);
    }

    public FileChangedWatcher(HookExecutor hookExecutor) {
        this(hookExecutor, null);
    }

    /** 完整构造器 (notifier 可为 null → 仅日志). */
    public FileChangedWatcher(HookExecutor hookExecutor, BiConsumer<String, Boolean> notifier) {
        this.hookExecutor = hookExecutor;
        this.notifier = notifier;
    }

    /** 生产接线 · 对齐 CC module-level notifier (fileChangedWatcher.ts:22-26). */
    public void setEnvHookNotifier(BiConsumer<String, Boolean> cb) {
        this.notifier = cb;
    }

    /** 生产接线 · 注入 HookRegistry 构建执行器 (CC 模块级 executor 依赖 hooks 引擎). */
    @Autowired(required = false)
    public void setHookRegistry(HookRegistry registry) {
        if (registry != null) {
            this.hookExecutor = hookRegistryExecutor(registry);
        }
    }

    /** 生产接线 · 注入配置快照, 供 {@link #initialize} 自动解析监听路径. */
    @Autowired(required = false)
    public void setHooksConfigSnapshot(HooksConfigSnapshot snapshot) {
        this.hooksConfigSnapshot = snapshot;
    }

    /**
     * 幂等初始化 · 对齐 CC initializeFileChangedWatcher (fileChangedWatcher.ts:28-46).
     *
     * <p>[H14-FIX] 自动解析: 若 watchPaths 为空且配置快照已注入, 从快照解析
     * FILE_CHANGED matcher 监听路径 (对齐 CC :48-65 resolveWatchPaths + :65-66
     * "if (paths.length === 0) return"). 生产无需手动 setWatchPaths.
     *
     * <p>[⊕-9 评估 + CCJ-EXEC-10] 对齐 CC hasEnvHooks 门控 (fileChangedWatcher.ts:34-36):
     * 快照已注入且 <b>CwdChanged/FileChanged 均无 hooks</b> 时直接 return, 不启动 watcher
     * （CC 无 env hooks 时不注册 cleanup 也不 startWatching；[CCJ-EXEC-10] 门控由原
     * FILE_CHANGED 单事件扩展为双事件）。快照未注入 (测试手动 setWatchPaths 路径) 维持原行为。
     */
    public synchronized void initialize(String cwd) {
        if (running) {
            return;
        }
        currentCwd = cwd;
        if (watchPaths.isEmpty() && hooksConfigSnapshot != null) {
            java.util.Map<HookEventType, List<HookMatcher>> config =
                hooksConfigSnapshot.getHooksConfigFromSnapshot();
            // CC :34-36 hasEnvHooks = (CwdChanged?.length ?? 0) > 0 || (FileChanged?.length ?? 0) > 0
            List<HookMatcher> fileChangedMatchers = config != null
                ? config.get(HookEventType.FILE_CHANGED) : null;
            List<HookMatcher> cwdChangedMatchers = config != null
                ? config.get(HookEventType.CWD_CHANGED) : null;
            if ((fileChangedMatchers == null || fileChangedMatchers.isEmpty())
                    && (cwdChangedMatchers == null || cwdChangedMatchers.isEmpty())) {
                if (log.isDebugEnabled()) {
                    log.debug("FileChangedWatcher 无 CwdChanged/FileChanged hooks, 跳过启动 (对齐 CC hasEnvHooks 门控)");
                }
                return;
            }
            List<String> paths = resolveWatchPaths(config, cwd);
            if (log.isDebugEnabled()) {
                log.debug("FileChangedWatcher 自动解析监听路径: {} (cwd={})", paths, cwd);
            }
            setWatchPaths(paths);
        }
        running = true;
        log.info("FileChangedWatcher 初始化 (cwd={}, paths={})", cwd, watchPaths.size());
        startWatching();
    }

    /** 手动设定监听路径 (测试 / 生产 resolveWatchPaths 结果注入). */
    public void setWatchPaths(List<String> paths) {
        watchPaths.clear();
        if (paths != null) {
            for (String p : paths) {
                if (p != null && !p.isBlank() && !watchPaths.contains(p)) {
                    watchPaths.add(p);
                }
            }
        }
    }

    /**
     * 解析 matcher 监听路径 · 对齐 CC resolveWatchPaths (fileChangedWatcher.ts:48-65).
     *
     * <p>FileChanged matcher 字段是 "cwd 内待监听文件名, {@code |} 分隔"; 每个 name
     * join 到 currentCwd. 供配置驱动初始化使用.
     *
     * @param fileChangedMatchers FileChanged 事件的 matcher 列表
     * @return 绝对路径列表 (去重)
     */
    public List<String> resolveWatchPaths(List<HookMatcher> fileChangedMatchers, String cwd) {
        Set<String> result = new java.util.LinkedHashSet<>();
        if (fileChangedMatchers != null) {
            for (HookMatcher m : fileChangedMatchers) {
                if (m == null || m.matcher() == null || m.matcher().isBlank()) continue;
                for (String name : m.matcher().split("\\|")) {
                    String trimmed = name.trim();
                    if (trimmed.isEmpty()) continue;
                    result.add(trimmed.startsWith("/") || trimmed.matches("^[A-Za-z]:.*")
                        ? trimmed : Path.of(cwd, trimmed).toString());
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** 对齐 CC resolveWatchPaths 便捷重载 (注入 cwd + matcher map). */
    public List<String> resolveWatchPaths(java.util.Map<HookEventType, List<HookMatcher>> config,
                                          String cwd) {
        List<HookMatcher> matchers = config != null ? config.get(HookEventType.FILE_CHANGED) : null;
        return resolveWatchPaths(matchers, cwd);
    }

    /** 启动 WatchService 监听 (对齐 CC startWatching :67-78；[CCJ-EXEC-04] 文件级过滤). */
    public synchronized void startWatching() {
        if (watchPaths.isEmpty()) {
            return;
        }
        running = true;
        watchedFileNamesByDir.clear();
        watchedDirPaths.clear();
        try {
            WatchService service = FileSystems.getDefault().newWatchService();
            watchService = service;
            for (String p : watchPaths) {
                Path abs = Path.of(p).toAbsolutePath().normalize();
                if (java.nio.file.Files.isDirectory(abs)) {
                    // 目录 watch: 监听目录自身（直接子项全匹配 · chokidar 对目录路径的 Java 简化）
                    watchedDirPaths.add(abs);
                    registerDir(service, abs);
                    continue;
                }
                // 文件 watch: 注册父目录 + 记录文件名（[CCJ-EXEC-04] 父目录去重 Map —
                //   旧实现同名父目录重复注册产生重复 key + 目录内任意文件变更均触发）
                Path dir = abs.getParent();
                if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
                    continue;
                }
                watchedFileNamesByDir.computeIfAbsent(dir, k -> new java.util.LinkedHashSet<>())
                    .add(abs.getFileName().toString());
                registerDir(service, dir);
            }
            watcherThread.execute(this::pollLoop);
            log.info("FileChangedWatcher 开始监听 {} 个路径 ({} 个父目录)", watchPaths.size(), watchedFileNamesByDir.size());
        } catch (IOException e) {
            log.warn("FileChangedWatcher 启动失败 (WatchService): {}", e.toString());
        }
    }

    /** 目录 → 待监听文件名集合（文件 watch 的父目录注册 · [CCJ-EXEC-04] 去重 Map）. */
    private final java.util.Map<Path, java.util.Set<String>> watchedFileNamesByDir =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 目录 watch 集合（目录自身注册 · 直接子项全匹配）. */
    private final java.util.Set<Path> watchedDirPaths =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void registerDir(WatchService service, Path dir) throws IOException {
        dir.register(service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE);
    }

    /** 轮询 WatchService 事件 · 对齐 chokidar 'change'/'add'/'unlink' (CC :75-77). */
    private void pollLoop() {
        // [H14 v3 Gap④] 绑定本次 pollLoop 到启动时的 WatchService：
        //   restartWatching 会关闭旧 service 并新建 service + 提交新 pollLoop。若不绑定，
        //   旧 pollLoop 在旧 service 关闭后 catch(Exception) 继续 busy-spin（take() 抛
        //   NPE/ClosedWatchServiceException），占住单线程 executor，新 pollLoop 永不运行 →
        //   dynamicWatchPaths 回填后重起 watcher 实际不监听。绑定后旧线程检测到
        //   watchService 已更换即退出，让出新线程给新 pollLoop。
        WatchService service = watchService;
        if (service == null) {
            return;
        }
        while (running && watchService == service) {
            try {
                WatchKey key = service.take();
                Path dirPath = (Path) key.watchable();   // 注册时的绝对父目录 / 目录本身
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changed = pathEvent.context();
                    if (changed == null) continue;
                    // [CCJ-EXEC-04] 文件级过滤：仅命中 watchPaths 才触发（目录 watch = 直接子项全匹配）
                    boolean allChildren = watchedDirPaths.contains(dirPath);
                    java.util.Set<String> names = watchedFileNamesByDir.get(dirPath);
                    if (!allChildren && (names == null || !names.contains(changed.toString()))) {
                        continue;
                    }
                    String eventName = switch (event.kind().name()) {
                        case "ENTRY_CREATE" -> "add";
                        case "ENTRY_MODIFY" -> "change";
                        case "ENTRY_DELETE" -> "unlink";
                        default -> "change";
                    };
                    // [CCJ-EXEC-04] 全路径契约：dir.resolve(changed).normalize()（旧实现传 basename）
                    handleFileEvent(dirPath.resolve(changed).normalize().toString(), eventName);
                }
                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.nio.file.ClosedWatchServiceException cwse) {
                // 旧 service 已关闭 (restart/dispose) → 本线程退出, 让出新线程
                break;
            } catch (Exception e) {
                if (running && watchService == service) {
                    log.warn("FileChangedWatcher 轮询异常: {}", e.toString());
                } else {
                    // watchService 已更换 → 本线程退出, 新 pollLoop 接管
                    break;
                }
            }
        }
    }

    /**
     * 文件事件 → executeFileChangedHooks · 对齐 CC handleFileEvent (:80-106).
     *
     * <p>CC 直接触发 executeFileChangedHooks (异步). Java 端 hookExecutor 同步调用
     * (调用方 HookRegistry.fireFileChanged 内部已异步); awaitWriteFinish 500ms 去抖
     * 近似 (Concern H14-2): 事件后 sleep 500ms 稳定窗, 防写一半触发.
     *
     * <p>[H14 v3 Gap④] 对齐 CC fileChangedWatcher.ts:86-89 — hook 结果带 watchPaths 时
     * 动态扩展监听 (updateWatchPaths)。
     *
     * <p>[CCJ-EXEC-05] 通知链对齐 CC :86-106：watchPaths>0 → updateWatchPaths；
     * systemMessages → notify(false)；失败结果输出 → notify(true)；执行异常 →
     * notify(err, true)（CC :99-105 catch）。
     *
     * @param path  文件全路径（[CCJ-EXEC-04] file_path 契约）
     * @param event 'change' | 'add' | 'unlink'
     */
    void handleFileEvent(String path, String event) {
        try {
            // awaitWriteFinish 近似: stabilityThreshold 500ms (CC :72)
            Thread.sleep(AWAIT_WRITE_FINISH_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        log.info("FileChangedWatcher 事件: event={} path={}", event, path);
        if (hookExecutor != null) {
            EnvHookResult result;
            try {
                result = hookExecutor.executeFileChangedHooks(path, event);
            } catch (Exception e) {
                // CC :99-105 catch(e) → notify(errorMessage, true)
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("FileChangedWatcher executeFileChangedHooks 异常: {}", msg, e);
                if (notifier != null) {
                    notifier.accept(msg, true);
                }
                return;
            }
            // CC :87-89 — if (watchPaths.length > 0) updateWatchPaths(watchPaths)
            if (result.watchPaths() != null && !result.watchPaths().isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("FileChangedWatcher 动态扩展监听路径: {}", result.watchPaths());
                }
                updateWatchPaths(result.watchPaths());
            }
            // CC :90-92 — systemMessages → notify(msg, false)
            for (String msg : result.systemMessages()) {
                if (notifier != null) {
                    notifier.accept(msg, false);
                }
            }
            // CC :93-97 — !r.succeeded && r.output → notify(output, true)
            for (String out : result.failureOutputs()) {
                if (notifier != null) {
                    notifier.accept(out, true);
                }
            }
        } else if (notifier != null) {
            notifier.accept("FileChanged: " + event + " " + path, false);
        }
    }


    /**
     * 动态更新监听路径 · 对齐 CC updateWatchPaths (:108-120): 排序比较, 变化则重起 watcher.
     */
    public synchronized void updateWatchPaths(List<String> newPaths) {
        if (newPaths == null || newPaths.isEmpty()) {
            return;
        }
        List<String> sorted = new ArrayList<>(newPaths);
        sorted.sort(String::compareTo);
        if (sorted.equals(watchPaths.stream().sorted().toList())) {
            return;
        }
        log.info("FileChangedWatcher 动态更新路径: {} → {}", watchPaths.size(), newPaths.size());
        setWatchPaths(newPaths);
        restartWatching();
    }

    /** 重起 watcher · 对齐 CC restartWatching (:122-131). */
    private synchronized void restartWatching() {
        stopWatching();
        if (running) {
            startWatching();
        }
    }

    /**
     * cwd 切换 · 对齐 CC onCwdChangedForHooks (:133-175).
     *
     * <p>old != new → [CCJ-EXEC-10] hasEnvHooks 门控 → clearCwdEnvFiles +
     * executeCwdChangedHooks（catch → notify + 空结果）→ systemMessages/failureOutputs
     * notify → dynamicWatchPaths 回填 → 重读配置后重起 watcher。
     *
     * <p>[CCJ-EXEC-10] hasEnvHooks 门控（CC :140-144）：快照非空且 CwdChanged/FileChanged
     * matcher 均空 → return（cwd 切换零副作用）；快照 null（测试/手动模式）跳过门控维持原行为。
     *
     * <p>[H14 v3 Gap④] 补齐 CC 两个缺失动作:
     * <ol>
     *   <li>{@code clearCwdEnvFiles()} (CC :147, sessionEnvironment.ts:33-46) — 清空
     *       filechanged/cwdchanged-hook-*.sh 环境文件, 防旧 cwd env 残留泄漏到新 cwd hook 执行。</li>
     *   <li>{@code dynamicWatchPaths = hookResult.watchPaths} (CC :160-161) — 从 CwdChanged hook
     *       结果回填动态监听路径, 重启后监听新路径。</li>
     * </ol>
     */
    public synchronized void onCwdChangedForHooks(String oldCwd, String newCwd) {
        if (oldCwd != null && oldCwd.equals(newCwd)) {
            return;
        }
        // [CCJ-EXEC-10] hasEnvHooks 门控 · CC :140-144 重读快照再判（快照 null = 测试/手动模式跳过门控）
        if (hooksConfigSnapshot != null) {
            java.util.Map<HookEventType, List<HookMatcher>> config =
                hooksConfigSnapshot.getHooksConfigFromSnapshot();
            List<HookMatcher> fileChanged = config != null
                ? config.get(HookEventType.FILE_CHANGED) : null;
            List<HookMatcher> cwdChanged = config != null
                ? config.get(HookEventType.CWD_CHANGED) : null;
            boolean hasEnvHooks = (cwdChanged != null && !cwdChanged.isEmpty())
                || (fileChanged != null && !fileChanged.isEmpty());
            if (!hasEnvHooks) {
                if (log.isDebugEnabled()) {
                    log.debug("FileChangedWatcher 无 CwdChanged/FileChanged hooks, cwd 切换零副作用 (对齐 CC :141-144)");
                }
                return;
            }
        }
        log.info("FileChangedWatcher cwd 切换: {} → {}", oldCwd, newCwd);
        // [H14 v3 Gap④] CC fileChangedWatcher.ts:147 await clearCwdEnvFiles()
        clearCwdEnvFiles();
        EnvHookResult hookResult = EnvHookResult.empty();
        if (hookExecutor != null) {
            try {
                // CC :148-161 — executeCwdChangedHooks → {results, watchPaths, systemMessages}
                hookResult = hookExecutor.executeCwdChangedHooks(oldCwd, newCwd);
            } catch (Exception e) {
                // CC :148-159 catch(e) → notify(errorMessage, true) + 空结果
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("FileChangedWatcher executeCwdChangedHooks 异常: {}", msg, e);
                if (notifier != null) {
                    notifier.accept(msg, true);
                }
                hookResult = EnvHookResult.empty();
            }
            // CC :162-164 — systemMessages → notify(msg, false)
            for (String msg : hookResult.systemMessages()) {
                if (notifier != null) {
                    notifier.accept(msg, false);
                }
            }
            // CC :165-169 — !r.succeeded && r.output → notify(output, true)
            for (String out : hookResult.failureOutputs()) {
                if (notifier != null) {
                    notifier.accept(out, true);
                }
            }
        }
        // 对齐 CC :172-174: initialized 时重起 watcher (matcher 路径基于新 cwd 重解析)
        java.util.List<String> dynamicWatchPaths = hookResult.watchPaths();
        if (running && newCwd != null && hooksConfigSnapshot != null) {
            List<String> paths = resolveWatchPaths(
                hooksConfigSnapshot.getHooksConfigFromSnapshot(), newCwd);
            // [H14 v3 Gap④] CC :64 resolveWatchPaths 合并 dynamicWatchPaths — hook 结果回填
            java.util.Set<String> merged = new java.util.LinkedHashSet<>(paths);
            if (dynamicWatchPaths != null) {
                merged.addAll(dynamicWatchPaths);
            }
            paths = new ArrayList<>(merged);
            if (log.isDebugEnabled()) {
                log.debug("FileChangedWatcher cwd 切换后重解析监听路径: {} (含 dynamic {})",
                    paths, dynamicWatchPaths);
            }
            setWatchPaths(paths);
        }
        restartWatching();
    }

    /**
     * [H14 v3 Gap④] clearCwdEnvFiles 等价 · 对齐 CC sessionEnvironment.ts:33-46.
     *
     * <p>清空 {@code nexusai-hooks} 临时目录下 {@code filechanged-hook-*.sh} /
     * {@code cwdchanged-hook-*.sh} 环境文件 (写空串), 防止旧 cwd 的 hook env 残留泄漏到
     * 新 cwd 的 hook 执行。Java 无 session env cache（getHookEnvFilePath 是占位路径），
     * 本方法实现等价机制：清空已存在的 cwd-hook env 文件。
     */
    static void clearCwdEnvFiles() {
        java.io.File hooksDir = new java.io.File(
            System.getProperty("java.io.tmpdir"), "nexusai-hooks");
        java.io.File[] files = hooksDir.listFiles();
        if (files == null) {
            return;
        }
        for (java.io.File f : files) {
            String name = f.getName();
            if ((name.startsWith("filechanged-hook-") || name.startsWith("cwdchanged-hook-"))
                    && name.endsWith(".sh")) {
                try {
                    java.nio.file.Files.writeString(f.toPath(), "");
                } catch (java.io.IOException e) {
                    if (log.isWarnEnabled()) {
                        log.warn("FileChangedWatcher 清空 cwd env 文件失败: {} err={}", name, e.toString());
                    }
                }
            }
        }
    }

    /** 关闭 watcher · 对齐 CC dispose (:177-187). */
    public void dispose() {
        running = false;
        stopWatching();
        watcherThread.shutdownNow();
    }

    private void stopWatching() {
        WatchService service = watchService;
        watchService = null;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                log.warn("FileChangedWatcher 关闭 WatchService 失败: {}", e.toString());
            }
        }
    }

    /**
     * Spring 容器关闭收尾 · [⊕-10 评估] 对齐 CC registerCleanup(async () => dispose())
     * (fileChangedWatcher.ts:41-43): 容器销毁时关闭 WatchService + 轮询线程, 防资源泄漏。
     * dispose 幂等 — 未启动时 stopWatching 对 null service 直接返回。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        if (log.isDebugEnabled()) {
            log.debug("FileChangedWatcher @PreDestroy shutdown: running={}", running);
        }
        dispose();
    }
}
