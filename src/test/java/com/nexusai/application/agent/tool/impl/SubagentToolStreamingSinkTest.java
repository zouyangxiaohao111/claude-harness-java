package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SUB-28 A5 返工 R1] buildSyncStreamingSink + D18 初始发射 聚焦测试
 * 覆盖 CC AgentTool.tsx sync 路径 onProgress 上报的 D19/D20/D21/D22 语义。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>: A5 是 WF1-01 D18-D22 残余的收口 —— AgentTool 入口把
 * execute() 固定 sink=null 改为 executeStreaming + buildSyncStreamingSink，父 caller 才能逐消息
 * 观测子 Agent 产出。若删 sink 接线，本测试全部红（R1 前 A5 核心逻辑零直接测试，SubagentToolTest
 * 为 ODF-C3 JSON producer，不覆盖 A5）。
 *
 * <p>CC 真源:
 * <ul>
 *   <li><b>D18</b> 初始 agent_progress — AgentTool.tsx:791-806 {@code onProgress({toolUseID:
 *       'agent_'+id, data:{message, type:'agent_progress', prompt, agentId}})}</li>
 *   <li><b>D20</b> bash/powershell_progress 转发 — :1084-1089 {@code onProgress({toolUseID:
 *       message.toolUseID, data: message.data})}</li>
 *   <li><b>D21</b> setResponseLength 累加 — :1097-1102 {@code setResponseLength(len => len + contentLength)}</li>
 *   <li><b>D19/D22</b> tool_use/tool_result → agent_progress — :1103-1125</li>
 * </ul>
 */
@DisplayName("IMP-SUB-28 A5 · buildSyncStreamingSink D19/D20/D21/D22 + D18 初始发射")
class SubagentToolStreamingSinkTest {

    private static final AgentUsage USAGE = AgentUsage.EMPTY;

    private static SubagentMessage.AssistantMessage assistant(String content) {
        return new SubagentMessage.AssistantMessage(content, USAGE, false, null);
    }

    private static SubagentMessage.AssistantMessage toolContentAssistant(String agentId) {
        return new SubagentMessage.AssistantMessage("tool_use 块", USAGE, true, agentId);
    }

    @Test
    @DisplayName("D18: buildInitialAgentProgress 初始 agent_progress 形状（CC AgentTool.tsx:791-806）")
    void buildInitialAgentProgress_producesInitialAgentProgress() {
        // WHY: sync 路径首帧 agent_progress 承载任务 prompt 元数据（CC :800 data.prompt），父 Agent 凭此
        //   识别子 Agent 已启动及任务内容；toolUseID='agent_'+父消息 id（CC :797）。若形状偏离（如 toolUseID
        //   前缀丢失），父 Agent 无法关联到父消息。agentId=null 与 CC syncAgentId 的偏差已披露（concerns）。
        Tool.ToolProgress p = SubagentTool.buildInitialAgentProgress("msg-1", "帮我分析代码");

        assertThat(p.toolUseId()).isEqualTo("agent_msg-1");
        assertThat(p.data()).isInstanceOf(SubagentTool.AgentToolProgressData.class);
        SubagentTool.AgentToolProgressData data = (SubagentTool.AgentToolProgressData) p.data();
        assertThat(data.type()).as("CC :1116 type='agent_progress'").isEqualTo("agent_progress");
        assertThat(data.prompt()).as("CC :800 data.prompt=任务提示词").isEqualTo("帮我分析代码");
        assertThat(data.agentId()).as("Java 无 syncAgentId 上下文 → null（披露偏差）").isNull();
        assertThat(data.message()).as("Java 以 prompt 串近似 message 载体（披露偏差）").isEqualTo("帮我分析代码");
    }

    @Test
    @DisplayName("D21: AssistantMessage 文本长度累加 setResponseLength 运行总数（CC AgentTool.tsx:1097-1102）")
    void sink_assistantMessage_accumulatesResponseLength() {
        // WHY: CC 对每条 assistant 消息 content 长度做 len => len + contentLength 累加，父 loop 读
        //   setResponseLength 了解子 Agent 已产出多少字符（无 tool_use 时只有文本）。Java 端由
        //   AtomicLong 承担运行总数，逐消息 accept(String.valueOf(total))（对齐 CompactConversation:484-485）。
        List<Tool.ToolProgress> emitted = new ArrayList<>();
        AtomicLong lastLen = new AtomicLong(-1);
        java.util.function.Consumer<String> responseLength = v -> lastLen.set(Long.parseLong(v));

        java.util.function.Consumer<SubagentMessage> sink = SubagentTool.buildSyncStreamingSink(
            emitted::add, "msg-1", "prompt", responseLength);

        sink.accept(assistant("hello"));
        sink.accept(assistant(" world"));

        assertThat(lastLen.get()).as("5 + 6 累加 = 11（运行总数，非单条）").isEqualTo(11L);
        assertThat(emitted).as("assistant 纯文本不触发 onProgress 发射").isEmpty();
    }

