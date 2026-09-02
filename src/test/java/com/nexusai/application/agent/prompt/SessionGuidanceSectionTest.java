package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.ToolNameConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * session_guidance per-bullet 门控矩阵测试 · 对齐 CC getSessionSpecificGuidanceSection
 * （prompts.ts:352-400）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: 旧 SessionSpecificGuidance 是三连门控（ask-user/agent/skill），
 * 缺 4 类子弹（'!'/explore-plan/discover-skills/verification，gap S-01/S-06）。CC 真源是
 * per-bullet 门控，每颗子弹独立条件。测试钉死：
 * <ul>
 *   <li>'!' 恒注入（Java 交互式默认，OPD-SP-22）；nonInteractiveSession=true 时跳过</li>
 *   <li>explore-plan 默认可达（3P 默认 true）且需 hasAgentTool；flag 关 → 不注入</li>
 *   <li>discover-skills / verification 默认 false（feature-gated / 3P A/B）</li>
 *   <li>空 items → null（:398）；'!' 是唯一无条件子弹</li>
 * </ul>
 */
class SessionGuidanceSectionTest {

    private static final SessionGuidanceSection.SessionGuidanceFlags DEFAULTS =
        SessionGuidanceSection.SessionGuidanceFlags.defaults();

    @AfterEach
    void resetRuntimeState() {
        // R-A12：复位 coordinator 动态源 supplier（null=回退 config 静态槽）避免跨测试污染
        ForkSubagent.setCoordinatorModeSupplier(null);
        // 还原默认门槽（featureOn=true / coordinator=false / nonInteractive=false）
        ForkSubagent.syncRuntimeGate(true, false, false);
    }

    @Test
    @DisplayName("默认交互式：恒注入 '!' 子弹，其余门控全关 → 仍非 null（OPD-SP-22）")
    void bang_alwaysInjected_interactive() {
        String guidance = SessionGuidanceSection.build(Set.of(), List.of(), DEFAULTS);

        assertThat(guidance).as("'!' 恒注入 → 非 null").isNotNull();
        assertThat(guidance).startsWith("# Session-specific guidance\n");
        assertThat(guidance).contains("suggest they type `! <command>` in the prompt");
        assertThat(guidance).doesNotContain("has denied a tool call");        // 无 ask-user
        assertThat(guidance).doesNotContain("subagent_type=Explore");          // 无 agent → 无 explore
        assertThat(guidance).doesNotContain("/<skill-name>");                  // 无 skill
    }

    @Test
    @DisplayName("非交互 + 全门控关 → items 空 → null（CC:398）")
    void allGatesOff_nonInteractive_returnsNull() {
        SessionGuidanceSection.SessionGuidanceFlags flags =
            new SessionGuidanceSection.SessionGuidanceFlags(true, true, false, false, false);

        String guidance = SessionGuidanceSection.build(Set.of(), List.of(), flags);

        assertThat(guidance).as("空 items → null（prompts.ts:398 items.length===0）").isNull();
    }

    @Test
    @DisplayName("ask-user 子弹：仅 enabledTools 含 AskUserQuestion 时注入（:365-367）")
    void askUser_bullet_gatedOnTool() {
        String with = SessionGuidanceSection.build(
            Set.of(ToolNameConstants.ASK_USER_QUESTION_TOOL_NAME), List.of(), DEFAULTS);
        String without = SessionGuidanceSection.build(
            Set.of(), List.of(), DEFAULTS);

        assertThat(with).contains("If you do not understand why the user has denied a tool call, use the "
            + ToolNameConstants.ASK_USER_QUESTION_TOOL_NAME + " to ask them.");
        assertThat(without).doesNotContain("has denied a tool call");
    }

    @Test
    @DisplayName("agent-tool 子弹：enabledTools 含 Agent 时注入 getAgentToolSection 非 fork 变体（:373）")
    void agentTool_bullet_gatedOnTool() {
        String with = SessionGuidanceSection.build(
            Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(), DEFAULTS);
        String without = SessionGuidanceSection.build(Set.of(), List.of(), DEFAULTS);

        assertThat(with).contains("Use the " + AgentToolConstants.AGENT_TOOL_NAME + " tool with specialized agents");
        assertThat(without).doesNotContain("specialized agents");
    }

    @Test
    @DisplayName("agent-tool 子弹 fork 变体：flags.forkSubagentEnabled=true → AgentToolSection.get(true)（CC prompts.ts:318）；explore-plan 停注（:374-381 !isForkSubagentEnabled）")
    void agentTool_bullet_forkVariantWhenFlagOn() {
        // OPD-SP-23：fork 开关打开时 session_guidance 注入 fork 变体文本（对齐 CC getAgentToolSection 双分支，
        // prompts.ts:316-320 isForkSubagentEnabled() ? :318 : :319），且 explore-plan 两子弹不注入（:374-381）
        SessionGuidanceSection.SessionGuidanceFlags forkOn =
            new SessionGuidanceSection.SessionGuidanceFlags(false, true, true, false, false);

        String guidance = SessionGuidanceSection.build(
            Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(), forkOn);

        assertThat(guidance).as("fork 开关真 → :318 fork 变体注入（without a subagent_type creates a fork）")
            .contains("without a subagent_type creates a fork")
            .contains("do not re-delegate.");
        assertThat(guidance).as("fork 变体下不出现非 fork 变体文本（:319）").doesNotContain("specialized agents");
        assertThat(guidance).as("fork 开关真 → explore-plan 两子弹停注（:374-381 !isForkSubagentEnabled）")
            .doesNotContain("subagent_type=Explore");
    }

