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
 * macOS 取密钥：用 lldb 断在 CommonCrypto 上，从寄存器里把密钥读出来。
 *
 * <h2>为什么不用「内存特征码扫描」（chatlog 那套）</h2>
 * 在 4.1.x 上必然失败，两个独立原因：
 * <ol>
 *   <li>4.1+ 不再把密钥以 {@code x'<hex>'} 文本形式放在内存里，扫字符串扫不到；</li>
 *   <li>{@code task_for_pid}/{@code mach_vm_read} 读不到存放密钥的那段内存
 *       （rmqg 实测：mach_vm 扫 2.7GB 无果，而 lldb 的 debugserver 能看到 8.7GB 且含密钥）。</li>
 * </ol>
 *
 * <h2>两个断点的取舍</h2>
 * <table>
 *   <tr><th>断点</th><th>拿到</th><th>arm64 寄存器</th><th>x86_64 寄存器</th><th>触发条件</th></tr>
 *   <tr><td>{@code CCCryptorCreate}</td><td>raw encKey（可直接用）</td>
 *       <td>key=x3, len=x4</td><td>key=rcx, len=r8</td>
 *       <td>每次解密页都会调用 ⇒ 随时可用</td></tr>
 *   <tr><td>{@code CCKeyDerivationPBKDF}</td><td>passphrase（需 256000 轮）</td>
 *       <td>password=x1, len=x2</td><td>password=rsi, len=rdx</td>
 *       <td><b>只在登录时调用一次</b> ⇒ 必须退出重登</td></tr>
 * </table>
 * 两个断点都设，谁抓到的 key 有效由 {@code KeyMatcher} 的 HMAC 校验裁定 ——
 * 这样无论微信版本走哪条路径都能覆盖。
 *
 * <h2>⚠️ 本机是 Intel x86_64</h2>
 * 上游公开脚本大多只写 arm64 的 {@code x3/x4}。本实现按 {@code os.arch} 选寄存器，两套都覆盖。
 * 但 <b>x86_64 + 微信 4.1.13 这个组合没有公开验证过</b>，属于明确的未验证项。
 *
 * <h2>为什么用独立 Python 脚本文件，而不是 {@code lldb -o} 内联表达式</h2>
 * 断点回调需要多行 Python（读寄存器、读内存、写文件、异常处理）。塞进
 * {@code -o "script ..."} 里要经过 shell → lldb → Python 三层引号转义，极易出错且难排障。
 * 写成独立 {@code .py} 文件由 lldb {@code command script import} 加载，引号问题全消失。
 *
 * <h2>提权方式（用户裁定：osascript 弹系统授权框）</h2>
 * {@code do shell script ... with administrator privileges} 会<b>阻塞到命令结束</b>，
 * 而 lldb 要等用户在微信里操作才命中。所以后台启动 + Java 侧等待进程结束。
 */
public class MacOsKeyExtractor implements WeChatKeyExtractor {

    private static final Logger log = LoggerFactory.getLogger(MacOsKeyExtractor.class);

    /**
     * 默认抓取时长。
     *
     * <p>真机实测：60-90 秒会让微信持续停顿（每次断点命中都要暂停整个进程）。
     * 用户明确要求「控制在 20 秒之前」，且实际 14 个库的密钥在 20 秒内就抓齐了。
     */
    private static final int DEFAULT_CAPTURE_SECONDS = 20;

    /**
     * 抓到这么多「不同的 32 字节」就提前收工。
     *
     * <p>为什么是 14：本机实测一个 32 库的账号需要 14 把密钥（每库一把）。
     * 达到这个量说明微信已经把主要数据库都解过了，继续等只是徒增卡顿。
     * 若某些库没被访问，用户可在下次运行时重抓（缓存的密钥不会丢）。
     */
    private static final int TARGET_KEY_COUNT = 14;

    private static final long POLL_INTERVAL_MS = 500;

    private final int captureSeconds;

    public MacOsKeyExtractor() {
        this(DEFAULT_CAPTURE_SECONDS);
    }

    public MacOsKeyExtractor(int captureSeconds) {
        this.captureSeconds = Math.max(10, captureSeconds);
    }

