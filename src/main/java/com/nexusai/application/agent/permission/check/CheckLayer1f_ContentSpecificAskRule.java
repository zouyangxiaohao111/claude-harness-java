package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

/**
 * 1f 规则检查：消费工具 Ask 归因 → 透传 Ask（bypass-immune）
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1244-1250} - 1f 层（主流程
 * {@code hasPermissionsToUseToolInner}）；同语义亦见于
 * {@code utils/permissions/permissions.ts:1134-1142}（{@code checkRuleBasedPermissions}）。
 * <p>对应伪代码（CC 真源行为，非教学版）：<pre>
 *   if (toolPermissionResult?.behavior === 'ask' &&
 *       toolPermissionResult.decisionReason?.type === 'rule' &&
 *       toolPermissionResult.decisionReason.rule.ruleBehavior === 'ask') {
 *     return toolPermissionResult
 *   }
 * </pre>
 *
 * <h2>检查逻辑（P0#3 修复：消费工具 Ask 归因）</h2>
 * <p>本层<b>不重新查规则</b>——CC 中 content-specific ask rule 由工具的
 * {@code checkPermissions} 内部查询并返回带 {@link PermissionDecisionReason.Rule}
 * 归因的 Ask（BashTool exact/prefix ask、WritePermissionChecker / ReadPermissionChecker
 * 的 askRule 等），管线 1f 只消费该归因。Java 1c 已把工具结果
 * 存入 {@link ToolCheckCache}，本层复用（miss 则 fallback 重调，与 1d/1e 一致）。
 * 命中条件：Ask + {@link PermissionDecisionReason.Rule} 归因 +
 * {@code ruleBehavior == ASK} → 透传工具原始 Ask（保留 message / suggestions / 归因）。
 *
 * <h2>bypass-immune 状态</h2>
 * <p><strong>YES — bypass-immune ✅</strong>。即使在 BYPASS_PERMISSIONS mode 也强制 ask。
 * 这是 CC 安全底线：用户在 settings.json 配的 content-specific rule（如
 * {@code "Bash(rm:*)"} 或 {@code "Edit(/etc/**)"}) 由工具 checkPermissions 命中后
 * 必须以 ask 呈现，不能被 bypass 覆盖（CC :1238-1250 注释明示）。
 *
 * <h2>普通工具 Ask 不在此层拦截</h2>
 * <p>工具返回无 Rule 归因的 Ask（如 {@code Other} / {@code SafetyCheck} 归因）→
 * 本层放行，继续下一层（CC :1244-1250 仅 rule 归因 bypass-immune）。
 */
public class CheckLayer1f_ContentSpecificAskRule implements CheckLayer {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(CheckLayer1f_ContentSpecificAskRule.class);

    /**
     * 执行 1f 层检查：消费工具 Ask 的 rule 归因？
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入（仅 cache miss fallback 重调时使用）
     * @param ctx      工具调用上下文（仅 cache miss fallback 重调时使用）
     * @param permCtx  权限上下文（本层不需要——归因来自工具结果而非规则重查）
     * @return         工具原始 Ask（Rule 归因 + ask 行为，bypass-immune）或 null
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // 1. 取工具 checkPermissions 结果（1c 已存 ToolCheckCache；miss 则 fallback 重调，
        //    与 1d/1e 的 cache 消费模式一致）
        PermissionResult toolDecision = ToolCheckCache.get(tool.name());
        if (toolDecision == null) {
            toolDecision = tool.checkPermissions(input, ctx);
        }
        // 2. 消费工具 Ask 归因：Ask + Rule 归因 + ruleBehavior==ASK → 透传（bypass-immune）
        //    对齐 CC permissions.ts:1244-1250（与 :1134-1142 同语义）
        if (toolDecision instanceof PermissionResult.Ask ask
                && ask.reason() instanceof PermissionDecisionReason.Rule ruleReason
                && ruleReason.rule().ruleBehavior() == PermissionBehavior.ASK) {
            if (log.isDebugEnabled()) {
                log.debug("1f 消费工具 Ask 归因（bypass-immune）: tool={} ruleContent={} ruleBehavior={}",
                    tool.name(), ruleReason.rule().ruleValue().ruleContent(),
                    ruleReason.rule().ruleBehavior());
            }
            // 透传工具原始 Ask（保留 message / suggestions / 归因，CC return toolPermissionResult）
            return ask;
        }
        // 3. 非 Rule 归因的 Ask（Other / SafetyCheck 等）或非 Ask → 继续下一层
        //    （CC :1244-1250 仅 rule 归因 bypass-immune，普通工具 ask 由 2a/3 层处理）
        return null;
    }
}