    @Test
    @DisplayName("runtimeDefaults：fork 运行时门槽接线真实值 → 子弹选变体（开 → :318 fork 停 explore；关 → :319 + explore-plan）")
    void runtimeDefaults_forkGateWiresBulletVariant() {
        // RES-SP23：session_guidance 生产路径经 runtimeDefaults() 读 ForkSubagent.isForkSubagentEnabled()
        // （CC prompts.ts:317/374 渲染时全局判定），变体选择随真实 gate 翻转。
        ForkSubagent.syncRuntimeGate(true, false, false);
        try {
            String on = SessionGuidanceSection.build(
                Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(),
                SessionGuidanceSection.SessionGuidanceFlags.runtimeDefaults());
            assertThat(on).as("fork 门槽开 → :318 fork 变体注入").contains("without a subagent_type creates a fork");
            assertThat(on).as("fork 门槽开 → explore-plan 停注（:374-381）").doesNotContain("subagent_type=Explore");
        } finally {
            ForkSubagent.syncRuntimeGate(false, false, false);
        }
        try {
            String off = SessionGuidanceSection.build(
                Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(),
                SessionGuidanceSection.SessionGuidanceFlags.runtimeDefaults());
            assertThat(off).as("fork 门槽关 → :319 非 fork 变体注入").contains("specialized agents");
            assertThat(off).as("fork 门槽关 → explore-plan 恢复注入").contains("subagent_type=Explore");
        } finally {
            ForkSubagent.syncRuntimeGate(true, false, false); // 还原默认门槽
        }
    }

    @Test
    @DisplayName("R-A12 单源收敛：coordinator 动态 bean supplier 优先于 config 静态槽 → 子弹选变体随 env 源翻转（WF-D-UN-3）")
    void runtimeDefaults_coordinatorSupplierWinsOverStaticSlot() {
        // R-A12 核心：静态 prompt 链（runtimeDefaults → ForkSubagent.isForkSubagentEnabled）的
        // coordinator 判定必须与 SubagentTool 内部 fork gate 同源（动态 CoordinatorMode bean / env 真源）。
        // 场景 1：env coordinator=true（supplier 返回 true）但 config 静态槽 coordinator=false
        //   → fork 必须关闭（:318 不注入）→ 非 fork 变体 + explore-plan 恢复 —— 与 SubagentTool 内部一致。
        ForkSubagent.syncRuntimeGate(true, false, false); // config 静态槽 coordinator=false
        ForkSubagent.setCoordinatorModeSupplier(() -> true); // env 真源 coordinator=true
        try {
            String guidance = SessionGuidanceSection.build(
                Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(),
                SessionGuidanceSection.SessionGuidanceFlags.runtimeDefaults());
            assertThat(guidance).as("env coordinator=true 覆盖 config 静态槽 → :319 非 fork 变体注入")
                .contains("specialized agents");
            assertThat(guidance).as("fork 关闭 → explore-plan 恢复注入（:374-381 !isForkSubagentEnabled）")
                .contains("subagent_type=Explore");
            assertThat(guidance).as("fork 变体文本必须不出现").doesNotContain("without a subagent_type creates a fork");
        } finally {
            ForkSubagent.setCoordinatorModeSupplier(null); // 复位 → 回退 config 静态槽
        }
        // 场景 2：复位 supplier 后回退 config 静态槽 coordinator=false → fork 恢复开启
        String guidance = SessionGuidanceSection.build(
            Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(),
            SessionGuidanceSection.SessionGuidanceFlags.runtimeDefaults());
        assertThat(guidance).as("supplier null → 回退 config 静态槽（coordinator=false）→ :318 fork 变体注入")
            .contains("without a subagent_type creates a fork");
        assertThat(guidance).as("fork 恢复开启 → explore-plan 停注").doesNotContain("subagent_type=Explore");

        // 场景 3：反向 —— config 静态槽 coordinator=true 但 env 真源 coordinator=false
        //   → supplier 仍优先（env 源为单一真源），fork 开启。
        ForkSubagent.syncRuntimeGate(true, true, false); // config 静态槽 coordinator=true
        ForkSubagent.setCoordinatorModeSupplier(() -> false); // env 真源 coordinator=false
        try {
            String guidanceEnvOff = SessionGuidanceSection.build(
                Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(),
                SessionGuidanceSection.SessionGuidanceFlags.runtimeDefaults());
            assertThat(guidanceEnvOff).as("env 真源 coordinator=false 覆盖 config 静态槽 → :318 fork 变体注入")
                .contains("without a subagent_type creates a fork");
        } finally {
            ForkSubagent.setCoordinatorModeSupplier(null);
        }
    }

