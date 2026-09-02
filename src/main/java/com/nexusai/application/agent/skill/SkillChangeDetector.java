package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookCommand;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.telemetry.Telemetry;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Skill Change Detector · directory-watcher 递归监听 + onIdle debounce（P3-33 重构）.
 *
 * <p>对齐 CC {@code Open-ClaudeCode/src/utils/skills/skillChangeDetector.ts:85-279} 真实行为
 * （P1-16 + P3-33 · 非注释/旧报告）：
 * <ul>
 *   <li>{@link #initialize()} 幂等启动（:85-141）· {@link #getWatchablePaths()} 5 类路径 + fs.stat
 *       跳过缺失（:171-235）· {@link #handleChange} → {@code tengu_skill_file_changed} 遥测（:237-245）·
 *       ConfigChange hook 阻断闸（:267-273）· {@code SkillRegistry.refresh()} + {@code resetSentSkillNames()}
 *       + {@code emit()}（:274-277）· {@link #dispose()} 清理（:146-164）·
 *       {@link #resetForTesting}（:284-304）· bare 门控（main.tsx:423-425, R2I-DEC-13）</li>
 * </ul>
 *
 * <p><b>[P3-33] 监听机制重构（用户拍板：引入 io.methvin:directory-watcher 替代自实现 awaitWriteFinish）</b>：
 * <ul>
 *   <li><b>递归监听</b>：旧实现 JDK WatchService + 手动 ≤2 层子目录注册（registerTree/registerDir）+ 单线程
 *       pollLoop（阻塞 take）；新实现用 {@link DirectoryWatcher} 递归注册（FILE_TREE 修饰符，chokidar 等价），
 *       内容 hash 去重（fileHashing 默认开启 —— 同内容 MODIFY 事件不发射，防重复 reload）。</li>
 *   <li><b>写稳定 debounce（awaitWriteFinish 等价）</b>：旧实现 mtime/size 轮询（awaitWriteFinish
 *       stabilityThreshold=1000ms :27 + pollInterval=500ms :32，R2I-DEC-12/C-18）；新实现用
 *       {@code DirectoryChangeListener.onIdle} 空闲窗口 —— 文件系统空闲 {@link #FILE_STABILITY_THRESHOLD_MS}
 *       后触发一次 reload（等文件写稳定再触发热更新，directory-watcher 的 debounce 语义）。
 *       与库内建 {@code OnTimeoutListener} 语义一致，但复用本类 scheduler 而非自带线程池（避免
 *       OnTimeoutListener 内建单线程调度器无法关闭导致非 daemon 线程泄漏）。</li>
 *   <li><b>避免双轨</b>：自实现的"写稳定等待"逻辑（{@code handleChangeAfterAwaitWriteFinish} /
 *       {@code pollFileStability} / {@code StabilityState}）与旧 300ms 重载去抖（scheduleReload）全部移除，
 *       由单一 onIdle debounce 窗口承担「等待写稳定 + 合并为一次 reload」两个职责（C-18 长写放大语义保留）。</li>
 * </ul>
 *
 * <p><b>偏离注记（P3-33）</b>：directory-watcher 不支持 chokidar 式 {@code ignored: ['.git', ...]} 过滤
 * （:122-126），且递归深度不限（CC 为 depth:2）—— skill/command 目录内嵌 .git 或更深嵌套时可能产生
 * 额外 reload，属可接受的轻微行为放宽（旧 registerTree 的 isIgnored 已随之删除）。
 *
 * <p><b>旧实现删除（X29/D-8）</b>: 前身是 46 行 hash 对比内存存根（主动 hash 对比 Map +
 * 订阅通知，无真实文件系统监听），机制偏移（主动调用 hash 对比 vs CC 被动 FS watcher），
 * 全仓库零引用。整类重写，不留旧构造器/别名/双轨。
 *
 * <p><b>Java 落点复用</b>: {@link HookEvent#configChange}（HookEvent.java:535）+
 * {@link HookRegistry#executeEvent}（HookRegistry.java:1241）+ {@link Telemetry#recordEvent}
 * （Telemetry.java:400）+ {@link SkillRegistry#refresh}（SkillRegistry.java:367，P1-1 已合并
 * clearSkillCaches+clearCommandsCache）+ {@link FileChangedWatcher} WatchService 模式先例。
 */
@Component
public class SkillChangeDetector implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillChangeDetector.class);

    /**
     * 写稳定 debounce 窗口 · CC original: awaitWriteFinish.stabilityThreshold
     * （skillChangeDetector.ts:27 = 1000ms）。P3-33 起由 directory-watcher onIdle 空闲窗口承载：
     * 文件系统空闲该时长后触发一次 reload（等文件写稳定再触发热更新，替代自实现 mtime/size 轮询）。
     */
    static final long FILE_STABILITY_THRESHOLD_MS = 1000L;

    // ─── CC module-level 状态（skillChangeDetector.ts:64-72）→ Java 实例字段 ───

    /** directory-watcher 句柄 · CC original: {@code watcher}（:64）chokidar FSWatcher 等价. */
    private volatile DirectoryWatcher directoryWatcher;
    /** debounce 调度句柄 · CC original: {@code reloadTimer}（:65）setTimeout 等价（onIdle 空闲窗口触发）. */
    private volatile ScheduledFuture<?> debounceFuture;
    /** 待重载路径集合 · CC original: {@code pendingChangedPaths}（:66）. */
    private final Set<Path> pendingChangedPaths = ConcurrentHashMap.newKeySet();
    /** 幂等守卫 · CC original: {@code initialized}（:67）. */
    private volatile boolean initialized = false;
    /** 已销毁守卫 · CC original: {@code disposed}（:68）. */
    private volatile boolean disposed = false;
    /** skillsChanged signal 多订阅者 · CC original: {@code skillsChanged}（:71, createSignal）. */
    private final List<Runnable> subscribers = new CopyOnWriteArrayList<>();

    /**
     * 测试定时常量覆盖 · CC original: {@code testOverrides}（skillChangeDetector.ts:74-80）.
     * 键: {@code stabilityThreshold}（毫秒, debounce 空闲窗口）.
     */
    private volatile Map<String, Long> testOverrides = Map.of();

    // ─── 依赖注入（setter, POJO null-safe）───

    /** 缓存清理入口 · CC original: clearSkillCaches + clearCommandsCache（:274-275）. */
    private volatile SkillRegistry skillRegistry;
    /** ConfigChange hook 执行器 · CC original: executeConfigChangeHooks（:267, hooks.ts:4214）. */
    private volatile HookRegistry hookRegistry;
    /** 遥测 · CC original: logEvent('tengu_skill_file_changed')（:239-241）. */
    private volatile Telemetry telemetry;

    /**
     * bare 模式判定（可注入）· CC original: {@code isBareMode()}（envUtils.ts:60-65）+ 启动门控
     * {@code if (!isBareMode()) { void skillChangeDetector.initialize(); }}（main.tsx:423-425）——
     * bare 模式跳过 watcher 初始化（脚本化调用无"用户输入窗口"，watcher 是纯开销）。
     * 默认走 {@link MemoryBareModeConfig#isBareMode()} 统一判定（ODF-A3 同款）；可注入供测试
     * （SkillsLoader.java:148 setBareModeSupplier 同款，Java 无法进程内改 env）。
     */
    private Supplier<Boolean> bareModeSupplier = () -> MemoryBareModeConfig.isBareMode();

    // ─── 监听路径来源（CC getWatchablePaths :171-235）───

    /**
     * user 配置 home（测试覆写 seam）· CC original: getClaudeConfigHomeDir()（envUtils.ts:7-14）。
     * P2-20 起默认不再直接使用本字段：生产路径由 {@link ClaudePaths#getClaudeConfigHomeDir()}
     * 提供（honor CLAUDE_CONFIG_DIR，与加载侧 SkillsLoader 同源，EV-WF7-CD-014/024/025）；
     * 本字段仅当测试显式调用 {@link #setUserHomeDir} 覆写时生效（{@link #userHomeDirOverridden}）。
     */
    private volatile Path userHomeDir = Path.of(System.getProperty("user.home", "."));
    /** 是否经 {@link #setUserHomeDir} 显式覆写（P2-20：覆写时走 userHomeDir/.claude，否则走 ClaudePaths 同源）. */
    private volatile boolean userHomeDirOverridden = false;
    /** 项目根（cwd）· CC original: projectSettings = `.claude/${dir}`（loadSkillsDir.ts:88）→ resolve 绝对路径.
     *
     *  <p>cwd-align-ext：watcher 单例启动期实例化（main.tsx:423-425 initialize 单跑），此时无会话 →
     *  {@link CwdResolution#getOriginalCwdLayer} 回落 user.dir（零行为变化）；有会话则取会话 originalCwd
     *  （对齐 CC state.ts:509 skills 属 projectRoot/getOriginalCwd 稳定锚语义）。 */
    private volatile Path projectDir = Path.of(resolveWatcherProjectDir());
    /**
     * 附加目录（--add-dir）· CC original: getAdditionalDirectoriesForClaudeMd()（state.ts:1666）。
     * 测试注入 seam（{@link #addAdditionalDir}）。
     */
    private final List<Path> additionalDirs = new CopyOnWriteArrayList<>();

    /**
     * watcher 监听项目根 · 对齐 CC getOriginalCwd()（state.ts:509 skills 属 projectRoot 稳定锚）。
     *
     * <p>字段初始化时经 RequestContext 取会话 originalCwd；无 sessionId（Spring 单例启动期）回落
     * user.dir（方案 1，零行为变化）。
     */
    private static String resolveWatcherProjectDir() {
        String cwd = CwdResolution.getOriginalCwdLayer(RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }

    /**
     * 附加目录供应 · CC original: {@code getAdditionalDirectoriesForClaudeMd()}（state.ts:1666）。
     * P2-21 默认 {@link ClaudePaths#getAdditionalDirectoriesFromEnv()} —— 与加载侧
     * {@code SkillsLoader.additionalDirectoriesSupplier}（ToolRegistrationConfig:394 接同函数）
     * 同源，--add-dir 目录被监听（EV-WF7-CD-016/023）；可注入供测试。
     */
    private volatile Supplier<List<String>> additionalDirectoriesSupplier = ClaudePaths::getAdditionalDirectoriesFromEnv;

    /** DirectoryWatcher 事件循环线程（directory-watcher watchAsync 运行于其上）· 参考 FileChangedWatcher.java:110 先例. */
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "skill-change-watcher");
        t.setDaemon(true);
        return t;
    });
    /** debounce 调度线程（onIdle 空闲窗口 → reload；与事件循环分离）. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-reload-debounce");
        t.setDaemon(true);
        return t;
    });

    /** Spring 无参构造器 · 依赖经 setter 注入. */
    public SkillChangeDetector() {
    }

    // ════════════════════════════════════════════════════════════════════════
    // 依赖注入（@Autowired(required=false) · 手动 new 场景 null-safe）
    // ════════════════════════════════════════════════════════════════════════

    @Autowired(required = false)
    public void setSkillRegistry(SkillRegistry registry) {
        this.skillRegistry = registry;
    }

    @Autowired(required = false)
    public void setHookRegistry(HookRegistry registry) {
        this.hookRegistry = registry;
    }

    @Autowired(required = false)
    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * 注入 bare 模式判定（测试用）· 等价 CC envUtils.ts:60-65 {@code isBareMode()}。
     * null → 忽略（保持默认 env/配置判定，SkillsLoader.java:181 同款）。
     */
    public void setBareModeSupplier(Supplier<Boolean> bareModeSupplier) {
        if (bareModeSupplier != null) {
            this.bareModeSupplier = bareModeSupplier;
        }
    }

    /**
     * bare 模式判定 · 对齐 CC main.tsx:423-425 —— true 时 {@link #run(ApplicationArguments)}
     * 跳过 watcher 初始化（SkillsLoader.isBareMode :204-206 同款可注入判定）。
     */
    public boolean isBareMode() {
        return Boolean.TRUE.equals(bareModeSupplier.get());
    }

    // ─── 监听路径来源覆盖（测试 / 部署定制）───

    /** 覆盖 user home（默认 {@code user.home} 系统属性）· 测试注入临时目录. */
    public void setUserHomeDir(Path userHomeDir) {
        this.userHomeDir = userHomeDir;
        this.userHomeDirOverridden = true;
    }

    /** 覆盖项目根（默认 {@code user.dir}）· 测试注入临时目录. */
    public void setProjectDir(Path projectDir) {
        this.projectDir = projectDir;
    }

    /** 追加 --add-dir 目录 · 等价 CC getAdditionalDirectoriesForClaudeMd()（state.ts:1666）. */
    public void addAdditionalDir(Path dir) {
        if (dir != null && !additionalDirs.contains(dir)) {
            additionalDirs.add(dir);
        }
    }

    /**
     * 注入附加目录供应 · CC original: getAdditionalDirectoriesForClaudeMd（state.ts:1666）。
     * null → 忽略（保持默认 {@link ClaudePaths#getAdditionalDirectoriesFromEnv}，
     * SkillsLoader.setAdditionalDirectoriesSupplier :166 同款）。
     */
    public void setAdditionalDirectoriesSupplier(Supplier<List<String>> additionalDirectoriesSupplier) {
        if (additionalDirectoriesSupplier != null) {
            this.additionalDirectoriesSupplier = additionalDirectoriesSupplier;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 启动接线 · ApplicationRunner（对齐 BundledSkillsBootstrapper.java:19 先例）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Spring 启动后置初始化 · 对齐 CC {@code startDeferredPrefetches()} 内
     * {@code skillChangeDetector.initialize()} 调用点（main.tsx:421-425）.
     *
     * <p><b>bare 门控</b>: CC main.tsx:423-425 —— {@code if (!isBareMode()) { void skillChangeDetector.initialize(); }}
     * → Java 在 {@code run → initialize} 之间前置 {@link #isBareMode()} 判定，bare 模式跳过
     * watcher 初始化（R2I-DEC-13）。门控在调用点而非 initialize 内部（对齐 CC：initialize 本身
     * 无 bare 判定，:85-141），测试可直接调 initialize 绕过门控。
     *
     * <p>用 {@link ApplicationRunner} 而非 @PostConstruct：依赖 bean（SkillRegistry/HookRegistry/
     * Telemetry）就绪时序在 @PostConstruct 不保证（concern #6）；ApplicationRunner 在所有 bean
     * 创建完成后执行，规避 bean 就绪 NPE。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (isBareMode()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillChangeDetector] bare 模式 → 跳过 watcher 初始化 (CC main.tsx:423-425)");
            }
            return;
        }
        initialize();
    }

    /**
     * 幂等初始化 · 对齐 CC {@code initialize()}（skillChangeDetector.ts:85-141）.
     *
     * <ul>
     *   <li>:86 幂等守卫 {@code if (initialized || disposed) return}，:87 {@code initialized = true}
     *       在 paths 判断之前置位（空路径也要防重入）</li>
     *   <li>:103-104 {@code getWatchablePaths()} 为空直接 return（无目录可监听）</li>
     *   <li>:110-131 chokidar.watch 等价 → {@link DirectoryWatcher}（P3-33：递归注册 + 内容 hash 去重）</li>
     * </ul>
     */
    public synchronized void initialize() {
        if (initialized || disposed) {
            return;
        }
        initialized = true;
        List<Path> paths = getWatchablePaths();
        if (paths.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillChangeDetector] 无可监听 skill/command 目录 → 不启动 watcher (CC skillChangeDetector.ts:104 早返)");
            }
            return;
        }
        log.info("[SkillChangeDetector] 监听 skill/command 目录变更: {} (共 {} 个)", paths, paths.size());
        try {
            DirectoryWatcher watcher = DirectoryWatcher.builder()
                    .paths(paths)
                    // P3-33: 内容 hash 去重（directory-watcher 默认 fileHashing=true）—— 同内容 MODIFY
                    //   事件不发射，防重复 reload（CC chokidar 非 content-based，Java 语义加强）
                    .fileHashing(true)
                    .listener(watcherListener)
                    .logger(log)
                    .build();
            directoryWatcher = watcher;
            watcher.watchAsync(watcherExecutor);
            log.info("[SkillChangeDetector] directory-watcher 已启动 ({} 个根目录, 递归监听 + debounce {}ms)",
                    paths.size(), stabilityThresholdMs());
        } catch (IOException e) {
            log.warn("[SkillChangeDetector] directory-watcher 启动失败: {}", e.toString());
        }
    }

    /**
     * directory-watcher 监听回调 · CC original: chokidar watch 事件回调（:133-135 add/change/unlink 挂
     * handleChange）+ awaitWriteFinish（:114-119，P3-33 改由 onIdle debounce 承载）。
     *
     * <p><b>P3-33 事件分流</b>：
     * <ul>
     *   <li>{@code onEvent}（CREATE/MODIFY/DELETE）：取消 pending debounce（新事件到达 → 写未稳定，
     *       重排 onIdle 窗口；对齐 OnTimeoutListener.onEvent 取消语义）→ {@link #handleChange} 收集路径。</li>
     *   <li>{@code onIdle}（文件系统空闲）：排定 {@link #FILE_STABILITY_THRESHOLD_MS} 后 reload ——
     *       等文件写稳定再触发热更新（awaitWriteFinish 等价，替代自实现 mtime/size 轮询）。</li>
     * </ul>
     */
    private final DirectoryChangeListener watcherListener = new DirectoryChangeListener() {
        @Override
        public void onEvent(DirectoryChangeEvent event) throws IOException {
            if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
                return; // OVERFLOW 丢弃（旧 pollLoop 同款）
            }
            cancelDebounce(); // 新事件到达 → 取消 pending debounce（onIdle 会重排，OnTimeoutListener.onEvent 语义）
            handleChange(event.path());
        }

        @Override
        public void onIdle(int count) {
            scheduleDebounceReload(); // FS 空闲 → stability 窗口后排定 reload（awaitWriteFinish 等价）
        }
    };

    /**
     * 计算可监听路径（5 类）· 对齐 CC {@code getWatchablePaths()}（skillChangeDetector.ts:171-235）.
     *
     * <ul>
     *   <li><b>[T3 双目录]</b> nexusai user skills/commands = {@code NexusaiPaths.getAppConfigHomeDir()/skills|commands}
     *       （自有根优先，~/.{appName}，与加载侧 SkillsLoader 双目录用户源同源）</li>
     *   <li>:176 user skills = getSkillsPath('userSettings','skills') = getClaudeConfigHomeDir()/skills
     *       （loadSkillsDir.ts:86 ↔ envUtils.ts:7-14 CLAUDE_CONFIG_DIR ?? homedir()/.claude，claude 回落）</li>
     *   <li>:187 user commands = getClaudeConfigHomeDir()/commands（claude 回落）</li>
     *   <li>:198 project skills = `.claude/skills` → resolve 绝对路径（:202）</li>
     *   <li>:211 project commands = `.claude/commands` → resolve 绝对路径（:215）</li>
     *   <li>:224 additional dirs = join(dir, '.claude', 'skills')（state.ts:1666）</li>
     * </ul>
     *
     * <p><b>P2-20 同源</b>：user 两路径走 {@link ClaudePaths#getClaudeConfigHomeDir()}（honor
     * CLAUDE_CONFIG_DIR，与加载侧 {@code SkillsLoader} 同源，EV-WF7-CD-014/024/025）。
     * 旧实现 userHomeDir=user.home/.claude 忽略 CLAUDE_CONFIG_DIR → 设该 env 时 watcher 盯错
     * 目录，技能变更不触发 reload。测试经 {@link #setUserHomeDir} 覆写时回落 userHomeDir/.claude。</p>
     *
     * <p><b>P2-21 接入监听</b>：additional 两路径来源与加载侧同源
     * {@link ClaudePaths#getAdditionalDirectoriesFromEnv()}（--add-dir 等价，SkillsLoader
     * additionalDirectoriesSupplier 同款）；旧实现仅 {@link #addAdditionalDir} 注入生产零调用
     * → --add-dir 技能目录不被监听（EV-WF7-CD-016/023）。</p>
     *
     * <p>每路径 fs.stat 语义（:179/:192/:205/:219/:229 try/catch 跳过缺失）→ Java
     * {@link Files#isDirectory} 跳过不存在路径。
     *
     * @return 存在且为目录的可监听绝对路径列表
     */
    List<Path> getWatchablePaths() {
        List<Path> paths = new ArrayList<>();
        // user 配置根：P2-20 生产走 ClaudePaths（honor CLAUDE_CONFIG_DIR），测试覆写走 userHomeDir/.claude
        Path userConfigHome = userHomeDirOverridden
            ? userHomeDir.resolve(".claude")
            : Path.of(ClaudePaths.getClaudeConfigHomeDir());
        // T3: 内容读兼容 —— watcher 同步监听 nexusai 自有根 skills/commands（优先）与 claude 回落，
        //   与加载侧 SkillsLoader 双目录用户源同源（~/.{appName} + ~/.claude）
        Path nexusaiUserConfigHome = Path.of(NexusaiPaths.getAppConfigHomeDir());
        // 1. nexusai user skills ~/.{appName}/skills（自有根优先，T3）
        addIfDirectory(paths, nexusaiUserConfigHome.resolve("skills"));
        // 2. nexusai user commands ~/.{appName}/commands（自有根优先，T3）
        addIfDirectory(paths, nexusaiUserConfigHome.resolve("commands"));
        // 3. user skills getClaudeConfigHomeDir()/skills（CC :176-184，claude 回落）
        addIfDirectory(paths, userConfigHome.resolve("skills"));
        // 4. user commands getClaudeConfigHomeDir()/commands（CC :187-195，claude 回落）
        addIfDirectory(paths, userConfigHome.resolve("commands"));
        // 5. project skills .{appName}/skills（nexusai 优先，决策 D1/D6）→ 绝对化（.nexusai 等价 CC :198-208）
        addIfDirectory(paths, projectDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").toAbsolutePath());
        // 6. project commands .{appName}/commands（nexusai 优先，决策 D1/D6）→ 绝对化（.nexusai 等价 CC :211-221）
        addIfDirectory(paths, projectDir.resolve(NexusaiPaths.getProjectDirName()).resolve("commands").toAbsolutePath());
        // 7. project skills .claude/skills（claude 回落，CC :198-208）
        addIfDirectory(paths, projectDir.resolve(".claude").resolve("skills").toAbsolutePath());
        // 8. project commands .claude/commands（claude 回落，CC :211-221）
        addIfDirectory(paths, projectDir.resolve(".claude").resolve("commands").toAbsolutePath());
        // 9. additional dirs join(dir,'.claude','skills')（CC :224-232）· P2-21 同源合并
        List<Path> additional = new ArrayList<>(additionalDirs); // 测试注入 seam
        List<String> envAdditionalDirs = additionalDirectoriesSupplier.get();
        if (envAdditionalDirs != null) {
            for (String dir : envAdditionalDirs) {
                if (dir != null && !dir.isBlank()) {
                    try {
                        Path p = Path.of(dir);
                        if (!additional.contains(p)) {
                            additional.add(p);
                        }
                    } catch (InvalidPathException e) {
                        // 非法 env 值（含 NUL / Windows 非法字符）→ Path.of 抛 InvalidPathException，
                        // 跳过该值（对齐 CC skillChangeDetector.ts:224-232 逐路径 try/catch 跳过垃圾值），
                        // 不得逃逸到 initialize()（:301 无 catch → watcher 崩溃）。
                        if (log.isDebugEnabled()) {
                            log.debug("[SkillChangeDetector] 跳过非法附加目录 env 值: {} ({})", dir, e.getMessage());
                        }
                    }
                }
            }
        }
        for (Path dir : additional) {
            if (dir != null) {
                // 决策 D1/D6：additionalDir 技能目录 nexusai 优先 + claude 回落（与加载侧 SkillsLoader
                //   .nexusai/skills 优先同源，P2-21 同源原则——监听与加载侧目录集合一致）
                addIfDirectory(paths, dir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills"));
                addIfDirectory(paths, dir.resolve(".claude").resolve("skills"));
            }
        }
        return paths;
    }

    /** fs.stat try/catch 等价：仅加入存在且为目录的路径（CC :179/:192/:205/:219/:229 catch 跳过缺失）. */
    private void addIfDirectory(List<Path> paths, Path p) {
        if (Files.isDirectory(p)) {
            paths.add(p.normalize());
        } else if (log.isDebugEnabled()) {
            log.debug("[SkillChangeDetector] 监听路径不存在，跳过: {}", p);
        }
    }

    /**
     * 文件变更入口 · 对齐 CC {@code handleChange(path)}（skillChangeDetector.ts:237-245）.
     *
     * <ul>
     *   <li>:238 logForDebugging → Java {@code log.debug("检测到 skill 变更: {}")}</li>
     *   <li>:239-241 {@code logEvent('tengu_skill_file_changed', {source:'chokidar'})} → 遥测事件值恒为
     *       'chokidar'（对齐 telemetry 契约，不改 'watchservice'，避免数据口径分裂）</li>
     *   <li>P3-33: 收集进 {@link #pendingChangedPaths}（旧 :244 scheduleReload 300ms 去抖已移除 ——
     *       由 onIdle debounce 空闲窗口合并，见 {@link #watcherListener}）</li>
     * </ul>
     *
     * <p>调用面（P3-33）：directory-watcher 每个 CREATE/MODIFY/DELETE 事件直接进入本方法；
     * 写稳定等待（awaitWriteFinish）由 {@link #onIdle(int)} 空闲窗口承载（替代旧
     * {@code handleChangeAfterAwaitWriteFinish} mtime/size 轮询）。
     */
    void handleChange(Path path) {
        if (log.isDebugEnabled()) {
            log.debug("[SkillChangeDetector] 检测到 skill 变更: {}", path);
        }
        if (telemetry != null) {
            telemetry.recordEvent("tengu_skill_file_changed", Map.of("source", "chokidar"));
        }
        pendingChangedPaths.add(path);
    }

    // ─── P3-33 debounce（awaitWriteFinish 等价 · OnTimeoutListener 语义但复用本类 scheduler）───

    /**
     * 取消 pending debounce · CC original: OnTimeoutListener.onEvent 内 {@code currentTaskRef.cancel(false)}
     * （新事件到达 → 写未稳定 → 重排窗口）。与 {@link #scheduleDebounceReload()} 成对出现。
     */
    private void cancelDebounce() {
        ScheduledFuture<?> future = debounceFuture;
        debounceFuture = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 排定 debounce reload · CC original: OnTimeoutListener.onIdle 内 {@code service.schedule(callback, timeout)}
     * —— 文件系统空闲 {@link #FILE_STABILITY_THRESHOLD_MS} 后触发一次 reload（awaitWriteFinish 等价，
     * 等文件写稳定再触发热更新）。多次 onIdle 重排（后写覆盖先写），窗口内新事件经
     * {@link #cancelDebounce()} 取消后由下一次 onIdle 重排。
     */
    private void scheduleDebounceReload() {
        if (disposed || !initialized) {
            return;
        }
        ScheduledFuture<?> future = debounceFuture;
        if (future != null) {
            future.cancel(false);
        }
        debounceFuture = scheduler.schedule(this::debounceReload, stabilityThresholdMs(), TimeUnit.MILLISECONDS);
    }

    /** debounce 到期回调 · 执行 reload（清空句柄，供下一次 onIdle 重新排定）. */
    private void debounceReload() {
        debounceFuture = null;
        reload();
    }

    /** 测试覆盖读取 · CC original: {@code testOverrides?.stabilityThreshold ?? FILE_STABILITY_THRESHOLD_MS}（:116）. */
    private long stabilityThresholdMs() {
        Long v = testOverrides.get("stabilityThreshold");
        return v != null ? v : FILE_STABILITY_THRESHOLD_MS;
    }

    /**
     * 去抖批处理 · 对齐 CC scheduleReload 回调体（skillChangeDetector.ts:258-277）.
     *
     * <ul>
     *   <li>:260-261 快照 paths 并 clear pendingChangedPaths</li>
     *   <li>:267 {@code executeConfigChangeHooks('skills', paths[0]!)} → 批次首个路径作 file_path</li>
     *   <li>:268-273 {@code hasBlockingResult(results)} 阻断则 return（不 reload）· Java 等价:
     *       {@link #isBlockingConfigChange(List)} 聚合全部结果 {@code some(any blocked)}
     *       （FIX-C2 拍板#10，非 firstStop）</li>
     *   <li>:274-275 clearSkillCaches + clearCommandsCache → Java {@link SkillRegistry#refresh()}
     *       （P1-1 已合并，SkillRegistry.java:367）</li>
     *   <li>:276 resetSentSkillNames() → Java 端 per-run 状态自然复位（见 {@link #resetSentSkillNames()}）</li>
     *   <li>:277 skillsChanged.emit() → {@link #emit()}</li>
     * </ul>
     *
     * <p><b>空路径守卫（P3-33）</b>：onIdle 在启动即空闲（无任何事件）时也会触发一次 debounce
     * （directory-watcher 事件循环 poll 空 → onIdle），此时 pendingChangedPaths 为空 → 直接 return
     * 不 refresh/emit（CC 无此情况：CC reload 仅由 handleChange→scheduleReload 触发，路径恒非空）。
     */
    private void reload() {
        List<Path> paths = new ArrayList<>(pendingChangedPaths);
        pendingChangedPaths.clear();
        if (paths.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillChangeDetector] reload 空路径（onIdle 纯空闲触发）→ 跳过 refresh");
            }
            return;
        }
        String firstPath = paths.get(0).toString();
        List<GenericHook.HookResult> results = executeConfigChangeHook("skills", firstPath);
        if (isBlockingConfigChange(results)) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillChangeDetector] ConfigChange hook 阻止 skill 重载 ({} 个路径)",
                    paths.size());
            }
            return;
        }
        if (skillRegistry != null) {
            skillRegistry.refresh();
        }
        resetSentSkillNames();
        emit();
    }

    /**
     * [ALIGN-HS-1 △-SH-04 + FIX-C2 拍板#10] ConfigChange 阻断闸 · CC original:
     * {@code hasBlockingResult(results)}（hooks.ts:2983-2985）= {@code results.some(r => r.blocked)}，
     * 即「任一结果阻断则阻断」（聚合<b>全部</b>阻断结果，非 firstStop）。
     *
     * <p>CC {@code blocked} 仅由 command hook（exit 2）与 HTTP sync hook（decision="block"）产生
     * （hooks.ts:3328-3334 / :3237-3261）；prompt/agent 型 hook 在 {@code executeHooksOutsideREPL}
     * 恒 {@code blocked:false}「not yet supported outside REPL」（hooks.ts:3152-3186），永不阻断
     * ConfigChange。
     *
     * <p>Java 旧实现（ALIGN-HS-1）经 {@link HookRegistry#executeEvent} 折叠为单结果（firstStop）
     * 再按 hook 类型过滤 —— prompt hook 与 command hook 同事件并发时，若 prompt 先完成，
     * firstStop=prompt 结果 → 过滤后不阻断，会掩盖 command hook 的 exit-2 阻断（NG-2 /
     * HSCS-IMP-6 漏阻断边缘）。FIX-C2 改为 {@link HookRegistry#executeConfigChangeHooks} 返回
     * 全部结果，本方法按 CC {@code some(any blocked)} 聚合：任一结果阻断（且非 prompt/agent 型）
     * 即阻断。
     *
     * <p>prompt/agent 型不阻断（对齐 CC），command/http 型与 programmatic（hook==null）保留原阻断
     * 语义（既有 {@code blockedConfigChangeHook_skipsReload} 测试用 programmatic GenericHook 验证主闸）。
     *
     * @param results ConfigChange hook 全部执行结果（null/空 = 未接线或无匹配 hook，不阻断）
     * @return true = 任一结果阻断 → 阻断重载（跳过 refresh + emit）
     */
    private boolean isBlockingConfigChange(List<GenericHook.HookResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (GenericHook.HookResult result : results) {
            if (isBlockingConfigChangeResult(result)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单结果阻断判定 · CC original: 该结果的 {@code blocked} 标志（hooks.ts:3328-3334 / :3237-3261）.
     */
    private static boolean isBlockingConfigChangeResult(GenericHook.HookResult result) {
        if (result == null) {
            return false;
        }
        boolean blocked = result.preventContinuation() || result.blockingError() != null;
        if (!blocked) {
            return false;
        }
        HookCommand hook = result.hook();
        if (hook == null) {
            // programmatic GenericHook（无 HookCommand 载体）→ 保留阻断（等价 CC 主闸）
            return true;
        }
        // prompt/agent 型 hook 在 REPL 外不可阻断（CC hooks.ts:3152-3186）→ 不阻断 ConfigChange
        return hook.hookType() != HookCommand.HookType.PROMPT
            && hook.hookType() != HookCommand.HookType.AGENT;
    }

    /**
     * 执行 ConfigChange hooks · 对齐 CC {@code executeConfigChangeHooks(source, filePath, timeoutMs)}
     * （hooks.ts:4214-4223: 构建 ConfigChangeHookInput{hook_event_name:'ConfigChange', source, file_path}，
     * matchQuery=source='skills'）—— 返回<b>全部</b> hook 结果（非 firstStop 折叠，FIX-C2 拍板#10）。
     *
     * <p>protected 方法作为测试 seam（默认走真实 {@link HookRegistry#executeConfigChangeHooks}，
     * POJO 测试可覆写）。
     */
    protected List<GenericHook.HookResult> executeConfigChangeHook(String source, String filePath) {
        if (hookRegistry == null) {
            return List.of();
        }
        return hookRegistry.executeConfigChangeHooks(source, filePath);
    }

    /**
     * [ALIGN-HS-1 SU-△-1] 复位已发送技能名 · CC original: {@code resetSentSkillNames()}
     * （attachments.ts:2612-2615 {@code sentSkillNames.clear() + suppressNext = false}）.
     *
     * <p>CC {@code sentSkillNames} 与 {@code suppressNext} 是进程级全局状态（attachments.ts:2607 /
     * :2636）；Java 端二者是 per-run {@code AgentLoopContext.LoopSessionState} 字段
     * （{@code sentSkillNames} Map + {@code suppressNextSkillListing} AtomicBoolean，每 run 新建），
     * 无全局 registry。本类提供 {@link #REGISTERED_SENT_SKILL_NAMES} 静态注册表 +
     * {@link #registerSentSkillNames} / {@link #unregisterSentSkillNames} 通道清 {@code sentSkillNames}，
     * 及 {@link #REGISTERED_SUPPRESS_NEXT_SKILL_LISTING} 静态注册表 +
     * {@link #registerSuppressNextSkillListing} / {@link #unregisterSuppressNextSkillListing} 通道
     * 复位 {@code suppressNextSkillListing}。由 AgentLoopContextFactory 接线后，本方法实装
     * <b>两个动作</b>（对齐 CC {@code sentSkillNames.clear() + suppressNext = false}）：
     * <ol>
     *   <li>清空所有已注册 sentSkillNames Map —— skill 文件变更后下一轮 skill_listing 重发全部技能；</li>
     *   <li>复位所有已注册 suppressNextSkillListing 为 false —— 取消 pending 的 resume 抑制，使文件
     *       变更触发的重发不被 suppressNext 吞掉（否则 Java 端 suppressNext 消费（computeSkillListingDelta
     *       compareAndSet）会在 clear 后的下一轮仍抑制一次 listing，偏离 CC）。</li>
     * </ol>
     *
     * <p><b>生产接线（FIX-B3 · 拍板#5）</b>：{@code AgentLoopContextFactory.build()} 在每次
     * 构造会话时调用 {@code SkillChangeDetector.registerSentSkillNames(session.sentSkillNames())}
     * 与 {@code SkillChangeDetector.registerSuppressNextSkillListing(session.suppressNextSkillListing())}
     * 注册 per-run 引用（含主循环 5 参 forSession 直传会话），生产 reset 已生效
     * （skillChangeDetector.ts:276 在 skill 文件变更后调本方法）。
     */
    void resetSentSkillNames() {
        int cleared = 0;
        synchronized (REGISTERED_SENT_SKILL_NAMES) {
            for (Map<String, Set<String>> sentSkillNames : REGISTERED_SENT_SKILL_NAMES) {
                sentSkillNames.clear();
                cleared++;
            }
        }
        int suppressReset = 0;
        for (AtomicBoolean suppressNext : REGISTERED_SUPPRESS_NEXT_SKILL_LISTING) {
            suppressNext.set(false);
            suppressReset++;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillChangeDetector] resetSentSkillNames: 清理 {} 个已注册 sentSkillNames + 复位 {} 个 suppressNext (CC attachments.ts:2612-2615 sentSkillNames.clear() + suppressNext=false)",
                cleared, suppressReset);
        }
    }

    /**
     * [ALIGN-HS-1 SU-△-1] 进程级 sentSkillNames 注册表 · CC original: attachments.ts:2607
     * {@code sentSkillNames}（进程级全局 Map）。Java 端 sentSkillNames 为 per-run 字段，本表提供
     * 静态注册通道，使 {@link #resetSentSkillNames()} 能实装清理（对齐 CC clear 语义）。
     *
     * <p><b>[REG-SCD] 身份(identity)去重</b>：注册表必须按<b>引用身份</b>而非内容相等去重。
     * 旧实现 {@code ConcurrentHashMap.newKeySet()} 的 {@code add} 走 {@code Map.equals}
     * （内容相等）—— 两个空 ConcurrentHashMap 内容相等（hashCode 均 0、equals 均 true），
     * 后注册的空 Map 被去重吞掉，导致 skill 文件变更后该会话的 sentSkillNames 不清
     * （CC attachments.ts:2612-2615 clear 语义失效）。故改用
     * {@code IdentityHashMap} 背衬（引用身份）+ 同步包装（add/remove/遍历线程安全）。
     */
    private static final Set<Map<String, Set<String>>> REGISTERED_SENT_SKILL_NAMES =
            Collections.synchronizedSet(
                Collections.newSetFromMap(new IdentityHashMap<Map<String, Set<String>>, Boolean>()));

    /**
     * [ALIGN-HS-1 SU-△-1] 进程级 suppressNextSkillListing 注册表 · CC original: attachments.ts:2636
     * {@code suppressNext}（进程级全局 boolean）。Java 端 suppressNextSkillListing 为 per-run
     * {@code AtomicBoolean} 字段，本表提供静态注册通道，使 {@link #resetSentSkillNames()} 能
     * 实装复位（对齐 CC {@code suppressNext = false}）。
     */
    private static final Set<AtomicBoolean> REGISTERED_SUPPRESS_NEXT_SKILL_LISTING =
            ConcurrentHashMap.newKeySet();

    /**
     * 注册 per-run sentSkillNames 引用（供 {@link #resetSentSkillNames()} 清理）·
     * 由 AgentLoopContextFactory.build() 生产接线（FIX-B3 · 拍板#5）。
     *
     * @param sentSkillNames per-run sentSkillNames Map（null 忽略）
     */
    public static void registerSentSkillNames(Map<String, Set<String>> sentSkillNames) {
        if (sentSkillNames != null) {
            REGISTERED_SENT_SKILL_NAMES.add(sentSkillNames);
        }
    }

    /**
     * 注销 sentSkillNames 引用（会话结束移除，防泄漏）· 由 {@code LlmAgentLoop.loop()} finally
     * 成对调用（与 {@code sysPromptCtxProvider.close()} 同处）。主循环 forSession / subagent·hook
     * shared() 三条路径统一经 queryLoop → loop 终结，按同一 LoopSessionState 实例身份注销。
     *
     * @param sentSkillNames per-run sentSkillNames Map（null 忽略）
     */
    public static void unregisterSentSkillNames(Map<String, Set<String>> sentSkillNames) {
        if (sentSkillNames != null) {
            REGISTERED_SENT_SKILL_NAMES.remove(sentSkillNames);
        }
    }

    /**
     * 注册 per-run suppressNextSkillListing 引用（供 {@link #resetSentSkillNames()} 复位）·
     * 由 AgentLoopContextFactory.build() 生产接线（FIX-B3 · 拍板#5）。
     *
     * @param suppressNext per-run suppressNextSkillListing AtomicBoolean（null 忽略）
     */
    public static void registerSuppressNextSkillListing(AtomicBoolean suppressNext) {
        if (suppressNext != null) {
            REGISTERED_SUPPRESS_NEXT_SKILL_LISTING.add(suppressNext);
        }
    }

    /**
     * 注销 suppressNextSkillListing 引用（会话结束移除，防泄漏）· 由 {@code LlmAgentLoop.loop()}
     * finally 成对调用（与 {@code sysPromptCtxProvider.close()} 同处）。主循环 forSession /
     * subagent·hook shared() 三条路径统一经 queryLoop → loop 终结，按同一 LoopSessionState
     * 实例身份注销。
     *
     * @param suppressNext per-run suppressNextSkillListing AtomicBoolean（null 忽略）
     */
    public static void unregisterSuppressNextSkillListing(AtomicBoolean suppressNext) {
        if (suppressNext != null) {
            REGISTERED_SUPPRESS_NEXT_SKILL_LISTING.remove(suppressNext);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // skillsChanged signal（CC :71/:169 subscribe = skillsChanged.subscribe）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 订阅技能变更 · 对齐 CC {@code skillsChanged.subscribe}（signal.ts createSignal :169）.
     *
     * <p>CC subscribe 返回取消订阅函数 → Java 返回 {@link Runnable} 移除句柄（调用 {@code run()} 即退订）。
     *
     * @param listener 技能变更回调（无参数；CC signal emit 多订阅者）
     * @return 取消订阅句柄（run() = 从订阅列表移除）
     */
    public Runnable subscribe(Runnable listener) {
        if (listener != null) {
            subscribers.add(listener);
        }
        return () -> subscribers.remove(listener);
    }

    /**
     * 通知全部订阅者 · 对齐 CC {@code skillsChanged.emit()}（signal.ts; :277）.
     *
     * <p>CC emit 无 per-listener try-catch（旧存根 notify 的 catch(Exception ignored) 静默吞已随
     * X29 删除，不再静默吞）→ Java 同样不吞异常（规则十二：显式失败）。
     */
    private void emit() {
        for (Runnable listener : subscribers) {
            listener.run();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 生命周期清理
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 关闭 watcher · 对齐 CC {@code dispose()}（skillChangeDetector.ts:146-164）.
     *
     * <ul>
     *   <li>:147 {@code disposed = true}</li>
     *   <li>:153-156 {@code watcher.close()} → {@link DirectoryWatcher#close()}（关闭 WatchService →
     *       事件循环 take() 抛 ClosedWatchServiceException 退出，watchAsync future 完成）</li>
     *   <li>:157-160 {@code clearTimeout(reloadTimer)} → debounceFuture.cancel(false)</li>
     *   <li>:161 {@code pendingChangedPaths.clear()}</li>
     *   <li>:162 {@code skillsChanged.clear()}</li>
     * </ul>
     */
    @PreDestroy
    public void dispose() {
        disposed = true;
        DirectoryWatcher watcher = directoryWatcher;
        directoryWatcher = null;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                log.warn("[SkillChangeDetector] 关闭 directory-watcher 失败: {}", e.toString());
            }
        }
        cancelDebounce();
        pendingChangedPaths.clear();
        subscribers.clear();
        watcherExecutor.shutdownNow();
        scheduler.shutdownNow();
        log.info("[SkillChangeDetector] 已 dispose (directory-watcher 关闭 + debounce 取消 + 订阅者清空)");
    }

    /**
     * 测试重置 · 对齐 CC {@code resetForTesting(overrides)}（skillChangeDetector.ts:284-304）.
     *
     * <ul>
     *   <li>:291-294 关闭 watcher</li>
     *   <li>:295-298 clearTimeout</li>
     *   <li>:299-300 pendingChangedPaths.clear + skillsChanged.clear</li>
     *   <li>:301-302 initialized/disposed 复位（可重新 initialize）</li>
     *   <li>:303 testOverrides 覆盖定时常量</li>
     * </ul>
     *
     * <p>不 shutdown executor（reset 后可重新 initialize；daemon 线程不阻塞 JVM 退出）。
     *
     * @param overrides 定时常量覆盖，毫秒；键对齐 CC testOverrides（:74-79）:
     *                  {@code stabilityThreshold}（P3-33：debounce 空闲窗口）；
     *                  null = 清空覆盖
     */
    void resetForTesting(Map<String, Long> overrides) {
        DirectoryWatcher watcher = directoryWatcher;
        directoryWatcher = null;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                log.warn("[SkillChangeDetector] resetForTesting 关闭 directory-watcher 失败: {}", e.toString());
            }
        }
        cancelDebounce();
        pendingChangedPaths.clear();
        subscribers.clear();
        initialized = false;
        disposed = false;
        testOverrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }
}
