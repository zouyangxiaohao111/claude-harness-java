package com.nexusai.application.agent.tasks;

import com.nexusai.common.SessionKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [单通道出站 · 入队即投递] {@link SdkEventQueue} 投递器钩子的定向测试。
 *
 * <p><b>WHY（规则九 · 验证意图而非行为）—— 这些行为为何重要</b>：
 * 本仓原先有<b>两条</b>任务终态通道，结构上「互补又重叠」：
 * <ul>
 *   <li><b>重叠</b>：会话正在跑 turn 时，队列那条会在下一个 turn 顶部被
 *       {@code LlmAgentLoop} drain 推一次，而 {@code BackgroundTaskRunner} 的直推那条立即推一次
 *       ⇒ <b>同一终态推两次</b>。前端 {@code subagentStore} 对<b>状态</b>幂等，但
 *       {@code useChatSocket} 的 toast <b>不幂等</b> ⇒ 用户看到两次「任务完成」弹窗。</li>
 *   <li><b>互补</b>：会话空闲时没有任何 loop 会 drain 它的桶（drain 键 = {@code state.sessionId()}，
 *       只在**该会话自己**的 turn 顶部跑）⇒ 队列那条投递不到，只靠直推一条命。</li>
 * </ul>
 * 两条通道各自都「有用」，所以既不能只删直推（空闲路径丢），也不能只保直推（活跃路径双投）。
 * 根修 = 把投递时机前移到<b>入队点</b>并销毁性取走整桶（镜像 CC 2.1.281 {@code Zp.drain} 的
 * {@code splice(0)}，exe 偏移 200265xxx / drain 实现 220592956，入队监听器注册 220377666）：
 * <b>取只有一次 ⇒ 同一批事件物理上不可能被两个出口各取一遍</b>，且一条通道同时覆盖活跃与空闲会话。
 *
 * <p>本测试锁住四条结构不变量：
 * <ol>
 *   <li><b>取一次即投一次</b>（T1/T3）：投递后桶已空 ⇒ 兜底 drain 取到空 ⇒ 不双投。</li>
 *   <li><b>兜底仍可达</b>（T2/T6）：钩子为 null ⇒ 纯队列语义原封不动；投递抛异常 ⇒ 整批按原序
 *       回灌桶头，随后 drain 能取回。⭐ 没有 T6，T1 的「取走」会把「兜底」抵消成空操作
 *       （正是本仓踩过的「同一份派单书里两条要求互相抵消」陷阱）。</li>
 *   <li><b>整帧不发而非发空键帧</b>（T4）：空键拦截仍在**最前**，钩子一次都不该被调用。</li>
 *   <li><b>跨会话不互吞</b>（T5）：C2 的 per-session 桶语义在「取整桶」下同样成立。</li>
 * </ol>
 *
 * <p>纯 JUnit（⛔ 无 {@code @SpringBootTest} —— 会迁移用户真库）。
 */
@DisplayName("[单通道出站] SdkEventQueue 入队即投递")
class SdkEventQueueEnqueueDeliverTest {

    private final SdkEventQueue queue = new SdkEventQueue();

    /** 记录型假投递器：每批一次 {@code accept} ⇒ 批次列表就是「投了几次帧」 */
    private final List<List<SdkEventQueue.DrainedSdkEvent>> batches = new ArrayList<>();

    private void installRecorder() {
        queue.setDeliverer(batches::add);
    }

    private static SdkEventQueue.TaskStartedEvent started(String taskId) {
        return new SdkEventQueue.TaskStartedEvent(taskId, "tu-" + taskId, "desc-" + taskId,
            "local_agent", null, null);
    }

    private static SdkEventQueue.TaskNotificationEvent notification(String taskId) {
        return new SdkEventQueue.TaskNotificationEvent(taskId, "tu-" + taskId, "completed",
            "out-" + taskId, "summary-" + taskId, null);
    }

    // ── T1 取一次即投一次 ────────────────────────────────────────────────────

