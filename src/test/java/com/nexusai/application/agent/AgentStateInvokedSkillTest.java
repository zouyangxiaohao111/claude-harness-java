package com.nexusai.application.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-6-STATE-1] AgentState.invokedSkills Map + 5 函数 · 对齐 CC
 * {@code Open-ClaudeCode/src/bootstrap/state.ts:178-187,1502-1563}。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: invokedSkills 的核心意图是
 * <b>压缩重注入不跨 agent 泄漏</b> + <b>预算有界</b>。CC 用复合键
 * {@code `${agentId ?? ''}:${skillName}`} 让多 agent（主会话 + forked 子 agent）
 * 的 skill 记录互不覆盖，压缩后保留并重注入；本测试覆盖 7 个关键不变式：
 * <ol>
 *   <li>{@link AgentState#addInvokedSkill(String,String,String,UUID)} null-agent（主会话）
 *       与 getInvokedSkillsForAgent(null) 主会话作用域命中</li>
 *   <li>主会话与子 agent 完全隔离（getForAgent(null) 不得看到子 agent 条目）</li>
 *   <li>复合键语义：同 skillName 跨 agent 并存不覆盖；同 agentId 同名后写覆盖
 *       （size 保持 1，防止跨压缩后重复注入累积 —— 预算有界）</li>
 *   <li>{@link AgentState#getInvokedSkills()} 返回双 agent 全量（压缩重注入读源）</li>
 *   <li>{@link AgentState#clearInvokedSkills(Set)} 无参 / 空集全清</li>
 *   <li>clearInvokedSkills(preserved) 关键语义（CC state.ts:1551）：
 *       null-agent 恒被删，仅保留 preserved 集合内 agent</li>
 *   <li>{@link AgentState#clearInvokedSkillsForAgent(UUID)} 仅删该 agent，其余保留
 *       （子 agent 完成/失败后释放，主会话不受影响）</li>
 * </ol>
 *
 * <p>这些不变式若被破坏：压缩后 skill 内容会跨 agent 互相污染上下文
 * （泄漏）或随每次压缩无限重复注入（无界），两者都直接破坏 LLM 输入质量。
 */
class AgentStateInvokedSkillTest {

    private AgentState state;
    private UUID agentX;
    private UUID agentY;

    @BeforeEach
    void setUp() {
        state = new AgentState("test-system-prompt");
        agentX = UUID.randomUUID();
        agentY = UUID.randomUUID();
    }

    @Test
    @DisplayName("addInvokedSkill(null-agent) → getForAgent(null) 命中 1 条（主会话作用域）")
    void mainSessionScopedAddHitsNullAgentFilter() {
        state.addInvokedSkill("skill-a", "/skills/skill-a/SKILL.md", "content-a", null);

        var main = state.getInvokedSkillsForAgent(null);
        assertThat(main).hasSize(1);
        assertThat(main.values()).singleElement()
            .satisfies(info -> {
                assertThat(info.skillName()).isEqualTo("skill-a");
                assertThat(info.skillPath()).isEqualTo("/skills/skill-a/SKILL.md");
                assertThat(info.content()).isEqualTo("content-a");
                assertThat(info.agentId()).isNull();
            });
    }

    @Test
    @DisplayName("跨 agent 隔离：主会话与子 agent 条目互不可见")
    void crossAgentIsolation() {
        state.addInvokedSkill("skill-a", "/skills/a/SKILL.md", "main-content", null);
        state.addInvokedSkill("skill-a", "/skills/a/SKILL.md", "agentx-content", agentX);

        // 主会话视角：只见 null-agent 条目，看不见 agentX 的（防压缩重注入泄漏）
        assertThat(state.getInvokedSkillsForAgent(null)).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(agentX)).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(agentX).values())
            .singleElement()
            .satisfies(info -> assertThat(info.content()).isEqualTo("agentx-content"));
    }

    @Test
    @DisplayName("复合键语义：跨 agent 同名并存；同 agent 同名后写覆盖 size 保持")
    void compositeKeySemantics() {
        // 同 skillName 不同 agentId → 并存（键含 agentId 前缀）
        state.addInvokedSkill("skill-x", "/skills/x/SKILL.md", "v1", agentX);
        state.addInvokedSkill("skill-x", "/skills/x/SKILL.md", "v2", agentY);
        assertThat(state.getInvokedSkills()).hasSize(2);

        // 同 agentId 同名后写覆盖 → size 保持 1（预算有界：不随压缩重复累积）
        state.addInvokedSkill("skill-x", "/skills/x/SKILL.md", "v3", agentX);
        assertThat(state.getInvokedSkills()).hasSize(2);
        assertThat(state.getInvokedSkillsForAgent(agentX))
            .hasSize(1)
            .allSatisfy((k, info) -> assertThat(info.content()).isEqualTo("v3"));
    }

    @Test
    @DisplayName("3 参便捷重载等价 CC 默认参 agentId=null（主会话）")
    void threeArgOverloadDefaultsToMainSession() {
        state.addInvokedSkill("skill-b", "/skills/b/SKILL.md", "content-b");

        assertThat(state.getInvokedSkills()).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(null)).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(agentX)).isEmpty();
    }

    @Test
    @DisplayName("getInvokedSkills() 返回双 agent 全量（压缩重注入读源）")
    void getAllReturnsBothAgents() {
        state.addInvokedSkill("skill-1", "/s1/SKILL.md", "c1", null);
        state.addInvokedSkill("skill-2", "/s2/SKILL.md", "c2", agentX);

        assertThat(state.getInvokedSkills()).hasSize(2);
        assertThat(state.getInvokedSkills().keySet())
            .containsExactlyInAnyOrder(":skill-1", agentX + ":skill-2");
    }

    @Test
    @DisplayName("clearInvokedSkills(null) / 空集 → 全清")
    void clearAllWhenNullOrEmptyPreserved() {
        state.addInvokedSkill("skill-1", "/s1/SKILL.md", "c1", null);
        state.addInvokedSkill("skill-2", "/s2/SKILL.md", "c2", agentX);

        state.clearInvokedSkills(null);
        assertThat(state.getInvokedSkills()).isEmpty();

        state.addInvokedSkill("skill-1", "/s1/SKILL.md", "c1", null);
        state.addInvokedSkill("skill-2", "/s2/SKILL.md", "c2", agentX);
        state.clearInvokedSkills(Set.of());
        assertThat(state.getInvokedSkills()).isEmpty();
    }

    @Test
    @DisplayName("clearInvokedSkills({X}) 复刻 CC state.ts:1551：保留 X，删 null-agent 与 Y-agent")
    void clearPreservedDeletesNullAgentAndOthers() {
        state.addInvokedSkill("skill-main", "/s-main/SKILL.md", "main", null);
        state.addInvokedSkill("skill-x", "/s-x/SKILL.md", "x", agentX);
        state.addInvokedSkill("skill-y", "/s-y/SKILL.md", "y", agentY);

        // 保留集合保护 X；null-agent（主会话）恒被删 —— 与 CC 严格一致
        state.clearInvokedSkills(Set.of(agentX));

        assertThat(state.getInvokedSkills()).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(agentX)).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(null)).isEmpty();
        assertThat(state.getInvokedSkillsForAgent(agentY)).isEmpty();
    }

    @Test
    @DisplayName("clearInvokedSkillsForAgent(X) 仅删 X，主会话与 Y 保留")
    void clearForAgentOnlyRemovesThatAgent() {
        state.addInvokedSkill("skill-main", "/s-main/SKILL.md", "main", null);
        state.addInvokedSkill("skill-x", "/s-x/SKILL.md", "x", agentX);
        state.addInvokedSkill("skill-y", "/s-y/SKILL.md", "y", agentY);

        state.clearInvokedSkillsForAgent(agentX);

        assertThat(state.getInvokedSkills()).hasSize(2);
        assertThat(state.getInvokedSkillsForAgent(agentX)).isEmpty();
        assertThat(state.getInvokedSkillsForAgent(null)).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(agentY)).hasSize(1);
    }

    @Test
    @DisplayName("返回 Map 不共享内部引用：getForAgent 新 Map 修改不影响内部状态")
    void filteredMapIsDetached() {
        state.addInvokedSkill("skill-a", "/s-a/SKILL.md", "a", agentX);

        var filtered = state.getInvokedSkillsForAgent(agentX);
        filtered.clear(); // 修改返回的新 Map

        assertThat(state.getInvokedSkills()).hasSize(1); // 内部不受影响
    }
}
