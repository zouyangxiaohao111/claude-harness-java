package com.nexusai.application.agent.mcp;

import com.nexusai.model.command.Command;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MCP server 工具方法集合 · 对齐 CC services/mcp/utils.ts（保留 2 个生产消费函数）。
 *
 * <p>L1 语义: 生产消费面（grep 实证）——
 * <ul>
 *   <li>{@link #isMcpCommand(Command)}（CC utils.ts:254-256）：McpToolPool.wirePromptFunctions
 *       判别 prompt 是否 MCP server 产出（FIX-C3 接线）。</li>
 *   <li>{@link #getProjectMcpServerStatus(String, ProjectSettings, boolean, boolean, boolean)}
 *       （CC utils.ts:351-406）：.mcp.json 审批状态机（approved/rejected/pending）。</li>
 *   <li>{@link #normalizeNameForMCP(String)}：委托 {@link McpStringUtils}（CC normalization.ts:17-23
 *       无小写变体），上述两函数共享。</li>
 * </ul>
 * <p>其余 16 个 0 消费静态方法（工具/命令/资源过滤族、hash、stale 驱逐、scope/transport
 * 校验、header 解析、safe URL 提取等）与 isMcpTool（HasName 版，生产由
 * {@link McpServerScope#isMcpTool} 承担）、双枚举（ConfigScope/Transport）、helper 类型
 * （HasName/CommandLike/ServerResourceLike/McpInfo/McpClient/McpState/StaleResult）
 * 已按 D-B10-02/03/04 整段删除——逐方法 grep 仅自引用/注释命中，0 生产消费方
 * （EV-CE-27/EV-CE-18/EV-R2-20）。
 */
public final class McpServerUtils {

    private McpServerUtils() {
        // 纯静态工具类
    }

    /**
     * CC isMcpCommand（services/mcp/utils.ts:254-255，
     * {@code return command.name?.startsWith('mcp__') || command.isMcp === true}）。
     *
     * <p>FIX-C3（拍板#11 part · NG-5 消费侧接线）：签名从 {@code HasName} 改为真实
     * {@link Command} 模型——CC 判别函数消费的是 Command 类型（utils.ts:254），而 Java
     * {@code Command} 是普通 POJO（不实现 {@code HasName}），原 {@code HasName} 版本在生产
     * 无法接收真实模型（isMcp 字段生产零读取方）。改造后生产消费方
     * （{@link McpToolPool#wirePromptFunctions}）可直接以 {@code Command} 调用，isMcp 字段
     * 得到生产读取。
     */
    public static boolean isMcpCommand(Command command) {
        if (command == null || command.getName() == null) return false;
        return command.getName().startsWith("mcp__")
            || Boolean.TRUE.equals(command.getIsMcp());
    }

    /** CC getProjectMcpServerStatus — approved/rejected/pending（utils.ts:351-406）。 */
    public static String getProjectMcpServerStatus(String serverName,
            ProjectSettings settings,
            boolean skipDangerousModePermissionPrompt,
            boolean nonInteractiveSession,
            boolean isSettingSourceEnabledProject) {
        if (settings == null) return "pending";
        String normalized = normalizeNameForMCP(serverName);
        if (settings.disabledMcpjsonServers() != null) {
            for (String n : settings.disabledMcpjsonServers()) {
                if (normalizeNameForMCP(n).equals(normalized)) return "rejected";
            }
        }
        if ((settings.enabledMcpjsonServers() != null
                && settings.enabledMcpjsonServers().stream()
                    .anyMatch(n -> normalizeNameForMCP(n).equals(normalized)))
            || settings.enableAllProjectMcpServers()) {
            return "approved";
        }
        if (skipDangerousModePermissionPrompt && isSettingSourceEnabledProject) return "approved";
        if (nonInteractiveSession && isSettingSourceEnabledProject) return "approved";
        return "pending";
    }

    /**
     * normalizeNameForMCP · 委托 {@link McpStringUtils#normalizeNameForMCP}（无小写变体，
     * 对齐 CC normalization.ts:17-23）。
     *
     * <p>⊕-19：旧实现 {@code toLowerCase().replaceAll("[^a-z0-9_]","_")} 会小写化 server 名，
     * 与 {@link McpStringUtils}（CC 无小写）双实现漂移——server 名含大写/. 空格时前缀
     * 匹配错位（getProjectMcpServerStatus 消费点自动获得 CC 语义）。
     */
    public static String normalizeNameForMCP(String name) {
        if (name == null) return "";
        return McpStringUtils.normalizeNameForMCP(name);
    }

    /** CC getProjectMcpServerStatus 的 settings 输入（utils.ts 对应 settings 读取面）。 */
    public record ProjectSettings(
        List<String> enabledMcpjsonServers,
        List<String> disabledMcpjsonServers,
        boolean enableAllProjectMcpServers) {
        public ProjectSettings {
            enabledMcpjsonServers = enabledMcpjsonServers == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(enabledMcpjsonServers));
            disabledMcpjsonServers = disabledMcpjsonServers == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(disabledMcpjsonServers));
        }
    }
}