    @Test
    @DisplayName("explore-plan：hasAgentTool + 默认可达 → 2 子弹；flag 关 → 不注入（:374-381）")
    void explorePlan_gatedOnAgentAndFlag() {
        SessionGuidanceSection.SessionGuidanceFlags exploreOff =
            new SessionGuidanceSection.SessionGuidanceFlags(false, false, false, false, false);

        String with = SessionGuidanceSection.build(
            Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(), DEFAULTS);
        String gatedOff = SessionGuidanceSection.build(
            Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(), exploreOff);

        assertThat(with).as("探索直接搜索子弹").contains("For simple, directed codebase searches (e.g. for a specific file/class/function) use the "
            + ToolNameConstants.GLOB_TOOL_NAME + " or " + ToolNameConstants.GREP_TOOL_NAME + " directly.");
        assertThat(with).as("Explore agent 子弹（EXPLORE_AGENT.agentType='Explore'，MIN_QUERIES=3）")
            .contains("use the " + AgentToolConstants.AGENT_TOOL_NAME + " tool with subagent_type=Explore")
            .contains("more than 3 queries");
        assertThat(gatedOff).as("explore flag 关 → 不注入").doesNotContain("subagent_type=Explore");
    }

    @Test
    @DisplayName("skill 子弹：skillToolCommands 非空 且 enabledTools 含 Skill（:357-358 + :382-384）")
    void skill_bullet_gatedOnCommandsAndTool() {
        String with = SessionGuidanceSection.build(
            Set.of(ToolNameConstants.SKILL_TOOL_NAME), List.of("/commit"), DEFAULTS);
        String noTool = SessionGuidanceSection.build(
            Set.of(), List.of("/commit"), DEFAULTS);
        String noCommands = SessionGuidanceSection.build(
            Set.of(ToolNameConstants.SKILL_TOOL_NAME), List.of(), DEFAULTS);

        assertThat(with).contains("/<skill-name> (e.g., /commit) is shorthand for users to invoke a user-invocable skill.");
        assertThat(noTool).as("缺 Skill 工具 → 不注入").doesNotContain("/<skill-name>");
        assertThat(noCommands).as("skillToolCommands 空 → 不注入（hasSkills=false）").doesNotContain("/<skill-name>");
    }

    @Test
    @DisplayName("discover-skills 子弹：feature-gated 默认 false；flag 开且 hasSkills → 注入（:385-389）")
    void discoverSkills_featureGated() {
        SessionGuidanceSection.SessionGuidanceFlags discoverOn =
            new SessionGuidanceSection.SessionGuidanceFlags(false, true, false, true, false);
        Set<String> tools = Set.of(ToolNameConstants.SKILL_TOOL_NAME, "discover_skills");

        String on = SessionGuidanceSection.build(tools, List.of("/commit"), discoverOn);
        String off = SessionGuidanceSection.build(tools, List.of("/commit"), DEFAULTS);

        assertThat(on).contains("Relevant skills are automatically surfaced each turn as \"Skills relevant to your task:\" reminders.");
        assertThat(off).as("默认 discoverSkillsEnabled=false（CC DISCOVER_SKILLS_TOOL_NAME feature-gated）")
            .doesNotContain("Skills relevant to your task:");
    }

    @Test
    @DisplayName("verification 子弹：3P A/B 恒 false 默认；flag 开且 hasAgentTool → 注入（:390-395）")
    void verification_featureGated() {
        SessionGuidanceSection.SessionGuidanceFlags verificationOn =
            new SessionGuidanceSection.SessionGuidanceFlags(false, true, false, false, true);
        Set<String> tools = Set.of(AgentToolConstants.AGENT_TOOL_NAME);

        String on = SessionGuidanceSection.build(tools, List.of(), verificationOn);
        String off = SessionGuidanceSection.build(tools, List.of(), DEFAULTS);

        assertThat(on).contains("The contract: when non-trivial implementation happens on your turn");
        assertThat(on).contains("subagent_type=\"verification\"");
        assertThat(off).as("默认 verificationAgentEnabled=false（3P 默认，tengu_hive_evidence false）")
            .doesNotContain("The contract: when non-trivial implementation");
    }

    @Test
    @DisplayName("非交互时 '!' 跳过 → 该子弹不注入（CC:368-369 getIsNonInteractiveSession 门控）")
    void bang_skipped_whenNonInteractive() {
        SessionGuidanceSection.SessionGuidanceFlags nonInteractive =
            new SessionGuidanceSection.SessionGuidanceFlags(true, true, false, false, false);

        String guidance = SessionGuidanceSection.build(
            Set.of(AgentToolConstants.AGENT_TOOL_NAME), List.of(), nonInteractive);

        assertThat(guidance).doesNotContain("suggest they type `! <command>`");
        assertThat(guidance).as("其余子弹仍按各自门控注入（agent-tool 有）").contains("specialized agents");
    }
}
