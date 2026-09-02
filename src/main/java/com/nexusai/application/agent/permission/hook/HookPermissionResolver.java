package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * [Session H8] hook 权限决策解析器 · 对齐 CC {@code resolveHookPermissionDecision}
 * (Open-ClaudeCode/src/services/tools/toolHooks.ts:332-433).
 *
 * <p><b>核心不变量</b>: hook allow <b>不</b>绕过 settings deny/ask 规则 —
 * {@link #checkRuleBasedPermissions} 仍然生效 (对齐 CC toolHooks.ts:372-405
 * "Hook allow skips the interactive prompt, but deny/ask rules still apply").
 * 同时处理 requiresUserInteraction / requireCanUseTool 守卫与 ask 的 forceDecision 透传.
 *
 * <p>本类是无状态纯逻辑类: 所有输入经 {@link #resolve} 参数进入, 可安全地
 * 由 {@link com.nexusai.application.agent.tool.StreamingToolExecutor} 单实例复用.
 *
 * <p><b>[hooks_v3 H-PERM-02 · 5-W3-5]</b> {@link #checkRuleBasedPermissions} 已对齐 CC
 * {@code permissions.ts:1071-1156} 逐层语义:
 * <ul>
 *   <li><b>删除 Java 内容 deny 快捷层</b> (旧 getDenyRuleByContentsForTool 块): CC
 *       {@code checkRuleBasedPermissions} 无内容 deny 直查, 内容 deny 一律经
 *       1c {@code tool.checkPermissions} → 1d 表面化 (Bash/Skill 等工具内部
 *       用 getRuleByContentsForTool 判内容规则, CC permissions.ts:349-359).</li>
 *   <li><b>补 inputSchema.parse 门禁</b>: 1c 前先用 {@link ToolInputValidator#safeParseSchema}
 *       (= zod {@code tool.inputSchema.parse}, CC permissions.ts:1119) 解析 input,
 *       解析失败 → logError 等价 → 按"无规则异议"返回 null (CC catch → passthrough 语义).</li>
 * </ul>
 *
 * <p><b>[hooks_v3 H-PERM-02 · 1-7]</b> 本类为 {@link Component} Spring bean, 实例注入:
 * {@link SandboxManager} 经 {@link #setSandboxManager} 注入 (StreamingToolExecutor
 * buildStreamingExecutor 接线), {@link ToolInputValidator} 经 {@link #setInputValidator}
 * 注入 (gate 依赖). 静态单例 {@code SHARED} 与静态 {@code setSharedSandboxManager} 已于
 * 合并阶段删除 (对齐 CC resolveHookPermissionDecision 无状态纯函数, toolHooks.ts:332-433,
 * 无单例无 setter).</p>
 *
 * <h2>分支语义 (逐行对齐 CC toolHooks.ts:344-433)</h2>
 * <table>
 *   <tr><th>CC 行号</th><th>分支</th><th>Java 行为</th></tr>
 *   <tr><td>344-345</td><td>{@code requiresInteraction}/{@code requireCanUseTool} 读取</td>
 *       <td>{@link Tool#requiresUserInteraction()} / {@code ctx.requireCanUseTool()}</td></tr>
 *   <tr><td>347-406</td><td>behavior == 'allow'</td>
 *       <td>守卫满足 → canUseTool; 否则规则复检 (null→hook allow / deny→deny / ask→canUseTool)</td></tr>
 *   <tr><td>408-411</td><td>behavior == 'deny'</td>
 *       <td>直接返回 deny, 不走 canUseTool</td></tr>
 *   <tr><td>415-432</td><td>无决策 / 'ask'</td>
 *       <td>forceDecision = hook ask (仅 ask); 走 canUseTool</td></tr>
 * </table>
 *
 * <p><b>Java 端 updatedInput 语义</b>: {@link PermissionResult.Allow#updatedInput} 是
 * Java record 强制非空字段 (CC 端 optional), "hook 是否提供了 updatedInput" 的判别
 * 由参数 {@code hookUpdatedInput} (AHR.updatedInput(), CC {@code result.updatedInput}
 * toolHooks.ts:348/354) 承载 — {@code null} = hook 未改 input. 生效 input 由调用方
 * 先应用全替换 (CC toolExecution.ts:837 {@code processedInput = result.updatedInput}),
 * 本解析器统一用已生效的 {@code input}.
 *
 * @see com.nexusai.application.agent.tool.StreamingToolExecutor
 * @since Session H8
 */
@Component
public class HookPermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(HookPermissionResolver.class);

    /** Bash 工具名 · 对齐 CC BASH_TOOL_NAME (permissions.ts:1187). */
    private static final String BASH_TOOL_NAME = "Bash";

    /**
     * [H8 v2 补全 H8-GAP-1] 沙箱管理器 · {@code null} = 未接线 (测试/手动构造).
     *
     * <p>WHY: CC checkRuleBasedPermissions (permissions.ts:1186-1205) 命中 ask rule 但
     * {@code canSandboxAutoAllow} (Bash 工具 + sandbox 激活 + auto-allow + 命令可沙箱化) 时
     * fall-through 到 1c tool.checkPermissions 自动放行, 不弹窗. 由
     * {@link #setSandboxManager} 注入 (StreamingToolExecutor → buildStreamingExecutor 接线);
     * null 时 sandbox 语义关闭 = 与 H8-GAP-1 登记前行为一致 (ask rule 一律 Ask).
     */
    private SandboxManager sandboxManager;

    /**
     * 注入沙箱管理器 · 由 StreamingToolExecutor 接线 (buildStreamingExecutor → exec.setSandboxManager).
     *
     * @param sandboxManager Bash 沙箱管理器; null 允许 (未接线时 sandbox auto-allow 不激活)
     */
    public void setSandboxManager(SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    /**
     * [hooks_v3 H-PERM-02 · 5-W3-5] 工具输入验证器 · {@code null} = 未接线 (测试/手动构造).
     *
     * <p>WHY: {@link #checkRuleBasedPermissions} 1c 需补 CC {@code inputSchema.parse}
     * 门禁 (permissions.ts:1119 {@code tool.inputSchema.parse(input)}); Java 端等价 =
     * {@link ToolInputValidator#safeParseSchema} (zod safeParse, toolOrchestration.ts:97-107).
     * 默认自建实例 (无状态纯函数, 与 SyntheticOutputTool 同模式); 可由 Spring
     * ({@code @Autowired}) 或测试覆盖.
     */
    private ToolInputValidator inputValidator = new ToolInputValidator();

    /**
     * 注入工具输入验证器 · {@link #checkRuleBasedPermissions} 1c inputSchema.parse 门禁依赖.
     *
     * <p>[hooks_v3 1-7] 本类是 {@link Component} Spring bean, 由容器注入
     * {@link ToolInputValidator} (无状态, 无循环依赖); null-safe 保持默认实例.
     *
     * @param inputValidator 工具输入验证器 (zod safeParse 等价); null 允许 (保持默认)
     */
    @Autowired(required = false)
    public void setInputValidator(ToolInputValidator inputValidator) {
        if (inputValidator != null) {
            this.inputValidator = inputValidator;
        }
    }

    /**
     * canUseTool 等价回调 · 对齐 CC {@code CanUseToolFn}
     * (Open-ClaudeCode/src/hooks/useCanUseTool.tsx:27, toolHooks.ts:337).
     *
     * <p>Java 端等价实现 = {@link ToolPermissionGate#check} 6 参重载
     * (forceDecision 透传, 对齐 CC useCanUseTool.tsx:37
     * {@code forceDecision !== undefined ? Promise.resolve(forceDecision) : hasPermissionsToUseTool(...)}).
     *
     * @param tool          工具实例
     * @param input         已生效 input (hook updatedInput 已应用)
     * @param ctx           工具调用上下文
     * @param toolUseId     工具调用 ID (CC toolUseID)
     * @param forceDecision 仅 hook ask 时非 null (弹窗展示 hook 的 ask 消息, CC :415-416);
     *                      可为 null
     * @return gate 3 态决策 (Java gate 内部同步阻塞 prompter, ASK 已转 ALLOW/DENY)
     */
    @FunctionalInterface
    public interface CanUseTool {
        ToolPermissionGate.DecisionResult canUse(
                Tool tool, JsonNode input, ToolUseContext ctx,
                String toolUseId, PermissionResult forceDecision);
    }

    /**
     * 解析结果 · 对齐 CC resolveHookPermissionDecision 返回值
     * {@code { decision: PermissionDecision, input }} (toolHooks.ts:338-342).
     *
     * @param decision 最终权限决策 (Allow/Deny; gate 路径 ASK 已同步转 ALLOW/DENY)
     * @param input    生效 input (hook updatedInput / ask updatedInput / 原 input)
     */
    public record ResolvedPermission(PermissionResult decision, JsonNode input) {
        public ResolvedPermission {
            if (decision == null) {
                throw new IllegalArgumentException("ResolvedPermission.decision is null");
            }
        }
    }

    /**
     * 把 hook 的 {@link PermissionResult} + 上下文解析为最终权限决策.
     *
     * @param hookPermissionResult hook 的权限决策 (AHR.permissionBehavior(), CC
     *                             {@code hookPermissionResult} toolHooks.ts:333); 可为 null
     *                             (hook 未表态 → 正常权限流)
     * @param hookUpdatedInput     hook 是否/如何修改了 input (AHR.updatedInput(), CC
     *                             {@code result.updatedInput}); null = hook 未给 updatedInput
     * @param tool                 工具实例
     * @param input                已生效 input (调用方已应用 hook updatedInput 全替换)
     * @param ctx                  工具调用上下文 (requireCanUseTool / permissionContext)
     * @param toolUseId            工具调用 ID
     * @param canUseTool           canUseTool 回调 (gate 6 参)
     * @return 最终决策 + 生效 input
     */
    public ResolvedPermission resolve(
            PermissionResult hookPermissionResult,
            Map<String, Object> hookUpdatedInput,
            Tool tool,
            JsonNode input,
            ToolUseContext ctx,
            String toolUseId,
            CanUseTool canUseTool) {
        // ── CC toolHooks.ts:344-345 requiresInteraction / requireCanUseTool ──
        boolean requiresInteraction = tool != null && tool.requiresUserInteraction();
        boolean requireCanUseTool = ctx != null && ctx.requireCanUseTool();

        // ── behavior == 'allow' 分支 (CC toolHooks.ts:347-406) ──
        if (hookPermissionResult instanceof PermissionResult.Allow allow) {
            // CC :348 hookInput = hookPermissionResult.updatedInput ?? input
            //   (Java Allow.updatedInput 与 AHR.updatedInput() 同源, 都是 hook 给的新 input)
            JsonNode hookInput = allow.updatedInput() != null ? allow.updatedInput() : input;
            // CC :353-354 interactionSatisfied: hook 给了 updatedInput 即视为替用户完成了交互
            //   (headless wrapper 收集 AskUserQuestion 答案的场景), 对齐 CC
            //   `requiresInteraction && hookPermissionResult.updatedInput !== undefined`.
            //   Java Allow.updatedInput 强制非空, 故"是否给了 updatedInput"判别走 hookUpdatedInput
            //   (AHR 层, 与 CC decision.updatedInput 可空语义同构).
            boolean interactionSatisfied = requiresInteraction && hookUpdatedInput != null;

            // CC :356-370 guard: 交互未满足 或 requireCanUseTool → 仍走 canUseTool
            if ((requiresInteraction && !interactionSatisfied) || requireCanUseTool) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK 已批准工具 {} 但 canUseTool 仍被要求 (requireCanUseTool={} interactionSatisfied={})",
                        tool != null ? tool.name() : "?", requireCanUseTool, interactionSatisfied);
                }
                return new ResolvedPermission(
                    gateDecisionToPermission(canUseTool.canUse(tool, hookInput, ctx, toolUseId, null), hookInput),
                    hookInput);
            }

            // CC :372-405 hook allow 跳过交互弹窗, 但 deny/ask 规则仍适用
            PermissionResult ruleCheck = checkRuleBasedPermissions(tool, hookInput, ctx);
            if (ruleCheck == null) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK 已批准工具 {} 且无规则复检命中 → 直接放行 (跳过权限弹窗)",
                        tool != null ? tool.name() : "?");
                }
                // CC :378-385 返回 hook 自己的 allow 决策 + hookInput
                return new ResolvedPermission(hookPermissionResult, hookInput);
            }
            if (ruleCheck instanceof PermissionResult.Deny) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK 已批准工具 {} 但 deny 规则覆盖: {}",
                        tool != null ? tool.name() : "?", ((PermissionResult.Deny) ruleCheck).message());
                }
                // CC :386-391 deny 规则覆盖 hook allow (bypass-immune)
                return new ResolvedPermission(ruleCheck, hookInput);
            }
            // CC :392-405 ask 规则 — 尽管 hook 已批准, 弹窗仍必须出现 (无 forceDecision)
            if (log.isDebugEnabled()) {
                log.debug("HOOK 已批准工具 {} 但 ask 规则要求弹窗", tool != null ? tool.name() : "?");
            }
            return new ResolvedPermission(
                gateDecisionToPermission(canUseTool.canUse(tool, hookInput, ctx, toolUseId, null), hookInput),
                hookInput);
        }

        // ── behavior == 'deny' 分支 (CC toolHooks.ts:408-411) ──
        if (hookPermissionResult instanceof PermissionResult.Deny) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK 拒绝了工具 {} 使用", tool != null ? tool.name() : "?");
            }
            return new ResolvedPermission(hookPermissionResult, input);
        }

        // ── 无决策 或 'ask' — 正常权限流, 可携带 forceDecision (CC :415-432) ──
        // forceDecision: 仅 hook ask 透传 (弹窗展示 hook 的 ask 消息)
        PermissionResult forceDecision =
            hookPermissionResult instanceof PermissionResult.Ask ? hookPermissionResult : null;
        // CC :417-421 askInput = ask 决策携带的 updatedInput ?? input (hook ask 给的
        //   新 input 必须作为弹窗/执行的基础 input)
        JsonNode askInput = hookPermissionResult instanceof PermissionResult.Ask ask
                && ask.updatedInput() != null
            ? ask.updatedInput()
            : input;
        if (log.isDebugEnabled()) {
            log.debug("HOOK 无决策({}) → 正常权限流, forceDecision={}",
                hookPermissionResult == null ? "null" : hookPermissionResult.getClass().getSimpleName(),
                forceDecision != null ? "hook ask" : "null");
        }
        return new ResolvedPermission(
            gateDecisionToPermission(canUseTool.canUse(tool, askInput, ctx, toolUseId, forceDecision), askInput),
            askInput);
    }

    /**
     * 规则复检 · 对齐 CC {@code checkRuleBasedPermissions}
     * (Open-ClaudeCode/src/utils/permissions/permissions.ts:1071-1130).
     *
     * <p>仅检查规则层 (1a deny / 1b ask / 1c tool.checkPermissions 的 deny 与特定 ask),
     * <b>不</b>跑完整 10 层管线 — CC 的 hook-allow 分支用本函数而非
     * hasPermissionsToUseTool (permissions.ts:1071), 因为完整管线第 3 层兜底 Ask
     * 会让 hook allow 永远弹窗 (违反正则 "hook allow 跳过弹窗" 不变量).
     *
     * <p>[hooks_v3 5-W3-5] 与 CC {@code permissions.ts:1071-1156} 逐层对齐:
     * <ul>
     *   <li>已删 Java 内容 deny 快捷层 (getDenyRuleByContentsForTool 直查) — CC
     *       checkRuleBasedPermissions 无内容 deny, 内容 deny 经 1c tool.checkPermissions
     *       → 1d 表面化 (Bash/Skill 内部用 getRuleByContentsForTool 判内容规则).</li>
     *   <li>1c 补 inputSchema.parse 门禁 — zod 等价 (ToolInputValidator.safeParseSchema),
     *       parse 失败 → logError → 无规则异议 (CC catch → passthrough → null).</li>
     * </ul>
     *
     * <p>Java 端规则查找委托 {@link RuleQuery} (与 10 层规则层同一匹配引擎, 避免规则
     * 匹配逻辑双实现漂移); sandbox auto-allow (CC :1094-1105) 是 Bash+sandbox 专属
     * 通道, [H8 v2 补全 H8-GAP-1] 已接入 {@link SandboxManager} (经 {@link #setSandboxManager}
     * 注入, null=未接线时 sandbox 语义关闭与登记前一致).
     *
     * @param tool  工具实例
     * @param input 已生效 input
     * @param ctx   工具调用上下文 (permissionContext 承载规则集)
     * @return deny/ask 决策; 无规则异议返回 null (hook allow 放行)
     */
    private PermissionResult checkRuleBasedPermissions(Tool tool, JsonNode input, ToolUseContext ctx) {
        ToolPermissionContext permCtx = ctx != null ? ctx.permissionContext() : null;
        if (permCtx == null || tool == null) {
            return null; // 无规则集 → 无规则异议
        }
        // 1a. whole-tool deny rule (CC permissions.ts:1079-1086)
        // [hooks_v3 5-W3-5] CC 无内容 deny 直查 (getDenyRuleForTool 仅 whole-tool,
        //   permissions.ts:287-292); 内容 deny 经 1c tool.checkPermissions → 1d 表面化.
        PermissionRule denyRule = RuleQuery.getDenyRuleForTool(permCtx, tool);
        if (denyRule != null) {
            // CC :1084-1087 message = `Permission to use ${tool.name} has been denied.`
            return new PermissionResult.Deny(
                "Permission to use " + tool.name() + " has been denied.",
                new PermissionDecisionReason.Rule(denyRule),
                null);
        }
        // 1b. whole-tool ask rule (CC permissions.ts:1092-1109)
        PermissionRule askRule = RuleQuery.getAskRuleForTool(permCtx, tool);
        if (askRule != null) {
            // [H8 v2 补全 H8-GAP-1] CC permissions.ts:1186-1205: 命中 ask rule 但
            //   canSandboxAutoAllow (Bash 工具 + sandbox 激活 + auto-allow 开启 + 命令可沙箱化)
            //   → fall-through 到 1c tool.checkPermissions (不弹窗, Bash 沙箱内命令自动放行).
            //   与 R26 hook 层同模式; sandboxManager==null (未接线) 时 sandbox 语义关闭
            //   = 登记前行为 (ask rule 一律 Ask).
            boolean canSandboxAutoAllow = sandboxManager != null
                && BASH_TOOL_NAME.equals(tool.name())
                && sandboxManager.isEnabled()
                && sandboxManager.isAutoAllowBashIfSandboxed()
                && sandboxManager.shouldUseSandbox(tool.name(), input);
            if (!canSandboxAutoAllow) {
                // [OPD-WF3-01-06] hook 1b ask 消息逐字对齐 CC checkRuleBasedPermissions
                //   （permissions.ts:1102-1107）：message = createPermissionRequestMessage(
                //   tool.name)（不传 decisionReason）→ CC 通用默认句；decisionReason 仍为
                //   Rule(askRule)（归因）。旧硬编码 "requires your approval." 偏离 CC 文案。
                return new PermissionResult.Ask(
                    "Claude requested permissions to use " + tool.name()
                        + ", but you haven't granted it yet.",
                    new PermissionDecisionReason.Rule(askRule),
                    List.of(), null, null, null, false,null, List.of());
            }
            if (log.isDebugEnabled()) {
                log.debug("HOOK ask 规则命中但 Bash sandbox auto-allow → fall-through 到 tool.checkPermissions (tool={})",
                    tool.name());
            }
            // fall-through 到 1c: 让 BashTool.checkPermissions 决定 (sandbox 命令自动放行)
        }
        // 1c. tool.checkPermissions + inputSchema.parse 门禁 (CC permissions.ts:1118-1126)
        // [hooks_v3 5-W3-5] CC: const parsedInput = tool.inputSchema.parse(input) →
        //   zod 解析失败抛错 → catch → logError → toolPermissionResult 保持默认
        //   passthrough → 1d/1f/1g 均不命中 → 返回 null (无规则异议, hook allow 放行).
        //   Java 等价: ToolInputValidator.safeParseSchema (zod safeParse, toolOrchestration.ts:97-107);
        //   parse 失败 → log.error (CC logError 等价) → 无规则异议. 成功则用 typed value
        //   (现阶段 safeParse value == 原 input, 与 CC parsedInput 同构).
        var parsedInput = inputValidator.safeParseSchema(tool, input);
        if (!parsedInput.ok()) {
            log.error("HookPermissionResolver.checkRuleBasedPermissions: tool={} inputSchema.parse 失败 (对齐 CC permissions.ts:1121-1126 logError, 按无规则异议放行) issues={}",
                tool.name(), parsedInput.issues());
            return null;
        }
        PermissionResult toolDecision = tool.checkPermissions(parsedInput.value(), ctx);
        if (toolDecision instanceof PermissionResult.Deny) {
            // 1d. 工具实现方拒绝 (CC :1115-1118, 含 bash 子命令 deny)
            return toolDecision;
        }
        if (toolDecision instanceof PermissionResult.Ask ask && isBypassImmuneAsk(ask)) {
            // 1f. content-specific ask rule (ruleBehavior=ask) / 1g. safetyCheck
            //     (CC :1119-1128) — 仅这两类 ask 不能被 hook allow 豁免
            return ask;
        }
        // 工具默认 checkPermissions 返回 Allow (Tool.java:237-245) → 视为无异议
        return null;
    }

    /**
     * 判定 ask 是否 bypass-immune (规则 ask / safetyCheck) · 对齐 CC permissions.ts:1119-1128.
     *
     * <p>WHY: 普通 ask (decisionReason 非 rule/safetyCheck) 在 CC 规则复检中返回 null
     * (hook allow 放行); 只有 rule 行为 ask 与安全底线检查必须弹窗 (bypass-immune).
     */
    private boolean isBypassImmuneAsk(PermissionResult.Ask ask) {
        if (ask.reason() instanceof PermissionDecisionReason.Rule rule) {
            return rule.rule().ruleBehavior() == PermissionBehavior.ASK;
        }
        return ask.reason() instanceof PermissionDecisionReason.SafetyCheck;
    }

    /**
     * gate 决策 → 最终 PermissionResult · 对齐 CC canUseTool 返回值 (useCanUseTool.tsx:38-62).
     *
     * <p>Java gate ALLOW 的 result() 通常为 null (DecisionResult.allow()), 需合成
     * Allow 携带生效 input (CC buildAllow(result.updatedInput ?? input),
     * useCanUseTool.tsx:50-53); DENY 的 result() 直接透传 (含 message/reason).
     */
    private PermissionResult gateDecisionToPermission(
            ToolPermissionGate.DecisionResult gateResult, JsonNode input) {
        if (gateResult == null) {
            throw new IllegalArgumentException("canUseTool returned null DecisionResult");
        }
        if (gateResult.decision() == ToolPermissionGate.Decision.DENY) {
            return gateResult.result() instanceof PermissionResult.Deny deny
                ? deny
                : new PermissionResult.Deny(
                    "Permission denied for tool use.",
                    new PermissionDecisionReason.Other("permission gate denied"),
                    null);
        }
        // ALLOW
        return gateResult.result() instanceof PermissionResult.Allow allow
            ? allow
            : new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("allowed by permission gate"),
                null, false, null, List.of());
    }
}
