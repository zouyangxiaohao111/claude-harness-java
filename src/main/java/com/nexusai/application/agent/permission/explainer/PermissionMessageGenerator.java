package com.nexusai.application.agent.permission.explainer;

import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 权限弹窗消息生成器 · 对齐 CC {@code createPermissionRequestMessage}
 * (Open-ClaudeCode/src/utils/permissions/permissions.ts:137-211)。
 *
 * <p>CC 的 {@code createPermissionRequestMessage(toolName, decisionReason?)} 返回
 * <b>单句消息</b>（按 reason.type switch），非旧 Java 的多行 "Tool:/Input:/Reason:/Risk:"
 * 结构。附带两个 helper：
 * <ul>
 *   <li>{@link #getRuleBehaviorDescription} → 对齐 CC PermissionResult.ts:24-35
 *       （'allowed' / 'denied' / 'asked for confirmation for'）</li>
 *   <li>{@link #permissionRuleSourceDisplayString} → 对齐 CC
 *       {@code getSettingSourceDisplayNameLowercase}（constants.ts:72-89）</li>
 * </ul>
 *
 * @see PermissionDecisionReason
 */
@Component
public class PermissionMessageGenerator {

    /** SLF4J logger · 记录"reason → 弹窗消息"数据流（对齐 CC createPermissionRequestMessage）。 */
    private static final Logger log = LoggerFactory.getLogger(PermissionMessageGenerator.class);

    /**
     * 生成 ask 弹窗消息 · 对齐 CC permissions.ts:137-211 单句消息 switch。
     *
     * @param toolName 工具名（如 "Bash" / "Write"）
     * @param decisionReason 决策归因（可 null → 默认句）
     * @return 弹窗消息文本（单句）
     */
    public String createPermissionRequestMessage(
            String toolName, PermissionDecisionReason decisionReason) {
        String message;
        if (decisionReason != null) {
            message = switch (decisionReason) {
                // CC :143-148 classifier 分支（feature BASH_CLASSIFIER || TRANSCRIPT_CLASSIFIER）
                //   Java 侧 transcript classifier 默认启用（application.yml classifier.transcript.enabled=true），
                //   故此处无条件返回 classifier 消息（对齐 CC feature 开启语义）。
                case PermissionDecisionReason.Classifier c ->
                    "Classifier '" + c.classifier() + "' requires approval for this "
                        + toolName + " command: " + c.reason();
                case PermissionDecisionReason.Hook h -> {
                    String hookMessage = h.reason() != null && !h.reason().isBlank()
                        ? "Hook '" + h.hookName() + "' blocked this action: " + h.reason()
                        : "Hook '" + h.hookName() + "' requires approval for this "
                            + toolName + " command";
                    yield hookMessage;
                }
                case PermissionDecisionReason.Rule r -> {
                    String ruleString = r.rule().ruleValue().toRuleString();
                    String sourceString = permissionRuleSourceDisplayString(r.rule().source());
                    yield "Permission rule '" + ruleString + "' from " + sourceString
                        + " requires approval for this " + toolName + " command";
                }
                case PermissionDecisionReason.SubcommandResults s -> {
                    List<String> needsApproval = new ArrayList<>();
                    for (Map.Entry<String, PermissionResult> e : s.reasons().entrySet()) {
                        PermissionResult result = e.getValue();
                        if (result instanceof PermissionResult.Ask
                            || result instanceof PermissionResult.Passthrough) {
                            // CC :165-175 Bash 输出重定向剥离（extractOutputRedirections）——
                            //   Java 端简化：直接入列原始命令（见 bash 包 BashCommandOperatorPermissions，
                            //   重定向剥离为展示级细节，TODO 若弹窗需要再接 CC bash/commands.ts:634）
                            needsApproval.add(e.getKey());
                        }
                    }
                    if (!needsApproval.isEmpty()) {
                        int n = needsApproval.size();
                        yield "This " + toolName + " command contains multiple operations. The following "
                            + plural(n, "part") + " " + plural(n, "requires", "require")
                            + " approval: " + String.join(", ", needsApproval);
                    }
                    yield "This " + toolName + " command contains multiple operations that require approval";
                }
                case PermissionDecisionReason.PermissionPromptTool p ->
                    "Tool '" + p.toolName() + "' requires approval for this " + toolName + " command";
                case PermissionDecisionReason.SandboxOverride so -> "Run outside of the sandbox";
                case PermissionDecisionReason.WorkingDir wd -> wd.reason();
                case PermissionDecisionReason.SafetyCheck sc -> sc.reason();
                case PermissionDecisionReason.Other o -> o.reason();
                case PermissionDecisionReason.Mode m -> {
                    String modeTitle = permissionModeTitle(m.mode());
                    yield "Current permission mode (" + modeTitle
                        + ") requires approval for this " + toolName + " command";
                }
                case PermissionDecisionReason.AsyncAgent a -> a.reason();
            };
        } else {
            message = "Claude requested permissions to use " + toolName
                + ", but you haven't granted it yet.";
        }
        if (log.isDebugEnabled()) {
            log.debug("权限弹窗消息生成（对齐 CC permissions.ts:137-211 11 分支 switch）tool={} reason={} → message={}",
                toolName, decisionReason != null ? decisionReason.getClass().getSimpleName() : "default", message);
        }
        return message;
    }

    /**
     * behavior → 文案 · 对齐 CC {@code getRuleBehaviorDescription}
     * (PermissionResult.ts:24-35) = 'allowed' / 'denied' / 'asked for confirmation for'。
     *
     * <p>[F4a-2] 升为 static（纯函数，无实例态）以便 {@code HookRegistry#toPermissionResult}
     * 静态方法复用，消除内联 denied/asked 文案的结构性重复（DRY）。CC 侧同为模块级导出函数。
     */
    public static String getRuleBehaviorDescription(PermissionBehavior behavior) {
        return switch (behavior) {
            case ALLOW -> "allowed";
            case DENY -> "denied";
            case ASK -> "asked for confirmation for";
        };
    }

    /**
     * source → 展示字符串 · 对齐 CC {@code getSettingSourceDisplayNameLowercase}
     * (utils/settings/constants.ts:72-89)。
     */
    public static String permissionRuleSourceDisplayString(PermissionRuleSource source) {
        return switch (source) {
            case USER_SETTINGS -> "user settings";
            case PROJECT_SETTINGS -> "shared project settings";
            case LOCAL_SETTINGS -> "project local settings";
            case FLAG_SETTINGS -> "command line arguments";
            case POLICY_SETTINGS -> "enterprise managed settings";
            case CLI_ARG -> "CLI argument";
            case COMMAND -> "command configuration";
            case SESSION -> "current session";
        };
    }

    /** mode → 标题 · 对齐 CC {@code permissionModeTitle} = getModeConfig(mode).title
     *  (PermissionMode.ts:123-125 + :46-87)。 */
    static String permissionModeTitle(PermissionMode mode) {
        return switch (mode) {
            case DEFAULT -> "Default";
            case PLAN -> "Plan Mode";
            case ACCEPT_EDITS -> "Accept edits";
            case BYPASS_PERMISSIONS -> "Bypass Permissions";
            case DONT_ASK -> "Don't Ask";
            case AUTO -> "Auto mode";
            case BUBBLE -> "Bubble";
        };
    }

    /** 复数 helper · 对齐 CC {@code plural} (utils/stringUtils.ts:32-37)。 */
    private static String plural(int n, String word) {
        return n == 1 ? word : word + "s";
    }

    /** 复数 helper（显式复数词）· 对齐 CC {@code plural(n, word, pluralWord)}。 */
    private static String plural(int n, String word, String pluralWord) {
        return n == 1 ? word : pluralWord;
    }
}
