package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S1 P0-1] AgentDefinitionRegistry 合并优先级对齐测试.
 *
 * <p>验证 custom 覆盖 builtIn 的覆盖语义 (对齐 CC loadAgentsDir.ts:216 {@code agentMap.set}),
 * 6 组按序 [builtIn, plugin, user, project, flag, managed] 中 managed 最高优先 / builtIn 最低.
 */
class AgentDefinitionRegistryMergePriorityTest {

    private static AgentDefinition builtIn(String type) {
        return AgentDefinition.BuiltInAgentDefinition.builder(type, "builtin-" + type, (ctx, dirs) -> "prompt").build();
    }

    private static AgentDefinition custom(String type) {
        return AgentDefinition.CustomAgentDefinition.builder(type, "custom-" + type, "userSettings", "prompt").build();
    }

    private static AgentDefinition custom(String type, String source) {
        return AgentDefinition.CustomAgentDefinition.builder(type, "custom-" + type, source, "prompt").build();
    }

    private static AgentDefinition plugin(String type) {
        return AgentDefinition.PluginAgentDefinition.builder(type, "plugin-" + type, "p", "prompt").build();
    }

    @Test
    @DisplayName("同 agentType 时 custom 覆盖 builtIn (对齐 CC agentMap.set)")
    void customAgent_overrides_builtIn_when_same_agentType() {
        // WHY: CC loadAgentsDir.ts:216 agentMap.set 覆盖语义 — managed(policy) 可覆盖 builtIn.
        // 旧 Java putIfAbsent 使内置吞掉自定义, 用户 .claude/agents/general-purpose.md 永远不生效.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("general-purpose", builtIn("general-purpose")),
            List.of(custom("general-purpose")));

