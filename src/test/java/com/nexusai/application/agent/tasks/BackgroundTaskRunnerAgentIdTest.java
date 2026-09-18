package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.bash.ShellResolver;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator-align] 后台任务完成通知必须携带 <b>任务归属 subagent 的 agentId</b>。
 *
 * <p><b>WHY（规则九 · 测试验证意图，而非行为）</b>：本测试守护的不是「某个参数被填了」这个动作，
 * 而是「通知能被<b>正确的消费者</b>领取」这一后果。
 * {@link NotificationQueue#drainForQuery(boolean, String, String)} 的隔离规则：
 * <ul>
 *   <li>主线程（{@code currentAgentId == null}）只捞 {@code cmd.agentId() == null} 的通知；</li>
 *   <li>子代理只捞 {@code mode == task-notification && cmd.agentId() == 自己} 的通知。</li>
 * </ul>
 * 因此若子代理 spawn 的后台任务通知以 {@code agentId = null} 入队，它会<b>必然</b>被主线程捞走，
 * 子代理永远收不到 —— 这正是用户上报的现象（子代理跑的后台 bash 完成了，结果通知跑到主代理那里）。
 * 该后果对模型是可见的功能错误：子代理以为自己还在等命令，主代理却收到一条不属于自己的完成通知，
 * 双方上下文都被污染。所以本类断言的是「入队项的 agentId 等于任务归属 agent 的 agentId」，
 * 以及由此推出的「主线程 drain 捞不到它、归属代理 drain 能捞到它」。
 *
 * <p><b>反向断言（防「凭空编造」）</b>：主会话任务（{@code agentId == null}，如主代理自己
 * run_in_background 的 bash）的通知必须<b>仍为 null</b>。若实现改为「总是填一个非 null 值」
 * （例如回落全局 / 编造 UUID），主会话通知会变成任何消费者都捞不到的孤儿 —— 那是比原缺陷更坏的
 * 静默丢失。<b>本类两条用例互为约束：一条防「漏带」，一条防「带错」。</b>
 */
@DisplayName("[coordinator-align] BackgroundTaskRunner 通知 agentId 归属透传")
class BackgroundTaskRunnerAgentIdTest {

    /** 创建会话 id（spawn 的 createSessionId 必填 —— 批 3b-D7 签名收紧）。 */
    private static final String SESSION = "sess-agentid-fixture";

    @TempDir
    Path tempDir;

    private final TaskFrameworkService framework = new TaskFrameworkService(null);

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.sessionId");
        com.nexusai.application.agent.agent.SessionCwdHolder.reset();
    }

    /** 是否有可用 bash/zsh（Windows 走 Git Bash）· 无 shell 则跳过。 */
    private static boolean shellAvailable() {
        try {
            ShellResolver.resolveShell();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 轮询等待队列收到任意项（对齐既有 runner 测试 awaitUntil 模式）。 */
    private static void awaitQueueNotEmpty(NotificationQueue queue, String desc) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (queue.peek(q -> true).isPresent()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + desc, e);
            }
        }
        throw new AssertionError("等待超时: " + desc + "（队列始终为空）");
    }

    private BackgroundTaskRunner newRunner(NotificationQueue nq) {
        return new BackgroundTaskRunner(nq, framework, null);
    }

    /**
     * 构造带归属 subagent 的后台 bash 任务（20 参 canonical 构造器，与
     * {@code registerAsyncAgent} 同形；sessionId 留 null → 由 spawn 回填，走真实路径）。
     */
    private BackgroundTask newBashTaskOwnedBy(UUID agentId, String taskId, String command) {
        return new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            command, "tu-" + taskId,
            System.currentTimeMillis(), null, null,
            tempDir.resolve(taskId + ".out").toString(), 0L, false,
            agentId,   // ← 归属 agent（子代理 spawn → 非 null；主会话 spawn → null）
            true,      // isBackgrounded
            null,      // sessionId（spawn 回填 createSessionId）
            null, null, null, null, null, null);
    }

    @Test
    @DisplayName("子代理 spawn 的后台 bash 完成 → 通知 agentId = 归属 agentId，且主线程捞不到、归属代理捞得到")
    void subagentOwnedTask_notificationIsClaimableByOwnerOnly() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash/zsh（ShellResolver 找不到则跳过）");
        NotificationQueue nq = new NotificationQueue();
        BackgroundTaskRunner runner = newRunner(nq);

        UUID owner = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String command = "echo agentid-owner; exit 0";
        runner.spawn(newBashTaskOwnedBy(owner, "agentid-owned-" + UUID.randomUUID(), command),
            command, SESSION);
        awaitQueueNotEmpty(nq, "子代理后台 bash 完成通知入队");

        QueueItem item = nq.peek(q -> true).orElseThrow();
        assertThat(item.mode())
            .as("完成通知 mode=task-notification（drain 隔离规则只对 task-notification 生效）")
            .isEqualTo(NotificationQueue.MODE_TASK_NOTIFICATION);
        assertThat(item.agentId())
            .as("子代理任务的通知必须带归属 agentId —— 否则按 drainForQuery 规则必然被主线程捞走，"
                + "子代理永远收不到（这正是上报的现象）")
            .isEqualTo(owner.toString());

        // 后果断言 1：主线程（currentAgentId == null）不得捞到子代理的通知
        List<QueueItem> mainDrained = nq.drainForQuery(false, null, SESSION);
        assertThat(mainDrained)
            .as("主线程 drain 必须捞不到子代理归属的通知（否则通知错投主代理 = 缺陷本身）")
            .isEmpty();

        // 后果断言 2：归属子代理自己 drain 得到 —— 通知真正可达
        List<QueueItem> ownerDrained = nq.drainForQuery(false, owner.toString(), SESSION);
        assertThat(ownerDrained)
            .as("归属子代理 drain 必须捞到自己的通知 —— 这是本修复要恢复的能力")
            .hasSize(1);
        assertThat(ownerDrained.get(0).agentId()).isEqualTo(owner.toString());
    }

    @Test
    @DisplayName("主会话 spawn 的后台 bash 完成 → 通知 agentId 仍为 null（不得凭空编造），主线程捞得到")
    void mainSessionTask_notificationKeepsNullAgentId() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash/zsh（ShellResolver 找不到则跳过）");
        NotificationQueue nq = new NotificationQueue();
        BackgroundTaskRunner runner = newRunner(nq);

        String command = "echo agentid-main; exit 0";
        runner.spawn(newBashTaskOwnedBy(null, "agentid-main-" + UUID.randomUUID(), command),
            command, SESSION);
        awaitQueueNotEmpty(nq, "主会话后台 bash 完成通知入队");

        QueueItem item = nq.peek(q -> true).orElseThrow();
        assertThat(item.agentId())
            .as("主会话任务无归属 agent —— agentId 必须保持 null。填任何非 null 值都会让通知变成"
                + "谁都捞不到的孤儿（比原缺陷更坏的静默丢失）")
            .isNull();

        // 后果断言：主线程 drain 捞得到主会话自己的通知
        List<QueueItem> mainDrained = nq.drainForQuery(false, null, SESSION);
        assertThat(mainDrained)
            .as("主会话任务的完成通知必须仍能被主线程捞到（本条防「改过头」）")
            .hasSize(1);
        assertThat(mainDrained.get(0).agentId()).isNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ④ killShellTasksForAgent 的「收尸」半边：agent 退出时必须清掉
    //    已入队、但<b>再无消费者</b>的归属该 agent 的通知。
    //
    // CC 真源（实测）：Open-ClaudeCode/src/tasks/LocalShellTask/killShellTasks.ts:71-75
    //     // Purge any queued notifications addressed to this agent — its query loop
    //     // has exited and won't drain them. ...
    //     dequeueAllMatching(cmd => cmd.agentId === agentId)
    // 该函数与上面的「杀任务」循环同属一个函数体，在循环<b>之后无条件执行</b>
    // （即便 killed==0 也执行）—— 见 killShellTasks.ts:53-76。
    //
    // WHY 必须钉住：④ 与 ①② 是一根链条。①② 一旦给 shell task 接上 agentId，
    // killShellTasksForAgent（今日因 agentId 恒 null 而结构性 no-op）会真的开始杀任务
    // （这是 CC 要的），但<b>只杀不清队列</b>时，「子代理先退出、后台 bash 后完成」的完成
    // 通知会滞留在队列里成为没人捞的孤儿（主线程按规则捞不到、子代理已死）。孤儿通知是
    // 静默错误：不报错、不消费、白占队列，直到进程重启。
    // ══════════════════════════════════════════════════════════════════════

    /** 造一条归属指定 agent 的 task-notification 入队项（值的具体内容与判据无关）。 */
    private static QueueItem notificationAddressedTo(UUID owner) {
        return new NotificationQueue.QueueItem(
            "<task_notification>probe</task_notification>", NotificationQueue.MODE_TASK_NOTIFICATION,
            NotificationQueue.Priority.NEXT,
            owner == null ? null : owner.toString());
    }

    @Test
    @DisplayName("④ killShellTasksForAgent：清掉归属该 agent 的已入队通知，且只清它（不误伤别人的）")
    void killShellTasksForAgent_purgesQueuedNotificationsOfExitingAgent() {
        // 本用例不跑 shell：杀任务那半边由下面的集成用例覆盖；此处只钉「清队列」这一新行为，
        // 且 CC 的 dequeue 在 killed==0 时同样执行（killShellTasks.ts:75 在循环外无条件）。
        NotificationQueue nq = new NotificationQueue();
        BackgroundTaskRunner runner = newRunner(nq);

        UUID exiting = UUID.fromString("cccccccc-1111-2222-3333-444444444444");
        UUID other = UUID.fromString("dddddddd-1111-2222-3333-444444444444");
        nq.enqueuePendingNotification(notificationAddressedTo(exiting));
        nq.enqueuePendingNotification(notificationAddressedTo(other));
        nq.enqueuePendingNotification(notificationAddressedTo(null)); // 主线程通知

        int killed = runner.killShellTasksForAgent(exiting);

        assertThat(killed).as("无 running 任务 ⇒ 杀 0 个（但清队列仍必须执行）").isZero();
        assertThat(nq.peek(q -> exiting.toString().equals(q.agentId())))
            .as("归属退出 agent 的通知必须被清掉 —— 其实例 query loop 已退出，再无消费者；"
                + "留着就是没人捞的孤儿通知（CC killShellTasks.ts:75 dequeueAllMatching）")
            .isEmpty();
        assertThat(nq.peek(q -> other.toString().equals(q.agentId())))
            .as("别人的通知必须留下 —— 谓词只能按 agentId 精确匹配，不得退化成 dequeueAll")
            .isPresent();
        assertThat(nq.peek(q -> q.agentId() == null))
            .as("主线程通知必须留下 —— agentId==null 不属于任何退出 agent")
            .isPresent();
    }

    @Test
    @DisplayName("④ killShellTasksForAgent：RUNNING 的归属任务被真杀 + 其终态通知一并收回（不留孤儿）")
    void killShellTasksForAgent_killsRunningOwnedTaskAndDoesNotLeaveOrphanNotification() throws Exception {
        Assumptions.assumeTrue(shellAvailable(), "需要可用 bash/zsh（ShellResolver 找不到则跳过）");
        NotificationQueue nq = new NotificationQueue();
        BackgroundTaskRunner runner = newRunner(nq);

        UUID owner = UUID.fromString("eeeeeeee-1111-2222-3333-444444444444");
        String taskId = "agentid-kill-" + UUID.randomUUID();
        // 长命令：保证调用 kill 时任务仍是 RUNNING（短命令会先完成 → 杀不到，断言失去意义）。
        String command = "echo agentid-kill-start; sleep 30";
        runner.spawn(newBashTaskOwnedBy(owner, taskId, command), command, SESSION);

        assertThat(runner.getTask(taskId))
            .as("spawn 必须同步登记任务（后续断言的前置条件）").isPresent();
        assertThat(runner.getTask(taskId).orElseThrow().status())
            .as("前置条件：任务此刻仍 RUNNING").isEqualTo(BackgroundTaskStatus.RUNNING);

        // 模拟「子代理先退出、这条通知已排队等着它 drain」的孤儿场景
        nq.enqueuePendingNotification(notificationAddressedTo(owner));

        int killed = runner.killShellTasksForAgent(owner);

        assertThat(killed)
            .as("归属该 agent 的 RUNNING shell 任务必须被真杀 —— 这是 ①② 生效后才可达的 CC 行为"
                + "（killShellTasks.ts:53-70，防 10 天僵尸进程）")
            .isEqualTo(1);
        assertThat(runner.getTask(taskId).orElseThrow().status())
            .as("被杀任务必须落到 KILLED 终态").isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(nq.peek(q -> owner.toString().equals(q.agentId())))
            .as("被杀任务 markKilled 会入队一条归属该 agent 的终止通知，且此前已有一条孤儿通知 —— "
                + "两条都必须被同一句 dequeueAllMatching 收回，否则队列里全是没人捞的孤儿"
                + "（CC killShellTasks.ts:71-75）")
            .isEmpty();
    }
}
