package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H8] toolHooks 权限决策不变量 · 对齐 CC {@code resolveHookPermissionDecision}
 * (Open-ClaudeCode/src/services/tools/toolHooks.ts:332-433) + {@code checkRuleBasedPermissions}
 * (utils/permissions/permissions.ts:1071-1130) + {@code getPreToolHookBlockingMessage}
 * (utils/hooks.ts:1882-1887).
 *
 * <p><b>核心不变量 (WHY 本测试存在)</b>: H6 之前 Java 端 hook allow 会落入
 * {@link ToolPermissionGate} 全 10 层管线 → 第 3 层兜底 Ask → <b>hook allow 仍然弹窗</b>,
 * 违反 CC "hook allow 跳过权限弹窗" 语义 (toolHooks.ts:372-405: hook allow 仅被 settings
 * deny/ask 规则与 requireCanUseTool/requiresUserInteraction 守卫复检). 本测试锁定
 * 以下不变量, 任何一条被业务逻辑变更破坏都必须红:
 * <ol>
 *   <li>hook allow + 无规则命中 → 直接返回 hook allow, <b>不</b>走 canUseTool</li>
 *   <li>hook allow 不能绕过 settings deny 规则 (bypass-immune 底线)</li>
 *   <li>hook allow + settings ask 规则 → 仍走 canUseTool (弹窗), forceDecision=null</li>
 *   <li>hook deny → 直接返回 deny, 不走 canUseTool</li>
 *   <li>hook ask → 走 canUseTool, forceDecision=hook ask (弹窗展示 hook 的 ask 消息)</li>
 *   <li>requireCanUseTool=true + hook allow → 仍走 canUseTool (守卫优先于 hook 放行)</li>
 *   <li>requiresUserInteraction + hook 未给 updatedInput → 仍走 canUseTool (交互未满足)</li>
 *   <li>getPreToolHookBlockingMessage 格式化文本与 CC 逐字符一致</li>
 *   <li>getToolUseSummary 透传到 hook 上下文 (CC executePreToolHooks 9 参末 1 参)</li>
 *   <li>hook message → AttachmentMessageDto → AgentState.attachments() (LLM 可见, 不再只写不读)</li>
 * </ol>
 *
 * @since Session H8
 */
