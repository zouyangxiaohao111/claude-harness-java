package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Team Memory 路径安全 · 对齐 CC {@code Open-ClaudeCode/src/memdir/teamMemPaths.ts}.
 *
 * <p>CC 真源（2026-08-05 grep -n 自验）：{@code PathTraversalError} teamMemPaths.ts:10-15；
 * {@code sanitizePathKey} :22-64；{@code isTeamMemoryEnabled} :73-78；{@code getTeamMemPath} :84-86；
 * {@code getTeamMemEntrypoint} :92-94；{@code realpathDeepestExisting} :109-171；
 * {@code isRealPathWithinTeamDir} :183-206；{@code isTeamMemPath} :214-220；
 * {@code validateTeamMemWritePath} :228-256；{@code validateTeamMemKey} :265-284；
 * {@code isTeamMemFile} :290-292。
 *
 * <p><b>为什么新文件</b>：旧 {@code MemoryFileDetection} 内联了 isTeamMemPath/isTeamMemFile/
 * getTeamMemPath/isTeamMemoryEnabled（4 个 team 方法），但缺 CC 的 path 安全全集（sanitizePathKey /
 * validateTeamMemWritePath / validateTeamMemKey / symlink 防护）。CC 中 memoryFileDetection.ts
 * 委托 teamMemPaths.js（memoryFileDetection.ts:17-18 require + :107/:137/:171 调用），
 * 本类成为 team 路径逻辑的唯一 owner，MemoryFileDetection 改委托（消除双实现漂移，规则七）。
 *
 * <p>SECURITY（CC :96-107 注释，PSR M22186/M22187）：path.resolve() 不解析 symlink —— 攻击者
 * 在 teamDir 内放指向外部（如 ~/.ssh/authorized_keys）的 symlink 可绕过 resolve 式包含检查。
 * realpathDeepestExisting 解析最深已存在祖先的真实位置，保证比较的是真实文件系统位置。
 */
public final class TeamMemPaths {

    private static final Logger log = LoggerFactory.getLogger(TeamMemPaths.class);

    private final AutoMemPaths autoMemPaths;
    private final BooleanSupplier autoMemoryEnabled;
    /** 编译开关 · CC {@code feature('TEAMMEM')}（watcher.ts:253 + teamMemSecretGuard.ts:19 + memory/types.ts:9）·
     *  IMP-CM-09 生产接线 = {@link com.nexusai.application.agent.loop.FeatureFlags#teamMem()}（nexusai.feature.team-mem）。 */
    private final BooleanSupplier teamMemFeatureEnabled;
    /** 运行时开关 · CC {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_herring_clock', false)}
     *  （teamMemPaths.ts:77）· IMP-CM-09 生产接线 = FeatureFlags.tenguHerringClock()（nexusai.feature.tengu-herring-clock）。 */
    private final BooleanSupplier teamMemoryRuntimeEnabled;

    /**
     * 生产构造器 · 双门控拆分（OPD-CM3-11/B04）：autoMemory 对齐 OPD-M-47
     * （BundledSkillEnabledGates.isAutoMemoryEnabled）；编译开关（feature('TEAMMEM')）+ 运行时开关
     * （tengu_herring_clock）双 supplier 同时注入，两开关独立控制（编译开+运行关=不启用；双开=启用，
     * CC AND 语义 teamMemPaths.ts:73-78 + watcher.ts:253）。OAuth 可用性（isTeamMemorySyncAvailable）
     * 不再内联 —— 由 watcher/sync 层 {@code httpClient.isAuthAvailable()} 单独判定（对齐 watcher.ts:256）。
     */
    public TeamMemPaths(AutoMemPaths autoMemPaths,
                        BooleanSupplier teamMemFeatureEnabled,
                        BooleanSupplier teamMemoryRuntimeEnabled) {
        this(autoMemPaths, BundledSkillEnabledGates::isAutoMemoryEnabled,
            teamMemFeatureEnabled, teamMemoryRuntimeEnabled);
    }

    /**
     * 注入式构造器（测试隔离）。
     *
     * @param autoMemPaths            路径解析器（per-project autoMemPath）
     * @param autoMemoryEnabled       CC isAutoMemoryEnabled（paths.ts:30-56）
     * @param teamMemFeatureEnabled   编译开关 CC feature('TEAMMEM')（watcher.ts:253）
     * @param teamMemoryRuntimeEnabled 运行时开关 CC tengu_herring_clock（teamMemPaths.ts:77）
     */
    public TeamMemPaths(AutoMemPaths autoMemPaths,
                        BooleanSupplier autoMemoryEnabled,
                        BooleanSupplier teamMemFeatureEnabled,
                        BooleanSupplier teamMemoryRuntimeEnabled) {
        this.autoMemPaths = autoMemPaths;
        this.autoMemoryEnabled = autoMemoryEnabled;
        this.teamMemFeatureEnabled = teamMemFeatureEnabled;
        this.teamMemoryRuntimeEnabled = teamMemoryRuntimeEnabled;
    }

