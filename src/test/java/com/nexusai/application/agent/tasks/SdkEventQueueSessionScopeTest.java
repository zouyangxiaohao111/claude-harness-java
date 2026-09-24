package com.nexusai.application.agent.tasks;

import com.nexusai.common.SessionKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2 根修 · SDK 事件队列的<b>会话作用域</b>（per-session 桶）定向测试。
 *
 * <p><b>WHY（意图验证，规则九）—— 这些行为为何重要</b>：CC 的 {@code sdkEventQueue} 是
 * <b>模块级单数组</b>（sdkEventQueue.ts:75）且 {@code drainSdkEvents()} 零参数全取（:89/:95），
 * 其正确性完全依赖「<b>进程 = 会话</b>」这个免费不变量。本仓多会话共用一进程，该不变量不存在：
 * 进程级单队列下「谁先 drain，队列里的东西就被谁取走并盖上它的会话键」，于是子代理 / 后台任务的
 * 事件会<b>错乱归属到别的会话</b>（前端按 session_id 过滤 → 本会话任务卡片消失，用户报的
 * 「子代理错乱跑到别的会话」）。
 *
 * <p>本测试锁住 C2 的三条结构不变量：
 * <ol>
 *   <li><b>跨会话隔离</b>：本会话 drain 只可能取到本会话事件（T1 / T1b / T1c）</li>
 *   <li><b>反面对照可达</b>：他会话的事件**没有被丢弃**，它自己仍能取回（防「直接丢弃外来事件」
 *       的假修复让第一条断言恒绿）</li>
 *   <li><b>桶上限/回收按会话独立</b>：一个桶满不影响另一个桶；非运行态 + 超宽限才回收（T4/T5）</li>
 * </ol>
 *
 * <p>纯 JUnit（⛔ 无 @SpringBootTest —— 会迁移用户真库）。
 */
@DisplayName("[C2] SdkEventQueue 会话作用域（per-session 桶）")
class SdkEventQueueSessionScopeTest {

    private final SdkEventQueue queue = new SdkEventQueue();

    private static SdkEventQueue.TaskStartedEvent started(String taskId) {
        return new SdkEventQueue.TaskStartedEvent(taskId, "tu-" + taskId, "desc-" + taskId,
            "local_agent", null, null);
    }

    // ── T1 跨会话隔离 + 反面对照 ─────────────────────────────────────────────

    @Test
    @DisplayName("T1 跨会话隔离：drain(sess-A) 只含 A 的；反面对照 —— B 仍能取回自己的（未丢）")
    void t1_crossSessionIsolation_withReachableCounterControl() {
        // WHY: 这是 C2 的核心断言。旧实现（进程级单 List + 全量取）下先 drain 的会话会把
        //   他会话事件一并取走并盖上自己的 session_id ⇒ 前端按 session_id 过滤时，
        //   他会话的任务卡永久消失（用户症状）。
        queue.enqueueSdkEvent("sess-B", started("task-B"));
        queue.enqueueSdkEvent("sess-A", started("task-A"));

        List<SdkEventQueue.DrainedSdkEvent> a = queue.drainSdkEvents("sess-A");

        assertThat(a).hasSize(1);
        assertThat(a.get(0).sessionId()).as("出站键 = 本会话（绝不被他会话覆盖）").isEqualTo("sess-A");
        assertThat(((SdkEventQueue.TaskStartedEvent) a.get(0).event()).taskId()).isEqualTo("task-A");

        // ⭐ 反面对照必须可达：若实现改成「把外来事件直接丢弃」以求第一条恒绿，这里会空 ⇒ 变红
        List<SdkEventQueue.DrainedSdkEvent> b = queue.drainSdkEvents("sess-B");
        assertThat(b).as("他会话事件必须原样保留在它自己的桶里（不是被丢弃）").hasSize(1);
        assertThat(b.get(0).sessionId()).isEqualTo("sess-B");
        assertThat(((SdkEventQueue.TaskStartedEvent) b.get(0).event()).taskId()).isEqualTo("task-B");
    }

    @Test
    @DisplayName("T1b 前台两会话互吞：两条都无 task 归属（前台 loop）时同样不得互吞")
    void t1b_twoForegroundSessions_doNotSwallowEachOther() {
        // WHY: 旧补丁按 ownerTaskId 过滤，而「无 task 归属」的事件（前台 loop 路径）在旧实现里
        //   走的是**全量取**分支 ⇒ 前台会话之间照样互吞。会话桶必须对「无 task 归属」一视同仁。
        queue.enqueueSdkEvent("sess-fg-1", started("fg-1"));
        queue.enqueueSdkEvent("sess-fg-2", started("fg-2"));

        assertThat(queue.drainSdkEvents("sess-fg-1"))
            .extracting(d -> ((SdkEventQueue.TaskStartedEvent) d.event()).taskId())
            .containsExactly("fg-1");
        assertThat(queue.drainSdkEvents("sess-fg-2"))
            .extracting(d -> ((SdkEventQueue.TaskStartedEvent) d.event()).taskId())
            .containsExactly("fg-2");
    }

