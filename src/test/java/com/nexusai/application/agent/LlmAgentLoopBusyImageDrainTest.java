package com.nexusai.application.agent;

import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * [OD-D5] drain 逐项图归属（per-command 独立附件）测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>:
 * <ol>
 *   <li><b>两个 busy 图消息同 drain → 逐项图归属（不吞并）</b>：CC per-command 独立 pastedContents
 *       （attachments.ts:1060-1083 每条 queued_command 自带各自图片）；若 drain 误用共享桶 / 预登记合并，
 *       两条消息的 imagePasteIds 会互相污染（msg-b1 出现图 2 / msg-b2 出现图 1 或双图）→ 断言逐项 1:1。</li>
 *   <li><b>文本模型（无 DB mappers → supportsImage=false）busy 图走 vision_analyze 多模态提示</b>：
 *       buildUserMessageWithImages 文本路由（模型侧看到 contentId + vision_analyze 引导），DB 侧 content
 *       仍为原文（injected registry raw —— 由 ChatService 8 参 overload 落库，见
 *       ChatServiceBusyImagePersistenceTest）。双形态分别断言。</li>
 * </ol>
 *
 * <p>生产同构路径：{@link AgentLoopContextFactory}.setNotificationQueue 注入队列 bean + setImageAttachmentStore
 * 注入真实图片缓存 → 首个 turn drain（LlmAgentLoop.drainAndInjectQueued）消费两个 busy-queued。
 */
@DisplayName("[OD-D5] drain 逐项图归属 + 文本模型 busy 图多模态提示")
class LlmAgentLoopBusyImageDrainTest {

    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    @Test
    @DisplayName("两个 busy 图消息同 drain → 逐项图归属（msg-b1=图1 / msg-b2=图2，不吞并不双图）；文本模型走 vision_analyze 多模态提示")
    void twoBusyImageItems_drainPerItemNoMerge() {
        LlmProvider provider = stopProvider();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        ImageAttachmentStore store = new ImageAttachmentStore();
        loop.setImageAttachmentStore(store);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        // 两个 busy 图消息：各自独立 1 图（contentId=1 / contentId=2）
        AttachmentRequest img1 = new AttachmentRequest("image", "1", "a.png", "image/png", PNG_BASE64, null);
        AttachmentRequest img2 = new AttachmentRequest("image", "2", "b.png", "image/png", PNG_BASE64, null);
        queue.enqueue(busyQueued("排队图一", "msg-b1", sid, img1));
        queue.enqueue(busyQueued("排队图二", "msg-b2", sid, img2));

        AgentState state = loop.run(RunRequest.session("主消息", sid, null,
            ProviderConfig.empty(), "test-model", null, null));

        // [1] 逐项图归属：两条 busy user 消息各自 imagePasteIds = 自身 1 图（不吞并 / 不双图）
        ChatMessageDto m1 = findById(state, "msg-b1");
        ChatMessageDto m2 = findById(state, "msg-b2");
        assertThat(m1).as("busy 图一必须被 drain 注入为 user 消息").isNotNull();
        assertThat(m2).as("busy 图二必须被 drain 注入为 user 消息").isNotNull();
        assertThat(m1.imagePasteIds())
            .as("首条 busy 图消息只归属自身图 1（per-command 独立附件，绝不共享桶合并）")
            .containsExactly("1");
        assertThat(m2.imagePasteIds())
            .as("次条 busy 图消息只归属自身图 2（不吞并首条图）")
            .containsExactly("2");
        // queuedOrigin 标记保留（append 实时落库命中 → busy-queued 落 V67 + imagePasteIds）
        assertThat(m1.queuedOrigin()).isEqualTo("busy-queued");
        assertThat(m2.queuedOrigin()).isEqualTo("busy-queued");

        // [2] 文本模型（无 DB mappers → supportsImage=false）→ 多模态提示：模型侧 content 含
        //   contentId + vision_analyze 引导（原文嵌在「用户消息：」内）；DB 侧原文由 injected registry 承载
        assertThat(m1.content())
            .as("模型侧看到多模态提示（含 contentId + vision_analyze），文本模型据此调 vision_analyze 分析")
            .contains("contentId=1")
            .contains("vision_analyze")
            .contains("用户消息：排队图一");
        assertThat(m2.content()).contains("contentId=2").contains("vision_analyze");

        // DB 侧原文 = injected registry content（createQueuedUserMessage 8 参 overload 落 content=inj.content 原文）
        assertThat(state.injectedQueuedMessages())
            .extracting(AgentState.InjectedQueuedMessage::content)
            .containsExactly("排队图一", "排队图二");
        assertThat(state.injectedQueuedMessages())
            .extracting(AgentState.InjectedQueuedMessage::queuedOrigin)
            .containsExactly("busy-queued", "busy-queued");
    }

    /** 便捷构造 busy-queued QueueItem（13 参 canonical 携附件）。 */
    private static QueueItem busyQueued(String value, String uuid, String sessionId, AttachmentRequest attachment) {
        return new QueueItem(value, NotificationQueue.MODE_PROMPT, Priority.NEXT, null,
            uuid, false, "busy-queued", false, null, sessionId, null, null,
            attachment == null ? List.of() : List.of(attachment));
    }

    private static ChatMessageDto findById(AgentState state, String id) {
        if (state.messages() == null) {
            return null;
        }
        for (ChatMessageDto m : state.messages()) {
            if (m != null && id.equals(m.id())) {
                return m;
            }
        }
        return null;
    }
}
