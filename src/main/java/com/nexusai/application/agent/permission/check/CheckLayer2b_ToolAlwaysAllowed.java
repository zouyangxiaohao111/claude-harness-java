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
 * 2b 规则检查：整个工具被 allow rule 标记 → Allow
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1284-1297} - 2b 层
 * <p>对应伪代码：<pre>
 *   const rule = toolAlwaysAllowedRule(permCtx, tool);
 *   if (rule) return Allow(reason=Rule(rule), ...);
 * </pre>
 *
 * <h2>[s03 P2 #6 修补]</h2>
 * <ul>
 *   <li>修补前: reason 为字符串拼接 {@code new Other("Tool 'X' is always allowed by...")},
 *       审计归因仅用于 log/human。</li>
 *   <li>修补后: reason 为 {@link PermissionDecisionReason.Rule}({@link PermissionRule} 引用),
 *       审计归因完整 — CC 对齐。</li>
 * </ul>
 *
 * <h2>检查逻辑</h2>
 * <p>从 8 source 的 alwaysAllowRules 中查找 whole-tool allow rule（{@link RuleQuery#toolAlwaysAllowedRule}）。
 * 找到则返回 Allow。
 *
 * <h2>bypass-immune 状态</h2>
 * <p>不是 bypass-immune —— allow rule 是用户授权，应该被 bypass "再次确认"。
 * Pipeline 顺序：2a bypass 先于 2b allow。但实际效果一致（都是 allow）。
 *
 * <h2>与 1c 的关系</h2>
 * <p>1c 是工具自决 allow（{@code tool.checkPermissions} 返回 Allow）。
 * 2b 是用户配置 allow（{@code settings.json} 配 {@code "Bash"} allow）。
 * 两者都走 allow 路径但来源不同。
 *
 * <h2>为什么 2a 在 2b 前</h2>
 * <p>CC 教学版注释：bypass mode 优先于普通 allow rule 检查。
 * 因为 bypass 模式代表"用户明确表示不检查"，比"配 allow rule"更强烈。
 */
public class CheckLayer2b_ToolAlwaysAllowed implements CheckLayer {

    /**
     * 执行 2b 层检查：whole-tool allow rule 命中？
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入（Allow 透传）
     * @param ctx      工具调用上下文（2b 不需要）
     * @param permCtx  权限上下文
     * @return         AllowDecision（whole-tool allow）或 null
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // 1. 查 whole-tool allow rule（来自 8 source 合并）
        PermissionRule allowRule = RuleQuery.toolAlwaysAllowedRule(permCtx, tool);
        if (allowRule == null) {
            return null;
        }
        // 2. 命中 → 返回 AllowDecision
        //    [s03 P2 #6 修补] reason 改 PermissionDecisionReason.Rule 类型(record 包住
        //    PermissionRule 引用),对齐 CC permissions.ts:1292-1295。
        //    修补前用 Other(字符串拼接),审计归因链断裂(rule 信息无法结构化反查)。
        //    [OPD-WF3-01-02 / MIS-03] 对齐 CC permissions.ts:1289-1297：
        //    updatedInput = getUpdatedInputOrFallback(toolPermissionResult, input) ——
        //    保 1c tool.checkPermissions 改写的 updatedInput，不再返回 raw input。
        return new PermissionResult.Allow(
            com.nexusai.application.agent.permission.ToolCheckCache
                .getUpdatedInputOrFallback(tool.name(), input),
            new PermissionDecisionReason.Rule(allowRule),
            null, false, null, null);
    }
}
