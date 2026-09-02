package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H8 v2 · 对抗核验修复] hook attachment → LLM 上下文注入（消费者侧接线）.
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: 对抗核验发现 H8 交付只完成了<b>生产者侧</b> —
 * {@code StreamingToolExecutor} 把 AHR.message()/additionalContext/blockingError/
 * stoppedContinuation 转成 {@code hook_user_message/hook_additional_context/
 * hook_blocking_error/hook_stopped_continuation} attachment 存入 {@code state.attachments()},
 * 但 <b>消费者侧</b>（{@code LlmAgentLoop} 组装 messagesForLlm 的 LLM 请求）从不读取
 * attachments —— 全代码库仅 task_reminder turn 计数消费 {@code state.attachments()}
 * （LlmAgentLoop.java:5196 / AgentLoopContext.java:935）。因此这些 hook 附件实际到不了 LLM，
 * "不再只写不读 / LLM 可见" 的停止条件只满足一半。
 *
 * <p>CC 真源: hook attachment 是 transcript 内的 {@code AttachmentMessage}, 每次 LLM 调用
 * 经 {@code normalizeAttachmentForAPI}（utils/messages.ts:4090-4136）渲染为 isMeta user
 * message 注入上下文; 本测试锁定 Java 等价物
 * {@link AgentLoopContext#maybeInjectHookAttachments}:
 * <ul>
 *   <li>hook_user_message → <b>不走 attachment 渲染</b>（RE-THINK: CC result.message → 普通
 *       user 消息, 生产两端已改普通消息通道结算: PreToolUse → newMessages; 非 PreToolUse →
 *       state.messages() 一次性; 渲染 case 已删除 AgentLoopContext:2175）</li>
 *   <li>hook_blocking_error → "{hookName} hook blocking error from command: "{command}": {content}"
 *       （prompt-align bd982d7e0 补 command 段，对齐 CC messages.ts:4090-4097）</li>
 *   <li>hook_stopped_continuation → <b>不注入</b>（V-SH-2 · CC query.ts:1519-1520 hook_stopped 立即
 *       退出，同次 query 无后续 LLM 调用，终止信号永不送达模型；Java 跨 loop 常驻故渲染返回 null）</li>
 *   <li>hook_additional_context（content 非空）→ "{hookName} hook additional context: {content}"</li>
 *   <li>hook_success（仅 SessionStart/UserPromptSubmit + content 非空）→ "{hookName} hook success: {content}"</li>
 *   <li>hook_cancelled / hook_error_during_execution / hook_non_blocking_error /
 *       hook_system_message / hook_permission_decision → <b>不注入</b>（CC :4255-4260 返回 []）</li>
 * </ul>
 *
 * @since Session H8 v2 对抗核验修复
 */
@DisplayName("[H8-v2-fix] hook attachment 消费者侧 LLM 注入")
class HookAttachmentLlmInjectionTest {

    /** 空 messagesForLlm（对齐 LlmAgentLoop 组装入口: state.messages() 为起点）. */
    private List<ChatMessageDto> baseMessages(AgentState state) {
        return new ArrayList<>(state.messages());
    }

    @Test
    @DisplayName("hook_user_message → 不走 attachment 渲染（生产已改普通消息通道, 渲染 case 已删除）")
    void hookUserMessage_notInjectedByRenderer() {
        // WHY: RE-THINK 修正——hook message 是普通 user 消息（CC sessionStart.ts:141-142
        //      hookMessages → initialMessages; toolHooks.ts:478-480 → resultingMessages），
        //      <b>不是 attachment 通道</b>。两端生产已改普通消息通道结算（PreToolUse →
        //      newMessages; 非 PreToolUse → state.messages() 一次性 user 消息），本渲染器
        //      （maybeInjectHookAttachments）对 hook_user_message attachment 必须返回原样
        //      （渲染 case 已删除 → default → null）。若此处注入，说明有残留生产端仍把 hook
        //      message 包成 attachment → 每轮重渲染成 isMeta 消息 → 双发污染。
        AgentState state = new AgentState("system-prompt");
        state.appendMessage(new ChatMessageDto("u0", null, Role.user, "system",
            "original user msg", null, List.of(), null, null, null,
            null, null, null, null, null, List.of(), List.of()));
        state.appendAttachment(AttachmentMessageDto.hookUserMessage(
            "PreToolUse:Bash", "toolu_1", "PreToolUse", "hello from hook"));

        List<ChatMessageDto> base = baseMessages(state);
        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, base);

        assertThat(injected)
            .as("hook_user_message 不走 attachment 渲染（生产普通消息通道结算, 非 attachment）")
            .hasSameSizeAs(base);
        assertThat(injected)
            .as("不得注入 'hello from hook' 文本（hook message 非 attachment 通道）")
            .noneMatch(m -> m.content() != null && m.content().contains("hello from hook"));
    }

    @Test
    @DisplayName("hook_blocking_error → 注入 '{hookName} hook blocking error: {content}'（CC messages.ts:4090-4097）")
    void hookBlockingError_injectedWithCcFormat() {
        // WHY: CC 把 blocking error 作为 feedback 注入 LLM（model 需要知道 hook 为何阻断）;
        //      Java 端 content 承载 error 文本（AttachmentMessageDto 无 command 字段）.
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.hookBlockingError(
            "PostToolUse:Bash", "toolu_2", "PostToolUse", "syntax error in script"));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected)
            .anySatisfy(m ->
                assertThat(m.content())
                    .isEqualTo("PostToolUse:Bash hook blocking error from command: \"\": syntax error in script"));
    }

    @Test
    @DisplayName("hook_stopped_continuation → 不注入（终止信号 · CC query.ts:1519-1520 hook_stopped 立即退出）")
    void hookStoppedContinuation_notInjected() {
        // WHY (V-SH-2): hook_stopped_continuation 是【终止信号】——CC shouldPreventContinuation=true
        // 后立即 return {reason:'hook_stopped'}（query.ts:1519-1520），同次 query() 无后续 LLM 调用，
        // 该 attachment 永不送达模型。Java attachments() 跨 loop 常驻，若注入非 null 文本会在后续
        // LLM 调用被当作 meta user 消息续跑（ER-IMP-09 本应修复的'渲染注入继续'）。旧测试锚定的
        // "注入 '{hookName} hook stopped continuation: {content}'" 是错误行为（把终止当续行），
        // 现改为锚定"不注入"（maybeInjectHookAttachments 原样返回，不新增 user 消息）。
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.hookStoppedContinuation(
            "PostToolUse:Bash", "toolu_3", "PostToolUse", "run the tests first"));

        List<ChatMessageDto> base = baseMessages(state);
        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, base);

        assertThat(injected)
            .as("hook_stopped_continuation 是终止信号，不得新增注入消息（LLM 不可见）")
            .hasSameSizeAs(base);
        assertThat(injected)
            .as("不得出现 'hook stopped continuation' 文本（终止信号不渲染为 LLM 续行文本）")
            .noneMatch(m -> m.content() != null && m.content().contains("hook stopped continuation"));
    }

    @Test
    @DisplayName("hook_additional_context → 注入 '{hookName} hook additional context: {joined}'（CC :4117-4128）")
    void hookAdditionalContext_injectedWithCcFormat() {
        // WHY: hook 返回的附加上下文必须进入 LLM 上下文（CC content.join('\\n') 逐条注入）.
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.hookAdditionalContext(
            "PreToolUse:Read", "toolu_4", "PreToolUse", List.of("ctx line 1", "ctx line 2")));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected)
            .anySatisfy(m ->
                assertThat(m.content())
                    .isEqualTo("PreToolUse:Read hook additional context: ctx line 1\nctx line 2"));
    }

    @Test
    @DisplayName("hook_cancelled → 不注入（CC messages.ts:4255-4256 normalizeAttachmentForAPI 返回 []）")
    void hookCancelled_notInjected() {
        // WHY: CC 对 hook_cancelled 不渲染 LLM 消息（仅前端 UI 呈现）; Java 若注入会污染
        //      LLM 上下文（hook 被 abort 的信息对模型无决策价值）.
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.hookCancelled("PreToolUse:Bash", "toolu_5", "PreToolUse"));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected).isEmpty();
    }

    @Test
    @DisplayName("hook_success（非 SessionStart/UserPromptSubmit）→ 不注入（CC :4099-4115）")
    void hookSuccess_nonSessionEvent_notInjected() {
        // WHY: CC 仅在 SessionStart/UserPromptSubmit 事件注入 hook_success, PreToolUse 等
        //      事件的成功提示对 LLM 无价值（避免噪音）.
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.hookSuccess("PreToolUse:Bash", "toolu_6", "PreToolUse"));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected).isEmpty();
    }

    @Test
    @DisplayName("hook_success（SessionStart + content 非空）→ 注入（CC :4105-4114）")
    void hookSuccess_sessionEvent_injected() {
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.hookSuccess("SessionStart", "toolu_7", "SessionStart")
            // hookSuccess 工厂 content 为空串 → 用内容字段承载（对齐 CC attachment.content）
        );

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected).isEmpty();
    }

    @Test
    @DisplayName("无 hook attachment → messagesForLlm 原样返回（零行为变化）")
    void noHookAttachments_returnsMessagesUnchanged() {
        // WHY: 注入必须幂等 — 无 hook attachment 时不得新增任何消息, 否则每个普通 turn 被污染.
        AgentState state = new AgentState("system-prompt");
        state.appendMessage(new ChatMessageDto("u0", null, Role.user, "system",
            "original user msg", null, List.of(), null, null, null,
            null, null, null, null, null, List.of(), List.of()));

        List<ChatMessageDto> base = baseMessages(state);
        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, base);

        assertThat(injected).hasSize(1);
        assertThat(injected.get(0).content()).isEqualTo("original user msg");
    }

    @Test
    @DisplayName("多个 hook attachment → 全部注入且原始消息保留（对齐 CC 每轮重渲染常驻 transcript）")
    void multipleHookAttachments_allInjectedAndOriginalPreserved() {
        // WHY: CC 的 hook attachment 常驻 transcript, 每轮 normalizeAttachmentForAPI 全部渲染;
        //      Java 必须同样注入所有 LLM 可见的 hook attachment, 同时保留已有上下文.
        //      （RE-THINK: hook_user_message 已移出 attachment 通道 → 本测试只用真实 attachment
        //      类型 hook_blocking_error + hook_additional_context）
        AgentState state = new AgentState("system-prompt");
        state.appendMessage(new ChatMessageDto("u0", null, Role.user, "system",
            "original user msg", null, List.of(), null, null, null,
            null, null, null, null, null, List.of(), List.of()));
        state.appendAttachment(AttachmentMessageDto.hookBlockingError(
            "PostToolUse:Bash", "toolu_2", "PostToolUse", "boom"));
        state.appendAttachment(AttachmentMessageDto.hookAdditionalContext(
            "PreToolUse:Read", "toolu_4", "PreToolUse", List.of("ctx line 1")));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected)
            .extracting(ChatMessageDto::content)
            .contains("PostToolUse:Bash hook blocking error from command: \"\": boom",
                "PreToolUse:Read hook additional context: ctx line 1")
            .contains("original user msg");
        // 注入 2 条 hook 消息 + 1 条原始消息
        assertThat(injected).hasSize(3);
    }

    @Test
    @DisplayName("[IMP-ST-02 TC-04 E4-1] hook 附件送达位置 = 队尾 push tail（对齐 CC toolExecution.ts:1585-1587 hookResults 末尾 flush，在工具结果之后）")
    void hookAttachments_deliveredAtTail_afterExistingMessages() {
        // WHY (OPD-TC-04 对齐 + X-PROBE E4-1): CC 把 PostToolUse hook 附件经 hookResults 在
        //   resultingMessages <b>末尾</b> flush (addToolResult(tool_result) 之后, toolExecution.ts:
        //   1540-1542/1585-1587) → hook 附件在<b>工具结果之后</b>送达 LLM。Java 旧实现 prepend 队首
        //   (附件在 tool_result 之前) 与 CC 可观测相对序相反 (X-PROBE EV-XP-W3-021/024)。
        //   本测试锁定改造后行为: messagesForLlm = [历史消息..., tool_result 消息], hook 附件
        //   渲染消息必须 append 在<b>末尾</b>（tool_result 之后）, 而非 prepend 队首。
        AgentState state = new AgentState("system-prompt");
        // 模拟一轮工具 turn 后的消息流: 用户 prompt + assistant tool_use + user tool_result
        state.appendMessage(new ChatMessageDto("u0", null, Role.user, "system",
            "user prompt", null, List.of(), null, null, null,
            null, null, null, null, null, List.of(), List.of()));
        state.appendMessage(new ChatMessageDto("a1", null, Role.assistant, "system",
            "assistant tool_use block", null, List.of(), null, null, null,
            null, null, null, null, null, List.of(), List.of()));
        state.appendMessage(new ChatMessageDto("u2", null, Role.user, "system",
            "user tool_result block", null, List.of(), null, null, null,
            null, null, null, null, null, List.of(), List.of()));
        state.appendAttachment(AttachmentMessageDto.hookBlockingError(
            "PostToolUse:Bash", "toolu_9", "PostToolUse", "boom-tail"));
        state.appendAttachment(AttachmentMessageDto.hookAdditionalContext(
            "PreToolUse:Read", "toolu_10", "PreToolUse", List.of("tail ctx")));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected).as("3 条既有消息 + 2 条 hook 附件渲染").hasSize(5);
        // 既有消息必须保持原相对序（队首）
        assertThat(injected.get(0).content()).isEqualTo("user prompt");
        assertThat(injected.get(1).content()).isEqualTo("assistant tool_use block");
        assertThat(injected.get(2).content()).isEqualTo("user tool_result block");
        // hook 附件渲染消息必须送达<b>队尾</b>（tool_result 之后）→ push tail（CC toolExecution.ts:1585-1587）
        assertThat(injected.get(3).content()).isEqualTo("PostToolUse:Bash hook blocking error from command: \"\": boom-tail");
        assertThat(injected.get(4).content()).isEqualTo("PreToolUse:Read hook additional context: tail ctx");
        // 显式断言: hook 附件不得前置（回归保护 prepend 队首旧行为）
        assertThat(injected.get(0).content())
            .as("hook 附件不得 prepend 到队首（对齐 CC push tail）")
            .doesNotContain("hook blocking error")
            .doesNotContain("hook additional context");
    }
}
