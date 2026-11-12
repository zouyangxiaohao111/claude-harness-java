package com.nexusai.kb.app.parser;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
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
import java.util.Map;

/**
 * anydoc 原生库（C ABI）的 JNA 绑定层 · 把 Office 文档转成结构化 Markdown。
 *
 * <p><b>为什么用 JNA 而不是 JNI</b>：anydoc 是 Rust 的 {@code cdylib}（Cargo 的 {@code crate-type =
 * ["staticlib", "cdylib"]}），导出的是纯 C ABI（{@code include/anydoc.h}）。用 JNA 只需一个
 * {@link Library} 接口声明，<b>零构建期工具链</b>：不用写 {@code .h} 胶水、不用在本机为 win/mac/linux
 * 三平台各编一份 JNI 库、也不会有「JNI 库与 JVM 位数/ABI 不匹配导致进程直接 abort」的风险。
 * 代价是每次调用多一层 JNA 的 trivial 封送开销 —— 但本类一次调用就是「一份文档转 Markdown」，
 * 开销相对解析本身可忽略。
 *
 * <p><b>为什么加载必须兜底</b>：打包后进程的 {@code user.dir} 是<b>数据根</b>（{@code ~/.nexusai}，
 * 见 {@code front/src-tauri/src/backend.rs} 的 {@code current_dir(&data_root)}），而 {@code anydoc_go.dll}
 * 随 jar 一起躺在<b>资源目录</b> {@code <resource_dir>/backend/}。JNA 的 {@code Native.load("anydoc_go")}
 * 只按 {@code jna.library.path} / 应用目录 / 系统 PATH 搜索，<b>不会去 jar 旁边找</b>。因此这里按
 * 「库名 → jar 所在目录 → user.dir → user.dir/backend」逐级试，任何一级成功即用。
 *
 * <p><b>全失败不崩应用</b>：{@link #isAvailable()} 返回 false 并留下 {@link #unavailableReason()}，
 * {@link KbAnydocParser#supports} 据此放弃接管，注册表把文件路由给它旁边的内置解析器 ——
 * 一个可选增强能力不该让整个后端起不来。
 *
 * <p><b>字符串编码（真机坑）</b>：Rust 侧对路径做 {@code CStr::to_str()}，<b>只接受 UTF-8</b>；
 * 而 Windows 中文系统的 JVM 默认字符集是 GBK ⇒ 不显式指定 {@link Library#OPTION_STRING_ENCODING}
 * 时，中文文件名会被按 GBK 编码成非法 UTF-8 字节，anydoc 直接返回 {@code ERR_INVALID_ARG=8}。
 * 故加载时固定 UTF-8，并且返回的 Markdown 也按 UTF-8 解码（不依赖平台默认字符集）。
 *
 * <p><b>线程安全</b>：{@code Native.load} 得到的 interface 实例是线程安全的（JNA 内部共享
 * {@code NativeLibrary} 句柄），可跨请求并发调用。但 <b>anydoc 返回的每个 {@code char*} 都必须由
 * 调用方 {@code anydoc_string_free} 释放</b>（Rust 侧是 {@code CString::into_raw}，JVM GC 管不到），
 * 故所有取字符串的地方一律 {@code try/finally} 配对释放 —— 漏一次就是一次原生内存泄漏。
 */
final class AnydocNative {

    private static final Logger log = LoggerFactory.getLogger(AnydocNative.class);

    /** 原生库基名（JNA 会自行按平台补 {@code .dll/.dylib/.so} 前缀后缀）。 */
    private static final String LIBRARY_BASE_NAME = "anydoc_go";

    /** Windows 随包文件名。 */
    private static final String LIBRARY_FILE_WINDOWS = "anydoc_go.dll";

    /** macOS 随包文件名。 */
    private static final String LIBRARY_FILE_MAC = "libanydoc_go.dylib";

    /** Linux 随包文件名。 */
    private static final String LIBRARY_FILE_LINUX = "libanydoc_go.so";

    /**
     * anydoc crate 版本（{@code Cargo.toml} 的 {@code anydoc = "=0.2.4"}）。
     *
     * <p>只用于<b>缓存目录隔离</b>：升级库后换个版本号，旧缓存自动作废、重新提取。
     * 与 {@code RipgrepRunner.RG_VERSION} 同一用意。
     */
    static final String ANYDOC_VERSION = "0.2.4";