@DisplayName("[H8] toolHooks 权限决策不变量对齐 CC resolveHookPermissionDecision")
class ToolHooksPermissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_NAME = "TestTool";

    // ════════════════════════════════════════════════════════════════════════
    // 测试基建
    // ════════════════════════════════════════════════════════════════════════

    /** stub 工具 · 可覆写 requiresUserInteraction / checkPermissions. */
    private Tool stubTool(String name, boolean requiresInteraction) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub tool for H8 resolver test"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
            @Override public boolean requiresUserInteraction() { return requiresInteraction; }
        };
    }

    private Tool stubTool(String name) {
        return stubTool(name, false);
    }

    /**
     * Bash 工具 stub · name="Bash" + 默认 checkPermissions=Allow (模拟 BashTool sandbox allow).
     *
     * <p>WHY: H8-GAP-1 sandbox auto-allow 测试需要 Bash 工具 — CC canSandboxAutoAllow
     * (permissions.ts:1186-1205) 仅对 BASH_TOOL_NAME='Bash' 生效, 且 fall-through 后由
     * tool.checkPermissions 决定 (默认 Allow = 沙箱允许).
     */
    private Tool stubBashTool() {
        return new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub Bash for H8-GAP-1 sandbox test"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
    }

    /** 无规则 permCtx · 最严格模式. */
    private ToolPermissionContext permCtxNoRules() {
        return ToolPermissionContext.strict(PermissionMode.DEFAULT);
    }

    /** settings deny 规则: 整个 TestTool 被 deny. */
    private ToolPermissionContext permCtxWithDenyRule() {
        PermissionRule deny = new PermissionRule(
            PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY,
            new PermissionRuleValue(TOOL_NAME, null));
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.of(), Map.of(PermissionRuleSource.USER_SETTINGS, Set.of(deny)), Map.of(), Map.of());
    }

    /** settings ask 规则: 整个 TestTool 需询问. */
    private ToolPermissionContext permCtxWithAskRule() {
        return permCtxWithAskRuleFor(TOOL_NAME);
    }

    /** settings ask 规则: 指定工具需询问. */
    private ToolPermissionContext permCtxWithAskRuleFor(String toolName) {
        PermissionRule ask = new PermissionRule(
            PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ASK,
            new PermissionRuleValue(toolName, null));
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.of(), Map.of(), Map.of(PermissionRuleSource.USER_SETTINGS, Set.of(ask)), Map.of());
    }

    /** 9 参便利 ctx (permissionContext 带规则; requireCanUseTool=false 默认). */
    private ToolUseContext ctxWith(ToolPermissionContext permCtx) {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            permCtx, PermissionMode.DEFAULT);
    }

    /**
     * canonical 46 参 ctx · 仅 requireCanUseTool 可定制.
     * WHY: 9 参便利工厂到不了 Stage 3.4 的 requireCanUseTool 字段 (第 39 位),
     * 测试 requireCanUseTool 守卫必须走 canonical ctor (对齐
     * R32B15Stage3_4_SessionDimensionTest 既有构造模式).
     */
    private ToolUseContext ctxWithRequireCanUseTool(boolean requireCanUseTool) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of(),
            permCtxNoRules(), PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            false, null, null, null, null, null, requireCanUseTool, false, null, null, null, null, null, null, null);
    }

    /** hook allow · updatedInput 非空是 Java Allow record 契约 (CC updatedInput ?? input). */
    private PermissionResult hookAllow(JsonNode updatedInput) {
        return new PermissionResult.Allow(
            updatedInput,
            new PermissionDecisionReason.Hook("PreToolUse:" + TOOL_NAME, "test-hook", "hook approved"),
            null, false, null, List.of());
    }

    private PermissionResult hookDeny(String message) {
        return new PermissionResult.Deny(
            message,
            new PermissionDecisionReason.Hook("PreToolUse:" + TOOL_NAME, "test-hook", "hook denied"),
            null);
    }

    /** hook ask · updatedInput 可为 null (CC ask 分支 askInput = updatedInput ?? input). */
    private PermissionResult hookAsk(String message, JsonNode updatedInput) {
        return new PermissionResult.Ask(
            message,
            new PermissionDecisionReason.Hook("PreToolUse:" + TOOL_NAME, "test-hook", message),
            List.of(), null, updatedInput, null, false,null, List.of());
    }

    /** recording canUseTool stub · 记录调用次数 + forceDecision 参数. */
    private static class RecordingCanUseTool implements HookPermissionResolver.CanUseTool {
        final AtomicInteger calls = new AtomicInteger();
        final List<PermissionResult> forceDecisions = new ArrayList<>();
        final List<JsonNode> inputs = new ArrayList<>();

        @Override
        public ToolPermissionGate.DecisionResult canUse(
                Tool tool, JsonNode input, ToolUseContext ctx,
                String toolUseId, PermissionResult forceDecision) {
            calls.incrementAndGet();
            forceDecisions.add(forceDecision);
            inputs.add(input);
            return ToolPermissionGate.DecisionResult.allow();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 正向: hook allow + 无规则命中 → 返回 hook allow
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook allow + 无 settings deny/ask 规则 + 非交互工具 + requireCanUseTool=false
     * → 直接返回 hook allow, <b>不</b>走 canUseTool.
     *
     * <p>WHY: 这是 CC 语义 (toolHooks.ts:378-385) 与 H6 前 Java 行为的本质差异 —
     * H6 前 hook allow 会落入 gate 全 10 层管线 → 第 3 层兜底 Ask → 仍然弹窗,
     * hook allow 形同虚设. 本测试锁定 "hook allow 跳过权限弹窗" 不变量.
     */
    @Test
    @DisplayName("hook allow + 无规则 → 返回 hook allow, 不走 canUseTool (CC toolHooks.ts:378-385)")
    void hookAllow_noRule_returnsHookAllowWithoutCanUseTool() {
        JsonNode input = JSON.createObjectNode().put("cmd", "echo hi");
        PermissionResult hookPermission = hookAllow(input);
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver resolver = new HookPermissionResolver();
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookPermission, null, stubTool(TOOL_NAME), input,
            ctxWith(permCtxNoRules()), "toolu_1", canUseTool);

        assertThat(resolved.decision())
            .as("hook allow 无规则复检命中 → 决策就是 hook 自己的 allow (CC :384)")
            .isSameAs(hookPermission);
        assertThat(canUseTool.calls.get())
            .as("hook allow 无规则 → canUseTool 不得被调用 (跳过弹窗是核心不变量)")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 正向: hook allow + settings deny 命中 → 返回 deny
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook allow + settings deny 规则命中 → 返回 deny.
     *
     * <p>WHY: CC 不变量 "hook allow does NOT bypass settings.json deny rules"
     * (toolHooks.ts:386-391) — deny 规则是 bypass-immune 底线, 即使 hook 已批准
     * 也不能绕过用户显式配置的禁用. 若此测试变绿后业务逻辑把 hook allow 直接放行,
     * 用户的 deny 规则将被 hook 静默绕过 (安全漏洞).
     */
    @Test
    @DisplayName("hook allow + settings deny 规则 → 返回 deny, 不走 canUseTool (bypass-immune)")
    void hookAllow_settingsDenyRule_overridesToDeny() {
        JsonNode input = JSON.createObjectNode().put("cmd", "dangerous");
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver resolver = new HookPermissionResolver();
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookAllow(input), null, stubTool(TOOL_NAME), input,
            ctxWith(permCtxWithDenyRule()), "toolu_1", canUseTool);

        assertThat(resolved.decision())
            .as("hook allow 不能绕过 settings deny 规则 (CC :386-391 deny 覆盖)")
            .isInstanceOf(PermissionResult.Deny.class);
        PermissionResult.Deny deny = (PermissionResult.Deny) resolved.decision();
        assertThat(deny.reason())
            .as("deny 决策必须归因于 rule (而非 hook)")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
        assertThat(canUseTool.calls.get())
            .as("deny 规则命中 → 直接返回 deny, 弹窗/询问都无意义 (CC :390)")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 正向: hook allow + settings ask 命中 → 走 canUseTool (forceDecision=null)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook allow + settings ask 规则命中 → 走 canUseTool 弹窗, forceDecision=null.
     *
     * <p>WHY: CC toolHooks.ts:392-405 — hook 批准不豁免 ask 规则; 但 forceDecision
     * 保持 null (弹窗展示的是 ask 规则的消息, 不是 hook 的消息 — 只有 hook ask
     * 才透传 forceDecision). forceDecision 泄漏 hook 消息到 ask 规则弹窗 = 语义污染.
     */
    @Test
    @DisplayName("hook allow + settings ask 规则 → 走 canUseTool, forceDecision=null (CC :392-405)")
    void hookAllow_settingsAskRule_goesToCanUseTool() {
        JsonNode input = JSON.createObjectNode().put("cmd", "needs approval");
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver resolver = new HookPermissionResolver();
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookAllow(input), null, stubTool(TOOL_NAME), input,
            ctxWith(permCtxWithAskRule()), "toolu_1", canUseTool);

        assertThat(canUseTool.calls.get())
            .as("hook allow + ask 规则 → canUseTool 必须被调 (ask 规则需要弹窗)")
            .isEqualTo(1);
        assertThat(canUseTool.forceDecisions.get(0))
            .as("hook allow 路径的 forceDecision 必须为 null (弹窗展示 ask 规则消息, CC :396-403 无 forceDecision 实参)")
            .isNull();
        assertThat(resolved.decision())
            .as("canUseTool ALLOW → 最终决策为 Allow (用户允许后执行)")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(resolved.input())
            .as("生效 input = hook 输入")
            .isSameAs(input);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [H8 v2 补全 H8-GAP-1] hook allow + Bash ask rule + sandbox auto-allow
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook allow + Bash ask rule + sandbox 激活 + auto-allow → 跳过弹窗 (fall-through 到 1c).
     *
     * <p>WHY: CC checkRuleBasedPermissions (permissions.ts:1186-1205) — 命中 ask rule 但
     * {@code canSandboxAutoAllow} (Bash 工具 + sandbox 激活 + auto-allow 开启 + 命令可沙箱化)
     * → fall-through 到 1c tool.checkPermissions 自动放行, 不弹窗. H8-GAP-1 之前 Java
     * checkRuleBasedPermissions 对 ask rule 一律返回 Ask → hook allow 在 sandbox 激活时仍弹窗,
     * 违反 CC. 本测试锁定 "sandbox 激活时 Bash ask rule 不弹窗" 行为; 沙箱关闭时行为不变 (ask).
     */
    @Test
    @DisplayName("hook allow + Bash ask rule + sandbox auto-allow → 跳过弹窗, 不走 canUseTool (H8-GAP-1)")
    void hookAllow_bashSandboxAutoAllow_skipsCanUseTool() {
        JsonNode input = JSON.createObjectNode().put("command", "ls");
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver resolver = new HookPermissionResolver();
        resolver.setSandboxManager(new SandboxManager(true, true));  // sandbox 激活 + auto-allow
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookAllow(input), null, stubBashTool(), input,
            ctxWith(permCtxWithAskRuleFor("Bash")), "toolu_sandbox", canUseTool);

        assertThat(canUseTool.calls.get())
            .as("sandbox auto-allow 时 hook allow 必须跳过 ask 弹窗 (CC :1186-1205 fall-through 到 1c)")
            .isZero();
        assertThat(resolved.decision())
            .as("fall-through 到 1c tool.checkPermissions (默认 Allow) → 决策为 hook allow")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    /**
     * hook allow + Bash ask rule + sandbox 关闭 → 仍走 canUseTool (弹窗).
     *
     * <p>WHY: canSandboxAutoAllow 的 sandbox 激活前提不满足 (isEnabled()=false) → 保持 ask
     * 语义 (CC :1201-1205). 默认配置 sandbox 关闭, 此路径是生产默认行为, 必须不变.
     */
    @Test
    @DisplayName("hook allow + Bash ask rule + sandbox 关闭 → 仍走 canUseTool (默认行为不变)")
    void hookAllow_bashAskRule_sandboxDisabled_stillAsks() {
        JsonNode input = JSON.createObjectNode().put("command", "ls");
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver resolver = new HookPermissionResolver();
        resolver.setSandboxManager(new SandboxManager(false, true));  // sandbox 关闭
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookAllow(input), null, stubBashTool(), input,
            ctxWith(permCtxWithAskRuleFor("Bash")), "toolu_sandbox_off", canUseTool);

        assertThat(canUseTool.calls.get())
            .as("sandbox 关闭 → ask rule 仍需弹窗 (canSandboxAutoAllow=false)")
            .isEqualTo(1);
        assertThat(resolved.decision())
            .isInstanceOf(PermissionResult.Allow.class);  // canUseTool stub 返回 allow
    }

    // ════════════════════════════════════════════════════════════════════════
    // 正向: hook deny → 返回 deny
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook deny → 直接返回 deny, 不走 canUseTool, input 原样.
     *
     * <p>WHY: CC toolHooks.ts:408-411 deny 分支立即返回 — hook deny 是 bypass-immune
     * 的最高优先级, 弹窗/规则复检在 deny 面前都没有意义 (规则五: 确定性短路由代码完成).
     */
    @Test
    @DisplayName("hook deny → 返回 deny, 不走 canUseTool (CC toolHooks.ts:408-411)")
    void hookDeny_returnsDenyWithoutCanUseTool() {
        JsonNode input = JSON.createObjectNode().put("cmd", "block me");
        PermissionResult hookPermission = hookDeny("Tool explicitly denied");
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver resolver = new HookPermissionResolver();
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookPermission, null, stubTool(TOOL_NAME), input,
            ctxWith(permCtxNoRules()), "toolu_1", canUseTool);

        assertThat(resolved.decision())
            .as("hook deny → 决策就是 hook 的 deny (CC :410)")
            .isSameAs(hookPermission);
        assertThat(resolved.input())
            .as("hook deny → input 原样返回 (工具不会执行, 无需生效 input)")
            .isSameAs(input);
        assertThat(canUseTool.calls.get())
            .as("hook deny → canUseTool 不得被调用 (CC :408-411 deny 短路)")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 正向: hook ask → 走 canUseTool (forceDecision=hook ask)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook ask → 走 canUseTool, forceDecision = hook 的 ask 结果.
     *
     * <p>WHY: CC toolHooks.ts:415-432 — hook ask 走正常权限流, 但 forceDecision
     * 透传 hook 的 ask, 让弹窗展示 hook 的消息 (而非默认规则消息). 若 forceDecision
     * 丢失, hook 精心构造的 ask 消息 (如安全说明) 将不可见.
     */
    @Test
    @DisplayName("hook ask → 走 canUseTool, forceDecision=hook ask (CC toolHooks.ts:415-432)")
    void hookAsk_goesToCanUseTool_withForceDecision() {
        JsonNode input = JSON.createObjectNode().put("cmd", "ask me");
        JsonNode askUpdated = JSON.createObjectNode().put("cmd", "ask me (edited)");
        PermissionResult hookPermission = hookAsk("Hook requires user confirmation", askUpdated);
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver resolver = new HookPermissionResolver();
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookPermission, null, stubTool(TOOL_NAME), input,
            ctxWith(permCtxNoRules()), "toolu_1", canUseTool);

        assertThat(canUseTool.calls.get())
            .as("hook ask → canUseTool 必须被调 (ask 需要用户弹窗)")
            .isEqualTo(1);
        assertThat(canUseTool.forceDecisions.get(0))
            .as("forceDecision = hook 的 ask 结果 (弹窗展示 hook ask 消息, CC :415-416)")
            .isSameAs(hookPermission);
        assertThat(canUseTool.inputs.get(0))
            .as("ask input = updatedInput ?? input (CC :417-421)")
            .isSameAs(askUpdated);
        assertThat(resolved.input())
            .as("生效 input = hook ask 的 updatedInput")
            .isSameAs(askUpdated);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 反向: requireCanUseTool=true + hook allow → 仍走 canUseTool
    // ════════════════════════════════════════════════════════════════════════

    /**
     * requireCanUseTool=true + hook allow → 仍走 canUseTool.
     *
     * <p>WHY: CC toolHooks.ts:356-370 — 某些上下文 (REPL / 子 agent 等) 强制要求
     * canUseTool 守卫, hook allow 不得绕过该守卫. 这是安全底线: 若 hook 能静默
     * 跳过 requireCanUseTool 上下文, 守卫形同虚设.
     */
    @Test
    @DisplayName("requireCanUseTool=true + hook allow → 仍走 canUseTool (CC toolHooks.ts:356-370)")
    void requireCanUseTool_true_hookAllow_stillGoesToCanUseTool() {
        JsonNode input = JSON.createObjectNode().put("cmd", "guarded");
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        HookPermissionResolver resolver = new HookPermissionResolver();
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookAllow(input), null, stubTool(TOOL_NAME), input,
            ctxWithRequireCanUseTool(true), "toolu_1", canUseTool);

        assertThat(canUseTool.calls.get())
            .as("requireCanUseTool 守卫优先于 hook allow (CC :356 守卫条件)")
            .isEqualTo(1);
        assertThat(canUseTool.forceDecisions.get(0))
            .as("守卫路径 forceDecision=null (hook 已放行, 无 hook ask 可透传)")
            .isNull();
        assertThat(resolved.decision())
            .as("守卫路径 canUseTool ALLOW → 最终决策 Allow")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 反向: requiresUserInteraction=true + hook 未给 updatedInput → 仍走 canUseTool
    // ════════════════════════════════════════════════════════════════════════

    /**
     * requiresUserInteraction 工具 + hook allow 未提供 updatedInput → 仍走 canUseTool (交互未满足).
     * 反之 hook 提供了 updatedInput → 交互满足, 直接返回 hook allow.
     *
     * <p>WHY: CC toolHooks.ts:350-354 interactionSatisfied 语义 — 交互型工具
     * (如 AskUserQuestion) 必须由 hook 通过 updatedInput 完成交互 (headless wrapper
     * 收集答案), 否则仍要弹窗问用户. hook 空手放行交互型工具 = 交互丢失.
     */
    @Test
    @DisplayName("requiresUserInteraction + hook 无 updatedInput → 仍走 canUseTool (CC :350-370)")
    void requiresUserInteraction_hookAllowWithoutUpdatedInput_goesToCanUseTool() {
        JsonNode input = JSON.createObjectNode().put("question", "approve?");
        Tool interactiveTool = stubTool(TOOL_NAME, true);
        RecordingCanUseTool canUseTool = new RecordingCanUseTool();

        // 场景 A: hook allow 但未给 updatedInput (AHR.updatedInput=null) → 交互未满足 → 弹窗
        HookPermissionResolver resolver = new HookPermissionResolver();
        HookPermissionResolver.ResolvedPermission resolved = resolver.resolve(
            hookAllow(input), null, interactiveTool, input,
            ctxWith(permCtxNoRules()), "toolu_1", canUseTool);
        assertThat(canUseTool.calls.get())
            .as("requiresUserInteraction + hook 无 updatedInput → 交互未满足, 必须走 canUseTool")
            .isEqualTo(1);
        assertThat(canUseTool.forceDecisions.get(0))
            .as("守卫路径 forceDecision=null")
            .isNull();

        // 场景 B: hook allow 且给了 updatedInput (headless wrapper 已代收答案) → 交互满足 → 直接放行
        JsonNode hookUpdated = JSON.createObjectNode().put("answer", "yes");
        Map<String, Object> hookUpdatedMap = Map.of("answer", "yes");
        RecordingCanUseTool satisfiedGate = new RecordingCanUseTool();
        HookPermissionResolver.ResolvedPermission satisfied = resolver.resolve(
            hookAllow(hookUpdated), hookUpdatedMap, interactiveTool, input,
            ctxWith(permCtxNoRules()), "toolu_2", satisfiedGate);
        assertThat(satisfiedGate.calls.get())
            .as("hook updatedInput 满足交互 → 不走 canUseTool (CC :353-354 interactionSatisfied)")
            .isZero();
        assertThat(satisfied.decision())
            .as("交互满足 → 返回 hook allow (不经 canUseTool)")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(satisfied.input())
            .as("交互满足路径的生效 input 保持 hook 输入")
            .isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 边界: getPreToolHookBlockingMessage 格式化文本
    // ════════════════════════════════════════════════════════════════════════

    /**
     * getPreToolHookBlockingMessage 格式化与 CC 逐字符一致:
     * {@code `${hookName} hook error: ${blockingError.blockingError}`} (hooks.ts:1882-1887).
     *
     * <p>WHY: 该消息注入 LLM 作为 deny 反馈 (runPreToolUseHooks blockingError → deny,
     * toolHooks.ts:481-498). 格式漂移会让 LLM 反馈与 CC 生态不一致, 且审计 grep
     * {@code "hook error:"} 时漏报.
     */
    @Test
    @DisplayName("getPreToolHookBlockingMessage 格式 = '<hook> hook error: <msg>' (CC hooks.ts:1882-1887)")
    void getPreToolHookBlockingMessage_formatsLikeCC() {
        String formatted = HookRegistry.getPreToolHookBlockingMessage(
            "PreToolUse:Bash", new HookBlockingError("command blocked by policy", "block-command"));
        assertThat(formatted)
            .as("CC hooks.ts:1886 `${hookName} hook error: ${blockingError.blockingError}` 逐字符对齐")
            .isEqualTo("PreToolUse:Bash hook error: command blocked by policy");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 边界: getToolUseSummary 透传到 hook 上下文
    // ════════════════════════════════════════════════════════════════════════

    /**
     * executePreToolUse 5 参 (toolUseSummary) → hook 5 参可见.
     *
     * <p>WHY: CC executePreToolHooks (hooks.ts:3394-3444) 末 1 参 toolInputSummary 透传给
     * hook 链 (S9 DEL-02: Java 无 UI 消费端, 删除前恒传 null →
     * 可观测行为不变). 本测试锁定 toolUseSummary 真实透传.
     */
    @Test
    @DisplayName("getToolUseSummary 透传到 hook (CC hooks.ts:3394-3444)")
    void toolUseSummary_reachesHookContext() {
        AtomicReference<String> capturedSummary = new AtomicReference<>();
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("capture", new PreToolUseHook() {
            @Override
            public AggregatedHookResult onPreToolUse(String toolName, JsonNode input, ToolUseContext ctx) {
                // 实现抽象 3 参主方法 (匿名类必须覆盖抽象方法); 透传走 5 参 override
                return AggregatedHookResult.proceed();
            }

            @Override
            public AggregatedHookResult onPreToolUse(
                    String toolName, JsonNode input, ToolUseContext ctx,
                    String toolUseId, String toolUseSummary) {
                capturedSummary.set(toolUseSummary);
                return AggregatedHookResult.proceed();
            }
        });

        AggregatedHookResult result = registry.executePreToolUse(
            TOOL_NAME, JSON.createObjectNode(), ctxWith(permCtxNoRules()),
            "toolu_9", "summary: 读取配置文件");

        assertThat(result).isNotNull();
        assertThat(capturedSummary.get())
            .as("hook 必须拿到 tool.getToolUseSummary 摘要文本 (CC :475)")
            .isEqualTo("summary: 读取配置文件");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 集成: hook message → resultingMessages (appendAttachment) → LLM 可见
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hook message → 普通 user 消息 → result.newMessages()（与 tool_result 同批, 一次性）.
     *
     * <p>WHY: CC runPreToolUseHooks (toolHooks.ts:478-480) {@code result.message} →
     * {@code yield {type:'message', message:{message: result.message}}} →
     * toolExecution.ts:815 {@code resultingMessages.push} → query.ts:1395
     * {@code filter(_ => _.type === 'user')} → <b>普通 user 消息</b>, 与 tool_result 同批、
     * 一次性（非 attachment 常驻重渲染）。Java PreToolUse 桥
     * （injectPreToolUseHookAttachments → pendingHookUserMessages → dispatch 并入
     * {@code t.result.newMessages}）等价。hook_user_message 不再进入
     * {@code state.attachments()}（attachment 通道仅留 additionalContext/cancelled/
     * stopped_continuation 等 CC 本就 attachment 的类型）。
     */
    @Test
    @DisplayName("hook message → 普通 user 消息 → result.newMessages()（同批, 一次性）")
    void hookMessage_asPlainUserMessage_inNewMessages() {
        Tool tool = stubTool("msg_tool");
        ToolRegistry registry = new ToolRegistry().register(tool);
        HookRegistry hooks = new HookRegistry();
        hooks.registerPreToolUse("msg", (toolName, input, ctx) -> new AggregatedHookResult(
            // [IMPL-07 OD-14] message 统一 AttachmentMessageDto 通道: String 包装为
            //   hook_user_message DTO (消费端 injectPreToolUseHookAttachments 结算为普通 user 消息)
            AggregatedHookResult.messageChannel("hello from hook",
                "PreToolUse:msg_tool", "toolu_msg_1", "PreToolUse"),
            null, false, null, null, null, null, null, null, null, null, null, null, null, null, null));

        ToolUseContext ctx = ctxWith(permCtxNoRules());
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx, null, null, hooks);
        AgentState state = new AgentState("system-prompt");
        exec.setAgentState(state);

        exec.add(new ToolUseBlock("toolu_msg_1", "msg_tool", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_msg_1"))
            .as("hook message 不阻断工具执行 (message 是旁路交付, error flag 必须 false)")
            .isFalse();
        // 新普通消息通道: hook message → result.newMessages() 的 user-role 消息
        //   (CC query.ts:1395 filter type==='user' → 与 tool_result 同批送达)
        ToolResult<?> tr = results.get(0);
        assertThat(tr.newMessages())
            .as("hook message 必须作为 user-role 消息并入 tool_result.newMessages() (与 tool_result 同批)")
            .anySatisfy(m -> {
                assertThat(m.content()).isEqualTo("hello from hook");
                assertThat(m.role()).isEqualTo(com.nexusai.model.session.dto.Role.user);
            });
        // hook_user_message 不再进入 attachments（普通消息通道, 非 attachment 常驻重渲染）
        assertThat(state.attachments())
            .as("hook message 不再进入 AgentState.attachments() (普通消息通道, 非 attachment)")
            .noneMatch(a -> "hook_user_message".equals(a.type()));
    }
}
