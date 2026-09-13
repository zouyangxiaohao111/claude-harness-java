package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.agent.CwdResolution;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

/**
 * 路径展开工具 · 对齐 CC {@code utils/path.ts expandPath}（2026-09-03 用户拍板删 PathGuard 逃逸拦截）。
 *
 * <p><b>WHY</b>：CC 真源 FileReadTool.ts:443 validateInput 仅 {@code expandPath(file_path)}（展开路径），
 * <b>不做</b>"逃出 workspace" 检查——CC 信任模型可读任意绝对路径，安全靠权限弹窗（canUseTool / deny rule）。
 * 本类原 s02 教学版自建"最严格版本"（toRealPath 软链接解析 + startsWith 逃逸前缀检查），偏离 CC，
 * 误伤附件（Desktop/pdf-cache）、子代理 output、系统文件读取（日志实证 2026-09-03 blocked path escape）。
 * 用户拍板删除逃逸拦截，最大限度对齐 CC：{@link #resolve(String)} 现为纯展开（镜像
 * {@code expandPath}），绝对/相对路径只展开不拦截；~ / null 字节 / Windows POSIX 转换全处理。
 *
 * <p><b>安全边界移交</b>：删除 PathGuard 逃逸后，生产（checkPermissions 注入）路径的 workspace
 * 边界由 {@code ReadPermissionChecker.isInWorkingDir}（CC 对齐 filesystem.ts:1136-1151，锚
 * originalCwd + additionalWorkingDirectories，fail-closed）承担；本类不再做 execute 阶段第二道防线。
 *
 * <p><b>已知简化</b>：不再解析软链接（对齐 CC expandPath 仅 normalize）；相对路径逃逸（{@code ../}）
 * 不再拦截（CC 语义，安全靠权限层）。
 *
 * <h2>工作目录来源 · 对齐 CC expandPath(baseDir=getCwd()) 每调用取（INV-1）</h2>
 * <p>CC 文件工具相对路径基准 = {@code expandPath(path, baseDir)} 的 {@code baseDir} 默认
 * {@code getCwd()}（CC {@code utils/path.ts:32-35 expandPath}，{@code baseDir ?? getCwd()}），
 * <b>每次调用取</b>当前会话 cwd（非构造时冻结）。Java 端本类持两个来源：
 * <ul>
 *   <li>{@link #sessionWorkdirResolver}（会话 cwd）—— 会话感知重载
 *       {@link #workdir(String)} / {@link #resolve(String, String)} 每调用经它解析<b>该会话</b>
 *       cwd，对齐 CC「cd / worktree 入口后下一条文件工具用新 cwd」（INV-1 / INV-2）。</li>
 *   <li>{@link Supplier}{@code <Path> workdirSupplier}（无会话兜底）—— 无会话入参的
 *       {@link #workdir()} / {@link #resolve(String)} 每调用调 {@code supplier.get()}。</li>
 * </ul>
 *
 * <p><b>两种构造形态</b>：
 * <ul>
 *   <li>{@link #PathGuard(Path)}（固定 workdir，构造时 realpath+NFC 归一化）—— 测试与
 *       固定 workspace 场景，workdir 不可变（<b>含会话感知重载</b>：sessionId 被忽略，基准仍为该
 *       fixed workdir）。</li>
 *   <li>{@link #PathGuard(Supplier)}（动态 workdir）—— 生产 bean
 *       {@link com.nexusai.infra.config.ToolConfig#workspacePathGuard()} 注入
 *       {@code () -> Path.of(CwdResolution.getCwd(null))}。供应器签名 {@code Supplier<Path>}
 *       <b>不承载 sessionId</b> ⇒ 该形态的无会话调用只能按「无会话」解析（override / 进程 user.dir），
 *       仅作兜底；<b>会话</b>基准走 {@link #sessionWorkdirResolver}（默认
 *       {@code CwdResolution.getCwd(sessionId)}）。</li>
 * </ul>
 *
 * <h2>会话感知（唯一正确用法 · 工具必须走这条）</h2>
 * <p>本类持 {@link #sessionWorkdirResolver}（动态形态默认
 * {@code sessionId -> Path.of(CwdResolution.getCwd(sessionId))}），会话感知重载
 * {@link #workdir(String)} / {@link #resolve(String, String)} 以<b>该会话的 cwd</b> 为相对路径基准。
 *
 * <p><b>调用方应传 {@code ToolUseContext.sessionId()}</b>（文件工具均持有 ctx）：
 * <pre>
 *   Path file = guard.resolve(ctx.sessionId(), relPath);
 * </pre>
 * 不传会话（{@link #resolve(String)} / {@link #workdir()}，以及传 {@code null}/空白 sessionId）
 * ⇒ 回落无会话兜底基准（动态形态 = 进程 user.dir；固定形态 = 构造时冻结 workdir），
 * 仅供测试 / 固定 workspace / 静态工具用，并 WARN 一次（缺失会话会让相对路径解析到错误目录）。
 *
 * <p><b>已知简化</b>：{@link #resolve(String, String)} 与 {@link #workdir(String)} 在同一调用内
 * 取一次 workdir 快照（局部变量），保证该次 resolve 的 baseDir 与同一基准的校验一致；跨调用则
 * 取新值（对齐 CC 每调用取 getCwd()）。
 */