    @Test
    @DisplayName("T1 装钩子 ⇒ 入队**立即**收到整桶且桶已空（随后 drain 为空 = 不双投的结构保证）")
    void t1_installedDeliverer_receivesBucketImmediatelyAndBucketIsEmptied() {
        installRecorder();

        queue.enqueueSdkEvent("sess-1", started("t1"));

        assertThat(batches).as("入队即投递：恰好一批").hasSize(1);
        List<SdkEventQueue.DrainedSdkEvent> batch = batches.get(0);
        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).sessionId()).isEqualTo("sess-1");
        assertThat(batch.get(0).uuid()).as("drain 产物必须有 uuid（对齐 CC :96-100 补 uuid）").isNotBlank();
        assertThat(((SdkEventQueue.TaskStartedEvent) batch.get(0).event()).taskId()).isEqualTo("t1");

        assertThat(queue.sizeOf("sess-1")).as("取走即移除（镜像 drain 的 remove 语义，不留空壳桶）").isZero();
        // ⭐ 这条断言就是「物理上不可能双投」的结构证据：桶已空 ⇒ 后来的任何取（loop 兜底 drain /
        //   第二个消费者）只能拿到空。
        assertThat(queue.drainSdkEvents("sess-1"))
            .as("兜底 drain 不得二次投递同一批（否则前端 toast 弹两次）").isEmpty();
        assertThat(batches).as("drain 之后投递批次仍只有一批").hasSize(1);
    }

    // ── T2 兜底：钩子缺位 ⇒ 纯队列语义原封不动 ───────────────────────────────

    @Test
    @DisplayName("T2 钩子为 null ⇒ 不投递、事件照常留在桶里、drain 仍能取回（纯队列不回归）")
    void t2_nullDeliverer_pureQueueSemanticsUnchanged() {
        // WHY: 这是所有 `new SdkEventQueue()` 直构测试（40+ 处）赖以不变的前提，也是投递器
        //   缺位时（wsTemplate=null 的装配 / 非 Spring 单测）的唯一行为。
        assertThat(queue.deliverer()).as("直构默认无投递器").isNull();

        queue.enqueueSdkEvent("sess-2", started("t2"));

        assertThat(batches).as("钩子缺位 ⇒ 一次都不投").isEmpty();
        assertThat(queue.drainSdkEvents("sess-2"))
            .extracting(d -> ((SdkEventQueue.TaskStartedEvent) d.event()).taskId())
            .containsExactly("t2");
    }

    // ── T3 连续入队 ⇒ 两次投递，各一批，桶无残留 ─────────────────────────────

    @Test
    @DisplayName("T3 同一会话连续两次终态 ⇒ 投递器收到 2 批（各 1 条），且桶已被取空")
    void t3_twoTerminalEvents_sameSession_twoBatches_noResidue() {
        installRecorder();

        queue.emitTaskTerminatedSdk("sess-3", "task-a", "completed", null);
        queue.emitTaskTerminatedSdk("sess-3", "task-b", "failed", null);

        assertThat(batches).as("两次终态 = 两批（不是一批一帧一天顶两次）").hasSize(2);
        assertThat(batches.get(0)).hasSize(1);
        assertThat(batches.get(1)).hasSize(1);
        assertThat(((SdkEventQueue.TaskNotificationEvent) batches.get(0).get(0).event()).taskId())
            .isEqualTo("task-a");
        assertThat(((SdkEventQueue.TaskNotificationEvent) batches.get(1).get(0).event()).taskId())
            .isEqualTo("task-b");
        assertThat(queue.drainSdkEvents("sess-3")).as("两批都已被取走，桶里无残留").isEmpty();
    }

    // ── T4 空键拦截仍在最前（回归既有守卫） ──────────────────────────────────

    @Test
    @DisplayName("T4 null/blank/NO_SESSION 哨兵 ⇒ 钩子一次都没被调用（整帧不发，不是发空键帧）")
    void t4_invalidSessionKeys_neitherDeliveredNorQueued() {
        // WHY: 原直推通道自带空键守卫（错标到当前会话比不推更坏），那条通道已删 ⇒ 守卫必须**完整**
        //   落在 enqueue 的三分判据上，否则会退化成「发了一条 session_id 为空的帧」。
        installRecorder();

        queue.enqueueSdkEvent(null, started("null-key"));
        queue.enqueueSdkEvent("   ", started("blank-key"));
        queue.enqueueSdkEvent(SessionKeys.NO_SESSION, started("sentinel-key"));

        assertThat(batches).as("空键 ⇒ 整帧不发（连投递机会都没有）").isEmpty();
        assertThat(queue.size()).as("也不入队").isZero();

        // ⭐ 反面对照（可达性）：判据是**针对空键**的，不是「什么都拦」——否则上面的 isEmpty
        //   在「入队/投递被整体改坏」时也会绿。
        queue.enqueueSdkEvent("sess-ok", started("ok"));
        assertThat(batches).as("真会话键照常投递").hasSize(1);
        assertThat(batches.get(0).get(0).sessionId()).isEqualTo("sess-ok");
    }

    // ── T5 跨会话隔离（C2 回归 · 取整桶不得卷进别会话） ──────────────────────

    @Test
    @DisplayName("T5 两会话交替入队 ⇒ 每次投递只含本次会话的事件（取整桶不跨桶）")
    void t5_alternatingSessions_eachBatchContainsOnlyItsOwnSession() {
        // WHY: 投递器取的是「**该会话**整桶」（C2 归属结构不变）。若实现改成「无条件取全部桶」
        //   （图省事的写法），跨会话互吞就会以新形态回归 —— 这条断言把它钉住。
        installRecorder();

        queue.enqueueSdkEvent("sess-A", started("a1"));
        queue.enqueueSdkEvent("sess-B", started("b1"));
        queue.enqueueSdkEvent("sess-A", started("a2"));

        assertThat(batches).hasSize(3);
        assertThat(batches).extracting(b -> b.get(0).sessionId())
            .containsExactly("sess-A", "sess-B", "sess-A");
        assertThat(batches).allSatisfy(b -> assertThat(b).as("每批只含 1 条（不卷进他会话）").hasSize(1));
    }

    // ── T6 ⭐ 兜底可达性：投递失败 ⇒ 回灌桶头，drain 取回 ────────────────────

    @Test
    @DisplayName("T6 投递器抛异常 ⇒ 整批按原序回灌桶头，随后 drain 能取回（兜底不是死代码）")
    void t6_delivererThrows_eventsRequeuedAtHead_fallbackDrainReachable() {
        // WHY: 若没有回灌，「入队即破坏性取走」会把「drain 退化为兜底」这条要求**抵消**成
        //   「投递器一挂，事件凭空消失」—— 即本仓踩过的「两条要求互相抵消」陷阱。
        queue.enqueueSdkEvent("sess-6", started("first"));   // 无钩子：先留桶（模拟积压）
        assertThat(queue.sizeOf("sess-6")).isEqualTo(1);

        queue.setDeliverer(b -> {
            throw new IllegalStateException("模拟出站失败");
        });
        queue.enqueueSdkEvent("sess-6", started("second"));  // 取整桶 [first, second] → 抛 → 回灌

        assertThat(queue.drainSdkEvents("sess-6"))
            .as("回灌后兜底 drain 必须取回整批且顺序不变")
            .extracting(d -> ((SdkEventQueue.TaskStartedEvent) d.event()).taskId())
            .containsExactly("first", "second");
        assertThat(queue.sizeOf("sess-6")).as("兜底取走后不留残留").isZero();
    }

    // ── T7 重入安全：投递器在回调里再入队不得死锁/丢事件 ─────────────────────

    @Test
    @DisplayName("T7 投递器回调中再入队（模拟 convertAndSend 回环）⇒ 不死锁、两批都投出")
    void t7_reentrantEnqueueInsideDeliverer_doesNotDeadlockOrLose() {
        // WHY: 投递必须在**锁外**进行 —— 若实现把 convertAndSend 放在 synchronized 块内，这里会
        //   死锁（本仓有 STOMP 出站线程被占住的前科）。后端 /topic/tasks 目前无 in-process 消费者
        //   （生产者单点），但这是结构性护栏，不是假设。
        List<String> delivered = new ArrayList<>();
        queue.setDeliverer(b -> {
            delivered.add(b.get(0).sessionId());
            if (b.get(0).sessionId().equals("sess-outer")) {
                queue.enqueueSdkEvent("sess-inner", started("inner"));
            }
        });

        queue.enqueueSdkEvent("sess-outer", started("outer"));

        assertThat(delivered).as("外层 + 回调内入队的内层都必须投出").containsExactly("sess-outer", "sess-inner");
        assertThat(queue.drainSdkEvents("sess-inner")).isEmpty();
    }
}
