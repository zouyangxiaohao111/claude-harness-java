package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

/**
 * 1a 规则检查：整个工具被 deny rule 禁用 → 直接 deny
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1158-1181} — 1a 层
 * <p>对应伪代码：<pre>
 *   if (alwaysDenyRules 里有 tool 的 whole-tool deny) → return Deny(rule=...)
 * </pre>
 *
 * <h2>检查逻辑</h2>
 * <p>从 8 source 的 {@code alwaysDenyRules} 中查找匹配当前 {@code tool.name()}
 * 的 whole-tool 规则。找到则返回 {@link PermissionResult.Deny}，
 * {@code reason.rule = 命中的 rule}。
 *
 * <h2>bypass-immune 状态</h2>
 * <p>❌ 不是 bypass-immune——在 {@code BYPASS_PERMISSIONS} 模式会<b>先</b>命中
 * 本层（因 1a 先于 2a），所以实际"bypass 也 deny"。但 CC 教学版注释把 1a
 * 列为非 bypass-immune，因为它的 deny 来源是<b>用户主动配置</b>（settings.json
 * deny rule），不是安全底线。
 *
 * <h2>优先级</h2>
 * <p>是 10 层检查的<strong>第一层</strong>，确保 deny 规则最先生效（fail-fast）。
 * 如果用户把工具 deny 了，绝不进入 bypass / allow / ask 路径。
 */
public class CheckLayer1a_DenyRule implements CheckLayer {

    /**
     * 执行 1a 层检查：whole-tool deny rule 命中？
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入（1a 不需要，仅为接口一致）
     * @param ctx      工具调用上下文（1a 不需要）
     * @param permCtx  权限上下文
     * @return         DenyDecision（命中）或 null（未命中，继续下一层）
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // 1. 从 8 source 的 deny rules 查找 whole-tool deny rule
        PermissionRule denyRule = RuleQuery.getDenyRuleForTool(permCtx, tool);
        if (denyRule != null) {
            // 命中 whole-tool deny → 构造 DenyDecision
            // [WF3-02 DC-01 + OPD-WF3-01-06] 对齐 CC permissions.ts:1171-1181：
            //   message 逐字 = `Permission to use ${tool.name} has been denied.`
            //   （不再追加 "by rule from %s" 分支句）
            String message = "Permission to use " + tool.name() + " has been denied.";
            return new PermissionResult.Deny(
                message,
                new PermissionDecisionReason.Rule(denyRule),
                null
            );
        }
        // [WF3-02 DC-01] 删除 1a 第 2 步内容 deny 直查（旧
        //   RuleQuery.getDenyRuleByContentsForTool）——CC 检查链无内容 deny 直查
        //   （1a 仅 whole-tool getDenyRuleForTool，permissions.ts:1079-1089）；内容 deny
        //   一律经 1c tool.checkPermissions → 1d 表面化（permissions.ts:1128-1132）。
        //   旧实现改变命中顺序与 message，与 WF3-01 △-21 同源，随本决策删除。
        // 2. 未命中 → 返回 null 让 Pipeline 进入下一层
        return null;
    }
}
