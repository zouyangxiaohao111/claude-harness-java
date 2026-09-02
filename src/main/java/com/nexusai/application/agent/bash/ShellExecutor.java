package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.tasks.ProcessTreeKiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Bash 执行器 · 对齐 CC {@code utils/Shell.ts exec()}（Shell.ts:181-442）的 spawn 核心职责。
 *
 * <p>收敛 CC {@code exec()} 的 spawn 核心（探测 shell + 命令包装 + env 注入 + cwd 跟踪读回）为
 * 共享组件，消除 BashTool / LocalBashTaskRunner 的 ProcessBuilder+env 重复（ShellExecutor 抽取
 * 方案，02-implementation-plan.md T1）。
 *
 * <p><b>CC 真源</b>（grep -n 自验，不信注释）：
 * <ul>
 *   <li>{@code exec()}（Shell.ts:181-442）：{@code spawn(binShell, getSpawnArgs(command), {env, cwd})}
 *       （:316-337），跑完读 cwd 文件 → {@code posixPathToWindowsPath} → {@code setCwd}（:385-421）；</li>
 *   <li>{@code bashProvider.buildExecCommand}（bashProvider.ts:184-187 + :127-154）：命令串 =
 *       {@code [disable extglob] && eval <quoted> && pwd -P >| <cwdFile>}；quoted 经
 *       {@code rewriteWindowsNullRedirect}（shellQuoting.ts:126-134，2>nul→2>/dev/null）→
 *       {@code shouldAddStdinRedirect}（:93-107）→ {@code quoteShellCommand}（:46-79，
 *       heredoc/多行单引号字面量、普通命令 shell-quote）；管道 + stdin 走
 *       {@code rearrangePipeCommand}（bashPipeCommand.ts，无法 parse 时 fallback
 *       {@code quoteWithEvalStdinRedirect}）；</li>
 *   <li>env 注入（Shell.ts:316-328）：{@code SHELL=binShell / GIT_EDITOR='true' / CLAUDECODE='1'} ——
 *       <b>CC 真源键为 {@code CLAUDECODE}（无下划线，Shell.ts:321）</b>，非 {@code CLAUDE_CODE}
 *       （Java 旧内联实现误用带下划线键，此处对齐 CC 修正）。</li>
 * </ul>
 */
