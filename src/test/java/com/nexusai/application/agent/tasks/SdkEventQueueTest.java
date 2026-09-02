package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDK 事件队列语义定向测试 · 对齐 CC utils/sdkEventQueue.ts（134L）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>cap shift（:83-85）</b>——TUI/非流式会话事件永不被 drain，若无 1000 上限会无界堆积
 *       （内存泄漏）；shift 掉最旧保证有界。</li>
 *   <li><b>gate（:80-82）</b>——SDK 事件仅 headless/streaming 消费（CC 注释明言），交互会话
 *       入队纯浪费；gate 缺省会放行（CC isInteractive 恒 false 无生产 setter）。</li>
 *   <li><b>drain 补 uuid/session_id（:96-100）</b>——前端按 session_id 过滤跨会话事件，
 *       uuid 供去重；漏 stamp 则前端无法归属会话。</li>
 *   <li><b>扁平 JSON（spread 展开）</b>——uuid/session_id 必须与事件字段<b>平级</b>非嵌套，
 *       否则 /topic/tasks 契约（待前端联调.md）的解析方按顶层字段取数会全空。</li>
 * </ul>
 */
@DisplayName("[OPD-TS-22] SDK 事件队列语义（对齐 CC sdkEventQueue.ts）")
class SdkEventQueueTest {

