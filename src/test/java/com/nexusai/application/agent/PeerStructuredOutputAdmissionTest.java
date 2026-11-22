package com.nexusai.application.agent;

import com.nexusai.application.agent.messaging.PeerExecutionOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code structuredOutput.peer_source} 投影契约：两条落库路径必须写同一组准入字段。
 *
 * <h2>WHY（意图）</h2>
 * <p>跨会话来信有两条消费路径：空闲执行器（{@code CronIdleExecutor} →
 * {@code PeerRuntimeAdapter.structuredOutput}）与实时轮内 drain / 无队列直拼
 * （{@code LlmAgentLoop.peerStructuredOutput}）。两者写的是同一张
 * {@code messages.structured_output}，前端同一个准入徽标读取它。
 * <p>2026-11-21 实测：实时轮路径漏写 {@code admission/admissionReason} ⇒ 同一对会话交替显示
 * 「自动通过 · 模式一致」与「准入未知」。本测试锁住两点：
 * <ol>
 *   <li>已决定准入时，实时轮投影必须原样带出两个键（与空闲路径同键同值）；</li>
 *   <li>准入未知时写 {@code null}，⛔ 不伪造 {@code auto}。</li>
 * </ol>
 */
class PeerStructuredOutputAdmissionTest {

    /** 24 hex 才是合法 hop 段；本用例不需要转发链，直接空列表。 */
    private static PeerExecutionOrigin origin(String admission, String reason) {
        return new PeerExecutionOrigin(
            "sess-src0001", "来源会话", PeerExecutionOrigin.SOURCE_KIND_PEER,
            "peer-1", 1L, "default", "来源会话 [7cc678]",
            List.of(), admission, reason);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> peerSourceOf(Map<String, Object> structuredOutput) {
        Object ps = structuredOutput.get("peer_source");
        assertThat(ps).as("peer_source 必须存在").isInstanceOf(Map.class);
        return (Map<String, Object>) ps;
    }

    @Test
    @DisplayName("实时轮内投影带出 admission / admissionReason（与空闲路径同口径）")
    void realtimeProjectionCarriesAdmission() {
        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(origin("auto", "mode-match"), "摘要");
        assertThat(peerSourceOf(so))
            .containsEntry("admission", "auto")
            .containsEntry("admissionReason", "mode-match");
    }

    @Test
    @DisplayName("手动批准同样透传 approved（不被归一成 auto）")
    void approvedStaysApproved() {
        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(
            origin("approved", "user-approved"), "摘要");
        assertThat(peerSourceOf(so))
            .containsEntry("admission", "approved")
            .containsEntry("admissionReason", "user-approved");
    }

    @Test
    @DisplayName("准入未知 ⇒ null（⛔ 不伪造 auto，前端显示中性「准入未知」）")
    void unknownAdmissionStaysNull() {
        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(origin(null, null), "摘要");
        assertThat(peerSourceOf(so)).containsEntry("admission", null);
    }
}
