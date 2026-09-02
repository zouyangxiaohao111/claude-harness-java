package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

/**
 * 1g 规则检查：消费工具 safetyCheck 归因 → 透传 Ask（bypass-immune）
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1255-1260} - 1g 层（主流程
 * {@code hasPermissionsToUseToolInner}）；同语义亦见于
 * {@code utils/permissions/permissions.ts:1144-1152}（{@code checkRuleBasedPermissions}）。
 * <p>对应伪代码（CC 真源行为）：<pre>
 *   if (toolPermissionResult?.behavior === 'ask' &&
 *       toolPermissionResult.decisionReason?.type === 'safetyCheck') {
 *     return toolPermissionResult
 *   }
 * </pre>
 *
 * <h2>检查逻辑（[O33/T4] 自建敏感路径列表已删除）</h2>
 * <p>本层<b>不扫描路径、不自建敏感列表</b>——CC 中 safetyCheck 归因由工具的
 * {@code checkPermissions} 内部产出（write/edit 类工具经 checkPathSafetyForAutoEdit：
 * filesystem.ts:1305-1337；Bash 类工具经路径校验 pathValidation.ts:182-195；
 * PowerShell 类工具 pathValidation.ts:901），管线 1g 只消费该归因
 * （CC :1254 注释 "checkPathSafetyForAutoEdit returns {type:'safetyCheck'} for these paths"）。
 * Java 等价生产者：{@code WritePermissionChecker}（可疑 Windows 模式 / Claude 配置 /
 * 危险文件三检，对应 CC checkPathSafetyForAutoEdit）与 {@code BashTool} 危险命令模式检查。
 * Java 1c 已把工具结果存入 {@link ToolCheckCache}，本层复用（miss 则 fallback 重调，
 * 与 1d/1e/1f 一致）。命中条件：Ask + {@link PermissionDecisionReason.SafetyCheck}
 * 归因 → 透传工具原始 Ask（保留 message / suggestions / 归因，CC return toolPermissionResult）。
 *
 * <h2>bypass-immune 状态</h2>
 * <p><strong>YES — bypass-immune ✅</strong>。即使在 BYPASS_PERMISSIONS mode 也强制 ask。
 * 这是 CC 安全底线：工具产出的 safetyCheck 违规（如写 {@code .git/} 下文件）必须人工确认，
 * 不能被 bypass 覆盖（CC :1252-1260 注释明示）。
 *
 * <h2>与 1f 的关系（为何不合并）</h2>
 * <p>1f（rule 归因）与 1g（safetyCheck 归因）在 CC 中同为对 toolPermissionResult
 * 的顺序 if（:1244-1250 / :1255-1260），Java 保持独立分层：CC 顺序 if = Java
 * 顺序 layer，语义等价；且保持每层对 CC 行号的 1:1 映射（CheckLayer.java 设计哲学）。
 */
public class CheckLayer1g_SafetyCheck implements CheckLayer {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(CheckLayer1g_SafetyCheck.class);

    /**
     * 无参构造（Pipeline 以 {@code new CheckLayer1g_SafetyCheck()} 装配）。
     */
    public CheckLayer1g_SafetyCheck() {
    }

    /**
     * 执行 1g 层检查：消费工具 Ask 的 safetyCheck 归因？
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入（仅 cache miss fallback 重调时使用）
     * @param ctx      工具调用上下文（仅 cache miss fallback 重调时使用）
     * @param permCtx  权限上下文（本层不需要——归因来自工具结果而非规则重查）
     * @return         工具原始 Ask（SafetyCheck 归因 + ask 行为，bypass-immune）或 null
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
        //    与 1d/1e/1f 的 cache 消费模式一致）
        PermissionResult toolDecision = ToolCheckCache.get(tool.name());
        if (toolDecision == null) {
            toolDecision = tool.checkPermissions(input, ctx);
        }
        // 2. 消费工具 Ask 归因：Ask + SafetyCheck 归因 → 透传（bypass-immune）
        //    对齐 CC permissions.ts:1255-1260（与 :1144-1152 同语义）
        if (toolDecision instanceof PermissionResult.Ask ask
                && ask.reason() instanceof PermissionDecisionReason.SafetyCheck safetyCheck) {
            if (log.isDebugEnabled()) {
                log.debug("1g 消费工具 safetyCheck 归因（bypass-immune）: tool={} reason={} classifierApprovable={}",
                    tool.name(), safetyCheck.reason(), safetyCheck.classifierApprovable());
            }
            // 透传工具原始 Ask（保留 message / suggestions / 归因，CC return toolPermissionResult）
            return ask;
        }
        // 3. 非 SafetyCheck 归因的 Ask（Rule / Other 等）或非 Ask → 继续下一层
        //    （CC :1255-1260 仅 safetyCheck 归因 bypass-immune，普通工具 ask 由 2a/3 层处理）
        return null;
    }
}
