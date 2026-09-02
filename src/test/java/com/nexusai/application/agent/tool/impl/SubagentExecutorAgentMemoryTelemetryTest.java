package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * [IMP-CM-18] 子代理记忆遥测测试 · 对齐 CC AgentTool.tsx:524-530
 * ({@code if (selectedAgent.memory) logEvent('tengu_agent_memory_loaded',
 *  { scope: selectedAgent.memory, source: 'subagent' })})。
 *
 * <p>WHY 本测试验证意图（而非仅行为）：D07 补 subagent 发射点——CC 在子代理带 memory
 * scope 且走非 fork 路径时发射事件，Java 端此前 grep {@code tengu_agent_memory_loaded}
 * 0 命中（全局报告 §4 #28）。若事件在 (a) fork 路径、(b) 无 memory 子代理下也发射，
 * 或 (c) 属性与 CC 不一致，则遥测数据失真（subagent 记忆加载无法归因）。
 */
class SubagentExecutorAgentMemoryTelemetryTest {

    private static AgentDefinition agentWithMemory(String type, String scope) {
        return AgentDefinition.CustomAgentDefinition.builder(type, "desc", "custom", "prompt")
            .memory(scope)
            .build();
    }

    private static AgentDefinition agentWithoutMemory(String type) {
        return AgentDefinition.CustomAgentDefinition.builder(type, "desc", "custom", "prompt")
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 门控：shouldEmitAgentMemoryLoaded（对齐 CC if(selectedAgent.memory) + 非 fork else 分支）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("门控: 非 fork + memory 有值 → 发射（CC AgentTool.tsx:526 else 分支）")
    void gate_emits_when_non_fork_with_memory() {
        // WHY: CC 事件位于非 fork 分支（AgentTool.tsx:513-531），scope 判定=selectedAgent.memory truthy。
        //   三 scope（user/project/local）任一存在都必须发射。
        assertThat(SubagentExecutor.shouldEmitAgentMemoryLoaded(false, agentWithMemory("m", "project")))
            .as("非 fork + memory=project 必须发射").isTrue();
        assertThat(SubagentExecutor.shouldEmitAgentMemoryLoaded(false, agentWithMemory("m", "user")))
            .as("非 fork + memory=user 必须发射").isTrue();
        assertThat(SubagentExecutor.shouldEmitAgentMemoryLoaded(false, agentWithMemory("m", "local")))
            .as("非 fork + memory=local 必须发射").isTrue();
    }

    @Test
    @DisplayName("门控: fork 路径不发射（CC AgentTool.tsx:482-513 fork 分支无本事件）")
    void gate_does_not_emit_on_fork_path() {
        // WHY: fork 子代理继承父 system prompt（forkParentSystemPrompt），CC fork 分支不发射
        //   本事件；即使子代理定义带 memory，fork 路径也必须静默（否则事件源/scope 归因错误）。
        assertThat(SubagentExecutor.shouldEmitAgentMemoryLoaded(true, agentWithMemory("m", "project")))
            .as("fork 路径即使带 memory 也不发射").isFalse();
    }

    @Test
    @DisplayName("门控: 无 memory / 空 memory / null 定义 → 不发射")
    void gate_does_not_emit_without_memory() {
        // WHY: CC 判定 selectedAgent.memory truthy —— 无 memory 字段（loadAgentsDir.ts:505 省略
        //   memory 属性）、空串（JS falsy）、null 定义都必须不发射。
        assertThat(SubagentExecutor.shouldEmitAgentMemoryLoaded(false, agentWithoutMemory("m")))
            .as("无 memory 字段不发射").isFalse();
        assertThat(SubagentExecutor.shouldEmitAgentMemoryLoaded(false, agentWithMemory("m", "  ")))
            .as("空白 memory 不发射（JS 空串 falsy）").isFalse();
        assertThat(SubagentExecutor.shouldEmitAgentMemoryLoaded(false, null))
            .as("null 定义不发射").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 发射：emitAgentMemoryLoaded（双发射 recordEvent + logOTelEvent · 属性对齐 CC）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("发射: memory 子代理 → tengu_agent_memory_loaded(scope=memory值, source='subagent') 双发射")
    void emission_sends_scope_and_subagent_source() {
        // WHY: CC 事件属性 = { scope: selectedAgent.memory, source: 'subagent' }（AgentTool.tsx:527-529）。
        //   agent_type 恒不发射（条件 "external"==='ant' 恒 false，AgentTool.tsx:525-527）。
        Telemetry tel = spy(new Telemetry());
        SubagentExecutor executor = newExecutor();
        executor.setTelemetry(tel);

        executor.emitAgentMemoryLoaded(agentWithMemory("research", "project"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tel).recordEvent(eq("tengu_agent_memory_loaded"), captor.capture());
        verify(tel).logOTelEvent(eq("tengu_agent_memory_loaded"), captor.capture());
        Map<String, Object> attrs = captor.getAllValues().get(0);
        assertThat(attrs)
            .as("事件属性必须与 CC 对齐")
            .containsEntry("scope", "project")
            .containsEntry("source", "subagent");
    }

    @Test
    @DisplayName("发射: 无 memory 子代理 → 不发射任何 tengu_agent_memory_loaded")
    void emission_skips_without_memory() {
        // WHY: CC 门控 if(selectedAgent.memory) —— 无 memory 字段必须零发射，否则遥测
        //   counts 虚高、无法区分「子代理带记忆」与「子代理不带记忆」。
        Telemetry tel = spy(new Telemetry());
        SubagentExecutor executor = newExecutor();
        executor.setTelemetry(tel);

        executor.emitAgentMemoryLoaded(agentWithoutMemory("m"));

        verify(tel, never()).recordEvent(eq("tengu_agent_memory_loaded"), org.mockito.Mockito.any());
        verify(tel, never()).logOTelEvent(eq("tengu_agent_memory_loaded"), org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("发射: telemetry 未注入（null）→ 静默跳过不抛异常（零行为变化）")
    void emission_null_telemetry_is_noop() {
        // WHY: MemoryPromptBuilder.emitMemdirLoaded :630-637 惯例 —— telemetry=null 时
        //   （测试直构/未接线路径）必须零行为变化，不能 NPE。
        SubagentExecutor executor = newExecutor();

        assertThatCode(() -> executor.emitAgentMemoryLoaded(agentWithMemory("m", "local")))
            .as("telemetry=null 不抛异常").doesNotThrowAnyException();
        assertThatCode(() -> executor.emitAgentMemoryLoaded(null))
            .as("null 定义不抛异常").doesNotThrowAnyException();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 生产接线：buildAgentSystemPrompt（IMP-F2-3 · OPD-CM5-F-18/19/22）
    //   spawn 点（非 fork buildAgentSystemPrompt）接真实 Telemetry，删 AnalyticsTracker stub（DC-V5-09）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("生产接线: 非 fork buildAgentSystemPrompt → 经真实 Telemetry 发射（即使 memory prompt 未注入）")
    void buildAgentSystemPrompt_emitsViaRealTelemetry_whenMemoryFieldPresent() throws Exception {
        // WHY (IMP-F2-3): 修复前生产发射走 AnalyticsTracker stub（0 下游消费，tengu_agent_memory_loaded
        //   无真实事件输出），且门控绑定 memoryPrompt 非空（auto-memory 关闭 → memoryPrompt=null → 不发射）。
        //   CC 门控 = selectedAgent.memory truthy（AgentTool.tsx:523），不 gate isAutoMemoryEnabled；
        //   发射 = 真实 Telemetry（recordEvent + logOTelEvent）。本测试锁定：agent 定义带 memory 字段
        //   （即使 agentMemoryDirectory 未注入 → prompt 未实际追加）也必须经 telemetry 发射 scope+source，
        //   且 stub 通道不再接收该事件。
        Telemetry tel = spy(new Telemetry());
        AnalyticsTracker stub = spy(new AnalyticsTracker());
        SubagentExecutor executor = newExecutor();
        executor.setTelemetry(tel);
        executor.setAnalyticsTracker(stub);

        invokeBuildAgentSystemPrompt(executor, false, agentWithMemory("research", "project"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tel).recordEvent(eq("tengu_agent_memory_loaded"), captor.capture());
        verify(tel).logOTelEvent(eq("tengu_agent_memory_loaded"), captor.capture());
        Map<String, Object> attrs = captor.getAllValues().get(0);
        assertThat(attrs)
            .as("事件属性必须与 CC 对齐：scope + source，无 agent_type")
            .containsEntry("scope", "project")
            .containsEntry("source", "subagent")
            .doesNotContainKey("agent_type");
        verify(stub, never()).logEvent(eq("tengu_agent_memory_loaded"), org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("生产接线: fork 路径 buildAgentSystemPrompt 不发射（CC fork 分支无事件）")
    void buildAgentSystemPrompt_forkPath_doesNotEmit() throws Exception {
        // WHY: 对齐 CC AgentTool.tsx:482-513 fork 分支——fork 子代理继承父 prompt，不发射本事件。
        //   Java fork 但父 prompt 缺失时会回退 buildAgentSystemPrompt，此时 isForkPath=true 必须静默，
        //   否则事件源/scope 归因错误（shouldEmitAgentMemoryLoaded(isForkPath=true) → false）。
        Telemetry tel = spy(new Telemetry());
        SubagentExecutor executor = newExecutor();
        executor.setTelemetry(tel);

        invokeBuildAgentSystemPrompt(executor, true, agentWithMemory("research", "project"));

        verify(tel, never()).recordEvent(eq("tengu_agent_memory_loaded"), org.mockito.Mockito.any());
        verify(tel, never()).logOTelEvent(eq("tengu_agent_memory_loaded"), org.mockito.Mockito.any());
    }

    private static void invokeBuildAgentSystemPrompt(SubagentExecutor executor, boolean isForkPath,
            AgentDefinition agent) throws Exception {
        Method m = SubagentExecutor.class.getDeclaredMethod("buildAgentSystemPrompt",
            boolean.class, AgentDefinition.class, List.class, String.class, List.class, String.class);
        m.setAccessible(true);
        m.invoke(executor, isForkPath, agent, List.of(), "claude-sonnet-4-6", List.of(), null);
    }

    private SubagentExecutor newExecutor() {
        // 无 Spring 依赖的最小构造（对齐 SubagentExecutorAdditionalAgentsTest:119-125 既有构造模式）
        return new SubagentExecutor(
            new com.nexusai.application.agent.tool.ToolRegistry(),
            null, null, null, null,
            "gpt-4", "system", null);
    }
}
