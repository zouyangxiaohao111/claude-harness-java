package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2-CTX · subagent spawn 包裹 runWithAgentContext（analytics 归因）RED-GREEN 双证测试.
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：CC 每次 spawn 都把整个 agent 执行包进
 * {@code runWithAgentContext(context, ...)}（AgentTool.tsx:733 async / :785 sync / :911 background），
 * 使 query loop 内事件能经 {@code getSubagentLogName()}（→ {@code subagent_name}）与
 * {@code getAgentContext()}（→ parent_agent_id）正确归因到子 agent。Java 现状（S-13 / DISC-SUB-03
 * EV-FK-015）：SubagentTool → SubagentExecutor 无包裹，query loop 内 {@code getSubagentLogName()}
 * 恒 null → {@code SessionFileAccessHooks.subagentProps()} 空 map → 事件缺 {@code subagent_name}。
 *
 * <p>本测试覆盖：
 * <ol>
 *   <li>{@link SubagentExecutor#buildSubagentAgentContext} 字段映射（CC AgentTool.tsx:719-727
 *       object literal → SubagentContext）：agentId / subagentName / isBuiltIn / invocationKind
 *       spawn|resume / parentSessionId null / invokingRequestId null</li>
 *   <li>归因组合语义：{@code runWithAgentContext(buildSubagentAgentContext(...))} 块内
 *       {@code getSubagentLogName()} 返回内置名（built-in）/ "user-defined"（自定义），块外 null（不泄漏）</li>
 * </ol>
 *
 * <p>RED 依据：本测试引用的 {@code SubagentExecutor.buildSubagentAgentContext} 在 R2-CTX 实施前不存在
 * （编译即失败）；executeStreaming Step 20 未包裹 runWithAgentContext → 归因组合语义（subagent_name）
 * 无从产生。回退任一 → 测试红。
 */
@DisplayName("[R2-CTX] subagent spawn 包裹 runWithAgentContext（analytics 归因 subagent_name / parent_agent_id）")
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
    @DisplayName("自定义 agent: isBuiltIn=false → getSubagentLogName 返回 'user-defined'（CC agentContext.ts:150）")
    void customAgent_shouldMapIsBuiltInFalse() {
        // WHY: CC agentContext.ts:145-150 getSubagentLogName 对非内置 agent 恒返回 'user-defined'，
        //   自定义 agent 名不泄入 analytics。isBuiltIn=false 是这条路径的前置（CC :723 isBuiltInAgent）。
        AgentContext.SubagentContext ctx = SubagentExecutor.buildSubagentAgentContext(
            UUID.randomUUID(), "my-custom-agent", false, null, "spawn");

        assertThat(ctx.isBuiltIn())
            .as("自定义 agent isBuiltIn 必须 false（CC :723）")
            .isFalse();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 归因组合语义（runWithAgentContext + getSubagentLogName，对齐 CC agentContext.ts:141-151）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("归因组合: 内置 agent 块内 getSubagentLogName()=类型名，块外 null（不泄漏到调用方线程）")
    void builtInAgent_shouldExposeSubagentName_insideAndNullOutside() {
        // WHY: SessionFileAccessHooks.subagentProps() 在 query loop 内调 getSubagentLogName()，
        //   包裹后必须返回内置名 "Explore"（CC agentContext.ts:148-149 isBuiltIn → subagentName）。
        //   块外必须 null（runWithAgentContext finally restore/remove，S-15 ThreadLocal 不泄漏）。
        AgentContext.SubagentContext ctx = SubagentExecutor.buildSubagentAgentContext(
            UUID.randomUUID(), "Explore", true, null, "spawn");

        String nameInside = AgentContext.runWithAgentContext(ctx, AgentContext::getSubagentLogName);

        assertThat(nameInside)
            .as("内置 agent 块内 getSubagentLogName 必须 = 类型名（CC agentContext.ts:148）")
            .isEqualTo("Explore");
        assertThat(AgentContext.getAgentContext())
            .as("块外 getAgentContext 必须 null（runWithAgentContext finally remove，无 ThreadLocal 泄漏）")
            .isNull();
    }

    @Test
    @DisplayName("归因组合: 自定义 agent 块内 getSubagentLogName()='user-defined'（自定义名不泄入 analytics）")
    void customAgent_shouldExposeUserDefinedLogName() {
        // WHY: CC agentContext.ts:150 非内置 agent 恒返回 "user-defined"（自定义名安全边界）。
        AgentContext.SubagentContext ctx = SubagentExecutor.buildSubagentAgentContext(
            UUID.randomUUID(), "my-secret-custom-agent", false, null, "spawn");

        String nameInside = AgentContext.runWithAgentContext(ctx, AgentContext::getSubagentLogName);

        assertThat(nameInside)
            .as("自定义 agent getSubagentLogName 必须恒为 'user-defined'（CC agentContext.ts:150）")
            .isEqualTo("user-defined");
    }
}