    @Test
    @DisplayName("T1c 子代理视角：以父会话键 drain 能取到该子代理的 task_started（正面断言用户症状已关）")
    void t1c_subagentTaskVisibleOnlyToOwningSession() {
        // WHY: 子代理 loop 走 AgentLoopContextFactory.shared()（streamTopic 恒 null 但 wsTemplate 注入），
        //   旧实现下它全量取 = 会把**主会话**的任务事件取走并盖上子代理的会话键（反之亦然）。
        //   现在子代理事件归属父会话，父会话 drain 必须能拿到它，且不掺入别的会话的事件。
        queue.enqueueSdkEvent("sess-parent", started("subagent-task-1"));
        queue.enqueueSdkEvent("sess-other", started("unrelated-task"));

        List<SdkEventQueue.DrainedSdkEvent> drained = queue.drainSdkEvents("sess-parent");

        assertThat(drained).hasSize(1);
        assertThat(((SdkEventQueue.TaskStartedEvent) drained.get(0).event()).taskId())
            .isEqualTo("subagent-task-1");
        assertThat(drained.get(0).sessionId()).isEqualTo("sess-parent");
    }

    // ── T3 session_state_changed 归属 ───────────────────────────────────────

    @Test
    @DisplayName("T3 session_state_changed 归发射会话：drain(他会话) 为空、drain(发射会话) 得 1")
    void t3_sessionStateChanged_belongsToEmittingSession() {
        // WHY: session_state_changed 无 task_id ⇒ 旧 ownerTaskId 打标恒 null ⇒ 被**任意**会话的全量
        //   drain 取走并盖键（最坏情形：盖到当前打开的会话上）。C2 后它归发射会话。
        queue.enqueueSdkEvent("sess-B", new SdkEventQueue.SessionStateChangedEvent("running"));

        assertThat(queue.drainSdkEvents("sess-A")).as("他会话取不到该事件").isEmpty();

        List<SdkEventQueue.DrainedSdkEvent> b = queue.drainSdkEvents("sess-B");
        assertThat(b).hasSize(1);
        assertThat(b.get(0).event()).isInstanceOf(SdkEventQueue.SessionStateChangedEvent.class);
        assertThat(b.get(0).sessionId()).isEqualTo("sess-B");
    }

    // ── T4 桶上限变每桶 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("T4 桶上限按会话独立：一个桶塞满不影响另一个桶的事件")
    void t4_capIsPerBucket_notPerProcess() {
        // WHY: MAX_QUEUE_SIZE 若仍是全进程共享上限，一个话唠会话可以把别的会话的事件挤掉
        //   ⇒ 他会话任务卡丢失（跨会话干扰）。C2 下它是**每桶**上限（CC 的 1000 是每队列，
        //   CC 进程=会话 ⇒ 每队列 ≡ 每会话，语义一致）。
        for (int i = 0; i < SdkEventQueue.MAX_QUEUE_SIZE + 5; i++) {
            queue.enqueueSdkEvent("sess-chatty", started("c" + i));
        }
        queue.enqueueSdkEvent("sess-quiet", started("q1"));

        assertThat(queue.sizeOf("sess-chatty")).isEqualTo(SdkEventQueue.MAX_QUEUE_SIZE);
        assertThat(queue.sizeOf("sess-quiet")).as("别的会话的桶不受满桶影响").isEqualTo(1);
        assertThat(queue.drainSdkEvents("sess-quiet"))
            .extracting(d -> ((SdkEventQueue.TaskStartedEvent) d.event()).taskId())
            .containsExactly("q1");
    }

    // ── T5 桶回收（照 CC 翻译） ──────────────────────────────────────────────

    @Test
    @DisplayName("T5 非运行态 + 超宽限 ⇒ 回收该桶（丢事件 + log.warn，非静默）")
    void t5_staleBucketOfInactiveSession_getsReclaimed() {
        // WHY: CC 里队列随进程消亡（进程=会话）；本仓无进程退出 ⇒ 以「会话不再运行 + 超宽限」
        //   为等价触发点，否则死会话的桶永久占内存。且必须留痕（⛔ 不静默丢弃）。
        queue.setSessionActiveProbe(sid -> false);   // 所有会话都视为非运行态
        queue.setBucketGraceMsForTest(0L);           // 无宽限 ⇒ 立即到期
        queue.enqueueSdkEvent("sess-dead", started("orphan-task"));

        // 任一 drain 都会顺带清扫（最小侵入：drain 是队列被活跃会话维护的唯一自然时机）
        queue.drainSdkEvents("sess-live");

        assertThat(queue.sizeOf("sess-dead")).as("非运行态死会话的桶被回收").isZero();
    }