public class PathGuard {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(PathGuard.class);

    /** [批 3c 会话态显式化] 「无会话入参」只 WARN 一次（下方 {@link #sessionWorkdir}）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean NO_SESSION_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** 「expandPath 无 baseDir 回落」只 WARN 一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean EXPAND_BASE_DIR_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 工作目录供应器 · 每调用取（对齐 CC getCwd() per-call · INV-1）· <b>无会话兜底</b>。
     * <ul>
     *   <li>固定形态：{@code () -> normalizedRealPath}（构造时冻结，测试 / 固定 workspace）</li>
     *   <li>动态形态：{@code () -> Path.of(CwdResolution.getCwd(null))}（生产 bean；
     *       供应器<b>无 sessionId 形参</b> ⇒ 恒按「无会话」解析，仅兜底）</li>
     * </ul>
     */
    private final Supplier<Path> workdirSupplier;

    /**
     * 会话感知 workdir 解析器 · 会话 cwd 的<b>唯一来源</b>（每调用取，对齐 CC getCwd() per-call）。
     *
     * <p>默认值：动态构造（{@link #PathGuard(Supplier)} / 生产 bean）为
     * {@code sessionId -> Path.of(CwdResolution.getCwd(sessionId))}（三层回落
     * override ?? sessionCwd ?? boundProject ?? user.dir）——<b>默认即够用，不要传 null</b>；
     * 固定构造（{@link #PathGuard(Path)} / 测试）为恒返回该固定 workdir。
     * 测试可经 {@link #setSessionWorkdirResolver} 注入固定值（测试缝）。
     *
     * <p>volatile：bean 为进程级单例，setter 通常在装配期调用，读侧（工具池线程）须可见。
     */
    private volatile java.util.function.Function<String, Path> sessionWorkdirResolver =
        sessionId -> Path.of(CwdResolution.getCwd(sessionId));

