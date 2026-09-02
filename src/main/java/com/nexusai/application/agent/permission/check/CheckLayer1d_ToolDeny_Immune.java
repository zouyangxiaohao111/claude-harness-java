package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

/**
 * 1d 规则检查：工具返回 Deny → 直接 deny（bypass-immune）
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1226-1228} - 1d 层
 * <p>对应伪代码：<pre>
 *   if (decision.behavior === 'deny') return Deny(...);
 *   // 注意：此 deny 不受 bypassPermissions mode 影响
 * </pre>
 *
 * <h2>检查逻辑</h2>
 * <p>再次检查 tool 的 checkPermissions 返回值（1c 已经调过，本层重复调以隔离语义）：
 * 如果返回 {@link PermissionResult.Deny} → 直接返回。
 *
 * <h2>bypass-immune 状态</h2>
 * <p><strong>YES — bypass-immune ✅</strong>。即使在 BYPASS_PERMISSIONS mode 也强制 deny。
 * 这是 CC 安全底线：工具自决的 deny（如 BashTool 看到危险命令）不能被 bypass 覆盖。
 *
 * <h2>为什么 1c 已调过还要 1d 重复</h2>
 * <p>1c 和 1d 看起来重复，但语义不同：
 * <ul>
 *   <li>1c：工具的"任何决策"（allow / deny / ask / passthrough）→ 直接放行或继续</li>
 *   <li>1d：单独检查 deny 决策 → 即使在 bypass 模式下也生效</li>
 * </ul>
 *
 * <p>Phase 1 简化：1c 命中 Allow 时直接返回，1d 不会再调 tool.checkPermissions。
 * 但 1c 命中 Passthrough 时 Pipeline 继续到 1d，本层会再调一次拿 deny 检查。
 * CC 教学版注释：1d "tool returns deny"，与 1c 共享 toolDecision 但语义独立。
 *
 * <h2>为什么独立 layer</h2>
 * <p>如果合并到 1c，1c 命中 Allow 会绕过 1d 的 bypass-immune 检查。
 * 分成 2 层让 Pipeline 在 1c 命中 Passthrough 时还能在 1d 拦截 deny。
 */
public class CheckLayer1d_ToolDeny_Immune implements CheckLayer {

    /**
     * 执行 1d 层检查：工具 deny 即使 bypass 也生效。
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入
     * @param ctx      工具调用上下文
     * @param permCtx  权限上下文（1d 不需要）
     * @return         DenyDecision 或 null
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // 1. [s03 P3 #12 修补] L1 对齐 CC: 先查 ThreadLocal cache (1c 已存),miss 则 fallback 重调
        PermissionResult toolDecision = ToolCheckCache.get(tool.name());
        if (toolDecision == null) {
            // cache miss (1c 未命中) → fallback 重调 (保持向后兼容:若未来 1d 被独立调用)
            toolDecision = tool.checkPermissions(input, ctx);
        }
        // 2. 仅当工具返回 Deny 时拦截（其他行为继续由 1e/1f/1g 等层处理）
        if (toolDecision instanceof PermissionResult.Deny deny) {
            // 3. bypass-immune：直接返回，不让 Pipeline 继续到 2a bypass 层
            return deny;
        }
        // 4. 非 deny → 继续下一层
        return null;
    }
}
