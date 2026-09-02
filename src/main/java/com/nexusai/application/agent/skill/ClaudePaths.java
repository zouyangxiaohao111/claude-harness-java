package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Claude 配置路径工具 · 对齐 CC {@code utils/envUtils.ts:7-14 getClaudeConfigHomeDir} +
 * {@code utils/settings/managedPath.ts:8-25 getManagedFilePath} +
 * {@code skills/loadSkillsDir.ts:78-94 getSkillsPath}（A6 四源决策）。
 *
 * <h2>CC 对应（snake_case → camelCase，行号标注）</h2>
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #getClaudeConfigHomeDir()}</td><td>{@code getClaudeConfigHomeDir}</td><td>envUtils.ts:7-14</td></tr>
 *   <tr><td>{@link #getManagedFilePath()}</td><td>{@code getManagedFilePath}</td><td>managedPath.ts:8-25</td></tr>
 *   <tr><td>{@link #getSkillsPath(String, String)}</td><td>{@code getSkillsPath}</td><td>loadSkillsDir.ts:78-94</td></tr>
 *   <tr><td>{@link #getNexusaiConfigHomeDir()}</td><td>镜像 {@code getClaudeConfigHomeDir}</td><td>envUtils.ts:7-14（自有根委托 NexusaiPaths）</td></tr>
 * </table>
 *
 * <p><b>平台覆盖</b>：CC {@code getClaudeConfigHomeDir} 用 {@code CLAUDE_CONFIG_DIR ?? homedir()/.claude}
 * 并 normalize('NFC')（envUtils.ts:7-14）。Java 无法进程内改 env，故提供 {@link #setConfigDirOverride}
 * 测试覆写；{@code getManagedFilePath} 用 {@code os.name} 探测（win→{@code C:\Program Files\ClaudeCode}、
 * macos→{@code /Library/Application Support/ClaudeCode}、其余→{@code /etc/claude-code}，
 * managedPath.ts:17-24），同样提供 {@link #setManagedFilePathOverride} 测试覆写。
 *
 * <p><b>nexusai 自有根</b>（决策 D1）：{@link #getNexusaiConfigHomeDir()} 委托
 * {@link NexusaiPaths#getAppConfigHomeDir()}（{@code {user.home}/.{appName}}，appName=
 * spring.application.name 默认 nexusai）；{@link #getClaudeConfigHomeDir()}（=~/.claude）
 * 仅作 CC 只读兼容保留（D3/D4 读取回落源）。
 */
public final class ClaudePaths {

    private static final Logger log = LoggerFactory.getLogger(ClaudePaths.class);

    /** 测试覆写：Claude 配置根目录（{@code getClaudeConfigHomeDir}），null = 未覆写。 */
    private static volatile String configDirOverride;
    /** 测试覆写：managed settings 路径（{@code getManagedFilePath}），null = 未覆写。 */
    private static volatile String managedFilePathOverride;

    private ClaudePaths() {
        // 静态工具类
    }

    /**
     * 获取 Claude 配置根目录 · CC original: {@code getClaudeConfigHomeDir}（envUtils.ts:7-14）
     * {@code (process.env.CLAUDE_CONFIG_DIR ?? join(homedir(), '.claude')).normalize('NFC')}。
     *
     * <p>Java 映射（优先级从高到低）：
     * <ol>
     *   <li>{@link #setConfigDirOverride}（测试覆写，Java 无法进程内改 env）</li>
     *   <li>env {@code CLAUDE_CONFIG_DIR}（CC 原生覆盖）</li>
     *   <li>{user.home}/.claude（CC 默认 homedir()/.claude）</li>
     * </ol>
     *
     * <p>输出 NFC 归一化（OPD-R2-06）：CC envUtils.ts:11 全链 `.normalize('NFC')` ——
     * 分解形 Unicode（e+U+0301 类）路径输入产出不同字节路径串，与 AutoMemPaths/TeamMemPaths
     * 共用 {@link #normalizeNfc}。
     *
     * @return Claude 配置根目录（NFC 归一化后绝对路径）
     */
    public static String getClaudeConfigHomeDir() {
        String result;
        if (configDirOverride != null && !configDirOverride.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("Claude 配置根目录（测试覆写）：{}", configDirOverride);
            }
            result = Paths.get(configDirOverride).toAbsolutePath().normalize().toString();
        } else {
            String env = System.getenv("CLAUDE_CONFIG_DIR");
            if (env != null && !env.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("Claude 配置根目录（CLAUDE_CONFIG_DIR 环境变量）：{}", env);
                }
                result = Paths.get(env).toAbsolutePath().normalize().toString();
            } else {
                Path home = Path.of(System.getProperty("user.home", "."), ".claude").toAbsolutePath().normalize();
                if (log.isDebugEnabled()) {
                    log.debug("Claude 配置根目录（默认用户 home）：{}", home);
                }
                result = home.toString();
            }
        }
        return normalizeNfc(result);
    }

    /**
     * 获取 NexusAI 配置自有根 · 委托 {@link NexusaiPaths#getAppConfigHomeDir()}（决策 D1）。
     *
     * <p>自有根 = 动态 {@code {user.home}/.{appName}}（{@code appName} 来自
     * {@code spring.application.name}，默认 nexusai）；{@link #getClaudeConfigHomeDir()}
     * （=~/.claude）仅作 CC 只读兼容保留。镜像 CC {@code getClaudeConfigHomeDir}
     * （envUtils.ts:7-14 {@code homedir()/.claude} → 我们 mirror {@code homedir()/.{appName}}）。
     *
     * @return NexusAI 配置自有根（NFC 归一化后绝对路径）
     */
    public static String getNexusaiConfigHomeDir() {
        return NexusaiPaths.getAppConfigHomeDir();
    }

    /**
     * NFC 归一化 · CC 全链 {@code .normalize('NFC')}（paths.ts:149/232、teamMemPaths.ts:85、
     * git.ts:48/71/174/176、state.ts:271/274、envUtils.ts:11）。
     *
     * <p>路径产出共用工具（OPD-R2-06）：AutoMemPaths/TeamMemPaths/ClaudePaths 的目录字符串
     * 产出统一走本方法，保证分解形 Unicode 输入产出与 CC 相同的合成形字节路径。
     *
     * @param s 待归一化字符串（null → null）
     * @return NFC 归一化结果
     */
    public static String normalizeNfc(String s) {
        if (s == null) {
            return null;
        }
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /**
     * 获取 managed settings 目录 · CC original: {@code getManagedFilePath}（managedPath.ts:8-25）
     * <pre>
     * switch (getPlatform()) {
     *   case 'macos':   return '/Library/Application Support/ClaudeCode'
     *   case 'windows': return 'C:\\Program Files\\ClaudeCode'
     *   default:        return '/etc/claude-code'
     * }
     * </pre>
     *
     * <p>CC 另有 ant-only 覆写（USER_TYPE=ant + CLAUDE_CODE_MANAGED_SETTINGS_PATH，managedPath.ts:10-15），
     * Java 后端无 USER_TYPE 概念，跳过；测试经 {@link #setManagedFilePathOverride} 覆写。
     *
     * @return managed settings 目录（按平台）
     */
    public static String getManagedFilePath() {
        if (managedFilePathOverride != null && !managedFilePathOverride.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("managed settings 路径（测试覆写）：{}", managedFilePathOverride);
            }
            return managedFilePathOverride;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "/Library/Application Support/ClaudeCode";
        }
        if (os.contains("win")) {
            return "C:\\Program Files\\ClaudeCode";
        }
        return "/etc/claude-code";
    }

    /**
     * 返回给定 source 的 claude 配置目录路径 · CC original: {@code getSkillsPath(source, dir)}
     * （loadSkillsDir.ts:78-94）
     *
     * <p><b>@Deprecated</b>（2026-08-30 探查）：无生产调用方（仅测试覆盖），返回 claude 目录系 CC
     * mirror。nexusai 生产 skills/commands 读侧走 {@link NexusaiPaths} 动态自有根（nexusai 优先 +
     * claude 只读回落），勿再直读 claude 用户级目录（决策 D2/D4）。
     *
     * <pre>
     * switch (source) {
     *   case 'policySettings':  return join(getManagedFilePath(), '.claude', dir)
     *   case 'userSettings':    return join(getClaudeConfigHomeDir(), dir)
     *   case 'projectSettings': return `.claude/${dir}`
     *   case 'plugin':          return 'plugin'
     *   default:                return ''
     * }
     * </pre>
     *
     * @param source 配置源（'policySettings' | 'userSettings' | 'projectSettings' | 'plugin'）
     * @param dir    子目录名（'skills' | 'commands'，CC :80 联合类型）
     * @return 路径；未知 source → 空串
     */
    @Deprecated(since = "2026-08-30", forRemoval = false)
    public static String getSkillsPath(String source, String dir) {
        // null → default ''（CC switch 缺省分支 loadSkillsDir.ts:92；Java switch 表达式对 null 会 NPE，需前置守卫）
        if (source == null) {
            return "";
        }
        return switch (source) {
            case "policySettings" -> Paths.get(getManagedFilePath(), ".claude", dir).toString();
            // 无生产调用方（探查 A2/A3/A4，仅测试覆盖）；返回 ~/.claude/<dir> 系 CC mirror
            // （loadSkillsDir.ts:78-94 userSettings = join(getClaudeConfigHomeDir(), dir)）。
            // nexusai 用户级 skills 应走 NexusaiPaths.getAppConfigHomeDir()（自有根 ~/.{appName}），
            // 不读 claude 用户级目录（决策 D2/D4）。
            case "userSettings" -> Paths.get(getClaudeConfigHomeDir(), dir).toString();
            case "projectSettings" -> ".claude/" + dir;
            case "plugin" -> "plugin";
            default -> "";
        };
    }

    /**
     * 从 env {@code CLAUDE_CODE_ADDITIONAL_DIRECTORIES} 读取 --add-dir 等价附加目录（按平台路径分隔符拆分）。
     *
     * <p>Java 后端无 CLI/会话状态等价物（concern #1：CC getAdditionalDirectoriesForClaudeMd 来自 --add-dir，
     * state.ts:206-207），以 env 供源（P2-20 拍板 option A）；CC 默认即空（additionalDirs.length===0，
     * loadSkillsDir.ts:659 bare 分支）。未设置 → 空列表。
     *
     * @return 附加目录列表（可能为空）
     */
    public static List<String> getAdditionalDirectoriesFromEnv() {
        String env = System.getenv("CLAUDE_CODE_ADDITIONAL_DIRECTORIES");
        if (env == null || env.isBlank()) {
            return List.of();
        }
        String sep = File.pathSeparator;
        List<String> dirs = new ArrayList<>();
        for (String part : env.split(java.util.regex.Pattern.quote(sep))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                dirs.add(trimmed);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("CLAUDE_CODE_ADDITIONAL_DIRECTORIES 解析出 {} 个附加目录：{}", dirs.size(), dirs);
        }
        return dirs;
    }

    // ── 测试覆写 setter（Java 无法进程内改 env）──

    /**
     * 覆写 Claude 配置根目录（测试用）· 对齐 CC env 变量 CLAUDE_CONFIG_DIR 的可替换性。
     * 传 null 清除覆写恢复默认。
     */
    public static void setConfigDirOverride(String override) {
        configDirOverride = override;
    }

    /**
     * 覆写 managed settings 路径（测试用）· 对齐 CC managedPath.ts:10-15 ant-only env 覆写。
     * 传 null 清除覆写恢复默认。
     */
    public static void setManagedFilePathOverride(String override) {
        managedFilePathOverride = override;
    }
}