    /**
     * 固定 workdir 构造 · 测试与固定 workspace 场景用。
     *
     * <p>构造时 realpath + normalize 归一化（对齐 CC setCwdState NFC + Shell.ts setCwd realpathSync），
     * 之后 {@link #workdir()} 恒返回该归一化路径（不可变）。
     *
     * <p><b>会话感知重载亦钉在该 workdir：</b>{@link #workdir(String)} / {@link #resolve(String, String)}
     * 忽略 {@code sessionId} 直接返回/以该固定 workdir 为基准（「workdir 不可变」不因入参而异，
     * 测试注入的临时 workspace 不被会话 cwd 顶掉）；需要按会话解析的测试可经
     * {@link #setSessionWorkdirResolver} 显式注入解析器。
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
        // 固定形态的会话解析器同样钉在该 workdir 上：「workdir 不可变」不因是否传 sessionId 而异
        // （否则测试注入的临时 workspace 会被会话 cwd 顶掉）。需要按会话解析的测试可经
        // setSessionWorkdirResolver 显式注入。
        this.sessionWorkdirResolver = sessionId -> real;
    }

    /**
     * 动态 workdir 构造 · 生产 bean 用 · 对齐 CC expandPath baseDir=getCwd() 每调用取（INV-1）。
     *
     * <p>supplier 应返回经 {@link CwdResolution#normalizeCwd} 归一化的 cwd（生产 bean 传
     * {@code () -> Path.of(CwdResolution.getCwd(null))}，{@code getCwd} 内部已 realpath+NFC
     * 归一化）。supplier 返回 null 时兜底按「无会话」解析 → 进程 {@code user.dir}
     * （对齐 CC getCwd catch 兜底，不抛）。
     *
     * <p><b>本构造器只定义「无会话兜底」基准</b>；会话 cwd 由 {@link #sessionWorkdirResolver}
     * 承担（构造后即可用，默认走 {@link CwdResolution#getCwd(String)}）。需要会话 cwd 的调用方
     * 应调 {@link #resolve(String, String)} / {@link #workdir(String)} 并传
     * {@code ToolUseContext.sessionId()}。
     *
     * @param workdirSupplier 无会话兜底工作目录供应器（每调用取一次；<b>无 sessionId 形参</b>）
     */
    public PathGuard(Supplier<Path> workdirSupplier) {
        if (workdirSupplier == null) {
            throw new IllegalArgumentException("workdirSupplier is null");
        }
        this.workdirSupplier = workdirSupplier;
    }

