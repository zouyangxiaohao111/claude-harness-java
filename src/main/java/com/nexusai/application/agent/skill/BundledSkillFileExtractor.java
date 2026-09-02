package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bundled skill 参考文件解压器 · 对齐 CC bundledSkills.ts + filesystem.ts。
 *
 * <p>CC 在注册 bundled skill 时若带 {@code files}（BundledSkillDefinition.files，
 * bundledSkills.ts:36），把参考文件解压到确定性目录（getBundledSkillExtractDir
 * bundledSkills.ts:120-122），随后 skill prompt 加 "Base directory for this skill: <dir>"
 * 前缀（prependBaseDir bundledSkills.ts:208-220），使模型可按需 Read/Grep 这些文件——
 * 与磁盘技能同一契约。
 *
 * <p><b>安全</b>（CC filesystem.ts:349-370 注释）：
 * <ul>
 *   <li>主防御是 per-process random nonce（filesystem.ts:365-370），其他路径组件
 *       （tmpdir/VERSION/skill name/file keys）都是公开知识，无 nonce 时本地攻击者可在共享
 *       /tmp 预建目录树并做 symlink/内容交换注入。</li>
 *   <li>写文件用 O_EXCL 语义（CC Windows 'wx' 分支，bundledSkills.ts:178-184），刻意
 *       不 unlink+retry on EEXIST（bundledSkills.ts:169-175 注释：unlink 会跟中间符号链接）。</li>
 *   <li>0o700/0o600 权限让 nonce 子树 owner-only 即使 umask=0。</li>
 *   <li>resolveSkillFilePath 拒绝穿越（bundledSkills.ts:196-206）。</li>
 * </ul>
 *
 * <p><b>BD-08 O_NOFOLLOW 等价</b>：CC safeWriteFile 用 O_NOFOLLOW 防最终组件符号链接攻击
 * （bundledSkills.ts:176/:184，仅 POSIX；Windows 'wx' 分支无 O_NOFOLLOW）。Java NIO open 无
 * NOFOLLOW option，故写入前显式检测最终组件是否为符号链接（{@link #safeWriteFile}，POSIX 下拒绝）；
 * O_EXCL 用 CREATE_NEW（= CC Windows 'wx' 分支）等效；per-process nonce 仍是主防御
 * （filesystem.ts:352-363）。POSIX 权限在 Windows 为 no-op。
 *
 * <p><b>BD-05 惰性解压</b>：CC 注册期仅定值 skillRoot（getBundledSkillExtractDir，:60），解压
 * 推迟到首次 getPromptForCommand（extractionPromise memoize :64-72）。Java 用 {@link #lazyExtract}
 * 提供 memoized 惰性 supplier，由调用方（BundledSkillsBootstrapper）包装 promptFn 首调解压。
 *
 * <p><b>V-BD-5 Spring bean</b>：本类标注 {@code @Component} 使权限层
 * {@link com.nexusai.application.agent.permission.ReadPermissionChecker} 可经
 * {@code @Autowired(required=false)} 注入同一根目录来源。per-process nonce 仍由<b>静态</b>
 * {@code rootCache} 保证（filesystem.ts:365-370 memoize），故无论 Spring 单例 bean 还是
 * BundledSkillsBootstrapper 内的 {@code new} 实例，{@link #getBundledSkillsRoot()} 返回
 * <b>同一</b> nonce 根目录 —— 解压落盘路径与权限检查路径同源（对齐 CC "memoize so the
 * extraction writes and the permission check agree on the path"）。
 */
@Component
public class BundledSkillFileExtractor {

    private static final Logger log = LoggerFactory.getLogger(BundledSkillFileExtractor.class);

    /**
     * 版本隔离组件 · CC MACRO.VERSION（filesystem.ts:368）。硬编码常量对齐 CC 构建期常量语义，
     * 需与 pom.xml &lt;version&gt; 同步（当前 0.2.33）。
     */
    private static final String VERSION = "0.2.33";

    /** per-process 随机源 · CC randomBytes（filesystem.ts:367）。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 0o700 目录权限 · CC writeSkillFiles mkdir mode（bundledSkills.ts:163）。 */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIR =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    /** 0o600 文件权限 · CC safeWriteFile open mode（bundledSkills.ts:187）。 */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** 当前文件系统是否支持 POSIX 权限视图（Windows 不支持 → no-op，用 CREATE_NEW=CC 'wx'）。 */
    private static final boolean POSIX_ENABLED;

    static {
        boolean posix = false;
        try {
            posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (Exception e) {
            // Windows/受限环境：POSIX 视图不可用，回退 CREATE_NEW（= CC 'wx' 分支）
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillFileExtractor] POSIX 权限视图不可用，回退 CREATE_NEW（CC Windows 'wx' 分支 bundledSkills.ts:178-184）: {}",
                    e.getMessage());
            }
        }
        POSIX_ENABLED = posix;
    }

    /** per-process 单例的 bundled-skills 根目录。
     *
     * <p>对齐 CC {@code getBundledSkillsRoot}（filesystem.ts:365-370）：memoize + per-process
     * random nonce（16 bytes → 32 hex）+ {@code join(getClaudeTempDir(), 'bundled-skills',
     * MACRO.VERSION, nonce)}。底层 temp 根经 {@link #getClaudeTempDir()}（filesystem.ts:331-347
     * 等价，OPD-WF5-02-07 补齐 env 覆写 / Unix /tmp / realpath / uid 后缀）。
     *
     * <p>memoize 保证解压写与权限检查在进程生命周期内路径一致；版本隔离让旧二进制残留
     * 不落入 allowlist。double-checked volatile 字段保证进程内确定性。
     *
     * @return bundled-skills 根目录（进程内恒定）
     */
    public Path getBundledSkillsRoot() {
        if (rootCache == null) {
            synchronized (BundledSkillFileExtractor.class) {
                if (rootCache == null) {
                    String nonce = HexFormat.of().formatHex(randomBytes(16));
                    rootCache = Paths.get(getClaudeTempDir())
                        .resolve("bundled-skills")
                        .resolve(VERSION)
                        .resolve(nonce);
                    if (log.isDebugEnabled()) {
                        log.debug("[BundledSkillFileExtractor] bundled-skills 根目录（per-process nonce={}）→ {}（CC getBundledSkillsRoot filesystem.ts:365-370）",
                            nonce, rootCache);
                    }
                }
            }
        }
        return rootCache;
    }

    // ────────────────────────────────────────────────────────────────────────
    // getClaudeTempDir · OPD-WF5-02-07 对齐 CC filesystem.ts:307-315 / :331-347
    // ────────────────────────────────────────────────────────────────────────

    /** 当前平台是否 Windows（CC getPlatform()==='windows' 等价）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Claude temp 目录名 · CC {@code getClaudeTempDirName}（filesystem.ts:307-315）：
     * win='claude'；非 win='claude-{uid}'（uid 防多用户共享 /tmp 权限冲突）。
     */
    public static String getClaudeTempDirName() {
        if (isWindows()) {
            return "claude";
        }
        return "claude-" + getUid();
    }

    /**
     * Claude temp 目录（symlink 已解析）· CC {@code getClaudeTempDir}（filesystem.ts:331-347）：
     * {@code CLAUDE_CODE_TMPDIR || (win ? tmpdir() : '/tmp')} → realpath → {@code join(base, dirName) + sep}。
     *
     * <p>realpath 解析 symlink（macOS /tmp → /private/tmp），使路径与权限检查的 resolved 路径一致
     * （filesystem.ts:324-327 注释）。memoize：输入（env + 平台 + 系统 tmpdir realpath）进程内恒定。
     *
     * @return temp 根目录 + 平台目录名 + 尾分隔符（进程内恒定）
     */
    public static String getClaudeTempDir() {
        if (tempRootCache == null) {
            synchronized (BundledSkillFileExtractor.class) {
                if (tempRootCache == null) {
                    String base = System.getenv("CLAUDE_CODE_TMPDIR");
                    if (base == null || base.isBlank()) {
                        base = isWindows()
                            ? System.getProperty("java.io.tmpdir")
                            : "/tmp";
                    }
                    // realpath（CC :339-344 realpathSync；失败回退原路径）
                    String resolved = base;
                    try {
                        resolved = Paths.get(base).toRealPath().normalize().toString();
                    } catch (IOException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("[BundledSkillFileExtractor] getClaudeTempDir realpath 失败回退原路径（CC filesystem.ts:339-344）: {} → {}",
                                base, e.getMessage());
                        }
                    }
                    tempRootCache = Paths.get(resolved, getClaudeTempDirName())
                        .normalize().toString() + File.separator;
                    if (log.isDebugEnabled()) {
                        log.debug("[BundledSkillFileExtractor] getClaudeTempDir（CC filesystem.ts:331-347）→ {}", tempRootCache);
                    }
                }
            }
        }
        return tempRootCache;
    }

    /**
     * 当前进程 uid（CC process.getuid?.() ?? 0，filesystem.ts:313）。Java 无可移植 getuid：
     * 非 Windows 用 JDK UnixSystem（失败兜底 0）；Windows 恒 0（tmpdir 已 per-user）。
     */
    private static long getUid() {
        if (isWindows()) {
            return 0L;
        }
        try {
            return new com.sun.security.auth.module.UnixSystem().getUid();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 确定性解压目录 · CC getBundledSkillExtractDir（bundledSkills.ts:120-122）：
     * {@code join(getBundledSkillsRoot(), skillName)}。
     *
     * @param skillName bundled skill 名（如 verify）
     * @return 该 skill 的参考文件解压目录
     */
    public Path getBundledSkillExtractDir(String skillName) {
        return getBundledSkillsRoot().resolve(skillName);
    }

    /**
     * 解压 bundled skill 参考文件（fail-soft）。
     *
     * <p>对齐 CC {@code extractBundledSkillFiles}（bundledSkills.ts:131-145）：try →
     * writeSkillFiles → return dir；catch → logForDebugging → return null。失败时 skill
     * 继续可用、仅无 base-dir 前缀（注册方对 null 不设 baseDir）。
     *
     * @param skillName bundled skill 名
     * @param files     relPath → content（键为相对路径、正斜杠、无 '..'，对齐 CC files 契约
     *                  BundledSkillDefinition.files bundledSkills.ts:36）
     * @return 解压成功的目录；失败返回 null（不抛出，不阻断注册）
     */
    public Path extractBundledSkillFiles(String skillName, Map<String, String> files) {
        Path dir = getBundledSkillExtractDir(skillName);
        try {
            writeSkillFiles(dir, files);
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillFileExtractor] 解压 bundled skill '{}' 参考文件（{} 个）到 {}（CC extractBundledSkillFiles bundledSkills.ts:131-145）",
                    skillName, files == null ? 0 : files.size(), dir);
            }
            return dir;
        } catch (Exception e) {
            // fail-soft：CC logForDebugging 后返回 null（bundledSkills.ts:139-144）
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillFileExtractor] 解压 bundled skill '{}' 到 {} 失败: {}（fail-soft 返回 null，CC bundledSkills.ts:139-144）",
                    skillName, dir, e.getMessage());
            }
            return null;
        }
    }

    /**
     * 惰性解压（memoized）· 对齐 CC extractionPromise ??= extractBundledSkillFiles(...)
     * （bundledSkills.ts:64-72）。
     *
     * <p>CC 注册期只定值 skillRoot（getBundledSkillExtractDir，:60），解压推迟到首次
     * getPromptForCommand 调用，且 memoize promise（非 result）使并发调用方等待同一解压而非
     * 竞争写。Java 等价：返回 memoized {@link Supplier}，首调 {@code get()} 触发解压并缓存结果
     * （含 null fail-soft），后续并发调用阻塞等待首次解压完成并复用结果。
     *
     * @param skillName bundled skill 名
     * @param files     relPath → content（与 extractBundledSkillFiles 同契约）
     * @return memoized 惰性解压 supplier（首调解压，后续复用结果，含 null）
     */
    public Supplier<Path> lazyExtract(String skillName, Map<String, String> files) {
        return memoize(() -> extractBundledSkillFiles(skillName, files));
    }

    /**
     * 写全部参考文件 · 对齐 CC writeSkillFiles（bundledSkills.ts:147-167）。
     *
     * <p>按父目录分组（每组 mkdir(parent,{recursive,mode:0o700}) 一次，bundledSkills.ts:163）
     * 再逐个 safeWriteFile（:164）。CC 并行写，Java 顺序写（性能差异非可观察契约）。
     *
     * @param dir   目标根目录（getBundledSkillExtractDir 结果）
     * @param files relPath → content
     * @throws IOException 任一文件写失败（穿越由 resolveSkillFilePath 抛 IllegalArgumentException）
     */
    public void writeSkillFiles(Path dir, Map<String, String> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return;
        }
        // 按父目录分组，每组 mkdir 一次后写
        Map<Path, List<Map.Entry<Path, String>>> byParent = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = resolveSkillFilePath(dir, entry.getKey());
            byParent.computeIfAbsent(target.getParent(), k -> new ArrayList<>())
                .add(Map.entry(target, entry.getValue()));
        }
        for (Map.Entry<Path, List<Map.Entry<Path, String>>> group : byParent.entrySet()) {
            createDirectoriesOwnerOnly(group.getKey());
            for (Map.Entry<Path, String> entry : group.getValue()) {
                safeWriteFile(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 递归创建目录并设 owner-only（0o700）· 对齐 CC mkdir(parent,{recursive,mode:0o700})
     * （bundledSkills.ts:163）。POSIX 下每层 createDirectory 带 0o700 属性；Windows 无 POSIX
     * 视图则普通 createDirectory（权限 no-op，主防御 nonce 已复刻）。
     *
     * @param dir 待创建目录
     * @throws IOException 创建失败
     */
    private void createDirectoriesOwnerOnly(Path dir) throws IOException {
        if (Files.exists(dir)) {
            return;
        }
        Path parent = dir.getParent();
        if (parent != null && !Files.exists(parent)) {
            createDirectoriesOwnerOnly(parent);
        }
        if (POSIX_ENABLED) {
            Files.createDirectory(dir, OWNER_ONLY_DIR);
        } else {
            Files.createDirectory(dir);
        }
    }

    /**
     * 安全写单文件 · 对齐 CC safeWriteFile（bundledSkills.ts:186-193）。
     *
     * <p>CREATE_NEW = O_CREAT|O_EXCL，等价 CC Windows 'wx' 分支（bundledSkills.ts:178-184）；
     * 已存在文件抛 {@link java.nio.file.FileAlreadyExistsException}，刻意不 unlink+retry
     * （CC bundledSkills.ts:169-175 注释：unlink 会跟中间符号链接）。POSIX 下 0o600
     * （bundledSkills.ts:187）。UTF-8 写入（:189）。
     *
     * <p>BD-08 O_NOFOLLOW 等价：CC safeWriteFile 用 O_NOFOLLOW 防最终组件符号链接攻击
     * （bundledSkills.ts:176/:184，仅 POSIX；Windows 'wx' 分支无 O_NOFOLLOW）。Java NIO open
     * 无 NOFOLLOW option，故写入前显式检测最终组件是否为符号链接，POSIX 下为符号链接则拒绝
     * （belt-and-suspenders，主防御仍是 per-process nonce）。
     *
     * @param path    目标文件（须已在 resolveSkillFilePath 校验过的根目录内）
     * @param content 文件内容
     * @throws IOException 写入失败或文件已存在（FileAlreadyExistsException 不覆盖）或目标为符号链接
     */
    public void safeWriteFile(Path path, String content) throws IOException {
        // BD-08：CC O_NOFOLLOW（bundledSkills.ts:176/:184）等价——POSIX 下写入前拒绝符号链接目标
        // （防 symlink 攻击；CC Windows 'wx' 分支无 O_NOFOLLOW，故 POSIX_ENABLED 门控）。
        if (POSIX_ENABLED && Files.isSymbolicLink(path)) {
            throw new IOException("Refusing to write through symbolic link (CC O_NOFOLLOW bundledSkills.ts:176): " + path);
        }
        Set<StandardOpenOption> options =
            Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        if (POSIX_ENABLED) {
            try (SeekableByteChannel channel = Files.newByteChannel(path, options, OWNER_ONLY_FILE)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            }
        } else {
            try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    /**
     * 规范化并校验 skill 相对路径 · 对齐 CC resolveSkillFilePath（bundledSkills.ts:196-206）。
     *
     * <p>normalize 后若 isAbsolute 或按 pathSep 与 '/' 双切含 '..' → 抛异常。Java 等价：
     * {@link Path#normalize()} 折叠 {@code a/../b} → {@code b}（CC Node normalize 同），故
     * {@code a/../b} 不穿越、落回根内（与 CC 一致，bundledSkills.ts:197-204）；真正的穿越是
     * {@code ../escape} / {@code a/../../escape} / 绝对路径。
     *
     * @param baseDir 技能根目录
     * @param relPath 技能相对路径（键）
     * @return baseDir 下校验后的绝对目标路径
     * @throws IllegalArgumentException 路径穿越或为绝对路径（CC bundledSkills.ts:203 同文案）
     */
    public Path resolveSkillFilePath(Path baseDir, String relPath) {
        String normalized = Paths.get(relPath).normalize().toString();
        Path normalizedPath = Paths.get(normalized);
        // CC bundledSkills.ts:199-202 双切：pathSep 与 '/'（Node normalize 在平台间行为不同，
        // 双切保证 Windows 反斜杠与正斜杠都覆盖）；Java Path.normalize 后分隔符已是 OS 原生。
        List<String> bySeparator = List.of(normalized.split(java.util.regex.Pattern.quote(File.separator)));
        List<String> bySlash = List.of(normalized.split("/"));
        if (normalizedPath.isAbsolute() || bySeparator.contains("..") || bySlash.contains("..")) {
            throw new IllegalArgumentException("bundled skill file path escapes skill dir: " + relPath);
        }
        return baseDir.resolve(normalized).normalize();
    }

    /** 生成随机字节 · CC randomBytes（filesystem.ts:367）。 */
    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * memoize 单个 supplier 结果（含 null）· 对齐 CC extractionPromise memoize
     * （bundledSkills.ts:64「memoize the promise (not the result) so concurrent callers
     * await the same extraction」）。synchronized 使并发调用方等待首次计算完成，避免竞争写。
     */
    private static Supplier<Path> memoize(Supplier<Path> delegate) {
        return new Supplier<>() {
            private boolean done = false;
            private Path value;

            @Override
            public synchronized Path get() {
                if (!done) {
                    value = delegate.get();
                    done = true;
                }
                return value;
            }
        };
    }

    /** per-process 单例缓存 · CC memoize（filesystem.ts:365）。 */
    private static volatile Path rootCache;

    /** getClaudeTempDir per-process 单例缓存 · CC memoize（filesystem.ts:331）。 */
    private static volatile String tempRootCache;
}
