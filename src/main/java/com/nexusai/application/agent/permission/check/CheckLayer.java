package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

/**
 * 权限检查层接口 · 对齐 CC {@code utils/permissions/permissions.ts:1158-1319}
 * 中 {@code hasPermissionsToUseToolInner} 的 10 层规则检查。
 *
 * <h2>设计哲学</h2>
 * <p>CC 实际是把 10 层规则检查放在一个 162 行的函数里。
 * Java 端为可读性 + 单元测试，拆成 10 个独立类（{@code CheckLayer1a_DenyRule}
 * 等）+ 1 个 {@link com.nexusai.application.agent.permission.PermissionPipeline}
 * 编排类。
 *
 * <h2>每层职责</h2>
 * <p>每层检查 1 个 CC 源码行号对应的规则，命中返回 {@link PermissionResult}，
 * 未命中返回 {@code null}。Pipeline 按顺序调用 10 层，第一个非 null 结果即最终决策。
 *
 * <h2>bypass-immune 概念</h2>
 * <p>1d / 1e / 1f / 1g 这 4 层是 <strong>bypass-immune</strong>——即使在
 * {@code BYPASS_PERMISSIONS} mode 也强制 deny/ask。这是 CC 安全底线：
 * 工具自决的 deny、内容特定的 ask、safetyCheck 违规不能被 bypass。
 *
 * <p>实现细节：10 层的顺序本身就是"bypass-immune 优先于 2a bypass 检查"。
 * 1a-1g 先于 2a → 即使 2a 会 allow，前 7 层命中先返回。
 *
 * <h2>实现规范</h2>
 * <ul>
 *   <li>每个 layer 必须是无状态（无 field）</li>
 *   <li>每个 layer 必须线程安全（无 mutable static）</li>
 *   <li>每个 layer 不调用其他 layer（避免循环依赖）</li>
 *   <li>每个 layer 命中即返回，未命中返回 {@code null}</li>
 * </ul>
 *
 * <h2>为什么不叫 PermissionCheck</h2>
 * <p>PermissionCheck 已存在于 CC 教学版（仅 3 种 behavior）。
 * 本接口对齐 CC 实际 4 行为（allow/deny/ask/passthrough）+ 10 层独立检查。
 *
 * @see CheckLayer1a_DenyRule
 * @see CheckLayer1b_AskRule
 * @see CheckLayer1c_ToolCheck
 * @see CheckLayer1d_ToolDeny_Immune
 * @see CheckLayer1e_RequiresUserInteraction
 * @see CheckLayer1f_ContentSpecificAskRule
 * @see CheckLayer1g_SafetyCheck
 * @see CheckLayer2a_BypassMode
 * @see CheckLayer2b_ToolAlwaysAllowed
 * @see CheckLayer3_PassthroughToAsk
 */
@FunctionalInterface
public interface CheckLayer {

    /**
     * 检查该层规则。
     *
     * <p>所有 layer 实现同一签名：(tool, call, input, ctx, permCtx) → PermissionResult。
     * 这是 Pipeline 编排 10 层的统一契约。
     *
     * @param tool     工具实例（CC: Tool 对象）
     * @param call     LLM 的工具调用请求（CC: ToolUseBlock）
     * @param input    已解析的 JSON 输入（与 {@code call.input()} 等价，独立参数便于测试）
     * @param ctx      工具调用上下文（agentId + sessionId + mode + workingDirs）
     * @param permCtx  权限上下文（mode + 8 source 合并规则）
     * @return         命中返回 PermissionResult；未命中返回 null（继续下一层）
     */
    PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    );
}
