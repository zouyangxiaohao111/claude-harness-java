package com.nexusai.wechatlog.key;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.VerRsrc;
import com.sun.jna.platform.win32.VersionUtil;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Windows 取密钥：<b>只读</b>扫描微信进程内存，沿 WCDB 的 {@code Config.Cipher}
 * 结构体链取出配置 blob，再解析「十六进制密钥 + 嵌入 salt」候选。
 *
 * <h2>扫描策略（2026-11-22 修正）</h2>
 * 旧实现把预算写死 256MB 且按地址升序扫，结果在到达 {@code Weixin.dll}
 * （镜像区、特征串在其 +146MB 处）之前就耗尽预算，静默返回 0 候选。
 * 现在：镜像区优先、预算默认 2GB 可覆盖、耗尽时可观测（见 {@link MemoryScanner}）。
 *
 * <h2>版本适配</h2>
 * 偏移按版本成表（{@link WeChatOffsetTable}）。已验证版本用对应链；未验证版本
 * best-effort 尝试最近的链，失败时明确报「版本未适配 + 版本号」，不再当成没有候选。
 *
 * <h2>安全边界</h2>
 * 只请求 {@code PROCESS_VM_READ | PROCESS_QUERY_INFORMATION}，绝不写对方进程；
 * 不落盘内存内容；候选只在内存里过一遍就交给 {@code KeyMatcher}。
 */
public class WindowsKeyExtractor implements WeChatKeyExtractor {

    private static final Logger log = LoggerFactory.getLogger(WindowsKeyExtractor.class);

    /** WCDB 配置类的类型名字符串（版本无关；4.2.0.53 磁盘上仍存在）。 */
    private static final byte[] CONFIG_CIPHER_NAME =
            "com.Tencent.WCDB.Config.Cipher".getBytes(StandardCharsets.US_ASCII);

    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_VM_READ = 0x0010;

    /** 版本探测（可注入，便于单测失败分类）。 */
    @FunctionalInterface
    public interface VersionDetector {
        String detect(int pid);
    }

    private final VersionDetector versionDetector;
    private final WeChatOffsetTable offsetTable;
    private final long maxScanBytes;

    public WindowsKeyExtractor() {
        this(WindowsKeyExtractor::detectVersionViaJna,
                WeChatOffsetTable.defaults(),
                MemoryScanner.DEFAULT_MAX_TOTAL_SCAN_BYTES);
    }

    public WindowsKeyExtractor(VersionDetector versionDetector,
                               WeChatOffsetTable offsetTable,
                               long maxScanBytes) {
        this.versionDetector = versionDetector;
        this.offsetTable = offsetTable;
        this.maxScanBytes = maxScanBytes;
    }

    @Override
    public boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    @Override
    public String platformName() {
        return "Windows (只读内存扫描 · Config.Cipher 结构体链)";
    }

