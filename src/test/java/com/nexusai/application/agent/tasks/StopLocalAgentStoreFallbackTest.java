package com.nexusai.application.agent.tasks;

import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 刀 3 · stopTask 的 local_agent「统一 store 回退」（与 dream / remote / monitor / teammate 四个回退同构）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这条回退为何重要：
 * <ul>
 *   <li>{@code listAllTasks()} 的查表范围 = 本地 {@code tasks} ∪ framework store，而 {@code stopTask}
 *       入口只在本地 {@code tasks} 里查（{@code tasks.get(taskId)}）⇒ **只注册进统一 store 的
 *       local_agent**（主会话后台化：{@code MainSessionBackgroundService.registerMainSessionTask}
 *       → {@code TaskFrameworkService.registerMainSessionTask}，不经 {@code spawn}）会
 *       「列得出来、点停止必 404」。</li>
 *   <li>前端 2s 轮询据此反复登记 ⇒ 该卡片成永久幽灵（用户报的「点停止、后端已 not found、前端还显示」）。</li>
 * </ul>
 *
 * <p><b>反向实验</b>：删掉 {@code stopTask} 里
 * {@code StopTaskResult localAgentResult = stopLocalAgentTask(taskId); ...} 那三行 ⇒ 本测试
 * 前两条断言从 NOT_RUNNING 变回 NOT_FOUND ⇒ 变红。
 *
 * <p><b>本刀做不到什么（如实登记，非静默）</b>：{@code killAsyncAgent} 以 runner 本地
 * {@code tasks} 地图为权威（首行 {@code tasks.get(taskId)}），而能走到本回退的任务按定义**不在**
 * 该地图 ⇒ store 报 running 时 {@code killAsyncAgent} 仍返回 false，本回退只能把「任务不存在」
 * （NOT_FOUND）纠正为「任务不可由 runner 停止」（NOT_RUNNING）。主会话后台化任务当前**没有可用的
 * 中断通道**（R-1 待办：{@code MainSessionBackgroundService.taskAbortSignals} 无消费点，
 * {@code ChatController} 调 {@code startBackgroundSession} 时 abortFlag=null）—— 见
 * {@code stopLocalAgentTask} 内的 WARN 日志与本次交付的 concerns。
 */
@DisplayName("[刀 3] stopTask ↔ 只注册在统一 store 的 local_agent（NOT_FOUND → 确定停止结果）")
class StopLocalAgentStoreFallbackTest {

    @BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    @TempDir
    Path tempDir;

    private record Ctx(BackgroundTaskRunner runner, TaskFrameworkService framework) {}

    private Ctx newCtx() {
        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        return new Ctx(new BackgroundTaskRunner(nq, service, sdk), service);
    }

    /** 构造一个「只进统一 store、不进 runner 本地 tasks」的 local_agent 任务（主会话后台化同形）。 */
    private BackgroundTask storeOnlyLocalAgent(String taskId, BackgroundTaskStatus status, String sessionId) {
        return new BackgroundTask(
            taskId, TaskType.LOCAL_AGENT, status,
            "主会话后台化", null,
            System.currentTimeMillis(), null, null,
            tempDir.resolve(taskId + ".output").toString(), 0L, false,
            null, true,          // agentId=null, isBackgrounded=true
            sessionId,           // 主会话后台化注册时**显式带会话**（MainSessionBackgroundService:161）
            null, null, "后台化 prompt", null, null, null);
    }

    @Test
    @DisplayName("store-only 的 running local_agent：stopTask 从 NOT_FOUND 变为确定结果（回退命中，不再 404）")
    void storeOnlyRunningLocalAgent_stopTask_noLongerNotFound() {
        Ctx ctx = newCtx();
        String taskId = "s-" + UUID.randomUUID();
        BackgroundTask storeOnly = storeOnlyLocalAgent(taskId, BackgroundTaskStatus.RUNNING, "sess-bg");
        ctx.framework().registerTask(storeOnly); // 只进统一 store

        // 前置旁证：runner 本地 tasks 里确实没有它（否则走的是 stopTask 主分支，不是本回退）
        assertThat(ctx.runner().getTask(taskId)).as("前置：本地 tasks 不应含该任务").isEmpty();
        assertThat(ctx.runner().listAllTasks()).as("前置：listAllTasks 合并视图要能列出它（否则不是幽灵）")
            .extracting(BackgroundTask::id).contains(taskId);

        BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask(taskId);

        // 改动前：本地 tasks 查不到 + 四个回退都不认 ⇒ NOT_FOUND（TaskController → 404）
        assertThat(result.errorCode())
            .as("store-only local_agent 必须被回退认领（NOT_FOUND = 回退缺失，即改动前的行为）")
            .isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING);
        assertThat(result.taskType()).as("task_type 用实际类型 local_agent").isEqualTo("local_agent");
        assertThat(result.command()).as("command 承载 description（CC stopTask.ts:97）").isEqualTo("主会话后台化");
    }

    @Test
    @DisplayName("store-only 的终态 local_agent：stopTask → NOT_RUNNING（任务存在但不可停，非 NOT_FOUND）")
    void storeOnlyTerminalLocalAgent_stopTask_returnsNotRunning() {
        Ctx ctx = newCtx();
        String taskId = "s-" + UUID.randomUUID();
        ctx.framework().registerTask(storeOnlyLocalAgent(taskId, BackgroundTaskStatus.COMPLETED, "sess-bg"));

        BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask(taskId);

        assertThat(result.errorCode())
            .as("终态 store-only 任务必须报「存在但非运行态」而不是「不存在」")
            .isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING);
        assertThat(result.taskType()).isEqualTo("local_agent");
    }

    @Test
    @DisplayName("回归：store / 本地都无此任务 ⇒ 仍是 NOT_FOUND（回退不越界）")
    void unknownTask_stillNotFound() {
        Ctx ctx = newCtx();
        BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask("no-such-task-at-all");
        assertThat(result.errorCode()).isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_FOUND);
        assertThat(result.taskType()).isNull();
    }

    @Test
    @DisplayName("回归：store 里是**非** local_agent 类型 ⇒ 本回退不认领（不误伤 monitor/teammate 等其它回退）")
    void storeTaskOfOtherType_notClaimedByLocalAgentFallback() {
        Ctx ctx = newCtx();
        String taskId = "x-" + UUID.randomUUID();
        BackgroundTask monitorLike = new BackgroundTask(
            taskId, TaskType.MONITOR_MCP, BackgroundTaskStatus.RUNNING,
            "监控任务", null, System.currentTimeMillis(), null, null,
            tempDir.resolve(taskId + ".output").toString(), 0L, false,
            null, true, "sess-bg", null, null, null, null, null, null);
        ctx.framework().registerTask(monitorLike);

        BackgroundTaskRunner.StopTaskResult result = ctx.runner().stopTask(taskId);

        // monitor 回退依赖 monitorMcpTaskRunner 未装配 ⇒ 返回 null；本刀只认 LOCAL_AGENT ⇒ 仍是 NOT_FOUND
        assertThat(result.errorCode())
            .as("类型守卫：本回退只认 local_agent（未装配 runner 时）")
            .isEqualTo(BackgroundTaskRunner.StopTaskErrorCode.NOT_FOUND);
    }
}