    @Test
    @DisplayName("D20: ProgressMessage → 父 onProgress ToolProgress（CC AgentTool.tsx:1084-1089）")
    void sink_progressMessage_forwardsToParent() {
        // WHY: 子 Agent 工具进度（bash/powershell/mcp 等）必须转发父 onProgress，否则父 Agent 无法实时
        //   观测子 Agent 工具运行状态。CC :1085 toolUseID=message.toolUseID，Java 端 ProgressMessage
        //   不保留原始 toolUseID → 以 'agent_'+parentMsgId 近似（披露偏差）。
        List<Tool.ToolProgress> emitted = new ArrayList<>();

        java.util.function.Consumer<SubagentMessage> sink = SubagentTool.buildSyncStreamingSink(
            emitted::add, "msg-1", "prompt", null);

        sink.accept(new SubagentMessage.ProgressMessage("bash_progress: running"));

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).toolUseId()).isEqualTo("agent_msg-1");
        assertThat(emitted.get(0).data()).as("ProgressMessage 描述原样转发").isEqualTo("bash_progress: running");
    }

    @Test
    @DisplayName("D19/D22: toolContent AssistantMessage → agent_progress（CC AgentTool.tsx:1103-1125）")
    void sink_toolContentAssistant_forwardsAgentProgress() {
        // WHY: tool_use/tool_result 块必须转发 agent_progress 且 data.message=原消息（CC :1115），父 Agent
        //   凭此观测子 Agent 的工具调用/结果。agentId 用 message.agentId()（fork 子 agent id 载体透传）。
        List<Tool.ToolProgress> emitted = new ArrayList<>();

        java.util.function.Consumer<SubagentMessage> sink = SubagentTool.buildSyncStreamingSink(
            emitted::add, "msg-1", "prompt", null);

        SubagentMessage.AssistantMessage toolMsg = toolContentAssistant("agent-fork-9");
        sink.accept(toolMsg);

        assertThat(emitted).hasSize(1);
        Tool.ToolProgress p = emitted.get(0);
        assertThat(p.toolUseId()).isEqualTo("agent_msg-1");
        assertThat(p.data()).isInstanceOf(SubagentTool.AgentToolProgressData.class);
        SubagentTool.AgentToolProgressData data = (SubagentTool.AgentToolProgressData) p.data();
        assertThat(data.type()).isEqualTo("agent_progress");
        assertThat(data.prompt()).as("Java 每条都带 prompt（CC 首条后置 ''，披露偏差）").isEqualTo("prompt");
        assertThat(data.agentId()).as("agentId 透传 message.agentId（CC :1120）").isEqualTo("agent-fork-9");
        assertThat(data.message()).as("CC :1115 data.message=原消息").isSameAs(toolMsg);
    }

    @Test
    @DisplayName("D19/D22: toolContent UserMessage（tool_result）同样转发 agent_progress")
    void sink_toolContentUser_forwardsAgentProgress() {
        List<Tool.ToolProgress> emitted = new ArrayList<>();

        java.util.function.Consumer<SubagentMessage> sink = SubagentTool.buildSyncStreamingSink(
            emitted::add, "msg-1", "prompt", null);

        SubagentMessage.UserMessage toolResult = new SubagentMessage.UserMessage("tool_result 内容", true, "agent-x");
        sink.accept(toolResult);

        assertThat(emitted).hasSize(1);
        SubagentTool.AgentToolProgressData data =
            (SubagentTool.AgentToolProgressData) emitted.get(0).data();
        assertThat(data.type()).isEqualTo("agent_progress");
        assertThat(data.agentId()).isEqualTo("agent-x");
        assertThat(data.message()).isSameAs(toolResult);
    }

    @Test
    @DisplayName("onProgress==null → buildSyncStreamingSink 返回 null（非流式回落决策 2）")
    void buildSyncStreamingSink_nullOnProgress_returnsNull() {
        // WHY: onProgress==null（非流式 caller 直接 execute）时 sink 无意义，返回 null 让调用方回落
        //   executor.execute（决策 2 非流式保留）。若返回非 null sink 消费端 NPE。
        assertThat(SubagentTool.buildSyncStreamingSink(null, "msg-1", "prompt", null)).isNull();
    }
}
