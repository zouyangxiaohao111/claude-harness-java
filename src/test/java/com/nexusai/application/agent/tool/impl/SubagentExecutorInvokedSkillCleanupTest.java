package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-6-CLEANUP-1] SubagentExecutor finally 清理 invokedSkills 行为验证.
 *
 * <p>WHY (规则九 · 验证意图): CC 4 个清理调用方 — SkillTool.ts:287 (fork finally) /
 * AgentTool.tsx:1032 (后台化 finally) / AgentTool.tsx:1187 (子 agent 完成路径) /
 * agentToolUtils.ts:683 (killed/failed finally) — Java 端全部收敛到
 * {@link SubagentExecutor#runSubagentQueryLoop} 的 finally → {@link SubagentExecutor#cleanSubagentInvokedSkills}
 * （隔离 state 即子 agent 写入侧落点）。若该清理缺失：forked/子 agent 调用的 skill 全文
 * （每条含完整 skill content）在子 agent 结束后仍留在 invokedSkills Map，随主会话压缩被重注入
 * → 累积泄漏 + stale skill 注入，直接污染 LLM 输入。
 *
 * <p><b>测试方式说明</b>: runSubagentQueryLoop 依赖 LLM 循环等重依赖无法在单测跑全流程，故
 * 把清理抽成 package-private {@code cleanSubagentInvokedSkills(state, agentId)} seam（对齐
 * {@link SubagentExecutorSessionHookCleanupTest} 测 {@code cleanupSessionHooks} 先例）。
 * finally 接线本身由 grep 硬指标兜底
 * （{@code grep -n "cleanSubagentInvokedSkills" SubagentExecutor.java} 命中 finally 调用点）。
 *
 * <p>RED 依据: 实施前 cleanSubagentInvokedSkills 不存在（调用编译失败）。GREEN 后: 清理仅删
 * 目标 agent 条目，主会话(null-agent) 与其它 agent 条目不受影响（clearForAgent 精确性）。
 */
@DisplayName("[P1-6-CLEANUP-1] SubagentExecutor cleanSubagentInvokedSkills 精确清理子 agent skill")
class SubagentExecutorInvokedSkillCleanupTest {

    private final SubagentExecutor executor =
            new SubagentExecutor(null, null, null, null, null, "model", "system-prompt");

    private final AgentState state = new AgentState("test-system-prompt");
    private final UUID agentX = UUID.randomUUID();
    private final UUID agentY = UUID.randomUUID();

    @Test
    @DisplayName("cleanSubagentInvokedSkills(X) 清空 X 条目，主会话与 Y 条目保留")
    void cleanForAgent_onlyRemovesTargetAgentEntries() {
        // 预置 3 类条目：主会话(null-agent) + agentX + agentY
        state.addInvokedSkill("skill-main", "/s-main/SKILL.md", "main", null);
        state.addInvokedSkill("skill-x", "/s-x/SKILL.md", "x-content", agentX);
        state.addInvokedSkill("skill-y", "/s-y/SKILL.md", "y-content", agentY);

        executor.cleanSubagentInvokedSkills(state, agentX);

        assertThat(state.getInvokedSkillsForAgent(agentX))
                .as("fork 子 agent 完成/失败后其 skill 全文必须释放")
                .isEmpty();
        assertThat(state.getInvokedSkillsForAgent(null))
                .as("主会话(null-agent) skill 不受子 agent 清理影响")
                .hasSize(1)
                .allSatisfy((k, info) -> assertThat(info.content()).isEqualTo("main"));
        assertThat(state.getInvokedSkillsForAgent(agentY))
                .as("其它 agent 条目不受影响（clearForAgent 精确性）")
                .hasSize(1);
    }

    @Test
    @DisplayName("cleanSubagentInvokedSkills 对空 agentId 或无条目 state 安全 no-op")
    void cleanForAgent_nullOrEmpty_isSafeNoop() {
        state.addInvokedSkill("skill-main", "/s-main/SKILL.md", "main", null);
        // 从未注册的 agentId → 无条目可删，不抛异常
        executor.cleanSubagentInvokedSkills(state, UUID.randomUUID());
        assertThat(state.getInvokedSkillsForAgent(null)).hasSize(1);
        // null state / null agentId → 安全返回
        executor.cleanSubagentInvokedSkills(null, agentX);
        executor.cleanSubagentInvokedSkills(state, null);
        assertThat(state.getInvokedSkillsForAgent(null)).hasSize(1);
    }
}
