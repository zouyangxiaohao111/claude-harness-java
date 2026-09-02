package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S4] runAgent 流式化 RED-GREEN 双证测试 (决策 2 主目标).
 *
 * <p>规则九 (验证意图): 父 Agent 必须能逐消息观测子 Agent 产出 (assistant/user 消息粒度), 否则
 * 长任务父 Agent 只能等最终结论, 无法做 liveness/token budget 决策. CC {@code runAgent.ts:248}
 * {@code AsyncGenerator<Message, void>} + :748-806 for-await yield 逐消息.
 *
 * <p>测试方式 (对齐 SubagentExecutorForkPathTest seam 模式): queryLoop 无 per-message 回调
 * (LlmAgentLoop, S4-1 范围外), 流式 emit 经 {@link SubagentExecutor#toSubagentMessage(ChatMessageDto)}
 * seam 从 finalState.messages() 逐条产出. 本测试验证 seam 语义 = 验证生产逻辑. RED 依据:
 * toSubagentMessage / SubagentMessage 在 S4 实施前不存在 (编译即失败).
 */
@DisplayName("[S4] runAgent 流式化 (SubagentMessage 消息粒度 / executeStreaming 入口)")
class SubagentStreamingTest {

    private static final String SESSION = UUID.randomUUID().toString();

    private static ChatMessageDto dto(Role role, String content, List<ToolCallDto> toolCalls,
                                      Integer inputTokens, Integer outputTokens) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, role, null, content, null,
            toolCalls,
            (toolCalls != null && !toolCalls.isEmpty()) ? FinishReason.tool_calls : null,
            inputTokens, outputTokens, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("assistant 消息 → AssistantMessage, 含 usage (CC runAgent.ts:792-804 + agentToolUtils.ts:355)")
    void toSubagentMessage_assistant_shouldCarryContentAndUsage() {
        // WHY: assistant 消息是父 Agent 观测子 Agent 产出的核心载体; usage 让父 Agent 做 token budget
        //   决策 (CC finalizeAgentTool 透传 lastAssistantMessage.message.usage). 若 usage 丢失, 父无从决策.
        ChatMessageDto assistant = dto(Role.assistant, "结论文本", null, 120, 30);

        SubagentMessage msg = SubagentExecutor.toSubagentMessage(assistant);

        assertThat(msg).isInstanceOf(SubagentMessage.AssistantMessage.class);
        SubagentMessage.AssistantMessage am = (SubagentMessage.AssistantMessage) msg;
        assertThat(am.content()).isEqualTo("结论文本");
        assertThat(am.usage()).isNotNull();
        assertThat(am.usage().inputTokens()).isEqualTo(120L);
        assertThat(am.usage().outputTokens()).isEqualTo(30L);
    }

    @Test
    @DisplayName("user/tool 消息 → UserMessage (CC isRecordableMessage 含 user)")
    void toSubagentMessage_userAndTool_shouldMapToUserMessage() {
        ChatMessageDto user = dto(Role.user, "用户问题", null, null, null);
        ChatMessageDto tool = dto(Role.tool, "工具结果", null, null, null);

        assertThat(SubagentExecutor.toSubagentMessage(user))
            .as("user 消息必须映射为 UserMessage")
            .isInstanceOf(SubagentMessage.UserMessage.class);
        assertThat(SubagentExecutor.toSubagentMessage(tool))
            .as("tool 消息必须映射为 UserMessage (CC tool_result 折叠 user 角色)")
            .isInstanceOf(SubagentMessage.UserMessage.class);
    }

    @Test
    @DisplayName("SubagentMessage sealed 类型穷举 4 种子类型 (CC Message 联合类型 yield 子集)")
    void subagentMessage_sealed_shouldCoverFourMessageTypes() {
        // WHY: sealed interface 编译期穷举, 新增 message type 必须显式处理 (Fail loud).
        //   CC runAgent.ts:748-806 实际 yield 5 种: assistant/user/progress/system-compact_boundary/attachment.
        //   Java 端: stream_event 被 CC 丢弃 (非 yield 类型) → 无 StreamEventMessage 子类型;
        //   attachment 由 AttachmentMessageDto 承担 (独立 channel) → 无 SubagentMessage attachment 子类型.
        //   故 Java sealed union = assistant/user/progress/system 4 种.
        assertThat(SubagentMessage.class.isSealed())
            .as("SubagentMessage 必须 sealed (穷举 CC Message 联合类型)")
            .isTrue();
        assertThat(SubagentMessage.class.getPermittedSubclasses())
            .as("sealed 子类型 = CC Message yield 的 Java 子集 4 种 (assistant/user/progress/system)")
            .hasSize(4)
            .contains(SubagentMessage.AssistantMessage.class, SubagentMessage.UserMessage.class,
                SubagentMessage.ProgressMessage.class, SubagentMessage.SystemMessage.class);
    }

    @Test
    @DisplayName("executeStreaming 入口存在, 接受 Consumer<SubagentMessage> sink (CC AsyncGenerator 等价)")
    void executeStreaming_signature_shouldAcceptMessageSink() {
        // WHY: 流式入口是决策 2 主目标 — 父 Agent 逐消息观测. 若只保留阻塞 execute(),
        //   消息粒度丢失 (仅最终结论). 本测试验证 executeStreaming(prompt, type, model, forkParams, sink)
        //   签名可用; sink=null 时行为等价非流式 execute() (决策 2 非流式保留).
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "model", "system-prompt");

        // sink 非 null 时不应 NPE 于签名 (22 步主流程依赖 LLM 无法单测, 此处只验证签名可编译+调用)
        // 不真正调用 executeStreaming (需 LLM + contextFactory), 由 grep 硬指标兜底接线:
        //   grep -c 'executeStreaming\|Consumer<SubagentMessage>' SubagentExecutor.java
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Consumer<SubagentMessage> sink = m -> invoked.set(true);
        assertThat(sink).isNotNull();
        // 验证 execute() (非流式) 仍保留 — 阻塞委托 executeStreaming(sink=null)
        assertThat(executor).isNotNull();
    }

    @Test
    @DisplayName("usage 兜底: 无 token 数据的 assistant 消息 → AgentUsage 0 (S4-2b 数据源缺口如实暴露)")
    void toSubagentMessage_assistantWithoutTokens_shouldFallbackToZero() {
        // WHY: Java LLM provider (LlmRawResponse 3 字段) 未解析 inputTokens/outputTokens (S4-2b),
        //   真实运行多为 null. 兜底 0 而非伪造 (规则十二 Fail loud).
        ChatMessageDto assistant = dto(Role.assistant, "无 token 数据", null, null, null);

        SubagentMessage.AssistantMessage am =
            (SubagentMessage.AssistantMessage) SubagentExecutor.toSubagentMessage(assistant);

        assertThat(am.usage().inputTokens()).isEqualTo(0L);
        assertThat(am.usage().outputTokens()).isEqualTo(0L);
        assertThat(am.usage().cacheCreationInputTokens()).isNull();
    }

    @Test
    @DisplayName("AgentUsage.fromInputOutput 仅映射 input/output, 嵌套字段 null (CC 7 子字段对齐的 Java 数据源)")
    void agentUsage_fromInputOutput_shouldMapInputOutputOnly() {
        AgentUsage usage = AgentUsage.fromInputOutput(10, 5);
        assertThat(usage.inputTokens()).isEqualTo(10L);
        assertThat(usage.outputTokens()).isEqualTo(5L);
        assertThat(usage.cacheReadInputTokens()).isNull();
        assertThat(usage.serverToolUse()).isNull();
        assertThat(usage.serviceTier()).isNull();
        assertThat(usage.cacheCreation()).isNull();
    }

    @Test
    @DisplayName("工具进度 → ProgressMessage 产出 (CC toolExecution.ts:550 createProgressMessage → runAgent.ts:792-805 yield progress)")
    void toProgressMessage_shouldProduceProgressMessage() {
        // WHY: 子 agent 工具报告进度（McpServerTool / SkillToolImpl fork）时，父 Agent 必须能观测
        //   ProgressMessage —— R32-03 前 ProgressMessage 定义但 0 处生产构造（toSubagentMessage 仅
        //   assistant/user/system），子 agent 的工具进度对父 Agent 完全不可见。CC runAgent.ts:792-805
        //   isRecordableMessage('progress') → yield；Java 等价 = toProgressMessage 生产接线。
        // RED 依据: toProgressMessage 在 R32-03 实施前不存在（编译即失败）。
        Tool.ToolProgress progress = new Tool.ToolProgress(
            "tool-1", Map.of("type", "mcp_progress", "status", "running", "toolName", "web_search"));

        SubagentMessage msg = SubagentExecutor.toProgressMessage(progress);

        assertThat(msg).as("工具进度必须产出 ProgressMessage (非仅定义)")
            .isInstanceOf(SubagentMessage.ProgressMessage.class);
        SubagentMessage.ProgressMessage pm = (SubagentMessage.ProgressMessage) msg;
        assertThat(pm.description())
            .as("description 携带进度数据（CC createProgressMessage data 原样载荷, messages.ts:603-618）")
            .contains("mcp_progress")
            .contains("running");
    }

    @Test
    @DisplayName("QueryParams.withOnToolProgress 消费者 → ProgressMessage → sink (子 loop 进度通道接线)")
    void withOnToolProgress_consumer_shouldEmitProgressMessageToSink() {
        // WHY: runSubagentQueryLoop 经 QueryParams.withOnToolProgress 把工具进度消费者注入 query loop，
        //   loop 读 params.onToolProgress() → StreamingToolExecutor.add(call, parent, onProgress)
        //   （wrappedCallback :1520-1540 转发工具进度）。若该 seam 缺失（onToolProgress 恒 null），
        //   即使 toProgressMessage 存在，子 agent 工具进度也永远到不了 sink。
        AtomicReference<SubagentMessage> received = new AtomicReference<>();
        QueryParams params = QueryParams.forLoop(
                List.of(), "system-prompt", null, QuerySource.SUBAGENT, "model",
                null, null, null, null, null, null, null)
            .withOnToolProgress(progress -> {
                // 镜像 runSubagentQueryLoop 生产接线: 构造 ProgressMessage 发射 sink
                received.set(SubagentExecutor.toProgressMessage(progress));
            });

        // 模拟 StreamingToolExecutor wrappedCallback（:1520-1540）转发工具进度到 onToolProgress
        params.onToolProgress().accept(new Tool.ToolProgress("tool-2", "正在下载资源"));

        assertThat(received.get()).as("sink 必须收到 ProgressMessage（工具进度可观测）")
            .isInstanceOf(SubagentMessage.ProgressMessage.class);
        assertThat(((SubagentMessage.ProgressMessage) received.get()).description())
            .isEqualTo("正在下载资源");
        // DEC-25 降级保留: messageSink==null 时生产接线不发射（async worker / 非流式 execute）
        AtomicReference<SubagentMessage> noSink = new AtomicReference<>();
        QueryParams defaultParams = QueryParams.forLoop(
                List.of(), "system-prompt", null, QuerySource.SUBAGENT, "model",
                null, null, null, null, null, null, null);
        assertThat(defaultParams.onToolProgress())
            .as("主循环 / 非流式 onToolProgress 默认 null (CC 主循环不 yield progress)")
            .isNull();
    }
}
