package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.agent.CwdResolution;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

/**
 * 路径安全校验 · 对齐 CC utils/path.ts:138 sanitizePath 助手。
 *
 * <p>确保所有文件操作都限定在 workspace 内，禁止 {@code ../../etc/passwd} 这种逃逸。
 *
 * <h2>CC 对齐</h2>
 * <p>CC 的 FileRead / FileWrite / Edit 工具在 {@code validateInput()}（{@code toolExecution.ts:682}）
 * 阶段做路径校验。CC 用更复杂的规则（多 worktree / 软链接解析），本类做最严格版本（解析
 * 软链接 + 路径前缀检查）—— 这与 s02 教学版一致。
 *
 * <p>s02 [P2] 修补：通过 {@link Path#toRealPath()} 解析软链接，消除
 * {@code ln -s /etc/passwd workspace/link} 绕过 PathGuard 的安全漏洞。
 *
 * <p>注意：本类<b>不</b>做"白名单路径"判断（如只允许 /src 目录），只做"不能逃出 workspace"
 * 的判断。更细的权限由 s03 permission 章节加（canUseTool 检查）。
 *
 * <h2>工作目录来源 · 对齐 CC expandPath(baseDir=getCwd()) 每调用取（INV-1）</h2>
 * <p>CC 文件工具相对路径基准 = {@code expandPath(path, baseDir)} 的 {@code baseDir} 默认
 * {@code getCwd()}（CC {@code utils/path.ts:32-35 expandPath}，{@code baseDir ?? getCwd()}），
 * <b>每次调用取</b>当前会话 cwd（非构造时冻结）。Java 端本类持 {@link Supplier}<{@link Path}>
 * {@code workdirSupplier}：{@link #workdir()} 与 {@link #resolve(String)} 每调用调
 * {@code supplier.get()} 取当前 cwd，对齐 CC「cd / worktree 入口后下一条文件工具用新 cwd」
 * （INV-1 / INV-2）。
 *
 * <p><b>两种构造形态</b>：
 * <ul>
 *   <li>{@link #PathGuard(Path)}（固定 workdir，构造时 realpath+NFC 归一化）—— 测试与
 *       固定 workspace 场景，workdir 不可变。</li>
 *   <li>{@link #PathGuard(Supplier)}（动态 workdir）—— 生产 bean
 *       {@link com.nexusai.infra.config.ToolConfig#workspacePathGuard()} 注入
 *       {@code () -> Path.of(CwdResolution.getCwd())}，每调用经统一入口
 *       {@link CwdResolution#getCwd()} 解析（override ?? sessionCwd ?? boundProject ??
 *       user.dir，对齐 CC pwd/getCwd 三层 + user.dir 兜底）。</li>
 * </ul>
 *
 * <p><b>已知简化</b>：{@link #resolve(String)} 与 {@link #workdir()} 在同一调用内取一次
 * workdir 快照（局部变量），保证该次 resolve 的 baseDir 与逃逸检查基座一致；跨调用则取新值
 * （对齐 CC 每调用取 getCwd()）。
 */
public class PathGuard {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(PathGuard.class);

    /**
     * 工作目录供应器 · 每调用取（对齐 CC getCwd() per-call · INV-1）。
     * <ul>
     *   <li>固定形态：{@code () -> normalizedRealPath}（构造时冻结，测试 / 固定 workspace）</li>
     *   <li>动态形态：{@code () -> Path.of(CwdResolution.getCwd())}（生产 bean，每调用经统一入口）</li>
     * </ul>
     */
    private final Supplier<Path> workdirSupplier;

    /**
     * 固定 workdir 构造 · 测试与固定 workspace 场景用。
     *
     * <p>构造时 realpath + normalize 归一化（对齐 CC setCwdState NFC + Shell.ts setCwd realpathSync），
     * 之后 {@link #workdir()} 恒返回该归一化路径（不可变）。
     *
     * @param workdir workspace 根目录（绝对路径）。构造时解析自身 symlink。
     */
    public PathGuard(Path workdir) {
        if (workdir == null) {
            throw new IllegalArgumentException("workdir is null");
        }
        Path normalized = workdir.toAbsolutePath().normalize();
        Path real = toRealPathOrFallback(normalized);
        // 固定 supplier：恒返回构造时归一化的路径（不可变语义，对齐既有测试期望）
        this.workdirSupplier = () -> real;
    }

