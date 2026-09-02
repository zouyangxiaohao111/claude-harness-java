package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.powershell.PowerShellPermissionChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 通用路径校验核心 · 对齐 CC {@code utils/permissions/pathValidation.ts}（485 行）+ 核心
 * {@code filesystem.ts} 函数（hasSuspiciousWindowsPathPattern / checkEditableInternalPath /
 * checkReadableInternalPath / isDangerousFilePathToAutoEdit / pathInWorkingPath /
 * normalizeCaseForComparison / checkPathSafetyForAutoEdit）。
 *
 * <p><b>三层结构（OPD-WF5-02-05 拍板）</b>：
 * <ol>
 *   <li>本类 = CC {@code utils/permissions/pathValidation.ts} 核心层（validatePath / isPathAllowed /
 *       危险 / 内部路径白名单 / 沙箱写白名单 / 大小写归一）；</li>
 *   <li>{@link com.nexusai.application.agent.bash.BashPathValidator} = CC
 *       {@code tools/BashTool/pathValidation.ts} 扩展层（命令 PATH_EXTRACTORS / 危险删除 / 重定向），
 *       调用本核心；</li>
 *   <li>{@link com.nexusai.application.agent.tool.powershell.PowerShellPathValidator} = CC
 *       {@code tools/PowerShellTool/pathValidation.ts} 扩展层（归 WF-5，本期不改）。</li>
 * </ol>
 *
 * <p>纯静态工具类（无 Spring 依赖），所有会话状态经 {@link PathValidationEnv} 参数透传；
 * 沙箱写白名单配置经 {@link SandboxWriteConfig} 参数透传（null = 无配置 → fail-closed 不命中）。
 *
 * <h2>与既有检查器关系</h2>
 * <ul>
 *   <li>{@link ReadPermissionChecker}/{@link WritePermissionChecker} 的可疑 Windows 模式检查
 *       （步骤 2 / 1.7 检查①）委派 {@link #hasSuspiciousWindowsPathPattern}（OPD-WF5-02-01 补齐 7 类）；</li>
 *   <li>内部路径白名单（OPD-WF5-02-02 补齐 17 分支）委派 {@link #checkEditableInternalPath} /
 *       {@link #checkReadableInternalPath}；plan 写分支按 OD-20 约束 passthrough（写盘仍走 ask）；</li>
 *   <li>工作目录判定（OPD-WF5-02-03 大小写归一 + macOS /private 归一）委派
 *       {@link #pathInWorkingPath}。</li>
 * </ul>
 */
public final class PathValidation {

    private static final Logger log = LoggerFactory.getLogger(PathValidation.class);

    private PathValidation() {
        throw new AssertionError("utility class - do not instantiate");
    }

    /** CC pathValidation.ts:24 MAX_DIRS_TO_LIST。 */
    static final int MAX_DIRS_TO_LIST = 5;

    /** CC pathValidation.ts:25 GLOB_PATTERN_REGEX（含 brace）。 */
    static final Pattern GLOB_PATTERN_REGEX = Pattern.compile("[*?\\[\\]{}]");

    /** CC pathValidation.ts:318 WINDOWS_DRIVE_ROOT_REGEX。 */
    static final Pattern WINDOWS_DRIVE_ROOT_REGEX = Pattern.compile("^[A-Za-z]:\\/?$");

    /** CC pathValidation.ts:319 WINDOWS_DRIVE_CHILD_REGEX。 */
    static final Pattern WINDOWS_DRIVE_CHILD_REGEX = Pattern.compile("^[A-Za-z]:\\/[^/]+$");

    /** CC path.ts:133 containsPathTraversal · `..` 路径段。 */
    static final Pattern PATH_TRAVERSAL = Pattern.compile("(?:^|[\\\\/])\\.\\.(?:[\\\\/]|$)");

    /** CC filesystem.ts:57-68 DANGEROUS_FILES。Java 含 .nexusai.json（.claude.json 改名，OPD-WF5-02-04）。 */
    static final Set<String> DANGEROUS_FILES = Set.of(
        ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".zshrc",
        ".zprofile", ".profile", ".ripgreprc", ".mcp.json", ".claude.json", ".nexusai.json");

    /** CC filesystem.ts:74-79 DANGEROUS_DIRECTORIES。'.claude' 保留 CC mirror（只读兼容）；项目级
     * nexusai 目录（.{appName}）为动态（决策 D1/D6）→ isDangerousFilePathToAutoEdit 方法内
     * {@link NexusaiPaths#getProjectDirName()} 判定（静态 Set 无法运行时动态，R12-3）。 */
    static final Set<String> DANGEROUS_DIRECTORIES = Set.of(
        ".git", ".vscode", ".idea", ".claude");

    // ────────────────────────────────────────────────────────────────────────
    // 结果类型 · 对齐 CC PathCheckResult / ResolvedPathCheckResult / PermissionResult
    // ────────────────────────────────────────────────────────────────────────

    /** 路径校验结果 · 对齐 CC ResolvedPathCheckResult（pathValidation.ts:34-36）。 */
    public record PathCheckResult(
            boolean allowed,
            String resolvedPath,
            String message,
            PermissionDecisionReason decisionReason
    ) {
        public static PathCheckResult allowed(String resolvedPath) {
            return new PathCheckResult(true, resolvedPath, null, null);
        }

        public static PathCheckResult allowed(String resolvedPath, PermissionDecisionReason reason) {
            return new PathCheckResult(true, resolvedPath, null, reason);
        }

        public static PathCheckResult blocked(String resolvedPath, String message,
                PermissionDecisionReason reason) {
            return new PathCheckResult(false, resolvedPath, message, reason);
        }
    }

    /** 内部路径白名单结果 · 对齐 CC PermissionResult {behavior:'allow'|'passthrough'}。 */
    public record InternalPathResult(boolean allowed, PermissionDecisionReason decisionReason) {
        public static InternalPathResult allow(String reason) {
            return new InternalPathResult(true, new PermissionDecisionReason.Other(reason));
        }

        public static InternalPathResult passthrough() {
            return new InternalPathResult(false, null);
        }
    }

    /** 沙箱写白名单配置 · 对齐 CC SandboxManager.getFsWriteConfig()（sandbox-adapter.ts）。 */
    public record SandboxWriteConfig(List<String> allowOnly, List<String> denyWithinAllow) {
        public SandboxWriteConfig {
            allowOnly = allowOnly == null ? List.of() : List.copyOf(allowOnly);
            denyWithinAllow = denyWithinAllow == null ? List.of() : List.copyOf(denyWithinAllow);
        }
    }

    /** 安全检查结果 · 对齐 CC checkPathSafetyForAutoEdit 返回 {safe, message, classifierApprovable}。 */
    public record SafetyCheckResult(boolean safe, String message, boolean classifierApprovable) {
        /** 通过（无安全失败）。命名 pass 避免与 record 字段 accessor safe() 冲突。 */
        public static SafetyCheckResult pass() {
            return new SafetyCheckResult(true, null, false);
        }

        public static SafetyCheckResult unsafe(String message, boolean classifierApprovable) {
            return new SafetyCheckResult(false, message, classifierApprovable);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 纯函数 · 对齐 CC filesystem.ts + pathValidation.ts
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 大小写归一 · CC {@code normalizeCaseForComparison}（filesystem.ts:90-92）→ toLowerCase。
     * CC 注释：无论平台恒转小写（跨平台一致的防御）。
     */
    public static String normalizeCaseForComparison(String path) {
        return path == null ? "" : path.toLowerCase();
    }

    /** 当前平台是否 Windows（CC getPlatform()==='windows' 等价）。WSL 在 Java 端无法可靠探测，按 Windows 宿主处理。 */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }

    /**
     * 可疑 Windows 路径模式 · CC {@code hasSuspiciousWindowsPathPattern}（filesystem.ts:537-602）7 项全查：
     * <ol>
     *   <li>ADS 冒号（windows/wsl 平台，位置 2 之后；例 {@code file.txt::$DATA}、{@code .bashrc:hidden}）；</li>
     *   <li>8.3 短名 {@code ~\d}（例 {@code GIT~1}、{@code SETTIN~1.JSON}）；</li>
     *   <li>长路径前缀 {@code \\?\ } / {@code \\.\} / {@code //?/} / {@code //./}；</li>
     *   <li>尾点/尾空格 {@code [.\s]+$}（Windows 归一化剥离，例 {@code .git.}、{@code settings.json.}）；</li>
     *   <li>DOS 设备名 {@code \.(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$}（例 {@code .git.CON}）；</li>
     *   <li>3+ 连续点作路径段 {@code (^|\/|\\)\.{3,}(\/|\\|$)}（例 {@code .../file}）；</li>
     *   <li>UNC 子检查 {@code containsVulnerableUncPath}（仅 Windows 平台，纵深防御）。</li>
     * </ol>
     *
     * <p><b>OPD-WF5-02-01</b>：Java 旧 {@code SUSPICIOUS_WIN_PATTERN} 仅 4/7 且 {@code .{3,}} 无分隔符边界
     * 过度命中（{@code ...name]}）；本实现全量对齐 CC 7 类。
     *
     * @param path 待检查路径
     * @return true = 含可疑 Windows 路径模式
     */
    public static boolean hasSuspiciousWindowsPathPattern(String path) {
        if (path == null) {
            return false;
        }
        // 1. NTFS ADS 冒号（win/wsl 平台，位置 2 之后跳过盘符，CC :546-551）
        if (isWindows()) {
            int colonIndex = path.indexOf(':', 2);
            if (colonIndex != -1) {
                return true;
            }
        }
        // 2. 8.3 短名（CC :556-558）
        if (EIGHT_DOT_THREE.matcher(path).find()) {
            return true;
        }
        // 3. 长路径前缀（CC :562-569）
        if (path.startsWith("\\\\?\\") || path.startsWith("\\\\.\\")
                || path.startsWith("//?/") || path.startsWith("//./")) {
            return true;
        }
        // 4. 尾点/尾空格（CC :574-576）
        if (TRAILING_DOT_SPACE.matcher(path).find()) {
            return true;
        }
        // 5. DOS 设备名（CC :581-583）
        if (DOS_DEVICE_NAME.matcher(path).find()) {
            return true;
        }
        // 6. 3+ 连续点作路径段（CC :590-592，分隔符边界防过度命中）
        if (THREE_PLUS_DOTS.matcher(path).find()) {
            return true;
        }
        // 7. UNC 子检查（CC :597-599；containsVulnerableUncPath 内部仅 Windows 生效）
        if (containsVulnerableUncPath(path)) {
            return true;
        }
        return false;
    }

    private static final Pattern EIGHT_DOT_THREE = Pattern.compile("~\\d");
    private static final Pattern TRAILING_DOT_SPACE = Pattern.compile("[.\\s]+$");
    private static final Pattern DOS_DEVICE_NAME =
        Pattern.compile("\\.(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREE_PLUS_DOTS =
        Pattern.compile("(^|/|\\\\)\\.{3,}(/|\\\\|$)");

    /** CC readOnlyCommandValidation.ts:1562-1638 containsVulnerableUncPath · 仅 Windows 平台生效。 */
    static boolean containsVulnerableUncPath(String pathOrCommand) {
        if (!isWindows() || pathOrCommand == null) {
            return false;
        }
        // 1. 反斜杠 UNC（CC :1572-1575）
        if (UNC_BACKSLASH.matcher(pathOrCommand).find()) {
            return true;
        }
        // 2. 正斜杠 UNC（CC :1582-1587，负 lookbehind 排除 URL 协议）
        if (UNC_FORWARD.matcher(pathOrCommand).find()) {
            return true;
        }
        // 3. 混合分隔符 /\\{2,}（CC :1594-1597）
        if (UNC_MIXED.matcher(pathOrCommand).find()) {
            return true;
        }
        // 4. 反向混合 \\{2,}/（CC :1602-1605）
        if (UNC_REVERSE_MIXED.matcher(pathOrCommand).find()) {
            return true;
        }
        // 5. WebDAV SSL/port（CC :1609-1611）
        if (UNC_WEBDAV_SSL.matcher(pathOrCommand).find() || UNC_WEBDAV_PORT.matcher(pathOrCommand).find()) {
            return true;
        }
        // 6. DavWWWRoot 标记（CC :1615-1617）
        if (UNC_DAVWWWROOT.matcher(pathOrCommand).find()) {
            return true;
        }
        // 7. IPv4（CC :1622-1624）
        if (UNC_IPV4_BS.matcher(pathOrCommand).find() || UNC_IPV4_FS.matcher(pathOrCommand).find()) {
            return true;
        }
        // 8. 方括号 IPv6（CC :1631-1633）
        if (UNC_IPV6_BS.matcher(pathOrCommand).find() || UNC_IPV6_FS.matcher(pathOrCommand).find()) {
            return true;
        }
        return false;
    }

    // 正则转义（Java 字符串双反斜杠）：CC 原文见 readOnlyCommandValidation.ts:1572-1633。
    // UNC_BACKSLASH：\\\\ 匹配 2 个字面反斜杠。
    private static final Pattern UNC_BACKSLASH =
        Pattern.compile("\\\\\\\\[^\\s\\\\/]+(?:@(?:\\d+|ssl))?(?:[\\\\/]|$|\\s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNC_FORWARD =
        Pattern.compile("(?<!:)//[^\\s\\\\/]+(?:@(?:\\d+|ssl))?(?:[\\\\/]|$|\\s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNC_MIXED = Pattern.compile("/\\\\{2,}[^\\s\\\\/]");
    private static final Pattern UNC_REVERSE_MIXED = Pattern.compile("\\\\{2,}/[^\\s\\\\/]");
    private static final Pattern UNC_WEBDAV_SSL = Pattern.compile("@SSL@\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNC_WEBDAV_PORT = Pattern.compile("@\\d+@SSL", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNC_DAVWWWROOT = Pattern.compile("DavWWWRoot", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNC_IPV4_BS =
        Pattern.compile("^\\\\\\\\(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})[\\\\/]");
    private static final Pattern UNC_IPV4_FS =
        Pattern.compile("^//(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})[\\\\/]");
    private static final Pattern UNC_IPV6_BS =
        Pattern.compile("^\\\\\\\\(\\\\[\\\\da-fA-F:]+\\\\])[\\\\/]");
    private static final Pattern UNC_IPV6_FS =
        Pattern.compile("^//(\\\\[\\\\da-fA-F:]+\\\\])[\\\\/]");

    /** CC utils/path.ts:133 containsPathTraversal。 */
    public static boolean containsPathTraversal(String path) {
        return path != null && PATH_TRAVERSAL.matcher(path).find();
    }

    /** CC filesystem.ts:709-744 pathInWorkingPath · macOS /private 归一 + 大小写归一 + 相对路径穿越检查。 */
    public static boolean pathInWorkingPath(String path, String workingPath) {
        if (path == null || workingPath == null) {
            return false;
        }
        String absolutePath = expandPath(path, null);
        String absoluteWorkingPath = expandPath(workingPath, null);
        if (absolutePath == null || absoluteWorkingPath == null) {
            return false;
        }
        // macOS /private/var→/var、/private/tmp→/tmp 归一（CC :716-721）
        String normalizedPath = absolutePath
            .replaceFirst("^/private/var/", "/var/")
            .replaceFirst("^/private/tmp(/|$)", "/tmp$1");
        String normalizedWorkingPath = absoluteWorkingPath
            .replaceFirst("^/private/var/", "/var/")
            .replaceFirst("^/private/tmp(/|$)", "/tmp$1");
        // 大小写归一（CC :723-728，防 case 变体绕过）
        String casePath = normalizeCaseForComparison(normalizedPath);
        String caseWorkingPath = normalizeCaseForComparison(normalizedWorkingPath);
        // 跨平台 POSIX 相对路径（CC :730-731）
        String relative = relativePath(caseWorkingPath, casePath);
        if (relative == null) {
            return false;
        }
        // 相同路径（CC :734-736）
        if (relative.isEmpty()) {
            return true;
        }
        // 相对路径含 .. 段 → 越界（CC :738-740）
        if (containsPathTraversal(relative)) {
            return false;
        }
        // 相对路径不是绝对路径（CC :743，路径在内）
        return !isPosixAbsolute(relative);
    }

    /**
     * 跨平台 POSIX 相对路径 · CC {@code relativePath}（filesystem.ts:170-179）。
     * 手工计算（无关平台分隔符）：公共前缀后 from 残余每层 {@code ../}，to 残余逐段拼接。
     * 无法计算（根不同/非法路径）返回 null → pathInWorkingPath fail-closed false。
     */
    static String relativePath(String from, String to) {
        if (from == null || to == null) {
            return null;
        }
        String[] fromParts = from.replace('\\', '/').split("/", -1);
        String[] toParts = to.replace('\\', '/').split("/", -1);
        int common = 0;
        while (common < fromParts.length && common < toParts.length
                && fromParts[common].equals(toParts[common])) {
            common++;
        }
        StringBuilder rel = new StringBuilder();
        for (int i = common; i < fromParts.length; i++) {
            if (!fromParts[i].isEmpty()) {
                rel.append("../");
            }
        }
        for (int i = common; i < toParts.length; i++) {
            if (!toParts[i].isEmpty()) {
                rel.append(toParts[i]).append("/");
            }
        }
        // 去尾分隔符（相对路径末尾保留 '/' 语义等价，这里统一去掉避免 '' vs '/' 歧义）
        if (rel.length() > 0 && rel.charAt(rel.length() - 1) == '/') {
            rel.setLength(rel.length() - 1);
        }
        return rel.toString();
    }

    /** POSIX 绝对路径判定（/ 或盘符根）。 */
    static boolean isPosixAbsolute(String path) {
        return path.startsWith("/") || path.matches("^[A-Za-z]:.*");
    }

    /** CC utils/path.ts expandPath（PathGuard 等价物委托，baseDir null → user.dir）。 */
    public static String expandPath(String path, String baseDir) {
        try {
            return PathGuard.expandPath(path, baseDir);
        } catch (IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("[PathValidation] expandPath 失败回退原 path: path={} cause={}", path, e.getMessage());
            }
            return path;
        }
    }

    /** CC pathValidation.ts:80-89 expandTilde（~ / ~/ / win ~\）。 */
    public static String expandTilde(String path) {
        if (path == null) {
            return null;
        }
        if ("~".equals(path) || path.startsWith("~/")
                || (isWindows() && path.startsWith("~\\"))) {
            return System.getProperty("user.home", "") + path.substring(1);
        }
        return path;
    }

    /** CC pathValidation.ts:38-51 formatDirectoryList · ≤5 全列 / &gt;5 前5+"and N more"。 */
    public static String formatDirectoryList(List<String> directories) {
        if (directories == null || directories.isEmpty()) {
            return "";
        }
        if (directories.size() <= MAX_DIRS_TO_LIST) {
            StringBuilder sb = new StringBuilder();
            for (String d : directories) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append("'").append(d).append("'");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_DIRS_TO_LIST; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("'").append(directories.get(i)).append("'");
        }
        sb.append(", and ").append(directories.size() - MAX_DIRS_TO_LIST).append(" more");
        return sb.toString();
    }

    /** CC pathValidation.ts:57-74 getGlobBaseDirectory（glob 首字符前段 + 平台分隔符回退）。 */
    public static String getGlobBaseDirectory(String path) {
        if (path == null) {
            return null;
        }
        var m = GLOB_PATTERN_REGEX.matcher(path);
        if (!m.find()) {
            return path;
        }
        String beforeGlob = path.substring(0, m.start());
        int lastSep = Math.max(beforeGlob.lastIndexOf('/'), beforeGlob.lastIndexOf('\\'));
        if (lastSep == -1) {
            return ".";
        }
        String base = beforeGlob.substring(0, lastSep);
        return base.isEmpty() ? "/" : base;
    }

    /** CC pathValidation.ts:331-367 isDangerousRemovalPath · 单一真理源 PowerShellPermissionChain。 */
    public static boolean isDangerousRemovalPath(String resolvedPath) {
        return PowerShellPermissionChain.isDangerousRemovalPath(resolvedPath);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 危险路径判定 · 对齐 CC filesystem.ts:435-488 / :200-242 / :620-665
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 危险文件/目录判定 · CC {@code isDangerousFilePathToAutoEdit}（filesystem.ts:435-488）。
     * UNC 前缀 → 路径段命中 DANGEROUS_DIRECTORIES（.claude/worktrees 例外）→ 文件名命中 DANGEROUS_FILES，
     * 均大小写不敏感。
     */
    public static boolean isDangerousFilePathToAutoEdit(String expanded) {
        if (expanded == null) {
            return false;
        }
        if (expanded.startsWith("\\\\") || expanded.startsWith("//")) {
            return true;
        }
        String[] segments = expanded.split("[\\\\/]");
        // [R12-3] 项目级 nexusai 目录名动态（决策 D1/D6）· DANGEROUS_DIRECTORIES 静态 Set 无法运行时
        //   动态，'.nexusai' 字面随 appName 变（spring.application.name）而失效 → getProjectDirName()
        //   兜底判定；'.claude' 保留 CC mirror（只读兼容）。appName=nexusai 时 = ".nexusai" 行为不变。
        String projectDirName = NexusaiPaths.getProjectDirName();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            if (!isDangerousDirectorySegment(segment, projectDirName)) {
                continue;
            }
            // .claude/worktrees 与 .{appName}/worktrees 是结构性目录（git worktree 存放处，决策 D7）
            if (segment.equalsIgnoreCase(".claude") || segment.equalsIgnoreCase(projectDirName)) {
                String next = i + 1 < segments.length ? segments[i + 1] : null;
                if (next != null && next.equalsIgnoreCase("worktrees")) {
                    continue;
                }
            }
            return true;
        }
        if (segments.length > 0) {
            String fileName = segments[segments.length - 1];
            for (String f : DANGEROUS_FILES) {
                if (f.equalsIgnoreCase(fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * [R12-3] 危险目录段判定 · 静态黑名单（{@link #DANGEROUS_DIRECTORIES}，CC filesystem.ts:74-79）
     * + 动态项目级 nexusai 目录名（{@link NexusaiPaths#getProjectDirName()}）。静态 Set 无法运行时
     * 动态 → 方法内 getProjectDirName() 兜底：appName 变（spring.application.name）时 '.{appName}'
     * 仍判危险。
     *
     * @param segment        路径段（单目录名）
     * @param projectDirName 动态项目级 nexusai 目录名（.{appName}）
     * @return 该段命中危险目录
     */
    private static boolean isDangerousDirectorySegment(String segment, String projectDirName) {
        for (String dir : DANGEROUS_DIRECTORIES) {
            if (segment.equalsIgnoreCase(dir)) {
                return true;
            }
        }
        return segment.equalsIgnoreCase(projectDirName);
    }

    /**
     * Claude 配置/指令/代理/技能路径判定 · CC {@code isClaudeConfigFilePath}
     * （filesystem.ts:225-242）：settings.json / settings.local.json 后缀 + {originalCwd}/.claude/{commands,agents,skills}。
     * 大小写不敏感。
     *
     * @param expanded    已 expand 的绝对路径
     * @param originalCwd 进程 cwd（CC getOriginalCwd，filesystem.ts:233）
     */
    public static boolean isClaudeConfigFilePath(String expanded, String originalCwd) {
        if (expanded == null) {
            return false;
        }
        String sep = java.io.File.separator;
        String lower = normalizeCaseForComparison(expanded);
        // 决策 D2/D6 全动态：项目级 nexusai 目录名 = NexusaiPaths.getProjectDirName()（.{appName}），
        // .nexusai/settings.json + .nexusai/settings.local.json 与 .claude 等价受保护配置 carve-out。
        String nexusaiDirLower = normalizeCaseForComparison(NexusaiPaths.getProjectDirName());
        if (lower.endsWith(sep + ".claude" + sep + "settings.json")
                || lower.endsWith(sep + ".claude" + sep + "settings.local.json")
                || lower.endsWith(sep + nexusaiDirLower + sep + "settings.json")
                || lower.endsWith(sep + nexusaiDirLower + sep + "settings.local.json")) {
            return true;
        }
        if (originalCwd == null || originalCwd.isBlank()) {
            return false;
        }
        // {cwd}/.claude/{commands,agents,skills}（CC :230-241）+ .nexusai 等价 carve-out
        String commandsDir = Paths.get(originalCwd, ".claude", "commands").toString();
        String agentsDir = Paths.get(originalCwd, ".claude", "agents").toString();
        String skillsDir = Paths.get(originalCwd, ".claude", "skills").toString();
        String nexusaiCommandsDir = Paths.get(originalCwd, NexusaiPaths.getProjectDirName(), "commands").toString();
        String nexusaiAgentsDir = Paths.get(originalCwd, NexusaiPaths.getProjectDirName(), "agents").toString();
        String nexusaiSkillsDir = Paths.get(originalCwd, NexusaiPaths.getProjectDirName(), "skills").toString();
        return pathInWorkingPath(expanded, commandsDir)
            || pathInWorkingPath(expanded, agentsDir)
            || pathInWorkingPath(expanded, skillsDir)
            || pathInWorkingPath(expanded, nexusaiCommandsDir)
            || pathInWorkingPath(expanded, nexusaiAgentsDir)
            || pathInWorkingPath(expanded, nexusaiSkillsDir);
    }

    /**
     * auto-edit 安全检查 · CC {@code checkPathSafetyForAutoEdit}（filesystem.ts:620-665）三道检查：
     * 可疑 Windows 模式（classifierApprovable=false）→ Claude 配置（true）→ 危险文件/目录（true）。
     *
     * @param path 原始路径（检查 original + 展开路径由调用方遍历 pathsToCheck）
     * @param env  路径校验环境（originalCwd 供 claude-config 判定）
     * @return 安全检查结果
     */
    public static SafetyCheckResult checkPathSafetyForAutoEdit(String path, PathValidationEnv env) {
        if (hasSuspiciousWindowsPathPattern(path)) {
            return SafetyCheckResult.unsafe(
                "Path contains suspicious Windows-specific patterns", false);
        }
        if (isClaudeConfigFilePath(path, env.originalCwd())) {
            return SafetyCheckResult.unsafe(
                "Claude config file path", true);
        }
        if (isDangerousFilePathToAutoEdit(path)) {
            return SafetyCheckResult.unsafe(
                "Path is a sensitive file", true);
        }
        return SafetyCheckResult.pass();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 内部路径白名单 · 对齐 CC filesystem.ts checkEditableInternalPath（:1479-1605）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 内部可编辑路径白名单 · CC {@code checkEditableInternalPath}（filesystem.ts:1479-1605）。
     *
     * <p>分支（对齐 CC）：plan（OD-20 → Java passthrough，写盘仍走 ask）→ scratchpad →
     * job（feature('TEMPLATES') 门）→ agent-memory → auto-mem（!hasAutoMemPathOverride 门）→
     * launch.json → passthrough。
     *
     * @param absolutePath 已 normalize 的绝对路径
     * @param env          路径校验环境
     * @return allow（reason 对齐 CC 文案）或 passthrough
     */
    public static InternalPathResult checkEditableInternalPath(String absolutePath, PathValidationEnv env) {
        if (absolutePath == null || env == null) {
            return InternalPathResult.passthrough();
        }
        String normalized = normalizePath(absolutePath);
        if (normalized == null) {
            return InternalPathResult.passthrough();
        }

        // ── plan 文件（CC :1488-1497）──
        // OD-20 子项4：plan 写盘走 ask 而非 CC auto-allow（UX 偏差已登记），故 passthrough。
        // 读分支（checkReadableInternalPath）仍按 CC auto-allow。
        if (isSessionPlanFile(normalized, env)) {
            if (log.isDebugEnabled()) {
                log.debug("[PathValidation] checkEditableInternalPath: plan 文件命中但按 OD-20 passthrough（写盘走 ask）: {}",
                    absolutePath);
            }
            return InternalPathResult.passthrough();
        }

        // ── scratchpad 目录（CC :1500-1509，isScratchpadEnabled 门）──
        if (isScratchpadPath(normalized, env)) {
            return InternalPathResult.allow("Scratchpad files for current session are allowed for writing");
        }

        // ── job 目录（CC :1520-1551，feature('TEMPLATES') + CLAUDE_JOB_DIR）──
        // CC feature('TEMPLATES') 为 Bun 编译期宏（外部构建恒 false）；Java 以常量 TEMPLATES_FEATURE=false
        // 表达相同语义（构建期不可见 → 分支恒不活，代码保留对齐 CC 结构）。
        if (TEMPLATES_FEATURE_ENABLED && isJobDirectoryPath(normalized, env)) {
            return InternalPathResult.allow("Job directory files for current job are allowed for writing");
        }

        // ── agent-memory（CC :1554-1562）──
        // Java 端在 EditFileTool/WriteFileTool 工具层实现（deny 先于 carve-out），本核心不重复。

        // ── auto-mem（CC :1572-1581，!hasAutoMemPathOverride 门）──
        if (!env.hasAutoMemPathOverride() && isAutoMemPath(normalized, env)) {
            return InternalPathResult.allow("auto memory files are allowed for writing");
        }

        // ── launch.json（CC :1590-1602，项目级 {originalCwd}/.claude/launch.json，大小写不敏感）──
        String launchJson = env.launchJsonPath();
        String launchJsonNorm = normalizePath(launchJson);
        if (launchJsonNorm != null && normalizeCaseForComparison(normalized)
                .equals(normalizeCaseForComparison(launchJsonNorm))) {
            return InternalPathResult.allow("Preview launch config is allowed for writing");
        }

        return InternalPathResult.passthrough();
    }

    /** CC feature('TEMPLATES')（filesystem.ts:1520）· Bun 编译期宏，外部构建恒 false。 */
    private static final boolean TEMPLATES_FEATURE_ENABLED = false;

    /**
     * 可读内部路径白名单 · CC {@code checkReadableInternalPath}（filesystem.ts:1611-1777）11 分支：
     * session-memory → project-dir → plan → tool-results → scratchpad → project-temp →
     * auto-mem → tasks → teams → bundled-skills → passthrough。
     *
     * <p>agent-memory 分支由 {@link ReadPermissionChecker} 既有 bean 判定承载（scope-aware，
     * 本核心不重复）；bundled-skills/auto-mem 分支当 env 提供了对应基址（bean 派生）时生效。
     *
     * @param absolutePath 已 normalize 的绝对路径
     * @param env          路径校验环境
     * @return allow（reason 对齐 CC 文案）或 passthrough
     */
    public static InternalPathResult checkReadableInternalPath(String absolutePath, PathValidationEnv env) {
        if (absolutePath == null || env == null) {
            return InternalPathResult.passthrough();
        }
        String normalized = normalizePath(absolutePath);
        if (normalized == null) {
            return InternalPathResult.passthrough();
        }

        // ── session-memory（CC :1620-1629）──
        if (isWithin(normalized, env.sessionMemoryDir())) {
            return InternalPathResult.allow("Session memory files are allowed for reading");
        }

        // ── project-dir（CC :1633-1642；===projectDir || startsWith(projectDir+sep)）──
        if (isWithin(normalized, env.projectDir())) {
            return InternalPathResult.allow("Project directory files are allowed for reading");
        }

        // ── plan 文件（CC :1645-1654）──
        if (isSessionPlanFile(normalized, env)) {
            return InternalPathResult.allow("Plan files for current session are allowed for reading");
        }

        // ── tool-results 目录（CC :1656-1674，尾分隔符防 tool-results-evil 前缀攻击）──
        if (isWithin(normalized, env.toolResultsDir())) {
            return InternalPathResult.allow("Tool result files are allowed for reading");
        }

        // ── scratchpad 目录（CC :1677-1686）──
        if (isScratchpadPath(normalized, env)) {
            return InternalPathResult.allow("Scratchpad files for current session are allowed for reading");
        }

        // ── project-temp（CC :1688-1701，跨会话同项目 temp 空间）──
        if (isWithin(normalized, env.projectTempDir())) {
            return InternalPathResult.allow("Project temp directory files are allowed for reading");
        }

        // ── auto-mem（CC :1716-1725，恒 isAutoMemPath 无 override 门）──
        if (isAutoMemPath(normalized, env)) {
            return InternalPathResult.allow("auto memory files are allowed for reading");
        }

        // ── tasks 目录（CC :1728-1741）──
        if (isWithin(normalized, env.tasksDir())) {
            return InternalPathResult.allow("Task files are allowed for reading");
        }

        // ── teams 目录（CC :1744-1757）──
        if (isWithin(normalized, env.teamsDir())) {
            return InternalPathResult.allow("Team files are allowed for reading");
        }

        // ── bundled-skills（CC :1764-1774，尾分隔符防 nonce 前缀攻击）──
        if (isWithin(normalized, env.bundledSkillsRoot())) {
            return InternalPathResult.allow("Bundled skill reference files are allowed for reading");
        }

        return InternalPathResult.passthrough();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 内部路径判定辅助 · 对齐 CC isSessionPlanFile / isScratchpadPath / isAutoMemPath
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 归一化前缀匹配 · path 等于 prefix 或 path 以 prefix+sep 开头（双侧转原生分隔符）。
     * CC 各 isXxxPath 的 {@code === dir || startsWith(dir + sep)} 统一形态。
     */
    static boolean isWithin(String path, String prefix) {
        String p = normalizePath(path);
        String pre = normalizePath(prefix);
        if (p == null || pre == null) {
            return false;
        }
        return p.equals(pre) || p.startsWith(pre + sep());
    }

    /** CC filesystem.ts:245-255 isSessionPlanFile · startsWith(plansDir+planSlug) && endsWith('.md')。 */
    static boolean isSessionPlanFile(String normalizedPath, PathValidationEnv env) {
        String prefix = normalizePath(env.plansPrefix());
        if (prefix == null) {
            return false;
        }
        return normalizedPath.startsWith(prefix) && normalizedPath.endsWith(".md");
    }

    /** CC filesystem.ts:410-424 isScratchpadPath · isScratchpadEnabled 门 + ===/startsWith(scratchpad+sep)。 */
    static boolean isScratchpadPath(String normalizedPath, PathValidationEnv env) {
        if (!env.scratchpadEnabled()) {
            return false;
        }
        return isWithin(normalizedPath, env.scratchpadDir());
    }

    /** CC memdir/paths.ts:274-278 isAutoMemPath · startsWith(getAutoMemPath())。 */
    static boolean isAutoMemPath(String normalizedPath, PathValidationEnv env) {
        String base = env.autoMemBaseDir();
        if (base == null || base.isBlank()) {
            return false;
        }
        String baseNorm = normalizePath(base);
        return baseNorm != null && normalizedPath.startsWith(baseNorm);
    }

    /**
     * CC filesystem.ts:1520-1551 job 目录分支 · 劫持/符号守卫（Java 以 CLAUDE_JOB_DIR env + configHome/jobs 根）。
     *
     * <p><b>job 兜底定案（决策 D1/D6，不改行为）</b>：Java 端无 CLAUDE_JOB_DIR / jobs 写介质
     * （TEMPLATES 门恒 false，本函数为死分支），保留仅作 CC 结构对齐兜底。若未来启用 jobs 根，
     * 应改由 {@code NexusaiPaths.getAppConfigHomeDir()} 派生（{@code ~/.{appName}/jobs}，
     * 非 claude 根），随 D1 自有根全动态。
     */
    static boolean isJobDirectoryPath(String normalizedPath, PathValidationEnv env) {
        String jobDir = System.getenv("CLAUDE_JOB_DIR");
        if (jobDir == null || jobDir.isBlank()) {
            return false;
        }
        String jobsRoot = Paths.get(env.claudeConfigHomeDir(), "jobs").normalize().toString();
        String normalizedJobDir = Paths.get(jobDir).normalize().toString();
        // 劫持守卫：job dir 必须解析在 configHome/jobs 下（CC :1529-1531）
        if (!normalizedJobDir.startsWith(jobsRoot + sep())) {
            return false;
        }
        return normalizedPath.equals(normalizedJobDir)
            || normalizedPath.startsWith(normalizedJobDir + sep());
    }

    /** 平台分隔符。 */
    static String sep() {
        return java.io.File.separator;
    }

    /** 路径 normalize（非法路径返回 null）。 */
    static String normalizePath(String p) {
        if (p == null) {
            return null;
        }
        try {
            return Paths.get(p).normalize().toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 沙箱写白名单 · 对齐 CC pathValidation.ts:101-123 isPathInSandboxWriteAllowlist
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 路径是否在沙箱写白名单内 · CC {@code isPathInSandboxWriteAllowlist}（pathValidation.ts:101-123）。
     *
     * <p>沙箱启用时用户显式配置了可写目录（如 /tmp/claude/），路径校验把这些目录视为额外允许写目录，
     * 使重定向/touch/mkdir 不额外弹窗。denyWithinAllow（如 .claude/settings.json）即使在 allowOnly 父目录
     * 内也阻断。双侧 realpath 展开对称（与 pathInAllowedWorkingPath 一致）。
     *
     * <p><b>Java 现状</b>：沙箱执行域（sandbox-adapter.ts getFsWriteConfig）单列专项探查
     * （OPD-WF4-DEC-03，登记 后期待实现.md 第 12 项）；{@code config == null} → fail-closed 不命中
     * （沙箱路径照常 ask）。配置经 {@link SandboxWriteConfig} 参数注入（测试 / 未来 WF-4 接线）。
     *
     * @param resolvedPath 已解析路径
     * @param config       沙箱写白名单配置（null = 无配置）
     * @return true = 在 allowOnly 内且不在 denyWithinAllow 内
     */
    public static boolean isPathInSandboxWriteAllowlist(String resolvedPath, SandboxWriteConfig config) {
        if (config == null) {
            return false;
        }
        if (config.allowOnly().isEmpty()) {
            return false;
        }
        if (resolvedPath == null) {
            return false;
        }
        String normalized = normalizePath(resolvedPath);
        if (normalized == null) {
            return false;
        }
        // 全路径表示均须允许且均未被 deny（CC :117-122 every/pathsToCheck）
        List<String> pathsToCheck = PermissionPaths.getPathsForPermissionCheck(resolvedPath);
        if (pathsToCheck.isEmpty()) {
            pathsToCheck = List.of(normalized);
        }
        for (String p : pathsToCheck) {
            for (String deny : config.denyWithinAllow()) {
                if (pathInWorkingPath(p, deny)) {
                    return false;
                }
            }
            boolean insideAllow = false;
            for (String allow : config.allowOnly()) {
                if (pathInWorkingPath(p, allow)) {
                    insideAllow = true;
                    break;
                }
            }
            if (!insideAllow) {
                return false;
            }
        }
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    // isPathAllowed 决策链 · 对齐 CC pathValidation.ts:141-263
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 解析后路径是否允许 · CC {@code isPathAllowed}（pathValidation.ts:141-263）8 步：
     * deny → internal-edit（写）→ auto-edit 安全（写）→ 工作目录 → internal-read（读）→
     * 沙箱写白名单（写且目录外）→ allow rule → 兜底 deny。
     *
     * @param resolvedPath            已解析路径
     * @param permCtx                 权限上下文（deny/allow 规则 + 附加工作目录；可为 null）
     * @param operationType           操作类型（read/write/create）
     * @param env                     路径校验环境（内部路径白名单 / 工作目录）
     * @param sandboxConfig           沙箱写白名单配置（null = 无沙箱 allowlist）
     * @param precomputedPathsToCheck 调用方已展开路径（可选；null = 本方法自算）
     * @return 路径校验结果
     */
    public static PathCheckResult isPathAllowed(String resolvedPath, ToolPermissionContext permCtx,
            PermissionUpdates.OperationType operationType, PathValidationEnv env,
            SandboxWriteConfig sandboxConfig, List<String> precomputedPathsToCheck) {
        String normalized = normalizePath(resolvedPath);
        if (normalized == null) {
            return PathCheckResult.blocked(resolvedPath,
                "Invalid path: " + resolvedPath, new PermissionDecisionReason.Other("invalid path"));
        }
        boolean read = operationType == PermissionUpdates.OperationType.READ;
        // 展开路径集合（安全/工作目录检查遍历；CC :147 precomputedPathsToCheck ?? [resolvedPath]）
        List<String> pathsToCheck = precomputedPathsToCheck != null && !precomputedPathsToCheck.isEmpty()
            ? precomputedPathsToCheck
            : List.of(normalized);

        // 1. deny 规则优先（CC :151-162）
        PermissionRule deny = editDenyRule(normalized, permCtx);
        if (deny != null) {
            return PathCheckResult.blocked(normalized, null,
                new PermissionDecisionReason.Rule(deny));
        }

        // 2. 内部可编辑路径（写/create；CC :164-176）
        if (!read) {
            InternalPathResult internalEdit = checkEditableInternalPath(normalized, env);
            if (internalEdit.allowed()) {
                return PathCheckResult.allowed(normalized, internalEdit.decisionReason());
            }
        }

        // 2.5 auto-edit 安全检查（写/create；CC :181-196，遍历全部展开路径）
        if (!read) {
            for (String p : pathsToCheck) {
                SafetyCheckResult safety = checkPathSafetyForAutoEdit(p, env);
                if (!safety.safe()) {
                    return PathCheckResult.blocked(normalized, safety.message(),
                        new PermissionDecisionReason.SafetyCheck(safety.message(), safety.classifierApprovable()));
                }
            }
        }

        // 3. 工作目录内（CC :201-211；read 或 acceptEdits 自动放行）
        boolean inWorkingDir = isInAllowedWorkingPath(normalized, permCtx, env, precomputedPathsToCheck);
        if (inWorkingDir && (read || (permCtx != null && permCtx.mode() == PermissionMode.ACCEPT_EDITS))) {
            return PathCheckResult.allowed(normalized);
        }

        // 3.5 内部可读路径（读；CC :215-223）
        if (read) {
            InternalPathResult internalRead = checkReadableInternalPath(normalized, env);
            if (internalRead.allowed()) {
                return PathCheckResult.allowed(normalized, internalRead.decisionReason());
            }
        }

        // 3.7 沙箱写白名单（写且目录外；CC :233-245）
        if (!read && !inWorkingDir && isPathInSandboxWriteAllowlist(normalized, sandboxConfig)) {
            return PathCheckResult.allowed(normalized,
                new PermissionDecisionReason.Other("Path is in sandbox write allowlist"));
        }

        // 4. allow 规则（CC :248-259）
        PermissionRule allow = editAllowRule(normalized, permCtx);
        if (allow != null) {
            return PathCheckResult.allowed(normalized, new PermissionDecisionReason.Rule(allow));
        }

        // 5. 兜底不允许（CC :262）
        return PathCheckResult.blocked(normalized, null, null);
    }

    /**
     * 全部展开路径是否都在某工作目录内 · CC {@code pathInAllowedWorkingPath}（filesystem.ts:683-707）
     * every/some 语义。工作目录白名单 = env.originalCwd + permCtx.additionalWorkingDirectories。
     *
     * <p><b>[G10] 白名单锚 originalCwd 层</b>（对齐 CC {@code allWorkingDirectories}
     * filesystem.ts:667-674 {@code new Set([getOriginalCwd(), ...additional.keys()])}）。
     * 原锚 env.effectiveCwd（=getCwd，随 bash cd 变）→ bash cd 进子目录后白名单根=子目录（变窄），
     * 与 CC 不符（CC 锚 getOriginalCwd=启动/worktree 入口层，cd 不改白名单根）。改锚 env.originalCwd
     * （=CwdResolution.getOriginalCwdLayer，PathValidationEnv.fromToolUseContext/forProcess 已走统一入口）。
     * <b>WHY</b>：白名单根稳定在启动/worktree 入口层，cd 后子目录仍在 originalCwd 子树内（pathInWorkingPath
     * 树语义放行），不随 cd 变窄——对齐 CC 「worktree 内 cd 不改变权限工作目录范围」。
     */
    static boolean isInAllowedWorkingPath(String resolvedPath, ToolPermissionContext permCtx,
            PathValidationEnv env, List<String> precomputedPathsToCheck) {
        List<String> pathsToCheck = precomputedPathsToCheck != null && !precomputedPathsToCheck.isEmpty()
            ? precomputedPathsToCheck
            : List.of(resolvedPath);
        List<String> workingPaths = new ArrayList<>();
        // [G10] 白名单锚 originalCwd（对齐 CC allWorkingDirectories 锚 getOriginalCwd）
        if (env != null && env.originalCwd() != null) {
            workingPaths.add(env.originalCwd());
        }
        if (permCtx != null && permCtx.additionalWorkingDirectories() != null) {
            for (AdditionalWorkingDirectory awd : permCtx.additionalWorkingDirectories().values()) {
                if (awd.path() != null && !awd.path().isBlank()) {
                    workingPaths.add(awd.path());
                }
            }
        }
        if (workingPaths.isEmpty()) {
            return false;
        }
        for (String pathToCheck : pathsToCheck) {
            boolean inside = false;
            for (String wp : workingPaths) {
                if (pathInWorkingPath(pathToCheck, wp)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                return false;
            }
        }
        return true;
    }

    private static PermissionRule editDenyRule(String path, ToolPermissionContext permCtx) {
        if (permCtx == null) {
            return null;
        }
        return RuleQuery.getEditRuleByContentsForPath(permCtx, path, PermissionBehavior.DENY);
    }

    private static PermissionRule editAllowRule(String path, ToolPermissionContext permCtx) {
        if (permCtx == null) {
            return null;
        }
        return RuleQuery.getEditRuleByContentsForPath(permCtx, path, PermissionBehavior.ALLOW);
    }

    // ────────────────────────────────────────────────────────────────────────
    // validatePath / validateGlobPattern · 对齐 CC pathValidation.ts:373-485 / :269-316
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 路径校验入口 · CC {@code validatePath}（pathValidation.ts:373-485）：引号剥离+tilde →
     * UNC 阻断 → tilde 变体阻断 → shell 展开阻断 → glob（写阻断/读 base 校验）→ 解析 + isPathAllowed。
     *
     * @param path        原始路径（LLM 入参）
     * @param cwd         解析基座 cwd
     * @param permCtx     权限上下文
     * @param operationType 操作类型
     * @param env         路径校验环境
     * @param sandboxConfig 沙箱写白名单配置（null = 无）
     * @return 路径校验结果
     */
    public static PathCheckResult validatePath(String path, String cwd, ToolPermissionContext permCtx,
            PermissionUpdates.OperationType operationType, PathValidationEnv env,
            SandboxWriteConfig sandboxConfig) {
        if (path == null) {
            return PathCheckResult.blocked(path, "Path is null",
                new PermissionDecisionReason.Other("path is null"));
        }
        String cleanPath = expandTilde(path.replaceAll("^['\"]|['\"]$", ""));
        if (cleanPath == null) {
            cleanPath = path;
        }
        String normalizedPath = cleanPath.replace('\\', '/');

        // UNC 网络路径（凭据泄漏）→ 阻断（CC :383-392）
        if (containsVulnerableUncPath(cleanPath)) {
            return PathCheckResult.blocked(cleanPath, "UNC network paths require manual approval",
                new PermissionDecisionReason.Other("UNC network paths require manual approval"));
        }
        // tilde 变体（~user/~+/~-）→ 阻断（CC :401-411）
        if (cleanPath.startsWith("~")) {
            return PathCheckResult.blocked(cleanPath,
                "Tilde expansion variants (~user, ~+, ~-) in paths require manual approval",
                new PermissionDecisionReason.Other("Tilde expansion variants (~user, ~+, ~-) in paths require manual approval"));
        }
        // shell 展开语法（$ / % / = 开头）→ 阻断（CC :423-436）
        if (cleanPath.contains("$") || cleanPath.contains("%") || cleanPath.startsWith("=")) {
            return PathCheckResult.blocked(cleanPath,
                "Shell expansion syntax in paths requires manual approval",
                new PermissionDecisionReason.Other("Shell expansion syntax in paths requires manual approval"));
        }
        // glob（CC :443-463）
        if (GLOB_PATTERN_REGEX.matcher(normalizedPath).find()) {
            if (operationType == PermissionUpdates.OperationType.WRITE
                    || operationType == PermissionUpdates.OperationType.CREATE) {
                return PathCheckResult.blocked(cleanPath,
                    "Glob patterns are not allowed in write operations. Please specify an exact file path.",
                    new PermissionDecisionReason.Other("Glob patterns are not allowed in write operations"));
            }
            return validateGlobPattern(cleanPath, cwd, permCtx, operationType, env, sandboxConfig);
        }
        // 常规解析 + isPathAllowed（CC :465-485）
        String abs = resolveAgainstCwd(cleanPath, cwd);
        return isPathAllowed(abs, permCtx, operationType, env, sandboxConfig, null);
    }

    /** glob 模式校验（base 目录）· CC {@code validateGlobPattern}（pathValidation.ts:269-316）。 */
    public static PathCheckResult validateGlobPattern(String cleanPath, String cwd,
            ToolPermissionContext permCtx, PermissionUpdates.OperationType operationType,
            PathValidationEnv env, SandboxWriteConfig sandboxConfig) {
        if (containsPathTraversal(cleanPath)) {
            String abs = resolveAgainstCwd(cleanPath, cwd);
            return isPathAllowed(abs, permCtx, operationType, env, sandboxConfig, null);
        }
        String basePath = getGlobBaseDirectory(cleanPath);
        String absBase = resolveAgainstCwd(basePath, cwd);
        return isPathAllowed(absBase, permCtx, operationType, env, sandboxConfig, null);
    }

    /** 相对路径解析基座（absoluteLike → normalize；否则 resolve(cwd, path).normalize）。 */
    static String resolveAgainstCwd(String path, String cwd) {
        if (path == null) {
            return null;
        }
        boolean absoluteLike = path.startsWith("/") || path.startsWith("\\")
            || path.matches("^[a-zA-Z]:.*");
        try {
            if (absoluteLike || Paths.get(path).isAbsolute()) {
                return Paths.get(path).normalize().toString();
            }
        } catch (Exception ignored) {
            // 非法路径字符 → 交给 isPathAllowed 失败
        }
        if (cwd == null) {
            return Paths.get(path).normalize().toString();
        }
        return Paths.get(cwd, path).normalize().toString();
    }
}
