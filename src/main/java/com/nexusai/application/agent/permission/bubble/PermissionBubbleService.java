package com.nexusai.application.agent.permission.bubble;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AgentToolConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 权限冒泡服务 · 子 agent Ask 决策冒泡到父终端
 *
 * <p>子 agent 遇到 {@link PermissionResult.Ask} 决策时，由
 * {@code ToolPermissionGate.mapToDecision}（仅当 {@code ctx.permissionMode() == BUBBLE}）
 * 调用 {@link #handleBubble}，把"弹窗给谁看"路由到父终端交互层。
 *
 * <h2>CC 真源（已 grep 实证）</h2>
 * <ol>
 *   <li><b>子 agent 以 bubble 模式运行</b>：CC {@code forkSubagent.ts:50-67}
 *       FORK_AGENT 定义 {@code permissionMode: 'bubble'}（L67）—— fork 子 agent
 *       的权限提示直接冒泡到父终端。</li>
 *   <li><b>bubble 模式必弹窗（不静默）</b>：CC {@code runAgent.ts:440-446}
 *       {@code shouldAvoidPrompts} 推导（L440-446）：bubble 模式 → false；
 *       {@code isAsync && !shouldAvoidPrompts} → {@code awaitAutomatedChecksBeforeDialog: true}
 *       （L458-463，自动化检查竞速后才弹窗）。</li>
 *   <li><b>Ask 决策走 interactive（父终端弹窗兜底）</b>：CC {@code useCanUseTool.tsx:37}
 *       + {@code interactiveHandler.ts:57-60} handleInteractivePermission；Java 端
 *       gate mapToDecision ASK 分支 → {@code interactiveHandler.awaitUserDecision}。
 *       bubble 返回原 Ask 不短路、继续 interactive（CC 语义：bubble 只决定
 *       "弹窗给谁看"，不修改 ALLOW/DENY 结论）。</li>
 *   <li><b>Agent(agentType) deny 过滤</b>：{@link #filterDeniedAgents} 与
 *       {@link #getDenyRuleForAgent} 对齐 CC {@code utils/permissions/permissions.ts:307-343}，
 *       被 {@code AgentTool.tsx:219/342-355} 消费（Java 侧消费方 SubagentTool）。</li>
 * </ol>
 *
 * <h2>核心流程（§10.7）</h2>
 * <ol>
 *   <li>gate 仅在 {@code permissionMode() == BUBBLE} 时调用 {@link #handleBubble}</li>
 *   <li>冒泡到父 agent：Java 为同步调用栈语义 —— 返回原 Ask，由 gate 上层
 *       interactive 弹窗机制处理（无 CC 的双向消息通道/回传机制）</li>
 * </ol>
 *
 * <p>历史 Java 扩展已删除：递归深度守卫（{@code MAX_BUBBLE_DEPTH}）与工具黑名单
 * （{@code deniedTools}）CC 无对应概念；CC 防递归 fork 靠 {@code forkSubagent.ts:78-89}
 * {@code isInForkChild}（对话历史特征检测），非深度计数。
 *
 * @see BubblePermissionMode
 * @see SubagentPermissionContext
 */
@Component
public class PermissionBubbleService {

    private static final Logger log = LoggerFactory.getLogger(PermissionBubbleService.class);

    /** CC AgentTool/constants.ts:1 {@code AGENT_TOOL_NAME = 'Agent'}（{@link AgentToolConstants#AGENT_TOOL_NAME}）。 */
    private static final String AGENT_TOOL_NAME = AgentToolConstants.AGENT_TOOL_NAME;

    /**
     * 处理子 agent 的权限冒泡请求（§10.7 权限冒泡实现）。
     *
     * <p>对齐 CC {@code runAgent.ts:440-446}：bubble 模式必弹窗（不 avoid prompts），
     * Ask 决策不短路、不篡改 —— 返回原 Ask，由上层 interactive 弹窗机制处理。
     *
     * @param childCtx  子 agent 权限上下文
     * @param toolName  工具名
     * @param input     工具输入（用于审计日志，当前阶段不修改）
     * @param askResult Ask 决策
     * @return 原 Ask 决策（冒泡到父终端 interactive）
     * @throws IllegalArgumentException 如果必填参数为 {@code null}
     */
    public PermissionResult handleBubble(
            SubagentPermissionContext childCtx,
            String toolName,
            JsonNode input,
            PermissionResult.Ask askResult
    ) {
        if (childCtx == null) {
            throw new IllegalArgumentException("childCtx is null");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is blank");
        }
        if (askResult == null) {
            throw new IllegalArgumentException("askResult is null");
        }

        // 冒泡到父 agent：返回原 Ask，由上层 LlmAgentLoop 的弹窗机制处理
        if (log.isDebugEnabled()) {
            log.debug("Bubble: agent {} → parent {}，工具={}，返回原 Ask 走 interactive",
                    childCtx.agentId(), childCtx.parentAgentId(), toolName);
        }
        return askResult;
    }

    /**
     * 获取 agent 级别的 deny 规则 · 对齐 CC {@code utils/permissions/permissions.ts:307-320}
     * {@code getDenyRuleForAgent(context, agentToolName, agentType)}
     * （Agent(agentType) 语法 deny，被 {@code AgentTool.tsx:350} 消费）。
     *
     * <p>匹配键对齐 CC {@code permissions.ts:316-318}：
     * {@code rule.ruleValue.toolName === agentToolName && rule.ruleValue.ruleContent === agentType}。
     * Java 端 {@code agentToolName} 恒为 {@link AgentToolConstants#AGENT_TOOL_NAME}（"Agent"），
     * 因唯一生产消费方 {@code SubagentTool} 只传 {@code AGENT_TOOL_NAME}（CC AgentTool.tsx:350 同）。
     *
     * <p>遍历全 8 source（对齐 CC {@code getDenyRules} 用 {@code PERMISSION_RULE_SOURCES.flatMap}，
     * {@code permissions.ts:213-221}），按 source 声明顺序返回第一个命中（CC {@code .find} 语义，
     * 命中 source 影响报错文案的 {@code denyRule.source}）。
     *
     * @param agentType agent 类型名（如 "Explore"）CC original: agentType
     * @param ctx       权限上下文 CC original: context
     * @return 匹配的 deny 规则；无匹配返回 {@code null}
     */
    public PermissionRule getDenyRuleForAgent(String agentType, ToolPermissionContext ctx) {
        if (agentType == null || ctx == null) {
            return null;
        }

        for (PermissionRule rule : getDenyRules(ctx)) {
            if (AGENT_TOOL_NAME.equals(rule.ruleValue().toolName())
                    && agentType.equals(rule.ruleValue().ruleContent())) {
                if (log.isDebugEnabled()) {
                    log.debug("getDenyRuleForAgent 命中: agentType={}, source={}, rule={}",
                            agentType, rule.source(), rule.ruleValue().toRuleString());
                }
                return rule;
            }
        }
        return null;
    }

    /**
     * 过滤被禁用的 agent · 对齐 CC {@code utils/permissions/permissions.ts:324-342}
     * {@code filterDeniedAgents(agents, context, agentToolName)}
     * （Agent(agentType) 语法 deny 过滤，被 {@code AgentTool.tsx:219/342-355} 消费）。
     *
     * <p>对齐 CC 预计算语义（{@code permissions.ts:328-341}）：先遍历全 8 source 的 deny 规则，
     * 收集 {@code ruleContent} 到 {@code deniedAgentTypes} Set（{@code rule.ruleValue.toolName ===
     * agentToolName && rule.ruleValue.ruleContent !== undefined}），再一次性过滤 —— 避免 CC 注释所述
     * "Previously called per agent, which re-parsed every deny rule for every agent (O(agents×rules))"。
     *
     * @param agentTypes 可用 agent 类型列表 CC original: agents（T extends { agentType }）
     * @param ctx        权限上下文 CC original: context
     * @return 未被禁用的 agent 类型列表（保持原顺序）
     */
    public List<String> filterDeniedAgents(List<String> agentTypes, ToolPermissionContext ctx) {
        if (agentTypes == null || agentTypes.isEmpty() || ctx == null) {
            return List.of();
        }

        // 预计算被禁用的 agentType Set（CC permissions.ts:328-341）
        Set<String> deniedAgentTypes = new java.util.HashSet<>();
        for (PermissionRule rule : getDenyRules(ctx)) {
            if (AGENT_TOOL_NAME.equals(rule.ruleValue().toolName())
                    && rule.ruleValue().ruleContent() != null) {
                deniedAgentTypes.add(rule.ruleValue().ruleContent());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("filterDeniedAgents: 输入 {} 个 agent, 预计算 deniedAgentTypes={}",
                    agentTypes.size(), deniedAgentTypes);
        }

        return agentTypes.stream()
                .filter(type -> !deniedAgentTypes.contains(type))
                .toList();
    }

    /**
     * 全 8 source 的 deny 规则（按 source 声明顺序展开）· 对齐 CC
     * {@code utils/permissions/permissions.ts:213-221} {@code getDenyRules} 的
     * {@code PERMISSION_RULE_SOURCES.flatMap} 语义。
     *
     * <p>不复用 {@code RuleQuery.getDenyRules}（其基于 HashSet，丢失 source 顺序），
     * 因为 {@link #getDenyRuleForAgent} 需要 CC {@code .find} 的"首个命中"顺序语义
     * （命中 source 顺序决定报错文案 {@code denyRule.source}）。
     */
    private static List<PermissionRule> getDenyRules(ToolPermissionContext ctx) {
        List<PermissionRule> all = new ArrayList<>();
        for (PermissionRuleSource source : PermissionRuleSource.values()) {
            all.addAll(ctx.alwaysDenyRules().getOrDefault(source, Set.of()));
        }
        return all;
    }
}
