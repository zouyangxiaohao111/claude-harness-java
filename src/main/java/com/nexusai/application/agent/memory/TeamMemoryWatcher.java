package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.team.TeamMemorySyncTypes.SyncState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Team Memory 文件 watcher · 对齐 CC {@code Open-ClaudeCode/src/services/teamMemorySync/watcher.ts}.
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：{@code startTeamMemoryWatcher} watcher.ts:252-305 ——
 * TEAMMEM 门 :253 + isTeamMemoryEnabled/isTeamMemorySyncAvailable 门 :256 + github.com remote 门
 * :259-266（早退防 no_repo 噪音循环）+ createSyncState :268 + <b>初始 pull 先行</b> :272-291（pull 的
 * 盘写不触发 schedulePush）+ <b>无条件启动</b> watcher :296（空目录 watch 廉价，fresh repo 无 bootstrap
 * 死区）+ {@code tengu_team_mem_sync_started} :298-304；{@code notifyTeamMemoryWrite} :314-319
 * （PostToolUse hooks 调用，防 fs.watch 漏事件）；{@code schedulePush} :132-145（2s debounce；
 * pushInProgress 时重新排队）；{@code executePush} :84-127（成功 → hasPendingChanges=false；permanent
 * failure → pushSuppressedReason 抑制）；{@code isPermanentFailure} :61-73（no_oauth/no_repo 或 4xx
 * 除 409/429）；{@code stopTeamMemoryWatcher} :327-352（清 timer + close watcher + await in-flight +
 * flush pending）。
 *
 * <p>DEL-M-18：旧实现 emit/listenerCount/readAllFiles（EventEmitter 式 API）删除 —— CC watcher 无这些。
 *
 * <p>[IMP-D-5 · OPD-CM5-D-10] 运行期错误 warn 日志：eventLoop key.reset() false（目录被删 / 文件系统
 * 错误 → key 失效）记 warn 后退出循环（对齐 CC {@code watcher.on('error')} watcher.ts:209-214）；
 * InterruptedException / ClosedWatchServiceException 属预期关闭信号（CC watcher.close() 不触发 error）
 * 保持静默。
 *
 * <p><b>门控接线</b>：[IMP-CM-07] teamMemoryEnabled = 真实 first-party OAuth 可用性判定
 * （{@code TeamMemoryHttpClient.isFirstPartyOAuthAvailable}，对齐 CC isUsingOAuth）——OAuth 未登录/未授权时
 * 整链惰性（不炸），登录后 watcher 可启动。启动走 {@link ApplicationRunner}（SkillChangeDetector 先例）。
 */
