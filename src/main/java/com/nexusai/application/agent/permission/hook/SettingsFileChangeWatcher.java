package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.plugin.PluginLoader;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [IMP-HOOKS-S5 D-04] settings 文件变更监听 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/settings/changeDetector.ts}（settingsChangeDetector，
 * main.tsx:422 {@code settingsChangeDetector.initialize()}）。
 *
 * <p><b>职责</b>: 监听 4 个 settings 文件路径（user/project/local/policy）→ 变更时
 * 按路径解析 ConfigChange source → {@link HookRegistry#executeConfigChangeHooks} →
 * 任一阻断则跳过 reload（CC changeDetector.ts:296-301 {@code hasBlockingResult → return}）
 * → 否则 {@link MultiSourceHooksConfigLoader#updateHooksConfigSnapshot()}（CC fanOut
 * 等价，hooks 配置运行中生效）。policy_settings 恒不阻断（CC executeConfigChangeHooks
 * :4234-4236 blocked=false → hasBlockingConfigChangeResult 按源跳过）。
 *
 * <p><b>与 CC 的差异登记</b>：
 * <ul>
 *   <li>CC getWatchTargets（:180-249）只监听"至少含一个已存在 settings 文件的目录"，
 *       但跟踪该目录下全部潜在 settings 路径（新建文件也能被检测）→ Java 等价：按父目录
 *       分组，目录存在候选文件才注册，事件按完整路径精确匹配候选集</li>
 *   <li>CC managed-settings.d/ drop-in 目录（:233-247）映射 policySettings —— Java 部署
 *       仅 managed-file 层（{@code nexusai.policy.path}，DIF-CFG-02 登记），无 drop-in
 *       目录概念 → 仅监听 policy 单文件</li>
 *   <li>CC 无 bare 门控（main.tsx:422 无条件 initialize，区别于 skillChangeDetector
 *       :423-425 的 isBareMode 门控）→ Java run() 同样不判 bare</li>
 *   <li>CC handleDelete 有 200ms 删除-重建宽限（:324-360）→ Java 简化：delete 事件与
 *       change 同等处理（reload 幂等 + lenient 缺失跳过，可观察语义一致）</li>
 * </ul>
 *
 * <p><b>本地-only 约束</b>：本类仅本地文件监听与内存快照刷新，不向外发送任何数据。
 */
@Component
public class SettingsFileChangeWatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettingsFileChangeWatcher.class);

    /** 共享 settings 文件名（user/project 共用）。 */
    private static final String SETTINGS_FILE = "settings.json";
    /** 本地覆盖 settings 文件名（gitignored）。 */
    private static final String LOCAL_SETTINGS_FILE = "settings.local.json";

    // ─── 依赖注入（setter, POJO null-safe；镜像 SkillChangeDetector 模式）───

    /** ConfigChange hook 执行器 · CC original: executeConfigChangeHooks（changeDetector.ts:292）. */
    private volatile HookRegistry hookRegistry;
    /** 快照刷新入口 · CC original: fanOut（changeDetector.ts:300）. */
    private volatile MultiSourceHooksConfigLoader hooksConfigLoader;

    // ─── 插件 hook 热重载（[H-WF1-01] 对齐 CC loadPluginHooks.ts:255-287 setupPluginHookHotReload）───

    /** 插件 hook 全量重载触发器 · CC original: clearPluginCache + loadPluginHooks（loadPluginHooks.ts:280-284）. */
    private volatile PluginLoader pluginLoader;
    /** 上次插件相关 settings 快照 · CC original: lastPluginSettingsSnapshot（loadPluginHooks.ts:262）. */
    private volatile String lastPluginSettingsSnapshot;

    // ─── 监听路径来源（CC SETTING_SOURCES 顺序 user→project→local→policy）───

    /** user settings 基 · 保留：测试 API 兼容（{@link #setUserHome}）；
     *  生产 user settings 路径已改走 {@link NexusaiPaths#getAppConfigHomePath()}（决策 D2），
     *  本字段不再参与 user 候选路径构造。 */
    private volatile String userHome = System.getProperty("user.home", ".");
    /** project/local settings 基目录覆盖（测试注入；默认 null → 惰性 CwdResolution 项目根）.
     *  nexusai.home 已废弃（第二轮拍板），不再注入；生产项目根 = {@link CwdResolution#getOriginalCwdLayer()}
     *  （决策 D6 项目根，无会话回落 {@code user.dir}）. */
    private volatile String projectRootOverride;
    /** 企业 policy 文件路径（{@code nexusai.policy.path}；空 = 无 policy 源）. */
    private volatile String policyFilePath = "";

    // ─── WatchService 基础设施 ───

    /** 幂等守卫 · CC original: {@code initialized}（changeDetector.ts:71）. */
    private volatile boolean initialized = false;
    /** 已销毁守卫 · CC original: {@code disposed}（changeDetector.ts:72）. */
    private volatile boolean disposed = false;
    private volatile WatchService watchService;

    /** 监听目录 → 该目录下候选 settings 文件（CC dirToSettingsFiles :186-208）. */
    private final Map<Path, List<Path>> dirToSettingsFiles = new LinkedHashMap<>();

    /** WatchService 轮询线程（阻塞 take，单独线程池）· 参考 SkillChangeDetector.java:152 先例. */
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-change-watcher");
        t.setDaemon(true);
        return t;
    });

    /** Spring 无参构造器 · 依赖经 setter 注入. */
    public SettingsFileChangeWatcher() {
    }

    // ════════════════════════════════════════════════════════════════════════
    // 依赖注入
    // ════════════════════════════════════════════════════════════════════════

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHookRegistry(HookRegistry registry) {
        this.hookRegistry = registry;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHooksConfigLoader(MultiSourceHooksConfigLoader loader) {
        this.hooksConfigLoader = loader;
    }

    /** 插件 hook 全量重载触发器注入 · 测试直构可不注入（无插件场景跳过热重载）. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPluginLoader(PluginLoader pluginLoader) {
        this.pluginLoader = pluginLoader;
    }

    /** user settings 基目录 · 测试注入临时目录（POJO 默认 user.home）. */
    public void setUserHome(String userHome) {
        if (userHome != null && !userHome.isBlank()) {
            this.userHome = userHome;
        }
    }

    /** project/local settings 基目录 · 测试注入临时目录（legacy 名 nexusaiHome；默认 null →
     *  CwdResolution.getOriginalCwdLayer() 项目根，无会话回落 user.dir）. */
    public void setNexusaiHome(String nexusaiHome) {
        if (nexusaiHome != null && !nexusaiHome.isBlank()) {
            this.projectRootOverride = nexusaiHome;
        }
    }

    /** project/local settings 基目录 · 测试覆盖优先，否则 CwdResolution 项目根（决策 D6）.
     *  nexusai.home 已废弃，不再注入. */
    private String projectRoot() {
        if (projectRootOverride != null && !projectRootOverride.isBlank()) {
            return projectRootOverride;
        }
        return CwdResolution.getOriginalCwdLayer();
    }

    /** 企业 policy 文件路径 · 测试注入（POJO 默认空 = 无 policy 源）. */
    public void setPolicyFilePath(String policyFilePath) {
        this.policyFilePath = policyFilePath != null ? policyFilePath : "";
    }

    // ════════════════════════════════════════════════════════════════════════
    // 启动接线 · ApplicationRunner（对齐 CC main.tsx:422 无条件 initialize）
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    /**
     * 幂等初始化 · 对齐 CC {@code initialize()}（changeDetector.ts:85-141）.
     *
     * <p><b>无 bare 门控</b>：CC main.tsx:422 {@code void settingsChangeDetector.initialize()}
     * 无条件执行（区别于 skillChangeDetector :423-425 的 isBareMode 门控）。
     */
    public synchronized void initialize() {
        if (initialized || disposed) {
            return;
        }
        initialized = true;
        // [H-WF1-01] 捕获初始插件相关快照 · CC setupPluginHookHotReload 启动注册时捕获
        // （loadPluginHooks.ts:262），首次 policy 变更与上次快照比较；loader 未接线 → null
        // （首变更即重载）
        if (hooksConfigLoader != null) {
            lastPluginSettingsSnapshot = hooksConfigLoader.pluginAffectingSettingsSnapshot();
        }
        List<Path> candidates = settingsFileCandidates();
        if (candidates.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SettingsFileChangeWatcher] 无可监听 settings 文件路径 → 不启动 watcher");
            }
            return;
        }
        // 按父目录分组（CC dirToSettingsFiles :186-208）
        for (Path candidate : candidates) {
            Path dir = candidate.toAbsolutePath().normalize().getParent();
            if (dir == null) {
                continue;
            }
            dirToSettingsFiles.computeIfAbsent(dir, k -> new ArrayList<>()).add(candidate.toAbsolutePath().normalize());
        }
        // 仅注册"至少含一个已存在候选文件"的目录（CC dirsWithExistingFiles :210-219），
        // 但目录注册后该目录下全部候选文件（含后创建的）都参与匹配（CC :221-231）
        List<Path> dirsToWatch = new ArrayList<>();
        for (Map.Entry<Path, List<Path>> e : dirToSettingsFiles.entrySet()) {
            boolean hasExisting = false;
            for (Path f : e.getValue()) {
                if (Files.isRegularFile(f)) {
                    hasExisting = true;
                    break;
                }
            }
            if (hasExisting) {
                dirsToWatch.add(e.getKey());
            } else if (log.isDebugEnabled()) {
                log.debug("[SettingsFileChangeWatcher] 目录无已存在 settings 文件，跳过监听: {}",
                    e.getKey());
            }
        }
        if (dirsToWatch.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SettingsFileChangeWatcher] 无含已存在 settings 文件的目录 → 不启动 watcher");
            }
            return;
        }
        try {
            WatchService service = FileSystems.getDefault().newWatchService();
            watchService = service;
            for (Path dir : dirsToWatch) {
                dir.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            }
            watcherExecutor.execute(this::pollLoop);
            log.info("[SettingsFileChangeWatcher] WatchService 已启动: {} 个目录 ({} 个候选文件)",
                dirsToWatch.size(), candidates.size());
        } catch (IOException e) {
            log.warn("[SettingsFileChangeWatcher] WatchService 启动失败: {}", e.toString());
        }
    }

    /**
     * 4 个候选 settings 文件路径 · 对齐 CC SETTING_SOURCES（user→project→local→policy）+
     * getSettingsFilePathForSource。
     *
     * @return 归一化绝对路径列表（顺序 = source 优先级 user→project→local→policy）
     */
    List<Path> settingsFileCandidates() {
        List<Path> paths = new ArrayList<>();
        // user 源改走 NexusaiPaths（决策 D2，用户级 ~/.nexusai/settings.json）。
        //   userHome 字段保留作测试注入缝：默认 user.home，测试 setUserHome 后 → {userHome}/.{appName}/settings.json
        //   （生产等价 NexusaiPaths.getAppConfigHomePath()，不违背 D2；R12 修复 watcher 测试 seam）
        paths.add(Path.of(userHome, NexusaiPaths.getProjectDirName(), SETTINGS_FILE).toAbsolutePath().normalize());
        // [T3 hook 读兼容] claude 用户级只读回落源（~/.claude/settings.json，对齐 skills/commands 双目录）
        paths.add(Path.of(ClaudePaths.getClaudeConfigHomeDir(), SETTINGS_FILE).toAbsolutePath().normalize());
        // project/local 源保持项目内 .nexusai（决策 D6，项目根 = CwdResolution.getOriginalCwdLayer()）
        // 项目级目录名动态化（决策 D1/D6）：NexusaiPaths.getProjectDirName() = "." + appName
        // （生产 appName=nexusai → .nexusai；appName 变则项目级目录名全联动）
        String projectRoot = projectRoot();
        paths.add(Paths.get(projectRoot, NexusaiPaths.getProjectDirName(), SETTINGS_FILE).toAbsolutePath().normalize());
        // [T3 hook 读兼容] claude 项目级只读回落源（<projectRoot>/.claude/settings.json）
        paths.add(Paths.get(projectRoot, ".claude", SETTINGS_FILE).toAbsolutePath().normalize());
        paths.add(Paths.get(projectRoot, NexusaiPaths.getProjectDirName(), LOCAL_SETTINGS_FILE).toAbsolutePath().normalize());
        // [T3 hook 读兼容] claude 项目级本地只读回落源（<projectRoot>/.claude/settings.local.json）
        paths.add(Paths.get(projectRoot, ".claude", LOCAL_SETTINGS_FILE).toAbsolutePath().normalize());
        if (policyFilePath != null && !policyFilePath.isBlank()) {
            paths.add(Paths.get(policyFilePath).toAbsolutePath().normalize());
        }
        return paths;
    }

    /**
     * 按完整路径解析 ConfigChange source · 对齐 CC getSourceForPath（changeDetector.ts:362-375）：
     * 精确路径匹配 + SETTING_SOURCES find 顺序（user→project→local→policy 优先）。
     *
     * @param file 事件完整路径
     * @return ConfigChangeSource 值；非候选文件（如同目录无关文件）→ null
     */
    String sourceFor(Path file) {
        if (file == null) {
            return null;
        }
        Path normalized = file.toAbsolutePath().normalize();
        List<Path> candidates = settingsFileCandidates();
        // [T3 hook 读兼容] candidates 含 claude 回落路径（紧邻各源 nexusai 路径），映射到同源
        // source 名（claude 用户级 → user_settings，项目级 → project_settings/local_settings）。
        String[] sources = {"user_settings", "user_settings",
            "project_settings", "project_settings",
            "local_settings", "local_settings", "policy_settings"};
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).equals(normalized)) {
                return sources[i];
            }
        }
        return null;
    }

    /**
     * 轮询循环 · 对齐 CC handleChange/handleAdd/handleDelete（changeDetector.ts:268-360）。
     *
     * <p>每事件: 完整路径 → source（null = 非候选文件跳过，如 skills 目录归
     * SkillChangeDetector）→ executeConfigChangeHooks(source, path) → 阻断则跳过 reload
     * （CC :296-299）→ 否则 updateHooksConfigSnapshot（CC fanOut :300）。policy_settings
     * 恒不阻断（hasBlockingConfigChangeResult 按源跳过）。
     */
    private void pollLoop() {
        WatchService service = this.watchService;
        if (service == null) {
            return;
        }
        while (!disposed) {
            WatchKey key;
            try {
                key = service.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return;
            }
            Path dir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                Path fileName = (Path) event.context();
                Path fullPath = dir.resolve(fileName).toAbsolutePath().normalize();
                handleChange(fullPath);
            }
            key.reset();
        }
    }

    /**
     * 单文件变更处理 · CC handleChange 的 Java 等价（changeDetector.ts:268-302）。
     *
     * @param path 事件完整路径（绝对归一化）
     */
    void handleChange(Path path) {
        String source = sourceFor(path);
        if (source == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SettingsFileChangeWatcher] 非候选 settings 文件，跳过: {}", path);
            }
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SettingsFileChangeWatcher] 检测到变更: source={} path={}", source, path);
        }
        // 先发射 ConfigChange hook —— 若阻断则跳过应用变更（CC :290-299）
        java.util.List<GenericHook.HookResult> results = null;
        if (hookRegistry != null) {
            results = hookRegistry.executeConfigChangeHooks(source, path.toString());
        }
        if (hookRegistry != null && hookRegistry.hasBlockingConfigChangeResult(source, results)) {
            if (log.isDebugEnabled()) {
                log.debug("[SettingsFileChangeWatcher] ConfigChange hook 阻断变更: source={} path={}",
                    source, path);
            }
            return;
        }
        if (hooksConfigLoader != null) {
            try {
                hooksConfigLoader.updateHooksConfigSnapshot();
                if (log.isDebugEnabled()) {
                    log.debug("[SettingsFileChangeWatcher] 快照已刷新: source={} path={}", source, path);
                }
            } catch (Exception e) {
                // lenient：单次刷新失败不中断 watcher（CC fanOut 异常不炸 watcher）
                log.warn("[SettingsFileChangeWatcher] 快照刷新失败: {}", e.toString());
            }
        }
        // [H-WF1-01] 插件 hook 热重载判定（独立订阅 · CC loadPluginHooks.ts:264 订阅回调,
        // 与 hooks 配置快照刷新同为 fanOut 的 settingsChanged 信号订阅方）
        maybeReloadPluginHooks(source);
    }

    /**
     * [H-WF1-01] 插件 hook 热重载判定 · 对齐 CC {@code setupPluginHookHotReload} 订阅回调
     * （Open-ClaudeCode/src/utils/plugins/loadPluginHooks.ts:264-285）。
     *
     * <p><b>触发条件</b>：仅 policySettings 源进入判定（CC :265 {@code source === 'policySettings'}，
     * user/project/local 变更不触发插件 hook 热重载）—— 计算插件相关快照（4 字段 diff，
     * {@link MultiSourceHooksConfigLoader#pluginAffectingSettingsSnapshot()}）与上次比较，
     * 相等则跳过（CC :267-272）；变化 → 更新快照 + 清插件缓存 + 全量重载插件 hook
     * （CC :280-284 {@code clearPluginCache + clearPluginHookCache + loadPluginHooks}，
     * Java 由 {@link PluginLoader#refreshActivePlugins()} 表达 clear-then-register 全量 swap）。
     *
     * <p><b>与 CC 差异登记</b>：
     * <ul>
     *   <li>CC :284 {@code void loadPluginHooks()} fire-and-forget（Node event loop 不阻塞信号
     *       发射）→ Java 同步调用（watcher 单线程 daemon，插件本地 I/O 快速完成，可观察语义
     *       等价；异常捕获 fail-loud 不炸 watcher）</li>
     *   <li>CC :281 {@code clearPluginHookCache()}（loadPluginHooks memoize 失效）Java 无对应物
     *       （Java loadPluginHooks 非 memoize，每次重读 hooks.json）</li>
     * </ul>
     *
     * @param source ConfigChange source（user_settings/project_settings/local_settings/policy_settings）
     */
    void maybeReloadPluginHooks(String source) {
        if (!"policy_settings".equals(source)) {
            return; // CC loadPluginHooks.ts:265 仅 policySettings 触发
        }
        MultiSourceHooksConfigLoader loader = this.hooksConfigLoader;
        PluginLoader pluginLoader = this.pluginLoader;
        if (loader == null || pluginLoader == null) {
            return; // 未接线（测试直构 / 无插件场景）
        }
        String newSnapshot = loader.pluginAffectingSettingsSnapshot();
        if (newSnapshot != null && newSnapshot.equals(lastPluginSettingsSnapshot)) {
            if (log.isDebugEnabled()) {
                log.debug("[SettingsFileChangeWatcher] 插件 hook 跳过重载, 插件相关 settings 未变 (CC loadPluginHooks.ts:267-272)");
            }
            return;
        }
        lastPluginSettingsSnapshot = newSnapshot;
        if (log.isInfoEnabled()) {
            log.info("[SettingsFileChangeWatcher] 插件相关 settings 变更, 重载插件 hook (CC loadPluginHooks.ts:274-284)");
        }
        try {
            pluginLoader.refreshActivePlugins();
        } catch (RuntimeException e) {
            // fail-loud：单次重载失败不炸 watcher（对齐 CC fire-and-forget 未捕获）
            log.warn("[SettingsFileChangeWatcher] 插件 hook 热重载失败: {}", e.toString());
        }
    }
}