    @Test
    @DisplayName("T5b 反面对照：会话仍运行 ⇒ 桶绝不被回收（即使宽限=0）")
    void t5b_activeSession_isNeverReclaimed() {
        // WHY: 回收必须只打「确定不再运行」的会话。若判据写错（例如无条件按年龄回收），
        //   运行中会话留给本轮 turn 尾部 drain 的事件会被静默吞掉 —— 比内存泄漏严重得多。
        queue.setSessionActiveProbe(sid -> "sess-active".equals(sid));
        queue.setBucketGraceMsForTest(0L);
        queue.enqueueSdkEvent("sess-active", started("live-task"));

        queue.drainSdkEvents("sess-live");   // 触发清扫（会回收所有非运行态会话的过期桶）

        assertThat(queue.sizeOf("sess-active")).as("运行中会话的桶绝不回收").isEqualTo(1);
        assertThat(queue.drainSdkEvents("sess-active"))
            .extracting(d -> ((SdkEventQueue.TaskStartedEvent) d.event()).taskId())
            .containsExactly("live-task");
    }

    // ── 取出即移除（镜像 CC splice(0)） ─────────────────────────────────────
    @Test
    @DisplayName("drain 是『取出并移除』本会话桶（镜像 CC :95 queue.splice(0)）——二次 drain 为空")
    void drainRemovesBucket_secondDrainIsEmpty() {
        queue.enqueueSdkEvent("sess-A", started("t1"));

        assertThat(queue.drainSdkEvents("sess-A")).hasSize(1);
        assertThat(queue.drainSdkEvents("sess-A")).as("已取走的桶不得二次返回（防前端重复收帧）").isEmpty();
    }

    // ── T6 「确无会话」哨兵单点判据（与 Prompter 三分支对齐 · C2 收口） ──────

    @Test
    @DisplayName("T6 NO_SESSION 哨兵键 ⇒ 不入队（队列为空）；反面对照：真会话键照常入队")
    void t6_noSessionSentinel_isRejectedLikeNullAndBlank() {
        // WHY: 「入队即定死归属」的归属必须是**真会话**。哨兵 SessionKeys.NO_SESSION 是
        //   「确无会话」标记（SessionKeys javadoc：⛔ 不得用它冒充真实会话），不是会话键：
        //   它没有真会话的 loop 会以该键 drain（loop 的 drain 键 = state.sessionId()）⇒ 事件只能
        //   滞留到 30 分钟宽限回收（结构性不可消费，正是本仓「无效按钮」失效模式）。
        //   判据必须与 WebSocketPermissionPrompter 侧（null / blank / isNoSession 三分支）
        //   同口径，否则同一契约两支不一致。
        queue.enqueueSdkEvent(SessionKeys.NO_SESSION, started("ghost-task"));

        assertThat(queue.size()).as("哨兵键的事件不进任何桶").isZero();
        assertThat(queue.sizeOf(SessionKeys.NO_SESSION)).isZero();
        assertThat(queue.drainSdkEvents(SessionKeys.NO_SESSION)).as("无桶可取").isEmpty();

        // ⭐ 反面对照（可达性）：判据是**针对哨兵**的，不是「什么都丢」——真会话键照常入队，
        //   否则上面的 isZero 断言在「入队被整体改坏」时也会绿。
        queue.enqueueSdkEvent("sess-real", started("real-task"));
        assertThat(queue.drainSdkEvents("sess-real"))
            .extracting(d -> ((SdkEventQueue.TaskStartedEvent) d.event()).taskId())
            .containsExactly("real-task");
    }

    @Test
    @DisplayName("T6b null / 空白键同样不入队（三分判据齐全）")
    void t6b_nullAndBlankKeys_areRejected() {
        // WHY: 哨兵分支是**新增的第三支**，不得把原有两支改坏（改坏则出站 session_id 为
        //   null/空 ⇒ 前端 `evt.session_id ?? 当前会话` 错标到当前打开的会话）。
        queue.enqueueSdkEvent(null, started("null-key-task"));
        queue.enqueueSdkEvent("  ", started("blank-key-task"));

        assertThat(queue.size()).as("null 与空白键都不入队").isZero();
    }
}
