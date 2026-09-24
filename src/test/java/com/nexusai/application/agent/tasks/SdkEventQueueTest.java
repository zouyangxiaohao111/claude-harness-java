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
        queue.enqueueSdkEvent("sess-1", new SdkEventQueue.TaskStartedEvent("t1", "tu1", "desc", "local_bash", null, null));

        List<SdkEventQueue.DrainedSdkEvent> drained = queue.drainSdkEvents("sess-1");

        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).uuid()).isNotBlank();
        assertThat(drained.get(0).sessionId()).isEqualTo("sess-1");
        assertThat(drained.get(0).event()).isInstanceOf(SdkEventQueue.TaskStartedEvent.class);
    }

    @Test
    @DisplayName("满 MAX_QUEUE_SIZE=1000 shift 最旧（CC :83-85 · C2 起为每会话桶上限）")
    void capShift_dropsOldestWhenOver1000() {
        // WHY: 无界堆积 = 内存泄漏；shift 保证桶有界。C2 起 MAX_QUEUE_SIZE 是**每会话桶**上限
        //   （CC 的 1000 是每队列，而 CC 进程=会话 ⇒ 每队列 ≡ 每会话，语义一致）。
        for (int i = 0; i < SdkEventQueue.MAX_QUEUE_SIZE + 1; i++) {
            queue.enqueueSdkEvent("sess", new SdkEventQueue.TaskStartedEvent("t" + i, null, "d", null, null, null));
        }

        List<SdkEventQueue.DrainedSdkEvent> drained = queue.drainSdkEvents("sess");

        assertThat(drained).hasSize(SdkEventQueue.MAX_QUEUE_SIZE);
        // 最旧的 t0 被 shift 掉，队首是 t1
        assertThat(((SdkEventQueue.TaskStartedEvent) drained.get(0).event()).taskId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("交互会话 gate 拦截入队（CC :80-82）")
    void interactiveGate_blocksEnqueue() {
        // WHY: TUI 等价场景事件永不消费，入队纯浪费；gate 关闭后必须零入队
        queue.setNonInteractiveSession(false);
        queue.enqueueSdkEvent("sess", new SdkEventQueue.TaskStartedEvent("t1", null, "d", null, null, null));

        assertThat(queue.drainSdkEvents("sess")).isEmpty();
    }

    @Test
    @DisplayName("空队列 drain 返回空列表（CC :92-94）")
    void drainEmpty_returnsEmptyList() {
        assertThat(queue.drainSdkEvents("sess")).isEmpty();
    }

    @Test
    @DisplayName("emitTaskTerminatedSdk 缺省 output_file/summary=''（CC :130-131）")
    void emitTaskTerminatedSdk_defaultsEmptyOutputFileAndSummary() {
        queue.emitTaskTerminatedSdk("sess", "t1", "completed", null);

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
        queue.emitTaskProgress("sess", "t1", "tu1", "desc", start, 500, 3, "Read", null);

        SdkEventQueue.TaskProgressEvent evt =
            (SdkEventQueue.TaskProgressEvent) queue.drainSdkEvents("sess").get(0).event();

        assertThat(evt.usage().totalTokens()).isEqualTo(500);
        assertThat(evt.usage().toolUses()).isEqualTo(3);
        assertThat(evt.usage().durationMs()).isGreaterThanOrEqualTo(1000);
    }

    /**
     * ⚠️ <b>契约变更（C2 · 有意推翻旧语义，不得悄悄绿）</b>
     *
     * <p><b>旧契约</b>（本用例改写前的 {@code drainByOwnerTaskId_onlyDrainsOwningTaskEvents}）：
     * 「队列是进程级单 List；入队打 ownerTaskId；drain(sessionId, ownerTaskId) 按**任务**过滤——
     * 后台 loop 传自身 taskId 只取本任务事件，其余留队，再由前台 loop 的**全量 drain** 取走并
     * **盖上它的 session_id**」。该契约把「他会话事件留队后被本会话盖键取走」当作<b>正确</b>行为。
     *
     * <p><b>新契约</b>（C2）：归属按**会话**而非任务。入队即定死会话键、每会话一个桶、
     * {@code drainSdkEvents(sessionId)} 只取本会话桶 ⇒ <b>任何会话都取不到别人的事件</b>
     * （不存在「他会话事件留队等着被谁盖键取走」这一态）。旧语义正是用户症状「子代理错乱跑到
     * 别的会话」的结构成因，故<b>有意推翻</b>；任务维度（ownerTaskId）整块删除。
     */
    @Test
    @DisplayName("[契约变更 C2] 按会话隔离 drain：本会话只取本会话桶，绝不吞他会话事件（取代旧 drainByOwnerTaskId）")
    void drainBySession_isolationReplacesOwnerTaskIdContract() {
        // WHY: 旧「按 ownerTaskId 过滤 + 前台全量取盖章」在子代理 loop / fork / hook / teammate
        //   路径上全部退化为全量取（那些路径拿不到 taskId 来源）⇒ 谁先 drain 谁把别人事件盖成
        //   自己的会话键。按会话分桶后该结构不再存在。
        queue.enqueueSdkEvent("sess-x",
            new SdkEventQueue.TaskStartedEvent("task-bg", "tu1", "bg desc", "local_agent", null, null));
        queue.enqueueSdkEvent("sess-y",
            new SdkEventQueue.TaskStartedEvent("task-fg", "tu2", "fg desc", "local_bash", null, null));
        queue.enqueueSdkEvent("sess-y", new SdkEventQueue.SessionStateChangedEvent("running"));

        // 本会话（sess-x）只取到自己的 1 条
        List<SdkEventQueue.DrainedSdkEvent> mine = queue.drainSdkEvents("sess-x");
        assertThat(mine).hasSize(1);
        assertThat(((SdkEventQueue.TaskStartedEvent) mine.get(0).event()).taskId()).isEqualTo("task-bg");
        assertThat(mine.get(0).sessionId()).isEqualTo("sess-x");

        // 他会话（sess-y）的事件**未被本会话取走**：sess-y 自己仍能原样取回 2 条（反面对照可达）
        List<SdkEventQueue.DrainedSdkEvent> theirs = queue.drainSdkEvents("sess-y");
        assertThat(theirs).hasSize(2);
        assertThat(theirs).anyMatch(e -> e.event() instanceof SdkEventQueue.TaskStartedEvent t
            && "task-fg".equals(t.taskId()));
        assertThat(theirs).anyMatch(e -> e.event() instanceof SdkEventQueue.SessionStateChangedEvent);
        // 且它们的 session_id 仍是 sess-y（未被盖上 sess-x）
        assertThat(theirs).allMatch(e -> "sess-y".equals(e.sessionId()));
    }

    @Test
    @DisplayName("空会话键入队 fail-loud 丢弃（C2：出站 session_id 恒非空）")
    void enqueueWithBlankSession_dropsAndDoesNotStampEmptyKey() {
        // WHY: 空键入队 → 出站 `session_id: null` → 前端 `evt.session_id ?? 当前会话` 会把事件
        //   错标到**当前打开的会话**（比丢弃更坏）。故入队处拦下并留痕。
        queue.enqueueSdkEvent(null, new SdkEventQueue.TaskStartedEvent("t1", null, "d", null, null, null));
        queue.enqueueSdkEvent("  ", new SdkEventQueue.TaskStartedEvent("t2", null, "d", null, null, null));

        assertThat(queue.size()).as("空会话键事件不得入任何桶").isZero();
    }

    @Test
    @DisplayName("出站 JSON：snake_case + uuid/session_id 平级 + session_id 恒在（C2 不再省略）")
    void toFlatJsonNodes_flattensWithSnakeCaseAndOmitsNulls() {
        queue.enqueueSdkEvent("sess-1", new SdkEventQueue.TaskStartedEvent("t1", "tu1", "desc", "local_bash", null, null));

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
