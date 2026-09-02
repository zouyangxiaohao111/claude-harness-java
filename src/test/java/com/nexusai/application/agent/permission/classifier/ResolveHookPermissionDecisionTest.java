package com.nexusai.application.agent.permission.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [D P1-2 + hooks_v3 H-PERM-02 · 1-7] LlmAgentLoop.resolveHookPermissionDecision
 * 7 参实例入口 · 对齐 CC {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:921-929}
 * 调用点 + {@code toolHooks.ts:332-433} 定义。
 *
 * <p><b>WHY (意图验证)</b>: CC 在 toolExecution.ts:921-929 以 7 参调用
 * {@code resolveHookPermissionDecision(hookPermissionResult, tool, input,
 * toolUseContext, canUseTool, assistantMessage, toolUseID)} —— hook 决议/工具/上下文/
 * canUseTool 函数一次性传入, 由解析器产出最终权限决策, 而不是在调用方散落 if-else。
 * Java 端镜像入口 = {@link LlmAgentLoop#resolveHookPermissionDecision} (实例方法),
 * 实现委托 {@link HookPermissionResolver} (CC toolHooks.ts:332-433 定义镜像, 单一实现
 * 无双轨); [hooks_v3 1-7] 原静态单例 {@code HookPermissionResolver.SHARED} 已删除,
 * 实例经 {@link LlmAgentLoop#setPermissionResolver} 注入.
 *
 * <p>本测试锁定两个核心语义 (REQ-D-02):
 * <ul>
 *   <li><b>denyOverBypass</b>: hook allow <b>不能</b>绕过 settings deny 规则
 *       (CC toolHooks.ts:386-391 "deny rule overrides") —— hook 已批准 + deny 规则命中
 *       → 最终决策 = rule deny, canUseTool 不被调用。若业务变更让 hook allow 直接放行,
 *       用户的 deny 规则将被静默绕过 (安全漏洞)。</li>
 *   <li><b>bubbleToParent</b>: 无 hook 决议 (null) → 决策"冒泡"到 canUseTool
 *       (CC toolHooks.ts:413-432 正常权限流, canUseTool 透传) —— 解析器不吞决策,
 *       权限弹窗/门禁照常运行。若解析器对 null hook 直接放行, 权限系统形同虚设。</li>
 * </ul>
 *
 * <p>测试位于 classifier 包系 task-register.csv D 行 write_scope 指定路径
 * (documenter#4 登记); 语义上属 hook 权限决议, 位置异常已在 progress/D.md 注明。
 *
 * @see LlmAgentLoop#resolveHookPermissionDecision
 * @see HookPermissionResolver
 */
@DisplayName("[D P1-2] LlmAgentLoop.resolveHookPermissionDecision 7 参实例入口")
class ResolveHookPermissionDecisionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_NAME = "TestTool";

    /** 实例入口 · 注入 {@link HookPermissionResolver} bean (hooks_v3 1-7 SHARED 删除后). */
    private static LlmAgentLoop entry() {
        LlmAgentLoop loop = new LlmAgentLoop(new LlmProviderFactory());
        loop.setPermissionResolver(new HookPermissionResolver());
        return loop;
    }

    /** stub 工具 · 非交互 (requiresUserInteraction=false). */
    private static Tool stubTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub tool for D resolveHookPermissionDecision test"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
    }

    /** 无规则 permCtx · 最严格模式. */
    private static ToolPermissionContext permCtxNoRules() {
        return ToolPermissionContext.strict(PermissionMode.DEFAULT);
    }

    /** settings deny 规则: 整个 TestTool 被 deny (bypass-immune 底线). */
    private static ToolPermissionContext permCtxWithDenyRule() {
        PermissionRule deny = new PermissionRule(
            PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY,
            new PermissionRuleValue(TOOL_NAME, null));
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.of(), Map.of(PermissionRuleSource.USER_SETTINGS, Set.of(deny)), Map.of(), Map.of());
    }

    /** 9 参便利 ctx (permissionContext 带规则; requireCanUseTool=false 默认). */
    private static ToolUseContext ctxWith(ToolPermissionContext permCtx) {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            permCtx, PermissionMode.DEFAULT);
    }

    /** hook allow · updatedInput 非空是 Java Allow record 契约 (CC updatedInput ?? input). */
    private static PermissionResult hookAllow(JsonNode updatedInput) {
        return new PermissionResult.Allow(
            updatedInput,
            new PermissionDecisionReason.Hook("PreToolUse:" + TOOL_NAME, "test-hook", "hook approved"),
            null, false, null, List.of());
    }

    /** recording canUseTool stub · 记录调用次数 + forceDecision 参数. */
    private static final class RecordingCanUseTool implements HookPermissionResolver.CanUseTool {
        final AtomicInteger calls = new AtomicInteger();
        final List<PermissionResult> forceDecisions = new java.util.ArrayList<>();

        @Override
        public ToolPermissionGate.DecisionResult canUse(
                Tool tool, JsonNode input, ToolUseContext ctx,
                String toolUseId, PermissionResult forceDecision) {
            calls.incrementAndGet();
            forceDecisions.add(forceDecision);
            return ToolPermissionGate.DecisionResult.allow();
        }
    }

    /**
     * hook allow + settings deny 规则 → 最终决策 = rule deny, canUseTool 不被调用.
     *
     * <p>WHY: CC toolHooks.ts:386-391 — hook allow 不豁免 settings deny 规则,
     * deny 是 bypass-immune 底线 (用户显式配置的禁用不能被 hook 静默绕过).
     * 变异点: 把 "deny rule overrides" 分支删掉 → 红.
     */
    @Test
    @DisplayName("resolveHookPermissionDecision 7 参入口 · hook allow + deny 规则 → deny 优先于 bypass (CC toolHooks.ts:386-391)")
    void resolveHookPermissionDecision_denyOverBypass() {
        JsonNode input = JSON.createObjectNode().put("cmd", "dangerous");
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver.ResolvedPermission resolved = entry().resolveHookPermissionDecision(
            hookAllow(input), null, stubTool(TOOL_NAME), input,
            ctxWith(permCtxWithDenyRule()), "toolu_1", canUseTool);

        assertThat(resolved.decision())
            .as("hook allow 不能绕过 settings deny 规则 → 最终决策必须为 deny")
            .isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) resolved.decision()).reason())
            .as("deny 决策必须归因于 rule (而非 hook)")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
        assertThat(canUseTool.calls.get())
            .as("deny 规则命中 → 直接返回 deny, canUseTool 不得被调用")
            .isZero();
    }

    /**
     * 无 hook 决议 (null) → canUseTool 透传 (决策冒泡到门禁/弹窗).
     *
     * <p>WHY: CC toolHooks.ts:413-432 — 无 hook 决策 = 正常权限流, canUseTool 决定
     * 最终结果; 解析器不得吞掉 null 直接放行. 变异点: 把 null 分支改成直接 allow → 红.
     */
    @Test
    @DisplayName("resolveHookPermissionDecision 7 参入口 · null hook → canUseTool 冒泡 (CC toolHooks.ts:413-432)")
    void resolveHookPermissionDecision_bubbleToParent() {
        JsonNode input = JSON.createObjectNode().put("cmd", "echo hi");
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver.ResolvedPermission resolved = entry().resolveHookPermissionDecision(
            null, null, stubTool(TOOL_NAME), input,
            ctxWith(permCtxNoRules()), "toolu_2", canUseTool);

        assertThat(canUseTool.calls.get())
            .as("null hook → canUseTool 必须被调 (正常权限流冒泡到门禁)")
            .isEqualTo(1);
        assertThat(canUseTool.forceDecisions.get(0))
            .as("无 hook ask → forceDecision 必须为 null")
            .isNull();
        assertThat(resolved.decision())
            .as("canUseTool ALLOW → 最终决策为 Allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(resolved.input())
            .as("生效 input = 原 input (无 hook 修改)")
            .isSameAs(input);
    }
}
