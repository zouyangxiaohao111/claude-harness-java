package com.nexusai.application.agent.team;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Teammate 模式快照 · 对齐 CC utils/swarm/backends/teammateModeSnapshot.ts。
 *
 * <p>会话启动时捕获 teammate 模式，遵循 hooksConfigSnapshot.ts 同一模式——运行时配置变更
 * 不影响当前会话的 teammate 模式。
 *
 * <p>CC 真源（grep 自验 teammateModeSnapshot.ts，不信注释）：
 * <ul>
 *   <li>{@code type TeammateMode = 'auto' | 'tmux' | 'in-process'}（:13）；</li>
 *   <li>模块级 {@code initialTeammateMode}（:16）+ {@code cliTeammateModeOverride}（:19）；</li>
 *   <li>{@code setCliTeammateModeOverride}（:25-27）/ {@code getCliTeammateModeOverride}（:33-35）
 *       / {@code clearCliTeammateModeOverride(newMode)}（:43-49，清 override 并直改快照）；</li>
 *   <li>{@code captureTeammateModeSnapshot}（:56-69）：CLI override &gt; config.teammateMode ?? 'auto'；</li>
 *   <li>{@code getTeammateModeFromSnapshot}（:75-87）：null 时 log error + 重新 capture，兜底 'auto'。</li>
 * </ul>
 *
 * <p>Java 映射：CC {@code config.teammateMode} 来自 getGlobalConfig()，Java 无全局配置，
 * 以静态 {@code configTeammateModeSupplier}（部署侧/测试可注入）代理，缺省 null → 'auto'。
 *
 * <p>修复项（U-4 ②）：原 {@code Mode {AUTO, PLAN, CHAT}} 与 CC {@code auto/tmux/in-process}
 * 错位，本类改为 {@link TeammateMode} 三值对齐，并移除原按 teammateId 的 map 快照形态
 * （CC 无该形态）。
 */
public final class TeammateModeSnapshot {

    private static final Logger log = LoggerFactory.getLogger(TeammateModeSnapshot.class);

    /** teammate 模式 · 对齐 CC teammateModeSnapshot.ts:13 {@code 'auto' | 'tmux' | 'in-process'}。 */
    public enum TeammateMode {
        AUTO("auto"),
        TMUX("tmux"),
        IN_PROCESS("in-process");

        private final String ccValue;

        TeammateMode(String ccValue) {
            this.ccValue = ccValue;
        }

        /** CC 原始字符串值（snake/短横线），用于序列化/日志对齐。 */
        public String ccValue() {
            return ccValue;
        }

        /** 从 CC 字符串解析；未知/空返回 null（确定性数据转换，规则五）。 */
        public static TeammateMode fromCc(String cc) {
            if (cc == null) {
                return null;
            }
            return switch (cc) {
                case "auto" -> AUTO;
                case "tmux" -> TMUX;
                case "in-process" -> IN_PROCESS;
                default -> null;
            };
        }
    }

    private TeammateModeSnapshot() {}

    /** 启动时捕获的模式 · 对齐 CC teammateModeSnapshot.ts:16。 */
    private static volatile TeammateMode initialTeammateMode;

    /** CLI override（--teammate-mode 提供，capture 前设置）· 对齐 CC :19。 */
    private static volatile TeammateMode cliTeammateModeOverride;

    /** config.teammateMode 来源 · 对齐 CC getGlobalConfig().teammateMode，缺省 null → 'auto'。 */
    private static volatile Supplier<TeammateMode> configTeammateModeSupplier;

    /**
     * 设置 CLI override · 必须在 captureTeammateModeSnapshot() 前调用。
     * 对齐 CC teammateModeSnapshot.ts:25-27。
     */
    public static void setCliTeammateModeOverride(TeammateMode mode) {
        cliTeammateModeOverride = mode;
        if (log.isDebugEnabled()) {
            log.debug("[TeammateModeSnapshot] 设置 CLI override: {}", mode);
        }
    }

    /**
     * 获取当前 CLI override（无则 null）· 对齐 CC :33-35。
     */
    public static TeammateMode getCliTeammateModeOverride() {
        return cliTeammateModeOverride;
    }

    /**
     * 清除 CLI override 并更新快照为新模式（用户在 UI 改设置时调用，允许生效）。
     * 对齐 CC :43-49。
     *
     * @param newMode 用户新选的模式（直接传入避免竞态）
     */
    public static void clearCliTeammateModeOverride(TeammateMode newMode) {
        cliTeammateModeOverride = null;
        initialTeammateMode = newMode;
        if (log.isDebugEnabled()) {
            log.debug("[TeammateModeSnapshot] CLI override 已清除，新模式: {}", newMode);
        }
    }

    /**
     * 会话启动时捕获 teammate 模式（main.tsx 解析 CLI 后调用）。
     * CLI override 优先于 config。对齐 CC :56-69。
     */
    public static void captureTeammateModeSnapshot() {
        if (cliTeammateModeOverride != null) {
            initialTeammateMode = cliTeammateModeOverride;
            if (log.isDebugEnabled()) {
                log.debug("[TeammateModeSnapshot] 从 CLI override 捕获: {}", initialTeammateMode);
            }
        } else {
            TeammateMode config = configTeammateModeSupplier != null
                    ? configTeammateModeSupplier.get() : null;
            initialTeammateMode = config != null ? config : TeammateMode.AUTO;
            if (log.isDebugEnabled()) {
                log.debug("[TeammateModeSnapshot] 从 config 捕获: {}", initialTeammateMode);
            }
        }
    }

    /**
     * 获取当前会话的 teammate 模式（返回启动时快照，忽略运行时配置变更）。
     * 对齐 CC :75-87。
     */
    public static TeammateMode getTeammateModeFromSnapshot() {
        if (initialTeammateMode == null) {
            // 初始化 bug 指示（CC :77-84）：capture 应在 setup() 发生
            log.warn("[TeammateModeSnapshot] getTeammateModeFromSnapshot 在 capture 前调用，"
                    + "表明初始化顺序错误，执行补捕获");
            captureTeammateModeSnapshot();
        }
        // 兜底 'auto'（不应发生，防御）
        return initialTeammateMode != null ? initialTeammateMode : TeammateMode.AUTO;
    }

    /** 注入 config.teammateMode 来源（部署/测试 seam；CC getGlobalConfig 等价）。 */
    public static void setConfigTeammateModeSupplier(Supplier<TeammateMode> supplier) {
        configTeammateModeSupplier = supplier;
    }

    /** 清除本类全部静态状态（测试 seam）。 */
    public static void resetForTest() {
        initialTeammateMode = null;
        cliTeammateModeOverride = null;
        configTeammateModeSupplier = null;
    }
}