    @Override
    public boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac");
    }

    @Override
    public String platformName() {
        return "macOS (lldb + CommonCrypto 断点)";
    }

    @Override
    public List<byte[]> extract(Path accountDir) throws KeyExtractionException {
        preflight();

        int pid = findWeChatPid();
        if (pid <= 0) {
            throw new KeyExtractionException(
                    "未找到正在运行的微信进程",
                    "请先启动微信并登录，保持窗口打开，然后重试。");
        }
        log.info("找到微信进程 pid={}，开始抓取（时长 {}s）", pid, captureSeconds);

        Path dir = null;
        try {
            dir = Files.createTempDirectory("nexusai-wx-keys-");
            Path pyScript = dir.resolve("capture.py");
            Path outFile = dir.resolve("keys.txt");
            Files.writeString(pyScript, buildPythonCaptureScript(), StandardCharsets.UTF_8);
            Files.createFile(outFile);

            Path lldbCmd = dir.resolve("capture.lldb");
            Files.writeString(lldbCmd, buildLldbCommands(pid, captureSeconds, pyScript, outFile),
                    StandardCharsets.UTF_8);

            runLldbWithElevation(lldbCmd, dir);
            List<byte[]> keys = readCapturedKeys(outFile);

            // ⚠️ 真实事故：原先 finally 无条件删临时目录，结果「抓到了 5 个候选」
            // 但密钥随目录一起没了，用户必须重新提权再抓一次（每次还可能导致微信登出）。
            // 现在：成功后把候选<b>复制到持久位置</b>再清理临时目录。
            persistCapturedKeys(keys);
            return keys;

        } catch (IOException e) {
            throw new KeyExtractionException(
                    "准备抓取脚本失败", "检查临时目录是否可写。", e);
        } finally {
            if (dir != null) {
                deleteRecursively(dir);
            }
        }
    }

    /**
     * 把抓到的候选密钥持久化到 {@code ~/.nexusai/wechat-log/.keys}（权限 600）。
     *
     * <p>目的：致命成本在于「提权 + 微信可能登出」，所以抓到就必须留下，
     * 不能只在内存里用一次。后续 {@code sync} 会优先读这里。
     */
    private void persistCapturedKeys(List<byte[]> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Path target = Path.of(System.getProperty("user.home"),
                ".nexusai", "wechat-log", ".keys");
        try {
            Files.createDirectories(target.getParent());

            // ⚠️ 必须是**合并写**，不是覆盖写。
            // 真实事故：某次只抓到 1 把（微信当时只访问了 contact.db），
            // 覆盖写把之前辛苦抓到的 14 把全冲掉了，导致 10 个库变成「缺密钥」。
            // 密钥的获取成本极高（要提权 + 微信会卡顿），绝不能丢。
            java.util.List<String> existing = new java.util.ArrayList<>();
            if (Files.isRegularFile(target)) {
                existing.addAll(Files.readAllLines(target));
            }
            int before = countValidKeys(existing);
            // 合并逻辑抽成静态方法，便于单测直接验证（见 KeyPersistenceMergeTest）
            java.util.List<String> merged = mergeKeys(existing, keys);

            StringBuilder sb = new StringBuilder();
            for (String h : merged) {
                sb.append(h).append('\n');
            }
            Files.writeString(target, sb.toString());
            log.info("密钥候选: 原有 {} 把 + 本次 {} 把 = 合并后 {} 把",
                    before, keys.size(), merged.size());
            try {
                Files.setPosixFilePermissions(target,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
                // 非 POSIX 忽略
            }
            log.info("已把 {} 个密钥候选持久化到 {}", keys.size(), target);
        } catch (IOException e) {
            // 持久化失败不该让本次结果作废
            log.warn("持久化密钥候选失败（下次需重新抓取）: {}", e.getMessage());
        }
    }

    /**
     * 合并「已有密钥行」与「新抓的密钥」。
     *
     * <p><b>为什么单独抽出来</b>：这是被真实事故验证过的关键逻辑（一次覆盖写把 14 把冲成 1 把），
     * 必须有能直接跑的单测守住。写成静态纯函数就能脱离文件系统测试。
     *
     * <p>规则：
     * <ul>
     *   <li>保留已有的合法行（64 个十六进制字符，大小写归一）；</li>
     *   <li>追加新密钥，已存在的不重复加（幂等）；</li>
     *   <li>非法行（空行、注释、残缺）被丢弃。</li>
     * </ul>
     *
     * @param existingLines 现有文件的行
     * @param newKeys       新抓到的密钥（每个 32 字节）
     * @return 去重后的十六进制字符串列表，顺序：先历史后新增
     */
    static List<String> mergeKeys(List<String> existingLines, List<byte[]> newKeys) {
        java.util.Set<String> merged = new java.util.LinkedHashSet<>();
        if (existingLines != null) {
            for (String line : existingLines) {
                String h = line == null ? "" : line.trim().toLowerCase();
                if (h.length() == 64 && h.matches("[0-9a-f]+")) {
                    merged.add(h);
                }
            }
        }
        if (newKeys != null) {
            for (byte[] k : newKeys) {
                if (k == null || k.length != 32) {
                    continue;
                }
                StringBuilder one = new StringBuilder();
                for (byte b : k) {
                    one.append(String.format("%02x", b));
                }
                merged.add(one.toString().toLowerCase());
            }
        }
        return new java.util.ArrayList<>(merged);
    }

    /** 数一数传入的行里有几把合法密钥。 */
    static int countValidKeys(List<String> lines) {
        if (lines == null) {
            return 0;
        }
        int n = 0;
        for (String line : lines) {
            String h = line == null ? "" : line.trim().toLowerCase();
            if (h.length() == 64 && h.matches("[0-9a-f]+")) {
                n++;
            }
        }
        return n;
    }

    // ==================================================================
    // 前置检查
    // ==================================================================

    private void preflight() {
        if (!isSupported()) {
            throw new KeyExtractionException("当前不是 macOS", "此实现仅在 macOS 上可用。");
        }
        if (!isSipDisabled()) {
            throw new KeyExtractionException(
                    "系统完整性保护（SIP）未关闭，无法附加到微信进程",
                    "关闭 SIP：重启进恢复模式（Intel 按住 Command+R；Apple Silicon 长按电源键），"
                            + "终端执行 `csrutil disable` 后重启。取完密钥可用 `csrutil enable` 恢复。");
        }
        if (!Files.isExecutable(Paths.get("/usr/bin/lldb"))) {
            throw new KeyExtractionException(
                    "未找到 lldb",
                    "安装 Xcode Command Line Tools：`xcode-select --install`");
        }
        String flags = readWeChatSignFlags();
        if (flags != null && flags.contains("0x10000")) {
            // 不在这里抛异常：让用户看到「授权框 → attach 失败 → 重签名提示」的完整链路，
            // 比在启动前就报错更容易理解。真正的失败处理在 runLldbWithElevation 里。
            log.warn("微信带 Hardened Runtime 标志（{}），lldb 很可能无法附加。"
                    + "需要重签名：sudo codesign --force --deep --sign - /Applications/WeChat.app", flags);
        }
    }

    private boolean isSipDisabled() {
        try {
            Process p = new ProcessBuilder("csrutil", "status")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            p.waitFor(5, TimeUnit.SECONDS);
            return out.contains("disabled");
        } catch (Exception e) {
            log.warn("检查 SIP 状态失败，按「已关闭」继续: {}", e.getMessage());
            return true;
        }
    }

    private String readWeChatSignFlags() {
        try {
            Process p = new ProcessBuilder("codesign", "-dvvv", "/Applications/WeChat.app")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            for (String line : out.split("\n")) {
                if (line.contains("flags=")) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            log.debug("读取微信签名失败: {}", e.getMessage());
        }
        return null;
    }

    private int findWeChatPid() {
        for (String name : new String[]{"WeChat", "Weixin"}) {
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

    // ==================================================================
    // 脚本生成
    // ==================================================================

    private boolean isArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    /**
     * lldb 命令脚本：加载 Python 模块 + 调用入口函数 + <b>显式 detach</b> + quit。
     *
     * <h2>⚠️ 为什么 detach 必须写在 lldb 命令层，而不是 Python 的 process.Detach()</h2>
     * 真实事故：微信在每次抓取后都会被 SIGKILL（日志 {@code Process exited with status = 9}），
     * 用户必须重新登录。原因是我原先把退出序列写成
     * {@code process.Stop() → sleep → process.Detach()} 之后直接 {@code quit}：
     * <ul>
     *   <li>{@code process.Stop()} 给微信发 SIGSTOP，微信进入停止态；</li>
     *   <li>若 Detach 因任何原因失败（超时/异常被吞），lldb 进程退出时
     *       debugserver 会对仍 attached 的进程发 SIGKILL；</li>
     *   <li>{@code --batch} 模式下 lldb 遇到退出会直接销毁 target，不会帮你优雅分离。</li>
     * </ul>
     * 对照 chatlog（{@code internal/wechat/key/darwin/glance/glance.go}）的正确做法：
     * <b>用交互式 lldb，向 stdin 写 {@code process detach}，等 200ms，再写 {@code quit}，
     * 最后 close stdin 让 lldb 自己退出</b>。它从不调 {@code process.Stop()}，
     * 也从不 kill lldb。
     *
     * <p>因此这里改为：Python 侧只 attach + 收集（最后 {@code Continue} 让微信跑起来），
     * detach 由 lldb 命令层的 {@code process detach} 完成。
     */
    String buildLldbCommands(int pid, int seconds, Path pyScript, Path outFile) {
        // ⚠️ 真机事故（22:06 那次微信被杀）：日志只有一行
        //   `0:173: execution error: error: Process must be launched. (1)`
        // 位置 0:173 恰好是这条 `process detach`。
        // 因果：`nexusai_capture` 若因任何原因<b>提前返回/抛异常</b>（这次是函数体没跑完），
        // target 会被销毁成「空的」；此时无条件执行 `process detach` 必然报错，
        // 而 lldb 非 0 退出会让 debugserver 与目标的关联以「异常终止」收场 ⇒ 微信被杀。
        //
        // 修法：detach 必须<b>条件化</b> —— 只有当前确实存在已 attach 的进程才执行。
        // 用一个 Python 侧的小守卫命令来表达，避免 lldb 命令层无法判断状态。
        return "command script import " + pyScript.toAbsolutePath() + "\n"
                + "nexusai_capture " + pid + " " + seconds + " "
                + outFile.toAbsolutePath() + "\n"
                + "nexusai_safe_detach\n"
                + "quit\n";
    }

    /**
     * Python 抓取脚本。
     *
     * <p>设计要点：
     * <ul>
     *   <li>两个断点都设，回调里按长度 == 32 过滤；</li>
     *   <li>回调返回 False（不停止进程）⇒ 微信不会卡住；</li>
     *   <li>按架构选寄存器，错误架构取到的是垃圾值（会被后续 HMAC 校验淘汰）；</li>
     *   <li>写文件时逐条 flush，即使 lldb 被 kill 也能拿到已捕获的部分。</li>
     * </ul>
     */
    /**
     * 生成 lldb 用的 Python 抓取模块。
     *
     * <h2>⚠️ 这里踩过一个坑：不能用 {@code String.formatted()}</h2>
     * 脚本正文里本来就有 Python 的 {@code %s} / {@code %d} 格式化（打印 lldb 状态），
     * 而 Java 的 {@code .formatted()} 会把它们当成<b>自己的</b>格式符去解析，
     * 直接抛 {@code Format specifier '%s'}。
     *
     * <p>改法：正文里的 Python 格式化<b>全部改写成 f-string 风格之外的无格式拼接</b>，
     * 并且用 {@link #PLACEHOLDER} + {@code replace} 注入变量，彻底不经过 Java 的格式引擎。
     */
    String buildPythonCaptureScript() {
        boolean arm64 = isArm64();
        // CCCryptorCreate(CCOperation, CCAlgorithm, CCOptions, const void *key, size_t keyLength, ...)
        String cryptKeyReg = arm64 ? "x3" : "rcx";
        String cryptLenReg = arm64 ? "x4" : "r8";
        // CCKeyDerivationPBKDF(CCPBKDFAlgorithm, const char *password, size_t passwordLen, ...)
        String kdfKeyReg = arm64 ? "x1" : "rsi";
        String kdfLenReg = arm64 ? "x2" : "rdx";

        String script = """
                # NexusAI 微信密钥抓取（lldb Python 模块）
                # arch = __ARCH__
                import lldb
                import time

                _seen = set()
                _out = None

                def _grab(frame, key_reg, len_reg):
                    # ⚠️ 性能关键：断点回调运行在被调试进程的线程上，微信会完整暂停。
                    # 真机实测的两条教训：
                    #   1) 绝不在回调里做文件 I/O（原来每次 open+write+flush，
                    #      等于让微信为磁盘刷盘买单 ⇒ 全程卡顿）；
                    #   2) 只做「读寄存器 → 读 32 字节 → 存内存 set」三件事，
                    #      过滤集中到结束时一次性落盘。
                    try:
                        klen = frame.FindRegister(len_reg).GetValueAsUnsigned()
                        if klen != 32:
                            return False
                        kptr = frame.FindRegister(key_reg).GetValueAsUnsigned()
                        if not kptr:
                            return False
                        err = lldb.SBError()
                        data = frame.GetThread().GetProcess().ReadMemory(kptr, 32, err)
                        if not err.Success() or not data or len(data) != 32:
                            return False
                        _seen.add(data.hex())
                        # 达到目标数量就通知主循环提前收工
                        if len(_seen) >= __TARGET_KEYS__:
                            return True
                    except Exception:
                        # 单次读取失败不能让断点回调炸掉，否则微信会被挂起
                        pass
                    return False

                def _cb_crypt(frame, bp_loc, internal_dict):
                    # 返回 True 表示「命中目标，请停下进程」——
                    # 由主循环看到 stopped 状态后立刻收工，不用等满时长。
                    return _grab(frame, "__CRYPT_KEY_REG__", "__CRYPT_LEN_REG__")

                def _cb_kdf(frame, bp_loc, internal_dict):
                    return _grab(frame, "__KDF_KEY_REG__", "__KDF_LEN_REG__")

                def _log(msg):
                    # 用 print 而不是 Python 的 % 格式化，避免与任何外层格式引擎冲突
                    print(msg)

                def nexusai_capture(debugger, command, result, internal_dict):
                    # ⚠️ 整体包 try/except：任何未捕获异常都会让函数提前返回，
                    # 留下一个「半 attach」的 target，进而导致后面的 detach 报错 →
                    # lldb 非 0 退出 → 微信被杀。宁可吞掉异常也要把状态交代清楚。
                    try:
                        _do_capture(debugger, command, result, internal_dict)
                    except Exception as e:
                        _log("CAPTURE_EXCEPTION " + str(e))

                def _do_capture(debugger, command, result, internal_dict):
                    global _out
                    args = command.split()
                    if len(args) < 3:
                        _log("usage: nexusai_capture <pid> <seconds> <out_file>")
                        return
                    pid = int(args[0])
                    dur = int(args[1])
                    _out = args[2]

                    debugger.SetAsync(True)
                    target = debugger.CreateTarget("")
                    err = lldb.SBError()
                    process = target.Attach(lldb.SBAttachInfo(pid), err)
                    if not err.Success() or not process or not process.IsValid():
                        _log("ATTACH_FAIL " + str(err.GetCString() or "unknown"))
                        return
                    _log("ATTACHED pid=" + str(process.GetProcessID()))

                    # ⚠️⚠️ 条件断点：只在「密钥长度 == 32」时停下。
                    #
                    # 真机事故（23:59）：微信自己的 WCDB 看门狗把进程 abort 了 —— 日志里
                    #   thread #8, name = 'WCDB.Operation', stop reason = signal SIGABRT
                    #   frame #0: libsystem_c.dylib`__abort +176
                    # 原因是 CCCryptorCreate 是 WCDB 解密的热路径，我们<b>每次命中都暂停整个进程</b>；
                    # WCDB 的看门狗检测到操作超时 → 主动 abort。
                    #
                    # 关键优化：把「长度过滤」从 Python 回调（每次都要停下、跑一遍解释器）
                    # <b>下推到 lldb 的条件断点</b> —— lldb 在原生层评估条件，
                    # 不满足时<b>根本不会停下进程</b>，开销几乎为零。
                    # 微信里很多其它地方也会调 CCCryptorCreate（不同长度），
                    # 这些噪声调用现在完全不会打断它。
                    bp1 = target.BreakpointCreateByName("CCCryptorCreate")
                    bp1.SetCondition("__COND_CRYPT__")
                    bp1.SetScriptCallbackFunction("capture._cb_crypt")
                    _log("BP CCCryptorCreate locations=" + str(bp1.GetNumLocations())
                         + " cond=" + "__COND_CRYPT__")

                    bp2 = target.BreakpointCreateByName("CCKeyDerivationPBKDF")
                    bp2.SetCondition("__COND_KDF__")
                    bp2.SetScriptCallbackFunction("capture._cb_kdf")
                    _log("BP CCKeyDerivationPBKDF locations=" + str(bp2.GetNumLocations())
                         + " cond=" + "__COND_KDF__")

                    listener = debugger.GetListener()
                    process.Continue()
                    deadline = time.time() + dur
                    early_stop = False
                    while time.time() < deadline:
                        ev = lldb.SBEvent()
                        if listener.WaitForEvent(1, ev) and lldb.SBProcess.EventIsProcessEvent(ev):
                            st = lldb.SBProcess.GetStateFromEvent(ev)
                            if st == lldb.eStateExited:
                                _log("PROC_EXITED")
                                return
                            if st == lldb.eStateStopped:
                                # 已抓到目标数量 ⇒ 提前收工，别再让微信反复暂停
                                if len(_seen) >= __TARGET_KEYS__:
                                    early_stop = True
                                    _log("EARLY_STOP keys=" + str(len(_seen))
                                         + " elapsed=" + str(int(dur - (deadline - time.time()))) + "s")
                                    break
                                process.Continue()

                    # 结束时一次性落盘（绝不在回调里做 I/O）
                    try:
                        with open(_out, "w") as f:
                            for h in sorted(_seen):
                                f.write(h + chr(10))
                            f.flush()
                    except Exception as e:
                        _log("WRITE_FAIL " + str(e))

                    if not early_stop:
                        _log("TIMER_DONE keys=" + str(len(_seen)))
                    # ⚠️ 这里绝对不要调 process.Stop()。
                    # 真机事故：Stop() 给微信发 SIGSTOP；随后若分离失败，
                    # lldb 退出时 debugserver 会补一刀 SIGKILL（日志可见 signal 9），
                    # 用户必须重新登录微信。
                    # 正确做法（对照 chatlog glance.go）：什么都不做，
                    # 让微信保持运行态，由 lldb 命令层的 `process detach` 完成分离。
                    _log("CAPTURE_DONE keys=" + str(len(_seen)))
                    return

                def nexusai_safe_detach(debugger, command, result, internal_dict):
                    # ⚠️ 条件化 detach（真机事故修复）：
                    # 若 capture 提前返回/抛异常，target 可能是空的；此时无条件
                    # `process detach` 会报 "Process must be launched" 并让 lldb 以
                    # 非 0 退出 ⇒ debugserver 异常收场 ⇒ 被调试进程被 SIGKILL。
                    # 所以这里先判断有没有活着的进程，再决定是否 detach。
                    try:
                        target = debugger.GetSelectedTarget()
                        if not target or not target.IsValid():
                            _log("SAFE_DETACH skipped=no-target")
                            return
                        process = target.GetProcess()
                        if not process or not process.IsValid():
                            _log("SAFE_DETACH skipped=no-process")
                            return
                        st = process.GetState()
                        # eStateExited / eStateDetached / eStateInvalid 都不需要（也不能）detach
                        if st in (lldb.eStateExited, lldb.eStateDetached, lldb.eStateInvalid):
                            _log("SAFE_DETACH skipped=state-" + str(st))
                            return
                        # ⚠️⚠️ 关键：Detach 必须在**同步模式**下做。
                        #
                        # 真机事故（23:44）：日志出现
                        #   Process exited with status = -1 (lost connection)
                        #   SAFE_DETACH detached=True          ← 但进程其实已被杀
                        #
                        # 原因：capture 开始时我们调了 debugger.SetAsync(True)。
                        # 在异步模式下 SBProcess.Detach() 返回 True 只表示
                        # 「分离请求已入队」，**不代表已离开目标**。随后命令层立即
                        # `quit` 销毁 target，debugserver 在「仍 attached」状态下退出，
                        # 于是给微信补一刀 SIGKILL。
                        #
                        # 修法：先切回同步模式，Detach 会阻塞到真正分离完成；
                        # 再轮询状态确认拿到 eStateDetached/eStateExited 才返回。
                        debugger.SetAsync(False)
                        ok = process.Detach()
                        # 等状态真正变成「已分离」/「已退出」，最多 5 秒
                        deadline = time.time() + 5.0
                        st2 = process.GetState()
                        while time.time() < deadline:
                            if st2 in (lldb.eStateDetached, lldb.eStateExited,
                                       lldb.eStateInvalid):
                                break
                            time.sleep(0.05)
                            st2 = process.GetState()
                        _log("SAFE_DETACH requested=" + str(bool(ok))
                             + " final_state=" + str(st2)
                             + " (detached=" + str(st2 == lldb.eStateDetached)
                             + ", exited=" + str(st2 == lldb.eStateExited) + ")")
                    except Exception as e:
                        # 任何异常都不能让 lldb 以错误退出
                        _log("SAFE_DETACH_ERROR " + str(e))

                def __lldb_init_module(debugger, internal_dict):
                    debugger.HandleCommand(
                        "command script add -f capture.nexusai_capture nexusai_capture")
                    debugger.HandleCommand(
                        "command script add -f capture.nexusai_safe_detach nexusai_safe_detach")
                """;

        // 条件断点表达式：密钥/密码长度为 32 才停。
        // x86_64: CCCryptorCreate 的 keyLength 是第 5 个整型参数 → r8
        //         CCKeyDerivationPBKDF 的 passwordLen 是第 3 个 → rdx
        // arm64:  分别对应 x4 / x2
        String condCrypt = arm64 ? "$x4 == 32" : "$r8 == 32";
        String condKdf = arm64 ? "$x2 == 32" : "$rdx == 32";

        return script
                .replace("__COND_CRYPT__", condCrypt)
                .replace("__COND_KDF__", condKdf)
                .replace("__TARGET_KEYS__", String.valueOf(TARGET_KEY_COUNT))
                .replace("__ARCH__", arm64 ? "arm64" : "x86_64")
                .replace("__CRYPT_KEY_REG__", cryptKeyReg)
                .replace("__CRYPT_LEN_REG__", cryptLenReg)
                .replace("__KDF_KEY_REG__", kdfKeyReg)
                .replace("__KDF_LEN_REG__", kdfLenReg);
    }

    // ==================================================================
    // 提权执行
    // ==================================================================

    private void runLldbWithElevation(Path lldbCmdFile, Path workDir) {
        String lldbCmd = "/usr/bin/lldb --batch -s "
                + shellQuote(lldbCmdFile.toAbsolutePath().toString());
        String escaped = lldbCmd.replace("\\", "\\\\").replace("\"", "\\\"");
        String applescript = "do shell script \"" + escaped
                + "\" with administrator privileges";

        log.info("请求管理员授权以运行 lldb（将弹出系统密码框，请点「好」并输入本机密码）");

        // ⚠️ 实时输出（真实事故）：lldb 的输出必须边跑边显示，
        // 否则用户面对 90 秒全黑，不知道什么时候该去微信里滚动聊天记录。
        // 关键：osascript 的 `do shell script` 会阻塞到命令结束，
        // 所以这里必须用「后台线程读 stdout + 主线程等进程」而不是 readAllBytes()。
        try {
            Process p = new ProcessBuilder("osascript", "-e", applescript)
                    .redirectErrorStream(true).start();

            // 把 lldb 的每一行实时透传到 stderr（stderr 不缓冲，终端立刻可见）
            Thread pump = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println("[lldb] " + line);
                    }
                } catch (IOException ignored) {
                    // 进程结束时流关闭，正常
                }
            }, "lldb-output-pump");
            pump.setDaemon(true);
            pump.start();

            long start = System.currentTimeMillis();
            boolean done = p.waitFor(captureSeconds + 90L, TimeUnit.SECONDS);
            long elapsed = (System.currentTimeMillis() - start) / 1000;

            if (!done) {
                p.destroyForcibly();
                throw new KeyExtractionException(
                        "lldb 执行超时（已 " + elapsed + "s）",
                        "微信窗口可能被挂起。确认微信仍可操作后重试。");
            }
            pump.join(2000);

            int code = p.exitValue();
            if (code != 0) {
                log.warn("osascript 退出码 {}（耗时 {}s）", code, elapsed);
                throw new KeyExtractionException(
                        "lldb 执行失败，退出码 " + code,
                        "常见原因：授权被取消（重试并点「好」）、"
                                + "微信未重签名（sudo codesign --force --deep --sign - /Applications/WeChat.app），"
                                + "或 SIP 未关闭。");
            }
            log.info("lldb 抓取结束，耗时 {}s", elapsed);
        } catch (IOException e) {
            throw new KeyExtractionException(
                    "启动 osascript 失败", "确认系统允许执行 osascript（未被安全软件拦截）。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeyExtractionException("等待授权被中断", "请重试。", e);
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
                    "抓取期间请在微信里点开几个会话并上下滚动聊天记录，让微信真正去解密数据库页。"
                            + "若微信已长时间登录、密钥早被缓存，请完全退出登录再重新登录以强制重新派生。");
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
                    // 清理失败不影响主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
