package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolNameConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆文件检测 · 对齐 CC {@code Open-ClaudeCode/src/utils/memoryFileDetection.ts}.
 *
 * <p>CC 真源（2026-08-05 grep -n 自验）：{@code isAutoMemFile} memoryFileDetection.ts:87-92；
 * {@code isMemoryDirectory} :152-207；{@code memoryScopeForPath} :106-114；
 * {@code isAutoManagedMemoryFile} :133-147；{@code isShellCommandTargetingMemory} :215-271；
 * {@code isAutoManagedMemoryPattern} :277-289；{@code toComparable} :31-34；{@code toPosix} :25-27。
 *
 * <p><b>为什么新文件</b>：旧 {@code SessionFileAccessHooks.isAutoMemFile}（:332-337）无
 * isAutoMemoryEnabled 门控、无 normalize、无 Windows 小写化，且直接基于
 * DEFAULT_MEMORY_DIR（DEL-M-06 废弃）。本类对齐 CC 语义：门控 + normalize + toComparable
 * （Windows 小写化），并被 SessionFileAccessHooks 复用（消除双实现漂移）。
 */
public final class MemoryFileDetection {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileDetection.class);

    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final char BACKSLASH = '\\';

    private final AutoMemPaths autoMemPaths;
    private final Supplier<String> configHomeSupplier;
    private final BooleanSupplier autoMemoryEnabled;
    /** 编译开关 CC feature('TEAMMEM') · [IMP-CM-09] 生产注入 FeatureFlags.teamMem()（nexusai.feature.team-mem）。 */
    private final BooleanSupplier teamMemFeatureEnabled;
    /** 运行时开关 CC tengu_herring_clock · [IMP-CM-09] 生产注入 FeatureFlags.tenguHerringClock()。 */
    private final BooleanSupplier teamMemoryRuntimeEnabled;
    /** team 路径逻辑唯一 owner · 对齐 CC memoryFileDetection.ts:17-18 require teamMemPaths.js。 */
    private final TeamMemPaths teamMemPaths;
    /** [IMP-M-P2-2] agent-memory 路径判定委托 · 对齐 CC isAgentMemFile（memoryFileDetection.ts:119-124）。 */
    private final com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory;

    /**
     * 生产构造器 · 门控对齐 OPD-M-47：{@link BundledSkillEnabledGates#isAutoMemoryEnabled()}；
     * team 双门控拆分（IMP-CM-09/OPD-CM3-11/B04）：编译开关 feature('TEAMMEM') + 运行时开关
     * tengu_herring_clock 双 supplier 注入（FeatureFlags.teamMem()/tenguHerringClock()）——与
     * {@code teamMemPaths} bean 同源，消除双实例门控分裂。OAuth 可用性（isTeamMemorySyncAvailable）
     * 由 watcher/sync 层 {@code httpClient.isAuthAvailable()} 单独判定（对齐 watcher.ts:256），不再内联。
     */
    public MemoryFileDetection(AutoMemPaths autoMemPaths,
                               BooleanSupplier teamMemFeatureEnabled,
                               BooleanSupplier teamMemoryRuntimeEnabled) {
        // 决策 D1/D3 全动态：config 根 = nexusai 自有根（NexusaiPaths.getAppConfigHomeDir()，
        //   ~/.{appName}）优先；claude（~/.claude）读取回落由 isUnderConfigRoot 双根判定兜底
        //   （session_memory/session_transcript 检测覆盖 ~/.{appName} 与 ~/.claude 两处）。
        this(autoMemPaths, NexusaiPaths::getAppConfigHomeDir,
            BundledSkillEnabledGates::isAutoMemoryEnabled,
            teamMemFeatureEnabled, teamMemoryRuntimeEnabled);
    }

    /**
     * 注入式构造器（测试隔离）。
     *
     * @param autoMemPaths            路径解析器（per-project autoMemPath）
     * @param configHomeSupplier      CC getClaudeConfigHomeDir（envUtils.ts:7-14）
     * @param autoMemoryEnabled       CC isAutoMemoryEnabled（paths.ts:30-56）
     * @param teamMemFeatureEnabled   编译开关 CC feature('TEAMMEM')（watcher.ts:253）
     * @param teamMemoryRuntimeEnabled 运行时开关 CC tengu_herring_clock（teamMemPaths.ts:77）
     */
    public MemoryFileDetection(AutoMemPaths autoMemPaths,
                               Supplier<String> configHomeSupplier,
                               BooleanSupplier autoMemoryEnabled,
                               BooleanSupplier teamMemFeatureEnabled,
                               BooleanSupplier teamMemoryRuntimeEnabled) {
        this.autoMemPaths = autoMemPaths;
        this.configHomeSupplier = configHomeSupplier;
        this.autoMemoryEnabled = autoMemoryEnabled;
        this.teamMemFeatureEnabled = teamMemFeatureEnabled;
        this.teamMemoryRuntimeEnabled = teamMemoryRuntimeEnabled;
        this.teamMemPaths = new TeamMemPaths(autoMemPaths, autoMemoryEnabled,
            teamMemFeatureEnabled, teamMemoryRuntimeEnabled);
        // [IMP-M-P2-2] agent-memory 判定委托（对齐 CC isAgentMemFile 用 isAgentMemoryPath）
        this.agentMemoryDirectory = com.nexusai.application.agent.agent.AgentMemoryDirectory
            .fromAutoMemPaths(autoMemPaths, autoMemoryEnabled);
    }

    /**
     * team 路径安全单例（P1-4 接线 · 与当前实例共享同一门控 supplier，供 sync 链复用）。
     */
    public TeamMemPaths teamMemPaths() {
        return teamMemPaths;
    }

    // ════════════════════════════════════════════════════════════════
    // toComparable / toPosix · memoryFileDetection.ts:25-34
    // ════════════════════════════════════════════════════════════════

    /**
     * 转稳定可比较形式：正斜杠分隔 + Windows 小写化 · CC original: {@code toComparable}
     * （memoryFileDetection.ts:31-34）。Windows 文件系统大小写不敏感 → 必须折叠。
     */
    public static String toComparable(String p) {
        if (p == null) {
            return "";
        }
        String posixForm = toPosix(p);
        return IS_WINDOWS ? posixForm.toLowerCase(Locale.ROOT) : posixForm;
    }

    /** 路径分隔符转 posix（/）· CC original: {@code toPosix}（memoryFileDetection.ts:25-27）。 */
    public static String toPosix(String p) {
        return p.replace('\\', '/');
    }

    // ════════════════════════════════════════════════════════════════
    // detectSessionFileType / detectSessionPatternType · :40-82
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测会话相关文件类型 · CC original: {@code detectSessionFileType}（memoryFileDetection.ts:40-59）。
     * {@code configDir/session-memory/*.md} → session_memory；{@code configDir/projects/*.jsonl} → session_transcript。
     *
     * @return 'session_memory' | 'session_transcript' | null
     */
    public String detectSessionFileType(String filePath) {
        if (filePath == null) {
            return null;
        }
        String normalized = toComparable(filePath);
        // 决策 D1/D3：双根判定（nexusai 自有根 supplier 优先 + claude 读取回落）——session_memory/
        //   session_transcript 检测覆盖 ~/.{appName} 与 ~/.claude 两处。
        if (!isUnderConfigRoot(normalized)) {
            return null;
        }
        if (normalized.contains("/session-memory/") && normalized.endsWith(".md")) {
            return "session_memory";
        }
        if (normalized.contains("/projects/") && normalized.endsWith(".jsonl")) {
            return "session_transcript";
        }
        return null;
    }

    /**
     * config 根双根判定（决策 D1/D3）：nexusai 自有根（{@code configHomeSupplier}，生产 =
     * NexusaiPaths.getAppConfigHomeDir() = ~/.{appName}）优先 + claude（~/.claude）读取回落。
     * 测试经注入 supplier 覆写 primary 根（如 "C:/cfg"），claude 回落仍生效（D3 transcript 读兼容）。
     *
     * @param normalizedCmp toComparable 归一化路径
     * @return 是否位于任一 config 根下
     */
    private boolean isUnderConfigRoot(String normalizedCmp) {
        String primary = configHomeSupplier.get();
        if (primary != null && !primary.isBlank()
                && normalizedCmp.startsWith(toComparable(primary))) {
            return true;
        }
        String claudeConfig = ClaudePaths.getClaudeConfigHomeDir();
        if (claudeConfig != null && !claudeConfig.isBlank()
                && normalizedCmp.startsWith(toComparable(claudeConfig))) {
            return true;
        }
        return false;
    }

    /**
     * 检测 glob/pattern 是否为会话文件访问意图 · CC original: {@code detectSessionPatternType}
     * （memoryFileDetection.ts:65-82）。
     *
     * @return 'session_memory' | 'session_transcript' | null
     */
    public String detectSessionPatternType(String pattern) {
        if (pattern == null) {
            return null;
        }
        String normalized = toPosix(pattern);
        if (normalized.contains("session-memory")
            && (normalized.contains(".md") || normalized.endsWith("*"))) {
            return "session_memory";
        }
        if (normalized.contains(".jsonl")
            || (normalized.contains("projects") && normalized.contains("*.jsonl"))) {
            return "session_transcript";
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // isAutoMemFile / memoryScopeForPath · :87-114
    // ════════════════════════════════════════════════════════════════

    /**
     * 路径是否在 memdir 目录内 · CC original: {@code isAutoMemFile}（memoryFileDetection.ts:87-92）。
     * <pre>{@code
     * if (isAutoMemoryEnabled()) return isAutoMemPath(filePath)
     * return false
     * }</pre>
     * 门控（OPD-M-47 接入 BundledSkillEnabledGates.isAutoMemoryEnabled）+ normalize +
     * Windows 小写化（toComparable 语义，REQ-M-21）。
     */
    public boolean isAutoMemFile(String filePath) {
        if (filePath == null) {
            return false;
        }
        if (autoMemoryEnabled.getAsBoolean()) {
            boolean hit = autoMemPaths.isAutoMemPath(filePath);
            if (log.isDebugEnabled()) {
                log.debug("[MemoryFileDetection] isAutoMemFile 门控开启，命中={}: {}", hit, filePath);
            }
            return hit;
        }
        if (log.isDebugEnabled()) {
            log.debug("[MemoryFileDetection] isAutoMemFile 门控关闭（autoMemoryEnabled=false），不判定: {}", filePath);
        }
        return false;
    }

    /** 记忆作用域 · CC {@code MemoryScope}（memoryFileDetection.ts:94）= 'personal' | 'team'. */
    public enum MemoryScope { PERSONAL, TEAM }

    /**
     * 路径属于哪个记忆库 · CC original: {@code memoryScopeForPath}（memoryFileDetection.ts:106-114）。
     * team 目录是 memdir 子目录（getTeamMemPath = join(getAutoMemPath, 'team')），team 路径同时命中
     * isTeamMemFile 与 isAutoMemFile → team 先查（CC :99-100 注释）；team 分支带编译门
     * feature('TEAMMEM') 组合（CC :107），编译门关 → 回落 isAutoMemFile 判 'personal'。
     *
     * @return 'team' | 'personal' | null
     */
    public MemoryScope memoryScopeForPath(String filePath) {
        if (filePath == null) {
            return null;
        }
        if (teamMemFeatureEnabled.getAsBoolean() && isTeamMemFile(filePath)) {
            return MemoryScope.TEAM;
        }
        if (isAutoMemFile(filePath)) {
            return MemoryScope.PERSONAL;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // team 路径联动 · teamMemPaths.ts:73-92 + 214-220 + 290-292
    // ════════════════════════════════════════════════════════════════

    /**
     * team memory 是否启用 · 委托 {@link TeamMemPaths#isTeamMemoryEnabled()} · CC original:
     * {@code isTeamMemoryEnabled}（teamMemPaths.ts:73-78）。
     */
    public boolean isTeamMemoryEnabled() {
        return teamMemPaths.isTeamMemoryEnabled();
    }

    /**
     * 路径是否在 team memory 目录内 · 委托 {@link TeamMemPaths#isTeamMemPath} · CC original:
     * {@code isTeamMemPath}（teamMemPaths.ts:214-220）。SECURITY：resolve 转绝对 + 消 .. 段；
     * getTeamMemPath 尾分隔符防前缀攻击（team-evil 不命中）。
     */
    public boolean isTeamMemPath(String filePath) {
        return teamMemPaths.isTeamMemPath(filePath);
    }

    /**
     * 路径是否在 team memory 目录内且 team 启用 · 委托 {@link TeamMemPaths#isTeamMemFile} ·
     * CC original: {@code isTeamMemFile}（teamMemPaths.ts:290-292）。
     */
    public boolean isTeamMemFile(String filePath) {
        return teamMemPaths.isTeamMemFile(filePath);
    }

    /**
     * team memory 目录 · 委托 {@link TeamMemPaths#getTeamMemPath} · CC original: {@code getTeamMemPath}
     * （teamMemPaths.ts:84-86）= join(getAutoMemPath(), 'team') + sep。随 T1 基址联动（REQ-M-11）。
     */
    public String getTeamMemPath() {
        return teamMemPaths.getTeamMemPath();
    }

    // ════════════════════════════════════════════════════════════════
    // isAutoManagedMemoryFile / isMemoryDirectory · :133-207
    // ════════════════════════════════════════════════════════════════

    /**
     * 是否 Claude 托管的记忆文件（非用户托管指令文件）· CC original: {@code isAutoManagedMemoryFile}
     * （memoryFileDetection.ts:133-147）。包含 auto-memory（memdir）、agent memory、session memory/
     * transcript；排除 CLAUDE.md / CLAUDE.local.md / .claude/rules/*.md（用户托管）。
     * team 分支带编译门 feature('TEAMMEM') 组合（CC :136-137）。
     *
     * <p>用于 collapse/badge 逻辑：用户托管文件应显示完整 diff。
     */
    public boolean isAutoManagedMemoryFile(String filePath) {
        if (filePath == null) {
            return false;
        }
        boolean result = isAutoMemFile(filePath)
            || (teamMemFeatureEnabled.getAsBoolean() && isTeamMemFile(filePath))
            || detectSessionFileType(filePath) != null
            || isAgentMemFile(filePath);
        if (log.isDebugEnabled()) {
            log.debug("[MemoryFileDetection] isAutoManagedMemoryFile 判定={}: {}", result, filePath);
        }
        return result;
    }

    /**
     * 目录路径是否记忆相关目录 · CC original: {@code isMemoryDirectory}（memoryFileDetection.ts:152-207）。
     * Grep/Glob 接收目录 path 而非具体文件时使用；同时检查 configDir 与 memoryBaseDir 处理自定义
     * memory dir。SECURITY：normalize 防 .. 段路径遍历绕过。team 分支带编译门 feature('TEAMMEM')
     * 组合（CC :169-171：feature && isTeamMemoryEnabled && isTeamMemPath）。
     */
    public boolean isMemoryDirectory(String dirPath) {
        if (dirPath == null) {
            return false;
        }
        String normalizedCmp = toComparable(Paths.get(dirPath).normalize().toString());
        boolean result = classifyMemoryDirectory(normalizedCmp, dirPath);
        if (log.isDebugEnabled()) {
            log.debug("[MemoryFileDetection] isMemoryDirectory 判定={}: {}", result, dirPath);
        }
        return result;
    }

    /** isMemoryDirectory 内部判定（与日志解耦，保持数据流可测试）。 */
    private boolean classifyMemoryDirectory(String normalizedCmp, String dirPath) {
        // agent-memory 目录可在 cwd（project scope）、configDir、memoryBaseDir 下（CC :161-167）
        if (autoMemoryEnabled.getAsBoolean()
            && (normalizedCmp.contains("/agent-memory/")
                || normalizedCmp.contains("/agent-memory-local/"))) {
            return true;
        }
        // team memory 目录在 <autoMemPath>/team/ 下（CC memoryFileDetection.ts:169-175：
        // feature('TEAMMEM') && isTeamMemoryEnabled() && isTeamMemPath(normalizedPath)）
        if (teamMemFeatureEnabled.getAsBoolean()
            && teamMemPaths.isTeamMemoryEnabled()
            && teamMemPaths.isTeamMemPath(Paths.get(dirPath).normalize().toString())) {
            return true;
        }
        // auto-memory 路径 override 检查（CC :177-187）
        if (autoMemoryEnabled.getAsBoolean()) {
            String autoMemPath = autoMemPaths.getAutoMemPath();
            String autoMemDirCmp = toComparable(stripTrailing(autoMemPath));
            String autoMemPathCmp = toComparable(autoMemPath);
            if (normalizedCmp.equals(autoMemDirCmp) || normalizedCmp.startsWith(autoMemPathCmp)) {
                return true;
            }
        }
        // configDir / memoryBaseDir 下的 session/projects/memory 目录（CC :189-206）
        // 决策 D1/D3：underConfig 双根判定（nexusai 自有根 supplier 优先 + claude 读取回落）
        String memoryBaseCmp = toComparable(autoMemPaths.getMemoryBaseDir());
        boolean underConfig = isUnderConfigRoot(normalizedCmp);
        boolean underMemoryBase = normalizedCmp.startsWith(memoryBaseCmp);
        if (!underConfig && !underMemoryBase) {
            return false;
        }
        if (normalizedCmp.contains("/session-memory/")) {
            return true;
        }
        if (underConfig && normalizedCmp.contains("/projects/")) {
            return true;
        }
        if (autoMemoryEnabled.getAsBoolean() && normalizedCmp.contains("/memory/")) {
            return true;
        }
        return false;
    }

    /**
     * agent memory 文件（CC isAgentMemFile，memoryFileDetection.ts:119-124）· 委托
     * {@link com.nexusai.application.agent.agent.AgentMemoryDirectory#isAgentMemoryPath}。
     *
     * <p>[IMP-M-P2-2] 旧实现用 contains 子串（{@code "/agent-memory/"}）——无尾分隔符语义，
     * {@code agent-memory-evil} 前缀攻击路径会误命中（INV-12）。改委托 CC 对齐的
     * isAgentMemoryPath（normalize + 各 scope 基址 {@code +sep} 尾分隔符）。门控保留
     * （CC :120-122 {@code if (isAutoMemoryEnabled()) return isAgentMemoryPath(filePath)}）。
     */
    private boolean isAgentMemFile(String filePath) {
        if (!autoMemoryEnabled.getAsBoolean()) {
            return false;
        }
        return agentMemoryDirectory.isAgentMemoryPath(filePath);
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '/' || c == '\\') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    // ════════════════════════════════════════════════════════════════
    // isShellCommandTargetingMemory · :215-271
    // ════════════════════════════════════════════════════════════════

    /** 绝对路径 token 提取 · CC :249 {@code /(?:[A-Za-z]:[/\\]|\/)[^\s'"]+/g}。 */
    private static final Pattern PATH_TOKEN_PATTERN = Pattern.compile(
        "(?:[A-Za-z]:[/\\\\]|/)[^\\s'\"]+");
    /** 尾部 shell 元字符剥离 · CC :256 {@code /[,;|&>]+$/}。 */
    private static final Pattern TRAILING_SHELL_META = Pattern.compile("[,;|&>]+$");

    /**
     * shell 命令是否目标记忆文件 · CC original: {@code isShellCommandTargetingMemory}
     * （memoryFileDetection.ts:215-271）。提取命令中的绝对路径 token，对每个 token 检查
     * isAutoManagedMemoryFile / isMemoryDirectory。用于 Bash/PowerShell grep/search 命令的
     * collapse 逻辑。
     *
     * <p>Windows（Git Bash）BashTool 输出 MinGW {@code /c/...} 编码 → 提取时统一转 native
     * （posixPathToWindowsPath），下游谓词只依赖 toComparable（CC :258-263）。
     */
    public boolean isShellCommandTargetingMemory(String command) {
        if (command == null) {
            return false;
        }
        String configDir = configHomeSupplier.get();
        // 决策 D1/D3：claude 配置根纳入快速检查（D3 transcript 读回落，命令提及 ~/.claude 亦命中）
        String claudeConfigDir = ClaudePaths.getClaudeConfigHomeDir();
        String memoryBase = autoMemPaths.getMemoryBaseDir();
        String autoMemDir = autoMemoryEnabled.getAsBoolean()
            ? stripTrailing(autoMemPaths.getAutoMemPath()) : "";

        // 快速检查：命令是否提及 config / memoryBase / auto-mem 目录（CC :222-241）
        String commandCmp = toComparable(command);
        boolean matchesAnyDir = false;
        for (String d : new String[] {configDir, claudeConfigDir, memoryBase, autoMemDir}) {
            if (d == null || d.isEmpty()) {
                continue;
            }
            if (commandCmp.contains(toComparable(d))) {
                matchesAnyDir = true;
                break;
            }
            if (IS_WINDOWS && commandCmp.contains(windowsPathToPosixPath(d).toLowerCase(Locale.ROOT))) {
                matchesAnyDir = true;
                break;
            }
        }
        if (!matchesAnyDir) {
            return false;
        }

        // 提取绝对路径 token（CC :249-252）
        Matcher matcher = PATH_TOKEN_PATTERN.matcher(command);
        if (!matcher.find()) {
            return false;
        }
        do {
            String match = matcher.group();
            // 剥离尾部 shell 元字符（CC :256）
            String cleanPath = TRAILING_SHELL_META.matcher(match).replaceFirst("");
            // Windows 转 MinGW /c/... → native C:\...（CC :262-264）；其他平台原样
            String nativePath = IS_WINDOWS ? posixPathToWindowsPath(cleanPath) : cleanPath;
            if (isAutoManagedMemoryFile(nativePath) || isMemoryDirectory(nativePath)) {
                return true;
            }
        } while (matcher.find());
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // isAutoManagedMemoryPattern · :277-289
    // ════════════════════════════════════════════════════════════════

    /**
     * glob/pattern 是否只针对 auto-managed 记忆文件 · CC original: {@code isAutoManagedMemoryPattern}
     * （memoryFileDetection.ts:277-289）。排除 CLAUDE.md / CLAUDE.local.md / .claude/rules/
     * （用户托管）。用于 collapse badge 逻辑。
     */
    public boolean isAutoManagedMemoryPattern(String pattern) {
        if (pattern == null) {
            return false;
        }
        if (detectSessionPatternType(pattern) != null) {
            return true;
        }
        if (autoMemoryEnabled.getAsBoolean()) {
            String normalized = toPosix(pattern);
            if (normalized.contains("agent-memory/")
                || normalized.contains("agent-memory-local/")) {
                return true;
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // collapseReadSearch 消费链 · collapseReadSearch.ts:81-115
    // ════════════════════════════════════════════════════════════════

    /**
     * 搜索工具使用是否针对记忆文件 · CC original: {@code isMemorySearch}
     * （collapseReadSearch.ts:81-104）。collapseReadSearchGroups 折叠链的内存搜索分类入口：
     * <ul>
     *   <li>{@code path}（Grep/Glob 搜索路径）→ {@link #isAutoManagedMemoryFile} ||
     *       {@link #isMemoryDirectory}（CC :89-92）</li>
     *   <li>{@code glob}（glob 模式）→ {@link #isAutoManagedMemoryPattern}（CC :94-96）</li>
     *   <li>{@code command}（bash grep/rg、PowerShell Select-String 等 shell 命令）→
     *       {@link #isShellCommandTargetingMemory}（CC :99-101）</li>
     * </ul>
     *
     * <p><b>[IMP-C-3] U-2 五谓词接线（OPD-CM5-C-07）</b>：本方法与 {@link #isMemoryWriteOrEdit}
     * 共同构成 CC collapseReadSearch 消费链的 Java 分类入口，消费 isAutoManagedMemoryFile /
     * isMemoryDirectory / isAutoManagedMemoryPattern / isShellCommandTargetingMemory 四谓词
     * （U-2 根因：CC 消费方 collapseReadSearch.ts Java 无对应 → 0 生产消费者）。
     * <b>前端配合项已登记 待前端对接.md §32</b>：折叠 UI 在实现折叠/进度功能时消费本分类
     * （与 isSearchOrReadCommand 同先例，见 待前端对接.md §11.6.1）。
     *
     * @param path    Grep/Glob 的搜索路径（CC input.path）
     * @param glob    glob 模式（CC input.glob）
     * @param command shell 命令（CC input.command）
     * @return true = 搜索针对记忆文件 / 记忆目录 / 记忆模式
     */
    public boolean isMemorySearch(String path, String glob, String command) {
        boolean result = (path != null && (isAutoManagedMemoryFile(path) || isMemoryDirectory(path)))
            || (glob != null && isAutoManagedMemoryPattern(glob))
            || (command != null && isShellCommandTargetingMemory(command));
        if (log.isDebugEnabled()) {
            log.debug("[MemoryFileDetection] isMemorySearch 判定={} path={} glob={} command={}",
                result, path, glob, command);
        }
        return result;
    }

    /**
     * Write/Edit 工具使用是否针对记忆文件 · CC original: {@code isMemoryWriteOrEdit}
     * （collapseReadSearch.ts:109-115）。collapseReadSearchGroups 折叠链的内存写入分类入口：
     * 仅 Write/Edit 工具（CC :110-112），且提取的 file_path 命中
     * {@link #isAutoManagedMemoryFile}（CC :114）。
     *
     * <p><b>[IMP-C-3] U-2 五谓词接线（OPD-CM5-C-07）</b>：本方法与 {@link #isMemorySearch}
     * 共同构成 CC collapseReadSearch 消费链。前端配合项已登记 待前端对接.md §32。
     *
     * @param toolName 工具名（CC FILE_WRITE_TOOL_NAME / FILE_EDIT_TOOL_NAME）
     * @param filePath 工具输入提取的文件路径（CC getFilePathFromToolInput = file_path ?? path）
     * @return true = Write/Edit 针对记忆文件
     */
    public boolean isMemoryWriteOrEdit(String toolName, String filePath) {
        if (!ToolNameConstants.FILE_WRITE_TOOL_NAME.equals(toolName)
            && !ToolNameConstants.FILE_EDIT_TOOL_NAME.equals(toolName)) {
            return false;
        }
        boolean result = filePath != null && isAutoManagedMemoryFile(filePath);
        if (log.isDebugEnabled()) {
            log.debug("[MemoryFileDetection] isMemoryWriteOrEdit 判定={} toolName={} filePath={}",
                result, toolName, filePath);
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    // posixPathToWindowsPath · windowsPaths.ts:148-173
    // ════════════════════════════════════════════════════════════════

    /**
     * Windows → POSIX 路径（纯逻辑）· CC original: {@code windowsPathToPosixPath}
     * （windowsPaths.ts:128-145）：{@code \\server\share} → {@code //server/share}；
     * {@code C:\Users\foo} → {@code /c/Users/foo}。用于 isShellCommandTargetingMemory 的
     * Windows MinGW 形式快速检查（CC :234-237）。
     */
    public static String windowsPathToPosixPath(String windowsPath) {
        if (windowsPath.startsWith("\\\\")) {
            return windowsPath.replace('\\', '/');
        }
        Matcher drive = Pattern.compile("^([A-Za-z]):[/\\\\]").matcher(windowsPath);
        if (drive.find()) {
            String driveLetter = drive.group(1).toLowerCase(Locale.ROOT);
            return "/" + driveLetter + windowsPath.substring(2).replace('\\', '/');
        }
        return windowsPath.replace('\\', '/');
    }

    /**
     * POSIX → Windows 路径（纯逻辑）· CC original: {@code posixPathToWindowsPath}
     * （windowsPaths.ts:148-173）：{@code //server/share} → {@code \\server\share}；
     * {@code /cygdrive/c/...} → {@code C:\...}；{@code /c/...}（MSYS2/Git Bash）→ {@code C:\...}。
     */
    public static String posixPathToWindowsPath(String posixPath) {
        if (posixPath.startsWith("//")) {
            return posixPath.replace('/', BACKSLASH);
        }
        // MSYS 保留挂载点 /tmp → Windows %TEMP%（Git Bash 的 /tmp = 用户 Temp 目录）。
        // 说明：CC 纯 JS 转换（windowsPaths.ts:148-173）未特判 /tmp —— fallback 翻斜杠得 \tmp（错误），
        // 且 /tmp 会误匹配 drive 模式（t → "T:\mp"）。本增强修复该 CC 边缘 bug：Windows + cd 到
        // Temp 时 pwd -P 输出 /tmp/... 必须正确转 native，否则 cwd 持久化损坏（BashToolTest cd 系列暴露）。
        if (posixPath.equals("/tmp") || posixPath.startsWith("/tmp/")) {
            String tmp = System.getProperty("java.io.tmpdir", "\\tmp");
            String rest = posixPath.equals("/tmp") ? "" : posixPath.substring("/tmp".length());
            String base = tmp.endsWith("\\") || tmp.endsWith("/") ? tmp : tmp + "\\";
            return (base + rest).replace('/', BACKSLASH);
        }
        Matcher cygdrive = Pattern.compile("^/cygdrive/([A-Za-z])(/|$)").matcher(posixPath);
        if (cygdrive.find()) {
            String driveLetter = cygdrive.group(1).toUpperCase(Locale.ROOT);
            String rest = posixPath.substring(("/cygdrive/" + cygdrive.group(1)).length());
            return driveLetter + ":" + (rest.isEmpty() ? String.valueOf(BACKSLASH)
                : rest.replace('/', BACKSLASH));
        }
        Matcher drive = Pattern.compile("^/([A-Za-z])(/|$)").matcher(posixPath);
        if (drive.find()) {
            String driveLetter = drive.group(1).toUpperCase(Locale.ROOT);
            String rest = posixPath.substring(2);
            return driveLetter + ":" + (rest.isEmpty() ? String.valueOf(BACKSLASH)
                : rest.replace('/', BACKSLASH));
        }
        return posixPath.replace('/', BACKSLASH);
    }
}
