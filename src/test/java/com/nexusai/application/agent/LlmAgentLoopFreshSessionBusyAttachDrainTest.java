package com.nexusai.application.agent;

import com.nexusai.application.agent.attachment.MediaAttachmentStore;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [attach-busy-resolve 2026-09-18] {@code freshSession} 路径构造的 ctx 必须携带附件消费 bean。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：{@code LoopSessionState} 有三个构造点，其中
 * {@link LlmAgentLoop#buildSessionStateFromInstance()}（主循环 5 参 {@code forSession} 传入）<b>设了</b>
 * {@code attachmentService/mediaAttachmentStore}，而 {@link AgentLoopContextFactory#freshSession} 产出的
 * 全新 sessionState <b>没设</b>。{@code freshSession} 是 {@code shared()}（Subagent/Hook/fork）与
 * {@code forSession} 3 参 / {@code build} fallback 的会话状态来源 ⇒ 这些路径上
 * {@code LoopSessionState.attachmentService() == null}：
 * <ul>
 *   <li>mid-turn drain 消费 busy-queued 携附件时 {@code buildMediaAttachmentNotes} 的 <b>②contentId 腿</b>
 *       （附件表 {@code getPath}）被<b>静默跳过</b> ⇒ upload 版 PDF/媒体（只有 contentId、无 path）拿不到
 *       「本地路径=…」说明，模型看不见文件在哪；</li>
 *   <li>{@code mediaAttachmentStore} 同为 null ⇒ ③ store 回退腿也失效，两条腿同时哑掉（只剩 path 直读）。</li>
 * </ul>
 *
 * <p><b>本测试怎么把「freshSession 路径」逼出来</b>：主循环 {@code run()} 恒走 5 参
 * {@code forSession}（显式传 per-run sessionState），故生产上用「丢弃传入 sessionState ⇒ build() 的
 * session==null 分支 ⇒ freshSession()」这一形态驱动真实 drain —— 这正是 {@code shared()} /
 * 3 参 {@code forSession} 的会话状态来源，走的是<b>真实</b> {@code build()} + {@code freshSession()}
 * 代码，不是测试自造 ctx。
 *
 * <p><b>反向实验</b>：去掉 {@code build()} 里的附件 bean 接线 ⇒ 本例必须变红（说明它真的钉住了接线，
 * 而不是被 {@code buildSessionStateFromInstance} 顺带救活）。
 */
@DisplayName("[attach-busy-resolve] freshSession 构造的 ctx → busy 携 contentId 附件 drain 出「本地路径=…」")
class LlmAgentLoopFreshSessionBusyAttachDrainTest {

    /**
     * 强制走 {@code freshSession} 分支的工厂：丢弃 {@code LlmAgentLoop} 传入的 per-run sessionState
     * ⇒ {@code build()} 的 {@code session == null} 分支 → {@code freshSession()} 新建。
     *
     * <p>等价生产 {@code shared(projectRoot)} / 3 参 {@code forSession} 的会话状态来源（两者都经
     * {@code build()} 内部调 {@code freshSession()}）。stream 三字段 / overridePublisher 仍按原参透传，
     * 保证 ctx 其余语义不变。
     */
    private static final class FreshSessionFactory extends AgentLoopContextFactory {
        @Override
        public AgentLoopContext forSession(String streamTopic, String streamSessionId, String streamUserMessageId,
                AgentLoopContext.LoopSessionState session,
                org.springframework.context.ApplicationEventPublisher publisher) {
            return super.forSession(streamTopic, streamSessionId, streamUserMessageId,
                (AgentLoopContext.LoopSessionState) null, publisher);
        }
    }

    private static LlmProvider stopProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("response");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("response", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    @Test
    @DisplayName("shared()（Subagent/Hook/fork 生产路径）构造的 ctx → sessionState 携带附件表 + 媒体 store")
    void sharedContext_carriesAttachmentBeans() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        MediaAttachmentStore mediaStore = new MediaAttachmentStore();
        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        ReflectionTestUtils.setField(factory, "attachmentService", attachmentService);
        ReflectionTestUtils.setField(factory, "mediaAttachmentStore", mediaStore);

        AgentLoopContext ctx = factory.shared(null);

        assertThat(ctx.sessionState().attachmentService())
            .as("shared() 经 freshSession 产出 sessionState → 附件表必须已接线"
                + "（否则 drain 的 contentId 腿静默跳过，upload 版 PDF/媒体拿不到本地路径）")
            .isSameAs(attachmentService);
        assertThat(ctx.sessionState().mediaAttachmentStore())
            .as("媒体 store 同为一等消费依赖（③ 回退腿），不得漏接线")
            .isSameAs(mediaStore);
    }

    @Test
    @DisplayName("freshSession 构造的 ctx 驱动真实 drain：携 contentId 媒体附件 → 模型侧出现「本地路径=…」")
    void freshSessionCtx_contentIdAttachment_drainProducesLocalPathNote() throws IOException {
        LlmProvider provider = stopProvider();
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        when(providerFactory.getProvider(any(), any())).thenReturn(provider);

        NotificationQueue queue = new NotificationQueue();
        // 附件表：contentId=42 → 真实落盘路径（upload 版附件形态：只有 contentId，无 path）
        Path media = Files.createTempFile("nexusai-mixed-", ".bin");
        Files.writeString(media, "media-bytes");
        String absPath = media.toAbsolutePath().normalize().toString();
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.getPath(42L)).thenReturn(absPath);

        FreshSessionFactory ctxFactory = new FreshSessionFactory();
        ctxFactory.setLlmProviderFactory(providerFactory);
        ctxFactory.setNotificationQueue(queue);
        ReflectionTestUtils.setField(ctxFactory, "attachmentService", attachmentService);
        ReflectionTestUtils.setField(ctxFactory, "mediaAttachmentStore", new MediaAttachmentStore());

        LlmAgentLoop loop = new LlmAgentLoop(providerFactory);
        loop.setImageAttachmentStore(new com.nexusai.application.agent.attachment.ImageAttachmentStore());
        loop.setContextFactory(ctxFactory);

        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AttachmentRequest mediaAttachment = new AttachmentRequest("file", "42", "会议录像.mp4",
            "video/mp4", null, null);
        queue.enqueue(busyQueued("忙时发的媒体", "msg-b-media", sid, mediaAttachment));

        AgentState state = loop.run(RunRequest.session("主消息", sid, null,
            ProviderConfig.empty(), "test-model", null, null));

        ChatMessageDto m = findById(state, "msg-b-media");
        assertThat(m).as("busy 排队项必须被 drain 注入为 user 消息").isNotNull();
        assertThat(m.role()).isEqualTo(Role.user);
        assertThat(m.content())
            .as("freshSession 产出的 sessionState 若未接线附件表，contentId 腿静默跳过 → 模型看不到文件在哪")
            .contains("本地路径=" + absPath)
            .contains("contentId=42")
            .contains("会议录像.mp4")
            .contains("忙时发的媒体");
    }

    /** 便捷构造 busy-queued QueueItem（13 参 canonical 携附件）。 */
    private static QueueItem busyQueued(String value, String uuid, String sessionId, AttachmentRequest attachment) {
        return new QueueItem(value, NotificationQueue.MODE_PROMPT, Priority.NEXT, null,
            uuid, false, "busy-queued", false, null, sessionId, null, null,
            attachment == null ? List.of() : List.of(attachment));
    }

    private static ChatMessageDto findById(AgentState state, String id) {
        if (state.rawMessages() == null) {
            return null;
        }
        for (ChatMessageDto m : state.rawMessages()) {
            if (m != null && id.equals(m.id())) {
                return m;
            }
        }
        return null;
    }
}