    /**
     * 注入会话感知 workdir 解析器（测试缝）· 生产用默认
     * {@code sessionId -> Path.of(CwdResolution.getCwd(sessionId))}，<b>无需调用本方法</b>。
     *
     * @param sessionWorkdirResolver 会话 id → 会话 cwd 解析器（不得为 null）
     */
    public void setSessionWorkdirResolver(java.util.function.Function<String, Path> sessionWorkdirResolver) {
        if (sessionWorkdirResolver == null) {
            throw new IllegalArgumentException("sessionWorkdirResolver is null");
        }
        this.sessionWorkdirResolver = sessionWorkdirResolver;
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
     * 把相对路径解析为 workdir 下的绝对路径（<b>无会话兜底形态</b> · 委托
     * {@link #resolve(String, String)}{@code (null, relative)}）· 纯展开语义（镜像 CC
     * {@code expandPath}），绝对/相对路径只展开不拦截。
     *
     * <p><b>无会话入参 ⇒ 相对路径基准回落无会话兜底</b>（动态形态 = 进程 {@code user.dir}）；
     * 相对路径要按会话 cwd 解析的调用方<b>必须</b>改调
     * {@link #resolve(String, String)} 并传 {@code ToolUseContext.sessionId()}。
     *
     * @param relative 相对路径（允许 null/blank → 返回 workdir 本身）
     * @return 解析后的绝对路径
     */
    public Path resolve(String relative) {
        return resolve(null, relative);
    }

    /**
     * 会话感知重载 · 把相对路径解析为<b>该会话 cwd</b> 下的绝对路径。
     *
     * <p>语义与 {@link #resolve(String)} 完全一致（纯展开，不拦截、不解析软链接），唯一差别是
     * 相对路径基准 = {@link #workdir(String)}（会话 cwd）。{@code sessionId} 非空 ⇒ 基准取
     * {@link #sessionWorkdirResolver} 解析出的会话 cwd；{@code sessionId} 为 null/空白 ⇒ 回落
     * 无会话兜底基准并 WARN 一次。
     *
     * <p>基准目录与校验用同一份 cwd 快照（本方法内取一次局部变量），不会出现「以会话 cwd 解析、
     * 却以进程 cwd 校验」的错位。
     *
     * @param sessionId 会话 ID（<b>调用方应传 {@code ToolUseContext.sessionId()}</b>；null/空白 ⇒ 无会话兜底）
     * @param relative  相对路径（允许 null/blank → 返回基准目录本身）
     * @return 解析后的绝对路径
     */
    public Path resolve(String sessionId, String relative) {
        // [CC 对齐 2026-09-03 用户拍板 · 删 PathGuard 逃逸拦截] 纯展开语义（镜像 CC expandPath）：
        //   绝对/相对路径只展开不拦截（绝对路径任意可读；相对路径 resolve(baseDir).normalize() 不限制
        //   是否在 workspace 内），~ / null 字节 / Windows POSIX 转换全处理；不解析软链接、不做
        //   "逃出 workspace" 检查（s02 教学版遗留，CC 真源 FileReadTool.ts:443 仅 expandPath）。
        //   生产安全边界由 ReadPermissionChecker.isInWorkingDir（checkPermissions 注入时，CC 对齐
        //   filesystem.ts:1136-1151）承担，本类不再做 execute 阶段第二道防线。
        // 每调用取一次基准快照（对齐 CC expandPath baseDir=getCwd() per-call · INV-1）：会话 cwd
        // 优先，缺失才回落无会话兜底；相对路径解析与（若有）同基准校验共用这一个快照。
        Path workdir = sessionWorkdir(sessionId);
        if (relative == null || relative.isBlank()) {
            return workdir;
        }
        return Paths.get(expandPath(relative, workdir.toString()));
    }

    /**
     * 当前工作目录 · <b>无会话兜底形态</b> · 每调用经 {@link #workdirSupplier} 取
     * （对齐 CC getCwd() per-call · INV-1）。
     *
     * <p>固定形态返回构造时归一化路径；动态形态返回 supplier 解析的 workdir（进程 user.dir 层）。
     * 需要会话 cwd 的调用方<b>必须</b>改调 {@link #workdir(String)} 并传
     * {@code ToolUseContext.sessionId()}。
     */
    public Path workdir() {
        return currentWorkdir();
    }

    /**
     * 会话感知 workdir · 该会话当前工作目录（每调用取 · 对齐 CC getCwd() per-call · INV-1）。
     *
     * <p>{@code sessionId} 非空 ⇒ {@link #sessionWorkdirResolver} 解析出的会话 cwd（默认
     * {@link CwdResolution#getCwd(String)}：override ?? sessionCwd ?? boundProject ?? user.dir）；
     * 为 null/空白 ⇒ 回落 {@link #workdir()} 无会话兜底基准并 WARN 一次。
     *
     * @param sessionId 会话 ID（<b>调用方应传 {@code ToolUseContext.sessionId()}</b>；null/空白 ⇒ 无会话兜底）
     * @return 归一化绝对路径的 workdir（恒非 null）
     */
    public Path workdir(String sessionId) {
        return sessionWorkdir(sessionId);
    }

    /**
     * 取会话 cwd 快照 · 会话感知重载的唯一入口。
     *
     * <p>{@code sessionId} null/空白 → 无会话兜底（{@link #currentWorkdir()}）+ WARN 一次
     * （不得只 DEBUG：缺失会话会让相对路径静默解析到错误目录，即工作区错位）。
     * 解析器返回 null / 抛异常 → 同样回落 + WARN（对齐 CC getCwd catch 兜底，不抛）。
     *
     * @param sessionId 会话 ID（可 null）
     * @return 归一化绝对路径的 workdir（恒非 null）
     */
    private Path sessionWorkdir(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            if (NO_SESSION_WARNED.compareAndSet(false, true)) {
                log.warn("[PathGuard] 会话感知重载未收到 sessionId（null/空白）→ 相对路径基准回落"
                    + "「无会话兜底」（动态 guard = 进程 user.dir={}；固定 guard = 构造时冻结 workdir）；"
                    + "绝对路径与 ~ 家目录不依赖基准、不受影响；需要会话 cwd 的调用方应传 "
                    + "ToolUseContext.sessionId()（即 guard.resolve(ctx.sessionId(), path)）· 本告警只提示一次",
                    System.getProperty("user.dir"));
            }
            return currentWorkdir();
        }
        Path wd = null;
        try {
            wd = sessionWorkdirResolver.apply(sessionId);
        } catch (Exception e) {
            log.warn("[PathGuard] sessionWorkdirResolver 解析会话 cwd 异常 → 回落无会话兜底: sessionId={} cause={}",
                sessionId, e.toString());
        }
        if (wd == null) {
            log.warn("[PathGuard] sessionWorkdirResolver 返回 null → 回落无会话兜底: sessionId={}；"
                + "默认解析器 CwdResolution.getCwd 恒非 null，非默认解析器须返回会话 cwd", sessionId);
            return currentWorkdir();
        }
        return wd.toAbsolutePath().normalize();
    }

    /**
     * 取当前 workdir 快照 · supplier 返回 null 时兜底统一入口按「无会话」解析
     * （{@link CwdResolution#getCwd(String)}{@code (null)} → 仅 override / 进程 user.dir 层；
     * 对齐 CC getCwd catch 兜底，不抛；不直读 user.dir · INV-6）。
     *
     * <p><b>本方法恒为「无会话兜底」</b>（{@code Supplier<Path>} 不承载 sessionId）；此分支在
     * supplier 返回 null 时命中（生产 bean 的 supplier 恒非 null）。需要会话 cwd 的调用方走
     * {@link #sessionWorkdir(String)}（{@link #workdir(String)} / {@link #resolve(String, String)}）。
     */
    private Path currentWorkdir() {
        Path wd = workdirSupplier.get();
        if (wd == null) {
            // supplier 返回 null → 统一入口按「无会话」解析（CwdResolution 内部 user.dir 兜底，
            // 符合 INV-4；本类不直读 user.dir）
            log.warn("[PathGuard] workdirSupplier 返回 null（无会话 cwd 来源）→ workdir 回落进程 user.dir={}；"
                + "如需会话 cwd 须由注入方在 supplier 内显式解析（CwdResolution.getCwd(sessionId)）",
                System.getProperty("user.dir"));
            wd = Path.of(CwdResolution.getCwd(null));
        }
        return wd.toAbsolutePath().normalize();
    }

    /**
     * [FIX-A backfill-observable] 纯路径展开 · 镜像 CC {@code utils/path.ts:32-85 expandPath}。
     *
     * <p>展开 ~ 家目录 / 相对路径 / 绝对路径为归一化绝对路径。与 {@link #resolve(String)} 的关系：
     * 二者<b>同为纯展开语义</b>（2026-09-03 起 {@code resolve} 的软链接解析 + 逃逸检查已删，
     * 见 {@link #resolve(String, String)}）；区别只在本方法由调用方<b>显式传 baseDir</b>、不做归一化
     * 兜底，供 {@code backfillObservableInput} 阶段给 hook/canUseTool 看绝对化路径，
     * 防 {@code ~}/相对路径绕过 hook allowlist（CC FileEditTool.ts:116-120 注释语义
     * "expand so hook allowlists can't be bypassed via ~ or relative paths"）。
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
     * 需求，登记为已知简化。
     *
     * @param raw     原始路径（可 null/空白；null 字节触发异常，镜像 CC path.ts:48-51）
     * @param baseDir 相对路径解析基座（<b>调用方须传会话感知基准</b>：
     *                {@code guard.workdir(ctx.sessionId()).toString()}，等价 CC {@code getCwd()} 语义；
     *                <b>无会话</b>调用方传无参 workdir（无会话兜底形态），见
     *                {@link com.nexusai.infra.config.ToolConfig#workspacePathGuard()} 的「仅兜底」说明）
     * @return 展开后的绝对路径（平台原生格式、归一化）
     * @throws IllegalArgumentException {@code raw} 含 null 字节（镜像 CC path.ts:48-51）
     */
    public static String expandPath(String raw, String baseDir) {
        // baseDir 缺省走统一入口按「无会话」解析（对齐 CC expandPath baseDir ?? getCwd()，
        // 不直读 user.dir · INV-6）。生产调用方传会话感知基准（guard.workdir(sessionId).toString()），
        // 此分支仅兜底；本方法为静态工具无会话入参 → 缺失 baseDir 时只能按「无会话」解析
        // （进程 user.dir）；需要会话 baseDir 的调用方必须显式传入。
        if (baseDir == null && EXPAND_BASE_DIR_WARNED.compareAndSet(false, true)) {
            log.warn("[PathGuard] expandPath 无 baseDir 入参 → 相对路径基准回落进程 user.dir={}；"
                + "如需会话 cwd 须由调用方显式传入 baseDir（guard.workdir(ctx.sessionId()).toString()）",
                System.getProperty("user.dir"));
        }
        String actualBaseDir = baseDir != null ? baseDir : CwdResolution.getCwd(null);
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
