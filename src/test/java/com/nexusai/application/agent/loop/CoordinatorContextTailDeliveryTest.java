package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * coordinator_context 动态附件意图测试 · 对齐 claude.exe 2.1.328
 * （producer {@code Ra()} @336413600，渲染 @330697225，schema @325127619）。
 *
 * <h2>WHY 存在</h2>
 * <p>协调者的 worker 工具清单会变（插件/MCP/skill 变化、模式进出）。旧清单仍留在对话历史里，
 * 模型可能继续按它行事。CC 在清单变化时插一条附件明确「旧清单作废」；模式结束时插
 * 「Coordinator mode has ended」。
 *
 * <p>本仓落点 = 尾部真实 meta 消息（{@code state.appendMessage}），判据读本 agent 自己的历史，
 * 与 date 附件同款。subtype 编码三态：
 * <ul>
 *   <li>{@code coordinator_context}（首次）</li>
 *   <li>{@code coordinator_context_change}（变化，content 前缀 "The worker tools have changed…"）</li>
 *   <li>{@code coordinator_context_end}（结束）</li>
 * </ul>
 */
@DisplayName("coordinator_context 尾部附件 · 首次/变化/结束")
class CoordinatorContextTailDeliveryTest {

    private static final String TOOLS_A =
        "Workers spawned via the Agent tool have access to these tools:\n- Bash\n- Read";
    private static final String TOOLS_B =
        "Workers spawned via the Agent tool have access to these tools:\n- Bash\n- Read\n- Skill";

    private static String newSessionId() {
        return "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static AgentState stateWithUserMsg(String sessionId) {
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(new ChatMessageDto(
            java.util.UUID.randomUUID().toString(), sessionId, Role.user, "user",
            "hello", null, java.util.List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, java.util.List.of(), java.util.List.of(), null, false));
        return state;
    }

    private static ChatMessageDto last(AgentState state) {
        List<ChatMessageDto> all = state.rawMessages();
        return all.get(all.size() - 1);
    }

    @Test
    @DisplayName("首次：无历史 → 追加 coordinator_context（裸工具清单，isMeta）")
    void first_emitsContext() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, true, TOOLS_A);

        assertThat(state.rawMessages()).hasSize(before + 1);
        ChatMessageDto msg = last(state);
        assertThat(msg.subtype()).isEqualTo("coordinator_context");
        assertThat(msg.isMeta()).isTrue();
        assertThat(msg.author()).isEqualTo("attachment");
        assertThat(msg.content()).isEqualTo(TOOLS_A);
    }

    @Test
    @DisplayName("同内容：零追加（历史已有同一份清单）")
    void sameContent_noop() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, true, TOOLS_A);
        int afterFirst = state.rawMessages().size();

        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, true, TOOLS_A);

        assertThat(state.rawMessages()).hasSize(afterFirst);
    }

    @Test
    @DisplayName("变化：追加 coordinator_context_change + 旧清单作废前缀")
    void changed_emitsChange() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, true, TOOLS_A);

        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, true, TOOLS_B);

        ChatMessageDto msg = last(state);
        assertThat(msg.subtype()).isEqualTo("coordinator_context_change");
        assertThat(msg.content())
            .startsWith("The worker tools have changed; this replaces the earlier list.\n\n")
            .contains(TOOLS_B);
    }

    @Test
    @DisplayName("模式结束：历史有清单 + 门关 → 追加 coordinator_context_end")
    void ended_emitsEnd() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, true, TOOLS_A);

        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, false, null);

        ChatMessageDto msg = last(state);
        assertThat(msg.subtype()).isEqualTo("coordinator_context_end");
        assertThat(msg.content())
            .isEqualTo("Coordinator mode has ended; the earlier list of worker tools no longer applies.");
    }

    @Test
    @DisplayName("结束后再进入：按首次处理（不带 changed 前缀）")
    void reentered_afterEnd_treatedAsFirst() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, true, TOOLS_A);
        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, false, null);

        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.REPL_MAIN_THREAD, true, TOOLS_A);

        ChatMessageDto msg = last(state);
        assertThat(msg.subtype()).isEqualTo("coordinator_context");
        assertThat(msg.content()).isEqualTo(TOOLS_A);
    }

    @Test
    @DisplayName("非主线程（SUBAGENT）：不注入")
    void subagent_noInjection() {
        String sid = newSessionId();
        AgentState state = stateWithUserMsg(sid);
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitCoordinatorContext(state, QuerySource.SUBAGENT, true, TOOLS_A);

        assertThat(state.rawMessages()).hasSize(before);
    }
}