    /**
     * 内置原生库所在的 classpath 前缀（对齐 {@code RipgrepRunner} 的 {@code /rg/}）。
     *
     * <p>布局：{@code resources/anydoc/<arch>-<os>/<libname>}，六平台命名与 rg 完全一致：
     * {@code x64-win32} / {@code x64-darwin} / {@code x64-linux} / {@code arm64-win32} /
     * {@code arm64-darwin} / {@code arm64-linux}。
     *
     * <p><b>⚠️ 目前只随包了 {@code x64-win32}</b>：Rust {@code cdylib} 必须在其目标平台上链接 ——
     * macOS 需要 Apple SDK（且系统对 dylib 有签名要求），Linux 需要 glibc sysroot；
     * musl 目标则<b>根本不支持 cdylib</b>（Rust 会丢弃该 crate-type，只出 {@code .a} 静态库）。
     * ⇒ 其余平台的目录需要在对应平台的构建机（或 CI）上用同一份
     * {@code third_party/anydoc-go} 编出后放进来，本仓代码不用改。
     */
    private static final String BUNDLED_RESOURCE_PREFIX = "/anydoc/";

    /**
     * 当前平台的库文件名 · 按 {@code os.name} 推导。
     *
     * <p>⚠️ 候选路径与失败文案都必须用<b>同一个</b>推导结果：写死 {@code anydoc_go.dll}
     * 会让 Mac/Linux 的「已尝试路径」列出根本不可能存在的 Windows 文件名，
     * 排查时把人带偏。
     */
    static String libraryFileName() {
        return libraryFileNameFor(System.getProperty("os.name", ""));
    }

    /** {@code ANYDOC_FORMAT_NONE}（{@code anydoc.h}）：格式未识别。 */
    static final int FORMAT_NONE = -1;

    /** 加载只做一次；用独立锁对象避免与类初始化锁纠缠。 */
    private static final Object LOAD_LOCK = new Object();

    /** 已加载的库句柄；null = 不可用（原因见 {@link #failureReason}）。 */
    private static volatile AnydocLib loaded;
    /** 不可用原因（仅在 {@link #loaded} 为 null 时有意义）。 */
    private static volatile String failureReason;
    /** 是否已经尝试过加载（避免每次调用都重扫一遍文件系统）。 */
    private static volatile boolean loadAttempted;

    private AnydocNative() {
    }

    /**
     * anydoc C ABI（逐字对应 {@code include/anydoc.h} 的 4 个函数）。
     *
     * <p>只映射本仓用到的子集：{@code to_markdown} / {@code string_free} / {@code last_error} /
     * {@code format_from_path}。其余导出（{@code to_markdown_bytes} / {@code to_document} /
     * {@code buffer_free} …）本仓不用 —— 入库链拿到的永远是磁盘文件，{@code to_document} 出的二进制
     * 文档模型也没有下游消费者。
     *
     * <p>{@code uintptr_t} 在 64 位 JVM 上是 8 字节，故用 {@link LongByReference}（本仓 JDK 25 仅 64 位）。
     */
    interface AnydocLib extends Library {

        /**
         * 文件 → Markdown。成功时 {@code *out_str} 为堆分配字符串、{@code *out_len} 为其<b>字节</b>长度。
         *
         * @return {@code ERR_OK=0} 成功；其余见 {@link #errorName(int)}
         */
        int anydoc_to_markdown(String path, PointerByReference outStr, LongByReference outLen);

        /** 释放 {@code anydoc_to_markdown*} 与 {@code anydoc_last_error} 返回的字符串（null 为空操作）。 */
        void anydoc_string_free(Pointer s);

        /** 当前线程最后一次错误的可读描述（新分配的字符串，必须 {@code string_free}）。 */
        Pointer anydoc_last_error();

        /** 按路径扩展名判格式，把格式 tag 写入 {@code *out}（未识别写 {@code ANYDOC_FORMAT_NONE}）。 */
        int anydoc_format_from_path(String path, IntByReference out);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 能力判定
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 原生库是否可用（加载过且成功）。
     *
     * <p>首次调用会真正尝试加载；之后读缓存。加载失败<b>不抛异常</b> —— 调用方（{@code supports()}）
     * 靠 false 决定「不接管这批文件」。
     */
    static boolean isAvailable() {
        return library() != null;
    }

