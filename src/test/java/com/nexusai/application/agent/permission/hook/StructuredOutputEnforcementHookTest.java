package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.ToolNameConstants;
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
 * [H7-arch Phase 3] StructuredOutputEnforcementHook 单测 · 对齐 CC
 * registerStructuredOutputEnforcement (hookHelpers.ts:70-83) + hasSuccessfulToolCall
 * (messages.ts:4719-4760).
 *
 * <p>WHY (CLAUDE.md 规则 9 · 测试验证意图): 验证两条核心意图:
 * <ol>
 *   <li>{@code hasSuccessfulToolCall} 正确识别 StructuredOutput 是否成功调用（CC :4719 反向扫语义）</li>
 *   <li>{@code onEvent} STOP 自过滤 + 未调用则 blockingError 重入（CC :157-160 强制语义）</li>
 * </ol>
 */
@DisplayName("[H7-arch Phase 3] StructuredOutputEnforcementHook 对齐 CC")
class StructuredOutputEnforcementHookTest {

    private static final String SO = ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME; // "StructuredOutput" (D-R1d 迁移)

    // ════════════════════════════════════════════════════════════════════════
    // hasSuccessfulToolCall（静态，镜像 CC messages.ts:4719）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hasSuccessfulToolCall: 无消息 -> false（CC :4722 empty guard）")
    void hasSuccessful_noMessages_returnsFalse() {
        assertThat(StructuredOutputEnforcementHook.hasSuccessfulToolCall(null, SO)).isFalse();
        assertThat(StructuredOutputEnforcementHook.hasSuccessfulToolCall(List.of(), SO)).isFalse();
    }

    @Test
    @DisplayName("hasSuccessfulToolCall: StructuredOutput 成功 result -> true（CC :4754 is_error!==true）")
    void hasSuccessful_successResult_returnsTrue() {
        String toolUseId = "toolu-so-1";
        List<ChatMessageDto> msgs = List.of(
            assistantWithToolCall(toolUseId, SO),
            toolResult(toolUseId, StructuredOutputEnforcementHook.SUCCESS_CONTENT));
        assertThat(StructuredOutputEnforcementHook.hasSuccessfulToolCall(msgs, SO)).isTrue();
    }

    @Test
    @DisplayName("hasSuccessfulToolCall: StructuredOutput 失败 result（isError=true）-> false（CC :4754 is_error===true）")
    void hasSuccessful_errorResult_returnsFalse() {
        String toolUseId = "toolu-so-2";
        List<ChatMessageDto> msgs = List.of(
            assistantWithToolCall(toolUseId, SO),
            toolResult(toolUseId, "Output does not match required schema: ok must be boolean", true));
        assertThat(StructuredOutputEnforcementHook.hasSuccessfulToolCall(msgs, SO)).isFalse();
    }

    @Test
    @DisplayName("hasSuccessfulToolCall: content 命中 SUCCESS 文案但 isError=true -> false（对抗核验 H13-GAP）")
    void hasSuccessful_contentMatchesSuccessButIsError_returnsFalse() {
        // WHY (对抗核验 H13-GAP): CC :4754 以 tool_result.is_error!==true 判定成功, 不依赖 content 文案。
        // 旧 Java 用 content==SUCCESS_CONTENT 判定 —— 若工具失败路径返回与成功文案相同的 content
        // （但 is_error=true），会误判成功。isError 字段修复此误判。
        String toolUseId = "toolu-so-4";
        List<ChatMessageDto> msgs = List.of(
            assistantWithToolCall(toolUseId, SO),
            toolResult(toolUseId, StructuredOutputEnforcementHook.SUCCESS_CONTENT, true));
        assertThat(StructuredOutputEnforcementHook.hasSuccessfulToolCall(msgs, SO)).isFalse();
    }

    @Test
    @DisplayName("hasSuccessfulToolCall: content 非 SUCCESS 文案但 isError=false -> true（CC is_error!==true 语义）")
    void hasSuccessful_contentDifferentButNotError_returnsTrue() {
        // WHY: CC 只认 is_error!==true, 不比对 content 文本。工具返回任意 content 且 isError=false
        // 即视为成功（SyntheticOutputTool 成功 data 恰为 SUCCESS_CONTENT, 但判定不依赖文案）。
        String toolUseId = "toolu-so-5";
        List<ChatMessageDto> msgs = List.of(
            assistantWithToolCall(toolUseId, SO),
            toolResult(toolUseId, "any payload", false));
        assertThat(StructuredOutputEnforcementHook.hasSuccessfulToolCall(msgs, SO)).isTrue();
    }