public final class ShellExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShellExecutor.class);

    /** Windows 平台判定 · 等价 CC {@code getPlatform() === 'windows'}（platform.ts:18）。 */
    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    /** 信号退出码常量 · CC original: {@code SIGKILL = 137 / SIGTERM = 143}（ShellCommand.ts:49-50）。 */
    static final int EXIT_SIGKILL = 137;

    /** 信号退出码常量 · CC original: {@code SIGKILL = 137 / SIGTERM = 143}（ShellCommand.ts:49-50）。 */
    static final int EXIT_SIGTERM = 143;

    /** 信号杀进程时的退出码 · CC original: {@code #exitHandler signal==SIGTERM ? 144 : 1}（ShellCommand.ts:195-203）。 */
    static final int EXIT_SIGNALED = 144;

    /**
     * 会话级快照路径缓存 · 对齐 CC {@code snapshotPromise}（bashProvider.ts:63-70）+ 快照消失回退
     * {@code lastSnapshotFilePath}（:85-103）+ {@code getShellConfig} memoize（Shell.ts:146）。
     * 空 Optional = 生成失败已缓存（CC resolve(undefined) 后不再重试），后续命令回退 {@code -l} login shell。
     */
    private static volatile Optional<Path> snapshotCache;

    /** 快照缓存锁 · 双检锁（对齐 CC 单例 promise 语义）。 */
    private static final Object SNAPSHOT_LOCK = new Object();

    private ShellExecutor() {
    }

    /**
     * E1 探测 bash/zsh · 委托 {@link ShellResolver#resolveShell()}（会话内 memoize）。
     *
     * @return 可执行 shell 绝对/探测路径
     * @throws IllegalStateException 无可用 Posix shell（CC 同款信息，Shell.ts:128-134）
     */
    public static String resolveShell() {
        return ShellResolver.resolveShell();
    }

    /**
     * E2 命令包装 · 严格对齐 CC {@code bashProvider.buildExecCommand}（bashProvider.ts:184-187
     * + shellQuoting.ts 全链）：
     * <pre>
     * [disable extglob] && eval <quoted> && pwd -P >| '<track>'
     * </pre>
     *
     * <p>链构造（逐项对齐 CC）：{@code rewriteWindowsNullRedirect}（2>nul→2>/dev/null）→
     * {@code shouldAddStdinRedirect}（无 heredoc 且无既有 stdin redirect）→
     * {@code quoteShellCommand}（heredoc/多行 → 单引号字面量 + {@code '"'"'} escape；
     * 普通命令 → shell-quote 等价）→ 管道 + stdin 走 CC {@code quoteWithEvalStdinRedirect}
     * fallback（bashPipeCommand.ts 的 shell-quote parse 重建为 BashParser 语义差异高风险，
     * 登记后续严格实现）→ disable extglob 前缀（bash→shopt / zsh→setopt，bashProvider.ts:17-35）
     * → {@code eval <quoted> && pwd -P >| <track>}（{@code >|} clobber redirect，CC :186）。
     *
     * <p>trackFile 在 Windows 走 Git Bash 时先转 POSIX（{@code /c/...}，bashProvider.ts:118-121）；
     * {@code pwd -P} 输出 POSIX 物理路径，读回时经 {@link #readCwdTracked} 转 native（Shell.ts:400-402）。
     *
     * @param command      原始 bash 命令
     * @param cwdTrackFile 承载 {@code pwd -P} 输出的临时文件（非 null）
     * @return 包装后的 bash 命令串
     */
    public static String wrapForCwdTracking(String command, Path cwdTrackFile) {
        // 1. rewriteWindowsNullRedirect · 对齐 shellQuoting.ts:126-134
        String normalized = rewriteWindowsNullRedirect(command);
        // 2. shouldAddStdinRedirect · 对齐 shellQuoting.ts:93-107
        boolean addStdin = shouldAddStdinRedirect(normalized);
        // 3. quoteShellCommand · 对齐 shellQuoting.ts:46-79
        String quoted = quoteShellCommand(normalized, addStdin);
        // 4. 管道 + stdin：CC rearrangePipeCommand（bashPipeCommand.ts）——
        //    shell-quote parse 重建 `cmd1 < /dev/null | cmd2`（stdin 落到第一命令，
        //    防 cat 等继承父 stdin 挂起）；无法安全 parse 时内部 fallback quoteWithEvalStdinRedirect。
        if (normalized.contains("|") && addStdin) {
            quoted = ShellQuoteParser.rearrangePipeCommand(normalized);
        }
        // 5. disable extglob 前缀 · 对齐 bashProvider.ts:17-35 + :159-163
        String disableExtglob = getDisableExtglobCommand(resolveShell());
        // 6. trackFile Windows → POSIX · 对齐 bashProvider.ts:118-121 shellTmpdir
        String trackPath = cwdTrackFile.toString();
        if (IS_WINDOWS) {
            trackPath = MemoryFileDetection.windowsPathToPosixPath(trackPath);
        }
        // 7. 组装 · 对齐 bashProvider.ts:159-168（eval + pwd -P >| quote(cwdFilePath)）
        StringBuilder sb = new StringBuilder();
        if (disableExtglob != null) {
            sb.append(disableExtglob).append(" && ");
        }
        sb.append("eval ").append(quoted)
            .append(" && pwd -P >| ").append(ShellQuoteParser.quote(List.of(trackPath)));
        String result = sb.toString();
        if (log.isDebugEnabled()) {
            log.debug("ShellExecutor.wrapForCwdTracking: 生成命令串 length={}", result.length());
        }
        return result;
    }

    /**
     * E3 env 注入 · 对齐 CC {@code exec()} spawn env（Shell.ts:316-328）。
     *
     * <p>{@code SHELL=binShell}（子进程 $SHELL 指向实际执行 shell）/ {@code GIT_EDITOR='true'}
     * （防 git 打开编辑器卡住）/ {@code CLAUDECODE='1'}（标识 Claude Code 环境）。
     *
     * <p><b>键名对齐 CC 真源</b>：Shell.ts:321 实际为 {@code CLAUDECODE}（无下划线）。Java 旧内联
     * 实现（BashTool/LocalBashTaskRunner）误用 {@code CLAUDE_CODE}（带下划线），此处按
     * 「CC 唯一事实来源」规则修正。{@code CLAUDE_CODE_SESSION_ID} 为 CC ant user 专属
     * （Shell.ts:323-327），Java 外部构建不注入。ProcessBuilder 默认继承父进程 env，本方法只显式
     * 覆盖这三项。
     *
     * @param pb        目标 ProcessBuilder
     * @param bashPath  解析出的 shell 路径（CC {@code binShell}）
     */
    public static void applyExecEnv(ProcessBuilder pb, String bashPath) {
        pb.environment().put("SHELL", bashPath);
        pb.environment().put("GIT_EDITOR", "true");
        pb.environment().put("CLAUDECODE", "1");
    }

    /**
     * G5-10: 沙箱环境变量注入 · 对齐 CC bashProvider.ts:235-247 沙箱 env：
     * {@code TMPDIR=<沙箱 tmpdir>}（POSIX 路径）+ {@code CLAUDE_CODE_TMPDIR}（同源）。
     *
     * <p>沙箱 tmpdir = {@code $CLAUDE_CODE_TMPDIR || /tmp} + claude 临时目录名（对齐 Shell.ts:217-219
     * {@code sandboxTmpDir = posixJoin(process.env.CLAUDE_CODE_TMPDIR || '/tmp', getClaudeTempDirName())}）。
     *
     * <p><b>[G5-10 返工] 目录创建</b>：注入前先在<b>宿主</b>创建该目录（0o700）——对齐 CC Shell.ts:267-272
     * {@code await fs.mkdir(sandboxTmpDir, { mode: 0o700 })}。旧实现只注入 env 不建目录，沙箱启用后
     * 命令写 {@code $TMPDIR}（如临时文件）会 ENOENT 失败（潜在功能回归）。目录创建失败仅 debug 日志
     * （对齐 CC :270-272 logForDebugging 不阻断；bwrap {@code --ro-bind / / + --bind /tmp /tmp} 下
     * 宿主目录即沙箱内可见目录）。创建模式：POSIX 0o700（rwx------，防多用户读 temp）；Windows
     * 无 POSIX 权限位，用默认权限。
     * TMPPREFIX=zsh heredoc（bashProvider.ts:247）Java bash 提供器不适用（zsh 专属），登记已知差异。
     *
     * @param pb 目标 ProcessBuilder（已注入 SHELL/GIT_EDITOR/CLAUDECODE）
     */
    public static void applySandboxExecEnv(ProcessBuilder pb) {
        String claudeCodeTmpdir = System.getenv("CLAUDE_CODE_TMPDIR");
        String base = (claudeCodeTmpdir != null && !claudeCodeTmpdir.isBlank())
            ? claudeCodeTmpdir : "/tmp";
        // getClaudeTempDirName 等价：claude-{uid}（CC claudeTempDirName.ts / shell.ts getClaudeTempDirName）
        String tmpName = "claude-" + (System.getProperty("user.name", "unknown"));
        String sandboxTmpDir = base.endsWith("/") ? base + tmpName : base + "/" + tmpName;
        // G5-10 返工：宿主侧 mkdir 0o700（CC Shell.ts:267-272）——先建目录再注入 env，防写 TMPDIR ENOENT
        createSandboxTmpDir(sandboxTmpDir);
        pb.environment().put("TMPDIR", sandboxTmpDir);
        // P2-9: CLAUDE_CODE_TMPDIR 也指向沙箱 tmpdir（非 base）· 对齐 CC bashProvider.ts:241-243
        //   （env.TMPDIR = posixTmpDir; env.CLAUDE_CODE_TMPDIR = posixTmpDir —— 同一沙箱 tmpdir）。
        //   旧实现注入 base（/tmp 或宿主 CLAUDE_CODE_TMPDIR）→ 沙箱命令读 CLAUDE_CODE_TMPDIR 落到
        //   宿主 /tmp 而非沙箱可写目录，与 CC 语义漂移（bwrap 下 /tmp 已 bind，但显式指向更精确）。
        pb.environment().put("CLAUDE_CODE_TMPDIR", sandboxTmpDir);
        if (log.isDebugEnabled()) {
            log.debug("ShellExecutor.applySandboxExecEnv: 注入 TMPDIR={} CLAUDE_CODE_TMPDIR={}（G5-10, bashProvider.ts:241-243 同源）",
                sandboxTmpDir, sandboxTmpDir);
        }
    }

    /**
     * G5-10: 宿主侧创建沙箱 TMPDIR（0o700）· 对齐 CC Shell.ts:267-272。
     *
     * <p>POSIX 设置 {@code rwx------}（0o700，CC {@code mode: 0o700}）；创建失败仅 debug 日志不抛
     * （对齐 CC :270-272 logForDebugging 兜底，沙箱化命令随后 ENOENT fail-loud 也不阻塞宿主）。
     * 返回沙箱 tmpdir 路径（供 bwrap 包装/日志复用）。
     *
     * @param sandboxTmpDir 沙箱 tmpdir 绝对路径（POSIX）
     */
    static String createSandboxTmpDir(String sandboxTmpDir) {
        try {
            Path dir = Path.of(sandboxTmpDir);
            Files.createDirectories(dir);
            if (!IS_WINDOWS) {
                try {
                    Files.setPosixFilePermissions(dir, java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
                } catch (UnsupportedOperationException e) {
                    // 非 POSIX 文件系统（FAT/NTFS 挂载）→ 跳过权限位（0o700 语义不可表达）
                    if (log.isDebugEnabled()) {
                        log.debug("ShellExecutor.createSandboxTmpDir: 文件系统不支持 POSIX 权限位，跳过 0o700: {}",
                            sandboxTmpDir);
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("ShellExecutor.createSandboxTmpDir: 沙箱 TMPDIR 就绪 {}（G5-10, Shell.ts:267-272 mkdir 0o700）",
                    sandboxTmpDir);
            }
        } catch (Exception e) {
            // 对齐 CC :270-272 logForDebugging 不阻断
            if (log.isDebugEnabled()) {
                log.debug("ShellExecutor.createSandboxTmpDir: 创建失败（沙箱化命令后续 ENOENT fail-loud）: {} {}",
                    sandboxTmpDir, e.toString());
            }
        }
        return sandboxTmpDir;
    }

    /**
     * G5-10: bwrap 沙箱命令包装 · 对齐 CC Shell.ts:259-266 {@code SandboxManager.wrapWithSandbox(commandString, sandboxBinShell)}
     * （srt SDK 运行时，Java 端以 bubblewrap 命令行等价实现）。
     *
     * <p><b>[G5-10 返工] 真正接线</b>：旧实现仅注入 TMPDIR env，命令串未沙箱化 —— 与 checkPermissions
     * 沙箱 auto-allow（"沙箱内执行"即 auto-allow）形成 fail-open 安全洞。本方法把命令串包成
     * {@code bwrap ... <binShell> -c <command>}：
     * <ul>
     *   <li>{@code --ro-bind / /}：根只读（对齐 CC srt 默认 denyWrite 根）；</li>
     *   <li>{@code --bind /tmp /tmp}：临时目录可写（沙箱命令写 {@code $TMPDIR} 需要，CC 文件模式同款）；</li>
     *   <li>{@code --bind <cwd> <cwd> --chdir <cwd>}：工作目录可写（命令在 cwd 内产出文件，防
     *       {@code git add} 等只读根下失败）；</li>
     *   <li>{@code --dev /dev --proc /proc}：设备与 proc（进程/管道依赖）；</li>
     *   <li>{@code --unshare-all --share-net}：隔离命名空间但保留网络（CC 沙箱允许网络访问，仅
     *       allowManagedDomainsOnly 策略下拦截）；</li>
     *   <li>{@code --die-with-parent}：父进程退出时沙箱进程随之退出（防孤儿）。</li>
     * </ul>
     *
     * <p><b>依赖门</b>：bwrap 二进制缺失（Linux/WSL 依赖门 checkDependenciesEnv 已判 bwrap+socat）
     * → 返回原命令串 + log.warn（不假沙箱化，fail-loud —— 宁可命令不沙箱也不静默标沙箱）。
     * Windows/macOS（sandbox-exec 需 profile 文件，Java 无 srt 适配）→ 返回原命令串 + log.warn。
     * 各 arg 经 {@link ShellQuoteParser#quote} 单引号化（外层仍是 {@code <bash> -c <wrapped>}）。
     *
     * @param command  已 wrapForCwdTracking/wrapForExec 包装的命令串
     * @param binShell 沙箱内 shell 路径（CC sandboxBinShell）
     * @param cwd      工作目录（非 null 时 bind+chdir；null/blank 跳过）
     * @return bwrap 包装命令串；无法沙箱化 → 原命令串（log.warn 登记）
     */
    public static String wrapWithSandbox(String command, String binShell, String cwd) {
        if (command == null || command.isBlank()) {
            return command;
        }
        if (IS_WINDOWS) {
            // Windows 无 bwrap（srt 亦不支持），macOS sandbox-exec 需 profile 文件 —— Java 无 srt 适配
            if (log.isDebugEnabled()) {
                log.debug("ShellExecutor.wrapWithSandbox: 当前平台无 bwrap 等价沙箱（Windows）→ 原命令串（G5-10 遗留，srt 适配待专项）");
            }
            return command;
        }
        if (!bwrapExists()) {
            log.warn("ShellExecutor.wrapWithSandbox: bwrap 不可用（依赖门 checkDependenciesEnv 应已判 bwrap+socat）"
                + "→ 不假沙箱化，返回原命令串（fail-loud）");
            return command;
        }
        List<String> args = new ArrayList<>();
        args.add("bwrap");
        args.add("--die-with-parent");
        args.add("--ro-bind"); args.add("/"); args.add("/");
        args.add("--dev"); args.add("/dev");
        args.add("--proc"); args.add("/proc");
        args.add("--bind"); args.add("/tmp"); args.add("/tmp");
        if (cwd != null && !cwd.isBlank()) {
            args.add("--bind"); args.add(cwd); args.add(cwd);
            args.add("--chdir"); args.add(cwd);
        }
        args.add("--unshare-all");
        args.add("--share-net");
        args.add(binShell);
        args.add("-c");
        args.add(command);
        String wrapped = ShellQuoteParser.quote(args);
        if (log.isDebugEnabled()) {
            log.debug("ShellExecutor.wrapWithSandbox: 命令串已 bwrap 包装 length={}（G5-10, Shell.ts:259-266 wrapWithSandbox）",
                wrapped.length());
        }
        return wrapped;
    }

    /** G5-10: bwrap 二进制存在性探针（Windows 恒 false）。 */
    private static boolean bwrapExists() {
        if (IS_WINDOWS) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("which", "bwrap").redirectErrorStream(true).start();
            try (InputStream is = p.getInputStream()) {
                is.readAllBytes();
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * E5 cwd 读回 · 对齐 CC {@code exec()} 跑完读 cwd 文件（Shell.ts:397-402）：
     * {@code readFileSync(nativeCwdFilePath, 'utf8').trim()} → Windows 上
     * {@code posixPathToWindowsPath(newCwd)}。
     *
     * @param cwdTrackFile 承载 {@code pwd -P} 输出的临时文件
     * @param windows      平台判定（Windows Git Bash 输出 POSIX 路径须转 native）
     * @return 规范化 cwd（trim；Windows 已转 native 路径）
     * @throws IOException 文件不存在/读取失败（调用方按 CC :411-413 catch 兜底，命令可能 pwd 前失败）
     */
    public static String readCwdTracked(Path cwdTrackFile, boolean windows) throws IOException {
        String newCwd = Files.readString(cwdTrackFile, StandardCharsets.UTF_8).trim();
        if (windows) {
            newCwd = MemoryFileDetection.posixPathToWindowsPath(newCwd);
        }
        return newCwd;
    }

    /**
     * 统一构造 bash -c ProcessBuilder · 对齐 CC {@code exec()} spawn 核心
     * （Shell.ts:316 {@code spawn(binShell, getSpawnArgs(command), {env, cwd})}）：
     * {@code resolveShell + applyExecEnv + directory}。BashTool / LocalBashTaskRunner 共用，
     * 消除 ProcessBuilder+env 重复。
     *
     * @param command bash 命令（BashTool 传 wrapForCwdTracking 包装后的 wrappedCommand；后台任务
     *                传原始 command——后台不跟踪 cwd）
     * @param cwd     工作目录（会话 cwd；null/blank → 不设置 directory）
     * @return 配置完成的 ProcessBuilder（[bashPath, "-c", command]，env 已注入，directory 已设）
     * @throws IllegalStateException 无可用 Posix shell（CC 同款显式错误，fail-loud）
     */
    public static ProcessBuilder bash(String command, String cwd) {
        String bashPath = resolveShell();
        ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", command);
        applyExecEnv(pb, bashPath);
        if (cwd != null && !cwd.isBlank()) {
            pb.directory(new File(cwd));
        }
        return pb;
    }

    // ════════════════════════════════════════════════════════════════════
    // G2-1 快照 / G2-2 合并流 / G2-3 超时执行层 / G2-4 spawn 参数 对齐
    // ════════════════════════════════════════════════════════════════════

    /**
     * 会话级快照获取（懒生成一次）· 对齐 CC {@code snapshotPromise}（bashProvider.ts:63-70）：
     * {@code options?.skipSnapshot ? Promise.resolve(undefined) : createAndSaveSnapshot(shellPath)}。
     *
     * <p>结果缓存于 {@link #snapshotCache}（含失败的空结果，对齐 CC resolve(undefined) 后不再重试）。
     * 生成失败不阻塞——调用方（G5）随后对无快照路径加 {@code -l} login shell。
     *
     * @return 快照文件路径；生成失败/未生成 → {@link Optional#empty()}
     */
    public static Optional<Path> getOrCreateSnapshot() {
        Optional<Path> c = snapshotCache;
        if (c != null) {
            return c;
        }
        synchronized (SNAPSHOT_LOCK) {
            if (snapshotCache == null) {
                String shell = resolveShell();
                snapshotCache = ShellSnapshot.generate(shell);
                if (snapshotCache.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("ShellExecutor.getOrCreateSnapshot: 快照生成失败/不可用，后续命令回退 login shell（-l）");
                    }
                } else if (log.isDebugEnabled()) {
                    log.debug("ShellExecutor.getOrCreateSnapshot: 快照就绪 path={}", snapshotCache.get());
                }
            }
            return snapshotCache;
        }
    }

    /**
     * 快照有效性复验 · 对齐 CC buildExecCommand 内 {@code access(snapshotFilePath)}（bashProvider.ts:93-103）：
     * 快照中途消失（tmpdir 清理）→ 返回 false，调用方应清空快照引用并加 {@code -l} login shell。
     *
     * @param snapshotPath 待校验快照路径（null → false）
     * @return true = 快照文件仍存在
     */
    public static boolean isSnapshotValid(Path snapshotPath) {
        if (snapshotPath == null) {
            return false;
        }
        boolean exists = Files.isRegularFile(snapshotPath);
        if (!exists && log.isDebugEnabled()) {
            log.debug("ShellExecutor.isSnapshotValid: 快照文件已消失，回退 login shell（对齐 bashProvider.ts:93-103 access()）: {}",
                snapshotPath);
        }
        return exists;
    }

    /**
     * 带快照 source 前缀的命令包装 · 对齐 CC buildExecCommand 命令链（bashProvider.ts:156-187）：
     * {@code source <posix快照> 2>/dev/null || true && [disable extglob] && eval <quoted> && pwd -P >| <track>}。
     *
     * <p>快照为空 → 直接委托 {@link #wrapForCwdTracking}（无 source 前缀）。快照路径在 Windows
     * 先转 POSIX（/c/...，bashProvider.ts:162-166）；{@code || true} 兜底 source 与 spawn 之间
     * 的竞态（bashProvider.ts:158-160）。
     *
     * @param command      原始 bash 命令
     * @param cwdTrackFile 承载 {@code pwd -P} 输出的临时文件
     * @param snapshotPath 快照文件路径（null → 不 source）
     * @return 包装后的 bash 命令串
     */
    public static String wrapForExec(String command, Path cwdTrackFile, Path snapshotPath) {
        if (snapshotPath == null) {
            return wrapForCwdTracking(command, cwdTrackFile);
        }
        // Windows → POSIX 快照路径 · 对齐 bashProvider.ts:162-166 finalPath
        String finalPath = IS_WINDOWS
            ? MemoryFileDetection.windowsPathToPosixPath(snapshotPath.toString())
            : snapshotPath.toString();
        String sourceCmd = "source " + ShellQuoteParser.quote(List.of(finalPath)) + " 2>/dev/null || true";
        String result = sourceCmd + " && " + wrapForCwdTracking(command, cwdTrackFile);
        if (log.isDebugEnabled()) {
            log.debug("ShellExecutor.wrapForExec: 快照前缀 source 已注入 snapshot={}", snapshotPath);
        }
        return result;
    }

    /**
     * 后台任务命令包装（仅 source 快照、不含 cwd 回写）· 对齐 CC buildExecCommand 的
     * source 前缀（CC original: {@code source <posix快照> 2>/dev/null || true},
     * bashProvider.ts:161-167）。
     *
     * <p><b>与 {@link #wrapForExec} 的差别</b>：后台任务不需要 cwd 回写（对齐 CC 后台语义
     * 不更新 cwd），故<b>不含</b> {@code pwd -P >| <track>} 段；命令也不做 eval/quote 隔离
     * （保留 LocalBashTaskRunner 既有 {@code bash -c <原始命令>} 语义，避免引入引号化差异）。
     * 快照路径 Windows 先转 POSIX（/c/...，bashProvider.ts:162-166 finalPath）；
     * {@code || true} 兜底 source 与 spawn 之间的竞态（bashProvider.ts:158-160）。
     *
     * <p>快照为空/命令空白 → 返回原始命令（不 source，spawn 参数由调用方按 getSpawnArgs
     * 回退 {@code -l} login shell，bashProvider.ts:200-206）。
     *
     * @param command      原始 bash 命令
     * @param snapshotPath 快照文件路径（null → 不 source）
     * @return {@code source <快照> 2>/dev/null || true && <command>}；快照为空/命令空白 → 原命令
     */
    public static String wrapForBackground(String command, Path snapshotPath) {
        if (command == null || command.isBlank() || snapshotPath == null) {
            return command;
        }
        // Windows → POSIX 快照路径 · 对齐 bashProvider.ts:162-166 finalPath
        String finalPath = IS_WINDOWS
            ? MemoryFileDetection.windowsPathToPosixPath(snapshotPath.toString())
            : snapshotPath.toString();
        String sourceCmd = "source " + ShellQuoteParser.quote(List.of(finalPath)) + " 2>/dev/null || true";
        String result = sourceCmd + " && " + command;
        if (log.isDebugEnabled()) {
            log.debug("ShellExecutor.wrapForBackground: 后台命令 source 前缀已注入 snapshot={}", snapshotPath);
        }
        return result;
    }

    /**
     * 统一构造 bash -c ProcessBuilder · 对齐 CC {@code getSpawnArgs}（bashProvider.ts:200-206）：
     * {@code ['-c', ...(skipLoginShell?[]:['-l']), commandString]}。快照存在 → 跳过 {@code -l}
     * （快照已携带用户环境）；无快照 → 加 {@code -l} login shell。
     *
     * <p>两参 {@link #bash(String, String)} 为既有兼容入口（后台任务等未接快照的调用方，
     * 无 {@code -l} 保持旧行为）；本三参重载是 CC 对齐路径，G5 接线 BashTool 时使用。
     *
     * @param command      bash 命令（BashTool 传 wrapForExec/wrapForCwdTracking 包装后的 wrappedCommand）
     * @param cwd          工作目录（会话 cwd；null/blank → 不设置 directory）
     * @param snapshotPath 快照文件路径（null → spawn 参数加 {@code -l}）
     * @return 配置完成的 ProcessBuilder（env 已注入，directory 已设）
     */
    public static ProcessBuilder bash(String command, String cwd, Path snapshotPath) {
        String bashPath = resolveShell();
        List<String> args = new ArrayList<>();
        args.add(bashPath);
        args.add("-c");
        boolean skipLoginShell = snapshotPath != null;
        if (!skipLoginShell) {
            args.add("-l");
        }
        args.add(command);
        ProcessBuilder pb = new ProcessBuilder(args);
        applyExecEnv(pb, bashPath);
        if (cwd != null && !cwd.isBlank()) {
            pb.directory(new File(cwd));
        }
        return pb;
    }

    /**
     * 合并 stdout/stderr 单流的 bash ProcessBuilder · 对齐 CC 文件模式
     * （Shell.ts:289-313）：stdio[1]/stdio[2] 两个 fd 指向同一文件（O_APPEND 原子交错）→
     * stdout/stderr 按时间天然合并，result.stderr 恒空（ShellCommand.ts:301）。
     * Java 等价 {@code redirectErrorStream(true)}（合并为单流，行序为时间交错序）。
     *
     * @param command bash 命令
     * @param cwd     工作目录（null/blank → 不设置 directory）
     * @return 配置完成的 ProcessBuilder（redirectErrorStream(true)）
     */
    public static ProcessBuilder bashMerged(String command, String cwd) {
        ProcessBuilder pb = bash(command, cwd);
        pb.redirectErrorStream(true);
        return pb;
    }

    /**
     * 超时转后台执行层 · 对齐 CC {@code wrapSpawn}（ShellCommand.ts:387-403）+ {@code #handleTimeout}
     * （:135-141）+ {@code #doKill}（:337-343）+ {@code #exitHandler}（:195-203）。
     *
     * <p>语义（逐项对齐 CC）：
     * <ul>
     *   <li>超时定时器在构造时启动（对齐 CC 构造 {@code setTimeout} :275-279）；</li>
     *   <li>到点 {@code #handleTimeout}（:135-141）：{@code shouldAutoBackground} 且已注册
     *       {@code onTimeoutBackgroundFn} → 调回调（把 {@code background(taskId)} 函数交给调用方，
     *       转后台，进程不杀）；否则 {@code #doKill(SIGTERM)} → 退出码 143（SIGTERM=143 :50）
     *       + stderr 前缀 {@code "Command timed out after <formatDuration(timeout)>"}
     *       （{@code formatDuration} :34-49 / :323-328）；</li>
     *   <li>退出监听（:263-289）+ kill 后退出码映射（143/137/144, :195-203）；</li>
     *   <li>输出合并单流捕获（对齐 CC 文件模式 stdout/stderr 同 fd :289-313），经
     *       {@code onProgress} 逐块回传。</li>
     * </ul>
     *
     * <p><b>BashTool 接线在 G5</b>：本方法提供执行层接口，G5 负责转后台回调接线（TaskId）、
     * ShellError 构造、超时前缀落输出。
     *
     * @param command               bash 命令
     * @param cwd                   工作目录（null/blank → 不设置 directory）
     * @param timeoutMs             超时毫秒（&gt;0）
     * @param shouldAutoBackground  超时时是否允许转后台（对齐 CC {@code shouldAutoBackground}）
     * @param onTimeoutBackgroundFn 超时回调（收到 {@code background(taskId)} 函数）；null = 超时直接 kill
     * @param onProgress            输出块回调（可空；合并流逐块回传，供 G5 进度/落盘）
     * @return {@link TimedShellCommand}（含 process / exitFuture / background / killTree）
     */
    public static TimedShellCommand executeTimed(String command, String cwd, long timeoutMs,
                                                 boolean shouldAutoBackground,
                                                 Consumer<Function<String, Boolean>> onTimeoutBackgroundFn,
                                                 Consumer<String> onProgress) {
        ProcessBuilder pb = bashMerged(command, cwd);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // spawn 失败 · 对齐 CC Shell.ts:437-440 createAbortedCommand({code:126, stderr:errorMessage})
            if (log.isDebugEnabled()) {
                log.debug("ShellExecutor.executeTimed: spawn 失败 → code=126: {}", e.toString());
            }
            TimedShellCommand failed = new TimedShellCommand(null, onProgress);
            failed.exitFuture.complete(126);
            return failed;
        }
        TimedShellCommand timed = new TimedShellCommand(process, onProgress);
        if (log.isDebugEnabled()) {
            log.debug("ShellExecutor.executeTimed: 启动 pid={} timeoutMs={} shouldAutoBackground={}",
                process.pid(), timeoutMs, shouldAutoBackground);
        }

        // 合并流读取（bashMerged 已 redirectErrorStream(true)，单流=时间交错序）
        Thread reader = new Thread(() -> {
            try (InputStream in = timed.process().getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    timed.appendOutput(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("ShellExecutor.executeTimed: 读取合并输出失败: {}", e.toString());
                }
            }
        }, "bash-timed-reader");
        reader.setDaemon(true);
        reader.start();

        // 退出监听 · 对齐 CC #createResultPromise 'exit' 监听（ShellCommand.ts:263-289）
        Thread exitWatcher = new Thread(() -> {
            try {
                timed.process().waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            timed.resolveExit();
        }, "bash-timed-exit");
        exitWatcher.setDaemon(true);
        exitWatcher.start();

        // 超时定时器 · 对齐 CC 构造 setTimeout（:275-279）+ #handleTimeout（:135-141）
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (timed.exitFuture().isDone() || timed.isBackgrounded()) {
                return;
            }
            if (shouldAutoBackground && onTimeoutBackgroundFn != null) {
                if (log.isDebugEnabled()) {
                    log.debug("ShellExecutor.executeTimed: 超时转后台（shouldAutoBackground，进程保留）pid={}",
                        timed.process().pid());
                }
                // 对齐 CC #handleTimeout :136-137：把 background(taskId) 交给回调，进程不杀
                onTimeoutBackgroundFn.accept(taskId -> timed.background(taskId));
                return;
            }
            String message = "Command timed out after " + formatDuration(timeoutMs);
            if (log.isDebugEnabled()) {
                log.debug("ShellExecutor.executeTimed: 超时 kill pid={} {}", timed.process().pid(), message);
            }
            timed.markTimedOut(message);
            // G2-3: 超时消息落到输出流（对齐 CC #handleExit prependStderr 前缀，
            //   ShellCommand.ts:323-328 `Command timed out after ${formatDuration(timeout)}`）——
            //   合并单流模型下 append 到输出缓冲 + onProgress 实时回传（BashTool 侧读取 timeoutMessage()
            //   结构化字段构建 stderr 前缀；此处保证消息已进入输出流）。
            timed.appendOutput(message + "\n");
            timed.killTree();
            // 对齐 CC #doKill resolveExitCode(code ?? SIGKILL)：立即以 143 解析（幂等）
            timed.resolveExit();
        }, "bash-timed-timeout");
        timer.setDaemon(true);
        timer.start();

        return timed;
    }

    /**
     * 超时执行返回句柄 · 等价 CC {@code ShellCommand}（ShellCommand.ts:32-47）的子集（process /
     * exitFuture / background / killTree / taskOutput≈output）。
     */
    public static final class TimedShellCommand {

        private final Process process;
        private final CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
        private final StringBuilder output = new StringBuilder();
        private final Consumer<String> onProgress;
        private final Object lock = new Object();
        private boolean backgrounded;
        private boolean timedOut;
        private boolean userKilled;
        private volatile String timeoutMessage;

        TimedShellCommand(Process process, Consumer<String> onProgress) {
            this.process = process;
            this.onProgress = onProgress;
        }

        /** 底层进程（spawn 失败 → null）。 */
        public Process process() {
            return process;
        }

        /** 退出码 future（对齐 CC {@code result.code}）：自然退出=实际码；超时 kill=143；kill()=137；spawn 失败=126。 */
        public CompletableFuture<Integer> exitFuture() {
            return exitFuture;
        }

        /** 是否因超时被杀（对齐 CC code === SIGTERM 判定，ShellCommand.ts:323-328）。 */
        public boolean isTimedOut() {
            synchronized (lock) {
                return timedOut;
            }
        }

        /** 超时提示消息（对齐 CC prependStderr 前缀，:323-328）。 */
        public String timeoutMessage() {
            return timeoutMessage;
        }

        /** 累计合并输出（stdout/stderr 时间交错序，对齐 CC TaskOutput stdout）。 */
        public String getOutput() {
            synchronized (lock) {
                return output.toString();
            }
        }

        /** 追加输出块（读线程回调）· 同步喂 onProgress。 */
        void appendOutput(String s) {
            synchronized (lock) {
                output.append(s);
            }
            if (onProgress != null) {
                onProgress.accept(s);
            }
        }

        /**
         * 转后台 · 对齐 CC {@code background(taskId)}（ShellCommand.ts:349-366）：
         * 仅 running 状态可转（返回 true）；超时清除由定时器唤醒时检查 {@link #isBackgrounded()} 承接。
         *
         * @param taskId 后台任务 ID
         * @return true = 成功转后台（进程保留、exitFuture 挂起至自然退出）
         */
        public boolean background(String taskId) {
            synchronized (lock) {
                if (backgrounded || exitFuture.isDone()) {
                    return false;
                }
                backgrounded = true;
            }
            if (log.isDebugEnabled()) {
                log.debug("ShellExecutor.TimedShellCommand: 转后台 taskId={} pid={}", taskId, process.pid());
            }
            return true;
        }

        boolean isBackgrounded() {
            synchronized (lock) {
                return backgrounded;
            }
        }

        /** 标记超时（定时器线程调用；已转后台/已解析则忽略）。 */
        void markTimedOut(String message) {
            synchronized (lock) {
                if (backgrounded || exitFuture.isDone()) {
                    return;
                }
                timedOut = true;
                timeoutMessage = message;
            }
        }

        /**
         * 杀进程树 · 对齐 CC {@code kill()} → {@code #doKill()}（ShellCommand.ts:337-347）：
         * {@code treeKill(pid, 'SIGKILL')} 杀<b>整棵进程树</b>（含 {@code bash -c "sleep 999 & wait"}
         * 的 sleep 子进程），委托 {@link ProcessTreeKiller#killTree}——Windows taskkill /F /T /
         * POSIX 先枚举后代再杀根。G2-3 修复：旧实现 {@code killProcessTree}（SIGTERM→5s→SIGKILL）
         * 只对直接子进程生效，树内 sleep 成孤儿。退出码由 {@link #resolveExit()} 映射
         * （用户 kill → 137，超时 → 143）。
         */
        public void killTree() {
            synchronized (lock) {
                if (exitFuture.isDone()) {
                    return;
                }
                userKilled = true;
            }
            if (process == null) {
                resolveExit();
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("ShellExecutor.TimedShellCommand: killTree pid={}", process.pid());
            }
            // G2-3: 对齐 CC #doKill treeKill(pid,'SIGKILL')（ShellCommand.ts:337-343）杀整棵进程树
            ProcessTreeKiller.killTree(process);
            // 对齐 CC #doKill resolveExitCode(code ?? SIGKILL)：立即解析（幂等）
            resolveExit();
        }

        /** 解析最终退出码（exit watcher / kill / 超时均调用，幂等）。 */
        void resolveExit() {
            int code;
            synchronized (lock) {
                if (exitFuture.isDone()) {
                    return;
                }
                if (timedOut) {
                    code = EXIT_SIGTERM;
                } else if (userKilled) {
                    code = EXIT_SIGKILL;
                } else if (process != null) {
                    try {
                        code = process.exitValue();
                    } catch (IllegalThreadStateException e) {
                        // 进程未回收/信号杀无退出码（等价 CC #exitHandler code===null）→ 144
                        //   （G5-9, ShellCommand.ts:195-203 signal==='SIGTERM' ? 144 : 1）
                        code = EXIT_SIGNALED;
                    }
                } else {
                    code = 1;
                }
            }
            exitFuture.complete(code);
        }
    }

    /**
     * 杀进程树（含子孙进程）· 对齐 CC {@code tree-kill} 包（ShellCommand.ts:340
     * {@code treeKill(this.#childProcess.pid, 'SIGKILL')}）。
     *
     * <p>POSIX：{@code destroy()}（≈SIGTERM）→ 5s 未退 → {@code destroyForcibly()}（≈SIGKILL）；
     * Windows 无 TERM 单独语义 → {@code taskkill /T /F}（树杀，对齐 tree-kill 包 Windows 实现）。
     */
    private static void killProcessTree(Process process) {
        if (IS_WINDOWS) {
            try {
                Process tk = new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(process.pid()))
                    .redirectErrorStream(true).start();
                tk.waitFor(5, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (log.isDebugEnabled()) {
                    log.debug("ShellExecutor.killProcessTree: taskkill 失败，回退 destroy/destroyForcibly: {}", e.toString());
                }
                process.destroyForcibly();
            }
            return;
        }
        // POSIX：SIGTERM → 5s → SIGKILL（对齐计划 SIGTERM 语义）
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                if (log.isDebugEnabled()) {
                    log.debug("ShellExecutor.killProcessTree: SIGTERM 5s 未退，升级 SIGKILL pid={}", process.pid());
                }
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * G5-1/G5-9: 杀进程树（public 包装）· BashTool 前台超时/中断 kill 复用
     * {@link #killProcessTree}（对齐 CC {@code #doKill} treeKill 'SIGKILL'，ShellCommand.ts:337-343）。
     *
     * @param process 目标进程（null → no-op）
     */
    public static void killProcessTreeSafely(Process process) {
        if (process == null) {
            return;
        }
        killProcessTree(process);
    }

    /**
     * spawn cwd 校验与回退 · 对齐 CC Shell.ts:218-238：{@code realpath(cwd)} 失败（cwd 被删，
     * ENOENT）→ 回落 {@code getOriginalCwd()}（:225）；再失败 → null（调用方 G5 按 CC :234-236
     * 文案生成 ToolResult.error：{@code Working directory "<cwd>" no longer exists. ...}）。
     *
     * @param cwd 会话 cwd（null/blank → 原样返回，调用方不设置 directory）
     * @return realpath 后的 cwd；cwd 与启动目录都不可用 → null
     */
    public static String resolveSpawnCwd(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return cwd;
        }
        try {
            return Path.of(cwd).toRealPath().toString();
        } catch (IOException e) {
            // cwd 被删 · 对齐 CC realpath(cwd) catch → getOriginalCwd（Shell.ts:222-228）
            if (log.isDebugEnabled()) {
                log.debug("ShellExecutor.resolveSpawnCwd: cwd 不存在，回落启动目录: {}", cwd);
            }
            String fallback = CwdResolution.getOriginalCwdLayer();
            try {
                return Path.of(fallback).toRealPath().toString();
            } catch (IOException e2) {
                // 启动目录也不可用 · 调用方按 CC :234-236 文案生成错误
                if (log.isDebugEnabled()) {
                    log.debug("ShellExecutor.resolveSpawnCwd: 启动目录也不存在，返回 null: {}", fallback);
                }
                return null;
            }
        }
    }

    /**
     * 时长格式化 · 对齐 CC {@code formatDuration}（format.ts:34-100）：
     * &lt;60s → {@code floor(ms/1000)+"s"}（0 → {@code "0s"}）；≥60s → 天/时/分/秒
     * （秒四舍五入 + 进位）。超时消息前缀用（ShellCommand.ts:325）。
     *
     * @param ms 时长毫秒
     * @return 人类可读时长（如 {@code "10s"} / {@code "2m 0s"} / {@code "30m"})
     */
    public static String formatDuration(long ms) {
        if (ms < 60000) {
            if (ms == 0) {
                return "0s";
            }
            if (ms < 1) {
                return String.format("%.1fs", ms / 1000.0);
            }
            return (ms / 1000) + "s";
        }
        long days = ms / 86400000;
        long hours = (ms % 86400000) / 3600000;
        long minutes = (ms % 3600000) / 60000;
        long seconds = Math.round((ms % 60000) / 1000.0);
        if (seconds == 60) {
            seconds = 0;
            minutes++;
        }
        if (minutes == 60) {
            minutes = 0;
            hours++;
        }
        if (hours == 24) {
            hours = 0;
            days++;
        }
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    // ════════════════════════════════════════════════════════════════════
    // CC shellQuoting.ts / bashPipeCommand.ts 等价（utils/bash/ 下，grep 自验）
    // ════════════════════════════════════════════════════════════════════

    /** 2>nul / >nul / >>nul 重定向改写 · 对齐 shellQuoting.ts:126-134（防 Git Bash 建字面量 nul 文件）。 */
    private static String rewriteWindowsNullRedirect(String command) {
        Pattern p = Pattern.compile("(\\d?&?>+\\s*)[Nn][Uu][Ll](?=\\s|$|[|&;)\\n])");
        return p.matcher(command).replaceAll("$1/dev/null");
    }

    /** heredoc 检测 · 对齐 shellQuoting.ts:17-37（排除位运算 << / $(( << ))。 */
    private static boolean containsHeredoc(String command) {
        if (Pattern.compile("\\d\\s*<<\\s*\\d").matcher(command).find()) {
            return false;
        }
        if (Pattern.compile("\\[\\[\\s*\\d+\\s*<<\\s*\\d+\\s*\\]\\]").matcher(command).find()) {
            return false;
        }
        if (Pattern.compile("\\$\\(\\(.*<<.*\\)\\)").matcher(command).find()) {
            return false;
        }
        return Pattern.compile("<<-?\\s*(?:(['\"]?)(\\w+)\\1|\\\\(\\w+))").matcher(command).find();
    }

    /** 多行字符串检测 · 对齐 shellQuoting.ts:39-58（含换行的引号字符串）。 */
    private static boolean containsMultilineString(String command) {
        return Pattern.compile("'(?:[^'\\\\]|\\\\.)*\\n(?:[^'\\\\]|\\\\.)*'").matcher(command).find()
            || Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\\n(?:[^\"\\\\]|\\\\.)*\"").matcher(command).find();
    }

    /** 既有 stdin 重定向检测 · 对齐 shellQuoting.ts:81-91（排除 <( < < 但匹配 < file）。 */
    private static boolean hasStdinRedirect(String command) {
        return Pattern.compile("(?:^|[\\s;&|])<(?![<(])\\s*\\S+").matcher(command).find();
    }

    /** 是否应加 stdin 重定向 · 对齐 shellQuoting.ts:93-107。 */
    private static boolean shouldAddStdinRedirect(String command) {
        return !containsHeredoc(command) && !hasStdinRedirect(command);
    }

    /**
     * 命令引号化 · 对齐 shellQuoting.ts:46-79：heredoc/多行 → 单引号字面量 + {@code '"'"'} escape
     * （heredoc 不加 stdin；多行加 {@code < /dev/null}）；普通命令 → shell-quote 等价
     * （{@code quote([command])} 或 {@code quote([command, '<', '/dev/null'])}）。
     */
    private static String quoteShellCommand(String command, boolean addStdinRedirect) {
        if (containsHeredoc(command) || containsMultilineString(command)) {
            String quoted = ShellQuoteParser.singleQuoteForEval(command);
            if (containsHeredoc(command)) {
                return quoted;
            }
            return addStdinRedirect ? quoted + " < /dev/null" : quoted;
        }
        if (addStdinRedirect) {
            return ShellQuoteParser.quote(List.of(command, "<", "/dev/null"));
        }
        return ShellQuoteParser.quote(List.of(command));
    }

    /**
     * 禁用 extglob 前缀 · 对齐 bashProvider.ts:17-35 getDisableExtglobCommand：
     * CLAUDE_CODE_SHELL_PREFIX 时双 shell 兼容；bash → shopt；zsh → setopt；未知 → null。
     */
    private static String getDisableExtglobCommand(String bashPath) {
        if (System.getenv("CLAUDE_CODE_SHELL_PREFIX") != null) {
            return "{ shopt -u extglob || setopt NO_EXTENDED_GLOB; } >/dev/null 2>&1 || true";
        }
        if (bashPath != null && bashPath.contains("bash")) {
            return "shopt -u extglob 2>/dev/null || true";
        }
        if (bashPath != null && bashPath.contains("zsh")) {
            return "setopt NO_EXTENDED_GLOB 2>/dev/null || true";
        }
        return null;
    }
}
