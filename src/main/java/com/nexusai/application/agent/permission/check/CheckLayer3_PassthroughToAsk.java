package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.explainer.PermissionMessageGenerator;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 3 规则检查：兜底 — passthrough 转 ask（Pipeline 最后一层）
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1299-1310} - 第 3 层（兜底层）
 * <p>对应伪代码：<pre>
 *   // 前 9 层（1a-2b）都未命中 → 兜底转 ask
 *   return Ask(reason=toolPermissionResult.decisionReason, suggestions=[...]);
 * </pre>
 * <p>decisionReason 取 passthrough 实际 reason（工具回带，可为 null）；null 时
 * {@link PermissionMessageGenerator} 落 CC 通用默认句。
 *
 * <h2>检查逻辑</h2>
 * <p>本层永远命中（不返回 null），把任何未在前 9 层决定的工具调用转 Ask。
 * 是 10 层 Pipeline 的兜底。
 *
 * <h2>bypass-immune 状态</h2>
 * <p>不是 bypass-immune —— 但实际效果上 bypass 模式已在 2a 命中，本层不会执行。
 * 仅在 mode=DEFAULT / ACCEPT_EDITS / DONT_ASK / PLAN 等非 bypass 模式下走到。
 *
 * <h2>为什么是兜底</h2>
 * <p>CC 教学版注释："default to ask unless we know it's safe"。
 * 工具调用未在任何规则命中 → 不知道是否安全 → 让用户决定。
 * 比"默认 allow"安全得多。
 *
 * <h2>suggestions</h2>
 * <p>本层生成的 suggestions 含两个 AddRules：
 * <ul>
 *   <li>"Allow this tool once" → 加 CLI_ARG session-only allow rule</li>
 *   <li>"Allow this tool forever" → 加 USER_SETTINGS persistent allow rule</li>
 * </ul>
 *
 * <p>Phase 1 简化：仅提供 whole-tool allow 建议，不深入 ruleContent。
 */
public class CheckLayer3_PassthroughToAsk implements CheckLayer {

    /** SLF4J logger · 记录兜底 ask 消息生成数据流。 */
    private static final Logger log = LoggerFactory.getLogger(CheckLayer3_PassthroughToAsk.class);

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
     * 执行第 3 层兜底：passthrough → Ask；非 passthrough（Allow/Ask）原样透传。
     *
     * <p>本层是 Pipeline 最后一层，永远返回非 null 结果。
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入
     * @param ctx      工具调用上下文（3 不需要）
     * @param permCtx  权限上下文（3 不需要）
     * @return         非 passthrough → 原样返回；passthrough/cache miss → AskDecision（兜底）
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // 1. 自建 suggestions 兜底（⊕-4/DC-03 保留，OPD-WF3-01-04 拍板）：
        //    (a) Allow this session → CLI_ARG destination（仅本会话内存生效）
        //    (b) Allow forever → USER_SETTINGS destination（持久化到 user settings.json）
        List<com.nexusai.application.agent.permission.PermissionUpdate> selfBuilt = List.of(
            new com.nexusai.application.agent.permission.PermissionUpdate.AddRules(
                com.nexusai.application.agent.permission.PermissionUpdate.Destination.CLI_ARG,
                List.of(
                    new com.nexusai.application.agent.permission.PermissionRule(
                        com.nexusai.application.agent.permission.PermissionRuleSource.CLI_ARG,
                        com.nexusai.application.agent.permission.PermissionBehavior.ALLOW,
                        com.nexusai.application.agent.permission.PermissionRuleValue.wholeTool(tool.name())
                    )
                ),
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW
            ),
            new com.nexusai.application.agent.permission.PermissionUpdate.AddRules(
                com.nexusai.application.agent.permission.PermissionUpdate.Destination.USER_SETTINGS,
                List.of(
                    new com.nexusai.application.agent.permission.PermissionRule(
                        com.nexusai.application.agent.permission.PermissionRuleSource.USER_SETTINGS,
                        com.nexusai.application.agent.permission.PermissionBehavior.ALLOW,
                        com.nexusai.application.agent.permission.PermissionRuleValue.wholeTool(tool.name())
                    )
                ),
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW
            )
        );
        // 2. 消费 1c 结果（ToolCheckCache，CC in-scope toolPermissionResult 等价）
        //    [WF3-02 DC-04 / OPD-WF3-02-3] 对齐 CC permissions.ts:1299-1310：
        //      const result = toolPermissionResult.behavior === 'passthrough'
        //        ? {...toolPermissionResult, behavior:'ask',
        //           message: createPermissionRequestMessage(tool.name, decisionReason)}
        //        : toolPermissionResult  —— 非 passthrough（Allow/Ask）原样透传
        PermissionResult cached = ToolCheckCache.get(tool.name());
        if (cached != null && !(cached instanceof PermissionResult.Passthrough)) {
            // CC 第 3 层：非 passthrough → 原样返回（工具 Allow / 非 bypass-immune Ask，
            //   decisionReason 由工具自决，不被本层覆盖）
            if (log.isDebugEnabled()) {
                log.debug("3 层：1c 结果非 passthrough，原样透传（对齐 CC permissions.ts:1310）tool={} decision={}",
                    tool.name(), cached.getClass().getSimpleName());
            }
            return cached;
        }
        // 3. passthrough / cache miss → 转 Ask（兜底）
        // [RF-6 ①] CC {...toolPermissionResult} spread 保留工具自带的 suggestions
        //   （MCP checkPermissions addRules allow→localSettings）；空 suggestions 时
        //   沿用自建 CLI_ARG + USER_SETTINGS 兜底（⊕-4/DC-03 保留）。
        // [F4a-1] message=createPermissionRequestMessage(tool.name, decisionReason)；
        //   无 reason → null → CC 通用默认句（permissions.ts:207-209）。
        // [H14-FIX] passthrough 携带 pendingClassifierCheck (BashTool buildPendingClassifierCheck)
        //   时, 必须把结构体透传到 Ask — 否则 ToolPermissionGate:585 竞速条件 /
        //   coordinatorPendingCheck(:809) 在生产恒为死路径 (H14 对抗核验缺口).
        List<com.nexusai.application.agent.permission.PermissionUpdate> suggestions = selfBuilt;
        PermissionResult.PendingClassifierCheck pendingClassifierCheck = null;
        PermissionDecisionReason decisionReason = null;
        if (cached instanceof PermissionResult.Passthrough passthrough) {
            decisionReason = passthrough.reason();
            pendingClassifierCheck = passthrough.pendingClassifierCheck();
            if (passthrough.suggestions() != null && !passthrough.suggestions().isEmpty()) {
                suggestions = passthrough.suggestions();
            }
        }
        String message = messageGenerator.createPermissionRequestMessage(tool.name(), decisionReason);
        if (log.isDebugEnabled()) {
            log.debug("3 兜底 ask 消息生成（对齐 CC permissions.ts:1299-1310 decisionReason 派生）tool={} reason={} message={}",
                tool.name(), decisionReason != null ? decisionReason.getClass().getSimpleName() : "default", message);
        }
        return new PermissionResult.Ask(
            message,
            decisionReason,
            suggestions,
            null,
            null, null, false, pendingClassifierCheck, null);
    }
}