    @Test
    @DisplayName("hasSuccessfulToolCall: 未调 StructuredOutput（只调 Read）-> false（CC :4740 no tool_use）")
    void hasSuccessful_notCalled_returnsFalse() {
        List<ChatMessageDto> msgs = List.of(
            assistantWithToolCall("toolu-read-1", "Read"),
            toolResult("toolu-read-1", "file content"));
        assertThat(StructuredOutputEnforcementHook.hasSuccessfulToolCall(msgs, SO)).isFalse();
    }

    @Test
    @DisplayName("hasSuccessfulToolCall: 调了但无 result -> false（CC :4759 called but no result）")
    void hasSuccessful_calledNoResult_returnsFalse() {
        List<ChatMessageDto> msgs = List.of(assistantWithToolCall("toolu-so-3", SO));
        assertThat(StructuredOutputEnforcementHook.hasSuccessfulToolCall(msgs, SO)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // onEvent（STOP 自过滤 + blockingError 重入）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("onEvent: 非 STOP 事件 -> proceed（自过滤 1）")
    void onEvent_nonStop_returnsProceed() {
        UUID hookAgentId = UUID.randomUUID();
        AgentState state = newStateWithMessages(List.of());
        HookEvent event = HookEvent.sessionStart("sess", hookAgentId.toString(), "startup", null, null);

        GenericHook.HookResult r = new StructuredOutputEnforcementHook(hookAgentId, state).onEvent(event);

        assertThat(r.preventContinuation()).isFalse();
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("onEvent: STOP 但 agentId 不匹配 -> proceed（自过滤 2，隔离父循环）")
    void onEvent_stopNonMatchingAgent_returnsProceed() {
        UUID hookAgentId = UUID.randomUUID();
        UUID otherAgentId = UUID.randomUUID();
        AgentState state = newStateWithMessages(List.of()); // 未调 StructuredOutput
        HookEvent event = HookEvent.stop("sess", otherAgentId.toString(), false, null);

        GenericHook.HookResult r = new StructuredOutputEnforcementHook(hookAgentId, state).onEvent(event);

        assertThat(r.preventContinuation()).isFalse();
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("onEvent: STOP 匹配 agentId 且未调 StructuredOutput -> blockingError 重入（CC :157-160 强制）")
    void onEvent_stopMatchingNotCalled_returnsBlockingError() {
        UUID hookAgentId = UUID.randomUUID();
        // messages 只有 Read 调用，无 StructuredOutput
        AgentState state = newStateWithMessages(List.of(
            assistantWithToolCall("toolu-read-2", "Read"),
            toolResult("toolu-read-2", "content")));
        HookEvent event = HookEvent.stop("sess", hookAgentId.toString(), false, null);

        GenericHook.HookResult r = new StructuredOutputEnforcementHook(hookAgentId, state).onEvent(event);

        // blockingError 非空 -> 触发 loop L2782-2789 重入（注入强制提示 + markNeedsFollowUp）
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError()).contains(StructuredOutputEnforcementHook.ENFORCEMENT_PROMPT);
    }

    @Test
    @DisplayName("onEvent: STOP 匹配 agentId 且已成功调 StructuredOutput -> proceed（放行退出）")
    void onEvent_stopMatchingSuccess_returnsProceed() {
        UUID hookAgentId = UUID.randomUUID();
        String toolUseId = "toolu-so-ok";
        AgentState state = newStateWithMessages(List.of(
            assistantWithToolCall(toolUseId, SO),
            toolResult(toolUseId, StructuredOutputEnforcementHook.SUCCESS_CONTENT)));
        HookEvent event = HookEvent.stop("sess", hookAgentId.toString(), false, null);

        GenericHook.HookResult r = new StructuredOutputEnforcementHook(hookAgentId, state).onEvent(event);

        assertThat(r.preventContinuation()).isFalse();
        assertThat(r.blockingError()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 主循环接线 registerStructuredOutputEnforcement（IMP-HR-08 · OPD-WF6-01-06-?-3）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("主循环接线: 未调 StructuredOutput 的 STOP → blockingError 重入（HookRegistry.registerStructuredOutputEnforcement）")
    void mainLoopEnforcement_notCalled_blocksStop() {
        // WHY (GC-002 · EV-WF7-GC-002): 主循环 jsonSchema 门控注册 enforcement
        // （CC QueryEngine.ts:327-333）→ Stop 时未成功调用 StructuredOutput → blockingError 重入
        // （强制模型调用）。若注册后不阻断，结构化输出模式可被绕过（模型自由退出不产出结构）。
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        String sessionUuid = "00000000-0000-0000-0000-0000000000d1";
        registry.registerStructuredOutputEnforcement(sessionUuid.toString());

        // [IMP-HR-07 测试调和] isSessionHookEligible 要求会话活跃（LlmAgentLoop.isSessionRunning）
        // 且事件 ∈ CC appState 发射点集合（STOP ∈）→ markRunning 建立活跃会话。
        LlmAgentLoop.markRunning(sessionUuid);
        try {
            HookEvent stopEvent = HookEvent.stop(sessionUuid.toString(), null, false, null);
            List<ChatMessageDto> msgs = List.of(
                assistantWithToolCall("toolu-read-main", "Read"),
                toolResult("toolu-read-main", "content"));
            HookRegistry.StopHookCollectResult collect = registry.executeStopHooksCollecting(stopEvent, null, msgs);

            assertThat(collect.results()).isNotEmpty();
            boolean blocked = collect.results().stream()
                .anyMatch(r -> r.blockingError() != null
                    && r.blockingError().blockingError().contains(StructuredOutputEnforcementHook.ENFORCEMENT_PROMPT));
            assertThat(blocked).isTrue();
        } finally {
            LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    @Test
    @DisplayName("主循环接线: 已成功调 StructuredOutput 的 STOP → 放行（无 blockingError）")
    void mainLoopEnforcement_successAllowsStop() {
        // WHY: 结构化输出已成功产出 → STOP 放行（CC hookHelpers.ts:79 callback
        // hasSuccessfulToolCall 返回 true = 放行）。若已成功仍阻断，无限重入死循环。
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        String sessionUuid = "00000000-0000-0000-0000-0000000000d2";
        registry.registerStructuredOutputEnforcement(sessionUuid.toString());

        // [IMP-HR-07 测试调和] 同 notCalled 用例：markRunning 建立活跃会话（STOP ∈ CC appState 发射点）。
        LlmAgentLoop.markRunning(sessionUuid);
        try {
            HookEvent stopEvent = HookEvent.stop(sessionUuid.toString(), null, false, null);
            String toolUseId = "toolu-so-main";
            List<ChatMessageDto> msgs = List.of(
                assistantWithToolCall(toolUseId, SO),
                toolResult(toolUseId, StructuredOutputEnforcementHook.SUCCESS_CONTENT));
            HookRegistry.StopHookCollectResult collect = registry.executeStopHooksCollecting(stopEvent, null, msgs);

            assertThat(collect.results()).isNotEmpty();
            boolean blocked = collect.results().stream().anyMatch(r -> r.blockingError() != null);
            assertThat(blocked).isFalse();
        } finally {
            LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    @Test
    @DisplayName("主循环接线: 未注册 enforcement（jsonSchema=null）→ STOP 放行（零回归）")
    void mainLoopEnforcement_notRegistered_allowsStop() {
        // WHY: jsonSchema==null（默认）→ 主循环不注册 enforcement（CC QueryEngine.ts:331 门控
        // jsonSchema && hasStructuredOutputTool）→ 行为与现状一致（零回归）。
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        HookEvent stopEvent = HookEvent.stop("sess-main", null, false, null);
        List<ChatMessageDto> msgs = List.of(assistantWithToolCall("toolu-read-x", "Read"));
        HookRegistry.StopHookCollectResult collect = registry.executeStopHooksCollecting(stopEvent, null, msgs);

        boolean blocked = collect.results().stream().anyMatch(r -> r.blockingError() != null);
        assertThat(blocked).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 测试夹具
    // ════════════════════════════════════════════════════════════════════════

    private static AgentState newStateWithMessages(List<ChatMessageDto> msgs) {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        for (ChatMessageDto m : msgs) state.appendMessage(m);
        return state;
    }

    /** assistant message 含一个 tool_call（对齐 CC assistant.message.content tool_use block）. */
    private static ChatMessageDto assistantWithToolCall(String toolUseId, String toolName) {
        ToolCallDto tc = new ToolCallDto(toolUseId, toolName, "{}", null, null);
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, null,
            "", null, List.of(tc), null, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** tool result message（role=tool，对齐 CC user.message.content tool_result block）· isError=false. */
    private static ChatMessageDto toolResult(String toolUseId, String content) {
        return toolResult(toolUseId, content, false);
    }

    /** tool result message 含 isError 标志 · 对齐 CC tool_result.is_error (messages.ts:4754). */
    private static ChatMessageDto toolResult(String toolUseId, String content, boolean isError) {
        // canonical 20 参: ... imagePasteIds, structuredOutput=null, isMeta=false, isError
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.tool, null,
            content, null, null, null, null, null, null, OffsetDateTime.now(),
            toolUseId, null, null, List.of(), List.of(),
            null, false, isError);
    }
}