    /** 不可用原因（可用时为空串；恒非 null）。 */
    static String unavailableReason() {
        if (library() != null) {
            return "";
        }
        String reason = failureReason;
        return reason == null ? "anydoc 原生库不可用（原因未知）" : reason;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 转换
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 把一份文档转成 Markdown。
     *
     * @param file 待转换文件（任意编码的文件名都支持：路径按 UTF-8 传给原生层）
     * @return Markdown（可能为空串 = 文档没有可转换内容；恒非 null）
     * @throws AnydocException 原生库不可用、调用异常，或 anydoc 返回非 0 错误码
     */
    static String toMarkdown(Path file) throws AnydocException {
        AnydocLib lib = requireLibrary();
        if (file == null) {
            throw new AnydocException(-1, "anydoc 转换失败：file 为 null");
        }
        PointerByReference outStr = new PointerByReference();
        LongByReference outLen = new LongByReference();
        int rc;
        try {
            rc = lib.anydoc_to_markdown(file.toAbsolutePath().toString(), outStr, outLen);
        } catch (Throwable t) {
            // 原生层段错误之外的异常（UnsatisfiedLinkError / IllegalArgument 等）也要转成受检异常，
            // 否则解析链会以 Error 形式穿透 fail-soft 的 catch
            throw new AnydocException(-1, "anydoc_to_markdown 调用异常: " + t, t);
        }
        if (rc != 0) {
            throw new AnydocException(rc, describeFailure(lib, rc));
        }
        Pointer text = outStr.getValue();
        if (text == null) {
            return "";
        }
        try {
            long len = outLen.getValue();
            if (len < 0 || len > Integer.MAX_VALUE) {
                throw new AnydocException(rc, "anydoc 返回的文本长度异常: " + len);
            }
            // 按字节长度解码：Rust 侧写的是 UTF-8 字节，⛔ 不要 new String(bytes)（平台默认 GBK 会乱码）
            return new String(text.getByteArray(0, (int) len), StandardCharsets.UTF_8);
        } finally {
            // 必须配对释放（Rust 侧 into_raw，GC 不管）
            freeQuietly(lib, text);
        }
    }

    /**
     * 按路径扩展名探测 anydoc 的格式 tag（诊断用；{@link #FORMAT_NONE} = 未识别）。
     *
     * <p>仅为把 ABI 的 4 个函数全部绑定完整、并在排障时能回答「anydoc 到底认不认这个后缀」。
     * 生产解析链不依赖它：真正的格式判定由 {@code anydoc_to_markdown} 内部先看文件内容、
     * 再看扩展名兜底完成。
     *
     * @throws AnydocException 原生库不可用或调用失败
     */
    static int formatTagOf(Path file) throws AnydocException {
        AnydocLib lib = requireLibrary();
        if (file == null) {
            throw new AnydocException(-1, "anydoc_format_from_path 失败：file 为 null");
        }
        IntByReference out = new IntByReference(FORMAT_NONE);
        int rc;
        try {
            rc = lib.anydoc_format_from_path(file.toAbsolutePath().toString(), out);
        } catch (Throwable t) {
            throw new AnydocException(-1, "anydoc_format_from_path 调用异常: " + t, t);
        }
        if (rc != 0) {
            throw new AnydocException(rc, describeFailure(lib, rc));
        }
        return out.getValue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 加载
    // ════════════════════════════════════════════════════════════════════════

    /** 取已加载的库；不可用时抛 {@link AnydocException}（message 带原因，便于上层写进 metadata）。 */
    private static AnydocLib requireLibrary() throws AnydocException {
        AnydocLib lib = library();
        if (lib == null) {
            throw new AnydocException(-1, unavailableReason());
        }
        return lib;
    }

    /** 惰性加载（双检 + 独立锁）。加载失败只记原因，不抛。 */
    private static AnydocLib library() {
        if (!loadAttempted) {
            synchronized (LOAD_LOCK) {
                if (!loadAttempted) {
                    try {
                        loadInternal();
                    } catch (Throwable t) {
                        // 连 throwable 兜住：加载器可能抛 UnsatisfiedLinkError/ExceptionInInitializerError，
                        // 一个可选增强能力不该让 Spring 上下文或首次上传 500
                        loaded = null;
                        failureReason = clip("anydoc 原生库加载异常: " + briefMessage(t));
                        log.warn("AnydocNative: 加载失败（anydoc 引擎将不可用）原因={}", t.toString());
                    } finally {
                        loadAttempted = true;
                    }
                }
            }
        }
        return loaded;
    }

    /** 真实加载逻辑：内置资源（主）→ 库名 → 候选绝对路径（兜底）。 */
    private static void loadInternal() {
        String osName = System.getProperty("os.name", "");
        String libFile = libraryFileName();
        List<String> tried = new ArrayList<>();
        /** 当前平台是否随包了内置库（false ⇒ 失败文案要说「未随包」而不是「找不到」）。 */
        boolean bundledForPlatform = true;

        // ── 1. 随包内置（classpath → 临时目录）· 与 RipgrepRunner 同一套机制 ──
        //    这是**安装包的真实形态**：库随 jar 走，不依赖用户把文件放到哪个目录。
        try {
            Path bundled = extractBundledLibrary();
            if (bundled != null) {
                try {
                    AnydocLib lib = Native.load(bundled.toAbsolutePath().toString(), AnydocLib.class, loadOptions());
                    loaded = lib;
                    log.info("AnydocNative: 已加载内置原生库 {}（平台 {}）", bundled, platformDirName());
                    return;
                } catch (Throwable t) {
                    // 资源在但加载失败（架构不符 / 依赖缺失）⇒ 继续走兜底，并把原因记下来
                    tried.add("内置 " + bundled + "：" + briefMessage(t));
                }
            } else {
                bundledForPlatform = false;
                tried.add("内置 " + resourceName() + "（当前平台未随包）");
            }
        } catch (IOException e) {
            tried.add("提取内置库失败：" + e.getMessage());
        }

        // ── 2. 按库名加载：JNA 搜索 jna.library.path → 应用目录 → 系统 PATH → 当前目录 ──
        //    Native.load 本身跨平台（JNA 自动补 .dll / lib*.dylib / lib*.so 前后缀），保留这一级。
        try {
            AnydocLib lib = Native.load(LIBRARY_BASE_NAME, AnydocLib.class, loadOptions());
            loaded = lib;
            log.info("AnydocNative: 已按库名加载 {}（os={} 搜索路径含 jna.library.path / PATH / 当前目录）",
                LIBRARY_BASE_NAME, osName);
            return;
        } catch (Throwable t) {
            tried.add("按库名 " + LIBRARY_BASE_NAME + "：" + briefMessage(t));
        }

        // ── 2. 按已知候选绝对路径加载（打包后 jar 与原生库同目录，这是真正生效的一级） ──
        for (Path candidate : candidatePaths()) {
            if (!Files.isRegularFile(candidate)) {
                tried.add(candidate + "（不存在）");
                continue;
            }
            try {
                AnydocLib lib = Native.load(candidate.toAbsolutePath().toString(), AnydocLib.class, loadOptions());
                loaded = lib;
                log.info("AnydocNative: 已按绝对路径加载 {}", candidate.toAbsolutePath());
                return;
            } catch (Throwable t) {
                tried.add(candidate + "：" + briefMessage(t));
            }
        }

        failureReason = bundledForPlatform
            ? clip("未找到可加载的 " + libFile + "（os=" + osName + "）；已尝试 —— "
                + String.join("；", tried))
            : clip("当前平台（" + platformDirName() + "）未随包内置 " + libFile
                + " —— anydoc 目前只随包了 x64-win32 的产物（Rust cdylib 必须在目标平台上链接："
                + "macOS 需 Apple SDK、Linux 需 glibc sysroot、musl 不支持 cdylib）。"
                + "如需在本平台启用，请按 front/scripts/build-anydoc.md 编译后放入 "
                + "backend/src/main/resources/anydoc/" + platformDirName() + "/ 再重新打包；"
                + "日志里有全部尝试明细。");
        log.warn("AnydocNative: {}（anydoc 引擎将不可用，Office 文档回落内置 Office 解析）", failureReason);
    }

    /**
     * 把 JNA 的加载异常压成一句人话。
     *
     * <p><b>WHY 必须压</b>：{@code UnsatisfiedLinkError} 的消息里带有<b>整条 classpath</b>
     * （实测本仓 22KB 的「Native library … not found in resource path (…)」）。这个串会进
     * {@code unavailableReason}，再被前端下拉当提示渲染 —— 不压等于把一屏 classpath 甩给用户。
     *
     * <p><b>压缩规则</b>：按行切 → 丢掉只有壳子的 {@code Unable to load library 'x':} 行 →
     * 保持顺序去重（JNA 会为每个搜索位置重复一遍同样的「找不到指定的模块」）→
     * 取前两行用空格接起来 → 在 {@code Native library (} / {@code resource path (} 处截断 → 限长。
     * 真实情形下结果就是一句「找不到指定的模块。」。
     */
    private static String briefMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = String.valueOf(t.getMessage());
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        java.util.LinkedHashSet<String> lines = new java.util.LinkedHashSet<>();
        for (String raw : msg.lines().toList()) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Unable to load library")) {
                // 这一行只是 JNA 的套壳标题（以冒号结尾、正文在下一行），对用户无信息量
                continue;
            }
            lines.add(line);
            if (lines.size() >= 2) {
                break;
            }
        }
        String joined = lines.isEmpty() ? msg.trim().lines().findFirst().orElse("") : String.join(" ", lines);
        int cut = indexOfAny(joined, "Native library (", "resource path (");
        return clip(cut > 0 ? joined.substring(0, cut).trim() : joined);
    }