    /**
     * CC PathTraversalError（teamMemPaths.ts:10-15）· 路径校验检测到遍历/注入时抛出。
     * 未受检异常，caller 显式捕获（CC catch instanceof PathTraversalError 语义等价）。
     */
    public static class PathTraversalError extends RuntimeException {
        public PathTraversalError(String message) {
            super(message);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // sanitizePathKey · :22-64
    // ════════════════════════════════════════════════════════════════

    /**
     * 清洗路径 key，拒绝危险模式 · CC original: {@code sanitizePathKey}（teamMemPaths.ts:22-64）。
     * 检查 null 字节 / URL 编码遍历（%2e%2e%2f）/ Unicode NFKC 归一化攻击 / 反斜杠 / 绝对路径。
     *
     * @return 清洗后的 key（未变）
     * @throws PathTraversalError 检测到注入向量
     */
    public static String sanitizePathKey(String key) {
        // null 字节可在 C 系 syscall 中截断路径（CC :24-26）
        if (key.indexOf('\0') >= 0) {
            throw new PathTraversalError("Null byte in path key: \"" + key + "\"");
        }
        // URL 编码遍历（e.g. %2e%2e%2f = ../）· decodeURIComponent 等价（不转 '+'）
        String decoded;
        try {
            decoded = decodeUriComponent(key);
        } catch (IllegalArgumentException e) {
            // 畸形百分号编码（e.g. %ZZ, 孤立 %）→ 非合法 URL 编码，不可能产生 URL 编码遍历
            decoded = key;
        }
        if (!decoded.equals(key) && (decoded.contains("..") || decoded.contains("/"))) {
            throw new PathTraversalError("URL-encoded traversal in path key: \"" + key + "\"");
        }
        // Unicode 归一化攻击：全角 ．．／（U+FF0E U+FF0F）NFKC 归一化为 ASCII ../（CC :39-54）
        String normalized = Normalizer.normalize(key, Normalizer.Form.NFKC);
        if (!normalized.equals(key)
                && (normalized.contains("..")
                    || normalized.contains("/")
                    || normalized.contains("\\")
                    || normalized.indexOf('\0') >= 0)) {
            throw new PathTraversalError("Unicode-normalized traversal in path key: \"" + key + "\"");
        }
        // 反斜杠（Windows 路径分隔符作遍历向量，CC :56-58）
        if (key.contains("\\")) {
            throw new PathTraversalError("Backslash in path key: \"" + key + "\"");
        }
        // 绝对路径（CC :60-62）
        if (key.startsWith("/")) {
            throw new PathTraversalError("Absolute path key: \"" + key + "\"");
        }
        return key;
    }

    /** decodeURIComponent 等价：仅解码 %XX，不把 '+' 转空格；畸形 % 序列抛 IllegalArgumentException。 */
    private static String decodeUriComponent(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '%') {
                if (i + 2 >= key.length()) {
                    throw new IllegalArgumentException("malformed percent-encoding");
                }
                int hi = Character.digit(key.charAt(i + 1), 16);
                int lo = Character.digit(key.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("malformed percent-encoding");
                }
                sb.append((char) ((hi << 4) | lo));
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // isTeamMemoryEnabled / getTeamMemPath / getTeamMemEntrypoint · :73-94
    // ════════════════════════════════════════════════════════════════

    /**
     * team memory 是否启用 · CC original: {@code isTeamMemoryEnabled}（teamMemPaths.ts:73-78）。
     * = auto-memory 启用 && 运行时开关 {@code tengu_herring_clock}（CC :77 真源：{@code isAutoMemoryEnabled()
     * && getFeatureValue_CACHED_MAY_BE_STALE('tengu_herring_clock', false)}）。team 是 auto-memory
     * 子目录 → 需要 auto-memory 启用；编译开关 feature('TEAMMEM') 由 {@link #isTeamMemFeatureEnabled()} 独立控制
     * （IMP-CM-09/OPD-CM3-11/B04 双门控拆分）。
     */
    public boolean isTeamMemoryEnabled() {
        return autoMemoryEnabled.getAsBoolean() && teamMemoryRuntimeEnabled.getAsBoolean();
    }

    /**
     * team memory 目录 · CC original: {@code getTeamMemPath}（teamMemPaths.ts:84-86）
     * = join(getAutoMemPath(), 'team') + sep。尾分隔符契约防前缀攻击（team-evil 不命中 team/）。
     *
     * <p>OPD-R2-06：CC :85 {@code (join(getAutoMemPath(), 'team') + sep).normalize('NFC')}。
     */
    public String getTeamMemPath() {
        String autoMem = autoMemPaths.getAutoMemPath();
        return ClaudePaths.normalizeNfc(autoMem + "team" + java.io.File.separator);
    }

    /**
     * team memory 入口文件 · CC original: {@code getTeamMemEntrypoint}（teamMemPaths.ts:92-94）
     * = join(getAutoMemPath(), 'team', 'MEMORY.md')。
     */
    public String getTeamMemEntrypoint() {
        return Paths.get(autoMemPaths.getAutoMemPath(), "team", "MEMORY.md").toString();
    }

    // ════════════════════════════════════════════════════════════════
    // realpathDeepestExisting / isRealPathWithinTeamDir · :109-206
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析路径最深已存在祖先的 symlink · CC original: {@code realpathDeepestExisting}
     * （teamMemPaths.ts:109-171）。目标文件可能尚不存在（即将创建），沿目录树向上直到 realpath
     * 成功，再把不存在的尾部按反序重新拼回。
     *
     * <p>SECURITY（PSR M22186）：path.resolve() 不解析 symlink。攻击者在 teamDir 内放指向外部
     * 的 symlink（如 ~/.ssh/authorized_keys）会通过 resolve 式包含检查。对最深已存在祖先用
     * realpath 确保比较真实文件系统位置。
     *
     * @param absolutePath 绝对路径（待创建文件也可）
     * @return 真实（symlink 解析后）绝对路径
     * @throws PathTraversalError 悬空 symlink / symlink 循环 / 无法验证包含（fail closed）
     */
    public String realpathDeepestExisting(String absolutePath) throws IOException {
        List<String> tail = new ArrayList<>();
        Path current = Paths.get(absolutePath);
        Path parent = current.getParent();
        // 循环终止：到达文件系统根（dirname('/') === '/'，parent == current）
        while (parent != null && !current.equals(parent)) {
            try {
                Path realCurrent = current.toRealPath();
                // 把不存在的尾部反序重新拼回（最深先弹出）
                Path result = realCurrent;
                for (int i = tail.size() - 1; i >= 0; i--) {
                    result = result.resolve(tail.get(i));
                }
                if (log.isDebugEnabled()) {
                    log.debug("[TeamMemPaths] realpathDeepestExisting {} → {}", absolutePath, result);
                }
                return result.toString();
            } catch (NoSuchFileException e) {
                // 可能真不存在（安全向上走）OR 悬空 symlink（目标是攻击向量：writeFile 会跟随
                // 链接在 teamDir 外创建目标）。Files.isSymbolicLink 对悬空 symlink 也成功（NOFOLLOW）。
                if (Files.isSymbolicLink(current)) {
                    throw new PathTraversalError(
                        "Dangling symlink detected (target does not exist): \"" + current + "\"");
                }
                // lstat 成功但非 symlink —— 是祖先中的悬空 symlink 导致 ENOENT，向上走找它
            } catch (FileSystemLoopException e) {
                // symlink 循环 —— 损坏或恶意文件系统状态
                throw new PathTraversalError("Symlink loop detected in path: \"" + current + "\"");
            } catch (java.nio.file.NotDirectoryException e) {
                // ENOTDIR（路径中间有非目录组件）→ 向上走（CC teamMemPaths.ts:156-163）
            } catch (java.nio.file.FileSystemException e) {
                // JDK 无 FileNameTooLongException：ENAMETOOLONG 以 FileSystemException 抛出
                // （Unix reason "File name too long" / Windows "The filename or extension is too long"）。
                // ENAMETOOLONG → 向上走（CC teamMemPaths.ts:156-163）；
                // 其余 FileSystemException（EACCES=AccessDeniedException 等）→ 落入 fail-closed。
                String reason = e.getReason();
                if (reason == null || !reason.toLowerCase(java.util.Locale.ROOT).contains("too long")) {
                    throw new PathTraversalError(
                        "Cannot verify path containment (" + e.getClass().getSimpleName() + "): \"" + current + "\"");
                }
            } catch (IOException e) {
                // OPD-R2-03（D10，medium）：非 {ENOENT,ELOOP,ENOTDIR,ENAMETOOLONG} 错误
                // （EACCES/EIO/EMFILE 等）→ 无法验证包含，fail-closed 抛 PathTraversalError
                // （CC teamMemPaths.ts:156-162 全部非四类错误一律 fail-closed）。
                // 旧实现仅 AccessDeniedException fail-closed，其余 IOException 继续向上走
                // → 以「无法验证」的祖先真实路径通过包含检查（symlink 逃逸验证被跳过）。
                throw new PathTraversalError(
                    "Cannot verify path containment (" + e.getClass().getSimpleName() + "): \"" + current + "\"");
            }
            String fileName = current.getFileName() == null ? "" : current.getFileName().toString();
            if (!fileName.isEmpty()) {
                tail.add(fileName);
            }
            current = parent;
            parent = current.getParent();
        }
        // 到达文件系统根仍未找到已存在祖先（罕见 —— 根通常存在）。回退输入；包含检查会拒绝。
        return absolutePath;
    }

    /**
     * 真实（symlink 解析后）路径是否在真实 team memory 目录内 · CC original:
     * {@code isRealPathWithinTeamDir}（teamMemPaths.ts:183-206）。两侧都 realpath 保证比较的是
     * canonical 文件系统位置。teamDir 不存在 → true（跳过检查，无目录即无 symlink 逃逸可能）。
     *
     * <p>前缀攻击防护：要求前缀后跟分隔符，/foo/team-evil 不匹配 /foo/team。
     */
    public boolean isRealPathWithinTeamDir(String realCandidate) throws IOException {
        Path realTeamDir;
        try {
            // getTeamMemPath() 带尾分隔符；realpath 在某些平台拒绝尾分隔符 → 先 strip
            realTeamDir = Paths.get(stripTrailingSeparators(getTeamMemPath())).toRealPath();
        } catch (NoSuchFileException e) {
            // team 目录不存在 —— symlink 逃逸不可能，跳过检查
            return true;
        } catch (IOException e) {
            // 意外错误（EACCES/EIO）—— fail closed
            return false;
        }
        // OPD-R2-07（D11，v3）：CC 比较大小写敏感（teamMemPaths.ts:183-206）——去掉旧
        // toComparable（Windows 小写折叠）→ 对 CC 拒绝的大小写变体 accept-more 消失。
        // Windows 文件系统大小写不敏感差异登记受控（真实路径两侧均来自 toRealPath，
        // 实际文件系统大小写一致，折叠不再需要；字符串级包含检查见 isTeamMemPath）。
        String realTeamDirStr = realTeamDir.toString();
        if (realCandidate.equals(realTeamDirStr)) {
            return true;
        }
        // 前缀攻击防护：要求前缀后跟分隔符（/foo/team-evil 不匹配 /foo/team）
        return realCandidate.startsWith(realTeamDirStr + "/")
            || realCandidate.startsWith(realTeamDirStr + java.io.File.separator);
    }

    /** 去尾部分隔符（getTeamMemPath 尾分隔符在 realpath 前 strip，CC :190）。 */
    private static String stripTrailingSeparators(String s) {
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
    // 旧 toComparable（正斜杠 + Windows 小写折叠）已删除：折叠仅属
    // MemoryFileDetection.toComparable（CC memoryFileDetection.ts:31-34，isMemoryDirectory 段）；
    // team 判定链对齐 CC 大小写敏感（OPD-R2-07，G-08/G-62）后不再需要。

    // ════════════════════════════════════════════════════════════════
    // isTeamMemPath / validateTeamMemWritePath / validateTeamMemKey · :214-284
    // ════════════════════════════════════════════════════════════════

    /**
     * 绝对路径是否在 team memory 目录内 · CC original: {@code isTeamMemPath}（teamMemPaths.ts:214-220）。
     * resolve 转绝对 + 消 .. 段；getTeamMemPath 尾分隔符防前缀攻击。不解析 symlink —— 写路径校验
     * 用 validateTeamMemWritePath/validateTeamMemKey（含 symlink 解析）。
     */
    public boolean isTeamMemPath(String filePath) {
        if (filePath == null) {
            return false;
        }
        // SECURITY: resolve() 转绝对 + 消 .. 段，防路径遍历（"team/../../etc/passwd"）
        // OPD-R2-07（G-08/G-62）：对齐 CC 大小写敏感（teamMemPaths.ts:214-220）——
        // Paths.get().toAbsolutePath().normalize() 输出平台原生分隔符，getTeamMemPath()
        // 同为原生分隔符 → 直比较即可（不再 toComparable 小写折叠）。
        String resolvedPath = Paths.get(filePath).toAbsolutePath().normalize().toString();
        String teamDir = getTeamMemPath();
        return resolvedPath.startsWith(teamDir);
    }

    /**
     * 校验写入 team memory 目录的绝对路径安全 · CC original: {@code validateTeamMemWritePath}
     * （teamMemPaths.ts:228-256）。注入向量 / .. 段逃逸 / symlink 逃逸（PSR M22186）→ PathTraversalError。
     *
     * @return 校验后的解析绝对路径
     * @throws PathTraversalError 包含注入向量或逃逸
     */
    public String validateTeamMemWritePath(String filePath) throws IOException {
        if (filePath.indexOf('\0') >= 0) {
            throw new PathTraversalError("Null byte in path: \"" + filePath + "\"");
        }
        // 第一遍：normalize .. 段 + 字符串级包含检查（先于碰文件系统的快速拒绝）
        String resolvedPath = Paths.get(filePath).toAbsolutePath().normalize().toString();
        String teamDir = getTeamMemPath();
        // 前缀攻击防护：teamDir 以 sep 结尾，team-evil/ 不匹配 team/
        if (!resolvedPath.startsWith(teamDir)) {
            throw new PathTraversalError("Path escapes team memory directory: \"" + filePath + "\"");
        }
        // 第二遍：解析最深已存在祖先的 symlink，验证真实路径仍在真实 team dir 内
        String realPath = realpathDeepestExisting(resolvedPath);
        if (!isRealPathWithinTeamDir(realPath)) {
            throw new PathTraversalError(
                "Path escapes team memory directory via symlink: \"" + filePath + "\"");
        }
        return resolvedPath;
    }

    /**
     * 校验来自服务端的相对路径 key · CC original: {@code validateTeamMemKey}（teamMemPaths.ts:265-284）。
     * sanitizePathKey + join teamDir + 字符串级包含检查 + symlink 真实包含检查。
     *
     * @return 解析后的绝对路径
     * @throws PathTraversalError key 恶意（PSR M22186）
     */
    public String validateTeamMemKey(String relativeKey) throws IOException {
        sanitizePathKey(relativeKey);
        String teamDir = getTeamMemPath();
        String fullPath = Paths.get(teamDir).resolve(relativeKey).toString();
        // 第一遍：normalize .. 段 + 字符串级包含检查
        String resolvedPath = Paths.get(fullPath).toAbsolutePath().normalize().toString();
        if (!resolvedPath.startsWith(teamDir)) {
            throw new PathTraversalError("Key escapes team memory directory: \"" + relativeKey + "\"");
        }
        // 第二遍：解析 symlink 并验证真实包含
        String realPath = realpathDeepestExisting(resolvedPath);
        if (!isRealPathWithinTeamDir(realPath)) {
            throw new PathTraversalError(
                "Key escapes team memory directory via symlink: \"" + relativeKey + "\"");
        }
        return resolvedPath;
    }

    // ════════════════════════════════════════════════════════════════
    // isTeamMemFile · :290-292
    // ════════════════════════════════════════════════════════════════

    /**
     * 路径是否在 team memory 目录内且 team 启用 · CC original: {@code isTeamMemFile}
     * （teamMemPaths.ts:290-292）= isTeamMemoryEnabled() && isTeamMemPath()。
     */
    public boolean isTeamMemFile(String filePath) {
        return isTeamMemoryEnabled() && isTeamMemPath(filePath);
    }

    /**
     * CC feature('TEAMMEM') 门控（G-65/M-1 · teamMemSecretGuard.ts:19 + watcher.ts:253）·
     * 不要求 auto-memory 启用。Java 端由注入的 {@code teamMemFeatureEnabled} supplier 建模
     * （[IMP-CM-09] 生产 = FeatureFlags.teamMem()，nexusai.feature.team-mem）；供
     * {@link TeamMemSecretGuard} 与 checkTeamMemSecrets 门控组合 feature('TEAMMEM') && isTeamMemPath 使用。
     * 与运行时开关 {@code tengu_herring_clock} 独立（双门控拆分 OPD-CM3-11/B04）。
     */
    public boolean isTeamMemFeatureEnabled() {
        return teamMemFeatureEnabled.getAsBoolean();
    }
}
