package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S2] BuiltInAgents 静态 ALL → 动态 getBuiltInAgents() 对齐测试 · CC builtInAgents.ts:22-72。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>: CC {@code getBuiltInAgents()} 是动态装配（base + explore/plan gate
 * + 非 SDK 入口 guide + verification gate），<b>不含 FORK</b>（FORK 在 AgentTool.tsx:335 直接引用）。
 * Java 旧静态 ALL Map 恒含全部 7 项（含 FORK），与 CC 动态语义漂移 — 移除 FORK 且 gate 生效后
 * 才能让 LLM 看到的 agent 列表与 CC 一致（agent 列表来自 SubagentToolPrompt agentListSection）。
 *
 * <p>注意: gate 是 static volatile，测试须在 finally 恢复默认值，避免污染其他测试。
 */
@DisplayName("Session S2 · BuiltInAgents getBuiltInAgents() 动态装配 (无 FORK)")
class BuiltInAgentsDynamicTest {

    @Test
    @DisplayName("getBuiltInAgents() 不含 'fork' (CC builtInAgents.ts:22-72 全文无 FORK)")
    void getBuiltInAgents_doesNotContainFork() {
        // GIVEN: 默认 gate（explorePlan=true, verification=false, 非 SDK 入口）
        // WHEN
        List<AgentDefinition> agents = BuiltInAgents.getBuiltInAgents();

        // THEN: 无 fork 类型（CC getBuiltInAgents 不含 FORK）
        assertThat(agents.stream().map(AgentDefinition::agentType)).doesNotContain("fork");
    }

    @Test
    @DisplayName("getBuiltInAgents() 是动态方法调用，非静态 Map（areExplorePlanAgentsEnabled=false 无 Explore/Plan）")
    void getBuiltInAgents_isDynamic_notStaticMap() {
        // GIVEN: 保存默认并关闭 explore/plan gate
        BuiltInAgents.GateConfig gate = new BuiltInAgents.GateConfig();
        // 先重置为默认，再关 explore/plan
        gate.setExplorePlanEnabled(true);
        gate.setVerificationEnabled(false);
        gate.setEntrypoint("");
        try {
            gate.setExplorePlanEnabled(false);
            // WHEN
            List<AgentDefinition> agents = BuiltInAgents.getBuiltInAgents();
            // THEN: 无 Explore/Plan（CC builtInAgents.ts:50-52 gate 关）
            assertThat(agents.stream().map(AgentDefinition::agentType))
                .doesNotContain("Explore", "Plan");
            // 但 base 两项恒在（CC :45-48）
            assertThat(agents.stream().map(AgentDefinition::agentType))
                .contains("general-purpose", "statusline-setup");
        } finally {
            // 恢复默认，避免污染其他测试
            gate.setExplorePlanEnabled(true);
            gate.setVerificationEnabled(false);
            gate.setEntrypoint("");
        }
    }

    @Test
    @DisplayName("areExplorePlanAgentsEnabled() 默认 true (CC builtInAgents.ts:13-20 3P default true)")
    void areExplorePlanAgentsEnabled_defaultTrue() {
        // GIVEN: 默认 gate
        BuiltInAgents.GateConfig gate = new BuiltInAgents.GateConfig();
        gate.setExplorePlanEnabled(true);
        try {
            assertThat(BuiltInAgents.areExplorePlanAgentsEnabled()).isTrue();
        } finally {
            gate.setExplorePlanEnabled(true);
        }
    }

    @Test
    @DisplayName("默认 getBuiltInAgents() 含 Explore/Plan + claude-code-guide，不含 verification")
    void getBuiltInAgents_defaultAssembly() {
        // GIVEN: 默认 gate（explorePlan=true, verification=false, entrypoint=非 SDK）
        BuiltInAgents.GateConfig gate = new BuiltInAgents.GateConfig();
        gate.setExplorePlanEnabled(true);
        gate.setVerificationEnabled(false);
        gate.setEntrypoint("");
        try {
            List<AgentDefinition> agents = BuiltInAgents.getBuiltInAgents();
            assertThat(agents.stream().map(AgentDefinition::agentType))
                .contains("general-purpose", "statusline-setup", "Explore", "Plan", "claude-code-guide");
            // verification GrowthBook default false (CC :64-69)
            assertThat(agents.stream().map(AgentDefinition::agentType)).doesNotContain("verification");
        } finally {
            gate.setExplorePlanEnabled(true);
            gate.setVerificationEnabled(false);
            gate.setEntrypoint("");
        }
    }

    @Test
    @DisplayName("get('fork') 仍可命中 — SubagentExecutor.execute 以 'fork' 字符串重解析 (S2-5)")
    void get_forkStillResolvable_forSubagentExecutorPath() {
        // WHY: SubagentTool.executeSync → executor.execute(prompt, "fork", ...) → SubagentExecutor
        // resolveAgentDefinition → BuiltInAgents.get("fork")。删除 ALL Map 后 get() 必须保留 fork
        // 特殊分支（等价 CC AgentTool.tsx:335 FORK_AGENT 直接引用），否则 fork 生产路径抛
        // AgentNotFoundException。
        AgentDefinition fork = BuiltInAgents.get("fork");
        assertThat(fork).isNotNull();
        assertThat(fork.agentType()).isEqualTo("fork");
    }
}
