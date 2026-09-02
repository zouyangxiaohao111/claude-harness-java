package com.nexusai.application.agent.bash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BashModeValidation · 对齐 CC tools/BashTool/modeValidation.ts.
 *
 * <p>L1 语义: Bash 命令在当前 permission mode 下的额外判定。
 * 当前仅在 {@code acceptEdits} mode 下自动允许 7 个文件系统命令
 * (mkdir/touch/rm/rmdir/mv/cp/sed)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 返回类型统一为
 *       {@link com.nexusai.application.agent.permission.PermissionResult}
 *       （对齐 CC PermissionResult union，
 *       CC 原名: PermissionResult @ Open-ClaudeCode/src/types/permissions.ts:251）；
 *       {@link #checkPermissionMode(String, String)} (command, mode) → PermissionResult;
 *       {@link #getAutoAllowedCommands(String)} (mode) → {@code List<String>}</li>
 *   <li><b>A2 Golden Trace</b>: mode='acceptEdits' + baseCmd is filesystem →
 *       'allow' (updatedInput={command}, decisionReason.type='mode'；
 *       CC modeValidation.ts:42-49);
 *       mode='acceptEdits' + baseCmd not filesystem → 'passthrough';
 *       mode='bypassPermissions'/'dontAsk' → 'passthrough' (handled elsewhere)</li>
 *   <li><b>A3 纯函数</b>: 无副作用;first command triggering non-passthrough wins</li>
 *   <li><b>A4 边界</b>: 空 command (split returns []) → 'passthrough' 'No mode-specific validation required'</li>
 *   <li><b>A5 业务场景</b>: 用户在 acceptEdits mode 下 mkdir → 自动批准;rm → 自动批准;git push → passthrough (上层处理)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS discriminated union ({@code 'allow'|'passthrough'}) →
 * Java {@link PermissionResult} sealed interface + {@code instanceof} 分派;
 * CC {@code updatedInput: {command: cmd}} → {@code JsonNode{command: cmd}}
 * （Java 签名无 input 结构，取子命令近似，CC 原名: updatedInput @ modeValidation.ts:44）;
 * first-match semantics → Java for-loop early return。
 */
public final class BashModeValidation {

    private static final Logger log = LoggerFactory.getLogger(BashModeValidation.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final List<String> ACCEPT_EDITS_ALLOWED_COMMANDS = List.of(
        "mkdir", "touch", "rm", "rmdir", "mv", "cp", "sed");

    private BashModeValidation() {}

    private static boolean isFilesystemCommand(String command) {
        return ACCEPT_EDITS_ALLOWED_COMMANDS.contains(command);
    }

    /**
     * Main entry point: validates the bash command against the current permission mode.
     * Returns the first non-passthrough result for any subcommand.
     *
     * @param command raw bash command (may have multiple subcommands)
     * @param mode current ToolPermissionContext mode
     */
    public static PermissionResult checkPermissionMode(String command, String mode) {
        // Skip in bypass mode（CC modeValidation.ts:77-82）
        if ("bypassPermissions".equals(mode)) {
            return new PermissionResult.Passthrough("Bypass mode is handled in main permission flow",
                null, List.of(), null, null);
        }
        // Skip in dontAsk mode（CC modeValidation.ts:85-90）
        if ("dontAsk".equals(mode)) {
            return new PermissionResult.Passthrough("DontAsk mode is handled in main permission flow",
                null, List.of(), null, null);
        }

        // Split command (CC uses splitCommand_DEPRECATED → BashParser.splitCommands 引号/subshell/heredoc 感知)
        List<String> commands = splitCommand(command);
        for (String cmd : commands) {
            PermissionResult result = validateCommandForMode(cmd, mode);
            if (!(result instanceof PermissionResult.Passthrough)) {
                return result;
            }
        }
        return new PermissionResult.Passthrough("No mode-specific validation required",
            null, List.of(), null, null);
    }

    private static PermissionResult validateCommandForMode(String cmd, String mode) {
        String trimmed = cmd.trim();
        if (trimmed.isEmpty()) {
            return new PermissionResult.Passthrough("Base command not found", null, List.of(), null, null);
        }
        String[] tokens = trimmed.split("\\s+");
        String baseCmd = tokens[0];
        if (baseCmd.isEmpty()) {
            return new PermissionResult.Passthrough("Base command not found", null, List.of(), null, null);
        }
        // In Accept Edits mode, auto-allow filesystem operations（CC modeValidation.ts:38-50）
        if ("acceptEdits".equals(mode) && isFilesystemCommand(baseCmd)) {
            if (log.isDebugEnabled()) {
                log.debug("acceptEdits 模式自动放行文件系统命令: [{}]", cmd);
            }
            return new PermissionResult.Allow(
                MAPPER.valueToTree(Map.of("command", cmd)),
                new PermissionDecisionReason.Mode(PermissionMode.ACCEPT_EDITS),
                null, false, null, List.of());
        }
        return new PermissionResult.Passthrough(
            "No mode-specific handling for '" + baseCmd + "' in " + mode + " mode",
            null, List.of(), null, null);
    }

    /**
     * 子命令切分 · 对齐 CC modeValidation.ts:92 {@code splitCommand_DEPRECATED(input.command)}。
     *
     * <p>复用 {@link BashParser#splitCommands}（引号感知 + 子shell/heredoc 深度感知，
     * {@code echo "a;b"} 保持 1 段不切；A2 已用同款 splitter 注入 CommandSemanticsInterpreter）。
     * 切出的段可能残留行尾操作符字符（如 {@code "echo hi &"}），baseCmd 取首词不受影响。
     */
    private static List<String> splitCommand(String command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        return BashParser.splitCommands(command);
    }

    /**
     * Returns the list of commands auto-allowed under the given mode（CC modeValidation.ts:111-115）.
     */
    public static List<String> getAutoAllowedCommands(String mode) {
        return "acceptEdits".equals(mode) ? ACCEPT_EDITS_ALLOWED_COMMANDS : List.of();
    }
}
