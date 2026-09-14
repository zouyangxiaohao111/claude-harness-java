package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2-CTX · {@link SubagentExecutor#buildSubagentAgentContext} 的<b>字段映射</b>测试
 * （CC AgentTool.tsx:719-727 object literal → {@link AgentContext.SubagentContext}）。
 *
 * <p><b>本测试覆盖</b>（只此一项）：agentId / subagentName / isBuiltIn / invocationKind
 * spawn|resume / parentSessionId null / invokingRequestId null 的逐字段映射。
 *
 * <p><b>历史（S1-T7-2 之后的现状）</b>：本类原先还覆盖「归因组合语义」——即 CC 每次 spawn 把
 * agent 执行包进 {@code runWithAgentContext(context, ...)}（CC AgentTool.tsx:733 async /
 * :785 sync / :911 background）后，query loop 内可经 CC 侧的 {@code getSubagentLogName()} /
 * {@code getAgentContext()}（CC agentContext.ts:141-151 / :100-102）读回归因上下文。
 * <b>Java 侧该 ambient 载体（ThreadLocal + 同名读取方法 + 线程包裹）已于 S1-T7-2 整体删除，
 * 两条相应用例随之删除</b>；等价语义现由「显式载体」两处承载：
 * <ul>
 *   <li>hook 侧 {@code subagent_name}：{@code SessionFileAccessHooks.subagentProps(ToolUseContext)}
 *       （读 TUC 的 {@code subagentName/isBuiltIn}，含 CC agentContext.ts:145-151 的
 *       {@code isBuiltIn ? name : 'user-defined'} 隐私映射），由
 *       {@code SubagentNameExplicitCarrierTest} 在真 {@code HOOK_EXECUTOR} 线程上守。</li>
 *   <li>provider 侧 {@code invokingRequestId}：{@code AgentContext.attachInvokingRequestEdge(Map, AgentContext)}
 *       的显式实参版，由 {@code InvokingRequestIdExplicitCarrierTest} 守。</li>
 * </ul>
 *
 * <p><b>RED 依据</b>：本测试引用的 {@code SubagentExecutor.buildSubagentAgentContext} 在 R2-CTX
 * 实施前不存在（编译即失败）；映射表达式回退（如 isBuiltIn 恒 true / invocationKind 恒 "spawn"）
 * ⇒ 对应用例红。
 */
@DisplayName("[R2-CTX] buildSubagentAgentContext 字段映射（CC AgentTool.tsx:719-727 object literal → SubagentContext）")
class SubagentAgentContextWiringTest {

    // ────────────────────────────────────────────────────────────────────────
    // 字段映射（CC AgentTool.tsx:719-727 object literal → SubagentContext）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("spawn: buildSubagentAgentContext 映射 agentId/subagentName/isBuiltIn/parentSessionId/invokingRequestId")
    void spawn_shouldMapAllFields_aligningCcObjectLiteral() {
        // WHY: CC AgentTool.tsx:719-727 asyncAgentContext = { agentId, parentSessionId: getParentSessionId(),
        //   agentType: 'subagent', subagentName: selectedAgent.agentType, isBuiltIn: isBuiltInAgent(...),
        //   invokingRequestId: assistantMessage?.requestId, invocationKind: 'spawn', invocationEmitted: false }.
        //   R3-WF-F IMP-SUB-12 返工（D18/B2 拍板，open-decisions §F1/F4）：agentId 输出 a+16hex，非 UUID 字符串。
        //   CC 真源：uuid.ts:24-27 createAgentId() → `a{label-}{16 hex}`；agentContext.ts:34 agentId: string；
        //   AgentTool.tsx:580 earlyAgentId = createAgentId() + :716 asyncAgentContext.agentId = asyncAgentId。
        //   注意 agentContext.ts:33 注释旧称 "UUID" 为过时注释（规则九：不信 CC 注释，看实际源码）。
        //   Java 侧 ToolUseContext.agentId 为 UUID（S-12 Java record 基础设施），buildSubagentAgentContext
        //   收 UUID 经 AgentContext.unpackAgentId（a+16hex 可逆编码桥）还原输出。
        UUID agentId = UUID.randomUUID();

        AgentContext.SubagentContext ctx = SubagentExecutor.buildSubagentAgentContext(
            agentId, "Explore", true, null, "spawn");

        assertThat(ctx.agentId())
            .as("agentId 必须 = 子 agent a+16hex（CC uuid.ts:24-27 createAgentId() 产物；agentContext.ts:34）")
            .isEqualTo(AgentContext.unpackAgentId(agentId));
        assertThat(ctx.agentId())
            .as("agentId 必须匹配 CC 校验正则 /^a(?:.+-)?[0-9a-f]{16}$/（types/ids.ts:35 AGENT_ID_PATTERN，"
                + "toAgentId :42；非 UUID 8-4-4-4-12）")
            .matches("^a[0-9a-f]{16}$");
        assertThat(ctx.agentType())
            .as("agentType 必须恒为 'subagent'（CC :721 literal）")
            .isEqualTo("subagent");
        assertThat(ctx.subagentName())
            .as("subagentName 必须 = selectedAgent.agentType（CC :722）")
            .isEqualTo("Explore");
        assertThat(ctx.isBuiltIn())
            .as("isBuiltIn 必须 = isBuiltInAgent(selectedAgent)（CC :723）")
            .isTrue();
        assertThat(ctx.parentSessionId())
            .as("parentSessionId 必须 null（main REPL subagent，CC :720 getParentSessionId() → undefined）")
            .isNull();
        assertThat(ctx.invokingRequestId())
            .as("invokingRequestId 未透传时 null（CC :726 assistantMessage?.requestId 可为 undefined）")
            .isNull();
        assertThat(ctx.invocationKind())
            .as("invocationKind 必须 = 'spawn'（CC :725）")
            .isEqualTo("spawn");
        assertThat(ctx.invocationEmitted().get())
            .as("invocationEmitted 初始必须 false（CC :727，每次 spawn reset）")
            .isFalse();
    }

    @Test
    @DisplayName("[RF-1] spawn: buildSubagentAgentContext 透传 invokingRequestId（父 assistantMessage.requestId → CC :726）")
    void spawn_shouldMapInvokingRequestId_fromParentAssistantMessage() {
        // WHY: CC AgentTool.tsx:723/:778 invokingRequestId: assistantMessage?.requestId —— 子 agent 的
        //   第一个 terminal API event 需带 invokingRequestId + invocationKind 标记 spawn/resume 边界
        //   （agentContext.ts:159-161 sparse-edge 语义）。旧实现 buildSubagentAgentContext 硬编码 null →
        //   归因缺 invokingRequestId（RF-1 修复）。回退 → 测试红。
        String parentRequestId = "req-abc-123";

        AgentContext.SubagentContext ctx = SubagentExecutor.buildSubagentAgentContext(
            UUID.randomUUID(), "Explore", true, parentRequestId, "spawn");

        assertThat(ctx.invokingRequestId())
            .as("invokingRequestId 必须透传父 assistantMessage.requestId（CC AgentTool.tsx:723/:778）")
            .isEqualTo(parentRequestId);
    }

    @Test
    @DisplayName("resume: invocationKind 必须 = 'resume'（CC resumeAgent.ts:218-224 续跑边界）")
    void resume_shouldSetInvocationKindResume() {
        // WHY: CC resumeAgent.ts:230 void runWithAgentContext(asyncAgentContext, ...) 的 asyncAgentContext
        //   invocationKind: 'resume'（resumeAgent.ts:218-224）—— 续跑子 agent 必须与初始 spawn 区分，
        //   否则 analytics sparse-edge 归因把 resume 当成新 spawn。
        UUID agentId = UUID.randomUUID();

        AgentContext.SubagentContext ctx = SubagentExecutor.buildSubagentAgentContext(
            agentId, "Explore", true, null, "resume");

        assertThat(ctx.invocationKind())
            .as("resume 边界 invocationKind 必须 = 'resume'（CC resumeAgent.ts:218-224）")
            .isEqualTo("resume");
    }

    @Test
    @DisplayName("自定义 agent: isBuiltIn=false（CC :723 isBuiltInAgent；隐私映射的前置）")
    void customAgent_shouldMapIsBuiltInFalse() {
        // WHY: CC agentContext.ts:145-150 的隐私映射（非内置 agent 恒映射为 'user-defined'，
        //   自定义名不泄入 analytics）以 <b>isBuiltIn=false</b> 为前置（CC :723 isBuiltInAgent）。
        //   该映射本身现由 {@code SessionFileAccessHooks.subagentProps} 承载、由
        //   {@code SubagentNameExplicitCarrierTest} 守；本用例只钉「映射的输入字段被正确派生」。
        AgentContext.SubagentContext ctx = SubagentExecutor.buildSubagentAgentContext(
            UUID.randomUUID(), "my-custom-agent", false, null, "spawn");

        assertThat(ctx.isBuiltIn())
            .as("自定义 agent isBuiltIn 必须 false（CC :723）")
            .isFalse();
    }

    // ────────────────────────────────────────────────────────────────────────
    // [S1-T7-2] 原「归因组合语义」小节（ambient 载体 + 已删的 Java 侧同名读取方法）
    // 随载体整体删除而移除；等价语义的守卫落点见类 javadoc 的「历史」段。
    // ────────────────────────────────────────────────────────────────────────

}