    private static int indexOfAny(String s, String... needles) {
        int best = -1;
        for (String n : needles) {
            int i = s.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    /** 原因串上限（前端提示要能一屏读完；完整明细在日志里）。 */
    private static final int MAX_REASON_CHARS = 400;

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_REASON_CHARS ? s : s.substring(0, MAX_REASON_CHARS) + "…";
    }

    /** JNA 加载选项 · 固定 UTF-8 字符串编码（见类 javadoc 的「字符串编码」一节）。 */
    private static Map<String, ?> loadOptions() {
        return Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name());
    }

    /**
     * 候选路径（按优先级，已去重）· 文件名按当前平台推导（{@link #libraryFileName()}）：
     * <ol>
     *   <li>{@code <jar 所在目录>/<平台库名>} —— 打包后即 {@code <resource_dir>/backend/}，
     *       与 jar/jre 同级，这是真实安装包里的常态</li>
     *   <li>{@code <user.dir>/<平台库名>} —— dev 下把库放在启动目录</li>
     *   <li>{@code <user.dir>/backend/<平台库名>} —— 从仓库根启动（{@code java -jar backend/...}）时</li>
     * </ol>
     */
    private static List<Path> candidatePaths() {
        String libFile = libraryFileName();
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        Path jarDir = jarDirectory();
        if (jarDir != null) {
            candidates.add(jarDir.resolve(libFile).toAbsolutePath().normalize());
        }
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        candidates.add(userDir.resolve(libFile));
        candidates.add(userDir.resolve("backend").resolve(libFile));
        return new ArrayList<>(candidates);
    }

