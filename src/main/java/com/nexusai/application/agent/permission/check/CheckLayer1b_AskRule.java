package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.explainer.PermissionMessageGenerator;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.util.List;

/**
 * 1b 规则检查：整个工具被 ask rule 标记 → 必须问用户
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1184-1206} — 1b 层
 * （{@code hasPermissionsToUseToolInner}；同语义亦见
 * {@code checkRuleBasedPermissions} permissions.ts:1091-1111）
 * <p>对应伪代码：<pre>
 *   if (alwaysAskRules 里有 tool 的 whole-tool ask) {
 *     canSandboxAutoAllow =
 *       tool.name === BASH_TOOL_NAME &&
 *       SandboxManager.isSandboxingEnabled() &&
 *       SandboxManager.isAutoAllowBashIfSandboxedEnabled() &&
 *       shouldUseSandbox(input);
 *     if (!canSandboxAutoAllow) → return Ask(rule=..., suggestions=[AddRules]);
 *     // 否则 fall-through 到 1c，由 BashTool.checkPermissions 的
 *     // sandbox auto-allow（bashPermissions.ts:1829-1843）自动放行
 *   }
 * </pre>
 *
 * <h2>检查逻辑</h2>
 * <p>从 8 source 的 {@code alwaysAskRules} 中查找匹配当前 {@code tool.name()}
 * 的 whole-tool 规则。找到则返回 {@link PermissionResult.Ask}，
 * {@code reason.rule = 命中的 rule}，{@code suggestions} 含"允许此工具"按钮的建议。
 * <p>例外（CC 唯一 fall-through）：工具为 Bash 且 sandbox 激活 +
 * {@code autoAllowBashIfSandboxed} 开启 + 命令可沙箱化（{@code shouldUseSandbox}）
 * 时，返回 {@code null} 让 1c 层调 {@code tool.checkPermissions}——沙箱内命令
 * 由 BashTool 自动放行，不弹窗。
 *
 * <h2>bypass-immune 状态</h2>
 * <p>❌ 不是 bypass-immune——ask rule 是<b>用户配置</b>，bypass 模式下用户已明确
 * 表示"不要问我"，所以 2a 会先命中覆盖。但 1b 仍<b>先于</b> 2a，所以 bypass
 * + ask rule 同时存在时，按 1b 优先 ask。
 *
 * <h2>suggestions 字段</h2>
 * <p>本层生成的 suggestions 是 {@link PermissionUpdate.AddRules} 类型的列表，
 * 把当前工具的 whole-tool ask 转 whole-tool allow，写到 {@code USER_SETTINGS}
 * destination——用户点击"Allow forever"就执行此 update。
 */
public class CheckLayer1b_AskRule implements CheckLayer {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(CheckLayer1b_AskRule.class);

    /**
     * Bash 沙箱管理器（可为 {@code null}）。
     *
     * <p>与 {@code HookPermissionResolver} 同模式：null（未注入）时 sandbox 语义
     * 关闭 = ask rule 一律 Ask，不 fall-through（CC 四条件之一永远为 false）。
     */
    private SandboxManager sandboxManager;

    /**
     * 注入 Bash 沙箱管理器（PermissionPipeline 构造器接线）。
     *
     * @param sandboxManager 沙箱管理器（可为 null，null = sandbox 语义关闭）
     */
    public void setSandboxManager(SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    /**
     * 权限弹窗消息生成器 · 对齐 CC {@code createPermissionRequestMessage}
     * (permissions.ts:137-211)。默认实例（无 Spring 时亦可用，纯无状态类）；
     * {@link PermissionPipeline} 通过 {@link #setMessageGenerator} 注入 Spring 单例。
     */
    private PermissionMessageGenerator messageGenerator = new PermissionMessageGenerator();

    /**
     * 注入消息生成器（PermissionPipeline 构造/后置接线）。
     *
     * @param messageGenerator 弹窗消息生成器（null 时忽略，保留默认实例）
     */
    public void setMessageGenerator(PermissionMessageGenerator messageGenerator) {
        if (messageGenerator != null) {
            this.messageGenerator = messageGenerator;
        }
    }

    /**
     * 执行 1b 层检查：whole-tool ask rule 命中？
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入（1b 只需要传给 shouldUseSandbox 判断）
     * @param ctx      工具调用上下文（1b 不需要）
     * @param permCtx  权限上下文
     * @return         AskDecision（含 suggestions）、null（未命中或 Bash sandbox fall-through）
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // 1. 查 ask rule（whole-tool）
        PermissionRule askRule = RuleQuery.getAskRuleForTool(permCtx, tool);
        if (askRule == null) {
            return null;
        }
        // 2. [S02] 对齐 CC permissions.ts:1189-1193：canSandboxAutoAllow
        //    = tool.name === BASH_TOOL_NAME && isSandboxingEnabled()
        //      && isAutoAllowBashIfSandboxedEnabled() && shouldUseSandbox(input)
        //    仅此场景 fall-through 到 1c tool.checkPermissions（BashTool 沙箱内
        //    命令自动放行）；其余工具（含默认 Allow 的 PowerShell）一律 Ask，
        //    工具默认决策不得覆盖用户 whole-tool ask 规则。
        boolean canSandboxAutoAllow = sandboxManager != null
            && ToolNameConstants.BASH_TOOL_NAME.equals(tool.name())
            && sandboxManager.isEnabled()
            && sandboxManager.isAutoAllowBashIfSandboxed()
            && sandboxManager.shouldUseSandbox(tool.name(), input);
        if (canSandboxAutoAllow) {
            if (log.isDebugEnabled()) {
                log.debug("1b ask 规则命中但 Bash sandbox auto-allow → fall-through 到 1c tool.checkPermissions (tool={})",
                    tool.name());
            }
            return null;
        }
        // 3. 构造 suggestions：把当前 tool 的 whole-tool ask 转 whole-tool allow
        //    写到 USER_SETTINGS destination —— 用户点击"Always allow"按钮即生效
        List<PermissionUpdate> suggestions = List.of(
            new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.USER_SETTINGS,
                List.of(new PermissionRule(
                    askRule.source(),
                    com.nexusai.application.agent.permission.PermissionBehavior.ALLOW,
                    com.nexusai.application.agent.permission.PermissionRuleValue.wholeTool(tool.name())
                )),
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW
            )
        );
        // 4. 命中 → 返回 AskDecision（对齐 CC permissions.ts:1196-1203）
        if (log.isDebugEnabled()) {
            log.debug("1b ask 规则命中 → Ask 分发 (tool={} rule={})",
                tool.name(), RuleQuery.ruleToString(askRule));
        }
        // [WF3-01 OPD-WF3-01-06] 1b ask 消息逐字对齐 CC permissions.ts:1195-1203：
        //   message = createPermissionRequestMessage(tool.name)（<b>不传</b> decisionReason）
        //   → 落通用默认句 "Claude requested permissions to use X, but you haven't
        //   granted it yet."。decisionReason 字段仍为 Rule(askRule)（归因），仅 message
        //   用默认句——旧实现传 ruleReason 走 rule 分支句，偏离 CC 文案。
        PermissionDecisionReason ruleReason = new PermissionDecisionReason.Rule(askRule);
        String message = messageGenerator.createPermissionRequestMessage(tool.name(), null);
        if (log.isDebugEnabled()) {
            log.debug("1b ask 消息生成（对齐 CC permissions.ts:137-211 rule 分支）tool={} message={}",
                tool.name(), message);
        }
        return new PermissionResult.Ask(
            message,
            ruleReason,
            suggestions,
            null,
            null, null, false, null, null);
    }
}
