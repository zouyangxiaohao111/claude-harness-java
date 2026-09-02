package com.nexusai.application.agent;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [esc-cancel-ccalign] AgentState.abortStream 意图测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>硬中断接线</b> — cancelSession 经 {@code state.abortStream("user-cancel")} 触发
 *       LlmAgentLoop attach 的 runAbortController.abort() → provider 流消费循环 chunk 边界检查
 *       aborted → CancellationException → done.countDown() → loop 立即退出（对齐 CC abort('user-cancel')
 *       硬中断，替代原 500ms 协式轮询）。本测试断言 abort 信号真正送达 controller（isCancelled + reason）。</li>
 *   <li><b>未 attach 兜底</b> — attach 前 abortStream 必须无 NPE 且仍置协式 flag（cancelled=true），
 *       保证 registry 竞态下 cancel 语义不丢。</li>
 *   <li><b>reason 对齐</b> — CC REPL.tsx:2147 abort('user-cancel')；null 入参回落 'user-cancel'。</li>
 * </ol>
 *
 * @see AgentState#abortStream
 * @see AgentState#attachAbortController
 */
class AgentStateAbortStreamTest {

    private static AgentState newState() {
        return new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
    }

    @Test
    @DisplayName("attach 后 abortStream 送达 controller（isCancelled + reason=user-cancel + cancelled flag）")
    void abortStream_attached_abortsController() {
        AgentState state = newState();
        AbortController ac = new AbortController();
        state.attachAbortController(ac);
        assertThat(ac.isCancelled()).as("attach 后未 abort 前 controller 未取消").isFalse();

        state.abortStream("user-cancel");

        assertThat(ac.isCancelled()).as("abortStream 触发 attach 的 controller abort").isTrue();
        assertThat(ac.reason()).as("reason 对齐 CC REPL.tsx:2147 abort('user-cancel')").isEqualTo("user-cancel");
        assertThat(state.cancelled()).as("同时置协式 flag（loop 轮询退出兜底）").isTrue();
    }

    @Test
    @DisplayName("未 attach abortStream 无 NPE 且仍置 cancelled flag")
    void abortStream_noAttach_setsFlagOnly() {
        AgentState state = newState();
        state.abortStream(null);
        assertThat(state.cancelled()).as("无 attach 时 abortStream 仅置协式 flag").isTrue();
    }

    @Test
    @DisplayName("null reason 回落 'user-cancel'")
    void abortStream_nullReason_fallsBackToUserCancel() {
        AgentState state = newState();
        AbortController ac = new AbortController();
        state.attachAbortController(ac);
        state.abortStream(null);
        assertThat(ac.reason()).as("null reason 回落 'user-cancel'（CC abort() 无参等价）").isEqualTo("user-cancel");
    }

    @Test
    @DisplayName("attach 不同 controller：abortStream 作用最新 attach 的实例")
    void abortStream_usesLatestAttachedController() {
        AgentState state = newState();
        AbortController stale = new AbortController();
        AbortController current = new AbortController();
        state.attachAbortController(stale);
        state.attachAbortController(current);

        state.abortStream("user-cancel");

        assertThat(current.isCancelled()).as("abort 最新 attach 的 controller").isTrue();
        assertThat(stale.isCancelled()).as("旧 controller 不受影响（LlmAgentLoop 每次 run() 重 attach）").isFalse();
    }
}
