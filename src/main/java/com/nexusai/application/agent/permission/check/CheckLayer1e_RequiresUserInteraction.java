package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1e 规则检查：工具需要用户交互 + 工具返回 Ask → 原样透传工具 Ask（bypass-immune）
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1230-1236} - 1e 层（2026-08-18 grep 自验）
 * <p>对应伪代码：<pre>
 *   if (tool.requiresUserInteraction?.() && toolPermissionResult?.behavior === 'ask') {
 *     return toolPermissionResult          // :1235 —— 原样透传，不自建 message/reason/suggestions
 *   }
 * </pre>
 *
 * <h2>[IMP-12] OPD-WF3-DC-v4-01 / OPD-WF3-01-20 拍板：对齐 CC 透传（删除自建 Ask）</h2>
 * <ul>
 *   <li><b>旧实现</b>：自建 {@code PermissionResult.Ask} —— message="Tool 'X' requires explicit
 *       user interaction (cannot bypass)." + reason=Other("requiresUserInteraction") + 空 suggestions
 *       + updatedInput=null。弹窗文案/归因/updatedInput 均偏离 CC（△-23，探查 EV-WF3-DC-040）。</li>
 *   <li><b>拍板</b>：对齐 CC 透传 —— {@code return toolPermissionResult} 原样返回工具自决的 Ask，
 *       message/reason/suggestions/updatedInput/metadata/contentBlocks 全保留。</li>
 * </ul>
 *
 * <h2>检查逻辑</h2>
 * <p>两个条件必须同时满足：
 * <ol>
 *   <li>工具的 {@link Tool#requiresUserInteraction()} 返回 true</li>
 *   <li>工具的 {@link Tool#checkPermissions} 返回 {@link PermissionResult.Ask}
 *       （经 {@link ToolCheckCache} 复用 1c 结果，miss 则 fallback 重调）</li>
 * </ol>
 *
 * <p>两者都满足 → 返回工具自决的 Ask（原样透传）。即使在 BYPASS_PERMISSIONS mode 也强制 ask。
 *
 * <h2>bypass-immune 状态</h2>
 * <p><strong>YES — bypass-immune ✅</strong>。即使在 BYPASS_PERMISSIONS mode 也强制 ask。
 * 这是 CC 安全底线：必须用户交互确认的工具（{@code AskUserQuestion}、
 * {@code permission_prompt_tool} 等）不能被 bypass。
 *
 * <h2>应用场景</h2>
 * <p>{@code AskUserQuestion} 工具 override 了 {@code requiresUserInteraction()}
 * 返回 true，且其 {@code checkPermissions} 对所有调用返回 Ask。
 * 即使 agent 在 bypass 模式调此工具，1e 仍强制弹窗让用户选 —— 弹窗文案/归因透传工具自决。
 */
public class CheckLayer1e_RequiresUserInteraction implements CheckLayer {

    /** SLF4J logger · 数据流日志（CLAUDE.md 规范）。 */
    private static final Logger log = LoggerFactory.getLogger(CheckLayer1e_RequiresUserInteraction.class);

    /**
     * 执行 1e 层检查：必须用户交互的工具即使 bypass 也 ask（原样透传工具自决结果）。
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入
     * @param ctx      工具调用上下文
     * @param permCtx  权限上下文（1e 不需要）
     * @return         工具自决的 Ask（原样透传）或 null
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // 1. 检查工具是否声明"必须用户交互"
        if (!tool.requiresUserInteraction()) {
            return null;
        }
        // 2. [s03 P3 #12 修补] L1 对齐 CC: 先查 ThreadLocal cache (1c 已存),miss 则 fallback 重调
        PermissionResult toolDecision = ToolCheckCache.get(tool.name());
        if (toolDecision == null) {
            toolDecision = tool.checkPermissions(input, ctx);
        }
        // 工具自决为 Ask？
        if (!(toolDecision instanceof PermissionResult.Ask)) {
            return null;
        }
        // 3. [IMP-12 / OPD-WF3-DC-v4-01] 对齐 CC permissions.ts:1235 return toolPermissionResult ——
        //    原样透传工具自决的 Ask（message/reason/suggestions/updatedInput 全保留），不自建 message。
        if (log.isDebugEnabled()) {
            log.debug("1e 命中：requiresUserInteraction + 工具 Ask → 原样透传 (tool={} message={} reason={})",
                tool.name(), ((PermissionResult.Ask) toolDecision).message(),
                ((PermissionResult.Ask) toolDecision).reason());
        }
        return toolDecision;
    }
}
