package com.nexusai.application.terminal;

import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 默认 shell 探测 + 白名单校验（独立小类，便于纯单测）。
 *
 * <p><b>WHY 必须真的探测</b>：按 OS 猜（「Windows 就给 cmd」）会让装了 PowerShell 7 的机器拿不到
 * 真终端体验，更糟的是<b>猜错就直接崩</b> —— 例如 macOS 上 {@code $SHELL} 被用户改成一个不存在的
 * 路径，或 Windows 上根本没有 Git Bash 却硬要用 {@code bash.exe}。故每个候选都必须过
 * {@link #exists} 谓词（生产实现 = 真的看文件系统 / PATH），⛔ 不允许只按 OS 名返回。
 *
 * <p><b>候选顺序（照抄需求，不自行调整）</b>
 * <ul>
 *   <li>Windows：{@code pwsh.exe} → {@code powershell.exe} → {@code cmd.exe} →
 *       Git Bash（{@code C:\Program Files\Git\bin\bash.exe}、
 *       {@code C:\Program Files (x86)\Git\bin\bash.exe}）→ WSL（{@code wsl.exe}，
 *       且 {@code wsl -l -q} 至少有一个发行版）</li>
 *   <li>macOS / Linux：{@code $SHELL} → {@code /bin/zsh} → {@code /bin/bash} → {@code /bin/sh}</li>
 * </ul>
 *
 * <p><b>白名单 = 防命令注入的唯一判据</b>：{@link #validate(String)} 只接受「本机实际可用的候选」
 * 里的<b>逐字相等</b>值（{@link #availableShells()}）。任何其它字符串（{@code "/bin/sh -c rm -rf /"}、
 * {@code "cmd.exe & del ..."}）一律 400。因为启动参数是 {@code String[]} 直传 PTY（不经 shell 解析），
 * 只要值不来自调用方拼装，就没有注入面。
 *
 * <p><b>可测性</b>：{@link #exists} 谓词 / {@link #env} / WSL 发行版查询全部构造注入 ——
 * 单测不依赖本机装了哪些 shell（{@code TerminalShellResolverTest}）。
 */
public final class TerminalShellResolver {

    private static final Logger log = LoggerFactory.getLogger(TerminalShellResolver.class);

    /** WSL 引导器（Windows 候选表最后一项）。 */
    public static final String WSL = "wsl.exe";

    /** WSL 探测超时：{@code wsl.exe} 在服务未启动时可能长时间不返回，超时即视为「无发行版」。 */
    private static final long WSL_PROBE_TIMEOUT_MS = 3000;

    private final String osName;
    private final Predicate<String> exists;
    private final Map<String, String> env;
    private final Supplier<List<String>> wslDistributions;

    /** {@link #availableShells()} 的惰性缓存（WSL 探测要起子进程，一次会话只探一次）。 */
    private volatile List<String> cachedAvailable;

    /**
     * @param osName            {@code System.getProperty("os.name")} 的值（测试可传任意串）
     * @param exists            可执行文件存在性谓词（生产 = 文件系统 / PATH 真检查）
     * @param env               环境变量快照（读 {@code SHELL} / {@code PATH} / {@code PATHEXT}）
     * @param wslDistributions  返回 WSL 发行版名列表（无 WSL ⇒ 空列表；测试可注入固定值）
     */
    public TerminalShellResolver(String osName, Predicate<String> exists, Map<String, String> env,
                                 Supplier<List<String>> wslDistributions) {
        this.osName = osName != null ? osName : "";
        this.exists = exists != null ? exists : n -> false;
        this.env = env != null ? env : Map.of();
        this.wslDistributions = wslDistributions != null ? wslDistributions : List::of;
    }

    /**
     * 生产构造：读当前系统属性 / 环境变量，并用<b>真实的</b>可执行文件探测（绝对路径看文件系统，
     * 裸名走 {@code PATH} + {@code PATHEXT}）。
     *
     * @return 绑定本机环境的解析器
     */
    public static TerminalShellResolver forCurrentSystem() {
        Map<String, String> env = System.getenv();
        String os = System.getProperty("os.name", "");
        return new TerminalShellResolver(os, systemExecutableProbe(env, os), env,
            TerminalShellResolver::queryWslDistributions);
    }

    /** 当前是否 Windows（按 {@code os.name} 前缀，含 "Windows 11" / "Windows Server ..."）。 */
    public boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).startsWith("win");
    }

    /**
     * 原始候选表（<b>未</b>过滤存在性，顺序即优先级）。
     *
     * @return 候选 shell 标识（Windows = 可执行文件名/绝对路径 + {@link #WSL}；Unix = 绝对路径）
     */
    public List<String> candidates() {
        if (isWindows()) {
            return List.of(
                "pwsh.exe",
                "powershell.exe",
                "cmd.exe",
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                WSL);
        }
        List<String> unix = new ArrayList<>(4);
        String envShell = envGet(env, "SHELL");
        if (envShell != null && !envShell.isBlank()) {
            unix.add(envShell.trim());
        }
        unix.add("/bin/zsh");
        unix.add("/bin/bash");
        unix.add("/bin/sh");
        return List.copyOf(unix);
    }

    /**
     * 本机<b>真实可用</b>的 shell（候选表过滤存在性；{@link #WSL} 额外要求至少一个发行版）。
     *
     * <p>结果缓存：首次调用可能起 {@code wsl.exe} 子进程（≤{@value #WSL_PROBE_TIMEOUT_MS}ms）。
     *
     * @return 有序、去重、可安全传入 {@link #validate} 的白名单
     */
    public List<String> availableShells() {
        List<String> cached = cachedAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedAvailable != null) {
                return cachedAvailable;
            }
            LinkedHashSet<String> available = new LinkedHashSet<>();
            boolean wslUsable = false;
            for (String candidate : candidates()) {
                if (WSL.equals(candidate)) {
                    if (wslUsable || hasWslDistribution()) {
                        wslUsable = true;
                        available.add(candidate);
                    }
                    continue;
                }
                if (safeExists(candidate)) {
                    available.add(candidate);
                }
            }
            cachedAvailable = List.copyOf(available);
            if (log.isInfoEnabled()) {
                log.info("[TerminalService] shell 探测完成: os={} 可用={}", osName, cachedAvailable);
            }
            return cachedAvailable;
        }
    }

    /**
     * 默认 shell = {@link #availableShells()} 第一项。
     *
     * @return 默认 shell（白名单内）
     * @throws IllegalStateException 本机一个候选都不可用（装配/环境异常，必须 fail loud）
     */
    public String resolveDefault() {
        List<String> available = availableShells();
        if (available.isEmpty()) {
            throw new IllegalStateException("未探测到任何可用 shell（候选=" + candidates()
                + "）；请检查 PATH / SHELL 环境变量");
        }
        return available.get(0);
    }

    /**
     * 校验调用方指定的 shell（前端下拉）。
     *
     * <p>判据 = {@link #availableShells()} 内<b>逐字（Windows 忽略大小写）相等</b>；否则
     * {@link ValidationException} → 400。⛔ 不接受「白名单外但文件存在」的值 —— 那会打开任意命令执行面。
     *
     * @param requested 请求指定的 shell（null/空白 ⇒ 返回 {@link #resolveDefault()}）
     * @return 白名单内的规范值
     * @throws ValidationException 不在白名单内
     */
    public String validate(String requested) {
        if (requested == null || requested.isBlank()) {
            return resolveDefault();
        }
        String wanted = requested.trim();
        for (String allowed : availableShells()) {
            boolean match = isWindows() ? allowed.equalsIgnoreCase(wanted) : allowed.equals(wanted);
            if (match) {
                return allowed;
            }
        }
        throw new ValidationException("不支持的终端 shell: " + wanted
            + "（允许=" + availableShells() + "）");
    }

    /**
     * 把白名单 shell 展开成 PTY 启动参数。
     *
     * <p>参数以 {@code String[]} 直传 PTY，<b>不经任何 shell 解析</b> ⇒ 调用方提供的值只要来自
     * {@link #validate}，就不存在命令注入。
     *
     * @param shell {@link #validate} 返回值
     * @return argv（至少 1 个元素）
     */
    public String[] commandFor(String shell) {
        if (shell == null || shell.isBlank()) {
            throw new ValidationException("终端 shell 为空");
        }
        if (WSL.equalsIgnoreCase(shell)) {
            // 默认发行版 + 默认登录 shell；cwd 由 wsl.exe 继承并自动翻译（见交付报告「未做」部分）
            return new String[]{WSL};
        }
        if (isWindows() && shell.toLowerCase(Locale.ROOT).endsWith("bash.exe")) {
            // Git Bash：-i 强制交互（否则某些启动方式会直接退出）
            return new String[]{shell, "-i"};
        }
        return new String[]{shell};
    }

    /**
     * shell → 前端下拉友好名（未知值回落原文件名，⛔ 不编造名字）。
     *
     * <p>仅用于<b>展示</b>；{@code value} 仍用 {@link #availableShells()} 原值，保证回传
     * {@link #validate} 时逐字命中白名单。
     *
     * @param shell 白名单原值（如 {@code C:\Program Files\Git\bin\bash.exe}）
     * @return 友好名（如 {@code Git Bash}）；null/空白 ⇒ 空串
     */
    public static String labelFor(String shell) {
        if (shell == null || shell.isBlank()) {
            return "";
        }
        String fileName = fileNameOf(shell);
        String lower = fileName.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "pwsh.exe":
                return "PowerShell 7";
            case "powershell.exe":
                return "Windows PowerShell";
            case "cmd.exe":
                return "Command Prompt";
            case "wsl.exe":
                return "WSL";
            case "bash.exe":
                return shell.toLowerCase(Locale.ROOT).contains("git") ? "Git Bash" : fileName;
            default:
                return fileName;
        }
    }

    /** 取路径最后一段（Windows / Unix 分隔符都认）。 */
    private static String fileNameOf(String path) {
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    private boolean hasWslDistribution() {
        if (!safeExists(WSL)) {
            return false;
        }
        try {
            List<String> distros = wslDistributions.get();
            boolean present = distros != null && !distros.isEmpty();
            if (!present && log.isInfoEnabled()) {
                log.info("[TerminalService] 探测到 wsl.exe 但无发行版 ⇒ 不纳入白名单");
            }
            return present;
        } catch (RuntimeException e) {
            log.warn("[TerminalService] WSL 发行版探测失败（按无 WSL 处理）: {}", e.toString());
            return false;
        }
    }

    private boolean safeExists(String candidate) {
        try {
            return exists.test(candidate);
        } catch (RuntimeException e) {
            log.warn("[TerminalService] shell 存在性探测异常: candidate={} err={}", candidate, e.toString());
            return false;
        }
    }

    /** 大小写不敏感的环境变量读取（Windows 的 PATH 可能是 {@code Path} / {@code PATH}）。 */
    private static String envGet(Map<String, String> env, String key) {
        String direct = env.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 生产用存在性谓词：绝对/带分隔符的路径 → 文件系统；裸名 → {@code PATH}（Windows 叠加
     * {@code PATHEXT}）。Unix 额外要求可执行位。
     */
    static Predicate<String> systemExecutableProbe(Map<String, String> env, String osName) {
        final boolean windows = osName != null
            && osName.toLowerCase(Locale.ROOT).startsWith("win");
        return candidate -> {
            if (candidate == null || candidate.isBlank()) {
                return false;
            }
            boolean pathLike = candidate.contains("/") || candidate.contains("\\");
            if (pathLike) {
                return isExecutableFile(Path.of(candidate), windows);
            }
            String pathEnv = envGet(env, "PATH");
            if (pathEnv == null || pathEnv.isBlank()) {
                return false;
            }
            List<String> names = new ArrayList<>(2);
            names.add(candidate);
            if (windows && !hasExtension(candidate)) {
                String pathext = envGet(env, "PATHEXT");
                if (pathext == null || pathext.isBlank()) {
                    pathext = ".COM;.EXE;.BAT;.CMD";
                }
                for (String ext : pathext.split(";")) {
                    String e = ext.trim();
                    if (!e.isEmpty()) {
                        names.add(candidate + e);
                    }
                }
            }
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                for (String name : names) {
                    try {
                        if (isExecutableFile(Path.of(dir, name), windows)) {
                            return true;
                        }
                    } catch (RuntimeException ignored) {
                        // 非法路径（含通配符等）⇒ 跳过该目录项
                    }
                }
            }
            return false;
        };
    }

    private static boolean hasExtension(String name) {
        int dot = name.lastIndexOf('.');
        int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot > sep + 1;
    }

    private static boolean isExecutableFile(Path p, boolean windows) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        return windows || Files.isExecutable(p);
    }

    /**
     * 查询 WSL 发行版（{@code wsl.exe -l -q}）。
     *
     * <p>⚠️ 输出是 <b>UTF-16LE</b>（实测：{@code 64 00 6f 00 63 00 ...} = "docker-desktop"）；
     * 但历史版本/环境也可能给 UTF-8 ⇒ 按字节形态判定后解码。
     *
     * @return 发行版名列表（无 WSL / 探测失败 / 超时 ⇒ 空列表）
     */
    static List<String> queryWslDistributions() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(WSL, "-l", "-q");
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process proc = process;
            final byte[][] out = new byte[1][];
            Thread reader = new Thread(() -> {
                try {
                    out[0] = proc.getInputStream().readAllBytes();
                } catch (Exception e) {
                    // 进程被强杀时 read 会失败 —— 由调用方按「无发行版」处理
                }
            }, "wsl-probe-reader");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(WSL_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("[TerminalService] wsl.exe -l -q 超时（{}ms）⇒ 按无 WSL 处理", WSL_PROBE_TIMEOUT_MS);
                process.destroyForcibly();
                return List.of();
            }
            reader.join(1000);
            return parseWslDistributions(out[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("[TerminalService] WSL 发行版探测异常（按无 WSL 处理）: {}", e.toString());
            return List.of();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 解析 {@code wsl -l -q} 的原始字节（可见性放宽到包内，便于单测）。 */
    static List<String> parseWslDistributions(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        String text = looksLikeUtf16Le(raw)
            ? new String(raw, StandardCharsets.UTF_16LE)
            : new String(raw, StandardCharsets.UTF_8);
        List<String> distros = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String s = line.replace("\uFEFF", "").replace("\u0000", "").trim();
            if (s.isEmpty()) {
                continue;
            }
            // 无发行版时 wsl.exe 会打印一句提示语（含空格 / 非 ASCII / 冒号，或很长）—— 不是发行版名。
            // ⚠️ 不能按「含点号」过滤：Ubuntu-22.04 / docker-desktop 这类正常名字就带点。
            if (s.length() > 64 || s.contains(" ") || s.contains("：") || s.contains(":")
                || !isAscii(s)) {
                continue;
            }
            distros.add(s);
        }
        return List.copyOf(distros);
    }

    /**
     * UTF-16LE 的字节形态判据：ASCII 字符的高字节落在<b>奇数位</b>且为 0，中文字符两个字节都不为 0
     * ⇒ 「奇数位有 0、偶数位几乎没有 0」就是 UTF-16LE。
     *
     * <p>⚠️ 不能用「奇数位 90% 为 0」这类严格阈值：纯 UTF-8 或含大量中文的 UTF-16LE 文本都会被判成
     * UTF-8，进而解出乱码（实测：{@code wsl.exe} 的无发行版提示语就是中文，ASCII 只占少数）。
     */
    private static boolean looksLikeUtf16Le(byte[] raw) {
        int checked = Math.min(raw.length - (raw.length % 2), 200);
        if (checked < 2) {
            return false;
        }
        int oddZeros = 0;
        int evenZeros = 0;
        for (int i = 0; i < checked; i++) {
            if (raw[i] == 0) {
                if ((i & 1) == 1) {
                    oddZeros++;
                } else {
                    evenZeros++;
                }
            }
        }
        return oddZeros > 0 && (evenZeros == 0 || oddZeros >= evenZeros * 5);
    }

    /** 全部字符 &lt; 0x80（发行版名是 ASCII；非 ASCII 一律视为本地化提示语）。 */
    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