    /**
     * 本类所在 classpath 条目的目录（jar → jar 的父目录；{@code target/classes} → 该目录）。
     * 取不到（自定义 ClassLoader / 无 CodeSource）→ null，调用方跳过这一级。
     */
    private static Path jarDirectory() {
        try {
            java.security.CodeSource cs = AnydocNative.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path location = Paths.get(cs.getLocation().toURI());
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (Exception e) {
            log.debug("AnydocNative: 无法定位 jar 所在目录（跳过该候选路径）原因={}", e.toString());
            return null;
        }
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** 平台的库文件名推导（供测试核跨平台口径；生产只用 {@link #libraryFileName()}）。 */
    static String libraryFileNameFor(String osName) {
        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("windows")) {
            return LIBRARY_FILE_WINDOWS;
        }
        if (lower.startsWith("mac") || lower.contains("darwin")) {
            return LIBRARY_FILE_MAC;
        }
        return LIBRARY_FILE_LINUX;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 平台标识（与 RipgrepRunner 逐字同口径：<arch>-<os>，即 Node 的 process.arch-platform）
    // ════════════════════════════════════════════════════════════════════════

    /** {@code os.name} → {@code win32} / {@code darwin} / {@code linux}（对齐 {@code process.platform}）。 */
    static String resolveOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        return "linux";
    }

    /** {@code os.arch} → {@code x64} / {@code arm64}（对齐 {@code process.arch}）。 */
    static String resolveArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        // amd64 / x86_64 / x64 / 以及未知值都归 x64：
        // 未知架构下二进制必然缺失，由「未随包」文案兜底（同 RipgrepRunner.resolveArch）
        return "x64";
    }

    /** 平台目录名 · 如 {@code x64-win32}（与 {@code resources/anydoc/} 下的目录名一致）。 */
    static String platformDirName() {
        return resolveArch() + "-" + resolveOs();
    }

    /** 当前平台的内置库资源路径 · 如 {@code /anydoc/x64-win32/anydoc_go.dll}。 */
    static String resourceName() {
        return BUNDLED_RESOURCE_PREFIX + platformDirName() + "/" + libraryFileName();
    }

    /**
     * 从 classpath 提取内置原生库到临时目录并返回其路径。
     *
     * <p><b>WHY 必须提取</b>：Spring Boot fat jar 里的资源是<b>压缩条目</b>，
     * {@code Native.load} 只能加载<b>文件系统上的</b>动态库 —— 必须先把字节落到磁盘
     * （与 {@code RipgrepRunner.ensureRgBinary} 同一个原因）。
     *
     * <p>缓存位置 {@code ${java.io.tmpdir}/nexusai-anydoc/{版本}/{平台}/}：
     * 带版本与平台两段，升级库或换机器都不会复用错的旧文件；
     * 已存在且<b>大小一致</b>时直接复用（大小不符 = 上次半写 / 被截断，重新提取）。
     *
     * @return 提取后的绝对路径；当前平台<b>未随包</b>时返回 null（不算错误，由调用方降级）
     * @throws IOException 资源存在但读写失败（磁盘满 / 权限）
     */
    private static Path extractBundledLibrary() throws IOException {
        String resource = resourceName();
        String libFile = libraryFileName();
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
            .resolve("nexusai-anydoc")
            .resolve(ANYDOC_VERSION)
            .resolve(platformDirName());
        Path target = cacheDir.resolve(libFile);

        try (java.io.InputStream probe = AnydocNative.class.getResourceAsStream(resource)) {
            if (probe == null) {
                // 当前平台没有随包库（目前除 x64-win32 之外的五个平台都是这条路）
                return null;
            }
            long resourceSize = probe.available();
            if (Files.isRegularFile(target) && resourceSize > 0 && Files.size(target) == resourceSize) {
                return target;   // 缓存可用（含进程重启后的复用）
            }
        }

        Files.createDirectories(cacheDir);
        try (java.io.InputStream in = AnydocNative.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            // 先写临时文件再原子移动：避免「JVM 崩溃留下半截 dll，下次被当成完好的缓存」
            Path tmp = cacheDir.resolve(libFile + ".tmp" + System.nanoTime());
            try {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清理失败不掩盖原始错误
                }
                throw e;
            }
        }
        log.info("AnydocNative: 内置原生库已提取 → {}（来源 classpath {}）", target, resource);
        return target;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 错误处理
    // ════════════════════════════════════════════════════════════════════════

    /** 组装失败描述：「rc=2 (MALFORMED) — <last_error>」。 */
    private static String describeFailure(AnydocLib lib, int rc) {
        String detail = lastError(lib);
        return "anydoc 转换失败：rc=" + rc + " (" + errorName(rc) + ")" + (detail.isEmpty() ? "" : " — " + detail);
    }

    /** 读当前线程最后一次错误（取完即释放；读不到返回空串，⛔ 不影响主错误码）。 */
    private static String lastError(AnydocLib lib) {
        Pointer p = null;
        try {
            p = lib.anydoc_last_error();
            if (p == null) {
                return "";
            }
            return p.getString(0, StandardCharsets.UTF_8.name());
        } catch (Throwable t) {
            return "（读取 last_error 失败: " + t + "）";
        } finally {
            if (p != null) {
                freeQuietly(lib, p);
            }
        }
    }

    /** 释放原生字符串 · 释放本身失败不覆盖原始错误。 */
    private static void freeQuietly(AnydocLib lib, Pointer p) {
        try {
            lib.anydoc_string_free(p);
        } catch (Throwable t) {
            log.warn("AnydocNative: anydoc_string_free 失败（原生内存未释放）原因={}", t.toString());
        }
    }

    /** 错误码 → 名称（逐条对齐 {@code anydoc.h} 的 {@code ERR_*}）。 */
    static String errorName(int rc) {
        return switch (rc) {
            case 0 -> "OK";
            case 1 -> "UNSUPPORTED";
            case 2 -> "MALFORMED";
            case 3 -> "ENCRYPTED";
            case 4 -> "RESOURCE_LIMIT";
            case 5 -> "MISSING_PART";
            case 6 -> "IO";
            case 7 -> "PDF_NO_MODEL";
            case 8 -> "INVALID_ARG";
            case 9 -> "UNKNOWN_FORMAT";
            case 10 -> "NEEDS_OCR";
            default -> "UNKNOWN";
        };
    }
}