@Component
public class TeamMemoryWatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TeamMemoryWatcher.class);
    /** CC DEBOUNCE_MS=2000（watcher.ts:35）：最后一次变更后等 2s 再 push。 */
    private static final long DEBOUNCE_MS = 2000;

    private final TeamMemorySyncService syncService;
    private final TeamMemPaths teamMemPaths;
    private final TeamMemoryHttpClient httpClient;
    private final Supplier<String> baseUrlSupplier;

    /** 遥测注入 · null → 不发射（对齐 CC logEvent 可空上下文）。 */
    private volatile com.nexusai.application.agent.telemetry.Telemetry telemetry;

    /** watcher 拥有的 sync state —— 所有 sync 操作共享（CC watcher.ts:76）。 */
    private volatile SyncState syncState = null;
    private volatile WatchService watchService = null;
    private volatile boolean watcherStarted = false;
    private volatile boolean pushInProgress = false;
    private volatile boolean hasPendingChanges = false;
    private volatile String pushSuppressedReason = null;
    private volatile ScheduledFuture<?> debounceFuture = null;
    private volatile Future<?> currentPushFuture = null;

    private final ScheduledExecutorService scheduler;

    @org.springframework.beans.factory.annotation.Autowired
    public TeamMemoryWatcher(TeamMemorySyncService syncService,
                             TeamMemPaths teamMemPaths,
                             TeamMemoryHttpClient httpClient) {
        this(syncService, teamMemPaths, httpClient, TeamMemoryWatcher::defaultBaseUrl);
    }

    /** 测试构造器（注入 baseUrl supplier）。 */
    public TeamMemoryWatcher(TeamMemorySyncService syncService,
                             TeamMemPaths teamMemPaths,
                             TeamMemoryHttpClient httpClient,
                             Supplier<String> baseUrlSupplier) {
        this.syncService = syncService;
        this.teamMemPaths = teamMemPaths;
        this.httpClient = httpClient;
        this.baseUrlSupplier = baseUrlSupplier;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "team-memory-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /** CC TEAM_MEMORY_SYNC_URL || BASE_API_URL 等价（index.ts:164-166）。 */
    static String defaultBaseUrl() {
        String override = System.getenv("TEAM_MEMORY_SYNC_URL");
        return override != null && !override.isEmpty() ? override : "https://api.anthropic.com";
    }

    /** 注入遥测（tengu_team_mem_sync_started / tengu_team_mem_push_suppressed ·
     *   watcher.ts:298/112）· Spring @Component 自动装配（required=false 容错）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTelemetry(com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /** 遥测双发射 · CC original: {@code logEvent}（watcher.ts:112/298）。telemetry 未注入 → 静默跳过。 */
    private void emitTelemetry(String eventName, java.util.Map<String, ?> attributes) {
        if (telemetry == null) {
            return;
        }
        java.util.Map<String, Object> attrs = attributes == null
            ? java.util.Map.of() : new java.util.HashMap<>(attributes);
        telemetry.recordEvent(eventName, attrs);
        telemetry.logOTelEvent(eventName, attrs);
    }

    @Override
    public void run(ApplicationArguments args) {
        startTeamMemoryWatcher();
    }

    /**
     * 永久失败判定 · CC original: {@code isPermanentFailure}（watcher.ts:61-73）。
     * no_oauth/no_repo（请求前客户端检查，无状态码）；4xx 除 409/429（404 缺 repo / 413 太多条目 /
     * 403 权限；409 瞬时冲突，下次 pull 后 fresh push 可成功；429 限流由 watcher backoff 处理）。
     */
    public static boolean isPermanentFailure(TeamMemoryHttpClient.PushResult r) {
        if (r == null) {
            return false;
        }
        if ("no_oauth".equals(r.errorType()) || "no_repo".equals(r.errorType())) {
            return true;
        }
        if (r.httpStatus() != null && r.httpStatus() >= 400 && r.httpStatus() < 500
                && r.httpStatus() != 409 && r.httpStatus() != 429) {
            return true;
        }
        return false;
    }

    /**
     * 启动 team memory sync 系统 · CC original: {@code startTeamMemoryWatcher}（watcher.ts:252-305）。
     * 三闸（feature('TEAMMEM') 编译开关 / isTeamMemoryEnabled 运行时开关 / OAuth）+ github.com remote 早退
     * → createSyncState → 初始 pull 先行 → 无条件启动文件 watcher → 记 started 事件。
     * IMP-CM-09（OPD-CM3-11/B04）：双门控拆分后 watcher 同时检查编译开关（watcher.ts:253
     * {@code if (!feature('TEAMMEM')) return}）与运行时开关（watcher.ts:256 isTeamMemoryEnabled）。
     */
    public void startTeamMemoryWatcher() {
        if (!teamMemPaths.isTeamMemFeatureEnabled()
                || !teamMemPaths.isTeamMemoryEnabled()
                || !httpClient.isAuthAvailable()) {
            return;
        }
        // B9 接线：CC watcher.ts:259 getGithubRepo() → resolveGitDir(getCwd())；进程级无会话 → sessionId=null
        String repoSlug = GitRemoteResolver.getGithubRepo(Paths.get(CwdResolution.getCwd(null)));
        if (repoSlug == null) {
            if (log.isDebugEnabled()) {
                log.debug("team-memory-watcher: no github.com remote, skipping sync");
            }
            return;
        }

        this.syncState = SyncState.create();

        // 初始 pull（先于 watcher 启动，其盘写不触发 schedulePush）
        boolean initialPullSuccess = false;
        int initialFilesPulled = 0;
        boolean serverHasContent = false;
        try {
            TeamMemorySyncService.PullResult pull =
                syncService.pullTeamMemory(syncState, baseUrlSupplier.get(), false);
            initialPullSuccess = pull.success();
            serverHasContent = pull.entryCount() > 0;
            if (pull.success() && pull.filesWritten() > 0) {
                initialFilesPulled = pull.filesWritten();
                if (log.isInfoEnabled()) {
                    log.info("team-memory-watcher: initial pull got {} files", initialFilesPulled);
                }
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("team-memory-watcher: initial pull failed: {}", e.getMessage());
            }
        }

        // 无条件启动 watcher（空目录 watch 廉价；懒启动造成 fresh repo bootstrap 死区，CC :293-295）
        startFileWatcher(teamMemPaths.getTeamMemPath());

        if (log.isInfoEnabled()) {
            log.info("tengu_team_mem_sync_started initial_pull_success={} initial_files_pulled={} "
                + "server_has_content={}", initialPullSuccess, initialFilesPulled, serverHasContent);
        }
        // CC watcher.ts:298-304 tengu_team_mem_sync_started（initial_pull_success/watcher_started 恒 true/
        //   server_has_content；initial_files_pulled 保持 dashboard 连续性）
        emitTelemetry("tengu_team_mem_sync_started", java.util.Map.of(
            "initial_pull_success", initialPullSuccess,
            "initial_files_pulled", initialFilesPulled,
            "watcher_started", true,
            "server_has_content", serverHasContent));
    }

    /**
     * 启动文件 watcher · CC original: {@code startFileWatcher}（watcher.ts:167-229）。mkdir recursive
     * 幂等；Java WatchService 非递归，对 team 目录 + 已有子目录递归注册；新目录经 ENTRY_CREATE 递归
     * 注册其子树（IMP-D-4 · OPD-CM5-D-08，对齐 fs.watch recursive，mkdir -p 竞态修复）；daemon 线程
     * poll。suppression 未清时 stat 区分 unlink（ENOENT → 清 suppression）。
     */
    void startFileWatcher(String teamDirStr) {
        if (watcherStarted) {
            return;
        }
        watcherStarted = true;
        try {
            Path teamDir = Paths.get(teamDirStr);
            Files.createDirectories(teamDir);
            watchService = teamDir.getFileSystem().newWatchService();
            registerRecursive(teamDir);
            Thread t = new Thread(this::eventLoop, "team-memory-fs-watch");
            t.setDaemon(true);
            t.start();
            if (log.isDebugEnabled()) {
                log.debug("team-memory-watcher: watching {}", teamDirStr);
            }
        } catch (Exception e) {
            // fs.watch 同步抛（mkdir 与 watch 间目录被删的竞态 / EACCES）。watcherStarted 已置真，
            // notifyTeamMemoryWrite 的显式 schedulePush 路径仍工作（CC :218-226 注释）
            if (log.isWarnEnabled()) {
                log.warn("team-memory-watcher: failed to watch {}: {}", teamDirStr, e.getMessage());
            }
        }
    }

    private void registerRecursive(Path dir) {
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isDirectory).forEach(d -> {
                try {
                    d.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException e) {
                    if (log.isWarnEnabled()) {
                        log.warn("team-memory-watcher: register failed for {}: {}", d, e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("team-memory-watcher: walk failed for {}: {}", dir, e.getMessage());
            }
        }
    }

    private void eventLoop() {
        // [IMP-D-5 · OPD-CM5-D-10] 运行期错误 warn 日志对齐 CC watcher.on('error')（watcher.ts:209-214）：
        //   Java WatchService 无异步 error 事件通道，运行期 watch 故障经 key.reset() false（key 失效 =
        //   目录被删 / 文件系统错误）显式暴露，记 warn 后退出循环。InterruptedException /
        //   ClosedWatchServiceException 属预期关闭信号（CC watcher.close() 不触发 error），静默 return。
        while (true) {
            java.nio.file.WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            // WatchService event context() 相对各注册目录（teamDir 或某子目录的 WatchKey）—— 用
            // key.watchable() 定位该 key 实际注册的目录再 resolve filename，嵌套子目录事件才含完整
            // 路径（CC watcher.ts:191 join(teamDir, filename) 语义，防 167K 事件场景）。旧实现
            // resolve(teamDir) 缺子目录段 → 嵌套事件 child 指向不存在路径 → 抑制态 stat 误清
            // pushSuppressedReason（CM-D2 △-③，IMP-CM-10）。
            Path watchableDir = (Path) key.watchable();
            for (java.nio.file.WatchEvent<?> event : key.pollEvents()) {
                Object ctx = event.context();
                String filename = ctx == null ? null : ctx.toString();
                if (filename == null) {
                    schedulePush();
                    continue;
                }
                // ENTRY_CREATE 目录 → 递归注册其子树（对齐 fs.watch recursive 语义，watcher.ts:179-181；
                // IMP-D-4 · OPD-CM5-D-08 mkdir -p 竞态修复：新目录内已建的嵌套子目录一并注册，防
                // b 在 a 注册前创建导致的事件丢失；registerRecursive 已按目录粒度记 warn）
                Path child = watchableDir.resolve(filename);
                if (Files.isDirectory(child)) {
                    registerRecursive(child);
                }
                if (pushSuppressedReason != null) {
                    // suppression 仅由 unlink 清除（too-many-entries 恢复动作）。fs.watch 不区分
                    // unlink/add/write —— stat 区分：ENOENT → 文件没了 → 清 suppression（CC :187-204）
                    try {
                        Files.readAttributes(child, java.nio.file.attribute.BasicFileAttributes.class);
                    } catch (java.nio.file.NoSuchFileException nsfe) {
                        if (pushSuppressedReason != null) {
                            if (log.isInfoEnabled()) {
                                log.info("team-memory-watcher: unlink cleared suppression (was: {})",
                                    pushSuppressedReason);
                            }
                            pushSuppressedReason = null;
                        }
                        schedulePush();
                        continue;
                    } catch (IOException e) {
                        // stat 其他错误 —— 忽略
                    }
                    continue;
                }
                schedulePush();
            }
            if (!key.reset()) {
                // CC watcher.ts:209-214 watcher.on('error') → warn log：key 失效 = fs.watch 运行期
                // 错误（目录被删 / 文件系统卸载），WatchService 该 key 已死无法继续监听 —— 记 warn 后
                // 退出循环（watcherStarted 仍 true，notifyTeamMemoryWrite 显式 push 路径继续工作）
                if (log.isWarnEnabled()) {
                    log.warn("team-memory-watcher: fs.watch error: watch key invalid "
                        + "(directory removed or filesystem error), stopping event loop");
                }
                return;
            }
        }
    }

    /**
     * debounce push：等写入稳定后 push 一次 · CC original: {@code schedulePush}（watcher.ts:132-145）。
     * pushInProgress 时重新排队（防事件风暴期间堆叠）。
     */
    void schedulePush() {
        if (pushSuppressedReason != null) {
            return;
        }
        hasPendingChanges = true;
        if (debounceFuture != null) {
            debounceFuture.cancel(false);
        }
        debounceFuture = scheduler.schedule(() -> {
            if (pushInProgress) {
                schedulePush();
                return;
            }
            currentPushFuture = scheduler.submit(this::executePush);
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** 执行 push · CC original: {@code executePush}（watcher.ts:84-127）。push 对盘只读（delta+probe，无 merge 写），
     * 无需事件抑制 —— push 期间到达的编辑命中 schedulePush()，debounce 在本次 push 完成后重新武装。 */
    private void executePush() {
        SyncState state = syncState;
        if (state == null) {
            return;
        }
        pushInProgress = true;
        try {
            TeamMemoryHttpClient.PushResult result =
                syncService.pushTeamMemory(state, baseUrlSupplier.get());
            if (result.success()) {
                hasPendingChanges = false;
                if (result.filesUploaded() > 0 && log.isInfoEnabled()) {
                    log.info("team-memory-watcher: pushed {} files", result.filesUploaded());
                }
            } else {
                if (log.isWarnEnabled()) {
                    log.warn("team-memory-watcher: push failed: {}", result.error());
                }
                // [IMP-D-2 · OPD-CM5-D-06] errorType `?? 'unknown'` 兜底对齐 CC（watcher.ts:104-107）。
                //   CC 真源：`httpStatus !== undefined ? \`http_${httpStatus}\` : (errorType ?? 'unknown')`。
                //   补 'unknown' 兜底消除 D1→D2 契约依赖：若 TeamMemorySyncService/HttpClient 未来新增
                //   失败路径漏赋 errorType 且 httpStatus 为空，抑制仍生效（不再产生 null → 抑制失效 +
                //   每次失败重复发射遥测）。当前不可达性见 CM-D2 探查报告 §7-⑤。
                if (isPermanentFailure(result) && pushSuppressedReason == null) {
                    pushSuppressedReason = result.httpStatus() != null
                        ? "http_" + result.httpStatus()
                        : (result.errorType() != null ? result.errorType() : "unknown");
                    if (log.isWarnEnabled()) {
                        log.warn("team-memory-watcher: suppressing retry until next unlink or session restart ({})",
                            pushSuppressedReason);
                    }
                    // CC watcher.ts:112-116 tengu_team_mem_push_suppressed（reason + status）
                    if (telemetry != null) {
                        java.util.Map<String, Object> attrs =
                            new java.util.HashMap<>(java.util.Map.of("reason", pushSuppressedReason));
                        if (result.httpStatus() != null) {
                            attrs.put("status", result.httpStatus());
                        }
                        telemetry.recordEvent("tengu_team_mem_push_suppressed", attrs);
                        telemetry.logOTelEvent("tengu_team_mem_push_suppressed", attrs);
                    }
                }
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("team-memory-watcher: push error: {}", e.getMessage());
            }
        } finally {
            pushInProgress = false;
            currentPushFuture = null;
        }
    }

    /**
     * team memory 文件写入时调用（PostToolUse hooks）· CC original: {@code notifyTeamMemoryWrite}
     * （watcher.ts:314-319）。fs.watch 漏事件时显式 schedulePush —— watcher 启动同一 tick 写入的
     * 文件可能不触发事件；部分平台合并快速连续写。若 watcher 触发，debounce 只是重置。
     */
    public void notifyTeamMemoryWrite() {
        if (syncState == null) {
            return;
        }
        schedulePush();
    }

    /**
     * 停止 watcher 并 flush pending · CC original: {@code stopTeamMemoryWatcher}（watcher.ts:327-352）。
     * 清 debounce timer + close watcher + await in-flight push + flush 未推的 pending（best-effort）。
     */
    public void stopTeamMemoryWatcher() {
        if (debounceFuture != null) {
            debounceFuture.cancel(false);
            debounceFuture = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("team-memory-watcher: close failed: {}", e.getMessage());
                }
            }
            watchService = null;
        }
        if (currentPushFuture != null) {
            try {
                currentPushFuture.get();
            } catch (Exception e) {
                // 关闭期错误忽略
            }
            currentPushFuture = null;
        }
        // flush 已 debounce 但未推的 pending（CC :345-351 best-effort）
        // TMS-06（EV-TMS-40）：flush 后<b>不重置</b> hasPendingChanges —— CC stopTeamMemoryWatcher
        // （watcher.ts:345-351）flush 只是尽力推一次，pending 标记保持（关闭窗口内新变更仍待下次
        // 会话启动补推；旧实现额外置 false 属微差，已删）。
        if (hasPendingChanges && syncState != null && pushSuppressedReason == null) {
            try {
                syncService.pushTeamMemory(syncState, baseUrlSupplier.get());
            } catch (Exception e) {
                // 关闭期可能被 kill —— best-effort
            }
        }
    }

    /**
     * Spring 容器关闭回调 · CC registerCleanup(stopTeamMemoryWatcher)（watcher.ts:228）。
     * 关闭时 flush pending 变更（best-effort）。对齐 SkillChangeDetector:553 / FileChangedWatcher:475
     * @PreDestroy 先例；daemon 线程 + 进程退出兜底之上提供显式关闭路径（DRIFT-8/10，OPD-R2-TMS-06）。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        stopTeamMemoryWatcher();
    }

    /** 测试注入 sync state（对齐 CC _resetWatcherStateForTesting，watcher.ts:365-378）。 */
    public void resetForTesting(SyncState state) {
        resetForTesting(state, false, null);
    }

    /**
     * 重置 watcher 模块状态并注入测试参数 · CC original: {@code _resetWatcherStateForTesting}
     * （watcher.ts:365-378）。opts: {syncState?, skipWatcher?, pushSuppressedReason?}。
     *
     * <p>[IMP-D-3 · OPD-CM5-D-07] 补齐签名：新增 skipWatcher / pushSuppressedReason 注入 + 重置
     * debounce 与 watchService（对齐 CC :370-371 重置 debounceTimer/watcher）。CC 仅置引用 null 不
     * clearTimeout/close；Java 侧额外 cancel/close 以真正消除旧测试 2s debounce 任务跨测试泄漏触发
     * executePush 的隔离风险（原 B-17 登记差异已关闭，见 TeamMemorySyncTest
     * resetForTesting_clearsDebounceAndSupportsOpts）。
     *
     * @param state                种子 syncState（CC opts.syncState；null → 不建 sync 操作）
     * @param skipWatcher          标记 watcher 已启动而不真正启动（CC opts.skipWatcher，watcher.ts:361-363）
     * @param pushSuppressedReason 种子抑制原因（CC opts.pushSuppressedReason；null → 不抑制）
     */
    public void resetForTesting(SyncState state, boolean skipWatcher, String pushSuppressedReason) {
        if (debounceFuture != null) {
            debounceFuture.cancel(false);
            debounceFuture = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("team-memory-watcher: reset close failed: {}", e.getMessage());
                }
            }
            watchService = null;
        }
        pushInProgress = false;
        hasPendingChanges = false;
        currentPushFuture = null;
        watcherStarted = skipWatcher;
        this.pushSuppressedReason = pushSuppressedReason;
        this.syncState = state;
    }
}
