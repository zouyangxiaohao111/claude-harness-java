package com.nexusai.application.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sm-boundary-reload] {@code AgentState.turnCompacted} turn 级标记契约测试（consume 恰好一次）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：本标记是「对账层」的唯一信号源 —— 压缩<b>落库成功</b>后置位，
 * turn 收尾由 {@code ChatService.publishCompleteEvent} <b>consume 一次</b>（读+清）随
 * {@code message.complete.compacted=true} 出站，前端据此在收尾后重拉尾页补上「已压缩」分割线。
 * 语义必须<b>恰好一次</b>，否则两侧都出错：
 * <ul>
 *   <li>consume 不清 → 标记被后续轮重复消费 → <b>未压缩的轮</b>也触发前端重拉（假信号 / 无谓请求）；</li>
 *   <li>clear 不生效 → 上一轮 abort/error（不发 complete ⇒ 无人消费）的残留串到下一轮
 *       → 下一轮 complete 谎报 compacted=true（假信号）。</li>
 * </ul>
 * clear 的防御性语义（当前恒 no-op —— 每 run 新建 AgentState，见 {@link AgentState#clearTurnCompacted()}
 * javadoc）：将来引入 state 跨 run 复用时，上一轮 abort/error 的残留才会真正被它挡住。
 */
class AgentStateTurnCompactedTest {

    @Test
    @DisplayName("consume 恰好一次：初始 false → mark 后 true → 再 consume 回 false（读+清）")
    void markThenConsume_returnsTrueOnce() {
        AgentState state = newState();

        assertThat(state.consumeTurnCompacted())
            .as("初始未压缩 → false（不能凭空报压缩）")
            .isFalse();

        state.markTurnCompacted();

        assertThat(state.consumeTurnCompacted())
            .as("压缩落库置位后首次 consume = true（complete 据此带 compacted）")
            .isTrue();
        assertThat(state.consumeTurnCompacted())
            .as("consume 即清 → 第二次 false（防同一标记被下一轮 complete 重复消费 → 未压缩轮假重拉）")
            .isFalse();
    }

    @Test
    @DisplayName("clear 复位：mark → clear → consume == false（前瞻防御语义；当前每 run 新建 state ⇒ 恒 no-op）")
    void clear_resetsFlag() {
        AgentState state = newState();

        state.markTurnCompacted();
        state.clearTurnCompacted();

        assertThat(state.consumeTurnCompacted())
            .as("run 入口 clear 后 consume 必须 false —— 否则上一轮 abort/error 的残留会让本轮 complete 谎报 compacted")
            .isFalse();
    }

    /** 构造方式对齐同包 {@link AgentStateDualViewTest}（无 Spring，纯单测）。 */
    private static AgentState newState() {
        return new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
    }
}
