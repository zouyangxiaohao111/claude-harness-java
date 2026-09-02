package com.nexusai.application.agent.permission.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.bash.BashParser;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bash 沙箱管理器 · 对齐 CC SandboxManager（shouldUseSandbox.ts + sandbox-adapter.ts）
 *
 * <p>沙箱模式下，Bash 命令在受限环境中执行（禁止网络/越界写/高危命令）。
 * 沙箱启用时，Bash 命令可以自动 allow（§9.9 isAutoAllowBashIfSandboxed）。
 *
 * <p>IMP-5 三闸（OPD-WF4-BC-01 拍板：对齐 CC 三闸）：{@link #isEnabled()} 由单闸（仅配置
 * enabled）升级为 CC {@code isSandboxingEnabled()}（sandbox-adapter.ts:532-547）四门组合：
 * <ol>
 *   <li>{@code isSupportedPlatform}（:533-535）—— macOS / Linux / WSL2+ 支持，WSL1 不支持；</li>
 *   <li>{@code checkDependencies().errors 空}（:537-540）—— Linux/WSL 需 {@code bwrap+socat}、
 *       macOS 需 {@code sandbox-exec}（沙箱执行链依赖，Java 侧未实施执行链，故二进制存在性门
 *       为 fail-closed 前置，OPD-WF4-DEC-03 待专项）；</li>
 *   <li>{@code isPlatformInEnabledList}（:542-545）—— {@code settings.sandbox.enabledPlatforms}
 *       白名单（未配置 → 允许全部）；</li>
 *   <li>{@code getSandboxEnabledSetting}（:546）—— {@code settings.sandbox.enabled ?? false}。</li>
 * </ol>
 * 四门全过才允许沙箱 auto-allow —— 平台无法实际运行沙箱时不得把命令当作"沙箱内执行"
 * 而自动放行（fail-closed，防 CC #34044 fail-open 安全脚枪）。
 *
 * <p>IMP-B2（组 1-4 B）对齐 CC shouldUseSandbox.ts 全量语义：
 * <ul>
 *   <li><b>DEL-B2-001</b>：删除 {@code canSandbox} 硬编码网络/root 命令黑名单（curl/wget/sudo…）。
 *       CC shouldUseSandbox.ts 真源 = {@code settings.sandbox.excludedCommands}（:54）+ 规则三型
 *       （prefix/exact/wildcard，bashPermissionRule :104）+ 复合命令子命令切分（:64-69）+
 *       固定点剥 env/wrapper 前缀候选（:82-101），无任何硬编码黑名单（EV-B2-020/⊕-1）。</li>
 *   <li><b>per-input dangerouslyDisableSandbox</b>：CC shouldUseSandbox.ts:136-141 从<b>输入</b>
 *       读取 {@code input.dangerouslyDisableSandbox}（非全局标志）+ {@code allowUnsandboxedCommands}
 *       （:137 sandbox-adapter.ts:474-477 {@code settings.sandbox.allowUnsandboxedCommands ?? true}）
 *       → 不沙箱化。删除旧全局 volatile {@code dangerouslyDisabled} 标志与其 setter（无生产调用方）。</li>
 * </ul>
 *
 * <h2>配置</h2>
 * <pre>
 * nexusai.sandbox.enabled=false            # 是否启用沙箱（CC getSandboxEnabledSetting）
 * nexusai.sandbox.auto-allow=true          # 沙箱内是否自动允许 Bash（isAutoAllowBashIfSandboxed；
 *                                          #   [WF-4 DEC-04] 对齐 CC sandbox-adapter.ts:469-472 默认 true，不门控 isEnabled）
 * nexusai.sandbox.allow-unsandboxed=true   # 是否允许非沙箱命令（areUnsandboxedCommandsAllowed，CC 默认 true）
 * nexusai.sandbox.enabled-platforms=       # 逗号分隔平台白名单（settings.sandbox.enabledPlatforms，CC :505-529；
 *                                          #   空 = 未配置 → 允许全部支持平台；值用 CC 平台名 macos/windows/wsl/linux/unknown）
 * nexusai.sandbox.excluded-commands=       # 逗号分隔排除命令（settings.sandbox.excludedCommands，CC :54）
 * </pre>
 *
 * <h2>CC 对齐</h2>
 * <ul>
 *   <li>§9.8 isSandboxingEnabled() → {@link #isEnabled()}</li>
 *   <li>§9.9 isAutoAllowBashIfSandboxedEnabled() → {@link #isAutoAllowBashIfSandboxed()}</li>
 *   <li>§9.10 shouldUseSandbox(input) → {@link #shouldUseSandbox(String, JsonNode)}（per-input 判断）</li>
 *   <li>§9.11 dangerouslyDisableSandbox → 输入字段（per-input，非全局标志）</li>
 *   <li>CC shouldUseSandbox.ts:21-128 containsExcludedCommand → {@link #containsExcludedCommand(String)}</li>
 *   <li>CC sandbox-adapter.ts:491-495 isSupportedPlatform → {@link #isSupportedPlatformEnv()}（memoized）</li>
 *   <li>CC sandbox-adapter.ts:451-457 checkDependencies → {@link #checkDependenciesEnv()}（memoized）</li>
 *   <li>CC sandbox-adapter.ts:505-529 isPlatformInEnabledList → {@link #isPlatformInEnabledList()}</li>
 *   <li>CC platform.ts getPlatform → {@link #currentPlatform()}</li>
 * </ul>
 */
@Component
public class SandboxManager {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(SandboxManager.class);

    /** CC platform.ts getWslVersion —— 显式 WSL 版本标记 {@code WSL(\d+)}。 */
    private static final Pattern WSL_VERSION = Pattern.compile("WSL(\\d+)");

    /** CC getPlatform 平台值（platform.ts:7 Platform）。 */
    public static final String PLATFORM_MACOS = "macos";
    public static final String PLATFORM_WINDOWS = "windows";
    public static final String PLATFORM_WSL = "wsl";
    public static final String PLATFORM_LINUX = "linux";
    public static final String PLATFORM_UNKNOWN = "unknown";

    private static volatile Boolean supportedPlatformCache;
    private static volatile Boolean dependenciesCache;

    private final boolean sandboxEnabled;
    private final boolean autoAllowIfSandboxed;
    private final boolean allowUnsandboxedCommands;
    /** CC settings.sandbox.enabledPlatforms（sandbox-adapter.ts:508-517）。 */
    private final List<String> enabledPlatforms;
    /** CC isSupportedPlatform（memoized）—— 环境探针，测试可注入。 */
    private final BooleanSupplier platformProbe;
    /** CC checkDependencies().errors 空（memoized）—— 环境探针，测试可注入。 */
    private final BooleanSupplier dependencyProbe;

    /**
     * settings.sandbox.excludedCommands · CC shouldUseSandbox.ts:54（真源）。
     *
     * <p>用户配置的排除命令（如 {@code bazel}、{@code "npm:*"}、{@code "git status *"}），
     * 命中则命令<b>不沙箱化</b>。仅用户便捷功能非安全边界（CC :18-20 NOTE）——
     * 沙箱权限系统（ask 弹窗）才是实际安全控制。逗号分隔注入（{@code nexusai.sandbox.excluded-commands}），
     * 空串 → 空列表。
     */
    @Value("${nexusai.sandbox.excluded-commands:}")
    private List<String> excludedCommands = List.of();

    /**
     * 构造沙箱管理器（Spring 注入 4 参，真实环境探针）。
     *
     * <p>[pull origin RES-04 同类修复] 本类含多个构造器，必须显式 {@code @Autowired}
     * 标记注入目标，否则 Spring 多构造器下无法确定注入而报 "No default constructor"
     * （HooksConfigSnapshot.java:65 同一先例）。
     *
     * @param sandboxEnabled        是否启用沙箱（nexusai.sandbox.enabled）
     * @param autoAllowIfSandboxed  沙箱启用时是否自动允许 Bash（nexusai.sandbox.auto-allow）
     * @param allowUnsandboxedCommands 是否允许非沙箱命令（nexusai.sandbox.allow-unsandboxed；
     *                               CC sandbox-adapter.ts:474-477 默认 {@code ?? true}）
     * @param enabledPlatforms      平台白名单（nexusai.sandbox.enabled-platforms；空 = 未配置 → 允许全部）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SandboxManager(
            @Value("${nexusai.sandbox.enabled:false}") boolean sandboxEnabled,
            @Value("${nexusai.sandbox.auto-allow:true}") boolean autoAllowIfSandboxed,
            @Value("${nexusai.sandbox.allow-unsandboxed:true}") boolean allowUnsandboxedCommands,
            @Value("${nexusai.sandbox.enabled-platforms:}") List<String> enabledPlatforms
    ) {
        this(sandboxEnabled, autoAllowIfSandboxed, allowUnsandboxedCommands,
            sanitizePlatforms(enabledPlatforms),
            SandboxManager::isSupportedPlatformEnv,
            SandboxManager::checkDependenciesEnv);
    }

    /**
     * 便捷构造（测试/工具直接构造）：allowUnsandboxedCommands 取 CC 默认 {@code true}
     * （sandbox-adapter.ts:477 {@code settings?.sandbox?.allowUnsandboxedCommands ?? true}），
     * 环境探针视为就绪（平台+依赖均通过）——用于隔离验证 per-input 沙箱逻辑
     * （shouldUseSandbox 的 excludedCommands / dangerouslyDisableSandbox 等）。
     *
     * @param sandboxEnabled      是否启用沙箱
     * @param autoAllowIfSandboxed 沙箱启用时是否自动允许 Bash
     */
    public SandboxManager(boolean sandboxEnabled, boolean autoAllowIfSandboxed) {
        this(sandboxEnabled, autoAllowIfSandboxed, true);
    }

    /**
     * 便捷构造（同上，可指定 allowUnsandboxedCommands）。
     *
     * @param sandboxEnabled      是否启用沙箱
     * @param autoAllowIfSandboxed 沙箱启用时是否自动允许 Bash
     * @param allowUnsandboxedCommands 是否允许非沙箱命令
     */
    public SandboxManager(boolean sandboxEnabled, boolean autoAllowIfSandboxed,
            boolean allowUnsandboxedCommands) {
        this(sandboxEnabled, autoAllowIfSandboxed, allowUnsandboxedCommands, List.of(),
            () -> true, () -> true);
    }

    /**
     * 全参构造（测试注入探针）：显式控制平台/依赖探针与平台白名单。
     *
     * @param sandboxEnabled      是否启用沙箱
     * @param autoAllowIfSandboxed 沙箱启用时是否自动允许 Bash
     * @param allowUnsandboxedCommands 是否允许非沙箱命令
     * @param enabledPlatforms     平台白名单（CC settings.sandbox.enabledPlatforms）
     * @param platformProbe       平台支持探针（CC isSupportedPlatform）
     * @param dependencyProbe     依赖就绪探针（CC checkDependencies().errors 空）
     */
    public SandboxManager(boolean sandboxEnabled, boolean autoAllowIfSandboxed,
            boolean allowUnsandboxedCommands, List<String> enabledPlatforms,
            BooleanSupplier platformProbe, BooleanSupplier dependencyProbe) {
        this.sandboxEnabled = sandboxEnabled;
        this.autoAllowIfSandboxed = autoAllowIfSandboxed;
        this.allowUnsandboxedCommands = allowUnsandboxedCommands;
        this.enabledPlatforms = sanitizePlatforms(enabledPlatforms);
        this.platformProbe = platformProbe;
        this.dependencyProbe = dependencyProbe;
    }

    private static List<String> sanitizePlatforms(List<String> platforms) {
        if (platforms == null) {
            return List.of();
        }
        return platforms.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    /**
     * 注入 excludedCommands 配置（Spring 字段 {@code @Value} 已注入；测试可覆盖）。
     *
     * @param excludedCommands 用户配置排除命令列表（空过滤）
     */
    public void setExcludedCommands(List<String> excludedCommands) {
        if (excludedCommands == null) {
            this.excludedCommands = List.of();
            return;
        }
        this.excludedCommands = excludedCommands.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(String::trim)
            .toList();
        if (log.isDebugEnabled()) {
            log.debug("SandboxManager.setExcludedCommands: 注入 {} 条排除命令（CC settings.sandbox.excludedCommands）",
                this.excludedCommands.size());
        }
    }

    // ──────────────────────────────────────────────
    // §9.8  isSandboxingEnabled（三闸四门）
    // ──────────────────────────────────────────────

    /**
     * 沙箱是否启用（§9.8 isSandboxingEnabled · sandbox-adapter.ts:532-547）。
     *
     * <p>IMP-5 三闸（OPD-WF4-BC-01 拍板）：四门组合
     * {@code isSupportedPlatform && checkDependencies && isPlatformInEnabledList &&
     * getSandboxEnabledSetting}，任一不过即 fail-closed 关闭（不再仅看配置 enabled）。
     * 平台无法实际运行沙箱（Windows / WSL1 / 未知平台 / 依赖缺失 / 白名单排除）时，
     * 不得把命令当作"沙箱内执行"而 auto-allow。
     *
     * @return {@code true} 沙箱处于启用状态（四门全过）
     */
    public boolean isEnabled() {
        if (!platformProbe.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("SandboxManager.isEnabled: 平台不支持沙箱（CC isSupportedPlatform "
                    + "sandbox-adapter.ts:533-535，当前平台={}）→ false", currentPlatform());
            }
            return false;
        }
        if (!dependencyProbe.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("SandboxManager.isEnabled: 沙箱依赖缺失（CC checkDependencies "
                    + "sandbox-adapter.ts:537-540）→ false");
            }
            return false;
        }
        if (!isPlatformInEnabledList()) {
            if (log.isDebugEnabled()) {
                log.debug("SandboxManager.isEnabled: 当前平台不在 enabledPlatforms 白名单（CC "
                    + "isPlatformInEnabledList sandbox-adapter.ts:542-545，当前平台={}）→ false",
                    currentPlatform());
            }
            return false;
        }
        if (!sandboxEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("SandboxManager.isEnabled: 配置 sandbox.enabled=false（CC "
                    + "getSandboxEnabledSetting sandbox-adapter.ts:546）→ false");
            }
            return false;
        }
        return true;
    }

    // ──────────────────────────────────────────────
    // 三闸 1：isSupportedPlatform（sandbox-adapter.ts:491-495）
    // ──────────────────────────────────────────────

    /**
     * 当前平台是否支持沙箱（CC isSupportedPlatform，memoized）。
     *
     * <p>支持 macOS / Linux / WSL2+；Windows、WSL1、未知平台不支持
     * （sandbox-adapter.ts:483-489 注释 "Supports: macOS, Linux, and WSL2+ (WSL1 is not supported)"）。
     * 经静态缓存避免每次调用读 /proc/version 或 spawn 子进程（CC lodash memoize 等价）。
     *
     * @return {@code true} 当前平台支持沙箱
     */
    public static boolean isSupportedPlatformEnv() {
        Boolean cached = supportedPlatformCache;
        if (cached != null) {
            return cached;
        }
        synchronized (SandboxManager.class) {
            if (supportedPlatformCache == null) {
                supportedPlatformCache = isSupportedPlatform(
                    System.getProperty("os.name", "").toLowerCase(), readProcVersion());
            }
            cached = supportedPlatformCache;
        }
        return cached;
    }

    /**
     * 平台支持判定纯函数（测试可直接验证，无环境 IO）。
     *
     * @param osNameLower 小写 {@code os.name}（如 {@code "windows 11"} / {@code "mac os x"} / {@code "linux"}）
     * @param procVersion {@code /proc/version} 内容（仅 Linux 读取；非 Linux 传 {@code null}）
     * @return {@code true} 平台支持沙箱
     */
    static boolean isSupportedPlatform(String osNameLower, String procVersion) {
        if (osNameLower.contains("mac") || osNameLower.contains("darwin")) {
            return true;
        }
        if (osNameLower.contains("win")) {
            return false;
        }
        if (osNameLower.contains("linux")) {
            if (procVersion != null && procVersion.toLowerCase().contains("microsoft")
                    || procVersion != null && procVersion.toLowerCase().contains("wsl")) {
                // WSL：WSL2+ 支持、WSL1 不支持（platform.ts getWslVersion：含 WSL(\d) 标记用标记值，
                // 仅含 microsoft 无标记 → 视为 WSL1）
                Matcher m = WSL_VERSION.matcher(procVersion);
                if (m.find()) {
                    return !"1".equals(m.group(1));
                }
                return false;
            }
            return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────
    // 三闸 2：checkDependencies（sandbox-adapter.ts:451-457）
    // ──────────────────────────────────────────────

    /**
     * 沙箱运行依赖是否就绪（CC checkDependencies().errors 空，memoized）。
     *
     * <p>CC 经 {@code BaseSandboxManager.checkDependencies} 探测沙箱运行时 + ripgrep；
     * Java 端沙箱执行链未实施（OPD-WF4-DEC-03 待专项），故以<b>沙箱运行时二进制存在性</b>
     * 作为依赖门：Linux/WSL 需 {@code bwrap+socat}（srt SDK 依赖），macOS 需
     * {@code sandbox-exec}。依赖缺失 = 无法实际沙箱化 → fail-closed 关闭（防 auto-allow
     * 假沙箱）。经静态缓存避免每次调用 spawn 子进程。
     *
     * @return {@code true} 依赖就绪
     */
    public static boolean checkDependenciesEnv() {
        Boolean cached = dependenciesCache;
        if (cached != null) {
            return cached;
        }
        synchronized (SandboxManager.class) {
            if (dependenciesCache == null) {
                dependenciesCache = checkDependenciesFor(
                    System.getProperty("os.name", "").toLowerCase(),
                    SandboxManager::commandExists);
            }
            cached = dependenciesCache;
        }
        return cached;
    }

    /**
     * 依赖判定纯函数（测试可直接验证，注入命令存在性探针）。
     *
     * @param osNameLower 小写 {@code os.name}
     * @param commandExists 命令存在性探针（{@code which}/{@code where} 等价）
     * @return {@code true} 依赖就绪
     */
    static boolean checkDependenciesFor(String osNameLower, Predicate<String> commandExists) {
        if (osNameLower.contains("win")) {
            return false;
        }
        if (osNameLower.contains("mac") || osNameLower.contains("darwin")) {
            return commandExists.test("sandbox-exec");
        }
        // Linux / WSL：bwrap + socat
        return commandExists.test("bwrap") && commandExists.test("socat");
    }

    private static boolean commandExists(String cmd) {
        try {
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            Process p = new ProcessBuilder(win ? new String[] {"where", cmd}
                : new String[] {"which", cmd}).redirectErrorStream(true).start();
            try (java.io.InputStream is = p.getInputStream()) {
                is.readAllBytes();
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ──────────────────────────────────────────────
    // 三闸 3：isPlatformInEnabledList（sandbox-adapter.ts:505-529）
    // ──────────────────────────────────────────────

    /**
     * 当前平台是否在 enabledPlatforms 白名单（CC isPlatformInEnabledList）。
     *
     * <p>未配置（空）→ 允许全部支持平台（CC undefined → true，sandbox-adapter.ts:510-513）。
     * 配置且当前平台不在列表 → 拒绝（CC :516-518，NVIDIA 仅 macos 场景）。
     * 注：Java 逗号分隔配置无法表达 CC "显式空数组 → false"（:514-515）边界，
     * 空串等价 CC 未配置，故空列表按 undefined 处理。
     *
     * @return {@code true} 当前平台在白名单
     */
    public boolean isPlatformInEnabledList() {
        return isPlatformInEnabledList(enabledPlatforms, currentPlatform());
    }

    /**
     * 白名单判定纯函数（测试可直接验证）。
     *
     * @param enabledPlatforms 配置白名单（CC 平台名）
     * @param currentPlatform  当前平台（CC getPlatform 值）
     * @return {@code true} 平台在白名单（空列表 = 未配置 → 允许）
     */
    static boolean isPlatformInEnabledList(List<String> enabledPlatforms, String currentPlatform) {
        if (enabledPlatforms == null || enabledPlatforms.isEmpty()) {
            return true;
        }
        return enabledPlatforms.contains(currentPlatform);
    }

    // ──────────────────────────────────────────────
    // CC platform.ts getPlatform（platform.ts:12-47）
    // ──────────────────────────────────────────────

    /**
     * 当前平台（CC getPlatform 值：macos / windows / wsl / linux / unknown）。
     *
     * <p>从 {@code os.name} + {@code /proc/version}（Linux 下检测 WSL）推断，
     * 对齐 CC platform.ts:12-47。
     *
     * @return 当前平台值
     */
    public static String currentPlatform() {
        return detectPlatform(System.getProperty("os.name", "").toLowerCase(), readProcVersion());
    }

    /**
     * 平台识别纯函数（测试可直接验证）。
     *
     * @param osNameLower 小写 {@code os.name}
     * @param procVersion {@code /proc/version} 内容（非 Linux 传 {@code null}）
     * @return CC 平台值
     */
    static String detectPlatform(String osNameLower, String procVersion) {
        if (osNameLower.contains("mac") || osNameLower.contains("darwin")) {
            return PLATFORM_MACOS;
        }
        if (osNameLower.contains("win")) {
            return PLATFORM_WINDOWS;
        }
        if (osNameLower.contains("linux")) {
            if (procVersion != null && procVersion.toLowerCase().contains("microsoft")
                    || procVersion != null && procVersion.toLowerCase().contains("wsl")) {
                return PLATFORM_WSL;
            }
            return PLATFORM_LINUX;
        }
        return PLATFORM_UNKNOWN;
    }

    private static String readProcVersion() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(Path.of("/proc/version")),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────
    // §9.9  isAutoAllowBashIfSandboxedEnabled
    // ──────────────────────────────────────────────

    /**
     * 沙箱启用时是否自动允许 Bash（§9.9 isAutoAllowBashIfSandboxedEnabled）。
     *
     * <p>[WF-4 DEC-04] 对齐 CC sandbox-adapter.ts:469-472
     * {@code settings?.sandbox?.autoAllowBashIfSandboxed ?? true}：默认 true 且
     * <b>不门控 isEnabled</b>（isEnabled 由调用方显式叠加，如 BashTool step 3 的
     * shouldUseSandbox / CheckLayer1b 的 canSandboxAutoAllow）。配合 S01 deny 预检
     * （matchingDenyOrAskRule）保护——auto-allow 前先查 deny/ask 规则。
     *
     * @return {@code true} 允许自动审批沙箱内 Bash 命令（配置默认 true）
     */
    public boolean isAutoAllowBashIfSandboxed() {
        return autoAllowIfSandboxed;
    }

    // ──────────────────────────────────────────────
    // §9.10 shouldUseSandbox（per-input 判断）
    // ──────────────────────────────────────────────

    /**
     * 是否应该对特定输入使用沙箱（§9.10 shouldUseSandbox）。
     *
     * <p>对齐 CC shouldUseSandbox.ts:130-153 全量语义：
     * <ol>
     *   <li>沙箱未启用（三闸 {@link #isEnabled()} 不过）→ false（:131-133）</li>
     *   <li>仅 Bash 工具沙箱化（Java 端 toolName 门控，消费点 1b/Hook 已在调用前判 BASH_TOOL_NAME）</li>
     *   <li>{@code input.dangerouslyDisableSandbox && allowUnsandboxedCommands} → false（:136-141，
     *       per-input 显式覆盖）</li>
     *   <li>输入无 command → false（:143-145）</li>
     *   <li>{@code containsExcludedCommand(command)} → false（:147-150，settings.sandbox.excludedCommands）</li>
     *   <li>默认 → true</li>
     * </ol>
     *
     * @param toolName 工具名（Java 端门控：仅 {@code "Bash"} 沙箱化）
     * @param input    工具输入（JSON，含 command / dangerouslyDisableSandbox）
     * @return {@code true} 应该沙箱化该命令
     */
    public boolean shouldUseSandbox(String toolName, JsonNode input) {
        if (!isEnabled()) {
            return false;
        }
        // 仅 Bash 工具需要沙箱（CC 由 input 隐含 Bash；Java 显式 toolName 门控）
        if (!"Bash".equals(toolName)) {
            return false;
        }
        // CC shouldUseSandbox.ts:136-141 — per-input dangerouslyDisableSandbox +
        // areUnsandboxedCommandsAllowed()（settings.sandbox.allowUnsandboxedCommands ?? true）
        if (input != null && input.path("dangerouslyDisableSandbox").asBoolean(false)
                && allowUnsandboxedCommands) {
            if (log.isDebugEnabled()) {
                log.debug("SandboxManager.shouldUseSandbox: per-input dangerouslyDisableSandbox=true "
                    + "且 allowUnsandboxedCommands=true → 不沙箱化（CC shouldUseSandbox.ts:136-141）");
            }
            return false;
        }
        if (input == null || !input.hasNonNull("command")) {
            return false;
        }
        String command = input.get("command").asText();
        if (command.isBlank()) {
            return false;
        }
        // CC shouldUseSandbox.ts:147-150 — settings.sandbox.excludedCommands 命中 → 不沙箱化
        if (containsExcludedCommand(command)) {
            if (log.isDebugEnabled()) {
                log.debug("SandboxManager.shouldUseSandbox: excludedCommands 命中 → 不沙箱化（CC shouldUseSandbox.ts:148）: {}",
                    abbreviate(command, 120));
            }
            return false;
        }
        return true;
    }

    // ──────────────────────────────────────────────
    // CC containsExcludedCommand（shouldUseSandbox.ts:21-128）
    // ──────────────────────────────────────────────

    /**
     * 命令是否包含用户配置的排除命令（CC shouldUseSandbox.ts:21-128 containsExcludedCommand）。
     *
     * <p>语义对齐：
     * <ul>
     *   <li>排除命令为空 → false（:56-58）</li>
     *   <li>复合命令（{@code docker ps && curl evil.com}）先 splitCommand_DEPRECATED 切子命令
     *       逐条检查（:60-69），防首子命令匹配即放行后段逃逸</li>
     *   <li>每子命令做<b>固定点</b>候选展开：{@code stripAllLeadingEnvVars(BINARY_HIJACK_VARS)} +
     *       {@code stripSafeWrappers}（:82-101，{@code FOO=bar bazel ...} / {@code timeout 30 bazel ...}
     *       命中 {@code bazel:*}）</li>
     *   <li>模式经 {@code bashPermissionRule} 解析为三型（prefix/exact/wildcard）逐候选匹配（:103-124）</li>
     * </ul>
     *
     * <p>非安全边界（CC :18-20 NOTE）——仅用户便捷功能；沙箱权限系统（ask）才是安全控制。
     *
     * @param command 完整命令字符串
     * @return {@code true} 命令含排除命令（不沙箱化）
     */
    private boolean containsExcludedCommand(String command) {
        if (excludedCommands == null || excludedCommands.isEmpty()) {
            return false;
        }
        // 复合命令切子命令（CC :64-69；parse 失败降级整条）
        List<String> subcommands;
        try {
            subcommands = BashParser.splitCommandDeprecated(command);
        } catch (Exception e) {
            subcommands = List.of(command);
        }
        for (String subcommand : subcommands) {
            String trimmed = subcommand.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 固定点候选：trimmed + 剥 env 前缀 + 剥 wrapper 前缀（CC :82-101）
            List<String> candidates = new ArrayList<>();
            candidates.add(trimmed);
            Set<String> seen = new HashSet<>(candidates);
            int startIdx = 0;
            while (startIdx < candidates.size()) {
                int endIdx = candidates.size();
                for (int i = startIdx; i < endIdx; i++) {
                    String cmd = candidates.get(i);
                    String envStripped =
                        BashRuleMatcher.stripAllLeadingEnvVars(cmd, BashRuleMatcher.BINARY_HIJACK_VARS);
                    if (seen.add(envStripped)) {
                        candidates.add(envStripped);
                    }
                    String wrapperStripped = BashRuleMatcher.stripSafeWrappers(cmd);
                    if (seen.add(wrapperStripped)) {
                        candidates.add(wrapperStripped);
                    }
                }
                startIdx = endIdx;
            }
            // 模式三型匹配（CC :103-124 bashPermissionRule：prefix/exact/wildcard）
            for (String pattern : excludedCommands) {
                BashRuleMatcher.ShellPermissionRule rule = BashRuleMatcher.parseRule(pattern);
                for (String cand : candidates) {
                    switch (rule.type()) {
                        case "prefix":
                            if (cand.equals(rule.prefix())
                                    || cand.startsWith(rule.prefix() + " ")) {
                                return true;
                            }
                            break;
                        case "exact":
                            if (cand.equals(rule.command())) {
                                return true;
                            }
                            break;
                        case "wildcard":
                            if (BashRuleMatcher.matchWildcardPattern(rule.pattern(), cand)) {
                                return true;
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        return false;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
