package com.nexusai.application.agent.command;

/**
 * BriefCommand · 对齐 CC commands/brief.ts:46-131 /brief 斜杠命令。
 *
 * <p>L1 语义: {@code /brief} 切换 brief-only 模式。开启需 entitlement (isBriefEntitled); 关闭恒允许
 * (防止 GB gate 中途翻转卡死)。切换后 setUserMsgOptIn(newState) 使 SendUserMessage 工具随模式出现/消失,
 * 并向下一轮注入 system-reminder 提示 (Kairos 激活时跳过, 因工具从未真正离开列表)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: NAME="brief" / DESCRIPTION / BRIEF_TOOL_NAME="SendUserMessage" /
 *       DEFAULT_ENABLE_SLASH_COMMAND=false; buildToggleReminder(boolean) / gateBlocksToggle(boolean,boolean)</li>
 *   <li><b>A2 Golden Trace</b>: enable → "Brief mode is now enabled. Use the SendUserMessage tool..."</li>
 *   <li><b>A3 纯函数</b>: reminder 文本仅依赖 newState; gate 决策仅依赖 (newState, entitled)</li>
 *   <li><b>A4 边界</b>: 关闭 (newState=false) 恒不被 gate 阻断; 开启且未 entitled → 阻断</li>
 *   <li><b>A5 业务场景</b>: 未授权用户 /brief 开启 → gateBlocksToggle=true → "Brief tool is not enabled for your account"</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS local-jsx command + React setAppState → Java 纯静态命令元数据 + 决策/文案函数;
 * GrowthBook config parse (enable_slash_command 默认 false) → 常量 + 显式默认。
 */
public final class BriefCommand {

    /** CC brief.ts:48 name */
    public static final String NAME = "brief";
    /** CC brief.ts:49 description */
    public static final String DESCRIPTION = "Toggle brief-only mode";
    /** CC BriefTool/prompt.ts:1 BRIEF_TOOL_NAME (当前 wire name) */
    public static final String BRIEF_TOOL_NAME = "SendUserMessage";
    /** CC brief.ts:29-31 DEFAULT_BRIEF_CONFIG.enable_slash_command */
    public static final boolean DEFAULT_ENABLE_SLASH_COMMAND = false;
    /** CC brief.ts:79 未授权时的用户提示 */
    public static final String NOT_ENTITLED_MESSAGE = "Brief tool is not enabled for your account";

    private BriefCommand() {}

    /**
     * CC brief.ts:74-81 — 仅 on-transition (newState=true) 且未 entitled 时阻断; off 恒允许。
     *
     * @param newState 目标状态 (true=开启)
     * @param entitled 是否有权限
     * @return 是否应阻断本次切换
     */
    public static boolean gateBlocksToggle(boolean newState, boolean entitled) {
        return newState && !entitled;
    }

    /**
     * CC brief.ts:109-118 — 注入下一轮的 system-reminder 文本。
     *
     * @param newState 切换后的状态
     * @return system-reminder 包裹的提示文本
     */
    public static String buildToggleReminder(boolean newState) {
        String body = newState
            ? "Brief mode is now enabled. Use the " + BRIEF_TOOL_NAME
                + " tool for all user-facing output — plain text outside it is hidden from the user's view."
            : "Brief mode is now disabled. The " + BRIEF_TOOL_NAME
                + " tool is no longer available — reply with plain text.";
        return "<system-reminder>\n" + body + "\n</system-reminder>";
    }

    /** CC brief.ts:120-122 onDone 系统消息 */
    public static String toggleResultMessage(boolean newState) {
        return newState ? "Brief-only mode enabled" : "Brief-only mode disabled";
    }
}
