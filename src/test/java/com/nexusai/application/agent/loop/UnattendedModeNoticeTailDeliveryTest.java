package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.config.ImUnattendedConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * unattended_mode 尾部附件意图测试（与 {@link CoordinatorContextTailDeliveryTest} 同款投递形态）。
 *
 * <h2>WHY 存在</h2>
 * <p>无人值守会话会把 AskUserQuestion 从工具池摘掉、同时打开 SendUserMessage。模型只看得到
 * 工具清单，不知道「用户不在电脑前」⇒ 会把结论只留在正文里，或反复尝试向用户提问。
 * 本附件在开关切换时插一条状态提示；关闭时插「已结束」，否则历史里的开启提示会一直生效。
 *
 * <p>判据 = 会话状态（{@code sessions.unattended_mode}，经 {@link ImUnattendedConfig}）
 * 与历史里上一条 unattended 提示是否一致；一致 ⇒ 零追加（每 tool 轮调用幂等）。
 */
@DisplayName("unattended_mode 尾部附件 · 开/关各一次、幂等")
class UnattendedModeNoticeTailDeliveryTest {

    @AfterEach
    void resetChecker() {
        // 静态全局注入：不清理会污染同 JVM 的其他测试（ImUnattendedConfigTest 同款）
        ImUnattendedConfig.installChecker(null);
    }

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
    @DisplayName("开启无人值守：无历史 → 追加 unattended_mode（isMeta 附件，正文指向 SendUserMessage）")
    void enabled_emitsNotice() {
        String sid = newSessionId();
        ImUnattendedConfig.installChecker(sid::equals);
        AgentState state = stateWithUserMsg(sid);
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.REPL_MAIN_THREAD);

        assertThat(state.rawMessages()).hasSize(before + 1);
        ChatMessageDto msg = last(state);
        assertThat(msg.subtype()).isEqualTo("unattended_mode");
        assertThat(msg.isMeta()).isTrue();
        assertThat(msg.author()).isEqualTo("attachment");
        assertThat(msg.content()).contains("unattended mode").contains("SendUserMessage");
    }

    @Test
    @DisplayName("同状态重复调用：零追加（每 tool 轮幂等）")
    void sameState_noop() {
        String sid = newSessionId();
        ImUnattendedConfig.installChecker(sid::equals);
        AgentState state = stateWithUserMsg(sid);
        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.REPL_MAIN_THREAD);
        int afterFirst = state.rawMessages().size();

        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.REPL_MAIN_THREAD);

        assertThat(state.rawMessages()).hasSize(afterFirst);
    }

    @Test
    @DisplayName("关闭无人值守：历史有开启提示 → 追加 unattended_mode_end")
    void disabled_emitsEnd() {
        String sid = newSessionId();
        ImUnattendedConfig.installChecker(sid::equals);
        AgentState state = stateWithUserMsg(sid);
        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.REPL_MAIN_THREAD);

        ImUnattendedConfig.installChecker(s -> false);
        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.REPL_MAIN_THREAD);

        ChatMessageDto msg = last(state);
        assertThat(msg.subtype()).isEqualTo("unattended_mode_end");
        assertThat(msg.content()).contains("Unattended mode has ended");
    }

    @Test
    @DisplayName("结束后再次开启：按首次处理（再投一条 unattended_mode）")
    void reenabled_afterEnd_emitsAgain() {
        String sid = newSessionId();
        ImUnattendedConfig.installChecker(sid::equals);
        AgentState state = stateWithUserMsg(sid);
        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.REPL_MAIN_THREAD);
        ImUnattendedConfig.installChecker(s -> false);
        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.REPL_MAIN_THREAD);

        ImUnattendedConfig.installChecker(sid::equals);
        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.REPL_MAIN_THREAD);

        assertThat(last(state).subtype()).isEqualTo("unattended_mode");
    }

    @Test
    @DisplayName("未开启且从未提示：零追加（普通会话不被塞提示）")
    void neverEnabled_noop() {
        String sid = newSessionId();
        ImUnattendedConfig.installChecker(s -> false);
        AgentState state = stateWithUserMsg(sid);
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.USER);

        assertThat(state.rawMessages()).hasSize(before);
    }

    @Test
    @DisplayName("非主线程（SUBAGENT）：不注入")
    void subagent_noInjection() {
        String sid = newSessionId();
        ImUnattendedConfig.installChecker(sid::equals);
        AgentState state = stateWithUserMsg(sid);
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitUnattendedModeNotice(state, QuerySource.SUBAGENT);

        assertThat(state.rawMessages()).hasSize(before);
    }
}
