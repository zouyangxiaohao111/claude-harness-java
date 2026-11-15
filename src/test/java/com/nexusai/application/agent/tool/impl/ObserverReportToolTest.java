package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ObserverReport 工具投递测试。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>观察者的报告必须带 {@code workload="observer-report"}：否则 CronIdleExecutor 会把
 * {@code mode=prompt && workload=null} 当成真实用户 prompt 排除，报告在会话空闲时永久滞留
 * （与 goal 重试同一个坑）。本用例锁死这个队列契约。
 */
@DisplayName("[agent-observer] ObserverReport 投递")
class ObserverReportToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("报告入队：workload=observer-report + 会话归属 + 含原文")
    void report_enqueuesWithWorkload() throws Exception {
        NotificationQueue queue = mock(NotificationQueue.class);
        ObserverReportTool tool = new ObserverReportTool();
        tool.setNotificationQueue(queue);

        ToolUseContext ctx = mock(ToolUseContext.class);
        when(ctx.sessionId()).thenReturn("sess-obs");

        ToolUseBlock call = new ToolUseBlock("c1", "ObserverReport",
            JSON.readTree("{\"message\":\"missing constraint X\",\"urgency\":\"high\"}"));

        ToolResult<?> r = (ToolResult<?>) tool.execute(call, ctx);

        ArgumentCaptor<NotificationQueue.QueueItem> cap =
            ArgumentCaptor.forClass(NotificationQueue.QueueItem.class);
        verify(queue).enqueue(cap.capture());
        assertThat(cap.getValue().workload()).isEqualTo(ObserverReportTool.WORKLOAD_OBSERVER_REPORT);
        assertThat(cap.getValue().sessionId()).isEqualTo("sess-obs");
        assertThat(cap.getValue().value()).contains("missing constraint X").contains("high");
        assertThat(String.valueOf(r.data())).contains("delivered");
    }

    @Test
    @DisplayName("message 为空 ⇒ 报错，不入队")
    void emptyMessage_rejected() throws Exception {
        NotificationQueue queue = mock(NotificationQueue.class);
        ObserverReportTool tool = new ObserverReportTool();
        tool.setNotificationQueue(queue);

        ToolUseBlock call = new ToolUseBlock("c2", "ObserverReport", JSON.readTree("{}"));

        ToolResult<?> r = (ToolResult<?>) tool.execute(call, mock(ToolUseContext.class));

        assertThat(String.valueOf(r.data())).contains("message is required");
        verify(queue, org.mockito.Mockito.never()).enqueue(any());
    }
}
