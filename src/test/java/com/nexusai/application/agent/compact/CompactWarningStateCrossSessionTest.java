package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T15-2 · {@code CompactWarningState} 会话级隔离 · 对齐 CC {@code compactWarningStore}
 * （compactWarningState.ts:6-18）。
 *
 * <p><b>WHY（可观测语义判据）</b>：抑制态的可观测单元是 <b>「该会话前端收到的
 * {@code token_warning(suppressed=…)} STOMP 事件 + 该会话的 'Context low' 横幅态」</b>。
 * CC 侧是 module-level store（CC 单进程单会话 ⇒ 模块态 ≡ 会话态，前端直接订阅模块 store）；
 * 本仓 <b>一 JVM 多会话 + 按会话寻址的 STOMP</b> ⇒ 原单份 {@code AtomicBoolean} 有两条**结构性**缺陷：
 * <ol>
 *   <li><b>推送丢失</b>：State 变化才推送 ⇒ A 抑制后 B 的 CAS(false→true) 失败 ⇒ B 前端<b>永远收不到</b>
 *       {@code token_warning(suppressed=true)}。</li>
 *   <li><b>误清他人抑制</b>：A 的 microcompact 复位会把<b>全局</b>抑制态清掉 ⇒ B 的前端横幅错误解除。</li>
 * </ol>
 */
class CompactWarningStateCrossSessionTest {

    private static final String SESSION_A = "sess-warn-a";
    private static final String SESSION_B = "sess-warn-b";

    private final List<AgentEvent.TokenWarning> pushes = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void resetModuleState() {
        CompactWarningState.resetForTesting();
    }

    private CompactWarningState.SessionPushContext ctx(String sessionId) {
        return new CompactWarningState.SessionPushContext(sessionId, pushes::add);
    }

    @Test
    @DisplayName("T15-2: A 会话抑制后，B 会话压缩仍必须推 token_warning(suppressed=true)")
    void sessionBSuppress_stillPushes() {
        CompactWarningState.suppressCompactWarning(SESSION_A, ctx(SESSION_A));
        CompactWarningState.suppressCompactWarning(SESSION_B, ctx(SESSION_B));

        assertThat(pushes).as("两会话各推一条（原全局 AtomicBoolean 实现下 B 的 CAS 失败 ⇒ 只有 1 条）")
            .hasSize(2);
        assertThat(pushes).extracting(AgentEvent.TokenWarning::sessionId)
            .containsExactly(SESSION_A, SESSION_B);
        assertThat(pushes).allMatch(AgentEvent.TokenWarning::suppressed);

        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION_A)).isTrue();
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION_B)).isTrue();
    }

    @Test
    @DisplayName("T15-2: clear(A) 只复位 A 的抑制态，不动 B")
    void clearSessionA_doesNotClearSessionB() {
        CompactWarningState.suppressCompactWarning(SESSION_A, ctx(SESSION_A));
        CompactWarningState.suppressCompactWarning(SESSION_B, ctx(SESSION_B));
        pushes.clear();

        CompactWarningState.clearCompactWarningSuppression(SESSION_A, ctx(SESSION_A));

        assertThat(pushes).as("clear(A) 推一条 suppressed=false（sessionId=A）").hasSize(1);
        assertThat(pushes.get(0).sessionId()).isEqualTo(SESSION_A);
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION_A)).isFalse();
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION_B))
            .as("B 的抑制态不得被 A 的 clear 清掉（原全局实现处为 false = 误清他人 → B 横幅错误解除）")
            .isTrue();
    }

    @Test
    @DisplayName("T15-2: 同会话幂等（值未变不重复推送），跨会话互不抑制")
    void sameSession_isIdempotent() {
        CompactWarningState.suppressCompactWarning(SESSION_A, ctx(SESSION_A));
        CompactWarningState.suppressCompactWarning(SESSION_A, ctx(SESSION_A));

        assertThat(pushes).as("同会话第二次 suppress 值未变 ⇒ 不推").hasSize(1);
        assertThat(CompactWarningState.isCompactWarningSuppressed(SESSION_B))
            .as("B 会话从未抑制 ⇒ false（原全局实现处为 true = 读到他会话状态）").isFalse();
    }

    @Test
    @DisplayName("T15-2: 抑制态读数按会话（A 抑制不影响 B 的 publishTokenWarning 载荷）")
    void readIsPerSession() {
        CompactWarningState.suppressCompactWarning(SESSION_A, ctx(SESSION_A));

        boolean suppressedForB = CompactWarningState.isCompactWarningSuppressed(SESSION_B);
        boolean suppressedForA = CompactWarningState.isCompactWarningSuppressed(SESSION_A);

        assertThat(suppressedForA).isTrue();
        assertThat(suppressedForB)
            .as("LlmAgentLoop 触发点3 按 state.sessionId() 读抑制态 ⇒ B 必须读到 false（否则 B 的"
                + "'Context low' 警告被 A 的压缩静默吞掉）")
            .isFalse();
    }
}