    @Override
    public List<byte[]> extract(Path accountDir) throws KeyExtractionException {
        if (!isSupported()) {
            throw new KeyExtractionException("当前不是 Windows", "此实现仅在 Windows 上可用。");
        }

        int pid = findWeChatPid();
        if (pid <= 0) {
            throw new KeyExtractionException(
                    "未找到正在运行的微信进程（Weixin.exe / WeChat.exe）",
                    "请先启动微信并登录（窗口不必可见），然后重试。",
                    KeyExtractionException.Kind.NO_PROCESS);
        }

        String version = versionDetector == null ? null : versionDetector.detect(pid);
        var resolution = offsetTable.resolve(version);
        if (resolution.isEmpty()) {
            throw new KeyExtractionException(
                    "当前微信版本未适配（" + (version == null ? "未知" : version) + "）",
                    "没有可用于该版本的偏移链。可在 ~/.nexusai/wechat-log/wechat-offsets.properties "
                            + "添加该版本偏移后重试；或先用企业微信/飞书渠道。",
                    KeyExtractionException.Kind.VERSION_UNSUPPORTED);
        }
        WeChatOffsetChain chain = resolution.get().chain();
        log.info("找到微信进程 pid={}，版本={}，偏移链={}（{}），开始只读内存扫描",
                pid, version, chain.versionKey(),
                resolution.get().verified() ? "已验证" : "未验证，best-effort");

        WinNT.HANDLE handle;
        try {
            handle = Kernel32.INSTANCE.OpenProcess(
                    PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, pid);
        } catch (Win32Exception e) {
            throw new KeyExtractionException(
                    "打开微信进程失败: " + e.getMessage(),
                    "以管理员身份重新运行 NexusAI（读取微信进程内存需要提权）。",
                    KeyExtractionException.Kind.PERMISSION, e);
        }
        if (handle == null) {
            throw new KeyExtractionException(
                    "打开微信进程失败",
                    "以管理员身份重新运行 NexusAI，或确认微信确实在运行。",
                    KeyExtractionException.Kind.PERMISSION);
        }

        try {
            List<MemoryScanner.Region> regions = enumReadableRegions(handle);
            long imageCount = regions.stream().filter(MemoryScanner.Region::image).count();
            log.info("可读内存区域 {} 个（其中镜像区 {} 个）", regions.size(), imageCount);
            MemoryScanner.ScanOutcome outcome = MemoryScanner.scan(
                    regions,
                    (address, size) -> readMemory(handle, address, size),
                    CONFIG_CIPHER_NAME,
                    chain,
                    maxScanBytes,
                    log::info);

            if (!outcome.candidates().isEmpty()) {
                return outcome.candidates();
            }
            throw classifyFailure(outcome, resolution.get(), version);
        } finally {
            try {
                Kernel32.INSTANCE.CloseHandle(handle);
            } catch (Exception ignored) {
                // 关闭失败无副作用
            }
        }
    }

    /**
     * 0 候选时的失败归因（修「无权限和版本不支持甩同一句话」的核心）。
     *
     * <ul>
     *   <li>预算耗尽 ⇒ 扫描覆盖不足；</li>
     *   <li>偏移链未验证 ⇒ 版本未适配 + 版本号；</li>
     *   <li>其余 ⇒ 真的没解析出候选（可能需要在微信里打开会话触发初始化）。</li>
     * </ul>
     */
    static KeyExtractionException classifyFailure(MemoryScanner.ScanOutcome outcome,
                                                   WeChatOffsetTable.Resolution resolution,
                                                   String version) {
        if (outcome.budgetExhausted()) {
            return new KeyExtractionException(
                    "内存扫描未覆盖到微信特征数据（扫描预算不足）",
                    String.format(Locale.ROOT,
                            "已扫描 %.1f MB，仍有 %.1f MB 未扫描。"
                                    + "可用 -Dnexusai.wechat.scan.maxBytes 提高预算后重试。",
                            outcome.scannedBytes() / 1024.0 / 1024.0,
                            outcome.skippedBytes() / 1024.0 / 1024.0),
                    KeyExtractionException.Kind.SCAN_BUDGET_EXHAUSTED);
        }
        if (!resolution.verified()) {
            return new KeyExtractionException(
                    "当前微信版本未适配（" + (version == null ? "未知" : version) + "）",
                    "已按 " + resolution.matchedKey() + " 的偏移链尝试但未取到密钥。"
                            + "可在 ~/.nexusai/wechat-log/wechat-offsets.properties 添加该版本偏移；"
                            + "或先用企业微信/飞书渠道。",
                    KeyExtractionException.Kind.VERSION_UNSUPPORTED);
        }
        return new KeyExtractionException(
                "未能从微信进程解析出密钥候选",
                "请先在微信里打开几个会话并滚动聊天记录后重试（配置对象可能尚未初始化）；"
                        + "若持续失败，请确认微信版本为 " + resolution.matchedKey()
                        + "，或改用企业微信/飞书渠道。",
                KeyExtractionException.Kind.NO_CANDIDATES);
    }

    // ==================================================================
    // 内存区域 / 版本
    // ==================================================================

