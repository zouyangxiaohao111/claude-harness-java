package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 不可达规则检测器 · 对齐 CC {@code utils/permissions/shadowedRuleDetection.ts}
 * （detectUnreachableRules，基线 e7598af2）
 *
 * <h2>CC 遮蔽模型（S15 对齐）</h2>
 * <ul>
 *   <li><b>仅 tool-wide 遮蔽带 content</b>：同 tool 的 tool-wide deny/ask
 *       （ruleContent 为空，如 {@code "Bash"}）遮蔽带 content 的 allow
 *       （如 {@code "Bash(ls:*)"}，CC shadowedRuleDetection.ts:111-147/160-184）</li>
 *   <li><b>源无关</b>：遮蔽判定不比较 source 优先级——任何 source 的 tool-wide
 *       deny/ask 遮蔽任何 source 的带 content allow（CC 无优先级比较）</li>
 *   <li><b>sandbox 例外</b>：Bash + sandboxAutoAllowEnabled + ask 规则来自个人
 *       source（非 shared）→ 不遮蔽（CC shadowedRuleDetection.ts:131-141）</li>
 *   <li><b>tool-wide allow 不遮蔽</b>：ruleContent 为空的 allow 直接放行
 *       （CC shadowedRuleDetection.ts:123-125/170-172）</li>
 *   <li><b>deny 优先</b>：同一条 allow 被 deny 遮蔽后不再报告 ask 遮蔽
 *       （CC shadowedRuleDetection.ts:215-218 continue）</li>
 * </ul>
 *
 * <h2>CC 原名字段</h2>
 * <ul>
 *   <li>{@link ShadowedRule#shadowedBy} — CC {@code shadowedBy}（shadowedRuleDetection.ts:21）</li>
 *   <li>{@link ShadowedRule#shadowType} — CC {@code shadowType}（:22，'ask' | 'deny'）</li>
 *   <li>{@link ShadowedRule#reason} — CC {@code reason}（:20）</li>
 *   <li>{@link ShadowedRule#fix} — CC {@code fix}（:23）</li>
 * </ul>
 */
@Component
public class ShadowedRuleDetector {

    private static final Logger log = LoggerFactory.getLogger(ShadowedRuleDetector.class);

    /** Bash 工具名 · 对齐 CC {@code tools/BashTool/toolName.ts BASH_TOOL_NAME}（'Bash'）。 */
    private static final String BASH_TOOL_NAME = "Bash";

    /**
     * 沙箱管理器 · 对齐 CC 调用方 sandboxAutoAllowEnabled 计算
     * （PermissionDecisionDebugInfo.tsx:354 / AddPermissionRules.tsx:93 /
     * doctorContextWarnings.ts:220 均传
     * {@code SandboxManager.isSandboxingEnabled() && isAutoAllowBashIfSandboxedEnabled()}；
     * [WF-4 DEC-04] Java {@link SandboxManager#isAutoAllowBashIfSandboxed()} 已对齐 CC
     * 不再含 isEnabled 前置，故本类显式叠加 {@code isEnabled()}（见
     * {@link #detectShadowedRules} 的 sandboxAutoAllowEnabled 计算）。
     *
     * <p>null（未注入）→ sandbox 例外不激活（与 CC 中 sandbox 关闭时行为一致）。
     */
    @Autowired(required = false)
    private SandboxManager sandboxManager;

    /**
     * 注入沙箱管理器 · 对齐 {@code HookPermissionResolver.setSandboxManager} 模式；
     * 测试可手动构造注入（sandbox 例外分支验证），生产由 Spring 字段装配。
     *
     * @param sandboxManager Bash 沙箱管理器；null 允许（sandbox 例外不激活）
     */
    public void setSandboxManager(SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    /**
     * 检测被遮蔽（不可达）的规则 · 对齐 CC {@code detectUnreachableRules}
     * （shadowedRuleDetection.ts:193-234）。
     *
     * <p>CC 入参 {@code options.sandboxAutoAllowEnabled} 在本方法内由注入的
     * {@link SandboxManager} 实时计算。
     *
     * @param ctx 权限上下文（含全部 source 的 allow/deny/ask 规则）
     * @return 不可达规则列表（空列表表示无遮蔽）
     */
    public List<ShadowedRule> detectShadowedRules(ToolPermissionContext ctx) {
        List<ShadowedRule> unreachable = new ArrayList<>();
        boolean sandboxAutoAllowEnabled = sandboxManager != null
            && sandboxManager.isEnabled()
            && sandboxManager.isAutoAllowBashIfSandboxed();

        List<PermissionRule> allowRules = flatten(ctx.alwaysAllowRules());
        List<PermissionRule> askRules = flatten(ctx.alwaysAskRules());
        List<PermissionRule> denyRules = flatten(ctx.alwaysDenyRules());

        if (log.isDebugEnabled()) {
            log.debug("遮蔽检测开始: allow={} deny={} ask={} sandboxAutoAllowEnabled={}",
                allowRules.size(), denyRules.size(), askRules.size(), sandboxAutoAllowEnabled);
        }

        // CC shadowedRuleDetection.ts:202-232：对每条 allow 规则先查 deny 遮蔽，再查 ask 遮蔽
        for (PermissionRule allowRule : allowRules) {
            ShadowResult denyResult = isAllowRuleShadowedByDenyRule(allowRule, denyRules);
            if (denyResult.shadowed()) {
                unreachable.add(buildRecord(allowRule, denyResult));
                // CC :215-218 continue —— deny 遮蔽优先，不再报告 ask 遮蔽
                continue;
            }
            ShadowResult askResult =
                isAllowRuleShadowedByAskRule(allowRule, askRules, sandboxAutoAllowEnabled);
            if (askResult.shadowed()) {
                unreachable.add(buildRecord(allowRule, askResult));
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("遮蔽检测完成: 共 {} 条不可达规则", unreachable.size());
        }
        return unreachable;
    }

    /**
     * allow 是否被同 tool 的 tool-wide deny 完全遮蔽 · 对齐 CC
     * {@code isAllowRuleShadowedByDenyRule}（shadowedRuleDetection.ts:160-184）。
     *
     * <p>遮蔽条件：allow 带 content + 存在同 tool 的 tool-wide deny（ruleContent 为空）。
     * 不比较 source 优先级；无 sandbox 例外。
     */
    private ShadowResult isAllowRuleShadowedByDenyRule(
            PermissionRule allowRule, List<PermissionRule> denyRules) {
        // CC :170-172 —— tool-wide allow（ruleContent 为空）不能被遮蔽
        if (allowRule.ruleValue().ruleContent() == null) {
            return ShadowResult.notShadowed();
        }
        for (PermissionRule denyRule : denyRules) {
            if (isToolWideSameTool(denyRule, allowRule.ruleValue().toolName())) {
                return ShadowResult.shadowed(denyRule, ShadowType.DENY);
            }
        }
        return ShadowResult.notShadowed();
    }

    /**
     * allow 是否被同 tool 的 tool-wide ask 遮蔽 · 对齐 CC
     * {@code isAllowRuleShadowedByAskRule}（shadowedRuleDetection.ts:111-147）。
     *
     * <p>遮蔽条件：allow 带 content + 存在同 tool 的 tool-wide ask。
     * <p>sandbox 例外（CC :131-141）：Bash + sandboxAutoAllowEnabled + ask 规则来自
     * 个人 source（非 shared）→ 不遮蔽；例外基于 <b>ask 规则</b> 的 source
     * （共享 settings 始终警告，因为团队成员可能未启用 sandbox）。
     */
    private ShadowResult isAllowRuleShadowedByAskRule(
            PermissionRule allowRule, List<PermissionRule> askRules,
            boolean sandboxAutoAllowEnabled) {
        // CC :123-125 —— tool-wide allow 不能被遮蔽
        if (allowRule.ruleValue().ruleContent() == null) {
            return ShadowResult.notShadowed();
        }
        for (PermissionRule askRule : askRules) {
            if (!isToolWideSameTool(askRule, allowRule.ruleValue().toolName())) {
                continue;
            }
            // sandbox 例外：Bash + sandbox 自动放行 + ask 来自个人 source → 不遮蔽
            if (BASH_TOOL_NAME.equals(allowRule.ruleValue().toolName())
                    && sandboxAutoAllowEnabled
                    && !isSharedSettingSource(askRule.source())) {
                if (log.isDebugEnabled()) {
                    log.debug("sandbox 例外: Bash 个人 source ask 规则不遮蔽 allow {}",
                        allowRule.ruleValue().toRuleString());
                }
                return ShadowResult.notShadowed();
            }
            return ShadowResult.shadowed(askRule, ShadowType.ASK);
        }
        return ShadowResult.notShadowed();
    }

    /** 规则是否为指定 tool 的 tool-wide 规则（ruleContent 为空）· CC :128-129/:176-177。 */
    private static boolean isToolWideSameTool(PermissionRule rule, String toolName) {
        return rule.ruleValue().ruleContent() == null
            && toolName.equals(rule.ruleValue().toolName());
    }

    /**
     * source 是否共享（对其他用户可见）· 对齐 CC {@code isSharedSettingSource}
     * （shadowedRuleDetection.ts:48-67）：projectSettings / policySettings / command。
     */
    private static boolean isSharedSettingSource(PermissionRuleSource source) {
        return source == PermissionRuleSource.PROJECT_SETTINGS
            || source == PermissionRuleSource.POLICY_SETTINGS
            || source == PermissionRuleSource.COMMAND;
    }

    /** 拼接 CC 语义的 ShadowedRule 记录。 */
    private ShadowedRule buildRecord(
            PermissionRule allowRule, ShadowResult result) {
        ShadowedRule record = new ShadowedRule(
            allowRule,
            result.reason(allowRule),
            result.shadowedBy(),
            result.shadowType(),
            result.fix(allowRule));
        if (log.isInfoEnabled()) {
            log.info("检测到被遮蔽规则: {} [{}] 被 {} 遮蔽 (shadowType={})",
                allowRule.ruleValue().toRuleString(), allowRule.source(),
                result.shadowedBy().ruleValue().toRuleString(), result.shadowType());
        }
        return record;
    }

    /** 扁平化 8 source 规则桶 → 单列表（对齐 CC getAllowRules/getDenyRules/getAskRules flatMap）。 */
    private static List<PermissionRule> flatten(
            java.util.Map<PermissionRuleSource, ? extends Collection<PermissionRule>> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<PermissionRule> flat = new ArrayList<>();
        for (Collection<PermissionRule> bucket : rules.values()) {
            if (bucket != null) {
                flat.addAll(bucket);
            }
        }
        return flat;
    }

    /** 遮蔽类型 · 对齐 CC {@code ShadowType}（shadowedRuleDetection.ts:14，'ask' | 'deny'）。 */
    public enum ShadowType {
        /** CC 'ask' —— 被 tool-wide ask 遮蔽（总会先弹窗询问）。 */
        ASK,
        /** CC 'deny' —— 被 tool-wide deny 完全阻断（更严重）。 */
        DENY
    }

    /** 遮蔽判定结果 · 对齐 CC {@code ShadowResult} 判别联合（shadowedRuleDetection.ts:43-45）。 */
    private record ShadowResult(
            boolean shadowed, PermissionRule shadowedBy, ShadowType shadowType) {

        static ShadowResult notShadowed() {
            return new ShadowResult(false, null, null);
        }

        static ShadowResult shadowed(PermissionRule shadowedBy, ShadowType shadowType) {
            return new ShadowResult(true, shadowedBy, shadowType);
        }

        /** reason 文案 · 对齐 CC detectUnreachableRules :210-212/:223-225。 */
        String reason(PermissionRule allowRule) {
            if (shadowType == ShadowType.DENY) {
                return "Blocked by \"" + shadowedBy.ruleValue().toolName()
                    + "\" deny rule (from " + formatSource(shadowedBy.source()) + ")";
            }
            return "Shadowed by \"" + shadowedBy.ruleValue().toolName()
                + "\" ask rule (from " + formatSource(shadowedBy.source()) + ")";
        }

        /** fix 建议 · 对齐 CC {@code generateFixSuggestion}（shadowedRuleDetection.ts:79-92）。 */
        String fix(PermissionRule allowRule) {
            String shadowingSource = formatSource(shadowedBy.source());
            String shadowedSource = formatSource(allowRule.source());
            String toolName = shadowedBy.ruleValue().toolName();
            if (shadowType == ShadowType.DENY) {
                return "Remove the \"" + toolName + "\" deny rule from " + shadowingSource
                    + ", or remove the specific allow rule from " + shadowedSource;
            }
            return "Remove the \"" + toolName + "\" ask rule from " + shadowingSource
                + ", or remove the specific allow rule from " + shadowedSource;
        }
    }

    /**
     * source 显示名（小写，内联用）· 对齐 CC {@code getSettingSourceDisplayNameLowercase}
     * （utils/settings/constants.ts:72-93），经 {@code permissionRuleSourceDisplayString}
     * （permissions.ts:116-120）调用。
     */
    private static String formatSource(PermissionRuleSource source) {
        return switch (source) {
            case USER_SETTINGS    -> "user settings";
            case PROJECT_SETTINGS -> "shared project settings";
            case LOCAL_SETTINGS   -> "project local settings";
            case FLAG_SETTINGS    -> "command line arguments";
            case POLICY_SETTINGS  -> "enterprise managed settings";
            case CLI_ARG          -> "CLI argument";
            case COMMAND          -> "command configuration";
            case SESSION          -> "current session";
        };
    }

    /**
     * 不可达规则记录 · 对齐 CC {@code UnreachableRule}（shadowedRuleDetection.ts:19-25）。
     *
     * <p>【DEL-WF2-RL-01】删除顶层 {@code source} 字段——CC {@code UnreachableRule} 无
     * 顶层 source（{@code {rule, reason, shadowedBy, shadowType, fix}}）；被遮蔽规则的 source
     * 经 {@code rule.source()} 读取（消费者 PermissionContextBuilder warn 日志已同步改用）。
     *
     * @param rule       被遮蔽的 allow 规则（CC {@code rule}）
     * @param reason     遮蔽原因（CC {@code reason}）
     * @param shadowedBy 执行遮蔽的 tool-wide deny/ask 规则（CC {@code shadowedBy}）
     * @param shadowType 遮蔽类型（CC {@code shadowType}）
     * @param fix        修复建议（CC {@code fix}）
     */
    public record ShadowedRule(
        PermissionRule rule,
        String reason,
        PermissionRule shadowedBy,
        ShadowType shadowType,
        String fix
    ) {}
}
