package com.nexusai.wechatlog.key;

import com.nexusai.wechatlog.crypto.SqlCipherParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Linux 取密钥：用 gdb 断在密钥派生函数上。
 *
 * <h2>与 macOS 的关键差异</h2>
 * macOS 能直接断苹果的系统符号 {@code CCKeyDerivationPBKDF}；Linux 微信是静态链接的
 * 单体二进制，<b>没有可用的系统符号</b>。上游（wcdb-key-tool）的解法是：
 * <ol>
 *   <li>解析 ELF，在 {@code .rodata} 里找 {@code com.Tencent.WCDB.Config.Cipher} 字符串；</li>
 *   <li>顺着交叉引用定位「处理密钥的那个函数」的入口地址；</li>
 *   <li>在该地址（基址 + 偏移）下 gdb 断点；</li>
 *   <li>命中时从 {@code rsi/rdx}（x86_64 SysV 第 2/3 个整型参数 = password/passwordLen）
 *       读 32 字节。</li>
 * </ol>
 *
 * <h2>⚠️ 本实现的验证状态（诚实声明）</h2>
 * <b>本机是 macOS，没有 Linux 环境可实测。</b>因此：
 * <ul>
 *   <li>ELF 解析 + {@code .rodata} 搜索 + 交叉引用定位这一整套「自动适配新版本」
 *       的能力，本实现<b>没有照抄</b>（那是数千行的静态分析）。</li>
 *   <li>本实现改为<b>两种可落地的策略</b>：
 *     <ol>
 *       <li><b>符号断点</b>：若发行版微信仍导出可用的派生符号（部分版本有），直接断；</li>
 *       <li><b>用户提供偏移</b>：日志与错误信息里明确要求用户填「基址+偏移」，
 *           由配置项传入。这是<b>显式失败</b>而不是瞎猜地址。</li>
 *     </ol>
 *   </li>
 *   <li>因此 Linux 路径在默认配置下<b>不会自动工作</b>，需要人工补一次偏移。
 *       这一点必须在交付说明里讲清楚，不能让人以为「三平台开箱即用」。</li>
 * </ul>
 *
 * <h2>前置条件（缺一不可）</h2>
 * <ul>
 *   <li>root 权限（attach 需要 {@code CAP_SYS_PTRACE}）；</li>
 *   <li>{@code /proc/sys/kernel/yama/ptrace_scope} 为 0，或 gdb 以 root 运行
 *       （root + ptrace_scope=1 仍不能 attach 非子进程）；</li>
 *   <li>已安装 gdb。</li>
 * </ul>
 */
public class LinuxKeyExtractor implements WeChatKeyExtractor {

    private static final Logger log = LoggerFactory.getLogger(LinuxKeyExtractor.class);

    private static final int DEFAULT_CAPTURE_SECONDS = 60;

    private final int captureSeconds;

    /** 用户显式提供的断点偏移（十六进制字符串，如 "1a2b3c"）。null 表示未提供。 */
    private final String hookOffsetHex;

    /** 用户显式提供的派生函数符号名。null 表示未提供。 */
    private final String symbolName;

    public LinuxKeyExtractor() {
        this(DEFAULT_CAPTURE_SECONDS, null, null);
    }

    public LinuxKeyExtractor(int captureSeconds, String hookOffsetHex, String symbolName) {
        this.captureSeconds = Math.max(10, captureSeconds);
        this.hookOffsetHex = hookOffsetHex;
        this.symbolName = symbolName;
    }