    /** 枚举可读的已提交内存区域（含 {@code MEM_IMAGE} 标记）。 */
    List<MemoryScanner.Region> enumReadableRegions(WinNT.HANDLE handle) {
        List<MemoryScanner.Region> out = new ArrayList<>();
        long address = 0;
        WinNT.MEMORY_BASIC_INFORMATION mbi = new WinNT.MEMORY_BASIC_INFORMATION();
        long mbiSize = mbi.size();

        while (true) {
            BaseTSD.SIZE_T written = Kernel32.INSTANCE.VirtualQueryEx(
                    handle, Pointer.createConstant(address), mbi,
                    new BaseTSD.SIZE_T(mbiSize));
            if (written == null || written.longValue() == 0) {
                break;
            }
            long regionSize = mbi.regionSize.longValue();
            long baseAddr = Pointer.nativeValue(mbi.baseAddress);
            if (regionSize <= 0) {
                break;
            }
            int state = mbi.state.intValue();
            int protect = mbi.protect.intValue();
            boolean readable = (protect & WinNT.PAGE_NOACCESS) == 0
                    && (protect & WinNT.PAGE_GUARD) == 0;
            boolean image = (mbi.type.intValue() & WinNT.MEM_IMAGE) != 0;
            if (state == WinNT.MEM_COMMIT && readable
                    && baseAddr < MemoryScanner.MAX_USER_ADDRESS) {
                out.add(new MemoryScanner.Region(baseAddr, regionSize, image));
            }
            long next = baseAddr + regionSize;
            if (next <= address) {
                break;
            }
            address = next;
        }
        return out;
    }

    /** 只读读一段内存；失败返回 null。 */
    private byte[] readMemory(WinNT.HANDLE handle, long address, int size) {
        if (size <= 0) {
            return null;
        }
        try {
            Memory mem = new Memory(size);
            IntByReference read = new IntByReference(0);
            boolean ok = Kernel32.INSTANCE.ReadProcessMemory(
                    handle, Pointer.createConstant(address), mem, size, read);
            if (!ok || read.getValue() <= 0) {
                return null;
            }
            return mem.getByteArray(0, read.getValue());
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取微信进程 exe 的文件版本；失败返回 null（走 best-effort 链）。 */
    static String detectVersionViaJna(int pid) {
        try {
            String exe = Kernel32Util.QueryFullProcessImageName(pid, 0);
            if (exe == null || exe.isBlank()) {
                return null;
            }
            VerRsrc.VS_FIXEDFILEINFO info = VersionUtil.getFileVersionInfo(exe);
            if (info == null) {
                return null;
            }
            int ms = info.dwFileVersionMS.intValue();
            int ls = info.dwFileVersionLS.intValue();
            return ((ms >>> 16) & 0xffff) + "." + (ms & 0xffff) + "."
                    + ((ls >>> 16) & 0xffff) + "." + (ls & 0xffff);
        } catch (Throwable t) {
            log.info("读取微信版本失败（将按未验证链 best-effort）: {}", t.getMessage());
            return null;
        }
    }

    // ==================================================================
    // 进程检测
    // ==================================================================

    /**
     * 本机是否正在运行微信客户端。
     *
     * <p>提权前先做这一步，避免「微信没开」也弹一次 UAC。
     * 判据与 {@link #extract} 完全同一条（{@code tasklist} 进程名过滤）。
     */
    public static boolean isWeChatProcessRunning() {
        return findWeChatPid() > 0;
    }

    static int findWeChatPid() {
        for (String exe : new String[]{"Weixin.exe", "WeChat.exe"}) {
            try {
                Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + exe,
                        "/FO", "CSV", "/NH").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                for (String line : out.split("\n")) {
                    if (!line.contains(exe)) {
                        continue;
                    }
                    String[] parts = line.split("\",\"");
                    if (parts.length >= 2) {
                        return Integer.parseInt(parts[1].replace("\"", "").trim());
                    }
                }
            } catch (Exception e) {
                log.debug("查找 {} 失败: {}", exe, e.getMessage());
            }
        }
        return -1;
    }
}
