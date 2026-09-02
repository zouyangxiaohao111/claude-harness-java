package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public static String getAppConfigHomeDir() {
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
}
