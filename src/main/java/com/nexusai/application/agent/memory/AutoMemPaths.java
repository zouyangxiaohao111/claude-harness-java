package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * auto-memory 路径解析器 · 对齐 CC {@code Open-ClaudeCode/src/memdir/paths.ts}.
 *
 * <p>CC 真源（2026-08-05 grep -n 自验）：{@code getAutoMemPath} paths.ts:223-235；
 * {@code getAutoMemBase} paths.ts:203-205；{@code getMemoryBaseDir} paths.ts:85-90；
 * {@code getAutoMemPathOverride} paths.ts:161-166；{@code getAutoMemPathSetting} paths.ts:179-186；
 * {@code hasAutoMemPathOverride} paths.ts:194-196；{@code validateMemoryPath} paths.ts:109-150；
 * {@code isAutoMemPath} paths.ts:274-278；{@code getAutoMemDailyLogPath} paths.ts:246-251；
 * {@code getAutoMemEntrypoint} paths.ts:257-259；{@code sanitizePath} sessionStoragePortable.ts:311-319；
 * {@code findCanonicalGitRoot} utils/git.ts:97-109 + 123-183 + 195。
 *
 * <p><b>设计动机</b>：旧 Java 全局内存目录方案无 per-project 作用域（跨项目记忆互相污染）、
 * 无 override/settings 链（Cowork space-scoped mount 无法重定向）、无 validateMemoryPath
 * 安全校验。本类按 CC 实现完整路径解析链。
 *
 * <p><b>依赖注入</b>：CC 读 process.env / settings 全局；Java 后端无进程级可改 env，按代码库惯例
 * （AgentMemoryDirectory 模式）用注入 supplier + {@link #defaultInstance()} 生产默认，测试可隔离。
 */
public final class AutoMemPaths {

    private static final Logger log = LoggerFactory.getLogger(AutoMemPaths.class);

    /** CC paths.ts:92 AUTO_MEM_DIRNAME = 'memory' */
    private static final String AUTO_MEM_DIRNAME = "memory";
    /** CC paths.ts:93 AUTO_MEM_ENTRYPOINT_NAME = 'MEMORY.md' */
    private static final String AUTO_MEM_ENTRYPOINT_NAME = "MEMORY.md";
    /** CC sessionStoragePortable.ts:293 MAX_SANITIZED_LENGTH = 200 */
    private static final int MAX_SANITIZED_LENGTH = 200;
    /** CC paths.ts:153 Cowork override env var */
    public static final String COWORK_OVERRIDE_ENV = "NEXUSAI_MEMORY_PATH_OVERRIDE";
    /** CC paths.ts:87 remote memory dir env var */
    public static final String REMOTE_MEMORY_DIR_ENV = "NEXUSAI_CODE_REMOTE_MEMORY_DIR";
    /** ODF-A1: 会话 projectRoot 回落源 env · nexusai 命名 {@code NEXUSAI_PROJECT_DIR}（决策 D1/D6；
     *  CC 原 {@code CLAUDE_PROJECT_DIR}，nexusai 独立部署不依赖 CC/CCR 设置，改名无外部影响）。 */
    public static final String CLAUDE_PROJECT_DIR_ENV = "NEXUSAI_PROJECT_DIR";

    /**
     * [C-05 · OPD-CM5-C-05] DB settings 静态桥接（auto_memory_directory 前端可配列）。
     * 对齐 {@link com.nexusai.application.agent.config.MemoryBareModeConfig} 静态桥接惯例
     * （setter 注入 → 静态字段；POJO {@code new} 场景不触发 Spring → null → 走 settings 文件回落）。
     */
    private static volatile SettingsMapper staticSettingsMapper;

    /**
     * [C-05] 静态桥接 setter · Spring 装配点（SettingsService @PostConstruct）注入 SettingsMapper，
     * 使 {@link #readAutoMemoryDirectorySetting()} 能读 DB settings 列。测试（无 Spring）→ null → 回落文件。
     *
     * @param mapper DB settings mapper（可 null → 清除桥接，回落文件链）
     */
    public static void bridgeSettingsMapper(SettingsMapper mapper) {
        staticSettingsMapper = mapper;
        if (log.isDebugEnabled()) {
            log.debug("[AutoMemPaths] bridgeSettingsMapper: {}", mapper != null ? "已注入(DB autoMemoryDirectory 可用)" : "已清除(回落文件链)");
        }
    }

    /**
     * 当前会话 projectRoot · CC STATE.projectRoot（bootstrap/state.ts:277-279）等价。
     *
     * <p><b>ODF-A1-R2（返工）</b>：由 static volatile 改 {@link ThreadLocal} —— 对齐
     * {@link com.nexusai.common.RequestContext} MDC 模式。CC 单进程单会话（STATE.projectRoot
     * 无并发）；Java Web 后端同一 JVM 多会话并发（{@code chatExecutor} 线程池），static volatile
     * 会被后写会话覆盖 → 会话 A 的异步/后续内存读解析到会话 B 目录（跨会话污染）。ThreadLocal 使
     * 每个会话线程持有独立 projectRoot：会话 A 线程注入 A、会话 B 线程注入 B，互不干扰。
     *
     * <p>读取链：memory 路径的主要消费者在<b>会话线程</b>（LlmAgentLoop.run() 所在线程：
     * AutoMemPaths/MemoryPrefetcher/MemoryPromptBuilder 直接计算；prefetch 异步任务拿到的是
     * 入参 memoryDirs，不再回读 holder）；但工具池（StreamingToolExecutor.executeAsync）、
     * HOOK_EXECUTOR（HookRegistry supplyAsync）、pollScheduler 定时器（RemoteAgentTaskService
     * tick）等<b>非会话线程</b>也会经 {@link #currentSessionProjectRoot()} 惰性读取 —— 由
     * IMP-C (D2-A/F3) 的捕获-回放传播（调度线程 capture → 任务体开头 set → finally restore，
     * 见各调用点注释）使这些线程解析到会话绑定 P；未注入线程回落 CLAUDE_PROJECT_DIR env ??
     * config-home。会话结束由 LlmAgentLoop.run() finally
     * {@link #restoreCurrentProjectRoot(String)} 复位（线程池复用防泄漏），使无项目绑定会话
     * 回落 config-home 真正生效。
     *
     * <p>CC 真源：启动时 {@code realpath(cwd)} 冻结为 projectRoot（state.ts:271/279），会话中不更新
     * （state.ts:519-525）；Java 由 run() 入口一次注入（{@code resolveSessionProjectRoot()}）。
     */
    private static final ThreadLocal<String> CURRENT_PROJECT_ROOT = new ThreadLocal<>();

    /** 设置当前线程的会话 projectRoot（对齐 CC setProjectRoot 启动冻结语义 · 会话中不更新）。 */
    public static void setCurrentProjectRoot(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            CURRENT_PROJECT_ROOT.remove();
        } else {
            CURRENT_PROJECT_ROOT.set(projectRoot);
        }
    }

    /** 捕获当前线程 projectRoot 原值（供嵌套 run() 出口恢复 · 可为 null）。 */
    public static String captureCurrentProjectRoot() {
        return CURRENT_PROJECT_ROOT.get();
    }

    /** 恢复被嵌套 run() 覆盖前的 projectRoot 原值（null → 移除，回落生效）。 */
    public static void restoreCurrentProjectRoot(String prev) {
        if (prev == null || prev.isBlank()) {
            CURRENT_PROJECT_ROOT.remove();
        } else {
            CURRENT_PROJECT_ROOT.set(prev);
        }
    }

    /** 复位当前线程 projectRoot（会话结束清理 · 线程池复用防泄漏）。 */
    public static void resetCurrentProjectRoot() {
        CURRENT_PROJECT_ROOT.remove();
    }

    /**
     * 读取当前线程的会话 projectRoot。
     *
     * <p>未设置（无会话上下文：bean 构造期/非会话调用）→ 回落 {@code NEXUSAI_PROJECT_DIR} env
     * （nexusai 命名，决策 D1/D6；CC 原 {@code CLAUDE_PROJECT_DIR}）
     * 或 {@link NexusaiPaths#getAppConfigHomeDir()}（确定性非 null，绝不读 JVM 进程工作目录 ——
     * ODF-A1 验收：同一 JVM 内不同 cwd 会话必须解析出不同 memory 目录）。
     *
     * @return 非 null 的 projectRoot 字符串
     */
    public static String currentSessionProjectRoot() {
        String pr = CURRENT_PROJECT_ROOT.get();
        if (pr != null && !pr.isBlank()) {
            return pr;
        }
        String env = System.getenv(CLAUDE_PROJECT_DIR_ENV);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return NexusaiPaths.getAppConfigHomeDir();
    }

    private static final String SEP = java.io.File.separator;
    private static final char BACKSLASH = '\\';

    private final Supplier<String> projectRootSupplier;
    private final Supplier<String> memoryBaseDirSupplier;
    private final Supplier<String> overrideSupplier;
    private final Supplier<String> settingsDirSupplier;

    /**
     * getAutoMemPath memoize 等价（CC paths.ts:223-235 · OPD-R2-12）。
     *
     * <p>v1.2 复验（EV-036）：旧实现为单槽 volatile 双字段（cachedProjectRoot/cachedAutoMemPath），
     * 写序 value→key → 线程 A 写完 value 未写 key 的窗口内，线程 B（projectRoot==旧 key）命中旧 key
     * 取到 A 的新 value → 跨会话 memory 路径错配。CC lodash memoize 按 projectRoot 建独立 Map 槽
     * （JS 单线程 + 每 key 独立条目）。现改 {@link ConcurrentHashMap} 按 projectRoot 独立槽，
     * 消除 value→key 写序竞态（生产 defaultInstance 单例 @Bean 多会话线程共享）。
     */
    private final ConcurrentHashMap<String, String> autoMemPathCache = new ConcurrentHashMap<>();

    /**
     * 注入式构造器。
     *
     * @param projectRootSupplier   CC getProjectRoot()（bootstrap/state.ts:511-513）等价
     * @param memoryBaseDirSupplier CC getMemoryBaseDir() 的 memoryBase 供应（env CLAUDE_CODE_REMOTE_MEMORY_DIR 或 null）
     * @param overrideSupplier      CC getAutoMemPathOverride() 的 env 供应（CLAUDE_COWORK_MEMORY_PATH_OVERRIDE）
     * @param settingsDirSupplier   CC getAutoMemPathSetting() 的 settings 供应（autoMemoryDirectory，可信源读取）
     */
    public AutoMemPaths(Supplier<String> projectRootSupplier,
                        Supplier<String> memoryBaseDirSupplier,
                        Supplier<String> overrideSupplier,
                        Supplier<String> settingsDirSupplier) {
        this.projectRootSupplier = Objects.requireNonNull(projectRootSupplier);
        this.memoryBaseDirSupplier = Objects.requireNonNull(memoryBaseDirSupplier);
        this.overrideSupplier = Objects.requireNonNull(overrideSupplier);
        this.settingsDirSupplier = Objects.requireNonNull(settingsDirSupplier);
    }

    /**
     * 测试缝：覆写 {@link #COWORK_OVERRIDE_ENV} env 读取（JDK 9+ 模块封装下 {@code System.getenv}
     * 不可就地修改 · 对齐同库 MemoryBareModeConfig.setEnvOverride / BuiltInCommands.envProvider
     * 惯例）。{@code null} → 读真实 env（生产行为不变）；测试用后必须复位，防跨测试污染。
     */
    static volatile String overrideEnvSeam;

    /** 设置 override env 测试缝（{@code null} = 复位为读真实 env）。 */
    static void setOverrideEnvForTest(String value) {
        overrideEnvSeam = value;
    }

    /**
     * 生产默认实例 · ODF-A1：projectRoot 从 {@link #currentSessionProjectRoot()}
     * 惰性读取（per-session ThreadLocal 注入，绝不读 JVM 进程工作目录）。
     *
     * <p>bean 构造期/无会话上下文 → CLAUDE_PROJECT_DIR env ?? config home（确定性回落）。
     * 有会话时 LlmAgentLoop.run() 入口经 {@link #setCurrentProjectRoot(String)} 注入，
     * 本 supplier 即解析到该线程会话的 projectRoot（对齐 CC getProjectRoot 全局态，
     * ThreadLocal 按会话线程隔离 —— 多会话并发各自解析本会话目录）。
     */
    public static AutoMemPaths defaultInstance() {
        return new AutoMemPaths(
            AutoMemPaths::currentSessionProjectRoot,
            () -> System.getenv(REMOTE_MEMORY_DIR_ENV),
            () -> overrideEnvSeam != null ? overrideEnvSeam : System.getenv(COWORK_OVERRIDE_ENV),
            AutoMemPaths::readAutoMemoryDirectorySetting);
    }

    // ════════════════════════════════════════════════════════════════
    // getMemoryBaseDir · paths.ts:85-90
    // ════════════════════════════════════════════════════════════════

    /**
     * persistent memory 存储基目录 · CC original: {@code getMemoryBaseDir}（paths.ts:85-90）
     * <pre>{@code
     * return process.env.CLAUDE_CODE_REMOTE_MEMORY_DIR ?? getClaudeConfigHomeDir()
     * }</pre>
     *
     * <p><b>决策 D1</b>：默认 config home 改 nexusai 自有根 {@code ~/.{appName}}（镜像 CC
     * {@code getClaudeConfigHomeDir()} → 我们 mirror {@code getNexusaiConfigHomeDir()}）——
     * AutoMem/agent-memory USER scope 基址随之迁移 nexusai，DB/文件里既有 {@code ~/.claude}
     * 记忆路径由各读取方负责回落兼容。
     *
     * @return 基目录（CLAUDE_CODE_REMOTE_MEMORY_DIR 或 ~/.{appName}）
     */
    public String getMemoryBaseDir() {
        String remote = memoryBaseDirSupplier.get();
        if (remote != null && !remote.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoMemPaths] memoryBase 来自 CLAUDE_CODE_REMOTE_MEMORY_DIR: {}", remote);
            }
            return remote;
        }
        return NexusaiPaths.getAppConfigHomeDir();
    }

    // ════════════════════════════════════════════════════════════════
    // validateMemoryPath · paths.ts:109-150
    // ════════════════════════════════════════════════════════════════

    /**
     * 校验并归一化候选 auto-memory 路径 · CC original: {@code validateMemoryPath}（paths.ts:109-150）。
     *
     * <p>SECURITY（CC :98-107 注释）：作为 read-allowlist root，以下路径危险：
     * <ul>
     *   <li>相对路径（{@code ../foo}）— 会被当作 CWD 相对解释</li>
     *   <li>根/近根（长度 &lt; 3）：{@code "/"} → strip 后 {@code ""}，{@code "/a"} 过短</li>
     *   <li>Windows 盘符根（{@code C:\} → strip 后 {@code C:}）</li>
     *   <li>UNC（{@code \\server\share}）— 网络路径，不透明信任边界</li>
     *   <li>null 字节 — normalize 后仍存活，syscall 中可截断</li>
     * </ul>
     *
     * @param raw         候选路径
     * @param expandTilde settings 路径允许 {@code ~/} 展开（env override 不允许）
     * @return 唯一尾分隔符的归一化绝对路径；未设置/被拒绝 → null（CC undefined）
     */
    public static String validateMemoryPath(String raw, boolean expandTilde) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        // null 字节在 Path 构造前拒绝（Java Path 会抛 InvalidPathException）
        if (raw.indexOf(0) >= 0) {
            return null;
        }
        String candidate = raw;
        // CC :122-135 settings 路径支持 ~/ 展开（用户友好）；env override 不支持
        if (expandTilde
            && (candidate.startsWith("~/") || candidate.startsWith("~\\"))) {
            String rest = candidate.substring(2);
            // OPD-R2-01（D1，high）：平凡余部拒绝对齐 Node normalize 语义（paths.ts:127-133）。
            // 实测（JDK 25）：Paths.get("foo/..").normalize() = ""（Node normalize('foo/..')='.'）、
            // Paths.get(".").normalize() = ""（Node '.'）→ 空串判定即覆盖 `~/`、`~/.`、`~/foo/..`；
            // ".." 判定覆盖 `~/..`、`~/foo/../..`。拒绝防止展开为 $HOME 或祖先 → isAutoMemPath
            // 全匹配 $HOME + 读写 carve-out 静默放行（权限面扩张）。
            String restNorm = safeNormalize(rest.isEmpty() ? "." : rest);
            if (restNorm == null || restNorm.isEmpty()
                || ".".equals(restNorm) || "..".equals(restNorm)) {
                return null;
            }
            candidate = Paths.get(System.getProperty("user.home"), rest).toString();
        }
        String normalized = safeNormalize(candidate);
        if (normalized == null) {
            return null;
        }
        normalized = stripTrailingSeparators(normalized);
        if (!isAbsoluteString(normalized)
            || normalized.length() < 3
            || normalized.matches("^[A-Za-z]:$")
            || startsWithBackslashBackslash(normalized)
            || normalized.startsWith("//")
            || normalized.indexOf(0) >= 0) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[AutoMemPaths] validateMemoryPath 通过: {}", normalized);
        }
        // OPD-R2-06：CC paths.ts:149 (normalized + sep).normalize('NFC')
        return ClaudePaths.normalizeNfc(normalized + SEP);
    }

    /** 词法 normalize（PlatformPath），非法路径（null 字节等）→ null。 */
    private static String safeNormalize(String s) {
        try {
            return Paths.get(s).normalize().toString();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 去尾部分隔符（CC {@code normalize(candidate).replace(/[/\\]+$/, '')}，paths.ts:138）。 */
    private static String stripTrailingSeparators(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '/' || c == BACKSLASH) {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    private static boolean isAbsoluteString(String s) {
        try {
            return Paths.get(s).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static boolean startsWithBackslashBackslash(String s) {
        return s.length() >= 2 && s.charAt(0) == BACKSLASH && s.charAt(1) == BACKSLASH;
    }

    // ════════════════════════════════════════════════════════════════
    // getAutoMemPathOverride / getAutoMemPathSetting / hasAutoMemPathOverride
    // ════════════════════════════════════════════════════════════════

    /**
     * env 全路径 override · CC original: {@code getAutoMemPathOverride}（paths.ts:161-166）
     * <pre>{@code
     * return validateMemoryPath(process.env.CLAUDE_COWORK_MEMORY_PATH_OVERRIDE, false)
     * }</pre>
     * 设置后 {@code getAutoMemPath()}/{@code getAutoMemEntrypoint()} 直接返回该路径。
     */
    public String getAutoMemPathOverride() {
        return validateMemoryPath(overrideSupplier.get(), false);
    }

    /**
     * settings.json override · CC original: {@code getAutoMemPathSetting}（paths.ts:179-186）
     * <pre>{@code
     * const dir = getSettingsForSource('policySettings')?.autoMemoryDirectory
     *          ?? getSettingsForSource('flagSettings')?.autoMemoryDirectory
     *          ?? getSettingsForSource('localSettings')?.autoMemoryDirectory
     *          ?? getSettingsForSource('userSettings')?.autoMemoryDirectory
     * return validateMemoryPath(dir, true)
     * }</pre>
     *
     * <p>SECURITY（CC :172-177）：projectSettings（仓库内 .claude/settings.json，攻击者可控制）
     * 必须排除 —— 恶意仓库可设 {@code autoMemoryDirectory: "~/.ssh"} 借写 carve-out 静默写敏感目录。
     * Java 后端可用可信源 = localSettings + userSettings（config home 下）；policy/flag 无等价实现。
     */
    public String getAutoMemPathSetting() {
        return validateMemoryPath(settingsDirSupplier.get(), true);
    }

    /**
     * override 是否生效 · CC original: {@code hasAutoMemPathOverride}（paths.ts:194-196）。
     * SDK 调用方显式 opt-in auto-memory 机制的信号（QueryEngine.ts:316-323 用它门控 memory 注入）。
     */
    public boolean hasAutoMemPathOverride() {
        return getAutoMemPathOverride() != null;
    }

    /** 测试/重配后清除 memoize 缓存（全部 projectRoot 槽）。 */
    public void clearCache() {
        autoMemPathCache.clear();
    }

    /** 当前 projectRoot（CC getProjectRoot 等价 · 本实例注入/回落的 root）。 */
    public String projectRoot() {
        return projectRootSupplier.get();
    }

    // ════════════════════════════════════════════════════════════════
    // getAutoMemBase / getAutoMemPath · paths.ts:203-235
    // ════════════════════════════════════════════════════════════════

    /**
     * canonical git 根（可得时），否则 projectRoot · CC original: {@code getAutoMemBase}（paths.ts:203-205）
     * <pre>{@code
     * return findCanonicalGitRoot(getProjectRoot()) ?? getProjectRoot()
     * }</pre>
     * 用 findCanonicalGitRoot 使同一仓库的所有 worktree 共享一个 auto-memory 目录
     * （anthropics/claude-code#24382）。
     *
     * <p>[A1 重做 2026-09-04] 委托 {@link #getAutoMemBase(String)}（显式 projectRoot 解析），
     * 本方法读注入 supplier（ThreadLocal currentSessionProjectRoot）。异步 fork 线程不读本方法
     * （无 ThreadLocal），改由调用方在会话线程按显式 projectRoot 调重载后传参。
     */
    public String getAutoMemBase() {
        return getAutoMemBase(projectRootSupplier.get());
    }

    /**
     * 按显式 projectRoot 解析 canonical git 根 · CC original: {@code getAutoMemBase}（paths.ts:203-205）。
     *
     * <p>[A1 重做] 新增显式重载 —— 不依赖 supplier/ThreadLocal，调用方持有确切 projectRoot 时
     * 直接传（LlmAgentLoop 会话线程用 boundProject 解析 → 传 extract/dream fork 消费）。
     *
     * @param explicitProjectRoot 会话绑定 projectRoot（boundProject/originalCwd；null → NFC 空回落）
     * @return canonical git root（可得时），否则 NFC(explicitProjectRoot)
     */
    public String getAutoMemBase(String explicitProjectRoot) {
        String projectRoot = explicitProjectRoot;
        String canonical = findCanonicalGitRoot(projectRoot);
        if (canonical != null) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoMemPaths] getAutoMemBase 使用 canonical git root: {}", canonical);
            }
            return canonical;
        }
        // OPD-R2-06：CC projectRoot 在注入点即 NFC（state.ts:271/274 realpath(cwd).normalize('NFC')）；
        // Java 注入链在 LlmAgentLoop（共享文件，串行任务域），本回落点补 NFC 保证产出字节一致。
        return ClaudePaths.normalizeNfc(projectRoot);
    }

    /**
     * auto-memory 目录路径 · CC original: {@code getAutoMemPath}（paths.ts:223-235）。
     *
     * <p>[A1 重做 2026-09-04] 委托 {@link #getAutoMemPath(String)}（显式 projectRoot 解析），
     * 本方法读注入 supplier（ThreadLocal currentSessionProjectRoot）。<b>异步 fork 线程不读本
     * 方法</b>（无 ThreadLocal 回落 config-home）—— 改由调用方在会话线程按显式 projectRoot 调
     * 重载后传参（对齐 CC extractMemories.ts:339 runExtraction 先 getAutoMemPath 再 fork）。
     *
     * @return 带唯一尾分隔符的 auto-memory 目录
     */
    public String getAutoMemPath() {
        return getAutoMemPath(projectRootSupplier.get());
    }

    /**
     * 按显式 projectRoot 解析 auto-memory 目录 · CC original: {@code getAutoMemPath}（paths.ts:223-235）。
     *
     * <p>[A1 重做] 新增显式重载 —— 不依赖 supplier/ThreadLocal，调用方持有确切 projectRoot 时
     * 直接传（LlmAgentLoop 会话线程用 boundProject 解析 → 传 extract/dream fork 消费）。这也对齐
     * CC 真实形态：CC 每次 runExtraction/runAutoDream 在会话上下文开头 getAutoMemPath() 算一次，
     * 目录是"算好传进 fork"而非 fork 内现算。
     *
     * <p>解析顺序（paths.ts:223-235）：
     * <ol>
     *   <li>CLAUDE_COWORK_MEMORY_PATH_OVERRIDE（全路径 override，Cowork 用）</li>
     *   <li>settings.json autoMemoryDirectory（可信源 policy/local/user，排除 projectSettings）</li>
     *   <li>{@code <memoryBase>/projects/<sanitizePath(getAutoMemBase(explicitRoot))>/memory/}（per-project 默认）</li>
     * </ol>
     *
     * <p>memoize（CC :216-234 注释）：render-path 调用方（collapseReadSearchGroups → isAutoManagedMemoryFile）
     * 每条 tool-use 消息触发多次；keyed on projectRoot 使测试 mock 变更时重算，env/settings 会话稳定。
     *
     * @param explicitProjectRoot 会话绑定 projectRoot（boundProject/originalCwd；null → 按空解析）
     * @return 带唯一尾分隔符的 auto-memory 目录
     */
    public String getAutoMemPath(String explicitProjectRoot) {
        String projectRoot = explicitProjectRoot;
        // OPD-R2-12：按 projectRoot 独立槽（CC lodash memoize paths.ts:223-235）——
        // 旧单槽 volatile 双字段 value→key 写序存在跨会话错配竞态（EV-036，medium）。
        // projectRoot 为 null（异常注入）→ 不缓存直接计算。
        if (projectRoot != null) {
            String cached = autoMemPathCache.get(projectRoot);
            if (cached != null) {
                return cached;
            }
        }
        String override = getAutoMemPathOverride();
        if (override == null) {
            override = getAutoMemPathSetting();
        }
        if (override != null) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoMemPaths] getAutoMemPath 使用 override/settings 路径: {}", override);
            }
            return cachePut(projectRoot, override);
        }
        String projectsDir = Paths.get(getMemoryBaseDir(), "projects").toString();
        // OPD-R2-06：CC paths.ts:232 (...+sep).normalize('NFC')
        String path = ClaudePaths.normalizeNfc(
            Paths.get(projectsDir, sanitizePath(getAutoMemBase(projectRoot)), AUTO_MEM_DIRNAME).toString() + SEP);
        if (log.isDebugEnabled()) {
            log.debug("[AutoMemPaths] getAutoMemPath 计算 per-project 路径: {}", path);
        }
        return cachePut(projectRoot, path);
    }

    /** 按 projectRoot 槽写入缓存（putIfAbsent：并发下先算者胜，与 lodash memoize 一致）。 */
    private String cachePut(String projectRoot, String path) {
        if (projectRoot == null) {
            return path;
        }
        String prev = autoMemPathCache.putIfAbsent(projectRoot, path);
        return prev != null ? prev : path;
    }

    /**
     * auto-memory entrypoint（MEMORY.md）· CC original: {@code getAutoMemEntrypoint}（paths.ts:257-259）。
     * 与 getAutoMemPath 同解析顺序。
     */
    public String getAutoMemEntrypoint() {
        return Paths.get(getAutoMemPath(), AUTO_MEM_ENTRYPOINT_NAME).toString();
    }

    /**
     * 指定日期的 daily log 路径 · CC original: {@code getAutoMemDailyLogPath}（paths.ts:246-251）。
     * 形状：{@code <autoMemPath>/logs/YYYY/MM/YYYY-MM-DD.md}（KAIROS assistant 模式 append 用）。
     */
    public String getAutoMemDailyLogPath(LocalDate date) {
        String yyyy = String.format("%04d", date.getYear());
        String mm = String.format("%02d", date.getMonthValue());
        String dd = String.format("%02d", date.getDayOfMonth());
        return Paths.get(getAutoMemPath(), "logs", yyyy, mm, yyyy + "-" + mm + "-" + dd + ".md").toString();
    }

    /**
     * 今日 daily log 路径 · CC original: {@code getAutoMemDailyLogPath(date: Date = new Date())}
     * （paths.ts:246，date 缺省=今天 · OPD-R2-04）。
     * [IMP-MV2-21 登记] KAIROS daily-log 不接线（登记关闭）：CC 侧本导出同样 0 调用方
     * （grep Open-ClaudeCode 仅定义处 1 命中）；KAIROS prompt 以路径「模式」
     * {@code logs/YYYY/MM/YYYY-MM-DD.md} 描述（memdir.ts:335，buildAssistantDailyLogPrompt 不消费本方法）。
     * 保留本重载 = CC 默认参数（date 缺省=今天）的 1:1 镜像（E3 锁定 AutoMemPathsTest:540-544）。
     */
    public String getAutoMemDailyLogPath() {
        return getAutoMemDailyLogPath(LocalDate.now());
    }

    /**
     * 绝对路径是否在 auto-memory 目录内 · CC original: {@code isAutoMemPath}（paths.ts:274-278）
     * <pre>{@code
     * const normalizedPath = normalize(absolutePath)
     * return normalizedPath.startsWith(getAutoMemPath())
     * }</pre>
     * SECURITY：normalize 防 {@code ..} 段路径遍历绕过；getAutoMemPath 尾分隔符契约防前缀攻击
     * （{@code /foo/team-evil} 不匹配 {@code /foo/team/}）。
     */
    public boolean isAutoMemPath(String absolutePath) {
        if (absolutePath == null) {
            return false;
        }
        String normalizedPath = safeNormalize(absolutePath);
        if (normalizedPath == null) {
            return false;
        }
        return normalizedPath.startsWith(getAutoMemPath());
    }

    // ════════════════════════════════════════════════════════════════
    // sanitizePath · sessionStoragePortable.ts:311-319
    // ════════════════════════════════════════════════════════════════

    /**
     * 使字符串可安全用作目录/文件名 · CC original: {@code sanitizePath}
     * （sessionStoragePortable.ts:311-319）：非字母数字 → '-'；长度 &gt; 200 截断 + hash
     * base36 后缀（保证唯一且不超 255 字节文件系统限制）。
     *
     * <p><b>OPD-R2-05（D4/D5）</b>：字符集按 CC {@code [^a-zA-Z0-9]}（ASCII）——旧实现
     * {@code Character.isLetterOrDigit}（Unicode，中文/重音保留）对非 ASCII 项目根目录产出
     * 不同目录名（CC 全替换为 '-'）。
     *
     * <p><b>OPD-CM5-C-06</b>：超长路径 hash 对齐 CC 生产（Bun runtime）{@code Bun.hash}
     * （sessionStoragePortable.ts:316-317）——wyhash v4 final（seed=0，UTF-8 bytes）base36 后缀。
     * 旧实现用 djb2（CC Node 回退 {@code simpleHash}，hash.ts:7-13），对 &gt;200 字符项目根与
     * CC 生产产出不同目录名（△2）；现接 Bun 等价使跨运行方目录名一致。
     */
    public static String sanitizePath(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(isAsciiAlphanumeric(c) ? c : '-');
        }
        String sanitized = sb.toString();
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return sanitized;
        }
        String hash = Long.toUnsignedString(bunHash(name), 36);
        return sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-" + hash;
    }

    /** CC {@code /[^a-zA-Z0-9]/}（ASCII 字母数字，sessionStoragePortable.ts:312 · OPD-R2-05）。 */
    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * CC {@code Bun.hash(name)} 等价（sessionStoragePortable.ts:316-317）：对 name 的 UTF-8 bytes
     * 施加 wyhash v4 final（seed=0）返回 u64。CC 生产（Bun runtime）用 {@code Bun.hash}；Java 以
     * wyhash 逐字节等价实现，测试向量交叉验证：{@code Bun.hash("hello world") === 0x668d5e431c3b2573}
     * （Open-ClaudeCode 侧 test/js/bun/util/hash.test.js）。旧实现 djb2Hash 仅等价 CC Node 回退，
     * 已删除（OPD-CM5-C-06）。
     */
    private static long bunHash(String name) {
        byte[] data = name.getBytes(StandardCharsets.UTF_8);
        return wyhash(data, data.length, 0L);
    }

    /** CC wyhash 默认 secret {@code _wyp}（wyhash.h v4 final 四常量）。 */
    private static final long[] WY_SECRET = {
            0xa0761d6478bd642fL, 0xe7037ed1a0b428dbL, 0x8ebc6af09c88c6e3L, 0x589965cc75374cc3L
    };

    /**
     * wyhash v4 final（WYHASH_CONDOM=1，小端）· 与 CC Bun.hash 字节级等价。
     * 结构对齐 wyhash.h：seed 预混、len≤16 短分支、len&gt;48 三轮 see1/see2 循环
     * （边界为严格 {@code > 48} —— 对齐 wyhash.h {@code if(_unlikely(i>48))}，若用 {@code >=48}
     * 则 len=48/96/… 会整段消费后尾部双读，产出与 Bun 不同的 hash，已返工修复）、
     * 16-byte 尾段、末段 MUM 收尾。
     */
    private static long wyhash(byte[] p, int len, long seed) {
        long s0 = WY_SECRET[0], s1 = WY_SECRET[1], s2 = WY_SECRET[2], s3 = WY_SECRET[3];
        int idx = 0;
        seed ^= wymix(seed ^ s0, s1);
        long a, b;
        if (len <= 16) {
            if (len >= 4) {
                a = (wyr4(p, idx) << 32) | wyr4(p, idx + ((len >> 3) << 2));
                b = (wyr4(p, idx + len - 4) << 32) | wyr4(p, idx + len - 4 - ((len >> 3) << 2));
            } else if (len > 0) {
                a = wyr3(p, idx, len);
                b = 0L;
            } else {
                a = 0L;
                b = 0L;
            }
        } else {
            int i = len;
            if (i > 48) {
                long see1 = seed, see2 = seed;
                do {
                    seed = wymix(wyr8(p, idx) ^ s1, wyr8(p, idx + 8) ^ seed);
                    see1 = wymix(wyr8(p, idx + 16) ^ s2, wyr8(p, idx + 24) ^ see1);
                    see2 = wymix(wyr8(p, idx + 32) ^ s3, wyr8(p, idx + 40) ^ see2);
                    idx += 48;
                    i -= 48;
                } while (i > 48);
                seed ^= see1 ^ see2;
            }
            while (i > 16) {
                seed = wymix(wyr8(p, idx) ^ s1, wyr8(p, idx + 8) ^ seed);
                i -= 16;
                idx += 16;
            }
            a = wyr8(p, idx + i - 16);
            b = wyr8(p, idx + i - 8);
        }
        a ^= s1;
        b ^= seed;
        long[] mum = wymum(a, b);
        a = mum[0];
        b = mum[1];
        return wymix(a ^ s0 ^ len, b ^ s1);
    }

    /** MUM：无符号 64×64 → 128 位乘积（WYHASH_CONDOM=1，A=低 64 位，B=高 64 位）。 */
    private static long[] wymum(long a, long b) {
        return new long[]{a * b, Math.unsignedMultiplyHigh(a, b)};
    }

    /** 乘法-异或混合（wyhash.h {@code _wymix}）。 */
    private static long wymix(long a, long b) {
        long[] m = wymum(a, b);
        return m[0] ^ m[1];
    }

    /** 小端读 8 字节（wyhash.h {@code _wyr8}，Bun 运行平台小端）。 */
    private static long wyr8(byte[] p, int off) {
        return ((long) (p[off] & 0xff))
                | ((long) (p[off + 1] & 0xff) << 8)
                | ((long) (p[off + 2] & 0xff) << 16)
                | ((long) (p[off + 3] & 0xff) << 24)
                | ((long) (p[off + 4] & 0xff) << 32)
                | ((long) (p[off + 5] & 0xff) << 40)
                | ((long) (p[off + 6] & 0xff) << 48)
                | ((long) (p[off + 7] & 0xff) << 56);
    }

    /** 小端读 4 字节（wyhash.h {@code _wyr4}）。 */
    private static long wyr4(byte[] p, int off) {
        return ((p[off] & 0xff))
                | ((long) (p[off + 1] & 0xff) << 8)
                | ((long) (p[off + 2] & 0xff) << 16)
                | ((long) (p[off + 3] & 0xff) << 24);
    }

    /** 小端读 1-3 字节（wyhash.h {@code _wyr3}）。 */
    private static long wyr3(byte[] p, int off, int k) {
        return ((long) (p[off] & 0xff) << 16) | ((long) (p[off + (k >> 1)] & 0xff) << 8) | (p[off + k - 1] & 0xff);
    }

    // ════════════════════════════════════════════════════════════════
    // findCanonicalGitRoot · git.ts:97-109 + 123-183 + 195
    // ════════════════════════════════════════════════════════════════

    /**
     * 找到 canonical git 仓库根 · CC original: {@code findCanonicalGitRoot}（git.ts:195）。
     * <ol>
     *   <li>findGitRoot：从 startPath 向上找含 {@code .git} 目录或文件的最近祖先（git.ts:27-86）</li>
     *   <li>resolveCanonicalRoot：worktree（.git 是文件）→ 读 gitdir/commondir 链解析到主仓库工作目录
     *       （git.ts:123-183）；子模块（无 commondir）回退输入 root</li>
     * </ol>
     *
     * @return canonical 根；非 git 目录 → null
     */
    public static String findCanonicalGitRoot(String startPath) {
        if (startPath == null || startPath.isEmpty()) {
            return null;
        }
        Path gitRoot = findGitRoot(Paths.get(startPath));
        if (gitRoot == null) {
            return null;
        }
        return resolveCanonicalRoot(gitRoot);
    }

    // [C-4 登记 · IMP-MV2-40] △-4：CC findGitRootImpl/resolveCanonicalRoot 各 memoizeWithLRU(50)
    //   （git.ts:27/:123）；Java 无缓存，每次调用全链 file IO（getAutoMemBase → findCanonicalGitRoot
    //   走盘）。热路径（permission 检查/prompt 构建）性能差异，无正确性影响 —— 登记不修。
    /** findGitRoot（git.ts:27-86）：向上找 .git 目录/文件（worktree/submodule 用 .git 文件）。 */
    private static Path findGitRoot(Path startPath) {
        Path current = startPath.toAbsolutePath().normalize();
        Path root = current.getRoot();
        while (current != null) {
            Path gitPath = current.resolve(".git");
            if (Files.exists(gitPath) && (Files.isDirectory(gitPath) || Files.isRegularFile(gitPath))) {
                return current;
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) {
                break;
            }
            current = parent;
        }
        // 检查根目录（git.ts:60-75）
        if (root != null) {
            Path gitPath = root.resolve(".git");
            if (Files.exists(gitPath) && (Files.isDirectory(gitPath) || Files.isRegularFile(gitPath))) {
                return root;
            }
        }
        return null;
    }

    /**
     * resolveCanonicalRoot（git.ts:123-183）：worktree → 主仓库根。
     * 常规仓库 no-op；worktree（.git 文件含 gitdir:）跟随 gitdir → commondir 链。
     * 安全校验（git.ts:142-170）：worktreeGitDir 必须是 commondir/worktrees 直接子目录 +
     * gitdir 回链指向 &lt;gitRoot&gt;/.git（防恶意仓库借权威 worktree 条目）。
     * 任何读取失败 → 回退输入 root（CC catch → return gitRoot）。
     */
    private static String resolveCanonicalRoot(Path gitRoot) {
        try {
            Path gitPath = gitRoot.resolve(".git");
            if (!Files.isRegularFile(gitPath)) {
                // .git 是目录 = 常规仓库（readFileSync 抛 EISDIR 分支，git.ts:128）
                return gitRoot.toString();
            }
            String gitContent = Files.readString(gitPath).trim();
            if (!gitContent.startsWith("gitdir:")) {
                return gitRoot.toString();
            }
            // CC: resolve(gitRoot, gitdirContent) —— gitdir 内容相对 <gitRoot> 解析（git.ts:132-135）
            Path worktreeGitDir = gitRoot.resolve(gitContent.substring("gitdir:".length()).trim())
                .toAbsolutePath().normalize();
            Path commonDirPath = worktreeGitDir.resolve("commondir");
            if (!Files.isRegularFile(commonDirPath)) {
                // 子模块无 commondir → 回退（git.ts:137）
                return gitRoot.toString();
            }
            // CC: resolve(worktreeGitDir, commonDirContent)（git.ts:138-141）
            Path commonDir = worktreeGitDir.resolve(Files.readString(commonDirPath).trim())
                .toAbsolutePath().normalize();
            // 安全校验 (1)：worktreeGitDir 是 <commonDir>/worktrees 直接子目录（git.ts:156）
            if (worktreeGitDir.getParent() == null
                || !worktreeGitDir.getParent().equals(commonDir.resolve("worktrees"))) {
                return gitRoot.toString();
            }
            // 安全校验 (2)：<worktreeGitDir>/gitdir 回链到 <gitRoot>/.git（git.ts:165-170）
            Path gitdirFile = worktreeGitDir.resolve("gitdir");
            if (!Files.isRegularFile(gitdirFile)) {
                return gitRoot.toString();
            }
            // OPD-R2-08（△-8）：CC 回链侧 realpathSync(gitdir 内容)（git.ts:165-167）——
            // git 以 strbuf_realpath 写回链（symlink 已解析），而 findGitRoot 只做词法解析；
            // 经 symlink 路径访问的 worktree（CC 注释场景：macOS /tmp→/private/tmp）必须
            // realpath 后才能与 realpath(gitRoot)+'.git' 比对，否则回链比对失败 → 回退
            // worktree 根 → worktree 与主仓 memory 目录分裂。toRealPath 失败（路径不存在）
            // → 走 catch 回退 gitRoot（对齐 CC catch → return gitRoot）。
            Path backlink = Paths.get(Files.readString(gitdirFile).trim()).toRealPath();
            Path expected = gitRoot.toRealPath().resolve(".git");
            if (!backlink.equals(expected)) {
                return gitRoot.toString();
            }
            // bare-repo worktree：commonDir 不在工作目录内，用 commonDir 本身（git.ts:171-175）
            if (!".git".equals(commonDir.getFileName().toString())) {
                // OPD-R2-06：CC git.ts:174 commonDir.normalize('NFC')
                return ClaudePaths.normalizeNfc(commonDir.toString());
            }
            Path parent = commonDir.getParent();
            // OPD-R2-06：CC git.ts:176 dirname(commonDir).normalize('NFC')
            return parent != null
                ? ClaudePaths.normalizeNfc(parent.toString())
                : gitRoot.toString();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoMemPaths] resolveCanonicalRoot 失败，回退 gitRoot: {}", gitRoot, e);
            }
            return gitRoot.toString();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // settings 读取 · getAutoMemPathSetting 可信源（排除 projectSettings）
    // ════════════════════════════════════════════════════════════════

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从可信 settings 源读取 {@code autoMemoryDirectory} · CC {@code getAutoMemPathSetting}
     * 四源链 policy→flag→local→user（paths.ts:179-186 + settings.ts:240-307）。
     *
     * <p><b>[C-05 · OPD-CM5-C-05] DB settings 列优先</b>（V34 列 auto_memory_directory，前端
     * "模型配置页-环境配置"可配置，对齐 CC 给默认值）：{@link #bridgeSettingsMapper} 已注入时先读
     * DB settings 单例行（id=1）；未配置 → 回落 settings 文件链。
     *
     * <p><b>OPD-R2-02（D2，第二轮拍板修订）</b>：所有 claude settings.json 一律不读（用户级
     * {@code ~/.claude/settings.json} + 项目级 {@code .claude/settings.json} +
     * {@code .claude/settings.local.json}）——D2 复刻版改造改读 nexusai 自有 settings 结构：
     * local 源 {@code {projectRoot}/.nexusai/settings.local.json}（项目内 nexusai 自有目录，对齐
     * D6 迁移目标；per-session 项目目录语义保持 CC getSettingsRootPathForSource('localSettings')
     * settings.ts:244-246/283-287/304-305）；user 源 {@code {configHome}/settings.json} =
     * {@link NexusaiPaths#getAppConfigHomeDir()}/settings.json（{@code ~/.nexusai/settings.json}，
     * 对齐 settings.ts:241-242/277-281 结构，nexusai 自有根）。policy/flag 源 Java 无对应基础设施
     * （managed settings/flag 通道）→ 登记 N/A。projectSettings（仓库内 .claude/settings.json）
     * 仍不读取 —— 对齐 CC 安全注释（paths.ts:172-177：恶意仓库可设
     * {@code autoMemoryDirectory: "~/.ssh"} 借写 carve-out 静默写敏感目录）。
     *
     * <p>[C-1 前瞻登记 · IMP-MV2-40] △-1：本实现为 4→2 源链（仅 local/user）——未来接入 managed
     * settings 通道时按 CC 四源链重做；保持「排除 projectSettings」安全不变式。
     *
     * @return autoMemoryDirectory 值或 null
     */
    private static String readAutoMemoryDirectorySetting() {
        String fromDb = readAutoMemoryDirectoryFromDb();
        if (fromDb != null) {
            return fromDb;
        }
        String projectRoot = currentSessionProjectRoot();
        String fromLocal = readAutoMemoryDirectoryFromFile(
            Paths.get(projectRoot, NexusaiPaths.getProjectDirName(), "settings.local.json"));
        if (fromLocal != null) {
            return fromLocal;
        }
        return readAutoMemoryDirectoryFromFile(
            Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json"));
    }

    /**
     * [C-05] 从 DB settings 单例行（id=1）读取 {@code auto_memory_directory}（V34 列）。
     * 未桥接（null）/行缺失/列未配置 → null（回落 settings 文件链）。
     */
    private static String readAutoMemoryDirectoryFromDb() {
        SettingsMapper mapper = staticSettingsMapper;
        if (mapper == null) {
            return null;
        }
        try {
            SettingsRecord row = mapper.selectOneById(1);
            if (row != null) {
                String dir = row.getAutoMemoryDirectory();
                if (dir != null && !dir.isBlank()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[AutoMemPaths] autoMemoryDirectory 来自 DB settings 列: {}", dir);
                    }
                    return dir;
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoMemPaths] 读 DB auto_memory_directory 失败（按无配置处理）: {} - {}", e.getMessage());
            }
        }
        return null;
    }

    private static String readAutoMemoryDirectoryFromFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode val = root.get("autoMemoryDirectory");
            if (val != null && val.isTextual()) {
                return val.asText();
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoMemPaths] 读取 settings.json 失败（按无配置处理）: {} - {}", file, e.getMessage());
            }
        }
        return null;
    }
}
