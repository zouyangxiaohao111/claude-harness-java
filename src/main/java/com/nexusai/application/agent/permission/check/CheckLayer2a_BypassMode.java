package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 2a 规则检查：bypass 直通 / plan 可用 → Allow（除 4 个 bypass-immune）
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1268-1281} - 2a 层
 * <p>对应伪代码（CC 真源，2026-08-18 grep 自验）：<pre>
 *   shouldBypass = mode === 'bypassPermissions'                       // :1269 —— bypass 直通不查 available
 *               || (mode === 'plan' && isBypassPermissionsModeAvailable); // :1270-1271 —— 仅 plan 查 available
 *   if (shouldBypass) return Allow(reason=Mode(mode), updatedInput=getUpdatedInputOrFallback(...));
 * </pre>
 *
 * <h2>[IMP-12] OPD-WF3-01-12 拍板：bypass 直通对齐 CC（删除旧更严格门）</h2>
 * <ul>
 *   <li><b>旧实现</b>：两种 mode（BYPASS/PLAN）都要求 {@code isBypassPermissionsModeAvailable=true}
 *       才 Allow —— 比 CC 更严格（Java 安全底线选择）。</li>
 *   <li><b>拍板</b>：对齐 CC 宽松 —— {@code mode==='bypassPermissions'} 直接 Allow 不查 available；
 *       仅 {@code mode==='plan'} 分支查 {@code isBypassPermissionsModeAvailable}（用户最初以
 *       bypass 启动 plan 时也走 bypass 路径）。</li>
 *   <li><b>P2 #6 保留</b>：reason 仍为 {@link PermissionDecisionReason.Mode} 类型
 *       （CC permissions.ts:1276-1279 也是 Mode(record)）。</li>
 * </ul>
 *
 * <h2>检查逻辑</h2>
 * <p>{@code shouldBypass = mode==BYPASS_PERMISSIONS || (mode==PLAN && isBypassPermissionsModeAvailable)}。
 * <p>命中 → 返回 Allow（updatedInput 回填 1c 改写 + reason 为 Mode 类型）。
 *
 * <h2>bypass-immune 状态</h2>
 * <p>不是 bypass-immune —— 这就是 bypass 检查本身。Pipeline 在 1a-1g 之后
 * 才到 2a，所以 1d / 1e / 1f / 1g 已先拦截的危险操作不会到 2a。
 *
 * <h2>isBypassPermissionsModeAvailable 字段</h2>
 * <p>仅 plan 分支消费（CC :1270-1271）。mode=BYPASS_PERMISSIONS 直通不受该字段影响
 * （CC :1268-1269）；mode=PLAN 且该字段 false → 不 bypass，落 2b/3。
 */
public class CheckLayer2a_BypassMode implements CheckLayer {

    /** SLF4J logger · 数据流日志（CLAUDE.md 规范）。 */
    private static final Logger log = LoggerFactory.getLogger(CheckLayer2a_BypassMode.class);

    /**
     * 执行 2a 层检查：bypass 直通 / plan 可用 → Allow。
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入（Allow 透传）
     * @param ctx      工具调用上下文（2a 不需要）
     * @param permCtx  权限上下文（检查 mode + plan 分支的 bypass-available）
     * @return         AllowDecision 或 null
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // [IMP-12 / OPD-WF3-01-12] 对齐 CC permissions.ts:1268-1271 —— bypass 直通（仅 plan 查 available）。
        //   shouldBypass = mode==='bypassPermissions' || (mode==='plan' && isBypassPermissionsModeAvailable)
        PermissionMode mode = permCtx.mode();
        boolean shouldBypass = mode == PermissionMode.BYPASS_PERMISSIONS
                || (mode == PermissionMode.PLAN && permCtx.isBypassPermissionsModeAvailable());
        if (!shouldBypass) {
            // 未命中 → 继续下一层（2b allow / 3 passthrough）
            return null;
        }
        // 命中 → 返回 Allow（updatedInput 回填 1c 改写 + reason 为 Mode 类型, [P2 #6]）
        //   [OPD-WF3-01-02 / MIS-03] 对齐 CC permissions.ts:1273-1280：
        //   updatedInput = getUpdatedInputOrFallback(toolPermissionResult, input) ——
        //   保 1c tool.checkPermissions 改写的 updatedInput（如 hook/工具改写 input），
        //   不再返回 raw input。
        if (log.isDebugEnabled()) {
            log.debug("2a bypass 命中 (tool={} mode={} isBypassPermissionsModeAvailable={})",
                tool.name(), mode, permCtx.isBypassPermissionsModeAvailable());
        }
        return new PermissionResult.Allow(
            ToolCheckCache.getUpdatedInputOrFallback(tool.name(), input),
            new PermissionDecisionReason.Mode(mode),
            null, false, null, null);
    }
}
