package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.SessionHookStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S8 H8 CCJ-HOOKS-T8-02] SubagentExecutor Step 13 plugin-only 门控测试.
 *
 * <p>对齐 CC runAgent.ts:564-566:
 * <pre>
 *   const hooksAllowedForThisAgent =
 *     !isRestrictedToPluginOnly('hooks') || isSourceAdminTrusted(agentDefinition.source)
 *   if (agentDefinition.hooks && hooksAllowedForThisAgent) registerFrontmatterHooks(..., true)
 * </pre>
 * policy (strictPluginOnlyCustomization) 锁 hooks 面且 agent source 非 admin-trusted
 * (userSettings/projectSettings/flagSettings) → 不注册; built-in/plugin/policySettings
 * (ADMIN_TRUSTED_SOURCES) 始终放行. 与 SkillToolImpl:1158-1162 skill 侧门控同一语义.
 *
 * <p>测试方式 (seam 模式): {@link SubagentExecutor#registerAgentFrontmatterHooks} 是
 * package-private static seam (Step 13 真实调用, collectAdditionalContext 先例), 入参
 * Optional&lt;Map&gt; + source 字符串规避 AgentDefinition 构造. RED 依据: 门控在 S8 前不存在
 * (SubagentExecutor 无 PluginOnlyPolicy hooks 面引用, grep 实证), 锁 policy 时 userSettings
 * agent 仍注册.
 */
@DisplayName("[IMP-HOOKS-S8 H8] SubagentExecutor Step 13 frontmatter hooks plugin-only 门控")
class SubagentFrontmatterHookGateTest {

    private static final Map<String, Object> HOOKS = Map.of(
            "PreToolUse", List.of(Map.of(
                    "matcher", "*",
                    "hooks", List.of(Map.of("type", "command", "command", "echo a")))));

    /** WHY: 断言注册面经 SessionHookStore 可见 (CC registerFrontmatterHooks addSessionHook 后
     *  getSessionHooks 可查), 执行链分发 (executeOneConfiguredHook 4 分支) 已有独立覆盖. */
    private static int registerWith(String source, java.util.function.Supplier<Map<String, Object>> supplier) {
        HookRegistry hookRegistry = new HookRegistry();
        int count = SubagentExecutor.registerAgentFrontmatterHooks(
                hookRegistry, "agent-1", "subagent",
                Optional.of(HOOKS), source, supplier);
        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                hookRegistry.getSessionHooks("agent-1", HookEventType.PRE_TOOL_USE);
        assertThat(hooks.containsKey(HookEventType.PRE_TOOL_USE))
            .as("门控语义与注册结果必须一致 (count>0 ↔ session hooks 可见)")
            .isEqualTo(count > 0);
        return count;
    }

    @Test
    @DisplayName("policy 锁 hooks 面 + userSettings agent → 0 注册 (CC runAgent.ts:564-566)")
    void policyLocksHooks_userSettingsAgent_skipsRegistration() {
        int count = registerWith("userSettings",
                () -> Map.of("strictPluginOnlyCustomization", true));
        assertThat(count).as("userSettings agent 在 hooks 面锁 plugin-only 时必须跳过 frontmatter hooks").isZero();
    }

    @Test
    @DisplayName("policy 锁 hooks 面 + projectSettings agent → 0 注册 (同 userSettings 被拒)")
    void policyLocksHooks_projectSettingsAgent_skipsRegistration() {
        int count = registerWith("projectSettings",
                () -> Map.of("strictPluginOnlyCustomization", true));
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("policy 锁 hooks 面 + plugin agent → 注册 (admin-trusted 例外, pluginOnlyPolicy.ts:38-39)")
    void policyLocksHooks_pluginAgent_registers() {
        int count = registerWith("plugin",
                () -> Map.of("strictPluginOnlyCustomization", true));
        assertThat(count).as("plugin 来源 ∈ ADMIN_TRUSTED_SOURCES, policy 锁定时仍注册").isEqualTo(1);
    }

    @Test
    @DisplayName("policy 锁 hooks 面 + built-in agent → 注册 (admin-trusted 例外)")
    void policyLocksHooks_builtInAgent_registers() {
        int count = registerWith("built-in",
                () -> Map.of("strictPluginOnlyCustomization", true));
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("policy 锁 hooks 面 + policySettings agent → 注册 (admin-trusted 例外)")
    void policyLocksHooks_policySettingsAgent_registers() {
        int count = registerWith("policySettings",
                () -> Map.of("strictPluginOnlyCustomization", true));
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("policy array 形式只锁 hooks 面 + userSettings agent → 0 注册")
    void policyLocksHooksArray_userSettingsAgent_skipsRegistration() {
        int count = registerWith("userSettings",
                () -> Map.of("strictPluginOnlyCustomization", List.of("hooks")));
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("未接线 supplier (默认 Map::of) + userSettings agent → 注册 (无 policy 不锁)")
    void noSupplierWired_userSettingsAgent_registers() {
        int count = registerWith("userSettings", Map::of);
        assertThat(count).as("未接线 supplier → isRestrictedToPluginOnly false → 正常注册").isEqualTo(1);
    }

    @Test
    @DisplayName("hooks 为空 → 0 注册 (CC registerFrontmatterHooks.ts:25-27 空对象早退)")
    void emptyHooks_returnsZero() {
        HookRegistry hookRegistry = new HookRegistry();
        int count = SubagentExecutor.registerAgentFrontmatterHooks(
                hookRegistry, "agent-1", "subagent",
                Optional.empty(), "userSettings", Map::of);
        assertThat(count).isZero();
    }
}