    private final SdkEventQueue queue = new SdkEventQueue();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("enqueue→drain 补 uuid + drain 会话 session_id（CC :96-100）")
    void enqueueAndDrain_stampsUuidAndDrainSessionId() {
        // WHY: 前端按 session_id 归属会话、按 uuid 去重；漏 stamp 则跨会话事件无法过滤
        queue.enqueueSdkEvent(new SdkEventQueue.TaskStartedEvent("t1", "tu1", "desc", "local_bash", null, null));

        List<SdkEventQueue.DrainedSdkEvent> drained = queue.drainSdkEvents("sess-1");

        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).uuid()).isNotBlank();
        assertThat(drained.get(0).sessionId()).isEqualTo("sess-1");
        assertThat(drained.get(0).event()).isInstanceOf(SdkEventQueue.TaskStartedEvent.class);
    }

    @Test
    @DisplayName("满 MAX_QUEUE_SIZE=1000 shift 最旧（CC :83-85）")
    void capShift_dropsOldestWhenOver1000() {
        // WHY: 无界堆积 = 内存泄漏；shift 保证队列有界
        for (int i = 0; i < SdkEventQueue.MAX_QUEUE_SIZE + 1; i++) {
            queue.enqueueSdkEvent(new SdkEventQueue.TaskStartedEvent("t" + i, null, "d", null, null, null));
        }

        List<SdkEventQueue.DrainedSdkEvent> drained = queue.drainSdkEvents(null);

        assertThat(drained).hasSize(SdkEventQueue.MAX_QUEUE_SIZE);
        // 最旧的 t0 被 shift 掉，队首是 t1
        assertThat(((SdkEventQueue.TaskStartedEvent) drained.get(0).event()).taskId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("交互会话 gate 拦截入队（CC :80-82）")
    void interactiveGate_blocksEnqueue() {
        // WHY: TUI 等价场景事件永不消费，入队纯浪费；gate 关闭后必须零入队
        queue.setNonInteractiveSession(false);
        queue.enqueueSdkEvent(new SdkEventQueue.TaskStartedEvent("t1", null, "d", null, null, null));

        assertThat(queue.drainSdkEvents(null)).isEmpty();
    }

    @Test
    @DisplayName("空队列 drain 返回空列表（CC :92-94）")
    void drainEmpty_returnsEmptyList() {
        assertThat(queue.drainSdkEvents("sess")).isEmpty();
    }

    @Test
    @DisplayName("emitTaskTerminatedSdk 缺省 output_file/summary=''（CC :130-131）")
    void emitTaskTerminatedSdk_defaultsEmptyOutputFileAndSummary() {
        queue.emitTaskTerminatedSdk("t1", "completed", null);

        SdkEventQueue.TaskNotificationEvent evt =
            (SdkEventQueue.TaskNotificationEvent) queue.drainSdkEvents("sess").get(0).event();

        assertThat(evt.status()).isEqualTo("completed");
        assertThat(evt.outputFile()).isEmpty();
        assertThat(evt.summary()).isEmpty();
        assertThat(evt.usage()).isNull();
    }

    @Test
    @DisplayName("emitTaskProgress 按 startTime 计算 duration_ms（sdkProgress.ts:30）")
    void emitTaskProgress_computesUsageFromStartTime() {
        long start = System.currentTimeMillis() - 1000;
        queue.emitTaskProgress("t1", "tu1", "desc", start, 500, 3, "Read", null);

        SdkEventQueue.TaskProgressEvent evt =
            (SdkEventQueue.TaskProgressEvent) queue.drainSdkEvents("sess").get(0).event();

        assertThat(evt.usage().totalTokens()).isEqualTo(500);
        assertThat(evt.usage().toolUses()).isEqualTo(3);
        assertThat(evt.usage().durationMs()).isGreaterThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("按 ownerTaskId 过滤 drain：后台 loop 只取本任务事件，不吞前台/他会话事件（RK-w5-2）")
    void drainByOwnerTaskId_onlyDrainsOwningTaskEvents() {
        // WHY: SdkEventQueue 为进程级单例队列，前台 loop（ChatService）与后台 loop
        //   （startBackgroundSession）并发 drain 时全取会"互吞"——后台 loop 若取走前台/他会话
        //   任务事件，会盖成后台 session_id（跨会话错标，前端按 session_id 过滤就漏了本会话任务）。
        //   enqueue 打标 ownerTaskId + drain 按任务过滤，保证后台 loop 只取自身 task 的事件。
        queue.enqueueSdkEvent(new SdkEventQueue.TaskStartedEvent("task-bg", "tu1", "bg desc", "local_agent", null, null));
        queue.enqueueSdkEvent(new SdkEventQueue.TaskStartedEvent("task-fg", "tu2", "fg desc", "local_bash", null, null));
        queue.enqueueSdkEvent(new SdkEventQueue.SessionStateChangedEvent("running"));

        // 后台 loop 按自身 task 过滤 drain：只取 task-bg 事件，盖后台 session_id
        List<SdkEventQueue.DrainedSdkEvent> bg = queue.drainSdkEvents("sess-x", "task-bg");
        assertThat(bg).hasSize(1);
        assertThat(((SdkEventQueue.TaskStartedEvent) bg.get(0).event()).taskId()).isEqualTo("task-bg");
        assertThat(bg.get(0).sessionId()).isEqualTo("sess-x");

        // 其余（task-fg + session_state_changed）必须留队，前台 loop 全量 drain 取回
        List<SdkEventQueue.DrainedSdkEvent> rest = queue.drainSdkEvents("sess-y");
        assertThat(rest).hasSize(2);
        assertThat(rest).anyMatch(e -> e.event() instanceof SdkEventQueue.TaskStartedEvent t
            && "task-fg".equals(t.taskId()));
        assertThat(rest).anyMatch(e -> e.event() instanceof SdkEventQueue.SessionStateChangedEvent);
    }

    @Test
    @DisplayName("出站 JSON：snake_case + uuid/session_id 平级 + null 省略")
    void toFlatJsonNodes_flattensWithSnakeCaseAndOmitsNulls() {
        queue.enqueueSdkEvent(new SdkEventQueue.TaskStartedEvent("t1", "tu1", "desc", "local_bash", null, null));

        List<JsonNode> nodes = SdkEventQueue.toFlatJsonNodes(queue.drainSdkEvents("sess-1"), mapper);
        JsonNode n = nodes.get(0);

        // CC sdkEventQueue.ts:96-100 spread：uuid/session_id 与事件字段平级
        assertThat(n.get("type").asText()).isEqualTo("system");
        assertThat(n.get("subtype").asText()).isEqualTo("task_started");
        assertThat(n.get("task_id").asText()).isEqualTo("t1");
        assertThat(n.get("tool_use_id").asText()).isEqualTo("tu1");
        assertThat(n.get("task_type").asText()).isEqualTo("local_bash");
        assertThat(n.get("uuid").asText()).isNotBlank();
        assertThat(n.get("session_id").asText()).isEqualTo("sess-1");
        // Java 无对应字段（null）→ NON_NULL 省略，避免前端收到 null 噪音
        assertThat(n.has("workflow_name")).isFalse();
        assertThat(n.has("prompt")).isFalse();
    }
}
