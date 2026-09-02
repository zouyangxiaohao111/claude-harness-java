package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import org.junit.jupiter.api.DisplayName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-11 Q-CFG-04] HookMatcherEngine 快照源择源测试（user/merged 快照分支下 match 命中）.
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: Q-CFG-04 探查期闭环承诺 —— HookMatcherEngine 从
 * {@link HooksConfigSnapshot} 取配置（getMatchingHooks 步骤 1），快照 5 分支
 * （hooksConfigSnapshot.ts:18-53，IMPL-01 完整建模）决定引擎看到哪个来源的 hooks。
 * 本测试通过<b>引擎公共入口</b>验证三条源择源路径（与 PolicyGateHookRegistryTest #7
 * 直接断言快照 Map 互补，本测试断言引擎匹配结果）：
 * <ol>
 *   <li><b>merged（默认）分支</b>（hooksConfigSnapshot.ts:52）：user/project/local 合并 hooks
 *       → {@code Bash} matcher + {@code Bash} 事件 → match 命中；</li>
 *   <li><b>allowManagedHooksOnly 分支</b>（:27-29）：返回 policy hooks（非空）→ 引擎命中
 *       policy hook；user settings hook 被排除（方向：settings 非 managed 丢弃）；</li>
 *   <li><b>disableAllHooks 分支</b>（:22-24）：空 → 引擎零命中。</li>
 * </ol>
 *
 * <p>关联：IMPL-01 快照 5 分支建模（EV-CFG-016）+ 02 §4 闭环表 Q-CFG-04 行（风险 low）。
 *
 * @since IMPL-11 (P2 测试补强)
 */
@DisplayName("[IMPL-11 Q-CFG-04] HookMatcherEngine 快照源择源（user/merged/policy 分支 match 命中）")
class HookMatcherSnapshotSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** policy 键值 supplier：仅返回给定的键值对，其余 null（镜像 PolicyGateHookRegistryTest）. */
    private static HooksSettings policySettings(Map<String, Object> policy) {
        return new HooksSettings(key -> policy.get(key));
    }

    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    /** user settings 源装载 Bash matcher hook. */
    private static void loadUserBashHook(HooksSettings settings, String command) {
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                commandHook(command), "Bash", HookSource.USER_SETTINGS, null)
        ));
    }

    private HookMatcherEngine newEngine(HooksSettings settings) {
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        return new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
    }

    // ── 1. merged 分支（默认）：user 源 hook → 引擎 match 命中 ─────────────

    @Test
    @DisplayName("1. merged 分支: user 源 Bash hook 经引擎命中（默认无 policy 限制）")
    void mergedBranch_userHook_matches() throws Exception {
        // WHY: 分支 5（hooksConfigSnapshot.ts:52）合并全部来源 — 无 policy 时 user settings
        //       hook 必须可达引擎匹配（快照源择源的第一条路径）。
        HooksSettings settings = new HooksSettings(key -> null);
        loadUserBashHook(settings, "echo user-hook");
        HookMatcherEngine engine = newEngine(settings);

        ObjectNode input = mapper.createObjectNode();
        input.put("command", "git status");
        List<MatchedHook> matched = engine.getMatchingHooks(
            HookEvent.toolPre("Bash", input, "s1", null));

        assertThat(matched).as("merged 分支下 user hook 必须被引擎匹配").hasSize(1);
        assertThat(matched.get(0).hook()).isInstanceOf(CommandHook.class);
        assertThat(((CommandHook) matched.get(0).hook()).command()).isEqualTo("echo user-hook");
    }

    // ── 2. allowManagedHooksOnly 分支：policy hooks 命中、user hooks 排除 ──

    @Test
    @DisplayName("2. allowManagedHooksOnly 分支: 引擎命中 policy hook, user hook 被排除")
    void policyOnlyBranch_policyHookMatches_userExcluded() throws Exception {
        // WHY (EV-CFG-016): 旧实现该分支恒空 Map —— 企业 managed hook 永不执行。
        //       CC hooksConfigSnapshot.ts:27-29 返回 policySettings.hooks（非空）。
        //       引擎从快照取配置 → 必须命中 policy hook 且 user 源被排除（方向修正）。
        JsonNode policyHooks = mapper.readTree("""
            {"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "command", "command": "echo policy-hook"}]}]}
            """);
        HooksSettings settings = policySettings(Map.of(
            "allowManagedHooksOnly", Boolean.TRUE,
            "hooks", policyHooks));
        // user settings 也配了同名 hook —— managedOnly 时必须被排除
        loadUserBashHook(settings, "echo user-hook");
        HookMatcherEngine engine = newEngine(settings);

        ObjectNode input = mapper.createObjectNode();
        input.put("command", "git status");
        List<MatchedHook> matched = engine.getMatchingHooks(
            HookEvent.toolPre("Bash", input, "s1", null));

        assertThat(matched).as("managedOnly 分支必须命中 policy hooks（非空）").hasSize(1);
        assertThat(((CommandHook) matched.get(0).hook()).command())
            .as("命中的必须是 policy hook, user hook 被排除").isEqualTo("echo policy-hook");
    }

    // ── 3. disableAllHooks 分支：空 → 引擎零命中 ──────────────────────────

    @Test
    @DisplayName("3. disableAllHooks 分支: 快照为空 → 引擎零命中（短路前的源择源）")
    void disableAllBranch_engineMatchesNothing() throws Exception {
        // WHY: 分支 1（hooksConfigSnapshot.ts:22-24）policy disableAllHooks=true → 空。
        //       引擎 getMatchingHooks 从空快照取配置 → 零命中（INV-1 短路先于匹配）。
        HooksSettings settings = policySettings(Map.of("disableAllHooks", Boolean.TRUE));
        loadUserBashHook(settings, "echo user-hook");
        HookMatcherEngine engine = newEngine(settings);

        ObjectNode input = mapper.createObjectNode();
        input.put("command", "git status");
        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null)))
            .as("disableAllHooks=true 时引擎必须零命中").isEmpty();
    }
}
