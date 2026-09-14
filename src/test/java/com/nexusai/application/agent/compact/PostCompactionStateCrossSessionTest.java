package com.nexusai.application.agent.compact;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T15-1 · {@code PostCompactionState} 会话级隔离 · 对齐 CC {@code STATE.pendingPostCompaction}
 * （bootstrap/state.ts:771 mark / :777-781 consume）。
 *
 * <p><b>WHY（可观测语义判据）</b>：{@code isPostCompaction} 的可观测单元是
 * <b>「某个会话的首个 API success 事件」</b>（{@code AnthropicSdkProvider} 消费后写进该请求的
 * 遥测元数据，用于区分「压缩导致的 cache miss」与「TTL 过期导致的 cache miss」）⇒ 该状态必须
 * 按会话键控。CC 侧是进程级单布尔（{@code STATE}）—— 在 CC 里安全（单进程单会话），本仓
 * <b>一 JVM 多会话</b> ⇒ 原实现的单份布尔会让 <b>A 会话的 mark 被 B 会话的下个 API success 消费</b>。
 *
 * <p><b>断言面</b>：本类固定走<b>回落分支</b>（registry 未接线 / 会话未注册）—— 这正是原实现里
 * 唯一承载跨会话串扰的载体（会话级分支挂 {@code AgentState}，本身已隔离）。
 *
 * <p>本类只在 <b>T15 批复验</b>时使用；生产语义不变（仅键控维度变化）。
 */
class PostCompactionStateCrossSessionTest {

    private static final String SESSION_A = "sess-cross-a";
    private static final String SESSION_B = "sess-cross-b";

    @BeforeEach
    @AfterEach
    void resetState() {
        // 固定走回落 Map 分支：registry 未接线（生产 registry 由 @Component 注入；本用例只测回落载体）
        PostCompactionState.setSessionAgentStateRegistry(null);
        PostCompactionState.reset();
    }

    @Test
    @DisplayName("T15-1: A 会话 mark 后，B 会话 consume 不得拿到（B 的 isPostCompaction 恒 false）")
    void markInSessionA_isNotConsumedBySessionB() {
        PostCompactionState.markPostCompaction(SESSION_A);

        assertThat(PostCompactionState.isPostCompactionPending(SESSION_A))
            .as("A 会话自身处于 pending").isTrue();
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_B))
            .as("B 会话不得看到 A 的 mark（原单布尔实现此处为 true = 跨会话串扰）").isFalse();

        assertThat(PostCompactionState.consumePostCompaction(SESSION_B))
            .as("B 会话的首个 API success 不得被 A 的 mark 归因（isPostCompaction 必须 false）")
            .isFalse();
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A))
            .as("A 会话自己的首个 API success 仍须拿到 isPostCompaction=true"
                + "（原实现会被 B 抢先消费掉 ⇒ false）")
            .isTrue();
    }

    @Test
    @DisplayName("T15-1: 两会话各自 mark/consume 互不影响（各得一次 true）")
    void twoSessions_eachGetOwnTrue() {
        PostCompactionState.markPostCompaction(SESSION_A);
        PostCompactionState.markPostCompaction(SESSION_B);

        assertThat(PostCompactionState.consumePostCompaction(SESSION_A)).isTrue();
        assertThat(PostCompactionState.consumePostCompaction(SESSION_B)).isTrue();
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A))
            .as("consume 是 one-shot：同会话第二次为 false").isFalse();
    }

    @Test
    @DisplayName("T15-1: clear(A) 只清 A 的回落标记，不动 B")
    void clearSessionA_doesNotClearSessionB() {
        PostCompactionState.markPostCompaction(SESSION_A);
        PostCompactionState.markPostCompaction(SESSION_B);

        PostCompactionState.clear(SESSION_A);

        assertThat(PostCompactionState.isPostCompactionPending(SESSION_A)).isFalse();
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_B))
            .as("clear(A) 不得清掉 B 的 pending（原单布尔实现处为 false = 误清他人）").isTrue();
    }
}
