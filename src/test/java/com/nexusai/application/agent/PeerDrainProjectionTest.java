package com.nexusai.application.agent;

import com.nexusai.application.agent.messaging.PeerEnvelope;
import com.nexusai.application.agent.messaging.PeerExecutionOrigin;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sendmessage-cross-session · B] peer 投影 / 队列载体的窄测试（纯对象，无 Spring / 无 DB）。
 *
 * <p><b>验证意图</b>：
 * <ol>
 *   <li>{@link LlmAgentLoop#peerMessageDto} 的字段合同：role=user / isMeta / subtype=agent_message /
 *       author=来源 sessionId / queuedOrigin='peer' / content=原文（不预包壳）/ structuredOutput 投影；</li>
 *   <li>{@link LlmAgentLoop#wrapQueuedMessagesForApi} 从 structuredOutput.peer_source 还原信封后
 *       走 CC 同形围栏，且<b>只包一次</b>（第二次调用原样返回）；</li>
 *   <li>{@link NotificationQueue} 入队（priority=null → normalizePriority 重建）不丢 peerEnvelope，
 *       且 {@code drainForQuery} 按会话归属只捞本会话 peer 项；</li>
 *   <li>非 peer 会话 drain 不走 peer 投影（守卫判据是信封，不是 workload 字符串）。</li>
 * </ol>
 */
class PeerDrainProjectionTest {

    private static PeerEnvelope envelope(String messageId, String fromSession) {
        return PeerEnvelope.create(messageId, "lead", fromSession, "Lead",
            PermissionMode.DEFAULT, List.of(), 3L, "摘要", false);
    }

    private static PeerExecutionOrigin origin(PeerEnvelope env) {
        return PeerExecutionOrigin.fromEnvelope(env, env.fromName(),
            PeerExecutionOrigin.SOURCE_KIND_PEER);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    @Test
    @DisplayName("peerMessageDto：字段合同逐项成立（内容为原文、壳不在 DTO 上）")
    void peerMessageProjection() {
        PeerEnvelope env = envelope("msg-peer-1", "sess-src-9");
        PeerExecutionOrigin origin = origin(env);
        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(origin, env.summary());

        ChatMessageDto m = LlmAgentLoop.peerMessageDto("msg-peer-1", "原文正文", "sess-src-9", so);

        assertThat(m.id()).isEqualTo("msg-peer-1");
        assertThat(m.role()).isEqualTo(Role.user);
        assertThat(m.isMeta()).isTrue();
        assertThat(m.subtype()).isEqualTo("agent_message");
        assertThat(m.author()).isEqualTo("sess-src-9");
        assertThat(m.queuedOrigin()).isEqualTo("peer");
        assertThat(m.content()).isEqualTo("原文正文");
        assertThat(m.content()).doesNotContain("<cross-session-message>");
        assertThat(m.structuredOutput()).isSameAs(so);

        @SuppressWarnings("unchecked")
        Map<String, Object> peerSource = (Map<String, Object>) so.get("peer_source");
        assertThat(peerSource).containsEntry("sourceSessionId", "sess-src-9")
            .containsEntry("messageId", "msg-peer-1")
            .containsEntry("endpointGeneration", 3L)
            .containsEntry("from", "lead")
            .containsEntry("fromMode", "default");
        assertThat(so).containsEntry("agent_summary", "摘要");
    }

    @Test
    @DisplayName("发送层包壳：从 peer_source 还原信封，首行+结尾双判，只包一次")
    void wrapsPeerPayloadOnce() {
        PeerEnvelope env = envelope("msg-peer-2", "sess-src-10");
        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(origin(env), env.summary());
        ChatMessageDto raw = LlmAgentLoop.peerMessageDto("msg-peer-2", "请检查磁盘", "sess-src-10", so);

        List<ChatMessageDto> once = LlmAgentLoop.wrapQueuedMessagesForApi(List.of(raw));
        assertThat(once).hasSize(1);
        String wrapped = once.get(0).content();
        assertThat(wrapped).startsWith("<cross-session-message");
        assertThat(wrapped).contains("from-session=\"sess-src-10\"");
        assertThat(wrapped).contains("\n请检查磁盘\n</cross-session-message>");
        // [J05] 真实发送层注入路径也要带服务端入站说明段，且只有一份
        assertThat(wrapped).contains(PeerEnvelope.inboundNotice());
        assertThat(countOccurrences(wrapped, "<" + PeerEnvelope.INBOUND_NOTICE_TAG + ">")).isEqualTo(1);
        // state / DB 侧原文未被污染
        assertThat(raw.content()).isEqualTo("请检查磁盘");

        // 第二遍（模拟重试 / resume 重包）必须原样返回，不叠壳
        List<ChatMessageDto> twice = LlmAgentLoop.wrapQueuedMessagesForApi(once);
        assertThat(twice.get(0).content()).isEqualTo(wrapped);
    }

    @Test
    @DisplayName("SessionCreate 首条任务（peer-launch-*）：追加专用说明恰好一份，重包不重复")
    void launchNoteRenderedOnce() {
        String launchId = "peer-launch-abc123";
        PeerEnvelope env = envelope(launchId, "sess-src-11");
        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(origin(env), env.summary());
        ChatMessageDto raw = LlmAgentLoop.peerMessageDto(launchId, "调研竞品", "sess-src-11", so);

        List<ChatMessageDto> once = LlmAgentLoop.wrapQueuedMessagesForApi(List.of(raw));
        String wrapped = once.get(0).content();

        assertThat(countOccurrences(wrapped, "<" + PeerEnvelope.CREATED_PEER_LAUNCH_NOTE_TAG + ">"))
            .isEqualTo(1);
        assertThat(wrapped).contains("Originating session:").contains("sess-src-11");
        assertThat(wrapped).contains("send a message");
        assertThat(wrapped).contains("SendMessage");
        assertThat(wrapped).contains(PeerEnvelope.inboundNotice());
        // 专用说明在正文之后（正文 → note → 闭合）
        assertThat(wrapped.indexOf("调研竞品"))
            .isLessThan(wrapped.indexOf("<" + PeerEnvelope.CREATED_PEER_LAUNCH_NOTE_TAG + ">"));
        // state / DB 侧原文未被污染
        assertThat(raw.content()).isEqualTo("调研竞品");

        // 第二遍（模拟重试 / resume 重包）原样返回，不叠说明
        List<ChatMessageDto> twice = LlmAgentLoop.wrapQueuedMessagesForApi(once);
        assertThat(twice.get(0).content()).isEqualTo(wrapped);
    }

    @Test
    @DisplayName("普通 SendMessage（peer-*）：不出现专用说明")
    void plainPeerHasNoLaunchNote() {
        String id = "peer-1234";
        PeerEnvelope env = envelope(id, "sess-src-12");
        Map<String, Object> so = LlmAgentLoop.peerStructuredOutput(origin(env), env.summary());
        ChatMessageDto raw = LlmAgentLoop.peerMessageDto(id, "普通来信", "sess-src-12", so);

        String wrapped = LlmAgentLoop.wrapQueuedMessagesForApi(List.of(raw)).get(0).content();

        assertThat(wrapped).contains(PeerEnvelope.inboundNotice());
        assertThat(wrapped).doesNotContain("<" + PeerEnvelope.CREATED_PEER_LAUNCH_NOTE_TAG + ">");
    }

    @Test
    @DisplayName("peer_source 缺失的 peer 行：不包壳、不抛（fail loud 记日志）")
    void missingPeerSourceDoesNotWrapOrThrow() {
        ChatMessageDto orphan = LlmAgentLoop.toMessage(Role.user, "无来源", null, "msg-orphan", true)
            .withQueuedOrigin("peer");
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(List.of(orphan));
        assertThat(out.get(0).content()).isEqualTo("无来源");
    }

    @Test
    @DisplayName("队列：priority=null 入队不丢 peerEnvelope；drainForQuery 只捞本会话项")
    void queuePreservesPeerEnvelopeAndSessionOwnership() {
        NotificationQueue queue = new NotificationQueue();
        String target = "sess-b2-target";
        String other = "sess-b2-other";
        PeerEnvelope env = envelope("msg-peer-3", "sess-src-11");

        queue.enqueue(new NotificationQueue.QueueItem(
            "peer 正文", NotificationQueue.MODE_PROMPT,
            null /* priority → normalizePriority 重建 */, null,
            "msg-peer-3", true, "peer-message", true,
            new NotificationQueue.MessageOrigin("peer", null), target,
            null, null, null, null, null,
            // [V306] notifyChats 是第 16 组件（peer 项无通知目标）⇒ 信封在第 17 位走 canonical。
            // ⛔ 不能补「16 参 + peerEnvelope 末位」兼容构造：ChatService:409 等既有 16 参调用
            //    末参传裸 null，两个重载（String notifyChats / PeerEnvelope）会歧义编译失败。
            null, env));

        // 别的会话 turn 捞不走（归属过滤不变）
        assertThat(queue.drainForQuery(false, null, other)).isEmpty();
        // 目标会话 turn 命中，且信封逐位保留
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(false, null, target);
        assertThat(drained).hasSize(1);
        NotificationQueue.QueueItem item = drained.get(0);
        assertThat(item.peerEnvelope()).isNotNull();
        assertThat(item.peerEnvelope().messageId()).isEqualTo(env.messageId());
        assertThat(item.peerEnvelope().fromSessionId()).isEqualTo(env.fromSessionId());
        assertThat(item.peerEnvelope().hopChain()).isEqualTo(env.hopChain());
        assertThat(item.priority()).isEqualTo(NotificationQueue.Priority.NEXT);
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("普通 prompt 项 peerEnvelope 恒 null（15 参兼容构造仍可用）")
    void plainPromptHasNoEnvelope() {
        NotificationQueue.QueueItem plain = new NotificationQueue.QueueItem(
            "hi", NotificationQueue.MODE_PROMPT,
            NotificationQueue.Priority.NEXT, null, "uuid-1", false,
            null, false, null, "sess-b2-plain",
            null, null, null, null, null);
        assertThat(plain.peerEnvelope()).isNull();
        // 旧 10 参签名仍可用（兼容壳）
        NotificationQueue.QueueItem legacy = new NotificationQueue.QueueItem(
            "hi2", NotificationQueue.MODE_PROMPT,
            NotificationQueue.Priority.NEXT, null, "uuid-2", false,
            null, false, null, "sess-b2-plain");
        assertThat(legacy.peerEnvelope()).isNull();
    }
}