    /**
     * 动态 workdir 构造 · 生产 bean 用 · 对齐 CC expandPath baseDir=getCwd() 每调用取（INV-1）。
     *
     * <p>supplier 应返回经 {@link CwdResolution#normalizeCwd} 归一化的 cwd（生产 bean 传
     * {@code () -> Path.of(CwdResolution.getCwd())}，{@code getCwd()} 内部已 realpath+NFC 归一化）。
     * supplier 返回 null 时兜底 {@code user.dir}（对齐 CC getCwd catch 兜底，不抛）。
     *
     * @param workdirSupplier 工作目录供应器（每调用取当前会话 cwd）
     */
    public PathGuard(Supplier<Path> workdirSupplier) {
        if (workdirSupplier == null) {
            throw new IllegalArgumentException("workdirSupplier is null");
        }
        this.workdirSupplier = workdirSupplier;
    }

    /** 尝试解析 symlink，失败返回原路径。 */
    private static Path toRealPathOrFallback(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    /**
     * 把相对路径解析为 workdir 下的绝对路径，并校验不逃出。
     *
     * <p>s02 [P2] 修补：{@code toRealPath()} 解析软链接 ——
     * 如果 symlink 指向 workdir 外部（如 {@code ln -s /etc/passwd link}），
     * {@code startsWith(workdir)} 检查会发现真实路径不在 workdir 内并拒绝。
     * 对于新建文件（{@link NoSuchFileException}），降级为不解析软链接的路径。
     *
     * @param relative 相对路径（允许 null/blank → 返回 workdir 本身）
     * @return 解析后的绝对路径
     * @throws SecurityException 如果路径解析后逃出 workdir
     */
    public Path resolve(String relative) {
        // 每调用取当前 workdir（对齐 CC expandPath baseDir=getCwd() per-call · INV-1）
        Path workdir = currentWorkdir();
        if (relative == null || relative.isBlank()) {
            return workdir;
        }
        Path resolved = workdir.resolve(relative).normalize();
        // 已通过 toRealPath 解决软链接绕过（对齐 CC safePath）
        try {
            resolved = resolved.toRealPath();
        } catch (NoSuchFileException e) {
            // 新建文件（尚不存在），不解析软链接，用 normalize() 结果
        } catch (IOException e) {
            // 其他 I/O 错误也降级（权限问题等），不阻断正常操作
        }
        if (!resolved.startsWith(workdir)) {
            throw new SecurityException(
                "Path escapes workspace: '" + relative + "' → " + resolved);
        }
        return resolved;
    }

    /**
     * 当前工作目录 · 每调用经 {@link #workdirSupplier} 取（对齐 CC getCwd() per-call · INV-1）。
     *
     * <p>固定形态返回构造时归一化路径；动态形态返回 {@link CwdResolution#getCwd()} 解析的
     * 当前会话 cwd（override ?? sessionCwd ?? boundProject ?? user.dir）。
     */
    public Path workdir() {
        return currentWorkdir();
    }

    /**
     * 取当前 workdir 快照 · supplier 返回 null 时兜底统一入口 {@link CwdResolution#getCwd()}
     * （对齐 CC getCwd catch 兜底，不抛；不直读 user.dir · INV-6）。
     */
    private Path currentWorkdir() {
        Path wd = workdirSupplier.get();
        if (wd == null) {
            // supplier 异常返回 null → 走统一入口（CwdResolution 内部 L4 user.dir 兜底，
            // 符合 INV-4；本类不直读 user.dir）
            wd = Path.of(CwdResolution.getCwd());
        }
        return wd.toAbsolutePath().normalize();
    }

    /**
     * [FIX-A backfill-observable] 纯路径展开 · 镜像 CC {@code utils/path.ts:32-85 expandPath}。
     *
     * <p>展开 ~ 家目录 / 相对路径 / 绝对路径为归一化绝对路径。与 {@link #resolve(String)}
     * 的关键区别（二者职责不可混淆）：
     * <ul>
     *   <li><b>{@link #resolve(String)}</b>：做 {@link Path#toRealPath()} 软链接解析 +
     *       逃逸检查（逃出 workspace 抛 {@link SecurityException}），供 {@code execute} 前
     *       路径守卫用；</li>
     *   <li><b>本方法</b>：<b>纯展开</b>，<b>不解析软链接</b>、<b>不抛逃逸异常</b>、幂等，
     *       供 {@code backfillObservableInput} 阶段给 hook/canUseTool 看绝对化路径，
     *       防 {@code ~}/相对路径绕过 hook allowlist（CC FileEditTool.ts:116-120 注释语义
     *       "expand so hook allowlists can't be bypassed via ~ or relative paths"）。</li>
     * </ul>
     *
     * <p>映射（镜像 CC path.ts:32-85 分支）：
     * <ul>
     *   <li>{@code ~} → {@code System.getProperty("user.home")}（CC homedir()）</li>
     *   <li>{@code ~/x} → {@code home/x}（CC join(homedir(), x)）</li>
     *   <li>绝对路径 → {@code normalize()}（CC isAbsolute → normalize）</li>
     *   <li>相对路径 → {@code Paths.get(baseDir).resolve(raw).normalize()}（CC resolve(baseDir, path)）</li>
     *   <li>trim 空 → {@code baseDir} 归一化（CC normalize(actualBaseDir)）</li>
     * </ul>
     *
     * <p>注：CC 在 Windows 上额外做 POSIX 风格 {@code /c/Users/...} → Windows 路径转换
     * （path.ts:73-82）；Java 端 {@link Paths#get} 原生处理平台路径，无此 Git Bash 桥接
     * 需求，登记为已知简化（相对路径基准与 guard.workdir() 一致，见 backfill 调用点）。
     *
     * @param raw     原始路径（可 null/空白；null 字节触发异常，镜像 CC path.ts:48-51）
     * @param baseDir 相对路径解析基座（调用方传 {@code guard.workdir().toString()}，
     *                等价 CC getCwd() 语义）
     * @return 展开后的绝对路径（平台原生格式、归一化）
     * @throws IllegalArgumentException {@code raw} 含 null 字节（镜像 CC path.ts:48-51）
     */
    public static String expandPath(String raw, String baseDir) {
        // baseDir 缺省走统一入口 CwdResolution.getCwd()（对齐 CC expandPath baseDir ?? getCwd()，
        // 不直读 user.dir · INV-6）。生产调用方均传 guard.workdir().toString()（动态取），此分支仅兜底。
        String actualBaseDir = baseDir != null ? baseDir : CwdResolution.getCwd();
        if (raw == null) {
            // 镜像 CC path.ts:40-42: typeof path !== 'string' → TypeError
            throw new IllegalArgumentException("Path must be a string, received null");
        }
        // 镜像 CC path.ts:48-51: 含 null 字节抛错
        if (raw.indexOf('\0') >= 0 || actualBaseDir.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Path contains null bytes");
        }
        String trimmed = raw.trim();
        // 镜像 CC path.ts:54-57: trim 空 → normalize(baseDir)
        if (trimmed.isEmpty()) {
            return Paths.get(actualBaseDir).toAbsolutePath().normalize().toString();
        }
        String home = System.getProperty("user.home", ".");
        // 镜像 CC path.ts:60-62: '~' → homedir()
        if ("~".equals(trimmed)) {
            return Paths.get(home).toAbsolutePath().normalize().toString();
        }
        // 镜像 CC path.ts:64-66: '~/x' → join(homedir(), x)
        if (trimmed.startsWith("~/")) {
            return Paths.get(home).resolve(trimmed.substring(2)).normalize().toString();
        }
        Path p = Paths.get(trimmed);
        // 镜像 CC path.ts:78-80: 绝对 → normalize
        if (p.isAbsolute()) {
            return p.normalize().toString();
        }
        // 镜像 CC path.ts:83-85: 相对 → resolve(baseDir, path).normalize()
        return Paths.get(actualBaseDir).resolve(trimmed).normalize().toString();
    }

    /** 便利构造：从字符串路径创建。 */
    public static PathGuard of(String workdirPath) {
        return new PathGuard(Paths.get(workdirPath));
    }
}
