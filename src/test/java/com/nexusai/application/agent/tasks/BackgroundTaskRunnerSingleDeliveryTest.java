package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [单通道出站] 「<b>一次终态 ⇒ 恰好一帧</b>」的端到端（进程内）回归守卫。
 *
 * <p><b>WHY（本批合并两条通道的全部理由 · 规则九）</b>：
 * <ul>
 *   <li><b>合并前</b>：{@code BackgroundTaskRunner.emitTerminatedSdk} 独立发两条 —— ①入队
 *       （由该会话 turn 顶部 drain 推）+ ②直推 {@code /topic/tasks}。会话**正在跑 turn** 时两条都到
 *       ⇒ 同一终态推两次。前端 {@code subagentStore} 的终态活动有幂等守卫，但
 *       {@code useChatSocket} 的 {@code showToast} <b>不幂等</b> ⇒ 「任务完成」弹两次
 *       （用户可见症状）。</li>
 *   <li><b>合并后</b>：投递前移到入队点（{@link SdkEventQueue#setDeliverer}），直推整段删除 ⇒
 *       同一批事件只被取走一次 ⇒ {@code times(1)} 成立。</li>
 * </ul>
 * ⭐ 本测试是「双投不会再回来」的**唯一**守卫：只断言「有帧发出」的测试在「又加了一条通道」时
 * 仍然全绿（没判别力）—— 所以这里断言的是**次数**，不是存在性。
 *
 * <p>⚠️ 本文件取代 {@code BackgroundTaskRunnerDirectPushSessionGuardTest}（上一批「直推非空守卫」的
 * 产物，随直推通道一并删除）。它的**有价值内核被保留**：见
 * {@link #t2_blankSessionKey_noFrameAtAll()} —— 空会话键的**整帧不发**（错标到当前打开的会话比不推
 * 更坏）。守卫现在落在 {@link SdkEventQueue#enqueueSdkEvent} 的三分判据上，本测试从出站侧验证它仍然有效。
 *
 * <p>投递器接线与 {@code TaskConfiguration.backgroundTaskRunner} 的装配**同款**（同 topic、同
 * {@link SdkEventQueue#toFlatJsonNodes} 序列化）—— 测试内自装是为了不起 Spring 上下文。
 *
 * <p>纯 JUnit（⛔ 无 {@code @SpringBootTest} —— 会迁移用户真库）。
 */
@DisplayName("[单通道出站] BackgroundTaskRunner 一次终态恰好一帧")
class BackgroundTaskRunnerSingleDeliveryTest {

    private static final String TASKS_TOPIC = "/topic/tasks";

    /** 与 TaskConfiguration.LlmAgentLoop.JSON 同款裸 ObjectMapper（同一种序列化） */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** frameworkService 不带队列（null）⇒ registerForeground 不发 task_started，只测终态那一条 */
    private final TaskFrameworkService framework = new TaskFrameworkService(null);
    private final SdkEventQueue sdkEventQueue = new SdkEventQueue();
    private final BackgroundTaskRunner runner = new BackgroundTaskRunner(
        mock(NotificationQueue.class), framework, sdkEventQueue);
    private final SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);

    /** 装配「入队即投递」投递器（= TaskConfiguration 的接线，测试内自装） */
    private void wireDeliverer() {
        sdkEventQueue.setDeliverer(drained -> {
            List<JsonNode> nodes = SdkEventQueue.toFlatJsonNodes(drained, JSON);
            if (!nodes.isEmpty()) {
                ws.convertAndSend(TASKS_TOPIC, nodes);
            }
        });
    }

    /** 登记前台 bash 任务（G1-2 路径 ⇒ 可经 emitForegroundTerminal 直抵 emitTerminatedSdk） */
    private String registerForegroundTask(String sessionId) {
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING, "desc-" + taskId,
            "tu-" + taskId, System.currentTimeMillis(), null, null,
            "out-" + taskId + ".output", 0L, false, null, false);
        if (sessionId != null) {
            task = task.withSessionId(sessionId);
        }
        runner.registerForeground(task, new LocalBashTaskRunner());
        return taskId;
    }

    @Test
    @DisplayName("T1 一次终态 ⇒ 恰好一帧（List 载荷 · subtype=task_notification · session_id 非空）")
    void t1_oneTerminalTransition_exactlyOneFrame() {
        wireDeliverer();
        String sessionId = "sess-single-delivery";
        String taskId = registerForegroundTask(sessionId);

        runner.emitForegroundTerminal(taskId, BackgroundTaskStatus.COMPLETED);

        // ⭐ times(1) 是本测试的全部意义：合并前这里是 2（队列 drain 那条 + 直推那条）。
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(ws, times(1)).convertAndSend(eq(TASKS_TOPIC), payload.capture());

        Object sent = payload.getValue();
        assertThat(sent).as("统一数组形态（前端 useChatSocket.ts:447 Array.isArray(raw) ? raw : [raw]）")
            .isInstanceOf(List.class);
        List<?> nodes = (List<?>) sent;
        assertThat(nodes).hasSize(1);
        JsonNode node = (JsonNode) nodes.get(0);
        assertThat(node.get("type").asText()).isEqualTo("system");
        assertThat(node.get("subtype").asText()).isEqualTo("task_notification");
        assertThat(node.get("status").asText()).isEqualTo("completed");
        assertThat(node.get("task_id").asText()).isEqualTo(taskId);
        assertThat(node.get("session_id").asText()).as("出站 session_id 恒非空（前端按它归属）")
            .isEqualTo(sessionId);
        assertThat(node.get("uuid").asText()).as("drain 产物补 uuid（CC :96-100）").isNotBlank();
    }

    @Test
    @DisplayName("T2 空会话键 ⇒ 整帧不发（原直推守卫的内核：错标比不推更坏）")
    void t2_blankSessionKey_noFrameAtAll() {
        wireDeliverer();
        // 13 参兼容构造 ⇒ sessionId=null（standalone teammate / headless 任务的真实来源）
        String taskId = registerForegroundTask(null);

        runner.emitForegroundTerminal(taskId, BackgroundTaskStatus.COMPLETED);

        // WHY 断言「一次都没调用」而不是「没有带 null 的载荷」：只要帧发出去，前端
        //   `evt.session_id ?? sessionIdRef.current` 就会用它当前打开的会话兜底 ⇒ 错标。
        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
        assertThat(sdkEventQueue.size()).as("空键连队列都不进（三分判据在最前）").isZero();
    }

    @Test
    @DisplayName("T3 投递器缺位（装配未接线）⇒ 终态仍留桶、drain 兜底能取回")
    void t3_noDeliverer_fallbackDrainStillGetsTerminalEvent() {
        // WHY: 「直推已删」不等于「空闲路径丢了」—— 兜底仍是 drain，只是投递器缺位时才成为主路径。
        //   这条同时是反向实验（摘掉投递器装配）的观测点：它必须**仍然绿**。
        String sessionId = "sess-fallback";
        String taskId = registerForegroundTask(sessionId);

        runner.emitForegroundTerminal(taskId, BackgroundTaskStatus.COMPLETED);

        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
        List<SdkEventQueue.DrainedSdkEvent> drained = sdkEventQueue.drainSdkEvents(sessionId);
        assertThat(drained).as("兜底路径可达（否则『drain 退化为兜底』是死话）").hasSize(1);
        SdkEventQueue.TaskNotificationEvent evt =
            (SdkEventQueue.TaskNotificationEvent) drained.get(0).event();
        assertThat(evt.taskId()).isEqualTo(taskId);
        assertThat(evt.status()).isEqualTo("completed");
        assertThat(drained.get(0).sessionId()).isEqualTo(sessionId);
    }
}