    @Override
    public boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux");
    }

    @Override
    public String platformName() {
        return "Linux (gdb 断点)";
    }

    @Override
    public List<byte[]> extract(Path accountDir) throws KeyExtractionException {
        preflight();

        int pid = findWeChatPid();
        if (pid <= 0) {
            throw new KeyExtractionException(
                    "未找到正在运行的微信进程",
                    "请先启动微信并登录，然后重试。Linux 版微信的可执行名通常是 "
                            + "`wechat`、`weixin` 或 `WeChat`，可用 `pgrep -a -i wechat` 确认。");
        }
        log.info("找到微信进程 pid={}", pid);

        // 断点目标：优先用户给的符号，其次用户给的偏移
        String breakTarget = resolveBreakTarget(pid);
        log.info("gdb 断点目标: {}", breakTarget);

        Path dir = null;
        try {
            dir = Files.createTempDirectory("nexusai-wx-keys-");
            Path outFile = dir.resolve("keys.txt");
            Files.createFile(outFile);
            Path gdbScript = dir.resolve("capture.gdb");
            Files.writeString(gdbScript,
                    buildGdbScript(pid, captureSeconds, outFile, breakTarget),
                    StandardCharsets.UTF_8);

            runGdbWithSudo(gdbScript, dir);
            return readCapturedKeys(outFile);

        } catch (IOException e) {
            throw new KeyExtractionException("准备 gdb 脚本失败", "检查临时目录是否可写。", e);
        } finally {
            if (dir != null) {
                deleteRecursively(dir);
            }
        }
    }

    // ==================================================================
    // 前置检查
    // ==================================================================

    private void preflight() {
        if (!isSupported()) {
            throw new KeyExtractionException("当前不是 Linux", "此实现仅在 Linux 上可用。");
        }
        if (!Files.isExecutable(Paths.get("/usr/bin/gdb"))
                && !Files.isExecutable(Paths.get("/usr/local/bin/gdb"))) {
            throw new KeyExtractionException(
                    "未找到 gdb",
                    "安装 gdb：Debian/Ubuntu `sudo apt install gdb`；"
                            + "Fedora `sudo dnf install gdb`；Arch `sudo pacman -S gdb`");
        }
        Integer scope = readPtraceScope();
        if (scope != null && scope > 0) {
            // 不是硬阻断：root 下多数发行版仍可 attach。只提示。
            log.warn("ptrace_scope={} 限制了 attach 非子进程。若 gdb 报 "
                    + "「ptrace: Operation not permitted」，执行："
                    + "echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope", scope);
        }
    }

    private Integer readPtraceScope() {
        try {
            String s = Files.readString(
                    Paths.get("/proc/sys/kernel/yama/ptrace_scope")).trim();
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private int findWeChatPid() {
        for (String name : new String[]{"wechat", "weixin", "WeChat", "Weixin"}) {
            try {
                Process p = new ProcessBuilder("pgrep", "-x", name).start();
                String out = new String(p.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim();
                p.waitFor(5, TimeUnit.SECONDS);
                if (!out.isBlank()) {
                    return Integer.parseInt(out.split("\\s+")[0]);
                }
            } catch (Exception e) {
                log.debug("pgrep {} 失败: {}", name, e.getMessage());
            }
        }
        return -1;
    }

    /**
     * 决定 gdb 断在哪里。
     *
     * <p>⚠️ 这里<b>不做</b>「自动在 ELF 里找函数偏移」的静态分析 —— 那是一整套
     * 交叉引用推导，无法在本机验证就写进来等同于瞎猜。宁可显式失败并告诉用户
     * 需要填什么，也不要给出一个看起来能跑、实际断在错误地址的方案。
     */
    private String resolveBreakTarget(int pid) {
        if (symbolName != null && !symbolName.isBlank()) {
            return symbolName;
        }
        if (hookOffsetHex != null && !hookOffsetHex.isBlank()) {
            return "*($base + 0x" + hookOffsetHex.trim() + ")";
        }
        throw new KeyExtractionException(
                "Linux 平台缺少断点位置配置，无法自动定位密钥派生函数",
                "Linux 版微信是静态链接、无可用的系统符号，git 上的工具靠 ELF 静态分析"
                        + "自动推导偏移，而本实现没有内置这套分析。请任选其一：\n"
                        + "  1) 在配置里填 `wechatlog.linux.hookOffset`（十六进制，如 1a2b3c）；\n"
                        + "  2) 在配置里填 `wechatlog.linux.symbol`（若你的微信版本导出了派生函数符号）；\n"
                        + "  3) 先手工用 gdb/偏移工具取一次密钥，再用「手动填密钥」的方式导入。\n"
                        + "获取偏移的参考实现：github.com/TANGandXUE/wcdb-key-tool（ELF 静态分析部分）。");
    }

    // ==================================================================
    // gdb 脚本
    // ==================================================================

    /**
     * 生成 gdb 脚本。
     *
     * <p>两种断点目标分别处理：
     * <ul>
     *   <li>符号：{@code break <symbol>}；</li>
     *   <li>偏移：先用 {@code info proc mappings} 取基址，再 {@code break *(base+off)}。</li>
     * </ul>
     *
     * <p>命中时读 {@code $rsi}（password 指针）与 {@code $rdx}（长度），长度 == 32 才写文件。
     * 这是 x86_64 SysV 调用约定；aarch64 Linux 则用 {@code x1/x2}。
     */
    String buildGdbScript(int pid, int seconds, Path outFile, String breakTarget) {
        boolean arm64 = isArm64();
        String keyReg = arm64 ? "$x1" : "$rsi";
        String lenReg = arm64 ? "$x2" : "$rdx";
        String outPath = outFile.toAbsolutePath().toString();

        StringBuilder sb = new StringBuilder();
        sb.append("set pagination off\n");
        sb.append("set confirm off\n");
        sb.append("set height 0\n");
        sb.append("set width 0\n");
        sb.append("attach ").append(pid).append("\n");

        if (breakTarget.startsWith("*($base")) {
            // 偏移模式：先解析基址
            String off = breakTarget.replace("*($base + 0x", "").replace(")", "");
            sb.append("shell echo RESOLVING_BASE\n");
            sb.append("set $base = 0\n");
            sb.append("info proc mappings\n");
            // gdb 脚本里无法方便地解析 mappings 输出，这里退化为让用户提供绝对地址
            sb.append("python\n");
            sb.append("import gdb, re\n");
            sb.append("try:\n");
            sb.append("    out = gdb.execute('info proc mappings', to_string=True)\n");
            sb.append("    base = None\n");
            sb.append("    for line in out.splitlines():\n");
            sb.append("        if 'wechat' in line.lower() or 'weixin' in line.lower():\n");
            sb.append("            base = int(line.split()[0], 16)\n");
            sb.append("            break\n");
            sb.append("    if base is None:\n");
            sb.append("        base = int(out.splitlines()[0].split()[0], 16)\n");
            sb.append("    gdb.execute('set $base = 0x%x' % base)\n");
            sb.append("except Exception as e:\n");
            sb.append("    print('BASE_RESOLVE_FAIL %s' % e)\n");
            sb.append("end\n");
            sb.append("break *($base + 0x").append(off).append(")\n");
        } else {
            sb.append("break ").append(breakTarget).append("\n");
        }

        // 断点命令：读 rsi/rdx，长度 32 才落盘
        sb.append("commands\n");
        sb.append("  silent\n");
        sb.append("  python\n");
        sb.append("import gdb\n");
        sb.append("try:\n");
        sb.append("    ln = int(gdb.parse_and_eval('").append(lenReg).append("'))\n");
        sb.append("    if ln == 32:\n");
        sb.append("        p = int(gdb.parse_and_eval('").append(keyReg).append("'))\n");
        sb.append("        if p:\n");
        sb.append("            data = gdb.selected_inferior().read_memory(p, 32).tobytes()\n");
        sb.append("            with open(r'").append(outPath).append("', 'a') as f:\n");
        sb.append("                f.write(data.hex() + chr(10))\n");
        sb.append("                f.flush()\n");
        sb.append("except Exception:\n");
        sb.append("    pass\n");
        sb.append("end\n");
        sb.append("  continue\n");
        sb.append("end\n");

        sb.append("continue\n");
        sb.append("shell sleep ").append(seconds).append("\n");
        sb.append("interrupt\n");
        sb.append("detach\n");
        sb.append("quit\n");
        return sb.toString();
    }

    private boolean isArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    // ==================================================================
    // 提权执行
    // ==================================================================

    /**
     * 用 {@code sudo -n}（非交互）尝试；失败则提示用户手动执行。
     *
     * <p>Linux 下没有 osascript 那样的图形授权，且我们的进程通常没有 tty 可输密码。
     * 所以策略是：先试 {@code sudo -n}（已配置 NOPASSWD 或刚授权过），
     * 不行就<b>把命令打印给用户</b>让他自己跑一次 —— 这比伪造一个卡住的密码提示更好。
     */
    private void runGdbWithSudo(Path gdbScript, Path workDir) {
        String cmd = "gdb -q --nx -batch -x " + shellQuote(gdbScript.toAbsolutePath().toString());
        try {
            Process p = new ProcessBuilder("sudo", "-n", "sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(captureSeconds + 60L, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new KeyExtractionException("gdb 执行超时", "确认微信可操作后重试。");
            }
            if (p.exitValue() == 0) {
                return;
            }
            log.warn("sudo -n 执行失败（可能未配置免密），输出:\n{}", out);

            // 把确切命令交给用户手动执行
            if (out.contains("password is required") || out.contains("a password is required")
                    || p.exitValue() == 1) {
                throw new KeyExtractionException(
                        "需要 root 权限运行 gdb（当前无法自动提权）",
                        "请在终端手动执行下面这条命令，完成后回到 NexusAI 重试：\n\n"
                                + "  sudo sh -c \"" + cmd + "\"\n\n"
                                + "（脚本位置：" + gdbScript.toAbsolutePath() + "）");
            }
            if (out.toLowerCase(Locale.ROOT).contains("ptrace")) {
                throw new KeyExtractionException(
                        "ptrace 被内核策略阻止",
                        "执行：echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope\n然后重试。");
            }
            throw new KeyExtractionException(
                    "gdb 执行失败: " + out.trim(),
                    "确认微信已登录、gdb 已安装、断点目标（符号/偏移）与你的微信版本匹配。");
        } catch (IOException e) {
            throw new KeyExtractionException("启动 sudo 失败", "确认系统有 sudo。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeyExtractionException("等待 gdb 被中断", "请重试。", e);
        }
    }

    // ==================================================================
    // 结果
    // ==================================================================

    List<byte[]> readCapturedKeys(Path outFile) throws KeyExtractionException {
        Set<String> hex = new LinkedHashSet<>();
        try {
            if (Files.isRegularFile(outFile)) {
                for (String line : Files.readAllLines(outFile, StandardCharsets.UTF_8)) {
                    String h = line.trim().toLowerCase(Locale.ROOT);
                    if (h.length() == SqlCipherParams.KEY_SIZE * 2 && h.matches("[0-9a-f]+")) {
                        hex.add(h);
                    }
                }
            }
        } catch (IOException e) {
            throw new KeyExtractionException("读取抓取结果失败", "检查临时目录权限。", e);
        }
        if (hex.isEmpty()) {
            throw new KeyExtractionException(
                    "未捕获到任何密钥",
                    "抓取期间请点开微信会话并滚动聊天记录。若仍无结果，说明断点位置与当前"
                            + "微信版本不匹配 —— Linux 是静态链接二进制，微信升级后偏移会变，需要重新获取。");
        }
        log.info("捕获到 {} 个不同的 32 字节候选", hex.size());
        List<byte[]> out = new ArrayList<>(hex.size());
        for (String h : hex) {
            out.add(fromHex(h));
        }
        return out;
    }

    private static byte[] fromHex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 忽略
                }
            });
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
