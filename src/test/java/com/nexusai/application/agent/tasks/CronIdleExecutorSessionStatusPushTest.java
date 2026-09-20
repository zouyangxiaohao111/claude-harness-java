package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.eventbus.ws.SessionStatusEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [C6] drain 起轮 / 收口推 {@code session.status} —— 前端「停止键」可见性的<b>实时信号</b>
 * （用户需求「能够 UI 中手动终止会话循环」）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：修这条缺陷时存在两个入口，一个文件的修改
 * 在另一个入口可能完全无效（本仓前科：同一文件两个入口结局相反）。本批的停止键可见性有两条独立通道：
 * <ol>
 *   <li><b>实时通道</b>（本测试）：drain 起轮推 {@code session.status=thinking} → 前端立刻显示停止键；</li>
 *   <li><b>重建通道</b>（{@code ChatControllerSessionRunningTest}）：GET /running 供 F5 / 切会话重建。</li>
 * </ol>
 * 只测「点一下就有」的纯函数会漏掉第一条：drain 路径此前<b>不推任何 status</b>，前端根本无从知道
 * 「本会话有 run 在跑」。故本测试<b>真的驱动一遍 drain 消费路径</b>
 * （{@code executeQueuedInput} → runAgentLoop，与生产同一入口），并把推出的两个事件按序钉住：
 * thinking（起轮）在前、idle（收口）在后 —— 顺序反了会让前端在 run 跑完时仍显示「运行中」。
 *
 * <p><b>手法</b>：沿用同包 {@code CronIdleExecutorBusyAttachmentSnapshotTest} 的同步执行器 + mock loop
 * 装配（不新增依赖），只多注入一个 mock wsTemplate 以捕获 STOMP 推送。
 */
@DisplayName("[C6] drain run 起轮/收口推 session.status（停止键实时信号）")
class CronIdleExecutorSessionStatusPushTest {

    private NotificationQueue queue;
    private CronIdleExecutor executor;
    private SimpMessagingTemplate ws;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        executor = new CronIdleExecutor();
        ws = mock(SimpMessagingTemplate.class);
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        ReflectionTestUtils.setField(executor, "queueEventPublisher", mock(QueueEventPublisher.class));
        ReflectionTestUtils.setField(executor, "wsTemplate", ws);
        // 同步执行器：executeQueuedInput 的 cronExecutor.execute(...) 直接在当前线程跑
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @Test
    @DisplayName("drain 消费一条排队命令 → 先推 status=thinking（起轮）再推 status=idle（收口），同一会话 topic")
    void drainRun_pushesThinkingThenIdleToSessionStreamTopic() {
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        String sessionId = "sess-c6-drain-status";
        QueueItem cmd = new QueueItem("排队追问（后台 drain 起轮）", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-c6-drain", false, "busy-queued", false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        verify(loop).run(any());
        // 事件必须落在该会话的 stream topic（前端只常驻订阅这一条）
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(ws, times(2)).convertAndSend(eq("/topic/sessions/" + sessionId + "/stream"), cap.capture());

        List<Object> payloads = cap.getAllValues();
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).isInstanceOf(SessionStatusEvent.class);
        assertThat(payloads.get(1)).isInstanceOf(SessionStatusEvent.class);
        assertThat(((SessionStatusEvent) payloads.get(0)).getStatus())
            .as("起轮必须先推 thinking —— 否则 run 卡在思考/等待权限时前端仍无停止键（本批要修的正是它）")
            .isEqualTo("thinking");
        assertThat(((SessionStatusEvent) payloads.get(1)).getStatus())
            .as("收口必须推 idle —— 否则停止键永不消失、UI 永久停在「运行中」")
            .isEqualTo("idle");
        assertThat(((SessionStatusEvent) payloads.get(0)).getSessionId()).isEqualTo(sessionId);
        assertThat(((SessionStatusEvent) payloads.get(1)).getSessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("headless / 全局哨兵会话（无前端可收）→ 不推任何 session.status（不污染他会话）")
    void globalSentinelSession_pushesNothing() {
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        // sessionId 置空 → resolveSessionUuid 返回 GLOBAL 哨兵（无前端会话可收）
        QueueItem cmd = new QueueItem("全局 cron 起轮", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-c6-global", false, "cron", false, null, null);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        verify(ws, times(0)).convertAndSend(any(String.class), any(Object.class));
    }
}
