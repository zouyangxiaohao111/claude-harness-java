package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * NexusAI 配置路径工具 · 自有根 = 动态 {@code ~/.{appName}/}（决策 D1）。
 *
 * <p>对齐 CC {@code utils/envUtils.ts:7-14 getClaudeConfigHomeDir}：
 * {@code (process.env.CLAUDE_CONFIG_DIR ?? join(homedir(), '.claude')).normalize('NFC')}
 * —— 我们 mirror 该结构为 {@code homedir()/.{appName}}，其中 {@code appName} 来自
 * {@code spring.application.name}（见 {@link NexusaiAppNameInitializer}），未指定默认
 * {@value #DEFAULT_APP_NAME}。当前生产 {@code spring.application.name=nexusai}（G3 拍板），
 * 故生产自有根 = {@code ~/.nexusai}；测试经 {@link #setAppNameOverride} 覆写 appName 隔离。
 *
 * <p>决策 D2（nexusai 复刻版 .claude 改造）：所有 claude settings.json（用户级
 * {@code ~/.claude/settings.json} + 项目级 {@code .claude/settings.json} +
 * {@code .claude/settings.local.json}）一律不读；nexusai 用独立 settings 结构
 * {@code ~/.{appName}/settings.json}（CC 参照 {@code utils/settings/settings.ts:274-296}
 * getSettingsFilePathForSource userSettings 源路径）。
 *
 * <p>CC {@code utils/settings/managedPath.ts:8-25} 平台化路径模式：本类用 {@code user.home}
 * 系统属性 + {@link ClaudePaths#normalizeNfc} NFC 归一化，保证分解形 Unicode 输入产出与 CC
 * 相同的合成形字节路径（跨平台一致）。
 *
 * <p>{@link ClaudePaths#getClaudeConfigHomeDir()}（=~/.claude）仅作 CC 只读兼容保留
 * （D3 transcript / D4 plugins 读取回落源）；nexusai 一律走本类。
 */
public final class NexusaiPaths {

    private static final Logger log = LoggerFactory.getLogger(NexusaiPaths.class);

    /** 默认 appName（spring.application.name 未指定时）。 */
    static final String DEFAULT_APP_NAME = "nexusai";

    /** 动态 appName · volatile 供测试覆写（{@link #setAppNameOverride}）与运行时重读。 */
    private static volatile String appName = DEFAULT_APP_NAME;

    private NexusaiPaths() {
        // 静态工具类
    }

    /**
     * 当前 appName（Nexusai 自有根目录名，去掉前导点）· CC 语义: {@code homedir()/.claude}
     * 中的 {@code claude}。
     *
     * @return 当前 appName（默认 {@value #DEFAULT_APP_NAME}）
     */
    public static String getAppName() {
        return appName;
    }

    /**
     * 设置 appName（生产由 {@link NexusaiAppNameInitializer} 从 {@code spring.application.name}
     * 写入）· null/blank → 回落默认 {@value #DEFAULT_APP_NAME}。
     *
     * @param name 应用名
     */
    public static void setAppName(String name) {
        if (name == null || name.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("appName 为 null/blank，回落默认 {}", DEFAULT_APP_NAME);
            }
            appName = DEFAULT_APP_NAME;
        } else {
            appName = name;
            if (log.isDebugEnabled()) {
                log.debug("appName 设置为 {}", appName);
            }
        }
    }

    /**
     * 测试覆写 appName（语义命名面向测试；等价 {@link #setAppName(String)}）。
     * 传 null/blank 清除覆写恢复默认 {@value #DEFAULT_APP_NAME}。
     */
    public static void setAppNameOverride(String name) {
        setAppName(name);
    }

    /**
     * 项目级 nexusai 目录名（决策 D1/D6 全动态）：{@code .{appName}}（当前 appName=nexusai → .nexusai）。
     *
     * <p>项目级内容目录（{@code <projectRoot>/.nexusai}）随 appName 动态——与用户级
     * {@link #getAppConfigHomeDir()}（{@code ~/.{appName}}）同源。appName 变（spring.application.name）
     * 则用户级与项目级目录名全联动。D6 导入器 / SkillsLoader / ClaudemdEngine / WorktreePaths 等项目级
     * 写死 {@code .nexusai} 的点应统一走本方法。
     *
     * @return 项目级 nexusai 目录名（含前导点，如 {@code .nexusai}）
     */
    public static String getProjectDirName() {
        return "." + appName;
    }

    /**
     * 获取 NexusAI 配置自有根 · mirror CC {@code getClaudeConfigHomeDir}（envUtils.ts:7-14）：
     * <pre>
     *   CC:   (process.env.CLAUDE_CONFIG_DIR ?? join(homedir(), '.claude')).normalize('NFC')
     *   Java: {user.home}/.{appName}   （NFC 归一化）
     * </pre>
     * Java 无法进程内改 env，测试经 {@link #setAppNameOverride} 覆写 appName 即等价。
     *
     * @return NexusAI 配置自有根（NFC 归一化后绝对路径）
     */
    /** 测试覆写：配置自有根目录（{@link #getAppConfigHomeDir()}），null = 未覆写（镜像 ClaudePaths.configDirOverride）。 */
    private static volatile String configHomeDirOverride;

    /**
     * 覆写配置自有根（测试用）· 镜像 {@link ClaudePaths#setConfigDirOverride} —— Java 无法进程内改
     * env，测试隔离经本 seam 把 config home 定位到 {@code @TempDir}（防污染真实 ~/.{appName}）。
     * null/blank → 清除覆写恢复默认 {@code {user.home}/.{appName}}。
     *
     * @param override 配置自有根绝对路径；null/blank → 清除
     */
    public static void setConfigHomeDirOverride(String override) {
        configHomeDirOverride = (override == null || override.isBlank()) ? null : override;
        if (log.isDebugEnabled()) {
            log.debug("NexusaiPaths config home override 设置: {}", configHomeDirOverride);
        }
    }

    public static String getAppConfigHomeDir() {
        // [sm-reloc 2026-09-02] override 优先（测试隔离）· 对齐 ClaudePaths override ?? env 语义：
        //   override 直接替换默认 {user.home}/.{appName} 到 @TempDir，防测试污染真实 home。
        if (configHomeDirOverride != null) {
            String result = ClaudePaths.normalizeNfc(
                Path.of(configHomeDirOverride).toAbsolutePath().normalize().toString());
            if (log.isDebugEnabled()) {
                log.debug("NexusAI 配置自有根（override）: {}", result);
            }
            return result;
        }
        Path home = Path.of(System.getProperty("user.home", "."), "." + appName)
            .toAbsolutePath().normalize();
        String result = ClaudePaths.normalizeNfc(home.toString());
        if (log.isDebugEnabled()) {
            log.debug("NexusAI 配置自有根: {}（appName={}）", result, appName);
        }
        return result;
    }

    /**
     * {@link #getAppConfigHomeDir()} 的 {@link Path} 形式（下游 Files/Paths 操作便捷入口）。
     *
     * @return 配置自有根 Path
     */
    public static Path getAppConfigHomePath() {
        return Paths.get(getAppConfigHomeDir());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 运行时临时根 · 行为对齐 CC getClaudeTempDir / getClaudeTempDirName
    //   （Open-ClaudeCode/src/utils/permissions/filesystem.ts:307-315/:331-347），
    //   仅 per-user 层目录名品牌 = {appName} 自有（决策：config-home 用 .{appName}，
    //   temp 层同名，无前导点）。方案A 收敛全仓散落的 CC getClaudeTempDir 重复实现到本单出口。
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 运行时临时根 base（env/tmpdir，进程内恒定）缓存 · CC memoize（filesystem.ts:331）。
     * 只缓存 base，不缓存含 appName 的 per-user 名 —— 时序纪律见 {@link #getAppTempDirName()}。
     */
    private static volatile String tempBaseCache;

    /** 当前平台是否 Windows · CC getPlatform()==='windows' 等价（filesystem.ts:307-315）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
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
     * 运行时临时根的 <b>per-user 层</b>目录名 · CC {@code getClaudeTempDirName}（filesystem.ts:307-315）：
     * <pre>
     *   CC:  win   → 'claude'                       // :308-310 Windows tmpdir 已 per-user
     *        nonwin→ 'claude-{uid}'                 // :313-314 Unix /tmp 共享需 uid 隔离
     *   Java: win   → {appName}                     // 品牌名 = appName 自有
     *        nonwin → {appName}-{uid}               // 对齐 CC 结构，仅 per-user 层名换品牌
     * </pre>
     *
     * <p><b>时序纪律</b>：每次调用现读 {@link #getAppName()}，不得缓存含 appName 的值 ——
     * {@link com.nexusai.application.agent.skill.NexusaiAppNameInitializer} 的
     * {@code @PostConstruct} 可能晚于本方法首调（首调在 Spring bean 装配前的静态初始化 / 测试夹具），
     * 缓存旧名会让生产 appName 注入失效。Windows tmpdir（{@code C:\Users\{user}\AppData\Local\Temp}）
     * 天然 per-user，故不加 uid（filesystem.ts:305 注释）；Unix 共享 /tmp 必须 uid 隔离
     * （filesystem.ts:311-313 注释）。
     *
     * @return per-user 层目录名（Windows {appName} / Unix {appName}-{uid数字}）
     */
    public static String getAppTempDirName() {
        if (isWindows()) {
            return getAppName();
        }
        return getAppName() + "-" + getUid();
    }

    /**
     * 运行时临时根 base · env {@code CLAUDE_CODE_TMPDIR} 优先 → Windows {@code java.io.tmpdir}
     * / 非 Windows {@code /tmp} → {@code toRealPath()} 解析 symlink（失败回落原路径），
     * 进程内 memoize（double-checked volatile）。对齐 CC {@code getClaudeTempDir} 的 base 段
     * （filesystem.ts:331-347：{@code CLAUDE_CODE_TMPDIR || (win ? tmpdir() : '/tmp')} → realpath）。
     *
     * @return 规范化后的 temp base 绝对路径（不含 per-user 名、不含尾分隔符）
     */
    private static String getTempBase() {
        String base = tempBaseCache;
        if (base == null) {
            synchronized (NexusaiPaths.class) {
                base = tempBaseCache;
                if (base == null) {
                    base = System.getenv("CLAUDE_CODE_TMPDIR");
                    if (base == null || base.isBlank()) {
                        base = isWindows()
                            ? System.getProperty("java.io.tmpdir")
                            : "/tmp";
                    }
                    // realpath（CC filesystem.ts:339-344 realpathSync；失败回退原路径）
                    String resolved = base;
                    try {
                        resolved = Paths.get(base).toRealPath().normalize().toString();
                    } catch (IOException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("运行时临时根 base realpath 失败回退原路径（CC filesystem.ts:339-344）: {} → {}",
                                base, e.getMessage());
                        }
                    }
                    tempBaseCache = resolved;
                    base = resolved;
                }
            }
        }
        return base;
    }

    /**
     * 运行时临时根 · 行为对齐 CC {@code getClaudeTempDir}（filesystem.ts:331-347）：
     * {@code join(getTempBase(), getClaudeTempDirName()) + sep}，per-user 名每次现拼
     * {@link #getAppTempDirName()}（与缓存的 base 现拼，不缓存含 appName 的结果 —— 时序纪律）。
     *
     * <p>返回<b>带尾分隔符</b>串（CC filesystem.ts:346 {@code + sep} 语义），下游
     * {@code Paths.get(getAppTempDir(), ...)} 宽容尾分隔符。生产形态：
     * <pre>
     *   Windows: {java.io.tmpdir}/nexusai/     （appName=nexusai，无 uid）
     *   非 Windows: /tmp/nexusai-{uid}/          （uid=数字，Unix 共享 /tmp 隔离）
     * </pre>
     *
     * @return 运行时临时根绝对路径 + 尾分隔符（per-user 层 = {appName}[-{uid}]）
     */
    public static String getAppTempDir() {
        String perUserDir = Paths.get(getTempBase(), getAppTempDirName())
            .normalize().toString() + File.separator;
        if (log.isDebugEnabled()) {
            log.debug("NexusaiPaths 运行时临时根: {}（appName={}）", perUserDir, appName);
        }
        return perUserDir;
    }

    /**
     * {@link #getAppTempDir()} 的 {@link Path} 形式（下游 Files/Paths 操作便捷入口；
     * Path 操作宽容尾分隔符）。
     *
     * @return 运行时临时根 Path
     */
    public static Path getAppTempPath() {
        return Paths.get(getAppTempDir());
    }
}
