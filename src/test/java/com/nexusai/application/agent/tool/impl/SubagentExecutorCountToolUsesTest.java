package com.nexusai.application.agent.tool.impl;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SUB-03 返工] countToolUses 聚焦单测（闭环规则九 + reflection #6）。
 *
 * <p>WHY（规则九 验证意图）：D3 修复的核心行为 = 子 Agent 工具调用计数必须从消息历史
 * tool_use 块真实统计（对齐 CC {@code countToolUses} agentToolUtils.ts:262-274），
 * 而不是 queryLoop 恒 0 占位。若该行为失效，父 Agent 收到的 totalToolUseCount 会回退为 0
 * —— tool budget 决策 / task-notification usage 段（TaskNotificationBuilder:124）会系统性
 * 低估子 Agent 工具用量。此前旧实现正是"业务逻辑恒 0 但测试仍绿"的测试设计错误（reflection #6）。
 *
 * <p>countToolUses 为 static 包可见方法（package-private seam，Pattern #14），
 * 直接以单元方式验证四种边界：assistant+toolCalls 计数 / 非 assistant 跳过 /
 * startInclusive 前缀跳过 / null 安全。
 */
@DisplayName("[IMP-SUB-03] SubagentExecutor.countToolUses 聚焦单测")
class SubagentExecutorCountToolUsesTest {

    private static final String SESSION = UUID.randomUUID().toString();

    private static ToolCallDto toolCall() {
        return new ToolCallDto(UUID.randomUUID().toString(), "bash", "{}", null, false);
    }

    /** R32-b14 17 参兼容构造器：只关心 role + toolCalls，其余字段取安全默认。 */
    private static ChatMessageDto msg(Role role, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, role, null, "content", null,
            toolCalls, null, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("assistant 消息的 toolCalls 逐条累加（对齐 CC tool_use 块计数）")
    void countToolUses_shouldSumToolCallsOnAssistantMessages() {
        // WHY: CC countToolUses 对 assistant 消息 content 中 type==='tool_use' 块逐个 +1
        //   （agentToolUtils.ts:265-269）。Java 等价表示 = toolCalls 列表；每条 toolCall 即一个
        //   tool_use 块。父 Agent 依赖该值做 tool budget 决策（规则九 WHY）。
        List<ChatMessageDto> messages = List.of(
            msg(Role.assistant, List.of(toolCall(), toolCall())),   // 2 个 tool_use 块
            msg(Role.user, null),
            msg(Role.assistant, List.of(toolCall())),               // 1 个 tool_use 块
            msg(Role.assistant, List.of(toolCall(), toolCall(), toolCall()))); // 3 个

        assertThat(SubagentExecutor.countToolUses(messages, 0))
            .as("assistant 消息的 toolCalls 必须逐条累加 = 2+1+3")
            .isEqualTo(6);
    }

    @Test
    @DisplayName("非 assistant 消息跳过（含 user 带 toolCalls 的异常形状）")
    void countToolUses_shouldSkipNonAssistantMessages() {
        // WHY: CC 仅对 m.type === 'assistant' 的消息遍历 content 块（agentToolUtils.ts:265）；
        //   user/tool/system 消息无 tool_use 块。Java 端即使 toolCalls 字段被异常填充，
        //   也必须按 role 判别跳过 —— 保证口径与 CC 一致（不把工具结果消息当调用计数）。
        List<ChatMessageDto> messages = List.of(
            msg(Role.user, List.of(toolCall())),      // 非 assistant，即使带 toolCalls 也跳过
            msg(Role.tool, List.of(toolCall())),
            msg(Role.system, null),
            msg(Role.assistant, List.of(toolCall())), // 唯一应计数
            msg(Role.user, null));

        assertThat(SubagentExecutor.countToolUses(messages, 0))
            .as("仅 assistant 消息参与计数")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("startInclusive 前缀跳过（父 context / fork 前缀不计数）")
    void countToolUses_shouldSkipPrefixBeforeStartInclusive() {
        // WHY: D3 实现语义 —— CC agentMessages 不含 initialMessages/fork 前缀（AgentTool.tsx:786
        //   空数组 + :1065 push），Java finalState.messages() 含 initialMsgCount 前缀。父 context
        //   的 tool_use 不应计入子 agent 自身工具调用计数（SubagentExecutor.java:3699-3700）。
        //   startInclusive=initialMsgCount 即该前缀分界；此用例验证分界前全部跳过。
        List<ChatMessageDto> messages = List.of(
            msg(Role.assistant, List.of(toolCall(), toolCall())),   // index 0：父 context 前缀，应跳过
            msg(Role.assistant, List.of(toolCall(), toolCall(), toolCall())), // index 1：子 agent 产出
            msg(Role.assistant, List.of(toolCall())));                       // index 2

        assertThat(SubagentExecutor.countToolUses(messages, 1))
            .as("startInclusive=1 应跳过 index 0 的 2 个父 context toolCall，计 index 1+2 = 3+1")
            .isEqualTo(4);

        assertThat(SubagentExecutor.countToolUses(messages, 0))
            .as("startInclusive=0 全量计数 = 2+3+1")
            .isEqualTo(6);
    }

    @Test
    @DisplayName("null 安全：null 列表 / null toolCalls / 越界 startInclusive 均不抛异常")
    void countToolUses_shouldBeNullSafe() {
        // WHY: finalState 可能为 null（queryLoop 异常/abort 路径），summarySource 回退到
        //   state.messages()；历史 DB 消息 toolCalls 可能为 null（旧消息无解析）。countToolUses
        //   作为 metrics 计算不得在这些输入下抛 NPE —— 规则十二 Fail loud 之外，异常路径
        //   本身已有 catch 兜底，计数函数应保持纯函数性质（null 输入 → 0）。
        assertThat(SubagentExecutor.countToolUses(null, 0))
            .as("null 消息列表 → 0")
            .isEqualTo(0);

        List<ChatMessageDto> withNullToolCalls = List.of(
            msg(Role.assistant, null),
            msg(Role.assistant, null));
        assertThat(SubagentExecutor.countToolUses(withNullToolCalls, 0))
            .as("assistant 消息 toolCalls=null → 跳过不抛异常")
            .isEqualTo(0);

        List<ChatMessageDto> three = List.of(
            msg(Role.assistant, List.of(toolCall())),
            msg(Role.assistant, List.of(toolCall())),
            msg(Role.assistant, List.of(toolCall())));
        assertThat(SubagentExecutor.countToolUses(three, 99))
            .as("startInclusive 越界（≥ size）→ 0，无 IndexOutOfBounds")
            .isEqualTo(0);
    }
}