        AgentDefinition found = reg.findAgent("general-purpose");
        assertThat(found).isNotNull();
        assertThat(found.source()).isEqualTo("userSettings"); // custom 胜出
    }

    @Test
    @DisplayName("custom 后注册覆盖 builtIn (custom 的 whenToUse 生效)")
    void mergeOrder_custom_overrides_builtIn() {
        // WHY: CC getActiveAgentsFromList 6 组按序合并, 后组 set 覆盖前组.
        // Java 构造器单层 builtIn+custom 合并时 custom (后注册) 覆盖 builtIn (先注册).
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("a", builtIn("a")),
            List.of(custom("a")));
        assertThat(reg.findAgent("a").source()).isEqualTo("userSettings");
        assertThat(reg.findAgent("a").whenToUse()).isEqualTo("custom-a");
    }

    @Test
    @DisplayName("无冲突时 builtIn 保留")
    void builtIn_only_when_no_custom_collision() {
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("b", builtIn("b")),
            List.of());
        assertThat(reg.findAgent("b").source()).isEqualTo("built-in");
        assertThat(reg.findAgent("b").whenToUse()).isEqualTo("builtin-b");
    }

    @Test
    @DisplayName("custom 之间后注册覆盖前注册 (同 source 同 agentType)")
    void custom_list_later_wins() {
        // WHY: 对齐 CC for..for 双循环 agentMap.set — 列表内重复 agentType 时后者胜出.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of(),
            List.of(custom("x"), custom("x")));
        assertThat(reg.findAgent("x").whenToUse()).isEqualTo("custom-x");
    }

    // ────────────────────────────────────────────────────────────────────────
    // [IMP-SUB-09 REWORK R2-WF-E] 构造路径跨源优先级（managed > project > user）
    //   —— 生产构造路径（SubagentTool → new AgentDefinitionRegistry(builtIn, loadAllSources(...))）
    //   必须与 CC getActiveAgentsFromList 同语义，详见 IMP-SUB-09-reflection §二-1。
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("构造路径：同 agentType 跨源冲突时 managed(policySettings) 最高优先（对齐 CC getActiveAgentsFromList）")
    void constructor_managed_wins_over_user_and_project() {
        // WHY: [IMP-SUB-09 终轮 Reflection §二-1] 生产构造路径对跨源同 agentType 冲突的优先级
        //   必须与 CC 一致：managed 最高。旧实现 m.put last-wins + loadAllSources 返回序
        //   [managed,user,project] → project 覆盖 user 覆盖 managed（反转）——管理策略被本地
        //   agent 静默覆盖，恰是 CC 把 managed 设最高优先所防的场景。本测试按 loadAllSources
        //   真实返回序 [managed,user,project] 构造（managed 最先、project 最后 = last-wins 最强），
        //   断言 managed 胜出。若未来把构造路径回退到 last-wins，本测试必须变红（规则九）。
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of(),
            List.of(
                custom("gp", "policySettings"),     // managed 最先（loadAllSources 序 :377-378）
                custom("gp", "userSettings"),       // user 次之
                custom("gp", "projectSettings")));  // project 最后（last-wins 下最强）

        assertThat(reg.findAgent("gp").source())
            .as("managed(policySettings) 必须覆盖 user/project（CC 6 组 managed 最高优先，loadAgentsDir.ts:203-218）")
            .isEqualTo("policySettings");
    }

    @Test
    @DisplayName("构造路径：project 覆盖 user（project > user，loadAgentsDir.ts:203-218）")
    void constructor_project_overrides_user() {
        // WHY: CC 组序 [builtIn,plugin,user,project,flag,managed] — project 晚于 user 注册 →
        //   同 type 时 project 胜出。构造路径经 getActiveAgentsFromList 折叠后 project 必须压过
        //   user（旧 last-wins 恰好同向，本测试锁定该顺序防未来回归到 if-absent 语义）。
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of(),
            List.of(custom("p", "userSettings"), custom("p", "projectSettings")));
        assertThat(reg.findAgent("p").source())
            .as("project 覆盖 user（project > user）").isEqualTo("projectSettings");
    }

    @Test
    @DisplayName("构造路径：managed 覆盖 builtIn（managed > builtIn，构造器全源折叠）")
    void constructor_managed_overrides_builtIn() {
        // WHY: 构造路径合并 builtIn + custom 后整体经 getActiveAgentsFromList 折叠 —— managed 必须
        //   压过 builtIn（CC 组序 managed 最后，loadAgentsDir.ts:203-218）。旧构造器 custom 覆盖
        //   builtIn 对"内置 + 单 custom"恰好同向；经 6 组折叠后任何 source 组合都应正确，本测试
        //   锁定 managed 高于 built-in 的最外层边界（用户/项目源覆盖内置已由既有用例覆盖）。
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("builtin-type", builtIn("builtin-type")),
            List.of(custom("builtin-type", "policySettings")));
        assertThat(reg.findAgent("builtin-type").source())
            .as("managed(policySettings) 覆盖 built-in（managed > builtIn）").isEqualTo("policySettings");
    }

    // ────────────────────────────────────────────────────────────────────────
    // [ODF-C3] 6 组覆盖优先级合并 (merge)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("merge: 同 agentType 时 plugin 覆盖 builtIn（plugin > builtIn，loadAgentsDir.ts:193-225）")
    void merge_plugin_overrides_builtIn() {
        // WHY: CC getActiveAgentsFromList 6 组 [builtIn, plugin, user, project, flag, managed] 按序
        //   agentMap.set — plugin 晚于 builtIn 注册 → 同 type 时 plugin 胜出. registry.merge 必须
        //   应用该优先级，否则 plugin agents 与内置同名被吞.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("gp", builtIn("gp")), List.of());

        reg.merge(List.of(plugin("gp")));

        assertThat(reg.findAgent("gp").source())
            .as("plugin 覆盖 builtIn（plugin > builtIn）").isEqualTo("plugin");
    }

    @Test
    @DisplayName("merge: flag 覆盖 plugin 覆盖 builtIn（flag > plugin > builtIn）")
    void merge_flag_overrides_plugin_overrides_builtIn() {
        // WHY: 6 组顺序 [builtIn, plugin, user, project, flag, managed] — flag 比 plugin 晚注册,
        //   managed 比 flag 晚. 递增覆盖验证顺序不漂移.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("a", builtIn("a")), List.of());

        reg.merge(List.of(plugin("a")));
        assertThat(reg.findAgent("a").source())
            .as("第 1 次 merge: plugin 覆盖 builtIn").isEqualTo("plugin");

        reg.merge(List.of(custom("a", "flagSettings")));
        assertThat(reg.findAgent("a").source())
            .as("第 2 次 merge: flag 覆盖 plugin").isEqualTo("flagSettings");
    }

    @Test
    @DisplayName("merge: managed 覆盖 flag（managed > flag，最高优先）")
    void merge_managed_overrides_flag() {
        // WHY: CC getActiveAgentsFromList 最后 group 是 managed（policySettings），最高优先 —
        //   managed 定义必须压过 flag/project/user/plugin/builtIn 全部来源.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("b", builtIn("b")), List.of());

        reg.merge(List.of(custom("b", "flagSettings")));
        reg.merge(List.of(custom("b", "policySettings")));

        assertThat(reg.findAgent("b").source())
            .as("managed(policySettings) 是 6 组最高优先").isEqualTo("policySettings");
    }

    @Test
    @DisplayName("merge: 追加 flag/plugin 后 listAgents() 返回含新来源（占位 N/A 移除）")
    void merge_flag_agents_visible_in_listAgents() {
        // WHY: [ODF-C3 验收 #5] registry.listAgents() 必须返回含 flag+plugin 来源 agent —
        //   旧状态 plugin 完全不加载（占位 N/A），flag 无生产 producer 无从可见.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("c", builtIn("c")), List.of());

        reg.merge(List.of(plugin("p1"), custom("f1", "flagSettings")));

        List<String> types = reg.listAgents().stream().map(AgentDefinition::agentType).toList();
        assertThat(types).as("listAgents 必须包含 flag+plugin 来源 agent").contains("p1", "f1");
    }

    @Test
    @DisplayName("merge: null/空列表为 no-op，registry 不变")
    void merge_null_or_empty_is_noop() {
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("d", builtIn("d")), List.of());
        int before = reg.listAgents().size();

        reg.merge(null);
        reg.merge(List.of());

        assertThat(reg.listAgents().size()).isEqualTo(before);
    }

    // ────────────────────────────────────────────────────────────────────────
    // [R-B2] shadowed 记录对齐 CC resolveAgentOverrides（agentDisplay.ts:46-72）
    //   —— B-2 受控差异修复: registry 不再只留 winner, resolveAgentOverrides 还原全量
    //   overriddenBy 覆盖标注, agents 端点可展示 "(shadowed by ...)"。
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R-B2: 同 agentType custom 覆盖 builtIn 时, resolveAgentOverrides 记录 builtIn 为 shadowed(overriddenBy=userSettings)")
    void resolveAgentOverrides_builtIn_shadowed_by_custom() {
        // WHY (B-2): CC resolveAgentOverrides (agentDisplay.ts:46-72) 把全量 allAgents 逐条标注
        //   overriddenBy —— 被覆盖的 builtIn 必须可达, agents 端点才能展示 "(shadowed by user)".
        //   旧 registry 折叠后只留 winner, builtIn 被静默丢弃 (overriddenBy 恒 null, B-2 受控差异).
        //   若未来回退到"只留 winner"语义, 本测试必须变红 (规则九).
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("general-purpose", builtIn("general-purpose")),
            List.of(custom("general-purpose")));

        List<AgentDefinitionRegistry.ResolvedAgentDefinition> resolved = reg.resolveAgentOverrides();
        assertThat(resolved).hasSize(2);
        AgentDefinitionRegistry.ResolvedAgentDefinition builtInEntry = resolved.stream()
            .filter(r -> "built-in".equals(r.agent().source())).findFirst().orElseThrow();
        AgentDefinitionRegistry.ResolvedAgentDefinition customEntry = resolved.stream()
            .filter(r -> "userSettings".equals(r.agent().source())).findFirst().orElseThrow();
        assertThat(builtInEntry.overriddenBy())
            .as("builtIn 被 custom 覆盖 → overriddenBy=userSettings（CC active.source, agentDisplay.ts:66-67）")
            .isEqualTo("userSettings");
        assertThat(customEntry.overriddenBy())
            .as("winner 本身 overriddenBy=null（同 source 不标）").isNull();
        // winner 面不变: findAgent 仍返回 custom（覆盖语义未被 shadowed 记录破坏）
        assertThat(reg.findAgent("general-purpose").source()).isEqualTo("userSettings");
    }

    @Test
    @DisplayName("R-B2: 构造路径 managed 覆盖 user/project 时, 两个被覆盖 source 均记录 shadowed")
    void resolveAgentOverrides_managed_shadows_user_and_project() {
        // WHY (B-2): 对齐 CC resolveAgentOverrides —— 一个 agentType 可有多个被覆盖 source 同时存在
        //   (builtIn/user/project/flag/managed 各自一条), 每个非 winner 都标 overriddenBy=winner.source.
        //   只断言 winner 而漏断言 shadowed 源会漏掉 B-2 修复的核心（shadowed 可达性）.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of(),
            List.of(
                custom("gp", "policySettings"),
                custom("gp", "userSettings"),
                custom("gp", "projectSettings")));

        List<AgentDefinitionRegistry.ResolvedAgentDefinition> resolved = reg.resolveAgentOverrides();
        assertThat(resolved).hasSize(3);
        assertThat(resolved).allSatisfy(r -> {
            if ("policySettings".equals(r.agent().source())) {
                assertThat(r.overriddenBy()).as("managed 是 winner → overriddenBy=null").isNull();
            } else {
                assertThat(r.overriddenBy()).as("user/project 被 managed 覆盖").isEqualTo("policySettings");
            }
        });
    }

    @Test
    @DisplayName("R-B2: (agentType, source) 同键重复仅保留首现 (对齐 CC seen Set agentDisplay.ts:61-63)")
    void resolveAgentOverrides_dedupe_by_type_and_source_keeps_first() {
        // WHY (B-2): CC resolveAgentOverrides 以 `${agentType}:${source}` 去重·首现保留 —— worktree
        //   双副本同源 agent 只记一次 (agentDisplay.ts:61-63). 首现同 source 即 winner → overriddenBy=null.
        //   若未来改为不去重, shadowed 列表会出现重复条目, 本测试必须变红.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of(),
            List.of(custom("dup", "userSettings"), custom("dup", "userSettings")));

        List<AgentDefinitionRegistry.ResolvedAgentDefinition> resolved = reg.resolveAgentOverrides();
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).agent().agentType()).isEqualTo("dup");
        assertThat(resolved.get(0).overriddenBy()).isNull();
    }

    @Test
    @DisplayName("R-B2: merge 逐级覆盖时 shadowed 记录跨 merge 保留 (flag > plugin > builtIn)")
    void resolveAgentOverrides_merge_flag_shadows_plugin_and_builtIn() {
        // WHY (B-2): 折叠基已改为全量 allAgents (而非 winner-only), 被覆盖的 builtIn/plugin 必须跨
        //   merge 保留 —— 旧实现 merge 基 byType.values() 会把前一轮 shadowed 丢弃, overriddenBy 不可达.
        //   若未来把 merge 基回退到 winner-only, 本测试必须变红 (规则九).
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("a", builtIn("a")), List.of());
        reg.merge(List.of(plugin("a")));
        reg.merge(List.of(custom("a", "flagSettings")));

        List<AgentDefinitionRegistry.ResolvedAgentDefinition> resolved = reg.resolveAgentOverrides();
        assertThat(resolved).hasSize(3); // builtIn + plugin + flag
        assertThat(resolved).allSatisfy(r -> {
            if ("flagSettings".equals(r.agent().source())) {
                assertThat(r.overriddenBy()).as("flag 是最终 winner → overriddenBy=null").isNull();
            } else {
                assertThat(r.overriddenBy()).as("builtIn/plugin 被 flag 覆盖").isEqualTo("flagSettings");
            }
        });
        // winner 面 (listAgents) 只含 flag; shadowed 仅经 resolveAgentOverrides 可达
        assertThat(reg.listAgents())
            .singleElement()
            .satisfies(a -> assertThat(a.source()).isEqualTo("flagSettings"));
    }

    @Test
    @DisplayName("R-B2: listAgents() 只含 winner, shadowed 仅经 resolveAgentOverrides 可达")
    void resolveAgentOverrides_reveals_shadowed_that_listAgents_hides() {
        // WHY (B-2): agents 端点要展示 shadowed 必须消费 resolveAgentOverrides —— listAgents 是 active
        //   (winner) 面 (对齐 CC activeAgents, loadAgentsDir.ts:365), 不含被覆盖 agent.
        AgentDefinitionRegistry reg = new AgentDefinitionRegistry(
            Map.of("h", builtIn("h")),
            List.of(custom("h", "userSettings"), custom("h", "policySettings")));

        assertThat(reg.listAgents()).hasSize(1);
        assertThat(reg.resolveAgentOverrides()).hasSize(3);
        assertThat(reg.resolveAgentOverrides().stream().filter(r -> r.overriddenBy() != null))
            .as("builtIn + userSettings 两个非 winner 均应标 shadowed")
            .hasSize(2);
    }
}
